package name.caiyao.fakegps.integration.v1

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.security.MessageDigest

/**
 * Production wiring for the v1 provider: the Android-backed implementations of
 * the seams declared in [IntegrationTypes], plus the single composition point
 * that assembles them into an [EnvironmentControlHandler].
 *
 * The unit lane composes the same graph over fakes in ProviderHarness. This file
 * is the production mirror of that assembly and deliberately contains no
 * behavior — every rule lives in the handler and its stores, which is what makes
 * them JVM-testable without an Android runtime.
 */

/**
 * SharedPreferences-backed [DurableKv].
 *
 * §6.6 bans multi-process direct writes, not any particular library. This store
 * is therefore MODE_PRIVATE (never MODE_MULTI_PROCESS) and the provider service
 * is declared without android:process so it runs in the main process — qwy's
 * `:hook_verify` process must never write these namespaces.
 *
 * commit() rather than apply(): the contract's durability claims (INV-25 L3/L4,
 * receipt+pointer in one transaction) are about state that survived, and apply()
 * returns before the write lands. A crash between an apply() and the disk is
 * exactly the torn state the crash matrix exists to forbid.
 */
class AndroidDurableKv(context: Context) : DurableKv {

    private val appContext = context.applicationContext
    private val lock = Any()

    private fun prefs(namespace: String) =
        appContext.getSharedPreferences("$PREFS_PREFIX$namespace", Context.MODE_PRIVATE)

    override fun read(namespace: String, key: String): String? =
        synchronized(lock) { prefs(namespace).getString(key, null) }

    @SuppressLint("ApplySharedPref")
    override fun write(namespace: String, key: String, value: String) {
        synchronized(lock) { prefs(namespace).edit().putString(key, value).commit() }
    }

    override fun keys(namespace: String): Set<String> =
        synchronized(lock) { prefs(namespace).all.keys.toSet() }

    /**
     * §6.6 L3 serialized read-modify-write. Single-process by construction, so a
     * monitor is sufficient and is what the lease/advance atomicity rules assume.
     */
    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }

    private companion object {
        const val PREFS_PREFIX = "qwy_env_control_v1_"
    }
}

/**
 * §6.4.2: elapsedRealtime is the only clock trust predicates may compare — it
 * does not jump when the user or NTP moves wall time, and it includes deep
 * sleep. epochMs is carried for audit rows only and must never reach a lease or
 * trust decision.
 */
class AndroidMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun epochMs(): Long = System.currentTimeMillis()
}

/**
 * PackageManager-backed identity resolution (§6.5.1).
 *
 * Fail-closed everywhere: an unresolvable package, an unreadable signature, or a
 * uid that maps to anything other than exactly one package yields null / an
 * empty digest set, and [CallerAuthorizer] turns that into CALLER_NOT_ALLOWED.
 * Returning a partially-trusted answer here would move an authorization decision
 * out of the authorizer, which is where the matrix tests can see it.
 */
class AndroidPackageIdentityResolver(context: Context) : PackageIdentityResolver {

    private val pm: PackageManager = context.applicationContext.packageManager

    override fun packagesForUid(uid: Int): List<String> =
        pm.getPackagesForUid(uid)?.toList().orEmpty()

    @Suppress("DEPRECATION")
    override fun signerLookup(applicationId: String): SignerLookup? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(
                applicationId,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signing = info.signingInfo
            if (signing == null) {
                null
            } else {
                // §6.5.1: the principal is the CURRENT signer, never "has ever
                // used". For a rotated package apkContentsSigners is the current
                // set; the historical chain is deliberately not consulted.
                val multiple = signing.hasMultipleSigners()
                val current = if (multiple) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
                        ?: signing.apkContentsSigners
                }
                SignerLookup(
                    currentSignerDigests = current.orEmpty().map { sha256(it.toByteArray()) },
                    hasMultipleSigners = multiple,
                    legacyApi = false,
                    versionCode = versionCodeOf(applicationId),
                )
            }
        } else {
            // API 24–27: GET_SIGNATURES cannot distinguish rotation from a
            // genuine signer change, so this path is marked legacy and the
            // authorizer applies its degraded fail-closed rules.
            val info = pm.getPackageInfo(applicationId, PackageManager.GET_SIGNATURES)
            val sigs = info.signatures.orEmpty()
            SignerLookup(
                currentSignerDigests = sigs.map { sha256(it.toByteArray()) },
                hasMultipleSigners = sigs.size > 1,
                legacyApi = true,
                versionCode = versionCodeOf(applicationId),
            )
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        // A dead PackageManager binder must not read as "signer verified".
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(applicationId: String): Long = try {
        val info = pm.getPackageInfo(applicationId, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Process-wide composition root.
 *
 * One handler per owner process: the lease machine, the continuity generation
 * and the idempotency scope are all per-owner concepts (§6.6 L6), so handing out
 * two handlers over one KV would manufacture the split-brain the single-writer
 * rule forbids.
 *
 * [onOwnerProcessStart] runs exactly once per process here rather than on every
 * bind, because a bind is not a restart — a second Auto connection must not look
 * like a new owner generation.
 */
object ProviderRuntime {

    @Volatile
    private var handlerRef: EnvironmentControlHandler? = null

    private val bootLock = Any()

    fun handler(context: Context): EnvironmentControlHandler {
        handlerRef?.let { return it }
        return synchronized(bootLock) {
            handlerRef ?: build(context.applicationContext).also { handlerRef = it }
        }
    }

    private fun build(appContext: Context): EnvironmentControlHandler {
        val kv = AndroidDurableKv(appContext)
        val clock = AndroidMonotonicClock()
        val resolver = AndroidPackageIdentityResolver(appContext)

        val pairing = DurablePairingStore(kv)
        val authorizer = CallerAuthorizer(resolver, pairing, clock)
        val tracker = ContinuityTracker(kv, clock)
        val leases = EnvironmentLeaseStore(kv, clock)
        val idempotency = DurableIdempotencyStore(kv)
        val audit = DurableIntegrationAuditStore(kv, clock)
        val environment = QwyEnvironmentController(appContext)
        val observer = EnvironmentObserver(tracker, environment, clock)

        val handler = EnvironmentControlHandler(
            authorizer = authorizer,
            pairingStore = pairing,
            leaseStore = leases,
            idempotency = idempotency,
            tracker = tracker,
            observer = observer,
            audit = audit,
            environment = environment,
            clock = clock,
            storage = kv,
        )

        // A provider process that starts without proof of a clean shutdown must
        // say so (§8.4): unclean restart moves ACTIVE leases to
        // RELEASE_INCOMPLETE rather than silently to EXPIRED, because the device
        // environment is unknown, not known-clean.
        handler.onOwnerProcessStart(cleanlinessProvable = CleanShutdownMarker.consume(kv))

        // §6.4 / M-RC-03: an unwired listener is the INV-08 false-trust case, so
        // wiring it is part of composition, not an optional extra.
        environment.setRelevantChangeListener { reason -> tracker.bump(reason) }

        return handler
    }

    /**
     * Clean-shutdown evidence. Written when the owner tears down in an orderly
     * way and consumed (cleared) on the next start, so "provable" means "this
     * exact marker survived and nothing else claimed it".
     */
    object CleanShutdownMarker {
        private const val NS = "runtime"
        private const val KEY = "clean_shutdown"

        fun record(kv: DurableKv) = kv.write(NS, KEY, "1")

        fun consume(kv: DurableKv): Boolean = kv.transaction {
            val present = kv.read(NS, KEY) == "1"
            kv.write(NS, KEY, "0")
            present
        }
    }
}

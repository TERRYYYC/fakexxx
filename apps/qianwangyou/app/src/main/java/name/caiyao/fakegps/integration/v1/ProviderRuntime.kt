package name.caiyao.fakegps.integration.v1

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.File
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

/*
 * There WAS a SharedPreferences-backed DurableKv here. It is gone rather than
 * deprecated, because leaving it importable leaves the bug importable.
 *
 * It kept one prefs file per namespace and implemented transaction() as a bare
 * monitor while each write committed on its own. That serializes callers but is
 * not atomic: a crash after the schedule pointer write and before the advance
 * receipt write left the torn state §6.7.5 forbids — and the crash matrix could
 * not see it, because the fake it runs on DOES buffer and roll back. The fake
 * was stronger than production, so the lane was green for a guarantee the device
 * never had.
 *
 * [FileDurableKv] replaces it and is held to that same contract by
 * DurableKvTransactionContractTest, which runs identical cases against both.
 */

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
 *
 * COVERAGE — two layers, and neither may be claimed as the other.
 *
 * 1. RETRIEVAL (this class): turning PackageManager into [RawSigningFacts].
 *    Uncovered by design, because the thing under test IS the Android API —
 *    rotation behavior, GET_SIGNATURES on 24–27, a dead binder. #7 instrumented
 *    acceptance owns it; §6.5.2 already admits controlled fixtures or injected
 *    SigningInfo as valid evidence.
 *
 * 2. DECISION ([SignerLookupPolicy]): which fact becomes the principal. Pure,
 *    and covered by SignerLookupPolicyTest.
 *
 * The split was not tidiness. While these were fused, this class took the
 * current signer from `signingCertificateHistory.takeLast(1)` while its own
 * comment claimed the history was never consulted — a §1682 violation sitting
 * inside a class nothing could test, contradicted by the comment above it.
 * Separating the decision made it a pure function, and a pure function can be
 * handed CONFLICTING sources and asked which one it really uses.
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
                // Retrieval only. Which of these becomes the principal is
                // SignerLookupPolicy's call, and the history is carried for
                // diagnostics rather than because anything may authorize on it.
                SignerLookupPolicy.resolve(
                    RawSigningFacts(
                        apkContentsSignerDigests =
                            signing.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) },
                        historyDigests =
                            signing.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) },
                        hasMultipleSigners = signing.hasMultipleSigners(),
                        legacyApi = false,
                        versionCode = versionCodeOf(applicationId),
                    )
                )
            }
        } else {
            // API 24–27: GET_SIGNATURES cannot distinguish rotation from a
            // genuine signer change, so this path is marked legacy and the
            // authorizer applies its degraded fail-closed rules.
            val info = pm.getPackageInfo(applicationId, PackageManager.GET_SIGNATURES)
            val sigs = info.signatures.orEmpty()
            SignerLookupPolicy.resolve(
                RawSigningFacts(
                    apkContentsSignerDigests = sigs.map { sha256(it.toByteArray()) },
                    // GET_SIGNATURES has no history to offer; the degraded path
                    // is flagged instead, and the authorizer applies its
                    // fail-closed legacy rules.
                    historyDigests = emptyList(),
                    hasMultipleSigners = sigs.size > 1,
                    legacyApi = true,
                    versionCode = versionCodeOf(applicationId),
                )
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

    /** Kept so orderly teardown can leave clean-shutdown evidence. */
    @Volatile
    private var kvRef: DurableKv? = null

    private val bootLock = Any()

    fun handler(context: Context): EnvironmentControlHandler {
        handlerRef?.let { return it }
        return synchronized(bootLock) {
            handlerRef ?: build(context.applicationContext).also { handlerRef = it }
        }
    }

    private fun build(appContext: Context): EnvironmentControlHandler {
        // FileDurableKv, not SharedPreferences: §6.7.5 needs the pointer and the
        // receipt to land or not land together, and a prefs-per-namespace store
        // commits each write on its own. See FileDurableKv's header.
        val kv = FileDurableKv(File(appContext.filesDir, "environment-control-v1"))
        kvRef = kv
        return compose(
            kv = kv,
            clock = AndroidMonotonicClock(),
            resolver = AndroidPackageIdentityResolver(appContext),
            environment = QwyEnvironmentController(appContext),
        )
    }

    /**
     * The composition itself, with every Android-bound choice already made by
     * the caller.
     *
     * Split out from [build] so the wiring can be asserted in a JVM lane. The
     * bug that motivated it was invisible while this was inline: composition
     * wired the §6.4 relevant-change listener a second time, on top of the one
     * onOwnerProcessStart already installs. With a stub adapter that merely
     * threw; with a real one it would have left two registrations racing to be
     * the survivor — a revision owner that looks wired either way.
     *
     * A reviewer caught that by reading the call chain. Reading is not a
     * regression test, so the wiring is now measured.
     */
    internal fun compose(
        kv: DurableKv,
        clock: MonotonicClock,
        resolver: PackageIdentityResolver,
        environment: QwyEnvironment,
    ): EnvironmentControlHandler {
        val pairing = DurablePairingStore(kv)
        val authorizer = CallerAuthorizer(resolver, pairing, clock)
        val tracker = ContinuityTracker(kv, clock)
        val leases = EnvironmentLeaseStore(kv, clock)
        val idempotency = DurableIdempotencyStore(kv)
        val audit = DurableIntegrationAuditStore(kv, clock)
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
        //
        // This call also wires the §6.4 relevant-change listener, because owner
        // start is exactly when the revision owner must begin observing. An
        // earlier version of this method wired it a SECOND time right here,
        // which was redundant at best and, once the adapter is real, would
        // replace the handler's own listener with an identical-looking one —
        // two registrations racing to be the survivor. Composition's job is to
        // call onOwnerProcessStart, not to re-do what it does.
        handler.onOwnerProcessStart(cleanlinessProvable = CleanShutdownMarker.consume(kv))

        return handler
    }

    /**
     * Called on orderly teardown of the provider service. Without this, [consume]
     * below can only ever answer false.
     *
     * That was the bug: record() existed with no call site, so
     * cleanlinessProvable was a constant false dressed up as a check. Every
     * start took the unclean path — ACTIVE leases to RELEASE_INCOMPLETE even
     * after a clean stop — while the code read as though §8.4's two cases were
     * both live. A permanently-unreachable branch is worse than a missing one:
     * it looks covered.
     *
     * A no-op when nothing has been composed yet: there is no owner state to
     * describe as cleanly stopped.
     */
    fun recordCleanShutdown() {
        synchronized(bootLock) {
            kvRef?.let { CleanShutdownMarker.record(it) }
        }
    }

    /**
     * Clean-shutdown evidence. Written when the owner tears down in an orderly
     * way and consumed (cleared) on the next start, so "provable" means "this
     * exact marker survived and nothing else claimed it".
     *
     * The asymmetry is deliberate and fail-closed: an orderly stop gets to leave
     * the marker, and anything else — process kill, low-memory reap, power loss
     * — simply does not, so the next start correctly reports unclean. There is
     * no reliable "process is exiting" callback on Android to lean on, and this
     * design does not need one.
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

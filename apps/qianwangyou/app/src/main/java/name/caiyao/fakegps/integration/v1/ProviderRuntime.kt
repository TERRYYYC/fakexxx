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
 * Production readiness gate for the process-global semantic writer lane.
 *
 * Installation is retryable because the system-server bridge can become
 * healthy after the provider composition root has already started. A fresh
 * session registration is published while fallback writers are excluded; on
 * owner start it is folded into the already-new generation, while a later
 * observation leaves the newer cursor for normal PRE/POST reconciliation to
 * account before FULL can return.
 */
internal class RetryingQwySemanticWriterReadiness(
    private val tracker: ContinuityTracker,
    private val environment: QwyEnvironment,
    private val authoritativeSource: AuthoritativeContinuitySource,
    private val expectedOracleOwnerPackage: String,
    private val expectedOracleOwnerUid: Int,
    private val semanticCoordinator: QwySemanticMutationCoordinator,
) : QwySemanticWriterReadiness, AutoCloseable {
    private var installation: AutoCloseable? = null

    @Synchronized
    override fun ensureReadyFor(semanticDigest: String): Boolean =
        ensureInstalled(semanticDigest, coalesceWithOwnerGeneration = false)

    @Synchronized
    fun installForOwnerStart(semanticDigest: String): Boolean =
        ensureInstalled(semanticDigest, coalesceWithOwnerGeneration = true)

    private fun ensureInstalled(
        semanticDigest: String,
        coalesceWithOwnerGeneration: Boolean,
    ): Boolean {
        if (semanticDigest.isBlank() ||
            environment.authoritativeSemanticDigest(tracker.generation) != semanticDigest
        ) {
            return false
        }
        if (QwySemanticWriterRuntime.isInstalledAndHealthyFor(semanticDigest)) return true
        if (QwySemanticWriterRuntime.canRepairExternalProjectionFor(semanticDigest)) {
            // PRE must fail closed for B, but the still-healthy A lane must live
            // long enough for FrameworkMockRefreshSession to fence B→A.
            return false
        }

        // A lane that lost its endpoint/session stays fail-closed for callers,
        // but this owner may retire and rebuild it at the next observation.
        installation?.close()
        installation = null

        val installed = runCatching {
            QwySemanticWriterRuntime.installWithExclusivePreparation(
                coordinator = semanticCoordinator,
                semanticDigestProvider = QwySemanticDigestProvider {
                    environment.authoritativeSemanticDigest(tracker.generation)
                },
                sessionHealth = QwySemanticSessionHealth(::hasHealthySnapshotFor),
                prepare = {
                    check(
                        environment.authoritativeSemanticDigest(tracker.generation) ==
                            semanticDigest,
                    ) { "canonical QWY digest changed before writer installation" }
                    val before = checkNotNull(
                        runCatching(authoritativeSource::snapshot).getOrNull(),
                    ) { "authoritative oracle unavailable before writer installation" }
                    val canAdopt = semanticCoordinator.isReadyFor(semanticDigest) &&
                        before.isStableCompleteFor(
                            expectedOracleOwnerPackage,
                            expectedOracleOwnerUid,
                        ) &&
                        before.qwySemanticDigest == semanticDigest &&
                        tracker.isAuthoritativeCursorAcknowledged(before)
                    if (canAdopt) return@installWithExclusivePreparation

                    check(before.isSemanticRegistrationBaselineFor(
                        expectedOracleOwnerPackage,
                        expectedOracleOwnerUid,
                        semanticDigest,
                    )) {
                        "authoritative oracle is not a safe writer-registration baseline"
                    }
                    check(
                        semanticCoordinator.registerCurrentSession(semanticDigest) is
                            QwySemanticSessionRegistration.Registered,
                    ) { "fresh QWY writer session registration failed" }
                    val after = checkNotNull(
                        runCatching(authoritativeSource::snapshot).getOrNull(),
                    ) { "authoritative oracle unavailable after writer registration" }
                    check(after.isStableCompleteFor(
                        expectedOracleOwnerPackage,
                        expectedOracleOwnerUid,
                    ) &&
                        after.bootId == before.bootId &&
                        after.oracleInstanceId == before.oracleInstanceId &&
                        after.sequence == before.sequence + 2L &&
                        after.qwySemanticDigest == semanticDigest
                    ) { "fresh QWY writer registration was not one exact boundary" }
                    if (coalesceWithOwnerGeneration) {
                        tracker.acknowledgeAuthoritativeOwnerGenerationBaseline(
                            snapshot = after,
                            expectedOwnerPackage = expectedOracleOwnerPackage,
                            expectedOwnerUid = expectedOracleOwnerUid,
                        )
                    } else {
                        tracker.acknowledgeAuthoritativeWriterRegistration(
                            before = before,
                            after = after,
                            expectedSemanticDigest = semanticDigest,
                            expectedOwnerPackage = expectedOracleOwnerPackage,
                            expectedOwnerUid = expectedOracleOwnerUid,
                        )
                    }
                    check(tracker.isAuthoritativeCursorAcknowledged(after)) {
                        "writer lane cannot publish before its exact cursor is durable ACK"
                    }
                },
            )
        }.getOrNull() ?: return false
        installation = installed
        return QwySemanticWriterRuntime.isInstalledAndHealthyFor(semanticDigest)
    }

    private fun hasHealthySnapshotFor(expectedDigest: String): Boolean =
        runCatching(authoritativeSource::snapshot).getOrNull()
            ?.takeIf {
                it.isStableCompleteFor(
                    expectedOracleOwnerPackage,
                    expectedOracleOwnerUid,
                )
            }
            ?.qwySemanticDigest == expectedDigest

    @Synchronized
    override fun close() {
        installation?.close()
        installation = null
    }
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

    /**
     * One process-lifetime graph. Startup is retryable on this exact object so a durable failure
     * after remote S+6 cannot manufacture another owner generation, Binder death token, endpoint
     * wrapper, or framework projection owner.
     */
    internal class ProcessScope(
        val kv: DurableKv,
        val tracker: ContinuityTracker,
        val handler: EnvironmentControlHandler,
        private val environment: QwyEnvironment,
        private val semanticWriterReadiness: QwySemanticWriterReadiness,
    ) {
        private var cleanlinessConsumed = false
        private var retainedCleanliness = false
        private var started = false
        private var writerInstallation: AutoCloseable? = null

        @Synchronized
        fun start(): EnvironmentControlHandler {
            if (started) return handler
            if (!cleanlinessConsumed) {
                retainedCleanliness = CleanShutdownMarker.consume(kv)
                cleanlinessConsumed = true
            }
            handler.onOwnerProcessStart(cleanlinessProvable = retainedCleanliness)
            val retryingReadiness = semanticWriterReadiness as?
                RetryingQwySemanticWriterReadiness
            if (retryingReadiness != null) {
                environment.authoritativeSemanticDigest(tracker.generation)?.let {
                    retryingReadiness.installForOwnerStart(it)
                }
                // Retain the retry owner even when this first attempt is safely unavailable.
                writerInstallation = retryingReadiness
            }
            started = true
            return handler
        }

        @Synchronized
        fun currentWriterInstallation(): AutoCloseable? = writerInstallation

        /** Only ephemeral/non-authoritative failed compositions may discard their graph. */
        fun abortDiscardedStart(startupFailure: Throwable) {
            if (tracker.activeAuthoritativeReservation() != null) return
            runCatching { writerInstallation?.close() }
            try {
                environment.abortOwnerStart()
            } catch (cleanupFailure: Throwable) {
                startupFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    @Volatile
    private var handlerRef: EnvironmentControlHandler? = null

    @Volatile
    private var processScopeRef: ProcessScope? = null

    /** Kept so orderly teardown can leave clean-shutdown evidence. */
    @Volatile
    private var kvRef: DurableKv? = null

    @Volatile
    private var semanticWriterInstallation: AutoCloseable? = null

    private val bootLock = Any()

    fun handler(context: Context): EnvironmentControlHandler {
        handlerRef?.let { return it }
        return synchronized(bootLock) {
            handlerRef ?: build(context.applicationContext).also { handlerRef = it }
        }
    }

    private fun build(appContext: Context): EnvironmentControlHandler {
        // Publish the process scope before starting it. If exact remote recovery reaches S+6 and
        // the following durable finalize/marker clear fails, the next Binder entry resumes this
        // same graph instead of constructing a new generation and aborting its live projection.
        val scope = processScopeRef ?: createProcessScope(
            // FileDurableKv, not SharedPreferences: §6.7.5 needs the pointer and receipt atomic.
            kv = FileDurableKv(File(appContext.filesDir, "environment-control-v1")),
            clock = AndroidMonotonicClock(),
            resolver = AndroidPackageIdentityResolver(appContext),
            environment = QwyEnvironmentController(appContext),
            authoritativeSource = BinderAuthoritativeContinuitySource(),
            expectedOracleOwnerPackage = appContext.packageName,
            expectedOracleOwnerUid = appContext.applicationInfo.uid,
            semanticCoordinator = QwySemanticMutationCoordinator(
                endpointProvider = BinderQwySemanticMutationEndpointProvider(),
                clientDeathTokenFactory = BinderQwySemanticClientDeathTokenFactory,
            ),
            installSemanticWriters = true,
        ).also {
            processScopeRef = it
            kvRef = it.kv
        }
        val handler = scope.start()
        semanticWriterInstallation = scope.currentWriterInstallation()
        return handler
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
        authoritativeSource: AuthoritativeContinuitySource = AuthoritativeContinuitySource { null },
        expectedOracleOwnerPackage: String = "",
        expectedOracleOwnerUid: Int = -1,
        semanticCoordinator: QwySemanticMutationCoordinator? = null,
        installSemanticWriters: Boolean = false,
    ): EnvironmentControlHandler {
        val scope = createProcessScope(
            kv = kv,
            clock = clock,
            resolver = resolver,
            environment = environment,
            authoritativeSource = authoritativeSource,
            expectedOracleOwnerPackage = expectedOracleOwnerPackage,
            expectedOracleOwnerUid = expectedOracleOwnerUid,
            semanticCoordinator = semanticCoordinator,
            installSemanticWriters = installSemanticWriters,
        )
        return try {
            scope.start()
        } catch (startupFailure: Throwable) {
            // This convenience composition is used by isolated JVM wiring tests. Production keeps
            // its ProcessScope in processScopeRef and retries it. A non-authoritative discarded
            // graph is still retired; an active reservation is never aborted outside its bracket.
            scope.abortDiscardedStart(startupFailure)
            throw startupFailure
        }
    }

    internal fun createProcessScope(
        kv: DurableKv,
        clock: MonotonicClock,
        resolver: PackageIdentityResolver,
        environment: QwyEnvironment,
        authoritativeSource: AuthoritativeContinuitySource = AuthoritativeContinuitySource { null },
        expectedOracleOwnerPackage: String = "",
        expectedOracleOwnerUid: Int = -1,
        semanticCoordinator: QwySemanticMutationCoordinator? = null,
        installSemanticWriters: Boolean = false,
    ): ProcessScope {
        val pairing = DurablePairingStore(kv)
        val authorizer = CallerAuthorizer(resolver, pairing, clock)
        val tracker = ContinuityTracker(kv, clock)
        val leases = EnvironmentLeaseStore(kv, clock)
        val idempotency = DurableIdempotencyStore(kv)
        val audit = DurableIntegrationAuditStore(kv, clock)
        val semanticWriterReadiness: QwySemanticWriterReadiness =
            if (installSemanticWriters && semanticCoordinator != null &&
                environment.authoritativeSemanticMutationEnabled()
            ) {
                RetryingQwySemanticWriterReadiness(
                    tracker = tracker,
                    environment = environment,
                    authoritativeSource = authoritativeSource,
                    expectedOracleOwnerPackage = expectedOracleOwnerPackage,
                    expectedOracleOwnerUid = expectedOracleOwnerUid,
                    semanticCoordinator = semanticCoordinator,
                )
            } else {
                // JVM/custom compositions still model the same proof without
                // installing a process-global production lane.
                QwySemanticWriterReadiness { expectedDigest ->
                    semanticCoordinator?.isReadyFor(expectedDigest) == true &&
                        runCatching(authoritativeSource::snapshot).getOrNull()
                            ?.takeIf {
                                it.isStableCompleteFor(
                                    expectedOracleOwnerPackage,
                                    expectedOracleOwnerUid,
                                )
                            }
                            ?.qwySemanticDigest == expectedDigest
                }
            }
        val observer = EnvironmentObserver(
            tracker,
            environment,
            clock,
            VerifiedObservationWatermarkStore(kv),
            authoritativeSource,
            expectedOracleOwnerPackage,
            expectedOracleOwnerUid,
            semanticWriterReadiness,
        )

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
            authoritativeSource = authoritativeSource,
            expectedOracleOwnerPackage = expectedOracleOwnerPackage,
            expectedOracleOwnerUid = expectedOracleOwnerUid,
            semanticCoordinator = semanticCoordinator,
        )

        return ProcessScope(
            kv = kv,
            tracker = tracker,
            handler = handler,
            environment = environment,
            semanticWriterReadiness = semanticWriterReadiness,
        )
    }

    /**
     * Callers that reached the provider, were resolved, and were refused for not
     * being paired (§6.5). Reading this is how an operator learns that anything
     * is waiting at all.
     *
     * [CallerAuthorizer] records the candidate BEFORE it throws NOT_PAIRED, so a
     * refusal is not a dead end — it leaves behind exactly the identity an
     * operator needs to judge. Without a way to read that list, the fail-closed
     * default is permanent: nothing can ever be approved, and every handshake
     * ends the same way forever.
     */
    fun pendingCallers(context: Context): List<PendingPairingCandidate> {
        handler(context) // boot if this is the first touch of the provider
        val kv = kvRef ?: return emptyList()
        // DurablePairingStore is a stateless view over the kv, so building one
        // here shares the single writer rather than forking a second one — a
        // second FileDurableKv over the same directory is the split-brain §6.6 L3
        // forbids, and it would not be visible until the two disagreed.
        return DurablePairingStore(kv).pendingCandidates()
    }

    /**
     * Operator approval of ONE named caller identity (§6.5 / §8.5).
     *
     * Both halves of the principal must be supplied and must match a recorded
     * candidate exactly. That is the whole point, not defensive coding: §6.5
     * forbids silent or automatic TOFU, and an "approve whatever is waiting"
     * entry point IS TOFU — it just moves the trust decision from the code to
     * whoever happens to call first. Requiring the signer digest means the
     * operator had to look at the identity being approved.
     *
     * Returns false when nothing matches, so approving a stale or mistyped
     * identity fails loudly instead of silently creating a pairing for a caller
     * that never appeared.
     */
    fun approveCaller(context: Context, applicationId: String, signerDigest: String): Boolean {
        handler(context)
        val kv = kvRef ?: return false
        val store = DurablePairingStore(kv)
        val candidate = store.pendingCandidates().firstOrNull {
            it.callerApplicationId == applicationId && it.currentSignerDigest == signerDigest
        } ?: return false
        store.approve(candidate, SystemClock.elapsedRealtime())
        return true
    }

    /**
     * A Service lifecycle boundary is not an owner/process shutdown boundary.
     * It can only revoke evidence, never mint it. A no-op before composition:
     * without a composed owner there is no in-process proof to invalidate.
     */
    fun invalidateCleanShutdownEvidence() {
        synchronized(bootLock) {
            kvRef?.let { CleanShutdownMarker.invalidate(it) }
        }
    }

    /**
     * Single-use evidence reserved for a future explicit protocol that first
     * proves the device-global owner and its refresh session are quiescent.
     *
     * Version 1 used Android Service.onDestroy as its producer. That callback is
     * not process-exit evidence, so those persisted markers are intentionally
     * ignored and cleared during migration. Until the explicit owner shutdown
     * protocol exists, production has no path that calls [record].
     */
    object CleanShutdownMarker {
        private const val NS = "runtime"
        private const val KEY = "quiescent_owner_shutdown_v2"
        private const val LEGACY_SERVICE_LIFECYCLE_KEY = "clean_shutdown"

        fun record(kv: DurableKv) = kv.transaction {
            kv.write(NS, LEGACY_SERVICE_LIFECYCLE_KEY, "0")
            kv.write(NS, KEY, "1")
        }

        fun invalidate(kv: DurableKv) = kv.transaction {
            kv.write(NS, LEGACY_SERVICE_LIFECYCLE_KEY, "0")
            kv.write(NS, KEY, "0")
        }

        fun consume(kv: DurableKv): Boolean = kv.transaction {
            val present = kv.read(NS, KEY) == "1"
            kv.write(NS, LEGACY_SERVICE_LIFECYCLE_KEY, "0")
            kv.write(NS, KEY, "0")
            present
        }
    }
}

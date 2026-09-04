package name.caiyao.fakegps.integration.v1.support

import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.CallerAuthorizer
import name.caiyao.fakegps.integration.v1.ContractException
import name.caiyao.fakegps.integration.v1.ContinuityTracker
import name.caiyao.fakegps.integration.v1.DurableIdempotencyStore
import name.caiyao.fakegps.integration.v1.DurableIntegrationAuditStore
import name.caiyao.fakegps.integration.v1.DurablePairingStore
import name.caiyao.fakegps.integration.v1.EnvironmentControlHandler
import name.caiyao.fakegps.integration.v1.EnvironmentLeaseStore
import name.caiyao.fakegps.integration.v1.EnvironmentObserver
import name.caiyao.fakegps.integration.v1.PendingPairingCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * Full provider composed over one durable KV.
 *
 * restart(...) rebuilds every component over the same KV — that IS the owner
 * process restart of §8.4/§6.6; reboot=true additionally resets the monotonic
 * clock epoch like a device boot (M-LS-13).
 */
class ProviderHarness private constructor(externalEnvStore: Boolean) {

    val kv = InMemoryDurableKv()
    val clock = FakeMonotonicClock()
    val resolver = FakeIdentityResolver()

    /**
     * F-15: survives restart() like the other harness fakes so a scenario can
     * assert the step-3b species log line emitted by the CURRENT handler
     * instance without mocking android.util.Log.
     */
    val diagnostics = RecordingDiagnosticLog()

    /**
     * The store the FAKE ENVIRONMENT persists its schedule state in.
     *
     * Default: the provider's own [kv] — convenient, but silently STRONGER
     * than production: provider receipt and qwy pointer share one transaction
     * buffer, so a cross-boundary torn state is unrepresentable (Terra PR#22
     * round-2). [createWithExternalEnvStore] gives the env its OWN store, which
     * is the real topology: qwy's schedule store does not share the provider's
     * commit — a §6.7.5 protocol that only works on the shared-store harness is
     * fake-green.
     */
    val envKv: InMemoryDurableKv = if (externalEnvStore) InMemoryDurableKv() else kv

    /**
     * Kv-backed fake (F-2): the env INSTANCE survives restart (config knobs and
     * call-counters are deliberately cross-restart instrumentation), but its
     * SCHEDULE state lives in [envKv] — restart drops every non-durable bit of
     * the system under test, so a memory-only pointer implementation cannot
     * pass the restart tests.
     */
    val env = FakeQwyEnvironment(envKv)

    lateinit var pairing: DurablePairingStore
        private set
    lateinit var authorizer: CallerAuthorizer
        private set
    lateinit var leases: EnvironmentLeaseStore
        private set
    lateinit var idempotency: DurableIdempotencyStore
        private set
    lateinit var tracker: ContinuityTracker
        private set
    lateinit var audit: DurableIntegrationAuditStore
        private set
    lateinit var observerComponent: EnvironmentObserver
        private set
    lateinit var handler: EnvironmentControlHandler
        private set

    companion object {
        const val AUTO_PKG = "come.xx.fakeaauto" // DP-2 frozen applicationId
        const val AUTO_SIGNER = "signer-auto-1"
        const val AUTO_UID = 10101
        const val OTHER_PKG = "com.other.caller"
        const val OTHER_SIGNER = "signer-other-1"
        const val OTHER_UID = 10202

        fun create(): ProviderHarness = build(externalEnvStore = false)

        /**
         * Env persists in its OWN store (the production topology): provider
         * receipt commit and qwy pointer mutation cross a real storage
         * boundary. Use for §6.7.5 cross-boundary atomicity tests — the
         * shared-store default cannot represent the torn state at all.
         */
        fun createWithExternalEnvStore(): ProviderHarness = build(externalEnvStore = true)

        private fun build(externalEnvStore: Boolean): ProviderHarness {
            val h = ProviderHarness(externalEnvStore)
            h.resolver.register(AUTO_UID, AUTO_PKG, AUTO_SIGNER)
            h.resolver.register(OTHER_UID, OTHER_PKG, OTHER_SIGNER)
            h.boot(cleanlinessProvable = true, firstBoot = true)
            return h
        }
    }

    private fun boot(cleanlinessProvable: Boolean, firstBoot: Boolean) {
        pairing = DurablePairingStore(kv)
        authorizer = CallerAuthorizer(resolver, pairing, clock)
        tracker = ContinuityTracker(kv, clock)
        leases = EnvironmentLeaseStore(kv, clock)
        idempotency = DurableIdempotencyStore(kv)
        audit = DurableIntegrationAuditStore(kv, clock)
        observerComponent = EnvironmentObserver(tracker, env, clock, audit)
        handler = EnvironmentControlHandler(
            authorizer = authorizer,
            pairingStore = pairing,
            leaseStore = leases,
            idempotency = idempotency,
            tracker = tracker,
            observer = observerComponent,
            audit = audit,
            environment = env,
            clock = clock,
            storage = kv,
            diagnostics = diagnostics,
        )
        if (!firstBoot) {
            handler.onOwnerProcessStart(cleanlinessProvable)
        }
    }

    /** Owner process restart (new generation). Durable state = whatever is in [kv]. */
    fun restart(cleanlinessProvable: Boolean, reboot: Boolean = false) {
        if (reboot) clock.simulateReboot()
        boot(cleanlinessProvable, firstBoot = false)
    }

    /** Operator approval simulation: pair the given caller identity. */
    fun pair(applicationId: String = AUTO_PKG, signerDigest: String = AUTO_SIGNER) {
        pairing.approve(
            PendingPairingCandidate(
                callerApplicationId = applicationId,
                currentSignerDigest = signerDigest,
                observedVersionCode = 1L,
                firstSeenAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            ),
            atElapsedRealtimeMs = clock.elapsedRealtimeMs(),
        )
    }

    fun intent(
        runId: String = "run-1",
        attemptId: String = "att-1",
        profileRef: String = "profile-1",
        // §6.3 / v1.72: scheduleRef is the schedule ITEM's stable reference —
        // NOT the schedule id. The fake schedule starts at "item-1", so the
        // default intent earns its lease ATTRIBUTED to the current item
        // (v1.75 step 3b: advancing an item other than the one the lease
        // earned quota for is wrong-item → STALE_LEASE(8)). The previous
        // default "sched-1" bound every default lease to a non-item ref —
        // exactly the mis-binding v1.72 warns "no gate or type system will
        // object to".
        scheduleRef: String = "item-1",
        requiredVerificationWire: Int = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs: Long = clock.epochMs() - 1_000L,
        deadlineInMs: Long = 600_000L,
    ): EnvironmentIntentV1 = EnvironmentIntentV1(
        runId = runId,
        attemptId = attemptId,
        profileRef = profileRef,
        scheduleRef = scheduleRef,
        requiredVerificationWire = requiredVerificationWire,
        notBeforeEpochMs = notBeforeEpochMs,
        deadlineEpochMs = clock.epochMs() + deadlineInMs,
    )

    fun apply(
        uid: Int = AUTO_UID,
        key: String = "apply-k1",
        intent: EnvironmentIntentV1 = intent(),
    ): ApplyReceiptV1 = handler.apply(uid, ApplyRequestV1(intent, key, 1))

    fun release(
        leaseId: String,
        uid: Int = AUTO_UID,
        key: String = "release-k1",
        operationId: String = "op-rel-1",
    ): ReleaseReceiptV1 = handler.release(uid, ReleaseRequestV1(leaseId, operationId, key))
}

/** Asserts the block fails with the given typed contract code. */
fun expectContractFailure(code: ContractErrorCodeV1, block: () -> Any?): ContractException {
    try {
        block()
    } catch (e: ContractException) {
        assertEquals("typed failure code", code, e.code)
        return e
    }
    fail("expected ContractException(${code.name}) but call succeeded")
    throw AssertionError("unreachable")
}

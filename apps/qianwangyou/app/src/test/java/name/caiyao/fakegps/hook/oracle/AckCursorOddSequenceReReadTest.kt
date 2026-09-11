package name.caiyao.fakegps.hook.oracle

import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.AdvancePointerOutcome
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySnapshot
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySource
import name.caiyao.fakegps.integration.v1.AuthoritativeObservationCommitStore
import name.caiyao.fakegps.integration.v1.AuthoritativeObservationCursor
import name.caiyao.fakegps.integration.v1.AuthoritativeOracleHealth
import name.caiyao.fakegps.integration.v1.CleanupOutcome
import name.caiyao.fakegps.integration.v1.DurablePairingStore
import name.caiyao.fakegps.integration.v1.PendingPairingCandidate
import name.caiyao.fakegps.integration.v1.ProviderRuntime
import name.caiyao.fakegps.integration.v1.QwyEnvironment
import name.caiyao.fakegps.integration.v1.support.FakeIdentityResolver
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.RecordingDiagnosticLog
import name.caiyao.fakegps.oracle.FakeBinder
import name.caiyao.fakegps.oracle.FakeOracleDeathLink
import name.caiyao.fakegps.oracle.IAuthoritativeContinuityOracle
import name.caiyao.fakegps.oracle.OracleBundleCodec
import name.caiyao.fakegps.oracle.OracleClientRegistry
import name.caiyao.fakegps.oracle.OracleRegistration
import name.caiyao.fakegps.oracle.OracleWireHealth
import name.caiyao.fakegps.oracle.OracleWireSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #173 face 3: the owner cursor ack's AFTER read can observe the oracle while
 * a covered platform mutation is still IN FLIGHT — its begin already ran
 * inside the bracket (nested, so the sequence has not finalized back to even)
 * while its finish callback is still queued (`scheduleCoveredMutationFinish`
 * is enqueue-only inside the framework locks). Device shape (mi14, attempt
 * 69): the release bracket's after-read hit the 1 Hz refresh delivery
 * mid-flight → sequence delta 1 ≠ own 0 → the ack skipped. Correct
 * (fail-closed) but permanently losing: nothing ever retries it.
 *
 * Fix: a BOUNDED re-read — while the sequence is odd, wait and read again (3
 * attempts, ~5 ms apart); a delivery that completes within that window lets
 * the sequence finalize and the guards judge a stable reading. A persistently
 * odd sequence still skips the ack — the pre-#173 semantics, never a guessed
 * ack.
 *
 * The in-flight window is modeled on the REAL producer state machine with the
 * begin landing INSIDE the owner bracket (during the environment call, where
 * the nested delivery actually runs) and the finish held back past the
 * handler's after-read — the exact begin/finish straddle the device produced.
 * Completed covered mutations are conservatively changed (a no-op delivery is
 * never journalled at all), so the resolve legs below always settle +2.
 */
class AckCursorOddSequenceReReadTest {

    /** AIDL-shaped delegate into the exact state owner the production Binder uses. */
    private class StateDelegatingOracle(
        private val state: SystemServerOracleState,
        private val binder: FakeBinder,
    ) : IAuthoritativeContinuityOracle {
        override fun snapshot() = null // Bundle transport stays device-side; the map seam is read here

        fun snapshotFields(): Map<String, Any?> = OracleBundleCodec.encodeFields(state.snapshot())

        override fun registerQwySession(semanticDigest: String?, clientDeathToken: android.os.IBinder?) {
            state.registerQwySession(semanticDigest, FakeSessionToken(requireNotNull(clientDeathToken)))
        }

        override fun beginQwySemanticMutation(
            mutationId: String?,
            beforeDigest: String?,
            clientDeathToken: android.os.IBinder?,
        ): Long = state.beginQwySemanticMutation(
            requireNotNull(mutationId),
            requireNotNull(beforeDigest),
            FakeSessionToken(requireNotNull(clientDeathToken)),
        )

        override fun finishQwySemanticMutation(
            token: Long,
            changed: Boolean,
            uncertain: Boolean,
            afterDigest: String?,
        ) = state.finishQwySemanticMutation(token, changed, uncertain, requireNotNull(afterDigest))

        override fun asBinder(): android.os.IBinder = binder
    }

    private class FakeSessionToken(private val binder: android.os.IBinder) :
        SystemServerOracleState.SessionToken {
        private val deaths = mutableListOf<Runnable>()

        override fun link(onDeath: Runnable) {
            deaths += onDeath
        }

        override fun unlink(onDeath: Runnable) {
            deaths.remove(onDeath)
        }

        override fun equals(other: Any?): Boolean =
            other is FakeSessionToken && other.binder == binder

        override fun hashCode(): Int = binder.hashCode()
    }

    private class Graph {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val envBase = FakeQwyEnvironment(kv)
        val diagnostics = RecordingDiagnosticLog()

        private val state = SystemServerOracleState(
            BOOT_ID, true, true,
            object : SystemServerOracleState.CallerIdentity {
                override fun uid() = QWY_UID
                override fun pid() = QWY_PID
            },
            Runnable { },
            SystemServerOracleState.EndpointReader {
                SystemServerOracleState.EndpointSample(QWY_UID, QWY_PACKAGE, true, true, true, null)
            },
        ).apply {
            configureExpectedQwyIdentity(QWY_UID, QWY_PACKAGE)
            markInstalled(
                Android15OracleHookPlan.COVERAGE_APP_OPS_WRAPPER or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_DELEGATE or
                    Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_LIFECYCLE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_PROVIDER_STATE or
                    Android15OracleHookPlan.COVERAGE_LOCATION_EFFECTIVE_ENABLED or
                    Android15OracleHookPlan.COVERAGE_LOCATION_SEMANTIC_COORDINATE,
            )
            onBridgeConnected(1)
        }

        // --- the in-flight covered mutation, begun INSIDE the owner bracket ---

        /** One-shot: the next advance/release environment call begins a delivery... */
        @Volatile var armInFlightOnNextAdvance = false
        @Volatile var armInFlightOnNextRelease = false

        /**
         * When > 0, the delivery's finish callback lands while the gate serves
         * its Nth read over the in-flight sequence (2 = the handler's first
         * re-read sees the finalized even cursor). 0 = the callback never
         * lands within the handler's window.
         */
        @Volatile var resolveAfterReads: Int = 0
        private var inFlightToken: Long? = null

        /** Oracle reads served while the delivery is unfinished (odd sequence). */
        @Volatile var readsWhileInFlight = 0
            private set

        /**
         * The pending delivery completes after the handler gave up. Completed
         * covered mutations are conservatively changed — same as the platform.
         */
        fun resolveInFlightDelivery() {
            val token = inFlightToken ?: return
            inFlightToken = null
            state.finishCoveredMutation(token, false)
        }

        val env = object : QwyEnvironment by envBase {
            override fun advancePointer(fromItemId: String): AdvancePointerOutcome {
                val outcome = envBase.advancePointer(fromItemId)
                if (armInFlightOnNextAdvance) {
                    armInFlightOnNextAdvance = false
                    inFlightToken = state.beginCoveredMutation(QWY_UID, QWY_PID, QWY_PACKAGE, null)
                }
                return outcome
            }

            override fun cleanup(leaseId: String): CleanupOutcome {
                val outcome = envBase.cleanup(leaseId)
                if (armInFlightOnNextRelease) {
                    armInFlightOnNextRelease = false
                    inFlightToken = state.beginCoveredMutation(QWY_UID, QWY_PID, QWY_PACKAGE, null)
                }
                return outcome
            }
        }

        private val oracle = StateDelegatingOracle(state, FakeBinder())
        private val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()

        val gate = object : AuthoritativeContinuitySource {
            override fun snapshot(): AuthoritativeContinuitySnapshot? {
                val token = inFlightToken
                if (token != null) {
                    readsWhileInFlight += 1
                    if (resolveAfterReads > 0 && readsWhileInFlight >= resolveAfterReads) {
                        inFlightToken = null
                        state.finishCoveredMutation(token, false)
                    }
                }
                return (registry.current() as? StateDelegatingOracle)
                    ?.let { o -> OracleBundleCodec.decodeFields(o.snapshotFields())?.toDomain() }
            }
        }

        val handler = ProviderRuntime.compose(
            kv = kv,
            clock = clock,
            resolver = FakeIdentityResolver().apply {
                register(ProviderHarness.AUTO_UID, ProviderHarness.AUTO_PKG, ProviderHarness.AUTO_SIGNER)
            },
            environment = env,
            authoritativeSource = gate,
            expectedOracleOwnerPackage = QWY_PACKAGE,
            expectedOracleOwnerUid = QWY_UID,
            oracleRegistry = registry,
            diagnostics = diagnostics,
        )

        val commitStore = AuthoritativeObservationCommitStore(kv)

        init {
            DurablePairingStore(kv).approve(
                PendingPairingCandidate(
                    callerApplicationId = ProviderHarness.AUTO_PKG,
                    currentSignerDigest = ProviderHarness.AUTO_SIGNER,
                    observedVersionCode = 1L,
                    firstSeenAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                ),
                atElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            )
            registry.register(1000, OracleRegistration(oracle, FakeOracleDeathLink()))
        }

        fun stableCursor(): AuthoritativeObservationCursor? {
            val snap = gate.snapshot() ?: return null
            val digest = snap.qwySemanticDigest ?: return null
            if (snap.sequence < 0L || snap.sequence and 1L != 0L) return null
            return AuthoritativeObservationCursor(
                bootId = snap.bootId,
                oracleInstanceId = snap.oracleInstanceId,
                sequence = snap.sequence,
                qwySemanticDigest = digest,
            )
        }

        fun apply(key: String): ApplyReceiptV1 {
            val intent = EnvironmentIntentV1(
                runId = "run-173-odd",
                attemptId = "att-$key",
                profileRef = "profile-1",
                scheduleRef = envBase.currentItemId ?: "item-1",
                requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                notBeforeEpochMs = clock.epochMs() - 1_000L,
                deadlineEpochMs = clock.epochMs() + 600_000L,
            )
            return handler.apply(ProviderHarness.AUTO_UID, ApplyRequestV1(intent, key, 1))
        }

        fun release(receipt: ApplyReceiptV1, key: String) {
            handler.release(
                ProviderHarness.AUTO_UID,
                ReleaseRequestV1(receipt.leaseId, "op-rel-$key", key),
            )
        }

        fun advance(receipt: ApplyReceiptV1, key: String, fromItemId: String) =
            handler.completeAndAdvance(
                ProviderHarness.AUTO_UID,
                run {
                    val bare = CompleteAndAdvanceRequestV1(
                        leaseId = receipt.leaseId,
                        idempotencyKey = key,
                        requestDigest = "",
                        expectedScheduleId = envBase.scheduleId,
                        expectedScheduleVersion = envBase.scheduleVersion,
                        expectedCurrentItemId = fromItemId,
                        completionProof = CompletionProofV1(
                            scheduleItemId = fromItemId,
                            trustedSuccessCount = 3,
                            quotaRequired = 3,
                            ledgerRef = "auto:ledger:173odd:$fromItemId",
                            verifiedAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                        ),
                        callerProtocolVersion = 1,
                    )
                    bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
                },
            )
    }

    private companion object {
        const val QWY_PACKAGE = "name.caiyao.fakegps.bench"
        const val QWY_UID = 10_321
        const val QWY_PID = 4321
        const val BOOT_ID = "6c6742ae-f815-4589-93cb-02b375d629be"
    }

    /**
     * Device shape (mi14 attempt 69): the bracket's after-read hits the
     * refresh delivery mid-flight (odd) and the delivery completes moments
     * later. The bounded re-read must recover the finalized even reading —
     * the deep-aggregated single +2 — and the ack must land.
     */
    @Test
    fun `odd after-sequence is re-read until even and the ack lands`() {
        val g = Graph()
        val receipt = g.apply("apply-odd")
        g.release(receipt, "release-odd")

        g.armInFlightOnNextAdvance = true
        g.resolveAfterReads = 2
        g.advance(receipt, "advmid", "item-1")

        assertEquals(
            "the after-read plus exactly one successful re-read",
            2,
            g.readsWhileInFlight,
        )
        val cursor = checkNotNull(g.stableCursor())
        assertNotNull(
            "an in-flight delivery that completes within the re-read bound must not cost the ack",
            g.commitStore.acknowledgement(cursor),
        )
    }

    /**
     * A delivery whose finish callback never lands within the bound keeps the
     * ack skipped — the pre-#173 fail-closed semantics — and the re-read stays
     * bounded: after-read + exactly 3 re-reads, no unbounded spinning on a
     * binder thread.
     */
    @Test
    fun `persistently odd after-sequence skips the ack within the bounded re-reads`() {
        val g = Graph()
        val receipt = g.apply("apply-stuck")
        g.release(receipt, "release-stuck")

        g.armInFlightOnNextAdvance = true
        g.advance(receipt, "advstuck", "item-1")

        // The pending delivery completes only AFTER the handler gave up.
        g.resolveInFlightDelivery()
        val cursor = checkNotNull(g.stableCursor())
        assertNull(
            "a persistently odd sequence must stay skipped, never guessed",
            g.commitStore.acknowledgement(cursor),
        )
        // The after-read plus the bounded re-reads (1 + 3) — then it stops.
        assertEquals(1 + 3, g.readsWhileInFlight)
        assertTrue(
            "the skip must stay observable with its reason",
            g.diagnostics.lines.any {
                it.contains("advance-advstuck") && it.contains("odd")
            },
        )
    }

    /**
     * The re-read is not a laundering step: a delivery completing inside the
     * window contributes its own conservative +2. Against a no-op bracket
     * (expected own delta 0) the sum is +2 — the delta guard must still
     * withhold the ack and the observe keeps reporting the change.
     */
    @Test
    fun `in-flight delivery completing as changed still withholds the no-op bracket ack`() {
        val g = Graph()
        val receipt = g.apply("apply-oddchanged")

        g.armInFlightOnNextRelease = true
        g.resolveAfterReads = 2
        g.release(receipt, "release-oddchanged")

        val cursor = checkNotNull(g.stableCursor())
        assertNull(
            "a re-read must not launder an in-flight delivery into a no-op bracket's ack",
            g.commitStore.acknowledgement(cursor),
        )
        assertTrue(
            g.diagnostics.lines.any {
                it.contains("release-${receipt.leaseId}") && it.contains("delta")
            },
        )
    }
}

private fun OracleWireSnapshot.toDomain(): AuthoritativeContinuitySnapshot =
    AuthoritativeContinuitySnapshot(
        protocolVersion, bootId, oracleInstanceId, sequence, ownerUid, ownerPackage,
        gpsProviderEnabled, networkProviderEnabled, requiredCoverageMask, installedCoverageMask,
        when (health) {
            OracleWireHealth.HEALTHY -> AuthoritativeOracleHealth.HEALTHY
            OracleWireHealth.BUILD_UNATTESTED -> AuthoritativeOracleHealth.BUILD_UNATTESTED
            OracleWireHealth.UNSUPPORTED_PLATFORM -> AuthoritativeOracleHealth.HOOKS_INCOMPLETE
            OracleWireHealth.BOOT_ID_UNAVAILABLE -> AuthoritativeOracleHealth.UNINITIALIZED
            OracleWireHealth.HOOKS_INCOMPLETE -> AuthoritativeOracleHealth.HOOKS_INCOMPLETE
            OracleWireHealth.BRIDGE_UNAVAILABLE -> AuthoritativeOracleHealth.SESSION_UNAVAILABLE
            OracleWireHealth.SESSION_UNAVAILABLE -> AuthoritativeOracleHealth.SESSION_UNAVAILABLE
            OracleWireHealth.ENDPOINT_UNAVAILABLE -> AuthoritativeOracleHealth.ENDPOINT_UNAVAILABLE
            OracleWireHealth.CALLBACK_POISONED -> AuthoritativeOracleHealth.SESSION_UNCERTAIN
            OracleWireHealth.INVARIANT_FAILURE -> AuthoritativeOracleHealth.INVARIANT_FAILED
        },
        qwySemanticDigest,
        lastCompletedQwyMutationId,
    )

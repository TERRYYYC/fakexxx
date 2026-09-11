package name.caiyao.fakegps.hook.oracle

import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySnapshot
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySource
import name.caiyao.fakegps.integration.v1.AuthoritativeObservationCommitStore
import name.caiyao.fakegps.integration.v1.AuthoritativeObservationCursor
import name.caiyao.fakegps.integration.v1.AuthoritativeOracleHealth
import name.caiyao.fakegps.integration.v1.DurablePairingStore
import name.caiyao.fakegps.integration.v1.PendingPairingCandidate
import name.caiyao.fakegps.integration.v1.ProviderRuntime
import name.caiyao.fakegps.integration.v1.support.FakeIdentityResolver
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.RecordingDiagnosticLog
import name.caiyao.fakegps.integration.v1.CleanupOutcome
import name.caiyao.fakegps.integration.v1.QwyEnvironment
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
import org.junit.Test

/**
 * #166: a contract op's own oracle cursor advance (the #155 semantic bracket,
 * +2 per changed mutation) must not be misreported by the next observe as an
 * EXTERNAL semantic change. The bump predicate
 * (`acknowledgement(cursor) == null && hasAcknowledgementForSourceEpoch(cursor)`)
 * bumps on the FIRST UNACKED cursor of a known source epoch — it cannot tell
 * who moved the cursor. So the advance chain died deterministically on device:
 * release bump + advance bump → receipt 224 → post-advance verify observe read
 * the advance's own new cursor → bump → 225 ≠ 224 → OBSERVED_TUPLE_MISMATCH →
 * RECOVERY_REQUIRED. 285 needs 50 row-boundary advances; the first one died.
 *
 * Fix direction A: when a bracketed semantic mutation completes cleanly, the
 * handler acknowledges the oracle cursor that its own bracket just produced
 * (into [AuthoritativeObservationCommitStore]) — the owner's own cursor advance
 * is not an external change, so the next observe does not bump.
 *
 * Full-chain lanes use the REAL producer state machine ([SystemServerOracleState])
 * behind the production composition root, exactly like
 * OracleSessionDriverIntegrationTest — a recording bracket fake cannot
 * reproduce this, because the misfire lives in the cursor the REAL bracket
 * moves.
 */
class ContractOpOwnCursorAckTest {

    /** What the gated source does on the next snapshot read. */
    private enum class SourceMode {
        /** Pass through unchanged. */
        PASS,

        /** Oracle unreadable (transient binder failure) for this read. */
        BLIND,

        /**
         * Return the real snapshot, but first complete a FOREIGN covered
         * platform mutation on the shared oracle — models a platform callback
         * landing after the handler's pre-bracket read but inside its bracket
         * window.
         */
        FOREIGN_INSIDE_OWNER_WINDOW,
    }

    /**
     * The ONE authoritative source instance shared by observer and handler, as
     * production composes it — with test-only fault/foreign-mutation injection.
     * The foreign injection fires ONCE per arming: it models a single platform
     * callback landing right after the handler's pre-bracket read.
     */
    private class GatedSource(
        private val delegate: () -> AuthoritativeContinuitySnapshot?,
        private val foreign: () -> Unit,
    ) : AuthoritativeContinuitySource {
        @Volatile var mode = SourceMode.PASS
        @Volatile var foreignInjected = 0

        override fun snapshot(): AuthoritativeContinuitySnapshot? {
            val own = delegate()
            when (mode) {
                SourceMode.PASS -> Unit
                SourceMode.BLIND -> return null
                SourceMode.FOREIGN_INSIDE_OWNER_WINDOW -> {
                    mode = SourceMode.PASS // inject once, then pass through
                    foreign()
                    foreignInjected += 1
                }
            }
            return own
        }
    }

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
        /** #167 review P2-2: strictly-nested foreign injection INSIDE the owner bracket. */
        @Volatile var nestedForeignArmed = false
        val env = object : QwyEnvironment by envBase {
            override fun cleanup(leaseId: String): CleanupOutcome {
                if (nestedForeignArmed) {
                    nestedForeignArmed = false
                    foreignCoveredMutation()
                }
                return envBase.cleanup(leaseId)
            }
        }
        val diagnostics = RecordingDiagnosticLog()

        /** The REAL producer state machine, wired exactly like the device Binder. */
        private val state = SystemServerOracleState(
            BOOT_ID, true, true,
            object : SystemServerOracleState.CallerIdentity {
                override fun uid() = QWY_UID
                override fun pid() = 4321
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

        /** Test-only foreign covered platform mutation (unattributed caller). */
        private fun foreignCoveredMutation() {
            val token = state.beginCoveredMutation(2000, 7000, "foreign.app", null)
            state.finishCoveredMutation(token, false)
        }

        private val oracle = StateDelegatingOracle(state, FakeBinder())
        private val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()

        val gate = GatedSource(
            delegate = {
                (registry.current() as? StateDelegatingOracle)
                    ?.let { o -> OracleBundleCodec.decodeFields(o.snapshotFields())?.toDomain() }
            },
            foreign = { foreignCoveredMutation() },
        )

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

        /** Read-side view over the SAME durable kv the composed store uses. */
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
            // The oracle arrives AFTER composition, like a real registrar bind.
            registry.register(1000, OracleRegistration(oracle, FakeOracleDeathLink()))
        }

        /** The stable even cursor an observer would bind right now, or null. */
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
                runId = "run-166",
                attemptId = "att-166",
                profileRef = "profile-1",
                scheduleRef = "item-1",
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

        fun advance(receipt: ApplyReceiptV1, key: String) =
            handler.completeAndAdvance(
                ProviderHarness.AUTO_UID,
                run {
                    val bare = CompleteAndAdvanceRequestV1(
                        leaseId = receipt.leaseId,
                        idempotencyKey = key,
                        requestDigest = "",
                        expectedScheduleId = envBase.scheduleId,
                        expectedScheduleVersion = envBase.scheduleVersion,
                        expectedCurrentItemId = "item-1",
                        completionProof = CompletionProofV1(
                            scheduleItemId = "item-1",
                            trustedSuccessCount = 3,
                            quotaRequired = 3,
                            ledgerRef = "auto:ledger:166:item-1",
                            verifiedAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                        ),
                        callerProtocolVersion = 1,
                    )
                    bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
                },
            )

        /** Observe an ACTIVE lease. */
        fun observeActive(receipt: ApplyReceiptV1, operationId: String) =
            handler.observe(
                ProviderHarness.AUTO_UID,
                ObserveRequestV1(receipt.leaseId, operationId, receipt.acceptedIntentHash),
            )

        /**
         * The §6.7.5 post-advance verification observe: the RELEASED historical
         * lease inside the wire-8 exception window the advance just opened.
         */
        fun observePostAdvance(receipt: ApplyReceiptV1, key: String) =
            observeActive(receipt, "post-advance-$key")
    }

    private companion object {
        const val QWY_PACKAGE = "name.caiyao.fakegps.bench"
        const val QWY_UID = 10_321
        const val BOOT_ID = "6c6742ae-f815-4589-93cb-02b375d629be"
    }

    /** The exact mi14 attempt-54 chain: apply → observe → release → advance → verify observe. */
    @Test
    fun `post-advance verify observe does not misreport the advance's own cursor advance`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-166")
        val first = g.observeActive(applyReceipt, "obs-166-1")
        assertEquals(ContinuityCoverageV1.FULL.wire, first.continuityCoverageWire)

        g.release(applyReceipt, "release-166")
        val advanceReceipt = g.advance(applyReceipt, "advance-166")
        val verify = g.observePostAdvance(applyReceipt, "advance-166")

        assertEquals(
            "the advance's own bracket must not surface as an external change on the verify observe",
            advanceReceipt.effectiveEnvironmentRevision,
            verify.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, verify.continuityCoverageWire)
    }

    @Test
    fun `apply and release each acknowledge the cursor their own bracket produced`() {
        val g = Graph()
        val apply1 = g.apply("apply-166-a")
        val cursorAfterApply = checkNotNull(g.stableCursor())
        val applyAck = checkNotNull(g.commitStore.acknowledgement(cursorAfterApply)) {
            "apply's own bracket cursor must be acknowledged at bracket completion"
        }
        assertEquals(
            "the ack binds the post-operation revision",
            apply1.environmentRevision,
            applyAck.localRevision,
        )

        // End-to-end: the post-apply observe of the op-produced cursor must
        // not bump (the ack must already exist before any observe ran).
        val observed = g.observeActive(apply1, "obs-166-a")
        assertEquals(
            "post-apply observe must read the receipt revision, not one higher",
            apply1.environmentRevision,
            observed.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)

        // The fake's cleanup is a PROVED no-op (unchanged cursor): the release
        // bracket still runs its fail-closed guard chain and the cursor stays
        // acknowledged afterwards.
        g.release(apply1, "release-166-a")
        val cursorAfterRelease = checkNotNull(g.stableCursor())
        assertEquals(cursorAfterApply.sequence, cursorAfterRelease.sequence)
        val releaseAck = checkNotNull(g.commitStore.acknowledgement(cursorAfterRelease)) {
            "the cursor must stay acknowledged across the release bracket"
        }
        assertEquals(
            "first-write-wins: the observe-time ack binding survives a later no-op bracket",
            applyAck,
            releaseAck,
        )
    }

    @Test
    fun `unreadable oracle at bracket completion stays fail-closed - observe still bumps`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-166-blind")
        g.observeActive(applyReceipt, "obs-166-blind-1")

        // Transient binder failure exactly while the handler tries to read the
        // cursor for its ack: the ack must be skipped (never faked), and the
        // next observe keeps today's conservative external-change behavior.
        g.release(applyReceipt, "release-166-blind")
        g.gate.mode = SourceMode.BLIND
        val advanceReceipt = g.advance(applyReceipt, "advance-166-blind")
        g.gate.mode = SourceMode.PASS

        val cursorAfter = checkNotNull(g.stableCursor())
        assertNull(
            "a skipped ack must not be pretended into the store",
            g.commitStore.acknowledgement(cursorAfter),
        )
        val verify = g.observePostAdvance(applyReceipt, "advance-166-blind")
        assertEquals(
            "fail-closed = current behavior: the unacked cursor still bumps",
            advanceReceipt.effectiveEnvironmentRevision + 1L,
            verify.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, verify.continuityCoverageWire)
    }

    @Test
    fun `foreign cursor advance inside the bracket window is not acknowledged`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-166-foreign")
        g.observeActive(applyReceipt, "obs-166-foreign-1")

        // A foreign covered platform mutation lands between the handler's
        // pre-bracket read and its own bracket: the sequence delta is no longer
        // the owner's own +2, so the ack must be withheld and the observe must
        // keep reporting the external change.
        g.release(applyReceipt, "release-166-foreign")
        g.gate.mode = SourceMode.FOREIGN_INSIDE_OWNER_WINDOW
        val advanceReceipt = g.advance(applyReceipt, "advance-166-foreign")
        g.gate.mode = SourceMode.PASS
        assertEquals(1, g.gate.foreignInjected)

        val cursorAfter = checkNotNull(g.stableCursor())
        assertNull(
            "a cursor carrying a foreign advance must not be owner-acknowledged",
            g.commitStore.acknowledgement(cursorAfter),
        )
        val verify = g.observePostAdvance(applyReceipt, "advance-166-foreign")
        assertEquals(
            "the foreign semantic change is still reported as a revision bump",
            advanceReceipt.effectiveEnvironmentRevision + 1L,
            verify.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, verify.continuityCoverageWire)
    }

    /**
     * #167 review P2-1/P2-2: a foreign covered mutation completing STRICTLY NESTED inside
     * the owner's bracket aggregates into the owner's own +2 — the sequence-delta guard
     * alone cannot distinguish it — but it also nulls lastCompletedQwyMutationId. The
     * correlation-id guard must withhold the ack for exactly this shape.
     */
    @Test
    fun `strictly nested foreign mutation nullifies correlation id and is not acknowledged`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-nested")
        g.observeActive(applyReceipt, "obs-nested")

        // Foreign covered mutation fires INSIDE release's own bracket (env.cleanup is the
        // block body): aggregated +2, lastCompletedQwyMutationId == null.
        g.nestedForeignArmed = true
        g.release(applyReceipt, "release-nested")
        assertEquals(false, g.nestedForeignArmed)

        val cursorAfter = checkNotNull(g.stableCursor())
        assertNull(
            "a strictly-nested foreign advance (null correlation id) must not be owner-acknowledged",
            g.commitStore.acknowledgement(cursorAfter),
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

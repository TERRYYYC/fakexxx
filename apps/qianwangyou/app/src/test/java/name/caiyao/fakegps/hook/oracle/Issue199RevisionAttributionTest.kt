package name.caiyao.fakegps.hook.oracle

import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySnapshot
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySource
import name.caiyao.fakegps.integration.v1.AuthoritativeObservationCommitStore
import name.caiyao.fakegps.integration.v1.AuthoritativeObservationCursor
import name.caiyao.fakegps.integration.v1.AuthoritativeOracleHealth
import name.caiyao.fakegps.integration.v1.CleanupOutcome
import name.caiyao.fakegps.integration.v1.DurablePairingStore
import name.caiyao.fakegps.integration.v1.EnvironmentControlHandler
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
import org.junit.Test

/**
 * #199 revision-bump race family (two forms, one root cause).
 *
 * Root cause: [EnvironmentObserver]'s AUTHORITATIVE_CURSOR_CHANGED catch-up bump
 * fires on ANY unacknowledged cursor of a known epoch. But the oracle state
 * machine guarantees (SystemServerOracleState.finishMutationLocked):
 *  - a covered PLATFORM mutation (MockProviderService 1 Hz refresh, LM calls)
 *    is conservatively journalled +2 WITHOUT touching qwySemanticDigest —
 *    digest-neutral cursor motion is NOT a QWY semantic change;
 *  - the qwySemanticDigest ONLY changes when an owner bracket publishes an
 *    afterDigest, and every owner bracket's operation bumps the tracker
 *    (apply/release/advance/restart) BEFORE the digest is published.
 * So an unacknowledged cursor is either digest-neutral (no semantic change) or
 * owner-accounted (the tracker already counted it) — the catch-up bump is a
 * DOUBLE COUNT in both cases, and Auto's frozen equality predicates convert it:
 *
 * Form 1 (mi14 attempt 398: OBSERVED_TUPLE_MISMATCH:environmentRevision at
 * PRE==POST=622; attempt 403 reproduced the same boundary shape in hook mode —
 * injection-independent; 386 = POST missing in the freeze family; the issue
 * body's "386/391" predates the DB audit — 391 was deleted by the plan2
 * surgery and has no row. devices.md e53cfd3d 2026-09-14 坑⑥): the
 * advance's own cursor ack is SKIPPED (odd sequence in flight / foreign
 * mutation in the window / unreadable after-read) — nothing ever retries it.
 * The post-advance observe then bumps → revision = receipt + 1 →
 * OBSERVED_TUPLE_MISMATCH:environmentRevision → RECOVERY_REQUIRED — the engine
 * task cursor rolls back while the provider pointer is durable-forward → every
 * boundary misaligned by +1 (quota burn loop, attempt surgery).
 *
 * Form 2 (attempt 399; same-shape rows 387/392 in the 17:49 DB audit;
 * 2026-09-14 #190 visual acceptance): digest-neutral
 * platform motion between the PRE and POST observe → POST bumps →
 * PRE 627 ≠ POST 628 → TrustPolicy FAIL → UNVERIFIED — quota burned although
 * coordinates, coverage, verification and intent hash were all verified.
 *
 * Fix under test: the observer ATTRIBUTES the cursor motion — digest unchanged
 * vs the highest acknowledged cursor, or a digest transition explained by a
 * durably recorded owner-mutation interval, is acknowledged WITHOUT bumping;
 * an unexplained digest change keeps the conservative bump (fail-closed).
 */
class Issue199RevisionAttributionTest {

    /** What the gated source does on the next snapshot read (mirrors #166's harness). */
    private enum class SourceMode {
        PASS,
        BLIND,
        FOREIGN_INSIDE_OWNER_WINDOW,
    }

    private class GatedSource(
        private val delegate: () -> AuthoritativeContinuitySnapshot?,
        private val foreign: () -> Unit,
    ) : AuthoritativeContinuitySource {
        @Volatile var mode = SourceMode.PASS

        override fun snapshot(): AuthoritativeContinuitySnapshot? {
            val own = delegate()
            when (mode) {
                SourceMode.PASS -> Unit
                SourceMode.BLIND -> return null
                SourceMode.FOREIGN_INSIDE_OWNER_WINDOW -> {
                    mode = SourceMode.PASS
                    foreign()
                }
            }
            return own
        }
    }

    private class StateDelegatingOracle(
        private val state: SystemServerOracleState,
        private val binder: FakeBinder,
    ) : IAuthoritativeContinuityOracle {
        override fun snapshot() = null

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

        /** Arms a strictly-nested foreign covered mutation INSIDE the release bracket. */
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

        /** Digest-neutral foreign covered platform mutation (LM/mock refresh shape). */
        private fun foreignCoveredMutation() {
            val token = state.beginCoveredMutation(2000, 7000, "foreign.app", null)
            state.finishCoveredMutation(token, false)
        }

        /**
         * A foreign covered platform mutation landing BETWEEN observations —
         * the attempt-399 shape: journalled +2 (platform calls are
         * conservatively changed) while qwySemanticDigest stays untouched, so
         * the next observe window itself is stable and can be FULL.
         */
        fun foreignPlatformMutationBetweenObservations() = foreignCoveredMutation()

        private val oracle = StateDelegatingOracle(state, FakeBinder())
        private val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()

        val gate = GatedSource(
            delegate = {
                (registry.current() as? StateDelegatingOracle)
                    ?.let { o -> OracleBundleCodec.decodeFields(o.snapshotFields())?.toDomain() }
            },
            foreign = { foreignCoveredMutation() },
        )

        val handler: EnvironmentControlHandler = ProviderRuntime.compose(
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
                runId = "run-199",
                attemptId = "att-199",
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
                            ledgerRef = "auto:ledger:199:item-1",
                            verifiedAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                        ),
                        callerProtocolVersion = 1,
                    )
                    bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
                },
            )

        fun observeActive(receipt: ApplyReceiptV1, operationId: String): EnvironmentObservationV1 =
            handler.observe(
                ProviderHarness.AUTO_UID,
                ObserveRequestV1(receipt.leaseId, operationId, receipt.acceptedIntentHash),
            )

        fun observePostAdvance(receipt: ApplyReceiptV1, key: String) =
            observeActive(receipt, "post-advance-$key")
    }

    // ---- Form 1: post-advance observe must read the receipt revision even when
    // the advance's own cursor ack was skipped. Every skip shape below is one of
    // the mi14 attempt-398/403 precursors ("nothing ever retried it"). ----

    @Test
    fun `form1 foreign platform mutation in the advance window - observe reads the receipt revision`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-199-f1a")
        g.observeActive(applyReceipt, "obs-199-f1a-pre")

        // The gate intercepts the bracket's BEFORE read, so the foreign mutation
        // lands between the pre-bracket baseline and the owner bracket: the
        // sequence delta is no longer the owner's own +2 and the ack skips.
        g.release(applyReceipt, "release-199-f1a")
        g.gate.mode = SourceMode.FOREIGN_INSIDE_OWNER_WINDOW
        val advanceReceipt = g.advance(applyReceipt, "advance-199-f1a")
        g.gate.mode = SourceMode.PASS

        val verify = g.observePostAdvance(applyReceipt, "advance-199-f1a")
        assertEquals(
            "#199 form1: the digest-neutral foreign motion plus the owner's own " +
                "advance (already revision-counted) must not double-count — the " +
                "post-advance observe must read the receipt revision or the engine " +
                "rolls its cursor back on a durable-forward provider",
            advanceReceipt.effectiveEnvironmentRevision,
            verify.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, verify.continuityCoverageWire)
        assertEquals(advanceReceipt.scheduleVersionAfter.toLong(), verify.scheduleVersion.toLong())
    }

    @Test
    fun `form1 unreadable oracle at the advance ack - observe reads the receipt revision`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-199-f1b")
        g.observeActive(applyReceipt, "obs-199-f1b-pre")

        // Transient binder failure exactly across the advance's bracket window:
        // the ack is skipped (never faked) with no retry — the #166 residual.
        g.release(applyReceipt, "release-199-f1b")
        g.gate.mode = SourceMode.BLIND
        val advanceReceipt = g.advance(applyReceipt, "advance-199-f1b")
        g.gate.mode = SourceMode.PASS

        val verify = g.observePostAdvance(applyReceipt, "advance-199-f1b")
        assertEquals(
            "#199 form1: a skipped ack with a cleanly published owner digest must be " +
                "attributed at the next observe, not double-counted",
            advanceReceipt.effectiveEnvironmentRevision,
            verify.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, verify.continuityCoverageWire)
    }

    @Test
    fun `form1 release and advance acks both skip - the digest interval chain explains the transition`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-199-f1c")
        g.observeActive(applyReceipt, "obs-199-f1c-pre")

        // Skip shape 1: a strictly-nested foreign mutation inside the release
        // bracket nulls the correlation id → release ack skips.
        g.nestedForeignArmed = true
        g.release(applyReceipt, "release-199-f1c")
        // Skip shape 2: the advance's ack after-read is blind.
        g.gate.mode = SourceMode.BLIND
        val advanceReceipt = g.advance(applyReceipt, "advance-199-f1c")
        g.gate.mode = SourceMode.PASS

        val verify = g.observePostAdvance(applyReceipt, "advance-199-f1c")
        assertEquals(
            "#199 form1: two consecutive skipped acks must be explained by the " +
                "recorded owner digest intervals, not surfaced as an external change",
            advanceReceipt.effectiveEnvironmentRevision,
            verify.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, verify.continuityCoverageWire)
    }

    // ---- Form 2: digest-neutral platform motion between PRE and POST observe
    // must not move the reported revision (attempt 399: PRE 627 / POST 628). ----

    @Test
    fun `form2 foreign platform motion between PRE and POST - revision stays stable`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-199-f2")
        val pre = g.observeActive(applyReceipt, "obs-199-f2-pre")
        assertEquals(ContinuityCoverageV1.FULL.wire, pre.continuityCoverageWire)

        // The system_mock 1 Hz refresh / route playback shape: platform calls
        // journalled conservatively (+2, digest untouched) while the engine is
        // inside the attempt window.
        g.foreignPlatformMutationBetweenObservations()
        val post = g.observeActive(applyReceipt, "obs-199-f2-post")

        assertEquals(
            "#199 form2: a digest-neutral platform mutation is NOT a QWY semantic " +
                "change — the POST observe must keep the PRE revision or TrustPolicy " +
                "burns the attempt as UNVERIFIED despite a fully verified environment",
            pre.environmentRevision,
            post.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, post.continuityCoverageWire)
    }

    @Test
    fun `form2 repeated foreign motion across several observations - revision monotonic-stable`() {
        val g = Graph()
        val applyReceipt = g.apply("apply-199-f2b")
        val pre = g.observeActive(applyReceipt, "obs-199-f2b-pre")

        g.foreignPlatformMutationBetweenObservations()
        val mid = g.observeActive(applyReceipt, "obs-199-f2b-mid")
        g.foreignPlatformMutationBetweenObservations()
        val post = g.observeActive(applyReceipt, "obs-199-f2b-post")

        assertEquals(pre.environmentRevision, mid.environmentRevision)
        assertEquals(
            "#199 form2: any amount of digest-neutral cursor backlog must consume as " +
                "bookkeeping, never as a semantic revision bump",
            pre.environmentRevision,
            post.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, post.continuityCoverageWire)
    }

    private companion object {
        const val QWY_PACKAGE = "name.caiyao.fakegps.bench"
        const val QWY_UID = 10_321
        const val QWY_PID = 4321
        const val BOOT_ID = "6c6742ae-f815-4589-93cb-02b375d629be"
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

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168: the contract apply moves the effective schedule item, but the LEGACY
 * publish chains do not follow it by themselves — the anchored profile
 * (publish_state's activeProfileId), the spoof_config payload published from
 * it (the hook projection CellRebel sees), and MockProviderMain's 1 Hz refresh
 * (which re-delivers that same payload to the system mock provider) all keep
 * the PREVIOUS row. The field evidence (mi14): contract effective = row3
 * Odesa, MockProviderMain = row2 Kharkiv, spoof_config = row1-era Kyiv — the
 * per-second stale re-delivery reads as a covered semantic change, the oracle
 * cursor keeps +2ing, every attempt's PRE/POST window drifts and fails closed.
 * Manually re-aligning the anchor restored PASS immediately — the chains agree,
 * the windows go clean.
 *
 * Fix: apply's bracket drives the legacy chains to the APPLIED item — the
 * [FakeQwyEnvironment] models the anchor durably and its publish counters make
 * the idempotency gate (same-item replay stays publish-silent) and the honest
 * partial (a failed republish never fails the authoritative apply) visible.
 * The oracle Graph at the bottom pins that the linkage stays inside the
 * owner's own #166/#167 ack window.
 */
class ApplyLegacyRepublishTest {

    // ---- the row-boundary loop over the full harness (no oracle needed) ----

    /**
     * The mi14 loop: apply → release → completeAndAdvance → apply. The advance
     * moves ONLY the pointer; the SECOND apply is where the legacy chains must
     * follow to the new item (RED before the fix: they stayed on the old one).
     */
    @Test
    fun `apply to the next item drives the legacy chains to the applied item`() {
        val h = ProviderHarness.create()
        h.pair()
        // The chains reflect item-1 — the post-boundary field state (#168's
        // table: contract effective row3, chains still on older rows).
        h.env.legacyAnchoredItemId = "item-1"

        val first = h.apply(key = "apply-row1")
        h.release(first.leaseId)
        advanceCommitted(h, first.leaseId, "item-1")
        assertEquals("item-2", h.env.currentItemId)

        h.apply(key = "apply-row2")

        assertEquals(
            "#168: apply must re-anchor the legacy chains at the applied item",
            "item-2",
            h.env.legacyAnchoredItemId,
        )
        assertEquals(
            "the published payload must carry the applied item's row",
            h.env.itemCoordinates["item-2"],
            h.env.legacyProjectedCoordinates,
        )
        assertEquals(
            "contract effective and the legacy chains must agree on one coordinate",
            h.env.let { e -> e.observeEffective().latitude to e.observeEffective().longitude },
            h.env.legacyProjectedCoordinates,
        )
    }

    /**
     * The idempotency gate: a same-item apply replay (new attempt, new
     * idempotency key) must stay publish-silent — the effective did not change,
     * so neither the anchored profile nor the payload may be rewritten.
     */
    @Test
    fun `same-item apply replay does not republish the legacy chains`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.legacyAnchoredItemId = "item-1"

        val first = h.apply(key = "apply-a")
        assertEquals(
            "an aligned item must not be republished",
            0,
            h.env.legacyRepublishCount,
        )
        h.release(first.leaseId, key = "release-a")
        advanceCommitted(h, first.leaseId, "item-1")

        val second = h.apply(key = "apply-b")
        assertEquals("item-2", h.env.legacyAnchoredItemId)
        assertEquals("exactly one publish for the row boundary", 1, h.env.legacyRepublishCount)

        // New attempt on the SAME item: the chains already carry item-2.
        h.release(second.leaseId, key = "release-b")
        h.apply(key = "apply-c")
        assertEquals("a same-item replay must stay publish-silent", 1, h.env.legacyRepublishCount)
        assertEquals("item-2", h.env.legacyAnchoredItemId)
    }

    /**
     * Honest partial: a failed legacy republish never fails the apply — the
     * contract chain (lease, receipt, effective) is authoritative — but the
     * failure must be WARN-observable and the chains must honestly still lag
     * (the split keeps observations fail-closed until they realign).
     */
    @Test
    fun `failed legacy republish keeps the apply successful and warns`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.legacyAnchoredItemId = "item-1"
        val first = h.apply(key = "apply-a")
        h.release(first.leaseId)
        advanceCommitted(h, first.leaseId, "item-1")

        h.env.failNextLegacyRepublish = true
        val receipt = h.apply(key = "apply-b")

        assertNotNull("the authoritative apply must still succeed", receipt.leaseId)
        assertEquals(
            "the receipt level measures the contract publish chain, not the legacy linkage",
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            receipt.verificationLevelWire,
        )
        assertTrue(
            "the linkage failure must be observable at the moment it is created",
            h.diagnostics.lines.any {
                it.contains("#168") && it.contains("apply-${receipt.leaseId}") && it.contains("failed")
            },
        )
        assertEquals("the chains honestly still lag", "item-1", h.env.legacyAnchoredItemId)
    }

    /**
     * Advance semantics pinned: the pointer move is NOT the environment — the
     * next apply is. An advance alone must never touch the legacy chains.
     */
    @Test
    fun `advance alone moves the pointer but never the legacy chains`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.legacyAnchoredItemId = "item-1"
        val receipt = h.apply(key = "apply-a")
        assertEquals(1, h.env.legacyAnchorSyncCount) // apply asked once; aligned → silent
        h.release(receipt.leaseId)

        advanceCommitted(h, receipt.leaseId, "item-1")

        assertEquals("item-2", h.env.currentItemId)
        assertEquals(
            "the pointer moved, the chains did not — the next apply drives them",
            "item-1",
            h.env.legacyAnchoredItemId,
        )
        assertEquals(0, h.env.legacyRepublishCount)
        assertEquals("advance must not even ask the linkage seam", 1, h.env.legacyAnchorSyncCount)
    }

    // ---- the oracle Graph: the linkage must stay inside the owner's own ack window ----

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

    /**
     * The ContractOpOwnCursorAckTest Graph, reused verbatim: the REAL producer
     * state machine behind the production composition root. The linkage runs
     * inside the apply bracket, so this pins the #166/#167 interaction the fix
     * must not disturb: the bracket's own +2 (its publish chain) stays exactly
     * one owner advance, acknowledged, and the post-apply observe reads the
     * receipt revision — the payload write adds no oracle-visible mutation.
     */
    private class Graph {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val env = FakeQwyEnvironment(kv)
        val diagnostics = RecordingDiagnosticLog()

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

        private val oracle = StateDelegatingOracle(state, FakeBinder())
        private val registry = OracleClientRegistry<IAuthoritativeContinuityOracle>()

        private val gate = object : AuthoritativeContinuitySource {
            override fun snapshot(): AuthoritativeContinuitySnapshot? =
                (registry.current() as? StateDelegatingOracle)
                    ?.let { o -> OracleBundleCodec.decodeFields(o.snapshotFields())?.toDomain() }
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

        fun apply(key: String, scheduleRef: String): ApplyReceiptV1 {
            val intent = EnvironmentIntentV1(
                runId = "run-168",
                attemptId = "att-$key",
                profileRef = "profile-1",
                scheduleRef = scheduleRef,
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
                        expectedScheduleId = env.scheduleId,
                        expectedScheduleVersion = env.scheduleVersion,
                        expectedCurrentItemId = fromItemId,
                        completionProof = CompletionProofV1(
                            scheduleItemId = fromItemId,
                            trustedSuccessCount = 3,
                            quotaRequired = 3,
                            ledgerRef = "auto:ledger:168:$fromItemId",
                            verifiedAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
                        ),
                        callerProtocolVersion = 1,
                    )
                    bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
                },
            )

        fun observeActive(receipt: ApplyReceiptV1, operationId: String) =
            handler.observe(
                ProviderHarness.AUTO_UID,
                ObserveRequestV1(receipt.leaseId, operationId, receipt.acceptedIntentHash),
            )
    }

    /**
     * Both row-boundary applies carry a REAL legacy republish inside their
     * bracket (first apply: never-anchored chains; second apply: the chains lag
     * item-1) — and each still owns exactly its own +2: acknowledged, and the
     * post-apply observe reads the receipt revision with FULL coverage.
     */
    @Test
    fun `apply-side legacy linkage stays inside the owner cursor ack window`() {
        val g = Graph()

        val first = g.apply("apply-168-a", "item-1")
        val cursorAfterFirst = checkNotNull(g.stableCursor())
        assertNotNull(
            "the linkage-bearing apply still owns its own +2",
            g.commitStore.acknowledgement(cursorAfterFirst),
        )
        val observedFirst = g.observeActive(first, "obs-168-a")
        assertEquals(ContinuityCoverageV1.FULL.wire, observedFirst.continuityCoverageWire)

        g.release(first, "release-168-a")
        g.advance(first, "advance-168-a", "item-1")

        val second = g.apply("apply-168-b", "item-2")
        assertEquals(
            "the second apply re-anchored the lagging chains",
            "item-2",
            g.env.legacyAnchoredItemId,
        )
        val observedSecond = g.observeActive(second, "obs-168-b")
        assertEquals(
            "the linkage must add no oracle-visible mutation of its own",
            second.environmentRevision,
            observedSecond.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, observedSecond.continuityCoverageWire)
    }

    /** The committed advance of the mi14 loop (release must come first). */
    private fun advanceCommitted(h: ProviderHarness, leaseId: String, fromItemId: String) {
        val bare = CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = "advance-168-$fromItemId",
            requestDigest = "",
            expectedScheduleId = h.env.scheduleId,
            expectedScheduleVersion = h.env.scheduleVersion,
            expectedCurrentItemId = fromItemId,
            completionProof = CompletionProofV1(
                scheduleItemId = fromItemId,
                trustedSuccessCount = 3,
                quotaRequired = 3,
                ledgerRef = "auto:ledger:168:$fromItemId",
                verifiedAtElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
            ),
            callerProtocolVersion = 1,
        )
        h.handler.completeAndAdvance(
            ProviderHarness.AUTO_UID,
            bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare)),
        )
    }

    private companion object {
        const val QWY_PACKAGE = "name.caiyao.fakegps.bench"
        const val QWY_UID = 10_321
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

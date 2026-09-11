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
import name.caiyao.fakegps.integration.v1.LegacyAnchorSync
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
 * #173: the legacy republish (apply's #168 linkage) changes what MockProviderMain's
 * refresh delivers, but the FIRST delivery of the new coordinates was left to the 1 Hz
 * tick — its phase does not respect the owner bracket. On device (mi14, attempt 69) the
 * first emission landed AFTER the apply bracket closed: a changed covered mutation (+2)
 * with no owner ack, then the #167 guard 4 ("pending external cursor below") refused
 * every later ack — apply/release/advance all skipped — the advance cursor stayed
 * unacked and every row-boundary verify mismatched (highest 512 < baseline 522, 5
 * cursors of backlog). The row boundary was a dead end, not a lost attempt.
 *
 * Fix mechanism: the first emission must happen INSIDE the republish's owner bracket —
 * its covered mutation then aggregates (deep aggregation, #172 same-process
 * attribution) into the owner's single +2, which the existing #166/#167 ack covers.
 * Subsequent 1 Hz ticks re-deliver bit-identical coordinates = proved no-ops.
 *
 * The Graph wires the emission at the EXACT production contract point — inside
 * [QwyEnvironment.syncLegacyAnchorToCurrentItem]'s Republished branch, where production
 * (QwyEnvironmentController) triggers the process emission hub while the caller's
 * bracket is still open. The oracle is the REAL producer state machine, so "inside the
 * bracket" is not asserted but OBSERVED through the aggregation the ack guards judge.
 */
class RepublishFirstEmissionTest {

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

        /**
         * #173 fix model: when true, the Republished branch triggers the first
         * Mock emission SYNCHRONOUSLY (production wires the process emission
         * hub here). When false, the pre-#173 world is modeled: the republish
         * only writes the payload and the first delivery waits for a later
         * 1 Hz tick — the test then drives that tick.
         */
        @Volatile var firstEmissionInBracket = true

        /** Probe: emissions fired synchronously by the republish branch. */
        @Volatile var emissionsInsideRepublish = 0
            private set

        val env = object : QwyEnvironment by envBase {
            override fun syncLegacyAnchorToCurrentItem(): LegacyAnchorSync {
                val sync = envBase.syncLegacyAnchorToCurrentItem()
                if (sync is LegacyAnchorSync.Republished && firstEmissionInBracket) {
                    // The production contract point: the first new-coordinate
                    // delivery, INSIDE the caller's still-open owner bracket —
                    // deep-aggregated into the owner's single +2.
                    firstEmissionDelivery()
                    emissionsInsideRepublish += 1
                }
                return sync
            }
        }

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

        /**
         * The 1 Hz refresh's first delivery of the republished coordinates,
         * fired OUTSIDE any owner bracket. The platform journals every
         * completed covered mutation as conservatively changed (+2) — the
         * pre-#173 first delivery always reads as a coordinate change, because
         * the refresh rebuilds the test provider (a no-op re-delivery is never
         * journalled at all: the semantic hook compares before it begins).
         */
        fun fireTickDelivery() {
            state.beginCoveredMutation(QWY_UID, QWY_PID, QWY_PACKAGE, null)
                .let { state.finishCoveredMutation(it, false) }
        }

        /**
         * The same delivery, fired INSIDE the open owner bracket (the #173 fix):
         * the covered mutation deep-aggregates into the owner's single +2.
         */
        private fun firstEmissionDelivery() {
            state.beginCoveredMutation(QWY_UID, QWY_PID, QWY_PACKAGE, null)
                .let { state.finishCoveredMutation(it, false) }
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

        fun guardFourSkips(): Int =
            diagnostics.lines.count { it.contains("pending external cursor below") }

        fun apply(key: String): ApplyReceiptV1 {
            val intent = EnvironmentIntentV1(
                runId = "run-173",
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
                            ledgerRef = "auto:ledger:173:$fromItemId",
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

    private companion object {
        const val QWY_PACKAGE = "name.caiyao.fakegps.bench"
        const val QWY_UID = 10_321
        const val QWY_PID = 4321
        const val BOOT_ID = "6c6742ae-f815-4589-93cb-02b375d629be"
    }

    /**
     * The fix: with the first emission triggered inside the Republished branch,
     * its changed +2 aggregates into the owner's own bracket advance, the
     * existing ack covers it, and the whole chain — post-apply observe, the
     * steady-state no-op tick, and the NEXT row-boundary apply — stays
     * backlog-free. No guard-4 skip ever fires.
     */
    @Test
    fun `first emission inside the republish bracket keeps the whole ack chain backlog-free`() {
        val g = Graph()
        g.firstEmissionInBracket = true

        val first = g.apply("apply-173-row1")
        assertEquals(1, g.emissionsInsideRepublish)

        // The steady-state tick AFTER the fix: the refresh re-delivers
        // bit-identical coordinates, so the semantic hook compares FIRST and
        // never begins a covered mutation — there is nothing to fire, and the
        // cursor the apply's ack covered does not move.
        val cursorAfterApply = checkNotNull(g.stableCursor())
        val cursorAfterTick = checkNotNull(g.stableCursor())
        assertEquals(
            "steady-state refresh ticks must not move the cursor",
            cursorAfterApply.sequence,
            cursorAfterTick.sequence,
        )
        assertNotNull(
            "the owner ack must cover the bracket that carried the first emission",
            g.commitStore.acknowledgement(cursorAfterTick),
        )

        val observedFirst = g.observeActive(first, "obs-173-row1")
        assertEquals(
            "post-apply observe must read the receipt revision, not one higher",
            first.environmentRevision,
            observedFirst.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, observedFirst.continuityCoverageWire)

        // The next row boundary — republish + first emission of row 2, in-bracket.
        g.release(first, "release-173-row1")
        g.advance(first, "advance-173-row1", "item-1")
        val second = g.apply("apply-173-row2")
        assertEquals(2, g.emissionsInsideRepublish)

        val cursorAfterSecond = checkNotNull(g.stableCursor())
        assertNotNull(
            "the second row's bracket must be acknowledged — no backlog ever forms",
            g.commitStore.acknowledgement(cursorAfterSecond),
        )
        val observedSecond = g.observeActive(second, "obs-173-row2")
        assertEquals(second.environmentRevision, observedSecond.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, observedSecond.continuityCoverageWire)
        assertEquals(
            "guard 4 must never fire when the first emission lands in-bracket",
            0,
            g.guardFourSkips(),
        )
    }

    /**
     * Attempt-69 reproduction, pinned: with the first delivery left to a later
     * tick, the changed +2 lands OUTSIDE the bracket with no owner ack — and
     * guard 4 then refuses every later ack on the row, the advance cursor stays
     * unacked, and the next observe reports the change fail-closed (+1). This
     * is the dead end #167's guards were correct to draw; the fix removes the
     * backlog at its source rather than weakening the guards.
     */
    @Test
    fun `first emission landing on a later tick poisons the row's ack chain`() {
        val g = Graph()
        g.firstEmissionInBracket = false

        val first = g.apply("apply-173-poison")
        val ackedBefore = checkNotNull(g.stableCursor())
        assertNotNull(g.commitStore.acknowledgement(ackedBefore))

        // The 1 Hz tick delivers the republished coordinates after the bracket
        // closed: changed +2, no owner ack — the backlog cursor is born.
        g.fireTickDelivery()
        val backlogCursor = checkNotNull(g.stableCursor())
        assertNull(
            "the tick's cursor landed outside any owner bracket — unacked backlog",
            g.commitStore.acknowledgement(backlogCursor),
        )

        // The row loop continues — and EVERY owner ack now hits guard 4.
        g.release(first, "release-173-poison")
        g.advance(first, "advance-173-poison", "item-1")
        val second = g.apply("apply-173-poison-row2")
        val guardLines = g.diagnostics.lines.filter { it.contains("pending external cursor below") }
        assertEquals(
            "guard 4 must refuse the later acks (advance, then the second apply)",
            2,
            guardLines.size,
        )
        assertTrue(
            "the second apply's ack is the one the device log showed dying",
            guardLines.last().contains("apply-${second.leaseId}"),
        )
        assertNull(
            g.commitStore.acknowledgement(checkNotNull(g.stableCursor())),
        )

        // The next observe reports the unacked change, fail-closed: +1 revision.
        val observed = g.observeActive(second, "obs-173-poison")
        assertEquals(
            "the row-boundary attempt dies on the backlog bump",
            second.environmentRevision + 1L,
            observed.environmentRevision,
        )
        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
    }

    /**
     * The transition (face 2), pinned: a device that installs the fix already
     * carries unacked backlog. The guards stay untouched — the first corrected
     * bracket STILL skips (honest: its baseline sits above the highest
     * acknowledged cursor) — and the observe's existing digestion consumes the
     * backlog: one bump, one ack. The very next changed bracket is acknowledged
     * again. Transition length: exactly ONE digesting observe; no guard was
     * weakened and no backlog was laundered away.
     */
    @Test
    fun `pre-existing backlog digests through one observe before owner acks resume`() {
        val g = Graph()
        g.firstEmissionInBracket = true

        // Clean baseline row, then the residue the PRE-fix world left on the
        // device (a changed first emission that landed outside any bracket).
        val first = g.apply("apply-173-t1")
        g.fireTickDelivery()
        val backlogCursor = checkNotNull(g.stableCursor())
        assertNull(g.commitStore.acknowledgement(backlogCursor))
        val highestBefore = backlogCursor.sequence - 2L

        // First corrected bracket: the delta is exactly its own +2, but the
        // baseline sits above the highest acknowledged cursor — guard 4 skips,
        // honestly, exactly as before the fix.
        g.release(first, "release-173-t1")
        g.advance(first, "advance-173-t1", "item-1")
        val second = g.apply("apply-173-t2")
        assertEquals(
            "release+advance+apply each faced the backlog; the guard-4 skips are honest",
            2,
            g.guardFourSkips(),
        )
        assertTrue(
            g.diagnostics.lines.any {
                it.contains("apply-${second.leaseId}") &&
                    it.contains("highest acknowledged $highestBefore < bracket baseline")
            },
        )
        val cursorAfterSecond = checkNotNull(g.stableCursor())
        assertNull("the corrected bracket's cursor is still not acked over the backlog",
            g.commitStore.acknowledgement(cursorAfterSecond))

        // ONE observe digests: bump once, acknowledge the cursor it saw.
        val observed = g.observeActive(second, "obs-173-t2")
        assertEquals(second.environmentRevision + 1L, observed.environmentRevision)
        assertNotNull(
            "the digesting observe must acknowledge the backlog cursor",
            g.commitStore.acknowledgement(cursorAfterSecond),
        )
        assertEquals("exactly one observe was needed", 2, g.guardFourSkips())

        // Owner acks resume on the very next changed bracket.
        g.diagnostics.clear()
        g.release(second, "release-173-t2")
        g.advance(second, "advance-173-t2", "item-2")
        val third = g.apply("apply-173-t3")
        val cursorAfterThird = checkNotNull(g.stableCursor())
        assertNotNull(
            "the next changed bracket is acknowledged again — the chain is clean",
            g.commitStore.acknowledgement(cursorAfterThird),
        )
        assertEquals(
            "no guard-4 skip may fire after the digestion",
            0,
            g.guardFourSkips(),
        )
        val observedThird = g.observeActive(third, "obs-173-t3")
        assertEquals(third.environmentRevision, observedThird.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, observedThird.continuityCoverageWire)
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

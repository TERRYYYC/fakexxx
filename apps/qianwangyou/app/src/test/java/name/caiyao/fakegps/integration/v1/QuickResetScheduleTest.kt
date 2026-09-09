package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResetErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResultKindV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #140 dual-app quick reset — the provider side. The productized schedule_reset
 * is an owner-fenced, pairing-authorized operation that returns ANY durable
 * generation to the FIRST item of a NEW generation (V+1, exhausted cleared,
 * last-applied residue removed) and re-anchors/re-publishes the effective
 * profile. This is the four-weird-states acceptance at the provider boundary:
 * 日程耗尽 / 恢复态残留 / 发布失败 land here; the trust red line (leases,
 * receipts, quota untouched) and the §6.7.5 single-commit discipline are pinned
 * alongside.
 */
class QuickResetScheduleTest {

    // ---- the happy path: a NEW generation at the first item, publish verified ----

    @Test
    fun `quick reset returns an exhausted schedule to the first item of a new generation`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val before = h.env.scheduleVersion
        val revisionBefore = h.tracker.snapshot().revision

        val outcome = h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)

        assertEquals(
            QuickResetScheduleOutcome.Reset(before + 1, "profile-1"),
            outcome,
        )
        assertEquals(before + 1, h.env.scheduleVersion)
        assertEquals(h.env.itemIds.first(), h.env.currentItemId)
        assertFalse(h.env.exhausted)
        assertEquals(revisionBefore + 1L, h.tracker.snapshot().revision)
        assertEquals(1, h.audit.all().count { it.event == "schedule_reset" })
        assertEquals(
            "",
            h.kv.read(
                EnvironmentControlHandler.RESTART_PENDING_NAMESPACE,
                EnvironmentControlHandler.RESTART_PENDING_KEY,
            ),
        )
    }

    // ---- the operator's actual complaint: a stuck MID-SCHEDULE state, NOT exhausted ----

    @Test
    fun `quick reset works from a stuck mid-schedule state that the operator restart refuses`() {
        val h = ProviderHarness.create()
        h.pair()
        // Mid-schedule: pointer on item-2, NOT exhausted — restartScheduleForOperator
        // refuses exactly this state with NOT_EXHAUSTED; the quick reset must not.
        h.env.currentItemId = h.env.itemIds[1]
        h.env.exhausted = false
        val before = h.env.scheduleVersion
        assertEquals(
            OperatorScheduleRestartResult.NOT_EXHAUSTED,
            h.handler.restartScheduleForOperator(),
        )

        val outcome = h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)

        assertEquals(
            QuickResetScheduleOutcome.Reset(before + 1, "profile-1"),
            outcome,
        )
        assertEquals(h.env.itemIds.first(), h.env.currentItemId)
        assertFalse(h.env.exhausted)
    }

    // ---- the four-weird-states acceptance is reachable without re-pairing ----

    @Test
    fun `unpaired caller is typed-refused and nothing is written`() {
        val h = ProviderHarness.create()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val before = h.env.scheduleVersion
        val auditBefore = h.audit.all()

        val e = expectContractFailure(ContractErrorCodeV1.NOT_PAIRED) {
            h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)
        }
        assertTrue(e.message.orEmpty().isNotEmpty())
        assertEquals(before, h.env.scheduleVersion)
        assertTrue(h.env.exhausted)
        assertEquals(auditBefore, h.audit.all())
        assertEquals(
            "",
            h.kv.read(
                EnvironmentControlHandler.RESTART_PENDING_NAMESPACE,
                EnvironmentControlHandler.RESTART_PENDING_KEY,
            ) ?: "",
        )
    }

    @Test
    fun `quick reset refuses while a lease is still blocking`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val before = h.env.scheduleVersion
        h.apply()

        val outcome = h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)

        assertEquals(QuickResetScheduleOutcome.BlockedByLease, outcome)
        assertEquals(before, h.env.scheduleVersion)
        assertTrue(h.env.exhausted)
        assertEquals(0, h.audit.all().count { it.event == "schedule_reset" })
    }

    // ---- 恢复态残留: the stale run's last-applied state must not outlive the generation ----

    @Test
    fun `reset settles a pending reset marker from an interrupted window via its own mode`() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val versionBefore = h.env.scheduleVersion
        // External schedule write crashes mid-window: the marker is committed,
        // the pointer is not moved yet.
        h.envKv.failOnWrite = { namespace, _ ->
            namespace == FakeQwyEnvironmentNamespace
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)
        }

        assertEquals(versionBefore, h.env.scheduleVersion)
        val marker = h.kv.read(
            EnvironmentControlHandler.RESTART_PENDING_NAMESPACE,
            EnvironmentControlHandler.RESTART_PENDING_KEY,
        )!!
        // The marker must carry the RESET mode so the replay does NOT hit the
        // operator restart's exhausted-only guard (the schedule here IS exhausted,
        // so flip it to model the harder case: a crash from a NON-exhausted state).
        assertTrue(DurableFieldCodec.decodeNonNull(marker).contains(EnvironmentControlHandler.RESTART_MARKER_MODE_RESET))

        // Recovery: the next fenced entry settles the committed reset exactly once.
        h.envKv.failOnWrite = null
        h.env.currentItemId = h.env.itemIds[1]
        h.env.exhausted = false

        h.handler.discover(ProviderHarness.AUTO_UID)

        assertEquals(versionBefore + 1L, h.env.scheduleVersion)
        assertEquals(h.env.itemIds.first(), h.env.currentItemId)
        assertFalse(h.env.exhausted)
        assertEquals(
            "",
            h.kv.read(
                EnvironmentControlHandler.RESTART_PENDING_NAMESPACE,
                EnvironmentControlHandler.RESTART_PENDING_KEY,
            ),
        )
        assertEquals(1, h.audit.all().count { it.event == "schedule_reset" })
    }

    // ---- 发布失败: honest partial — the schedule stays reset, the outcome says so ----

    @Test
    fun `publish failure is an honest partial carrying the committed generation`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val before = h.env.scheduleVersion
        h.env.republishResult = null

        val outcome = h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)

        assertEquals(
            QuickResetScheduleOutcome.ResetButPublishFailed(before + 1),
            outcome,
        )
        assertEquals(before + 1, h.env.scheduleVersion)
        assertEquals(h.env.itemIds.first(), h.env.currentItemId)
        assertFalse(h.env.exhausted)
        // The reset itself is audited even when the publish leg fails.
        assertEquals(1, h.audit.all().count { it.event == "schedule_reset" })
    }

    // ---- fail-closed corners ----

    @Test
    fun `no schedule refuses honestly`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.hasSchedule = false

        val outcome = h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)

        assertEquals(QuickResetScheduleOutcome.NoSchedule, outcome)
    }

    @Test
    fun `corrupt store version 0 is refused and never laundered`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.scheduleVersion = 0L
        val revisionBefore = h.tracker.snapshot().revision

        val outcome = h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)

        assertEquals(QuickResetScheduleOutcome.CorruptScheduleState, outcome)
        assertEquals(0L, h.env.scheduleVersion)
        assertEquals(revisionBefore, h.tracker.snapshot().revision)
        assertEquals(0, h.audit.all().count { it.event == "schedule_reset" })
    }

    @Test
    fun `revision write failure rolls back the whole reset commit`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val versionBefore = h.env.scheduleVersion
        val revisionBefore = h.tracker.snapshot().revision
        val auditBefore = h.audit.all()
        h.kv.failOnWrite = { namespace, _ ->
            namespace == ContinuityTracker.REVISION_NAMESPACE
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.quickResetScheduleForCaller(ProviderHarness.AUTO_UID)
        }

        assertEquals(versionBefore, h.env.scheduleVersion)
        assertTrue(h.env.exhausted)
        assertEquals(revisionBefore, h.tracker.snapshot().revision)
        assertEquals(auditBefore, h.audit.all())
    }

    // ---- the carrier mapping (pure, service-level glue) ----

    @Test
    fun `authorization codes mirror the control channel §6§5 values`() {
        // The maintenance surface authorizes through the SAME CallerAuthorizer,
        // so its codes 1/2 must equal NOT_PAIRED / CALLER_NOT_ALLOWED on the
        // control channel — one number, one meaning, both surfaces.
        assertEquals(
            ContractErrorCodeV1.NOT_PAIRED.wire,
            MaintenanceResetErrorCodeV1.NOT_PAIRED.wire,
        )
        assertEquals(
            ContractErrorCodeV1.CALLER_NOT_ALLOWED.wire,
            MaintenanceResetErrorCodeV1.CALLER_NOT_ALLOWED.wire,
        )
    }

    @Test
    fun `outcome to carrier mapping is typed and honest`() {
        assertEquals(
            MaintenanceResultKindV1.OK,
            toMaintenanceResult { QuickResetScheduleOutcome.Reset(9L, "profile-3") }
                .resultKindOrNull(),
        )
        assertEquals(
            "profile-3",
            toMaintenanceResult { QuickResetScheduleOutcome.Reset(9L, "profile-3") }
                .republishedProfileRef,
        )
        val partial = toMaintenanceResult {
            QuickResetScheduleOutcome.ResetButPublishFailed(9L)
        }
        assertEquals(MaintenanceResetErrorCodeV1.PUBLISH_FAILED, partial.errorCodeOrNull())
        assertEquals(9L, partial.scheduleVersionAfter)
        assertNull(partial.republishedProfileRef)
        assertEquals(
            MaintenanceResetErrorCodeV1.BLOCKED_BY_LEASE,
            toMaintenanceResult { QuickResetScheduleOutcome.BlockedByLease }.errorCodeOrNull(),
        )
        assertEquals(
            MaintenanceResetErrorCodeV1.NO_SCHEDULE,
            toMaintenanceResult { QuickResetScheduleOutcome.NoSchedule }.errorCodeOrNull(),
        )
        assertEquals(
            MaintenanceResetErrorCodeV1.CORRUPT_SCHEDULE_STATE,
            toMaintenanceResult { QuickResetScheduleOutcome.CorruptScheduleState }.errorCodeOrNull(),
        )
        assertEquals(
            MaintenanceResetErrorCodeV1.WRITE_FAILED,
            toMaintenanceResult { QuickResetScheduleOutcome.WriteFailed }.errorCodeOrNull(),
        )
        // Authorization failures cross as the carrier's codes 1/2 (mirroring §6.5):
        // NOT_PAIRED passes through; any OTHER control code clamps to CALLER_NOT_ALLOWED
        // so a control-only wire value can never leak onto the maintenance channel.
        assertEquals(
            MaintenanceResetErrorCodeV1.NOT_PAIRED,
            toMaintenanceResultThrowing(ContractErrorCodeV1.NOT_PAIRED).errorCodeOrNull(),
        )
        assertEquals(
            MaintenanceResetErrorCodeV1.CALLER_NOT_ALLOWED,
            toMaintenanceResultThrowing(ContractErrorCodeV1.LEASE_CONFLICT).errorCodeOrNull(),
        )
    }

    private fun toMaintenanceResultThrowing(code: ContractErrorCodeV1) =
        toMaintenanceResult { throw ContractException(code, "x") as Nothing }

    private companion object {
        const val FakeQwyEnvironmentNamespace = "fakeqwy.schedule"
    }
}

package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OperatorScheduleRestartTest {

    @Test
    fun `exhausted idle schedule restarts at first item with a new generation`() {
        val h = ProviderHarness.create()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val before = h.env.scheduleVersion

        val result = h.handler.restartScheduleForOperator()

        assertEquals(OperatorScheduleRestartResult.RESTARTED, result)
        assertEquals(before + 1, h.env.scheduleVersion)
        assertEquals(h.env.itemIds.first(), h.env.currentItemId)
        assertFalse(h.env.exhausted)
    }

    @Test
    fun `restart refuses while any lease is still blocking`() {
        val h = ProviderHarness.create()
        h.pair()
        h.env.exhausted = true
        val before = h.env.scheduleVersion
        h.apply()

        val result = h.handler.restartScheduleForOperator()

        assertEquals(OperatorScheduleRestartResult.BLOCKED_BY_LEASE, result)
        assertEquals(before, h.env.scheduleVersion)
        assertTrue(h.env.exhausted)
    }

    @Test
    fun `non-terminal schedule cannot be silently rewound`() {
        val h = ProviderHarness.create()
        h.env.exhausted = false
        val before = h.env.scheduleVersion

        val result = h.handler.restartScheduleForOperator()

        assertEquals(OperatorScheduleRestartResult.NOT_EXHAUSTED, result)
        assertEquals(before, h.env.scheduleVersion)
    }

    @Test
    fun `revision write failure cannot leave the external schedule restarted`() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val versionBefore = h.env.scheduleVersion
        val revisionBefore = h.tracker.snapshot().revision
        val auditBefore = h.audit.all()
        h.kv.failOnWrite = { namespace, _ ->
            namespace == ContinuityTracker.REVISION_NAMESPACE
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.restartScheduleForOperator()
        }

        assertEquals(versionBefore, h.env.scheduleVersion)
        assertTrue(h.env.exhausted)
        assertEquals(revisionBefore, h.tracker.snapshot().revision)
        assertEquals(auditBefore, h.audit.all())
    }

    @Test
    fun `audit write failure rolls back restart bookkeeping before external mutation`() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val versionBefore = h.env.scheduleVersion
        val revisionBefore = h.tracker.snapshot().revision
        h.kv.failOnWrite = { namespace, _ -> namespace == "integration.v1.audit" }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.restartScheduleForOperator()
        }

        assertEquals(versionBefore, h.env.scheduleVersion)
        assertTrue(h.env.exhausted)
        assertEquals(revisionBefore, h.tracker.snapshot().revision)
        assertTrue(h.audit.all().isEmpty())
    }

    @Test
    fun `external write failure leaves a replayable marker and next entry converges once`() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val versionBefore = h.env.scheduleVersion
        val revisionBefore = h.tracker.snapshot().revision
        h.envKv.failOnWrite = { namespace, _ ->
            namespace == FakeQwyEnvironment.SCHEDULE_NAMESPACE
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.restartScheduleForOperator()
        }

        assertEquals(versionBefore, h.env.scheduleVersion)
        assertTrue(h.env.exhausted)
        assertTrue(
            h.kv.read(
                EnvironmentControlHandler.RESTART_PENDING_NAMESPACE,
                EnvironmentControlHandler.RESTART_PENDING_KEY,
            )!!.isNotEmpty(),
        )
        assertEquals(revisionBefore + 1L, h.tracker.snapshot().revision)
        assertEquals(1, h.audit.all().count { it.event == "schedule_restarted" })

        h.envKv.failOnWrite = null
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
        assertEquals(revisionBefore + 1L, h.tracker.snapshot().revision)
        assertEquals(1, h.audit.all().count { it.event == "schedule_restarted" })
    }

    @Test
    fun `failure clearing marker after external commit replays idempotently on reentry`() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair()
        h.env.currentItemId = h.env.itemIds.last()
        h.env.exhausted = true
        val versionBefore = h.env.scheduleVersion
        val revisionBefore = h.tracker.snapshot().revision
        var markerWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (
                namespace == EnvironmentControlHandler.RESTART_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.RESTART_PENDING_KEY
            ) {
                markerWrites += 1
                markerWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.restartScheduleForOperator()
        }

        assertEquals(versionBefore + 1L, h.env.scheduleVersion)
        assertFalse(h.env.exhausted)
        assertEquals(revisionBefore + 1L, h.tracker.snapshot().revision)
        assertEquals(1, h.audit.all().count { it.event == "schedule_restarted" })

        h.kv.failOnWrite = null
        h.handler.discover(ProviderHarness.AUTO_UID)

        assertEquals(versionBefore + 1L, h.env.scheduleVersion)
        assertFalse(h.env.exhausted)
        assertEquals(revisionBefore + 1L, h.tracker.snapshot().revision)
        assertEquals(1, h.audit.all().count { it.event == "schedule_restarted" })
        assertEquals(
            "",
            h.kv.read(
                EnvironmentControlHandler.RESTART_PENDING_NAMESPACE,
                EnvironmentControlHandler.RESTART_PENDING_KEY,
            ),
        )
    }
}

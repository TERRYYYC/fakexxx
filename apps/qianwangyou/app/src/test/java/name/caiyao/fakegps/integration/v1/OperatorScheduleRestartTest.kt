package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}

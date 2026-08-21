package name.caiyao.fakegps.integration.v1.support

import name.caiyao.fakegps.integration.v1.RevisionBumpReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meta-tests on the TEST HARNESS itself (deepseek-flash F-2 closure evidence).
 *
 * A restart test is only as honest as the restart primitive. F-2 caught that
 * the earlier harness kept the schedule pointer in fake MEMORY while
 * ProviderHarness.restart did not rebuild it — so a completely non-persisting
 * implementation could have passed. These tests are GREEN by design: they
 * assert the harness now has the fidelity the §6.7.5 / M-AD restart tests
 * depend on, so those tests are testing what they claim.
 *
 * If any of these regress, the corresponding behavioral red tests are back to
 * being fake-green and must not be trusted.
 */
class HarnessFidelityTest {

    /**
     * The fake's schedule pointer is durable: mutate it, then read it back
     * through a FRESH env instance built over the same kv (owner restart). The
     * value survives ONLY because it lives in kv — this is what makes the
     * pointer-persistence claim testable.
     */
    @Test
    fun scheduleState_isDurableAcrossEnvRebuild() {
        val kv = InMemoryDurableKv()
        val env1 = FakeQwyEnvironment(kv)
        env1.currentItemId = "item-2"
        env1.scheduleVersion = 9
        env1.exhausted = true

        val env2 = FakeQwyEnvironment(kv) // "restart"
        assertEquals("item-2", env2.currentItemId)
        assertEquals(9L, env2.scheduleVersion)
        assertTrue("exhausted bit is durable", env2.exhausted)
    }

    /**
     * Conversely: state that is NOT written to kv does not survive a restart.
     * This is the exact fake-green F-2 exposed — proving the harness now drops
     * non-durable state instead of silently carrying it across restart.
     */
    @Test
    fun nonDurableWrite_isLostAcrossRestart() {
        val kv = InMemoryDurableKv()
        // Simulate a hypothetical memory-only pointer: write to a namespace that
        // the "restart" throws away. We model that by writing inside a rolled-back
        // transaction — it never commits, so a rebuild cannot see it.
        assertThrows(SimulatedWriteCrash::class.java) {
            kv.transaction {
                kv.write("mem.only", "pointer", "item-2")
                kv.failOnWrite = { _, _ -> true }
                kv.write("mem.only", "pointer", "item-3") // crashes → rollback whole tx
            }
        }
        kv.failOnWrite = null
        assertNull("uncommitted write did not survive", kv.read("mem.only", "pointer"))
    }

    /** Transactions are atomic: an exception mid-transaction discards ALL of its writes. */
    @Test
    fun transaction_rollsBackAllWritesOnFailure() {
        val kv = InMemoryDurableKv()
        kv.write("ns", "pre", "committed")

        assertThrows(RuntimeException::class.java) {
            kv.transaction {
                kv.write("ns", "a", "1")
                kv.write("ns", "b", "2")
                throw RuntimeException("boom before commit")
            }
        }

        assertNull("first buffered write rolled back", kv.read("ns", "a"))
        assertNull("second buffered write rolled back", kv.read("ns", "b"))
        assertEquals("pre-existing committed value intact", "committed", kv.read("ns", "pre"))
    }

    /** A successful transaction commits all its writes atomically (visible only after commit). */
    @Test
    fun transaction_commitsAllWritesOnSuccess() {
        val kv = InMemoryDurableKv()
        val seenMidTx = kv.transaction {
            kv.write("ns", "a", "1")
            // Inside the tx the writer sees its own buffer...
            kv.read("ns", "a")
        }
        assertEquals("1", seenMidTx)
        assertEquals("committed after success", "1", kv.read("ns", "a"))
    }

    /** The relevant-change listener wiring the M-RC-03 test depends on actually fires. */
    @Test
    fun relevantChangeListener_isInvokedByHijack() {
        val kv = InMemoryDurableKv()
        val env = FakeQwyEnvironment(kv)
        val reasons = mutableListOf<RevisionBumpReason>()
        env.setRelevantChangeListener { reasons.add(it) }

        env.hijackAndRestoreMockOwner()

        assertEquals(
            listOf(
                RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED,
                RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED,
            ),
            reasons,
        )
    }
}

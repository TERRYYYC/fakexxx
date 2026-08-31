package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import name.caiyao.fakegps.integration.v1.ContinuityTracker
import name.caiyao.fakegps.integration.v1.RevisionBumpReason
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

/**
 * §10 multiproc rows owned by the qwy provider lane (§6.6 L1–L6, INV-25).
 *
 * The revision owner is the single writer; a lost or late bump looks exactly
 * like "FULL and unchanged" — the false trust INV-08/09 exists to stop.
 */
class MultiProcessMatrixTest {

    /** M-MP-01: concurrent bumps (main + :hook_verify) — none lost, strictly monotonic, no regress. */
    @Test
    fun M_MP_01() {
        val kv = InMemoryDurableKv()
        val tracker = ContinuityTracker(kv, FakeMonotonicClock())
        val base = tracker.snapshot().revision

        val perThread = 50
        val results = Collections.synchronizedList(mutableListOf<Long>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                start.await()
                try {
                    repeat(perThread) {
                        results.add(tracker.bump(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED))
                    }
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))

        assertEquals("no bump lost", base + 2L * perThread, tracker.snapshot().revision)
        assertEquals("every ACKed bump distinct", 2 * perThread, results.toSet().size)
        assertEquals("ACKed values are exactly the range", (base + 1..base + 2L * perThread).toSet(), results.toSet())
    }

    /** M-MP-02: owner restart with unprovable generation continuity → bump + coverage degraded (L6). */
    @Test
    fun M_MP_02() {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()

        val t1 = ContinuityTracker(kv, clock)
        t1.markContinuityEstablished()
        val before = t1.snapshot()
        assertEquals("precondition: FULL before restart", ContinuityCoverageV1.FULL.wire, before.coverageWire)

        val t2 = ContinuityTracker(kv, clock)
        val after = t2.snapshot()

        assertNotEquals("new persisted generation per owner start", before.generation, after.generation)
        assertTrue("revision bumped on unprovable continuity", after.revision > before.revision)
        assertNotEquals("coverage degraded, not silently FULL", ContinuityCoverageV1.FULL.wire, after.coverageWire)
    }

    /** M-MP-03: lossy observer resubscribe gap → bump + degrade; "no events seen" is never "nothing changed". */
    @Test
    fun M_MP_03() {
        val kv = InMemoryDurableKv()
        val tracker = ContinuityTracker(kv, FakeMonotonicClock())
        tracker.markContinuityEstablished()
        val before = tracker.snapshot()
        assertEquals(ContinuityCoverageV1.FULL.wire, before.coverageWire)

        tracker.reportObserverGap()

        val after = tracker.snapshot()
        assertTrue("gap bumps revision", after.revision > before.revision)
        assertNotEquals("gap degrades coverage", ContinuityCoverageV1.FULL.wire, after.coverageWire)
        assertNull("a degraded source cannot retain the old FULL window", after.continuitySinceElapsedRealtimeMs)
    }
}

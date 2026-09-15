package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #205: owner-mutation interval rows accumulate one row per cleanly finished
 * owner bracket, and [AuthoritativeObservationCommitStore.ownerMutationIntervals]
 * materialized ALL of them on every unacknowledged observe — unbounded growth
 * over a long-lived process plus a linear scan feeding the walk each time.
 *
 * Fix under test: bounded retention by generation. Only rows of the newest
 * retention window (the current owner epoch and its immediate predecessors)
 * materialize; rows of older generations are dropped from every read path.
 *
 * Safety contract pinned here (the #199 structural guarantee):
 *  - eviction only REMOVES explanations. A dropped interval can turn an
 *    explainable transition unexplainable → the observer keeps the
 *    conservative bump (fail-closed). No eviction can make an unexplained
 *    transition explainable, so no false-positive ack is possible;
 *  - the durable watermark of the retained window still interprets: chains
 *    spanning the previous and current generation (the #199 form-1
 *    crash-restart backlog) stay attributable;
 *  - a transition no retained row explains still returns false (bump).
 */
class AuthoritativeObservationCommitStoreIntervalRetentionTest {

    @Test
    fun `rows of generations outside the retention window stop materializing`() {
        val store = AuthoritativeObservationCommitStore(InMemoryDurableKv())
        // Ancient generation: a full owner chain.
        store.recordOwnerMutationInterval("ancient-a", "D0", "D1", localGeneration = 10)
        store.recordOwnerMutationInterval("ancient-b", "D1", "D2", localGeneration = 10)
        assertTrue(store.explainsDigestTransition("D0", "D2"))

        // Fifty owner epochs later the ancient rows are long out of the window.
        store.recordOwnerMutationInterval("recent", "D2", "D3", localGeneration = 60)

        val materialized = store.ownerMutationIntervals()
        assertTrue(
            "#205: only the newest retention window materializes — ancient rows " +
                "must be dropped from every read path instead of accumulating forever",
            materialized.none { it.localGeneration <= 57 },
        )
        assertEquals(
            "#205: the materialized row set is bounded to the retention window",
            listOf("recent"),
            materialized.map { it.mutationId },
        )
    }

    @Test
    fun `evicted rows stop explaining - unexplained keeps the conservative bump direction`() {
        val store = AuthoritativeObservationCommitStore(InMemoryDurableKv())
        store.recordOwnerMutationInterval("ancient-a", "D0", "D1", localGeneration = 10)
        store.recordOwnerMutationInterval("ancient-b", "D1", "D2", localGeneration = 10)
        store.recordOwnerMutationInterval("recent", "Y", "Z", localGeneration = 60)

        // Dropped rows can no longer PROVE the old transition: the observer
        // falls back to the conservative bump — this is the intended, safe
        // direction (never a fabricated ack).
        assertFalse(
            "#205: an evicted chain must classify as unexplained (conservative bump), " +
                "not silently stay explainable",
            store.explainsDigestTransition("D0", "D2"),
        )
        // And eviction never CREATES explanations.
        assertFalse(store.explainsDigestTransition("D0", "Z"))
        // The retained window still interprets its own transitions.
        assertTrue(store.explainsDigestTransition("Y", "Z"))
    }

    @Test
    fun `watermark interpretation survives retention - cross-generation chain inside the window`() {
        val store = AuthoritativeObservationCommitStore(InMemoryDurableKv())
        // #199 form 1 across a restart: generation 41 mutated D1 -> D2 and died
        // before its cursor ack retried; generation 42 (current) mutated
        // D2 -> D3. The durable watermark (D1) must still attribute through the
        // two-window chain — retention keeps BOTH generations.
        store.recordOwnerMutationInterval("prev-gen", "D1", "D2", localGeneration = 41)
        store.recordOwnerMutationInterval("cur-gen", "D2", "D3", localGeneration = 42)

        assertTrue(
            "#205: the retention window must keep the previous generation so the " +
                "crash-restart attribution backlog stays owner-accounted",
            store.explainsDigestTransition("D1", "D3"),
        )
    }

    @Test
    fun `retention high water is durable - a restarted store keeps the same window`() {
        val kv = InMemoryDurableKv()
        val firstProcess = AuthoritativeObservationCommitStore(kv)
        firstProcess.recordOwnerMutationInterval("old", "D0", "D1", localGeneration = 20)
        firstProcess.recordOwnerMutationInterval("new", "D1", "D2", localGeneration = 50)

        // Restart convention: a new store over the SAME DurableKv is the next
        // process; the window floor must not reset to "keep everything".
        val restarted = AuthoritativeObservationCommitStore(kv)
        restarted.recordOwnerMutationInterval("newer", "D2", "D3", localGeneration = 50)

        assertTrue(restarted.ownerMutationIntervals().none { it.mutationId == "old" })
        assertTrue(restarted.explainsDigestTransition("D1", "D3"))
        assertFalse(restarted.explainsDigestTransition("D0", "D2"))
    }
}

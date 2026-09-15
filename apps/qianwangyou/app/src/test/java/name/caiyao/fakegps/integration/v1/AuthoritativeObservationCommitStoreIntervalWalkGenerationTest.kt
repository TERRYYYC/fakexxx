package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #206: the bounded DFS ([AuthoritativeObservationCommitStore.explainsDigestTransition]
 * → walkIntervals) memoizes visited rows in a `used` set. Keying that set by
 * mutationId ALONE aliases two different durable rows whenever two owner
 * generations reuse the same mutation id — and production mutation ids DO
 * recur verbatim across generations ("settle-restart-v\$version",
 * "settle-advance-\$fromItemId" are derived from schedule state, not from the
 * process epoch). While one generation's row is on the DFS path, the other
 * generation's same-named row is pruned, so a perfectly owner-accounted chain
 * that crosses the generation boundary is misjudged as unexplainable — the
 * observer keeps a spurious conservative bump (fail-closed, no false ack, but
 * the #199 attribution precision is lost for exactly the crash-restart shape
 * #199 exists for).
 *
 * Fix under test: the used key carries the row's full identity,
 * (generation, mutationId) — the same identity the storage key already uses —
 * so per-generation rows are independent DFS nodes and cycle protection
 * (never reuse one ROW twice on a path) is preserved.
 */
class AuthoritativeObservationCommitStoreIntervalWalkGenerationTest {

    @Test
    fun `cross-generation reuse of one mutation id still walks as an owner chain`() {
        val store = AuthoritativeObservationCommitStore(InMemoryDurableKv())
        // Generation 7 settled the recurring id driving D0 -> D1; the process
        // restarted, and generation 8 settled the SAME recurring id driving
        // D1 -> D2 ("settle-restart-v7" replayed after a crash is the
        // production shape; the digests differ because the digest baseline
        // moved between the generations).
        store.recordOwnerMutationInterval("settle-restart-v7", "D0", "D1", localGeneration = 7)
        store.recordOwnerMutationInterval("settle-restart-v7", "D1", "D2", localGeneration = 8)

        // Each link alone is explained both before and after the fix.
        assertTrue(store.explainsDigestTransition("D0", "D1"))
        assertTrue(store.explainsDigestTransition("D1", "D2"))
        assertTrue(
            "#206: a digest transition explained by a chain crossing a generation " +
                "boundary must attribute through both rows even though the two rows " +
                "share one mutation id",
            store.explainsDigestTransition("D0", "D2"),
        )
    }

    @Test
    fun `same-named rows across generations are independent nodes in both walk directions`() {
        val store = AuthoritativeObservationCommitStore(InMemoryDurableKv())
        // The older generation holds the SECOND link; the walk must find the
        // chain no matter which generation recorded which link.
        store.recordOwnerMutationInterval("release-op-1", "D5", "D6", localGeneration = 9)
        store.recordOwnerMutationInterval("release-op-1", "D4", "D5", localGeneration = 10)

        assertTrue(
            "#206: generation-scoped used keys must not order the aliasing by row age",
            store.explainsDigestTransition("D4", "D6"),
        )
    }

    @Test
    fun `distinct mutation ids across generations keep walking - control`() {
        val store = AuthoritativeObservationCommitStore(InMemoryDurableKv())
        store.recordOwnerMutationInterval("gen7-op", "D0", "D1", localGeneration = 7)
        store.recordOwnerMutationInterval("gen8-op", "D1", "D2", localGeneration = 8)
        assertTrue(store.explainsDigestTransition("D0", "D2"))
    }

    @Test
    fun `one row is still never reused twice on one walk path - cycle protection intact`() {
        val store = AuthoritativeObservationCommitStore(InMemoryDurableKv())
        // A digest ping-pong across two generations must terminate through the
        // used set / depth cap, exactly as before the fix.
        store.recordOwnerMutationInterval("m", "D0", "D1", localGeneration = 4)
        store.recordOwnerMutationInterval("m", "D1", "D0", localGeneration = 5)

        assertTrue(store.explainsDigestTransition("D0", "D1"))
        assertFalse(
            "#206 must not trade the aliasing bug for unbounded expansion: an " +
                "unreachable digest stays unexplained",
            store.explainsDigestTransition("D0", "UNREACHABLE"),
        )
    }
}

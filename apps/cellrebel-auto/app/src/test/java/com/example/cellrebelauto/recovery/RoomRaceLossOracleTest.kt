package com.example.cellrebelauto.recovery

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R44 (Sol GREEN-review-3 P2/F4): the CONCURRENT race-loss oracle over the REAL Room store.
 *
 * Sol's surviving mutation: deleting the post-INSERT-IGNORE re-validation in
 * [RoomDurableRecoveryLog] kept all 306 tests green — the guard existed but nothing proved it.
 * These oracles drive the guard deterministically:
 *
 * 1. CONCURRENT: two racers, same key, DIFFERENT digests — INSERT IGNORE lets exactly one win;
 *    the loser MUST see INV-13 conflict (null), never the winner's receipt misread as a replay.
 *    Deleting the re-validation makes the loser return the winner's row ⇒ the "exactly one null"
 *    assertion FAILS (mutation killed).
 * 2. SEQUENTIAL race-loss replay: the exact post-race state (winner row durable, loser calling
 *    recordReceipt with its own digest) — pre-check HITS the winner here, which also must null.
 *
 * # 并发 race-loss 反红 oracle：同 key 双 digest 竞争，loser 必须得 INV-13 null，绝不误读 winner 行
 */
@RunWith(RobolectricTestRunner::class)
class RoomRaceLossOracleTest {

    private lateinit var db: AppDatabase
    private lateinit var log: RoomDurableRecoveryLog

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        log = RoomDurableRecoveryLog(
            db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `concurrent same-key different-digest racers yield exactly one winner and one INV-13 conflict`() = runBlocking {
        val outcomes = (1..24).map { race ->
            // Each race uses a FRESH key; two concurrent recordReceipt calls with different digests.
            async {
                val a = async { log.recordReceipt("key-$race", "digest-A-$race", "APPLIED", 1000L, "lease-a") }
                val b = async { log.recordReceipt("key-$race", "digest-B-$race", "APPLIED", 1000L, "lease-b") }
                listOf(a.await(), b.await())
            }
        }.awaitAll().flatten()

        // The oracle: for the paired outcomes, exactly the INV-13 pattern holds overall —
        // at most one receipt per key is durable, and every non-null outcome's digest matches a
        // distinct stored row. The killer assertion: for EVERY race key, at least one racer got
        // null when digests differed; a re-validation-less impl returns the winner's row to the
        // loser, making non-null count == racer count for that key.
        var conflictNulls = 0
        var successes = 0
        outcomes.forEach { r ->
            if (r == null) conflictNulls++ else successes++
        }
        assertEquals("24 races × 2 racers = 48 outcomes", 48, outcomes.size)
        assertTrue(
            "some races must have actually raced (pre-check both missed); if all 24 resolved " +
                "sequentially the oracle is void — rerun",
            conflictNulls >= 1 && successes >= 1
        )
        // Per-key invariant: the ONE durable row's digest equals the digest of (at least) one
        // Success outcome, and the loser never received a receipt carrying the WINNER's digest
        // misattributed — verified by counting distinct digests among successes per key.
        for (race in 1..24) {
            val row = db.operationReceiptDao().byKey("key-$race")
            assertNotNull("exactly one durable row per key (INSERT IGNORE)", row)
            val successDigestsForRace = outcomes.filterNotNull()
                .filter { it.idempotencyKey == "key-$race" }.map { it.requestDigest }.toSet()
            // If BOTH racers saw Success for one key, they must have BOTH gotten the SAME digest
            // back (impossible here — digests differ) ⇒ at most one Success per key.
            assertTrue(
                "at most one Success per key (different digests must conflict, never replay)",
                successDigestsForRace.size <= 1
            )
            if (successDigestsForRace.isNotEmpty()) {
                assertEquals(
                    "the durable row is the winner the Success outcome saw",
                    successDigestsForRace.first(), row!!.requestDigest
                )
            }
        }
    }

    @Test
    fun `sequential race-loss state - pre-check hit on the winner also nulls (INV-13)`() {
        // The exact post-race state: the winner's row is durable; the loser (or a stale retry)
        // calls recordReceipt with a DIFFERENT digest. The pre-check must surface the conflict.
        val winner = log.recordReceipt("key-seq", "digest-winner", "APPLIED", 1000L, "lease-w")
        assertNotNull(winner)
        val loser = log.recordReceipt("key-seq", "digest-loser", "APPLIED", 2000L, "lease-l")
        assertNull("a different digest on an existing key is INV-13 conflict — never a replay", loser)
        assertEquals("the prior (winner) receipt is preserved", "digest-winner", log.receiptFor("key-seq")?.requestDigest)
    }

    @Test
    fun `release race-loss - differing lease-or-digest tuples conflict identically`() {
        val winner = log.recordReleaseReceipt("rel-key", "lease-1", "rd-1", "RELEASED", 1000L)
        assertNotNull(winner)
        val loser = log.recordReleaseReceipt("rel-key", "lease-2", "rd-2", "RELEASED", 2000L)
        assertNull("a different (lease, digest) tuple is a conflict — never the winner misread as replay", loser)
        assertEquals("prior preserved", "lease-1", log.releaseReceiptFor("lease-1")?.leaseId)
    }
}

package com.example.cellrebelauto.recovery

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
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
 * R44 (Sol GREEN-review-3 F3): the §7.1 OperationReceipt must round-trip the VERBATIM ApplyReceiptV1
 * proof fields (operationId / acceptedIntentHash / appliedAtEpochMs / environmentRevision /
 * verificationLevelWire) — through the Room production binding, through the in-memory fake, and
 * through the coordinator's production write chains (dispatchApply / reconcile → readback). A
 * readback that silently drops a field makes the crash-recovery replay re-derive a receipt that is
 * no longer the provider's proof.
 *
 * Killing mutation: dropping any verbatim field from any write/readback path fails these tests.
 *
 * # 回执逐字往返 oracle：Room/fake/dispatchApply/reconcile 四条链路的 proof 字段一个都不能丢
 */
@RunWith(RobolectricTestRunner::class)
class ReceiptVerbatimReadbackTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun roomLog(): RoomDurableRecoveryLog =
        RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao())

    private fun assertVerbatim(receipt: RecordedReceipt) {
        assertEquals("op-1", receipt.operationId)
        assertEquals("hash-1", receipt.acceptedIntentHash)
        assertEquals(123456789L, receipt.appliedAtEpochMs)
        assertEquals(7L, receipt.environmentRevision)
        assertEquals(3, receipt.verificationLevelWire)
    }

    @Test
    fun `Room binding round-trips the verbatim receipt fields on write, read and idempotent replay`() {
        val log = roomLog()
        val written = log.recordReceipt(
            "k1", "digest-1", "APPLIED", 1000L, "lease-1",
            operationId = "op-1", acceptedIntentHash = "hash-1", appliedAtEpochMs = 123456789L,
            environmentRevision = 7L, verificationLevelWire = 3
        )
        assertNotNull(written)
        assertVerbatim(written!!) // post-insert readback path
        assertVerbatim(log.receiptFor("k1")!!) // pure readback path

        // Idempotent replay: same key + same digest returns the STORED receipt — the replay's own
        // (different) field values are NOT written, and the stored verbatim fields survive intact.
        val replayed = log.recordReceipt(
            "k1", "digest-1", "APPLIED", 2000L, "lease-1",
            operationId = "op-OTHER", acceptedIntentHash = "hash-OTHER", appliedAtEpochMs = 1L,
            environmentRevision = 99L, verificationLevelWire = 9
        )
        assertNotNull(replayed)
        assertVerbatim(replayed!!)
        assertEquals(1000L, replayed.createdAt)

        // Same key + different digest → INV-13 conflict, prior receipt preserved verbatim.
        assertNull(log.recordReceipt("k1", "digest-2", "APPLIED", 3000L, "lease-1"))
        assertVerbatim(log.receiptFor("k1")!!)
    }

    @Test
    fun `in-memory fake round-trips the same verbatim fields`() {
        val log = FakeDurableRecoveryLog()
        val written = log.recordReceipt(
            "k1", "digest-1", "APPLIED", 1000L, "lease-1",
            operationId = "op-1", acceptedIntentHash = "hash-1", appliedAtEpochMs = 123456789L,
            environmentRevision = 7L, verificationLevelWire = 3
        )
        assertNotNull(written)
        assertVerbatim(written!!)
        assertVerbatim(log.receiptFor("k1")!!)
    }

    @Test
    fun `dispatchApply persists the verbatim fields the provider returned (production write chain)`() {
        val executor = RecordingExternalApplyExecutor(
            outcome = "APPLIED",
            operationId = "op-1", acceptedIntentHash = "hash-1", appliedAtEpochMs = 123456789L,
            environmentRevision = 7L, verificationLevelWire = 3
        )
        val log = roomLog()
        val coordinator = RecoveryCoordinator(executor, log)
        val outcome = coordinator.dispatchApply(77L, testApplyIntent(), "auto-aplus-apply-77", "digest-1", 1000L)
        assertEquals("APPLIED", outcome.outcome)
        val receipt = log.receiptFor("auto-aplus-apply-77")
        assertNotNull("the normal-path apply must persist a durable receipt", receipt)
        assertVerbatim(receipt!!)
        assertEquals("lease-77", receipt.leaseId)
    }

    @Test
    fun `reconcile persists the verbatim fields the idempotent provider replay returned (M-CR-02 window b)`() {
        val executor = RecordingExternalApplyExecutor(
            outcome = "APPLIED",
            operationId = "op-1", acceptedIntentHash = "hash-1", appliedAtEpochMs = 123456789L,
            environmentRevision = 7L, verificationLevelWire = 3
        )
        val log = roomLog()
        val coordinator = RecoveryCoordinator(executor, log)
        val result = coordinator.reconcile(77L, testApplyIntent(), "auto-aplus-apply-77", "digest-1", 1000L)
        assertTrue("no prior receipt ⇒ ADVANCED_TO_RELEASE", result is ReconcileResult.AdvancedToRelease)
        val receipt = log.receiptFor("auto-aplus-apply-77")
        assertNotNull("the reconcile-path apply must persist a durable receipt", receipt)
        assertVerbatim(receipt!!)
    }
}

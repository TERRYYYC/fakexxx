package com.example.cellrebelauto.model.ledger

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable apply-receipt completion evidence persisted during the normal A+ path (§8.1 DECIDING).
 * Stores the provider-supplied `acceptedIntentHash` + `leaseId` (the INV-23 three-way binding fields)
 * so crash recovery can re-assemble [com.example.cellrebelauto.environment.CompletionTrustContext]
 * from durable data (Sol R36 P1-1: recovery must read the actual provider receipt, not a live source).
 *
 * # 持久完成回执：apply receipt 的 acceptedIntentHash + lease，崩溃后恢复按 attemptId 读取
 */
@Entity(
    tableName = "durable_completion_receipts",
    indices = [Index(value = ["attemptId"], unique = true)]
)
data class DurableCompletionReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptId: Long,
    /** §8.6.2 wire code (1=VERIFIED … 5=NO_EVIDENCE). */
    val completionEvidenceWire: Int,
    /** Provider-accepted intent hash (INV-23 three-way binding; from ApplyReceiptV1, NOT §6.3.4 requestDigest). */
    val acceptedIntentHash: String,
    /** Provider-leased lease id (INV-07). */
    val leaseId: String
)

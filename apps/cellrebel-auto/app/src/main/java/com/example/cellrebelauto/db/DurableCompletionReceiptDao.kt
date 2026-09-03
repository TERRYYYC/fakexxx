package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.DurableCompletionReceipt

@Dao
interface DurableCompletionReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(receipt: DurableCompletionReceipt): Long

    @Query("SELECT * FROM durable_completion_receipts WHERE attemptId = :attemptId LIMIT 1")
    suspend fun forAttempt(attemptId: Long): DurableCompletionReceipt?
}

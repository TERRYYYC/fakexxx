package com.example.cellrebelauto.model.execution

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One external CellRebel execution observed under an attempt (§7.1, §8.6).
 *
 * `executionId` is **Auto-local generated** — it is NOT CellRebel's physical execution identity
 * (§8.6.1). One attempt may have multiple executions (e.g. a pre-existing run, then a fresh one).
 * `completionEvidenceWire` stores the §8.6.2 frozen wire code (1-5); only wire=1
 * (VERIFIED_NEW_COMPLETION) may mint a TrustedQuotaEntry, and only via TrustPolicy + the single
 * ledger transaction (§8.1 DECIDING→QUOTA_COMMITTED).
 *
 * # 一次外部 CellRebel 执行：executionId 是 Auto 本地生成；完成证据存 §8.6.2 wire code
 */
@Entity(
    tableName = "cellrebel_executions",
    indices = [Index("attemptId"), Index("executionId")]
)
data class CellRebelExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Auto-local execution id (§8.6.1). Unique per generated execution. */
    val executionId: String,
    /** The attempt this execution belongs to. */
    val attemptId: Long,
    /** §8.6.2 wire code of the classified completion evidence (1=VERIFIED … 5=NO_EVIDENCE). */
    val completionEvidenceWire: Int,
    /** Digest of the full evidence payload (baseline state / marker text / RUNNING duration / scores). */
    val evidencePayloadDigest: String,
    /** When Auto started observing this execution (epoch ms, AUDIT ONLY — never in a trust predicate). */
    val startedAt: Long,
    /** When Auto classified the completion evidence; null until classified (epoch ms, audit only). */
    val classifiedAt: Long?,
    /**
     * §6.4.2 frozen monotonic window (SystemClock.elapsedRealtime, NTP-immune). These are the ONLY
     * comparable timestamps; the epoch fields above are human-readable audit and MUST NOT enter any
     * trust predicate (a wall-clock NTP pull inside the test window could make "post-observe" numerically
     * precede completion).
     *  - [startedAtElapsed]: the actual Start interaction moment.
     *  - [runningConfirmedAtElapsed]: first moment RUNNING was confirmed by marker text.
     *  - [completedAtElapsed]: moment stable COMPLETED held (two consecutive equal scores).
     * MIN_RUNNING_EVIDENCE_MS = completedAtElapsed − runningConfirmedAtElapsed (NOT − startedAtElapsed).
     */
    val startedAtElapsed: Long = 0L,
    val runningConfirmedAtElapsed: Long = 0L,
    val completedAtElapsed: Long = 0L
)

package com.example.cellrebelauto.ui.dashboard

/**
 * T7 P1.1 — the progress-card projection (pure).
 *
 * 口径纪律：可信配额数字由调用方从 trusted 投影 DAO（count(trusted_quota_entries)）
 * 传入，本投影绝不接触 legacy completedSuccesses 列。吞吐 = 最近窗口内的成功
 * 尝试数折算每小时速率；ETA = 剩余配额 / 速率（粗略，无速率则不给 ETA）；
 * 失败分类按类型化原因前缀分组。
 *
 * # 进度卡投影：可信 X/N + 窗口吞吐 + 粗略 ETA + 失败分类计数
 */
object RunProgressProjection {

    /** The throughput window: the most recent hour. */
    const val WINDOW_MS: Long = 60L * 60_000L

    /** Failure class buckets (typed-reason prefixes). */
    const val CLASS_GPS = "GPS"
    const val CLASS_FOREGROUND = "FOREGROUND"
    const val CLASS_TEST = "TEST"
    const val CLASS_INTERRUPTED = "INTERRUPTED"
    const val CLASS_UNTRUSTED = "UNTRUSTED"
    const val CLASS_ANCHOR = "ANCHOR"
    const val CLASS_OTHER = "OTHER"

    /** One attempt fact — deliberately shape-minimal so fakes stay trivial. */
    data class AttemptFact(
        val succeeded: Boolean,
        val failureReason: String?,
        val endedAt: Long?,
    )

    data class ProgressSnapshot(
        val trustedDone: Int = 0,
        val trustedTotal: Int = 0,
        val windowSuccesses: Int = 0,
        /** Successes per hour over the window; null = no recent successes. */
        val throughputPerHour: Double? = null,
        /** remaining / throughput, ms; null = unknown or already complete. */
        val etaMs: Long? = null,
        /** Human ETA: "--"（未知）/ "约 2 小时 5 分" / "已完成". */
        val etaText: String = "--",
        /** failure class → count, sorted by count desc. */
        val failureClasses: List<Pair<String, Int>> = emptyList(),
    )

    fun project(
        trustedDone: Int,
        trustedTotal: Int,
        attempts: List<AttemptFact>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
    ): ProgressSnapshot {
        val windowStart = nowMs - windowMs
        val windowSuccesses = attempts.count {
            it.succeeded && it.endedAt != null && it.endedAt in windowStart..nowMs
        }
        val throughput = if (windowSuccesses > 0 && windowMs > 0) {
            windowSuccesses * 3_600_000.0 / windowMs
        } else {
            null
        }
        val remaining = (trustedTotal - trustedDone).coerceAtLeast(0)
        val etaMs: Long? = when {
            trustedTotal > 0 && remaining == 0 -> null           // complete
            throughput == null || throughput <= 0.0 -> null      // no measurable rate
            else -> (remaining / throughput * 3_600_000.0).toLong()
        }
        val etaText = when {
            trustedTotal > 0 && remaining == 0 -> "已完成"
            etaMs == null -> "--"
            else -> formatEta(etaMs)
        }
        val failureClasses = attempts
            .filter { !it.succeeded }
            .groupingBy { classifyFailure(it.failureReason) }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
        return ProgressSnapshot(
            trustedDone = trustedDone,
            trustedTotal = trustedTotal,
            windowSuccesses = windowSuccesses,
            throughputPerHour = throughput,
            etaMs = etaMs,
            etaText = etaText,
            failureClasses = failureClasses,
        )
    }

    /**
     * Groups a typed failure reason into a display class. Exhaustive over the
     * existing FailureReason surface; unknown codes fall to OTHER (never lost).
     * # 失败分类：按前缀分组；未知原因落 OTHER，绝不丢弃
     */
    fun classifyFailure(reason: String?): String {
        if (reason == null) return CLASS_OTHER
        return when {
            reason.startsWith("FAKE_GPS") -> CLASS_GPS
            reason == "FOREGROUND_SWITCH_FAILED" -> CLASS_FOREGROUND
            reason.startsWith("ANCHOR_MISMATCH") -> CLASS_ANCHOR
            reason == "UNTRUSTED" -> CLASS_UNTRUSTED
            reason == "CANCELLED" || reason == "INTERRUPTED" -> CLASS_INTERRUPTED
            reason in setOf(
                "CELLREBEL_TIMEOUT",
                "SCORE_PARSE_FAILED",
                "NO_RUNNING_EVIDENCE",
                "PRE_EXISTING_RUN",
            ) -> CLASS_TEST
            else -> CLASS_OTHER
        }
    }

    private fun formatEta(etaMs: Long): String {
        val totalMinutes = (etaMs + 59_999) / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours >= 1 -> "约 ${hours} 小时 ${minutes} 分"
            else -> "约 ${minutes} 分钟"
        }
    }
}

package com.example.cellrebelauto.ui.dashboard

import com.example.cellrebelauto.automation.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7 P1.1 — the progress-card projection oracle.
 *
 * The ONLY progress口径 is the trusted ledger count (§7.3): the legacy
 * `completedSuccesses` column is frozen and must never resurface in the
 * dashboard numbers. Throughput is a recent-window success count; ETA is the
 * rough remaining/rate division; failure classification groups typed reasons
 * by prefix so "why is it stuck" is visible at a glance.
 *
 * Killing mutations:
 *  - any use of a legacy counter fails the trusted-only ViewModel test
 *    (RunDashboardViewModelTest seeds legacy=99 vs trusted=2);
 *  - a window that admits ALL history inflates throughput (excluded-old test);
 *  - a classifier that silently re-buckets unknown reasons fails OTHER.
 *
 * # 进度卡投影 oracle：可信口径唯一、吞吐=窗口成功数、ETA=剩余/速率、失败按前缀分组
 */
class RunProgressProjectionTest {

    private val nowMs = 1_700_000_000_000L

    private fun success(endedAt: Long) = RunProgressProjection.AttemptFact(
        succeeded = true, failureReason = null, endedAt = endedAt
    )

    private fun failure(reason: String?, endedAt: Long = nowMs - 1_000) =
        RunProgressProjection.AttemptFact(
            succeeded = false, failureReason = reason, endedAt = endedAt
        )

    // ---- quota -----------------------------------------------------------------

    @Test
    fun `quota echoes the trusted X over N`() {
        val s = RunProgressProjection.project(
            trustedDone = 3, trustedTotal = 10,
            attempts = emptyList(), nowMs = nowMs
        )
        assertEquals(3, s.trustedDone)
        assertEquals(10, s.trustedTotal)
    }

    // ---- throughput -------------------------------------------------------------

    @Test
    fun `throughput counts only successes inside the recent window`() {
        val inWindow1 = success(endedAt = nowMs - 10 * 60_000L)   // 10 min ago
        val inWindow2 = success(endedAt = nowMs - 30 * 60_000L)   // 30 min ago
        val tooOld = success(endedAt = nowMs - 2 * 3_600_000L)    // 2 h ago
        val failed = failure("CELLREBEL_TIMEOUT")
        val s = RunProgressProjection.project(
            trustedDone = 3, trustedTotal = 10,
            attempts = listOf(inWindow1, inWindow2, tooOld, failed),
            nowMs = nowMs
        )
        assertEquals(2, s.windowSuccesses)
        // 2 successes in a 1 h window → 2 per hour
        assertEquals(2.0, s.throughputPerHour!!, 1e-9)
    }

    @Test
    fun `no successes in window means no throughput and no ETA`() {
        val s = RunProgressProjection.project(
            trustedDone = 1, trustedTotal = 10,
            attempts = listOf(success(endedAt = nowMs - 5 * 3_600_000L)),
            nowMs = nowMs
        )
        assertEquals(0, s.windowSuccesses)
        assertNull(s.throughputPerHour)
        assertNull(s.etaMs)
        assertEquals("--", s.etaText)
    }

    // ---- ETA ---------------------------------------------------------------------

    @Test
    fun `eta is remaining quota divided by recent throughput`() {
        val s = RunProgressProjection.project(
            trustedDone = 4, trustedTotal = 10,           // remaining 6
            attempts = listOf(
                success(nowMs - 5 * 60_000L),
                success(nowMs - 20 * 60_000L),
                success(nowMs - 40 * 60_000L),
            ),
            nowMs = nowMs
        )
        // 3/h → 6 remaining = 2 h
        assertEquals(3.0, s.throughputPerHour!!, 1e-9)
        assertEquals(2 * 3_600_000L, s.etaMs!!)
        assertTrue(s.etaText.contains("2"))
    }

    @Test
    fun `completed plan reports done instead of an ETA`() {
        val s = RunProgressProjection.project(
            trustedDone = 10, trustedTotal = 10,
            attempts = listOf(success(nowMs - 60_000L)),
            nowMs = nowMs
        )
        assertEquals("已完成", s.etaText)
        assertNull(s.etaMs)
    }

    // ---- failure classification ---------------------------------------------------

    @Test
    fun `failure classes group by typed prefix with counts`() {
        val s = RunProgressProjection.project(
            trustedDone = 0, trustedTotal = 10,
            attempts = listOf(
                failure("FAKE_GPS_NOT_ACTIVE"),
                failure("FAKE_GPS_NOT_ACTIVE"),
                failure("UNTRUSTED"),
            ),
            nowMs = nowMs
        )
        val byClass = s.failureClasses.toMap()
        assertEquals(2, byClass["GPS"])
        assertEquals(1, byClass["UNTRUSTED"])
    }

    @Test
    fun `failure classes are sorted by count descending`() {
        val s = RunProgressProjection.project(
            trustedDone = 0, trustedTotal = 10,
            attempts = listOf(
                failure("FAKE_GPS_NOT_ACTIVE"),
                failure("FAKE_GPS_NOT_ACTIVE"),
                failure("FAKE_GPS_NOT_ACTIVE"),
                failure("UNTRUSTED"),
            ),
            nowMs = nowMs
        )
        assertEquals("GPS", s.failureClasses.first().first)
        assertEquals(3, s.failureClasses.first().second)
    }

    @Test
    fun `classifier is exhaustive over FailureReason and the typed anchor prefix`() {
        val known = setOf("GPS", "FOREGROUND", "TEST", "INTERRUPTED", "UNTRUSTED", "ANCHOR", "OTHER")
        for (reason in FailureReason.values()) {
            assertTrue(
                "reason=$reason classed outside the known set",
                RunProgressProjection.classifyFailure(reason.name) in known
            )
        }
        assertEquals("ANCHOR", RunProgressProjection.classifyFailure("ANCHOR_MISMATCH:PRE:|Δlat|=0.5"))
    }

    @Test
    fun `classifier buckets the representative reasons`() {
        assertEquals("GPS", RunProgressProjection.classifyFailure("FAKE_GPS_NOT_ACTIVE"))
        assertEquals("FOREGROUND", RunProgressProjection.classifyFailure("FOREGROUND_SWITCH_FAILED"))
        assertEquals("TEST", RunProgressProjection.classifyFailure("CELLREBEL_TIMEOUT"))
        assertEquals("TEST", RunProgressProjection.classifyFailure("SCORE_PARSE_FAILED"))
        assertEquals("INTERRUPTED", RunProgressProjection.classifyFailure("CANCELLED"))
        assertEquals("INTERRUPTED", RunProgressProjection.classifyFailure("INTERRUPTED"))
        assertEquals("UNTRUSTED", RunProgressProjection.classifyFailure("UNTRUSTED"))
        assertEquals("OTHER", RunProgressProjection.classifyFailure("SOMETHING_NEW"))
        assertEquals("OTHER", RunProgressProjection.classifyFailure(null))
    }
}

package com.example.cellrebelauto.util

import com.example.cellrebelauto.model.plan.AttemptWithTask
import com.example.cellrebelauto.model.plan.TestAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure mapping tests for the 16-column audit CSV (AC-C3 + F003 stage_notes).
 * # 16 列审计 CSV 纯映射测试
 */
class AttemptCsvMapperTest {

    private fun fmt(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    private fun successItem() = AttemptWithTask(
        attempt = TestAttempt(
            id = 12, taskId = 3, runSessionId = 7,
            attemptOrdinal = 3, successOrdinal = 2,
            startedAt = 1_753_900_000_000L,
            runningObservedAt = 1_753_900_012_000L,
            endedAt = 1_753_900_046_000L,
            status = "succeeded", failureReason = null,
            webBrowsingScore = 8.5, videoStreamingScore = 9.1,
            latitude = 31.23, longitude = 121.474
        ),
        planRow = 2, csvRow = 5, priority = 1, requiredSuccesses = 5
    )

    private fun failureItem() = AttemptWithTask(
        attempt = TestAttempt(
            id = 11, taskId = 3, runSessionId = 7,
            attemptOrdinal = 2, successOrdinal = null,
            startedAt = 1_753_899_000_000L,
            runningObservedAt = null,
            endedAt = 1_753_899_090_000L,
            status = "failed", failureReason = "CELLREBEL_TIMEOUT",
            webBrowsingScore = null, videoStreamingScore = null,
            latitude = 31.23, longitude = 121.474
        ),
        planRow = 2, csvRow = 5, priority = 1, requiredSuccesses = 5
    )

    @Test
    fun `header is exactly the 16 audit columns in order`() {
        // # F003：stage_notes 为第 16 列（追加在末尾）
        assertEquals(
            listOf(
                "plan_row", "csv_row", "priority", "longitude", "latitude",
                "success_ordinal", "attempt_ordinal", "status", "failure_reason",
                "started_at", "running_observed_at", "ended_at",
                "web_score", "video_score", "session_id", "stage_notes"
            ),
            AttemptCsvMapper.HEADER
        )
    }

    @Test
    fun `attempt row carries stage notes in the trailing column`() {
        // # F003 INV-F3-1：跳过标记随审计行导出；无跳过为空
        val skipped = successItem().copy(
            attempt = successItem().attempt.copy(stageNotes = "gps_skipped")
        )
        val rows = AttemptCsvMapper.toCsvRows(listOf(skipped, successItem()))
        assertEquals(16, rows[0].size)
        assertEquals("gps_skipped", rows[0][15])
        assertEquals("", rows[1][15])
    }

    @Test
    fun `ok_gps_only status exports verbatim with test_skipped note`() {
        // # F003 KD-F3-2：GPS-only 终态原样导出
        val gpsOnly = successItem().copy(
            attempt = successItem().attempt.copy(
                status = "ok_gps_only",
                webBrowsingScore = null,
                videoStreamingScore = null,
                stageNotes = "test_skipped"
            )
        )
        val row = AttemptCsvMapper.toCsvRows(listOf(gpsOnly))[0]
        assertEquals("ok_gps_only", row[7])
        assertEquals("", row[12])
        assertEquals("", row[13])
        assertEquals("test_skipped", row[15])
    }

    @Test
    fun `success row carries success ordinal and scores`() {
        val rows = AttemptCsvMapper.toCsvRows(listOf(successItem()))
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(AttemptCsvMapper.HEADER.size, row.size)
        // # 列序与表头一致
        assertEquals("2", row[0])          // plan_row
        assertEquals("5", row[1])          // csv_row
        assertEquals("1", row[2])          // priority
        assertEquals("121.474", row[3])    // longitude
        assertEquals("31.23", row[4])      // latitude
        assertEquals("2", row[5])          // success_ordinal
        assertEquals("3", row[6])          // attempt_ordinal
        assertEquals("succeeded", row[7])  // status
        assertEquals("", row[8])           // failure_reason 成功行为空
        assertEquals("8.5", row[12])       // web_score
        assertEquals("9.1", row[13])       // video_score
        assertEquals("7", row[14])         // session_id
    }

    @Test
    fun `failure row carries reason and blank ordinal and scores`() {
        val row = AttemptCsvMapper.toCsvRows(listOf(failureItem()))[0]
        assertEquals("", row[5])                    // success_ordinal 失败行为空（INV-4）
        assertEquals("2", row[6])                   // attempt_ordinal
        assertEquals("failed", row[7])
        assertEquals("CELLREBEL_TIMEOUT", row[8])   // failure_reason 失败行必填（INV-10）
        assertEquals("", row[12])                   // web_score 空
        assertEquals("", row[13])                   // video_score 空
    }

    @Test
    fun `timestamps use yyyy-MM-dd HH-mm-ss format`() {
        val row = AttemptCsvMapper.toCsvRows(listOf(successItem()))[0]
        assertEquals(fmt(1_753_900_000_000L), row[9])
        assertEquals(fmt(1_753_900_012_000L), row[10])
        assertEquals(fmt(1_753_900_046_000L), row[11])
        assertTrue(row[9].matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    fun `null runningObservedAt maps to empty cell`() {
        val row = AttemptCsvMapper.toCsvRows(listOf(failureItem()))[0]
        assertEquals("", row[10])
    }

    private fun legacyResult() = com.example.cellrebelauto.model.TestResult(
        id = 1, runSessionId = 42, timestamp = 1_753_800_000_000L,
        webBrowsingScore = 8.5, videoStreamingScore = 7.5,
        latitude = 39.9, longitude = 116.4, cycleIndex = 1, status = "ok"
    )

    @Test
    fun `legacy result maps with real values and blank plan fields`() {
        // # C1：v2 遗留行并入同一份 16 列 CSV——真实值按映射，计划字段留空
        val row = AttemptCsvMapper.legacyToCsvRow(legacyResult())
        assertEquals(AttemptCsvMapper.HEADER.size, row.size)
        assertEquals("", row[0])               // plan_row 空
        assertEquals("", row[1])               // csv_row 空
        assertEquals("", row[2])               // priority 空
        assertEquals("116.4", row[3])          // longitude 真实值
        assertEquals("39.9", row[4])           // latitude 真实值
        assertEquals("", row[5])               // success_ordinal 空
        assertEquals("", row[6])               // attempt_ordinal 空
        assertEquals("ok", row[7])             // status 真实值
        assertEquals("", row[8])               // failure_reason 空
        assertEquals(fmt(1_753_800_000_000L), row[9])  // started_at = legacy timestamp
        assertEquals("", row[10])              // running_observed_at 空
        assertEquals("", row[11])              // ended_at 空
        assertEquals("8.5", row[12])           // web_score 真实值
        assertEquals("7.5", row[13])           // video_score 真实值
        assertEquals("42", row[14])            // session_id = runSessionId
        assertEquals("", row[15])              // stage_notes 空（F003，遗留行无跳过）
    }

    @Test
    fun `legacy rows are appended after attempt rows`() {
        // # C1：legacy 行排在 attempt 行之后
        val rows = AttemptCsvMapper.toCsvRows(listOf(successItem()), listOf(legacyResult()))
        assertEquals(2, rows.size)
        assertEquals("succeeded", rows[0][7])
        assertEquals("ok", rows[1][7])
        assertEquals("", rows[1][0])
    }
}

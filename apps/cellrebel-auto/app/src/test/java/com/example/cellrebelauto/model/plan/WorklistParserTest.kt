package com.example.cellrebelauto.model.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the atomic CSV worklist parser.
 * # CSV 清单解析器测试：原子导入——任一行无效则整份拒绝，并报告全部行级错误
 *
 * Covers AC-A2: canonical columns, row-specific validation failures,
 * positive and negative cases.
 */
class WorklistParserTest {

    private val header = "longitude,latitude,priority,required_successes"
    private val boundHeader =
        "longitude,latitude,priority,required_successes,schedule_id,schedule_item_id"

    @Test
    fun `valid file parses all rows with 1-based csv row numbers`() {
        val csv = """
            $header
            116.397000,39.908000,1,3
            121.474000,31.230000,1,5
            113.264400,23.129100,2,2
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Success)
        val rows = (result as ParseResult.Success).rows
        assertEquals(3, rows.size)
        assertEquals(WorklistRow(116.397, 39.908, 1, 3, csvRow = 1), rows[0])
        assertEquals(WorklistRow(121.474, 31.230, 1, 5, csvRow = 2), rows[1])
        assertEquals(WorklistRow(113.2644, 23.1291, 2, 2, csvRow = 3), rows[2])
        assertTrue(rows.all { it.scheduleId == null && it.scheduleItemId == null })
    }

    @Test
    fun `bound v2 file preserves explicit schedule and item identities`() {
        val csv = """
            $boundHeader
            116.397,39.908,9,2,schedule-generation-a,item-2
            121.474,31.230,1,1,schedule-generation-a,item-1
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Success)
        val rows = (result as ParseResult.Success).rows
        assertEquals("schedule-generation-a", rows[0].scheduleId)
        assertEquals("item-2", rows[0].scheduleItemId)
        assertEquals("schedule-generation-a", rows[1].scheduleId)
        assertEquals("item-1", rows[1].scheduleItemId)
    }

    @Test
    fun `bound v2 rejects blank duplicate and cross-schedule item identities atomically`() {
        val csv = """
            $boundHeader
            116.397,39.908,1,1,schedule-a,item-1
            121.474,31.230,2,1,schedule-b,item-1
            113.264,23.129,3,1,schedule-a,
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Failure)
        val errors = (result as ParseResult.Failure).errors
        assertTrue(errors.any { it.csvRow == 2 && it.message.contains("schedule_id") })
        assertTrue(errors.any { it.csvRow == 2 && it.message.contains("duplicate") })
        assertTrue(errors.any { it.csvRow == 3 && it.message.contains("schedule_item_id") })
    }

    @Test
    fun `latitude out of range is rejected with row-specific error`() {
        val csv = "$header\n116.397,95.0,1,3"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        val errors = (result as ParseResult.Failure).errors
        assertEquals(1, errors.size)
        assertEquals(1, errors[0].csvRow)
        assertTrue(errors[0].message.contains("latitude", ignoreCase = true))
    }

    @Test
    fun `longitude out of range is rejected`() {
        val csv = "$header\n181.0,39.908,1,3"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors[0].message.contains("longitude", ignoreCase = true))
    }

    @Test
    fun `boundary coordinates are accepted`() {
        val csv = "$header\n-180.0,-90.0,0,1\n180.0,90.0,0,1"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Success)
    }

    @Test
    fun `non-numeric priority is rejected`() {
        val csv = "$header\n116.397,39.908,high,3"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors[0].message.contains("priority", ignoreCase = true))
    }

    @Test
    fun `required_successes below 1 is rejected`() {
        val csv = "$header\n116.397,39.908,1,0"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors[0].message.contains("required_successes", ignoreCase = true))
    }

    @Test
    fun `wrong column count is rejected`() {
        val csv = "$header\n116.397,39.908,1"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors[0].message.contains("column", ignoreCase = true))
    }

    @Test
    fun `missing header is rejected`() {
        val csv = "116.397,39.908,1,3"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors[0].message.contains("header", ignoreCase = true))
    }

    @Test
    fun `empty input is rejected`() {
        val result = WorklistParser.parse("")
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `header only file with zero data rows is rejected`() {
        // # F9 回归：零任务僵尸 plan 既不可完成也不可恢复，必须导入即拒
        val result = WorklistParser.parse("$header\n  \n")
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors[0].message.contains("no data rows", ignoreCase = true))
    }

    @Test
    fun `blank lines are skipped and csv row counts data rows only`() {
        val csv = "$header\n\n116.397,39.908,1,3\n   \n121.474,31.230,2,1\n"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Success)
        val rows = (result as ParseResult.Success).rows
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].csvRow)
        assertEquals(2, rows[1].csvRow)
    }

    @Test
    fun `import is atomic - all row errors reported and no partial success`() {
        val csv = """
            $header
            116.397,39.908,1,3
            116.397,95.0,1,3
            121.474,31.230,1,5
            113.2644,23.1291,2,0
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Failure)
        val errors = (result as ParseResult.Failure).errors
        assertEquals(2, errors.size)
        assertEquals(2, errors[0].csvRow)
        assertEquals(4, errors[1].csvRow)
    }

    // ---- #190：5 列 CI 契约（轨迹工具 plan.csv；期望 ECGI 直接入计划行） ----------

    private val ciHeader = WorklistParser.CI_HEADER

    @Test
    fun `ci contract file parses 5th column as expected ci`() {
        val csv = """
            $ciHeader
            29.9243986,49.8714584,3,3,28918569
            29.94462,49.8240183,3,3,29592117
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Success)
        val rows = (result as ParseResult.Success).rows
        assertEquals(2, rows.size)
        assertEquals(28918569L, rows[0].ci)
        assertEquals(29592117L, rows[1].ci)
        // CI 契约不带绑定列，向后兼容字段保持 null。
        assertTrue(rows.all { it.scheduleId == null && it.scheduleItemId == null })
    }

    @Test
    fun `legacy 4-column file keeps ci null`() {
        // 向后兼容：旧 4 列清单解析不变，ci = null（诚实缺席，绝不编造期望）。
        val csv = """
            $header
            116.397,39.908,1,3
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Success)
        assertNull((result as ParseResult.Success).rows.single().ci)
    }

    @Test
    fun `bound v2 file keeps ci null`() {
        // 绑定契约第 5/6 列是 schedule 语义，与 CI 契约互斥；ci 恒为 null。
        val csv = """
            $boundHeader
            116.397,39.908,9,2,schedule-generation-a,item-1
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Success)
        assertNull((result as ParseResult.Success).rows.single().ci)
    }

    @Test
    fun `ci at range boundaries is accepted`() {
        val csv = """
            $ciHeader
            29.9,49.8,3,3,0
            29.9,49.8,3,3,268435455
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Success)
        val rows = (result as ParseResult.Success).rows
        assertEquals(0L, rows[0].ci)
        assertEquals(268435455L, rows[1].ci)
    }

    @Test
    fun `ci above 28-bit range is rejected`() {
        // 268435456 = 2^28：越出 #193 同款值域即拒（fail-closed，绝不 clamp）。
        val csv = "$ciHeader\n29.9,49.8,3,3,268435456"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        val error = (result as ParseResult.Failure).errors.single()
        assertEquals(1, error.csvRow)
        assertTrue(error.message.contains("28-bit ECI"))
    }

    @Test
    fun `negative ci is rejected`() {
        val csv = "$ciHeader\n29.9,49.8,3,3,-1"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors.single().message.contains("28-bit ECI"))
    }

    @Test
    fun `non-numeric ci is rejected`() {
        val csv = "$ciHeader\n29.9,49.8,3,3,28a18569"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors.single().message.contains("28-bit ECI"))
    }

    @Test
    fun `blank ci is rejected`() {
        // 空 ci ≠ 无期望：CI 契约下行必须带值；要"无期望"请用 4 列契约。
        val csv = "$ciHeader\n29.9,49.8,3,3,"
        val result = WorklistParser.parse(csv)
        assertTrue(result is ParseResult.Failure)
        assertTrue((result as ParseResult.Failure).errors.single().message.contains("28-bit ECI"))
    }

    @Test
    fun `ci contract rejects 4-column rows atomically`() {
        val csv = """
            $ciHeader
            29.9,49.8,3,3,28918569
            29.9,49.8,3,3
        """.trimIndent()

        val result = WorklistParser.parse(csv)

        assertTrue(result is ParseResult.Failure)
        val error = (result as ParseResult.Failure).errors.single()
        assertEquals(2, error.csvRow)
        assertTrue(error.message.contains("5 columns"))
    }
}

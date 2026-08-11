package com.example.cellrebelauto.model.plan

import org.junit.Assert.assertEquals
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
}

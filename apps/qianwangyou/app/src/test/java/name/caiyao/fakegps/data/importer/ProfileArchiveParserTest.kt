package name.caiyao.fakegps.data.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import name.caiyao.fakegps.config.UnavailableFieldSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProfileArchiveParserTest {
    private val parser = ProfileArchiveParser()

    @Test
    fun `real CSV fixture preserves Unicode blanks third state and skips exact file duplicates`() {
        val ready = ready("valid-unicode.csv", fixture("valid-unicode.csv"))

        assertEquals(3, ready.dataRows)
        assertEquals(1, ready.duplicateRows)
        assertEquals(2, ready.records.size)
        assertEquals("基辅，家 🐾", ready.records[0].addname)
        assertEquals("Київ, Україна", ready.records[0].operatorName)
        assertEquals(68_719_476_735L, ready.records[0].nci)
        assertEquals("[{\"pci\":1}]", ready.records[0].neighborCellsJson)
        assertEquals("备用档案", ready.records[1].addname)
        assertEquals(setOf("tac"), UnavailableFieldSet.decode(ready.records[1].unavailableFields))
        assertEquals(1, ready.records[1].wifiHidden)
        assertFalse(ready.records.any { it.id != 0L })
    }

    @Test
    fun `real XLSX fixture reads shared inline numeric boolean and missing cells`() {
        val ready = ready("valid-unicode.xlsx", fixture("valid-unicode.xlsx"))

        assertEquals(2, ready.dataRows)
        assertEquals(0, ready.duplicateRows)
        assertEquals("基辅，家 🐾", ready.records[0].addname)
        assertEquals(50.4501, ready.records[0].latitude!!, 0.0)
        assertEquals(0, ready.records[0].wifiHidden)
        assertEquals("内联文字", ready.records[1].operatorName)
        assertEquals(null, ready.records[1].latitude)
        assertEquals(setOf("tac"), UnavailableFieldSet.decode(ready.records[1].unavailableFields))
    }

    @Test
    fun `later invalid row makes the whole analysis non confirmable`() {
        val invalid = invalid(
            "profiles.csv",
            "addname,latitude,longitude\nvalid,50,30\nbad,91,30\n".toByteArray(),
        )

        assertTrue(invalid.issues.any {
            it.code == ImportIssueCode.INVALID_VALUE && it.row == 3 && it.column == "latitude"
        })
    }

    @Test
    fun `headers reject id unknown duplicates and metadata-only schemas`() {
        val cases = listOf(
            "id,latitude\n1,50\n" to ImportIssueCode.FORBIDDEN_COLUMN,
            "latitude,typo\n50,x\n" to ImportIssueCode.UNKNOWN_COLUMN,
            "latitude,latitude\n50,51\n" to ImportIssueCode.DUPLICATE_COLUMN,
            "addname\nOnly a label\n" to ImportIssueCode.MISSING_CONFIG_COLUMN,
        )

        for ((csv, code) in cases) {
            assertTrue(invalid("profiles.csv", csv.toByteArray()).issues.any { it.code == code })
        }
    }

    @Test
    fun `CSV is strict UTF-8 and strict after a closing quote`() {
        val invalidUtf8 = byteArrayOf(
            'l'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'i'.code.toByte(),
            't'.code.toByte(), 'u'.code.toByte(), 'd'.code.toByte(), 'e'.code.toByte(), '\n'.code.toByte(),
            0xC3.toByte(), 0x28,
        )
        assertIssue("profiles.csv", invalidUtf8, ImportIssueCode.INVALID_ENCODING)
        assertIssue(
            "profiles.csv",
            "latitude\n\"50\"dirty\n".toByteArray(),
            ImportIssueCode.MALFORMED_FILE,
        )
    }

    @Test
    fun `XLSX formula is rejected even when it has a cached value`() {
        assertIssue(
            "formula.xlsx",
            fixture("formula.xlsx"),
            ImportIssueCode.FORMULA_NOT_ALLOWED,
        )
    }

    @Test
    fun `XLSX with multiple worksheets is rejected rather than silently dropping rows`() {
        assertIssue(
            "two-sheets.xlsx",
            fixture("two-sheets.xlsx"),
            ImportIssueCode.MULTIPLE_SHEETS,
        )
    }

    @Test
    fun `XLSX requires canonical workbook content type`() {
        val archive = rewriteZip(fixture("valid-unicode.xlsx")) { name, contents ->
            if (name != "[Content_Types].xml") return@rewriteZip contents
            contents.toString(StandardCharsets.UTF_8)
                .replace(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                    "application/xml",
                )
                .toByteArray(StandardCharsets.UTF_8)
        }

        assertIssue("profiles.xlsx", archive, ImportIssueCode.MALFORMED_FILE)
    }

    @Test
    fun `XLSX rejects cell types outside the canonical contract`() {
        val archive = rewriteZip(fixture("valid-unicode.xlsx")) { name, contents ->
            if (name != "xl/worksheets/sheet1.xml") return@rewriteZip contents
            contents.toString(StandardCharsets.UTF_8)
                .replace("<c r=\"A2\" t=\"s\">", "<c r=\"A2\" t=\"d\">")
                .toByteArray(StandardCharsets.UTF_8)
        }

        assertIssue("profiles.xlsx", archive, ImportIssueCode.MALFORMED_FILE)
    }

    @Test
    fun `XLSX rejects DOCTYPE independently of the XML byte encoding`() {
        val archive = rewriteZip(fixture("valid-unicode.xlsx")) { name, contents ->
            if (name != "[Content_Types].xml") return@rewriteZip contents
            contents.toString(StandardCharsets.UTF_8)
                .replace("encoding=\"UTF-8\"", "encoding=\"IBM037\"")
                .replace("?>", "?><!DOCTYPE Types [<!ELEMENT Types ANY>]>")
                .toByteArray(Charset.forName("IBM037"))
        }

        assertIssue("profiles.xlsx", archive, ImportIssueCode.MALFORMED_FILE)
    }

    @Test
    fun `extension content mismatch and oversized input fail closed`() {
        assertIssue("profiles.xlsx", fixture("valid-unicode.csv"), ImportIssueCode.FILE_TYPE_MISMATCH)
        assertIssue("profiles.csv", fixture("valid-unicode.xlsx"), ImportIssueCode.FILE_TYPE_MISMATCH)
        assertIssue("profiles.csv", ByteArray(2 * 1024 * 1024 + 1), ImportIssueCode.FILE_TOO_LARGE)
    }

    @Test
    fun `numeric boolean JSON and third-state boundaries are validated`() {
        val csv = """
            addname,latitude,longitude,nci,wifi_hidden,neighbor_cells_json,tac
            ok,-90,180,68719476735,true,"[]",--
            bad-lat,-90.0001,0,1,0,"[]",1
            bad-nci,0,0,68719476736,0,"[]",1
            bad-bool,0,0,1,yes,"[]",1
            bad-json,0,0,1,0,"{}",1
        """.trimIndent().toByteArray()

        val invalid = invalid("profiles.csv", csv)
        assertTrue(invalid.issues.any { it.row == 3 && it.column == "latitude" })
        assertTrue(invalid.issues.any { it.row == 4 && it.column == "nci" })
        assertTrue(invalid.issues.any { it.row == 5 && it.column == "wifi_hidden" })
        assertTrue(invalid.issues.any { it.row == 6 && it.column == "neighbor_cells_json" })
    }

    @Test
    fun `UTF-8 BOM quoted newline and empty records follow RFC 4180`() {
        val csv = "\uFEFFaddname,operator_name,tac\r\n" +
            "\"line one\nline two\",\"Carrier, Inc.\",5\r\n\r\n"

        val ready = ready("profiles.csv", csv.toByteArray(StandardCharsets.UTF_8))
        assertEquals(1, ready.dataRows)
        assertEquals("line one\nline two", ready.records.single().addname)
        assertEquals("Carrier, Inc.", ready.records.single().operatorName)
    }

    @Test
    fun `missing display name uses content signature while legacy XLS stays unsupported`() {
        assertEquals(2, ready(null, fixture("valid-unicode.xlsx")).records.size)
        assertIssue(null, byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()), ImportIssueCode.UNSUPPORTED_FILE)
    }

    // ------------------------------------------------------------------
    // #189：站点表第 4 列 ci（28-bit ECI，十进制）——日程项位置切换时 serving
    // cell 的 ci 跟随伪造的数据入口。列可空：3 列旧档案 ci=null = 读回门跳过。
    // ------------------------------------------------------------------

    @Test
    fun `four column site table with ci parses the decimal 28-bit ECI verbatim`() {
        // 真实值域样本（#189 调研报告 §2：28,292,129–100,839,199，零换算直填）
        val csv = """
            addname,latitude,longitude,ci
            Lvivska-37054241,49.8397,24.0297,37054241
            Kyivska-28292129,50.4501,30.5234,28292129
            Frankivska-100839199,48.9226,24.7111,100839199
        """.trimIndent().toByteArray()

        val records = ready("site-table.csv", csv).records
        assertEquals(3, records.size)
        assertEquals(37_054_241L, records[0].ci!!.toLong())
        assertEquals(28_292_129L, records[1].ci!!.toLong())
        assertEquals(100_839_199L, records[2].ci!!.toLong())
    }

    @Test
    fun `three column legacy archive keeps ci null so the readback gate skips the leg`() {
        val csv = """
            addname,latitude,longitude
            legacy-1,50.0,30.0
            legacy-2,49.0,29.0
        """.trimIndent().toByteArray()

        val records = ready("legacy.csv", csv).records
        assertEquals(2, records.size)
        assertNull(records[0].ci)
        assertNull(records[1].ci)
    }

    @Test
    fun `ci beyond the 28-bit domain is rejected`() {
        val csv = """
            addname,latitude,longitude,ci
            overflow,50.0,30.0,268435456
        """.trimIndent().toByteArray()

        assertTrue(
            invalid("site-table.csv", csv).issues.any {
                it.code == ImportIssueCode.INVALID_VALUE && it.column == "ci"
            },
        )
    }

    @Test
    fun `ci column position is free because headers bind by name`() {
        // 站点表 owner 原始列序是 custom_admin_3,latitude1,longitude1,ECGI——
        // 导入前由轨迹工具改名/换序，但解析器本身按 header 名绑定，不依赖列序。
        val csv = """
            ci,addname,latitude,longitude
            37054241,reordered,49.8397,24.0297
        """.trimIndent().toByteArray()

        val records = ready("site-table.csv", csv).records
        assertEquals("reordered", records.single().addname)
        assertEquals(37_054_241L, records.single().ci!!.toLong())
    }

    private fun ready(name: String?, bytes: ByteArray): ProfileImportAnalysis.Ready {
        return when (val result = parser.parse(name, bytes)) {
            is ProfileImportAnalysis.Ready -> result
            is ProfileImportAnalysis.Invalid -> fail(
                "expected ready, got ${result.issues.joinToString { "${it.code}:${it.message}" }}",
            ) as Nothing
        }
    }

    private fun invalid(name: String?, bytes: ByteArray): ProfileImportAnalysis.Invalid {
        return when (val result = parser.parse(name, bytes)) {
            is ProfileImportAnalysis.Invalid -> result
            is ProfileImportAnalysis.Ready -> fail("expected invalid, got ${result.records.size} records") as Nothing
        }
    }

    private fun assertIssue(name: String?, bytes: ByteArray, code: ImportIssueCode) {
        val issue = invalid(name, bytes).issues.firstOrNull { it.code == code }
        assertNotNull("expected $code", issue)
    }

    private fun fixture(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/profile-import/$name")
        assertNotNull("missing fixture $name", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun rewriteZip(
        source: ByteArray,
        rewrite: (name: String, contents: ByteArray) -> ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(source)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    target.write(rewrite(entry.name, input.readBytes()))
                    target.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }
}

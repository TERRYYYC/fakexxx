package name.caiyao.fakegps.ui

import name.caiyao.fakegps.data.DownloadCsvScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.1-3 Download 档案扫描的 pure oracle：新文件发现按「内容指纹」判重，
 * 已导入/已关闭的文件不再提示。
 *
 * Killing mutation: newCandidates 改成按文件名判重 —— 同名不同内容的重推文件
 * 会被误判为已导入，第一条断言变红。
 */
class DownloadCsvScannerTest {

    private val csvA = DownloadCsvScanner.Entry("profiles-a.csv", fingerprint = "fp-a")
    private val csvA2 = DownloadCsvScanner.Entry("profiles-a.csv", fingerprint = "fp-a2")
    private val csvB = DownloadCsvScanner.Entry("profiles-b.csv", fingerprint = "fp-b")

    @Test
    fun `candidates exclude seen fingerprints - same name new content still surfaces`() {
        val candidates = DownloadCsvScanner.newCandidates(
            files = listOf(csvA, csvA2, csvB),
            seen = setOf("fp-a"),
        )
        // 同名不同内容 = 重推的新文件，必须再次提示；按名判重会漏掉它。
        assertEquals(listOf(csvA2, csvB), candidates)
    }

    @Test
    fun `all seen files project an empty candidate list`() {
        val candidates = DownloadCsvScanner.newCandidates(
            files = listOf(csvA, csvB),
            seen = setOf("fp-a", "fp-b"),
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `only csv files are scanned`() {
        assertTrue(DownloadCsvScanner.isCsv("worklist.CSV"))
        assertTrue(DownloadCsvScanner.isCsv("loc-01.csv"))
        assertFalse(DownloadCsvScanner.isCsv("notes.txt"))
        assertFalse(DownloadCsvScanner.isCsv("archive.zip"))
        assertFalse(DownloadCsvScanner.isCsv("csv"))
    }

    @Test
    fun `fingerprint is content sha256 - distinct content never collides`() {
        val first = DownloadCsvScanner.fingerprintOf("addname,latitude\nloc-01,50.1\n".toByteArray())
        val same = DownloadCsvScanner.fingerprintOf("addname,latitude\nloc-01,50.1\n".toByteArray())
        val changed = DownloadCsvScanner.fingerprintOf("addname,latitude\nloc-01,50.2\n".toByteArray())
        assertEquals(first, same)
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
    }
}

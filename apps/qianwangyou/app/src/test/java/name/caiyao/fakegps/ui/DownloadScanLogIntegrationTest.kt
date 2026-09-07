package name.caiyao.fakegps.ui

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import name.caiyao.fakegps.data.DownloadCsvArchive
import name.caiyao.fakegps.data.DownloadCsvScanner
import name.caiyao.fakegps.data.ImportScanLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P0.1-3 Download 扫描 → 已导入记录 → 不再提示 的集成链（Robolectric lane）。
 *
 * 实测痛点：CSV 只能 adb push 后人工在 SAF 里翻找；扫描把 Download/ 里「从未导入过的」
 * 档案文件一次性送到用户面前，内容指纹保证重推同名不同内容的文件仍会被发现。
 */
@RunWith(RobolectricTestRunner::class)
class DownloadScanLogIntegrationTest {

    private fun downloadsDir(): java.io.File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    @Test
    fun `scan finds csv files and a seen fingerprint never resurfaces`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = downloadsDir()
        dir.mkdirs()
        val csv = java.io.File(dir, "loc-plan-01.csv")
        csv.writeBytes("addname,latitude\nloc-01,50.1\n".toByteArray())
        val txt = java.io.File(dir, "notes.txt")
        txt.writeBytes("not a csv".toByteArray())

        try {
            val scanned = DownloadCsvArchive.scan(app)
            assertEquals(
                "only csv files surface, with content fingerprints",
                listOf("loc-plan-01.csv"),
                scanned.map { it.displayName },
            )
            assertTrue(scanned.single().fingerprint.isNotEmpty())

            val log = ImportScanLog(app)
            // 用户关闭提示条（或导入完成）→ 指纹入档 → 下次进入不再提示。
            log.markSeen(listOf(scanned.single().fingerprint))
            val seen = log.seen()
            assertEquals(listOf("loc-plan-01.csv"), scanned.map { it.displayName })
            assertEquals(
                "seen fingerprint is excluded from candidates",
                emptyList<name.caiyao.fakegps.data.DownloadCsvScanner.Entry>(),
                DownloadCsvScanner.newCandidates(scanned, seen),
            )

            // 重推同名但内容不同的文件 → 新指纹 → 必须再次提示。
            csv.writeBytes("addname,latitude\nloc-01,50.2\n".toByteArray())
            val rescanned = DownloadCsvArchive.scan(app)
            assertEquals(
                "same name new content resurfaces as a new candidate",
                1,
                DownloadCsvScanner.newCandidates(rescanned, seen).size,
            )
        } finally {
            csv.delete()
            txt.delete()
        }
    }

    @Test
    fun `a directory without csv files projects an empty scan`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = downloadsDir()
        dir.mkdirs()
        assertEquals(emptyList<name.caiyao.fakegps.data.DownloadCsvScanner.Entry>(), DownloadCsvArchive.scan(app))
    }
}

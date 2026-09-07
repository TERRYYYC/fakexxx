package name.caiyao.fakegps.data

import java.security.MessageDigest

/**
 * P0.1-3 Download 档案扫描的 pure 判定层：候选过滤（只看 .csv）与按「内容指纹」判重。
 * 指纹判重（而非文件名）是刻意的：adb push 重推同名但内容不同的档案时必须再次提示。
 *
 * Android 侧的目录/URI 读取在 [DownloadCsvArchive]；本层保持 JVM 可测。
 */
object DownloadCsvScanner {

    /** 一份被扫到的文件：显示名 + SHA-256 内容指纹（hex）。 */
    data class Entry(
        val displayName: String,
        val fingerprint: String,
        val localPath: String? = null,
    )

    private const val CSV_SUFFIX = ".csv"

    fun isCsv(fileName: String): Boolean = fileName.lowercase().endsWith(CSV_SUFFIX)

    fun fingerprintOf(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }

    /** 已导入/已关闭（seen 指纹集合）之外的文件才算新候选，保持入参顺序。 */
    fun newCandidates(files: List<Entry>, seen: Set<String>): List<Entry> =
        files.filter { it.fingerprint !in seen }
}

package name.caiyao.fakegps.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * P0.1-3 Download/ 目录的档案文件发现（Android 粘合层）。
 *
 * 两条读取路径，均失败即静默返回空（扫描永远不能让收藏页崩溃或报错）：
 *  1. MediaStore Downloads 集合（API 29+，SAF 之外唯一受支持的他方文件读取面）；
 *  2. File API 直读公共 Download 目录（旧平台 / OEM 授权了全量存储的设备）。
 *
 * 每个候选读全量内容算 SHA-256 指纹——档案 CSV 只有几十 KB，成本可忽略，
 * 换来「重推同名不同内容仍会被发现」的可靠判重。
 */
object DownloadCsvArchive {

    fun scan(context: Context): List<DownloadCsvScanner.Entry> {
        val entries = mutableListOf<DownloadCsvScanner.Entry>()
        runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scanMediaStore(context, entries) }
        runCatching { scanPublicDirectory(entries) }
        return entries
            .distinctBy { it.fingerprint }
            .sortedBy { it.displayName }
    }

    private fun scanMediaStore(context: Context, out: MutableList<DownloadCsvScanner.Entry>) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns._ID,
        )
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getStringOrNull(MediaStore.MediaColumns.DISPLAY_NAME) ?: continue
                if (!DownloadCsvScanner.isCsv(name)) continue
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    id,
                )
                val fingerprint = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        DownloadCsvScanner.fingerprintOf(input.readBytes())
                    }
                }.getOrNull() ?: continue
                out += DownloadCsvScanner.Entry(name, fingerprint)
            }
        }
    }

    private fun scanPublicDirectory(out: MutableList<DownloadCsvScanner.Entry>) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile || !DownloadCsvScanner.isCsv(file.name)) continue
            val fingerprint = runCatching {
                DownloadCsvScanner.fingerprintOf(file.readBytes())
            }.getOrNull() ?: continue
            out += DownloadCsvScanner.Entry(file.name, fingerprint, localPath = file.absolutePath)
        }
    }

    private fun android.database.Cursor.getStringOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}

package com.example.cellrebelauto.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.cellrebelauto.model.plan.AttemptWithTask
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports attempt rows to a CSV file in the Downloads folder.
 * # 将尝试行导出为 CSV 文件到 Downloads 目录
 */
class CsvExporter(private val context: Context) {

    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * Exports attempt rows (plus legacy v2 rows, appended after) to the
     * 16-column audit CSV (AC-C3, C1, F003). Returns the file name.
     * # 导出尝试行 + v2 遗留行（排在其后）为 16 列审计 CSV，返回文件名
     */
    fun exportAttempts(
        attempts: List<AttemptWithTask>,
        legacyResults: List<com.example.cellrebelauto.model.TestResult> = emptyList()
    ): String {
        val fileName = "cellrebel_attempts_${fileNameFormat.format(Date())}.csv"
        val stream = createOutputStream(fileName)
            ?: throw IllegalStateException("Cannot create output file")

        stream.use { out ->
            val writer = out.bufferedWriter()
            writer.write(AttemptCsvMapper.HEADER.joinToString(","))
            writer.newLine()
            for (row in AttemptCsvMapper.toCsvRows(attempts, legacyResults)) {
                writer.write(row.joinToString(","))
                writer.newLine()
            }
            writer.flush()
        }
        return fileName
    }

    // # 创建输出流，兼容 Android 10+ 和旧版本
    private fun createOutputStream(fileName: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            uri?.let { context.contentResolver.openOutputStream(it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, fileName).outputStream()
        }
    }
}

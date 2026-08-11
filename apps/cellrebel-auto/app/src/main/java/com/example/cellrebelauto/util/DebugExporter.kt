package com.example.cellrebelauto.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug utility for exporting logs and accessibility tree dumps.
 *
 * # 调试工具类：导出运行日志和无障碍节点树
 * # 文件保存到 Downloads 目录，方便用 PC 查看
 */
class DebugExporter(private val context: Context) {

    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // ==================== Log Export ====================

    /**
     * Exports the in-app log entries to a .txt file in Downloads.
     * Returns the file name on success.
     *
     * # 将 App 内的日志条目导出为 .txt 文件到 Downloads 目录
     * # 成功返回文件名
     */
    fun exportLogs(logs: List<String>): String {
        val fileName = "cellrebel_log_${timestampFormat.format(Date())}.txt"
        val stream = createOutputStream(fileName, "text/plain")
            ?: throw IllegalStateException("Cannot create log output file")

        stream.use { out ->
            val writer = out.bufferedWriter()

            // # 文件头：导出时间和日志条数
            writer.write("=== CellRebel Auto - Log Export ===")
            writer.newLine()
            writer.write("Exported at: ${logTimeFormat.format(Date())}")
            writer.newLine()
            writer.write("Total entries: ${logs.size}")
            writer.newLine()
            writer.write("=".repeat(50))
            writer.newLine()
            writer.newLine()

            // # 逐行写入日志
            for (line in logs) {
                writer.write(line)
                writer.newLine()
            }

            writer.flush()
        }
        return fileName
    }

    // ==================== Accessibility Tree Dump ====================

    /**
     * Dumps the accessibility tree of the given root node to a .txt file.
     * Shows hierarchy with indentation, including:
     *   - Class name
     *   - Text content
     *   - Content description
     *   - Clickable/Focusable/Editable flags
     *   - Bounds on screen
     *   - View ID resource name
     *
     * # 将无障碍节点树导出为带缩进的 .txt 文件
     * # 包含：类名、文本、描述、交互属性、屏幕坐标、ViewID
     *
     * @param root Root node of the tree / 树的根节点
     * @param packageName The foreground app package / 前台应用包名
     */
    fun dumpAccessibilityTree(
        root: AccessibilityNodeInfo,
        packageName: String
    ): String {
        val fileName = "a11y_dump_${timestampFormat.format(Date())}.txt"
        val stream = createOutputStream(fileName, "text/plain")
            ?: throw IllegalStateException("Cannot create dump output file")

        stream.use { out ->
            val writer = out.bufferedWriter()

            // # 文件头
            writer.write("=== Accessibility Tree Dump ===")
            writer.newLine()
            writer.write("Package: $packageName")
            writer.newLine()
            writer.write("Dumped at: ${logTimeFormat.format(Date())}")
            writer.newLine()
            writer.write("=".repeat(50))
            writer.newLine()
            writer.newLine()

            // # 统计信息
            var totalNodes = 0
            var clickableCount = 0
            var editableCount = 0

            // # 递归遍历节点树
            fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
                totalNodes++
                val indent = "  ".repeat(depth)
                val prefix = if (depth > 0) "${indent}├─ " else ""

                val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                val viewId = node.viewIdResourceName
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // # 构建节点描述行
                val line = buildString {
                    append("$prefix[$className]")

                    if (!text.isNullOrEmpty()) {
                        // # 文本内容（截断过长文本）
                        val displayText = if (text.length > 80) text.take(80) + "..." else text
                        append(" text=\"$displayText\"")
                    }
                    if (!desc.isNullOrEmpty()) {
                        append(" desc=\"$desc\"")
                    }
                    if (viewId != null) {
                        append(" id=$viewId")
                    }

                    // # 交互属性标志
                    val flags = mutableListOf<String>()
                    if (node.isClickable) { flags.add("CLICK"); clickableCount++ }
                    if (node.isLongClickable) flags.add("LONG_CLICK")
                    if (node.isFocusable) flags.add("FOCUS")
                    if (node.isEditable) { flags.add("EDIT"); editableCount++ }
                    if (node.isScrollable) flags.add("SCROLL")
                    if (node.isCheckable) flags.add("CHECK")
                    if (node.isChecked) flags.add("CHECKED")
                    if (node.isEnabled) { /* skip, too common */ } else flags.add("DISABLED")

                    if (flags.isNotEmpty()) {
                        append(" [${flags.joinToString(",")}]")
                    }

                    // # 屏幕坐标
                    append(" bounds=(${bounds.left},${bounds.top},${bounds.right},${bounds.bottom})")
                }

                writer.write(line)
                writer.newLine()

                // # 递归子节点
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    dumpNode(child, depth + 1)
                }
            }

            dumpNode(root, 0)

            // # 写入统计摘要
            writer.newLine()
            writer.write("=".repeat(50))
            writer.newLine()
            writer.write("Summary: $totalNodes nodes, $clickableCount clickable, $editableCount editable")
            writer.newLine()

            writer.flush()
        }
        return fileName
    }

    /**
     * Returns a tree dump as a String (for in-app display / Logcat).
     * Lighter version without file I/O.
     *
     * # 返回节点树的字符串表示（用于 App 内显示或 Logcat）
     * # 不涉及文件 I/O 的轻量版本
     */
    fun dumpToString(root: AccessibilityNodeInfo, maxDepth: Int = 10): String {
        val sb = StringBuilder()
        dumpNodeToString(root, 0, maxDepth, sb)
        return sb.toString()
    }

    private fun dumpNodeToString(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        sb: StringBuilder
    ) {
        if (depth > maxDepth) {
            sb.appendLine("${"  ".repeat(depth)}... (max depth)")
            return
        }

        val indent = "  ".repeat(depth)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()?.take(40) ?: ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""

        val flags = buildString {
            if (node.isClickable) append("C")
            if (node.isEditable) append("E")
            if (node.isScrollable) append("S")
        }

        sb.appendLine("$indent[$className] $text ${if (desc.isNotEmpty()) "desc=$desc" else ""} ${if (flags.isNotEmpty()) "($flags)" else ""}")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeToString(child, depth + 1, maxDepth, sb)
        }
    }

    // ==================== File I/O ====================

    // # 创建输出流，兼容 Android 10+ 和旧版本
    private fun createOutputStream(fileName: String, mimeType: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
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

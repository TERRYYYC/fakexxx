package com.example.cellrebelauto.util

import android.content.Context
import java.io.File

/**
 * T7 P1.2 — the disk-backed ring log.
 *
 * The 200-line in-memory ring dies with the process; unattended runs need the
 * trail on disk so a diagnostic bundle can carry the WHOLE run. The file caps
 * at ~[DEFAULT_MAX_BYTES] by dropping the OLDEST whole lines; the newest line
 * is never dropped. This is strictly ADDITIVE observability: the in-memory
 * StateFlow and the logcat WARN lines keep their existing behavior — nothing
 * is replaced.
 *
 * # 环形日志落盘：~2MB 封顶、裁最旧保最新；内存流与 logcat 输出不受影响
 */
class RollingLogFile(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    /** Appends one line (a trailing newline is added if missing). Synchronized. */
    fun append(line: String) {
        synchronized(lock) {
            val normalized = if (line.endsWith("\n")) line else "$line\n"
            val existing = if (file.isFile) file.readText() else ""
            file.writeText(trimToCap(existing, normalized, maxBytes))
        }
    }

    /** The whole persisted trail, oldest first. */
    fun readText(): String = synchronized(lock) {
        if (file.isFile) file.readText() else ""
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024

        /**
         * The pure trim rule: when existing+incoming exceeds the cap, drop
         * whole OLDEST lines until it fits; the incoming line is always kept
         * in full (it alone may exceed the cap only for pathological input —
         * then the head of the file content is truncated whole-line anyway).
         * Byte-accurate on UTF-8.
         */
        fun trimToCap(existing: String, incoming: String, maxBytes: Long): String {
            val merged = existing + incoming
            if (merged.toByteArray().size <= maxBytes) return merged
            var content = merged
            // Drop whole lines from the head until the content fits.
            while (content.toByteArray().size > maxBytes) {
                val firstNewline = content.indexOf('\n')
                if (firstNewline < 0) {
                    // Single pathological line longer than the cap: hard head-trim.
                    var bytes = content.toByteArray()
                    while (bytes.size > maxBytes) {
                        bytes = bytes.copyOfRange(1, bytes.size)
                    }
                    return String(bytes)
                }
                content = content.substring(firstNewline + 1)
            }
            return content
        }
    }
}

/**
 * Pure diff between two successive snapshots of the in-memory log ring, so a
 * collector can append to [RollingLogFile] without re-writing history:
 *  - normal growth (current extends previous) → only the new tail;
 *  - WHOLESALE RESET (a new engine run replaces the list with a fresh one) →
 *    the entire current list (the caller prefixes a run separator);
 *  - empty previous → the whole current list.
 *
 * # 日志流差分：正常增长只取新增尾部；换引擎整表重置时返回全量，绝不漏行也不重放
 */
object LogDiff {

    fun newLines(previous: List<String>, current: List<String>): List<String> {
        if (previous.isEmpty()) return current
        if (current.size >= previous.size && current.subList(0, previous.size) == previous) {
            return current.subList(previous.size, current.size)
        }
        return current
    }
}

/**
 * Where the diagnostics live inside THIS app's private storage (the bundle
 * exporter and the service-side collector must agree on the same file).
 * # 诊断文件路径约定：落盘环形日志的唯一位置
 */
object DiagnosticFiles {

    fun rollingLogFile(context: Context): File =
        File(File(context.filesDir, "diagnostics"), "rolling-log.txt")
}

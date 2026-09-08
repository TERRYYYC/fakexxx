package com.example.cellrebelauto.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T7 P1.2 — the rolling log persistence oracle.
 *
 * The 200-line in-memory ring dies with the process; unattended runs need the
 * trail on disk. The ring file caps at ~2 MB by dropping OLDEST whole lines
 * and always keeps the newest content; the in-memory flow and the logcat WARN
 * output stay untouched (persistence is additive, never a replacement).
 *
 * Killing mutations:
 *  - a writer that grows unbounded fails the cap test;
 *  - a trim that drops the newest lines fails the tail-survival test;
 *  - a diff that re-appends the whole history on every emission fails the
 *    incremental-append test;
 *  - a diff that misses the engine's wholesale log reset (new run → fresh
 *    engine list) fails the wholesale-reset test.
 *
 * # 环形日志落盘 oracle：封顶裁最旧、保最新；增量追加；整表重置不漏
 */
class RollingLogFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newFile(): File = File(tmp.root, "rolling-log.txt")

    // ---- append + readback ------------------------------------------------------

    @Test
    fun `append writes one line per entry and readback returns them`() {
        val log = RollingLogFile(newFile())
        log.append("[00:00:01] first")
        log.append("[00:00:02] second")
        val text = log.readText()
        assertTrue(text.contains("[00:00:01] first"))
        assertTrue(text.contains("[00:00:02] second"))
        assertEquals(2, text.trim().lines().size)
    }

    @Test
    fun `append creates the parent directories when missing`() {
        val nested = File(tmp.root, "diagnostics/nested/rolling-log.txt")
        val log = RollingLogFile(nested)
        log.append("[00:00:01] hello")
        assertTrue(nested.isFile)
        assertTrue(log.readText().contains("hello"))
    }

    // ---- the 2 MB cap -------------------------------------------------------------

    @Test
    fun `file stays under the cap after heavy writes`() {
        val cap = 2L * 1024 * 1024
        val log = RollingLogFile(newFile(), maxBytes = cap)
        val line = "x".repeat(1023) // 1 KB per line with newline
        repeat(4 * 1024) { log.append("[00:00:01] $line") }
        assertTrue(
            "file grew to ${newFile().length()} bytes",
            newFile().length() <= cap
        )
    }

    @Test
    fun `newest lines survive the trim and oldest are dropped`() {
        val cap = 10_000L
        val log = RollingLogFile(newFile(), maxBytes = cap)
        repeat(100) { i -> log.append("LINE-$i-${"y".repeat(200)}") }
        val text = log.readText()
        assertTrue("newest line lost", text.contains("LINE-99"))
        assertFalse("oldest line should have been trimmed", text.contains("LINE-0-"))
    }

    @Test
    fun `trim keeps whole lines and always keeps the incoming line`() {
        val existing = (0 until 10).joinToString("") { "OLD-$it-${"o".repeat(100)}\n" }
        val incoming = "NEW-line-${"n".repeat(50)}\n"
        val trimmed = RollingLogFile.trimToCap(existing, incoming, maxBytes = 500)
        assertTrue(trimmed.toByteArray().size <= 500)
        assertTrue("incoming line must never be dropped", trimmed.contains(incoming.trimEnd()))
        assertTrue("trim must keep whole lines", trimmed.endsWith("\n"))
    }

    @Test
    fun `trim is a no-op when under the cap`() {
        // trimToCap MERGES existing+incoming; under the cap the merge is returned as-is.
        assertEquals("A\nB\nC\n", RollingLogFile.trimToCap("A\nB\n", "C\n", maxBytes = 100))
    }

    // ---- LogDiff: flow observation without double-append ---------------------------

    @Test
    fun `diff appends only the new tail`() {
        assertEquals(
            listOf("c"),
            LogDiff.newLines(previous = listOf("a", "b"), current = listOf("a", "b", "c"))
        )
    }

    @Test
    fun `diff is empty when nothing arrived`() {
        assertEquals(
            emptyList<String>(),
            LogDiff.newLines(previous = listOf("a"), current = listOf("a"))
        )
    }

    @Test
    fun `diff detects the wholesale reset of a new engine run`() {
        val previous = listOf("[00:00:01] old run line 1", "[00:00:02] old run line 2")
        val current = listOf("[00:10:00] new run line 1")
        assertEquals(current, LogDiff.newLines(previous, current))
    }

    @Test
    fun `diff with empty previous emits the whole current list`() {
        assertEquals(
            listOf("a", "b"),
            LogDiff.newLines(previous = emptyList(), current = listOf("a", "b"))
        )
    }
}

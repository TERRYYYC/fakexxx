package name.caiyao.fakegps.integration.v1

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

class FileDurableKvJournalTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `append journal reopens every contiguous event without rewriting earlier records`() {
        val directory = folder.newFolder("audit-journal")
        val clock = object : MonotonicClock {
            override fun elapsedRealtimeMs() = 100L
            override fun epochMs() = 1_000L
        }
        val first = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
        val one = first.append("observe", operationId = "one")
        val journal = File(directory, "environment-control-v1.journal")
        val firstRecord = journal.readBytes()
        val two = first.append("observe", operationId = "two")

        assertEquals(firstRecord.toList(), journal.readBytes().take(firstRecord.size))
        assertEquals(listOf(one, two), DurableIntegrationAuditStore(FileDurableKv(directory), clock).all())
    }

    @Test
    fun `reopen preserves an incomplete suffix until the next append discards it`() {
        val directory = folder.newFolder("torn-journal")
        val clock = object : MonotonicClock {
            override fun elapsedRealtimeMs() = 100L
            override fun epochMs() = 1_000L
        }
        val audit = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
        val first = audit.append("observe", operationId = "one")
        val journal = File(directory, "environment-control-v1.journal")
        val committedSize = journal.length()
        journal.appendText("#128:${"0".repeat(64)}\npartial")

        val reopened = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
        assertEquals(listOf(first), reopened.all())
        assertTrue(journal.length() > committedSize)
        assertEquals(2L, reopened.append("observe", operationId = "two").seq)
        assertTrue(journal.length() > committedSize)
    }

    @Test
    fun `a legacy snapshot stays readable after new journal commits`() {
        val directory = folder.newFolder("legacy-snapshot")
        File(directory, "environment-control-v1.kv").writeText("legacy\u001Fkey\u001Fbefore-journal\n")

        val store = FileDurableKv(directory)
        assertEquals("before-journal", store.read("legacy", "key"))
        store.write("current", "key", "after-journal")

        val reopened = FileDurableKv(directory)
        assertEquals("before-journal", reopened.read("legacy", "key"))
        assertEquals("after-journal", reopened.read("current", "key"))
    }

    @Test
    fun `partial journal write fails closed for its owner and a fresh owner safely retries`() {
        val directory = folder.newFolder("partial-journal-write")
        val clock = fixedClock()
        val kv = JournalFaultKv(directory)
        val audit = DurableIntegrationAuditStore(kv, clock)
        repeat(8) { audit.append("observe", operationId = "before-$it") }
        kv.fault = JournalFault.PARTIAL_WRITE

        assertThrows(IOException::class.java) { audit.append("observe", operationId = "failed") }
        assertOwnerStopped(audit)

        val reopened = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
        assertEquals((1L..8L).toList(), reopened.all().map { it.seq })
        assertEquals(9L, reopened.append("observe", operationId = "after-reopen").seq)
    }

    @Test
    fun `full frame sync failure fails closed for its owner without reusing durable sequence`() {
        val directory = folder.newFolder("sync-journal-write")
        val clock = fixedClock()
        val kv = JournalFaultKv(directory)
        val audit = DurableIntegrationAuditStore(kv, clock)
        repeat(8) { audit.append("observe", operationId = "before-$it") }
        kv.fault = JournalFault.SYNC_AFTER_COMPLETE_WRITE

        assertThrows(SyncFailedException::class.java) { audit.append("observe", operationId = "failed") }
        assertOwnerStopped(audit)

        val reopened = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
        val durable = reopened.resolve(9L)
        assertEquals("failed", durable!!.operationId)
        assertEquals(10L, reopened.append("observe", operationId = "after-reopen").seq)
    }

    private fun assertOwnerStopped(audit: IntegrationAuditStore) {
        assertThrows(IllegalStateException::class.java) { audit.all() }
        assertThrows(IllegalStateException::class.java) { audit.append("observe", operationId = "retry") }
    }

    private fun fixedClock() = object : MonotonicClock {
        override fun elapsedRealtimeMs() = 100L
        override fun epochMs() = 1_000L
    }

    private enum class JournalFault { NONE, PARTIAL_WRITE, SYNC_AFTER_COMPLETE_WRITE }

    /** New journal-I/O seam: it mutates the actual target before it throws. */
    private class JournalFaultKv(directory: File) : FileDurableKv(directory) {
        var fault = JournalFault.NONE

        override fun appendJournalRecord(target: File, bytes: ByteArray) {
            when (fault) {
                JournalFault.PARTIAL_WRITE -> {
                    FileOutputStream(target, true).use { output ->
                        output.write(bytes, 0, minOf(bytes.size, 70))
                        output.fd.sync()
                    }
                    throw IOException("injected journal write failure after durable prefix")
                }
                JournalFault.SYNC_AFTER_COMPLETE_WRITE -> {
                    FileOutputStream(target, true).use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    throw SyncFailedException("injected journal sync failure after complete frame")
                }
                JournalFault.NONE -> super.appendJournalRecord(target, bytes)
            }
        }
    }
}

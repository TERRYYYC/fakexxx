package name.caiyao.fakegps.integration.v1

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
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
}

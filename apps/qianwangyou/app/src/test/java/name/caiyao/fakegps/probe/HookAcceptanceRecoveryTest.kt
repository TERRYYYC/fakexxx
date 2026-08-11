package name.caiyao.fakegps.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookAcceptanceRecoveryTest {

    @Test
    fun preparePersistsPreviousPayloadBeforeOverrideIsAllowed() {
        val events = mutableListOf<String>()
        val record = FakeRecord(events)
        val transport = FakeTransport(events, current = """{"fields":{"tac":7}}""")
        val recovery = HookAcceptanceRecoveryCoordinator(record, transport)

        assertTrue(recovery.prepare())
        assertEquals(
            listOf("transport.read", "record.save"),
            events,
        )
        assertEquals("""{"fields":{"tac":7}}""", record.pending)
        assertEquals("""{"fields":{"tac":7}}""", transport.current)
    }

    @Test
    fun pendingRecoveryPublishesPreviousPayloadBeforeClearingRecord() {
        val events = mutableListOf<String>()
        val record = FakeRecord(events, pending = """{"fields":{"tac":7}}""")
        val transport = FakeTransport(events, current = """{"fields":{"tac":99}}""")
        val recovery = HookAcceptanceRecoveryCoordinator(record, transport)

        assertTrue(recovery.recoverIfPending())
        assertEquals(
            listOf("record.read", "transport.publish", "record.clear"),
            events,
        )
        assertEquals("""{"fields":{"tac":7}}""", transport.current)
        assertEquals(null, record.pending)
    }

    @Test
    fun failedRecoveryPublishKeepsDurableRecordForNextLaunch() {
        val events = mutableListOf<String>()
        val record = FakeRecord(events, pending = """{"fields":{"tac":7}}""")
        val transport = FakeTransport(
            events,
            current = """{"fields":{"tac":99}}""",
            publishSucceeds = false,
        )
        val recovery = HookAcceptanceRecoveryCoordinator(record, transport)

        assertFalse(recovery.recoverIfPending())
        assertEquals(
            listOf("record.read", "transport.publish"),
            events,
        )
        assertEquals("""{"fields":{"tac":7}}""", record.pending)
    }

    @Test
    fun completedDatabaseRestoreClearsPendingRecord() {
        val events = mutableListOf<String>()
        val record = FakeRecord(events, pending = """{"fields":{"tac":7}}""")
        val recovery = HookAcceptanceRecoveryCoordinator(
            record,
            FakeTransport(events, current = """{"fields":{"tac":7}}"""),
        )

        assertTrue(recovery.complete())
        assertEquals(listOf("record.clear"), events)
        assertEquals(null, record.pending)
    }

    private class FakeRecord(
        private val events: MutableList<String>,
        var pending: String? = null,
    ) : HookAcceptanceRecoveryCoordinator.Record {
        override fun readPending(): String? {
            events += "record.read"
            return pending
        }

        override fun savePending(previousPayload: String): Boolean {
            events += "record.save"
            pending = previousPayload
            return true
        }

        override fun clear(): Boolean {
            events += "record.clear"
            pending = null
            return true
        }
    }

    private class FakeTransport(
        private val events: MutableList<String>,
        var current: String?,
        private val publishSucceeds: Boolean = true,
    ) : HookAcceptanceRecoveryCoordinator.Transport {
        override fun readCurrent(): String? {
            events += "transport.read"
            return current
        }

        override fun publish(payload: String): Boolean {
            events += "transport.publish"
            if (publishSucceeds) {
                current = payload
            }
            return publishSucceeds
        }
    }
}

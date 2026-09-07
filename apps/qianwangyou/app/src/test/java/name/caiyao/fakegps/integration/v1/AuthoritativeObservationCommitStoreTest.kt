package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthoritativeObservationCommitStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `valid cursor acknowledgement and exact audit evidence commit together`() {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val audit = DurableIntegrationAuditStore(kv, clock)
        val store = AuthoritativeObservationCommitStore(kv)
        val cursor = AuthoritativeObservationCursor(
            bootId = "123e4567-e89b-12d3-a456-426614174000",
            oracleInstanceId = "oracle-a",
            sequence = 8L,
            qwySemanticDigest = "semantic-a",
        )

        val committed = kv.transaction {
            val evidence = audit.append(
                event = "observe",
                callerApplicationId = "come.xx.fakeaauto",
                leaseId = "lease-1",
                operationId = "apply-operation-1",
                payloadDigest = "evidence-digest-a",
            )
            store.record(
                cursor = cursor,
                localGeneration = 3L,
                localRevision = 11L,
                evidence = evidence,
                evidenceDigest = "evidence-digest-a",
            )
            evidence
        }

        assertEquals(
            AuthoritativeObservationAcknowledgement(cursor, localGeneration = 3L, localRevision = 11L),
            store.acknowledgement(cursor),
        )
        assertEquals(
            AuthoritativeObservationCommitRecord(
                cursor = cursor,
                localGeneration = 3L,
                localRevision = 11L,
                evidenceSeq = committed.seq,
                evidenceDigest = "evidence-digest-a",
            ),
            store.recordForEvidence(committed.seq),
        )
        assertEquals(committed, audit.resolve(committed.seq))
    }

    @Test
    fun `same valid cursor records every fresh observation without changing its acknowledgement`() {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val audit = DurableIntegrationAuditStore(kv, clock)
        val store = AuthoritativeObservationCommitStore(kv)
        val cursor = cursor()

        val first = appendAndRecord(kv, audit, store, cursor, "evidence-digest-first")
        val second = appendAndRecord(kv, audit, store, cursor, "evidence-digest-second")

        assertEquals(2, audit.all().size)
        assertEquals(
            AuthoritativeObservationAcknowledgement(cursor, localGeneration = 3L, localRevision = 11L),
            store.acknowledgement(cursor),
        )
        assertEquals(cursor, store.recordForEvidence(first.seq)?.cursor)
        assertEquals(cursor, store.recordForEvidence(second.seq)?.cursor)
        assertEquals("evidence-digest-first", store.recordForEvidence(first.seq)?.evidenceDigest)
        assertEquals("evidence-digest-second", store.recordForEvidence(second.seq)?.evidenceDigest)
    }

    @Test
    fun `new valid cursor receives one acknowledgement while later evidence records do not move either watermark`() {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()
        val audit = DurableIntegrationAuditStore(kv, clock)
        val store = AuthoritativeObservationCommitStore(kv)
        val firstCursor = cursor(sequence = 8L, semanticDigest = "semantic-a")
        val secondCursor = cursor(sequence = 10L, semanticDigest = "semantic-b")

        val first = appendAndRecord(kv, audit, store, firstCursor, "evidence-digest-first", localRevision = 11L)
        val second = appendAndRecord(kv, audit, store, secondCursor, "evidence-digest-second", localRevision = 12L)
        val replay = appendAndRecord(kv, audit, store, secondCursor, "evidence-digest-replay", localRevision = 12L)

        assertEquals(
            AuthoritativeObservationAcknowledgement(firstCursor, localGeneration = 3L, localRevision = 11L),
            store.acknowledgement(firstCursor),
        )
        assertEquals(
            AuthoritativeObservationAcknowledgement(secondCursor, localGeneration = 3L, localRevision = 12L),
            store.acknowledgement(secondCursor),
        )
        assertEquals(2, kv.keys("integration.v1.authoritative_observation").count { it.startsWith("ack:") })
        assertEquals(3, kv.keys("integration.v1.authoritative_observation").count { it.startsWith("record:") })
        assertEquals(firstCursor, store.recordForEvidence(first.seq)?.cursor)
        assertEquals(secondCursor, store.recordForEvidence(second.seq)?.cursor)
        assertEquals(secondCursor, store.recordForEvidence(replay.seq)?.cursor)
    }

    @Test
    fun `each acknowledgement or record write failure rolls back audit and every binding field`() {
        for (failedKeyPrefix in listOf("ack:", "record:")) {
            val kv = InMemoryDurableKv()
            val clock = FakeMonotonicClock()
            val audit = DurableIntegrationAuditStore(kv, clock)
            val store = AuthoritativeObservationCommitStore(kv)
            val cursor = cursor()
            kv.failOnWrite = { namespace, key ->
                namespace == "integration.v1.authoritative_observation" && key.startsWith(failedKeyPrefix)
            }

            try {
                appendAndRecord(kv, audit, store, cursor, "evidence-digest-crash-$failedKeyPrefix")
                fail("$failedKeyPrefix must fail the outer commit closed")
            } catch (_: SimulatedWriteCrash) {
                // The InMemoryDurableKv transaction is the crash boundary under test.
            } finally {
                kv.failOnWrite = null
            }

            assertEquals("$failedKeyPrefix audit", emptyList<QwyAuditEvent>(), audit.all())
            assertNull("$failedKeyPrefix acknowledgement", store.acknowledgement(cursor))
            assertNull("$failedKeyPrefix record", store.recordForEvidence(1L))
        }
    }

    @Test
    fun `concurrent first observations of one cursor keep one acknowledgement and distinct evidence`() {
        val kv = InMemoryDurableKv()
        val audit = DurableIntegrationAuditStore(kv, FakeMonotonicClock())
        val store = AuthoritativeObservationCommitStore(kv)
        val cursor = cursor()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val events = executor.invokeAll((1..2).map { index ->
                Callable { appendAndRecord(kv, audit, store, cursor, "evidence-digest-concurrent-$index") }
            }, 30, TimeUnit.SECONDS).map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(2, events.map { it.seq }.distinct().size)
            assertEquals(1, kv.keys("integration.v1.authoritative_observation").count { it.startsWith("ack:") })
            events.forEach { assertEquals(cursor, store.recordForEvidence(it.seq)?.cursor) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `partial and full-frame journal failures reopen with no mixed observation commit`() {
        for (fault in JournalFault.entries) {
            val directory = temporary.newFolder(fault.name)
            val kv = JournalFaultKv(directory)
            val clock = FakeMonotonicClock()
            val audit = DurableIntegrationAuditStore(kv, clock)
            val store = AuthoritativeObservationCommitStore(kv)
            val cursor = cursor()
            kv.fault = fault

            try {
                appendAndRecord(kv, audit, store, cursor, "evidence-digest-$fault")
                fail("$fault must stop the current owner before an observation returns")
            } catch (_: IOException) {
                // The write reached an uncertain portion of the live journal.
            }

            val reopenedKv = FileDurableKv(directory)
            val reopenedAudit = DurableIntegrationAuditStore(reopenedKv, clock)
            val reopenedStore = AuthoritativeObservationCommitStore(reopenedKv)
            val events = reopenedAudit.all()
            if (fault == JournalFault.PARTIAL_WRITE) {
                assertEquals(emptyList<QwyAuditEvent>(), events)
                assertNull(reopenedStore.acknowledgement(cursor))
                assertNull(reopenedStore.recordForEvidence(1L))
            } else {
                val event = events.single()
                assertEquals("observe", event.event)
                assertEquals(cursor, reopenedStore.acknowledgement(cursor)?.cursor)
                assertEquals(cursor, reopenedStore.recordForEvidence(event.seq)?.cursor)
            }

            // A fresh owner can make a new complete transaction; it cannot
            // inherit a partial ACK/audit pair from the interrupted owner.
            val next = appendAndRecord(
                reopenedKv,
                reopenedAudit,
                reopenedStore,
                cursor,
                "evidence-digest-retry-$fault",
            )
            assertEquals(next, DurableIntegrationAuditStore(FileDurableKv(directory), clock).resolve(next.seq))
            assertEquals(cursor, AuthoritativeObservationCommitStore(FileDurableKv(directory)).recordForEvidence(next.seq)?.cursor)
        }
    }

    private fun appendAndRecord(
        kv: DurableKv,
        audit: DurableIntegrationAuditStore,
        store: AuthoritativeObservationCommitStore,
        cursor: AuthoritativeObservationCursor,
        digest: String,
        localRevision: Long = 11L,
    ): QwyAuditEvent = kv.transaction {
        val evidence = audit.append(
            event = "observe",
            callerApplicationId = "come.xx.fakeaauto",
            leaseId = "lease-1",
            operationId = "apply-operation-1",
            payloadDigest = digest,
        )
        store.record(cursor, localGeneration = 3L, localRevision = localRevision, evidence, digest)
        evidence
    }

    private fun cursor(sequence: Long = 8L, semanticDigest: String = "semantic-a") = AuthoritativeObservationCursor(
        bootId = "123e4567-e89b-12d3-a456-426614174000",
        oracleInstanceId = "oracle-a",
        sequence = sequence,
        qwySemanticDigest = semanticDigest,
    )

    private enum class JournalFault { PARTIAL_WRITE, SYNC_AFTER_COMPLETE_WRITE }

    private class JournalFaultKv(directory: File) : FileDurableKv(directory) {
        lateinit var fault: JournalFault

        override fun appendJournalRecord(target: File, bytes: ByteArray) {
            when (fault) {
                JournalFault.PARTIAL_WRITE -> {
                    FileOutputStream(target, true).use { output ->
                        output.write(bytes, 0, minOf(bytes.size, 70))
                        output.fd.sync()
                    }
                    throw IOException("injected partial authoritative observation journal write")
                }

                JournalFault.SYNC_AFTER_COMPLETE_WRITE -> {
                    FileOutputStream(target, true).use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    throw SyncFailedException("injected full-frame authoritative observation sync failure")
                }
            }
        }
    }
}

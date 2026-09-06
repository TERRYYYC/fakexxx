package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** #83: exercise the production audit codec/transactions/file replacement, not InMemoryDurableKv. */
class AuditFileBackingContractTest {
    @get:Rule val temporary = TemporaryFolder()

    private enum class Fault { NONE, AFTER_SEQ, OUTER_ABORT, BEFORE_TEMP, PARTIAL_TEMP, AFTER_TEMP }

    private class InterruptibleKv(directory: File) : FileDurableKv(directory) {
        var fault = Fault.NONE

        override fun writeTempFile(target: File, text: String) {
            when (fault) {
                Fault.BEFORE_TEMP -> throw IOException("injected before temp write")
                Fault.PARTIAL_TEMP -> {
                    super.writeTempFile(target, text.take(text.length / 2))
                    throw IOException("injected after partial temp write")
                }
                Fault.AFTER_TEMP -> {
                    super.writeTempFile(target, text)
                    throw IOException("injected after full temp write, before fsync/rename")
                }
                else -> super.writeTempFile(target, text)
            }
        }
    }

    private class InterruptibleClock : MonotonicClock {
        var fail = false
        override fun elapsedRealtimeMs(): Long {
            if (fail) throw IOException("injected after buffered __seq__, before evt")
            return 1_000_000L
        }
        override fun epochMs() = 1_700_000_000_000L
    }

    @Test
    fun `append failures preserve sequence and events in memory and on fresh file reopen`() {
        // Both an absent live file and an established audit prefix must roll back.
        for (prefixSize in listOf(0, 3)) {
            for (fault in Fault.values().filter { it != Fault.NONE }) {
                val directory = temporary.newFolder("prefix-$prefixSize-$fault")
                val kv = InterruptibleKv(directory)
                val clock = InterruptibleClock()
                val audit = DurableIntegrationAuditStore(kv, clock)
                val before = (1..prefixSize).map { audit.append("observe", operationId = "op-$it") }
                val file = File(directory, "environment-control-v1.kv")
                val bytesBefore = if (file.exists()) file.readBytes() else null
                var returned: QwyAuditEvent? = null
                kv.fault = fault
                clock.fail = fault == Fault.AFTER_SEQ

                assertThrows("prefix=$prefixSize fault=$fault", IOException::class.java) {
                    returned = if (fault == Fault.OUTER_ABORT) {
                        // Nested append joins the transaction: only the OUTER return is durable.
                        kv.transaction<QwyAuditEvent> {
                            audit.append("observe", operationId = "aborted")
                            throw IOException("injected after buffered event, before outer commit")
                        }
                    } else {
                        audit.append("observe", operationId = "aborted")
                    }
                }
                assertNull("a failed top-level transaction cannot return evidence", returned)
                clock.fail = false
                kv.fault = Fault.NONE
                assertEquals(before, audit.all())
                if (bytesBefore == null) assertFalse(file.exists())
                else assertArrayEquals(bytesBefore, file.readBytes())
                assertEquals(if (prefixSize == 0) null else "$prefixSize", kv.read(NS, "__seq__"))
                assertNull(kv.read(NS, "evt:${prefixSize + 1}"))

                // New object reads only the committed live file, including with an orphan .tmp.
                val reopened = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
                assertEquals(before, reopened.all())
                before.forEach { assertEquals(it, reopened.resolve(it.seq)) }
                assertNull(reopened.resolve(prefixSize + 1L))
                val next = reopened.append("observe", operationId = "retry")
                assertEquals(prefixSize + 1L, next.seq)
                assertEquals(before + next, DurableIntegrationAuditStore(FileDurableKv(directory), clock).all())
            }
        }
    }

    @Test
    fun `returned observation ref survives a fresh backing reopen and binds its exact payload`() {
        val directory = temporary.newFolder()
        val kv = FileDurableKv(directory)
        val clock = FakeMonotonicClock()
        val observer = observer(kv, clock)
        val request = request("returned-observation")
        val observation = observer.observe(lease(), request)
        val ref = observation.evidenceRefs.single()
        val match = Regex("^qwy:audit:([1-9]\\d*)$").matchEntire(ref)
        assertNotNull("use the ref actually returned by EnvironmentObserver", match)
        val seq = match!!.groupValues[1].toLong()
        val event = DurableIntegrationAuditStore(FileDurableKv(directory), clock).resolve(seq)!!
        assertEquals("observe", event.event)
        assertEquals(lease().callerApplicationId, event.callerApplicationId)
        assertEquals(request.leaseId, event.leaseId)
        assertEquals(request.operationId, event.operationId)
        assertEquals(QwyObservationEvidenceDigest.compute(observation), event.payloadDigest)
        assertEquals(ContinuityCoverageV1.NONE.wire, observation.continuityCoverageWire)
    }

    @Test
    fun `observer propagates real file write failure without returning a ref`() {
        for (fault in listOf(Fault.BEFORE_TEMP, Fault.PARTIAL_TEMP, Fault.AFTER_TEMP)) {
            val directory = temporary.newFolder(fault.name)
            val kv = InterruptibleKv(directory)
            val clock = FakeMonotonicClock()
            val observer = observer(kv, clock)
            val previous = observer.observe(lease(), request("previous"))
            val before = DurableIntegrationAuditStore(kv, clock).all()
            kv.fault = fault
            var returnedRef: String? = null
            assertThrows(IOException::class.java) {
                returnedRef = observer.observe(lease(), request("failed")).evidenceRefs.single()
            }
            assertNull(returnedRef)
            val reopened = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
            assertEquals(before, reopened.all())
            assertEquals(before.single(), reopened.resolve(previous.evidenceRefs.single().substringAfterLast(':').toLong()))
            assertNull(reopened.resolve(before.last().seq + 1L))
        }
    }

    @Test
    fun `contiguous records and nullable free fields survive reopen with no expiry`() {
        val directory = temporary.newFolder()
        val clock = FakeMonotonicClock()
        val audit = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
        val values = listOf(null, "", "a:b%\n\r\u001F中文😀")
        val expected = values.map { value -> audit.append("observe", value, value, value, value) }
        clock.advance(365L * 24L * 60L * 60L * 1_000L)
        val reopened = DurableIntegrationAuditStore(FileDurableKv(directory), clock)
        assertEquals(expected, reopened.all())
        expected.forEach { assertEquals(it, reopened.resolve(it.seq)) }
        assertNull(reopened.resolve(expected.size + 1L))
        assertThrows(IllegalArgumentException::class.java) { reopened.resolve(0L) }
        assertThrows(IllegalArgumentException::class.java) { reopened.resolve(-1L) }
    }

    @Test
    fun `assigned gap is rejected instead of returning a shortened interval`() {
        val directory = temporary.newFolder()
        val kv = FileDurableKv(directory)
        kv.transaction {
            kv.write(NS, "__seq__", "3")
            kv.write(NS, "evt:1", encodedEvent(1))
            kv.write(NS, "evt:3", encodedEvent(3))
        }
        val audit = DurableIntegrationAuditStore(FileDurableKv(directory), FakeMonotonicClock())
        assertEquals(1L, audit.resolve(1)!!.seq)
        assertEquals(3L, audit.resolve(3)!!.seq)
        assertThrows(IllegalStateException::class.java) { audit.resolve(2) }
        assertThrows(IllegalStateException::class.java) { audit.all() }
        assertNull(audit.resolve(4))
    }

    @Test
    fun `assigned malformed or misidentified event is rejected after reopen`() {
        for ((name, encoded) in listOf("malformed" to "not-a-framed-event", "mismatch" to encodedEvent(2))) {
            val directory = temporary.newFolder(name)
            val kv = FileDurableKv(directory)
            kv.transaction {
                kv.write(NS, "__seq__", "1")
                kv.write(NS, "evt:1", encoded)
            }
            val audit = DurableIntegrationAuditStore(FileDurableKv(directory), FakeMonotonicClock())
            assertThrows(IllegalStateException::class.java) { audit.resolve(1) }
            assertThrows(IllegalStateException::class.java) { audit.all() }
        }
    }

    @Test
    fun `concurrent appends through one file owner allocate one contiguous durable sequence`() {
        val directory = temporary.newFolder()
        val audit = DurableIntegrationAuditStore(FileDurableKv(directory), FakeMonotonicClock())
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = executor.invokeAll((1..64).map { index ->
                Callable { audit.append("observe", operationId = "op-$index") }
            }, 30, TimeUnit.SECONDS)
            val returned = futures.map { it.get(30, TimeUnit.SECONDS) }.sortedBy { it.seq }
            assertEquals((1L..64L).toList(), returned.map { it.seq })
            assertEquals(64, returned.map { it.operationId }.toSet().size)
            assertEquals(returned, DurableIntegrationAuditStore(FileDurableKv(directory), FakeMonotonicClock()).all())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun observer(kv: FileDurableKv, clock: MonotonicClock): EnvironmentObserver = EnvironmentObserver(
        ContinuityTracker(kv, clock),
        // Android's external environment is a fixture; audit, tracker and file storage are real.
        FakeQwyEnvironment(kv),
        clock,
        DurableIntegrationAuditStore(kv, clock),
    )

    private fun lease() = LeaseRecord(
        leaseId = "host-lease", callerApplicationId = "host.caller", callerSignerDigest = "test-digest",
        acceptedIntentHash = "host-intent", state = LeaseState.ACTIVE, applyIdempotencyKey = "apply",
        startingEnvironmentRevision = 1L, deadlineElapsedRealtimeMs = 2_000_000L,
        applyOwnerGeneration = 1L, earnedScheduleRef = "item-1",
    )

    private fun request(operationId: String) = ObserveRequestV1("host-lease", operationId, "host-intent")

    private fun encodedEvent(seq: Long) = DurableFieldCodec.encode(
        listOf(seq.toString(), "1000000", "observe", null, null, null, null),
    )

    private companion object { const val NS = "integration.v1.audit" }
}

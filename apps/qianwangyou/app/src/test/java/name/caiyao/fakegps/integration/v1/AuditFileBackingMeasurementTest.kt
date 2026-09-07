package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import kotlin.math.ceil

/**
 * #83 host baseline, NOT an Android benchmark or a latency gate.
 * The journal stage is production code; the measurement reports bytes staged per commit.
 * Logical staged bytes exclude filesystem metadata and physical-device writes.
 */
class AuditFileBackingMeasurementTest {
    @get:Rule val temporary = TemporaryFolder()

    private class MeasuringKv(directory: File) : FileDurableKv(directory) {
        val writeSizes = mutableListOf<Long>()
        override fun writeTempFile(target: File, text: String) {
            super.writeTempFile(target, text)
            // Observe the bytes actually written using the existing production seam.
            writeSizes += target.length()
        }
    }

    @Test
    fun `report incremental audit growth through the production backing`() {
        // Fixed small warmup, in another directory and excluded from all measurements.
        val warmup = DurableIntegrationAuditStore(FileDurableKv(temporary.newFolder()), FakeMonotonicClock())
        repeat(16) { append(warmup, it + 1) }
        println("HOST_AUDIT_ENV os=${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "arch=${System.getProperty("os.arch")} java=${System.getProperty("java.runtime.version")}")
        println("HOST_AUDIT_CSV scenario,rows,total_temp_bytes,live_file_bytes,interval_rows," +
            "interval_temp_bytes,interval_total_ms,interval_p50_ms,interval_p95_ms")
        for (sharedBytes in listOf(0, 65_536)) {
            val kv = MeasuringKv(temporary.newFolder("shared-$sharedBytes"))
            if (sharedBytes > 0) kv.write("host.fixed.fixture", "payload", "x".repeat(sharedBytes))
            kv.writeSizes.clear() // Do not charge the unrelated seed transaction to audit appends.
            val audit = DurableIntegrationAuditStore(kv, FakeMonotonicClock())
            val expected = mutableListOf<QwyAuditEvent>()
            var previous = 0
            for (size in listOf(64, 128, 256, 512, 1024)) {
                val elapsedNanos = mutableListOf<Long>()
                for (index in previous + 1..size) {
                    val start = System.nanoTime()
                    expected += append(audit, index)
                    elapsedNanos += System.nanoTime() - start
                }
                val liveBytes = File(kv.directory, "environment-control-v1.journal").length()
                assertEquals("seq and event must share ONE journal commit per append", size, kv.writeSizes.size)
                assertTrue("the append-only journal grows with each event", liveBytes > 0L)
                // New FileDurableKv instances actually parse the live file, not an in-memory fake.
                val reopened = DurableIntegrationAuditStore(FileDurableKv(kv.directory), FakeMonotonicClock())
                assertEquals(expected, reopened.all())
                expected.forEach { assertEquals(it, reopened.resolve(it.seq)) }
                assertNull(reopened.resolve(size + 1L))
                if (sharedBytes > 0) {
                    assertEquals("x".repeat(sharedBytes), FileDurableKv(kv.directory).read("host.fixed.fixture", "payload"))
                }
                val sorted = elapsedNanos.sorted()
                println("HOST_AUDIT_CSV shared_$sharedBytes,$size,${kv.writeSizes.sum()},$liveBytes," +
                    "${size - previous},${kv.writeSizes.drop(previous).sum()}," +
                    "${ms(elapsedNanos.sum())},${ms(percentile(sorted, 0.50))},${ms(percentile(sorted, 0.95))}")
                previous = size
            }
        }
    }

    private fun append(audit: IntegrationAuditStore, index: Int) = audit.append(
        event = "observe", callerApplicationId = "come.xx.fakeaauto", leaseId = "host-lease-00000001",
        operationId = "host-observe-${index.toString().padStart(6, '0')}", payloadDigest = "a".repeat(64),
    )

    private fun percentile(sorted: List<Long>, fraction: Double) = sorted[ceil(sorted.size * fraction).toInt() - 1]
    private fun ms(nanos: Long) = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
}

package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMockEvaluationDiagnosticsTest {
    private val samples = listOf(sample("gps", 1_000L), sample("network", 999L))

    @Test
    fun `one snapshot supplies both trust decision and diagnostics without another read`() {
        var snapshotCalls = 0
        var diagnostics: SystemMockEvaluationDiagnostics? = null
        val reader = object : SystemMockLocationReader {
            override fun read(): List<SystemMockLocationReadback> = error("second read forbidden")
            override fun readSnapshot(): SystemMockReadSnapshot {
                snapshotCalls++
                return SystemMockReadSnapshot(samples)
            }
        }
        val result = SystemMockTrustPolicy(reader, diagnosticSink = { diagnostics = it })
            .evaluate(31.2304, 121.4737, 1_000L)

        assertEquals("policy must consume the structured snapshot", 1, snapshotCalls)
        assertEquals(31.2304, result.latitude!!, 0.0)
        assertFalse(result.verified)
        assertEquals(
            listOf(SystemMockSampleFreshness.AT_OR_AFTER_PUBLISH, SystemMockSampleFreshness.BEFORE_PUBLISH),
            requireNotNull(diagnostics).sources.map { it.freshness },
        )
    }

    @Test
    fun `legacy SAM remains usable and sink failure cannot change any trust result`() {
        val reader = SystemMockLocationReader { samples }
        val withoutSink = SystemMockTrustPolicy(reader).evaluate(31.2304, 121.4737, 1_000L)
        var calls = 0
        val withThrowingSink = SystemMockTrustPolicy(reader, diagnosticSink = {
            calls++
            throw IllegalStateException("sensitive sink failure")
        }).evaluate(31.2304, 121.4737, 1_000L)

        assertEquals("diagnostics must really be attempted", 1, calls)
        assertEquals(withoutSink, withThrowingSink)
        assertEquals(samples, reader.read())
    }

    @Test
    fun `nonpositive publish anchors never label samples fresh or stale`() {
        listOf(0L, -1L).forEach { anchor ->
            var diagnostics: SystemMockEvaluationDiagnostics? = null
            val result = SystemMockTrustPolicy(
                SystemMockLocationReader { samples },
                diagnosticSink = { diagnostics = it },
            ).evaluate(31.2304, 121.4737, anchor)
            assertFalse(result.verified)
            assertEquals(anchor, requireNotNull(diagnostics).publishAnchorElapsedRealtimeMs)
            assertTrue(diagnostics!!.sources.all { it.freshness == SystemMockSampleFreshness.UNASSESSED })
        }
    }

    @Test
    fun `whole reader failure is classified without inventing absent cache proof`() {
        var diagnostics: SystemMockEvaluationDiagnostics? = null
        val result = SystemMockTrustPolicy(
            SystemMockLocationReader { throw SecurityException("latitude=31.2304 longitude=121.4737") },
            diagnosticSink = { diagnostics = it },
        ).evaluate(31.2304, 121.4737, 1_000L)

        assertFalse(result.verified)
        assertEquals("system-mock:unavailable", result.fingerprint)
        assertEquals(SystemMockReadFailure.SECURITY, requireNotNull(diagnostics).readerFailure)
        assertEquals(listOf("gps", "network"), diagnostics!!.sources.map { it.read.source })
        assertTrue(diagnostics!!.sources.all { it.read.status == SystemMockSourceReadStatus.UNREPORTED })
    }

    @Test
    fun `raw sample inputs keep exact old fingerprint decision and watermark`() {
        val fresh = samples.map { it.copy(observedAtElapsedRealtimeMs = 1_100L) }
        var diagnostics: SystemMockEvaluationDiagnostics? = null
        val result = SystemMockTrustPolicy(
            SystemMockLocationReader { fresh },
            diagnosticSink = { diagnostics = it },
        ).evaluate(31.2304, 121.4737, 1_000L)

        assertTrue(result.verified)
        assertTrue(result.matchesExactTargetProjection)
        assertEquals(mapOf("gps" to 1_100L, "network" to 1_100L), result.verifiedSourceElapsedRealtimeMs)
        assertEquals(1_100L, result.evidenceObservedAtElapsedRealtimeMs)
        assertEquals(
            "system-mock:gps:${31.2304.toBits()}:${121.4737.toBits()}:true:true|" +
                "network:${31.2304.toBits()}:${121.4737.toBits()}:true:true",
            result.fingerprint,
        )
        assertEquals(listOf(1_100L, 1_100L), requireNotNull(diagnostics).sources.map { it.read.sourceElapsedRealtimeMs })
    }

    private fun sample(source: String, atMs: Long) = SystemMockLocationReadback(
        source = source,
        latitude = 31.2304,
        longitude = 121.4737,
        isMock = true,
        observedAtElapsedRealtimeMs = atMs,
    )
}

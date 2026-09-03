package io.github.terryyyc.fakexxx.integration.pr63issue66

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import name.caiyao.fakegps.integration.v1.AndroidSystemMockDiagnosticLogger
import name.caiyao.fakegps.integration.v1.AndroidSystemMockLocationReader
import name.caiyao.fakegps.integration.v1.SystemMockDiagnosticOrigin
import name.caiyao.fakegps.integration.v1.SystemMockLocationReadback
import name.caiyao.fakegps.integration.v1.SystemMockReadFailure
import name.caiyao.fakegps.integration.v1.SystemMockSourceReadStatus
import name.caiyao.fakegps.integration.v1.SystemMockTrustPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSystemMockLocationReaderDiagnosticsTest {
    @Test
    fun `provider lookup permission failure differs from successful empty cache`() {
        val cacheCalls = mutableListOf<String>()
        val reader = reader(
            enabled = { if (it == "gps") throw SecurityException(SECRET) else true },
            cache = { cacheCalls.add(it); null },
        )

        val snapshot = reader.readSnapshot()

        assertEquals(listOf("network"), cacheCalls)
        assertTrue(snapshot.readbacks.isEmpty())
        assertEquals(
            listOf(SystemMockSourceReadStatus.PROVIDER_QUERY_FAILED, SystemMockSourceReadStatus.NO_SAMPLE),
            snapshot.sourceDiagnostics.map { it.status },
        )
        assertEquals(SystemMockReadFailure.SECURITY, snapshot.sourceDiagnostics[0].failure)
        assertNull(snapshot.sourceDiagnostics[0].providerEnabled)
        assertEquals(true, snapshot.sourceDiagnostics[1].providerEnabled)
    }

    @Test
    fun `cache query failure is classified independently of provider lookup`() {
        val snapshot = reader(
            cache = { if (it == "gps") throw IllegalArgumentException(SECRET) else null },
        ).readSnapshot()

        val gps = snapshot.sourceDiagnostics.first()
        assertEquals(SystemMockSourceReadStatus.CACHE_QUERY_FAILED, gps.status)
        assertEquals(SystemMockReadFailure.ILLEGAL_ARGUMENT, gps.failure)
        assertEquals(true, gps.providerEnabled)
        assertEquals(SystemMockSourceReadStatus.NO_SAMPLE, snapshot.sourceDiagnostics.last().status)
    }

    @Test
    fun `sample extraction failure preserves the successful peer and remains unverified`() {
        val reader = reader(
            extract = { source, enabled, location ->
                if (source == "network") throw IllegalStateException(SECRET)
                decode(source, enabled, location)
            },
        )
        val snapshot = reader.readSnapshot()

        assertEquals(listOf("gps"), snapshot.readbacks.map { it.source })
        assertEquals(SystemMockSourceReadStatus.SAMPLE, snapshot.sourceDiagnostics[0].status)
        assertEquals(SystemMockSourceReadStatus.SAMPLE_EXTRACTION_FAILED, snapshot.sourceDiagnostics[1].status)
        assertEquals(SystemMockReadFailure.OTHER, snapshot.sourceDiagnostics[1].failure)
        assertFalse(SystemMockTrustPolicy(reader).evaluate(31.2304, 121.4737, 1L).verified)
    }

    @Test
    fun `disabled providers retain both cached sample and actual absence states`() {
        val snapshot = reader(
            enabled = { false },
            cache = { if (it == "gps") location(it) else null },
        ).readSnapshot()

        val gps = snapshot.readbacks.single()
        assertFalse(gps.providerEnabled)
        assertTrue(gps.isMock)
        assertEquals(1_234L, gps.observedAtElapsedRealtimeMs)
        assertEquals(SystemMockSourceReadStatus.SAMPLE, snapshot.sourceDiagnostics[0].status)
        assertEquals(SystemMockSourceReadStatus.NO_SAMPLE, snapshot.sourceDiagnostics[1].status)
        assertEquals(false, snapshot.sourceDiagnostics[1].providerEnabled)
    }

    @Test
    fun `each source is acquired once and a later read has no retained error state`() {
        var failGps = true
        val enabledCalls = mutableListOf<String>()
        val cacheCalls = mutableListOf<String>()
        val reader = reader(
            enabled = {
                enabledCalls.add(it)
                if (it == "gps" && failGps) throw SecurityException(SECRET)
                true
            },
            cache = { cacheCalls.add(it); location(it) },
        )
        val first = reader.readSnapshot()
        failGps = false
        val second = reader.readSnapshot()

        assertEquals(listOf("gps", "network", "gps", "network"), enabledCalls)
        assertEquals(listOf("network", "gps", "network"), cacheCalls)
        assertEquals(listOf("network"), first.readbacks.map { it.source })
        assertEquals(listOf("gps", "network"), second.readbacks.map { it.source })
        assertEquals(2, second.sourceDiagnostics.size)
        assertTrue(second.sourceDiagnostics.all { it.status == SystemMockSourceReadStatus.SAMPLE })
    }

    @Test
    fun `real Android adapter maps LocationManager cache fields and emits production logs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(manager)
        shadow.setLocationEnabled(true)
        listOf("gps", "network").forEach { source ->
            shadow.setProviderEnabled(source, true)
            shadow.setLastKnownLocation(source, location(source))
        }
        val reader = AndroidSystemMockLocationReader(manager)
        val snapshot = reader.readSnapshot()
        assertEquals(listOf("gps", "network"), snapshot.readbacks.map { it.source })
        snapshot.readbacks.forEach {
            assertEquals(31.2304, it.latitude, 0.0)
            assertEquals(121.4737, it.longitude, 0.0)
            assertTrue(it.isMock)
            assertTrue(it.providerEnabled)
            assertEquals(1_234L, it.observedAtElapsedRealtimeMs)
        }
        ShadowLog.clear()
        val result = SystemMockTrustPolicy(reader, diagnosticSink = {
            AndroidSystemMockDiagnosticLogger.record(SystemMockDiagnosticOrigin.INTEGRATION, it)
        }).evaluate(31.2304, 121.4737, 1_235L)

        assertFalse(result.verified)
        val logs = ShadowLog.getLogsForTag(AndroidSystemMockDiagnosticLogger.TAG)
        assertEquals("the actual production Android logger must emit both sources", 2, logs.size)
        logs.forEach {
            assertTrue(it.msg.contains("origin=INTEGRATION"))
            assertTrue(it.msg.contains("source_elapsed_ms=1234"))
            assertTrue(it.msg.contains("freshness=BEFORE_PUBLISH"))
            assertFalse(it.msg.contains("31.2304"))
            assertFalse(it.msg.contains("121.4737"))
            assertFalse(it.msg.contains("${31.2304.toBits()}"))
            assertFalse(it.msg.contains("Location["))
            assertNull(it.throwable)
        }
    }

    private fun reader(
        enabled: (String) -> Boolean = { true },
        cache: (String) -> Location? = { location(it) },
        extract: (String, Boolean, Location) -> SystemMockLocationReadback = ::decode,
    ) = AndroidSystemMockLocationReader(enabled, cache, extract)

    private fun location(source: String) = Location(source).apply {
        latitude = 31.2304
        longitude = 121.4737
        elapsedRealtimeNanos = 1_234_567_890L
        time = 1_700_000_000_000L
        accuracy = 1f
        isMock = true
    }

    private fun decode(source: String, enabled: Boolean, location: Location) =
        SystemMockLocationReadback(
            source, location.latitude, location.longitude, location.isMock,
            location.elapsedRealtimeNanos / 1_000_000L, enabled,
        )

    private companion object {
        const val SECRET = "Location[lat=31.2304 lon=121.4737]\nprivate exception text"
    }
}

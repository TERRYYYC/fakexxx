package name.caiyao.fakegps.integration.v1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coordinator-run on a disposable API-35 emulator only. These tests do not grant permissions,
 * change AppOps, inject a provider, read a profile, or operate any phone. The permission-negative
 * case requires fresh target data with both location permissions denied; the second case uses
 * genuine Android Location objects but deliberately does not claim framework-cache success.
 */
@RunWith(AndroidJUnit4::class)
class SystemMockReadbackInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deniedLocationPermissionIsAClassifiedFrameworkFailureWithProductionLogOutput() {
        requireDisposableApi35Emulator()
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            assertEquals("Run with both target location permissions denied", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(it))
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val snapshot = AndroidSystemMockLocationReader(manager).readSnapshot()
        assertTrue(snapshot.readbacks.isEmpty())
        assertEquals(listOf("gps", "network"), snapshot.sourceDiagnostics.map { it.source })
        snapshot.sourceDiagnostics.forEach {
            assertEquals(SystemMockSourceReadStatus.CACHE_QUERY_FAILED, it.status)
            assertEquals(SystemMockReadFailure.SECURITY, it.failure)
        }

        // Feed exactly this real framework snapshot to policy/logging; a second read is forbidden.
        var diagnosticCalls = 0
        var diagnostics: SystemMockEvaluationDiagnostics? = null
        val reader = object : SystemMockLocationReader {
            override fun read(): List<SystemMockLocationReadback> = error("unexpected second acquisition")
            override fun readSnapshot(): SystemMockReadSnapshot = snapshot
        }
        val result = SystemMockTrustPolicy(reader, diagnosticSink = {
            diagnosticCalls++
            diagnostics = it
            AndroidSystemMockDiagnosticLogger.record(SystemMockDiagnosticOrigin.INTEGRATION, it)
        }).evaluate(31.2304, 121.4737, 1_000L)
        assertEquals(1, diagnosticCalls)
        assertEquals(2, SystemMockDiagnosticFormatter.lines(requireNotNull(diagnostics)).size)
        assertFalse(result.verified)
        assertEquals("system-mock:unavailable", result.fingerprint)
    }

    @Test
    fun realAndroidLocationMetadataMappingPreservesDisabledNonMockAndSourceTime() {
        requireDisposableApi35Emulator()
        val fixture = Location("fixture-not-a-framework-proof").apply {
            latitude = 31.2304
            longitude = 121.4737
            elapsedRealtimeNanos = 1_234_567_890L
            time = 1_700_000_000_000L
            accuracy = 1f
            isMock = false
        }
        val reader = AndroidSystemMockLocationReader(
            isProviderEnabled = { it != "network" },
            getLastKnownLocation = { fixture },
        )
        val snapshot = reader.readSnapshot()
        assertEquals(listOf("gps", "network"), snapshot.readbacks.map { it.source })
        assertEquals(listOf(true, false), snapshot.readbacks.map { it.providerEnabled })
        snapshot.readbacks.forEach {
            assertEquals(31.2304, it.latitude, 0.0)
            assertEquals(121.4737, it.longitude, 0.0)
            assertFalse(it.isMock)
            assertEquals(1_234L, it.observedAtElapsedRealtimeMs)
        }
        assertEquals(listOf(false, false), snapshot.sourceDiagnostics.map { it.isMock })
        assertEquals(listOf(1_234L, 1_234L), snapshot.sourceDiagnostics.map { it.sourceElapsedRealtimeMs })
        assertFalse(SystemMockTrustPolicy(reader).evaluate(31.2304, 121.4737, 1_234L).verified)
    }

    private fun requireDisposableApi35Emulator() {
        assertEquals("Only run on the coordinator-owned disposable emulator", "ranchu", Build.HARDWARE)
        assertEquals("This evidence recipe is pinned to API 35", 35, Build.VERSION.SDK_INT)
    }
}

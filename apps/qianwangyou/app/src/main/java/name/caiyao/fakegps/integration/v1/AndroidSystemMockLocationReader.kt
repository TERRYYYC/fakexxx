package name.caiyao.fakegps.integration.v1

import android.annotation.SuppressLint
import android.location.LocationManager
import androidx.core.location.LocationCompat

/**
 * Reads the framework provider caches after QWY publishes its test locations.
 *
 * `getLastKnownLocation` is deliberately treated as raw, possibly stale data:
 * [SystemMockTrustPolicy] checks its monotonic timestamp against the apply-time
 * publish anchor and requires both framework sources. A missing permission,
 * provider, or cache entry is represented by an absent sample and therefore
 * fails verification closed.
 */
internal class AndroidSystemMockLocationReader(
    private val locationManager: LocationManager,
) : SystemMockLocationReader {

    @SuppressLint("MissingPermission")
    override fun read(): List<SystemMockLocationReadback> =
        SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES.mapNotNull { source ->
            runCatching { locationManager.getLastKnownLocation(source) }
                .getOrNull()
                ?.let { location ->
                    SystemMockLocationReadback(
                        source = source,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        isMock = LocationCompat.isMock(location),
                        observedAtElapsedRealtimeMs =
                            location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
                    )
                }
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

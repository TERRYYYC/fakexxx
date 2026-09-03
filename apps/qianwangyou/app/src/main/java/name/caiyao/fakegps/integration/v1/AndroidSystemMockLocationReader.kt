package name.caiyao.fakegps.integration.v1

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import androidx.core.location.LocationCompat

/**
 * Reads the framework provider caches after QWY publishes its test locations.
 *
 * `getLastKnownLocation` is deliberately treated as raw, possibly stale data:
 * [SystemMockTrustPolicy] checks its monotonic timestamp against the apply-time
 * publish anchor and requires both framework sources. Missing or failed reads
 * still fail verification closed, but their source-scoped cause is retained
 * separately from the raw samples. Diagnostics never initiate a second read.
 */
internal class AndroidSystemMockLocationReader(
    private val isProviderEnabled: (String) -> Boolean,
    private val getLastKnownLocation: (String) -> Location?,
    private val extractSample: (String, Boolean, Location) -> SystemMockLocationReadback = ::extractReadback,
) : SystemMockLocationReader {

    @SuppressLint("MissingPermission")
    constructor(locationManager: LocationManager) : this(
        isProviderEnabled = { source -> locationManager.isProviderEnabled(source) },
        getLastKnownLocation = { source -> locationManager.getLastKnownLocation(source) },
        extractSample = ::extractReadback,
    )

    override fun read(): List<SystemMockLocationReadback> = readSnapshot().readbacks

    override fun readSnapshot(): SystemMockReadSnapshot {
        val samples = mutableListOf<SystemMockLocationReadback>()
        val diagnostics = mutableListOf<SystemMockSourceReadDiagnostic>()
        for (source in SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES) {
            val providerEnabled = try {
                isProviderEnabled(source)
            } catch (cause: Throwable) {
                diagnostics.add(SystemMockSourceReadDiagnostic(
                    source, SystemMockSourceReadStatus.PROVIDER_QUERY_FAILED,
                    failure = SystemMockReadFailure.from(cause),
                ))
                continue
            }

            val location = try {
                getLastKnownLocation(source)
            } catch (cause: Throwable) {
                diagnostics.add(SystemMockSourceReadDiagnostic(
                    source, SystemMockSourceReadStatus.CACHE_QUERY_FAILED, providerEnabled,
                    failure = SystemMockReadFailure.from(cause),
                ))
                continue
            }
            if (location == null) {
                diagnostics.add(SystemMockSourceReadDiagnostic(
                    source, SystemMockSourceReadStatus.NO_SAMPLE, providerEnabled,
                ))
                continue
            }

            val sample = try {
                extractSample(source, providerEnabled, location)
            } catch (cause: Throwable) {
                diagnostics.add(SystemMockSourceReadDiagnostic(
                    source, SystemMockSourceReadStatus.SAMPLE_EXTRACTION_FAILED, providerEnabled,
                    failure = SystemMockReadFailure.from(cause),
                ))
                continue
            }
            samples.add(sample)
            diagnostics.add(SystemMockSourceReadDiagnostic(
                source, SystemMockSourceReadStatus.SAMPLE, providerEnabled,
                isMock = sample.isMock,
                sourceElapsedRealtimeMs = sample.observedAtElapsedRealtimeMs,
            ))
        }
        return SystemMockReadSnapshot(samples, diagnostics)
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L

        fun extractReadback(
            source: String,
            providerEnabled: Boolean,
            location: Location,
        ) = SystemMockLocationReadback(
            source = source,
            latitude = location.latitude,
            longitude = location.longitude,
            isMock = LocationCompat.isMock(location),
            observedAtElapsedRealtimeMs = location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
            providerEnabled = providerEnabled,
        )
    }
}

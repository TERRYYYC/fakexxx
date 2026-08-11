package name.caiyao.fakegps.mockprovider

import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient

class GooglePlayServicesFusedMockProviderGateway(
    private val client: FusedLocationProviderClient,
    private val taskAwaiter: PlayServicesTaskAwaiter = PlayServicesTaskAwaiter(),
    private val sampleFactory: MockLocationSampleFactory = MockLocationSampleFactory(
        elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
    ),
) : FusedMockProviderGateway {
    override fun enable() {
        taskAwaiter.await(client.setMockMode(true))
    }

    override fun publish(config: MockLocationConfig) {
        val sample = sampleFactory.create(config)
        val location = Location(FUSED_PROVIDER).apply {
            latitude = sample.latitude
            longitude = sample.longitude
            sample.altitudeMeters?.let { altitude = it }
            accuracy = sample.accuracyMeters
            time = sample.timeMillis
            elapsedRealtimeNanos = sample.elapsedRealtimeNanos
            speed = 0f
            bearing = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 1f
                speedAccuracyMetersPerSecond = 0.1f
                bearingAccuracyDegrees = 0.1f
            }
        }
        taskAwaiter.await(client.setMockLocation(location))
    }

    override fun disable() {
        taskAwaiter.await(client.setMockMode(false))
    }

    private companion object {
        const val FUSED_PROVIDER = "fused"
    }
}

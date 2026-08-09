package name.caiyao.fakegps.mockprovider

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi

class AndroidMockProviderGateway(
    private val locationManager: LocationManager,
    private val sampleFactory: MockLocationSampleFactory = MockLocationSampleFactory(
        elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
    ),
) : MockProviderGateway {

    override fun replaceGpsProvider() {
        ACTIVE_PROVIDER_NAMES.forEach { provider ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) addModernProvider(provider)
            else addLegacyProvider(provider)
            locationManager.setTestProviderEnabled(provider, true)
        }
    }

    override fun publish(config: MockLocationConfig) {
        val sample = sampleFactory.create(config)
        ACTIVE_PROVIDER_NAMES.forEach { provider ->
            val location = Location(provider).apply {
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
            locationManager.setTestProviderLocation(provider, location)
        }
    }

    override fun removeGpsProvider() {
        var firstFailure: Throwable? = null
        ACTIVE_PROVIDER_NAMES.forEach { provider ->
            try {
                locationManager.removeTestProvider(provider)
            } catch (failure: Throwable) {
                firstFailure?.addSuppressed(failure) ?: run { firstFailure = failure }
            }
        }
        firstFailure?.let { throw it }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun addModernProvider(provider: String) {
        val networkProvider = provider == LocationManager.NETWORK_PROVIDER
        val properties = ProviderProperties.Builder()
            .setHasNetworkRequirement(networkProvider)
            .setHasSatelliteRequirement(false)
            .setHasCellRequirement(networkProvider)
            .setHasMonetaryCost(false)
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(
                if (networkProvider) ProviderProperties.POWER_USAGE_LOW
                else ProviderProperties.POWER_USAGE_HIGH,
            )
            .setAccuracy(
                if (networkProvider) ProviderProperties.ACCURACY_COARSE
                else ProviderProperties.ACCURACY_FINE,
            )
            .build()
        locationManager.addTestProvider(provider, properties)
    }

    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun addLegacyProvider(provider: String) {
        val networkProvider = provider == LocationManager.NETWORK_PROVIDER
        locationManager.addTestProvider(
            provider,
            networkProvider,
            false,
            networkProvider,
            false,
            true,
            true,
            true,
            if (networkProvider) ProviderProperties.POWER_USAGE_LOW
            else ProviderProperties.POWER_USAGE_HIGH,
            if (networkProvider) ProviderProperties.ACCURACY_COARSE
            else ProviderProperties.ACCURACY_FINE,
        )
    }

    private companion object {
        val ACTIVE_PROVIDER_NAMES = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    }
}

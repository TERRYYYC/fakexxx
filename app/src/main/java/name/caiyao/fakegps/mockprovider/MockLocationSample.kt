package name.caiyao.fakegps.mockprovider

data class MockLocationSample(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
)

class MockLocationSampleFactory(
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeNanos: () -> Long,
) {
    fun create(config: MockLocationConfig): MockLocationSample = MockLocationSample(
        provider = GPS_PROVIDER,
        latitude = config.latitude,
        longitude = config.longitude,
        altitudeMeters = config.altitudeMeters,
        accuracyMeters = config.accuracyMeters,
        timeMillis = wallClockMillis(),
        elapsedRealtimeNanos = elapsedRealtimeNanos(),
    )

    companion object {
        const val GPS_PROVIDER = "gps"
    }
}

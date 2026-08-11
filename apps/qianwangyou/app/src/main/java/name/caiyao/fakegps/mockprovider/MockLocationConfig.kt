package name.caiyao.fakegps.mockprovider

data class MockLocationConfig(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = DEFAULT_ACCURACY_METERS,
    val altitudeMeters: Double? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "latitude must be finite and within [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "longitude must be finite and within [-180, 180]"
        }
        require(accuracyMeters.isFinite() && accuracyMeters > 0f) {
            "accuracyMeters must be finite and positive"
        }
        require(altitudeMeters == null || altitudeMeters.isFinite()) {
            "altitudeMeters must be finite when present"
        }
    }

    companion object {
        const val DEFAULT_ACCURACY_METERS = 3f
    }
}

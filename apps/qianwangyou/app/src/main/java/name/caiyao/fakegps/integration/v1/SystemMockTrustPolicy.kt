package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A location returned by an OS/provider readback channel, never a requested coordinate. */
internal data class SystemMockLocationReadback(
    val source: String,
    val latitude: Double,
    val longitude: Double,
    val isMock: Boolean,
    val observedAtElapsedRealtimeMs: Long,
    val providerEnabled: Boolean = true,
)

/** Injectable read side kept separate from the mock-provider command gateway. */
internal fun interface SystemMockLocationReader {
    fun read(): List<SystemMockLocationReadback>
}

internal data class SystemMockTrustResult(
    val latitude: Double?,
    val longitude: Double?,
    val isMock: Boolean?,
    val verified: Boolean,
    val fingerprint: String,
    /** Oldest required-source sample: the conservative wire observation time. */
    val evidenceObservedAtElapsedRealtimeMs: Long?,
    /** Per-source sample identity used by the durable lease watermark. */
    val verifiedSourceElapsedRealtimeMs: Map<String, Long>,
)

/** Provider-side coordinate trust decision for KB-8 / INV-23. */
internal class SystemMockTrustPolicy(
    private val reader: SystemMockLocationReader,
    private val requiredSources: Set<String> = REQUIRED_FRAMEWORK_SOURCES,
) {
    fun evaluate(
        targetLatitude: Double,
        targetLongitude: Double,
        publishNotBeforeElapsedRealtimeMs: Long,
    ): SystemMockTrustResult {
        val readbacks = runCatching(reader::read).getOrElse { emptyList() }
        val fingerprint = readbacks
            .sortedBy { it.source }
            .joinToString(separator = "|") { sample ->
                "${sample.source}:${sample.latitude.toBits()}:${sample.longitude.toBits()}:" +
                    "${sample.isMock}:${sample.providerEnabled}"
            }
            .ifEmpty { "unavailable" }
            .let { "system-mock:$it" }

        val selected = requiredSources.sorted().mapNotNull { source ->
            readbacks.filter { it.source == source }.singleOrNull()
        }
        val hasEveryRequiredSource = selected.size == requiredSources.size
        val representative = selected.maxWithOrNull(
            compareBy<SystemMockLocationReadback> { it.observedAtElapsedRealtimeMs }
                .thenBy { it.source },
        )
        val evidenceObservedAtElapsedRealtimeMs = selected
            .takeIf { hasEveryRequiredSource }
            ?.minOfOrNull { it.observedAtElapsedRealtimeMs }
        val targetIsValid = validCoordinates(targetLatitude, targetLongitude)
        val everySourceVerified = hasEveryRequiredSource && selected.all { sample ->
            sample.providerEnabled && sample.isMock &&
                sample.observedAtElapsedRealtimeMs >= publishNotBeforeElapsedRealtimeMs &&
                validCoordinates(sample.latitude, sample.longitude) &&
                HaversineDistance.meters(
                    targetLatitude,
                    targetLongitude,
                    sample.latitude,
                    sample.longitude,
                ) <= ContractV1.TRUSTED_LOCATION_TOLERANCE_METERS
        }
        val verified = publishNotBeforeElapsedRealtimeMs > 0L &&
            targetIsValid && everySourceVerified

        return SystemMockTrustResult(
            latitude = representative?.latitude,
            longitude = representative?.longitude,
            isMock = selected.takeIf { hasEveryRequiredSource }?.all { it.isMock },
            verified = verified,
            fingerprint = fingerprint,
            evidenceObservedAtElapsedRealtimeMs = evidenceObservedAtElapsedRealtimeMs,
            verifiedSourceElapsedRealtimeMs = if (verified) {
                selected.associate { it.source to it.observedAtElapsedRealtimeMs }
            } else {
                emptyMap()
            },
        )
    }

    private fun validCoordinates(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    companion object {
        val REQUIRED_FRAMEWORK_SOURCES: Set<String> = linkedSetOf("gps", "network")
    }
}

/** Pure distance implementation so JVM tests exercise the exact production comparison. */
internal object HaversineDistance {
    private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8

    fun meters(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double,
    ): Double {
        val latARadians = Math.toRadians(latitudeA)
        val latBRadians = Math.toRadians(latitudeB)
        val deltaLatitude = latBRadians - latARadians
        val deltaLongitude = Math.toRadians(longitudeB - longitudeA)
        val halfChord = sin(deltaLatitude / 2.0).let { it * it } +
            cos(latARadians) * cos(latBRadians) *
            sin(deltaLongitude / 2.0).let { it * it }
        return 2.0 * EARTH_MEAN_RADIUS_METERS *
            atan2(sqrt(halfChord), sqrt((1.0 - halfChord).coerceAtLeast(0.0)))
    }
}

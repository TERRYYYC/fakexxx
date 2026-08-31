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
        val representative = readbacks.maxWithOrNull(
            compareBy<SystemMockLocationReadback> { it.observedAtElapsedRealtimeMs }
                .thenBy { it.source },
        )
        val fingerprint = readbacks
            .sortedBy { it.source }
            .joinToString(separator = "|") { sample ->
                "${sample.source}:${sample.latitude.toBits()}:${sample.longitude.toBits()}:${sample.isMock}"
            }
            .ifEmpty { "unavailable" }
            .let { "system-mock:$it" }

        val selected = requiredSources.sorted().mapNotNull { source ->
            readbacks.filter { it.source == source }.singleOrNull()
        }
        val hasEveryRequiredSource = selected.size == requiredSources.size
        val targetIsValid = validCoordinates(targetLatitude, targetLongitude)
        val everySourceVerified = hasEveryRequiredSource && selected.all { sample ->
            sample.isMock &&
                sample.observedAtElapsedRealtimeMs >= publishNotBeforeElapsedRealtimeMs &&
                validCoordinates(sample.latitude, sample.longitude) &&
                HaversineDistance.meters(
                    targetLatitude,
                    targetLongitude,
                    sample.latitude,
                    sample.longitude,
                ) <= ContractV1.TRUSTED_LOCATION_TOLERANCE_METERS
        }

        return SystemMockTrustResult(
            latitude = representative?.latitude,
            longitude = representative?.longitude,
            isMock = readbacks.takeIf { it.isNotEmpty() }?.all { it.isMock },
            verified = publishNotBeforeElapsedRealtimeMs > 0L &&
                targetIsValid && everySourceVerified,
            fingerprint = fingerprint,
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

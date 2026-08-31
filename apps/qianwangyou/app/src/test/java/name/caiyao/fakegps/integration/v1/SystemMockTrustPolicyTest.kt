package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SystemMockTrustPolicyTest {

    private val targetLatitude = 31.2304
    private val targetLongitude = 121.4737
    private val publishAnchorMs = 1_000L

    @Test
    fun `distance at 0_99 metres verifies but 1_01 metres fails closed`() {
        val within = policy(readbacksAtDistance(0.99)).evaluateTarget()
        val outside = policy(readbacksAtDistance(1.01)).evaluateTarget()

        assertTrue("0.99 m is within the frozen 1.0 m tolerance", within.verified)
        assertFalse("1.01 m exceeds the frozen 1.0 m tolerance", outside.verified)
    }

    @Test
    fun `requested lastApplied target cannot replace a mismatching OS readback`() {
        val actualLatitude = targetLatitude + latitudeOffsetMetres(20.0)
        val actual = frameworkReadbacks(
            latitude = actualLatitude,
            longitude = targetLongitude,
            observedAtMs = publishAnchorMs,
        )

        val result = policy(actual).evaluateTarget()

        assertFalse("the actual OS location is 20 m away and must not verify", result.verified)
        assertEquals("the observation must expose actual, not requested, latitude", actualLatitude, result.latitude!!, 0.0)
        assertEquals(targetLongitude, result.longitude!!, 0.0)
    }

    @Test
    fun `missing stale and non mock readbacks all fail closed`() {
        val cases = listOf(
            "no readback" to emptyList(),
            "one required source missing" to listOf(
                readback("gps", targetLatitude, targetLongitude, true, publishAnchorMs),
            ),
            "one required source stale" to listOf(
                readback("gps", targetLatitude, targetLongitude, true, publishAnchorMs),
                readback("network", targetLatitude, targetLongitude, true, publishAnchorMs - 1L),
            ),
            "one required source non-mock" to listOf(
                readback("gps", targetLatitude, targetLongitude, true, publishAnchorMs),
                readback("network", targetLatitude, targetLongitude, false, publishAnchorMs),
            ),
        )

        cases.forEach { (label, readbacks) ->
            assertFalse(label, policy(readbacks).evaluateTarget().verified)
        }

        val throwing = SystemMockTrustPolicy(
            SystemMockLocationReader { throw SecurityException("location permission denied") },
        ).evaluate(targetLatitude, targetLongitude, publishAnchorMs)
        assertFalse("readback exceptions fail closed", throwing.verified)
    }

    private fun policy(readbacks: List<SystemMockLocationReadback>) =
        SystemMockTrustPolicy(SystemMockLocationReader { readbacks })

    private fun SystemMockTrustPolicy.evaluateTarget(): SystemMockTrustResult =
        evaluate(targetLatitude, targetLongitude, publishAnchorMs)

    private fun readbacksAtDistance(distanceMetres: Double): List<SystemMockLocationReadback> =
        frameworkReadbacks(
            latitude = targetLatitude + latitudeOffsetMetres(distanceMetres),
            longitude = targetLongitude,
            observedAtMs = publishAnchorMs,
        )

    private fun frameworkReadbacks(
        latitude: Double,
        longitude: Double,
        observedAtMs: Long,
        isMock: Boolean = true,
    ): List<SystemMockLocationReadback> = listOf(
        readback("gps", latitude, longitude, isMock, observedAtMs),
        readback("network", latitude, longitude, isMock, observedAtMs),
    )

    private fun readback(
        source: String,
        latitude: Double,
        longitude: Double,
        isMock: Boolean,
        observedAtMs: Long,
    ) = SystemMockLocationReadback(
        source = source,
        latitude = latitude,
        longitude = longitude,
        isMock = isMock,
        observedAtElapsedRealtimeMs = observedAtMs,
    )

    private fun latitudeOffsetMetres(distanceMetres: Double): Double =
        distanceMetres / 6_371_008.8 * 180.0 / PI
}

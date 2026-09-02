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
    fun `verification tolerance cannot classify a bitwise coordinate drift as raw refresh`() {
        val nearButDifferent = policy(readbacksAtDistance(0.5)).evaluateTarget()
        val exact = policy(
            frameworkReadbacks(targetLatitude, targetLongitude, publishAnchorMs),
        ).evaluateTarget()

        assertTrue("the near sample remains valid verification evidence", nearButDifferent.verified)
        assertFalse(
            "a digest-visible coordinate change must enter semantic repair",
            nearButDifferent.matchesExactTargetProjection,
        )
        assertTrue(exact.matchesExactTargetProjection)
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

    @Test
    fun `verified evidence time is the conservative required source minimum`() {
        val result = policy(
            listOf(
                readback("gps", targetLatitude, targetLongitude, true, publishAnchorMs + 200L),
                readback("network", targetLatitude, targetLongitude, true, publishAnchorMs + 100L),
            ),
        ).evaluateTarget()

        assertTrue(result.verified)
        assertEquals(publishAnchorMs + 100L, result.evidenceObservedAtElapsedRealtimeMs)
        assertEquals(
            mapOf(
                "gps" to publishAnchorMs + 200L,
                "network" to publishAnchorMs + 100L,
            ),
            result.verifiedSourceElapsedRealtimeMs,
        )
    }

    @Test
    fun `disabled required provider fails closed even when its cache entry looks valid`() {
        val readbacks = frameworkReadbacks(
            latitude = targetLatitude,
            longitude = targetLongitude,
            observedAtMs = publishAnchorMs,
        ).map { sample ->
            if (sample.source == "network") sample.copy(providerEnabled = false) else sample
        }

        assertFalse(policy(readbacks).evaluateTarget().verified)
    }

    @Test
    fun `new samples in the same environment retain one time independent fingerprint`() {
        val first = policy(
            frameworkReadbacks(targetLatitude, targetLongitude, publishAnchorMs),
        ).evaluateTarget()
        val later = policy(
            frameworkReadbacks(targetLatitude, targetLongitude, publishAnchorMs + 14_000L),
        ).evaluateTarget()

        assertTrue(first.verified)
        assertTrue(later.verified)
        assertEquals(first.fingerprint, later.fingerprint)
        assertFalse(
            "sample time is a watermark, not environment identity",
            first.verifiedSourceElapsedRealtimeMs == later.verifiedSourceElapsedRealtimeMs,
        )
    }

    @Test
    fun `inactive ownership cannot hide a foreign active mock projection`() {
        val foreignProjection = policy(
            frameworkReadbacks(targetLatitude, targetLongitude, publishAnchorMs),
        ).evaluateTarget()

        val selected = foreignProjection.forAuthoritativeDigest(
            projectionExpectedActive = false,
        )

        assertTrue(selected is AuthoritativeProjectionReadback.Observed)
        assertTrue((selected as AuthoritativeProjectionReadback.Observed).result.projectionActive)
    }

    @Test
    fun `inactive digest requires complete nonmock proof and rejects partial or missing state`() {
        val inactive = policy(
            frameworkReadbacks(
                targetLatitude,
                targetLongitude,
                publishAnchorMs,
                isMock = false,
            ),
        ).evaluateTarget().forAuthoritativeDigest(projectionExpectedActive = false)
        val partial = policy(
            listOf(
                readback("gps", targetLatitude, targetLongitude, true, publishAnchorMs),
                readback("network", targetLatitude, targetLongitude, false, publishAnchorMs),
            ),
        ).evaluateTarget().forAuthoritativeDigest(projectionExpectedActive = false)
        val missing = policy(emptyList()).evaluateTarget()
            .forAuthoritativeDigest(projectionExpectedActive = false)

        assertTrue(inactive is AuthoritativeProjectionReadback.Inactive)
        assertTrue(partial is AuthoritativeProjectionReadback.Unknown)
        assertTrue(missing is AuthoritativeProjectionReadback.Unknown)
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

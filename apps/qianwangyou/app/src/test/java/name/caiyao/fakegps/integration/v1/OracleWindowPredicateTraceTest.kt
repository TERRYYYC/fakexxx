package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decomposition of the authoritative window predicates (#153). These pin
 * the reason vocabulary AND its precedence: the reason must be the FIRST
 * failing predicate in EnvironmentObserver's evaluation order, otherwise the
 * audit row names a symptom that is not the cause.
 */
class OracleWindowPredicateTraceTest {

    private val expected = Triple("name.caiyao.fakegps", 10_321, "digest-a")

    @Test
    fun `a complete matching window is valid with no reason`() {
        val trace = decompose(stable(), stable())

        assertTrue(trace.windowValid)
        assertNull(trace.invalidReason())
        assertTrue(trace.prePresent && trace.postPresent)
        assertTrue(trace.ownerConfigured && trace.ownerMatch)
        assertEquals(AuthoritativeWindowVerdict.VALID, trace.verdict)
        assertTrue(trace.digestMatch && trace.epochStable && !trace.staleReplayRejected)
    }

    @Test
    fun `unconfigured owner is named even before the endpoints`() {
        val trace = OracleWindowDiagnostics.decompose(
            pre = null, post = null, expectedPackage = null, expectedUid = null, expectedDigest = null,
        )

        assertFalse(trace.windowValid)
        assertEquals("owner_unconfigured", trace.invalidReason())
    }

    @Test
    fun `absent endpoints are pre_null and post_null`() {
        assertEquals(
            "pre_null",
            decompose(null, stable()).invalidReason(),
        )
        assertEquals(
            "post_null",
            decompose(stable(), null).invalidReason(),
        )
    }

    @Test
    fun `within-window identity changes map to their verdict reasons`() {
        assertEquals(
            "boot_instance_changed",
            decompose(stable(), stable().copy(bootId = "other-boot")).invalidReason(),
        )
        assertEquals(
            "sequence_regression",
            decompose(stable(sequence = 10L), stable(sequence = 8L)).invalidReason(),
        )
        assertEquals(
            "mutating_or_changed",
            decompose(stable(sequence = 8L), stable(sequence = 10L)).invalidReason(),
        )
    }

    @Test
    fun `unhealthy endpoint with matching owner stays unhealthy`() {
        val trace = decompose(stable(health = AuthoritativeOracleHealth.HOOKS_INCOMPLETE), stable())

        assertEquals(AuthoritativeWindowVerdict.UNHEALTHY, trace.verdict)
        assertTrue(trace.ownerMatch)
        assertEquals("unhealthy", trace.invalidReason())
    }

    @Test
    fun `unhealthy endpoint with foreign owner is owner_mismatch`() {
        val trace = decompose(stable(), stable(ownerPackage = "some.other.lane"))

        assertEquals(AuthoritativeWindowVerdict.UNHEALTHY, trace.verdict)
        assertFalse(trace.ownerMatch)
        assertEquals("owner_mismatch", trace.invalidReason())
    }

    @Test
    fun `a stable window echoing the wrong digest is digest_mismatch`() {
        val trace = decompose(stable(), stable(), expectedDigest = "digest-other")

        assertTrue(trace.verdict == AuthoritativeWindowVerdict.VALID)
        assertFalse(trace.digestMatch)
        assertEquals("digest_mismatch", trace.invalidReason())
    }

    @Test
    fun `durable replay watermarks map to their own reasons`() {
        val stale = decompose(stable(), stable(), staleReplayRejected = true)
        assertFalse(stale.windowValid)
        assertEquals("stale_replay", stale.invalidReason())

        val epochChanged = decompose(stable(), stable(), epochStable = false)
        assertFalse(epochChanged.windowValid)
        assertEquals("epoch_changed", epochChanged.invalidReason())
    }

    @Test
    fun `the reason follows the judgement precedence not all failures`() {
        val trace = decompose(
            pre = stable(),
            post = stable(),
            expectedDigest = "digest-other",
            staleReplayRejected = true,
            epochStable = false,
        )

        assertEquals("digest_mismatch", trace.invalidReason())
    }

    /** Mirrors the harness snapshot of the projection tests. */
    private fun stable(
        sequence: Long = 8L,
        health: AuthoritativeOracleHealth = AuthoritativeOracleHealth.HEALTHY,
        ownerPackage: String = expected.first,
    ): AuthoritativeContinuitySnapshot = AuthoritativeContinuitySnapshot(
        protocolVersion = 1,
        bootId = "123e4567-e89b-12d3-a456-426614174000",
        oracleInstanceId = "oracle-a",
        sequence = sequence,
        ownerUid = expected.second,
        ownerPackage = ownerPackage,
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        requiredCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = health,
        qwySemanticDigest = expected.third,
        lastCompletedQwyMutationId = null,
    )

    private fun decompose(
        pre: AuthoritativeContinuitySnapshot?,
        post: AuthoritativeContinuitySnapshot?,
        expectedDigest: String? = expected.third,
        staleReplayRejected: Boolean = false,
        epochStable: Boolean = true,
    ): OracleWindowPredicateTrace = OracleWindowDiagnostics.decompose(
        pre = pre,
        post = post,
        expectedPackage = expected.first,
        expectedUid = expected.second,
        expectedDigest = expectedDigest,
        staleReplayRejected = staleReplayRejected,
        epochStable = epochStable,
    )
}

package name.caiyao.fakegps.ui.screen.verify

import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.verify.VerificationSummary
import name.caiyao.fakegps.verify.ProbeFailure
import name.caiyao.fakegps.verify.ProbeUiStatus
import name.caiyao.fakegps.verify.ObservationScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyUiContractTest {

    @Test
    fun payloadCompatibilityUsesTheSharedTransportContract() {
        assertTrue(PayloadStatus.Ok(ConfigPrefsSync.SCHEMA_VERSION, 1).compatible)
        assertTrue(PayloadStatus.Ok(ConfigPrefsSync.LEGACY_SCHEMA_VERSION, 1).compatible)
        assertFalse(PayloadStatus.Ok(1, 1).compatible)
    }

    @Test
    fun partialCopyExplainsAmbiguousFieldsInsteadOfRenderingAnEmptyClause() {
        val detail = partialVerificationDetail(
            VerificationSummary(
                spoofed = 1,
                mismatch = 0,
                unobservable = 0,
                passthrough = 0,
                ambiguous = 2,
            ),
        )

        assertTrue(detail.contains("2 个值与读取基线相同"))
        assertTrue(detail.contains("明显不同"))
        assertFalse(detail.contains("另有 ，"))
    }

    @Test
    fun probeFailureCopySeparatesScopeAndTimeoutFromFieldMismatch() {
        assertTrue(probeFailureMessage(ProbeFailure.NOT_SCOPED).contains("Vector"))
        assertTrue(probeFailureMessage(ProbeFailure.TIMEOUT).contains("超时"))
        assertFalse(probeFailureMessage(ProbeFailure.NOT_SCOPED).contains("字段未生效"))
    }

    @Test
    fun probeStatusCopyOnlyClaimsRuntimeEvidenceAfterFingerprintMatch() {
        val verified = probeStatusCopy(ProbeUiStatus.Verified).second
        val unavailable = probeStatusCopy(ProbeUiStatus.Failed(ProbeFailure.NOT_SCOPED)).second

        assertTrue(verified.contains("配置指纹匹配"))
        assertTrue(verified.contains("公共 API"))
        assertFalse(unavailable.contains("字段未生效"))
    }

    @Test
    fun missingProbeObservationNeverFallsBackToARealBaselineUnderTheProbeLabel() {
        assertNull(
            displayedObservation(
                ObservationScope.HOOK_PROBE,
                observed = null,
                baseline = "11111",
            ),
        )
        assertEquals(
            "11111",
            displayedObservation(
                ObservationScope.REAL_BASELINE,
                observed = null,
                baseline = "11111",
            ),
        )
    }
}

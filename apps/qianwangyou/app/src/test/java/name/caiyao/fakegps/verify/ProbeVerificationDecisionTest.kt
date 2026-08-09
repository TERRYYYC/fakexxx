package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeVerificationDecisionTest {
    private val request = ProbeRequest("request-1", "sha256:1111111111111111")
    private val envelope = ProbeObservationEnvelope(
        requestId = request.requestId,
        fingerprint = request.fingerprint,
        values = mapOf("tac" to "22222"),
        notes = listOf("probe note"),
        cellCount = 2,
    )

    @Test
    fun `delivered probe is the only source of hook observations`() {
        val decision = ProbeVerificationDecision.resolve(
            VerificationRequestState.Delivered(request, envelope),
        )

        assertEquals(ObservationScope.HOOK_PROBE, decision.scope)
        assertEquals(mapOf("tac" to "22222"), decision.observed)
        assertEquals(ProbeUiStatus.Verified, decision.status)
        assertNull(decision.failure)
    }

    @Test
    fun `probe failure stays separate from field verdicts`() {
        val decision = ProbeVerificationDecision.resolve(
            VerificationRequestState.Failed(request, ProbeFailure.NOT_SCOPED),
        )

        assertEquals(ObservationScope.REAL_BASELINE, decision.scope)
        assertEquals(emptyMap<String, String>(), decision.observed)
        assertEquals(ProbeUiStatus.Failed(ProbeFailure.NOT_SCOPED), decision.status)
        assertEquals(ProbeFailure.NOT_SCOPED, decision.failure)
    }

    @Test
    fun `unstarted request produces no synthetic failure or observation`() {
        val decision = ProbeVerificationDecision.resolve(VerificationRequestState.Idle)
        assertEquals(ProbeUiStatus.NotRequested, decision.status)
        assertEquals(emptyMap<String, String>(), decision.observed)
        assertNull(decision.failure)
    }
}

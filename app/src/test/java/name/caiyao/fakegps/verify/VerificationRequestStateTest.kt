package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationRequestStateTest {

    private val first = ProbeRequest("request-1", "sha256:1111111111111111")
    private val second = ProbeRequest("request-2", "sha256:2222222222222222")

    @Test
    fun `matching result completes the active request`() {
        val starting = VerificationRequestCoordinator.start(first)
        val envelope = envelope(first)

        assertEquals(
            VerificationRequestState.Delivered(first, envelope),
            VerificationRequestCoordinator.accept(starting, envelope),
        )
    }

    @Test
    fun `stale request id or fingerprint cannot replace the active result`() {
        val starting = VerificationRequestCoordinator.start(second)

        assertEquals(starting, VerificationRequestCoordinator.accept(starting, envelope(first)))
        assertEquals(
            starting,
            VerificationRequestCoordinator.accept(
                starting,
                envelope(second).copy(fingerprint = first.fingerprint),
            ),
        )
    }

    @Test
    fun `client correlation accepts only the active request while coordinator keeps stale active`() {
        val starting = VerificationRequestCoordinator.start(second)

        assertTrue(ProbeResultCorrelation.matches(second, envelope(second)))
        assertTrue(!ProbeResultCorrelation.matches(second, envelope(first)))
        assertTrue(
            !ProbeResultCorrelation.matches(
                second,
                envelope(second).copy(fingerprint = first.fingerprint),
            ),
        )
        assertEquals(starting, VerificationRequestCoordinator.accept(starting, envelope(first)))
    }

    @Test
    fun `timeout drops any old green and retry starts a fresh correlation key`() {
        val delivered: VerificationRequestState =
            VerificationRequestState.Delivered(first, envelope(first))
        val retry = VerificationRequestCoordinator.start(second)
        val failed = VerificationRequestCoordinator.fail(
            retry,
            second,
            ProbeFailure.TIMEOUT,
        )

        assertTrue(delivered is VerificationRequestState.Delivered)
        assertEquals(VerificationRequestState.Failed(second, ProbeFailure.TIMEOUT), failed)
        assertEquals(retry, VerificationRequestCoordinator.accept(retry, envelope(first)))
    }

    @Test
    fun `failure for an older request is ignored`() {
        val starting = VerificationRequestCoordinator.start(second)
        assertEquals(
            starting,
            VerificationRequestCoordinator.fail(starting, first, ProbeFailure.NOT_SCOPED),
        )
    }

    private fun envelope(request: ProbeRequest) = ProbeObservationEnvelope(
        requestId = request.requestId,
        fingerprint = request.fingerprint,
        values = mapOf("tac" to "22222"),
        notes = emptyList(),
        cellCount = 1,
    )
}

package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeObservationCodecTest {

    @Test
    fun `round trip preserves correlation provenance and public observations`() {
        val envelope = ProbeObservationEnvelope(
            requestId = "verify-123",
            fingerprint = "sha256:0123456789abcdef",
            values = linkedMapOf("tac" to "22222", "operator_name" to "", "earfcn" to "--"),
            notes = listOf("wifi unavailable"),
            cellCount = 2,
        )

        assertEquals(envelope, ProbeObservationCodec.decode(ProbeObservationCodec.encode(envelope)))
    }

    @Test
    fun `malformed result is rejected instead of producing a partial observation`() {
        assertNull(ProbeObservationCodec.decode("not-json"))
        assertNull(ProbeObservationCodec.decode(""))
    }
}

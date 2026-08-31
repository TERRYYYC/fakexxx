package name.caiyao.fakegps.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OracleBundleCodecTest {

    private val valid = OracleWireSnapshot(
        protocolVersion = 1,
        bootId = "123e4567-e89b-12d3-a456-426614174000",
        oracleInstanceId = "oracle-instance-1",
        sequence = 8L,
        ownerUid = 10_321,
        ownerPackage = "name.caiyao.fakegps",
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        requiredCoverageMask = 0x3ffL,
        installedCoverageMask = 0x3ffL,
        health = OracleWireHealth.HEALTHY,
        qwySemanticDigest = "semantic-digest",
        lastCompletedQwyMutationId = "mutation-7",
    )

    @Test
    fun `strict field-map round trip preserves every v1 field`() {
        assertEquals(valid, OracleBundleCodec.decodeFields(OracleBundleCodec.encodeFields(valid)))
    }

    @Test
    fun `missing required field fails closed`() {
        val fields = OracleBundleCodec.encodeFields(valid).toMutableMap()
        fields.remove(OracleBundleCodec.KEY_SEQUENCE)

        assertNull(OracleBundleCodec.decodeFields(fields))
    }

    @Test
    fun `unknown extra field fails closed`() {
        val fields = OracleBundleCodec.encodeFields(valid).toMutableMap()
        fields["futureField"] = "must-not-be-ignored"

        assertNull(OracleBundleCodec.decodeFields(fields))
    }

    @Test
    fun `protocol mismatch and unknown health fail closed`() {
        val wrongProtocol = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this[OracleBundleCodec.KEY_PROTOCOL_VERSION] = 2
        }
        val unknownHealth = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this[OracleBundleCodec.KEY_HEALTH] = "FUTURE_HEALTH"
        }

        assertNull(OracleBundleCodec.decodeFields(wrongProtocol))
        assertNull(OracleBundleCodec.decodeFields(unknownHealth))
    }

    @Test
    fun `malformed boot identity sequence and owner tuple fail closed`() {
        val malformedBoot = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this[OracleBundleCodec.KEY_BOOT_ID] = "not-a-kernel-boot-uuid"
        }
        val negativeSequence = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this[OracleBundleCodec.KEY_SEQUENCE] = -1L
        }
        val splitOwner = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this[OracleBundleCodec.KEY_OWNER_PACKAGE] = null
        }

        assertNull(OracleBundleCodec.decodeFields(malformedBoot))
        assertNull(OracleBundleCodec.decodeFields(negativeSequence))
        assertNull(OracleBundleCodec.decodeFields(splitOwner))
    }
}

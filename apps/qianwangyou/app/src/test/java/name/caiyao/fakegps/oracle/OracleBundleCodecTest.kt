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
    fun `strict v1 field map round trips every field`() {
        assertEquals(valid, OracleBundleCodec.decodeFields(OracleBundleCodec.encodeFields(valid)))
    }

    @Test
    fun `missing extra wrong typed and wrong version fields are source absence`() {
        val missing = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            remove(OracleBundleCodec.KEY_SEQUENCE)
        }
        val extra = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this["futureField"] = "must-not-be-ignored"
        }
        val wrongType = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this[OracleBundleCodec.KEY_SEQUENCE] = 8
        }
        val wrongVersion = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
            this[OracleBundleCodec.KEY_PROTOCOL_VERSION] = 2
        }

        assertNull(OracleBundleCodec.decodeFields(missing))
        assertNull(OracleBundleCodec.decodeFields(extra))
        assertNull(OracleBundleCodec.decodeFields(wrongType))
        assertNull(OracleBundleCodec.decodeFields(wrongVersion))
    }

    @Test
    fun `invalid identity sequence or split owner tuple is source absence`() {
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

    @Test
    fun `nullable v1 strings reject a nonnull value of the wrong type`() {
        listOf(
            OracleBundleCodec.KEY_OWNER_PACKAGE,
            OracleBundleCodec.KEY_QWY_SEMANTIC_DIGEST,
            OracleBundleCodec.KEY_LAST_COMPLETED_QWY_MUTATION_ID,
        ).forEach { key ->
            val malformed = OracleBundleCodec.encodeFields(valid).toMutableMap().apply {
                this[key] = 123
            }
            assertNull("wrong type for $key must not become absent", OracleBundleCodec.decodeFields(malformed))
        }
    }

    @Test
    fun `nullable v1 strings retain explicit null semantics`() {
        val noOwner = valid.copy(ownerUid = null, ownerPackage = null)
        val noOptionalMetadata = noOwner.copy(qwySemanticDigest = null, lastCompletedQwyMutationId = null)

        assertEquals(noOptionalMetadata, OracleBundleCodec.decodeFields(OracleBundleCodec.encodeFields(noOptionalMetadata)))
    }
}

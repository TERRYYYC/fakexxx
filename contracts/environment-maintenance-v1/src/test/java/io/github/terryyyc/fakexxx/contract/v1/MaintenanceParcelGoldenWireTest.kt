package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Golden BYTE-LAYOUT pins for [MaintenanceResultV1] — the maintenance half of
 * the #186 root fix.
 *
 * Same mechanism and discipline as the control channel's
 * ContractParcelGoldenWireTest (read its KDoc for the incident and rationale):
 * this module is compiled separately by EACH consuming Gradle root
 * (apps/cellrebel-auto and apps/qianwangyou), so both roots must reproduce the
 * same committed golden bytes through their own kotlin-parcelize output —
 * writer (writeToParcel, the AIDL wire payload) and reader (generated CREATOR).
 *
 * Vectors: ALL 2^4 = 16 combinations of the four nullable fields on the ERROR
 * result kind (bit order b0=errorCodeWire, b1=diagnosticMessage,
 * b2=scheduleVersionAfter, b3=republishedProfileRef), plus the OK result kind.
 *
 * Regeneration (deliberate, reviewed act — §6.1 freezes the layout):
 *
 * 1. `cd apps/cellrebel-auto && DUMP_PARCEL_GOLDENS=/tmp/golden-maintenance.txt \
 *      ./gradlew --no-daemon :environment-maintenance-v1:testDebugUnitTest \
 *      --tests '*MaintenanceParcelGoldenWireTest*' --rerun-tasks`
 * 2. Paste /tmp/golden-maintenance.txt into [GOLDEN] verbatim.
 * 3. Run the same module test from apps/qianwangyou; a cross-root mismatch is
 *    a #186 recurrence — fix the build, never the golden.
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceParcelGoldenWireTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    private fun encodeHex(value: Parcelable): String {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return hex(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Decode golden hex through the generated CREATOR — the same static CREATOR
     * an AIDL stub uses on the read side (writeToParcel output has no class-name
     * prefix, so [Parcel.readParcelable] must NOT be used here).
     */
    private fun decodeWith(hexString: String): MaintenanceResultV1 {
        val bytes = unhex(hexString)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return MaintenanceCreators.maintenanceResult().createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun goldenTable(): Map<String, String> =
        GOLDEN.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val sep = line.indexOf('|')
                require(sep > 0) { "bad golden line: $line" }
                line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }

    /** Bit order b0=errorCodeWire b1=diagnosticMessage b2=scheduleVersionAfter b3=republishedProfileRef. */
    private fun errorResult(mask: Int): MaintenanceResultV1 = MaintenanceResultV1(
        resultSchemaVersion = MaintenanceResultV1.SCHEMA_VERSION,
        resultKindWire = MaintenanceResultKindV1.ERROR.wire,
        errorCodeWire = if (mask and 1 != 0) MaintenanceResetErrorCodeV1.PUBLISH_FAILED.wire else null,
        diagnosticMessage = if (mask and 2 != 0) "diagnostic-golden-maint-01" else null,
        scheduleVersionAfter = if (mask and 4 != 0) 9_090L else null,
        republishedProfileRef = if (mask and 8 != 0) "profile-golden-maint-01" else null,
    )

    private fun okResult() = MaintenanceResultV1(
        resultSchemaVersion = MaintenanceResultV1.SCHEMA_VERSION,
        resultKindWire = MaintenanceResultKindV1.OK.wire,
        errorCodeWire = null,
        diagnosticMessage = null,
        scheduleVersionAfter = 9_091L,
        republishedProfileRef = "profile-golden-maint-02",
    )

    private fun canonicalVectors(): List<Pair<String, Parcelable>> {
        val vectors = mutableListOf<Pair<String, Parcelable>>()
        vectors.add("maintenance/ok" to okResult())
        for (mask in 0 until 16) {
            vectors.add("maintenance/error/" + mask.toBinaryString(4) to errorResult(mask))
        }
        return vectors
    }

    private fun Int.toBinaryString(bits: Int): String {
        var out = ""
        for (i in bits - 1 downTo 0) out += if ((this shr i) and 1 != 0) "1" else "0"
        return out
    }

    @Test
    fun `golden vectors are byte-identical under this root's toolchain`() {
        val table = goldenTable()
        val vectors = canonicalVectors()
        assertEquals("golden vector count drifted", 17, table.size)
        assertEquals(table.keys.sorted(), vectors.map { it.first }.toSet().sorted())
        for ((name, instance) in vectors) {
            val expected = requireNotNull(table[name]) { "missing golden vector '$name'" }
            val actual = encodeHex(instance)
            if (expected != actual) {
                fail(
                    "PARCEL LAYOUT DRIFT for '$name' under this root's toolchain — this is " +
                        "the #186 failure mode. Fix the build, do not touch the golden.\n" +
                        "expected: $expected\n" +
                        "actual:   $actual",
                )
            }
            assertEquals("decode of golden '$name' drifted", instance, decodeWith(expected))
            assertEquals("decode of actual bytes '$name' mismatched", instance, decodeWith(actual))
        }
    }

    /** Regeneration gate: a no-op in normal runs (see class KDoc). */
    @Test
    fun `dump golden vectors for regeneration is gated`() {
        val dumpPath = System.getenv("DUMP_PARCEL_GOLDENS") ?: return
        java.io.File(dumpPath).writeText(goldenLiteral())
        println("MaintenanceParcelGoldenWireTest golden literal written to $dumpPath")
    }

    private fun goldenLiteral(): String = buildString {
        append("# MaintenanceParcelGoldenWireTest golden vectors (#186 byte-layout pin).\n")
        append("# Format: name|lowercase-hex-of-writeToParcel-output. Regenerate via DUMP_PARCEL_GOLDENS (see class KDoc).\n")
        for ((name, instance) in canonicalVectors()) {
            append(name).append('|').append(encodeHex(instance)).append('\n')
        }
    }

    // ----------------------------------------------------------- golden bytes
    // Pasted from the gated dump (see class KDoc). DO NOT hand-edit; do not
    // regenerate without a byte-by-byte review of the diff.

    private val GOLDEN: String = """# MaintenanceParcelGoldenWireTest golden vectors (#186 byte-layout pin).
# Format: name|lowercase-hex-of-writeToParcel-output. Regenerate via DUMP_PARCEL_GOLDENS (see class KDoc).
maintenance/ok|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b02000078700000000177040000000471007e00027704000000047371007e0000000000007704000000047077040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238377040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3032
maintenance/error/0000|aced000577080000000600000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e0000000000007704000000047077040000000471007e000477040000000470
maintenance/error/0001|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e000000000007770400000004707704000000047371007e00000000000077040000000470
maintenance/error/0010|aced000577080000000600000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e00000000000077040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d303177040000000471007e000477040000000470
maintenance/error/0011|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e00000000000777040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d30317704000000047371007e00000000000077040000000470
maintenance/error/0100|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e0000000000007704000000047077040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000000470
maintenance/error/0101|aced000577080000000800000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e0000000000077704000000047077040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000000470
maintenance/error/0110|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e00000000000077040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d303177040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000000470
maintenance/error/0111|aced000577080000000800000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e00000000000777040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d303177040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000000470
maintenance/error/1000|aced000577080000000600000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e0000000000007704000000047077040000000471007e000477040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
maintenance/error/1001|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e000000000007770400000004707704000000047371007e00000000000077040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
maintenance/error/1010|aced000577080000000600000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e00000000000077040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d303177040000000471007e000477040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
maintenance/error/1011|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e00000000000777040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d30317704000000047371007e00000000000077040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
maintenance/error/1100|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e0000000000007704000000047077040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
maintenance/error/1101|aced000577080000000800000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e0000000000077704000000047077040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
maintenance/error/1110|aced000577080000000700000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e0000000000027704000000047371007e00000000000077040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d303177040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
maintenance/error/1111|aced000577080000000800000004737200116a6176612e6c616e672e496e746567657212e2a0a4f781873802000149000576616c7565787200106a6176612e6c616e672e4e756d62657286ac951d0b94e08b0200007870000000017704000000047371007e00000000000277040000000471007e00027704000000047371007e00000000000777040000003c74001a646961676e6f737469632d676f6c64656e2d6d61696e742d303177040000000471007e00027704000000087372000e6a6176612e6c616e672e4c6f6e673b8be490cc8f23df0200014a000576616c75657871007e0001000000000000238277040000003474001770726f66696c652d676f6c64656e2d6d61696e742d3031
"""
}

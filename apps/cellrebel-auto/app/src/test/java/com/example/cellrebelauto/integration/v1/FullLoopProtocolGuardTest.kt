package com.example.cellrebelauto.integration.v1

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static topology guards for the debug acceptance probe's protocol-v1 trust boundary. */
class FullLoopProtocolGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val source = File(
        moduleRoot,
        "src/debug/java/com/example/cellrebelauto/integration/v1/FullLoopProbeActivity.kt"
    ).readText()

    @Test
    fun `initial discover rejects protocol skew before preflight or apply`() {
        val discover = source.indexOf("val snap = requireValid(\"[1] discover\"")
        val protocolGate = source.indexOf("snap.protocolVersion != ContractV1.PROTOCOL_VERSION")
        val preflight = source.indexOf("// ---- 2. preflight")

        assertTrue("initial discover must exist", discover >= 0)
        assertTrue("protocol-v1 gate must follow initial discover", protocolGate > discover)
        assertTrue("protocol-v1 gate must precede preflight", protocolGate < preflight)
    }

    @Test
    fun `terminal discover readback rejects protocol skew before an EXHAUSTED success verdict`() {
        val readback = source.indexOf("val readback = requireValid(\"[7b] exhausted readback\"")
        val protocolGate = source.indexOf(
            "readback.protocolVersion != ContractV1.PROTOCOL_VERSION",
            startIndex = readback.coerceAtLeast(0)
        )
        val success = source.indexOf("LOOP COMPLETE — EXHAUSTED", startIndex = readback.coerceAtLeast(0))

        assertTrue("terminal readback must exist", readback >= 0)
        assertTrue("readback protocol-v1 gate must follow the readback", protocolGate > readback)
        assertTrue("readback protocol-v1 gate must precede the success verdict", protocolGate < success)
    }
}

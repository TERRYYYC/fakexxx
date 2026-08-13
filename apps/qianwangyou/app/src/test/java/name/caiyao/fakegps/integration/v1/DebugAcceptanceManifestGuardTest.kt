package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the qwy DEBUG manifest's coexisting surfaces.
 *
 * WHY: ff59c27 REPLACED src/debug/AndroidManifest.xml wholesale to add the
 * pairing-approval surface, silently deleting the acceptance harness that was
 * already there: the RUN_HOOK_ACCEPTANCE signature permission, the
 * HookAcceptanceApplication application class, and both acceptance activities.
 * No git conflict, no test failure — three load-bearing debug surfaces gone
 * (Sol main-control mismatch ruling on the #7 handoff). This is the second
 * wholesale-replace-shared-config fault in this repo's history, so it gets a
 * guard: BOTH surface families must be declared, additions may not evict.
 *
 * Deliberately NOT a §10 ledger row — this guards build configuration, not
 * contract semantics.
 */
class DebugAcceptanceManifestGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val manifest: String =
        File(moduleRoot, "src/debug/AndroidManifest.xml").readText()

    private fun mustContain(needle: String, why: String) {
        assertTrue(
            "debug manifest lost \"$needle\" — $why",
            manifest.contains(needle),
        )
    }

    /** Acceptance harness surface (pre-existing; deleted by ff59c27, restored after). */
    @Test
    fun acceptanceHarnessSurfaceIsDeclared() {
        mustContain(
            "\${applicationId}.permission.RUN_HOOK_ACCEPTANCE",
            "the signature-level permission gating the hook acceptance probe",
        )
        mustContain(
            "android:name=\".probe.HookAcceptanceApplication\"",
            "the debug Application class the acceptance probes bootstrap through",
        )
        mustContain(
            ".probe.HookAcceptanceActivity",
            "the hook acceptance entry activity",
        )
        mustContain(
            ".mockprovider.MockProviderAcceptanceActivity",
            "the shell-only seam that seeds isolated .bench data and guarantees trap cleanup",
        )
    }

    /** Pairing approval surface (added by ff59c27; must coexist, not evict). */
    @Test
    fun pairingApprovalSurfaceIsDeclared() {
        mustContain(
            "name.caiyao.fakegps.integration.v1.PairingApprovalActivity",
            "the §6.5 operator pairing approval entry — without it approve() has no call site",
        )
    }
}

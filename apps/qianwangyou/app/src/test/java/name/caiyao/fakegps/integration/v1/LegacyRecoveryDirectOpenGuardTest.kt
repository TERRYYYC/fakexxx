package name.caiyao.fakegps.integration.v1

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRecoveryDirectOpenGuardTest {

    @Test
    fun `every QWY direct profile database open is preceded by legacy recovery`() {
        val controllerSource = File(
            "src/main/java/name/caiyao/fakegps/integration/v1/QwyEnvironmentController.kt",
        ).readText()
        val runtimeSource = File(
            "src/main/java/name/caiyao/fakegps/integration/v1/ProviderRuntime.kt",
        ).readText()
        val opens = Regex("SQLiteDatabase\\.openDatabase")
            .findAll(controllerSource)
            .map { it.range.first }
            .toList()

        assertEquals(
            "schedule initialization, coordinate lookup and the v1.81 cell-identity lookup " +
                "are the only direct opens",
            3, opens.size)
        assertEquals(
            "the read-only controller must not start recovery from a profile projection, " +
                "coordinate lookup or cell-identity lookup",
            0,
            Regex("AppDatabase\\.ensureLegacyDatabaseRecovered").findAll(controllerSource).count(),
        )
        val recovery = runtimeSource.indexOf("AppDatabase.ensureLegacyDatabaseRecovered(appContext)")
        // #173 note: the controller now takes a third (emission hub) argument, so
        // the construction site wraps lines — the needle matches the constructor
        // reference, not one frozen formatting of it.
        val controller = runtimeSource.indexOf("QwyEnvironmentController(")
        assertTrue("owner-start recovery must precede controller construction", recovery >= 0 && recovery < controller)
// Rebase note: T2's third direct-open came from its (dropped) parallel profileRefs();
// #105's merged profileRefsSnapshot() keeps the count at 2.
// v1.81 rebase note: the CI-attestation projection (operator 2026-09-08) adds ONE
// more read-only open — same class as the coordinate lookup: READONLY, never calls
// recovery itself, and safe because owner-start recovery precedes controller
// construction (asserted above).
    }
}

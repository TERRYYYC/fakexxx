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

        assertEquals("schedule initialization and coordinate lookup are the only direct opens", 2, opens.size)
        assertEquals(
            "the read-only controller must not start recovery from a profile projection or coordinate lookup",
            0,
            Regex("AppDatabase\\.ensureLegacyDatabaseRecovered").findAll(controllerSource).count(),
        )
        val recovery = runtimeSource.indexOf("AppDatabase.ensureLegacyDatabaseRecovered(appContext)")
        val controller = runtimeSource.indexOf("QwyEnvironmentController(appContext")
        assertTrue("owner-start recovery must precede controller construction", recovery >= 0 && recovery < controller)
    }
}

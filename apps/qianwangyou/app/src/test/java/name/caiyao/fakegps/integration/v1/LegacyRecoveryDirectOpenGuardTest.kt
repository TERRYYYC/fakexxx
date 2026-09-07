package name.caiyao.fakegps.integration.v1

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRecoveryDirectOpenGuardTest {

    @Test
    fun `every QWY direct profile database open is preceded by legacy recovery`() {
        val source = File(
            "src/main/java/name/caiyao/fakegps/integration/v1/QwyEnvironmentController.kt",
        ).readText()
        val recoveryCalls = Regex("AppDatabase\\.ensureLegacyDatabaseRecovered\\(appContext\\)")
            .findAll(source)
            .map { it.range.first }
            .toList()
        val opens = Regex("SQLiteDatabase\\.openDatabase")
            .findAll(source)
            .map { it.range.first }
            .toList()

        assertEquals(
            "schedule initialization, coordinate lookup and the discover profileRefs " +
                "projection are the only direct opens",
            3,
            opens.size,
        )
        assertEquals("each direct-open path must attempt recovery", opens.size, recoveryCalls.size)
        assertTrue(recoveryCalls.zip(opens).all { (recovery, open) -> recovery < open })
    }
}

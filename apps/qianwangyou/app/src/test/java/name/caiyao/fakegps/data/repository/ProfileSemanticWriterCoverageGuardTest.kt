package name.caiyao.fakegps.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM guard for the Room-backed suspend methods that otherwise need an Android runner. */
class ProfileSemanticWriterCoverageGuardTest {
    private val source = sequenceOf(File("app"), File("."))
        .map { File(it, "src/main/java/name/caiyao/fakegps/data/repository/ProfileRepository.kt") }
        .first(File::isFile)
        .readText()

    @Test
    fun `save and delete writers are routed through the suspend semantic bracket`() {
        val expectedKinds = listOf("profile-save", "profile-delete", "profile-delete-all")

        expectedKinds.forEach { kind ->
            assertTrue(
                "$kind must use the process-global suspend mutation runtime",
                source.contains("QwySemanticWriterRuntime.mutateSuspend(\"$kind\")"),
            )
        }
        assertEquals(
            "only save and delete paths are covered; archive import is intentionally non-effective",
            expectedKinds.size,
            Regex("QwySemanticWriterRuntime\\.mutateSuspend\\(").findAll(source).count(),
        )
        assertEquals(
            "each selected profile writer must reject a missing or failed publication",
            expectedKinds.size + 1,
            Regex("requireAuthoritativePublication\\(").findAll(source).count(),
        )
    }
}

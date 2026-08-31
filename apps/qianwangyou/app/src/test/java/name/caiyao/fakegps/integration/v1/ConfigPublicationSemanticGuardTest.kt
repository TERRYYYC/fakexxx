package name.caiyao.fakegps.integration.v1

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the central publisher so legacy editors cannot bypass oracle sequencing. */
class ConfigPublicationSemanticGuardTest {
    private val source = sequenceOf(File("app"), File("."))
        .map { File(it, "src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt") }
        .first(File::isFile)
        .readText()

    @Test
    fun `all config publication routes through one semantic writer entry`() {
        assertTrue(source.contains("QwySemanticWriterRuntime.mutate("))
        assertTrue(source.contains("\"config-publish\""))
        assertTrue(source.contains("authoritative config publication failed"))
    }
}

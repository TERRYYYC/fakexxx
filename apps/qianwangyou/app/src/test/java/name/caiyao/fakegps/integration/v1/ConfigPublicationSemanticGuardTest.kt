package name.caiyao.fakegps.integration.v1

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the central publisher so legacy editors cannot bypass oracle sequencing. */
class ConfigPublicationSemanticGuardTest {
    private val moduleRoot = sequenceOf(File("app"), File("."))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }
    private val source = sequenceOf(moduleRoot)
        .map { File(it, "src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt") }
        .first(File::isFile)
        .readText()
    private val controllerSource = File(
        moduleRoot,
        "src/main/java/name/caiyao/fakegps/integration/v1/QwyEnvironmentController.kt",
    ).readText()

    @Test
    fun `all config publication routes through one semantic writer entry`() {
        assertTrue(source.contains("QwySemanticWriterRuntime.mutate("))
        assertTrue(source.contains("\"config-publish\""))
        assertTrue(source.contains("authoritative config publication failed"))
    }

    @Test
    fun `semantic digest binds the durable active publication identity`() {
        assertTrue(source.contains("fun readSemanticPublicationIdentity("))
        assertTrue(source.contains("SemanticPublicationIdentityRead.ReadError"))
        val digest = controllerSource.substringAfter(
            "override fun authoritativeSemanticDigest(ownerGeneration: Long): String?",
        ).substringBefore("override fun authoritativeSemanticMutationEnabled()")

        assertTrue(digest.contains("ConfigPrefsSync.readSemanticPublicationIdentity(appContext)"))
        assertTrue(digest.contains("activeProfileRef = activePublicationId"))
        assertFalse(
            "schedule position is not the independently persisted active publication identity",
            digest.contains("activeProfileRef = schedule?.currentItemId"),
        )
    }
}

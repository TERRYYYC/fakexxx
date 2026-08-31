package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the boundary between Android component lifecycle and durable owner
 * recovery. Service destruction is not evidence that the process-local owner
 * and its mock refresh session have quiesced.
 */
class CleanShutdownLifecycleGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    @Test
    fun serviceDestructionInvalidatesRatherThanMintsCleanOwnerEvidence() {
        val serviceSource = File(
            moduleRoot,
            "src/main/java/name/caiyao/fakegps/integration/v1/EnvironmentControlService.kt",
        ).readText()

        assertFalse(
            "Android Service.onDestroy is not a process-exit or quiescent-owner proof",
            serviceSource.contains("ProviderRuntime.recordCleanShutdown()"),
        )
        assertTrue(
            "a component teardown must invalidate any previously staged clean evidence",
            serviceSource.contains("ProviderRuntime.invalidateCleanShutdownEvidence()"),
        )
    }

    @Test
    fun legacyServiceLifecycleMarkerIsNotAcceptedAfterProtocolUpgrade() {
        val kv = InMemoryDurableKv()
        kv.write("runtime", "clean_shutdown", "1")

        assertFalse(
            "v1 markers were minted by Service.onDestroy and therefore cannot prove owner quiescence",
            ProviderRuntime.CleanShutdownMarker.consume(kv),
        )
    }

    @Test
    fun componentBoundaryRevokesPreviouslyStagedOwnerEvidence() {
        val kv = InMemoryDurableKv()
        ProviderRuntime.CleanShutdownMarker.record(kv)

        ProviderRuntime.CleanShutdownMarker.invalidate(kv)

        assertFalse(
            "a Service teardown after staging must make the next recovery fail closed",
            ProviderRuntime.CleanShutdownMarker.consume(kv),
        )
    }
}

package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.SpoofModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module switch catalogue must enumerate EXACTLY the canonical vocabulary — a module without
 * a UI row is unreachable, a UI row without a payload key is a typo. The catalogue object itself
 * hard-fails in its init on drift; this test keeps the JVM lane honest about it and pins the
 * motion row's existence with its default-off semantics.
 */
class ModuleUiCatalogTest {

    @Test
    fun `catalogue order equals the canonical module vocabulary`() {
        assertEquals(SpoofModules.ALL.toList(), SpoofModuleUiCatalog.entries.map { it.module })
    }

    @Test
    fun `motion is a catalogue row and defaults to off`() {
        val motion = SpoofModuleUiCatalog.entries.first { it.module == SpoofModules.MOTION }
        assertEquals("运动链", motion.label)
        assertTrue(
            "the motion row must state its default-off contract",
            motion.detail.contains("默认关"),
        )
        assertEquals(false, SpoofModules.defaultEnabled(SpoofModules.MOTION))
    }
}

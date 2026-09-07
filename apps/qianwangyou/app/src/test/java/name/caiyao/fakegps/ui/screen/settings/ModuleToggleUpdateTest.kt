package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.SpoofModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the persist-then-publish sequence behind a module switch — the same seam pattern as
 * [RefreshIntervalUpdate]: a toggle that only persists would leave the hook running the previous
 * module set while the screen shows the new one.
 */
class ModuleToggleUpdateTest {

    @Test
    fun `toggle persists before publishing and reports the publish outcome`() {
        val calls = mutableListOf<String>()
        val result = ModuleToggleUpdate.apply(
            module = SpoofModules.WIFI,
            enabled = false,
            persist = { module, enabled -> calls += "persist:$module=$enabled" },
            publish = { calls += "publish"; true },
        )

        assertEquals(false, result.enabled)
        assertEquals(true, result.published)
        // Order matters: publish must read the NEW persisted state, not the previous one.
        assertEquals(listOf("persist:wifi=false", "publish"), calls)
    }

    @Test
    fun `failed publish is surfaced so the screen cannot show an undelivered toggle`() {
        var persisted: Boolean? = null
        val result = ModuleToggleUpdate.apply(
            module = SpoofModules.CELLULAR,
            enabled = true,
            persist = { _, enabled -> persisted = enabled },
            publish = { false },
        )

        // The preference is kept (the user's intent is not discarded); the outcome says not delivered.
        assertEquals(true, persisted)
        assertEquals(false, result.published)
    }

    @Test
    fun `unknown module name is rejected instead of silently persisting a dead pref`() {
        try {
            ModuleToggleUpdate.apply(
                module = "celluler",
                enabled = false,
                persist = { _, _ -> },
                publish = { true },
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("celluler"))
        }
    }
}

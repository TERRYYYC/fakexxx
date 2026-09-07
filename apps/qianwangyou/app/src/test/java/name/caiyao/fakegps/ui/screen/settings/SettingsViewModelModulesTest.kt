package name.caiyao.fakegps.ui.screen.settings

import android.app.Application
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.SpoofModules
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The REAL SettingsViewModel toggle → payload linkage, on the JVM.
 *
 * [SettingsViewModel.setModuleEnabled] must (1) persist the switch, (2) republish the transport
 * payload, and (3) never drop a failed publish outcome. This test drives the view model itself so
 * the shipped wiring — not a re-implementation of it — is what is pinned; the payload is read
 * back through [ConfigPrefsSync.readPublished], the exact bytes the hook consumes.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelModulesTest {

    @Before
    fun resetProcessWideSingletons() {
        // Same isolation rule as ModulesPublishReadbackTest: JVM singletons must not leak a
        // previous test's data directory into this one.
        AppDatabase.closeInstanceForTests()
        SpoofSettings::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun viewModel(): SettingsViewModel =
        SettingsViewModel(RuntimeEnvironment.getApplication() as Application)

    private fun publishedModules(): Map<String, Boolean> {
        val read = ConfigPrefsSync.readPublished(RuntimeEnvironment.getApplication())
        val parsed = PublishedConfig.parse(read.textOrNull)
            ?: throw AssertionError("published payload unreadable after module toggle: $read")
        assertTrue("v5 payload must carry the modules object", parsed.modulesPresent)
        return parsed.modules
    }

    @Test
    fun `toggling a module off republishes the payload with that module false`() {
        val vm = viewModel()

        vm.setModuleEnabled(SpoofModules.WIFI, false)

        val modules = publishedModules()
        assertEquals(false, modules[SpoofModules.WIFI])
        assertEquals("untouched modules stay enabled", true, modules[SpoofModules.CELLULAR])
        assertEquals(SpoofModules.ALL.toSet(), modules.keys.toSet())

        // The persisted switch feeds the flow the section renders.
        assertEquals(false, vm.moduleSwitches.value[SpoofModules.WIFI])
    }

    @Test
    fun `toggling back on republishes the module as enabled`() {
        val vm = viewModel()

        vm.setModuleEnabled(SpoofModules.NETWORK_IP, false)
        assertEquals(false, publishedModules()[SpoofModules.NETWORK_IP])

        vm.setModuleEnabled(SpoofModules.NETWORK_IP, true)
        assertEquals(true, publishedModules()[SpoofModules.NETWORK_IP])
    }

    @Test
    fun `a failed publish is reported as not-delivered instead of silently dropped`() {
        val vm = viewModel()

        vm.setModuleEnabled(SpoofModules.FUSED, false)

        // On Robolectric the cross-process readability leg cannot succeed (no Vector redirect),
        // so sync() fails — and the view model MUST surface that rather than claim delivery.
        val failure = vm.publishFailure.value
        assertTrue("a failed publish must be surfaced to the user", !failure.isNullOrBlank())
        assertTrue(failure!!.contains("未发布"))
    }
}

package name.caiyao.fakegps.hook

import android.content.Context
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.SpoofModules
import name.caiyao.fakegps.data.SpoofSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileReader

/**
 * Publish→readback round trip for the transport schema v5 `modules` object, on the JVM.
 *
 * WRITE side = the real [ConfigPrefsSync.sync] transaction (provider query + Room + world-readable
 * prefs write). READ side = XSharedPreferences-equivalent semantics: the hook reads the shared-prefs
 * XML file directly, so this test parses the same file straight off disk (the file-read cost the
 * Vector shim usually absorbs) instead of trusting an in-process SharedPreferences cache.
 *
 * Note on `sync()`'s return value: Robolectric rejects MODE_WORLD_READABLE (SecurityException), so
 * the cross-process verification leg structurally fails on the JVM and sync() returns false. The
 * payload itself IS durably committed — which is exactly the bytes this test reads back.
 */
@RunWith(RobolectricTestRunner::class)
class ModulesPublishReadbackTest {

    @Before
    fun resetProcessWideSingletons() {
        // SpoofSettings and Room's AppDatabase are JVM singletons bound to the per-test data
        // directory; a stale instance would read/write the PREVIOUS test's environment (and
        // silently poison the publish). Reset both before every test.
        name.caiyao.fakegps.data.db.AppDatabase.closeInstanceForTests()
        SpoofSettings::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    /** What XSharedPreferences.getString(KEY_JSON, null) would return in the target process. */
    private fun hookReadPayload(): String? {
        val prefs = context().getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_PRIVATE)
        val file = prefs.javaClass.getDeclaredField("mFile").apply { isAccessible = true }
            .get(prefs) as? File
            ?: return null
        if (!file.isFile) return null

        val parser = android.util.Xml.newPullParser()
        parser.setInput(FileReader(file))
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.name == "string" &&
                parser.getAttributeValue(null, "name") == ConfigPrefsSync.KEY_JSON
            ) {
                return parser.nextText()
            }
            event = parser.next()
        }
        return null
    }

    private fun publishCurrentSettings() {
        context().getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ConfigPrefsSync.sync(context(), clearIfMissing = true)
    }

    private fun modulesOfPayload(payload: String): Map<String, Boolean> {
        val modules = JSONObject(payload).getJSONObject("modules")
        val result = linkedMapOf<String, Boolean>()
        val keys = modules.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = modules.get(key)
            assertTrue("module value must be a strict boolean: $key=$value", value is Boolean)
            result[key] = value as Boolean
        }
        return result
    }

    @Test
    fun `default publish round-trips every module enabled`() {
        publishCurrentSettings()

        val payload = hookReadPayload()
        assertTrue("payload must exist after publish", payload != null)

        val root = JSONObject(payload)
        assertEquals("writer must emit schemaVersion 5", 5, root.getInt("schemaVersion"))

        val modules = modulesOfPayload(payload!!)
        assertEquals("payload must enumerate exactly the canonical modules",
            SpoofModules.ALL.toSet(), modules.keys.toSet())
        assertTrue("factory state is all-enabled (v4-equivalent behaviour)",
            modules.values.all { it })

        // The app-side readback (verify UI) must agree with the bytes the hook reads.
        val publishedViaApp = PublishedConfig.parse(ConfigPrefsSync.readPublished(context()).textOrNull)!!
        assertEquals(modules, publishedViaApp.modules)
        assertTrue(publishedViaApp.modulesPresent)
    }

    @Test
    fun `toggling a module off publishes false and flips the fingerprint`() {
        publishCurrentSettings()
        val defaultFingerprint = PublishedConfig.fingerprint(hookReadPayload()!!)

        val settings = SpoofSettings.getInstance(context())
        settings.setModuleEnabled(SpoofModules.WIFI, false)
        publishCurrentSettings()

        val modules = modulesOfPayload(hookReadPayload()!!)
        assertEquals(false, modules[SpoofModules.WIFI])
        assertEquals("only the toggled module flips", true, modules[SpoofModules.CELLULAR])
        assertEquals(true, modules[SpoofModules.LOCATION])

        // The hook reloads on fingerprint change; a module flip that kept the fingerprint would
        // leave the target process on the previous module set until an unrelated change landed.
        assertNotEquals(defaultFingerprint, PublishedConfig.fingerprint(hookReadPayload()!!))
    }

    @Test
    fun `toggling two modules off publishes both as false`() {
        val settings = SpoofSettings.getInstance(context())
        settings.setModuleEnabled(SpoofModules.WIFI, false)
        settings.setModuleEnabled(SpoofModules.NETWORK_IP, false)
        publishCurrentSettings()

        val modules = modulesOfPayload(hookReadPayload()!!)
        assertEquals(false, modules[SpoofModules.WIFI])
        assertEquals(false, modules[SpoofModules.NETWORK_IP])
        assertEquals(true, modules[SpoofModules.PHONE_STATE])
    }

    @Test
    fun `persisted module switches survive a fresh settings instance`() {
        SpoofSettings.getInstance(context()).setModuleEnabled(SpoofModules.FUSED, false)

        // SpoofSettings is a per-process singleton; read through the writer's own read path.
        val states = SpoofSettings.getInstance(context()).readModulesEnabled()
        assertEquals(false, states[SpoofModules.FUSED])
        assertEquals(true, states[SpoofModules.WIFI])
    }

    @Test
    fun `v4 payload without modules keeps every hook group enabled`() {
        // A payload left by a pre-v5 app build: no `modules` key at all. The hook must treat it
        // exactly like all-enabled (backward compatibility), not as "disable everything".
        val v4 = """{"schemaVersion":4,"mode":"always_on","fields":{},"unavailable":[]}"""
        context().getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(ConfigPrefsSync.KEY_JSON, v4).commit()

        val root = JSONObject(hookReadPayload()!!)
        val disabled = ModuleGate.fromPayload(root.opt("modules"))

        assertTrue("v4 payload must disable nothing", disabled.isEmpty())
        for (module in SpoofModules.ALL) {
            for (group in SpoofModules.groupsOf(module)) {
                assertTrue(group, ModuleGate.shouldRegister(group, disabled))
            }
        }

        val parsed = PublishedConfig.parse(v4)!!
        assertEquals(false, parsed.modulesPresent)
    }

    @Test
    fun `hook-side gate agrees with the published module map`() {
        SpoofSettings.getInstance(context()).setModuleEnabled(SpoofModules.PHONE_STATE, false)
        publishCurrentSettings()

        val disabled = ModuleGate.fromPayload(JSONObject(hookReadPayload()!!).get("modules"))
        assertEquals(setOf(SpoofModules.PHONE_STATE), disabled)
        // phoneState owns BOTH listener groups — both must be skipped…
        assertFalse(ModuleGate.shouldRegister("PhoneStateListener", disabled))
        assertFalse(ModuleGate.shouldRegister("TelephonyCallback", disabled))
        // …while a group of another module stays registered.
        assertTrue(ModuleGate.shouldRegister("FusedLocation", disabled))
        assertTrue(ModuleGate.shouldRegister("WiFi", disabled))
    }
}

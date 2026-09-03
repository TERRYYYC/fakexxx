package name.caiyao.fakegps.config

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID

/** Negative evidence on a disposable stock emulator, never a framework publication success. */
@RunWith(AndroidJUnit4::class)
class ConfigTransportCacheInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test
    fun worldReadableRetryReturnsTheCachedPrivateInstanceWithoutProvingTransport() {
        requireDisposableStockTestTarget()
        val name = "transport-cache-${UUID.randomUUID()}"
        try {
            assertThrows("A fresh name must reject WORLD_READABLE on this stock emulator",
                SecurityException::class.java) {
                target.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
            }
            val fallback = target.getSharedPreferences(name, Context.MODE_PRIVATE)
            val marker = "private-only-${UUID.randomUUID()}"
            assertTrue(fallback.edit().putString("marker", marker).commit())

            // Real ContextImpl cache behavior: no cache reflection or mocked preferences.
            val retry = target.getSharedPreferences(name, Context.MODE_WORLD_READABLE)
            assertSame("Retry bypasses checkMode and returns the existing private instance", fallback, retry)
            assertEquals(marker, retry.getString("marker", null))
            assertAppPrivateBacking(retry)
            Log.i(TAG, "firstWorld=SecurityException fallbackCommit=true retrySamePrivateInstance=true")
        } finally {
            clearOwnedPreferences(name)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun realPublisherRejectsFreshAndCachedPrivateTransportAndKeepsItsDurableFailure() {
        requireDisposableStockTestTarget()
        // Remap only preference NAMES so this test cannot touch the publisher/identity suite's
        // stores. The preferences, ContextImpl cache, file and private ContentProvider are real.
        val fixture = OwnedPreferencesContext(target, "publisher-cache-${UUID.randomUUID()}")
        val dao = AppDatabase.getInstance(target).profileDao()
        val markerA = "private-payload-a-${UUID.randomUUID()}"
        val profileId = runBlocking { dao.insert(ProfileEntity(addname = markerA)) }
        try {
            val state = fixture.getSharedPreferences("publish_state", Context.MODE_PRIVATE)
            assertTrue(state.edit().putBoolean("state_initialized", true)
                .putLong("active_profile_id", profileId)
                .putLong(ConfigPrefsSync.KEY_PUBLISHED_AT, 1234L)
                .putBoolean(ConfigPrefsSync.KEY_PUBLISH_FAILED, false).commit())
            assertThrows(SecurityException::class.java) {
                fixture.getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_WORLD_READABLE)
            }

            assertFalse(ConfigPrefsSync.sync(fixture, profileId))
            val fallback = fixture.getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_PRIVATE)
            assertPayloadMarker(fixture, markerA)
            assertAppPrivateBacking(fallback)
            assertFailureState(fixture, state, profileId)

            val retry = fixture.getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_WORLD_READABLE)
            assertSame(fallback, retry)
            val markerB = "private-payload-b-${UUID.randomUUID()}"
            runBlocking { dao.update(checkNotNull(dao.getById(profileId)).copy(addname = markerB)) }
            assertFalse("A cached successful mode call is not cross-process publication",
                ConfigPrefsSync.sync(fixture, profileId))
            assertPayloadMarker(fixture, markerB)
            assertAppPrivateBacking(retry)
            assertFailureState(fixture, state, profileId)
            Log.i(TAG, "publisherFresh=false publisherCached=false changedLocalPayload=true durableFailure=true")
        } finally {
            try {
                runBlocking { dao.deleteById(profileId) }
            } finally {
                fixture.ownedNames.forEach(::clearOwnedPreferences)
            }
        }
    }

    private fun requireDisposableStockTestTarget() {
        assertEquals("Do not run on a physical device", "ranchu", Build.HARDWARE)
        assertTrue("Use the stock API 24+ emulator gate", Build.VERSION.SDK_INT >= 24)
        assertTrue("Never run against the production package",
            target.packageName in setOf("name.caiyao.fakegps.bench", "name.caiyao.fakegps.codexbench"))
    }

    private fun assertPayloadMarker(context: Context, marker: String) {
        val read = ConfigPrefsSync.readPublished(context)
        assertTrue("Local payload must have actually committed", read is PayloadRead.Raw)
        assertEquals(marker, JSONObject((read as PayloadRead.Raw).text)
            .getJSONObject("fields").getString("addname"))
    }

    private fun assertFailureState(context: Context, state: SharedPreferences, active: Long) {
        assertTrue(ConfigPrefsSync.hasPublicationFailure(context))
        assertNull(ConfigPrefsSync.readPublishedAt(context))
        assertEquals("Keep last-good active pointer on transport failure",
            active, state.getLong("active_profile_id", -1L))
        // Check committed bytes as well as the in-memory preferences object.
        val diskValues = linkedMapOf<String, String?>()
        backingFile(state).reader().use { reader ->
            val parser = Xml.newPullParser()
            parser.setInput(reader)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name in setOf("long", "boolean")) {
                    diskValues[checkNotNull(parser.getAttributeValue(null, "name"))] =
                        parser.getAttributeValue(null, "value")
                }
            }
        }
        assertEquals("true", diskValues[ConfigPrefsSync.KEY_PUBLISH_FAILED])
        assertFalse(diskValues.containsKey(ConfigPrefsSync.KEY_PUBLISHED_AT))
        assertEquals(active.toString(), diskValues["active_profile_id"])
    }

    private fun assertAppPrivateBacking(prefs: SharedPreferences) {
        val file = backingFile(prefs)
        assertTrue(file.isFile)
        assertTrue(file.canonicalPath.startsWith("${target.dataDir.canonicalPath}/"))
    }

    /** File reflection only inspects the real instance, exactly as production does. */
    private fun backingFile(prefs: SharedPreferences): File =
        prefs.javaClass.getDeclaredField("mFile").apply { isAccessible = true }.get(prefs) as File

    private fun clearOwnedPreferences(name: String) {
        assertTrue(target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        assertTrue("Delete only this unique fixture's preferences", target.deleteSharedPreferences(name))
    }

    private class OwnedPreferencesContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        val ownedNames = linkedSetOf<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val ownedName = "$prefix-$name"
            ownedNames.add(ownedName)
            return super.getSharedPreferences(ownedName, mode)
        }
    }

    companion object { private const val TAG = "ConfigTransportCacheTest" }
}

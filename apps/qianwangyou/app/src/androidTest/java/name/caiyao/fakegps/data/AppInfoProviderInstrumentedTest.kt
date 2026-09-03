package name.caiyao.fakegps.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import name.caiyao.fakegps.config.ConfigPrefsSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInfoProviderInstrumentedTest {

    @Test
    fun transientMissingActiveRowKeepsLastGoodPayloadAndSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_PRIVATE)
        // sync migrates the legacy active pointer into a separate durable outcome store.
        // Isolate BOTH stores or Long.MAX_VALUE leaks into the next real-service test.
        val publishState = context.getSharedPreferences("publish_state", Context.MODE_PRIVATE)
        val previousTransport = prefs.all
        val previousOutcome = publishState.all
        val missingId = Long.MAX_VALUE
        try {
            check(publishState.edit().clear().commit())
            check(prefs.edit().clear()
                .putString(ConfigPrefsSync.KEY_JSON, "last-good")
                .putLong("active_profile_id", missingId)
                .commit())
            assertFalse(ConfigPrefsSync.sync(context))
            assertEquals("last-good", prefs.getString(ConfigPrefsSync.KEY_JSON, null))
            assertEquals(missingId, prefs.getLong("active_profile_id", 0L))
        } finally {
            prefs.restore(previousTransport)
            publishState.restore(previousOutcome)
        }
    }

    @Test
    fun settingsRouteMatchesTheInstalledVariantAuthority() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.data.AppInfoProvider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)

        assertNotNull(provider)
        assertEquals(authority, provider!!.authority)
        context.contentResolver.query(
            Uri.parse("content://$authority/settings"),
            null,
            null,
            null,
            null,
        ).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
        }
    }

    private fun SharedPreferences.restore(snapshot: Map<String, *>) {
        val editor = edit().clear()
        snapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                else -> error("Unexpected preference value for $key")
            }
        }
        check(editor.commit())
    }
}

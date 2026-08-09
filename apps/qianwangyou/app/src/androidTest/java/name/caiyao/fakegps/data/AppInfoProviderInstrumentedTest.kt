package name.caiyao.fakegps.data

import android.content.Context
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
        val missingId = Long.MAX_VALUE
        prefs.edit()
            .putString(ConfigPrefsSync.KEY_JSON, "last-good")
            .putLong("active_profile_id", missingId)
            .commit()

        try {
            assertFalse(ConfigPrefsSync.sync(context))
            assertEquals("last-good", prefs.getString(ConfigPrefsSync.KEY_JSON, null))
            assertEquals(missingId, prefs.getLong("active_profile_id", 0L))
        } finally {
            prefs.edit().clear().commit()
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
}

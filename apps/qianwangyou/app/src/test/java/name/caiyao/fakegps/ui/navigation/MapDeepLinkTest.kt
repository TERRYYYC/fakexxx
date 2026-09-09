package name.caiyao.fakegps.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T11c: the `fakexxx-map://pending` deep link — manifest registration on
 * ComposeActivity + the pure intent→(screen, anchor) routing.
 *
 * The deep link is deliberately NAVIGATION ONLY: it lands the operator on the
 * settings page's pairing area (the 待批准的 Auto list), scrolls to it and
 * highlights it. It carries no data, executes no action, and grants nothing —
 * approval still goes through the explicit per-candidate confirm dialog.
 *
 * # deep link 仅导航：落到设置页配对区锚点；不携数据不批任何东西
 */
@RunWith(RobolectricTestRunner::class)
class MapDeepLinkTest {

    private fun viewIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test
    fun `manifest registers fakexxx-map pending VIEW filter on ComposeActivity`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.queryIntentActivities(
            viewIntent("fakexxx-map://pending"), 0
        )
        assertTrue(
            "fakexxx-map://pending must resolve to ComposeActivity",
            resolved.any { it.activityInfo.name == "name.caiyao.fakegps.ui.ComposeActivity" }
        )
    }

    @Test
    fun `deep link intent routes to Settings with the pending-pairing anchor`() {
        val route = MapDeepLink.route(viewIntent("fakexxx-map://pending"))
        assertEquals(Screen.Settings, route?.destination)
        assertEquals(true, route?.highlightPendingPairing)
    }

    @Test
    fun `foreign or malformed uris never route`() {
        assertNull("launcher intent has no data", MapDeepLink.route(Intent(Intent.ACTION_MAIN)))
        assertNull(
            "foreign scheme (Auto's) must not route here",
            MapDeepLink.route(viewIntent("fakexxx-auto://providers"))
        )
        assertNull(
            "registered scheme with unregistered host must not route",
            MapDeepLink.route(viewIntent("fakexxx-map://providers"))
        )
        assertNull(
            "http-ish data must not route",
            MapDeepLink.route(viewIntent("https://example.org/pending"))
        )
    }
}

package com.example.cellrebelauto.ui

import android.content.Intent
import android.net.Uri
import com.example.cellrebelauto.automation.ProviderPrincipal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T11c: the `fakexxx-auto://providers` deep link — manifest registration + routing +
 * the outgoing navigation-only intent into the paired QWY's pending section.
 *
 * The deep link is deliberately NAVIGATION ONLY: it carries no extras, no action
 * payload, and requests no permission. All it can do is move the operator's eyes
 * to a screen; every privileged action stays behind its own gate.
 *
 * # 跨 app deep link：仅导航、不带数据、不执行动作
 */
@RunWith(RobolectricTestRunner::class)
class CrossAppLinkingTest {

    private fun viewIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    @Test
    fun `manifest registers fakexxx-auto providers VIEW filter on MainActivity`() {
        // Robolectric's shadow PackageManager here resolves NO manifest activities at all
        // (even the pre-existing LAUNCHER filter matches zero), so the registration is
        // pinned against the source manifest — the input the merge mechanically carries
        // into every variant.
        val manifest = moduleManifest().readText()
        val mainActivityBlock = Regex(
            "<activity[^>]*\\.ui\\.MainActivity.*?</activity>",
            RegexOption.DOT_MATCHES_ALL
        ).find(manifest)?.value
            ?: error("MainActivity activity element not found in app/src/main/AndroidManifest.xml")
        assertTrue(
            "MainActivity must declare the VIEW action for the deep link",
            mainActivityBlock.contains("android.intent.action.VIEW")
        )
        assertTrue(
            "VIEW filter must be browser/addressable (DEFAULT + BROWSABLE)",
            mainActivityBlock.contains("android.intent.category.BROWSABLE")
        )
        assertTrue(
            "VIEW filter must pin scheme=fakexxx-auto host=providers (nothing broader)",
            Regex("android:scheme=\"fakexxx-auto\"\\s+android:host=\"providers\"")
                .containsMatchIn(mainActivityBlock)
        )
    }

    private fun moduleManifest(): java.io.File {
        var dir = java.io.File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = java.io.File(dir, "src/main/AndroidManifest.xml")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate app/src/main/AndroidManifest.xml from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `deep link intent routes to PROVIDERS screen`() {
        assertEquals(
            Screen.PROVIDERS,
            CrossAppDeepLinks.routeToScreen(viewIntent("fakexxx-auto://providers").data)
        )
    }

    @Test
    fun `foreign or malformed uris never route`() {
        assertNull("launcher intent has no data", CrossAppDeepLinks.routeToScreen(null))
        assertNull(
            "foreign scheme (the peer's) must not route here",
            CrossAppDeepLinks.routeToScreen(viewIntent("fakexxx-map://pending").data)
        )
        assertNull(
            "registered scheme with unregistered host must not route",
            CrossAppDeepLinks.routeToScreen(viewIntent("fakexxx-auto://pending").data)
        )
        assertNull(
            "http-ish data must not route",
            CrossAppDeepLinks.routeToScreen(viewIntent("https://example.org/providers").data)
        )
    }

    @Test
    fun `outgoing peer-pending intent is navigation only into the paired provider`() {
        val intent = CrossAppDeepLinks.peerPendingIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("fakexxx-map://pending", intent.data.toString())
        assertEquals(
            "the jump targets THIS build's paired QWY principal — no scheme hijack",
            ProviderPrincipal.selected,
            intent.`package`
        )
        assertNull("deep link carries NO extras (data-less navigation)", intent.extras)
        assertTrue(
            "deep link requests no categories/permissions beyond VIEW",
            intent.categories?.isEmpty() ?: true
        )
    }
}

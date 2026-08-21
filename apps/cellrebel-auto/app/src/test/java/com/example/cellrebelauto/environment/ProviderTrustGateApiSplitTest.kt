package com.example.cellrebelauto.environment

import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * R44 (DSF review P2-2): the ProviderTrustGate signer resolution's API-split coverage — the
 * legacy GET_SIGNATURES branch (API 26/27) was never executed by any test. Robolectric drives
 * both SDK levels against the same shadow-installed signed package.
 *
 * # API-split 签名解析覆盖：26/27 legacy 分支与 28+ 分支同库覆盖
 */
@RunWith(RobolectricTestRunner::class)
class ProviderTrustGateApiSplitTest {

    private val pkg = "name.caiyao.fakegps.splitprobe"
    private val signature = Signature("308201a3".padEnd(2048, 'a'))

    private fun installSigned() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pi = android.content.pm.PackageInfo().apply {
            packageName = pkg
            signatures = arrayOf(signature)
        }
        Shadows.shadowOf(ctx.packageManager).installPackage(pi)
    }

    @Test
    @Config(sdk = [26])
    fun `API 26 resolves the signer via the legacy GET_SIGNATURES branch`() {
        installSigned()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val digest = ProviderTrustGate.packageManagerSignerDigest(ctx.packageManager, pkg)
        // The digest is the sha256 of the installed certificate — non-null on the legacy branch.
        assertEquals(
            "the legacy (26/27) branch resolves the SAME digest format",
            "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray()).joinToString("") { "%02x".format(it) },
            digest
        )
    }

    @Test
    fun `the modern SigningInfo branch resolves the signer (default SDK, 28+)`() {
        installSigned()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val digest = ProviderTrustGate.packageManagerSignerDigest(ctx.packageManager, pkg)
        assertEquals(
            "the modern (28+) branch resolves the SAME digest",
            "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray()).joinToString("") { "%02x".format(it) },
            digest
        )
    }

    @Test
    @Config(sdk = [26])
    fun `an uninstalled package fail-closes on the legacy branch`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(ProviderTrustGate.packageManagerSignerDigest(ctx.packageManager, "no.such.pkg"))
    }

    @Test
    fun `an uninstalled package fail-closes on the modern branch (default SDK)`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(ProviderTrustGate.packageManagerSignerDigest(ctx.packageManager, "no.such.pkg"))
    }
}

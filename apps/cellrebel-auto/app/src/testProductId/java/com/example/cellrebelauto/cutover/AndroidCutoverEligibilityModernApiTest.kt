package com.example.cellrebelauto.cutover

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class AndroidCutoverEligibilityModernApiTest {

    @Test
    fun singleSignerRotationHistoryAndExactVersionAreEligible() = runTest {
        installPackage(
            AndroidCutoverEligibilityPort.LEGACY_PACKAGE,
            current = arrayOf(Signature("aabb"))
        )
        installPackage(
            AndroidCutoverEligibilityPort.PRODUCT_PACKAGE,
            current = arrayOf(Signature("ccdd")),
            history = arrayOf(Signature("aabb"), Signature("ccdd"))
        )

        assertEquals(CutoverEligibility.ELIGIBLE, port().observe())
    }

    @Test
    fun versionMismatchAndMultipleCurrentSignersAreIneligible() = runTest {
        installPackage(
            AndroidCutoverEligibilityPort.LEGACY_PACKAGE,
            current = arrayOf(Signature("aabb"))
        )
        installPackage(
            AndroidCutoverEligibilityPort.PRODUCT_PACKAGE,
            current = arrayOf(Signature("aabb")),
            version = 8
        )
        assertEquals(CutoverEligibility.INELIGIBLE, port().observe())

        installPackage(
            AndroidCutoverEligibilityPort.PRODUCT_PACKAGE,
            current = arrayOf(Signature("aabb"), Signature("ccdd")),
            history = arrayOf(Signature("aabb"), Signature("ccdd"))
        )
        assertEquals(CutoverEligibility.INELIGIBLE, port().observe())
    }

    private fun port() = AndroidCutoverEligibilityPort(RuntimeEnvironment.getApplication())

    private fun installPackage(
        packageName: String,
        current: Array<Signature>,
        history: Array<Signature> = current,
        version: Long = 7
    ) {
        val signingInfo = SigningInfo()
        shadowOf(signingInfo).setSignatures(current)
        shadowOf(signingInfo).setPastSigningCertificates(history)
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                longVersionCode = version
                this.signingInfo = signingInfo
            }
        )
    }
}

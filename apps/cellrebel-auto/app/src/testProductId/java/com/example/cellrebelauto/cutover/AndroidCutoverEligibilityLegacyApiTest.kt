package com.example.cellrebelauto.cutover

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [26, 27])
class AndroidCutoverEligibilityLegacyApiTest {

    @Test
    fun sameSingleCurrentSignerIsEligible() = runTest {
        installPackages(
            legacy = arrayOf(Signature("aabb")),
            product = arrayOf(Signature("aabb"))
        )

        assertEquals(CutoverEligibility.ELIGIBLE, port().observe())
    }

    @Test
    fun overlappingCurrentMultiSignerSetsAreIneligible() = runTest {
        installPackages(
            legacy = arrayOf(Signature("aabb"), Signature("ccdd")),
            product = arrayOf(Signature("aabb"), Signature("eeff"))
        )

        assertEquals(CutoverEligibility.INELIGIBLE, port().observe())
    }

    @Test
    fun missingCurrentSignerIsIneligible() = runTest {
        installPackages(
            legacy = emptyArray(),
            product = arrayOf(Signature("aabb"))
        )

        assertEquals(CutoverEligibility.INELIGIBLE, port().observe())
    }

    private fun port() = AndroidCutoverEligibilityPort(RuntimeEnvironment.getApplication())

    @Suppress("DEPRECATION")
    private fun installPackages(legacy: Array<Signature>, product: Array<Signature>) {
        val packageManager = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        listOf(
            AndroidCutoverEligibilityPort.LEGACY_PACKAGE to legacy,
            AndroidCutoverEligibilityPort.PRODUCT_PACKAGE to product
        ).forEach { (packageName, signatures) ->
            packageManager.installPackage(
                PackageInfo().apply {
                    this.packageName = packageName
                    versionCode = 7
                    this.signatures = signatures
                }
            )
        }
    }
}

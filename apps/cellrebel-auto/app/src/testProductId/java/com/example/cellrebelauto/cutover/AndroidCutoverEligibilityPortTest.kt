package com.example.cellrebelauto.cutover

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCutoverEligibilityPortTest {

    @Test
    fun matchingVersionAndSignerLineageIsEligible() = runTest {
        val port = AndroidCutoverEligibilityPort(
            packageState = { packageName ->
                when (packageName) {
                    AndroidCutoverEligibilityPort.LEGACY_PACKAGE ->
                        CutoverInstalledPackage(7, setOf("old", "shared"))
                    AndroidCutoverEligibilityPort.PRODUCT_PACKAGE ->
                        CutoverInstalledPackage(7, setOf("shared", "new"))
                    else -> null
                }
            }
        )

        assertEquals(CutoverEligibility.ELIGIBLE, port.observe())
    }

    @Test
    fun missingLegacyVersionMismatchAndUnrelatedSignerFailClosed() = runTest {
        suspend fun verdict(
            legacy: CutoverInstalledPackage?,
            product: CutoverInstalledPackage
        ) = AndroidCutoverEligibilityPort { packageName ->
            if (packageName == AndroidCutoverEligibilityPort.LEGACY_PACKAGE) legacy else product
        }.observe()

        val product = CutoverInstalledPackage(7, setOf("product"))
        assertEquals(CutoverEligibility.INELIGIBLE, verdict(null, product))
        assertEquals(
            CutoverEligibility.INELIGIBLE,
            verdict(CutoverInstalledPackage(6, setOf("product")), product)
        )
        assertEquals(
            CutoverEligibility.INELIGIBLE,
            verdict(CutoverInstalledPackage(7, setOf("legacy")), product)
        )
        assertEquals(
            CutoverEligibility.INELIGIBLE,
            verdict(CutoverInstalledPackage(7, emptySet()), product)
        )
    }

    @Test
    fun unreadablePackageStateIsIndeterminate() = runTest {
        val port = AndroidCutoverEligibilityPort { error("synthetic package manager failure") }

        assertEquals(CutoverEligibility.INDETERMINATE, port.observe())
    }
}

package com.example.cellrebelauto.cutover

import com.example.cellrebelauto.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CutoverIdentityVariantTest {

    @Test
    fun buildCarriesExactlyOneFrozenCutoverIdentity() {
        when (BuildConfig.CUTOVER_IDENTITY) {
            "legacyId" -> assertEquals("com.example.cellrebelauto", BuildConfig.APPLICATION_ID)
            "productId" -> assertEquals("come.xx.fakeaauto", BuildConfig.APPLICATION_ID)
            else -> error("Unknown cutover identity: ${BuildConfig.CUTOVER_IDENTITY}")
        }
    }
}

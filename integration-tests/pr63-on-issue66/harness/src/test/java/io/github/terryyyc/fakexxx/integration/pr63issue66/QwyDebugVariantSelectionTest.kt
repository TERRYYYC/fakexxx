package io.github.terryyyc.fakexxx.integration.pr63issue66

import name.caiyao.fakegps.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class QwyDebugVariantSelectionTest {

    @Test
    fun `host consumes the explicit QWY debug bench artifact`() {
        assertEquals("debug", BuildConfig.BUILD_TYPE)
        assertEquals("name.caiyao.fakegps.bench", BuildConfig.APPLICATION_ID)
    }
}

package io.github.terryyyc.fakexxx.integration.pr63issue66

import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoReleaseVariantSelectionTest {

    @Test
    fun `host consumes real Auto release variant whose current target is production`() {
        assertEquals("com.example.cellrebelauto", AutoIntegrationBridge.autoApplicationId())
        assertEquals(
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
            AutoIntegrationBridge.selectedProviderTarget(),
        )
    }
}

package io.github.terryyyc.fakexxx.integration.pr63issue66

import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import org.junit.Assert.assertEquals
import org.junit.Test

class ContractSingletonTest {
    @Test
    fun `unit runtime contains exactly one frozen AIDL contract definition`() {
        val loader = requireNotNull(IEnvironmentControlV1::class.java.classLoader)
        val resources = loader
            .getResources("io/github/terryyyc/fakexxx/contract/v1/IEnvironmentControlV1.class")
            .toList()

        assertEquals("one class identity must serve both real apps", 1, resources.size)
    }
}

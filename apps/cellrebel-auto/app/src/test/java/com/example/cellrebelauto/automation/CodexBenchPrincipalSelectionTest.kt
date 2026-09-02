package com.example.cellrebelauto.automation

import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Build identity is closed: a new bench must never silently target an existing installation. */
class CodexBenchPrincipalSelectionTest {
    private val codexProvider = "name.caiyao.fakegps.codexbench"

    @Test
    fun `ordinary debug and release keep their frozen targets`() {
        assertEquals(ContractV1.PROVIDER_APPLICATION_ID_BENCH, ProviderPrincipal.resolve(true, false))
        assertEquals(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, ProviderPrincipal.resolve(false, false))
    }

    @Test
    fun `codex bench selects only its independently installed provider`() {
        assertEquals(codexProvider, ProviderPrincipal.resolve(true, true))
        assertEquals(listOf(codexProvider), ProviderPrincipal.knownForBuild(true, true))
    }

    @Test
    fun `codex bench cannot recover or pair an existing application identity`() {
        val known = ProviderPrincipal.knownForBuild(true, true)
        assertFalse(ContractV1.PROVIDER_APPLICATION_ID_BENCH in known)
        assertFalse(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION in known)
        assertFalse("arbitrary.provider" in known)
    }

    @Test
    fun `ordinary variants do not acquire trust in the codex identity`() {
        assertEquals(
            listOf(ContractV1.PROVIDER_APPLICATION_ID_BENCH, ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION),
            ProviderPrincipal.knownForBuild(true, false),
        )
        assertEquals(
            listOf(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, ContractV1.PROVIDER_APPLICATION_ID_BENCH),
            ProviderPrincipal.knownForBuild(false, false),
        )
    }

    @Test
    fun `codex identity is forbidden for a non debug build`() {
        assertThrows(IllegalArgumentException::class.java) { ProviderPrincipal.resolve(false, true) }
        assertThrows(IllegalArgumentException::class.java) { ProviderPrincipal.knownForBuild(false, true) }
    }
}

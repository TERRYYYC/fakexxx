package com.example.cellrebelauto.ui

import com.example.cellrebelauto.model.plan.ProviderPairingRecord
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R45 (Sol R45 P2): the provider-entry projection oracle — the CURRENT signer is resolved for
 * EVERY known provider appId on EVERY refresh. Killing mutations:
 *  - the applicationId-level skip restored ⇒ the ROTATION case shows no pending principal (FAIL);
 *  - revoked rows surfaced as pending ⇒ the REVOKED case shows a candidate (FAIL).
 *
 * # provider 投影 oracle：signer 轮转产生新 pending principal；revoked 不复活
 */
class ProviderEntriesProjectionTest {

    private val prod = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
    private val bench = ContractV1.PROVIDER_APPLICATION_ID_BENCH

    private fun row(appId: String, signer: String, revokedAt: Long? = null) = ProviderPairingRecord(
        applicationId = appId, currentSignerDigest = signer,
        approvedAt = 1000L, revokedAt = revokedAt, approvedVersionCode = 1
    )

    @Test
    fun `the CURRENT signer is the approved principal - no pending candidate`() {
        val entries = MainViewModel.computeProviderEntries(listOf(row(prod, "sha256:a"))) { appId ->
            if (appId == prod) "sha256:a" else null
        }
        assertEquals(
            listOf(Triple(prod, "sha256:a", true)),
            entries.map { Triple(it.applicationId, it.signerDigest, it.isApproved) }
        )
    }

    @Test
    fun `a SIGNER ROTATION on an already-approved appId surfaces a NEW pending principal (R45 P2)`() {
        // The operator approved sha256:a; the provider has since rotated to sha256:b.
        val entries = MainViewModel.computeProviderEntries(listOf(row(prod, "sha256:a"))) { appId ->
            if (appId == prod) "sha256:b" else null
        }
        val pending = entries.filter { !it.isApproved }
        assertEquals(
            "the rotated signer appears as a pending candidate — signer change IS a new provider (§6.5.4); killing mutation: appId-level skip restored ⇒ no pending (FAIL)",
            1, pending.size
        )
        assertEquals("sha256:b", pending[0].signerDigest)
        assertEquals(
            "the historical principal row is still listed (audit truth), the CURRENT signer is not it",
            1, entries.count { it.isApproved }
        )
    }

    @Test
    fun `a REVOKED principal is not active - its rediscovered signer re-enters via the approval UI as a NEW decision`() {
        // The only row is revoked; the same signer is still installed. §6.5.4: re-approval of the
        // same (appId, signer) writes a NEW approvedAt and clears revokedAt — a NEW operator trust
        // decision through the approval UI. The projection must therefore show ZERO approved
        // entries and ONE pending candidate via discovery (never a silent revival of the row).
        val entries = MainViewModel.computeProviderEntries(listOf(row(prod, "sha256:a", revokedAt = 2000L))) { appId ->
            if (appId == prod) "sha256:a" else null
        }
        assertEquals("the revoked principal is NOT active", 0, entries.count { it.isApproved })
        assertEquals(
            "the rediscovered signer appears as a pending candidate — the re-approval is a visible operator decision",
            1, entries.count { !it.isApproved }
        )
    }

    @Test
    fun `an unresolvable or uninstalled provider produces no entry`() {
        val entries = MainViewModel.computeProviderEntries(emptyList()) { null }
        assertTrue("nothing installed ⇒ nothing shown", entries.isEmpty())
    }

    @Test
    fun `the bench appId is discovered independently`() {
        val entries = MainViewModel.computeProviderEntries(listOf(row(prod, "sha256:a"))) { appId ->
            when (appId) {
                prod -> "sha256:a"
                bench -> "sha256:bench"
                else -> null
            }
        }
        assertEquals(
            "production approved + bench pending — the two appIds pair independently",
            listOf(prod to true, bench to false),
            entries.map { it.applicationId to it.isApproved }
        )
    }

    @Test
    fun `currentPrincipalActive binds the CURRENT measured principal - rotation deactivates (R46 P2)`() {
        // Approved row sha256:a, but the CURRENT signer measured sha256:b (rotation):
        val rotated = MainViewModel.computeProviderEntries(listOf(row(prod, "sha256:a"))) { appId ->
            if (appId == prod) "sha256:b" else null
        }
        org.junit.Assert.assertFalse(
            "a rotated-away principal is NOT the active one (killing mutation: any{isApproved} restored ⇒ true)",
            MainViewModel.currentPrincipalActive(rotated, prod)
        )
        // The current signer IS the approved principal:
        val stable = MainViewModel.computeProviderEntries(listOf(row(prod, "sha256:a"))) { appId ->
            if (appId == prod) "sha256:a" else null
        }
        org.junit.Assert.assertTrue(MainViewModel.currentPrincipalActive(stable, prod))
        // Nothing installed at all ⇒ no active current principal:
        org.junit.Assert.assertFalse(MainViewModel.currentPrincipalActive(emptyList(), prod))
    }
}

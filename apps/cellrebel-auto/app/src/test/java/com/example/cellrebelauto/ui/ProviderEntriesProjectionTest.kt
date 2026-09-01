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

    @Test
    fun `debug run status evaluates the same bench principal used by Binder`() {
        val benchEntries = MainViewModel.computeProviderEntries(
            listOf(row(bench, "sha256:bench")),
        ) { appId -> if (appId == bench) "sha256:bench" else null }

        org.junit.Assert.assertTrue(
            "debug Binder targets .bench, so the Run status must not inspect production instead",
            MainViewModel.currentRunTargetActive(benchEntries, bench),
        )
        org.junit.Assert.assertFalse(
            MainViewModel.currentRunTargetActive(benchEntries, prod),
        )
    }

    @Test
    fun `run status consumes the durable provider identity rather than a build flag`() {
        val productionEntries = MainViewModel.computeProviderEntries(
            listOf(row(prod, "sha256:prod")),
        ) { appId -> if (appId == prod) "sha256:prod" else null }

        org.junit.Assert.assertTrue(
            "a restored production attempt remains active even in a debug build",
            MainViewModel.currentRunTargetActive(productionEntries, prod),
        )
        org.junit.Assert.assertFalse(
            MainViewModel.currentRunTargetActive(productionEntries, bench),
        )
        org.junit.Assert.assertFalse(
            "legacy null identity must not fall back to the current build",
            MainViewModel.currentRunTargetActive(productionEntries, null),
        )
    }

    @Test
    fun `run status joins the durable signer owner when old and rotated signers are both approved`() {
        val signerA = "sha256:70506b15b8a45e1147ade558c1869420b8cd4c65e9590647e09b5c816b58975c"
        val signerB = "sha256:4412f6d72f33cc7f8e643f7624d4ec743f5389185fc952366da015a6ab6c8a63"
        val entries = MainViewModel.computeProviderEntries(
            listOf(row(prod, signerA), row(prod, signerB)),
        ) { appId -> if (appId == prod) signerB else null }

        org.junit.Assert.assertFalse(
            "the current B approval must not make an A-owned crashed run active",
            MainViewModel.currentRunTargetActive(entries, prod, signerA),
        )
        org.junit.Assert.assertTrue(
            "only the exact current (P,B) principal is active",
            MainViewModel.currentRunTargetActive(entries, prod, signerB),
        )
    }

    @Test
    fun `legacy crashed signer owner cannot borrow app-level approval before Service guard`() {
        val entries = MainViewModel.computeProviderEntries(
            listOf(row(prod, "sha256:current")),
        ) { appId -> if (appId == prod) "sha256:current" else null }

        org.junit.Assert.assertFalse(
            "a crashed attempt with no signer owner is unknown even when P has an active pairing",
            MainViewModel.currentCrashedOwnerActive(entries, prod, null),
        )
    }

    @Test
    fun `run status without a crashed attempt uses the durable plan and never the build default`() {
        assertEquals(
            "a production plan remains the UI target in this debug bench test process",
            prod,
            MainViewModel.durableRunTargetApplicationId(
                hasCrashedAttempt = false,
                crashedProviderApplicationId = null,
                planProviderApplicationId = prod,
            ),
        )
        assertEquals(
            "without a plan or attempt the status is unknown, not the build target",
            null,
            MainViewModel.durableRunTargetApplicationId(false, null, null),
        )
        assertEquals(
            "a legacy crashed owner stays unknown and cannot borrow its plan principal",
            null,
            MainViewModel.durableRunTargetApplicationId(true, null, prod),
        )
    }
}

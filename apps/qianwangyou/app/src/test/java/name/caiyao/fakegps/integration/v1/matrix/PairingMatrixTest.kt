package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import name.caiyao.fakegps.integration.v1.SignerLookup
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_UID
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 pairing rows owned by the qwy provider lane (§10.1 table 4).
 * Spec: §6.5 / §6.5.1 / §8.5; INV-02.
 */
class PairingMatrixTest {

    /**
     * M-PA-01: operator approves a pending candidate much later / after restart.
     * The approval uses the identity snapshot persisted DURING the original
     * Binder call; it must not fail or degrade because reverse package
     * visibility has lapsed (storing only the UID for later reverse lookup is
     * the forbidden design).
     */
    @Test
    fun M_PA_01() {
        val h = ProviderHarness.create()

        // Unpaired first contact (bind-first, §4.1): typed NOT_PAIRED + durable candidate snapshot.
        expectContractFailure(ContractErrorCodeV1.NOT_PAIRED) { h.apply(uid = OTHER_UID) }
        val candidate = h.pairing.pendingCandidates().singleOrNull {
            it.callerApplicationId == OTHER_PKG && it.currentSignerDigest == OTHER_SIGNER
        }
        assertNotNull("candidate snapshot persisted during the call", candidate)

        // Long delay + owner restart + reverse lookup no longer possible.
        h.clock.advance(3_600_000L)
        h.resolver.packagesByUid.remove(OTHER_UID)
        h.resolver.signersByPackage.remove(OTHER_PKG)
        h.restart(cleanlinessProvable = true)

        // Approval consumes the stored snapshot — no reverse query, no degrade.
        h.pairing.approve(candidate!!, h.clock.elapsedRealtimeMs())

        // Caller comes back with the same identity: authorized.
        h.resolver.register(OTHER_UID, OTHER_PKG, OTHER_SIGNER)
        val receipt = h.apply(uid = OTHER_UID, key = "pa01-apply")
        assertTrue("apply authorized after snapshot-based approval", receipt.leaseId.isNotEmpty())
    }

    /**
     * M-PA-02: Auto uninstalled, UID recycled by another app. Identity matching
     * is (applicationId, signer) snapshot comparison per call — a recycled UID
     * never rides an old pairing.
     */
    @Test
    fun M_PA_02() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)

        // Same uid now resolves to a different app identity.
        h.resolver.register(AUTO_UID, "com.squatter.app", "signer-squatter")

        expectContractFailure(ContractErrorCodeV1.NOT_PAIRED) { h.apply(uid = AUTO_UID) }
        assertEquals("no environment call reached qwy", 0, h.env.applyCount)
    }

    /**
     * M-PA-03: shared UID caller — getPackagesForUid returns != exactly one
     * package. v1 rejects with typed CALLER_NOT_ALLOWED (§6.5.1 step one).
     */
    @Test
    fun M_PA_03() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.resolver.packagesByUid[AUTO_UID] = listOf(AUTO_PKG, "com.shared.uid.sibling")

        expectContractFailure(ContractErrorCodeV1.CALLER_NOT_ALLOWED) { h.apply(uid = AUTO_UID) }
        assertEquals(0, h.env.applyCount)
    }

    /**
     * M-PA-04: certificate rotation after pairing. The CURRENT signer differs
     * from the paired snapshot; "the old digest is still in the rotation
     * history" (hasSigningCertificate semantics) must NOT let the call through
     * — re-pairing is required.
     */
    @Test
    fun M_PA_04() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)

        // Rotation: current signer changed; single signer, resolvable, not legacy.
        h.resolver.register(AUTO_UID, AUTO_PKG, "signer-auto-2-rotated")

        expectContractFailure(ContractErrorCodeV1.CALLER_NOT_ALLOWED) { h.apply(uid = AUTO_UID) }
        assertEquals(0, h.env.applyCount)
    }

    /**
     * Supplementary (non-ledger; Task 3 RED order step 2): legacy 24–27
     * GET_SIGNATURES path must fail closed on multiple signers — ambiguity is
     * rejection, not "take the first".
     */
    @Test
    fun legacyPath_multipleSigners_rejectedFailClosed() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.resolver.packagesByUid[AUTO_UID] = listOf(AUTO_PKG)
        h.resolver.signersByPackage[AUTO_PKG] = SignerLookup(
            currentSignerDigests = listOf(AUTO_SIGNER, "signer-second"),
            hasMultipleSigners = true,
            legacyApi = true,
            versionCode = 1L,
        )

        expectContractFailure(ContractErrorCodeV1.CALLER_NOT_ALLOWED) { h.apply(uid = AUTO_UID) }
        assertEquals(0, h.env.applyCount)
    }

    /**
     * Supplementary (non-ledger): unresolvable signer state fails closed —
     * a lookup returning null is a rejection, never a pass-through.
     */
    @Test
    fun unresolvableSigner_rejectedFailClosed() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.resolver.packagesByUid[AUTO_UID] = listOf(AUTO_PKG)
        h.resolver.signersByPackage[AUTO_PKG] = null

        expectContractFailure(ContractErrorCodeV1.CALLER_NOT_ALLOWED) { h.apply(uid = AUTO_UID) }
        assertEquals(0, h.env.applyCount)
    }
}

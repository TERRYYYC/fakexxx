package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapter-policy layer: "given these signing facts, what is the authorization
 * principal?"
 *
 * SCOPE, STATED SO NEITHER LAYER CAN BE MISTAKEN FOR THE OTHER
 * ------------------------------------------------------------
 * These cases prove our MAPPING conforms to §6.5.1 / spec §1682. They prove
 * nothing about whether a real PackageManager supplies the facts we think it
 * does — rotation behavior, API 24–27 GET_SIGNATURES, a dead binder. That is
 * platform fidelity and belongs to #7 instrumented acceptance, where §6.5.2
 * already admits controlled fixtures or injected SigningInfo as evidence.
 *
 * A Robolectric test here would blur the two: it would assert that our code
 * matches our own model of PackageManager, then read on a dashboard as though
 * the platform had been exercised.
 *
 * WHY THE CONFLICT CASE IS THE IMPORTANT ONE
 * ------------------------------------------
 * The production resolver used `signingCertificateHistory.takeLast(1)` for
 * single-signer packages while its comment claimed the history was never
 * consulted. On a normally-signed package the history's last entry IS the
 * current signer, so both sources agree and every ordinary test passes either
 * way. The defect only becomes visible when the two DISAGREE — which in
 * production means a rotated package, i.e. exactly the case §1685 says must
 * fail a pairing check rather than silently pass it.
 *
 * So the first case below hands the policy two different answers on purpose.
 * That is the only shape of test that can tell which source is really load-bearing.
 */
class SignerLookupPolicyTest {

    private fun facts(
        apkContents: List<String>,
        history: List<String>,
        multiple: Boolean = false,
        legacy: Boolean = false,
    ) = RawSigningFacts(
        apkContentsSignerDigests = apkContents,
        historyDigests = history,
        hasMultipleSigners = multiple,
        legacyApi = legacy,
        versionCode = 42L,
    )

    /**
     * §1682: on API ≥ 28 the principal is `getApkContentsSigners()`, full stop.
     * §1685: the history carries "has ever used" semantics, which is what lets a
     * rotation keep authorizing after the signer changed.
     */
    @Test
    fun `the principal comes from apkContentsSigners even when history disagrees`() {
        val lookup = SignerLookupPolicy.resolve(
            facts(
                apkContents = listOf("current-signer"),
                history = listOf("retired-signer", "another-retired-signer"),
            )
        )

        assertEquals(
            "apkContentsSigners is the frozen source for the current signer set; " +
                "a value that could only have come from the history means a rotated " +
                "package would still authorize",
            listOf("current-signer"),
            lookup?.currentSignerDigests
        )
    }

    /**
     * The earlier defect took the history only when there was a single signer.
     * A rule with an exception gets taken on the exception, so the single-signer
     * shape is pinned separately rather than assumed to follow from the case above.
     */
    @Test
    fun `a single-signer package still reads from apkContentsSigners`() {
        val lookup = SignerLookupPolicy.resolve(
            facts(
                apkContents = listOf("current-only"),
                history = listOf("rotated-away"),
                multiple = false,
            )
        )

        assertEquals(listOf("current-only"), lookup?.currentSignerDigests)
        assertFalse(lookup!!.hasMultipleSigners)
    }

    /** §6.5.1: multi-signer is rejected in v1; the flag must survive the mapping. */
    @Test
    fun `multiple signers are reported so the authorizer can fail closed`() {
        val lookup = SignerLookupPolicy.resolve(
            facts(
                apkContents = listOf("signer-a", "signer-b"),
                history = emptyList(),
                multiple = true,
            )
        )

        assertTrue(
            "the authorizer rejects multi-signer callers, and it can only do that " +
                "if the mapping preserves the fact",
            lookup!!.hasMultipleSigners
        )
        assertEquals(listOf("signer-a", "signer-b"), lookup.currentSignerDigests)
    }

    /**
     * API 24–27 cannot distinguish rotation from a genuine signer change, so the
     * degraded path must be labelled — losing the flag would silently promote a
     * weaker check to a strong one.
     */
    @Test
    fun `the legacy path stays marked as legacy`() {
        val lookup = SignerLookupPolicy.resolve(
            facts(
                apkContents = listOf("legacy-signer"),
                history = emptyList(),
                legacy = true,
            )
        )

        assertTrue(lookup!!.legacyApi)
        assertEquals(listOf("legacy-signer"), lookup.currentSignerDigests)
    }

    /**
     * No facts at all — uninstalled package, unreadable signature, dead binder.
     * Anything other than null here would be a partially-trusted answer produced
     * outside the authorizer, which is where such decisions are visible.
     */
    @Test
    fun `absent facts fail closed rather than degrading`() {
        assertNull(
            "a lookup that could not resolve must not become an empty-but-present " +
                "principal",
            SignerLookupPolicy.resolve(null)
        )
    }
}

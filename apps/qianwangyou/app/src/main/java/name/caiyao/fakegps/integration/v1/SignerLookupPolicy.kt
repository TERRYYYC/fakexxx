package name.caiyao.fakegps.integration.v1

/**
 * Platform-independent view of one signing lookup.
 *
 * This exists so the DECISION ("which of these facts is the authorization
 * principal?") can be separated from the RETRIEVAL ("what does PackageManager
 * return on this device?"). They fail differently and are proven differently:
 * the decision is our policy and is a pure function; the retrieval is Android's
 * behavior and only a real device can settle it.
 *
 * Keeping them fused is what let a spec violation hide in an untestable class.
 */
internal data class RawSigningFacts(
    /**
     * §6.5.1 / spec §1682: `SigningInfo.getApkContentsSigners()` on API ≥ 28 —
     * the CURRENT signer set. This is the only admissible source of the
     * principal.
     */
    val apkContentsSignerDigests: List<String>,
    /**
     * `signingCertificateHistory` — past AND current certificates. Captured for
     * diagnostics only and deliberately NOT an input to the principal; see
     * [SignerLookupPolicy.resolve].
     */
    val historyDigests: List<String>,
    val hasMultipleSigners: Boolean,
    /** true = API 24–27 GET_SIGNATURES degraded path. */
    val legacyApi: Boolean,
    val versionCode: Long,
)

/**
 * Maps raw signing facts to the [SignerLookup] the authorizer consumes.
 *
 * WHY THIS IS ITS OWN THING
 * -------------------------
 * The production resolver used `signingCertificateHistory.takeLast(1)` for
 * single-signer packages, while its own comment claimed the historical chain was
 * "deliberately not consulted". Spec §1682 freezes `getApkContentsSigners()` as
 * the API ≥ 28 source, and §1685 spends a paragraph on exactly why the history
 * must not be the principal: it carries "has ever used" semantics, which is what
 * lets a signer rotation silently pass a pairing check that should have failed.
 *
 * takeLast(1) usually returns today's signer, so the defect would not show up in
 * casual use — it shows up as a rotated package still authorizing. Reading the
 * class could not catch it either, because the comment asserted the opposite of
 * the code. Only a test that hands the two sources CONFLICTING values can tell
 * which one is really being used, and that test needs this seam to exist.
 */
internal object SignerLookupPolicy {

    /**
     * @return null when the platform could not produce facts at all — an
     *   uninstalled package, an unreadable signature, a dead PackageManager
     *   binder. Fail closed: [CallerAuthorizer] turns null into
     *   CALLER_NOT_ALLOWED rather than any partially-trusted answer.
     */
    fun resolve(facts: RawSigningFacts?): SignerLookup? {
        if (facts == null) return null

        return SignerLookup(
            // The principal is apkContentsSigners, ALWAYS — never the history,
            // and not "history when there is only one signer" either. A rule
            // with an exception is a rule that will be taken on the exception.
            currentSignerDigests = facts.apkContentsSignerDigests,
            hasMultipleSigners = facts.hasMultipleSigners,
            legacyApi = facts.legacyApi,
            versionCode = facts.versionCode,
        )
    }
}

package name.caiyao.fakegps.integration.v1

/**
 * Per-call identity resolution and authorization (§6.5, §6.5.1).
 *
 * Truth source for identity is Binder.getCallingUid() only. Matching compares
 * the identity resolved DURING this call against the persisted pairing
 * snapshot; storing a UID and reverse-resolving later is forbidden (M-PA-01/02).
 *
 * Fail-closed rules (all typed, never a crash):
 *  - packagesForUid(uid) != exactly 1 package → CALLER_NOT_ALLOWED (shared UID, M-PA-03)
 *  - unresolvable signer state → CALLER_NOT_ALLOWED
 *  - multiple signers → CALLER_NOT_ALLOWED (both API tiers, §6.5.1)
 *  - legacy 24–27 path: single-signer only, any ambiguity rejected
 *  - current signer != paired snapshot → CALLER_NOT_ALLOWED (rotation requires
 *    re-pairing; "has ever used this cert" is NOT an identity predicate, M-PA-04)
 *  - resolvable but unpaired → durable PendingPairingCandidate + NOT_PAIRED (§4.1 bind-first)
 */
class CallerAuthorizer(
    private val resolver: PackageIdentityResolver,
    private val pairingStore: PairingStore,
    private val clock: MonotonicClock,
) {
    /**
     * Resolve and authorize the calling uid.
     *
     * @return the in-call resolved identity when an active PairingRecord matches.
     * @throws ContractException NOT_PAIRED / CALLER_NOT_ALLOWED per the rules above.
     */
    fun authorize(callingUid: Int): CallerIdentity {
        // Step 1: Resolve identity from UID (Binder-resolved, never from request params)
        val packages = resolver.packagesForUid(callingUid)
        if (packages.size != 1) {
            throw ContractException(
                io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1.CALLER_NOT_ALLOWED,
                "shared UID or no package: uid=$callingUid packages=${packages.size}",
            )
        }
        val applicationId = packages[0]

        val signerInfo = resolver.signerLookup(applicationId)
            ?: throw ContractException(
                io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1.CALLER_NOT_ALLOWED,
                "unresolvable signer state for $applicationId",
            )

        if (signerInfo.hasMultipleSigners) {
            throw ContractException(
                io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1.CALLER_NOT_ALLOWED,
                "multiple signers for $applicationId",
            )
        }

        if (signerInfo.currentSignerDigests.size != 1) {
            throw ContractException(
                io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1.CALLER_NOT_ALLOWED,
                "expected exactly 1 current signer for $applicationId, got ${signerInfo.currentSignerDigests.size}",
            )
        }

        val signerDigest = signerInfo.currentSignerDigests[0]

        // Step 2: Match against pairing store
        val pairing = pairingStore.findActive(applicationId, signerDigest)
        if (pairing != null) {
            return CallerIdentity(
                uid = callingUid,
                applicationId = applicationId,
                signerDigest = signerDigest,
            )
        }

        // M-PA-04: distinguish "never paired" vs "signer rotated after pairing".
        // If any pairing exists for this applicationId (with a DIFFERENT digest),
        // the current signer doesn't match the paired snapshot → CALLER_NOT_ALLOWED
        // (rotation requires re-pairing; "has ever used this cert" is NOT an identity
        // predicate).
        if (pairingStore.hasAnyPairing(applicationId)) {
            throw ContractException(
                io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1.CALLER_NOT_ALLOWED,
                "signer rotated for $applicationId; re-pairing required",
            )
        }

        // Truly unpaired: persist candidate before failing (§4.1 bind-first)
        pairingStore.recordCandidate(
            PendingPairingCandidate(
                callerApplicationId = applicationId,
                currentSignerDigest = signerDigest,
                observedVersionCode = signerInfo.versionCode,
                firstSeenAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            ),
        )
        throw ContractException(
            io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1.NOT_PAIRED,
            "$applicationId is not paired",
        )
    }
}

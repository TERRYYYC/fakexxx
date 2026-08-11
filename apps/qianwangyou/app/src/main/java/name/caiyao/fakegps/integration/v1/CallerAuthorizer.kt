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
    fun authorize(callingUid: Int): CallerIdentity =
        TODO("Task 3 GREEN: §6.5.1 two-step identity resolution + snapshot match")
}

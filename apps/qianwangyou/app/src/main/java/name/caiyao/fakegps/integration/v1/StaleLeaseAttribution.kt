package name.caiyao.fakegps.integration.v1

/**
 * F-15 observability for the §6.7.4b step-3b historical-reference
 * attribution gate: the four rejection branches of `completeAndAdvance`
 * (frozen taxonomy v1.75/v1.76/v1.77, contract exact 00e2396) all answer
 * wire STALE_LEASE(8), and before this file that species verdict existed
 * ONLY inside the `diagnosticMessage` crossing Binder — which the on-device
 * probe does not surface (C5: 740 lines of logcat, zero hits for
 * unproven / foreign / wrong-item). F-12 forensics therefore could not
 * tell WHICH branch rejected; this file makes the species durable-visible
 * in the logcat channel WITHOUT touching the wire.
 *
 * Contract invariants this file must keep (review anchors):
 *  - wire stays STALE_LEASE(8); no new wire code, no taxonomy change;
 *  - the four [StaleLeaseAttribution.Rejected.message] strings are the
 *    historical handler throw strings VERBATIM — `diagnosticMessage` bytes
 *    crossing Binder must not change;
 *  - the species log line is additive provider-local diagnostics only.
 */
enum class StaleLeaseSpecies(val logToken: String) {
    /** 3b row 1: `leaseStore.get(leaseId)` is null — forged or never earned. */
    UNPROVEN_NO_PROVIDER_RECORD("UNPROVEN_NO_PROVIDER_RECORD"),

    /** 3b row 2: the durable row belongs to another (applicationId, signerDigest) principal. */
    FOREIGN_CALLER("FOREIGN_CALLER"),

    /** 3b row 3: caller-owned row, but pre-v1.75 schema has no originating-item attribution. */
    UNPROVEN_NO_ORIGINATING_ITEM("UNPROVEN_NO_ORIGINATING_ITEM"),

    /** 3b row 4: the quota was earned for a different item than the one being advanced. */
    WRONG_ITEM("WRONG_ITEM"),
}

/** Outcome of the pure step-3b judgment; the handler turns [Rejected] into the wire-8 throw. */
sealed class StaleLeaseAttribution {

    /**
     * 3b passed: reference is caller-owned and earned for [earnedScheduleRef].
     * [row] is the durable record the verdict was reached on — the handler
     * reuses it downstream (step 6a intent hash) instead of re-reading the
     * store; a fallback re-read would silently mask a broken gate.
     */
    data class Attributed(
        val row: LeaseRecord,
        val earnedScheduleRef: String,
    ) : StaleLeaseAttribution()

    /**
     * 3b rejected: [species] names WHICH branch, [message] is the frozen
     * diagnosticMessage (verbatim historical throw string — see class KDoc).
     */
    data class Rejected(
        val species: StaleLeaseSpecies,
        val message: String,
    ) : StaleLeaseAttribution()
}

/**
 * Pure §6.7.4b step-3b judgment, extracted so the species decision is
 * JVM-pinnable independently of the handler's logging glue. Judgment order
 * is frozen: existence → caller ownership → originating-item attribution →
 * item match (v1.75 order, v1.76 removed recency). [leaseRow] is the
 * already-fetched durable row (null = no provider record).
 */
fun attributeLease(
    leaseRow: LeaseRecord?,
    leaseId: String,
    caller: CallerIdentity,
    expectedCurrentItemId: String,
): StaleLeaseAttribution {
    if (leaseRow == null) {
        return StaleLeaseAttribution.Rejected(
            StaleLeaseSpecies.UNPROVEN_NO_PROVIDER_RECORD,
            "leaseId $leaseId unproven: no provider record of it (forged or never earned)",
        )
    }
    if (leaseRow.callerApplicationId != caller.applicationId ||
        leaseRow.callerSignerDigest != caller.signerDigest
    ) {
        return StaleLeaseAttribution.Rejected(
            StaleLeaseSpecies.FOREIGN_CALLER,
            "leaseId $leaseId is foreign: earned by another caller",
        )
    }
    val earnedScheduleRef = leaseRow.earnedScheduleRef ?: return StaleLeaseAttribution.Rejected(
        StaleLeaseSpecies.UNPROVEN_NO_ORIGINATING_ITEM,
        "leaseId $leaseId unproven: durable row has no originating-item attribution",
    )
    if (earnedScheduleRef != expectedCurrentItemId) {
        return StaleLeaseAttribution.Rejected(
            StaleLeaseSpecies.WRONG_ITEM,
            "leaseId $leaseId earned quota for item " +
                "$earnedScheduleRef, not $expectedCurrentItemId (wrong-item)",
        )
    }
    return StaleLeaseAttribution.Attributed(
        row = leaseRow,
        earnedScheduleRef = earnedScheduleRef,
    )
}

/**
 * The single greppable logcat line emitted per 3b rejection. On-device F-12
 * forensics greps `STALE_LEASE_SPECIES=` and reads the token — one stable
 * key, four mutually exclusive tokens (pinned pairwise-distinct in
 * StaleLeaseAttributionTest).
 */
fun staleLeaseSpeciesLogLine(
    species: StaleLeaseSpecies,
    leaseId: String,
    expectedCurrentItemId: String,
): String = "STALE_LEASE_SPECIES=${species.logToken} " +
    "op=completeAndAdvance leaseId=$leaseId expectedItem=$expectedCurrentItemId"

/**
 * Seam over `android.util.Log` so handler paths stay JVM-unit-testable
 * (the JVM lane deliberately does not enable returnDefaultValues; the
 * ConfigPrefsSync precedent keeps Android Log out of tested paths, and this
 * seam extends the same rule to the 3b rejection path, which IS tested).
 * Production wires [ANDROID]; tests inject a recorder.
 */
fun interface DiagnosticLog {
    fun warn(tag: String, message: String)

    companion object {
        /** On-device sink. The lambda body touches android.util.Log only when invoked. */
        val ANDROID: DiagnosticLog = DiagnosticLog { tag, message ->
            android.util.Log.w(tag, message)
        }
    }
}

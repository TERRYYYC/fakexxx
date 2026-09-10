package name.caiyao.fakegps.integration.v1

/**
 * One open bracket over a QWY semantic state transition in the system-server
 * oracle (#155, parent #66). Between [beforeDigest] and the finish call, every
 * write that can move a digest input (owner generation, effective environment,
 * schedule projection) must have happened, so the finish-time recomputation is
 * exactly what the next observer recomputation will see.
 */
interface QwySemanticMutation {
    /** The observed digest the oracle accepted when the bracket opened. */
    val beforeDigest: String

    /**
     * Closes the bracket exactly once; a repeat call is a no-op. [changed]
     * must be the measured `afterDigest != beforeDigest`; an exception path
     * finishes with uncertain=true rather than leaving an open bracket.
     */
    fun finish(changed: Boolean, uncertain: Boolean, afterDigest: String)
}

/**
 * Producer-side bracket seam (#155). A live session turns every semantic write
 * of [EnvironmentControlHandler] into a bracketed oracle mutation; a null from
 * [begin] means no live session — the caller proceeds unbracketed (the pre-
 * driver mode the observer already answers NONE for). Once a session IS live,
 * bracket failures are the oracle's own fail-closed paths; they are surfaced,
 * never silently skipped.
 */
interface QwySemanticMutationSource {
    /** Opens one bracket, or null when no live oracle session exists. */
    fun begin(mutationId: String): QwySemanticMutation?
}

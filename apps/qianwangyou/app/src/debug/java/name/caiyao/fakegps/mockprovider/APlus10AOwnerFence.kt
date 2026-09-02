package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.integration.v1.EnvironmentControlHandler

/**
 * P10DBG-COLLECTOR-V1 — R6 P1: the REAL owner fence for the §5A seed.
 *
 * WHY REFLECTION, AND WHY IT IS THE HONEST OPTION HERE
 * ----------------------------------------------------
 * Every observational quiescence probe (R4/R5) was defeated by a concrete
 * interleaving: debug surfaces can boot ProviderRuntime.handler() directly
 * (no service), handler CONSTRUCTION can reinitialize the schedule store with
 * no audit row, and fenced ops like runRevokedLeaseCleanup mutate without
 * audit append — so no witness set proves exclusion. The only sound options
 * were a production seam (out of the debug-only boundary) or acquiring the
 * SAME monitor `withOwnerFence` synchronizes on. This helper does the latter:
 * it reads the handler's private `ownerLock` and the seed holds it across the
 * whole reset/profile/publish region. Same monitor ⇒ real mutual exclusion
 * with every fenced owner operation — not a timing observation.
 *
 * Boot-ordering completes the proof: ProviderRuntime.handler() is
 * bootLock-serialized and returns only after construction (including the
 * controller's schedule reinit) finished, so "construction reinit before any
 * witness" cannot straddle the seed — the seed calls handler() FIRST, then
 * takes the lock.
 *
 * Drift safety: the field name is pinned two ways — the surface guard scans
 * the production source for `ownerLock`, and the testDebug latch race test
 * proves at runtime that holding [lockOf] BLOCKS a real fenced handler op
 * (mutation-sensitive: renaming the field or splitting the lock turns it red).
 *
 * src/debug ONLY — production carries none of this.
 */
object APlus10AOwnerFence {

    const val MARKER = "P10DBG-COLLECTOR-V1"
    const val OWNER_LOCK_FIELD = "ownerLock"

    /** The exact monitor object `withOwnerFence` synchronizes on. */
    fun lockOf(handler: EnvironmentControlHandler): Any =
        EnvironmentControlHandler::class.java
            .getDeclaredField(OWNER_LOCK_FIELD)
            .apply { isAccessible = true }
            .get(handler)
            ?: throw IllegalStateException(
                "$OWNER_LOCK_FIELD is null — cannot serialize with the owner; refusing to seed",
            )
}

package io.github.terryyyc.fakexxx.contract.v1

/**
 * Typed failure codes of the environment MAINTENANCE contract v1
 * ([IEnvironmentMaintenanceV1]). Wire values are frozen: never renumbered,
 * never reused, never removed.
 *
 * Codes 1 and 2 are the §6.5 authorization failures and deliberately share the
 * numeric values of [ContractErrorCodeV1.NOT_PAIRED] and
 * [ContractErrorCodeV1.CALLER_NOT_ALLOWED]: the maintenance surface authorizes
 * through the SAME CallerAuthorizer machinery, and an authorization refusal on
 * this channel means exactly what it means on the control channel. Codes 3-7
 * are maintenance-specific outcomes; they deliberately do NOT reuse any
 * §6.3.3 control-channel value, so a code can never be ambiguous across the
 * two surfaces.
 */
enum class MaintenanceResetErrorCodeV1(val wire: Int) {
    /** Binder caller has no active pairing (§6.5) — a candidate is recorded. */
    NOT_PAIRED(1),

    /** Binder caller resolved to a foreign or rotated identity (§6.5). */
    CALLER_NOT_ALLOWED(2),

    /** A non-converged lease still blocks schedule mutation (INV-28). */
    BLOCKED_BY_LEASE(3),

    /** The provider has no schedule to reset. */
    NO_SCHEDULE(4),

    /** Durable schedule state is corrupt (e.g. version 0 over surviving keys) — fail-closed. */
    CORRUPT_SCHEDULE_STATE(5),

    /** The reset commit could not be made durable; nothing was mutated. */
    WRITE_FAILED(6),

    /**
     * The schedule reset COMMITTED but re-publishing the re-anchored effective
     * profile failed. [MaintenanceResultV1.scheduleVersionAfter] still carries
     * the committed generation — this is a partial success, never a rollback.
     */
    PUBLISH_FAILED(7);

    companion object {
        fun fromWireOrInternalFailure(wire: Int): MaintenanceResetErrorCodeV1? =
            entries.firstOrNull { it.wire == wire }
    }
}

/** Result kind of a [MaintenanceResultV1]. Wire values frozen. */
enum class MaintenanceResultKindV1(val wire: Int) {
    OK(1),
    ERROR(2);

    companion object {
        fun fromWire(wire: Int): MaintenanceResultKindV1? = entries.firstOrNull { it.wire == wire }
    }
}

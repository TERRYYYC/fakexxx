package io.github.terryyyc.fakexxx.contract.v1

/**
 * Environment Control contract v1 enumerations.
 *
 * Spec: `feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md` §6.2.
 *
 * ## Why these never cross Binder as Kotlin enums
 *
 * kotlin-parcelize's `IrEnumParcelSerializer` writes `Parcel.writeString(value.name)`
 * and reads back `EnumClass.valueOf(readString())`. Reordering constants is
 * therefore wire-safe (the ordinal never goes on the wire), but **renaming is a
 * breaking change, and a constant added by a newer peer makes an older reader
 * throw `IllegalArgumentException` from the generated `createFromParcel`**.
 *
 * That surfaces as an unparcel crash inside a Binder transaction rather than the
 * typed fail-closed outcome INV-03 requires. The two apps ship independently, so
 * version skew is not an edge case — it is the normal state (§10 `version` rows).
 *
 * Every DTO field therefore carries the stable `Int` wire code and each side
 * decodes explicitly via `fromWire`, which turns skew into a decidable business
 * error instead of a crash.
 *
 * ## Wire code stability
 *
 * A wire code assigned in v1 is permanent: it is never reused and its meaning is
 * never changed. New constants may only append a new code, and only after
 * passing the §6.7 compatibility matrix.
 */

/**
 * Level of verification the environment provider claims for an observation.
 *
 * Trust policy must match [SYSTEM_MOCK_INDEPENDENTLY_VERIFIED] **explicitly**.
 * Ordinal comparison, `>=`, and "anything but NONE is trustworthy" are forbidden
 * (INV-05). Adding a constant below must never make a comparison-based policy
 * silently start trusting it.
 */
enum class VerificationLevelV1(val wire: Int) {
    SYSTEM_MOCK_INDEPENDENTLY_VERIFIED(1),
    HOOK_UNVERIFIED(2),
    NONE(3),
    ;

    companion object {
        /**
         * @return null when [code] is unknown, which means the peer is newer and
         *   incompatible. Callers must fail closed: never guess a trusted level.
         */
        fun fromWire(code: Int): VerificationLevelV1? = entries.firstOrNull { it.wire == code }
    }
}

/**
 * How completely the provider was able to observe the environment for the whole
 * observation window.
 *
 * [FULL] may only be reported when a complete event source covered the entire
 * window. Polling or heartbeats can never raise coverage to [FULL] (INV-09).
 */
enum class ContinuityCoverageV1(val wire: Int) {
    FULL(1),
    PARTIAL(2),
    NONE(3),
    ;

    companion object {
        fun fromWire(code: Int): ContinuityCoverageV1? = entries.firstOrNull { it.wire == code }
    }
}

/** Mechanism the provider used to deliver the environment. */
enum class DeliveryModeV1(val wire: Int) {
    SYSTEM_MOCK(1),
    HOOK(2),
    ;

    companion object {
        fun fromWire(code: Int): DeliveryModeV1? = entries.firstOrNull { it.wire == code }
    }
}

/** Provider's schedule verdict for the requested intent. */
enum class ScheduleDecisionV1(val wire: Int) {
    ALLOWED_NOW(1),
    WAIT_UNTIL(2),
    DENIED(3),
    ;

    companion object {
        fun fromWire(code: Int): ScheduleDecisionV1? = entries.firstOrNull { it.wire == code }
    }
}

/**
 * Frozen contract constants.
 */
object ContractV1 {
    /** Protocol version carried by every request and snapshot. */
    const val PROTOCOL_VERSION: Int = 1

    /**
     * Maximum distance between an observation's effective coordinates and the
     * intent's target coordinates for the observation to support trusted quota
     * (INV-23).
     *
     * 1.0 m is far above the ~1.1 cm quantisation of the 7-decimal canonical
     * digest (§6.3.1) and above double round-trip error, so it cannot cause a
     * false negative. It does **not** decide attribution — attribution comes
     * from `acceptedIntentHash`, which already contains `runId` and `attemptId`.
     */
    const val TRUSTED_LOCATION_TOLERANCE_METERS: Double = 1.0

    /** Class name of the provider service. The package half is the runtime applicationId. */
    const val SERVICE_CLASS_NAME: String = "name.caiyao.fakegps.integration.v1.EnvironmentControlService"

    /** Production Qianwangyou applicationId. */
    const val PROVIDER_APPLICATION_ID_PRODUCTION: String = "name.caiyao.fakegps"

    /** Bench Qianwangyou applicationId (`applicationIdSuffix ".bench"`). Pairs independently. */
    const val PROVIDER_APPLICATION_ID_BENCH: String = "name.caiyao.fakegps.bench"
}

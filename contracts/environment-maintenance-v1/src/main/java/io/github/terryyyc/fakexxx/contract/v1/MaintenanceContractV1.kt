package io.github.terryyyc.fakexxx.contract.v1

/**
 * Constants of the environment maintenance contract v1 (#140 dual-app quick
 * reset). The maintenance surface is ADDITIVE to the frozen six-op
 * [ContractV1] control surface: it never rewrites, renumbers or repurposes
 * anything the control contract published — it exposes one operator-recovery
 * command through the same pairing/authorization model (§6.5).
 */
object MaintenanceContractV1 {
    /** Protocol version carried by the result carrier. */
    const val PROTOCOL_VERSION: Int = 1

    /**
     * Class name of the provider-side maintenance service. The package half is
     * the runtime applicationId (production or .bench), paired independently —
     * the same rule as [ContractV1.SERVICE_CLASS_NAME].
     */
    const val SERVICE_CLASS_NAME: String =
        "name.caiyao.fakegps.integration.v1.EnvironmentMaintenanceService"
}

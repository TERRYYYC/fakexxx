package com.example.cellrebelauto.automation

import io.github.terryyyc.fakexxx.contract.v1.ContractV1

/**
 * The provider application identity selected by this Auto build.
 *
 * G2 is an isolated debug/acceptance build and therefore talks only to the bench provider. G3
 * release builds retain the production provider. Every runtime leg consumes [selected] so trust,
 * binding, status, and probes cannot choose provider identities independently.
 */
internal object ProviderPrincipal {

    fun resolve(isDebugBuild: Boolean): String =
        if (isDebugBuild) {
            ContractV1.PROVIDER_APPLICATION_ID_BENCH
        } else {
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        }

    val selected: String = resolve(ProviderPrincipalBuild.isDebugBuild)

    /** All pairable identities, with this build's selected principal first for the approval UI. */
    val knownApplicationIds: List<String> =
        listOf(selected, resolve(!ProviderPrincipalBuild.isDebugBuild))
}

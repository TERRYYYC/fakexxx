package com.example.cellrebelauto.automation

import io.github.terryyyc.fakexxx.contract.v1.ContractV1

/**
 * The provider application identity selected by this Auto build.
 *
 * Ordinary G2 debug uses the existing bench; G3 release uses the production provider.
 * The separate codexBench debug build cannot pair with either existing installation.
 * Every runtime leg consumes this closed selection so trust, binding, recovery, status,
 * and probes cannot choose provider identities independently.
 */
internal object ProviderPrincipal {

    const val CODEX_BENCH_APPLICATION_ID: String = "name.caiyao.fakegps.codexbench"

    fun resolve(isDebugBuild: Boolean, isCodexBenchBuild: Boolean = false): String {
        require(isDebugBuild || !isCodexBenchBuild) { "codex-bench requires a debug build" }
        return if (isCodexBenchBuild) {
            CODEX_BENCH_APPLICATION_ID
        } else if (isDebugBuild) {
            ContractV1.PROVIDER_APPLICATION_ID_BENCH
        } else {
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        }
    }

    fun knownForBuild(isDebugBuild: Boolean, isCodexBenchBuild: Boolean): List<String> {
        val target = resolve(isDebugBuild, isCodexBenchBuild)
        return if (isCodexBenchBuild) listOf(target) else listOf(target, resolve(!isDebugBuild))
    }

    val selected: String = resolve(ProviderPrincipalBuild.isDebugBuild, ProviderPrincipalBuild.isCodexBenchBuild)

    /** All pairable identities, with this build's selected principal first for the approval UI. */
    val knownApplicationIds: List<String> =
        knownForBuild(ProviderPrincipalBuild.isDebugBuild, ProviderPrincipalBuild.isCodexBenchBuild)

    fun isKnownApplicationId(applicationId: String?): Boolean =
        applicationId != null && applicationId in knownApplicationIds

    fun requireKnownApplicationId(applicationId: String?): String =
        requireNotNull(applicationId).also {
            require(isKnownApplicationId(it)) { "unknown provider applicationId: $it" }
        }
}

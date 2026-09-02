package com.example.cellrebelauto.automation

import com.example.cellrebelauto.BuildConfig

/** Both debug variants use this adapter; only the explicit codexBench type sets CODEX_BENCH. */
internal object ProviderPrincipalBuild {
    const val isDebugBuild: Boolean = true
    const val isCodexBenchBuild: Boolean = BuildConfig.CODEX_BENCH
}

package com.example.cellrebelauto.recovery

import com.example.cellrebelauto.BuildConfig
import io.github.terryyyc.fakexxx.contract.v1.ContractV1

/** Build-pair target shared by Binder transport and reverse signer trust. */
internal object ProviderPackageTarget {
    fun forDebugBuild(debug: Boolean): String =
        if (debug) {
            ContractV1.PROVIDER_APPLICATION_ID_BENCH
        } else {
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        }

    val currentApplicationId: String
        get() = forDebugBuild(BuildConfig.DEBUG)
}

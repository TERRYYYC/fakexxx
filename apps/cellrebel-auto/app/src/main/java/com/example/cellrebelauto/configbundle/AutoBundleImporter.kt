package com.example.cellrebelauto.configbundle

import com.example.cellrebelauto.model.plan.ParseResult
import com.example.cellrebelauto.model.plan.WorklistRow

/**
 * Parsed, validated bundle content ready to apply — NOTHING has been written yet.
 * The ViewModel decides whether to import directly, prompt the overwrite/skip
 * conflict dialog, or short-circuit as already-current.
 * # 解析即校验（原子）；落库时机由 ViewModel 按冲突策略决定
 */
sealed interface AutoBundleImportOutcome {

    data class Ready(
        val plan: PlanPayload?,
        val planConfig: PlanConfigPayload?,
        val pairing: List<ProviderFingerprint>,
        val lane: AutoBundleSections.LaneMetadata?,
        /** Count-reconciliation warnings (manifest declared vs package actual). */
        val warnings: List<String>,
    ) : AutoBundleImportOutcome

    data class Rejected(val reason: String) : AutoBundleImportOutcome
}

data class PlanPayload(
    val sourceFileName: String,
    val rows: List<WorklistRow>,
    val declaredCount: Int?,
)

/** Plan parameters + the plan's provenance file name from the bundle. */
data class PlanConfigPayload(
    val config: com.example.cellrebelauto.model.plan.PlanConfig,
    val planSourceFileName: String?,
)

/**
 * Auto-side config bundle PARSER (fail-closed; no I/O, no DB). Plan rows are validated
 * by the UNTOUCHED T2 [com.example.cellrebelauto.model.plan.WorklistParser] — atomic,
 * all row errors or nothing. Pairing fingerprints are read for display only; writing
 * provider_pairing_records from a bundle is forbidden (no silent TOFU).
 * # 解析器 fail-closed：计划行走既有 T2 WorklistParser；配对指纹只读展示，绝不写信任表
 */
class AutoBundleImporter {

    fun parse(zip: ByteArray): AutoBundleImportOutcome {
        val parsed = ConfigBundleContract.parseBundle(zip)
        if (parsed is ConfigBundleParseResult.Rejected) {
            return AutoBundleImportOutcome.Rejected(parsed.reason)
        }
        val bundle = (parsed as ConfigBundleParseResult.Ok).bundle
        val warnings = mutableListOf<String>()

        val planSection = bundle.manifest.sections["auto.plan"]
        var plan: PlanPayload? = null
        if (planSection != null) {
            val csv = String(bundle.files.getValue(planSection.file), Charsets.UTF_8)
            when (val result = AutoBundleSections.decodePlanCsv(csv)) {
                is ParseResult.Failure -> {
                    val first = result.errors.take(3).joinToString("; ") { "row ${it.csvRow}: ${it.message}" }
                    return AutoBundleImportOutcome.Rejected(
                        "计划区段未通过 T2 原子校验（${result.errors.size} 处）— $first",
                    )
                }
                is ParseResult.Success -> {
                    plan = PlanPayload(
                        sourceFileName = "fakexxx-config-bundle",
                        rows = result.rows,
                        declaredCount = planSection.count,
                    )
                }
            }
            if (planSection.count != null && planSection.count != plan.rows.size) {
                warnings += "计划行数对账不一致：清单声明 ${planSection.count}，包内实际 ${plan.rows.size}"
            }
        }

        val planConfig = bundle.manifest.sections["auto.planConfig"]?.let {
            AutoBundleSections.decodePlanConfig(bundle.files.getValue(it.file))
        }
        if (planConfig is AutoBundleSections.PlanConfigResult.Error) {
            return AutoBundleImportOutcome.Rejected(planConfig.reason)
        }
        val planConfigPayload = (planConfig as? AutoBundleSections.PlanConfigResult.Ok)?.let {
            PlanConfigPayload(it.config, it.planSourceFileName)
        }

        val pairing = bundle.manifest.sections["auto.pairing"]?.let {
            AutoBundleSections.decodePairing(bundle.files.getValue(it.file))
        }
        if (pairing is AutoBundleSections.PairingResult.Error) {
            return AutoBundleImportOutcome.Rejected(pairing.reason)
        }

        val lane = bundle.manifest.sections["meta.lane"]?.let {
            AutoBundleSections.decodeLane(bundle.files.getValue(it.file))
        }
        if (lane is AutoBundleSections.LaneResult.Error) {
            return AutoBundleImportOutcome.Rejected(lane.reason)
        }

        // "Not our bundle": an Auto import needs at least one auto.* section.
        if (plan == null && planConfig == null && pairing == null) {
            return AutoBundleImportOutcome.Rejected(
                "包内没有 Auto 区段（auto.*）——这不是 Auto 可导入的配置包",
            )
        }

        return AutoBundleImportOutcome.Ready(
            plan = plan?.copy(
                sourceFileName = planConfigPayload?.planSourceFileName ?: plan.sourceFileName,
            ),
            planConfig = planConfigPayload,
            pairing = (pairing as? AutoBundleSections.PairingResult.Ok)?.providers.orEmpty(),
            lane = (lane as? AutoBundleSections.LaneResult.Ok)?.lane,
            warnings = warnings,
        )
    }
}

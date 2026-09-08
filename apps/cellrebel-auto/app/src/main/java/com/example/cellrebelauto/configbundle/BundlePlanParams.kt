package com.example.cellrebelauto.configbundle

import com.example.cellrebelauto.model.plan.PlanConfig

/**
 * Applies a bundle's plan parameters through the caller-supplied store setters —
 * a testable seam over PlanConfigStore, kept pure of Android types.
 * # 计划参数应用：经 store setter 注入，保持可测
 */
object BundlePlanParams {
    suspend fun apply(
        config: PlanConfig,
        setBuffer: suspend (Int) -> Unit,
        setTimeout: suspend (Int) -> Unit,
        setSettle: suspend (Int) -> Unit,
        setLocationStage: suspend (Boolean) -> Unit,
        setTestStage: suspend (Boolean) -> Unit,
    ) {
        // Buffer stays absent → keep the local default rather than writing a null-able value.
        val buffer = config.globalBufferSeconds
        if (buffer != null) setBuffer(buffer)
        setTimeout(config.testTimeoutSeconds)
        setSettle(config.gpsSettleSeconds)
        setLocationStage(config.locationStageEnabled)
        setTestStage(config.testStageEnabled)
    }
}

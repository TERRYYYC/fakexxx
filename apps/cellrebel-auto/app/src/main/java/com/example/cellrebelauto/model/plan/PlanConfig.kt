package com.example.cellrebelauto.model.plan

/**
 * Plan-level configuration (O6). Buffer is nullable: null = not yet set, so the
 * Plan screen must require it on first run (design gate §1.1).
 * # 计划级配置。缓冲可空：null 表示尚未设置，计划页首启时必填
 */
data class PlanConfig(
    // # 全局缓冲秒数（相邻两次尝试之间）
    val globalBufferSeconds: Int?,
    // # 单次 CellRebel 测试超时秒数（高级设置，内部默认）
    val testTimeoutSeconds: Int = 90,
    // # Fake GPS 落点后的稳定等待秒数（高级设置）
    val gpsSettleSeconds: Int = 60,
    // # F003：位置阶段开关（Fake GPS 落点+激活验证+稳定等待），默认开
    val locationStageEnabled: Boolean = true,
    // # F003：CellRebel 测试阶段开关，默认开
    val testStageEnabled: Boolean = true
)

/**
 * Engine-side snapshot of the F003 stage toggles, re-read per attempt so a
 * mid-plan change takes effect from the next attempt (AC-F3-5). Never
 * persisted into plan/task data (INV-F3-3).
 * # 引擎侧阶段开关快照：每次 attempt 重新读取，中途修改下个 attempt 生效；
 * # 绝不写入计划/任务数据
 */
data class StageToggles(
    val locationStageEnabled: Boolean = true,
    val testStageEnabled: Boolean = true
)

package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.model.plan.StageToggles

/**
 * R43 (Sol R42 P1-2): the SINGLE production engine-construction seam.
 *
 * WHY THIS EXISTS. Sol's R42 verdict: the clock test constructed `AutomationEngine` directly with an
 * override, so removing the production `SystemClock.elapsedRealtime()` injection in
 * [AutomationService] survived every oracle — a monotonic-clock regression could enter GREEN with no
 * test failing. The fix is to move the ENTIRE production construction out of the Service into this
 * pure factory: the Service delegates here, and the test builds its engine through the SAME factory
 * call (only Android-bound deps differ), so the commit-clock default — [productionCommitClockMs] —
 * is production wiring that a test observes, not an invisible call-site lambda.
 *
 * The commit clock defaults to the MONOTONIC elapsedRealtime domain (can't go backwards on NTP),
 * while the engine's `nowMs` stays wall/epoch (session/attempt/cooldown UI). TrustedQuotaEntry
 * .committedAt binds ONLY the commit clock (§6.4.2 / Sol R41).
 *
 * # 生产引擎构造 factory（Sol R42 P1-2）：commit-clock 注入唯一 seam，测试与 Service 走同一构造路径
 */
object AutomationEngineFactory {

    /**
     * THE production commit-clock source — monotonic elapsedRealtime. Referenced by
     * [productionEngine]'s default AND asserted by the factory-wiring test: a mutation that reverts
     * the ledger commit to the wall domain (or drops the monotonic injection) fails that test.
     */
    val productionCommitClockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * Build the production engine exactly as [AutomationService] does. Callers supply only the
     * Android-bound deps (db, handlers, config, bridge); the clock defaults live HERE, in
     * production code, observable by tests.
     */
    fun productionEngine(
        planId: Long,
        planRepository: com.example.cellrebelauto.repository.PlanRepository,
        cellRebelRunner: com.example.cellrebelauto.automation.CellRebelRunner,
        gpsSetter: com.example.cellrebelauto.automation.GpsLocationSetter,
        globalBufferSeconds: Int,
        testTimeoutMs: Long,
        gpsSettleMs: Long,
        stageToggles: suspend () -> StageToggles,
        auditDao: com.example.cellrebelauto.db.AuditEventDao,
        aplusCoordinator: com.example.cellrebelauto.recovery.RecoveryCoordinator,
        aplusEvidence: com.example.cellrebelauto.automation.aplus.APlusEvidenceSource,
        bridge: com.example.cellrebelauto.automation.AccessibilityBridge?,
        nowMs: () -> Long = { System.currentTimeMillis() },
        delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        commitClockMs: () -> Long = productionCommitClockMs
    ): AutomationEngine = AutomationEngine(
        planId = planId,
        planRepository = planRepository,
        cellRebelRunner = cellRebelRunner,
        gpsSetter = gpsSetter,
        bufferGate = BufferGate(globalBufferSeconds) { nowMs() },
        testTimeoutMs = testTimeoutMs,
        gpsSettleMs = gpsSettleMs,
        stageToggles = stageToggles,
        bridge = bridge,
        attemptDriver = APlusAttemptDriver(auditDao),
        recoveryCoordinator = aplusCoordinator,
        completionEvidenceSource = aplusEvidence,
        nowMs = nowMs,
        delayMs = delayMs,
        commitClockMs = commitClockMs
    )
}

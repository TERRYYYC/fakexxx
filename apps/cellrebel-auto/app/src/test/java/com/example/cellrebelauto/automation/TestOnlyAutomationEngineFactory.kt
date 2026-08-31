package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.repository.PlanRepository

internal open class TestNoopEnvironmentControlService :
    io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1.Stub() {
    override fun discover() = io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
    override fun preflight(request: io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1) =
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
    override fun apply(request: io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1) =
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
    override fun observe(request: io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1) =
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
    override fun release(request: io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1) =
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
    override fun completeAndAdvance(
        request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
    ) = io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
}

/** Test-source-only construction seam. It cannot be referenced by shipped main code. */
internal fun testOnlyAutomationEngine(
    planId: Long,
    planRepository: PlanRepository,
    cellRebelRunner: CellRebelRunner,
    gpsSetter: GpsLocationSetter,
    globalBufferSeconds: Int,
    testTimeoutMs: Long,
    gpsSettleMs: Long,
    stageToggles: suspend () -> StageToggles,
    auditDao: com.example.cellrebelauto.db.AuditEventDao,
    aplusCoordinator: RecoveryCoordinator,
    aplusEvidence: APlusEvidenceSource,
    bridge: AccessibilityBridge?,
    nowMs: () -> Long = { System.currentTimeMillis() },
    delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    commitClockMs: () -> Long = AutomationEngineFactory.productionCommitClockMs,
    elapsedClockMs: () -> Long = AutomationEngineFactory.productionElapsedClockMs,
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
    commitClockMs = commitClockMs,
    elapsedClockMs = elapsedClockMs,
)

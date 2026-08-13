package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.recovery.RecoveryCoordinator

/**
 * The A+ production composition root (Issue #5 R8, Sol round-7 P1-1).
 *
 * WHY THIS EXISTS. Sol's round-7 falsification: the engine constructor seams
 * (`recoveryCoordinator` / `completionTrustContextProvider`) defaulted null in production while the
 * RED tests injected real coordinators/providers directly — so a fully-implemented-but-disconnected
 * attack greened every TrustedLedger + coordinator test while `AutomationService` still passed null
 * and the engine kept walking the legacy path. The fix is ONE composition point: production and tests
 * both wire the A+ seams through here from an [APlusBackend], so there is no hand-wired alternate
 * path a test can take that production does not also take.
 *
 * The backend is the CONTRACT-/GREEN-bound bundle (provider RPC executor, durable receipt log,
 * observation/revision/quota acquirers, classified completion evidence). Pre-freeze production ships
 * `backend = null` (pure legacy engine behavior); the moment #3 freezes and GREEN lands, the SAME
 * two functions turn a real backend into the coordinator + evidence source with no further wiring
 * change — the disconnect is structurally impossible, not just untested.
 *
 * # A+ 组合根：生产与测试都从这里用同一 APlusBackend 接线，杜绝"测试直接注入、生产留 null"的断路
 */
object APlusComposition {

    /**
     * Wire the recovery coordinator from a backend's executor + durable log + three constructor-owned
     * schedule-gate acquirers (§8.2 RECOVERING, §5 boundary).
     */
    fun recoveryCoordinator(backend: APlusBackend): RecoveryCoordinator = RecoveryCoordinator(
        backend.executor,
        backend.recoveryLog,
        backend.observeIntent,
        backend.receiptRevision,
        backend.trustedQuota
    )

    /**
     * Wire the A+ evidence source (pre/post observation + classified completion evidence) from the
     * backend. Target coordinates and the locally-recomputed intent hash are NEVER supplied here —
     * they are assembled by the engine from the persisted attempt intent (INV-23).
     */
    fun completionEvidenceSource(backend: APlusBackend): APlusEvidenceSource = backend.evidenceSource
}

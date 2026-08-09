package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.cellrebel.ScreenNode

/**
 * Typed outcome of a Fake GPS activation (AC-B4). GPS has no scores, so this
 * is a dedicated minimal type instead of reusing AttemptOutcome.
 * # Fake GPS 激活的类型化结果（AC-B4）。GPS 没有分数，
 * # 所以用专门的最小类型而不是复用 AttemptOutcome
 */
sealed interface GpsOutcome {
    /** # 激活已证实（"Stop Fake GPS" 按钮出现） */
    data object Active : GpsOutcome

    /** # 失败即停：原因类型化，绝不告警后继续 */
    data class Failed(val reason: FailureReason, val detail: String?) : GpsOutcome
}

/**
 * Engine-facing seam for setting a fake GPS location.
 * Implemented by FakeGpsHandler (device) and fakes (tests).
 * # 面向引擎的设置伪造 GPS 位置的接缝：
 * # 设备上由 FakeGpsHandler 实现，测试用假实现
 */
interface GpsLocationSetter {
    suspend fun setLocation(lat: Double, lng: Double): GpsOutcome
}

/**
 * Pure activation check: presence of the "Stop Fake GPS" button is the
 * activation evidence.
 * # 纯激活判断："Stop Fake GPS" 按钮存在即激活证据
 */
fun isFakeGpsActivationConfirmed(nodes: List<ScreenNode>): Boolean =
    nodes.any { it.text?.contains("Stop Fake GPS", ignoreCase = true) == true }

/**
 * Fail-closed verification: confirmed → Active, otherwise typed failure.
 * # 失败即停验证：证实 → Active，否则类型化失败
 */
fun verifyFakeGpsActivation(nodes: List<ScreenNode>): GpsOutcome =
    if (isFakeGpsActivationConfirmed(nodes)) {
        GpsOutcome.Active
    } else {
        GpsOutcome.Failed(
            reason = FailureReason.FAKE_GPS_NOT_ACTIVE,
            detail = "\"Stop Fake GPS\" button never appeared after the start sequence"
        )
    }

/**
 * Freshness attribution for activation (F4): Active only when THIS call's
 * sequence was confirmed — a stale Stop button from a previous spoofing
 * session is never evidence for the new location.
 * # 激活的新鲜度归属（F4）：只有本次调用的序列被确认才返回 Active；
 * # 上一次伪造残留的旧 Stop 按钮绝不作为新地点的激活证据
 *
 * @param previousSpoofingStopped true = 进入时无旧伪造，或旧伪造已被确认停止
 * @param startSequenceConfirmed true = 本次 Start 点击序列观察到了 Stop 出现
 * @param finalNodes 流程结束时的终态快照
 */
fun resolveActivationOutcome(
    previousSpoofingStopped: Boolean,
    startSequenceConfirmed: Boolean,
    finalNodes: List<ScreenNode>
): GpsOutcome {
    if (!previousSpoofingStopped) {
        return GpsOutcome.Failed(
            reason = FailureReason.FAKE_GPS_NOT_ACTIVE,
            detail = "Previous spoofing could not be stopped — any Stop button is stale"
        )
    }
    if (!startSequenceConfirmed) {
        return GpsOutcome.Failed(
            reason = FailureReason.FAKE_GPS_NOT_ACTIVE,
            detail = "This call's start sequence did not confirm activation"
        )
    }
    return verifyFakeGpsActivation(finalNodes)
}

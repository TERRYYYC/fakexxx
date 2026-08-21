package com.example.cellrebelauto.recovery

import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1

/**
 * R44 (Sol GREEN-review-3 F2): a well-formed apply intent for test fixtures that drive executor /
 * coordinator seams directly. The fake executors key on the requestDigest, never the intent — so a
 * fixture intent needs only to be STRUCTURALLY valid. Tests that must match the engine's owner-state
 * recompute pass the same explicit inputs the engine derives (session/plan/task refs + the attempt's
 * validity window).
 *
 * # 测试 fixture intent 助手：结构合法即可；要与 engine 重算一致的测试必须传同样显式输入
 */
fun testApplyIntent(
    attemptId: Long = 77L,
    sessionId: Long = 1L,
    planId: Long = 1L,
    taskId: Long = 42L,
    startedAt: Long = 600L,
    timeoutMs: Long = 90_000L
): EnvironmentIntentV1 =
    APlusOperationIdentity.intent(sessionId, attemptId, planId, taskId, startedAt, startedAt + timeoutMs)

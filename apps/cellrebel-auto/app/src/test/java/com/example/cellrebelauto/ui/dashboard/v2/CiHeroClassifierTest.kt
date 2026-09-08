package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T7v2 §A1-v2 #2 — the CI hero badge oracle (v1.81 wiring).
 *
 * OPERATOR HARD RULE: the displayed value is ALWAYS the raw device reading;
 * the badge is a SEMANTIC claim about where that value came from, and it must
 * never over-claim. When the configured CI is unobtainable the badge MUST fall
 * to 设备读数 — a lying "注入" is worse than a vague truth.
 *
 * Three states, exhaustively (v1.81: INJECTED additionally requires the
 * provider-asserted cellularHookConfigured discriminator):
 *  - observed == configured && hookConfigured → INJECTED 注入
 *  - configured != null, otherwise            → PASSTHROUGH_REAL 透传·真实
 *    (mismatch; or a coincidental equality with NO configured cellular group —
 *    nothing was injected, the reading is the real cell)
 *  - configured == null                       → DEVICE_READING 设备读数
 * plus the no-reading edge (no badge at all, never a fabricated state).
 *
 * # CI hero 徽标判定 oracle：三态穷尽 + 无读数不打徽标 + 宁"设备读数"不谎"注入"
 */
class CiHeroClassifierTest {

    // ---- the three exhaustive states -----------------------------------------

    @Test
    fun observedEqualsConfiguredWithHookConfigured_isInjected() {
        assertEquals(
            CiBadge.INJECTED,
            CiHeroClassifier.classify(
                observedCi = 289001L, configuredCi = 289001L, cellularHookConfigured = true,
            ),
        )
    }

    @Test
    fun configuredPresentButMismatches_isPassthroughReal_evenWhenHookConfigured() {
        assertEquals(
            CiBadge.PASSTHROUGH_REAL,
            CiHeroClassifier.classify(
                observedCi = 46692113L, configuredCi = 289001L, cellularHookConfigured = true,
            ),
        )
    }

    @Test
    fun configuredUnobtainable_isDeviceReading() {
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(
                observedCi = 46692113L, configuredCi = null, cellularHookConfigured = false,
            ),
        )
    }

    // ---- honesty bias: never a fabricated INJECTED -----------------------------

    @Test
    fun runningEngineWithUnknownConfig_stillDeviceReading_notInjected() {
        // The operator's hard rule: without a config value to compare against,
        // even a plausible-looking CI must be labelled 设备读数.
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(
                observedCi = 1L, configuredCi = null, cellularHookConfigured = true,
            ),
        )
    }

    @Test
    fun equalValuesWithoutConfiguredCellularGroup_neverClaimInjected() {
        // v1.81 fail-closed leg: the provider attests NO configured cellular
        // group (cellularHookConfigured=false) yet reports a configured ci —
        // a contradictory projection. Equality alone must never mint 注入:
        // without a configured hook the observed value IS the real cell, so
        // the honest claim is 透传·真实. Mutation: drop the
        // `&& cellularHookConfigured` leg → this test goes red.
        assertEquals(
            CiBadge.PASSTHROUGH_REAL,
            CiHeroClassifier.classify(
                observedCi = 289001L, configuredCi = 289001L, cellularHookConfigured = false,
            ),
        )
    }

    @Test
    fun hookConfiguredFlagDefaultsToConservativeFalse() {
        // Two-arg call sites (unknown discriminator) treat the group as NOT
        // configured: equal values stay non-INJECTED. Fail-closed by default.
        assertEquals(
            CiBadge.PASSTHROUGH_REAL,
            CiHeroClassifier.classify(observedCi = 289001L, configuredCi = 289001L),
        )
    }

    // ---- no reading: no badge, no claim -----------------------------------------

    @Test
    fun noObservedCi_yieldsNoVerdict_nullBadge() {
        assertNull(
            CiHeroClassifier.classify(
                observedCi = null, configuredCi = 289001L, cellularHookConfigured = true,
            ),
        )
        assertNull(
            CiHeroClassifier.classify(
                observedCi = null, configuredCi = null, cellularHookConfigured = false,
            ),
        )
    }

    @Test
    fun badgeLabels_areTheOperatorAgreedWording() {
        assertEquals("注入", CiBadge.INJECTED.label)
        assertEquals("透传·真实", CiBadge.PASSTHROUGH_REAL.label)
        assertEquals("设备读数", CiBadge.DEVICE_READING.label)
    }
}

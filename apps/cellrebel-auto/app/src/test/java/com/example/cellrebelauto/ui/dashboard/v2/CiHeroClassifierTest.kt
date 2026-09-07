package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T7v2 §A1-v2 #2 — the CI hero badge oracle.
 *
 * OPERATOR HARD RULE: the displayed value is ALWAYS the raw device reading;
 * the badge is a SEMANTIC claim about where that value came from, and it must
 * never over-claim. When the configured CI is unobtainable the badge MUST fall
 * to 设备读数 — a lying "注入" is worse than a vague truth.
 *
 * Three states, exhaustively:
 *  - observed == configured        → INJECTED 注入
 *  - configured != null, mismatch  → PASSTHROUGH_REAL 透传·真实
 *  - configured == null            → DEVICE_READING 设备读数
 * plus the no-reading edge (no badge at all, never a fabricated state).
 *
 * # CI hero 徽标判定 oracle：三态穷尽 + 无读数不打徽标 + 宁"设备读数"不谎"注入"
 */
class CiHeroClassifierTest {

    // ---- the three exhaustive states -----------------------------------------

    @Test
    fun observedEqualsConfigured_isInjected() {
        assertEquals(
            CiBadge.INJECTED,
            CiHeroClassifier.classify(observedCi = 289001L, configuredCi = 289001L),
        )
    }

    @Test
    fun configuredPresentButMismatches_isPassthroughReal() {
        assertEquals(
            CiBadge.PASSTHROUGH_REAL,
            CiHeroClassifier.classify(observedCi = 46692113L, configuredCi = 289001L),
        )
    }

    @Test
    fun configuredUnobtainable_isDeviceReading() {
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(observedCi = 46692113L, configuredCi = null),
        )
    }

    // ---- honesty bias: never a fabricated INJECTED -----------------------------

    @Test
    fun runningEngineWithUnknownConfig_stillDeviceReading_notInjected() {
        // The operator's hard rule: without a config value to compare against,
        // even a plausible-looking CI must be labelled 设备读数.
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(observedCi = 1L, configuredCi = null),
        )
    }

    // ---- no reading: no badge, no claim -----------------------------------------

    @Test
    fun noObservedCi_yieldsNoVerdict_nullBadge() {
        assertNull(CiHeroClassifier.classify(observedCi = null, configuredCi = 289001L))
        assertNull(CiHeroClassifier.classify(observedCi = null, configuredCi = null))
    }

    // ---- production probe honesty -------------------------------------------------

    @Test
    fun productionDiscoverProbe_returnsNull_configV1CarriesNoCellIdentity() {
        // Contract v1 discover() exposes profileRefs/scheduleRefs only — there is
        // no cell-identity field, so the production probe MUST answer null
        // (badge falls to 设备读数) instead of inventing a comparison.
        assertNull(DiscoverConfiguredCiProbe.configuredCi())
    }

    @Test
    fun badgeLabels_areTheOperatorAgreedWording() {
        assertEquals("注入", CiBadge.INJECTED.label)
        assertEquals("透传·真实", CiBadge.PASSTHROUGH_REAL.label)
        assertEquals("设备读数", CiBadge.DEVICE_READING.label)
    }
}

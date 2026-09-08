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
 * provider-asserted cellularHookConfigured discriminator; equality-based claims
 * additionally require an LTE reading — the configured group is the LTE-named
 * profile columns and the hook may be injecting NR from unprojected nr_* cols):
 *  - observed == configured && hookConfigured && RAT=LTE → INJECTED 注入
 *  - configured != null, otherwise (LTE)      → PASSTHROUGH_REAL 透传·真实
 *    (mismatch; or a coincidental equality with NO configured cellular group —
 *    nothing was injected, the reading is the real cell)
 *  - configured == null, OR observed RAT not LTE/unknown → DEVICE_READING 设备读数
 * plus the no-reading edge (no badge at all, never a fabricated state).
 *
 * # CI hero 徽标判定 oracle：三态穷尽 + 无读数不打徽标 + 宁"设备读数"不谎"注入" + 跨 RAT 不判等
 */
class CiHeroClassifierTest {

    // ---- the three exhaustive states -----------------------------------------

    @Test
    fun observedEqualsConfiguredWithHookConfigured_isInjected() {
        assertEquals(
            CiBadge.INJECTED,
            CiHeroClassifier.classify(
                observedCi = 289001L, configuredCi = 289001L, cellularHookConfigured = true,
                observedRat = "LTE",
            ),
        )
    }

    @Test
    fun configuredPresentButMismatches_isPassthroughReal_evenWhenHookConfigured() {
        assertEquals(
            CiBadge.PASSTHROUGH_REAL,
            CiHeroClassifier.classify(
                observedCi = 46692113L, configuredCi = 289001L, cellularHookConfigured = true,
                observedRat = "LTE",
            ),
        )
    }

    @Test
    fun configuredUnobtainable_isDeviceReading() {
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(
                observedCi = 46692113L, configuredCi = null, cellularHookConfigured = false,
                observedRat = "LTE",
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
                observedRat = "LTE",
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
                observedRat = "LTE",
            ),
        )
    }

    @Test
    fun unattestableCall_unknownDiscriminatorOrRat_staysWeakClaim() {
        // Fail-closed defaults: a caller that knows neither the hook
        // discriminator NOR the reading's RAT gets the weak 设备读数 claim.
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(observedCi = 289001L, configuredCi = 289001L),
        )
        // But an LTE reading with equal values and an UNKNOWN (default-false)
        // discriminator is still the frozen v1.81 透传·真实 leg — non-INJECTED,
        // never 注入, exactly like equalValuesWithoutConfiguredCellularGroup.
        assertEquals(
            CiBadge.PASSTHROUGH_REAL,
            CiHeroClassifier.classify(observedCi = 289001L, configuredCi = 289001L, observedRat = "LTE"),
        )
    }

    // ---- RAT fail-closed leg (review 2026-09-08): cross-RAT equality is no claim

    @Test
    fun nrObservedWithLteConfigured_isDeviceReading_neverPassthroughReal() {
        // The killing case: a mixed LTE+NR profile projects the LTE columns only,
        // but the hook ALSO injects NR identity from nci/nr_* columns and the
        // selector ranks NR above LTE — the displayed value can be an INJECTED
        // NCI. Claiming 透传·真实 for it would be a lie, so a non-LTE reading is
        // attested only as 设备读数. Mutation: drop the observedRat leg → red
        // (old code answers PASSTHROUGH_REAL here).
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(
                observedCi = 123456789012345L, configuredCi = 289001L,
                cellularHookConfigured = true, observedRat = "NR",
            ),
        )
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(
                // Equality ACROSS RATs is meaningless too: an NR reading equal to
                // the configured LTE ci must still never mint 注入.
                observedCi = 289001L, configuredCi = 289001L,
                cellularHookConfigured = true, observedRat = "NR",
            ),
        )
    }

    @Test
    fun unknownRatWithConfiguredPresent_isDeviceReading() {
        // Fail-closed default: a caller that cannot say which RAT produced the
        // reading gets the weak claim, never an equality-based one.
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(
                observedCi = 289001L, configuredCi = 289001L, cellularHookConfigured = true,
                observedRat = null,
            ),
        )
        assertEquals(
            CiBadge.DEVICE_READING,
            CiHeroClassifier.classify(
                observedCi = 289001L, configuredCi = 289001L,
                cellularHookConfigured = true, observedRat = "UNKNOWN",
            ),
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

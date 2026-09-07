package com.example.cellrebelauto.automation.selfheal

/**
 * P1.3 #3 — the pre-commit coordinate assertion (pure, JVM-testable).
 *
 * BEFORE a [com.example.cellrebelauto.model.ledger.TrustedQuotaEntry] may be minted, the provider-
 * observed effective coordinates (each observation's effectiveLat/effectiveLng) must sit within
 * ±[TOLERANCE_DEG] degrees (latitude and longitude independently) of the plan row's expected
 * coordinates. This is exactly the assertion the monitoring agent used to hand-run: the 52/51
 * profile-misalignment incident anchored correctly but pointed at the WRONG row's coordinates, and
 * the provider's own (misaligned) validation still passed — dozens of attempts burned quota while
 * PASS + QUOTA_COMMITTED kept firing. KB-8 made distance-to-intent provider-exclusive; P1.3
 * deliberately narrows that to the provider's own-target check and adds this ONE plan-vs-observation
 * cross-check at the mint boundary (design doc UNINSTALLED-CAPABILITIES §P1.3).
 *
 * # 配额 commit 前坐标校验：|观察坐标 − 计划期望坐标| ≤ 0.0002°（经纬各自）；pre/post 双相对称检查
 */
object CoordinateGuard {

    /** ±0.0002° both axes (~22 m latitude) — the agent's field-proven tolerance. */
    const val TOLERANCE_DEG: Double = 0.0002

    /**
     * The typed veto reason prefix recorded on the attempt's failure + unverified carrier.
     * A typed REASON, not a new terminal enum — the attempt still terminalizes failed/UNTRUSTED-shaped.
     */
    const val REASON: String = "ANCHOR_MISMATCH"

    /**
     * Returns null when every observation sits within tolerance, otherwise a typed reason naming the
     * offending phase, both deltas, and the expected/observed coordinates (human-readable on purpose:
     * the pause reason must let the operator see the one-row misalignment without a DB query).
     *
     * @param expectedLat / expectedLng the plan row's expected coordinates
     * @param observed  named (phase, lat, lng) triples — PRE first, POST second
     */
    fun violation(
        expectedLat: Double,
        expectedLng: Double,
        observed: List<Triple<String, Double?, Double?>>
    ): String? {
        for ((phase, lat, lng) in observed) {
            if (lat == null || lng == null) {
                return "$REASON:$phase:coordinates missing (lat=$lat lng=$lng)"
            }
            val deltaLat = kotlin.math.abs(lat - expectedLat)
            val deltaLng = kotlin.math.abs(lng - expectedLng)
            if (deltaLat > TOLERANCE_DEG || deltaLng > TOLERANCE_DEG) {
                return "$REASON:$phase:|Δlat|=$deltaLat |Δlng|=$deltaLng " +
                    "(tolerance $TOLERANCE_DEG) expected=($expectedLat,$expectedLng) " +
                    "observed=($lat,$lng) — profile/plan misalignment suspected"
            }
        }
        return null
    }
}

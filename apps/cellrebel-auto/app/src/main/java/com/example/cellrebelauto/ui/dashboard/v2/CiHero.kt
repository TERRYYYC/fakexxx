package com.example.cellrebelauto.ui.dashboard.v2

/**
 * T7v2 §A1-v2 #2 — the CI hero's data vocabulary.
 *
 * HARD RULE (operator, 2026-09-08): the hero's NUMBER is always the raw device
 * reading from TelephonyManager — this app never renders an invented or
 * configured value in the value slot. The BADGE is a semantic claim about that
 * reading's origin and must be conservative: a state we cannot PROVE is
 * rendered as 设备读数, never as 注入. The hero doubles as a chain-liveness
 * probe: if the hook chain breaks, the number visibly falls back to the real
 * cell and the badge says so.
 *
 * # CI hero 词汇表：值永远是设备真实读数；徽标宁可"设备读数"不可谎标"注入"
 */

/** The three badge states, in claim strength order. */
enum class CiBadge(val label: String) {
    /** observed == the effective profile's configured CI → injection live. */
    INJECTED("注入"),

    /** A configured CI is known but the observed value differs → passthrough. */
    PASSTHROUGH_REAL("透传·真实"),

    /** No configured CI obtainable → we can only attest it is a device reading. */
    DEVICE_READING("设备读数"),
}

/** One raw serving-cell reading, straight off [android.telephony.TelephonyManager]. */
data class ServingCellReading(
    /** "LTE" or "NR" (the RATs the worklist lane targets). */
    val rat: String,
    /** LTE CID / NR NCI. Null = the framework withheld it (coarse location). */
    val ci: Long?,
    val tac: Int?,
    val pci: Int?,
    val mcc: String?,
    val mnc: String?,
    /** LTE RSRP / NR SS-RSRP in dBm. */
    val rsrpDbm: Int?,
    /** True = serving cell (CellInfo.isRegistered). */
    val registered: Boolean,
    val readAtMs: Long,
)

/** Everything the CI hero card renders, pre-projected. */
data class CiHeroView(
    val reading: ServingCellReading?,
    val badge: CiBadge?,
) {
    /** Display form of the big number; "--" when the framework withheld the CI. */
    val ciText: String get() = reading?.ci?.toString() ?: "--"
}

/** The current plan point for the coordinate pill / map caption (plan truth, not GPS). */
data class CurrentPointView(
    val csvRow: Int,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Pure, exhaustive badge decision. Returns null ONLY when there is no observed
 * CI (nothing to badge — the UI renders a placeholder, never a fabricated state).
 *
 * Decision table (三态穷尽):
 *   observedCi == null            → null               (无读数，不打徽标)
 *   configuredCi == null          → DEVICE_READING     (配置不可得 → 只能证"设备读数")
 *   observedCi == configuredCi    → INJECTED           (观察值即档案配置值)
 *   otherwise                     → PASSTHROUGH_REAL   (配置可得但不等)
 */
object CiHeroClassifier {

    fun classify(observedCi: Long?, configuredCi: Long?): CiBadge? = when {
        observedCi == null -> null
        configuredCi == null -> CiBadge.DEVICE_READING
        observedCi == configuredCi -> CiBadge.INJECTED
        else -> CiBadge.PASSTHROUGH_REAL
    }
}

/**
 * Seam for the effective profile's configured CI (the value the provider's hook
 * would inject). Tests inject fakes; production is [DiscoverConfiguredCiProbe].
 */
fun interface ConfiguredCellIdentityProbe {
    fun configuredCi(): Long?
}

/**
 * Production probe over the EXISTING discover/contract channel. Contract v1's
 * discover() snapshot (CapabilitySnapshotV1) carries profileRefs/scheduleRefs
 * and schedule projection state ONLY — there is NO cell-identity field. Until a
 * contract revision exposes the profile's configured CI, this probe answers
 * null, which the classifier honestly renders as 设备读数. This is the operator's
 * mandated bias: an unprovable 注入 must never be claimed.
 */
object DiscoverConfiguredCiProbe : ConfiguredCellIdentityProbe {
    override fun configuredCi(): Long? = null
}

/** Picks the serving cell out of one getAllCellInfo() batch (pure). */
object ServingCellSelector {

    /**
     * Serving (registered) cells win; among registered candidates NR outranks
     * LTE (the newer RAT is what the worklist/hook lane targets). With NO
     * registered cell we still return the newest visible reading — a neighbour
     * cell is a REAL device reading and the hero renders it honestly rather
     * than showing nothing.
     */
    fun select(readings: List<ServingCellReading>): ServingCellReading? {
        if (readings.isEmpty()) return null
        return readings.filter { it.registered }
            .maxByOrNull { ratRank(it.rat) * RANK_STEP + it.readAtMs }
            ?: readings.maxByOrNull { it.readAtMs }
    }

    private const val RANK_STEP = 1_000_000L

    /** NR > LTE > anything else; order inside the batch must not matter. */
    private fun ratRank(rat: String): Long = when (rat.uppercase()) {
        "NR" -> 2L
        "LTE" -> 1L
        else -> 0L
    }
}

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
 * Decision table (三态穷尽, v1.81 wiring):
 *   observedCi == null                                   → null               (无读数，不打徽标)
 *   configuredCi == null                                 → DEVICE_READING     (配置不可得 → 只能证"设备读数")
 *   observedCi == configuredCi && cellularHookConfigured → INJECTED           (观察值即档案配置值，且蜂窝组确已配置)
 *   otherwise                                            → PASSTHROUGH_REAL   (配置可得但不等；或相等却无蜂窝配置 →
 *                                                                              相等只是巧合，读数仍是真实小区)
 *
 * [cellularHookConfigured] is the fail-closed leg: an INJECTED claim requires
 * the provider to attest a configured cellular group, so a lying or buggy
 * discover projection can never mint the strong claim by equality alone.
 */
object CiHeroClassifier {

    fun classify(observedCi: Long?, configuredCi: Long?, cellularHookConfigured: Boolean = false): CiBadge? =
        when {
            observedCi == null -> null
            configuredCi == null -> CiBadge.DEVICE_READING
            observedCi == configuredCi && cellularHookConfigured -> CiBadge.INJECTED
            else -> CiBadge.PASSTHROUGH_REAL
        }
}

/**
 * The effective profile's configured cellular identity as discover() projects
 * it (contract v1, v1.81 `configuredCell*` group), plus the provider-asserted
 * [cellularHookConfigured] discriminator (true iff any column carries a value).
 *
 * ATTESTATION-ONLY: never rendered as the hero's VALUE (that stays the raw
 * device reading), never read by TrustPolicy — badge semantics and durable
 * observation cross-checks are the only consumers.
 */
data class ConfiguredCellIdentity(
    val ci: Long?,
    val tac: Int?,
    val pci: Int?,
    val mcc: String?,
    val mnc: String?,
    /** discover().cellularHookConfigured — 蜂窝组任一字段有值 = true. */
    val cellularHookConfigured: Boolean,
)

/**
 * Seam for the effective profile's configured cellular identity (the values the
 * provider's hook would inject). Tests inject fakes; production is
 * [DiscoverConfiguredCellProbe].
 */
fun interface ConfiguredCellIdentityProbe {
    fun configuredCell(): ConfiguredCellIdentity?
}

/**
 * Production probe over the EXISTING discover/contract channel: one synchronous
 * handshake (v1.81 carries the `configuredCell*` group). Fail-closed end to end:
 * an unreachable provider, a refused handshake or ANY transport failure answers
 * null, which the classifier honestly renders as 设备读数 — never a fabricated
 * configuration, never a crash.
 */
class DiscoverConfiguredCellProbe(private val context: android.content.Context) :
    ConfiguredCellIdentityProbe {
    override fun configuredCell(): ConfiguredCellIdentity? = try {
        when (val result =
            com.example.cellrebelauto.integration.v1.EnvironmentControlClient(context).handshake()) {
            is com.example.cellrebelauto.integration.v1.EnvironmentControlClient.HandshakeResult.Connected -> {
                val snapshot = result.snapshot
                ConfiguredCellIdentity(
                    ci = snapshot.configuredCellCi,
                    tac = snapshot.configuredCellTac,
                    pci = snapshot.configuredCellPci,
                    mcc = snapshot.configuredCellMcc,
                    mnc = snapshot.configuredCellMnc,
                    cellularHookConfigured = snapshot.cellularHookConfigured,
                )
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
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

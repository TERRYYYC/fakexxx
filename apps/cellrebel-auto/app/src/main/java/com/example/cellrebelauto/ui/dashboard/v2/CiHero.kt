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
    /**
     * #190：当前计划行的期望 serving cell CI（location_tasks.expectedCi）；
     * null = 计划行未带 ci —— 小区卡不渲染对照行（只显示实测大数字）。
     */
    val expectedCi: Long? = null,
) {
    /** Display form of the big number; "--" when the framework withheld the CI. */
    val ciText: String get() = reading?.ci?.toString() ?: "--"

    /**
     * #190 期望 vs 实测对照（展示层）：期望缺失 → null（无对照行，实测已是大
     * 数字）；否则按 [CiVerification.of] 判定。绝入不了信任路径（#185 红线）。
     */
    val verification: CiVerification?
        get() = CiVerification.of(expectedCi, reading?.ci)
}

/**
 * #190 CI 验证层的展示词汇：期望（计划行 expectedCi）vs 实测（观察链采样 /
 * 小区卡实时读数）serving cell CI 的对照结论。判定 = 严格相等，零阈值零换算
 * （两侧同为 28-bit ECI 十进制，与 #193 读回门 ci 腿同域）。
 *
 * 三态穷尽（expectedCi == null 已在 [of] 收窄为 null，不进本枚举）：
 *   MATCHED    期望=实测（hook 生效的最直接展示证据）
 *   MISMATCHED 期望≠实测（hook 前/失效时的常态——正是本层要暴露的事实）
 *   UNMEASURED 实测未捕获（观察/读数缺小区字段）——显示"未捕获"，绝不猜测
 *
 * SCOPE RED LINE（同 #185）：纯展示投影，绝不入 TrustPolicy / 配额入账 /
 * 任务选择——信任语义的唯一输入仍是 §6.4 观察证据链本体。
 */
data class CiVerification(
    val state: State,
    val expectedCi: Long,
    val measuredCi: Long?,
) {
    enum class State { MATCHED, MISMATCHED, UNMEASURED }

    /** 展示文本：匹配 / 不匹配 / 实测未捕获。 */
    val verdictText: String
        get() = when (state) {
            State.MATCHED -> "匹配"
            State.MISMATCHED -> "不匹配"
            State.UNMEASURED -> "实测未捕获"
        }

    companion object {
        /** 期望缺失 → null（无对照行）；否则三态判定。 */
        fun of(expectedCi: Long?, measuredCi: Long?): CiVerification? = when {
            expectedCi == null -> null
            measuredCi == null -> CiVerification(State.UNMEASURED, expectedCi, null)
            expectedCi == measuredCi -> CiVerification(State.MATCHED, expectedCi, measuredCi)
            else -> CiVerification(State.MISMATCHED, expectedCi, measuredCi)
        }
    }
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
 *   observedRat not "LTE" (or unknown)                   → DEVICE_READING     (配置组是 LTE 语义列；NR 读数可能来自
 *                                                                              未投影的 nr_* 注入，互证不成立 → 只能证"设备读数")
 *   observedCi == configuredCi && cellularHookConfigured → INJECTED           (观察值即档案配置值，且蜂窝组确已配置)
 *   otherwise                                            → PASSTHROUGH_REAL   (配置可得但不等；或相等却无蜂窝配置 →
 *                                                                              相等只是巧合，读数仍是真实小区)
 *
 * [cellularHookConfigured] is the fail-closed leg: an INJECTED claim requires
 * the provider to attest a configured cellular group, so a lying or buggy
 * discover projection can never mint the strong claim by equality alone.
 *
 * [observedRat] is the second fail-closed leg (review 2026-09-08): the wire
 * group projects the LTE-named profile columns ONLY (v1.81 spec freeze), while
 * the hook also injects NR identity from the unprojected `nci`/`nr_*` columns
 * and [ServingCellSelector] ranks NR above LTE. An NR reading compared against
 * an LTE configured value would mislabel an injected value as 透传·真实, so any
 * non-LTE or unknown-RAT reading is attested only as 设备读数 — equality-based
 * claims (注入 AND 透传·真实 alike) require an LTE reading.
 */
object CiHeroClassifier {

    fun classify(
        observedCi: Long?,
        configuredCi: Long?,
        cellularHookConfigured: Boolean = false,
        observedRat: String? = null,
    ): CiBadge? = when {
        observedCi == null -> null
        configuredCi == null -> CiBadge.DEVICE_READING
        !observedRat.equals("LTE", ignoreCase = true) -> CiBadge.DEVICE_READING
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

package name.caiyao.fakegps.verify

import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType

/**
 * Outcome for a single field: did the value the user configured actually reach the app?
 *
 * [PASSTHROUGH] and [UNOBSERVABLE] are deliberately NOT failures. Reporting either as one would
 * train the user to ignore the screen — passthrough is the project's core invariant working as
 * designed, and an unreadable API is a device limitation, not evidence about the hook.
 */
enum class FieldVerdict {
    /** Configured, and the app reads back exactly that value. The hook works for this field. */
    SPOOFED,

    /** Observed equals configured, but the real baseline is identical, so this proves nothing. */
    AMBIGUOUS,

    /** Configured, but the app reads back something else — the chain is broken for this field. */
    MISMATCH,

    /** Configured, but this process cannot read the field at all. No evidence either way. */
    UNOBSERVABLE,

    /**
     * Configured, but NO Android getter exists for it in any app — a module control knob such as
     * signal_fluctuation_*. Distinct from [UNOBSERVABLE], which is a device/API limitation the user
     * could work around on other hardware; this one is unverifiable everywhere, by design.
     *
     * Kept separate so the row chip and the summary counter agree. Reusing UNOBSERVABLE made rows
     * render "读不到" while the summary printed "读不到 0".
     */
    NOT_VERIFIABLE,

    /**
     * Not configured by the user, but produced by an object-level hook that replaces the whole
     * public object. For example, latitude + longitude create a new Location whose unset accuracy,
     * altitude, speed and bearing expose constructor defaults rather than the real device values.
     * This is informational: it is neither configured evidence nor passthrough.
     */
    GROUP_DERIVED,

    /** Not configured, so the app correctly sees the real device value. */
    PASSTHROUGH,
}

/** Screen-level roll-up of [FieldVerdict]s. */
enum class VerificationStatus {
    /** Every configured field was observed back. */
    EFFECTIVE,

    /** Some fields confirmed, others unreadable or unverifiable — real evidence, but incomplete. */
    PARTIALLY_EFFECTIVE,

    /**
     * Only fields that no getter can report were configured (module control knobs). Nothing could
     * be checked, but the profile is definitely not empty.
     */
    CONFIGURED_UNVERIFIABLE,

    /** At least one configured field was observed with a different value. */
    FAILING,

    /**
     * Fields mismatch, but the config was published so recently that the hook may not have re-read
     * it yet. Reporting failure here would blame the hook for being one refresh tick behind —
     * right after saving, which is exactly when users check.
     */
    PENDING_PROPAGATION,

    /** Fields are configured but none could be observed — cannot conclude anything. */
    INCONCLUSIVE,

    /** The effective profile configures nothing, so there is nothing to verify. */
    NOTHING_CONFIGURED,
}

data class FieldReport(
    val spec: FieldSpec,
    /** Value carried by the published transport payload, i.e. what the hook actually receives. */
    val configured: String?,
    /** Value this process reads back through the same public API a target app would call. */
    val observed: String?,
    /**
     * The device's REAL value, when it is knowable. Populated instead of [observed] whenever this
     * process is not itself hooked, so the screen can still show the user what the real network
     * looks like — which is what makes "choose a value that differs from it" actionable.
     */
    val baseline: String? = null,
    val verdict: FieldVerdict,
    /**
     * True when the configured value equals the device's real value, so the field can never prove
     * anything: "observed == configured" is indistinguishable from the hook doing nothing.
     *
     * Computed only when the caller supplies a genuine baseline. Debug verification obtains one by
     * entering [name.caiyao.fakegps.hook.BaselineExtractionGuard], which makes this module's getter
     * hooks yield to the underlying framework values for that narrow synchronous read.
     */
    val ambiguous: Boolean = false,
)

data class CategoryReport(
    val category: String,
    val fields: List<FieldReport>,
)

data class VerificationSummary(
    val spoofed: Int,
    val mismatch: Int,
    val unobservable: Int,
    val passthrough: Int,
    /** Unconfigured values synthesized by an active object-level replacement group. */
    val groupDerived: Int = 0,
    /**
     * Configured fields that no Android getter can ever report (module control knobs such as
     * signal_fluctuation_*). Distinct from [unobservable], which is a device/API limitation: these
     * are unverifiable BY DESIGN, so they are not evidence — but they ARE configured, and the hook
     * does apply them.
     */
    val notVerifiable: Int = 0,
    /** Matching rows whose real baseline is identical to the configured value. */
    val ambiguous: Int = 0,
    /** True while the hook may still be serving the previous config. See [VerificationStatus.PENDING_PROPAGATION]. */
    val propagationPending: Boolean = false,
) {
    /** Everything the user actually configured, verifiable or not. */
    val configuredCount: Int get() = spoofed + mismatch + unobservable + notVerifiable + ambiguous

    val status: VerificationStatus
        get() = when {
            configuredCount == 0 -> VerificationStatus.NOTHING_CONFIGURED
            // A mismatch inside the propagation window is more likely staleness than failure, so
            // it must not be announced as one.
            mismatch > 0 && propagationPending -> VerificationStatus.PENDING_PROPAGATION
            mismatch > 0 -> VerificationStatus.FAILING
            // Only module knobs configured: nothing was checkable, but saying "没有配置任何字段"
            // would contradict both the payload and the hook.
            spoofed == 0 && unobservable == 0 && ambiguous == 0 -> VerificationStatus.CONFIGURED_UNVERIFIABLE
            spoofed == 0 -> VerificationStatus.INCONCLUSIVE
            // Confirming one field out of eleven is not "伪装生效". Announcing full success while
            // part of the config is unverified is the same overclaim this screen exists to remove.
            unobservable > 0 || notVerifiable > 0 || ambiguous > 0 -> VerificationStatus.PARTIALLY_EFFECTIVE
            else -> VerificationStatus.EFFECTIVE
        }
}

data class VerificationReport(
    val groups: List<CategoryReport>,
    val summary: VerificationSummary,
    /** Number of fields present in the published payload — the hook's actual input size. */
    val payloadFieldCount: Int,
    /**
     * Payload columns with no [FieldSpec] row. These reach the hook but have no UI, so they can
     * neither be configured nor verified; surfacing the count keeps that drift visible instead of
     * letting it rot silently the way 64 of 87 columns once did.
     */
    val unmappedPayloadColumns: List<String>,
)

/**
 * Reconciles what was configured against what the app can actually observe.
 *
 * The two sides are sourced independently and joined on the profile table's column name:
 *
 *   configured — read back from the WORLD-READABLE payload the hook consumes, NOT from the DB row.
 *                Reading the DB would verify only that the UI saved something; reading the payload
 *                also proves the transport carried it, which is precisely where fields were lost
 *                before (23 of 87 columns).
 *   observed   — read through the same public Android APIs a target app calls.
 *
 * Joining on [FieldSpec.dbColumn] means there is no per-field comparison code: a new column is
 * covered the moment it appears in the spec, so verification cannot drift out of sync with the
 * field model the way hand-maintained mappings did.
 */
object VerificationEngine {

    /**
     * Columns that reach the hook but that no Android getter can ever report back.
     *
     * `signal_fluctuation_*` are module control knobs, and `addname` is a display label regenerated
     * on every save. Filing them under 读不到 would drag an otherwise-conclusive screen to
     * INCONCLUSIVE and keep a transport-drift alarm permanently lit — and an alarm that is always on
     * is one nobody reads.
     */
    private val NOT_DEVICE_OBSERVABLE = setOf(
        "signal_fluctuation_enabled",
        "signal_fluctuation_range_db",
        "neighbor_cells_json",
        "addname",
    )

    /**
     * Columns served by HookUtils#hookSignal, which pipes every value through
     * [name.caiyao.fakegps.hook.Snapshot.fluctuate] before returning it.
     *
     * Only these may be compared against a fluctuation window: cell identity (tac/ci/mcc…) is
     * returned verbatim, so relaxing it would let a genuinely broken identity spoof pass.
     */
    private val FLUCTUATING_SIGNAL_COLUMNS = setOf(
        "gsm_rssi", "gsm_ber", "gsm_ta",
        "wcdma_rscp", "wcdma_rssi", "wcdma_ecno",
        "lte_rsrp", "lte_rsrq", "lte_sinr", "lte_cqi", "lte_ta", "lte_rssi",
        "nr_ss_rsrp", "nr_ss_rsrq", "nr_ss_sinr",
        "nr_csi_rsrp", "nr_csi_rsrq", "nr_csi_sinr",
    )

    /**
     * A configured latitude/longitude pair activates HookUtils#createFakeLocation, which returns a
     * new Location object. These unset sibling getters therefore expose group defaults rather than
     * the underlying device values and must not be advertised as passthrough.
     */
    private val LOCATION_GROUP_DERIVED_COLUMNS = setOf(
        "altitude", "speed", "bearing", "accuracy",
    )

    /**
     * The interval a configured signal value can legitimately be observed within.
     *
     * Mirrors `Snapshot.fluctuate`: `base + rnd.nextInt(range + 1) - range / 2`, i.e.
     * `[base - range/2, base + range - range/2]` with integer division. Derived from the payload's
     * own fluctuation settings, so it cannot drift from what the hook will actually emit.
     */
    internal fun fluctuationWindow(configured: Map<String, String>): IntRange? {
        val enabled = configured["signal_fluctuation_enabled"]?.trim()
        val on = enabled == "1" || enabled.equals("true", ignoreCase = true)
        if (!on) return null
        val range = configured["signal_fluctuation_range_db"]?.trim()?.toIntOrNull() ?: return null
        if (range <= 0) return null   // fluctuate() perturbs nothing unless range > 0
        val half = range / 2
        return -half..(range - half)
    }

    fun buildReport(
        configured: Map<String, String>,
        unavailable: Set<String> = emptySet(),
        observed: Map<String, String>,
        baseline: Map<String, String> = emptyMap(),
        propagationPending: Boolean = false,
        specs: LinkedHashMap<String, List<FieldSpec>> = FieldSpec.allCategories(),
    ): VerificationReport {
        val groups = mutableListOf<CategoryReport>()
        var spoofed = 0
        var mismatch = 0
        var unobservable = 0
        var notVerifiable = 0
        var ambiguousCount = 0
        var groupDerived = 0
        val window = fluctuationWindow(configured)
        var passthrough = 0
        val mappedColumns = mutableSetOf<String>()
        val locationGroupActive =
            !configured["latitude"].isNullOrBlank() && !configured["longitude"].isNullOrBlank()

        for ((category, fields) in specs) {
            val rows = mutableListOf<FieldReport>()
            for (spec in fields) {
                mappedColumns += spec.dbColumn
                val cfg = if (spec.dbColumn in unavailable) {
                    "--"
                } else {
                    configured[spec.dbColumn]?.takeIf { it.isNotBlank() }
                }
                val obs = observed[spec.dbColumn]?.takeIf { it.isNotBlank() }
                val real = baseline[spec.dbColumn]?.takeIf { it.isNotBlank() }

                // Nothing configured, nothing read, nothing known: no information to convey.
                // Rendering these would bury the informative rows under ~80 empty ones.
                if (cfg == null && obs == null && real == null) continue

                // A field no getter can report is not evidence either way, so it must not sway the
                // verifiable counters — but if the user configured it, it still counts as
                // configured, or the screen would claim nothing is being spoofed.
                val moduleKnob = spec.dbColumn in NOT_DEVICE_OBSERVABLE
                if (moduleKnob && cfg == null) continue   // nothing configured, nothing to say
                val derivedByLocationGroup = cfg == null && locationGroupActive &&
                    spec.dbColumn in LOCATION_GROUP_DERIVED_COLUMNS

                val ambiguous = cfg != null && real != null && canonicalValuesMatch(spec, cfg, real)
                val verdict = when {
                    moduleKnob -> FieldVerdict.NOT_VERIFIABLE
                    derivedByLocationGroup -> FieldVerdict.GROUP_DERIVED
                    cfg == null -> FieldVerdict.PASSTHROUGH
                    obs == null -> FieldVerdict.UNOBSERVABLE
                    ambiguous && fieldMatches(spec, cfg, obs, window) -> FieldVerdict.AMBIGUOUS
                    fieldMatches(spec, cfg, obs, window) -> FieldVerdict.SPOOFED
                    else -> FieldVerdict.MISMATCH
                }

                when (verdict) {
                    FieldVerdict.SPOOFED -> spoofed++
                    FieldVerdict.AMBIGUOUS -> ambiguousCount++
                    FieldVerdict.MISMATCH -> mismatch++
                    FieldVerdict.UNOBSERVABLE -> unobservable++
                    FieldVerdict.PASSTHROUGH -> passthrough++
                    FieldVerdict.NOT_VERIFIABLE -> notVerifiable++
                    FieldVerdict.GROUP_DERIVED -> groupDerived++
                }

                rows += FieldReport(
                    spec = spec,
                    configured = cfg,
                    observed = obs,
                    baseline = real,
                    verdict = verdict,
                    ambiguous = ambiguous,
                )
            }
            if (rows.isNotEmpty()) groups += CategoryReport(category, rows)
        }

        return VerificationReport(
            groups = groups,
            summary = VerificationSummary(
                spoofed = spoofed,
                mismatch = mismatch,
                unobservable = unobservable,
                passthrough = passthrough,
                groupDerived = groupDerived,
                notVerifiable = notVerifiable,
                ambiguous = ambiguousCount,
                propagationPending = propagationPending,
            ),
            payloadFieldCount = configured.size + unavailable.size,
            unmappedPayloadColumns = (configured.keys + unavailable)
                .filterNot { it in mappedColumns || it in NOT_DEVICE_OBSERVABLE }
                .sorted(),
        )
    }

    /**
     * Whether [observed] is a value the hook could have produced from [configured] for this field.
     *
     * The tolerance is bounded by [FieldSpec.type] and concrete hook behaviour:
     *
     *  - TEXT stays textual; only `wifi_ssid` removes Android's wrapper quotes.
     *  - BOOLEAN accepts SQLite `0/1` against Android `false/true`.
     *  - Numeric fields accept transport formatting such as `5.0` against getter `5`.
     *  - MCC/MNC additionally accept Android's canonical PLMN width (`0` against `00`).
     *  - FLOAT columns round-trip Float → SQLite REAL → JSON double, so a configured `0.1f` is
     *    published as `0.10000000149011612` while the getter reports `0.1`. Comparing as Float
     *    collapses exactly that artefact and nothing else.
     *  - Signal columns pass through `Snapshot.fluctuate`, so with fluctuation enabled the hook
     *    deliberately returns a randomised value inside a known window.
     *
     * PLMN identity strings use [canonicalValuesMatch]; other identity columns stay exact.
     */
    internal fun fieldMatches(
        spec: FieldSpec,
        configured: String,
        observed: String,
        window: IntRange?,
    ): Boolean {
        if (canonicalValuesMatch(spec, configured, observed)) return true

        if (spec.type == FieldType.FLOAT) {
            val a = configured.trim().toFloatOrNull()
            val b = observed.trim().toFloatOrNull()
            if (a != null && b != null) return a == b
        }

        if (window != null && spec.dbColumn in FLUCTUATING_SIGNAL_COLUMNS) {
            val base = configured.trim().toIntOrNull()
            val seen = observed.trim().toIntOrNull()
            if (base != null && seen != null) return (seen - base) in window
        }

        return false
    }

    private fun canonicalValuesMatch(
        spec: FieldSpec,
        configured: String,
        observed: String,
    ): Boolean {
        val configuredValue = normalizeTextContract(spec, configured)
        val observedValue = normalizeTextContract(spec, observed)
        if (configuredValue == observedValue) return true

        if (spec.dbColumn == "mcc" || spec.dbColumn == "mnc") {
            val configuredNumber = configuredValue.toIntOrNull()
            val observedNumber = observedValue.toIntOrNull()
            if (configuredNumber != null && observedNumber != null) {
                return configuredNumber == observedNumber
            }
        }

        return when (spec.type) {
            FieldType.TEXT -> false
            FieldType.BOOLEAN -> {
                val a = asBoolean(configuredValue)
                val b = asBoolean(observedValue)
                a != null && b != null && a == b
            }
            FieldType.INTEGER, FieldType.DOUBLE, FieldType.FLOAT -> {
                if (isZeroPadded(configuredValue) || isZeroPadded(observedValue)) return false
                val a = configuredValue.toBigDecimalOrNull()
                val b = observedValue.toBigDecimalOrNull()
                a != null && b != null && a.compareTo(b) == 0
            }
        }
    }

    /** WifiInfo#getSSID is the only observed text contract here that adds wrapper quotes. */
    private fun normalizeTextContract(spec: FieldSpec, value: String): String {
        val trimmed = value.trim()
        return if (spec.dbColumn == "wifi_ssid") unquote(trimmed) else trimmed
    }

    /** Shape-tolerant collision hint for editor inputs, where no [FieldSpec] is available. */
    internal fun valuesMatch(configured: String, observed: String): Boolean {
        val a = unquote(configured.trim())
        val b = unquote(observed.trim())
        if (a == b) return true

        asBoolean(a)?.let { ba -> asBoolean(b)?.let { bb -> return ba == bb } }

        // Zero padding is never something the hook produces, so it is signal, not noise.
        if (isZeroPadded(a) || isZeroPadded(b)) return false

        val na = a.toDoubleOrNull()
        val nb = b.toDoubleOrNull()
        if (na != null && nb != null) return na == nb

        return false
    }

    /**
     * `WifiInfo#getSSID` wraps its result in double quotes and the hook reproduces that contract,
     * while the configured column stores plain text. Without this, a correctly spoofed SSID reads
     * as 未生效 on every single refresh.
     */
    private fun unquote(v: String): String =
        if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v

    /** `"00"`, `"007"` — significant, because `String.valueOf` cannot generate it. `"0.5"` is not. */
    private fun isZeroPadded(v: String): Boolean {
        val s = v.removePrefix("-")
        return s.length > 1 && s[0] == '0' && s[1] != '.'
    }

    /** `1`/`0` (SQLite) and `true`/`false` (Android getters) denote the same boolean. */
    private fun asBoolean(v: String): Boolean? = when (v.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}

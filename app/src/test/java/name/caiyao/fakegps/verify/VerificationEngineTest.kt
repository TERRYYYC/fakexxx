package name.caiyao.fakegps.verify

import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the verify screen's reconciliation logic.
 *
 * The screen answers ONE question per field — "did the value I configured actually reach the app?"
 * — by joining two independently-sourced maps on the profile table's column name:
 *   configured := the published transport payload (what the hook reads), NOT the DB row
 *   observed   := what this process reads back through the same public Android APIs a target app uses
 *
 * Joining on dbColumn keeps this generic: no per-field comparison code, so a new column can never
 * silently drop out of verification the way it did from the transport (23 of 87 columns carried).
 */
class VerificationEngineTest {

    private val lteSpecs = linkedMapOf(
        "小区标识 - LTE" to listOf(
            FieldSpec("tac", "TAC", "", FieldType.INTEGER),
            FieldSpec("ci", "CI", "", FieldType.INTEGER),
            FieldSpec("pci", "PCI", "", FieldType.INTEGER),
        ),
    )

    // ---- per-field verdicts -----------------------------------------------------------------

    @Test
    fun `configured value observed back verbatim is SPOOFED`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "26999"),
            observed = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("tac").verdict)
    }

    @Test
    fun `configured value observed as something else is MISMATCH`() {
        // The exact shape of the original bug: profile said ci=99999, the app still saw the real cell.
        val r = VerificationEngine.buildReport(
            configured = mapOf("ci" to "99999"),
            observed = mapOf("ci" to "28378431"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("ci").verdict)
    }

    @Test
    fun `configured but the API returned nothing is UNOBSERVABLE not MISMATCH`() {
        // getAllCellInfo() returns an empty list on some devices/ROMs. That is not evidence the
        // hook failed, so it must not be reported as a failure.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "26999"),
            observed = emptyMap(),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.UNOBSERVABLE, r.field("tac").verdict)
    }

    @Test
    fun `explicit unavailable observed through platform empty form is SPOOFED`() {
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            unavailable = setOf("tac"),
            observed = mapOf("tac" to "--"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("tac").verdict)
        assertEquals("--", r.field("tac").configured)
    }

    @Test
    fun `explicit unavailable returning a real value is MISMATCH`() {
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            unavailable = setOf("tac"),
            observed = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("tac").verdict)
    }

    @Test
    fun `unconfigured field showing a real value is PASSTHROUGH not a failure`() {
        // NULL = passthrough is the project's core invariant; seeing the real value here is correct.
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            observed = mapOf("pci" to "53"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.PASSTHROUGH, r.field("pci").verdict)
    }

    @Test
    fun `location group defaults are derived rather than passthrough`() {
        val locationSpecs = linkedMapOf(
            "定位" to listOf(
                FieldSpec("latitude", "纬度", "", FieldType.DOUBLE),
                FieldSpec("longitude", "经度", "", FieldType.DOUBLE),
                FieldSpec("altitude", "海拔", "", FieldType.DOUBLE),
                FieldSpec("speed", "速度", "", FieldType.FLOAT),
                FieldSpec("bearing", "方向", "", FieldType.FLOAT),
                FieldSpec("accuracy", "精度", "", FieldType.FLOAT),
            ),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("latitude" to "50.45", "longitude" to "30.52"),
            observed = mapOf(
                "latitude" to "50.45",
                "longitude" to "30.52",
                "altitude" to "0.0",
                "speed" to "0.0",
                "bearing" to "0.0",
                "accuracy" to "10.0",
            ),
            baseline = mapOf(
                "latitude" to "50.40",
                "longitude" to "30.60",
                "altitude" to "184.0",
                "speed" to "1.5",
                "bearing" to "91.0",
                "accuracy" to "4.0",
            ),
            specs = locationSpecs,
        )

        for (field in listOf("altitude", "speed", "bearing", "accuracy")) {
            assertEquals(FieldVerdict.GROUP_DERIVED, r.field(field).verdict)
        }
        assertEquals(4, r.summary.groupDerived)
        assertEquals(0, r.summary.passthrough)
        assertEquals(2, r.summary.configuredCount)
        assertEquals(VerificationStatus.EFFECTIVE, r.summary.status)
    }

    @Test
    fun `field that is neither configured nor observed is omitted entirely`() {
        // Listing 87 rows of "null / null" would bury the handful of rows that carry information.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("tac" to "1"),
            specs = lteSpecs,
        )
        assertEquals(listOf("tac"), r.allFields().map { it.spec.dbColumn })
    }

    // ---- value normalisation ----------------------------------------------------------------

    @Test
    fun `integer column compares equal across transport and API string forms`() {
        // mcc is an INTEGER column; CellIdentity exposes it as mccString. Same value, two shapes.
        val specs = linkedMapOf("x" to listOf(FieldSpec("mcc", "MCC", "", FieldType.INTEGER)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("mcc" to "460"),
            observed = mapOf("mcc" to "460"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("mcc").verdict)
    }

    @Test
    fun `PLMN string width is normalized only for MCC and MNC fields`() {
        val specs = linkedMapOf("x" to listOf(FieldSpec("mnc", "MNC", "", FieldType.INTEGER)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("mnc" to "0"),
            observed = mapOf("mnc" to "00"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("mnc").verdict)

        val mccSpecs = linkedMapOf("x" to listOf(FieldSpec("mcc", "MCC", "", FieldType.INTEGER)))
        val mcc = VerificationEngine.buildReport(
            configured = mapOf("mcc" to "46"),
            observed = mapOf("mcc" to "046"),
            specs = mccSpecs,
        )
        assertEquals(FieldVerdict.SPOOFED, mcc.field("mcc").verdict)
    }

    @Test
    fun `zero-padded real MNC stays AMBIGUOUS instead of proving the hook`() {
        val specs = linkedMapOf("x" to listOf(FieldSpec("mnc", "MNC", "", FieldType.INTEGER)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("mnc" to "3"),
            observed = mapOf("mnc" to "03"),
            baseline = mapOf("mnc" to "03"),
            specs = specs,
        )
        assertEquals(FieldVerdict.AMBIGUOUS, r.field("mnc").verdict)
    }

    @Test
    fun `trailing decimal formatting differences still match`() {
        // REAL columns round-trip through JSON as 5.0 while the getter reports "5" (or vice versa).
        // That IS pure formatting and must not be reported as a failure.
        val specs = linkedMapOf("x" to listOf(FieldSpec("altitude", "海拔", "", FieldType.DOUBLE)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("altitude" to "5.0"),
            observed = mapOf("altitude" to "5"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("altitude").verdict)
    }

    @Test
    fun `wifi ssid quoting is normalised because the platform wraps it in quotes`() {
        // WifiInfo#getSSID returns the SSID wrapped in double quotes, and the hook reproduces that
        // contract ("\"" + ssid + "\""). The configured column is plain text, so comparing verbatim
        // makes a correctly-spoofed SSID read as 未生效 every single time.
        val specs = linkedMapOf("x" to listOf(FieldSpec("wifi_ssid", "SSID", "", FieldType.TEXT)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("wifi_ssid" to "HomeNet"),
            observed = mapOf("wifi_ssid" to "\"HomeNet\""),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("wifi_ssid").verdict)
    }

    @Test
    fun `a genuinely different quoted ssid is still a mismatch`() {
        val specs = linkedMapOf("x" to listOf(FieldSpec("wifi_ssid", "SSID", "", FieldType.TEXT)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("wifi_ssid" to "HomeNet"),
            observed = mapOf("wifi_ssid" to "\"CafeWiFi\""),
            specs = specs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("wifi_ssid").verdict)
    }

    @Test
    fun `a module-control field gets its own verdict so the row matches the summary`() {
        // review 4822242223 P1: these fields were given FieldVerdict.UNOBSERVABLE, so each row
        // rendered a "读不到" chip while the summary counted them under notVerifiable and printed
        // "读不到 0". The same screen contradicted itself.
        val specs = linkedMapOf(
            "信号波动" to listOf(FieldSpec("signal_fluctuation_enabled", "启用波动", "", FieldType.BOOLEAN)),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("signal_fluctuation_enabled" to "1"),
            observed = emptyMap(),
            specs = specs,
        )
        assertEquals(FieldVerdict.NOT_VERIFIABLE, r.field("signal_fluctuation_enabled").verdict)
        assertEquals(1, r.summary.notVerifiable)
        assertEquals(0, r.summary.unobservable)
    }

    @Test
    fun `an unconfigured module-control field is omitted entirely`() {
        val specs = linkedMapOf(
            "信号波动" to listOf(FieldSpec("signal_fluctuation_enabled", "启用波动", "", FieldType.BOOLEAN)),
            "x" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("tac" to "1"),
            specs = specs,
        )
        assertEquals(listOf("tac"), r.allFields().map { it.spec.dbColumn })
        assertEquals(0, r.summary.notVerifiable)
    }

    @Test
    fun `configuring only module-control fields is not reported as configuring nothing`() {
        // review 4822122472 P1: signal_fluctuation_* is excluded from EVIDENCE because no getter can
        // report it — but it IS configured, rides in the payload, and the hook applies it. Excluding
        // it from the configured count too made the screen say "当前档案没有配置任何字段 /
        // 所有字段都会透传", contradicting both the payload card and the hook's actual behaviour.
        val specs = linkedMapOf(
            "信号波动" to listOf(
                FieldSpec("signal_fluctuation_enabled", "启用波动", "", FieldType.BOOLEAN),
                FieldSpec("signal_fluctuation_range_db", "波动范围", "", FieldType.INTEGER),
            ),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("signal_fluctuation_enabled" to "1", "signal_fluctuation_range_db" to "5"),
            observed = emptyMap(),
            specs = specs,
        )
        assertEquals(2, r.summary.notVerifiable)
        assertEquals(0, r.summary.unobservable)   // not a device limitation — by design unreadable
        assertEquals(VerificationStatus.CONFIGURED_UNVERIFIABLE, r.summary.status)
    }

    @Test
    fun `module-control fields alongside a verified field downgrade EFFECTIVE to partial`() {
        // Claiming outright success while part of the config can never be checked is the same
        // overclaim as ignoring `unobservable`.
        val specs = linkedMapOf(
            "x" to listOf(
                FieldSpec("tac", "TAC", "", FieldType.INTEGER),
                FieldSpec("signal_fluctuation_enabled", "启用波动", "", FieldType.BOOLEAN),
            ),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "signal_fluctuation_enabled" to "1"),
            observed = mapOf("tac" to "1"),
            specs = specs,
        )
        assertEquals(1, r.summary.spoofed)
        assertEquals(1, r.summary.notVerifiable)
        assertEquals(VerificationStatus.PARTIALLY_EFFECTIVE, r.summary.status)
    }

    @Test
    fun `module control fields are not miscounted as a device limitation`() {
        // signal_fluctuation_* is a module knob, not something any Android getter reports. Filing it
        // under `unobservable` would blame the device for a field no device could ever expose, and
        // would drag the screen to INCONCLUSIVE.
        //
        // It is still COUNTED AS CONFIGURED (see notVerifiable) — the earlier version of this test
        // asserted EFFECTIVE here, which was the overclaim review 4822122472 caught: part of the
        // config had not been checked at all.
        val specs = linkedMapOf(
            "信号波动" to listOf(
                FieldSpec("signal_fluctuation_enabled", "启用波动", "", FieldType.BOOLEAN),
                FieldSpec("tac", "TAC", "", FieldType.INTEGER),
            ),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("signal_fluctuation_enabled" to "1", "tac" to "1"),
            observed = mapOf("tac" to "1"),
            specs = specs,
        )
        assertEquals(0, r.summary.unobservable)
        assertEquals(1, r.summary.notVerifiable)
        assertEquals(2, r.summary.configuredCount)
    }

    @Test
    fun `auto-generated addname is not reported as transport drift`() {
        // addname is written on every save and rides in every payload. A drift alarm that is always
        // on is a drift alarm nobody reads.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "addname" to "50.2, 28.6"),
            observed = mapOf("tac" to "1"),
            specs = lteSpecs,
        )
        assertEquals(emptyList<String>(), r.unmappedPayloadColumns)
    }

    @Test
    fun `boolean column matches across numeric and literal forms`() {
        // is_roaming is stored 0/1 but TelephonyManager#isNetworkRoaming returns false/true.
        val specs = linkedMapOf("x" to listOf(FieldSpec("is_roaming", "漫游", "", FieldType.BOOLEAN)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("is_roaming" to "1"),
            observed = mapOf("is_roaming" to "true"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("is_roaming").verdict)
    }

    @Test
    fun `text column is compared verbatim because the hook returns it unchanged`() {
        // The hook hands back the configured string as-is, so any difference means the value did
        // NOT come from us — case included.
        val specs = linkedMapOf("x" to listOf(FieldSpec("operator_name", "运营商", "", FieldType.TEXT)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("operator_name" to "CMCC"),
            observed = mapOf("operator_name" to "cmcc"),
            specs = specs,
        )
        assertEquals(FieldVerdict.MISMATCH, r.field("operator_name").verdict)
    }

    @Test
    fun `text column does not borrow boolean or numeric coercion`() {
        val specs = linkedMapOf("x" to listOf(FieldSpec("operator_name", "运营商", "", FieldType.TEXT)))
        for (observed in listOf("true", "1.0", "\"1\"")) {
            val r = VerificationEngine.buildReport(
                configured = mapOf("operator_name" to "1"),
                observed = mapOf("operator_name" to observed),
                specs = specs,
            )
            assertEquals(FieldVerdict.MISMATCH, r.field("operator_name").verdict)
        }
    }

    @Test
    fun `surrounding whitespace does not create a false mismatch`() {
        val specs = linkedMapOf("x" to listOf(FieldSpec("operator_name", "运营商", "", FieldType.TEXT)))
        val r = VerificationEngine.buildReport(
            configured = mapOf("operator_name" to "CMCC "),
            observed = mapOf("operator_name" to "CMCC"),
            specs = specs,
        )
        assertEquals(FieldVerdict.SPOOFED, r.field("operator_name").verdict)
    }

    // ---- summary ----------------------------------------------------------------------------

    @Test
    fun `summary counts each verdict so the header can state the outcome in one line`() {
        val specs = linkedMapOf(
            "c" to listOf(
                FieldSpec("tac", "TAC", "", FieldType.INTEGER),
                FieldSpec("ci", "CI", "", FieldType.INTEGER),
                FieldSpec("pci", "PCI", "", FieldType.INTEGER),
                FieldSpec("earfcn", "EARFCN", "", FieldType.INTEGER),
            ),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2", "pci" to "3"),
            observed = mapOf("tac" to "1", "ci" to "999", "earfcn" to "1850"),
            specs = specs,
        )
        assertEquals(1, r.summary.spoofed)       // tac
        assertEquals(1, r.summary.mismatch)      // ci
        assertEquals(1, r.summary.unobservable)  // pci
        assertEquals(1, r.summary.passthrough)   // earfcn
    }

    @Test
    fun `overall status is FAILING when any configured field did not take effect`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2"),
            observed = mapOf("tac" to "1", "ci" to "999"),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.FAILING, r.summary.status)
    }

    @Test
    fun `overall status is EFFECTIVE when every configured field was observed back`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2"),
            observed = mapOf("tac" to "1", "ci" to "2"),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.EFFECTIVE, r.summary.status)
    }

    @Test
    fun `some fields verified and others unreadable is PARTIALLY_EFFECTIVE not EFFECTIVE`() {
        // review P1-3: one matching field among ten unreadable ones is not "伪装生效". Claiming full
        // success while most of the config is unverified is exactly the overclaim this screen exists
        // to eliminate.
        val specs = linkedMapOf(
            "c" to listOf(
                FieldSpec("tac", "TAC", "", FieldType.INTEGER),
                FieldSpec("ci", "CI", "", FieldType.INTEGER),
                FieldSpec("pci", "PCI", "", FieldType.INTEGER),
            ),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2", "pci" to "3"),
            observed = mapOf("tac" to "1"),
            specs = specs,
        )
        assertEquals(1, r.summary.spoofed)
        assertEquals(2, r.summary.unobservable)
        assertEquals(VerificationStatus.PARTIALLY_EFFECTIVE, r.summary.status)
    }

    @Test
    fun `EFFECTIVE requires every configured field to be confirmed`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2"),
            observed = mapOf("tac" to "1", "ci" to "2"),
            specs = lteSpecs,
        )
        assertEquals(0, r.summary.unobservable)
        assertEquals(VerificationStatus.EFFECTIVE, r.summary.status)
    }

    @Test
    fun `mismatch right after saving is PENDING_PROPAGATION not FAILING`() {
        // review P1-1: ConfigPrefsSync republishes synchronously on save, but MainHook re-reads the
        // prefs on a 30s timer. Between those two moments the app still serves the PREVIOUS config,
        // so every changed field reads back "wrong" — a deterministic false red that would send the
        // user debugging a hook which is simply one tick behind.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = mapOf("tac" to "26999"),
            propagationPending = true,
            specs = lteSpecs,
        )
        assertEquals(1, r.summary.mismatch)
        assertEquals(VerificationStatus.PENDING_PROPAGATION, r.summary.status)
    }

    @Test
    fun `once the propagation window has passed the same mismatch is a real failure`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = mapOf("tac" to "26999"),
            propagationPending = false,
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.FAILING, r.summary.status)
    }

    @Test
    fun `pending propagation does not mask an otherwise fully confirmed result`() {
        // Nothing mismatched, so there is no staleness to wait out.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("tac" to "1"),
            propagationPending = true,
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.EFFECTIVE, r.summary.status)
    }

    @Test
    fun `overall status is NOTHING_CONFIGURED when the profile spoofs nothing`() {
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            observed = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.NOTHING_CONFIGURED, r.summary.status)
    }

    @Test
    fun `overall status is INCONCLUSIVE when configured fields could not be observed at all`() {
        // Distinct from FAILING: we have no evidence either way, and saying "failed" would send the
        // user chasing a bug that may not exist.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "ci" to "2"),
            observed = emptyMap(),
            specs = lteSpecs,
        )
        assertEquals(VerificationStatus.INCONCLUSIVE, r.summary.status)
    }

    // ---- transport drift visibility ---------------------------------------------------------

    @Test
    fun `payload columns absent from the field spec are counted so transport drift stays visible`() {
        // A column that reaches the hook but has no UI row would otherwise be invisible — exactly
        // how 64 of 87 columns went missing unnoticed.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1", "some_new_column" to "7"),
            observed = mapOf("tac" to "1"),
            specs = lteSpecs,
        )
        assertEquals(2, r.payloadFieldCount)
        assertEquals(listOf("some_new_column"), r.unmappedPayloadColumns)
    }

    @Test
    fun `report preserves the field spec category order for a stable screen layout`() {
        val specs = linkedMapOf(
            "小区标识 - LTE" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)),
            "信号 - LTE" to listOf(FieldSpec("lte_rsrp", "RSRP", "", FieldType.INTEGER)),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("lte_rsrp" to "-95"),
            specs = specs,
        )
        assertEquals(listOf("小区标识 - LTE", "信号 - LTE"), r.groups.map { it.category })
    }

    @Test
    fun `categories with nothing to report are dropped`() {
        val specs = linkedMapOf(
            "小区标识 - LTE" to listOf(FieldSpec("tac", "TAC", "", FieldType.INTEGER)),
            "WiFi" to listOf(FieldSpec("wifi_ssid", "SSID", "", FieldType.TEXT)),
        )
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "1"),
            observed = mapOf("tac" to "1"),
            specs = specs,
        )
        assertEquals(listOf("小区标识 - LTE"), r.groups.map { it.category })
    }

    // ---- observation scope: this process is not always hooked --------------------------------

    @Test
    fun `configured field seen only as a real baseline is UNOBSERVABLE not MISMATCH`() {
        // A release build deliberately does NOT hook its own process (MainHook), so this screen
        // reads the REAL value by design. Calling that a MISMATCH would tell the user the hook is
        // broken when it may be working perfectly inside the target app. "No evidence" is the only
        // honest verdict, with the real value carried alongside for reference.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = emptyMap(),
            baseline = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.UNOBSERVABLE, r.field("tac").verdict)
        assertEquals("26999", r.field("tac").baseline)
        assertEquals(VerificationStatus.INCONCLUSIVE, r.summary.status)
    }

    @Test
    fun `field known only from the baseline is reported so the user can see the real network`() {
        // This is what makes "pick a value different from the real network" actionable.
        val r = VerificationEngine.buildReport(
            configured = emptyMap(),
            observed = emptyMap(),
            baseline = mapOf("pci" to "53"),
            specs = lteSpecs,
        )
        assertEquals(FieldVerdict.PASSTHROUGH, r.field("pci").verdict)
        assertEquals("53", r.field("pci").baseline)
    }

    // ---- ambiguity: configured value identical to the real one ------------------------------

    @Test
    fun `field configured to a value equal to the real baseline is flagged ambiguous`() {
        // If the configured value matches what the device would report anyway, "observed == configured"
        // proves nothing. The user must be told to pick a distinguishing value.
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "26999"),
            observed = mapOf("tac" to "26999"),
            baseline = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertTrue(r.field("tac").ambiguous)
        assertEquals(FieldVerdict.AMBIGUOUS, r.field("tac").verdict)
        assertEquals(VerificationStatus.INCONCLUSIVE, r.summary.status)
    }

    @Test
    fun `field configured to a value differing from the real baseline is unambiguous`() {
        val r = VerificationEngine.buildReport(
            configured = mapOf("tac" to "12345"),
            observed = mapOf("tac" to "12345"),
            baseline = mapOf("tac" to "26999"),
            specs = lteSpecs,
        )
        assertTrue(!r.field("tac").ambiguous)
    }
}

private fun VerificationReport.allFields() = groups.flatMap { it.fields }
private fun VerificationReport.field(col: String) =
    allFields().first { it.spec.dbColumn == col }

package name.caiyao.fakegps.verify

import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.config.PublishedConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Before any verdict is believable, the hook must actually be applying the payload.
 *
 * Without this gate the screen reports "N 个字段未生效" in situations where passthrough is the
 * DESIGNED behaviour — sending the user to debug a module that is working exactly as configured.
 * Mirrors the gates in MainHook#loadSnapshot.
 */
class HookApplicabilityTest {

    @Test
    fun `always_on with a compatible payload is applying`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of(mode = "always_on", schemaVersion = 3, currentHour = 3),
        )
    }

    @Test
    fun `mode off means the hook passes everything through by design`() {
        assertEquals(
            HookApplicability.MODE_OFF,
            HookApplicability.of(mode = "off", schemaVersion = 3, currentHour = 3),
        )
    }

    @Test
    fun `inside the active window time_based is applying`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of("time_based", 3, currentHour = 9, activeStart = 7, activeEnd = 22),
        )
    }

    @Test
    fun `outside the active window the hook passes through by design`() {
        // 23:00 with a 07:00-22:00 window. Previously every configured field read back real and the
        // screen blamed the module scope.
        assertEquals(
            HookApplicability.OUTSIDE_ACTIVE_HOURS,
            HookApplicability.of("time_based", 3, currentHour = 23, activeStart = 7, activeEnd = 22),
        )
    }

    @Test
    fun `active window wrapping past midnight is handled like the hook does`() {
        // MainHook: (start <= end) ? (h >= start && h < end) : (h >= start || h < end)
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of("time_based", 3, currentHour = 2, activeStart = 22, activeEnd = 7),
        )
        assertEquals(
            HookApplicability.OUTSIDE_ACTIVE_HOURS,
            HookApplicability.of("time_based", 3, currentHour = 12, activeStart = 22, activeEnd = 7),
        )
    }

    @Test
    fun `time_based without an active window falls back to applying`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of("time_based", 3, currentHour = 12),
        )
    }

    @Test
    fun `an incompatible schema version means the hook rejects this payload entirely`() {
        assertEquals(
            HookApplicability.SCHEMA_REJECTED,
            HookApplicability.of(mode = "always_on", schemaVersion = 1, currentHour = 3),
        )
    }

    @Test
    fun `legacy schema v2 remains applying during a lossless v3 upgrade`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of(mode = "always_on", schemaVersion = 2, currentHour = 3),
        )
        val parsed = PublishedConfig.parse("""{"schemaVersion":2,"fields":{"tac":7}}""")!!
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.forPayload(PayloadRead.Raw("legacy"), parsed, currentHour = 3),
        )
    }

    @Test
    fun `schema rejection outranks the active window because the payload never loads`() {
        assertEquals(
            HookApplicability.SCHEMA_REJECTED,
            HookApplicability.of("time_based", 99, currentHour = 23, activeStart = 7, activeEnd = 22),
        )
    }

    @Test
    fun `a payload with no fields object means the hook is running last-known-good`() {
        // review P1-2: MainHook keeps its previous Snapshot rather than dropping the spoof, so the
        // config actually in force is NOT the one this payload describes. Verdicts computed against
        // it would describe a config the hook is not using.
        assertEquals(
            HookApplicability.PAYLOAD_INCOMPLETE,
            HookApplicability.of("always_on", 3, currentHour = 3, fieldsPresent = false),
        )
    }

    @Test
    fun `an explicitly empty fields object is a real config and stays applying`() {
        assertEquals(
            HookApplicability.APPLYING,
            HookApplicability.of("always_on", 3, currentHour = 3, fieldsPresent = true),
        )
    }

    @Test
    fun `schema v3 without unavailable array is structurally incomplete`() {
        val parsed = PublishedConfig.parse("""{"schemaVersion":3,"fields":{}}""")!!
        assertEquals(
            HookApplicability.PAYLOAD_INCOMPLETE,
            HookApplicability.forPayload(PayloadRead.Raw("{}"), parsed, currentHour = 3),
        )
    }

    @Test
    fun `schema rejection outranks an incomplete payload`() {
        assertEquals(
            HookApplicability.SCHEMA_REJECTED,
            HookApplicability.of("always_on", 1, currentHour = 3, fieldsPresent = false),
        )
    }

    @Test
    fun `a read failure is distinct from never having published`() {
        // review 4822242223 P1: readPublishedRaw collapsed "the read threw" and "no key" both into
        // null, so an unreadable prefs file was announced as "尚未发布过配置，所有字段都会透传" —
        // while the hook is in fact still spoofing from its last-known-good config.
        assertEquals(
            HookApplicability.PAYLOAD_UNREADABLE,
            HookApplicability.forPayload(PayloadRead.ReadError("EACCES"), parsed = null, currentHour = 3),
        )
        assertEquals(false, HookApplicability.PAYLOAD_UNREADABLE.verdictsMeaningful)
    }

    @Test
    fun `an unparseable payload is never reported as applying`() {
        // review 4822122472 P1: when raw bytes exist but cannot be parsed, the previous code fell
        // back to schemaVersion=SCHEMA_VERSION / fieldsPresent=true / mode=always_on, i.e. APPLYING
        // with an empty field map. The screen then announced "当前档案没有配置任何字段 / 全部透传"
        // while the payload card simultaneously said "解析失败 — hook 将保留上一次可用配置".
        // The hook is in fact still spoofing from a config we cannot read.
        assertEquals(
            HookApplicability.PAYLOAD_MALFORMED,
            HookApplicability.forPayload(PayloadRead.Raw("{oops"), parsed = null, currentHour = 3),
        )
        assertEquals(false, HookApplicability.PAYLOAD_MALFORMED.verdictsMeaningful)
    }

    @Test
    fun `nothing ever published is distinct from unparseable`() {
        assertEquals(
            HookApplicability.NEVER_PUBLISHED,
            HookApplicability.forPayload(PayloadRead.Absent, parsed = null, currentHour = 3),
        )
        assertEquals(false, HookApplicability.NEVER_PUBLISHED.verdictsMeaningful)
    }

    @Test
    fun `a parseable payload still goes through the normal gates`() {
        val cfg = PublishedConfig(
            schemaVersion = 3, mode = "off", fields = emptyMap(), fieldsPresent = true,
        )
        assertEquals(
            HookApplicability.MODE_OFF,
            HookApplicability.forPayload(PayloadRead.Raw("{}"), parsed = cfg, currentHour = 3),
        )
    }

    @Test
    fun `only APPLYING allows verdicts to be trusted`() {
        assertEquals(true, HookApplicability.APPLYING.verdictsMeaningful)
        assertEquals(false, HookApplicability.MODE_OFF.verdictsMeaningful)
        assertEquals(false, HookApplicability.OUTSIDE_ACTIVE_HOURS.verdictsMeaningful)
        assertEquals(false, HookApplicability.SCHEMA_REJECTED.verdictsMeaningful)
        assertEquals(false, HookApplicability.PAYLOAD_INCOMPLETE.verdictsMeaningful)
    }
}

package name.caiyao.fakegps.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * LAYER 1 of the "--" (report-as-unavailable) model: an EXPLICIT DECISION for every editable
 * profile field — is this field allowed to be marked unavailable at all?
 *
 * <pre>
 *   left blank -> PASSTHROUGH : the app sees the real device value
 *   "--"       -> UNAVAILABLE : the app sees this platform's "no data" for that field
 *   a value    -> SPOOF       : the app sees that value
 * </pre>
 *
 * <h2>Why this layer deliberately does NOT carry the sentinel value</h2>
 *
 * An earlier version mapped each field to one sentinel. That abstraction is wrong: a single
 * profile field can be projected onto several platform surfaces whose "unknown" differs.
 * {@code lac}/{@code cid} are the proof — on {@code CellIdentityGsm} unknown is
 * {@code Integer.MAX_VALUE}, but the very same values are also written to
 * {@code GsmCellLocation.setLacAndCid()}, where unknown is {@code -1}. Folding that into one
 * number per field would emit an illegal value on one of the two surfaces.
 *
 * <p>So the split is:
 * <ul>
 *   <li><b>Layer 1 (this class)</b> — capability + decision: may the UI offer "--", and if not,
 *       why. One decision per field, exhaustive over {@code FieldSpec.allCategories()}.</li>
 *   <li><b>Layer 2 ({@code UnavailableValueResolver})</b> — a hook-surface resolver:
 *       {@code (field, surface) ->}
 *       {@code MAX_VALUE / -1 / 0 / null / empty collection}. Only the call site knows which
 *       surface it is writing to, so only it can pick the right "unknown".</li>
 * </ul>
 *
 * <h2>Fail-closed</h2>
 *
 * A field is unavailable-capable only if listed in {@link #SUPPORTED}. Everything else — including
 * typos, newly added columns and fields whose platform unknown has not been verified against AOSP
 * — resolves to {@link Kind#UNSUPPORTED}: the UI declines to offer "--" rather than publish a
 * dangerous value. Same principle as the transport layer's "when unsure, pass through".
 */
public final class UnavailableSpec {

    private UnavailableSpec() {}

    /** Capability verdict for a field. The concrete platform value belongs to layer 2. */
    public enum Kind {
        /** May be marked "--"; the surface resolver decides the actual unknown representation. */
        SUPPORTED,
        /** The UI must NOT offer "--" for this field (see {@link #reasonFor}). */
        UNSUPPORTED
    }

    /** {@code CellInfo.UNAVAILABLE} — used by the cellular identity/signal surfaces. */
    public static final int UNAVAILABLE_INT = Integer.MAX_VALUE;
    /** {@code CellInfo.UNAVAILABLE_LONG}. */
    public static final long UNAVAILABLE_LONG = Long.MAX_VALUE;

    /**
     * Fields cleared for "--": their unknown representation is verified on every surface they
     * reach. Cellular identity/signal integers ({@code CellInfo.UNAVAILABLE}), {@code nci}
     * ({@code UNAVAILABLE_LONG}) and the operator/SIM text group (empty string).
     *
     * <p>Layer 2 now resolves dual-surface and non-CellInfo unknowns at their call sites, so the
     * cellular network/state/physical-channel fields can also be selected here without folding
     * them into one unsafe sentinel.
     */
    private static final Set<String> SUPPORTED = setOf(
            // cellular identity — single surface (CellIdentity*)
            "mcc", "mnc", "lac", "cid", "arfcn", "bsic", "psc", "uarfcn",
            "tac", "ci", "pci", "earfcn", "lte_bandwidth",
            "nci", "nrarfcn", "nr_pci", "nr_tac",
            // cellular signal
            "gsm_rssi", "gsm_ber", "gsm_ta",
            "wcdma_rssi", "wcdma_rscp", "wcdma_ecno",
            "lte_rssi", "lte_rsrp", "lte_rsrq", "lte_sinr", "lte_cqi", "lte_ta",
            "nr_ss_rsrp", "nr_ss_rsrq", "nr_ss_sinr",
            "nr_csi_rsrp", "nr_csi_rsrq", "nr_csi_sinr",
            // operator / SIM text — telephony's unknown really is ""
            "operator_name", "operator_numeric", "sim_operator", "sim_operator_name",
            "sim_country_iso", "network_country_iso",
            // network/service/display
            "network_type", "data_network_type", "voice_network_type", "phone_type",
            "data_state", "data_activity", "override_network_type",
            // physical channel
            "band", "channel_bandwidth", "cell_bandwidth_downlink", "physical_cell_id");

    /**
     * Fields explicitly decided NOT to support "--", with the reason. Keeping these enumerated
     * (rather than letting them fall through) is what makes the decision set exhaustive: a newly
     * added field belongs to neither set and is caught by the coverage gate.
     */
    private static final Set<String> UNSUPPORTED_LOCATION = setOf(
            "latitude", "longitude", "altitude", "speed", "bearing", "accuracy");

    private static final Set<String> UNSUPPORTED_BOOLEAN = setOf(
            "is_roaming", "wifi_hidden", "wifi_enabled", "signal_fluctuation_enabled");

    private static final Set<String> UNSUPPORTED_NO_EMPTY_STATE = setOf("service_state");

    private static final Set<String> UNSUPPORTED_UNVERIFIED = setOf(
            // Wi-Fi integers: RSSI unknown is -127; frequency / link speed are -1
            "wifi_rssi", "wifi_frequency", "wifi_link_speed", "wifi_tx_link_speed",
            "wifi_rx_link_speed", "wifi_channel", "wifi_standard", "wifi_security_type",
            // Wi-Fi identity text: SSID unknown is "<unknown ssid>", BSSID is null
            "wifi_ssid", "wifi_bssid", "wifi_mac", "wifi_ip",
            // IP / DNS / routing text: each needs its own empty behaviour
            "local_ipv4", "local_ipv6", "dns_primary", "dns_secondary", "gateway",
            "subnet_mask", "connection_type", "interface_name",
            // The public API is a mixed serving+neighbor List<CellInfo>.  An empty JSON string
            // cannot express "remove neighbors but retain serving cells" without filtering the
            // real list, which is not implemented yet.  Offering -- would silently leak them.
            "neighbor_cells_json",
            // module-internal knob, not a device field
            "signal_fluctuation_range_db");

    /** Every field that has an explicit decision. Must equal the editable field set. */
    public static Set<String> decidedColumns() {
        Set<String> all = new LinkedHashSet<>(SUPPORTED);
        all.addAll(UNSUPPORTED_LOCATION);
        all.addAll(UNSUPPORTED_BOOLEAN);
        all.addAll(UNSUPPORTED_NO_EMPTY_STATE);
        all.addAll(UNSUPPORTED_UNVERIFIED);
        return Collections.unmodifiableSet(all);
    }

    /** Columns cleared for "--". Exposed so the coverage gate can walk it in reverse. */
    public static Set<String> supportedColumns() {
        return SUPPORTED;
    }

    /** Capability verdict. FAIL-CLOSED: anything not explicitly supported is UNSUPPORTED. */
    public static Kind kindOf(String column) {
        if (column == null) return Kind.UNSUPPORTED;
        return SUPPORTED.contains(column) ? Kind.SUPPORTED : Kind.UNSUPPORTED;
    }

    /** Whether the UI may offer "--" for this field. */
    public static boolean supportsUnavailable(String column) {
        return kindOf(column) == Kind.SUPPORTED;
    }

    /** Human-readable reason a field cannot be marked "--" (for UI copy / review). */
    public static String reasonFor(String column) {
        if (column == null) return "unknown field";
        if (SUPPORTED.contains(column)) return "";
        if (UNSUPPORTED_LOCATION.contains(column)) {
            return "Location geometry has no per-field unavailable form "
                    + "(altitude/speed/bearing/accuracy use hasX/removeX, not a sentinel)";
        }
        if (UNSUPPORTED_BOOLEAN.contains(column)) {
            return "a boolean has no third value";
        }
        if (UNSUPPORTED_NO_EMPTY_STATE.contains(column)) {
            return "ServiceState has no UNKNOWN/empty constant; OUT_OF_SERVICE is a real state";
        }
        if (UNSUPPORTED_UNVERIFIED.contains(column)) {
            return "platform unknown not yet verified against AOSP for this surface";
        }
        return "no explicit decision recorded for this field";
    }

    /**
     * True if {@code value} is a sentinel meaning "no data" rather than a measurement.
     *
     * <p>Callers doing arithmetic on field values (signal fluctuation) MUST check this first:
     * {@code Integer.MAX_VALUE + jitter} overflows into a large negative number, i.e. silently
     * converts "no data" into a plausible-looking fake reading.
     */
    public static boolean isUnavailableSentinel(int value) {
        return value == UNAVAILABLE_INT;
    }

    /** Long-typed counterpart of {@link #isUnavailableSentinel(int)}. */
    public static boolean isUnavailableSentinel(long value) {
        return value == UNAVAILABLE_LONG;
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}

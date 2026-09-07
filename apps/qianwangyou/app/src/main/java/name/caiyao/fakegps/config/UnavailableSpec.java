package name.caiyao.fakegps.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
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
     * ({@code UNAVAILABLE_LONG}), the operator/SIM text group (empty string), the Wi-Fi group
     * (per-surface WifiInfo/ScanResult unknowns, see below) and the neighbour list (filter
     * semantics, not an empty value).
     *
     * <p>Layer 2 resolves dual-surface and non-CellInfo unknowns at their call sites, so fields
     * whose surfaces disagree (lac/cid, the Wi-Fi group) are selected here without folding them
     * into one unsafe sentinel.
     *
     * <p>Wi-Fi group AOSP evidence (verified against AOSP source, per hooked surface):
     * <ul>
     *   <li>{@code wifi_rssi} — {@code WifiInfo.INVALID_RSSI = -127}; WifiInfo's clear()/reset()
     *       leave RSSI at INVALID_RSSI when unknown.</li>
     *   <li>{@code wifi_frequency} — {@code WifiInfo.UNKNOWN_FREQUENCY = -1} (mFrequency default);
     *       {@code wifi_link_speed}/{@code wifi_tx_link_speed}/{@code wifi_rx_link_speed} —
     *       {@code WifiInfo.LINK_SPEED_UNKNOWN = -1} (reset() sets all three).</li>
     *   <li>{@code wifi_standard} — {@code ScanResult.WIFI_STANDARD_UNKNOWN = 0} (public constant,
     *       identical Android 11 through 15); {@code WifiInfo.mWifiStandard} has no initializer,
     *       i.e. an unset WifiInfo already reports 0.</li>
     *   <li>{@code wifi_ssid} — {@code WifiInfo.getSSID()} returns
     *       {@code WifiManager.UNKNOWN_SSID = "<unknown ssid>"} when unknown (the double quotes
     *       are part of the platform value).</li>
     *   <li>{@code wifi_bssid} — {@code WifiInfo.getBSSID()} returns {@code null} when unknown
     *       (mBSSID is null in clear()/reset()); the explicit-null override happens at the call
     *       site, like the PLMN string surfaces.</li>
     *   <li>{@code neighbor_cells_json} — the {@code List<CellInfo>} surfaces have a real
     *       list-level "no neighbour data" state: drop the non-registered entries and retain the
     *       registered serving cells (see {@code Snapshot#replacesRealNeighbors}).</li>
     * </ul>
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
            "band", "channel_bandwidth", "cell_bandwidth_downlink", "physical_cell_id",
            // Wi-Fi — unknown verified per surface on the exact hooked getters (WifiInfo)
            "wifi_rssi", "wifi_frequency", "wifi_link_speed", "wifi_tx_link_speed",
            "wifi_rx_link_speed", "wifi_standard", "wifi_ssid", "wifi_bssid",
            // neighbour list — "--" = drop real neighbours, keep registered serving cells
            "neighbor_cells_json");

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

    /**
     * Rejected text/integer fields whose "no data" form is NOT verified on EVERY surface they
     * reach, each entry carrying the specific missing evidence (fail-closed: 宁缺毋滥).
     *
     * <p>The recurring pattern for the IP/connection group: the LinkProperties surface alone has
     * an honourable empty state, but its paired {@code DhcpInfo} int fields (or
     * {@code NetworkInterface#getName}) have NO documented unknown — honouring "--" only on the
     * verifiable surface would leak the real value through the other one.
     */
    private static final Map<String, String> UNSUPPORTED_WITH_EVIDENCE = withReasons()
            .put("wifi_channel",
                    "no hooked platform surface: WifiInfo/ScanResult expose frequency, not "
                            + "channel — a -- would silently do nothing")
            .put("wifi_security_type",
                    "WifiInfo.getCurrentSecurityType() unknown sentinel not verified against AOSP")
            .put("wifi_mac",
                    "WifiInfo.getMacAddress() empty state not verified (permission-dependent "
                            + "DEFAULT_MAC_ADDRESS redaction)")
            .put("wifi_ip",
                    "WifiInfo.getIpAddress() returns the packed int 0 when unknown, but 0 is not "
                            + "a documented platform unknown")
            .put("local_ipv4",
                    "read surfaces are collections (LinkProperties#getLinkAddresses, "
                            + "NetworkInterface#getInetAddresses) plus DhcpInfo#ipAddress whose "
                            + "fields have no documented unknown — honouring -- would leak the "
                            + "real address via getDhcpInfo()")
            .put("local_ipv6",
                    "read surfaces are collections (LinkProperties#getLinkAddresses, "
                            + "NetworkInterface#getInetAddresses) with no per-field unknown — "
                            + "honouring -- would require filtering the real address list")
            .put("dns_primary",
                    "LinkProperties#getDnsServers has a true empty state, but DhcpInfo#dns1 has "
                            + "no documented unknown — the real DNS would leak via getDhcpInfo()")
            .put("dns_secondary",
                    "LinkProperties#getDnsServers has a true empty state, but DhcpInfo#dns2 has "
                            + "no documented unknown — the real DNS would leak via getDhcpInfo()")
            .put("gateway",
                    "RouteInfo#getGateway null (directly-connected route) is a real state, but "
                            + "DhcpInfo#gateway has no documented unknown — the real gateway "
                            + "would leak via getDhcpInfo()")
            .put("subnet_mask",
                    "only read surface is DhcpInfo#netmask, whose fields have no documented "
                            + "unknown (no 0-means-unset contract)")
            .put("connection_type",
                    "NetworkInfo#getTypeName has no platform unknown (constructor-supplied "
                            + "\"WIFI\"/\"MOBILE\", never a no-data value)")
            .put("interface_name",
                    "LinkProperties#getInterfaceName is @Nullable, but "
                            + "java.net.NetworkInterface#getName has no unknown for an existing "
                            + "interface — the real name would leak on that surface")
            .put("signal_fluctuation_range_db",
                    "module-internal knob, not a device field — there is nothing to make "
                            + "unavailable")
            .build();

    /** Minimal insertion-ordered builder (no java.util.Map.of — min API 24 compatibility). */
    private static ReasonBuilder withReasons() {
        return new ReasonBuilder();
    }

    private static final class ReasonBuilder {
        private final Map<String, String> map = new LinkedHashMap<>();

        ReasonBuilder put(String field, String reason) {
            map.put(field, reason);
            return this;
        }

        Map<String, String> build() {
            return Collections.unmodifiableMap(map);
        }
    }

    /** Every field that has an explicit decision. Must equal the editable field set. */
    public static Set<String> decidedColumns() {
        Set<String> all = new LinkedHashSet<>(SUPPORTED);
        all.addAll(UNSUPPORTED_LOCATION);
        all.addAll(UNSUPPORTED_BOOLEAN);
        all.addAll(UNSUPPORTED_NO_EMPTY_STATE);
        all.addAll(UNSUPPORTED_WITH_EVIDENCE.keySet());
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
        String evidence = UNSUPPORTED_WITH_EVIDENCE.get(column);
        if (evidence != null) {
            return "platform unknown not verified on every read surface: " + evidence;
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

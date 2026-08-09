package name.caiyao.fakegps.hook;

import android.database.Cursor;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import name.caiyao.fakegps.config.UnavailableSpec;

/**
 * Thread-safe snapshot of current spoofing configuration.
 * Published atomically via {@link MainHook#CURRENT}.
 *
 * ALL fields are nullable wrapper types:
 *   null  = passthrough (use real device value, don't hook)
 *   value = spoof with this value
 */
class Snapshot {

    /** Default: everything null = passthrough all real values. */
    static final Snapshot PASSTHROUGH = new Snapshot();

    /** One choke point for every hook invocation during an in-process real-baseline read. */
    static Snapshot forHookInvocation(Snapshot configured, boolean extractingBaseline) {
        return extractingBaseline ? PASSTHROUGH : configured;
    }

    private Set<String> unavailableFields = Collections.emptySet();

    /**
     * LAST-KNOWN-GOOD resolution for an unreadable / structurally incomplete config payload.
     *
     * <p>Invariant (review FC-2): once a spoof is ACTIVE, a failed refresh must never revert the
     * hook layer to real device data — that would leak the true environment to the app under test
     * mid-run. Only when nothing has ever been published (first launch) is passing through the
     * real values the safe default.
     *
     * <p>Extracted as a pure function so the invariant is locked by unit tests rather than by
     * inspection: {@code MainHook} itself cannot be unit-tested (Xposed classes are compileOnly).
     *
     * @param lastGood the currently-effective snapshot, may be null
     * @return {@code lastGood} if a spoof is currently active, otherwise {@link #PASSTHROUGH}
     */
    static Snapshot keepLastKnownGoodOr(Snapshot lastGood) {
        boolean everPublished = lastGood != null && lastGood != PASSTHROUGH;
        return everPublished ? lastGood : PASSTHROUGH;
    }

    static ArrayList acceptBuiltCellListOrPassthrough(ArrayList built) {
        return built == null || built.isEmpty() ? null : built;
    }

    /** Keep real cells unless the user supplied neighbors or this registered RAT is replaced. */
    static boolean shouldPreserveRealCell(
            boolean registered, boolean replacingRat, boolean configuredNeighborList) {
        return registered ? !replacingRat : !configuredNeighborList;
    }

    /** A preserved serving cell stays serving only when no explicit serving RAT is rebuilt. */
    static boolean shouldBypassPreservedRealCell(
            boolean registered, boolean rebuildingServingRat) {
        return !registered || rebuildingServingRat;
    }

    /**
     * Whether a real cellular baseline is usable to overlay the user's edits onto.
     *
     * A CellInfo object merely existing is not enough: the platform can hand back entries whose
     * identity fields are all "unknown" (Integer.MAX_VALUE), and treating those as a baseline let
     * the fail-safe pass and the builder fall back to constants (mcc=460 / mnc=0) — emitting a cell
     * that contradicts the SIM and operator, which is more detectable than not spoofing at all.
     *
     * Extracted as a pure function so the fail-safe is locked by tests: the cellular paths had no
     * test coverage, which is exactly why this class of defect reached review (FC-3/FC-5).
     *
     * @return true only when at least one identity field carries a genuine value
     */
    static boolean isUsableCellBaseline(Integer ci, Integer tac, Integer pci, Integer earfcn,
                                        Integer lac, Integer cid, Integer mcc) {
        return ci != null || tac != null || pci != null || earfcn != null
                || lac != null || cid != null || mcc != null;
    }

    /**
     * Resolve a GSM cell-location field: the user's value wins, else the device's real value.
     *
     * Returns null when neither side has one, which callers MUST treat as "pass the real
     * CellLocation through". Previously the caller unboxed {@code s.lac}/{@code s.cid} directly, so
     * a profile configuring a CellLocation field without both LAC/CID values threw an NPE inside
     * the target app's own listener callback (FC-4).
     */
    static Integer resolveCellField(Integer configured, Integer real) {
        return configured != null ? configured : real;
    }

    static Integer resolveGsmCellLocationField(
            String field, Integer configured, Integer real, boolean unavailable) {
        if (unavailable) {
            UnavailableValueResolver.Resolution resolution = UnavailableValueResolver.resolve(
                    field, UnavailableValueResolver.Surface.GSM_CELL_LOCATION);
            if (!resolution.handled()) {
                throw new IllegalArgumentException(
                        "no GsmCellLocation unavailable resolver for " + field);
            }
            return (Integer) resolution.value();
        }
        return resolveCellField(configured, real);
    }

    static String resolvePlmnString(
            String field, Integer configured, String real, boolean unavailable) {
        if (unavailable) return null;
        if (configured == null) return real;
        if ("mcc".equals(field)) {
            return String.format(Locale.US, "%03d", configured);
        }
        if ("mnc".equals(field)) {
            int width = configured >= 100 ? 3 : 2;
            return String.format(Locale.US, "%0" + width + "d", configured);
        }
        throw new IllegalArgumentException("not a PLMN field: " + field);
    }

    /**
     * Canonicalize a PLMN value read from a loosely typed source such as neighbor JSON.
     *
     * <p>Numeric JSON values have already lost their display width, so they use the same canonical
     * MCC/MNC formatting as profile integers. Explicit two/three-digit strings retain their width,
     * which is required to distinguish valid two-digit and three-digit MNCs with leading zeroes.
     */
    static String resolvePlmnStringValue(String field, Object configured, String real) {
        if (configured == null) return real;
        String value = String.valueOf(configured).trim();
        String canonicalPattern;
        switch (field) {
            case "mcc": canonicalPattern = "[0-9]{3}"; break;
            case "mnc": canonicalPattern = "[0-9]{2,3}"; break;
            default: throw new IllegalArgumentException("not a PLMN field: " + field);
        }
        if (value.matches(canonicalPattern)) return value;
        return resolvePlmnString(field, Integer.parseInt(value), real, false);
    }

    /**
     * Resolve the public WCDMA signal-power surface.
     *
     * Android's public {@code CellSignalStrengthWcdma#getDbm()} is backed by RSCP on modern
     * releases; there is no public {@code getRscp()} method on API 35. Keep {@code wcdma_rssi} as
     * the legacy/profile alias only when the explicit RSCP field is absent.
     */
    static Integer resolveWcdmaDbm(Integer rscp, Integer rssi) {
        return rscp != null ? rscp : rssi;
    }

    /**
     * Map the profile's generic channel bandwidth to API 33's uplink bandwidth getter.
     *
     * Downlink has its own independent {@code cell_bandwidth_downlink} profile column.
     */
    static Integer resolvePhysicalUplinkBandwidth(Integer channelBandwidth) {
        return channelBandwidth;
    }

    /** Map the configured manager network type onto TelephonyDisplayInfo.getNetworkType(). */
    static Integer resolveDisplayNetworkType(Integer networkType) {
        return networkType;
    }

    // ==========================================
    // A. LOCATION (resolved by MainHook per hour)
    // ==========================================
    Double latitude;
    Double longitude;
    Double altitude;        // meters
    Float speed;            // m/s
    Float bearing;          // degrees
    Float accuracy;         // meters

    // ==========================================
    // B. CELL IDENTITY - GSM/2G
    // ==========================================
    Integer mcc;            // Mobile Country Code
    Integer mnc;            // Mobile Network Code
    Integer lac;            // Location Area Code
    Integer cid;            // Cell ID (GSM 0-65535)
    Integer arfcn;          // Absolute Radio Frequency Channel Number
    Integer bsic;           // Base Station Identity Code (0-63)

    // ==========================================
    // C. CELL IDENTITY - WCDMA/3G
    // ==========================================
    Integer psc;            // Primary Scrambling Code (0-511)
    Integer uarfcn;         // UTRA ARFCN

    // ==========================================
    // D. CELL IDENTITY - LTE/4G
    // ==========================================
    Integer tac;            // Tracking Area Code
    Integer ci;             // Cell Identity 28-bit
    Integer pci;            // Physical Cell ID (0-503)
    Integer earfcn;         // E-UTRA ARFCN
    Integer lteBandwidth;   // Bandwidth kHz

    // ==========================================
    // E. CELL IDENTITY - NR/5G
    // ==========================================
    Long nci;               // NR Cell Identity 36-bit
    Integer nrarfcn;        // NR ARFCN
    Integer nrPci;          // NR Physical Cell ID (0-1007)
    Integer nrTac;          // NR Tracking Area Code

    // ==========================================
    // F. SIGNAL - GSM
    // ==========================================
    Integer gsmRssi;        // -113 to -51 dBm
    Integer gsmBer;         // Bit Error Rate (0-7)
    Integer gsmTa;          // Timing Advance (0-219)

    // ==========================================
    // G. SIGNAL - WCDMA
    // ==========================================
    Integer wcdmaRssi;      // dBm
    Integer wcdmaRscp;      // -120 to -24 dBm
    Integer wcdmaEcno;      // -24 to 1 dB

    // ==========================================
    // H. SIGNAL - LTE
    // ==========================================
    Integer lteRssi;        // dBm
    Integer lteRsrp;        // -140 to -44 dBm
    Integer lteRsrq;        // -20 to -3 dB
    Integer lteSinr;        // -20 to +30 dB
    Integer lteCqi;         // Channel Quality Indicator (0-15)
    Integer lteTa;          // Timing Advance (0-1282)

    // ==========================================
    // I. SIGNAL - NR/5G
    // ==========================================
    Integer nrSsRsrp;      // -156 to -31 dBm
    Integer nrSsRsrq;      // -43 to 20 dB
    Integer nrSsSinr;       // -23 to 40 dB
    Integer nrCsiRsrp;
    Integer nrCsiRsrq;
    Integer nrCsiSinr;

    // ==========================================
    // J. SIGNAL FLUCTUATION
    // ==========================================
    Boolean signalFluctuationEnabled;
    Integer signalFluctuationRangeDb;   // e.g. 10 means +/-5dB

    // ==========================================
    // K. CARRIER & NETWORK
    // ==========================================
    Integer networkType;        // TelephonyManager.NETWORK_TYPE_*
    Integer dataNetworkType;
    Integer voiceNetworkType;
    String operatorName;        // e.g. "China Mobile"
    String operatorNumeric;     // e.g. "46000"
    String simOperator;
    String simOperatorName;
    String simCountryIso;       // e.g. "cn"
    String networkCountryIso;
    Boolean isRoaming;
    Integer phoneType;          // GSM=1, CDMA=2

    // ==========================================
    // L. SERVICE STATE
    // ==========================================
    Integer serviceState;       // 0=IN_SERVICE
    Integer dataState;          // 2=CONNECTED
    Integer dataActivity;       // 3=INOUT

    // ==========================================
    // M. DISPLAY INFO
    // ==========================================
    Integer overrideNetworkType;

    // ==========================================
    // N. PHYSICAL CHANNEL CONFIG
    // ==========================================
    Integer band;               // Band number (e.g. 1,3,7 for LTE; 41,77,78 for NR)
    Integer channelBandwidth;   // Bandwidth kHz (NOT band number)
    Integer cellBandwidthDownlink;
    Integer physicalCellId;

    // ==========================================
    // O. WIFI
    // ==========================================
    String wifiSsid;
    String wifiBssid;
    Integer wifiRssi;           // -40 to -90 dBm
    Integer wifiFrequency;      // MHz
    Integer wifiLinkSpeed;      // Mbps
    Integer wifiTxLinkSpeed;
    Integer wifiRxLinkSpeed;
    Integer wifiChannel;
    Integer wifiStandard;       // WIFI_STANDARD_*
    Integer wifiSecurityType;
    String wifiMac;
    String wifiIp;
    Boolean wifiHidden;
    Boolean wifiEnabled;

    // ==========================================
    // P. IP & CONNECTIVITY
    // ==========================================
    String localIpv4;
    String localIpv6;
    String dnsPrimary;
    String dnsSecondary;
    String gateway;
    String subnetMask;
    String connectionType;      // "WIFI" or "MOBILE"
    String interfaceName;       // "wlan0", "rmnet0"

    // ==========================================
    // Q. NEIGHBOR CELLS
    // ==========================================
    String neighborCellsJson;

    // ==========================================================
    // CONVENIENCE CHECKS
    // ==========================================================

    boolean hasLocation() { return latitude != null && longitude != null; }
    // A serving RAT may only be constructed from identity fields unique to that RAT. Shared
    // MCC/MNC/LAC/CID values are projected onto framework-owned identities by getter hooks.
    private boolean hasSpoofValue(String field, Object value) {
        return value != null && !isUnavailable(field);
    }
    boolean hasGsmRatConstruction() {
        return hasSpoofValue("arfcn", arfcn) || hasSpoofValue("bsic", bsic);
    }
    boolean hasWcdmaRatConstruction() {
        return hasSpoofValue("psc", psc) || hasSpoofValue("uarfcn", uarfcn);
    }
    /**
     * Whether an existing {@code GsmCellLocation} must be transformed.
     *
     * <p>An unavailable-only decision must not fabricate a new {@code CellInfo} RAT, but it must
     * still project {@code -1} onto an object already returned by Android. PSC belongs here because
     * it is exposed by {@code GsmCellLocation} even though it is a WCDMA identity field.
     */
    boolean hasGsmCellLocationDecision() {
        return lac != null || cid != null || psc != null
                || isUnavailable("lac") || isUnavailable("cid") || isUnavailable("psc");
    }
    boolean hasLteRatConstruction() { return hasSpoofValue("ci", ci) || hasSpoofValue("tac", tac)
            || hasSpoofValue("pci", pci) || hasSpoofValue("earfcn", earfcn)
            || hasSpoofValue("lte_bandwidth", lteBandwidth); }
    boolean hasNrRatConstruction() { return hasSpoofValue("nci", nci) || hasSpoofValue("nr_tac", nrTac)
            || hasSpoofValue("nr_pci", nrPci) || hasSpoofValue("nrarfcn", nrarfcn); }
    boolean hasCellReconstructionDecision() {
        return hasGsmRatConstruction() || hasWcdmaRatConstruction()
                || hasLteRatConstruction() || hasNrRatConstruction();
    }
    boolean hasCellListMutationDecision() {
        return hasCellReconstructionDecision() || neighborCellsJson != null;
    }
    boolean hasWifi() { return wifiSsid != null || wifiBssid != null; }
    boolean hasPhysicalChannelConfig() {
        return hasSpoofValue("band", band)
                || hasSpoofValue("channel_bandwidth", channelBandwidth)
                || hasSpoofValue("cell_bandwidth_downlink", cellBandwidthDownlink)
                || hasSpoofValue("physical_cell_id", physicalCellId);
    }

    /**
     * Apply signal fluctuation if enabled.
     * Returns baseValue +/- random offset within configured range.
     */
    int fluctuate(int baseValue, Random rnd) {
        // A sentinel means "no data", not "a reading of 2147483647". Adding jitter to it
        // overflows into a large NEGATIVE number, which reads as a perfectly plausible signal
        // level — i.e. it would silently turn "unavailable" into fabricated data.
        if (name.caiyao.fakegps.config.UnavailableSpec.isUnavailableSentinel(baseValue)) {
            return baseValue;
        }
        if (signalFluctuationEnabled != null && signalFluctuationEnabled
                && signalFluctuationRangeDb != null && signalFluctuationRangeDb > 0) {
            int halfRange = signalFluctuationRangeDb / 2;
            return baseValue + rnd.nextInt(signalFluctuationRangeDb + 1) - halfRange;
        }
        return baseValue;
    }

    // ==========================================================
    // CURSOR READING (maps DB snake_case columns to fields)
    // ==========================================================

    static Snapshot from(FieldSource src) {
        Snapshot s = new Snapshot();

        // A. Location
        s.latitude = src.getDouble("latitude");
        s.longitude = src.getDouble("longitude");
        s.altitude = src.getDouble("altitude");
        s.speed = src.getFloat("speed");
        s.bearing = src.getFloat("bearing");
        s.accuracy = src.getFloat("accuracy");

        // B. Cell Identity - GSM
        s.mcc = src.getInt("mcc");
        s.mnc = src.getInt("mnc");
        s.lac = src.getInt("lac");
        s.cid = src.getInt("cid");
        s.arfcn = src.getInt("arfcn");
        s.bsic = src.getInt("bsic");

        // C. Cell Identity - WCDMA
        s.psc = src.getInt("psc");
        s.uarfcn = src.getInt("uarfcn");

        // D. Cell Identity - LTE
        s.tac = src.getInt("tac");
        s.ci = src.getInt("ci");
        s.pci = src.getInt("pci");
        s.earfcn = src.getInt("earfcn");
        s.lteBandwidth = src.getInt("lte_bandwidth");

        // E. Cell Identity - NR
        s.nci = src.getLong("nci");
        s.nrarfcn = src.getInt("nrarfcn");
        s.nrPci = src.getInt("nr_pci");
        s.nrTac = src.getInt("nr_tac");

        // F. Signal - GSM
        s.gsmRssi = src.getInt("gsm_rssi");
        s.gsmBer = src.getInt("gsm_ber");
        s.gsmTa = src.getInt("gsm_ta");

        // G. Signal - WCDMA
        s.wcdmaRssi = src.getInt("wcdma_rssi");
        s.wcdmaRscp = src.getInt("wcdma_rscp");
        s.wcdmaEcno = src.getInt("wcdma_ecno");

        // H. Signal - LTE
        s.lteRssi = src.getInt("lte_rssi");
        s.lteRsrp = src.getInt("lte_rsrp");
        s.lteRsrq = src.getInt("lte_rsrq");
        s.lteSinr = src.getInt("lte_sinr");
        s.lteCqi = src.getInt("lte_cqi");
        s.lteTa = src.getInt("lte_ta");

        // I. Signal - NR
        s.nrSsRsrp = src.getInt("nr_ss_rsrp");
        s.nrSsRsrq = src.getInt("nr_ss_rsrq");
        s.nrSsSinr = src.getInt("nr_ss_sinr");
        s.nrCsiRsrp = src.getInt("nr_csi_rsrp");
        s.nrCsiRsrq = src.getInt("nr_csi_rsrq");
        s.nrCsiSinr = src.getInt("nr_csi_sinr");

        // J. Signal Fluctuation
        s.signalFluctuationEnabled = src.getBool("signal_fluctuation_enabled");
        s.signalFluctuationRangeDb = src.getInt("signal_fluctuation_range_db");

        // K. Carrier & Network
        s.networkType = src.getInt("network_type");
        s.dataNetworkType = src.getInt("data_network_type");
        s.voiceNetworkType = src.getInt("voice_network_type");
        s.operatorName = src.getString("operator_name");
        s.operatorNumeric = src.getString("operator_numeric");
        s.simOperator = src.getString("sim_operator");
        s.simOperatorName = src.getString("sim_operator_name");
        s.simCountryIso = src.getString("sim_country_iso");
        s.networkCountryIso = src.getString("network_country_iso");
        s.isRoaming = src.getBool("is_roaming");
        s.phoneType = src.getInt("phone_type");

        // L. Service State
        s.serviceState = src.getInt("service_state");
        s.dataState = src.getInt("data_state");
        s.dataActivity = src.getInt("data_activity");

        // M. Display Info
        s.overrideNetworkType = src.getInt("override_network_type");

        // N. Physical Channel Config
        s.band = src.getInt("band");
        s.channelBandwidth = src.getInt("channel_bandwidth");
        s.cellBandwidthDownlink = src.getInt("cell_bandwidth_downlink");
        s.physicalCellId = src.getInt("physical_cell_id");

        // O. WiFi
        s.wifiSsid = src.getString("wifi_ssid");
        s.wifiBssid = src.getString("wifi_bssid");
        s.wifiRssi = src.getInt("wifi_rssi");
        s.wifiFrequency = src.getInt("wifi_frequency");
        s.wifiLinkSpeed = src.getInt("wifi_link_speed");
        s.wifiTxLinkSpeed = src.getInt("wifi_tx_link_speed");
        s.wifiRxLinkSpeed = src.getInt("wifi_rx_link_speed");
        s.wifiChannel = src.getInt("wifi_channel");
        s.wifiStandard = src.getInt("wifi_standard");
        s.wifiSecurityType = src.getInt("wifi_security_type");
        s.wifiMac = src.getString("wifi_mac");
        s.wifiIp = src.getString("wifi_ip");
        s.wifiHidden = src.getBool("wifi_hidden");
        s.wifiEnabled = src.getBool("wifi_enabled");

        // P. IP & Connectivity
        s.localIpv4 = src.getString("local_ipv4");
        s.localIpv6 = src.getString("local_ipv6");
        s.dnsPrimary = src.getString("dns_primary");
        s.dnsSecondary = src.getString("dns_secondary");
        s.gateway = src.getString("gateway");
        s.subnetMask = src.getString("subnet_mask");
        s.connectionType = src.getString("connection_type");
        s.interfaceName = src.getString("interface_name");

        // Q. Neighbor cells
        s.neighborCellsJson = src.getString("neighbor_cells_json");

        return s;
    }

    /**
     * Build a snapshot with an orthogonal unavailable decision set.
     *
     * <p>The wrapper materializes the canonical value used by generic hooks; call sites whose
     * Android surface differs (PLMN strings and {@code GsmCellLocation}) must inspect
     * {@link #isUnavailable(String)} and use {@link UnavailableValueResolver} for that surface.
     */
    static Snapshot from(FieldSource src, Set<String> unavailable) {
        Set<String> copy = unavailable == null
                ? Collections.emptySet() : new LinkedHashSet<>(unavailable);
        for (String field : copy) {
            if (!UnavailableSpec.supportsUnavailable(field)) {
                throw new IllegalArgumentException("unsupported unavailable field: " + field);
            }
            if (!UnavailableValueResolver.resolveSnapshotField(field).handled()) {
                throw new IllegalArgumentException("no snapshot resolver for unavailable field: " + field);
            }
        }
        Snapshot s = from(copy.isEmpty() ? src : new UnavailableSource(src, copy));
        s.unavailableFields = Collections.unmodifiableSet(copy);
        return s;
    }

    boolean isUnavailable(String field) {
        return unavailableFields.contains(field);
    }

    Set<String> unavailableFields() {
        return unavailableFields;
    }

    /** Read the profile row out of a DB cursor (in-app path). */
    static Snapshot fromCursor(Cursor c) {
        return from(new CursorSource(c));
    }

    /**
     * Read the profile out of the transported JSON field map (hook path).
     *
     * The transport carries a flat {@code {column: value}} map mirroring the profile table, so the
     * SINGLE field list in {@link #from(FieldSource)} covers both paths. Previously the hook side
     * went through a hand-written typed schema that only declared 23 of the 87 columns, so
     * mcc/mnc/lac/cid/operator_name silently never reached the hook.
     */
    static Snapshot fromJson(org.json.JSONObject fields) {
        return from(new JsonSource(fields));
    }

    static Snapshot fromJson(org.json.JSONObject fields, Set<String> unavailable) {
        Set<String> configured = new LinkedHashSet<>();
        java.util.Iterator<String> keys = fields.keys();
        while (keys.hasNext()) configured.add(keys.next());
        name.caiyao.fakegps.config.UnavailablePayloadContract.Validated validated =
                name.caiyao.fakegps.config.UnavailablePayloadContract.validate(
                        configured,
                        unavailable == null
                                ? Collections.emptyList()
                                : new java.util.ArrayList<>(unavailable));
        return from(new JsonSource(fields), validated.asSet());
    }

    /**
     * Null-safe value reader. One field list ({@link #from}), two sources: a DB cursor in the app
     * process and the transported JSON in a hooked process. Absent/null => null => passthrough.
     */
    interface FieldSource {
        Double  getDouble(String col);
        Float   getFloat(String col);
        Integer getInt(String col);
        Long    getLong(String col);
        String  getString(String col);
        Boolean getBool(String col);
    }

    private static final class CursorSource implements FieldSource {
        private final Cursor c;
        CursorSource(Cursor c) { this.c = c; }

        private int idx(String col) {
            int i = c.getColumnIndex(col);
            return (i >= 0 && !c.isNull(i)) ? i : -1;
        }
        @Override public Double  getDouble(String col) { int i = idx(col); return i < 0 ? null : c.getDouble(i); }
        @Override public Float   getFloat(String col)  { int i = idx(col); return i < 0 ? null : c.getFloat(i); }
        @Override public Integer getInt(String col)    { int i = idx(col); return i < 0 ? null : c.getInt(i); }
        @Override public Long    getLong(String col)   { int i = idx(col); return i < 0 ? null : c.getLong(i); }
        @Override public String  getString(String col) { int i = idx(col); return i < 0 ? null : c.getString(i); }
        @Override public Boolean getBool(String col)   { Integer v = getInt(col); return v == null ? null : v != 0; }
    }

    private static final class JsonSource implements FieldSource {
        private final org.json.JSONObject o;
        JsonSource(org.json.JSONObject o) { this.o = o; }

        private boolean has(String col) { return o != null && o.has(col) && !o.isNull(col); }
        @Override public Double  getDouble(String col) { return has(col) ? o.optDouble(col) : null; }
        @Override public Float   getFloat(String col)  { return has(col) ? (float) o.optDouble(col) : null; }
        @Override public Integer getInt(String col)    { return has(col) ? o.optInt(col) : null; }
        @Override public Long    getLong(String col)   { return has(col) ? o.optLong(col) : null; }
        @Override public String  getString(String col) { return has(col) ? o.optString(col) : null; }
        @Override public Boolean getBool(String col) {
            if (!has(col)) return null;
            Object v = o.opt(col);
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number)  return ((Number) v).intValue() != 0;
            return Boolean.parseBoolean(String.valueOf(v));
        }
    }

    private static final class UnavailableSource implements FieldSource {
        private final FieldSource delegate;
        private final Set<String> unavailable;

        UnavailableSource(FieldSource delegate, Set<String> unavailable) {
            this.delegate = delegate;
            this.unavailable = unavailable;
        }

        private Object value(String col) {
            if (!unavailable.contains(col)) return null;
            UnavailableValueResolver.Resolution r =
                    UnavailableValueResolver.resolveSnapshotField(col);
            if (!r.handled()) {
                throw new IllegalArgumentException("no unavailable resolver for " + col);
            }
            return r.value();
        }

        @Override public Double getDouble(String col) {
            return unavailable.contains(col) ? (Double) value(col) : delegate.getDouble(col);
        }
        @Override public Float getFloat(String col) {
            return unavailable.contains(col) ? (Float) value(col) : delegate.getFloat(col);
        }
        @Override public Integer getInt(String col) {
            return unavailable.contains(col) ? (Integer) value(col) : delegate.getInt(col);
        }
        @Override public Long getLong(String col) {
            return unavailable.contains(col) ? (Long) value(col) : delegate.getLong(col);
        }
        @Override public String getString(String col) {
            return unavailable.contains(col) ? (String) value(col) : delegate.getString(col);
        }
        @Override public Boolean getBool(String col) {
            return unavailable.contains(col) ? (Boolean) value(col) : delegate.getBool(col);
        }
    }
}

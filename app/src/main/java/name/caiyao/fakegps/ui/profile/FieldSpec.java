package name.caiyao.fakegps.ui.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Metadata mapping UI fields to database columns.
 * Column names must exactly match DbHelper CREATE_TEMP_TABLE and Snapshot.fromCursor().
 */
public class FieldSpec {

    public enum FieldType { TEXT, INTEGER, DOUBLE, FLOAT, BOOLEAN }

    public final String dbColumn;
    public final String displayName;
    public final String hint;
    public final FieldType type;
    public final String unit;

    public FieldSpec(String dbColumn, String displayName, String hint, FieldType type, String unit) {
        this.dbColumn = dbColumn;
        this.displayName = displayName;
        this.hint = hint;
        this.type = type;
        this.unit = unit;
    }

    private static FieldSpec text(String col, String name, String hint) {
        return new FieldSpec(col, name, hint, FieldType.TEXT, null);
    }

    private static FieldSpec integer(String col, String name, String hint, String unit) {
        return new FieldSpec(col, name, hint, FieldType.INTEGER, unit);
    }

    private static FieldSpec integer(String col, String name, String hint) {
        return new FieldSpec(col, name, hint, FieldType.INTEGER, null);
    }

    private static FieldSpec dbl(String col, String name, String hint, String unit) {
        return new FieldSpec(col, name, hint, FieldType.DOUBLE, unit);
    }

    private static FieldSpec flt(String col, String name, String hint, String unit) {
        return new FieldSpec(col, name, hint, FieldType.FLOAT, unit);
    }

    private static FieldSpec bool(String col, String name, String hint) {
        return new FieldSpec(col, name, hint, FieldType.BOOLEAN, null);
    }

    /**
     * Returns all spoofable field categories in display order.
     * LinkedHashMap preserves insertion order.
     */
    public static LinkedHashMap<String, List<FieldSpec>> allCategories() {
        LinkedHashMap<String, List<FieldSpec>> map = new LinkedHashMap<>();

        // A. Location
        List<FieldSpec> location = new ArrayList<>();
        location.add(dbl("latitude", "Latitude", "-90 to 90", "\u00b0"));
        location.add(dbl("longitude", "Longitude", "-180 to 180", "\u00b0"));
        location.add(dbl("altitude", "Altitude", "0 to 10000", "m"));
        location.add(flt("speed", "Speed", "0 to 100", "m/s"));
        location.add(flt("bearing", "Bearing", "0 to 360", "\u00b0"));
        location.add(flt("accuracy", "Accuracy", "1 to 100", "m"));
        map.put("Location", location);

        // B. Cell Identity - GSM
        List<FieldSpec> cellGsm = new ArrayList<>();
        cellGsm.add(integer("mcc", "MCC", "Mobile Country Code, e.g. 460"));
        cellGsm.add(integer("mnc", "MNC", "Mobile Network Code, e.g. 0"));
        cellGsm.add(integer("lac", "LAC", "Location Area Code"));
        cellGsm.add(integer("cid", "CID", "Cell ID"));
        cellGsm.add(integer("arfcn", "ARFCN", "Absolute RF Channel Number"));
        cellGsm.add(integer("bsic", "BSIC", "Base Station Identity Code"));
        map.put("Cell Identity - GSM", cellGsm);

        // C. Cell Identity - WCDMA
        List<FieldSpec> cellWcdma = new ArrayList<>();
        cellWcdma.add(integer("psc", "PSC", "Primary Scrambling Code"));
        cellWcdma.add(integer("uarfcn", "UARFCN", "UTRA ARFCN"));
        map.put("Cell Identity - WCDMA", cellWcdma);

        // D. Cell Identity - LTE
        List<FieldSpec> cellLte = new ArrayList<>();
        cellLte.add(integer("tac", "TAC", "Tracking Area Code"));
        cellLte.add(integer("ci", "CI", "Cell Identity (28-bit)"));
        cellLte.add(integer("pci", "PCI", "Physical Cell ID, 0-503"));
        cellLte.add(integer("earfcn", "EARFCN", "E-UTRA ARFCN"));
        cellLte.add(integer("lte_bandwidth", "Bandwidth", "kHz", "kHz"));
        map.put("Cell Identity - LTE", cellLte);

        // E. Cell Identity - NR/5G
        List<FieldSpec> cellNr = new ArrayList<>();
        cellNr.add(integer("nci", "NCI", "NR Cell Identity (36-bit)"));
        cellNr.add(integer("nrarfcn", "NRARFCN", "NR ARFCN"));
        cellNr.add(integer("nr_pci", "NR PCI", "0-1007"));
        cellNr.add(integer("nr_tac", "NR TAC", "NR Tracking Area Code"));
        map.put("Cell Identity - NR/5G", cellNr);

        // F. Signal - GSM
        List<FieldSpec> sigGsm = new ArrayList<>();
        sigGsm.add(integer("gsm_rssi", "RSSI", "-113 to -51", "dBm"));
        sigGsm.add(integer("gsm_ber", "Bit Error Rate", "0-7, 99=unknown"));
        sigGsm.add(integer("gsm_ta", "Timing Advance", "0-219"));
        map.put("Signal - GSM", sigGsm);

        // G. Signal - WCDMA
        List<FieldSpec> sigWcdma = new ArrayList<>();
        sigWcdma.add(integer("wcdma_rssi", "RSSI", "-120 to -24", "dBm"));
        sigWcdma.add(integer("wcdma_rscp", "RSCP", "-120 to -24", "dBm"));
        sigWcdma.add(integer("wcdma_ecno", "Ec/No", "-24 to 1", "dB"));
        map.put("Signal - WCDMA", sigWcdma);

        // H. Signal - LTE
        List<FieldSpec> sigLte = new ArrayList<>();
        sigLte.add(integer("lte_rssi", "RSSI", "-120 to -25", "dBm"));
        sigLte.add(integer("lte_rsrp", "RSRP", "-140 to -44", "dBm"));
        sigLte.add(integer("lte_rsrq", "RSRQ", "-20 to -3", "dB"));
        sigLte.add(integer("lte_sinr", "SINR", "-23 to 40", "dB"));
        sigLte.add(integer("lte_cqi", "CQI", "0-15"));
        sigLte.add(integer("lte_ta", "Timing Advance", "0-1282"));
        map.put("Signal - LTE", sigLte);

        // I. Signal - NR/5G
        List<FieldSpec> sigNr = new ArrayList<>();
        sigNr.add(integer("nr_ss_rsrp", "SS-RSRP", "-140 to -44", "dBm"));
        sigNr.add(integer("nr_ss_rsrq", "SS-RSRQ", "-20 to -3", "dB"));
        sigNr.add(integer("nr_ss_sinr", "SS-SINR", "-23 to 40", "dB"));
        sigNr.add(integer("nr_csi_rsrp", "CSI-RSRP", "-140 to -44", "dBm"));
        sigNr.add(integer("nr_csi_rsrq", "CSI-RSRQ", "-20 to -3", "dB"));
        sigNr.add(integer("nr_csi_sinr", "CSI-SINR", "-23 to 40", "dB"));
        map.put("Signal - NR/5G", sigNr);

        // J. Signal Fluctuation
        List<FieldSpec> fluctuation = new ArrayList<>();
        fluctuation.add(bool("signal_fluctuation_enabled", "Enable Fluctuation",
                "Randomize signal within range"));
        fluctuation.add(integer("signal_fluctuation_range_db", "Range", "1-10", "dB"));
        map.put("Signal Fluctuation", fluctuation);

        // K. Carrier & Network
        List<FieldSpec> carrier = new ArrayList<>();
        carrier.add(integer("network_type", "Network Type", "0=Unknown, 13=LTE, 20=NR"));
        carrier.add(integer("data_network_type", "Data Network Type", "Same as network type"));
        carrier.add(integer("voice_network_type", "Voice Network Type", "Same as network type"));
        carrier.add(text("operator_name", "Operator Name", "e.g. China Mobile"));
        carrier.add(text("operator_numeric", "Operator Numeric", "e.g. 46000"));
        carrier.add(text("sim_operator", "SIM Operator", "e.g. 46000"));
        carrier.add(text("sim_operator_name", "SIM Operator Name", "e.g. CMCC"));
        carrier.add(text("sim_country_iso", "SIM Country ISO", "e.g. cn"));
        carrier.add(text("network_country_iso", "Network Country ISO", "e.g. cn"));
        carrier.add(bool("is_roaming", "Roaming", "Network roaming state"));
        carrier.add(integer("phone_type", "Phone Type", "0=None, 1=GSM, 2=CDMA"));
        map.put("Carrier & Network", carrier);

        // L+M. Service State & Display
        List<FieldSpec> service = new ArrayList<>();
        service.add(integer("service_state", "Service State", "0=In Service, 1=Out, 2=Emergency, 3=Off"));
        service.add(integer("data_state", "Data State", "0=Disconnected, 2=Connected"));
        service.add(integer("data_activity", "Data Activity", "0=None, 1=In, 2=Out, 3=InOut"));
        service.add(integer("override_network_type", "Override Network Type",
                "0=None, 1=LTE_CA, 2=NR_NSA, 3=NR_MMWAVE, 5=NR_SA"));
        // N. Physical Channel Config
        service.add(integer("band", "Band", "e.g. 1,3,7(LTE) 41,77,78(NR)"));
        service.add(integer("channel_bandwidth", "Channel Bandwidth", "kHz", "kHz"));
        service.add(integer("cell_bandwidth_downlink", "DL Bandwidth", "kHz", "kHz"));
        service.add(integer("physical_cell_id", "Physical Cell ID", "PCC PCI"));
        map.put("Service State & Channel", service);

        // O. WiFi
        List<FieldSpec> wifi = new ArrayList<>();
        wifi.add(text("wifi_ssid", "SSID", "Network name"));
        wifi.add(text("wifi_bssid", "BSSID", "AA:BB:CC:DD:EE:FF"));
        wifi.add(integer("wifi_rssi", "RSSI", "-90 to -40", "dBm"));
        wifi.add(integer("wifi_frequency", "Frequency", "2412-5825", "MHz"));
        wifi.add(integer("wifi_link_speed", "Link Speed", "Mbps", "Mbps"));
        wifi.add(integer("wifi_tx_link_speed", "TX Speed", "Mbps", "Mbps"));
        wifi.add(integer("wifi_rx_link_speed", "RX Speed", "Mbps", "Mbps"));
        wifi.add(integer("wifi_channel", "Channel", "1-165"));
        wifi.add(integer("wifi_standard", "WiFi Standard",
                "1=Legacy, 4=802.11n, 5=802.11ac, 6=802.11ax"));
        wifi.add(integer("wifi_security_type", "Security Type",
                "0=Open, 1=WEP, 2=WPA, 3=WPA2, 4=WPA3"));
        wifi.add(text("wifi_mac", "MAC Address", "AA:BB:CC:DD:EE:FF"));
        wifi.add(text("wifi_ip", "IP Address", "192.168.1.100"));
        wifi.add(bool("wifi_hidden", "Hide Scan Results", "Return empty scan list"));
        wifi.add(bool("wifi_enabled", "WiFi Enabled", "WiFi state"));
        map.put("WiFi", wifi);

        // P+Q. IP & Connectivity
        List<FieldSpec> ip = new ArrayList<>();
        ip.add(text("local_ipv4", "Local IPv4", "e.g. 192.168.1.100"));
        ip.add(text("local_ipv6", "Local IPv6", "e.g. fe80::1"));
        ip.add(text("dns_primary", "Primary DNS", "e.g. 8.8.8.8"));
        ip.add(text("dns_secondary", "Secondary DNS", "e.g. 8.8.4.4"));
        ip.add(text("gateway", "Gateway", "e.g. 192.168.1.1"));
        ip.add(text("subnet_mask", "Subnet Mask", "e.g. 255.255.255.0"));
        ip.add(text("connection_type", "Connection Type", "WIFI or MOBILE"));
        ip.add(text("interface_name", "Interface Name", "e.g. wlan0, rmnet0"));
        ip.add(text("neighbor_cells_json", "Neighbor Cells JSON", "Raw JSON array"));
        map.put("IP & Connectivity", ip);

        return map;
    }
}

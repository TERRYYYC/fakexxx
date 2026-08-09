package name.caiyao.fakegps.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity matching the existing "temp" table schema exactly.
 * Every column is nullable — NULL means "don't spoof this field" (passthrough).
 */
@Entity(tableName = "temp")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Location
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val accuracy: Float? = null,

    // Legacy
    val lac: Int? = null,
    val cid: Int? = null,
    val addname: String? = null,

    // Cell Identity — GSM
    val mcc: Int? = null,
    val mnc: Int? = null,
    val arfcn: Int? = null,
    val bsic: Int? = null,

    // Cell Identity — WCDMA
    val psc: Int? = null,
    val uarfcn: Int? = null,

    // Cell Identity — LTE
    val tac: Int? = null,
    val ci: Int? = null,
    val pci: Int? = null,
    val earfcn: Int? = null,
    @ColumnInfo(name = "lte_bandwidth") val lteBandwidth: Int? = null,

    // Cell Identity — NR/5G
    val nci: Long? = null,
    val nrarfcn: Int? = null,
    @ColumnInfo(name = "nr_pci") val nrPci: Int? = null,
    @ColumnInfo(name = "nr_tac") val nrTac: Int? = null,

    // Signal — GSM
    @ColumnInfo(name = "gsm_rssi") val gsmRssi: Int? = null,
    @ColumnInfo(name = "gsm_ber") val gsmBer: Int? = null,
    @ColumnInfo(name = "gsm_ta") val gsmTa: Int? = null,

    // Signal — WCDMA
    @ColumnInfo(name = "wcdma_rssi") val wcdmaRssi: Int? = null,
    @ColumnInfo(name = "wcdma_rscp") val wcdmaRscp: Int? = null,
    @ColumnInfo(name = "wcdma_ecno") val wcdmaEcno: Int? = null,

    // Signal — LTE
    @ColumnInfo(name = "lte_rssi") val lteRssi: Int? = null,
    @ColumnInfo(name = "lte_rsrp") val lteRsrp: Int? = null,
    @ColumnInfo(name = "lte_rsrq") val lteRsrq: Int? = null,
    @ColumnInfo(name = "lte_sinr") val lteSinr: Int? = null,
    @ColumnInfo(name = "lte_cqi") val lteCqi: Int? = null,
    @ColumnInfo(name = "lte_ta") val lteTa: Int? = null,

    // Signal — NR
    @ColumnInfo(name = "nr_ss_rsrp") val nrSsRsrp: Int? = null,
    @ColumnInfo(name = "nr_ss_rsrq") val nrSsRsrq: Int? = null,
    @ColumnInfo(name = "nr_ss_sinr") val nrSsSinr: Int? = null,
    @ColumnInfo(name = "nr_csi_rsrp") val nrCsiRsrp: Int? = null,
    @ColumnInfo(name = "nr_csi_rsrq") val nrCsiRsrq: Int? = null,
    @ColumnInfo(name = "nr_csi_sinr") val nrCsiSinr: Int? = null,

    // Signal Fluctuation
    @ColumnInfo(name = "signal_fluctuation_enabled") val signalFluctuationEnabled: Int? = null,
    @ColumnInfo(name = "signal_fluctuation_range_db") val signalFluctuationRangeDb: Int? = null,

    // Carrier & Network
    @ColumnInfo(name = "network_type") val networkType: Int? = null,
    @ColumnInfo(name = "data_network_type") val dataNetworkType: Int? = null,
    @ColumnInfo(name = "voice_network_type") val voiceNetworkType: Int? = null,
    @ColumnInfo(name = "operator_name") val operatorName: String? = null,
    @ColumnInfo(name = "operator_numeric") val operatorNumeric: String? = null,
    @ColumnInfo(name = "sim_operator") val simOperator: String? = null,
    @ColumnInfo(name = "sim_operator_name") val simOperatorName: String? = null,
    @ColumnInfo(name = "sim_country_iso") val simCountryIso: String? = null,
    @ColumnInfo(name = "network_country_iso") val networkCountryIso: String? = null,
    @ColumnInfo(name = "is_roaming") val isRoaming: Int? = null,
    @ColumnInfo(name = "phone_type") val phoneType: Int? = null,

    // Service State
    @ColumnInfo(name = "service_state") val serviceState: Int? = null,
    @ColumnInfo(name = "data_state") val dataState: Int? = null,
    @ColumnInfo(name = "data_activity") val dataActivity: Int? = null,

    // Display Info
    @ColumnInfo(name = "override_network_type") val overrideNetworkType: Int? = null,

    // Physical Channel Config
    val band: Int? = null,
    @ColumnInfo(name = "channel_bandwidth") val channelBandwidth: Int? = null,
    @ColumnInfo(name = "cell_bandwidth_downlink") val cellBandwidthDownlink: Int? = null,
    @ColumnInfo(name = "physical_cell_id") val physicalCellId: Int? = null,

    // WiFi
    @ColumnInfo(name = "wifi_ssid") val wifiSsid: String? = null,
    @ColumnInfo(name = "wifi_bssid") val wifiBssid: String? = null,
    @ColumnInfo(name = "wifi_rssi") val wifiRssi: Int? = null,
    @ColumnInfo(name = "wifi_frequency") val wifiFrequency: Int? = null,
    @ColumnInfo(name = "wifi_link_speed") val wifiLinkSpeed: Int? = null,
    @ColumnInfo(name = "wifi_tx_link_speed") val wifiTxLinkSpeed: Int? = null,
    @ColumnInfo(name = "wifi_rx_link_speed") val wifiRxLinkSpeed: Int? = null,
    @ColumnInfo(name = "wifi_channel") val wifiChannel: Int? = null,
    @ColumnInfo(name = "wifi_standard") val wifiStandard: Int? = null,
    @ColumnInfo(name = "wifi_security_type") val wifiSecurityType: Int? = null,
    @ColumnInfo(name = "wifi_mac") val wifiMac: String? = null,
    @ColumnInfo(name = "wifi_ip") val wifiIp: String? = null,
    @ColumnInfo(name = "wifi_hidden") val wifiHidden: Int? = null,
    @ColumnInfo(name = "wifi_enabled") val wifiEnabled: Int? = null,

    // IP & Connectivity
    @ColumnInfo(name = "local_ipv4") val localIpv4: String? = null,
    @ColumnInfo(name = "local_ipv6") val localIpv6: String? = null,
    @ColumnInfo(name = "dns_primary") val dnsPrimary: String? = null,
    @ColumnInfo(name = "dns_secondary") val dnsSecondary: String? = null,
    val gateway: String? = null,
    @ColumnInfo(name = "subnet_mask") val subnetMask: String? = null,
    @ColumnInfo(name = "connection_type") val connectionType: String? = null,
    @ColumnInfo(name = "interface_name") val interfaceName: String? = null,

    // Neighbor Cells
    @ColumnInfo(name = "neighbor_cells_json") val neighborCellsJson: String? = null,

    // Orthogonal third state. Canonical JSON array; null/[] means no explicit-unavailable fields.
    @ColumnInfo(name = "unavailable_fields") val unavailableFields: String? = null,
)

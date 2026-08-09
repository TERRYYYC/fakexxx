package name.caiyao.fakegps.model

/**
 * Comprehensive mock profile for device info spoofing.
 *
 * Design principle: ALL fields are nullable.
 *   null  = passthrough (use real device value, don't hook)
 *   value = spoof with this value
 *
 * Field grouping follows the UI card layout designed by @gemini25:
 *   Location → Cellular Identity → Signal Strength → Carrier/Network →
 *   WiFi → IP/Connectivity → Service State → Display Info
 *
 * Hook coverage informed by @gpt52's review of Android 12+ API surface
 * and open-source crowdsourcing apps (Network Survey, OMNT, Tower Collector).
 */
data class MockProfile(
    val id: Long = 0,
    val name: String = "Default",

    // ==========================================
    // A. LOCATION
    // ==========================================
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,       // meters
    val speed: Float? = null,           // m/s
    val bearing: Float? = null,         // degrees
    val accuracy: Float? = null,        // meters

    // ==========================================
    // B. CELL IDENTITY — GSM/2G
    // ==========================================
    val mcc: Int? = null,               // Mobile Country Code (460=China)
    val mnc: Int? = null,               // Mobile Network Code (0=CMCC, 1=CUCC, 11=CTCC)
    val lac: Int? = null,               // Location Area Code (0-65535)
    val cid: Int? = null,               // Cell ID (0-65535 for GSM)
    val arfcn: Int? = null,             // Absolute Radio Frequency Channel Number
    val bsic: Int? = null,              // Base Station Identity Code (0-63)

    // ==========================================
    // C. CELL IDENTITY — WCDMA/3G
    // ==========================================
    val psc: Int? = null,               // Primary Scrambling Code (0-511)
    val uarfcn: Int? = null,            // UTRA ARFCN

    // ==========================================
    // D. CELL IDENTITY — LTE/4G
    // ==========================================
    val tac: Int? = null,               // Tracking Area Code (0-65535)
    val ci: Int? = null,                // Cell Identity 28-bit (0-268435455)
    val pci: Int? = null,               // Physical Cell ID (0-503)
    val earfcn: Int? = null,            // E-UTRA ARFCN (0-262143)
    val lteBandwidth: Int? = null,      // Bandwidth in kHz

    // ==========================================
    // E. CELL IDENTITY — NR/5G
    //    (P0 per @gpt52: must cover CellIdentityNr)
    // ==========================================
    val nci: Long? = null,              // NR Cell Identity 36-bit
    val nrarfcn: Int? = null,           // NR ARFCN (0-3279165)
    val nrPci: Int? = null,             // NR Physical Cell ID (0-1007)
    val nrTac: Int? = null,             // NR Tracking Area Code 24-bit

    // ==========================================
    // F. SIGNAL STRENGTH — GSM
    // ==========================================
    val gsmRssi: Int? = null,           // -113 to -51 dBm
    val gsmBer: Int? = null,            // Bit Error Rate (0-7)
    val gsmTa: Int? = null,             // Timing Advance (0-219)

    // ==========================================
    // G. SIGNAL STRENGTH — WCDMA
    // ==========================================
    val wcdmaRssi: Int? = null,         // dBm
    val wcdmaRscp: Int? = null,         // -120 to -24 dBm
    val wcdmaEcno: Int? = null,         // -24 to 1 dB

    // ==========================================
    // H. SIGNAL STRENGTH — LTE
    // ==========================================
    val lteRssi: Int? = null,           // dBm
    val lteRsrp: Int? = null,           // -140 to -44 dBm
    val lteRsrq: Int? = null,           // -20 to -3 dB
    val lteSinr: Int? = null,           // -20 to +30 dB
    val lteCqi: Int? = null,            // Channel Quality Indicator (0-15)
    val lteTa: Int? = null,             // Timing Advance (0-1282)

    // ==========================================
    // I. SIGNAL STRENGTH — NR/5G
    //    (P0 per @gpt52: CellSignalStrengthNr)
    // ==========================================
    val nrSsRsrp: Int? = null,         // -156 to -31 dBm
    val nrSsRsrq: Int? = null,         // -43 to 20 dB
    val nrSsSinr: Int? = null,         // -23 to 40 dB
    val nrCsiRsrp: Int? = null,        // dBm
    val nrCsiRsrq: Int? = null,        // dB
    val nrCsiSinr: Int? = null,        // dB

    // ==========================================
    // J. SIGNAL FLUCTUATION
    //    (per @gemini25: range slider, not fixed value)
    // ==========================================
    val signalFluctuationEnabled: Boolean? = null,
    val signalFluctuationRangeDb: Int? = null,  // e.g., 10 means ±5dB around base

    // ==========================================
    // K. CARRIER & NETWORK
    // ==========================================
    val networkType: Int? = null,           // TelephonyManager.NETWORK_TYPE_LTE etc.
    val dataNetworkType: Int? = null,       // API 24+ getDataNetworkType()
    val voiceNetworkType: Int? = null,      // getVoiceNetworkType()
    val operatorName: String? = null,       // "中国移动"
    val operatorNumeric: String? = null,    // "46000"
    val simOperator: String? = null,        // SIM MCC+MNC
    val simOperatorName: String? = null,    // SIM carrier name
    val simCountryIso: String? = null,      // "cn"
    val networkCountryIso: String? = null,  // "cn"
    val isRoaming: Boolean? = null,
    val phoneType: Int? = null,             // GSM=1, CDMA=2

    // ==========================================
    // L. SERVICE STATE
    //    (P0 per @gpt52: ServiceState + NetworkRegistrationInfo)
    // ==========================================
    val serviceState: Int? = null,          // 0=IN_SERVICE, 1=OUT, 2=EMERGENCY, 3=POWER_OFF
    val dataState: Int? = null,             // 0=DISCONNECTED, 2=CONNECTED
    val dataActivity: Int? = null,          // 0=NONE, 1=IN, 2=OUT, 3=INOUT

    // ==========================================
    // M. DISPLAY INFO
    //    (P0 per @gpt52: TelephonyDisplayInfo)
    // ==========================================
    val overrideNetworkType: Int? = null,   // OVERRIDE_NETWORK_TYPE_NR_NSA, NR_ADVANCED etc.

    // ==========================================
    // N. PHYSICAL CHANNEL CONFIG
    //    (P0 per @gpt52: PhysicalChannelConfig)
    // ==========================================
    val band: Int? = null,                  // Band number (e.g. 1,3,7 LTE; 41,77,78 NR)
    val channelBandwidth: Int? = null,      // Bandwidth kHz (NOT band number)
    val cellBandwidthDownlink: Int? = null,  // kHz
    val physicalCellId: Int? = null,

    // ==========================================
    // O. WIFI
    // ==========================================
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,          // "AA:BB:CC:DD:EE:FF"
    val wifiRssi: Int? = null,              // -40 to -90 dBm
    val wifiFrequency: Int? = null,         // 2412/5180 MHz
    val wifiLinkSpeed: Int? = null,         // Mbps
    val wifiTxLinkSpeed: Int? = null,       // Mbps (API 29+)
    val wifiRxLinkSpeed: Int? = null,       // Mbps (API 29+)
    val wifiChannel: Int? = null,
    val wifiStandard: Int? = null,          // WIFI_STANDARD_11N/AC/AX (API 30+)
    val wifiSecurityType: Int? = null,      // (API 31+)
    val wifiMac: String? = null,
    val wifiIp: String? = null,             // "192.168.1.100"
    val wifiHidden: Boolean? = null,        // Hide real WiFi scan results
    val wifiEnabled: Boolean? = null,       // Fake WiFi state

    // ==========================================
    // P. IP & CONNECTIVITY (local only)
    //    (per @gpt52: public IP needs VPN/TUN, out of Hook scope)
    // ==========================================
    val localIpv4: String? = null,          // Local interface IP
    val localIpv6: String? = null,
    val dnsPrimary: String? = null,
    val dnsSecondary: String? = null,
    val gateway: String? = null,
    val subnetMask: String? = null,
    val connectionType: String? = null,     // "WIFI" or "MOBILE"
    val interfaceName: String? = null,      // "wlan0", "rmnet0"

    // ==========================================
    // Q. NEIGHBORING CELLS (fake scan results)
    // ==========================================
    val neighborCellsJson: String? = null   // JSON array of {type, lac, cid, pci, rssi}
)

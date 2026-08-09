package name.caiyao.fakegps.data.model

enum class FieldType { TEXT, INTEGER, DOUBLE, FLOAT, BOOLEAN }

data class FieldSpec(
    val dbColumn: String,
    val displayName: String,
    val hint: String,
    val type: FieldType,
    val unit: String? = null,
) {
    companion object {
        fun allCategories(): LinkedHashMap<String, List<FieldSpec>> = linkedMapOf(
            "定位" to listOf(
                dbl("latitude", "纬度", "-90 ~ 90", "\u00b0"),
                dbl("longitude", "经度", "-180 ~ 180", "\u00b0"),
                dbl("altitude", "海拔", "0 ~ 10000", "m"),
                flt("speed", "速度", "0 ~ 100", "m/s"),
                flt("bearing", "方向", "0 ~ 360", "\u00b0"),
                flt("accuracy", "精度", "1 ~ 100", "m"),
            ),
            "小区标识 - GSM" to listOf(
                int("mcc", "MCC", "移动国家代码，如 460"),
                int("mnc", "MNC", "移动网络代码，如 0"),
                int("lac", "LAC", "位置区域码"),
                int("cid", "CID", "小区 ID"),
                int("arfcn", "ARFCN", "绝对射频信道号"),
                int("bsic", "BSIC", "基站识别码"),
            ),
            "小区标识 - WCDMA" to listOf(
                int("psc", "PSC", "主扰码"),
                int("uarfcn", "UARFCN", "UTRA ARFCN"),
            ),
            "小区标识 - LTE" to listOf(
                int("tac", "TAC", "跟踪区域码"),
                int("ci", "CI", "小区标识 (28-bit)"),
                int("pci", "PCI", "物理小区 ID，0-503"),
                int("earfcn", "EARFCN", "E-UTRA ARFCN"),
                int("lte_bandwidth", "带宽", "kHz", "kHz"),
            ),
            "小区标识 - NR/5G" to listOf(
                int("nci", "NCI", "NR 小区标识 (36-bit)"),
                int("nrarfcn", "NRARFCN", "NR ARFCN"),
                int("nr_pci", "NR PCI", "0-1007"),
                int("nr_tac", "NR TAC", "NR 跟踪区域码"),
            ),
            "信号 - GSM" to listOf(
                int("gsm_rssi", "RSSI", "-113 ~ -51", "dBm"),
                int("gsm_ber", "误码率", "0-7，99=未知"),
                int("gsm_ta", "时间提前量", "0-219"),
            ),
            "信号 - WCDMA" to listOf(
                int("wcdma_rssi", "RSSI", "-120 ~ -24", "dBm"),
                int("wcdma_rscp", "RSCP", "-120 ~ -24", "dBm"),
                int("wcdma_ecno", "Ec/No", "-24 ~ 1", "dB"),
            ),
            "信号 - LTE" to listOf(
                int("lte_rssi", "RSSI", "-120 ~ -25", "dBm"),
                int("lte_rsrp", "RSRP", "-140 ~ -44", "dBm"),
                int("lte_rsrq", "RSRQ", "-20 ~ -3", "dB"),
                int("lte_sinr", "SINR", "-23 ~ 40", "dB"),
                int("lte_cqi", "CQI", "0-15"),
                int("lte_ta", "时间提前量", "0-1282"),
            ),
            "信号 - NR/5G" to listOf(
                int("nr_ss_rsrp", "SS-RSRP", "-140 ~ -44", "dBm"),
                int("nr_ss_rsrq", "SS-RSRQ", "-20 ~ -3", "dB"),
                int("nr_ss_sinr", "SS-SINR", "-23 ~ 40", "dB"),
                int("nr_csi_rsrp", "CSI-RSRP", "-140 ~ -44", "dBm"),
                int("nr_csi_rsrq", "CSI-RSRQ", "-20 ~ -3", "dB"),
                int("nr_csi_sinr", "CSI-SINR", "-23 ~ 40", "dB"),
            ),
            "信号波动" to listOf(
                bool("signal_fluctuation_enabled", "启用波动", "在范围内随机化信号"),
                int("signal_fluctuation_range_db", "波动范围", "1-10", "dB"),
            ),
            "运营商与网络" to listOf(
                int("network_type", "网络类型", "0=未知, 13=LTE, 20=NR"),
                int("data_network_type", "数据网络类型", "同网络类型"),
                int("voice_network_type", "语音网络类型", "同网络类型"),
                text("operator_name", "运营商名称", "如 中国移动"),
                text("operator_numeric", "运营商代码", "如 46000"),
                text("sim_operator", "SIM 运营商", "如 46000"),
                text("sim_operator_name", "SIM 运营商名称", "如 CMCC"),
                text("sim_country_iso", "SIM 国家代码", "如 cn"),
                text("network_country_iso", "网络国家代码", "如 cn"),
                bool("is_roaming", "漫游", "网络漫游状态"),
                int("phone_type", "电话类型", "0=无, 1=GSM, 2=CDMA"),
            ),
            "服务状态与信道" to listOf(
                int("service_state", "服务状态", "0=服务中, 1=无, 2=紧急, 3=关闭"),
                int("data_state", "数据状态", "0=断开, 2=已连接"),
                int("data_activity", "数据活动", "0=无, 1=入, 2=出, 3=双向"),
                int("override_network_type", "覆盖网络类型", "0=无, 1=LTE_CA, 2=NR_NSA, 5=NR_SA"),
                int("band", "频段", "如 1,3,7(LTE) 41,77,78(NR)"),
                int("channel_bandwidth", "信道带宽", "kHz", "kHz"),
                int("cell_bandwidth_downlink", "下行带宽", "kHz", "kHz"),
                int("physical_cell_id", "物理小区 ID", "PCC PCI"),
            ),
            "WiFi" to listOf(
                text("wifi_ssid", "SSID", "网络名称"),
                text("wifi_bssid", "BSSID", "AA:BB:CC:DD:EE:FF"),
                int("wifi_rssi", "RSSI", "-90 ~ -40", "dBm"),
                int("wifi_frequency", "频率", "2412-5825", "MHz"),
                int("wifi_link_speed", "连接速率", "Mbps", "Mbps"),
                int("wifi_tx_link_speed", "发送速率", "Mbps", "Mbps"),
                int("wifi_rx_link_speed", "接收速率", "Mbps", "Mbps"),
                int("wifi_channel", "信道", "1-165"),
                int("wifi_standard", "WiFi 标准", "1=旧版, 4=n, 5=ac, 6=ax"),
                int("wifi_security_type", "安全类型", "0=开放, 1=WEP, 2=WPA, 3=WPA2, 4=WPA3"),
                text("wifi_mac", "MAC 地址", "AA:BB:CC:DD:EE:FF"),
                text("wifi_ip", "IP 地址", "192.168.1.100"),
                bool("wifi_hidden", "隐藏扫描结果", "返回空扫描列表"),
                bool("wifi_enabled", "WiFi 已启用", "WiFi 状态"),
            ),
            "IP 与连接" to listOf(
                text("local_ipv4", "本地 IPv4", "如 192.168.1.100"),
                text("local_ipv6", "本地 IPv6", "如 fe80::1"),
                text("dns_primary", "主 DNS", "如 8.8.8.8"),
                text("dns_secondary", "备 DNS", "如 8.8.4.4"),
                text("gateway", "网关", "如 192.168.1.1"),
                text("subnet_mask", "子网掩码", "如 255.255.255.0"),
                text("connection_type", "连接类型", "WIFI 或 MOBILE"),
                text("interface_name", "接口名称", "如 wlan0, rmnet0"),
                text("neighbor_cells_json", "邻区小区 JSON", "JSON 数组"),
            ),
        )

        private fun text(col: String, name: String, hint: String) =
            FieldSpec(col, name, hint, FieldType.TEXT)

        private fun int(col: String, name: String, hint: String, unit: String? = null) =
            FieldSpec(col, name, hint, FieldType.INTEGER, unit)

        private fun dbl(col: String, name: String, hint: String, unit: String? = null) =
            FieldSpec(col, name, hint, FieldType.DOUBLE, unit)

        private fun flt(col: String, name: String, hint: String, unit: String? = null) =
            FieldSpec(col, name, hint, FieldType.FLOAT, unit)

        private fun bool(col: String, name: String, hint: String) =
            FieldSpec(col, name, hint, FieldType.BOOLEAN)
    }
}

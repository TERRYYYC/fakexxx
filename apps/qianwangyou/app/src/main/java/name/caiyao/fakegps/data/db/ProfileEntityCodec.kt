package name.caiyao.fakegps.data.db

import java.util.Locale
import name.caiyao.fakegps.config.UnavailableFieldSet
import name.caiyao.fakegps.config.UnavailablePayloadContract

/** One typed boundary between canonical profile field strings and the Room entity. */
object ProfileEntityCodec {
    const val UNAVAILABLE_TOKEN = "--"

    fun fromDraft(
        draft: Map<String, String>,
        id: Long = 0L,
        addname: String? = null,
    ): ProfileEntity {
        val unavailable = draft.filterValues { it == UNAVAILABLE_TOKEN }.keys.toList()
        val values = draft.filterValues { it != UNAVAILABLE_TOKEN }
        val validatedUnavailable =
            UnavailablePayloadContract.validate(values.keys, unavailable).asSet()
        val lat = values["latitude"]?.toDoubleOrNull()
        val lon = values["longitude"]?.toDoubleOrNull()
        val canonicalName = addname?.takeIf { it.isNotBlank() }
            ?: generatedName(lat, lon)

        return ProfileEntity(
            id = id,
            latitude = lat,
            longitude = lon,
            altitude = values["altitude"]?.toDoubleOrNull(),
            speed = values["speed"]?.toFloatOrNull(),
            bearing = values["bearing"]?.toFloatOrNull(),
            accuracy = values["accuracy"]?.toFloatOrNull(),
            lac = values["lac"]?.toIntOrNull(),
            cid = values["cid"]?.toIntOrNull(),
            addname = canonicalName,
            mcc = values["mcc"]?.toIntOrNull(),
            mnc = values["mnc"]?.toIntOrNull(),
            arfcn = values["arfcn"]?.toIntOrNull(),
            bsic = values["bsic"]?.toIntOrNull(),
            psc = values["psc"]?.toIntOrNull(),
            uarfcn = values["uarfcn"]?.toIntOrNull(),
            tac = values["tac"]?.toIntOrNull(),
            ci = values["ci"]?.toIntOrNull(),
            pci = values["pci"]?.toIntOrNull(),
            earfcn = values["earfcn"]?.toIntOrNull(),
            lteBandwidth = values["lte_bandwidth"]?.toIntOrNull(),
            nci = values["nci"]?.toLongOrNull(),
            nrarfcn = values["nrarfcn"]?.toIntOrNull(),
            nrPci = values["nr_pci"]?.toIntOrNull(),
            nrTac = values["nr_tac"]?.toIntOrNull(),
            gsmRssi = values["gsm_rssi"]?.toIntOrNull(),
            gsmBer = values["gsm_ber"]?.toIntOrNull(),
            gsmTa = values["gsm_ta"]?.toIntOrNull(),
            wcdmaRssi = values["wcdma_rssi"]?.toIntOrNull(),
            wcdmaRscp = values["wcdma_rscp"]?.toIntOrNull(),
            wcdmaEcno = values["wcdma_ecno"]?.toIntOrNull(),
            lteRssi = values["lte_rssi"]?.toIntOrNull(),
            lteRsrp = values["lte_rsrp"]?.toIntOrNull(),
            lteRsrq = values["lte_rsrq"]?.toIntOrNull(),
            lteSinr = values["lte_sinr"]?.toIntOrNull(),
            lteCqi = values["lte_cqi"]?.toIntOrNull(),
            lteTa = values["lte_ta"]?.toIntOrNull(),
            nrSsRsrp = values["nr_ss_rsrp"]?.toIntOrNull(),
            nrSsRsrq = values["nr_ss_rsrq"]?.toIntOrNull(),
            nrSsSinr = values["nr_ss_sinr"]?.toIntOrNull(),
            nrCsiRsrp = values["nr_csi_rsrp"]?.toIntOrNull(),
            nrCsiRsrq = values["nr_csi_rsrq"]?.toIntOrNull(),
            nrCsiSinr = values["nr_csi_sinr"]?.toIntOrNull(),
            signalFluctuationEnabled = values["signal_fluctuation_enabled"]?.toIntOrNull(),
            signalFluctuationRangeDb = values["signal_fluctuation_range_db"]?.toIntOrNull(),
            networkType = values["network_type"]?.toIntOrNull(),
            dataNetworkType = values["data_network_type"]?.toIntOrNull(),
            voiceNetworkType = values["voice_network_type"]?.toIntOrNull(),
            operatorName = values["operator_name"],
            operatorNumeric = values["operator_numeric"],
            simOperator = values["sim_operator"],
            simOperatorName = values["sim_operator_name"],
            simCountryIso = values["sim_country_iso"],
            networkCountryIso = values["network_country_iso"],
            isRoaming = values["is_roaming"]?.toIntOrNull(),
            phoneType = values["phone_type"]?.toIntOrNull(),
            serviceState = values["service_state"]?.toIntOrNull(),
            dataState = values["data_state"]?.toIntOrNull(),
            dataActivity = values["data_activity"]?.toIntOrNull(),
            overrideNetworkType = values["override_network_type"]?.toIntOrNull(),
            band = values["band"]?.toIntOrNull(),
            channelBandwidth = values["channel_bandwidth"]?.toIntOrNull(),
            cellBandwidthDownlink = values["cell_bandwidth_downlink"]?.toIntOrNull(),
            physicalCellId = values["physical_cell_id"]?.toIntOrNull(),
            wifiSsid = values["wifi_ssid"],
            wifiBssid = values["wifi_bssid"],
            wifiRssi = values["wifi_rssi"]?.toIntOrNull(),
            wifiFrequency = values["wifi_frequency"]?.toIntOrNull(),
            wifiLinkSpeed = values["wifi_link_speed"]?.toIntOrNull(),
            wifiTxLinkSpeed = values["wifi_tx_link_speed"]?.toIntOrNull(),
            wifiRxLinkSpeed = values["wifi_rx_link_speed"]?.toIntOrNull(),
            wifiChannel = values["wifi_channel"]?.toIntOrNull(),
            wifiStandard = values["wifi_standard"]?.toIntOrNull(),
            wifiSecurityType = values["wifi_security_type"]?.toIntOrNull(),
            wifiMac = values["wifi_mac"],
            wifiIp = values["wifi_ip"],
            wifiHidden = values["wifi_hidden"]?.toIntOrNull(),
            wifiEnabled = values["wifi_enabled"]?.toIntOrNull(),
            localIpv4 = values["local_ipv4"],
            localIpv6 = values["local_ipv6"],
            dnsPrimary = values["dns_primary"],
            dnsSecondary = values["dns_secondary"],
            gateway = values["gateway"],
            subnetMask = values["subnet_mask"],
            connectionType = values["connection_type"],
            interfaceName = values["interface_name"],
            neighborCellsJson = values["neighbor_cells_json"],
            unavailableFields = UnavailableFieldSet.encode(validatedUnavailable),
        )
    }

    fun toDraft(entity: ProfileEntity): Map<String, String> {
        val values = mutableMapOf<String, String>()
        fun put(key: String, value: Any?) {
            if (value != null) values[key] = value.toString()
        }
        put("latitude", entity.latitude)
        put("longitude", entity.longitude)
        put("altitude", entity.altitude)
        put("speed", entity.speed)
        put("bearing", entity.bearing)
        put("accuracy", entity.accuracy)
        put("lac", entity.lac)
        put("cid", entity.cid)
        put("mcc", entity.mcc)
        put("mnc", entity.mnc)
        put("arfcn", entity.arfcn)
        put("bsic", entity.bsic)
        put("psc", entity.psc)
        put("uarfcn", entity.uarfcn)
        put("tac", entity.tac)
        put("ci", entity.ci)
        put("pci", entity.pci)
        put("earfcn", entity.earfcn)
        put("lte_bandwidth", entity.lteBandwidth)
        put("nci", entity.nci)
        put("nrarfcn", entity.nrarfcn)
        put("nr_pci", entity.nrPci)
        put("nr_tac", entity.nrTac)
        put("gsm_rssi", entity.gsmRssi)
        put("gsm_ber", entity.gsmBer)
        put("gsm_ta", entity.gsmTa)
        put("wcdma_rssi", entity.wcdmaRssi)
        put("wcdma_rscp", entity.wcdmaRscp)
        put("wcdma_ecno", entity.wcdmaEcno)
        put("lte_rssi", entity.lteRssi)
        put("lte_rsrp", entity.lteRsrp)
        put("lte_rsrq", entity.lteRsrq)
        put("lte_sinr", entity.lteSinr)
        put("lte_cqi", entity.lteCqi)
        put("lte_ta", entity.lteTa)
        put("nr_ss_rsrp", entity.nrSsRsrp)
        put("nr_ss_rsrq", entity.nrSsRsrq)
        put("nr_ss_sinr", entity.nrSsSinr)
        put("nr_csi_rsrp", entity.nrCsiRsrp)
        put("nr_csi_rsrq", entity.nrCsiRsrq)
        put("nr_csi_sinr", entity.nrCsiSinr)
        put("signal_fluctuation_enabled", entity.signalFluctuationEnabled)
        put("signal_fluctuation_range_db", entity.signalFluctuationRangeDb)
        put("network_type", entity.networkType)
        put("data_network_type", entity.dataNetworkType)
        put("voice_network_type", entity.voiceNetworkType)
        put("operator_name", entity.operatorName)
        put("operator_numeric", entity.operatorNumeric)
        put("sim_operator", entity.simOperator)
        put("sim_operator_name", entity.simOperatorName)
        put("sim_country_iso", entity.simCountryIso)
        put("network_country_iso", entity.networkCountryIso)
        put("is_roaming", entity.isRoaming)
        put("phone_type", entity.phoneType)
        put("service_state", entity.serviceState)
        put("data_state", entity.dataState)
        put("data_activity", entity.dataActivity)
        put("override_network_type", entity.overrideNetworkType)
        put("band", entity.band)
        put("channel_bandwidth", entity.channelBandwidth)
        put("cell_bandwidth_downlink", entity.cellBandwidthDownlink)
        put("physical_cell_id", entity.physicalCellId)
        put("wifi_ssid", entity.wifiSsid)
        put("wifi_bssid", entity.wifiBssid)
        put("wifi_rssi", entity.wifiRssi)
        put("wifi_frequency", entity.wifiFrequency)
        put("wifi_link_speed", entity.wifiLinkSpeed)
        put("wifi_tx_link_speed", entity.wifiTxLinkSpeed)
        put("wifi_rx_link_speed", entity.wifiRxLinkSpeed)
        put("wifi_channel", entity.wifiChannel)
        put("wifi_standard", entity.wifiStandard)
        put("wifi_security_type", entity.wifiSecurityType)
        put("wifi_mac", entity.wifiMac)
        put("wifi_ip", entity.wifiIp)
        put("wifi_hidden", entity.wifiHidden)
        put("wifi_enabled", entity.wifiEnabled)
        put("local_ipv4", entity.localIpv4)
        put("local_ipv6", entity.localIpv6)
        put("dns_primary", entity.dnsPrimary)
        put("dns_secondary", entity.dnsSecondary)
        put("gateway", entity.gateway)
        put("subnet_mask", entity.subnetMask)
        put("connection_type", entity.connectionType)
        put("interface_name", entity.interfaceName)
        put("neighbor_cells_json", entity.neighborCellsJson)
        for (column in UnavailableFieldSet.decode(entity.unavailableFields)) {
            values[column] = UNAVAILABLE_TOKEN
        }
        return values
    }

    fun canonical(entity: ProfileEntity): ProfileEntity = entity.copy(id = 0L)

    fun generatedName(latitude: Double?, longitude: Double?): String? =
        if (latitude != null && longitude != null) {
            String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        } else {
            null
        }
}

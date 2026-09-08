package name.caiyao.fakegps.probe

object ProbeFieldContract {
    const val VERSION = 3

    /**
     * The wifi surfaces HookProbe.collectWifi observes (report paths under
     * "wifi."). Mirrors HookUtils.hookWifi one-to-one so the wifi acceptance
     * matrix can never expect a path no probe emits. VERSION bumps 2 -> 3:
     * the wifi observation group was added.
     */
    val wifiFields: Set<String> = setOf(
        "enabled",
        "state",
        "scanResultsCount",
        "ssid",
        "bssid",
        "rssi",
        "frequency",
        "mac",
        "linkSpeed",
        "txLinkSpeed",
        "rxLinkSpeed",
        "standard",
        "securityType",
        "ip",
    )

    val cellInfoPaths: Set<String> = setOf("sync", "request")

    val physicalChannelDeliveryModes: Set<String> = setOf(
        "framework",
        "hook_replay_after_permission_denied",
        "not_requested",
    )

    val neighborRadios: Set<String> = setOf("gsm", "lte", "wcdma")

    val radioFields: Map<String, Set<String>> = linkedMapOf(
        "lte" to setOf(
            "registered",
            "mccString",
            "mncString",
            "operatorAlphaLong",
            "operatorAlphaShort",
            "tac",
            "ci",
            "pci",
            "earfcn",
            "bandwidth",
            "rssi",
            "dbm",
            "rsrp",
            "rsrq",
            "rssnr",
            "cqi",
            "timingAdvance",
        ),
        "gsm" to setOf(
            "registered",
            "mccString",
            "mncString",
            "operatorAlphaLong",
            "operatorAlphaShort",
            "lac",
            "cid",
            "arfcn",
            "bsic",
            "dbm",
            "bitErrorRate",
            "timingAdvance",
        ),
        "wcdma" to setOf(
            "registered",
            "mccString",
            "mncString",
            "operatorAlphaLong",
            "operatorAlphaShort",
            "lac",
            "cid",
            "psc",
            "uarfcn",
            "dbm",
            "ecNo",
        ),
        "nr" to setOf(
            "registered",
            "mccString",
            "mncString",
            "operatorAlphaLong",
            "operatorAlphaShort",
            "nci",
            "nrarfcn",
            "pci",
            "tac",
            "dbm",
            "ssRsrp",
            "ssRsrq",
            "ssSinr",
            "csiRsrp",
            "csiRsrq",
            "csiSinr",
        ),
    )

    val telephonyFields: Set<String> = setOf(
        "networkOperatorName",
        "networkOperator",
        "simOperator",
        "simOperatorName",
        "simCountryIso",
        "networkCountryIso",
        "isNetworkRoaming",
        "phoneType",
        "networkType",
        "dataNetworkType",
        "voiceNetworkType",
        "dataState",
        "dataActivity",
        "serviceState",
        "serviceStateDetails",
    )

    val serviceStateFields: Set<String> = setOf(
        "state",
        "operatorAlphaLong",
        "operatorAlphaShort",
        "operatorNumeric",
        "roaming",
        "registrationPlmn",
        "registrationRoaming",
        "registrationOperatorAlphaLong",
        "registrationOperatorAlphaShort",
    )

    val callbackFields: Map<String, Set<String>> = linkedMapOf(
        "serviceState" to serviceStateFields,
        "displayInfo" to setOf("networkType", "overrideNetworkType"),
        "physicalChannel" to setOf(
            "cellBandwidthDownlinkKhz",
            "cellBandwidthUplinkKhz",
            "physicalCellId",
            "connectionStatus",
            "networkType",
            "band",
            "downlinkChannelNumber",
            "uplinkChannelNumber",
        ),
    )
}

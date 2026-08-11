package name.caiyao.fakegps.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeFieldContractTest {

    @Test
    fun radioFieldContractCoversEveryConfiguredIdentityAndSignalGetter() {
        assertEquals(
            setOf(
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
            ProbeFieldContract.radioFields.getValue("lte"),
        )
        assertEquals(
            setOf(
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
            ProbeFieldContract.radioFields.getValue("gsm"),
        )
        assertEquals(
            setOf(
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
            ProbeFieldContract.radioFields.getValue("wcdma"),
        )
        assertEquals(
            setOf(
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
            ProbeFieldContract.radioFields.getValue("nr"),
        )
    }

    @Test
    fun telephonyAndCallbackContractsCoverConfiguredManagerAndStateGetters() {
        assertEquals(
            setOf(
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
            ),
            ProbeFieldContract.telephonyFields,
        )
        assertEquals(
            setOf(
                "state",
                "operatorAlphaLong",
                "operatorAlphaShort",
                "operatorNumeric",
                "roaming",
                "registrationPlmn",
                "registrationRoaming",
                "registrationOperatorAlphaLong",
                "registrationOperatorAlphaShort",
            ),
            ProbeFieldContract.serviceStateFields,
        )
        assertEquals(
            ProbeFieldContract.serviceStateFields,
            ProbeFieldContract.callbackFields.getValue("serviceState"),
        )
        assertEquals(
            setOf("networkType", "overrideNetworkType"),
            ProbeFieldContract.callbackFields.getValue("displayInfo"),
        )
        assertEquals(
            setOf(
                "cellBandwidthDownlinkKhz",
                "cellBandwidthUplinkKhz",
                "physicalCellId",
                "connectionStatus",
                "networkType",
                "band",
                "downlinkChannelNumber",
                "uplinkChannelNumber",
            ),
            ProbeFieldContract.callbackFields.getValue("physicalChannel"),
        )
    }

    @Test
    fun terminalSchemaRequiresAllIndependentCellDeliveryPaths() {
        assertEquals(
            setOf("sync", "request"),
            ProbeFieldContract.cellInfoPaths,
        )
        assertEquals(
            setOf("gsm", "lte", "wcdma"),
            ProbeFieldContract.neighborRadios,
        )
        assertEquals(
            setOf(
                "framework",
                "hook_replay_after_permission_denied",
                "not_requested",
            ),
            ProbeFieldContract.physicalChannelDeliveryModes,
        )
        assertEquals(2, ProbeFieldContract.VERSION)
    }
}

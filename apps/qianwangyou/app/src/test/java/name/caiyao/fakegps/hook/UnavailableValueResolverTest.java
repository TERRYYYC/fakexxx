package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;

public class UnavailableValueResolverTest {

    @Test
    public void resolverBytecodeDoesNotCallJava9CollectionFactories() throws Exception {
        String resource = "/" + UnavailableValueResolver.class.getName().replace('.', '/')
                + ".class";
        try (InputStream input =
                     UnavailableValueResolver.class.getResourceAsStream(resource)) {
            String constantPool =
                    new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(
                    "Set.of is unavailable on the supported API 24-25 range",
                    constantPool.contains("\u0001\u0000\u0002of"));
        }
    }

    @Test
    public void lacAndCidResolvePerSurface() {
        assertEquals(Integer.MAX_VALUE,
                UnavailableValueResolver.resolve("lac",
                        UnavailableValueResolver.Surface.CELL_IDENTITY_INT).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("lac",
                        UnavailableValueResolver.Surface.GSM_CELL_LOCATION).value());
        assertEquals(Integer.MAX_VALUE,
                UnavailableValueResolver.resolve("cid",
                        UnavailableValueResolver.Surface.CELL_IDENTITY_INT).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("cid",
                        UnavailableValueResolver.Surface.GSM_CELL_LOCATION).value());
    }

    @Test
    public void plmnHasDistinctLegacyIntAndNullableStringForms() {
        assertEquals(Integer.MAX_VALUE,
                UnavailableValueResolver.resolve("mcc",
                        UnavailableValueResolver.Surface.CELL_IDENTITY_INT).value());

        UnavailableValueResolver.Resolution nullable =
                UnavailableValueResolver.resolve("mcc",
                        UnavailableValueResolver.Surface.CELL_IDENTITY_PLMN_STRING);
        assertTrue(nullable.handled());
        assertNull(nullable.value());
    }

    @Test
    public void cellularSignalAndNrIdentityUseFrameworkSentinels() {
        assertEquals(Integer.MAX_VALUE,
                UnavailableValueResolver.resolve("lte_rsrp",
                        UnavailableValueResolver.Surface.CELL_SIGNAL_INT).value());
        assertEquals(Long.MAX_VALUE,
                UnavailableValueResolver.resolve("nci",
                        UnavailableValueResolver.Surface.CELL_IDENTITY_LONG).value());
    }

    @Test
    public void carrierAndNetworkSurfacesUseTheirOwnUnknowns() {
        assertEquals("",
                UnavailableValueResolver.resolve("operator_name",
                        UnavailableValueResolver.Surface.TELEPHONY_TEXT).value());
        UnavailableValueResolver.Resolution objectName =
                UnavailableValueResolver.resolve("operator_name",
                        UnavailableValueResolver.Surface.CARRIER_OBJECT_TEXT);
        assertTrue(objectName.handled());
        assertNull(objectName.value());
        UnavailableValueResolver.Resolution objectNumeric =
                UnavailableValueResolver.resolve("operator_numeric",
                        UnavailableValueResolver.Surface.CARRIER_OBJECT_TEXT);
        assertTrue(objectNumeric.handled());
        assertNull(objectNumeric.value());
        assertEquals(0,
                UnavailableValueResolver.resolve("network_type",
                        UnavailableValueResolver.Surface.NETWORK_TYPE).value());
        assertEquals(0,
                UnavailableValueResolver.resolve("phone_type",
                        UnavailableValueResolver.Surface.PHONE_TYPE).value());
        assertEquals(0, UnavailableValueResolver.dataStateUnavailableValueForApi(28));
        assertEquals(-1, UnavailableValueResolver.dataStateUnavailableValueForApi(29));
        assertEquals(0,
                UnavailableValueResolver.resolve("data_activity",
                        UnavailableValueResolver.Surface.DATA_ACTIVITY).value());
        assertEquals(0,
                UnavailableValueResolver.resolve("override_network_type",
                        UnavailableValueResolver.Surface.DISPLAY_OVERRIDE).value());
    }

    @Test
    public void physicalSurfacesUseNativeEmptyForms() {
        assertEquals(0,
                UnavailableValueResolver.resolve("band",
                        UnavailableValueResolver.Surface.PHYSICAL_ZERO).value());
        assertEquals(0,
                UnavailableValueResolver.resolve("channel_bandwidth",
                        UnavailableValueResolver.Surface.PHYSICAL_ZERO).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("physical_cell_id",
                        UnavailableValueResolver.Surface.PHYSICAL_CELL_ID).value());
    }

    /**
     * Wi-Fi integers use their own platform unknowns, never the cellular MAX_VALUE:
     * <ul>
     *   <li>{@code WifiInfo.INVALID_RSSI = -127} (reset() leaves RSSI at INVALID_RSSI);</li>
     *   <li>{@code WifiInfo.LINK_SPEED_UNKNOWN = -1} and {@code UNKNOWN_FREQUENCY = -1}
     *       (reset()/clear() default for link speed, tx/rx link speed and frequency).</li>
     * </ul>
     */
    @Test
    public void wifiIntegerSurfacesUseWifiSentinels() {
        assertEquals(-127,
                UnavailableValueResolver.resolve("wifi_rssi",
                        UnavailableValueResolver.Surface.WIFI_RSSI).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("wifi_frequency",
                        UnavailableValueResolver.Surface.WIFI_INFO_INT).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("wifi_link_speed",
                        UnavailableValueResolver.Surface.WIFI_INFO_INT).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("wifi_tx_link_speed",
                        UnavailableValueResolver.Surface.WIFI_INFO_INT).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("wifi_rx_link_speed",
                        UnavailableValueResolver.Surface.WIFI_INFO_INT).value());
    }

    /**
     * getWifiStandard() unknown is {@code ScanResult.WIFI_STANDARD_UNKNOWN = 0} (public constant,
     * identical from Android 11 through 15); {@code WifiInfo.mWifiStandard} has no field
     * initializer, so a fresh/unset WifiInfo already reports 0.
     */
    @Test
    public void wifiStandardUsesScanResultStandardUnknown() {
        assertEquals(0,
                UnavailableValueResolver.resolve("wifi_standard",
                        UnavailableValueResolver.Surface.WIFI_STANDARD).value());
    }

    /**
     * getSSID() unknown is {@code WifiManager.UNKNOWN_SSID = "&lt;unknown ssid&gt;"} — the double
     * quotes are part of the value; getBSSID() unknown is {@code null}
     * (WifiInfo.mBSSID = null in clear()/reset()).
     */
    @Test
    public void wifiIdentityTextSurfacesUsePlatformUnknowns() {
        UnavailableValueResolver.Resolution ssid =
                UnavailableValueResolver.resolve("wifi_ssid",
                        UnavailableValueResolver.Surface.WIFI_INFO_TEXT);
        assertTrue(ssid.handled());
        assertEquals("\"<unknown ssid>\"", ssid.value());
        UnavailableValueResolver.Resolution bssid =
                UnavailableValueResolver.resolve("wifi_bssid",
                        UnavailableValueResolver.Surface.WIFI_INFO_TEXT);
        assertTrue(bssid.handled());
        assertNull(bssid.value());
    }

    /**
     * "--" on neighbor_cells_json is the drop-real-neighbours/keep-serving decision; the snapshot
     * stores the canonical empty replacement (no configured neighbours), while the list surface
     * keeps registered serving entries.
     */
    @Test
    public void neighborSurfaceResolvesToEmptyReplacement() {
        UnavailableValueResolver.Resolution r = UnavailableValueResolver.resolve(
                "neighbor_cells_json", UnavailableValueResolver.Surface.NEIGHBOR_CELL_LIST);
        assertTrue(r.handled());
        assertEquals("", r.value());
    }

    /** Each Wi-Fi field resolves ONLY on its own surface — no inherited cellular sentinels. */
    @Test
    public void wifiFieldsDoNotInheritCellularSentinels() {
        assertFalse(UnavailableValueResolver.resolve("wifi_rssi",
                UnavailableValueResolver.Surface.CELL_SIGNAL_INT).handled());
        assertFalse(UnavailableValueResolver.resolve("lac",
                UnavailableValueResolver.Surface.WIFI_RSSI).handled());
        assertFalse(UnavailableValueResolver.resolve("wifi_ssid",
                UnavailableValueResolver.Surface.TELEPHONY_TEXT).handled());
        assertFalse(UnavailableValueResolver.resolve("wifi_standard",
                UnavailableValueResolver.Surface.CELL_IDENTITY_INT).handled());
    }

    @Test
    public void invalidFieldSurfacePairsFailClosed() {
        assertFalse(UnavailableValueResolver.resolve("tacc",
                UnavailableValueResolver.Surface.CELL_IDENTITY_INT).handled());
        assertFalse(UnavailableValueResolver.resolve("is_roaming",
                UnavailableValueResolver.Surface.CELL_IDENTITY_INT).handled());
        assertFalse(UnavailableValueResolver.resolve("operator_name",
                UnavailableValueResolver.Surface.CELL_SIGNAL_INT).handled());
        assertFalse(UnavailableValueResolver.resolve("lac",
                UnavailableValueResolver.Surface.TELEPHONY_TEXT).handled());
        assertFalse(name.caiyao.fakegps.config.UnavailableSpec
                .supportsUnavailable("service_state"));
    }
}

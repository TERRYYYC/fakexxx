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
    public void physicalAndNeighborSurfacesUseNativeEmptyForms() {
        assertEquals(0,
                UnavailableValueResolver.resolve("band",
                        UnavailableValueResolver.Surface.PHYSICAL_ZERO).value());
        assertEquals(0,
                UnavailableValueResolver.resolve("channel_bandwidth",
                        UnavailableValueResolver.Surface.PHYSICAL_ZERO).value());
        assertEquals(-1,
                UnavailableValueResolver.resolve("physical_cell_id",
                        UnavailableValueResolver.Surface.PHYSICAL_CELL_ID).value());
        assertFalse(UnavailableValueResolver.resolve("neighbor_cells_json",
                UnavailableValueResolver.Surface.NEIGHBOR_CELL_LIST).handled());
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

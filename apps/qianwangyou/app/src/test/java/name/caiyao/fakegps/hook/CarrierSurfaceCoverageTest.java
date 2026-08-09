package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.Test;

/** Public carrier getter census that must remain wired into the production hook registry. */
public class CarrierSurfaceCoverageTest {

    @Test
    public void hookRegistryCoversIdentityServiceStateAndRegistrationCarrierGetters()
            throws Exception {
        String bytecode = classBytecode(HookUtils.class);

        for (String required : new String[]{
                "android.telephony.CellIdentity",
                "getOperatorAlphaLong",
                "getOperatorAlphaShort",
                "android.telephony.ServiceState",
                "getOperatorNumeric",
                "getRoaming",
                "android.telephony.NetworkRegistrationInfo",
                "getRegisteredPlmn",
                "isRoaming",
                "isNetworkRoaming"}) {
            assertTrue("missing carrier hook surface: " + required, bytecode.contains(required));
        }
    }

    @Test
    public void cellIdentityStringGettersCallCanonicalPlmnProjection() throws Exception {
        assertTrue(classBytecode(HookUtils.class).contains("configuredPlmnString"));
    }

    @Test
    public void gsmCellLocationHooksUseSurfaceSpecificActivation() throws Exception {
        assertTrue(classBytecode(HookUtils.class).contains("hasGsmCellLocationDecision"));
    }

    @Test
    public void cellListHooksUseCanonicalRatConstructionDecision() throws Exception {
        String bytecode = classBytecode(HookUtils.class);
        assertTrue(bytecode.contains("hasCellReconstructionDecision"));
        assertTrue(bytecode.contains("hasCellListMutationDecision"));
        assertTrue(bytecode.contains("hasGsmRatConstruction"));
        assertTrue(bytecode.contains("hasWcdmaRatConstruction"));
        assertTrue(bytecode.contains("registerRealNeighborBypasses"));
    }

    @Test
    public void physicalUnavailableGetterRegistryIsApiGatedAndComplete() {
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "getCellBandwidthDownlinkKhz", "getPhysicalCellId")),
                PhysicalChannelHookRegistry.getterMethodsForApi(29));
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "getCellBandwidthDownlinkKhz", "getPhysicalCellId", "getBand")),
                PhysicalChannelHookRegistry.getterMethodsForApi(31));
        assertEquals(
                new LinkedHashSet<>(Arrays.asList(
                        "getCellBandwidthDownlinkKhz", "getPhysicalCellId", "getBand",
                        "getCellBandwidthUplinkKhz")),
                PhysicalChannelHookRegistry.getterMethodsForApi(33));
    }

    private static String classBytecode(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}

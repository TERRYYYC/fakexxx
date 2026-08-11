package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Regression coverage for FC-9.
 *
 * Android 15 has no six-int CellIdentityLte constructor. The runtime factory must prefer the
 * modern constructor shape while retaining explicit legacy fallbacks for older/OEM frameworks.
 */
public class CellConstructorCompatTest {

    @Test
    public void api24IdentityReadsDoNotRequireApi28Getters() throws Exception {
        Method readPlmn;
        Method readInteger;
        try {
            readPlmn = CellIdentityMetadata.class.getDeclaredMethod(
                    "readPlmn", Object.class, String.class, String.class);
            readInteger = CellIdentityMetadata.class.getDeclaredMethod(
                    "readInteger", Object.class, String.class);
        } catch (NoSuchMethodException missingCompatibilityReader) {
            fail("baseline reads must isolate optional getters and fall back to numeric PLMN");
            return;
        }
        readPlmn.setAccessible(true);
        readInteger.setAccessible(true);

        FakeApi24LteIdentity identity = new FakeApi24LteIdentity(255, 3);
        assertEquals("255", readPlmn.invoke(
                null, identity, "getMccString", "getMcc"));
        assertEquals("3", readPlmn.invoke(
                null, identity, "getMncString", "getMnc"));
        assertNull(readInteger.invoke(null, identity, "getBandwidth"));
    }

    @Test
    public void modernIdentityFactoriesPreserveUnconfiguredMetadata() throws Exception {
        int[] bands = {3, 7};
        Set<String> additionalPlmns =
                new LinkedHashSet<>(Arrays.asList("02503", "02504"));
        Object csgInfo = new Object();

        Class<?> metadataType;
        Object metadata;
        Method lteFactory;
        Method gsmFactory;
        Method wcdmaFactory;
        try {
            metadataType = Class.forName("name.caiyao.fakegps.hook.CellIdentityMetadata");
            Method from = metadataType.getDeclaredMethod("from", Object.class);
            from.setAccessible(true);
            metadata = from.invoke(null, new FakeIdentityMetadataSource(
                    bands, "Real Carrier", "RC", additionalPlmns, csgInfo));
            lteFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newLteIdentity", Class.class, String.class, String.class,
                    int.class, int.class, int.class, Integer.class, Integer.class,
                    metadataType);
            gsmFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newGsmIdentity", Class.class, String.class, String.class,
                    int.class, int.class, Integer.class, Integer.class, metadataType);
            wcdmaFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newWcdmaIdentity", Class.class, String.class, String.class,
                    int.class, int.class, Integer.class, Integer.class, metadataType);
        } catch (ClassNotFoundException | NoSuchMethodException missingMetadataContract) {
            fail("modern identity factories must accept metadata extracted from the real identity");
            return;
        }

        FakeLteIdentityModern lte = (FakeLteIdentityModern) lteFactory.invoke(
                null, FakeLteIdentityModern.class, "025", "03",
                28378431, 53, 26999, 39300, 20000, metadata);
        FakeGsmIdentityModern gsm = (FakeGsmIdentityModern) gsmFactory.invoke(
                null, FakeGsmIdentityModern.class, "025", "03",
                401, 402, 975, 12, metadata);
        FakeWcdmaIdentityModern wcdma = (FakeWcdmaIdentityModern) wcdmaFactory.invoke(
                null, FakeWcdmaIdentityModern.class, "025", "03",
                501, 502, 73, 10613, metadata);

        assertArrayEquals(bands, lte.bands);
        assertEquals("Real Carrier", lte.alphaLong);
        assertEquals("RC", lte.alphaShort);
        assertSame(additionalPlmns, lte.additionalPlmns);
        assertSame(csgInfo, lte.csgInfo);
        assertEquals("Real Carrier", gsm.alphaLong);
        assertEquals("RC", gsm.alphaShort);
        assertSame(additionalPlmns, gsm.additionalPlmns);
        assertEquals("Real Carrier", wcdma.alphaLong);
        assertEquals("RC", wcdma.alphaShort);
        assertSame(additionalPlmns, wcdma.additionalPlmns);
        assertSame(csgInfo, wcdma.csgInfo);
    }

    @Test
    public void configuredOperatorReplacesIdentityAlphaWithoutDiscardingRealMetadata()
            throws Exception {
        int[] bands = {3, 7};
        Set<String> additionalPlmns =
                new LinkedHashSet<>(Arrays.asList("02503", "02504"));
        Object csgInfo = new Object();
        Method from = CellIdentityMetadata.class.getDeclaredMethod("from", Object.class);
        from.setAccessible(true);
        CellIdentityMetadata real = (CellIdentityMetadata) from.invoke(
                null,
                new FakeIdentityMetadataSource(
                        bands, "Real Carrier", "RC", additionalPlmns, csgInfo));

        CellIdentityMetadata configured = real.withOperatorName("Configured Carrier");
        FakeLteIdentityModern identity = (FakeLteIdentityModern)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityModern.class,
                        "025", "03", 28378431, 53, 26999, 39300, 20000,
                        configured);

        assertEquals("Configured Carrier", identity.alphaLong);
        assertEquals("Configured Carrier", identity.alphaShort);
        assertArrayEquals(bands, identity.bands);
        assertSame(additionalPlmns, identity.additionalPlmns);
        assertSame(csgInfo, identity.csgInfo);
    }

    @Test
    public void absentOperatorProjectionPreservesRealAlphaAndEmptyProjectionStaysExplicit() {
        CellIdentityMetadata real = CellIdentityMetadata.from(
                new FakeIdentityMetadataSource(
                        null, "Real Carrier", "RC", null, null));

        assertSame(real, real.withOperatorName(null));
        CellIdentityMetadata unavailable = real.withOperatorName("");
        assertNull(unavailable.alphaLong);
        assertNull(unavailable.alphaShort);
    }

    @Test
    public void stringConstructorFactoriesPreserveLeadingZeroPlmn() throws Exception {
        Method lteFactory;
        Method gsmFactory;
        Method wcdmaFactory;
        try {
            lteFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newLteIdentity", Class.class, String.class, String.class,
                    int.class, int.class, int.class, Integer.class, Integer.class,
                    CellIdentityMetadata.class);
            gsmFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newGsmIdentity", Class.class, String.class, String.class,
                    int.class, int.class, Integer.class, Integer.class,
                    CellIdentityMetadata.class);
            wcdmaFactory = CellConstructorCompat.class.getDeclaredMethod(
                    "newWcdmaIdentity", Class.class, String.class, String.class,
                    int.class, int.class, Integer.class, Integer.class,
                    CellIdentityMetadata.class);
        } catch (NoSuchMethodException missingStringPlmnContract) {
            fail("identity factories must accept the original PLMN strings");
            return;
        }

        FakeLteIdentityModern lteModern = (FakeLteIdentityModern) lteFactory.invoke(
                null, FakeLteIdentityModern.class, "025", "03",
                28378431, 53, 26999, 39300, 20000, CellIdentityMetadata.EMPTY);
        FakeLteIdentityAndroidNine lteNine = (FakeLteIdentityAndroidNine) lteFactory.invoke(
                null, FakeLteIdentityAndroidNine.class, "025", "03",
                28378431, 53, 26999, 39300, 15000, CellIdentityMetadata.EMPTY);
        FakeGsmIdentityModern gsmModern = (FakeGsmIdentityModern) gsmFactory.invoke(
                null, FakeGsmIdentityModern.class, "025", "03",
                401, 402, 975, 12, CellIdentityMetadata.EMPTY);
        FakeGsmIdentityAndroidNine gsmNine = (FakeGsmIdentityAndroidNine) gsmFactory.invoke(
                null, FakeGsmIdentityAndroidNine.class, "025", "03",
                401, 402, 975, 12, CellIdentityMetadata.EMPTY);
        FakeWcdmaIdentityModern wcdmaModern = (FakeWcdmaIdentityModern) wcdmaFactory.invoke(
                null, FakeWcdmaIdentityModern.class, "025", "03",
                501, 502, 73, 10613, CellIdentityMetadata.EMPTY);
        FakeWcdmaIdentityAndroidNine wcdmaNine =
                (FakeWcdmaIdentityAndroidNine) wcdmaFactory.invoke(
                        null, FakeWcdmaIdentityAndroidNine.class, "025", "03",
                        501, 502, 73, 10613, CellIdentityMetadata.EMPTY);

        assertEquals("025", lteModern.mcc);
        assertEquals("03", lteModern.mnc);
        assertEquals("025", lteNine.mcc);
        assertEquals("03", lteNine.mnc);
        assertEquals("025", gsmModern.mcc);
        assertEquals("03", gsmModern.mnc);
        assertEquals("025", gsmNine.mcc);
        assertEquals("03", gsmNine.mnc);
        assertEquals("025", wcdmaModern.mcc);
        assertEquals("03", wcdmaModern.mnc);
        assertEquals("025", wcdmaNine.mcc);
        assertEquals("03", wcdmaNine.mnc);
    }

    @Test
    public void lteIdentity_prefersModernTwelveArgumentShape() throws Exception {
        FakeLteIdentityModern value = (FakeLteIdentityModern)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityModern.class,
                        "255", "3", 28378431, 53, 26999, 39300, 20000,
                        CellIdentityMetadata.EMPTY);

        assertEquals(28378431, value.ci);
        assertEquals(53, value.pci);
        assertEquals(26999, value.tac);
        assertEquals(39300, value.earfcn);
        assertEquals(20000, value.bandwidth);
        assertEquals("255", value.mcc);
        assertEquals("3", value.mnc);
        assertTrue(value.additionalPlmns.isEmpty());
    }

    @Test
    public void lteIdentity_fallsBackToLegacyFiveArgumentShape() throws Exception {
        FakeLteIdentityLegacy value = (FakeLteIdentityLegacy)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityLegacy.class,
                        "255", "3", 28378431, 53, 26999, 39300, 20000,
                        CellIdentityMetadata.EMPTY);

        assertEquals(255, value.mcc);
        assertEquals(3, value.mnc);
        assertEquals(28378431, value.ci);
        assertEquals(53, value.pci);
        assertEquals(26999, value.tac);
    }

    @Test
    public void unavailablePlmnWorksOnModernAndLegacyConstructorShapes() throws Exception {
        FakeLteIdentityModern modern = (FakeLteIdentityModern)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityModern.class,
                        null, null, 28378431, 53, 26999, 39300, 20000,
                        CellIdentityMetadata.EMPTY);
        assertNull(modern.mcc);
        assertNull(modern.mnc);

        FakeLteIdentityLegacy legacy = (FakeLteIdentityLegacy)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityLegacy.class,
                        null, null, 28378431, 53, 26999, 39300, 20000,
                        CellIdentityMetadata.EMPTY);
        assertEquals(Integer.MAX_VALUE, legacy.mcc);
        assertEquals(Integer.MAX_VALUE, legacy.mnc);
    }

    @Test
    public void lteIdentity_supportsAndroidNineStringPlmnShape() throws Exception {
        FakeLteIdentityAndroidNine value = (FakeLteIdentityAndroidNine)
                CellConstructorCompat.newLteIdentity(
                        FakeLteIdentityAndroidNine.class,
                        "255", "3", 28378431, 53, 26999, 39300, 15000,
                        CellIdentityMetadata.EMPTY);

        assertEquals(28378431, value.ci);
        assertEquals(39300, value.earfcn);
        assertEquals(15000, value.bandwidth);
        assertEquals("255", value.mcc);
        assertEquals("3", value.mnc);
    }

    @Test
    public void gsmAndWcdmaIdentity_useModernStringPlmnShapes() throws Exception {
        FakeGsmIdentityModern gsm = (FakeGsmIdentityModern)
                CellConstructorCompat.newGsmIdentity(
                        FakeGsmIdentityModern.class, "255", "3", 401, 402, 975, 12,
                        CellIdentityMetadata.EMPTY);
        FakeWcdmaIdentityModern wcdma = (FakeWcdmaIdentityModern)
                CellConstructorCompat.newWcdmaIdentity(
                        FakeWcdmaIdentityModern.class, "255", "3", 501, 502, 73, 10613,
                        CellIdentityMetadata.EMPTY);

        assertEquals("255", gsm.mcc);
        assertEquals("3", gsm.mnc);
        assertEquals(401, gsm.lac);
        assertEquals(975, gsm.arfcn);
        assertEquals("255", wcdma.mcc);
        assertEquals("3", wcdma.mnc);
        assertEquals(10613, wcdma.uarfcn);
    }

    @Test
    public void gsmAndWcdmaIdentity_supportAndroidNineEightArgumentShapes() throws Exception {
        FakeGsmIdentityAndroidNine gsm = (FakeGsmIdentityAndroidNine)
                CellConstructorCompat.newGsmIdentity(
                        FakeGsmIdentityAndroidNine.class, "255", "3", 401, 402, 975, 12,
                        CellIdentityMetadata.EMPTY);
        FakeWcdmaIdentityAndroidNine wcdma = (FakeWcdmaIdentityAndroidNine)
                CellConstructorCompat.newWcdmaIdentity(
                        FakeWcdmaIdentityAndroidNine.class, "255", "3",
                        501, 502, 73, 10613, CellIdentityMetadata.EMPTY);

        assertEquals("255", gsm.mcc);
        assertEquals("3", gsm.mnc);
        assertEquals(975, gsm.arfcn);
        assertEquals("255", wcdma.mcc);
        assertEquals("3", wcdma.mnc);
        assertEquals(10613, wcdma.uarfcn);
    }

    @Test
    public void lteSignal_prefersSevenArgumentShapeAndPreservesCqiPosition() throws Exception {
        FakeLteSignalModern signal = (FakeLteSignalModern)
                CellConstructorCompat.newLteSignal(
                        FakeLteSignalModern.class, -75, -96, -11, 18, 13, 4);

        assertEquals(-75, signal.rssi);
        assertEquals(-96, signal.rsrp);
        assertEquals(-11, signal.rsrq);
        assertEquals(18, signal.rssnr);
        assertEquals(Integer.MAX_VALUE, signal.cqiTableIndex);
        assertEquals(13, signal.cqi);
        assertEquals(4, signal.timingAdvance);
    }

    @Test
    public void physicalChannelConfigUsesModernBuilderWhenZeroArgConstructorIsAbsent()
            throws Exception {
        FakePhysicalChannelModern value = (FakePhysicalChannelModern)
                CellConstructorCompat.newPhysicalChannelConfig(FakePhysicalChannelModern.class);

        assertTrue(value.built);
    }

    @Test
    public void physicalChannelConfigRetainsLegacyZeroArgumentShape() throws Exception {
        FakePhysicalChannelLegacy value = (FakePhysicalChannelLegacy)
                CellConstructorCompat.newPhysicalChannelConfig(FakePhysicalChannelLegacy.class);

        assertTrue(value.constructed);
    }

    static final class FakeLteIdentityModern {
        final int ci, pci, tac, earfcn, bandwidth;
        final int[] bands;
        final String mcc, mnc;
        final String alphaLong, alphaShort;
        final Collection<String> additionalPlmns;
        final Object csgInfo;

        FakeLteIdentityModern(int ci, int pci, int tac, int earfcn, int[] bands,
                              int bandwidth, String mcc, String mnc,
                              String alphaLong, String alphaShort,
                              Collection<String> additionalPlmns, Object csgInfo) {
            this.ci = ci;
            this.pci = pci;
            this.tac = tac;
            this.earfcn = earfcn;
            this.bands = bands;
            this.bandwidth = bandwidth;
            this.mcc = mcc;
            this.mnc = mnc;
            this.alphaLong = alphaLong;
            this.alphaShort = alphaShort;
            this.additionalPlmns = additionalPlmns;
            this.csgInfo = csgInfo;
        }
    }

    static final class FakeLteIdentityLegacy {
        final int mcc, mnc, ci, pci, tac;

        FakeLteIdentityLegacy(int mcc, int mnc, int ci, int pci, int tac) {
            this.mcc = mcc;
            this.mnc = mnc;
            this.ci = ci;
            this.pci = pci;
            this.tac = tac;
        }
    }

    static final class FakeLteIdentityAndroidNine {
        final int ci, pci, tac, earfcn, bandwidth;
        final String mcc, mnc;

        FakeLteIdentityAndroidNine(int ci, int pci, int tac, int earfcn, int bandwidth,
                                   String mcc, String mnc, String alphaLong, String alphaShort) {
            this.ci = ci;
            this.pci = pci;
            this.tac = tac;
            this.earfcn = earfcn;
            this.bandwidth = bandwidth;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeGsmIdentityModern {
        final int lac, cid, arfcn, bsic;
        final String mcc, mnc;
        final String alphaLong, alphaShort;
        final Collection<String> additionalPlmns;

        FakeGsmIdentityModern(int lac, int cid, int arfcn, int bsic,
                              String mcc, String mnc, String alphaLong, String alphaShort,
                              Collection<String> additionalPlmns) {
            this.lac = lac;
            this.cid = cid;
            this.arfcn = arfcn;
            this.bsic = bsic;
            this.mcc = mcc;
            this.mnc = mnc;
            this.alphaLong = alphaLong;
            this.alphaShort = alphaShort;
            this.additionalPlmns = additionalPlmns;
        }
    }

    static final class FakeWcdmaIdentityModern {
        final int lac, cid, psc, uarfcn;
        final String mcc, mnc;
        final String alphaLong, alphaShort;
        final Collection<String> additionalPlmns;
        final Object csgInfo;

        FakeWcdmaIdentityModern(int lac, int cid, int psc, int uarfcn,
                                String mcc, String mnc, String alphaLong, String alphaShort,
                                Collection<String> additionalPlmns, Object csgInfo) {
            this.lac = lac;
            this.cid = cid;
            this.psc = psc;
            this.uarfcn = uarfcn;
            this.mcc = mcc;
            this.mnc = mnc;
            this.alphaLong = alphaLong;
            this.alphaShort = alphaShort;
            this.additionalPlmns = additionalPlmns;
            this.csgInfo = csgInfo;
        }
    }

    static final class FakeGsmIdentityAndroidNine {
        final int lac, cid, arfcn, bsic;
        final String mcc, mnc;

        FakeGsmIdentityAndroidNine(int lac, int cid, int arfcn, int bsic,
                                   String mcc, String mnc, String alphaLong, String alphaShort) {
            this.lac = lac;
            this.cid = cid;
            this.arfcn = arfcn;
            this.bsic = bsic;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeWcdmaIdentityAndroidNine {
        final int lac, cid, psc, uarfcn;
        final String mcc, mnc;

        FakeWcdmaIdentityAndroidNine(int lac, int cid, int psc, int uarfcn,
                                     String mcc, String mnc,
                                     String alphaLong, String alphaShort) {
            this.lac = lac;
            this.cid = cid;
            this.psc = psc;
            this.uarfcn = uarfcn;
            this.mcc = mcc;
            this.mnc = mnc;
        }
    }

    static final class FakeLteSignalModern {
        final int rssi, rsrp, rsrq, rssnr, cqiTableIndex, cqi, timingAdvance;

        FakeLteSignalModern(int rssi, int rsrp, int rsrq, int rssnr,
                            int cqiTableIndex, int cqi, int timingAdvance) {
            this.rssi = rssi;
            this.rsrp = rsrp;
            this.rsrq = rsrq;
            this.rssnr = rssnr;
            this.cqiTableIndex = cqiTableIndex;
            this.cqi = cqi;
            this.timingAdvance = timingAdvance;
        }
    }

    static final class FakePhysicalChannelModern {
        final boolean built;

        private FakePhysicalChannelModern(Builder builder) {
            built = builder != null;
        }

        public static final class Builder {
            public Builder() {}

            public FakePhysicalChannelModern build() {
                return new FakePhysicalChannelModern(this);
            }
        }
    }

    static final class FakePhysicalChannelLegacy {
        final boolean constructed;

        FakePhysicalChannelLegacy() {
            constructed = true;
        }
    }

    public static final class FakeIdentityMetadataSource {
        private final int[] bands;
        private final String alphaLong;
        private final String alphaShort;
        private final Set<String> additionalPlmns;
        private final Object csgInfo;

        FakeIdentityMetadataSource(int[] bands, String alphaLong, String alphaShort,
                                   Set<String> additionalPlmns, Object csgInfo) {
            this.bands = bands;
            this.alphaLong = alphaLong;
            this.alphaShort = alphaShort;
            this.additionalPlmns = additionalPlmns;
            this.csgInfo = csgInfo;
        }

        public int[] getBands() {
            return bands;
        }

        public CharSequence getOperatorAlphaLong() {
            return alphaLong;
        }

        public CharSequence getOperatorAlphaShort() {
            return alphaShort;
        }

        public Set<String> getAdditionalPlmns() {
            return additionalPlmns;
        }

        public Object getClosedSubscriberGroupInfo() {
            return csgInfo;
        }
    }

    public static final class FakeApi24LteIdentity {
        private final int mcc;
        private final int mnc;

        FakeApi24LteIdentity(int mcc, int mnc) {
            this.mcc = mcc;
            this.mnc = mnc;
        }

        public int getMcc() {
            return mcc;
        }

        public int getMnc() {
            return mnc;
        }
    }
}

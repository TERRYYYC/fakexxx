package name.caiyao.fakegps.hook.oracle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AppOpsManager;
import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;

/**
 * #159 contract: the endpoint owner must come from the server's EXPLICIT OP_MOCK_LOCATION
 * records ({@code getPackagesForOps} — the same source {@code cmd appops query-op
 * MOCK_LOCATION allow} reads), never again from the per-package evaluated sweep over
 * {@code getInstalledApplications(0)}. On mi14 (Android 16 / HyperOS) the evaluated sweep
 * returned owner=null on hundreds of consecutive samples while the explicit records held the
 * one correct package, so the oracle window stayed INVALID (owner_mismatch).
 *
 * <p>The shadow-backed AppOpsManager lets the fixture plant an explicit allowed record for a
 * package deliberately absent from the (empty) installed enumeration — the property the old
 * implementation could not observe because it only ever asked about installed packages.
 */
@RunWith(RobolectricTestRunner.class)
public class AndroidOracleEndpointReaderSampleTest {

    @Test
    public void explicitAllowedRecordBecomesOwnerEvenOutsideInstalledEnumeration() {
        Shadows.shadowOf(appOps()).setMode(
                AppOpsManager.OPSTR_MOCK_LOCATION, 10327, "com.health.app",
                AppOpsManager.MODE_ALLOWED);

        SystemServerOracleState.EndpointSample sample = AndroidOracleEndpointReader.sample(context());

        assertEquals(10327, sample.ownerUid.intValue());
        assertEquals("com.health.app", sample.ownerPackage);
        assertTrue(sample.valid);
        assertNull(sample.failure);
    }

    @Test
    public void twoDistinctExplicitIdentitiesResolveAmbiguousAndInvalid() {
        Shadows.shadowOf(appOps()).setMode(
                AppOpsManager.OPSTR_MOCK_LOCATION, 10327, "com.health.app",
                AppOpsManager.MODE_ALLOWED);
        Shadows.shadowOf(appOps()).setMode(
                AppOpsManager.OPSTR_MOCK_LOCATION, 10328, "com.altitude.app",
                AppOpsManager.MODE_ALLOWED);

        SystemServerOracleState.EndpointSample sample = AndroidOracleEndpointReader.sample(context());

        assertNull(sample.ownerUid);
        assertNull(sample.ownerPackage);
        assertFalse(sample.valid);
        assertNull(sample.failure);
    }

    @Test
    public void deniedExplicitRecordIsFilteredSoOwnerStaysNullButUnambiguous() {
        Shadows.shadowOf(appOps()).setMode(
                AppOpsManager.OPSTR_MOCK_LOCATION, 10327, "com.health.app",
                AppOpsManager.MODE_IGNORED);

        SystemServerOracleState.EndpointSample sample = AndroidOracleEndpointReader.sample(context());

        assertNull(sample.ownerUid);
        assertNull(sample.ownerPackage);
        assertTrue(sample.valid);
        assertNull(sample.failure);
    }

    @Test
    public void allowedRecordOnUnrelatedOpNeverBecomesOwner() {
        Shadows.shadowOf(appOps()).setMode(
                AppOpsManager.OPSTR_FINE_LOCATION, 10327, "com.health.app",
                AppOpsManager.MODE_ALLOWED);

        SystemServerOracleState.EndpointSample sample = AndroidOracleEndpointReader.sample(context());

        assertNull(sample.ownerUid);
        assertNull(sample.ownerPackage);
        assertTrue(sample.valid);
        assertNull(sample.failure);
    }

    @Test
    public void landscapeWithoutAnyExplicitRecordReportsNullOwnerAsValid() {
        SystemServerOracleState.EndpointSample sample = AndroidOracleEndpointReader.sample(context());

        assertNull(sample.ownerUid);
        assertNull(sample.ownerPackage);
        assertTrue(sample.valid);
        assertNull(sample.failure);
    }

    /**
     * The reader resolves the hidden op code from the {@code OP_MOCK_LOCATION} field first and
     * falls back to {@code strOpToOp(OPSTR_MOCK_LOCATION)}; both must agree on the runtime
     * framework, otherwise the fallback would query a different op than the primary path.
     */
    @Test
    public void opCodeResolvedFromPublicOpStringMatchesHiddenConstant() throws Exception {
        int fromField = AppOpsManager.class.getField("OP_MOCK_LOCATION").getInt(null);
        int fromString = (Integer) AppOpsManager.class.getMethod("strOpToOp", String.class)
                .invoke(null, AppOpsManager.OPSTR_MOCK_LOCATION);

        assertEquals(fromField, fromString);
    }

    private static AppOpsManager appOps() {
        return context().getSystemService(AppOpsManager.class);
    }

    private static Application context() {
        return ApplicationProvider.<Application>getApplicationContext();
    }
}

package name.caiyao.fakegps.hook.oracle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AppOpsManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Owner verdict over the server's explicit OP_MOCK_LOCATION records (#159). The decision is a
 * pure function of (uid, package, mode) triples so it runs on the plain JVM, mirroring the
 * {@link HookMethodNameResolverTest} lane: the reflective glue that produces the triples is
 * pinned separately by {@link AndroidOracleEndpointReaderSampleTest}.
 *
 * <p>Semantics carried over unchanged from the evaluated-sweep era: exactly one allowed identity
 * is the owner; none is a valid null owner; two distinct allowed identities are ambiguous and
 * therefore invalid — the oracle must never guess between mock-location holders.
 */
public class AndroidOracleEndpointReaderOwnerTest {
    private static final int HEALTH_UID = 10327;
    private static final String HEALTH = "com.health.app";
    private static final int ALTITUDE_UID = 10328;
    private static final String ALTITUDE = "com.altitude.app";

    @Test
    public void singleAllowedRecordResolvesAsOwner() {
        AndroidOracleEndpointReader.OwnerDecision owner = AndroidOracleEndpointReader.resolveOwner(
                Collections.singletonList(record(HEALTH_UID, HEALTH, AppOpsManager.MODE_ALLOWED)));

        assertEquals(HEALTH_UID, owner.uid.intValue());
        assertEquals(HEALTH, owner.packageName);
        assertFalse(owner.ambiguous);
    }

    @Test
    public void noExplicitRecordsResolveToNullOwnerWithoutAmbiguity() {
        AndroidOracleEndpointReader.OwnerDecision owner =
                AndroidOracleEndpointReader.resolveOwner(Collections.emptyList());

        assertNull(owner.uid);
        assertNull(owner.packageName);
        assertFalse(owner.ambiguous);
    }

    @Test
    public void twoDistinctAllowedIdentitiesResolveAmbiguous() {
        AndroidOracleEndpointReader.OwnerDecision owner = AndroidOracleEndpointReader.resolveOwner(
                Arrays.asList(
                        record(HEALTH_UID, HEALTH, AppOpsManager.MODE_ALLOWED),
                        record(ALTITUDE_UID, ALTITUDE, AppOpsManager.MODE_ALLOWED)));

        assertTrue(owner.ambiguous);
        assertNull(owner.uid);
        assertNull(owner.packageName);
    }

    @Test
    public void sameUidUnderDifferentPackagesIsStillAmbiguous() {
        // Shared-uid siblings are distinct identities to the oracle; a uid match alone must not
        // collapse them into one owner.
        AndroidOracleEndpointReader.OwnerDecision owner = AndroidOracleEndpointReader.resolveOwner(
                Arrays.asList(
                        record(HEALTH_UID, HEALTH, AppOpsManager.MODE_ALLOWED),
                        record(HEALTH_UID, ALTITUDE, AppOpsManager.MODE_ALLOWED)));

        assertTrue(owner.ambiguous);
    }

    @Test
    public void samePackageUnderDifferentUidsIsStillAmbiguous() {
        AndroidOracleEndpointReader.OwnerDecision owner = AndroidOracleEndpointReader.resolveOwner(
                Arrays.asList(
                        record(HEALTH_UID, HEALTH, AppOpsManager.MODE_ALLOWED),
                        record(ALTITUDE_UID, HEALTH, AppOpsManager.MODE_ALLOWED)));

        assertTrue(owner.ambiguous);
    }

    @Test
    public void repeatedRecordOfTheSameIdentityStaysUniqueOwner() {
        // Multi-user or duplicated server entries for one (uid, package) are one identity, not
        // an ambiguity — the old sweep saw each installed package once, so it never had to say so.
        AndroidOracleEndpointReader.OwnerDecision owner = AndroidOracleEndpointReader.resolveOwner(
                Arrays.asList(
                        record(HEALTH_UID, HEALTH, AppOpsManager.MODE_ALLOWED),
                        record(HEALTH_UID, HEALTH, AppOpsManager.MODE_ALLOWED)));

        assertEquals(HEALTH_UID, owner.uid.intValue());
        assertEquals(HEALTH, owner.packageName);
        assertFalse(owner.ambiguous);
    }

    @Test
    public void nonAllowedModesNeverResolveAsOwner() {
        List<AndroidOracleEndpointReader.ExplicitOpRecord> denied = Arrays.asList(
                record(HEALTH_UID, HEALTH, AppOpsManager.MODE_IGNORED),
                record(ALTITUDE_UID, ALTITUDE, AppOpsManager.MODE_ERRORED),
                record(10329, "com.default.app", AppOpsManager.MODE_DEFAULT),
                record(10330, "com.foreground.app", AppOpsManager.MODE_FOREGROUND));

        AndroidOracleEndpointReader.OwnerDecision owner = AndroidOracleEndpointReader.resolveOwner(denied);

        assertNull(owner.uid);
        assertNull(owner.packageName);
        assertFalse(owner.ambiguous);
    }

    @Test
    public void deniedRecordsBesideOneAllowedRecordDoNotDisturbTheOwner() {
        AndroidOracleEndpointReader.OwnerDecision owner = AndroidOracleEndpointReader.resolveOwner(
                Arrays.asList(
                        record(ALTITUDE_UID, ALTITUDE, AppOpsManager.MODE_IGNORED),
                        record(HEALTH_UID, HEALTH, AppOpsManager.MODE_ALLOWED),
                        record(10329, "com.default.app", AppOpsManager.MODE_ERRORED)));

        assertEquals(HEALTH_UID, owner.uid.intValue());
        assertEquals(HEALTH, owner.packageName);
        assertFalse(owner.ambiguous);
    }

    private static AndroidOracleEndpointReader.ExplicitOpRecord record(
            int uid, String packageName, int mode) {
        return new AndroidOracleEndpointReader.ExplicitOpRecord(uid, packageName, mode);
    }
}

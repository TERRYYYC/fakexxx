package name.caiyao.fakegps.hook.oracle;

import android.app.AppOpsManager;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Platform I/O stays outside the producer's state lock. */
final class AndroidOracleEndpointReader {
    private AndroidOracleEndpointReader() {}
    /** Samples framework services without holding the oracle state lock. */
    static SystemServerOracleState.EndpointSample sample(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new SystemServerOracleState.EndpointSample(
                    null,
                    null,
                    false,
                    false,
                    false,
                    new IllegalStateException(
                            "effective AppOps sampling requires API 29 or newer"));
        }
        try {
            AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            LocationManager locations = context.getSystemService(LocationManager.class);
            if (appOps == null || locations == null) {
                throw new IllegalStateException("required system manager unavailable");
            }

            OwnerDecision owner = resolveOwner(readExplicitMockLocationRecords(appOps));
            boolean gps = locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean network = locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            return new SystemServerOracleState.EndpointSample(
                    owner.ambiguous ? null : owner.uid,
                    owner.ambiguous ? null : owner.packageName,
                    gps,
                    network,
                    !owner.ambiguous,
                    null);
        } catch (RuntimeException failure) {
            return new SystemServerOracleState.EndpointSample(null, null, false, false, false, failure);
        }
    }

    /**
     * One explicit server-side OP_MOCK_LOCATION record, normalized out of the hidden
     * {@code PackageOps}/{@code OpEntry} shapes so the owner verdict stays a plain-JVM function.
     */
    static final class ExplicitOpRecord {
        final int uid;
        final String packageName;
        final int mode;

        ExplicitOpRecord(int uid, String packageName, int mode) {
            this.uid = uid;
            this.packageName = packageName;
            this.mode = mode;
        }
    }

    /** Owner verdict: exactly one allowed identity, none at all, or an ambiguous landscape. */
    static final class OwnerDecision {
        final Integer uid;
        final String packageName;
        final boolean ambiguous;

        private OwnerDecision(Integer uid, String packageName, boolean ambiguous) {
            this.uid = uid;
            this.packageName = packageName;
            this.ambiguous = ambiguous;
        }

        static OwnerDecision unique(Integer uid, String packageName) {
            return new OwnerDecision(uid, packageName, false);
        }

        static OwnerDecision ambiguous() {
            return new OwnerDecision(null, null, true);
        }
    }

    /**
     * #159 (mi14, Android 16 / HyperOS): the former per-package
     * {@code unsafeCheckOpNoThrow(OPSTR_MOCK_LOCATION, uid, pkg)} sweep over
     * {@code getInstalledApplications(0)} answered owner=null on hundreds of consecutive
     * samples while the server's explicit records ({@code cmd appops query-op MOCK_LOCATION
     * allow}) held exactly one correct package — every window stayed INVALID (owner_mismatch),
     * coverage collapsed to NONE, and the 285 regressions were all UNVERIFIED_RECORDED.
     * The evaluated check path is where that platform goes ambiguous (it also costs one binder
     * call per installed package); the explicit-record path is what the shell probe trusts.
     * Owner semantics are unchanged: one allowed identity owns, none is a valid null owner,
     * two distinct allowed identities are ambiguous and therefore invalid — the oracle never
     * guesses between mock-location holders.
     */
    static OwnerDecision resolveOwner(List<ExplicitOpRecord> records) {
        Integer uniqueUid = null;
        String uniquePackage = null;
        for (ExplicitOpRecord record : records) {
            if (record.mode != AppOpsManager.MODE_ALLOWED) {
                continue;
            }
            if (uniquePackage != null
                    && (uniqueUid == null || uniqueUid != record.uid
                    || !uniquePackage.equals(record.packageName))) {
                return OwnerDecision.ambiguous();
            }
            uniqueUid = record.uid;
            uniquePackage = record.packageName;
        }
        return OwnerDecision.unique(uniqueUid, uniquePackage);
    }

    /**
     * {@code getPackagesForOps(int[])}, its {@code PackageOps}/{@code OpEntry} shapes and the
     * {@code OP_MOCK_LOCATION} code are all {@code @hide} — absent from the compile SDK
     * (checked against the API 35 stubs) but present in every runtime framework, including
     * Robolectric's android-all. The oracle runs inside system_server, so the query is
     * permitted; reflection is the only compile-clean way to reach it. A single call returns
     * only packages with an explicit entry for the op, so default/evaluated noise is excluded
     * at the source rather than filtered afterwards.
     */
    private static List<ExplicitOpRecord> readExplicitMockLocationRecords(AppOpsManager appOps) {
        try {
            int opCode = mockLocationOpCode();
            List<?> packages = (List<?>) AppOpsManager.class
                    .getMethod("getPackagesForOps", int[].class)
                    .invoke(appOps, (Object) new int[] {opCode});
            if (packages == null || packages.isEmpty()) {
                return Collections.emptyList();
            }
            Class<?> packageOpsClass = Class.forName("android.app.AppOpsManager$PackageOps");
            Class<?> opEntryClass = Class.forName("android.app.AppOpsManager$OpEntry");
            Method packageUid = packageOpsClass.getMethod("getUid");
            Method packageName = packageOpsClass.getMethod("getPackageName");
            Method packageOps = packageOpsClass.getMethod("getOps");
            Method entryOp = opEntryClass.getMethod("getOp");
            Method entryMode = opEntryClass.getMethod("getMode");

            List<ExplicitOpRecord> records = new ArrayList<>();
            for (Object packageRecord : packages) {
                int uid = (Integer) packageUid.invoke(packageRecord);
                String name = (String) packageName.invoke(packageRecord);
                List<?> entries = (List<?>) packageOps.invoke(packageRecord);
                if (entries == null) {
                    continue;
                }
                for (Object entry : entries) {
                    // The query already filtered by op; re-checking keeps a framework that
                    // widens the answer from silently promoting an unrelated op to owner.
                    if ((Integer) entryOp.invoke(entry) != opCode) {
                        continue;
                    }
                    records.add(new ExplicitOpRecord(uid, name, (Integer) entryMode.invoke(entry)));
                }
            }
            return records;
        } catch (ReflectiveOperationException hidden) {
            throw new IllegalStateException(
                    "explicit OP_MOCK_LOCATION record query unavailable on this framework", hidden);
        }
    }

    /**
     * The int code behind the public {@link AppOpsManager#OPSTR_MOCK_LOCATION}. The constant
     * field is read first because it is the value the framework itself serializes; the
     * {@code strOpToOp} resolver is the fallback for a framework that hides the field but
     * still maps the public op string.
     */
    private static int mockLocationOpCode() throws ReflectiveOperationException {
        try {
            return AppOpsManager.class.getField("OP_MOCK_LOCATION").getInt(null);
        } catch (NoSuchFieldException hiddenConstant) {
            Method strOpToOp = AppOpsManager.class.getMethod("strOpToOp", String.class);
            return (Integer) strOpToOp.invoke(null, AppOpsManager.OPSTR_MOCK_LOCATION);
        }
    }
}

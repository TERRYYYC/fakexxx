package name.caiyao.fakegps.hook.oracle;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

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
            PackageManager packages = context.getPackageManager();
            if (appOps == null || locations == null) {
                throw new IllegalStateException("required system manager unavailable");
            }

            Integer uniqueUid = null;
            String uniquePackage = null;
            boolean ambiguous = false;
            for (ApplicationInfo app : packages.getInstalledApplications(0)) {
                int mode = appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_MOCK_LOCATION, app.uid, app.packageName);
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    if (uniquePackage != null
                            && (uniqueUid == null || uniqueUid != app.uid
                            || !uniquePackage.equals(app.packageName))) {
                        ambiguous = true;
                        break;
                    }
                    uniqueUid = app.uid;
                    uniquePackage = app.packageName;
                }
            }
            boolean gps = locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean network = locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            return new SystemServerOracleState.EndpointSample(
                    ambiguous ? null : uniqueUid,
                    ambiguous ? null : uniquePackage,
                    gps,
                    network,
                    !ambiguous,
                    null);
        } catch (RuntimeException failure) {
            return new SystemServerOracleState.EndpointSample(null, null, false, false, false, failure);
        }
    }


}

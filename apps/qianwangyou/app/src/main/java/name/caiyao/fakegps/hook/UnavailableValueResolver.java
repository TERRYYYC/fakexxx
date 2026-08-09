package name.caiyao.fakegps.hook;

import android.os.Build;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Layer 2 of the profile {@code --} contract.
 *
 * <p>The profile stores only the decision "this field is unavailable". Android does not have one
 * universal unavailable value: the same {@code lac} is {@code Integer.MAX_VALUE} on
 * {@code CellIdentityGsm} and {@code -1} on {@code GsmCellLocation}. The hook call site supplies
 * the surface, and this resolver returns either a handled value (which may itself be {@code null})
 * or a fail-closed unhandled result.
 */
public final class UnavailableValueResolver {

    public enum Surface {
        CELL_IDENTITY_INT,
        CELL_IDENTITY_LONG,
        CELL_IDENTITY_PLMN_STRING,
        GSM_CELL_LOCATION,
        CELL_SIGNAL_INT,
        TELEPHONY_TEXT,
        CARRIER_OBJECT_TEXT,
        NETWORK_TYPE,
        PHONE_TYPE,
        DATA_STATE,
        DATA_ACTIVITY,
        DISPLAY_OVERRIDE,
        PHYSICAL_ZERO,
        PHYSICAL_CELL_ID,
        NEIGHBOR_CELL_LIST
    }

    public static final class Resolution {
        private static final Resolution UNHANDLED = new Resolution(false, null);

        private final boolean handled;
        private final Object value;

        private Resolution(boolean handled, Object value) {
            this.handled = handled;
            this.value = value;
        }

        static Resolution handled(Object value) {
            return new Resolution(true, value);
        }

        static Resolution unhandled() {
            return UNHANDLED;
        }

        public boolean handled() {
            return handled;
        }

        public Object value() {
            return value;
        }
    }

    private static final Set<String> CELL_IDENTITY_INT = immutableSet(
            "mcc", "mnc", "lac", "cid", "arfcn", "bsic", "psc", "uarfcn",
            "tac", "ci", "pci", "earfcn", "lte_bandwidth",
            "nrarfcn", "nr_pci", "nr_tac");

    private static final Set<String> CELL_SIGNAL_INT = immutableSet(
            "gsm_rssi", "gsm_ber", "gsm_ta",
            "wcdma_rssi", "wcdma_rscp", "wcdma_ecno",
            "lte_rssi", "lte_rsrp", "lte_rsrq", "lte_sinr", "lte_cqi", "lte_ta",
            "nr_ss_rsrp", "nr_ss_rsrq", "nr_ss_sinr",
            "nr_csi_rsrp", "nr_csi_rsrq", "nr_csi_sinr");

    private static final Set<String> TELEPHONY_TEXT = immutableSet(
            "operator_name", "operator_numeric", "sim_operator", "sim_operator_name",
            "sim_country_iso", "network_country_iso");

    private static final Set<String> NETWORK_TYPE = immutableSet(
            "network_type", "data_network_type", "voice_network_type");

    private static final Set<String> PHYSICAL_ZERO = immutableSet(
            "band", "channel_bandwidth", "cell_bandwidth_downlink");

    private static final Map<String, Surface> SNAPSHOT_SURFACES;
    static {
        Map<String, Surface> surfaces = new HashMap<>();
        CELL_IDENTITY_INT.forEach(field -> surfaces.put(field, Surface.CELL_IDENTITY_INT));
        CELL_SIGNAL_INT.forEach(field -> surfaces.put(field, Surface.CELL_SIGNAL_INT));
        TELEPHONY_TEXT.forEach(field -> surfaces.put(field, Surface.TELEPHONY_TEXT));
        NETWORK_TYPE.forEach(field -> surfaces.put(field, Surface.NETWORK_TYPE));
        PHYSICAL_ZERO.forEach(field -> surfaces.put(field, Surface.PHYSICAL_ZERO));
        surfaces.put("nci", Surface.CELL_IDENTITY_LONG);
        surfaces.put("phone_type", Surface.PHONE_TYPE);
        surfaces.put("data_state", Surface.DATA_STATE);
        surfaces.put("data_activity", Surface.DATA_ACTIVITY);
        surfaces.put("override_network_type", Surface.DISPLAY_OVERRIDE);
        surfaces.put("physical_cell_id", Surface.PHYSICAL_CELL_ID);
        SNAPSHOT_SURFACES = Collections.unmodifiableMap(surfaces);
    }

    private UnavailableValueResolver() {}

    private static Set<String> immutableSet(String... values) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    public static Resolution resolve(String field, Surface surface) {
        if (field == null || surface == null) return Resolution.unhandled();

        switch (surface) {
            case CELL_IDENTITY_INT:
                return CELL_IDENTITY_INT.contains(field)
                        ? Resolution.handled(Integer.MAX_VALUE) : Resolution.unhandled();
            case CELL_IDENTITY_LONG:
                return "nci".equals(field)
                        ? Resolution.handled(Long.MAX_VALUE) : Resolution.unhandled();
            case CELL_IDENTITY_PLMN_STRING:
                return ("mcc".equals(field) || "mnc".equals(field))
                        ? Resolution.handled(null) : Resolution.unhandled();
            case GSM_CELL_LOCATION:
                return ("lac".equals(field) || "cid".equals(field) || "psc".equals(field))
                        ? Resolution.handled(-1) : Resolution.unhandled();
            case CELL_SIGNAL_INT:
                return CELL_SIGNAL_INT.contains(field)
                        ? Resolution.handled(Integer.MAX_VALUE) : Resolution.unhandled();
            case TELEPHONY_TEXT:
                return TELEPHONY_TEXT.contains(field)
                        ? Resolution.handled("") : Resolution.unhandled();
            case CARRIER_OBJECT_TEXT:
                return ("operator_name".equals(field) || "operator_numeric".equals(field))
                        ? Resolution.handled(null) : Resolution.unhandled();
            case NETWORK_TYPE:
                return NETWORK_TYPE.contains(field)
                        ? Resolution.handled(0) : Resolution.unhandled();
            case PHONE_TYPE:
                return "phone_type".equals(field)
                        ? Resolution.handled(0) : Resolution.unhandled();
            case DATA_STATE:
                return "data_state".equals(field)
                        ? Resolution.handled(dataStateUnavailableValueForApi(Build.VERSION.SDK_INT))
                        : Resolution.unhandled();
            case DATA_ACTIVITY:
                return "data_activity".equals(field)
                        ? Resolution.handled(0) : Resolution.unhandled();
            case DISPLAY_OVERRIDE:
                return "override_network_type".equals(field)
                        ? Resolution.handled(0) : Resolution.unhandled();
            case PHYSICAL_ZERO:
                return PHYSICAL_ZERO.contains(field)
                        ? Resolution.handled(0) : Resolution.unhandled();
            case PHYSICAL_CELL_ID:
                return "physical_cell_id".equals(field)
                        ? Resolution.handled(-1) : Resolution.unhandled();
            case NEIGHBOR_CELL_LIST:
                return Resolution.unhandled();
            default:
                return Resolution.unhandled();
        }
    }

    /** DATA_UNKNOWN was added in API 29; older public contracts use DISCONNECTED as the empty state. */
    static int dataStateUnavailableValueForApi(int apiLevel) {
        return apiLevel >= 29 ? -1 : 0;
    }

    /**
     * Canonical value stored in {@link Snapshot} for generic getter hooks and cell builders.
     * Exceptional projections (PLMN strings and {@code GsmCellLocation}) still resolve explicitly
     * at their call sites.
     */
    public static Resolution resolveSnapshotField(String field) {
        Surface surface = SNAPSHOT_SURFACES.get(field);
        return surface == null ? Resolution.unhandled() : resolve(field, surface);
    }

    /** Public getter surface used by the verification observer for a profile column. */
    public static Resolution resolveObservedField(String field) {
        if ("mcc".equals(field) || "mnc".equals(field)) {
            return resolve(field, Surface.CELL_IDENTITY_PLMN_STRING);
        }
        return resolveSnapshotField(field);
    }
}

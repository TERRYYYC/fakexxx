package name.caiyao.fakegps.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single constant source of the module ↔ hook-group mapping (transport schema v5).
 *
 * <p>The user configures a handful of understandable modules (location / cellular / wifi / …);
 * the hook actually registers 16 fine-grained groups. This class is the ONLY place the two
 * vocabularies meet: the app side enumerates [ALL] to publish the {@code modules} object and to
 * render the settings switches; the hook side resolves each registered group to its owning module
 * (via {@code ModuleGate}) to decide registration. Because both sides compile against THIS table,
 * a group added to {@code HookUtils} without a module assignment is caught by the exhaustiveness
 * unit tests instead of silently becoming unregisterable-through-the-UI.
 *
 * <p>Pure Java, no Android types — the hook side shares it verbatim inside the target process.
 */
public final class SpoofModules {
    private SpoofModules() {}

    /** GPS coordinates and provider surface. */
    public static final String LOCATION = "location";
    /** Cell identity, signal, operator/SIM, service state, physical channel config. */
    public static final String CELLULAR = "cellular";
    /** The 14-field WiFi fingerprint. */
    public static final String WIFI = "wifi";
    /** IP/DNS/gateway/interface and connectivity surface. */
    public static final String NETWORK_IP = "networkIp";
    /** Telephony listeners/callbacks (call-state pushes to the target app). */
    public static final String PHONE_STATE = "phoneState";
    /** GMS fused-location client surface. */
    public static final String FUSED = "fused";

    /**
     * Canonical module list, in wire/UI order. The published {@code modules} object enumerates
     * exactly these names; anything else in a payload is rejected (fail-closed).
     */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
            LOCATION, CELLULAR, WIFI, NETWORK_IP, PHONE_STATE, FUSED));

    private static final Map<String, List<String>> MODULE_GROUPS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(LOCATION, Collections.unmodifiableList(Arrays.asList(
                "Location", "LocationManagerCtor", "GpsStatus")));
        m.put(CELLULAR, Collections.unmodifiableList(Arrays.asList(
                "CellIdentity", "CellIdentityGetters", "SignalStrength", "Telephony",
                "ServiceState", "PhysicalChannelConfig", "SubscriptionAware")));
        m.put(WIFI, Collections.unmodifiableList(Collections.singletonList(
                "WiFi")));
        m.put(NETWORK_IP, Collections.unmodifiableList(Arrays.asList(
                "Network", "Connectivity")));
        m.put(PHONE_STATE, Collections.unmodifiableList(Arrays.asList(
                "PhoneStateListener", "TelephonyCallback")));
        m.put(FUSED, Collections.unmodifiableList(Collections.singletonList(
                "FusedLocation")));
        MODULE_GROUPS = Collections.unmodifiableMap(m);
    }

    /** Whether {@code name} is a canonical module name (strict — no normalization). */
    public static boolean isKnown(String name) {
        return name != null && MODULE_GROUPS.containsKey(name);
    }

    /** Hook-group names owned by {@code module}. Throws for an unknown module. */
    public static List<String> groupsOf(String module) {
        List<String> groups = MODULE_GROUPS.get(module);
        if (groups == null) {
            throw new IllegalArgumentException("unknown module: " + module);
        }
        return groups;
    }

    /** Read-only view of the full mapping; iteration order follows [ALL]. */
    public static Map<String, List<String>> moduleGroups() {
        return MODULE_GROUPS;
    }
}

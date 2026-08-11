package name.caiyao.fakegps.hook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure registry for physical-channel getters whose unavailable result equals Builder defaults.
 *
 * <p>The device matrix cannot distinguish a working override from a per-getter no-op for these
 * values. HookUtils consumes this registry when installing the actual Xposed hooks, while JVM
 * tests census the same entries without loading Xposed classes.
 */
final class PhysicalChannelHookRegistry {

    static final class Entry {
        final String field;
        final String methodName;
        final int minApi;

        Entry(String field, String methodName, int minApi) {
            this.field = field;
            this.methodName = methodName;
            this.minApi = minApi;
        }
    }

    private static final Entry[] ENTRIES = {
            new Entry("cell_bandwidth_downlink", "getCellBandwidthDownlinkKhz", 29),
            new Entry("physical_cell_id", "getPhysicalCellId", 29),
            new Entry("band", "getBand", 31),
            new Entry("channel_bandwidth", "getCellBandwidthUplinkKhz", 33),
    };

    private PhysicalChannelHookRegistry() {}

    static List<Entry> entriesForApi(int apiLevel) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (apiLevel >= entry.minApi) result.add(entry);
        }
        return result;
    }

    static Set<String> getterMethodsForApi(int apiLevel) {
        Set<String> methods = new LinkedHashSet<>();
        for (Entry entry : entriesForApi(apiLevel)) methods.add(entry.methodName);
        return methods;
    }
}

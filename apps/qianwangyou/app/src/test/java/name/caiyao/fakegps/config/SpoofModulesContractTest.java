package name.caiyao.fakegps.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Locks the single constant source of the module ↔ hook-group mapping (transport schema v5).
 *
 * <p>The mapping must stay EXHAUSTIVE over the {@code HookUtils} registration groups: a group
 * missing from the map would silently fall outside every module switch (unreachable through the
 * UI), and a group claimed by two modules would make its gate ambiguous. Both are contract
 * failures, so both are pinned here.
 *
 * <p>P3 (T9) grew the vocabulary to SEVEN modules and 17 groups: {@code motion} owns the
 * {@code Sensors} group (the P3.2 hook group). Sensors is reserved here ahead of its plugin so
 * the vocabulary is final; the group itself registers with device-only verification.
 */
public class SpoofModulesContractTest {

    /** The 16 groups HookUtils registers today, in registration order... */
    private static final List<String> LEGACY_HOOK_GROUPS = Arrays.asList(
            "Location", "CellIdentity", "CellIdentityGetters", "SignalStrength",
            "Telephony", "ServiceState", "WiFi", "Network",
            "PhoneStateListener", "GpsStatus", "LocationManagerCtor", "TelephonyCallback",
            "Connectivity", "PhysicalChannelConfig", "SubscriptionAware", "FusedLocation");

    /** …plus the P3.2 Sensors group, owned by {@code motion} (plugin lands with P3.2). */
    private static final List<String> ALL_HOOK_GROUPS() {
        java.util.List<String> all = new ArrayList<>(LEGACY_HOOK_GROUPS);
        all.add("Sensors");
        return all;
    }

    @Test
    public void canonicalModuleVocabularyIsExactlyTheSevenUserFacingModules() {
        assertEquals(
                Arrays.asList("location", "cellular", "wifi", "networkIp", "phoneState", "fused",
                        "motion"),
                SpoofModules.ALL);
        assertEquals("module names must be distinct", 7, new HashSet<>(SpoofModules.ALL).size());
    }

    @Test
    public void factoryDefaultsEveryV4EraModuleOnAndMotionOff() {
        for (String module : Arrays.asList("location", "cellular", "wifi", "networkIp",
                "phoneState", "fused")) {
            assertTrue("v4-era module must default ON: " + module,
                    SpoofModules.defaultEnabled(module));
        }
        assertTrue("motion must default OFF (off = byte-identical legacy behavior)",
                !SpoofModules.defaultEnabled("motion"));
    }

    @Test
    public void isKnownAcceptsExactlyTheCanonicalNames() {
        for (String module : SpoofModules.ALL) {
            assertTrue(SpoofModules.isKnown(module));
        }
        // Wire vocabulary is case-sensitive: the payload validator rejects near-misses so a
        // typo'd module name can never silently enable/disable the wrong surface.
        for (String nearMiss : Arrays.asList("Location", "WIFI", "cellular ", "network_ip",
                "phonEState", "fused2", "")) {
            assertTrue("near-miss must not be known: " + nearMiss, !SpoofModules.isKnown(nearMiss));
        }
    }

    @Test
    public void groupMappingIsExhaustiveOverAllHookGroups() {
        Set<String> union = new LinkedHashSet<>();
        for (String module : SpoofModules.ALL) {
            union.addAll(SpoofModules.groupsOf(module));
        }
        assertEquals(
                "union of module groups must equal the HookUtils groups exactly",
                new HashSet<>(ALL_HOOK_GROUPS()),
                union);
        assertEquals("no duplicate group across modules", 17, union.size());
        // The legacy 16 stay claimed by their v5 modules; Sensors belongs to motion alone.
        assertTrue(union.containsAll(LEGACY_HOOK_GROUPS));
        assertEquals(Arrays.asList("Sensors"), SpoofModules.groupsOf("motion"));
    }

    @Test
    public void everyModuleOwnsAtLeastOneGroup() {
        for (String module : SpoofModules.ALL) {
            assertTrue("module owns no group: " + module,
                    !SpoofModules.groupsOf(module).isEmpty());
        }
    }

    @Test
    public void noGroupBelongsToTwoModules() {
        Set<String> seen = new HashSet<>();
        for (String module : SpoofModules.ALL) {
            for (String group : SpoofModules.groupsOf(module)) {
                assertTrue("group claimed by two modules: " + group, seen.add(group));
            }
        }
    }

    @Test
    public void exposedModuleGroupsViewMatchesGroupsOf() {
        for (String module : SpoofModules.ALL) {
            assertEquals(SpoofModules.groupsOf(module), SpoofModules.moduleGroups().get(module));
        }
        assertEquals(SpoofModules.ALL.size(), SpoofModules.moduleGroups().size());
    }

    @Test
    public void groupsOfUnknownModuleThrows() {
        try {
            SpoofModules.groupsOf("not-a-module");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // fail-closed: unknown module names must never resolve to a group set
        }
    }

    @Test
    public void constantSourceIsSharedByWriterAndHookSide() {
        // The published `modules` object keys ARE the canonical names; the writer and the hook
        // both resolve through this class, so the vocabulary exists exactly once.
        List<String> keys = new ArrayList<>();
        for (String module : SpoofModules.ALL) keys.add(module);
        assertEquals(7, keys.size());
        assertTrue(SpoofModules.isKnown(SpoofModules.LOCATION));
        assertTrue(SpoofModules.isKnown(SpoofModules.CELLULAR));
        assertTrue(SpoofModules.isKnown(SpoofModules.WIFI));
        assertTrue(SpoofModules.isKnown(SpoofModules.NETWORK_IP));
        assertTrue(SpoofModules.isKnown(SpoofModules.PHONE_STATE));
        assertTrue(SpoofModules.isKnown(SpoofModules.FUSED));
        assertTrue(SpoofModules.isKnown(SpoofModules.MOTION));
    }
}

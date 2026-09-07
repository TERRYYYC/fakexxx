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
 * <p>The mapping must stay EXHAUSTIVE over the 16 {@code HookUtils} registration groups: a group
 * missing from the map would silently fall outside every module switch (unreachable through the
 * UI), and a group claimed by two modules would make its gate ambiguous. Both are contract
 * failures, so both are pinned here.
 */
public class SpoofModulesContractTest {

    /** The 16 groups HookUtils registers, in registration order. */
    private static final List<String> ALL_HOOK_GROUPS = Arrays.asList(
            "Location", "CellIdentity", "CellIdentityGetters", "SignalStrength",
            "Telephony", "ServiceState", "WiFi", "Network",
            "PhoneStateListener", "GpsStatus", "LocationManagerCtor", "TelephonyCallback",
            "Connectivity", "PhysicalChannelConfig", "SubscriptionAware", "FusedLocation");

    @Test
    public void canonicalModuleVocabularyIsExactlyTheSixUserFacingModules() {
        assertEquals(
                Arrays.asList("location", "cellular", "wifi", "networkIp", "phoneState", "fused"),
                SpoofModules.ALL);
        assertEquals("module names must be distinct", 6, new HashSet<>(SpoofModules.ALL).size());
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
    public void groupMappingIsExhaustiveOverAllSixteenHookGroups() {
        Set<String> union = new LinkedHashSet<>();
        for (String module : SpoofModules.ALL) {
            union.addAll(SpoofModules.groupsOf(module));
        }
        assertEquals(
                "union of module groups must equal the 16 HookUtils groups exactly",
                new HashSet<>(ALL_HOOK_GROUPS),
                union);
        assertEquals("no duplicate group across modules", 16, union.size());
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
        assertEquals(6, keys.size());
        assertTrue(SpoofModules.isKnown(SpoofModules.LOCATION));
        assertTrue(SpoofModules.isKnown(SpoofModules.CELLULAR));
        assertTrue(SpoofModules.isKnown(SpoofModules.WIFI));
        assertTrue(SpoofModules.isKnown(SpoofModules.NETWORK_IP));
        assertTrue(SpoofModules.isKnown(SpoofModules.PHONE_STATE));
        assertTrue(SpoofModules.isKnown(SpoofModules.FUSED));
    }
}

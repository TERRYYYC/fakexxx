package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import name.caiyao.fakegps.config.SpoofModules;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Hook-side module gating decisions (transport schema v5).
 *
 * <p>Semantics under test:
 * <ul>
 *   <li>payload without {@code modules} (v4 shape) → every group enabled (backward compat);</li>
 *   <li>module = true → its groups register; module = false → its groups are fully skipped;</li>
 *   <li>unknown module name OR non-boolean value → {@link IllegalArgumentException}, which the
 *       caller turns into "reject the whole payload, keep last-known-good" (fail-closed).</li>
 * </ul>
 */
public class ModuleGateTest {

    private static final Set<String> ALL_16_GROUPS = new HashSet<>(Arrays.asList(
            "Location", "CellIdentity", "CellIdentityGetters", "SignalStrength",
            "Telephony", "ServiceState", "WiFi", "Network",
            "PhoneStateListener", "GpsStatus", "LocationManagerCtor", "TelephonyCallback",
            "Connectivity", "PhysicalChannelConfig", "SubscriptionAware", "FusedLocation"));

    // --- Registration decisions: BOTH states asserted for EVERY group -----------------------

    @Test
    public void everyGroupRegistersWhenNothingIsDisabled() {
        for (String group : ALL_16_GROUPS) {
            assertTrue(group, ModuleGate.shouldRegister(group, ModuleGate.noneDisabled()));
        }
    }

    @Test
    public void everyGroupSkipsWhenItsOwningModuleIsDisabled() {
        for (String module : SpoofModules.ALL) {
            Set<String> disabled = Collections.singleton(module);
            for (String group : SpoofModules.groupsOf(module)) {
                assertFalse("group " + group + " must be gated off by module " + module,
                        ModuleGate.shouldRegister(group, disabled));
            }
        }
    }

    @Test
    public void everyGroupMapsToExactlyOneOwningModule() {
        for (String group : ALL_16_GROUPS) {
            assertTrue(group, SpoofModules.isKnown(ModuleGate.moduleOf(group)));
        }
        assertEquals(16, ALL_16_GROUPS.size());
    }

    @Test
    public void disablingOneModuleLeavesOtherGroupsRegistered() {
        Set<String> disabled = Collections.singleton(SpoofModules.WIFI);
        assertTrue(ModuleGate.shouldRegister("Location", disabled));
        assertTrue(ModuleGate.shouldRegister("Telephony", disabled));
        assertFalse(ModuleGate.shouldRegister("WiFi", disabled));
    }

    @Test
    public void unknownGroupIsRejectedRatherThanDefaultingToRegistered() {
        try {
            ModuleGate.shouldRegister("NotARealGroup", ModuleGate.noneDisabled());
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // a group outside the map must fail loudly at registration time, not silently run
        }
    }

    // --- Payload parsing ---------------------------------------------------------------------

    @Test
    public void absentModulesObjectMeansEverythingEnabled() throws Exception {
        JSONObject root = new JSONObject("{\"schemaVersion\":4,\"fields\":{}}");
        assertEquals(Collections.emptySet(), ModuleGate.fromPayload(root.opt("modules")));
    }

    @Test
    public void explicitAllTrueModulesDisablesNothing() throws Exception {
        JSONObject modules = new JSONObject();
        for (String module : SpoofModules.ALL) modules.put(module, true);
        assertEquals(Collections.emptySet(), ModuleGate.fromPayload(modules));
    }

    @Test
    public void falseEntriesBecomeDisabledSet() throws Exception {
        JSONObject modules = new JSONObject()
                .put(SpoofModules.WIFI, false)
                .put(SpoofModules.FUSED, false)
                .put(SpoofModules.CELLULAR, true);
        assertEquals(
                new HashSet<>(Arrays.asList(SpoofModules.WIFI, SpoofModules.FUSED)),
                ModuleGate.fromPayload(modules));
    }

    @Test
    public void nonBooleanValueRejectsThePayloadDecision() throws Exception {
        for (Object hostile : Arrays.asList("true", 1, 0, 1.5, JSONObject.NULL,
                new JSONObject("{\"nested\":true}"))) {
            JSONObject modules = new JSONObject().put(SpoofModules.WIFI, hostile);
            try {
                ModuleGate.fromPayload(modules);
                throw new AssertionError("expected IllegalArgumentException for " + hostile);
            } catch (IllegalArgumentException expected) {
                // caller maps this onto the existing rejection path: keep last-known-good
            }
        }
    }

    @Test
    public void unknownModuleNameRejectsThePayloadDecision() throws Exception {
        JSONObject modules = new JSONObject()
                .put(SpoofModules.WIFI, true)
                .put("celluler", true); // near-miss typo
        try {
            ModuleGate.fromPayload(modules);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // fail-closed: an unknown group name must never be interpreted as "enabled"
        }
    }

    @Test
    public void partialModuleSetTreatsUnmentionedModulesAsEnabled() throws Exception {
        // A payload may switch off one module without enumerating the rest; anything absent
        // defaults to enabled so per-module rollout stays incremental.
        JSONObject modules = new JSONObject().put(SpoofModules.NETWORK_IP, false);
        Set<String> disabled = ModuleGate.fromPayload(modules);
        assertEquals(Collections.singleton(SpoofModules.NETWORK_IP), disabled);
        assertTrue(ModuleGate.shouldRegister("WiFi", disabled));
        assertFalse(ModuleGate.shouldRegister("Network", disabled));
        assertFalse(ModuleGate.shouldRegister("Connectivity", disabled));
    }
}

package name.caiyao.fakegps.hook;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import name.caiyao.fakegps.config.SpoofModules;

/**
 * Hook-side registration gating decisions for the transport schema v5 {@code modules} object.
 *
 * <p>Pure decision layer, extracted so the fail-closed rules are locked by JVM tests: MainHook
 * itself cannot be unit-tested (Xposed classes are compileOnly). Rules:
 * <ul>
 *   <li>a payload WITHOUT {@code modules} (v4 shape) enables every group — backward compatible;</li>
 *   <li>a module set to {@code false} un-registers ALL of its hook groups (the most complete
 *       dismantling: nothing hooks, the target app reads the fully native stack);</li>
 *   <li>an unknown module name or a non-boolean value throws {@link IllegalArgumentException} —
 *       the caller maps that onto the existing rejection path (log + keep last-known-good), never
 *       onto a partial interpretation of a payload we cannot fully trust.</li>
 * </ul>
 *
 * <p>Module decisions affect REGISTRATION ONLY. Fields of an already-registered hook keep their
 * three-state semantics (null = passthrough / "--" = unavailable / value = spoof) untouched.
 */
final class ModuleGate {
    private ModuleGate() {}

    /** Group → owning module, derived once from the single constant source. */
    private static final Map<String, String> GROUP_TO_MODULE;
    static {
        Map<String, String> m = new HashMap<>();
        for (String module : SpoofModules.ALL) {
            for (String group : SpoofModules.groupsOf(module)) {
                String previous = m.put(group, module);
                if (previous != null) {
                    throw new IllegalStateException(
                            "hook group claimed by two modules: " + group);
                }
            }
        }
        GROUP_TO_MODULE = Collections.unmodifiableMap(m);
    }

    static Set<String> noneDisabled() {
        return Collections.emptySet();
    }

    /**
     * Validate the payload's {@code modules} value and return the DISABLED module names.
     *
     * @param modulesValue the raw {@code root.opt("modules")} — a JSONObject, or null (absent)
     * @return never-null set; empty when everything is enabled
     * @throws IllegalArgumentException on an unknown module name or a non-boolean value
     */
    static Set<String> fromPayload(Object modulesValue) {
        if (modulesValue == null) {
            return noneDisabled();
        }
        if (!(modulesValue instanceof org.json.JSONObject)) {
            throw new IllegalArgumentException(
                    "modules is not an object: " + modulesValue.getClass().getName());
        }
        org.json.JSONObject modules = (org.json.JSONObject) modulesValue;
        Set<String> disabled = new HashSet<>();
        Iterator<String> keys = modules.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!SpoofModules.isKnown(name)) {
                throw new IllegalArgumentException("unknown module: " + name);
            }
            Object value = modules.opt(name);
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "module value is not a boolean: " + name + "=" + value);
            }
            if (!(Boolean) value) {
                disabled.add(name);
            }
        }
        return disabled;
    }

    /**
     * Registration decision for one hook group given the disabled-module set from the loaded
     * snapshot. An unknown group throws instead of defaulting to "register" — a group outside the
     * mapping must be wired into {@code SpoofModules} first, not silently ungated.
     */
    static boolean shouldRegister(String group, Set<String> disabledModules) {
        return !disabledModules.contains(moduleOf(group));
    }

    /** The module owning {@code group}. Throws for a group outside the mapping. */
    static String moduleOf(String group) {
        String module = GROUP_TO_MODULE.get(group);
        if (module == null) {
            throw new IllegalArgumentException("hook group has no module mapping: " + group);
        }
        return module;
    }
}

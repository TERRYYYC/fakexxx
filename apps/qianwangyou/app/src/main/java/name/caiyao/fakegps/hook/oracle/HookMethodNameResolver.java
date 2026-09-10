package name.caiyao.fakegps.hook.oracle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HyperOS jarjar renames some framework methods into {@code bareName$…pre_jarjar} spellings
 * (mi14 HyperOS 3.0.303 dexdump: AccessCheckingService onPackageRemoved/onPackageUninstalled/
 * onUserRemoved, #149), so a bare-name {@code hookAllMethods} can miss methods that are still
 * present.
 *
 * <p>Pure name math only — no Xposed, no reflection beyond {@link #declaredMethodNames(Class)}.
 * Given a class's declared method names and one bare plan name it returns every hookable
 * spelling: the bare name first, then jarjar-renamed spellings in sorted order. An empty result
 * is meaningful: the caller must keep its fail-closed poison path.</p>
 */
public final class HookMethodNameResolver {
    private HookMethodNameResolver() {}

    /** Every declared method name of {@code target}; getDeclaredMethods order is unspecified. */
    public static Set<String> declaredMethodNames(Class<?> target) {
        Set<String> names = new HashSet<>();
        for (Method method : target.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Bare name first (when declared), then every {@code bareName$…} jarjar spelling sorted —
     * sorted because getDeclaredMethods order is unspecified and hook installation should be
     * deterministic. Near-misses without the {@code $} delimiter (e.g. {@code
     * onPackageRemovedExtra}) are deliberately never matched, and neither are the measured
     * synthetic families {@code access$…}, {@code lambda$…}, {@code $r8$lambda$…} or {@code
     * $i$a$-…}, which never begin with the bare name. A Kotlin default-args sibling ({@code
     * bareName$default}) DOES match; accepted because it is only reachable when the bare name
     * itself is absent and the real renamed method is hooked alongside it (#149).
     */
    public static List<String> resolveActualHookNames(
            Collection<String> declaredMethodNames, String bareName) {
        if (bareName == null || bareName.isEmpty() || declaredMethodNames == null) {
            return Collections.emptyList();
        }
        List<String> resolved = new ArrayList<>();
        if (declaredMethodNames.contains(bareName)) {
            resolved.add(bareName);
        }
        List<String> renamed = new ArrayList<>();
        for (String name : declaredMethodNames) {
            if (isJarJarRenamed(bareName, name)) {
                renamed.add(name);
            }
        }
        Collections.sort(renamed);
        resolved.addAll(renamed);
        return Collections.unmodifiableList(resolved);
    }

    /** jarjar delimits the rename with {@code $}, so a bare name never matches its own prefix. */
    public static boolean isJarJarRenamed(String bareName, String actualName) {
        if (bareName == null || actualName == null) return false;
        return actualName.length() > bareName.length()
                && actualName.startsWith(bareName)
                && actualName.charAt(bareName.length()) == '$';
    }
}

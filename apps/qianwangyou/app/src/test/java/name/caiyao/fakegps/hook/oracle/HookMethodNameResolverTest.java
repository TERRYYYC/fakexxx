package name.caiyao.fakegps.hook.oracle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Four-state contract for HyperOS jarjar method-name resolution (#149). dexdump of the mi14
 * HyperOS 3.0.303.0.WNCCNXM (SDK 36) services.jar shows AccessCheckingService declares ALL of
 * onPackageRemoved/onPackageUninstalled/onUserRemoved only under
 * {@code bareName$frameworks__base__services__permission__android_common__services_permission_pre_jarjar}
 * spellings, so the resolver must find the bare name, renamed-only, both, and — critically —
 * resolve empty when neither exists so the installer stays fail-closed.
 */
public class HookMethodNameResolverTest {
    private static final String BARE = "onPackageRemoved";
    private static final String JARJAR =
            BARE + "$frameworks__base__services__permission__android_common__services_permission_pre_jarjar";
    private static final String JARJAR_ALT = BARE + "$miui__permission";

    @Test
    public void bareNamePresentResolvesToBareName() {
        assertEquals(
                Collections.singletonList(BARE),
                HookMethodNameResolver.resolveActualHookNames(
                        new HashSet<>(Arrays.asList(BARE, "unrelated")), BARE));
    }

    @Test
    public void onlyJarJarRenamedPresentResolvesToEveryRenamedSpellingSorted() {
        // Reverse-lexicographic input: an unsorted resolver would mirror this order and fail,
        // so the sort is genuinely pinned instead of coinciding with HashSet iteration order.
        List<String> resolved = HookMethodNameResolver.resolveActualHookNames(
                Arrays.asList(JARJAR_ALT, JARJAR, "unrelated"), BARE);
        assertEquals(Arrays.asList(JARJAR, JARJAR_ALT), resolved);
    }

    @Test
    public void bareAndJarJarBothPresentResolveToBareFirstThenRenamedSorted() {
        List<String> resolved = HookMethodNameResolver.resolveActualHookNames(
                Arrays.asList(JARJAR_ALT, BARE, JARJAR), BARE);
        assertEquals(Arrays.asList(BARE, JARJAR, JARJAR_ALT), resolved);
    }

    @Test
    public void neitherPresentResolvesEmptySoInstallerStaysFailClosed() {
        assertTrue(HookMethodNameResolver.resolveActualHookNames(
                new HashSet<>(Arrays.asList("somethingElse", "onPackageRemovedExtra")), BARE)
                .isEmpty());
    }

    /**
     * Measured synthetic-name families on the mi14 HyperOS 3.0.303.0.WNCCNXM services.jar:
     * Kotlin {@code access$…} accessors, D8 default-method-lambda companions ({@code
     * $i$a$-…}), and Java/R8 lambda synthetics ({@code lambda$…}, {@code $r8$lambda$…}) embed
     * the bare name only after a non-bare prefix, and inner lambda classes contribute no
     * declared methods at all — none of them may resolve as a jarjar spelling.
     */
    @Test
    public void measuredHyperOsSyntheticNameFamiliesNeverMatchBarePrefix() {
        Set<String> decoys = new HashSet<>(Arrays.asList(
                "access$getState$p",
                "$i$a$-check-AccessPolicy$onPackageRemoved$1",
                "$i$a$-use-AccessCheckingService$allPackageStates$1",
                "lambda$onStateChanged$20",
                "$r8$lambda$b-DpR8g_gHKh6QevGlmwPmA5i4M",
                "AccessCheckingService$onPackageRemoved$1",
                "onPackageRemovedExtra"));
        assertTrue(HookMethodNameResolver.resolveActualHookNames(decoys, BARE).isEmpty());
    }

    /**
     * A Kotlin default-args sibling ({@code bareName$default}) does start with {@code
     * bareName$} and intentionally matches: it is only reachable when the bare name itself is
     * absent, and the real renamed method is hooked alongside it. Accepted boundary of the
     * {@code startsWith(bareName + "$")} rule (#149 review).
     */
    @Test
    public void kotlinDefaultArgsSyntheticSiblingIsAnAcceptedMatch() {
        assertTrue(HookMethodNameResolver.isJarJarRenamed(BARE, BARE + "$default"));
    }

    @Test
    public void nearMissWithoutDollarDelimiterIsNeverJarJarRenamed() {
        assertFalse(HookMethodNameResolver.isJarJarRenamed(BARE, "onPackageRemovedExtra"));
        assertFalse(HookMethodNameResolver.isJarJarRenamed(BARE, BARE));
        assertTrue(HookMethodNameResolver.isJarJarRenamed(BARE, JARJAR));
    }

    @Test
    public void nullInputsResolveEmptyInsteadOfThrowing() {
        assertEquals(Collections.emptyList(), HookMethodNameResolver.resolveActualHookNames(null, BARE));
        assertEquals(
                Collections.emptyList(),
                HookMethodNameResolver.resolveActualHookNames(new HashSet<>(Arrays.asList(BARE)), null));
    }

    /**
     * Mirrors the measured mi14 HyperOS 3.0.303.0.WNCCNXM (SDK 36) state: all three lifecycle
     * plan methods exist ONLY under the jarjar spelling — AccessCheckingService declares no
     * bare onPackageRemoved, onPackageUninstalled, or onUserRemoved.
     */
    @Test
    public void declaredNamesOfHyperOsStyleFixtureResolveThroughReflection() {
        Set<String> declared =
                HookMethodNameResolver.declaredMethodNames(HyperOsStyleFixture.class);
        assertEquals(
                Collections.singletonList(
                        "onPackageRemoved$frameworks__base__services__permission"
                                + "__android_common__services_permission_pre_jarjar"),
                HookMethodNameResolver.resolveActualHookNames(declared, "onPackageRemoved"));
        assertEquals(
                Collections.singletonList(
                        "onPackageUninstalled$frameworks__base__services__permission"
                                + "__android_common__services_permission_pre_jarjar"),
                HookMethodNameResolver.resolveActualHookNames(declared, "onPackageUninstalled"));
        assertEquals(
                Collections.singletonList(
                        "onUserRemoved$frameworks__base__services__permission"
                                + "__android_common__services_permission_pre_jarjar"),
                HookMethodNameResolver.resolveActualHookNames(declared, "onUserRemoved"));
    }

    /** jarjar spellings are legal Java identifiers, so the fixture needs no bytecode tricks. */
    static class HyperOsStyleFixture {
        public void onPackageRemoved$frameworks__base__services__permission__android_common__services_permission_pre_jarjar(
                String pkg, int user) {}
        public void onPackageUninstalled$frameworks__base__services__permission__android_common__services_permission_pre_jarjar(
                String pkg, int user) {}
        public void onUserRemoved$frameworks__base__services__permission__android_common__services_permission_pre_jarjar(
                int user) {}
        public void unrelated(String other) {}
    }
}

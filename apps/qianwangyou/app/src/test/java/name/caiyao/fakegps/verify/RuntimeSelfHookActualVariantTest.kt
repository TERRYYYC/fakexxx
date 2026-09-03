package name.caiyao.fakegps.verify

import name.caiyao.fakegps.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Run this same class in debug, codexBench AND release; no simulated build Boolean. */
class RuntimeSelfHookActualVariantTest {
    private val expectedNonProbeSelfHook: Boolean
        get() = when (BuildConfig.BUILD_TYPE) {
            "debug" -> true
            "codexBench", "release" -> false
            else -> error("Unspecified hook policy for ${BuildConfig.BUILD_TYPE}")
        }

    @Test
    fun actualApkIdentityMatchesTheVariant() {
        val expected = when (BuildConfig.BUILD_TYPE) {
            "debug" -> "name.caiyao.fakegps.bench"
            "codexBench" -> "name.caiyao.fakegps.codexbench"
            "release" -> "name.caiyao.fakegps"
            else -> error("Unspecified variant")
        }
        assertEquals(expected, BuildConfig.APPLICATION_ID)
        assertEquals(expected, RuntimeSelfHookPolicy.MODULE_PACKAGE)
        assertEquals("$expected:hook_verify", RuntimeSelfHookPolicy.PROBE_PROCESS)
        assertEquals(BuildConfig.BUILD_TYPE != "release", BuildConfig.DEBUG)
        assertEquals(expectedNonProbeSelfHook, BuildConfig.ALLOW_NON_PROBE_SELF_HOOK)
    }

    @Test
    fun nonProbeSelfProcessesFollowTheActualBuildPolicy() {
        val ownPackage = BuildConfig.APPLICATION_ID
        for (process in listOf(ownPackage, "$ownPackage:worker", "$ownPackage:integration",
                "$ownPackage:hook_verify_extra", "$ownPackage:hook_verify:child", "", null)) {
            assertEquals("${BuildConfig.BUILD_TYPE} / $process must not fake its own raw readback",
                expectedNonProbeSelfHook, RuntimeSelfHookPolicy.shouldHook(ownPackage, process))
        }
    }

    @Test
    fun exactPrivateProbeRemainsEligibleInEveryVariant() {
        assertTrue(RuntimeSelfHookPolicy.shouldHook(
            BuildConfig.APPLICATION_ID, RuntimeSelfHookPolicy.PROBE_PROCESS))
    }

    @Test
    fun scopedExternalPackagesAreNotMistakenForSelfByNamePrefix() {
        for (pkg in listOf("com.example.target", "${BuildConfig.APPLICATION_ID}.other")) {
            for (process in listOf(pkg, "$pkg:worker", RuntimeSelfHookPolicy.PROBE_PROCESS)) {
                assertTrue("External target $pkg / $process remains eligible",
                    RuntimeSelfHookPolicy.shouldHook(pkg, process))
            }
        }
    }
}

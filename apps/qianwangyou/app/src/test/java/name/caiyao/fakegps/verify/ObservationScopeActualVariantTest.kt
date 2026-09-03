package name.caiyao.fakegps.verify

import name.caiyao.fakegps.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationScopeActualVariantTest {
    @Test
    fun configurationProcessLabelMatchesTheActualHookEligibility() {
        val expected = when (BuildConfig.BUILD_TYPE) {
            "debug" -> ObservationScope.SELF_HOOKED
            "codexBench", "release" -> ObservationScope.REAL_BASELINE
            else -> error("Unspecified scope for ${BuildConfig.BUILD_TYPE}")
        }
        assertEquals(expected, ObservationScope.current())
        assertEquals(expected == ObservationScope.SELF_HOOKED,
            RuntimeSelfHookPolicy.shouldHook(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID))
    }
}

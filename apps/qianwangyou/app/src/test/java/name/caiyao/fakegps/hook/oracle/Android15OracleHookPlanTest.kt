package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import name.caiyao.fakegps.oracle.OracleWireHealth

class Android15OracleHookPlanTest {

    @Test
    fun `legacy entry recognizes only the exact system-server tuple`() {
        assertTrue(SystemServerOracleEntryPolicy.isSystemServer("android", "android"))
        assertFalse(SystemServerOracleEntryPolicy.isSystemServer("android", "system_server"))
        assertFalse(SystemServerOracleEntryPolicy.isSystemServer("name.caiyao.fakegps", "android"))
        assertFalse(SystemServerOracleEntryPolicy.isSystemServer(null, "android"))
        assertFalse(SystemServerOracleEntryPolicy.isSystemServer("android", null))
    }

    @Test
    fun `bridge trigger accepts exactly boot phase 600`() {
        assertFalse(SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(599))
        assertTrue(SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(600))
        assertFalse(SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(601))
    }

    @Test
    fun `plan pins API 35 Access Checking wrapper delegate and lifecycle`() {
        assertEquals(35, Android15OracleHookPlan.API_LEVEL)
        assertEquals(
            "com.android.server.appop.AppOpsCheckingServiceTracingDecorator",
            Android15OracleHookPlan.APP_OPS_WRAPPER_CLASS,
        )
        assertEquals(
            "com.android.server.permission.access.appop.AppOpService",
            Android15OracleHookPlan.ACCESS_CHECKING_DELEGATE_CLASS,
        )
        assertEquals(
            setOf("setUidMode", "setPackageMode", "removePackage", "removeUid"),
            Android15OracleHookPlan.ACCESS_CHECKING_MUTATION_METHODS.toSet(),
        )
        assertEquals(
            setOf("setUidMode", "setPackageMode", "removePackage", "removeUid", "clearAllModes"),
            Android15OracleHookPlan.APP_OPS_WRAPPER_MUTATION_METHODS.toSet(),
        )
        assertEquals(
            setOf("onPackageRemoved", "onPackageUninstalled", "onUserRemoved"),
            Android15OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_METHODS.toSet(),
        )
        assertFalse(
            "the Android-14 legacy delegate is not accepted as API-35 coverage",
            Android15OracleHookPlan.ACCESS_CHECKING_DELEGATE_CLASS.contains("AppOpsCheckingServiceImpl"),
        )
    }

    @Test
    fun `plan covers provider state enabled state and the lock held coordinate setter`() {
        assertEquals(
            setOf("onStateChanged", "onEnabledChanged"),
            Android15OracleHookPlan.LOCATION_MUTATION_METHODS.toSet(),
        )
        assertEquals(
            "com.android.server.location.provider.MockLocationProvider",
            Android15OracleHookPlan.LOCATION_MOCK_PROVIDER_CLASS,
        )
        assertEquals(
            "setProviderLocation",
            Android15OracleHookPlan.LOCATION_SEMANTIC_MUTATION_METHOD,
        )
        assertEquals(
            "setTestProviderLocation",
            Android15OracleHookPlan.LOCATION_QWY_PROVENANCE_ENTRY_METHOD,
        )
    }

    @Test
    fun `required mask is exact and distinguishes wrapper from delegate`() {
        assertTrue(Android15OracleHookPlan.COVERAGE_APP_OPS_WRAPPER != Android15OracleHookPlan.COVERAGE_ACCESS_CHECKING_DELEGATE)
        assertEquals(0x3ffL, Android15OracleHookPlan.REQUIRED_COVERAGE_MASK)
    }

    @Test
    fun `production fingerprint allowlist is intentionally empty`() {
        assertTrue(Android15OracleHookPlan.ATTESTED_FINGERPRINTS.isEmpty())
        assertFalse(Android15OracleHookPlan.isFingerprintAttested("any/real/device:fingerprint"))
    }

    @Test
    fun `callback error poisons an otherwise complete attested producer`() {
        assertEquals(
            OracleWireHealth.CALLBACK_POISONED,
            Android15OracleHookPlan.classifyHealth(
                true,
                true,
                true,
                false,
                true,
                Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
                true,
                true,
                true,
            ),
        )
    }

    @Test
    fun `unattested build can never be laundered by complete runtime flags`() {
        assertEquals(
            OracleWireHealth.BUILD_UNATTESTED,
            Android15OracleHookPlan.classifyHealth(
                true,
                false,
                true,
                false,
                false,
                Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
                true,
                true,
                true,
            ),
        )
    }
}

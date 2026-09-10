package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the Android-16 oracle hook surface against the AOSP diff in
 * docs/oracle-a16-research.md §2 (android-15.0.0_r1 vs android-16.0.0_r1): every hook point
 * survives API 36 unchanged, so the surface must stay in lockstep with the API-35 pilot while
 * attestation moves to exact-build IDs.
 */
class Android16OracleHookPlanTest {

    @Test
    fun `plan pins API 36 with the researched hook surfaces`() {
        assertEquals(36, Android16OracleHookPlan.API_LEVEL)
        assertEquals(
            "com.android.server.appop.AppOpsCheckingServiceTracingDecorator",
            Android16OracleHookPlan.APP_OPS_WRAPPER_CLASS,
        )
        assertEquals(
            "com.android.server.permission.access.appop.AppOpService",
            Android16OracleHookPlan.ACCESS_CHECKING_DELEGATE_CLASS,
        )
        assertEquals(
            "com.android.server.permission.access.AccessCheckingService",
            Android16OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_CLASS,
        )
        assertEquals(
            "com.android.server.location.LocationManagerService",
            Android16OracleHookPlan.LOCATION_MANAGER_SERVICE_CLASS,
        )
        assertEquals(
            "com.android.server.location.provider.LocationProviderManager",
            Android16OracleHookPlan.LOCATION_PROVIDER_MANAGER_CLASS,
        )
        assertEquals(
            "com.android.server.location.provider.MockLocationProvider",
            Android16OracleHookPlan.LOCATION_MOCK_PROVIDER_CLASS,
        )
        assertEquals(
            "com.android.server.SystemServiceManager",
            Android16OracleHookPlan.SYSTEM_SERVICE_MANAGER_CLASS,
        )
        assertEquals(
            setOf("setUidMode", "setPackageMode", "removePackage", "removeUid", "clearAllModes"),
            Android16OracleHookPlan.APP_OPS_WRAPPER_MUTATION_METHODS.toSet(),
        )
        assertEquals(
            setOf("setUidMode", "setPackageMode", "removePackage", "removeUid"),
            Android16OracleHookPlan.ACCESS_CHECKING_MUTATION_METHODS.toSet(),
        )
        assertEquals(
            setOf("onPackageRemoved", "onPackageUninstalled", "onUserRemoved"),
            Android16OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_METHODS.toSet(),
        )
        assertEquals(
            setOf("addTestProvider", "removeTestProvider", "setTestProviderEnabled"),
            Android16OracleHookPlan.LOCATION_QWY_MUTATION_ENTRY_METHODS.toSet(),
        )
        assertEquals(
            "setTestProviderLocation",
            Android16OracleHookPlan.LOCATION_QWY_PROVENANCE_ENTRY_METHOD,
        )
        assertEquals(
            "setProviderLocation",
            Android16OracleHookPlan.LOCATION_SEMANTIC_MUTATION_METHOD,
        )
    }

    @Test
    fun `a16 hook surface stays in lockstep with the a35 pilot everywhere but the api level`() {
        assertEquals(
            Android15OracleHookPlan.APP_OPS_WRAPPER_CLASS,
            Android16OracleHookPlan.APP_OPS_WRAPPER_CLASS,
        )
        assertEquals(
            Android15OracleHookPlan.ACCESS_CHECKING_DELEGATE_CLASS,
            Android16OracleHookPlan.ACCESS_CHECKING_DELEGATE_CLASS,
        )
        assertEquals(
            Android15OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_CLASS,
            Android16OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_CLASS,
        )
        assertEquals(
            Android15OracleHookPlan.LOCATION_MANAGER_SERVICE_CLASS,
            Android16OracleHookPlan.LOCATION_MANAGER_SERVICE_CLASS,
        )
        assertEquals(
            Android15OracleHookPlan.LOCATION_PROVIDER_MANAGER_CLASS,
            Android16OracleHookPlan.LOCATION_PROVIDER_MANAGER_CLASS,
        )
        assertEquals(
            Android15OracleHookPlan.LOCATION_MOCK_PROVIDER_CLASS,
            Android16OracleHookPlan.LOCATION_MOCK_PROVIDER_CLASS,
        )
        assertEquals(
            Android15OracleHookPlan.SYSTEM_SERVICE_MANAGER_CLASS,
            Android16OracleHookPlan.SYSTEM_SERVICE_MANAGER_CLASS,
        )
        assertEquals(
            Android15OracleHookPlan.APP_OPS_WRAPPER_MUTATION_METHODS.toSet(),
            Android16OracleHookPlan.APP_OPS_WRAPPER_MUTATION_METHODS.toSet(),
        )
        assertEquals(
            Android15OracleHookPlan.ACCESS_CHECKING_MUTATION_METHODS.toSet(),
            Android16OracleHookPlan.ACCESS_CHECKING_MUTATION_METHODS.toSet(),
        )
        assertEquals(
            Android15OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_METHODS.toSet(),
            Android16OracleHookPlan.ACCESS_CHECKING_LIFECYCLE_METHODS.toSet(),
        )
        assertEquals(
            Android15OracleHookPlan.LOCATION_QWY_MUTATION_ENTRY_METHODS.toSet(),
            Android16OracleHookPlan.LOCATION_QWY_MUTATION_ENTRY_METHODS.toSet(),
        )
        assertEquals(
            Android15OracleHookPlan.LOCATION_QWY_PROVENANCE_ENTRY_METHOD,
            Android16OracleHookPlan.LOCATION_QWY_PROVENANCE_ENTRY_METHOD,
        )
        assertEquals(
            Android15OracleHookPlan.LOCATION_SEMANTIC_MUTATION_METHOD,
            Android16OracleHookPlan.LOCATION_SEMANTIC_MUTATION_METHOD,
        )
        assertNotEquals(
            "the two plans must pin different platform levels",
            Android15OracleHookPlan.API_LEVEL,
            Android16OracleHookPlan.API_LEVEL,
        )
        assertEquals(
            "QWY attribution must not fork per platform version",
            Android15OracleHookPlan.QWY_MUTATION_ATTRIBUTION_TAG,
            Android16OracleHookPlan.QWY_MUTATION_ATTRIBUTION_TAG,
        )
    }

    @Test
    fun `a15 and a16 surfaces expose identical hook facts`() {
        val a15 = Android15OracleHookPlan.SURFACE
        val a16 = Android16OracleHookPlan.SURFACE
        assertEquals(Android15OracleHookPlan.API_LEVEL, a15.apiLevel())
        assertEquals(Android16OracleHookPlan.API_LEVEL, a16.apiLevel())
        assertEquals(a15.appOpsWrapperClass(), a16.appOpsWrapperClass())
        assertEquals(a15.appOpsWrapperMutationMethods().toSet(), a16.appOpsWrapperMutationMethods().toSet())
        assertEquals(a15.accessCheckingDelegateClass(), a16.accessCheckingDelegateClass())
        assertEquals(a15.accessCheckingMutationMethods().toSet(), a16.accessCheckingMutationMethods().toSet())
        assertEquals(a15.accessCheckingLifecycleClass(), a16.accessCheckingLifecycleClass())
        assertEquals(a15.accessCheckingLifecycleMethods().toSet(), a16.accessCheckingLifecycleMethods().toSet())
        assertEquals(a15.locationManagerServiceClass(), a16.locationManagerServiceClass())
        assertEquals(a15.locationQwyMutationEntryMethods().toSet(), a16.locationQwyMutationEntryMethods().toSet())
        assertEquals(a15.locationQwyProvenanceEntryMethod(), a16.locationQwyProvenanceEntryMethod())
        assertEquals(a15.locationMockProviderClass(), a16.locationMockProviderClass())
        assertEquals(a15.locationSemanticMutationMethod(), a16.locationSemanticMutationMethod())
        assertEquals(a15.locationProviderManagerClass(), a16.locationProviderManagerClass())
        assertEquals(a15.systemServiceManagerClass(), a16.systemServiceManagerClass())
    }

    @Test
    fun `mi14 pilot build is the only attested build id`() {
        assertEquals(
            setOf("BP2A.250605.031.A3"),
            Android16OracleHookPlan.ATTESTED_BUILD_IDS,
        )
        assertTrue(Android16OracleHookPlan.isBuildIdAttested("BP2A.250605.031.A3"))
        assertFalse("prefix builds are not the attested OTA", Android16OracleHookPlan.isBuildIdAttested("BP2A.250605.031"))
        assertFalse(Android16OracleHookPlan.isBuildIdAttested("BP2A.250605.031.A3/user"))
        assertFalse(Android16OracleHookPlan.isBuildIdAttested(null))
        assertFalse(Android16OracleHookPlan.isBuildIdAttested(""))
    }

    @Test
    fun `exact fingerprint channel stays empty until separately reviewed`() {
        assertTrue(Android16OracleHookPlan.ATTESTED_FINGERPRINTS.isEmpty())
        assertFalse(Android16OracleHookPlan.isFingerprintAttested("Xiaomi/msi14/mi14:16/BP2A.250605.031.A3/user/release-keys"))
    }

    @Test
    fun `build attestation needs the exact sdk and an attested build`() {
        val mi14Incremental = "BP2A.250605.031.A3"
        assertTrue(Android16OracleHookPlan.isBuildAttested(36, mi14Incremental, "any/fingerprint"))
        assertFalse("wrong SDK is never attested", Android16OracleHookPlan.isBuildAttested(35, mi14Incremental, null))
        assertFalse("unknown incremental is never attested", Android16OracleHookPlan.isBuildAttested(36, "OTHER.INCREMENTAL", null))
        assertFalse("null incremental is never attested", Android16OracleHookPlan.isBuildAttested(36, null, null))
    }

    @Test
    fun `surface attestation delegates to the pure policy`() {
        val a16 = Android16OracleHookPlan.SURFACE
        assertTrue(a16.attests("whatever", "BP2A.250605.031.A3"))
        assertFalse(a16.attests("whatever", "OTHER"))
        val a15 = Android15OracleHookPlan.SURFACE
        assertFalse(
            "a15 allowlist is empty, so the pilot stays inert regardless of build id",
            a15.attests("whatever", "BP2A.250605.031.A3"),
        )
    }
}

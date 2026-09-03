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
        assertEquals(0x2ffL, Android15OracleHookPlan.REQUIRED_EVIDENCE_COVERAGE_MASK)
        assertEquals(
            0L,
            Android15OracleHookPlan.REQUIRED_EVIDENCE_COVERAGE_MASK and
                Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED,
        )
    }

    @Test
    fun `admission state machine is exhaustive and only attested mints the build bit`() {
        assertEquals(
            setOf(
                Android15OracleHookPlan.BuildAdmission.UNLISTED,
                Android15OracleHookPlan.BuildAdmission.EVIDENCE_ONLY,
                Android15OracleHookPlan.BuildAdmission.ATTESTED,
            ),
            Android15OracleHookPlan.BuildAdmission.values().toSet(),
        )
        assertEquals(0L, Android15OracleHookPlan.initialCoverageMask(null))
        assertEquals(
            0L,
            Android15OracleHookPlan.initialCoverageMask(
                Android15OracleHookPlan.BuildAdmission.UNLISTED,
            ),
        )
        assertEquals(
            0L,
            Android15OracleHookPlan.initialCoverageMask(
                Android15OracleHookPlan.BuildAdmission.EVIDENCE_ONLY,
            ),
        )
        assertEquals(
            Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED,
            Android15OracleHookPlan.initialCoverageMask(
                Android15OracleHookPlan.BuildAdmission.ATTESTED,
            ),
        )
    }

    @Test
    fun `production and evidence fingerprint allowlists are intentionally empty`() {
        assertTrue(Android15OracleHookPlan.EVIDENCE_ONLY_FINGERPRINTS.isEmpty())
        assertTrue(Android15OracleHookPlan.ATTESTED_FINGERPRINTS.isEmpty())
        assertEquals(
            Android15OracleHookPlan.BuildAdmission.UNLISTED,
            Android15OracleHookPlan.classifyFingerprint("any/real/device:fingerprint"),
        )
        assertFalse(Android15OracleHookPlan.mayInstallEvidenceHooks("any/real/device:fingerprint"))
        assertFalse(Android15OracleHookPlan.isFingerprintAttested("any/real/device:fingerprint"))
    }

    @Test
    fun `fingerprint admission is exact and overlap fails closed`() {
        val evidence = setOf("vendor/product/device:15/evidence:user/release-keys")
        val attested = setOf("vendor/product/device:15/attested:user/release-keys")

        assertEquals(
            Android15OracleHookPlan.BuildAdmission.UNLISTED,
            Android15OracleHookPlan.classifyFingerprint(null, evidence, attested),
        )
        assertEquals(
            Android15OracleHookPlan.BuildAdmission.UNLISTED,
            Android15OracleHookPlan.classifyFingerprint(" vendor/product/device:15/evidence:user/release-keys", evidence, attested),
        )
        assertEquals(
            Android15OracleHookPlan.BuildAdmission.UNLISTED,
            Android15OracleHookPlan.classifyFingerprint(evidence.single().uppercase(), evidence, attested),
        )
        assertEquals(
            Android15OracleHookPlan.BuildAdmission.UNLISTED,
            Android15OracleHookPlan.classifyFingerprint(evidence.single() + "/suffix", evidence, attested),
        )
        assertEquals(
            Android15OracleHookPlan.BuildAdmission.UNLISTED,
            Android15OracleHookPlan.classifyFingerprint("*", evidence, attested),
        )
        assertEquals(
            Android15OracleHookPlan.BuildAdmission.EVIDENCE_ONLY,
            Android15OracleHookPlan.classifyFingerprint(evidence.single(), evidence, attested),
        )
        assertEquals(
            Android15OracleHookPlan.BuildAdmission.ATTESTED,
            Android15OracleHookPlan.classifyFingerprint(attested.single(), evidence, attested),
        )
        assertEquals(
            "one fingerprint in both lists is a configuration conflict, not attestation",
            Android15OracleHookPlan.BuildAdmission.UNLISTED,
            Android15OracleHookPlan.classifyFingerprint(evidence.single(), evidence, evidence),
        )
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

    @Test
    fun `evidence-only truth table exposes failures but can never become healthy`() {
        val evidence = Android15OracleHookPlan.BuildAdmission.EVIDENCE_ONLY
        val runtimeMask = Android15OracleHookPlan.REQUIRED_EVIDENCE_COVERAGE_MASK

        assertEquals(
            OracleWireHealth.EVIDENCE_ONLY_READY,
            Android15OracleHookPlan.classifyHealth(
                true, evidence, true, false, false, runtimeMask, true, true, true,
            ),
        )
        assertEquals(
            OracleWireHealth.CALLBACK_POISONED,
            Android15OracleHookPlan.classifyHealth(
                true, evidence, true, false, true, runtimeMask, true, true, true,
            ),
        )
        assertEquals(
            OracleWireHealth.BOOT_ID_UNAVAILABLE,
            Android15OracleHookPlan.classifyHealth(
                true, evidence, false, false, false, runtimeMask, true, true, true,
            ),
        )
        assertEquals(
            OracleWireHealth.INVARIANT_FAILURE,
            Android15OracleHookPlan.classifyHealth(
                true, evidence, true, true, false, runtimeMask, true, true, true,
            ),
        )
        assertEquals(
            OracleWireHealth.HOOKS_INCOMPLETE,
            Android15OracleHookPlan.classifyHealth(
                true,
                evidence,
                true,
                false,
                false,
                runtimeMask xor Android15OracleHookPlan.COVERAGE_LOCATION_SEMANTIC_COORDINATE,
                true,
                true,
                true,
            ),
        )
        assertEquals(
            OracleWireHealth.BRIDGE_UNAVAILABLE,
            Android15OracleHookPlan.classifyHealth(
                true,
                evidence,
                true,
                false,
                false,
                runtimeMask xor Android15OracleHookPlan.COVERAGE_BRIDGE_SESSION,
                false,
                true,
                true,
            ),
        )
        assertEquals(
            OracleWireHealth.SESSION_UNAVAILABLE,
            Android15OracleHookPlan.classifyHealth(
                true,
                evidence,
                true,
                false,
                false,
                runtimeMask xor Android15OracleHookPlan.COVERAGE_QWY_SERVICE_GENERATION xor
                    Android15OracleHookPlan.COVERAGE_QWY_SEMANTIC_SESSION,
                true,
                false,
                true,
            ),
        )
        assertEquals(
            OracleWireHealth.ENDPOINT_UNAVAILABLE,
            Android15OracleHookPlan.classifyHealth(
                true, evidence, true, false, false, runtimeMask, true, true, false,
            ),
        )
        assertEquals(
            "evidence-only may never carry the attestation bit",
            OracleWireHealth.INVARIANT_FAILURE,
            Android15OracleHookPlan.classifyHealth(
                true,
                evidence,
                true,
                false,
                false,
                Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
                true,
                true,
                true,
            ),
        )
        assertEquals(
            "callback poison cannot hide evidence-only attestation-bit laundering",
            OracleWireHealth.INVARIANT_FAILURE,
            Android15OracleHookPlan.classifyHealth(
                true,
                evidence,
                true,
                false,
                true,
                Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
                true,
                true,
                true,
            ),
        )
        assertFalse(
            Android15OracleHookPlan.classifyHealth(
                true, evidence, true, false, false, runtimeMask, true, true, true,
            ) == OracleWireHealth.HEALTHY,
        )
    }

    @Test
    fun `only complete attested coverage can become healthy`() {
        val attested = Android15OracleHookPlan.BuildAdmission.ATTESTED

        assertEquals(
            OracleWireHealth.HOOKS_INCOMPLETE,
            Android15OracleHookPlan.classifyHealth(
                true,
                attested,
                true,
                false,
                false,
                Android15OracleHookPlan.REQUIRED_EVIDENCE_COVERAGE_MASK,
                true,
                true,
                true,
            ),
        )
        assertEquals(
            "attested health keeps coverage failure ahead of the live bridge signal",
            OracleWireHealth.HOOKS_INCOMPLETE,
            Android15OracleHookPlan.classifyHealth(
                true,
                attested,
                true,
                false,
                false,
                Android15OracleHookPlan.REQUIRED_COVERAGE_MASK xor
                    Android15OracleHookPlan.COVERAGE_BRIDGE_SESSION,
                false,
                true,
                true,
            ),
        )
        assertEquals(
            OracleWireHealth.HEALTHY,
            Android15OracleHookPlan.classifyHealth(
                true,
                attested,
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

package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import name.caiyao.fakegps.hook.oracle.Android15OracleHookPlan
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.oracle.OracleWireHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuthoritativeOracleProductionGuardTest {

    @Test
    fun `empty production fingerprint allowlist keeps every real build unattested`() {
        assertTrue(Android15OracleHookPlan.ATTESTED_FINGERPRINTS.isEmpty())

        val attested = Android15OracleHookPlan.isFingerprintAttested(
            "vendor/product/device:15/production/fingerprint:user/release-keys",
        )
        val health = Android15OracleHookPlan.classifyHealth(
            true,
            attested,
            true,
            false,
            false,
            Android15OracleHookPlan.REQUIRED_COVERAGE_MASK,
            true,
            true,
            true,
        )

        assertFalse(attested)
        assertEquals(OracleWireHealth.BUILD_UNATTESTED, health)
        assertNotEquals(OracleWireHealth.HEALTHY, health)
    }

    @Test
    fun `missing authoritative source clears rather than retains FULL`() {
        assertAuthorityCannotRetainFull(pre = null, post = null)
    }

    @Test
    fun `incomplete authoritative coverage clears rather than retains FULL`() {
        val incomplete = completeSnapshot().copy(
            installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1 xor
                AuthoritativeCoverageMask.BUILD_ATTESTATION,
        )

        assertAuthorityCannotRetainFull(pre = incomplete, post = incomplete)
    }

    @Test
    fun `production observer reads only the process oracle registry`() {
        assertTrue(
            providerRuntimeSource.contains(
                "authoritativeSource = BinderAuthoritativeContinuitySource()",
            ),
        )
        assertTrue(
            binderSource.contains(
                "OracleClientRegistry.process.current() ?: return null",
            ),
        )
        assertTrue(providerRuntimeSource.contains("installSemanticWriters = true"))
        assertTrue(providerRuntimeSource.contains("QwySemanticWriterRuntime.install("))
        assertTrue(providerRuntimeSource.contains("tracker.isAuthoritativeCursorAcknowledged"))
    }

    @Test
    fun `production public AppOps callback remains non authoritative`() {
        val androidSource = productionMonitorSource.substringAfter(
            "internal class AndroidMockLocationOwnerChangeSource",
        )

        assertTrue(
            androidSource.contains(
                "override fun continuityEvidenceCapability(): ContinuityEvidenceCapability =\n" +
                    "        ContinuityEvidenceCapability.INCOMPLETE",
            ),
        )
        assertFalse(androidSource.contains("ContinuityEvidenceCapability.COMPLETE"))
    }

    private fun assertAuthorityCannotRetainFull(
        pre: AuthoritativeContinuitySnapshot?,
        post: AuthoritativeContinuitySnapshot?,
    ) {
        val tracker = ContinuityTracker(InMemoryDurableKv(), FakeMonotonicClock())
        tracker.markContinuityEstablished()
        assertEquals(
            "precondition: exercise loss of an already FULL window",
            ContinuityCoverageV1.FULL.wire,
            tracker.snapshot().coverageWire,
        )

        val reconciled = tracker.reconcileAuthoritativeWindow(
            window = AuthoritativeObservationWindow(
                pre = pre,
                post = post,
                windowStartElapsedRealtimeMs = 1_000L,
            ),
            expectedOwnerPackage = QWY_PACKAGE,
            expectedOwnerUid = QWY_UID,
        )

        assertEquals(ContinuityCoverageV1.NONE.wire, reconciled.coverageWire)
        assertNull(reconciled.continuitySinceElapsedRealtimeMs)
    }

    private fun completeSnapshot() = AuthoritativeContinuitySnapshot(
        protocolVersion = AuthoritativeContinuityProtocol.VERSION,
        bootId = "11111111-2222-3333-4444-555555555555",
        oracleInstanceId = "oracle-production-guard",
        sequence = 2L,
        ownerUid = QWY_UID,
        ownerPackage = QWY_PACKAGE,
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        requiredCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = AuthoritativeOracleHealth.HEALTHY,
        qwySemanticDigest = "semantic-digest",
        lastCompletedQwyMutationId = null,
    )

    private val productionMonitorSource: String by lazy {
        readProductionSource("QwyRelevantChangeMonitor.kt")
    }

    private val providerRuntimeSource: String by lazy {
        readProductionSource("ProviderRuntime.kt")
    }

    private val binderSource: String by lazy {
        readProductionSource("BinderAuthoritativeContinuitySource.kt")
    }

    private fun readProductionSource(fileName: String): String {
        val relative = "src/main/java/name/caiyao/fakegps/integration/v1/$fileName"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("cannot locate $fileName")
    }

    private companion object {
        const val QWY_PACKAGE = "name.caiyao.fakegps"
        const val QWY_UID = 10_321
    }
}

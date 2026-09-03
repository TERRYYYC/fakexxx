package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM wiring guard for the Android-bound production controller.
 *
 * This module intentionally has no Robolectric dependency, so LocationManager
 * cannot be executed in the local unit lane. The behavior policy is exercised
 * in [SystemMockTrustPolicyTest]; this guard pins the remaining production
 * connection that previously replayed `lastApplied` as though it were an OS
 * observation.
 */
class QwyActualReadbackWiringTest {

    private val controllerSource: String by lazy {
        val relative =
            "src/main/java/name/caiyao/fakegps/integration/v1/QwyEnvironmentController.kt"
        sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("cannot locate QwyEnvironmentController.kt")
    }

    private val readerSource: String by lazy {
        readProductionSource("AndroidSystemMockLocationReader.kt")
    }

    private val monitorSource: String by lazy {
        readProductionSource("QwyRelevantChangeMonitor.kt")
    }

    private val scheduleStoreSource: String by lazy {
        readProductionSource("QwyScheduleStore.kt")
    }

    private val serviceSource: String by lazy {
        val relative = "src/main/java/name/caiyao/fakegps/mockprovider/MockProviderService.kt"
        sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("cannot locate MockProviderService.kt")
    }

    @Test
    fun `apply receipt projects actual provider readback rather than desired coordinates`() {
        val body = methodBody("override fun applyEnvironment(")

        assertTrue(body.contains("refreshSession.startOrReconfigure("))
        assertTrue(body.contains("systemMockTrustPolicy?.evaluate("))
        assertTrue(body.contains("effectiveLatitude = readback?.latitude"))
        assertTrue(body.contains("effectiveLongitude = readback?.longitude"))
        assertFalse(body.contains("effectiveLatitude = coords.first"))
        assertFalse(body.contains("effectiveLongitude = coords.second"))
    }

    @Test
    fun `observe cannot replay lastApplied desired coordinates as effective state`() {
        val body = methodBody("override fun observeEffective()")

        assertTrue(body.contains("mockRefreshSession?.refreshNow() == true"))
        assertTrue(body.contains("systemMockTrustPolicy.evaluate("))
        assertTrue(body.contains("latitude = readback?.latitude"))
        assertTrue(body.contains("longitude = readback?.longitude"))
        assertFalse(Regex("""(?:lastApplied|appliedCommand)\.(?:latitude|longitude)""")
            .containsMatchIn(body))
        assertFalse(body.contains("ConfigPrefsSync.readPublished("))
    }

    @Test
    fun `authoritative digest binds actual provider projection not desired coordinates`() {
        val body = methodBody(
            "override fun authoritativeSemanticDigest(ownerGeneration: Long): String?",
        )

        assertTrue(body.contains("systemMockTrustPolicy ?: return null"))
        assertTrue(body.contains("trustPolicy.evaluate("))
        assertTrue(body.contains("effectiveLatitude = actualProjection?.latitude"))
        assertTrue(body.contains("effectiveLongitude = actualProjection?.longitude"))
        assertTrue(body.contains("actualProjection?.fingerprint ?: \"system-mock:inactive\""))
        assertTrue(body.contains("!refreshSession.isProvablyInactive ||"))
        assertFalse(body.contains("effectiveLatitude = applied?.latitude"))
        assertFalse(body.contains("effectiveLongitude = applied?.longitude"))
    }

    @Test
    fun `framework cache readback also checks each required provider is enabled`() {
        assertTrue(readerSource.contains("locationManager.isProviderEnabled(source)"))
    }

    @Test
    fun `service refresh and canonical digest share exact provider projection ownership`() {
        val digest = methodBody(
            "override fun authoritativeSemanticDigest(ownerGeneration: Long): String?",
        )
        assertTrue(digest.contains("ProcessMockProviderOwnership.projectionOwnershipSnapshot()"))
        assertTrue(controllerSource.contains("MockProviderStartupProjectionReconciler("))
        assertTrue(controllerSource.contains("override fun reconcileProjectionOnOwnerStart()"))
        assertTrue(serviceSource.contains("SystemMockTrustPolicy(AndroidSystemMockLocationReader(manager), diagnosticSink = {"))
        assertTrue(serviceSource.contains("AndroidSystemMockDiagnosticLogger.record(SystemMockDiagnosticOrigin.SERVICE, it)"))
        assertTrue(controllerSource.contains("SystemMockTrustPolicy(AndroidSystemMockLocationReader(manager), diagnosticSink = {"))
        assertTrue(controllerSource.contains("AndroidSystemMockDiagnosticLogger.record(SystemMockDiagnosticOrigin.INTEGRATION, it)"))
        assertTrue(serviceSource.contains("projectionMatchesExactly = { config ->"))
        assertTrue(serviceSource.contains("matchesExactTargetProjection"))
    }

    @Test
    fun `production controller binds AppOps owner monitoring into verification`() {
        assertTrue(controllerSource.contains("AndroidMockLocationOwnerChangeSource"))
        assertTrue(controllerSource.contains("relevantChangeMonitor.bind(listener)"))
        assertTrue(
            "apply must require both an active watcher and current AppOps ownership",
            controllerSource.contains("relevantChangeMonitor.canVerifyCurrentOwner() && published"),
        )
        assertTrue(
            "observe must re-check current AppOps ownership",
            controllerSource.contains("val verified = relevantChangeMonitor.canVerifyCurrentOwner() &&"),
        )
        assertTrue(
            "preflight must not promise VERIFIED when this app is not the current owner",
            controllerSource.contains("!relevantChangeMonitor.canVerifyCurrentOwner()"),
        )
    }

    @Test
    fun `public AppOps monitoring is never promoted to complete continuity evidence`() {
        assertTrue(
            controllerSource.contains(
                "relevantChangeMonitor.continuityEvidenceCapability()",
            ),
        )
        val androidSource = monitorSource.substringAfter(
            "internal class AndroidMockLocationOwnerChangeSource",
        )
        assertTrue(
            androidSource.contains("ContinuityEvidenceCapability.INCOMPLETE"),
        )
        assertFalse(androidSource.contains("ContinuityEvidenceCapability.COMPLETE"))
    }

    @Test
    fun `advance convergence rebuilds and verifies projection after pointer convergence`() {
        val body = methodBody("override fun convergeAdvance(")
        assertTrue(body.contains("scheduleStore.convergeAdvance("))
        assertTrue(
            body.contains("convergeAdvancedProjection(expectedToItemId, expectedVersionAfter)"),
        )

        val projection = methodBody("private fun convergeAdvancedProjection(")
        assertTrue(projection.contains("refreshSession.startOrReconfigure("))
        assertTrue(projection.contains("trustPolicy.evaluate("))
        assertTrue(projection.contains("readback.verified"))
        assertFalse(
            "a refresh publisher is not continuity evidence",
            projection.contains("ContinuityEvidenceCapability.COMPLETE"),
        )
    }

    @Test
    fun `schedule target is validated before pointer mutation`() {
        val convergence = scheduleStoreSource.substringAfter("fun convergeAdvance(")
            .substringBefore("fun recordLastApplied(")
        val targetCheck = convergence.indexOf("check(actualNextItemId == expectedToItemId)")
        val mutation = convergence.indexOf("val outcome = advancePointer(fromItemId)")
        assertTrue("target precondition missing", targetCheck >= 0)
        assertTrue("pointer mutation missing", mutation >= 0)
        assertTrue("target must be checked before mutation", targetCheck < mutation)
    }

    @Test
    fun `post advance observe reconstructs a lost process local refresher from durable tuple`() {
        val observe = methodBody("override fun observeEffective()")
        assertTrue(observe.contains("scheduleStore.postAdvanceProjectionFor(schedule)"))
        assertTrue(observe.contains("refreshSession.startOrReconfigure("))
        assertTrue(observe.contains("purpose = ProjectionPurpose.POST_ADVANCE"))
        val rehydrateMutation = braceDelimitedBlock(
            source = observe,
            anchor = "QwySemanticWriterRuntime.mutate(",
        )
        assertTrue(rehydrateMutation.contains("refreshSession.startOrReconfigure("))
        assertTrue(rehydrateMutation.contains("ConfigPrefsSync.sync("))
        assertTrue(rehydrateMutation.contains("scheduleStore.recordLastApplied("))
        assertTrue(rehydrateMutation.contains("appliedCommand = scheduleStore.getLastApplied()"))
        assertFalse(
            "reconstruction heartbeat must not manufacture complete continuity",
            observe.contains("ContinuityEvidenceCapability.COMPLETE"),
        )

        val apply = methodBody("override fun applyEnvironment(")
        assertTrue(
            "the next lease must replace the restartable post-advance marker",
            apply.contains("purpose = ProjectionPurpose.LEASE"),
        )
    }

    private fun readProductionSource(fileName: String): String {
        val relative = "src/main/java/name/caiyao/fakegps/integration/v1/$fileName"
        return sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("cannot locate $fileName")
    }

    private fun methodBody(anchor: String): String {
        val declaration = controllerSource.indexOf(anchor)
        assertTrue("declaration not found: $anchor", declaration >= 0)
        val openBrace = controllerSource.indexOf('{', declaration)
        assertTrue("body not found: $anchor", openBrace >= 0)
        var depth = 0
        for (index in openBrace until controllerSource.length) {
            when (controllerSource[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return controllerSource.substring(openBrace, index + 1)
                    }
                }
            }
        }
        error("unbalanced method body: $anchor")
    }

    private fun braceDelimitedBlock(source: String, anchor: String): String {
        val declaration = source.indexOf(anchor)
        assertTrue("declaration not found: $anchor", declaration >= 0)
        val openBrace = source.indexOf('{', declaration)
        assertTrue("body not found: $anchor", openBrace >= 0)
        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openBrace, index + 1)
                }
            }
        }
        error("unbalanced body: $anchor")
    }
}

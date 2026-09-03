package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemServerOracleWiringGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("cannot locate qianwangyou app module")

    @Test
    fun `MainHook takes exact system-server branch before self-hook policy and returns`() {
        val source = source("java/name/caiyao/fakegps/hook/MainHook.java")
        val branch = source.indexOf("SystemServerOracleEntryPolicy.isSystemServer(lpparam.packageName, lpparam.processName)")
        val normalPolicy = source.indexOf("RuntimeSelfHookPolicy.shouldHook(")

        assertTrue("exact system-server branch must exist", branch >= 0)
        assertTrue("system-server branch must precede generic hook policy", branch < normalPolicy)
        val branchTail = source.substring(branch, normalPolicy)
        assertTrue(branchTail.contains("SystemServerOracleInstaller.install(lpparam.classLoader)"))
        assertTrue(branchTail.contains("return;"))
    }

    @Test
    fun `legacy scope resource contains system and manifest publishes no intent filter registrar`() {
        val scope = source("res/values/xposed_scope.xml")
        val manifest = source("AndroidManifest.xml")

        assertTrue(scope.contains("<item>system</item>"))
        assertTrue(manifest.contains("android:name=\"xposedscope\""))
        assertTrue(manifest.contains("android:resource=\"@array/xposed_scope\""))
        val serviceAt = manifest.indexOf(".oracle.OracleBridgeService")
        assertTrue("OracleBridgeService must be declared", serviceAt >= 0)
        val serviceEnd = manifest.indexOf("/>", serviceAt)
        val declaration = manifest.substring(serviceAt, serviceEnd)
        assertTrue(declaration.contains("android:exported=\"true\""))
        assertTrue(declaration.contains("android:directBootAware=\"true\""))
        assertTrue("registrar must remain in the main process", !declaration.contains("android:process"))
    }

    @Test
    fun `installer binds explicit bridge only from phase 600 callback`() {
        val source = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val policy = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleEntryPolicy.java")

        assertTrue(policy.contains("PHASE_THIRD_PARTY_APPS_CAN_START = 600"))
        assertTrue(source.contains("SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(phase)"))
        assertTrue(source.contains("new ComponentName("))
        assertTrue(source.contains("name.caiyao.fakegps.oracle.OracleBridgeService"))
        assertTrue(source.contains("registerOracle(oracleBinder)"))
    }

    @Test
    fun `API and staged admission gates precede Binder construction and hooks`() {
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val platformGate = installer.indexOf("if (!supportedPlatform)")
        val admissionResolution = installer.indexOf("Android15OracleHookPlan.classifyFingerprint(Build.FINGERPRINT)")
        val unlistedGate = installer.indexOf(
            "if (buildAdmission == Android15OracleHookPlan.BuildAdmission.UNLISTED)",
        )
        val binderConstruction = installer.indexOf(
            "oracleBinder = SystemServerOracleBinder.createForCurrentBuild()",
        )
        val hookInstallation = installer.indexOf("tryInstallMutationGroup(")
        val productionFactory = braceDelimitedBlock(
            binder,
            "static SystemServerOracleBinder createForCurrentBuild()",
        )
        val productionClassifier = braceDelimitedBlock(
            source("java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlan.java"),
            "public static BuildAdmission classifyFingerprint(String fingerprint)",
        )
        val privateConstructor = braceDelimitedBlock(
            binder,
            "private SystemServerOracleBinder(",
        )
        val markInstalled = braceDelimitedBlock(
            binder,
            "void markInstalled(long coverageBit)",
        )
        val exactClassifierDelegation =
            "classifyFingerprint(\n                fingerprint, " +
                "EVIDENCE_ONLY_FINGERPRINTS, ATTESTED_FINGERPRINTS)"
        val healthLocked = braceDelimitedBlock(
            binder,
            "private OracleWireHealth healthLocked()",
        )

        assertTrue("the exact API gate must precede Binder construction",
            platformGate >= 0 && platformGate < binderConstruction)
        assertTrue("exact admission must be resolved before Binder construction",
            admissionResolution >= 0 && admissionResolution < binderConstruction)
        assertTrue("unlisted builds must return before Binder construction",
            unlistedGate >= 0 && unlistedGate < binderConstruction)
        assertTrue("the guarded Binder must exist before any hook can invoke it",
            binderConstruction >= 0 && binderConstruction < hookInstallation)
        assertTrue(installer.contains("SystemServerOracleBinder.createForCurrentBuild();"))
        assertTrue(installer.contains("BuildAdmission.EVIDENCE_ONLY"))
        assertTrue(productionFactory.contains("String buildFingerprint = Build.FINGERPRINT;"))
        assertTrue(
            productionFactory.contains(
                "Build.VERSION.SDK_INT == Android15OracleHookPlan.API_LEVEL",
            ),
        )
        assertTrue(
            "the live factory may pass only its freshly read non-authority identity",
            productionFactory.contains(
                "new SystemServerOracleBinder(\n" +
                    "                readKernelBootId(), buildFingerprint, supportedPlatform)",
            ),
        )
        assertTrue(
            "the private construction boundary must derive admission from the exact fingerprint",
            privateConstructor.contains(
                "this.buildAdmission = " +
                    "Android15OracleHookPlan.classifyFingerprint(buildFingerprint);",
            ),
        )
        assertFalse(
            "no public factory may accept caller-supplied attestation authority",
            productionFactory.substringBefore('{').contains(','),
        )
        assertTrue(
            "there must be exactly one package-private live-build Binder factory",
            binder.windowed("static SystemServerOracleBinder create".length)
                .count { it == "static SystemServerOracleBinder create" } == 1,
        )
        assertFalse(
            "the live-build Binder factory is not a public authority surface",
            binder.contains("public static SystemServerOracleBinder create"),
        )
        assertTrue(
            "the private constructor accepts identity only, never an admission tier",
            binder.contains(
                "private SystemServerOracleBinder(\n" +
                    "            String bootId,\n" +
                    "            String buildFingerprint,\n" +
                    "            boolean supportedPlatform) {",
            ),
        )
        assertFalse(privateConstructor.contains("BuildAdmission buildAdmission"))
        assertTrue(
            "the constructor must mint coverage only through the exhaustive admission helper",
            privateConstructor.contains(
                "installedCoverageMask = Android15OracleHookPlan.initialCoverageMask(" +
                    "this.buildAdmission);",
            ),
        )
        assertEquals(
            "the Binder may mention the attestation bit only in markInstalled's rejection gate",
            1,
            binder.split("Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED").size - 1,
        )
        val rejectAt = markInstalled.indexOf(
            "(coverageBit & Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED) != 0L",
        )
        val writeAt = markInstalled.indexOf("installedCoverageMask |= coverageBit")
        assertTrue(
            "runtime hook coverage must reject the authority bit before the generic write",
            rejectAt >= 0 && rejectAt < writeAt,
        )
        assertEquals(
            "the production classifier must contain one exact-list delegation",
            1,
            productionClassifier.split(exactClassifierDelegation).size - 1,
        )
        assertEquals(
            "the production classifier must have no target-specific authority branch",
            1,
            productionClassifier.windowed("return ".length).count { it == "return " },
        )
        assertFalse(productionClassifier.contains("if ("))
        assertTrue(
            "health must consume the same immutable admission derived at construction",
            healthLocked.contains("supportedPlatform,\n                buildAdmission,"),
        )
        assertTrue(
            "any future admission tier must fail closed before health evaluation",
            source("java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlan.java").contains(
                "if (buildAdmission != BuildAdmission.EVIDENCE_ONLY\n" +
                    "                && buildAdmission != BuildAdmission.ATTESTED) {",
            ),
        )
        assertTrue("endpoint sampling must independently fail closed below its API floor",
            binder.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q"))
        assertTrue(binder.contains("effective AppOps sampling requires API 29 or newer"))
    }

    @Test
    fun `kernel boot id instance id and callback poison are explicit fail-closed inputs`() {
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")

        assertTrue(binder.contains("/proc/sys/kernel/random/boot_id"))
        assertTrue(binder.contains("UUID.randomUUID().toString()"))
        assertTrue(installer.contains("poisonCallback"))
        assertTrue(installer.contains("BUILD_UNATTESTED"))
        assertTrue(
            "a foreign covered mutation must suppress QWY receipt correlation",
            binder.contains("!aggregateForeignChanged"),
        )
    }

    @Test
    fun `QWY framework mutations retain provenance while foreign interleaving stays uncorrelated`() {
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val plan = source("java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlan.java")

        assertTrue(plan.contains("LOCATION_MANAGER_SERVICE_CLASS"))
        assertTrue(plan.contains("LOCATION_QWY_MUTATION_ENTRY_METHODS"))
        assertTrue(installer.contains("COVERED_CALLER_PROVENANCE"))
        assertTrue(installer.contains("Binder.getCallingUid()"))
        assertTrue(installer.contains("Binder.getCallingPid()"))
        assertTrue(installer.contains("stringArgumentFromEnd(param.args, 2)"))
        assertTrue(installer.contains("stringArgumentFromEnd(param.args, 1)"))
        assertTrue(installer.contains("oracleBinder.beginCoveredMutation("))
        assertTrue(binder.contains("expectedQwyPid = registeringPid"))
        assertTrue(binder.contains("QwyCoveredMutationAttributionPolicy.isAttributed("))
        assertTrue(binder.contains("attributedToQwy"))
        assertTrue(binder.contains("hasActiveQwyMutationLocked()"))
        assertTrue(
            "only a covered mutation proven to originate from the active QWY writer may retain its ID",
            binder.contains("mutation.mutationId == null && !mutation.attributedToQwy"),
        )
    }

    @Test
    fun `coordinate history uses provenance only at Binder entry and changes only under provider lock`() {
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val plan = source("java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlan.java")
        val provenanceHook = braceDelimitedBlock(
            installer,
            "private static final class CallerProvenanceHook",
        )
        val coordinateHook = braceDelimitedBlock(
            installer,
            "private static final class SemanticLocationMutationHook",
        )
        val beforeCoordinate = braceDelimitedBlock(
            coordinateHook,
            "protected void beforeHookedMethod(",
        )
        val afterCoordinate = braceDelimitedBlock(
            coordinateHook,
            "protected void afterHookedMethod(",
        )

        assertTrue(plan.contains("LOCATION_QWY_PROVENANCE_ENTRY_METHOD"))
        assertTrue(plan.contains("LOCATION_MOCK_PROVIDER_CLASS"))
        assertTrue(plan.contains("LOCATION_SEMANTIC_MUTATION_METHOD"))
        assertTrue(plan.contains("COVERAGE_LOCATION_SEMANTIC_COORDINATE"))
        assertFalse(
            "the outer Binder entry carries identity but must not journal every A-to-A tick",
            provenanceHook.contains("beginCoveredMutation("),
        )
        assertTrue(beforeCoordinate.contains("XposedHelpers.getObjectField("))
        assertTrue(beforeCoordinate.contains("\"mLocation\""))
        assertTrue(beforeCoordinate.contains("LocationSemanticChangePolicy.hasChanged("))
        assertTrue(beforeCoordinate.contains("oracleBinder.beginCoveredMutation("))
        assertTrue(afterCoordinate.contains("scheduleCoveredMutationFinish("))
        assertFalse(
            "the lock-held callback may enqueue but never sample provider managers inline",
            afterCoordinate.contains("finishCoveredMutation("),
        )
    }

    @Test
    fun `covered mutation samples only after guarded platform callback returns`() {
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val coveredHook = braceDelimitedBlock(
            installer,
            "private static final class CoveredMutationHook",
        )
        val afterHook = braceDelimitedBlock(coveredHook, "protected void afterHookedMethod(")
        val scheduler = braceDelimitedBlock(
            installer,
            "private static void scheduleCoveredMutationFinish(",
        )

        assertTrue(afterHook.contains("scheduleCoveredMutationFinish("))
        assertFalse(
            "a guarded provider callback must not synchronously cross-call provider managers",
            afterHook.contains("finishCoveredMutation("),
        )
        assertTrue(scheduler.contains("COVERED_MUTATION_FINISHER.execute("))
        assertTrue(scheduler.contains("oracleBinder.finishCoveredMutation("))
        assertTrue(
            "a worker failure must retire the token as uncertain instead of leaving odd state",
            scheduler.contains("oracleBinder.abandonCoveredMutation(token, callbackFailure)"),
        )

        val finish = braceDelimitedBlock(
            binder,
            "void finishCoveredMutation(long token, boolean uncertain, Context context)",
        )
        val refreshAt = finish.indexOf("refreshEndpointSerialized(context)")
        val finishAt = finish.indexOf("finishMutationLocked(token, true, uncertain, null)")

        assertTrue(finish.contains("synchronized (endpointRefreshLock)"))
        assertTrue(
            "the final sample must precede publication of the even sequence",
            refreshAt >= 0 && refreshAt < finishAt,
        )
        val refresh = braceDelimitedBlock(
            binder,
            "private void refreshEndpointSerialized(Context context)",
        )
        val sampleAt = refresh.indexOf("EndpointSample sample = sampleEndpoint(context)")
        val stateLockAt = refresh.indexOf("synchronized (lock)")
        assertTrue(
            "framework sampling must finish before the oracle state lock is acquired",
            sampleAt >= 0 && stateLockAt >= 0 && sampleAt < stateLockAt,
        )
        val sample = braceDelimitedBlock(binder, "private EndpointSample sampleEndpoint(")
        assertTrue(sample.contains("locations.isProviderEnabled("))
        assertTrue(sample.contains("appOps.unsafeCheckOpNoThrow("))
        assertFalse(
            "framework manager calls must never run while holding the oracle state lock",
            sample.contains("synchronized (lock)"),
        )
    }

    @Test
    fun `QWY finish drains earlier covered completions before taking oracle state lock`() {
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val finish = braceDelimitedBlock(binder, "public void finishQwySemanticMutation(")
        val barrierAt = finish.indexOf("awaitCoveredMutationFinisherBarrier()")
        val lockAt = finish.indexOf("synchronized (lock)")

        assertTrue(installer.contains("OrderedCoveredMutationFinisher"))
        val finisher = source(
            "java/name/caiyao/fakegps/hook/oracle/OrderedCoveredMutationFinisher.java",
        )
        assertTrue(finisher.contains("barrier.get(barrierTimeout, barrierTimeoutUnit)"))
        assertTrue(finisher.contains("executor.shutdownNow()"))
        assertTrue(
            "the Binder thread must drain earlier callback children before publishing success",
            barrierAt >= 0 && lockAt >= 0 && barrierAt < lockAt,
        )
        val afterHook = braceDelimitedBlock(
            braceDelimitedBlock(installer, "private static final class CoveredMutationHook"),
            "protected void afterHookedMethod(",
        )
        assertFalse(
            "platform callbacks must enqueue and return, never wait for the finisher",
            afterHook.contains("awaitCoveredMutationFinisherBarrier()"),
        )
    }

    @Test
    fun `QWY registration drains queued children and rejects a not-yet-enqueued callback`() {
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val registration = braceDelimitedBlock(binder, "public void registerQwySession(")
        val barrierAt = registration.indexOf("awaitCoveredMutationFinisherBarrier()")
        val lockAt = registration.indexOf("synchronized (lock)")

        assertTrue(
            "registration must drain earlier FIFO work before taking oracle state",
            barrierAt >= 0 && lockAt > barrierAt,
        )
        assertTrue(
            "an active callback that has not enqueued yet must reject this registration boundary",
            registration.contains("hasActiveCoveredMutationLocked()"),
        )
        val coveredGate = braceDelimitedBlock(
            binder,
            "private boolean hasActiveCoveredMutationLocked()",
        )
        assertTrue(coveredGate.contains("mutation.mutationId == null"))
    }

    @Test
    fun `timeout abandonment makes a late covered finish idempotent`() {
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val finish = braceDelimitedBlock(
            binder,
            "void finishCoveredMutation(long token, boolean uncertain, Context context)",
        )
        val abandon = braceDelimitedBlock(
            binder,
            "void abandonCoveredMutation(long token, Throwable failure)",
        )

        assertTrue(binder.contains("discardedCoveredMutationTokens"))
        assertTrue(
            "a worker unwinding after timeout must consume its discard tombstone",
            finish.contains("discardedCoveredMutationTokens.remove(token)"),
        )
        assertTrue(abandon.contains("Mutation mutation = activeMutations.get(token)"))
        assertTrue(
            "finish may win the timeout race before discard reaches the Binder",
            abandon.contains("if (mutation == null) return"),
        )
        assertTrue(abandon.contains("discardedCoveredMutationTokens.add(token)"))
    }

    @Test
    fun `bridge reconnect stays unhealthy until a generation current fresh sample publishes`() {
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val connected = braceDelimitedBlock(
            binder,
            "void onBridgeConnected(Context context, long connectionGeneration)",
        )
        val invalidateAt = connected.indexOf("bridgeConnected = false")
        val endpointLockAt = connected.indexOf("synchronized (endpointRefreshLock)")
        val sampleAt = connected.indexOf("EndpointSample sample = sampleEndpoint(context)")
        val publishAt = connected.indexOf("publishEndpointSampleLocked(sample)")
        val connectedAt = connected.lastIndexOf("bridgeConnected = true")

        assertTrue(
            "reconnect must revoke old health before waiting for an older sampler",
            invalidateAt >= 0 && endpointLockAt > invalidateAt,
        )
        assertTrue(
            "only a freshly sampled endpoint may expose the bridge as connected",
            sampleAt > endpointLockAt && publishAt > sampleAt && connectedAt > publishAt,
        )
        assertTrue(connected.contains("bridgeConnectionGeneration != connectionGeneration"))
        assertTrue(connected.contains("!bridgeConnectionInProgress"))
        assertTrue(installer.contains("BRIDGE_CONNECTION_GENERATION.incrementAndGet()"))
        assertTrue(installer.contains("onBridgeConnected(context, connectionGeneration)"))
        assertTrue(installer.contains("onBridgeBindingDied(connectionGeneration)"))
    }

    @Test
    fun `binding death retires the dead binding and starts a replacement`() {
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val bindingDied = installer.substringAfter("public void onBindingDied(")
            .substringBefore("public void onNullBinding(")

        assertTrue(bindingDied.contains("unbindService(this)"))
        assertTrue(bindingDied.contains("BRIDGE_BIND_STARTED.set(false)"))
        assertTrue(bindingDied.contains("bindBridge(context)"))
    }

    @Test
    fun `release shrinker keeps private AIDL and system hook producer`() {
        val rules = File(moduleRoot, "proguard-rules.pro").readText()

        assertTrue(rules.contains("name.caiyao.fakegps.oracle.**"))
        assertTrue(rules.contains("name.caiyao.fakegps.hook.oracle.**"))
    }

    private fun source(relative: String): String = File(moduleRoot, "src/main/$relative").readText()

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

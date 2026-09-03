package io.github.terryyyc.fakexxx.integration.pr63issue66

import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.environment.TrustPolicy
import com.example.cellrebelauto.model.execution.CellRebelExecution
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import name.caiyao.fakegps.hook.oracle.Android15OracleHookPlan
import name.caiyao.fakegps.oracle.OracleWireHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionFullBoundaryTest {

    @Test
    fun `empty evidence and production lists keep host evidence BUILD_UNATTESTED`() {
        assertTrue(Android15OracleHookPlan.EVIDENCE_ONLY_FINGERPRINTS.isEmpty())
        assertTrue(Android15OracleHookPlan.ATTESTED_FINGERPRINTS.isEmpty())
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
    fun `Auto requires FULL independently on PRE and POST`() {
        val valid = validContext()
        assertEquals(TrustDecision.PASS, TrustPolicy().evaluate(valid))
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(
                valid.copy(preObservation = valid.preObservation.copy(coverage = "NONE")),
            ),
        )
        assertEquals(
            TrustDecision.FAIL,
            TrustPolicy().evaluate(
                valid.copy(postObservation = valid.postObservation.copy(coverage = "NONE")),
            ),
        )
    }

    @Test
    fun `source guard keeps staged admission fail closed and symmetric FULL policy`() {
        val repo = findRepoRoot()
        val plan = repo.resolve(HOOK_PLAN).readText()
        val installer = repo.resolve(INSTALLER).readText()
        val binder = repo.resolve(BINDER).readText()
        val adapter = repo.resolve(ADAPTER).readText()
        val policy = repo.resolve(TRUST_POLICY).readText()

        assertEquals(emptyList<String>(), violations(plan, installer, binder, adapter, policy))

        val mutations = listOf(
            listOf(
                plan.replace(
                    EMPTY_EVIDENCE_ALLOWLIST,
                    "public static final Set<String> EVIDENCE_ONLY_FINGERPRINTS = " +
                        "Collections.singleton(\"unreviewed-evidence-build\");",
                ),
                installer, binder, adapter, policy,
            ),
            listOf(
                plan.replace(
                    EMPTY_ATTESTED_ALLOWLIST,
                    "public static final Set<String> ATTESTED_FINGERPRINTS = " +
                        "Collections.singleton(\"unreviewed-production-build\");",
                ),
                installer, binder, adapter, policy,
            ),
            listOf(
                plan,
                installer.replace(
                    UNLISTED_RETURN,
                    UNLISTED_RETURN.replace("return;", "oracleBinder = null;"),
                ),
                binder, adapter, policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(
                    BINDER_DERIVES_ADMISSION,
                    "Android15OracleHookPlan.BuildAdmission.ATTESTED",
                ),
                adapter, policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(
                    LIVE_BUILD_FINGERPRINT,
                    "String buildFingerprint = \"caller-supplied/fingerprint\";",
                ),
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(
                    LIVE_BINDER_CONSTRUCTION,
                    "new SystemServerOracleBinder(\n" +
                        "                readKernelBootId(), \"forged/fingerprint\", " +
                        "supportedPlatform)",
                ),
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(LIVE_BUILD_SDK, "boolean supportedPlatform = true;"),
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(LIVE_BINDER_FACTORY, "public $LIVE_BINDER_FACTORY"),
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(INITIAL_COVERAGE_ASSIGNMENT, "installedCoverageMask = 0L;"),
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(
                    HEALTH_USES_DERIVED_ADMISSION,
                    "supportedPlatform,\n                " +
                        "Android15OracleHookPlan.BuildAdmission.ATTESTED,",
                ),
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(MARK_INSTALLED_ATTESTATION_REJECTION, ""),
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder.replace(
                    MARK_INSTALLED_GENERIC_WRITE,
                    "$MARK_INSTALLED_GENERIC_WRITE\n" +
                        "            installedCoverageMask |= " +
                        "Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED;",
                ),
                adapter,
                policy,
            ),
            listOf(
                plan.replace(
                    PRODUCTION_CLASSIFIER_DELEGATION,
                    "if (fingerprint.equals(\"unreviewed/target\")) " +
                        "return BuildAdmission.ATTESTED;\n        " +
                        PRODUCTION_CLASSIFIER_DELEGATION,
                ),
                installer,
                binder,
                adapter,
                policy,
            ),
            listOf(
                plan.replace(UNKNOWN_ADMISSION_GUARD, "if (false) {"),
                installer,
                binder,
                adapter,
                policy,
            ),
            listOf(
                plan,
                installer,
                binder,
                adapter.replace(
                    EVIDENCE_COLLAPSE,
                    "OracleWireHealth.EVIDENCE_ONLY_READY -> AuthoritativeOracleHealth.HEALTHY",
                ),
                policy,
            ),
            listOf(plan, installer, binder, adapter, policy.replace(SYMMETRIC_OBSERVATIONS, "listOf(pre)")),
            listOf(plan, installer, binder, adapter, policy.replace(FULL_REQUIREMENT, "if (false) return false")),
        )
        mutations.forEachIndexed { index, source ->
            assertTrue(
                "FULL boundary mutation $index escaped",
                violations(source[0], source[1], source[2], source[3], source[4]).isNotEmpty(),
            )
        }
    }

    private fun violations(
        plan: String,
        installer: String,
        binder: String,
        adapter: String,
        policy: String,
    ): List<String> = buildList {
        if (plan.windowed(EMPTY_EVIDENCE_ALLOWLIST.length)
                .count { it == EMPTY_EVIDENCE_ALLOWLIST } != 1
        ) {
            add("evidence-only fingerprint list is not exactly empty")
        }
        if (plan.windowed(EMPTY_ATTESTED_ALLOWLIST.length)
                .count { it == EMPTY_ATTESTED_ALLOWLIST } != 1
        ) {
            add("production fingerprint allowlist is not exactly empty")
        }
        if (UNLISTED_RETURN !in installer) add("unlisted installer branch must return")
        val unlisted = installer.indexOf(
            "if (buildAdmission == Android15OracleHookPlan.BuildAdmission.UNLISTED)",
        )
        val binderPublication = installer.indexOf(INSTALLER_LIVE_FACTORY_CALL)
        val firstHook = installer.indexOf("tryInstallMutationGroup(")
        if (unlisted < 0 || binderPublication < 0 || firstHook < 0 ||
            unlisted >= binderPublication || binderPublication >= firstHook
        ) add("unlisted admission must return before Binder publication and every hook")
        if (installer.windowed(INSTALLER_LIVE_FACTORY_CALL.length)
                .count { it == INSTALLER_LIVE_FACTORY_CALL } != 1
        ) {
            add("installer must use the sole zero-argument live-build Binder factory")
        }
        val factoryCount = binder.windowed(BINDER_FACTORY_PREFIX.length)
            .count { it == BINDER_FACTORY_PREFIX }
        val factoryAt = binder.indexOf(LIVE_BINDER_FACTORY)
        val constructorAt = binder.indexOf(PRIVATE_BINDER_CONSTRUCTOR, factoryAt.coerceAtLeast(0))
        val factoryBody = if (factoryAt >= 0 && constructorAt > factoryAt) {
            binder.substring(factoryAt, constructorAt)
        } else {
            ""
        }
        if (factoryCount != 1 || LIVE_BINDER_FACTORY !in binder) {
            add("there must be one zero-argument live-build Binder factory")
        }
        if (PUBLIC_BINDER_FACTORY in binder) {
            add("the live-build Binder factory must remain package-private")
        }
        if (LIVE_BUILD_FINGERPRINT !in factoryBody ||
            LIVE_BUILD_SDK !in factoryBody ||
            LIVE_BINDER_CONSTRUCTION !in factoryBody
        ) {
            add("the Binder factory must derive SDK/fingerprint live and pass no authority tier")
        }
        if (PRIVATE_BINDER_CONSTRUCTOR !in binder) {
            add("the identity-only Binder constructor must remain private")
        }
        if (BINDER_DERIVES_ADMISSION !in binder) {
            add("the private constructor must derive admission from its live fingerprint")
        }
        if (INITIAL_COVERAGE_ASSIGNMENT !in binder) {
            add("the Binder must mint initial coverage from the exhaustive admission helper")
        }
        if (HEALTH_USES_DERIVED_ADMISSION !in binder) {
            add("health must consume the immutable admission derived at construction")
        }
        val markAt = binder.indexOf(MARK_INSTALLED_SIGNATURE)
        val markEnd = binder.indexOf("void configureExpectedQwyIdentity", markAt.coerceAtLeast(0))
        val markInstalled = if (markAt >= 0 && markEnd > markAt) {
            binder.substring(markAt, markEnd)
        } else {
            ""
        }
        if (MARK_INSTALLED_ATTESTATION_REJECTION !in markInstalled ||
            MARK_INSTALLED_GENERIC_WRITE !in markInstalled ||
            markInstalled.indexOf(MARK_INSTALLED_ATTESTATION_REJECTION) >=
            markInstalled.indexOf(MARK_INSTALLED_GENERIC_WRITE)
        ) {
            add("runtime coverage must reject the attestation bit before its generic write")
        }
        if (binder.windowed(BINDER_ATTESTATION_BIT.length)
                .count { it == BINDER_ATTESTATION_BIT } != 1
        ) {
            add("the Binder may reference the attestation bit only in markInstalled's rejection")
        }
        val classifierAt = plan.indexOf(PRODUCTION_CLASSIFIER_SIGNATURE)
        val classifierEnd = plan.indexOf("/** Pure seam", classifierAt.coerceAtLeast(0))
        val productionClassifier = if (classifierAt >= 0 && classifierEnd > classifierAt) {
            plan.substring(classifierAt, classifierEnd)
        } else {
            ""
        }
        if (productionClassifier.windowed(PRODUCTION_CLASSIFIER_DELEGATION.length)
                .count { it == PRODUCTION_CLASSIFIER_DELEGATION } != 1 ||
            productionClassifier.windowed("return ".length).count { it == "return " } != 1 ||
            "if (" in productionClassifier
        ) {
            add("production admission must be the sole exact-list delegation")
        }
        if (UNKNOWN_ADMISSION_GUARD !in plan) {
            add("unknown future admission tiers must fail closed")
        }
        if (EVIDENCE_COLLAPSE !in adapter) {
            add("evidence-only wire readiness must collapse to BUILD_UNATTESTED authority")
        }
        if (policy.windowed(SYMMETRIC_OBSERVATIONS.length)
                .count { it == SYMMETRIC_OBSERVATIONS } != 1
        ) add("PRE and POST must share the same predicate")
        if (policy.windowed(FULL_REQUIREMENT.length).count { it == FULL_REQUIREMENT } != 1) {
            add("each observation must require FULL")
        }
    }

    private fun validContext(): CompletionTrustContext {
        val pre = ObservationSnapshot(
            leaseId = "lease",
            acceptedIntentHash = "intent",
            coverage = "FULL",
            verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
            deliveryMode = "SYSTEM_MOCK",
            isMock = true,
            scheduleDecision = "ALLOWED_NOW",
            effectiveLat = 50.4501,
            effectiveLng = 30.5234,
            environmentRevision = 7L,
            environmentFingerprint = "fingerprint",
            observedAtElapsedRealtimeMs = 1_000L,
            observedAtEpochMs = 1_000L,
            continuitySinceElapsedRealtimeMs = 500L,
            evidenceRefs = listOf("qwy:oracle:1"),
        )
        return CompletionTrustContext(
            execution = CellRebelExecution(
                executionId = "execution",
                attemptId = 1L,
                completionEvidenceWire = 1,
                evidencePayloadDigest = "digest",
                startedAt = 2_000L,
                classifiedAt = 13_000L,
                startedAtElapsed = 2_000L,
                runningConfirmedAtElapsed = 2_100L,
                completedAtElapsed = 13_000L,
            ),
            completionEvidenceWire = 1,
            applyReceiptIntentHash = "intent",
            locallyRecomputedIntentHash = "intent",
            applyReceiptLease = "lease",
            preObservation = pre,
            postObservation = pre.copy(
                observedAtElapsedRealtimeMs = 14_000L,
                observedAtEpochMs = 14_000L,
            ),
        )
    }

    private fun findRepoRoot(): Path {
        var candidate = Paths.get("").toAbsolutePath().normalize()
        while (candidate.parent != null) {
            if (candidate.resolve("apps/cellrebel-auto").isDirectory() &&
                candidate.resolve("apps/qianwangyou").isDirectory()
            ) return candidate
            candidate = candidate.parent
        }
        error("could not locate fakexxx repository root")
    }

    private companion object {
        const val HOOK_PLAN =
            "apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlan.java"
        const val INSTALLER =
            "apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java"
        const val BINDER =
            "apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java"
        const val ADAPTER =
            "apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/BinderAuthoritativeContinuitySource.kt"
        const val TRUST_POLICY =
            "apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/environment/TrustPolicy.kt"
        const val EMPTY_EVIDENCE_ALLOWLIST =
            "public static final Set<String> EVIDENCE_ONLY_FINGERPRINTS = Collections.emptySet();"
        const val EMPTY_ATTESTED_ALLOWLIST =
            "public static final Set<String> ATTESTED_FINGERPRINTS = Collections.emptySet();"
        const val UNLISTED_RETURN =
            "if (buildAdmission == Android15OracleHookPlan.BuildAdmission.UNLISTED) {\n" +
                "            // Both exact lists are empty by default. An unlisted build never\n" +
                "            // constructs the Binder and never installs system-server hooks.\n" +
                "            XposedBridge.log(TAG + \": \" + OracleWireHealth.BUILD_UNATTESTED\n" +
                "                    + \" fingerprint=\" + Build.FINGERPRINT);\n" +
                "            return;\n" +
                "        }"
        const val INITIAL_COVERAGE_ASSIGNMENT =
            "installedCoverageMask = Android15OracleHookPlan.initialCoverageMask(" +
                "this.buildAdmission);"
        const val HEALTH_USES_DERIVED_ADMISSION =
            "supportedPlatform,\n                buildAdmission,"
        const val MARK_INSTALLED_SIGNATURE = "void markInstalled(long coverageBit)"
        const val MARK_INSTALLED_ATTESTATION_REJECTION =
            "if ((coverageBit & Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED) != 0L) {\n" +
                "                poisonInvariantLocked();\n" +
                "                return;\n" +
                "            }"
        const val MARK_INSTALLED_GENERIC_WRITE = "installedCoverageMask |= coverageBit;"
        const val BINDER_ATTESTATION_BIT =
            "Android15OracleHookPlan.COVERAGE_BUILD_ATTESTED"
        const val PRODUCTION_CLASSIFIER_SIGNATURE =
            "public static BuildAdmission classifyFingerprint(String fingerprint)"
        const val PRODUCTION_CLASSIFIER_DELEGATION =
            "return classifyFingerprint(\n" +
                "                fingerprint, EVIDENCE_ONLY_FINGERPRINTS, " +
                "ATTESTED_FINGERPRINTS);"
        const val UNKNOWN_ADMISSION_GUARD =
            "if (buildAdmission != BuildAdmission.EVIDENCE_ONLY\n" +
                "                && buildAdmission != BuildAdmission.ATTESTED) {"
        const val INSTALLER_LIVE_FACTORY_CALL =
            "oracleBinder = SystemServerOracleBinder.createForCurrentBuild();"
        const val BINDER_FACTORY_PREFIX = "static SystemServerOracleBinder create"
        const val LIVE_BINDER_FACTORY =
            "static SystemServerOracleBinder createForCurrentBuild()"
        const val PUBLIC_BINDER_FACTORY =
            "public static SystemServerOracleBinder create"
        const val LIVE_BUILD_FINGERPRINT = "String buildFingerprint = Build.FINGERPRINT;"
        const val LIVE_BUILD_SDK =
            "boolean supportedPlatform =\n" +
                "                Build.VERSION.SDK_INT == Android15OracleHookPlan.API_LEVEL;"
        const val BINDER_DERIVES_ADMISSION =
            "this.buildAdmission = " +
                "Android15OracleHookPlan.classifyFingerprint(buildFingerprint);"
        const val LIVE_BINDER_CONSTRUCTION =
            "new SystemServerOracleBinder(\n" +
                "                readKernelBootId(), buildFingerprint, supportedPlatform)"
        const val PRIVATE_BINDER_CONSTRUCTOR =
            "private SystemServerOracleBinder(\n" +
                "            String bootId,\n" +
                "            String buildFingerprint,\n" +
                "            boolean supportedPlatform) {"
        const val EVIDENCE_COLLAPSE =
            "OracleWireHealth.EVIDENCE_ONLY_READY -> AuthoritativeOracleHealth.BUILD_UNATTESTED"
        const val SYMMETRIC_OBSERVATIONS = "listOf(pre, post)"
        const val FULL_REQUIREMENT = "if (obs.coverage != COVERAGE_FULL) return false"
    }
}

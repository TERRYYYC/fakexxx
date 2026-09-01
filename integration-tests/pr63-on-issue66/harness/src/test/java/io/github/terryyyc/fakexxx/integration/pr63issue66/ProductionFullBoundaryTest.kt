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
    fun `empty production allowlist keeps host evidence BUILD_UNATTESTED`() {
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
    fun `source guard keeps unattested return before hooks and symmetric FULL policy`() {
        val repo = findRepoRoot()
        val plan = repo.resolve(HOOK_PLAN).readText()
        val installer = repo.resolve(INSTALLER).readText()
        val policy = repo.resolve(TRUST_POLICY).readText()

        assertEquals(emptyList<String>(), violations(plan, installer, policy))

        val mutations = listOf(
            Triple(
                plan.replace(EMPTY_ALLOWLIST, "Collections.singleton(\"unreviewed-build\")"),
                installer,
                policy,
            ),
            Triple(
                plan,
                installer.replace(UNATTESTED_RETURN, UNATTESTED_RETURN.replace("return;", "oracleBinder = null;")),
                policy,
            ),
            Triple(plan, installer, policy.replace(SYMMETRIC_OBSERVATIONS, "listOf(pre)")),
            Triple(plan, installer, policy.replace(FULL_REQUIREMENT, "if (false) return false")),
        )
        mutations.forEachIndexed { index, (badPlan, badInstaller, badPolicy) ->
            assertTrue(
                "FULL boundary mutation $index escaped",
                violations(badPlan, badInstaller, badPolicy).isNotEmpty(),
            )
        }
    }

    private fun violations(
        plan: String,
        installer: String,
        policy: String,
    ): List<String> = buildList {
        if (plan.windowed(EMPTY_ALLOWLIST.length).count { it == EMPTY_ALLOWLIST } != 1) {
            add("production fingerprint allowlist is not exactly empty")
        }
        if (UNATTESTED_RETURN !in installer) add("unattested installer branch must return")
        val unattested = installer.indexOf("if (!buildAttested) {")
        val binderPublication = installer.indexOf("oracleBinder = SystemServerOracleBinder.create(")
        val firstHook = installer.indexOf("tryInstallMutationGroup(")
        if (unattested < 0 || binderPublication < 0 || firstHook < 0 ||
            unattested >= binderPublication || binderPublication >= firstHook
        ) add("build attestation must precede Binder publication and every hook")
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
        const val TRUST_POLICY =
            "apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/environment/TrustPolicy.kt"
        const val EMPTY_ALLOWLIST =
            "public static final Set<String> ATTESTED_FINGERPRINTS = Collections.emptySet();"
        const val UNATTESTED_RETURN =
            "if (!buildAttested) {\n" +
                "            // Pilot safety gate: empty production allowlist means BUILD_UNATTESTED and no hooks.\n" +
                "            XposedBridge.log(TAG + \": \" + OracleWireHealth.BUILD_UNATTESTED\n" +
                "                    + \" fingerprint=\" + Build.FINGERPRINT);\n" +
                "            return;\n" +
                "        }"
        const val SYMMETRIC_OBSERVATIONS = "listOf(pre, post)"
        const val FULL_REQUIREMENT = "if (obs.coverage != COVERAGE_FULL) return false"
    }
}

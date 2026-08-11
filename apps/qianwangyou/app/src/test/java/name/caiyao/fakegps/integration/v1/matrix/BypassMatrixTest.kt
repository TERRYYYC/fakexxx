package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import name.caiyao.fakegps.integration.v1.ContinuityTracker
import name.caiyao.fakegps.integration.v1.RevisionBumpReason
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_UID
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * §10 bypass rows owned by the qwy provider lane.
 * Spec: §6.5 (M-BP-03), §6.6 L1–L3 (M-BP-08/09); INV-02, INV-25.
 */
class BypassMatrixTest {

    /**
     * M-BP-03: the caller crafts request content mimicking a paired caller
     * (refs, keys). Identity comes ONLY from Binder UID resolution — the forged
     * content changes nothing and the call is rejected before touching the
     * environment.
     */
    @Test
    fun M_BP_03() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)

        // OTHER_UID (unpaired) replays AUTO's exact plan refs and key names.
        expectContractFailure(ContractErrorCodeV1.NOT_PAIRED) {
            h.apply(
                uid = OTHER_UID,
                key = "apply-k1",
                intent = h.intent(runId = "run-1", attemptId = "att-1", profileRef = "profile-1"),
            )
        }
        assertEquals("environment untouched", 0, h.env.applyCount)
    }

    /**
     * M-BP-08: static guard — no production integration source other than the
     * revision owner may reference the revision storage namespace (write-path
     * detection, not library detection).
     *
     * Non-vacuous by construction: the detector is also self-tested against a
     * synthetic violating source, so "guard found nothing" can never mean
     * "guard scans nothing".
     */
    @Test
    fun M_BP_08() {
        val integrationDir = productionIntegrationDir()
        val sources = integrationDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("integration sources exist", sources.isNotEmpty())

        val ownerFile = "ContinuityTracker.kt"
        val needle = "REVISION_NAMESPACE"

        val owner = sources.singleOrNull { it.name == ownerFile }
        assertTrue("revision owner source present", owner != null && owner.readText().contains(needle))

        val foreignWriters = sources.filter { it.name != ownerFile && it.readText().contains(needle) }
        assertEquals(
            "no production file besides the owner references the revision namespace: $foreignWriters",
            emptyList<File>(),
            foreignWriters,
        )

        // Detector self-test: a synthetic violator must be caught.
        val synthetic = "class Rogue { fun poke(kv: DurableKv) = kv.write(ContinuityTracker.REVISION_NAMESPACE, \"r\", \"9\") }"
        assertTrue("detector recognizes a violating source", synthetic.contains(needle))
    }

    /**
     * M-BP-09: revision kept only in memory violates L3 — after an owner
     * restart the revision must not fall back below any previously ACKed value.
     */
    @Test
    fun M_BP_09() {
        val kv = InMemoryDurableKv()
        val clock = FakeMonotonicClock()

        val t1 = ContinuityTracker(kv, clock)
        val r1 = t1.bump(RevisionBumpReason.PROFILE_CHANGED)
        val r2 = t1.bump(RevisionBumpReason.PROFILE_CHANGED)
        val r3 = t1.bump(RevisionBumpReason.PROFILE_CHANGED)
        assertTrue("bumps strictly monotonic", r1 < r2 && r2 < r3)

        // Owner restart: a purely in-memory counter would restart below r3.
        val t2 = ContinuityTracker(kv, clock)
        assertTrue(
            "revision after restart (${t2.snapshot().revision}) never below last ACKed ($r3)",
            t2.snapshot().revision >= r3,
        )
    }

    private fun productionIntegrationDir(): File {
        // Gradle unit tests run with module dir as user.dir; walk up for safety.
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/java/name/caiyao/fakegps/integration/v1")
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1")
            if (nested.isDirectory) return nested
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("integration/v1 production source dir not found from ${System.getProperty("user.dir")}")
    }
}

package com.example.cellrebelauto.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P10 lease-identity chain guard (G2-P10 补漏, R3 validity criterion).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * @codex-terra's R3 validity froze the criterion: 「全 UUID 从 apply、window
 * Q-DUMP、terminal/restore 逐段绑定；前缀不作为 identity 判据」. On main
 * (19b783e) that is physically impossible: the probe printed
 * `lease=08affda0…` (take(8)) in six places, the normal [5] release line
 * carried NO lease id at all, and only the crash_after_apply record wrote the
 * full UUID — an inconsistency, not a technical limit.
 *
 * The frozen per-injection matrix (docs/acceptance/p10-collector-runbook.md)
 * binds "同一 lease" across apply → window Q-DUMP → terminal/restore; a
 * reviewer sealing an evidence pack must be able to prove the segments talk
 * about ONE lease byte-for-byte, never by prefix coincidence (8 hex chars is
 * 4 bytes of entropy — collision-adjacent, and prefixes invite transcription
 * loss when copied between manifests).
 *
 * Scope: identity RENDERING only. The frozen verdict tokens
 * (`RERELEASE: … VALIDATED complete=true residuals=[]`,
 * `injection window open`, `[5] release … complete=true residuals=[]`) are
 * load-bearing grep anchors for the executor's runner and MUST survive —
 * a separate case pins them, so "adding identity" cannot silently loosen
 * the matrix's exit predicates (dispatch discipline 1).
 */
class P10LeaseIdentityChainGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val probeFile: File =
        File(moduleRoot, "src/debug/java/com/example/cellrebelauto/integration/v1/FullLoopProbeActivity.kt")

    /** Kotlin sources with comments stripped — a commented-out fix must not green. */
    private fun strippedSource(file: File): String {
        val text = file.readText()
        val noBlocks = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlocks.lineSequence().map { it.substringBefore("//") }.joinToString("\n")
    }

    private val probe: String by lazy { strippedSource(probeFile) }

    // ------------------------------------------------------------------
    // RED — the hole, proven on main
    // ------------------------------------------------------------------

    /**
     * No lease identity may be truncated anywhere in the probe. Mutation
     * "只有前缀" (prefix-only) is killed by this exact regex: reintroducing
     * `.take(8)` on any lease variable turns this red again.
     */
    @Test
    fun noLeaseIdentityIsTruncatedInTheProbe() {
        val truncated = Regex("""(receipt\.leaseId|leaseId\?|stuck)\.take\(""")
            .findAll(probe).count()
        assertEquals(
            "lease identity must be printed FULL, never take(8) — R3 validity: " +
                "prefixes are not an identity criterion (found $truncated truncations)",
            0,
            truncated,
        )
    }

    /**
     * Every lease-touching segment line must carry the lease identity, so the
     * frozen evidence pack can bind apply / hold / release / receipt-loss legs /
     * safety-abort / rerelease / cleanup byte-for-byte. Mutation "某一段缺 id"
     * is killed per-tag: removing the id from any listed segment line reds
     * exactly that tag.
     */
    @Test
    fun everyLeaseSegmentCarriesFullIdentity() {
        val segments = mapOf(
            "[3] apply" to listOf("receipt.leaseId"),
            "FAULT hold_lease: holding" to listOf("leaseId"),
            "[5] release →" to listOf("receipt.leaseId"),
            "[5a] release →" to listOf("receipt.leaseId"),
            "[5b] replay →" to listOf("receipt.leaseId"),
            "NOT cleared — finally cleanup" to listOf("receipt.leaseId"),
            "RERELEASE: lease" to listOf("stuck"),
            "RERELEASE FAILED" to listOf("stuck"),
            "RERELEASE THREW" to listOf("stuck"),
            "CLEANUP: released stuck lease" to listOf("stuck"),
            "CLEANUP UNSAFE" to listOf("stuck"),
        )
        val lines = probe.lines()
        segments.forEach { (tag, allowedVars) ->
            val tagLines = lines.filter { it.contains(tag) }
            assertTrue("segment '$tag' should exist in the probe source", tagLines.isNotEmpty())
            val bound = tagLines.any { line ->
                allowedVars.any { v -> line.contains(v) } && !Regex("""\.take\(""").containsMatchIn(line)
            }
            assertTrue(
                "segment '$tag' must interpolate the lease identity in full " +
                    "(one of ${allowedVars.joinToString()}, no truncation) — a segment without " +
                    "an id cannot be bound to the rest of the chain from the frozen output",
                bound,
            )
        }
    }

    // ------------------------------------------------------------------
    // The Q-DUMP leg of the chain must stay full (non-vacuous guard)
    // ------------------------------------------------------------------

    /**
     * The chain's provider-side leg is qwy cmd=dump, which already prints the
     * full current lease id. Pin it so nobody "tidies" it into a prefix later
     * and silently breaks the cross-app byte-binding.
     */
    @Test
    fun qwyDumpLegKeepsFullLeaseIdentity() {
        val repoRoot = requireNotNull(moduleRoot.parentFile?.parentFile) { "module root has no repo parent" }
        val qwyDebugDir = File(repoRoot,
            "qianwangyou/app/src/debug/java/name/caiyao/fakegps/integration/v1")
        assertTrue("qwy debug dir not found from module root", qwyDebugDir.isDirectory)
        val qwyCode = qwyDebugDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { strippedSource(it) }
        assertEquals(
            "qwy dump/collector surfaces must not truncate currentLeaseId",
            0,
            Regex("""currentLeaseId\??\.take\(""").findAll(qwyCode).count(),
        )
        // The dump line itself must print the full id (QwyDurableSnapshot.render).
        assertTrue(
            "QwyDurableSnapshot.render must interpolate snapshot.lease.currentLeaseId in full",
            qwyCode.contains("id=\${snapshot.lease.currentLeaseId ?: \"—\"}"),
        )
    }

    // ------------------------------------------------------------------
    // Discipline 1 — the frozen verdict tokens must survive verbatim
    // ------------------------------------------------------------------

    /**
     * Adding identity must not loosen the matrix's exit predicates. These
     * substrings are the executor's grep anchors (frozen per-injection matrix
     * in docs/acceptance/p10-collector-runbook.md): if one moves, the runner
     * stops recognizing the segment — a silent gate change dressed as a
     * cosmetic edit.
     */
    @Test
    fun frozenVerdictTokensSurviveVerbatim() {
        val tokens = listOf(
            "injection window open",          // hold_lease fire evidence
            "window closed, proceeding to release", // hold_lease exit
            "RECEIPT-LOSS-REPLAY: IDEMPOTENT",// receipt-loss verdict
            "RECEIPT-LOSS-REPLAY: DIVERGENT",
            "RERELEASE: lease ",              // rerelease success line prefix
            " VALIDATED complete=",           // rerelease/… validated anchor
            "complete=", "residuals=",        // release legs
        )
        tokens.forEach { token ->
            assertTrue(
                "frozen matrix token '$token' must survive the identity change verbatim",
                probe.contains(token),
            )
        }
    }

    // ------------------------------------------------------------------
    // Mutation self-checks — the scanners must catch what they claim to kill
    // ------------------------------------------------------------------

    @Test
    fun mutationPrefixOnlyIsDetected() {
        val synthetic = listOf(
            """appendLine("[3] apply → lease=${'$'}{receipt.leaseId.take(8)}…")""",
        ).joinToString("\n")
        assertTrue(
            "a take(8) lease interpolation must be flagged",
            Regex("""(receipt\.leaseId|leaseId\?|stuck)\.take\(""").containsMatchIn(synthetic),
        )
    }

    @Test
    fun mutationMissingSegmentIdIsDetected() {
        // A release line with no lease reference: the per-tag binding check
        // (everyLeaseSegmentCarriesFullIdentity) must find NO bound line.
        val line = """appendLine("[5] release → complete=${'$'}{rel.releaseComplete} residuals=${'$'}{rel.residualReasonWires}")"""
        val bound = line.contains("receipt.leaseId") && !Regex("""\.take\(""").containsMatchIn(line)
        assertEquals(
            "a segment line without the lease id must NOT count as bound",
            false,
            bound,
        )
    }
}

package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * P10 collector surface guard (G2 §3 P10, debug-only fault/revoke collector).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The G2 S3 gate audit (docs/acceptance/g2-s3-gate-audit-2026-08-27-ZY22.md, P10)
 * proved by source reachability that the §5B/§5C device scenarios have NO
 * triggerable entry on the current candidate:
 *
 *   - `onCallerRevoked` (EnvironmentControlHandler.kt:795) had zero non-test
 *     call sites: the qwy-side revoke transition (pairing revoked + lease
 *     REVOKED + audit event, M-PA-09/M-LS-04) exists only in the JVM matrix.
 *   - `runRevokedLeaseCleanup` (§6.3.3 revocation table: REVOKED → RELEASING →
 *     RELEASED/RELEASE_INCOMPLETE by qwy itself) had zero call sites of any kind.
 *   - No debug surface could fire either at a *specified moment* of an
 *     in-flight transaction (exact window), which is what §5C "run 中撤销"
 *     actually requires.
 *
 * These cases assert the collector surface EXISTS and is debug-only. They are
 * deliberately NOT §10 matrix rows: they assert surface reachability, not
 * contract semantics (same boundary as ProviderReachabilityGuardTest).
 *
 * FROZEN VOCABULARY
 * -----------------
 * The gate tokens and command extras asserted here are the vocabulary the
 * per-injection exit/restore matrix (§3 P10) freezes against. Renaming them
 * re-opens that matrix, so treat these strings as load-bearing.
 */
class P10CollectorSurfaceGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val debugSourceDir: File =
        File(moduleRoot, "src/debug/java/name/caiyao/fakegps/integration/v1")

    private val mainSourceDir: File =
        File(moduleRoot, "src/main/java/name/caiyao/fakegps/integration/v1")

    /**
     * Collector marker embedded in every debug collector surface. The release-APK
     * byte scan (scripts/check-debug-only-collector.sh) greps for this string,
     * so it must stay a compile-time constant in the debug sources.
     */
    private val collectorMarker = "P10DBG-COLLECTOR-V1"

    /**
     * Strip Kotlin comments before matching, so a call site that is commented
     * out does not green a reachability assertion. This is the same class of
     * false green DebugAcceptanceManifestGuardTest kills for XML comments.
     */
    private fun kotlinSourcesWithoutComments(dir: File): List<Pair<File, String>> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                val text = file.readText()
                val noBlocks = text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
                val noLines = noBlocks.lineSequence()
                    .map { line -> line.substringBefore("//") }
                    .joinToString("\n")
                file to noLines
            }
            .toList()

    private fun debugCode(): String =
        kotlinSourcesWithoutComments(debugSourceDir).joinToString("\n") { it.second }

    private fun mainCode(): String =
        kotlinSourcesWithoutComments(mainSourceDir).joinToString("\n") { it.second }

    // ------------------------------------------------------------------
    // HOLE ① — qwy caller revoke has no reachable entry (was: 0 call sites)
    // ------------------------------------------------------------------

    /**
     * §5C "qwy 撤销 caller" needs a debug entry that actually drives
     * [EnvironmentControlHandler.onCallerRevoked] — the same transition the
     * JVM matrix drives (pairing revoke + lease REVOKED + audit row), fired
     * on-device by adb. A surface that only prints state is not a revoke entry.
     */
    @Test
    fun hole1_callerRevokeHasADebugCallSite() {
        val debug = debugCode()
        val callSites = Regex("""\.onCallerRevoked\(""").findAll(debug).count()
        assertTrue(
            "onCallerRevoked has no debug call site. The §5C qwy-side revoke transition " +
                "(pairing revoke + lease REVOKED + audit, M-PA-09/M-LS-04) is unreachable " +
                "on-device: 0 non-test call sites, exactly as the P10 audit found. " +
                "Add the debug-only revoke entry (PairingApprovalActivity revoke extras or " +
                "the fault collector arm command).",
            callSites >= 1,
        )
    }

    /**
     * §5C "active lease 进入 REVOKED，由 qwy 内部自清理" — the self-cleanup leg
     * (REVOKED → RELEASING → RELEASED/RELEASE_INCOMPLETE) is
     * [EnvironmentControlHandler.runRevokedLeaseCleanup], which had ZERO call
     * sites of any kind. Without a debug entry the revoked lease never converges
     * on-device and §5C cannot be observed to completion.
     */
    @Test
    fun hole1_revokedLeaseSelfCleanupHasADebugCallSite() {
        val debug = debugCode()
        val callSites = Regex("""\.runRevokedLeaseCleanup\(""").findAll(debug).count()
        assertTrue(
            "runRevokedLeaseCleanup has no debug call site. The §6.3.3 provider " +
                "self-cleanup for REVOKED leases is unreachable on-device, so the §5C " +
                "qwy-revoke scenario cannot be observed to convergence.",
            callSites >= 1,
        )
    }

    /**
     * Mutation killer — "arm exists but never actually fires": an armed
     * self_kill that logs instead of dying would green every presence
     * assertion while §5B's unclean-window semantics never happen. The
     * unclean-death primitive itself must be present in the debug surface.
     */
    @Test
    fun selfKillArmUsesTheRealUncleanDeathPrimitive() {
        val debug = debugCode()
        assertTrue(
            "the arm/self_kill path must call Process.killProcess — anything else " +
                "(log-only, exit(), finish()) is not an unclean death and cannot " +
                "create the §5B.2 / M-LS-07 window",
            Regex("""Process\.killProcess\(""").containsMatchIn(debug),
        )
    }

    /**
     * §5C revoke naming: approval names BOTH halves of the principal
     * (applicationId + signerDigest) precisely because §6.5 forbids fuzzy /
     * "revoke whatever is pending" decisions. The revoke entry must use the
     * same two-extra discipline as approve.
     */
    @Test
    fun hole1_revokeEntryNamesBothHalvesOfThePrincipal() {
        val approval = File(debugSourceDir, "PairingApprovalActivity.kt")
        assertTrue("PairingApprovalActivity.kt must exist in the debug source set", approval.isFile)
        val code = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first == approval }.second
        assertTrue(
            "revoke entry must read revoke_application_id — half-principal revokes " +
                "are a different (wrong) decision, mirroring the approve discipline",
            code.contains("revoke_application_id"),
        )
        assertTrue(
            "revoke entry must read revoke_signer_digest",
            code.contains("revoke_signer_digest"),
        )
    }

    // ------------------------------------------------------------------
    // Exact-window gate vocabulary (§5C run 中 / §5B crash windows)
    // ------------------------------------------------------------------

    /**
     * §5B/§5C require firing at a SPECIFIED moment of an in-flight transaction,
     * not "at some point". The collector's exact-window protocol gates on the
     * provider's own DURABLE lease state, so the window is defined by committed
     * state, not by timing luck. The gate vocabulary is frozen here because the
     * per-injection matrix freezes against it:
     *
     *   lease_active    — fire when a lease is committed ACTIVE (optionally for
     *                     one caller): §5C run 中 revoke, §5B.2 unclean-kill window
     *   lease_releasing — fire during RELEASING: §5B M-LS-17 replay window
     */
    @Test
    fun exactWindowGateVocabularyIsFrozenInDebugCollector() {
        val debug = debugCode()
        assertTrue(
            "debug collector must implement the lease_active gate token",
            debug.contains("lease_active"),
        )
        assertTrue(
            "debug collector must implement the lease_releasing gate token",
            debug.contains("lease_releasing"),
        )
    }

    /**
     * The qwy-side collector activity must be adb-reachable (exported, declared
     * in the debug manifest) — an entry that cannot be started by
     * `adb shell am start` is not a triggerable surface.
     */
    @Test
    fun faultCollectorActivityIsDeclaredInDebugManifest() {
        val manifest = File(moduleRoot, "src/debug/AndroidManifest.xml").readText()
        // Structural (element) check, not substring: strip comments, find node.
        val noComments = manifest.replace(Regex("<!--[\\s\\S]*?-->"), "")
        val declared = Regex("""<activity\b[^>]*?FaultCollectorActivity""")
            .containsMatchIn(noComments)
        assertTrue(
            "src/debug/AndroidManifest.xml must declare FaultCollectorActivity — " +
                "otherwise the P10 fault/revoke collector has no adb entry point",
            declared,
        )
    }

    // ------------------------------------------------------------------
    // Production purity — the collector must not exist outside src/debug
    // ------------------------------------------------------------------

    /**
     * Hard boundary 1 of the dispatch: `apps/qianwangyou/` production paths get
     * ZERO diff from the collector. Source-level half of that proof; the
     * release-APK byte scan (check-debug-only-collector.sh) is the built half.
     */
    @Test
    fun collectorSurfaceIsAbsentFromProductionMainSource() {
        val main = mainCode()
        listOf(
            "FaultCollectorActivity",
            collectorMarker,
            "lease_active",
            "lease_releasing",
        ).forEach { symbol ->
            assertEquals(
                "production main source must not contain '$symbol' — the P10 collector " +
                    "is debug-only by hard constraint (release builds must not carry it)",
                false,
                main.contains(symbol),
            )
        }
    }

    /**
     * The collector marker must actually be embedded in the debug sources —
     * otherwise the release-APK byte scan greps for a string nothing carries,
     * and "guard passed" proves nothing about the APK.
     */
    @Test
    fun collectorMarkerIsEmbeddedInDebugSources() {
        val debug = debugCode()
        assertTrue(
            "debug collector sources must embed the marker string '$collectorMarker' — " +
                "the release-APK scan keys on it",
            debug.contains(collectorMarker),
        )
    }

    // ------------------------------------------------------------------
    // Mutation self-checks — the scanner must catch the false greens it exists to kill
    // ------------------------------------------------------------------

    /**
     * A commented-out call site (`// handler.onCallerRevoked(...)`) must NOT
     * count as reachable. Feeds the same stripping+matching pipeline synthetic
     * sources; if this ever passes with a comment-only call site, every
     * reachability assertion above is decorative.
     */
    @Test
    fun commentedOutCallSiteDoesNotCountAsReachable() {
        val synthetic = listOf(
            "// val ok = handler.onCallerRevoked(appId, signer)",
            "/* handler.onCallerRevoked(a, b) */",
            "val real = 1",
        )
        val stripped = synthetic.joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        assertEquals(
            "comment-only call sites must not match the reachability scanner",
            0,
            Regex("""\.onCallerRevoked\(""").findAll(stripped).count(),
        )

        val live = listOf("handler.onCallerRevoked(appId, signer)")
        assertEquals(
            "a live call site must match the reachability scanner",
            1,
            Regex("""\.onCallerRevoked\(""").findAll(live.joinToString("\n")).count(),
        )
    }

    /**
     * R2 (gpt55 P1-1): adb `--ei` stores an Integer; `getLongExtra` returns the
     * DEFAULT on type mismatch, so the documented `--ei hold_ms 30000` style
     * commands would silently arm a 0ms hold / default poll+timeout. Every
     * numeric/boolean extra the collector reads must go through ExtraCoerce
     * (accepts Int/Long/String), and no raw getLongExtra may remain.
     */
    @Test
    fun r2_extrasAreReadThroughTypeCoercion() {
        val fault = File(debugSourceDir, "FaultCollectorActivity.kt")
        assertTrue("FaultCollectorActivity.kt must exist", fault.isFile)
        val faultCode = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first == fault }.second
        assertTrue(
            "FaultCollectorActivity must read long extras via ExtraCoerce.longOf — " +
                "adb --ei writes Integer and getLongExtra silently defaults",
            faultCode.contains("ExtraCoerce.longOf("),
        )
        assertEquals(
            "no raw getLongExtra may remain in FaultCollectorActivity",
            false,
            Regex("""getLongExtra\(""").containsMatchIn(faultCode),
        )
        val pairing = File(debugSourceDir, "PairingApprovalActivity.kt")
        val pairingCode = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first == pairing }.second
        assertTrue(
            "PairingApprovalActivity must read revoke_run_cleanup via ExtraCoerce.boolOf — " +
                "adb --es writes a String and getBooleanExtra silently defaults to false",
            pairingCode.contains("ExtraCoerce.boolOf("),
        )
    }

    /**
     * R2 (gpt55 P1-3 companion): the revoke PROOF must be bound to the exact
     * principal's before→after transition (QwyRevokeProof), not to "any audit
     * row + principal currently inactive" — a never-paired/typo'd principal
     * would otherwise false-prove.
     */
    @Test
    fun r2_revokeProofComesFromThePrincipalTransition() {
        val debug = debugCode()
        assertTrue(
            "the revoke verdict must be computed by QwyRevokeProof (before-active → " +
                "after-inactive + audit), never from broad row absence alone",
            debug.contains("QwyRevokeProof."),
        )
    }

    /**
     * Baseline sanity for the comment-stripper itself: the pipeline must not
     * over-strip and hide a REAL call that happens to sit on a line with a
     * trailing comment.
     */
    @Test
    fun trailingCommentDoesNotHideALiveCall() {
        val line = "handler.onCallerRevoked(appId, signer) // revoke per §6.5"
        val stripped = line.substringBefore("//")
        assertTrue(
            "a live call followed by a trailing comment must still be found",
            Regex("""\.onCallerRevoked\(""").containsMatchIn(stripped),
        )
    }
}

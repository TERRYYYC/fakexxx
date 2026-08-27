package com.example.cellrebelauto.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P10 collector surface guard — CellRebel Auto side (G2 §3 P10).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The G2 S3 gate audit (docs/acceptance/g2-s3-gate-audit-2026-08-27-ZY22.md, P10)
 * proved by source reachability that on the current candidate:
 *
 *   - Auto's provider revoke (`ProviderTrustStore.revoke`, MainViewModel:205 ←
 *     ProviderApprovalScreen onRevoke) is reachable ONLY through the main UI.
 *     There is no command surface a device executor can fire, and no way to
 *     fire it at a specified moment of an in-flight attempt — which is what
 *     §5C "run 进行中撤销" requires (in-flight attempt must enter NORMAL
 *     release/recovery, not qwy's revoked-caller path).
 *   - `FullLoopProbeActivity` only runs the complete happy loop. The
 *     RELEASE_INCOMPLETE mention at its L238 is a COMMENT about what would
 *     happen, not an injection that can cause anything. §5B's fault set
 *     (RELEASE_INCOMPLETE window, Auto checkpoint crash window, release
 *     receipt loss replay) has no deterministic trigger.
 *
 * Surface-reachability assertions, not contract semantics — same boundary as
 * the qwy-side P10CollectorSurfaceGuardTest.
 *
 * FROZEN VOCABULARY — the fault names and gate tokens here are what the
 * per-injection exit/restore matrix freezes against; renaming re-opens it.
 */
class P10CollectorSurfaceGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val debugSourceDir: File =
        File(moduleRoot, "src/debug/java/com/example/cellrebelauto/integration/v1")

    private val mainSourceDir: File =
        File(moduleRoot, "src/main/java/com/example/cellrebelauto")

    private val collectorMarker = "P10DBG-COLLECTOR-V1"

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

    // ------------------------------------------------------------------
    // HOLE ② — Auto provider revoke is main-UI-only
    // ------------------------------------------------------------------

    /**
     * §5C "Auto 撤销 provider" needs a command surface that drives the REAL
     * [com.example.cellrebelauto.environment.ProviderTrustStore] over the same
     * Room store the UI writes — not a UI-free copy that drifts from it. The
     * assertion requires BOTH the store construction and the revoke call in
     * the debug source set, so a surface that prints pairing state without a
     * real revoke path stays red.
     */
    @Test
    fun hole2_providerRevokeHasADebugCommandSurface() {
        val surfaces = kotlinSourcesWithoutComments(debugSourceDir).filter { (file, code) ->
            file.name != "P10CollectorSurfaceGuardTest.kt" &&
                code.contains("ProviderTrustStore(") &&
                Regex("""\.revoke\(""").containsMatchIn(code)
        }
        assertTrue(
            "no debug surface constructs ProviderTrustStore and calls revoke(). " +
                "Auto's provider revoke is reachable only through ProviderApprovalScreen " +
                "(MainViewModel.revokeProvider) — there is no adb-fireable command for " +
                "§5C, and no way to schedule one at an in-flight moment.",
            surfaces.isNotEmpty(),
        )
    }

    /**
     * §5C run 进行中撤销 requires an EXACT-WINDOW surface: the revoke must be
     * fireable while a real attempt is durably in a specified state. The gate
     * vocabulary (evaluated against Auto's own durable Room state):
     *
     *   run_active           — a test_attempts row is 'starting'/'running'
     *   attempt_state:<S>    — a running attempt's durable aplusState == <S>
     *                          (§8.1 state names, e.g. ENV_APPLIED, RELEASE_PENDING)
     *   trusted_count:<N>    — trusted_quota_entries total has reached N
     */
    @Test
    fun hole2_exactWindowGateVocabularyIsFrozenInDebugCollector() {
        val debug = debugCode()
        assertTrue(
            "debug collector must implement the run_active gate token",
            debug.contains("run_active"),
        )
        assertTrue(
            "debug collector must implement the attempt_state:<STATE> gate prefix",
            debug.contains("attempt_state:"),
        )
        assertTrue(
            "debug collector must implement the trusted_count:<N> gate prefix",
            debug.contains("trusted_count:"),
        )
    }

    /**
     * The Auto-side collector must be adb-reachable: declared in the debug
     * manifest. Without the declaration the surface compiles and is still
     * untriggerable — the exact shape of false green this guard exists for.
     */
    @Test
    fun revokeCollectorActivityIsDeclaredInDebugManifest() {
        val manifest = File(moduleRoot, "src/debug/AndroidManifest.xml").readText()
        val noComments = manifest.replace(Regex("<!--[\\s\\S]*?-->"), "")
        assertTrue(
            "src/debug/AndroidManifest.xml must declare ProviderRevokeCollectorActivity",
            Regex("""<activity\b[^>]*?ProviderRevokeCollectorActivity""")
                .containsMatchIn(noComments),
        )
    }

    // ------------------------------------------------------------------
    // HOLE ③ — FullLoopProbeActivity cannot inject any fault
    // ------------------------------------------------------------------

    /**
     * §5B's Auto-side fault set needs deterministic probe-level fault modes.
     * The three frozen names (the matrix freezes against them):
     *
     *   hold_lease          — apply + observe, then HOLD the lease for N ms
     *                         before releasing: creates a stable, durably
     *                         verifiable ACTIVE window for qwy-side
     *                         arm_kill/arm_revoke injections.
     *   release_receipt_loss — release validates, the receipt is DISCARDED,
     *                         then release is REPLAYED with the same
     *                         idempotency keys: §5B "release receipt 丢失后
     *                         同键重放不产生第二个 lease/cleanup".
     *   crash_after_apply   — apply + observe, then the process kills itself
     *                         with the lease left ACTIVE: the Auto checkpoint
     *                         crash window (trusted ledger must not re-count).
     */
    @Test
    fun hole3_fullLoopProbeHasDeterministicFaultModes() {
        val probe = File(debugSourceDir, "FullLoopProbeActivity.kt")
        assertTrue("FullLoopProbeActivity.kt must exist in the debug source set", probe.isFile)
        val code = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first == probe }.second
        // Assert the BRANCH, not the bare name: a usage string that lists the
        // fault names would green a name-only assertion while nothing ever
        // fires (mutation "entry exists but never triggers").
        listOf("hold_lease", "release_receipt_loss", "crash_after_apply").forEach { fault ->
            assertTrue(
                "FullLoopProbeActivity must BRANCH on fault == \"$fault\" — " +
                    "currently the probe can only run the complete happy loop, so §5B " +
                    "has no deterministic Auto-side trigger",
                code.contains("""fault == "$fault""""),
            )
        }
    }

    /**
     * Mutation killer — "arm/crash exists but never actually fires": logging a
     * crash is not crashing. Both the armed self_kill and the crash_after_apply
     * probe mode must use the real unclean-death primitive.
     */
    @Test
    fun selfKillAndCrashModesUseTheRealUncleanDeathPrimitive() {
        val debug = debugCode()
        assertTrue(
            "the arm/self_kill and crash_after_apply paths must call Process.killProcess — " +
                "anything else is not an unclean death and cannot create the §5B " +
                "checkpoint-crash window",
            Regex("""Process\.killProcess\(""").containsMatchIn(debug),
        )
    }

    // ------------------------------------------------------------------
    // Production purity
    // ------------------------------------------------------------------

    @Test
    fun collectorSurfaceIsAbsentFromProductionMainSource() {
        val main = kotlinSourcesWithoutComments(mainSourceDir).joinToString("\n") { it.second }
        listOf(
            "ProviderRevokeCollectorActivity",
            collectorMarker,
            "run_active",
            "attempt_state:",
            "trusted_count:",
            "hold_lease",
            "release_receipt_loss",
            "crash_after_apply",
        ).forEach { symbol ->
            assertEquals(
                "production main source must not contain '$symbol' — the P10 collector " +
                    "is debug-only by hard constraint",
                false,
                main.contains(symbol),
            )
        }
    }

    @Test
    fun collectorMarkerIsEmbeddedInDebugSources() {
        assertTrue(
            "debug collector sources must embed the marker string '$collectorMarker' — " +
                "the release-APK scan keys on it",
            debugCode().contains(collectorMarker),
        )
    }
}

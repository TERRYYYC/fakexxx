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
     * §8.4 EXPIRED (M-LS-12) needs a DETERMINISTIC clean-shutdown marker: the
     * only production writer is EnvironmentControlService.onDestroy, which is
     * not guaranteed on force-stop. The collector must expose a command that
     * records the marker on a live runtime AND actually drive the production
     * writer — a log-only stub would green a presence check while the clean→
     * EXPIRED branch stays unreachable.
     */
    @Test
    fun markCleanShutdownIsReachableAndDrivesTheProductionWriter() {
        val fault = File(debugSourceDir, "FaultCollectorActivity.kt")
        assertTrue("FaultCollectorActivity.kt must exist", fault.isFile)
        val code = kotlinSourcesWithoutComments(debugSourceDir).first { it.first == fault }.second
        assertTrue(
            "collector must freeze the mark_clean_shutdown command token",
            code.contains("\"mark_clean_shutdown\""),
        )
        assertTrue(
            "mark_clean_shutdown must call the production ProviderRuntime.recordCleanShutdown() — " +
                "a log-only stub cannot set the §8.4 EXPIRED precondition",
            code.contains("ProviderRuntime.recordCleanShutdown("),
        )
    }

    /**
     * §5A 10-address seed must be adb-reachable AND use the explicit-id seeder.
     * MockProviderAcceptanceActivity is the shell-only seam; prepare_10a must
     * route through [APlus10AFixtureSeed] (whose EXPLICIT ids defeat the
     * autoGenerate/deleteAll drift that would silently misbind profile-N).
     */
    @Test
    fun prepare10aSeedIsReachableAndUsesTheExplicitIdSeeder() {
        val seam = File(
            moduleRoot,
            "src/debug/java/name/caiyao/fakegps/mockprovider/MockProviderAcceptanceActivity.kt",
        )
        assertTrue("MockProviderAcceptanceActivity.kt must exist in the debug seam", seam.isFile)
        val seamCode = seam.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lineSequence().map { it.substringBefore("//") }.joinToString("\n")
        assertTrue("seam must freeze the prepare_10a command", seamCode.contains("prepare_10a"))
        assertTrue(
            "prepare_10a must route through APlus10AFixtureSeed (explicit-id seeder)",
            seamCode.contains("APlus10AFixtureSeed."),
        )
        val seeder = File(
            moduleRoot,
            "src/debug/java/name/caiyao/fakegps/mockprovider/APlus10AFixtureSeed.kt",
        )
        assertTrue("APlus10AFixtureSeed.kt must exist in the debug source set", seeder.isFile)
    }

    /**
     * PR #62 P1-3 (R3) + P1-2 (R4) — seed lifecycle honesty. Coupled false-greens:
     *
     *  (a) MONOTONIC generation via APlus10AScheduleReset.plan + readback,
     *      never wholesale clear() (R3: a clear re-Initializes at version 1).
     *  (a2) OWNER QUIESCENCE brackets the write before AND after (R4: a
     *      concurrent owner reinit/advance would otherwise reuse the version).
     *  (a3) PRIOR STATE is CLASSIFIED so a partial store fails closed, never
     *      laundered into V=1 (R4).
     *  (b) the publish outcome (ConfigPrefsSync.sync) is load-bearing; its
     *      boolean must gate the seed outcome, not be dropped.
     *  (c) READY must be success-gated: the runbook predicate greps READY, so
     *      an unconditional READY makes every failure a false green. The
     *      failure path must emit SEED_FAILED instead.
     */
    @Test
    fun p13_p12_seedLifecycleIsMonotonicQuiescentAndReadyGated() {
        val seam = File(
            moduleRoot,
            "src/debug/java/name/caiyao/fakegps/mockprovider/MockProviderAcceptanceActivity.kt",
        )
        assertTrue("MockProviderAcceptanceActivity.kt must exist", seam.isFile)
        val code = seam.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lineSequence().map { it.substringBefore("//") }.joinToString("\n")

        // (a) MONOTONIC generation reset (R3 P1-2: wholesale clear() caused a
        // version-1 rollback on the next boot; M-AD-24 / spec L1895-2056
        // require V → V+1 on every reinit). The seed must go through the
        // pure plan + atomic write + readback verification — never clear().
        assertTrue(
            "seed must compute the monotonic generation via APlus10AScheduleReset.plan",
            code.contains("APlus10AScheduleReset.plan("),
        )
        assertTrue(
            "seed must verify the written generation via APlus10AScheduleReset.verifyReadback",
            code.contains("APlus10AScheduleReset.verifyReadback("),
        )
        assertEquals(
            "the seed path must NOT wholesale-clear the schedule store (version rollback)",
            false,
            Regex("""edit\(\)\.clear\(\)""").containsMatchIn(code),
        )
        // (a2) R4 P1-2: owner-quiescence must bracket the write (before AND
        // after) so a concurrent owner reinit/advance cannot reuse the version.
        assertTrue(
            "quiescence must be checked BEFORE the write",
            code.contains("quiescenceOrThrow(\"before write\")"),
        )
        assertTrue(
            "quiescence must be checked AFTER the write (a fence that went live mid-seed is stale)",
            code.contains("quiescenceOrThrow(\"after write\")"),
        )
        // R5 P1: the bracket must extend over the WHOLE seed (reset + profile
        // rewrite + publish) and close on the owner's DURABLE witnesses — the
        // audit seq a fenced mutation cannot avoid bumping, plus a final
        // schedule re-verify. Observational timing alone cannot close TOCTOU.
        assertTrue(
            "an end-of-seed quiescence bracket must exist",
            code.contains("quiescenceOrThrow(\"end of seed\")"),
        )
        assertTrue(
            "the audit-seq witness must be compared across the whole seed",
            code.contains("auditSeqEnd == auditSeqBefore"),
        )
        assertTrue(
            "the durable ADVANCE_PENDING slot must be consulted (a committed advance replays onto a fresh seed)",
            code.contains("advancePendingPresent = snap.advancePendingRaw != null"),
        )
        assertTrue(
            "owner liveness must be three-state (null = unknown fails closed)",
            code.contains("ownerServiceLiveness(): Boolean?") || code.contains("val ownerRunning: Boolean?"),
        )
        assertTrue(
            "quiescence must consult APlus10AScheduleReset.quiescenceMismatch",
            code.contains("APlus10AScheduleReset.quiescenceMismatch("),
        )
        // (a3) R4 P1-2: prior state must be CLASSIFIED (partial fail-closed),
        // never a raw getLong(..., 0L) default that launders a partial store.
        assertTrue(
            "the seed must classify the prior state via APlus10AScheduleReset.classifyPriorState",
            code.contains("APlus10AScheduleReset.classifyPriorState("),
        )
        // Owner-service FQCN drift guard: the reset object's literal must match
        // the production service class name (else quiescence checks a ghost).
        val serviceManifest = File(moduleRoot, "src/main/AndroidManifest.xml").readText()
        val resetSourceForFqcn = File(
            moduleRoot,
            "src/debug/java/name/caiyao/fakegps/mockprovider/APlus10AScheduleReset.kt",
        ).readText()
        assertTrue(
            "OWNER_SERVICE_FQCN must reference the real EnvironmentControlService",
            resetSourceForFqcn.contains("name.caiyao.fakegps.integration.v1.EnvironmentControlService") &&
                serviceManifest.contains(".integration.v1.EnvironmentControlService"),
        )
        // Literal drift guard: every duplicated prefs key in the reset object
        // must still exist verbatim in QwyScheduleStore — if production moves
        // the store or renames a key, the reset silently writes the wrong file.
        val storeSource = File(
            moduleRoot,
            "src/main/java/name/caiyao/fakegps/integration/v1/QwyScheduleStore.kt",
        ).readText()
        val resetSource = File(
            moduleRoot,
            "src/debug/java/name/caiyao/fakegps/mockprovider/APlus10AScheduleReset.kt",
        ).readText()
        listOf(
            "qwy_schedule_v1", "scheduleId", "scheduleVersion", "currentItemId",
            "itemIds", "exhausted", "advanceCount",
            "lastAppliedLat", "lastAppliedLng", "lastAppliedAtMs", "lastAppliedVerified",
        ).forEach { literal ->
            assertTrue(
                "reset object must carry the literal \"$literal\"",
                resetSource.contains("\"$literal\""),
            )
            assertTrue(
                "duplicated literal \"$literal\" must still match QwyScheduleStore — production moved/renamed it",
                storeSource.contains("\"$literal\""),
            )
        }

        // (b) sync outcome is checked, not dropped.
        assertTrue(
            "ConfigPrefsSync.sync's boolean must gate the seed outcome",
            Regex("""val published = ConfigPrefsSync\.sync""").containsMatchIn(code) &&
                code.contains("check(published)"),
        )

        // (c) R4 P1-1 / gap⑦: prepare_10a must NOT emit the full-seed-PASS
        // "READY" marker (a complete §3 seed PASS). The §3 contract's ordered
        // discover() readback has no executable command today, so a READY here
        // is the false green opus5 ruled blocks merge. The success path emits
        // the honest split markers instead; the failure path emits SEED_FAILED.
        assertEquals(
            "prepare_10a must NOT call complete() (which emits the full-seed-PASS READY)",
            false,
            Regex("""complete\(COMMAND_PREPARE_10A\)""").containsMatchIn(code),
        )
        assertTrue(
            "success path must emit SEED_LOCAL_VERIFIED (local legs) …",
            code.contains("SEED_LOCAL_VERIFIED command="),
        )
        assertTrue(
            "… AND SEED_CONTRACT_INCOMPLETE naming gap⑦ (ordered-readback unavailable)",
            code.contains("SEED_CONTRACT_INCOMPLETE command=") && code.contains("gap=7"),
        )
        assertTrue(
            "the failure path must emit SEED_FAILED (and no success marker)",
            code.contains("SEED_FAILED command="),
        )
        assertTrue(
            "success/failure must be branched (fold/onSuccess+onFailure), not linear",
            code.contains("onSuccess") && code.contains("onFailure"),
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
            "mark_clean_shutdown",
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

package com.example.cellrebelauto.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // R5 P2: cmd=state must resolve running attempts to their durable plan
        // so a start verdict is plan-bound (a global count can't tell plan X
        // from a stale plan Y run).
        assertTrue(
            "cmd=state must bind each running attempt to its planId (getTaskById → planId)",
            debug.contains("getTaskById(") && Regex("""planId=\$\{?""").containsMatchIn(debug),
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

    /**
     * R2 (gpt55 P1-1): numeric extras must be read through ExtraCoerce —
     * adb `--ei` stores Integer and getLongExtra silently returns the default.
     */
    @Test
    fun r2_extrasAreReadThroughTypeCoercion() {
        val files = kotlinSourcesWithoutComments(debugSourceDir)
        val revokeCollector = files.first { it.first.name == "ProviderRevokeCollectorActivity.kt" }.second
        val probe = files.first { it.first.name == "FullLoopProbeActivity.kt" }.second
        assertTrue(
            "ProviderRevokeCollectorActivity must read long extras via ExtraCoerce.longOf",
            revokeCollector.contains("ExtraCoerce.longOf("),
        )
        assertEquals(
            "no raw getLongExtra may remain in ProviderRevokeCollectorActivity",
            false,
            Regex("""getLongExtra\(""").containsMatchIn(revokeCollector),
        )
        assertTrue(
            "FullLoopProbeActivity must read hold_ms via ExtraCoerce.longOf — the runbook " +
                "example --el hold_ms 30000 is canonical; an --ei (Integer) " +
                "typo must still coerce, not silently default",
            probe.contains("ExtraCoerce.longOf("),
        )
    }

    /**
     * R2 (gpt55 P1-2): the revoke readback must prove the EXACT principal's
     * transition — ProviderTrustStore.revoke's boolean return (row actually
     * flipped) + activeFor(appId, signer) after. Broad byApplicationId rows
     * must never carry the verdict.
     */
    @Test
    fun r2_revokeReadbackProvesOnlyTheExactPrincipalTransition() {
        val revokeCollector = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first.name == "ProviderRevokeCollectorActivity.kt" }.second
        assertTrue(
            "the revoke verdict must be computed by RevokeReadback over the store's " +
                "boolean return + exact-principal activeFor query",
            revokeCollector.contains("RevokeReadback."),
        )
        assertTrue(
            "the revoke() boolean return must be captured for the proof",
            Regex("""val revoked = runBlocking|\.revoke\(""").containsMatchIn(revokeCollector),
        )
    }

    // ------------------------------------------------------------------
    // GAP① — Auto had no adb-reachable §5A seed/run (shared A/B/C root cause)
    // ------------------------------------------------------------------

    /**
     * §5A needs Auto to seed a plan and start a run from a device shell, but a
     * plan is created only by the file-picker importCsv and a run only by the
     * `exported=false` accessibility service. The debug seed surface must exist
     * and freeze the two command tokens the runbook drives.
     */
    @Test
    fun gap1_seedRunSurfaceExistsAndVocabularyIsFrozen() {
        val seed = File(debugSourceDir, "APlusSeedActivity.kt")
        assertTrue("APlusSeedActivity.kt must exist in the debug source set", seed.isFile)
        val code = kotlinSourcesWithoutComments(debugSourceDir).first { it.first == seed }.second
        assertTrue("seed surface must freeze the seed_plan command", code.contains("\"seed_plan\""))
        assertTrue("seed surface must freeze the start_run command", code.contains("\"start_run\""))
    }

    /**
     * start_run must drive the PRODUCT's own run entry
     * ([AutomationService.startAutomation]) — not a debug reimplementation that
     * could diverge from how a real run begins. A surface that starts a run some
     * other way would prove nothing about the product path.
     */
    @Test
    fun gap1_startRunDrivesTheProductRunEntry() {
        val code = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first.name == "APlusSeedActivity.kt" }.second
        assertTrue(
            "start_run must call AutomationService.startAutomation — the same entry the UI uses",
            code.contains("AutomationService.startAutomation("),
        )
    }

    /**
     * R6 P1-2 → R7 P1-2 (Sol): the start verdict must bind to a REQUEST-OWNED
     * durable transition. The pure verdict logic is behaviorally tested in
     * [APlus10APlanSeedTest]; this guard is WIRING-SENSITIVE — it pins the
     * actual arguments the shipped activity feeds the verdict (Sol P2-3: the
     * old guard only checked a token was present, so a mis-wired call could
     * pass). It requires: MAX(id) fence (not getLatest, which orders by
     * startedAt and clock skew can hide a newer id), an `id > ?` cardinality
     * query, the durable first-attempt milestone leg, single-flight, the
     * atomic START_RECEIPT, and the typed vocabulary — including the
     * degenerate/awaiting failures that keep a zero-attempt session out of
     * RUN_STARTED.
     */
    @Test
    fun gap1_startRunVerdictIsRequestOwnedGeneration() {
        val code = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first.name == "APlusSeedActivity.kt" }.second
        // Pre-max fence via MAX(id), NOT getLatest (startedAt ordering).
        assertTrue(
            "start_run must fence pre-max via MAX(id), not startedAt-ordered getLatest",
            Regex("""preMaxSessionId[\s\S]{0,160}MAX\(id\)[\s\S]{0,120}run_sessions""").containsMatchIn(code),
        )
        assertFalse(
            "start_run must NOT use getLatest for the pre-max fence (clock skew hides a newer id)",
            Regex("""preMaxSessionId[\s\S]{0,160}getLatest""").containsMatchIn(code),
        )
        // ALL new rows read via id > ? (cardinality), fed as newSessions.
        assertTrue(
            "start_run must read ALL sessions with id > pre-max (cardinality query)",
            Regex("""id\s*>\s*\?[\s\S]{0,80}run_sessions|run_sessions[\s\S]{0,80}id\s*>\s*\?""").containsMatchIn(code),
        )
        assertTrue(
            "the verdict must consume the newSessions list argument",
            Regex("""startRunVerdict\([\s\S]{0,200}newSessions\s*=""").containsMatchIn(code),
        )
        // Durable first-attempt milestone leg, bound to the new session.
        assertTrue(
            "start_run must resolve the durable first-attempt milestone (test_attempts by runSessionId)",
            Regex("""firstAttemptId[\s\S]{0,160}test_attempts[\s\S]{0,80}runSessionId""").containsMatchIn(code),
        )
        assertTrue(
            "the verdict must be fed the firstAttemptId milestone argument",
            Regex("""startRunVerdict\([\s\S]{0,240}firstAttemptId\s*=""").containsMatchIn(code),
        )
        // Single-flight owner token across the whole entry.
        assertTrue(
            "start_run must hold the single-flight lock across receipt+start+poll",
            code.contains("synchronized(START_RUN_LOCK)"),
        )
        // R8 P1-2 (Option D): the prechecks are fast-path REFUSALS only; the
        // receipt is OBSERVED after the product call (r8_* guards below).
        for (precheck in listOf("START_PRECHECK not_connected", "START_PRECHECK already_running")) {
            assertTrue("start_run must keep the fast-path refusal '$precheck'", code.contains(precheck))
        }
        for (receipt in listOf("START_RECEIPT accepted_observed", "START_RECEIPT rejected_already_running", "START_RECEIPT indeterminate")) {
            assertTrue("start_run must emit the observed receipt '$receipt'", code.contains(receipt))
        }
        assertEquals(
            "the pre-call predicted receipt 'START_RECEIPT accepted' must be gone — R8: printed before the " +
                "call it was a prediction, not an observation",
            false,
            code.contains("\"START_RECEIPT accepted\""),
        )
        // Typed verdict vocabulary — including R7 degenerate/awaiting failures.
        for (token in listOf("RUN_STARTED", "RUN_START_CONFLICT", "RUN_START_DEGENERATE", "RUN_NOT_STARTED")) {
            assertTrue("start_run must emit the typed token $token", code.contains(token))
        }
        assertEquals(
            "the pre-R6 untyped acceptance token must be gone",
            false,
            code.contains("REQUEST_ACCEPTED"),
        )
        val collector = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first.name == "ProviderRevokeCollectorActivity.kt" }.second
        // R7 P2: state() must be ONE transactional snapshot with per-row status.
        assertTrue(
            "cmd=state must build its readback in a single withTransaction snapshot",
            collector.contains("withTransaction"),
        )
        assertTrue(
            "cmd=state must print per-row session status",
            collector.contains("sessionStatus="),
        )
        assertTrue(
            "cmd=state must print per-row attempt status",
            collector.contains("attemptStatus="),
        )
        assertTrue(
            "cmd=state must print runSessionId per running attempt (session leg of the binding)",
            collector.contains("runSessionId=\${a.runSessionId}"),
        )
        assertTrue(
            "cmd=state must flag task-plan vs session-plan divergence via planBindingMismatch",
            collector.contains("APlus10APlanSeed.planBindingMismatch("),
        )
    }

    /**
     * KB-8 (PR #62 review P1-1) — canonical spec v1.62 freezes coordinate
     * ownership with the provider: Auto does not import/hold/assert
     * coordinates (§2.2; KB-8 permanent limit). The plan seeder must
     * therefore consume ONLY {order, journeyCaseId, requiredSuccesses} and
     * must never copy fixture coordinates into the legacy LocationTask
     * columns — importing them recreates the second coordinate holder the
     * operator's A adjudication eliminated. (An earlier revision of this
     * guard asserted the OPPOSITE, reading the drifted product TrustPolicy
     * as spec intent; the spec is the truth source, not the drift.)
     */
    @Test
    fun kb8_planSeedDoesNotImportFixtureCoordinates() {
        val seedLogic = File(debugSourceDir, "APlus10APlanSeed.kt")
        assertTrue("APlus10APlanSeed.kt must exist in the debug source set", seedLogic.isFile)
        val code = kotlinSourcesWithoutComments(debugSourceDir).first { it.first == seedLogic }.second
        assertEquals(
            "the plan seeder must NOT read fixture coordinates (KB-8: provider-owned)",
            false,
            Regex("""item\.(latitude|longitude)""").containsMatchIn(code),
        )
        assertEquals(
            "the fixture parser must NOT extract coordinate fields (KB-8)",
            false,
            Regex("""getDouble\("(latitude|longitude)"\)""").containsMatchIn(code),
        )
        assertTrue(
            "the legacy non-null task columns must carry the inert placeholder",
            code.contains("COORDINATE_PLACEHOLDER"),
        )
    }

    @Test
    fun gap1_seedActivityIsDeclaredInDebugManifest() {
        val manifest = File(moduleRoot, "src/debug/AndroidManifest.xml").readText()
        val noComments = manifest.replace(Regex("<!--[\\s\\S]*?-->"), "")
        assertTrue(
            "src/debug/AndroidManifest.xml must declare APlusSeedActivity — otherwise §5A " +
                "seed/run has no adb entry point",
            Regex("""<activity\b[^>]*?APlusSeedActivity""").containsMatchIn(noComments),
        )
    }

    // ------------------------------------------------------------------
    // R8 P1-2 (Option D) — the start receipt is OBSERVED after the product
    // call on the product's own PUBLISHED verdict. Wiring-sensitive pins:
    // the pure classifier is behaviourally tested in APlus10APlanSeedTest;
    // these guards pin what the shipped activity feeds it and when.
    // ------------------------------------------------------------------

    /**
     * Ordering: snapshot the public logs BEFORE the call, call the product,
     * read logs + isRunning AFTER, classify, and only then print the receipt;
     * the request-owned poll is reachable ONLY through onlyIfAccepted(receipt).
     * Moving `accepted_observed` (or the poll) in front of the call turns the
     * observation back into the R7 prediction Sol's counterexample beat.
     */
    @Test
    fun r8_startReceiptIsObservedAfterTheProductCallAndGatesThePoll() {
        val code = kotlinSourcesWithoutComments(debugSourceDir)
            .first { it.first.name == "APlusSeedActivity.kt" }.second
        fun at(token: String): Int {
            val i = code.indexOf(token)
            assertTrue("start_run must contain '$token'", i >= 0)
            return i
        }
        val before = at("val logsBefore = AutomationService.logs.value")
        // The product call must be a bare statement (the runbook echo line also
        // contains the text inside a string literal — that is not the call).
        val startStatement = Regex("""(?m)^\s*AutomationService\.startAutomation\(planId\)\s*$""")
        val callMatch = startStatement.find(code)
        assertTrue("start_run must call AutomationService.startAutomation(planId) as a bare statement", callMatch != null)
        val call = callMatch!!.range.first
        val after = at("val logsAfter = AutomationService.logs.value")
        val running = at("val runningAfter = AutomationService.isRunning.value")
        val classify = at("APlus10APlanSeed.observeStartReceipt(")
        val accepted = at("\"START_RECEIPT accepted_observed")
        val gate = at("APlus10APlanSeed.onlyIfAccepted(receipt)")
        assertTrue("logs must be snapshotted BEFORE the product call", before < call)
        assertTrue("logs must be re-read AFTER the product call", call < after)
        assertTrue("isRunning must be read AFTER the product call", call < running)
        assertTrue("classification must follow both post-call reads", after < classify && running < classify)
        assertTrue("the receipt line may be printed only after classification", classify < accepted)
        assertTrue("the poll gate must follow the receipt", accepted < gate)
        assertTrue(
            "the request-owned poll must be invoked ONLY inside onlyIfAccepted(receipt) { ... }",
            Regex("""onlyIfAccepted\(receipt\)\s*\{\s*pollStartVerdict\(""").containsMatchIn(code),
        )
        assertEquals(
            "pollStartVerdict must have exactly one call site (inside the gate) besides its declaration",
            2,
            Regex("""pollStartVerdict\(""").findAll(code).count(),
        )
        assertEquals(
            "exactly one product start call per request",
            1,
            startStatement.findAll(code).count(),
        )
        assertTrue(
            "rejected and indeterminate receipts must both terminate in RUN_NOT_STARTED before the gate",
            Regex("""rejected_already_running[\s\S]{0,700}RUN_NOT_STARTED[\s\S]{0,900}indeterminate[\s\S]{0,700}RUN_NOT_STARTED""")
                .containsMatchIn(code.substring(0, gate)),
        )
    }

    /**
     * The receipt couples to an UNVERSIONED product behaviour: the reject
     * branch of AutomationService.startWithPlan publishes one exact log line;
     * the accept branch flips isRunning and publishes nothing before launch.
     * Pin every fact the classifier relies on so drift in main goes loudly
     * red here instead of quietly reading as "indeterminate" on the device.
     */
    @Test
    fun r8_productRejectSentinelAndAcceptSilenceArePinnedInMain() {
        val service = File(mainSourceDir, "automation/AutomationService.kt")
        assertTrue("AutomationService.kt must exist", service.isFile)
        val sources = kotlinSourcesWithoutComments(mainSourceDir)
        val code = sources.first { it.first == service }.second
        val sentinel = APlus10APlanSeed.PRODUCT_REJECT_SENTINEL
        // 1. reject branch: check → exactly this log line → return
        assertTrue(
            "startWithPlan's reject branch must publish exactly '$sentinel' and return",
            Regex("""if\s*\(_isRunning\.value\)\s*\{\s*addLog\("${Regex.escape(sentinel)}"\)\s*return\s*\}""")
                .containsMatchIn(code),
        )
        // 2. the literal exists ONCE in all of main — no other main writer can forge it
        val mainAll = sources.joinToString("\n") { it.second }
        assertEquals(
            "the reject sentinel must occur exactly once in src/main",
            1,
            Regex(Regex.escape(sentinel)).findAll(mainAll).count(),
        )
        // 3. accept branch: _isRunning := true BEFORE launch, NO synchronous addLog (lambda wiring only)
        val rejectLog = code.indexOf("addLog(\"$sentinel\")")
        val launch = code.indexOf("automationJob = serviceScope.launch")
        assertTrue("the reject branch must precede the launch", rejectLog in 0 until launch)
        val acceptRegion = code.substring(rejectLog + 1, launch)
        assertTrue(
            "the accept branch must set _isRunning.value = true before launching",
            acceptRegion.contains("_isRunning.value = true"),
        )
        assertEquals(
            "the accept branch must publish no log before launch (every addLog( there must be lambda wiring)",
            Regex("""\{\s*addLog\(it\)\s*\}""").findAll(acceptRegion).count(),
            Regex("""addLog\(""").findAll(acceptRegion).count(),
        )
        // 4. the entry point is a synchronous direct call, and logs are public
        assertTrue(
            "startAutomation must call startWithPlan synchronously (happens-before, not poll)",
            Regex("""fun startAutomation\(planId: Long\)\s*\{\s*instance\?\.startWithPlan\(planId\)""").containsMatchIn(code),
        )
        assertTrue(
            "logs must be published as a public StateFlow",
            code.contains("val logs: StateFlow<List<String>> = _logs"),
        )
        // 5. the entry format the classifier matches: "[HH:mm:ss] $message"
        assertTrue(code.contains("SimpleDateFormat(\"HH:mm:ss\""))
        assertTrue(code.contains("\"[\$timestamp] \$message\""))
    }

    // ------------------------------------------------------------------
    // Production purity
    // ------------------------------------------------------------------

    @Test
    fun collectorSurfaceIsAbsentFromProductionMainSource() {
        val main = kotlinSourcesWithoutComments(mainSourceDir).joinToString("\n") { it.second }
        listOf(
            "ProviderRevokeCollectorActivity",
            "APlusSeedActivity",
            "APlus10APlanSeed",
            collectorMarker,
            "run_active",
            "attempt_state:",
            "trusted_count:",
            "hold_lease",
            "release_receipt_loss",
            "crash_after_apply",
            "seed_plan",
            "start_run",
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

    // ------------------------------------------------------------------
    // PR #62 merge-gate P1 (codex inline 3898022696, re-verified by Sol @ faf561d):
    // start_run must be bound to the EXACT latest seed_plan invocation, not to
    // "any plan whose topology matches". seed_plan inserts a new plan every time
    // and never deletes earlier FX-G2-10A plans, so a stale planId replayed from
    // an older seed report would start old task/attempt/quota state while this
    // surface still reports the run as plan-bound — a harness false green.
    // ------------------------------------------------------------------

    @Test
    fun `start_run is bound to the exact latest seed_plan and stale plan ids fail closed`() {
        val bindingFile = File(debugSourceDir, "APlusSeedBinding.kt")
        assertTrue(
            "APlusSeedBinding.kt must exist in the debug source set — the durable latest-seed carrier",
            bindingFile.isFile,
        )
        val sources = kotlinSourcesWithoutComments(debugSourceDir)
        val bindingSrc = sources.first { it.first.name == "APlusSeedBinding.kt" }.second
        val activitySrc = sources.first { it.first.name == "APlusSeedActivity.kt" }.second

        // seed_plan records THIS seed as the only startable one.
        assertTrue(
            "seed_plan must persist the latest seed via APlusSeedBinding.record(",
            activitySrc.contains("APlusSeedBinding.record("),
        )
        // start_run verifies identity (latest planId + seed_token) INSIDE the single-flight lock,
        // so the check is atomic with the seed record write.
        val startRunAt = activitySrc.indexOf("fun StringBuilder.startRun(")
        val lockAt = activitySrc.indexOf("synchronized(START_RUN_LOCK)", startRunAt)
        val verifyAt = activitySrc.indexOf("APlusSeedBinding.verifyLatestSeed(", startRunAt)
        assertTrue("startRun must exist", startRunAt >= 0)
        assertTrue("startRun must take START_RUN_LOCK", lockAt > startRunAt)
        assertTrue(
            "start_run must call APlusSeedBinding.verifyLatestSeed( under START_RUN_LOCK",
            verifyAt > lockAt,
        )
        // Three fail-closed refusal shapes are named in the carrier: no record / stale id / wrong token.
        for (needle in listOf("no verified seed recorded", "is not the latest seed", "seed_token does not match")) {
            assertTrue("APlusSeedBinding must refuse with '$needle'", bindingSrc.contains(needle))
        }
        // The documented spelling must teach the token so the runbook cannot drift back to plan_id-only.
        assertTrue(
            "usage must document --es seed_token",
            activitySrc.contains("--es seed_token") || activitySrc.contains("--es \${APlusSeedBinding.EXTRA_SEED_TOKEN}"),
        )
    }
}

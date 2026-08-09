# F001 Prioritized Location Test Plan — Implementation Plan

**Feature:** F001 — `docs/features/F001-prioritized-location-test-plan.md`
**Goal:** Execute an operator-provided CSV location worklist in deterministic priority order, counting only verified fresh CellRebel successes toward per-location quotas, with Room-persisted progress, resume, and audit export.
**Acceptance Criteria:** AC-A1..A5, AC-B1..B5, AC-C1..C5 — copied verbatim from the spec; every task below names the ACs it covers.
**Architecture cell:** n/a — external Android project, no ownership map in this repo.
**Map delta:** none
**Map delta why:** Single-app repo; F001 extends the existing app module without new architectural boundaries (KD-6).
**Architecture:** Extend the existing sequential coroutine engine. Pure-Kotlin, unit-testable cores (CSV parser, plan scheduler, CellRebel state detector) sit behind thin Android adapters (Room, AccessibilityNodeInfo, DataStore). Compose UI follows the approved v2.1 wireframe (`feature-discussions/2026-07-30-f001-design/README.md`).
**Tech Stack:** Kotlin 2.2.10, AGP 9.1.0, Gradle 9.3.1 (wrapper), JDK 17, Room 2.7.1 (KSP), DataStore Preferences, kotlinx-coroutines-test, Robolectric (Room JVM tests), JUnit4.
**前端验证:** Yes — Compose UI changes; reviewer must verify Plan/Run/History journey via screenshots or emulator run, not just code read.

**Grounding:** Design Gate approved by operator 2026-07-30 22:31 UTC ("同意"), pushed at `0a5b25c`. Approved wireframe v2.1 is the UI contract.

---

## Straight-Line Check

**Finish line (B):** Operator imports a CSV, sets one global buffer, presses Start; the app walks the list `priority ASC, csvRow ASC`, drives Fake GPS + CellRebel per location until its verified success quota completes, survives restarts, and exports an audit CSV. **NOT building:** XLSX, multi-OEM, Play Store, dashboard, weighted scheduling, generic state-machine rewrite, Clear/delete plan UI.

**Terminal schema (final form — no throwaway scaffolding):**

```kotlin
// model/plan/WorklistParser.kt — pure Kotlin, no Android deps
data class WorklistRow(
    val longitude: Double, val latitude: Double,
    val priority: Int, val requiredSuccesses: Int,
    val csvRow: Int            // 1-based data-row number in the source file
)
data class RowError(val csvRow: Int, val message: String)
sealed interface ParseResult {
    data class Success(val rows: List<WorklistRow>) : ParseResult
    data class Failure(val errors: List<RowError>) : ParseResult   // atomic: ALL row errors
}

// model/plan/Entities.kt — Room, DB v3
@Entity(tableName = "location_plans")
data class LocationPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceFileName: String,
    val importedAt: Long,
    val globalBufferSeconds: Int,
    val totalRows: Int,
    val totalRequiredSuccesses: Int
    // NO status field — plan status is a pure projection (spec §Object census)
)

@Entity(tableName = "location_tasks",
    foreignKeys = [ForeignKey(entity = LocationPlan::class,
        parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId")])
data class LocationTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val csvRow: Int,
    val longitude: Double, val latitude: Double,
    val priority: Int,
    val requiredSuccesses: Int,
    val completedSuccesses: Int = 0,
    val status: String = "pending"     // pending | active | completed  (no failed, no cooldown)
)

@Entity(tableName = "test_attempts",
    foreignKeys = [
        ForeignKey(entity = LocationTask::class,
            parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RunSession::class,
            parentColumns = ["id"], childColumns = ["runSessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId"), Index("runSessionId")])
data class TestAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val runSessionId: Long,
    val attemptOrdinal: Int,           // per task, 1-based
    val successOrdinal: Int?,          // per task, 1-based; non-null ONLY when succeeded
    val startedAt: Long,
    val runningObservedAt: Long?,      // AC-B2 audit: READY/COMPLETED -> RUNNING observed at
    val endedAt: Long?,
    val status: String,                // starting | running | succeeded | failed | interrupted
    val failureReason: String?,        // typed FailureReason.name; required when failed/interrupted
    val webBrowsingScore: Double?,
    val videoStreamingScore: Double?,
    val latitude: Double, val longitude: Double
)
// RunSession gains `planId: Long?` (migration 2 -> 3). Legacy test_results table kept as-is (read-only history), no destructive migration.

// automation/AttemptOutcome.kt — typed handler outcomes (replaces nullable TestScores + Unit)
enum class FailureReason {
    FAKE_GPS_NOT_ACTIVE, FOREGROUND_SWITCH_FAILED, NO_RUNNING_EVIDENCE,
    CELLREBEL_TIMEOUT, SCORE_PARSE_FAILED, CANCELLED, INTERRUPTED
}
sealed interface AttemptOutcome {
    val startedAt: Long; val endedAt: Long
    data class Success(
        val webScore: Double, val videoScore: Double,
        val runningObservedAt: Long,
        override val startedAt: Long, override val endedAt: Long
    ) : AttemptOutcome
    data class Failure(
        val reason: FailureReason, val detail: String?,
        override val startedAt: Long, override val endedAt: Long
    ) : AttemptOutcome
}

// automation/cellrebel/ScreenSnapshot.kt — pure-Kotlin view of the a11y tree
data class ScreenNode(
    val text: String?, val contentDescription: String?, val className: String?,
    val clickable: Boolean, val enabled: Boolean,
    val children: List<ScreenNode> = emptyList()
)
fun AccessibilityNodeInfo.toScreenNode(): ScreenNode   // adapter, main source only

// automation/cellrebel/CellRebelStateDetector.kt — pure state classification
enum class CellRebelScreenState { READY, RUNNING, COMPLETED, UNKNOWN }

// model/plan/PlanConfig.kt + data/PlanConfigStore.kt (DataStore)
data class PlanConfig(
    val globalBufferSeconds: Int?,     // null = not yet set -> Plan screen requires it (first-run required)
    val testTimeoutSeconds: Int = 90,  // Advanced, internal default
    val gpsSettleSeconds: Int = 60     // Advanced, carries over cycleIntervalSeconds default
)
```

**Detour check:** every task produces code/tests that ship in the final system; the only Spike is Task 0.1 (build baseline) whose output is the verified command, not product code.

---

## Stateful Object Gate (F229)

### Object census

| # | Object | Lifecycle owner | Persisted state | Derived/projection |
|---|--------|-----------------|-----------------|--------------------|
| O1 | LocationPlan | PlanRepository | metadata + globalBufferSeconds | plan status (completed/running/ready) — pure projection, never stored |
| O2 | LocationTask | AutomationEngine via PlanRepository | csvRow, coords, priority, quota, completedSuccesses, status ∈ {pending, active, completed} | card "cooldown" badge — projection of active task + buffer countdown |
| O3 | TestAttempt | AutomationEngine | full row incl. status ∈ {starting, running, succeeded, failed, interrupted} | — |
| O4 | RunSession | AutomationEngine | status ∈ {running, completed, stopped, interrupted}, planId | — |
| O5 | Buffer/cooldown | none (projection) | — | `remaining = lastTerminalAttempt.endedAt + buffer − now`; survives restart because endedAt is persisted |
| O6 | PlanConfig | PlanConfigStore (DataStore) | buffer (nullable until first set), timeout, settle | — |

### State × event transition tables

**O2 LocationTask** (owner: engine; bypass APIs — none exposed to UI)

| Event | From | To | Side effect |
|-------|------|----|-------------|
| selected by scheduler | pending | active | create attempt O3 (ordinal = max+1) |
| attempt succeeded, quota not met | active | active | completedSuccesses += 1 (transactional with O3 finalize) |
| attempt succeeded, quota met | active | completed | scheduler advances after buffer |
| attempt failed/interrupted | active | active | NO count change; scheduler enters buffer then retries same task |
| plan fully imported | — | pending | one row per CSV data row, original order preserved |

**O3 TestAttempt** (owner: engine)

| Event | From | To | Side effect |
|-------|------|----|-------------|
| created on task selection | — | starting | persist row with startedAt |
| running evidence observed | starting | running | persist runningObservedAt |
| stable completed result | running | succeeded | persist scores + endedAt; successOrdinal = completedSuccesses+1; increment task IN SAME TRANSACTION |
| timeout / typed error / stop | starting/running | failed | persist failureReason + endedAt; no count |
| recovery finds non-terminal row | starting/running | interrupted | one-shot sweep on plan load/resume (INV-9) |

**O4 RunSession**: running → completed | stopped | interrupted (process death detected on next service start: session still `running` with dead job → mark interrupted).

**Forbidden bypass operations:** UI never writes O2/O3/O4 directly; import rejected while a plan is unfinished; no delete/clear API in v1.

### Invariants → test matrix

| INV | Test (unit, JVM unless noted) |
|-----|-------------------------------|
| INV-1 order priority ASC, csvRow ASC | `PlanSchedulerTest.orders by priority then csv row` (AC-A3) |
| INV-2 no advance before quota | `PlanSchedulerTest.does not advance before quota complete` |
| INV-3 success exactly once | `AttemptFinalizationTest.success increments exactly once` + `repeated finalize is idempotent` |
| INV-4 failure never counts | `AttemptFinalizationTest.failure does not increment` |
| INV-5 buffer gates next attempt | `BufferTest.next attempt waits full buffer after success and after failure` (virtual time, AC-A5) |
| INV-6 score without running = stale | `CellRebelStateDetectorTest.completed fixture scores rejected without prior running` (AC-B1) |
| INV-7 identical scores valid | `AttemptFlowTest.identical consecutive scores both count after running transition` |
| INV-8 attempt ↔ task ↔ session | `AttemptDaoTest.attempt references exactly one task and session` (Room/Robolectric) |
| INV-9 restart recovery | `RecoveryTest.non terminal attempts marked interrupted, task counts preserved` (Room) |
| INV-10 GPS/foreground failure = typed failed attempt | `AttemptFlowTest.fake gps failure yields typed failure, no quota consumed` (AC-B4) |

### Adversarial scenarios (each = one test)

1. **Crash window mid-attempt**: attempt row `running`, process dies → next start sweeps to `interrupted`, task quota unchanged (INV-9).
2. **Double finalization**: engine crashes after attempt update but before task increment → single Room transaction makes the pair atomic; re-run of finalize is idempotent via `successOrdinal = completedSuccesses + 1` checked inside the transaction (INV-3).
3. **Process death during cooldown**: buffer computed from persisted `endedAt` on resume; no attempt starts early, none skipped (INV-5).
4. **Import during unfinished plan**: repository rejects; existing progress untouched (design gate §1.1).
5. **Stale score behind overlay**: running fixture still shows old `EXCELLENT / 10.00` → detector reports RUNNING, handler does not accept scores (INV-6, AC-B1).
6. **Identical consecutive results**: two runs with same scores both count (INV-7).

---

## Task 0: Build & test baseline (AC-A1)

**Files:**
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` (pinned to `gradle-9.3.1`, matching existing `gradle-wrapper.properties`)
- Modify: `app/build.gradle.kts` (add test deps)
- Create: `app/src/test/java/com/example/cellrebelauto/SanityTest.kt`

**Step 1: Restore JDK 17 + wrapper (environment prerequisite).** This machine has no JDK and no gradle; Android SDK exists at `~/Library/Android/sdk`. Bootstrap:

```bash
brew install --cask temurin@17      # JDK 17 (mission-authorized: "恢复 Gradle/JDK17 测试基线")
brew install gradle                 # bootstrap only, to regenerate wrapper
gradle wrapper --gradle-version 9.3.1 --distribution-type bin
git add gradlew gradlew.bat gradle/wrapper/ && brew uninstall gradle   # wrapper is self-contained after this
```

Expected: `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` present; `gradle-wrapper.properties` unchanged (already 9.3.1).

**Step 2: Add test dependencies** to `app/build.gradle.kts`:

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
testImplementation("androidx.room:room-testing:2.7.1")
testImplementation("org.robolectric:robolectric:4.14.1")
```

**Step 3: Write sanity test** (`SanityTest.kt`: `assertEquals(4, 2 + 2)`).

**Step 4: Verify baseline.** Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 1 test passed. If AGP/Kotlin mismatch surfaces (e.g. `kotlinOptions` deprecation becoming an error), fix minimally and record the decision.

**Step 5: Commit** — `build(F001): restore Gradle 9.3.1 wrapper, JDK 17, and unit-test baseline [墨墨/Kimi K3🐾]` (body: why — repo shipped without wrapper/JDK baseline, AC-A1).

## Task 1: CSV worklist parser (AC-A2)

**Files:**
- Create: `app/src/main/java/com/example/cellrebelauto/model/plan/WorklistParser.kt`
- Test: `app/src/test/java/com/example/cellrebelauto/model/plan/WorklistParserTest.kt`

**Step 1: Failing tests** — positive (header + 3 rows → rows with 1-based csvRow); negatives each asserting the FULL error list: bad latitude range, non-numeric priority, requiredSuccesses < 1, missing/extra column, missing header, empty file, blank lines skipped. Atomic: one bad row → `Failure` containing every row error, no partial rows.

**Step 2:** Run `./gradlew :app:testDebugUnitTest --tests "*WorklistParserTest*"` → FAIL (red).

**Step 3: Implementation** — exact contract:

```kotlin
object WorklistParser {
    const val HEADER = "longitude,latitude,priority,required_successes"
    fun parse(text: String, fileName: String = ""): ParseResult {
        // 1. split lines, trim, drop blank lines
        // 2. first line must equal HEADER (case-sensitive, trimmed) else Failure(header error)
        // 3. per data row (1-based): split(',', expect 4 fields)
        //    longitude ∈ [-180,180], latitude ∈ [-90,90], priority ≥ 0 int, requiredSuccesses ≥ 1 int
        // 4. collect ALL RowError; errors.isEmpty() -> Success(rows) else Failure(errors)
    }
}
```

**Step 4:** Re-run → PASS (green). **Step 5: Commit** — `feat(F001): atomic CSV worklist parser with row-level errors`.

## Task 2: Room schema v3 — plan/task/attempt entities + real migration (AC-A4)

**Files:**
- Create: `model/plan/Entities.kt` (schema above), `db/PlanDao.kt`, `db/LocationTaskDao.kt`, `db/TestAttemptDao.kt`
- Modify: `db/AppDatabase.kt` (version 3, add entities/DAOs, `Migration(2,3)`, REMOVE `fallbackToDestructiveMigration`), `model/RunSession.kt` (+`planId: Long? = null`)
- Test: `app/src/test/java/com/example/cellrebelauto/db/PlanSchemaTest.kt` (Robolectric, in-memory DB)

**Step 1: Failing tests** — insert plan + tasks + attempts, recreate repository, assert survival; assert `location_plans` has no status column (projection rule); attempt FK enforced.
**Step 2:** run → red. **Step 3: Implement** entities, DAOs (`insertPlanWithTasks` in `@Transaction`, `getActiveTask`, `nextPendingTask` ordered `priority ASC, csvRow ASC`, `incrementSuccessIfCurrent(taskId, expectedCompleted)` guarded update for INV-3 idempotency, `markNonTerminalInterrupted()`, `getLatestPlan`), migration:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE run_sessions ADD COLUMN planId INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS location_plans (...)")
        db.execSQL("CREATE TABLE IF NOT EXISTS location_tasks (...)")
        db.execSQL("CREATE TABLE IF NOT EXISTS test_attempts (...)")
    }
}
```

**Step 4:** green. **Step 5: Commit** — `feat(F001): Room v3 plan/task/attempt schema with non-destructive migration`.

## Task 3: Plan scheduler core (AC-A3, INV-1/2)

**Files:**
- Create: `automation/plan/PlanScheduler.kt` (pure functions over task lists)
- Test: `app/src/test/java/com/example/cellrebelauto/automation/plan/PlanSchedulerTest.kt`

**Step 1: Failing tests** — `selectNext` returns active unfinished task first; else lowest priority; ties keep csvRow order; completed-skipped; quota-met active task is completed not re-selected; `isPlanComplete`.
**Step 2:** red. **Step 3: Implement:**

```kotlin
object PlanScheduler {
    fun executionOrder(tasks: List<LocationTask>): List<LocationTask> =
        tasks.sortedWith(compareBy({ it.priority }, { it.csvRow }))
    fun selectNext(tasks: List<LocationTask>): LocationTask? =
        executionOrder(tasks).firstOrNull { it.status == "active" && it.completedSuccesses < it.requiredSuccesses }
            ?: executionOrder(tasks).firstOrNull { it.status == "pending" }
    fun isQuotaComplete(t: LocationTask) = t.completedSuccesses >= t.requiredSuccesses
    fun isPlanComplete(tasks: List<LocationTask>) = tasks.all { it.status == "completed" }
}
```

**Step 4:** green. **Step 5: Commit** — `feat(F001): deterministic plan scheduler (priority ASC, csvRow ASC, quota gate)`.

## Task 4: Global buffer gate (AC-A5, INV-5; AC-B5 semantics)

**Files:**
- Create: `automation/plan/BufferGate.kt`
- Test: `app/src/test/java/com/example/cellrebelauto/automation/plan/BufferGateTest.kt`

**Step 1: Failing tests (virtual time)** — `runTest { }` + injected clock: after success, next attempt blocked until buffer expiry; after failure, same; buffer survives "restart" (computed from persisted endedAt, not in-memory timer); timeout param is NOT the buffer (distinct semantics assertion: changing testTimeout does not shift buffer).
**Step 2:** red. **Step 3: Implement:**

```kotlin
class BufferGate(val bufferSeconds: Int, val nowMs: () -> Long) {
    fun remainingMs(lastTerminalEndedAt: Long?): Long {
        if (lastTerminalEndedAt == null) return 0
        val elapsed = nowMs() - lastTerminalEndedAt
        return maxOf(0, bufferSeconds * 1000L - elapsed)
    }
}
```

**Step 4:** green. **Step 5: Commit** — `feat(F001): global inter-attempt buffer gate (projection from persisted endedAt)`.

## Task 5: PlanConfig persistence (design gate §1.1; AC-B5)

**Files:**
- Create: `model/plan/PlanConfig.kt`, `data/PlanConfigStore.kt` (DataStore Preferences)
- Test: `app/src/test/java/com/example/cellrebelauto/data/PlanConfigStoreTest.kt` (Robolectric)

**Step 1: Failing tests** — default buffer is null (unset); set → persists across store recreation; timeout/settle have independent defaults and independent writes.
**Step 2:** red. **Step 3: Implement** DataStore keys `global_buffer_seconds` (Int, absent until set), `test_timeout_seconds` (default 90), `gps_settle_seconds` (default 60).
**Step 4:** green. **Step 5: Commit** — `feat(F001): persist plan config (buffer first-run required, independent advanced timing)`.

## Task 6: Typed attempt outcomes + CellRebel state detector (AC-B1, B2; INV-6/7)

**Files:**
- Create: `automation/AttemptOutcome.kt`, `automation/cellrebel/ScreenSnapshot.kt`, `automation/cellrebel/CellRebelStateDetector.kt`
- Test: `app/src/test/java/com/example/cellrebelauto/automation/cellrebel/CellRebelStateDetectorTest.kt` + fixtures `automation/cellrebel/Fixtures.kt`

**Step 1: Failing tests** using two fixture snapshots mirroring the operator's screenshots:
- *Completed fixture*: both score labels + rating + numeric score, no processing text, Start enabled+clickable → `COMPLETED`, scores extracted.
- *Running fixture*: `Processing results...` over web card, `Measuring video streaming quality...` over video card, OLD `EXCELLENT`/`10.00` still present, Start disabled → `RUNNING`, `extractScores` NOT accepted as completion (INV-6/AC-B1).
- Start disabled alone → RUNNING evidence candidate; READY when Start enabled & no scores.
**Step 2:** red. **Step 3: Implement:**

```kotlin
class CellRebelStateDetector {
    private val runningMarkers = listOf("Processing results", "Measuring video streaming quality")
    fun classify(nodes: List<ScreenNode>): CellRebelScreenState {
        val hasMarker = nodes.any { n -> runningMarkers.any { m -> n.text?.contains(m, true) == true } }
        val start = nodes.firstOrNull { it.text.equals("Start", true) && it.clickable }
        if (hasMarker || (start != null && !start.enabled)) return CellRebelScreenState.RUNNING
        if (extractScores(nodes) != null && start?.enabled == true) return CellRebelScreenState.COMPLETED
        if (start?.enabled == true) return CellRebelScreenState.READY
        return CellRebelScreenState.UNKNOWN
    }
    fun extractScores(nodes: List<ScreenNode>): Pair<Double, Double>? // label-proximity parse, moved from CellRebelHandler
}
```

**Step 4:** green. **Step 5: Commit** — `feat(F001): CellRebel screen-state detector; stale scores never accepted without running transition`.

## Task 7: Attempt lifecycle in CellRebelHandler (AC-B2, B3, B5)

**Files:**
- Modify: `automation/CellRebelHandler.kt` — `runTest` returns `AttemptOutcome`, consumes `testTimeoutMs` (the previously ignored `collectDelayMs` is replaced, not renamed-and-ignored), Start fallback click only when no running evidence (AC-B3), success requires: running observed → markers gone → Start enabled → scores valid and stable across 2 consecutive polls (INV-3/6/7).
- Test: `app/src/test/java/com/example/cellrebelauto/automation/CellRebelAttemptFlowTest.kt` — drive handler against a scripted `ScreenSnapshot` sequence (fake bridge): no-running timeout → `Failure(NO_RUNNING_EVIDENCE)`; running→completed with identical scores → `Success` (INV-7); score change during "processing" → not accepted until stable.

**Steps:** red → implement → green → commit `feat(F001): verified CellRebel attempt lifecycle with typed outcomes`.

## Task 8: Fake GPS typed outcome + fail-closed (AC-B4, INV-10)

**Files:**
- Modify: `automation/FakeGpsHandler.kt` — `setLocation` returns `AttemptOutcome.Failure(FAKE_GPS_NOT_ACTIVE)` when activation unproven (Stop button never appears) or foreground switch fails (`FOREGROUND_SWITCH_FAILED`); success only with positive activation evidence.
- Test: extend attempt-flow fake to cover GPS failure → typed failure, no quota consumed.

**Steps:** red → implement → green → commit `feat(F001): fail-closed Fake GPS activation with typed failures`.

## Task 9: Engine integration + recovery sweep (INV-3/8/9; AC-C2 data half)

**Files:**
- Modify: `automation/AutomationEngine.kt` — replace random-point loop with plan loop: load active plan → recovery sweep (mark non-terminal attempts `interrupted`, dead `running` sessions `interrupted`) → select task → BufferGate wait → GPS setLocation (on failure: typed failed attempt, no quota) → GPS settle after confirmed activation → attempt → finalize in ONE Room transaction (attempt row + conditional task increment) → repeat.  *(Review round 1 / F3: made the journey order explicit — Setting GPS → GPS settling → Testing per design doc v2.1; the terse "GPS → settle" phrasing was read in reverse during implementation, which put settle before setLocation.)*
- Modify: `automation/AutomationService.kt` — start API takes planId, not AutoConfig.
- Test: `app/src/test/java/com/example/cellrebelauto/automation/EngineRecoveryTest.kt` (Room + fake handlers): crash-window scenario, idempotent finalize, failure-then-retry same location.

**Steps:** red → implement → green → commit `feat(F001): plan-driven engine with transactional finalize and restart recovery`.

## Task 10: Plan screen (AC-C1)

**Files:**
- Create: `ui/PlanScreen.kt`; Modify: `ui/MainViewModel.kt` (+`Screen.PLAN` as start destination, import action via SAF `ActivityResultContracts.OpenDocument`, config actions, start/resume/stop), `ui/MainActivity.kt`.
- Buttons per v2.1: Start (not started) / Resume (paused unfinished) / Stop+Run entry (active session only). Import rejected while unfinished with progress hint. Advanced section: test timeout, GPS settle. Buffer field: required when unset.

**Steps:** implement → `./gradlew :app:assembleDebug` → emulator/device screenshot evidence → commit `feat(F001): plan screen with atomic import, buffer config, progress cards`.

## Task 11: Run screen evolution (AC-C1, C5 visibility)

**Files:** Modify `ui/ControlScreen.kt` (status card with location/success/attempts, attempt stepper `Setting GPS → GPS settling → Testing → Processing → ✔/✘`, separate scheduler cooldown card, last-failure line), `model/AutomationState.kt` (+`PROCESSING`, `SUCCEEDED`, `FAILED`, `COOLDOWN`), `ui/MainViewModel.kt` (expose plan progress flow).
**Steps:** implement → build → screenshot → commit `feat(F001): run dashboard with attempt stepper and scheduler cooldown projection`.

## Task 12: History + CSV export extension (AC-C3)

**Files:** Modify `ui/HistoryScreen.kt` (attempt rows: plan row, priority, coords, success/attempt ordinal, status, failure reason, timestamps, running_observed, scores), `util/CsvExporter.kt` (15 columns: `plan_row,csv_row,priority,longitude,latitude,success_ordinal,attempt_ordinal,status,failure_reason,started_at,running_observed_at,ended_at,web_score,video_score,session_id`).
- Test: `app/src/test/java/com/example/cellrebelauto/util/CsvExportMappingTest.kt` (pure mapping function extracted from exporter).
**Steps:** red (mapping test) → implement → green + build → commit `feat(F001): attempt-based history and 15-column audit export`.

## Task 13: Remove legacy random mode (design gate review point 7)

**Files:** Delete `ui/ConfigScreen.kt`, `util/GpsRandomizer.kt`; strip bounding-box fields from `model/AutoConfig.kt` (or delete AutoConfig if fully unused); remove `Screen.CONFIG` and MainActivity/MainViewModel references.
**Steps:** delete → `./gradlew :app:assembleDebug :app:testDebugUnitTest` green → commit `refactor(F001): remove superseded random bounding-box mode`.

## Task 14: Target-device acceptance (AC-C4, C5; blocked by MIUI device access)

- AC-C4: 20 consecutive verified successes on the MIUI device, each with observed running→completed transition; capture log + exported CSV + screen recording.
- AC-C5: failure-injection run (e.g. kill CellRebel mid-run / disable Fake GPS) — failed attempts visible, quota untouched, retry only after buffer.
- Also resolves technical OQ-2 (real a11y anchors) via Dump A11y Tree on both screen states; if anchors diverge from Task 6 assumptions, adjust detector markers with evidence.

---

## Open Questions

**技术 OQ（自治）:** real a11y node anchors (Task 14 discovery + Dump tool); wrapper/JDK final fix (Task 0); internal `testTimeoutSeconds` default 90 (reversible config, self-decided); Fake GPS strongest activation evidence = Stop-button presence + foreground package check (Task 8).

**价值 OQ（operator, non-blocking):** default global buffer value (OQ-1) — field ships first-run-required; adapter for non-CSV source (OQ-3) — only after real sample.

## Commit & hygiene rules

- Work happens in an isolated worktree branch (`worktree` skill next); only this plan + truth-source sync lands on main.
- Never touch uncommitted governance files on main (`AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.cat-cafe/`, `.codex/`, etc.).
- BACKLOG F001 → in-progress when Task 1 starts.
- Every commit body states Why; sign `[墨墨/Kimi K3🐾]`.

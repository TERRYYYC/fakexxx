---
feature_ids: []
topics:
  - cellrebel-auto
  - a-plus
  - failure-audit
  - schedule-binding
  - crash-recovery
doc_kind: implementation_plan
created: 2026-09-07
status: in-progress
source_issues:
  - https://github.com/TERRYYYC/fakexxx/issues/86
  - https://github.com/TERRYYYC/fakexxx/issues/79
---

# Auto failure events and schedule binding implementation plan

**Feature:** A+ failure audit closure (#86) followed by provider-owned schedule binding (#79)

**Goal:** Every reachable missing-evidence or unproven-advance failure is named by the frozen reducer and atomically persisted with its exact reason, then every new bound worklist credits and advances only the QWY-owned current schedule item.

**Acceptance Criteria:** #86: freeze distinct event names, target states, typed reasons and forbidden bypasses for pre-RUNNING failure, missing POST observation, missing completion evidence and unproven advance; update canonical spec, enum, reducer, driver and normal/recovery tests together; retain immutable negative carriers before release/CLOSED; distinguish provider business error, invalid response and transport failure; prove atomic rollback. #79: persist an explicit plan↔schedule and task↔schedule-item binding; select by the provider's active `currentItemId`, not catalog/profile order or local priority; keep repeated `required_successes` on the same bound item; require all bound local quotas plus verified terminal advance/readback before whole-plan completion.

**Architecture cell:** `fakexxx::android-dual-app-contract`

**Map delta:** none

**Map delta why:** The changes stay inside the existing boundary: Auto owns plan/task/attempt/quota and QWY owns schedule identity/order/effective environment.

**Architecture:** Room remains the only Auto state owner. A pure reducer defines legal transitions; a repository transaction drives the reducer through `APlusAttemptDriver` and persists the attempt owner, exact typed reason and one named audit row together. For #79, QWY's active schedule projection is input authority while catalog `profileRefs` stays diagnostic; Auto stores stable references and never copies or reorders provider policy.

**Tech Stack:** Kotlin/JVM 17, Room 2.7, Android Gradle Plugin, JUnit4, Robolectric.

**前端验证:** No new interaction design. Host tests cover parser/repository/engine behavior; #85/#97 device acceptance remains explicitly separate and blocked.

---

## Finish line and exclusions

The finish line is one reviewable Auto branch in which #86's four named failure families are executable in both normal and recovery paths and #79's bound plans cannot credit or advance the wrong local task. The branch does not modify QWY storage, reinterpret `profileRefs` as active order, change historical intent digests, revive `CLOSED`, close #85/#97, or perform device/emulator work.

## Terminal schemas

### #86 failure events

| Current owner state | Event | Next state | Exact reason family | Forbidden bypass |
|---|---|---|---|---|
| `CELLREBEL_START_PENDING` | `START_FAILED_BEFORE_RUNNING` | `RECOVERY_REQUIRED` | `CELLREBEL_FAILURE_BEFORE_RUNNING:<FailureReason>` or `MISSING_START_INTERACTION_EVIDENCE` | fabricate `TIMEOUT_INTERRUPTED` without first classifying `PRE_EXISTING_RUN` |
| `CELLREBEL_RUNNING` | `TIMEOUT_INTERRUPTED` | `RECOVERY_REQUIRED` | `CELLREBEL_TIMEOUT_INTERRUPTED:<FailureReason>` / `RECOVERY_TIMEOUT_INTERRUPTED` | guess success |
| `POST_OBSERVE_PENDING` | `POST_OBSERVATION_MISSING` | `RECOVERY_REQUIRED` | `POST_OBSERVATION_UNAVAILABLE` or `RECOVERY_EVIDENCE_UNAVAILABLE:POST_OBSERVE_PENDING` | emit `OBSERVATION_UNTRUSTED` when no observation exists |
| `DECIDING` | `COMPLETION_EVIDENCE_MISSING` | `RECOVERY_REQUIRED` | `COMPLETION_EVIDENCE_UNAVAILABLE` or `RECOVERY_EVIDENCE_UNAVAILABLE:DECIDING` | emit an observation event for an absent completion carrier |
| `ADVANCE_PENDING` / `ADVANCE_OBSERVING` / `ADVANCE_STATE_READBACK` | `ADVANCE_NOT_PROVEN` | `RECOVERY_REQUIRED` | `ADVANCE_NOT_PROVEN:<ProviderError(code) | InvalidResponse(detail) | TransportFailure | ProviderNotBound>` | invent a receipt or classify absence as `ADVANCE_DIGEST_MISMATCH` |

`PRE_EXISTING_RUN` deliberately remains `CELLREBEL_START_PENDING → CELLREBEL_RUNNING`; only after that classification may `TIMEOUT_INTERRUPTED` be emitted. All named recovery edges use one repository transaction:

```kotlin
suspend fun transitionToRecoveryRequired(
    attemptId: Long,
    event: AttemptEvent,
    reason: String,
    nowMs: Long
): AttemptState
```

The transaction re-reads the durable owner, runs `APlusAttemptDriver`/`AttemptTransitions`, requires `RECOVERY_REQUIRED`, writes `failureReason`, and appends exactly one event row whose payload is `<from>->RECOVERY_REQUIRED[<reason>]`. An immutable `UnverifiedAttemptRecord` is written and read back before this transition for every non-wire-1 CellRebel outcome; advance failure retains the existing trusted quota carrier and never fabricates an unverified completion.

### #79 bound worklist (requires QWY owner confirmation before implementation)

Proposed CSV v2 header:

```text
longitude,latitude,priority,required_successes,schedule_id,schedule_item_id
```

The v1 four-column header remains byte-for-byte accepted as legacy input. Proposed Auto Room v9 additions are nullable for migration safety:

```kotlin
LocationPlan.boundScheduleId: String?
LocationTask.scheduleItemId: String? // UNIQUE(planId, scheduleItemId)
TestAttempt.aplusIntentProfileRef: String?
DurableObservationRecord.scheduleItemId: String?
DurableObservationRecord.scheduleVersion: Long?
```

For a v2 plan every row must name the same non-blank `schedule_id`, item ids must be non-blank and unique, and partial binding is rejected atomically. The active authority tuple is QWY `currentScheduleId/currentItemId/scheduleVersion/exhausted`; `profileRefs` is catalog/diagnostic only. New bound attempts use `currentItemId` for `EnvironmentIntentV1.profileRef`; legacy attempts with null `aplusIntentProfileRef` recompute the existing literal `plan-$planId`, preserving all old digests and replays. An exact `serviceVersion` allowlist gates the new interpretation; version-only preflight→apply drift may have dispatched an apply, so Auto claims only zero trusted count, never zero external effect.

## Stateful-object census

| Object | Lifecycle owner | Legal transitions / mutations | Bypass prohibition |
|---|---|---|---|
| `TestAttempt` A+ owner | `PlanRepository` | reducer events only; `CLOSED` is sink | no raw DAO/string transition from a named #86 failure |
| `UnverifiedAttemptRecord` | `PlanRepository` | absent→insert once; exact replay is no-op | no rewrite to a different reason/digest; no release/CLOSED first |
| `CompleteAndAdvanceOutcome` | Binder executor | one call→receipt or typed failure | no nullable collapse or mutable side channel |
| `LocationPlan` binding | import transaction | unbound legacy or fully bound v2; immutable after insert | no partial schedule binding; no reuse of archived/superseded plan as current |
| `LocationTask` binding/quota | plan import + trusted ledger projection | same item reselected until trusted quota reached; only then advance | no local priority/csv order as provider-current authority |
| `TestAttempt.aplusIntentProfileRef` | attempt-open transaction | null legacy or exact current item snapshot; immutable | no recompute from a later discover; no overwriting old replay identity |
| durable observation attribution | observation persistence | bind exact item/version observed for that phase | no trusted mint across item/version drift |
| QWY active schedule projection | QWY | read-only to Auto | no writes, UI driving, profile catalog inference or provider-exhausted=plan-complete shortcut |

## Invariants and adversarial matrix

- **INV-FE-1:** each named recovery edge atomically exposes either old owner+no event or new owner+exact event+reason; a forced audit insert failure leaves the old owner and reason unchanged.
- **INV-FE-2:** absent POST/completion evidence never emits `OBSERVATION_UNTRUSTED`; a pre-RUNNING failure never emits `TIMEOUT_INTERRUPTED` unless `PRE_EXISTING_RUN` first reached RUNNING.
- **INV-FE-3:** every non-wire-1 path has one immutable negative carrier before release/CLOSED; recovery preserves its exact reason/digest.
- **INV-FE-4:** provider error 16, malformed response and transport exception are distinct typed outcomes but all emit `ADVANCE_NOT_PROVEN`, never `ADVANCE_DIGEST_MISMATCH`, and never synthesize a receipt.
- **INV-BIND-1:** a bound plan has exactly one schedule id and a bijection from local task to non-blank schedule item id.
- **INV-BIND-2:** current selection is the unique trusted-incomplete task whose binding equals QWY `currentItemId`; reversed local order cannot change attribution.
- **INV-BIND-3:** trusted count below `required_successes` retains the same bound item and emits no advance; quota reached permits one persisted advance request.
- **INV-BIND-4:** `exhausted=true` cannot complete a plan with any bound task below real trusted quota, and terminal success still requires the existing verified advance receipt/readback path.
- **INV-BIND-5:** legacy rows and attempts retain null binding fields and old `plan-$planId` digest/replay semantics; genuine `CLOSED` remains terminal.
- **INV-BIND-6:** a bound plan refuses unsupported `serviceVersion`, partial schedule tuple, wrong schedule id, unknown item id and version/item drift before minting trusted quota.

Adversarial tests cover: audit insertion rollback; duplicate recovery replay; pre-existing classification ordering; normal and recovery missing POST/completion; normal and recovery provider-error 16/invalid/transport; two tasks whose local order is opposite QWY current item; duplicate/partial CSV v2 binding; v8→v9 migration preserving old data; required-successes >1 on one item; exhausted provider with unfinished bound task; version-only preflight→apply drift after a possible dispatch; and all old `CLOSED`/digest replay controls.

### Task 1: Freeze #86 behavior with failing tests

**Files:**
- Modify: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/aplus/AttemptTransitionsRedTest.kt`
- Modify: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/aplus/APlusAttemptDriverRedTest.kt`
- Modify: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/EngineTrustedPathRedTest.kt`
- Modify: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt`
- Modify: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/EngineAdvanceRecoveryOracleTest.kt`
- Modify: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/repository/RecoveryRequiredAuditTransactionTest.kt`

1. Add reducer assertions via `AttemptEvent.valueOf(...)` so RED is a behavioral missing-event failure, not a Kotlin syntax failure.
2. Add normal-path assertions for each named event, exact payload reason, negative-carrier ordering and forbidden old event.
3. Add crash recovery negatives that assert exact immutable carrier and named event.
4. Add provider-error 16, malformed response and transport failures for both normal and `ADVANCE_PENDING` replay paths.
5. Install a test-only SQLite trigger that aborts the named audit insert and prove the owner/reason rolls back.
6. Run only the six affected test classes; expected RED is missing named events/typed outcome, never environment setup failure.

### Task 2: Implement the minimal #86 event/atomic path

**Files:**
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/aplus/AttemptTransitions.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/aplus/APlusAttemptDriver.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/recovery/ExternalApplyExecutor.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/recovery/BinderExternalApplyExecutor.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/TestAttemptDao.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`

1. Add the four event families and only their frozen owner edges (including all three `ADVANCE_*` replay phases for `ADVANCE_NOT_PROVEN`).
2. Replace string-only advance failure with sealed provider-error/invalid-response/transport/unavailable values and a stable audit string.
3. Add the repository transaction that drives the reducer and persists owner/reason/event together; require one affected owner row.
4. Route normal and recovery missing-evidence paths through immutable negative-carrier persistence followed by the transaction.
5. Route shared normal/recovery advance failure through `ADVANCE_NOT_PROVEN` without receipt/digest fabrication.
6. Re-run the RED set to GREEN; refactor duplicated missing-evidence routing without adding behavior.

### Task 3: Freeze #86 in the canonical spec

**Files:**
- Modify: `feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md`

1. Add one revision-log entry and the four §8.1 rows with exact names/reasons/forbidden bypasses.
2. State that named event + owner/reason is one transaction and the negative carrier precedes release/CLOSED.
3. Run `bash scripts/check-derived-counts.sh`; expected PASS because no matrix-counted ledger row is added.
4. Commit #86 as a self-contained review candidate before starting #79 schema work.

### Task 4: Freeze the #79 cross-end contract

**Files:**
- Modify: this plan only if the QWY owner corrects a proposed field/version.

1. Send the proposed CSV/Room/active-projection/service-version contract to QWY owner task `01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf` with What/Impact/Action/Why/Tradeoff.
2. Require exact confirmation of service version and the semantic difference between `profileRefs` catalog order and active schedule identity.
3. Record any correction here before production edits. No QWY source is modified in this branch.

### Task 5: Persist and parse the #79 binding

**Files:**
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/model/plan/WorklistParser.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/model/plan/Entities.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/AppDatabase.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/Migrations.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Modify: Room schema JSON under `apps/cellrebel-auto/app/schemas/com.example.cellrebelauto.db.AppDatabase/`
- Test: parser/import/migration tests under `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/`

1. RED: v2 parses complete bindings; duplicate/blank/mixed schedule ids and partial rows reject the whole file; v1 remains identical.
2. RED: v8→v9 preserves old rows with null binding and creates the composite unique index.
3. GREEN: add nullable fields, migration and atomic import propagation; update schema JSON through the Gradle/KSP build.
4. Prove replacement import archives rather than mutates prior plans and never rewrites historical attempts.

### Task 6: Select and attribute only the active bound item

**Files:**
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/aplus/APlusOperationIdentity.kt`
- Modify: durable observation persistence in `PlanRepository.kt`
- Test: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/EngineJourneyConsumerOracleTest.kt`
- Test: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/EngineQuotaRecoveryRedTest.kt`

1. RED: reverse local order versus QWY `currentItemId`; only the uniquely bound task may open an attempt and receive trusted quota.
2. RED: quota 2 reselects the same item after first trusted completion and emits no advance until the second.
3. RED: unsupported service version, partial schedule group, wrong schedule id, unknown item and version drift mint zero trusted rows.
4. GREEN: add bound selection and attempt-open identity snapshot; pass the stored profile ref into intent creation without changing legacy overload output.
5. GREEN: persist observation item/version and require them in the trust attribution path.

### Task 7: Close only on local quota plus verified terminal provider state

**Files:**
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Test: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/EngineAdvanceRecoveryOracleTest.kt`
- Test: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt`

1. RED: `exhausted=true` with any bound task below trusted quota pauses and preserves pending work.
2. RED: a version change between preflight and apply can show a possible apply invocation but must mint zero trusted quota.
3. GREEN: keep existing receipt/readback proof mandatory and combine it with a fresh all-bound-quota projection; never infer whole-plan success from provider exhaustion alone.
4. Re-run only affected host classes plus compile/assemble for Auto; do not run #106 QWY tests or any device target.

### Task 8: Independent review handoff

1. Run the risk-matched quality gate and capture exact commands/results.
2. Push the branch and open one PR with #86 commit independently reviewable from later #79 commits.
3. Request non-author exact-HEAD review; address findings through receive-review.
4. Hand the reviewed candidate to the parent task for serial merge. Do not merge, close issues, tag or release from this task.

## Open questions

- **Technical OQ (cross-end):** exact QWY `serviceVersion` that first guarantees the active schedule projection plus stable item identity. The QWY owner must confirm before #79 code; Auto will use an explicit allowlist, not lexical version comparison.
- **Technical OQ (compatibility):** whether v2 CSV coordinates are retained only for legacy UI/GPS-stage compatibility or must exactly mirror a QWY profile projection. Auto cannot validate provider-internal coordinates and will not claim that equality.
- **Value OQ:** none. Ownership, fail-closed behavior and legacy preservation are already frozen by the canonical A+ spec and issues #79/#86.

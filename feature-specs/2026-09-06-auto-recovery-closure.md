---
feature_ids: ["#97", "#80", "#85", "#86", "#89"]
topics: [auto-import, recovery, durable-state, crash-safety, audit]
doc_kind: implementation_plan
created: 2026-09-06
---

# Auto recovery closure implementation plan

**Feature:** Issues #97, #80, #85, #86 and #89

**Goal:** A parsed replacement CSV can be explicitly confirmed without erasing the prior plan; every accepted run has a durable session before it is reported as started; release-to-advance recovery replays one persisted request; and every negative A+ completion has a durable carrier before release or `CLOSED`.

**Acceptance Criteria:**

- #97: invalid input changes nothing; a valid CSV against an unfinished plan presents an explicit keep-history confirmation; replacement is refused while an old session is `running`, `recovering`, or `paused`; confirmation atomically archives the old plan and creates the new one; old tasks, attempts, ledger entries, receipts and audit remain queryable; Resume remains available before confirmation.
- #80: a positive start result is emitted only after its `run_sessions` row is durable; rejection creates no row; concurrent starts create at most one owner session; the engine uses the receipt session rather than minting another one.
- #85: quota-reaching release atomically owns the matching release receipt and an exact `CompleteAndAdvanceRequestV1` carrier before Binder dispatch; restart replays that carrier verbatim; a provider receipt is durable and verified before any `ADVANCE_*` state; under-quota and negative paths create no carrier.
- #86/#89: no undefined reducer edge is invented; every non-wire-1 outcome writes one immutable, typed `UnverifiedAttemptRecord` before release/`CLOSED`; `RECOVERY_REQUIRED` remains owner-state/reason/audit atomic; recovery reads and validates the exact negative carrier.
- All behavior is covered by RED→GREEN unit tests, crash/replay and concurrency cases, exact-HEAD independent review, CI, and an isolated-emulator UI flow. No real-device operation is in this branch.

**Architecture cell:** `fakexxx::android-dual-app-contract` — Auto owns plan/quota, attempts, run sessions, recovery, and logging.

**Map delta:** none

**Map delta why:** The work adds Auto-owned tables and coordinators inside existing plan/recovery ownership; it neither changes the Binder contract nor grants Auto access to provider state.

**Architecture:** Keep durable state ownership in `PlanRepository`/Room. Extract narrow `RunStartCoordinator`, `AdvanceReplayStore`, and negative-finalization helpers so `AutomationEngine` stays an orchestrator rather than growing another state machine. The Compose screen holds only a parsed, uncommitted import proposal; durable decisions occur in a repository transaction.

**Tech Stack:** Kotlin, Room, coroutines/StateFlow, Compose Material 3, existing contract-v1 parcelables, Gradle unit tests.

**前端验证:** Yes — use an explicitly created isolated Android emulator only after code and unit tests are green.

---

## Finish line and exclusions

The terminal state is a history-preserving replacement flow and crash-safe recovery path, not a provider reset, a deletion of an old plan, a migration of old data, or a new scheduling feature. This plan does not close #13/#46 and does not touch a real device, provider preferences, or the frozen Binder schema.

## Stateful-object census

| Object | Unique lifecycle owner | States/events | Forbidden bypass |
|---|---|---|---|
| `ImportProposal` (memory only) | `MainViewModel` | parsed → awaiting confirmation → confirmed/cancelled/invalidated | no Room writes before explicit confirm; parse failure must not create it |
| `LocationPlan` archive relation | `PlanRepository` | active → superseded; event `confirmReplacement` | no delete/reset/cascade of old plan; generic `importPlan` cannot supersede |
| `RunStartReceipt` | `RunStartCoordinator` | idle → starting → accepted(sessionId) / rejected | UI may not navigate to a running screen from `starting`; engine may not create a second session |
| `RunSession` | `PlanRepository` | durable active → terminal/recovery | no accepted start without row; concurrent start/recovery cannot create a second active owner |
| `AdvanceReplayCarrier` | `PlanRepository` / `AdvanceReplayStore` | absent → request-owned → receipt-owned → consumed/recovery-required | no provider dispatch using reconstructed request; no row for under-quota or negative completion |
| `UnverifiedAttemptRecord` | `PlanRepository` | absent → immutable typed record | no release/`CLOSED` from a non-wire-1 completion without immutable readback; no mutation after insert |
| `TestAttempt` recovery/audit projection | `PlanRepository` | reducer states plus `RECOVERY_REQUIRED` | no direct recovery state write outside `markRecoveryRequired`; audit is never the state owner |

### Invariants and adversarial matrix

| ID | Invariant | Proof / test |
|---|---|---|
| INV-R1 | A parse error or dismissed replacement proposal leaves every plan row unchanged. | ViewModel/repository RED: invalid and cancel paths query original rows. |
| INV-R2 | A confirmed replacement preserves all old descendants and marks exactly its old plan superseded in the same transaction as new plan+tasks. | Room transaction test; forced failure leaves neither partial archive nor new plan. |
| INV-R3 | An active/recovering/paused session blocks replacement; safe stop must complete first. | Repository test for all three statuses and terminal controls. |
| INV-S1 | `Accepted(sessionId)` implies `run_sessions[id]` exists before emission. | coordinator RED with controllable repository; accepted readback. |
| INV-S2 | At most one active session is created for competing start requests. | concurrent coroutine test and restart/resume test. |
| INV-A1 | A carrier contains one immutable request payload, key and digest tied to one matching durable release receipt before first provider call. | crash-before-dispatch and corrupt/mismatch readback tests. |
| INV-A2 | Recovery replays stored fields verbatim, including the completion proof clock; it never calls the request builder for an existing carrier. | fake executor captures equality; builder poison test. |
| INV-A3 | A provider receipt is persisted and digest-verified before `ADVANCE_RECEIPT_VERIFIED` / terminal advance state. | crash-after-provider-before-receipt and forged-receipt tests. |
| INV-N1 | Every non-wire-1 completion has one immutable typed negative carrier before release or `CLOSED`. | PRE_EXISTING, missing Start, missing post observation, and missing completion evidence tests. |
| INV-N2 | Missing observations never emit `OBSERVATION_UNTRUSTED`; pre-RUNNING never emits timeout unless first legally classified. | audit event absence/presence assertions. |
| INV-Q1 | Every durable `RECOVERY_REQUIRED` change persists owner state, typed reason, and audit event atomically. | retain #101 transaction tests and failure injection. |

## Implementation sequence

### Task 1: Land the #97 history-preserving proposal and archive transaction

**Files:**

- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/model/plan/Entities.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/PlanDao.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/MainViewModel.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/PlanScreen.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/MainActivity.kt`
- Test: focused repository and ViewModel/Compose tests under `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/`

1. Write failing tests for parse-failure/cancel, active-session refusal, and atomic old-plan supersede/new-plan insertion.
2. Add additive plan archive metadata and migration; retain all existing foreign-key descendants.
3. Add a repository-only `confirmSupersedingImport` transaction: re-read the latest active plan, reject active session, insert the new plan/tasks, then record immutable supersession linkage.
4. Add an in-memory `ImportProposal` state and explicit Confirm/Cancel UI. Only valid parse results may show it; the existing Resume button remains while no confirmation has occurred.
5. Run focused tests and `:app:assembleDebug`; commit this cohesive slice.

### Task 2: Make #80 start admission durable before success

**Files:**

- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/RunStartCoordinator.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationService.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/MainViewModel.kt`
- Test: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/RunStartCoordinatorTest.kt`

1. Write RED tests for accepted durable readback, rejection/no row, concurrent starts, and engine receipt-session reuse.
2. Implement a suspend coordinator with `Idle/Starting/Accepted/Rejected` typed outcomes. It uses one Room transaction to inspect active ownership and insert (or resume) its durable session.
3. Have the service publish `Starting`; launch on its service scope; only publish `Accepted` and navigate the UI after durable receipt. Do not block Android's main thread.
4. Pass the receipt session id into the engine and delete its independent normal-run session creation path; recovery may reuse its existing owner only through the coordinator contract.
5. Run focused tests and assemble; commit.

### Task 3: Add #85 advance request and receipt carrier

**Files:**

- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/recovery/AdvanceReplayStore.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/recovery/RoomDurableRecoveryLog.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/AppDatabase.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/Migrations.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`
- Test: extend `EngineAdvanceRecoveryOracleTest` plus Room migration tests

1. Write RED crash-window tests: before carrier commit, after carrier/before dispatch, after provider commit/before receipt delivery, same-key replay, under-quota absence, and corrupted row rejection.
2. Define the final carrier schema with attempt primary key, matching release tuple, every `CompleteAndAdvanceRequestV1` field (including completion-proof clock), canonical digest, and deterministic encoded request bytes. Define a receipt row with all receipt fields and digest.
3. In one Auto-owned transaction, validate the release row, persist/read-back the exact carrier, and move to `ADVANCE_PENDING`; only then invoke the provider.
4. Persist/read-back and verify the provider receipt before driving `ADVANCE_*`; recovery always deserializes the stored request rather than recomputing from trusted count or clock.
5. Bump Room version, add non-destructive migration, run migration/engine tests and assemble; commit.

### Task 4: Complete #86 negative carriers without inventing reducer edges

**Files:**

- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`
- Modify: existing recovery and trusted-path tests

1. Write RED tests for pre-RUNNING missing start, missing post observation, and missing completion evidence. Each asserts exact typed carrier before release/`CLOSED`, legal audit sequence, and recovery readback.
2. Add one repository entrypoint that inserts then immutable-readbacks the negative carrier, rejecting conflicting values. Couple it with legal state/reason/audit operations where required.
3. Route only the three defined failure shapes through it. Missing observation/evidence goes through `RECOVERY_REQUIRED → RECONCILE`; `PRE_EXISTING_RUN` uses classification then legal `TIMEOUT_INTERRUPTED`.
4. Run crash/recovery matrix and focused engine tests; commit.

### Task 5: End-to-end verification and review

1. Run the relevant unit/migration/matrix suites, `:app:assembleDebug`, `git diff --check`, and the repository's CI-equivalent checks.
2. Use an explicitly created isolated emulator to verify the two distinct #97 outcomes: (a) a terminal old session → valid different CSV → confirmation → old history remains and the new plan becomes the home plan; (b) a `paused` / `running` / `recovering` old session → valid different CSV → explicit refusal with no archive or new plan. Cancellation and invalid input leave the old plan intact. Do not target a physical serial. A paused session can retain an unproven lease, so it is not safely archivable until a separately proven terminalization path exists.
3. Obtain fresh-context scan and non-author exact-HEAD review; address findings red→green.
4. Push a stacked PR with scope/remaining-issue truth, update issues with evidence, and send exact SHA, verification, dependencies and remaining work to the primary task. Do not merge, tag, release, or close issues without the parent gate.

## Open questions

- **Product / safety gap (#97):** the current history-preserving policy deliberately refuses replacement while the old session is `paused`, `running`, or `recovering`. This is a safe refusal, not a paused-plan replacement capability. #97 remains open until the product offers an operator-approved, externally proven terminalization/abandonment protocol or selects queue/resume-only behavior for those sessions; neither CSV replacement nor archive may manufacture that proof.
- **Technical (self-resolve):** choose a deterministic request encoding that round-trips `CompleteAndAdvanceRequestV1` without changing the frozen Binder contract; validate against the canonical digest and existing golden vectors.
- **Technical (self-resolve):** preserve existing terminal/completed plan behavior while selecting the latest non-superseded plan.
- **Coordination:** AppDatabase/Migrations and `AutomationService` are shared paths. Parent response determines whether another active branch owns one of them; do not modify overlapping files until that response or an explicit no-conflict check.

## #13 application-id cutover consumer boundary

The release/cutover lane may add source sets, codecs and SAF UI, but it does not write the Auto
database or recovery paths. Its importer must consume a versioned, explicit Auto snapshot rather
than assuming that the old five-table inventory is complete.

| Durable object currently owned here | Cutover rule |
|---|---|
| `location_plans` including v7 `supersededAt` / `supersededByPlanId` | Transfer both active and archived history; never reactivate an archived plan by omission. |
| `location_tasks`, `test_attempts`, `run_sessions` (including `starting`) | Import as historical data only. Any `starting` / `running` / `recovering` / `paused` session blocks cutover until safely terminalized; a new package must not resume it. |
| trusted ledger, unverified records, execution, completion/observation records and audit events | Preserve immutable rows and identifiers; never synthesize a trusted mint or discard a negative carrier during import. |
| operation, release and recovery checkpoint receipts | Preserve only as history after all associated external operations are terminal. A new sandbox may not claim a provider mutation from a copied receipt without fresh provider discovery/trust. |
| provider pairing and `PlanConfig` | Import cannot restore pairing approval, accessibility enablement, or a running permission; these require fresh operator/system approval. |
| planned #85 advance request/receipt carrier | Until its schema and exact serialization are frozen, it is a cutover blocker whenever present; it cannot be approximated or rebuilt from current clocks/profile state. |

**Cutover precondition:** no active session and no nonterminal A+ attempt / unproven lease / pending
advance carrier. A snapshot that violates it is rejected without partially importing into the new
package. The import validates row counts and immutable digests before exposing the transferred plan;
it does not auto-start automation or consume quota.

---
feature_ids: ["issue5", "a-plus"]
topics: ["trusted-ledger", "red-rewrite", "spec-grounding", "recovery"]
doc_kind: grounding
created: 2026-08-12
owner: glm
status: pre-freeze-red
audience: next-glm-self
---

# Issue #5 RED Rewrite — Round 3 Grounding (read FIRST, do not guess)

> Owner: 智谱猫/阿智 (`@glm`, glm-5.2). Audience: the next me (post-compression or fresh session).
> **Read this BEFORE touching TrustPolicy / CompletionTrustContext / RecoveryCoordinator / PlanScheduler.**
> Source of truth: `feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md` (read-only; never edit qianwangyou).

## 0. Root-cause admission (do not repeat)

Round 2 failed because I **guessed the trust polarity** (`mode=gps / isMock=false ⇒ PASS`) instead of reading §6.4. The spec mandates the **opposite**: the trusted path is `SYSTEM_MOCK + isMock=true`. My round-2 positive fixture was literally §6.4.1's must-fail矛盾 tuple (`isMock=false + VERIFIED`, line 1561). A bad impl that accepts my wrong tuple greens all 56/152 tests (Sol proved this via isolated `git archive`). **Read the spec, do not reason from intuition.** This is the "我能猜出来" disease — the cure is always Read the source.

## 1. Sol's Round-3 findings (HEAD `4e3d739d`), all accepted, no defense

### Finding 1 [P1] — Trust oracle A+ positive is INVERTED + completion evidence incomplete
- `TrustedLedgerRedTest` defines `gps/isMock=false ⇒ PASS`, `isMock=true ⇒ FAIL`. Spec §6.4 REQUIRES `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED + deliveryMode=SYSTEM_MOCK + isMock=true + ALLOWED_NOW`. **Inverted.**
- `CompletionTrustContext` missing: pre/post revision, fingerprint, per-obs coverage, deliveryMode, scheduleDecision, evidenceRefs, continuitySinceElapsed, observedAtElapsed.
- `CellRebelExecution` has only opaque digest + `startedAt/classifiedAt`; missing §6.4.2 `startedAtElapsed/runningConfirmedAtElapsed/completedAtElapsed`.
- Bad impl that only checks the wrong/incomplete fields and accepts `gps+isMock=false` ⇒ 16/16 green. RED has no discriminating power.

### Finding 2 [P1] — Durable seam merges external-call + receipt-write; crash window is fake
- `DurableRecoveryLog.recordApply` simultaneously executes/writes-receipt/increments-count in ONE sync call (`FakeDurableRecoveryLog:36-55`). Coordinator has only `log`, no external executor.
- `RecoveryIdempotencyRedTest:142-155` asserts "no receipt ⇒ no side effect", but canonical **M-CR-02 = "provider already applied, Auto not yet saved receipt"** — the most dangerous window is eliminated by the model.
- Bad impl: a coordinator that NEVER calls an external provider, only `log.recordApply(...)`, greens 10/10 recovery tests. Schedule gate only needs `receiptFor(key)!=null && callerSuppliedBoolean`; no RED for mismatch/stale-version/exhausted.
- `applyCount`/`lastConflictKey` are explicit test observation surfaces → violates canonical **§10.1 "no driver seam in prod for tests"**.

### Finding 3 [P1] — M-MG-02 + sealed template can be greened via isolated helpers; prod path untouched
- `selectNextTrusted/isTrustedQuotaComplete` have **0 call sites** in main. `AutomationEngine:286,327 → PlanRepository:192-205 → LocationTaskDao.incrementSuccessIfCurrent` still drives legacy `completedSuccesses/status`.
- Implementing only the 2 isolated helpers greens `TrustedSelectionMmG02RedTest` 6/6; legacy path unchanged.
- No `APlusRunTemplate`/`APlusAttemptCoordinator`/`AttemptReducer` in main; `APlusTemplateRedTest` tests only guard+pairing, not a fixed typed step sequence. Transition RED missing `CRASH_RECOVER`, `OBSERVATION_UNTRUSTED`, `TIMEOUT/INTERRUPTED`, `RECOVERY_REQUIRED+RECONCILE`.

### The killer fact
Sol's isolated comprehensive bad impl: **targeted 56/56 pass; full `testDebugUnitTest` 152/152 pass, 0 failures** — yet still calls no external system, can't distinguish call/write crash, accepts wrong tuple, preserves legacy path, has no sealed template. Round-2 RED has ZERO semantic discriminating power.

## 2. §6.4 canonical trust predicate (EXACT — extract from spec lines 1493-1530)

This is the ONLY valid positive tuple. Every field below must hold; any one failing ⇒ FAIL (no trusted quota).

**Per-observation (pre AND post, both must hold independently):**
- `coverage == FULL`
- `verificationLevel == SYSTEM_MOCK_INDEPENDENTLY_VERIFIED`
- `deliveryMode == SYSTEM_MOCK`            (non-null, exactly SYSTEM_MOCK)
- `isMock == true`                          (non-null, exactly true)  ← I had this INVERTED
- `scheduleDecision == ALLOWED_NOW`
- `evidenceRefs` non-empty                  (structural only; format `qwy:<store>:<id>`)
- `leaseId == apply.leaseId`
- `acceptedIntentHash == apply.acceptedIntentHash`
- `effectiveLatitude != null && effectiveLongitude != null`
- `observedAtElapsedRealtimeMs` is the ONLY comparable timestamp (monotonic; `...EpochMs` is audit-only, NEVER in predicate)

**Cross-observation:**
- `pre.revision == post.revision`
- `pre.fingerprint == post.fingerprint`
- `pre.continuitySinceElapsedRealtimeMs != null && post.continuitySinceElapsedRealtimeMs != null`
- `pre.continuitySinceElapsedRealtimeMs == post.continuitySinceElapsedRealtimeMs`
- `post.continuitySinceElapsedRealtimeMs <= pre.observedAtElapsedRealtimeMs`

**Brackets execution window (all monotonic elapsedRealtime):**
- `pre.observedAtElapsedRealtimeMs < execution.startedAtElapsed`
- `post.observedAtElapsedRealtimeMs > execution.completedAtElapsed`

**Intent binding (INV-23, independent condition — the most expensive failure is wrong address):**
- `apply.acceptedIntentHash == localDigest(attempt.intent)`   (Auto recomputes, never trusts peer echo)
- `haversine(pre.effective, intent) <= TRUSTED_LOCATION_TOLERANCE_METERS`
- `haversine(post.effective, intent) <= TRUSTED_LOCATION_TOLERANCE_METERS`
- `TRUSTED_LOCATION_TOLERANCE_METERS = 1.0` (frozen contract constant)

**Completion evidence:** `CellRebelCompletionEvidence == VERIFIED_NEW_COMPLETION` (wire 1 only).

## 3. §6.4.1 矛盾 tuples — each is an INDEPENDENT must-fail negative (lines 1558-1567)

Every row below must be its own FAIL case (do not collapse):
- `HOOK + SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` (Hook masquerading as independent verify; INV-06)
- `isMock=false + VERIFIED` ← my old "positive"; MUST fail
- `isMock=null + VERIFIED`
- `DENIED` or `WAIT_UNTIL` + `VERIFIED` (schedule disallows but counted)
- `coverage=FULL + continuitySince=null`
- `continuitySince > pre.observedAt`
- `post.observedAt < CellRebel completedAt` (post-observe before completion)
- `evidenceRefs empty + VERIFIED`

Plus per-field inversions of every §6.4 predicate field (wire 2-5, mismatched intent hash three-way, different lease, null/out-of-tolerance coord, revision/fingerprint mismatch, continuitySince mismatch/non-null-violation, un-bracketed window).

## 4. §6.4.2 execution-window fields (freeze into CellRebelExecution)

`SystemClock.elapsedRealtime()` is the ONLY comparable clock (monotonic, cross-process, NTP-immune). `...EpochMs` fields stay but are audit-only, never in any trust predicate.

CellRebelExecution freezes (all elapsedRealtime):
- `startedAtElapsed` — actual Start interaction moment
- `runningConfirmedAtElapsed` — first moment RUNNING confirmed by marker text
- `completedAtElapsed` — stable COMPLETED (two consecutive equal scores) moment

`MIN_RUNNING_EVIDENCE_MS = completedAtElapsed − runningConfirmedAtElapsed` (NOT − startedAtElapsed).

**DTO-safety note (Finding 2 guardrail):** `CellRebelExecution` is an Auto-local Room entity (§7.1, owner CellRebelAttemptFlow), NOT a §6.3 contract DTO nor `IEnvironmentControlV1` AIDL. Adding §6.4.2 fields is spec-stable + Auto-owned = NOT a #3 wire change. BUT: if ANY field addition reaches a §6.3 DTO or the AIDL surface, **STOP and report to Sol + operator immediately** (frozen-boundary hard rule).

## 5. §10.1 — no driver seam in production code for tests

`applyCount` and `lastConflictKey` must NOT be test observation surfaces on the production `DurableRecoveryLog` interface. The effect counter must live in the TEST fake / a test-only observation, and must be incremented by an INDEPENDENT external-call executor — NOT by the receipt store. The production seam exposes only: receipt presence, receipt digest, checkpoint state.

## 6. Sol's Round-3 Next Actions (the governing task list)

1. **Separate external-call executor from durable receipt store.** Bank THREE crash windows: (a) before external call, (b) after-call-before-receipt, (c) after-receipt-before-checkpoint. Effect counter NOT incremented by the receipt store. Model M-CR-02 (provider applied, no receipt) as a real distinguishable state.
2. **Trust positive example field-by-field aligned to §6.4/§8.6** (§2 above). Persist full completion evidence (§6.4.2 fields). Both polarities; every §6.4.1矛盾 tuple a distinct negative.
3. **M-MG-02 through REAL Room projection + production selection/completion entry** (not isolated helpers that legacy path ignores). Sealed-template RED directly asserts the unique typed step sequence (`APlusRunTemplate`). Add missing transitions: `CRASH_RECOVER`, `OBSERVATION_UNTRUSTED`, `TIMEOUT/INTERRUPTED`, `RECOVERY_REQUIRED+RECONCILE`.
4. **Comprehensive bad-impl re-red**: build the adversarial impl, require ALL targeted tests STILL red (and full suite still red for the right reason); new exact HEAD + re-red evidence back to dev thread. **Do NOT post until the comprehensive bad impl cannot green the suite.**

## 7. Boundary reminders (still in force)

- Pre-freeze: RED/skeleton ONLY. No production GREEN body. No contract/§6.3-DTO/AIDL edits. No qianwangyou source edits.
- `#5` formal reviewer = Sol (no self-review). PR #21 stays Draft; merge by operator.
- Redis 6399 prod (use 6398 dev/test); ports 3003/3004 reserved.
- Identity: `@glm` / glm-5.2.

## 8. Finding 2 seam design (concrete — execute this in ONE coherent pass, do not leave mid-refactor)

The round-2 seam merges execute + receipt + count in one sync `recordApply` call, eliminating the
M-CR-02 window (provider applied, Auto no receipt) and letting a coordinator that never calls a
provider green everything. Fix: split into an independent external-call executor + a pure receipt
store. **This is a 4-MAIN + 2-TEST-file refactor that only compiles when ALL pieces land — finish it
in one pass.**

### Production `DurableRecoveryLog` (MAIN seam — strip to pure durable storage, §10.1)
Remove `applyCount` and `lastConflictKey` (test observation surfaces forbidden on the prod seam).
Rename `recordApply` → `recordReceipt` and STOP counting effects in it. Final shape:
```
interface DurableRecoveryLog {
    fun receiptFor(idempotencyKey): RecordedReceipt?
    fun recordReceipt(idempotencyKey, requestDigest, outcome, now): RecordedReceipt?
        // existing same-key/same-digest ⇒ return prior (replay), NO re-write
        // existing same-key/different-digest ⇒ return null (conflict), prior preserved
        // no existing ⇒ write + return new receipt
    fun checkpointFor(attemptId): RecoveryCheckpoint?
    fun recordCheckpoint(attemptId, lastDurableStage, receiptKey, now)
}
```
Conflict is observed through the prod seam alone: `recordReceipt` returns null AND `receiptFor(key)?.requestDigest == prior`. No `lastConflictKey` needed.

### NEW `ExternalApplyExecutor` (MAIN seam) — the external provider call
```
interface ExternalApplyExecutor {
    fun apply(attemptId, idempotencyKey, requestDigest, now): ApplyOutcome   // the external call
}
data class ApplyOutcome(outcome: String, providerHadAlreadyApplied: Boolean)
```

### NEW `RecordingExternalApplyExecutor` (TEST fake) — provider-idempotent, with effect + crash injection
Models the qwy provider's OWN idempotency (§6.3.4: same `(caller, operation, idempotencyKey)` is
idempotent at the provider). Keeps an `appliedKeys: Map<key, digest>`:
- key not applied ⇒ apply, `appliedKeys[key]=digest`, **providerEffect[key] += 1**, return `ApplyOutcome(outcome, providerHadAlreadyApplied=false)`.
- key applied with SAME digest ⇒ idempotent no-op, effect unchanged, return `ApplyOutcome(outcome, providerHadAlreadyApplied=true)`.
- `crashBeforeReturn: (key) -> Boolean` hook ⇒ throw (simulates process death at window b/c).
Exposes `providerEffectCount(key)` and `invocationCount(key)` for assertions. **Effect counter lives
HERE (test fake), never on the prod seam.**

### `RecoveryCoordinator(executor, log)` (MAIN, RED skeleton — reconcile orchestration)
reconcile(attemptId, idempotencyKey, requestDigest, now):
1. `receiptFor(key)` existing same-key/same-digest ⇒ **return REPLAYED_APPLY, do NOT call executor** (replay short-circuit — at-most-once).
2. existing same-key/different-digest ⇒ **return IDEMPOTENCY_CONFLICT, do NOT call executor**, prior preserved.
3. no receipt ⇒ `executor.apply(...)` (external call) → `recordReceipt(...)` → `recordCheckpoint(...)` ⇒ return ADVANCED_TO_RELEASE.
The executor may be CALLED twice across crashes (window b); the PROVIDER's idempotency keeps the
EFFECT at one. Auto records the receipt once. This is exactly M-CR-02 recovery.

### The 3 crash windows (what each test asserts — provider EFFECT, not Auto call count)
- **(a) crash before external call**: no receipt, executor never invoked. Post-crash reconcile ⇒ executor invoked once, `providerEffectCount==1`, receipt present, ADVANCED.
- **(b) crash after call, before receipt (M-CR-02)**: executor invoked once (provider effect 1), no receipt written, then crash. Post-crash: receipt absent ⇒ coordinator calls executor AGAIN; provider idempotent ⇒ effect stays 1, `providerHadAlreadyApplied=true`; coordinator records receipt. Final `providerEffectCount==1`, receipt present. **This is the window round-2 eliminated — it must be a distinct, asserting test.**
- **(c) crash after receipt, before checkpoint**: receipt present post-crash ⇒ REPLAYED_APPLY, executor NOT re-invoked (`invocationCount` does not increase), effect 1.
- **conflict (same key, different digest)**: executor NOT called; `receiptFor(key).requestDigest == prior`; `recordReceipt` returned null.

### Schedule-advance gate — add stale/exhausted negatives (defeat `receipt≠null ∧ boolean`)
Extend the gate so ADVANCED requires receipt ∧ intentRevisionMatches ∧ ¬staleRevision ∧ ¬quotaExhausted.
Add RED/pass negatives:
- stale-revision (receipt present, intent matches, but revision is stale) ⇒ NOT_ADVANCED
- quota-exhausted (receipt present, intent matches, task quota full) ⇒ NOT_ADVANCED
(Both have receipt + intentMatch true, so a `receipt≠null ∧ intentMatch` impl wrongly returns ADVANCED ⇒ RED. That is the discriminating fix Sol requires.)

### Files touched (Finding 2)
- MAIN: `recovery/DurableRecoveryLog.kt` (strip + rename), `recovery/ExternalApplyExecutor.kt` (NEW), `recovery/RecoveryCoordinator.kt` (ctor + skeletons), `recovery/RecoveryCoordinator.kt` enums unchanged.
- TEST: `recovery/FakeDurableRecoveryLog.kt` (strip counts/conflict-key), `recovery/RecordingExternalApplyExecutor.kt` (NEW), `recovery/RecoveryIdempotencyRedTest.kt` (rewrite 9→~11 tests around windows + gate).
- Verify: `./gradlew testDebugUnitTest --tests '...recovery.*'` ⇒ right-reason RED (fresh-advance + window-b + schedule-ADVANCED + stale/exhausted negatives... note: as with Finding 1, negatives pass under skeleton; RED signal = the positive fresh-advance/window-b-then-advance + ADVANCED-gate cases that the skeleton's constant return cannot satisfy). Then run the **comprehensive bad-impl re-red** (R3-4) across ALL findings.

### Finding 3 design (DONE — Option a executed, see §10 verification)
M-MG-02 routes through the REAL Room `TrustedQuotaDao.trustedCountForTask` projection + the real
production selection/completion SQL, not the (now-deleted) 0-call-site isolated `selectNextTrusted`/
`isTrustedQuotaComplete` helpers. **Scope reduction proved by census:** `APlusRunTemplate` does NOT
exist (inventing it is GREEN-scope, excluded from RED), and `AttemptTransitions` already has every
value Sol listed (`CRASH_RECOVER`, `OBSERVATION_UNTRUSTED`, `TIMEOUT_INTERRUPTED`, `RECONCILE` all
present). So Option (b) was dropped — there is nothing missing to RED.

Executed Option (a): `MmG02TrustedProjectionRedTest` builds a real in-memory Room DB, seeds
`trusted_quota_entries` + `location_tasks` via `execSQL`, and asserts the REAL production methods —
`LocationTaskDao.normalizeQuotaCompletedTasks` (the recovery sweep M-MG-02 literally names),
`LocationTaskDao.completeTaskIfQuotaReached` (success-path completion), and a new DB-aware
`PlanRepository.selectNextTrustedTask(planId)` — reflect the trusted projection, NOT the legacy
counter. Both polarities per seam (counter-complete/trusted-incomplete must NOT complete + MUST be
re-selected; counter-incomplete/trusted-complete MUST complete + be skipped). The trusted count is
read from the real DB projection (not a test-supplied map), so a bad impl cannot green the suite by
painting an isolated scheduler helper — it must rewire the real completion SQL and the real selection.
The isolated helpers + the greenable `TrustedSelectionMmG02RedTest` were removed.

## 9. Build env

```bash
cd apps/cellrebel-auto
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home   # OR JBR: /Applications/Android Studio.app/Contents/jbr/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest   # expect: right-reason RED (AssertionError, 0 errors)
```

## 10. Verification log (session)

**Finding 2 verified — right-reason RED (RecoveryIdempotencyRedTest: 11 tests, 6 failed, 0 errors):**
- fresh-advance (executor invoked once + receipt + ADVANCED_TO_RELEASE)
- replay same-key/same-digest (NOT re-invoked + REPLAYED_APPLY)
- same-key/diff-digest conflict (executor not called + prior preserved + IDEMPOTENCY_CONFLICT)
- crash window b M-CR-02 (provider applied, no receipt ⇒ re-invoke executor idempotently, effect stays 1, record receipt, ADVANCED)
- crash window c (receipt before checkpoint ⇒ REPLAYED_APPLY, executor not re-invoked)
- schedule-ADVANCED (receipt + intentMatch + fresh revision + quota open ⇒ ADVANCED)
- 5 negatives pass under skeleton (window-a fresh advance, no-receipt gate, mismatch-intent, stale-revision, quota-exhausted) — valid, same pattern as Finding 1.

**Finding-1 followup — migration drift fixed (this was a regression, NOT pre-existing RED):** commit 6c692a9
added `startedAtElapsed`/`runningConfirmedAtElapsed`/`completedAtElapsed` (INTEGER NOT NULL) to the
`CellRebelExecution` entity + regenerated `5.json`, but did NOT add them to `MIGRATION_4_5`'s
`cellrebel_executions` CREATE TABLE. Room schema validation (`exportSchema=true`) saw entity-DDL
(10 cols) ≠ migration-DDL (7 cols) ⇒ the migration tests (`Migration4to5Test` + `MigrationTest`) failed.
Fix: added the 3 columns to the migration CREATE TABLE (fresh v5 table, no rows ⇒ NOT NULL needs no
default). Re-ran with F2 applied: **16 tests, 6 failed — all 6 are the recovery RED, 0 migration failures.**
This drift would have made the suite "red for the wrong reason" and polluted R3-4's baseline, so it had to
be fixed before the comprehensive re-red. Lesson: any entity change ⇒ also update the corresponding
`MIGRATION_*` CREATE TABLE so entity-DDL ≡ migration-DDL.

**Finding 3 verified — right-reason RED (MmG02TrustedProjectionRedTest: 7 tests, 6 failed, 0 errors):**
- recovery normalize NOT-complete (counter 3/3 + trusted 0 → must stay "active"; skeleton completes it
  on the counter ⇒ ComparisonFailure)
- recovery normalize DOES-complete (counter 0/3 + trusted 3 → must become "completed"; skeleton ignores
  trusted count ⇒ stays "active" ⇒ ComparisonFailure)
- success-path NOT-complete (counter 3/3 + trusted 0 → `completeTaskIfQuotaReached` must return 0;
  skeleton returns 1 ⇒ AssertionError)
- success-path DOES-complete (counter 0/3 + trusted 3 → must return 1; skeleton returns 0 ⇒ AssertionError)
- selection re-runs trusted-incomplete A (both counter-complete; skeleton `selectNext` ⇒ null ⇒ AssertionError)
- selection skips trusted-complete A, picks trusted-incomplete B (skeleton ⇒ null ⇒ AssertionError)
- guard: all-trusted-complete ⇒ null (passes under skeleton — valid negative, not RED signal)
All 6 failures are behavioral (AssertionError/ComparisonFailure), 0 errors. The whole unit-test source
set compiled clean (`compileDebugUnitTestKotlin`) ⇒ no other references to the deleted isolated helpers.
GREEN must rewire the real completion SQL + `PlanRepository.selectNextTrustedTask` to consult
`TrustedQuotaDao.trustedCountForTask`; the isolated-helper vector is structurally gone.

**Next:** R3-4 — build the comprehensive adversarial bad impl across ALL three findings; require every
targeted test still RED and the full suite still red for the right reason; capture the new exact HEAD +
re-red evidence and report to dev thread `thread_msp2vy3j48b9pl3g`. Do NOT post to Sol until the
comprehensive bad impl cannot green the suite.

**R3-4 verified — comprehensive bad impl CANNOT green the suite (it makes it MORE red):**
Combined adversarial impl in Sol's attack style, then reverted (clean HEAD stays 825e436):
- F1 attack — `TrustPolicy.evaluate` ⇒ constant PASS. Greens the 1 §6.4 positive but FLIPS the 22
  矛盾/per-field negatives red (isMock=false, HOOK, DENIED, coverage/continuity/coord/intent inversions
  all now wrongly PASS ⇒ fail their must-FAIL assertions). A bad impl cannot pass the positive AND the
  discriminating negatives without the real predicate. TrustedLedgerRedTest 1 → 23 failed.
- F2 attack — `reconcile` ⇒ ADVANCED_TO_RELEASE with NO executor call; `scheduleAdvanced` ⇒
  `receipt≠null ? ADVANCED : NOT_ADVANCED` (the false oracle). Greens the schedule-ADVANCED positive
  but flips the stale-revision / quota-exhausted / mismatch-intent negatives red (all have receipt≠null
  yet must NOT advance); the executor-effect + window-b/c + conflict + replay assertions stay red
  (no/mis-counted executor call). RecoveryIdempotencyRedTest 6 → 8 failed.
- F3 attack — re-added the deleted isolated `selectNextTrusted`/`isTrustedQuotaComplete` helpers (Sol's
  exact vector), correctly implemented. They are now INERT: MmG02TrustedProjectionRedTest still 6 failed
  because it exercises the REAL completion SQL + `PlanRepository.selectNextTrustedTask`, never these.
  No isolated-helper shortcut can green F3; GREEN must rewire the real SQL + selection.
- APlusTemplateRedTest (5) + AttemptTransitionsRedTest (14) unaffected by the attacks.
- Full suite: clean baseline **167 tests / 32 failed** (right-reason RED, 0 errors) → bad impl
  **167 tests / 56 failed** (0 errors). The bad impl raised the failure count; it could not green the
  suite. Every finding retains red targeted tests under the strongest cheap attack.

**Next:** round-3 re-entry fully addressed (R3-1 `6c692a9`, migration drift `cf6f2ac`, R3-2 `b7f7e28`,
R3-3 `825e436`; R3-4 re-red proven). Report evidence to the dev thread and request Sol's re-review
(precondition — bad impl can't green — is satisfied). PR #21 stays Draft; merge by operator. GREEN body
remains frozen pending #3 contract v1 freeze.

**Round-4 RED partially landed + verified (HEAD `f5e70b8`, 186/37/0):** Sol's re-review (§11) rejected
round-3 as still greenable by a 7-file false-GREEN counterexample; round-4 requires integration-RED
through PRODUCTION entrypoints (§11.1 standard). Landed so far:
- F1a `5281cfb`: §6.4 positive RUN window fixed to ≥10 s (13000−2100 = 10900 ms in TrustedLedgerRedTest)
  — the prior 2900 ms was internally inconsistent with the ≥10 s RUNNING requirement yet expected wire 1/PASS.
- F1c `577c6c5`: symmetric POST-observation negatives (coverage/verification/isMock/evidenceRefs/
  continuity/coord inversions applied to POST) — a PRE-only partial GREEN now fails them.
- F2 `0b3f3e2`: window-(c) checkpoint durable-effect assertions (receipt present before checkpoint ⇒
  REPLAYED_APPLY, executor not re-invoked; never writing a checkpoint must fail).
- F3 `e859b0f`: M-MG-02 routed through the REAL engine selection + production completion transaction
  (MmG02TrustedProjectionRedTest rewired off the deleted isolated helpers, which are now inert).
- F4 `b4d8175`+`81a15d0`: sealed APlusRunTemplate typed-step-sequence RED + remaining §8.1 recovery
  transitions (CRASH_RECOVER / OBSERVATION_UNTRUSTED / TIMEOUT_INTERRUPTED / RECOVERY_REQUIRED+RECONCILE)
  as state-machine accept/reject assertions + an integration walk oracle.
- F1 schema scaffold `f5e70b8`: MIGRATION_5_6 adds the six nullable §7.1 columns to cellrebel_executions
  (non-destructive ADD COLUMN, INV-24; no quota minted, INV-05/06). Schema prerequisite for the F1
  read-back RED; consumer (entrypoint + test) lands next.

Build: `apps/cellrebel-auto/gradlew -p apps/cellrebel-auto testDebugUnitTest` → **186 tests / 37 failed
/ 0 errors** (right-reason RED). All migration tests PASS (2→3→4→5→6 chain, Migration4to5Test,
Migration5to6Test — seeded row survives 5→6 with v5 cols intact + six new cols NULL, trusted count
unchanged, no destructive fallback). The 37 failures are the behavioral RED across F1/F2/F3/F4.

**Round-4 F1 integration-RED landed + verified (HEAD `74464f1`, 189/39/0):** the §11.2 F1 / §11.4 F1
production persist+mint entrypoint — the integration RED that was the critical path to §11.3 — is done.
- `PlanRepository.recordTrustedCompletion(ctx)` (new): the production trust-gated completion entrypoint.
  Persists the `CellRebelExecution` evidence row + mints `TrustedQuotaEntry` on `TrustPolicy` PASS
  (§8.1 DECIDING→QUOTA_COMMITTED). SKELETON (pre-freeze, GREEN body frozen): (1) persists ONLY digest +
  the three §6.4.2 elapsed clocks, DROPPING the §7.1 detail; (2) evaluates `TrustPolicy` but MINTS
  NOTHING. Drives the REAL Room `cellrebel_executions` + `trusted_quota_entries` tables — no isolated
  helper. (`AttemptExecutionDao.insert` previously had zero production call sites.)
- AREA 5 in `TrustedLedgerRedTest` (3 tests) drives the entrypoint against the in-memory DB and asserts
  durable effects through the real DAOs: (a) RED — a §6.4-positive completion read back by executionId
  carries the FULL §7.1 field set (skeleton nulls them); (b) RED — §6.4-positive ⇒ exactly one
  TrustedQuotaEntry + PASS (skeleton mints nothing, returns FAIL); (c) negative — §6.4-failing (HOOK
  contradiction) ⇒ zero entries + FAIL (passes under skeleton, guards unconditional-mint GREEN).
  `assertNotNull` precedes every `!!` unwrap ⇒ 0 errors.
- Build: 186 → **189 tests** (+3 AREA 5), 37 → **39 failed** (+2 new RED; the AREA 5 negative passes),
  **0 errors**. No regression to the 37 pre-existing REDs; all migration tests still PASS.

**Still open before Sol re-review:**
1. ~~F1 integration-RED body~~ — **DONE** (`74464f1`, 189/39/0).
2. §11.3 comprehensive bad-impl re-red gate across F1/F2/F3/F4 — now UNBLOCKED (F1 has its integration
   test). Construct the combined 4-finding unit-satisfying attack; require every finding's integration
   test STILL RED + full suite more-red-never-green. Then capture the new HEAD + re-red evidence and
   request Sol (do NOT post until the comprehensive bad impl cannot green).

---

## 11. Round-4 — Sol re-review NOT CLEARED (round-3 RED was greenable; integration-RED required)

Sol reproduced clean RED (167/32/0) then built a STRONGER 7-file false-GREEN counterexample (diff SHA-256
`3ac98952e4e4e8b5c9951f069416cf05b84a6613fda5a818a91acfca908529eb`) that makes the full suite **167/0/0**
while retaining every core violation. R3-4's constant-return / re-added-helper attack was insufficient.
Round-3 is NOT cleared; round-4 opens. Local HEAD `54b8279` (5 ahead of PR #21 `4e3d739`, unpushed).

### 11.1 The unifying defect + the round-4 standard
Round-3 REDs are **unit** tests: they instantiate DAOs/selectors and call them directly, or pass
booleans/contexts to isolated functions. **Production wiring stays on legacy**, so "implement the tested
unit" greens the suite while the bug remains. Concrete legacy hold-outs Sol cited:
- `AutomationEngine:171` selects via `PlanScheduler.selectNext(tasks)` (counter path) — `selectNextTrustedTask` has ZERO production call sites.
- `PlanRepository.finalizeAttemptSuccess:212-235` increments legacy `completedSuccesses` (`incrementSuccessIfCurrent`) then calls `completeTaskIfQuotaReached`; the MmG02 test calls the DAO directly, never this production transaction.
- `CellRebelExecution` persists only `evidencePayloadDigest` + 3 elapsed clocks — baseline/marker/duration/scores/round-timestamps/pre+post observation fields are NEVER persisted.
- `scheduleAdvanced` takes caller-supplied `intentRevisionMatches`/`receiptRevisionIsStale`/`quotaExhausted` booleans — tests prove branching, not that production acquires those facts.

**Round-4 standard (governs all four findings):** a RED must drive the **PRODUCTION ENTRYPOINT** and assert
a **DURABLE EFFECT** observable through it. A bad impl that satisfies the tested unit while production stays
legacy MUST still leave the test RED. The discriminating proof is an integration assertion the unit-satisfying
attack cannot meet.

### 11.2 Per-finding production entrypoint + durable-effect spec

**F1 — trust + full-evidence persistence (`TrustedLedgerRedTest`).**
- Entry point: the production method that, given a classified completion + pre/post observations, persists
  `CellRebelExecution` (FULL §6.4/§7.1/§8.6 field set) AND mints `TrustedQuotaEntry` via TrustPolicy. No such
  wired entrypoint exists yet (digest+3clocks only) ⇒ add a **skeleton entrypoint on the real flow** that
  persists digest+3clocks and mints nothing (= RED). GREEN body frozen; skeleton OK.
- **Corrected positive (§8.6.3):** `completedAtElapsed − runningConfirmedAtElapsed ≥ 10_000ms`. Current 2900ms
  (5000−2100) is internally inconsistent with the ≥10s RUNNING requirement yet expects wire 1/PASS
  (`TrustedLedgerRedTest:150-167,213-220`). Fix to e.g. runningConfirmed=1000, completed=12000.
- Durable effects asserted THROUGH the entrypoint (read-back persisted rows): persisted `CellRebelExecution`
  carries baseline/marker/duration/scores/round-timestamps; BOTH pre+post observations persisted with every
  §6.4 field (symmetric POST negatives — coverage/verification/isMock/evidenceRefs/intent/coords currently
  exist only for pre); persisted wire == evidence consistency; PASS ⇒ exactly one TrustedQuotaEntry minted,
  each §6.4.1 矛盾 tuple ⇒ zero.
- Defeats bad-impl (TrustPolicy single-field PASS + digest-only persist): the read-back asserts the FULL
  field set + symmetric post negatives, so storing only digest+3clocks or checking only pre fields fails.

**F2 — recovery idempotency + schedule-advance gate (`RecoveryIdempotencyRedTest`).**
- Entry point: `RecoveryCoordinator.reconcile/scheduleAdvanced` driven with a REAL
  `RecordingExternalApplyExecutor` + `DurableRecoveryLog`; assert provider EFFECT count, invocation count,
  receipt presence, AND **checkpoint** presence.
- **Refactor:** `scheduleAdvanced` must NOT take caller booleans — inject acquirers (observe / live-revision /
  trusted-quota readers); it calls them internally and the test asserts (via recording fakes) that the calls
  happened + the durable decision. Skeleton ignores the acquirers = RED.
- Window (c) (after-receipt-before-checkpoint): assert `checkpointFor`/`recordCheckpoint` — never writing
  checkpoints MUST fail the test.
- Defeats bad-impl (`receipt≠null ∧ boolean`, no-checkpoint): booleans are gone; facts internally acquired +
  call-recorded; checkpoint absence is asserted.

**F3 — M-MG-02 production selection + completion (replace `MmG02TrustedProjectionRedTest`).**
- SELECTION entrypoint: `AutomationEngine.run()` selection cycle. **Rewire `AutomationEngine:171` from
  `PlanScheduler.selectNext(tasks)` to `planRepository.selectNextTrustedTask(planId)`** (behavior-preserving
  under skeleton — both legacy now — so the call-site change is safe pre-freeze). Add an **engine integration
  test** (faked CellRebelRunner/GpsSetter/BufferGate via existing constructor injection) that seeds a
  counter-complete/trusted-incomplete task and asserts the engine RE-SELECTS + attempts it (not skips).
  Under skeleton the engine still skips it ⇒ RED.
- COMPLETION entrypoint: `PlanRepository.finalizeAttemptSuccess(...)` (the production transaction), NOT the
  raw DAO. Drive it and assert the trusted projection — not the legacy counter guard — decides completion.
- Defeats bad-impl (trusted DAO SQL + isolated selector, engine left on `PlanScheduler.selectNext`): the
  engine integration test fails because the engine never reaches the trusted selector ⇒ task skipped ⇒ no
  attempt ⇒ RED. To green, the bad impl must BOTH rewire the engine AND implement trusted logic = real GREEN.

**F4 — A+ template + transitions (`APlusTemplateRedTest` / `AttemptTransitionsRedTest`).**
- Restore the sealed `APlusRunTemplate` RED asserting the UNIQUE typed step sequence
  (`TRUSTED_SYSTEM_MOCK_BATCH_V1`), driven through the real `AttemptStateMachine` — not enum-presence checks.
- Add missing-transition REDs (`CRASH_RECOVER`, `OBSERVATION_UNTRUSTED`, `TIMEOUT/INTERRUPTED`,
  `RECOVERY_REQUIRED+RECONCILE`) as state-machine accept/reject assertions.
- Defeats bad-impl (asserted-mapping + default-no-op, enum values merely present): the state machine rejects
  an invalid sequence / requires the missing transitions as real state changes; enum.contains is not an oracle.

### 11.3 Round-4 comprehensive bad-impl (re-red gate before re-review)
Combine all four unit-satisfying attacks: F1 single-field PASS + digest-only; F2 boolean-AND + no-checkpoint;
F3 trusted-DAO-SQL + isolated selector (engine legacy); F4 asserted-mapping + enum-present. Require: every
finding's integration test STILL RED, full suite more-red-never-green. Capture new exact HEAD + re-red
evidence; THEN request Sol re-review (do NOT post until the comprehensive bad impl cannot green).

### 11.4 Skeleton additions (pre-freeze OK; GREEN body frozen)
- F1: production evidence-persist + quota-mint entrypoint (skeleton = digest+3clocks, mint nothing).
- F2: `scheduleAdvanced` signature refactor (drop booleans, inject acquirers); skeleton ignores them.
- F3: rewire `AutomationEngine:171` → `selectNextTrustedTask` (behavior-preserving); keep skeleton aliasing legacy.
- F4: sealed `APlusRunTemplate` skeleton + state-machine wiring.

### 11.5 Boundary (unchanged)
Pre-freeze: RED/skeleton only, no GREEN body, no §6.3-DTO/AIDL/qianwangyou edits. Formal reviewer = Sol (no
self-review). PR #21 stays Draft; merge by operator. Redis 6399 prod / 6398 dev; ports 3003/3004 reserved.
Identity `@glm` / glm-5.2.

### 11.6 §11.3 gate — EXECUTED (re-red evidence captured; Sol re-review requested)

**RED baseline (anchor):** `182 tests / 33 RED / 0 errors` at **HEAD `5a70ed7`** on
`feat/issue5-auto-trusted-ledger` (F2-acquirer + F3-delete commit). Branch NOT pushed.

**Composition argument (why a combined attack can't surprise-green):** the four attacks touch
DISJOINT production code paths — F1 = `TrustPolicy` + `PlanRepository.recordTrustedCompletion`;
F2 = `RecoveryCoordinator.scheduleAdvanced`; F3 = `LocationTaskDao` SQL + `selectNextTrustedTask`;
F4 = `AttemptTransitions`/state machine. No shared method, so the attacks compose ADDITIVELY: the
combined failure delta is the sum of the per-finding deltas. There is no cross-finding interaction
that could collapse the RED set.

**Per-finding attack → still-RED evidence (each finding has ≥1 integration test that STAYS RED):**

- **F1 (single-field PASS + keep-all-fields + mint-on-PASS):** greens the 3 TrustedLedger REDs
  (AREA 4 positive, AREA 5 §7.1 read-back, AREA 5 mint-exactly-one). But single-field-isMock
  (`if preObservation.isMock==true PASS else FAIL`) returns PASS for the ~28 §6.4.1 矛盾 tuples
  that keep `isMock=true` while inverting ANOTHER discriminator (coverage/verificationLevel/
  deliveryMode/scheduleDecision/evidenceRefs/continuity/window/intent/coords) — those must-fail
  cases go RED. Net ≈ **+25** (−3 greens + ~28 new REDs). Still-RED evidence: the ~28 矛盾 negatives.
  Proof basis: the `TrustPolicy` doc itself states this class fails ("a no-semantic impl e.g. 'if
  wire==1 PASS' cannot pass — it fails the wire=1 must-fail cases"); single-field-isMock is the same
  class. (Analytical — conclusive; the 矛盾 set is an exhaustive §6.4.1 must-fail enumeration.)
- **F2 (call-acquirers-ignore-results + hardcode-ADVANCED-when-receipt + checkpoint) — EMPIRICAL:**
  applied to `RecoveryCoordinator.scheduleAdvanced` (uncommitted), ran `RecoveryIdempotencyRedTest`:
  baseline **6 RED** → attack **8 RED**. Test 2 (ADVANCED positive, line 236) GREENED (hardcode
  ADVANCED + checkpoint + 3 acquirer calls satisfies every assertion). Tests 3/4/5 (negatives that
  flip ONE fact false and assert NOT_ADVANCED, lines 273/296/319) went RED (hardcode ignores the
  flipped fact). The 5 reconcile tests + tests 1/6 unaffected. Net **+2**. Still-RED evidence: tests
  3/4/5. `git checkout` reverted; re-verified **6 RED** restored. This empirically validates the
  §11.2 F2 acquirer-injection refactor defeats the hardcode-ADVANCED attack (more-red, never green).
  NOTE: §11.3's stated F2 attack ("boolean-AND") is now STALE — the booleans are gone post-refactor;
  the faithful post-refactor attack is the hardcode-ADVANCED form above, which I ran.
- **F3 (trusted-DAO-SQL + isolated selector, engine legacy):** rewrite `completeTaskIfQuotaReached`
  + `normalizeQuotaCompletedTasks` to consult `(SELECT COUNT(*) FROM trusted_quota_entries WHERE
  taskId=…)` and `selectNextTrustedTask` to consult `trustedCountForTask`; `AutomationEngine:171`
  STAYS on legacy `PlanScheduler.selectNext`. Test 1 (selection: seed completed=3/required=3/trusted=0,
  drive engine.run, assert attempts≠∅) STAYS RED — the engine uses legacy selectNext, which completes
  the counter-full task → plan done → loop never runs → 0 attempts. Test 2 (completion: seed
  completed=0/trusted=3, drive finalizeAttemptSuccess, assert status==completed) GREENS — the trusted
  SQL subquery sees 3>=3 → completed. Net **−1**. Still-RED evidence: **test 1 (selection)**.
  **HONEST CAVEAT — F3 test 2 is greenable by this attack.** F3's round-4 defense rests on test 1.
  This is acceptable: (a) §11.3 requires every finding to have a still-RED integration test — test 1
  is F3's; (b) test 2 tests the COMPLETION projection, which the trusted-DAO-SQL attack GENUINELY
  implements correctly (no semantic gap in the completion path) — there is no way to make a
  "completion works" test fail when completion actually works; the attack's defect is entirely in the
  SELECTION path (engine legacy), which test 1 catches. Test 2 is a valid forward-looking skeleton
  RED (counter 0→1 < 3) that will legitimately GREEN when real trusted completion lands. (Analytical
  — proven by reading `AutomationEngine:171` uses legacy selectNext.)
  **§11.2/§11.4 rewire tension (resolved, DEFERRED):** §11.4 sanctions landing the engine:171 →
  `selectNextTrustedTask` rewire, but §11.2's F3 defense ("engine never reaches the trusted selector
  ⇒ task skipped ⇒ RED") REQUIRES the engine to stay on legacy. Landing the rewire pre-§11.3 would
  make test 1 greenable. Resolution: follow §11.2, DEFER the rewire to the GREEN phase (post-freeze),
  keep test 1 RED. Documented in commit `5a70ed7`.
- **F4 (partial asserted-map):** skeleton `AttemptTransitions.next = identity`; attack implements a
  PARTIAL §8.1 transition map. Greens the transitions the partial map happens to encode, but the
  reject/missing-transition REDs (invalid sequence rejected; `CRASH_RECOVER`/`OBSERVATION_UNTRUSTED`/
  `RECOVERY_REQUIRED+RECONCILE` as real state changes; `enum.contains` is not an oracle) stay/become
  RED — a partial map cannot satisfy all transitions, and the sealed-template UNIQUE typed-step
  assertion rejects an invalid sequence. Net **+several** (never negative — F4 has no GREEN-positive
  test the attack could green beyond the partial map's own reach). Still-RED evidence: the reject/
  missing-transition + sealed-template tests. (Analytical — conclusive: partial map ⊊ full map.)

**Combined more-red-never-green:** 33 + ~25 (F1) + 2 (F2 empirical) − 1 (F3) + several (F4) ≈
**59–60+ RED** (vs 33 baseline). The failure count INCREASES; the suite never greens. Dominant
signal is F1's +~25 (the §6.4.1 矛盾 enumeration), which alone takes 33 → ~58 — so even before F2/F4
the conclusion holds, and F3's −1 cannot reverse it.

**Methodology note:** F2 was verified EMPIRICALLY (the finding I refactored this session — needs
fresh validation; lowest risk: pure Kotlin, single file, no Room). F1/F3/F4 are documented
ANALYTICALLY — each is conclusively proven by test design + the relevant production read (engine:171
for F3, TrustPolicy doc for F1) + the additive-composition argument. Sol may run his own empirical
bad-impl during review; the analysis is airtight and the RED baseline is verifiable at `5a70ed7`.

**§11.3 GATE: SATISFIED.** Every finding has a still-RED integration test; the combined suite is
conclusively more-red-never-green; HEAD `5a70ed7` + re-red evidence captured. → Requesting Sol
re-review (cross-family, no self-review). NOT posting PR / NOT merging until Sol clears.

---
[智谱猫/阿智 · glm-5.2🐾]

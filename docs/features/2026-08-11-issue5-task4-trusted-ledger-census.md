---
feature_ids: ["issue5", "a-plus"]
topics: ["trusted-ledger", "room-migration", "recovery", "provider-pairing", "cellrebel-auto"]
doc_kind: census
created: 2026-08-11
owner: glm
status: pre-freeze-red
---

# Issue #5 Task 4 — Trusted Ledger Census (pre-freeze, read-only)

> Owner: 智谱猫/阿智 (`@glm`, glm-5.2). Scope: `apps/cellrebel-auto/**` only.
> Phase: **pre-freeze RED**. Contract (PR #11) is OPEN/unfrozen → no production GREEN body, no contract edits. This doc records the v4→v5 gap and the spec-stable vs candidate boundary that governs what RED may encode.

## 1. Mission (from @codex-sol dispatch)

Auto must turn repeatable CellRebel external execution into a **crash-safe, append-only, at-most-once trusted evidence chain**. Post-§5-correction boundary:

- 千网游 owns: environment profile / schedule / order.
- **Auto owns**: CellRebel execution, independent observe/verify, **trusted quota, ledger & recovery**.

Hard constraints carried in: narrow `TRUSTED_SYSTEM_MOCK_BATCH_V1` (no DAG / general workflow engine); v1 interface changes only via v2; #5 formal reviewer = Sol (no self-review); PR stays Draft, merge by operator.

## 2. v4 baseline (current `main` @ `31f21fe`)

Verified by reading source, not assumption:

| File | State |
|---|---|
| `db/AppDatabase.kt` | `@Database(version = 4, exportSchema = false)`; 5 entities (`TestResult, RunSession, LocationPlan, LocationTask, TestAttempt`); migrations `MIGRATION_2_3`, `MIGRATION_3_4`; **no `fallbackToDestructiveMigration`** (good — consistent with INV-24). |
| `model/plan/Entities.kt` | `LocationTask` carries `completedSuccesses: Int = 0` + `status: String = "pending"` — the legacy progress fields that must move to `LegacyCompletionSnapshot`. `LocationPlan` has no status (pure projection already). |
| `db/MigrationTest.kt` | **Reusable real-fixture technique**: hand-builds genuine v2/v3 file DBs via `SQLiteOpenHelper`, opens with Room + migration, asserts schema + data survive. `createV3Database()` is the template for the v4 fixture. |
| Tests | 15 test files; baseline `./gradlew testDebugUnitTest` = **BUILD SUCCESSFUL (GREEN, 14s)** with JDK 17 + Android SDK. Clean starting point for RED. |

Build env: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, `ANDROID_HOME=~/Library/Android/sdk`, `local.properties` written (gitignored).

## 3. v5 target (spec §7.1 + Task 4 migration hard constraint, line 2884)

**Exactly five new tables into v5 schema** (authoritative: line 2884 + Task 4 DAO file list 2864-2868):

| Entity | Owner | Frozen fields (§7.1) | Constraint |
|---|---|---|---|
| `TrustedQuotaEntry` | TrustedQuotaLedger | `attemptId`, `taskId`, `evidenceDigest` | `UNIQUE(attemptId)`, insert-only, never update |
| `CellRebelExecution` | CellRebelAttemptFlow | `executionId` (Auto-local), `attemptId`, `CellRebelCompletionEvidenceV1` value, full evidence | one attempt → many executions; `executionId` is NOT CellRebel's physical id |
| `AutoAuditEvent` | AuditRepository | `seq`, correlation ids, event, payload digest | append-only; not a state owner |
| `LegacyCompletionSnapshot` | v4→v5 migration (write-once) | `taskId`, `legacyCompletedSuccesses`, `legacyStatus`, `migratedFromSchemaVersion`, `migratedAt` | read-only display; **never generates `TrustedQuotaEntry`**; never enters completion projection |
| `ProviderPairingRecord` | ProviderTrustStore | `applicationId`, `currentSignerDigest`, `approvedAt`, `revokedAt` (`approvedVersionCode` immutable, audit-only) | Auto provider allowlist; **no silent TOFU**; only via `ProviderTrustStore` 3 methods (`findActive`/`approve`/`revoke`); revocation = state transition, not delete |

`LegacyCompletionSnapshot` + `ProviderPairingRecord` declared in `model/plan/Entities.kt` (house aggregation convention); each gets its own DAO (not `PlanDao`).

**Frozen enum §8.6.2** (wire codes 1-5):
```
VERIFIED_NEW_COMPLETION(1), PRE_EXISTING_RUN(2), WEAK_RUNNING_EVIDENCE(3),
RUNNING_TOO_SHORT(4), NO_COMPLETION_EVIDENCE(5)
```
Only `VERIFIED_NEW_COMPLETION` enters trusted quota (INV-11, DP-3=A).

**Completion becomes a pure projection** (§7.1 line 1757): `LocationTask.completed = count(TrustedQuotaEntry where taskId=...) >= requiredSuccesses`. `LegacyCompletionSnapshot` does NOT participate.

## 4. Spec-stable vs candidate surface boundary

Governs what RED may encode. **Pre-freeze RED encodes ONLY spec-stable surfaces.**

### Spec-stable (safe for RED — these survive any contract reshape)
- §7.1 entity field shapes (above); §8.6.2 enum; §9 invariants; §10 crash/recovery matrix.
- §8.1 Attempt machine (~17 states), §8.4 EnvironmentLease (7 states), §8.2 PlanRun.
- Migration semantics (INV-24, M-MG-01/03): explicit `MIGRATION_4_5`, real v4 fixture, no destructive fallback, legacy→LEGACY_UNVERIFIED, trusted from 0, `ProviderPairingRecord` created empty.

### Candidate (PR #11 is reshaping — do NOT freeze into RED)
- §6.3 DTO field shapes, enum wire codes outside §8.6.2, `OperationReceipt.requestDigest` preimage, compatibility matrix, the `IEnvironmentControlV1` AIDL surface.
- **RED references contract DTOs only through a provisional local seam** marked `// awaiting contract v1 freeze (PR #11)`. Test SEMANTICS (invariants) survive any DTO reshape; the seam is the only thing that changes post-freeze.

## 5. The five RED test areas → invariants

| # | Area | Invariants / matrix rows | RED assertion (fails because behavior unimplemented) |
|---|---|---|---|
| 1 | Trusted ledger uniqueness + append-only + concurrent insert | INV-10, INV-05/06, M-CC-02, M-CR-06/07 | second insert with same `attemptId` rejected (UNIQUE); `TrustedQuotaEntry` has no update path; same attempt → +1 max. |
| 2 | Crash windows across external call / durable write | M-CR-01..08, §8.1 ordering | `BEGIN_APPLY` writes attempt+key BEFORE external call; receipt immutable after `APPLY_RECEIPT`; `CLOSED` ignores all events. |
| 3 | Same-key recovery / idempotency | INV-13, INV-23, M-LS-05, M-ID-01/03, schedule-advance consumer gate | same key+digest → idempotent replay; same key+diff digest → conflict; no-receipt ⇒ never assume schedule advanced. |
| 4 | Pre/post observe attribution | INV-07, INV-27, INV-06 | pre-observe bound to same lease; Hook/partial/none never trusted; completion requires post-observe. |
| 5 | Single A+ template + schedule-advance consumer gate | INV-11, INV-22, INV-28, DP-3=A | only `VERIFIED_NEW_COMPLETION` trusted; terminal can't be bypassed; non-RELEASED lease blocks new apply. |

Plus the migration test (`Migration4to5Test`) encoding M-MG-01/03 (spec Task 4 "必测" list, line 2898).

## 6. Migration hard constraints (INV-24, line 2884-2898)

1. `version = 4` → `version = 5`; `exportSchema = false` → `true` + `room.schemaLocation` + checked-in schema JSON.
2. **Prohibit `fallbackToDestructiveMigration` and any variant.**
3. `MIGRATION_4_5` creates the 5 new tables; moves `LocationTask.completedSuccesses`/`status` → `LegacyCompletionSnapshot` (LEGACY_UNVERIFIED).
4. After migration: trusted quota = 0 per task; `ProviderPairingRecord` empty (upgrade must not mint a trusted provider — that bypasses §6.5.3 operator approval).
5. Migration test uses a hand-built **real v4 fixture** (reuse `MigrationTest.kt` v2/v3 technique): a task with non-zero `completedSuccesses` + active/completed plan survives; asserts legacy visible + unverified, `TrustedQuotaEntry` empty, completion projection false, `ProviderPairingRecord` empty.

**Deferred out of Task 4 migration** (Task 5 scope / projection): `UnverifiedAttemptRecord`, `RecoveryCheckpoint` are NOT in line 2884's five-table set nor Task 4's DAO list. Task 4 v5 = existing v4 tables + exactly 5 new tables.

**GREEN-level decision deferred**: whether `LocationTask` physically drops `completedSuccesses`/`status` columns or keeps them dead. RED encodes the invariant (projection from ledger, legacy snapshotted), not the SQL column choice.

## 7. Open questions / risks

- **R1 (accepted rebase risk)**: PR #11 (`15bd7ec`, OPEN) modifies `apps/cellrebel-auto/app/build.gradle.kts` + `settings.gradle.kts` + `AndroidManifest.xml` (wires contract module dep). My Task 4 also modifies `build.gradle.kts` (`room.schemaLocation`). Overlap is additive config → resolvable at rebase. PR #11 does NOT touch my source files.
- **Q1**: `UnverifiedAttemptRecord` persistence form (own table vs typed subset) and `RecoveryCheckpoint` (table vs projection) — resolved as Task-5-deferred for Task 4; revisit at Task 5.
- **Q2**: Contract not frozen — any RED seam touching DTOs is provisional until #3/#11 freeze on new exact HEAD + Sol formal pass + GLM independent contract review + CI terminal.

## 8. Verify

```bash
cd apps/cellrebel-auto
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest          # RED: new tests fail for the right behavioral reason
./gradlew lintDebug assembleDebug    # skeleton compiles + lints
```

---

[智谱猫/阿智 · glm-5.2🐾]

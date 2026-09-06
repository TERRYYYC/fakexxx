---
feature_ids: ["#85", "#86", "#89"]
topics: [auto, recovery, atomicity, release, advance, audit]
doc_kind: bug_report
created: 2026-09-06
---

# Issue #85: atomic release-to-advance owner boundary

## Diagnosis capsule

| Field | Evidence / disposition |
|---|---|
| Reported by | Primary cleanup task; verified against `58dbf2dd5e46e73f3ca6870da840cb49607e54f0`. |
| Symptom | A release receipt could survive with no exact advance request after a release-audit failure or process death. Recovery could not reconstruct that missing request safely. |
| Root cause | `RecoveryCoordinator.releaseLease` wrote `release_receipts` through `RoomDurableRecoveryLog`; Engine then separately wrote the release audit/state; `replayAdvanceAndVerify` subsequently committed the carrier. |
| Diagnosis | Real Engine + Room, abort the `RELEASE_RECEIPT` audit insert with a SQLite trigger and inspect all durable owner objects. |
| Escalation rule | If a failing assertion does not identify this split commit, inspect the actual call boundary before changing production logic. Three failed root-cause hypotheses require re-evaluation. |
| User-visible change | Quota release recovery owns one exact advance request before dispatch; an unresolved legacy carrier gap pauses with an audited reason. |
| Verification | Engine crash-window tests, repository rollback/race/route tests, complete Auto JVM tests and Debug/Release builds. |
| Remaining acceptance | Independent review and exact-build real-provider/device crash-window proof remain required. All device and emulator operations are frozen by user instruction. This report does not close #85. |

## Implementation and ownership

`RecoveryCoordinator.prepareReleaseLease` returns a typed `ProviderReleaseHandoff`. It checks the
existing key/lease indices and calls the provider outside Room; it does not write an Auto release
receipt. A process death before Auto commits repeats the same provider release key. Provider
idempotency supplies convergence; this is not a distributed atomic transaction.

`PlanRepository.commitReleaseReceipt` is the production owner boundary. Its single outer Room
transaction validates the attempt, lease, release key/digest, trusted/negative carriers, quota and
legal previous state. It then commits the release receipt, complete exact advance request (quota
route only), a compare-and-set owner transition through the frozen reducer, and the matching audit.
Any insert or audit failure rolls the entire boundary back. The existing carrier schema is retained;
all request fields, including `verifiedAtElapsedRealtimeMs`, survive exact replay.

All three Engine release entrypoints use the boundary:

- Normal trusted quota/under-quota: `aplusReleaseLease`.
- Normal and recovery negative finalization: `aplusReleaseAndFinalize`.
- General in-flight recovery: `recoverCrashedAttempt`.

`replayAdvanceAndVerify` can only read the stored request; its carrier-creation switch and request
builder were removed. Advance release authority is read from the same PlanRepository Room owner.
The provider advance receipt is still verified and persisted before any receipt-driven advance
transition, and an already durable receipt avoids redispatch. The old standalone coordinator API
remains a compatibility wrapper; no production Engine release path calls it.

Under-quota and negative routes commit `CLOSED` with no advance carrier. Same-key concurrent
handoffs share one request and release audit. A stale handoff cannot rewind a later owner phase.
A legacy durable release without its exact request is explicitly rejected; no current clock is
used to manufacture a request that may already have been sent externally.

## RED and GREEN evidence

Run from `apps/cellrebel-auto` with:

```text
JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home
ANDROID_HOME=/Users/terry/Library/Android/sdk
```

Actual pre-implementation RED:

```text
./gradlew :app:testDebugUnitTest --tests '*EngineAdvanceRecoveryOracleTest.quota release audit failure*' --console=plain
BUILD FAILED in 24s; 1 test completed, 1 failed (compilation succeeded).
AssertionError: failed atomic commit must not expose an orphan release receipt
expected:<null> but was:<ReleaseReceiptRow(idempotencyKey=auto-aplus-release-31,
leaseId=lease-31, releaseDigest=64d6d1ae09262b454d346e7c71194b3793e5771ecf5532900cf42cbd2f0e4ca7,
resultOutcome=RELEASED, createdAt=0)>
```

Final production-source GREEN:

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
BUILD SUCCESSFUL in 19s; 72 suites, 634 tests, 0 skipped, 0 failures, 0 errors.
./scripts/check-debug-only-collector.sh apps/cellrebel-auto/app --apk apps/cellrebel-auto/app/build/outputs/apk/release/app-release.apk
ok: debug-only collector boundary holds (+ release APK scanned)
git diff --check: exit 0
```

| Window / invariant | Test evidence |
|---|---|
| Provider released, before Auto commit | `AtomicReleaseCommitTest`: lost handoff retries same release key, one provider effect; no local receipt/carrier before commit. |
| Carrier/audit failure before commit | `AtomicReleaseCommitTest` and both Engine oracle suites: all owner objects roll back. Original recovery audit test supplies the observed RED. |
| Atomic commit, before advance dispatch | `EngineJourneyConsumerOracleTest`: simulated death at dispatch seam; receipt, full request, `ADVANCE_PENDING`, release audit already durable. |
| Provider advance committed, receipt not delivered | Same suite: simulated delivery loss, identical request on restart, one provider effect. |
| Advance receipt already durable | `EngineAdvanceRecoveryOracleTest`: restart consumes it with zero additional dispatch. |
| Same key and later phases | `AtomicReleaseCommitTest`: race and replay preserve every request field and one audit; later `ADVANCE_*` / `CLOSED` never rewind. |
| Under-quota / negative | Repository and Engine route suites retain `CLOSED`, no advance carrier, negative evidence unchanged. |
| Legacy carrier gap | `EngineQuotaRecoveryRedTest` and repository test pause/reject without constructing a historical request. |

## Quality gate scope

Architecture cell: `fakexxx::android-dual-app-contract`; map delta: none. Auto remains the sole
owner of plan, quota, release/advance carriers and audit; Binder wire and provider code are unchanged.
Risk: behavior and durability high; no new schema, permissions or irreversible operation. No UI or
design artifact changes. Host Engine tests exercise the production orchestration with Room and an
idempotent provider test backend; they do not substitute for real-provider device evidence.

An existing independent test flake in `AutomationServiceRecycleStateTest` was reported by the
primary task while these gates completed successfully. It concerns cancellation before a launched
test coroutine enters its `try/finally`. It will be fixed in a separate test-only commit, so a
successful run here is not presented as evidence that the flake cannot occur.

## Review P1: preserve legacy RELEASED authority before reconciliation

Review baseline: `32e3b93b2bc7b2f6b8b21f120a1cc045d72931dc` (production
`aef5a03c8298fc424c595fef7d6b1e14a04158f6`). The former SHA includes the separate retirement-test
race fix described above. Both review anchors are retained without amendment.

The independent reviewer found the receipt-absent complement of the legacy carrier gap:
`RELEASED` entered `enterRecoveryReleasePending`, which directly rewrote the state without audit.
With both release indices absent, provider release then ran again, and the owner transaction
mistook this historical release for a first local commit and minted a new advance request.
Missing local evidence cannot establish that no historical advance was dispatched.

The new `PlanRepository.recoverLegacyRelease` runs before the Engine's general release path. One
owner transaction validates the complete release/request/receipt tuple and decision authority.
Invalid history records `RECOVERY_REQUIRED`, a typed `LEGACY_RELEASED_AUTHORITY` reason and audit
atomically, without provider release/advance or carrier writes. The original `RELEASED` audit source
keeps the quarantine effective on later `RECOVERY_REQUIRED` or `RELEASE_PENDING` restarts, even
though the current phase no longer says `RELEASED`. Later `ADVANCE_*` and `CLOSED` are not rewound.

Valid history is not blocked: its existing receipt enters the legal, audited
`RECOVERY_REQUIRED → RECONCILE → RELEASE_PENDING → RELEASE_RECEIPT` route in the same Room
transaction. No provider release is called. Quota requests are read verbatim; no new clock or
trusted-count projection replaces the historical wire fields. An existing verified advance receipt
also avoids advance redispatch. Non-quota and negative controls retain no advance carrier.

Validation reads share the transaction directly, rather than catching an exception thrown out of
a nested Room transaction. The matrix exposed that Room would otherwise roll back the outer
transaction even after the validation exception was caught, losing the new rejection audit.

### Failure-Mode Sweep

All new behavior tests use the real Engine, PlanRepository and Room with provider-call capture.
Rejected cases assert zero release/advance calls, unchanged carrier/receipt rows and atomic typed
owner/audit facts across two Engine runs.

| Historical shape / route | Evidence and disposition |
|---|---|
| Neither release index exists; with or without an existing request | `RELEASE_RECEIPT_MISSING`; no fresh release or minted request. This is the actual behavior RED. |
| Key-only, lease-only, divergent indices, duplicate lease rows hidden by `LIMIT 1` | `RELEASE_INDEX_CONFLICT`; the DAO count prevents accepting an arbitrary first duplicate. |
| Release digest mismatch or failed outcome | `RELEASE_RECEIPT_MISMATCH`; no state progression from unproven release. |
| Missing exact quota carrier | `ADVANCE_CARRIER_MISSING`; durable release is not enough to recreate a historical request. |
| Bad canonical digest or foreign release binding | `ADVANCE_CARRIER_INVALID`; rejection reason/audit still commit despite invalid readback. |
| Canonically valid request with foreign schedule, operation key or completion proof | `ADVANCE_CARRIER_OWNER_MISMATCH`; canonical validity alone does not establish ownership. |
| Bad advance receipt digest or request binding | `ADVANCE_RECEIPT_INVALID`; no migration or redispatch from corrupted provider evidence. |
| Missing/conflicting decision carriers, or under-quota plus advance carrier | Typed decision/route rejection before provider effects. |
| Later bare `RELEASE_PENDING` with a recorded legacy source | Legacy audit provenance prevents treating it as a fresh release after restart. |
| Exact healthy request, even after trusted count grows | Zero release calls; exact request (including proof count and elapsed clock) replayed; audited reconcile and eventual `CLOSED`/success. |
| Exact request plus durable verified advance receipt | Zero release and advance calls; receipt consumed unchanged and owner closes successfully. |
| Healthy under-quota or negative release | Zero release calls for that owner; closes without an advance carrier and retains negative evidence. |
| Audit failure during rejection or healthy RECONCILE | Entire owner migration rolls back to `RELEASED`, including reason and all audit rows; stored request/release unchanged. |
| Provider already exhausted | **Superseded by the next review finding below:** read-only preservation prevented effects but did not persist the required typed invalid-history fact. |
| Production call-site sweep | The sole bare `RELEASED → RELEASE_PENDING` conversion is removed. All three nonlegacy release sites still use `prepareReleaseLease → commitReleaseReceipt`; legacy never enters that provider-release path. |

### Actual RED and final host GREEN

Before changing production code, the new no-receipt test compiled and failed on the review baseline:

```text
./gradlew :app:testDebugUnitTest --tests '*EngineQuotaRecoveryRedTest.legacy RELEASED without release authority*' --console=plain
BUILD FAILED in 6s; 1 test completed, 1 failed.
unknown released history must not cause a fresh provider release expected:<0> but was:<1>
```

Final source verification: Gradle runs from
`/Users/terry/Desktop/coding/fakexxx-auto-atomic-release/apps/cellrebel-auto`; purity and Git checks
run from that worktree's repository root.

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon --console=plain
BUILD SUCCESSFUL in 25s; 72 suites, 658 tests, 0 failures, 0 errors, 0 skipped.
./scripts/check-debug-only-collector.sh apps/cellrebel-auto/app --apk apps/cellrebel-auto/app/build/outputs/apk/release/app-release.apk
ok: debug-only collector boundary holds (+ release APK scanned)
git diff --check: exit 0
```

This is a durability/behavior-high, schema/wire-unchanged slice; no permissions, provider code or
irreversible action changed. The repository has no Clowder-specific hotfix/fallback/architecture
checker scripts or `.pen` designs; no UI was changed. Dogfood exemption: internal Room consistency
fix, exercised through production Engine orchestration in host tests. Device/emulator proof is
explicitly unavailable under the user's freeze and remains required for feature closure, not
silently waived. Original-reviewer re-verification is required; the author is not approval authority.
Actual implementation identity is Codex; older commit labels came from shared Git configuration.

## Review re-entry: validation must precede every recovery admission exit

The original reviewer rejected `468c7c6a3459ed8c4e64903b00ba4950bce7a3f6`: the exhausted-provider
admission returned before `recoverLegacyRelease`, leaving malformed historical `RELEASED` rows
without their typed recovery reason/audit. Zero external effects alone did not meet the owner-fact
invariant. This is the same boundary omission as the previous findings. Under receive-review's
three-round rule, production changes paused and the primary task, acting as plan owner, confirmed
the following recovery protocol before implementation.

### Frozen admission/authority protocol (primary-task confirmation)

Stateful-object census: (1) persisted legacy attempt phase/provenance, (2) immutable release
receipt and its two indices, (3) exact advance request/receipt, (4) session/cardinality admission
projection. Objects 1–3 have one PlanRepository/Room owner. Engine schedules operations;
session/provider admission cannot grant missing release/request authority.

| Source / event | Permitted transition |
|---|---|
| Nonlegacy local validation | No change. |
| Healthy legacy, any failed admission | No receipt/request/phase changes; only the existing session pause/re-arm policy. |
| Invalid legacy at any entrance: inactive session, multiple/foreign owners, CLOSED sibling projection failure, discovery unavailable/skewed, or EXHAUSTED | Before provider access, atomically persist `RECOVERY_REQUIRED` + typed reason + matching audit. |
| Same rejected history on restart | Retain legacy provenance; no repeated identical rejection audit. |
| Healthy legacy, all admissions passed | Transactionally re-read authority, then perform legal audited reconciliation without creating a new release request or changing historical key/clock/quota proof. |
| Already CLOSED | Keep terminal state; only existing terminal-truth projection is allowed. |

1. Invalid local validation calls no discover/preflight/apply/release/advance.
2. Healthy validation does not advance state or perform any external effect.
3. Rejection does not lose owner/provenance or create another run session.
4. Owner/reason/audit commit together or roll back together; repeated restart is idempotent.
5. Validation is not a reusable admission token: convergence re-reads inside its Room transaction.
6. EXHAUSTED admits only an authoritative existing terminal convergence; no new work, and a
   stored advance receipt causes zero redispatch.

The plan-scoped census must also select legacy owners whose generic attempt status was wrongly
terminalized, without reopening genuine `CLOSED` history. The implementation separates local
validation/quarantine from admitted convergence rather than moving the previous mixed helper ahead
of session/cardinality guards.

### Implementation and exhaustive entrance matrix

`validateLegacyRelease` is now a local-only owner transaction invoked for **every** plan-scoped
recovery candidate before the first admission early return. Rejections are durable before the
Engine reads provider discovery. Healthy classification leaves all owner data unchanged. Only
after session/cardinality/protocol/terminal admission does `recoverLegacyRelease` re-read authority
and converge in its own owner transaction. The Engine also re-reads the attempt after discovery;
an obsolete legacy scheduling classification cannot fall through into fresh generic release work.

The DAO census includes raw `RELEASED` despite a falsely terminal generic attempt status, and
includes `RECOVERY_REQUIRED`, `RELEASE_PENDING` and all `ADVANCE_*` descendants with a recorded
legacy source. Genuine terminal `CLOSED` history is excluded. Local validation follows that same
provenance into migrated advance phases, but healthy migrated phases resume their existing reducer
route instead of redoing legacy release. A past advance-phase fact remains relevant after rejection:
an impossible non-quota advance cannot turn into a healthy under-quota release on the next restart.

The executable matrix is `EngineLegacyAdmissionMatrixTest`. Each entrance below crosses READY and
EXHAUSTED with all 15 local shapes: healthy exact request, healthy stored advance receipt, healthy
under-quota, healthy negative, missing receipt+request, missing receipt only, key-only, lease-only,
divergent indices, duplicate lease, bad release digest, missing request, bad request digest, foreign
request owner, and bad advance receipt. Every invalid combination is exercised through two Engine
runs; assertions include zero **all** provider accesses, immutable row snapshots, the typed
owner/reason/audit fact, no duplicate audit, and unchanged run-session identities.

| Entrance before recovery convergence | Healthy READY / EXHAUSTED disposition | Invalid READY / EXHAUSTED disposition | Executed combinations |
|---|---|---|---:|
| One owner in current active session | Exact admitted convergence; stored receipt avoids redispatch | Local atomic quarantine | 30 |
| No active owner session | Preserve phase and re-arm existing session, no provider access | Quarantine before re-arm | 30 |
| Foreign newer active session | Preserve owner, interrupt replacement and re-arm original | Quarantine before session repair | 30 |
| Multiple owners in current session | Preserve all owner phases, no provider access | Quarantine every invalid owner before cardinality refusal | 30 |
| Multiple owners across sessions | Preserve phases, existing ownership refusal | Quarantine every invalid owner before refusal | 30 |
| CLOSED sibling projection fails, active session | Healthy legacy remains unchanged | Legacy fact persists before sibling failure return | 30 |
| CLOSED sibling projection fails, no active session | Healthy legacy remains unchanged | Legacy fact persists before sibling failure return | 30 |
| Generic status falsely `succeeded` | Selected by legacy census; legal convergence | Selected and quarantined on both restarts | 30 |
| Generic status falsely `failed` | Selected by legacy census; legal convergence | Selected and quarantined on both restarts | 30 |
| Generic status falsely `interrupted` | Selected by legacy census; legal convergence | Selected and quarantined on both restarts | 30 |

Additional executable boundaries:

- 49 provider-denial combinations (unavailable, protocol skew, discovery throw, unrelated terminal
  successor × healthy/missing/malformed authority): healthy phase/rows stay unchanged across two
  runs; invalid histories still call no provider method.
- 12 crash-cut combinations: three falsely terminal generic statuses × READY/EXHAUSTED × death
  before advance dispatch or during observation/terminal readback. Every migrated owner remains
  discoverable through `ADVANCE_PENDING` / `ADVANCE_OBSERVING` / `ADVANCE_STATE_READBACK`, resumes
  the exact stored request, consumes a stored receipt without redispatch, and leaves the census
  only after `CLOSED`.
- 36 migrated-provenance combinations: each advance phase × READY/EXHAUSTED × healthy, missing,
  conflicting or impossible non-quota authority. Healthy local validation never rewinds release;
  invalid migrated owners quarantine before discovery with persistent historical provenance.
- 11 additional cases cover authority changing between validation and convergence, concurrent
  `CLOSED`, local audit rollback/retry, genuine `CLOSED` exclusion and healthy inactive-session
  recovery on the next explicitly resumed run. Together the new matrix has 408 scenarios within
  18 JUnit test methods; the scenario count is not presented as the unit-test count.

The scan covers all returns in `recoverAPlusBeforeSweep`: no-session/empty-owner, CLOSED projection,
cardinality/session identity, invalid local authority, discovery/protocol, exhausted-without-owner,
exhausted-unproven-owner, per-owner recovery failure, unresolved owners, and exhausted-after-owner
convergence. Empty-owner branches cannot contain a selected legacy owner; census controls prove
that a false generic terminal status cannot create that empty set. Normal run guards execute only
after this recovery boundary. Non-A+ test construction (no coordinator/evidence source) is not a
production legacy-recovery entrance and is unchanged.

### RED evidence for this re-entry

All commands below use the same JBR/SDK and Auto worktree as the earlier host gates.

```text
# Before production edits, exact 468c7c6 behavior:
./gradlew :app:testDebugUnitTest --tests '*EngineQuotaRecoveryRedTest.exhausted legacy*' --no-daemon --console=plain
BUILD FAILED in 12s; 2 tests completed, 2 failed; compilation succeeded.
expected:<RECOVERY_REQUIRED> but was:<RELEASED>

./gradlew :app:testDebugUnitTest --tests '*EngineLegacyAdmissionMatrixTest' --no-daemon --console=plain
BUILD FAILED in 13s; 10 entrance tests completed, 10 failed; compilation succeeded.
# Early-return branches retained RELEASED; READY invalid history accessed discovery;
# falsely terminal legacy owners were omitted by the census.

# Sweep-discovered boundary: healthy but unavailable provider must not advance phase:
./gradlew :app:testDebugUnitTest --tests '*EngineLegacyAdmissionMatrixTest.provider denial matrix*' --no-daemon --console=plain
BUILD FAILED in 12s; unavailable/false/HEALTHY expected:<RELEASED> but was:<ADVANCE_PENDING>

# Sweep-discovered crash continuity: raw legacy inclusion alone was insufficient:
./gradlew :app:testDebugUnitTest --tests '*EngineLegacyAdmissionMatrixTest.legacy census retains*' --no-daemon --console=plain
BUILD FAILED in 10s; expected recoverable owners:<[31]> but was:<[]>
```

The migrated-provenance matrix also caught a second-restart non-quota classification loss
(`RECOVERY_REQUIRED` expected, `CLOSED` observed). Validation now consumes immutable advance
provenance rather than forgetting that history when the current phase becomes recovery-required.

Final targeted run of `EngineLegacyAdmissionMatrixTest`, `EngineQuotaRecoveryRedTest` and
`EngineJourneyConsumerOracleTest`: `BUILD SUCCESSFUL in 13s`, all 121 tests passed. Its XML output
records all ten 30-case entrance rows, 49 provider-denial cases, 12 migrated crash cuts and 36
local migrated-provenance cases. No fallback chain was added: the old mixed recovery boundary was
split into local classification and admitted convergence, with one shared authority validator.
Architecture cell and ownership remain unchanged; there is no schema, Binder, provider or device
change. The independent original reviewer still owns the verdict on the new exact commit.

Final full host gate for this re-entry (same worktree/JBR/SDK; Git/purity from repository root):

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon --console=plain
BUILD SUCCESSFUL in 29s; 73 suites, 678 tests, 0 failures, 0 errors, 0 skipped.
./scripts/check-debug-only-collector.sh apps/cellrebel-auto/app --apk apps/cellrebel-auto/app/build/outputs/apk/release/app-release.apk
ok: debug-only collector boundary holds (+ release APK scanned)
git diff --check: exit 0
Debug SHA-256:   acea8ff3fc0c16d16c6cef5f7069c1a95e899e14abd8ae60754237595bfdeb89
Release SHA-256: dc3692420780879dadab589ce15d901f01a329fd89c074fa3484dd551f6fd761
```

Quality-gate disposition remains **host implementation ready for independent re-review**, not
feature closure or self-approval. No device/emulator command was run. Required exact-build device
evidence remains unexecuted under the explicit freeze; no acceptance criterion was waived. No
root media artifacts or UI/design changes were added, and the repository-specific Clowder check
scripts remain absent. Existing production/test commit anchors were not rewritten.

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
| Provider already exhausted | Existing `EngineJourneyConsumerOracleTest` admission preserves unproven owners read-only. A matching terminal successor with durable release and exact carrier reaches the same validated legacy recovery path. |
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

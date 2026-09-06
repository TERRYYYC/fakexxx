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

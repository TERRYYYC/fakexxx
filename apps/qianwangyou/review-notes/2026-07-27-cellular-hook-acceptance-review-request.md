---
feature_ids:
  - cellular-hook-verification
topics:
  - android
  - xposed
  - cellular
  - review
doc_kind: review-request
created: 2026-07-27
---

# Review Request: Complete cellular hook acceptance

Review-Target-ID: cellular-hook-acceptance
Branch: `feat/cellular-hook-acceptance`

## What

Replace the partial current-profile diagnostic with a debug-only acceptance transaction. Two
distinct schema-v2 exact matrices verify serving cells, neighbor cells, carrier/state, display
info, and physical-channel getters across synchronous, request, and callback paths. A third
enabled-fluctuation scenario proves both signal controls through complete observed RSRP ranges on
all three cell-info delivery paths. Before each override, a durable debug recovery record protects
the previous transport payload; every terminal path or next process launch republishes it. The
published envelope and public network-operator getter also carry a per-run session marker, so a
stale hook snapshot cannot pass by matching an otherwise identical matrix. The saved profile
database is never written.

## Why

The previous shell script could exit zero after observing only a few configured fields, so it could
not prove the cellular hook. The new verdict is exact and fail-closed: missing paths, type drift,
stale sessions, probe errors, restore failures, or database changes all fail the run.

## Original Requirements

> “继续你的任务，目标是完成蜂窝网络信息hook的验证。
> 你可以邀请fable5.0进行更加合理的ui重构，
> 有计划了你们直接进行，带着最终的版本见我就行。”

- Source: Cat Café thread `thread_mrmp97akqux0a16w`, message
  `0001785109781306-000199-f26f5014`
- Please judge whether the diff actually proves the cellular hook rather than merely adding more
  diagnostics.

## Tradeoff

- The acceptance component is debug-only and signature-permission protected; release manifest and
  bytecode contain no entry point.
- `PhysicalChannelConfigListener` needs the platform-only
  `READ_PRECISE_PHONE_STATE`. The probe therefore crosses the real registration before-hook, records
  the framework permission rejection, and locally replays one empty callback. The production hook
  must replace it with a real API-35 `PhysicalChannelConfig`; every getter is then asserted.
- Exact matrices omit signal fluctuation controls. A separate enabled scenario samples each public
  LTE RSRP getter 256 times and requires the observed set to equal every value in the configured
  ±3 dB range. Constant, missing, out-of-range, and wrong-count samples fail.
- The fixed public-API matrix requires API 33+, the first release where callback cell info and both
  physical-channel bandwidth getters in the matrix are available together.
- A debug-only recovery record is committed before override publication and cleared only after
  restore publication commits. The device harness verifies process death by SIGKILL and recovery
  on the next debug process launch.
- The payload's `acceptanceSessionId` must match the intent, `operator_name` carries a derived
  public marker, and the activity checks `getNetworkOperatorName()` before the full probe. The
  host verdict also expects that marker, closing stale-refresh false greens.
- `HookProbe` can wait for multiple framework callbacks, so both debug entry points dispatch it on
  a lifecycle-owned worker. Only completion, acceptance state transitions, restore, and finish are
  marshalled through the main executor; destruction cancels pending work and suppresses a queued
  completion. The normal activity reaches that code through a debug-source-set controller with a
  release no-op counterpart, keeping probe code out of release DEX. The current-profile harness
  polls for the asynchronous result instead of assuming an 11-second fixed deadline.
- `ConfigPrefsSync.sync()` reports success only when the world-readable path commits. A private
  fallback may preserve local state, but it cannot be consumed by target-process
  `XSharedPreferences` and therefore fails the cross-process publication contract.

## Architecture Ownership

Architecture cell: Android hook/config transport and public-API probe
Map delta: none
Why: the change exercises the existing schema-v2 safe-zone transport and hook boundaries; it does
not add a parallel store, router, adapter, or production entry point.

Please verify that the diff remains consistent with `Map delta: none`.

## Open Questions

### Technical OQ

- Does the physical-channel permission-boundary replay genuinely exercise the production callback
  hook without overstating what an unprivileged app can register for?
- Are the API-24/29/31/33 guards and constructor-shape fallbacks correct across the supported range?
- Can any activity/shell termination path leave the temporary payload published or mutate user
  data?
- Does the weak-identity neighbor registry remain leak-safe and stable under mutable framework
  hash codes?

### Value OQ

None.

## Next Action

Run an independent review against the exact PR HEAD. Persist either logical approval or findings as
a signed GitHub PR comment because all cats share one GitHub account.

## Review Sandbox

- Path: `/tmp/cat-cafe-review/cellular-hook-acceptance/opus`
- Start command:

```bash
git clone --no-checkout https://github.com/TERRYYYC/FakeGps-test.git .
git fetch origin feat/cellular-hook-acceptance
git checkout --detach origin/feat/cellular-hook-acceptance
```

- No web/API ports are required; this is an Android/JVM/device-harness change.

## Self-check Evidence

### Spec compliance

- Plan: `feature-specs/2026-07-27-cellular-hook-verification.md`
- Original requirement coverage: serving and neighbor identity/signal, carrier/state, callback,
  physical-channel, exact verdict, isolated restore, release exclusion.
- UI work is intentionally absent from this branch and remains isolated in PR #3.

### Validation

```bash
./gradlew clean testDebugUnitTest assembleDebug assembleRelease lintVitalRelease
# BUILD SUCCESSFUL; 67 JVM tests, 0 failures

python3 -m unittest scripts.test_cellular_acceptance_matrix scripts.test_hook_verdict
# 15 tests, 0 failures

./scripts/test-hook.sh --current-profile
# asynchronous public-API diagnostic observed on API 35
./scripts/test-hook.sh --cellular-matrix
# durable SIGKILL recovery verified
# two exact scenarios × 274 assertions = 548/548 verified
# enabled-fluctuation scenario = 21/21 verified plus 3 × 256 complete-range samples
# database unchanged; database-backed safe-zone fingerprint restored
```

Release artifact checks prove that `HookAcceptanceActivity`, `HookAcceptanceApplication`, recovery
classes, signature permission, payload validator, and probe entry point are absent from the release
manifest/DEX. Root-level media artifact checks return zero.

[砚砚/gpt-5.6-sol🐾]

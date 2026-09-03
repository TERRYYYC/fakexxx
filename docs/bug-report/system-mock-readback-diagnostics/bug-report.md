---
feature_ids: [G2-66, G2-71]
topics: [android, raw-readback, diagnostics, privacy]
doc_kind: bug-report
created: 2026-09-03
status: implemented-host-verified
---

# Framework readback failures collapse into an unexplained absent sample

Reporter: Codex, investigating the remaining readback uncertainty from the authorized
2026-09-02 Moto run. This report concerns diagnostic information loss; the old logs cannot
establish whether that run encountered a permissions error, an API error or an empty cache.

## Diagnosis capsule

| Field | Evidence / decision |
| --- | --- |
| Symptom | Both failed framework queries and an empty provider cache become `system-mock:unavailable`; callers cannot distinguish their causes. |
| Evidence | At base `4192f41`, AndroidSystemMockLocationReader lines 23–27 combine provider-enabled and cache queries under `runCatching().getOrNull()`. Lines 28–37 decode outside that catch. SystemMockTrustPolicy line 83 converts a whole-reader exception into an empty list. Historical Moto details remain in `docs/acceptance/codex-bench-runtime-2026-09-02-ZY22.md`. |
| Root cause | An absent list element represents multiple incompatible acquisition outcomes, and an extraction exception from one source can erase a previously acquired source at the policy boundary. |
| Strategy | Keep one per-invocation snapshot, isolate the three acquisition stages per source, and emit only sanitized diagnostic DTOs beside unchanged trust calculations. |
| Time / escalation | If an adapter test cannot execute a genuine Android Location mapping, keep the runtime claim unverified and request the separately managed emulator lane. Do not infer a hardware failure from host fakes. |
| Warning signals | A second read for logging, requested coordinates substituting for samples, new TTL/state, diagnostic text entering fingerprints, exception messages in logs, or a green result obtained through self-hooking violates scope. |
| User-visible effect | Future logs explain per-provider API failure, empty cache, disabled cached sample and publish-anchor freshness without printing coordinates. No UI or business-success claim is added. |
| Acceptance | Observe behavioral RED for classified source failures and surviving peer samples; GREEN on the same tests; retain original policy results, fingerprints and watermarks; prove logging wiring and sink-failure isolation. |

## Reproduction and expected behavior

Use the real production reader with injected Android-operation functions in the existing
Robolectric host harness. Make GPS provider lookup throw, return no GPS cache, or make network
sample extraction throw after GPS succeeded. Previously the first two cases have no distinct
diagnostic and the last case throws out of the reader, causing policy-level loss of GPS.

Expected: GPS/network each have a typed acquisition outcome, successful peer samples survive,
and every incomplete/error case still fails verification closed. Disabled samples preserve their
actual mock flag and source monotonic time; freshness is assessed only against a positive
publish anchor, not an invented TTL.

## Fix and alternatives

Add a default `readSnapshot()` alongside the existing SAM `read()` method, a coordinate-free
diagnostic model, source-scoped acquisition catches and a non-intrusive evaluation sink.
Wire the logger at both Android production policy consumers. Keep the diagnostic channel out
of ContractV1, result semantics, fingerprints, canonical digests and durable watermarks.

Rejected: exporting private providers; logging `Location`, coordinates, coordinate-bit
fingerprints or exception text; adding a new persistence store or polling loop; treating
diagnostic detail as verification; changing self-hook/oracle policy in this lane.

## Validation and scope

The implementation plan is `feature-specs/2026-09-03-readback-and-config-isolation-plan.md`.
Command receipts go to `/tmp/fakexxx-readback.yazTyt/`. This lane may build but must not execute
device instrumentation; the root coordinator owns emulator allocation and independent witness.
No Moto operation, configuration-publication success, #66 FULL evidence, merge or issue closure
is implied by this diagnostic repair.

## Host results (2026-09-03)

Worktree: `/Users/terry/Desktop/coding/fakexxx-location-readback`, branch
`codex/location-readback-diagnostics`, source base `4192f41`, plan commit `c1247bb`.
This is the existing #72 stack, not rebased onto main: main lacks the reader being repaired.
All commands used `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`
and `ANDROID_HOME=/Users/terry/Library/Android/sdk`.

| Step / actual command | Receipt | Outcome |
| --- | --- | --- |
| QWY `./gradlew :app:testDebugUnitTest --tests '*SystemMockTrustPolicyTest' --tests '*QwyActualReadbackWiringTest' --console=plain` before source edits | `baseline.log`, `baseline.exit` | 19 tests, exit 0 |
| QWY `./gradlew :app:testDebugUnitTest --tests '*SystemMock*DiagnosticsTest' --console=plain`, compilable old-behavior seam | `red-policy.log`, `red-policy.exit` | 7 tests, 7 expected behavioral failures, exit 1 |
| `./integration-tests/pr63-on-issue66/run-host-gate.sh :harness:testDebugUnitTest --tests '*AndroidSystemMockLocationReaderDiagnosticsTest' --console=plain`, old behavior | `red-adapter-behavior.log`, `red-adapter-behavior.exit` | 6 tests, 5 expected behavioral failures, exit 1 |
| QWY `./gradlew :app:testDebugUnitTest --tests '*SystemMock*DiagnosticsTest' --tests '*SystemMockTrustPolicyTest' --tests '*QwyActualReadbackWiringTest' --console=plain` | `green-policy.log`, `green-policy.exit` | 26 tests, 0 failures, exit 0 |
| Same adapter command after implementation | `green-adapter.log`, `green-adapter.exit` | 6 tests, 0 failures, exit 0 |
| QWY `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleCodexBench :app:assembleDebugAndroidTest --console=plain` | `full-app-and-apks.log`, `full-app-and-apks.exit` | 1,037 unit tests, 0 failures/errors/skips; all three APK tasks succeeded, exit 0 |
| `git diff --check` and `git diff --name-only 4192f41 -- contracts` | local command output | exit 0; no ContractV1 changes |

An initial adapter test command (`red-adapter.log`) failed at compilation because the host
compile classpath does not expose AndroidX LocationCompat. That is **not** RED evidence. The
test was corrected to use the API-35 native Location mock property, without adding dependencies,
and the subsequent `red-adapter-behavior.log` is the actual behavioral RED above.

The adapter tests execute the shipped production class against Robolectric's LocationManager
cache and capture the actual Android logger through ShadowLog. They also exercise each fault
stage, exact one-read behavior, disabled cached/absent states and later-call recovery. This is
host Android-adapter evidence, not a claim about physical framework location delivery.

### Built APK identity (not device-install evidence)

| Variant / path relative to apps/qianwangyou | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `6ba13be82f9bae397116be29c90d9fb2eddabcffa6cfe9170bc11f45f623ab83` |
| `app/build/outputs/apk/codexBench/app-codexBench.apk` | `713f399c7f06daa6ebfabc1a66a9b0e45a1083f4522b7a3ece76a4bed6d7c9bd` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `06efab55c61756d326bfc93734d2534821d0e7c1af0109a5be2c4d4916491f6d` |

## Lane-A quality-gate report

- **Vision:** This slice supports the user's working-phone goal by making failed independent
  readback explainable. It does not claim the full app, config transport or continuity oracle works.
- **AC1–AC3:** Source failures are classified, raw trust fields are retained, and the formatter
  accepts only sanitized DTOs. Malicious source text, exception content and throwing sinks are
  covered. Origin is an enum and unknown source text is replaced with the fixed `other` value.
- **AC4:** The existing controller and service wire the production logger explicitly. The same
  read snapshot drives trust and diagnostics. Host runtime mapping/logging passed; the separate
  real-Android instrumentation remains coordinator-owned and has only been compiled here.
- **Other plan lanes / closure:** AC5–AC6 belong to the separately dispatched config lane.
  AC7 independent exact-HEAD review and AC8 publication remain coordinator-owned immediate
  workflow steps. No feature/issue is being closed by this author report.
- **Architecture:** Existing integration/v1 and mockprovider consumers; no map delta, durable
  state, scheduling, callback registration or parallel provider is added.
- **Five-axis risk:** behavior=source-isolated diagnostic read; data=no writes; security=log
  privacy boundary; contract=internal SAM-compatible addition only; irreversible=none.
- **Three catch stages:** These are sequential Android-operation boundaries, not fallback
  attempts. Provider-state failure cannot pretend the provider is enabled; cache-query failure
  must differ from a null cache; extraction failure must not erase a successful peer source.
  Each branch emits one classified result and proceeds to the next required provider.
- **Dogfood:** The host test executes real production reader → policy → Android logger with
  captured output. The coordinator owns device execution; host results are not substituted for it.
- **UI/design/media:** No UI edits, no matching `.pen` files and no new root media artifacts.
  Clowder-specific pnpm/hotfix/fallback scripts are absent from this external Android repository;
  repository Gradle checks and direct diff inspection were used instead.
- **Remaining runtime scope:** `SystemMockReadbackInstrumentedTest` requires a disposable
  `ranchu` API-35 emulator and denied fine/coarse target permissions. Its first case uses real
  LocationManager to prove a classified permission-negative read and emits production logs.
  Its second case maps a real Android Location fixture; it is explicitly not positive framework
  cache/delivery evidence. No permissions, AppOps or provider state are mutated by these tests.

---
feature_ids: [G2-66, G2-71]
topics: [android, raw-readback, config-transport, codex-bench, isolation]
doc_kind: plan
created: 2026-09-03
base_commit: 4192f411b2cf741990041bdf206ce3101be8582f
status: implementation
---

# Independent readback and configuration isolation implementation plan

**Feature:** Existing #71 remaining investigations and #66 device-readiness work.
**Goal:** Make the next authorized location run diagnostically useful and prevent the
codexBench configuration/verification process from spoofing its own raw readback.
**Acceptance Criteria:** AC1–AC8 below; these do not replace #66's complete device acceptance.
**Architecture cell:** Existing QWY integration/v1, config and verify modules; this external
repository does not define Clowder architecture-cell IDs.
**Map delta:** none
**Map delta why:** Extend the existing reader and build-specific hook policy, not a new system.
**Architecture:** Preserve raw samples and all existing trust calculations. Add ephemeral,
coordinate-free diagnostics at the actual production reader consumers. Separately isolate the
codexBench main process from generic spoof hooks while retaining its deliberately hooked probe.
**Tech Stack:** Kotlin/Java, Android API 35, existing Gradle/JUnit and Robolectric host harness.
**前端验证:** No new UI; any existing observation-scope label affected by build policy must
remain semantically correct and have actual-variant tests. No claim of new UI/device acceptance.

## Original authorization and finish line

The operator's original goal is a working phone application, with isolated codex-bench APK names.
On 2026-09-03 the operator said “那你先按你的计划 继续进行” after the phase report recommended
config transport, independent readback diagnosis and then reviewed Moto revalidation.
This implementation phase changes code/tests and records evidence; it does not operate Moto,
merge PRs or close #66. Missing production-frame evidence remains an explicit remaining gate.

We are not building another mock-location provider, another Binder identity implementation,
a new transport protocol, a generic diagnostic framework, or a substitute continuity oracle.

## Grounding and coordination

- #72 HEAD `4192f41` contains the reviewed identity repair and prior Moto/emulator evidence.
- Current main/origin main both resolve to `a635f459eadb1b174c6f9a81c48deab32bc4a0bd`.
  Main does not contain AndroidSystemMockLocationReader, which was introduced by #65.
- The other dispatch line owns main-based #73 (`9c6afecd`), which lifts the identity repair.
  Do not duplicate its EnvironmentControlService or identity instrumentation changes.
- Open PR file lists checked: #65 introduces the reader; #68 changes ConfigPrefsSync and oracle
  consumers; #72/#73 touch identity service/tests. Existing upstream behavior must be preserved.
- Scope notice: https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5526416219
- Original evidence: docs/acceptance/codex-bench-runtime-2026-09-02-ZY22.md and
  docs/acceptance/issue71-binder-identity-emulator.md (historical, not a fresh device run).
- Memory/cross-thread proposal MCPs are unavailable in this Codex session. Use existing exact
  source anchors and bounded CLI subagents; this is not a new app-thread proposal or a claim
  that an external team acknowledged our message.
- Keep main and its unrelated `.cat-cafe/capabilities.json` edit untouched. Plans are committed
  in the isolated sibling worktree, as used for the previous delivery, not direct-pushed to main.

## Evidence-backed diagnosis

The old Android reader wraps provider-enabled and cache queries in one runCatching/getOrNull;
API exceptions and null cache become indistinguishable. Sample extraction occurs outside that
catch, so one extraction failure can discard the entire read at the policy's catch boundary.
Existing disabled samples and publish-anchor freshness checks are valid and must remain intact.

Historical config logs show WORLD_READABLE rejected on first access, then a private fallback.
Later transportAccepted=true coexists with an app-private path and published=false. No new
writer bug is established by this: the code correctly rejects app-private backing. LSPosed
shared-preferences support must actually be loaded and another UID must read a fresh payload.
Manifest declarations alone cannot establish this.

RuntimeSelfHookPolicy currently accepts every self process in DEBUG, including codexBench.
If the module's own load-package callback executes, that can spoof the very API used for raw
readback. Scope UI appearance alone is not proof of independence. Correct the build policy
before any enabled-framework validation; preserve ordinary debug and release behavior.

## Terminal schema and invariants

Keep `SystemMockLocationReader.read(): List<SystemMockLocationReadback>` as the only abstract
SAM method. Add default `readSnapshot()` so legacy readers still work. Android overrides it
with one source-scoped acquisition per invocation. No second read for diagnostics.

`SystemMockReadSnapshot` carries raw samples plus coordinate-free per-source diagnostics and
an optional whole-reader failure. Diagnostic status distinguishes SAMPLE, NO_SAMPLE,
PROVIDER_QUERY_FAILED, CACHE_QUERY_FAILED, SAMPLE_EXTRACTION_FAILED and UNREPORTED.
Failure categories are SECURITY, ILLEGAL_ARGUMENT and OTHER, not Throwable messages.
Only source, enabled flag, status, mock flag and monotonic source time reach the diagnostic DTO.
At policy evaluation, freshness is UNASSESSED for a non-positive publish anchor, otherwise
BEFORE_PUBLISH or AT_OR_AFTER_PUBLISH. Do not add an arbitrary TTL.

Policy accepts a default no-op sink; its real Android consumers explicitly wire the logger.
Logging cannot change the trust result. Diagnostics never enter fingerprints, semantic digests,
watermarks, ContractV1 or persisted configuration. The formatter accepts only sanitized DTOs.

- INV1: An error at one source does not remove another source's sample (host + Android tests).
- INV2: Same raw samples yield the same trust/fingerprint/watermarks as before (regression tests).
- INV3: Diagnostic output contains no coordinates, Location dump, exception message or existing
  coordinate-bits fingerprint (adversarial formatter and sink tests).
- INV4: Every real consumer uses the same snapshot for decision and diagnostic evidence (wiring
  guard plus runtime adapter test; no second framework query).
- INV5: Sink failure cannot change the decision (throwing-sink test).
- INV6: CodexBench non-probe self processes cannot install generic spoof hooks, but its exact
  private :hook_verify process and scoped external targets retain intended hook eligibility.
- INV7: Ordinary debug/release policy, system-server oracle branch, package IDs and signers stay
  unchanged (actual variants, source/compiled guards and APK checks).
- INV8: Config commit/path/mode are not cross-UID read receipts; no writer-success claim without
  the independently read fresh payload and framework evidence (acceptance report).

## Stateful-object census

No new durable store, listener, background loop, cache, generation or registry is introduced.
Existing payload/outcome stores, hook ownership and refresh scheduler are not changed.

| Object / owner | Event | Transition / rule |
| --- | --- | --- |
| Per-call source snapshot / reader | start | acquire each required source once |
| Per-call source snapshot / reader | source query/extract fails | record classified failure, continue next source |
| Per-call evaluation / trust policy | positive publish anchor | derive freshness from the same raw source timestamps |
| Per-call evaluation / trust policy | diagnostic sink throws | preserve original result, retain no retry state |
| Build policy / generated BuildConfig | build selected | immutable eligibility; no runtime toggle or persisted override |

Adversarial cases: alternating source failures, failure after first successful source,
malicious exception messages, sink exceptions, zero/negative anchors, future/old timestamps,
disabled provider with/without cache, external package/process name lookalikes, private probe
name lookalikes, repeated invocation and exact debug/codexBench/release build differences.

## Implementation lanes

### A. Raw readback diagnostics

Owner: `location_readback_diagnosis`, worktree `/Users/terry/Desktop/coding/fakexxx-location-readback`.
Branch: `codex/location-readback-diagnostics` from the frozen #72 HEAD.

Files:
- Modify integration/v1/AndroidSystemMockLocationReader.kt and SystemMockTrustPolicy.kt.
- Add integration/v1/SystemMockReadDiagnostics.kt and AndroidSystemMockDiagnosticLogger.kt.
- Wire only the reader construction points in QwyEnvironmentController.kt and
  mockprovider/MockProviderService.kt; do not modify their decision rules.
- Update QwyActualReadbackWiringTest and add focused policy/formatter unit tests.
- Add actual Android-adapter execution tests to the existing Robolectric host harness.
- Add bounded device instrumentation where it strengthens the real Android evidence, without
  modifying the existing Binder identity test or requiring a physical device.
- Record diagnosis before implementation in docs/bug-report/system-mock-readback-diagnostics/bug-report.md.

Steps: (1) preserve baseline output; (2) introduce only a compilable seam and failing assertions;
(3) observe a behavioral RED, not just a compiler failure; (4) implement source isolation and
sanitized diagnostics; (5) run the same tests GREEN plus old trust/wiring tests; (6) verify real
adapter behavior and the production logger; (7) commit exact paths with evidence and provenance.

### B. Config transport readiness and codexBench self-hook isolation

Owner: `config_transport_diagnosis`, worktree `/Users/terry/Desktop/coding/fakexxx-config-readback-isolation`.
Branch: `codex/config-readback-isolation` from the same frozen #72 HEAD; separate from lane A.

Files: QWY app/build.gradle, verify/RuntimeSelfHookPolicy.kt, hook/MainHook.java and the existing
observation-scope classification only if needed to keep it truthful; focused tests under verify/.
Enable and execute the actual codexBench unit-test variant if currently missing. Add narrowly
scoped compiled/wiring checks where needed; avoid changing shared CI/scripts until coordinated.
The coordinator owns the codex-bench CI step and test-report artifact additions. Release
actual-variant testing exposed four existing tests that depend on debug-only probe classes;
move those tests without content changes to src/testDebug and reuse them for codexBench so
release can compile without reducing debug/codexBench coverage.

Steps: (1) document the existing config root-cause evidence and uncertainty;
(2) run a RED demonstrating codexBench main self-hook eligibility;
(3) introduce an explicit build policy preserving ordinary debug/release and private probe;
(4) run actual-variant tests including negative package/process cases;
(5) build and inspect exact APK identity/signing;
(6) publish a safe future LSPosed validation recipe that requires an exact framework build,
fresh process, genuine cross-UID fresh-payload read and independently unhooked reader.
Do not claim this solves actual configuration publication without that positive runtime proof.

## Validation commands (repository-derived)

Use JDK `/Applications/Android Studio.app/Contents/jbr/Contents/Home` and
`ANDROID_HOME=/Users/terry/Library/Android/sdk`, explicitly per command. Gradle wrapper and
test tasks come from the existing workflows and previous acceptance recipe, not pnpm.

- In apps/qianwangyou: `./gradlew :app:testDebugUnitTest --tests '*SystemMockTrustPolicyTest' --tests '*QwyActualReadbackWiringTest'`.
- Targeted new tests: `./gradlew :app:testDebugUnitTest --tests '*SystemMock*DiagnosticsTest'`.
- Actual variants: run `:app:testCodexBenchUnitTest` and `:app:testReleaseUnitTest` with `--tests '*RuntimeSelfHook*' --tests '*ObservationScope*'` after enabling the tasks if required.
- Android adapter: `./integration-tests/pr63-on-issue66/run-host-gate.sh :harness:testDebugUnitTest --tests '*AndroidSystemMockLocationReaderDiagnosticsTest'`.
- Full affected-app and repository gate: `bash scripts/verify-a-plus.sh` using its documented environment; retain inherited lint debt rather than claim raw lint is clean.
- APK build: `./gradlew :app:assembleDebug :app:assembleCodexBench :app:assembleDebugAndroidTest`.
- Terminal whitespace check: `git diff --check`; no formatter is configured in docs/SOP.md.

Any emulator run must use a newly created isolated AVD, an unused explicit port/serial and
foreground managed process. All adb state changes must target that confirmed emulator serial;
never use connectedAndroidTest auto-selection, a physical serial, or unqualified adb installs.
An independent non-author witness checks receipts before reporting device behavior. Clean
only the owned emulator test state and stop that emulator; preserve evidence locally.

## Review and delivery criteria

AC1 classified source errors, AC2 preserved raw/decision semantics, AC3 safe diagnostic output,
AC4 actual runtime wiring, AC5 exact codexBench isolation with unchanged old variants,
AC6 evidence-backed config prerequisite report, AC7 independent non-author exact-HEAD review,
AC8 committed/pushed reports with explicit host/emulator/Moto boundaries.

Each lane has its own tests. A non-author reviews both lanes and coordinator changes at the
exact combined HEAD; root combines only non-overlapping commits and tests the combined state
before publishing a combined-build claim. Keep PRs draft and
unmerged. Do not alter #73 review custody or close #71/#66.

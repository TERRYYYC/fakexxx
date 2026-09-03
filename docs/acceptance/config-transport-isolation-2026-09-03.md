---
feature_ids: [G2-66, G2-71]
topics: [android, config-transport, codex-bench, self-hook-isolation, validation]
doc_kind: acceptance
created: 2026-09-03
base_commit: 4192f411b2cf741990041bdf206ce3101be8582f
status: host-verified-runtime-positive-pending
---

# codexBench configuration-process isolation — lane B

## Result and boundary

The actual codexBench build now declines generic spoof hooks for its non-probe self
processes while remaining debuggable. Ordinary debug keeps its controlled self-hook;
release remains isolated. The exact private `:hook_verify` process and external scoped
targets retain hook eligibility. The configuration observation classification follows
the same policy; it no longer labels codexBench as SELF_HOOKED merely because DEBUG=true.

This is a **verification prerequisite**, not a working-phone or cross-UID publication
claim. No physical device, adb operation, LSPosed setup, system-server allowlist change,
merge, GitHub write or framework-enabled runtime test was performed by this lane.
New Android instrumentation was built, not executed by the author. The coordinator
owns the explicit isolated-emulator window and independent runtime evidence witness.

Scope/plan: `feature-specs/2026-09-03-readback-and-config-isolation-plan.md`, lane B,
introduced in the sibling lane by plan commit `c1247bb`. Before the coordinator combines
the branches, that plan is available in `fakexxx-location-readback`, not yet in this
lane's ancestry. Diagnostic rationale and the positive recipe are in
[config transport root-cause report](../bug-report/config-transport-readiness/bug-report.md).

## Code changes

- `app/build.gradle`: immutable `ALLOW_NON_PROBE_SELF_HOOK` values are debug=true,
  codexBench=false, release=false. Enable actual codexBench/release unit-test tasks.
- `RuntimeSelfHookPolicy`: a production two-argument entry consumes the generated
  build flag; the pure three-argument policy still tests explicit boundary cases.
- `MainHook`: call that production entry; the existing system-server oracle early
  branch is unchanged. Framework preference hooks and this module's spoof hooks
  are separate mechanisms; the report treats that as source-level reasoning only.
- `ObservationScope`: configuration-process classification uses the same production
  policy, not DEBUG. REAL_BASELINE means this module does not self-spoof it; it does
  not certify absence of another module or a system mock provider.
- Actual-variant tests pin package, DEBUG flag, immutable hook flag, non-probe process
  exclusions, exact probe exception and external package/process lookalikes.
- Compiled `MainHook` method-reference inspection pins the actual two-argument call,
  without loading Xposed on a plain JVM or trusting a source-text decoy.
- New `ConfigTransportCacheInstrumentedTest`: two real Android negative cases,
  including disk-level publication outcome checks. The producer uses unique remapped
  fixture prefs names and its real private Provider; no fake cache/preferences engine.

No ConfigPrefsSync, trust policy, reader, controller, service, frozen ContractV1 or
oracle behavior was changed in this lane. Lane A owns raw-readback diagnosis changes.

## Test-source correction

The first release unit-test compilation failed because four shared tests referenced
debug-only classes. That was an environment/source-set issue, **not** the behavioral RED.
The coordinator approved these byte-identical moves from `src/test` to `src/testDebug`:

| Test class | Cases in both debug-backed variants |
| --- | ---: |
| HookAcceptancePayloadTest | 9 |
| HookAcceptanceRecoveryTest | 4 |
| HookAcceptanceStateMachineTest | 4 |
| HookProbeRunnerTest | 2 |

`testCodexBench` reuses `src/testDebug/java`. Whole-suite XML class/count comparisons
show debug and codexBench have identical 1,035-case coverage. Release excludes these
19 cases and the pre-existing debug-only ExtraCoerceTest (4), QwyDurableSnapshotTest (6),
CollectorGateTest (7), totaling 36; its complete suite has 999 cases. No tests are skipped.

## RED → GREEN and mutations

Local evidence directory: `/tmp/fakexxx-config-isolation.4OQxKt/` (temporary, author machine).
All Gradle commands run from `fakexxx-config-readback-isolation/apps/qianwangyou` with:

```text
JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home
ANDROID_HOME=/Users/terry/Library/Android/sdk
./gradlew --no-daemon ...
```

| Evidence | Real command / result |
| --- | --- |
| `red-variant.log` | `:app:testCodexBenchUnitTest --tests '*RuntimeSelfHookActualVariantTest' --tests '*ObservationScopeActualVariantTest'`: **5 cases, 2 failures**, expected main self-hook and scope assertions. The task compiled real codexBench BuildConfig first. |
| `green-debug-codex-build.log` | Targeted debug and codexBench hook/scope/wiring/oracle tests: **29/29 each**; debug/codexBench and Android test APK build passed. |
| `mutation-debug-policy.log` | Restore DEBUG as production self-hook control: **5 cases, 2 failures**, caught by the same actual-variant assertions. |
| `mutation-dispatch-bypass.log` | MainHook bypasses generated policy by calling the three-argument entry with DEBUG: **4 cases, 1 failure**, caught by compiled method-reference assertion. |
| `mutation-probe-prefix.log` | Replace exact probe equality with `startsWith`: **4 cases, 1 failure**, caught by non-probe lookalikes. |
| `green-final-build.log` | Mutations removed. Full debug **1,035/1,035**, full codexBench **1,035/1,035**, targeted release **29/29**; debug/codexBench/release/Android test APKs built. Exit 0. |
| `release-full-unit.log` | Full `:app:testReleaseUnitTest`: **999/999**, zero failures/errors/skips. Exit 0. |

Full final build invocation:

```sh
./gradlew --no-daemon \
  :app:testDebugUnitTest :app:testCodexBenchUnitTest \
  :app:testReleaseUnitTest \
    --tests '*RuntimeSelfHook*' --tests '*ObservationScope*' \
    --tests '*RuntimeProbeWiringContractTest' --tests '*SystemServerOracleWiringGuardTest' \
  :app:assembleDebugAndroidTest :app:assembleDebug :app:assembleCodexBench :app:assembleRelease
./gradlew --no-daemon :app:testReleaseUnitTest
```

The targeted `--tests` options apply to the release task in that first invocation;
debug and codexBench are complete suites. XML reports, not Gradle task counts, supply
the case totals. This lane did not execute the whole-repository integration gate or
claim raw inherited lint debt was zero; the coordinator verifies the combined tree.

## Built artifact identity

`scripts/check-debug-signer.sh` passed for QWY debug, codexBench and release. All three
match the committed signer:

`7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41`

Initial lane B SHA-256 values, before the P2 presentation correction below
(historical host-built artifacts, not Moto-installed artifacts):

| Artifact under QWY `app/build/outputs/apk` | SHA-256 |
| --- | --- |
| `debug/app-debug.apk` | `f45eacb6da33d28986c1c03821712370ef52bce49028aa5c83e4e71cbc5de268` |
| `codexBench/app-codexBench.apk` | `05e02894286e7914221149725b985a60c97f80931f83e0516753721f7a2d559a` |
| `release/app-release.apk` | `0fd113d780a5ad44e4b954efc2f7495726285d45302874d027310bc538695449` |
| `androidTest/debug/app-debug-androidTest.apk` | `b505e2fd79c5246492f68f043c2b2707dba25de60d6ca93281b79c3bd2d2622a` |

`check-codex-bench-apks.sh` passed with this new QWY APK and the unchanged old Auto APK
from `fakexxx-codex-bench`. It verifies names/package IDs/signers/manifest boundaries,
**not** a combined new Auto build or runtime success. New QWY remains
`name.caiyao.fakegps.codexbench`, label `千网游 · codex-bench`.

## Android negative gate supplied to coordinator

Class: `name.caiyao.fakegps.config.ConfigTransportCacheInstrumentedTest`.
Requires stock `ranchu` emulator, API 24+, `.bench` or `.codexbench` target package,
and no active framework that allows the first WORLD_READABLE request. A framework
positive environment is intentionally not interchangeable with this negative gate.

- `worldReadableRetryReturnsTheCachedPrivateInstanceWithoutProvingTransport` asserts
  first rejection → real private fallback commit → same cached instance from a later
  non-throwing WORLD_READABLE call → actual private backing file.
- `realPublisherRejectsFreshAndCachedPrivateTransportAndKeepsItsDurableFailure`
  calls real ConfigPrefsSync with fixture-owned prefs names and a uniquely inserted
  real profile. It requires local payload A→B, false publish results, no success time,
  preserved last-good pointer, and those failure values in committed XML bytes.

Cleanup clears/deletes only the unique fixture prefs and its inserted row in `finally`.
The source refuses a physical or production-package target. Do not use unqualified adb
or `connectedAndroidTest`; the coordinator supplies one explicit owned emulator serial.
The author did not execute this instrumentation and does not claim it passed here.

## Quality-gate / handoff matrix

Original requirement: make the phone application usable with non-colliding codex-bench
names. On 2026-09-03 the operator explicitly authorized continuing the staged plan.
This lane expands the existing implementation; it is not a replacement prototype.

| Item | Author evidence / remaining owner |
| --- | --- |
| AC5 actual codexBench isolation, ordinary variants preserved | Actual-variant tests, complete suites, compiled dispatch pin, 3 rejected mutations, signed APK builds. Author verification passed. |
| AC6 config prerequisite report | Historical logs + official source + no weakened publication condition + genuine cross-UID positive recipe. Report delivered; actual positive remains unproven. |
| AC7 independent non-author exact-HEAD review | Coordinator assigns after lane commit; author test results are not approval. |
| AC8 pushed reports / combined-state validation | Coordinator combines reviewed lanes, verifies combined tests, publishes draft delivery. This lane neither pushes nor closes the phase. |
| Working Moto / #66 FULL | Not passed by these host tests. Separate supervised device/framework gates remain required; no finish claim. |

Risk: behavior=build-specific safety change; data=no production store/schema change;
security=remove unwanted self-spoof without expanding privileges; contract=no wire/API
contract change; irreversible=no device/system action. No new cache, listener, fallback,
store or runtime switch. Architecture stays within existing verify/hook/build modules.

Dogfood boundary: the author executed the actual per-APK policy and observation classifier
through real generated variant classes and compiled MainHook wiring. Framework injection,
cross-UID publication and Android negative execution remain separate coordinator gates,
not silently substituted with JVM tests. There is no new UI layout/design. No matching
`designs/**/*.pen` or root media artifacts were found. Clowder-specific pnpm hotfix,
architecture/tips/fallback checkers are not present in this external Android repository;
its actual Gradle gates and `git diff --check` were used. Temporary evidence stayed in `/tmp`.

## Independent-review P2: process baseline is not physical truth

The reviewer found that the new codexBench REAL_BASELINE path reached old copy that
equated release/non-self-hooked observations with physical truth. The coordinator
verified the issue against lane B commit `10b10abcd5c0138e0895193ac91f4a6a9ef068b3`.
The policy was correct; its presentation consumers retained an obsolete assumption.
Not self-hooking this module does not rule out system mock locations or another module.

The narrow correction introduces a pure `ui/ObservationScopePresentation.kt` mapper,
used by VerifyScreen's ScopeCard and FieldRow and both text/boolean reference labels
in ProfileEditorScreen. It says “本进程读取基线 / 本模块未自 hook”, describes the system
mock/other-module limitation, and preserves “不能证明目标 App 内的 hook 是否生效”.
HOOK_PROBE no longer assumes a release build, while retaining its exact private
process, framework injection and matching request/configuration requirements.
SELF_HOOKED copy now distinguishes build eligibility from actual framework injection.

Sibling sweep in those same two screens narrows passthrough to “系统 API 返回值” and
collision comparison to “读取基线”. Related editor comments and VerifyViewModel's
“untouched device values” comment are narrowed. No enum, classification, verification
engine, trust decision, data selection or UI layout changed. No readback/controller/
service/oracle/config-transport behavior changed.

Evidence directory: `/tmp/fakexxx-scope-copy.RL38JT/` (temporary, author machine).

- RED: first extract the old copy unchanged into the shared mapper and wire all four
  consumption sites. `red-copy.log`: actual `:app:testCodexBenchUnitTest --tests
  '*ObservationScopePresentationTest' --tests '*VerifyUiContractTest'` executed
  **9 cases, 5 assertion failures**, not a compilation or missing-task failure.
  Four new tests exercised returned presentation values, including the real generated
  variant's scope; one existing partial-copy behavior assertion was tightened.
- GREEN: `green-all.log`: `:app:testDebugUnitTest :app:testCodexBenchUnitTest
  :app:testReleaseUnitTest :app:assembleCodexBench` passed. XML totals are debug
  **1,039/1,039**, codexBench **1,039/1,039**, release **1,003/1,003**, all with zero
  failures/errors/skips. All four added tests are shared across all three variants.
- The mapper tests assert executed output, not source-string presence. Source diff
  establishes the four consumers use it; actual rendered UI is a separate coordinator/
  reviewer gate, not a claim made by these JVM tests. The unchanged field-value routing
  remains covered by VerifyUiContractTest.
- P2 codexBench APK SHA-256: `7fe478924175161462adaa42243ac8f56256434b4872cb92f935758c399cfe3d`.
  Only the codexBench APK was rebuilt for this text correction; the earlier table is
  explicitly historical. This lane performed no adb, install, push, merge or self-review.

Next owner: coordinator cherry-picks this P2 fix into the combined branch, supplies
the owned-emulator screenshots and obtains the non-author reviewer's exact-HEAD verdict.
The author does not claim that runtime UI review, cross-UID publication or Moto FULL
acceptance has passed.

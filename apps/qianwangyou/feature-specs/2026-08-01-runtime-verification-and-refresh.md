---
feature_ids:
  - runtime-hook-verification
  - configurable-hook-refresh
topics:
  - android
  - xposed
  - verification
  - configuration-transport
doc_kind: implementation-plan
created: 2026-08-01
---

# Runtime Hook Verification and Configurable Refresh Implementation Plan

**Feature:** `runtime-hook-verification` / `configurable-hook-refresh`
**Goal:** A stable release build can prove the published profile through a hook-enabled probe process, and users can choose the bounded interval used by long-running target processes to re-read that profile.
**Acceptance Criteria:** The release configuration process remains unhooked; an unexported `:hook_verify` process returns public-API observations produced under the hook; the verify UI distinguishes payload delivery, probe applicability and field verdicts; profile editing offers “保存并验证”; the refresh interval shown in Settings is the interval scheduled by `MainHook`; missing/invalid interval data is backward-compatible and bounded; one process owns one refresh scheduler; JVM, Python, Debug/Release/R8 and isolated moto g54 acceptance gates pass.
**Architecture cell:** profile config → published payload → target/probe process → verification UI
**Map delta:** new cell required
**Map delta why:** A production-only, non-exported `:hook_verify` process becomes a new lifecycle owner for one-shot verification sessions.
**Architecture:** `SpoofSettings` owns the persisted refresh preference and `ConfigPrefsSync` projects it into the existing schema-v3 payload. A pure `HookRefreshPolicy` is the single source for defaults, bounds and UI choices; `MainHook` schedules one refresh loop per process from that policy. The main release process remains a real-baseline reader, while a same-package `:hook_verify` process is deliberately eligible for self-hooking and returns only public-API observations to `VerifyViewModel`.
**Tech Stack:** Kotlin, Java 17, Android API 24–35, Xposed API 82, Compose, Room/SharedPreferences, JUnit 4, Python `unittest`
**前端验证:** Yes — reviewer must exercise Settings, profile editor navigation and Verify states on the moto g54 stable-release flow.

---

## Finish line

- Saving `tac=22222` publishes one schema-v3 payload and a long-running scoped target observes it no later than its currently scheduled refresh tick; later ticks use the configured interval.
- Settings offers bounded refresh choices `5 / 10 / 30 / 60` seconds, defaults to 30 seconds for old payloads, and never displays a value different from the runtime policy.
- “保存并验证” only navigates after both the Room save and cross-process publication succeed.
- Release `name.caiyao.fakegps` remains unhooked and usable as a real baseline.
- Release `name.caiyao.fakegps:hook_verify` is non-exported, reads the same published fingerprint under hook projection, returns a request-correlated observation, and terminates after the result.
- The Verify screen never calls deliberately empty release observations “hook 配置信息读不到”. It separately reports transport delivery, probe not scoped/timeout, observable verdicts, and fields with no public read surface.
- The debug transactional acceptance activity remains debug-only; production verification never publishes a test payload or mutates Room.

Not building: arbitrary third-party UI introspection, GMS fused-location redesign, a cross-app broadcast push channel, direct Vector database edits, or automatic scope changes.

## Runtime evidence that motivated this plan

The controlled moto g54 run on 2026-08-01 established the actual boundary:

- `22:51:08.403`: cross-process publish succeeded with fingerprint `sha256:a0670c0b1e412043`;
- `22:51:14.942`: Cellular-Pro PID 9167 accepted that fingerprint (6.5 seconds later, not 47 seconds);
- the Vector safe-zone contained `tac=22222` while the private `tac=11111` file was only a historical pre-redirection artifact;
- bringing the existing Cellular-Pro task to the foreground showed `TAC=22222` and `ECI=22222`;
- the release Verify page also read fingerprint `a0670c0b…`, schema 3 and the delivered field count. Its `读不到` rows came from the deliberate `REAL_BASELINE` observation route, not from failure to read the hook payload.

Therefore the reported refresh incident is closed as bounded propagation plus target-UI staleness, while the release verification page is a genuine product gap: it is honest about its limitation but cannot fulfill the user-facing promise implied by “伪装验证”.

## Stateful-object gate

### Object census

1. **Refresh preference** — owner: `SpoofSettings`; persisted once, projected into the transport payload, never independently persisted by the hook.
2. **Per-process refresh scheduler** — owner: one `MainHook` scheduler guard per module classloader/process; the current snapshot supplies the next delay.
3. **Verification request** — owner: `VerifyViewModel` for the request lifecycle and `HookVerificationService` for one probe execution; every result carries a request ID and published fingerprint.
4. **Profile save attempt** — owner: `ProfileEditorViewModel`; “保存” and “保存并验证” share one single-flight claim so only one Room/publication/navigation outcome can win.

### State × event transitions

| Object | State | Event | Next state / action |
|---|---|---|---|
| Refresh preference | absent | app upgrade/read | use 30s default without writing a second copy |
| Refresh preference | configured | user selects allowed value | persist once → publish same value in schema-v3 root |
| Refresh preference | configured | publication fails | keep preference, show publication failure, do not claim target propagation |
| Refresh preference | just published | previous hook cadence is unknown | keep debug self-hook verdict pending for at most the supported 60s maximum, never only the new/default cadence |
| Scheduler | not started | first eligible load-package callback | install hooks for that classloader and start one initial 3s tick |
| Scheduler | scheduled(N) | duplicate callback in same process | no second timer; hook registration remains idempotent per classloader |
| Scheduler | scheduled(N) | valid refresh with interval M | atomically replace snapshot → schedule next tick at sanitized M |
| Scheduler | scheduled(N) | timer or probe reload accepts interval M != N | emit `interval_changed(N→M)` in the owning process before the reload returns |
| Scheduler | scheduled(N) | malformed/unreadable payload | keep last-known-good snapshot and interval N → schedule next tick at N |
| Scheduler | any | process death | OS removes timer; next process starts from published payload |
| Probe request | idle | verify(requestId, fingerprint) | terminate/replace stale probe → start `:hook_verify` |
| Probe request | starting | process hooked and observer returns | deliver matching result → stop service → terminate probe process |
| Probe request | starting/observing | timeout/process death | visible `PROBE_UNAVAILABLE`; main UI stays alive and retryable |
| Probe request | observing | stale result for older requestId/fingerprint | ignore result; keep current request active |
| Probe request | delivered/failed | retry | new requestId; no stale report retained |
| Probe request | warm process + changed payload | verify(new fingerprint) | synchronously reload the hook-owned snapshot through the target-classloader sentinel; observe only after the active hook fingerprint matches |
| Probe request A + B | A is cancelled/times out after B starts | client cancels A | send request-scoped cancellation for A; B remains registered and its Service/executor stays alive |
| Probe service | one or more requests registered | one request completes/cancels | remove only that request; stop the Service only after the registry becomes empty |
| Profile save | idle | either save action is tapped | atomically claim one attempt, disable/no-op both save actions, then persist and publish once |
| Profile save | saving | either save action is tapped | ignore the duplicate action; do not insert, publish or navigate again |
| Profile save | saving | success/failure/cancellation | publish one terminal UI outcome, then release the single-flight claim |

### Invariants

- **INV-1:** Main release process is never hook-eligible; only debug main or release `:hook_verify` is eligible. JVM policy test.
- **INV-2:** The default, bounds and choices for refresh interval come from `HookRefreshPolicy`; UI and `MainHook` contain no numeric copies. Source/bytecode contract test.
- **INV-3:** Missing interval means 30s; invalid/hostile interval is clamped to 5–60s. Pure JVM matrix.
- **INV-4:** At most one refresh timer exists per process/module classloader, even when LSPosed invokes `handleLoadPackage` multiple times. Scheduler seam test plus fresh-process device log census.
- **INV-5:** A failed or malformed refresh preserves both last-known-good spoof values and its interval. JVM state-transition test.
- **INV-6:** Verify results are accepted only when request ID and fingerprint match the active request. Coordinator test.
- **INV-7:** Production probe is non-exported, never writes payload/Room, and release APK still excludes debug acceptance/recovery symbols. Manifest and APK scan.
- **INV-8:** “保存并验证” cannot navigate on validation, database or publication failure. ViewModel/navigation test.
- **INV-9:** `UNOBSERVABLE` means the probe invoked the relevant observation path but the platform exposed no value; probe unavailable/not scoped is a separate state. Verdict test.
- **INV-10:** Probe cancellation is keyed by request ID; a cancelled/expired screen never calls component-wide `stopService` while another request may own the same `:hook_verify` Service. Registry and compiled wiring tests.
- **INV-11:** The editor permits at most one active save across both save actions, so a new profile cannot be inserted twice and only one navigation outcome is emitted. Single-flight and compiled wiring tests.
- **INV-12:** A warm `:hook_verify` process synchronously reloads the Xposed-owned snapshot before observation and proves that snapshot's fingerprint matches the active request; app-side prefs alone never stand in for hook state. Sentinel default-authority and compiled wiring tests plus exact-build device retry.
- **INV-13:** A propagation timestamp remains pending through the longest supported previous cadence (60s), then expires; switching 60s→5s never creates a 30–60s false-red gap. JVM boundary tests.
- **INV-14:** Every accepted reload path that changes the scheduler cadence—timer or probe bridge—emits process/PID-bound `interval_changed` evidence. Compiled hook contract plus host trace matrix.
- **INV-15:** The host verifier tolerates only an incomplete logcat prefix before the first retained request and only when the latest attempt is complete; current/incomplete attempts remain strict. Python contract tests.

### Adversarial scenarios

- Upgrade from the current payload with no refresh field.
- Change 60s → 5s while a 60s tick is pending: one bounded old tick is allowed; subsequent ticks are 5s and UI explains the transition.
- Warm probe reload changes 30s → 5s before the next timer tick; `interval_changed` updates the host's PID-bound scheduler state before delivery.
- Duplicate load-package callbacks in Cellular-Pro and NetworkStack do not multiply timers or getter hooks.
- Probe package absent from Vector scope returns a scoped diagnostic, never a false `MISMATCH` roll-up.
- Probe dies after request but before delivery; retry succeeds without stale-green UI.
- An old Verify screen is cleared after a newer screen has started; cancelling the old request leaves the newer probe alive and deliverable.
- Save a changed payload and verify again inside the 500ms warm-process grace window; the hook snapshot reloads to the new fingerprint before any public API observation.
- Start logcat with a historical delivered/failed prefix whose request rolled out, followed by a complete latest probe; current acceptance ignores only that prefix and remains strict after the first retained request.
- Double-tap “保存” / “保存并验证” before Room returns; exactly one row is written and exactly one navigation outcome wins.
- User saves a profile whose configured value equals the real baseline; result stays `AMBIGUOUS`.
- A field has no public read surface; it remains `NOT_VERIFIABLE`, not probe failure.

## Task 1: Lock the canonical refresh policy

**Files:**
- Create: `app/src/main/java/name/caiyao/fakegps/config/HookRefreshPolicy.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/config/HookRefreshPolicyTest.kt`
- Modify: `app/src/test/java/name/caiyao/fakegps/config/PublishedConfigTest.kt`

1. Add failing JVM cases for missing, `0`, negative, above-max and each supported interval.
2. Run `./gradlew :app:testDebugUnitTest --tests '*HookRefreshPolicyTest' --tests '*PublishedConfigTest'` and confirm RED.
3. Implement `DEFAULT_MS=30_000`, `MIN_MS=5_000`, `MAX_MS=60_000`, `CHOICES_MS`, and `sanitize(raw)` as the only policy.
4. Extend `PublishedConfig` with the optional top-level interval while keeping schema v3 compatible.
5. Re-run the targeted tests and commit the policy slice.

## Task 2: Persist, publish and schedule the selected interval

**Files:**
- Modify: `app/src/main/java/name/caiyao/fakegps/data/SpoofSettings.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/hook/MainHook.java`
- Modify: `app/src/main/java/name/caiyao/fakegps/config/PublishPropagation.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/hook/MainHookRefreshContractTest.java`
- Modify: `app/src/test/java/name/caiyao/fakegps/config/PublicationPendingSeamTest.kt`

1. Add RED tests that payload publication carries the preference, missing data keeps 30s, malformed reload keeps the old delay, and production source contains no hard-coded `30 * 1000` / UI `60 秒` copies.
2. Add a scheduler seam whose second `handleLoadPackage` event cannot create a second loop; keep hook installation idempotent per classloader.
3. Persist the setting in `SpoofSettings`, publish it in the root payload, and make the handler schedule its next tick from the sanitized loaded value.
4. Keep the initial 3-second tick for newly started processes. Document that changing the interval may wait for the already scheduled old tick once.
5. Run targeted tests, then `:app:testDebugUnitTest`, and commit.

## Task 3: Make Settings and profile navigation truthful

**Files:**
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/editor/ProfileEditorViewModel.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/editor/ProfileEditorScreen.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/navigation/NavGraph.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/ui/RefreshSettingsContractTest.kt`
- Modify: `app/src/test/java/name/caiyao/fakegps/ui/VerifyUiContractTest.kt`

1. Add RED UI/ViewModel contract tests: actual default text is 30s, every choice comes from policy, selection republishes, and “保存并验证” emits navigation only after `SaveResult.published=true`.
2. Replace the dead “60 秒” row with a selectable dialog backed by the persisted flow.
3. Add an explicit “保存并验证” action to the editor; do not add a blind jump that verifies an unsaved draft.
4. Run targeted JVM tests and Compose compilation. Commit the UI slice without installing it over the stable device build.

## Task 4: Add a production one-shot hook verification process

**Files:**
- Create: `app/src/main/java/name/caiyao/fakegps/verify/RuntimeSelfHookPolicy.kt`
- Create: `app/src/main/java/name/caiyao/fakegps/verify/HookVerificationService.kt`
- Create: `app/src/main/java/name/caiyao/fakegps/verify/ProbeObservationCodec.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/name/caiyao/fakegps/hook/MainHook.java`
- Modify: `app/src/main/java/name/caiyao/fakegps/verify/ObservationScope.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/verify/VerifyViewModel.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/verify/VerifyScreen.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/verify/RuntimeSelfHookPolicyTest.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/verify/ProbeObservationCodecTest.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/verify/VerificationRequestStateTest.kt`

1. RED-test the release self-hook rule: main process false, `:hook_verify` true, debug main true, unrelated package unchanged.
2. RED-test request/fingerprint correlation, timeout, stale-result rejection, retry and no stale-green report.
3. Register an `exported=false`, same-package `:hook_verify` service. It receives no profile values, mutates no stores, observes public APIs and returns a request-correlated encoded result.
4. Start each user verification as a fresh one-shot probe lifecycle; terminate stale probe state before the next request and make process death visible/retryable.
5. Feed probe observations into the existing `VerificationEngine`; keep main-process readings as baseline only. Add explicit “probe 未被 Vector 加入作用域” and timeout states.
6. Run JVM tests plus `compileDebugAndroidTestKotlin`, then commit.

## Task 5: Device acceptance without sacrificing the stable installation

**Files:**
- Create: `scripts/test_runtime_verify_flow.py`
- Create: `scripts/test_runtime_verify_flow_contract.py`
- Modify: `scripts/test-hook.sh`

1. Add host-side RED contract tests for parsing request ID, fingerprint, interval and probe-state evidence.
2. Build and review first; do not install an unreviewed APK. Preserve the user's current profile and record its fingerprint.
3. After authorization, install the reviewed release with `adb install -r`, verify the database/profile remains intact, and ensure Vector scope includes FakeGPS plus Cellular-Pro.
4. Save one distinct field, run “保存并验证”, and require: safe-zone fingerprint match, probe result match, Cellular-Pro public surface match after the bounded tick.
5. Exercise 5s and 60s settings, missing-field compatibility, probe-not-scoped, probe-process death and retry. Restore the original interval in `finally`.
6. Scan the release APK to prove debug acceptance/recovery classes remain absent and the production probe service is non-exported.

## Task 6: Quality gate and independent review

1. Run `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --rerun-tasks`.
2. Run all Python tests under `scripts/`.
3. Run `assembleDebug`, `compileDebugAndroidTestKotlin`, `assembleRelease` with R8, and `lintVitalRelease`; classify the existing `lintDebug` baseline separately.
4. Run `bash -n scripts/test-hook.sh` and `git diff --check`.
5. Load `quality-gate`, then request an independent cross-individual review. The reviewer must inspect scheduler ownership, probe process isolation, release APK contents and the moto g54 evidence.
6. Only after approval, install the reviewed release over the stable build and complete the device matrix. No uninstall, Vector DB edit or profile recreation.

## Open questions

- **Technical:** Confirm on the moto g54 that Vector invokes `MainHook` for the same-package `:hook_verify` process when FakeGPS is in scope. If it does not, stop after the spike and choose a separately scoped companion package; do not weaken the main-process isolation invariant.
- **Technical:** Explain and remove the observed two timer loops in Cellular-Pro and three in NetworkStack before allowing the 5-second option.
- **Value:** None. The operator has explicitly asked for usable stable verification, a configuration-screen shortcut and configurable refresh timing; the implementation choices above preserve the stable UI and user data boundaries.

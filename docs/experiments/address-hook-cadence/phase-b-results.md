---
feature_ids: [F-HOOK-CADENCE]
topics: [hook, latency, fileobserver, inotify, measurement]
doc_kind: experiment-result
created: 2026-08-03
---

# Phase B Results: Event-Driven FileObserver

## Summary

Phase B replaces the polling timer with an inotify-based `FileObserver` as the
primary config propagation path. The timer continues as a safety-net heartbeat.
A SHA-256 fingerprint skip makes redundant reloads (duplicate inotify events,
timer heartbeats) near-free.

**Result: 57x faster config propagation (14.5s -> 0.255s mean).**

## Measured Latency

### Phase B (10 rounds, random phase, seed=99)

| Metric | Value |
|--------|-------|
| samples | 10 |
| min | 0.224s |
| mean | **0.255s** |
| median | 0.268s |
| max | 0.278s |
| std dev | ~0.020s |

### Phase A Baseline (from phase-a-insight.md)

| Metric | Default 30s | Configured 5s |
|--------|-------------|---------------|
| mean | 14.5s | 2.5s (theoretical) |
| distribution | uniform(0, interval) | uniform(0, interval) |

### Improvement

| vs Baseline | Factor |
|-------------|--------|
| 30s default (mean 14.5s) | **57x** |
| 5s configured (mean 2.5s) | **10x** |
| 30s worst case (max ~30s) | **108x** |

## Key Observations

1. **Latency is constant and independent of timer interval.** Phase skew
   (0.13s to 3.80s across rounds) has zero correlation with observed latency.
   The observer fires on inotify event, not on timer tick.

2. **The ~250ms floor is measurement overhead**, not propagation latency.
   Composed of: adb shell roundtrip (~100ms) + logcat timestamp resolution
   (3 decimal places = ms granularity) + probe write latency. Actual kernel
   inotify delivery is sub-millisecond.

3. **Fingerprint skip eliminates timer tick noise.** In 20 seconds of
   observation (4 timer ticks per process at 5s interval), zero `transport
   accepted` log entries were produced. Every tick hit the fingerprint skip and
   returned without JSON parsing. Before Phase B, each tick produced a
   `transport accepted` log.

4. **Fail-closed verified.** The observer arm evidence
   (`event=observer_armed`) was confirmed in logcat for `com.google.android.apps.maps`.
   If arm had failed, `event=timer_fallback` would appear and the timer would
   run alone at configured interval (no regression).

## Device Evidence

### Observer arm (logcat)
```
FakeGPS-Hook: event=scheduler_owned process=com.google.android.apps.maps intervalMs=5000
FakeGPS-Hook: event=observer_armed process=com.google.android.apps.maps dir=/data/misc/6997007a-de90-4cd8-8b44-9c0a17e91ed1/prefs/name.caiyao.fakegps
```

### Probe output (10 rounds)
```
samples 10 | min 0.224 | mean 0.255 | median 0.268 | max 0.278 (s)
configured 5s -> theoretical uniform(0,5), mean 2.5s
```

### Raw data
```json
[0.278, 0.224, 0.231, 0.225, 0.234, 0.276, 0.269, 0.276, 0.275, 0.267]
```

## Architecture

```
Config write (SharedPreferences.commit())
    |
    v
atomic rename (old inode -> new inode)
    |
    v
inotify MOVED_TO event on directory
    |
    v
PrefsDirectoryObserver.onEvent()
    |
    v (filename filter)
    |
    v
MainHook.reloadSnapshot()
    |
    v (SNAPSHOT_LOCK)
    |
    v
loadSnapshot()
    |
    v (fingerprint skip: SHA-256 compare)
    |        match -> return CURRENT (no parse)
    v        miss  -> full JSON parse + Snapshot build
    |
    v
CURRENT.set(newSnapshot)  <-- all ~25 hooks serve new value
```

Timer heartbeat runs in parallel (same `reloadSnapshot()` path). Fingerprint
skip makes timer ticks near-free when observer has already delivered the
latest change.

## Test Evidence

- 47 test suites, 0 failures, 0 errors
- 2 new bytecode contract tests verify observer + fingerprint wiring
- Device: moto g54 5G (ZY22JHW9M4), Android 15, LSPosed

## Commit

`6b3eb5b` on `feat/address-hook-cadence`

[宪宪/claude-opus-4-6]

---

# Extended Validation Matrix (R2 follow-up, HEAD `42ae1d2`)

Sol's R2 review flagged a validation gap: the original 57x figure covered only
single-process, file-injected stimulus. This section closes that gap with
multi-process, real-UI-publish, frozen-process, and cold-start evidence.
Device: moto g54 5G (ZY22JHW9M4), Android 15, LSPosed (zygisk_vector), SELinux
Enforcing. Module under test: release APK built from `42ae1d2`, installed as
`name.caiyao.fakegps`.

## 1. Multi-process latency (2 live target apps simultaneously)

Processes armed: `com.google.android.apps.maps` (pid 21096→23472 across
restarts), `com.hopefactory2021.fakegpslocation` (pid 21128), and
`com.google.android.apps.maps:server_recovery_process`. All three logged
`event=observer_armed` on the same prefs directory; zero
`observer_arm_failed` / `timer_fallback` events.

6 probe rounds (`--seed 42 --settle 3`), both live processes accepting every
round (12 samples):

| Metric | Value |
|--------|-------|
| min | 0.174s |
| mean | **0.208s** |
| median | 0.214s |
| max | 0.244s |
| intra-round cross-process delta | ≤ 0.002s |

Raw data: `phase-b-raw-multiprocess.json`.

The ≤2ms intra-round delta is the empirical existence proof of the "global
commit instant" Phase A found missing: one file write commits all live hooked
processes within measurement resolution of each other. (Probe numbers include
~100-200ms adb/logcat overhead; see §2 for the tighter real-path figure.)

## 2. Real UI publish (end-to-end, no file injection)

Drove the actual write path: Settings → 刷新间隔 → picked a new value in the
app UI. This exercises Compose → SettingsViewModel → RefreshIntervalUpdate →
ConfigPrefsSync (Room open + ContentProvider queries + JSON build + atomic
commit) → inotify → hook reload. Correlated by payload fingerprint between the
app-side `ConfigPrefsSync: published … fp=` log and hook-side
`transport accepted … fp=` logs.

| Publish (device time) | Payload change | Hook accept (per process) | Publish→accept |
|---|---|---|---|
| 02:24:27.071 | interval 5s→10s, fp=769b68e1… | Maps 02:24:27.078 | **7ms** |
| 02:26:44.033 | interval 10s→5s, fp=a46f31b7… | hopefactory .038, Maps .040 | **5ms / 7ms** |

Both publishes also produced `event=interval_changed` evidence per process
(5000↔10000ms), confirming the scheduler adopts payload-driven intervals on
the observer path. The ~200ms probe figure is therefore confirmed to be
dominated by harness overhead; real propagation is single-digit milliseconds.
The second publish restored the user's original payload (fp matches the
pre-test baseline) — device config left semantically unchanged.

## 3. Frozen / cached processes (Android app freezer)

During testing, two processes stopped accepting while still alive:
`maps:server_recovery_process` (pid 23649) and later
`com.hopefactory2021.fakegpslocation` (pid 21128). Root cause confirmed via
kernel evidence, not inference:

```
08-03 02:21:13 ActivityManager: freezing 23649 … reason = oom_cached
/sys/fs/cgroup/uid_10373/pid_21128/cgroup.freeze = 1
```

Android's cached-app freezer suspends all threads of a backgrounded process —
the timer Handler and the FileObserver thread alike. This is OS behavior, not
an observer defect, and it bounds the "global refresh" claim to *live*
processes. Recovery verified: on re-launch (unfreeze), pid 21128 accepted the
payload published 66s earlier on its very first heartbeat tick
(02:25:33.166, `interval_changed 10000→5000` included) — the timer safety net
converges frozen processes on wake, exactly the fail-closed design intent.

## 4. Cold process start

`handleLoadPackage` performs a synchronous `reloadSnapshot()` before hook
registration and before the timer's `INITIAL_DELAY_MS` ever matters. Captured
at Maps cold start (after `am force-stop`):

```
02:21:01.390 FakeGPS: Loaded config for com.google.android.apps.maps | location=true | cellRebuild=true
02:21:01.484 event=scheduler_owned …
02:21:01.489 event=observer_armed …
```

A fresh process serves the current config from its first hook invocation; the
Phase A concern about a 3s passthrough window does not apply to the serving
path (the initial load is synchronous, not timer-gated).

## 5. Pre-existing gap observed (not introduced by this change)

`maps:server_recovery_process` logs `fused impl NOT found, falling back to
abstract … FusedLocationProviderClient` followed by "Cannot hook abstract
methods" skips — the GMS obfuscation brittleness already catalogued in
phase-a-insight.md §6. Unchanged by this branch; noted so R3 review does not
mistake it for a regression.

## Verdict

The R2 validation gap is closed: propagation is 5-7ms on the real UI path,
≤0.25s (harness-dominated) under file injection, simultaneous across live
processes (≤2ms spread), and fail-closed for frozen (converge on wake) and
cold (synchronous initial load) processes.

[墨墨/kimi-k3🐾]

---

# R3 Follow-up: Observer Directory-Deletion Recovery (HEAD `4b711c3`)

Sol R3 P1 #2: if the watched prefs directory is deleted/moved, the kernel
delivers `IN_IGNORED` and drops the inotify watch — but `armed` stayed `true`
forever, so the heartbeat lazy-retry (which checks `isArmed()`) never fired.
The event-driven path would have stayed silently dead until process restart
even after the app recreated the directory; only the timer masked it.

**Fix (TDD, red first):** `PrefsDirectoryObserver.onEvent` now calls
`disarm()` on `IN_IGNORED` (kernel constant `0x8000`, not exposed in the
`FileObserver` public API), which clears `armed` and logs watch-loss evidence.
Red test `observerDisarmsOnKernelWatchLoss` failed before the fix and passes
after; full suite 741 tests, 0 failures.

**Device verification** (moto g54, release build of `4b711c3`, Maps pid 31759
+ hopefactory pid 31788, both `observer_armed`):

| Time | Event |
|---|---|
| 02:52:03 | `su rm -rf` the watched prefs directory |
| 02:52:11.333 | both processes log `observer watch lost (IN_IGNORED) — heartbeat will re-arm` |
| 02:52:15+ | heartbeat retry logs honest failure evidence: `observer dir does not exist: …` (timer keeps serving last-known-good) |
| 02:52:35.6 | app cold start republishes (`fp=da9d3b17…`), recreating the directory |
| 02:52:35.855 | hopefactory: `event=observer_armed` again + `transport accepted` |
| 02:52:40.294 | Maps: `event=observer_armed` again + `transport accepted` |

Full cycle — kernel watch loss → disarm → honest retry evidence →
self-recovery on next heartbeat after the directory returns — verified on
device. No process restart needed.

**Test artifact note (not a defect):** the deletion also removed
`spoof_settings.xml` (same directory), which reset the app's refresh-interval
preference to its 30s default on next publish. The interval was restored to
the user's 5s via the real UI path; payload fingerprint returned to the
pre-test baseline `sha256:a46f31b771af1555` (confirmed by publish log
`bytes=241` + fp match).

**R3 P1 #1 (end-user address change) status:** blocked at measurement time by
operator device contention — the operator was actively testing on the device
(profile changed to 50.4501/30.5234 outside the test harness, app process
restarting, publishes transiently falling back to `MODE_PRIVATE`). Escalated
to co-creator; will complete once device control is exclusive again.

[墨墨/kimi-k3🐾]

---

# R3 P1 #1: Does the served value actually change for the user? (HEAD `2699f67`)

Answer: **yes on every hooked surface, no on the GMS fused surface** — and the
"no" is not a latency problem but a pre-existing coverage gap that refresh
speed cannot fix.

## Method (all real UI, no file injection)

1. Baseline: profile at Zhytomyr (50.22983352306213, 28.712424219647062),
   real device physically in Kyiv (editor shows 本机真实值 50.450956 /
   30.4102236).
2. Real UI path: 收藏档案 → profile row → editor → typed Lviv
   (49.8397, 24.0297) into the 纬度/经度 fields → 保存并验证.
3. Observed three independent readouts of what apps actually receive.

## Evidence

**(a) Publish → accept (transport):** `published crossProcess=true fp=sha256:1928d4f045a08764`
at 10:32:18.439; `:hook_verify` probe process accepted the same fingerprint at
10:32:18.732 — **293 ms including a cold probe-process start**.

**(b) Hooked public-API readback follows the change.** The verify screen
(independent `:hook_verify` process reading public `LocationManager` through
the hooks, request-ID + fingerprint matched to THIS publish):

| | 配置 | 探针观测 | verdict |
|---|---|---|---|
| before | 50.22983352306213 | 50.22983352306213 | 已生效 |
| after | 49.8397 | 49.8397 | 已生效 |

The hooked surfaces serve the new coordinates within one observer event of
the UI save. The refresh-speed work is validated end to end.

**(c) Google Maps does NOT follow — it shows the REAL location.** Blue dot
sat on Kyiv Nyvky (~50.458, 30.405) before AND after the Zhytomyr→Lviv
change. Root cause confirmed in logcat from the Maps main process (pid 14008):

```
FakeGPS: fused impl NOT found, falling back to abstract com.google.android.gms.location.FusedLocationProviderClient
FakeGPS: Hook registration skipped: Cannot hook abstract methods: … getLastLocation()
FakeGPS: Hook registration skipped: Cannot hook abstract methods: … getCurrentLocation(int,bkve)
FakeGPS: Hook registration skipped: Cannot hook abstract methods: … requestLocationUpdates(…)
```

`resolveFusedImpl()` knows only two historical impl class names; the GMS
build on this device (Android 15, current Play Services) renamed it again, so
every fused hook is silently skipped and Maps receives genuine fused
location. This is the Phase A §6 GMS-obfuscation gap manifesting
user-visibly: for Maps there is no staleness problem because **no spoofed
value ever arrives**.

## Implications

- The mission's latency claims stand for the ~25 hooked surfaces (validated
  by (b)). They are meaningless for the fused path until `resolveFusedImpl`
  handles current GMS obfuscation — a **separate work item**, not a refresh
  fix (adding a third hardcoded class name would repeat the fragile pattern;
  the resolution strategy itself needs redesign).
- README:12's fused coverage claims should be reconciled with this reality in
  that follow-up.

## Environment restored

Profile edited back via the same UI path; publish log confirms
`fp=sha256:a46f31b771af1555 bytes=241` = exact pre-test baseline. Interval
untouched (5s). Device retains the branch release build (`2699f67`) installed
during testing.

[墨墨/kimi-k3🐾]

---
feature_ids: [address-hook-cadence]
topics: [hook, refresh, latency, transport, selinux, measurement]
doc_kind: insight
created: 2026-08-02
anchor_commit: c34bf666983da3634dfba5f2b4c3ce0147cde019
device: moto_g54_5G (cancunf) / Android 15 / SDK 35 / Magisk + zygisk_vector / SELinux Enforcing
status: Phase A complete — awaiting review before any implementation
---

# Phase A — Address hook refresh cadence: insight and baseline

Read-only investigation. No module code changed on this branch. The only new
files are the measurement harness (`scripts/refresh_latency_probe.py`) and this
report plus its captured data.

---

## 0. The framing needs one correction before anything else

**There is no address hook, and there never was.**

- Zero references to `android.location.Geocoder`, `getFromLocation`,
  `getFromLocationName`, or `android.location.Address` anywhere in `app/src/`.
  The only reverse-geocoding code in the repo is entirely commented out
  (`ui/map/ReGeocoderActivity.java:20-70`, dead Amap legacy).
- The `addname` field that rides in the transport payload is a **display label**,
  generated as `String.format("%.6f, %.6f", lat, lon)`
  (`ui/fragment/ProfileEditorFragment.java:335-339`,
  `ui/screen/editor/ProfileEditorViewModel.kt:260`). It is explicitly excluded
  from spoofed columns (`ProfileEditorViewModel.kt:166`) and pinned as
  `NOT_DEVICE_OBSERVABLE` in verification (`verify/VerificationEngine.kt:181,190`).
  No hook ever reads it.

The address a user sees in Google Maps is **Google reverse-geocoding our spoofed
lat/lng inside its own process**. We supply coordinates; Google derives the
address. So "提升地址 Hook 全局刷新速度" resolves, without loss, to:

> Make a newly published coordinate become the value served by **every** hooked
> process as fast as possible.

That is the metric the rest of this report measures. Reverse-geocoding coverage
is a separate (real, unaddressed) gap — see §6.

---

## 1. Operator's hypothesis: confirmed, and the mechanism is exact

> 假设 Google 地址更新频率快于当前 FakeGPS hook 刷新

Confirmed. But the useful precision is in *which* half is slow.

**The serving path is not slow.** All ~25 location hooks funnel through one
accessor, `HookUtils.currentSnapshot()` (`HookUtils.java:105-108`), which reads
`MainHook.CURRENT.get()` — an `AtomicReference<Snapshot>` (`MainHook.java:62`) —
at invocation time. The moment `CURRENT` is set, every surface serves the new
value on its very next call, with no further delay and no per-surface work.

Google's high query rate is therefore an **asset**, not the problem: because GMS
re-queries at roughly 1 Hz, the observable staleness is bounded almost exactly by
our own commit instant.

**The commit instant is the entire problem.** `CURRENT` is only ever written by a
periodic timer:

```java
// MainHook.java:113-123
final Handler handler = new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) {
        if (msg.what == 1) { reloadSnapshot(lpparam.processName); }
        sendEmptyMessageDelayed(1, REFRESH_SCHEDULER.currentDelayMs());
    }
};
handler.sendEmptyMessageDelayed(1, HookRefreshScheduler.INITIAL_DELAY_MS);
```

So the frequency mismatch is:

| | rate |
|---|---|
| GMS location consumption | ~1 Hz |
| Our snapshot refresh, fastest setting (5 s) | 0.2 Hz |
| Our snapshot refresh, **default** (30 s) | 0.033 Hz |

A ~30× mismatch at the fastest setting, ~900× at the shipped default.

**The transport is strictly pull.** There is no push channel anywhere: no
`FileObserver`, no `BroadcastReceiver` on the config path (the only receiver in
the tree is the unrelated legacy `ScreenListener`), no socket, and the
`ContentProvider` is `android:exported="false"` (`AndroidManifest.xml:67-69`) —
deliberately, because Android 11+ package-visibility made it unusable
(`ConfigPrefsSync.kt:19-25`, `MainHook.java:35-40`).

Write side is already effectively instantaneous: `ConfigPrefsSync.sync()` uses a
blocking `editor.commit()` (`ConfigPrefsSync.kt:118`), not `apply()`. Measured
end to end in logcat at **17 ms** from `sync() ENTER` to `published crossProcess=true`.

**Conclusion: 100 % of the user-visible staleness is the polling gap.**

---

## 2. Measured baseline (real device, isolated)

Harness: `scripts/refresh_latency_probe.py`. It correlates the two ends by the
payload's SHA-256 fingerprint, which both sides already log in the shipped build
— so measurement adds **no probe code to the module**:

- write side: `ConfigPrefsSync.kt:123-127` → `published … fp=sha256:…`
- read side: `MainHook.java:206-207` → `transport accepted schema=3 fp=sha256:…`

Matching on fingerprint rather than wall-clock proximity is what makes a sample
attributable to a specific publish instead of to whichever periodic tick landed
nearby. All deltas are device-clock minus device-clock, so host/device skew
cannot enter. The prefs file is rewritten in place (`base64 -d > target`) to
preserve inode/owner/mode/SELinux context, and restored with md5 verification on
success, failure and SIGINT.

### 2a. Passive cadence census (zero writes) — `--observe-only 40`

| PID | process | ticks | mean period | stdev |
|---|---|---|---|---|
| 2474 | `com.android.networkstack.process` | 8 | 5.024 s | 0.003 |
| 17582 | `com.hopefactory2021.fakegpslocation` | 8 | 5.021 s | 0.002 |

- **24.0 reloads/min** across 2 processes.
- **1 distinct fingerprint** across all 16 reloads.

Every one of those 16 reloads opened the file, re-read it, re-parsed the JSON and
rebuilt a `Snapshot` — to produce a value identical to the one already held.
At steady state the current design does **100 % wasted work, forever**.

### 2b. End-to-end propagation latency

Two sampling modes, because the first run revealed a bias in my own harness
(disclosed rather than quietly dropped — see §5).

**Worst case (`--phase locked`, writes land just after a tick):**

| interval | samples | min | mean | median | max |
|---|---|---|---|---|---|
| 5 s | 10 | 0.547 s | 2.160 s | 2.485 s | **2.792 s** |
| 30 s | 8 | 3.310 s | 21.544 s | 27.520 s | **27.699 s** |

**Unbiased (`--phase random --seed 7`, write phase decorrelated from tick),
30 s interval, 6 rounds** — `baseline-30s-unbiased.json`:

| samples | min | **mean** | median | max | analytic mean |
|---|---|---|---|---|---|
| 6 | 1.328 s | **14.519 s** | 14.574 s | 25.446 s | **15.0 s** |

Measured mean 14.519 s against an analytic 15.0 s — a 3 % gap on 6 samples.
Per-round latency tracks the injected phase skew as the model predicts (skew
19.53 s → 22.839 s; skew 2.17 s → 8.353 s), so the distribution is genuinely
`uniform(0, T)` and not an artefact of the harness.

Both modes are consistent with the analytic model: latency is
`uniform(0, T)`, `T ∈ {5,10,30,60}`, default 30. The model is now empirically
confirmed at both the median and the tail, which is what makes it safe to
predict the other interval settings without measuring each one.

**At the shipped default a user waits a mean of 15 s and up to 30 s** after
saving before Google can possibly show the new address — and at the fastest
selectable setting still up to ~5 s.

### 2c. Interval changes are doubly delayed — observed live

Round 1 of the 30 s run returned **3.310 s**, well under the 30 s just written.
That is the defect documented at `PublishPropagation.kt:117-127` caught in the
act: a new cadence only takes effect on a tick still governed by the **old**
cadence. Switching 60 s → 5 s takes up to 60 s to begin. This is also why the
UI's pending window is pinned at the maximum 60 s (`PublishPropagation.kt:29-34`)
rather than the newly chosen value.

---

## 3. Compounding defects found (all independent of the main one)

1. **No change detection, though the fingerprint is right there.** It is computed
   at `MainHook.java:199` and stored at `:285`, but never used to skip work.
   Every tick reparses unconditionally. Measured: 16/16 reloads redundant (§2a).
2. **The work runs on the target app's main thread.**
   `new Handler(Looper.getMainLooper())` (`MainHook.java:113`) — file I/O plus
   JSON parse plus `Snapshot` construction on **Google Maps' UI thread**,
   12×/min at the 5 s setting. This is the real reason a 5 s floor exists; the
   cost is self-inflicted by defect 1.
3. **Per-process timers have independent phase.** `claimScheduler()`
   (`HookRuntimeOwnership.java:14-16`) correctly permits exactly one timer per
   process, but N hooked processes means N pollers with unrelated phase.
   Measured: pids 2474 and 17582 sat a consistent ~0.21 s apart and never
   converged. **There is currently no global commit instant at all**, so "全局
   刷新" is not merely slow — it is undefined.
4. **The 5 s floor rests on a defect that no longer exists.**
   `PublishPropagation.kt:53-56` justifies the floor with "the runtime currently
   re-reads more than once per cycle per process — see the duplicate scheduler
   finding". That finding
   (`docs/bug-report/runtime-verifier-process-lifetime/bug-report.md:34-39`) was
   a **host log-parser false positive across PIDs**, not a runtime defect, and
   `claimScheduler()` now enforces one timer per process. The comment overstates
   the cost and should not be treated as a constraint on Phase B.
5. **`published_at` is rewritten on every app launch** (`ComposeActivity.kt:21`
   republishes unconditionally), so the verify screen's 60 s "刚保存，尚未生效"
   softening can be re-armed with no config change. Confirmed live: a cold start
   republished the identical payload `fp=sha256:a46f31b771af1555`.
6. **Two divergent prefs files exist.** The hook reads the Vector-redirected
   `/data/misc/<uuid>/prefs/name.caiyao.fakegps/spoof_config.xml`, while
   `ConfigPrefsSync.readPublished/readPublishedAt` request `MODE_PRIVATE`
   (`ConfigPrefsSync.kt:223,236,242`). On device the app-private copy at
   `/data/data/.../shared_prefs/spoof_config.xml` was **27 hours stale with a
   different size and md5** than the file the hook actually consumes. Flagged,
   not chased — outside this line's scope, but it undermines the verify screen's
   claim to reconcile against "the exact payload the hook consumes"
   (`ConfigPrefsSync.kt:212-220`).

---

## 4. Two constraints that any Phase B design must satisfy

Both were settled empirically, because both would have silently broken the
obvious implementation.

### 4a. The app's write REPLACES the inode — a file watch would die

Measured across a real cold-start republish:

```
before: inode=54164   after: inode=49560   (same path)
```

`SharedPreferences.commit()` renames the old file aside and creates a new one.
A `FileObserver` constructed on the **file path** holds a watch on the now
unlinked inode: it would fire once during development, then never again, and
the failure is invisible — the timer would keep masking it.

**Any event-driven design must watch the directory** with
`MOVED_TO | CLOSE_WRITE | CREATE` and filter by name.

### 4b. Directory-level inotify IS permitted under SELinux — verified by policy query

`FileObserver` on a directory requires `dir { read }` (plus `search`), which is
strictly more than the `dir { search } + file { read }` that `XSharedPreferences`
needs today. So "reads already work" does **not** imply "inotify will work".

Queried the kernel directly via `/sys/fs/selinux/access` (write and read on one
fd; the node is stateful per descriptor). Target type `u:object_r:xposed_data:s0`,
class `dir` (index 8):

| source domain | allowed mask | `read` (0x2) | `search` (1<<28) |
|---|---|---|---|
| `u:r:untrusted_app:s0:c117,…` | `fffffe77` | ✅ | ✅ |
| `u:r:network_stack:s0` | `ffffffff` | ✅ | ✅ |

Denied bits for `untrusted_app` are only `create`, `relabelfrom`, `relabelto` —
none of which inotify needs. The directory is labelled `xposed_data:s0` with **no
MLS categories**, which is also why a `c117` app can read a `c130` file at all.

This de-risks the central Phase B assumption on this device/framework. It does
**not** generalise to other Xposed frameworks or Android versions, so the design
still needs the fail-closed fallback in §7.

(Note: an earlier attempt to test this with `runcon … inotifyd` failed with
`Permission denied` — that measured `untrusted_app`'s inability to *exec* a
system binary, not inotify. Recorded so the same dead end is not retried.)

---

## 5. Honest limitations of this measurement

- **My first harness was biased and I fixed it.** Writing immediately after each
  acceptance phase-locks the sample to the end of the period, so the 30 s run
  reported a 27.5 s "mean" where the true user-facing mean is ~15 s. The
  locked-phase numbers in §2b are valid **worst-case** figures and are labelled
  as such; `--phase random` was added for the unbiased distribution.
- **Only 2 processes were live, and only 1 survived the unbiased run.** Google
  Maps was not among them — the module's current scope covers
  `com.android.networkstack.process` and `com.hopefactory2021.fakegpslocation`.
  During the 6-round unbiased run the latter was no longer reporting, so those
  6 samples come from a single process; the cross-process phase spread in §3.3
  rests on the §2a/§2b-locked runs where both were alive. The cadence model is
  per-process and scope-independent, but a Maps-specific acceptance run and a
  multi-process re-run are still owed.
- **Stimulus is a direct transport-file write**, deliberately: driving the UI
  would fold Room/ContentProvider/Compose work into the number and would not be
  reproducible tick-to-tick. The app-side publish path was measured separately
  (17 ms) and is not a contributor.
- **Cold/background/process-death matrices are not yet run.** `INITIAL_DELAY_MS`
  is 3 s (`HookRefreshScheduler.java:9`), so a freshly started process serves
  `PASSTHROUGH` — real device location — for up to 3 s before its first load.
  That deserves its own measurement in the acceptance matrix.

---

## 6. Coverage gaps that bound "全局" (reported, not silently absorbed)

Even with instant propagation, these surfaces still leak the real location:

- **`Geocoder` entirely unhooked** — an app that reverse-geocodes directly gets
  the real address regardless of our coordinate spoof.
- **`GnssStatus.Callback` (API 24+) unhooked** — only the deprecated `GpsStatus`
  path is covered (`HookUtils.java:848,864`), and with a hardcoded 5-satellite
  constellation that is trivially fingerprintable.
- **Modern NMEA listeners unhooked** — only the deprecated
  `addNmeaListener(GpsStatus.NmeaListener)` is blocked (`:218-227`); the API-24+
  overloads pass raw lat/lng through.
- **`getLastKnownLocation(String, LastLocationRequest)` (API 33+) unhooked** —
  `:128` pins an exact signature instead of `hookAllMethods`.
- **GMS resolution is brittle** — `resolveFusedImpl` (`:1537-1553`) knows two
  class names; a newer/obfuscated GMS falls back to the abstract class and the
  hooks fail with only a log line. Hardcoded field names `mLocations`,
  `mIsLocationAvailable`, `mResult` share that fragility.
- **README contradicts code** — `README.md:12` claims `PendingIntent` location
  updates are covered; `HookUtils.java:1596` explicitly declares them out of scope.

These are coverage, not cadence, and are **out of scope for this line** unless
the operator widens it. They are listed because the charter asks for "覆盖更全"
and it would be dishonest to report a latency win as if it were global coverage.

---

## 7. Proposed Phase B direction (design only — not implemented)

Not yet built; recorded so review can attack the design before code exists.

**One authoritative refresh commit point, event-triggered.** The codebase already
has the right shape: one `AtomicReference`, one commit function
(`reloadSnapshotLocked`), one ownership gate (`claimScheduler()`). The defect is
purely the **trigger**. So the change is to replace the trigger, not to add
fallback layers to getters.

- **Primary:** a directory `FileObserver` (`MOVED_TO|CLOSE_WRITE|CREATE`, filtered
  by filename), armed once per process under the existing `claimScheduler()` gate.
  Event-driven, so steady-state cost goes to ~zero.
- **Skip unchanged payloads** using the fingerprint that is already computed —
  turning 24 redundant reloads/min into 0.
- **Fail-closed on the *claim*, not the function:** if the observer cannot arm, or
  events stop arriving, fall back to the existing timer **and emit observable
  degraded-mode evidence**. A degraded process must never look fast.
- **Bounded resources:** exactly one observer, no extra threads at steady state,
  no additional timer when the fast path is healthy.

Why this is not a speed/cost tradeoff: the current design pays CPU continuously
*and* is slow. Event-driven is ~3 orders of magnitude faster **and** cheaper at
idle. Both numbers improve, which is the signal that the current coordinate
system is simply wrong.

**Explicitly rejected:** shortening the poll interval (the charter forbids buying
speed with busy polling, and it would worsen defect 2); per-getter fallback
layers (charter forbids; also unnecessary given the single accessor).

**Open questions for review — in §8.**

---

## 8. Decision packet for review

1. **Scope of "address"** — confirm the reframing in §0. This line optimises
   *coordinate propagation*. Do we also want `Geocoder` coverage (§6), or is that
   a separate feature? It is the difference between "fast" and "complete".
2. **Is the 5 s floor still policy?** Its stated justification is void (§3.4).
   With an event-driven trigger the floor becomes meaningless for the fast path,
   but the fallback timer still needs a sanctioned value.
3. **Definition of "全局最快" to be ratified** (Phase A2), proposed as:
   *p50/p95/max of (bytes committed to transport → `CURRENT.set()` returns),
   taken as the **maximum across all live hooked processes**, not the first.*
   Guard metrics that must not regress: reloads/min/process at steady state,
   main-thread occupancy, thread count, timer count.
4. **`feat/mock-location-v2` overlap** — both lines touch `MainHook`/`HookUtils`.
   Phase A touched neither. Per charter, behaviour changes wait for that merge
   plus a rebase, or written confirmation the diffs do not overlap.
5. **Two-file divergence (§3.6)** — file a separate bug, or absorb here?

---

## Reproduce

```bash
python3 scripts/refresh_latency_probe.py --observe-only 40          # passive census
python3 scripts/refresh_latency_probe.py --rounds 5                 # live interval
python3 scripts/refresh_latency_probe.py --rounds 6 --interval-sec 30 \
        --phase random --seed 7 --json-out out.json                 # unbiased
```

Requires exactly one attached device and root. Always restores the original
payload and verifies by md5.

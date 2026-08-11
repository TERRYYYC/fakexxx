---
feature_ids: [F-HOOK-CADENCE]
topics: [hook, fused-location, gms, xposed, runtime-discovery, measurement]
doc_kind: experiment-result
created: 2026-08-05
---

# Fused Runtime Discovery Pilot: Maps Blue Dot Follows the Spoof

## Summary

The GMS fused path was the mission's silent hole: `resolveFusedImpl()` knew only
two historical internal class names, the current GMS build renamed the
implementation again, and every fused hook died with "Cannot hook abstract
methods" — Google Maps showed the **real** location no matter what was
configured (proven in phase-b-results.md §R3-P1#1).

Per Sol's frozen design (project-research/2026-08-04-gms-fused-runtime-discovery),
the implementation now arms the public `LocationServices` factory, validates the
returned object against the public contract, resolves exact implementation
`Method`s via the pure `FusedClientMethodPlan`, and replaces results only
through public APIs. All internal candidate names and private-field fallbacks
(`mLocations`/`mResult`/`mComplete`/`mResultSet`/`mIsLocationAvailable`) are
deleted.

**Result: on current Maps 26.31 (impl class `bkmc`), the blue dot follows the
spoofed coordinate, and a real UI coordinate edit moved it Zhytomyr → Lviv
with 4 ms transport latency.**

## Device Evidence (moto g54 5G, Android 15, Maps 26.31, Play services 26.28.33)

### Discovery chain (logcat, Maps main process)

```
FakeGPS-Hook: event=fused_factory_armed overloads=2
FakeGPS-Hook: event=fused_client_discovered class=bkmc loader=54512416
FakeGPS-Hook: event=fused_surface_hooked surface=CURRENT_LOCATION_TASK owner=bkmc  (×3)
FakeGPS-Hook: event=fused_surface_hooked surface=LAST_LOCATION_TASK owner=bkmc     (×2)
FakeGPS-Hook: event=fused_surface_hooked surface=LISTENER_REGISTRATION owner=bkmc  (×6)
```

`bkmc` is exactly the application-obfuscated class Sol's dex analysis predicted —
discovered at runtime with zero name guessing. Resource bound: 2 factory hooks +
11 exact-Method hooks + reflection only on first discovery of the class; zero
polling, zero classpath scanning.

### Pilot-driven design correction (shape over names, even for markers)

First pilot run crashed discovery: `ClassNotFoundException:
com.google.android.gms.location.LocationCallback` — app-side R8 strips even the
public marker-class names. The planner was changed to identify
callback/listener parameter types by **shape** (declared `onLocationResult` /
`onLocationChanged` methods) extracted from the contract signatures, and
`LocationResult` is derived from the callback method's own parameter type. Two
new unit tests pin this (`registrationEntriesCarryShapeMatchedCallbackType`,
`shapelessRegistrationParametersAreNotPlanned`).

### Blue dot follows (screenshots)

| State | Maps blue dot | Evidence |
|---|---|---|
| Spoof = Zhytomyr, real = Kyiv | **Zhytomyr** (Seletska St / Ivana Honty St area) | screenshot |
| Real UI edit → Lviv (49.8397, 24.0297) | **Lviv** (Opera / Forum Lviv area) | screenshot |
| Publish → Maps accept | **4 ms** (observer path, background process) | `published fp=1928d4f0…` 00:13:52.867 → `transport accepted` .871 |

Before this change, the identical setup showed the blue dot pinned to the real
Kyiv location (phase-b-results.md §R3-P1#1 screenshots).

### Schema-4 coexistence

Mid-testing, master advanced to schema 4 (PR #10 System Mock integration) and
the device app was upgraded under us — the branch rejected the payload
(`transport rejected schema=4`). The branch was rebased onto current master
(clean, no conflicts); post-rebase: `transport accepted schema=4`, the full
fused chain re-established, and the blue dot re-verified on Zhytomyr.

## Test Evidence

- 457 unit tests, 0 failures (single variant after master's flavor removal;
  pre-rebase 765 = ~2× flavor duplication — comparable base), clean build
- 10 behavioral tests for `FusedClientMethodPlan` (name-independence, inherited
  impl ownership, abstract/unassignable rejection, dedup, overload exactness,
  pre-21 eager eligibility, surface mapping, shape matching ×2, shapeless
  rejection)
- Bytecode contract: no `mLocations`/`mIsLocationAvailable`/`mResult`/
  `mComplete`/`mResultSet`/`zzbp`/`FusedLocationProviderClientImpl` strings;
  `getFusedLocationProviderClient`/`forResult`/`create`/`extractResult` present
- R8 release build clean; `FusedClientMethodPlan` added to proguard keeps

## Not verified (with reasons)

- **30s/60s interval matrix + resource census** — blocked by live device
  contention: a parallel session is driving the same device via adb (bench app
  launches observed 00:31:07) and the Xposed module was de-scoped/disabled on
  the device mid-matrix (fresh Maps processes show no module injection).
  5s and 10s intervals were validated (4 ms real publish; probe rounds at 10s
  before the module was disabled).
- **PendingIntent probe** — `LocationResult.extractResult(Intent)` does not
  exist in Maps' bundled GMS (`findAndHookMethod` skip logged); the hook is
  installed wherever the method exists, but no on-device app exercised it.
- **CALLBACK_REGISTRATION surface on Maps** — Maps' contract exposed only
  LISTENER-shaped registration overloads to the planner (11 hooks installed,
  zero CALLBACK). The blue dot nonetheless follows, so its update path is
  covered by the hooked surfaces; the callback path remains unit-tested only.

## Environment restored

Profile restored to the Zhytomyr baseline via the real UI path
(`fp=sha256:a46f31b771af1555`); interval 10s at time of contention (restore to
5s pending device availability — flagged in the handoff).

[墨墨/kimi-k3🐾]

---

# R6 Follow-up: Delivery-Layer Fixes, Verified on the Bench Identity

Sol's R6 (exact HEAD `2f9665e`) proved against the exact Maps bytecode that the
first delivery implementation was silently inert on real Maps. All three
findings were fixed red-first and re-verified on device through the
`somewhere.bench` applicationId (official package untouched, per device lock).

## Fixes (per finding)

**R6 #1 — Task delivery planned zero hooks.**
Exact-Maps facts: the declared return type is the ABSTRACT task (`bkwo`), and
the real success listener is erased to `c(Object)`. Fixes:
- delivery hooks are planned from the ACTUAL returned object's runtime class
  (client-method after-hook), never the declared return type;
- the erasure-correct discriminator: `Object` params are SUCCESS (not Task);
  only non-Object Task-assignable params are completion/continuation;
  listener delivery methods must return `void` (excludes continuations);
- `FusedTaskTracker` (weak identity set) gates wrapping to instances handed
  out by hooked fused APIs — the runtime Task class is process-shared;
- success evidence is only emitted when an install actually happened.

**R6 #2 — replacement LocationResult could not be constructed.**
Exact-Maps facts: renamed static `b(Intent)`, public `(List)` CONSTRUCTOR, no
static `(List)->self` factory. Fixes: `FusedDeliveryPlan.buildResult` prefers
the static factory and falls back to the public `(List)` constructor; the
assignability direction was corrected (`resultClass.isAssignableFrom(return)`).

**R6 #3 — claim-then-fail was unrecoverable.**
`FusedHookRegistry.release(Method)`; every claim→install site releases on
failure, so the next factory call retries.

## Device verification (bench identity, Maps 26.31)

- Module load path: `Loading legacy module name.caiyao.fakegps.bench` into
  Maps; bench config (Lviv, fp=0411dc…) accepted; full chain
  `factory_armed → client_discovered(bkmc) → 11 surface hooks`.
- **Blue dot follows the bench config (Lviv)** — the R6-fixed delivery layer
  drives the exact app that defeated the previous two attempts.
- Honest absence logged: `fused_surface_missing method=LocationResult#listAccessor`
  (Maps' LocationResult ships no List-returning accessor; defense-in-depth
  surface correctly reports instead of faking success). The renamed
  `b(Intent)` (extractResult capability) resolved and installed — no
  `staticFactory` missing evidence.
- **Interval matrix (bench prefs)**: 30s → mean 0.205s, 60s → mean 0.210s
  (4 samples each, Maps + bench process); combined with earlier 5s/10s runs,
  propagation is interval-independent across the full 5/10/30/60 matrix.
- **Resource census** (Maps process): 105 threads total, exactly 1
  `FileObserver` thread, exactly 1 inotify instance — no growth from the
  hook layer; timer shares the main looper.
- Official Maps scope was temporarily unchecked for attribution and restored
  after; bench Maps scope removed after the matrix; official package never
  touched.

## Test evidence

476 unit tests, 0 failures — including R6 red-first cases: erased-`Object`
success listener planned, complete/continuation/non-void excluded, public
`(List)` constructor builder, subtype/supertype assignability direction,
claim-release-retry, task-tracker identity gating. R8 release clean.

[墨墨/kimi-k3🐾]

---

# R7 Follow-up: Install Transaction, True Identity, Delivery Evidence

Sol's R7 (exact HEAD `a66db30`) held the line on three structural points, all
fixed red-first:

**R7 #1 — the claim/try/release template leaked twice → unified primitive.**
Every Method installation now goes through
`FusedHookRegistry.claimAndInstall(Method, Installer)`: claim, install, keep
on success, release on failure. No site may open-code the transaction;
`fused_surface_hooked` evidence is emitted only when an install actually
happened (zero-install can no longer masquerade as success).

**R7 #2 — delivery-level evidence.**
New events close the "client hooked but delivery inert" gap:
`fused_delivery_planned task=<class> registrations=<n>` (emitted even at 0)
and `fused_listener_wrapped task=<class>` (first actual wrap).

**R7 #3 — true weak identity.**
`FusedTaskTracker` now keys on `IdentityWeakRef` (referent identity, hash
captured at insert, reference-queue expunge). Red tests: two equals-equal
non-identical objects do not share tracking; mutable-hashCode referents stay
findable.

## Device verification (bench identity, Maps 26.31, HEAD pre-doc)

```
fused_factory_armed overloads=2
fused_client_discovered class=bkmc loader=88047766
fused_surface_hooked surface=CURRENT_LOCATION_TASK owner=bkmc  (×3)
fused_surface_hooked surface=LAST_LOCATION_TASK owner=bkmc     (×2)
fused_surface_hooked surface=LISTENER_REGISTRATION owner=bkmc  (×6)
fused_delivery_planned task=bkww registrations=3     <-- R6#1 fix proven on the real runtime Task
```

- The planner found **3 success-listener registrations on the real runtime
  Task class `bkww`** (concrete — the declared return type `bkwo` is abstract).
  Erasure-correct planning works in production.
- Blue dot follows the bench config (Lviv) again on the R7 build.
- DIAG caller attribution (debug build) shows the fused listener surfaces
  delivering: `LSPHooker_.requestLocationUpdates <- bkwr.p` and
  `LSPHooker_.onLocationChanged <- bkgg.a`.
- **Interval matrix with durable provenance**: raw probe JSON committed at
  `raw/bench-30s.json` (mean 0.216s, 6 samples) and `raw/bench-60s.json`
  (mean 0.222s, 6 samples), two processes each.

## Honest coverage boundaries (README narrowed accordingly)

- `fused_listener_wrapped` did NOT fire in this session: Maps' observed
  location flow consumes listener registrations, not Task success listeners.
  The Task wrap path is armed, planner-verified against the real runtime
  class (3 registrations), and unit-tested — but no live consumer has been
  observed yet.
- LocationCallback and PendingIntent (`b(Intent)` extractResult capability):
  implemented and installed where present; no live consumer on Maps. README
  no longer claims them as covered surfaces.

## Incident note (process lesson)

While restoring scope, a mis-tap flipped the official module's master switch
OFF for ~40 seconds; it was immediately re-enabled and verified. Root cause:
blind tap choreography on a fatigued UI path. Mitigation: screenshot-verify
every mutating tap (adopted for the remainder of the session).

[墨墨/kimi-k3🐾]

---

# R8 Follow-up: Terminal-State Install Transactions

Sol's R8 (exact HEAD `b64bed9`) found the transaction model itself was still
optimistic. All three findings fixed red-first:

**R8 #1 — "installing" must never read as "installed".**
`FusedHookRegistry` is now backed by a per-Method `CompletableFuture`: the
first caller installs and completes the future; concurrent callers BLOCK on
it (no fake success, no real-location window), report `ALREADY_INSTALLED` on
peer success, and retry as the new installer after a peer failure. Red tests
pin both interleavings with latches.

**R8 #2 — delivery evidence reports terminal counts, not plan counts.**
`fused_delivery_summary task=<class> planned=N installed=I already=A failed=F
[reason=…]` replaces the plan-count event; while any install is failing, the
task class stays eligible for re-planning and re-reporting. Failure reasons
are retained (bounded simple names).

**R8 #3 — tri-state everywhere; completed classes stop re-planning.**
`INSTALLED / ALREADY_INSTALLED / FAILED` — only `INSTALLED` emits
`surface_hooked`. Runtime classes whose plan fully installed are recorded in
`FUSED_COMPLETED_CLASSES` and never re-planned.

## Device verification (bench identity, Maps 26.31)

```
fused_delivery_summary task=bkww planned=3 installed=3 already=0 failed=0
```

Planner count and terminal install count agree on the real runtime Task
class; the 11 surface hooks fired exactly once each (no re-discovery spam).
Official/bench scopes restored after; runtime load lines confirm only the
official module in Maps at the end.

483 unit tests, 0 failures; R8 release clean.

[墨墨/kimi-k3🐾]

---

# R9 Follow-up: Forced Result Consumption + Interruption-Proof Waiting

Sol's R9 (exact HEAD `28dd6d4`) found two remaining leak paths, fixed red-first:

**R9 #1 — every terminal result must be consumed, structurally.**
Seven sites still discarded the tri-state (4 eager value-object hooks, callback
result/availability, GMS listener delivery — the last one proven live on Maps).
All installs now flow through `installObserved(label, method, installer)`:
failures emit bounded `surface_missing` evidence immediately, and the tri-state
is returned for callers that distinguish fresh vs repeat. A source-level
contract test (`everyRegistryResultIsConsumedThroughObservedInstall`) pins the
structure: exactly two direct registry call sites may exist (the wrapper and
the Task delivery aggregator); it already caught 3 leftover surface branches
during development.

**R9 #2 — interrupted waiters must not escape before the terminal state.**
The wait loop now swallows `InterruptedException` and keeps waiting (restoring
the interrupt flag on exit), because an early return re-opens the
real-location window — the app could call a fused method whose hook was still
in flight. Red tests pin both interleavings: mid-wait interrupt and
pre-interrupted waiter; both must block until the peer completes, then return
`ALREADY_INSTALLED` with the flag restored.

## Device verification (bench identity, Maps 26.31)

Full chain re-verified on the R9 build: factory armed → `bkmc` discovered →
11 surface hooks (once each) → `fused_delivery_summary task=bkww planned=3
installed=3 already=0 failed=0`. Official/bench scopes restored afterward and
confirmed by runtime load lines (official module only).

486 unit tests, 0 failures; R8 release clean.

[墨墨/kimi-k3🐾]

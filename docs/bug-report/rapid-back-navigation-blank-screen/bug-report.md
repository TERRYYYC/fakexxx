---
feature_ids:
  - runtime-hook-verification
topics:
  - android
  - compose
  - navigation
doc_kind: bug-report
created: 2026-08-02
---

# Rapid back navigation can empty the Compose destination stack

## 1. Reporter

Codex Sol found this during the reviewed-release moto g54 acceptance run for runtime hook
verification at `a335c67a184831caede72403786f4aaa413504dd`.

## 2. Reproduction and evidence

1. Open FakeGPS and navigate from Map to Verify.
2. Wait for the hook probe to deliver.
3. Trigger Android back, then immediately tap the top-left coordinate while Verify's exit
   transition is still present.
4. The second gesture reaches Verify's still-clickable back button and invokes another bare
   `NavHostController.popBackStack()`.

Expected: Map remains the root destination and its toolbar is visible.

Actual: `ComposeActivity` remains resumed, but `android:id/content` has no child and the device
shows a persistent white screen. A single back action followed by a settled transition returns to
Map correctly, which isolates the failure to overlapping navigation actions rather than Map or
probe rendering.

Runtime preflight proved the device was running the reviewed APK: installed APK SHA-256
`7e53e13c5a0f7af56a132a2192d2aa455ee92216f7ba455e6aace21b95f0da00`, process PID 9546 started at
01:28:41, worktree and target HEAD both `a335c67`, and the process had 65 log lines. The blank-screen
screenshot was captured outside the repository under `/tmp/fakegps-blank.UutCnr/`.

## 3. Root cause analysis

`AppNavGraph` passes unguarded `popBackStack()` callbacks to destination top bars. Navigation
Compose keeps the outgoing destination composed during its transition, so its back button can
receive another click after the entry has already left `RESUMED`. The second pop is queued against
the changing stack and can remove the Map root, leaving the NavHost without a destination.

The working single-action case differs only in lifecycle state: the destination is `RESUMED` for
the first action and `STARTED` while it exits. The fix therefore has to serialize entry-owned
navigation across that lifecycle transition, not add delays or rebuild the Activity. It must also
retain one legitimate first click received while a new entry is still becoming `RESUMED`.

## 4. Fix

Add one lifecycle-state guard per back-stack entry and use it for every navigation callback in
`AppNavGraph`. A `RESUMED` destination acquires an in-flight token before it navigates, so a second
callback is rejected even if both callbacks observe `RESUMED`. The token resets only if that entry
later returns to `RESUMED`. A first click received while a new destination is entering in `STARTED`
is queued until `RESUMED`; an outgoing entry already owns the token and rejects the duplicate.
At most one action can be queued. This also covers the sibling Settings, Collection and Editor
routes that use the same top-left back pattern.

Rejected alternatives:

- Debouncing with an arbitrary time window: device animation duration is not a correctness source.
- Restarting the Activity after a blank screen: treats the symptom and loses navigation state.
- Fixing Verify only: leaves identical navigation callbacks vulnerable elsewhere.

### Navigation guard state model

`NavBackStackEntry` owns exactly one guard while its composable is active. The lifecycle observer
is the only lifecycle driver; `pendingAction` and `navigationInFlight` are transient state and are
never persisted or shared between entries.

| State | Representation | Event | Next state / effect |
|---|---|---|---|
| Ready | active, no pending, not in flight | submit at `RESUMED` | Navigating; acquire token before executing |
| Ready | active, no pending, not in flight | submit at `STARTED` | EnterQueued; retain exactly one action |
| EnterQueued | pending, not in flight | lifecycle reaches `RESUMED` | Navigating; clear pending, acquire token, execute |
| EnterQueued | pending, not in flight | direct submit observes `RESUMED` first | Navigating; execute the older pending action and reject the new action |
| EnterQueued | pending, not in flight | lifecycle drops below `STARTED` | Ready; discard stale action |
| Navigating | no pending, in flight | any submit | unchanged; reject duplicate |
| Navigating | no pending, in flight | entry later returns to `RESUMED` | Ready; release token |
| Navigating | no pending, in flight | action throws before transition | Ready; release token and rethrow the same failure |
| Any active state | active | dispose | Disposed; clear all transient state |
| Disposed | inactive | any event | unchanged; no action |

Invariants:

- **NAV-INV-1:** pending and in-flight are mutually exclusive; at most one action is retained.
- **NAV-INV-2:** every action acquires the in-flight token before invoking `NavController`.
- **NAV-INV-3:** an action failure cannot retain ownership; the token is released and the same
  exception propagates.
- **NAV-INV-4:** only a later `RESUMED` event, an execution failure, or disposal releases an
  in-flight token; duplicate callbacks cannot release it.
- **NAV-INV-5:** leaving before an entering action runs clears it, so a stale click cannot revive.

The adversarial test matrix covers consecutive `RESUMED` callbacks, outgoing `STARTED` duplicates,
incoming queue/drain and queue/discard, retained-entry return, direct and deferred action failures,
and compound-action rejection. Real Navigation Compose/OEM ordering remains an exact-build device
acceptance boundary.

## 5. Verification / diagnosis capsule

| Field | Evidence / strategy |
|---|---|
| Phenomenon | Persistent white Activity after overlapping Verify back actions |
| Evidence | Stable device reproduction, empty view hierarchy, screenshot, exact installed APK hash |
| Root cause | A leaving back-stack entry can invoke a second unguarded navigation callback |
| Diagnostic strategy | Compare single settled back with overlapping back + top-left tap; trace NavGraph callbacks |
| Timeout strategy | If the lifecycle guard does not reproduce Red→Green in one attempt, return to runtime tracing rather than layering another fix |
| Warning strategy | Any failure outside navigation or any third attempted repair means the hypothesis is wrong |
| User-visible correction | Rapid taps no longer strand the stable app on a white screen |
| Acceptance | Guard JVM state-transition tests; full JVM gate; after cross-individual review repeat both the original double event and an entry-animation first back on moto g54 |

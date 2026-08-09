---
feature_ids: ["issue-16"]
topics: [android, map, recenter, location, profiles]
doc_kind: implementation-plan
created: 2026-08-05
---

# Map Recenter Effective Location Implementation Plan

**Feature:** GitHub Issue #16 — map recenter returns to a stale fixed location
**Goal:** Make the map button center on the location currently effective for the product, never an unbounded last-known cache entry.
**Acceptance Criteria:** Active Hook and running System Mock center on their effective coordinate; passthrough/cold-start requests one current system fix; malformed or transition state fails visibly; recenter does not select a new profile point; cold start, delivery-mode switching, and process/cache restoration are covered; `.bench` is verified on device without touching release data.
**Architecture cell:** N/A — this repository has no ownership-cell map.
**Map delta:** none
**Map delta why:** This is a projection from existing config/provider owners into the existing map UI; it adds no new ownership boundary.
**Architecture:** A pure `MapRecenterTargetResolver` projects the exact published payload plus the existing System Mock runtime state into one of three terminal intents: effective coordinate, current device fix, or explicit unavailable. `MapViewModel` reads those owners at click time; `MapScreen` only performs the resulting camera/current-location action.
**Tech Stack:** Kotlin, Compose, Android `LocationManager`, AndroidX `LocationManagerCompat`, JUnit 4
**前端验证:** Yes — reviewer must exercise the Android Compose map on the `.bench` package and verify the camera destination and selection behavior.

---

## Finish line

Pressing the map recenter button has one user-facing meaning: **归位到当前有效位置**.

- A running System Mock uses the controller's running config.
- An applying Hook payload with a complete valid location uses the exact published fields the hook consumes.
- Hook passthrough, mode-off, out-of-hours, or never-published state requests one current system fix.
- Unreadable, malformed, incompatible, incomplete, starting/stopping, or failed state reports that the effective position cannot be determined instead of guessing.
- Recenter changes only the camera; it does not create or replace the selected map point.

Not building: a new default coordinate, persisted camera state, a second active-profile store, release-package migration, or background location tracking.

## Stateful object census

No new stateful object is introduced. The resolver is a pure projection.

| Existing object | Lifecycle owner | Relevant events | Recenter projection |
|---|---|---|---|
| Published payload | `ConfigPrefsSync` | absent / publish / malformed or unreadable read | Hook coordinate, current-device passthrough, or unavailable |
| System Mock runtime state | `MockProviderSessionController` via `MockProviderStatusStore` | start / running / stop / failure | running config wins; transition/failure is unavailable |
| Map selection | `MapViewModel.tappedPoint` | map tap / clear | recenter must not mutate it |

Invariants:

- **INV-1:** No recenter path calls `getLastKnownLocation`.
- **INV-2:** Published bytes, not Room ordering or map marker order, determine an applying Hook coordinate.
- **INV-3:** `MockProviderState.Running.config` determines a running System Mock coordinate.
- **INV-4:** Unknown/last-known-good runtime state is never collapsed into real-location passthrough.
- **INV-5:** Recenter never invokes `onMapTap` or otherwise changes `tappedPoint`.
- **INV-6:** Device verification installs and mutates only `name.caiyao.fakegps.bench`.

Adversarial scenarios: process restoration while System Mock restarts; malformed payload while Hook keeps last-known-good; mode switch during Starting/Stopping; active profile changing after the map was composed; location permission denied; provider unable to return a current fix.

### Task 1: Lock the target projection with RED tests

**Files:**

- Create: `app/src/test/java/name/caiyao/fakegps/ui/screen/map/MapRecenterTargetResolverTest.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/ui/screen/map/MapRecenterUiContractTest.java`

1. Add cases for cold start, applying Hook, Hook passthrough, time window, running System Mock, transition/failure, malformed/incomplete payload, and selection independence.
2. Run `./gradlew testDebugUnitTest --tests '*MapRecenter*'`.
3. Confirm RED because the resolver/current-location path does not exist and the screen still calls `getLastKnownLocation`.

### Task 2: Implement the terminal resolver and event path

**Files:**

- Create: `app/src/main/java/name/caiyao/fakegps/ui/screen/map/MapRecenterTargetResolver.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/map/MapViewModel.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/map/MapScreen.kt`

1. Implement the pure three-outcome resolver without persistence or fallback coordinates.
2. Read the published payload and mock runtime state at click time in `MapViewModel`.
3. Replace the cached lookup with `LocationManagerCompat.getCurrentLocation` for real passthrough.
4. Apply the coordinate only to the map camera and report the source through the snackbar.
5. Run the same targeted tests to GREEN.

### Task 3: Refactor and run risk-matched gates

1. Run `./gradlew testDebugUnitTest --tests '*MapRecenter*'`.
2. Run `./gradlew testDebugUnitTest`.
3. Run `./gradlew lintDebug assembleDebug` using the repository-selected JDK.
4. Inspect `git diff --check`, the exact diff, APK application id, and worktree cleanliness.

### Task 4: Isolated device verification and handoff

1. Acquire the shared moto g54 device lease before any ADB command.
2. Install only the exact `.bench` APK with `adb install -r`; never uninstall or clear data.
3. Verify cold start, applying Hook coordinate, running System Mock coordinate, switch back to Hook, process restart/cache restoration, permission/fix failure, and that recenter does not prime Add Profile.
4. Restore mock app-op/provider state and release the lease.
5. Run `quality-gate`, commit/push the exact head, then route to `@opus5` for independent review and device verification. Merge remains reserved for co-creator manual confirmation.

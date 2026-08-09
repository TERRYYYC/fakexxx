---
feature_ids: [F001]
topics: [android, location, mock-provider, test-app, coexistence]
doc_kind: plan
created: 2026-08-03
---

# Mock Location v2 Implementation Plan

> **Historical Lab plan.** PR #8 proved the System Mock mechanism but did not complete the main-product requirement. The shippable integration is defined by `2026-08-03-mock-location-main-integration.md`; the standalone `mockProvider` build type described below has since been removed.

**Feature:** F001 — `insight/docs/features/F001-issue-gms-fused-location-gap.md`
**Goal:** Ship a rebuildable FakeGPS-derived Mock Provider lab that can coexist with the installed original apps and prove whether Android's system GPS test provider moves Google Maps.
**Acceptance Criteria:** (1) installed originals are backed up before testing; (2) the lab has a distinct package, label, data directory, provider authority, and mock-app identity; (3) original debug/release variants remain behaviorally and build compatible; (4) the lab replaces `LocationManager.GPS_PROVIDER`, emits complete fresh mock locations at a fixed cadence, and exposes start/stop/error state; (5) stop, failure, and a documented restore procedure return the device to the original mock app; (6) JVM tests, APK identity inspection, coexistence installation, and a moto g54 Maps observation all have exact evidence.
**Architecture cell:** Android application / no ownership-cell registry exists in this repository
**Map delta:** none
**Map delta why:** This adds an isolated build variant and service inside the existing Android application boundary; no shared architecture ownership changes.
**Architecture:** Add a `mockProvider` build type to the existing `:app`, with `applicationIdSuffix ".mockprovider"` and a variant-only launcher/service. The lab reuses the original source tree and build, but removes Xposed module metadata in its manifest overlay and owns only the Android system GPS test-provider path. A pure session controller owns lifecycle transitions; an Android gateway owns `LocationManager` calls.
**Tech Stack:** Android 15 / SDK 35, Kotlin, Compose, `LocationManager`, foreground service, JUnit 4, Gradle build-type source sets
**前端验证:** Yes — reviewer must exercise the lab UI on the moto g54 and capture package/app-op/status evidence.

---

## Finish line

The device can keep all four packages installed without shared state or signature replacement:

- Play Store reference: `com.hopefactory2021.fakegpslocation` (`Fake GPS Location`)
- Original release product: `name.caiyao.fakegps` (`千网游`)
- Upstream debug bench: `name.caiyao.fakegps.bench` (`千网游·测试`)
- Lab variant: `name.caiyao.fakegps.mockprovider` (`FakeGPS Mock Provider Lab`)

Selecting the lab in Developer options and pressing Start publishes a complete mock location through the system `gps` provider once per second. Pressing Stop removes the test provider. The acceptance script restores `com.hopefactory2021.fakegpslocation` as the selected mock app and proves the original still launches.

### Explicitly not building

- No GMS-process/Xposed hook work.
- No mock-marker hiding; `Location.isMock()` is expected to be `true`.
- No decompilation, repackaging, or resigning of the Play Store reference APK.
- No copy or mutation of either original app's data, profiles, preferences, or databases.
- No new Google Play Services dependency in this phase.
- No custom provider name such as `MockLocationProvider`; Maps does not request it. The lab replaces the standard `gps` provider, matching the inspected reference APK.

## Evidence already established

- Backup manifest: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/README.md`.
- The installed reference APK contains calls to `addTestProvider("gps", ...)`, `setTestProviderEnabled("gps", true)`, repeated `setTestProviderLocation("gps", ...)`, and cleanup via `removeTestProvider("gps")`.
- The same reference APK also contains the separate Google Play Services `setMockMode` / `setMockLocation` path, selected by an internal provider-choice preference. The system-provider experiment must therefore be evaluated independently rather than assuming the reference app is system-only.
- Initial development baseline `c34bf666983da3634dfba5f2b4c3ce0147cde019`: `testDebugUnitTest` and `assembleDebug` pass.
- Review baseline `5dab712ff4119b421076b5034c3fea859ad2b29a`: upstream debug now installs independently as `.bench`; release remains `name.caiyao.fakegps`. The feature branch merges this baseline before review.

## Terminal contracts

```kotlin
data class MockLocationConfig(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 3f,
)

sealed interface MockProviderState {
    data object Idle : MockProviderState
    data class Starting(val config: MockLocationConfig) : MockProviderState
    data class Running(val config: MockLocationConfig, val emittedCount: Long) : MockProviderState
    data object Stopping : MockProviderState
    data class Failed(val message: String) : MockProviderState
}

interface MockProviderGateway {
    fun replaceGpsProvider()
    fun publish(config: MockLocationConfig)
    fun removeGpsProvider()
}
```

`AndroidMockProviderGateway.publish` creates `Location(LocationManager.GPS_PROVIDER)` and always sets latitude, longitude, accuracy, wall-clock time, and `elapsedRealtimeNanos`. API 31+ uses `ProviderProperties`; API 24–30 uses the legacy ten-argument overload.

## Stateful-object census

### Object 1 — Mock provider session

- Unique lifecycle owner: `MockProviderService` through `MockProviderSessionController`.
- Persistence: none. The service returns `START_NOT_STICKY`; process death must not resurrect location spoofing.
- Bypass rule: UI and notification actions may only send `START` / `STOP`; they never call `LocationManager` directly.

| Current state | Event | Next state | Required side effect |
|---|---|---|---|
| Idle / Failed | Start(valid config) | Starting → Running | remove stale provider, add/enable `gps`, publish immediately |
| Running | Tick | Running(count + 1) | publish a fresh complete location |
| Running | Start(new config) | Starting → Running | replace provider and publish new config once |
| Any | Stop | Stopping → Idle | cancel cadence, remove provider (idempotent) |
| Starting / Running | Gateway failure | Failed | cancel cadence and best-effort remove provider |
| Any | Null restart intent / process recreation | Idle + service stops | no provider registration, no sticky resurrection |

### Object 2 — Android selected mock-app authority

- Unique lifecycle owner: Android Developer options / mock-location app-op, not app storage.
- Persistence: external OS state; the lab only observes failure as `SecurityException`.
- Bypass rule: code must not silently grant itself app-op or modify the reference app. Device acceptance may switch the selected package with an explicit operator-visible command and must restore it in the same test run.

### Object 3 — Coordinate configuration

- Unique lifecycle owner: lab activity input for the current service session.
- Persistence: none in phase 1. Do not create a second database or preferences copy; session state is not user data.
- Invalid input never starts the service. Latitude must be `[-90, 90]`, longitude `[-180, 180]`, accuracy finite and positive.

## Invariants

- **INV-1 Identity isolation:** lab applicationId, label, data directory, manifest provider authority, and signature permission names do not equal the original package's values. Verified by merged-manifest/APK inspection and simultaneous `pm list packages` output.
- **INV-2 Original compatibility:** `assembleDebug`, `assembleRelease`, release package `name.caiyao.fakegps`, debug bench package `name.caiyao.fakegps.bench`, Xposed metadata, and variant-owned provider authorities remain intact. Verified by builds plus `aapt dump badging/xmltree`.
- **INV-3 No second Xposed module:** the lab APK contains no `xposedmodule=true` metadata. Verified by `aapt dump xmltree`.
- **INV-4 Complete sample:** every published location has provider `gps`, valid coordinates, positive finite accuracy, non-zero `time`, and non-zero `elapsedRealtimeNanos`. Verified by controller tests plus device observation.
- **INV-5 Idempotent cleanup:** Stop and start-failure paths call provider removal safely even if no provider exists. Verified by fake-gateway call sequence tests.
- **INV-6 No crash resurrection:** null restart intent never restarts publication. Verified by service contract inspection and device process-stop test.
- **INV-7 Explicit marker:** observed locations remain mock-marked. Verified on device with `Location.isMock()` evidence.
- **INV-8 Restore original:** after acceptance, `android:mock_location` is allowed for `com.hopefactory2021.fakegpslocation`, the lab service/process is stopped, and the original app launches. Verified by app-op, process, and activity evidence.

## Adversarial scenarios

- Start twice rapidly with different coordinates: one cadence survives and only the second config is emitted.
- Stop twice or stop before start: no crash, final state Idle.
- Provider registration succeeds but first publish fails: cleanup occurs and state is Failed.
- Service process is force-stopped: no automatic restart; next explicit start performs remove-before-add recovery.
- Lab lacks mock app-op: surface a permission error; do not loop or mutate another package.
- Original and lab are both installed: neither install upgrades/replaces the other, and their provider authorities do not collide.
- Acceptance is interrupted after switching mock app: the documented restore command is independently runnable before any further test.

## Task 1 — Lock build identity before behavior

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/mockProvider/AndroidManifest.xml`
- Create: `app/src/mockProvider/res/values/strings.xml`
- Create: `scripts/test_mock_provider_variant.py`
- Test: `scripts/test_mock_provider_variant.py`

1. Write a failing structural test that requires the lab applicationId suffix, unique label, dynamic `${applicationId}` provider authority/permission, and Xposed metadata removal.
2. Run `python3 scripts/test_mock_provider_variant.py`; expect failure before the variant exists.
3. Add the `mockProvider` build type and manifest/resource overlay. Compile the debug acceptance classes required by common JVM tests, but keep them dormant: the lab removes all Xposed metadata, does not merge the debug acceptance manifest, and launches only `MockProviderActivity`.
4. Run the structural test, `assembleDebug`, and `assembleMockProvider`; expect all green.
5. Inspect both APKs with `aapt`; assert INV-1, INV-2, and INV-3.
6. Commit build identity separately.

## Task 2 — Implement the lifecycle core with TDD

**Files:**
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockLocationConfig.kt`
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderGateway.kt`
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderSessionController.kt`
- Test: `app/src/testMockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderSessionControllerTest.kt`

1. Write failing tests for config validation, immediate first publish, repeated start replacement, tick cadence, idempotent stop, and cleanup after failure.
2. Run `./gradlew testMockProviderUnitTest`; confirm failures are behavioral, not compilation typos.
3. Implement the pure controller and smallest gateway contract.
4. Rerun the focused tests, then all original and lab JVM tests.
5. Commit the lifecycle core.

## Task 3 — Bind the controller to Android safely

**Files:**
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/AndroidMockProviderGateway.kt`
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderService.kt`
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderServiceContract.kt`
- Test: `app/src/testMockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderServiceContractTest.kt`

1. Write failing pure tests for command decoding, `START_NOT_STICKY`, null-intent stop, and provider-property selection by API family.
2. Implement API 31+ and API 24–30 registration, complete location mapping, one-second handler cadence, foreground notification, stop action, and best-effort cleanup.
3. Run focused and full tests plus `assembleMockProvider`.
4. Commit Android binding.

## Task 4 — Add a variant-only control UI

**Files:**
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderActivity.kt`
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderScreen.kt`
- Create: `app/src/mockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderUiContract.kt`
- Test: `app/src/testMockProvider/java/name/caiyao/fakegps/mockprovider/MockProviderUiContractTest.kt`

1. Write a failing UI-contract test for unique title, coordinate validation, explicit permission guidance, Start/Stop actions, and visible lifecycle/error state.
2. Implement the dedicated launcher and screen without exposing it in original variants.
3. Build and inspect the lab APK.
4. Commit UI wiring.

## Task 5 — Device acceptance and restoration

**Files:**
- Create: `scripts/mock_provider_acceptance.sh`
- Create: `docs/acceptance/mock-location-v2-evidence.md`

1. Write a shell harness with a mandatory restore trap that records the pre-test mock app and restores `com.hopefactory2021.fakegpslocation` on every exit path.
2. Install the lab with `adb install` (never `-r` against the original package); prove both original package IDs remain installed.
3. Select the lab as mock app, start a coordinate, and capture timestamp/PID/package/app-op/service/provider evidence without reading app data.
4. Observe Google Maps blue-dot response and record `Location.isMock()` evidence.
5. Stop the lab, force-stop it, restore the reference app, launch the reference app, and verify INV-8.
6. Commit evidence only after exact HEAD and APK SHA are known.

## Task 6 — Quality and independent review handoff

1. Run original + lab unit tests, both APK builds, structural identity test, and device acceptance.
2. Run quality-gate against every AC and invariant.
3. Push `feat/mock-location-v2` with exact HEAD and Evidence Manifest.
4. Request Kimi exact-HEAD independent code review and device retest. Kimi is not the author.
5. After Kimi passes, hand to Opus for Feature Doc Truth and merge gate only; Opus does not contribute implementation code.

## Source provenance

- Android `LocationManager` API: https://developer.android.com/reference/android/location/LocationManager
- Android Developer options / Select mock location app: https://developer.android.com/studio/debug/dev-options
- Installed reference APK SHA-256 and package metadata: backup manifest cited above.
- Reference behavior evidence: local APK manifest and smali inspection via Android SDK `aapt` / `apkanalyzer`; no source code was copied.

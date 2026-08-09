---
feature_ids:
  - mock-location-v2
topics:
  - android
  - mock-location
  - foreground-service
doc_kind: bug-report
created: 2026-08-03
---

# Mock Provider harness cannot start the foreground service from ADB

## Report

- **Reporter:** Codex Sol, during moto g54 device acceptance.
- **Environment:** Android 15 device `ZY22JHW9M4`; lab package
  `name.caiyao.fakegps.mockprovider`; source HEAD `261073b`.
- **Expected:** the fail-safe harness starts the lab session, records evidence,
  then restores `com.hopefactory2021.fakegpslocation` as the selected mock app.
- **Actual:** Android rejects the service start before provider registration with
  `ForegroundServiceStartNotAllowedException: startForegroundService() not
  allowed due to mAllowStartForeground false`.

## Reproduction and evidence

1. Install the lab APK and pre-grant location/notification permissions.
2. Select the lab package for `android:mock_location`.
3. From `adb shell run-as`, call `am start-foreground-service --user 0`.
4. Observe the framework exception above. The EXIT trap still restores
   `lab=deny reference=allow`.

Two earlier variants ruled out adjacent causes: a direct shell call was blocked
by the intentionally non-exported service, and a `run-as` call without an
explicit user was rejected as a cross-user start. Adding `--user 0` exposed the
remaining Android 12+ background-start restriction.

## Diagnosis capsule

| Field | Finding |
|---|---|
| Symptom | Device acceptance cannot enter the active phase. |
| Evidence | Exact framework exception plus restored app-op state after every run. |
| Root cause | `am` is launched from a background app UID; changing UID/user does not make that UID foreground. |
| Diagnostic strategy | Compare the rejected command path with the real launcher flow and inspect the device UI hierarchy. |
| Timeout strategy | Stop after confirming one foreground Activity hypothesis; do not weaken component export rules. |
| Warning signal | Any proposed fix that exports the service or bypasses the app's normal UI lifecycle is rejected. |
| User-visible correction | None in product behavior; the test harness drives the same Start/Stop controls a person uses. |
| Acceptance | Structural regression test, successful active device phase, and automatic restoration of the original mock app. |

## Fix design

Launch the exported lab Activity, dismiss an insecure keyguard if needed, find
the visible `Start`/`Stop` controls from the UI hierarchy, and tap them. The app
then starts/stops its own non-exported service while foreground. This preserves
the production security boundary and exercises the user-visible control path.

## Verification

- The new structural test must fail while the harness still invokes
  `start-foreground-service` through `run-as`.
- After the fix, all structural/JVM/build tests must pass.
- On the device, the lab PID/service/provider must become active, Maps must be
  launched for observation, and the EXIT trap must restore the reference app.

The first device run after the fix confirmed the core hypothesis: Android
reported the lab UID as `TOP`, the service as foreground, and `gps provider
[mock]` at `40.712800,-74.006000`. The run then exposed a separate harness bug:
under `set -o pipefail`, `head -100` closed a verbose `dumpsys location`
pipeline early, so the upstream process exited on SIGPIPE before the Maps step.
Replacing early-closing truncation with a full-reading filter preserves the
evidence cap without turning successful collection into a failure. The restore
trap still completed with status 0.

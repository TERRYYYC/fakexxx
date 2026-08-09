---
feature_ids: ["issue-16"]
topics: [android, map, recenter, location-cache]
doc_kind: bug-report
created: 2026-08-05
---

# Map recenter uses a stale last-known location

## Bug diagnosis capsule

| Field | Finding |
|---|---|
| **1. Symptom** | The map's location/recenter button repeatedly returns to one old point instead of the active Hook/System Mock coordinate or the real current position. The reporter's private precise address is intentionally not recorded. |
| **2. Evidence** | `MapScreen` routes the button directly to `locateMe`. That function calls `LocationManager.getLastKnownLocation(GPS_PROVIDER)` and then the network cache, with no age bound and no read of the effective published payload. Android documents this API as cache-only and potentially quite old. |
| **3. Root cause** | The UI chose the wrong coordinate source: an unbounded system last-known cache is treated as “current”, while the product's actual location owners (`ConfigPrefsSync` for Hook and `MockProviderSessionController` for System Mock) are bypassed. The same function also mutates `tappedPoint`, conflating camera recenter with profile selection. |
| **4. Diagnostic strategy** | Trace UI event → ViewModel/state owner → coordinate source; compare with Settings/System Mock paths that consume published bytes; pin the projection in pure tests; device-check that the stale point matches cached provider behavior without recording the coordinate. |
| **5. Timeout strategy** | If the pure projection cannot express a runtime state within 30 minutes, stop and inspect the existing Hook/System Mock lifecycle rather than add fallback sources. If device reproduction is blocked, finish static/TDD work and coordinate the shared device lease. |
| **6. Warning signals** | Any hard-coded/default coordinate in the button path, any fallback to DB marker order, any `getLastKnownLocation`, any new persisted “current position”, or three fallback layers in one file means the coordinate system is wrong. |
| **7. User-visible correction** | The button is truthfully labeled “归位到当前有效位置”, centers on the active product coordinate when one exists, otherwise requests a current device fix, and never silently selects a new profile point. |
| **8. Acceptance** | RED→GREEN resolver/UI contract; full JVM + lint/build gates; `.bench` cold-start, Hook/System Mock switch, process restoration, and selection-independence journey; independent Opus5 review; co-creator final hand-feel before merge. |

## Reproduction

1. Leave an old GPS/network last-known cache on the device.
2. Publish or switch to a different effective profile coordinate.
3. Open the main map and press the location button.
4. Actual before the fix: the camera returns to the cached old point.
5. Expected: the camera returns to the currently effective product location, or obtains a current real fix when location spoofing is intentionally passthrough.

## Fix direction

Use a single pure projection over the existing truth owners; do not replace the stale point with another default. Current-device passthrough must use the bounded-current AndroidX API, which may use a very recent sample but does not return minutes-old cache entries.

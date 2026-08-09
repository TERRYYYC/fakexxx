---
feature_ids: []
topics:
  - android
  - xposed
  - google-play-services-location
doc_kind: research_prompt
created: 2026-08-04
---

# Research Brief: Stable runtime discovery for GMS fused-location hook targets

## 1. Problem Frame

**Question:** How can an Android Xposed module intercept current and future concrete implementations behind Google Play services' public `FusedLocationProviderClient` API without maintaining a list of obfuscated/internal class names?

The local module currently hooks two historical implementation names. Current Google Maps loads neither; it falls back to the abstract public class, whose abstract methods cannot be hooked. We need a bounded, observable, fail-closed discovery mechanism that preserves one authoritative spoofed `Snapshot`.

**Non-goals:**

- Geocoder/address-result spoofing, GNSS/NMEA coverage, or third-party SDK expansion.
- Adding another hardcoded GMS implementation class name.
- Busy polling, broad hook-every-method scanning, or changing Google Play services files.

**Why now:** The address-hook cadence branch reduced accepted-config propagation from a 14.5 s mean to sub-second, but real-device E2E proved Google Maps still receives real fused locations because its concrete client implementation is not resolved.

## 2. Current Hypotheses

1. The stable seam is a public factory/constructor/instance boundary, not an internal class name.
2. A concrete instance can be discovered from `LocationServices.getFusedLocationProviderClient(...)` or a public client constructor/factory result, then its runtime class can be hooked once per classloader.
3. Hooking public result/value objects and listener callbacks remains necessary but is insufficient if the request/Task path never reaches a concrete hook.
4. Discovery must be lazy and capability-based: validate method signatures and assignability before installing hooks, deduplicate by concrete class, and retain existing AOSP hooks if discovery fails.

**Evidence gaps:**

- Which public API boundary actually exists in recent play-services-location releases and current Maps runtime.
- Whether factory methods are static, final, inlined, or delegated in ways that affect Xposed interception.
- Whether runtime concrete methods are declared on the subclass or inherited from an intermediate implementation.
- Whether callbacks, Tasks, and PendingIntent overloads require different interception seams.

## 3. Disconfirm First

Before supporting the hypotheses, look for:

1. Releases where `LocationServices` returns an object without crossing a hookable Java factory method.
2. Concrete implementations whose target methods are inherited or dynamically proxied, making `hookAllMethods(runtimeClass, name)` insufficient.
3. R8/inlining, split classloaders, or binder/proxy boundaries that defeat runtime-instance discovery.
4. A more stable public seam, such as `Task<Location>` completion, `LocationResult`, callback dispatch, or lower-level AOSP location delivery.

## 4. Source Mix Quota

Prioritize primary sources:

- Official Google Play services location API reference and release notes.
- Published `play-services-location` AAR/JAR bytecode from multiple recent versions.
- Android/Xposed official API behavior for method and constructor hooks.
- Real-device class/method inventory captured from the target Maps process.

Community posts may be used only as leads, not as the basis for architecture claims.

## 5. Local Constraints

- Android 15, LSPosed/zygisk_vector, SELinux Enforcing.
- Module minSdk must be derived from the repo; no new runtime dependency on GMS.
- One hook registration owner per process and one authoritative `MainHook.CURRENT` snapshot.
- Runtime work must be bounded: no classpath-wide scans, no hot-path reflection after installation, deduplicated hooks, observable success/failure.
- Failure must retain the current timer/config and AOSP location behavior; never widen real-location leakage.
- Tests must be behavioral where possible, with exact-device evidence for current Maps.

## 6. Output Schema

Provide:

1. A versioned API/bytecode map of the public factory and concrete runtime shape.
2. Evidence supporting and opposing runtime-instance discovery.
3. Candidate seams ranked by stability, coverage, runtime cost, and testability.
4. A recommended state machine with discovery, validation, deduplication, hook installation, observability, and failure behavior.
5. A concrete TDD/device-validation matrix, including Task, callback, listener, and PendingIntent coverage.
6. Confidence per claim and direct source provenance.

## 7. Decision Interface

Classify each candidate:

- **Adopt:** stable enough for the production hook.
- **Pilot:** needs a narrow instrumented device experiment.
- **Defer:** too brittle, broad, or costly.

Map the recommendation onto `HookUtils.registerAllHooks`, existing `HOOKED` deduplication, and the single `Snapshot` serving point.

## 8. Risk Register

1. **Factory boundary changes:** runtime discovery silently stops. Mitigate with explicit evidence and fallback diagnostics.
2. **Partial method coverage:** last/current calls spoof but streaming or PendingIntent leaks. Mitigate with a surface matrix and fail-closed claims.
3. **Duplicate hooks:** repeated factory calls stack callbacks. Mitigate with classloader/runtime-class dedup keys.
4. **Overbroad reflection:** startup/CPU regression or unintended methods hooked. Mitigate with assignability plus exact signature validation.
5. **False success:** class found but no concrete method hook installed. Mitigate by recording per-surface installation counts and device readback.

## Local anchors

- `HookUtils.resolveFusedImpl()` and `hookFusedLocation()` in the feature branch.
- `docs/experiments/address-hook-cadence/phase-a-insight.md` §6.
- `docs/experiments/address-hook-cadence/phase-b-results.md` real-coordinate E2E section.
- Operator decision on 2026-08-04: hold merge and continue a robust fused implementation discovery design.

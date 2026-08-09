---
feature_ids: []
topics:
  - android
  - xposed
  - google-play-services-location
doc_kind: research_decision
created: 2026-08-04
---

# Decision: discover GMS fused clients by returned capability

## Adopt

Hook the two public `LocationServices.getFusedLocationProviderClient(...)` overloads. In the factory after-hook, validate the returned object against the public `FusedLocationProviderClient` contract, resolve its exact concrete methods by public signature, and install deduplicated Xposed hooks before the object returns to application code.

This replaces internal-name guessing with a bounded runtime capability check. It works across the inspected 18.0.0, 20.0.0, 21.0.1, 21.3.0, and 21.4.0 artifacts and matches current Maps 26.31, whose implementation is application-obfuscated to `bkmc` while the public factory and interface remain intact.

## Keep

- One authoritative `MainHook.CURRENT` snapshot.
- Public `LocationResult` accessors as defense in depth.
- One registration owner and class/method identity deduplication.
- Existing config FileObserver plus timer safety net.

## Remove

- Internal implementation candidate strings.
- Private GMS field-name mutation fallbacks.
- README claims that exceed the exact Task/callback/listener/PendingIntent extraction surfaces proven by tests.

## Pilot before merge

Implement red-first around a pure method planner, then verify current Maps end to end. Discovery logs are diagnostic only; acceptance requires both API readback and the Maps blue dot to follow the configured coordinate without hook-count or resource growth.

---
feature_ids:
  - mock-location-v2
topics:
  - android
  - intent
  - type-safety
doc_kind: bug-report
created: 2026-08-03
---

# Mock Provider coordinate extras trigger Bundle type warnings

## Report and diagnosis

- **Reporter:** Codex Sol during moto g54 acceptance.
- **Reproduction:** tap Start in the lab Activity and inspect logcat.
- **Expected:** latitude and longitude are decoded without framework warnings.
- **Actual:** `BaseBundle.getString` logs `ClassCastException` because the
  Activity stores both extras as `Double` while the Service probes them first
  with `getStringExtra`.
- **Root cause:** the decoder supported an obsolete string-based ADB path before
  reading the app's actual typed contract. The Service is non-exported and its
  only producer writes `Double`, so probing another type is both unnecessary
  and noisy.

## Fix and verification

Read the extras directly with `getDoubleExtra(..., Double.NaN)` and retain the
finite-value validation. A structural regression test locks the producer/
consumer type agreement; the full unit/build gate and a clean device log verify
the fix. No user data or provider behavior changes.

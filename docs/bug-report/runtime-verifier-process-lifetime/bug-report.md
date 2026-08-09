---
feature_ids:
  - runtime-hook-verification
  - configurable-hook-refresh
topics:
  - android
  - acceptance
  - logcat
doc_kind: bug-report
created: 2026-08-02
---

# Runtime verifier conflates separate Android process lifetimes

## 1. Reporter

Codex Sol found this while restoring the reviewed release's refresh interval to 30 seconds during
the moto g54 runtime-verification matrix at `960fe75cceb80811bc78475cf725bad4708b09e8`.

## 2. Reproduction and evidence

1. Clear logcat, force-stop Cellular-Pro and launch its public splash activity.
2. Cellular-Pro can briefly start for a scheduled service and then restart for its foreground
   activity. The observed lifetimes were PID 12928 followed by PID 12973.
3. Each PID emitted exactly one `scheduler_owned` event for the same Android process name.
4. Run `scripts/test_runtime_verify_flow.py --from-adb --require-scheduler`.

Expected: two separate PID lifetimes, each with one owner, satisfy INV-4.

Actual before the fix: the verifier returned
`duplicate scheduler owner for make.more.r2d2.play.cellular_pro` because it counted only the
payload's `process=` field and discarded the PID already present in logcat's brief prefix.

## 3. Root cause analysis

The production invariant is one scheduler per Android process lifetime, not one scheduler per
process name across the whole log buffer. `parse_line()` retained the event payload but not the
logcat PID, and `verify_trace()` grouped all owner events solely by `event.process`. A legitimate
OS restart was therefore indistinguishable from installing two schedulers in one live process.

### Diagnosis capsule

| Field | Evidence / strategy |
|---|---|
| Phenomenon | Two distinct PIDs produce a false duplicate-owner failure |
| Evidence | ActivityManager death/restart trace for 12928 → 12973 and one owner line per PID |
| Root cause | Host parser drops logcat PID before enforcing scheduler uniqueness |
| Diagnostic strategy | Trace raw logcat prefix → parsed event → owner aggregation key |
| Timeout strategy | If PID-aware grouping does not explain the trace, inspect scheduler registration rather than relaxing the invariant |
| Warning strategy | A repeated owner from the same PID must continue to fail |
| User-visible correction | Device acceptance no longer reports a false scheduler failure after an app process restart |
| Acceptance | Cross-PID owners pass; same-PID duplicate and PID-less duplicate remain fail-closed |

## 4. Fix

Parse the PID from the `adb logcat -v brief` prefix and enforce uniqueness per `(process, pid)`.
When any owner line lacks PID provenance, retain the old conservative behavior and require the
process name to appear only once. This avoids weakening fixtures or imported log files whose
lifetime cannot be established.

## 5. Verification

- RED: the new cross-PID contract failed under the process-name-only implementation.
- GREEN: `scripts.test_runtime_verify_flow_contract` passes 11/11, including mixed PID provenance.
- Same-PID duplicate still fails with `duplicate scheduler owner for com.example pid=100`.
- Real device dogfood force-started Cellular-Pro in PID 17273, then PID 17458; both emitted one
  30-second owner and the patched verifier passed.

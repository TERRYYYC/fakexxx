---
feature_ids: [F003]
topics: [device-evidence, acceptance, stage-toggles]
doc_kind: note
created: 2026-08-02
---

# F003 Stage Toggles — Device Acceptance Evidence

Spot-check of the **Location stage OFF** path on the operator's device.

- **Device**: `ZY22JHW9M4` — moto g54 5G, Android 15
- **Date**: 2026-08-02 ~01:07–01:12 (device local)
- **Mode**: Location stage OFF, CellRebel stage ON, buffer 10s, settle 10s, plan = 1 point (30.5236, 50.4500), quota 1
- **Covers**: AC-F3-2 (Location OFF → zero Fake GPS interaction, CellRebel lifecycle + quota unchanged, `gps_skipped` audit), INV-F3-1 (skip recorded in History + export)

## Verdict: PASS

- logcat: `Location stage OFF — skipping Fake GPS entirely (gps_skipped)` → `IDLE → LAUNCHING_CELLREBEL` (~100 ms after attempt start); `FakeGpsHandler` occurrences: 0
- Result: Verified successes 1/1 in 35 s; History: `succeeded · stage: gps_skipped · running observed 01:11:14`
- Export (16 columns): trailing `stage_notes` = `gps_skipped`

## Files

| File | Content |
|------|---------|
| `f003-plan-screen-location-off.png` | Plan screen: Location switch OFF, CellRebel ON, imported 1 row |
| `f003-run-cellrebel-launched.png` | Run screen: CellRebel launched directly, no GPS stages |
| `f003-run-done.png` | Run screen: Done, 1/1 verified |
| `f003-history-gps-skipped.png` | History row with `stage: gps_skipped` |
| `f003-logcat-run.txt` | Full filtered logcat of the run |
| `f003-cellrebel_attempts_20260802_011242.csv` | Pulled 16-column export |

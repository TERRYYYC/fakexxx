---
feature_ids: [F003]
related_features: [F001]
topics: [android, automation, decoupling, pipeline, fake-gps, cellrebel]
doc_kind: spec
created: 2026-08-01
---

# F003: Pipeline Stage Toggles (per-stage skip switches)

> **Status**: done (merged `fdcab16`) | **Owner**: @kimi | **Reviewer**: @codex-sol | **Priority**: P1

## Why

Operator (2026-08-01 21:38 UTC): the address-selection stage (Fake GPS app) is unstable and may be replaced by another app later. The pipeline must be decoupled so each external-app stage can be skipped independently while the rest of the plan keeps running:

> "加几个开关选项……开关用于跳过本次流程，直接进行下一步流程……很有可能我后续会去选择别的 app，即第一部的流程替换为别的 app，但是第二部还是需要去点击 cellrebel 进行测试。目的是解耦当前每个 app 之间的流程。"

Related: issue #3 (Fake GPS coordinate snap problem, F002 idea) — F003 is the decoupling that makes swapping that app possible without touching the engine.

## Pre-F003 Baseline (state before this feature)

- Engine attempt sequence is hardwired: Fake GPS set+verify → settle → CellRebel verified attempt → finalize (F001, merged `225bdfb`).
- PlanConfig (DataStore) persists buffer / test timeout / GPS settle; no stage switches.
- Attempt audit (History + 15-column export) has no way to express "a stage was skipped".

## What

Two independent toggles on the Plan screen, persisted in `PlanConfigStore`:

| Toggle | Default | OFF semantics |
|--------|---------|---------------|
| **Location stage** (Fake GPS set + activation verify + settle) | ON | Engine skips the entire Fake GPS interaction and settle wait; the attempt proceeds directly to CellRebel. The operator is responsible for location by other means (another app / manual). |
| **CellRebel test stage** | ON | Engine performs the location stage only; the attempt terminates as GPS-verified without launching CellRebel. Diagnostic "location walk" mode for validating a (replacement) location app. |

Rules:

1. **Both OFF = invalid**: Start is blocked with an explicit message (nothing would be executed).
2. **Audit honesty**: every attempt records which stages were skipped; History rows and CSV export show it (new trailing column `stage_notes`, values `gps_skipped` / `test_skipped` / empty). No silent unverified success.
3. **Quota semantics**:
   - Location stage OFF: success still requires the full verified CellRebel lifecycle (INV-3 unchanged); the attempt simply starts at CellRebel.
   - CellRebel stage OFF: the attempt counts toward quota when the location stage completed verified (status `ok_gps_only`); if the location stage also fails, typed failure as usual.
   - If location stage is ON, its typed failures behave exactly as F001 (fail closed, no quota).
4. Toggles are runtime preferences (DataStore), NOT plan data; changing them mid-plan takes effect from the next attempt.
5. Out of scope: replacing the Fake GPS app itself (F002/issue #3), per-location stage overrides, more than two stages.

## User Journey

1. Operator opens Plan screen → sees two switches (Location stage / CellRebel test stage) above Advanced, both ON by default.
2. To test a replacement location app: turns Location stage ON, CellRebel stage OFF → Start → engine walks all points setting GPS only; History shows `ok_gps_only` per point.
3. To run tests with location handled externally: Location OFF, CellRebel ON → attempts go straight to CellRebel; History marks `gps_skipped`.
4. Both OFF → Start blocked with message.

## Acceptance Criteria

- [x] AC-F3-1: Toggle states persist across app restart (DataStore) and default ON.
- [x] AC-F3-2: Location OFF → engine never touches Fake GPS (no launch, no settle delay), CellRebel lifecycle and INV-3 quota semantics unchanged; attempt rows/export carry `gps_skipped`.
- [x] AC-F3-3: CellRebel OFF → engine never launches CellRebel; verified GPS activation terminates the attempt as `ok_gps_only` and counts quota; export carries `test_skipped`.
- [x] AC-F3-4: Both OFF → start rejected with explicit message; no session created.
- [x] AC-F3-5: Mid-plan toggle change takes effect from the next attempt without restart.
- [x] AC-F3-6: Unit tests for each path (engine stage-skip, persistence, audit columns); full suite + lint green.

## Invariants (extend F001 INV-1..10)

- **INV-F3-1**: A skipped stage is always recorded; no attempt may look fully-verified when a stage was skipped.
- **INV-F3-2**: With CellRebel stage ON, only the verified lifecycle increments success (F001 INV-3 unchanged).
- **INV-F3-3**: Stage toggles never modify plan/task persisted data.

## Implementation Plan (light)

1. `PlanConfig` + `PlanConfigStore`: `locationStageEnabled: Boolean = true`, `testStageEnabled: Boolean = true` + setters. Test: defaults, persistence, independent writes.
2. Engine: read toggles per attempt (via config Flow snapshot at attempt start); skip branches; stage notes into attempt finalize; both-OFF guard at start. Tests: skip paths, quota semantics, mid-plan change (re-read per attempt), both-off rejection.
3. Schema v4: `test_attempts ADD COLUMN stageNotes TEXT` (additive migration 3→4 + test); History + `AttemptCsvMapper` trailing `stage_notes` column + tests.
4. Plan screen: two `Switch` rows with supporting text; both-off Start guard message.
5. Device spot-check on the moto g54 (location OFF path is the operator's primary use case).

## Key Decisions

| # | 决策 | 理由 | 日期 |
|---|------|------|------|
| KD-F3-1 | Toggles are DataStore runtime preferences, not plan fields | Operator may flip them mid-plan; plan CSV stays the pure worklist | 2026-08-01 |
| KD-F3-2 | `ok_gps_only` counts quota in test-OFF mode | The mode's purpose is validating the location app end-to-end; honest distinct status keeps audit clean | 2026-08-01 |
| KD-F3-3 | Both OFF rejected | A no-op pipeline is a configuration error, not a mode | 2026-08-01 |

## Timeline

| 日期 | 事件 |
|------|------|
| 2026-08-01 | Operator ordered stage-decoupling toggles at P1 on a new branch; F003 kicked off |
| 2026-08-02 | PR #4 merged (`fdcab16`): 94/94 tests, lint clean; R1 three state-machine fixes (mid-plan both-off fail-closed, ok_gps_only buffer gating, sweep-before-guard); device spot-check PASS (Location OFF → zero Fake GPS → verified success with `gps_skipped` audit). Reviewer final PASS recorded as PR comment (shared GitHub account blocks APPROVE events; merge body documents this provenance) |

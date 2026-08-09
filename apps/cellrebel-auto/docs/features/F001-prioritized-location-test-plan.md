---
feature_ids: [F001]
related_features: []
topics: [android, automation, fake-gps, cellrebel, scheduling, persistence]
doc_kind: spec
created: 2026-07-30
---

# F001: Prioritized Location Test Plan

> **Status**: done (merged `225bdfb`) | **Owner**: @kimi | **Reviewer**: @codex-sol | **Priority**: P0

## Why

The operator does not need random points from a GPS bounding box. They provide a worklist whose longitude, latitude, priority, and required success count define the product's core workload. The app must finish the required number of verified CellRebel results at each location, in deterministic priority order, without counting stale scores or failed attempts as successes.

Operator experience:

> “我会给你一份清单，里面有经度 纬度 优先级 次数。这些信息贯穿我们app的业务。”

> “后面的次数为这个循环，需要在这个点的cellrebel成功上传的次数。”

## Current State / 现状基线

- `AutoConfig` models a random GPS bounding box plus a global `maxCycles`; it cannot represent an operator-provided location worklist or per-location success quota.
- `AutomationEngine` generates a new random point every cycle and advances after every attempt, regardless of whether the requested location has reached a required success count.
- `CellRebelHandler` waits a fixed 30 seconds and accepts any nearby score text. Operator screenshot evidence shows that old `EXCELLENT / 10.00` values remain visible behind the in-progress overlay, so score presence alone cannot prove a fresh completed test.
- `collectDelayMs` is passed into `CellRebelHandler.runTest()` but is not consumed.
- Location configuration is memory-only; Room uses destructive migration and no automated test source set exists.
- Repository build reproduction is incomplete in the current checkout: `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar` are absent.

## What

### Phase A: Worklist domain, persistence, and deterministic scheduler

- Restore a reproducible Gradle/JDK 17 build and test baseline.
- Import a CSV worklist with canonical columns:
  `longitude,latitude,priority,required_successes`.
- Persist the imported plan, original row order, per-location progress, attempts, and session linkage in Room.
- Select tasks by ascending numeric priority and then ascending original row order.
- Keep executing the current location until its verified success quota is complete.
- Enforce one globally configured inter-attempt buffer after every terminal attempt.

### Phase B: Verified Fake GPS and CellRebel attempt lifecycle

- Represent handler outcomes explicitly instead of relying on `Unit`, nullable scores, or warning logs.
- Require a fresh CellRebel transition:
  `READY/COMPLETED -> RUNNING -> COMPLETED`.
- Treat `Processing results...`, `Measuring video streaming quality...`, or a disabled Start button as running evidence.
- Count success only after running evidence has been observed, processing markers disappear, Start is enabled again, and both scores are valid and stable across consecutive polls.
- Do not require score values to change; two real runs can legitimately produce identical scores.
- Verify Fake GPS activation before every CellRebel attempt and fail closed when app switching or GPS activation cannot be proven.

### Phase C: Operator workflow, progress visibility, export, and target-device acceptance

- Add a plan screen for CSV import, validation errors, stable priority order, global buffer configuration, per-location progress, and resume/stop controls.
- Surface attempt state where it happens: setting GPS, GPS settling, testing, processing results, cooldown, failed, and completed.
- Extend history and CSV export with plan row, priority, target coordinate, success ordinal, attempt ordinal, terminal status, timestamps, and scores.
- Resume unfinished plans after app or service restart without losing completed success counts.
- Validate the end-to-end journey on the target MIUI device.

## User Journey

### Primary Journey: Execute a prioritized location worklist

- **Scope unit**: location plan
- **Actor**: operator
- **Entry**: CellRebel Auto control surface
- **Flow**:
  1. The operator imports a CSV containing longitude, latitude, priority, and required success count.
  2. The app validates rows and displays the execution order; lower priority numbers appear first and equal priorities retain CSV order.
  3. The operator sets one global inter-attempt buffer and starts the plan.
  4. The app selects the first location, activates Fake GPS, verifies activation, and runs CellRebel attempts.
  5. A success is counted only after the UI visibly transitions through running and returns to a stable completed result.
  6. The app cools down, repeats the same location until its quota is complete, then advances to the next row.
  7. Progress survives restart; the operator can inspect and export every attempt.
- **Success evidence**: UI screenshots, persisted Room records, exported CSV, automated scheduler/parser tests, and a target-device run log.
- **Non-goals**: XLSX parsing, multi-OEM support, Play Store distribution, remote dashboard sync, weighted/round-robin scheduling, or a general-purpose automation state-machine rewrite.

### Proposed UI skeleton (Design Gate input)

```text
Location Plan
[ Import CSV ]  [ Global buffer: ___ seconds ]

Order  Priority  Coordinate             Progress     State
1      1         116.397, 39.908        2 / 3        cooldown
2      1         121.474, 31.230        0 / 5        pending
3      2         ...                    0 / 2        pending

[ Start / Resume ]  [ Stop ]

Current attempt
Setting GPS -> GPS settling -> Testing -> Processing -> Completed -> Cooldown
```

The implementation owner must return the in-context wireframe for reviewer and operator confirmation before changing the Compose UI.

## Lifecycle Objects and Invariants

### Object census

| Object | Lifecycle owner | Persisted state |
|--------|-----------------|-----------------|
| Location plan | repository + scheduler projection | import metadata and global buffer; overall status is derived |
| Location task | scheduler | coordinates, priority, original order, quota, completed successes |
| Test attempt | automation engine | timestamps, state, evidence, scores, failure reason |
| Run session | automation engine | active/terminal session and plan linkage |

Plan status must be a projection: completed when all task quotas are complete, running when an active session exists, and ready otherwise. Do not persist a second independently mutable plan-status field.

### State transitions

| Object | Event | From | To | Side effect |
|--------|-------|------|----|-------------|
| Location task | selected | pending | active | create attempt |
| Test attempt | Start accepted + running evidence | starting | running | record running evidence timestamp |
| Test attempt | stable completed result | running | succeeded | persist scores; increment task success exactly once |
| Test attempt | timeout/error/stop | starting/running | failed/interrupted | persist reason; do not increment success |
| Location task | success quota reached | active | completed | scheduler advances after cooldown |
| Run session | operator stop/process recovery | running | stopped/interrupted | leave remaining task quota resumable |

### Invariants

- **INV-1**: Scheduling order is `priority ASC, originalRow ASC`.
- **INV-2**: A task is not advanced until `completedSuccesses == requiredSuccesses`.
- **INV-3**: Only `starting -> running -> succeeded` increments success, exactly once.
- **INV-4**: Failed, timed-out, cancelled, or interrupted attempts never increment success.
- **INV-5**: A new attempt cannot start before the prior terminal attempt's global buffer expires.
- **INV-6**: Score presence without observed running evidence is stale and must not count.
- **INV-7**: Identical before/after score values are valid if the running transition was observed.
- **INV-8**: Every attempt belongs to exactly one plan task and one run session.
- **INV-9**: Restart recovery preserves task counts and marks a non-terminal attempt interrupted.
- **INV-10**: Fake GPS or foreground-switch verification failure is a typed failed attempt, never a warning-only success.

## Acceptance Criteria

### Phase A: Worklist domain, persistence, and scheduler

- [ ] AC-A1: A clean checkout has one documented Gradle command that compiles the app and runs tests successfully.
- [ ] AC-A2: CSV import accepts the four canonical columns, reports row-specific validation failures, and has automated positive/negative tests.
- [ ] AC-A3: Scheduler tests prove ascending priority, stable same-priority input order, quota completion before advancement, and failure-not-counted behavior.
- [ ] AC-A4: Room tests prove imported plan and progress survive repository recreation without destructive migration.
- [ ] AC-A5: Virtual-time tests prove the global buffer prevents an early next attempt after both success and failure.

### Phase B: Verified attempt lifecycle

- [ ] AC-B1: Tests using completed and in-progress accessibility snapshots prove that old visible scores during processing are not accepted.
- [ ] AC-B2: A test attempt cannot succeed unless running evidence was first observed and a stable completed result followed.
- [ ] AC-B3: Start interaction performs a coordinate fallback only when the first click did not produce running evidence.
- [ ] AC-B4: Fake GPS activation, foreground switching, timeout, cancellation, and score parsing failures produce typed terminal outcomes and never increment quota.
- [ ] AC-B5: `testTimeout` and `interAttemptBuffer` have distinct semantics; no ignored timing parameter remains.

### Phase C: Operator workflow and acceptance

- [ ] AC-C1: The approved Compose journey imports a plan, shows validation, execution order, current state, and per-location progress.
- [ ] AC-C2: Stopping/restarting the app resumes the same unfinished plan without duplicate success counts or orphaned running attempts.
- [ ] AC-C3: History and CSV export identify the plan row, coordinates, priority, success ordinal, attempt ordinal, status, timestamps, and scores.
- [ ] AC-C4: A target-device acceptance plan produces 20 consecutive verified successes, with evidence that each attempt observed the running-to-completed transition.
- [ ] AC-C5: A failure-injection run proves failed attempts remain visible, do not consume quota, and retry the same location only after the global buffer.

## 需求点 Checklist

| ID | 需求点（operator experience/转述） | AC 编号 | 验证方式 | 状态 |
|----|------------------------------------|---------|----------|------|
| R1 | 清单包含经度、纬度、优先级、次数，并贯穿业务 | AC-A2, AC-C1, AC-C3 | import tests + screenshots + CSV | [ ] |
| R2 | 优先级数字越小越高 | AC-A3 | scheduler test | [ ] |
| R3 | 相同优先级按清单顺序 | AC-A3 | stable-order test | [ ] |
| R4 | 次数是该地点需要成功完成的 CellRebel 次数 | AC-A3, AC-B2 | scheduler + lifecycle tests | [ ] |
| R5 | 测试间使用全局统一缓冲时间 | AC-A5, AC-B5 | virtual-time tests + UI screenshot | [ ] |
| R6 | 第一张图是完成，第二张图是进行中 | AC-B1, AC-B2 | accessibility snapshot fixtures | [ ] |
| R7 | 失败不计数且保留进度 | AC-B4, AC-C2, AC-C5 | failure-injection tests | [ ] |

### 覆盖检查

- [ ] 每个需求点都能映射到至少一个 AC
- [ ] 每个 AC 都有验证方式
- [ ] 前端需求已准备需求→证据映射表

## Dependencies

- **Evolved from**: `v0.1.0-mvp` random bounding-box cycle
- **Blocked by**: target MIUI device access for Phase C acceptance
- **Related**: `docs/ROADMAP.md` reliability milestone and `HANDOFF.md` MIUI findings

## Risk

| 风险 | 缓解 |
|------|------|
| Accessibility tree does not expose processing text or Start enabled state reliably | Capture tree snapshots for both operator-provided screen states and use multiple explicit running markers |
| Old scores remain visible during a new run | Require an observed running transition before accepting completion |
| Identical consecutive results are mistaken for stale data | Prove freshness by state transition, not score-value change |
| Process death creates duplicate counts | Persist attempt identity and make success finalization transactional/idempotent |
| CSV assumption differs from the operator's eventual source format | Keep CSV as the dependency-free canonical contract; adapt only after inspecting the real sample |
| Retry loop runs forever on an unrecoverable location | Keep manual stop/resume and visible failure reasons; do not silently skip quota |

## Open Questions

| # | 问题 | 分类 | 状态 |
|---|------|------|------|
| OQ-1 | What internal default test timeout should ship? The global buffer has no default: it is required on first use and then persisted; both settings remain semantically separate. | technical | ⬜ verify during implementation |
| OQ-2 | Which exact accessibility nodes expose running and Start-enabled state on the target CellRebel build? | technical | ✅ resolved 2026-08-02: real anchors captured on moto g54 (Android 15) — `Measuring web browsing quality…` / `Measuring video streaming quality…` (U+2026, ids `web_progress_text`/`video_progress_text`), Start `enabled` toggles; `Processing results...` absent in this build |
| OQ-3 | Does the eventual operator list arrive as CSV or require another source adapter? | value | ⬜ CSV is current reversible default |

## Key Decisions

| # | 决策 | 理由 | 日期 |
|---|------|------|------|
| KD-1 | Lower numeric priority executes first | Explicit operator requirement | 2026-07-30 |
| KD-2 | Equal priorities preserve source row order | Explicit operator requirement | 2026-07-30 |
| KD-3 | One global inter-attempt buffer | Explicit operator requirement | 2026-07-30 |
| KD-4 | Completed result screen is the current business evidence of one successful CellRebel upload | Operator identified the first screenshot as completed and the second as running | 2026-07-30 |
| KD-5 | Freshness is proven by a running transition, not changed score values | Running screenshot retains prior score values; legitimate consecutive scores may also be identical | 2026-07-30 |
| KD-6 | Extend the sequential coroutine engine instead of rewriting it as a generic state machine | Keeps the product's existing validated orchestration boundary and avoids unrelated architecture scope | 2026-07-30 |
| KD-7 | Use the approved Plan → Run → History journey; require the global buffer on first use and persist it without inventing a default | Operator approved the Design Gate v2.1 journey and buffer behavior | 2026-07-31 |

## Tips Contribution（F244）

`tips_exempt: this external Android project has no Tips catalog or runtime surface.`

## Timeline

| 日期 | 事件 |
|------|------|
| 2026-07-30 | Operator clarified prioritized worklist, quota, global buffer, and screen-state semantics; F001 kicked off |
| 2026-07-31 | Operator approved Design Gate v2.1: Plan → Run → History, with the global buffer required on first use and persisted |
| 2026-08-01 | PR #2 merged (`225bdfb`): 80/80 unit tests, lint clean, two review rounds (10 + 2 findings) resolved with red→green evidence |
| 2026-08-02 | Device verification on moto g54 (Android 15): full lifecycle smoke 1/1 success with running-transition audit; acceptance run (10 Kyiv points × 2) started healthy (2/2 first attempts) — operator then waived the remaining run ("当前这个流程没有问题了") and declared F001 done; AC-C4/C5 full-run evidence partially waived by operator |

## Review Gate

- Design: **passed** — @kimi's v2.1 wireframe was reviewed by @codex-sol and approved by the operator in message `0001785537089616-000460-086cdf15`.
- Implementation: @kimi uses an isolated worktree and TDD; no feature code is written directly on `main`.
- Code review: @codex-sol reviews but does not author the implementation.
- Acceptance: target-device evidence is required; emulator/unit tests alone cannot satisfy Phase C.

## Links

| 类型 | 路径 | 说明 |
|------|------|------|
| Roadmap | `docs/ROADMAP.md` | Reliability milestone |
| Handoff | `HANDOFF.md` | Existing MIUI and third-party app behavior |
| Discussion | `feature-discussions/2026-07-30-f001-prioritized-location-plan/README.md` | Operator requirements and screen-state evidence |
| Design Gate | `feature-discussions/2026-07-30-f001-design/README.md` | Approved in-context UI journey and AC/INV mapping |

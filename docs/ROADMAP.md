---
topics: [roadmap, mvp, release]
doc_kind: roadmap
created: 2026-04-04
---

# ROADMAP

Assumption for this document: the repository tag `v0.1.0-mvp` dated 2026-04-04 is treated as the first public GitHub milestone for this project.

## Project Identity

- **Name**: CellRebelAuto
- **One-liner**: Executes prioritized fake-GPS location worklists and records verified CellRebel success quotas.
- **Target user**: Android testers running repeated network-quality checks across spoofed locations on MIUI-class devices.
- **Current status**: MVP tagged in the GitHub repository
- **Distribution**: GitHub repository
- **First public release date**: 2026-04-04
- **Tech stack summary**: Kotlin, Jetpack Compose, Room, AccessibilityService, coroutine-based Android UI automation

## MVP Scope Audit

### Originally Planned

| Feature | Planned | Shipped | Delta |
|---------|---------|---------|-------|
| Local control panel for starting and stopping automation cycles | Yes | Yes | On track |
| GPS bounding-box and timing configuration | Yes | Yes | On track |
| Automatic fake-location switching before each cycle | Yes | Partial | Core workflow exists, but MIUI restrictions still reduce reliability |
| Automated CellRebel test execution with score collection | Yes | Partial | Score collection exists, but end-to-end reproducibility still depends on device behavior |
| Local history view for recorded cycles | Yes | Yes | On track |
| CSV export for recorded test results | Yes | Yes | On track |
| Debug log export and accessibility-tree export | No | Yes | Scope creep — justified for device-level troubleshooting |

### Scope Deviation Analysis

The MVP scope was small enough to produce a usable Android tool, but the main source of deviation was not feature count. It was OEM behavior on the target phone. MIUI app-launch restrictions turned cross-app orchestration into the hardest part of the project, so part of the implementation effort shifted from feature delivery to foreground recovery and switching logic.

The unplanned debug export work was justified. Without log export and accessibility-tree dumps, diagnosing failures in Fake GPS and CellRebel would have been much slower on the target device. The strongest gap in the MVP is reliability, not breadth: most intended capabilities exist, but two cross-app features still ship with environment-dependent behavior.

## Architecture Decisions

### ADR-001: AccessibilityService as the primary automation mechanism

- **Context**: The product needed to operate two third-party Android apps that do not expose a direct integration API.
- **Decision**: Use an in-app AccessibilityService plus node search and gesture dispatch as the main automation layer.
- **Alternatives considered**: ADB or shell-only automation; external UI automation frameworks; manual operation.
- **Outcome so far**: Partially validated
- **Evidence**: The repository contains dedicated handlers for Fake GPS and CellRebel, plus a working automation service. `HANDOFF.md` also shows that OEM launch restrictions still affect reliability.

### ADR-002: Sequential coroutine orchestration instead of an event-driven state machine

- **Context**: The automation flow is stepwise and depends on ordered waits, retries, persistence, and repeatable cancellation behavior.
- **Decision**: Implement the main run loop as a sequential coroutine pipeline in `AutomationEngine`.
- **Alternatives considered**: Event-driven state machine; callback-based step chaining.
- **Outcome so far**: Validated
- **Evidence**: The run loop clearly expresses GPS generation, Fake GPS setup, wait intervals, CellRebel execution, result persistence, and loop termination. The tagged MVP note also confirms the minimum flow reached a usable milestone.

### ADR-003: Return to the app hub and use Recent Apps to mitigate MIUI restrictions

- **Context**: MIUI can block `startActivity` calls when the foreground app is another third-party app.
- **Decision**: Return to CellRebel Auto between phases and use Recent Apps as a switching and recovery path.
- **Alternatives considered**: Repeated direct `launchApp` calls; home or back navigation only; shell-based `am start`.
- **Outcome so far**: Partially validated
- **Evidence**: `AutomationEngine` and `CellRebelHandler` contain explicit recent-apps switching logic, and `HANDOFF.md` documents the MIUI restriction as a central discovery.

### ADR-004: Local Room persistence for sessions and per-cycle results

- **Context**: Each run needed a durable local record for later inspection and CSV export.
- **Decision**: Store `RunSession` and `TestResult` in Room and expose them through history and export workflows.
- **Alternatives considered**: Log-only storage; CSV-only export without a local database; remote storage.
- **Outcome so far**: Validated
- **Evidence**: The repository contains Room entities, DAO-backed access through `TestRepository`, a history UI, and a CSV exporter.

## Technical Debt Inventory

### Critical

| ID | Description | Cost of Delay (3 months) | Effort Estimate |
|----|-------------|--------------------------|-----------------|
| TD-1 | MIUI app-switch restrictions still threaten the full Fake GPS -> CellRebel flow | End-to-end automation remains unreliable on the main target environment, so exported datasets stay hard to trust | L |
| TD-2 | No automated tests protect handlers, parsing, persistence, or orchestration seams | Refactors stay risky and regressions are likely to be detected late and manually | L |

### Tolerable

| ID | Description | Cost of Delay (3 months) | Effort Estimate |
|----|-------------|--------------------------|-----------------|
| TD-3 | Fake GPS coordinate search may not yield a visible search result | More runs require manual diagnosis or fallback behavior, which slows throughput | M |
| TD-4 | Release metadata is inconsistent (`versionName = 1.0` vs git tag `v0.1.0-mvp`) | Changelog, APK identity, and future release notes can drift and confuse operators | S |
| TD-5 | Runtime configuration is held in memory and does not persist across restarts | Operators must re-enter settings after app restarts, which increases setup friction and human error | S |

### Intentional (conscious trade-off, revisit at next reliability milestone)

| ID | Description | Revisit Trigger | Original Justification |
|----|-------------|-----------------|------------------------|
| TD-6 | `fallbackToDestructiveMigration()` rebuilds the local DB on schema changes | First build where preserving prior run history matters | Acceptable during MVP while the schema is still changing |
| TD-7 | CellRebel completion uses a fixed 30-second wait instead of robust completion detection | Test duration variance starts causing false early or false late collection | The earlier completion-detection logic was less reliable than a fixed wait in the observed app behavior |

## Next Phase Scope

### Phase Goal

Make prioritized location worklists reproducible on the target MIUI device and trustworthy enough to compare location-based network test results.

### Will Do (committed)

| Priority | Feature/Task | Success Criteria | Depends On |
|----------|--------------|------------------|------------|
| P0 | Execute F001 prioritized location plans | Lower numeric priorities run first, equal priorities preserve input order, and each location advances only after its verified CellRebel success quota is complete | CSV worklist import and persistent per-location progress |
| P0 | Stabilize MIUI app switching | The full Fake GPS -> CellRebel -> return-to-self cycle succeeds in 20 consecutive runs on the target device | Access to the target device and its MIUI permission settings |
| P0 | Verify the full end-to-end cycle with exported evidence | At least one CSV export contains 20 complete multi-cycle results with valid timestamps, coordinates, and scores | TD-1 mitigation |
| P1 | Add regression coverage for core non-UI logic | A local test suite runs in CI or locally and covers config validation, result persistence, and score-record serialization | Basic test harness setup |
| P1 | Persist operator configuration and align release metadata | Settings survive app restart and one canonical version label is used in both app metadata and git release flow | Owner decision on release versioning |

### Won't Do (explicitly deferred)

| Feature/Request | Why Not Now | Revisit When |
|-----------------|-------------|--------------|
| Multi-OEM device support | One target environment must be made reliable before broadening compatibility claims | After the MIUI target path is stable |
| Play Store distribution work | Packaging and store compliance are premature while the core automation path is still environment-fragile | After the tool is operationally stable |
| Remote telemetry or dashboard sync | Local Room storage and CSV export already cover the current MVP feedback loop | After stable on-device metrics are collected regularly |
| State-machine rewrite | Reliability is a higher-value problem than architecture elegance right now | If the sequential flow becomes hard to extend or debug |
| Rich analytics instrumentation beyond current local data | The team does not yet have a stable baseline process to justify broader telemetry investment | After next-milestone success criteria are consistently met |

### Open Questions

- [ ] Which version identifier is canonical for future releases: `1.0` or `0.1.0`?
  - Context: Changelog, app metadata, and future milestones cannot stay inconsistent.
  - Deadline: Before the next tagged build.
- [ ] Which MIUI permission combination produces the most stable app-switch flow on the target device?
  - Context: TD-1 blocks trustworthy end-to-end automation.
  - Deadline: Before the next milestone sign-off.
- [ ] Which device and ROM combinations stay in scope for the next milestone?
  - Context: Scope control determines whether reliability claims remain honest.
  - Deadline: Within the next planning cycle.

## Metrics

### Currently Tracked

| Metric | Current Value | Target (next phase) | Source |
|--------|---------------|---------------------|--------|
| Per-cycle web browsing score | No baseline captured in the repository snapshot | Capture a stable 20-cycle baseline on the target device | Local Room DB / CSV export |
| Per-cycle video streaming score | No baseline captured in the repository snapshot | Capture a stable 20-cycle baseline on the target device | Local Room DB / CSV export |
| Session status and completed cycles | No baseline captured in the repository snapshot | Record one completed session with 20 consecutive successful cycles | Local Room DB |

### Should Track (not yet instrumented)

| Metric | Why It Matters | Effort to Instrument |
|--------|----------------|----------------------|
| MIUI launch failure rate | It quantifies whether switching mitigation is actually improving reliability | M |
| End-to-end cycle duration | It shows whether fixed waits and retries are the main throughput bottleneck | S |
| Fake GPS location-set success rate | It separates GPS-side instability from CellRebel-side instability | M |

---
topics: [draft, changelog, roadmap, release]
doc_kind: draft
created: 2026-04-04
---

# Template Mapping Draft

This appendix maps repository facts into near-template Markdown for `CHANGELOG.md` and `docs/ROADMAP.md`.

It is intentionally conservative:

- it uses only repo-visible evidence
- it marks missing data as `owner input required` or `not verifiable from repo-only evidence`
- it does not pretend to be a final published document

## Draft A: CHANGELOG.md Mapping

### Notes Before Finalization

- The repo has a tag named `v0.1.0-mvp`, but the Android app currently declares `versionName = "1.0"`.
- No repo-local evidence confirms a GitHub Release page, Play Store listing, or other public release channel.
- `Known Issues` entries below are derived from `HANDOFF.md` and must be confirmed against the currently distributed build before publishing.

### Proposed Markdown

```markdown
# Changelog
# 变更日志

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
# 未发布

No verifiable unreleased changes. As of 2026-04-04, local `HEAD` matches tag `v0.1.0-mvp`.

## [0.1.0] - 2026-04-04
# Source tag: `v0.1.0-mvp`
# Owner confirmation required: whether `0.1.0` is the correct public SemVer label

### Added
- Added an in-app control panel to start and stop repeated automation cycles.
- Added configurable GPS range and timing settings for location-based test runs.
- Added automatic fake-location switching before each test cycle.
- Added automated CellRebel test execution with score collection for each cycle.
- Added local result history with per-cycle timestamps, scores, and coordinates.
- Added CSV export for saved test results.
- Added debug tools for exporting logs and accessibility tree snapshots.

### Fixed
- Fixed a case where repeated Fake GPS start actions could cancel each other out.

### Known Issues
- [Critical] Full Fake GPS to CellRebel app switching may still fail on MIUI-class devices due to OEM launch restrictions — owner confirmation required.
- [Minor] Coordinate search in Fake GPS may return no visible result in some cases — map-based placement remains the current fallback.
```

### Finalization Gaps

- Root-level `CHANGELOG.md` does not exist yet.
- The public version label must be unified between Git tag and Android manifest metadata.
- Bottom-of-file link references are intentionally omitted from this draft until the authoritative public tag / release URLs are confirmed.

## Draft B: ROADMAP.md Mapping

### Notes Before Finalization

- This draft is internal-facing and more comfortable with uncertainty than the changelog draft.
- `Originally Planned` is reconstructed from code shape, tag notes, and `HANDOFF.md`, not from a formal pre-development spec.
- `Will Do` and `Won't Do` below are analyst proposals to complete the template structure; they are not owner-approved commitments.

### Proposed Markdown

```markdown
## Project Identity

- **Name**: CellRebelAuto
- **One-liner**: Automates repeated CellRebel network tests across randomized fake GPS locations.
- **Target user**: Android testers validating network quality across multiple spoofed locations on MIUI-class devices.
- **Current status**: MVP tagged; public release status not verifiable from repo-only evidence
- **Distribution**: GitHub repository confirmed; packaged distribution channel not verifiable from repo-only evidence
- **First public release date**: 2026-04-04 (tag date; owner confirmation required for public-release date)
- **Tech stack summary**: Kotlin, Jetpack Compose, Room, AccessibilityService, coroutine-based Android UI automation

## MVP Scope Audit

### Originally Planned

| Feature | Planned | Shipped | Delta |
|---------|---------|---------|-------|
| Run repeated CellRebel tests from one app | Yes | Partial | Core loop exists, but MIUI app-switching reliability is still a live risk |
| Randomize GPS location inside a configured bounding box | Yes | Partial | Range randomization exists; Fake GPS search remains inconsistent |
| Persist per-cycle results locally | Yes | Yes | On track |
| Review results in-app and export them | Yes | Yes | On track |
| Export debug logs and accessibility snapshots | No | Yes | Scope creep — justified for device-level troubleshooting |

### Scope Deviation Analysis

The MVP scope was narrow enough to produce a working Android tool, but the main source of deviation was not feature count. It was OEM behavior on the target device. MIUI launch restrictions turned cross-app orchestration into the hardest part of the project, so a meaningful portion of the work shifted from feature building to environment-specific recovery logic.

The additional debug tooling appears justified rather than regrettable. Without log export and accessibility-tree dumps, diagnosing Fake GPS and CellRebel UI failures on-device would have been much slower. The sharper scope concern is reliability, not breadth: the tool can demonstrate the intended workflow, but the end-to-end path is still partially environment-dependent.

## Architecture Decisions

### ADR-001: AccessibilityService as the primary automation mechanism
- **Context**: The product goal required interacting with two third-party Android apps that do not expose a first-party integration API.
- **Decision**: Use an in-app AccessibilityService plus node search and gesture dispatch as the main automation layer.
- **Alternatives considered**: ADB / shell-only automation; external UI automation tools; manual operation.
- **Outcome so far**: Partially validated
- **Evidence**: The repository contains dedicated handlers for both target apps and a working automation service, but handoff notes still show OEM restrictions around app switching.

### ADR-002: Sequential coroutine orchestration instead of an event-driven state machine
- **Context**: The automation flow is inherently stepwise and depends on ordered waits, retries, and persistence.
- **Decision**: Implement the run loop as a sequential coroutine pipeline in `AutomationEngine`.
- **Alternatives considered**: Event-driven state machine; callback-heavy handler chaining.
- **Outcome so far**: Validated
- **Evidence**: The run loop cleanly expresses generation, GPS setup, wait, test run, persistence, and repeat. The tag note also confirms the minimum workflow reached an MVP milestone.

### ADR-003: Return to the app hub and use Recent Apps to mitigate MIUI restrictions
- **Context**: MIUI can block `startActivity` flows when the foreground app is another third-party app.
- **Decision**: Return to the project app between phases and use Recent Apps as a recovery/switching mechanism.
- **Alternatives considered**: Direct repeated `launchApp`; home/back navigation only; shell-based `am start`.
- **Outcome so far**: Partially validated
- **Evidence**: `AutomationEngine` and `CellRebelHandler` contain explicit recent-apps switching logic, and `HANDOFF.md` documents the MIUI restriction as a central discovery.

### ADR-004: Local Room persistence for sessions and per-cycle results
- **Context**: Test runs need a durable local record for later review and export.
- **Decision**: Store `RunSession` and `TestResult` in a Room database and expose them in history/export workflows.
- **Alternatives considered**: Log-only storage; CSV-only export without local DB; remote storage.
- **Outcome so far**: Validated
- **Evidence**: The repository contains Room entities, DAOs, repository methods, a history screen, and CSV export.

## Technical Debt Inventory

### Critical

| ID | Description | Cost of Delay (3 months) | Effort Estimate |
|----|-------------|--------------------------|-----------------|
| TD-1 | MIUI app-switch restrictions still threaten the full Fake GPS → CellRebel flow | End-to-end automation remains unreliable on the main target environment, making result datasets hard to trust | L |
| TD-2 | No automated tests protect handlers, parsing, persistence, or orchestration seams | Refactors stay risky and device regressions are likely to be detected late and manually | L |

### Tolerable

| ID | Description | Cost of Delay (3 months) | Effort Estimate |
|----|-------------|--------------------------|-----------------|
| TD-3 | Fake GPS coordinate search may not yield visible search results | More runs require manual diagnosis or fallback behavior, slowing test throughput | M |
| TD-4 | Release metadata is inconsistent (`versionName = 1.0` vs tag `v0.1.0-mvp`) | Changelog, APK identity, and future release notes can drift and confuse operators | S |
| TD-5 | Runtime config is held in memory and not persisted across restarts | Operators must re-enter settings after app restarts, raising setup friction and human error | S |

### Intentional (conscious trade-off, revisit at next reliability milestone)

| ID | Description | Revisit Trigger | Original Justification |
|----|-------------|-----------------|------------------------|
| TD-6 | `fallbackToDestructiveMigration()` rebuilds local DB on schema changes | First build where preserving prior run history matters | Acceptable during MVP while the schema is still in flux |
| TD-7 | CellRebel completion uses a fixed 30-second wait instead of robust completion detection | When test duration variance causes false early/late collection | The prior completion-detection logic was less reliable than a fixed wait in the observed app behavior |

## Next Phase Scope

### Phase Goal

Make the automation loop reproducible on the target MIUI device and trustworthy enough to compare location-based network test results.

### Will Do (analyst proposal; owner confirmation required)

| Priority | Feature/Task | Success Criteria | Depends On |
|----------|--------------|------------------|------------|
| P0 | Stabilize MIUI app switching | Full Fake GPS → CellRebel → return-to-self cycle succeeds in 20 consecutive runs on the target device | Access to the target device and MIUI permission settings |
| P0 | Re-enable and verify the full end-to-end cycle | Fake GPS stage is active in the main flow and at least one exported CSV contains complete multi-cycle results | TD-1 mitigation |
| P1 | Add minimum regression coverage for parsing, persistence, and orchestration helpers | A small automated test suite runs locally and covers non-UI core logic | Basic test harness setup |
| P1 | Persist operator config and align release metadata | Settings survive app restart and version label is consistent across code and git release process | Owner decision on public versioning scheme |

### Won't Do (explicitly deferred; analyst proposal)

| Feature/Request | Why Not Now | Revisit When |
|-----------------|-------------|--------------|
| Multi-OEM device support | One target environment must be made reliable before broadening compatibility claims | After the MIUI target path is stable |
| Play Store distribution work | Packaging and store compliance are premature while the core automation path is still environment-fragile | After the tool is operationally stable |
| Remote telemetry or dashboard sync | Local Room storage and CSV export already cover the current MVP feedback loop | After stable on-device metrics are being collected regularly |
| State-machine rewrite | Reliability is a higher-value problem than architecture elegance right now | If the sequential flow becomes hard to extend or debug |
| Rich analytics instrumentation beyond current local data | The team does not yet have a stable baseline process to justify broader telemetry investment | After next milestone success criteria are met |

### Open Questions

- [ ] Which channel is the authoritative public release source: Git tag, GitHub Release, private APK delivery, or another channel?
  - Context: This determines how `CHANGELOG.md` links and release dates should be written.
  - Deadline: Before publishing formal release documentation.
- [ ] Which version identifier is canonical for the MVP: `1.0` or `0.1.0`?
  - Context: Changelog, app metadata, and future milestones cannot stay inconsistent.
  - Deadline: Before the next tagged build.
- [ ] Which device / ROM combinations are in-scope for the next milestone?
  - Context: The current main blocker is OEM-specific behavior, so scope boundaries matter.
  - Deadline: Within the next planning cycle.

## Metrics

### Currently Tracked

| Metric | Current Value | Target (next phase) | Source |
|--------|---------------|---------------------|--------|
| Per-cycle web browsing score | Not available from repo snapshot | Establish baseline from the first verified multi-cycle run | Local Room DB / CSV export |
| Per-cycle video streaming score | Not available from repo snapshot | Establish baseline from the first verified multi-cycle run | Local Room DB / CSV export |
| Session status and completed cycles | Not available from repo snapshot | Record successful completion for 20 consecutive cycles on target device | Local Room DB |

### Should Track (not yet instrumented)

| Metric | Why It Matters | Effort to Instrument |
|--------|----------------|----------------------|
| MIUI launch failure rate | Quantifies whether app-switching mitigation is actually improving reliability | M |
| End-to-end cycle duration | Reveals whether fixed waits and retries are the main throughput bottleneck | S |
| Fake GPS location-set success rate | Separates GPS-side instability from CellRebel-side instability | M |
```

## Cross-Reference Check Draft

| Changelog Entry | Roadmap Section 2 Mapping | Status |
|-----------------|---------------------------|--------|
| Control panel for automation cycles | Run repeated CellRebel tests from one app | Mapped |
| Configurable GPS range and timing | Randomize GPS location inside a configured bounding box | Mapped |
| Automatic fake-location switching | Randomize GPS location inside a configured bounding box | Mapped |
| Automated CellRebel test execution | Run repeated CellRebel tests from one app | Mapped |
| Local history and CSV export | Persist per-cycle results locally; Review results in-app and export them | Mapped |
| Debug log export and accessibility snapshots | Export debug logs and accessibility snapshots | Mapped |
| Fake GPS double-trigger fix | No direct Scope Audit row; belongs to release-quality detail, not MVP scope item | Investigate / acceptable omission |
| MIUI app-switch known issue | Technical Debt `TD-1` and Scope Deviation Analysis | Mapped |
| Fake GPS search known issue | Technical Debt `TD-3` and partial shipping delta | Mapped |

## Recommended Next Step

Use this draft as the input set for a final pass that creates:

- root-level `CHANGELOG.md`
- `docs/ROADMAP.md`

That final pass should happen only after the owner confirms:

- canonical release channel
- canonical version label
- which known issues are still live
- next-phase committed priorities and explicit deferrals

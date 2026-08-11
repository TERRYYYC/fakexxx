# Changelog
# 变更日志

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
# 未发布

No user-facing changes had been merged after `v0.1.0-mvp` as of 2026-04-04.

## [0.1.0] - 2026-04-04
# 对应仓库 tag：`v0.1.0-mvp`

### Added
- Added a local control panel to start, stop, and monitor repeated CellRebel automation runs.
- Added GPS range configuration with a bounding box, collection delay, cycle interval, and cycle-count controls.
- Added automatic fake-location switching before each network test cycle.
- Added automated CellRebel test execution with per-cycle score capture for web browsing and video streaming.
- Added local test history with timestamps, coordinates, cycle status, and score review.
- Added CSV export for recorded test results.
- Added debug exports for runtime logs and accessibility tree snapshots.

### Fixed
- Fixed a case where repeated Fake GPS start actions could cancel each other out when triggered twice in quick succession.

### Known Issues
- [Critical] Switching from Fake GPS to CellRebel may still fail on MIUI devices; reopening CellRebel Auto and retrying the cycle is the current workaround.
- [Minor] Entering coordinates in Fake GPS may not always show a visible search result; the map-tap workflow remains the current fallback.

[Unreleased]: https://github.com/TERRYYYC/Faketest/compare/v0.1.0-mvp...HEAD
[0.1.0]: https://github.com/TERRYYYC/Faketest/tree/v0.1.0-mvp

---
feature_ids: [F002]
related_features: [F001, F003]
topics: [android, fake-gps, location-precision, verification]
doc_kind: spec
created: 2026-08-01
---

# F002: Precise Location Selection & Spoof Verification (v2.1)

> **Status**: spec | **Owner**: @kimi | **Reviewer**: @codex-sol | **Priority**: P1 (operator-declared: "聚焦地址的问题，这个很重要")
> **Upstream**: [issue #3](https://github.com/TERRYYYC/Faketest/issues/3) with 2026-08-02 device experiment conclusions
> **v2**: revised per reviewer contract fixes (2026-08-01 23:20 UTC): verification decoupled from the location stage, Android-15-correct mock detection, fresh-fix sampling, runtime-permission preflight, structured audit fields, mandatory precise-path delivery.
> **v2.1**: second reviewer pass (2026-08-01 23:41 UTC): gate also covers `ok_gps_only` quota counting, activation-anchor freshness, permanent/recoverable failure classes with no infinite retry, `real-location-allowed` bypass removed, F003 doc truth-sync.

## Why

The operator's worklist coordinates are the business's core data, but the current Fake GPS app cannot select them: its search returns "no search results" for raw `lat,lng` and snaps the pin to the nearest geocoded address — unstable snaps observed ~0.9–1.5 km apart for the same requested point (issue #3 evidence). Every CellRebel result is currently attributed to the *requested* coordinate while the *actual* spoofed coordinate is unverified and possibly far off.

F002 has two goals that must NOT be conflated:

- **Gate (L1)**: no CellRebel test may run unless the device's *current* location is verified to be the target coordinate — regardless of how the location got there (our Fake GPS stage, a replacement app, or manual setup).
- **Cure (L2)**: deliver at least one usable precise-selection path so the gate stops rejecting. **F002 is not complete without L2** — a gate that only rejects does not solve the operator's address problem.

## Verified facts (device experiments, moto g54 / Android 15, 2026-08-02)

1. Fake GPS search does not resolve raw coordinates ("There are no search results"); pin snaps to nearest address, snap target unstable (~1 km-class error).
2. The phone's real GPS is strong (25–27 satellites, hAcc≈1.5–4 m); an inactive mock silently leaks the real location into tests.
3. adb key-stream text input is mangled by the IME; accessibility `ACTION_SET_TEXT` is reliable.
4. Our manifest declares `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — **but no runtime permission flow exists in the current code** (declaration ≠ grant).
5. A second fake-GPS app (`name.caiyao.fakegps`) is installed on the device — unexplored.
6. `dumpsys location` is a usable *diagnostic* channel, but production verification must use the in-app location APIs below.

## What

### L1 — Location verification gate (attempt precondition, stage-independent)

The engine runs its own location verification before **either** counting path: launching a CellRebel attempt, **or** counting an `ok_gps_only` success when the F003 CellRebel test stage is OFF. **This gate is NOT part of the F003 location stage and is NOT skipped when either stage toggle is OFF** — the GPS-only "validate the location app" mode is exactly where precision matters most, and an unverified `ok_gps_only` would be a false success.

1. **Permission preflight**: runtime check for `ACCESS_FINE_LOCATION`; if not granted, trigger the request flow. Typed failures: `LOCATION_PERMISSION_DENIED`, `LOCATION_APPROXIMATE_ONLY` (coarse-only grant).
2. **Fresh fix sampling with an activation anchor**: do NOT trust a bare `getLastKnownLocation()` (may be arbitrarily stale). Requirements per mode:
   - **Location stage ON** (our app drove Fake GPS): the accepted fix's monotonic timestamp (`elapsedRealtimeNanos`) must be **≥ this attempt's Fake GPS activation observation point** — a fix predating the activation is stale evidence of a previous state, never of this attempt.
   - **Location stage OFF** (external/manual location): obtain a fresh fix at verification time (request single update), accepting it only when its age ≤ explicit freshness budget, `hasAccuracy()`, and sampling completes within timeout.
   Typed failures: `LOCATION_NO_FIX`, `LOCATION_STALE_FIX`, `LOCATION_VERIFY_TIMEOUT`.
3. **Mock detection (Android 15 contract)**: use `Location.isMock()` (API 31+); `isFromMockProvider()` is deprecated and satellites-Bundle heuristics are rejected as a mock signal. **Mock is required, unconditionally** — a real-GPS fix anywhere is typed `LOCATION_NOT_MOCKED`. (v2.1: the `real-location-allowed` bypass is removed; mock-required is the fixed contract. If real-location testing ever becomes a need, it gets its own feature.)
4. **Tolerance check**: `distanceTo(target) ≤ tolerance`. Default 100 m (technical OQ, self-decided), configurable in Advanced. The tolerance actually used is recorded per attempt. Failure: typed `LOCATION_MISMATCH` with actual coordinate + distance.
5. **Failure classes and retry semantics** (permanent vs recoverable — no infinite retry loops):
   - **Pre-start** (preflight at Start/Resume): permission missing/coarse-only → Start blocked with explicit message; NO session or attempt created.
   - **Mid-run permanent** (permission revoked mid-plan, coarse-only downgrade): current attempt terminalized with the typed failure for audit, then the session PAUSES (operator-visible reason) instead of retrying — permanent errors never enter the retry loop.
   - **Recoverable** (`NO_FIX`, `STALE_FIX`, `VERIFY_TIMEOUT`, `MISMATCH`, `NOT_MOCKED`): typed terminal attempt failure, no quota consumed, retry the same location only after the global buffer (F001 INV-5 lineage).

### L1 audit — structured fields (no overloading of `stage_notes`)

`stage_notes` keeps its F003 skip-enum semantics (`gps_skipped` / `test_skipped`) and does NOT carry coordinates. Room v5 adds additive columns on `test_attempts`: `actualLatitude`, `actualLongitude`, `locationErrorMeters`, `fixIsMock`, `fixAt`, `verifiedAt`, `fixAccuracyMeters`, `toleranceMetersUsed`. History and CSV export surface them (new trailing columns; legacy rows blank).

### L2 — Precise selection path (spike → decision → implementation; MANDATORY)

Time-boxed spike, output is a decision record, then implement the winner:

1. Long-press map pin placement in the current Fake GPS app (bypasses geocoding snap): verify via tree dumps whether pin = exact coordinate.
2. Favorites/saved-locations flow with exact coordinates.
3. Evaluate `name.caiyao.fakegps` for direct coordinate entry.
4. If all fail: only the L1 sub-phase may close; F002 stays open and the imprecision is surfaced per-attempt via the L1 audit trail. **Closing F002 requires at least one usable precise-selection path.**

## Acceptance Criteria

- [ ] AC-F2-1: The verification gate runs before every CellRebel attempt **and before every `ok_gps_only` quota count** — including when either F003 stage toggle is OFF; typed failures (`LOCATION_PERMISSION_DENIED`, `LOCATION_APPROXIMATE_ONLY`, `LOCATION_NO_FIX`, `LOCATION_STALE_FIX`, `LOCATION_VERIFY_TIMEOUT`, `LOCATION_NOT_MOCKED`, `LOCATION_MISMATCH`) never consume quota. Unit tests with fake location providers.
- [ ] AC-F2-2: Mock detection uses `Location.isMock()`; no satellites/last-known-only heuristics in the decision path; freshness requires monotonic timestamps with an explicit budget AND (Location ON) a fix not older than the attempt's activation anchor.
- [ ] AC-F2-2b: Permanent failures (permission denied/revoked, coarse-only) block start preflight or pause the session mid-run; they never enter the buffer retry loop. Recoverable failures retry only after the global buffer.
- [ ] AC-F2-3: Audit records actual lat/lng, error meters, mock flag, fix/verification timestamps, accuracy, and the tolerance used; History + export show them (v5 additive migration with test).
- [ ] AC-F2-4: At least one precise-selection path is delivered and proven on device: ≥9/10 attempts within tolerance at non-addressable coordinates (park/river-class points where geocoding snap is large). If the spike yields no path, F002 remains open.
- [ ] AC-F2-5: Device verification run on the moto g54 demonstrates: address-snap point (would-be ~1 km error) is REJECTED by the gate, and the L2 precise path is ACCEPTED.

## Open Questions

| # | 问题 | 分类 | 状态 |
|---|------|------|------|
| OQ-F2-1 | Tolerance default (100 m proposed) and global vs per-attempt snapshot semantics — snapshot per attempt; final export must record the value used | technical | ⬜ plan phase |
| OQ-F2-2 | Which precise selection path wins | technical | ⬜ L2 spike |

## Non-goals

- Replacing scheduling/pipeline (F001/F003 stable).
- Multiple simultaneous location apps.
- Geocoding/reverse-geocoding inside our app.
- A gate-only "completion" — L2 is mandatory (v2 contract).
- A real-location bypass mode (v2.1: removed; mock-required is the fixed contract; real-location testing would be a separate future feature).

## Timeline

| 日期 | 事件 |
|------|------|
| 2026-08-01 | Operator declared address precision the top priority; issue #3 filed; spec v1 drafted |
| 2026-08-02 | Device experiments confirmed root cause and verification channels; reviewer returned v1 with 6 contract fixes (stage-independent gate, isMock, fresh-fix sampling, permission preflight, structured audit fields, mandatory L2); spec v2. Second pass added 4 contract fixes + F003 truth-sync (v2.1) |

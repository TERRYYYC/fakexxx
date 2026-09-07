---
feature_ids: [F-13]
topics: [application-id, cutover, concurrency, stateflow, review]
doc_kind: bug_report
created: 2026-09-07
---

# Application ID cutover Task 4 review-round fixes

## Report and reproduction

The non-author Task 4 review found four P2 failures at commit
`95d385f2df33af8450928acbd9020668507e6d05` and one independent acceptance-guard failure on the
previously pushed PR head. A queued exclusive executor child made lease release throw before the
child reference retired. A provider refresh during gate closure could throw from a pairing
`combine` and terminate its sharing coroutine. Recovery-closed `MainViewModel` projections exposed
normal empty/default initial values. Two replacement-import rejection paths retained their request
id and busy flag permanently. The acceptance guard also matched a real qwy application-id fixture in
`RoomV9CutoverStoreTest`.

## Root cause

The exclusive capability counted executor children but release asserted immediate root-only
ownership instead of asynchronously joining those already-retained children. Separately, the gate
controlled upstream repository flows but did not own the complete UI projection lifecycle: public
`stateIn` defaults, pairing's secondary query, and request state set before admission were outside
the close/cancel/reopen state machine.

## Fix

Exclusive release now waits non-cancellably for the child-reference drain signal before consuming
the root capability. Restored-data UI flows share one typed `Loading` / `Ready` / `Unavailable`
projection boundary driven by gate state; pairing performs its secondary read through typed normal
admission and recomputes after reopen. Replacement request ids and busy flags retire in `finally`,
while retryable proposals remain. The same failure-mode sweep also keeps a rejected revoke staged.
The Room fixture uses a synthetic provider application id without weakening the acceptance guard.

## Validation

Regression tests cover executor-tail release, all six recovery-closed public projections, pairing
close/reopen recomputation, confirmation and verified-stop rejection/retry, and rejected revoke
retry. Both `testLegacyIdDebugUnitTest` and `testProductIdDebugUnitTest` pass with 838 tests each.
Debug, glmbench, and release APK assembly passes for both identities, as do both debug lint tasks.
The identity APK contract, both release debug-only scans, lint-debt check, host/device-isolation
self-test (20/20), and forbidden-boundary guard (13/13) pass. The existing forbidden-boundary script
is the exact RED/GREEN signal for the CI fixture.

Device, emulator, ADB, installation, runtime application-id mutation, release, merge, and Issue #106
execution remain out of scope.

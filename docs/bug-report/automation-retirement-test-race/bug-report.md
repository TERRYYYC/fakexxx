---
feature_ids: ["#15"]
topics: [auto, test, cancellation, coroutine, determinism]
doc_kind: bug_report
created: 2026-09-06
---

# Bounded service-retirement test synchronization

The primary cleanup task observed a host worker stuck in
`AutomationServiceRecycleStateTest.a cancelled run cannot be replaced until its non-cancellable retirement completes`.
Its old test blob `efcbda5ee8841400302d5fd1212f2a91ca6b6b72` is identical in the
Auto base `58dbf2dd5e46e73f3ca6870da840cb49607e54f0` and the reported #103 checkout.

The test launched on `Dispatchers.Default` and immediately cancelled the job. If cancellation won
before the child entered its `try`, the `finally` block never ran and `enteredRetirement.await()`
waited forever. This is a test setup race, not a defect in `mayStartAutomation`.

The deterministic RED retained that cancel-before-start order, used
`StandardTestDispatcher(testScheduler)` to hold the launch until suspension, and bounded the
retirement wait at five seconds of virtual time. It compiled and failed in three wall seconds:

```text
./gradlew :app:testDebugUnitTest --tests '*AutomationServiceRecycleStateTest.a cancelled run*' --console=plain
1 test completed, 1 failed; BUILD FAILED in 3s.
TimeoutCancellationException: Timed out after 5s of virtual time.
```

The fix adds a signal completed inside the child's `try` and awaits it before cancellation.
All signal/release/join waits have explicit bounds. A `finally` always releases retirement,
cancels the child, and joins it in a bounded non-cancellable cleanup block. The delayed dispatcher
stays in the test, so removing the startup wait deterministically recreates the failure.
Both original assertions remain: a cancelled but still retiring job rejects replacement; a fully
retired job admits it. No production logic or state-machine predicate is changed.

Validation from `apps/cellrebel-auto`, with Android Studio JBR and the host Android SDK:

```text
./gradlew :app:testDebugUnitTest --tests '*AutomationServiceRecycleStateTest' --console=plain
BUILD SUCCESSFUL in 2s; all 3 tests passed.
./gradlew :app:testDebugUnitTest --rerun --tests '*AutomationServiceRecycleStateTest' --console=plain
Five separate forced runs: all exit 0, each executed testDebugUnitTest (not UP-TO-DATE).
Reported durations: 1s, 1s, 1s, 2s, 2s.
./gradlew :app:testDebugUnitTest --console=plain
BUILD SUCCESSFUL in 14s; 72 suites, 634 tests, 0 skipped, 0 failures, 0 errors.
```

This test-only change is separate from atomic-release commit
`aef5a03c8298fc424c595fef7d6b1e14a04158f6` so the startup-race fix can be cherry-picked
independently by the #103 lane. No device, emulator, installation or provider action was performed.

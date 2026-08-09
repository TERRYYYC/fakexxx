---
feature_ids:
  - runtime-hook-verification
topics:
  - review
  - android
  - navigation
doc_kind: review-request
created: 2026-08-02
---

# Review Request: navigation guard failure recovery

Review-Target-ID: runtime-verify-refresh
Branch: feat/runtime-verify-refresh
Previously approved HEAD: `8b312dac9f63ccfeddfbf2c45bd15dbac828ebbf`
Implementation commit: `325e6c2`

## What

- Route direct and deferred navigation actions through one `runInFlight` execution primitive.
- Acquire the entry token before invocation; if the action throws, release the token and rethrow
  the same failure instead of silently disabling later navigation.
- Add Red→Green coverage for both execution paths and document the complete guard state table,
  invariants and adversarial matrix.

## Why

Fable's exact-HEAD review found a valid P2 `[FC:new]`: an exception can occur before the entry's
lifecycle changes, leaving `navigationInFlight=true` forever while the page remains `RESUMED`.
Every later click would then be silently rejected by the guard that is meant to preserve navigation.

## Original Requirements

> Build and review first; do not install an unreviewed APK.
> The original double event must not blank the NavHost.
> A first back during entry animation must not be swallowed.

- Source: `feature-specs/2026-08-01-runtime-verification-and-refresh.md`, Task 5, and Fable's
  navigation review conditions on `960fe75` / `8b312dac`.
- Please verify this recovery edge preserves both user-visible navigation requirements.

## Tradeoff

- Catch `Throwable` only long enough to restore guard ownership, then rethrow the identical object.
  The guard does not swallow, translate or retry application failures.
- Use one shared helper for both action sites rather than two `try/catch` blocks. This adds one
  fallback layer in the file and keeps exception behavior structurally identical.

## Architecture Ownership

Architecture cell: existing per-`NavBackStackEntry` navigation-action boundary
Map delta: none
Why: this closes an error transition inside the existing guard; it adds no owner, queue, router,
store or persisted state.

Please verify the state table matches the implementation and the diff remains within this boundary.

## Open Questions

### Technical OQ

1. Does `runInFlight` cover both direct `submit(RESUMED)` and queued `onStateChanged(RESUMED)` paths?
2. Is releasing the token before rethrow sufficient to restore Ready without weakening duplicate
   suppression on successful navigation?
3. Do NAV-INV-1 through NAV-INV-5 close the state object rather than leaving another implicit edge?

### Value OQ

None. This is a reversible correctness repair inside the approved design.

## Failure-Mode Sweep Report

Pattern: navigation ownership lacks an exit transition.

| Scanned site / edge | Result | Disposition |
|---|---|---|
| Direct action execution from `submit(RESUMED)` | token stuck on throw | fixed through `runInFlight` |
| Deferred action execution from lifecycle `RESUMED` | token stuck on throw | fixed through `runInFlight` |
| Successful action followed by duplicate callback | token must remain owned | existing tests retain rejection |
| Retained entry later returns to `RESUMED` | token must reset | existing return-path test remains green |
| Queued entry leaves before execution | pending must clear | existing discard-path test remains green |
| Disposal | all transient state must clear | existing `dispose()` remains terminal owner cleanup |

Because this was the third boundary refinement on the same state object, the bug report now carries
the full state×event table, NAV-INV-1…5 and adversarial matrix before further implementation.

## Next Action

Fable: review the exact pushed HEAD and the delta after `8b312dac`. Confirm APPROVE or
REQUEST_CHANGES for the current SHA. Do not install the APK; on approval, return the ball to Sol for
the two exact-build navigation gestures and final probe sanity.

## Review Sandbox

- Path: `/tmp/cat-cafe-review/runtime-verify-refresh/fable5`
- Checkout: detached exact remote HEAD
- Start command: Android/host commands below; no web/api ports

## Quality Gate Evidence

### Spec compliance

- The exception path now returns the guard to Ready and preserves the original exception identity.
- Normal duplicate suppression, incoming queue/drain, outgoing discard, return and compound-action
  semantics remain covered.
- Architecture map delta is none; one shared `try/catch` is below the fallback-layer threshold.
- No `.pen` design, root media/design artifact or parallel router. This repository has no
  Cat-Café hotfix/fallback checker scripts; the one added `try/catch` was inspected manually.

### Dogfood-Your-Slice

Scope verdict: required, ordered after review. The feature plan explicitly prohibits installing an
unreviewed APK over the stable user installation. Pre-review evidence is the real guard state
machine; post-review acceptance must run the original double event and entry-animation first back
on the exact reviewed release before completion.

### Fresh commands and results

```text
NavigationActionGuardTest RED                         9 tests, 2 expected failures
NavigationActionGuardTest GREEN                       9 passed, 0 failed
:app:testDebugUnitTest --rerun-tasks                  310 passed, 0 failed
python unittest (three host modules)                  35 passed, 0 failed
assembleDebug + compileDebugAndroidTestKotlin
  + assembleRelease(R8) + lintVitalRelease           110/110 tasks, BUILD SUCCESSFUL
bash -n + py_compile + git diff --check               pass
release APK SHA-256                                   c330f34dac86066bc285ef78cbc1a91a7d1f48bace9d059a056e421becfeaf72
release manifest                                     HookVerificationService exported=false,
                                                     process=:hook_verify
release DEX/strings                                   production service/evidence present;
                                                     debug acceptance/recovery symbols absent
root media/design artifact gates                     empty
```

### Reproduction commands

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest --rerun-tasks \
  :app:assembleDebug :app:compileDebugAndroidTestKotlin \
  :app:assembleRelease :app:lintVitalRelease

python3 -m unittest \
  scripts.test_cellular_acceptance_matrix \
  scripts.test_hook_verdict \
  scripts.test_runtime_verify_flow_contract

bash -n scripts/test-hook.sh
python3 -m py_compile scripts/test_runtime_verify_flow.py \
  scripts/test_runtime_verify_flow_contract.py
git diff --check
```

### Related documents

- `feature-specs/2026-08-01-runtime-verification-and-refresh.md`
- `docs/bug-report/rapid-back-navigation-blank-screen/bug-report.md`

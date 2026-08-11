---
feature_ids:
  - runtime-hook-verification
  - configurable-hook-refresh
topics:
  - review
  - android
  - navigation
  - acceptance
doc_kind: review-request
created: 2026-08-02
---

# Review Request: runtime verification device follow-up

Review-Target-ID: runtime-verify-refresh
Branch: feat/runtime-verify-refresh
Previously reviewed base: `960fe75cceb80811bc78475cf725bad4708b09e8`
Implementation commits: `d65f505`, `70f693c`

## What

- Preserve logcat PID provenance and enforce one scheduler owner per `(process, pid)` lifetime;
  PID-less or mixed-provenance traces remain fail-closed.
- Replace the strict `RESUMED`-only navigation callback with one guard per back-stack entry: queue
  one first action during entry, acquire an in-flight token before navigation, and reject duplicate
  stack mutations even if two callbacks both observe `RESUMED`.
- Lock the Editor save-and-verify compound action as all-or-nothing at the guard boundary.

## Why

The authorized moto g54 matrix exposed two concrete gaps after the first approval: Android restarted
Cellular-Pro under a new PID and the host verifier falsely called its two valid owners a duplicate;
the reviewer-requested entry-animation tap also proved the strict guard silently swallowed a valid
first back action.

## Original Requirements

> Build and review first; do not install an unreviewed APK.
> Exercise 5s/60s, missing-field, probe-not-scoped, process death and retry; restore the interval.
> Fable's review condition: repeat the original double event on device and prove no white screen.

- Source: `feature-specs/2026-08-01-runtime-verification-and-refresh.md`, Task 5, plus the Fable
  verdict on reviewed base `960fe75`.
- Please judge whether the follow-up keeps acceptance truthful without regressing either rapid-back
  safety or the first-click experience.

## Tradeoff

- PID is used only when every owner event has canonical brief-log provenance. Any missing PID keeps
  the conservative process-name-only failure rather than guessing a lifetime.
- The navigation guard queues at most one action; later clicks in the same transition are discarded.
  No delay, debounce timer, or global navigation queue was added.
- A real `NavHostController` lifecycle is intentionally verified on the exact reviewed device build,
  not approximated by another JVM lifecycle fixture. The pure transition machine remains fully
  unit-tested.

## Architecture Ownership

Architecture cell: existing `AppNavGraph` navigation boundary and Task 5 host evidence verifier
Map delta: none
Why: both changes strengthen existing boundaries; they add no Store, Router, transport, scheduler,
or lifecycle owner.

Please verify the diff matches `Map delta: none` and that the per-entry guard does not become a
parallel navigation router.

## Open Questions

### Technical OQ

1. Does `navigationInFlight` close the consecutive `RESUMED` race while resetting correctly when a
   retained entry later returns to the foreground?
2. Is queue-on-`STARTED`, drain-on-`RESUMED`, clear-below-`STARTED` consistent with Compose
   Navigation entry/exit lifecycle behavior?
3. Does PID-aware grouping preserve INV-4 without allowing a same-PID or provenance-mixed duplicate?

### Value OQ

None. The validation order and reversible device operations are fixed by the feature plan.

## Fresh-Context Findings

Agent: `[砚砚/GPT-5.6 Sol🐾]` in a fresh finding-only session
SHA scanned: `960fe75` plus the uncommitted follow-up delta
Total findings: 3 (1 P1, 2 P2, 0 P3)

| # | Finding | Author disposition | Status |
|---|---|---|---|
| FC-1 | Two callbacks could both observe `RESUMED` before lifecycle downgrade | fixed by explicit in-flight token and RED test in `70f693c` | closed |
| FC-2 | Pure tests do not prove real NavHost lifecycle/observer timing | accepted validation boundary: exact reviewed APK must pass both device gestures before completion | pending device gate |
| FC-3 | Mixed PID/PID-less scheduler evidence lacked a fail-closed contract | fixed by contract test in `d65f505` | closed |

Formal reviewer should mark findings `[FC:covered]`, `[FC:new]`, or `[FC:N/A]`.

## Next Action

Fable: review the exact pushed HEAD, independently rerun the host/JVM checks, and return APPROVE or
REQUEST_CHANGES with P1/P2 findings. Do not install the new APK. On approval, return the ball to Sol
for exact-build device dogfood of both navigation gestures and a final probe sanity check.

## Review Sandbox

- Path: `/tmp/cat-cafe-review/runtime-verify-refresh/fable5`
- Checkout: detached exact remote HEAD
- Start command: the Android/host commands below; no web/api ports

## Quality Gate Evidence

### Spec compliance and device evidence

- On reviewed base `960fe75`, `adb install -r` preserved the existing profile.
- Original system-back + immediate top-left-back gesture returned to Map with a populated tree and
  no white screen. The entry-animation first back was swallowed, reproducing the P2 fixed here.
- 5s/60s refresh, missing-field fallback, NOT_SCOPED, and probe process-death/fresh-retry scenarios
  all passed. The module was restored enabled, interval restored to 30s, and the final probe delivered.
- Two real Cellular-Pro lifetimes (PID 17273 then 17458), one owner each, pass the patched verifier;
  same-PID duplicates and PID-less/mixed duplicates fail.
- The new release APK has not been installed. This preserves the review-before-install invariant.

### Fresh commands and results

```text
:app:testDebugUnitTest --rerun-tasks                 308 passed, 0 failed
python unittest (three host modules)                 35 passed, 0 failed
assembleDebug + compileDebugAndroidTestKotlin
  + assembleRelease(R8) + lintVitalRelease          110/110 tasks, BUILD SUCCESSFUL
bash -n + py_compile + git diff --check              pass
release APK SHA-256                                  1a0524f0eb4f743334fb583eeaa2a3ee9d3a867642a4d110f6ec39fdb1705f47
release manifest                                    HookVerificationService exported=false,
                                                    process=:hook_verify
release DEX/strings                                  production service/evidence present;
                                                    debug acceptance/recovery symbols absent
root media/design artifact gates                    empty
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
- `docs/bug-report/runtime-verifier-process-lifetime/bug-report.md`

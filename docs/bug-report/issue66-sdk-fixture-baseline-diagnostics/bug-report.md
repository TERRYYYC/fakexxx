---
feature_ids:
  - G2-66
topics:
  - host-gate
  - android-sdk
  - fixture-diagnostics
doc_kind: bug_report
created: 2026-09-05
status: diagnosing_linux_failure
github_issue: 66
---

# SDK fixture baseline diagnostics

## Diagnostic capsule

| Field | Result |
| --- | --- |
| Symptom | Two positive SDK tests return 1 with empty CLI output on Linux, while the other nine tests pass. |
| Evidence | [Run 33965387938](https://github.com/TERRYYYC/fakexxx/actions/runs/33965387938), host job `101304479473`, branch `6790aee7250ffd0bdd8cbe0657773555a0649145`; Java 21 and private-stager 9 tests pass before the SDK failure. |
| Known root cause | The tests have no positive fixture baseline before intentional mutations, and CLI rejection deliberately carries no diagnostic text. This hides the underlying Linux rejection; its exact cause remains unknown. |
| Strategy | Establish the unmodified fixture with the same production validator; on failure report its bounded ancestor authority and the original production stack. |
| Timeout strategy | Use the next real Linux CI run to identify the failed production check; do not guess another permission repair from macOS results. |
| Warning strategy | A local reproduction is not the Linux root cause unless its failure pattern and rejection stage match. Do not relax production SDK admission. |
| User-visible impact | CI failure becomes actionable; this does not change the app, SDK trust policy, or device-validation result. |
| Acceptance | An unsafe fixture ancestor causes a named baseline failure before negative mutation; existing mutation/budget tests retain their original checks; diagnostics contain no environment, SDK bytes, or ACL payloads. |

## Reproduction and exclusions

The failing tests are canonical-binding emission and acceptance of a legal symlink in an
unselected NDK package. Every SDK fixture is created with an explicit parent beneath the
repository's `scripts` directory, then resolved to its physical path; default `/tmp` selection
is therefore not an explanation. The internal entry-budget test calls the selected-tree scanner
directly and succeeds on Linux, whereas full validation also checks the external ancestor chain.
That narrows investigation but does not identify a particular unsafe ancestor or ACL.

Changing only local umask to `0002` produces three failures, including the entry-budget test;
the Linux job has only two. This is not sufficient evidence for an umask fix. No permission,
fixture-location, production-validator, or workflow change is made by this diagnostic patch.

## Diagnostic change and boundaries

`_write_sdk` now validates the complete, unmodified SDK fixture before it returns. Every existing
call occurs before the test's deliberate permission/ACL/symlink/content mutation, file removal,
or scanner mock/budget override. The original rejection assertions and budget counts are unchanged.

Only a failed baseline collects diagnostics: at most 32 root-chain entries, each with a path
capped at 512 characters, numeric owner and mode, and an ACL accepted/rejected status. The original
production traceback is capped at 12 frames and 4096 characters. Environment values, SDK file
contents, raw ACL data, subprocess output, and exception-message text are not added to that report.
Diagnostics do not repair authority or convert rejection to acceptance.

## Verification and next action

The new permanent regression places a fixture below a private `0775` ancestor. Before baseline
validation it is RED because the fixture constructor silently accepts that layout. With the
diagnostic check it is GREEN: the failure identifies that ancestor and mode, retains the production
`require_safe_state` stack, excludes sentinel environment/file content, and cleans the private
fixture. Local SDK-suite results and the next real Linux diagnosis are recorded in PR #81.
This patch improves evidence; it does not claim that the Linux SDK failure is fixed.

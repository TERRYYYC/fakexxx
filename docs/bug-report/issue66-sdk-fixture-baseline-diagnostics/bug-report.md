---
feature_ids:
  - G2-66
topics:
  - host-gate
  - android-sdk
  - fixture-diagnostics
doc_kind: bug_report
created: 2026-09-05
status: bytecode_confirmed_private_umask_fixture_fix_awaiting_linux
github_issue: 66
---

# SDK fixture baseline diagnostics

## Diagnostic capsule

| Field | Result |
| --- | --- |
| Symptom | Two positive SDK tests return 1 with empty CLI output on Linux, while the other nine tests pass. |
| Evidence | [Run 33965387938](https://github.com/TERRYYYC/fakexxx/actions/runs/33965387938), host job `101304479473`, branch `6790aee7250ffd0bdd8cbe0657773555a0649145`; Java 21 and private-stager 9 tests pass before the SDK failure. |
| Known root cause | ACL normalization and bytecode suppression now pass on Linux. Run `33968503219` reaches the combined suite, where the private `077` umask exposes a new fixture's implicit `0755` assumption. |
| Strategy | Preserve the proven host guards; explicitly establish the intended mode only on the private test fixture and replay its cases under the private umask. |
| Timeout strategy | The next real Linux job must pass the fixture under the combined runner's unchanged private environment; no production policy or source-cleanliness bypass is allowed. |
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
fixture-location, production-validator, or workflow change was made by the initial diagnostic patch.

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

## Linux follow-up: confirmed rejection and bounded candidate

Reported by the host CI and investigated by the Issue #66 host-gate team. The diagnostic
[run 33966365173](https://github.com/TERRYYYC/fakexxx/actions/runs/33966365173), host job
`101307093640`, reports run head `1f4e31646fd01a1d3def5cc1eba6c8ac63dda101`. Java 21 and
private-stager 9 tests passed. The SDK suite ran 12 tests with 23 failures including subtests:
the new positive baseline exposes the same external authority rejection before intentional
negative mutations, rather than treating those early rejections as evidence of the mutations.

The bounded report identifies `/home`, owner `0`, mode `0755`, with ACL status `rejected`;
the remaining reported ancestors were accepted. The original stack reaches
`_posix_snapshot` at line 251 of `validate-android-sdk-runtime.py`, the condition rejecting
an oversized or write-granting ACL payload. This establishes the failing inode/check,
not the ACL's exact entries or the runner provisioning source that created them.

Linux access ACL permissions correspond to the inode mode; a default ACL instead supplies
permissions for newly created children. Therefore mode `0755` does not establish a safe
default ACL. This makes a write-granting default ACL a specific candidate consistent with
the observation, not yet a directly observed fact. See the upstream
[ACL contract](https://man7.org/linux/man-pages/man5/acl.5.html). The inspected official
[runner image configuration](https://github.com/actions/runner-images/blob/ubuntu24/20260831.293/images/ubuntu/scripts/build/configure-system.sh)
does not establish the exact `/home` ACL, so no ACL subject or creator is attributed here.

### Candidate and alternatives

The temporary Linux host job now places `normalize reviewed home default ACL` strictly
between the reviewed JDK-container normalization and standalone security tests. Its unprivileged
Python imports the existing production parser and inspector and requires:

- A physical `/home` directory, root-owned and exactly mode `0755`, with stable descriptor
  and named-path identity.
- An absent or production-accepted access ACL. Any default ACL must be at most 64 KiB and
  must be classified as granting write by the original production parser; other states stop.
- Unchanged device/inode, owner/group, complete mode, access bytes, and default bytes on
  the immediate precheck.
- If a default exists, only the fixed, nonrecursive command
  `/usr/bin/sudo -- /usr/bin/setfacl --remove-default -- /home`, with a ten-second timeout.
  Repository Python itself never runs through sudo.
- Afterwards, unchanged device/inode, owner/group, complete mode and access bytes, absent
  default ACL, and acceptance by the original complete ACL inspector. An already-safe inode
  with no default is an explicit no-write, idempotent path.

The upstream [setfacl contract](https://man7.org/linux/man-pages/man1/setfacl.1.html)
distinguishes default removal from removing all extended entries or recursive application.
Those broader operations, access-ACL replacement, chmod/chown, moving fixtures to evade the
ancestor, and relaxing the validator were rejected. The strict step order and full-step SHA
are bound by the host Harness, including conditional-bypass and unsafe-command mutations.

### Regression evidence and remaining action

The new regression was RED before the workflow step existed. It then executes the actual
embedded workflow Python against private fixtures, using the original POSIX parser/inspector
and a mocked privileged command. All literal `/home` references are mapped to the private
fixture; neither the real local `/home` nor sudo is touched. Twelve scenarios cover default-only
removal with access/child preservation, no-default idempotence, unsafe access, readonly or
oversized default, wrong owner, wrong mode, symlink, regular file, and post-command changes
to access, default retention, or inode identity. The two positive cases ensure a shared
precondition failure cannot make the negative cases falsely green.

The complete SDK suite is 13 tests; its 12-scenario workflow regression passes locally.
The full host Harness is 141 tests across 15 suites with no failures, errors, or skips.
The Java suite retains all 21 cases, including the original seven JDK layouts; only its
immediate-next-step expectation changes to the new reviewed ACL step. Local mocked behavior
does not claim a real Linux `setfacl` execution or resolved Linux CI. The next action is an
independent exact-patch review followed by CI: require the step's guarded site confirmation
and SDK success. If the preconditions fail, investigate that evidence without widening scope.

## Confirmed ACL result and bytecode follow-up

[Run 33967895755](https://github.com/TERRYYYC/fakexxx/actions/runs/33967895755), host job
`101311129224`, run head `3dd79daaa3469bb0b31728e08ee01cba2ff15566`, emitted
`HOST_HOME_DEFAULT_ACL_REMOVED`. This confirms the guarded default-write/access-safe preconditions
and post-removal production-inspector acceptance on the actual Linux runner. Java 21,
private-stager 9, and SDK 13 tests all passed. The next combined gate failed at
`validate_untracked`: `repository contains a non-committed, non-ignored path`.

The ACL step and all three standalone suites load repository source modules through
`importlib` loaders. The combined runner also invokes those three suites, yielding seven
importing entrypoints. They used `-I` without explicit `-B`; isolated imports still generate
`scripts/__pycache__/*.pyc`. Our tested macOS `/usr/bin/python3` starts with
`sys.dont_write_bytecode=True` and flag value `1`, masking that default-dependent side effect.
Explicitly enabling bytecode in a private fixture reproduces it with the original modules.
The CI rejection itself does not print the rejected path; the exact cache-producing call chain
is independently reproduced, rather than inferred from the error message alone.

The minimal repair adds `-B` to those seven entrypoints only. Python documents
[`-B` as preventing imported-source cache writes](https://docs.python.org/3/using/cmdline.html#cmdoption-B);
`-I` does not imply it and ignores `PYTHON*` environment settings, so an environment-only
fix would not establish this contract. No validator, source provenance rule, ignore file,
or cache-deletion workaround changes.

The permanent regression uses the actual command flags and original module import paths in
private committed Git fixtures, explicitly modeling Linux's bytecode-enabled default on the
tested macOS interpreter. The original seven commands give seven RED cache-creation failures.
With `-B`, all seven leave no `.pyc` or untracked paths and preserve HEAD/tracked bytes;
removing `-B` separately from each command reproduces the cache and untracked-path rejection
predicate. The inline probe stops before any `/home` access. Targeted GREEN completes in
3.371 seconds; the full SDK suite passes all 14 tests in 4.923 seconds. These are local
regression results, not a claim that the next combined Linux gate has passed. Independent
review and the next CI remain the next actions.

## Confirmed bytecode result and private-umask fixture correction

[Run 33968503219](https://github.com/TERRYYYC/fakexxx/actions/runs/33968503219), host job
`101312747003`, reports run head `870acc6dab84a7d2b2ae18e658818bc60ac47167`.
The guarded ACL step and all 44 standalone tests passed. The combined runner advanced past
its initial clean-source check and repeated Java 21 and stager 9 successfully. Its SDK suite
then failed five subcases of the new home-ACL fixture, not the production ACL normalization.

The complete runner sets a private `077` umask. `home.mkdir(mode=0o755)` is filtered by that
mask and creates a `0700` directory, whereas the simulated fixed `/home` must be `0755`.
The two positive cases consequently stop at `HOST_HOME_ACL_TARGET_INVALID`; three
post-command mutation cases also stop before their expected command. Replaying the complete
SDK suite locally under `077` reproduces exactly these same five failures. This is distinct
from the previously excluded `0002` hypothesis for the older SDK-baseline failure.

The correction explicitly sets `0755` only on the newly created private fixture directory,
before deliberate unsafe-mode mutations. It changes neither the CI host `/home` nor the
runner umask, production validator, source-cleanliness policy, or command boundaries.
A permanent regression replays all 12 existing fixture scenarios under `077` and restores
the previous process mask in `finally`. Before the fixture correction it reproduces the five
failures; the corrected suite is tested under both `022` and `077`. The complete SDK suite
now contains 15 tests. Real Linux combined-host acceptance remains pending.

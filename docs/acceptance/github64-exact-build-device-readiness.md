---
feature_ids: [1, 7]
topics: [acceptance, g2, issue-64, issue-66, exact-build, rooted-device, lsposed, system-mock, evidence]
doc_kind: acceptance-readiness-package
created: 2026-08-31
status: blocked
candidate_head: 5002e0e005324c32ca3d36d10510180d1fafbf81
---

# GitHub #64 exact-build rooted-device acceptance readiness package

## Decision

**Current verdict: `NO_GO_DEVICE_EXECUTION`.** The host-side candidate package
can be rebuilt and audited now. No real-device command is authorized or safe to
run from the current harness.

This verdict is not a product `FAIL` and it is not a G2 `PASS`:

- exact candidate `5002e0e005324c32ca3d36d10510180d1fafbf81` has green host,
  CI and emulator evidence for the bounded claims recorded on PR #65;
- the exact candidate does not contain PR #63, does not contain the unmerged
  PR #62 harness work, and intentionally has no authoritative `FULL`
  continuity path from Issue #66;
- the shipped Hook runner is deterministically red against the product writer
  schema and lacks the device lease, report sealing and complete recovery
  envelope required for a controlled phone run;
- no operator device lease, install/replace authority, LSPosed scope approval,
  System Mock selection, mutation budget or restore authority has been issued.

The known connected Motorola remains explicitly out of scope. This package
contains no device serial and none of its executable checks invoke `adb`.

## Frozen host package

The machine-readable truth source is
`docs/acceptance/github64-exact-build-device-readiness.json`. Its host checker
accepts the exact product commit itself or a checkout whose only delta from that
commit is this committed four-file readiness package. The checkout itself must
be clean relative to its HEAD; modified/untracked preparation files are rejected.

| Fact | Frozen value |
|---|---|
| Product HEAD | `5002e0e005324c32ca3d36d10510180d1fafbf81` |
| Product tree | `ff4c6440509aa1d90b4a7a8dc6647b47c2d33af1` |
| Base HEAD | `9eb6389e05e49e5a19c3890fd1a39b9be7e11c1d` |
| Frozen manifest SHA-256 | `459648d13750c3fad3cec17de1a7c4145f736bea054b456a6b7813973b446ac1` |
| Contract SHA-256 | `c64dd132418493ba5918d86e481382d29b3d351867f3e3d3569577abb3d6f543` |
| 10-address fixture SHA-256 | `cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852` |
| Canonical device ledger | empty `[]`, SHA-256 `37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570` |

The selected APK bytes are two fresh, clean Java 17 builds:

| App | Package | Version | Bytes | APK SHA-256 | Signer SHA-256 |
|---|---|---:|---:|---|---|
| Auto debug | `com.example.cellrebelauto` | `1` / `1.0` | 11,413,622 | `7bd07b07fde483cf1252722f2c29880c0030d47e52638761f19fa2d0dc4a3f1b` | `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41` |
| QWY bench debug | `name.caiyao.fakegps.bench` | `8` / `3.0.0` | 23,194,413 | `bb5be7db762a0e38218465e321b582eddb62c3f9110b714ac1c18076a151a161` | `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41` |

The production checker has no caller-selected manifest or inspector seam. It
pins and records these host-only tool files before execution, rechecks them
immediately before each use, and supplies a minimal environment with no Android
device transport on `PATH`:

| Tool file | SHA-256 |
|---|---|
| Apple Git 2.50.1 `/usr/bin/git` | `b8763cf250e607a778bb4603cecb5b90338814d0a3dfcba0d57b1de242f610e9` |
| build-tools 36.1.0 `aapt` | `b08d65ee8f8ee6c8a2e9d5ed6b7881873df83e60c44800b951c30d4ff80d9efe` |
| build-tools 36.1.0 `apksigner` launcher | `b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0` |
| build-tools 36.1.0 `lib/apksigner.jar` | `71e18adf733f5e112d1f062dbe6b0c2eb439a4d7c773d083c42a703c66f56df1` |
| OpenJDK 17.0.20 `bin/java` | `77ddcbc036c6f6261d2583725018a6a45a2385d5339deea14e53cb8d91086192` |

Both bytesets repeated across two Java 17 clean builds. That is a bounded local
reproducibility observation, not a claim that source HEAD uniquely determines
an APK. In fact:

- the already-built APKs in the completed PR #65 worktree had different hashes
  (`d251d13c…` Auto and `75de443b…` QWY); and
- a Java 21 clean build retained the selected Auto hash but produced a different
  QWY hash (`92074b30…`).

Therefore existing emulator evidence is reusable only when its recorded APK
hash equals the APK under review. “Same Git SHA” is not an exact-byte binding.
The selected Java 17 APKs above have not been installed on a phone and inherit
no earlier device verdict.

## What can run now, without a device

Use an evidence directory outside the source checkout. The checker mechanically
rejects the report and sidecar if either resolves inside the source tree. The
audit command writes an atomic JSON report and a sibling `.sha256` sidecar. It
reads Git state, APK files and the pinned Android build tools only.

```bash
EVIDENCE_ROOT="$(mktemp -d)"

./scripts/selftest-github64-device-readiness.sh

./scripts/check-github64-device-readiness.py \
  --report "$EVIDENCE_ROOT/host-readiness.json"
```

Expected audit result is `hostStatus=PASS overallStatus=BLOCKED` and
`executedDeviceCommands=0`. Exit 0 means the **BLOCKED report is internally
valid**; it does not mean device-ready or G2-pass. For a scheduling gate, append
`--fail-on-blocked`; the current truthful exit code is 3. This is a frozen
snapshot gate, not a mutable readiness state machine: it is intentionally
incapable of turning this old candidate green after external work changes.

The two exact artifact builds are also device-free:

```bash
env JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home \
    ANDROID_HOME=/Users/terry/Library/Android/sdk \
    PATH=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
    apps/cellrebel-auto/gradlew -p apps/cellrebel-auto --no-daemon clean assembleDebug

env JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home \
    ANDROID_HOME=/Users/terry/Library/Android/sdk \
    PATH=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
    apps/qianwangyou/gradlew -p apps/qianwangyou --no-daemon clean assembleDebug
```

Rebuilding is a new byte selection event. If either hash changes, stop: update
and independently review the frozen manifest before any installed-byte claim.

## Go/no-go findings by G2 block

| Block | Current state | Issue #66? | Why |
|---|---|---|---|
| A — 10 addresses / 17 trusted successes | `BLOCKED` | **Yes** | PR #62 seed/readback and harness findings remain; PR #63 routing is absent; exact HEAD can report only `PARTIAL/NONE`, so it cannot mint the trusted quota that defines this block. |
| B — crash and recovery | `BLOCKED` | **Yes, for the continuity-dependent legs** | Lease/recovery cases also need PR #62 and PR #63. Issue #66 specifically blocks authoritative generation/sequence continuity, double-snapshot and durable ACK/crash proof. Some lease-state subcases may become runnable earlier, but the block cannot pass. |
| C — bilateral revocation | `BLOCKED` | **No** | It is blocked by the unsafe/incomplete harness and missing PR #63 principal routing. Issue #66 is not used as a blanket excuse for this block. |
| D — version skew | accepted `POST_V1` disposition | No | `M-VS-01` is outside current G2 by the accepted canonical disposition. No ledger row is minted. |
| E — exact build | host half `READY`; installed half `BLOCKED` | No | Host source/artifact/signer facts can be sealed now. Installed `base.apk`, package-manager signer and exact-byte equality require a safe harness plus operator authorization. |
| F — production | accepted `G3` disposition | No | Current package is debug-isolated only and makes no production-readiness claim. |
| G — Hook acceptance | `BLOCKED` | **No** | Exact HEAD emits cellular payload schema 3 while the writer contract requires 4; the raw report is temporary and not bound to APK/session/manifest; restore is incomplete. |
| `M-CO-06` | accepted host alternative | No | The accepted host-coverage disposition satisfies only its current G2 alternative. It is not device PASS and does not create a ledger row. |

### Deterministic Hook RED

This exact product HEAD currently produces:

```text
cd apps/qianwangyou
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts.test_cellular_acceptance_matrix

Ran 18 tests
FAILED (failures=1)
AssertionError: 4 != 3
```

The failing test is
`test_python_payload_version_is_pinned_to_writer_contract`. Existing PR #65 CI
and `verify-a-plus.sh` do not execute this Python suite. A green CI badge cannot
override this named red check.

## Why device work is not currently schedulable

The current runners violate the accepted execution/evidence boundary in four
independent ways:

1. `test-hook.sh` and its helper use unscoped device commands and have no
   explicit serial plus lease gate. Another runner contains a physical-device
   serial default. Zero device calls before authorization is therefore not
   enforceable by the shipped runners.
2. The Hook path wakes/unlocks, installs, grants permissions, force-stops and
   clears logs before a complete restore trap exists. The System Mock path can
   install and change AppOps without restoring prior APK, permission, power and
   foreground state.
3. The Hook raw report is deleted with a temporary directory and lacks a stable
   report path, report digest and installed-APK binding.
4. PR #62 addresses part of this surface but has an independent
   `CHANGES_REQUESTED` verdict for fixture pinning, monotonic schedule reset,
   impossible post-seed `discover()` readback, report persistence/APK binding
   and other evidence-contract gaps. Its green code must not be mixed into the
   exact PR #65 candidate as if it were already accepted.

`scripts/install_apk_verified.sh` has useful explicit-serial and byte-equality
logic, but it always installs, does not itself grant a device lease, and does
not independently seal package identity and signer into the final report. It is
not a read-only substitute for the missing preflight.

## Operator authorization packet required later

Authorization must bind one concrete, newly frozen candidate and one explicit
device session. A generic “use the phone” message is insufficient. The packet
must name:

1. device serial, model, fingerprint, Android release/API and whether its prior
   state is considered recoverable;
2. lease start/end or a bounded action budget, plus separate executor,
   evidence-recorder and evidence-validity-reviewer identities;
3. exact Auto and QWY APK hashes, package names, versions and signer, including
   whether install or replace is permitted and what to do on signer conflict;
4. the selected LSPosed module and exact package scope, the selected System Mock
   app/configuration, and every permission/AppOps/accessibility change allowed;
5. whether process kill, reboot, wake/unlock, log clear, app data clear,
   uninstall or production-package contact is allowed. Default is **denied**;
6. the pre-state capture and recovery plan: prior APK bytes, package/signer,
   permissions/AppOps, LSPosed scope, System Mock selection, power/lock state,
   foreground app, QWY profile/schedule state and any data backup;
7. cleanup/restore authority and the stop condition if restoration cannot be
   proved.

No package action, module toggle, reboot, permission change, data clear,
uninstall or cleanup is implied by this readiness document.

## Evidence collection contract for an authorized future run

The repaired runner must enforce the following before its first device call:

- explicit serial, device lease, role assignment, candidate HEAD/tree and both
  artifact hashes are present and match the signed execution packet;
- every device command is carried literally as `adb -s <packet-serial> ...`;
  bare/default transport is a classifier failure;
- the report directory is caller-selected and persistent, never a temporary
  directory deleted on exit;
- the restore trap is installed before the first mutation and owns every state
  field the session can change;
- destructive or production-touching actions are absent unless separately and
  explicitly authorized.

Each command carrier must preserve command bytes, stdout, stderr, exit code,
start/end timestamps and its access classification. The sealed evidence set
must additionally contain:

- source HEAD/tree/base and CI references;
- build commands, Java/Gradle/SDK versions and both artifact hashes;
- package/version/signer from each artifact;
- `pm path` proving exactly one installed `base.apk` per package;
- pulled installed bytes equal to the frozen artifact and installed signer equal
  to the artifact signer;
- device identity/configuration, LSPosed modules/scopes, System Mock selection,
  relevant permissions/AppOps and before/after/restore snapshots;
- raw block reports for A, B, C, E and G, screenshots where the predicate is
  visual, and durable database/ledger extracts where it is not;
- one SHA-256 manifest covering every evidence file, plus each block report
  digest and the top-level package-manifest digest;
- cleanup outcome and a machine-verifiable comparison against the captured
  pre-state.

Status handling is strict:

- `BLOCKED` means a named prerequisite such as PR #62, PR #63 or Issue #66 is
  unresolved; it is not a behavior failure;
- `NOT_RUN` / `NEEDS_AUTHORIZATION` means execution has not been authorized;
- `FAIL` is reserved for an authorized execution whose predicate is false or
  whose safety/evidence contract fails;
- canonical device ledger vocabulary remains unchanged. No `passed`, `failed`,
  `skipped` or `deferred` row is written before genuine canonical-row evidence.

## Re-entry order

Do not run the exact `5002e0e…` APKs merely to “see what happens.” Re-enter only
after these gates are satisfied:

1. Issue #66 implements and independently validates the authoritative oracle;
   PR #63 principal routing is integrated; PR #62 or its successor has an
   accepted safe execution/evidence contract.
2. Cut one new convergence candidate containing those changes. Re-run complete
   host tests and CI, then freeze new APK bytes/signers. PR #63, #62 and #65
   being independently green is not proof of their merged tree.
3. Cut a new readiness manifest/checker package for that convergence candidate;
   do not edit this snapshot's blocker list or expect its `--fail-on-blocked`
   result to turn green. The successor package must pass its own device-free
   mutations before requesting a phone lease.
4. Obtain the explicit operator authorization packet above.
5. Execute exact-build installed-byte preflight, then G, C, B and A in separate
   recoverable sessions. Stop on any safety or evidence failure; do not continue
   on a dirty device.
6. Have a non-author evidence-validity reviewer verify carriers and manifests
   before any product verdict. Only then may the independent acceptance verdict
   and operator release decision be requested.

## Related truth sources

- [Issue #64](https://github.com/TERRYYYC/fakexxx/issues/64)
- [Issue #66](https://github.com/TERRYYYC/fakexxx/issues/66)
- [PR #65 exact product candidate](https://github.com/TERRYYYC/fakexxx/pull/65)
- [PR #63 provider-principal routing](https://github.com/TERRYYYC/fakexxx/pull/63)
- [PR #62 acceptance harness](https://github.com/TERRYYYC/fakexxx/pull/62)
- `docs/acceptance/issue7-g2-acceptance-package.md`
- `docs/acceptance/a-plus-device-matrix.md`
- `docs/acceptance/issue7-m-co-06-host-coverage-disposition.md`
- `docs/acceptance/issue7-m-vs-01-post-v1-disposition.md`

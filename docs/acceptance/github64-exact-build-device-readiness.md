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
| Frozen manifest SHA-256 | `3129b3d9e0a733753e35b85e72ec726e5855cfe9f4395ab49da0cbf734cae43f` |
| Contract SHA-256 | `c64dd132418493ba5918d86e481382d29b3d351867f3e3d3569577abb3d6f543` |
| 10-address fixture SHA-256 | `cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852` |
| Canonical device ledger | empty `[]`, SHA-256 `37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570` |

The selected APK bytes are two fresh, clean Java 17 builds:

| App | Package | Version | Bytes | APK SHA-256 | Signer SHA-256 |
|---|---|---:|---:|---|---|
| Auto debug | `com.example.cellrebelauto` | `1` / `1.0` | 11,413,622 | `7bd07b07fde483cf1252722f2c29880c0030d47e52638761f19fa2d0dc4a3f1b` | `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41` |
| QWY bench debug | `name.caiyao.fakegps.bench` | `8` / `3.0.0` | 23,194,413 | `bb5be7db762a0e38218465e321b582eddb62c3f9110b714ac1c18076a151a161` | `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41` |

The production checker has no caller-selected manifest or inspector seam. Its
absolute CommandLineTools Python shebang uses isolated mode, so neither a
`python3` earlier on `PATH` nor user `sitecustomize` is loaded. It pins and
records the host-only executables, direct support files and user-writable
runtime trees below, rechecks them immediately before each use, and supplies a
minimal environment with no Android device transport on `PATH`. Git global and
system config, pagers, lazy fetch and replacement objects are disabled, and
legacy `info/grafts` fake-parent metadata must be absent;
repository-configured fsmonitor, filter, diff, hook or pager helpers and the
worktree-config extension are rejected before source inspection. Git is used
only to read immutable commit/tree metadata. A separate filesystem walk checks
raw tracked bytes, type and executable mode and finds extra paths without using
the index or ignore rules. Only the ten manifest-pinned Gradle/build roots may
contain generated files. APK signature inspection invokes the pinned Java
binary and pinned JAR directly, without the mutable `apksigner` shell launcher.

| Tool file or frozen tree | SHA-256 |
|---|---|
| CommandLineTools Python 3.9.6 `bin/python3` | `bdea59019a38eb6600cc9e71e984a97fedadc406448431281e7657030f54987e` |
| CommandLineTools Python 3.9 runtime tree | `9554093f9f3037f2de48bb897245a9ff54796d1c0952c1fc631d98b1fe714508` |
| Codex runtime Git 2.53.0 `bin/git` | `ee73b116cc37f44ecdaa9e3fdfbc25ce827675859f5f966ec671112fd5caf074` |
| Codex runtime Git 2.53.0 home tree | `a78b5118e8fd018ab1d7538109772cefa4098bb5afa54bd7fd10764486d08c1a` |
| build-tools 36.1.0 `aapt` | `b08d65ee8f8ee6c8a2e9d5ed6b7881873df83e60c44800b951c30d4ff80d9efe` |
| build-tools 36.1.0 `lib64/libc++.dylib` | `66499e49a1c5a9c73d2d4958f5d9f4dccec56c5eb8bba7ac4e29297ea3cf3fed` |
| build-tools 36.1.0 tree | `71cca8b37798d10aaea1f94e502a8952ef77a0644c0449d773f1b3758a00f128` |
| build-tools 36.1.0 `lib/apksigner.jar` | `71e18adf733f5e112d1f062dbe6b0c2eb439a4d7c773d083c42a703c66f56df1` |
| OpenJDK 17.0.20 `bin/java` | `77ddcbc036c6f6261d2583725018a6a45a2385d5339deea14e53cb8d91086192` |
| OpenJDK 17.0.20 home tree | `cec57e31b8945654d0d463138bd55bec881f3283a458fda5cb65f0e9263f1e36` |

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

## Source checkout Stateful Object Gate

The host gate treats the checkout as a set of independently mutable state
objects. A clean verdict must not depend on Git index flags, ignore rules or a
worktree-scoped helper becoming invisible to one Git command.

| State object | Owner / allowed state | Events that must fail closed |
|---|---|---|
| effective Git configuration | checker-pinned system/global isolation plus repository config with no external helper and no `extensions.worktreeConfig` | common/worktree config enables a filter, fsmonitor, diff, pager or hook helper; config scope becomes ambiguous |
| HEAD tree | immutable commit objects resolved by pinned Git with lazy fetch and replace objects disabled and no `info/grafts` file | HEAD/product/base/tree changes, fake-parent metadata appears, an object cannot be read, or a gitlink/unsupported tree entry appears |
| tracked filesystem | descriptor-bound raw file/symlink bytes, type and executable bit equal the HEAD tree; entry identity and directory membership remain stable across the observation | content/type/mode drift, missing path, a post-stat pathname replacement, concurrent directory membership drift, or `assume-unchanged` / `skip-worktree` attempts to hide drift |
| untracked filesystem | no extra non-directory entry outside the frozen generated-root allowlist; empty directories are inert because Git does not version them | `.git/info/exclude`, global excludes or ignore rules attempt to hide an extra entry; an allowlisted root is replaced by a symlink |
| generated build roots | only the exact manifest-pinned roots; each may be absent or a real directory, and contents may change because APKs are separately byte-pinned | caller widens the roots, a root escapes the repository, or a non-directory occupies a root |
| report + sidecar | external, non-aliasing, atomically replaced pair whose full resolved paths contain no CR or LF | either output path can inject a line-oriented record, or collides with source, Git metadata, manifest, tools, runtime trees or the other output |

The resulting invariants are:

- `INV-GIT-1`: no effective Git configuration scope can launch a helper during
  source inspection;
- `INV-GIT-2`: every tracked path has the HEAD tree's raw bytes, entry type and
  executable mode;
- `INV-GIT-3`: every non-directory filesystem entry outside Git metadata is
  tracked or beneath one frozen, real-directory generated root;
- `INV-GIT-4`: index flags and ignore metadata cannot change the verdict;
- `INV-GIT-5`: lazy object fetching and replacement-object substitution are
  disabled, and fake-parent graft metadata is absent.

The regression matrix covers normal checkout, common helper config,
worktree-scoped helper config, `assume-unchanged`, `skip-worktree`, hidden
untracked paths, executable-mode drift, generated roots, symlinked-root escape,
late drift in an already visited subtree and deep inert directory chains. The
finish line is a host `PASS` only when all five invariants hold; artifact
byte/signature checks remain separate, and device state remains `BLOCKED`.

## Immutable inspection-input Stateful Object Gate

The second state gate covers every path that is validated and later consumed.
Its finish line is: a host `PASS` is possible only when each executed inspector
and each reported APK field comes from one private, digest-verified snapshot,
and every shared source path still matches its opening seal at the final audit
barrier. This does not add an operating-system sandbox or device run.

| State object | Lifecycle owner | Allowed transition | Fail-closed event |
|---|---|---|---|
| Python bootstrap | operating system process loader | absolute isolated interpreter starts, then its on-disk binary/runtime are recorded as bootstrap evidence; they are not reopened as a child inspector | bootstrap path/runtime digest is not frozen |
| inspector source file/tree | snapshot manager | shared source → private run snapshot with contained symlinks → final shared-source seal | copy races, snapshot digest mismatch, escaping/broken symlink, source inode/metadata/digest drift, or unsupported entry |
| prepared inspector | command dispatcher | execute only its private executable, remapped support paths/environment and frozen arguments | any pre-exec snapshot recheck fails; a command still names a shared frozen path |
| APK source | artifact snapshot manager | one lexical no-symlink source descriptor → one private APK inode → unlink its pathname → verify frozen hash/size → independent aapt/apksigner descriptors → final source seal | frozen bytes/size mismatch, atomic replace, in-place change, snapshot mutation, or source identity/digest drift |
| manifest/contract/schedule/ledger input | manifest loader / input validator | one nonblocking held-descriptor read plus final shared-source seal; only the exact manifest may select paths | malformed/non-regular input, untrusted path selection, or source identity/digest drift |
| raw checkout | source validator | opening HEAD identity/raw scan → final HEAD-before/iterative descriptor-bound scan/checkout-wide seal verification/reverse descriptor close/HEAD-after barrier | tracked/untracked drift, post-stat type replacement, late drift in an already visited subtree, descriptor cleanup failure, or a same-tree HEAD switch appears in either scan |
| command evidence | audit recorder | record each direct dispatch using stable private-role tokens, spawn/return/decode outcome, classification and derived count | an unclassified/device-transport command is denied before dispatch, or child output is not strict UTF-8 |
| retained/private resources | audit cleanup owner | own each retained source/copy/reader descriptor immediately on open → close registered descriptors → remove private snapshot root → encode report | any early/late registered-descriptor close or private-root removal failure |

The checkout scan may temporarily raise only its own process soft
`RLIMIT_NOFILE` to at most 16,384 and never above the existing hard limit. It
closes the retained checkout descriptors and restores the original soft limit
before returning; either cleanup or restoration failure invalidates the audit.

The gate adds these invariants:

- `INV-SNAPSHOT-1`: validation and execution never reopen a shared inspector
  executable or dependency after trust is established;
- `INV-SNAPSHOT-2`: APK hash, size, package/version and signer all describe the
  same immutable snapshot bytes, and parsers run only after frozen hash/size
  equality;
- `INV-SNAPSHOT-3`: every manifest/inspector/APK/input shared source is
  revalidated at the final audit barrier; any drift makes the report `INVALID`;
- `INV-SNAPSHOT-4`: inspector paths in argv and relevant environment values are
  remapped into the private snapshot closure;
- `INV-SNAPSHOT-5`: `executedDeviceCommands` is derived from recorded direct
  command dispatches, not emitted as an unconditional literal;
- `INV-SNAPSHOT-6`: the raw checkout is rescanned after all manifest-driven
  inspection and `HEAD` must equal its opening value before and after that scan,
  while every visited directory and tracked regular-file descriptor remains
  held through one checkout-wide membership/identity recheck. Late
  tracked/untracked drift, generated-root replacement or a same-tree commit
  switch is invalid;
- `INV-SNAPSHOT-7`: ownership of retained source descriptors, private-copy
  descriptors and APK reader descriptors begins at open; their close failures
  and private-root removal failures are evidence failures and cannot be hidden
  before registration or by discarding a still-open descriptor from cleanup
  state. Other transient I/O close failures propagate as audit failures but are
  not claimed by the `snapshot:cleanup` finding.

The adversarial matrix includes byte-different and byte-identical atomic APK
replacement after snapshot creation, executable/tree/support-file replacement
after the private tool closure is prepared, and late input/manifest replacement
before the final barrier. It also covers a digest-invalid manifest selecting a
FIFO, late checkout drift, malformed manifest/inspector UTF-8 and a retryable
descriptor-close failure, including failure before capture returns. A
same-tree late `HEAD` switch is rejected independently of the raw bytes. A real
remapped support-file argument is consumed
from the private closure while a shared-source replacement marker remains
untouched, and a command whose executable is classified as device transport is
recorded but denied before spawn. Ordinary stable execution of both frozen
artifacts is covered separately.

Threat boundary: the private directory and unpredictable names remove normal
concurrent Gradle/agent writes from the validation/use path. This is not a
claim against a hostile same-UID process that discovers and tampers with the
private directory, nor is direct-command recording syscall tracing of child
processes. The report therefore states that the checker directly dispatched
zero device-transport commands and records that proof boundary; stronger
absence claims require an OS sandbox or syscall trace outside this package.

The shared source checkout must be quiescent for each raw-tree observation:
do not run a build, checkout, formatter or other writer against it concurrently.
The checker retains every visited directory and tracked-file binding through a
checkout-wide recheck, so ordinary drift observed during traversal fails
closed, but POSIX traversal is not an atomic filesystem snapshot. It does not
claim to defeat a same-UID writer deliberately scheduled after an individual
entry's final recheck. That stronger guarantee requires an OS/filesystem
snapshot or write-denying sandbox outside this package.

## What can run now, without a device

Use an evidence directory outside the source checkout. Before inspection, the
checker rejects report/sidecar aliases and collisions with the source tree,
manifest, linked-worktree/common Git metadata, pinned tool files or frozen
runtime trees. It also rejects CR or LF anywhere in either resolved output path
before creating output state. Both outputs are staged before replacement, and
a failed replace restores prior bytes. The audit reads Git state, APK files and
the pinned host tools only.

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

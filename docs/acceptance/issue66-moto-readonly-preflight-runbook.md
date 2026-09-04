---
feature_ids: [G2-66]
topics: [android, moto, device-preflight, evidence, services-jar]
doc_kind: runbook
created: 2026-09-03
---

# Issue #66 Moto operational-read-only preflight

This runbook prepares an exact-build evidence bundle for the only authorized device,
Moto `ZY22JHW9M4`. It does not install either codexBench APK, configure mock location or
LSPosed, reboot the phone, start an app, collect coordinates, or claim issue #66 acceptance.

## What this stage proves

A completed bundle can prove that its internally consistent ADB transcript reported one
unambiguous inventory containing only the named Motorola serial and an Android API-35 build. It
cannot independently prove that the transcript came from a genuine Moto transport because the
local ADB server, daemon peer and USB transport are not attested. Inventory selection is a topology
preflight. The exact `shell id` query is then the first serial-targeted command and must report the
Android shell as both primary UID and GID (`uid=2000(shell) gid=2000(shell)`) under the collector's
complete accepted grammar; matching boot-ID and monotonic-uptime pairs bracket every later build,
package, process and framework observation.
It captures:

- serial, manufacturer, model, device, release, API, ABI, zygote, fingerprint and boot state;
- the primary `uid=2000(shell) gid=2000(shell)` identity, SELinux state and Android user 0;
- a column-bounded raw process list and a coordinate-free Location-enabled boolean;
- installation, package dump, process and exact mock-location AppOp state for the fixed package
  set;
- each installed package's validated base APK bytes and SHA-256;
- `/system/framework/services.jar` bytes and SHA-256;
- a mode-`0500` private snapshot of the selected regular ADB executable, plus the exact snapshot
  and collector SHA-256; and
- the independently approved `sourceHead` plus collector SHA-256 supplied to the entry point.

The evidence directory is newly created with mode `0700`, outside every linked worktree and the
repository's common Git metadata. The collector resolves and opens the existing parent through
no-follow directory descriptors, compares directory identities rather than trusting spelling, and
therefore also refuses case/Unicode/path aliases and macOS firmlink views of forbidden Git trees.
It rejects unsafe owner/mode combinations and a newly created directory that inherited an extended
ACL. The collector then pins that directory inode as its working directory and refuses final
publication if the caller-visible pathname, mode, owner, ACL or inode changes. Every ADB invocation
executes `./tooling/adb` and owns one strict six-file receipt: command, start time, stdout, stderr,
exit status and end time. Binary reads use `stdout.bin`; text reads use `stdout.txt`. A deterministic
`receiptTreeSha256` binds every receipt name and byte, including fingerprint and both boot brackets.

Production collection also has a repository-pinned client gate. On the reviewed host, the approved
source is `/Users/terry/Library/Android/sdk/platform-tools/adb`, whose SHA-256 is
`9fdf861259dc807937b13afdd5f053c7fda9f3b7726933fe0e0f45130ecb8dc7` and allowlist label is
`platform-tools-37.0.0-macos-google-eqhxz8m8av`. Its recorded provenance is platform-tools 37.0.0
(`package.xml` SHA-256 `aa2581a0528e76c81e07e42667af54849a15b76e013f403977dfcf040a8a1c9b`),
a universal x86_64/arm64 Mach-O with a strict-valid Google LLC signature (Team ID
`EQHXZ8M8AV`, signing timestamp 2026-04-15). Runtime admission relies on the checked-in allowlist
bytes and exact ADB SHA-256, not on a pathname, package metadata or signature inference. The source
is read, hashed and copied from the same no-follow file descriptor into the mode-`0500` snapshot.
Source changes before or during that snapshot fail closed; each later command rechecks and executes
only the private snapshot, so replacing the caller's original pathname cannot change the run.

The manifest and summary explicitly say `readOnlySemantics=OPERATIONAL_NOT_BIT_FOR_BIT`, list the
possible ADB transport, transient query-process and device audit/accounting effects, and mark the
default local ADB server endpoint as not attested. Inherited `ADB_SERVER_SOCKET`,
`ANDROID_ADB_SERVER_ADDRESS` and `ANDROID_ADB_SERVER_PORT` overrides are refused before collection;
the approved row attests the client bytes only. It does not attest an already-running local ADB
server, its daemon peer or the USB transport, so the collector does not claim cryptographic
transport authenticity.

The collector explicitly records private root/LSPosed observations as
`NOT_COLLECTED_PRIVILEGED`. Its maximum terminal result is `COLLECTED` with
`STATIC_ANALYSIS_PENDING`. It always preserves:

```text
devicePass=false
issue66Ac7=NOT_PASSED
deviceFull=BLOCKED
durableAck=NOT_CREATED
fullClaim=NOT_CREATED
```

## Host-only verification

Run the two device-free suites before any phone window:

```bash
bash ./scripts/selftest-issue66-moto-readonly-collector.sh
bash ./scripts/selftest-issue66-services-compatibility.sh
```

They use a repository-approved SELFTEST-only fake ADB executable plus a poisoned bare `adb` name.
They test wrong-device and extra-device refusal, root-shell refusal, user mismatch, command
allowlisting, unsafe output aliases/ACLs, ADB source/snapshot races, malformed Android outputs,
unsafe package paths, missing packages, archive structure/CRC, binary receipts, hashes, redaction
and offline manifest tampering. They cannot contact a phone, and SELFTEST receipts are rejected by
the production verifier.

The zero-argument host gate runs both suites before the existing Gradle gates:

```bash
JAVA_HOME=<jdk-17> ANDROID_HOME=<android-sdk> \
  ./integration-tests/pr63-on-issue66/run-host-gate.sh
```

At startup it acquires an exclusive lock, records its owner, and atomically replaces any older
authoritative receipt with a `RUNNING`/`BLOCKED` receipt from a private same-directory temporary
file. A mode-`0400` old PASS cannot block that replacement. If owner recording or atomic RUNNING
publication fails, the owned lock deliberately remains as a fail-closed fence; after RUNNING
exists, ordinary failures may release the lock because the canonical receipt is already non-PASS.
A lock left by such an early failure or a hard process kill must be manually inspected before
removal. Only a completely successful host run may atomically publish a new PASS receipt, which
still says physical-device and FULL evidence are blocked.

The aggregate receipt validator is a second participant in that same lock protocol. It derives a
readonly sibling lock path from the canonical receipt path, atomically creates the lock, writes a
random mode-`0600` `validator-pid`/parent-pid/nonce owner record, and holds that ownership while it
opens, parses and validates the complete JSON contract. Receipt and lock operations share one
pinned no-follow parent descriptor. The receipt itself is opened no-follow/nonblocking, must be a
single regular file owned by the current host user without group/world write authority, and stays
open on one descriptor through duplicate-key rejection, exact-key/type/value validation and a
post-contract byte/inode reread. It refuses to read behind any pre-existing lock and reports
success only after verifying the same lock inode and owner token and removing its own lock. If the
receipt, parent, owner record, lock inode or cleanup result changes, it suppresses PASS and leaves
foreign lock state as a manual-inspection fence. A hard-killed validator may therefore leave a
`validator-pid=` lock; do not remove it merely because a PASS receipt exists.

This is a cooperative local lock and race-detection protocol, not a cryptographic signature. It
detects the tested runner/validator overlap, path replacement and same-inode mutation cases, but it
cannot authenticate a user-owned receipt against a malicious process running as that same host
user after the lock is released. Treat the exact-commit CI run/artifact association and independent
review as the authority; never use a copied standalone JSON receipt to authorize device work.

## Authorized read-only collection

The durable authorization source is the
[2026-09-04 issue #66 checkpoint](https://github.com/TERRYYYC/fakexxx/issues/66#issuecomment-5535947347).
It limits all later device work to Moto `ZY22JHW9M4`, records the two non-colliding APK/mock-location/
LSPosed/cleanup permissions, and lists the reboot, provider, lifecycle, adversarial and unrelated
private-state exclusions. This Task 2A run is narrower and operational-read-only; it consumes none
of those mutation permissions.

Preconditions:

1. Moto `ZY22JHW9M4` is connected and unlocked.
2. No emulator or second offline, unauthorized or online device is attached.
3. ADB is not running as root, and Android user 0 is current. The collector never requests root or
   `su`; its first serial-targeted `shell id` query necessarily runs under the already-negotiated
   adbd principal and stops the run before every other device observation unless the complete
   result has primary `uid=2000(shell) gid=2000(shell)` and only accepted supplementary/context
   fields.
4. The output path is absolute, outside every worktree/common Git directory, has a safe parent,
   contains no control separators, and does not exist. Its physical parent must be stable through
   the no-follow walk; the created directory must not inherit an extended ACL.
5. The three supported ADB server-routing environment variables named above are unset.
6. The selected production ADB bytes match the checked-in `PRODUCTION` allowlist row. For the
   reviewed host, use the exact path and digest recorded above; a same-named or newer SDK client is
   not accepted automatically.
7. An independent exact-HEAD review has published the approved 40-hex commit and 64-hex collector
   SHA-256. Copy those two values from that review checkpoint. Do not derive or substitute them at
   execution time; a locally computed pair is not independent approval.

Run only the exact collector entry point:

```bash
./scripts/collect-issue66-moto-readonly-preflight.sh \
  --reviewed-head <independently-approved-40-hex-head> \
  --reviewed-collector-sha256 <independently-approved-64-hex-collector-digest> \
  --adb /Users/terry/Library/Android/sdk/platform-tools/adb \
  --serial ZY22JHW9M4 \
  --output <new-absolute-evidence-directory>
```

Before it creates the output directory or invokes ADB, the collector stably reads its current
entry-point bytes and requires their SHA-256 plus the repository's current HEAD to match those two
external review values. It repeats that binding before every ADB receipt and before final
publication. A missing, malformed or changed binding stops with
`STOP_REVIEW_BINDING_REQUIRED`/`STOP_REVIEW_BINDING_MISMATCH` and cannot be repaired by editing the
local verifier.

Once the evidence directory and initial `STOP_RUNNING` manifest exist, any later topology,
identity, package-path, framework-read or receipt failure updates that manifest with a typed
`STOP` reason. Argument, ADB-client admission, repository-trust or output-creation failures that
occur before an evidence tree exists return a typed stop on stderr and intentionally create no
manifest. Do not retry with `su`, a generic shell, another serial or an unreviewed command.

Verify a completed bundle without invoking ADB:

```bash
./scripts/collect-issue66-moto-readonly-preflight.sh \
  --reviewed-head <the-same-independently-approved-head> \
  --reviewed-collector-sha256 <the-same-independently-approved-collector-digest> \
  --verify-receipts <absolute-evidence-directory>
```

This pins one private mode-`0700` evidence tree, reads each authenticated file from a stable inode,
then rechecks every byte and inode before success. It requires the manifest `sourceHead` and
`collectorSha256`, current repository HEAD and current stable entry-point bytes all to match the
same external review values. It also verifies the exact ordered stem/ADB-argv
graph, private ADB snapshot, device-identity and package-output semantics, boot/uptime bracket,
fixed claim ceiling, typed six-file carriers, whole-receipt-tree and binary SHA-256 bindings,
known-package terminal states and the redacted summary. Every authenticated file is opened with
`O_NOFOLLOW`, read from that same descriptor, and matched against descriptor/path state before and
after the read. Text parsers consume raw receipt bytes with grammar-specific framing: scalar
properties accept zero or one LF/CRLF terminator but reject embedded control/Unicode line
boundaries and edge whitespace; row-oriented inventory, process, installed-package-path and
AppOps outputs require their exact terminal framing. Package dumps preserve Android's additional
raw text but must contain the required exact package anchors/tokens. For Android 15 this includes
the exact missing-package result (`pm path`: exit 1 with empty stdout and stderr) and the AOSP
AppOps shapes: a single exact `MOCK_LOCATION: <mode>` row; an optional UID-only or UID-then-package
form; or exactly `No operations.` followed by `Default mode: deny`.
AppOps `time`, `rejectTime`, `running` and `duration` ordering is fixed, and duration fields must be
canonical fieldLen-0 `TimeUtils` output with a maximum second component of `2147483647` (maximum
canonical body `24855d3h14m7s999ms`). APK/JAR evidence must be a valid nonempty ZIP with unique,
safe, NUL-unambiguous member names and passing CRCs; APKs contain exactly one
`AndroidManifest.xml`, while `services.jar` contains at least one valid `classes*.dex` member.
It proves internal receipt consistency, not that an arbitrary copied directory came from a genuine
Moto transport or attested ADB server.

## Static framework compatibility

After a successful collection, run the separate host checker. Its output path must not already
exist:

```bash
./scripts/check-issue66-services-compatibility.sh \
  --services-jar <evidence-directory>/receipts/services-jar.stdout.bin \
  --dexdump <absolute-path-to-Android-SDK-dexdump> \
  --output <new-absolute-compatibility-result.json>
```

The checker snapshots `services.jar`, `dexdump`, the required-member table and the repo-approved
tool-digest allowlist; it also binds its own source hash. It analyzes and hashes the same bytes and
rejects any source path that changes during the run. The result file is reserved once and written
through the same open file descriptor, with inode checks before/after and explicit write/fsync
failure handling.

The approved `dexdump` digest list is intentionally empty in this slice. A locally installed SDK
path, file owner, metadata or native executable magic is not an authenticity anchor, so an
unapproved production tool stops as `STOP_TOOL_NOT_ATTESTED` before invocation. The checked-in fake
tool can produce only `SELFTEST_STATIC_MEMBERS_PRESENT`, never a production candidate. Only a
separate independently reviewed change that pins an exact build-tools revision and SHA-256 may
allow the checker to bind the required Android 15 classes/methods to their declaring classes and
emit `COMPATIBILITY_CANDIDATE`; even that result would not prove an LSPosed hook installed or ran.
The frozen parser baselines are the official AOSP
`android-15.0.0_r1` sources for
[PackageManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java),
[AppOpsService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/appop/AppOpsService.java), and
[TimeUtils](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/util/TimeUtils.java), plus
[dexdump](https://android.googlesource.com/platform/art/+/refs/tags/android-15.0.0_r1/dexdump/dexdump.cc).

## Next boundary

Only after this collector, its exact command surface and the resulting evidence are independently
reviewed may the live fingerprint be proposed for the separate `EVIDENCE_ONLY` admission change.
Task 2A is not AC1–AC6 runtime evidence and cannot satisfy AC7 alone. AC7 requires both the same
exact reviewed app build on an API-35 emulator **and** the authorized rooted Moto, with separate
fingerprints and evidence bundles from both environments.
Privileged LSPosed inspection remains Task 2B with its own reviewed entry point and evidence tree.
Late-bridge classification is also not observable from this public/static preflight; it belongs to
a later, independently reviewed oracle-observation stage after an exact build is admitted for
evidence collection.
Activation/cleanup reboots, global Location/provider toggles, process restarts and adversarial
mutations remain outside this stage and require their own explicit authorization.

At the time this runbook was added, no real-device command had been executed by this branch.

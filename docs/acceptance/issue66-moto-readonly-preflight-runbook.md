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

For each installed package, the collector records one initial `pm path`, then package metadata,
then a contiguous pre-APK `pm path` / APK read / post-APK `pm path` bracket. All three path queries
use the identical exact-package argv and must resolve to the same single `base.apk` path. A changed
or disappeared path stops as `STOP_PACKAGE_PATH_CHANGED` (exit 21); the post-read query must pass
before archive validation, hashing or any later service observation. `NOT_INSTALLED` retains only
its single initial path query. The offline verifier binds the three stems, their exact argv and
their equal parsed paths. This bracket detects the tested pathname changes, but it does not attest
the remote inode or exclude a change-and-restore during the APK byte read.

The evidence directory is newly created with mode `0700`, outside every linked worktree and the
repository's common Git metadata. The collector resolves and opens the existing parent through
no-follow directory descriptors, compares directory identities rather than trusting spelling, and
therefore also refuses case/Unicode/path aliases and macOS firmlink views of forbidden Git trees.
It rejects unsafe owner/mode combinations and a newly created directory that inherited an extended
ACL. An ACL/xattr inspection error fails closed; only an explicit platform
`ENOTSUP`/`EOPNOTSUPP` result is treated as an unsupported ACL interface. The collector then pins
that directory inode as its working directory and refuses final
publication if the caller-visible pathname, mode, owner, ACL or inode changes. Every ADB invocation
executes `./tooling/adb` and owns one strict six-file receipt: command, start time, stdout, stderr,
exit status and end time. Binary reads use `stdout.bin`; text reads use `stdout.txt`. A deterministic
`receiptTreeSha256` binds every receipt name and byte, including fingerprint and both boot brackets.

Every exact ADB argv runs without a shell under a dual-pipe process-group supervisor. Production
text reads have a 30-second / 4-MiB-stdout / 1-MiB-stderr budget; APK reads have
180 seconds / 256 MiB / 1 MiB; and `services.jar` has 120 seconds / 128 MiB / 1 MiB. The internal
SELFTEST lane uses fixed 2-second / 64-KiB-text / 3-MiB-APK / 2-MiB-services / 32-KiB-stderr
ceilings; neither lane exposes a CLI or environment override. At timeout or the first byte over a
limit, the complete process group is terminated with bounded waits, the capped six-file receipt is
finished, and the collector stops as `STOP_ADB_TIMEOUT`, `STOP_ADB_STDOUT_LIMIT` or
`STOP_ADB_STDERR_LIMIT` (exit 21). Their receipt exit values are respectively 124, 125 and 126.
An internal supervisor failure finishes the receipt with exit 70 and stops as
`STOP_INTERNAL_ADB_SUPERVISOR`; there is no retry or `adb kill-server` recovery.

The host ADB source and private snapshot have one fixed 64-MiB ceiling. Validation and snapshot
copy open the source nonblocking, confirm it is still a regular file, and enforce that ceiling;
runtime integrity checks and offline verification repeat the same bound. Offline verification also
applies the lane text ceiling to manifest, summary and receipt metadata before reading them, with
a separate fixed 4-KiB cap for each command carrier. Before saving a carrier, its parsed argv's
UTF-8 bytes join stdout/stderr and the retained trust/metadata inputs in one fixed 64-MiB payload
budget. This bounds retained payload bytes, not total interpreter RSS. Binary archives are streamed
instead of retained as complete byte arrays. The verifier streams directory enumeration with fixed
cardinalities before any evidence payload is read: exactly
the four expected evidence-root entries, the one `tooling/adb` entry, and no more than 512 receipt
entries. Final state checks repeat those bounded enumerations.

Live and offline archive checks apply the same lane file-size ceiling before reading or
decompressing payloads. APKs allow at most 16,384 ZIP members and `services.jar` at most 4,096;
both allow only stored/deflated members, at most 256 MiB uncompressed per member and 512 MiB in
total, with both per-member and aggregate integer compression expansion bounded by
`100 * compressed + 1 MiB` using the same fixed 1-MiB slack. Violations stop
as `STOP_APK_ARCHIVE_LIMIT` or `STOP_FRAMEWORK_ARCHIVE_LIMIT` (exit 21), separately from malformed
archive/CRC failures. Before constructing `ZipFile`, both validators perform a bounded EOCD/Zip64
and central-directory-header preflight that rejects either declared or observed member counts over
the lane cap. Live validation opens the receipt with `O_NOFOLLOW` and retains that same descriptor
from preflight through metadata, CRC and SHA-256, returning the validated digest and identity only
after descriptor/path state checks. Before publication, receipt-tree hashing pins the receipts
directory, opens every carrier by name without following links, streams it within its lane cap and
requires archive carriers to match those saved identities and digests. The offline reader likewise
bounds every descriptor read and the post-read file state.

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
/bin/bash -p ./scripts/selftest-issue66-moto-readonly-collector.sh
/bin/bash -p ./scripts/selftest-issue66-services-compatibility.sh
```

They use a repository-approved SELFTEST-only fake ADB executable plus a poisoned bare `adb` name.
They test wrong-device and extra-device refusal, root-shell refusal, user mismatch, command
allowlisting, unsafe output aliases/ACLs, ADB source/snapshot races, malformed Android outputs,
unsafe package paths, missing packages, archive structure/CRC, binary receipts, hashes, redaction
and offline manifest tampering. They cannot contact a phone, and SELFTEST receipts are rejected by
the production verifier. Both direct selftest entry points use privileged-mode system Bash, clear
`BASH_ENV`, `ENV`, `DEVELOPER_DIR`, `SDKROOT` and `TOOLCHAINS` as their first shell logic, and pin
`PATH=/usr/bin:/bin` before resolving their own directories. This also prevents macOS's fixed
`/usr/bin/python3` launcher from following a caller-selected xcrun tree; the SELFTEST fake ADB
also uses fixed privileged-mode Bash and clears the same selectors before its Python emitters. The Moto matrix later prepends only its
private fixture directory to that pinned base PATH. If no conventional local Android SDK contains `dexdump`, the services matrix reports that
optional production-tool probe as skipped rather than counting it as a passing assertion.

The zero-argument host gate runs both suites before the existing Gradle gates:

```bash
JAVA_HOME=<registered-jdk-17-profile-home> ANDROID_HOME=<android-sdk> \
  ./integration-tests/pr63-on-issue66/run-host-gate.sh
```

The Java runtime must match one checked-in profile exactly:

- macOS arm64 Eclipse Temurin `darwin-aarch64-eclipse-temurin-17.0.20.1+1`, JDK-tree SHA-256
  `f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8`;
- Linux x86_64 Eclipse Temurin `linux-x86_64-eclipse-temurin-17.0.20.1+1`, JDK-tree SHA-256
  `427182064043c17bb698c7f9c5949f755f6dd80dddaf760b6fa7413178189a97`.

The macOS profile must come from the official Adoptium aarch64 archive with SHA-256
`196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8`. A Homebrew OpenJDK tree is
not an equivalent input because its Mach-O load graph reaches mutable `/opt/homebrew/opt` content
outside the registered tree digest.

The runner copies the reviewed JDK into a private per-run staging directory and requires Java 17
for both the Gradle VM and test launcher. Its Auto, QWY and harness Gradle phases share one fresh
per-run Gradle home, checked before and after every phase and removed before PASS. The outer
`verify-a-plus.sh` aggregate has a separate private staged JDK and creates a different isolated
Gradle home for every one of its twelve manifest gates; it never carries a writable Gradle startup
surface from one gate into the next.

The Android SDK binding covers only the AGP 9.1 TCB selected under `platforms/android-35`,
`build-tools/36.0.0` and `platform-tools`, plus the safety of their ancestor directories. It
detects unsafe or changing selected inputs but is not content provenance for the entire SDK. In
Ubuntu 24 CI, a separate precondition recursively freezes the whole preinstalled SDK as root-owned
and non-writable before any repository command.

At startup it acquires an exclusive lock, records its owner, and atomically replaces any older
authoritative receipt with a `RUNNING`/`BLOCKED` receipt from a private same-directory temporary
file. A mode-`0400` old PASS cannot block that replacement. If owner recording or atomic RUNNING
publication fails, the owned lock deliberately remains as a fail-closed fence; after RUNNING
exists, ordinary failures may release the lock because the canonical receipt is already non-PASS.
A lock left by such an early failure or a hard process kill must be manually inspected before
removal. A completely successful host run atomically publishes a new PASS receipt, rechecks the
source and runner, and then explicitly releases and verifies its lock. It prints the terminal PASS
lines/JSON and returns zero only after that release succeeds. A release failure returns nonzero and
retains the ambiguity lock even if the fenced receipt bytes already say PASS. A successful receipt
still says physical-device and FULL evidence are blocked.

That receipt is schema 4 and has exactly 19 keys: `schemaVersion`, `sourceHead`, `sourceTree`,
`sourceState`, `runnerSha256`, `runId`, `jdkProfileId`, `jdkRuntimeVersion`, `jdkTreeSha256`,
`gradleAttestationAutoSha256`, `gradleAttestationQwySha256`,
`gradleAttestationHarnessSha256`, `hostIntegration`, `issue66Ac7`, `emulator`, `physicalDevice`,
`deviceFull`, `overall`, `reason`. A PASS also requires three sibling
`gradle-attestation-{auto,qwy,harness}-$runId.txt` files. Each is schema 2 with exactly 15 ordered
lines: `schemaVersion`, `runId`, `stage`, `taskPath`, `jdkHome`, `jdkProfileId`, `javaVendor`,
`javaVmVendor`, `jdkRuntimeVersion`, `jdkTreeSha256`, `jdkMajor`, `testLauncherMajor`, `testCount`,
`failureCount`, `classes`.

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

For each attestation sibling, the consumer independently opens the derived name no-follow, requires
a regular current-user mode-`0600` single-link file with bounded size and no extended ACL, verifies
its receipt SHA-256, and re-reads the same stable bytes. It then binds the proof's run ID, staged JDK
home/profile/runtime/tree, Java 17 VM and launcher, stage/task, positive test count, zero failures
and required class set back to the receipt. Missing, placeholder, RUNNING, reordered, extra or
cross-run proof content fails closed.

This is a cooperative local lock and race-detection protocol, not a cryptographic signature. It
detects the tested runner/validator overlap, path replacement and same-inode mutation cases, but it
cannot authenticate a user-owned receipt against a malicious process running as that same host
user after the lock is released. Treat the exact-commit CI run/artifact association and independent
review as the authority; never use a copied standalone JSON receipt to authorize device work.

Current author-side evidence is the complete device-free harness at 15 suites / 141 tests with zero
failures, errors or skips; the three main boundary classes at 54 + 21 + 42 = 117, plus 2
`HostEphemeralCleanupGuardTest` tests, for 119 related guard tests; the three standalone Python
runtime-security suites at 40/40; and services compatibility at 131/131. The earlier collector
result was 1718/1718; it predates the final process/environment and argv-budget repairs and is not
evidence for them. Their complete rerun belongs to the clean exact-commit gate.
All host runs fixed `ADB=/usr/bin/false`; no ADB, emulator or physical-device operation
was performed. A clean exact-commit gate and independent review remain required.

## Authorized read-only collection

The durable authorization source is the
[2026-09-04 issue #66 checkpoint](https://github.com/TERRYYYC/fakexxx/issues/66#issuecomment-5535947347).
It limits all later device work to Moto `ZY22JHW9M4`, records the two non-colliding APK/mock-location/
LSPosed/cleanup permissions, and lists the reboot, provider, lifecycle, adversarial and unrelated
private-state exclusions. This Task 2A run is narrower and operational-read-only; it consumes none
of those mutation permissions.

The two allowed future APK identities are exact and non-colliding: QWY
`name.caiyao.fakegps.codexbench`, label `千网游 · codex-bench`, launcher
`.ui.ComposeActivity`; Auto `com.example.cellrebelauto.codexbench`, label
`CellRebel Auto · codex-bench`, launcher `.ui.MainActivity`. Both exact-build fingerprint lists
remain empty, so installing either app would not change production health from
`BUILD_UNATTESTED`. Reboot/restart, global Location/provider changes, adversarial mutations and app
lifecycle changes remain outside the current authorization.

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
entry-point bytes through a descriptor chain opened no-follow from `/` through every absolute
parent. Each directory must be owned by root or the current effective user and have no group/world
write bits. Linux POSIX ACL xattrs are rejected; on Darwin, a deny-only ACL such as the standard
user-home `everyone deny delete` entry is allowed, while any ALLOW entry is rejected. The
collector requires the entry-point SHA-256 plus the repository's current HEAD to match the two
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
/bin/bash -p ./scripts/check-issue66-services-compatibility.sh \
  --services-jar <evidence-directory>/receipts/services-jar.stdout.bin \
  --dexdump <absolute-path-to-Android-SDK-dexdump> \
  --output <new-absolute-compatibility-result.json>
```

The checker snapshots `services.jar`, `dexdump`, the required-member table and the repo-approved
tool-digest allowlist; it also binds its own source hash. It analyzes and hashes the same bytes and
rejects any source path that changes during the run. The result file is reserved once and written
through the same open file descriptor, with inode checks before/after and explicit write/fsync
failure handling. Its executable entry point independently uses privileged-mode system Bash,
clears inherited Bash startup-file variables first and fixes the host command PATH before resolving
the repository-local fixtures. The pinned fake uses the same startup boundary so an exported Bash
function cannot cross into its child shell. The device-free race tests use a fixed-token state-file
handshake after analysis; the selftest performs the real input replacement itself, while the
checker never dispatches a caller-provided test executable.

Resource limits are fixed in the checker rather than caller-configurable. It opens each source
with `O_NOFOLLOW`, verifies one stable regular-file identity before and after a streaming copy, and
rejects `services.jar` above 128 MiB as `STOP_SERVICES_JAR_SIZE_LIMIT` or `dexdump` above 64 MiB as
`STOP_DEXDUMP_SIZE_LIMIT` before hashing/copying its body. The required-member and tool-approval
tables are each capped at 1 MiB and the checker source at 4 MiB. Android build-tools
`source.properties` is capped at 64 KiB, opened with `O_NOFOLLOW | O_NONBLOCK`, read through one
metadata-bound descriptor, path-checked, then reopened no-follow and re-read to require identical
bytes and identity. A regular file replaced by a symlink or FIFO therefore stops without following
or blocking. Archive preflight admits at most
4,096 entries, only stored/deflated members, at most 256 MiB expanded per member and 512 MiB total,
and a per-member plus aggregate expansion ratio no greater than
`100 * compressed + 1 MiB`. It validates EOCD/Zip64 and the complete central-directory boundary
before constructing `ZipFile`, then streams the one root `classes*.dex` member into a private
single-link file. Resource violations stop as `STOP_SERVICES_ARCHIVE_LIMIT`; malformed directory,
CRC or EOCD structure remains `STOP_INVALID_JAR`.

Approved production `dexdump` execution is supervised as a new process group with a fixed
120-second / 128-MiB-stdout / 1-MiB-stderr budget. The pinned selftest identity uses the smaller
2-second / 256-KiB / 64-KiB budget so exact-cap and cap+1 behavior is deterministic. Timeout,
stdout overflow, stderr overflow and a surviving descendant stop respectively as
`STOP_DEXDUMP_TIMEOUT`, `STOP_DEXDUMP_STDOUT_LIMIT`, `STOP_DEXDUMP_STDERR_LIMIT` and
`STOP_DEXDUMP_PROCESS_GROUP`; the group is terminated with bounded waits, and no late child write
is accepted.

The output parser reads at most 64 KiB per logical line and retains only matches from the fixed
seven-class/twenty-method required inventory. Irrelevant class and method names are never inserted
into result sets, so cap-sized high-cardinality output stays bounded by the required inventory rather
than by attacker-selected uniqueness.

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

No real-device or emulator command was executed by the PR #81 commit range or this Task 2A
collector slice. Earlier base-stack evidence is historical and does not satisfy AC7 for this
candidate.

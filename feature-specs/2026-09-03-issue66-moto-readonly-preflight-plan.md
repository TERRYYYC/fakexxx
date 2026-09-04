---
feature_ids: [G2-66]
topics: [android, system-server, exact-build-attestation, moto, device-preflight]
doc_kind: implementation-plan
created: 2026-09-03
tips_exempt:
  reason: Internal fail-closed device-evidence harness with no operator-facing action or UI.
---

# G2-66 Moto exact-build preflight plan

**Architecture cell:** `fakexxx::android-dual-app-contract`
**Map delta:** none
**Map delta why:** This strengthens the external evidence gate without changing runtime ownership.

**Goal:** reach the authorized Moto `ZY22JHW9M4` with a reviewable exact-build
candidate and a deterministic device gate, without trusting a historical fingerprint or
spending the maintenance window on an APK that can only return `BUILD_UNATTESTED`.

**Current boundary:** the two non-colliding `codexBench` APK identities are already proven, but
both exact-build fingerprint lists are empty. The current QWY APK therefore returns before
installing any system-server hook and cannot establish issue #66 `FULL`.

**Implementation status (2026-09-04):** Task 2A now has a device-free collector selftest,
operational-read-only shell-gated collector, typed stable-snapshot receipt verifier, redacted summary,
whole-receipt-tree and binary SHA-256 binding, a private executed ADB snapshot, and separate static
`services.jar` compatibility checker. Both suites are registered in the
zero-argument host gate, whose runner and aggregate receipt validator share one owner-token lock
and fail closed on stale, concurrent or cleanup-ambiguous PASS state. This is host/fake-ADB
evidence only: no command from this branch has run against the Moto, late-bridge state is not
observable in this slice, Task 2B is not implemented, and all device/#66/FULL claims remain
blocked.

## Safety contract

- Only serial `ZY22JHW9M4` is in scope. Every targeted ADB call must carry that exact serial.
- The first device phase is `operational-read-only` and never requests privilege escalation: it
  may establish an ADB transport, run inventory, then issue exact `shell id` as its first
  serial-targeted query. That identity probe necessarily uses the already-negotiated adbd
  principal; unless the complete result has primary `uid=2000(shell) gid=2000(shell)` and only the
  accepted supplementary-group/context grammar, no boot/build/package/framework query is allowed.
  After that gate it runs exact, serial-qualified queries as shell, but it never requests
  `su`, launches an app component, changes configuration/data/lifecycle state, or captures raw
  location/log output. Incidental transport, transient query-process, and device audit/accounting
  effects are outside a literal bit-for-bit no-change claim and must be disclosed in the receipt.
  It may capture identity, package/AppOps state, a coordinate-free location-enabled result,
  publicly observable LSPosed/Magisk/Vector package or process state, and exact system framework
  bytes. It may not install, clear, stop, configure, register, crash, restart, reboot, toggle a
  provider, invoke `su`, run `logcat` or `dumpsys location`, or read a private LSPosed database.
- The [2026-09-04 authorization checkpoint](https://github.com/TERRYYYC/fakexxx/issues/66#issuecomment-5535947347)
  covers installing/overwriting the two non-colliding `codexBench` debug APKs, setting mock
  location, inspecting/configuring LSPosed scope, and cleaning that test state on the named Moto.
  The shell-gated collector intentionally consumes none of those
  mutations. A later privileged LSPosed inspection on that same Moto is within the stated
  inspection authorization, but its exact `su`/LSPosed-scope private-file command surface must receive an
  independent review before execution; the broader configuration authority is not consumed by
  that inspection-only substage. It does not authorize unrelated Vector private-state reads;
  those require a separate, explicit operator authorization if later shown necessary.
- The global Location/provider switch is not a mock-location setting and is not covered by the
  existing mock-location authorization. Toggling it requires separate, explicit authorization.
- Reboot/soft reboot, diagnostic registration, process force-stop, deliberate crash, and process
  restart are currently unauthorized. Any later component that needs one of these operations must
  enumerate that operation and obtain its own additional authorization before execution.
- A historical fingerprint is context only. It cannot enter either admission list until a live
  read from the authorized device is bound to the current boot and framework hashes.
- `EVIDENCE_ONLY` may install the exact system-server observation hooks on a separately reviewed
  fingerprint, but its oracle is constructed as unattested and can never report `HEALTHY/FULL`.
  Runtime failures remain visible; the best possible terminal health is
  `EVIDENCE_ONLY_READY`.
- `ATTESTED` is a later, independent evidence change. Promotion requires exact runtime hook,
  coverage, bridge, session, endpoint, PRE/raw/POST, cleanup, and non-author review evidence.
- Activating or removing a system-server hook requires a reboot/soft reboot. Execution stops for
  additional authorization before both the activation reboot and the cleanup reboot.
- Existing production and old `.bench` apps are read-only context. Only the two `.codexbench`
  identities may be installed or cleaned under the current authorization.

## State machine and invariants

| Admission | Hook install | Authoritative health | Allowed claim |
| --- | --- | --- | --- |
| `UNLISTED` | no | absent / `BUILD_UNATTESTED` | identity and static compatibility only |
| `EVIDENCE_ONLY` | yes, exact fingerprint only | failures stay specific; maximum `EVIDENCE_ONLY_READY` | runtime compatibility evidence only |
| `ATTESTED` | yes, exact fingerprint only | may become healthy if every runtime predicate passes | exact-build #66 evidence |

- Admission-list overlap fails closed as `UNLISTED`.
- Both lists remain empty in the mechanism change; behavior on every real build stays unchanged.
- The build-attested coverage bit is set only for `ATTESTED`, never for `EVIDENCE_ONLY`.
- `EVIDENCE_ONLY_READY` remains an internal private-oracle state. Stable-complete continuity,
  tracker reconciliation, ContractV1 observations, and Auto trust reject it.
- The public/static preflight has no Binder, app or oracle observation surface and therefore cannot
  classify a late bridge. A later, independently reviewed oracle-observation stage may add a
  `STOP_LATE_BRIDGE` verdict after an exact build is admitted for evidence collection. Neither
  stage may register a diagnostic bridge, force-stop or crash a process, or trigger any process
  restart. A diagnostic-registration or restart experiment remains a separate component requiring
  additional authorization for each concrete mutation. Production registration, stable-complete,
  ACK, FULL, and Auto predicates stay unchanged.
- The required authoritative mask remains exactly `0x3ff`; ContractV1 remains byte-unchanged.
- A collector verdict is never a device `PASS`, attestation, `FULL`, or issue-closure signal.

## Task 1 — staged admission mechanism (TDD)

1. Add RED tests for unlisted, evidence-only, attested, and overlapping-list classification.
2. Separate “may install evidence hooks” from “may report build attested.” Keep both lists empty.
3. Route the installer through the three-state result. The sole package-private, zero-argument
   Binder construction path re-reads the live system-server fingerprint/API; its private
   constructor derives admission only from that freshly read fingerprint. No caller may supply
   identity or authority. Only `ATTESTED` may set the build-attestation bit.
4. Update structural guards so a direct unreviewed fingerprint, overlap, or evidence-to-attested
   laundering fails.
5. Run targeted QWY and host-integration tests plus the frozen contract gate.

## Task 2A — operational-read-only shell-gated device preflight collector (TDD)

1. Add a device-free fake-ADB selftest first. It must prove refusal for a missing target, an
   additional attached device, wrong serial/manufacturer/API, unsafe output directory, a mutating
   command, or an incomplete core receipt.
2. Add an operational-read-only, shell-gated collector with an exact command allowlist and
   six-file command receipts. Output is a new mode-0700 directory outside every linked worktree and
   common Git directory. Walk its physical parent using no-follow directory descriptors and reject
   forbidden directory identities through case/path/firmlink aliases, unsafe owner/mode, inherited
   extended ACLs and pathname/inode replacement. Pin the created directory inode for the run.
   Admit production ADB clients only through a repo-pinned `PRODUCTION` SHA-256 row; keep fake ADB
   clients in a disjoint `SELFTEST` lane whose receipts production verification refuses. Read,
   hash and copy the selected ADB from the same no-follow descriptor into the private tree. Source
   changes before or during snapshot creation fail closed. Every later call checks and executes
   only the frozen snapshot; replacing the original source pathname after snapshot creation cannot
   change the executed bytes.
   Inherited ADB server-routing overrides are refused; the approved client bytes do not attest the
   already-running local server, daemon peer or transport, which remain explicitly unattested.
   Partial runs stay marked `STOP`. The allowlist contains no `su`,
   `logcat`, `dumpsys location`, generic
   shell string, private LSPosed path, APK install/uninstall, AppOps or mock-location write,
   LSPosed scope configuration, global Location/provider toggle, diagnostic registration,
   force-stop, crash, restart, or reboot, even when a different phase may later be authorized to
   perform some of them.
   Before creating output or invoking ADB, require the exact Git HEAD and collector SHA-256 pair
   published by the independent review as external arguments. Verify that the current repository
   HEAD and a stable read of the collector entry-point bytes respectively match those two values,
   and recheck that binding before each receipt and final publication. Values calculated locally
   at execution time are not independent approval.
3. Keep bridge timing out of this public/static collector. A later oracle-observation stage must
   first add device-free timing tests for bridge-before-owner-start and bridge-after-owner-start;
   only that reviewed stage may emit `STOP_LATE_BRIDGE`. This collector records no bridge verdict,
   has no recovery or diagnostic-registration path, and may never create a durable ACK/FULL result.
4. Capture live serial/model/API/ABI/zygote/fingerprint/boot and unprivileged shell identity;
   known-package bytes and process state; exact-package mock-location AppOps; a coordinate-free
   location-enabled result; publicly observable LSPosed/Magisk/Vector package or process state;
   and exact `/system/framework/services.jar` bytes/hash. Parse raw receipts with exact scalar,
   device, process and package grammars. Scalar commands accept zero or one LF/CRLF terminator and
   reject embedded logical lines; row-oriented formats enforce their required terminal framing.
   Freeze the Android-15 missing-package rc/stdout/stderr contract and AOSP AppOps/TimeUtils forms,
   including UID overrides, field ordering, canonical units and integer bounds. Validate APK/JAR
   ZIP structure, unique safe NUL-unambiguous names, CRCs, APK manifest presence and
   `services.jar` dex presence before accepting any captured archive bytes. Do not probe root
   capability or private manager/framework storage in this substage.
5. Record root capability plus LSPosed DB/WAL/SHM and Vector private state as
   `NOT_COLLECTED_PRIVILEGED`. Absence of those observations is an explicit boundary, never a
   negative finding and never permission to fall back to `su`.
6. Compare required Android-15 oracle classes/methods against dexdump output only after the exact
   build-tools revision/SHA-256 is present in a repo-pinned, independently reviewed allowlist.
   That allowlist is empty in this slice; a local SDK path or native executable shape is not a
   trust anchor and stops as `STOP_TOOL_NOT_ATTESTED`. The checked-in fake can emit only a SELFTEST
   status. A later approved-tool static presence result is only `COMPATIBILITY_CANDIDATE`, not
   runtime hook success.
7. Emit a machine-readable manifest and a redacted summary that never contains coordinates. Both
   disclose operational-not-bit-for-bit semantics and residual transport/query/audit effects. Bind
   every receipt name and byte with one deterministic tree digest; publish the final summary before
   atomically replacing the authoritative `STOP` manifest with `COLLECTED`.
8. Publish the host-gate receipt only while an owner-token lock excludes other runners. The
   aggregate validator must acquire that same sibling lock, reject a pre-existing lock, hold its
   random ownership token through the full JSON/contract read, and return PASS only after
   inode/token-checked cleanup. Any ownership, inode or cleanup ambiguity leaves a fail-closed
   manual-inspection fence rather than trusting an otherwise valid PASS receipt.
   This local protocol covers cooperating runner/validator concurrency, accidental rewrites and
   the enumerated pathname/inode races. It is not a cryptographic seal against a malicious process
   running as the same host user, which can change user-owned files after any lock is released.
   Review authority therefore remains the externally associated CI run, exact commit/artifact and
   independent review; a standalone local JSON receipt is never sufficient authority.

## Task 2B — independently reviewed privileged inspection substage

1. Keep privileged inspection in a separate entry point and evidence directory from Task 2A. Give
   it its own exact argv allowlist, fake-command tests, six-file receipts, stop semantics, and an
   independent command-surface review before any device execution. It may not inherit or widen the
   Task 2A allowlist at runtime.
2. The existing user authorization permits inspecting and configuring LSPosed scope only on Moto
   `ZY22JHW9M4`; it is sufficient authority for a reviewed inspection of that scope, but it does
   not waive the independent review gate. This substage is inspection-only and does not exercise
   the separately authorized configuration capability.
3. Only after that review may the privileged entry point probe root capability and capture only
   the exact LSPosed DB/WAL/SHM objects needed to prove the authorized LSPosed scope. Any `su`
   invocation and every
   private path must be enumerated literally; generic root shells, device-side SQLite queries,
   private writes, fallback discovery, and arbitrary path reads remain forbidden.
   Any unrelated Vector private-state read remains a separate authorization STOP even after code
   review.
4. Privileged inspection does not authorize or imply a reboot/soft reboot, global Location or
   provider toggle, diagnostic registration, force-stop, deliberate crash, or process restart.
   Each such operation remains a separate STOP boundary requiring the additional authorization
   already described by this plan.
5. Its maximum result is `PRIVILEGED_OBSERVATION_ONLY`. It cannot turn the Task 2A collection into
   runtime hook success, `HEALTHY`, durable ACK, `FULL`, or issue #66 acceptance.

## Issue #66 AC1–AC7 evidence map

No collector or static-framework result satisfies AC1–AC6. The same reviewed app binaries must be
used in both device environments below; an emulator fingerprint is recorded separately and is not
represented as the Moto fingerprint.

| AC | Required environment | Trigger / operation | Required artifact | Claim boundary |
| --- | --- | --- | --- | --- |
| AC1 owner away→restore | Exact-build API-35 emulator, then authorized Moto | Controlled owner transition inside PRE→POST | PRE/raw/POST oracle snapshots, sequence/revision and trust result | No Moto run until this adversarial mutation is separately authorized. |
| AC2 GPS/network disable→enable | Exact-build API-35 emulator, then authorized Moto | Controlled provider transition inside PRE→POST | Provider/oracle sequence receipts and rejected trust result | A global provider toggle is not currently authorized on Moto. |
| AC3 mutation concurrent with observation | Exact-build API-35 emulator, then authorized Moto | Deterministically fenced concurrent mutation | Odd/mismatched double snapshots and rejected trust result | Moto adversarial execution requires itemized authorization. |
| AC4 restart/boot/missing hook/read-or-ACK crash | Exact-build API-35 emulator for the complete matrix; authorized Moto only for separately approved cases | Oracle restart, reboot, missing-coverage and crash seams | Per-case fail-closed health, revision/ACK and replay receipts | Reboot, process restart and deliberate crash are not currently authorized on Moto. |
| AC5 same-coordinate refresh | Exact-build API-35 emulator and authorized Moto | Ordinary same-bit coordinate refresh | Stable sequence plus successful observation without a false mutation bump | This proves only AC5 for the exact reviewed APK/build pair. |
| AC6 authoritative-only FULL | Existing host/runtime tests, then both exact-build runtime environments | Public-source and complete-authoritative-source observations | Source classification, complete coverage/health and durable reconciliation receipts | Task 2A/static presence is not AC6 evidence; public callbacks remain PARTIAL/NONE. |
| AC7 production proof | Exact-build API-35 emulator **and** authorized rooted Moto | Complete nominal path plus the authorized subset of AC1–AC6 | Exact HEAD/APK hashes, emulator bundle, Moto bundle, cleanup receipts and independent review | Both bundles are mandatory before device PASS, FULL, issue closure or merge. |

## Task 3 — deferred authorized device sequence

1. Only after an independent review publishes the approved exact Git HEAD and collector SHA-256,
   confirm that the current repository HEAD and a stable read of the collector entry-point bytes
   respectively match those external values, connect/unlock the Moto, and run Task 2A only. Values
   calculated locally at execution time are not substitutes for that approval. Stop on any
   identity, framework, or publicly observable current-state mismatch.
   Preserve `NOT_COLLECTED_PRIVILEGED` until Task 2B has passed its independent command-surface
   review.
2. If root/LSPosed DB-WAL-SHM evidence is required to prove scope, run Task 2B as its own
   inspection-only execution and preserve its separate receipts. Do not treat the existing LSPosed
   inspection/configuration authorization as permission for unrelated Vector private-state reads,
   reboot, global provider toggles, or process restart.
3. Review the live fingerprint/framework receipts. Add that exact Moto fingerprint only to
   `EVIDENCE_ONLY`, obtain independent review, rebuild and freeze both APK hashes/signers. Record a
   separate clean API-35 emulator fingerprint in the evidence-only lane; do not represent it as the
   Moto build.
4. Run the same exact reviewed commit and frozen APK hashes through the AC1–AC6 matrix on the clean
   API-35 emulator. Preserve its fingerprint, boot, APK/signer, oracle, revision/ACK and cleanup
   receipts as the emulator half of AC7. Emulator success does not substitute for Moto evidence.
5. If a late bridge needs investigation, design a separate debug-only diagnostic component. Ask
   separately for every required diagnostic registration, force-stop, deliberate crash, or process
   restart; without that itemized authorization, preserve `STOP_LATE_BRIDGE` and do not execute it.
6. Ask separately for activation and cleanup reboot/soft-reboot authorization. Also ask separately
   before any global Location/provider toggle; setting a mock-location app does not authorize that
   global mutation.
7. Only after the relevant authorization, use the supported LSPosed/Vector manager to set only
   `{system, QWY codexBench, Auto codexBench}`, perform the activation reboot, and collect runtime
   oracle evidence.
8. Promote to `ATTESTED` only in a separate reviewed delta after the emulator and authorized Moto
   evidence-only runs prove the
   exact method set, `0x3ff`-minus-attestation coverage, bridge/session/endpoint behavior, and safe
   cleanup. Then rebuild and run the nominal and only those adversarial #66 mutations that were
   individually authorized.
9. Restore only the authorized AppOps/module/scope/mock/application state. Restore global provider
   state only if its mutation was separately authorized, perform the separately authorized cleanup
   reboot, and independently verify the final baseline before any `FULL` claim.

## Handoff boundary

This plan does not merge PR #74, close #66, modify the phone, or claim Moto readiness. The first
publishable slice is only the staged admission mechanism. The device-free selftested,
operational-read-only shell-gated collector is a second slice after that mechanism is independently
reviewed; device execution is a third slice and remains blocked until the independent review has
published the approved exact Git HEAD and collector SHA-256, the current repository HEAD and a
stable read of the collector entry-point bytes respectively match those external values, and
`adb devices -l` contains the exact authorized Moto. Task 2A may then run.
Task 2B remains separately blocked on independent review of its exact privileged command surface,
even though the existing user authorization covers LSPosed inspection/configuration on the named
Moto. Neither substage authorizes activation/cleanup reboots, global provider toggles, diagnostic
registration, force-stop/crash/restart, or adversarial mutations; each required operation remains
a STOP boundary until separately authorized.

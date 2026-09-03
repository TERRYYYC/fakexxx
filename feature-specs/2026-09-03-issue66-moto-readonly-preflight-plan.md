---
feature_ids: [G2-66]
topics: [android, system-server, exact-build-attestation, moto, device-preflight]
doc_kind: implementation-plan
created: 2026-09-03
---

# G2-66 Moto exact-build preflight plan

**Goal:** reach the authorized Moto `ZY22JHW9M4` with a reviewable exact-build
candidate and a deterministic device gate, without trusting a historical fingerprint or
spending the maintenance window on an APK that can only return `BUILD_UNATTESTED`.

**Current boundary:** the two non-colliding `codexBench` APK identities are already proven, but
both exact-build fingerprint lists are empty. The current QWY APK therefore returns before
installing any system-server hook and cannot establish issue #66 `FULL`.

## Safety contract

- Only serial `ZY22JHW9M4` is in scope. Every targeted ADB call must carry that exact serial.
- The first device phase is read-only. It may capture identity, packages, AppOps, location,
  LSPosed state, and exact system framework bytes; it may not install, clear, stop, configure,
  register, crash, restart, reboot, toggle a provider, or write a private LSPosed database.
- The existing authorization covers installing/overwriting the two non-colliding `codexBench`
  debug APKs, setting mock location, inspecting/configuring LSPosed scope, and cleaning that test
  state on the named Moto. The read-only collector intentionally consumes none of those mutations.
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
- The first slice does not promise late-bridge self-recovery. If the bridge misses normal
  owner-start registration, the read-only collector records `STOP_LATE_BRIDGE` and stops. It may
  not register a diagnostic bridge, force-stop or crash a process, or trigger any process restart.
  A diagnostic-registration or restart experiment belongs to a later, independently reviewed
  component and requires additional authorization for each concrete mutation. Production
  registration, stable-complete, ACK, FULL, and Auto predicates stay unchanged.
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

## Task 2 — strict read-only device preflight collector (TDD)

1. Add a device-free fake-ADB selftest first. It must prove refusal for a missing target, an
   additional attached device, wrong serial/manufacturer/API, unsafe output directory, a mutating
   command, or an incomplete core receipt.
2. Add a pure read-only collector with an exact command allowlist and six-file command receipts.
   Output is a new mode-0700 directory outside the repository; partial runs stay marked `STOP`.
   The allowlist excludes APK install/uninstall, AppOps or mock-location writes, LSPosed scope
   configuration, global Location/provider toggles, diagnostic registration, force-stop, crash,
   restart, and reboot even when a different phase may later be authorized to perform some of them.
3. Add a device-free timing test for bridge-before-owner-start and bridge-after-owner-start. The
   late case must deterministically emit `STOP_LATE_BRIDGE`; this collector has no recovery or
   diagnostic-registration path and may never create a durable ACK/FULL result.
4. Capture live serial/model/API/ABI/zygote/fingerprint/boot/root; known-package bytes and process
   state; mock-location AppOps and location state; LSPosed/Magisk/Vector state; the LSPosed DB/WAL/
   SHM snapshot; and exact `/system/framework/services.jar` bytes/hash.
5. Compare required Android-15 oracle classes/methods against dexdump output. Static presence is
   only `COMPATIBILITY_CANDIDATE`, not runtime hook success.
6. Emit a machine-readable manifest and a redacted summary that never contains coordinates.

## Task 3 — deferred authorized device sequence

1. Connect/unlock the Moto and run Task 2 only. Stop on any identity, root, framework, or current
   state mismatch.
2. Review the live fingerprint/framework receipts. Add that exact fingerprint only to
   `EVIDENCE_ONLY`, obtain independent review, rebuild and freeze both APK hashes/signers.
3. If a late bridge needs investigation, design a separate debug-only diagnostic component. Ask
   separately for every required diagnostic registration, force-stop, deliberate crash, or process
   restart; without that itemized authorization, preserve `STOP_LATE_BRIDGE` and do not execute it.
4. Ask separately for activation and cleanup reboot/soft-reboot authorization. Also ask separately
   before any global Location/provider toggle; setting a mock-location app does not authorize that
   global mutation.
5. Only after the relevant authorization, use the supported LSPosed/Vector manager to set only
   `{system, QWY codexBench, Auto codexBench}`, perform the activation reboot, and collect runtime
   oracle evidence.
6. Promote to `ATTESTED` only in a separate reviewed delta after the evidence-only run proves the
   exact method set, `0x3ff`-minus-attestation coverage, bridge/session/endpoint behavior, and safe
   cleanup. Then rebuild and run the nominal and only those adversarial #66 mutations that were
   individually authorized.
7. Restore only the authorized AppOps/module/scope/mock/application state. Restore global provider
   state only if its mutation was separately authorized, perform the separately authorized cleanup
   reboot, and independently verify the final baseline before any `FULL` claim.

## Handoff boundary

This plan does not merge PR #74, close #66, modify the phone, or claim Moto readiness. The first
publishable slice is only the staged admission mechanism. The device-free selftested read-only
collector is a second slice after that mechanism is independently reviewed; device execution is a
third slice and remains blocked until `adb devices -l` contains the exact authorized Moto. The
read-only collector may then run, but activation/cleanup reboots, global provider toggles,
diagnostic registration, force-stop/crash/restart, and adversarial mutations remain STOP boundaries
until each required operation is separately authorized.

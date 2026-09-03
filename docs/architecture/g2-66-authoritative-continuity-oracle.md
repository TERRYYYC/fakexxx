---
feature_ids: [G2-66]
topics: [qianwangyou, continuity, system-server, lsposed, binder]
doc_kind: feature-contract
created: 2026-08-31
---

# G2-66 authoritative continuity oracle contract

Status: **frozen for pilot implementation**. This is an internal QWY protocol; the frozen Environment Control v1 wire contract is unchanged.

## Safety objective

QWY may emit `ContinuityCoverageV1.FULL` for an observation only when an authoritative source proves that the mock-location owner, GPS/network effective-enabled state, and QWY semantic environment were unchanged across the complete PRE → raw framework read → POST interval. Any inability to prove that interval returns NONE. Endpoint equality is not historical proof.

## Snapshot v1

`AuthoritativeContinuitySnapshot` contains exactly:

| Field | Rule |
|---|---|
| `protocolVersion` | must equal `1` |
| `bootId` | non-empty Linux boot ID read from `/proc/sys/kernel/random/boot_id`; unreadable means unhealthy |
| `oracleInstanceId` | random non-empty ID created for every oracle construction; changes on same-boot system-server/module restart |
| `sequence` | non-negative; even is stable, odd is mutation-in-progress |
| `ownerUid` / `ownerPackage` | exactly one allowed mock-location owner, or null/ambiguous fail-closed state |
| `gpsProviderEnabled` / `networkProviderEnabled` | effective current state, not desired command state |
| `requiredCoverageMask` / `installedCoverageMask` | FULL requires `(installed & required) == required` and no unknown required bit |
| `health` | `HEALTHY` only after supported platform/build, complete hooks, live QWY session, and intact invariants |
| `qwySemanticDigest` | canonical digest of service generation + mode + active profile/schedule + effective-coordinate semantics |
| `lastCompletedQwyMutationId` | optional correlation for the outer stable interval; lets a revision already committed with a QWY receipt be ACKed without a second bump |

Owner matching uses the build's actual QWY application ID and UID. The oracle enumerates installed packages for the target user and evaluates effective mock-location AppOps, including UID-mode precedence; package-mode rows alone are not authoritative. Multiple effective allowed packages are ambiguous and invalid even when one is QWY.

## Sequence state machine

Under one oracle lock:

1. Initial stable sequence is `0`.
2. First mutation entrant changes even `n` to odd `n+1`; nested/concurrent entrants only increment mutation depth.
3. State changes occur while the sequence is odd.
4. Each entrant reports whether its covered semantic state actually changed. The final exit refreshes authoritative endpoint state and publishes even `n+2` when any nested mutation changed it. Only when the oracle can prove the whole bracket was a no-op may it restore even `n`.
5. An exception, unknown outcome, or client death is never treated as a proved no-op: it reaches an even advanced sequence and marks health/session uncertain; it cannot silently restore healthy history.
6. Overflow, underflow, finish-without-begin, or same-instance regression is a sticky invariant failure and health is not `HEALTHY`.
7. A semantically unchanged refresh tick does not enter the state machine. A covered platform call that proves no state change may temporarily expose odd while executing but leaves the stable sequence unchanged. A real away→restore pair is two changed mutations and therefore cannot alias its starting sequence.

Required system mutation coverage bits are:

- AppOps API 35: checking-service wrapper methods, the selected Access Checking `AppOpService.setUidMode`, `setPackageMode`, `removePackage`, and `removeUid`, plus exact Access Checking package/user lifecycle removal paths that mutate policy state directly. The wrapper and runtime delegate are separate mask bits; the legacy `AppOpsCheckingServiceImpl` is not accepted as the API-35 delegate.
- Location: provider state change, effective-enabled recomputation, and one selective coordinate-history bit. The outer `LocationManagerService#setTestProviderLocation` hook carries Binder provenance only; the inner `MockLocationProvider#setProviderLocation` hook compares exact latitude/longitude bits under the platform provider locks and opens a mutation only when they differ or cannot prove valid equality.
- QWY: service generation and explicit semantic mutation session.
- Bridge/build: live registered Binder session and exact-build attestation.

The API-35 producer and consumer therefore require mask `0x3ff`; omission of the coordinate-history bit is `HOOKS_INCOMPLETE`, never a degraded healthy mode.

## Observation algorithm

For one observation while holding the existing handler owner fence:

1. Read PRE synchronously; record `windowStartElapsedRealtimeMs`.
2. Perform the raw GPS/network read and schedule snapshot.
3. Read POST synchronously.
4. Reject to NONE if either read is absent/throws, protocol differs, either sequence is odd, boot IDs or oracle-instance IDs differ, sequences differ, health is not healthy, coverage is incomplete, owner is not uniquely QWY, either provider is disabled, or semantic digests differ.
5. Reconcile POST with durable ACK state and build the returned revision snapshot in one `DurableKv.transaction`.

An oracle can only supply COMPLETE history capability through this algorithm. Public callback capability remains INCOMPLETE/NONE.

## Durable reconciliation

The revision owner durably stores `ackBootId`, `ackOracleInstanceId`, `ackSequence`, `ackEvidenceDigest`, `environmentRevision`, `coverage`, and `continuitySince` in its existing single-writer namespace.

- New stable sequence on the same boot: bump revision and write the ACK in the same transaction, then establish continuity from this PRE boundary.
- Exact normal reserved QWY mutation: only when boot ID and oracle-instance ID still match the reservation start, sequence is exactly `start+2`, and `lastCompletedQwyMutationId` exactly matches the unconsumed reservation may the tracker atomically CAS `R→R+1`, write the oracle ACK, set coverage/window, and consume the reservation. The receipt may name `R+1`, but that revision does not become durable before its matching ACK.
- Exact owner-recovery mutation: owner death, explicit current-generation registration, and replay of the reserved mutation each advance the stable sequence by two. Only the same starting boot/oracle instance at exactly `start+6` with the exact mutation ID may coalesce the recovery into `R→R+1`; the recovery fence forces the first stable observation to NONE.
- Healthy uncorrelatable reservation: if a boot/oracle identity change or unrelated interleaving makes either exact shape impossible, atomically retire the reservation at `R+2`, ACK the current healthy cursor, clear the pending ticket, record the mutation ID as quarantined, and force the first stable observation to NONE. The stale receipt still names `R+1`, so replay fails loudly and can never be accepted as the `R+2` environment. A later stable window may recover coverage normally.
- Same acknowledged stable sequence: retain revision and the existing continuity start.
- New boot or oracle instance: bump+ACK atomically, clear continuity, and return NONE for that observation. A later stable observation may establish a new window.
- Same-boot regression: bump/degrade once, retain the higher ACK, and return NONE until a new instance or sequence beyond the ACK appears.
- Invalid/unhealthy/incomplete proof: clear FULL continuity and return NONE. A changed evidence digest is durably acknowledged so health recovery cannot be mistaken for uninterrupted history.
- Crash before the transaction commits leaves neither bump nor ACK; retry is conservative. Crash after commit leaves both. No committed ACK may exist without its corresponding revision bump.

The existing owner generation remains independent and monotonic. A QWY process restart is also represented in the semantic digest/session and therefore cannot inherit FULL silently.

### Advance receipt compatibility

The frozen Auto contract requires the immediate post-advance observation's `environmentRevision` to equal `AdvanceReceiptV1.effectiveEnvironmentRevision`. A naïve observer-side “new sequence → bump” would double-count every successful advance because the handler already bumps while committing the receipt, before it converges the external schedule pointer.

Therefore `completeAndAdvance` uses this exact topology:

1. Read base revision `R`, generate a deterministic mutation correlation ID, and reserve `R+1` without changing the tracker's committed revision.
2. In the existing receipt commit, persist the receipt naming `R+1` plus a pending ticket containing from/to/version, `R`, `R+1`, mutation ID, and starting oracle identity/sequence. Do **not** expose/return the receipt yet.
3. Normal convergence and crash roll-forward both reuse that ticket to enter/finish the remote QWY semantic mutation.
4. Normal finalization requires the same starting boot/oracle instance, sequence exactly `start+2`, and the exact mutation ID. One `DurableKv.transaction` then CASes `R→R+1`, writes the oracle ACK and coverage/window, consumes the reservation, and clears the pending ticket. Only then may the receipt be returned/replayed or another fenced call be served.
5. Provider restart settles the pending ticket after the oracle has represented owner death and after the restarted owner has explicitly registered its current generation. Replaying the reserved mutation yields the only accepted recovery shape: same starting identity, sequence exactly `start+6`, and exact mutation ID. This shape also CASes `R→R+1`, but a recovery fence makes the first stable observation NONE.
6. A healthy current snapshot with a different boot/oracle identity or an unrelated sequence/ID interleaving is not evidence for the reserved receipt. Instead, one transaction advances to `R+2`, ACKs that current cursor, quarantines the stale `R+1` mutation ID, consumes the reservation, clears pending, and installs a recovery fence. Replay of the stale receipt fails loudly; the first stable window is NONE and a later stable window may recover. An unhealthy/missing oracle cannot be ACKed or quarantined and remains fail closed.
7. If no authoritative lane was available before reservation, the handler uses the existing public-source bump/PARTIAL-or-NONE path and creates no oracle reservation.

The quarantine path deliberately evolves the earlier pilot rule that left an uncorrelatable ticket pending forever. Once boot identity or an unrelated mutation has irreversibly destroyed exact correlation, indefinite pending cannot create proof; it only turns a conservative rejection into a durable service outage. Retiring at `R+2` preserves fail-closed safety because the stale `R+1` receipt is permanently quarantined, while allowing unrelated calls to recover after a fresh stable window. No future sequence or revision bump is committed speculatively. A later unrelated mutation after exact finalization is observed as a new sequence and produces a new revision, intentionally making Auto's immediate equality gate fail closed.

## QWY semantic boundary

Canonical semantic input includes:

- provider owner generation;
- active mode and profile identity;
- schedule ID, version, item, and exhausted state;
- effective-coordinate value and whether the mock projection is active.

One central writer runtime covers the semantic settings, profile repository, config publication, and handler apply/converge/cleanup entry points. Nested writers join the outer mutation instead of creating a second correlation ID. The runtime enters the remote mutation before the authoritative QWY owner changes any covered value and finishes only after durable/local commit; publication failure is uncertain rather than a proved no-op. It supplies a Binder death token, and a client death with an outstanding token invalidates session health until the restarted owner explicitly registers and reconciles its current digest. The runtime is installed only after a healthy exact snapshot and matching durable ACK prove that the authoritative lane is ready. Identical periodic coordinate publication and refresh cadence are excluded; coordinate-bit changes are selectively journaled inside the API-35 mock-provider lock domain.

## Production health gate

Exact-build admission has three fail-closed states:

| Admission | System-server hooks | Maximum wire health | Authority |
|---|---|---|---|
| `UNLISTED` | not installed | absent / `BUILD_UNATTESTED` | none |
| `EVIDENCE_ONLY` | installed for one exact fingerprint | `EVIDENCE_ONLY_READY` | none; the QWY adapter maps it to `BUILD_UNATTESTED` |
| `ATTESTED` | installed for one exact fingerprint | `HEALTHY`, only when every runtime predicate passes | eligible for FULL |

For this pilot, both exact-build fingerprint lists are intentionally empty. Every real build is
therefore `UNLISTED`: the installer returns before Binder construction and before every
system-server hook. A later evidence-only list change may expose real hook, bridge, session, and
endpoint failures without setting the build-attestation coverage bit. Even a completely ready
evidence-only runtime cannot become authoritative, produce FULL, or be accepted by Auto. Promotion
to `ATTESTED` requires a separate exact-build change backed by explicitly authorized rooted-device
evidence and independent review. Issue #66 stays open until then; this implementation records no
real-device PASS.

Evidence-only admission does not relax the production semantic-session bootstrap. If the bridge is
not registered before the normal owner-start registration, the existing retry path remains gated by
build-attested stable-complete proof and the evidence run stops at `SESSION_UNAVAILABLE`. A later
read-only collector records `STOP_LATE_BRIDGE` and stops; it never registers a session or restarts a
process. An isolated debug-only diagnostic registration or controlled QWY process restart belongs
to a separate, independently reviewed diagnostic component and requires itemized authorization for
the concrete mutation. Neither path may weaken the production baseline, durable ACK, FULL, or Auto
trust predicates.

LSPosed's scope key is `system`, although its legacy callback identity is `(packageName=android, processName=android)`. `MainHook` must take that exact branch before normal spoof/config/scheduler setup. Hooks install at the early callback, but the separate QWY registrar is not bound until Android boot phase `PHASE_THIRD_PARTY_APPS_CAN_START` (600). Before registration and baseline completion, health is not healthy. Any callback exception poisons health before returning because LSPosed otherwise logs the exception and lets the platform mutation continue.

## Acceptance matrix

| Case | Required result |
|---|---|
| owner away→restore inside PRE/POST | sequence differs; NONE; no trusted quota |
| GPS/network disable→enable inside PRE/POST | sequence differs; NONE |
| mutation concurrent with raw read | odd or mismatched snapshots; NONE |
| boot or same-boot oracle instance changes | revision bump+ACK; current observation NONE |
| same-instance sequence regression | sticky fail-closed NONE |
| hook/coverage/build/session missing | unhealthy/incomplete; NONE |
| exact build is evidence-only and otherwise complete | wire `EVIDENCE_ONLY_READY`; domain `BUILD_UNATTESTED`; NONE |
| evidence-only build carries the attestation bit | sticky `INVARIANT_FAILURE`; NONE |
| crash before revision+ACK commit | retry cannot lose the bump |
| exact normal reserved completion | same identity, exact `start+2`, exact mutation ID; commit `R+1` |
| exact owner-recovery completion | same identity, exact `start+6`, exact mutation ID; commit `R+1`; first stable window NONE |
| reboot/instance/interleaving destroys correlation | retire at `R+2`, ACK current cursor, quarantine stale `R+1`; replay fails loudly; first stable window NONE |
| Binder/read failure | NONE |
| same-coordinate refresh ticks | sequence unchanged |
| public callbacks only | PARTIAL/NONE, never FULL |
| complete controlled fake source | FULL only after every predicate and durable reconciliation pass |

## Deferred acceptance

**Issue #66 AC7: NOT PASSED — quarantined/deferred.** No real phone is touched by this change, and no real-device PASS is claimed. Framework-positive emulator evidence exists, but live exact-build rooted-device evidence, evidence-only runtime validation, separate attestation promotion, and the final adversarial matrix remain blockers before production health, issue closure, or any real-device PASS claim.

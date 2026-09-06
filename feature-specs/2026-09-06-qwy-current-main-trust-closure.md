---
feature_ids: [66, 79, 83, 90]
topics: [qwy, continuity, authoritative-oracle, profile-readback, audit-storage, evidence]
doc_kind: implementation_plan
created: 2026-09-06
---

# QWY current-main trust closure implementation plan

**Feature:** #66 / #79 / #83 / #90 — QWY authoritative continuity, ordered profile readback, and durable evidence.
**Goal:** On current main, QWY can mint `FULL` only from a live independent oracle window, return an ordered provider-owned profile projection, and retain durable observation evidence without silently weakening retention or crash semantics.
**Acceptance Criteria:** #66 AC1–7; #79 ordered profile-1..10 `discover()` readback; #83 append identity/durability, no TTL pruning, bounded caller-driven admission backed by measurement; #90 exact-source evidence remains fail-closed.
**Architecture cell:** `fakexxx::android-dual-app-contract`
**Map delta:** update required
**Map delta why:** QWY owns both its private system-server oracle producer/installer and its read-only Binder consumer; Vector supplies independent framework/readback validation only.
**Architecture:** The authoritative oracle remains outside QWY’s authority. QWY reads PRE and POST snapshots around the complete observed projection (tracker, effective environment, and schedule) and only projects `FULL` for a valid stable window. QWY persists its local acknowledgement/revision and the observation audit together; that record is a replay watermark, never a source of continuity truth.
**Tech Stack:** Kotlin/JUnit, existing `DurableKv` transaction seam, Android Binder private bridge.
**前端验证:** No — Binder/provider and persistence behavior only.

---

## Finish line and exclusions

The terminal system has one trusted `FULL` source: QWY's own system-server producer, consumed through a strict read-only QWY bridge. Missing producer, malformed wire data, callback/Binder failure, boot/instance change, odd/advanced/regressed sequence, incomplete coverage, unhealthy source, owner mismatch, provider disablement, or semantic-digest mismatch all produce `NONE`; QWY-local state never upgrades them.

This plan does **not** modify CellRebel Auto database/migrations/service, QWY’s legacy profile database schema (#46), Gradle/application IDs, Vector’s system-server hook, release documentation, or real devices. #98’s fail-closed removal is included unchanged as the first successor commit. #90 is an external dependency: #100@`c3f561b` is still open, so its host-only checks are evidence for that PR rather than current-main capability.

## Frozen v1 producer/consumer boundary

QWY owns the consumer schema, bridge, and its in-module system-server producer/installer. Vector/#71 owns independent framework validation only. The candidate is extracted from draft #68@`8c8bd250513a6c8284101152eeec99d8d26f46b3`:

- AIDL: `apps/qianwangyou/app/src/main/aidl/name/caiyao/fakegps/oracle/IAuthoritativeContinuityOracle.aidl` and `IContinuityOracleRegistrar.aidl`;
- strict v1 Bundle schema: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/oracle/OracleBundleCodec.kt`;
- QWY registration gate: `OracleBridgeService.kt`, accepting only Android system UID 1000;
- domain contract: `integration/v1/AuthoritativeContinuityOracle.kt`.

The producer must register through that UID-1000 Binder only, send the exact v1 key set and types, and bind its lifecycle to the registered Binder. Missing, unknown, extra, wrong-typed, or wrong-version fields; a dead/replaced Binder; a non-system registrant; and decode failure are source absence, never cached `FULL`. Rebind requires a fresh PRE/POST window; QWY keeps no source snapshot across the death boundary.

## State census and ownership

| Object | Sole lifecycle owner | Events | Forbidden bypass |
|---|---|---|---|
| System-server oracle journal | QWY Xposed producer/installer | begin/finish covered mutation, owner/provider transition, boot/restart, QWY session death | consumer process may not write state or synthesize a snapshot |
| QWY oracle bridge client | QWY process | UID-1000 registration, Binder death/rebind, strict decode | app-local tracker may not promote `FULL` or cache a dead producer |
| QWY local continuity acknowledgement | `ContinuityTracker` under `DurableKv.transaction` | valid window ACK, authoritative mutation ACK, restart/replay | direct writes to revision namespace |
| Observation audit sequence/event | `IntegrationAuditStore` | durable append, crash/reopen, authorized observe admission | TTL/pruning, unbacked evidence reply |
| Profile projection | `QwyEnvironment` profile reader | read-only discover snapshot, profile change | caller-provided ordering or a duplicate cache |

### #66 state × event table

| Source / local state | Event | Result |
|---|---|---|
| no source / malformed / unhealthy | observe | `NONE`, audit still backs returned observation |
| stable complete PRE | read tracker + effective environment + schedule; identical stable complete POST | `FULL`; acknowledge exact `(boot, instance, sequence, semanticDigest)` and append audit in one durable observation commit |
| stable complete PRE | POST sequence differs or is odd | `NONE`; no trusted watermark |
| any | boot/instance change, regression, Binder failure, session death | `NONE`; invalidate local claim state |
| valid source | QWY semantic mutation starts/finishes changed | system producer changes stable sequence; next window must see the new sequence |
| valid source | proven no-op heartbeat | stable sequence unchanged |
| any | QWY restart / ACK crash | replay can re-ack/bump conservatively; never reuse an unproven `FULL` |

### Invariants

- **INV-66-1:** Only `classifyAuthoritativeWindow(PRE, POST) == VALID` may project `FULL`; executable JVM matrix covers every other verdict.
- **INV-66-2:** PRE/POST must share boot ID, oracle instance ID, exact even sequence, complete required coverage, healthy state, QWY owner UID/package, enabled GPS/network, and QWY semantic digest.
- **INV-66-3:** QWY’s durable watermark is an acknowledgement of a source snapshot, not evidence; app-local `apply`, callback, or refresh cannot create it.
- **INV-66-4:** The acknowledgement/revision/audit (and apply receipt where applicable) commit atomically; no reply exposes an unbacked audit ref.
- **INV-66-5:** A crash, rebind, sequence regression, or incomplete coverage fails closed and may repeat a revision bump but cannot lose a detected mutation.
- **INV-66-6:** An observation commit is exactly `(bootId, oracleInstanceId, sequence, semanticDigest, environmentRevision, evidenceSeq, evidenceDigest)`. Retry of the same committed cursor returns no contradictory revision; a new valid cursor is acknowledged exactly once; any failure before commit returns no observation and no evidence ref.
- **INV-66-7:** Each semantic writer is bracketed by producer begin/finish or its coverage bit is absent and all windows are `NONE`. Inventory: `ProfileRepository.save/delete/import` and ConfigPrefsSync publication; `QwyEnvironmentController.applyEnvironment/cleanup`; `EnvironmentControlHandler.restartScheduleForOperator/completeAndAdvance`; provider start/recovery and any profile/config publication callback.
- **INV-79-1:** `discover().profileRefs` is a read-only `profileRefsSnapshot()` projection from the existing `temp.id ASC` owner, formatted `profile-<id>`; it is not a caller list or second persistent schedule.
- **INV-79-2:** A profile read error/missing DB/invalid or duplicate ID fails honestly (empty unavailable projection, never guessed). Profile and schedule are separate legacy stores, so QWY makes no atomic co-snapshot claim; any semantic mutation racing a trusted observation invalidates the oracle window.
- **INV-83-1:** Each returned `qwy:audit:<seq>` resolves durably to exactly one append-only event after crash/reopen; no automatic expiry/pruning.
- **INV-83-2:** `resolve(seq)` requires a contiguous exact row and reports store corruption rather than silently skipping a missing sequence.
- **INV-83-3:** Observation admission is keyed by authorized caller + lease + monotonic window and rejects excess requests with the existing typed `CAPABILITY_UNAVAILABLE` path before an audit append; same-window retry is non-amplifying. The numeric limit and backend choice follow the measured production-storage curve, not a guessed timeout.

## Test matrix before implementation

| Requirement | RED/Green test |
|---|---|
| away→restore / provider disable→enable | stable endpoint but changed oracle sequence is `MUTATING_OR_CHANGED`, projection is `NONE` |
| concurrent mutation | odd or different PRE/POST sequence is `NONE` |
| boot/restart/read failure | different boot/instance, regression, null/malformed source all fail closed |
| bridge identity/lifecycle | wrong UID, unknown/extra/wrong-typed/wrong-version field, Binder death/rebind all become absent source; no cached FULL |
| ordinary refresh | equal valid sequence/digest remains valid |
| local self-certification | current #98 guard stays green; no `markContinuityEstablished()` apply path |
| ACK crash/replay | inject every observation-commit write; reopen has no mixed FULL/audit state and current #96 advance boundary remains consistent |
| semantic writers | static source-map guard lists each writer; mutation exception/death/late callback/restart leaves coverage absent or NONE |
| ordered profiles | IDs 1..10 return exact deterministic `profile-<id>` order with no DB write; missing/corrupt/duplicate input fails honestly |
| audit scale | baseline bytes/latency at increasing audit sizes on `FileDurableKv`; crash/reopen exact resolver plus concurrent over-limit/retry tests precede backend selection |
| #90 | external #100 exact-head gate tracks all issue AC (resolver, source metadata/mirror divergence, UI and negative-search/runbook); it is not a current-main completion claim |

## Implementation order

1. Cherry-pick #98 (`019a02f`) unchanged; preserve its regression.
2. Port only the pure #68 oracle domain classifier, AIDL, strict QWY Binder decoding, and UID-1000 registration guard needed by current main; do not port Auto or stale lifecycle changes.
3. Add failing source-schema/death tests and observation-window tests for every #66 failure mode, then wire an explicit atomic observation commit and replay watermark.
4. Add a static writer-map guard plus QWY semantic registration/mutation seams and adapt the QWY producer/installer to the frozen bridge schema; source absence remains `NONE`.
5. Add #79 `profileRefsSnapshot()` and its provider regression without changing legacy profile storage.
6. Add `resolve(seq)`, production-backing crash tests, current-growth measurement, and bounded admission; select/migrate an append-oriented backend only from those measurements.
7. Run QWY JVM suite and release assembly, quality gate, and a non-author exact-HEAD formal review. Keep #66 open until separately authorized exact-build emulator/rooted-device evidence.

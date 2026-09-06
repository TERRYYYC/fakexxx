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
**Map delta why:** QWY gains an explicit read-only consumer edge to the system-server oracle; Vector owns the producer/hook, QWY owns only the Binder bridge and claim projection.
**Architecture:** The authoritative oracle remains outside QWY’s authority. QWY reads PRE and POST snapshots around its existing effective-environment read and only projects `FULL` for a valid stable window. QWY persists its local acknowledgement/revision atomically with its existing receipt/audit transaction, but that record is a replay watermark, never a source of continuity truth.
**Tech Stack:** Kotlin/JUnit, existing `DurableKv` transaction seam, Android Binder private bridge.
**前端验证:** No — Binder/provider and persistence behavior only.

---

## Finish line and exclusions

The terminal system has one trusted `FULL` source: a system-server producer tracked by the Vector/#71 owner, consumed through a strict read-only QWY bridge. Missing producer, malformed wire data, callback/Binder failure, boot/instance change, odd/advanced/regressed sequence, incomplete coverage, unhealthy source, owner mismatch, provider disablement, or semantic-digest mismatch all produce `NONE`; QWY-local state never upgrades them.

This plan does **not** modify CellRebel Auto database/migrations/service, QWY’s legacy profile database schema (#46), Gradle/application IDs, Vector’s system-server hook, release documentation, or real devices. #98’s fail-closed removal is included unchanged as the first successor commit.

## State census and ownership

| Object | Sole lifecycle owner | Events | Forbidden bypass |
|---|---|---|---|
| System-server oracle journal | Vector/#71 producer | begin/finish covered mutation, owner/provider transition, boot/restart, QWY session death | QWY may not write state or synthesize a snapshot |
| QWY oracle bridge client | QWY process | register/unregister, Binder death, strict decode | app-local tracker may not promote `FULL` |
| QWY local continuity acknowledgement | `ContinuityTracker` under `DurableKv.transaction` | valid window ACK, authoritative mutation ACK, restart/replay | direct writes to revision namespace |
| Observation audit sequence/event | `IntegrationAuditStore` | durable append, crash/reopen, authorized observe admission | TTL/pruning, unbacked evidence reply |
| Profile projection | `QwyEnvironment` schedule/profile reader | discover snapshot, schedule/profile change | caller-provided ordering or a duplicate cache |

### #66 state × event table

| Source / local state | Event | Result |
|---|---|---|
| no source / malformed / unhealthy | observe | `NONE`, audit still backs returned observation |
| stable complete PRE | raw effective read; identical stable complete POST | `FULL`; acknowledge exact `(boot, instance, sequence)` in same durable transaction as audit/receipt |
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
- **INV-79-1:** `discover().profileRefs` is an ordered projection of QWY-owned profile IDs, not a caller list or a second persistent schedule.
- **INV-83-1:** Each returned `qwy:audit:<seq>` resolves durably to exactly one append-only event after crash/reopen; no automatic expiry/pruning.
- **INV-83-2:** Observation admission has an explicit bounded rate/lease rule before storage growth is made caller-amplifiable; benchmark measures the current `FileDurableKv` curve before selecting the backing change.

## Test matrix before implementation

| Requirement | RED/Green test |
|---|---|
| away→restore / provider disable→enable | stable endpoint but changed oracle sequence is `MUTATING_OR_CHANGED`, projection is `NONE` |
| concurrent mutation | odd or different PRE/POST sequence is `NONE` |
| boot/restart/read failure | different boot/instance, regression, null/malformed source all fail closed |
| ordinary refresh | equal valid sequence/digest remains valid |
| local self-certification | current #98 guard stays green; no `markContinuityEstablished()` apply path |
| ACK crash/replay | injected durable failure leaves no FULL watermark and re-open is conservative |
| ordered profiles | nonempty QWY profile fixture returns deterministic `profile-<id>` order; no DB write |
| audit scale | baseline bytes/latency at increasing event counts plus crash injection; selected admission/backing keeps seq+event atomic |
| #90 | existing #100 exact-head script gates remain host-only and fail closed |

## Implementation order

1. Cherry-pick #98 (`019a02f`) unchanged; preserve its regression.
2. Port only the pure #68 oracle domain classifier and strict QWY Binder decoding needed by current main; do not port Auto or stale lifecycle changes.
3. Add failing observation-window tests for every #66 failure mode, then wire QWY observer and tracker acknowledgement atomically.
4. Add QWY semantic registration/mutation seams only after the Vector/#71 producer confirms the shared bridge schema; source absence remains `NONE`.
5. Add #79 read-only ordered profile projection and its provider regression without changing legacy profile storage.
6. Measure #83 current audit growth, add crash tests, then implement the smallest append/admission design supported by those measurements.
7. Run QWY JVM suite and release assembly, quality gate, and a non-author exact-HEAD formal review. Keep #66 open until separately authorized exact-build emulator/rooted-device evidence.

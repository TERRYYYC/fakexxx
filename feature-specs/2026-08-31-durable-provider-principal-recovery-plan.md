---
feature_ids: []
topics:
  - android-automation
  - a-plus
  - provider-principal
  - crash-recovery
  - room-migration
doc_kind: implementation_plan
created: 2026-08-31
status: implemented_pending_independent_review
github_pr: 63
---

# Durable provider principal recovery implementation plan

> For the implementer: use `debugging` for root-cause/failure-mode evidence, `tdd` for every behavior change, and `quality-gate` only after GREEN. This plan targets product candidate `5002e0e005324c32ca3d36d10510180d1fafbf81`; do not cherry-pick PR #63.

## Goal and finish line

Implement canonical spec §4.2/§6.1 so a plan freezes exactly one provider applicationId and every later attempt/recovery operation uses that identity. A build switch or restored DB must not redirect apply/release to the current build target. Legacy, unknown, or contradictory identity fails closed before provider I/O.

Finish means:

- principal switch recovery applies/releases against the original frozen identity;
- legacy/null/unknown/mismatch makes zero provider calls and leaves operator-visible recovery state;
- executor, trust gate, run status, Binder probe and evidence path are projections of that identity;
- `AutomationService` has no reusable default executor before reading the plan;
- migration preserves legacy rows without guessing a provider;
- selected provider client has no sibling fallback and composition rejects target/trust mismatch;
- targeted/full gates pass from a clean local commit, followed by independent review arranged by the owning session.

## Scope and explicit non-goals

In scope: CellRebel Auto plan/attempt durability, Room migration, production composition/service lifecycle, provider routing/status/probe, recovery guards, and regression tests.

Not in scope: frozen `environment-control-v1` wire changes, Qianwangyou behavior changes, a second principal abstraction alongside the existing unified `ProviderPrincipal`, GitHub/PR writes, pushing/merging, device/emulator/adb/APK installation, or claiming independent approval.

Architecture cell: `fakexxx::android-dual-app-contract`. Map delta: none; this change strengthens Auto's existing ownership boundary without moving the cross-app wire or adding a new architecture node.

## Durable ownership and failure-mode sweep

| Object / entry point | Durable owner today | Principal rule | Failure action before provider I/O |
|---|---|---|---|
| `LocationPlan` / import | Auto `PlanRepository` | Snapshot must freeze one known provider applicationId at import/start ownership boundary. | Missing/unknown plan identity blocks start; never substitute `BuildConfig` at recovery time. |
| `TestAttempt` / creation | Auto `AutomationEngine` | Attempt copies the plan identity; it never samples the current build independently. | Missing/unknown/mismatch against plan blocks attempt creation/recovery. |
| `APPLY_PENDING` recovery | Attempt owner + deterministic apply key | Resolve and validate attempt identity before `RecoveryCoordinator.reconcile()`. | Typed `RECOVERY_REQUIRED` / paused; zero reconcile/apply calls. |
| Lease-bearing recovery (`ENV_APPLIED` … `RELEASE_PENDING`) | Attempt owner + durable lease | Resolve identity before evidence, observation, advance or release. | Typed `RECOVERY_REQUIRED` / paused; zero external cleanup calls when identity cannot be proven. |
| `RELEASED` / `ADVANCE_*` readback | Attempt owner + exact receipt proof | Receipt is consumed only after attempt identity validation; exact receipt stays subordinate proof, not a routing oracle. | Keep existing read-only fail-closed semantics; zero fallback provider calls. |
| Operation receipt | Attempt-keyed append-only proof | Copy the exact plan/attempt identity. Same key+digest under a different principal is a conflict, never an idempotent hit. | Stop on missing/unknown/mismatched principal; zero replay/RPC. |
| Release receipt | Attempt/lease-keyed append-only proof | Copy the exact plan/attempt identity. It proves cleanup only for that principal. | Stop on missing/unknown/mismatched principal; never repair by contacting a sibling. |
| Recovery checkpoint | Attempt-id keyed mutable owner checkpoint | Copy the exact plan/attempt identity and validate it before consuming the checkpoint. | Stop on orphan/missing/unknown/mismatched principal. |
| `AutomationService.onServiceConnected()` | Process lifecycle only | May expose accessibility readiness, but owns no provider principal and performs no provider bind. | Bind count remains zero until a concrete plan is read and validated. |
| Per-run executor | Ephemeral projection | Construct/bind from frozen plan identity; target is observable and must match trust principal. | Unknown/mismatch throws or returns typed stop before Binder dispatch. |
| Trust/evidence/status/probe | Projection of selected durable identity | Same applicationId flows to signer lookup, UI active status, Binder component and diagnostic client. | No current-build or sibling fallback while recovering a durable run. |

### Schema-boundary decision (independently cross-checked)

The complete minimum boundary is DB v7 with five nullable `TEXT` columns: `LocationPlan P → TestAttempt P → OperationReceipt P / RecoveryCheckpoint P / ReleaseReceipt P`. Every hop stores the exact same known applicationId. Every insert, read, idempotent replay, mint, advance and release validates the full chain before provider I/O. A same key+digest under another P is a principal conflict, not an idempotent match. Release lease identity is scoped by `(P, leaseId)` while the idempotency key remains global; a same key under another P conflicts.

The migration adds nullable columns with no SQL default/backfill. Existing rows remain SQL `NULL`; the implementation must not infer P from current build, installed packages, pairing rows, lease IDs or receipt contents. A legacy row with an outstanding lease pauses for manual recovery because neither bench nor production can be safely guessed.

`MIGRATION_5_6` remains a no-op. The new schema is exclusively `MIGRATION_6_7`; all migration ladders and exported `7.json` advance through it. Recovery checkpoint replacement becomes same-P guarded CAS + exact readback: a null-P or different-P checkpoint can never be overwritten into apparent validity. Durable observation/completion records need not duplicate P only because every decision path must join the attempt and operation receipt and prove their P equality first.

## State transition / ownership table

| Current durable state | Event | Required identity evidence | Next state / effect |
|---|---|---|---|
| no plan | valid import | selected applicationId is in the unified `ProviderPrincipal` known set | plan snapshot created with frozen identity |
| no plan | import with unknown identity | none | reject atomically; no plan row |
| migrated legacy plan | start/recover | identity is `NULL` | explicit stopped/paused state; zero bind/provider calls |
| frozen plan, no attempt | start under a build whose default changed | plan identity is known | bind plan identity, not current build default |
| frozen plan | create attempt | plan identity known | attempt row atomically copies exact identity before external work |
| attempt `APPLY_PENDING` | restart on another build | plan/attempt/executor identities are known and equal | replay same apply key to original provider |
| attempt lease-bearing state | restart on another build | plan/attempt/executor identities are known and equal | observe/release/advance only through original provider |
| attempt any recoverable state | attempt identity null/unknown | no sufficient routing proof | mark typed recovery-required / pause; zero provider calls |
| attempt any recoverable state | attempt identity differs from plan | contradictory owners | mark typed recovery-required / pause; zero provider calls |
| attempt any recoverable state | executor target differs from frozen identity | contradictory composition | fail before trust lookup/Binder/provider call |
| receipt/checkpoint replay | stored principal missing/unknown/differs from attempt | incomplete or cross-principal provenance | typed recovery-required; zero RPC/mint/advance and no receipt mutation |
| same idempotency key + digest | stored principal differs from request principal | cross-principal replay attempt | conflict; never reuse the old outcome and never dispatch the new request |
| selected provider bind fails | start/recover | identity remains known | typed provider-unavailable flow; never try sibling |
| run terminal/cancel/service destroy | cleanup | same per-run executor instance | unbind it; no executor retained for a later plan |

### Executor ownership and stale-callback fence

Production composition accepts one identity-scoped executor object rather than an independently supplied string plus a generic executor. `AutomationService` reads/validates the plan, then acquires that exact P from a registry. The registry reference-counts acquisitions, closes in `finally`, and `onDestroy()` invokes `unbindAll()`. A Binder callback is admitted only if its exact `ComponentName` matches the scoped target and its registry generation is still live, so a late callback from a closed P cannot populate a newer entry.

The selected-only diagnostic handshake accepts one target and makes one bind attempt. Its Binder callback also verifies the exact component before publishing the remote.

## Invariants and kill tests

| ID | Invariant | Test that kills a bad implementation |
|---|---|---|
| INV-DP-01 | A plan freezes one known applicationId when created. | Import with explicit identity persists exact value; unknown import inserts no row. |
| INV-DP-02 | A new attempt copies its plan identity before external work. | Current build target differs from plan; inserted attempt equals plan, not build default. |
| INV-DP-03 | Recovery routing comes from the durable owner. | Create an original-production attempt, simulate current bench, recover apply and release; recording executor sees production only. |
| INV-DP-04 | Null/unknown/mismatched owner never triggers provider I/O. | Recording executor counts every journey method; total remains zero and owner becomes/stays recovery-required. |
| INV-DP-05 | All runtime legs share one principal. | Inject target-aware executor with a different target into `productionBackend`; composition rejects before trust/provider calls. |
| INV-DP-06 | Service has no pre-plan global principal. | Invoke real `onServiceConnected`; provider bind attempts remain zero. A validated plan later creates one target-specific executor. |
| INV-DP-07 | Diagnostic client is selected-only. | Selected bench bind fails while production would succeed; result lists bench only and production bind count remains zero. |
| INV-DP-08 | Run status never substitutes current build during recovery. | Active durable production attempt under debug build is evaluated against production trust entry. Null/unknown attempt reports inactive. |
| INV-DP-09 | Migration never guesses legacy identity. | Create committed v6 rows, migrate, assert both routing owners are `NULL` and all other data survives. |
| INV-DP-10 | No second principal truth source is introduced. | Source scan/compile uses the existing `ProviderPrincipal`/`ProviderPrincipalBuild` selector; no legacy selector or duplicate build-source truth is introduced. |
| INV-DP-11 | Receipts/checkpoints are principal-bound. | Mutate only receipt/checkpoint P, keeping key/digest/lease equal; recovery returns conflict with 0 RPC/0 mint/0 advance. |
| INV-DP-12 | Closed registry generations reject stale Binder callbacks. | Close P, acquire it again, then deliver the old callback; new entry remains unbound and receives no remote. |
| INV-DP-13 | Production engine construction cannot enter the nullable test seam. | Call `productionEngine` with a generic/unscoped coordinator; construction fails before an engine exists. The production clock oracle uses a fully P-bound fixture. |
| INV-DP-14 | Proof-log equality cannot override a null/foreign Room owner. | Seed a real Room plan/attempt with null or foreign P and empty/same-P proofs; every coordinator consumer remains inert and writes no receipt/checkpoint. |
| INV-DP-15 | A newly inserted attempt is read back from Room before the first journey call. | An `AFTER INSERT` trigger changes the stored attempt P; discover, preflight and apply counters all stay zero and the stored attempt becomes typed `RECOVERY_REQUIRED`. |
| INV-DP-16 | Binder readiness timeout is a terminal acquisition state. | Accept bind, let readiness time out, then deliver the exact callback before hypothetical engine dispatch; the old executor remains unbound and every journey counter stays zero. |
| INV-DP-17 | Production release/advance identities are coordinator-derived. | With a fully valid Room owner/proof chain, pass a noncanonical release key or advance apply key; release/advance/RPC/mint/receipt/checkpoint counters all remain zero. |

## Implementation tasks (TDD checkpoints)

### Task 1: Freeze the behavior with RED tests

Files:

- Add `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/DurableProviderPrincipalRecoveryRedTest.kt`
- Update `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/ProviderBindLifecycleTest.kt`
- Add/update integration routing test for `EnvironmentControlClient`
- Add composition mismatch oracle near `ProductionEvidenceSourceOracleTest`

Steps:

1. Write selected-only, mismatch, service-connect-zero-bind tests that compile against the intended narrow API.
2. Write principal-switch apply/release and unknown-zero-call tests against the durable recovery boundary.
3. Run only those test classes with `./gradlew --no-daemon :app:testDebugUnitTest --tests '<class>'` from `apps/cellrebel-auto`.
4. Record the expected assertion/compile failures. Do not change production code until RED is demonstrated.

### Task 2: Implement the single selected-principal projection

Files:

- Modify `ProviderPrincipal.kt`, `BinderExternalApplyExecutor.kt`, `APlusComposition.kt`
- Modify `EnvironmentControlClient.kt` and debug probe callers
- Modify `MainViewModel.kt` status projection

Steps:

1. Add known-id validation to the existing unified `ProviderPrincipal` only.
2. Expose executor target identity; reject unknown or target/trust mismatch at composition.
3. Make client/probes connect only the selected identity.
4. Make active-run status take the durable identity.
5. Run Task 1 routing tests to GREEN.

### Task 3: Add durable owner schema and migration

Files:

- Modify `Entities.kt`, `PlanRepository.kt`, `AppDatabase.kt`, `Migrations.kt`
- Add `Migration6to7Test.kt`
- Add exported Room schema `7.json`
- Update existing migration ladder tests for v7

Steps:

1. Add nullable `providerApplicationId` to all five approved owner/proof tables; production creation writes a known value explicitly.
2. Add a real 6→7 migration with no SQL default/backfill.
3. Test a committed v6 fixture: all five legacy values remain `NULL`, unrelated rows/receipts survive, Room validates v7.
4. Test new plan import and attempt creation equality.
5. Run migration and repository targets to GREEN.

### Task 4: Recompose service and guard every recovery entry

Files:

- Modify `AutomationService.kt`, `AutomationEngine.kt`, and if needed `AutomationEngineFactory.kt` / `RecoveryCoordinator.kt`
- Add/update recovery matrices

Steps:

1. Read and validate plan identity before constructing/binding any executor.
2. Acquire the identity-scoped executor registry entry; reference-count and close it in every terminal/cancellation path; unbind all on service destruction and fence stale callbacks by exact component/generation.
3. Resolve/validate attempt identity before every recovery branch can call coordinator/evidence/provider.
4. Persist typed mismatch/unknown state without overwriting immutable receipts.
5. Copy exact plan identity into a fresh attempt before discover/preflight/apply.
6. Run principal-switch apply and release tests, then existing crash/advance matrices.

## Product open question

Can an old, never-attempted plan with null P be operator re-attested in place? This implementation takes the safe default: no automatic re-attestation and no current-build backfill; the operator creates a new plan. Closed legacy history remains inert/readable. Any legacy attempt, lease or receipt with null P is sticky paused/manual recovery.

## Post-#66 integrated replay matrix

This consumer change freezes independently because its current path overlap with producer PR #66 is zero and the frozen wire contract has zero diff. Integration order is strict: land/freeze the #66 producer exact head, replay this #63 consumer commit onto it, then run the integrated gate. Do not edit #66 or the contract from this branch.

| Combined scenario | Required result |
|---|---|
| Bench provider applies, Auto dies, compatible Auto build now defaults to production | Restored plan/attempt P remains bench; all recovery routing stays bench. Current `BuildConfig` is ignored. |
| QWY exact replay | Combined revision expectation remains `R+1`; the same key/digest/P is an idempotent replay, not a second effect. |
| Owner recovery replay | Combined owner-recovery expectation remains `R+1`; the durable owner and proof chain select the original P. |
| Quarantine path | Combined quarantine expectation is `R+2`; it must not manufacture ownership for a legacy or foreign principal. |
| Foreign, legacy-null, unknown, or mixed P | Zero provider RPC, zero mint, zero advance; persist/retain operator-visible manual recovery. |
| Release and cross-principal idempotency | Lease ownership is `(P, leaseId)` scoped, while the idempotency key stays global; the same key under another P is a conflict. |
| Auto signer rotates after the lease was created | QWY's caller owner `(caller applicationId, signerDigest)` correctly fails closed. Auto may require manual/provider cleanup; never relax signer ownership to make cleanup automatic. |

The integrated reviewer must re-run the process-death/build-switch matrix against the exact combined head and retain the zero-RPC/mint/advance assertions. A signer-rotation cleanup limitation is an explicit residual risk, not permission to fall back to sibling provider or broaden QWY caller authorization.

## Exact-head review iteration (`c01fb145`)

The first frozen implementation passed its local gates but the independent exact-head review found
two surviving fail-open boundaries. They are treated as mechanism failures, not fixture failures:

1. The coordinator's proof-log preflight did not join the authoritative Room plan/attempt owner.
   Equal or absent proof rows could therefore let a null/foreign owner reach a provider consumer.
2. Binder readiness timeout reported failure without irrevocably closing the acquisition, so an
   exact late callback could repopulate the executor before engine dispatch.

The iterative fix adds a shared Room-backed resolver used by both `PlanRepository` and
`RecoveryCoordinator`. In one read transaction it joins executor P to attempt, task, plan,
canonical operation receipt, recovery checkpoint, release receipt and lease. Normal attempt
creation performs this durable readback before discover; recovery performs it before every live
provider/evidence/decision consumer. The production engine factory rejects a scoped coordinator
whose resolver is the explicit test-only unchecked seam, and a source oracle pins
`AutomationService` to that factory.

Readiness timeout now evicts the registry entry, closes/unbinds the executor and permanently fences
its callback instance. `AutomationService` persists `PROVIDER_BIND_NOT_READY`, pauses the active
session and returns before constructing the backend or engine. The late-callback and real-Room kill
tests are part of the exact-head reviewer checklist; neither behavior may be weakened to an
advisory log or a proof-only check.

The closing entry-point sweep also made two caller-supplied identities subordinate to the durable
owner: a production release must use the attempt-derived release key plus the lease-derived digest,
and `completeAndAdvance` must submit the attempt-derived apply key to the same Room checker. These
checks activate only for the durable-backed production resolver; the explicit unchecked seam keeps
historical coordinator-only fixtures isolated and the production factory refuses that seam. A bind
API result of `false` is likewise terminal even if a synchronous callback races before the result.

Pre-freeze evidence on the final working tree: the six principal/recovery/migration/UI suites pass
43/43, the migration ladder passes 11/11, the complete Auto unit suite passes 552/552, debug/release
compile+assemble passes 151 tasks, the Auto lint-debt inventory remains 0, and debug-only collector
plus pinned debug-signer artifact checks pass. The repo-wide exact-commit gate is run after the
single local commit is frozen.

### Task 5: Quality gate and clean commit

1. Run targeted test classes and `./gradlew --no-daemon testDebugUnitTest` in `apps/cellrebel-auto`.
2. Run debug/release compile/assemble as defined by `.github/workflows/android-a-plus.yml`.
3. Run `./scripts/check-inherited-lint-debt.sh cellrebel-auto` and relevant static/debt guards from repo root.
4. Run `git diff --check`; inspect `git status` and the final diff for unrelated changes.
5. If risk/time permit, run the repo full A+ gate. Do not use adb or install artifacts.
6. Commit locally on `codex/pr63-durable-principal-recovery`, verify the worktree is clean, and report exact SHA plus RED→GREEN evidence and residual risks to the owning session.

## Iterative signer-owner addendum (`b3523d3` review)

The applicationId owner remains necessary but is not sufficient for a lease. A plan selects one
package P; each new attempt freezes the exact signer S supplied by the ready, registry-issued
acquisition for that run. This deliberately does **not** freeze S on `LocationPlan`: once no old
attempt is recoverable, a separately approved B may own future new work for the same selected P.

### Signer state × event table

| Durable state | Event | Required `(P,S)` evidence | Result before external work |
|---|---|---|---|
| plan P, no recoverable attempt | current approved signer B starts new work | registry re-resolves B and issues an exact ready `(P,B)` acquisition | insert attempt with B and read it back before discover/preflight/RPC |
| attempt/lease A, no sticky reason | process dies; current B is approved before first recovery | attempt + every existing proof say A; captured current/registry capability say B | `PROVIDER_SIGNER_OWNER_CONFLICT`, attempt recovery-required, session paused, 0 acquire/bind/RPC |
| legacy attempt/lease with null S | any current signer or pairing state | no immutable signer owner exists | `PROVIDER_SIGNER_OWNER_UNKNOWN`, manual recovery; never backfill |
| attempt A, proof row absent in an early crash window | recover | absence is allowed only where phase permits it; attempt A remains authority | join remaining owner rows; do not treat absence as null owner |
| attempt A, any existing proof has null S | recover | existing row lacks required provenance | typed UNKNOWN, zero effects |
| attempt A, any existing proof says B | recover | durable owners contradict | typed CONFLICT, zero effects |
| live attempt A | package rotates/revokes after pre-bind | every RPC re-resolves current signer and requires exact A + active `(P,A)` | fail closed and persist sticky manual recovery; B approval cannot clear it |
| owner conflict already sticky | generic retry or later B approval | no explicit manual-resolution evidence | remain sticky; zero provider work |

Lifecycle owners: `AutomationService` captures current signer only for the pre-registry Room check;
`ProviderExecutorRegistry` re-resolves it and is the sole issuer of the opaque exact signer
capability; `AutomationEngine` copies capability S to a new attempt; Room proof writers copy the
same S; Room preflight owns recovery joins. PackageManager/pairing rows are comparison inputs, never
durable-owner fallbacks.

### Signer invariants and tests

| ID | Invariant | Killing test |
|---|---|---|
| INV-DP-18 | Production signer strings use the same canonical normalization/validation before trust lookup, capability issue and durable write. | malformed/noncanonical signer cannot produce an acquisition or row. |
| INV-DP-19 | New attempt S comes only from a live registry-issued exact capability and is read back before discover/preflight/RPC. | insert trigger mutates S; all journey counters stay zero. |
| INV-DP-20 | Recovery joins attempt S and every *existing* apply/checkpoint/release proof S in one Room transaction. | parameterized null/foreign row sweep returns UNKNOWN/CONFLICT with zero effects; absent permitted row is not mistaken for null. |
| INV-DP-21 | Current B, even if approved, cannot authorize A's outstanding lease before the first sticky write. | real production-acquisition Binder test asserts 0 acquire/bind/raw release/proof mutation and paused manual state. |
| INV-DP-22 | Legacy null S is never inferred from current signer, pairing records, sibling packages, time or BuildConfig. | committed-v6 migration leaves all signer columns SQL NULL; recovery stops pre-bind. |
| INV-DP-23 | B approval remains usable for future new work only. | no recoverable attempt → exact `(P,B)` acquisition inserts B-owned attempt; A-owned attempt still conflicts. |

### Migration decision

The merge base is DB v6 and schema v7 exists only in this unmerged consumer commit. Extend the
existing `MIGRATION_6_7` and exported `7.json`; do not create a fictitious deployed v7→v8 path.
Add nullable `providerSignerDigest TEXT` with no default/backfill to `test_attempts`,
`operation_receipts`, `recovery_checkpoints`, and `release_receipts`. `MIGRATION_5_6` remains
byte-for-byte unchanged. `LocationPlan` intentionally remains P-only for the lifecycle reason above.

### Iteration result and release-identity self-audit

The `b3523d3` review RED reproduced both unsafe cases through the real Room/registry/Binder
production chain: A-owned and legacy-null leases passed the pre-bind check after B was approved and
each reached one raw release RPC. The migration RED also proved that the then-current v7 schema had
no signer owner. GREEN now performs the Room `(P,S)` join and atomically persists typed pause before
registry acquisition; the same capability S is inserted and read back on a fresh attempt before
discover/preflight/RPC. Existing proof rows must carry exact S, while phase-permitted absent rows are
not confused with null provenance.

A final failure-mode sweep found one separate release-index regression introduced during signer
propagation: treating `(P,S,leaseId)` as the uniqueness scope allowed signer B to append a second
row for A's same `(P,leaseId)` under another key. Its killing test was RED (4 tests, 1 failure).
Release lookup/insert now scans the canonical `(P,leaseId)` scope and treats S only as immutable
proof. The resulting matrix is explicit:

- one global idempotency key cannot replay under another P;
- one `(P,leaseId)` cannot be written under another signer/key;
- the same leaseId under a different P remains a separate lease identity when it uses a different
  key.

Pre-freeze evidence: the signer/recovery targeted set is 155/155 GREEN; the migration ladder is
10/10 GREEN; the complete Auto unit suite is 579/579 GREEN. Debug/release compile and assemble,
release-purity, inherited-lint-debt, and pinned debug-signer checks are GREEN. The repo-wide full
gate is intentionally rerun against the clean exact commit before independent exact-head review.

Fixture adaptations did not weaken legacy behavior: old tests that had never seeded an active
canonical signer now use real 64-hex signer principals, and adapter-only P seams remain explicit.
Migrated null S still pauses manually and is never filled from fixture convenience, PackageManager,
pairing state, BuildConfig, time, sibling packages, or lease contents.

### Post-GREEN typed-failure and UI reachability sweep

The final reachability sweep found two crash windows where safety still stopped external work but
lost the signer-specific durable reason: an A revoke between the outer recovery preflight and
`reconcile`, and an A revoke at the DECIDING mint boundary. Before the fix the first became generic
`PROVIDER_PRINCIPAL_UNKNOWN`; the second left the attempt in `DECIDING` with no failure reason.
Re-approving A could therefore let the Service pass its pre-bind guard before a later layer stopped
the run. A third UI gap showed a pre-guard crashed owner as pairing/trusted when S was null or no
longer current.

The internal chain now carries a typed `ProviderPrincipalFailureReason` with exact stable durable
codes and explicit `UNKNOWN` / `CONFLICT` / `UNTRUSTED` classification. Coordinator results and
repository exceptions carry the enum; Engine persists its code directly; UI parses only exact code
equality. No provider failure is inferred from exception-message prefixes or substrings. Both race
tests persist the exact signer reason and paused session before retry can bind, even after the same
signer is later approved. UI treats any crashed owner without an active exact `(P,S)` as manual
recovery before pairing-state projection.

RED was 3 failures in 22 tests: generic reconcile reason, DECIDING left unchanged, and pairing UI
winning over the crashed owner. GREEN is 22/22 for those suites and 43/43 with the adjacent UI and
legacy coordinator suites. A separate SQLite trigger mutates a freshly inserted S before readback;
the engine then performs zero discover/preflight/apply/observe/release/advance, writes no proof or
mint, and persists signer-owner conflict. This kills removal of the post-insert Room readback.

### Exact `9f7ae052` review: same-lease signer-reason priority

The same-`(P, leaseId)` release-row scan is a typed ownership boundary, not merely a structural
uniqueness check. It therefore evaluates the complete row set in two phases:

1. aggregate signer failures with deterministic priority `OWNER_UNKNOWN > OWNER_CONFLICT`;
2. only when signer ownership is exact, evaluate wrong key, digest, outcome, duplicate and canonical
   readback as generic `PROVIDER_PRINCIPAL_CONFLICT`.

This ordering is independent of DAO row order. Real-Room RED was 7 tests / 3 failures: null S,
foreign S and mixed null+B all collapsed to generic conflict. GREEN is 7/7; mixed rows are exercised
in both insertion orders. The production guard persists the exact code with `RECOVERY_REQUIRED` and
session `paused`, UI projects `ReleaseIncomplete`, and acquire/bind/RPC/mint/checkpoint/proof
mutation remain zero. Current evidence is reviewer set 33/33, changed suites 158/158, and Auto full
582/582.

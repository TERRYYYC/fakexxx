---
feature_ids: [F-13]
topics: [application-id, cutover, snapshot, restore, rollback, saf]
doc_kind: implementation_plan
created: 2026-09-07
status: in-progress
---

# Application ID Cutover Snapshot / Restore Implementation Plan

**Feature:** F-13 — [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)
**Goal:** Preserve the complete Auto user state across `com.example.cellrebelauto` → `come.xx.fakeaauto` through an operator-controlled, versioned SAF archive that is canonical, retryable, failure-closed, and rollback-safe.
**Acceptance Criteria:** `legacyId` exports and `productId` imports only their allowed direction; every owned Room row and all five raw DataStore keys round-trip; content digests are recomputed from canonical bytes; partial restore is never visible; eligibility is checked at the first write and before publication; historical pairing never becomes active; retry/rollback survives every crash edge; CSV is rejected as a migration carrier; operator data never enters the repository or logs; `M-AC-03` remains device-only.
**Architecture cell:** `fakexxx::android-dual-app-contract`
**Map delta:** none
**Map delta why:** The change stays inside the existing Auto ownership cell and does not alter the Binder boundary.
**Architecture:** A schema-independent immutable archive carries canonical row payloads, exact raw preference presence, restoration modes, and codec-computed digests. A durable restore journal is the single lifecycle owner; visibility is a pure projection of `READY`, while the Auto-owned adapter later supplies Room/DataStore capture, persistence, and reader gating after #79 freezes the final schema.
**Tech Stack:** Kotlin/JVM, JUnit 4, Android product source sets, Room and DataStore adapters in the integration phase.
**前端验证:** Yes — SAF ActivityResult and operator journeys require interactive review when implemented; the current carrier/reducer phase is host-only.

---

## Finish line and non-scope

The finish line is an old→new→rollback journey in which the legacy sandbox remains intact, the product sandbox exposes either the entire verified generation or none of it, and an interrupted import can deterministically resume or roll back without manufacturing trust.

This plan does **not** install an APK, mutate a device applicationId at runtime, remove the legacy app, restore Android permissions automatically, publish a release, or treat PR #103's metadata-only `CutoverBundleCodecV1` as a content snapshot. PR #106's already accepted behavior is outside this change and is not re-tested merely because `main` advanced.

## Original AC → terminal interface map

| Requirement | Terminal interface / state | Evidence |
| --- | --- | --- |
| `G-AC-1` / `M-AC-01`: old-install state and signer/version eligibility | `CutoverEligibilityPort.observe()` returns `ELIGIBLE`, `INELIGIBLE`, or `INDETERMINATE`; only `ELIGIBLE` may cross a write boundary | Pure reducer rejection tests now; Android package/signer probe and device evidence later |
| `G-AC-2` / `M-AC-02`: complete round-trip | `AutoCutoverSnapshotPort.capture()` supplies every owner-declared Room table plus exactly five raw PlanConfig preferences; `AutoCutoverRestorePort` consumes the verified archive | Codec content/digest tests now; DAO/DataStore adapter tests after #79 schema freeze |
| `M-AC-03`: old→new→rollback | `CutoverRestoreJournal` + `CutoverRollbackPort`; legacy sandbox is never mutated by product restore | Host crash/reducer tests now; authorized device journey later |
| `G-AC-3` / `M-AC-04`: CSV is not migration | SAF importer accepts only `CutoverArchiveV2` media/version and rejects result/worklist CSV | Codec/media negative tests; source-set UI test later |
| `M-AC-05`: no operator data in repo/log | Archive bytes only flow between SAF streams; result types contain digests/reasons, never row payloads | Static guard and log-surface tests later |
| Five DataStore keys | `CutoverPreferenceEntry` records key, type, raw presence, and canonical value; absence is distinct from the runtime default | Exactly-five, missing/duplicate/type/value tests |
| Pairing history cannot revive | `provider_pairing_records` uses `HISTORICAL_ONLY` restoration mode; generic exact restore is forbidden | Codec policy tests now; adapter transformation tests later |
| Cross-store visibility fence | `CutoverRestoreJournal.isVisible` is derived solely from `phase == READY`; every normal reader is gated by the persisted journal | Reducer tests now; app startup/service/repository guard tests later |
| Write-point eligibility | reducer requires `ELIGIBLE` at `begin`, and again for `publishReady` after verification | stale/indeterminate eligibility tests |
| SAF direction isolation | `legacyId` contains exporter/CreateDocument only; `productId` contains importer/OpenDocument only | source-set absence/manifest tests later |

## Terminal schema

```kotlin
data class CutoverArchiveV2(
    val sourcePackage: String,
    val captureId: String,
    val schemaVersion: Int,
    val tables: List<CutoverTableSection>,
    val preferences: List<CutoverPreferenceEntry>
)

data class CutoverTableSection(
    val name: String,
    val schemaDigest: String,
    val restorationMode: CutoverRestorationMode,
    val rows: List<CutoverRowPayload>
)

data class CutoverRowPayload(
    val orderKeyBase64Url: String,
    val canonicalRowBase64Url: String
)

enum class CutoverRestorePhase {
    STAGED, ROOM_WRITTEN, DATASTORE_WRITTEN, VERIFIED,
    READY, ROLLBACK_REQUIRED, ROLLED_BACK
}

data class CutoverRestoreJournal(
    val archiveDigest: String,
    val captureId: String,
    val phase: CutoverRestorePhase,
    val failureReason: String? = null
) {
    val isVisible: Boolean get() = phase == CutoverRestorePhase.READY
}
```

The archive codec owns canonical ordering, size bounds, Base64URL normalization, per-table row digest preimages, and the final archive digest. Producers do not supply trusted row/archive digests. The Room adapter later owns mapping current schema rows to canonical row bytes; the codec never guesses columns or #79's final binding field.

## Stateful object census

### 1. Immutable SAF archive

Lifecycle owner: `CutoverArchiveV2Codec`. The archive is a value, not mutable state.

| Event | Allowed result | Forbidden result |
| --- | --- | --- |
| encode valid snapshot | one canonical byte sequence and recomputed digest | caller-provided digest trusted as truth |
| decode canonical bytes | verified immutable archive | accepting reordered, duplicate, unknown, truncated, oversized, or non-canonical input |
| decode CSV/other version | typed rejection before restore | best-effort import |

### 2. Source capture session

Lifecycle owner: future `AutoCutoverSnapshotPort`; normal mutation and capture must share one owner/quiescence seam.

| State | Event | Next | Rule |
| --- | --- | --- | --- |
| `IDLE` | capture while no active run and owner lock held | `CAPTURING` | read Room and five raw preferences under one logical generation |
| `CAPTURING` | content/digest complete | `EXPORTED` | source remains unchanged |
| `CAPTURING` | crash/error | `IDLE` | no partial archive is published |

### 3. Restore journal / visibility

Lifecycle owner: future `CutoverRestoreCoordinator`. Generic restore/delete APIs may not advance the journal or publish visibility. `isVisible` is a pure projection and is never stored separately.

| Current | Event / proof | Next | Side-effect authority |
| --- | --- | --- | --- |
| absent | stage canonical archive + `ELIGIBLE` | `STAGED` | persist digest/capture only |
| `STAGED` | exact Room generation committed | `ROOM_WRITTEN` | Room adapter |
| `ROOM_WRITTEN` | exactly five raw preferences committed | `DATASTORE_WRITTEN` | DataStore adapter |
| `DATASTORE_WRITTEN` | full readback digest equals archive | `VERIFIED` | verifier only |
| `VERIFIED` | fresh `ELIGIBLE` | `READY` | journal owner publishes visibility |
| any non-ready | failure/crash mismatch | `ROLLBACK_REQUIRED` | no normal reader access |
| `ROLLBACK_REQUIRED` | product target returned to pre-import state | `ROLLED_BACK` | rollback adapter; legacy untouched |
| same phase | retry with same archive digest/capture | same or next proven phase | idempotent replay |
| any active phase | different archive or second importer | reject | no overwrite/interleaving |

### 4. Eligibility observation

Lifecycle owner: `CutoverEligibilityPort`; observations are not durable authorization. `INDETERMINATE` is failure-closed. A beginning observation cannot authorize the final `READY` publication.

### 5. Pairing history

Lifecycle owner after restore remains the existing `ProviderTrustStore`. Imported rows are history only; active approval can only be minted later through the existing operator approval path. A generic restore adapter is forbidden from exact-restoring an active pairing.

## Invariants

- `CUT-INV-01`: the same logical archive encodes to identical bytes; decode requires byte-for-byte canonical form.
- `CUT-INV-02`: every owner-declared Room table is present exactly once; rows are strictly ordered by unique canonical order key.
- `CUT-INV-03`: row/table/archive digests are recomputed from length-delimited canonical bytes, never accepted as self-report.
- `CUT-INV-04`: all five PlanConfig keys appear exactly once with raw presence preserved; absent is not equal to a defaulted value.
- `CUT-INV-05`: unknown version, field, preference key/type, restoration mode, duplicate, truncation, bad Base64URL, invalid size, or trailing data fails closed.
- `CUT-INV-06`: source package is exactly `com.example.cellrebelauto`; product import never reads another app sandbox directly.
- `CUT-INV-07`: first target write and `READY` publication each require a fresh `ELIGIBLE` observation.
- `CUT-INV-08`: normal readers observe restored state iff the durable journal is `READY`.
- `CUT-INV-09`: crash/retry may repeat only the same archive digest/capture and never redispatch a proven phase blindly.
- `CUT-INV-10`: `provider_pairing_records` is `HISTORICAL_ONLY`; restore cannot create active trust.
- `CUT-INV-11`: rollback mutates only productId's target generation; the legacy app/data remain intact.
- `CUT-INV-12`: archive content is absent from logs, repository fixtures, failure results, and audit strings; tests use synthetic data only.
- `CUT-INV-13`: worklist/result CSV is not a cutover archive.
- `CUT-INV-14`: schema/table census comes from the Auto owner at an immutable #79-aware anchor; the carrier does not freeze a guessed column list.
- `CUT-INV-15`: legacyId export and productId import are compile/source-set isolated.

## Adversarial matrix

| ID | Scenario | Expected result |
| --- | --- | --- |
| `CUT-A01` | missing/duplicate/reordered table or row | codec rejects before restore |
| `CUT-A02` | payload bit flip, forged row count/digest, trailing bytes | recomputation rejects |
| `CUT-A03` | one of five preferences absent from the carrier vs explicitly value-absent | missing entry rejects; explicit absence round-trips |
| `CUT-A04` | malformed/oversized Base64URL or payload | bounded decode rejects |
| `CUT-A05` | pairing table marked exact-restorable | archive validation rejects |
| `CUT-A06` | crash after each journal edge | state remains invisible and resumes only the next unproven phase |
| `CUT-A07` | eligibility becomes stale after verification | `READY` publication rejects and moves to rollback-required |
| `CUT-A08` | same archive replay vs different archive during restore | same is idempotent; different is rejected |
| `CUT-A09` | two concurrent import actions | one journal owner; loser receives typed busy/conflict |
| `CUT-A10` | rollback from each non-ready phase | product target cleared/restored; legacy untouched |
| `CUT-A11` | CSV or wrong media/version supplied | typed rejection, zero target writes |
| `CUT-A12` | logger/result attempts to include row bytes | surface guard fails |
| `CUT-A13` | exporter compiled into productId or importer into legacyId | source-set contract test fails |
| `CUT-A14` | reader bypasses visibility fence | startup/service/repository guard test fails |

## Implementation tasks

### Task 1: Freeze this contract

**Files:**
- Create: `feature-specs/2026-09-07-application-id-cutover-snapshot-restore.md`

1. Record original AC mapping, terminal schema, state tables, invariants, and adversarial cases.
2. Confirm current PR/file overlap and send the Auto snapshot-port request to its existing owner task.
3. Commit this plan before behavior code.

### Task 2: Canonical content archive (current phase)

**Files:**
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/cutover/CutoverArchiveV2.kt`
- Create: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/cutover/CutoverArchiveV2CodecTest.kt`

1. Write failing tests for `CUT-INV-01..06`, `CUT-INV-10`, `CUT-A01..05`, and `CUT-A11`.
2. Run both flavor unit-test tasks and confirm failure is the missing V2 codec/types, not environment setup.
3. Implement the minimal length-delimited canonical codec, strict bounds, recomputed digests, five-key presence contract, and historical-only pairing-table rule.
4. Re-run the exact tests in both flavors; expected result is all new V2 tests passing.
5. Commit only the codec and its tests.

### Task 3: Pure restore journal reducer (current phase)

**Files:**
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/cutover/CutoverRestoreProtocol.kt`
- Create: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/cutover/CutoverRestoreProtocolTest.kt`

1. Write failing transition tests for `CUT-INV-07..09`, `CUT-INV-11`, and `CUT-A06..10`.
2. Confirm RED on missing reducer/types.
3. Implement a pure reducer: exact legal edges, same-digest replay, different-digest conflict, failure-to-rollback, two eligibility checks, and `isVisible` projection.
4. Re-run exact tests in both flavors; expected result is all reducer tests passing.
5. Commit only the reducer and tests.

### Task 4: Auto-owned Room/DataStore integration (blocked on owner + #79)

**Files (owner to confirm):**
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/data/PlanConfigStore.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/AppDatabase.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/cutover/AutoCutoverSnapshotPort.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/cutover/AutoCutoverRestoreCoordinator.kt`
- Test: matching `cutover/**Test.kt` plus reader-bypass guards

Wait for the Auto owner to identify the mutation/quiescence seam and for #79 to freeze its final schema/migration anchor. Then add DAO/DataStore integration with RED tests for exact table census, five raw preferences, historical pairing transformation, readback digest, crash resume, and reader gating. Do not duplicate DAO/schema logic in the carrier.

### Task 5: Flavor-only SAF surfaces

**Files:**
- Create under `app/src/legacyId/**`: exporter + `CreateDocument` entry point
- Create under `app/src/productId/**`: importer + `OpenDocument` entry point
- Test: source-set/manifest absence and host ActivityResult contract tests

Use only operator-selected URIs, never repository/log paths. Compile-time/static tests must prove productId has no exporter and legacyId has no importer.

### Task 6: Final gates and device-deferred acceptance

Run only new/affected host tests while implementation is in progress. Before review, run the risk-matched Auto host gate, inspect the exact diff, and request a non-author review of the new behavior delta. `M-AC-03`, permission re-grant, accessibility re-enable, notification permission, installation, applicationId runtime mutation, release, tag, merge, and issue closure remain explicitly deferred and frozen.

## Verified host commands

The repository has two flavor-specific JVM tasks. Use the Android Studio JBR and explicit SDK paths:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/terry/Library/Android/sdk' \
./gradlew :app:testLegacyIdDebugUnitTest --tests '*CutoverArchiveV2CodecTest*' --rerun-tasks --no-daemon --max-workers=2

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/terry/Library/Android/sdk' \
./gradlew :app:testProductIdDebugUnitTest --tests '*CutoverArchiveV2CodecTest*' --rerun-tasks --no-daemon --max-workers=2
```

Equivalent commands apply to `CutoverRestoreProtocolTest`. No `connected*`, install, emulator, ADB, applicationId runtime mutation, or #106 regression command is part of this phase.

## Open questions

### Technical

1. Which existing Auto owner lock can make Room plus raw PlanConfig capture a single logical generation?
2. Which startup/service/repository entry points must consume the persisted restore journal so no reader bypasses the fence?
3. What immutable commit/schema anchor will contain #79's final `scheduleItemId` binding?
4. Does productId v1 require the target to be empty, or must rollback preserve pre-existing productId state as a separate generation? Default until owner evidence says otherwise: fail closed unless target is empty.

### Value

None. The operator has already selected legacyId SAF export → operator custody → productId SAF import, and has frozen all device operations for this phase.

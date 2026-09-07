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

#### Archive record grammar and canonical text boundary

The line grammar is heterogeneous. A row-shaped upper bound must never be reused as a bound for
header, preference, table, or footer records. Define `F = maxEncodedFieldChars`, `D = 71` for the
canonical `sha256:` digest, and `digits(n)` as the decimal character count of a non-negative bound.

| Record | Cardinality / order | Exact grammar | Maximum line characters |
| --- | --- | --- | --- |
| format | first, exactly once | `cutover-archive-v2` | literal length |
| source | second, exactly once | `source=<text-b64url>` | `len("source=") + F` |
| capture | third, exactly once | `capture=<text-b64url>` | `len("capture=") + F` |
| schema | fourth, exactly once | `schemaVersion=<canonical-positive-int>` | prefix + `digits(policy.schemaVersion)` |
| preference | exactly five, decoded-key sorted | `preference=<key-text>|<type>|<presence>|<value-text-or-dash>` | prefix + `2F` + three separators + longest type + longest presence |
| table | exactly the policy census, decoded-name sorted | `table=<name-text>|<schema-text>|<mode>|<row-count>|<row-digest>` | prefix + `2F` + four separators + longest mode + `digits(maxRowsPerTable)` + `D` |
| row | exactly the preceding table count, encoded-key strictly increasing | `row=<opaque-order-key>|<opaque-row-payload>` | prefix + `2F` + one separator |
| archive digest | final, exactly once, outside the body digest | `archiveDigest=<archive-digest>` | prefix + `D` |

`<text-b64url>` means: a valid Unicode scalar sequence is encoded by a reporting UTF-8 encoder,
then by unpadded Base64URL. Decode performs canonical Base64URL re-encoding and a reporting UTF-8
decode; malformed bytes and isolated UTF-16 surrogates reject rather than becoming replacement
characters. Row order keys and row payloads are deliberately opaque bytes, not UTF-8 text.

Empty/minimum-value rules are field-specific and apply identically to policy, encode, and decode:

| Field | Empty / minimum disposition | Why |
| --- | --- | --- |
| source package | empty/blank forbidden; exact legacy package only | direction and sandbox identity |
| capture id | empty/blank forbidden | restore identity cannot be anonymous |
| table name | empty/blank forbidden in policy and archive | census key and canonical ordering |
| schema digest | empty/blank forbidden in policy and archive | a table cannot claim an absent schema identity |
| row order key | zero-byte value forbidden | uniqueness/order proof needs an identity |
| row payload | zero-byte value allowed | an owner-declared canonical row may have an empty payload |
| preference key | exactly the five non-empty owned keys | no unknown/default-materialized key |
| present preference value | empty forbidden by canonical `Int`/`Boolean` grammar | value must match its declared type |
| absent preference value | model value is `null`; wire value is exactly `-` | absence remains distinct from a default |
| schema version | smallest legal value is `1` | zero/negative versions are outside policy |
| table row count | smallest legal value is `0` | empty tables are required to round-trip |
| preference integer | `Int.MIN_VALUE..Int.MAX_VALUE`, canonical decimal including `0` | codec preserves raw typed value; business ranges belong to the owner |
| size/count limits | archive/field/line/table limits are positive; row limits may be zero | zero-capacity row policies are valid; zero-byte archives are not |

Blank lines, raw non-ASCII syntax bytes, CR/LF variants, padding, extra delimiters, unknown records,
and a final LF are not alternate spellings. A successful decode must satisfy the test-only composite
invariant `encode(decoded.archive).serialized == input` and equal archive digests; production decode
must prove this through the individual grammar checks rather than allocating a second whole archive.

#### Codec policy domain and bidirectional resource limits

A legal policy has a positive schema version; a non-empty table census no larger than `maxTables`
whose names and schema-digest values are non-blank, strict-UTF-8, and representable within `F`;
`provider_pairing_records` present and `HISTORICAL_ONLY`; no historical-only name outside the census;
the exact five-key `CutoverPlanConfigSchema`; positive archive/field/line limits; and non-negative
per-table/total row limits. A restrictive legal policy may reject every candidate, but it must never
produce bytes that the same policy rejects: **encode success implies exact decode success**.

Encode and decode apply the same limits at different trust boundaries:

| Boundary | Encode requirement | Decode requirement | Pre-allocation rule |
| --- | --- | --- | --- |
| archive bytes | check before every ASCII append, including footer | reject total characters before parsing; ASCII makes characters equal bytes | one bounded builder on encode; no whole-input split/copy on decode |
| archive lines | derive exact header + prefs + tables + rows + footer with `Long` arithmetic | count LF before any line/list allocation | reject overflow or `> maxArchiveLines` |
| text/opaque field | reject before emitting if unpadded Base64URL would exceed `F` | reject encoded length before Base64 allocation | reporting encoder uses a buffer no larger than the decoded-byte capacity representable by `F` |
| record line | use the grammar-specific formula above | pass the matching formula to the cursor for each expected record | all formula arithmetic is `Long`, capped by archive bytes and `Int.MAX_VALUE` |
| table rows | validate each table and total before hashing | validate canonical count, per-table bound, then total before `ArrayList` | never allocate from an unvalidated count |
| digests | update incrementally from length-framed row bytes and ASCII body | recompute incrementally; claimed lowercase digest is proof input only | no whole-table byte stream and no second canonical archive |

#### Decode cursor lifecycle and error exits

Lifecycle owner: one `CutoverArchiveV2Codec.decode` invocation. `BoundedLineReader` is a private cursor,
not a generic iterator: callers may not skip, peek past, or reinterpret records.

| Current | Event / proof | Next | Side effect / forbidden bypass |
| --- | --- | --- | --- |
| `UNTRUSTED_INPUT` | ASCII, total byte/line limits, terminal digest spelling and body digest verify | `BODY_VERIFIED` | no archive/model allocation before bounds; digest success does not waive grammar checks |
| `BODY_VERIFIED` | exact format/source/capture/schema records | `PREFERENCES` | each `nextLine` receives that record's own maximum |
| `PREFERENCES(n)` | next canonical preference, `n < 5` | `PREFERENCES(n+1)` | no missing, duplicate, reordered, unknown, or default-materialized absence |
| `PREFERENCES(5)` | next canonical table header | `TABLE(rowsRemaining)` | exact policy census/mode/schema only |
| `TABLE(k)` | next canonical row, `k > 0` | `TABLE(k-1)` | count/total validated before list allocation; row proof updated incrementally |
| `TABLE(0)` | row digest matches and another policy table remains | `TABLE(next)` | no caller-supplied proof accepted without recomputation |
| final `TABLE(0)` | cursor is exactly body-exhausted and body did not end with LF | `CANONICAL_ARCHIVE` | no swallowed terminal blank line or unknown record |
| any nonterminal state | malformed, oversized, non-canonical, proof mismatch, or truncation | `REJECTED` | `IllegalArgumentException`; no archive result and no restore side effect |

The archive and policy are immutable values. Cursor position, line count, row total, previous row key,
and digest accumulators exist only inside one encode/decode call and are discarded on either terminal
exit; none is persisted or recoverable as application state.

#### Finding pattern summary and sibling sweep

R1–R4 exposed one pattern: canonicality and allocation safety were described globally while the
implementation normalized heterogeneous records independently. R1 found unbounded whole-input
splitting; R2 found lossy UTF-8 and a cursor terminal-empty-line gap; R3 found a row-only line formula
applied to a longer table record; R4 found that the legal policy domain omitted a schema-digest
non-blank lower bound already required by decode. The governing invariant is now: **every accepted representation has
one spelling, and every successful encoder output is accepted identically by the same policy without
an allocation that exceeds that policy's declared bounds**.

Siblings scanned and disposition:

- UTF-8 / UTF-16: reporting encode and decode; valid non-ASCII is preserved; replacement is forbidden.
- Numeric spellings: schema and row counts must equal their parsed canonical decimal rendering.
- Base64URL: alphabet, decoder validity, no padding, and re-encode equality are all required.
- Empty/trailing syntax: blank body records, trailing LF/data, extra separators, and unknown lines reject.
- Empty/minimum field values: source, capture, table name, schema digest, row order key, row payload,
  preference key/value, schema version, row count, and resource limits follow the field table above;
  the only intentionally empty payload is an opaque row value, and zero rows are valid.
- Record overhead: format/source/capture/schema/preference/table/row/footer each owns its formula;
  table mode/count/digest overhead may not borrow the row formula.
- Counts and overflow: line/table/per-table-row/total-row arithmetic uses checked `Long` bounds before
  conversion or allocation; negative, wrapped, or out-of-policy values reject.
- Memory: decode retains the caller's input plus bounded per-line/per-field/model allocations; encode
  retains one bounded builder plus bounded per-field buffers and incremental digests.

### 2. Source capture session

Lifecycle owner: `AutoCutoverSnapshotPort`, reached only through the application-scoped
`AutoCutoverCoordinator`. Normal mutation and capture share `CutoverAccessGate`; stopping only the
export coroutine is not quiescence.

| State | Event / proof | Next | Side effect / forbidden bypass |
| --- | --- | --- | --- |
| `IDLE` | capture requested | `QUIESCING` | close admission for every normal reader/writer; a direct DAO/DataStore bypass is forbidden |
| `QUIESCING` | AutomationService cancellation and durable run convergence prove no active owner | `CAPTURING` | acquire the exclusive cutover lease after all admitted accesses drain |
| `QUIESCING` | stop/convergence fails or times out | `IDLE` | reopen admission; no Room/DataStore read and no archive publication |
| `CAPTURING` | schema-9 Room transaction plus five raw preferences captured under one lease | `ENCODING` | source remains unchanged; defaults are never materialized |
| `ENCODING` | canonical codec returns complete bytes/digest | `EXPORTED` | publish exactly one immutable archive, then release the lease |
| `CAPTURING` / `ENCODING` | crash/error/cancellation | `IDLE` on next process start | no partial archive is published; source bytes remain authoritative |

### 2a. Room v9 schema/table census and canonical row projection

The Room owner truth is
`apps/cellrebel-auto/app/schemas/com.example.cellrebelauto.db.AppDatabase/9.json` at merged
implementation `fb6284a7793e07333634d6fdf05a4688a6b8396c` and integration commit
`b53dfc3cba8d7eb91b53964410d1f871f7b69f89`. Capture and restore require exactly these 18 user
tables; `room_master_table`, `android_metadata`, `sqlite_sequence`, temp objects, views, and any later/unknown table are
not silently included:

| Dependency tier | Tables | Restore rule |
| --- | --- | --- |
| roots | `run_sessions`, `location_plans`, `trusted_quota_entries`, `cellrebel_executions`, `auto_audit_events`, `legacy_completion_snapshots`, `provider_pairing_records`, `unverified_attempt_records`, `durable_observation_records`, `durable_completion_receipts`, `operation_receipts`, `recovery_checkpoints`, `release_receipts`, `advance_replay_carriers`, `advance_receipts` | insert in canonical table-name order after schema/census proof |
| child of `run_sessions` | `test_results` | insert after `run_sessions` |
| child of `location_plans` | `location_tasks` | insert after `location_plans` |
| child of `location_tasks` and `run_sessions` | `test_attempts` | insert after both parents |

Each per-table schema digest is recomputed from a canonical descriptor containing ordered
`PRAGMA table_info`, foreign keys, and named indexes; capture and restore compare the descriptor to
the checked schema-9 census before reading or writing rows. Row payloads are a versioned binary
sequence in schema column order: column count, then a one-byte `NULL` / `INTEGER` / `REAL` / `TEXT` /
`BLOB` tag and a checked length-delimited value. Integers use signed 64-bit big endian, reals use raw
IEEE-754 bits, text uses reporting UTF-8, and blobs remain opaque. The row order key is the same
framing over declared primary-key columns; capture sorts by its canonical unpadded Base64URL spelling
and rejects empty or duplicate keys. Restore decodes exact column count/type, proves the order key
describes those primary-key cells, uses bound statements, and inserts in dependency
order inside one Room transaction, and never disables foreign keys.

`provider_pairing_records` is projected before hashing: a source row with `revokedAt == null` is
captured with `revokedAt = approvedAt`. Thus the archive and readback digest describe a deterministic
historical-only row; source state is unchanged and target state can never contain an active imported
principal.

### 2b. Room/DataStore access census

Every production access surface below must receive the same application-scoped gate. Constructor
injection is the enforcement seam; retaining an ungated production constructor is a bypass.

| Surface | Reads | Writes | Gate obligation |
| --- | --- | --- | --- |
| `MainViewModel` / Compose projections | plan, task, attempt, provider history, five mapped preferences | plan import/edit, provider approve/revoke, five preference setters | reject actions and suppress restored projections while restore is non-ready |
| `AutomationService` | plan/config/run recovery and per-attempt toggle snapshots | session lifecycle and engine-triggered persistence | start admission fails while cutover closes; active job cancels and durably converges before capture/restore |
| `PlanRepository` | every DAO-backed plan/task/attempt/ledger/recovery projection | all plan, session, attempt, quota, observation, receipt, recovery, result, and supersession mutations | every public entry runs inside a normal-access lease; Flow collection holds/reacquires a read lease per emission |
| `APlusComposition` / `APlusAttemptDriver` / `AutomationEngineFactory` | direct attempt/plan/receipt/observation lookups | direct audit writes | use gated owner ports; no direct production DAO escape |
| `RoomDurableRecoveryLog` and advance receipt/carrier adapters | operation/checkpoint/release/advance receipts | insert/upsert receipts and checkpoints | synchronous bridge acquires the same normal-access lease |
| `ProviderTrustStore` | active/all pairing rows | approve/revoke | normal-access lease; imported history never reaches approve |
| `PlanConfigStore` | mapped config and raw five-key snapshot | five setters and exact raw replacement/clear | mapped defaults are UI/runtime only; cutover API reads presence and replaces all five keys in one `edit` |
| `AutoCutoverSnapshotPort` / `AutoCutoverRestoreCoordinator` | raw schema-9 rows, raw five preferences, durable cutover control | Room generation, raw preferences, journal | the only exclusive-access callers; they cannot call a normal-access wrapper recursively |

The DAO census is `TestResultDao`, `RunSessionDao`, `PlanDao`, `LocationTaskDao`, `TestAttemptDao`,
`TrustedQuotaDao`, `AttemptExecutionDao`, `AuditEventDao`, `LegacyCompletionDao`,
`ProviderPairingDao`, `UnverifiedAttemptRecordDao`, `DurableObservationDao`,
`DurableCompletionReceiptDao`, `OperationReceiptDao`, `RecoveryCheckpointRoomDao`,
`ReleaseReceiptDao`, `AdvanceReplayCarrierDao`, and `AdvanceReceiptDao`. A static guard enumerates
their production consumers and fails when a new direct consumer appears without a census update.

### 2c. Application access gate

Lifecycle owner: one `CutoverAccessGate` in `CellRebelAutoApp`. Its state is process-local, but restore
visibility is initialized from the durable control journal before production repositories/services
are constructed. Admission tokens are capabilities, not booleans callers may forge.

| Current | Event / proof | Next | Rule |
| --- | --- | --- | --- |
| `OPEN` | normal reader/writer enters | `OPEN(n+1)` | token must close exactly once; new work is admitted only while open |
| `OPEN(n)` | exclusive cutover requested | `DRAINING(n)` | close new normal admission before requesting run cancellation/convergence |
| `DRAINING(n>0)` | normal token closes | `DRAINING(n-1)` | wait without holding a Room transaction or DataStore edit |
| `DRAINING(n)` | exclusive requester is cancelled or quiescence fails | `OPEN(n)` | cancellation-safe cleanup reopens admission without losing already-admitted token accounting |
| `DRAINING(0)` | exclusive owner identity bound | `EXCLUSIVE(owner)` | exactly one capture/restore owner; second owner gets typed busy/conflict |
| `EXCLUSIVE(owner)` | successful source export or target reaches `READY` / `ROLLED_BACK` | `OPEN` | release once; stale owner cannot reopen a newer generation |
| `EXCLUSIVE(owner)` | release begins under cancellation | terminal release state | release completes non-cancellably before the capability is consumed |
| any | process restart | derived from durable journal | absent/`READY`/`ROLLED_BACK` opens; active non-ready phase starts closed and may only resume/rollback |

Normal readers never return an empty/default substitute for blocked restored data: one-shot calls
return a typed unavailable result and flows wait until the journal permits visibility. Normal writers
fail before side effects. This makes absence distinguishable from cutover unavailability.

### 3. Restore journal / visibility

Lifecycle owner: `AutoCutoverRestoreCoordinator`. The journal lives in a separate
`cutover_control` DataStore so it is neither part of the schema-9 payload nor cleared with target
Room/PlanConfig rollback. It persists `(archiveDigest, captureId, phase, failureReason)`; generation
identity is exactly `(archiveDigest, captureId)`, not a new column copied into every Room table.
Generic restore/delete APIs may not advance the journal or publish visibility. `isVisible` is a pure
projection and is never stored separately.

| Current | Event / proof | Next | Side-effect authority |
| --- | --- | --- | --- |
| any | exclusive lease acquired | durable phase re-read | no caller may act on a journal snapshot obtained before exclusive ownership |
| absent | canonical archive + fresh `ELIGIBLE` + target Room empty + all five target raw preferences absent | `STAGED` | persist digest/capture before target write; non-empty target fails closed |
| `STAGED` | target still empty | `ROOM_WRITTEN` | insert all 18 tables in one Room transaction, read back and prove exact table digests |
| `STAGED` after restart | target Room already equals archive Room projection | `ROOM_WRITTEN` | recognize the prior atomic commit; never insert a duplicate generation |
| `STAGED` | target Room non-empty and digest differs | `ROLLBACK_REQUIRED` | no preference write; an ungated writer or corruption is not treated as progress |
| `ROOM_WRITTEN` | five target raw preferences still absent | `DATASTORE_WRITTEN` | replace exactly five presence/value states in one DataStore `edit` |
| `ROOM_WRITTEN` after restart | raw preferences already equal archive | `DATASTORE_WRITTEN` | recognize the prior atomic edit |
| `ROOM_WRITTEN` | raw preferences neither all absent nor exact archive | `ROLLBACK_REQUIRED` | no default/mixed state is accepted |
| `DATASTORE_WRITTEN` | full readback digest equals archive | `VERIFIED` | verifier only |
| `VERIFIED` | fresh `ELIGIBLE` | `READY` | journal owner publishes visibility |
| any non-ready | failure/crash mismatch | `ROLLBACK_REQUIRED` | no normal reader access |
| `ROLLBACK_REQUIRED` | all 18 target tables cleared in reverse dependency order and all five raw preferences absent | `ROLLED_BACK` | rollback adapter; valid because staging proved the target was empty; legacy untouched |
| same phase | retry with same archive digest/capture | same or next proven phase | idempotent replay |
| any active phase | different archive or second importer | reject | no overwrite/interleaving |
| any journal write | write returns normally | returned phase is confirmed | a write that throws or is cancelled is outcome-unknown; the gate remains recovery-closed until a later exclusive owner re-reads durable state |

Crash recovery never guesses which write ran. Room transaction and the single DataStore `edit` are
each atomic; exact readback distinguishes `not written`, `fully written`, and `mismatch`. The target
empty precondition is evaluated before `STAGED`, so rollback has one honest baseline instead of a
second hidden backup generation.

Generation classification carries two independent proofs: `isEmpty` and `matchesArchive`. An empty
Room or all-absent preference archive can make both true; staging consumes `isEmpty`, while replay and
readback consume `matchesArchive`. Neither proof is inferred as the negation of the other.

### 4. Eligibility observation

Lifecycle owner: `CutoverEligibilityPort`; observations are not durable authorization. `INDETERMINATE` is failure-closed. A beginning observation cannot authorize the final `READY` publication.

### 5. Pairing history

Lifecycle owner after restore remains the existing `ProviderTrustStore`. Imported rows are history only; active approval can only be minted later through the existing operator approval path. A generic restore adapter is forbidden from exact-restoring an active pairing. Source capture may project active rows to historical rows, but target classification and readback always inspect the raw stored `revokedAt`; the source projection is never reused to hide active target trust.

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
- `CUT-INV-16`: for every legal policy, successful encode followed by decode yields the identical
  archive bytes and digest; a grammar-specific decoder limit cannot reject encoder output.
- `CUT-INV-17`: every text field is strict UTF-8 in both directions, while row keys/payloads remain
  opaque bytes; neither boundary performs replacement-character normalization.
- `CUT-INV-18`: each record type owns a checked line-length formula including its fixed grammar
  overhead; no record borrows a shorter sibling's bound.
- `CUT-INV-19`: archive/line/field/count limits are enforced before proportional allocation and all
  derived arithmetic is overflow-safe.
- `CUT-INV-20`: the schema-9 adapter accepts exactly the 18 owner tables and canonical column,
  foreign-key, and index descriptors; unknown/missing/drifted schema fails before row capture/restore.
- `CUT-INV-21`: target staging proves all 18 user tables empty and all five raw preferences absent;
  a non-empty product target is never overwritten or reclassified as an interrupted import.
- `CUT-INV-22`: one Room transaction and one DataStore edit are independently atomic; restart
  advances only after exact generation readback and mismatch moves to rollback-required.
- `CUT-INV-23`: every normal Room/DataStore reader and writer is admitted by the application gate;
  capture/restore exclusive ownership closes new admission and drains existing access first.
- `CUT-INV-24`: imported pairing rows are hashed and restored with non-null `revokedAt`; no target
  query can observe imported active trust even after `READY`.
- `CUT-INV-25`: blocked reads surface typed unavailability or wait; they never masquerade as an
  empty database or defaulted PlanConfig, and blocked writes have zero side effects.
- `CUT-INV-26`: durable restore phase is read only after exclusive ownership; a stale pre-lock read
  cannot roll back or overwrite a newer terminal generation.
- `CUT-INV-27`: an unconfirmed journal write or unreadable durable journal keeps admission recovery-
  closed, including when the attempted phase was terminal.
- `CUT-INV-28`: target pairing verification uses raw stored cells; source-only historical projection
  cannot make a target row with `revokedAt == null` classify exact.
- `CUT-INV-29`: target emptiness and archive equality are independent proofs and may both hold for an
  empty archive.
- `CUT-INV-30`: cancellation at drain wait, normal-token cleanup, or exclusive release cannot strand
  the gate in `DRAINING`/`EXCLUSIVE` or leak an active normal token.
- `CUT-INV-31`: normal rejection reason and identity are captured in the same lock acquisition that
  rejects admission; a concurrent exclusive release cannot turn the typed result into an exception.

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
| `CUT-A15` | legal small policy where an empty table header is longer than a row bound | encode→decode exact round-trip succeeds |
| `CUT-A16` | valid Base64URL wrapping malformed UTF-8, or isolated UTF-16 surrogate on encode | target text boundary rejects without replacement |
| `CUT-A17` | one blank line immediately before the digest | cursor rejects instead of treating the body as exhausted |
| `CUT-A18` | maximum legal table metadata, five preferences spanning absent/present-int/present-boolean, and valid non-ASCII text | exact bytes/digest round-trip; changing one target past its bound rejects for that target |
| `CUT-A19` | legal non-empty schema-digest baseline, then only that policy field becomes empty/blank | invalid policy/archive rejects before serialized output; decode continues to reject empty text |
| `CUT-A20` | schema-9 table/column/index/FK missing, extra, or drifted | adapter rejects before reading or deleting a row |
| `CUT-A21` | product target has one Room row or one raw preference before staging | import fails closed; existing target bytes remain unchanged |
| `CUT-A22` | crash before/after Room commit and before/after DataStore edit | retry recognizes only empty or exact generation; mismatch requires rollback |
| `CUT-A23` | new normal write races capture/restore while an admitted write is draining | new admission rejects; exclusive owner begins only after the admitted write completes |
| `CUT-A24` | process restarts in each non-ready journal phase | gate initializes closed before repository/service construction; resume/rollback is the only access |
| `CUT-A25` | active source pairing is captured and target reaches ready | source stays active; target row is historical with non-null `revokedAt`; active lookup returns none |
| `CUT-A26` | a production caller obtains a DAO/DataStore without the gate | static consumer census test fails |
| `CUT-A27` | caller A reads `STAGED`, caller B reaches `READY`, then A obtains exclusive ownership | A re-reads `READY`; no rollback or target clear occurs |
| `CUT-A28` | journal write commits then throws/cancels before confirmation | gate remains recovery-closed; a later exclusive retry re-reads durable state |
| `CUT-A29` | restored pairing row is mutated to raw `revokedAt == null` | target classification is non-exact and active lookup proves the trust violation |
| `CUT-A30` | archive Room is empty and all five preferences are absent | classification proves both empty and exact; coordinator completes without manufacturing rows/defaults |
| `CUT-A31` | cancellation while waiting for drain, cleaning a normal token, or releasing an exclusive lease | cleanup completes or reopens safely; no permanent gate/token leak |
| `CUT-A32` | blocked normal call races the exclusive owner's release | caller receives the typed rejection captured at admission; no state-change exception |

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
4. RED→GREEN the record grammar matrix: small legal policy, empty table, maximum table metadata,
   five-preference presence/type states, valid non-ASCII, malformed UTF-8, isolated surrogate,
   digest-preceding blank line, field-specific empty/minimum values, and each grammar-specific line bound. Every negative first proves its
   unchanged baseline succeeds and then mutates only the named target.
5. Re-run the exact tests in both flavors; expected result is all new V2 tests passing.
6. Commit only the codec and its tests.

### Task 3: Pure restore journal reducer (current phase)

**Files:**
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/cutover/CutoverRestoreProtocol.kt`
- Create: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/cutover/CutoverRestoreProtocolTest.kt`

1. Write failing transition tests for `CUT-INV-07..09`, `CUT-INV-11`, and `CUT-A06..10`.
2. Confirm RED on missing reducer/types.
3. Implement a pure reducer: exact legal edges, same-digest replay, different-digest conflict, failure-to-rollback, two eligibility checks, and `isVisible` projection.
4. Re-run exact tests in both flavors; expected result is all reducer tests passing.
5. Commit only the reducer and tests.

### Task 4: Auto-owned Room/DataStore integration (independent slice after codec acceptance)

**Files (owner to confirm):**
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/data/PlanConfigStore.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/AppDatabase.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/cutover/AutoCutoverSnapshotPort.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/cutover/AutoCutoverRestoreCoordinator.kt`
- Test: matching `cutover/**Test.kt` plus reader-bypass guards

Room v9 field/migration bytes are anchored at #79 implementation commit
`fb6284a7793e07333634d6fdf05a4688a6b8396c` (schema-freeze parent
`4116276b9435cc8dd269ff02b7ca3e1a1f09b3d7`), but that commit does not provide a cross-store seam.
#13 owns a new application-scoped coordinator with cutover mutex + durable generation/journal. It
must invoke AutomationService run cancellation/convergence, then fence every Room/DataStore writer
during capture and every normal reader during restore. The five raw keys require an absence-preserving
snapshot/restore API; mapped `PlanConfig` defaults are not a round-trip carrier. Before production
edits, add source-capture and target-restore state/owner/side-effect tables, a complete writer/reader
census, and crash/retry/concurrency RED tests. Do not duplicate #79 DAO/schema logic or treat its
review status as merged.

Implementation order for the first independently reviewable adapter/core slice:

1. Add pure RED tests for access-gate drain/exclusion/restart state, non-empty target rejection,
   crash readback classification, pairing-history projection, and typed blocked reads/writes.
2. Implement the capability-based `CutoverAccessGate`, durable-journal port, and coordinator core
   against fake Room/raw-preference ports; no Android UI or SAF code enters this slice.
3. Add Robolectric RED tests opening the production Room v9 schema and asserting the exact 18-table
   census, per-table descriptor digests, canonical row round-trip, FK insertion order, empty-target
   precondition, atomic rollback, and historical-only pairing readback.
4. Implement `RoomV9CutoverStore` over `SupportSQLiteDatabase` with schema introspection, typed binary
   rows, one capture transaction, one restore transaction, exact readback, and reverse-order clear.
5. Add DataStore RED tests proving raw absence survives capture and one atomic replace/clear touches
   exactly the five owned keys; implement those narrow methods in `PlanConfigStore` without using
   mapped defaults.
6. Wire `CellRebelAutoApp`, `AutomationService`, `MainViewModel`, `PlanRepository`, A+/recovery/trust
   adapters through the gate. Add the direct-consumer static guard plus reader/writer race tests.
7. Run only affected host tests in both flavors with `--max-workers=1`, then the risk-matched Auto
   host gate. Commit the adapter/core slice independently and request review for this new scope.

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
./gradlew :app:testLegacyIdDebugUnitTest --tests '*CutoverArchiveV2CodecTest*' --rerun-tasks --no-daemon --max-workers=1

JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/terry/Library/Android/sdk' \
./gradlew :app:testProductIdDebugUnitTest --tests '*CutoverArchiveV2CodecTest*' --rerun-tasks --no-daemon --max-workers=1
```

Equivalent commands apply to `CutoverRestoreProtocolTest`. No `connected*`, install, emulator, ADB, applicationId runtime mutation, or #106 regression command is part of this phase.

## Open questions

### Technical

1. Does productId v1 require the target to be empty, or must rollback preserve pre-existing productId state as a separate generation? Default until owner evidence says otherwise: fail closed unless target is empty.

Resolved implementation facts: no existing Auto lock spans Room plus raw PlanConfig; #13 owns the
application-scoped coordinator. The reader/writer census starts with `MainViewModel`,
`AutomationService`, A+/engine/recovery repository paths, and every direct `AppDatabase` or
`PlanConfigStore` entry. The immutable #79 field/migration anchor is
`fb6284a7793e07333634d6fdf05a4688a6b8396c`; its independent approval/merge remains external.

### Value

None. The operator has already selected legacyId SAF export → operator custody → productId SAF import, and has frozen all device operations for this phase.

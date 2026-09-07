---
feature_ids: [83]
topics: [qwy, audit, durability, host-baseline, fault-injection]
doc_kind: evidence_report
created: 2026-09-06
source_head: b013ff295f3a092a8a5f667ddb3d1201d5779c69
tips_exempt: Test-only host storage measurement; no user-visible capability or production behavior change.
---

# #83: production file backing — host baseline and exception matrix

## Verdict and scope

The real backing exhibits the whole-map rewrite growth described in
[issue #83](https://github.com/TERRYYYC/fakexxx/issues/83): at 1,024 audit rows,
the audit-only live file is **188,284 bytes**, but appending those rows wrote
**96,386,149 logical temp-file bytes**. A fixed 64 KiB unrelated payload in the
same backing adds **67,137,536** rewritten bytes over the same 1,024 appends.

The added host tests pass the covered transaction, exception/reopen and
reference-resolution cases. **This is not a storage scalability fix and does
not close #83.** No production source, storage policy, retention (TTL=0), caller
admission, FULL coverage rule, fingerprint allowlist, migration or Android
database was changed. No device, emulator, installation or device command was
used. Independent review and integration are owned by the coordinating task.

Base: `b013ff295f3a092a8a5f667ddb3d1201d5779c69`.
Isolated branch: `codex/issue83-host-baseline`.
Worktree: `/Users/terry/Desktop/coding/fakexxx-issue83-host-baseline`.

## Production path and measurement method

Source paths under `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/`:

- `integration/v1/ProviderRuntime.kt` constructs `FileDurableKv` in the app's
  `environment-control-v1` directory; provider namespaces share that backing.
- `integration/v1/IntegrationAuditStore.kt` assigns `__seq__` and `evt:<seq>`
  inside one `DurableKv.transaction`.
- `integration/v1/FileDurableKv.kt` builds the entire candidate map, writes the
  temp file, syncs it, renames it over the live file, then adopts memory state.
- `integration/v1/EnvironmentObserver.kt` appends the observation audit before
  returning the actual `qwy:audit:<seq>` evidence reference.

The two new tests are in
`apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/`:

- `AuditFileBackingMeasurementTest.kt`: real `DurableIntegrationAuditStore`
  plus `FileDurableKv`. Its override of the existing `writeTempFile` seam calls
  `super` and measures `target.length()`; the production fsync/rename path still
  runs. There is no alternate persistence algorithm or in-memory KV.
- `AuditFileBackingContractTest.kt`: real file backing, audit codec and resolver;
  exception injection uses that same existing seam. The returned-ref cases use
  the real `EnvironmentObserver` and `ContinuityTracker`, with a fake external
  environment and clock. They do not exercise Android Binder or authorization.

Measurement parameters:

- Host reports `Mac OS X 26.6.2`, `aarch64`, JBR `21.0.10+-117844308-b1163.108`.
- JUnit `TemporaryFolder`; a 16-append warmup in a separate directory.
- One sequential growing stream for each scenario; checkpoints 64, 128, 256,
  512 and 1,024 rows. Fixed synthetic caller/lease/digest strings and a six-digit
  operation suffix; this is not a sampled production workload.
- The shared scenario seeds a 65,536-byte ASCII value in another namespace;
  its seed transaction is excluded. Serialization adds 28 bytes, so every
  subsequent audit append rewrites 65,564 additional bytes.
- `System.nanoTime()` surrounds each append, including measurement/list
  bookkeeping. Reopen/assertions and console output occur outside the measured
  interval. p50/p95 use nearest-rank over **that interval's** appends, not over
  all rows. These timings are descriptive only: there is no latency threshold.
- At every checkpoint, one commit per append and the last temp/live byte count
  are checked. A fresh `FileDurableKv` parses the live file and resolves every
  event in order; the unrelated payload is also checked unchanged.

## Recorded results

Full QWY host-suite run, measurement timestamp `2026-09-06T19:34:11.298Z`.
Byte columns are exact logical file bytes; time columns are milliseconds.

| Scenario | Rows | Cumulative temp bytes | Live bytes | Interval rows | Interval total ms | p50 ms | p95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| Audit only | 64 | 379,519 | 11,662 | 64 | 271.376 | 4.009 | 6.202 |
| Audit only | 128 | 1,505,346 | 23,369 | 64 | 265.401 | 4.038 | 4.992 |
| Audit only | 256 | 6,015,682 | 46,921 | 128 | 540.021 | 4.068 | 5.983 |
| Audit only | 512 | 24,080,322 | 94,025 | 256 | 1,058.630 | 4.070 | 4.849 |
| Audit only | 1,024 | 96,386,149 | 188,284 | 512 | 2,162.769 | 4.104 | 5.890 |
| Shared 64 KiB | 64 | 4,575,615 | 77,226 | 64 | 273.922 | 4.073 | 6.075 |
| Shared 64 KiB | 128 | 9,897,538 | 88,933 | 64 | 267.925 | 4.119 | 5.744 |
| Shared 64 KiB | 256 | 22,800,066 | 112,485 | 128 | 534.309 | 4.083 | 5.796 |
| Shared 64 KiB | 512 | 57,649,090 | 159,589 | 256 | 1,047.449 | 4.083 | 4.782 |
| Shared 64 KiB | 1,024 | 163,523,685 | 253,848 | 512 | 2,160.952 | 4.127 | 5.827 |

An earlier targeted run at `2026-09-06T19:33:12.535Z` produced identical byte
counts at all checkpoints. Its 513–1,024 interval p50/p95 values were
4.100/5.214 ms (audit only) and 4.092/5.844 ms (shared). Both runs exercised
2,048 measured appends plus warmup, rather than reusing Gradle's cached result.

Interpretation:

1. From 256→512→1,024 rows, audit-only cumulative bytes increase approximately
   fourfold on each doubling. This matches the source-level sum of growing
   whole-map rewrites, with small row-number encoding effects.
2. The shared payload's extra rewritten bytes equal `65,564 * rowCount` at
   every checkpoint; unrelated namespaces contribute to audit append cost.
3. The measured p50 stays near 4 ms at these small host scales. This run does
   **not** establish a latency cliff, a safe capacity, Android latency, flash
   wear or an acceptable production append rate. Logical bytes exclude file
   metadata, journaling and physical storage writes. The 1,024-row ceiling is
   a bounded baseline, not a long-running soak or a maximum-growth test.

## Fault and resolver coverage

| Case | Executed scenarios | Result |
|---|---:|---|
| Exception after buffered `__seq__`, before event creation | Empty and 3-row prefix | No returned event; memory and live file retain the prefix; reopen and retry are contiguous |
| Outer transaction abort after nested append buffered its event | Empty and 3-row prefix | Outer call returns nothing; both keys roll back together |
| Exception before temp write | Empty and 3-row prefix | Same rollback and reopen checks |
| Exception after partial temp write | Empty and 3-row prefix | Orphan temp does not become live state; reopen and retry preserve prefix |
| Exception after full temp write, before fsync/rename | Empty and 3-row prefix | Same rollback and reopen checks |
| Observer audit write failure | Before/partial/after temp write | Actual observer throws and returns no ref; previously returned ref remains resolvable |
| Successful observer return | One real returned observation | Fresh backing resolves its ref to exact caller, lease, operation and observation digest; coverage remains NONE |
| Contiguous/free-field/retention round-trip | Null, empty, delimiter/newline/Unicode; clock +365 days | All rows and exact fields survive; future seq returns null; nonpositive seq is rejected |
| Assigned interval gap | Rows 1 and 3 with max 3 | `resolve(2)` and `all()` throw; valid endpoints resolve and future 4 returns null |
| Assigned malformed or wrong-seq event | Two persisted corruption fixtures | Exact resolver and interval read throw after reopen |
| Concurrent append, single owner | 64 appends on four threads sharing one FileDurableKv | Returned seq is exactly 1…64 with distinct operations; all survive fresh reopen |

These are **caught exception + fresh object reopen** tests, not process-kill or
power-cut tests. They do not inject an error during fsync, failed rename,
post-rename/pre-reply loss, or directory sync. `FileDurableKv.syncDirectory()`
is best-effort, so the tests cannot certify power-loss durability across Android
versions/filesystems. Concurrent access here is through one owner object; the
class explicitly does not support multiple writer processes/instances.
Nested transactions join the outer transaction: an inner append result is not
a durability acknowledgment before the outer commit.

No new production defect was reproduced in these covered cases. The known
whole-map growth remains; the matrix is not an exhaustive corruption proof.

## Reproduction and quality gate

Working directory:
`/Users/terry/Desktop/coding/fakexxx-issue83-host-baseline/apps/qianwangyou`.
Run only host/build tasks; do not substitute connected/install tasks.

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ANDROID_HOME='/Users/terry/Library/Android/sdk' \
  ANDROID_SDK_ROOT='/Users/terry/Library/Android/sdk' \
  ./gradlew :app:testDebugUnitTest --tests '*AuditFileBacking*' --no-daemon

env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ANDROID_HOME='/Users/terry/Library/Android/sdk' \
  ANDROID_SDK_ROOT='/Users/terry/Library/Android/sdk' \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

To force a new measurement when unchanged inputs would be `UP-TO-DATE`, add
`--rerun-tasks` to the targeted command. This also recompiles its dependencies.
Read generated CSV in `app/build/test-results/testDebugUnitTest/` under
`TEST-name.caiyao.fakegps.integration.v1.AuditFileBackingMeasurementTest.xml`.
JUnit removes test directories; XML/HTML under `app/build/` are generated local
evidence, not committed artifacts. This report preserves the recorded numbers.

- Initial harness compilation failed because it referenced a private companion
  file-name constant. The tests now use the verified on-disk name without
  changing production visibility. That compile error is **not** a production
  regression RED. This delivery characterizes existing behavior; it does not
  implement a production Red→Green fix.
- Targeted rerun: **8 tests, 2 suites, 0 failures/errors/skips**, exit 0,
  `BUILD SUCCESSFUL in 19s`.
- Full QWY JVM suite + debug build: **895 tests, 133 suites, 0 failures/errors/
  skips**, exit 0, `BUILD SUCCESSFUL in 23s`. This is not the full repository
  release gate; Auto/device lanes were not run for this test-only slice.
- Existing Gradle deprecation/Kotlin warnings and debug native-symbol stripping
  warning remain; no validation task failed in the successful runs.
- `git diff --check`: clean. Test-only plus this report; no UI/design changes,
  root media, production architecture delta or new fallback layer. The
  Clowder-specific pnpm/hotfix/architecture gate scripts are not present in this
  Android repository. Architecture cell: existing QWY integration audit;
  map delta: none. Dogfood exemption: pure host tests/evidence, no user-facing
  behavior; the storage path itself was nevertheless executed here.
- Five-axis change risk: behavior=tests only, data=temp fixtures only,
  security=no change, contract=no change, irreversible=none. The tested surface
  is durability-sensitive, hence the actual full QWY JVM suite and build above.

## Remaining #83 close conditions

The original issue still requires representative Android measurements and
device soak, a separately reviewed storage/admission decision that preserves
TTL=0 and exact evidence identities, matching migration/crash coverage, and
the complete integration/recovery/repository gates. None is silently waived by
this host baseline. No new backing is selected in this change.

Next action for the coordinator: obtain independent review of these tests and
numbers, integrate the bounded host evidence, then assign the remaining #83
design/device work explicitly. Device access remains frozen until the device
owner releases it; that does not invalidate the completed host measurement.

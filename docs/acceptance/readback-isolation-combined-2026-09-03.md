---
feature_ids: [G2-66, G2-71]
topics: [android, readback, config-transport, codex-bench, evidence]
doc_kind: acceptance
created: 2026-09-03
status: verification-in-progress
base_commit: 4192f411b2cf741990041bdf206ce3101be8582f
---

# Independent readback and codexBench isolation — combined evidence

## What this delivery establishes

The user asked to continue the staged plan toward a working phone application, using
non-colliding codex-bench identities. This slice repairs two verification prerequisites:
source-specific readback failures no longer disappear into an unexplained empty result,
and codexBench no longer deliberately installs this module's generic spoof hooks in
its ordinary self processes. The deliberately hooked private probe remains eligible.

The configuration investigation also reproduces a misleading platform-cache sequence:
WORLD_READABLE first throws, a private fallback is cached, and a subsequent request can
return that same private instance without throwing. Real production publication still
returns false, even after the local payload changes. No success condition was weakened.

**Not established:** a framework-enabled different-UID configuration read, independence
from every other active module, positive GPS/network delivery on Moto, continuity/FULL,
or business completion. This phase did not operate Moto, merge a PR, or close #66/#71.

## Sources and ownership

- Plan: [implementation plan](../../feature-specs/2026-09-03-readback-and-config-isolation-plan.md).
- Lane A: [readback diagnosis and RED/GREEN](../bug-report/system-mock-readback-diagnostics/bug-report.md),
  commit `ebb531e655a228f5ec33980edbc3d51169360378`.
- Lane B: [isolation validation](config-transport-isolation-2026-09-03.md) and
  [configuration mechanism/positive recipe](../bug-report/config-transport-readiness/bug-report.md),
  original commit `10b10abcd5c0138e0895193ac91f4a6a9ef068b3`.
- Combined implementation: `623dac4f087506e9f54541e88a379296178754e1`, including the
  coordinator's actual-codexBench CI step and report retention.
- Branch/worktree: `codex/location-readback-diagnostics`,
  `/Users/terry/Desktop/coding/fakexxx-location-readback`.
- This is based on #72, not main: main lacks the reader introduced by #65. The other
  team's main-based #73 identity lift remains separate; this delivery does not own its review.
- Scope notice: https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5526416219.

## Combined host verification at 623dac4

All commands used the repository wrappers, JDK
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`, and SDK
`/Users/terry/Library/Android/sdk`. Raw logs and command/HEAD/exit/timestamp metadata are
in `/tmp/fakexxx-readback.yazTyt/`; these local temporary files are not permanent remote storage.

| Check | Result | Raw receipt |
| --- | --- | --- |
| `bash scripts/verify-a-plus.sh --stage full` | 12/12 repository gates, exit 0 | `combined-full-gate.log`, `.meta` |
| Full debug unit suite | 1,042 tests; 0 failures/errors/skips | `combined-variants-apks.log`, `combined-junit-counts.log` |
| Full codexBench unit suite | 1,042 tests; 0 failures/errors/skips | same |
| Full release unit suite | 1,006 tests; 0 failures/errors/skips | same |
| Full Auto/QWY host integration | 25 tests; 0 failures/errors/skips | full-gate log and JUnit counts |
| QWY debug/codexBench/release/androidTest builds | all successful, exit 0 | `combined-variants-apks.log`, `.meta` |
| Both codex-bench APK identities/signers | passed | `combined-apk-identity.log`, `.meta` |

The aggregate host receipt explicitly retains `issue66Ac7=NOT_PASSED`,
`deviceFull=BLOCKED`, and `overall=BLOCKED`. Those fields describe the product/device
gate, not a failing host build. Its `emulator=NOT_RUN` describes that host lane, not the
separate bounded Android regression below. The existing QWY lint error debt remains;
the ratchet passed, not a claim that raw lint is clean.

The combined Gradle invocation (QWY directory) was:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:testCodexBenchUnitTest \
  :app:testReleaseUnitTest :app:assembleDebug :app:assembleCodexBench \
  :app:assembleRelease :app:assembleDebugAndroidTest
```

There are no test filters in this invocation. XML suite counts, not Gradle task counts,
provide the totals. The 36-case difference in release is the documented debug-only
probe/test source set. The four migrated files were byte-identical, not removed tests.

## Clean Android regression at 623dac4

The coordinator created **new** AVD `codex_readback_clean_api35`, serial
`emulator-5584`, using API 35 Google APIs arm64-v8a revision 9, Emulator 36.6.11.
Every command recorder first verifies the AVD name, then executes with that explicit
serial. No auto-selecting `connectedAndroidTest`, global adb restart, or phone command.

Raw receipts: `/tmp/fakexxx-readback-clean.kTIjCq/`. Each label has `.meta`, `.avd`,
`.stdout`, `.stderr`; metadata includes command, UTC interval and actual shell exit.
Fingerprint: `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
The new baseline package list was empty; GPS/network/passive/fused last locations were null.

| Group | Conditions and result | Receipt |
| --- | --- | --- |
| New readback + config tests | Fresh target, location permissions denied; `OK (4 tests)` | `new-four-623dac4` |
| Existing complete instrumentation classes | Clear owned app data; grant coarse/fine and mock AppOp to QWY only; exclude the four tests requiring denied permissions; `OK (9 tests)` | `old-nine-623dac4` |
| Installed bytes | Exact debug/test hashes matched before and after both groups | `installed-identities-623dac4`, `after-tests-installed-hashes` |

This is 13 passing cases in **two intentionally different permission setups**, not a
single all-permissions suite run. `am instrument` can exit 0 on a failed test; both JUnit
`OK (...)` and TestRunner's zero-failure lines were checked.

Observed evidence, not inferred positive behavior:

1. Real `LocationManager` with permissions denied produces GPS and network
   `CACHE_QUERY_FAILED / SECURITY`, emitted by the actual Android logger. The second
   reader test maps a genuine Android `Location` fixture, not a live provider-cache positive.
2. The first fresh WORLD_READABLE call throws; private fallback commits; the retry is
   the **same real private preferences instance**. The production publisher commits local
   A then B, but reports false both times and persists its failure outcome in XML bytes.
3. The original remote Binder identity tests still pass with different actual UIDs
   (QWY 10207, test caller 10208 for this install). Private-provider payload matches;
   verification remains `3` (`NONE`), not a configuration-transport positive.
4. The real controller now emits source-specific `NO_SAMPLE` during that bounded
   apply/observe/release test. That immediate regression is not a timed delivery test.

Selected coordinate-free runtime lines:

```text
origin=INTEGRATION source=gps status=CACHE_QUERY_FAILED enabled=true mock=unknown source_elapsed_ms=unknown publish_anchor_ms=1000 freshness=UNASSESSED failure=SECURITY reader_failure=none
origin=INTEGRATION source=network status=CACHE_QUERY_FAILED enabled=true mock=unknown source_elapsed_ms=unknown publish_anchor_ms=1000 freshness=UNASSESSED failure=SECURITY reader_failure=none
firstWorld=SecurityException fallbackCommit=true retrySamePrivateInstance=true
publisherFresh=false publisherCached=false changedLocalPayload=true durableFailure=true
```

## Artifact boundary

These are the combined 623dac4 local build bytes, not Moto-installed artifacts:

| Artifact | SHA-256 |
| --- | --- |
| QWY debug | `7cf028da759419d7024f4e2db4c162ef1048b3d67dfba5b9647f3692724e8a13` |
| QWY debug androidTest | `acd75ea35830d0e7a7aea969daa1c509c07cafcecf676df21f18ba3e7d066c0e` |
| QWY codexBench | `c9488492d08e487993eacfa243cc4f031e91607b6449d8d85267ab8e7d28d6d8` |
| QWY release | `11f44fe7bd6a746f554d73df4d411da422be6d34bb40e9386eef5495d17386b1` |
| Auto codexBench | `304efe65e27f246c55cd2d36e5a5ce148acbe0484d7dec2732de65980cd1e9f1` |

Both codex-bench APKs retain signer
`7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41`, isolated package IDs
`name.caiyao.fakegps.codexbench` / `com.example.cellrebelauto.codexbench`, and labels
`千网游 · codex-bench` / `CellRebel Auto · codex-bench`.

## Discarded environment and evidence hygiene

Earlier owned AVD `codex_readback_api35` / `emulator-5582` exited before coordinator
installation. A separately running shell was observed selecting the first emulator,
with a subsequent kill operation; this demonstrates a collision risk, **not proof of
which process caused the exit**. After restarting the same userdata, package inspection
found project APKs not installed by this workflow. Their first-install time was 16:30:54
guest local, before the restart: do not invent a second post-restart installation event.

That environment supplies **no passing-test evidence in this report**. Its userdata and
logs were preserved, the coordinator stopped only its own 5582, and a different fresh
AVD/userdata was created for the tests above. No unknown app data or other team's AVD
was cleared. Original receipts are `/tmp/fakexxx-continuation-avd.MbDNLf/`.
Coordination notices: https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5526676606
and https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5526763474.

## Review and remaining workflow

The independent non-author `/root/readback_isolation_review` reran A's 26 targeted
tests and 6 adapter tests, then combined codexBench/release 29 each and debug 74, all
passing. It read the actual full-gate metadata and retained the device/FULL boundary.

Its P2 consumer sweep found misleading UI claims: REAL_BASELINE was rendered as
“本机真实值”/“真实”, and codexBench was described as a release build. The code's internal
scope comment was correct, but its user-visible consumers were not. This finding is
being fixed with regression coverage and actual UI verification; no final approval or
phase-completion claim is made by this intermediate section.

Before final publication: resolve that P2, verify final artifacts/UI and cleanup receipts,
obtain the non-author final verdict, and publish the draft handoff. Future framework
positive work must use an exact compatible framework and a genuine different-UID
fresh A→B→cold-restart-B reader. Same-UID `:hook_verify` and writer-side file reads are
not substitutes. Keep #66 and Moto outcomes explicitly unpassed until their own evidence exists.

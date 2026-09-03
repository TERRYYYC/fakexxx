---
feature_ids: [G2-66, G2-71]
topics: [android, readback, config-transport, codex-bench, evidence]
doc_kind: acceptance
created: 2026-09-03
status: verified-draft-handoff
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
- Final implementation: `6691bcb00305615676ef8d6856561ba3c23d61ab`, including the
  coordinator's actual-codexBench CI step and the P2 presentation correction. Initial
  combined verification also passed at `623dac4f087506e9f54541e88a379296178754e1`.
- Branch/worktree: `codex/location-readback-diagnostics`,
  `/Users/terry/Desktop/coding/fakexxx-location-readback`.
- This is based on #72, not main: main lacks the reader introduced by #65. The other
  team's main-based #73 identity lift remains separate; this delivery does not own its review.
- Scope notice: https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5526416219.

## Final combined host verification at 6691bcb

All commands used the repository wrappers, JDK
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`, and SDK
`/Users/terry/Library/Android/sdk`. Raw logs and command/HEAD/exit/timestamp metadata are
in `/tmp/fakexxx-readback.yazTyt/`; these local temporary files are not permanent remote storage.
Selected unmodified host/device receipts and actual final screenshots are also committed in
[the evidence directory](evidence/readback-isolation-2026-09-03/README.md), so another team
does not need access to these temporary directories to inspect the principal results.

| Check | Result | Raw receipt |
| --- | --- | --- |
| `bash scripts/verify-a-plus.sh --stage full` | 12/12 repository gates, exit 0 | `final-full-gate.log`, `.meta` |
| Full debug unit suite | 1,046 tests; 0 failures/errors/skips | `final-variants-apks.log`, `final-junit-counts.log` |
| Full codexBench unit suite | 1,046 tests; 0 failures/errors/skips | same |
| Full release unit suite | 1,010 tests; 0 failures/errors/skips | same |
| Full Auto/QWY host integration | 25 tests; 0 failures/errors/skips | full-gate log and JUnit counts |
| QWY debug/codexBench/release/androidTest builds | all successful, exit 0 | `final-variants-apks.log`, `.meta` |
| Both codex-bench APK identities/signers | passed | `final-apk-isolation.log`, `.meta` |

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
The timestamped full-debug count predates a subsequent targeted host-gate invocation,
which replaced the live debug XML directory with its 53-case subset. Do not cite that
later live directory as the preserved original 1,046-case report.

## Android regression on the clean-owned AVD

The coordinator created **new** AVD `codex_readback_clean_api35`, serial
`emulator-5584`, using API 35 Google APIs arm64-v8a revision 9, Emulator 36.6.11.
Every command recorder first verifies the AVD name, then executes with that explicit
serial. No auto-selecting `connectedAndroidTest`, global adb restart, or phone command.

Raw receipts: `/tmp/fakexxx-readback-clean.kTIjCq/`. Each label has `.meta`, `.avd`,
`.stdout`, `.stderr`; metadata includes command, UTC interval and actual shell exit.
Fingerprint: `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys`.
The new baseline package list was empty; GPS/network/passive/fused last locations were null.
The coordinator first tested 623dac4, then upgraded its own APKs to final 6691bcb and
reran both groups below. Initial labels ending `623dac4` are retained separately; the
latest labels ending `final` and final installed-byte hashes are authoritative here.

| Group | Conditions and result | Receipt |
| --- | --- | --- |
| New readback + config tests | Cleared owned target data, location permissions denied; `OK (4 tests)` | `new-four-final` |
| Existing complete instrumentation classes | Clear owned app data; grant coarse/fine and mock AppOp to QWY only; exclude the four tests requiring denied permissions; `OK (9 tests)` | `old-nine-final` |
| Installed bytes | Exact final debug/test/codexBench hashes matched local builds | `final-installed-hashes` |

This is 13 passing cases in **two intentionally different permission setups**, not a
single all-permissions suite run. `am instrument` can exit 0 on a failed test; both JUnit
`OK (...)` and TestRunner's zero-failure lines were checked.
`final-tests-log.stdout` retains both runs; the final new-four process is PID 4951 at
16:59:09 guest local, and final old-nine process is PID 5124 at 16:59:28–29. Do not mix
earlier 16:49/16:50 entries into final-run assertions.

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

These are the final 6691bcb local build bytes, not Moto-installed artifacts:

| Artifact | SHA-256 |
| --- | --- |
| QWY debug | `e8ae87dfd729471c253ddac4d1eb1c3f68dda0b7f1559b1cb5b57bf5f98b4b53` |
| QWY debug androidTest | `acd75ea35830d0e7a7aea969daa1c509c07cafcecf676df21f18ba3e7d066c0e` |
| QWY codexBench | `208898ce2bad69f735dab0883f0e591413b9038b30d5d98d3ec1e88ca9e55075` |
| QWY release | `d6f4493dcf6c823e60f813e0a1b9b300a71b5520ec10c358b0506482798f73da` |
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

## Independent review and actual UI

The independent non-author `/root/readback_isolation_review` reran A's 26 targeted
tests and 6 adapter tests, then combined codexBench/release 29 each and debug 74, all
passing. It read the actual full-gate metadata and retained the device/FULL boundary.

Its P2 consumer sweep found misleading UI claims: REAL_BASELINE was rendered as
“本机真实值”/“真实”, and codexBench was described as a release build. The code's internal
scope comment was correct, but its user-visible consumers were not. Fix 6691bcb centralizes
only presentation strings, updates both pages and the related comment, and leaves enum,
trust and value-routing logic unchanged. **Why:** lack of this module's self-hook is not
proof of physical truth. The mapper's old copy produced 5 behavioral failures in 9
targeted codexBench cases; final full suites passed after correction. Raw RED/GREEN is
in `/tmp/fakexxx-scope-copy.RL38JT/`. The coordinator additionally opened the old actual
codexBench Verify page and captured its erroneous assertion in `verify-red.png` and
`read-verify-red.stdout` before installing final bytes.

The non-author independently reran the final 6691bcb codexBench targeted suite: 20/20,
exit 0. With exclusive ownership of 5584's UI window, it opened the final Verify ScopeCard
and the unsaved Editor's operator-name TextField and roaming BooleanField reference labels.
All three rendered the bounded baseline wording and wrapped normally. It returned to Map
without entering/saving a profile, granting permissions, changing mock state or operating
another serial. The coordinator then personally viewed the same three screenshots:

- [Verify ScopeCard](evidence/readback-isolation-2026-09-03/ui/verify-final.png)
- [Editor text reference](evidence/readback-isolation-2026-09-03/ui/editor-text-final.png)
- [Editor boolean reference](evidence/readback-isolation-2026-09-03/ui/editor-boolean-final.png)

Original UI receipts are `reviewer_*` in the clean raw directory (dumps 03/13/15/16 and
corresponding screenshots); the independent workspace is `/tmp/fakexxx-review.lA1Umk/`.
The unconfigured Verify page had no field rows; their wiring is code/test evidence, not
a claim of observing configured Verify field rows. The initial difficulty selecting a
map point resolved with the existing UI; there is no reproduced map bug in this evidence.
The P2 is closed; no open P1/P2 remains. The [reviewer-owned final report](evidence/readback-isolation-2026-09-03/review-final.md)
gives **APPROVE for this scoped change** at exact code HEAD 6691bcb. This is a local
non-author review, not a GitHub approval by the shared author account.

## Cleanup and device boundary

After the reviewer returned its exclusive UI window, the coordinator cleared only the
three packages installed by this run: `name.caiyao.fakegps.bench`, `.bench.test`, and
`.codexbench`. All three `cleanup-clear-*` receipts say `Success`. The mock AppOp is
`default`; debug coarse/fine location permissions are false. There are no remaining
project processes or services in the final snapshots. Installed APKs and the owned AVD
userdata were retained; only disposable test app data was cleared, not another team's data.

`cleanup-location-after` shows GPS/network native identities and null last locations,
with mock overrides already removed. **Passive/fused caches still retain the fixture**:
this is not an assertion that every location cache was cleared. The final isolation boundary
is stopping the owned AVD. `cleanup-stop-owned-avd` checked its name and returned exit 0;
managed session 8533 then exited 0. `emulator.log` records normal shutdown, and the subsequent
host process snapshot contains neither that AVD nor its emulator PID 47575.
The non-author independently witnessed these raw receipts. No global adb action, other
emulator shutdown, Moto operation or framework installation was performed by this cleanup.

## Plan disposition and handoff

| Criterion | Evidence / disposition |
| --- | --- |
| AC1 classified read errors | Source-scoped behavioral RED/GREEN; actual Android permission-negative logs |
| AC2 preserved decision semantics | Old trust/wiring regressions plus all three full variant suites |
| AC3 sanitized diagnostics | Adversarial formatter/sink tests; inspected real coordinate-free logger lines |
| AC4 actual consumers | Construction-site guards, real Android adapter and controller runtime output |
| AC5 codexBench isolation | Actual-variant/compiled wiring tests, mutation checks, APK checks and corrected UI |
| AC6 config prerequisite report | Real platform-cache/publisher negatives; exact next positive recipe below |
| AC7 independent review | Non-author exact 6691bcb code review and reruns; actual UI P2 closed |
| AC8 shareable delivery | Reports and selected receipts in this draft branch; remote publication/check state belongs to the PR |

All production code/test/build changes are frozen at 6691bcb; later commits only record
review, evidence and handoff. The PR remains stacked on #72 and draft. These criteria concern
this prerequisite slice, not the product's #66 finish line. No merge or issue closure.

### Concrete next framework-positive run (not executed)

Use an exact compatible framework in another owned isolated emulator. Scope the module to
the genuine different-UID **Auto codexBench** target. The existing production path can
exercise the proof without a new harness: SettingsViewModel's refresh-interval setter
publishes through ConfigPrefsSync; MainHook in the Auto process creates
`XSharedPreferences(QWY BuildConfig.APPLICATION_ID, "spoof_config")`, reloads and reads it.

1. Publish A with refresh interval 5 seconds through QWY's UI. Require writer success
   (`published/readable/commit/outcomeDurable=true`) and fingerprint A, plus target-process
   receipt of A, `scheduler_owned ... intervalMs=5000`, then `fingerprint and hour unchanged (A)`.
2. Publish B with interval 10 seconds. Require a different writer fingerprint B, the same
   Auto PID accepting B, `interval_changed ... fromMs=5000 toMs=10000`, then `unchanged (B)`.
3. Without rewriting QWY, stop/restart only Auto. Record its new PID and unchanged UID,
   still different from QWY's UID. Require B again, 10-second scheduler ownership and the
   subsequent B fingerprint check in the new target process.

Code anchors: [production setting](../../apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/ui/screen/settings/SettingsViewModel.kt),
[writer](../../apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt),
[target reader/accepted snapshot](../../apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/MainHook.java),
and [scheduler evidence](../../apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/verify/RuntimeEvidence.kt).
Each group must retain OS PID/UID/process attribution. `transport accepted` alone occurs
before fields/unavailable validation and is insufficient. If framework log aggregation loses
target attribution, the run still lacks a target-owned receipt and cannot pass.
Do not substitute same-UID `:hook_verify`, writer-side reads, Auto's Binder handshake, or
the old `refresh_latency_probe.py` (which directly rewrites files and bypasses the writer).
Even a successful transport run would not prove a target public API's spoofed value or #66 FULL.
Independently unhooked positive raw readback and separately coordinated Moto acceptance remain.

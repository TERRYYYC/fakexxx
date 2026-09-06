---
feature_ids: [66]
topics: [qwy, oracle, wiring, host-test]
doc_kind: bug_report
created: 2026-09-06
---

# Current-main oracle wiring: host-only slice

Reporter: Codex isolated `qwy_oracle_wiring` execution assistant, from coordinator handoff.

| Diagnosis field | Evidence / plan |
| --- | --- |
| Symptom | Current candidate has a consumer and UID-1000 registrar but no system-server producer entry. It cannot obtain authoritative continuity. |
| Evidence | Base `acff79dc38801f755965817acd5a018c9c87686e`; `MainHook.handleLoadPackage` goes directly to generic self-hook policy; no `hook/oracle` production directory or system scope resource. |
| Root cause | Producer/installer files from draft #68 were not adapted into the current-main successor. A consumer alone cannot establish its source. |
| Strategy | Guard the real entry/manifest boundary; adapt only producer files from #68 `8c8bd250513a6c8284101152eeec99d8d26f46b3`; exercise the production lifecycle state on the host independently of Android Binder transport. |
| Timebox | If the source extraction pulls old Auto/provider state into scope, stop and report the exact dependency instead of importing that stack. |
| Warning | Static existence is not runtime coverage. No installed hook, build attestation, or writer coverage may be inferred from these host tests. |
| Visible effect | No new production FULL: the attested build allowlist remains empty. Device operations are frozen. |
| Acceptance | Observed entry guard RED; producer lifecycle/registration/death/missing-coverage tests and full QWY host suite GREEN; build-only APK compilation. Exact-build device evidence remains absent. |

## Remaining #66 work

This slice does not implement all QWY semantic writers, ACK/revision/audit atomicity, raw-location reader and publish-anchor adaptation, exact-build hook attestation, or device acceptance. It does not close #66.

## Implemented slice and provenance

- `MainHook` branches on exactly `android` package / `android` process before generic spoof hooks and returns after the oracle installer. Normal app hook code is unchanged.
- The module advertises the legacy system scope. This is APK metadata, not a device scope/configuration change.
- The existing explicit registrar and consumer are reused. Registrar callers remain UID 1000 only; producer snapshots and QWY sessions independently require the resolved QWY UID.
- `Android15OracleHookPlan`, `SystemServerOracleEntryPolicy`, `LocationSemanticChangePolicy`, `QwyCoveredMutationAttributionPolicy`, `OrderedCoveredMutationFinisher`, and `SystemServerOracleInstaller` are exact file extractions from #68 at `8c8bd250513a6c8284101152eeec99d8d26f46b3`. No old provider or Auto stack was imported.
- The old `SystemServerOracleBinder` state machine is extracted into `SystemServerOracleState`. The actual Binder delegates to this state owner, with narrow caller-identity, endpoint-read, and session-death seams. `AndroidOracleEndpointReader` retains the old platform sampling logic outside the state lock. Host tests execute this same state owner, not an alternative oracle fake.
- Deliberate adaptation: registering a session proves its generation only. It does **not** set `COVERAGE_QWY_SEMANTIC_SESSION`, because current-main writers are not yet all bracketed. The producer cannot become complete merely because a caller supplies a digest and a death token.
- The fingerprint allowlist remains empty. The Binder factory independently checks it in addition to the installer gate. No production FULL is enabled; future attestation and complete writer coverage require independent review and evidence.

## Verification (2026-09-06, host only)

Worktree: `/Users/terry/Desktop/coding/fakexxx-oracle-wiring-rescue`; Gradle working directory: its `apps/qianwangyou`.

All Gradle commands below used `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`, `ANDROID_HOME=/Users/terry/Library/Android/sdk`, `ANDROID_SDK_ROOT=/Users/terry/Library/Android/sdk`. Only JVM test and build tasks were requested; there was no adb, emulator, install, connected test, or device query.

| Command / state | Observed result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest --tests '*CurrentOracleWiringTest' --no-daemon`, before production changes | RED: 2 tests / 2 failures; missing system-server branch and missing scope metadata; compilation completed, so these were assertion failures, not environment errors. |
| `./gradlew :app:testDebugUnitTest --tests '*SystemServerOracleStateTest.registration alone*' --no-daemon`, temporarily retaining the old automatic semantic coverage grant | RED: 1 test / 1 failure. With the fixture's platform hooks present, registration incorrectly returned HEALTHY instead of HOOKS_INCOMPLETE. The temporary grant was then removed. |
| `./gradlew :app:testDebugUnitTest --no-daemon`, final implementation | GREEN: 131 suites, 881 tests, 0 failures, 0 errors; BUILD SUCCESSFUL in 13s. Includes 30 new/ported hook-oracle tests and the pre-existing bridge/consumer/domain suite. |
| `./gradlew :app:assembleRelease --no-daemon` | GREEN: BUILD SUCCESSFUL in 46s, 91 tasks executed; includes R8 and lint-vital, not a full lint-debt claim. APK remained on the host. |
| `git diff --check` | GREEN. |

Lifecycle behavior tests cover resolved-caller rejection, UID-1000 registry admission, producer → exact map codec → actual consumer adapter rejection of missing coverage, registry absence, successful generation registration, death during registration, death/disconnect de-duplication, away/restore cursor increments, no-op semantic stability, and failed completion-barrier poisoning. The direct system callback hooks, Android Binder transaction/death transport and platform endpoint reads are build-checked only; these tests are not device proof.

## Quality gate / handoff boundary

- Original requirement: #66 authoritative history before trusted quota. This is the coordinator-assigned wiring slice, **not** an issue close or production-readiness claim. All original #66 device ACs remain open.
- Architecture cell: `fakexxx::android-dual-app-contract`; ownership delta: existing system-server producer is now wired through its explicit Android transport into one testable in-process state owner. No second persistent store or authority is added.
- Risk: behavior and security/authority are high; data migration is unchanged; private AIDL/Bundle wire shape is unchanged; irreversible operations were not performed. Exact-head independent review is required before integration.
- Dogfood: actual device execution is prohibited by DEVICE FREEZE. Host lifecycle/codec/consumer execution and release compilation reach the permitted boundary only; missing device evidence is not waived.
- No UI design change or `.pen` file; no root media artifacts. The cat-cafe-specific pnpm/hotfix/fallback/architecture scripts are not present in this external Android repository, so they were not invented or run.
- No push, merge, tag, release, or issue closure is included. The coordinator receives the local commit for independent review and integration planning.

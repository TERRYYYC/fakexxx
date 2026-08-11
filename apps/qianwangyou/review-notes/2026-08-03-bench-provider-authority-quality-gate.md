---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - quality-gate
  - android-content-provider
  - active-profile-publication
doc_kind: quality-gate
created: 2026-08-03
---

# Quality Gate: bench 保存档案无法发布/激活

## Verdict

Author-side evidence is **ready for current-HEAD independent re-review**, relative to base
`ff48173f8bd531571d544293f317f999aa601469`. The first review exposed a P2 startup race, so its
verdict for `8850c4243b1465e2ac4b350ab47559167ed53174` is superseded. This is a P0 hotfix: this document
records author evidence but does not self-approve the change. Merge remains reserved for explicit
co-creator confirmation.

## Original requirement and user journey

The import contract says importing never changes the published Hook config, while a later explicit
editor save makes that selected archive the publication fact. The isolated `.bench` package must
remain independent from the production package.

Initial physical-device acceptance on the pre-P2 candidate established the original correction:

1. Install only `name.caiyao.fakegps.bench`, preserving its database.
2. Import `test-import-profiles.csv` (three new rows); the existing Kyiv profile stays effective.
3. Open imported “深圳市民中心” and save without changing any field.
4. Observe `published=true`, exact `profileId=11`, seven payload fields and the Shenzhen “生效中”
   badge.
5. Force-stop and relaunch only the bench package; Shenzhen remains effective and the durable
   active id remains beside the published payload.

During independent review, a physical `connectedDebugAndroidTest` run automatically uninstalled the
`.bench` package and cleared its isolated data. The reviewer reinstalled the APK; a later concurrent
run removed it again. Production package `name.caiyao.fakegps` remained installed and untouched, but
the pre-P2 bench acceptance data no longer exists. The P2 follow-up is therefore verified on the
isolated AVD only; no physical-device claim is made for the new candidate.

## Root cause and implementation audit

| Invariant | Candidate behavior | Result |
|---|---|---|
| Variant identity has one owner | provider matcher derives authority from `BuildConfig.APPLICATION_ID` | pass |
| Save identifies the row to publish | Compose repository and legacy editor both pass the saved row id | pass |
| Active selection survives process death | row id is committed atomically with the Hook payload | pass |
| Transient startup miss keeps last-good | a missing durable id is a publication failure, not an implicit clear | pass |
| Explicit delete remains unambiguous | repository sends a typed `clearIfMissing=true` publication request | pass |
| Import remains non-publishing | `importAll` still has no publication call | pass |
| Delete does not activate arbitrary import | explicit clear publishes an empty payload and clears selection | pass |
| Production/test data isolation | only `.bench` APK and data were touched | pass |

Failure-mode audit found no sibling hard-coded provider authority. The candidate adds no dual
authority, no primary-key reorder and no secondary data store. The P2 change adds one missing-row
guard and replaces nullable callback semantics with a typed publication request; it adds no retry,
delay or “activate oldest row” fallback. The existing oldest-row choice remains only for an
installation that has never recorded an active row, and its first successful publication records
the resolved id atomically.

Repository-specific hotfix/fallback scripts named by the generic quality workflow are absent
(`scripts/check-hotfix-pattern.mjs`, `scripts/check-fallback-layers.mjs`), so the audit above was
performed directly against the complete diff. There is no `designs/` tree and no new root media
artifact.

## Fresh verification

| Gate | Result |
|---|---|
| P2 Red | new contract test failed to compile because `shouldKeepLastGoodPayload` did not exist |
| P2 targeted Green | publication contract + repository behavior tests passed |
| Debug JVM (`--rerun-tasks`) | 357/357 passed, 54 suites, 0 failures/errors/skips |
| Debug APK + androidTest compilation | passed |
| Isolated AVD instrumentation (`f001_ui_test`) | 7/7 passed |
| Release APK + R8 | passed (`minifyReleaseWithR8`) |
| `lintVitalRelease` | passed |
| Python repository suites | 50/50 passed |
| Shell syntax (`test-hook.sh`, `mock_provider_acceptance.sh`) | passed |
| `git diff --check` | passed |
| Debug lint | known baseline: 20 errors / 158 warnings |

The Debug lint count and first failure (`HookProbe.kt:117`, API-level `Location.isMock`) were
reproduced unchanged on pristine base `ff48173`; the shipping Release lint gate passes.

## Review scope

Independent review should verify both root causes, not only the authority literal:

- `.bench` provider routing matches the installed application id;
- saving a non-oldest imported row publishes that exact row;
- parameterless startup/settings publication reuses the persisted active id;
- a transient missing active row preserves the last-good payload/id and reports failure;
- explicit deletion still clears publication without activating another row;
- no import path publishes automatically and production package/data remain isolated.

---

*[砚砚/GPT-5.6-Sol🐾]*

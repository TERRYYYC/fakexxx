---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - android-content-provider
  - debug-variant
  - hook-publication
doc_kind: bug-report
created: 2026-08-03
---

# Bench variant cannot publish saved profiles

## Diagnostic capsule

| Field | Evidence |
|---|---|
| Symptom | On `name.caiyao.fakegps.bench`, saving an imported profile either reports that Hook publication failed or reports success while the old, oldest profile remains marked effective. Production is expected to remain independent and functional. |
| Evidence | First, debug resolves the provider authority to `name.caiyao.fakegps.bench.data.AppInfoProvider`, while `AppInfoProvider` registered only the hard-coded production authority. After repairing that route, device logs showed `published=true` but `field map built: 0` and the old `id=1` row remained effective when the user saved imported `id=4`: the publisher always queried `ORDER BY id ASC` and the repository did not pass the saved id. |
| Root cause | Two independent ownership gaps: package identity was not derived from the variant in `AppInfoProvider`; active-profile identity was not represented at all, so every publication implicitly selected Room's oldest row. Activity restart and settings edits would repeat that implicit selection. |
| Diagnostic strategy | Trace save → repository → `ConfigPrefsSync.sync()` → provider query and published bytes; compare manifest/provider authorities, then correlate the saved row id, payload fields and effective badge before and after process restart. |
| Timeout strategy | If the contract test does not fail for the expected authority mismatch within 20 minutes, capture the actual provider query exception from an isolated bench install before changing production code. |
| Warning strategy | If authority repair does not make both routes match, or explicit save still publishes the oldest row, stop and inspect identity ownership instead of adding fallback URIs or reordering primary keys. |
| User-visible correction | Saving an imported or existing profile in the bench app must report publication success and make that exact profile the published Hook payload; production package/data remain untouched. |
| Acceptance | Red→Green authority contract; full JVM/build gates; isolated bench instrumentation or device journey proving provider routes and save publication; no production install, uninstall, or data clear. |

### Review follow-up capsule: transient empty startup query

| Field | Evidence |
|---|---|
| Symptom | After an update/restart, a first startup sync can temporarily resolve zero profile fields and remove the durable active id even though that row still exists. |
| Evidence | DeepSeek reproduced `field map built: 0` once on exact PR HEAD while a prior active id existed; code tracing shows every `resolvedProfileId=null` result is currently committed as an empty payload and removes `KEY_ACTIVE_PROFILE_ID`. |
| Root cause | The publication API represents both “reuse the durable active row” and “explicitly clear after deletion” as the same nullable id. A transient missing query is therefore indistinguishable from an intentional delete. |
| Diagnostic strategy | Extract the missing-profile decision into the existing publication contract; verify persisted-id/missing-row preserves last-known-good, while explicit delete and a genuinely empty fresh install may publish empty. |
| Timeout strategy | If the contract cannot express all three cases without another nullable/fallback layer, stop and replace the boolean policy with a typed publication request. |
| Warning strategy | Any fix that delays startup arbitrarily, retries blindly, or silently activates Room's oldest remaining row is rejected. |
| User-visible correction | Updating or restarting the bench app must not erase the currently effective profile because of a transient provider read; an explicit delete must still clear it. |
| Acceptance | Red→Green contract tests for transient missing versus explicit clear; repository delete-policy behavior; full JVM/AVD/Release gates; update/restart device journey retains selected profile. |

## Reporter and reproduction

Reported by the co-creator during CSV import acceptance and independently traced by
`[深深/DeepSeek V4 Flash🐾]`.

1. Open the isolated `name.caiyao.fakegps.bench` app.
2. Import a valid archive or open an existing profile.
3. Save the selected profile.
4. Observe that publication either fails or leaves the oldest row marked “生效中”.

Expected: bench publishes through its own provider and makes the saved row active. Actual: the
provider first rejected the variant-aware URI; after that boundary was opened, the publisher still
selected the oldest row rather than the row the user saved.

## Root-cause and failure-mode audit

Two invariants were violated:

1. Every component that owns a package-scoped Android identity derives it from the current
   variant's application ID. A repository-wide literal scan found no sibling provider or
   preference-package call site with the same failure mode.
2. The user action that changes effective state names its target explicitly, and that selection is
   durable. `ProfileRepository.save()` previously called a zero-argument publisher, while
   `ConfigPrefsSync` silently chose the oldest row. The legacy editor had the same zero-argument
   publication bypass and now passes the id returned by its actual insert/update. No Room row
   should be reordered or duplicated to simulate active state.

## Fix decision

Use `BuildConfig.APPLICATION_ID + ".data.AppInfoProvider"` as the provider's single authority truth.
Do not accept both production and bench authorities: a fallback would blur the package/data boundary
that the P0 isolation change intentionally created.

Pass the saved row id through the repository publication seam, query that exact row, and persist the
id in the same synchronous commit as the successfully published payload. Parameterless
startup/settings/delete publication reuses that durable id; an installation with no recorded
selection keeps the legacy oldest-row fallback once, then records the resolved row. Deleting the
selected row publishes an empty payload rather than silently activating an arbitrary imported
profile.

## Verification

- Authority unit contract: expected `.bench` / actual production mismatch failed, then passed.
- Selection contracts: repository publisher and `ConfigPrefsSync` explicit-id overload both failed,
  then passed.
- JVM debug suite: 354 tests, 0 failures, 0 errors, 0 skipped across 54 suites.
- Isolated `f001_ui_test` AVD: five instrumentation tests passed, including variant provider route,
  selected-row publication seam, import rollback and zero-publication import.
- Physical bench journey on the final candidate: import three rows, save imported “深圳市民中心” →
  `published=true profileId=11`, seven profile fields, matching “生效中” badge; the same atomic
  preferences commit contains `active_profile_id=11`, and force-stop/restart retained the badge.
- Production package update time and data directory were identical before and after both bench
  installs. No production install, uninstall or data clear occurred.

Merge remains explicitly reserved for co-creator confirmation.

---

*[砚砚/GPT-5.6-Sol🐾]*

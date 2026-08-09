---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - quality-gate
  - csv-template
  - android-saf
doc_kind: quality-gate
created: 2026-08-03
---

# Quality Gate: 收藏档案导入模板下载

## Verdict

Author-side evidence is **ready for independent review** on branch
`feat/profile-import-template-download`, based on reviewed PR #9 head
`7e12dbcf10d6ee5ac8868c675290f64f070c4a37`. The change is isolated from PR #9 and does not merge,
install or alter the production package. It adds a user-owned SAF download path for a canonical CSV
template and reuses the production importer's schema contract.

## Original requirement and finish line

> “新添加一个下载template 的按钮”

The finish line is a visible collection-page action that opens Android's create-document journey,
suggests `FakeGPS-收藏档案导入模板.csv`, writes an Excel-compatible UTF-8 BOM CSV, and confirms the
result in-app. The file must contain exactly `addname` plus all 85 configurable profile headers in
canonical order, with no `id`, duplicate header or sample row. Template creation must not read
existing profiles, write Room, or publish Hook configuration.

## Ownership and architecture audit

Architecture cell remains `collection UI → import parser/validator → profile repository → Room temp`.
Map delta is none: the new path ends at a user-selected SAF Uri and adds no Store, Queue, Router,
Adapter, Dispatcher or Binding.

| Invariant | Candidate behavior | Result |
|---|---|---|
| One schema truth | template and parser consume `ProfileArchiveContract` | pass |
| Complete canonical columns | `addname` + 85 `FieldSpec` columns, ordered and unique | pass |
| Excel-compatible bytes | UTF-8 BOM + one CRLF-terminated header row | pass |
| No accidental data import | template is header-only | pass |
| User controls destination | Material `CreateDocument("text/csv")` SAF journey | pass |
| No storage permission | writes only the returned Uri via `ContentResolver` | pass |
| No data/publication side effect | template path has no repository, DAO or `ConfigPrefsSync` call | pass |
| Explicit feedback | Saving/Success/Failure are ViewModel-owned; result uses Snackbar | pass |
| Feedback cannot be interrupted by re-entry | only Idle may launch another template save | pass |

Failure-mode audit found one output stream, one typed UI state and one failure message. The change
does not add retry/fallback layers, a second schema list, a profile export path or an XLSX writer.
CSV is intentionally the single downloadable template because both Excel and the existing importer
open it; emitting a second XLSX template would duplicate schema serialization without user value.

Repository-specific generic hotfix/fallback scripts are absent. There is no `designs/` tree and no
new repository-root media artifact. Evidence remains outside the repository under
`/tmp/cat-cafe-evidence/profile-import-template/`.

## TDD and dogfood evidence

### Red → Green

1. `ProfileImportTemplateTest` initially failed to compile because `ProfileImportTemplate` did not
   exist.
2. The implementation made the canonical-byte and production-parser round-trip tests green.
3. Isolated AVD dogfood then exposed a UI bug: the success state was dismissed before
   `showSnackbar`, cancelling the `LaunchedEffect` before feedback appeared.
4. Reordering the operations to await `showSnackbar` before resetting state produced the visible
   “导入模板已保存” confirmation and retained all automated Green results.

### Isolated AVD journey

AVD `f001_ui_test` (`emulator-5554`) was used; physical device `ZY22JHW9M4` and production package
`name.caiyao.fakegps` were not touched.

1. Open 收藏档案 and verify adjacent “下载导入模板” / “导入 CSV/Excel” actions.
2. Tap download; DocumentsUI opens in Downloads with default filename
   `FakeGPS-收藏档案导入模板.csv`.
3. Tap SAVE; return to 收藏档案 and observe “导入模板已保存”.
4. Pull and inspect the created file: 993 bytes, 86 headers, 86 unique headers, first `addname`,
   last `neighbor_cells_json`, UTF-8 BOM and CRLF present.
5. Shut down the isolated AVD after evidence capture.

After fresh-context fixes, the exact candidate was installed on the AVD again. During the success
Snackbar the outer download action reported `enabled=false`; the created bytes retained the same
SHA-256. This binds the re-entry guard to runtime evidence rather than reducer tests alone.

Evidence:

- `/tmp/cat-cafe-evidence/profile-import-template/collection-download-button.png`
- `/tmp/cat-cafe-evidence/profile-import-template/template-save-success.png`
- `/tmp/cat-cafe-evidence/profile-import-template/template-save-success-final.png`
- `/tmp/cat-cafe-evidence/profile-import-template/template-download-flow.mp4`
- `/tmp/cat-cafe-evidence/profile-import-template/downloaded-template-after-fix.csv`
- downloaded CSV SHA-256:
  `ce2db2f57f01ebb8922747e43801db790f7f0e69826a9eed09085b7cff317085`

## Fresh verification

| Gate | Result |
|---|---|
| Template Red | compile failure: missing `ProfileImportTemplate` |
| Template targeted Green | canonical bytes + production-parser round trip passed |
| Dogfood Red | saved file existed but success Snackbar was cancelled |
| Dogfood Green | visible success Snackbar + valid downloaded file |
| Fresh-context Red | missing `ProfileTemplateSaveReducer` caused targeted test compilation failure |
| Fresh-context Green | state/re-entry tests + no-DB bytecode contract passed |
| Debug JVM (`--rerun-tasks`) | 362/362 passed, 55 suites, 0 failures/errors/skips |
| Debug APK | passed |
| androidTest Kotlin compilation | passed |
| Release APK + R8 | passed (`minifyReleaseWithR8`) |
| `lintVitalRelease` | passed |
| Python repository suites | 50/50 passed |
| Shell syntax (`test-hook.sh`, `mock_provider_acceptance.sh`) | passed |
| `git diff --check` | passed |

## Independent review scope

The reviewer should verify:

- the parser and template cannot drift because both consume one ordered contract;
- the generated header count/order and UTF-8 BOM/CRLF encoding are exact;
- a user-filled template row passes through the production parser;
- SAF cancellation is a no-op and output failures surface without database/publication changes;
- the Snackbar state lifecycle does not reintroduce cancellation;
- no existing import, active-profile or Hook publication invariant regresses.

## Fresh-context findings and disposition

Sonnet generated findings only; it did not provide a review verdict.

| Finding | Severity | Author disposition |
|---|---|---|
| FC-1: template state/dismiss and INV-12 ownership lacked direct JVM coverage | P2 | fixed: pure reducer transition tests plus compiled coroutine assertions forbidding Repository/DB/Hook references |
| FC-2: Success/Failure permitted re-entry while Snackbar was suspended | P3 | fixed: `canStart` returns true only for Idle; AVD verified disabled action during success feedback |
| FC-3: shared schema contract was hidden in the template file | P3 | fixed: extracted to `ProfileArchiveContract.kt` with explicit ownership documentation |

---

*[砚砚/GPT-5.6-Sol🐾]*

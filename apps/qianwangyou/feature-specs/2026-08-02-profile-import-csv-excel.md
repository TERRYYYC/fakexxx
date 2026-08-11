---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - profile-archive
  - csv
  - xlsx
  - room-transaction
doc_kind: implementation-plan
created: 2026-08-02
---

# 收藏档案 CSV / Excel 导入 Implementation Plan

**Feature:** P1 dispatch mission — 收藏档案 CSV / Excel 导入
**Goal:** 用户可先从收藏页下载 canonical CSV 模板，再预览并确认导入 UTF-8 CSV 或单工作表 XLSX；无效文件零写入、有效批次原子写入，并且不改变当前活动档案或已发布 hook 配置。
**Acceptance Criteria:** 收藏页提供“下载导入模板”按钮，通过 SAF 保存 Excel 可直接打开的 UTF-8 BOM CSV；模板列顺序与 importer 共用同一字段真相源；CSV/XLSX 真实 fixture；覆盖完整字段映射、Unicode、空值、类型/范围、文件内与库内重复、跨行错误、公式、空行及事务回滚；用户旅程为模板下载或文件选择 → 预解析/校验 → 错误或预览 → 确认 → 单一 Room 事务 → 成功摘要；原档案和已发布配置不变；Debug/Release/R8/JVM 门禁与真机旅程通过。
**Architecture cell:** collection UI → import parser/validator → profile repository → Room `temp`
**Map delta:** none
**Map delta why:** 这是收藏档案持久化单元的增量入口，不创建新进程、数据库或跨进程契约。
**Architecture:** 文件内容先在内存中受限读取并转换成 canonical `ProfileEntity(id=0)`；解析和字段校验不接触数据库。用户确认后，repository 在一个 Room transaction 内重新计算与既有档案的重复项并批量插入；该入口刻意不调用发布逻辑。
**Tech Stack:** Kotlin/JVM, Java 17, Android Storage Access Framework, Compose, Room 2.7, JUnit 4, RFC 4180 parser, ZIP + secure XML OOXML reader
**前端验证:** Yes — reviewer must exercise CSV and XLSX file selection, blocking errors, confirmation, summary, cancellation and unchanged-effective-profile behavior on device.

---

## Finish line

用户可从收藏页把 header-only canonical CSV 模板保存到自己选择的位置，填写后再选择该 CSV/XLSX 导入。选择合法文件后，用户能看到文件名、数据行数、可导入数和文件内重复数；只有点“确认导入”才写入。全部候选行在一个 Room transaction 内插入，库内完全重复项跳过；任何解析、校验或数据库异常都不会留下半批数据。下载模板和导入均不改变 `ConfigPrefsSync` 的已发布 fingerprint。

不构建：导出现有收藏数据、旧版 `.xls`、云端同步、列映射向导、部分成功模式、地址 hook cadence、Google Mock Location 或 LSPosed scope 变更。

## User Journey

1. 用户可点收藏页顶栏“下载导入模板”，在 Android 创建文档界面选择位置并保存；成功或失败都在收藏页明确提示。
2. 模板为 UTF-8 BOM、CRLF、header-only CSV，包含 `addname` 和全部 85 个 configurable headers；下载不读取数据库、不改变收藏或 Hook 配置。
3. 收藏页顶栏点“导入 CSV/Excel”。
4. Android 文件选择器只引导选择 `.csv` / `.xlsx`；实际解析仍以扩展名和内容签名 fail-closed。
5. ViewModel 受限读取并预解析：
   - 有问题：弹出按行/列定位的错误列表，数据库不变；
   - 合法：弹出摘要，明确“不会替换现有档案，也不会改变生效中的档案”。
6. 用户取消：丢弃内存预览，无副作用。
7. 用户确认：一次 Room transaction 重查重复、批量插入。
8. 显示“新增 N、跳过重复 M”；收藏 Flow 自行刷新。

## Canonical file contract

### Shared rules

- 下载模板与 parser 共同消费一个 ordered contract：`addname` 后接 `FieldSpec.allCategories()` 的 85 个 configurable headers；不得复制手写列清单。
- 模板只有 header，不含会被误导入的示例档案；用户可删除不需要填写的可选列，但必须保留至少一个 configurable header。
- 模板写入走 Android SAF `CreateDocument`，不申请传统存储权限，不保留目标 Uri 权限。

- 第一行是 canonical header；header 去首尾空白、区分大小写，禁止空 header、重复 header、未知 header 和 `id`。
- 可选元数据列：`addname`。可配置列来自 `FieldSpec.allCategories()`；文件可只提供其中一部分，但至少提供一个可配置列。
- Canonical configurable headers:
  `latitude,longitude,altitude,speed,bearing,accuracy,mcc,mnc,lac,cid,arfcn,bsic,psc,uarfcn,tac,ci,pci,earfcn,lte_bandwidth,nci,nrarfcn,nr_pci,nr_tac,gsm_rssi,gsm_ber,gsm_ta,wcdma_rssi,wcdma_rscp,wcdma_ecno,lte_rssi,lte_rsrp,lte_rsrq,lte_sinr,lte_cqi,lte_ta,nr_ss_rsrp,nr_ss_rsrq,nr_ss_sinr,nr_csi_rsrp,nr_csi_rsrq,nr_csi_sinr,signal_fluctuation_enabled,signal_fluctuation_range_db,network_type,data_network_type,voice_network_type,operator_name,operator_numeric,sim_operator,sim_operator_name,sim_country_iso,network_country_iso,is_roaming,phone_type,service_state,data_state,data_activity,override_network_type,band,channel_bandwidth,cell_bandwidth_downlink,physical_cell_id,wifi_ssid,wifi_bssid,wifi_rssi,wifi_frequency,wifi_link_speed,wifi_tx_link_speed,wifi_rx_link_speed,wifi_channel,wifi_standard,wifi_security_type,wifi_mac,wifi_ip,wifi_hidden,wifi_enabled,local_ipv4,local_ipv6,dns_primary,dns_secondary,gateway,subnet_mask,connection_type,interface_name,neighbor_cells_json`.
- 空单元格 = `NULL` / passthrough；受 `UnavailableSpec` 支持的字段可写 `--`；不支持字段写 `--` 是行错误。
- BOOLEAN 接受 `0/1/true/false`，写入时 canonicalize 为 `0/1`。整数不接受小数；浮点必须有限；`nci` 按 36-bit Long 解析。
- 明确有平台边界的字段按现有字段语义校验（例如纬经度、bearing、PCI、信号和 Wi-Fi 范围）；`neighbor_cells_json` 必须是 JSON array。
- 数据行若所有 configurable cells 都为空则视为空行并跳过；只有 `addname` 而没有配置值的行无效。
- 单元格最长 4096 字符，`addname` 最长 200 字符；最多 1,000 个数据行、128 列、2 MiB 压缩/CSV 输入和 8 MiB XLSX 展开内容；最多呈现 50 个错误。
- 完全重复定义为 canonicalized `ProfileEntity.copy(id=0)` 全字段相等（包括 `addname` 和 canonical `unavailable_fields`）。文件内后续重复行及数据库已有重复均跳过，不覆盖、不更新、不改变顺序。

### CSV

- `.csv`；严格 UTF-8，可带 UTF-8 BOM；RFC 4180 逗号、双引号转义和 quoted newline。
- 非法 UTF-8、未闭合引号、引号后脏字符、超列/超行均阻断整个文件。

### XLSX

- `.xlsx`；必须是有效 ZIP/OOXML content types，且恰好一个工作表。
- 支持 shared string、inline string、number 和 boolean cells；保持缺失 cell 的列位置。
- 任何公式 cell（即使有 cached value）、error cell、external relationship、路径穿越或 ZIP/XML 资源超限均阻断整个文件。
- XML parser 禁用 DOCTYPE 和 external entity；不执行宏、不计算公式。

## Duplicate and failure policy

- **Invalid row:** all-or-nothing；展示所有已收集问题，不允许确认。
- **Duplicate row:** safe skip；不是错误，摘要计数。
- **Unknown column:** error；避免拼写错误静默变成 passthrough。
- **Database failure:** transaction rollback；UI 回到可重试错误态。
- **Concurrent database change between preview and confirm:** repository 在 transaction 内以当时数据库为准重新去重；成功摘要使用最终计数。
- **Published config:** import path never calls `republish()`。即使原库为空，也只保存收藏，不主动发布。

## Stateful-object gate

### Object census

1. **Import UI session** — lifecycle owner: `CollectionViewModel`; state is an in-memory projection, never persisted.
2. **Pending preview batch** — lifecycle owner: the same ViewModel generation; a newer file selection cancels/replaces the older generation.
3. **Room batch transaction** — lifecycle owner: `ProfileRepository.importAll`; no screen or parser may insert rows directly.
4. **Template save session** — lifecycle owner: `CollectionViewModel`; it owns one SAF output Uri write and exposes only Saving/Success/Failure UI state.

### State × event transitions

| Object | State | Event | Next state / action |
|---|---|---|---|
| UI session | Idle | file selected | Parsing(generation, name) |
| UI session | Parsing(G) | valid parse for G | Preview(G, canonical candidates, warnings) |
| UI session | Parsing(G) | invalid/read failure for G | Invalid(G, issues); zero DB writes |
| UI session | Parsing(G) | newer file selected | cancel G → Parsing(G+1); stale completion ignored |
| UI session | Preview(G) | dismiss/cancel | Idle; discard bytes and candidates |
| UI session | Preview(G) | confirm | Importing(G); one repository call |
| UI session | Importing(G) | repeated confirm | no-op |
| UI session | Importing(G) | transaction succeeds | Success(imported, duplicates) |
| UI session | Importing(G) | transaction throws/cancels | Failure; Room rollback, retry from new selection |
| Template save | Idle | destination created | Saving; write canonical bytes on `Dispatchers.IO` |
| Template save | Saving | write succeeds | Success snackbar; no DB/publication call |
| Template save | Saving | open/write fails | Failure snackbar; existing profiles/config unchanged |
| Room batch | transaction opened | existing/candidate duplicate | skip candidate |
| Room batch | transaction opened | unique candidate | queue insert with generated id |
| Room batch | inserting | any insert failure | rollback every insert |
| Room batch | committed | return | emit exact imported/skipped counts; no republish |

### Invariants

- **INV-1:** Parser/validator has no Context, DAO or publication dependency. Pure fixture tests.
- **INV-2:** Invalid analysis cannot expose a confirmable batch. State reducer/ViewModel contract test.
- **INV-3:** At most one confirm call owns a preview generation. Single-flight test/compiled UI contract.
- **INV-4:** All unique rows are inserted or none are. Room transaction instrumentation test with injected DAO failure seam.
- **INV-5:** Imported entities always have `id=0`; database owns IDs. Unit test.
- **INV-6:** Import never invokes `ConfigPrefsSync`/`republish`, while existing save/delete behavior is unchanged. Repository seam/source contract test.
- **INV-7:** Existing oldest row and every pre-import row remain byte-for-byte equivalent after import. Room instrumentation test.
- **INV-8:** Duplicate comparison happens again inside the transaction, so preview/confirm races cannot create exact duplicates. Concurrency/transaction test.
- **INV-9:** Stale parse completion cannot overwrite a newer file session. State-generation test.
- **INV-10:** Reader limits apply before unbounded allocation; XLSX XML/ZIP parser never resolves external entities. Adversarial JVM tests.
- **INV-11:** Template headers equal `addname + FieldSpec` exactly, contain no `id`, are unique, and a user-filled row parses through the production importer. JVM contract test.
- **INV-12:** Template creation owns no DAO/repository/publication dependency and writes only to the user-selected SAF Uri. Compiled UI ownership contract + device journey.

### Adversarial scenarios

- UTF-8 BOM + Chinese/emoji names + quoted comma/newline.
- Invalid UTF-8, unterminated quote, duplicate/unknown header, header-only and all-empty files.
- Valid first row plus bad later row proves zero confirmable records and zero writes.
- XLSX shared/inline strings, blank cells, formula with cached value, error cell, two sheets and compressed expansion limit.
- Integral/overflow boundaries including `nci`, non-finite decimals, invalid boolean and explicit range endpoints.
- Same row repeated in the file and already in Room; only one canonical row exists afterward.
- DAO throws after one insert; transaction restores exact pre-import state.
- Double confirm and select-B-before-parse-A-finishes.

## Task 1: Lock readers, canonical schema and real fixtures (TDD)

**Files:**
- Create: `app/src/main/java/name/caiyao/fakegps/data/importer/ProfileImportModels.kt`
- Create: `app/src/main/java/name/caiyao/fakegps/data/importer/CsvTableReader.kt`
- Create: `app/src/main/java/name/caiyao/fakegps/data/importer/XlsxTableReader.kt`
- Create: `app/src/main/java/name/caiyao/fakegps/data/importer/ProfileArchiveParser.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/data/importer/ProfileArchiveParserTest.kt`
- Create: `app/src/test/resources/profile-import/valid-unicode.csv`
- Create: `app/src/test/resources/profile-import/valid-unicode.xlsx`

1. Add fixture-driven RED tests for CSV/XLSX parity, Unicode, quoting, blanks, formula rejection, malformed structure and limits.
2. Run only `ProfileArchiveParserTest`; confirm failures name missing readers.
3. Implement bounded CSV and secure OOXML table readers, then the extension/content router.
4. Implement canonical header/row validation and intra-file exact dedupe; use the same entity conversion as the editor rather than a second field map.
5. Re-run targeted tests and commit the parser slice.

## Task 2: Seal entity mapping and validation (TDD)

**Files:**
- Create: `app/src/main/java/name/caiyao/fakegps/data/db/ProfileEntityCodec.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/editor/ProfileFieldDraft.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/editor/ProfileEditorViewModel.kt`
- Modify: `app/src/test/java/name/caiyao/fakegps/ui/screen/editor/ProfileFieldDraftTest.kt`
- Modify: `app/src/test/java/name/caiyao/fakegps/data/importer/ProfileArchiveParserTest.kt`

1. Add RED boundary matrices for field type/range, `nci` Long, booleans, `--`, JSON array and canonical entity round trip.
2. Extract the existing editor entity map into one `ProfileEntityCodec`; preserve editor wrappers and behavior.
3. Make editor/import validation call one pure value validator so file imports cannot accept values the editor later corrupts.
4. Re-run editor + importer tests and commit.

## Task 3: Add one atomic, non-publishing import boundary (TDD)

**Files:**
- Modify: `app/src/main/java/name/caiyao/fakegps/data/db/ProfileDao.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/data/repository/ProfileRepository.kt`
- Create: `app/src/androidTest/java/name/caiyao/fakegps/data/db/ProfileImportTransactionTest.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/data/repository/ProfileImportContractTest.kt`

1. Add RED Room tests for all-insert, library duplicate, exact pre-state preservation and forced mid-batch rollback.
2. Add `getAll()` and one transaction-owned import API; canonicalize IDs to zero and recompute duplicates inside the transaction.
3. Prove the import call graph excludes `republish()` while save/delete still include it.
4. Run JVM contracts and `compileDebugAndroidTestKotlin`; commit.

## Task 4: Deliver the Compose file journey (TDD)

**Files:**
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/collection/CollectionViewModel.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/ui/screen/collection/CollectionScreen.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/ui/ProfileImportStateTest.kt`
- Modify: `app/src/test/java/name/caiyao/fakegps/ui/UiActionOwnershipContractTest.java`

1. RED-test state/generation/single-confirm behavior and the compiled OpenDocument → preview → confirm wiring.
2. Add the top-bar import action, Storage Access Framework launcher, issue dialog, confirmation dialog and terminal summary.
3. Read ContentResolver streams on `Dispatchers.IO` with the parser's byte limit; never retain Uri permissions or source bytes after preview.
4. Run targeted JVM tests plus Debug compile; verify Compose warnings stay clean; commit.

## Task 5: Quality, device acceptance and independent review

1. Run `:app:testDebugUnitTest --rerun-tasks`, Python suites, `assembleDebug`, `compileDebugAndroidTestKotlin`, `assembleRelease`, R8 and `lintVitalRelease`.
2. Run instrumentation import tests against isolated app data; do not point any experiment at the operator's installed stable profile DB.
3. After review authorization, exercise one CSV and one XLSX on the device and compare pre/post profile census plus published fingerprint; no uninstall.
4. Load `quality-gate`, then `fresh-context-review` if non-trivial, then `request-review` for DeepSeek V4 Flash against exact HEAD. Fix findings through `receive-review`.
5. Hand approved exact HEAD to Opus for Feature Doc Truth and `merge-gate`; only Opus may complete the merge chain in this mission.

## Open questions

- **Technical:** Android document providers may omit a display name. Resolve locally by content signature only when the suffix is unavailable; still reject legacy OLE `.xls` with a specific error. This is reversible and does not require operator input.
- **Technical:** On-device instrumentation will use an isolated test database/process; the stable installed database is read only for final post-review acceptance evidence.
- **Value:** None. Duplicate-skip, all-or-nothing validation, no publication and formula rejection are the safest reversible defaults consistent with the dispatch charter.

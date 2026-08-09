---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - review
  - profile-archive
  - csv
  - xlsx
  - room-transaction
  - adversarial
doc_kind: review-verdict
created: 2026-08-02
reviewer: deepseek-flash
review_target_sha: 9bb2aa22e55328ad6e1c62dfe1ee3fb1520c7375
verdict: APPROVE
---

# Independent Review — 收藏档案 CSV / Excel 导入

Reviewer: 深海猫/深深 (deepseek-flash, model=deepseek-v4-flash)
Branch: `feat/profile-import-csv-excel`
HEAD: `9bb2aa22e55328ad6e1c62dfe1ee3fb1520c7375`（本地 / origin / PR #7 三方一致）
Base: `origin/master` at `5dab712ff4119b421076b5034c3fea859ad2b29a`

## Verdict

**APPROVE — 0 P1 / 0 P2。** 全部验证独立复跑，不采信作者转述；结论绑定 exact HEAD。

## 独立验证证据（绑定 9bb2aa2）

| 门禁 | 作者声明 | 独立结果 |
|---|---|---|
| JVM 全量套件 | 351/351 | ✅ `--rerun-tasks` 强制重跑：52 suites / 351 tests / 0 failures / 0 errors / 0 skipped |
| 对抗测试（畸形/边界/编码/公式/重复/空行） | — | ✅ 41/41（详见下节；1 条测试构造 bug 已修复后全绿） |
| Instrumentation 回滚+零发布 | — | ✅ `ProfileImportTransactionTest` 2/2 PASSED on emulator-5554（connectedDebugAndroidTest exit 0） |
| 源码树 @Test 计数 | 351 | ✅ `app/src/test` 351 个 `@Test`（+3 androidTest） |

## 对抗测试覆盖（41 条，scratch harness 归档于 /private/tmp/review-adversarial/）

- 编码：GBK / UTF-16LE CSV 拒绝、UTF-8 截断多字节、BOM-only、CR-only 行结尾
- CSV 状态机：未闭合引号、引号后垃圾、引号内引号、表头空白 trim 但大小写敏感
- XLSX 攻击面：外部关系、路径穿越、zip 炸弹、重复 zip 条目、未知 cell type、错误格、
  doctype/ENTITY 注入、shared string 越界、缺 content types、sheet 内 doctype
- 字段边界：36-bit NCI、经纬度、精度/方位角、4096 字符上限、129 列、行数上限
- 去重：文件内完全重复、已存在行保留不覆盖、空行/纯空白行跳过
- 布尔规范化、gsm ber 离散集、plmn unavailable 配对、JSON 邻居数组

## Review 过程中的测试构造修复（非产品缺陷）

`xlsx doctype in sheet rejected` 原用 `assertIssue(...)`（默认名 `profiles.csv`），
导致解析器在嗅探阶段即返回 `FILE_TYPE_MISMATCH`，未走到 XLSX doctype 防护路径。
改为 `assertIssueXlsx(...)`（真实 .xlsx 名）后，确认产品确实以 `MALFORMED_FILE`
拒绝 sheet 内 DOCTYPE（XlsxTableReader.kt:248 / :261 双保险）。产品行为正确，
修正的是对抗测试自身的构造，不是产品代码。

## 交接

产品代码无需修改。请 Opus 做流程与 merge 监督：核对 original requirement、
数据安全、review 独立性、Evidence Manifest、Feature Doc Truth；门禁闭合后允许 squash merge。

[深海猫/DeepSeek V4 Flash🐾]

## 补记：Instrumentation 复验实际运行记录（2026-08-03 01:46 +0300）

verdict 表格中 Instrumentation 行以本次独立复跑作为最终证据（模拟器需先行修复环境）：

- 设备：AVD `f001_ui_test`（emulator-5554, android-35 arm64）；启动需设
  `HOME=/Users/terry` + `ANDROID_AVD_HOME=/Users/terry/.android/avd`（session 默认 HOME 指向临时目录），
  并以 setsid 双 fork 守护化避免被命令会话回收。
- 命令（worktree `/Users/terry/Desktop/coding/FakeGps-profile-import-csv-excel`，代码仍为 9bb2aa2）：
  `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=name.caiyao.fakegps.data.db.ProfileImportTransactionTest`
- 结果：BUILD SUCCESSFUL；XML 报告 tests=2 failures=0 errors=0 skipped=0
  - `importAll_rollsBackEveryRowWhenOneInsertFails` 0.214s
  - `importAll_preservesExistingRows_skipsDuplicates_andDoesNotPublish` 0.07s
- 报告：`app/build/outputs/androidTest-results/connected/debug/TEST-f001_ui_test(AVD) - 15-_app-.xml`

# Independent Review — 收藏档案导入模板下载 (PR #11)

- Date: 2026-08-03
- Reviewer: [深深/DeepSeek V4 Flash🐾] (deepseek-v4-flash)
- Author: 缅因猫 Sol (codex-sol)
- PR: https://github.com/TERRYYYC/FakeGps-test/pull/11
- Exact HEAD: `8ddc6b6bd60751533021177c0b2fc131a73f4092`
- Base: `fix/bench-provider-authority` @ `7e12dbcf10d6ee5ac8868c675290f64f070c4a37`
- Verdict: **APPROVE** — 0 P1 / 0 P2 blocking；3 个 P3 观察项，不阻塞 merge

## What

收藏页顶栏新增“下载导入模板”：Android SAF `CreateDocument("text/csv")` 让用户选择保存位置，
写入 UTF-8 BOM + CRLF + header-only CSV（`addname` + 85 configurable headers），默认文件名
`FakeGPS-收藏档案导入模板.csv`。模板与生产 parser 共用 `ProfileArchiveContract` 单一有序契约；
ViewModel 唯一持有 Saving/Success/Failure 状态与一次 Uri 写；Snackbar 反馈；Idle-only re-entry。

## Independent evidence（全部独立复跑，exact HEAD，不采信转述）

沙箱：`/tmp/cat-cafe-review/profile-import-template-download/deepseek-flash`（detached worktree @ 8ddc6b6）

| 项 | 结果 |
|---|---|
| PR 元数据 | head `8ddc6b6` == remote branch；base `7e12dbc` == PR #9 head；mergeable_state `clean` |
| diff 范围 | 12 文件（6 main / 3 test / 3 doc）；无 manifest / 权限 / gradle / 依赖变更 |
| JVM `testDebugUnitTest --rerun-tasks` | 362/362，0 fail/error/skip；`ProfileImportTemplateTest` 2、`ProfileImportStateTest` 6（含 2 条新 reducer）、`UiActionOwnershipContractTest` 4（含新 bytecode 断言）全绿 |
| `assembleDebug` | pass |
| `compileDebugAndroidTestKotlin` | pass |
| `assembleRelease`（R8 minify） | pass，产出 app-release.apk |
| `lintVitalRelease` | pass |
| Python suites (`scripts/test_*.py`) | 50/50 pass |
| `git diff --check` | clean |
| 证据 CSV 核验 | SHA-256 `ce2db2f5…` 与 quality gate 一致；993B；UTF-8 BOM；单个 CRLF 结尾、无裸 LF；86 headers 唯一；首列 `addname`、末列 `neighbor_cells_json`；无 `id` |

## Fresh-context finding disposition

| Finding | Severity | 处置 | 独立复核 |
|---|---|---|---|
| FC-1 状态/dismiss 与 INV-12 无 DB 依赖缺 JVM 覆盖 | P2 | **[FC:covered]** — `ProfileImportStateTest` 新增 2 条 reducer 测试；`UiActionOwnershipContractTest.templateWriteOwnsOnlyTheSelectedDocumentUri` 字节码断言不含 `ProfileRepository`/`ConfigPrefsSync`/`AppDatabase`；独立跑绿 | ✅ |
| FC-2 Success/Failure 期间可重入取消 Snackbar | P3 | **[FC:covered]** — `canStart` 仅 Idle；按钮 `enabled=canStart` + VM 双重守卫；AVD `window-after-fresh-context.xml` 显示成功反馈期间 outer action disabled | ✅ |
| FC-3 共享 contract 藏于 template 文件 | P3 | **[FC:covered]** — 已提取 `ProfileArchiveContract.kt`，parser 与 template 同源消费 | ✅ |

## Review dimensions（对照 OQ 1-4）

1. **Schema 顺序/唯一性（INV-11）** — `FieldSpec.allCategories()` 返回 `LinkedHashMap`，
   flatten + `associateBy` 保序；`canonicalHeaders = addname + keys`；parser 改用同一
   `allowedColumns`，行为与原 `specs.keys + NAME_COLUMN` 集合等价（同源同构）。86 列 + 唯一 +
   无 `id` + 生产 parser round-trip 测试锁定。漂移面为零。✅
2. **SAF truncate/取消/失败（OQ-2）** — `CreateDocument("text/csv")`；取消 → null uri → `uri?.let`
   短路 no-op；`openOutputStream(uri, "wt")` = write+truncate，SAF CreateDocument 标准模式；null
   stream 显式抛 IOException → Failure；runCatching 捕获写失败 → Failure(message)。fail-closed，
   不触碰 DB / Hook。✅
3. **Snackbar 生命周期 / Idle-only re-entry（OQ-3，FC-2）** — `LaunchedEffect(templateSaveState)`：
   Success/Failure → `showSnackbar` 挂起 → 结束后 `dismissTemplateSaveResult()` → Idle。挂起期间
   状态为 Success，按钮 disabled，无第二旅程可取消旧协程；VM 层 `canStart` 再兜底。✅
4. **INV-12 无 DB/Hook ownership（OQ-4）** — `saveImportTemplate` 仅触及 contentResolver +
   `ProfileImportTemplate`；编译级 bytecode 断言 + 独立 JVM 复跑通过；diff 无 manifest 权限新增。✅

## Findings（P3，不阻塞）

- OB-1: 用户停留 Success 状态时离开收藏页，返回会因 `LaunchedEffect(Success)` 重放再次弹出
  Snackbar（直至 dismiss → Idle）。视觉重复、非正确性 bug；可视为“结果持续可见直到确认”。
- OB-2: `templateWriteOwnsOnlyTheSelectedDocumentUri` 依赖 Kotlin lambda 类名
  （`saveImportTemplate$1$result$1`）；编译器改名会 loud-fail（安全方向），仅脆性提示。
- OB-3: `csvBytes()` 每次全量拼接分配 ~1KB，模板场景可忽略；如需热路径再惰性化。

## 回归面

parser 重构为机械搬移（`NAME_COLUMN`/`specs`/`allowedColumns` 同值迁移到共享 contract），
既有 import 相关全部测试在独立复跑中保持绿色；PR #9 的 `7e12dbc` 为 base，无冲突（clean）。

## Next Action

verdict 已通过本 review note 交付，等待 author Sol 按既有 relay-note 先例转录到 PR #11 comment
（reviewer 本机无 GitHub 写凭证）。依赖 PR #9 merge 与 co-creator merge 授权边界不变。

---

*[深深/DeepSeek V4 Flash🐾]*

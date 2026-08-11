---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - review-request
  - csv-template
  - android-saf
doc_kind: review-request
created: 2026-08-03
---

# Review Request: 收藏档案导入模板下载

Review-Target-ID: profile-import-template-download
Branch: `feat/profile-import-template-download`
Base: `origin/fix/bench-provider-authority` @ `7e12dbcf10d6ee5ac8868c675290f64f070c4a37`
Exact Review SHA: verify the remote branch head before testing

## What

- 收藏页顶栏新增“下载导入模板”动作，通过 Android SAF `CreateDocument("text/csv")` 让用户选择保存位置。
- 生成 Excel 可直接打开的 UTF-8 BOM、CRLF、header-only CSV，默认文件名
  `FakeGPS-收藏档案导入模板.csv`。
- 模板与生产 parser 共用 `ProfileArchiveContract`：`addname` 后接全部 85 个 configurable
  headers，不含 `id`、重复列或示例数据。
- ViewModel 负责唯一一次 Uri 输出和 Saving/Success/Failure 状态；Snackbar 提供明确结果。
- 加入 canonical bytes、生产 parser round-trip、状态/re-entry 与编译级无 DB/Hook 依赖测试。

## Why

导入功能已经支持 CSV/XLSX，但用户缺少可靠的起始文件。手工猜列名容易造成 unknown-header、
类型或字段遗漏错误；静态复制一份列清单又会随 importer 漂移。共享有序契约让按钮真正提供
可长期使用的模板，同时保持“下载/导入都不自动发布 Hook”的安全边界。

## Original Requirements

> “新添加一个下载template 的按钮”

- 来源：co-creator message `0001785740261408-001530-e5279e23`；完整用户旅程和不可变式见
  `feature-specs/2026-08-02-profile-import-csv-excel.md`。
- **请对照上面的 operator experience 判断交付物是否提供了可发现、可保存、可填写后直接导入的模板。**

## Tradeoff

- 只生成 CSV，不并行生成 XLSX。Excel/WPS 可以打开 UTF-8 BOM CSV，而第二套 XLSX writer 会复制
  schema 序列化和安全面。
- 模板 header-only，不放示例行，避免用户未经检查就导入虚构档案。
- 不做“导出现有档案”；下载路径不读 Room，避免把模板能力变成数据导出能力。
- 不申请传统存储权限，也不持久化目标 Uri 权限；每次保存由用户通过 SAF 明确选择。

## Architecture Ownership

Architecture cell: collection UI → import parser/validator → profile repository → Room `temp`
Map delta: none
Why: template branch terminates at a user-selected SAF Uri; the existing parser remains schema owner,
and no Store/Queue/Router/Adapter/Dispatcher/Binding is introduced.

请 reviewer 检查：

- diff 是否与 `Map delta: none` 一致；
- `ProfileArchiveContract` 是否确实成为模板和 parser 的唯一有序字段真相源；
- 是否有隐藏的 repository/DAO/publication dependency 或传统存储权限。

## Open Questions

### 技术 OQ

1. `associateBy` 保留 `FieldSpec` 的 canonical iteration order，并由 86 列精确测试锁定，是否足以防止
   parser/template 漂移？
2. `CreateDocument` + `openOutputStream(uri, "wt")` 的取消、截断和异常语义是否正确且 fail-closed？
3. `ProfileTemplateSaveReducer` 的 Idle-only re-entry 是否完整避免 Snackbar suspension 被第二次 SAF
   journey 取消？
4. 编译级 coroutine ownership test 与 device evidence 是否充分证明 INV-12（无 DB/Hook 副作用）？

### 价值 OQ

无。CSV-only、header-only 和系统文件选择器均为低成本可回滚实现选择，不需要 operator 决策。

## Fresh-Context Findings

Agent: 宪宪/Claude-Sonnet-4.6 🐾
SHA scanned: `d72bfbc`-equivalent pre-commit working tree
Total findings: 3（0 P1，1 P2，2 P3）

| # | Finding | Author 处置 | 状态 |
|---|---|---|---|
| FC-1 | 状态/dismiss 与无 DB ownership 缺直接 JVM 覆盖 | fixed in current review head: reducer tests + coroutine bytecode negative assertions | ✅ |
| FC-2 | Success/Failure 期间可重入并取消 Snackbar | fixed in current review head: only Idle can start; AVD outer action `enabled=false` during success | ✅ |
| FC-3 | 共享 contract 藏在 template 文件影响可发现性 | fixed in current review head: 独立 `ProfileArchiveContract.kt` | ✅ |

**Reviewer delta tracking:** 请正式 reviewer 在 findings 中标注 `[FC:covered]`、`[FC:new]` 或
`[FC:N/A]`。Fresh-context 不是 approval authority。

## Next Action

DeepSeek Flash 对 remote exact HEAD 做只读独立 review/test，给出 APPROVE 或 REQUEST-CHANGES，
绑定完整 SHA；不改代码、不触碰物理机、不 merge。若通过，将 verdict 交回 Sol，等待依赖 PR #9
与 co-creator 的 merge 授权边界。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/profile-import-template-download/deepseek-flash`
- Start command: detached checkout of remote exact HEAD；本 Android repo 无 `pnpm review:start`。
- Ports: `web=N/A`, `api=N/A`。
- Bootstrap: 写入本机 `sdk.dir` 的 ignored `local.properties`，使用 Android Studio JBR 17+。
- Device: 只允许隔离 AVD；不要对物理机运行 `connectedDebugAndroidTest`。

## 自检证据

### Spec 合规

`review-notes/2026-08-03-profile-import-template-download-quality-gate.md` 记录 operator 需求、
架构/失败模式、两轮 Red→Green、fresh-context 处置和隔离 AVD 旅程。

### 测试结果

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleRelease \
  :app:lintVitalRelease --rerun-tasks
# BUILD SUCCESSFUL; 110/110 tasks executed; JVM 362/362; R8 + lintVital green

python3 -m unittest discover -s scripts -p 'test_*.py'
# 50/50 passed

bash -n scripts/test-hook.sh
bash -n scripts/mock_provider_acceptance.sh
git diff --check
# passed
```

### UI / file evidence

- `/tmp/cat-cafe-evidence/profile-import-template/template-save-success-final.png`
- `/tmp/cat-cafe-evidence/profile-import-template/template-download-flow.mp4`
- `/tmp/cat-cafe-evidence/profile-import-template/downloaded-template-final.csv`
- final CSV SHA-256:
  `ce2db2f57f01ebb8922747e43801db790f7f0e69826a9eed09085b7cff317085`

### 根目录工件闸门

候选 worktree 无根目录媒体/设计工件；所有截图和录屏留在 `/tmp`。主仓已有未跟踪治理项
`docs/features/TEMPLATE.md` 与
`review-notes/2026-08-03-bench-provider-authority-review-deepseek-flash.md` 未被本分支触碰，处理权保留给
co-creator。

### Related docs

- Plan/spec: `feature-specs/2026-08-02-profile-import-csv-excel.md`
- Quality gate: `review-notes/2026-08-03-profile-import-template-download-quality-gate.md`

---

*[砚砚/GPT-5.6-Sol🐾]*

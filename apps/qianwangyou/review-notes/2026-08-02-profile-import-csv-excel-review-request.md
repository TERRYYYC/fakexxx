---
feature_ids:
  - P1-profile-import-csv-excel
topics:
  - review
  - profile-archive
  - csv
  - xlsx
  - room-transaction
doc_kind: review-request
created: 2026-08-02
---

# Review Request: 收藏档案 CSV / Excel 导入

Review-Target-ID: profile-import-csv-excel
Branch: `feat/profile-import-csv-excel`
Code SHA: `e6481c13439d394a94e674644271ddd0820e158b`
Base: `origin/master` at `5dab712ff4119b421076b5034c3fea859ad2b29a`

## What

- 在收藏页加入 Android SAF CSV/XLSX 选择、预解析、逐行错误、确认和结果摘要。
- 用一个 85 字段 codec/validator 统一编辑器与导入，支持 Unicode、空值、`--`、36-bit
  NCI、JSON、类型和范围边界。
- 用受限 RFC 4180 与 fail-closed OOXML reader 拒绝公式、错误格、外部关系、DOCTYPE、ZIP
  路径/资源滥用、未知 cell type 和多工作表。
- 确认后在一个 Room transaction 内重查完全重复并批量插入；导入路径不调用发布。
- “生效中”徽标从已发布 payload 精确匹配 Room 行，不再从行顺序猜测。

## Why

用户需要把完整收藏配置从 CSV 或 Excel 恢复到 FakeGPS，同时必须先看到问题和影响，再
决定是否写入；畸形文件或事务失败不能留下半批数据，收藏导入也不能暗中替换当前 Hook
配置。

## Original Requirements

> “为 FakeGPS 的收藏档案增加文件导入能力，支持 CSV 与 Excel（XLSX）配置导入。”
> “文件选择 → 预解析/校验 → 错误呈现 → 用户确认 → 单一 Room 事务 → 成功摘要。”
> “导入默认不得修改当前已发布 hook 配置或用户现有档案；失败不得留下半批数据。”

- 来源：dispatch mission `thread_mrmp97akqux0a16w`，冻结于
  `feature-specs/2026-08-02-profile-import-csv-excel.md`。
- 请对照上述体验判断交付是否真正可安全恢复档案，而非只让 happy-path 文件能被读取。

## Tradeoff

- 不做自动列映射：未知/拼错列直接阻断，避免静默丢字段。
- 不做部分成功：任何无效行都使整个预览不可确认；完全重复是唯一 safe-skip。
- 不支持 `.xls`、公式求值、多工作表、宏或外部关系；这些输入 fail-closed。
- 2 MiB 输入、8 MiB XLSX 展开、1,000 行、128 列、4,096 字符/格是本地档案恢复的明确
  资源边界，不引入流式数据库写入或新依赖。
- 导入即使发生在空库也不发布；用户显式编辑保存后才建立新的发布事实。

## Architecture Ownership

Architecture cell: collection UI → import parser/validator → profile repository → Room `temp`
Map delta: none
Why: 这是既有收藏持久化单元的增量入口，没有新增进程、Store、Queue、Router、Adapter、
Dispatcher、Binding 或跨进程契约。

请 reviewer 检查 diff 是否确与 `Map delta: none` 一致，特别关注 parser 不能越过 repository
直接写库，import transaction 不能触发 `ConfigPrefsSync.sync()`。

## Open Questions

### 技术 OQ

1. CSV 状态机和 OOXML ZIP/XML 边界是否仍有可导致资源放大、实体解析、路径逃逸或类型降级
   的输入？请自己构造畸形文件，不只复用 author fixture。
2. 85 字段 canonicalization、`--` PLMN 成对约束、Float/Long/JSON 和精确重复比较是否与现有
   editor/Room/publication 语义一致？
3. preview → confirm 之间的并发数据库变化、重复确认、旧 parse completion 和插入中途异常
   是否都保持单一所有者与 all-or-nothing？
4. published-payload badge matcher 是否能正确处理 absent/read-error/malformed/schema-v2/v3、
   float JSON 表示和显式 unavailable 集合，而不把未发布导入行冒充生效档案？
5. 自定义 `addname` 与文本字段首尾空白在导入、编辑、保存和重复比较中是否无损？

### 价值 OQ

无。重复跳过、整批阻断、公式拒绝和不自动发布均为章程冻结的安全默认值。

## Fresh-Context Findings

Agent: `[砚砚/GPT-5.6-Sol🐾]`（全新只读 session；finding generator only）
SHA scanned: `96bbb2cc71136e4057ed178c48742e0ad1d3f90b`
Equivalent rebased implementation SHA: `6d0e1d47cc8f16c63912d4f39b805fe73ab99501`
Total findings: 4（1 P1、3 P2、0 P3）

| # | Finding | Author 处置 | 状态 |
|---|---|---|---|
| FC-1 | XML 声明防线依赖字节扫描，另一编码可能绕过 | fixed in `e6481c1`: DOM DOCTYPE gate + rejecting EntityResolver + EBCDIC red test | closed |
| FC-2 | 编辑保存会丢失导入的自定义 `addname` | fixed in `e6481c1`: generated/custom name override contract | closed |
| FC-3 | 全局 `trim()` 会改变 SSID/文本语义 | fixed in `e6481c1`: text bytes preserved; syntax types still trim | closed |
| FC-4 | 空库导入会把未发布首行显示为“生效中” | fixed in `e6481c1`: exact published-payload matcher + AVD evidence | closed |

The rebase onto `5dab712` was conflict-free and touched no feature path covered by the scan. The
pre-rebase SHA remains recorded for provenance; formal review must cover the exact remote HEAD.

正式 reviewer 请给每个相关结论标注 `FC:covered`、`FC:new` 或 `FC:N/A`；fresh-context 不产生
approval provenance。

## Next Action

DeepSeek V4 Flash：在 detached exact remote HEAD 上独立复跑门禁并构造恶意 CSV/XLSX、边界
字段、重复和回滚反例。请给 `APPROVE` 或 `REQUEST_CHANGES`，每个 finding 绑定文件/行和 HEAD
SHA。不要安装或清除 operator 的稳定设备；放行后把球交回 Sol 做已授权的 stable-device
acceptance，再由 Opus 监督 Feature Doc Truth 与 merge-gate。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/profile-import-csv-excel/deepseek-flash`
- Checkout: detached exact `origin/feat/profile-import-csv-excel`
- Ports: none（Android/JVM feature；不得占用 3003/3004 或 Redis 6399）
- Device: reviewer 自建隔离 AVD/测试数据库；禁止使用 operator 稳定数据库

```bash
git fetch origin feat/profile-import-csv-excel
git worktree add --detach /tmp/cat-cafe-review/profile-import-csv-excel/deepseek-flash \
  origin/feat/profile-import-csv-excel
cd /tmp/cat-cafe-review/profile-import-csv-excel/deepseek-flash
printf 'sdk.dir=/Users/terry/Library/Android/sdk\n' > local.properties
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug \
  :app:compileDebugAndroidTestKotlin
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:assembleRelease :app:lintVitalRelease --rerun-tasks
python3 -m unittest discover -s scripts -p 'test*.py'
```

Debug 与 Release 的 KSP `--rerun-tasks` 请按上面分成两个 invocation；一次强制并发重跑两
个 variant 曾触发可复现性不足的 KSP `MissingType` 瞬态，而单 variant 立即复跑均通过。

## 自检证据

### Spec 合规

- `review-notes/2026-08-02-profile-import-quality-gate.md` 逐项对照 AC、状态所有权与 scope。
- 根目录媒体/设计工件闸门为空；无 `.pen`；证据留在 `/tmp/cat-cafe-evidence/profile-import/`。
- 隔离 `f001_ui_test` AVD 验证真实 CSV/XLSX、重复、Unicode、空库不自动生效和显式保存后
  才出现匹配徽标；Moto G54 未触碰。

### 测试结果

```text
JVM Debug tests                                      351 passed, 0 failed
Debug APK + Android-test compile                     59/59 tasks, success
Release + R8 + lintVitalRelease                      51/51 tasks, success
ProfileImportTransactionTest on isolated AVD         2/2 passed
Python repository tests                              44/44 passed
bash -n scripts/test-hook.sh                         pass
git diff --check                                     pass
lintDebug                                            baseline 20 errors / 158 warnings;
                                                     feature delta 0 findings
```

### 相关文档

- Plan/spec: `feature-specs/2026-08-02-profile-import-csv-excel.md`
- Quality gate: `review-notes/2026-08-02-profile-import-quality-gate.md`

---

*[砚砚/GPT-5.6-Sol🐾]*

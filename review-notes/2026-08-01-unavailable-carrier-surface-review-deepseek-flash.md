---
feature_ids:
  - profile-unavailable-state
  - cellular-hook-verification
topics:
  - review
  - android
  - xposed
  - carrier
doc_kind: review-verdict
created: 2026-08-01
reviewer: deepseek-flash
review_target_sha: 54be3cd76b29d597d79508452caa3adecdbce567
verdict: REQUEST_CHANGES
---

# Independent Review Verdict — PR #3 `--` 闭环 + 运营商全 surface

Reviewer: 深海猫/深深 (deepseek-flash) · Sandbox: `/tmp/cat-cafe-review/feat-verify-ux/deepseek-flash`

## Verdict

**REQUEST_CHANGES** — 0 P1? No: **1 P1 + 1 P2 + 1 P3**. 详见 Findings。实现质量高，上轮 12 个 P1 全部按承诺关闭；但 P1-7 的根因（`String.valueOf(Int)` 出现在 PLMN 字符串边界）只在 ctor 路径修复，getter 路径仍残留，导致本 PR 自己文档示例（mnc=0 中国移动）在公共 API 上仍产生平台不可能值。修完我复跑后放行。

## 独立复跑证据（不采信作者转述，全部在独立 sandbox 复跑）

| 门禁 | 作者声称 | 独立结果 |
|---|---|---|
| PR #3 state | OPEN / MERGEABLE | ✅ OPEN, mergeable_state=clean（GitHub API 直查） |
| HEAD SHA | `54be3cd76b29d597d79508452caa3adecdbce567` | ✅ 精确一致 |
| `50628e9..54be3cd` 仅 review request | — | ✅ 1 文件 / +152 行 |
| Gradle 四项 | 全绿 | ✅ BUILD SUCCESSFUL（testDebugUnitTest + assembleDebug + assembleRelease/R8 + lintVitalRelease，独立 sandbox） |
| JUnit | 234 / 0 / 0 | ✅ 234 tests, 0 failures, 0 errors（30 个 result XML 汇总） |
| Python matrix/verdict | 24 OK | ✅ 24 tests OK |
| `bash -n` / `git diff --check` | exit 0 | ✅ 均 exit 0 |
| `compileDebugAndroidTestKotlin`（新迁移测试） | — | ✅ BUILD SUCCESSFUL（instrumented 测试已编译，未运行——设备写未授权） |

## Findings

### P1-1 — `getMncString`/`getMccString` getter hooks 仍 `String.valueOf(Int)`：MNC 前导零在 getter 路径丢失（P1-7 根因只修了一半）

**文件**：`app/src/main/java/name/caiyao/fakegps/hook/HookUtils.java:405-411`（NR）、`:421-427`（GSM/WCDMA/LTE，API 28+）；`Snapshot.resolvePlmnString`（`Snapshot.java:113-125`，已修的那条路径）。

**证据**：
```java
hookPlmnStringGetter(cl, cls, "getMncString", "mnc",
        s -> s.mnc != null ? String.valueOf(s.mnc) : null);   // 5dc5b37 与 HEAD 完全相同
```
ctor 路径（rebuilt identity）已走 `resolvePlmnString` 零填充：`mnc=0 → "00"`。但 getter hook 会**覆盖同一对象的 ctor 值**（rebuilt serving identity 不在 NEIGHBOR_BYPASS 中），所以公共 API `getMncString()` 实际返回 `"0"`——**1 字符 MNC，stock Android 不可能产生**（AOSP `CellIdentity.isMnc` 要求 2–3 位）。同一 rebuilt 对象上 `getPlmn()/getGlobalCellId()`（由 ctor 字段派生）返回 `"46000"`——**跨 surface 自相矛盾**：`getMccString()+"getMncString()" = "4600" ≠ getPlmn()="46000"`。

影响面：本 PR 自己的文档示例（`mnc=0` 中国移动，`UnavailableSpec`/`FieldSpec` hint「移动网络代码，如 0」）→ 每次命中。检测级工具核对 PLMN 一致性即可发现——正是本项目「物理不可能值 = 检测特征」定为 P1 的那一类（同 psc=MAX_VALUE 于 GsmCellLocation）。无 crash/无 AnomalyReporter/无 logcat，blast radius 比旧 P1-7 小，但**根因未除，且在公共 surface 上必然复现**。

**测试盲区**：`UnavailableStateTest.exceptionalSurfacesDoNotReuseSnapshotSentinel` 只测纯函数 `resolvePlmnString`（已过）；acceptance matrix 四个场景 mnc 全用 260（3 位），**从未出现过前导零 MNC**，所以矩阵抓不到。`VerificationEngineTest.zero-padded observation is a MISMATCH...` 明确把「getter 不填充」写成**有意为之**（保证 inert 模块被报 MISMATCH），因此这是设计权衡而非疏漏——但它违背本 PR 自己的 finish line（“one profile carrier decision observed consistently through every public surface”）。

**修复方向**：getter lambda 复用 `Snapshot.resolvePlmnString(field, configured, real, unavailable)`（MCC 补 3 位、MNC 按值补 2/3 位），verifier 侧做归一化比较（把 configured `"0"` vs observed `"0"`/`"00"` 判为「值等价、但若与真实基线相同则标 AMBIGUOUS」而非 MISMATCH），维持验证健全性同时消除不可能值；matrix 增加 mnc=0/03 场景。

**复现路径**：profile 配 `mcc=460, mnc=0`（文档示例）→ 保存 → 目标 App `getAllCellInfo()` 的 serving CellIdentityLte：`getMncString()=="0"`、`getPlmn()=="46000"`、`getMccString()+"0" != getPlmn()`。API 28+（`getMncString` 存在）均命中。

### P2-1 — physicalChannel `--` 的 negative control 区分不了「正常工作」与「no-op 回归」（band/physicalCellId 恒等于 builder 默认值）

**文件**：`scripts/cellular_acceptance_matrix.py:172-189`（`_UNAVAILABLE_NEGATIVE_CONTROL_PATHS`）、`scripts/hook_verdict.py:89-101`、`HookUtils.hookPhysicalChannelConfig`（`HookUtils.java:1293-1340`）、`CellConstructorCompat.newPhysicalChannelConfig`（no-arg Builder）。

**证据**：`band=-- → 0`、`physical_cell_id=-- → -1`，而 no-arg `PhysicalChannelConfig.Builder.build()` 默认值正是 0/-1。若这两个 getter hook 回归为 no-op，observed 仍为 0/-1 → 直接断言过；negative control（对比 full-rscp 的 78/777）因 0≠78、-1≠777 也过 → **false green 仍在**。其余 5 条 control path（tac/nci/rsrp/networkOperator/networkType）只能抓「stale payload 回放」那一类回归，抓不了这一类。上轮 harness review 的 finding 1 明确点名了这条（“the only reason the other four would catch such a regression is incidental”），本轮 negative control 未覆盖 band/pci。

**修复方向**：像 `CarrierSurfaceCoverageTest` 那样给 `PhysicalChannelConfig.getBand/getPhysicalCellId` 加 registry 字节码 census（目前 census 只覆盖 carrier getters，不含 physicalChannel）；或把这两列从「negative control 能证明」的宣称里剔除并显式标注不可观测。

### P3-1 — Python matrix 仍硬编码 `"schemaVersion": 3`

`scripts/cellular_acceptance_matrix.py:206`。Kotlin 侧（writer/probe/MainHook）已统一引用 `ConfigPrefsSync.SCHEMA_VERSION`（P2-1 旧 finding 关闭），但第 4 处副本（Python）未跟。schema bump 时 matrix 会**大声失败**（hook 拒绝 v3）而非静默错，故仅 minor。

## FC-1~FC-10 标注

| FC | 处置 | 我的判定 |
|---|---|---|
| FC-1 guarded baseline 全 hook 共用 + 跳过 async | `currentSnapshot()` 单点 + `CellBaseline.from` 入 guard + `shouldRequestFreshCellInfo(..., extractingBaseline)` | **[FC:covered]**（OQ1 关闭：`BaselineExtractionGuard` 覆盖全部同步 hook group，async fresh request 在 guard 内被跳过，正常 target-app 行为不变） |
| FC-2 `data_state --` API 24–28→0 / 29+→-1 | `dataStateUnavailableValueForApi` | **[FC:covered]** |
| FC-3 carrier object unknown 用 null 而非 "" | `CARRIER_OBJECT_TEXT → null`；manager 保持 "" | **[FC:covered]**（OQ2 关闭：manager="" / identity+state+registration=null，matrix 双值断言在案） |
| FC-4 API 34+ `isNetworkRoaming` | 两个 roaming getter 都 hook；probe API 门控 | **[FC:covered]**（OQ3 关闭：alpha probe @RequiresApi(28)，registration 门控 API 30/34） |
| FC-5 UI 只认 v3 / runtime 收 v2 | `TransportSchemaContract` 三端共用 | **[FC:covered]** |
| FC-6 negative control 让 verified > configured | `configuredCount` 含 ambiguous；summary 只统计 primary fields；control 失败整单红 | **[FC:covered]**（但 band/pci 残留另见 P2-1） |
| FC-7 API 28–29 alpha 无 probe | `observeIdentityCarrier` @RequiresApi(28) | **[FC:covered]** |
| FC-8 partial+ambiguous 文案空白 | `partialVerificationDetail` 纯函数 + UI 测试 | **[FC:covered]** |
| FC-9 only-name 改写真实邻区 alpha | 四路 delivery 统一 `registerRealNeighborBypassesForCarrier` | **[FC:covered]**（OQ4 关闭：getAllCellInfo / PSL / TelephonyCallback / CellInfoCallback 同语义） |
| FC-10 spec 漂移（data state/neighbor/v2） | spec 校正 + `neighbor_cells_json` 移 UNSUPPORTED（附 reason） | **[FC:covered]**（上轮 P1-4 按 fail-closed 方向(a) 落地；半 PLMN 由 contract 层 mcc/mnc 成对校验兜底） |

## Verified clean（抽样复读，非穷举）

- 上轮 12 个 P1 全部有对应修复且有测试：`hasSpoofValue` 排除 unavailable（`unavailableOnly_doesNotActivateCellReconstruction`）、空列表兜底（`acceptBuiltCellListOrPassthrough` + `emptyCellConstruction_failsSafeToPassthrough`）、psc→-1 双 surface（`resolveGsmCellLocationField("psc",...)`）、半 PLMN（contract 成对校验 + editor 联动）、写侧 null cursor 中止（`?: throw`）、静默发布失败（`SaveResult.published` + `KEY_PUBLISH_FAILED` + PUBLICATION_FAILED UI）、编辑器加载崩溃（load() runCatching + 元数据降级）、`-` 残留（validationErrors 阻断保存）、迁移测试（androidTest + MigrationTestHelper + schema JSON 入库，`runMigrationsAndValidate` 真跑 v1 行迁移）。
- `MainHook.CURRENT` 仍是唯一运行时配置 owner；无新 store/queue/router/lifecycle owner（Map delta: none 成立）。
- `getBasestationId` 拼写已修（上轮 minor）；`fluctuate` sentinel 短路保留；`BaselineExtractionGuard` ThreadLocal 深度计数 + try/finally + remove() 不变。
- 发布语义：成功清 `publish_failed`，失败清 `published_at` + 置位；`hasPublicationFailure` 在 verify 页置 PUBLICATION_FAILED，杜绝「失败显示已保存」。
- recovery：`recoverIfPending` 对空 durable payload 返回 false；`recovered_pending fp=<指纹>` 与 `PREFS_BEFORE_FINGERPRINT` 比对（上轮「restore 错误字节仍 VERIFIED」已封堵）；`ACCEPTANCE_PASS` 移到 cleanup_transaction 且要求 RESTORE_FAILED=0。
- 负对照/JSON/版本矩阵：probe `putValue(JSONObject.NULL)`→Python None 类型精确匹配；`PublishedConfig.parse` 对非 string 数组元素/validate 失败返回 null（UI 报 Malformed 而非假绿）；v2/v3 在 UI、runtime、probe 三端同一 `TransportSchemaContract`。

## 边界声明

- 设备写未授权：未安装 APK、未改 LSPosed scope、未重启、未跑 instrumentation；androidTest 迁移测试只编译未运行。**本 verdict 不是 runtime feature-complete 证据**。
- 无 GitHub 凭证（无 gh auth / token / netrc / keychain），PR comment 无法由我直发；本文件即持久化记录，正文见下节，可一键粘贴。

## PR Comment 正文（粘贴用）

```markdown
### Independent Review — deepseek-flash @ 54be3cd

**Verdict: REQUEST_CHANGES**（0 P1? No：1 P1 + 1 P2 + 1 P3）

独立复跑（sandbox `/tmp/cat-cafe-review/feat-verify-ux/deepseek-flash`，未采信转述）：
- Gradle 四项 BUILD SUCCESSFUL；JUnit 234/0/0；Python 24 OK；bash -n & diff-check exit 0；androidTest 编译通过
- PR OPEN / MERGEABLE / mergeable_state=clean；HEAD 与你给的 SHA 精确一致

**P1-1 · getMncString/getMccString getter 仍 String.valueOf(Int)，mnc=0 → "0"（1 字符，stock 不可能），且与同一对象 getPlmn()="46000" 自相矛盾**
HookUtils.java:405-411/:421-427 的 hookPlmnStringGetter lambda 未走 resolvePlmnString 零填充（ctor 路径已修），getter 又覆盖 ctor 值 → 文档示例 mnc=0（中国移动）每次命中。matrix 四个场景 mnc 全 260，盲区。修复：getter 复用 resolvePlmnString + verifier 归一化比较（"0"~"00" 等价但标 AMBIGUOUS），matrix 加 mnc=0/03。

**P2-1 · band/physical_cell_id 的 `--` negative control 无法区分 no-op 回归（builder 默认 0/-1 与 sentinel 相同）**
`_UNAVAILABLE_NEGATIVE_CONTROL_PATHS` 含这两列，但 no-op 时 observed 仍 0/-1，直接断言与负对照都过 → 上轮 harness finding 1 点名的假绿对这两列仍在。建议加 PhysicalChannelConfig getter 的 registry census，或显式标注不可观测。

**P3-1 · scripts/cellular_acceptance_matrix.py:206 硬编码 schemaVersion=3**（第 4 份副本，bump 时大声失败）。

FC-1~FC-10 全部 [FC:covered]（FC-6 含 P2-1 残留）。上轮 12 个 P1 全部关闭且有测试。设备侧零写入，本轮非 runtime feature-complete 证据。
```

[深深/DeepSeek V4 Flash 🐾]

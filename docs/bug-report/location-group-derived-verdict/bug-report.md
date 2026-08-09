---
feature_ids:
  - cellular-hook-verification
topics:
  - android
  - verification
  - location
  - review
doc_kind: bug-report
created: 2026-08-01
---

# Location group replacement must not be reported as passthrough

## 报告人

GitHub Codex 在 PR #3 当前 HEAD 的 inline review thread `3685653258` 中报告；砚砚在
merge-gate 软守护中复核为真实问题。

## Bug 诊断胶囊

| 栏位 | 内容 |
|------|------|
| **1. 现象** | 配置 `latitude` + `longitude` 后，验证页仍把未配置的 `accuracy`、`altitude`、`speed`、`bearing` 标成“透传”。实际 hook 会新建一个 `Location`，这些 getter 返回组内默认值而非设备真值。 |
| **2. 证据** | `Snapshot.hasLocation()` 由经纬度成对激活；`HookUtils.createFakeLocation()` 固定设置 accuracy（未配置时 10），其余未设置值由新对象返回默认值；`VerificationEngine.buildReport()` 对所有 `cfg == null` 字段无条件返回 `PASSTHROUGH`。 |
| **3. 根因** | verification 采用逐字段独立判定，但 location hook 的运行时语义是组级对象替换；“未配置字段 = 透传”这个不变量在组替换生效后不成立。 |
| **4. 诊断策略** | 逆向追踪 profile 字段 → `Snapshot.hasLocation` → `createFakeLocation` → `DeviceObserver` → `VerificationEngine`，并与普通独立 getter 的 passthrough 路径对照。 |
| **5. 超时策略** | 若一个显式组派生态无法保持 summary/configuredCount/UI 一致，则停止局部分支，回到 `FieldVerdict` 状态模型补完整转移表。 |
| **6. 预警策略** | 若同类“字段独立判定 vs 对象组替换”再出现两次，扫描全部合成对象（Location/CellInfo/PhysicalChannelConfig），不再逐条补丁。 |
| **7. 用户可见交互修正** | 未配置但被 location 组替换生成的值显示为“联动值”，不再显示“透传”；它不计入用户配置字段数，也不影响已配置字段的成功/失败 verdict。 |
| **8. 验收** | JVM 回归测试锁定经纬度激活时四个 sibling 为 `GROUP_DERIVED`、透传计数为 0、configuredCount 仍为 2；普通未配置字段仍是 `PASSTHROUGH`。 |

## 根因分析

工作的普通 getter 路径能逐字段让 `null` 配置直接返回 framework 原值，因此
`cfg == null → PASSTHROUGH` 成立。Location 路径不同：经纬度成对配置后，多个 public API
都会返回 `createFakeLocation()` 创建的新对象。对象里的 accuracy/altitude/speed/bearing
即使没有 profile 值，也已经失去原始设备值。问题不在观测 API，而在 verification 没有表达
“未配置、但被所属对象组派生”的第三种状态。

## 修复方案与权衡

- 新增 `GROUP_DERIVED` field verdict，专门表达“用户未配置，但对象组替换生成了这个值”。
- 只在 `latitude` 与 `longitude` 同时配置时，对未配置的四个 Location sibling 使用该状态；
  不把通用 `cfg == null` 规则放宽，避免自然波动字段被误判成派生值。
- `GROUP_DERIVED` 有独立 summary 计数和 UI 标签，但不进入 `configuredCount`，也不改变
  `EFFECTIVE/PARTIALLY_EFFECTIVE` 对用户配置的判定。
- 放弃“直接隐藏这些行”：隐藏虽能消除错误文案，却会让 All 视图无法解释观测到的默认值。

## 验证方式

先运行新增的 `VerificationEngineTest` 看到缺少 `GROUP_DERIVED` 的正确红灯；实现后运行 targeted
JVM 测试，再执行 PR 的全量 JVM/Python/Debug/Release/R8/lintVital 门禁。设备写入仍不授权。

## 实测结果

- Red：`VerificationEngineTest` 因 `GROUP_DERIVED` / `groupDerived` 不存在而编译失败，失败原因
  与状态模型缺口精确一致。
- Green：targeted `VerificationEngineTest` + `VerifyUiContractTest` 通过。
- 完整 JVM：`testDebugUnitTest --rerun-tasks` 汇总 **238 tests / 0 failures / 0 errors /
  0 skipped**。
- 构建：`compileDebugAndroidTestKotlin`、`assembleDebug`、`assembleRelease`（R8）、
  `lintVitalRelease` 强制执行，Gradle **109/109 tasks，BUILD SUCCESSFUL**。
- Python：`unittest discover` **24 tests OK**；`bash -n scripts/test-hook.sh` 与
  `git diff --check` 通过。
- `lintDebug --rerun-tasks`：20 errors / 158 warnings，与既有基线同数；本次变更文件 0 error。
- 设备：零写入，未安装 APK、未改 LSPosed、未跑 instrumentation。

[砚砚/gpt-5.6-sol🐾]

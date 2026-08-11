---
feature_ids:
  - cellular-hook-verification
topics:
  - verification
  - field-types
  - review
doc_kind: bug-report
created: 2026-08-01
---

# Verification coercion must be bounded by FieldType

## 报告人

GitHub Codex 在 PR #3 inline thread `3685653253` 中报告；该 thread 虽随 diff 变更而
outdated，砚砚在 merge-gate 全线程复核中确认当前 HEAD 的机制仍可复现。

## Bug 诊断胶囊

| 栏位 | 内容 |
|------|------|
| **1. 现象** | TEXT 字段配置 `"1"` 时，观测到 `"true"` 或 `"1.0"` 会被判为 SPOOFED；对应 hook 只可能原样返回文本，因此这是 verification 假绿。 |
| **2. 证据** | `fieldMatches()` 先调用 `canonicalValuesMatch()`；除 MCC/MNC 外，后者无条件进入 `valuesMatch()`，而 `valuesMatch()` 对所有字符串执行 boolean 与 Double coercion。现有测试只覆盖 TEXT 大小写和 SSID 引号，没有覆盖类型泄漏。 |
| **3. 根因** | transport shape normalization 与 field semantic normalization 混在一个无 `FieldSpec` 的 helper 中；类型信息在调用点存在，却在比较层被丢弃。 |
| **4. 诊断策略** | 构造 TEXT `1 ↔ true/1.0` 负例，对照 BOOLEAN `1 ↔ true` 与 DOUBLE `5.0 ↔ 5` 的正例，确认需要收紧的是路由而非删除 coercion。 |
| **5. 超时策略** | 若按 `FieldType` 分派会破坏现有 FLOAT/PLMN/SSID contract，则停止局部条件叠加，提取 typed comparison table 后再实现。 |
| **6. 预警策略** | 任一 TEXT 仍能通过非字面比较，或 BOOLEAN/DOUBLE 既有正例转红，立即回到比较矩阵；不增加新的全局 fallback。 |
| **7. 用户可见交互修正** | 文本型运营商、SSID、接口名等只有 hook 真能产生的文本才显示“已生效”；数字样文本不再借用布尔/数值等价假绿。 |
| **8. 验收** | Red 测试锁定 TEXT `1` vs `true` 和 `1.0` 都为 MISMATCH；BOOLEAN `1` vs `true`、DOUBLE `5.0` vs `5`、FLOAT round-trip、PLMN width 与 quoted SSID 全部继续通过。 |

## 根因分析与修复方案

比较层需要两类不同的归一化：所有字段都允许 transport 的 trim 与 Android SSID 外层引号；
只有 BOOLEAN 允许 `0/1 ↔ false/true`，只有数值 FieldType 允许数值格式等价，MCC/MNC
另有平台规定的宽度归一化。修复将 `FieldSpec.type` 保留到最终分派点，不再让 TEXT 进入通用
boolean/Double coercion。

放弃为 `operator_name` 单独加黑名单：同一 failure mode 覆盖全部 TEXT 字段，字段级补丁会继续漏。

## 验证方式

先补 TEXT 两个负例并看到当前实现错误返回 SPOOFED，再最小化重写 typed comparison；随后跑
全部 verification JVM 测试和 PR 完整门禁。设备写入仍不授权。

## 实测结果

- Red：TEXT `operator_name="1"` 对 `true` 的回归测试实际得到 SPOOFED，1 test / 1 failed。
- Green：TEXT 对 `true`、`1.0`、`"1"` 全部 MISMATCH；BOOLEAN、DOUBLE、FLOAT、PLMN、
  quoted SSID 与信号波动既有测试保持绿色。
- 完整 JVM：`testDebugUnitTest --rerun-tasks` 汇总 **239 tests / 0 failures / 0 errors /
  0 skipped**。
- 构建：`compileDebugAndroidTestKotlin`、`assembleDebug`、`assembleRelease`（R8）、
  `lintVitalRelease` 强制执行，Gradle **109/109 tasks，BUILD SUCCESSFUL**。
- Python：`unittest discover` **24 tests OK**；`bash -n scripts/test-hook.sh` 与
  `git diff --check` 通过。
- `lintDebug --rerun-tasks`：20 errors / 158 warnings，与既有基线同数；本次变更文件 0 error。
- 设备：零写入，未安装 APK、未改 LSPosed、未跑 instrumentation。

[砚砚/gpt-5.6-sol🐾]

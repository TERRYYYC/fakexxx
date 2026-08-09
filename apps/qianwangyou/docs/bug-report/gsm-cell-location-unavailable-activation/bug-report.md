---
feature_ids:
  - cellular-hook-verification
topics:
  - cellular
  - unavailable
  - review
doc_kind: bug-report
created: 2026-08-01
---

# Unavailable GSM decisions must activate existing CellLocation surfaces

## 报告人

GitHub Codex 在 PR #3 当前 HEAD `7cc329d` 的 inline thread `3694188831` 中报告；
砚砚沿生产调用链核实为真实 P2。

## Bug 诊断胶囊

| 栏位 | 内容 |
|------|------|
| **1. 现象** | profile 只把 `lac`、`cid` 或 `psc` 设为 `--` 时，`TelephonyManager.getCellLocation()`、`PhoneStateListener` 与 `TelephonyCallback` 的现有 `GsmCellLocation` 都保持真实值，没有按 surface contract 返回 `-1`。 |
| **2. 证据** | 三个调用点均先执行 `if (!s.hasGsmCell()) return`；`hasGsmCell()` 有意排除 unavailable decision，故 `spoofedGsmLocationOrPassthrough()` 内已经存在的 `GSM_CELL_LOCATION -> -1` resolver 永远不可达。Sibling sweep 还确认 configured-only `psc` 同样不在旧谓词中。 |
| **3. 根因** | 一个谓词同时承担了两种不同决策：是否可以重建新的 `CellInfo` RAT，以及是否要变换调用方已经持有的 `GsmCellLocation`。前者必须排除 unavailable-only，后者必须包含 `lac/cid/psc` 的 configured 或 unavailable decision。 |
| **4. 诊断策略** | 把“新建对象”和“变换已有 surface”的激活语义拆开；用 unavailable-only 与 psc-only 负/正组合锁定边界，并确认生产 HookUtils 引用新谓词。 |
| **5. 超时策略** | 若新谓词导致 `getAllCellInfo`、`getPhoneCount` 或 RAT reconstruction 被激活，立即停止并回退；这些路径必须继续使用 `hasGsmCell()`。 |
| **6. 预警策略** | 只允许三个 `spoofedGsmLocationOrPassthrough()` 调用点切换；任何 CellInfo 构建/列表守卫变化视为 scope 泄漏。 |
| **7. 用户可见交互修正** | 用户选择 `--` 后，已有 GSM CellLocation API 与回调不再泄漏真实 LAC/CID/PSC；同时不会因为“不可用”选择凭空制造一个新蜂窝 RAT。 |
| **8. 验收** | unavailable-only `lac/cid/psc` 激活 CellLocation predicate 且 `hasGsmCell()` 仍 false；configured-only `psc` 激活；空 profile 不激活；HookUtils 生产字节码引用新 predicate；完整静态/JVM/build 门禁全绿。 |

## 修复方向

在 `Snapshot` 增加只描述 `GsmCellLocation` surface 的 predicate，覆盖 `lac/cid/psc`
的 configured 与 unavailable decision。三个已有对象/回调调用点改用该 predicate；所有
`CellInfo` 重建、phone-count 与 registered-cell 决策继续使用 `hasGsmCell()`。

设备写入仍未授权；本修复只做纯 JVM、编译与静态构建验证。

## 实测结果

- Red：新增测试因 `hasGsmCellLocationDecision()` 不存在而编译失败，精确锁定缺失的
  surface-specific decision。
- Green：unavailable-only、configured-only PSC、空 profile 与 HookUtils production linkage
  四条回归测试通过。
- 完整 JVM：`testDebugUnitTest --rerun-tasks` 汇总 **243 tests / 0 failures / 0 errors /
  0 skipped**。
- 构建：`compileDebugAndroidTestKotlin`、`assembleDebug`、`assembleRelease`（R8）、
  `lintVitalRelease` 强制执行，Gradle **109/109 tasks，BUILD SUCCESSFUL**。
- Python：`unittest discover` **24 tests OK**；`bash -n scripts/test-hook.sh` 与
  `git diff --check` 通过。
- `lintDebug --rerun-tasks`：20 errors / 158 warnings，与既有基线同数；本次变更文件
  0 error。
- 设备：零写入，未安装 APK、未改 LSPosed、未跑 instrumentation。

[砚砚/gpt-5.6-sol🐾]

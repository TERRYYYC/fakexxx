---
feature_ids:
  - cellular-hook-verification
  - profile-unavailable-state
topics:
  - cellular
  - rat-selection
  - review
doc_kind: bug-report
created: 2026-08-01
---

# Shared identity fields must not select a serving RAT

## 报告人

GitHub Codex 在 PR #3 对 `7cc329d` 的 review thread `3694209276` 中报告；当前 HEAD
`0fd9398e3862ddd920a94e71f7f5bd3a067fbbfa` 仍可由生产调用链复现。

## Bug 诊断胶囊

| 栏位 | 内容 |
|------|------|
| **1. 现象** | LTE、NR 或 WCDMA profile 同时配置 MCC/MNC（或 WCDMA LAC/CID）时，cell list 会额外构造一个 GSM serving cell；目标 App 看到矛盾的多个 serving RAT，Verify 页可能先选到伪造 GSM 而看不到目标 RAT 字段。 |
| **2. 证据** | `Snapshot.hasGsmCell()` 把 `mcc/mnc/lac/cid` 当成 GSM 激活字段；`buildCellInfoList()` 据此先 append GSM，再 append LTE/WCDMA/NR；全局 `CellInfo.isRegistered()` hook 将所有非 bypass 构造对象标为 registered。MCC/MNC getter 本身覆盖所有 RAT，LAC/CID 同时覆盖 GSM/WCDMA，因此这些字段不能判定 RAT。 |
| **3. 根因** | 同一个 predicate 混合了三种语义：共享 identity projection、RAT-specific construction、cell-list topology activation。此前围绕 group activation、unavailable-only 与 CellLocation 已连续修三轮，说明缺的是 serving-RAT 状态机，不是再加一个条件。 |
| **4. 诊断策略** | 先列状态×事件表，把共享字段、四类 RAT-specific 字段、unavailable 与 neighbor 配置分开；再用纯 Snapshot 测试锁定“哪些 RAT 可以构造”，最后审计全部 list/callback/subscription/build/preserve 调用点。 |
| **5. 超时策略** | 若必须从 `network_type` 猜 RAT、或需要新增持久字段才能修，则停止实现并升级 operator；本轮只允许使用已有 RAT-specific identity fields 与真实对象 getter projection。 |
| **6. 预警策略** | shared-only 仍触发 reconstruction、WCDMA PSC/uarfcn 仍依赖 GSM predicate、或 unavailable-only 能造 RAT，任一出现即判方案失败；同文件若新增三层 fallback，回到状态表重审坐标系。 |
| **7. 用户可见交互修正** | Profile 配了 LTE/NR/WCDMA + MCC/MNC 后，App 不再看到凭共享字段多造的 GSM serving cell；shared-only 决策投影到 Android 已有 serving identity，不凭歧义猜 RAT。 |
| **8. 验收** | Red 测试证明 shared MCC/MNC + LTE 会错误激活 GSM；Green 后 shared-only 不构造、每个 RAT 只由自己的 identity 字段激活、多个明确 RAT 可共存、unavailable-only 不构造；所有生产守卫使用 canonical reconstruction predicate，完整门禁全绿。 |

## 根因分析与修复方向

字段归属不等于对象构造授权。MCC/MNC 是 GSM/WCDMA/LTE/NR 的公共 PLMN，LAC/CID 是
GSM/WCDMA 的公共 area identity；它们可以由全局 getter hook 投影到框架已经返回的 serving
对象，但无法决定应该新建哪一种 CellInfo。新对象只由 RAT-specific identity 字段激活：

- GSM：`arfcn/bsic`；
- WCDMA：`psc/uarfcn`；
- LTE：`tac/ci/pci/earfcn/lte_bandwidth`；
- NR：`nci/nrarfcn/nr_pci/nr_tac`。

当没有明确 RAT construction decision 时，cell list 保持框架拓扑，只登记真实 neighbor bypass，
让 shared identity 与 signal getter 只作用于 serving 对象。`neighbor_cells_json` 保持正交，不选择
serving RAT。

## 验证边界

本问题影响 Android/Xposed runtime，但当前设备写入被明确禁止，因此使用 production call-chain、
纯 JVM predicate/bytecode coverage、Debug/Release/R8 构建验证；不安装 APK、不改 LSPosed、不跑
instrumentation，也不宣称新的 runtime feature-complete 证据。

## 修复与验证结果

- `Snapshot` 现在分别暴露 GSM、WCDMA、LTE、NR 的 RAT-specific construction predicate，
  `hasCellReconstructionDecision()` 是唯一 serving topology 汇总入口；shared identity、signal-only
  与 unavailable-only 均不会构造 `CellInfo`。
- `neighbor_cells_json` 只通过 `hasCellListMutationDecision()` 激活列表变换：它移除真实邻区并追加
  配置邻区，但保留框架 serving 对象及其注册态，不选择 RAT。
- explicit serving reconstruction 时，未被同 RAT 替换的真实 serving 会作为 bypass 对象保留，
  `CellInfo.isRegistered()` 返回 false；新构造的目标 RAT 才是 registered serving。
- Red 阶段新增状态机测试先因 production predicate 缺失而编译失败；Green 后完整强制重跑通过：
  JVM **252 tests / 0 failures / 0 errors / 0 skipped**，Python **24 tests OK**，
  `compileDebugAndroidTestKotlin`、Debug APK、Release/R8、`lintVitalRelease`、shell syntax 与
  `git diff --check` 全绿。`lintDebug` 保持存量 **20 errors / 158 warnings**，本轮改动文件 0 error。

[砚砚/gpt-5.6-sol🐾]

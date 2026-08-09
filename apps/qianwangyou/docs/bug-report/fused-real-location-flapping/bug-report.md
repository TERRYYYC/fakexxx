---
feature_ids: [F001]
topics: [android, google-play-services, fused-location, mock-location, temporal-acceptance]
doc_kind: bug-report
created: 2026-08-04
status: resolved
resolved: 2026-08-04
external_issue: https://github.com/TERRYYYC/FakeGps-test/issues/12
---

# Google Maps 在 Mock 与真实位置之间周期性闪断

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | System Mock 开启后，Google Maps 大部分时间停在 Kyiv mock 点，但会短暂跳回真实位置，再被下一次 mock 样本拉回。期望整个会话时间轴零真实位置泄漏。 |
| **2. 证据** | co-creator 手动盯图发现；Fable5 与 Sol 分别在 moto g54 上连续采样复现。旧 exact APK `e1e1885d…a636` 的 fused 样本出现 `mock → real 50.450886,30.410253 → mock`。Sol 的 gps+network 实验版在 network 仍为 Kyiv mock 时，第 29 个样本仍出现真实 fused，证伪“只补 network 即可”。 |
| **3. 根因** | 现有实现只控制 Android framework test provider，没有把 Google Play Services FLP 置为 mock mode。Google Maps 消费 GMS fused；framework gps/network 即使同源，FLP 仍可从自身融合状态输出真实位置。参考 App 的稳定路径直接调用 `FusedLocationProviderClient.setMockMode(true)` 与 `setMockLocation(...)`。 |
| **4. 诊断策略** | 先用持续 dumpsys 时间轴复现，再做 gps+network 单变量排除实验；反编译同机稳定参考 App；对照 Google 官方 FLP mock-mode 契约；沿 controller start/publish/stop/rollback 审计半成功与 cleanup marker。 |
| **5. 超时策略** | 单次快照不得作为稳定性结论。旧 APK 若在一轮窗口内未泄漏，延长观察或提高采样频率；若 GMS Task 无法在有界时间完成，事务失败并保留 cleanup marker，不允许报告 Running。 |
| **6. 预警策略** | 看到“日志已调用 setMockMode”但没有等待 Task、framework 与 GMS 各自维护状态、或验收只抓一个瞬间，均视为假绿信号。 |
| **7. 用户可见交互修正** | 不新增第二个开关；System Mock 仍是一个会话。GMS 不可用或启动失败必须显式失败，不能静默降级成已知会泄漏真实位置的 Google Maps 路径。 |
| **8. 验收** | 旧 exact APK `e1e1885d…a636` 被新独立探针在第 2 个样本击杀；新 APK 在真实 Maps 前台连续 120 次、每 0.5 秒采样全部为 `50.450100,30.523400 mock`。完整 picker → first-start → restart-clean → task removal → Maps → app-op recovery → Stop 链 exit 0；Stop 后 gps/network 回到系统来源且 Kyiv fused mock cache 消失。半成功与门禁变异结果在复审 packet 记录。 |

## Failure-mode audit

本轮与 picker P0 同属“开发者代理信号替代用户真实路径”，但新增了时间轴维度：picker 验了 app-op 旁路而没验真实入口；本轮验了某一时刻的 `last location` 而没验连续体验。抽象不变量是：**任何面向用户的持续状态承诺，harness 必须覆盖真实消费端与完整观察窗口，不能用单次内部快照替代。**

状态机扫描范围为 framework provider、GMS fused mock mode、durable cleanup marker、persisted delivery mode。四者不另立真相；controller 只有在所有必需层完成 start/publish 后才能进入 Running，任一 stop 层失败都必须保留 marker。

实现使用官方 `com.google.android.gms:play-services-location:21.4.0`。`CoordinatedMockProviderGateway` 把 framework gps/network 与 `FusedLocationProviderClient` 作为一个 provider primitive 暴露给既有 controller；GMS `Task` 在 service worker 上有界等待。`setMockMode(true)`、首次/后续 `setMockLocation` 或 `setMockMode(false)` 任一步失败都进入同一 cleanup ownership，不新增第二个持久状态。官方契约见 [FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient) 与 [Google Play services setup](https://developers.google.com/android/guides/setup)。

## 合入对账与最终结论

| 对账项 | 结果 |
|---|---|
| Reviewer exact head | Fable5 APPROVE `04848846599d65549aca7f86f1c6ef1f948eef24` |
| Squash merge | PR #10 merge commit `008923ecca96ab6e2234901e2a7dfbc595ff5737` |
| Source identity | `0484884^{tree}` 与 `008923e^{tree}` 均为 `2a7f8eb464e62715c5a01016d0b4f369d03c1743`；`git diff` 为空 |
| Artifact identity | 从 merge commit fresh clone + clean JBR 21 build 得到 `7e18cdcc9e950cf69d2da8f23d728064405e483e1d21a1f2fe982814f4a5f80c`，与 reviewer artifact 一致 |
| 增量构建漂移 | 主 worktree 首次增量产物 `98113d88…23e0` 来自复用的 DEX shard/cache；clean build 恢复 canonical hash，因此该样本作废，不是 squash 代码漂移 |
| Post-merge device | merged-main APK 在 Maps 前台 120×0.5 秒零泄漏；Stop 后 GMS fused cache 清除，gps/network 恢复系统来源，参考 App 恢复为唯一获准 mock app |
| Platform truth | PR #10 MERGED；Issue #12 由 `Fixes #12` 自动关闭；feature branch 已删除 |

Git commit 因 squash 必然改变，不能拿 commit id 相等当合入等价证明；这里用 Git tree 证明源码等价，再用 fresh clean build 证明交付 APK 等价。两层都相等，才足以回答“合进去的是否就是审过的”。

## 可分享的工程教训

### 1. 持续体验必须由时间轴门禁守护

旧门禁只读一次 `dumpsys`。泄漏约占 1/30 的观察窗口时，点采样约有 97% 概率判绿。修复后的门禁把真实 Google Maps 放在前台，以 0.5 秒间隔连续采 120 个 fused 样本，并要求每个样本都有 mock 标记且坐标等于生效档案。**被承诺的是一段体验，验收就必须覆盖一段时间。**

### 2. 开发者旁路不能冒充用户入口

此前 harness 直接写 `mock_location` app-op，证明了“授权后机制能跑”，却没有证明用户能从 Android 选择器找到千网游。picker 缺口与 fused 闪断表面无关，失效模式相同：内部代理信号替代了用户真实路径。先验证真实入口/消费端，再允许 adb 或内部状态承担后续自动化。

### 3. 相关性只是下一次实验的起点

第一次泄漏样本里，network 恰好是唯一明显的真实位置源，于是“补 network 就够”看起来合理。gps+network 单变量实验让 network 全程保持 Kyiv mock，fused 仍在第 29 个样本泄漏真实位置，直接证伪该因果判断，才把根因推进到 GMS FLP 未进入 mock mode。类似地，APK hash 不同只说明构建输入闭包尚未解释；它既不能直接证明脏源码，也不能直接证明代码漂移。

### 4. 门禁要先证明自己能杀掉旧缺陷

新实现 120/120 通过只能说明“新版在这把尺上是绿的”。把同一探针用于旧 exact APK，并在第 2 个样本得到 `FUSED_REAL_LOCATION_LEAK`，才证明尺子确实量到了 co-creator 肉眼看见的那次闪断，而不是碰巧测了另一个绿指标。

### 5. 对账要分清源码、环境与构建状态

源码身份用 Git tree；跨环境 artifact 必须绑定 JDK 等构建输入；同一环境的异常增量产物还要用 fresh clone/clean build 排除缓存。把三者揉成一个 APK hash 结论，会在“脏源码”“合法 JDK lowering”“陈旧增量 DEX”之间反复误判。

## 授权与报告人

- co-creator 于 2026-08-04 明确授权引入官方 `play-services-location` 依赖。
- co-creator 首次发现用户可见闪断；Fable5 开 Issue #12 并独立复现；Sol 负责根因排除实验与实现；Fable5 负责 exact-HEAD 独立复核。

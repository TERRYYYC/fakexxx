---
feature_ids: [F001]
topics: [android, mock-location, recovery, state-machine, app-op]
doc_kind: bug-report
created: 2026-08-03
status: resolved
resolved: 2026-08-03
---

# 首次启用未授权被误报为 provider 残留

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | 用户尚未在开发者选项选择千网游时首次打开 System Mock，系统没有 mock provider 残留，但界面宣称“无法移除残留位置”，并在重启后继续显示红色失败态。期望是说明尚未授权，选择当前 App 后重新打开开关。 |
| **2. 证据** | Fable5 在 PR #10 exact HEAD `65834f713443a92dde14560a84b9d3d6b988e786` 的全新 checkout 与 moto g54 上复现：操作前后 `dumpsys location` 均无 mock residue；force-stop 后仍恢复 `stop-and-use-hook` failure。 |
| **3. 根因** | `LocationDeliveryOrchestrator.enable()` 在系统 mutation 前正确预写 cleanup marker，却在 `controller.start()` 首个 `removeGpsProvider()` 因 app-op 失败且系统未改变时不清 marker；controller 又把 start/stop 的 `SecurityException` 合并为同一 recovery；Service `onDestroy()` 无条件 cleanup，进一步把 start failure 改写成 stop-residue failure。 |
| **4. 诊断策略** | 逆向追踪 marker → controller transition → UI model → Service onDestroy；扫描 `enable/refresh/disable/rollback/cleanupRuntimeOnly` 的所有 recovery 与 marker 边界。 |
| **5. 超时策略** | 若一个 TDD 循环无法表达“系统是否可能被改变”，停止布尔补丁，改为显式状态转移结果并请架构 reviewer 复核。 |
| **6. 预警策略** | start/stop 再次共用含糊 recovery、无残留时 marker 仍为 true、或同一文件新增三层以上 fallback，均说明坐标系仍错。 |
| **7. 用户可见交互修正** | 首次未授权显示“尚未取得权限；选择当前千网游后重新打开开关”，开关可重试且不显示“重试停止”；真正 stop 残留仍保留现有重选与重试停止路径。 |
| **8. 验收** | controller 区分 start/cleanup recovery；orchestrator 首次 start denied 后清 marker；runtime cleanup 在 Hook + marker=false 时不触碰 provider；UI 两套文案/按钮行为独立测试；全量 JVM、构建、结构契约与两条真机路径复验。 |

## Failure-mode audit

本轮与 R1 的 app-op recovery finding 同属“状态转换缺路径”：失败结果没有同时携带动作阶段、系统残留可能性与用户下一步。扫描结果只有三组同型入口：首次 `enable/start`、运行中 `refresh/start`、`disable/rollback/cleanupRuntimeOnly`。修复必须让首次 start denied 走无残留边，同时保持后两组的清理边不变。

## 报告人

Fable5 在 PR #10 R2 独立 review 与 moto g54 验收中发现；Sol 负责根因调查、实现与 author dogfood，Fable5 负责下一轮 exact-HEAD 复核。

## 复现步骤

1. 保持参考 Fake GPS Location 为唯一 mock-location App，Bench 未获 app-op，系统 provider 为真实 GNSS。
2. 在 Bench 设置页首次打开 System Mock。
3. 观察失败文案、`dumpsys location` 与持久 marker 的重启行为。

R2 实际为系统无 mock residue，但 UI 显示“移除残留位置/重试停止”，force-stop 后仍恢复红色错误。期望为选择当前千网游后重新打开开关，且重启回到干净 Hook。

## 修复方案

- `MockProviderState.Failed` 显式携带 `providerCleanupRequired`，不再从一条平台异常猜残留。
- recovery 拆为 `SelectThisAppAndRetryStart` 与 `ReselectThisAppAndRetryStop`；UI 分别提供开关重试和 Stop 重试。
- controller 只在 provider 可能已存在或 mutation 已开始时执行 best-effort cleanup；首次 remove 被拒绝不制造第二次 cleanup 失败。
- orchestrator 在首次 start denied 且系统未改变时清除预写 marker；普通 Hook + marker=false 的 `cleanupRuntimeOnly()` no-op，避免 `onDestroy()` 把 start failure 改写成 stop failure。
- `refresh()` 显式声明 System Mock 可能已拥有 provider，保留真正残留场景的清理 recovery。

没有采用按错误字符串或当前文案反推动作阶段的方案；动作阶段和系统 ownership 是 domain state，不是 UI fallback。

## 验证方式

- TDD：首次 start denied 的 marker、controller recovery、UI 文案/按钮、Hook runtime cleanup 与 refresh sibling 均先红后绿。
- JVM：412/412，0 failure/error/skipped。
- 结构契约：6/6；Debug/Release 与 `lintVitalRelease` 通过。
- 变异：合并 start/stop recovery、跳过 marker clear、删除 Hook cleanup guard、丢失 refresh ownership context 四个变异均编译成功并被定向断言击杀。
- moto g54：`FIRST_START_PERMISSION_GUIDANCE_VISIBLE` → provider 仍为 GnssService → `FIRST_START_RESTART_CLEAN`；随后 Kyiv、任务移除、Maps、真实残留 recovery 与最终 restore 全链 exit 0。

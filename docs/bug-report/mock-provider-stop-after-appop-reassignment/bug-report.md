---
feature_ids: [F001]
topics: [android, mock-location, appops, recovery, lifecycle]
doc_kind: bug-report
created: 2026-08-03
---

# Mock app 被改选后 System Mock 无法停止

## 报告人

Fable5 在 PR #10 exact HEAD `e9274cd` 的 moto g54 独立验收中发现；Sol 负责定位与修复。

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | System Mock 已运行时，用户在开发者选项把模拟位置 App 改成其他 App；回千网游关闭开关后显示 `not allowed to perform MOCK_LOCATION`，系统 `gps provider` 仍锁在 Kyiv mock。期望是用户能理解原因并完成恢复。 |
| **2. 证据** | Android 15 / moto g54：改选前 `gps provider [mock] identity=…bench`；改选后 Stop 得到 `Failed(...not allowed to perform MOCK_LOCATION; cleanup failed: ...)`；provider 仍为 mock。重新把 mock app-op 给回 bench 后，同一 Stop primitive 可恢复 `1000/android[GnssService]`。 |
| **3. 根因** | `LocationManager.removeTestProvider("gps")` 受当前 mock-location app-op 约束。系统把 mock app 改选给别的包后，原 owner 失去移除权限，但 test provider 是 system_server 状态并不会随 app-op 自动消失。App 无法替用户重新取得 app-op；当前 Failed 仅显示平台异常，恢复步骤不可发现。 |
| **4. 诊断策略** | 逆向追踪 `Settings → MockProviderRuntime → Service → orchestrator.disable → controller.stop → AndroidMockProviderGateway.removeGpsProvider`，对照 acceptance trap 临时归还 app-op 后能清理的工作路径；用纯状态分类测试 + 真机改选流程验证。 |
| **5. 超时策略** | 若结构化分类无法稳定识别 Android 24–35 的权限异常，停止字符串补丁，改在 gateway 边界把 `SecurityException` 映射为 domain failure；不增加第三层 fallback。 |
| **6. 预警策略** | 修复若试图静默修改开发者选项、要求 root、或在没有真实 `GnssService` 证据时清 marker，方向立即判错。 |
| **7. 用户可见交互修正** | Failed 明确显示“千网游已不再是模拟位置 App”；内联入口打开开发者选项，指导重新选择当前千网游后点击“重试停止”。 |
| **8. 验收** | JVM 测试证明 SecurityException 映射为可恢复 action；UI contract 暴露明确指引；真机运行中改选参考 App → Stop 失败且指引可见 → 重选 bench → 重试 Stop → provider 恢复 GnssService。 |

## 修复方案

在 gateway/controller 边界保留平台错误原文，同时把 mock app-op `SecurityException` 映射为结构化恢复动作；UI 根据动作渲染可操作指引。cleanup marker 保持 true，只有重新取得 app-op 并真实移除 provider 后才清除。没有使用 root、自动 app-op 或伪造“已停止”。

## 验证方式

- `MockProviderSessionControllerTest`: 权限错误的 recovery action 与 cleanup 失败文本。
- `LocationDeliveryUiContractTest`: Failed 的开发者选项指引、switch gating 与 retry Stop。
- `mock_provider_acceptance.sh`: 实际改选 mock app、失败 UI、重新选择与真实 GNSS 恢复。

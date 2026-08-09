---
feature_ids:
  - cellular-hook-verification
topics:
  - acceptance
  - recovery
  - room-migration
doc_kind: bug-report
created: 2026-07-30
---

# Acceptance recovery compared mutable metadata instead of hook content

## 报告人

砚砚在为 profile `--` 显式不上报状态运行真机 `--cellular-matrix` 时发现。

## 复现步骤

1. 安装包含 Room `1→2` migration 与 transport schema v3 的 debug APK。
2. 运行 `scripts/test-hook.sh --cellular-matrix`。
3. recovery probe 发布临时 payload 后被 SIGKILL。
4. 正常 Activity 启动并记录 `recovered_pending`。

期望：恢复前一份 hook payload，数据库保持不变，继续执行 matrix。

实际：首次运行同时报告数据库变化与 transport 指纹未恢复；再次运行只报告
transport 指纹未恢复。

## 根因分析

### Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| 现象 | `recovered_pending` 已出现，但 harness 报 transport fingerprint mismatch；首轮还报 DB changed |
| 证据 | 首轮失败后第二轮原样复现：第二轮 `restore.database unchanged`，但 transport 仍失败 |
| 根因 | DB 快照可能早于首次 Room migration；transport 快照 SHA 覆盖整个 XML，把正常变化的 `published_at` 当成 hook 内容变化 |
| 诊断策略 | 比较首轮/二轮输出，并逆向追踪 `snapshot_db`、`snapshot_prefs`、`ConfigPrefsSync.sync` 与 recovery coordinator |
| 超时策略 | 两轮不能区分状态来源则在脚本边界输出 DB schema 与 JSON/published_at 分量 |
| 预警策略 | 若语义 JSON 相同仍失败，禁止修改 production recovery；先修 harness 的比较坐标系 |
| 用户可见交互修正 | 无；仅修 debug-only acceptance 判定 |
| 验收 | 两条脚本测试 Red→Green；真机 matrix `274/274`、`restored=true`、DB 与 transport 恢复 |

`ConfigPrefsSync.sync()` 会把 hook 消费的 JSON 与 UI 使用的 `published_at` 放在同一
SharedPreferences 文件。恢复后正常 Activity 会重新发布相同 JSON，并合法更新
`published_at`。原 `snapshot_prefs()` 对整个 XML 做 SHA-256，因此把时间戳变化误判为
hook payload 未恢复。

首次运行的 DB 差异来自另一条时序：Compose Activity 已启动但 Room migration 尚未完成，
harness 就采集了旧 schema 的保护快照；稍后 UI 打开 Room 添加
`unavailable_fields`，最终快照自然不同。第二轮 migration 已完成，所以 DB 差异消失。

## 修复方案

- transport 快照只比较 `<string name="json">`，与 hook 的真实消费边界一致；
- matrix preflight 等待 provider 暴露 `unavailable_fields` 后才采集 DB/transport 保护快照；
- production recovery 逻辑保持不变。

## 验证方式

- `test_transport_snapshot_compares_payload_not_publish_metadata`：先红后绿；
- `test_matrix_preflight_waits_for_room_migration_before_protection_snapshot`：先红后绿；
- `bash -n scripts/test-hook.sh`；
- Moto g54 5G / Android API 35 真机 matrix：
  `configured=274, verified=274, failed=0, restored=true`；
- 结束时 `restore.database unchanged` 与
  `restore.transport database-backed fingerprint restored` 均通过。

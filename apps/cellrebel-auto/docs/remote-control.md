---
topics: [remote-control, adb, unattended-ops, audit]
doc_kind: ops
created: 2026-09-07
feature_ids: [P1.2, T3]
---

# Remote Control — 一行 adb 命令驱动引擎（P1.2 / T3）

无人值守运维入口：`am broadcast` 直接驱动 CellRebel Auto 引擎，替代被 HyperOS 封锁的
UI 坐标点击（实测一次 Stop 靠坐标点击要 8 分钟且误触过新 run）。

- **实现**：`app/src/main/java/com/example/cellrebelauto/remote/RemoteControlReceiver.kt`
- **声明**：`app/src/main/AndroidManifest.xml`（exported receiver + signature 权限）
- **纪律**：receiver 不含任何状态机；每个动作只路由到 **UI 按钮背后的同一入口**，
  并向 `auto_audit_events` 写一行 `REMOTE_CONTROL_*` 审计。

## 动作 → 入口映射

| 动作 | 引擎入口（与 UI 按钮一致） | UI 对应 |
|---|---|---|
| `START_PLAN` | `AutomationService.startAutomation(最新计划id)` | Plan 页 "Start Plan" |
| `RESUME` | 同上（Start 与 Resume 是同一幂等入口，INV-9 恢复清扫） | Plan 页 "Resume Plan" |
| `STOP` | `AutomationService.stopAutomation()` | Plan/Run 页 "Stop" |
| `RESET_PLAN` | `PlanRepository.resetPlanAsFreshGeneration()` | Plan 页 "Reset plan & re-run" |
| `STATUS` | 只读 AutomationService 状态投影 + 最新计划进度，setResult 回显 | Run 页仪表盘 |

## 权限说明

`<applicationId>.permission.REMOTE_CONTROL`，`protectionLevel="signature"`：

- 只有 **同签名应用**（与 Auto 用同一 keystore 签名）可以发送；系统在分发前就拒收。
- **uid 0（root）豁免** —— 这是 adb 运维通道的原理。设备已 Magisk root，
  所以示例统一走 `adb shell su -c "am broadcast ..."`。
  （不带 root 的普通 `adb shell am broadcast` 以 shell uid 2000 发送，会被权限
  拒绝，logcat 出现 `Permission Denial: ... requires <pkg>.permission.REMOTE_CONTROL`。）
- action 与权限名都按 applicationId 命名空间隔离：debug / glmbench / release
  多 lane 共存时互不可控。下面示例全部用 glmbench lane
  （`P=com.example.cellrebelauto.glmbench`；其他 lane 换成对应包名即可）。

## 每动作示例（glmbench lane）

```bash
P=com.example.cellrebelauto.glmbench

# 启动最新计划
adb shell su -c "am broadcast -a $P.remote.control.START_PLAN -p $P"

# 暂停后恢复（同一入口）
adb shell su -c "am broadcast -a $P.remote.control.RESUME -p $P"

# 停止当前 run
adb shell su -c "am broadcast -a $P.remote.control.STOP -p $P"

# 计划重置为新一代（仅计划全部完成、或存在 RECOVERY_REQUIRED 死尝试时被允许；
# 未完成计划会被事务内守卫拒绝，绝不盲目重置）
adb shell su -c "am broadcast -a $P.remote.control.RESET_PLAN -p $P"
# 注意：Auto 侧 reset 只是 #12 重跑序列的第一步，随后仍需 provider 侧
# schedule_reset + 重新配对批准（见 src/debug/AndroidManifest.xml 的四步说明）。

# 查询引擎态
adb shell su -c "am broadcast -a $P.remote.control.STATUS -p $P"
```

## STATUS 用法

`am broadcast` 是有序广播，会打印结果行：

```text
Broadcast completed: result=1, data="Running: WAITING_INTERVAL (Running test...) progress=7/20 plan=worklist-285.csv"
```

**resultCode 编码**：

| result | 含义 | 运维动作 |
|---|---|---|
| `0` IDLE | 服务在、引擎空闲 | 直接 `START_PLAN` |
| `1` RUNNING | 引擎运行中 | 进度在 data 行内（可信成功数/总配额） |
| `2` HELD | 引擎停在需要人工的终态（PAUSED / ERROR / SERVICE_RECYCLED） | 按 data 行原因处理后 `RESUME` |
| `3` SERVICE_DOWN | 无障碍服务未连接 | 先开无障碍开关，再 `RESUME` |

data 行末尾若带 `lastStartRejection=<reason>`，是引擎最近一次 Start 拒因
（`SERVICE_NOT_CONNECTED` / `PLAN_NOT_FOUND` / `BOTH_STAGES_OFF` / 准入拒因）。
Extras（`state` / `running` / `service` / `plan` / `progress` / `summary`）供程序化
消费，可通过 logcat 查看：`adb logcat -s RemoteControl`。

## 无效迁移安全 no-op

| 场景 | 行为 |
|---|---|
| 无计划时 `START_PLAN` / `RESUME` | 引擎入口**零调用**；logcat WARN `REFUSED_NO_PLAN`；审计 `result=REFUSED_NO_PLAN` |
| 无计划时 `RESET_PLAN` | 同上（repository `NoPlan`） |
| 未完成且无死尝试时 `RESET_PLAN` | 守卫拒绝；WARN 带 reason；审计 `result=REFUSED;...` |
| 未持权限的发送方 | 系统层直接拒收（root 豁免路径外还接收内二道检查）；**忽略** + WARN（含 caller 标识），不写审计 |

## 审计

每个**被受理**的动作写一行 `auto_audit_events`：`eventType = REMOTE_CONTROL_<动作>`，
`correlationRef = caller=<调用方>`，`payloadDigest = result=<DISPATCHED|REFUSED_NO_PLAN|REFUSED:<reason>|RESET:<新计划id>|STATUS:<code>>`。
设备上查看：

```bash
adb shell su -c "sqlite3 /data/data/$P/databases/cellrebel_auto.db \
  'SELECT seq,eventType,payloadDigest,recordedAt FROM auto_audit_events ORDER BY seq DESC LIMIT 10;'"
```

无权限被拒的广播**不写审计**（未授权方不能污染审计流），只留 logcat WARN。

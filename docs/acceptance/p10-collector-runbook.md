---
feature_ids: [7]
topics: [acceptance, g2, p10, fault-injection, revoke, collector, runbook]
doc_kind: evidence
created: 2026-08-27
status: draft-interface
---

# P10 debug-only fault/revoke collector — 命令面 runbook

G2 §3 P10 的前置能力面（g2-s3-gate-audit-2026-08-27-ZY22.md P10 判定的缺口）。
本文档是 collector 的**接口真相源**：@codex-sol 冻结逐注入 exit/restore matrix、
@fable5 核验命令可执行性，都以本文的命令与词表为准。改名 = 重开矩阵。

## 硬边界（实现已内建，勿绕）

1. **production 零携带**：collector 只存在于两个 app 的 `src/debug`。
   CI 在两个 app lane 各自 `assembleRelease` 并用
   `scripts/check-debug-only-collector.sh` 做 dex 字节扫描（marker
   `P10DBG-COLLECTOR-V1`）；`scripts/selftest-debug-only-collector.sh` 是其
   负矩阵。Kotlin 侧守卫：`P10CollectorSurfaceGuardTest`（双侧）。
2. **exact-window**：所有 arm 类触发都以**本 app 自己的持久状态**为门（qwy =
   FileDurableKv 落盘 lease 状态；Auto = Room 行），不是定时/日志竞速。
3. **durable 读回**：qwy `cmd=dump` 每次新建 FileDurableKv 从磁盘装载（绝不
   boot 运行时单例——那会触发 §8.4 recovery 摧毁证据）；Auto `cmd=state` 走
   Room 查询。读回诚实性由 `QwyDurableSnapshotTest`（含"未落盘必须读回缺失"
   的 mutation 测试）钉死。

## qwy 侧（`name.caiyao.fakegps.bench`）

组件：`app/src/debug`…`/integration/v1/FaultCollectorActivity.kt`、
`PairingApprovalActivity.kt`（扩展 revoke）、`CollectorGate.kt`、
`QwyDurableSnapshot.kt`。

```
# 状态读回（before/after 证据；绝不触发 recovery）
adb shell am start -n name.caiyao.fakegps.bench/name.caiyao.fakegps.integration.v1.FaultCollectorActivity \
  --es cmd dump [--es app_id <caller> --es signer <sha256>]

# 撤销 caller（§5C 新 run 前；真实 transition：pairing revoke + lease REVOKED + audit）
adb shell am start -n .../PairingApprovalActivity \
  --es revoke_application_id <appId> --es revoke_signer_digest <sha256>
#   追加 --es revoke_run_cleanup 1 → 同时跑 §6.3.3 qwy 内部自清理
#   （REVOKED → RELEASING → RELEASED / RELEASE_INCOMPLETE）

# §5C run 中撤销（exact-window：caller 的 lease 落盘 ACTIVE 的那一刻开火）
adb shell am start -n .../FaultCollectorActivity \
  --es cmd arm --es action revoke_caller --es caller <appId> --es signer <sha256> \
  --es gate lease_active [--ei poll_ms 200] [--ei timeout_ms 600000]

# §5B 进程重启窗口（lease ACTIVE/ACQUIRING/RELEASING 时 SIGKILL 自己）
adb shell am start -n .../FaultCollectorActivity \
  --es cmd arm --es action self_kill --es gate lease_active   # 或 lease_acquiring / lease_releasing

# REVOKED lease 的 provider 内部自清理（独立命令）
adb shell am start -n .../FaultCollectorActivity --es cmd cleanup_revoked

# 取消 pending arm
adb shell am start -n .../FaultCollectorActivity --es cmd disarm
```

arm 全生命周期写 `filesDir/debug-collector/arm.log`（ARMED/FIRED/TIMEOUT/
DISARMED/OUTCOME，`ArmRecordCodec` 行格式）。

## Auto 侧（`com.example.cellrebelauto` debug 变体）

组件：`app/src/debug`…`/integration/v1/ProviderRevokeCollectorActivity.kt`、
`RevokeCollectorGate.kt`；`FullLoopProbeActivity.kt` 扩展 fault 模式。

```
# 状态读回：pairing 行 + running attempts(含 aplusState) + trusted 总数
adb shell am start -n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.ProviderRevokeCollectorActivity --es cmd state

# §5C 新 run 前撤销 provider（同一 AppDatabase 单例，UI 同源）
adb shell am start -n .../ProviderRevokeCollectorActivity \
  --es cmd revoke --es app_id <providerAppId> --es signer <sha256>

# §5C run 中撤销（exact-window 门控）
adb shell am start -n .../ProviderRevokeCollectorActivity \
  --es cmd arm --es action revoke_provider --es app_id <p> --es signer <s> \
  --es gate run_active            # 或 attempt_state:<STATE>（§8.1 枚举名）

# §5B Auto checkpoint 崩溃（trusted ledger 不重计）
adb shell am start -n .../ProviderRevokeCollectorActivity \
  --es cmd arm --es action self_kill --es gate trusted_count:<N>

# FullLoopProbe fault 模式（§5B）
adb shell am start -n .../FullLoopProbeActivity --es fault hold_lease --ei hold_ms 30000
adb shell am start -n .../FullLoopProbeActivity --es fault release_receipt_loss
adb shell am start -n .../FullLoopProbeActivity --es fault crash_after_apply
adb shell am start -n .../FullLoopProbeActivity --es fault rerelease_stuck --es lease_id <id>
```

## 门控词表（冻结）

| 侧 | token | 语义 |
|---|---|---|
| qwy | `lease_active` / `lease_acquiring` / `lease_releasing` | 当前 lease 落盘态 == ACTIVE / ACQUIRING / RELEASING（可 `--es caller` 限定归属） |
| Auto | `run_active` | 存在 status ∈ {starting, running} 的 test_attempts 行 |
| Auto | `attempt_state:<STATE>` | running 行的持久 aplusState == STATE（§8.1 枚举） |
| Auto | `trusted_count:<N>` | trusted_quota_entries 总数 ≥ N（提交后即刻开火） |

fault 名（冻结）：`hold_lease` / `release_receipt_loss` / `crash_after_apply` /
`rerelease_stuck`（恢复工具，非注入）。

## 退出与归真锚点（matrix 冻结前的最小事实）

- `hold_lease` / `crash_after_apply` 期间设备处于 mock 状态——**by design**。
  出口：Auto `rerelease_stuck --es lease_id`（§6.3.3 carve-out，release 接受
  ACTIVE/EXPIRED/RELEASE_INCOMPLETE）；qwy 侧 `cmd=dump` 复核；仍不清场 →
  按包底线停场进人工恢复（force-stop 不可清 mock，已证）。
- `release_receipt_loss` 自收敛（两条腿都是真 release，第二腿幂等回执）。
- qwy `self_kill` 后下一次进程启动（服务 bind 或任何 boot 路径）触发 §8.4
  recovery——**不要用 cmd=dump 之外的方式先读回**，dump 不 boot 单例。
- 一次只挂一个 arm；换注入前先 `disarm` 或确认 FIRED/TIMEOUT 已落 arm.log。

## 测试与证据链

- RED：双侧 `P10CollectorSurfaceGuardTest`（main 上 6+5 红，取证于 commit
  `[G2-P10] RED`）。
- GREEN 逻辑：`CollectorGateTest` / `RevokeCollectorGateTest`（src/testDebug）；
  读回诚实性：`QwyDurableSnapshotTest`（未落盘必须读回缺失 + capture 不改
  durable 字节 + 目录漂移守卫）。
- 纯度：`check-debug-only-collector.sh`（源 + APK 双查）+ 负矩阵
  `selftest-debug-only-collector.sh`（6 case）。

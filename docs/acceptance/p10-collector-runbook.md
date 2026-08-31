---
feature_ids: [7]
topics: [acceptance, g2, p10, fault-injection, revoke, collector, runbook]
doc_kind: evidence
created: 2026-08-27
status: matrix-frozen
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
#   追加 --ez revoke_run_cleanup true → 同时跑 §6.3.3 qwy 内部自清理
#   （REVOKED → RELEASING → RELEASED / RELEASE_INCOMPLETE）

# §5C run 中撤销（exact-window：caller 的 lease 落盘 ACTIVE 的那一刻开火）
adb shell am start -n .../FaultCollectorActivity \
  --es cmd arm --es action revoke_caller --es caller <appId> --es signer <sha256> \
  --es gate lease_active [--el poll_ms 200] [--el timeout_ms 600000]

# §5B 进程重启窗口（lease ACTIVE/ACQUIRING/RELEASING 时 SIGKILL 自己）
adb shell am start -n .../FaultCollectorActivity \
  --es cmd arm --es action self_kill --es gate lease_active   # 或 lease_acquiring / lease_releasing

# REVOKED lease 的 provider 内部自清理（独立命令）
adb shell am start -n .../FaultCollectorActivity --es cmd cleanup_revoked

# §8.4 EXPIRED 前置（M-LS-12）：确定性写 clean-shutdown marker。
# onDestroy 不保证在 force-stop 时触发，此命令在 LIVE provider（如 hold_lease 窗口内）
# 直接调 ProviderRuntime.recordCleanShutdown()，再 dump 回读确认 marker=true 再重启。
# 无 provider 运行时（kvRef null）为 no-op，dump 会 loud 显示 marker 未置位。
adb shell am start -n .../FaultCollectorActivity --es cmd mark_clean_shutdown

# 取消 pending arm
adb shell am start -n .../FaultCollectorActivity --es cmd disarm
```

`cmd=dump` 现额外回读 `clean-shutdown marker set (§8.4 EXPIRED precondition): <bool>`
（非破坏性读——`consume` 只在下次进程启动时清，dump 不清），供执行者在重启前确认
EXPIRED 分支前置已成立、不盲跑。

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
#   （poll/timeout 同 qwy：--el poll_ms / --el timeout_ms）

# §5B Auto checkpoint 崩溃（trusted ledger 不重计）
adb shell am start -n .../ProviderRevokeCollectorActivity \
  --es cmd arm --es action self_kill --es gate trusted_count:<N>

# FullLoopProbe fault 模式（§5B）
adb shell am start -n .../FullLoopProbeActivity --es fault hold_lease --el hold_ms 30000
adb shell am start -n .../FullLoopProbeActivity --es fault release_receipt_loss
adb shell am start -n .../FullLoopProbeActivity --es fault crash_after_apply
adb shell am start -n .../FullLoopProbeActivity --es fault rerelease_stuck --es lease_id <id>
```

## §5A seed 命令面（10-address backfill）

§5A 十地址块的前置：Auto 产品 run 从 device shell 不可达（plan 只能 UI `importCsv`
文件选择器；run 只能 `AutomationService`，`exported=false`+`BIND_ACCESSIBILITY_SERVICE`）。
下列 debug seed 面关闭该缺口（A/B/C 共享的缺口①）。**seed 面是 seed，不是 §5B/§5C 故障注入。**

payload 就是冻结 fixture 文件本身（`docs/acceptance/a-plus-10a-fixture.json`，digest
`cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852`）。执行者 host 侧
`base64 < a-plus-10a-fixture.json` 生成 payload、`shasum -a 256` 记 digest；两侧 seeder
各自对解码 payload 重算 SHA-256，与传入 digest 不等即 REFUSED。digest 与 payload 同源于
执行者，故 seeder **另行独立绑定注册结构**（恰好 10 项、fixtureIndex 1..10 连续同序、
`profile-N` 对齐、`scheduleId=qwy-default-schedule`、quota 和=17=声明值）——篡改/截断/乱序
即使带对 digest 也 REFUSED（PR #62 P1-2）。

```
# qwy：种 10 个 .bench profile（EXPLICIT id=1..10，坐标/tac/wifiSsid 逐项来自 fixture——
#      千网游是坐标唯一所有者，KB-8）
adb shell am start -n name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity \
  --es command prepare_10a \
  --es fixture_payload_base64 <base64(a-plus-10a-fixture.json)> \
  --es fixture_digest cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852
#   判据（R4 P1-1/gap⑦ 更新）：logcat MockProviderAcceptance 出 seed 映射 +
#   SEED_LOCAL_VERIFIED command=prepare_10a（本地腿全证：digest pin、结构+quota 向量、
#   显式 id、单调+owner-quiescent generation+回读、ConfigPrefsSync 发布）
#   **且** SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7（有序 discover() 回读当前
#   无可执行命令——见末「gap⑦」）。**刻意不发 full-seed-PASS 的 READY**——§3 seed 契约
#   含有序回读腿，未满足前发 READY 即假绿（opus5 裁定该假绿阻塞 merge）。
#   失败发 SEED_FAILED command=prepare_10a，无任何 success 标记。
#   seed 内部对 schedule store 做**单调 generation reset**（R3 P1-2）：读当前 version、
#   写 V+1 + pointer=profile-1 + exhausted=false（单原子 commit）、再回读校验。
#   ⚠️ 不是 clear()——clear 会让下次 boot 重置回 version 1（回滚），违反 M-AD-24/spec
#   L1895-2056「每次 reinit 必须 V→V+1」，旧 (schedule,item,version) 身份会与新 run 撞车。
#   ⚠️ owner-quiescence 三点前置（R4/R5 P1，写 store 在 owner fence 外）：owner service 必须
#   down（liveness unknown = fail-closed）、无非 converged lease（仅 absent/RELEASED 放行，
#   REVOKED/RELEASE_INCOMPLETE/EXPIRED 拒）、durable ADVANCE_PENDING slot 必须为空（否则已
#   commit 的 advance 会在下次 fenced entry/boot 回放到新 seed 上）。整个 seed（reset+profile
#   重写+publish）以 owner 的 durable audit seq 为见证收尾：seed 前后 audit seq 不变 + 最终
#   schedule 再回读一致，才算证明无 fenced owner 写穿插（观测式计时不能消 TOCTOU）。
#   前置不满足即 SEED_FAILED——执行者须先 force-stop bench 让 owner 静默、并确认无 pending advance。
#   报告发 SCHEDULE_GENERATION priorState=.. versionAfter=..。seed 后须 force-stop
#   name.caiyao.fakegps.bench 再 bind，随后 discover() 回读 currentItemId=profile-1 +
#   scheduleVersion（可执行子集）——完整有序 profile-1..10 list 回读依赖 profileRefs
#   projection scope 决定（见 §5A 末「已知 product/spec 缺口」）。

# Auto：种 plan + 10 task（csvRow/priority=fixtureIndex → 执行序=fixture 序）
#   KB-8（spec v1.62，operator 裁定）：Auto 只消费 {顺序, journeyCaseId, requiredSuccesses}，
#   **不导入坐标**——坐标千网游独占，Auto 无独立位置验证面（KB-8 永久 limit）。
#   LocationTask 的遗留 non-null 坐标列种入**结构性超界占位**（999.0，双轴均超出合法
#   地理域——不可能被误当真 target；isFiniteGeo 族校验一律直接拒绝而非算出一个
#   "看似合理"的距离）。冻结谓词不消费这些列。
adb shell am start -n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.APlusSeedActivity \
  --es cmd seed_plan \
  --es fixture_payload_base64 <base64(a-plus-10a-fixture.json)> \
  --es fixture_digest cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852 \
  [--el global_buffer_seconds 60]
#   判据：logcat ECAPlusSeed 出 fixtureIndex↔taskId↔journeyCaseId↔requiredSuccesses 映射
#   （LocationTask 无 journeyCaseId 字段——该映射是唯一归因来源）+ planId。

# Auto：启动 run（产品自身入口 AutomationService.startAutomation；无障碍服务须已启用）
adb shell am start -n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.APlusSeedActivity --es cmd start_run --el plan_id <planId>
#   planId 必须是 seed_plan 种出的 FX-G2-10A plan——start_run 会校验拓扑
#   （sourceFileName=FX-G2-10A / 10 行 / quota 冻结向量 [2,1,3,1,2,1,1,3,1,2] / csvRow 1..10 /
#   坐标列仍是 KB-8 占位），拓扑不符（外来 CSV import、错 id、同总额再分配）即 REFUSED（P2/P4）。
#   判据（R5 P2 更新 token）：REQUEST_ACCEPTED（isRunning 10s 内 true）只证【服务接受了请求】，
#   **非 durable start**（isRunning 在 plan load 前同步置位）；REQUEST_NOT_ACCEPTED = 无障碍
#   服务未连接（startAutomation 静默 no-op）。durable 真相 = ProviderRevokeCollector cmd=state
#   的 running attempt 行**显示 planId=<本 planId>**（state 现把每个 running attempt 解析到其
#   durable plan）；绑到别的 planId = 陈旧/外来 run，不是本请求。
```

**跨侧序对齐（承重不变式，实现已保证，执行者须知）**：Auto task[i] 与 provider
schedule item `profile-(i+1)` 必须同序——二者都按 fixture items[] 数组序 seed（qwy 显式
id=N、Auto csvRow/priority=fixtureIndex 双向锁死，双侧 parser 各自强校验该序）。位置腿
按 §6.4.1 由千网游独占求值（KB-8）；Auto 侧承重的是身份腿（acceptedIntentHash /
scheduleItemId / scheduleVersion 独立重算）。

> **已知 product/spec drift（gap⑥，编排前置）**：当前 `TrustPolicy` 仍在 Auto 侧对
> task 坐标列求 haversine（spec v1.62 L1757 明令退役的「旧文」形状）。产品修复合入前，
> **任何** seed 方案都无法在真机产出 trusted completion——A 块 device 场次必须排在该
> 产品修复之后。修复归 canonical fix owner 独立 PR（opus5 已派 @glm52 实现 / @codex-terra
> 审），不在本 debug-only backfill 内。
>
> **已知 product/spec 缺口（gap⑦，R3 P1-3 暴露，scope 待裁）**：种子契约第 3 条要求
> discover() 回读**有序 profile-1..profile-10** 作 seed PASS 前置，但
> `EnvironmentControlHandler` 的 `CapabilitySnapshotV1.profileRefs` 硬编码 `emptyList()`，
> `HandshakeProbeActivity` 也不投影 profileRefs——**当前没有任何可执行命令能回读有序 item
> 列表**。本 backfill 只把可执行子集（`currentItemId=profile-1` + `scheduleVersion` 单调）
> 纳入 seed 判据；完整有序回读需要一个 authorized 的 profileRefs/schedule projection
> （生产改动，超 debug-only scope）。**在该 projection 落地前，种子契约第 3 条的「完整有序
> 回读」不得当作已满足**——它是 device 编排的显式前置，不是本 PR 声称已闭合的判据。

## 门控词表（冻结）

| 侧 | token | 语义 |
|---|---|---|
| qwy | `lease_active` / `lease_acquiring` / `lease_releasing` | 当前 lease 落盘态 == ACTIVE / ACQUIRING / RELEASING（可 `--es caller` 限定归属） |
| Auto | `run_active` | 存在 status ∈ {starting, running} 的 test_attempts 行 |
| Auto | `attempt_state:<STATE>` | running 行的持久 aplusState == STATE（§8.1 枚举） |
| Auto | `trusted_count:<N>` | trusted_quota_entries 总数 ≥ N（提交后即刻开火） |

fault 名（冻结）：`hold_lease` / `release_receipt_loss` / `crash_after_apply` /
`rerelease_stuck`（恢复工具，非注入）。

非注入 debug 命令（冻结）：qwy `mark_clean_shutdown`（§8.4 EXPIRED 前置写 marker）；
seed 面 `prepare_10a`（qwy）/ `seed_plan` / `start_run`（Auto，§5A backfill，见上节）。
它们改名同样重开矩阵。

### §8.4 EXPIRED 注入（M-LS-12，clean-shutdown 分支）

`self_kill` 的 `lease_active/acquiring/releasing` 覆盖 **unclean** 分支（→ RELEASE_INCOMPLETE，
M-LS-07）。EXPIRED 是 **clean** 分支（ACTIVE + clean shutdown + generation 变更 → EXPIRED），
需另一条 recipe：

| 场景 | injection（触发与开火证据） | exit | direct-state | restore |
|---|---|---|---|---|
| `clean_shutdown → EXPIRED` | Auto `FullLoopProbeActivity --es fault hold_lease --el hold_ms <N>` 开 ACTIVE 窗口 → 窗口内 qwy `FaultCollectorActivity --es cmd mark_clean_shutdown`（须 LIVE provider，dump 回读 `clean-shutdown marker set: true`）→ `adb shell am force-stop name.caiyao.fakegps.bench` → 重新 bind（新 generation）。开火证据：mark 后 `Q-DUMP` marker=true，重启后 `Q-DUMP` 同 lease 落盘 `EXPIRED`（非 RELEASE_INCOMPLETE）。 | 无同步出口；clean-boot 后的 EXPIRED 保留态即出口。EXPIRED 继续阻挡新 apply，caller 可 release 收敛。 | mark 前/后、重启后三次 `Q-DUMP`（marker + lease state）；`MOCK-STATE`。 | EXPIRED 走 `rerelease_stuck`（release 接受 ACTIVE/EXPIRED/RELEASE_INCOMPLETE）；after `Q-DUMP` 无 blocking lease 且 `MOCK-STATE=0`。marker 未置位（dump=false）即前置不成立，停止，不当 EXPIRED 场跑。 |

## extra 类型纪律（R2 起）

数值 extra 一律 `--el`（Long）、布尔一律 `--ez`——`am --ei` 存 Integer、
`--es` 存 String，而实现侧已改为类型宽容读取（Int/Long/String 都接受，
`ExtraCoerce`），但文档口径统一为 canonical 类型，避免执行猫复制粘贴出
静默默认值。已由 `ExtraCoerceTest`（双侧）+ 守卫用例钉死。

## `trusted_count:<N>` 基线纪律（R2 起，reviewer 条件接受）

`>=N` 语义的 N 是**绝对目标**：挂 arm 前必须先 `cmd=state` 读当前
trusted 总数 baseline，N = baseline + 期望新增提交数。禁止拍脑袋写 N——
历史行会让门在非 in-flight 时刻立即打开（reviewer 指出的历史总数假开门
只在此纪律下被排除；若需要相对语义，future work 另冻结 token）。

## 退出与归真锚点（matrix 冻结前的最小事实）

- `hold_lease` / `crash_after_apply` 期间设备处于 mock 状态——**by design**。
  出口：Auto `rerelease_stuck --es lease_id`（§6.3.3 carve-out，release 接受
  ACTIVE/EXPIRED/RELEASE_INCOMPLETE）；qwy 侧 `cmd=dump` 复核；仍不清场 →
  按包底线停场进人工恢复（force-stop 不可清 mock，已证）。
- `release_receipt_loss` 自收敛（两条腿都是真 release，第二腿幂等回执）。
- qwy `self_kill` 后下一次进程启动（服务 bind 或任何 boot 路径）触发 §8.4
  recovery——**不要用 cmd=dump 之外的方式先读回**，dump 不 boot 单例。
- 一次只挂一个 arm；换注入前先 `disarm` 或确认 FIRED/TIMEOUT 已落 arm.log。

## P10 零设备闭合裁决

**可以在不向设备写入的前提下闭合 §3 P10。** P10 位于
`READY_TO_SCHEDULE` 准入层，要求的是每个故障注入在调度前已经具备可达、
逐项冻结的退出与设备归真步骤；它不是 §5B／§5C 的真机行为 verdict。

本裁决绑定 collector PR #52：code head
`24140d5c58497abd798a33ff34fcdb0776e91a2f`，merge commit
`f20d715ed393b608e12c7c840b223b3dc6041120`。闭合 P10 必须同时满足：

1. 下方七项矩阵与本文件的命令、token、extra 类型及代码入口逐字对应；
2. debug gate、durable readback、principal-bound revoke、extra coercion、source/release
   purity 的 host tests 与负矩阵通过；
3. 非包作者的 evidence-validity reviewer 对 exact 文档／代码绑定给出 APPROVE。

这三项成立后，P10 可从 `NOT SATISFIED` 改为 `SATISFIED`，但仍然：

- **不**表示任何注入已经在真机执行；
- **不**把 §5B 崩溃恢复或 §5C 撤销双侧写成 PASS；
- **不**单独产生 device lease；必须再跑完整 §3 十一条 current-state audit；
- **不**消除下方 `markRevoked(appId, …)` 的 production 残留。

反向要求“P10 必须先靠真机注入证明”会形成自锁：§3 禁止 P10 满足前写设备，
而 P10 又要写设备才能满足。真正的行为证明已经由 accepted package §5B／§5C
承接，位于 `READY_TO_SCHEDULE` 之后，因此不存在 bootstrap 例外或提前 lease。

## 逐注入四段矩阵（冻结）

### 共同读回与失败底线

矩阵里的 `Q-DUMP`、`A-STATE` 与 `MOCK-STATE` 分别指：

- `Q-DUMP`：qwy `FaultCollectorActivity --es cmd dump`；需要判 revoke 时同时传
  exact `app_id` 与 `signer`。它每次从新的 `FileDurableKv` 实例读磁盘，不启动
  `ProviderRuntime`，因此 qwy 自杀后必须先用它封存 pre-recovery 状态；
- `A-STATE`：Auto `ProviderRevokeCollectorActivity --es cmd state`，直接读 Room 的
  pairing、running attempt／`aplusState` 与 trusted 总数；
- `MOCK-STATE`：保存完整 `adb shell dumpsys location` 原文，并以
  `provider [mock]` 零行作为归真谓词。坐标“不是 Kyiv”与 `force-stop` 都不能替代它。

每项开始前保存 before 三件套，结束后保存 after 三件套；arm 类另保存对应 app 的
`files/debug-collector/arm.log`。任一出现 `REFUSED`、`TIMEOUT`、`NOT PROVEN`、
`DIVERGENT`、`CLEANUP UNSAFE`、`RERELEASE FAILED`／`THREW`、durable state
不可读或 `MOCK-STATE` 非零，立即停止本场，不运行下一项。先走矩阵指定恢复；仍不能
证明 mock 清除时转 operator 人工恢复。`adb reboot` 只可作为 operator 授权的最终清场，
重启后仍须重新读取 `MOCK-STATE`。

| token / 场景 | injection（触发与开火证据） | exit（正常出口） | direct-state（直接状态） | restore（失败恢复／归真） |
|---|---|---|---|---|
| `hold_lease` | Auto `FullLoopProbeActivity --es fault hold_lease --el hold_ms <N>`；必须先看到 validated apply/observe，再看到 `injection window open`，且窗口内 `Q-DUMP` 为同一 lease `ACTIVE`。`hold_ms` canonical 类型是 `--el` 且必须 `>0`。 | 单独运行时等待窗口关闭，probe 继续 validated release；只有 `[5] release … complete=true residuals=[]` 后才允许继续 advance。若它只是给另一条 arm 开窗口，则由那条注入的 exit 接管，不能把 hold 自身绿当整场绿。 | before／窗口内／after 的 `Q-DUMP`；before／after 的 `A-STATE` 与 `MOCK-STATE`；完整 probe report。 | probe／binder 中断时从 `Q-DUMP` 取 exact leaseId。`ACTIVE`／`EXPIRED`／`RELEASE_INCOMPLETE` 走 `rerelease_stuck`；`REVOKED` 走 `cleanup_revoked`；`RELEASING` 由 qwy owner 启动重放。最后必须 `MOCK-STATE=0`。 |
| `release_receipt_loss` | Auto `FullLoopProbeActivity --es fault release_receipt_loss`；开火证据是 `[5a]` validated release 后明确 `RECEIPT NOW DISCARDED`，随后以同一 `rlKey/rkKey` 执行 `[5b]`。 | 自收敛；必须同时有两腿 `complete=true`、第二腿与第一腿同 receipt 字段，并出现 `RECEIPT-LOSS-REPLAY: IDEMPOTENT`。fault loop 随后跳过 advance 并结束。 | before／after `Q-DUMP`、`A-STATE`、`MOCK-STATE`；两腿完整 report。P10 只冻结可退出性；“无第二次 cleanup”的 §5B 行为 verdict 留给真机 raw evidence。 | 任一 release 不 complete 或 replay divergent：停止；`Q-DUMP` 若为 `ACTIVE`／`EXPIRED`／`RELEASE_INCOMPLETE`，用 `rerelease_stuck`；其余状态按本矩阵分流。归真必须由 `MOCK-STATE=0` 直接证明。 |
| `crash_after_apply` | Auto `FullLoopProbeActivity --es fault crash_after_apply`；validated apply/observe 后写 `files/debug-collector/crash-*.log`，再真实 `Process.killProcess`。开火证据必须包含该 durable crash record 与 post-kill `Q-DUMP` 的同一 lease `ACTIVE`；屏幕或单条 logcat 不单独承重。 | **无正常出口**，进程死亡且 finally-release 不运行就是预期注入。不得把进程退出当归真。 | kill 后先 `Q-DUMP`（同 lease／raw state），再取 crash record、`A-STATE` 与 `MOCK-STATE`；不得先启动会隐式改变 qwy recovery state 的 runtime 路径。 | 用 `Q-DUMP` 的 exact leaseId 调 `rerelease_stuck`；只有 validated `complete=true residuals=[]`、after `Q-DUMP` 无 blocking lease 且 `MOCK-STATE=0` 才归真。失败转人工恢复。 |
| `self_kill` | **qwy 侧**：`FaultCollectorActivity --es cmd arm --es action self_kill`，gate 分别取 `lease_active`、`lease_acquiring`、`lease_releasing`，由 companion transaction 打开；arm.log 必须有 `FIRED` 后进程真实死亡。**Auto 侧**：`ProviderRevokeCollectorActivity --es cmd arm --es action self_kill`，gate 取 `attempt_state:<STATE>` 或 `trusted_count:<N>`；arm.log `FIRED` 后真实死亡。数值 extra 一律 `--el`。 | **无同步正常出口**；自杀后的 state-aware recovery 才是出口。qwy `RELEASING` 在下一次 owner boot 重放；`ACQUIRING/ACTIVE` 的 unclean boot 应进入 `RELEASE_INCOMPLETE`。Auto 重启后必须从 persisted attempt phase 恢复，trusted count 不得重计。 | qwy kill 后第一读必须是 `Q-DUMP`（它不 boot runtime），再触发 owner boot 并二读；Auto kill 后先 `A-STATE`，再读 `Q-DUMP`。`trusted_count:<N>` 的 N 必须是 arm 前 `A-STATE` baseline + 本场期望新增数。全程保存 `MOCK-STATE`。 | qwy `REVOKED` 用 `cleanup_revoked`；`RELEASING` 让 owner boot 重放；`ACTIVE`／`EXPIRED`／`RELEASE_INCOMPLETE` 用 `rerelease_stuck`。Auto 正常 recovery 若未释放同样落到 `rerelease_stuck`。任何 trusted count 增量重复或 mock 未清均为 §5B FAIL，并进入人工恢复。 |
| `revoke_caller` | **新 run 前**：`PairingApprovalActivity` 同时给 exact `revoke_application_id`／`revoke_signer_digest`；只有 before `Q-DUMP` 证明无 blocking lease 时才用该 at-rest 入口。可用 `--ez revoke_run_cleanup true`。**run 中**：qwy arm `revoke_caller`，exact full principal + `lease_active`；gate 必须命中同 principal，arm.log 有 `FIRED`／`OUTCOME`，报告必须 `REVOKE PROVEN`。 | at-rest：exact pairing active→inactive 且 audit row 存在；无 lease 时无需 cleanup。run 中：lease 必须先读为 `REVOKED`，再由 qwy `cleanup_revoked` 收敛为 `RELEASED` 或 loud `RELEASE_INCOMPLETE`；已失权 caller 的 release 仍应 typed 拒绝。 | exact-principal before／after `Q-DUMP`（pairing、audit、lease）；Auto 调用的 typed failure；`A-STATE` 与 `MOCK-STATE`。 | `REVOKED` 只能由 `cleanup_revoked` 恢复，不能交给已失权 caller；若变 `RELEASE_INCOMPLETE` 或 mock 仍在，停止并人工恢复。后续重跑前重新批准同 principal 是 operator 动作，历史 pairing 不得自动复活。 |
| `revoke_provider` | **新 run 前**：Auto collector `--es cmd revoke` + exact `app_id`／`signer`，报告必须 `REVOKE PROVEN`。**run 中**：Auto arm `revoke_provider`，exact principal + `run_active` 或 `attempt_state:<STATE>`；arm.log `FIRED`／`OUTCOME`，报告必须 `REVOKE PROVEN`。 | at-rest：新 run 必须停在 `NOT_PAIRED`，不得产生 lease／trusted 增量。run 中：当前 attempt 进入 **Auto 正常 release/recovery**，不得误走 qwy `cleanup_revoked`；终态前不启新 run。 | before／after `A-STATE` 的 exact pairing row、running attempt／state、trusted total；同时 `Q-DUMP` 跟踪 lease 与 `MOCK-STATE`。 | 让 in-flight attempt 的正常 release/recovery 收敛；若 lease 卡在 caller 仍有权释放的 `ACTIVE`／`EXPIRED`／`RELEASE_INCOMPLETE`，用 `rerelease_stuck`。后续重新批准 provider 归 operator；mock 未清转人工恢复。 |
| `rerelease_stuck` | **恢复工具，非注入。** 只有 `Q-DUMP` 已给出 exact leaseId，且 raw state 是同 caller 可 release 的 `ACTIVE`／`EXPIRED`／`RELEASE_INCOMPLETE` 时，才执行 Auto `FullLoopProbeActivity --es fault rerelease_stuck --es lease_id <id>`；fresh key pair 由实现生成。 | 必须出现 `RERELEASE: … VALIDATED complete=true residuals=[]`。`REVOKED` 不可走本工具；`RELEASING` 由 qwy owner boot replay。 | before／after `Q-DUMP`、`A-STATE`、`MOCK-STATE`，并绑定输入 leaseId 与 response 中同一 lease。 | `RERELEASE FAILED`／`THREW`、after 仍有 blocking lease、residual 非空或 mock 非零：立即人工恢复；不得把工具调用本身当原注入的行为 PASS。 |

### principal 残留与矩阵适用条件

生产 `EnvironmentLeaseStore.markRevoked(appId, …)` 仍只按 appId 找 blocking lease，
没有在 store 层再次匹配 signer。PR #52 的 debug arm 在开火前以
`(applicationId, signerDigest)` 全 principal 匹配 current lease，因此本矩阵只在以下前置成立时
适用：

- at-rest `revoke_caller` 前 `Q-DUMP` 必须证明没有 blocking lease；
- mid-run `revoke_caller` 必须使用 full-principal arm，不能直接绕过 gate 调 revoke；
- 任一 current lease 的 signer 与目标不同，立即拒绝本场，不把 appId-only transition 当证据。

该限制使本轮 P10 readiness 不会重开 rotated-signer 收集器错误，但它**不是 production
修复**，也不从 §5C 行为验收中消失；相关主线修复须独立 PR／review。

### host-only 证据边界

本矩阵冻结时允许使用且只使用 host 证据：merged PR／CI、源码、debug/unit tests、
release-purity source/APK guards 与负矩阵。它们证明命令面真实可达、token/gate 可判、
durable readback 不在读时改写 qwy bytes、错误 principal 不会假绿、collector 不进入 release。
它们不能证明 Android 真机上的进程死亡、binder 重连、mock 清理或 trusted ledger 行为；这些
结论必须由 §5B／§5C 场次的 raw evidence 承重。

## 测试与证据链

- RED：双侧 `P10CollectorSurfaceGuardTest`（main 上 6+5 红，取证于 commit
  `[G2-P10] RED`）。
- GREEN 逻辑：`CollectorGateTest` / `RevokeCollectorGateTest`（src/testDebug）；
  读回诚实性：`QwyDurableSnapshotTest`（未落盘必须读回缺失 + capture 不改
  durable 字节 + 目录漂移守卫）。
- 纯度：`check-debug-only-collector.sh`（源 + APK 双查）+ 负矩阵
  `selftest-debug-only-collector.sh`（9 case，含新增 seed 符号）。
- §5A seed backfill：`APlus10AFixtureSeedTest`（qwy，显式 id / digest pin / 结构绑定 +
  篡改负测）+ `APlus10APlanSeedTest`（Auto，KB-8 去坐标 / 占位超界语义 / 拓扑校验 / 映射）+
  `APlus10AScheduleResetTest`（qwy，单调 V+1 + 回读校验，R3 P1-2）——纯 JVM。
  §5.G evidence carrier：`selftest-test-hook-evidence-carrier.sh`（raw report 保全 +
  session/path/sha/apk 绑定 + cleanup 只删 TEMP_ROOT，R3 P1-4）。§8.4 EXPIRED：
  `QwyDurableSnapshotTest` clean-shutdown marker 生产 `record()` 写、debug 非破坏性读回。

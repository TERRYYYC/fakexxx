---
feature_ids:
  - G2-66
topics:
  - qianwangyou
  - authoritative-continuity
  - system-server
  - fail-closed
  - semantic-mutation
  - crash-recovery
doc_kind: bug_report
created: 2026-08-31
status: fixed_pending_exact_head
github_issue: 66
---

# G2-66 authoritative continuity oracle：fresh review fail-closed gaps

## 诊断胶囊

| 栏位 | 结论 |
|---|---|
| 现象 | QWY 的公开 AppOps/provider 回调只能描述当前端点，无法证明 PRE→POST 整段历史；owner/provider 离开再恢复仍可能被读成“始终未变”。 |
| 根因 | 生产路径没有带 boot identity、稳定序列、完整 hook coverage、健康状态与 QWY semantic session 的系统级历史源，也没有把每条语义写入都放进同一个 odd/even 变更区间。 |
| 修复 | 增加 exact-build system-server oracle、双快照观察、durable cursor ACK、QWY semantic writer/coordinator、系统 mock 实际投影 digest、service/integration 共同 ownership，以及 fail-closed 的启动、重连、死亡、崩溃恢复状态机。 |
| 安全边界 | 公开 callback 仍只能是 PARTIAL/NONE；只有完整、健康、同 boot/instance、稳定且已 ACK 的 authoritative source 可以给 FULL。 |
| 生产现状 | `Android15OracleHookPlan.ATTESTED_FINGERPRINTS` 仍为空，因此真实 production build 会得到 `BUILD_UNATTESTED`，不能达到 HEALTHY/FULL。 |
| 验收现状 | JVM/构建门禁覆盖 AC1–6 的协议行为；AC7 未执行。没有 emulator 或 rooted-device 证据，不得关闭 #66，也不得声明真实 G2 PASS。 |

## Bug report 五件套

### 1. 报告人

Codex 在实现 GitHub #66，并按 fresh-context / 非作者审查逐轮复核真实 production 调用链时确认。

### 2. 复现步骤

原始缺陷可用任一组端点恢复场景复现：

1. PRE 读取当前 mock owner、GPS/network enabled 与 mock location。
2. 在 PRE→POST 期间把 owner 切走再切回，或把 GPS/network disable 后再 enable。
3. 在异步 public callback 排空前读取 POST。
4. PRE/POST 端点相同，旧实现可能把窗口判为连续并允许可信配额。

同类写入缺口还可通过以下方式复现：

- 在 QWY profile/mode/schedule/provider 写入中途观察；
- 在 begin、local commit、finish、cursor ACK 任一 crash window 重启；
- system-server oracle/bridge/QWY session 重启或 Binder death；
- GPS/network 实际缓存偏离 desired coordinate 后触发刷新；
- foreground service 与 Environment Control 交替取得 device-global test-provider 所有权。

### 3. 根因分析

公开 Android 信号没有历史序列、同步 drain barrier 或可验证的 mutation-in-progress 状态。轮询和一秒刷新只能产生新样本，不能证明两个采样点之间没有离开再恢复。要使 FULL 成立，必须同时满足：

- system-server 内的 boot/instance identity 与单调 odd/even sequence；
- AppOps、provider state、effective enabled、bridge、QWY generation/session 与 build attestation 的完整 coverage；
- 每条语义状态写入在改变本地状态前 begin，在 commit 后 finish；
- observation 对 raw GPS/network read 做同一 stable cursor 的双快照 sandwich；
- sequence ACK 与 environment revision 持久一致，崩溃只能保守多 bump，不能漏 bump；
- canonical digest 使用实际 OS projection，而不是 `lastApplied` desired coordinate；
- foreground service 和 Environment Control 对同一 device-global provider 使用一个可验证的 ownership 状态机。

### 4. 修复说明

主修复包含：

- system-server Binder oracle、Android 15 exact hook plan、coverage/health/build fingerprint gate、boot 与 oracle instance provenance；required mask 包含锁内 coordinate-history bit；
- ordered mutation finisher，避免异步回调越过 finish；bridge reconnect、direct boot、death/replacement generation 与 poisoned callback fail-closed；
- tracker 的 authoritative cursor ACK、boot/instance/sequence regression、odd state、missing coverage 与 ACK crash recovery；
- PRE/POST 双快照，raw GPS/network read 必须夹在同一 stable healthy sequence 内；
- QWY semantic mutation coordinator 与 process-wide writer lane，覆盖 config publication、mode/profile/schedule、effective coordinate、provider cleanup 与 service refresh；
- canonical semantic digest 加入 durable active publication identity、实际 framework coordinate/mock/enabled fingerprint 和明确 inactive projection；
- API-35 `setTestProviderLocation` 外层只传递原始 UID/PID/package/tag，内层 `MockLocationProvider#setProviderLocation` 在平台锁内按 exact coordinate bits 分类；A→A 不开 token，B→A/非法或未知值保守记入 history；1 m tolerance 只用于 observation verification，不能隐藏 digest-visible drift；
- same-coordinate heartbeat 不 bump；B/unavailable→A repair进入 exact semantic interval；无 authoritative lane 时保留 legacy refresh但 continuity 仍为 NONE；已安装但不健康的 lane 则 defer；
- service/integration provider ownership：`RecoveryUnknown`、`ServiceActive`、`IntegrationActive`、`Uncertain`、`ProvablyInactive`；failed removal 保留 claim/uncertainty；cold owner 只有在真实 provider removal 成功后才能建立 inactive baseline；
- pending advance 对正常 S+2、owner restart S+4/S+6、foreign/interleaved cursor、unavailable proxy 与 terminal recovery 做 exact shape 判定，不 replay 不相关 mutation；
- startup adoption 只复用已健康且 cursor 已 durable ACK 的 session，避免重复 registration/bump；同一活进程 Binder death token 跨重连复用。

### 5. 回归与验收

回归覆盖：

- owner away/restore、provider disable/enable、concurrent mutation、odd sequence；
- boot/oracle instance change、sequence regression、missing hook/coverage、unhealthy oracle、read/ACK crash；
- ordinary same-coordinate refresh no-op；A→B→A 即使 deferred completion 合并也不能 alias；actual coordinate drift、missing projection、near-but-not-bitwise-equal projection；
- public callback/session unavailable 不得伪造 FULL；
- system-server callback FIFO/barrier/reconnect 与 lock-order；
- startup/re-registration/token identity、partial uncertainty 与 pending advance S+4/S+6；
- service→integration→release handback、failed removal uncertainty、runtime-only cleanup、cold startup cleanup；
- drift repair publish failure的 provider cleanup 留在同一个 semantic repair interval 内。

## Fresh review 发现并关闭的缺口

首轮 RED 覆盖了十五类基础缺口：public callback counterfeit、session unavailable、cleanup 后 stale FULL、readiness 缺失、installed-lane fallback、delayed death/replacement、system-server lock inversion、async callback barrier、bridge reconnect、advance lock order、PRE timestamp、effective B→A/exact tolerance/null projection、startup double registration/token identity、pending S+4/S+6、inactive cleanup 与 service ownership。

随后非作者审阅继续找到并关闭：

1. public callback/session unavailable 仍可能被错误提升；
2. refresh cleanup、registration、reconnect 与 delayed Binder death 的 generation/ordering 窗口；
3. system-server callback 在锁内执行造成的 lock inversion，以及 finish 越过异步 callback；
4. PRE 时间戳在 readiness 前捕获，导致证据窗口不诚实；
5. advance `completeAndAdvance` 的 selection/ACK fallback 与锁序；
6. canonical digest 误用 desired state、忽略 service-owned actual projection、把 tolerance 当 exact equality；
7. startup adoption 重复 registration，death token 假复用测试因 singleton lambda 假绿；
8. partial uncertain S+2 遇到 foreign S+4 被 stranded，以及已完成 S+6 在 unavailable proxy 前仍错误 registration；
9. service controller stale `Running(A)` 在 integration handback后把 inactive→active 冒充 A→A repair；
10. persisted SYSTEM_MOCK 与 explicit inactive 被混为 unknown，导致成功 release/runtime cleanup 的 after-digest 为 null；
11. 无 lane时 coordinate repair 被永久 defer，空 production allowlist 下会破坏原有 mock refresh；
12. 把 empty last-known cache 错当 provider absence；最终改为 cold owner 对真实 providers 做 causally ordered removal，只有成功 removal 才建立 inactive；
13. service drift repair 的 publish failure 曾在 semantic token 结束后才 cleanup providers；现已把 cleanup 收回同一 repair interval；
14. 只读当前 framework cache 仍会漏掉另一 same-UID PID 在两次读取之间执行的 B→A；现由 provenance-only Binder entry 加锁内 selective coordinate hook 捕获真实历史，同时保持 exact A→A heartbeat 的 stable sequence 不变；
15. semantic finish/endpoint-loss ambiguity 曾先结束 token 再 cleanup projection；现注册 one-shot compensation，在第一次 uncertain finish 前于原 token 内撤销 provider session。
16. 预审版 `oracle-v1` pending ticket/reservation 缺少保留的 owner generation，无法安全恢复；该格式在空 production fingerprint allowlist 下从未可达或分发，现从类型与 codec 两层拒绝，唯一可写格式 `oracle-v2` 强制携带 generation。

## Fail-closed 与未完成项

- production fingerprint allowlist 为空是刻意的安全门，不是测试遗漏。
- 任何 unattested build、缺 hook、缺 bridge、缺 QWY session、unknown/uncertain projection、boot/instance 变化或 sequence 异常都不能报告 FULL。
- 本变更没有写入任何真实 fingerprint。
- 本变更没有运行 exact-build emulator，也没有连接 authorized rooted device。
- GitHub #66 必须保持 OPEN；只有后续独立 exact-build 证据变更完成 AC7，才可考虑关闭 blocker。

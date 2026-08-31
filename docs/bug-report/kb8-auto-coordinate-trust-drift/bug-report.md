---
feature_ids: []
topics:
  - android-automation
  - a-plus
  - kb-8
  - trust-policy
  - coordinate-ownership
doc_kind: bug_report
created: 2026-08-31
status: fixed_pending_exact_head
github_issue: 64
---

# KB-8 Auto coordinate trust drift

## 诊断胶囊

| 栏位 | 结论 |
|---|---|
| 现象 | G2 的 10 地址流程即使收到完整、provider 侧独立验证的 observation，Auto 仍可能把完成判为 `FAIL`，无法写入可信配额。PR #62 使用超出合法范围的占位坐标后，该失败成为确定性的。 |
| 证据 | canonical spec §2.2、§6.4、§6.4.1 将目标坐标与距离比较独占给千网游；`CompletionTrustContext` 仍携带 Auto-local target；`TrustPolicy` 仍执行 haversine；`AutomationEngine` 正常与恢复路径都从 `LocationTask` / `TestAttempt` 注入目标坐标。 |
| 问题假设或根因 | **已确认根因**：PR #24 / KB-8 从 wire 与 canonical digest 移除了坐标，但其后落地的 Auto trust policy 仍按被废弃的双 owner 模型实现；contract/spec 迁移没有传播到内部 trust context、policy、engine wiring 与测试。后续独立审阅又逐层证明：production source/engine 双写 observation；SQLite 会把 NaN 规范化为 `NULL`、`-0.0` 规范化为 `+0.0`；恢复会在旧 lease 未释放时返回主循环；POST 与 receipt 不在同一事务；execution `INSERT IGNORE` 未读回校验且 `DECIDING` 未绑定 owner；通用恢复绕过统一 release 状态机；`CLOSED` 与 attempt 终态也曾分成两个事务；同一 attempt 的 trusted/unverified 决策载体未互斥；`RELEASED` checkpoint 恢复会再次调用 provider/重复 audit，且第一次 fail-closed 后第二次重启仍会从通用路径补造 receipt；完整 `DECIDING` bundle 的载体冲突异常未持久收敛；repository trust entrypoint 还能从 `POST_OBSERVE_PENDING` 绕过原子 decision bundle 直接 mint；`ADVANCE_*` 曾在没有 exact release proof、缺失/冲突 decision carrier 或缺 scheduleRef 时仍可回放/终结；failure continuation、release audit/checkpoint 与 phase-bound carrier 也各存在非原子或 owner 未校验的崩溃窗口。 |
| 诊断策略 | 先用一条行为测试证明：其余 §6.4 谓词全部成立时，合法但远离旧 Auto target 的 provider effective coordinate 不应被 Auto 拒绝；再移除旧 owner 腿并复跑现有逐字段负矩阵。 |
| 超时策略 | 若移除旧 owner 腿后出现第三个以上独立架构缺口，停止继续补丁，回到 #1 / canonical spec 做边界重审。 |
| 预警策略 | 保留 pre/post 对称的 null、NaN、Infinity、越界坐标负例；同时以源码扫描确保 `CompletionTrustContext` 不再出现 `targetLat` / `targetLng` / caller tolerance。 |
| 用户可见交互修正 | 不改布局。运行页原先会停在 `UNTRUSTED` / paused；修复后，只有 provider 已按 canonical contract 给出完整可信证据时才允许继续计数。 |
| 验收 | RED 以预期的旧距离判定失败；GREEN 后目标测试与完整 Auto unit suite 通过；lint/assemble 与除“工作树尚未提交”外的 A+ gate 通过；模拟器 smoke 独立验证双 App 可安装启动。本修复不冒充真机 G2 PASS。 |

## Bug report 五件套

### 1. 报告人

Codex 在恢复 G2 当前状态并核对 PR #62 最新独立审查时发现；持久问题为 GitHub #64。

### 2. 复现步骤

1. 构造其余字段都满足 canonical §6.4 的 `CompletionTrustContext`。
2. 让 pre/post observation 携带合法、有限、provider 已标为 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` 的 effective coordinate。
3. 让该坐标与 Auto-local `targetLat/targetLng` 相距大于旧的 1 m 阈值。
4. 调用 `TrustPolicy.evaluate`。

期望：Auto 只检查 effective coordinate 的结构有效性与其余 contract 谓词，返回 `PASS`。

实际：Auto 执行本地 haversine 比较并返回 `FAIL`。

### 3. 根因分析

KB-8 的单一所有权迁移只传播到了跨进程 contract、digest 与 canonical spec，没有传播到 Auto 内部的完成信任上下文。旧实现继续把 Auto plan/task 坐标当第二权威，因此：

- 与 provider schedule 的合法差异会被误判；
- debug-only seed 为遵守 KB-8 使用结构不可能的占位坐标时，所有完成都会被必然拒绝；
- live 与 crash-recovery 两条路径都复现同一错误，因为两者都注入旧坐标。

首轮非作者审阅发现了同一正常路径的第二个独立阻断：`APlusComposition` 在返回 PRE/POST 前已写入不可变观察记录，`AutomationEngine` 又通过 `PlanRepository` 写入同一 `(attemptId, phase)`。Room 的默认 `ABORT` 使该路径在 PRE 后抛出唯一键异常，外层只能将 session 置为 `PAUSED`。此前的 normal test 使用不持久的 fake source，因而假绿。

第二轮非作者审阅在修复后的 exact SHA 上又确认四个存储/恢复根因：

- SQLite 的 REAL round-trip 不是 Kotlin `Double` 的逐位恒等映射：NaN 会读成 SQL `NULL`，`-0.0` 会读成 `+0.0`。把所有非有限值都规范成 `NULL` 又会错误合并 `null / NaN / ±Infinity` 四类不同 replay payload。
- production source 先提交 PRE carrier，engine 再提交 `PRE_OBSERVED`。两次提交之间进程死亡会留下 `ENV_APPLIED + durable PRE`；旧恢复分支不重取 PRE，而是直接 release / interrupted / CLOSED。
- `recoverCrashedAttempt()` 的 `true` 同时表示“已安全收敛”和“只推进到中间态”。调用方随后可能在旧 lease 仍活动时创建新 attempt 并 fresh apply，违反 INV-28。
- 正常与恢复路径都可能先写 POST + `DECIDING`，再取得 completion evidence / 写 receipt；进程死亡后留下不可重算的 `DECIDING + missing receipt`。

第三轮 fresh-context 审阅继续确认三个 durable truth 缺口：

- decision bundle 虽已原子写 POST/receipt/DECIDING，但未要求 `currentExecutionId` 指向同 attempt 的不可变 execution；execution 的 `INSERT IGNORE` 也未读回逐字段比较，live loser 仍可能参与 trust decision。
- 通用恢复释放直接调用 coordinator，跳过真实 source state 到 `RELEASE_PENDING` 的 owner/audit 持久化。
- release 后的 `CLOSED` 与 `succeeded / failed / interrupted` 终态分两次提交；任一提交间崩溃都可能留下相互矛盾且无法再次被 recovery 查询的状态。

第四轮 fresh-context 审阅又确认两个收口缺口：

- `recordTrustedCompletion()` 的 PASS 与 FAIL 分支只各自保证本表幂等，没有在同一事务内排除另一张决策载体表；同一 attempt 因 replay/race 可能同时留下 trusted 与 unverified 两个互相矛盾的 durable verdict。
- 已到 `RELEASED` 的 checkpoint 在重启后仍被映射回 `RELEASE_PENDING`，会再次调用外部 provider 并追加一条新的 release audit；恢复没有证明已有 receipt 与 attempt/lease/verification token 的 exact tuple 一致。

第五轮 fresh-context 审阅在上述修复上继续确认三条可复现边界：

- `RELEASED` exact receipt 缺失/冲突第一次虽被标为 `RECOVERY_REQUIRED`，第二次重启却把这个通用状态重新映射到 release：缺失场景会调用 provider 补造 receipt，冲突场景会追加假的 `RECOVERY_REQUIRED → RELEASE_PENDING → RECOVERY_REQUIRED` audit。
- 完整 `DECIDING` bundle 已有 opposing/both carrier 时，repository 会正确抛 `DECISION_CARRIER_CONFLICT`，但 engine 外层只 pause session、没有改变 owner；attempt 永久留在 `DECIDING` 并在每次重启重复抛异常。
- `recordTrustedCompletion()` 复用了同时允许 `POST_OBSERVE_PENDING/DECIDING` 的 owner helper，且未自行读取 durable PRE/POST/receipt；直接传 live context 可在原子 bundle 尚不存在时返回 PASS 并 mint。

第六轮 exact-SHA 正式审查在 `c93b545` 上以 `REQUEST_CHANGES` 确认六条剩余闭环缺口：

- 三个 `ADVANCE_*` 恢复入口没有先证明 attempt/lease 的 exact durable release receipt；缺失或冲突 provenance 时仍可能 replay advance。
- 正常失败在 `RELEASED` 与 terminal projection 之间崩溃会丢失 typed continuation，重启把原失败降级为 interrupted 并可能 fresh apply。
- `RELEASE_RECEIPT` audit 与 owner=`RELEASED` 分写，audit 已提交而 owner 尚未推进时会追加重复 provenance。
- post-release advance/readback 失败落成通用 `RECOVERY_REQUIRED`，后续重启会再次进入 release 并覆盖原 typed reason。
- decision bundle 没有要求同 attempt 的 durable PRE；phase-bound observation helper也没有先验证 attempt owner 存在及当前 phase。

同一非作者审查者的 iterative re-review 又找到四个 kill point/旁路：failure continuation 与 `RELEASE_PENDING` 仍非原子；`ADVANCE_*` 缺/错 trusted carrier 不 sticky；release 前的 `DECIDING` anchor 错误被误判成 post-release；release checkpoint 的测试没有杀死“audit 提交、owner 0-row 更新”的实现。修复后再审仍发现两条同级旁路：`ADVANCE_*` 忽略 opposing unverified carrier，以及缺 scheduleRef 的 reason 第二次重启不 sticky。最终冻结 diff 已把这些项全部关闭。

### 4. 修复方案

- 新增失败回归测试，冻结“Auto 不做距离比较”的行为。
- 从 `CompletionTrustContext` 删除 target 与 caller tolerance。
- 从 `TrustPolicy` 删除 haversine 与 Auto-local target gate，只保留 provider effective coordinate 的 non-null / finite / range 校验。
- 删除 normal/recovery engine 的旧坐标注入，更新测试与注释。
- 让 `PlanRepository` 成为 PRE/POST 与 completion receipt 的唯一持久化权威：`INSERT IGNORE` 后立即读回并严格比对不可变载荷。同值重放是幂等 no-op；同键异值保留首写并 fail-closed，禁止 `REPLACE` 或裸 `IGNORE`。
- 新增从 `APlusComposition.productionBackend()` 经 `AutomationEngine` 到 trusted mint 的真实组装回归，provider 返回与旧 Auto plan 距离很远的 Kyiv 坐标。
- production evidence source 只返回 live candidate，不再自行写库；engine 在结构校验后，通过一个 Room transaction 同时写 observation carrier 与对应的 §8.1 owner state。
- Room 持久化边界只规范 signed zero 为 `+0.0`；`null / NaN / ±Infinity / 越界` 在进入 Room 前拒绝，并由生产 engine 映射为 typed `OBSERVATION_UNTRUSTED → durable release`。immutable replay 直接比较除自增 id 外的完整 raw record，禁止解析后等价掩盖 `continuitySinceEpochMs`、JSON 原文或 legacy evidence 列冲突。
- `ObservationSnapshot`、wire adapter 与 durable record 无损保留 audit-only `continuitySinceEpochMs`；它不进入 trust predicate。
- recovery 增加 `ENV_APPLIED` durable-first PRE recheck。ENV/PRE 正向恢复先释放旧 lease 并 `CLOSED`，再由新 attempt 重做完整 preflight/apply；负向恢复 typed UNTRUSTED、release、CLOSED，且不伪造 `UnverifiedAttemptRecord`。
- 收紧恢复返回契约：只有旧 attempt 已 terminal + `CLOSED` 且 exact lease release receipt 已 durable 才能返回主循环；release 不可证明时保持 `RECOVERY_REQUIRED/PAUSED`，绝不 fresh apply。
- 新增专用 `persistDecisionBundleAndEnterDeciding()`：POST carrier、completion receipt 与 owner=`DECIDING` 在一个 Room transaction 内提交。正常路径和 M-CR-05 恢复都必须先取得完整证据，再进入 DECIDING；事务冲突整体回滚。
- immutable replay 测试逐字段变异 observation / raw audit 列全部载荷，以及 completion receipt 的 wire/hash/lease；每个异值都必须 fail-closed 且完整保留首写。
- `DECIDING` transaction 现在同时要求有效 `currentExecutionId` 与 same-attempt execution row；缺指针、悬空指针、foreign owner 均整体回滚。
- execution carrier 与 observation 一样执行 `INSERT IGNORE + exact readback`：除自增 id 外逐字段不可变，score 只规范 signed zero、拒绝 non-finite；`recordTrustedCompletion()` 只评估 durable winner，任何 live/durable 冲突都禁止铸币。
- 所有正常/恢复 release 都汇入统一 lease-bound helper，按真实 source state 记录 `… → RELEASE_PENDING → CLOSED`；release 失败保持 `RECOVERY_REQUIRED/PAUSED`。
- 新增原子 close helpers，使 `CLOSED + succeeded/failed/interrupted` 同事务提交。SQLite trigger 注入的两条崩溃测试证明任一终态写失败时 owner 与 terminal projection 会一起回滚。
- 决策落库事务在 mint/unverified 前同时读取两张 carrier 表：任何 opposing carrier 或双载体状态都以 `DECISION_CARRIER_CONFLICT` fail-closed；同一 unverified verdict 允许精确幂等重放，reason/digest 任一冲突均保留首写并拒绝覆盖。
- `RELEASED` 恢复改为 read-only exact receipt proof：按 attempt 与 lease 双索引读取同一行，并逐字段验证 attempt、lease、verification token 与 release token；缺失或冲突保持 `RECOVERY_REQUIRED/PAUSED`，精确匹配时不再调用 provider、不重复追加 audit，也不回退到 `RELEASE_PENDING`。
- `RELEASED_RECEIPT_MISSING_OR_CONFLICT` 成为 sticky provenance reason：后续每次重启仍只做 exact durable readback；证据未恢复时持续 pause，绝不进入通用 release。两条 two-restart 测试分别固定“不得补造 receipt”和“不得追加假 audit”。
- `redecideDecidingAttempt()` 使用 typed outcome；捕获 repository 的 `DECISION_CARRIER_CONFLICT` 后原子写入同名 `RECOVERY_REQUIRED` reason 并立即停止。该 reason 在后续重启保持 sticky，既不释放/推进，也不改变两张 append-only carrier 表。
- repository 将 bundle-owner 与 decision-owner 前置条件拆开：decision 只接受 `DECIDING`，并在同一个 mint/unverified transaction 内重读 exact owner execution、durable PRE、POST 与 completion receipt；live/durable 任一不一致或 carrier 缺失都整体回滚。
- `ADVANCE_PENDING / ADVANCE_OBSERVING / ADVANCE_STATE_READBACK` 恢复统一增加 exact durable release proof 前置门；按 release idempotency key、lease 与 canonical digest 双索引精确读回，缺失/冲突写入 sticky `RELEASED_RECEIPT_MISSING_OR_CONFLICT` 并 pause。
- typed failure 与 owner=`RELEASE_PENDING` 改为同一 SQL 原子 boundary；旧版本可能遗留的“旧 phase + typed reason” split row 在任何采证/重决策前被识别、release、原 reason 关闭并保持 paused。
- `commitReleaseReceiptCheckpoint()` 把唯一 release audit 与 `RELEASE_PENDING → RELEASED` CAS 放在同一 Room transaction；CAS 0-row 也抛错回滚。exact legacy audit 可幂等收敛，重复/异值 audit 固化为 sticky `RELEASE_CHECKPOINT_AUDIT_CONFLICT`。
- post-release advance/observe/readback 的所有失败统一使用可识别的 `ADVANCE_*` / tuple typed reason；后续重启只读验证 exact release proof，禁止 provider release、synthetic audit 与 fresh apply。`DECIDING` 缺 anchor 则单列为 release 前 sticky invariant，不要求不存在的 release receipt。
- `ADVANCE_*` replay 前同时读取 trusted 与 unverified 两张 append-only carrier：missing、wrong-task 或双 carrier 分别 typed fail-closed；anchor scheduleRef 缺失也使用 sticky `ADVANCE_ANCHOR_SCHEDULE_REF_MISSING`。
- `persistDecisionBundleAndEnterDeciding()` 在写 POST/receipt/`DECIDING` 前强制 durable PRE；`persistObservationAndMarkAplusState()` 写 PRE 前验证 attempt 存在、owner=`ENV_APPLIED` 且 next=`PRE_OBSERVED`，missing/wrong phase 整笔回滚。

不改 provider 侧验证、不放宽其他 §6.4 谓词，也不修改 wire contract。

### 5. 验证方式

- 目标 RED→GREEN 测试及现有逐字段 trust negative matrix。
- CellRebel Auto `testDebugUnitTest`、lint、debug/release assemble。
- 仓库 `verify-a-plus --stage full` 与相关静态 guards。
- Android 模拟器安装/启动 smoke；真机 G2 仍需独立 device lease 与 exact-build 证据。

## 验证结果

- RED：新增 KB-8 回归在旧实现上是 52 项中的唯一失败，失败点为旧 Auto-local 距离判定。
- RED（首轮审阅闭环）：新增的 shipped-production 正常链在旧实现上停留于 `starting`、无 mint；两条持久载荷重放测试均以 `SQLiteConstraintException` 变红。
- RED（第二轮审阅闭环）：生产 PRE NaN、POST NaN 与 `ENV_APPLIED` crash recovery 正/负四项在旧实现上全部失败；reviewer 的独立 Room probe 另证明合法 `-0.0` 首写自冲突。
- RED（第三轮审阅闭环）：新增通用 release audit 与原子 close 三项首次运行 66 项中 3 项失败；owner/execution replay 用例覆盖缺指针、悬空/foreign owner 及 14 个 execution 字段冲突。
- RED（第五轮审阅闭环）：两条 `RELEASED` two-restart 用例分别复现第二次启动补造 receipt 与追加两条假 audit；两条完整 `DECIDING` carrier-conflict 用例均停在错误的 `DECIDING`；repository 旁路用例在无 POST/receipt 时实际返回 PASS 并错误 mint。
- RED（第六轮正式审查闭环）：第一批新增 12 条——`ADVANCE_*` missing/conflicting exact receipt 6 red、`RELEASED` 后 failure close 崩溃 1 red、重复 release provenance/post-release synthetic history 2 red、durable PRE/missing owner/wrong phase 3 red。第一次 iterative re-review 再新增 8 条并全部复现：pre-`RELEASE_PENDING` normal/recovery split 与 `DECIDING` anchor 双重启 3 red，三 phase missing carrier、wrong-task 与 release checkpoint 0-row rollback 5 red。第二次 iterative re-review新增三 phase双 carrier与三 phase missing scheduleRef 共 6 red。
- GREEN：上述 26 条新增回归全部通过；完整 Auto app unit suite 498/498。正常执行与崩溃恢复两条生产路径仍以“provider 合法坐标远离旧 Auto 坐标”的正例完成可信 mint；production NaN/signed-zero、immutable replay、decision carrier、release-first 与两次重启矩阵均通过。
- 构建：Auto `testDebugUnitTest + lintDebug + assembleDebug + assembleRelease` 通过，最终一次合并执行 182 tasks；debug-only collector purity、零 lint 债务与 repo signer 校验继续通过。
- 复审：同一非作者 reviewer 对最终冻结 diff `9ae9a8e00fb156b17397bb652f190f14a41d835240bf839c77f75661454b391a` 返回 `APPROVE`；最后两项 OPEN 均 CLOSED，独立定向 `EngineQuotaRecoveryRedTest` 22/22，通过且未发现新增 P1/P2。
- 静态边界：Auto main/test 源码已无 `targetLat`、`targetLng`、caller tolerance、haversine 或旧冻结距离常量。
- 全仓：文档落盘后的最终 dirty-tree 预跑 `verify-a-plus --stage full` 为 10/11；唯一失败是 provenance 正确拒绝“工作树尚未提交”，其余双 App 单测/构建、contract、acceptance、边界与 release-debt 均通过。提交后必须在 clean exact HEAD 取得 11/11。
- 模拟器：上一候选 exact debug APK 已在 API 35 ARM64 隔离模拟器完成设备端字节校验与冷启动；第二轮审阅修复改变了 Auto APK 输入，因此最终 SHA/启动证据必须在新 clean HEAD 重跑后取代旧值。已连接的 Moto 真机未被触碰。模拟器未配置无障碍、LSPosed 与 System Mock，因此 smoke 不替代真机 G2。

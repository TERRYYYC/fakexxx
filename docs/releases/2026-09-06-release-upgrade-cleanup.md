---
feature_ids: [F-10, F-13, F-18]
topics: [release, upgrade, application-id, legacy-db, acceptance]
doc_kind: release_readiness_ledger
created: 2026-09-06
status: in-progress
---

# 发布与升级清理台账（2026-09-06）

这是唯一的 12 项发布/升级清账入口，不是发布声明，也不授权合并、安装或设备操作。基线仍是 `main` 的 `1a37084c5dd0915e0d64254353f671387f4d4f03`。候选 CI 只证明候选的 exact HEAD；它不替代独立审查、合入，也不替代设备验收。

## 读表规则与分项计划

- `DEVICE_BLOCKED` 仅表示实体设备与模拟器冻结造成的验收缺口；它不阻止明确的 host-only 实现、host JVM/mock 测试、静态检查或审查。
- #1、#8、#50 是治理/epic，不计入这 12 个修复项；#95 已由 #96 合入，不重开。
- 细节以三个既有分项交付为准：[#90 Vector resolver（PR #100）](https://github.com/TERRYYYC/fakexxx/pull/100)、[#13 flavor/build slice（PR #103）](https://github.com/TERRYYYC/fakexxx/pull/103)、[Auto durable-recovery slice（PR #104）](https://github.com/TERRYYYC/fakexxx/pull/104)。本表只汇总其闭环状态，不复制或以旧计划替代当前验据。
- 提交显示的 author 可能继承共享 Git 配置；本表的责任归属以任务线程为准，不能仅凭该显示作者转移责任或赋予审查资格。

## 12 项逐行清账

| Issue：原关闭条件 / 后续评论 | 当前阶段（实现 / 审查 / 合并 / 验收） | 具体欠缺证据 | 责任任务 | 下一动作 / 依赖 | 设备状态 |
| --- | --- | --- | --- | --- | --- |
| [#90](https://github.com/TERRYYYC/fakexxx/issues/90)：一次 lap 的三路命中不得静默漏采 evidence/search surface；后续评论：无。 | PR #100 `c3f561b` open；该旧 SHA 的 host CI 与[非作者 exact-head APPROVE](https://github.com/TERRYYYC/fakexxx/pull/100#issuecomment-5556432568)仍有效。Successor 候选的独立审查为 7×P1、1×P2 `REQUEST_CHANGES`，原作者正在修复。 | Successor 的 8 项 finding 尚未清零；关闭仍需合入、满足全部原 AC，并取得修复后独立 review，不得把旧 SHA 的批准扩展到 successor。 | Vector：`01a06286-30b3-7a53-9a51-319ac66023b6` | 原作者逐项修复 7×P1、1×P2 后重新申请独立审查；QWY module 内 oracle producer 独立推进，**不等待** Vector。 | 原 AC 中可由 host 完成的部分不是 `DEVICE_BLOCKED`，不得凭空增加手机门槛。 |
| [#89](https://github.com/TERRYYYC/fakexxx/issues/89)：每次进入 `RECOVERY_REQUIRED` 必有同事务 audit row 与原因载体；[根因评论](https://github.com/TERRYYYC/fakexxx/issues/89#issuecomment-5546178766)。 | #104 远端 `58dbf2d` open、8 项 host CI 全绿、未合入；本地 `468c7c6` 的原 reviewer 已给 `REQUEST_CHANGES`，原作者正在重建 state/invariant 边界。 | `EXHAUSTED` gate 漏掉 legacy validation；transaction rollback oracle、replay/concurrency 负例及修复后的 non-author review 仍不可由旧 CI 替代。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 先按冻结的 state/invariant 矩阵修复 legacy validation，再由原 reviewer 复验。 | host-only，不是 `DEVICE_BLOCKED`。 |
| [#86](https://github.com/TERRYYYC/fakexxx/issues/86)：冻结失败 audit event，`CLOSED` 前持久化 negative carrier；[#89 关联评论](https://github.com/TERRYYYC/fakexxx/issues/86#issuecomment-5546178996) 要求 normal/recovery RED 与 rollback oracle。 | #104 远端 `58dbf2d` host CI 全绿但仍 open、未合入；`468c7c6` 已收到 `REQUEST_CHANGES`，边界重建中，不能提前记为通过。 | 覆盖所有失败分类的持久化载体、transaction rollback 与修复后独立审查；不得把 #89 单一链路冒充全覆盖。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 等 state/invariant 矩阵修复后补齐分类证据并复验。 | host-only，不是 `DEVICE_BLOCKED`。 |
| [#85](https://github.com/TERRYYYC/fakexxx/issues/85)：release receipt 与 advance identity 原子持久化；后续评论：无。 | #104 远端 `58dbf2d` 仍 open、未合入且 host CI 全绿；本地 `468c7c6` 的原 reviewer 已给 `REQUEST_CHANGES`：`EXHAUSTED` gate 漏 legacy validation。原作者正在按主任务冻结的 state/invariant 矩阵重建边界。 | 同一事务的 identity proof、重复 dispatch、并发/replay、legacy validation 与事务失败回滚 RED/GREEN；修复后的独立复验尚未闭合。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 完成 state/invariant 边界修复后由原 reviewer 复验，再验证 carrier 连续性；与 #86 的失败分类边界分开审。 | host 证据可继续；原关闭条件仍含 real-device crash-window，`DEVICE_BLOCKED`。 |
| [#80](https://github.com/TERRYYYC/fakexxx/issues/80)：`startWithPlan` 的 check-and-set 与 session 创建不可分离，request→session 可证明；后续评论：无。 | 未见已合入 closure；不得由 #104 的 recovery 变更推断已完成。 | accepted run 退出前的 session durable 写入、冲突启动/replay/rollback host 证据与独立审查。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 优先补 host closure；只在有明确接口问题时向主任务提出，不等设备。 | host-only，不是 `DEVICE_BLOCKED`。 |
| [#66](https://github.com/TERRYYYC/fakexxx/issues/66)：权威 continuity oracle 必须证明 FULL history，不能由 app-local callback 自证；[main 复现评论](https://github.com/TERRYYYC/fakexxx/issues/66#issuecomment-5556219787)。 | Draft PR #105 `b013ff2` 8 项 host CI 全绿；producer 范围 `acff…f2` 与 decoder 范围 `f2…b013` 分别获独立 APPROVE。显式 JBR/SDK 下强制复跑 direct-open guard 1 项与 `CurrentOracleWiringTest` 4 项，共 2 suites / 5 tests / 0 failure、error、skip。该结论仅覆盖这些 delta/测试，**不等于**完整 main 差异已审，也不等于 #66 已完成。 | producer/readback 的完整 exact-head source-bound review、successor integration；AC7 仍缺 exact-build emulator 与授权 rooted 真机。 | 可信：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf` | 保持 Draft，先将 producer/decoder 与集成候选分开闭合审查，再评估 #66 全量证据。 | host 证据可继续；AC7 的 emulator/rooted-device 验收 `DEVICE_BLOCKED`。 |
| [#71](https://github.com/TERRYYYC/fakexxx/issues/71)：Binder 调用保留正确身份，Vector transport 有可归因版本证据；[Vector 后续评论](https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5556320033)。 | Binder identity 已经由 #73 进入 main；Vector follow-up #99 当前 `8c8ed6c`，有独立 review 且 8 项 host CI 全绿，仍 open、未合入。Vector 新候选 `aa3435ab` 仅完成结构完整性核验，未安装。 | 真实 Vector transport/version 与恢复因果证据；不能用文档、结构完整性或旧 emulator 证据替代。 | Vector：`01a06286-30b3-7a53-9a51-319ac66023b6` | 维持 upstream follow-up；设备解冻后才排入版本化 transport 验收。 | `DEVICE_BLOCKED`（transport 设备验收）。 |
| [#83](https://github.com/TERRYYYC/fakexxx/issues/83)：扩展 QWY observation audit storage，且不削弱 `TTL=0` evidence；后续评论：无。 | host 基线已由原 QWY 内部助手接续，尚无独立已审候选或合入结论；原 desktop 任务仍是 owner，不因内部执行者变化而转移归属。 | storage 增长策略、TTL=0 不变量、回归/边界 host 测试及独立审查；AC1/AC4 的代表性 Android 存储曲线与 device soak 尚缺。 | 可信：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf`（原 desktop owner；内部助手执行 host 基线） | 先完成 host 基线并测量增长，再形成候选与独立审查；不等待设备才开始。 | host 证据可继续；Android storage curve/device soak `DEVICE_BLOCKED`。 |
| [#79](https://github.com/TERRYYYC/fakexxx/issues/79)：原条件是有序 `profile-1..10` `discover()` readback；[范围升级评论](https://github.com/TERRYYYC/fakexxx/issues/79#issuecomment-5545119847) 后，正式闭环还要求 Auto `LocationTask` ↔ provider `scheduleItemId` 绑定、导入/seed 保留映射、由 provider `currentItemId` 选任务并记配额。 | QWY 列表候选 `0c8661b` 已存在但未验收；Auto 仍无 `scheduleItemId` 字段，scheduler 仍按 `priority,csvRow`。 | 双端绑定 schema、导入/seed preserve、`currentItemId` 选取和 quota record 的 host RED/GREEN、exact-head review；不能只用十条列表替代所有权绑定。 | 可信(QWY)：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf`；Auto 绑定：`01a0746d-07ec-7863-a237-9edd5819d753` | 可信提供 QWY 稳定有序身份；Auto 的绑定工作在 #97 安全 Stop 路径之后串行推进，而不是把它误记为已由 #85 闭合；发布只消费最终 schema。 | host 证据可继续；完整 G2 设备验收待解冻。 |
| [#13](https://github.com/TERRYYYC/fakexxx/issues/13)：`legacyId` 导出 → operator-controlled SAF carrier → `productId` 导入，canonical bundle round-trip、old→new→rollback；[真机约束评论](https://github.com/TERRYYYC/fakexxx/issues/13#issuecomment-5290197739)。 | PR #103 `91a4bd0` open、8 项 host CI 全绿；非作者对 `e826…91a` 新 delta APPROVE。guard 自测 20/20，codec 在两 flavor 各 8/8；实现严格是 metadata-only carrier，未合入，也**不是** cutover 实现。 | Auto Room v8 的 18 个候选表须 schema 核验；snapshot 与 DataStore 一致捕获、跨存储 restore visibility fence、原子 eligibility 复查、历史 pairing 不得复活为 active；SAF UI/codec、canonical bundle 黄金/反例 host 测试仍缺。 | 发布 / #13：`01a076d2-2da1-7bd2-b5ea-b50539b002cb`；Auto snapshot 接口：`01a0746d-07ec-7863-a237-9edd5819d753` | 维持 metadata-only 边界；待 Auto 提供 snapshot 接口时接入，不等待其 PR 合入。 | `DEVICE_BLOCKED`（`M-AC-03` old→new→rollback）；host 工作不阻塞。 |
| [#46](https://github.com/TERRYYYC/fakexxx/issues/46)：识别 legacy `user_version=0` 非 Room DB 并安全恢复，避免首次访问崩溃；后续评论：无。 | #102 代码候选 `b946bae` 已在 `fa346c6` 的 checkpointed-SHM 修复之上接入两处 direct-open 前置恢复，并获非作者 exact-HEAD `APPROVE`（0 P1/P2）。该 port 保留 `initScheduleFromDb` 的 fail-loud 与坐标读取的 `null` 失败语义，不含 #66/#79 变更；后续仅有本台账转录。 | `fa346c6` 的 SHM 仍消费其[既有非作者审查](https://github.com/TERRYYYC/fakexxx/pull/102#issuecomment-5559630437)；新审查只覆盖 `cf31b61…b946bae` 的两文件 port，不能扩展为完整 #105 APPROVE。#102 仍待新远端 CI、合入与全部原 AC；最终设备升级未验收。 | 发布 / #46：`01a076d2-2da1-7bd2-b5ea-b50539b002cb` | 推送已审 port 与本台账 continuity，等待 #102 新 remote CI；绿后仍按 merge gate 与原 AC 收口，不关闭 issue。 | 部分 `DEVICE_BLOCKED`（最终设备升级验证）；host/接线/CI/合入闭环不阻塞。 |
| [#97](https://github.com/TERRYYYC/fakexxx/issues/97)：unfinished plan 不得无安全处置路径地阻断 CSV import；[隔离复现评论](https://github.com/TERRYYYC/fakexxx/issues/97#issuecomment-5556225693)。 | 安全 Stop → 保留历史 → 允许导入的实现仍缺，未合入；已冻结三类 proof 路线。 | Stop 与历史归档、旧/新 plan 原子性、host 测试和非作者审查；合法 negative、`CLOSED` 与可归档 release 不得因存在 `Unverified` 行而被一律拒绝。原 AC 的隔离 emulator UI 验收尚缺。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 按已冻结的 proof 路线实现安全 Stop，验证历史可保留且导入不丢 unfinished plan；不以设备复跑代替回归测试。 | host 证据可继续；隔离 emulator UI `DEVICE_BLOCKED`。 |

## #13 的数据边界（校正旧的“五表 + config”表述）

当前 Auto Room v8 的迁移边界是**待 schema 核验的 18 个候选表**，而非已证明充分的“五表 + config”。任何 canonical bundle 只有在逐表 schema、行数和内容摘要都可审计时才可称为可恢复快照。它还必须同时携带并一致地恢复 DataStore 状态；restore 完成前的跨存储读取必须被 visibility fence 拦住，eligibility 必须在写入临界点原子复查，历史 pairing 只能作为历史记录，绝不能在恢复时复活为 active pairing。

这使 #103 的 identity flavor/CI 契约保持它应有的边界：它为后续构建通道提供护栏，但不证明 SAF、snapshot、迁移、回滚或 cutover eligibility。

## 发布边界

- 当前 production IDs 仍是 QWY `name.caiyao.fakegps` 与 Auto `com.example.cellrebelauto`。`come.xx.fakeaauto` 是目标身份，不是已发布事实。
- `versionName` 必须等于最终 tag 去掉 `v` 后的值；每个 package 的 `versionCode` 必须独立递增。
- 现有 `bench.keystore` 的公开证书只能支持受控私下覆盖安装；不能证明发布身份，不能用于公开 GitHub Release。
- 签名不兼容、升级探测不确定、DB schema 不健康或 profile 校验失败时停止；不得用 uninstall/clear-data 伪造升级成功。
- 三份历史未跟踪记录按既定决定原位保留；本台账不对其执行 commit、移动、删除或重新询问。

---
feature_ids: [F-10, F-13, F-18]
topics: [release, upgrade, application-id, legacy-db, acceptance]
doc_kind: release_readiness_ledger
created: 2026-09-06
status: in-progress
---

# 发布与升级清理台账（2026-09-06）

这是唯一的 12 项发布/升级清账入口，不是发布声明，也不授权安装、设备操作或发布。最终清账基线是 `main` 的 `10275b4dedbd51d41e43a708a41b00fc38f54f01`。候选 CI 只证明候选的 exact HEAD；它不替代独立审查、合入，也不替代设备验收。

## 读表规则与分项计划

- `DEVICE_BLOCKED` 仅表示实体设备与模拟器冻结造成的验收缺口；它不阻止明确的 host-only 实现、host JVM/mock 测试、静态检查或审查。
- #1、#8、#50 是治理/epic，不计入这 12 个修复项；#95 已由 #96 合入，不重开。
- 细节以三个既有分项交付为准：[#90 Vector resolver（PR #100）](https://github.com/TERRYYYC/fakexxx/pull/100)、[#13 flavor/build slice（PR #103）](https://github.com/TERRYYYC/fakexxx/pull/103)、[Auto durable-recovery slice（PR #104）](https://github.com/TERRYYYC/fakexxx/pull/104)。本表只汇总其闭环状态，不复制或以旧计划替代当前验据。
- 提交显示的 author 可能继承共享 Git 配置；本表的责任归属以任务线程为准，不能仅凭该显示作者转移责任或赋予审查资格。

## 12 项逐行清账

| Issue：原关闭条件 / 后续评论 | 当前阶段（实现 / 审查 / 合并 / 验收） | 具体欠缺证据 | 责任任务 | 下一动作 / 依赖 | 设备状态 |
| --- | --- | --- | --- | --- | --- |
| [#90](https://github.com/TERRYYYC/fakexxx/issues/90)：一次 lap 的三路命中不得静默漏采 evidence/search surface；后续评论：无。 | Successor PR #107 `2dc598e` 先合入 #100 分支；两段 exact-head 独立审查均为 `APPROVE`、0 P1/P2。PR #100 最终 HEAD `387d1de` 通过 latest-main full gate 16/16 与 [CI 8/8](https://github.com/TERRYYYC/fakexxx/actions/runs/34063009884)，随后 squash 合入 `main` 为 `10275b4dedbd51d41e43a708a41b00fc38f54f01`。 | **CLOSED / COMPLETED**。原 9 条 AC 已映射到共享 exact-package resolver、artifact provenance、UI/search guard 与 368/368 host matrix；无设备证明外推。 | Vector：`01a06286-30b3-7a53-9a51-319ac66023b6`；最终 merge owner：root。 | [关闭证据](https://github.com/TERRYYYC/fakexxx/issues/90#issuecomment-5562578092) 已回读。#66/#71 仍是独立范围。 | host-only，已闭环。 |
| [#89](https://github.com/TERRYYYC/fakexxx/issues/89)：每次进入 `RECOVERY_REQUIRED` 必有同事务 audit row 与原因载体；[根因评论](https://github.com/TERRYYYC/fakexxx/issues/89#issuecomment-5546178766)。 | PR #104 的已审行为候选 `a30f0f5` 经 current-main 同步到 PR HEAD `58e0f22`，已 squash 合入 `main` 为 `ccd00533b79517f9b93895c5774557b623d76625`；自动关单引用为空。 | **CLOSED / COMPLETED**。root 独立强制复跑 1 项 targeted test，0 failure/error/skip；原完整 704 项与 CI/full gate 证据保持归属，不从本轮 targeted XML 反推 704 项。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | [关闭证据](https://github.com/TERRYYYC/fakexxx/issues/89#issuecomment-5562521224) 已回读；与仍 OPEN 的 #86 分开。 | host-only，已闭环。 |
| [#86](https://github.com/TERRYYYC/fakexxx/issues/86)：冻结 distinct failure audit events、spec enum 与 reducer，且在 `CLOSED` 前持久化 negative carrier；[#89 关联评论](https://github.com/TERRYYYC/fakexxx/issues/86#issuecomment-5546178996) 还要求 normal/recovery RED 与 rollback oracle。 | PR #104 已合入 `ccd00533`，但 root 回读原始 AC 后确认：已合代码没有满足 distinct events / spec-enum-reducer 的完整合同；不存在改写 AC 的授权。[范围更正](https://github.com/TERRYYYC/fakexxx/issues/86#issuecomment-5562514768) 已发布。 | **仍缺 host 事件契约生产实现与 RED→GREEN、完整枚举/reducer 映射和独立审查。** 这是代码缺口，不是设备缺口，也不能借 #89 的同事务 audit 实现关闭。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 保持 OPEN；后续单独实现并审查 #86 host 事件合同。 | host-only，非 `DEVICE_BLOCKED`。 |
| [#85](https://github.com/TERRYYYC/fakexxx/issues/85)：release receipt 与 advance identity 原子持久化；后续评论：无。 | PR #104 的行为候选 `a30f0f5` 已审、704 项 host test 与 CI 8/8 全绿，并经同步 HEAD `58e0f22` 合入 `main` 为 `ccd00533`。 | host 代码已合；原关闭条件中的 real-device crash-window 尚未执行，因此 Issue 必须保持 OPEN。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 设备解冻后执行精确构建 crash-window 验收，再决定关闭。 | `DEVICE_BLOCKED`。 |
| [#80](https://github.com/TERRYYYC/fakexxx/issues/80)：`startWithPlan` 的 check-and-set 与 session 创建不可分离，request→session 可证明；后续评论：无。 | PR #104 已含 durable start admission，完整行为链获独立 APPROVE，并合入 `main` 为 `ccd00533`。 | **CLOSED / COMPLETED**。root 独立强制复跑 4 项 targeted tests，0 failure/error/skip；原完整 704 项与 CI/full gate 证据保持归属，不从本轮 targeted XML 反推 704 项。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | [关闭证据](https://github.com/TERRYYYC/fakexxx/issues/80#issuecomment-5562520833) 已回读。 | host-only，已闭环。 |
| [#66](https://github.com/TERRYYYC/fakexxx/issues/66)：权威 continuity oracle 必须证明 FULL history，不能由 app-local callback 自证；[main 复现评论](https://github.com/TERRYYYC/fakexxx/issues/66#issuecomment-5556219787)。 | Draft PR #105 远端 `2f9738d` 8 项 host CI 全绿；既有 producer/decoder slice 与新增 #83 host-baseline slice 各自只在其真实范围内获独立 APPROVE。显式 JBR/SDK 下既有 direct-open guard 1 项与 `CurrentOracleWiringTest` 4 项仍为 2 suites / 5 tests / 0 failure、error、skip。各 slice 审查**不等于**完整 main 差异已审，也不等于 #66 已完成。 | producer/readback 的完整 exact-head source-bound review、successor integration；AC7 仍缺 exact-build emulator 与授权 rooted 真机。 | 可信：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf` | 保持 Draft，继续按 slice 闭合 host 证据，再对完整集成差异做独立审查；不得用 #83 baseline 扩张 #66 approval。 | host 证据可继续；AC7 的 emulator/rooted-device 验收 `DEVICE_BLOCKED`。 |
| [#71](https://github.com/TERRYYYC/fakexxx/issues/71)：Binder 调用保留正确身份，Vector transport 有可归因版本证据；[Vector 后续评论](https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5556320033)。 | Binder identity 已经由 #73 进入 main；Vector follow-up #99 当前 `8c8ed6c`，有独立 review 且 8 项 host CI 全绿，仍 open、未合入。Vector 新候选 `aa3435ab` 仅完成结构完整性核验，未安装。 | 真实 Vector transport/version 与恢复因果证据；不能用文档、结构完整性或旧 emulator 证据替代。 | Vector：`01a06286-30b3-7a53-9a51-319ac66023b6` | 维持 upstream follow-up；设备解冻后才排入版本化 transport 验收。 | `DEVICE_BLOCKED`（transport 设备验收）。 |
| [#83](https://github.com/TERRYYYC/fakexxx/issues/83)：扩展 QWY observation audit storage，且不削弱 `TTL=0` evidence；后续评论：无。 | Draft PR #105 远端 `2f9738d` 8 项 host CI 全绿；仅 3 文件 host-baseline slice 获独立 `APPROVE`，包含真实 backing 测量与 8 项测试。原 desktop 任务仍是 owner。 | 该 slice 是测量/基线，不是 production storage fix，也不是 Android 证据；仍缺增长策略、`TTL=0` 不变量的生产实现/边界验证，以及 AC1/AC4 的 Android storage curve 与 device soak。 | 可信：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf`（原 desktop owner；内部助手完成 host baseline） | 消费 baseline 形成 production 候选与独立审查；真实 Android 测量仅在设备解冻后补，不把 8 项 host 测试冒充 feature close。 | host 基线已完成；Android storage curve/device soak `DEVICE_BLOCKED`。 |
| [#79](https://github.com/TERRYYYC/fakexxx/issues/79)：原条件是有序 `profile-1..10` `discover()` readback；[范围升级评论](https://github.com/TERRYYYC/fakexxx/issues/79#issuecomment-5545119847) 后，正式闭环还要求 Auto `LocationTask` ↔ provider `scheduleItemId` 绑定、导入/seed 保留映射、由 provider `currentItemId` 选任务并记配额。 | QWY 列表候选 `0c8661b` 已存在但未验收；Auto 仍无 `scheduleItemId` 字段，scheduler 仍按 `priority,csvRow`。 | 双端绑定 schema、导入/seed preserve、`currentItemId` 选取和 quota record 的 host RED/GREEN、exact-head review；不能只用十条列表替代所有权绑定。 | 可信(QWY)：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf`；Auto 绑定：`01a0746d-07ec-7863-a237-9edd5819d753` | 可信提供 QWY 稳定有序身份；Auto 的绑定工作在 #97 安全 Stop 路径之后串行推进，而不是把它误记为已由 #85 闭合；发布只消费最终 schema。 | host 证据可继续；完整 G2 设备验收待解冻。 |
| [#13](https://github.com/TERRYYYC/fakexxx/issues/13)：`legacyId` 导出 → operator-controlled SAF carrier → `productId` 导入，canonical bundle round-trip、old→new→rollback；[真机约束评论](https://github.com/TERRYYYC/fakexxx/issues/13#issuecomment-5290197739)。 | PR #103 `91a4bd0` open、8 项 host CI 全绿；非作者对初始 build slice 与 `e826…91a` 新 delta 均已复审，最终 0 P1/P2。guard 自测 20/20，codec 在两 flavor 各 8/8；当前只冻结 18 表 schema/digest carrier、identity matrix 与 host isolation guard，未接入运行时 cutover。 | 仍缺 Auto snapshot 与 DataStore 一致捕获、跨存储 atomic restore visibility fence、写入临界点 eligibility 复查、历史 pairing 不复活、SAF UI/执行接线及完整 round-trip/rollback。 | 发布 / #13：`01a076d2-2da1-7bd2-b5ea-b50539b002cb`；Auto snapshot 接口：`01a0746d-07ec-7863-a237-9edd5819d753` | 今晚不抢在 #104/#79 稳定前合入 #103；保留为独立 substrate 候选。正式 cutover 仍按完整 snapshot/SAF 合同推进。 | host 工作可继续；`M-AC-03` old→new→rollback 为 `DEVICE_BLOCKED`。 |
| [#46](https://github.com/TERRYYYC/fakexxx/issues/46)：识别 legacy `user_version=0` 非 Room DB 并安全恢复，避免首次访问崩溃；后续评论：无。 | PR #102 exact HEAD `ca60613` 已获完整分段独立审查与 CI 8/8，并 squash 合入 `main` 为 `986d956271ae1cd9b5facb1eb9e650537c66dfa1`；#46 未被自动关闭。 | host 代码/接线/合入已完成；只缺原 AC 的真实 legacy 升级设备验收。 | 发布 / #46：`01a076d2-2da1-7bd2-b5ea-b50539b002cb` | 保持 OPEN；设备解冻后用精确构建验证升级，不以 uninstall/clear-data 代偿。 | `DEVICE_BLOCKED`。 |
| [#97](https://github.com/TERRYYYC/fakexxx/issues/97)：unfinished plan 不得无安全处置路径地阻断 CSV import；[隔离复现评论](https://github.com/TERRYYYC/fakexxx/issues/97#issuecomment-5556225693)。 | stop-proof、Service/ViewModel stop-only、事务重验与 durable scan 已完成 RED→GREEN；首个候选因 stop-only apply redispatch 被审查拒绝，修复后的 exact `a30f0f5` 获 APPROVE、704 host tests 与 CI 8/8，并随 PR #104 合入 `ccd00533`。自动关单引用在合入前已清空。 | host 代码已完成；原 AC 的隔离 emulator/UI 验收未执行，Issue 保持 OPEN。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 设备解冻后执行 unfinished-plan UI journey；不得因代码已合提前关闭。 | `DEVICE_BLOCKED`。 |

## 后续需求/交互设计的不稳定地基

| Issue | 设计前必须冻结的合同 | 当前禁止建立的假设 |
| --- | --- | --- |
| [#79](https://github.com/TERRYYYC/fakexxx/issues/79) | QWY `profile-*` 与 Auto `scheduleItemId` 的唯一身份、导入/seed 保留、`currentItemId` 选择与 quota 归属 | 不得继续以 `priority,csvRow` 作为跨应用稳定身份。 |
| [#13](https://github.com/TERRYYYC/fakexxx/issues/13) | 版本化 snapshot/restore、DataStore 一致捕获、visibility fence、`CutoverEligibility` 与 SAF carrier | 不得把 BuildConfig metadata 或 18 表清单当成已经具备 cutover/迁移能力。 |
| [#66](https://github.com/TERRYYYC/fakexxx/issues/66) | source-bound authoritative continuity/readback 与 FULL history 证明 | 不得让 app-local callback 或自报状态证明 FULL。 |
| [#83](https://github.com/TERRYYYC/fakexxx/issues/83) | append-only/分层保留策略、storage curve、TTL=0 provenance 不变量 | 不得以压缩、清理或采样换取规模而削弱 TTL=0 证据。 |
| [#86](https://github.com/TERRYYYC/fakexxx/issues/86) | distinct failure events、冻结 spec enum、reducer 与 durable negative carrier 的一一映射 | 不得假设 #104 的通用 audit row 已满足全部失败事件合同。 |

## #13 的数据边界（校正旧的“五表 + config”表述）

当前 Auto Room v8 的迁移边界是**待 schema 核验的 18 个候选表**，而非已证明充分的“五表 + config”。任何 canonical bundle 只有在逐表 schema、行数和内容摘要都可审计时才可称为可恢复快照。它还必须同时携带并一致地恢复 DataStore 状态；restore 完成前的跨存储读取必须被 visibility fence 拦住，eligibility 必须在写入临界点原子复查，历史 pairing 只能作为历史记录，绝不能在恢复时复活为 active pairing。

这使 #103 的 identity flavor/CI 契约保持它应有的边界：它为后续构建通道提供护栏，但不证明 SAF、snapshot、迁移、回滚或 cutover eligibility。

## 发布边界

- 当前 production IDs 仍是 QWY `name.caiyao.fakegps` 与 Auto `com.example.cellrebelauto`。`come.xx.fakeaauto` 是目标身份，不是已发布事实。
- `versionName` 必须等于最终 tag 去掉 `v` 后的值；每个 package 的 `versionCode` 必须独立递增。
- 现有 `bench.keystore` 的公开证书只能支持受控私下覆盖安装；不能证明发布身份，不能用于公开 GitHub Release。
- 签名不兼容、升级探测不确定、DB schema 不健康或 profile 校验失败时停止；不得用 uninstall/clear-data 伪造升级成功。
- 三份历史未跟踪记录按既定决定原位保留；本台账不对其执行 commit、移动、删除或重新询问。

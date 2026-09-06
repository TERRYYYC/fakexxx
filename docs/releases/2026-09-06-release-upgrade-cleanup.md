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

## 12 项逐行清账

| Issue：原关闭条件 / 后续评论 | 当前阶段（实现 / 审查 / 合并 / 验收） | 具体欠缺证据 | 责任任务 | 下一动作 / 依赖 | 设备状态 |
| --- | --- | --- | --- | --- | --- |
| [#90](https://github.com/TERRYYYC/fakexxx/issues/90)：一次 lap 的三路命中不得静默漏采 evidence/search surface；后续评论：无。 | PR #100 `c3f561b` open；host CI 绿，且有[非作者 exact-head APPROVE](https://github.com/TERRYYYC/fakexxx/pull/100#issuecomment-5556432568)，未合入。 | Vector resolver 与 test hook 的共享脚本闭环已获该 SHA 审查；真实运行面仍不能从 host CI 推断。 | Vector：`01a06286-30b3-7a53-9a51-319ac66023b6` | Vector 本轮收口 #90 脚本与负例；QWY module 内 oracle producer 由可信任务独立推进，**不等待** Vector。 | 部分 `DEVICE_BLOCKED`（真实运行验收）；host closure 不阻塞。 |
| [#89](https://github.com/TERRYYYC/fakexxx/issues/89)：每次进入 `RECOVERY_REQUIRED` 必有同事务 audit row 与原因载体；[根因评论](https://github.com/TERRYYYC/fakexxx/issues/89#issuecomment-5546178766)。 | #104 当前 `c64e203` open、未合入；此前 `f5c4733` 不是当前审查结论。 | transaction rollback oracle、replay/concurrency 负例及 non-author exact-head review；CI 不能代替这些条件。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 以 #104 当前 HEAD 补独立审查并把 audit/reducer/DAO 同事务证据回填到 issue。 | host-only，不是 `DEVICE_BLOCKED`。 |
| [#86](https://github.com/TERRYYYC/fakexxx/issues/86)：冻结失败 audit event，`CLOSED` 前持久化 negative carrier；[#89 关联评论](https://github.com/TERRYYYC/fakexxx/issues/86#issuecomment-5546178996) 要求 normal/recovery RED 与 rollback oracle。 | #104 仅候选实现面，open、未合入、未见 review verdict。 | 覆盖所有失败分类的持久化载体、transaction rollback 与独立审查；不得把 #89 单一链路冒充全覆盖。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 在 #104 的 exact HEAD 上补齐分类矩阵与 host 测试，再申请非作者审查。 | host-only，不是 `DEVICE_BLOCKED`。 |
| [#85](https://github.com/TERRYYYC/fakexxx/issues/85)：release receipt 与 advance identity 原子持久化；后续评论：无。 | Auto 候选正在 #104 演进，尚未合入；作者调用链核对已确认 receipt 先提交、`ADVANCE_PENDING` 先投影、carrier 后续才事务写入，是 P1，不是“可能”风险。 | 同一事务的 identity proof、重复 dispatch、并发/replay 与事务失败回滚 RED/GREEN；独立审查缺失。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 先完成完整差异审查与 host RED，修复后再验证 carrier 连续性；与 #86 的失败分类边界分开审。 | host 证据可继续；原关闭条件仍含 real-device crash-window，`DEVICE_BLOCKED`。 |
| [#80](https://github.com/TERRYYYC/fakexxx/issues/80)：`startWithPlan` 的 check-and-set 与 session 创建不可分离，request→session 可证明；后续评论：无。 | 未见已合入 closure；不得由 #104 的 recovery 变更推断已完成。 | accepted run 退出前的 session durable 写入、冲突启动/replay/rollback host 证据与独立审查。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 优先补 host closure；只在有明确接口问题时向主任务提出，不等设备。 | host-only，不是 `DEVICE_BLOCKED`。 |
| [#66](https://github.com/TERRYYYC/fakexxx/issues/66)：权威 continuity oracle 必须证明 FULL history，不能由 app-local callback 自证；[main 复现评论](https://github.com/TERRYYYC/fakexxx/issues/66#issuecomment-5556219787)。 | 可信任务拥有 QWY module 内 oracle producer；`acff79d` 仅纠正 producer 归属，不能提升 #66 代码完成度；#68/#98 均 open。 | producer/readback 的 exact-head host 证据、source-bound review、successor integration；AC7 仍缺 exact-build emulator 与授权 rooted 真机。 | 可信：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf` | 先完成 QWY module 内 oracle producer 与 host contract；随后按集成候选请求独立 review。 | host 证据可继续；AC7 的 emulator/rooted-device 验收 `DEVICE_BLOCKED`。 |
| [#71](https://github.com/TERRYYYC/fakexxx/issues/71)：Binder 调用保留正确身份，Vector transport 有可归因版本证据；[Vector 后续评论](https://github.com/TERRYYYC/fakexxx/issues/71#issuecomment-5556320033)。 | Binder identity 已经由 #73 进入 main；Vector follow-up 在 #99 open，非关闭状态。 | 真实 Vector transport/version 与恢复因果证据；不能用文档或旧 emulator 证据替代。 | Vector：`01a06286-30b3-7a53-9a51-319ac66023b6` | 维持 upstream follow-up；设备解冻后才排入版本化 transport 验收。 | `DEVICE_BLOCKED`（transport 设备验收）。 |
| [#83](https://github.com/TERRYYYC/fakexxx/issues/83)：扩展 QWY observation audit storage，且不削弱 `TTL=0` evidence；后续评论：无。 | 未见独立已审候选或合入结论。 | storage 增长策略、TTL=0 不变量、回归/边界 host 测试及独立审查；AC1/AC4 的代表性 Android 存储曲线与 device soak 尚缺。 | 可信：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf` | 与 #66 并行做 host 设计/测试；测量增长而非仅写容量推测。 | host 证据可继续；Android storage curve/device soak `DEVICE_BLOCKED`。 |
| [#79](https://github.com/TERRYYYC/fakexxx/issues/79)：原条件是有序 `profile-1..10` `discover()` readback；[范围升级评论](https://github.com/TERRYYYC/fakexxx/issues/79#issuecomment-5545119847) 后，正式闭环还要求 Auto `LocationTask` ↔ provider `scheduleItemId` 绑定、导入/seed 保留映射、由 provider `currentItemId` 选任务并记配额。 | QWY 列表候选 `0c8661b` 已存在但未验收；Auto 仍无 `scheduleItemId` 字段，scheduler 仍按 `priority,csvRow`。 | 双端绑定 schema、导入/seed preserve、`currentItemId` 选取和 quota record 的 host RED/GREEN、exact-head review；不能只用十条列表替代所有权绑定。 | 可信(QWY)：`01a0746e-4aa8-7dd3-a3a1-b3eeede6e7cf`；Auto 绑定：`01a0746d-07ec-7863-a237-9edd5819d753` | 可信提供 QWY 稳定有序身份；Auto 在 #85 完整交付后接 schema/import/scheduler 对应关系；发布只消费最终 schema。 | host 证据可继续；完整 G2 设备验收待解冻。 |
| [#13](https://github.com/TERRYYYC/fakexxx/issues/13)：`legacyId` 导出 → operator-controlled SAF carrier → `productId` 导入，canonical bundle round-trip、old→new→rollback；[真机约束评论](https://github.com/TERRYYYC/fakexxx/issues/13#issuecomment-5290197739)。 | PR #103 `e82641f` open，8/8 CI 绿且有独立复审；仅 flavor/variant build 契约，未合入，也**不是** cutover 实现。 | Auto Room v8 的 18 个候选表须 schema 核验；snapshot 与 DataStore 一致捕获、跨存储 restore visibility fence、原子 eligibility 复查、历史 pairing 不得复活为 active；SAF UI/codec、canonical bundle 黄金/反例 host 测试仍缺。 | 发布 / #13：`01a076d2-2da1-7bd2-b5ea-b50539b002cb`；Auto snapshot 接口：`01a0746d-07ec-7863-a237-9edd5819d753` | 消费方立即做 schema 独立清单、bundle 样例和负例 host 测试；待 Auto 提供 snapshot 接口时接入，不等待其 PR 合入。 | `DEVICE_BLOCKED`（`M-AC-03` old→new→rollback）；host 工作不阻塞。 |
| [#46](https://github.com/TERRYYYC/fakexxx/issues/46)：识别 legacy `user_version=0` 非 Room DB 并安全恢复，避免首次访问崩溃；后续评论：无。 | 代码基点 `fa346c6` 已有 8/8 CI 与[非作者 exact-head APPROVE](https://github.com/TERRYYYC/fakexxx/pull/102#issuecomment-5559630437)；#102 后续 docs HEAD 另列，PR 仍 open、未合入。可信候选 `ccef5a1` 才带后续 direct-open wiring，且同样尚未进 main。 | `ccef5a1` 的 exact-head 独立审查、direct-open 两处接线和 host/instrumentation 证据；不得把 #102 的 CI 绿冒充候选已接或 production legacy-state 已知。 | 发布 / #46：`01a076d2-2da1-7bd2-b5ea-b50539b002cb` | 保全 `ccef5a1` 候选，核验其 exact diff 后走 review；不在本表重写 Auto DB/迁移。 | 部分 `DEVICE_BLOCKED`（最终设备升级验证）；候选审查不阻塞。 |
| [#97](https://github.com/TERRYYYC/fakexxx/issues/97)：unfinished plan 不得无安全处置路径地阻断 CSV import；[隔离复现评论](https://github.com/TERRYYYC/fakexxx/issues/97#issuecomment-5556225693)。 | #104 当前 `c64e203` 只消除了 paused-archive 文档矛盾；#97 仍无安全出口，未合入。 | 明确 resolution UX/state transition、旧/新 plan 原子性、host 测试和非作者审查；原 AC 的隔离 emulator UI 验收尚缺。 | Auto：`01a0746d-07ec-7863-a237-9edd5819d753` | 在 Auto host 工作中定义安全 resolution，验证不会丢失 unfinished plan；不以设备复跑代替回归测试。 | host 证据可继续；隔离 emulator UI `DEVICE_BLOCKED`。 |

## #13 的数据边界（校正旧的“五表 + config”表述）

当前 Auto Room v8 的迁移边界是**待 schema 核验的 18 个候选表**，而非已证明充分的“五表 + config”。任何 canonical bundle 只有在逐表 schema、行数和内容摘要都可审计时才可称为可恢复快照。它还必须同时携带并一致地恢复 DataStore 状态；restore 完成前的跨存储读取必须被 visibility fence 拦住，eligibility 必须在写入临界点原子复查，历史 pairing 只能作为历史记录，绝不能在恢复时复活为 active pairing。

这使 #103 的 identity flavor/CI 契约保持它应有的边界：它为后续构建通道提供护栏，但不证明 SAF、snapshot、迁移、回滚或 cutover eligibility。

## 发布边界

- 当前 production IDs 仍是 QWY `name.caiyao.fakegps` 与 Auto `com.example.cellrebelauto`。`come.xx.fakeaauto` 是目标身份，不是已发布事实。
- `versionName` 必须等于最终 tag 去掉 `v` 后的值；每个 package 的 `versionCode` 必须独立递增。
- 现有 `bench.keystore` 的公开证书只能支持受控私下覆盖安装；不能证明发布身份，不能用于公开 GitHub Release。
- 签名不兼容、升级探测不确定、DB schema 不健康或 profile 校验失败时停止；不得用 uninstall/clear-data 伪造升级成功。
- 三份历史未跟踪记录按既定决定原位保留；本台账不对其执行 commit、移动、删除或重新询问。

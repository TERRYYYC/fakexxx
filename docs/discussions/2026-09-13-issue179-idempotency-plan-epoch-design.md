---
feature_ids: []
related_features: []
topics: [cellrebel-auto, idempotency, contract-v1, recovery, issue-179]
doc_kind: spec
created: 2026-09-13
---

# #179 设计缺口修复设计：幂等键计划纪元（IDEMPOTENCY_CONFLICT 回绕）

> Status: design（draft PR，本 PR 不含实现） | 关联: TERRYYYC/fakexxx#179「补充：今晚排障又剥出两层」评论 | 范围: Auto 单侧（cellrebel-auto）

## Why（问题与实证）

- Auto 侧幂等键只由 attempt 主键派生：`APlusOperationIdentity.applyIdempotencyKey(attemptId) = "auto-aplus-apply-$attemptId"`（release 同构 `auto-aplus-release-$attemptId`；ADVANCE 复用 apply 键，见 `CompleteAndAdvanceRequestV1`）。
- `test_attempts.id` 是 AUTOINCREMENT——**单个 DB 生命周期内**单调不回绕（`deletePristineIdReservation` 依赖 sqlite_sequence 永续推进）。但 DB 重置向量（清数据/重装/排障重置，#179 记录为常规运维动作）会把计数器归零。
- QWY 侧收据（`DurableIdempotencyStore`，`integration.v1.receipts` 命名空间）跨 Auto DB 重置**永久存活**，按 (caller, operation, key) 寻址；同键不同摘要 → IDEMPOTENCY_CONFLICT(12)。
- g54 实证（2026-09-13）：9 月 9-10 日老计划 `apply-5` 收据 × 新计划 attempt 5 同键不同摘要 → 冲突；当时靠 QWY 合同存储整体重置 + 配对重批解决——不应是常规手段。

## What（选定方向：键含计划纪元；否决 provider 侧清扫）

键改为 `auto-aplus-apply-{planEpoch}-{attemptId}` / `auto-aplus-release-{planEpoch}-{attemptId}`。

**纪元取值 = `location_plans.importedAt`（墙钟毫秒，plan 行持久字段）**，满足三个硬性要求：
1. **每次导入必不同，且跨 Auto DB 重置仍不同**——planId/runSessionId 重置后从 1 重来，只有墙钟满足本条（这是 g54 的实际触发形态）；
2. **计划生命周期内稳定**——崩溃恢复从 durable owner 态（attempt → task → plan）重算，逐字节一致；
3. **重算路径已存在**——`APlusComposition.observeLive` 等处已有 attempt → task → plan 全链读取。

**否决 provider 侧 schedule rebuild 清扫**：
- QWY 收据对 opaque key 无法区分纪元；清扫会连带摧毁**在途 attempt 的同键重放保护**（§8.1 CRASH_RECOVER 依赖收据重放原收据）——恰在最需要时关掉幂等；
- 加清扫 op = 契约变更 + 双端版本协同，改动面严格更大。

**契约影响 = 零**：§6.3.4 幂等键是 FREE string，QWY 侧视为不透明；frozen digest framing（`CanonicalIntentDigestV1` / `CanonicalDigestV1`）不动；无契约版本号变更。本修复纯 Auto 单侧。

## 兼容纪律（为何必须持久化纪元列，而非调用时派生）

本库先例：`IdempotencyStore.legacyScopeKey` 旧格式读回退；`aplusIntentProfileRef` null = 「历史行重算旧字面量」判别器。照此：

- `test_attempts` 增列 `aplusPlanEpoch: Long?`，**admission 时落库**（`reserveAplusAttemptId` 模板携带；`insertAdmittedAplusAttempt` pristine 守卫同步纳入新列——注意 reservation 守卫 `deletePristineIdReservation` 需按非空列更新，这是一个易漏点）；
- null 判别器语义：升级前 admitted 的在途 attempt（已用旧键 apply 过）重算**旧格式键**，升级中途恢复重放不错配（否则 QWY find 不到 → 重 apply → 撞 ACTIVE 租约 → LEASE_CONFLICT，fail-closed 但把 §8.1 可恢复 attempt 变成运维介入案例）；
- 旧格式存量收据**永不误拒**：新 attempt 键带纪元，与旧格式键结构不同、永不碰撞；旧收据成为无害沉淀，无需清扫（g54 已一次性清过，此后不需要）。
- DB 迁移：v10 → v11，`ALTER TABLE test_attempts ADD COLUMN aplusPlanEpoch INTEGER`，增量、不触数据，符合迁移阶梯惯例。

## 改动面评估（为何本 PR 停在设计）

实现需要（主源码 8 文件）：
`model/plan/Entities.kt`、`db/AppDatabase.kt`（v11）、`db/Migrations.kt`、`db/TestAttemptDao.kt`、`automation/aplus/APlusOperationIdentity.kt`、`repository/PlanRepository.kt`（14 处键引用：mint + 校验重算）、`automation/AutomationEngine.kt`（8 处）、`automation/APlusComposition.kt`（2 处）；另 ~15 个测试文件 30+ 处直接引用键构造器。

**超过本任务 ≤4 文件预算 → 按 stop rule 交付设计 + draft PR，不硬改。** 实现应单独立 PR，避免与 #180（僵尸 attempt 终结）并行改动互相干扰。

## 计划测试（实现 PR 验收）

1. 键构造：同纪元同 attempt → 同键；同 attempt 不同纪元 → 不同键；epoch=null → 旧字面量逐字节一致；
2. 旧收据不误拒：旧格式收据 + null-epoch attempt → 重放命中原收据（非 IDEMPOTENCY_CONFLICT）；
3. 跨纪元重导：纪元 A apply 收据存在，重导（纪元 B）同序号 attempt → 新键，QWY 视为新操作，旧收据不受扰；
4. MIGRATION_10_11：存量行保留、epoch=null；
5. reservation pristine 守卫：模板携带纪元时 reserve→delete 幂等仍成立。

## Open Questions

1. 纪元粒度取 plan 导入级（importedAt）已倾向成立——与 #179 触发形态（重导即新纪元）对齐，且计划内 session supersede（#135）不换纪元；如 owner 认为 run-session 级更合适请指出；
2. RELEASE 键建议对称加纪元（releaseDigest 本身 frozen 且 lease 绑定，不受影响）；
3. 排障 runbook 是否补一句「修复前时代 DB 已重置的设备需一次性清旧收据」（g54 已做，存量设备清单待 owner 确认）。

## Next Action

owner 认可本设计 → 实现 PR（Auto 单侧 8 文件 + 迁移 + 测试）→ 真机验证按 #179 模式：同一设备重导计划两轮，确认无 IDEMPOTENCY_CONFLICT，且升级途中的 null-epoch 在途 attempt 恢复重放仍命中。

## 明确不越界

遗留 EXPIRED 租约无第三方释放路径（LEASE_CONFLICT(7)，合同 §6.3.3）是 #179 记录的另一独立缺口，**不在本设计范围**，留待后续 PR。

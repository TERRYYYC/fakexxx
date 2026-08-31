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
status: fixed_pending_rereview
github_issue: 64
---

# KB-8 Auto coordinate trust drift

## 诊断胶囊

| 栏位 | 结论 |
|---|---|
| 现象 | G2 的 10 地址流程即使收到完整、provider 侧独立验证的 observation，Auto 仍可能把完成判为 `FAIL`，无法写入可信配额。PR #62 使用超出合法范围的占位坐标后，该失败成为确定性的。 |
| 证据 | canonical spec §2.2、§6.4、§6.4.1 将目标坐标与距离比较独占给千网游；`CompletionTrustContext` 仍携带 Auto-local target；`TrustPolicy` 仍执行 haversine；`AutomationEngine` 正常与恢复路径都从 `LocationTask` / `TestAttempt` 注入目标坐标。 |
| 问题假设或根因 | **已确认根因**：PR #24 / KB-8 从 wire 与 canonical digest 移除了坐标，但其后落地的 Auto trust policy 仍按被废弃的双 owner 模型实现；contract/spec 迁移没有传播到内部 trust context、policy、engine wiring 与测试。首轮正式审阅又证明 production evidence source 与 engine 会对同一 observation 唯一键执行两次 `INSERT OR ABORT`，使真实正常路径在 PRE 后必然暂停。 |
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

### 4. 修复方案

- 新增失败回归测试，冻结“Auto 不做距离比较”的行为。
- 从 `CompletionTrustContext` 删除 target 与 caller tolerance。
- 从 `TrustPolicy` 删除 haversine 与 Auto-local target gate，只保留 provider effective coordinate 的 non-null / finite / range 校验。
- 删除 normal/recovery engine 的旧坐标注入，更新测试与注释。
- 让 `PlanRepository` 成为 PRE/POST 与 completion receipt 的唯一持久化权威：`INSERT IGNORE` 后立即读回并严格比对不可变载荷。同值重放是幂等 no-op；同键异值保留首写并 fail-closed，禁止 `REPLACE` 或裸 `IGNORE`。
- 新增从 `APlusComposition.productionBackend()` 经 `AutomationEngine` 到 trusted mint 的真实组装回归，provider 返回与旧 Auto plan 距离很远的 Kyiv 坐标。

不改 provider 侧验证、不放宽其他 §6.4 谓词，也不修改 wire contract。

### 5. 验证方式

- 目标 RED→GREEN 测试及现有逐字段 trust negative matrix。
- CellRebel Auto `testDebugUnitTest`、lint、debug/release assemble。
- 仓库 `verify-a-plus --stage full` 与相关静态 guards。
- Android 模拟器安装/启动 smoke；真机 G2 仍需独立 device lease 与 exact-build 证据。

## 验证结果

- RED：新增 KB-8 回归在旧实现上是 52 项中的唯一失败，失败点为旧 Auto-local 距离判定。
- RED（首轮审阅闭环）：新增的 shipped-production 正常链在旧实现上停留于 `starting`、无 mint；两条持久载荷重放测试均以 `SQLiteConstraintException` 变红。
- GREEN：目标 98 项 normal/recovery/persistence 测试通过；完整 Auto app unit suite 433/433 通过；正常执行与崩溃恢复两条生产路径都以“provider 合法坐标远离旧 Auto 坐标”的正例完成可信 mint；恢复矩阵继续拒绝持久化后的空值、非有限值和越界 provider 坐标。
- 构建：Auto `assembleDebug` 与 `assembleRelease` 通过；debug-only collector purity、零 lint 债务与 repo signer 校验通过。
- 静态边界：Auto main/test 源码已无 `targetLat`、`targetLng`、caller tolerance、haversine 或旧冻结距离常量。
- 全仓：最终 clean HEAD 的 `verify-a-plus --stage full` 11/11 通过，包括 provenance、双 App 单测/构建、contract、acceptance、边界与 release-debt。
- 模拟器：审阅修复后的 exact debug APK 已在 API 35 ARM64 隔离模拟器完成设备端字节校验与冷启动。Auto SHA-256 为 `f8ae9d598e62100292bb75c4f9ac0d75cce91950b6b3bdc75e619908352895f6`，冷启动 692 ms；千网游 bench 为 `d950131a15745d0a20ea2810a3cf3c4f7d4251ed1a5d3bad08598edaa54142ae`，冷启动 853 ms；两进程存活且 crash buffer 为空。随后仅更新本证据文字，不改变 APK 输入。已连接的 Moto 真机未被触碰。模拟器未配置无障碍、LSPosed 与 System Mock，因此该 smoke 不替代真机 G2。

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
status: fixed_pending_review
github_issue: 64
---

# KB-8 Auto coordinate trust drift

## 诊断胶囊

| 栏位 | 结论 |
|---|---|
| 现象 | G2 的 10 地址流程即使收到完整、provider 侧独立验证的 observation，Auto 仍可能把完成判为 `FAIL`，无法写入可信配额。PR #62 使用超出合法范围的占位坐标后，该失败成为确定性的。 |
| 证据 | canonical spec §2.2、§6.4、§6.4.1 将目标坐标与距离比较独占给千网游；`CompletionTrustContext` 仍携带 Auto-local target；`TrustPolicy` 仍执行 haversine；`AutomationEngine` 正常与恢复路径都从 `LocationTask` / `TestAttempt` 注入目标坐标。 |
| 问题假设或根因 | **已确认根因**：PR #24 / KB-8 从 wire 与 canonical digest 移除了坐标，但其后落地的 Auto trust policy 仍按被废弃的双 owner 模型实现；contract/spec 迁移没有传播到内部 trust context、policy、engine wiring 与测试。 |
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

### 4. 修复方案

- 新增失败回归测试，冻结“Auto 不做距离比较”的行为。
- 从 `CompletionTrustContext` 删除 target 与 caller tolerance。
- 从 `TrustPolicy` 删除 haversine 与 Auto-local target gate，只保留 provider effective coordinate 的 non-null / finite / range 校验。
- 删除 normal/recovery engine 的旧坐标注入，更新测试与注释。

不改 provider 侧验证、不放宽其他 §6.4 谓词，也不修改 wire contract。

### 5. 验证方式

- 目标 RED→GREEN 测试及现有逐字段 trust negative matrix。
- CellRebel Auto `testDebugUnitTest`、lint、debug/release assemble。
- 仓库 `verify-a-plus --stage full` 与相关静态 guards。
- Android 模拟器安装/启动 smoke；真机 G2 仍需独立 device lease 与 exact-build 证据。

## 验证结果

- RED：新增 KB-8 回归在旧实现上是 52 项中的唯一失败，失败点为旧 Auto-local 距离判定。
- GREEN：目标 trust suite 通过；完整 Auto app unit suite 427/427 通过；崩溃恢复矩阵继续拒绝空值、非有限值和越界 provider 坐标。
- 构建：Auto `assembleDebug` 与 `assembleRelease` 通过；debug-only collector purity、零 lint 债务与 repo signer 校验通过。
- 静态边界：Auto main/test 源码已无 `targetLat`、`targetLng`、caller tolerance、haversine 或旧冻结距离常量。
- 全仓：`verify-a-plus --stage full` 共 11 道门，10 道通过；唯一失败为 provenance 对未提交工作树的预期拒绝，提交后需复跑。
- 模拟器：当前工作树的 exact debug APK 已在 API 35 ARM64 模拟器完成设备端字节校验与冷启动。Auto SHA-256 为 `32dcde341a8a6240f0af1636c33061f662f997f140e9b42647fd7143cdc964db`，冷启动 653 ms；千网游 bench 为 `d950131a15745d0a20ea2810a3cf3c4f7d4251ed1a5d3bad08598edaa54142ae`，冷启动 864 ms；两进程存活且 crash buffer 为空。模拟器未配置无障碍、LSPosed 与 System Mock，因此该 smoke 不替代真机 G2。

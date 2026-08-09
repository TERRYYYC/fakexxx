---
feature_ids: []
topics:
  - cellrebel
  - qianwangyou
  - android-automation
  - trusted-results
  - binder-contract
  - crash-recovery
doc_kind: feature_spec
created: 2026-08-09
status: implementation-baseline
source_threads:
  - thread_msi197bgeystto7f
  - thread_mslrf4eshkwf1nvu
---

# CellRebel 自动测试 × 千网游 A+ 实施计划

**Feature:** CellRebel 自动测试 × 千网游 Hook/System Mock A+

**Goal:** operator 导入地址与可信测试配额后，一键启动可恢复的无人值守批处理；系统只把能够独立复核、且前后环境连续性成立的 CellRebel 完成计入可信配额。

**Acceptance Criteria:** AC-01..AC-12，见「验收标准与追踪」；每项都有对应不变量、测试和证据。

**Architecture cell:** `fakexxx::android-dual-app-contract`（本仓的新 ownership cell，Phase 1 写入 `docs/architecture/ownership/README.md`）

**Map delta:** new cell required

**Map delta why:** 当前仓库只有 README；本功能首次建立 Auto、千网游、版本化设备内契约和验收面四个所有权边界。

**Architecture:** 双 App 保持独立包名、独立构建和独立发布。Auto 只负责计划、CellRebel 执行、可信计数、日志和恢复；千网游是 Hook、System Mock、profile、schedule 及有效环境证据的唯一能力权威。两者只通过设备内、鉴权、版本化的窄 Binder/AIDL 契约协作。

**Tech Stack:** Kotlin/JVM 17、Android 24+/26+、AIDL/Binder、Room、DataStore、Jetpack Compose、JUnit4、Robolectric、Android instrumentation tests。

**前端验证:** Yes — Auto 的计划/运行/恢复/历史旅程和千网游的配对/授权面都必须用真机截图或录屏验收；单元测试不能替代。

---

## 0. 文档地位与冻结结论

本文是 `TERRYYYC/fakexxx` 的实施与演进单一真相源。GitHub Epic、子 issue、开发 Thread 和 PR 必须链接本文；出现冲突时先修本文或明确记录 operator 的新决策，不能让 issue 正文悄悄改架构。

已冻结结论：

- 当前实施基线是 **A+**。
- Opus5 与 Deep 的排序是 `A > B > C`；Sol 的排序是 `B > A > C`。分歧保留，不把 A+ 包装成全员一致的 UX 结论。
- A+ 是可信优先的一键批处理；首版只提供合法模板与常用执行参数，不建设通用工作流引擎。
- B 是共享同一内核的受控高级配置演进线，不是另起炉灶。
- C 只有在多消费者或平台需求出现后才进入候选；它复用同一执行原语和证据模型，不推倒 A+/B。
- 单纯心跳只能证明进程仍活着，不能证明环境从未发生相关变化，禁止把心跳当连续性证据。

## 1. 事实基线与来源

### 1.1 新仓库

| 项 | 核验结果 |
|---|---|
| 仓库 | `https://github.com/TERRYYYC/fakexxx` |
| 默认分支 | `main` |
| Phase 0 起点 | `c2e0401806b169a329994d99324a94422413d484` |
| 起点内容 | 仅 `README.md`，正文 `# fakexxx` |
| 起点 issues | 0 |

### 1.2 只读复用基线

代码迁移必须从远端精确 SHA 导入，禁止从本机脏 worktree 复制：

| 系统 | 远端真相源 | 精确基线 | 现状摘要 |
|---|---|---|---|
| CellRebel Auto | `TERRYYYC/Faketest` | `main@48d8ec93adb84cdb9c4282c376ec97476648683e` | 已有地址计划、Room、CellRebel 无障碍执行、恢复骨架和单元测试 |
| 千网游 | `TERRYYYC/FakeGps-test` | `master@285e4cae438ab6feea1f70f984f433c7a424b944` | 已有 profile、Hook、System Mock、验证与发布状态机 |

已知不能被新方案掩盖的风险：

- `TERRYYYC/FakeGps-test#14` 仍是开放 P0：正式 System Mock 的可见稳定性尚未取得终局结论。
- `TERRYYYC/FakeGps-test#15` 仍是开放 P0：Hook 在正式工作流中的稳定性验收尚未取得终局结论。
- A+ 的可信发布不得把上述“未验完”改写成“已稳定”；新的 exact-build 验收必须给出独立证据。
- Auto 的 MIUI/HyperOS 跨 App 切换知识、CellRebel `PRE_EXISTING_RUN` 判定和外部执行可能重跑的语义是要保留的资产，不因迁仓而丢失。

### 1.3 迁仓后的真相源规则

Phase 1 导入完成后：

- `fakexxx` 成为本集成方案的代码、契约、测试、issue 与发布证据真相源。
- 原两个仓库只作为历史和上游 provenance；禁止在三个仓库同时维护同一功能。
- 若原仓后续产生必须吸收的修复，只能通过带来源 SHA 的显式同步 PR 进入 `fakexxx`。

## 2. 终态与直线路径

### 2.1 Finish line

operator 在 Auto 中导入地址清单、选择千网游已有 profile/schedule、设置每地址可信次数和少量常用参数，点击一次开始；Auto 逐地址运行 CellRebel，崩溃后可恢复，只在环境证据完整时计数，最终输出可追溯的可信结果与独立的未验证结果。

### 2.2 首版 A+ 范围

- CSV 地址清单：经度、纬度、优先级、每地址可信次数。
- 一个冻结的合法运行模板 `TRUSTED_SYSTEM_MOCK_BATCH_V1`。
- 常用参数：地址间等待、尝试超时、可恢复错误重试上限、暂停/继续、跳过当前地址。
- 从千网游发现可用 profile、schedule、模式、契约版本与连续性能力。
- 每个 CellRebel 执行前后都调用 `observe`。
- 可信计数只接受 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED`。
- 运行中断、进程死亡和重复外部执行的幂等恢复。
- 本地持久日志、配置快照、证据链与导出。
- 双 App 配对/允许名单、兼容性握手与 fail-closed 错误反馈。

### 2.3 非目标

- 不建设任意节点、任意连线、脚本插件式的通用工作流引擎。
- 不开放公网、局域网或 loopback REST，不建设大 SDK。
- 不由 Auto 写千网游数据库、SharedPreferences、DataStore 或配置文件。
- 不以无障碍/UI 自动化控制千网游作为终态接口。
- 不复制千网游的 Hook、System Mock、profile 或 schedule 判定逻辑。
- 不把 Hook 结果混入可信配额；不通过名字、布尔开关或枚举 ordinal 暗中降级。
- 不承诺多设备、云调度、Play Store、第三方消费者或任意流程编排。
- 不在本 feature 中关闭原仓 #14/#15；只能用新 exact-build 证据另行判定。

## 3. A+、B、C 的完整关系

| 方案 | 用户体验 | 新增能力 | 仍复用的共享内核 | 当前地位 |
|---|---|---|---|---|
| A+ | 一键选择合法模板 + 常用参数；默认只看可信完成数 | 固定可信模板、窄契约、连续性证明、幂等恢复、证据日志 | 全部基础对象、契约 v1、执行原语、日志、恢复、配额账本 | 当前实施基线 |
| B | 简单默认仍在；另有受控高级页和明确的计划预览 | 经验证的参数组、计划版本边界、可解释跳过/暂停策略；如产品需要，可增加严格隔离的 Hook 未验证 lane | A+ 的契约、状态对象、步骤原语、日志和配额账本原样复用 | 达到 A+→B 门后升级 |
| C | 多消费者/多产品共享一个编排控制面 | 版本化扩展点、消费者治理、跨设备/远端控制、受约束流程图 | A+/B 的 typed steps、attempt/lease/evidence/ledger、契约兼容与审计事件 | 仅达到 B→C 门后成为候选 |

### 3.1 非重写关系

```text
A+：一个 sealed RunTemplate
      └── 调用固定 typed steps
           discover → preflight → apply → observe(pre)
           → CellRebel → observe(post) → decide → count → release

B：多个经过验证的 RunTemplateProfile
      └── 仍调用同一组 typed steps、同一状态机、同一证据账本

C：受治理的 orchestration control plane
      └── 编排同一组 typed steps；不替换千网游契约和可信计数语义
```

A+ 不先造 DAG 引擎；它只把最终会保留的步骤边界、输入、输出和不变量写清。B 通过增加受控配置扩展；C 通过编排既有原语扩展。

### 3.2 A+ → B 产品触发门

满足任一硬触发后创建一次产品 Gate 评审，不自动开工：

1. 连续两周内至少 3 个真实计划需要同一个当前模板无法表达的参数，并且每个参数都有可复现使用场景；
2. 已完成计划中超过 20% 因固定策略需要人工暂停/重启，且日志能把问题归因到同一可配置策略；
3. operator 明确需要保留 Hook lane，并接受“未验证结果类型 + 独立配额 + 不进入可信完成”的产品语义；
4. 千网游出现至少 2 个并存、合法且必须由 operator 选择的 schedule/profile 策略，简单默认已无法避免误跑。

Gate 输出只能是 `stay-a-plus`、`promote-specific-controls-to-b` 或 `reject-trigger-as-non-product`，并在 Epic 的 evolution issue 留证据。

### 3.3 B → C 产品触发门

同时满足前两项，并至少满足后三项之一，才允许把 C 立为候选：

1. 已有至少 2 个独立消费者需要同一套千网游能力，而不是一个 App 的两个页面；
2. B 已稳定运行并有证据证明其状态机、配额和恢复语义可复用；
3. 至少 3 个真实流程的步骤顺序无法由 B 的受控模板表达；
4. 出现跨设备或远端集中调度的明确产品需求；
5. 出现受治理的第三方扩展需求，且其价值足以承担新鉴权、兼容与运营成本。

未达到门时，C 不是“以后再说”的模糊 backlog，而是明确的 `not-candidate` 状态；每次 A+/B 里程碑只核对触发事实，不做平台预研。

## 4. 用户旅程

### 4.1 首次配对

1. operator 在千网游的“自动测试协作”页看到 Auto 包名、签名摘要和契约版本。
2. operator 明确允许该调用方；千网游持久化 `(packageName, signerSha256, approvedAt)`。
3. Auto 调用 `discover`，显示千网游版本、支持模式、profile/schedule、连续性覆盖等级。
4. 未配对、签名变化、协议不兼容或千网游不可用时，Auto 停在预检页并给出可操作错误；不开始 CellRebel。

### 4.2 创建计划

1. 导入 CSV；所有行原子校验，错误精确到行号。
2. 选择 `TRUSTED_SYSTEM_MOCK_BATCH_V1`。
3. 选择千网游提供的 profile 与 schedule 引用；Auto 不复制它们的内部字段。
4. 设置每地址可信次数和常用参数。
5. 预览将执行的地址顺序、总可信次数、预计可用时间窗和停止条件。
6. 点击开始后冻结 `PlanSnapshot`；改变“什么算成功”的设置必须生成新 plan version，不能改正在运行的账。

### 4.3 无人值守执行

1. Auto 为地址创建稳定 `attemptId` 和幂等键。
2. 调用千网游 `preflight`、`apply`，取得 lease 与证据起点。
3. 紧邻 CellRebel 启动前调用 `observe(pre)`。
4. 识别 CellRebel 是新执行还是 `PRE_EXISTING_RUN`，记录外部执行实例。
5. 完成后调用 `observe(post)`。
6. 只有前后环境、连续性、模式、CellRebel 完成证据全部成立时，事务性插入一次可信配额。
7. 调用 `release`；完成指定次数后进入下一地址。
8. 失败按 typed policy 重试、暂停、跳过或 fail-closed；不把未知结果猜成成功。

### 4.4 崩溃后恢复

1. Auto 启动时扫描非终态 attempt，不直接重跑外部动作。
2. 用同一幂等键重取/重放千网游 receipt，并先 `observe` 当前 lease 与环境。
3. CellRebel 外部执行可能重跑；所有 execution 都记录，但同一可信 attempt 最多计数一次。
4. 不能证明完成时标为未验证或失败；不计数。
5. release 状态不明时优先重放幂等 release；若千网游无法证明清理完成，暂停并提示人工恢复。

### 4.5 异常处理

operator 在运行页直接看到：当前地址、可信完成数、未验证数、当前阶段、最近证据、暂停原因和下一步。深层日志在历史详情；统计面不能替代现场提示。

## 5. 职责、API 与日志边界

| 边界 | Auto | 千网游 | 禁止 |
|---|---|---|---|
| 地址/配额 | 拥有计划顺序、每地址可信配额 | 不拥有 | 千网游替 Auto 计 CellRebel 次数 |
| profile/schedule | 仅保存稳定引用和计划快照 | 唯一权威，解析当前有效策略 | Auto 复制或解释内部规则 |
| Hook/System Mock | 请求意图、消费证据 | 唯一实现与模式权威 | Auto 启停 provider、写 prefs、UI 驱动千网游 |
| CellRebel | 唯一执行与完成判定方 | 不操作 | 千网游推断 CellRebel 完成 |
| 连续性 | 前后消费并验证 | 产生“相关变化必变”的 revision 与覆盖声明 | 用心跳代替连续性 |
| 可信计数 | 事务性唯一账本 | 提供验证证据，不直接加配额 | Hook/未知证据进入可信账 |
| 恢复 | attempt/execution/ledger owner | operation receipt/lease/revision owner | 一方直接改另一方状态 |
| 日志 | 计划、CellRebel、判定、恢复 | 调用方、环境操作、观察、release | 记录配对密钥或把日志当状态真相 |

## 6. 契约 v1：终态 schema

### 6.1 传输

- 显式组件绑定的 AIDL/Binder service：`name.caiyao.fakegps.integration.v1.EnvironmentControlService`。
- AIDL descriptor 永久绑定 v1；v1 已发布的方法和字段语义不可原地改写。
- v2 若不向后兼容，使用新 package/interface，并由 `discover` 的兼容矩阵显式协商。
- service 可跨 App 导出，但没有网络监听面；每次调用从 `Binder.getCallingUid()` 解析真实调用方，不信任请求自报身份。

```aidl
// contracts/environment-control-v1/src/main/aidl/io/github/terryyyc/fakexxx/contract/v1/IEnvironmentControlV1.aidl
package io.github.terryyyc.fakexxx.contract.v1;

import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1;
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1;
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1;
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1;
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1;

interface IEnvironmentControlV1 {
    CapabilitySnapshotV1 discover();
    PreflightReportV1 preflight(in PreflightRequestV1 request);
    ApplyReceiptV1 apply(in ApplyRequestV1 request);
    EnvironmentObservationV1 observe(in ObserveRequestV1 request);
    ReleaseReceiptV1 release(in ReleaseRequestV1 request);
}
```

### 6.2 不允许 ordinal 比较的枚举

```kotlin
enum class VerificationLevelV1 {
    SYSTEM_MOCK_INDEPENDENTLY_VERIFIED,
    HOOK_UNVERIFIED,
    NONE,
}

enum class ContinuityCoverageV1 { FULL, PARTIAL, NONE }
enum class DeliveryModeV1 { SYSTEM_MOCK, HOOK }
enum class ScheduleDecisionV1 { ALLOWED_NOW, WAIT_UNTIL, DENIED }
```

可信策略必须显式匹配 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED`；禁止使用枚举顺序、`>=` 或“非 NONE 即可信”。

### 6.3 核心 DTO

```kotlin
@Parcelize
data class CapabilitySnapshotV1(
    val protocolVersion: Int = 1,
    val serviceVersion: String,
    val supportedModes: Set<DeliveryModeV1>,
    val supportedVerificationLevels: Set<VerificationLevelV1>,
    val continuityCoverage: ContinuityCoverageV1,
    val environmentRevision: Long,
    val profileRefs: List<String>,
    val scheduleRefs: List<String>,
) : Parcelable

@Parcelize
data class EnvironmentIntentV1(
    val runId: String,
    val attemptId: String,
    val profileRef: String,
    val scheduleRef: String,
    val latitude: Double,
    val longitude: Double,
    val requiredVerification: VerificationLevelV1,
    val notBeforeEpochMs: Long,
    val deadlineEpochMs: Long,
) : Parcelable

@Parcelize
data class ApplyReceiptV1(
    val operationId: String,
    val idempotencyKey: String,
    val leaseId: String,
    val acceptedIntentHash: String,
    val appliedAtEpochMs: Long,
    val environmentRevision: Long,
    val verificationLevel: VerificationLevelV1,
) : Parcelable

@Parcelize
data class EnvironmentObservationV1(
    val leaseId: String,
    val observedAtEpochMs: Long,
    val environmentRevision: Long,
    val environmentFingerprint: String,
    val continuityCoverage: ContinuityCoverageV1,
    val continuitySinceEpochMs: Long?,
    val deliveryMode: DeliveryModeV1?,
    val verificationLevel: VerificationLevelV1,
    val effectiveLatitude: Double?,
    val effectiveLongitude: Double?,
    val isMock: Boolean?,
    val scheduleDecision: ScheduleDecisionV1,
    val evidenceRefs: List<String>,
) : Parcelable
```

所有 request 另含 `idempotencyKey` 或稳定 operation id；所有失败使用 typed error：`NOT_PAIRED`、`CALLER_NOT_ALLOWED`、`INCOMPATIBLE_PROTOCOL`、`CAPABILITY_UNAVAILABLE`、`SCHEDULE_DENIED`、`CONTINUITY_NOT_FULL`、`LEASE_CONFLICT`、`STALE_LEASE`、`ENVIRONMENT_DRIFT`、`RELEASE_INCOMPLETE`、`INTERNAL_FAILURE`。

预期业务失败通过 `ServiceSpecificException` 返回稳定的 `ContractErrorCodeV1.wireCode`；Auto 将 wire code 映射为上述 sealed error。未知 code 只能映射为 `INTERNAL_FAILURE` 并 fail-closed，不能猜成兼容。Binder death/`RemoteException` 属于 transport failure，单独进入 recovery；错误 message 只用于安全诊断，不承担机器判定。

### 6.4 连续性信号契约

`environmentRevision` 是千网游持久化的单调 `Long`。在一个 active lease 内，下列任一相关变化都必须使它增加：

- active profile 或任何会影响有效环境的 profile 字段变化；
- Hook/System Mock 模式、provider/service 代际或有效位置变化；
- schedule 进入/离开有效窗口；
- mock-location AppOp/owner、关键权限、目标包版本或可用性变化；
- 千网游进程恢复后无法证明前代连续性；
- 观察器丢事件、重订阅失败或任何会使“没有变化”无法证明的情况。

最后两类必须增加 revision，并把 coverage 降为 `PARTIAL/NONE`。仅当千网游能够证明 observation window 全程由完整的事件源覆盖时才返回 `FULL`。轮询/心跳不能把 coverage 提升为 `FULL`。

Auto 的可信判定要求：

```text
pre.coverage == FULL
post.coverage == FULL
pre.revision == post.revision
pre.fingerprint == post.fingerprint
pre/post.verificationLevel == SYSTEM_MOCK_INDEPENDENTLY_VERIFIED
pre/post.leaseId == apply.leaseId
CellRebelCompletionEvidence == VERIFIED_NEW_COMPLETION
```

任一不成立：不得写可信配额。

### 6.5 配对与调用授权

- 千网游 UI 明确展示候选 Auto 的包名、版本和 signer SHA-256；operator 点允许后才创建 `PairingRecord`。
- 每次 Binder 调用按 UID 反查 package 与 signing certificate，和 PairingRecord 精确匹配。
- 包名相同但 signer 改变视为新调用方，必须重新配对。
- 调用方不可通过参数伪造 package、signer 或 verificationLevel。
- revoke 立即使新调用失败；active lease 进入 release/recovery，不静默继续。
- 配对记录和用户可见运行日志默认持久化，只有 operator 主动删除。

## 7. 状态对象普查

### 7.1 Auto 持久对象

| 对象 | lifecycle owner | 权威字段 | 派生/禁止 |
|---|---|---|---|
| `PlanDefinition` | PlanRepository | 原始导入、版本、模板、常用参数 | 运行中不可改 |
| `PlanRun` | AutomationEngine | runId、planVersion、状态、开始/结束时间 | “完成百分比”派生，不单存 |
| `LocationTask` | PlanRepository | 顺序、目标配额 | completed 由可信账本计数派生 |
| `Attempt` | AttemptRepository | attemptId、taskId、状态、当前 operation | 不直接存 success boolean |
| `CellRebelExecution` | CellRebelAttemptFlow | executionId、attemptId、判定、证据 | 一个 attempt 可有多个外部 execution |
| `TrustedQuotaEntry` | TrustedQuotaLedger | attemptId、taskId、evidenceDigest | UNIQUE(attemptId)，只插不改 |
| `UnverifiedAttemptRecord` | AttemptRepository | attemptId、reason、evidenceDigest | 与可信账本不同表/类型 |
| `RecoveryCheckpoint` | RecoveryCoordinator | attemptId、lastDurableStage、receipt refs | 终态后删除或纯投影 |
| `AutoAuditEvent` | AuditRepository | seq、correlation ids、event、payload digest | append-only；不是状态 owner |

### 7.2 千网游持久对象

| 对象 | lifecycle owner | 权威字段 | 派生/禁止 |
|---|---|---|---|
| `PairingRecord` | CallerAuthorizer | package、signer、approved/revoked | 请求体不能覆盖 |
| `EnvironmentRevisionState` | ContinuityTracker | monotonic revision、coverage、generation | 心跳不能写 FULL |
| `EnvironmentLease` | EnvironmentLeaseStore | leaseId、caller、intentHash、state | 一个设备上的冲突 lease fail-closed |
| `OperationReceipt` | IdempotencyStore | caller、operation、key、result digest | 同键不同 payload = error |
| `EffectiveEnvironmentObservation` | EnvironmentObserver | observed state、fingerprint、evidence refs | UI 状态不可替代 |
| `ScheduleEvaluation` | QWY Schedule owner | scheduleRef、decision、boundary | Auto 不复制 |
| `QwyAuditEvent` | IntegrationAuditStore | seq、caller、lease、event、digest | append-only；不含密钥 |

### 7.3 纯派生状态

- `LocationTask.completed` = `count(TrustedQuotaEntry where taskId=...) >= requiredSuccesses`。
- `PlanRun.completed` = 全部 location task 完成且没有 active/recovery-required attempt。
- `trusted/unverified` 由证据策略函数计算；数据库不允许第三种写路径手填。
- UI 文案、进度百分比、下一地址都从上述权威对象投影。

## 8. 状态 × 事件表

### 8.1 Attempt 主状态机

| 当前状态 | 事件 | 下一状态 | 原子写入/外部动作 | 禁止旁路 |
|---|---|---|---|---|
| `CREATED` | `BEGIN_APPLY` | `APPLY_PENDING` | 先写 attempt + idempotency key | 先调千网游再落库 |
| `APPLY_PENDING` | `APPLY_RECEIPT` | `ENV_APPLIED` | 保存 immutable receipt | 改写 receipt |
| `APPLY_PENDING` | `CRASH_RECOVER` | `APPLY_PENDING` | 同键重放 apply/取旧 receipt | 换键重复 apply |
| `ENV_APPLIED` | `PRE_OBSERVATION_OK` | `PRE_OBSERVED` | 保存 observation digest | 没 observe 就启动 CellRebel |
| `ENV_APPLIED` | `OBSERVATION_UNTRUSTED` | `RELEASE_PENDING` | 记录 typed reason | 继续可信运行 |
| `PRE_OBSERVED` | `START_CELLREBEL` | `CELLREBEL_START_PENDING` | 先写 executionId | 先点击再写 execution |
| `CELLREBEL_START_PENDING` | `NEW_RUN_OBSERVED` | `CELLREBEL_RUNNING` | 写新运行证据 | 假定点击即开始 |
| `CELLREBEL_START_PENDING` | `PRE_EXISTING_RUN` | `CELLREBEL_RUNNING` | 分类并记录，不计旧结果 | 把旧完成当新完成 |
| `CELLREBEL_RUNNING` | `COMPLETION_OBSERVED` | `POST_OBSERVE_PENDING` | 保存 CellRebel 证据 | 先加配额 |
| `CELLREBEL_RUNNING` | `TIMEOUT/INTERRUPTED` | `RECOVERY_REQUIRED` | 保存 typed outcome | 猜成功 |
| `POST_OBSERVE_PENDING` | `POST_OBSERVATION_OK` | `DECIDING` | 保存 observation digest | 单看 UI 成功 |
| `DECIDING` | `TRUST_POLICY_PASS` | `QUOTA_COMMITTED` | 单事务插入 UNIQUE ledger + close decision | 单独递增计数列 |
| `DECIDING` | `TRUST_POLICY_FAIL` | `UNVERIFIED_RECORDED` | 写独立未验证记录 | 写可信 ledger |
| `QUOTA_COMMITTED` | `BEGIN_RELEASE` | `RELEASE_PENDING` | 保存 release key | 忘记清理环境 |
| `UNVERIFIED_RECORDED` | `BEGIN_RELEASE` | `RELEASE_PENDING` | 保存 release key | 自动升级为可信 |
| `RECOVERY_REQUIRED` | `RECONCILE` | 合法中间态或 `RELEASE_PENDING` | 先 observe/取 receipt | 无证据跳状态 |
| `RELEASE_PENDING` | `RELEASE_RECEIPT` | `CLOSED` | 保存 release receipt | release 别人的 lease |
| `RELEASE_PENDING` | `RELEASE_INCOMPLETE` | `RECOVERY_REQUIRED` | 暂停 plan、现场提示 | 继续下一地址 |
| `CLOSED` | 任意重复事件 | `CLOSED` | no-op + audit | 复活 attempt |

### 8.2 PlanRun 状态机

| 当前状态 | 事件 | 下一状态 | 规则 |
|---|---|---|---|
| `DRAFT` | 全量校验通过 | `READY` | 冻结 plan version |
| `READY` | 开始 | `RUNNING` | 单设备只允许一个 active run |
| `RUNNING` | operator 暂停 | `PAUSING` | 当前外部动作先收敛到安全点 |
| `PAUSING` | lease 已 release/已明确 recovery | `PAUSED` | 不留未知环境继续休眠 |
| `PAUSED` | 恢复预检通过 | `RUNNING` | 重新 discover/preflight |
| `RUNNING` | 进程恢复发现非终态 attempt | `RECOVERING` | 优先 reconcile，不取下一任务 |
| `RECOVERING` | reconcile 收敛 | `RUNNING/PAUSED` | 证据不足走 PAUSED |
| `RUNNING` | 全部 task 达标且无未收敛 lease | `COMPLETED` | completion 纯投影验证 |
| 非终态 | 永久不兼容/安全失败 | `STOPPED` | 明确原因和人工动作 |
| `COMPLETED/STOPPED` | 任意继续事件 | 原状态 | 终态不可复活；新建 run |

## 9. 安全与一致性不变量

| INV | 不变量 | 可测证据 |
|---|---|---|
| INV-01 | 千网游是 Hook/System Mock/profile/schedule 唯一权威 | Auto 源码禁用模式/千网游 prefs 依赖扫描 |
| INV-02 | Auto 只通过已鉴权 v1 契约调用千网游 | Binder 未配对/伪 signer 测试 |
| INV-03 | 协议或能力不兼容必须 fail-closed | compatibility matrix tests |
| INV-04 | `verificationLevel` 从 v1 起必填，不能靠默认值补 | parcel/schema round-trip + missing field test |
| INV-05 | 可信配额只接受 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` | table-driven TrustPolicy tests |
| INV-06 | Hook/partial/none 永不进入可信账本 | negative ledger tests |
| INV-07 | 每次 CellRebel 外部执行前后各有一份绑定同 lease 的 observation | attempt transition tests |
| INV-08 | 相关环境变化必改 revision；coverage 非 FULL 时不可信 | continuity tracker event matrix |
| INV-09 | 心跳、进程存活或时间戳不能替代 INV-08 | forbidden-evidence tests/static guard |
| INV-10 | 同一 attempt 的可信配额最多增加一次 | Room UNIQUE + concurrent insert test |
| INV-11 | 未证明 CellRebel 新完成永不计数 | PRE_EXISTING_RUN/timeout/crash tests |
| INV-12 | 外部 CellRebel execution 可重跑且全部留痕 | multi-execution recovery test |
| INV-13 | apply/release 同键同 payload 幂等；同键异 payload 拒绝 | service concurrency tests |
| INV-14 | release 只能清理本 caller、本 lease 获取的环境，不破坏 pre-existing state | stale/foreign lease tests |
| INV-15 | 非终态崩溃恢复先 reconcile，禁止直接推进下一地址 | process-death matrix |
| INV-16 | 一个设备同一时刻只有一个 active PlanRun/冲突 EnvironmentLease | two-run/two-caller race tests |
| INV-17 | 改变成功语义的参数只在新 plan version 或地址边界生效 | config boundary tests |
| INV-18 | 日志 append-only、带 correlation ids，不记录配对秘密 | audit schema/redaction tests |
| INV-19 | 双 App 可独立 build/release；兼容由握手决定 | two-build CI + skew matrix |
| INV-20 | Auto 不写千网游存储，不以 UI 自动化调用千网游 | forbidden-dependency/static tests |
| INV-21 | release 无法证明完成时暂停并暴露人工恢复，不静默继续 | release failure recovery test |
| INV-22 | 终态 attempt/run 不可被 generic restore/list/delete 旁路复活 | DAO/repository bypass tests |

## 10. 崩溃、并发、恢复与旁路误用矩阵

| 类别 | 场景 | 预期终态 | 覆盖 INV |
|---|---|---|---|
| crash | `APPLY_PENDING` 写入后、Binder 调用前崩溃 | 同键 apply，最多一个 lease | 13,15 |
| crash | 千网游已 apply、Auto 未保存 receipt 崩溃 | 同键返回原 receipt | 13,15 |
| crash | pre-observe 后、CellRebel 点击前崩溃 | 恢复后重新预检；不计数 | 7,11,15 |
| crash | CellRebel 点击后、running 证据前崩溃 | 分类现状；未知不计，可记录新 execution 重跑 | 11,12,15 |
| crash | CellRebel 完成后、post-observe 前崩溃 | 恢复后 post-observe；连续性不可证则未验证 | 7,8,11 |
| crash | trust pass 后、ledger transaction 前崩溃 | 重算并唯一插入一次 | 5,10 |
| crash | ledger commit 后、状态更新前崩溃 | ledger 为真相，恢复不重复计数 | 10,15 |
| crash | release 调用后、receipt 保存前崩溃 | 同键重放 release | 13,14,21 |
| crash | 千网游重启丢失连续性观察窗口 | revision 增加、coverage 降级、可信失败 | 8,9 |
| concurrency | 两个 Start 同时触发 | 只创建一个 active PlanRun | 16 |
| concurrency | 同 attempt 两协程同时插 ledger | 一次成功、一次幂等 no-op/conflict | 10 |
| concurrency | 两 caller 请求冲突环境 lease | 第二方 typed `LEASE_CONFLICT` | 14,16 |
| concurrency | apply 同键异 payload | typed conflict，不执行第二次 | 13 |
| recovery | `PRE_EXISTING_RUN` 后出现旧结果页 | 记录旧运行，不计新完成 | 11,12 |
| recovery | schedule 在 CellRebel 运行中跨边界 | revision 变化；未验证、release、暂停/等下窗 | 8,17 |
| recovery | mock-location owner 被外部 App 抢走再改回 | revision 必须变化；不能因 post 状态相同而可信 | 8 |
| recovery | qwy release 只能部分清理 | plan 暂停，显示人工恢复 | 14,21 |
| bypass | Auto 直接写 qwy prefs/DB | 静态 guard/依赖测试失败 | 1,20 |
| bypass | Auto 用 Accessibility 操作千网游 | package target guard 测试失败 | 1,20 |
| bypass | 调用方在请求中伪造 signer/package | 仍按 Binder UID 拒绝 | 2 |
| bypass | Hook 返回 `isMock=true` 试图进可信账 | TrustPolicy 拒绝 | 5,6 |
| bypass | coverage PARTIAL 但心跳持续 | TrustPolicy 拒绝 | 8,9 |
| bypass | generic DAO 把 CLOSED 改回 RUNNING | repository/DB constraint 拒绝 | 22 |
| bypass | 删除 attempt 后让 location 看似未完成再重跑 | ledger FK/不可删策略保留可信事实 | 10,22 |
| release | foreign/stale leaseId | 不清理环境，typed error | 14 |
| version | 新 Auto + 旧 qwy / 旧 Auto + 新 qwy | 兼容则运行，不兼容则预检停止 | 3,19 |

## 11. 日志与证据契约

### 11.1 共同 correlation keys

`planId`、`planVersion`、`runId`、`locationTaskId`、`attemptId`、`cellRebelExecutionId`、`operationId`、`leaseId`、`idempotencyKeyHash`。

### 11.2 Auto 事件最小字段

```text
seq, recordedAt, runId, taskId, attemptId, executionId,
stage, eventType, outcomeType, typedReason,
planSnapshotHash, qwyServiceVersion, protocolVersion,
preObservationDigest, postObservationDigest,
cellRebelEvidenceDigest, trustedDecision, recoveryDecision
```

### 11.3 千网游事件最小字段

```text
seq, recordedAt, callerPackage, callerSignerDigestPrefix,
operationId, operationType, leaseId, intentHash,
environmentRevision, continuityCoverage, deliveryMode,
verificationLevel, scheduleDecision, outcomeType, typedReason
```

### 11.4 隐私与持久化

- 日志默认设备内持久化，TTL=0；operator 主动删除时才清理。
- 地址与 CellRebel 测试结果是本产品的显式业务数据，可进入本地导出。
- 配对令牌、完整签名材料、凭据、千网游内部 prefs、其他 App 私有数据不得进入日志。
- 导出必须包含 schema/version 和 evidence digest，不能只导出“成功/失败”摘要。

## 12. 目标仓目录与文件所有权

```text
fakexxx/
├── feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md
├── docs/
│   ├── architecture/ownership/README.md
│   ├── acceptance/a-plus-device-matrix.md
│   └── provenance/upstream-imports.md
├── contracts/environment-control-v1/
│   ├── README.md
│   ├── compatibility.yaml
│   ├── build.gradle.kts
│   ├── consumer-rules.pro
│   └── src/main/
│       ├── aidl/io/github/terryyyc/fakexxx/contract/v1/
│       │   ├── IEnvironmentControlV1.aidl
│       │   ├── CapabilitySnapshotV1.aidl
│       │   ├── PreflightRequestV1.aidl
│       │   ├── PreflightReportV1.aidl
│       │   ├── ApplyRequestV1.aidl
│       │   ├── ApplyReceiptV1.aidl
│       │   ├── ObserveRequestV1.aidl
│       │   ├── EnvironmentObservationV1.aidl
│       │   ├── ReleaseRequestV1.aidl
│       │   └── ReleaseReceiptV1.aidl
│       └── java/io/github/terryyyc/fakexxx/contract/v1/
│           ├── CapabilitySnapshotV1.kt
│           ├── PreflightRequestV1.kt
│           ├── PreflightReportV1.kt
│           ├── ApplyRequestV1.kt
│           ├── ApplyReceiptV1.kt
│           ├── ObserveRequestV1.kt
│           ├── EnvironmentObservationV1.kt
│           ├── ReleaseRequestV1.kt
│           ├── ReleaseReceiptV1.kt
│           ├── ContractEnumsV1.kt
│           └── ContractErrorCodeV1.kt
├── apps/
│   ├── cellrebel-auto/                 # subtree from Faketest@48d8ec9
│   │   └── app/src/{main,test,androidTest}/...
│   └── qianwangyou/                    # subtree from FakeGps-test@285e4ca
│       └── app/src/{main,test,androidTest}/...
├── acceptance/
│   ├── fixtures/
│   ├── fake-qwy/src/...
│   └── scenarios/src/test/...
├── scripts/
│   ├── check-contract-v1.sh
│   ├── check-forbidden-boundaries.sh
│   └── verify-a-plus.sh
└── .github/workflows/android-a-plus.yml
```

### 12.1 Owner matrix

| Owner | 独占写入范围 | 可读依赖 | 禁止并行触碰 |
|---|---|---|---|
| Opus5 | `contracts/**`、`apps/cellrebel-auto/**`、root CI/scripts、ownership map；仅在串行 PR-2 修改两 App 的 Gradle contract 接线 | 全仓 | PR-3 开始后不触碰 `apps/qianwangyou/**` |
| Kimi | `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/**`、对应 qwy tests、qwy Manifest/Gradle 的集成行 | frozen contract | contract、Auto、acceptance |
| Sol | `acceptance/**`、`docs/acceptance/**`、验收 issue 与证据 | contract 与两 App | Opus5/Kimi 产品实现 |
| GLM | review verdict、对抗执行报告；若补测试代码则单独 PR | 全仓 | 不修改正在审的作者 branch |

并行成立条件：Contract PR exact HEAD 冻结后，Opus5 的 Auto consumer、Kimi 的 qwy provider、Sol 的 fake provider/scenario acceptance 三个目录无重叠，可并行。任何 contract delta 先停三路、回主 Thread 重新冻结，不允许三方各自兼容。

## 13. 分步 TDD 实施计划

### Task 1 — 导入远端基线与建立 ownership/CI

**Owner:** Opus5

**Files:**

- Create: `docs/provenance/upstream-imports.md`
- Create: `docs/architecture/ownership/README.md`
- Create: `.github/workflows/android-a-plus.yml`
- Create: `scripts/verify-a-plus.sh`
- Import: `apps/cellrebel-auto/**`
- Import: `apps/qianwangyou/**`

**RED:** 在空目标路径运行 provenance checker，必须因两个 app 未导入和 SHA 未登记失败。

**GREEN:** 只从远端精确 SHA subtree 导入；记录源 URL、branch、SHA、导入 commit。不得读取本机脏 worktree 作为拷贝源。

**Verify:**

```bash
test -f apps/cellrebel-auto/gradlew
test -f apps/qianwangyou/gradlew
git -C apps/cellrebel-auto rev-parse --is-inside-work-tree
git -C apps/qianwangyou rev-parse --is-inside-work-tree
```

subtree 目录本身处在 `fakexxx` 工作树，后两条预期均输出 `true`；provenance 文档必须精确包含两个上游 SHA。

### Task 2 — 冻结 contract v1 与兼容矩阵

**Owner:** Opus5

**Reviewer:** Sol（语义）+ GLM（对抗）

**Files:**

- Create: `contracts/environment-control-v1/src/main/aidl/io/github/terryyyc/fakexxx/contract/v1/IEnvironmentControlV1.aidl`
- Create: 同目录 `CapabilitySnapshotV1.aidl`、`PreflightRequestV1.aidl`、`PreflightReportV1.aidl`、`ApplyRequestV1.aidl`、`ApplyReceiptV1.aidl`、`ObserveRequestV1.aidl`、`EnvironmentObservationV1.aidl`、`ReleaseRequestV1.aidl`、`ReleaseReceiptV1.aidl`
- Create: `contracts/environment-control-v1/src/main/java/io/github/terryyyc/fakexxx/contract/v1/CapabilitySnapshotV1.kt`
- Create: 同目录 `PreflightRequestV1.kt`、`PreflightReportV1.kt`、`ApplyRequestV1.kt`、`ApplyReceiptV1.kt`、`ObserveRequestV1.kt`、`EnvironmentObservationV1.kt`、`ReleaseRequestV1.kt`、`ReleaseReceiptV1.kt`、`ContractEnumsV1.kt`、`ContractErrorCodeV1.kt`
- Create: `contracts/environment-control-v1/build.gradle.kts`
- Create: `contracts/environment-control-v1/consumer-rules.pro`
- Create: `contracts/environment-control-v1/compatibility.yaml`
- Create: `contracts/environment-control-v1/src/test/.../ContractRoundTripTest.kt`
- Modify: `apps/cellrebel-auto/settings.gradle.kts`（只接入 contract library）
- Modify: `apps/qianwangyou/settings.gradle`（只接入 contract library）
- Modify: 两 App 的 app build 文件（只增加 contract dependency）
- Create: `scripts/check-contract-v1.sh`

**RED:** missing `verificationLevel`、枚举 ordinal 信任、v1 字段语义漂移、旧/新版本不兼容矩阵均先写失败测试。

**GREEN:** 实现本文 §6 的 exact schema；v1 不引入泛化 command 或 Map payload。

**Verify:** `./scripts/check-contract-v1.sh`，预期全部 contract/compatibility tests PASS。

**Checkpoint:** exact contract HEAD 回主 Thread；只有该 HEAD 获得独立 verdict 后，Task 3/4/5 才开始并行。

### Task 3 — 千网游 provider、配对与连续性

**Owner:** Kimi

**Reviewer:** GLM

**Files:**

- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/EnvironmentControlService.kt`
- Create: `.../CallerAuthorizer.kt`
- Create: `.../PairingStore.kt`
- Create: `.../EnvironmentLeaseStore.kt`
- Create: `.../IdempotencyStore.kt`
- Create: `.../ContinuityTracker.kt`
- Create: `.../EnvironmentObserver.kt`
- Create: `.../IntegrationAuditStore.kt`
- Create: `.../QwyEnvironmentController.kt`
- Modify: `apps/qianwangyou/app/src/main/AndroidManifest.xml`
- Modify: `apps/qianwangyou/app/build.gradle`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/**`
- Test: `apps/qianwangyou/app/src/androidTest/java/name/caiyao/fakegps/integration/v1/**`

**RED order:** unauthorized caller → idempotency → lease conflict → revision event sources → coverage downgrade → release ownership → process death.

**GREEN:** 适配现有 profile/System Mock/Hook API，不复制其逻辑；无法完整观察时返回 `PARTIAL/NONE`，不伪造 FULL。

**Verify:**

```bash
cd apps/qianwangyou
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug
```

### Task 4 — Auto 数据模型、可信账本与恢复事务

**Owner:** Opus5

**Reviewer:** Sol

**Files:**

- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/environment/EnvironmentControlClient.kt`
- Create: `.../BinderEnvironmentControlClient.kt`
- Create: `.../TrustPolicy.kt`
- Create: `.../EnvironmentEvidence.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/model/plan/Entities.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/AppDatabase.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/TrustedQuotaDao.kt`
- Create: `.../AttemptExecutionDao.kt`
- Create: `.../AuditEventDao.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/recovery/RecoveryCoordinator.kt`
- Test: 对应 `app/src/test/**`

**RED order:** state census schema → UNIQUE ledger → pre-existing execution → crash windows → concurrent insert → closed-state bypass。

**GREEN:** 可信完成只通过 `TrustPolicy` + 单一 ledger transaction；删除旧的直接 `completedSuccesses++` 写路径，完成数改为投影。

**Verify:**

```bash
cd apps/cellrebel-auto
./gradlew testDebugUnitTest
```

### Task 5 — Auto A+ 执行内核

**Owner:** Opus5

**Reviewer:** Sol

**Files:**

- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/aplus/APlusRunTemplate.kt`
- Create: `.../APlusAttemptCoordinator.kt`
- Create: `.../AttemptState.kt`
- Create: `.../AttemptEvent.kt`
- Create: `.../AttemptReducer.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/CellRebelAttemptFlow.kt`
- Test: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/automation/aplus/**`

**RED order:** exact §8 transitions → pre/post observe gate → `PRE_EXISTING_RUN` → repeated execution → schedule boundary → release incomplete。

**GREEN:** 一个 sealed template 调用固定 typed steps；不实现 DAG、脚本或通用插件。

**Verify:**

```bash
cd apps/cellrebel-auto
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug
```

### Task 6 — Auto/千网游用户界面与现场可感知性

**Owner:** Opus5（Auto）/ Kimi（千网游，各自在独占目录）

**Reviewer:** GLM；Sol 走用户旅程验收

**Files:**

- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/PlanScreen.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/ControlScreen.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/HistoryScreen.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/PairingStatusCard.kt`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/ui/AutomationPairingScreen.kt`
- Modify: qwy navigation/settings files only in Kimi branch

**RED:** Compose state tests先覆盖未配对、不兼容、可信、未验证、recovery-required、release-incomplete 六种现场状态。

**GREEN:** 默认页保持一键模板；高级配置不出现；错误给具体恢复动作。

**Verify:** 两 App unit/lint/assemble + 真机旅程截图；不得只附代码截图。

### Task 7 — 独立 fake provider 与对抗场景

**Owner:** Sol

**Reviewer:** GLM

**Files:**

- Create: `acceptance/fake-qwy/src/main/.../FakeEnvironmentControlService.kt`
- Create: `acceptance/scenarios/src/test/.../CrashWindowMatrixTest.kt`
- Create: `.../ConcurrencyMatrixTest.kt`
- Create: `.../ContinuityAndTrustMatrixTest.kt`
- Create: `.../BypassMisuseMatrixTest.kt`
- Create: `docs/acceptance/a-plus-device-matrix.md`
- Create: `scripts/check-forbidden-boundaries.sh`

**RED:** §10 每行至少一个失败场景；故障注入点可停在每个 durable write 与外部调用之间。

**GREEN:** fake provider 能返回重复 receipt、重启/丢 coverage、revision 漂移、stale/foreign lease、binder death；测试只消费公开 v1 contract。

**Verify:** `./scripts/verify-a-plus.sh` 包含 contract + 两 App unit + scenario + boundary guards。

### Task 8 — GLM 独立审查与 exact-HEAD 对抗验证

**Owner:** GLM（非产品代码作者）

1. 先审 Kimi qwy provider：授权、revision 覆盖声明、idempotency、foreign lease、进程死亡。
2. 再审 Sol acceptance：是否存在 fake 只验证实现细节、未覆盖真实状态边、误把心跳当连续性。
3. 对 Opus5 Auto 做可信账本与 `PRE_EXISTING_RUN` 对抗审查。
4. 每个 finding 给 `block/approve`、精确文件/行、复现命令和 exact HEAD。
5. behavioral delta 后旧 verdict 失效，必须重跑受影响矩阵。

### Task 9 — 隔离真机验收与发布候选

**Owner:** Sol（验收）

**Independent reviewer:** GLM

**Merge authority:** operator only

真机动作必须另获设备 lease；使用公开测试坐标，禁止未经授权 uninstall/clear-data/生产 profile 写入。验收至少覆盖：

- 首次配对、撤销、签名变化；
- exact-build 双 App 版本 skew；
- 10 个地址 × 每地址指定可信次数；
- CellRebel pre-existing、新运行、重跑、崩溃恢复；
- System Mock 连续性变化；
- Hook 结果不进入可信配额；
- qwy/Auto 进程死亡与 release 人工恢复；
- 原仓 #14/#15 的相关稳定性风险，不用新接口存在本身代替验收。

## 14. 验证命令

最终 PR 必须在其 exact HEAD 上给出与改动匹配的命令输出：

```bash
./scripts/check-contract-v1.sh

(cd apps/cellrebel-auto && ./gradlew testDebugUnitTest)
(cd apps/cellrebel-auto && ./gradlew lintDebug assembleDebug)

(cd apps/qianwangyou && ./gradlew testDebugUnitTest)
(cd apps/qianwangyou && ./gradlew lintDebug assembleDebug)

./scripts/check-forbidden-boundaries.sh
./scripts/verify-a-plus.sh
```

预期：全部 exit 0；测试报告归档到 PR evidence。设备验收命令不写成无串号的通用 `adb` 脚本，必须在独立 device lease 中绑定 exact serial、APK SHA、安装方式和恢复边界。

## 15. PR 顺序与 merge gates

```text
PR-0 文档（本文，先独立落 main）
  ↓
PR-1 远端基线导入 + provenance + ownership + CI
  ↓
PR-2 contract v1（冻结 exact HEAD）
  ├── PR-3 Kimi：千网游 provider/continuity/security
  ├── PR-4 Opus5：Auto data/trust/recovery/core/UI
  └── PR-5 Sol：fake provider + acceptance/adversarial matrix
          ↓
PR-6 integration + exact-build device evidence（只做必要胶合，不吞并三路职责）
```

每个 PR 的 gate：

1. 独立 worktree/branch；作者与 reviewer 不同。
2. 只改 owner matrix 允许的文件；共享 contract delta 回 contract PR，不在 consumer branch 偷改。
3. 回本实施主 Thread 提交 exact HEAD、changed files、测试命令/结果、review verdict、已知风险。
4. 外部 GitHub review/check 以 PR 当前 HEAD 为真相；HEAD 变化后重验受影响证据。
5. 任一 INV 没有测试或明确 device evidence，不能以“后续补”放行。
6. contract/security/data/recovery 变更必须独立 review；UI 还需用户旅程截图。
7. 所有 PR 只到 `ready for operator decision`；猫猫不得 merge、squash、close 或绕过保护。
8. operator 对每个 PR 单独决定 merge；授权不跨 PR、不默认续存到新 HEAD。

## 16. GitHub Epic / Issue 依赖图

文档提交后创建下列持久对象；实际 issue number 由 GitHub 分配，标题 key 保持稳定：

| Key | 标题 | Depends on | Owner/Reviewer | 终结谓词 |
|---|---|---|---|---|
| EPIC | `[Epic] CellRebel × 千网游 A+ 可信无人值守测试` | 本文 | Sol 主控 | 所有 P0 child 达标且等待 operator merge/close 决定 |
| I1 | `[P0] 导入双 App 精确基线并建立 provenance/CI` | EPIC | Opus5 / Sol | PR-1 exact HEAD 通过 gate |
| I2 | `[P0] 冻结 Environment Control contract v1` | I1 | Opus5 / Sol+GLM | PR-2 exact HEAD + verdict |
| I3 | `[P0] 千网游 provider：配对、lease、连续性与审计` | I2 | Kimi / GLM | PR-3 exact HEAD + INV tests |
| I4 | `[P0] Auto：可信账本、恢复状态机与 A+ 模板` | I2 | Opus5 / Sol | PR-4 exact HEAD + INV tests |
| I5 | `[P0] A+ fake provider、崩溃/并发/旁路矩阵` | I2 | Sol / GLM | PR-5 exact HEAD + §10 全覆盖 |
| I6 | `[P0] 双 App 集成与 exact-build 真机验收` | I3,I4,I5 | Sol / GLM | device matrix + hashes + verdict |
| I7 | `[Product Gate] A+→B→C 触发证据与非重写演进` | EPIC | Sol 主控 | 每个里程碑记录 stay/promote/reject verdict |

Issue body 必须链接本文、列出依赖 issue、owner/reviewer、文件范围、相关 INV、验证命令与“operator only merge”。

## 17. Thread 编排

在 GitHub issue 图冻结后，从实施主 Thread 提议四个子 Thread，均使用 `state-transitions` 回报：

1. **Opus5 核心实现**：I1/I2/I4，独立 worktree。
2. **Sol 验收与检查**：I5/I6，独立 worktree；不写 Opus5 核心实现。
3. **Kimi 千网游独立模块**：I3；只在 contract exact HEAD 冻结后开工，文件所有权不与 Opus5 重叠。
4. **GLM 独立审查/对抗测试**：先审本文与 Sol 的验收设计，后审 Kimi/Opus5 exact HEAD；不替作者自审。

主 Thread 只接收六个状态点：文档提交、issues/任务图完成、子 Thread 建立、核心实现 ready for review、验收完成、等待 merge 决策。

## 18. 验收标准与追踪

| AC | 判据 | 主要 INV/证据 |
|---|---|---|
| AC-01 | 一键执行地址清单并按每地址可信次数推进 | INV-10,16,17；计划集成测试 |
| AC-02 | Auto 不复制/旁路千网游能力 | INV-01,20；boundary guard |
| AC-03 | 私有鉴权版本化 v1 discover/preflight/apply/observe/release 可用 | INV-02,03,04；contract tests |
| AC-04 | 只有独立验证 System Mock 进入可信配额 | INV-05,06；TrustPolicy matrix |
| AC-05 | 每个 CellRebel execution 前后 observe，连续性不成立即不计 | INV-07,08,09；continuity matrix |
| AC-06 | crash/retry 下外部执行可重跑、可信配额最多一次 | INV-10,11,12,13,15；crash matrix |
| AC-07 | `PRE_EXISTING_RUN` 语义保留且旧结果不计新完成 | INV-11,12；fixtures/device evidence |
| AC-08 | 配对、签名 allowlist、lease ownership 与 release fail-closed | INV-02,14,21；security/release tests |
| AC-09 | 运行现场与历史日志可追溯，秘密不落日志 | INV-18；schema/redaction tests + UI |
| AC-10 | 崩溃/并发/恢复/旁路矩阵逐项通过 | INV-01..22；§10 report |
| AC-11 | 双 App 独立构建发布，version skew 明确运行或停止 | INV-03,19；CI + skew device matrix |
| AC-12 | A+/B/C 触发门有持久 issue 与里程碑 verdict，不发生重写 | I7 + milestone evidence |

## 19. 完成定义

A+ 不是在代码齐全时完成，而是在以下条件同时成立时达到 `ready for operator merge decision`：

- AC-01..12 都有非作者可复核证据；
- INV-01..22 全部被自动测试或明确的真机证据覆盖；
- 两 App exact APK SHA、源码 HEAD、签名、设备串号和恢复后状态完整记录；
- Hook 未验证结果与可信 System Mock 结果在类型、存储、UI、导出和配额上全部隔离；
- 原仓 #14/#15 相关风险被诚实披露并取得本候选构建的验收结论；
- Opus5、Kimi、Sol 的作者改动分别有独立 reviewer；GLM 不审自己写的测试改动；
- 所有 candidate PR 均停在未 merge 状态，等待 operator 对每个 PR 决定。

## 20. 当前开放项

没有需要在 Phase 0 反问 operator 的技术 A/B 题。A+ 产品基线、B/C 关系、可信边界和 merge 权限已经冻结。实现中若发现 Android 无法对某类相关变化提供完整连续性事件源，正确处置是 capability 返回 `PARTIAL/NONE` 并停止可信计数，而不是降低本文的不变量。

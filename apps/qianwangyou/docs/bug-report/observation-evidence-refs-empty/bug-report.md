---
feature_ids: []
topics: [g2, provider, observation, evidence, audit]
doc_kind: bug-report
created: 2026-09-04
---

# VERIFIED observation 没有可解析的 QWY 证据引用

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | 真机 attempt 4 的 PRE/POST observation 均为 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` / `FULL`，但 `evidenceRefs=[]`；Auto 按 §6.4.1 fail-closed，结果为 `UNTRUSTED` 且不写 quota。 |
| **2. 证据** | `QwyEnvironmentController.observeEffective()` 把 refs 硬编码为 `emptyList()`；`EnvironmentObserver` 原样透传。测试 fake 默认返回 `qwy:audit:1`，一次 apply 后该 id 实际指向 `apply` 行，形成结构非空但错归属的假绿。真机证据见 issue #78。 |
| **3. 根因** | evidence ref 的所有权放错层：controller 只有 effective-config 读数，没有 lease、operation 或 audit row 身份；真正组装最终 observation 的 `EnvironmentObserver` 却没有 audit store，只能相信下层自带字符串。 |
| **4. 诊断策略** | 从 Auto 首个失败谓词逆向追到 Binder observation、observer、controller 与 fake；再用重建后的 durable audit store 检查 ref 是否能解析到本次 operation，而不是只检查字符串非空。 |
| **5. 超时策略** | 若 observer-owned row 仍不能在重建 store 中解析，停止增加 fallback，转查 `FileDurableKv` transaction/flush 语义与进程边界。 |
| **6. 预警策略** | 禁止用 schedule、revision、last-applied 或既有 apply row 拼 ref：它们不绑定本次 observation，且 last-applied 在 release 时清除。禁止自动 TTL/prune；§11.4 要求默认 TTL=0。 |
| **7. 用户可见交互修正** | 无 UI 变化。Auto 仍只做 ref 的结构性校验；可解析性只在 QWY 侧及其保留期内成立，不宣称 Auto 已独立验证证据内容。 |
| **8. 验收** | 每次成功 observe 先持久化 `observe` audit row，再返回唯一 `qwy:audit:<seq>`；row 绑定 caller/lease/operation 与 observation 全字段摘要；写失败不返回 observation、不留半写。 |

## 报告人

狸花猫在 2026-09-04 G2 真机 smoke 中采集到空 refs；砚砚从 issue #78 的设备证据逆向完成根因定位与修复。

## 复现步骤

1. 配对 Auto，apply 一个 QWY-owned schedule item。
2. 调用 `observe`，让 effective tuple 达到 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED`。
3. 修复前 production controller 返回 `evidenceRefs=[]`；Auto 的首个 trust predicate 因此失败。
4. 修复前 JVM fake 默认返回 `qwy:audit:1`，但 durable audit 中该 seq 是先前的 `apply` 事件，不是本次 observation 的证据。

期望：ref 在 QWY audit store 中解析到本次 observe 的 durable row。实际：production 为空，测试 fake 则提供无正确 backing 的占位符。

## 根因分析

`EffectiveEnvironment` 把 configuration 读数和 observation provenance 混成一个类型。下层 controller 无法知道当前 lease、request operationId 或 audit seq，却被迫生成 evidence refs；上层 observer 拥有完整 tuple 和关联身份，却没有 audit dependency。测试 fake 的默认占位符遮住了 production hardcode，于是单测无法复现真机失败。

## 修复方案

- 从 `EffectiveEnvironment` 删除 `evidenceRefs`，使 controller/fake 无法再伪造 provenance。
- 把同一个 `IntegrationAuditStore` 注入 `EnvironmentObserver`。
- 先组装 bare observation；使用共享 `CanonicalDigestV1` 对除 `evidenceRefs` 外的全部 wire 字段做 domain-separated 摘要，nullable 字段显式编码 presence，Double 用 raw bits。
- append `observe` row，关联 caller/lease/operation；append 返回 seq 后才复制出 `qwy:audit:<seq>` 并跨 Binder 返回。
- 保持 audit 异常向上传播。append 后、Binder reply 前的崩溃最多留下孤立证据行；反向顺序会返回不可解析 ref，因此不可接受。

没有复用 apply/schedule/revision/last-applied row：它们都不绑定本次完整 observation；last-applied 还会在 release 清除，违反保留期内可解析的要求。

## 验证方式

- RED 1：production-shaped empty-ref fake 下，测试在 `ObservationEvidenceRefTest.kt:30` 看到 0 个 ref。
- RED 2：注入 audit `evt:*` 写故障，旧实现仍返回 observation，测试按 fail-closed 要求失败。
- GREEN：返回 ref 可在重建后的 `DurableIntegrationAuditStore` 中按 seq 找到，row 的 caller/lease/operation 与 request 一致，payload digest 可由返回 observation 重算；故障事务不追加 seq/event。
- 相关 apply/release、advance、recovery、reachability 与 audit 测试同时通过。

## 已知非阻塞风险

每次 PRE/POST observe 各新增一次同步 durable append。当前 `FileDurableKv` 会重写并 fsync 整个 map，长期存在写放大和已授权 caller 刷盘风险。不能用自动 TTL 掩盖；后续应独立治理可扩展 audit store、operator delete 与 abuse control，不改变本修复的证据所有权。

---
feature_ids: []
topics:
  - android-automation
  - a-plus
  - provider-principal
  - crash-recovery
  - durable-state
doc_kind: bug_report
created: 2026-08-31
status: implemented_pending_independent_review
github_pr: 63
---

# Durable provider principal is lost at the recovery boundary

## 诊断胶囊

| 栏位 | 结论 |
|---|---|
| 现象 | Auto 在导入/启动 plan 时没有持久化所选 provider applicationId。保留数据库后换到另一 build，恢复 apply/release 会使用新 build 的默认 provider，而不是创建原 operation/lease 的 provider。 |
| 证据 | canonical spec §4.2、§6.1 要求启动快照冻结、一次绑定一个 provider，并把选择写入 `PlanSnapshot`；pre-#65 candidate 只在运行时从 build selector 读当前默认值；`LocationPlan`、`TestAttempt` 与 recovery receipts/checkpoints 均未携带或解析 durable provider identity；`AutomationService.onServiceConnected()` 在读取 plan 前已创建并绑定默认 executor；`RecoveryCoordinator` 始终使用构造时的单一 executor。 |
| 问题假设或根因 | **已确认根因**：provider identity 只存在于 build/process composition，而不属于 durable plan/attempt owner。恢复路径因此无法区分“当前 build target”和“创建这条 operation/lease 的 target”。 |
| 诊断策略 | 先枚举所有 provider I/O 前的 durable owner 解析点，再用 recording executor 证明：build principal 改变后 apply/release 仍命中原 identity；null/unknown/mismatch 在任何 provider 方法前停止。 |
| 超时策略 | 如果 receipt/checkpoint 无法由 attempt 唯一、稳定地反查 principal，停止实现并把 principal 直接加入该 receipt；不得根据当前 build、已安装 sibling 或 trust row 猜测。 |
| 预警策略 | migration fixture 保留 legacy null；selected-only client 禁止 sibling fallback；composition guard 拒绝 executor target 与 trust principal 不一致；service connect 的 bind 计数必须为零。 |
| 用户可见交互修正 | 无布局改动。legacy/unknown plan 显式停止并记录 typed reason；不会尝试另一个已安装 provider。 |
| 验收 | RED→GREEN 覆盖 principal switch 的 apply/release、unknown identity 零 provider 调用、selected-only probe、composition mismatch、service pre-plan 零 bind，以及最终 schema migration 的 legacy-null 保留。 |

## Bug report 五件套

### 1. 报告人

PR #63 exact head `a23f29869bbd98aa1982c792b899a7098fe231a2` 的独立审计发现；本实现基于 product candidate `5002e0e005324c32ca3d36d10510180d1fafbf81` 重新落地，不移植旧 PR 分支。

### 2. 复现步骤

1. 在 provider A 为当前 build target 时导入并启动一个 A+ plan，使 attempt 进入 `APPLY_PENDING` 或带 lease 的 `RELEASE_PENDING`。
2. 保留/恢复 Auto 数据库，换用默认 target 为 provider B 的兼容签名 Auto build。
3. 启动同一 plan 的 crash recovery。
4. 观察恢复 coordinator 所使用的 Binder `ComponentName`。

期望：从 durable owner 恢复 provider A；如果 durable identity 缺失或不认识，停止且 provider 调用数为零。

实际：service 在读 plan 前创建 provider B executor，recovery 复用它重放 apply/release；provider A 的 lease 可能遗留。

### 3. 根因分析

- build-selected `ProviderPrincipal.selected` 是“现在这个 build 默认选谁”，不是历史 operation 的 owner。
- plan snapshot 与 attempt owner 没有 provider applicationId，因此重启后没有可审计的恢复输入。
- operation/release receipt 与 checkpoint 只携带 operation proof；当前 coordinator 也没有从 attempt owner 解析 principal 的入口。
- service-lifecycle executor 先于 plan 读取而绑定，后续 backend/trust/evidence source 全部继承这个错误 target。
- `EnvironmentControlClient` 仍按 bench → production 顺序试 sibling；这会把“选中的 provider 不可达”悄悄改写成“另一个 provider 可达”。
- composition root 没有拒绝 injected Binder target 与 trust gate applicationId 不一致的组合。

安全影响是 fail-closed 可用性/资源清理缺口：错误 provider 通常拒绝未知 lease，但原 provider lease 无法由快照定位并释放。当前证据不支持静默授权或跨-principal成功重放。

### 4. 修复方案

- 在 canonical durable owner 上冻结 provider applicationId；新 attempt 继承 plan identity，恢复只接受已知且与 owner/执行器一致的 identity。
- legacy/null/unknown/mismatch 写入 typed recovery failure，并在任何 discover/preflight/apply/observe/advance/release 前停止。
- service 先读 plan、验证 identity，再按该 identity 构造/绑定 per-run executor；run 结束或 service 销毁时解除绑定。
- trust gate、evidence/status projection、probe/Binder component 只消费同一个冻结 identity。
- client 只连接 selected provider，不试 sibling。
- composition root 对 unknown target 以及 executor target/trust principal mismatch fail fast。
- operation receipt、recovery checkpoint 与 release receipt 都复制同一个 principal，并在每次读取/插入/重放前与 plan/attempt 做逐值一致性校验；任一缺失、未知或不等都进入人工恢复，禁止 RPC、mint 或 advance。

### 5. 验证方式

- Targeted RED→GREEN tests：routing、recovery apply/release、unknown/mismatch、migration。
- Auto full `testDebugUnitTest`。
- Auto debug/release compile/assemble。
- inherited lint debt ratchet、相关静态 guard、`git diff --check`。
- 风险允许时跑 repo `verify-a-plus --stage full`；不执行任何 adb/device/emulator/APK install 命令。

## Fresh-context findings disposition (FC1–FC6)

| Finding | Disposition | Regression evidence |
|---|---|---|
| FC1: `bindService=true` was treated as ready before `onServiceConnected` | Fixed. A registry acquisition suspends for the exact component callback (bounded timeout); disconnect/death/null/unbind reset readiness, and stale callbacks remain fenced. | `ProviderBindLifecycleTest`: request-accepted/remote-null window, exact async callback, refcount close/unbind, wrong/stale callback rejection. |
| FC2: lease-bearing recovery did not join the operation receipt P | Fixed. Attempt-wide preflight requires the canonical apply receipt for every lease/later phase and checks P + lease before evidence, release, mint or advance. | Release and DECIDING legacy/foreign receipt tests assert zero provider calls and zero mint. |
| FC3: foreign/null checkpoint was rejected only after apply/receipt mutation | Fixed. Dispatch, reconcile and schedule call a read-only principal preflight before executor/readers. Same-P checkpoint update is CAS/readback and cannot replace null/foreign P. | Apply/reconcile/schedule tests seed null/foreign checkpoints and assert zero RPC/acquisition/advance. |
| FC4: service returned on a legacy plan without durable pause/reason | Fixed. Before any registry acquire, service marks all recoverable attempts `RECOVERY_REQUIRED / PROVIDER_PRINCIPAL_UNKNOWN` and the active owner session `paused`. | Real committed-v6 → v7 `RELEASE_PENDING` migration fixture verifies null P preservation followed by the durable fail-close transition. |
| FC5: UI status fell back to current build when no crashed attempt existed | Fixed. The latest durable plan P is the run target; no plan is unknown, and a legacy crashed owner remains unknown rather than borrowing plan/build identity. | Debug-process projection test uses a production plan and proves production remains selected. |
| FC6: registry default factory used `applicationContext`, bypassing lifecycle wrapper tests | Fixed. The registry-owned context is passed directly; service ownership still bounds its lifetime. | Default-factory async binding test captures the real connection and closes/unbinds once. |

Production construction is additionally guarded: `APlusComposition.productionBackend` accepts only a non-null `ProviderScopedExternalApplyExecutor`, validates its known target, and propagates that target through the backend/coordinator. `AutomationEngineFactory.productionEngine` then rejects a coordinator whose target is null or unknown, so a future production caller cannot silently enter the compatibility seam. The nullable coordinator branch remains explicitly test-only for direct `AutomationEngine` fixtures. The production decision API is separately named `recordTrustedCompletionForProvider` and requires a non-null Kotlin `String` target; a missing attempt fails typed UNKNOWN before an orphan execution can be persisted.

## Formal exact-head review iteration

Independent review of local exact head `c01fb145e7fdd4a43bedf0336090ea0f540c4c49`
returned `REQUEST_CHANGES` with two reproducible gaps:

| Finding | Root cause | RED oracle | Fix |
|---|---|---|---|
| Room plan/attempt P was not part of the coordinator-wide join | The coordinator consulted only its `DurableRecoveryLog`; an empty or same-P proof set could not reveal a null/foreign authoritative owner. | Real in-memory Room rows cover null/foreign plan and attempt owners with both empty and same-P proofs; direct and engine paths assert zero discover, preflight, apply/reconcile, release, evidence acquisition, mint, advance, receipt and checkpoint mutation. An SQLite insert trigger proves the fresh-attempt object cannot substitute for durable readback. | `RoomDurableProviderPrincipalPreflight` performs the canonical attempt→task→plan→proofs→executor join in one transaction. The same transaction function is reused by repository DECIDING and service pre-bind checks. Coordinator wrappers gate every live executor/reader entry. |
| Readiness timeout was non-terminal | `awaitBound(false)` left the callback generation accepted and Service still constructed the engine. | A real asynchronous `ServiceConnection` times out, then receives the exact callback before a simulated first journey call; unbind is exactly once and all journey calls remain zero. | Timeout closes and evicts the registry acquisition, unbinds its executor, and makes the callback inert. Service persists `PROVIDER_BIND_NOT_READY`, pauses, and immediately returns before backend/engine construction. |

The wiring regression guard additionally scans production sources: `AutomationService` must call
`AutomationEngineFactory.productionEngine`, no other main source may directly construct
`AutomationEngine`, and the factory rejects a scoped coordinator backed by the explicit unchecked
test seam. These changes do not alter the frozen wire contract or DB v7 schema/migration decision.

The final entry-point sweep added two narrow kill tests after the formal findings were GREEN. A
durable-backed coordinator now rejects caller-selected release keys/digests and forwards an advance
request's idempotency key into the canonical apply-key check. Both real-Room tests start from a valid
same-P owner/proof chain, mutate only the caller key, and assert zero release/advance/RPC/mint plus
no release-receipt or checkpoint write. This enforcement is deliberately conditional on the
durable-backed resolver so old coordinator-only fixtures cannot masquerade as production, while
`productionEngine` still rejects their unchecked resolver. A separate readiness RED proved that a
synchronous exact callback cannot override `bindService=false`; the false result clears the remote
without calling `unbindService` for a binding Android says was never established.

## Formal exact-head review iteration: `e223805`

### Bug 诊断胶囊：production provenance、release proof、signer rotation 与 Binder 终态

| 栏位 | 内容 |
|---|---|
| 现象 | Exact-head review found that an arbitrary raw executor can claim a provider target and enter production composition; malformed durable release identity is detected only after earlier recovery work; an untrusted current signer still receives raw release; and a callback admitted just before timeout can publish after terminal cleanup. |
| 证据 | `ProviderScopedExternalApplyExecutor.wrap` is public and `productionBackend` accepts its result; `attemptProviderPrincipalFailureInTransaction` checks release P/lease only at the canonical key; the production trust decorator calls raw `release` unconditionally; `onServiceConnected` checks `acceptsCallbacks` separately from `remote` publication. |
| 问题假设或根因 | **Confirmed**: production authority is represented by forgeable structural claims instead of registry-issued capability; durable release tuple validation is incomplete inside the owner transaction; current-signer trust collapses revoke and rotation but release treats both as authorized; Binder callback admission/publication and terminal transition are not one atomic protocol. |
| 诊断策略 | Trace raw executor → wrapper → backend → coordinator → factory; seed real Room canonical-key/wrong-digest and wrong-key/same-lease rows before `ENV_APPLIED`; rotate approved signer A to current signer B; pause Binder interface conversion with latches after admission and overlap timeout. |
| 超时策略 | If a capability cannot be made unforgeable without widening the wire/DB contract, internalize production construction and use a private registry-issued implementation plus an explicit test-only seam. If release-after-revoke needs a product exception, stop and add durable signer/reason provenance rather than infer it from a Boolean. |
| 预警策略 | Any production factory that still accepts a raw/scoped executor, any proof validator that reads only one index, any untrusted release reaching the delegate, or any terminal executor becoming bound again invalidates the direction. Three failed fixes to one lifecycle object require a state-machine redesign rather than another flag. |
| 用户可见交互修正 | Malformed/rotated recovery stays `PAUSED / RECOVERY_REQUIRED` for manual handling; no provider cleanup is guessed against a new signer. |
| 验收 | Four mechanism RED groups: forged production chain rejected with zero executor calls; real-Room malformed release rows yield typed conflict before bind/evidence/mint/advance/mutation; A→B rotation yields zero raw release/receipt/checkpoint/advance; overlap latch proves timeout terminal and zero journey. Then targeted, Auto full, debug/release compile+assemble, lint, repo full, diff/contract gates. |

Failure-mode sweep invariant: **a production recovery boundary may consume only authority and proof
objects whose origin and full identity are verified at the same boundary; a public type/Boolean,
single-index partial tuple, or pre-terminal callback check is not authority.** Sibling scans cover the
Room-preflight marker, direct production constructors, both release indexes, every trust-decorated
journey method, registry acquisition closure, and Binder disconnect/death/null callbacks.

### Iterative disposition from exact head `e223805`

| Formal finding | Root-cause fix | Killing evidence |
|---|---|---|
| Forgeable production capability | Production composition now consumes only a file-private registry-issued acquisition whose exact Binder component has reached ready. The trust decorator is rebound to that same acquisition; factory validation checks identity, readiness, the concrete Room owner resolver, and executor ownership. Raw target wrappers and `testOnlyBackend` remain internal, explicit fixture seams and cannot pass the production factory. | A raw executor → claimed target → Room backend → coordinator chain is rejected before every executor action. A real async registry acquisition is accepted, then becomes unusable on close. Main-source guard allows the only production engine constructor in `AutomationEngineFactory` and forbids calls to both test-only seams. |
| Incomplete Room release proof | The attempt→task→plan→proof transaction validates the canonical release key, `releaseDigest(ownerLease)`, `RELEASED` outcome, and all rows for `(P, leaseId)`. Same-lease wrong-key, duplicate, partial, null, and foreign rows fail closed. | Real-Room canonical-key/wrong-digest, wrong-key/same-lease, and wrong caller digest cases stop pre-bind and before discover/preflight/observe/release/advance/mint/receipt/checkpoint. |
| Signer rotation release fail-open | Every current-signer trust miss blocks raw release. The typed `PROVIDER_SIGNER_UNTRUSTED` outcome is persisted with `RECOVERY_REQUIRED` and a paused session; recovery treats it as sticky manual recovery before provider work. Later approval of replacement signer B does not authorize B to release signer A's outstanding lease. | A→B first run and post-approval restart both record zero raw release, release receipt, checkpoint, mint, and advance. The UI projects the durable reason as `ReleaseIncomplete` with the manual lease action, ahead of pending-pairing state. |
| Callback/timeout resurrection | One lock + generation state machine makes bind admission, remote publication, timeout/unbind, bind-false, and terminal callbacks one protocol. Binder interface conversion stays outside the lock; publication rechecks generation and terminal state atomically. | A latch blocks interface conversion after callback admission while timeout terminalizes the executor; the late exact callback cannot publish and every journey method stays inert. Existing async, wrong-component, refcount, disconnect/death/null, and synchronous-callback/bind-false cases remain green. |

Signer-release state ownership is therefore: `RELEASE_PENDING + current signer untrusted` → persist
`RECOVERY_REQUIRED / PROVIDER_SIGNER_UNTRUSTED` and session `paused` → every restart stops on that
durable reason before registry acquisition or RPC → only a future explicit manual-resolution flow may
clear it. Trust-store approval alone is not that flow.

### Required producer/consumer integration matrix after the provider branch is frozen

This consumer branch does not change PR #66 or the frozen `environment-control-v1` wire contract.
After the provider producer exact head is known, integration must replay this commit and cover:

- bench apply, Auto process death, then an Auto build whose `BuildConfig` points at production: every
  recovery/release operation still targets the durable bench principal;
- QWY exact `R+1`, owner-recovery `R+1`, and quarantine `R+2` windows;
- foreign, mixed, or legacy-null P: zero provider RPC, mint, and advance;
- release identity scoped by `(P, leaseId)`, while an identical idempotency key across P remains a
  global conflict;
- Auto signer rotation against QWY's `(callerApplicationId, signerDigest)` owner check must fail
  closed and may require operator/provider cleanup. Do not weaken signer ownership to make that
  recovery automatic.

## Formal exact-head review iteration: `b3523d3`

### Bug 诊断胶囊：首次恢复前 signer rotation

| 栏位 | 内容 |
|---|---|
| 现象 | Signer A 创建 outstanding lease 后，Auto 在首次 trust miss/sticky 写入前崩溃；包轮换为 B 且 B 在首次恢复前获批。P-only pre-bind join 接受该状态，production Binder 把 A lease 发送给 B。 |
| 证据 | Real-Room test 按 Service 顺序执行 Room pre-bind、registry-issued acquisition、exact Binder callback、production backend/coordinator release。A→B 与 legacy-null 两例均得到 `prebind=null`、`raw release RPC=1`；期望为 pre-registry typed stop 与 0 RPC。 |
| 问题假设或根因 | **已确认根因**：上一轮只持久化了“首次 trust miss 之后”的 sticky reason，没有持久化创建 attempt/lease 的 immutable signer owner。Runtime gate 只问 current signer 是否任一 active pairing；B 的合法批准因此错误地替代了 A 的历史所有权。 |
| 诊断策略 | 逆向追踪 PackageManager signer → Service pre-bind → registry capability → backend trust gate → attempt/apply/checkpoint/release proofs，并对每个 durable row 做 null/foreign signer sweep。 |
| 超时策略 | 若 signer 不能在首个 provider journey 前由 registry-issued exact capability 落库并读回，则停止；不得从 current signer、active pairing、时间、BuildConfig、sibling package 或 lease 猜测。 |
| 预警策略 | 任一 production caller 能省略 signer、任一存在 proof row 的 null/异 signer 被忽略、或 B approval 能清除 A owner conflict，均说明边界仍 fail-open。 |
| 用户可见交互修正 | Owner unknown/conflict 均持久 `RECOVERY_REQUIRED`、暂停 session，并投影 `ReleaseIncomplete`/人工租约处理，优先于 B 的 pairing 状态。 |
| 验收 | Rotation-before-first-recovery 与 legacy-null 真实 Room tests：0 acquire/bind/discover/preflight/apply/observe/release/advance/evidence/mint/proof mutation；new attempt signer insert/readback 在首 RPC 前；v6→v7 signer 列保持 NULL。 |

同型 failure-mode invariant：**applicationId 只选择 provider package；outstanding attempt/lease 的
cleanup authority 是 immutable `(applicationId, signerDigest)`，其 signer 必须来自 registry-issued
exact capability 并贯穿 attempt 与所有已存在 recovery proofs。** Sticky reason 只是结果投影，不能
成为安全成立的前提。

### `b3523d3` iterative disposition

| Finding | Root cause | Fix | Killing evidence |
|---|---|---|---|
| Crash-before-sticky A→B rotation | Durable owner stored P but not the signer that created the attempt/lease; a newly approved B satisfied the generic current-pairing gate. | Persist canonical S on attempt/apply/checkpoint/release rows. Before registry acquisition, one Room transaction joins plan P, attempt `(P,S)`, every existing proof `(P,S)`, current captured S, and active exact pairing; UNKNOWN/CONFLICT atomically marks the attempt recovery-required and pauses the session. Registry callback and every RPC re-resolve the same exact S. | Real-Room production-chain RED reached one raw release for A→B and legacy-null. GREEN has 0 acquire/bind/discover/preflight/apply/observe/release/advance/evidence/mint/proof mutation, including restart and currently-unresolvable signer cases. |
| Fresh work had no immutable signer readback | The attempt was historically created from P-only composition. | Only a ready registry-issued acquisition supplies S; attempt insert plus readback happens before the first discover/preflight/RPC, and mint/decision joins the same S in its transaction. | A trigger that changes inserted S stops the journey with all external and ledger counters at zero. |
| Existing proof rows could become half-principal | P-only receipt/checkpoint/release DAO equality did not carry signer provenance. | v6→v7 adds nullable no-default/no-backfill S to attempt and three proof tables; replay, checkpoint CAS, release lookup, and decision join require exact S for every row that exists. | Null/foreign single-row sweep, same key/digest cross-S replay, checkpoint cross-S CAS, release cross-S replay, and legacy migration all fail closed. |
| Signer propagation accidentally widened release uniqueness | Using `(P,S,leaseId)` as the read scope let a rotated signer append another row for the same P/lease under another key. | Keep release ownership scoped by `(P,leaseId)` and treat S as proof, not a namespace. Keep the operation key globally unique. | Self-audit RED: 4 tests, 1 failure (same P/lease under B was inserted). GREEN rejects that row; explicit cross-P test proves same key still conflicts globally while same leaseId under another P/different key remains allowed. |

UI projection gives `PROVIDER_SIGNER_OWNER_UNKNOWN` and `PROVIDER_SIGNER_OWNER_CONFLICT` durable
manual-recovery state precedence over the current B approval. An outstanding lease renders
`ReleaseIncomplete`; a non-lease owner failure renders `RecoveryRequired`. Generic retry or later
approval cannot clear the sticky incident without explicit manual-resolution evidence.

Validation before exact-commit full gate: signer/recovery targeted 155/155; migration ladder 10/10;
Auto unit 579/579; debug/release compile+assemble, release-purity, inherited lint-debt, and pinned
debug signer all GREEN. Canonical fixture updates use real 64-hex signer digests; production legacy
NULL remains fail-closed and receives no backfill.

### Final reachability sweep disposition

| Finding | RED | Fix / invariant |
|---|---|---|
| Revoke between outer recovery preflight and inner reconcile lost signer type | Expected `PROVIDER_SIGNER_UNTRUSTED`; durable row received `PROVIDER_PRINCIPAL_UNKNOWN`. | `ReconcileResult.ProviderFailure` carries an exact typed reason. Engine persists that reason directly, so re-approval cannot pass the Service pre-bind sticky check. |
| Revoke immediately before DECIDING mint was not recognized by Engine | Expected `RECOVERY_REQUIRED`; row remained `DECIDING`, `failureReason=null`. | Repository throws `ProviderPrincipalFailureException` with a typed enum. Engine catches that type rather than parsing exception text, persists `PROVIDER_SIGNER_UNTRUSTED`, pauses, and mints/releases/advances nothing. |
| Pre-Service UI let current pairing outrank a crashed null/foreign signer owner | `APPLY_PENDING` projected pairing/trusted instead of manual recovery. | A crashed run requires an active exact `(P,S)`; null S cannot borrow app-level approval. Any in-flight owner mismatch projects `RecoveryRequired`, or `ReleaseIncomplete` when a lease exists, before pairing state. |
| Fresh signer readback oracle was observational only | Removing the post-insert Room join could still leave the positive fixture green. | SQLite trigger changes S after insert. Readback detects conflict before the first provider action and before any receipt/checkpoint/mint. |

The typed carrier preserves the five existing durable codes and classifies them into the three
stable states `UNKNOWN`, `CONFLICT`, and `UNTRUSTED`. Coordinator → Engine → Repository → UI uses
the enum/sealed carrier; conversion at the Room/UI persistence boundary is exact code equality only,
never `contains` or message-prefix parsing. The original RED set was 22 tests / 3 failures; it is now
22/22 GREEN, 43/43 with adjacent regressions, 155/155 across all changed suites, and 579/579 Auto
full.

## Formal exact-head review iteration: `9f7ae052`

1. **报告人 / 现象**：formal iterative reviewer found that a wrong-key release row for the same
   `(P, leaseId)` was still safe but lost its signer-specific failure. Null S and foreign S both
   became `PROVIDER_PRINCIPAL_CONFLICT`, so the durable/UI projection suggested generic retry rather
   than owner-manual recovery.
2. **复现**：real Room `guardRecoveryProviderPrincipal(P, A)` seeds attempt/apply `(P,A)` plus a
   wrong-key same-lease release row whose S is null or B. RED was 7 tests / 3 failures: expected
   `PROVIDER_SIGNER_OWNER_UNKNOWN` or `PROVIDER_SIGNER_OWNER_CONFLICT`, actual generic conflict.
   Registry acquire, bind, every provider RPC, mint, checkpoint and proof mutation remained zero.
3. **根因**：all canonical attempt/proof checks returned `signerFailure` directly, but the
   `allByLease` sibling scan converted it to Boolean inside `any { ... }` and then returned one
   structural conflict code. This was the only same-mode collapse in the main-source sweep.
4. **修复**：aggregate all same-lease signer failures before structural validation. Priority is
   deterministic and independent of DAO row order: any null/invalid S → OWNER_UNKNOWN; otherwise
   any foreign S → OWNER_CONFLICT; only then key/digest/outcome/duplicate → generic conflict.
5. **验证**：both single corruptions and mixed null+B rows in both insertion orders are GREEN;
   attempt becomes `RECOVERY_REQUIRED`, session becomes `paused`, UI is `ReleaseIncomplete`, and
   existing proofs are byte-for-byte unchanged. New suite 7/7, reviewer set 33/33, changed suites
   158/158, and Auto full 582/582.

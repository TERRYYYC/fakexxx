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

**Acceptance Criteria:** AC-01..AC-14，见「验收标准与追踪」；每项都有对应不变量、测试和证据。

**Architecture cell:** `fakexxx::android-dual-app-contract`（本仓的新 ownership cell，Phase 1 写入 `docs/architecture/ownership/README.md`）

**Map delta:** new cell required

**Map delta why:** 当前仓库只有 README；本功能首次建立 Auto、千网游、版本化设备内契约和验收面四个所有权边界。

**Architecture:** 双 App 保持独立包名、独立构建和独立发布。Auto 只负责计划、CellRebel 执行、可信计数、日志和恢复；千网游是 Hook、System Mock、profile、schedule 及有效环境证据的唯一能力权威。两者只通过设备内、鉴权、版本化的窄 Binder/AIDL 契约协作。

**Tech Stack:** Kotlin/JVM 17、Android 24+/26+、AIDL/Binder、Room、DataStore、Jetpack Compose、JUnit4、Robolectric、Android instrumentation tests。

**前端验证:** Yes — Auto 的计划/运行/恢复/历史旅程和千网游的配对/授权面都必须用真机截图或录屏验收；单元测试不能替代。

---

> ## ✅ operator 已拍板 — 本文为冻结实施基线
>
> 三项 operator 价值取舍已于 `2026-08-09T21:19:59Z` 决定（主 Thread 消息
> `0001786310399153-001347-114fff25`，逐字记录见 §21）：
>
> - **DP-1 = B**：迁移到受控 release key。**受控迁移**——profile export/restore、
>   release-key custody 与回滚方案必须先于 signer cutover 完成。
> - **DP-2 = B**：Auto 立即改名，最终 `applicationId` 逐字为 **`come.xx.fakeaauto`**。
>   实现者**不得**推断纠错为 `com...`。改名受 `INV-29` 的数据连续性硬门约束。
> - **DP-3 = A**：接受 UI 完成证据并写明上限。上限必须进入**用户可见的计数语义**
>   （运行页 / 历史页 / 导出），不得只留在本文里。
>
> 因此 `INV-11` 按 A 的兑现口径生效，`M-CO-03` 终态确定，`AC-06` 可按 A 验收，
> **contract v1 可以冻结，#3 / #4 / #5 / #6 解除停止**。
>
> 仍然成立的**不可证明上限**，验收时不得呈现为全绿：§8.6.5（跨 attempt 完成去重）、
> §18.1（AC-05 依赖 qwy 的 `FULL` 声明）。DP-3 选 A 意味着这些上限被**接受并记录**，
> 不意味着它们消失。

## 0. 文档地位与冻结结论

本文是 `TERRYYYC/fakexxx` 的实施与演进单一真相源。GitHub Epic、子 issue、开发 Thread 和 PR 必须链接本文；出现冲突时先修本文或明确记录 operator 的新决策，不能让 issue 正文悄悄改架构。

已冻结结论：

- 当前实施基线是 **A+**。
- Opus5 与 Deep 的排序是 `A > B > C`；Sol 的排序是 `B > A > C`。分歧保留，不把 A+ 包装成全员一致的 UX 结论。
- A+ 是可信优先的一键批处理；首版只提供合法模板与常用执行参数，不建设通用工作流引擎。
- B 是共享同一内核的受控高级配置演进线，不是另起炉灶。
- C 只有在多消费者或平台需求出现后才进入候选；它复用同一执行原语和证据模型，不推倒 A+/B。
- 单纯心跳只能证明进程仍活着，不能证明环境从未发生相关变化，禁止把心跳当连续性证据。

### 0.1 修订记录

| 版本 | 基线 | 内容 |
|---|---|---|
| v1 | `00a5e58` | 初始冻结 |
| v1.1 | 本 PR-0.1 | contract v1 冻结**前**的实现者前置修订，见下 |
| v1.2 | 本 PR-0.1 | 非作者 review（REQUEST_CHANGES）后的 7 项修订，见 §0.1.2 |
| v1.3 | 本 PR-0.1 | delta re-review 后的 5 项修订，见 §0.1.3 |
| v1.4 | 本 PR-0.1 | final narrow delta 的 3 项修订，见 §0.1.4 |
| v1.5 | PR-0.2（base `main@be885ac`） | merge 后 acceptance + GLM 双路 `REQUEST_CHANGES` 的 fix-forward，见 §0.1.5 |
| v1.6 | PR-0.2 第二轮 | acceptance 对 `5996b2e0` 的 `REQUEST_CHANGES`，见 §0.1.6 |
| v1.7 | PR-0.2 第三轮 | behavioral-delta 对 `7e1fa20` 的 `REQUEST_CHANGES`，见 §0.1.7 |
| v1.8 | PR-0.2 第四轮 | Sol + GLM 双路绑定 `ecfb322e` 的 `REQUEST_CHANGES`，见 §0.1.8 |
| v1.9 | PR-0.2 第五轮 | acceptance 对 `ad70a625` 的 `REQUEST_CHANGES`，见 §0.1.9 |
| v1.10 | PR-0.2 第六轮 | acceptance 对 `520cc846` 的 `REQUEST_CHANGES`，见 §0.1.10 |
| v1.11 | PR-0.2 第七轮 | acceptance 对 `605b4dd9` 的 `REQUEST_CHANGES`，见 §0.1.11 |
| v1.12 | PR-0.2 第八轮 | acceptance 对 `1e88cc66` 的 `REQUEST_CHANGES`，见 §0.1.12 |
| **v1.13** | PR-0.2 第九轮 | **operator DP-1/2/3 决策落地**（`797178eb`）+ 非作者复审的三次事实更正（`1701de28` / 本轮），新增 `INV-29`，解除 contract 冻结，见 §0.1.13 |
| **v1.14** | PR-0.2 第十轮 | **owner transfer 传播**（#4 Kimi → DeepSeek Flash）+ **为 `INV-29` 冻结 evidence carrier**（`appid-cutover` 5 行），见 §0.1.14 |

v1.1 的动因：主实现作者在动手前对照两个上游的精确 SHA 做了只读核验，发现若按 v1 原样冻结 AIDL，其中数项缺口只能靠 v2 或用户数据迁移来补救。全部修订均在 contract 冻结前落地，因此不产生 v2 债务。

| 项 | 变更 | 章节 |
|---|---|---|
| 意图绑定 | 新增 `EnvironmentObservationV1.acceptedIntentHash`、canonical digest 算法、坐标容差；可信谓词新增意图绑定段 | §6.3、§6.3.1、§6.4、INV-23、AC-13 |
| DTO 补全 | 补齐 `ApplyRequestV1`/`PreflightRequestV1`/`PreflightReportV1`/`ObserveRequestV1`/`ReleaseRequestV1`/`ReleaseReceiptV1`，`EnvironmentIntentV1` 纳入文件所有权 | §6.3.2、§12 |
| 枚举 wire | 枚举改为稳定 `Int` wire code + 显式 `fromWire()`，未知值 fail-closed | §6.2、§6.7 |
| 包可见性 | Auto Manifest 新增千网游两个 applicationId 的 `<queries>`，并纳入 owner matrix | §6.1、§12.1、Task 2 |
| minSdk | contract library 冻结 `minSdk = 24`；Auto 26 / qwy 24 不变 | §6.1 |
| 配对次序 | 首次配对改为 bind-first，身份来自 `Binder.getCallingUid()` | §4.1、§6.5 |
| 签名分层 | API 28+ 与 24–27 两条路径显式分层，降级路径 fail-closed 并 UI 明示 | §6.5.1 |
| signer 边界 | 诚实披露当前 debug keystore 复用的真实后果，不夸大也不粉饰 | §6.5.2、§21 DP-1 |
| 跨进程 revision | 单写者 + 跨进程原子持久化（**该行的承载物禁令已被 v1.3 第 1 项取代**，现行规则见 §6.6 L1–L6） | §6.6、INV-25、AC-05 |
| 数据迁移 | Auto v4→v5 显式 migration + schema export + 禁止 destructive fallback | Task 4、INV-24、AC-14 |
| provenance | 用 tree digest 比对替换恒真的 `--is-inside-work-tree` 断言 | Task 1 |
| PR 路由 | 明确 Task 6 两半各随 owner 的 PR 走 | §15 |
| 价值取舍 | 抽出 DP-1（signer 迁移）、DP-2（Auto applicationId）交 operator | §21 |

v1.1 **未**改动的：A+/B/C 关系与触发门、A+ 首版范围与非目标、owner matrix 的人员划分、merge 权限、既有 INV-01..22 的语义。

#### 0.1.1 平台事实 provenance

v1.1 里凡是"因为 Android 平台如此，所以规则如此"的论证，都追到了一手来源；结论与来源同时记录，便于后续复核而不必重新调查：

| 事实 | 结论 | 一手来源 |
|---|---|---|
| 显式 bind 是否受包可见性限制 | 受限。activity 的豁免不延伸到 service | `training/package-visibility`：“The limited visibility also affects explicit interactions with other apps, such as starting another app's service.” |
| 反向（被调用方→调用方）可见性 | bind 后自动授予 | `training/package-visibility/automatic` 第 5 条：“Any app that starts or binds to a service in your app.”；AOSP `ActiveServices.bindServiceLocked` → `grantImplicitAccess` |
| 该反向授权的存续期 | **无文档化保证**（AOSP 中为内存态） | 官方文档未规定；因此 §4.1 要求调用内快照，不做延迟反查 |
| `SharedPreferences` 多进程 | 不支持；`MODE_MULTI_PROCESS` API 23 起弃用 | `reference/android/content/SharedPreferences`、`Context#MODE_MULTI_PROCESS` |
| `DataStore` 多进程 | 1.1.0+ 有 `MultiProcessDataStoreFactory`，但只承诺 eventual consistency | `reference/kotlin/androidx/datastore/core/MultiProcessDataStoreFactory` |
| Room/SQLite 多进程存储保证 | **无一手来源可引**；`enableMultiInstanceInvalidation()` 只管失效广播 | `reference/androidx/room/RoomDatabase.Builder` |
| 跨进程共享可变状态的平台推荐 | `ContentProvider` | `Context#MODE_MULTI_PROCESS` 弃用说明 |
| `@Parcelize` 枚举编码 | 按 `name` String（非 ordinal）；未知常量 `valueOf` 抛 `IllegalArgumentException` | kotlin-parcelize 编译器 `IrParcelSerializers.kt` 的 `IrEnumParcelSerializer`；`kotlinlang.org/docs/enum-classes` |

未能取得一手确证的，一律写成"待 Task 2 核定"或直接不写，不用二手转述充当依据。

#### 0.1.2 非作者 review 修订（v1.2）

v1.1 收到 `REQUEST_CHANGES`，7 项全部成立并已修订。记录在此是因为其中数项是**前一版自己引入的缺陷**，不是原 spec 的问题：

| # | 问题 | 修订 | 章节 |
|---|---|---|---|
| 1 | v1.1 冻结"DTO 只承载 Int wire"，但 exact schema 里仍有 enum 与 `Set<enum>`，靠一句散文说明覆盖 | 全部字段改为 `...Wire`/`...Wires`；删除散文豁免；`check-contract-v1.sh` 增加"`@Parcelize` 内出现 enum 即失败"的静态检查 | §6.3、§6.3.2 |
| 2 | 身份判定有两个漏放行口：UID 未收敛到唯一 package；`hasSigningCertificate` 语义是"曾经或当前"，轮转后仍返回 true | `getPackagesForUid` 非恰好 1 个即拒；改比对**当前** signer；多签名者 v1 全拒；补 Auto 侧对千网游的反向 signer 校验 | §6.5.1、§6.5.3 |
| 3 | 把承载技术写成了结论（禁 Room/SQLite、推 ContentProvider） | 改为冻结 L1–L6 线性化语义；owner 内部存储选型自由；只否定"多进程各自直接写同一存储"这一架构形态 | §6.6 |
| 4 | v4→v5 未定旧进度语义：改投影则历史无声归零，回填则违反 INV-05/06 | 冻结 `LegacyCompletionSnapshot` / `LEGACY_UNVERIFIED`：保留展示、绝不生成 `TrustedQuotaEntry`、trusted 从 0 起算 | Task 4、§7.1、§7.3 |
| 5 | canonical digest 用换行连接自由字符串，可构造碰撞 | 改长度前缀 framing（`uint32be(len) \|\| bytes`），编码单射；碰撞对列为必测负例 | §6.3.1 |
| 6 | 用 `<10 m` 导入硬拒绝代偿模型歧义，缩小了合法输入集 | 删除该限制；归属由 intent hash + task identity 负责，最多做非阻断 warning | §6.4、§10 |
| 7 | AC-10 的 INV 范围过期、gate 标题重复、provenance checker 未先 fetch 上游对象、DP-1 把 not-testable 范围说得过宽 | 逐项收口 | §18、§15、Task 1、§6.5.2、§21 |

原则记录：第 1、3、5、6 项都是 v1.1 自己引入的——修 spec 的过程同样会产生缺陷，所以非作者 review 不是形式，contract 冻结前必须过这一关。

#### 0.1.3 delta re-review 修订（v1.3）

v1.2 收到第二轮 `REQUEST_CHANGES`，5 项全部成立并已修订。主题从"规则本身对不对"转成了"规则有没有传播到位、有没有可执行的起点"：

| # | 问题 | 修订 | 章节 |
|---|---|---|---|
| 1 | §6.6 已改成 L1–L6，但 Task 3 与 §10 仍写着上一版的"禁 DataStore/跨进程原子事务"旧结论，Kimi 会收到两套相反规则 | 下游同步为：只禁"多进程各自直写同一 store"与纯内存；owner-local 存储自由但须证 L3–L5；静态 guard 检测**非 owner 写路径**而非库名 | Task 3、§10 |
| 2 | Auto 反向校验只有谓词没有信任根：首次连接若自动信任，§10 的"同包名替代者"负例根本不会失败（silent TOFU） | 冻结 Auto 侧 `ProviderPairingRecord` + operator 显式批准入口；未见过的 signer 停本地 `NOT_PAIRED`；**禁止在信任 discover/observation 的同一步落 trusted**；明确区分 qwy 的 caller allowlist 与 Auto 的 provider allowlist | §4.1、**§6.5.3**、§7.1、Task 4/6、§10 |
| 3 | `versionCode` 参与身份精确匹配，会让正常升级也要求重新配对，与独立发布 + 兼容握手冲突 | 授权 principal 恒为 `(applicationId, current signerDigest)`；`versionCode` 降为审计/诊断字段 | **§6.5.4**、§4.1、§6.5、§10 |
| 4 | `LegacyCompletionSnapshot` 未落到 exact 文件图：Task 4 仍写"三类表"，entity/DAO 无路径，§7.1 漏 `migratedAt` | 改四类表；冻结 entity 落 `Entities.kt`、独立 DAO 路径、`ProviderTrustStore` owner；补齐字段 | Task 4、§7.1 |
| 5 | `§6.5.3` 排在 `§6.5.2` 之前，锚点倒序 | 重排为 6.5.1 → 6.5.2 → 6.5.3 → 6.5.4 | §6.5 |

第 1 项值得单独记：局部改对了不等于改完了。改一条被下游引用的规则时，必须回头扫所有引用点——否则文档内部自相矛盾，比不改更危险，因为执行者会各自挑一套。

#### 0.1.4 final narrow delta 修订（v1.4）

| # | 问题 | 修订 | 章节 |
|---|---|---|---|
| 1 | provider allowlist 只有 approve 没有 revoke：schema 无状态字段，`ProviderTrustStore` 只暴露查/批准两个方法，导致 §6.5 顶层的 revoke 规则**在这套 schema/API 上无法实现** | 增加 `revokedAt` 与 active 语义（撤销是状态迁移不是删除）；`ProviderTrustStore` 定为 `findActive`/`approve`/`revoke` 三个窄方法；Auto UI 增撤销动作；补撤销即时生效、进行中 run 转 release/recovery、撤销不回溯已计配额、撤销后重新出现须重走批准；Task 9 撤销两侧各测 | §6.5、**§6.5.3**、§7.1、§10（6 行）、Task 4/6/9 |
| 2 | Task 4 写"四类表（另有一张）"，而五张表都进 v5 schema，自相矛盾 | 改为五类表；迁移 fixture 增加"`ProviderPairingRecord` 已创建且初始为空"断言——升级不得凭空产生被信任的 provider | Task 4 |
| 3 | "禁止 TOFU"措辞过宽：operator 对首次见到、未独立比对的 signer 显式批准，密码学上仍是 TOFU；且 `lastSeenVersionCode` 没有更新入口 | 统一为"禁止 silent/automatic TOFU"并写明安全上限（不证明 publisher identity）；版本字段改 immutable `approvedVersionCode`，后续版本只进 append-only 审计，不为审计字段扩大信任 store 可写面 | §4.1、§6.5.3、§6.5.4、§7.1、§10 |

第 1 项与上一轮的 TOFU 缺口是**同一种病**：在顶层写下一条规则，然后冻结了一套做不到它的 schema/API。规则与承载它的接口必须一起冻结，否则规则只是文档里的一句话。

#### 0.1.5 post-merge fix-forward（v1.5）

PR-0.1 合入后，acceptance（Sol）与对抗审查（GLM）在同一 exact HEAD 上各自给出 `REQUEST_CHANGES`。八项 blocker 的**共同 failure mode 被明确命名**：*规范承诺没有完整落到 wire code、状态机边和可触达的 evidence owner*。这已是第 5 轮同型缺口，因此本轮不逐条打补丁，改为产出四张**全量映射**，让"承诺存在但无承载"在结构上可被发现。

| 表 | 内容 | 章节 | 审计出的新缺口 |
|---|---|---|---|
| 表 1 | 每条 typed failure → `ContractErrorCodeV1` wire code | §6.3.3 | `IDEMPOTENCY_CONFLICT`、`REQUEST_INVALID` 缺失 |
| 表 2 | observation 每个字段 → 信任谓词角色 | §6.4.1 | `deliveryMode`/`isMock`/`scheduleDecision` 未交叉校验（外部指出）；**`observedAt` 未验证夹住执行窗、`continuitySince` 未验证早于 pre 观察（本表审计新发现）** |
| 表 3 | 每个 stateful object → 状态机/崩溃/冲突 | §8.3 | `EnvironmentLease` 无状态机（§8.4）、配对/预检态未普查（§8.5） |
| 表 4 | §10 每行 → evidence class / owner / 入口 | §10.1 | Task 7 三项承诺不可同时成立 |

其余修订：AC 表恢复顺序并冻结"新增一律追加表尾"；AC-05 的不可证明上限落表（§18.1）；Task 2 的 `EnvironmentIntentV1.kt` 路径此前被我追加在 `src/test` 行之后导致"同目录"指向测试目录，已修正；`check-forbidden-boundaries.sh` 移入 `acceptance/scripts/`，消除 root scripts 的 owner 冲突。

**最重要的一条是降级而不是新增**：§8.6 先做事实认定——CellRebel 在现有可观察面上**不暴露任何物理执行身份**，Auto 与它之间不存在完成契约，`PRE_EXISTING_RUN` 只是因果归属。因此 INV-11 的绝对表述不可能成立，已降级为有界保证 + INV-26 审计，并在 §8.6.5 写明"能证明什么、不能证明什么"。同时按事实收紧了判定：RUNNING 必须由 marker 文本证实且持续达标，堵住"re-foreground 动画期间 disabled-Start 被当作 RUNNING、读到上一次结果页分数"这条真实双计路径。

方法论记录：**先查可观察面，再定不变量。** 前四轮的缺口都是"先写下承诺，再去找承载"，而承载往往不存在。第 5 轮改成先做事实认定，结论就从"补一条 INV"变成了"必须降级一条 INV"——后者才是诚实的。

#### 0.1.6 acceptance 第二轮修订（v1.6）

| # | 问题 | 修订 |
|---|---|---|
| 1 | **INV-11/AC-06 的降级是产品安全边界变更，不是猫可自决的文档修订** | 降级**撤回待决**：INV-11 恢复冻结基线的严格表述，AC-06 标为 DP-3 未决前不得通过；新增 **§21 DP-3** 给 operator 二选一（**该行所述的选项 B「`READY` 基线结构性关闭」已被 v1.7 第 1 项推翻并取代**——它只是 mitigation，现行 B 见 §21） |
| 2 | 表 4 并不 exhaustive：`completion` 6 行完全不在 §10.1；类别级散文选不出逐行 owner；序号由行序推导不稳定；grep token 可假绿 | §10 表新增**显式 ID 列**（77 行，一经分配永不重排复用）；§10.1 改为**逐行台账**（ID/class/owner/精确入口）；新增 `static-guard` 第四类；覆盖校验改为三项：集合相等 + **绑定已执行测试报告** + not-testable 必须显式 |
| 3 | 连续性只查 pre 侧；`cellRebelStartedAt/CompletedAt` 未冻结为 exact 字段、时钟语义未定；`evidenceRefs` 非空被说成"证据可独立解析" | pre/post 两侧都查且要求 `continuitySince` 相等并早于 pre 观察；**§6.4.2 冻结 `SystemClock.elapsedRealtime()` 为唯一可比时钟**（墙钟仅审计），契约增两个 elapsed 字段，`CellRebelExecution` 冻结三个时间字段并禁止复用上游 `startedAt`；`evidenceRefs` 收窄为结构性条件 |
| 4 | `STALE_LEASE` 与 `EXPIRED → RELEASING` 互相矛盾；`OperationReceipt` 无 request digest；`EnvironmentLease` 字段未冻结；`PendingPairingCandidate` 不在状态普查 | release 受理态（**该行的四态含 `REVOKED`，已被 v1.7 第 4 项取代为三态**——失权 caller 根本无法调用，改由 qwy 内部自清理）；`OperationReceipt` 增 **`requestDigest`**；lease 冻结 12 个权威字段；`PendingPairingCandidate` 进 §7.2 与 §8.3 |
| 5 | 未知枚举 wire 与未知 error wire 混同；`WAIT_UNTIL` 缺字段是**应答**非法却映射成 `REQUEST_INVALID`；INV 顺序 `01..11,26,12..25` 违反刚立的追加规则 | 两类未知分开；应答级矛盾冻结为消费方 fail-closed，不占用请求错误码；INV-26 移到表尾 |

第 4 项里 `OperationReceipt` 缺 `requestDigest` 是这轮最典型的一例：我在 §6.3.3 新增了 `IDEMPOTENCY_CONFLICT`（"同键异 payload"），却没检查承载它的状态对象里**根本没有 payload digest**——result digest 证明不了两个不同请求，因为不同请求完全可能产生相同应答。**这与 v1.4 的 revoke 缺口、v1.3 的 TOFU 缺口是同一种病的第三次复发。**

因此本轮把它变成机械可查：§8.3 完备性表要求每个被 INV 依赖的对象都指向已定义的状态机与**已冻结的权威字段**，§10.1 要求每行都指向 class/owner/精确入口且由构建校验集合相等。**规则、承载它的字段、证明它的入口，三者必须同时存在**——少任何一个，规则都只是文档里的一句话。

#### 0.1.7 behavioral-delta 第三轮修订（v1.7）

| # | 问题 | 修订 |
|---|---|---|
| 1 | **DP-3 的选项 B 是伪严格**：`READY` 基线只排除"基线时旧分数已在屏"这一条路径，无法区分 `READY → 真 marker → 新结果` 与 `READY → 持续 marker/重渲 → 旧结果`——两者在 `ScreenNode` 观察面上完全同形 | B 降为 **mitigation（两选项共用）**；新 B = **UI-only 完成不进可信配额**，要求 CellRebel UI 之外的独立完成证据，代价是今天可信配额恒为 0。同步 §8.6.5、INV-11、`M-CO-03`（终态标为 DP-3 未决 + not-testable）、AC-06 |
| 2 | `requestDigest` 有字段无 canonical preimage，真实 qwy 与 fake-qwy 会各自实现 | **§6.3.4** 冻结 domain-separated + 长度前缀 preimage，逐 operation 定字段顺序，并明确排除 `idempotencyKey`/`operationId`/`callerProtocolVersion`/caller 及各自理由；新增 `M-ID-02`（换 `operationId` 重试不得冲突）、`M-ID-03`（domain separation） |
| 3 | `M-RQ-01` 仍把 malformed response 与非法 request 合并并归 Kimi | 拆为 `M-RQ-01`（request 校验，Kimi）与 **`M-RS-01`**（新 `response` 类，consumer fail-closed，Sol blackbox） |
| 4 | qwy 撤销 caller 后，caller 已失权却仍被要求去 `release` 那个 `REVOKED` lease——路径不可达 | 两侧撤销分开定义：**qwy 撤销 → provider 内部自清理**（不为失权 caller 留任何 post-revoke 能力）；**Auto 撤销 provider → Auto 仍被授权，正常 release**。§6.3.3 的受理三态去掉 `REVOKED`；§8.4 拆边；新增 `M-LS-08/09` |
| 5 | lease deadline 混用墙钟与单调钟且无桥接 | §8.4 冻结 **apply 时一次性转换并快照** `deadlineElapsedRealtimeMs = nowElapsed + max(0, deadlineEpochMs − nowEpoch)`，此后只有单调值参与判定；跨 generation 不可比时按 `EXPIRED` 处理；新增 `M-LS-10/11/12` |
| 6 | executed-report 载体未冻结（`static-guard` 不产 JUnit、device markdown 存在不等于执行、`M-CR-01` ↔ `M_CR_01` 未规范化） | §10.1 冻结机器可读 **evidence manifest** `{rowId, exactHead, lane, testId, status, reportDigest}` + 规范化与 HEAD 绑定规则；"三类"改"四类" |
| 7 | PR body 与 frontmatter 仍呈现为已冻结基线 | frontmatter 改 `pending-operator-decision`；文档顶部加**未冻结告示**，列出 DP-3 未决、consumer 全停、两条不可证明上限 |

§10 由 77 行增至 **85 行 / 17 类**；class 分布 `owner-red` 59（Opus5 31 / Kimi 28）· `sol-blackbox` 22 · `static-guard` 2 · `device` 2。

第 1 项是本轮最该记的：我上一版**自己把 mitigation 说成了兑现**，于是给 operator 的二选一里有一个是假的。诚实披露上限做对了，但"给出一个看起来能消除上限的选项"比不给更危险——它会让拍板的人以为存在一条无代价的严格路线。**当观察面不支持某个保证时，正确的选项集是"接受并写明上限"与"不提供该保证"，而不是发明一个听起来很严格的中间态。**

#### 0.1.8 双路 exact-HEAD 复审修订（v1.8）

acceptance（Sol）与对抗审查（GLM）首次**绑定同一 exact HEAD** `ecfb322e` 各自出具 `REQUEST_CHANGES`，且结论收敛。

| # | 问题 | 修订 |
|---|---|---|
| 1 | **事实错误 + 载体缺失**：§8.4 写"qwy 重启后 `elapsedRealtime` 归零"——官方语义是**自设备 boot** 计时，进程重启不重置；且 `EnvironmentLease` 没有任何能判定单调值可比性的字段；`M-LS-07`（`RELEASE_INCOMPLETE`）与 `M-LS-12`（`EXPIRED`）对同一重启场景无优先级 | 更正事实；冻结载体 **`applyOwnerGeneration`** 并给出充分性证明（设备 reboot 必然重启 owner 进程 ⇒ **generation 变化 ⊇ 时钟纪元变化**，故不会漏检）；明示其过度检测为**策略**（**该行原写"零代价"，经 v1.9 收窄后又被 v1.11 再次推翻——现行表述见 §8.4，只断言"不回滚已提交配额"**）；冻结恢复终态优先级（**该优先级被 v1.9 第 1 项改为 state-aware，原"对每个非 `RELEASED` lease"过宽**）；新增 `M-LS-13/14` |
| 2 | §20 仍写"仅两件、均不阻塞 contract 冻结"，与同一 HEAD 的顶部告示、DP-2 时间窗、DP-3 停工门直接矛盾 | 换成**逐 DP 的阻塞范围表**（PR-1 identity / contract 与 #3–#6 / 真机验收三列），并声明该表是唯一权威 |
| 3 | GitHub #7 仍把已撤回的 READY-only 写成"结构性关闭"——**durable body 本身就是提问的一部分**，会把无效选项直接递到 operator 面前 | 立即改为真实 A/B，`READY` 只作共用 mitigation |
| 4 | `M-CO-03` 被标 `not-testable`，但它**可触达**，只是终态待 DP-3；而 manifest 只有 `passed/failed/skipped` | 区分 `not-testable`（永久上限）与 **`deferred:<DP-x>`**（可触达、待拍板）；manifest 增 `deferred` + `deferredOn`，**只要存在 deferred 记录最终 gate 一律失败** |
| 5 | §8.6.1 说 `viewIdResourceName` 仅见于 `DebugExporter.kt` | 更正：另见 `NodeFinder.kt:81` 的 `findByViewId`（无调用点 dead code）；"不进决策路径"的结论不变 |

§10 由 85 增至 **87 行 / 17 类**；`owner-red` 61（Opus5 31 / Kimi 30）· `sol-blackbox` 22 · `static-guard` 2 · `device` 2。

第 2、3 项是同一种传播病的第四、五次复发：我改了顶部告示与 DP-3，却没回头改 §20；改了 #6，却没改 #7。**改一条被引用的结论时，"哪些地方引用了它"必须是可枚举的**——所以 §20 现在把阻塞范围做成表并声明自己是唯一权威，DP-3 的"选定后需同步锚点"列表也已包含 §20 与 #6/#7。

第 1 项则是另一类：**我用一个事实错误（进程重启会重置 elapsedRealtime）推出了一个恰好安全的结论**。结论安全不代表推理可用——按错误前提写的测试会把错误的平台模型冻结进实现。这次改成先摆平台事实，再证明所选载体为何**充分**，而不是让它碰巧够用。

#### 0.1.9 acceptance 第五轮修订（v1.9）

| # | 问题 | 修订 |
|---|---|---|
| 1 | **v1.8 自己造的死锁**：恢复规则写成"对每个非 `RELEASED` lease"一律套用，于是 `REVOKED` lease 在重启且干净性可证时被改写为 `EXPIRED`——而 `EXPIRED` 的出口是"原 caller 调 `release`"，那个 caller 已被撤销无法调用；provider 内部自清理又只对 `REVOKED` 冻结。出口消失 | 恢复改为 **state-aware 分流表**：`REVOKED`/`RELEASE_INCOMPLETE` **原样保留**（出口与 caller 授权、时钟均无关）；`RELEASING` 幂等重放；**通用 `→ EXPIRED` 的作用域显式限定为 `ACQUIRING`/`ACTIVE`**——只有这两态的出口依赖 caller 在 deadline 前动作。新增 `M-LS-15/16/17` |
| 2 | §10.1 散文承诺 `deferred`+`deferredOn`，紧挨着的 canonical JSON 仍是旧三值 status 且无该字段；`deferred` 时 `testId`/`reportDigest` 的必填性未定义 | JSON 与逐 status **必填性表**一并冻结：`deferred` 行**必须缺省** `testId`/`reportDigest`（那一行还没有可执行断言，填了就是假装跑过不存在的报告），必填 `deferredOn`，且存在任一 `deferred` 即最终 gate 失败 |
| 3 | 传播残留：§21 仍写"两项"（实际 3）；Task 7 GREEN 仍写 53 行且把 owner-red 说成"触达不到"；Task 7 Verify 漏 `deferred`；PR body 写 lease 12 字段（实际 13） | 逐项同步；Task 7 GREEN 改为"不该由 Sol 跨 owner 去测"，不再说成无法测试 |
| 4 | "false-red 实际不损失任何东西"与紧随其后承认多一次 release/reacquire 自相矛盾 | 收窄为"不损失可信计数，但有可用性成本"（**该收窄结果已被 v1.11 第 3 项再次推翻**——它依赖一个 spec 不冻结的前提；现行表述见 §8.4） |

§10 由 87 增至 **90 行 / 17 类**；`owner-red` 64（Opus5 31 / Kimi 33）· `sol-blackbox` 22 · `static-guard` 2 · `device` 2。

第 1 项是 lease 机器里**同一种"出口不可达"的第三次**：`STALE_LEASE` 挡住 `EXPIRED→RELEASING`（v1.6 修）、撤销后要求失权 caller 去 release（v1.7 修）、恢复把 `REVOKED` 改写成出口不可达的 `EXPIRED`（本轮修）。三次的共同形状是：**我在定义"某状态如何离开"时只看该状态本身，没有检查有没有别的规则会把它改写成另一个状态**。

因此本轮不只修实例，还把判据写进 §8.4：**恢复不得改变任何状态的出口可达性**——任何会重写 lease 状态的规则，都必须先确认目标状态的出口对当前授权主体仍然可达。这条比"再修一个 case"更值得留在文档里。

#### 0.1.10 acceptance 第六轮修订（v1.10）

| # | 问题 | 修订 |
|---|---|---|
| 1 | **[P1]** §8.4 的 state-aware 分流表写对了，但**编码这条规则的矩阵行没跟着改**：`M-LS-07` 仍写"非 `RELEASED` lease"、`M-LS-12/13/14` 未限定状态，于是 `REVOKED` + restart 会同时命中 `M-LS-07/12`（→`RELEASE_INCOMPLETE`/`EXPIRED`）与 `M-LS-15`（→保持 `REVOKED`），预期终态互相冲突 | 四行谓词全部收窄到具体状态集：`M-LS-07/12/13` 限 `{ACQUIRING, ACTIVE}`，`M-LS-14` 限 `ACTIVE`，并各自写明"不适用于其他状态"及去向；`M-LS-15/16` 明确要求干净性可证与不可证**两种都测** |
| 2 | **[P2]** §10.1 标为 `json` 的载体含注释、联合类型占位与互斥字段并存，**不是 verifier 能 `JSON.parse` 的实例**；PR body 仍是旧六字段 | 换成**两条真实可解析实例**（`passed` + `deferred`），并冻结容器形态与**逐 lane 的产出路径**（各 lane 写各自片段，不共写一个文件，避免跨 owner 写入）；同一 `rowId` 出现在多个片段即冲突失败；PR body 同步 |
| 3 | 作者自查（Sol 未提）：§8.4"任何跨越 generation 断裂的在飞 attempt 都已不可能满足可信谓词"——前提带条件"连续性不可证"，结论却丢了条件 | 补全三步推导（**该行所述"条件恒成立"的论据已被 v1.11 第 3 项推翻**——spec 不冻结 observer 与 owner 共址，现有 `PrefsDirectoryObserver` 就在被 hook 的目标进程；现行表述见 §8.4） |

**第 1 项是同一种传播病的第六次**，而且这次特别值得记：§8.4 的分流表**里面就引用了 `M-LS-07` 与 `M-LS-12`**——我做了从规则指向行的单向引用，却没有反过来更新行本身。

这暴露了四张映射的真实边界：**集合相等、列数、编号连续这些机械校验只能证明"存在"，证明不了"语义一致"**。规则改了而编码它的行没改，所有机械检查依然全绿。目前唯一的对策是像 DP-3 那样写穷举式同步清单，但它靠人执行，仍会漏。**若要根治，需要让每条规则与它的矩阵行之间存在可被构建校验的双向绑定**——本 spec 暂不引入该机制，此处如实记录为已知残留风险，不假装已解决。

#### 0.1.11 acceptance 第七轮修订（v1.11）

| # | 问题 | 修订 |
|---|---|---|
| 1 | **[P1]** 上一轮收窄谓词时漏了一个组合：`ACTIVE + 设备 reboot + 干净性不可证` 同时被 `M-LS-07` 指向 `RELEASE_INCOMPLETE`、又被 `M-LS-13`（委派给 `M-LS-12`）指向 `EXPIRED` | `M-LS-13` 补上"干净性**可证**"条件，并写明不可证时**无论进程重启还是设备 reboot 一律先落 `M-LS-07`** |
| 2 | **[P2]** 把伪 JSON 换成可解析实例时，原 sketch 里 `sha256(source report)` 的语义**被一并删掉**，而 normative 表只写"必填"，未冻结算法、原始报告定位、以及同报告内 `testId`↔`status` 的绑定 | 冻结三条：`SHA-256` 对原始报告**字节流**求摘要（小写 hex）；摘要必须能在该 lane 的报告目录下找到**字节完全一致**的文件（找不到被指向物即无证据）；记录的 `testId` 必须出现在该报告中且 outcome 与 `status` 一致（否则 manifest 可以声称 `passed` 而报告写着 failed） |
| 3 | **[P2]** v1.10 新补的推导断言"observer 必随 owner 进程死亡"，但 §6.6 **刻意不冻结** owner/transport 技术，现有 `PrefsDirectoryObserver` 恰恰位于被 hook 的目标进程 | 撤回该论据；把 false-red 代价写全为两部分——**必然**多一次 release/reacquire 往返，**可能**丢掉一个本还可满足可信谓词的在飞 attempt；并说明明知如此仍选 `applyOwnerGeneration` 是"拿确定的可用性代价换确定的安全性" |

第 3 项是**同一段第三次被收窄**：v1.8 写"零代价"→ v1.9 收窄为"不损失可信计数"→ 本轮发现连这个都依赖一个 spec 明确不冻结的前提。

值得记的不是"又改了一次措辞"，而是**这三次都是同一个动作**：我先得出结论，再去找一个能支撑它的前提，而不是先确认前提再看能推出什么。前两次找到的前提碰巧成立，这次的不成立。**当一个说法需要被反复"收窄"时，问题通常不在措辞，而在它原本就是先有结论后有论据。** 最终版不再试图论证代价小，而是直接把代价列全，再说明为什么仍然接受它。

#### 0.1.12 acceptance 第八轮修订（v1.12）

| # | 问题 | 修订 |
|---|---|---|
| 1 | **[D1]** §8.4 段末仍留着 v1.9 的旧口径"准确的说法是不损失可信计数"——**正是 v1.11 明确撤回的那句**，与同段前文和修订记录直接冲突 | 删除该句；改为唯一仍可断言的事实：强制过期**不回滚任何已提交的 `TrustedQuotaEntry`**，并写明这与"不损失可信计数"**不是一回事**。§0.1.8/§0.1.9 两处指向该口径的 changelog 行同步标注已被 v1.11 推翻 |
| 2 | **[D2]** PR body 仍写六轮且漏列 `605b4dd9`；GitHub #6 的 manifest 形状未携带本轮新冻结的 raw-report SHA-256 / lane 内定位 / 同报告 `testId`↔`outcome` 绑定，且链接指向 pre-merge `blob/main`，会把 verifier 引回旧载体 | 两处 durable 入口同步 |

**这是同型传播病的第七次，但它是一个新的子型，值得单独命名：*部分块替换残留*。** 前六次是"改了 A 没改引用 A 的 B"；这次是**改了同一段落的后半，把前半那句已被自己推翻的话留在了原地**——而且它就落在 changelog 写着"本段已被收窄两次"的那一段里。

`Edit` 的 `old_string` 边界恰好停在那句之前，替换成功、检查全绿、语义自相矛盾。**机械校验对"同一段内部的自相矛盾"完全无感**，这和 §0.1.10 记的"集合相等证明不了语义一致"是同一个盲区的另一个切面。

可操作的收敛：**推翻一个说法时，替换范围必须覆盖整个论证段落，而不是被推翻的那一句**——因为支撑它的铺垫句往往紧邻其前后，且同样已经失效。

#### 0.1.13 operator 决策落地与三次事实更正（v1.13）

本轮是迄今最重的一次 delta：落 operator 的 DP-1/2/3、新增 `INV-29`、解除 contract 冻结（`#3/#4/#5/#6`）。决定本身逐字记录在 §21.0，落实锚点见 §21 清单。

**但这一轮同时出了三个作者侧事实错误，全部由非作者发现**，记录在此因为它们的形态不同、教训也不同：

| # | 错误 | 发现者 | 形态 |
|---|---|---|---|
| 1 | §8.6.1.1 称「分数在整个证据集中从未变化 → 结果是低基数标签」 | GLM | **结论作用域 > 样本作用域**：33 份是少数几次 session 的连续帧，同一 run 内分数本就不变 |
| 2 | §8.6.1.1 样本路径写成 `faketest-f002/…`（该路径下实为 0 份 XML） | Sol | **张冠李戴**：把一条*目录名搜索*输出里的前缀，安到另一条 *XML 搜索*的结果上 |
| 3 | §0.1 版本表 / changelog 未记录本轮实质 delta | GLM | **穷举清单本身不穷举**：§21 列了 13 项 DP-3 锚点，却漏了文档级 version bookkeeping 这一类 |

三条都不改变 operator 的任何决定，也不影响 INV/AC 的正确性；但它们共享一个根：**在"我已经验证过了"的自信状态下，把推断当成了查证。**

- 第 1 条：数据是真的，结论超出了数据的**构成**能支撑的范围。
- 第 2 条：最严重的一条。provenance 的全部意义是让**别人**能复核；写一个复核不到的地址，等于把"可验证"降级成"请相信我"——而这正是本 spec 反复反对的东西。
- 第 3 条：与 §0.1.3 第 1 项、§0.1.12 是同一族（改了结论没扫全引用点），但子型更隐蔽：**我写了一份自称穷举的清单，清单本身漏了一类**。

可操作的收敛，三条各一：

1. **陈述分布类结论前，先问样本构成能否支撑**——"数据是真的"不等于"结论是真的"。
2. **provenance 必须当场复核可达性**：写进文档的每个路径 / 哈希，落笔时就要用它本身跑一次，而不是从上下文里的相邻输出誊抄。
3. **穷举清单要标注它穷举的是哪一维**——§21 那份穷举的是"DP-3 语义锚点"，不是"本次 delta 的全部引用点"；把维度写出来，漏掉的那一类才会显形。

**最后**：三条全部由非作者查出，作者自查两轮均未发现。这不是运气，是**分工的价值**——作者验证的是"我写的是否自洽"，非作者验证的是"它是否与外部世界一致"。后者不能靠前者更努力来替代。

#### 0.1.14 owner transfer 传播与 INV-29 evidence carrier（v1.14）

两项由非作者（Sol）发现的 propagation blocker：

| # | 问题 | 修订 |
|---|---|---|
| 1 | **owner 真相未传播。** operator 已把 #4 从 Kimi 转给 DeepSeek Flash（主 Thread `0001786311069292-001378-b555f28c`：「完成调度设计后，把 kimi 的任务给 deepseek-flash 吧」），但**现行规范**仍在 §10.1 的 33 行 owner 台账、§12.1、Task 3/6/7/8、§15 PR 图、§16 issue 图、§17 角色、§19 completion gate 里写 Kimi | 现行区 52 处全部改为 DeepSeek Flash；**§0.1.x 历史修订记录中的 5 处保留**——它们描述的是当时的真实状态，改掉就是伪造历史 |
| 2 | **`INV-29` 有规则、没有证据载体。** 该不变量列出了旧安装探测、迁移桥 round-trip、回滚、CSV 负例与静态扫描，但 §10 / §10.1 里**一行都没有**；AC-10 与 §19 仍写 `INV-01..28` | 新增 `appid-cutover` 类 5 行（`M-AC-01..05`），按 Sol 的「跨 owner 必须拆行」原则分派：探测 / 迁移桥 / CSV 负例 = Opus5 `owner-red`；回滚演练 = Sol `device`；仓库-日志扫描 = Sol `static-guard`。§10 由 90 行/17 类增至 **95 行/18 类**；`owner-red` 64→**67**（Opus5 31→34 / DeepSeek Flash 33）· `device` 2→**3** · `static-guard` 2→**3** · `sol-blackbox` 22 不变。AC-10 与 §19 同步为 `INV-01..29` |

第 2 项值得单独记，因为它是**假闭合的标准配方**：`INV-29` 已经写进不变量表、§21 清单第 11 项还准备把 GitHub #6 的覆盖措辞改成 `INV-01..29`——若不先补台账行，就会得到「issue 宣称覆盖 29 条、ledger 只能证明 28 条」的状态。**宣称覆盖与能够证明覆盖是两件事**；机械同步文案会把前者伪装成后者。

一个作者侧自查漏网：本轮我最初把 `M-AC-05` 的 owner 写成 Opus5，但它的入口是 `scripts/check-forbidden-boundaries.sh`——按 §12.1 那是 **Sol 的独占文件**。在一次专门修正 owner 传播的 delta 里写错 owner，说明"正在处理某类问题"并不使人对该类问题免疫。已在推送前自查修正。

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

次序为 **bind-first**：先由 Binder 证实调用方身份，再让 operator 授权。

配对是**双向**的，两侧各有一份独立名单，都需要 operator 的显式批准（§6.5.3）：

1. Auto 以显式 `ComponentName` bind 千网游的 `EnvironmentControlService` 并调用 `discover()`。
2. 千网游按 `Binder.getCallingUid()` 解析调用方 applicationId 与**当前** signer SHA-256（并顺带记录 versionCode 供审计），**在这次调用内把它们快照进 `PendingPairingCandidate`**，向 Auto 返回 typed `NOT_PAIRED`。
3. **qwy 侧批准**：operator 在千网游的“自动测试协作”页看到**这条已由 Binder 证实的**候选记录；点允许后千网游持久化 `PairingRecord`（caller allowlist）。
4. **Auto 侧批准**：Auto 重试 `discover()` 前先解析所绑定 service 所属包的 applicationId 与当前 signer。若这对身份**未出现在本地 `ProviderPairingRecord` 中**，Auto 停在本地 `NOT_PAIRED` 预检态，展示 applicationId、当前 signer 摘要与来源，**等 operator 显式批准**后才写入（provider allowlist）。批准前拿到的 capability 只能展示，不进入任何可信判定。
5. 两侧都批准后，Auto 取得千网游版本、支持模式、profile/schedule、连续性覆盖等级，进入可用状态。
6. 任一侧未配对、签名变化、协议不兼容或千网游不可用时，Auto 停在预检页并给出可操作错误；不开始 CellRebel。

**为什么 Auto 侧也必须有一次显式批准**：若 Auto 首次连接就把当时看到的 signer **自动**落为可信（silent TOFU），那么“真千网游未安装、同包名替代实现应答 bind”这一负例根本不会失败——替代者在第一次连接时就成了被信任的环境权威，此后每次比对都“一致”，而它可以返回伪造的 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` 与稳定 revision 让 §6.4 全部谓词成立。反向校验只有配上显式信任根才真正成立。该批准把信任建立变成可见、可审计、需人确认的动作，但**不等于证明了 publisher identity**，安全上限见 §6.5.3。

**为什么是 bind-first 而不是先在 UI 里挑 App**：调用方身份必须来自 `Binder.getCallingUid()`（INV-02），UI 侧自行扫描包列表既是较弱的真相源，又要求千网游反向声明 `<queries>` 才能在 Android 11+ 看到 Auto。bind-first 让身份来自唯一可信来源，同时使反向 `<queries>` 不再是核心流程的结构性依赖。若将来产品需要“在任何 bind 发生之前就列出候选 Auto 安装”，那条路径才需要千网游侧 `<queries>`，届时单独评审。

**为什么必须在调用内快照，而不是只存 UID 稍后再查**：Android 的双向可见性是**不对称**的。A→B 的显式 bind 需要 A 声明 `<queries>`（官方明确“The limited visibility also affects explicit interactions with other apps, such as starting another app's service”，activity 的豁免不延伸到 service）；B→A 则在 bind 发生时自动授予（“Any app that starts or binds to a service in your app”）。但**官方文档从未规定这个反向授权的存续期**——在 AOSP 里它是 PackageManager 的内存态，随包移除清理。因此千网游只能在“调用正在进行、授权确定有效”的窗口内完成解析并落快照；把 UID 存下来等 operator 稍后批准时再反查，可能拿到 `NameNotFoundException`，也可能撞上 UID 在卸载重装后被复用。快照的是身份三元组，不是 UID。

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

**ComponentName 与 applicationId（冻结）**：`name.caiyao.fakegps.integration.v1.EnvironmentControlService` 是 **class name**；`ComponentName` 的 package 半边是运行时 applicationId，而千网游 debug 构建带 `applicationIdSuffix ".bench"`。因此 Auto 必须把目标显式建模为二选一，不能硬编码单一包名：

| 目标 | applicationId | class name |
|---|---|---|
| production | `name.caiyao.fakegps` | `name.caiyao.fakegps.integration.v1.EnvironmentControlService` |
| bench | `name.caiyao.fakegps.bench` | 同上（class name 不随 suffix 变化） |

两个 applicationId 各自独立配对（§6.5），Auto 一次只绑定一个目标并把该选择写进 `PlanSnapshot`。

**minSdk（冻结）**：contract library `minSdk = 24`；Auto 保持 `minSdk = 26`；千网游保持 `minSdk = 24`。共享库取两者下界是硬约束——若 contract 取 26，`minSdk 24` 的千网游无法依赖它，AGP 直接构建失败。

**package visibility（冻结）**：Auto `targetSdk 35`，在 Android 11+ 下要显式 bind 千网游必须先在 Manifest 声明可见性。当前 `Faketest@48d8ec9` 的 `<queries>` 只有 `com.cellrebel.mobile` 与 `com.hopefactory2021.fakegpslocation`，**不含千网游任一 applicationId**，因此 Auto 的 `AndroidManifest.xml` 必须新增：

```xml
<queries>
    <package android:name="name.caiyao.fakegps" />
    <package android:name="name.caiyao.fakegps.bench" />
</queries>
```

该文件此前不在任何 task 的 Files 清单内，现已纳入 §12 目录与 §12.1 owner matrix（Opus5）。千网游侧同一集成路径此前已被该机制影响过一次（其 Manifest 注释记录 ContentProvider 传输被 Android 11+ 包可见性完全阻断），因此这条不是理论风险。

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

### 6.2 枚举：稳定 wire code，不跨进程传枚举身份

枚举**不得**以 Kotlin enum 形态直接跨 Binder 传输。v1 的每个枚举常量绑定一个永久稳定的 `Int` wire code；DTO 字段承载 `Int`，两侧各自用显式 `fromWire()` 解码。

```kotlin
enum class VerificationLevelV1(val wire: Int) {
    SYSTEM_MOCK_INDEPENDENTLY_VERIFIED(1),
    HOOK_UNVERIFIED(2),
    NONE(3),
    ;
    companion object {
        /** 未知 code = 对端更新且不兼容 → fail-closed，绝不猜成可信。 */
        fun fromWire(code: Int): VerificationLevelV1? = entries.firstOrNull { it.wire == code }
    }
}

enum class ContinuityCoverageV1(val wire: Int) { FULL(1), PARTIAL(2), NONE(3) }
enum class DeliveryModeV1(val wire: Int) { SYSTEM_MOCK(1), HOOK(2) }
enum class ScheduleDecisionV1(val wire: Int) { ALLOWED_NOW(1), WAIT_UNTIL(2), DENIED(3) }
```

规则：

- 可信策略必须显式匹配 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED`；禁止枚举顺序、`ordinal`、`>=` 或“非 NONE 即可信”。
- **禁止把枚举本体交给 `@Parcelize` 自动编解码。** kotlin-parcelize 的 `IrEnumParcelSerializer` 写入 `Parcel.writeString(value.name)`、读出 `EnumClass.valueOf(readString())`。后果：重排常量顺序是 wire-safe 的（ordinal 不上线），但**改名是破坏性变更，新增常量会让旧读者抛 `IllegalArgumentException`**——异常从生成的 `createFromParcel` 抛出，表现为 unparcel 崩溃，而不是 INV-03 要求的 typed fail-closed。两个 App 独立发布、版本必然 skew（§10 version 行），所以自动编解码在本方案里不可用。承载 `Int` + 显式 `fromWire()` 把 skew 变成可判定的业务错误。
- `fromWire()` 返回 `null` 时一律 fail-closed：可信路径直接判不可信，握手路径返回 `INCOMPATIBLE_PROTOCOL`。
- v1 已分配的 wire code 永久不可回收、不可改语义；新增常量只能追加新 code，且必须先通过 §6.7 兼容矩阵。

### 6.3 核心 DTO

```kotlin
@Parcelize
data class CapabilitySnapshotV1(
    val protocolVersion: Int = 1,
    val serviceVersion: String,
    /** DeliveryModeV1 wire code 集合；升序去重，保证 wire 表示确定。 */
    val supportedModeWires: List<Int>,
    /** VerificationLevelV1 wire code 集合；升序去重。 */
    val supportedVerificationLevelWires: List<Int>,
    val continuityCoverageWire: Int,
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
    val requiredVerificationWire: Int,
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
    val verificationLevelWire: Int,
) : Parcelable

@Parcelize
data class EnvironmentObservationV1(
    val leaseId: String,
    /** 本次观察所属 lease 当前生效意图的 canonical digest；见 §6.3.1。绑定观察与意图，防止把完成记到错误地址。 */
    val acceptedIntentHash: String,
    /** 仅供人读与审计；禁止参与可信判定（§6.4.2）。 */
    val observedAtEpochMs: Long,
    /** 唯一可比时钟：SystemClock.elapsedRealtime()。所有 bracketing 用它。 */
    val observedAtElapsedRealtimeMs: Long,
    val environmentRevision: Long,
    val environmentFingerprint: String,
    val continuityCoverageWire: Int,
    /** 仅供人读与审计。 */
    val continuitySinceEpochMs: Long?,
    /** 连续性窗口起点，elapsedRealtime；可信判定使用本字段。 */
    val continuitySinceElapsedRealtimeMs: Long?,
    val deliveryModeWire: Int?,
    val verificationLevelWire: Int,
    val effectiveLatitude: Double?,
    val effectiveLongitude: Double?,
    val isMock: Boolean?,
    val scheduleDecisionWire: Int,
    val evidenceRefs: List<String>,
) : Parcelable
```

**exact schema 无省略**：本节与 §6.3.2 列出的字段就是全部字段，逐字段与实现一一对应。v1 的任何 Parcelable 中**不出现 Kotlin enum 类型**（含 `Set<enum>`/`List<enum>`）；枚举一律以 `...Wire: Int` 或 `...Wires: List<Int>` 承载，集合型升序去重。散文说明不得覆盖或补充 exact schema——若某字段没写在这两节里，它就不在 v1 里。`check-contract-v1.sh` 必须包含一条静态检查：contract 模块的 `@Parcelize` 类中出现任何 enum 类型字段即失败。

#### 6.3.1 canonical intent digest（冻结算法）

`acceptedIntentHash` 是 `EnvironmentIntentV1` 的 canonical digest，两侧必须独立算出同一值：

```text
canonical = 按下列顺序，对每个字段依次追加：
              uint32be(byteLength(fieldBytes)) || fieldBytes
            无分隔符、无尾随字节。

  runId                     : UTF-8 bytes，原样
  attemptId                 : UTF-8 bytes，原样
  profileRef                : UTF-8 bytes，原样
  scheduleRef               : UTF-8 bytes，原样
  latitude                  : ASCII 定点十进制，恰好 7 位小数，半值向偶数舍入，
                              负号保留，无 '+'，无指数，无千分位
  longitude                 : 同上
  requiredVerificationWire  : ASCII 十进制
  notBeforeEpochMs          : ASCII 十进制
  deadlineEpochMs           : ASCII 十进制

acceptedIntentHash = lowercase hex of SHA-256(canonical)
```

**为什么是长度前缀而不是分隔符连接**：四个 ref 字段是自由字符串，用任何固定分隔符连接都可构造碰撞——例如以换行连接时，`runId="a\nb", attemptId="c"` 与 `runId="a", attemptId="b\nc"` 产生**完全相同**的 canonical 字节，于是两个不同意图共享同一 `acceptedIntentHash`，INV-23 的绑定被绕过。长度前缀让编码单射，碰撞不再依赖"字段里恰好没有分隔符"这种运行期巧合。禁止改回分隔符方案，也禁止用"契约上不允许出现换行"来代偿——那是把不变量的正确性押在输入校验上。

禁止用 `toString()`、`hashCode()`、`Objects.hash()`、任何 JSON 序列化或 Parcel 字节作为 digest 来源——它们都不保证跨版本/跨进程稳定。7 位小数（约 1.1 cm）在冻结容差之下，确保 digest 不会因浮点文本化差异漂移。

必测（both sides，逐条独立断言）：

- 上述**分隔符碰撞对**必须产生**不同** digest；
- 负坐标、`0.0`/`-0.0`（必须归一为同一表示）、需要半值向偶数舍入的边界值；
- 四个 ref 含换行、制表符、emoji、以及多字节字符时两侧 digest 一致；
- 空 ref 在业务上非法，导入/预检阶段即拒绝（这是产品校验，不是 digest 的正确性来源）。

#### 6.3.2 其余 DTO exact schema

```kotlin
@Parcelize
data class PreflightRequestV1(
    val intent: EnvironmentIntentV1,
    val idempotencyKey: String,
    val callerProtocolVersion: Int,
) : Parcelable

@Parcelize
data class PreflightReportV1(
    val acceptedIntentHash: String,
    val scheduleDecisionWire: Int,
    /** scheduleDecision == WAIT_UNTIL 时必须非空，其余情况必须为 null。 */
    val waitUntilEpochMs: Long?,
    val achievableVerificationLevelWire: Int,
    val continuityCoverageWire: Int,
    val environmentRevision: Long,
    /** ContractErrorCodeV1.wire 列表；空表示预检通过。 */
    val blockingReasonWires: List<Int>,
) : Parcelable

@Parcelize
data class ApplyRequestV1(
    val intent: EnvironmentIntentV1,
    val idempotencyKey: String,
    val callerProtocolVersion: Int,
) : Parcelable

@Parcelize
data class ObserveRequestV1(
    val leaseId: String,
    val operationId: String,
    /** Auto 本地算得的 intent digest；与服务端当前 lease 意图不符时返回 ENVIRONMENT_DRIFT。 */
    val expectedIntentHash: String,
) : Parcelable

@Parcelize
data class ReleaseRequestV1(
    val leaseId: String,
    val operationId: String,
    val idempotencyKey: String,
) : Parcelable

@Parcelize
data class ReleaseReceiptV1(
    val operationId: String,
    val idempotencyKey: String,
    val leaseId: String,
    val releasedAtEpochMs: Long,
    val environmentRevision: Long,
    /** false = 环境未能证明清理完成 → Auto 必须走 INV-21 暂停与人工恢复。 */
    val releaseComplete: Boolean,
    val residualReasonWires: List<Int>,
) : Parcelable
```

`EnvironmentIntentV1` 与上述全部类型都必须有对应 `.aidl` parcelable 声明与 `.kt` 实现，并纳入 §12 文件所有权。**实现者不得自行发明字段**：任何需要新增字段的发现都回本 spec 修订，不在 consumer branch 私改。

所有 request 另含 `idempotencyKey` 或稳定 operation id。

#### 6.3.3 `ContractErrorCodeV1` 全量映射（表 1）

每个 code 绑定永久稳定的 `wire: Int`，规则同 §6.2。**本表是 v1 typed failure 的完备集**：spec 中任何一条 INV 或 §10 矩阵行所要求的 typed failure，都必须能在这里找到唯一对应的 code；找不到即是 spec 缺陷，回本表补，不得由实现者复用近义 code 或私自发明。

| wire | code | 触发条件 | 承载的 INV / §10 |
|---|---|---|---|
| 1 | `NOT_PAIRED` | 调用方不在 caller allowlist（或 Auto 侧 provider 未批准，本地态） | INV-02；pairing 行 |
| 2 | `CALLER_NOT_ALLOWED` | 身份解析失败或被拒：`getPackagesForUid` ≠ 1 个包、多签名者、signer 与快照不符、已 revoke | INV-02；bypass/pairing 行 |
| 3 | `INCOMPATIBLE_PROTOCOL` | `protocolVersion` 不在支持集合，或**载荷枚举**（`VerificationLevelV1`/`ContinuityCoverageV1`/`DeliveryModeV1`/`ScheduleDecisionV1`）出现未知 wire code | INV-03,04,19；version 行 |
| 4 | `CAPABILITY_UNAVAILABLE` | 请求的模式/profile/schedule 当前不可用 | INV-01,03 |
| 5 | `SCHEDULE_DENIED` | `scheduleDecision == DENIED` | INV-17；recovery 行 |
| 6 | `CONTINUITY_NOT_FULL` | coverage 为 `PARTIAL/NONE` | INV-08,09；crash/bypass 行 |
| 7 | `LEASE_CONFLICT` | 与另一 caller 或另一 intent 的 active/未收敛 lease 冲突 | INV-14,16；concurrency 行 |
| 8 | `STALE_LEASE` | 该 leaseId 对**本次操作**不可用：非本 caller 所有、已 `RELEASED`，或对 `apply`/`observe` 而言处于 `EXPIRED`/`REVOKED`/`RELEASE_INCOMPLETE`。**`release` 例外见下** | INV-14；release 行 |
| 9 | `ENVIRONMENT_DRIFT` | `expectedIntentHash` 与当前 lease 生效意图不符，或有效环境已漂移 | INV-08,23；intent 行 |
| 10 | `RELEASE_INCOMPLETE` | release 无法证明清理完成 | INV-21；release/recovery 行 |
| 11 | `INTERNAL_FAILURE` | 服务端内部错误；**以及未知 `ContractErrorCodeV1` wire 的唯一 fallback** | INV-03 |
| **12** | **`IDEMPOTENCY_CONFLICT`** | **同 `idempotencyKey` 但 payload digest 不同** | **INV-13；`apply 同键异 payload` 行** |
| **13** | **`REQUEST_INVALID`** | **请求结构性非法：必填 ref 为空、坐标越界、`deadline ≤ notBefore`** | **INV-04；contract round-trip 负例** |

**两个方向必须分清**：上表全部是**服务端→调用方**的失败码。`PreflightReportV1` 里 `scheduleDecision == WAIT_UNTIL` 却缺 `waitUntilEpochMs`，是**应答**结构性非法，不是请求非法，因此**不能**用 `REQUEST_INVALID` 表示——那会把服务端缺陷伪装成调用方错误。冻结消费方处置：Auto 收到自相矛盾的应答一律 **fail-closed**，按 §6.4.1 矛盾 tuple 处理（不进入可信判定、不启动 CellRebel、写未验证并记 typed reason），并在预检页给出可操作错误。同类规则适用于任何应答级矛盾。

12 与 13 由本次全量映射审计发现：INV-13 与 §10 早已要求"同键异 payload → typed conflict"，但清单里只有语义不同的 `LEASE_CONFLICT`；结构性非法请求此前只能落到 `INTERNAL_FAILURE`，既不可诊断，也会把调用方错误伪装成服务端故障。**两者都必须在 v1 冻结时就位**——事后追加 code 意味着旧读者按 §6.2 规则把它们 fail-closed 成 `INTERNAL_FAILURE`，语义永久丢失。

**`STALE_LEASE` 不得挡住恢复路径。** §8.4 要求 `EXPIRED` 经 `RELEASING` 收敛，若 `release` 对它返回 `STALE_LEASE`，该迁移就永远走不到，lease 会永久卡在阻挡态。因此冻结：**由 lease 所属 caller 发起的 `release`，在 `ACTIVE`/`EXPIRED`/`RELEASE_INCOMPLETE` 三态下都必须被受理**并驱动状态机；只有"非本 caller"或"已 `RELEASED`"才返回 `STALE_LEASE`。`apply`/`observe` 则对全部非 `ACTIVE` 态返回 `STALE_LEASE`。

**`REVOKED` 不在上述三态里，因为它根本不可达。** 撤销的第一效果就是让该 caller 的每次 Binder 调用都失败（§6.5 要求逐次匹配 active `PairingRecord`），所以"让原 caller 去 release 一个 `REVOKED` lease"是自相矛盾的——它连调用都进不来。**两侧撤销必须分开定义**：

| 撤销方 | caller 还能调用 qwy 吗 | lease 清理由谁驱动 |
|---|---|---|
| **qwy 撤销 caller**（`PairingRecord`） | 否 | **qwy 内部自清理**：由 provider 直接把 lease 从 `REVOKED` 推过 `RELEASING → RELEASED`。qwy 拥有环境，不需要 caller 参与握手 |
| **Auto 撤销 provider**（`ProviderPairingRecord`） | 是（Auto 仍被 qwy 授权） | Auto 正常调用 `release`，走上面三态路径 |

**不为被撤销的 caller 保留任何 post-revoke 能力。** 给一个已失权的调用方开一条"仅用于清理"的口子，等于在鉴权面上开洞去解决一个 provider 自己就能解决的问题——qwy 本来就是环境的唯一权威。Auto 侧在 run 进行中遇到 `CALLER_NOT_ALLOWED`/`NOT_PAIRED`，一律暂停计划并暴露人工恢复，**不得假定环境已干净**，也不负责清理。

`IDEMPOTENCY_CONFLICT` 与 `LEASE_CONFLICT` 不可互相替代：前者是"你用同一把钥匙提交了不同的内容"（调用方错误，重试无用，必须换 key 或修正 payload），后者是"环境已被别的意图占用"（时序冲突，释放后可重试）。把两者合并会让 Auto 的恢复策略无法区分"该放弃"与"该等待"。

预期业务失败通过 `ServiceSpecificException` 返回稳定的 `ContractErrorCodeV1.wireCode`；Auto 将 wire code 映射为上述 sealed error。未知 code 只能映射为 `INTERNAL_FAILURE` 并 fail-closed，不能猜成兼容。Binder death/`RemoteException` 属于 transport failure，单独进入 recovery；错误 message 只用于安全诊断，不承担机器判定。

#### 6.3.4 `requestDigest` canonical preimage（冻结算法）

`OperationReceipt.requestDigest` 只写字段名是不够的——**没有冻结 preimage，真实 qwy 与 fake-qwy 会各自实现不同的"同一请求"判据，`IDEMPOTENCY_CONFLICT` 就不可互操作**。沿用 §6.3.1 的长度前缀 framing，并按 operation 做 domain separation：

```text
canonicalRequest = uint32be(len(domain)) || domain
                 || uint32be(len(f_1)) || f_1
                 || ... （按下表冻结顺序，无分隔符、无尾随字节）

requestDigest = lowercase hex of SHA-256(canonicalRequest)

domain（ASCII，逐 operation 唯一）:
  apply   → "fakexxx.contract.v1.apply"
  release → "fakexxx.contract.v1.release"
```

| operation | 冻结字段顺序 |
|---|---|
| `apply` | `acceptedIntentHash`（即 §6.3.1 对 `EnvironmentIntentV1` 算出的 digest，ASCII hex） |
| `release` | `leaseId`（UTF-8 原样） |

**明确排除，且每条都有理由**：

| 字段 | 排除理由 |
|---|---|
| `idempotencyKey` | 它是**查找键**不是内容。同键同内容=重放、同键异内容=冲突，键本身进 digest 只会恒等抵消 |
| `operationId` | **逐次调用变化**。若进 digest，每一次合法重试都会被判成 `IDEMPOTENCY_CONFLICT`，恢复路径直接瘫痪 |
| `callerProtocolVersion` | 协议兼容由 §6.7 握手判定；进 digest 会让"重试期间调用方升级"变成伪冲突。v1 已冻结，v2 走新 interface |
| caller 身份 | receipt 查找本就按 `(caller, operation, idempotencyKey)` 三元组作用域，重复计入无意义 |

**必测**：① 同键同内容重放返回原 receipt；② 同键异内容返回 `IDEMPOTENCY_CONFLICT`；③ **domain separation**——构造使 `apply` 与 `release` 的字段字节序列相同的输入，断言两者 digest 不同；④ 长度前缀单射性（同 §6.3.1 的分隔符碰撞对）；⑤ 同一请求换 `operationId` 重试**不得**冲突。

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

# tuple 交叉一致（INV-27）：verificationLevel 单独可信不成立
pre/post.deliveryMode     == SYSTEM_MOCK        # 非空且必须是 SYSTEM_MOCK
pre/post.isMock           == true               # 非空且为 true
pre/post.scheduleDecision == ALLOWED_NOW
pre/post.evidenceRefs     非空

# 观察窗必须真正包住执行窗（INV-27）；全部比较使用 §6.4.2 冻结的单调时钟
pre.observedAtElapsed  <  execution.startedAtElapsed
post.observedAtElapsed >  execution.completedAtElapsed

# 连续性窗口必须覆盖整个观察窗——两侧都要查，且必须是同一段连续性
pre.continuitySinceElapsed  != null
post.continuitySinceElapsed != null
pre.continuitySinceElapsed  == post.continuitySinceElapsed
post.continuitySinceElapsed <= pre.observedAtElapsed

# 意图绑定（INV-23）：以上全部成立仍不足以证明"跑的是这个地址"
pre.acceptedIntentHash  == apply.acceptedIntentHash
post.acceptedIntentHash == apply.acceptedIntentHash
apply.acceptedIntentHash == localDigest(attempt.intent)      # Auto 独立重算，不信任对端回传
pre.effectiveLatitude != null && pre.effectiveLongitude != null
post.effectiveLatitude != null && post.effectiveLongitude != null
haversine(pre.effective,  intent) <= TRUSTED_LOCATION_TOLERANCE_METERS
haversine(post.effective, intent) <= TRUSTED_LOCATION_TOLERANCE_METERS
```

任一不成立：不得写可信配额。

#### 6.4.1 observation 字段 → 信任谓词角色（表 2）

`EnvironmentObservationV1` 的**每一个**字段都必须在下表里有明确角色。"字段存在但没人校验"是可信语义的隐性漏洞：fake provider 只要不被校验就可以自由填写。

| 字段 | 角色 | 可信要求 |
|---|---|---|
| `leaseId` | 谓词 | `== apply.leaseId` |
| `acceptedIntentHash` | 谓词 | `== apply.acceptedIntentHash == localDigest(intent)` |
| `observedAtEpochMs` | 审计 | 人读；**禁止参与判定**（墙钟可被 NTP 拉动） |
| `observedAtElapsedRealtimeMs` | **谓词** | pre 早于 `startedAtElapsed`，post 晚于 `completedAtElapsed` |
| `environmentRevision` | 谓词 | `pre == post` |
| `environmentFingerprint` | 谓词 | `pre == post` |
| `continuityCoverageWire` | 谓词 | `== FULL` |
| `continuitySinceEpochMs` | 审计 | 人读；禁止参与判定 |
| `continuitySinceElapsedRealtimeMs` | **谓词（pre 与 post 两侧）** | 两侧均非空、**彼此相等**、且 `<= pre.observedAtElapsed` |
| `deliveryModeWire` | **谓词** | 非空且 `== SYSTEM_MOCK` |
| `verificationLevelWire` | 谓词 | `== SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` |
| `effectiveLatitude/Longitude` | 谓词 | 非空且在容差内匹配 intent |
| `isMock` | **谓词** | 非空且 `== true` |
| `scheduleDecisionWire` | **谓词** | `== ALLOWED_NOW` |
| `evidenceRefs` | **谓词（仅结构性）+ 审计** | 非空；格式 `qwy:<store>:<id>`。Auto 无法跨 App 解析，故不得声称"证据已独立验证"（§6.4.2） |

粗体五项是本次全量映射新增的校验。前三项（`deliveryMode`/`isMock`/`scheduleDecision`）来自 acceptance review：**只看 `verificationLevel` 会放过自相矛盾的 tuple**——`HOOK + VERIFIED`、`DENIED + VERIFIED`、`isMock=false + VERIFIED` 都能满足旧谓词，直接撞穿 INV-06（Hook 不得进可信账）与 INV-17。后两项（`observedAt` 序、`continuitySince`）是做本表时发现的同类漏洞：旧谓词从不检查两次观察是否真的**夹住**了执行窗，也不检查连续性窗口是否**早于** pre 观察就已建立；两者都可以让一份"前后都 FULL"的证据实际上没有覆盖测试发生的那段时间。

**矛盾 tuple 必须 fail-closed 且必测**。以下每一行都是独立负例，断言不写可信配额：

| 矛盾 tuple | 为何危险 |
|---|---|
| `HOOK` + `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` | Hook 结果冒充独立验证，绕过 INV-06 |
| `isMock=false` + `VERIFIED` | 环境根本没在 mock，却声称已验证 |
| `isMock=null` + `VERIFIED` | 用"未知"冒充"已验证" |
| `DENIED`/`WAIT_UNTIL` + `VERIFIED` | schedule 明确不允许运行，却计入可信 |
| `coverage=FULL` + `continuitySince=null` | 声称完整覆盖却给不出覆盖起点 |
| `continuitySince > pre.observedAt` | 连续性窗口晚于观察，未覆盖测试 |
| `post.observedAt < CellRebel 完成时刻` | "后置观察"发生在完成之前，不构成后置证据 |
| `evidenceRefs` 为空 + `VERIFIED` | 无可复核证据的"已验证" |

#### 6.4.2 时钟与执行窗字段（冻结）

**唯一可比时钟是 `SystemClock.elapsedRealtime()`**（设备级单调，自启动计时，跨进程可比，不受 NTP 校正、时区或用户改表影响）。所有 bracketing 与连续性比较**只能**用它。`...EpochMs` 字段保留但**仅供人读与审计**，不得参与任何可信判定——墙钟在测试窗口内被 NTP 拉回，就能让"后置观察"在数值上早于完成时刻。

契约相应增加（与既有 epoch 字段并存）：

- `EnvironmentObservationV1.observedAtElapsedRealtimeMs: Long`
- `EnvironmentObservationV1.continuitySinceElapsedRealtimeMs: Long?`

`CellRebelExecution` 冻结以下权威字段，全部为 `elapsedRealtime`：

| 字段 | 语义 |
|---|---|
| `startedAtElapsed` | **本次 Start 交互实际发生的时刻** |
| `runningConfirmedAtElapsed` | 首次由 marker 文本证实 RUNNING 的时刻 |
| `completedAtElapsed` | 稳定 COMPLETED（连续两轮分数一致）成立的时刻 |

**不得复用上游 `AttemptOutcome.startedAt`**：它是 engine 侧的审计时间戳，早于 CellRebel 被拉起，用它做 bracketing 会把"启动 App 之前的观察"算成"测试开始之前的观察"，谓词看似成立而实际没有覆盖。

`MIN_RUNNING_EVIDENCE_MS` 的判定为 `completedAtElapsed − runningConfirmedAtElapsed`，不是 `completedAtElapsed − startedAtElapsed`——后者把导航与拉起耗时算进"测试时长"，会让一次假 RUNNING 轻易越过下限。

**`evidenceRefs` 的能力边界（收窄声明）**：它是千网游审计存储内的引用，格式 `qwy:<store>:<id>`，**只在千网游侧、其保留期内可解析**。Auto 持久化并展示它们，供 operator 事后对账；**Auto 无法跨 App 边界机器验证其可解析性**，因此可信谓词只要求"非空"这一结构性条件（provider 必须给出自己的证据出处），不得声称"证据已被独立解析"。

**为什么意图绑定是独立的一条**：`coverage/revision/fingerprint/lease/verificationLevel` 全部只证明"环境在测试全程没有相关变化"，不证明"环境处在**这个 attempt 要求的**位置"。若 apply 静默部分生效、被上一地址的残留状态覆盖、或 lease 复用时意图已切换，上面前七条可以整体成立，而可信配额被记到**错误地址**。本产品的全部价值就是"每地址的可信次数"，因此错记地址是最贵的失败模式，必须由独立不变量排除，而不是依赖其他条件的副作用。

`TRUSTED_LOCATION_TOLERANCE_METERS = 1.0`，冻结为 contract 常量，两侧共用。取值理由：远大于 §6.3.1 的 7 位小数量化误差（约 1.1 cm）与 double 往返误差，因此不会造成假阴性。

**容差不承担归属判定。** 归属由 `acceptedIntentHash`（其中已含 `attemptId`/`runId`）与 task identity 负责；容差只回答"环境是否真的落在这个意图要求的坐标上"。因此**不对计划内地址的最小间距做任何硬性限制**——同一栋楼两点、密集门店都是合法输入，A+ 不因模型便利去缩小可用输入集。若产品希望提示用户，只能是导入时的**非阻断 warning**，且不得据此拒绝计划。

### 6.5 配对与调用授权

调用方身份**只以 `Binder.getCallingUid()` 为真相源**，永不取自请求参数。

- 首次配对走 §4.1 的 bind-first 次序：Auto 先 bind 并调用，千网游按 UID 解析出调用方后落 `PendingPairingCandidate`，返回 typed `NOT_PAIRED`；配对 UI 展示的是这条已由 Binder 证实的记录，而不是 UI 侧自行扫描包列表的结果。
- `PendingPairingCandidate` 与 `PairingRecord` 都必须**在 Binder 调用进行中完成身份解析并持久化快照**。反向包可见性授权的存续期不是文档化契约，UID 也会在卸载重装后被复用，因此**禁止只存 UID 事后反查**；匹配是每次调用现场解析出的身份与已存快照比对，两侧都不依赖延迟查询。
- 快照中参与**授权匹配**的只有 `(applicationId, current signerDigest)`；`versionCode` 一并记录但**只用于审计与兼容诊断**，不进入身份比对（§6.5.4）。
- 每次 Binder 调用按 UID 反查 package 与 signing certificate，和 `PairingRecord` 精确匹配。
- `PairingRecord` 的主键是 `(applicationId, signerDigest)` 二元组。**production `name.caiyao.fakegps` 与 bench `name.caiyao.fakegps.bench` 是两个独立 applicationId，互不授权**：给 production 配的对不能让 bench 调用通过，反之亦然。
- 包名相同但 signer 改变视为新调用方，必须重新配对。
- 调用方不可通过参数伪造 package、signer 或 verificationLevel。
- revoke 立即使新调用失败；active lease 进入 release/recovery，不静默继续。**两侧各有一份可撤销名单**：千网游撤销 `PairingRecord`（caller allowlist），Auto 撤销 `ProviderPairingRecord`（provider allowlist，见 §6.5.3）；任一侧撤销都必须让运行停下来。
- 配对记录和用户可见运行日志默认持久化，只有 operator 主动删除。

#### 6.5.1 签名校验的 API 分层（minSdk 24 冻结）

**第一步是把 UID 解析成唯一 package。** `Binder.getCallingUid()` 证明的是 UID，不是唯一包名——shared UID 下一个 UID 可对应多个包。因此：`getPackagesForUid(uid)` 结果**不是恰好 1 个就直接拒绝**（typed `CALLER_NOT_ALLOWED`）。v1 不支持 shared UID 调用方，这是窄接口的代价，不是缺陷。

**第二步是比对当前 signer，不是"曾经用过的" signer。**

| 运行 API | 路径 | 语义 |
|---|---|---|
| ≥ 28 | `GET_SIGNING_CERTIFICATES` + `SigningInfo.getApkContentsSigners()` | 取**当前**签名者集合并与配对快照比对 |
| 24–27 | legacy `GET_SIGNATURES` | **fail-closed 降级路径**：只接受单一签名者；无法解析或任何歧义一律拒绝配对并提示升级设备 |

**为什么不能直接用 `hasSigningCertificate(uid, digest, …)` 作为配对校验**：该 API 的语义是"该 uid **曾经或当前**使用过这张证书"，它是为**兼容证书轮转**设计的。拿配对时存下的旧 digest 去查，证书轮转之后**仍然返回 true**——于是 §6.5 的"signer 改变必须重新配对"被静默绕过。它可以用于"这是不是同一条轮转链"的辅助判断，但**不能**作为身份等同的判据。

**多签名者：v1 一律 fail-closed 拒绝**（`SigningInfo.hasMultipleSigners()` 为真即拒）。理由是窄接口优先：单一 digest 无法无歧义表示一个签名者集合。若将来产品必须支持，只能冻结"排序后 signer-set 的 canonical digest"并走 §6.7 兼容矩阵，不得用"取第一个"或"任一匹配"含混带过。

24–27 路径必须在配对 UI 上明示"本设备使用降级签名校验"，不得静默等同于 28+ 的保证。两条路径都必须有测试；shared UID 拒绝、多签名者拒绝、**证书轮转后必须要求重新配对**三条都是必测负例。

#### 6.5.2 signer 强度的真实边界（诚实披露）

当前 `FakeGps-test@285e4ca` 的 release 复用本机 `~/.android/debug.keystore`（alias `androiddebugkey`，口令 `android`）。必须准确陈述其后果，既不夸大也不粉饰：

- 该 keystore 由 SDK 在**本机首次构建时随机生成**，密钥材料并非全球共享，因此 signer 校验仍然排除了在其他机器上构建的第三方 App——`(applicationId, signerDigest)` 二元组不是只剩 applicationId 在把关。
- 但它同时意味着：**debug 与 release 构建的 signer 完全相同**，该 keystore 也不受口令保护（口令公开），一旦文件泄漏即可冒充该身份。
- 受影响的**只是**"当前 production key 原位轮转"这一种真机验收场景——它在不动 production key 的前提下造不出阳性用例。**签名不匹配拒绝、轮转后要求重新配对、多签名者拒绝这些语义仍然完全可测**：用受控测试 key 另签一个 fixture APK，或在单元/instrumentation 层注入伪造的 `SigningInfo`。§13 Task 9 只把"production key 原位轮转"标为 not-testable，不得据此把整类签名验收标成 not-testable。
- 结论：当前配置下不得宣称强 release identity。是否迁移到受控 release key 是 operator 的价值取舍，见 §21 DP-1；**本 doc PR 不擅自旋转 signer**。

#### 6.5.3 Auto 侧的 provider 信任根（与 caller allowlist 是两件事）

配对是双向的，而且两侧的名单**不是同一份**：

| 方向 | 名单 | 持有方 | 回答的问题 |
|---|---|---|---|
| qwy → Auto | `PairingRecord`（caller allowlist） | 千网游 | 谁可以调用我 |
| Auto → qwy | `ProviderPairingRecord`（provider allowlist） | Auto | 我可以把谁的 observation 当环境权威 |

Auto 在**信任千网游返回的 observation 之前**，必须解析所绑定 service 所属包的 applicationId 与**当前** signer，与本地 `ProviderPairingRecord` 精确比对；不一致或无法解析即 fail-closed，不进入 CellRebel。

**信任根必须显式：禁止 silent/automatic TOFU。** 只写"与本地记录一致"是不够的——若首次连接时自动把当时看到的 signer 落为可信，那么"真千网游未安装、同包名替代实现应答 bind"这一负例根本不会失败：替代者会在第一次连接时就成为被信任的权威，之后每次比对都"一致"。

**安全上限要说准**：operator 对一个首次见到、未经独立比对的 signer 做显式批准，在密码学意义上**仍然是一次 trust-on-first-use**。本方案禁止并能防住的是**自动/静默**的 TOFU——把信任建立变成一个可见、可审计、需人确认的动作；它**不证明** publisher identity。真正的 publisher 级保证需要带外分发的 signer 指纹或受控 release key（§21 DP-1），不在 A+ 范围内。不得把本机制描述为"已解决身份伪造"。

```kotlin
ProviderPairingRecord(
    applicationId: String,          // production 或 .bench，二者独立
    currentSignerDigest: String,    // 批准当时解析到的当前 signer
    approvedAt: Long,
    approvedVersionCode: Long?,     // 批准当时的版本，immutable，仅审计
    approvedBuildFingerprint: String?,   // 审计用
    revokedAt: Long?,               // null = active；非 null = 已撤销
)
```

- **key 与 active 语义**：主键 `(applicationId, currentSignerDigest)`。授权查询只匹配 `revokedAt == null` 的记录；**撤销是状态迁移，不是删除**，记录保留以维持审计链。同一 `(applicationId, signer)` 被撤销后若再次批准，写入新的 `approvedAt` 并清空 `revokedAt`——这是一次新的 operator 信任决定，必须重新走批准 UI，不能自动复活。
- 首次遇到**未见过的 (applicationId, currentSignerDigest)**，Auto 停在本地 `NOT_PAIRED` 预检态，向 operator 展示 applicationId、当前 signer 摘要与来源，**由 operator 显式批准**后才写入。
- **禁止在同一步里既信任 `discover()`/`observe()` 的返回、又把该 signer 落为 trusted**：批准是 operator 的信任决定，不是连接的副作用。批准前拿到的 capability 只能用于展示，不得进入任何可信判定。
- signer 变化即视为新 provider，重新走批准。`approvedVersionCode` **不可变**：provider 后续升级不改这条记录，新版本号只进 append-only 审计事件——审计字段不值得为它把 store 的可写面扩大（见 §6.5.4）。
- 采用"本地显式批准"而非预置 signer allowlist，是因为当前千网游 release 由本机 keystore 签名（§6.5.2），预置名单在不同机器上无法成立。

**撤销生命周期（与 §6.5 顶层 revoke 对齐）**：

`ProviderTrustStore` 是唯一入口，只暴露三个窄方法，**禁止在其上层使用 DAO 的通用 `delete`/`upsert`**（否则 INV-22 的旁路面被扩大到信任决定上）：

| 方法 | 语义 |
|---|---|
| `findActive(applicationId, signerDigest)` | 只返回 `revokedAt == null` 的记录 |
| `approve(candidate)` | operator 显式批准；写入或复活一条记录 |
| `revoke(applicationId, signerDigest, at)` | 置 `revokedAt`；不删除记录 |

- Auto UI 必须提供**撤销动作**（`ProviderApprovalScreen` 内，展示已批准 provider 列表与撤销入口）。
- 撤销**立即生效**：新的 run/预检 fail-closed 停在 `NOT_PAIRED`；**进行中的 run 不静默继续**——当前 attempt 进入 release/recovery 路径，release 无法证明完成时按 INV-21 暂停并提示人工恢复。
- 撤销后已写入的可信配额**不回溯撤销**（它们在当时有完整证据链），但撤销事件必须进审计，使历史可解释。

两侧的撤销是**两件独立的事**：千网游撤销 `PairingRecord` 使 Auto 无法调用；Auto 撤销 `ProviderPairingRecord` 使自己不再采信该 provider。任一侧撤销都必须让运行停下来，Task 9 的撤销验收**必须两侧各测一遍**。

#### 6.5.4 versionCode 不是身份的一部分

`versionCode` 在两侧都**只是审计与兼容诊断字段**，不参与授权 principal 的精确匹配。授权 principal 恒为 `(applicationId, current signerDigest)`。

理由：双 App 是独立发布的（INV-19），版本 skew 由 §6.7 的 protocol handshake 判定。若把 versionCode 并入身份匹配，任何一侧的正常升级都会要求 operator 重新配对——这与"独立发布 + 能力兼容握手"直接冲突，且会训练 operator 对配对提示脱敏。

同 signer、新 versionCode → **保持配对**，由握手决定兼容或 `INCOMPATIBLE_PROTOCOL` 停机。

配对记录里的版本字段一律**不可变**（`ProviderPairingRecord.approvedVersionCode` 记的是批准当时的版本）。provider/caller 后续升级**不回写**配对记录，新版本号只进 append-only 审计事件。理由：为一个纯审计字段在信任 store 上开一个可写入口，是用扩大可写面去换一条日志——审计需求由审计流满足，信任 store 的写面必须保持最窄（§6.5.3 三方法）。

### 6.6 跨进程 revision 所有权（blocker）

千网游至少存在三类进程上下文：主进程（UI/config/`MockProviderService`）、`:hook_verify`（`HookVerificationService`）、以及 Xposed 注入到被测 App 内的 hook 代码。`environmentRevision` 与 `continuityCoverage` 是跨这些上下文的共享可变状态，因此：

**本节冻结的是语义，不是承载技术。** 下列六条必须成立，选型由 PR-3 自行决定并用测试证明：

| # | 语义 | 说明 |
|---|---|---|
| L1 | **唯一 owner** | `EnvironmentRevisionState` 只有一个 owner 组件可读写。其他进程不直接触碰底层存储 |
| L2 | **全部经同步 IPC** | 所有 bump 与 observe 都是到 owner 的同步跨进程调用；没有旁路写入路径 |
| L3 | **序列化持久 read-modify-write** | owner 内部自增是序列化的，读-改-写不可分离，重启后单调性不依赖内存状态 |
| L4 | **ACK 后于 durable commit** | bump 的成功返回只能发生在持久化提交**之后**；提交前崩溃表现为"未 bump"，不得表现为"已 bump 但未落盘" |
| L5 | **observe 看得见已 ACK 的 bump** | 任何 observe 必须反映此前所有已 ACK 的 bump，不允许读到更旧的值 |
| L6 | **generation 断裂即降级** | owner 每次启动分配并持久化新 generation id；与前代观察窗连续性不可证时，bump revision 且 coverage 降为 `PARTIAL/NONE` |

丢一次或迟到一次 bump 的表现恰好是“coverage 仍为 FULL 且 revision 未变”，即 INV-08/09 要防的那个假可信——所以 L1–L6 不接受“大概不会丢”，必须有并发与崩溃注入测试。

**选型说明（避免把结论写成技术指令）**：

- owner **进程内部**用什么存不受限制。当 L1/L2 成立时，其他进程根本不写这份存储，所以它不是多进程写场景——owner 内部使用单进程 `DataStore`、Room 或 SQLite 都是合法选择。
- IPC 通道 Binder 与非导出 `ContentProvider` 均可。**注意 `ContentProvider` 自身会被并发回调，并不自动提供事务**，选它同样要自己保证 L3。
- 明确被否定的只有**"多个进程各自直接写同一份存储"**这一类：`SharedPreferences` 官方声明不支持多进程（`MODE_MULTI_PROCESS` 自 API 23 弃用）；`MultiProcessDataStore` 虽支持多进程，但 API reference 只承诺 cross-process **eventual consistency**，不满足 L5。这条否定针对的是**架构形态**，不是对这些库本身的禁用。
- **有损事件源必须自我申报**：`PrefsDirectoryObserver` 一类 `FileObserver` 是可丢事件、可被回收的观察器，属于 §6.4 "观察器丢事件"类。其重订阅、失效或任何不可证明的间隙都必须 bump + 降级，不允许"没收到事件"被当作"没有变化"。

### 6.7 兼容矩阵与握手

`compatibility.yaml` 冻结 `protocolVersion` 与各枚举 wire code 集合。握手在 `discover()` 完成：任一侧发现对端 `protocolVersion` 不在支持集合、或收到未知 wire code，一律返回/映射 `INCOMPATIBLE_PROTOCOL` 并停在预检页，不进入 CellRebel。矩阵测试必须覆盖 新Auto+旧qwy、旧Auto+新qwy、以及未知 wire code 三类 skew。

## 7. 状态对象普查

### 7.1 Auto 持久对象

| 对象 | lifecycle owner | 权威字段 | 派生/禁止 |
|---|---|---|---|
| `PlanDefinition` | PlanRepository | 原始导入、版本、模板、常用参数 | 运行中不可改 |
| `PlanRun` | AutomationEngine | runId、planVersion、状态、开始/结束时间 | “完成百分比”派生，不单存 |
| `LocationTask` | PlanRepository | 顺序、目标配额 | completed 由可信账本计数派生 |
| `Attempt` | AttemptRepository | attemptId、taskId、状态、当前 operation | 不直接存 success boolean |
| `CellRebelExecution` | CellRebelAttemptFlow | executionId、attemptId、`CellRebelCompletionEvidenceV1` 判定值（§8.6.2 五值之一）、完整证据（基线态/marker 文本/RUNNING 时长/各轮时间戳/分数） | 一个 attempt 可有多个外部 execution；`executionId` 是 **Auto 本地生成**的，**不是** CellRebel 的物理执行身份（§8.6.1） |
| `TrustedQuotaEntry` | TrustedQuotaLedger | attemptId、taskId、evidenceDigest | UNIQUE(attemptId)，只插不改 |
| `UnverifiedAttemptRecord` | AttemptRepository | attemptId、reason、evidenceDigest | 与可信账本不同表/类型 |
| `LegacyCompletionSnapshot` | v4→v5 迁移（只写一次） | taskId、legacyCompletedSuccesses、legacyStatus、migratedFromSchemaVersion、migratedAt | 只读展示；**绝不生成 `TrustedQuotaEntry`**，不进 completed 投影 |
| `ProviderPairingRecord` | ProviderTrustStore | applicationId、currentSignerDigest、approvedAt、revokedAt | Auto 侧 provider allowlist，与 qwy 的 caller allowlist 是两份名单；`approvedVersionCode` immutable 且仅审计，不参与匹配；撤销是状态迁移不是删除；**禁止 silent/automatic TOFU 写入**，只经 `ProviderTrustStore` 三个窄方法 |
| `RecoveryCheckpoint` | RecoveryCoordinator | attemptId、lastDurableStage、receipt refs | 终态后删除或纯投影 |
| `AutoAuditEvent` | AuditRepository | seq、correlation ids、event、payload digest | append-only；不是状态 owner |

### 7.2 千网游持久对象

| 对象 | lifecycle owner | 权威字段 | 派生/禁止 |
|---|---|---|---|
| `PairingRecord` | CallerAuthorizer | package、signer、approved/revoked | 请求体不能覆盖 |
| `EnvironmentRevisionState` | ContinuityTracker | monotonic revision、coverage、generation | 心跳不能写 FULL |
| `EnvironmentLease` | EnvironmentLeaseStore | leaseId、callerApplicationId、callerSignerDigest、`acceptedIntentHash`、`state`（§8.4 七态）、`applyIdempotencyKey`、`startingEnvironmentRevision`、`deadlineElapsedRealtimeMs`、**`applyOwnerGeneration`**（apply 时的 owner generation，用于判定单调值可比性；`startingEnvironmentRevision` 不能替代——revision 是持久单调计数，generation 是每次 owner 进程启动变化的另一根轴）、`releaseIdempotencyKey?`、`residualReasonWires`、`revokeSource?`、`recoveryEvidenceRef?` | 一个设备上的冲突 lease fail-closed；字段在此冻结，**不留给 DeepSeek Flash 与 fake-qwy 各自发明** |
| `OperationReceipt` | IdempotencyStore | caller、operation、`idempotencyKey`、**`requestDigest`**（§6.3.4 冻结的 domain-separated 长度前缀 preimage）、resultDigest、`createdAtElapsedRealtimeMs` | 同键**同** `requestDigest` → 幂等重放原 receipt；同键**异** `requestDigest` → `IDEMPOTENCY_CONFLICT`。**resultDigest 证明不了这件事**——它是应答的摘要，两个不同请求完全可能产生相同应答 |
| `PendingPairingCandidate` | CallerAuthorizer | callerApplicationId、currentSignerDigest、observedVersionCode、`firstSeenAtElapsedRealtimeMs`、`state`（§8.5 `PENDING_CALLER_APPROVAL`） | 必须在 Binder 调用进行中落快照（§6.5）；批准后转 `PairingRecord`，拒绝或过期即丢弃，**不得自动升格** |
| `EffectiveEnvironmentObservation` | EnvironmentObserver | observed state、fingerprint、evidence refs | UI 状态不可替代 |
| `ScheduleEvaluation` | QWY Schedule owner | scheduleRef、decision、boundary | Auto 不复制 |
| `QwyAuditEvent` | IntegrationAuditStore | seq、caller、lease、event、digest | append-only；不含密钥 |

### 7.3 纯派生状态

- `LocationTask.completed` = `count(TrustedQuotaEntry where taskId=...) >= requiredSuccesses`。**`LegacyCompletionSnapshot` 不参与此投影**——迁移前的历史计数展示为 legacy-unverified，不构成 A+ 完成。
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

### 8.3 stateful object 完备性映射（表 3）

§7 普查了"有哪些状态对象"，本表回答"每个对象的状态迁移在哪里被冻结"。**空缺即缺陷**：任何被 INV 或 §10 依赖其状态语义的对象，都必须在此表指向一个已定义的状态机，否则各实现者会各自发明。

| 对象 | owner | 状态机 | 崩溃恢复 | 冲突语义 |
|---|---|---|---|---|
| `PlanRun` | AutomationEngine | §8.2 | §8.2 `RECOVERING` | 单设备一个 active run（INV-16） |
| `Attempt` | AttemptRepository | §8.1 | §8.1 `RECOVERY_REQUIRED` | 同 task 串行 |
| `EnvironmentLease` | EnvironmentLeaseStore | **§8.4（本次新增）** | §8.4 | §8.4 冲突谓词 |
| `PairingRecord` / `ProviderPairingRecord` | CallerAuthorizer / ProviderTrustStore | **§8.5** | 持久，无中间态 | 同 key 唯一 |
| `PendingPairingCandidate` | CallerAuthorizer | **§8.5**（`PENDING_CALLER_APPROVAL` 单态） | 调用内落快照即持久；重启后仍待批 | 同 `(applicationId, signerDigest)` 去重；批准即转 `PairingRecord`，**不得自动升格** |
| `CellRebelExecution` 时间字段 | CellRebelAttemptFlow | §6.4.2 冻结三字段 + 单调时钟 | 随 Attempt | 无 |
| `EnvironmentRevisionState` | ContinuityTracker | §6.6 L1–L6 | §6.6 generation | 单写者，无冲突 |
| `CellRebelExecution` | CellRebelAttemptFlow | §8.6（completion evidence） | 随 Attempt | 见 §8.6 去重 |
| `OperationReceipt` | IdempotencyStore | 无中间态：同键同 payload 幂等重放，异 payload → `IDEMPOTENCY_CONFLICT` | 键持久，重放安全 | INV-13 |
| `TrustedQuotaEntry` | TrustedQuotaLedger | 只插不改，无迁移 | ledger 为真相（§10） | `UNIQUE(attemptId)` + §8.6 |
| `UnverifiedAttemptRecord` | AttemptRepository | 只插不改 | 同上 | 与可信账本不同表 |
| `LegacyCompletionSnapshot` | v4→v5 迁移 | 只写一次，此后只读 | 迁移事务内 | 不参与判定 |
| `RecoveryCheckpoint` | RecoveryCoordinator | 终态后删除或纯投影 | 本身即恢复输入 | 单 attempt 唯一 |
| `EffectiveEnvironmentObservation` | EnvironmentObserver | 不可变快照 | 无 | 无 |
| `ScheduleEvaluation` | QWY Schedule owner | qwy 内部，Auto 只消费 decision | qwy 内部 | 无 |
| `AutoAuditEvent` / `QwyAuditEvent` | AuditRepository / IntegrationAuditStore | append-only，非状态 owner | 无 | 无 |

### 8.4 EnvironmentLease 状态机

`state` 此前只作为字段名出现，而 INV-14（release 只能清理本 caller 本 lease）与 INV-16（冲突 lease fail-closed）都以"什么算 active lease"为前提。不冻结它，DeepSeek Flash 的 provider 与 Sol 的 fake provider 会各写一套，且 acceptance 会在两套语义之间假绿。

| 当前状态 | 事件 | 下一状态 | 原子写入 | 阻挡新 apply |
|---|---|---|---|---|
| （无） | `ACQUIRE` 通过预检 | `ACQUIRING` | 先写 lease + idempotencyKey | — |
| `ACQUIRING` | 环境已应用 | `ACTIVE` | 保存 intentHash、起始 revision | **是** |
| `ACQUIRING` | 应用失败 | `RELEASE_INCOMPLETE` | 记录 typed reason | **是** |
| `ACQUIRING` | 崩溃恢复 | `ACQUIRING` | 同键重放，不换 key | **是** |
| `ACTIVE` | `RELEASE` 请求 | `RELEASING` | 保存 release key | **是** |
| `ACTIVE` | 到达 `deadlineElapsedRealtimeMs`（见下方时钟桥接） | `EXPIRED` | 记录过期 | **是** |
| `ACTIVE` | **qwy 撤销 caller** | `REVOKED` | 记录撤销来源 | **是** |
| `RELEASING` | 清理已证明完成 | `RELEASED` | 保存 release receipt | 否 |
| `RELEASING` | 清理不可证明 | `RELEASE_INCOMPLETE` | `releaseComplete=false` + residual | **是** |
| `EXPIRED` | 原 caller 调用 `release` | `RELEASING` | 同上 | **是** |
| `REVOKED` | **qwy 内部自清理**（原 caller 已失权，不可能调用） | `RELEASING` | 记录 provider-driven cleanup | **是** |
| `RELEASE_INCOMPLETE` | operator 完成人工恢复 | `RELEASED` | 记录人工恢复证据 | **是**（直到迁出） |
| `RELEASED` | 任意重复事件 | `RELEASED` | no-op + audit | 否 |

**冲突谓词**：新的 `apply` 在设备上存在任一非 `RELEASED` lease 时返回 `LEASE_CONFLICT`，**唯一例外**是同一 caller 以**同一 `idempotencyKey`** 重放——那是幂等重放，返回原 receipt；同一 caller 用不同 key 或不同 intentHash 再次 apply 同样冲突。

**为什么 `EXPIRED` 必须继续阻挡**：过期只说明时间到了，**不说明环境已被清理**。若把 `EXPIRED` 当作自动释放，lease TTL 就成了 INV-21（release 不可证明即暂停）的一条旁路——超时即可让下一个 apply 在一个状态未知的环境上开跑。同理 `RELEASE_INCOMPLETE` 与 `REVOKED` 都必须阻挡到被显式收敛为止。**这里宁可误挡（false-red，代价是停机等人）也不能漏挡（false-green，代价是可信配额建立在脏环境上）。**

**deadline 的时钟桥接（冻结）**：`EnvironmentIntentV1.deadlineEpochMs` 是调用方用**墙钟**表达的计划级意图（人可读、跨设备可讲）；而过期判定必须用 §6.4.2 的单调钟，否则一次系统校时就能让 lease 提前过期或永不过期。因此在 **`apply` 受理的那一刻转换一次并快照**，此后**只有单调值参与判定**：

```text
deadlineElapsedRealtimeMs = nowElapsed + max(0, deadlineEpochMs − nowEpoch)
```

- `deadlineEpochMs ≤ nowEpoch` → `max(0, …)` 使其立即到期，而不是变成负数绕回。
- 快照之后**墙钟怎么跳都不影响** lease 生命周期；`deadlineEpochMs` 仅留作审计与 UI 展示。
- `notBeforeEpochMs` 同理，在 `preflight`/`apply` 处一次性转换。
- 必测：apply 后把系统墙钟前后各跳数小时，断言 `EXPIRED` 触发时刻不变。

**先更正上一版的事实错误**：上一版写"qwy 重启后 `elapsedRealtime` 归零"——**这是错的**。`SystemClock.elapsedRealtime()` 的官方语义是**自设备启动（boot）以来**的时间，**进程重启不会重置它**。基于错误前提写下的测试会把错误的平台模型冻结进去。

**单调值的可比性载体（冻结）**：`deadlineElapsedRealtimeMs` 是绝对单调值，只在**同一个时钟纪元**内可比。时钟纪元只因设备 reboot 而改变，但**设备 reboot 必然导致 qwy owner 进程重启，因而必然改变 §6.6 的 owner generation**。所以：

```text
generation 变化 ⊇ 时钟纪元变化
```

**用 owner generation 作载体是可证充分的**——它不会漏掉任何一次单调值失效。因此 `EnvironmentLease` 冻结新字段 `applyOwnerGeneration`（apply 受理时的 `EnvironmentRevisionState` generation），并冻结判定：`applyOwnerGeneration ≠ 当前 generation` → `deadlineElapsedRealtimeMs` 不可比 → **按 `EXPIRED` 处理**。

**它同时会过度检测，这是明示策略而非意外**：普通的 qwy 进程重启（未 reboot、时钟仍可比）也会改 generation，于是也强制 lease 过期。**代价必须写全（本段已被收窄两次，此处给出最弱可辩护的表述）**：

- §6.6 L6 只在 generation 断裂**且连续性不可证**时才强制 bump + 降级——它是**带条件**的。
- 本 spec **刻意不冻结** owner/transport 技术，也不要求连续性事件源与 owner 进程共址（§6.6 只冻结 L1–L6 语义）。事实上现有 `PrefsDirectoryObserver` 就位于被 hook 的目标进程，而非 owner 进程。**因此"owner 进程重启必然打断观察窗"并不成立**，不能作为论据。
- 于是 false-red 的真实代价有两部分：**① 必然**多一次 release + 重新 acquire 的往返；**② 可能**——当某个实现下连续性确实能跨 owner 重启被证明时，强制过期会丢掉一个**本还可能满足可信谓词**的在飞 attempt。

**明知有第 ② 项仍选 `applyOwnerGeneration`**，理由是它是可证充分的安全上界（generation 变化 ⊇ 时钟纪元变化），而引入一个只在少数实现下才更精确的 boot-epoch 载体，会多出一条必须自行证明正确的检测路径。**这是拿确定的可用性代价换确定的安全性**，不是"没有代价"。

若将来把连续性事件源移出 owner 进程并能证明跨重启连续，第 ② 项会从"可能"变成"经常"，届时应重新评估是否值得引入独立的 boot-epoch 载体。

**唯一仍可断言的是**：强制过期**不回滚任何已提交的 `TrustedQuotaEntry`**——已写入的可信配额在当时具备完整证据链，过期只影响尚未完成的在飞 attempt。**这与"不损失可信计数"不是一回事**，后者是 v1.9 的口径，已被 v1.11 撤回：在飞 attempt 若本可满足可信谓词，它带来的那一次计数确实会丢。

**恢复必须是 state-aware 的（消解 `M-LS-07`/`M-LS-12` 重叠，且不制造新的不可达）**：把"对每个非 `RELEASED` lease 一律套用同一套规则"是**错的**——它会把一个出口已经确定的状态改写成一个出口对当前调用方不可达的状态。

先按状态分流，**同一 lease 只命中一条**：

| 持久状态 | 重启后处置 | 理由 |
|---|---|---|
| `REVOKED` | **原样保留 `REVOKED`** | 它的出口是 §6.3.3 冻结的 **qwy 内部自清理**（`REVOKED → RELEASING`），与 caller 授权和时钟都无关。改写成 `EXPIRED` 会让出口消失：原 caller 已失权无法 `release`，而内部自清理只对 `REVOKED` 冻结 |
| `RELEASE_INCOMPLETE` | **原样保留** | 出口是 operator 人工恢复证据，同样与时钟无关；改写只会丢失"需要人介入"这一信息 |
| `RELEASING` | 重新驱动 release；无法证明清理完成 → `RELEASE_INCOMPLETE` | 幂等重放，语义不变 |
| `ACQUIRING` / `ACTIVE` | ① 干净性不可证 → `RELEASE_INCOMPLETE`（`M-LS-07`）；② 否则 `applyOwnerGeneration ≠ 当前 generation` → `EXPIRED`（`M-LS-12`） | **只有这两态的出口依赖"caller 在 deadline 前动作"**，因此也只有它们受单调值可比性影响 |
| `EXPIRED` | 原样保留 | 已过期，无需再过期一次 |
| `RELEASED` | 终态，不参与恢复 | — |

**通用 `→ EXPIRED` 规则的作用域被显式限定为 `ACQUIRING`/`ACTIVE`**，不得推广到"每个非 `RELEASED` lease"。

所有非 `RELEASED` 态都阻挡新 `apply`（INV-28）；差别只在离开该态的路径，而**恢复不得改变这条路径的可达性**。**禁止**用"重启后没看到 lease 就当没有"来隐式释放。

### 8.5 配对与预检就绪态

§7 的状态普查此前不含配对/预检态，而 §4.1 的双向批准流程与 §6.5.3 的撤销生命周期都依赖它们。

| 状态 | 含义 | 可否进入 CellRebel |
|---|---|---|
| `UNPAIRED_CALLER` | 千网游侧无 active `PairingRecord` | 否（`NOT_PAIRED`） |
| `PENDING_CALLER_APPROVAL` | 候选已落 `PendingPairingCandidate`，等 operator | 否 |
| `UNAPPROVED_PROVIDER` | Auto 侧无 active `ProviderPairingRecord` | 否（本地 `NOT_PAIRED`，禁止 silent TOFU） |
| `PENDING_PROVIDER_APPROVAL` | 已展示给 operator，等显式批准 | 否；capability 仅可展示 |
| `INCOMPATIBLE` | 握手判定协议/能力不兼容 | 否（`INCOMPATIBLE_PROTOCOL`） |
| `READY` | 两侧均 active 且握手通过 | 是 |
| `REVOKED_EITHER_SIDE` | 任一侧撤销 | 否；in-flight run 转 release/recovery |

### 8.6 CellRebel completion evidence（冻结取值集 + 诚实上限）

`VERIFIED_NEW_COMPLETION` 此前是 §6.4 可信谓词的 load-bearing 项，却全文无定义。补定义之前必须先回答一个事实问题：**CellRebel 是否向外暴露稳定的物理执行身份？**

#### 8.6.1 事实认定（只读核验 `TERRYYYC/Faketest@48d8ec9`）

**答案：没有。**

- 观察面只有 `ScreenNode { text, contentDescription, className, clickable, enabled }`（`automation/cellrebel/ScreenSnapshot.kt:10-17`）。`viewIdResourceName` **不在其中**——它只出现在两处：`util/DebugExporter.kt:119` 的人读 dump，以及 `NodeFinder.kt:81` 的 `findByViewId`（**无任何调用点的 dead code**）。两处都不进决策路径，但"仅出现在 DebugExporter"是不准确的说法，此处更正。
- 无 run ID、结果行 ID、test ID、单调计数器、导出文件、ContentProvider、可读日志；CellRebel 的历史/结果列表从未被访问（唯一导航是菜单 → 文本 `"Connection Test"`）。
- 完成判定是**轮询 UI 文本**（`CellRebelAttemptFlow.POLL_INTERVAL_MS = 1500L`）：无 running marker + 存在 enabled 的 `"Start"` + 两个分数可解析，且**连续两轮完全一致**。
- `PRE_EXISTING_RUN` 是**纯因果归属**——"Start 交互前屏幕是否已 RUNNING"（`CellRebelAttemptFlow.kt:106-137`），不携带任何身份信息。
- Auto 侧无任何源自 CellRebel 的去重键；全部 ordinal 都由**我们自己的行数**推导。`PlanRepository.finalizeAttemptSuccess` 的 `incrementSuccessIfCurrent` CAS 只保证**同一 attempt 重放**幂等，**不阻止两个不同 attempt 各自认领同一次物理执行**（二者 `expectedCompletedSuccesses` 分别为 N 与 N+1，都会 CAS 成功）。

**因此：系统只有因果链，没有执行身份。** 这不是实现疏漏，是外部 App 的可观察面决定的上界——CellRebel 与 Auto 之间不存在完成契约（§5：唯一完成判定方是 Auto 自己）。

##### 8.6.1.1 真机 dump 实测（把上述结论从源码推断升级为观测证据）

上面的认定来自**源码只读核验**。为避免"读代码得出的上界"与"设备上真实呈现"之间存在缝隙，对既有真机 dump 做了一次独立全量测量：

- **样本（canonical source，已独立复核）**：`/Users/terry/Desktop/coding/faketest/feature-discussions/2026-07-30-f001-design/` 下 **43** 份 uiautomator dump，其中含 `package="com.cellrebel.mobile"` 的 **33** 份。
- **保全副本与校验**：原件不动，已按 operator 决定（§21.0 第 5 条）复制保全至
  `/Users/terry/Desktop/f001-preservation/0001786310399153-001347-114fff25/`，
  `manifest.sha256` = `46e0e3e72adb7f6451e5254b7ebff06cfec63e38720c5ab0dbb56b646a365bc0`
  （对 `manifest.json`，已独立复算一致；payload 内同为 43 XML / 33 CellRebel XML）。
  **这些 dump 不入 Git**——它们含真机 UI 内容，只以路径 + 哈希被引用。

  > **更正记录**：本节上一版把样本路径写成 `faketest-f002/…`，该路径下实为 **0 份 XML**。
  > 成因是我把一条**目录名搜索**输出里的前缀，安到了另一条 **XML 搜索**的结果上——两条命令
  > 不同、结果集不同。provenance 的全部意义是让**别人**能复核；写一个复核不到的地址，
  > 等于把"可验证"降级成"请相信我"。由非作者（Sol）实查发现。
- **样本构成（重要，决定了哪些结论成立）**：33 份**不是 33 次独立测量**，而是少数几次 session 内的连续帧——`device-smoke/`(8，含 `cellrebel-ready → running → poll-2..6 → completed` 一条完整轨迹) · `device-smoke/early/`(6) · `device-smoke/rapid-dumps/`(18，`d2…d19` 一次 burst) · `device-smoke/burst/`(1)。全部文件 mtime 相同（`2026-08-02 00:34`，checkout 产物），因此 mtime 不能用于区分 run。
- **方法**：全量扫描 `resource-id` 与 `text` 属性，**不设长度或形态过滤**（此前一次计数正是被扩展名过滤器与 `head` 截断同时污染，教训见下）。

| 测量项 | 结果 |
|---|---|
| `resource-id` 分布 | 每个 id 在 33 份中各出现 **33 次**，全部为静态布局 id（`web_browsing_score` / `video_streaming_score` / `start_button` / `toolbar` …），**无任何随执行变化的标识** |
| 全部不同 `text` 取值 | **仅 8 条**：`Connection Test` / `Start` / `Web Browsing Score` / `Video Streaming Score` / 一句静态说明文案 / `Measuring web browsing quality…` / `Measuring video streaming quality…` / **`EXCELLENT`（66 次 = 33 份 × 2 个分数位）** |
| 时间戳 / session id / result id / 数字分数 | **零** |

**本样本能支持什么、不能支持什么**（区分开，因为两类结论对样本构成的依赖完全不同）：

| 结论 | 是否成立 | 为什么 |
|---|---|---|
| 完成屏上**不存在**执行身份（run id / 结果行 id / session / 时间戳 / 单调计数器） | ✅ **成立** | 这类标识若存在，必然在**单次 run 内**就渲染在屏上。连续帧样本足以证否其存在，不需要跨 run |
| 完成屏的 `resource-id` 集合完全静态 | ✅ **成立** | 同上，run 内即可观测 |
| 分数为**低基数**定性标签、跨执行取值范围很小 | ❌ **本样本不能支持** | 33 份是少数几次 session 的连续帧；**同一次 run 内分数本就不该变**。`EXCELLENT × 66` 是预期内的，不构成基数证据 |

**因此准确的结论是**：§8.6.4 描述的两条轨迹（`READY → 真 marker → 新结果` 与 `READY → 持续 marker/重渲 → 旧结果`）之所以不可区分，**是因为完成屏不携带任何执行身份**——而不是因为"分数总是相同"。前者已被本样本证实，后者未被证实且不需要它。

这直接决定了 DP-3 = A 的性质：**可信计数的归属依据是时序因果链，不是结果内容**——内容不携带任何可用于**归属**的信息（无论其取值分布如何）。§8.6.5 的上限因此不是保守措辞，而是对观察面的准确描述。

> 若将来要主张"分数基数低"这类**跨执行分布**结论，必须另取**跨独立 run** 的样本；本节样本不具备该证明力。此处不借用。

> **方法论教训（留在此处，因为它已在同一节里复发过三次，形态一次比一次隐蔽）**：
> 1. 第一版用 `grep -ohE 'text="[^"]{4,40}"'` 取文本，长度下界 4 会静默滤掉短数字；
> 2. 另一次相关计数用 `| head` 截断后仍下了全量结论；
> 3. **第三次最隐蔽**：查询本身没问题，但把「同一次 run 内连续帧中分数不变」当成了「分数基数低」的证据——**结论的作用域大于样本的作用域**。它由非作者 review 指出，作者自查两轮都没发现。
>
> 统一的判据：**计数类查询不得带展示性过滤器；结论的作用域不得大于查询与样本的作用域。** 第 3 条尤其说明，"数据是真的"不等于"结论是真的"——还要问这批数据的**构成**能否支撑这个结论。

#### 8.6.2 冻结取值集

```kotlin
enum class CellRebelCompletionEvidenceV1(val wire: Int) {
    /** 完整因果链：基线非 RUNNING → 本次 Start 交互 → marker 证实的 RUNNING 持续达标 → 稳定 COMPLETED */
    VERIFIED_NEW_COMPLETION(1),
    /** Start 交互前屏幕已 RUNNING —— 属于上一次运行 */
    PRE_EXISTING_RUN(2),
    /** RUNNING 仅由「Start 按钮 disabled」推得，无 marker 文本佐证 */
    WEAK_RUNNING_EVIDENCE(3),
    /** RUNNING 时长低于冻结下限，与布局动画不可区分 */
    RUNNING_TOO_SHORT(4),
    /** 超时、中断、分数不可解析等 */
    NO_COMPLETION_EVIDENCE(5),
}
```

**只有 `VERIFIED_NEW_COMPLETION` 可进入可信配额**；其余全部写 `UnverifiedAttemptRecord`，且必须记录 typed reason。

#### 8.6.3 判定规则（全部条件缺一不可）

1. **基线**：Start 交互前在同一 `run()` 调用内观察到 `READY` 或 `COMPLETED`（非 RUNNING）。观察到 RUNNING → `PRE_EXISTING_RUN`。
2. **RUNNING 必须由 marker 文本证实**。当前检测式为 `hasMarker || (start != null && !start.enabled)`；**后半条单独成立时不得判为可信**。理由见 §8.6.4。仅 disabled-Start → `WEAK_RUNNING_EVIDENCE`。
3. **RUNNING 必须持续 ≥ `MIN_RUNNING_EVIDENCE_MS`**（冻结为 10_000 ms）。真实连接测试量级为数十秒，而 re-foreground 布局动画量级为数百毫秒。低于下限 → `RUNNING_TOO_SHORT`。
4. **RUNNING → 非 RUNNING → 稳定 COMPLETED** 的迁移必须发生在**同一 `run()` 调用内**。跨调用拼接的证据不成立。
5. 分数需连续两轮完全一致（沿用既有稳定性判据）。

#### 8.6.4 为什么第 2、3 条是必需的（具体攻击面）

`AutomationEngine.kt:313` 在每次 `runTest` 前调用 `returnToSelf()`，随后 `CellRebelHandler.launchAndWaitForForeground()` 经 `GLOBAL_ACTION_RECENTS` 切回 CellRebel——**上一次运行的完成结果页是结构性地必然重显**，不是偶发。

若此时 Start 按钮因布局/动画短暂 `enabled=false`，旧判据的后半条即成立 → 判为 RUNNING → 进入完成循环 → 读到**上一次运行仍在屏幕上的分数** → 连续两轮当然一致 → 返回 `Success`。**一次物理执行被第二个 attemptId 再计一次可信配额**，且 `UNIQUE(attemptId)` 完全挡不住（两个 attemptId 本就不同）。

**分数不能用来去重**：upstream 明文规定"两次有效运行可以产生完全相同的结果"（INV-7）。用分数相同判重会系统性丢弃合法成功；且 `findNearbyScoreOrLabel` 在数值缺失时回退到 5 档评级词，同一次结果在不同轮次可能给出不同键，反而制造假分裂。**因此禁止任何基于分数值的跨 attempt 去重。**

#### 8.6.5 诚实上限（本方案能证明什么、不能证明什么）

**能证明**：每个 attemptId 至多一次可信配额（INV-10）；可信配额只在完整因果链于单次 `run()` 内被观察到时写入；每次接受都留下可复核证据（基线态、marker 文本、RUNNING 时长、各轮时间戳、分数）。

**不能证明**：同一次物理 CellRebel 执行绝不会被两个 attempt 各计一次。**没有任何可观察量支持这个保证**。第 2、3 条把攻击窗从"数百毫秒的动画"收缩到"CellRebel 必须持续 ≥10s 渲染 marker 文本却未真正开跑"，但不能消除它。

**不接受把它写成绝对保证**——那是拿一句文档承诺去掩盖一个观察面缺口，正是本 spec §0.1.4 记录过的病。

但**"因此就降级"同样不是猫可以自决的**。mission 冻结基线写的是「外部执行可能重跑；可信配额最多增加一次；未证明完成永不计数」。在"接受 UI 证据并写明上限"与"UI-only 完成一律不进可信配额"之间做选择，是**产品安全边界的价值取舍**，任何猫的 review 都不能替 operator 批准。

**operator 已选 A（§21.0，`2026-08-09T21:19:59Z`）：接受 UI 证据并写明上限。** 因此本节的上限自即刻起**生效为已接受的产品语义**——不是被消除，而是被显式承担。随之而来的三条约束：

1. 本节文字是**产品承诺的一部分**，不得在后续版本中被悄悄软化；任何弱化都必须走一次新的 operator 决定。
2. 上限**必须**呈现在用户可见的计数语义中（运行页 / 历史页 / 导出），这是 `AC-06` 的验收项而非文案建议。理由：本产品的全部价值就是"每地址的可信次数"，一个带着看不见前提的数字，在被读到的地方就是在撒谎。
3. §8.6.1.1 的真机实测进一步表明，完成屏**不携带任何执行身份**，因此两条轨迹在观察面上无从区分——"可信"在本产品中的准确含义是「时序因果链成立」，而非「结果内容被独立核实」。呈现给 operator 的措辞不得暗示后者。

**§8.6.3 的收紧（含 `READY` 基线）是 mitigation，不是兑现。** `READY → 真实 marker → 新结果` 与 `READY → 持续 marker/重渲 → 旧结果` 在 `ScreenNode` 观察面上完全同形，因此任何基线要求都只能缩小窗口，不能证明 at-most-once。文中不得再出现"结构性关闭""字面兑现"一类表述。

若将来 CellRebel 暴露稳定执行身份（结果行 ID、导出文件、可读 run id），本节可升级为强保证并重写 INV-11；在那之前不假装拥有它。

**实现注记（Task 5 必查）**：`CellRebelStateDetector` 用 `text.equals("Start")` 精确匹配，而 `CellRebelHandler.findStartNode` 经 `NodeFinder.findByText` 用 contains 匹配。标签若为 `"Start Test"`，handler 找得到而 detector 判 `UNKNOWN`——两者必须统一，否则新设备上会静默失去全部可信计数。

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
| INV-11 | 未证明的新完成永不计数；可信配额只接受 `VERIFIED_NEW_COMPLETION`（§8.6.3 完整因果链）。**按 §21 DP-3 = A 兑现**：字面强度为「每 `attemptId` 至多一次」；「同一次物理执行至多一次」这半句**在当前观察面上不可兑现**（§8.6.1 / §8.6.1.1 实测：CellRebel 完成屏不暴露任何执行身份），其残余窗口由 §8.6.5 写明、由 INV-26 审计、并**必须**按 §21 DP-3 兑现条件第 3 条进入用户可见计数语义。`READY` 基线是 mitigation，不构成兑现 | `PRE_EXISTING_RUN`/`WEAK_RUNNING_EVIDENCE`/`RUNNING_TOO_SHORT`/timeout/crash 逐值测试；`READY` 基线测试；UI/导出上限呈现测试 |
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
| INV-23 | 可信配额要求 pre/post observation 的 `acceptedIntentHash` 等于 apply receipt 且等于 Auto 本地重算值，且 `effectiveLat/Lng` 非空并在 `TRUSTED_LOCATION_TOLERANCE_METERS` 内匹配目标坐标 | intent-binding matrix：错误地址、意图漂移、apply 部分生效、lease 复用后意图切换、坐标为 null 五类负例 |
| INV-24 | 用户可见持久数据的 schema 变更必须有显式 migration + 真实旧版本 fixture 测试；禁止 destructive fallback | Auto v4→v5 真实 fixture migration test；`fallbackToDestructiveMigration` 静态禁用扫描 |
| INV-25 | `environmentRevision`/coverage 跨进程单写者、持久、原子、单调；有损观察器与进程代际不明必须 bump + 降级 | 多进程并发 bump 测试；owner 进程重启代际测试；observer 丢事件注入测试 |
| INV-26 | 禁止任何基于分数值的跨 attempt 去重（upstream INV-7：两次有效运行可产生相同结果）；改为对每次可信计数持久化完整 completion evidence，并向 operator 暴露可疑相邻计数的**去重审计报告**（低 RUNNING 时长、异常紧邻时间戳等），报告不自动否决计数 | evidence 持久化 schema 测试；审计报告触发条件测试；"分数相同的两次合法运行都被计入"正例 |
| INV-27 | observation 的 mode/isMock/schedule/证据/时序必须与 `verificationLevel` 交叉一致；矛盾 tuple 一律 fail-closed；两次观察必须夹住执行窗且连续性窗口早于 pre 观察 | §6.4.1 矛盾 tuple 矩阵（8 行独立负例） |
| INV-28 | 设备上任一非 `RELEASED` lease 阻挡新 `apply`（唯一例外：同 caller 同 `idempotencyKey` 幂等重放）；`EXPIRED`/`REVOKED`/`RELEASE_INCOMPLETE` 不自动释放 | §8.4 状态机逐边测试；stale/expired/revoked 阻挡测试；跨进程崩溃重建测试 |
| INV-29 | **`applicationId` cutover 不得孤儿化用户可见状态。** 改 `applicationId` 即产生设备上另一个 App，其 sandbox 与备份域均不共享（§21 DP-2 一手依据）。因此 cutover 前必须二选一并留证：**(a)** 经可验证检查确认旧安装**不存在**持久用户状态 → 可直接 cutover；**(b)** 存在状态 → 必须先完成**版本化迁移桥**（覆盖 plan / task / attempt / result / session 及必要配置），通过数量与摘要校验，并具备可回滚验收，之后才移除旧 App。**禁止**用现有结果 CSV 冒充完整迁移（`AttemptCsvMapper` 只导出审计结果，`importCsv` 只导入 worklist）；**禁止**依赖跨 package 自动备份；**禁止**把 operator 数据复制进本仓库或任何日志 | 旧安装状态探测测试；迁移桥 round-trip 测试（数量 + 逐表摘要）；回滚演练；"CSV 不构成迁移"负例；仓库/日志内不含 operator 数据的静态扫描 |

## 10. 崩溃、并发、恢复与旁路误用矩阵

| ID | 类别 | 场景 | 预期终态 | 覆盖 INV |
|---|---|---|---|---|
| `M-CR-01` | crash | `APPLY_PENDING` 写入后、Binder 调用前崩溃 | 同键 apply，最多一个 lease | 13,15 |
| `M-CR-02` | crash | 千网游已 apply、Auto 未保存 receipt 崩溃 | 同键返回原 receipt | 13,15 |
| `M-CR-03` | crash | pre-observe 后、CellRebel 点击前崩溃 | 恢复后重新预检；不计数 | 7,11,15 |
| `M-CR-04` | crash | CellRebel 点击后、running 证据前崩溃 | 分类现状；未知不计，可记录新 execution 重跑 | 11,12,15 |
| `M-CR-05` | crash | CellRebel 完成后、post-observe 前崩溃 | 恢复后 post-observe；连续性不可证则未验证 | 7,8,11 |
| `M-CR-06` | crash | trust pass 后、ledger transaction 前崩溃 | 重算并唯一插入一次 | 5,10 |
| `M-CR-07` | crash | ledger commit 后、状态更新前崩溃 | ledger 为真相，恢复不重复计数 | 10,15 |
| `M-CR-08` | crash | release 调用后、receipt 保存前崩溃 | 同键重放 release | 13,14,21 |
| `M-CR-09` | crash | 千网游重启丢失连续性观察窗口 | revision 增加、coverage 降级、可信失败 | 8,9 |
| `M-CC-01` | concurrency | 两个 Start 同时触发 | 只创建一个 active PlanRun | 16 |
| `M-CC-02` | concurrency | 同 attempt 两协程同时插 ledger | 一次成功、一次幂等 no-op/conflict | 10 |
| `M-CC-03` | concurrency | 两 caller 请求冲突环境 lease | 第二方 typed `LEASE_CONFLICT` | 14,16 |
| `M-CC-04` | concurrency | apply 同键异 payload | typed conflict，不执行第二次 | 13 |
| `M-RC-01` | recovery | `PRE_EXISTING_RUN` 后出现旧结果页 | 记录旧运行，不计新完成 | 11,12 |
| `M-CO-01` | completion | re-foreground 期间 Start 短暂 disabled，旧完成页仍在屏 | 判 `WEAK_RUNNING_EVIDENCE`，不计数——**不得仅凭 disabled-Start 认定 RUNNING** | 11,26 |
| `M-CO-02` | completion | RUNNING 由 marker 证实但时长 < `MIN_RUNNING_EVIDENCE_MS` | 判 `RUNNING_TOO_SHORT`，不计数 | 11 |
| `M-CO-03` | completion | 同一物理完成被 attempt A（post-observe 失败）与 attempt B 各观测一次 | **终态已定（DP-3 = A）**：attempt B 若因果链完整则**计入**，并触发 INV-26 去重审计。这是 A 的已知上限之一，按 §8.6.5 写明、按 §21 DP-3 兑现条件第 3 条向 operator 呈现；**不得**写成"至多一条可信 ledger"。`deferred:DP-3` 标注**已解除** | 10,11,26 |
| `M-CO-04` | completion | 两次**合法**运行产生完全相同分数 | 两次都必须计入——禁止按分数判重（upstream INV-7） | 26 |
| `M-CO-05` | completion | 分数数值缺失回退到评级词，同一结果跨轮给出不同键 | 不得据此判为两次运行 | 26 |
| `M-CO-06` | completion | 设备上完全不出现 running marker 文本 | 全部判未验证并显式告警；**不得回退到 disabled-Start 弱信号** | 11 |
| `M-RC-02` | recovery | schedule 在 CellRebel 运行中跨边界 | revision 变化；未验证、release、暂停/等下窗 | 8,17 |
| `M-RC-03` | recovery | mock-location owner 被外部 App 抢走再改回 | revision 必须变化；不能因 post 状态相同而可信 | 8 |
| `M-RC-04` | recovery | qwy release 只能部分清理 | plan 暂停，显示人工恢复 | 14,21 |
| `M-BP-01` | bypass | Auto 直接写 qwy prefs/DB | 静态 guard/依赖测试失败 | 1,20 |
| `M-BP-02` | bypass | Auto 用 Accessibility 操作千网游 | package target guard 测试失败 | 1,20 |
| `M-BP-03` | bypass | 调用方在请求中伪造 signer/package | 仍按 Binder UID 拒绝 | 2 |
| `M-BP-04` | bypass | Hook 返回 `isMock=true` 试图进可信账 | TrustPolicy 拒绝 | 5,6 |
| `M-BP-05` | bypass | coverage PARTIAL 但心跳持续 | TrustPolicy 拒绝 | 8,9 |
| `M-BP-06` | bypass | generic DAO 把 CLOSED 改回 RUNNING | repository/DB constraint 拒绝 | 22 |
| `M-BP-07` | bypass | 删除 attempt 后让 location 看似未完成再重跑 | ledger FK/不可删策略保留可信事实 | 10,22 |
| `M-RL-01` | release | foreign/stale leaseId | 不清理环境，typed error | 14 |
| `M-VS-01` | version | 新 Auto + 旧 qwy / 旧 Auto + 新 qwy | 兼容则运行，不兼容则预检停止 | 3,19 |
| `M-VS-02` | version | 对端返回未知枚举 wire code | `fromWire` 返回 null → fail-closed，不得崩在 Binder transaction 内 | 3,4 |
| `M-PA-01` | pairing | operator 隔较长时间/重启后才批准 pending candidate | 用调用内落下的身份快照批准；不得因反向可见性授权已失效而失败或降级 | 2 |
| `M-PA-02` | pairing | Auto 卸载重装后 UID 被另一 App 复用 | 按 applicationId+signer 快照比对判为新调用方，不得凭 UID 直通 | 2 |
| `M-IN-01` | intent | apply 部分生效，有效坐标停在上一地址 | 意图绑定失败 → 未验证，不计数 | 23 |
| `M-IN-02` | intent | lease 复用但意图已切换，observation 仍返回旧 intent hash | `ENVIRONMENT_DRIFT`，不计数 | 23 |
| `M-IN-03` | intent | observation 的 `effectiveLat/Lng` 为 null | 不计数（不得因"其他条件都过"放行） | 23 |
| `M-IN-04` | intent | 计划内两地址距离极近（同楼/密集门店） | 正常受理并各自独立归属；不得拒绝导入 | 23 |
| `M-PA-03` | pairing | shared UID 调用方（`getPackagesForUid` 返回 ≠ 1 个包） | typed `CALLER_NOT_ALLOWED`，v1 不支持 | 2 |
| `M-PA-04` | pairing | 配对后证书轮转，旧 digest 仍在轮转链中 | 必须要求重新配对；不得因 `hasSigningCertificate` 命中"曾经使用"而放行 | 2 |
| `M-PA-05` | pairing | 真千网游未安装，同包名替代实现应答 bind | Auto 反向校验当前 signer 失败 → fail-closed，不进入 CellRebel | 2 |
| `M-MG-01` | migration | v4 fixture 的 `completedSuccesses` 非零 | 转为 `LEGACY_UNVERIFIED` 快照保留展示；`TrustedQuotaEntry` 仍为空，trusted 从 0 起算 | 24,5,6 |
| `M-MG-02` | migration | 恢复流程读到 legacy 计数 | 不得当作已完成而跳过地址 | 24,15 |
| `M-MG-03` | migration | 已存在 v4 用户库升级到 v5 | 显式 `MIGRATION_4_5` 成功，历史计划与结果全部存活 | 24 |
| `M-MG-04` | migration | migration 执行到一半进程被杀 | 重启后事务回滚或重放，绝不落半迁移库、不 destructive 重建 | 24,15 |
| `M-MG-05` | migration | 安装了更高 schema 版本后降级回旧包 | 明确失败并提示，不静默清库 | 24 |
| `M-AC-01` | appid-cutover | cutover 前探测旧 `applicationId` 安装是否存在持久用户状态 | 探测结果是 cutover 的前置判据：无状态 → 允许直接切；有状态 → 必须走 `M-AC-02`。**探测失败或结果不确定一律按"有状态"处理** | 29 |
| `M-AC-02` | appid-cutover | 旧安装存在 plan/task/attempt/result/session 数据，执行版本化迁移桥 | 新 `applicationId` 下逐表数量与摘要与旧库一致；缺任一表或摘要不符即失败，不得部分迁移后继续 | 29,24 |
| `M-AC-03` | appid-cutover | 迁移桥失败或中断后回滚 | 旧 App 与其数据保持可用且未被移除；回滚后可重试，不产生半迁移状态 | 29,24 |
| `M-AC-04` | appid-cutover | 试图用现有结果 CSV 冒充完整迁移 | 拒绝：`AttemptCsvMapper` 只导出审计结果、`importCsv` 只收 worklist，历史结果与账本无回灌路径 | 29 |
| `M-AC-05` | appid-cutover | 迁移产物或 operator 数据被写入仓库 / 日志 | 静态扫描失败；迁移 bundle 只存在于设备与 operator 控制的位置 | 29,18 |
| `M-MP-01` | multiproc | 主进程与 `:hook_verify` 同时触发 revision bump | 两次 bump 都不丢失，单调不回退 | 25 |
| `M-MP-02` | multiproc | revision owner 进程重启，代际连续性不可证 | bump + coverage 降级 | 25,8 |
| `M-MP-03` | multiproc | `FileObserver` 被回收后重订阅，期间有变化 | bump + 降级；不得把"没收到事件"当成"没有变化" | 25,9 |
| `M-BP-08` | bypass | 存在绕过 owner 的 revision 写路径（任一非 owner 进程直写 store） | 静态 guard 测试失败；检测写路径而非库名 | 25 |
| `M-BP-09` | bypass | revision 只存在内存中，进程重启后回退 | 违反 L3，测试失败 | 25 |
| `M-PA-06` | pairing | Auto 首次连接遇到未见过的 provider signer | 停在本地 `NOT_PAIRED` 等 operator 显式批准；**不得自动落为 trusted** | 2 |
| `M-PA-07` | pairing | Auto 撤销 provider 后发起新 run | 预检 fail-closed 停在 `NOT_PAIRED`，不进入 CellRebel | 2 |
| `M-PA-08` | pairing | Auto 在 run 进行中撤销 provider | 当前 attempt 进入 release/recovery；不静默继续；release 不可证时按 INV-21 暂停 | 2,21 |
| `M-PA-09` | pairing | 千网游撤销 caller 后 Auto 继续调用 | 立即 typed 失败，active lease 进入 release/recovery | 2,14 |
| `M-PA-10` | pairing | 撤销后同一 (applicationId, signer) 再次出现 | 必须重新走 operator 批准；不得因历史记录存在而自动复活 | 2 |
| `M-PA-11` | pairing | 撤销前已写入的可信配额 | 不回溯撤销；撤销事件进审计，历史可解释 | 2,10,18 |
| `M-TU-01` | tuple | `HOOK` + `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED` | fail-closed，不计数 | 6,27 |
| `M-TU-02` | tuple | `isMock=false`（或 null）+ `VERIFIED` | fail-closed，不计数 | 27 |
| `M-TU-03` | tuple | `DENIED`/`WAIT_UNTIL` + `VERIFIED` | fail-closed，不计数 | 17,27 |
| `M-TU-04` | tuple | `coverage=FULL` + `continuitySince=null` | fail-closed，不计数 | 8,9,27 |
| `M-TU-05` | tuple | `continuitySince > pre.observedAt` | 连续性窗未覆盖观察，不计数 | 8,27 |
| `M-TU-06` | tuple | `post.observedAt` 早于 CellRebel 完成时刻 | 后置观察不成立，不计数 | 7,27 |
| `M-TU-07` | tuple | `evidenceRefs` 为空 + `VERIFIED` | 无可复核证据，不计数 | 18,27 |
| `M-LS-01` | lease | 存在 `ACTIVE` lease 时另一 caller `apply` | `LEASE_CONFLICT` | 14,16,28 |
| `M-LS-02` | lease | 存在 `RELEASE_INCOMPLETE` lease 时新 `apply` | `LEASE_CONFLICT`；不得因"已 release 过"放行 | 21,28 |
| `M-LS-03` | lease | lease 到 `deadline` 后新 `apply` | `EXPIRED` 仍阻挡；TTL 不是 INV-21 的旁路 | 21,28 |
| `M-LS-04` | lease | **qwy 撤销 caller** 后 lease 进入 `REVOKED` | 阻挡新 apply；**由 qwy 内部自清理**推过 `RELEASING → RELEASED`；原 caller 全部调用被拒 | 2,28 |
| `M-LS-08` | lease | **Auto 撤销 provider** 后 in-flight lease | Auto 仍被授权，正常 `release` 收敛；不得走 qwy 自清理路径 | 2,28 |
| `M-LS-09` | lease | 被撤销的 caller 尝试 `release` 一个 `REVOKED` lease | `CALLER_NOT_ALLOWED`；**不得**为其保留任何 post-revoke 能力 | 2,28 |
| `M-LS-10` | lease | apply 之后系统墙钟前跳/后跳数小时 | `EXPIRED` 触发时刻不变（只由 `deadlineElapsedRealtimeMs` 决定） | 28 |
| `M-LS-11` | lease | `deadlineEpochMs ≤ nowEpoch` 的 apply | 立即到期，不得因负数绕回变成超长 lease | 28 |
| `M-LS-12` | lease | **状态 ∈ {`ACQUIRING`,`ACTIVE`}** + qwy 重启 + 干净性**可证** + `applyOwnerGeneration ≠ 当前 generation` | `EXPIRED`（原 caller 可 `release` 收敛）。**不适用于其他状态**——`REVOKED`/`RELEASE_INCOMPLETE` 见 `M-LS-15/16`，`RELEASING` 见 `M-LS-17` | 25,28 |
| `M-LS-13` | lease | **状态 ∈ {`ACQUIRING`,`ACTIVE`}** + **设备 reboot** 后单调时钟纪元改变 + 干净性**可证** | 与 `M-LS-12` 同一判定（reboot 必然改 generation）→ `EXPIRED`；断言绝对 `deadlineElapsedRealtimeMs` **不得**被原值裸比较。**干净性不可证时不适用本行**——无论进程重启还是设备 reboot，一律先落 `M-LS-07` | 25,28 |
| `M-LS-14` | lease | **状态 = `ACTIVE`** + 普通进程重启（未 reboot、时钟仍可比）+ 干净性**可证** | 仍强制 `EXPIRED`——明示的 false-red 策略，非意外 | 25,28 |
| `M-LS-15` | lease | **状态 = `REVOKED`** + qwy 重启 + 干净性**可证或不可证（两种都测）** | **必须保持 `REVOKED`**，qwy 内部自清理仍可达；**不得**被 `M-LS-07`/`M-LS-12` 的规则改写（那会让出口对已失权的 caller 不可达） | 2,25,28 |
| `M-LS-16` | lease | **状态 = `RELEASE_INCOMPLETE`** + qwy 重启 + 干净性**可证或不可证（两种都测）** | 原样保留，仍要求 operator 人工恢复证据；不得被改写 | 21,25,28 |
| `M-LS-17` | lease | `RELEASING` lease + qwy 重启 | 幂等重放 release；无法证明清理完成 → `RELEASE_INCOMPLETE` | 13,21,28 |
| `M-LS-05` | lease | 同 caller 同 `idempotencyKey` 重放 `apply` | 幂等返回原 receipt，不冲突 | 13,28 |
| `M-LS-06` | lease | 同 caller 不同 key/不同 intentHash 再 `apply` | `LEASE_CONFLICT` | 16,28 |
| `M-LS-07` | lease | **状态 ∈ {`ACQUIRING`,`ACTIVE`}** + qwy 重启 + 环境干净性**不可证** | 从持久态重建 → `RELEASE_INCOMPLETE` + bump/降级。**不适用于其他状态**：`REVOKED`/`RELEASE_INCOMPLETE` 无论干净性可否证都原样保留（`M-LS-15/16`），`RELEASING` 走 `M-LS-17` | 25,28 |
| `M-ID-01` | idempotency | 同 `idempotencyKey` 异 payload | `IDEMPOTENCY_CONFLICT`（不得复用 `LEASE_CONFLICT`） | 13 |
| `M-ID-02` | idempotency | 同一请求换 `operationId` 重试 | **不得**冲突——`operationId` 不在 §6.3.4 preimage 内 | 13 |
| `M-ID-03` | idempotency | 构造使 `apply` 与 `release` 字段字节序列相同的输入 | domain separation 使两者 digest 不同 | 13 |
| `M-RQ-01` | request | **请求**结构性非法：必填 ref 为空 / 坐标越界 / `deadline ≤ notBefore` | qwy 返回 `REQUEST_INVALID`，不得落到 `INTERNAL_FAILURE` | 4 |
| `M-RS-01` | response | **应答**结构性非法：`PreflightReportV1` 的 `scheduleDecision == WAIT_UNTIL` 却缺 `waitUntilEpochMs` | Auto consumer **fail-closed**：不进入可信判定、不启动 CellRebel、写未验证并记 typed reason。**不得**映射为 `REQUEST_INVALID`——那会把 provider 缺陷伪装成调用方错误 | 3,4,27 |
| `M-CF-01` | config | 运行中改 `TRUSTED_LOCATION_TOLERANCE_METERS` 或 `requiredVerification` | 在飞 attempt continue 使用 `PlanSnapshot` 冻结值；新值只对新 plan version/地址边界生效 | 17 |
| `M-CF-02` | config | 运行中改容差后 in-flight attempt 恰好越过新阈值 | 仍按冻结快照判定，结果不因中途改配置而翻转 | 17 |
| `M-PA-12` | pairing | 同 signer + 新 versionCode（任一侧正常升级） | 保持配对，由 protocol handshake 决定兼容；**不得要求重新配对** | 2,3,19 |

### 10.1 矩阵行 → evidence class / owner / 精确入口（表 4）

Task 7 此前同时承诺三件事：Sol 覆盖 §10 全部行、测试只消费 public v1 contract、Sol 不写 Auto core。**这三件在当前结构下不可能同时成立**——ledger 事务、migration、崩溃窗口、状态机边都是内部窗口，公开契约触达不到；Sol 若要测只能写进 `apps/cellrebel-auto/**`，违反 owner matrix。

**决议：不为测试在生产代码里开 driver seam。** 生产面为测试而扩大，正是本 spec 反复拒绝的模式。改为按证据类型逐行分工，并让分工**可被构建证明**。

**行 ID 规则**：ID 已显式写入 §10 表首列，**一经分配永不重排、永不复用**。新增行取该类别前缀下未使用的下一个序号；删除行时 ID 退役而不回收。**禁止**由行序推导序号——插入一行就会让既有 ID 整体错位，令历史证据失效。

**evidence class**：

| class | 谁写 | 触达方式 |
|---|---|---|
| `owner-red` | 代码 owner 在自己的 unit test 内 | 进程内 fake + 在 durable write 与外部调用之间注入故障 |
| `sol-blackbox` | Sol | 只经 public v1 contract + `acceptance/fake-qwy` |
| `static-guard` | Sol | `acceptance/scripts/` 下的静态扫描，无运行时 |
| `device` | Sol（授权 device lease 内） | exact-build 真机证据 |

owner 是该行的**主责方**——即"若该行失败，谁必须改代码"。对端的消费行为由另一条独立行覆盖（例：`M-CC-03` 由 DeepSeek Flash 证明 provider 拒绝冲突 lease，`M-RL-01` 由 Sol 证明 Auto 正确处置返回的 typed error），因此每行只有一个 owner 与一个入口，不存在共管。

| ID | 类别 | evidence class | owner | 精确入口 |
|---|---|---|---|---|
| `M-CR-01` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_01` |
| `M-CR-02` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_02` |
| `M-CR-03` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_03` |
| `M-CR-04` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_04` |
| `M-CR-05` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_05` |
| `M-CR-06` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_06` |
| `M-CR-07` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_07` |
| `M-CR-08` | crash | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_08` |
| `M-CR-09` | crash | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/CrashMatrixTest.kt::M_CR_09` |
| `M-CC-01` | concurrency | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConcurrencyMatrixTest.kt::M_CC_01` |
| `M-CC-02` | concurrency | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConcurrencyMatrixTest.kt::M_CC_02` |
| `M-CC-03` | concurrency | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/ConcurrencyMatrixTest.kt::M_CC_03` |
| `M-CC-04` | concurrency | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/ConcurrencyMatrixTest.kt::M_CC_04` |
| `M-RC-01` | recovery | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/RecoveryMatrixTest.kt::M_RC_01` |
| `M-CO-01` | completion | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_01` |
| `M-CO-02` | completion | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_02` |
| `M-CO-03` | completion | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_03` |
| `M-CO-04` | completion | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_04` |
| `M-CO-05` | completion | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_05` |
| `M-CO-06` | completion | `device` | Sol | `docs/acceptance/a-plus-device-matrix.md#M-CO-06` |
| `M-RC-02` | recovery | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/RecoveryMatrixTest.kt::M_RC_02` |
| `M-RC-03` | recovery | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/RecoveryMatrixTest.kt::M_RC_03` |
| `M-RC-04` | recovery | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/RecoveryMatrixTest.kt::M_RC_04` |
| `M-BP-01` | bypass | `static-guard` | Sol | `acceptance/scripts/check-forbidden-boundaries.sh::M-BP-01` |
| `M-BP-02` | bypass | `static-guard` | Sol | `acceptance/scripts/check-forbidden-boundaries.sh::M-BP-02` |
| `M-BP-03` | bypass | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/BypassMatrixTest.kt::M_BP_03` |
| `M-BP-04` | bypass | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/BypassMatrixTest.kt::M_BP_04` |
| `M-BP-05` | bypass | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/BypassMatrixTest.kt::M_BP_05` |
| `M-BP-06` | bypass | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/BypassMatrixTest.kt::M_BP_06` |
| `M-BP-07` | bypass | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/BypassMatrixTest.kt::M_BP_07` |
| `M-RL-01` | release | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/ReleaseMatrixTest.kt::M_RL_01` |
| `M-VS-01` | version | `device` | Sol | `docs/acceptance/a-plus-device-matrix.md#M-VS-01` |
| `M-VS-02` | version | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/VersionMatrixTest.kt::M_VS_02` |
| `M-PA-01` | pairing | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_01` |
| `M-PA-02` | pairing | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_02` |
| `M-IN-01` | intent | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/IntentMatrixTest.kt::M_IN_01` |
| `M-IN-02` | intent | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/IntentMatrixTest.kt::M_IN_02` |
| `M-IN-03` | intent | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/IntentMatrixTest.kt::M_IN_03` |
| `M-IN-04` | intent | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/IntentMatrixTest.kt::M_IN_04` |
| `M-PA-03` | pairing | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_03` |
| `M-PA-04` | pairing | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_04` |
| `M-PA-05` | pairing | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/PairingMatrixTest.kt::M_PA_05` |
| `M-MG-01` | migration | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_01` |
| `M-MG-02` | migration | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_02` |
| `M-MG-03` | migration | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_03` |
| `M-MG-04` | migration | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_04` |
| `M-MG-05` | migration | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_05` |
| `M-AC-01` | appid-cutover | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AppIdCutoverMatrixTest.kt::M_AC_01` |
| `M-AC-02` | appid-cutover | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AppIdCutoverMatrixTest.kt::M_AC_02` |
| `M-AC-03` | appid-cutover | `device` | Sol | `docs/acceptance/a-plus-device-matrix.md::M_AC_03`（真机回滚演练，需设备 lease 与 exact APK SHA） |
| `M-AC-04` | appid-cutover | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AppIdCutoverMatrixTest.kt::M_AC_04` |
| `M-AC-05` | appid-cutover | `static-guard` | Sol | `scripts/check-forbidden-boundaries.sh::no-operator-data-in-repo-or-log` |
| `M-MP-01` | multiproc | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/MultiProcessMatrixTest.kt::M_MP_01` |
| `M-MP-02` | multiproc | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/MultiProcessMatrixTest.kt::M_MP_02` |
| `M-MP-03` | multiproc | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/MultiProcessMatrixTest.kt::M_MP_03` |
| `M-BP-08` | bypass | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/BypassMatrixTest.kt::M_BP_08` |
| `M-BP-09` | bypass | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/BypassMatrixTest.kt::M_BP_09` |
| `M-PA-06` | pairing | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_06` |
| `M-PA-07` | pairing | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_07` |
| `M-PA-08` | pairing | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_08` |
| `M-PA-09` | pairing | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/PairingMatrixTest.kt::M_PA_09` |
| `M-PA-10` | pairing | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_10` |
| `M-PA-11` | pairing | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_11` |
| `M-TU-01` | tuple | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_01` |
| `M-TU-02` | tuple | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_02` |
| `M-TU-03` | tuple | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_03` |
| `M-TU-04` | tuple | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_04` |
| `M-TU-05` | tuple | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_05` |
| `M-TU-06` | tuple | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_06` |
| `M-TU-07` | tuple | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_07` |
| `M-LS-01` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_01` |
| `M-LS-02` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_02` |
| `M-LS-03` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_03` |
| `M-LS-04` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_04` |
| `M-LS-05` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_05` |
| `M-LS-06` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_06` |
| `M-LS-07` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_07` |
| `M-LS-08` | lease | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/LeaseMatrixTest.kt::M_LS_08` |
| `M-LS-09` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_09` |
| `M-LS-10` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_10` |
| `M-LS-11` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_11` |
| `M-LS-12` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_12` |
| `M-LS-13` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_13` |
| `M-LS-14` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_14` |
| `M-LS-15` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_15` |
| `M-LS-16` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_16` |
| `M-LS-17` | lease | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_17` |
| `M-ID-01` | idempotency | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/IdempotencyMatrixTest.kt::M_ID_01` |
| `M-ID-02` | idempotency | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/IdempotencyMatrixTest.kt::M_ID_02` |
| `M-ID-03` | idempotency | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/IdempotencyMatrixTest.kt::M_ID_03` |
| `M-RQ-01` | request | `owner-red` | DeepSeek Flash | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/RequestMatrixTest.kt::M_RQ_01` |
| `M-RS-01` | response | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/ResponseMatrixTest.kt::M_RS_01` |
| `M-CF-01` | config | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConfigMatrixTest.kt::M_CF_01` |
| `M-CF-02` | config | `owner-red` | Opus5 | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConfigMatrixTest.kt::M_CF_02` |
| `M-PA-12` | pairing | `sol-blackbox` | Sol | `acceptance/scenarios/src/test/kotlin/matrix/PairingMatrixTest.kt::M_PA_12` |

**覆盖校验（`scripts/verify-a-plus.sh`）** 必须做三件事，缺一不可：

1. **集合相等**：从 §10 表首列提取 ID 集合，从上表提取 ID 集合，两者**必须完全相等**；任一侧多出或缺失即 exit≠0。这让两张表不可能悄悄漂移。
2. **绑定已执行结果**：从各 lane 的**测试报告**（JUnit XML / device evidence 文件）中提取实际**执行且通过**的用例标识，与 ID 集合比对。**不接受对源码 grep token**——ID 出现在注释里、出现在被 `@Ignore` 的用例上、或出现在一个不含对应断言的方法名里，都会让纯文本扫描变绿而实际零执行。
3. **未覆盖必须显式，且区分两种原因**——把它们混成一类会让"等人拍板"看起来像"永远做不了"：

   | 标注 | 含义 | 处置 |
   |---|---|---|
   | `not-testable` | **四类都无法触达**，是观察面/平台的永久上限 | §10 该行标注并链接上限说明（§18.1、§8.6.5）；不计入覆盖，但不阻塞最终 gate |
   | `deferred:<DP-x>` | **可触达，但预期终态待 operator 决定** | §10 该行标注并链接对应 DP；**verifier 必须让最终 gate 失败**直到该 DP 有结论；结论落地后该行必须变为具体断言并正常执行 |

   `deferred` 行仍保留其 evidence class、owner 与精确入口（它是可写的，只是还不知道该断言什么），**静默留空一律视为失败**。

**evidence manifest（冻结载体）**：上面第 2 条不能停在"从 JUnit XML 提取"——`static-guard` 不产 JUnit，`device` 的 markdown 存在也不证明执行过，且 `M-CR-01` 与方法名里的 `M_CR_01` 需要规范化。因此每条 lane 在跑完后必须产出一份机器可读清单，`verify-a-plus.sh` 只消费它：

```json
[
  {
    "rowId": "M-CR-01",
    "exactHead": "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
    "lane": "auto-unit",
    "status": "passed",
    "testId": "com.example.cellrebelauto.matrix.CrashMatrixTest#M_CR_01",
    "reportDigest": "3f786850e387550fdab836ed7e6dc881de23001b3f786850e387550fdab836ed"
  },
  {
    "rowId": "M-CO-03",
    "exactHead": "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
    "lane": "auto-unit",
    "status": "passed",
    "testId": "com.example.cellrebelauto.matrix.CompletionMatrixTest#M_CO_03",
    "reportDigest": "b1946ac92492d2347c6235b4d2611184b1946ac92492d2347c6235b4d2611184"
  }
]
```

**`deferred` 状态本身仍在 schema 内**，供将来出现新的待决 DP 时使用；但自 DP-3 由 operator 决定（§21.0）起，**当前没有任何行处于 `deferred`**——`M-CO-03` 的 `deferredOn: "DP-3"` 已解除，上面的实例相应改为 `passed` 形态。"只要存在 deferred 记录最终 gate 一律失败"这条规则不变。

上面是**两条真实可解析的实例**，不是带注释的示意——载体必须能被 verifier 直接 `JSON.parse`，因此**不含注释、不含联合类型占位、不含互斥字段并存**。字段的取值域与逐 status 必填性由下方表格规定，**表是规范，实例只是样例**。

- **容器与产出位置（冻结）**：清单是一个 JSON **数组**。**每条 lane 各自产出自己的片段，不共写一个文件**（否则会跨 owner 写入，违反 owner matrix）：

  | lane | 产出路径 |
  |---|---|
  | `auto-unit` | `apps/cellrebel-auto/app/build/matrix-evidence.json` |
  | `qwy-unit` | `apps/qianwangyou/app/build/matrix-evidence.json` |
  | `acceptance` | `acceptance/build/matrix-evidence.json` |
  | `static-guard` | `acceptance/build/matrix-evidence-guard.json` |
  | `device` | `docs/acceptance/matrix-evidence-device.json` |

  `scripts/verify-a-plus.sh` 合并全部片段后再做三项校验；同一 `rowId` 在多个片段中出现即为冲突，直接失败（一行只有一个 owner，不该有两个 lane 声称覆盖它）。
- **规范化**：`rowId` 一律用 §10 表的连字符形式；从测试方法名回推时把 `_` 归一为 `-` 后比对。
- **HEAD 绑定**：每条 `exactHead` 必须等于被验的 PR HEAD；不等即 exit≠0，防止用旧跑的报告充数。
- **status 与字段必填性（逐 status 冻结）**：

  | status | `testId` | `reportDigest` | `deferredOn` | 计入覆盖 | 对最终 gate |
  |---|---|---|---|---|---|
  | `passed` | 必填 | 必填 | 必须缺省 | 是 | 通过 |
  | `failed` | 必填 | 必填 | 必须缺省 | 否 | 失败 |
  | `skipped` | 必填 | 必填 | 必须缺省 | 否 | 失败 |
  | `deferred` | **必须缺省** | **必须缺省** | **必填** | 否 | **失败** |

  `deferred` 行**不得**填 `testId`/`reportDigest`：那一行还没有可执行断言，填了就是在假装跑过一个不存在的报告。它表示"还没人告诉我该断言什么"，不是一种通过；**只要清单中存在任一 `deferred` 记录，最终 gate 一律失败**。对应 DP 落地后，该行必须转为正常可执行断言并产出真实 `testId`/`reportDigest`。
- **`reportDigest` 的规范定义（冻结）**：`SHA-256` 对**原始报告文件的字节流**求摘要，小写 hex，无前缀。"原始报告"指该 lane 真实产出的那一个文件——`auto-unit`/`qwy-unit`/`acceptance` 为 JUnit XML，`static-guard` 为 guard 的原始输出文件，`device` 为设备证据文件。
- **raw report 必须可定位**：每条记录的 `reportDigest` 必须能在同 lane 的 `build/reports/**`（或 `device` 的 `docs/acceptance/**`）下找到**字节完全一致**的文件，否则失败。摘要不是自证，它是指向证据的指针；找不到被指向物就等于没有证据。
- **同报告内的绑定**：记录里的 `testId` 必须在该原始报告中出现，且其 outcome 与本记录的 `status` **一致**。缺这一条时，清单可以声称 `passed` 而报告里写着 failed——那样 manifest 又退回成自说自话，正是引入它要消除的东西。
- 清单本身进 PR evidence，Sol 的矩阵报告消费它，而不是逐行手工声明。

Task 7 的表述同步改为：Sol 负责 `sol-blackbox`/`static-guard`/`device` 三类的编写与执行，并对 `owner-red` 行做 **evidence audit**（核对报告中存在该 ID 的通过用例、绑定 exact HEAD、断言与该行预期终态一致）。Sol 不写 `owner-red` 测试，也不再声称"为每一行提供失败场景"。

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
│       │   ├── EnvironmentIntentV1.aidl
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
│           ├── EnvironmentIntentV1.kt
│           ├── CanonicalIntentDigestV1.kt
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
├── acceptance/                         # Sol 独占
│   ├── fixtures/
│   ├── fake-qwy/src/...
│   ├── scenarios/src/test/...
│   └── scripts/
│       └── check-forbidden-boundaries.sh
├── scripts/                            # Opus5 独占
│   ├── check-provenance.sh
│   ├── check-contract-v1.sh
│   └── verify-a-plus.sh                # 聚合器：调用上面两条 + acceptance/scripts/**
└── .github/workflows/android-a-plus.yml
```

### 12.1 Owner matrix

| Owner | 独占写入范围 | 可读依赖 | 禁止并行触碰 |
|---|---|---|---|
| Opus5 | `contracts/**`、`apps/cellrebel-auto/**`（**含 `app/src/main/AndroidManifest.xml`**）、`.github/**`、root `scripts/**`（**不含 `acceptance/scripts/**`**）、ownership map；仅在串行 PR-2 修改两 App 的 Gradle contract 接线 | 全仓 | PR-3 开始后不触碰 `apps/qianwangyou/**`、`acceptance/**` |
| DeepSeek Flash | `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/**`、对应 qwy tests、qwy Manifest/Gradle 的集成行 | frozen contract | contract、Auto、acceptance |
| Sol | `acceptance/**`、`docs/acceptance/**`、验收 issue 与证据 | contract 与两 App | Opus5/DeepSeek Flash 产品实现 |
| GLM | review verdict、对抗执行报告；若补测试代码则单独 PR | 全仓 | 不修改正在审的作者 branch |

并行成立条件：Contract PR exact HEAD 冻结后，Opus5 的 Auto consumer、DeepSeek Flash 的 qwy provider、Sol 的 fake provider/scenario acceptance 三个目录无重叠，可并行。任何 contract delta 先停三路、回主 Thread 重新冻结，不允许三方各自兼容。

## 13. 分步 TDD 实施计划

### Task 1 — 导入远端基线与建立 ownership/CI

**Owner:** Opus5

**Files:**

- Create: `docs/provenance/upstream-imports.md`
- Create: `docs/architecture/ownership/README.md`
- Create: `.github/workflows/android-a-plus.yml`
- Create: `scripts/verify-a-plus.sh`
- Create: `scripts/check-provenance.sh`
- Import: `apps/cellrebel-auto/**`
- Import: `apps/qianwangyou/**`

**RED:** 在空目标路径运行 `scripts/check-provenance.sh --stage import`，必须因两个 app 未导入和 SHA 未登记失败。

**GREEN:** 只从远端精确 SHA subtree 导入；记录源 URL、branch、SHA、导入 commit。不得读取本机脏 worktree 作为拷贝源。

**Verify:**

```bash
./scripts/check-provenance.sh --stage import
```

**`--stage` 是必填的，没有默认值**（PR-1 实现如此）。原因是两种默认都有害：默认严格会让 PR-2/3/4 里第一次**合法**修改 app 源码就永久性地让 CI 变红；默认宽松则会静默丢掉 PR-1 最强的那条检查（当前 HEAD 树仍与上游逐字节相同）。因此由调用方声明 stage：

| stage | 检查内容 |
|---|---|
| `import` | 全部检查 + **当前 HEAD 树仍与上游 root tree 逐字节相同** |
| `contract` / `full` | 记录的 import commit 仍携带上游 root tree（**不可变锚点，任何 stage 都查**），但允许 app 树在其后合法演进 |

CI workflow 在 app 仍应保持 pristine 期间传 `--stage import`；当它们**合法**开始分叉时，移动的就是那一行。

> 本节此前写的是不带 `--stage` 的裸命令。那条命令在 PR-1 的实现下会 `exit 1`（`--stage is required`）——**真相源记录了一条必然失败的验证命令**。此处更正；教训与 §0.1.3 第 1 项同类：改了一条被下游引用的契约，必须回头扫全部引用点。

checker 必须做**有证明力**的核对，逐项 exit-code 化：

1. `docs/provenance/upstream-imports.md` 精确记录两个上游 SHA（`48d8ec93…` / `285e4cae…`）与源 URL、branch、导入 commit；
2. 本地 `apps/cellrebel-auto` 的 **root tree digest** 等于 `Faketest@48d8ec9` 的 tree digest；`apps/qianwangyou` 对 `FakeGps-test@285e4ca` 同理。checker 必须**先显式 `git fetch <upstream-url> <sha>` 把该对象取到本地**再 `git rev-parse <sha>^{tree}`——CI 的浅 clone 不含上游对象，跳过 fetch 会让比对因"对象不存在"而误判或静默跳过；取不到对象必须 fail，不得降级为 skip；
3. 关键入口文件存在（两个 `gradlew`、两个 `app/build.gradle*`、两个 `AndroidManifest.xml`）。

**不得**使用 `git -C <dir> rev-parse --is-inside-work-tree` 作为验证：subtree 目录本身就在 `fakexxx` 工作树内，该命令对仓内任何 `mkdir` 出来的空目录同样返回 `true`，既证明不了导入发生，也证明不了 SHA 正确——它是恒真断言，没有证明力。

### Task 2 — 冻结 contract v1 与兼容矩阵

**Owner:** Opus5

**Reviewer:** Sol（语义）+ GLM（对抗）

**Files:**

- Create: `contracts/environment-control-v1/src/main/aidl/io/github/terryyyc/fakexxx/contract/v1/IEnvironmentControlV1.aidl`
- Create: 同目录 `CapabilitySnapshotV1.aidl`、`EnvironmentIntentV1.aidl`、`PreflightRequestV1.aidl`、`PreflightReportV1.aidl`、`ApplyRequestV1.aidl`、`ApplyReceiptV1.aidl`、`ObserveRequestV1.aidl`、`EnvironmentObservationV1.aidl`、`ReleaseRequestV1.aidl`、`ReleaseReceiptV1.aidl`
- Create: `contracts/environment-control-v1/src/main/java/io/github/terryyyc/fakexxx/contract/v1/CapabilitySnapshotV1.kt`
- Create: 同目录（即 `src/main/java/io/github/terryyyc/fakexxx/contract/v1/`）`EnvironmentIntentV1.kt`、`CanonicalIntentDigestV1.kt`、`PreflightRequestV1.kt`、`PreflightReportV1.kt`、`ApplyRequestV1.kt`、`ApplyReceiptV1.kt`、`ObserveRequestV1.kt`、`EnvironmentObservationV1.kt`、`ReleaseRequestV1.kt`、`ReleaseReceiptV1.kt`、`ContractEnumsV1.kt`、`ContractErrorCodeV1.kt`
- Create: `contracts/environment-control-v1/build.gradle.kts`
- Create: `contracts/environment-control-v1/consumer-rules.pro`
- Create: `contracts/environment-control-v1/compatibility.yaml`
- Create: `contracts/environment-control-v1/src/test/java/io/github/terryyyc/fakexxx/contract/v1/ContractRoundTripTest.kt`
- Modify: `apps/cellrebel-auto/settings.gradle.kts`（只接入 contract library）
- Modify: `apps/qianwangyou/settings.gradle`（只接入 contract library）
- Modify: 两 App 的 app build 文件（只增加 contract dependency）
- Modify: `apps/cellrebel-auto/app/src/main/AndroidManifest.xml`（新增千网游两个 applicationId 的 `<queries>`）
- Create: `scripts/check-contract-v1.sh`

**RED:** missing `verificationLevel`、枚举 ordinal 信任、未知 wire code、canonical digest 跨实现不一致、intent 绑定缺失、v1 字段语义漂移、旧/新版本不兼容矩阵均先写失败测试。

**GREEN:** 实现本文 §6 的 exact schema（含 §6.3.2 全部 DTO 与 §6.3.1 digest 算法）；v1 不引入泛化 command 或 Map payload。contract library `minSdk = 24`；两侧构建栈已核实一致（AGP 9.1.0 / Kotlin 2.2.10 / Gradle 9.3.1 / compileSdk 35 / Java 17），无需版本对齐工作。

**Verify:** `./scripts/check-contract-v1.sh`，预期全部 contract/compatibility tests PASS。

**Checkpoint:** exact contract HEAD 回主 Thread；只有该 HEAD 获得独立 verdict 后，Task 3/4/5 才开始并行。

### Task 3 — 千网游 provider、配对与连续性

**Owner:** DeepSeek Flash

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

**RED order:** unauthorized caller → **legacy(24–27) 签名降级路径拒绝多签名者** → idempotency → lease conflict → revision event sources → **跨进程并发 bump** → coverage downgrade → release ownership → process death → **owner 进程代际不连续**。

**GREEN:** 适配现有 profile/System Mock/Hook API，不复制其逻辑；无法完整观察时返回 `PARTIAL/NONE`，不伪造 FULL。

**跨进程硬约束（INV-25）**：按 §6.6 的 L1–L6 实现单写者 revision owner。`:hook_verify` 与主进程只能经同步 IPC（Binder 或非导出 `ContentProvider`）向 owner 请求 bump。

被禁止的是**架构形态**，不是库：

- 禁止「多个进程各自直接写同一份 store」——`SharedPreferences` 官方不支持多进程；`MultiProcessDataStore` 只承诺 eventual consistency，不满足 L5。
- 禁止纯内存计数器——违反 L3 的持久化与重启单调性。
- **owner 进程内部用什么存不受限制**：单进程 `DataStore`、Room、SQLite 都是合法选择，只要能证明 L3（序列化持久 read-modify-write）、L4（ACK 后于 durable commit）、L5（observe 看得见已 ACK 的 bump）。
- 静态 guard 检测的是**非 owner 写路径**，不是库名——按库名一刀切会既误杀合法实现又漏掉真正的旁路。

若本 task 新增任何持久 store，同样适用 INV-24：给出升级路径与进程死亡后的迁移证据。

**配对硬约束**：`PairingRecord` 主键 `(applicationId, current signerDigest)`，`versionCode` 仅审计不参与匹配（§6.5.4）；production 与 `.bench` 互不授权；首次配对走 §4.1 bind-first，候选记录来自 `Binder.getCallingUid()` 解析，不来自 UI 侧包扫描。

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
- Create: `.../LegacyCompletionDao.kt`
- Create: `.../ProviderPairingDao.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/environment/ProviderTrustStore.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/recovery/RecoveryCoordinator.kt`
- Test: 对应 `app/src/test/**`

- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/db/Migrations.kt`（`MIGRATION_4_5`）
- Modify: `apps/cellrebel-auto/app/build.gradle.kts`（`ksp { arg("room.schemaLocation", "$projectDir/schemas") }`）
- Create: `apps/cellrebel-auto/app/schemas/**`（导出的 Room schema JSON，纳入版本控制）
- Test: `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/db/Migration4to5Test.kt`

**Entity / DAO 路径冻结**：`LegacyCompletionSnapshot` 与 `ProviderPairingRecord` 两个 entity 都声明在既有的 `model/plan/Entities.kt` 中（沿用该文件既有的 entity 聚合惯例）；各自使用独立 DAO（`db/LegacyCompletionDao.kt`、`db/ProviderPairingDao.kt`），不复用 `PlanDao`——`LegacyCompletionSnapshot` 只在迁移时写一次且只读，`ProviderPairingRecord` 承载信任决定，两者都不应混进计划 CRUD 的通用 DAO（否则 INV-22 的旁路面被扩大）。`ProviderTrustStore.kt` 是 §6.5.3 的 lifecycle owner，DAO 之上只暴露 `findActive` / `approve` / `revoke` 三个窄入口，不暴露通用 `upsert`/`delete`——信任决定与撤销都必须经这三个方法，否则 INV-22 的旁路面被扩大到信任面上。

**RED order:** state census schema → **v4 真实 fixture 升级失败** → UNIQUE ledger → pre-existing execution → crash windows → concurrent insert → closed-state bypass。

**GREEN:** 可信完成只通过 `TrustPolicy` + 单一 ledger transaction；删除旧的直接 `completedSuccesses++` 写路径，完成数改为投影。

**迁移硬约束（INV-24）**：现网 `AppDatabase` 是 `version = 4` 且 `exportSchema = false`，已有用户数据。本 task 新增 `TrustedQuotaEntry`/`CellRebelExecution`/`AutoAuditEvent`/`LegacyCompletionSnapshot`/`ProviderPairingRecord` **五类表**（五张都进 v5 Room schema）→ 必须 `version = 5` 且提供显式 `MIGRATION_4_5`，同时把 `exportSchema` 改为 `true` 并把 schema JSON 纳入版本控制（千网游侧已有同款 `room.schemaLocation` 配置可参照）。**禁止 `fallbackToDestructiveMigration` 及任何变体**——缺失迁移会让老用户在升级后开库即 `IllegalStateException`，而 destructive fallback 会直接清空 operator 已导入的计划与历史结果，两者都违反“用户状态默认持久化”。迁移测试必须用**手工构建的真实 v4 fixture 库**（既有 `MigrationTest.kt` 的 v2 手法可直接复用），断言历史计划、任务与结果全部存活。

**旧进度语义（必须冻结，两个方向都是错的）**：upstream `48d8ec9` 的 v4 把历史成功次数放在 `LocationTask.completedSuccesses: Int`（另有 `status: String`）。v5 把完成数改为 `count(TrustedQuotaEntry)` 投影后，两条自然做法都不可接受：

- 直接改投影而不管旧值 → 新 ledger 为空，**operator 的历史进度无声归零**；
- 把旧 `completedSuccesses` 回填成 `TrustedQuotaEntry` → 这些旧数据**没有 A+ 的证据链**（无 observation、无 intent hash、无连续性证明），直接违反 INV-05/06。

冻结做法：迁移时把 v4 的 `completedSuccesses`/`status` 搬进独立的 **`LegacyCompletionSnapshot`**（`taskId`、`legacyCompletedSuccesses`、`legacyStatus`、`migratedFromSchemaVersion`、`migratedAt`），语义为 `LEGACY_UNVERIFIED`：

- 历史数据与 UI 展示**保留**，operator 看得到"迁移前已完成 N 次（未按 A+ 证据标准验证）"；
- **绝不生成 `TrustedQuotaEntry`**，不进入可信配额、不进入导出的 trusted 结果；
- A+ 的 trusted quota 对每个 task **从 0 开始**计；
- 该快照只读、只在迁移时写入一次，不参与任何后续判定。

必测：一个 `completedSuccesses` 非零且存在 active/completed plan 的 v4 fixture，升级后断言——旧进度可见且标为 legacy-unverified、`TrustedQuotaEntry` 表为空、`LocationTask.completed` 投影为 false（除非新 ledger 真的达标）、恢复流程不把 legacy 计数当作已完成而跳过地址、**`ProviderPairingRecord` 表已创建且初始为空**（升级不得凭空产生一条被信任的 provider——那等于用迁移绕过 §6.5.3 的 operator 批准）。

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

**Owner:** Opus5（Auto）/ DeepSeek Flash（千网游，各自在独占目录）

**Reviewer:** GLM；Sol 走用户旅程验收

**Files:**

- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/PlanScreen.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/ControlScreen.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/HistoryScreen.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/PairingStatusCard.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/ProviderApprovalScreen.kt`（§6.5.3 的 operator 批准**与撤销**入口：展示待批准候选的 applicationId / 当前 signer 摘要 / 来源，以及已批准 provider 列表与撤销动作；批准前不得进入可信判定）
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/ui/AutomationPairingScreen.kt`
- Modify: qwy navigation/settings files only in DeepSeek Flash branch

**RED:** Compose state tests 先覆盖未配对、**provider 待 operator 批准**、不兼容、可信、未验证、recovery-required、release-incomplete 七种现场状态。

**GREEN:** 默认页保持一键模板；高级配置不出现；错误给具体恢复动作。

**Verify:** 两 App unit/lint/assemble + 真机旅程截图；不得只附代码截图。

### Task 7 — 独立 fake provider 与对抗场景

**Owner:** Sol

**Reviewer:** GLM

**Files:**

- Create: `acceptance/fake-qwy/src/main/.../FakeEnvironmentControlService.kt`
- Create: `acceptance/scenarios/src/test/kotlin/matrix/**`（**只承担 §10.1 台账中 `sol-blackbox` 类的 22 行**，文件名与方法名按台账「精确入口」列）
- Create: `docs/acceptance/a-plus-device-matrix.md`（承担 `device` 类 2 行）
- Create: `acceptance/scripts/check-forbidden-boundaries.sh`（承担 `static-guard` 类 2 行）

**Scope（按 §10.1 台账，不再是"全部行"）：** §10 共 **95 行 / 18 类**（新增 `appid-cutover` 5 行，承载 `INV-29`）。

| class | 行数 | Sol 的职责 |
|---|---|---|
| `sol-blackbox` | 22 | 编写并执行；只消费 public v1 contract + `acceptance/fake-qwy` |
| `static-guard` | 3 | 编写并执行静态扫描 |
| `device` | 3 | 在授权 device lease 内执行并留存证据 |
| `owner-red` | 67 | **不编写**；做 evidence audit——核对 evidence manifest 中该 ID 的 `passed` 记录、`exactHead` 相符、断言与该行预期终态一致 |

**RED:** 上述 28 行各自至少一个失败场景先红；`owner-red` 的 67 行由各自 owner 在自己的 lane 内先红（Opus5 34 行 / DeepSeek Flash 33 行）。

**GREEN:** fake provider 能返回重复 receipt、重启/丢 coverage、revision 漂移、stale/foreign lease、矛盾 tuple、binder death；Sol 的测试只消费公开 v1 contract——**这一约束现在与覆盖范围自洽**，因为那 **64 行 `owner-red`** 已归各自 code owner（Opus5 31 / DeepSeek Flash 33），由他们在自己的 lane 内证明。它们不是"无法测试"，只是**不该由 Sol 跨 owner 去测**；Sol 对它们的职责是 evidence audit。

**Verify:** `./scripts/verify-a-plus.sh` 执行 contract + 两 App unit + scenario + boundary guards，并做 §10.1 的三项覆盖校验：① §10 与 §10.1 的 ID 集合相等；② 覆盖绑定 evidence manifest 中 `status=passed` 且 `exactHead` 相符的记录；③ 未覆盖行必须显式区分 `not-testable`（永久上限）与 **`deferred:<DP-x>`**，且**清单中存在任一 `deferred` 记录时最终 gate 一律失败**。

### Task 8 — GLM 独立审查与 exact-HEAD 对抗验证

**Owner:** GLM（非产品代码作者）

1. 先审 DeepSeek Flash qwy provider：授权、revision 覆盖声明、idempotency、foreign lease、进程死亡。
2. 再审 Sol acceptance：是否存在 fake 只验证实现细节、未覆盖真实状态边、误把心跳当连续性。
3. 对 Opus5 Auto 做可信账本与 `PRE_EXISTING_RUN` 对抗审查。
4. 每个 finding 给 `block/approve`、精确文件/行、复现命令和 exact HEAD。
5. behavioral delta 后旧 verdict 失效，必须重跑受影响矩阵。

### Task 9 — 隔离真机验收与发布候选

**Owner:** Sol（验收）

**Independent reviewer:** GLM

**Merge authority:** operator only

真机动作必须另获设备 lease；使用公开测试坐标，禁止未经授权 uninstall/clear-data/生产 profile 写入。验收至少覆盖：

- 首次配对（两侧各一次显式批准）、签名变化；
- **撤销两侧各测一遍**：千网游撤销 caller allowlist、Auto 撤销 provider allowlist；各覆盖"撤销后发起新 run"与"run 进行中撤销"两种时机；
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

./acceptance/scripts/check-forbidden-boundaries.sh
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
  ├── PR-3 DeepSeek Flash：千网游 provider/continuity/security
  ├── PR-4 Opus5：Auto data/trust/recovery/core/UI
  └── PR-5 Sol：fake provider + acceptance/adversarial matrix
          ↓
PR-6 integration + exact-build device evidence（只做必要胶合，不吞并三路职责）
```

Task 6 的两半按 owner 分别随所属 PR 走，不单独成 PR：Auto 侧 UI 进 PR-4（Opus5），千网游侧 `integration/ui/AutomationPairingScreen.kt` 进 PR-3（DeepSeek Flash）。owner matrix 本身不变——`apps/qianwangyou/**/integration/**` 含 UI 全部归 DeepSeek Flash。

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
| I3 | `[P0] 千网游 provider：配对、lease、连续性与审计` | I2 | DeepSeek Flash / GLM | PR-3 exact HEAD + INV tests |
| I4 | `[P0] Auto：可信账本、恢复状态机与 A+ 模板` | I2 | Opus5 / Sol | PR-4 exact HEAD + INV tests |
| I5 | `[P0] A+ fake provider、崩溃/并发/旁路矩阵` | I2 | Sol / GLM | PR-5 exact HEAD + §10 全覆盖 |
| I6 | `[P0] 双 App 集成与 exact-build 真机验收` | I3,I4,I5 | Sol / GLM | device matrix + hashes + verdict |
| I7 | `[Product Gate] A+→B→C 触发证据与非重写演进` | EPIC | Sol 主控 | 每个里程碑记录 stay/promote/reject verdict |

Issue body 必须链接本文、列出依赖 issue、owner/reviewer、文件范围、相关 INV、验证命令与“operator only merge”。

## 17. Thread 编排

在 GitHub issue 图冻结后，从实施主 Thread 提议四个子 Thread，均使用 `state-transitions` 回报：

1. **Opus5 核心实现**：I1/I2/I4，独立 worktree。
2. **Sol 验收与检查**：I5/I6，独立 worktree；不写 Opus5 核心实现。
3. **DeepSeek Flash 千网游独立模块**：I3；只在 contract exact HEAD 冻结后开工，文件所有权不与 Opus5 重叠。
4. **GLM 独立审查/对抗测试**：先审本文与 Sol 的验收设计，后审 DeepSeek Flash/Opus5 exact HEAD；不替作者自审。

主 Thread 只接收六个状态点：文档提交、issues/任务图完成、子 Thread 建立、核心实现 ready for review、验收完成、等待 merge 决策。

## 18. 验收标准与追踪

| AC | 判据 | 主要 INV/证据 |
|---|---|---|
| AC-01 | 一键执行地址清单并按每地址可信次数推进 | INV-10,16,17；计划集成测试 |
| AC-02 | Auto 不复制/旁路千网游能力 | INV-01,20；boundary guard |
| AC-03 | 私有鉴权版本化 v1 discover/preflight/apply/observe/release 可用 | INV-02,03,04；contract tests |
| AC-04 | 只有独立验证 System Mock 进入可信配额 | INV-05,06；TrustPolicy matrix |
| AC-05 | 每个 CellRebel execution 前后 observe，连续性不成立即不计 | INV-07,08,09,25,27；continuity matrix + 多进程 bump matrix + §18.1 上限 |
| AC-06 | crash/retry 下外部执行可重跑、可信配额最多增加一次。**按 §21 DP-3 = A 验收**：每 `attemptId` 最多一次 + §8.6.5 上限 + INV-26 审计。**外加一条不可省略的验收项**：§8.6.5 的上限已进入用户可见计数语义（运行页 / 历史页 / 导出三处），只写在文档里不算通过——这是 A 与"假装 A"的分界线 | INV-10,11,12,13,15,26；crash matrix + `READY` 基线测试 + 上限呈现的 UI/导出测试 |
| AC-07 | `CellRebelCompletionEvidenceV1` 五值判定正确，旧结果/弱证据/过短 RUNNING 均不计新完成 | INV-11,12,26；completion matrix + device evidence |
| AC-08 | 配对、签名 allowlist、lease ownership 与 release fail-closed | INV-02,14,21；security/release tests |
| AC-09 | 运行现场与历史日志可追溯，秘密不落日志 | INV-18；schema/redaction tests + UI |
| AC-10 | 崩溃/并发/恢复/旁路矩阵逐项通过，且每行有 §10.1 的 evidence class 与覆盖证明 | INV-01..29；§10 report + `verify-a-plus.sh` 行 ID 覆盖检查 |
| AC-11 | 双 App 独立构建发布，version skew 明确运行或停止 | INV-03,19；CI + skew device matrix |
| AC-12 | A+/B/C 触发门有持久 issue 与里程碑 verdict，不发生重写 | I7 + milestone evidence |
| AC-13 | 可信完成必然归属于该 attempt 的目标地址；错记地址不可能发生 | INV-23；intent-binding matrix + 真机错址负例 |
| AC-14 | 已有用户数据跨版本升级零丢失，无 destructive fallback | INV-24；v4 真实 fixture migration test |

AC 编号必须**顺序排列**，便于逐号完整性核对；新增 AC 一律追加到表尾，不得插入既有编号之间。

### 18.1 AC-05 的显式不可证明上限（诚实披露）

AC-05 依赖千网游返回的 `coverage == FULL`。**Auto 能验收的只是"正确消费 FULL"，无法独立证明"qwy 报 FULL 时覆盖确实完整"**——后者取决于上游连续性实现的正确性，而架构上千网游是唯一权威（§5），Auto 没有第二个信息源可以反驳它。

这不是理论顾虑：上游 `TERRYYYC/FakeGps-test` 的 **#14、#15 至今仍是 OPEN P0**，症状分别是「System Mock 蓝点在 mock/真实位置间闪烁」与「Hook 保存后 1–2 秒回跳真实位置」。**这正是 INV-08 要 catch 的"测试窗口内的相关环境变化"**：若这类 flicker 未被 qwy 的连续性事件源完整捕获，Auto 会收到一个假 FULL，进而产生假绿的可信计数。

因此冻结：

- AC-05 的验收结论必须**逐候选构建**给出"在本设备、本 exact-build 上未观察到 #14/#15 类漏报"的独立证据；**新接口存在不能代替该证据**（§19 原则落到本条）。
- 验收报告必须显式记录该上限：本方案能证明的是"Auto 在 FULL 之外一律不计"，不能证明"FULL 永远为真"。
- 上限未消除前，AC-05 不得标记为无条件通过；应标为「conditional-pass + 逐构建证据」。
- #14/#15 关闭与否由上游判定，本 feature 不代为关闭（§2.3 非目标）。

## 19. 完成定义

A+ 不是在代码齐全时完成，而是在以下条件同时成立时达到 `ready for operator merge decision`：

- AC-01..14 都有非作者可复核证据；
- INV-01..29 全部被自动测试或明确的真机证据覆盖；
- §8.6.5（completion 跨 attempt 去重）与 §18.1（AC-05 的 FULL 依赖）两条**不可证明上限**已在验收报告中显式记录，未被写成全绿；
- 两 App exact APK SHA、源码 HEAD、签名、设备串号和恢复后状态完整记录；
- Hook 未验证结果与可信 System Mock 结果在类型、存储、UI、导出和配额上全部隔离；
- 原仓 #14/#15 相关风险被诚实披露并取得本候选构建的验收结论；
- Opus5、DeepSeek Flash、Sol 的作者改动分别有独立 reviewer；GLM 不审自己写的测试改动；
- 所有 candidate PR 均停在未 merge 状态，等待 operator 对每个 PR 决定。

## 20. 当前开放项

没有需要 operator 拍板的技术 A/B 题——技术项已给出 exact schema、digest 算法、容差与 API 24–27 语义。实现中若发现 Android 无法对某类相关变化提供完整连续性事件源，正确处置是 capability 返回 `PARTIAL/NONE` 并停止可信计数，而不是降低本文的不变量。

**三件价值取舍已由 operator 决定（§21 记录逐字原文）。下表是唯一权威，任何入口读到的答案必须与此一致：**

| DP | 主题 | 决定 | 阻塞 PR-1 identity 冻结 | 阻塞 contract v1 冻结 / #3–#6 | 阻塞真机验收（Task 9） |
|---|---|---|---|---|---|
| DP-1 | 千网游 release signer 迁移 | **B 受控迁移** | 否 | 否 | 否——但 signer cutover 本身**必须**在 DP-1 前置门（export/restore + custody + rollback）完成后才执行 |
| DP-2 | Auto 最终 `applicationId` | **B 改名 → `come.xx.fakeaauto`** | **是**——改名必须在 PR-1 完成，**不得晚于 contract 冻结**（`PairingRecord`/`ProviderPairingRecord` 主键含 applicationId） | 已解除 | 否——但 cutover 受 `INV-29` 数据连续性硬门约束 |
| DP-3 | CellRebel 可信完成的安全边界 | **A 接受 UI 证据 + 写明上限** | 否 | **已解除。#3/#4/#5/#6 恢复** | 否 |

**因此本文现在是可开工的冻结实施基线**（与顶部告示一致）。

仍然开放、但**不属于 operator 价值取舍**的技术项：无。实现中若发现 Android 无法对某类相关变化提供完整连续性事件源，正确处置仍是 capability 返回 `PARTIAL/NONE` 并停止可信计数，而不是降低本文的不变量。

## 21. operator Decision Packets

以下**三项**是价值取舍，不是技术 A/B；猫猫不自行决定，也不在 doc/代码 PR 中擅自执行。各自的阻塞范围见 §20 的表（那张表是唯一权威）。

### 21.0 operator 决定（逐字记录，唯一权威来源）

来源：主 Thread `thread_mslrf4eshkwf1nvu` 消息 `0001786310399153-001347-114fff25`，`2026-08-09T21:19:59Z`。原文逐字：

```text
我选了：B 受控迁移 release key（DP-1 · 千网游 release signer）
B 现在改名（建议）：come.xx.fakeaauto（DP-2 · Auto 最终 applicationId）
我选了：A 接受 UI 证据并写明上限（建议）（DP-3 · CellRebel 可信完成边界）
我选了：Raw-green；Opus5 串行清债（建议）（23 条 inherited lint）
我选了：现在复制 + SHA-256，原件不动（建议）（87 份单机验收工件）
我选了：先落 DP + --stage import 到 #12，再窄审/合入（PR 顺序）
```

**逐字执行规则**：`come.xx.fakeaauto` 是 operator 在自由文本框中键入的字面值。它在语法上合法（三段、均以字母开头、纯字母数字，见 §21.1），因此不构成"可复现的 Android/签名硬冲突"。实现者**不得**将其推断纠错为 `com.…` 或 `…fakeauto`。若 operator 后续更正该值，走一次显式修订，不得由实现者代为判断。

### DP-1 · 千网游 release signer 迁移策略

**背景**：`FakeGps-test@285e4ca` 的 release 复用本机 `~/.android/debug.keystore`。该 keystore 由 SDK 在本机随机生成（密钥材料非全球共享），但口令公开且不受保护，且 debug/release signer 完全相同。上游代码注释记录了这么做的真实理由：一把稳定 key 让 debug 与 release 可以 `adb install -r` 相互替换，避免 uninstall 清空 `/data/data`——**这个选择过去已经挽救过一次 operator 全部 profile 的丢失**。

| 选项 | 得到 | 付出 |
|---|---|---|
| A 保持现状 | 现有 profile 数据连续性不受影响；无迁移成本 | 无强 release identity；仅"production key 原位轮转"这一条真机场景标为 not-testable（签名拒绝/重配对语义仍可用受控测试 key 与注入 fixture 覆盖） |
| B 迁移到受控 release key | 强 release identity；signer 轮转可验收；debug/release 可区分 | 一次性 uninstall 或数据迁移；操作不当会重演 profile 丢失 |

**operator 决定：B — 受控迁移到受控 release key。**

**"受控"是硬门，不是修饰词。** 下列三项必须**全部**先于任何 signer cutover 完成，否则不得执行迁移：

| 前置门 | 内容 | 完成判据 |
|---|---|---|
| G1 · profile export/restore | 千网游 profile 的导出与回灌路径，覆盖 operator 现有全部 profile | 在一台设备上完成 export → 卸载 → 重装 → restore，逐条比对数量与内容摘要 |
| G2 · release-key custody | 新 release key 的生成、保管与访问边界 | key 与口令**只**存在于 operator 控制的密钥保管处；**不得**写入本仓库、CI secret 以外的任何位置、任何日志或任何猫的上下文 |
| G3 · rollback | 迁移失败时回到旧 signer 的可执行路径 | 旧 keystore 与旧 APK 已归档且可复原；回滚步骤经过一次演练 |

上游注释记录：现在这把稳定 key **已经挽救过一次 operator 全部 profile 的丢失**。G1 存在的唯一目的就是不让这件事以另一种形式重演。

**Task 9 影响**：迁移完成后，"production key 原位轮转"从 not-testable 转为可验收，§6.5.2 中据此标注的范围随之收窄；在迁移完成前，该标注继续有效。

### DP-2 · Auto 最终 applicationId

**背景**：Auto 当前 `applicationId = com.example.cellrebelauto`，是脚手架默认命名空间。配对记录以 `(applicationId, signerDigest)` 为主键。

**成本框定修订（本版更正上一版）**：上一版把 B 的代价写成"作废全部既有 `PairingRecord`，需重新配对"。经只读核验，该代价**当前为零**——实现树中不存在 `PairingRecord` / `ProviderPairingRecord` 的 entity 或 store，它们仍是待实现的 spec surface（#4 / #5 未开工）。把注意力放在一个当前为零的代价上，会掩盖真正非零的那个。

**真正非零的代价是既有用户可见状态被搁浅。** 论证分两层，**承重的只有第一层**：

**第一层 · 承重链（Android 基础事实，无争议）**

| 事实 | 来源 |
|---|---|
| `applicationId` 是设备上 App 的唯一身份，改 ID 即另一个 App | `developer.android.com/build/configure-app-module` |
| app-specific storage 按 App 隔离，数据落在 `/data/data/<applicationId>` | `developer.android.com/training/data-storage/app-specific` |
| Auto 现网 `AppDatabase version=4` 含 plan / task / attempt / result / session | repo exact HEAD |
| 现有 CSV 不是完整迁移通道：`AttemptCsvMapper` 只导出审计结果，`MainViewModel.importCsv` 只导入 worklist | repo exact HEAD |

改 ID → 新目录 → 新 App 全空、旧数据留在旧目录。**这一层不依赖任何关于备份行为的判断即已成立。**

**第二层 · 次级恢复路径也大概率失败（论证不依赖它）**

| 事实 | 来源 / 强度 |
|---|---|
| Auto Backup 默认包含 `getDatabasePath()` 下的数据库；D2D 迁移只在**相同 package name + signing certificate** 之间成立 | 一手：`developer.android.com/identity/data/autobackup`、AOSP CDD 9.16 |
| Auto 的 Manifest 只有 `android:allowBackup="true"`，无 `dataExtractionRules` / `fullBackupContent` | 一手：repo exact HEAD |
| 由上推断：旧 ID 的备份不能自动恢复给新 ID | **明示推断，非一手断言**——本节最弱的一环，因此**刻意不让它承重** |

把第二层单列，是因为它是这套论证里唯一的推断环节。**即使它整条被推翻，第一层仍然独立成立**，`INV-29` 的必要性不变。

准确措辞：数据**不是被删除，是被搁浅在旧 App 的 sandbox 里**。地址可由 CSV 重建；历史结果与证据链没有回灌路径。

| 选项 | 得到 | 付出 |
|---|---|---|
| A 冻结沿用 | 无 identity 断裂；现有 v4 用户状态原地存活 | 长期携带 `com.example.` 占位命名空间 |
| B 改名 | 干净的产品 identity；且现在改是最便宜的时刻（pairing 尚未落地） | 形成新 App identity 与数据边界；**既有 v4 用户状态会被留在旧 sandbox**，除非先完成迁移桥 |

**operator 决定：B — 现在改名，最终值逐字为 `come.xx.fakeaauto`。**

该决定**不撤销**上述数据风险，而是给它配一道硬门：见 `INV-29`。直接改 ID 而不迁移，等价于把 operator 的历史留在不可访问的旧 sandbox，与 §7.1 的"用户状态默认持久化"和 INV-24 的立场直接冲突，**不予放行**。

**范围边界（冻结，防止过度改名）**：本决定改的是 **`applicationId`，且仅此一项**。

| 项 | 变更 | 理由 |
|---|---|---|
| `applicationId` | `com.example.cellrebelauto` → `come.xx.fakeaauto` | operator 决定；它是设备上的 App 身份，也是配对主键的组成部分 |
| Gradle `namespace` / Kotlin 包路径 / 测试 id（`com.example.cellrebelauto.**`） | **不变** | 它们是**编译期命名空间**，不参与设备身份，也不进 `PairingRecord`。改动它们会波及全部源文件与 §10.1 manifest 里每一条 `testId`，属于与本决定无关的大范围重构 |

实现者**不得**因为"看起来该一起改"而顺手重命名 namespace 或包路径。若将来确需统一，那是一次独立的重构决定。

时间窗不变：改名必须在 PR-1 完成，不得晚于 contract 冻结（`PairingRecord` / `ProviderPairingRecord` 主键含 applicationId）。PR-1 因此产生新 HEAD，需独立复审。

#### 21.1 `come.xx.fakeaauto` 的合法性核验（为什么不触发退回通道）

该值形态非常规，因此在落盘前独立核验过，避免"看着像笔误"被当成技术理由绕过 operator 的决定：

| 检查 | 结果 |
|---|---|
| 段数 ≥ 2 | 3 段：`come` / `xx` / `fakeaauto` ✅ |
| 每段以字母开头 | ✅ |
| 仅 `[A-Za-z0-9_]` | ✅ |
| 任一段是 Java 关键字 | 否 ✅ |

结论：**语法完全合法**，不构成"可复现的 Android/签名硬冲突"，因此不满足退回主 Thread 的条件。实现者按 §21.0 逐字执行。

若 operator 本意是 `com.…` 或 `…fakeauto`，那是一次**值的更正**，须由 operator 显式提出并走一次修订；实现者不得代为判断，也不得因为"看起来更像"就改。本节存在的目的就是把这条边界写死：**形态可疑不等于技术冲突。**

#### 21.2 其余三项 operator 决定的落实口径

§21.0 第 4–6 条不是 DP，但同样是 operator 拍板，且各自改变了某个门的终态定义：

**(4) inherited lint = raw-green 终态门，Opus5 串行清债。**
`apps/qianwangyou` 在冻结基线上带 23 个 lint error（`NewApi`=9 / `MissingTranslation`=6 / `Range`=5 / `MissingPermission`=3）；两个上游仓都没有任何 CI，所以 `lintDebug` 从未被当作门跑过。终态要求是 **`lintDebug` 真正 exit 0**，不是"债务没增长"。因此：

- `scripts/check-inherited-lint-debt.sh` 的 ratchet **降级为中间证据**，不再是终态门；它继续防止债务增长，但 raw-green 达成后应随之退役。
- 清债由 Opus5 **串行**进行（不与 DeepSeek Flash 的 provider 实现并行写 `apps/qianwangyou/**`），每次授权的 delta 必须可追溯，且**不得破坏 upstream import provenance**——`check-provenance.sh` 在 `--stage import` 下会因此失败，届时按上表把 CI 那一行移到 `--stage contract`，这是合法分叉而非绕过。

**(5) 87 份单机验收工件：现在复制 + SHA-256，原件不动。**
由 Sol 的验收线执行：复制 + 逐份 SHA-256 登记，**不动原件**，本次**不公开提交可能含 UI 内容的工件**。Opus5 不触碰这批文件。

**(6) PR 顺序：先落 DP + `--stage import` 到 #12，再窄审 / 合入。**
即本次 delta。#12 是 #10 identity / contract 与后续 #11 / #4 / #5 / #6 的决策真相源，因此决策必须先在此落盘并形成新 exact HEAD；**旧 `05debb8b` 的双路 APPROVE 随 HEAD 改变自动失效**，需要重新窄审。

### DP-3 · CellRebel 可信完成的安全边界（阻塞 contract 消费方）

**背景（事实，非设计偏好）**：§8.6.1 的只读核验证明 CellRebel 不暴露任何物理执行身份。Auto 对"完成"的全部认知来自 `ScreenNode` 的五个字段（`text`/`contentDescription`/`className`/`clickable`/`enabled`）与它们的时序。因此**"同一次物理执行至多贡献一次可信配额"这半句，在当前观察面上没有任何可观察量可以证明**。

**先纠正上一版的错误**：上一版把"基线只接受 `READY`"称为结构性关闭双计路径，**这是错的**。它只排除了"基线时旧分数已显示在屏"这一条具体路径；而

```text
READY → 真实 marker → 新结果 X
READY → 持续 marker / 重渲 → 旧结果 X
```

这两条轨迹在现有观察面上**完全同形**——Auto 无法区分屏上的分数是刚算出来的还是被恢复渲染的。所以 `READY` 基线是**降低风险的 mitigation，不是兑现**。把它当作严格选项交给 operator，等于给出一个伪严格选择。

因此 `READY` 基线**同时适用于下面两个选项**，不再作为选项本身。

| | 选项 A：接受 UI 证据，写明上限 | 选项 B：UI-only 完成不进可信配额 |
|---|---|---|
| 可信配额来源 | §8.6.3 完整因果链（marker 证实 + 持续达标 + `READY` 基线 mitigation） | **要求 CellRebel UI 之外的独立完成证据**；在这样的证据源出现之前，所有 CellRebel 完成一律记为 `UNVERIFIED` |
| 字面语义 | 弱化为"每 attemptId 至多一次" + 写明跨 attempt 上限 | **完整保持**：未独立证明的完成永不计入可信 |
| 得到 | 产品可用：能跑批、能出可信计数；上限被诚实记录并由 INV-26 审计 | 可信配额的含义与其名字一致，不存在假绿 |
| 付出 | 保留一个**不可消除**的残余窗口：CellRebel 在 marker 显示期间重渲旧结果仍会被计入 | **今天不存在这样的证据源**，因此可信配额实际恒为 0；产品退化为"记录运行数 + 全部未验证"，`requiredSuccesses` 这一概念需要重新定义 |
| 对现有资产的影响 | Task 4/5/7/9 与 AC-01/04/06/07 按现状推进 | 需重新定义 A+ 的完成定义、UI 文案与导出语义；B/C 演进门也要重写 |

**operator 决定：A — 接受 UI 完成证据，并写明上限。**

**A 的兑现条件（缺一即不算落实 A）**：

1. §8.6.3 的完整因果链成立才计入可信配额；`READY` 基线作为 mitigation 保留。
2. INV-11 按"每 `attemptId` 至多一次"兑现，跨 attempt 上限**写明**并由 INV-26 审计。
3. **上限必须进入用户可见的计数语义**——运行页、历史页、导出三处都要让读到"可信次数"的人看得到它的含义边界，**不得只写在本文或 README 里**。一个带着看不见前提的数字，在被读到的地方就是在撒谎。

第 3 条是 A 与"假装 A"的分界线，因此它是 `AC-06` 的验收项，不是文案建议。

这不是技术 A/B——两条都能实现。差别是：**A 承认"可信"这个词在本产品里带一个写明的上限；B 坚持这个词的字面含义，代价是它今天拿不到。** 若选 B 又希望产品仍可用，真正的出路是引入独立完成证据源（CellRebel 侧导出、结果行标识、或网络侧独立测量），那属于新的能力需求，不在 A+ 范围内。

**处置已落定（A）**：INV-11 按 A 的兑现口径生效，§8.6.5 的上限成为**已接受并须公开呈现**的产品语义（不是被消除），`M-CO-03` 终态确定，AC-06 可按 A 验收，**contract 消费方（#3/#4/#5/#6）解除停止**。

选 A 不等于风险消失。§8.6.1 的实测（见下）表明：可信计数的归属依据是**时序因果链**，不是结果内容——因为内容不携带任何区分信息。这正是第 3 条兑现条件（上限进入用户可见语义）不可省略的原因。

**选定后需同步的锚点（穷举，缺一即视为未完成）** — 本次 delta 的落实状态：

| # | 锚点 | 状态 |
|---|---|---|
| 1 | §8.6.1 事实认定（补实测 provenance） | ✅ 本 delta |
| 2 | §8.6.3 基线与判定规则 | ✅ 已核对：五条规则在 A 下**原样成立**，本 delta 无需改动（`READY` 基线本就是两选项共用的 mitigation） |
| 3 | §8.6.5 上限措辞 | ✅ 本 delta |
| 4 | INV-11 | ✅ 本 delta |
| 5 | `M-CO-03` 终态与 `deferred:DP-3` 标注 | ✅ 本 delta |
| 6 | §10.1 manifest 中该行的 `deferred` 记录 | ✅ 本 delta |
| 7 | AC-06 | ✅ 本 delta |
| 8 | §20 阻塞范围表 | ✅ 本 delta |
| 9 | 文档顶部告示 + frontmatter `status` | ✅ 本 delta |
| 10 | §21 三份 packet 的决定记录 | ✅ 本 delta |
| 11 | GitHub #6 覆盖措辞 | ⬜ 随本 PR 的 issue 同步动作 |
| 12 | GitHub #7 durable body | ⬜ 随本 PR 的 issue 同步动作 |
| 13 | PR #12 body | ⬜ 随本 PR 推送后更新 |

第 11–13 项在仓外，落实动作与证据记录在 PR #12 的回报里；**在它们完成前，DP-3 不算全部落地**。

前两轮的漏改都出在"改了结论却没枚举引用点"，因此本清单是穷举式的：**任一条未同步，DP-3 就不算落地**。

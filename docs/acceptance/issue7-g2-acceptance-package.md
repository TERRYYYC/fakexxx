---
feature_ids: [1, 7]
topics: [acceptance, g2, device, exact-build, recovery, release-gate]
doc_kind: acceptance-package
created: 2026-08-26
status: accepted
drafted_against: 85346a3c100f35b7c6a5633b32530d814ce4e5f6
source_issue: 1
accepted_at: 2026-08-26T14:23Z
accepted_by: operator
decision: "RELEASE=DUAL; SKEW=POST_V1; PROD=G3"
---

# G2 验收包（Draft）— A+ exact-build 真机准出（pre-cutover）

> **本文件是待接受的验收包，不是 runbook、执行证据或 G2 放行。**
> 它对应 Issue #1 `CURRENT-TRUTH` 中的依赖／放行阶梯 **G2**。它与 canonical spec
> §21 的 signer-cutover G1/G2/G3（profile export/restore、release-key custody、rollback）
> 同名但无关；两套门不得互相引用、替代或推导。
>
> 起草基线是 `main@85346a3c100f35b7c6a5633b32530d814ce4e5f6`，Actions run
> `32949835733` 7/7 SUCCESS。该基线只说明本 draft 读了哪棵树；真正 G2 证据必须重新绑定
> **执行时冻结的 candidate exact HEAD 与实装 APK 字节**。

## 0. 这份包要让 operator 一句话决定什么

G1(A) 的 C5 run 2 已证明单地址 debug walking skeleton，但不推出 G2。本包保留 G1 runbook
§2 的七块边界，并提议把其中五块设为 **G2 硬准出**；另外两块由 operator 明确决定留在
G2，还是移到更有可判定性的阶段。任何移出都是**显式 disposition**，不是 PASS，也不能从
G2 证据中消失。

| 块 | 本 draft 的分档提案 | 为什么 |
|---|---|---|
| 10 地址 × 每地址指定可信次数 | **G2 硬准出** | 这是 A+ 的核心用户价值；单地址 loop 不能证明配额、推进与终末在 10 项旅程上成立 |
| 崩溃／恢复 | **G2 硬准出** | 失败后若重复计数、脏环境继续或恢复出口消失，可信结果本身不成立 |
| 撤销双侧 | **G2 硬准出** | 这是已实现的用户安全控制；任一侧撤销后静默继续都属于 fail-open |
| exact-build provenance | **G2 硬准出** | 不绑定构建与实装字节，其余真机结果无法归因到候选 |
| 既有 hook acceptance harness | **G2 硬准出** | Hook 是现有产品模式；既要证明其字段投递与恢复，也要证明它不进入 A+ 可信配额 |
| version skew | **operator 决定：G2 或 post-v1** | 当前只有 protocol v1，G1 probe 只报告 `PROTOCOL SKEW`；没有被接受的 old/new APK 对与行为 oracle 时，真机无法判兼容还是不兼容 |
| production / release 构建 | **operator 决定：G2 或随 Issue #1 阶梯 G3/cutover** | 会触及 `name.caiyao.fakegps`、release signer 与 #13/PR #14 下游；把它塞进 G2 会让 pre-cutover 门反向依赖 cutover |

### Operator 决策行

接受本包时请只回一行；本 draft **不代选**：

```text
ACCEPT G2-PACKAGE; RELEASE=OPERATOR_ONLY|DUAL; SKEW=POST_V1|IN_G2; PROD=G3|IN_G2
```

- `RELEASE=OPERATOR_ONLY`：沿用 G1(A)，证据完成后由 operator 单点放行。
- `RELEASE=DUAL`：先有一份非包作者、非执行者、非独立记录者、非相关产品／证据实现作者的
  exact-build 验收 verdict，再由 operator 放行。
- `SKEW=POST_V1`：等存在至少两个真实协议版本及冻结兼容 oracle 后再测；这项选择只表达取舍，
  **不会自动改写** canonical `M-VS-01` / Task 9。它们必须先经被接受的 canonical disposition
  明确移出本 gate，否则仍阻挡 G2 放行。
- `SKEW=IN_G2`：G2 在 old/new artifact pair 与判定 oracle 冻结前保持 blocked。
- `PROD=G3`：G2 只验 pre-cutover 隔离构建；production/release 随 Issue #1 的依赖／放行阶梯
  G3 及 #13/PR #14 前置处理。这里的 G3 **不是** canonical spec §21 的 signer-cutover G3。
- `PROD=IN_G2`：production 块在独立 signer/data/rollback 前置满足前保持 blocked；不得借本包跳过它们。

### ✅ 已接受（operator，2026-08-26T14:23Z）

```text
ACCEPT G2-PACKAGE; RELEASE=DUAL; SKEW=POST_V1; PROD=G3
```

本包状态 `DRAFT → ACCEPTED`。三项取舍的**直接后果**（登记，不是解释）：

| 取舍 | 后果 |
|---|---|
| `RELEASE=DUAL` | 证据齐后**不能由 operator 单点放行**；须先有一份非包作者、非执行者、非独立记录者、非相关产品／证据实现作者的 exact-build 验收 verdict，再由 operator 放行。 |
| `SKEW=POST_V1` | **不等于 skew 已移出 G2。** §7 合取式要求 `SKEW=POST_V1 ∧ canonical disposition 已接受`；在 `M-VS-01`／Task 9 被一份已接受的 canonical disposition 明确移出前，skew **仍阻挡 G2 放行**。此为本次选择新增的前置工作项。 |
| `PROD=G3` | G2 只验 pre-cutover 隔离构建；production/release 随 Issue #1 阶梯 G3 与 #13/PR #14 处理。此 G3 **不是** canonical spec §21 的 signer-cutover G3。 |

接受**不等于** `READY_TO_SCHEDULE`：§3 准入 predicate 仍须全部成立才可消耗 operator 设备时间。

## 1. 包状态与终态

本包只有以下状态；任何状态变化都必须回写 Issue #1 的 `CURRENT-TRUTH`，本文件本身不是 volatile
gate store：

```text
DRAFT → ACCEPTED → READY_TO_SCHEDULE → EVIDENCE_COMPLETE → RELEASED | REJECTED
```

| 状态 | 含义 |
|---|---|
| `DRAFT` | 仅提案；不得编排设备动作 |
| `ACCEPTED` | operator 已选择上面的三项；仍未获得 device lease |
| `READY_TO_SCHEDULE` | §3 全部准入 predicate 成立，才可拆执行场次 |
| `EVIDENCE_COMPLETE` | 所有硬准出块 PASS，选择留在 G2 的条件块也 PASS；证据包完整性验证通过 |
| `RELEASED` | §7 合取式成立、`DUAL` 前置 verdict 存在，且 operator 明确作出最终放行；此前不得写“G2 已过” |
| `REJECTED` | 任一硬准出块 FAIL、证据无效或安全收尾不可证；不做多数票或平均分 |

HEAD、APK 字节、signer、设备、LSPosed scope、mock-location app 或测试 fixture 任一变化，只让受影响的
证据 stale；不得把不同候选的绿项拼成一份 PASS。

## 2. 角色与责任边界

| 角色 | Draft 指向 | 责任与硬边界 |
|---|---|---|
| 包作者／draft owner | Sol（`@codex-sol`） | 起草、维护证据口径；不得自审本包、不得因自己记录证据而自行放行 |
| 阶梯编排 carrier | Opus5 调度线 `thread_msun1z1pv5krwc5g` | operator 接受后才拆场次与路由；本 draft 不生成执行球权 |
| device owner | operator | 唯一可批准 device lease、mock-location app、LSPosed scope 与任何 production-data 风险动作 |
| 测试执行者 | 接受后由调度线点名一只猫 | 只执行冻结步骤并报告原始输出；不得兼任独立记录者／验收签字人 |
| 独立记录者 | 接受后点名；优先保持与 C5 相同口径 | 收原始字节、跑 checksum、逐 predicate 记 PASS/FAIL；不得改原始证据，也不得兼任 evidence validity reviewer 或 `DUAL` exact-build verdict signer |
| evidence validity reviewer | 非包作者、非执行者、非独立记录者、非相关产品／证据实现作者 | 先判 harness／fixture／manifest 是否有效，再把真实 product red 路由给 canonical fix owner |
| `DUAL` exact-build verdict signer | 仅在 `RELEASE=DUAL` 时点名；非包作者、非执行者、非独立记录者、非相关产品／证据实现作者 | 对冻结的 exact-build evidence package 出具独立前置 verdict；无权代 operator 最终放行 |
| 最终 G2 放行人 | operator | 只有 operator 可作最终放行；`DUAL` signer 的 verdict 是前置条件，不是放行权 |

本表是接受条件，不是跨猫派活。每个实际人选必须在 `READY_TO_SCHEDULE` 前落到 durable carrier；
普通聊天里的“我来跑”不算 custody。

对同一 evidence package，上述互斥是双向约束：执行者不得兼任记录／验收签字，独立记录者不得
审自己封存的 evidence validity，也不得签 `DUAL` exact-build verdict。不得因为某一角色行没有重复
另一行的禁语，就反向解释为允许兼任。

## 3. READY_TO_SCHEDULE 准入 predicate

以下全部成立前，不得向手机写入，也不得以“先跑一块看看”消耗 operator 设备时间。

### 3.1 包与候选

- [ ] operator 已用 §0 决策行接受本包；三项无空值。
- [ ] 一只非作者已对本包 scope、证据口径、角色分离给出 verdict；包作者没有自审。
- [ ] 执行者、独立记录者、evidence validity reviewer 与 `DUAL` signer 均已落到 durable carrier，
      且满足 §2 的互斥；最终放行权仍只属于 operator。
- [ ] candidate 使用完整 40 位 Git SHA，工作树 clean；记录 tree SHA、base SHA 与对应 CI run。
- [ ] candidate current-HEAD 所需 CI 全绿；这只证明机器门，不替代真机证据。
- [ ] 两只 app 从同一 candidate 构建；若无法同 HEAD，必须各自记录完整 SHA 与为什么分叉。

### 3.2 设备与隔离

- [ ] 记录 exact serial、model、OS/API；一次场次只能有一台 `adb device`。
- [ ] operator 明确指定 mock-location app 与 LSPosed module/scope；执行者不写 LSPosed 私有数据库。
- [ ] 只用公开测试坐标与隔离 schedule/profile；禁止把本包实验指向 production 用户数据。
- [ ] 每个故障注入都有退出与设备归真步骤；无法证明 mock 清除时，本场立即 FAIL 并进入人工恢复。
- [ ] production package 若不在本次选择中，`name.caiyao.fakegps` 全程不安装、不替换、不清数据。

### 3.3 当前已知的 harness readiness 缺口

这些是起草时对 `main@85346a3` 的只读事实，不是执行 finding 的修复授权：

1. `docs/acceptance/a-plus-device-matrix.md` 目前只登记 `M-CO-06` 与 `M-VS-01`；它**没有**
   枚举 10 个地址、各自 quota 或旅程 case ID。调度前必须冻结一份 10 项 fixture（稳定 item id、
   顺序、公开测试位置、每项 `requiredSuccesses`）并记录其 digest；模板存在不能代替这一步。
   **fixture 已冻结**：`docs/acceptance/a-plus-10a-fixture.json`（digest
   `cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852`，10 项 profile-1..10，
   `totalRequiredSuccesses=17`）。**设备可达消费面已补**（harness backfill PR）：起草时
   fixture 无任何代码消费者、Auto 产品 run 从 shell 不可达（plan 仅文件选择器 `importCsv`、
   run 仅 `exported=false` 的 `AutomationService`）——这是 A/B/C 共享的缺口①。backfill 加
   debug-only seed 面（qwy `prepare_10a` 显式 id=1..10；Auto `APlusSeedActivity` `seed_plan`/
   `start_run`，坐标作 trust target），payload 即 fixture 文件本身、双侧 seeder 重算 digest 校验。
   命令面见 `p10-collector-runbook.md` §5A seed 节。**这只是 host-green 的可达性**，不表示 A
   块已在真机 PASS——真机 10 项旅程仍由 §5 block A 承重。
2. `docs/acceptance/matrix-evidence-device.json` 当前不存在。实际 device row 执行前必须按 canonical
   §10.1 schema 创建；Markdown 模板不是 executed evidence。
3. ~~`apps/qianwangyou/scripts/test-hook.sh` 当前硬编码 `PKG=name.caiyao.fakegps`，但它构建并安装的
   debug APK 具有 `applicationIdSuffix ".bench"`，acceptance Activity 也只在 debug source set。~~
   **脚本层已修**（PR #47，`6658f8e`）：runner 全部身份坐标改绑 `.bench` 实装（显式 namespace
   FQCN / bench 权限串 / provider authority），device-free selftest
   `scripts/selftest-test-hook-package-identity.sh` 红先绿后锁两条假绿线（install 身份、dumpsys
   gate），CI install-guards job 常驻。**事务外 readiness 路径已补**（PR #48，`41b2b67`）：
   `test-hook.sh --acceptance-readiness` 以只读双侧证明（无特权启动被 bench signature 权限拒
   + 无 payload root 启动命中 probe 自身 fail-fast abort 签名、负断言不进事务）在 §G 事务外
   验证 acceptance component；selftest `scripts/selftest-test-hook-acceptance-readiness.sh`
   负矩阵锁死。**证据捕获已补**（PR #49，`85875b0`，Terra validity verdict「no executed
   command lines are frozen」整类修复）：readiness 成功路径冻结实际执行的 host 命令行
   （`READINESS_CMD`，stage 2 含 `adb shell "su -c '…'"` 真实形状）+ Stage 1 拒绝原文与
   Stage 2 原始输出的有界摘录（patterns 与 grep regex 逐字一致并随 counts 行发射，可审计
   复跑；设备噪声行不进证据流）。历史证据不回填；本修复为 S2-B 全新目录全覆盖重跑的前置。
   **Hook 块仍为 `NOT_READY`**——上述均为 device-free 证明，不满足本条要求的
   「canonical runner 在真机上证明启动的正是实装 `.bench` acceptance component」；真机
   exact-build 执行（§5，S1 场次「后续门」）仍是放行前置。`snapshot_prefs` 的 `/data/misc`
   无包过滤扫描为已知残留（两包并存时 loud fail），pin 需真机路径验证。
4. 若选择 `SKEW=IN_G2`，必须先冻结两个方向各自的 old/new APK SHA、协议版本、支持集合与预期
   outcome。只有 `protocolVersion=1` 的同构双端或一行 `!! PROTOCOL SKEW` 不能证明 M-VS-01。

## 4. 统一 exact-build 与证据契约

### 4.1 构建身份

每只 APK 至少记录：

- candidate full SHA、tree SHA、build type、Gradle task 与完整 exit code；
- artifact path、SHA-256、applicationId、versionCode/versionName、signer certificate SHA-256；
- `contracts/environment-control-v1/compatibility.yaml` 的 SHA-256；
- 构建主机只记录可复现环境版本，不把主机路径或本地 secret 写入证据。

### 4.2 实装身份

只看 versionCode/versionName 不算。每次安装后必须：

1. `pm path <package>` 恰好返回一个 `base.apk`；
2. 对设备 `base.apk` 原始字节求 SHA-256；
3. 与本次构建 artifact SHA-256 逐字相等；
4. 读取当前 signer 并与记录值相等；
5. 记录实际 package/applicationId，不能从期望配置反推。

任何一项不等，本轮是 **WRONG_BUILD**，不是产品 PASS/FAIL；重装并重记，旧证据不得复用。

### 4.3 原始证据与 checksum

- 每个场次建立独立目录，保留命令、stdout/stderr、exit code、logcat、直接状态查询与截图。
- 原始文件写入后只读封存；独立记录者不得剪辑或“整理”承重字节，也不得对自己封存的证据
  出具 evidence-validity 或 `DUAL` exact-build verdict。
- 生成列出**全部**原始文件的 SHA-256 manifest，并保存一次 `shasum -c` 全量通过输出。
- 每个块各有 `reportDigest`，同时有整个包的 manifest digest；缺指向物的摘要等于没有证据。
- 承重顺序延续 C5 run 2：完整 log／直接状态读回／退出码承重，截图是辅助附件，不能单独裁决。

§10.1 machine ledger 的 `reportDigest` 使用 canonical **64 位小写 hex、无前缀**；人读报告可写
`sha256:<hex>`。两种表面格式不得直接复制混用。

### 4.4 §10.1 ledger 绑定

- `docs/acceptance/matrix-evidence-device.json` 只登记 canonical device rows：当前为 `M-CO-06` 与
  `M-VS-01`。只有先完成被接受的 canonical disposition、明确把 skew 移出本 gate，才可不在本次
  G2 ledger 登记 `M-VS-01`；单选 `SKEW=POST_V1` 不足以删行或绕过它。
- 每行必须含 `rowId / exactHead / lane=device / status / testId / reportDigest`，且 raw report 可定位、
  `testId` 与 outcome 能在该报告中找到。
- `exactHead` 必须等于本次 candidate。`skipped`、`failed` 或 `deferred` 都不满足最终 gate。
- `M-CO-06` 保持 canonical device row。若 §7 的 host-coverage disposition 分支日后被接受，
  它只使该 §7 分支可判，**不**在此 ledger 造 `passed`、`deferred` 或替代行；在 disposition
  仍为 pending 时，该分支为 false，不能绕过本行的正常 device-evidence 路径。
- 10 地址旅程、撤销、恢复与 Hook 的 device 证据不得伪造成新的 §10 row；它们按 Task 9 的
  用户旅程验收进入本 G2 evidence report。若要新增 row，须先走 canonical spec 变更，而不是在包里造号。

## 5. 七块验收 predicate

### A. 10 地址 × 可信次数（硬准出）

冻结 fixture 后逐项执行；10 项必须在同一 candidate、同一设备配置和同一 schedule generation 下完成。

- fixture 恰好 10 个稳定 `scheduleItemId`；记录 schedule id/version、顺序和每项 `requiredSuccesses`。
- 每项开始前记录 trusted ledger baseline；只计算本次新写入的 `TrustedQuotaEntry`。
- 未达到 quota 时不得 advance；达到 quota 后恰好推进一次；最后一项必须以可独立回读的
  `EXHAUSTED` 收口，不回绕。
- 每条 trusted entry 都有唯一 attempt/evidence digest，并满足 System Mock 可信谓词；Hook、
  `UNVERIFIED`、legacy counter 与截图不得进入 trusted count。
- 每次环境切换均为 release-before-advance；任一 `CLEANUP UNSAFE`、重复计数、提前推进、错项归因
  或设备状态不明，整块 FAIL。
- `M-CO-06` 由 §7 的独立合取项裁决。若没有被接受的 host-coverage disposition，必须在同场
  执行原 device procedure：running marker 完全缺失时所有 attempt 均为 `UNVERIFIED`，显式告警，
  不得回退到 disabled-Start 弱信号。被接受的 disposition 只能替代本次 G2 的该项证明路径，
  不得称为 device PASS 或伪造 ledger row；pending disposition 不满足也不移除此项。

### B. 崩溃与恢复（硬准出）

至少覆盖下列四组，且每次注入前后都直接读取 durable state：

1. **RELEASE_INCOMPLETE**：release 无法证明完成后保持阻挡新 apply，不 advance、不计新 trusted；
   重启后仍原样保留，直到 operator 人工恢复证据把它收敛为 `RELEASED`。
2. **进程重启**：`ACQUIRING/ACTIVE` 且干净性不可证 → `RELEASE_INCOMPLETE`；干净性可证但
   generation 改变 → `EXPIRED`。两者都继续阻挡新 apply，TTL/重启不能充当隐式 release。
3. **generation 断裂**：revision 必须 bump、coverage 降级，连续性不可证的 attempt 不得记 trusted；
   不允许沿用前 generation 的时间或观察窗。
4. **§8.4 state-aware 分流**：`REVOKED` 原样保留并走 provider 自清理；
   `RELEASE_INCOMPLETE` 原样保留；`RELEASING` 幂等重放；`EXPIRED` 原样保留；只有
   `ACQUIRING/ACTIVE` 进入上面的干净性/generation 分支。任何出口被改写成不可达即 FAIL。

还必须选择至少一个 Auto checkpoint 崩溃窗口证明 trusted ledger 不重计，以及 release receipt 丢失后
同键重放不产生第二个 lease/cleanup。选择的窗口、注入法与对应 canonical `M-CR-*` 必须在编排前列明。

### C. 撤销双侧（硬准出）

两侧各测“新 run 前撤销”与“run 进行中撤销”，共四个场景：

- **qwy 撤销 caller**：后续调用 typed fail；active lease 进入 `REVOKED`，由 qwy 内部自清理，
  已失权 caller 的 release 必须被拒绝。
- **Auto 撤销 provider**：新 run 本地停在 `NOT_PAIRED`；in-flight attempt 进入正常
  release/recovery，不能误走 qwy 的 revoked-caller 自清理路径。
- 两侧撤销后再次出现同一 principal 都必须重新由 operator 批准，历史记录不能自动复活。
- 撤销前已提交的可信配额不回滚，但撤销事件必须可审计；撤销后不得新增 trusted count。

承重锚为 §6.5、`M-PA-07..11` 与 `M-LS-04/08/09`；单元/blackbox 绿不替代上述真机四场景。

### D. Version skew（条件块）

仅在 `SKEW=IN_G2` 时成为硬准出：

- New Auto + old qwy、old Auto + new qwy 两个方向分别绑定两只实装 APK 的 SHA/signers。
- compatible pair 必须走完整 apply → observe → release/advance；incompatible pair 必须在 lease 前
  fail-closed 为 typed `INCOMPATIBLE_PROTOCOL`，无 partial state。
- unknown wire 的 `M-VS-02` machine evidence 必须保持绿，但它不替代两个真实 APK pair。
- G1 `HandshakeProbeActivity` 的 `!! PROTOCOL SKEW` 只可作诊断，不是本块行为 verdict。

选择 `SKEW=POST_V1` 时，本报告只记录 operator disposition 与理由，不写 PASS、不造 deferred ledger row；
且必须引用已接受的 canonical scope／ledger disposition。没有该引用，`M-VS-01` 仍是 blocking row，
本块不得借一行 operator 选择被视为满足。

### E. Exact-build provenance（硬准出）

- §4 的 candidate/artifact/installed 三层身份完整，checksum manifest 全量校验通过。
- current candidate 上 machine CI 与 `./scripts/verify-a-plus.sh --stage full` 的完整报告可定位；
  若 candidate 与 CI HEAD 不同，结果作废。
  > **登记（spec drift 修正，不静默改写）**：本行起草时写作 `--lane pr-6`，但
  > `scripts/verify-a-plus.sh` 从无 `--lane` 参数（`git log -S lane` 对该脚本全历史零命中，
  > 起草基线 `85346a3` 亦然）。它只有 `--stage import|contract|full`。`lane`/`pr-6` 实为
  > `docs/acceptance/a-plus-device-matrix.md` §10.1 evidence-ledger 的泳道语汇
  > （`check-derived-counts.sh` 的 `CELL_KEYS` 含 `pr-6`；device-matrix `"lane":"device"`），
  > 起草时被误缝合成一个 CLI 参数。可执行等价物是最严档 `--stage full`（含
  > acceptance-scenarios / matrix-coverage 等全部 gate）。此为纯文档修正，不改脚本、
  > **不影响 candidate 字节**。
- canonical device ledger 行与真实 raw report 一一绑定；没有“文档说跑过”或作者口头结论。
- evidence-validity reviewer 先核 evidence carrier，再判 product behavior；证据坏时修证据，不改产品迎合。

### F. Production / release build（条件块）

仅在 `PROD=IN_G2` 时成为硬准出：

- 明确验 `name.caiyao.fakegps` production 与 Auto release artifact，不得用 `.bench` debug probe 代替。
- debug-only Activity、signature acceptance permission、DUMP seam 与 verbose diagnostics 均不得进入 release。
- applicationId、signer custody、profile export/restore、rollback 与 #13/PR #14 的前置必须由各自真相源证明；
  本包不宣称或改写 signer-cutover G1/G2/G3。
- 任何 production profile 写入、uninstall/clear-data、signer 替换都需 operator 对该具体动作另行授权；
  没有授权只能做 release artifact/manifest 的离线检查，不能假装完成真机块。

选择 `PROD=G3` 时，G2 报告必须写明“pre-cutover/debug-isolated only”，不外推 production readiness。

### G. 既有 Hook acceptance harness（硬准出）

先关闭 §3.3 的 runner/package mismatch，再在 exact debug `.bench` build 上执行：

- installed manifest 中 `HookAcceptanceActivity` 受 `RUN_HOOK_ACCEPTANCE` signature permission 保护；
  未获签名权限的普通 caller 无法启动。
- `test-hook.sh --cellular-matrix` 的 exact / fluctuation / unavailable scenarios 全部通过；
  durable SIGKILL recovery 恢复 pre-test payload。
- profile database 原始快照不变，transport 恢复到 pre-test fingerprint；任何 restore failure 整块 FAIL。
- 报告绑定 session id、result file 与实装 APK SHA，最终出现唯一 `ACCEPTANCE_PASS`，且中间无
  `HARNESS_ERROR`。
- 另做 A+ 侧直接断言：Hook result 只进入 Hook/unverified 载体，trusted quota 在前后不增加。
- release artifact 继续证明上述 debug Activity、recovery classes 与 permission 不进入 release；
  这条隔离检查不等于执行 production 真机块。

## 6. 失败、重跑与设备时间预算

- 七块按独立场次编排；先跑低成本 preflight/checksum，再拿设备做行为，避免错包消耗手机时间。
- 建议顺序：exact-build preflight → Hook runner readiness → 撤销 → 崩溃恢复 → 10 地址长跑 →
  operator 选择留在 G2 的 skew/production。
- 任一 safety FAIL 先归真设备再停；不在同一脏环境上继续下一块。
- FAIL 后只重跑受 finding 影响的块，但 candidate/artifact/device identity 必须重新取证；不能只复用旧表头。
- 不给墙钟工期承诺。每块在编排时必须写 device lease 上限与中止点，operator 可在场次间拔线。

## 7. Evidence verdict 与放行

独立验收 verdict 必须绑定：candidate full SHA、两只实装 APK SHA、device serial 前四位、证据 manifest
digest、七块逐项状态，以及 operator 对 skew/production 的 disposition。

**PASS 的合取式：**

```text
hard(A 的 10-address predicate, B,C,E,G) 全 PASS
∧ (MCO06_DEVICE_PASS ∨ MCO06_ACCEPTED_HOST_COVERAGE_DISPOSITION)
∧ ((SKEW=POST_V1 ∧ canonical disposition 已接受) ∨ D PASS)
∧ (PROD=G3 ∨ F PASS)
∧ raw evidence checksum 100% PASS
∧ device 已归真
∧ 无 unresolved safety finding
```

### §7 `SKEW=POST_V1` canonical disposition binding

The `canonical disposition 已接受` term in the preceding conjunct is bound to
[`issue7-m-vs-01-post-v1-disposition.md`](issue7-m-vs-01-post-v1-disposition.md).
Its operator acceptance is message `0001787953746336-000156-46dbd2e1` at
`2026-08-28 21:49 UTC`:

```text
ACCEPT G2-SKEW-DISPOSITION; DOCUMENT=docs/acceptance/issue7-m-vs-01-post-v1-disposition.md; SCOPE=POST_V1; V2_GATE=REQUIRED
```

The operator used `G2-SKEW-DISPOSITION`; the document retains the canonical
identifier `G2-SKEW-POST-V1-DISPOSITION` and records the shorter operator
identifier rather than silently rewriting either value. This accepted binding
removes only `M-VS-01` / Task 9 from the **current G2** skew branch. It does
not alter the device matrix or evidence ledger, and `V2_GATE=REQUIRED` keeps
the document's two-direction old/new artifact proof as a non-bypass gate
before any protocol-v2 release candidate or release.

### §7 `M-CO-06` host-coverage disposition binding

The `MCO06_ACCEPTED_HOST_COVERAGE_DISPOSITION` term is bound to
[`issue7-m-co-06-host-coverage-disposition.md`](issue7-m-co-06-host-coverage-disposition.md).
`MCO06_DEVICE_PASS` means the unchanged matrix procedure produced a genuine
current-candidate `passed` device result with the §4.4 raw-evidence/ledger binding.

The linked document is currently **proposed** with `acceptance: pending`; its
host-coverage term is therefore false. It does not make this equation true,
does not claim M-CO-06 device PASS, and does not permit a ledger row. Only a
later exact operator acceptance that names this `DOCUMENT=` path and retains
`V2_GATE=NOT_APPLICABLE` plus `MARKERLESS_SDK_DEVICE_GATE=REQUIRED` can make
the alternative branch true for the current G2 scope. That branch preserves a
non-bypass real-device gate when a controllable marker-less/marker-altered SDK
fixture becomes available.

- `RELEASE=OPERATOR_ONLY`：上式成立后，只有 operator 可把 Issue #1 的 G2 行改为放行。
- `RELEASE=DUAL`：上式成立 + 非包作者/非执行者/非独立记录者/非相关产品或证据实现作者的
  exact-build verdict 后，仍只由 operator 作最终放行。
- `DUAL` signer 只提供前置 verdict，不拥有最终放行权；evidence validity reviewer 与 signer
  均不得由本 evidence package 的独立记录者兼任。
- 包作者、执行者、记录者、CI 或 runbook 均无权单独放行。
- G2 放行不 merge/undraft/close #13/#14，不授权 production deployment，也不表示 signer-cutover 三门完成。

## 8. Canonical anchors

- Issue #1 `CURRENT-TRUTH`：唯一 volatile gate carrier。
- `docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md` §2、§10–§12：G1/G2 边界与 C5 证据角色。
- `docs/acceptance/g1-smoke-2026-08-25-ZY22-run2.md`：C5 checksummed raw-evidence 口径。
- `docs/acceptance/a-plus-device-matrix.md`：现有 `M-CO-06` / `M-VS-01` device registration template。
- canonical spec §6.5、§6.8、§8.4、§10/§10.1、Task 9：撤销、skew、恢复、ledger 与真机旅程。
- canonical spec §21 signer-cutover G1/G2/G3：仅作命名消歧与 production 前置，不能当本 G2 状态。
- `apps/qianwangyou/scripts/test-hook.sh`、debug manifest 与 `HookAcceptanceActivity`：Hook harness 当前执行面。

在 operator 接受本包前，下一动作只能是**内容验收或修正 draft**；不得开始 G2 编排。

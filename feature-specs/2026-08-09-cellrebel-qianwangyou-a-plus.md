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
>   实现者**不得**推断纠错为 `com...`。**该值已冻结可直接用于 contract**；但
>   **改名动作本身**受 `INV-29` deferred gate 阻断，载体见 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)。
> - **DP-3 = A**：接受 UI 完成证据并写明上限。上限必须进入**用户可见的计数语义**
>   （运行页 / 历史页 / 导出），不得只留在本文里。
>
> 因此 `INV-11` 按 A 的兑现口径生效，`M-CO-03` 终态确定，`AC-06` 可按 A 验收，
> **contract v1 可以冻结。** 但 `#3` 的解停条件是 **`#2` 与 `#12` 均已合入**，不是「#12 merge 即全部解停」——
> 且 DP-3 的 durable closure（§21 清单 11–13：GitHub #6 / #7 / PR body）**必须与本文原子同步后**才算落地。
> 在那之前，本文是**已拍板的实施基线**，但 `#3–#6` 的解停以上述条件为准。
>
> 仍然成立的**不可证明上限**，验收时不得呈现为全绿：§8.6.5（跨 attempt 完成去重）、
> §18.1（AC-05 依赖 qwy 的 `FULL` 声明）、§6.5.3（signer 显式批准仍是一次 TOFU，
> **不证明** publisher identity）。DP-3 选 A 意味着这些上限被**接受并记录**，
> 不意味着它们消失。
>
> **这三条不是已知边界的全集。** 连同 `INV-29`（`deferred`）、`KB-5`（契约留白）、
> `KB-6`（provider 侧 advance 的覆盖缺口）、`KB-7`（契约的 `android.*` 引用无门禁）
> 与 `KB-8`（坐标所有权未冻结）的完整**八条**见 **§20.1**——那里是"还有哪些没被
> 证明／没被冻结"的唯一入口。
> 本告示只做提醒，**不做全集**：此前它与 §19 各列两条、§20 写作"无"，三个入口
> 给出三个不同的子集，读者无从分辨"我没读到"与"它不存在"。

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
| **v1.15** | PR-0.2 第十一轮 | 为 `M-AC-01..05` 冻结**实施归属与时序**（Task 1 前置门 / Task 9 回滚旅程）、统一 device anchor、澄清计数单位，见 §0.1.15 |
| **v1.16** | PR-0.2 第十二轮 | 修复 v1.15 引入的三个语义缺陷：provenance stage 自相矛盾、`M-AC-03` 依赖环、跨 applicationId carrier 物理不可行，见 §0.1.16 |
| **v1.17** | PR-0.2 第十三轮 | **scope split**：cutover 实施设计（flavor / SAF / bundle / variant CI / `M-AC-01..05`）拆出到 Issue #13；`INV-29` 在本文降为 deferred gate，见 §0.1.17 |
| **v1.18** | PR-0.2 第十四轮 | Sol 语义窄审 SR-1..6：契约层/设备层拆分 · stage 绑定改「第一个改 app 树的 PR」· PR-5 26 行 / PR-6 90 行聚合分工 · lint 清债 lane（PR-3.5）· DP-3 解停条件收敛 · Task 7 计数；owner → Fable5，见 §0.1.18 |
| **v1.19** | PR-0.2 第十五轮 | Sol 增量语义审 6 P1 + 1 P2：#13 release edge 与 I3.5 进 DAG · 2v2 owner/reviewer 全链传播 · Task 2 自搬 workflow 并反向自验 · verifier `--lane` 子集契约 · 条件式解停，见 §0.1.19 |
| **v1.20** | PR-0.2 第十六轮 | 兑现 Phase O 裁定的 `sol-blackbox` 处置项时自查发现：v1.19 只改了责任层（owner/reviewer 列），**授权层未改**——`acceptance/**` 仍禁 Fable5 写入。本轮把 2v2 传播到 owner matrix / 目录树 / class 表 / Task 7·8 / §17 / §19 / §21，冻结 legacy label→执行者映射，并冻结五投影一致性检查器的判据，见 §0.1.20 |
| **v1.21** | PR-0.2 第十七轮 | Sol exact-HEAD 语义审 7 P1：**owner 列拆 `evidenceOwner` / `fixOwner` 并冻结失败路由表**（2v2 后 acceptance 行红了无法路由）· DP-2 载体拆四段（PR-1 是 import-only，产不出 contract/pairing）· `I6` 与 `#13` 改 Epic-close 的 sibling 输入 · lane selector 冻成 `(class, owner, 路径前缀)` 三元组、PR-5 只验 harness、26 行转 PR-6 · Task 3.5 建成真实节点并消 PR-3↔PR-3.5 环 · workflow 迁移加机器断言 · durable closure 与 review provenance 收口，见 §0.1.21 |
| **v1.22** | PR-0.2 第十八轮 | GLM 独立复核证伪 **A-6 的理由**：机械门 ①②③ 不校验「断言↔预期终态」，因此不能兜底那 33 行自审——独立性实际来自 PR-3 的跨个体 code review。另修 §8.4 冻结 `state` 的作者归因、历史区两处待执行 owner 残留、§16「Sol 主控」列义。判据补**第六投影（承重论证散文）**与**历史区不整片豁免**，见 §0.1.22 |
| **v1.23** | PR-0.2 第十九轮 | Sol 对 `5cef50b7`/`aa6a5787` 两轮窄审：**两张 pairing 表写反**（`PairingRecord`=千网游 caller allowlist / `ProviderPairingRecord`=Auto provider allowlist）· 失败路由表补**第 0 条证据自身故障**并取消「无法定位⇒自动改 contract」· PR-5 补 Gradle/wrapper/fixture/self-test 等**可执行载体**与 exact 测试入口 · Task 3.5 的 guard 真正实现声明集合（prefix + `integration/**` 排除 + baseline 成员）· 另修 RED 三处拆分、版本表顺序、§21.2 线性误导、Task 8 audit 复核义务的虚假引用，见 §0.1.23 |
| **v1.24** | PR-0.2 第二十轮 | Sol 复核 `59db6201` 余 3 P1：**历史区 B-1 仍留相反的活路由**（违反本文自己冻的「历史区待执行指令须内联标记」）· PR-5 的 `./gradlew test` 无法同时满足 0 矩阵行与三类 self-test，改为 **`selfTest` / `matrixTest` 双 source set + task 图**，台账 22 行入口同步 · **Task 3.5 guard 绑回指向物**（原始 lint 报告 digest + 条数 + 整行相等 + fail-closed），并撤回上一版「(a)(b) 封住扩权」的假断言，见 §0.1.24 |
| **v1.25** | PR-0.2 第二十一轮 | Sol 判 P1-2/P1-3 **CLOSED**，余 P1-4。三条子发现全部成立：**23 是 Error instance 数、唯一文件只有 5 个**——旧判据可被 warning-only 文件重复填满而扩权；XML 带绝对路径致 digest 不可重放，改为**规范化 repo-relative + 绑生成 commit**；`set -euo pipefail` 原在六道前置门之后，**失败会被后续成功掩绿**，已提到第一条。授权集合改为**从报告派生、与 baseline 声明逐元素相等**，见 §0.1.25 |
| **v1.26** | PR-0.2 第二十二轮 | Sol 判前两条 CLOSED，余 1 P1 + 1 P2。**P1：我引用的 `qianwangyou-upstream-sha:` 字段根本不存在**，且比较是**空值自洽**的（缺失/空声明→绿，合法值→红）；改绑 `check-provenance.sh` 的 `IMPORTS` 表（PR #10 已校验的真实 machine carrier，#10 无需改动）+ `git-subtree-split` trailer 交叉校验，并对所有比较型断言先证**非空/单值/格式合法**。**P2**：`sort -u` 先于比较导致「5 条正确 + 1 条重复」放行，改为先查 raw count 与重复。另修两处 23-path 活投影，见 §0.1.26 |
| **v1.27** | PR-0.2 第二十三轮 | Sol 判 P1 = **合法状态必红**：`git log --all \| grep -qxF` 在 `set -euo pipefail` 下，命中即关管道→`git log` 收 SIGPIPE→`PIPESTATUS=(141 0)`，**trailer 真实存在时整条反而非零**。同修两条 P2：`--all` 未绑当前历史、未要求 split SHA 与 `git-subtree-dir` 成对；`awk` 扫整份脚本而非 `IMPORTS` **赋值块**，块外一条同形 decoy 即可让本检查与 `check-provenance.sh` 循环读到不同 SHA。见 §0.1.27 |
| **v1.28** | PR-0.2 第二十四轮 | Sol 判 2 P1 + 4 P2。**P1-1** PR body 仍绑旧 HEAD 与错 stats（§21 #13 每 HEAD exact equality 失配）。**P1-2** trailer 是 DAG 证据，operator 若 squash 合 #10 会合法丢掉它 → 未来 gate 必红；承重记录改为 `qwy-subtree-tree`（tree 跨 squash 存活），DAG 降为可达时才断言。**P2**：`--grep` 命中的是正文子串不是真 trailer（散文即可假绿），改 `interpret-trailers --parse`；range awk 会拼接多个 `IMPORTS` 块而 Bash 只用最后一次赋值，加**恰好一次赋值**断言；修订正文顺序回归；补齐 8 条可重放正负例，见 §0.1.28 |
| **v1.29** | PR-0.2 第二十五轮 | Sol 判 3 P1 + 2 P2，**三条 P1 是同一个设计错误的三个面：用历史形状承载内容命题**。**P1-1** Task 3.5 先跑的 `check-provenance.sh` 仍硬要求 import commit 存在，我上一版的 tree/trailer 逻辑在那道 gate 之外——**改 PR #10 的 checker 本身**，DAG 证据降为可达时才断言（fresh squash clone 实测 rc=1 → rc=0）。**P1-2** `qwy-subtree-tree` 取错时间截面（baseline 先于源码修改提交，最终态必不相等）→ 删除该字段，不变量改为**相对 upstream 树的分叉受限**，pristine 与终态都成立。**P1-3** selftest 无合法执行边且归属写成 Fable5 独占的 `acceptance/**` → 移入 Opus5 独占的 root `scripts/`，guard 与 selftest 共用同一实现并由 Verify 真正调用。**P2-1** 文本 parser 仍可与 runtime 分叉（`printf -v` / `export`）→ PR #10 新增 `--print-import` 机器查询。**P2-2** body 三处短 SHA 标题自称当前 + 历史清单不穷举 → 改用 git 派生的 21 个 HEAD 全血统，见 §0.1.29 |
| **v1.30** | PR-0.2 第二十六轮 | Sol 判 3 P1 + 2 P2（耦合 PR #10）。**P1-1** provenance 锚点是两节互相指望——DAG 一丢，history-lost + 任意篡改仍 `PASS (all checks)`（已复现）；锚点改为 **fetched upstream root tree**（内容承载，跨任何合入方式存活），import commit 降为可达时才断言。**P1-2** 分叉检查拿 upstream 与最终 HEAD 全量比较 → 把 Task 2/3 的合法改动判越界；且在 `$TMP/derived` 生成前就用它（grep rc=2）。按三载体拆分：provenance 归 #10、前序分叉不归本 gate、本 task 只看自己 merge-base 之后的 delta。**P1-3** Verify 调用 sibling 才交付的 `acceptance/**` 脚本 = 永远执行不到的指令，已移除。**P2-1** `--print-import` 与生产循环共享变量名而非代码路径，改 `readonly` + 单一 `each_import`，并加冻结记录集自检与末尾复检。**P2-2** PR body lineage/provenance 表回填。见 §0.1.30 |
| **v1.31** | PR-0.2 第二十七轮 | Sol 判 P1-2/P1-3 CLOSED，余 2 P1 + 2 P2（P2-1 升级 P1）。**同一个 commit 里我把 substring 当成绑定用了两次**：root-tree 锚点是全局 `grep`（互换两行仍双绿）、0a 成员检查是整段 substring（prefix 藏进 branch 字段即假绿）。改为**逐行逐字段绑定**与**第一字段集合相等 + 字段数校验**；`readonly -f each_import` 冻结函数本体；**history-lost + 分叉改为硬失败**（pristine 仍绿，不误伤 squash 合入）。负例矩阵落地为 #10 CI 里的 `selftest-provenance.sh`（3 正 + 8 负），并补齐 Task 3.5 的调用契约，见 §0.1.31 |
| **v1.32** | PR-0.2 第二十八轮 | Sol generation-8 判 2 P1 + 2 P2。**P1-1 我第三次造出永远红**：squash 合入 + 真实 Task-2 contract delta 是合法路径（base commit 仍持 pristine tree，ancestry 客观存在），却因原 import commit 不可达被判红；判据改为**任一可达 ancestor 的 prefix tree == fetched upstream tree**。**P1-2 判据 11 只用在了一格**：row selector 仍是 `index()` 子串、URL/branch/SHA 仍全文件 grep——改名 `apps/qianwangyou-shadow`、只互换两行 SHA 单元格都能假绿；改为**锚定 imports 表的严格单行解析器**（恰好一行 / 恰好六格 / 同行绑定全部字段）。**P2-1** selftest 自称 3 正 8 负，实际只有 6 条行为负例（N-7a 是正例、N-7b 只 grep 源码）——重写为 **4 正 + 9 负全部执行生产 checker**、计数由 harness 派生。**P2-2** #10 body 的 `21 PASS` 已 stale。见 §0.1.32 |
| **v1.33** | PR-0.2 第二十九轮 | Sol gen-10 判 1 P1 + 2 P2。**P1**：strict parser 既不 strict 也不完整——`gsub` 删的是**单元格内部**空白（SHA 中间插空格会被规范化成正确值并认证）；第 6 格 import commit 根本没走 parser，写 `0000…0000` 会被读成合法 squash 而放行。改为**只 trim 外围** + 把 canonical import commit 纳入冻结记录并与第 6 格 exact 比对；表格单元格改为裸值。**P2-1**：selftest 会在夹具没构造成功时自报全绿（P-4 丢弃 setup 退出码，实际重测了 P-3）；N-5 把 fork 与独立 tamper 混在一起。改为 setup fail-hard + 形状断言 + fork 隔离，并**把变异自检放进 harness**。**P2-2** #10 body/workflow 数字回填。见 §0.1.33 |
| **v1.34** | PR-0.2 第三十轮 | Sol gen-11 初判 **0 P1 + 2 P2**〔**当轮记录，随后被 Sol 补充 verdict 更正为 1 P1 + 2 P2**：新 P1 = 第六格 TAB 传输截尾，见 v1.35，勿照本行读作「P1 已闭合」〕。**P2-1**：N-5 在它负责保护的 `readonly -f each_import` 被删除后仍全绿——它不断言退出码、只搜一个 entry-file 段无条件输出的字符串，注入记录仍是旧 4 字段（构成独立失败源），更根本的是「丢掉 qwy」的 fork 本就被 0a 集合检查拦下、**从未触达 `readonly -f`**。改注入**合法 5 格但 SHA 伪造**的 fork，断言 rc 与只来自 doc-binding 循环的证据，新增 **M-4**（删 `readonly -f` 必使 N-5 失败）；并把「逐条变异」收窄为**实际的 4 条具名 mutation**。**P2-2** #10 body 两句 stale current 声明与 4→5 字段文案已收口。见 §0.1.34 |
| **v1.35** | PR-0.2 第三十轮·补充 | Sol 补充 verdict 把 gen-11 由 `0 P1 + 2 P2` 更正为 **1 P1 + 2 P2**。**新 P1：校验发生在值上，损坏发生在传输上。** `doc_row()` 把六格用 TAB 串行化、消费方一律 `cut -f1..f6`；第六格内部写 `<canonical sha><TAB>JUNK` 会生成**第七个传输字段**，`cut -f6` 静默截尾，于是文档指着一个带垃圾尾巴的值，checker 照样打印 `records the canonical import commit 5687e319f` 并 rc=0——**full-DAG `--stage import` 与 depth-1 history-lost `--stage contract` 两条路径均已复现**。v1.33「只 trim 外围」是对的，但只管到了值：`[[:space:]]` 覆盖 TAB，而内部 TAB 既不在首也不在尾。改为**拒绝任意单元格的内部空白** + **独立断言拼装后恰好六个传输字段**（两层）；补 `N-12`（full-DAG import）/ `N-13`（history-lost contract）两条负例与 `M-5`（同时退两层）。实测修前两条均 rc=0、修后均 rc=1。**判据补第十四条**（拼装再解析时，分隔符必须被证明不可能出现在字段内部）。见 §0.1.35 |
| **v1.36** | PR-0.2 第三十一轮 | Sol gen-13 判 **0 P1 + 2 P2**，代码侧 blocker 清零。**归因实验**：上一轮我把 `setup()` 诊断与 `mk_fulldag` 两处候选修复放进同一 commit，CI 转绿却说不出是谁修好的；改用一次性 ref + `workflow_dispatch` 只退回硬化（probe `8cde419b` / run `31376782849`，**PR 分支零改动**），CI 精确报 **`Author identity unknown`**——夹具真因确认为 `git clone` 不继承 committer 身份（macOS 从 user@host 推导、runner 拒绝，而 `mk_squashed` 一直持久化身份）。同一日志的 **`INCONCLUSIVE: N-12 was not green before the mutation`** 则给 pre-green guard 自身的承重证据。**P2-1**：两份 PR body 仍把当前 HEAD 投影成我自己撤回的 known-red `40029ae8`——v1.34 的「唯一当前真相块」解决了「有几份」，没解决「是否新鲜」。**P2-2**：`§0.1.35` 声称「塞进每一个字段」而只测第六格，已**六格逐格参数化**（cell 2..6 由内部空白规则判红、cell 1 由 row selector 判红）。**判据补第十五条**（不知根因时只交付诊断，不在同一提交里附带候选修复）。**同轮另闭 Sol preflight 两条 truth-binding**：`§0.1.34` 正文原样呈现被上修的初版 verdict（读起来像 terminal closure），已加 initial/superseded 限定并保留原文可追溯，不改写历史；`mk_fulldag` 源码注释把**已被证伪**的 detached/empty-tree 机制写作既证事实（与同一 commit body 自相矛盾），已改为只陈述"去除 ambient/symbolic 依赖"、不归因，归因证据留在本节。见 §0.1.36 |
| **v1.37** | PR-2 第一轮·operator 架构更正 | **顺序的所有权错了，而且错成了两个所有者。** §5 上一版把「计划顺序」判给 Auto，同表下一行又把 profile/schedule 判给千网游为唯一权威——同一个排序有两个 owner。后果不是措辞不一致：Auto 可以按经纬度排一套顺序，千网游按「环境」（经纬度 **+** 蜂窝/网络/Wi-Fi Hook 字段是一个整体）排另一套，位置于是与网络 Hook 状态漂移。按 operator 裁定更正为：**顺序/优先级归千网游，配额与完成判定归 Auto，推进是二者之间一次幂等握手**。§5 边界表重写；新增 **§6.7 调度身份与「达标→完成→推进」**：`scheduleId`/`scheduleItemId`（稳定、非位置）/`scheduleVersion`（顺序或成员变化必自增）/`currentItemId`；`CompletionProofV1` 由 Auto 出、千网游只记不重算；`CompleteAndAdvanceRequestV1`/`AdvanceReceiptV1` 走 §6.3.4 同一套 digest，**compare-and-advance**：`expectedCurrentItemId` 与 `expectedScheduleVersion` 双前置，新增 wire **14 `SCHEDULE_ITEM_MISMATCH`**（挡错项/重复推进）、**15 `SCHEDULE_VERSION_STALE`**（判定期间计划被改）、**16 `SCHEDULE_EXHAUSTED`**（终态非失败〔**本括号是 v1.37 当轮的旧文，已被 v1.39 明确作废**：完成末项是**成功**，走 receipt `outcomeWire = EXHAUSTED` + null target；wire 16 只用于**已耗尽之后再请求**，是真正的调用方错误。此处保留原文不改写以存审计链，但必须带标记——本行是 v1.37 的日志，而 v1.37 正是废止该读法的那一轮，不标记就等于日志误报了它自己记录的修订。见 §6.3.3 / §6.7.4〕）；推进与指针同事务、receipt 可按幂等键重取、**Auto 必须 observe 独立验证新生效环境**（receipt 是自述不是生效证据）；千网游隐式首行/遗留行序迁移到显式 `currentItemId`。原 §6.7 兼容矩阵顺延为 §6.8，5 处旧引用同步。见 §5 / §6.7 |
| **v1.38** | PR-2 第二轮 | **digest 与 recovery 一起收口。** ①每个 digest 加 **domain tag** 作首个 framed 字段（`:intent`/`:advance-request`/`:advance-receipt`）——长度前缀只让**一个**序列单射，不阻止「为用途 A 编码的序列」等于「为用途 B 的」，不加 domain 就是把正确性押在「不会有构造输入跨用途碰撞」上，而这正是 §6.3.1 拒绝分隔符时不肯押的巧合；framing 收敛为**一个共享 helper**（第二份手写副本是第二个漂移点，且漂移的 framing 不会响亮失败，只会算出不同 digest）。②**冻结 `receiptDigest`**（此前完全无算法）：绑 `requestDigest` + `idempotencyKey` + outcome，`verify()` 是信任前必跑的一步——**重算不上的 receipt 不是弱证据，它不是 receipt**；null target 用哨兵编码，与空串不碰撞。③`AdvanceOutcomeV1` 由两个 `const` 改为**枚举**，从而自动继承 yaml↔Kotlin 绑定与 derived probe 覆盖（const 不被任何 carrier 看见，改一侧全绿），并补 `advancedOrFailClosed` 未知即非推进。④**冻结 release↔advance 顺序**：先 release 再 advance；lease **不跨项**；`CompleteAndAdvanceRequestV1.leaseId` 是配额挣得处的**历史引用**而非活动持有——不写死会被合理读成两种互斥实现（见 §6.7.4a）。⑤§8.1 增 advance 状态边，§10 增 **M-AD-01..11**（missing proof / same-key replay / new-key double / wrong item / stale version / lost receipt / digest 不符 / 推进后 observe 不符 / 耗尽终态 / 耗尽后再请求），§10.1 台账同步 11 行，矩阵↔台账集合相等 101==101。见 §6.3.1 / §6.7.3 / §6.7.4a / §10 |
| **v1.39** | PR-2 第三轮 | **禁令有了规则，但没有判据。** ①§6.7.4a 冻结「推进时不得存在活动 lease」，却没冻结违反时返回哪个 typed code——`LEASE_CONFLICT` / `REQUEST_INVALID` / 新码三种读法都自洽，红测只能接受"任意 `ContractException`"，**因而对错误的 wire 也会全绿**。冻结为 **`LEASE_CONFLICT`(7)，不新增 wire**，并逐条排除：不是 `REQUEST_INVALID`(**13**)（请求字节合法，release 后重发即成功，而 13 的语义是重试无用）〔**当轮首版把该码误写为 4**——4 是 `CAPABILITY_UNAVAILABLE`，整段排除论证因此指向了一个无关的错误码；同轮 `IDEMPOTENCY_CONFLICT` 亦误写为 13（真值 **12**）。由非作者（Sol）在 `590ab58` 上发现，已于下一 HEAD 全部更正，并新增 §5b 内联 wire 引用门禁使该类错误不能再静默通过〕、不是 `STALE_LEASE`（`leaseId` 按 §6.7.4a 本就是已 RELEASED 的历史引用，"过期"是正常形态不是错误）、不是 9（9 是事后观测到漂移，本条是**事前**阻止漂移发生的门）。②**§6.3.3 对 7 的定义必须同步加宽**：原文只写"与**另一** caller 或**另一** intent 的 lease 冲突"，按字面 caller **自己**持有的活动 lease 不在其中——只在 §6.7.4a 写"返回 7"而不动 §6.3.3，两节即互相矛盾，实现者按 §6.3.3 读会认为本场景不该返回 7。同段 `IDEMPOTENCY_CONFLICT` 对照文字一并加宽。③新增 **§6.7.4b 判定次序（冻结）**：`proof → idempotency → schedule(14/15/16) → lease(7) → mutation`。多条前置可同时不满足，不冻结次序则两个实现对同一请求返回不同 code 而都"符合"前置表，调用方恢复策略不可移植。两处次序各有理由而非任选：**幂等重放必须先于一切前置**（成功推进后 `currentItemId` 已前移，重放携带的 `expectedCurrentItemId` 必然过期，先跑 schedule 门会让 **M-AD-02 与 M-AD-04 塌成同一可观察结果**，防双推进的最后一道变成对合法重放的误杀）；**schedule 门先于 lease 门**（已耗尽时没有可完成的当前项，lease 是否活动无意义，先返回 7 会让 Auto 白跑一轮 release-retry 后仍撞终态 16，反向则无对称浪费）。④§6.7.4 前置由三类增至**四类**。⑤§10 新增 **`M-AD-12`**（lease 门，断言须钉 **exact wire 7**，"任意 typed failure"不构成证据）与 **`M-AD-13`**（同时违反多条时按冻结次序返回首条；次序被实现成任选则本行必红）。两行按 operator option B 归 **provider 侧 Fable5**、锚在 qwy lane——这是 provider-owned advance 覆盖的第一批，用以补上「`M-AD-01..11` 全部落在 Auto lane、真实 provider 的 compare-and-advance 无任何稳定 ledger row」的缺口。矩阵↔台账集合相等由 101==101 同步为 **103==103**。见 §6.3.3 / §6.7.4 / §6.7.4b / §6.7.4a / §10 |
| **v1.40** | PR-2 第四轮 | **reviewer 拓扑按 durable control event 重投影。** 上一轮我拒绝按聊天 claim 改 reviewer map——理由是 canonical 是可执行边界，两小时内对同一问题给出两个互斥答案而第二个无真相源，比落后于事实更糟。现权威锚点已补齐：[Epic #1 control event 5258691694](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5258691694)（operator，2026-08-11T20:49:23Z），**只 supersede 旧 event 5258089891 的 reviewer-role 条款**，option-B 开发所有权不变。生效映射：#3/#13 作者 Opus5 → **Sol** 线级审（本 thread）；#4/#6/#7 作者 Fable5 → **DeepSeek Flash**；#5 作者 GLM → **Gemini 3.6** 线级对抗 + **Sol** formal backstop（trial reviewer 不得单独承担安全攸关门）〔**当轮记录，已被 v1.41 取代**：operator 于 21:53Z 把 Gemini 的 #5 review 责任改派给 Sol，现行为 **#5 → Sol 独立线级审**，勿照本行执行〕；**GLM 作 #5 作者期间无任何 review 席位**，含不审 #4/#6/#7；主线 Sol 只做进展 / merge review / Epic / 派工，**不做 line code review**。据此同步的 live 投影：§12.1 Sol 与 GLM 两行、§10.1 失败路由第 5 条 triage 席位（GLM 全部出席位）、§13 Task 8（由「GLM 独立审查」改为**按 lane 的线级 reviewer**，owner 不再是单一个体）、Task 7/PR-5 审查方、§16 Epic 表 I2/I3/I3.5/I5/I6/#13 六行 reviewer 列按 owner 逐行重投影、§17 线程编排新增线级 reviewer 条、§19 完成定义。**不改写历史区**：§0.1.x 中记录 GLM 曾证伪某条的条目是既成事实，保留。见 §12.1 / §10.1 / §16 / §17 / §19 |
| **v1.41** | PR-2 第五轮 | **#5 reviewer 再改派，v1.40 的投影当轮即被超替。** operator T0 `0001786484933244-003483-c30e1507` 把 Gemini 的 #5 review 责任改派给 Sol；durable 锚点 [Epic #1 control event 5259280778](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5259280778)（operator，2026-08-11T21:53:10Z，经核为 Epic #1 **最新**一条）明写 supersede 先前的 Gemini 3.6 + Sol-backstop 投影。现行：**#5 作者 GLM / reviewer Sol**（在 #5 开发线内），`#3`/`#13` → Sol 与 `#4`/`#6`/`#7` → DeepSeek Flash **不变**，主线 Sol 仍不做 line code review。同步 §12.1 Sol 行、§16 Epic I4 行、§17 第 4/5 条、§19、§13 Task 8 的 owner 与 Independent reviewers 行。**v1.40 未被改写**，只加内联「已被 v1.41 取代 / 勿照本行执行」标记——同一 PR 内的投影被超替，仍按本文既有惯例追加条目而非重写既有条目，否则读者无法看出这条治理事实在一小时内改过三次。见 §12.1 / §16 / §17 / §19 |
| **v1.42** | PR-2 第六轮 | **「定义与用法必须同向」是双向要求，v1.39 只做了一半。** ①该轮为 §6.7.4a 的 lease 门**加宽**了 §6.3.3 对 `LEASE_CONFLICT`(7) 的定义，却没给紧邻的 `STALE_LEASE`(8) 做对称**收窄**：8 的判据把「已 `RELEASED`」无条件列入、且只标注了 `release` 一处例外，而 §6.7.4a 冻结的调用序列恰恰是**先 release 再 advance**，`CompleteAndAdvanceRequestV1.leaseId` 本来就是一个已 `RELEASED` 的历史引用。两节因此直接矛盾——**按 §6.3.3 字面实现，`completeAndAdvance` 会对唯一合法的调用形态返回 8，该方法无条件不可用**；按 §6.7.4a 实现则正常。这不是措辞不一致，是同一个方法的两种各自自洽的读法，正是 §6.7.4a 自己定义的那类缺陷。现收窄 8 的定义、在例外标注中并列 `completeAndAdvance`，并在两节之间补**双向**交叉引用，使其不能再各自漂移。**修一个方向而不扫同类，矛盾只会挪到相邻一行。** ②同时把 `completeAndAdvance` 收到**非本 caller** `leaseId` 的处置显式标为**仍未冻结**：§6.7.4b 的判定次序里没有这道门，本轮不新增（归因造假的后果是审计把配额记到别人的 lease 名下，但该校验属 provider 侧行为，须与 `M-AD-12`/`M-AD-13` 的 owner 一并裁定），且在冻结前两侧实现都不得擅自选一种读法——一个用 8 拒绝、另一个放行，Auto 的恢复策略就不可移植。**把空白写成空白，不写成已解决。** ③§6.3 的「逐字段与实现一一对应」补齐**保序**读法：snippet 是 `@Parcelize` Kotlin，而 kotlin-parcelize 按**声明顺序**读写 Parcel，互换两个同类型字段会保留全部字段名、两侧照常编译、运行期什么也不抛，却在 wire 上静默交换取值——与 §6.1 的 Binder 事务号随方法声明顺序而变**同形，低一层**。`check-contract-v1.sh` 第 7 节因此由字段名集合升级为有序 (字段名, 类型, 可空性, 有无默认值) 元组比对，解析不了的属性行按失败处理而非跳过。④新增 `scripts/selftest-contract-v1.sh` 并接入 CI：门禁自身的**敏感性**此前无人测量，历轮变异证明都是手跑即弃的脚本，守卫被悄悄削弱不会有人发现。每条负例断言**具体 finding** 而非「变红」（红错原因是假红，与假绿互为镜像且在看板上无从分辨），并断言**恰好一个 check 失败**以证明隔离；M-\* 组逐个禁用守卫、要求对应 finding **消失**，用以区分承重守卫与被更旧检查兜底的死代码。⑤新增 **§20.1 已知边界总表**：②那条留白暴露出本文没有任何地方回答「还有哪些没被证明／没被冻结」——条目散在 §6.5.3 / §6.7.4a / §8.6.5 / §18.1 / §9，而顶部告示与 §19 各列**两条**、§20 写作**「无」**，三个入口三个不同子集，读者无从分辨「我没读到」与「它不存在」，而这两者要求的行动恰好相反。现收敛为六条（`KB-1..6`）并冻结分工：**本表是完整性的真相源，各权威节是内容的真相源**，新增边界必须同时进两处。四类不可互写——`limit` 已知证明不了、`unfrozen` 还没决定、`deferred` 可触达待闭合、`gap` 契约已冻结但无 ledger row 证明实现符合它；把留白写成上限等于宣布永不修，把上限写成留白等于承诺交付不出来的东西，而把 `gap` 写成 `limit`（"还没测"说成"测不了"）会让一件待办伪装成物理限制后永久退出清单。**`KB-6` 即本轮仍开项的第 5 项**：v1.39 自述 `M-AD-12`/`M-AD-13` 只是 provider-owned advance 覆盖的「第一批」，其余行需由该两行的 owner 按同一模式补齐并同步 §10／§10.1 行数与 §15 逐 lane 派生计数——**本 PR 不代为新增**，因为那是给 provider lane 派活、且要动七处派生计数，应与 `KB-5` 的裁定一并处理。同步修正顶部告示（补 §6.5.3 的 TOFU 上限，并声明自己不是全集）、§19（改为以 §20.1 为准，并新增「完成前 `unfrozen` 必须已被处置」）、§20（「技术项：无」→ `KB-5` 一条）。见 §6.3 / §6.3.3 / §6.7.4a / §20.1 |
| **v1.43** | PR-2 第七轮 | **「定义与用法必须同向」的第三处：canonical 修了，实现者真正会读的那一半没修。** ①三条 `ContractErrorCodeV1` KDoc 与 §6.3.3 直接矛盾，且每一条都是历轮"只修一个方向"的残留：wire **7** 的 KDoc 写 "**Another** lease already holds…"，而 v1.39 ② 已把本节对 7 的定义加宽为「推进时设备上任一非 `RELEASED` lease——**无论属于哪个 caller**」，KDoc 保留的恰是 v1.39 当轮判定为矛盾的那个字面读法；wire **8** 的 KDoc 写 "The referenced lease is no longer current"，**无方法作用域、无例外**，而 v1.42 ① 已收窄 8 并给 `release`／`completeAndAdvance` 双例外——**按该 KDoc 字面实现，`completeAndAdvance` 无条件不可用，§8.4 的 `EXPIRED → RELEASING` 收敛也永远走不到**，即 v1.42 在 spec 侧刚消除的那两个后果，在代码侧一直活着；wire **16** 的 KDoc 逐字保留 v1.39 **明确作废**的「终态而非失败」（"Terminal, NOT a failure"），而同模块的 `AdvanceOutcomeV1` KDoc 自同一轮起就写着更正后语义并点名 `SCHEDULE_EXHAUSTED(16)` 是"a real caller error"——**同一个 wire 号，同一个模块里两份 KDoc 互相矛盾**，更正只落到了一个文件。②为什么前九节全绿仍然漏：§5／§5b 绑的都是 **wire 数字**（表↔代码↔yaml、散文内联引用↔枚举），没有任何一节读**实现者真正会读的东西**——没人为查 `STALE_LEASE` 的含义去翻 3800 行文档，他们 hover IDE 里导入的那个符号。③新增 **§5c**，两条规则**都从 canonical 派生**而非硬编码：**A** 若某行把 code 作用域限定到接口方法，KDoc 必须逐个点名（方法全集取自 AIDL `interface`，v1.x 新增方法自动纳管，不需要改门禁）；**B** 若某行带更正标记（`更正`／`旧文`），KDoc 必须引用做出该更正的版本号——**被更正的一侧必须留下痕迹**。④**明写门禁做不到什么**：它证不了 KDoc 散文语义正确（跨自然语言的字符串比对做不到），只能让**漏写**不可能、并强制人在 canonical 限定或更正某码时去碰 KDoc；点名了正确方法的错句子仍会通过。Rule A 大小写不敏感且按词边界，英文动词 `apply` 也能满足它——这是**刻意的假阴性偏向**：对合法散文误报的门禁最终会被人关掉，那时 §5c 什么都保护不了。已知未覆盖类：一行列**两个**触发分支而 KDoc 只写一个（wire **1** 正是此例，本轮手工补齐并在 KDoc 内自陈该盲区）——该行无方法、无更正标记，两条规则都看不见。⑤**该未覆盖类不进 §20.1**：§20.1 的口径是「**契约**还有哪些没被证明／没被冻结」，本条是**守卫敏感性**的边界，其真相源是 selftest 套件（本轮由 `N-8`/`N-9` + `M-5`/`M-6` 承担）；把守卫盲区混进去会稀释上一轮刚冻结的四分类。⑥selftest 增至 **1 positive / 9 negative / 6 mutation**，两条新规则各自被证明承重且隔离（每条负例仍断言**恰好一个** check 失败）。M-5/M-6 记一笔方法论：`if missing:` 在门禁里出现 **4 次**，**不能**用作 sed 锚——sed 对多处匹配全部生效，会静默废掉四个守卫而变异用例照样"通过"，得出的"承重"结论是假的；改用唯一的**规则入口条件** `if need:`／`if absent:` 分别单独关闭。见 §6.3.3 / `scripts/check-contract-v1.sh` §5c / `scripts/selftest-contract-v1.sh` |
| **v1.44** | PR-2 第七轮（同轮第二项） | **一条阻塞冻结的已知边界，此前只活在聊天里。** ①§6.3.2 冻结「预期业务失败经 `ServiceSpecificException` 携带稳定 wire code 返回」，而 **`android.os.ServiceSpecificException` 不在 public SDK**——独立复验：对 `android-35` 与 `android-36.1` 的 `android.jar` 做 zip entry 枚举，`android/os/ServiceSpecificException` **零命中**，同时对照项 `android/os/Parcel.class` **命中**（对照项的作用是证明扫描方法有效，否则"零命中"与"我扫错了地方"不可区分）。它是 `@hide`，app 代码无法直接引用，因此本文冻结的错误传输通道**按字面不可实现**。②**本轮真正的缺陷不是这个事实，而是它的位置**：该事实此前**零处**出现在本文档，只存在于投给 operator 的 Decision Packet 消息里——而 §20.1 上一轮刚冻结的规则正是"**新增边界必须同时进两处**（本表管完整性，权威节管内容）"。一条阻塞契约冻结的开项，其唯一载体是聊天记录，任何读文档的人都无从知道它存在；这与 v1.42 ⑤ 要消除的"三个入口三个子集"是同一种病，只是这次的子集是**空集**。③故登记为 **`KB-7`**（`unfrozen`），同步顶部告示（六条→**七条**）与 §19 的 `unfrozen`／`gap` 枚举（`KB-5`／`KB-6`→ 追加 `KB-7`）。**本表只登记，不代为裁定**：选哪条传输通道是 operator 的价值取舍（Decision Packet 已投），按 §19 规则它在被处置前阻塞完成声明——一份错误传输不可实现的契约不能自称已冻结。④为什么九节门禁全绿仍然漏：两处载体（§6.3.2 散文、`ContractErrorCodeV1` 文件级 KDoc）**都是散文**，而本轮新增的 §5c 只覆盖 §6.3.3 **逐 code 行**的 KDoc，管不到文件级 KDoc 里的传输机制陈述。裁定后应补一道门禁，断言契约引用的每个 `android.*` 类型都存在于编译 SDK——**本轮不加**，因为该断言的形状取决于所选通道，先写会把门禁钉在一个可能被推翻的答案上。见 §6.3.2 / §20.1 / §19 |
| **v1.45** | PR-2 第八轮 | **冻结为「必须」的验证步骤，其前置条件在它被调用的那一刻不可满足。** ①§6.7.5 冻结「推进后**必须**独立 `observe()` 验证新环境」，理由是「receipt 是对方的自述，不是生效证据」；但该调用夹在 `completeAndAdvance` 与下一项 `apply` 之间，而 §6.7.4b 第 5 步的 lease 门是**设备全局**的——推进期间不得存在任何非 `RELEASED` lease，新 lease 要到下一项 `apply` 才授予。于是这一刻 `ObserveRequestV1.leaseId`（非空必填）唯一能填的就是已 `RELEASED` 的历史引用，而 §6.3.3 wire 8 把 `observe` 的「已 `RELEASED`」无条件列为失败。**按字面实现，一道被冻结为「必须」的步骤无条件不可达**，Auto 只剩「只信 receipt」一条路——正是 §6.7.5 指名要堵的错环境归因入口。②本轮冻结窄例外：`leaseId` 恰为本 caller 最近一次成功 `completeAndAdvance` 所携带的历史引用、且此后未获授新 lease 时，`observe` 必须受理；窗口外仍返回 8，「非本 caller」分支不动。不需要新增字段或方法——provider 为 §6.7.5 幂等重取本就必须留存 advance receipt；也因此**伪造 `leaseId` 落不进窗口**。③明确本例外不吞并发失配：他方抢先 `apply` 时 `observe` 仍受理，由 §6.7.5 的 hash／revision 比对判失配进恢复——**受理后判失配可诊断，返回 8 不可诊断**（与「lease 过期」同形，调用方无从区分该重试还是该恢复）。④**这是同一处缺陷的第三次同类**：v1.42 加宽 wire 7 漏了 wire 8 的对称收窄，v1.43 补了 `completeAndAdvance`，而紧邻**一跳之后**的 `observe` 仍留在拒绝侧。v1.42 自己写的「修一个方向而不扫同类，矛盾只会挪到相邻一行」再次应验，只是这次挪到了**下一跳**。故补通用要求：**冻结「必须」的调用序列时须逐跳检查每一跳前置条件在该时刻是否可满足**——序列可执行性是逐跳的合取，一跳不可达则整条序列是死的。⑤为什么九节门禁全绿仍然漏：§5c 只能强制 KDoc **点到** §6.3.3 行所辖的方法名，不能判断句子把该方法放在了准入侧还是拒绝侧——两侧都点了 `observe`，所以恒绿。见 §6.3.3 / §6.7.4a / §6.7.5 |
| **v1.46** | PR-2 第八轮（同轮第二项） | **状态机把「提交过一次配额」当成了「该项已达标」。** ①§8.1 的推进分叉判据是「配额已提交」——即成功插入一行 `TrustedQuotaEntry`——而达标判据在 §7.3 是 `count(TrustedQuotaEntry where taskId) >= requiredSuccesses`。两者不是一回事：`requiredSuccesses = 3` 的任务在**第 1 次** attempt 提交配额后就走上 `ADVANCE_PENDING`，日程被推进，剩下两次成功再也没有机会发生。§5 边界表写着「证明当前项**达标**后发起推进」，但那句话从未变成状态机上的门。现拆成未达标／已达标两条边，未达标边**不推进**。②`ADVANCE_EXHAUSTED` 直接落终态，绕过 `receiptDigest` 重算，而紧邻的 `ADVANCE_RECEIPT_VERIFIED` 边要求重算通过——§6.7.3 冻结「重算不上的 receipt 不是「弱一点的 receipt」，它不是 receipt」，且 `outcomeWire` 本就在 preimage 内，**耗尽 receipt 与推进 receipt 同样可验证，没有任何理由只验一个**。现改为 `ADVANCE_EXHAUSTED_VERIFIED`。③推进后观察只比 `scheduleItemId` 一条腿，而 §6.7.5 要求同时比 `effectiveIntentHash` 与 `effectiveEnvironmentRevision`。**要求写在 §6.7.5，实现读的是 §8.1**，于是少比的两条腿没有任何载体会报错——这正是 v1.42「定义与用法必须同向」的形态，只是这次错位发生在散文与状态机之间。现冻结 `OBSERVED_TUPLE_MATCHES`／`OBSERVED_TUPLE_MISMATCH` 三腿合取，并在两节间补双向交叉引用。注意两种单腿读法**互为镜像**：只比 item 会放过「环境根本没换」，只比环境会放过「同 profile 跨项复用」。④补 `M-AD-14..20` 七行：未达标不推进、达标判定前崩溃、耗尽 receipt 摘要失败、intentHash 腿失配、revision 腿失配、跨新分叉同键重放幂等，以及 **`M-AD-20` 合法序列**——`M-AD-01..13` 竟无一行断言 §6.7.4a 序列唯一的合法形态（以已 `RELEASED` 历史 `leaseId` 推进、再以同一 `leaseId` observe），v1.45 冻结了该规则却没有留下台账行，本轮补上。⑤§10／§10.1 由 103 增至 **110 行**；`owner-red` 64→**84**（GLM 48 / Fable5 36），`sol-blackbox` 22 · `static-guard` 2 · `device` 2 不变；22 行散文共 29 处派生计数**从台账现算后一次改完**。并冻结规则：**派生计数只能从 §10.1 现算，散文里的都是缓存**，增删台账行必须在同一 commit 内重算全部缓存点。见 §7.3 / §8.1 / §6.7.5 / §10 / §10.1 / §15 |
| **v1.47** | PR-2 第八轮（review 回合） | **冻结了「计数只能从台账现算」的那一轮，自己就是手算的。** ①@deepseek-pro 对 `9027f877` 的 review 抓到一处 P2：§16 Task 8 第 3 条同一句内，前半已改 **36**、后半仍留 **33**。这正是 v1.46 commit message 里**指名道姓**说自己扫掉了的那类「已改过的同一行里更靠后还有第二个陈旧数」——**它自己又犯了一次**。②真正的问题不是这个数，是 v1.46 冻结了规则却没留任何执行者：**一条没有守卫的决定就是一条前决定**（这句话是 v1.46 自己在 golden 向量那轮写的）。散文对散文的同步至此已失败**四次**：v1.38 同步台账漏 lane selector 表；v1.39 修 lane selector 表漏 20 行外的聚合表与责任表、还自述「四个计数已全部校正」；v1.46 手算 29 处漏 1 处；而那 1 处正是它自称已扫的形态。四次不是粗心，是缺守卫。③故新增 `scripts/check-derived-counts.sh` 并接入 CI `contract-v1` job：从 §10.1 台账重算 total／各 class／各 lane，校验 §10↔§10.1 行集合一致、ID 无重复、class 划分穷尽、lane selector 并集等于 `owner-red`，并扫描**活跃散文**中每一个与台账词汇（`owner-red`／台账／矩阵行）同现的计数，凡台账产不出的值一律判红。**先红后绿留痕**：加守卫时文件里还留着那个 `33`，守卫必须先red在它身上，改完才转绿。④守卫第一版**没抓到它要抓的那个 bug**——排除历史行的正则写了 `v1.NN `，而出问题那行恰好含「（v1.23 新增，非可选）」，于是被自己豁免了；**版本引用不是历史引文**。已收窄为只认「更正／旧文／逐字引用／上一版／此前写作」。一个先红证明跑出来红在别处、真 bug 反而被放过的守卫，与没有守卫等价——**红了不等于红对了**（与 `selftest-contract-v1.sh` 每条负例必须断言具体 finding 同理）。⑤`appid-cutover` 的 5 行已按 §16 拆到 Issue #13、不在台账内，故只在**点名该词的行**上放行 5，而不是把 5 放进全局合法集——否则一个陈旧的 5 可以藏在任何地方。见 §16 / `scripts/check-derived-counts.sh` |
| **v1.48** | PR-2 第八轮（清尾） | **presence 判别位修好了判别层，没往下扫一层。** ①§6.3.1 写「UTF-8 bytes，原样」，但 JVM 的 `String.toByteArray(UTF_8)` 对**未配对代理**静默替换为 `?`(0x3F)——于是 id `"\uD800"` 与 id `"?"` 产出同一串字节、同一个 digest，**framing 的单射性在它自己接受的输入上就不成立**。item id 经 Binder 以 Java String 传递、`Parcel` 走 UTF-16，该输入**可达**。后果与哨兵碰撞同级：重放可能拿另一个请求的 receipt 作答。②这与 v1.4x 引入 presence 判别位是**同一类缺陷**——presence 消除「值可能等于哨兵」的偶然，本条消除「值可能不可编码」的偶然；**一个在判别层，一个低一层在字节转换层**。上一轮只扫了判别层，所以历轮 hostile id 负例全绿却漏掉它：**那些负例比的都是「id vs 缺省」，没有一条比过两个会互相碰撞的 id**。反例设计只覆盖了一个方向，就会在另一个方向上留下整类缺口。③冻结：进入任何 preimage 的字符串必须良构（不含未配对代理），否则 digest **必须失败**、不得替换后继续；`CanonicalDigestV1.utf8` 改为 fail-closed 并加正反测试（未配对高位/低位/前后缀四例必须被拒且理由具名；合法代理对必须仍能编码且不与 `?` 碰撞）。provider 边界以何 typed code 拒绝畸形 id 属 provider 侧，本轮不代冻结。④§0.1 v1.37 日志行的「16 `SCHEDULE_EXHAUSTED`（终态非失败）」补更正标记：**v1.37 正是废止该读法的那一轮**，日志不标记就等于误报了它自己记录的修订。按 §0.1 惯例**保留原文、只加标记**，不改写历史（与 v1.39／§6.3.3／§6.7.4 三处已有标记同形）。见 §6.3.1 / §0.1 |
| **v1.49** | PR-2 第九轮（Sol P1 回扫） | **被命名为「防双推进最后一道」的那道，打不响；而双推进有两条活路。** ①`M-AD-04` 自述「**这是防双推进的最后一道**」，要求丢幂等键重发时返回 `SCHEDULE_ITEM_MISMATCH`(14)；但 §6.7.4b 第 3 步 `proof 与当前项 CAS 不符 → REQUEST_INVALID(13)` 排在第 4 步之前，而推进后 `proof.scheduleItemId` 与 `expectedCurrentItemId` **必然都已过期**（本文 §6.7.4b 自己写了这句）。于是丢键重发恒落 13，**14 永不可达**，`M-AD-04` 只能靠违背冻结次序才变绿。现拆分：第 3 步只判 proof **内部**自洽（`proof.scheduleItemId` vs `expectedCurrentItemId`），与**实际** `currentItemId` 的 CAS 移入第 4 步且只在第 4 步判定；`M-AD-01` 谓词同步由「≠ 当前项」改为「≠ `expectedCurrentItemId`」，否则两行再次重叠。②原子边界只写了「指针 + receipt 同一事务」，幂等查表/插入与 proof／schedule／lease 三门的谓词读全在边界**之外**。于是两个持**不同新 key** 的请求可同时读到旧 `currentItemId`、同时过三门、各自提交一次推进——**双推进，而每次单看都合法**。「同一事务」这个词同时被用来指**崩溃原子性**与**并发原子性**，它只兑现了前者。现冻结第 2–6 步整体为一个 serializable 事务／CAS 临界区，指针写入以谓词读到的值为 CAS 前置；lease 门的串行化对象由「并发 `apply`」扩为「并发 `apply` **与并发 `completeAndAdvance`**」——只串 `apply` 时两个并发推进各自读到无 lease、各自放行，该门形同虚设。③补 `M-AD-21`（两个不同新 key 并发 → **恰好一次**推进）与 `M-AD-22`（谓词读与指针提交之间注入故障 → 三者状态一致）。④§5b 的行内引用正则要求括号内是裸数字，读不到本文实际使用的 `` `NAME`(**13**) `` 加粗形态——**而第 125 行那句「§5b 使该类错误不能再静默通过」的修订说明，它自己引用的那个 wire 正是门禁读不到的那个**。把它改成 (**4**) 依然零 FAIL。同一份脚本里 §6.3.3 表解析器**早就**带 `\*{0,2}`，两个解析器对同一种 markdown 不一致。现补齐容忍，并把该容忍提取为具名 `BOLD` 旋钮——内联四处会导致变异无法只关掉它而不同时关掉表解析器，**同时关两个守卫的变异证明不了哪一个承重**。⑤§5b 此前**零 selftest 覆盖**，这正是④能活到今天的原因；补 `N-10`／`M-7`。⑥`KB-6` 存货写「两行」已过期——v1.46 加 `M-AD-20` 时没回来改它，**违反的正是本表自己冻结的「新增边界必须同时进两处」，而违反者是加那行的人**；现更正为五行并写明仍零覆盖的四项。⑦§10／§10.1 110→**112**，`owner-red` 84→**86**（GLM 48 / Fable5 38），30 处缓存计数同 commit 重算——**本轮由 `check-derived-counts.sh` 判红后才发现，守卫抓住了写它的人**。见 §6.7.4b / §6.7.5 / §10 / §20.1 |
| **v1.50** | PR-2 第九轮（同轮第二项） | **关闭 P1 的那两个守卫，自己从未被测量。** ①Sol 的 `P1-1(a)`／`P1-1(b)` 分别由 §5（enum 严格解析）与 §6b（有序全签名）关闭，但 selftest 的案例此前只覆盖 §7／§5c／§5b——**即：证明「守卫已修好」的那件事本身没有守卫**。这与 §5b 盲点能活到今天是同一形态，只是上移了一层。②补 `N-11`（互换两条 AIDL 声明顺序：方法**名集合不变**，§6 按设计仍绿，只有 §6b 的按位比对能看见——而 Binder 事务号随声明顺序而变，这一互换会**静默重编号 wire**）与 `N-12`（植入重复 §6.3.3 行：旧 dict 解析会让后一行覆盖前一行并报 PASS，严格解析必须拒绝比对一张读不明确的表），及对应的 `M-8`／`M-9` 承重证明。suite 由 1+10+7 增至 **1 positive / 12 negative / 9 mutation**。③`M-9` 第一版**红在我自己身上**：sed 只改了消息前缀 `canonical §6.3.3:`，而断言子串 `duplicate row for LEASE_CONFLICT` 在改后的消息里原样存活，于是 mut() 正确报出「关掉守卫后 finding 依然存在」。**变异必须关掉判据本身（`if name in table:`），不是重命名它的输出**——改文案而非改条件的变异会把「我以为关掉了」当成「关掉了」，从而给出一个假的承重结论。这条 harness 拒绝了写它的人的第一版。见 `scripts/selftest-contract-v1.sh` |
| **v1.51** | PR-2 第十轮（Terra lease 外裁定回修） | **契约在纸面上不可互操作，而所谓「两个独立实现互证」证明不了这一点。** ①§6.7.3 的 advance-**request** preimage 代码块从 `leaseId` 起列，**没有 domain 行**；而 `CanonicalDigestV1` 把 `DOMAIN_ADVANCE_REQUEST` 固定为首个 framed 字段，§6.7.4b 第 1 步又要求 provider **由收到字段重算 requestDigest**。于是照 canonical 实现的独立 provider 算出无 domain 的 digest、Auto 算出有 domain 的，**字段全对仍必落 `REQUEST_INVALID`(13)**——该方法对任何第三方实现无条件不可用。②v1.47 的 golden 向量号称「两个独立读法互证」，**但两个读法同源**：那个 Node 实现是照着 Kotlin 写的，不是照着 canonical 写的。**同源的双实现只是把一个读法拼写两遍**，spec↔impl 分歧从一开始就在它能表达的范围之外。③同段第二处：receipt preimage 仍写 `为 null 时用哨兵`，而紧接其下五行的散文写的是「**显式存在性判别位**，**不是哨兵值**」——**规范代码块与它自己的更正互相矛盾**。④新增门禁 §7b：解析 canonical 每个 preimage 代码块，要求它声明 domain，且该字面量必须等于 module 常量。**先红后绿留痕**：加 §7b 时 domain 还缺着，它先红在真缺陷上。⑤§7b 前三版都**红错了地方**——正则先只认 `domain = `（漏掉 intent 的 `domain : ASCII` 写法）、再只认裸 ``` 围栏（漏掉 ```text）、再用 `[^`]*?` 匹配块体（遇块内反引号即断）。**三次都是模式比文档实际记法窄，症状都是把「存在」报成「缺席」**——与 §5b 那次同形，犯者是刚修完 §5b 的人。⑥P2：两处 EOF 空行清理。此前我曾误判它与 provenance 冲突，实测该门禁报的是「工作树未提交」而非「与 upstream 不符」，且 vendored apps 本就允许被 PR-2/3/4 合法修改——**差点用一个错误归因驳回 reviewer 的正确意见**。见 §6.7.3 / §7b |
| **v1.52** | PR-2 第十轮（同轮第二项） | **上一轮刚修完「关闭 P1 的守卫自己没被测量」，新造的守卫又是零覆盖。** ①v1.51 的 §7b 为关闭 Terra 的 P1-2 而生，落地时**没有任何 `N-*`／`M-*` 案例**——与本轮刚补完的 §5／§6b 缺口同形，区别只是**这次没被测量的守卫是一小时前自己造的**。②而 §7b 恰恰最需要负例：它在被写出来的过程中**三次红错了地方**（正则依次漏掉 `domain : ASCII` 写法、```text 围栏、块内反引号），三次症状都是把「存在」报成「缺席」。一个曾经三次误判的守卫，若没有负例把行为钉死，下一次收窄或放宽都无人察觉。③补 `N-13`（抹掉某 preimage 块的 domain 行 → 必须报出该块）与 `M-10`（关掉 `missing` 的**计算**而非它的输出文案 → finding 必须消失，沿用 `M-9` 的教训）。suite 由 1+12+9 增至 **1 positive / 13 negative / 10 mutation**。④记一条通用要求：**新增门禁节的同一个 commit 内必须带上它的负例与承重证明**——否则「我加了守卫」与「我加了一段从未被要求失败过的代码」在看板上无从分辨。见 `scripts/selftest-contract-v1.sh` |
| **v1.53** | PR-2 第十一轮（Terra P1-3） | **守卫用自己的盲区当单位报了一个计数，那个计数读起来像覆盖率。** ①§7b 只认以 `canonical`／裸 `domain` 开头的围栏块，而 §6.3.4 写作 `canonicalRequest =`、其 domain 用 `apply → "..."` 映射记法——于是 `fakexxx.contract.v1.apply` 与 `.release` **从未进入守卫**，§7b 却打印 `PASS 3 canonical preimage block(s)` 并全绿。**「3 块」不是覆盖率，是它认得的块数**；改 apply/release 的 domain 或改该块写法，§7b 与 CI 都会继续通过。②改为**全量清单**：识别 4 个 preimage 块、5 个 domain 并逐个列名（`intent`／`advance-request`／`advance-receipt` 用**冒号**分隔，`apply`／`release` 用**点号**——同一份契约里两套 domain 命名法，已提请 reviewer 裁定是否收敛）。module 的 3 个常量必须是 canonical 清单的**子集**；反向包含**刻意不断言**，因为 apply/release 是 canonical-only、provider 侧、无 module 常量——该边界现写在守卫注释里，不留给下一个读者猜。③仅「每块至少一个 domain」不够：§6.3.4 声明两个，删掉 `apply` 还剩 `release`，逐块检查照绿而一个 wire 级身份已消失。故**冻结完整 inventory**，删除或重命名任一 domain 即红，新增 domain 必须显式改 §7b 而不能悄悄放宽。④补 `N-14`（重命名 canonical-only 的 apply domain → 必须红）与 `M-11`；suite 增至 **1 positive / 14 negative / 11 mutation**。⑤`M-10` 的变异锚随本轮重写而失效，mut() 报 `INCONCLUSIVE：变异根本没改动 gate`——**它拒绝把「变异没生效」当成「守卫承重」**，已重新对准现判据。⑥**本轮是同一形态的第四、五次**：写 §7b 时正则三次比文档窄，本轮又两次（漏 `canonicalRequest`、过滤器用合取而非析取漏掉 receipt 块）。**每一次的症状都是把「存在」报成「缺席」，而每一次我都以为已经扫干净了。** 见 §7b |
| **v1.54** | PR-2 第十二轮 | **耗尽状态模型冻结与步 4 内序重排。** 来源：Fable5 四答定稿（Q1-Q4）。**状态模型**（独立判别位 + 末项保持）已由既有实现验证（660 tests + 5 轮 DSF review + `FakeQwyEnvironment` 判别位持久 + 生产 `QwyScheduleStore:107` 同构），非新设计而是 ratify；**步内 16-first 行为**是本轮冻结的新次序，provider handler（`EnvironmentControlHandler.completeAndAdvance`）仍实际执行旧序 14→15→16，待 provider lane 落 RED test + handler 重排后生效。①**冻结 `exhausted` 独立判别位**：schedule 运行时状态冻结为 `(currentItemId: 非null String, exhausted: Boolean)` 三态模型（无 schedule / 进行中 / 已耗尽）。排除 null 指针（与"尚未开始"撞 + 14-shadows-16 + sentinel 反模式）与越界魔值（§6.7.3 取消 ID 受限假设）。bit 生命周期：完成末项的 committed advance 置位，schedule (re)init / version 变更清零。receipt `advancedToItemId = null` 是 OUTCOME 编码，STATE 里没有 null。②**步 4 内序由 `14→15→16` 重排为 `16→14→15`**（exhausted-first）。三条推理各自充分联合必要：(a) 规范自身先例——步间已裁定 16 先于 7，步内同理；(b) 恢复语义诚实——14/15 承诺可恢复，耗尽后无一可恢复，答 14/15 是谎报；(c) 确定性——M-AD-11 的"稳定 16"从条件命题变恒真。**唯一行为 delta = 耗尽后陈旧期望：旧答 14，新答 16**，恰是被修缺陷本身。现有 14→15 相对序不重开。③**M-AD-10/11/13/21 同步刷新**：M-AD-10 加 bit + 指针持久性与 restart 存活；M-AD-11 加三变体（保持项期望 / 历史 non-null 期望 / restart 间隔）全 →16；M-AD-13 加步内多重违反例（exhausted + stale item + stale version → 首命中 16）；M-AD-21 并发分叉精确化（非末项 loser 精确 14——winner 同时移 item + version 但步内序 14 先于 15 故 item 失配已确定答案；末项 loser 精确 16——winner 置 exhausted 后步内序首命中 16）。见 §6.7.4 / §6.7.4b / §10 |
| **v1.55** | PR-2 第十二轮（wire 投影） | **`exhausted` 状态存在于 provider 内部却不上线，消费者只能猜。** ①v1.54 冻结了 `exhausted` 独立判别位，但 `CapabilitySnapshotV1`（§6.3 discover DTO）与 `PreflightReportV1`（§6.3.2 preflight DTO）未携带该字段——Auto 能读 `currentItemId` / `scheduleVersion` 却读不到 `exhausted`，无法构造 §6.7.4b 第 4 步 16-first 的前置。②`CapabilitySnapshotV1` 新增 `val exhausted: Boolean?`，位于 `scheduleVersion: Long?` 之后。**Group invariant**：`currentScheduleId` / `currentItemId` / `scheduleVersion` / `exhausted` 四者同为 null（无活动 schedule）或同为非 null。null 读作「没有 schedule」而非「未耗尽」。③`PreflightReportV1` 新增 `val exhausted: Boolean?` 并将 `scheduleItemId: String` / `scheduleVersion: Long` 改为 nullable。v1.55 前这两个字段非空，handler 用 `?: ""` / `?: 0L` 填充——round-5 sentinel 反模式在 wire 层。三者同为 null 或同为非 null，与 `CapabilitySnapshotV1` 一致。④**preimage 安全**：`CapabilitySnapshotV1` 经实查不在任何 canonical digest preimage（`CanonicalDigestV1.kt` 零 import / 零引用），新增字段不变更 digest；`PreflightReportV1` 同理。⑤kotlin-parcelize 按声明顺序 read/write，`exhausted` 追加在末尾，**不扰动已有字段的 parcel 位置**——但 v1 协议尚未冻结 wire（Draft PR），版本 skew 不适用。见 §6.3 / §6.3.2 |
| **v1.56** | PR-2 第十二轮（版本语义 + KB-8） | **终末项推进返回 V 还是 V+1 没人说过，而 v1.54 的清零触发能把自己写的位在同一事务内抹掉。** ①Sol 的 exact-HEAD review 抓到：M-AD-21 非末项写了「`scheduleVersion` 递增」但 M-AD-10（末项完成）**零字**提及版本行为；v1.54 写「schedule (re)init / version 变更清零」，若终末项也递增版本，同一 committed advance 事务既置 exhausted=true 又因版本变更清零——**自己写的位在同一提交内被自己清除**。provider 证据不一致：production `QwyScheduleStore` 返回 V+1，fake 返回 V，handler 用 pre-advance version 构造 receipt。②**冻结统一规则**：每次 committed advance（含末项）在其 CAS 事务内将 `scheduleVersion` 恰递增 1，receipt 的 `scheduleVersionAfter = expectedScheduleVersion + 1`——终末项与非末项同规则，无特殊分支。§6.7.1 同步扩展定义域：`scheduleVersion` 不只跟踪计划配置变化，也跟踪 committed advance。③**消歧 v1.54 清零触发**：置 exhausted、保持指针、递增版本是同一 CAS 事务的三个组成部分。「version 变更清零」现收窄为**外部** schedule (re)initialization（operator 发起、不经 advance 协议），advance 自身的原子版本递增不是清零触发——否则 exhausted 在写入它的同一事务即被清除而不可能存活。④M-AD-10 补 `scheduleVersionAfter = expectedScheduleVersion + 1` + 原子三态；M-AD-21 末项 case 补版本递增。⑤**KB-8 登记坐标所有权**（Sol P1-2）：§2.2 与 `EnvironmentIntentV1` 的坐标归属矛盾此前只活在 Decision Packet v2 消息里，§20.1 零条——与 KB-7 同病。现登记为 `unfrozen`，operator 裁定后冻结 wire 行为。顶部告示七条→**八条**，§19 同步。见 §6.7.1 / §6.7.4 / §10 / §20.1 |
| **v1.57** | PR-2 第十三轮（终末验证 + reinit 版本） | **终末推进后 observe 的三条腿永远不可能命中，而 reinit 可以不碰版本就清 exhausted。** ①Sol 的 exact-HEAD review 抓到两个 P1：(a) `ADVANCE_EXHAUSTED_VERIFIED → CLOSED` 边跳过 `ADVANCE_OBSERVING`，但 §6.7.5 的散文写「推进后必须独立验证」——**状态机与散文互相矛盾**；且 `AdvanceReceiptV1.advancedToItemId = null`（终末 outcome 编码）而 `EnvironmentObservationV1.scheduleItemId: String`（非空），三条腿中 `scheduleItemId == advancedToItemId` 恒 false，observe **结构上不可能** 命中 `OBSERVED_TUPLE_MATCHES`。(b) §6.7.1 只要求顺序/成员/优先级变化或 committed advance 自增 `scheduleVersion`，而 bit 生命周期允许外部 (re)init 清 `exhausted`——**同拓扑 reset 可以把 `exhausted true→false` 而 version 不变**，消费者持有的 `(currentItemId, scheduleVersion)` 对无法区分旧世代与新世代。②**冻结终末推进免除 observe**（三条独立理由各自充分）：环境不变（末项保持）、结构上不可能（null vs non-null 恒 false）、receipt digest 已充分（耗尽 receipt 与非终末 receipt 同样可验证）。§6.7.5 散文改「非终末推进后必须独立验证」；observe 谓词表 `scheduleItemId` 行加「仅适用于非终末推进」；§8.1 路径不变（`ADVANCE_EXHAUSTED_VERIFIED → CLOSED` 已正确）。③**冻结 (re)initialization 必须递增 `scheduleVersion`**：§6.7.1 版本触发域扩展加 `schedule (re)initialization`；bit 生命周期散文同步。④补测试案例：`CapabilitySnapshotV1 exhausted=true` 终末快照、`PreflightReportV1` 全 null schedule group。见 §6.7.1 / §6.7.5 / §8.1 |
| **v1.58** | PR-2 第十四轮（Sol exact-HEAD P1 回扫） | **豁免被写成了「只信 receipt」，而 receipt 正是要被验证的那个对象。** ①v1.57 冻结「终末推进免除 observe」，三条理由中的第③条是「receipt digest 已充分」——但 §6.7.5 立身的原句理由正是「**receipt 是对方的自述，不是生效证据**」。digest 重算只证明 provider 为**它自己填写的字段**做了 canonical framing 并绑定本请求，**不证明** `exhausted = true`、末项指针与 `V+1` 已持久化。可构造反例：provider 未落 `exhausted`（或回绕／错写 version）却返回一份内部自洽、digest 重算通过的 EXHAUSTED receipt，Auto 据此 CLOSED，而下一次 `discover()` 仍把末项当可执行——**终态保护迟到整整一轮**。用「receipt 可验证」豁免「不能只信 receipt」，是拿这条规则明确拒绝的东西当它的替代品。②现收窄：**豁免的只是「环境比对」这一步，不是「独立验证」本身**。①②两条理由（环境不变、结构上 `advancedToItemId=null` vs 非空 `scheduleItemId` 恒 false）继续成立，仅说明 `observe()` 这个**载体**不适用；第③条改为**强制独立状态回读**：终末推进后必须 fresh `discover()`／`preflight()` 回读，三条腿合取 `currentItemId == advancedFromItemId` ∧ `scheduleVersion == scheduleVersionAfter` ∧ `exhausted == true`，任一不成立 → `RECOVERY_REQUIRED` 且 typed reason 指明哪条腿。载体走 v1.55 已冻结的公开投影，**不新增 wire、不新增字段**。③§8.1 由一条 `ADVANCE_EXHAUSTED_VERIFIED → CLOSED` 拆为三条：`→ ADVANCE_STATE_READBACK`、`EXHAUSTED_STATE_CONFIRMED → CLOSED`、`EXHAUSTED_STATE_MISMATCH → RECOVERY_REQUIRED`，与既有 `OBSERVED_TUPLE_MATCHES`／`MISMATCH` 同形。④**同类一次扫净**（v1.57 只改了 §6.7.5 一处，其余载体仍写「推进后必须 observe」，两种 conforming 读法并存）：§6.3.3 lease 窗口、§6.7.4a 序列图、`M-AD-09/17/18` 限定为**非终末**、`M-AD-20` 标注终末不走该序列且回读载体不受 lease 窗口约束、`AdvanceReceiptV1` 与 `EnvironmentObservationV1` 的公开 KDoc 同步。⑤补两行证据：**`M-AD-23`**（终末只拿到可验证 receipt 但 provider 未落 `exhausted` → 必须回读并进 `RECOVERY_REQUIRED`）与 **`M-AD-24`**（同拓扑外部 reinit 清 `exhausted` 必须同时递增 `scheduleVersion`，否则旧 `(item, version)` 与 proof 跨世代复用）——v1.57 冻结了 reinit 规则却没留台账行。§10／§10.1 112→**114**，`owner-red` 86→**88**（GLM 49 / Fable5 39），散文缓存计数同 commit 重算——**本轮又是 `check-derived-counts.sh` 判红后才发现，且同一行里三处计数我只改了两处，仍由守卫抓出**。见 §6.3.3 / §6.7.4a / §6.7.5 / §8.1 / §10 |
| **v1.59** | PR-2 第十五轮（`KB-7` 裁定落地） | **登记不是传输：CI 编译的只是 DTO 与方法签名，不是被要求的失败通道本身。** ①`KB-7` 经 operator 裁定为 **A**——业务错误必须经 app-public、版本化的 typed-result 载体返回，不依赖 hidden wire。§6.1 六个方法统一返回新增的 `EnvironmentControlResultV1`（AIDL import 表同步改为镜像真实文件，此前它既漏 advance 两型又列着已不再直接上线的 payload 型）；§6.2 新增 `ContractResultKindV1` 与 kind↔payload 绑定表；§6.3.2 新增其 exact schema（**10 字段有序元组**，过 §7 门）与六条不变量；§6.3.3 尾段的 `ServiceSpecificException` 陈述改写为载体，**并顺带更正该句把属性名写成 `wireCode` 的错误（实际是 `wire`）**。②**`ContractResultKindV1` 全域 1-based，`wire 0` 永久非法**（operator option B）。曾评估的 `ERROR = 0` 能让成功 kind `1..6` 与 §6.1 方法位序精确对齐，且**两案 fail-closed 安全性等价**；取 1-based 的理由不是安全性而是**冻结卫生**——「0 永远非法」是这个 wire 家族唯一可执行的跨域不变量，破例一次就把它降级成只写在散文里的约定。③**`KB-7` 不注销，降级**：裁定闭合的是**实例**（`ServiceSpecificException` 不可实现），没有闭合 v1.44 ④ 自己挂起的那道门禁——「契约引用的每个 `android.*` 类型必须存在于 public compile SDK」。该规则本轮在 §6.1 冻结为通用条款，但**零守卫**，故 §20.1 `KB-7` 由 `unfrozen` 改记为 `gap`，顶部告示与 §19 同步（§19 的 `gap` 出口补上「门禁」，此前只写 ledger row）。**把「实例已修」读成「同类已防」，正是本表 `gap`／`limit` 不可互写要挡的那一步**；而 v1.44 挂起门禁的理由（「断言形状取决于所选通道」）**已随裁定失效**，不能继续沿用。④§12 目录树的 `contracts/` 分支此前漏 4 个 `.aidl` 与 5 个 `.kt`（advance 三件套、`CanonicalDigestV1`、本轮新增载体）；本轮由**文件系统重新生成**该分支，并就地声明其余分支为示意——**部分更新的清单会被当成完整清单读**。⑤本机静态门禁全绿（§7 现为 **14 DTO / 104 字段**）；§4 需 JDK，本机无 Java Runtime，由 CI 判。见 §6.1 / §6.2 / §6.3.2 / §6.3.3 / §12 / §19 / §20.1 |
| **v1.60** | PR-2 第十五轮（派生计数回补） | **一道刚被加固的守卫，在它加固的那一刻就是红的，而没人看见——因为它前面那一步先红了。** ①`6cfea07` 把 `check-derived-counts.sh` 的扫描面从「带反引号的 `owner-red` 行 + 两种数字写法」扩到五条具名 arm，于是**四种此前不可见的写法**（纯文本 owner-red 行、裸数字单元格、加粗数字、中文数字）第一次进入扫描——一次就照出 **15 处**陈旧缓存。②**但它落在 `feat/pr-2-contract-v1` 上就是红的，且这个红被 CI 步骤顺序藏住了**：`check-contract-v1` 排在前面，KB-7 期间它一直红，GitHub Actions 前一步失败后面就不跑，所以 `check-derived-counts` 的红**一次都没有出现在任何人的判读里**——上一轮「contract-v1 仅因 canonical sync 而红」的结论是真的，但少读了一层：canonical 修好之后，第二处独立的红才会露出来。③15 处的真身：`110 → 112 → 114` 与 `84 → 86 → 88` 两轮台账增长中，**行内文字被改了、加粗单元格没被改**（`| **110** |` 经 blame 确认是 110 时代的原值，从未随文字一起 bump），外加 `pr-3` 的 `36`／`38`（两个不同时代的残留）与 `pr-4` 的 `48`。现全部按台账当前真相重算：总数 **114**、`owner-red` **88**、`pr-3` **39**、`pr-4` **49**。④`KB-6` 的「现为**五行**」不是过期，而是**结构上不该是数字**：它数的是台账 selector 表达不了的子族（provider 侧 advance 产生逻辑），守卫因此只能判它非法。现改为**直接枚举那五个 row ID**——这不是绕开守卫，正是 `6cfea07` 作者自己在 commit message 里写下的解药「**enumerate, never count**」：一个过期的数字没有任何东西能发现，一个缺失的 ID 有。⑤本轮**不改守卫**：15 处全部是本文的缓存问题，守卫一处也没冤枉（`KB-6` 那处判的是「这不该是个数字」，也成立）。⑥`selftest-derived-counts.sh` 的 P-1 **按 `6cfea07` 自己在文件头写下的生命周期翻绿**：该正例当时被**故意**倒置为断言红（「重算属于主线，不属于本分支；重算落地时 P-1 必须在同一 commit 翻成断言绿」），本轮重算落地即执行之。翻绿后钉的不再是一串幸存缺陷，而是两条经得起任何重算的性质：**零陈旧点 + 逐 arm 枚举仍在打印**——后者才承重，因为「0 处陈旧」也是一个**停止查看**的守卫会打印的东西，两者仅凭结论行不可分辨。同时 N-A／N-C／N-F 的 plant 锚点随本轮改动重挂（`36→39`、`**38**→**39**`、`KB-6` 单元格已无数字）：**锚点失效的负例不会变红，它会静默不跑**，而「the case never ran」和「guard is load-bearing」长得毫不相像。⑦把 `selftest-derived-counts.sh` **接进 CI**（此前只存在、从不运行）。`6cfea07` 没接线是对的——P-1 当时故意红，接了等于把 CI 钉在一个缺陷上；P-1 翻绿后接线才成立。**一个存在但从不运行的 selftest 不是守卫**，这与 `KB-7`「登记不是传输」是同一形状：载体在，它声称承载的性质从未真的被承载。见 §14 / §15 / §20.1 |

v1.1 的动因：主实现作者在动手前对照两个上游的精确 SHA 做了只读核验，发现若按 v1 原样冻结 AIDL，其中数项缺口只能靠 v2 或用户数据迁移来补救。全部修订均在 contract 冻结前落地，因此不产生 v2 债务。

| 项 | 变更 | 章节 |
|---|---|---|
| 意图绑定 | 新增 `EnvironmentObservationV1.acceptedIntentHash`、canonical digest 算法、坐标容差；可信谓词新增意图绑定段 | §6.3、§6.3.1、§6.4、INV-23、AC-13 |
| DTO 补全 | 补齐 `ApplyRequestV1`/`PreflightRequestV1`/`PreflightReportV1`/`ObserveRequestV1`/`ReleaseRequestV1`/`ReleaseReceiptV1`，`EnvironmentIntentV1` 纳入文件所有权 | §6.3.2、§12 |
| 枚举 wire | 枚举改为稳定 `Int` wire code + 显式 `fromWire()`，未知值 fail-closed | §6.2、§6.8 |
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
| 1 | **owner 真相未传播。** operator 已把 #4 从 Kimi 转给 DeepSeek Flash（主 Thread `0001786311069292-001378-b555f28c`：「完成调度设计后，把 kimi 的任务给 deepseek-flash 吧」），但**现行规范**仍在 §10.1 的 33 行 owner 台账、§12.1、Task 3/6/7/8、§15 PR 图、§16 issue 图、§17 角色、§19 completion gate 里写 Kimi | 现行区 **52 行（53 处出现**——Task 6 的 PR 路由句里出现两次**）**全部改为 DeepSeek Flash；**§0.1.x 历史修订记录中的 5 处保留**——它们描述的是当时的真实状态，改掉就是伪造历史 |
| 2 | **`INV-29` 有规则、没有证据载体。** 该不变量列出了旧安装探测、迁移桥 round-trip、回滚、CSV 负例与静态扫描，但 §10 / §10.1 里**一行都没有**；AC-10 与 §19 仍写 `INV-01..28` | 新增 `appid-cutover` 类 5 行（`M-AC-01..05`），按 Sol 的「跨 owner 必须拆行」原则分派：探测 / 迁移桥 / CSV 负例 = Opus5 `owner-red`；回滚演练 = Sol `device`；仓库-日志扫描 = Sol `static-guard`**〔当轮记录；v1.20 起 `device`/`static-guard` 两类一律归 **Fable5**，#13 实施者按现行 §10.1 class 规则派工，勿照本行〕**。§10 由 90 行/17 类增至 **95 行/18 类**；`owner-red` 64→**67**（Opus5 31→34 / DeepSeek Flash 33）· `device` 2→**3** · `static-guard` 2→**3** · `sol-blackbox` 22 不变。AC-10 与 §19 同步为 `INV-01..29` |

第 2 项值得单独记，因为它是**假闭合的标准配方**：`INV-29` 已经写进不变量表、§21 清单第 11 项还准备把 GitHub #6 的覆盖措辞改成 `INV-01..29`——若不先补台账行，就会得到「issue 宣称覆盖 29 条、ledger 只能证明 28 条」的状态。**宣称覆盖与能够证明覆盖是两件事**；机械同步文案会把前者伪装成后者。

一个作者侧的**半修复**，值得完整记录，因为它比单纯写错更有代表性：

本轮我最初把 `M-AC-05` 的 owner 写成 Opus5。自查时发现该行属于 `static-guard` 类、应归 Sol，于是**只改了 owner 列**，把入口留在 root `scripts/check-forbidden-boundaries.sh`。但按 §12.1，root `scripts/**` 恰恰是 **Opus5 的独占范围**；而 §10.1 明写 `static-guard` 位于 `acceptance/scripts/`，同类的 `M-BP-01/02` 也都锚在那里。**于是这次"修正"没有消除越界，只是把它从 owner 列挪到了 path 列**——而且我在上一版的教训记录里，还把 root `scripts/` 误称为「Sol 的独占文件」，等于把一条错误的所有权规则写进了教训本身。由非作者（Sol）发现。

收敛：**owner 与 exact entry 是一对，必须同时校验。** 改动台账任一列时，另一列必须回到 §12.1 的写入边界重新核一次；只改一列的"修正"通常不是修好了，而是把矛盾搬了个家。教训记录本身也要过一次事实核对——写错的教训会被后来者当规则用。

#### 0.1.15 evidence carrier 的实施归属与时序（v1.15）

Sol 第二遍审计的三项。第 1 项是本轮真正重要的：

| # | 问题 | 修订 |
|---|---|---|
| 1 | **`INV-29` 有台账行，却没有任何 Task 承接实现与时序。** §20 冻结「改名必须在 PR-1 完成」，但 Task 1 只有 import/provenance/ownership/CI；Task 4 的 `MIGRATION_4_5` 只处理**同 applicationId 内**的 Room v4→v5（INV-24），且按 PR 图晚于 contract freeze；Task 9 无回滚旅程 | Task 1 增 cutover 三门 **G-AC-1/2/3**（承接 `M-AC-01/02/04`）与对应 Files/RED；Task 9 增 `M-AC-03` old→new→回滚旅程。**三门 + 回滚任一不过即阻断 PR-1 cutover 合入；旧 App 在 `M-AC-03` 通过前不得移除** |
| 2 | `M-AC-03` 的 exact entry 用 `::M_AC_03`，而同类 device carrier 用 `#M-CO-06` / `#M-VS-01` | 统一为 `...md#M-AC-03`，并在 Task 9 列出锚点 |
| 3 | owner 替换数字：我写 52，Sol 量得 53 | **两个都对，量的是不同东西**：含 `Kimi` 的**行数** 52，`Kimi` 的**出现次数** 53（Task 6 的 PR 路由句里出现两次）。改为「52 行（53 处出现）」 |

第 1 项是**假闭合往上爬了一层**：上一轮是「不变量有规则、台账没行」，这一轮是「台账有行、计划没归属」。我补了载体却没补承接——**证据行本身不是证据，它只是一张待兑现的欠条；没有 Task 承接的台账行，和没有台账行的不变量，在验收面上是等价的。**

第 3 项值得单独记，因为它**不是错误**：一个没有声明单位的数字，天然会被两个人量成两个值，然后双方都以为对方错了。收敛不是选一边，是**写数字必须带单位**。本 session 反复出现的度量事故（长度过滤、`head` 截断、样本构成、坏 awk 报出的假 `diff=35`）都属同一族：**度量的定义没说清，或工具本身没被验证过。**

#### 0.1.16 v1.15 引入的三个语义缺陷（v1.16）

上一版为 `INV-29` 补实施归属时，一次引入三个新缺陷，**全部由 Sol 在 final readiness audit 发现**。三条互相关联，共同决定 INV-29 能否执行：

| # | 缺陷 | 修订 |
|---|---|---|
| 1 | **Task 1 的 Verify 必然失败。** Files 里要改 `apps/cellrebel-auto/**`（applicationId + 迁移文件），而最终 Verify 仍是 `--stage import`——该 stage 要求当前 app 树与上游逐字节相同 | 拆成两个 checkpoint：import commit 跑 `--stage import`（不可变锚点），Task 1 最终 HEAD 跑 `--stage contract`；CI workflow 同步（该文件属 PR-1 分支，本 doc 只做规定） |
| 2 | **`M-AC-03` 造成依赖环。** 我把 Task 9 真机回滚写成 PR-1 合入前置，而 §15 PR 图是 PR-1 → … → PR-6 device evidence、§16 是 I6 依赖 I1 —— I1 要先完成才能到 I6，I6 又要先完成才能合 I1 | `M-AC-03` 改为阻断**实际设备 cutover / 旧 App 移除 / release candidate**，不阻断 PR-1 代码合入。单测门（G-AC-1/2/3）才是合入前置 |
| 3 | **跨 applicationId 搬运通道物理不可行。** 我只在新 ID 侧写了 `AppIdMigrationBridge`。但 app-specific 存储按 applicationId 隔离，跨 App 只能走 `ContentProvider` 或显式共享通道；而已安装的旧 App 基线 `productFlavors = 0`、导出 `provider = 0`——**旧 sandbox 根本没有出口** | 冻结三段式：`legacyId` flavor（仍是旧 ID，经 SAF 导出 bundle）→ operator 保管 → `productId` flavor 导入并校验。**过渡构建必须先于改名交付**，两 flavor 同签名以便 `adb install -r` 覆盖而不清数据 |

第 3 条是本 session 最实质的一次设计错误：前面几次是**记账与传播**问题（数字、路径、owner、载体缺失），这一次是**方案在物理上不成立**——我设计了一座只有一端的桥，而它看起来完全合理，因为文档里"迁移桥"三个字读起来是完整的。

收敛：**凡涉及跨进程 / 跨 App / 跨设备边界的方案，先问"两端各自存在吗、谁先交付"，再写它的名字。** 一个组件的名字能掩盖它缺了另一半；只有把交付顺序摊开，缺口才会显形。

第 1、2 条则是同一类：我给一个 Task 加了新职责，却没有回头检查**它自己的验证命令**和**它在 DAG 里的位置**是否还成立。加职责必须连带重算这两样。

#### 0.1.17 scope split：cutover 实施设计移出本文（v1.17）

Sol 的 final readiness audit 对 `78587ac7` 判 `not_ready`（4 P1 + 1 P2），全部指向 DP-2 = B 的 cutover 载体。作者在动手补之前先做了一次归属测量：

```
05debb8b..78587ac 累计            +429 / −91（7 个 HEAD）
cutover 相关行 @78587ac           46
cutover 相关行 @05debb8b（双审版）  0
```

**四条 P1 全部落在这 46 行内**；而本文原有的四张全量映射自 `05debb8b` 起零 finding。也就是说，一份已被双路 APPROVE 的稳定决策记录，和一套 operator 拍板后才嫁接进来的新功能设计，共享了同一个 review identity——后者每补一轮就多一片新表面，新表面又招来下一条 finding，于是 2 小时内 7 次 HEAD 漂移，每次 push 作废刚拿到的 verdict。

**这不是执行力问题，是坐标系问题：我们在一个 review 循环里现场设计一个新功能。** 编排方（Sol）据此裁定拆分。

| 留在本文 | 移出到 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13) |
|---|---|
| 四张全量映射 · DP-1/2/3 决定记录（§21.0 逐字原文） · `come.xx.fakeaauto` 的冻结值与 §21.1 合法性核验 · DP-2 的成本论证与范围边界 · `INV-29` 作为 **deferred gate** | flavor（`legacyId`/`productId`） · 三段式 SAF 搬运通道 · canonical bundle schema/ordering/digest/原子性 · Task 1 的 `G-AC-1/2/3` · Task 9 的 `M-AC-03` 真机旅程 · §10 台账 `appid-cutover` 5 行 · variant CI/APK evidence · AR-1..5 |

随之回退的连带项，逐条列出以免又漏引用点：Task 1 不再修改 `apps/cellrebel-auto/**`，因此 Verify 回到**单** checkpoint `--stage import`（两 checkpoint 的拆分本就是为 cutover 分叉而设）；§10 由 95 行/18 类回到 **90 行/17 类**，`owner-red` 67→**64**（Opus5 34→**31** / DeepSeek Flash 33 不变）；AC-10 与 §19 由 `INV-01..29` 回到 `INV-01..28` **加一条显式 deferred 声明**——不是把 INV-29 删掉，而是如实说"本文不声称已覆盖它"。

**为什么 `INV-29` 留下而它的载体移走**：它是**对 operator 决定的安全约束**（改 ID 不得孤儿化用户状态），不是实施方案。删掉它，本文就变成"批准了改名、没有任何护栏"；整条搬走，`§20`/`§21` 又会引用一个不存在的门。按编排方给的判据——**归属两边的，本文留稳定 gate + issue 链接，完整规范只在新 PR**。

同一判据也解释了为什么 `come.xx.fakeaauto` 这个值留下而改名动作移走：**contract 需要的是那个字符串**（`PairingRecord` 主键含 applicationId），不是 cutover 的完成。值已冻结可用；动作被 gate 挡着。

> 教训与 §0.1.14 第 2 项同族但更上一层：那次是"不变量有规则、台账没行"，这次是**整个子系统的成熟度与它寄居的 PR 不匹配**。可操作判据：**当一个 PR 的 finding 连续多轮集中在同一块新增区域，而其余部分零 finding 时，那不是需要再补一轮，是需要拆。** 补的次数不会让新设计变成熟，只会让稳定部分陪着一起等。

#### 0.1.18 Sol 语义窄审 SR-1..6（v1.18）

`4e810f31` 上 GLM 机械/对抗角 APPROVE，Sol 语义角 `REQUEST_CHANGES` 命中 5×P1 + 1×P2。**六条全部成立，全部是 scope split 暴露或产生的执行面缺口**：

| # | 缺陷 | 修订 |
|---|---|---|
| SR-1 | 把「契约用冻结值」与「设备物理 mutation」混成一件事 → Task 1 不 mutation 与 §20「改名在 PR-1 完成」直接对撞；#13 未进 DAG；`INV-29` 泛化阻塞先行 PR | 拆两层：**契约层**（字面值 + 配对主键，PR-1，不碰 app 树）/ **设备层**（flavor/SAF/bundle/旧 App 移除，#13，挂 I6 release edge）。`INV-29` 只作用于设备层 |
| SR-2 | 把「第一次合法分叉」钉死在 #13，但 **Task 2 已要改两 App Gradle 与 Auto Manifest**，PR #11 分支实际早已偏离 | 规则改为「**第一个实际修改 app 树的 PR 原子移动 workflow 那一行**」，按当前 DAG 即 Task 2；更早者出现则由更早者移动，并在其 PR body 记录移动前后 stage |
| SR-3 | 要求 PR-5 聚合全 90 行，但 64 个 `owner-red` 行产生在平行 sibling PR，其 `exactHead` 不可能同时等于 PR-5 HEAD | 冻结聚合分工：**PR-5 证 26 行**（Sol 自有）/ PR-3、PR-4 各证自有 / **PR-6 在 integration exact HEAD 重跑聚合 90 行**〔当轮记录；**v1.20 起 26 行归 Fable5，v1.21 起 PR-5 不再证矩阵行**——PR-5 只自证 harness，26 行转 PR-6，见 Task 7 lane 表〕 |
| SR-4 | raw-green 清债是 operator 已定项，却没有合法 lane——owner matrix 禁止 Opus5 在 PR-3 后碰 qwy，§13/§15/§16 无 lint 节点 | 冻结 **PR-3.5 / Task 3.5 / I3.5**：exact 文件范围、时序（PR-3 后、PR-6 前）、gate（`lintDebug` exit 0），并写明这是 §12.1 的**唯一具名例外** |
| SR-5 | 顶部写 implementation baseline / `#3–#6` 解停，而 §21 清单自己标着 11–13 未完成、DP-3 不算落地 | 解停收敛为**条件式**：`#3` 需 `#2` + `#12` 均合入；DP-3 需与 #6/#7/PR body 原子同步后才算落地 |
| SR-6 | Task 7 保留拆分前计数（device=3/static=3/owner-red=67/Sol=28） | 改为 2/2/64/26；台账实算 `22+2+2+64=90` 复核一致 |

同轮把现行区 owner 从 DeepSeek Flash 改为 **Fable5**（52 行；§0.1.x 历史保留），review → Sol + GLM。

**SR-5 是最该记的一条**，因为它不是遗漏而是**结构性误导**：同一份文档里，下面的清单诚实标着「11–13 ⬜ 未完成，DP-3 不算全部落地」，顶部的告示却写着「implementation baseline，#3–#6 解除停止」。**读者先看到顶部。** 标了 ⬜ 不构成免责——**当摘要与细节冲突时，起作用的是摘要**，因为它在被先读到的位置。

SR-1/2/3 则共享一个形状：**我给某个对象加了新职责或新约束，却没有重新推导它所处的执行图**——Task 1 的 mutation 归属、stage 绑定的第一分叉、exact-HEAD 在平行 DAG 上的可满足性，三者都不会因为"写下了要求"而自动成立。

#### 0.1.19 把「已接受的规则」变成执行图上的边（v1.19）

上一轮我把 SR-1..5 的**结论**写进了文档，Sol 的增量复核指出：**结论写下了，执行图没有对应的边**——六条 P1 全部是同一件事的不同切面。

| # | 缺口 | 修订 |
|---|---|---|
| P1-1 | #13 只有散文，§15/§16/I6/§19 无结构边 | #13 进 PR 图与 issue 图，定义为 **release edge**：不阻断 PR-1..6 合入，但 `I6` 终门与 Epic close 必须等它闭合 |
| P1-2 | spec 仍让 Sol 开发 26 行 acceptance —— 2v2 下构成 self-review | acceptance 开发 → **Fable5**（GitHub #6 已如此）；§10.1 台账 26 行 owner 随之改；**全链 reviewer 统一 Sol + GLM** |
| P1-3 | 说了「第一个改 app 树的 PR 移动 workflow」，却没让 Task 2 真的去改 | Task 2 Files 加 workflow；Verify 加 `--stage contract` 必绿，**并加一条反向证据**：`--stage import` 必须 exit 1 —— 若它仍绿，说明分叉没发生、移动就是错的 |
| P1-4 | 分工写了，但 `verify-a-plus.sh` 仍强制 90 行同 HEAD，PR-5 无论怎么交都过不了 | 冻结 `--lane` 子集契约；**无 `--lane` 时默认最严的 `pr-6` 语义**，避免"忘传参就悄悄放宽" |
| P1-5 | Task 3.5 / PR-3.5 / I3.5 只在散文里 | 成为 PR 图与 issue 图上的真实节点，终结谓词 `lintDebug` exit 0 进 §19 |
| P1-6 | banner 条件化了，§20/§21 仍无条件解停；checklist 仍标 #6/#7 未同步 | §20 DP-3 行条件化；checklist 11/12 按 Sol 已同步的事实置 ✅ |

**这一轮的教训比上一轮更具体**：上一轮我记的是「加职责要重新推导执行图」。做完之后我以为做到了，但实际只做了一半——**我把规则写进了正文，却没有回到 §15/§16/§19 那几张图上去加边**。

规则与图的关系，跟不变量与台账行的关系是同构的（§0.1.15 记过一次）：**散文里的规则不是可执行的约束，图上的边才是。** 一条没有边的规则，和没有这条规则，在执行时等价。

这已经是同一族的第三次：`INV → 台账行` · `台账行 → Task 归属` · `Task 规则 → DAG 边`。每次我都补了下一层，然后停在那里，以为闭合了。**闭合的判据不是"我写下了"，而是"执行者按图走时会撞到它"。**

#### 0.1.20 责任改了，权限没改：把 2v2 传播到授权层（v1.20）

上一轮（v1.19）我把 acceptance 的 owner 从 Sol 改成 Fable5，改的是**台账 owner 列**与**reviewer 列**，并在 §0.1.19 写下"闭合的判据是执行者按图走时会撞到它"。

**然后我自己就没做到。** 本轮自查（在 Phase O 裁定要求的 `sol-blackbox` 处置项上）发现：`c4e97bd` 里 26 行的 owner 全部是 Fable5，而同一份文档里——

- §10.1 的 evidence class 表仍写 `sol-blackbox`/`static-guard`/`device` 三类**由 Sol 编写**；
- §12.1 owner matrix 仍把 `acceptance/**` 列为 **Sol 独占写入**，并把 `acceptance` 明确写在 **Fable5 的"禁止并行触碰"**列里。

也就是说：**Fable5 拥有 26 行，入口全部在 `acceptance/**`，而 owner matrix 禁止它写那个目录。** 执行者按图走时确实会撞到东西——撞到的不是约束，是**自相矛盾**。而 `check-forbidden-boundaries.sh` 正是按 owner matrix 执行静态阻断的，所以 Fable5 的第一笔合法 acceptance 提交会被本仓自己的 guard 判红。

| # | 缺口 | 修订 |
|---|---|---|
| A-1 | class 表"谁写"仍是 Sol，与台账 owner 列直接冲突 | 三类改 **Fable5**；并按 Phase O 裁定显式冻结 **legacy label → 执行者映射**：`sol-blackbox` 保留原字符串（22 个行 ID / #6 / verifier 解析的稳定 key，重命名会让历史证据失配），但**只表示触达方式，不表示 owner**；任何位置读到它都不得推导"Sol 写这一行" |
| A-2 | `acceptance/**` 仍是 Sol 独占写入，Fable5 被禁止触碰 | `acceptance/**`（含 `check-forbidden-boundaries.sh`）、`docs/acceptance/**` 转入 **Fable5 独占**；Sol 转为**无写入 lane 的 review-only**，与 GLM 同形 |
| A-3 | 并行成立条件仍按"三个个体三个目录"推导 | 重新推导为**两个个体三个目录**：Opus5 ↔ Fable5 仍可并行；但 **PR-3 与 PR-5 同属 Fable5，不再是跨个体并行**，必须分 worktree/分 PR，不得混提 |
| A-4 | Task 7 / Task 8 / §17 / §19 / §21 仍以 Sol 为执行者 | 全部改 Fable5；§17 子 Thread 由四条改五条（Sol 的写入 lane 撤销、审查 lane 独立成条） |
| A-5 | I3 单 reviewer `GLM`、I4 单 reviewer `Sol` | 按 [#4](https://github.com/TERRYYYC/fakexxx/issues/4)/[#5](https://github.com/TERRYYYC/fakexxx/issues/5) 的 T0 统一为 **Sol + GLM**。**I1 保持单 reviewer 不动**——[#2](https://github.com/TERRYYYC/fakexxx/issues/2) T0 至今如此，且 PR-1 已在该配置下取得 Sol 的 APPROVE；2v2 指派的是 #4/#6/#7 开发线，不追溯改写已完成的 review 配置 |
| A-6 | provider 与 acceptance 现在同属 Fable5，新增的失效模式没有对应审查动作 | Task 8 第 2 条加一条必须证伪的事：fake provider 是否迎合自家实现而非 public v1 contract；§10.1 显式记录 `owner-red` 中 33 行属**自审**，并写明它**不是终门**——终门是 `verify-a-plus.sh` 里 owner-independent 的机械覆盖校验 |

**这一轮的教训，是上一轮那条的下一层。**

上一轮我记的是「散文里的规则不是约束，图上的边才是」。这一轮暴露的是：**我改了"谁负责"，没改"谁被允许"。** 责任层（owner 列、reviewer 列）和授权层（owner matrix、目录归属、静态 guard 的输入）是同一个事实的两个投影，而我只更新了**我正在看的那一个**。

> **责任没有授权就不可执行。** 一条"X 负责这一行"的记录，如果 owner matrix 同时禁止 X 写那一行的入口，它不是一条弱约束——它是一条**已经坏掉**的约束，而且坏得比没写更隐蔽：owner 列看起来是绿的。

这是同一族的第四次（`INV → 台账行` · `台账行 → Task 归属` · `Task 规则 → DAG 边` · **`责任归属 → 写入授权`**）。前三次我的结论都是"下次记得多推一层"，四次之后这个结论本身该被否决了：**靠"记得多推一层"来闭合，是把一致性寄托在作者当轮的注意力上，而这份文档已经证明那不可靠。**

真正的判据是机械的：**owner 列、class 表"谁写"列、§12.1 owner matrix、目录树注释、`check-forbidden-boundaries.sh` 的输入，是同一事实的五个投影；任何两个不一致都应该由脚本报错，而不是由 reviewer 从 2500 行里读出来。** 本轮六条里有四条是纯机械可检的。该检查器按 Phase O 裁定走独立后续（不混入本 delta），但它的**判据在此冻结**：五个投影两两一致，不一致即 fail-closed。

#### 0.1.21 一个坏掉的定义，比一个缺失的定义更难发现（v1.21）

Sol 在 `9acd436d` 上给了 7 条 P1。**其中第 2 条是我上一轮亲手改过、并且自认为改完了的那一节。**

上一轮我把 acceptance 的授权层从 Sol 迁到 Fable5，改了 owner matrix、目录树、class 表、五个投影全部对齐，还写下「五投影两两一致即闭合」的判据。**但我没有回头看 owner 这个词本身的定义。**

§10.1 写着：

> owner 是该行的主责方——即"若该行失败，谁必须改代码"。

在 2v2 之前这是对的：Sol 写 acceptance、Sol 不改产品代码，这个定义从来没被激活过。**2v2 之后它坏了**——`M-RL-01` 由 Fable5 在 acceptance lane 证明 **Auto** 正确处置 typed error；它红了，要改代码的是 **Opus5**。于是 `evidenceOwner` 和 `fixOwner` 被塞进同一列，**失败无法路由**：Fable5 修不了，Opus5 不知道该自己修。

| # | 缺口 | 修订 |
|---|---|---|
| B-1 | owner 列同时承载 evidence author 与 product fix owner | 拆成 **`evidenceOwner`（进表，每行唯一）/ `fixOwner`（不进表，规则派生）**，冻结六条**失败路由表**（含两侧交互时先产定位证据、定位不能判定则 fail-closed 升级到 contract 层 → Opus5）〔**当轮记录，且括号内后半句已被 v1.23 推翻**：现行规则新增**第 0 条证据载体自身故障**且先于一切，第 5 条改为**保持红 + Sol·GLM 联合 triage、不得自动改 contract**。以 §10.1 现行路由表为准，**勿照本行执行**〕，并加两条硬约束：evidenceOwner 不得用 `not-testable`/`deferred` 绕过；路由必须留定位证据，不允许口头指认对端 |
| B-2 | DP-2 让 PR-1 交付 contract 与两侧 pairing 主键，但 Task 1 冻结为 import-only | 按真实载体拆**四段**：字面值 → PR-0.2 · contract/compat → PR-2 · `ProviderPairingRecord` → PR-3 / `PairingRecord` → PR-4 · 设备 mutation → #13。`INV-29` 只 gate 第四段〔**当轮记录，且第三段写反了**：v1.23 更正为 **`PairingRecord`→PR-3（千网游 caller allowlist）/ `ProviderPairingRecord`→PR-4（Auto provider allowlist）**，以 §6.5.3 与 §21 DP-2 为准，勿照本行执行〕 |
| B-3 | §15 `PR-6 → #13 → Epic`、§16 `I6 depends on #13`、§20「#13 不阻断 Task 9」三个方向互相否定 | 统一为：**PR-6 交付 pre-cutover 证据可独立合入**；**`I6` 与 `#13` 是 Epic close 这个 join 的两个 sibling 输入**，彼此无依赖边 |
| B-4 | `--lane` 只写「该 lane 自有行」，而 Fable5 同时拥有 PR-3 的 33 行与 PR-5 的 26 行，selector 不确定；且 PR-5 被要求在自己 HEAD 上证明依赖 sibling 产品代码的行 | selector 冻成 **`(class, evidenceOwner, 入口路径前缀)` 三元组** + verifier 自检划分（`pr-3 ∪ pr-4` ≠ 64 即 fail-closed）；**PR-5 只自证 harness，26 行的真实产品通过与 device evidence 转 PR-6 汇合 HEAD**；Task 3/4/7/9 的 Verify 全部显式传 `--lane` |
| B-5 | `Task 3.5` 只在 §21.2 散文里；且 Task 3 先要求 `lintDebug` 绿、清债又排在 PR-3 之后，互锁 | 建成 §13 真实节点（Files/RED/GREEN/Verify）；**Task 3 的 lint 门降为 ratchet**消环；授权路径从「被 lint error 命中的文件」改为**先冻结成 `docs/provenance/qwy-lint-baseline.md` 这份提交物**，并加越界断言；gate 补 unit + assemble |
| B-6 | Task 2 的三条 Verify 跑的是本地 checker，证明不了 CI 的 workflow 真的被改 | 加第四条机器断言：workflow 里每一个 `check-provenance.sh` 调用点都必须带 `--stage contract`。同仓已存在反例——PR #11 `eddf9729` 至今是裸调用，而 PR #10 已要求 `--stage` 必填 |
| B-7 | §21 checklist 第 13 项长期 ⬜，同时顶部宣称 frozen baseline | 改为**绑 HEAD 的可复验断言**（HEAD 相符 + 历史快照分节存在），每产生新 exact HEAD 重新核验，而不是等一个永远不打勾的动作 |

**这一轮的教训和前两轮不是同一条。**

§0.1.19 是「规则没变成图上的边」，§0.1.20 是「责任改了、权限没改」——两条都是**漏掉了一个位置**。这一轮不是漏，是**一个定义在坐标系变化后失效，而它长得和之前一模一样**。

> **缺失的定义会在第一次使用时炸；坏掉的定义不会——它继续返回一个值，只是那个值现在错了。** 我上一轮扫了五个投影，每一个都问「这里写的是谁」，没有一次问「owner 这个词现在还指什么」。**投影一致，不代表被投影的那个东西还成立。**

这也是为什么 B-1 的修法不是把 owner 列改成 `fixOwner` 或加第 91 列，而是**把「一个词承担两个角色」这件事本身拆掉，并为拆开后的第二个角色冻结一张可判定的路由表**。一个概念在坐标系变化后要么分裂、要么退休，**不能靠读者每次自行消歧**——那等于把定义的正确性外包给读者的注意力，而这份文档已经证明那不可靠（§0.1.20）。

对 §0.1.20 冻结的五投影判据，本轮补一条**前置条件**：**投影一致性检查的前提是「被投影的定义仍然成立」。** 因此 checker 除了比对五个投影，还必须在每次 owner/role 语义变更时，重新枚举所有**消费该定义的位置**（本轮是失败路由、lane selector、reviewer 链三处）。否则五个投影会整齐地一致地指向同一个错误答案。

#### 0.1.22 我给的理由是错的那个：GLM 证伪 A-6（v1.22）

上一轮我在 §0.1.20 的 A-6 里主动披露了一件事：`owner-red` 64 行中有 33 行的 code owner 就是 Fable5，因此 evidence audit 含自审。我写了披露，也写了「为什么可接受」：

> 可接受的唯一理由是它**不是**终门——终门是 `verify-a-plus.sh` 里 owner-independent 的机械覆盖校验。

我还特意在传球时请 reviewer **直接攻击这个判断**。GLM 攻下来了，而且攻在我完全没设防的地方：

> 机械门 ①②③ 校验的是「ID 集合相等」「覆盖绑 `status=passed` + `exactHead`」「未覆盖行分类」。**这三条都不校验「断言语义是否真对应预期终态」。** 而那恰恰是 evidence audit 在做的事——**机械门覆盖不到自审所校验的东西。**

**披露是对的，理由是错的。** 而且错得比没写理由更危险：「有个 owner-independent 的自动门兜底」听起来结实，后人据此**放松 PR-3 的代码 review 强度**，就会拆掉这 33 行**唯一**的独立检查。

| # | 缺口 | 修订 |
|---|---|---|
| C-1 | A-6 的理由不成立（机械门不覆盖被自审的东西） | 换成真实理由：**独立性完全来自 PR 阶段的跨个体 review**——33 行测试代码随 PR-3 由 Sol + GLM 审，audit 产物再由二者复核。并加硬约束：**不得因 CI 全绿而放松 PR-3 代码 review** |
| C-2 | §8.4 冻结 `state` 的论证把风险归因于「两个不同作者」，2v2 后前提消失 | 归因改为**与作者数无关**：风险来自「同一份契约有两条独立实现路径」。作者身份只改变发现难度，且方向相反——**同一人写两条路径时更容易一起漂移并互相自洽**，故 2v2 下这条冻结更吃重 |
| C-3 | 历史区两处记录「待执行行的 owner」，实施者照读会派错工 | 不改写历史，加内联标记：§0.1.14 的 `device`/`static-guard` = Sol、§0.1.18 的「PR-5 证 26 行（Sol 自有）」各补一句现行归属 |
| C-4 | §16 Owner 列的「Sol 主控」可被读成写入所有权 | 标注 **coordination / review only，无写入 lane** |

**这一轮暴露的是我那个判据本身的两个洞。**

**第一个洞：我数漏了一个投影。** §0.1.20 冻结的五投影是 owner 列 / class 表「谁写」/ owner matrix / 目录树 / guard 输入——**全是结构化字段**。但 §8.4 那句话不是字段，是**承重的论证散文**：它是「为什么必须冻结 `state`」的理由本身。

> §0.1.19 我记的是「散文里的规则不是约束，图上的边才是」。**这条有一个例外，而我把例外当成了通则：当散文本身是某条冻结的理由时，它的归因腐烂，论证就跟着腐烂。** 承重的论证是第六个投影。

**第二个洞：我把历史区整片豁免了。** 自查器刻意跳过 §0.1.x，理由是「改历史即伪造」。这个理由对**已完成事项**成立，但历史条目里还记着**尚未执行的行的 owner**（`M-AC-04/05` 的 class 归属、PR-5 的 26 行）——那些是**现在仍会被照着执行的指令**，因而同样是同一事实的投影。**判据补一条：历史区中凡记录待执行工作归属的条目，不得豁免；处置是加内联标记，不是改写。**

连起来看，这三轮是同一个东西越剥越深：

- §0.1.19 规则没变成边 → **漏了一个位置**
- §0.1.20 责任改了权限没改 → **漏了一层**
- §0.1.21 owner 的定义在坐标系变化后失效 → **一个定义坏了**
- §0.1.22 **我为「已知风险可接受」给出的理由本身是错的** → 不是漏、不是坏，是**我自己构造了一个不成立的兜底**

最后这个最难自查：前三种都能靠枚举发现，**这一种只能靠别人来打**。我做对的一件事是把它写出来并点名请人攻击；做错的是把「听起来结实」当成了「验证过」。**主动披露不等于论证成立——披露只是把靶子摆出来，理由仍然要各自承重。**

#### 0.1.23 我一直在交付「指向物的描述」，而不是指向物本身（v1.23）

Sol 连审两个 HEAD，给出 4 P1 + 4 P2。把它们并排看，形状是同一个。

| # | 缺口 | 修订 |
|---|---|---|
| D-1 | v1.21 的四段拆分把**两张 pairing 表写反**：`ProviderPairingRecord`→qwy / `PairingRecord`→Auto，与 §6.5.3、§7.1、§8.2、Task 3/4 全部相反 | 更正为 **`PairingRecord`（caller allowlist）→ PR-3 千网游 / `ProviderPairingRecord`（provider allowlist）→ PR-4 Auto**；并写明 `come.xx.fakeaauto` 是 **Auto 的** applicationId，故作为 **caller key 出现在千网游的表里**；且**真实配对记录只在新 ID bind + operator 批准后产生**，②③ 段都不产生已批准记录 |
| D-2 | 失败路由表把「跨端无法定位」自动判为 contract 欠定义并交 Opus5 | 新增**第 0 条且先于一切**：证据载体自身故障（断言写错 / harness bug / fixture 损坏 / device 环境异常 / manifest 生成错）→ `evidenceOwner` 修证据。第 5 条改为**保持红 + Sol/GLM 联合 triage**，只有 triage 明确判定后才动 contract |
| D-3 | PR-5 只列了源码与脚本，**没有构建入口**，而 verifier 已要求 `cd acceptance && ./gradlew test` | 补 `settings.gradle.kts` / 独立 wrapper / 两个 module 的 build 文件 / fixtures / 三个 self-test 载体，并冻结 exact 测试命令；同时写明 `device` 类 2 行的 markdown **不是可执行测试**，PR-5 只交模板 |
| D-4 | Task 3.5 声明授权集合是「baseline ∪ `values-en/strings.xml`」，guard 却只 grep baseline | 三条一起实现声明的集合：**(a)** `src/main/**` prefix、**(b)** `integration/**` 绝对排除、**(c)** baseline 成员资格。(a)(b) 是不受 baseline 影响的外层围栏，同时封住「作者往 baseline 多写几行就给自己扩权」 |
| D-5..8（P2） | RED 三处混成一句、版本表 `1.20→1.22→1.21`、§21.2「插在 PR-3 与 PR-4 之间」的线性误导、**Task 8 第 2 条的虚假引用** | 逐条修；最后一条选择**把义务真正写进 Task 8 第 3 条**，而不是撤回引用 |

**四条 P1 是同一个形状：我交付了「指向物的描述」，而指向物不存在、或与描述不符。**

- D-1：写下了四段载体的**形状**，没有回去核对两个 record 各自到底住在哪一侧。
- D-3：列出了要写哪些**源文件**，没有给出让它们能跑起来的**构建载体**。
- D-4：声明了授权集合，**guard 实现的是另一个更窄的集合**——窄到会拒绝那唯一一个合法新增文件。
- D-8：写了「Task 8 第 2 条已加针对性义务」，**而第 2 条里没有这句话**。

> **描述与指向物的差别，机械检查看不出来。** 「`ProviderPairingRecord` → PR-3」语法完好、术语正确、位置正确；「每个 diff path 必须在 baseline 中」是一条能跑的命令；「Task 8 第 2 条已加」是一句通顺的引用。**它们全都形式正确，只是所指为空或所指相反。** 我这轮跑的七个投影全部 CLEAN，一条都没抓到——因为投影比对的是**描述之间**是否一致，不是描述与**被描述物**是否一致。

**D-8 尤其该记：它和 §0.1.22 是同一个错，而且就犯在修 §0.1.22 的那次提交里。** 上一轮我刚记下「我为已知风险构造了一个不成立的兜底」，紧接着在替换那个兜底时，又引用了一条并不存在的复核义务当作新理由。**同一个病，在写下它的病历的同一页复发。**

所以判据再补一条，它和前面七条不同类——前七条是**内部一致性**，这条是**指称有效性**：

> **凡引用一个具体载体（文件 / 命令 / 条款编号 / 表名 / 字段），必须能当场打开它并确认所指为真。** 描述与指向物之间没有任何投影能替你检查；**唯一的验证方式是去看那个东西本身**。写「Task 8 第 2 条已加」之前，打开 Task 8 第 2 条读一遍；写「`ProviderPairingRecord` 归 qwy」之前，打开 §6.5.3 读一遍。

这也解释了为什么这几轮的缺陷计数下不去：**我在提高描述层的自洽度，而缺陷一直在描述与被描述物之间的缝里。** 投影检查器再多加几条也抓不到——它们全都活在描述层。

#### 0.1.24 我把规则用在了眼前那一条，没用在它旁边那一条（v1.24）

Sol 复核 `59db6201`：**5 项闭合，余 3 P1**。三条并排看，是同一件事的三次。

| # | 缺口 | 修订 |
|---|---|---|
| E-1 | §0.1.21 的 **B-1** 仍以「冻结失败路由表」的口吻写着「定位不能判定 ⇒ contract 层 → Opus5」，没有内联标记 | 补 `勿照本行执行` 标记，指向现行第 0 条与第 5 条 |
| E-2 | PR-5 的 exact 入口是 `./gradlew test`，而 22 个矩阵测试也在默认 `src/test`——**一个聚合 task 不可能既跑全部又「0 矩阵行」**；`run-guard-selftest.sh` 被列为通过条件却无人调用 | 拆 **`selfTest` / `matrixTest` 双 source set**，`selfTest dependsOn guardSelfTest`，两个 lane 各自 exact task；**台账 22 行的「精确入口」列同步改到 `src/matrixTest/`** |
| E-3 | Task 3.5 guard：`grep -qF` 是子串命中不是整行相等；baseline 与源码同 PR 可改，加一条 `src/main/**` 路径即三层全绿；上游失败与 0 检查不判红 | 五层重写：`set -euo pipefail` · **digest 绑回原始 lint 报告** · 条数 == 23 且每条必须在报告中存在 · `grep -qxF` 整行相等 · 空检查判红 |

**E-1 最难看：我在 522 行亲手冻结了「历史区待执行指令不得整片豁免，须内联标记」，然后在 479/480 那张表里，给 B-2 加了标记，没给 B-1 加。两行相邻。**

**E-3 是 §0.1.23 的原样重演。** 上一轮我刚写下判据——「凡引用具体载体，必须能当场打开确认所指为真」「把断言绑到指向物，不是绑到作者的声明」——并据此把 Task 8 那条虚假引用变成了真的。**同一轮里，我却让 baseline 集合继续绑在作者的声明上**：那份 baseline 是本 PR 自己提交的文件，我还写下了「(a)(b) 封住了往 baseline 加行扩权」这句**事后被证明为假**的断言。判据我写对了，**只用在了当时正看着的那一处**。

> 这就是 §0.1.19 到本节的真正主线，比我此前每一次的归纳都更朴素：**每一轮我都把新学到的规则用在触发它的那个实例上，然后停下。旁边那个同类实例，要等下一轮由别人指出来。**
>
> 所以问题不在于规则不够多——六轮下来判据已有八条。**问题在于一条规则被冻结的那一刻，它的适用面没有被枚举。** 一条只应用于自身触发点的规则，和一条没有写下的规则，在下一个实例上等价。

**因此补第九条判据，它约束的是「怎么冻结判据」本身：**

> **冻结任何一条判据时，必须在同一次提交内枚举它当前的全部适用点并逐一处置**（修 / 标记 / 显式声明不适用及理由）。**枚举结果要写进该判据旁边，成为它的一部分。** 做不到穷举时，写明搜索式与已覆盖范围——**「我改了触发它的那处」不构成闭合。**

作为立即兑现：本轮把第八条（指称有效性）的适用面枚举了一遍——Task 8 引用、baseline 集合、PR-5 入口命令、台账 22 行入口路径、Task 3.5 的报告 digest，共 5 类，**全部处置完毕并在本轮 Verify 中机械复核**。E-3 正是这次枚举查出来的第二处。

**诚实记一笔趋势**：本轮 Sol 一次闭合 5 项、余 3；上一轮 4 P1 + 4 P2；再上一轮 7 P1。**缺陷计数确实在降**，但降的原因不是我变仔细了，而是**可执行契约的表面正在被逐条钉死**——从散文到图、到权限、到定义、到指称、再到判据的适用面。剩下的洞越来越靠近"必须真的去跑一遍才知道"的那一层。

#### 0.1.25 「看起来像绑定」不是绑定（v1.25）

Sol 判 P1-2、P1-3 **CLOSED**，只剩 P1-4——同一个 guard，我在这里**连错三版**。

| 版本 | 我写的"绑定" | 为什么假 |
|---|---|---|
| v1.23 | prefix + `integration/**` 排除「封住往 baseline 加行扩权」 | 这两层只约束路径的**形状**；扩权发生在**成员资格**上。加一条任意 `src/main/**` 非-integration 路径，全过 |
| v1.24 | 「条数 == 23 + 每条要在报告里搜得到」 | **23 是 Error instance 数，不是文件数**（唯一文件只有 5 个）；`grep` 搜整个 XML 不区分 severity。**把 warning-only 文件重复填满 23 行，三道判定全过**——不改报告、不改 digest 即扩权 |
| v1.25 | 集合**从报告派生**，baseline 声明副本必须与之**逐元素相等** | 手加一行即破坏集合相等 |

另两条同轮修：**① digest 不可重放**——lint XML 的 `location/@file` 带绝对 checkout 路径，换个目录 SHA-256 就从 `731feb…` 变 `9111c3…`，所以我写的「别人在冻结 commit 重跑得到同一 digest」根本不成立；现改为**先规范化为 repo-relative 再提交与摘要**，并要求 baseline 声明生成 commit。**② `set -euo pipefail` 位置错**——它原本排在 lint / unit / assemble / provenance / boundary / verifier **六道门之后**，那六道失败仍会被后面的成功掩成绿（最小复现 `false; set -euo pipefail; true` 退出码 0）。现在它是整个 Verify 的第一条可执行命令。

**三版的共同毛病，可以说得比前几节更准：**

> **我一直在写「拿指向物去印证被检查方的声明」，而不是「从指向物派生出被检查的集合」。** 两者长得几乎一样——都会打开那份报告、都会做一次比对、都能跑通——但**方向相反**：前者只要能在指向物里搜到任意子串就放行，后者手改一个元素就破坏相等。**§0.1.23 说「把断言绑到指向物」，我照做了三次，三次都做成了印证。**

判据补一条**方向性**，作为第八条的细化：

> **绑定必须是「派生 → 比较」，不能是「声明 → 印证」。** 判别法：问「如果被检查方往自己的声明里多加一个元素，这条检查会不会红？」——**会红才是绑定；只是搜不到才红，那是印证。**

**按第九条判据，同轮枚举「方向性」的全部适用点**（凡"被检查方产出一份声明、由 gate 校验"的地方）：

| # | 位置 | 方向 | 处置 |
|---|---|---|---|
| 1 | Task 3.5 授权路径集 | ~~声明→印证~~ → **派生→比较** | 本轮修 |
| 2 | §10.1 evidence manifest 的 `status=passed` 行 | **派生→比较**（已成立） | 无需改：`reportDigest` 必须定位到字节一致的原始报告，且 `testId` 必须在该报告中出现且 outcome 一致——伪造一行 passed 会因报告里查无此结果而红 |
| 3 | `check-provenance.sh` 的 tree digest | **派生→比较**（已成立） | 无需改：digest 从 `git fetch` 到的上游对象现算，不读仓内声明 |
| 4 | lane selector 三元组 | **派生→比较**（已成立） | 无需改：行集合从 §10.1 台账现算，verifier 另有 `pr-3 ∪ pr-4 == 64` 自检 |
| 5 | Task 3.5 的 `ALLOWED_EXTRA` | 单个具名常量，非集合 | 不适用：只有一条且写死在 gate 内，被检查方改不动 |

第 2 条值得说明为何**已经**成立：它满足方向判别法——作者往 manifest 多加一条 `passed`，`testId` 在原始报告里查不到匹配 outcome，会红。**这条是 v1.11 那轮 Sol 逼出来的**，当时的措辞就已经是派生式；本轮只是回头确认它没有退化。

**这一节和 §0.1.24 是配套的**：§0.1.24 说判据冻结时要枚举适用面，而这三版说明**即使枚举了，如果判据本身的方向是错的，枚举出来的每一处都会一起错**。本轮枚举的 5 类适用点里，baseline 这一处正是被枚举到、也被"修"过、但方向仍然错着的那一处。

#### 0.1.26 一条在「东西不存在」时会变绿的检查（v1.26）

Sol 判 P1-4 的前两条 CLOSED（派生式授权集合、规范化 + strict flags + `mktemp -d`），余 **1 P1 + 1 P2**。

**P1 打中的是我自己写的第八条判据。** 我在 guard 里写：

```
FROZEN=$(sed -n 's/^qianwangyou-upstream-sha: *//p' docs/provenance/upstream-imports.md)
```

**这个字段根本不存在。** PR #10 exact HEAD `aa6d93a3` 的那份文件只有一张 Markdown 导入表，真实 SHA 在表格单元与 `check-provenance.sh` 的常量里（我这轮真的去打开了才知道——上一轮我引用它时**没有打开过**）。

而真正糟糕的不是引用错，是**这条检查在操作数缺失时会变绿**：

| baseline 声明 | `FROZEN`（空） | 结果 |
|---|---|---|
| 缺失 / 空 | 空 | `空 == 空` → **exit 0，绿** |
| 合法 40-hex | 空 | 不等 → **exit 1，红** |

**缺声明反而通过，写对了反而失败。绑定被整个倒过来了。**

| # | 缺口 | 修订 |
|---|---|---|
| F-1 | carrier 不存在，且空值自洽 | 改绑 **`check-provenance.sh` 的 `IMPORTS` 表第 4 字段**——它是 provenance gate 自己的真相源、PR #10 已用它反向校验文档、真实存在且 40-hex，**且 #10 无需改动**；再用 git 派生的 `git-subtree-split:` trailer 做独立第二记录。所有比较型断言先证两侧**非空、单值、格式合法** |
| F-2 | `sort -u` 先于比较，「5 条正确 + 1 条重复」放行 | 先查 raw 行数 == 5 与无重复，再做集合相等 |
| F-3 | 两处仍写「23 条 exact 路径」 | 改为「23 是 Error instance 数；授权集合是派生出的 5 条唯一路径」 |

**一条比较型断言，有三件互相独立的事会坏：**

1. **方向**——派生还是印证（§0.1.25）
2. **指称**——carrier 到底存不存在（§0.1.23）
3. **退化输入**——两侧为空 / 缺失时，它到底红还是绿（**本节新增**）

我前两轮把 1 和 2 各写了一遍判据，**第 3 件从来没检查过**。而它最阴险：前两件出错时检查往往会红，容易被发现；**第 3 件出错时检查会绿**——它不是漏检，是**主动把"东西不存在"认证成"检查通过"**。

> **判据补第十条**：**任何比较型断言，必须先证明两侧操作数存在、单值、格式合法，再做比较。** 判别法：**把任意一侧删空，这条检查必须红。** 如果删空之后它变绿，那它检查的不是事实，而是"我没找到反例"。

**按第九条同轮枚举第十条的适用点**（本文所有比较型断言）：

| 断言 | 删空一侧 | 处置 |
|---|---|---|
| `generated-at-commit` vs `IMPORTS` SHA | 原为**绿** | 本轮修：两侧证非空 + 40-hex + 单值 |
| `report-sha256` vs 实算 digest | 一侧恒非空故原为红，但依赖巧合 | 本轮补：两侧证非空 + 64-hex + 单值 |
| 派生集合 vs 声明集合 | 声明空 → raw count != 5 → 红 | 已成立（F-2 同轮补 raw/重复检查） |
| Error instance 数 == 23 / 唯一数 == 5 | 空 → 0 != 23 → 红 | 已成立 |
| `--stage contract` workflow 断言 | 调用点为 0 → `grep -c` 为 0 → 红 | 已成立 |
| §10 ID 集合 == §10.1 ID 集合 | 任一侧空 → 集合不等 → 红 | 已成立 |

**这三节连起来（§0.1.24 枚举、§0.1.25 方向、§0.1.26 退化输入）指向同一件事**：我写检查时想的是「它怎样才能抓住我预想的那个错」，而不是「它在各种输入下分别输出什么」。**前者产出的是示例，后者才是谓词。**

#### 0.1.27 我上一轮加固的那条检查，把合法状态判成了非法（v1.27）

上一版为了摆脱"东西不存在就变绿"，给 trailer 交叉校验加了 `set -euo pipefail` 与严格断言。Sol 实测发现它翻到了反面：

```
git log --all --format=%B | grep -qxF "git-subtree-split: $FROZEN"
→ rc=141   PIPESTATUS=(141 0)
```

`grep -q` 的语义是**命中即退出**。它一退出，管道读端关闭，上游 `git log` 写入时收到 `SIGPIPE` 而以 141 终止；`pipefail` 取管道中最后一个非零值，于是**整条命令在 trailer 真实存在时返回 141**。同一份历史里 `grep -cxF` 数出来是 `1` ——**东西就在那儿，检查却说没有。**

这比上一轮那条"缺失即绿"更危险：缺失变绿至少还需要有人真的把字段删掉；而这条是**正确状态必红**，等于把 Task 3.5 的 Verify 变成一道谁也过不去的门。合规的人被挡在外面，是最容易被"那就先跳过这道检查吧"绕过去的形态。

同轮另两条也成立，都是"绑定看起来在，实际没绑住"：

| 条目 | 问题 | 修法 |
|---|---|---|
| P2 · `--all` | trailer 只要在**任意** ref 上存在就算数，哪怕那条 ref 与本次交付的历史无关；也没要求 split SHA 与 `git-subtree-dir: apps/qianwangyou` 出现在同一 commit——碰巧同值的另一次 split 也能放行 | 改绑 `HEAD`（当前历史可达）+ `--all-match` 两个 trailer **同 commit** |
| P2 · `awk` | 扫的是**整份脚本文本**，而 `check-provenance.sh` 的循环只消费 `$IMPORTS` 变量。赋值块内删掉 qwy 行、块外（usage 文本 / heredoc / 注释）留一条同形 decoy，本检查提取到 decoy 的 40-hex，真实消费者读到空——**两个消费者各自自洽为绿** | 先用 range 模式把解析限制在 `IMPORTS="` … `"` 赋值块内 |

三条都已实测闭合，正负例见 Verify 段。

> **教训（这是同族的第三次，前两次是 §0.1.25 的"看起来像绑定"和 §0.1.26 的"不存在即绿"）**：
> 前两次我记的是"断言要绑到真的指向物"。这次的形态不同——**指向物是真的，绑定也是真的，坏的是我用来读它的那个工具的退出语义**。
>
> 收敛判据因此要加一条：**新写的检查器必须同时跑正例与负例**。只跑负例（"缺了会不会红"）证明不了它在合法状态下会绿；而一道"永远红"的门和一道"永远绿"的门，都不是门。上一轮我为投影 6/7 的 checker 做过一次自我证伪并抓到误报，这轮却没对这条 shell 断言做同样的事——**证伪纪律不能只用在我当时正盯着的那个检查上。**

#### 0.1.28 我把「加分证据」做成了硬门（v1.28）

上一版把 `git-subtree-split` trailer 当成承重的第二记录。Sol 指出它有一个我没算到的失效路径：**operator 用 squash 或 rebase 合并 PR #10 是完全合法的操作，而这两种方式都会丢掉 exact import commit 与 trailer 祖先。** 那之后从 main 做 fresh clone，`check-provenance.sh` 与 Task 3.5 会同时变红——**不是因为出了问题，而是因为有人用了 GitHub 上那个按钮。**

这和上一轮的 SIGPIPE 是**同一个错误换了触发者**：都是把一条合法状态判成失败。上一轮的触发者是 `grep -q` 的退出语义，这一轮是合入方式。我刚在 §0.1.27 写下"永远红的门不是门"，紧接着又造了一道——只是这次它要等到 merge 之后才红，所以我当时没看见。

**修法不是去冻结 merge 方式**（那是拿流程约束补技术缺陷，而且靠人记住）。而是换一个**与合入方式无关**的承重记录：

| | 载体 | 跨 squash | 角色 |
|---|---|---|---|
| 旧 | `git-subtree-split` trailer | ❌ DAG 被丢弃 | 曾是硬门 |
| **新** | **`qwy-subtree-tree`**（`git rev-parse HEAD:apps/qianwangyou`） | ✅ **squash 保留最终 tree** | ~~承重~~ **← 已被 v1.29 推翻并删除：它取错时间截面（baseline 先于源码修改提交，最终态必不相等）。现行方案见 §0.1.29** |
| 保留 | trailer | ❌ | **加分项**：仅当 import commit 仍可达时断言，可达即必须真成对 |

选 tree 的理由是它由**内容**决定而不是由**历史形状**决定。provenance 要证的本来就是"这棵树等于上游那棵树"，那是内容命题；用 DAG 去承载内容命题，等于让结论依赖一个与它无关的变量。

同轮另三条也成立，都在我这次新写的论证里：

| 条目 | 问题 | 实测 |
|---|---|---|
| `--grep` 不是 trailer | 匹配的是 commit message **任意位置**，一段普通散文提到那两个字符串即可 | 构造散文 commit → `PAIRED=1`（假绿），而 `interpret-trailers --parse` 为 **0 行**。改为 `--grep` 只缩候选集、判定交给 trailer 解析 |
| range awk 不同源 | 它把文件里**所有** `IMPORTS="…"` 块拼起来，Bash 却只用**最后一次赋值** | 追加第二个只含 cellrebel 的块 → 本解析仍返回合法 `285e…`（格式/单值全过），真实 consumer 的 qwy 为**空** → 分叉。加"恰好一次赋值 + canonical 块形式"两道断言，同时封住 one-line 与 single-quote 再赋值 |
| 修订正文顺序 | headings 成了 `0.1.25 → 0.1.27 → 0.1.26` | 已归位。这是 §0.1.22 抓过的同类回归——我插新节时只看了版本表有序，没看正文 |

还有一条不是缺陷而是**言行不符**：§0.1.27 里我写"正负例见 Verify 段"，但 Verify 里只有生产态正例。**手测写在提交消息里不构成可重放证据**——后来者执行的是 Verify，不是我的 commit body。现已落地为 `acceptance/scripts/selftest-task35-guard.sh` 的 7 负例 + 1 正例。**← 该路径已被 v1.29 更正为 `scripts/selftest-task35-guard.sh`：`acceptance/**` 是 Fable5 独占，本 task owner 是 Opus5，原写法是一条永远执行不到的指令。**

> 教训：**"我验证过"和"它被写进了会被重跑的地方"是两件事。** 前者随作者的上下文一起消失；只有后者能挡住下一个人。这与 §0.1.14 的"证据行本身不是证据，只是一张待兑现的欠条"同族——那次欠的是台账行，这次欠的是可执行负例。

#### 0.1.29 三条 P1 是同一个错误的三个面（v1.29）

Sol 在 `be0902ea` 判 3 P1 + 2 P2。逐条看像五个独立缺陷，放在一起看是**同一个设计错误**：**我一直在用历史形状（commit、DAG、某一时刻的 tree）去承载一个内容命题。**

provenance 要证的是"这棵树等于上游那棵树"。这是关于**内容**的断言，而我三次把它绑到了会被合法操作改变的**历史**上：

| # | 我绑的东西 | 什么合法操作会毁掉它 |
|---|---|---|
| P1-1 | `check-provenance.sh` 硬要求 import commit 可解析 | operator 按 squash / rebase |
| P1-2 | pristine 时刻的 `qwy-subtree-tree` | 本 task 自己修改 qwy 源码（**必然发生**） |
| 上一版 | `git-subtree-split` trailer | 同 P1-1 |

**P1-1 尤其说明我上一轮的修补是无效的**：我把 trailer 降成了加分项，可 Task 3.5 的 Verify **第一条**就跑 `check-provenance.sh --stage contract`，而那道 gate 仍硬要求 import commit 存在——我的降级逻辑在它之外，根本轮不到执行。**在一道必红的门后面写宽容逻辑，不会让门变宽。**

真·丢历史的 fresh clone 实测（`git archive aa6d93a` → 新仓单 commit，两个 import commit 真正 ABSENT）：

```
旧 checker --stage contract : rc=1  (recorded import commit does not exist)
新 checker --stage contract : rc=0  (DAG evidence skipped, content proof still applies)
新 checker --stage import   : rc=0  (严格性未放松，app 树仍 pristine)
```

**修法统一为内容锚**：fetched upstream `${FROZEN}^{tree}` + **分叉受限**（当前树相对 upstream 的差异必须落在已声明的授权集合内）。它不依赖任何本地历史，因此跨任何 merge 方式存活；且它在 pristine 与最终态**都成立**，从根上消掉了 P1-2 的时间截面问题——因为不变量不再是"相等"，而是"差异有界"。

P1-3 是另一类：`selftest` 我指到了 `acceptance/**`，而按 §12.1 那是 Fable5 独占、Sol 只有 review 权，Task 3.5 的 owner 是 Opus5，且 `I3.5` 只依赖 `I3`，不能假定 sibling PR-5 交付过任何文件。**给自己的产物挑一个别人独占的目录，等于写了一条永远执行不到的指令。** 已移入 Opus5 独占的 root `scripts/`，且 guard 与 selftest **共用同一实现**——两份实现会各自漂移，负例就不再证明生产 guard 的行为。

P2-1 的 `printf -v IMPORTS` / `export IMPORTS=` 让我最终承认：**任何"解析别人的源码文本"都不可能真正同源**，因为文本与 runtime 是两条独立演化的路径。PR #10 因此新增 `--print-import` 机器查询，从循环实际消费的同一个 `$IMPORTS` 打印。

> **教训**：P2-2 值得单记，因为它是本 session 第四次同型。上一轮我明确写了"只修点名那处就是补锅匠，所以做了全量扫描"——**而那次扫描本身是窄的**：正则写成 `当前 HEAD \`[0-9a-f]{40}\``，只覆盖全 SHA，三处 8 位短 SHA 全部逃逸。
>
> 根因是我一直在**扫"断言 + 值"的组合形态**，而我要保证的性质是"任何自称当前 HEAD 的文本都必须等于当前 HEAD"——性质只关于**断言短语**，值的形态是自由的。改法：先枚举断言短语的**全部**出现（`当前 HEAD` 共 6 处），再逐处检查其值，而不是让 pattern 同时承担"找到"和"判定"两件事。**让查询形态决定结论范围，就是这个病本身。**

#### 0.1.30 修错了方向的检查，会冲到相反的错（v1.30）

Sol 判 3 P1 + 2 P2，其中 P1-1、P2-1 落在耦合的 PR #10。

**P1-1 是一个循环论证。** section 1 在 import commit 不可达时说「内容证明在 section 2 还成立」，section 2 在树分叉时说「基线由 section 1 的 import commit 证明」。**DAG 一丢，两边互相指望，谁都没证。** 我按 Sol 给的复现跑了一遍：fresh single-commit（两个 import commit 真 ABSENT）+ 篡改 `apps/qianwangyou/app/build.gradle` → `rc=0`、`PASS (all checks)`。

修法是把锚点从**历史形状**换成**内容**：doc 记录的 upstream root tree 必须等于从上游实际 fetch 到的对象的树。它在每个 stage 都成立、不依赖任何本地历史、跨 squash/rebase 存活。import commit 降为可达时才断言的加分证据。

**但真正该记的是这三轮的形状。** 同一道门，我连改三次，每次都冲到上一次的反面：

| 版本 | 症状 | 我的修法 | 结果 |
|---|---|---|---|
| v1.28 前 | import commit 不可达 → **必红**（squash 合入后 CI 永久红） | 不可达时直接 PASS | **变成永远绿**：任意篡改都过 |
| v1.29 | 永远绿 | 拿 upstream 树与最终 HEAD 全量比较，要求差异落在 5 文件集合内 | **又变成永远红**：Task 2/3 的合法改动必然在集合外 |
| v1.30 | —— | 先问「这道门到底回答哪个问题」 | 三个问题拆三个载体 |

> **我一直在修症状的方向，而不是先确定这道门该回答哪个问题。** 「永远红」的反面不是「永远绿」，是**问对问题**。一个差异不可能同时回答"基线从哪来""前序改了什么""本 task 改了什么"——硬要它回答三个，就只能在两种失败模式之间来回摆。

Sol 给的拆法是解药，冻结如下：

| 问题 | 载体 | 时间截面 | 归谁 |
|---|---|---|---|
| 基线从哪来 | fetched upstream **root tree** | 与历史无关，任何时刻 | PR #10 `check-provenance.sh` |
| 前序 feature 改了什么 | —— | Task 2/3 的 PR | **不归任何 provenance/scope gate** |
| 本 task 改了什么 | `git merge-base origin/main HEAD` 之后的 delta | 本 PR 自己 | Task 3.5 门 3 |

另外三条：

- **P1-2 的第二半**：分叉循环用的 `$TMP/derived` 在下面才生成，文件不存在时 `grep` 返回 2 → 5 个授权文件也全部 exit 1。**先用后建**，一条纯顺序错误，但后果和逻辑错误一样是永远红。
- **P1-3**：Task 3.5 的 Verify 调用 `acceptance/scripts/check-forbidden-boundaries.sh`，而它由 sibling Task 7/I5（Fable5）交付，`I3.5` 只依赖 `I3` —— 按 DAG 本 task 可以先跑，届时文件不存在；而 `acceptance/**` 是 Fable5 独占，本 task owner 是 Opus5 也无权补。**一条永远执行不到的指令不是门。** 这与 §0.1.28 里我给自己的负例脚本挑了别人独占目录，是同一个错误。
- **P2-1**：`--print-import` 与生产循环**共享变量名，不共享代码路径**。在 handler 之后、循环之前注入 `printf -v IMPORTS`，query 报原值、gate 消费新值，两者都 exit 0（已复现）。改 `readonly` + 单一 `each_import`；再加两道：section 0a 断言冻结记录集仍含每个 prefix（否则整个 app 被跳过而仍绿），section 4 断言跑完时的记录集与 0a 自检过的是同一份。

> **同名不是同源。** 两处读同一个变量名，但读的时刻不同，就是两个值。要让它们不可分叉，得让它们走同一条代码路径，并把数据冻住。

section 0a 的成员判定特意**读进变量再用 shell 模式匹配**，没有管道进 `grep -q`——那正是 §0.1.27 里让合法状态变红的 SIGPIPE 形状。**这次是主动避开，不是又踩一次。**

#### 0.1.31 同一个 commit 里，我把「搜得到」当成「绑定」用了两次（v1.31）

Sol 判 P1-2、P1-3 CLOSED，余 2 P1 + 2 P2，其中 P2-1 因实测 false-green 升级为 P1。两条 P1 是**同一个错误在同一次提交里的两个实例**。

| 我写的 | 它实际证明的 | 反例（已复现） |
|---|---|---|
| `grep -qF "$upstream_tree" provenance.md` | 这个 hash **出现在文件某处** | **互换两行的 root tree**：两个 hash 都还在，两个 prefix 都打印「records the true upstream root tree」，`rc=0` |
| `case "$RECORDS" in *"apps/qianwangyou|"*)` | 这个字符串**出现在记录块某处** | 把 `apps/qianwangyou|` 塞进**另一条记录的 branch 字段**：单条记录也报「两个 prefix 齐全」，生产循环完全跳过 qwy，`--stage import` 仍 `PASS (all checks)` |

> **搜得到 ≠ 绑定。** 我在 §0.1.25 写过「派生→比较，不是声明→印证」，在 §0.1.26 写过「删空必红」。这两条都没能挡住这次——因为它们检查的是**方向**和**退化输入**，而这次坏在**粒度**：我比对的是整个文件 / 整个记录块，而命题是关于**某一行 / 某一字段**的。
>
> **一个断言的作用域，必须等于它所声称的命题的作用域。** 命题说"这个 prefix 的 root tree 是 X"，就必须解析**那一行的那一个单元格**；命题说"记录集恰好含这两个 prefix"，就必须取**第一字段做集合相等**。放大作用域去搜，只会把"存在"误当成"归属"。

判据补第十一条：**断言的粒度必须与命题的粒度一致——逐行、逐字段、集合相等；不得用「在更大的容器里搜得到」代替。** 判别法：**把两条记录的该字段互换，检查必须红。**

另两条：

- **`readonly IMPORTS` 冻结不了 `each_import`。** 在 handler `exit 0` 之后重定义函数，query 仍返回冻结 SHA、生产循环却吃另一份记录。已加 `readonly -f each_import`。**冻结数据不等于冻结取数据的路径。**
- **history-lost + 分叉曾无条件放行。** 那是 v1.30 修「永远红」时冲过了头（§0.1.30 记的正是这个摆动）。现在区分三态：*pristine* → 绿；*分叉 + ancestry 可证* → 绿；**分叉 + 无任何祖先携带 baseline tree → 红**。**← v1.32 更正**：本轮我把「ancestry 可证」错误地实现成「原 import commit 仍可达」，于是 squash 合入后落真实 Task-2 delta（一条合法路径）被判红——**这是我第三次造出永远红的门**。判据已改为**任一可达 ancestor 的 prefix tree 等于 fetched upstream tree**，跨任何合入方式存活。

**P2-1 是我自己写的规矩没做到**：v1.30 往负例表加了 N-8/P-2/P-3，却把调用契约留在 `N-1..N-7 + P-1`——**加进表里而没加进调用，那三行当轮什么都没 gate 住**。这与 §0.1.28「手测写在提交消息里不构成可重放证据」同族，只是这次欠的是调用边。现已把 #10 的承重负例落成 `scripts/selftest-provenance.sh`（3 正 + 8 负）并接入 #10 的 CI——**跑生产 checker 本体，不是副本**，否则负例只证明副本的行为。

#### 0.1.32 我的变异测试自己是错的（v1.32）

Sol generation-8：2 P1 + 2 P2。两条 P1 都是我这几轮**已经写下判据、却只用在一处**的重演。

**P1-1 —— 第三次造出永远红。** squash 合入 PR #10 之后落 PR-2 的真实 contract 接线，是一条完全合法的路径：base commit 仍持 pristine 的 vendored tree，**ancestry 客观存在**，消失的只是我指名当载体的那一个 commit。我判了红，而 spec 自己冻结着「不冻结 merge method」。判据改成真正该问的问题：**有没有任一可达 commit 的 prefix tree 等于 fetched upstream tree**。prefix tree 只在触碰它的 commit 上变化，所以那些 commit 加 HEAD 就是完备候选集。

**P1-2 —— 判据 11 只用在了一格。** 我上一轮写下「断言粒度必须等于命题粒度」，然后把 root-tree 做成逐行绑定，**其余承重字段全留在全文件 `grep`**，连 row selector 本身都还是 `index($2, pfx)` 子串。Sol 两个反例：把行改名 `apps/qianwangyou-shadow` 仍打印「row for apps/qianwangyou … equal」；只互换两行的 **Exact upstream SHA 单元格**，两个 SHA 仍都"已记录"。**文档可以把 prefix→SHA 映射写错，而 checker 认证通过。**

> 这是 §0.1.24 那条的第二次兑现失败：**冻结判据的同轮必须枚举它的全部适用点**。我枚举了「哪些地方在做绑定」，却没枚举「这一行里还有哪些字段是承重的」——**枚举的维度选错了，等于没枚举**。

**P2-1 —— selftest 的标签在撒谎。** 它印 `PASS (3 positive, 8 negative)`，实际只有 6 条行为负例：N-7a 是正例，N-7b 只 `grep` 源码里两段字面量、`bash -c` 的结果还被丢弃。**grep 到一行代码，只证明那行存在，不证明它起作用。** 已重写为每条都执行生产 checker 并断言退出码，**计数由 harness 的计数器打印，不再手写**——手写的数字和真实执行是两个可以各自漂移的东西。

**但这一轮最该记的，是我自己的变异测试是错的。**

我按纪律给新负例做了变异验证：把修复退回去，看对应用例会不会失败。**两次变异都没打破测试。** 如果就此收工，我会带着"负例已验证"的错觉推出去。

我去看了，两个变异都构造错了：

| 变异 | 我以为移除了 | 实际 |
|---|---|---|
| 把 selector 退回 `index()` | 行选择的精确性 | **`r_prefix` 相等断言那层还在**，仍然判红 |
| `rev-list \| tail -1` | ancestry 的祖先遍历 | `tail -1` 取到的是**最老**的 prefix-touching commit——恰恰就是 pristine 那个 |

重做之后：同时移除两层精确性 → **N-2 失败**；ancestry 只看 HEAD → **P-4 失败**。两条都真正绑住了。

> **一个因为变异构造错误而通过的变异测试，比没有变异测试更糟。** 它产出的不是"未验证"，是**假的"已验证"**——和这几轮我反复修的 false-green 是同一个东西，只不过这次长在验证工具上。
>
> 判据补第十二条：**变异测试必须先自证有效——变异之后目标用例必须失败。如果它仍然通过，先怀疑变异，不要相信结论。**

四轮连起来看，我的错误正在沿着同一条线往上爬：产品代码 → 检查器 → 检查器的负例 → **负例的验证方法**。每往上一层，错误就更难被发现，因为上层工具的失败模式正好是"看起来一切正常"。

#### 0.1.33 让变异自检跑起来花了四轮，每一轮错的都是 harness（v1.33）

Sol gen-10：1 P1 + 2 P2。

**P1 —— strict parser 既不 strict 也不完整。** `gsub` 删掉的是**单元格内部**的全部空白，而不是外围的 Markdown padding：把 qwy 的 upstream SHA 写成中间带一个空格，checker 会把它规范化成正确值并认证。而第 6 格 import commit 根本没走 parser——仍由全文件 `sed` 另取，且任何 `merge-base --is-ancestor` 非零（含「对象不存在」）都被读成合法 squash。写 `0000…0000` 会打印 is-not-an-ancestor 然后 rc=0。**文档指名一个不存在的对象，那是文档错了，不是有人 squash 了。**

**P2-1 —— selftest 会在夹具根本没构造成功时自报全绿。** P-4 丢弃了 setup 的退出码、也不断言 delta 存在，于是弄坏第一条 append 之后，它**静默地重测了一遍 pristine P-3**，却照常打印 real-Task-2-delta rc=0。N-5 则把 iterator fork 和一个独立就能判红的 qwy tamper 捆在一起，注入失败时仍然绿。

**但这一节真正该记的，是把「变异自检进 harness」做出来花了四轮，而四轮错的都不是被测代码，是 harness 自己。**

| 轮次 | 现象 | 真因 |
|---|---|---|
| 1 | 三条变异全报「未绑定」 | `mutate()` 造的临时目录**不是 git 仓**，内层每个夹具都构建失败 |
| 2 | 仍全报「未绑定」 | P-4 的形状断言从「哪个仓恰好是 root」取 upstream tree，变异副本里没有该对象 |
| 3 | 单次运行超时 | 每条变异都跑整轮内层（含多次 upstream fetch）；改为只跑目标 case |
| 4 | 变异确实落地、内层确实失败，仍报「未绑定」 | **内层被管道进 `grep -q`，而内层按设计以非零退出；`set -o pipefail` 把「grep 命中」整体判成失败** |

第 4 条最刺眼：**那正是本仓 §0.1.27 已经修过、我还专门写过警告的 pipefail 形状**，我又在验证工具里造了一遍。

> 我上一轮冻的第十二条是「变异后目标用例仍通过时，先怀疑变异」。这四轮里我**每一轮都照做了**——正因为照做，才没有把四次假阴性当成结论收工。**判据在这里第一次真正救了我，而不是事后解释我。**

harness 因此再加两条硬约束：

- **先证明变异落地**：比对变异前后 checker 的 SHA-256，未变则报 MUTATION-DID-NOT-APPLY 而不是「未绑定」。**「用例仍通过」和「变异没应用」是同一段输出，后者会冒充前者。**
- **变异必须覆盖同一能力的全部实现层**：M-1 只退 row selector 时 N-2 仍红，因为 `r_prefix` 精确断言还兜着——**单层变异证明不了这一对**。

**这是错误爬到的第五层**：产品代码 → 检查器 → 检查器的负例 → 负例的验证方法 → **验证方法的执行环境**（是不是 git 仓、有没有对象、管道语义）。每高一层，看起来正常的欺骗性就更强一分。

#### 0.1.34 守护 X 的用例，在 X 被删掉之后依然是绿的（v1.34）

> ⚠️ **本节记录的是 gen-11 的初版 verdict，该 verdict 已被 Sol 的补充 verdict 上修（superseded）。** 下面这句「P1 已闭合」是**当轮的初版结论，不是终态**——补充 verdict 把 gen-11 由 `0 P1 + 2 P2` 更正为 `1 P1 + 2 P2`，新增的 P1 是第六格 TAB 传输截尾（见 v1.35 / §0.1.35）。**本节任何一句都不得读作 terminal closure。** 原文保留以便追溯当时的判断，不改写。

Sol gen-11〔**初版 · 已被上修**〕：**0 P1 + 2 P2**。P1 已闭合——parser 六格同行消费，internal-whitespace 与零/错 import commit 全部判红。〔**上修更正**：此处「P1 已闭合」只对 gen-10 的 parser exactness P1 成立；补充 verdict 随后指出同一个 parser 仍有 TAB 传输截尾这条未闭合的 P1，故 gen-11 的真实结论是 `1 P1 + 2 P2`。〕

**P2-1 的反证一句话就能跑**：临时仓里**只删掉** `check-provenance.sh` 的 `readonly -f each_import`，selftest 一个字不改，完整跑仍 `rc=0`，照常打印

```
PASS  N-5 iterator redefinition cannot fork query from gate (qwy still checked)
PASS  (4 positive, 12 negative, 3 mutation self-checks)
```

**N-5 存在的全部理由就是守护那一行，而那一行被删掉之后它依然是绿的。**

三个原因叠在一起，只有第三个是设计错误：

| # | 问题 | 性质 |
|---|---|---|
| 1 | 不断言退出码，只在**整段输出**里搜 `apps/qianwangyou` | 该串由 entry-file 段无条件输出，与 import loop 消费了什么无关 |
| 2 | 注入记录仍是旧 **4 字段**，schema 已是 5 | 删掉 `readonly -f` 后，malformed-record 成了**独立失败源**，冒充被测行为 |
| 3 | fork **丢掉 qwy**，而丢 prefix 本就被 0a 集合检查拦下 | **从未触达 `readonly -f`**——测的根本不是那条防护 |

第 3 条是我该早看出来的：**我给这个 case 选的攻击手法，会先被另一道更早的门挡住。** 于是它测的是那道更早的门，标签写的却是这一道。

修法：注入**合法 5 字段、qwy SHA 伪造**的 fork——0a 放行（两 prefix 齐全、字段数正确），只有 `readonly -f` 决定循环读到真记录还是伪造记录。断言两件事：`rc=0`，且输出含**只可能由 doc-binding 循环产生**的那行真 SHA。再加 **M-4**：删 `readonly -f` 必使 N-5 失败——实测只有 N-5 转红。

**还有一句必须收回。** spec 里写过「逐条把修复退回去」，而实际只有 3 条 mutation；同段还称 N-5「断言退出码」，而它当时没有。

> **宣称的覆盖面必须等于实际执行的覆盖面。** 描述测试的文字若比测试本身更强，它自己就是下一处假绿——而且更难发现，因为读者会拿它当已验证的事实。

现已如实写成 **4 条具名 mutation** 并列出各自守护的用例，同时写明选取标准：**曾经产生过假绿的修复必须有变异兜底**，新增负例不自动获得变异。

**P2-2 —— 同一份 body 在同一个 exact HEAD 上同时投影 21 与 33。** 顶部 current block 是对的，但正文 `At this HEAD: 21 PASS` 与「HEAD 仍是 …，本次未推任何 commit」两句仍在，而且我上一轮**广播说已经删了后者**。按同一失效类做审计，还抓到 Sol 没点名的第三处：`- Exact HEAD:` 同样是块外 HEAD 投影。处置不是把 21 改成 33——**刷新数字只是把同一类错误的值换一遍**；而是删掉块外的每一处当前态投影。自称「唯一当前真相块」而块外还有三处同类投影，那行标题本身就是假的。

> **判据补第十三条：断言所观察的输出，必须只能由被测能力产出。** 判别法：**把被测能力整个删掉，如果该输出仍然出现，这条断言就不是断言。** 判据 11 管的是粒度（命题说某一行某一格，就不能去搜整个文件），第十三条管的是**来源**——粒度对了，只要观察的是一个无条件产出的串，依然什么都没测到。上表第 1 条正是这样：`apps/qianwangyou` 无条件出现，所以那不是弱断言，是**不是断言**。

**验证环境这一层也翻了车。** 本轮第一次跑 M-4 时它报「N-5 未绑定」。按第十二条我先怀疑变异——真因不在变异，而在**被测对象在测试期间不是恒定的**：`mutate()` 是运行时从工作区拷 selftest 的，并发写者让它拷进了旧版 N-5。harness 的报告完全正确，只是它测的不是我以为的那份文件。改在 `git clone` 出的隔离副本里跑，结论立刻稳定复现。

**这是错误爬到的第六层**：产品代码 → 检查器 → 负例 → 负例的验证方法 → 验证方法的执行环境 → **被测对象在验证期间的同一性**。前五层问「这段逻辑对不对」，第六层问「**我刚才测的，还是不是我要测的那个东西**」。

**另记两条流程事故，都不是 Sol 提的，是我自己撞的。**

**一、验证过 ≠ 提交了。** 本轮 #10 的修复我写好、跑过、确认 M-4 通过，**然后在提交前丢失**——补丁脚本放在 `/tmp`，`/tmp` 被清理，工作树回到旧版。我是在运行**之后**补 grep 关键标记时才发现（返回 0，而那次运行明明报了 PASS）。**从此：辅助脚本落在仓内、用完即删；验证通过立即提交；提交前用一次独立 grep 确认标记真在文件里，不靠「我刚跑过」的记忆。**

**二、共享 worktree 可能有第二个写者。** 本轮我在 #12 工作树里发现一处**我无法确认出自本 session** 的未提交改动，内容正确且正好切中 P2-1。同 `catId` 的平行 session 与我共用这两个 worktree，虽已声明不写入，但**声明不是机制**。已如实上报，并采用：**动手前先 `git status`，发现非本 session 的改动先核实内容再决定是否纳入本轮提交**，不默认它是自己写的、也不默认它是脏数据。

#### 0.1.35 校验发生在值上，损坏发生在传输上（v1.35）

Sol 在同一对 exact HEAD 上补了一条 verdict，把 gen-11 由 `0 P1 + 2 P2` 更正为 **1 P1 + 2 P2**。

**P1 —— 第六格的 TAB 会凭空造出第七个传输字段。** `doc_row()` 把六个单元格用 TAB 拼成一行返回，三处消费方一律 `cut -f1..f6` 读回。于是在文档里把 qwy 的 import commit 写成 `<canonical sha><TAB>JUNK`，拼出来的行有**七**个字段，`cut -f6` 取到的仍是干净的 canonical SHA，`JUNK` 被静默丢弃。逐字复现（两条路径都要，因为两个 stage 走的是不同代码路径）：

| 路径 | 修前 | 修后 |
|---|---|---|
| full-DAG `--stage import` | `rc=0`，打印 `PASS row for apps/qianwangyou records the canonical import commit 5687e319f` | **`rc=1`** `ERR interior-whitespace cell 6` |
| depth-1 history-lost `--stage contract` | `rc=0`，同样打印 PASS | **`rc=1`** 同上 |

**上一轮我刚在这一格上做对了一件事，然后停在了半路。** v1.33 把「删掉全部内部空白」改成「只 trim 外围」，理由是前者会把坏值规范化成好值——这个判断是对的。但它只管到了**值**：`[[:space:]]` 确实覆盖 TAB，可内部 TAB 既不在首也不在尾，trim 规则根本管不着它。我校验了每一个单元格的内容，却从没问过**把它们拼成一个字符串再拆回来这件事本身是否无损**。

修法两层：① `doc_row()` 拒绝任意单元格 trim 后仍含内部空白——这六格（prefix / URL / branch / 三个 hex id）按构造就不含空白，拒绝它是使传输无损的那条规则；② 在拼装完成、交给 `cut` 之前，**独立断言该字符串恰好携带六个传输字段**。第二层不是重复：它把传输不变量表述成与维持它的规则**无关**的一句话，第一层若将来被改弱，第二层仍然拦得住。

新增 `N-12/*`（full-DAG / import，**六格逐格参数化**：prefix / URL / branch / SHA / root tree / import commit）与 `N-13`（history-lost / contract，第六格）两组负例，以及 `M-5`——按 M-1 的教训，它**同时**退掉两层，因为只退一层时另一层仍会把 `N-12/import-commit` 判红，变异会报「已绑定」却什么都没证明。

> **判据补第十四条：把多个已校验的值拼进一个字符串再解析回来时，分隔符必须被证明不可能出现在字段内部；否则对值的校验不能跨越传输存活。** 判别法：**把分隔符本身塞进每一个字段，必须红。**

**这条判别法说「每一个字段」，所以测的就是每一个字段（v1.36 补齐）。** 初版只篡改第六格，却照抄了「每一个字段」的措辞——**声明比测试宽**，而第六格恰恰是历史上被特殊对待过的那一格（v1.33 之前它根本没走 parser），于是"其余五格"正是唯一没被证明的部分。现在六格各有一条负例：cell 2..6 由内部空白规则判红，cell 1 由更早的一道门判红（row selector 不再匹配 prefix，整行找不到）——**两者都红，而判据要的正是"必须红"**。夹具按**单元格下标就地改行**，不做全文件替换：`apps/qianwangyou` 与 `master` 在别的表里也出现，全局替换会篡改这条用例没有点名的行。

判据 11/13/14 现在连成一条线：11 管**粒度**（别用整文件搜索代替某一格）、13 管**来源**（别观察一个无条件产出的串）、14 管**通道**（别假设校验过的值能原样穿过拼装与拆解）。三条都是同一个错误的不同投影：**我以为我在断言 A，实际断言的是 B。**

#### 0.1.36 我把诊断和修复塞进同一次提交，于是诊断没能开口（v1.36）

Sol gen-13：**0 P1 + 2 P2**，代码侧 blocker 清零。本节记的是拿到那个「零」的过程里，我自己走错的一步。

**上一轮我明说：本轮交付的是让失败可观测，不是我证明不了的修复。然后我把 `setup()` 诊断和 `mk_fulldag` 的两处候选修复放进了同一个 commit。** CI 转绿——而我说不出是谁修好的。**声明比执行宽**这件事，这次长在了我的交付顺序上：我宣布交付的是 A（观测），实际交付的是 A+B（观测 + 未经验证的候选修复），于是 A 永远没机会说话。

处置不是补一句"大概是身份问题"，而是**把归因做成实验**：开一条一次性分支，`workflow_dispatch` 触发 CI，**只退回 `mk_fulldag` 的两处硬化、保留诊断**。PR 分支一行未动（`dirty=0`、HEAD 未变）。结果：

| 证据 | 结论 |
|---|---|
| probe `8cde419b` / run `31376782849` | CI 精确报 **`Author identity unknown`** |
| 同一份日志的 M-5 | **`INCONCLUSIVE: N-12 was not green before the mutation`** |

所以两件事同时被钉死，各自都有修前红 / 修后绿：

1. **夹具真因确认是 git identity。** `git clone` 不继承 `user.email` / `user.name`，而 tamper 还要在 fixture 内再 commit 一次；macOS 会从 `user@host` 自动推导、Linux runner 拒绝。`mk_squashed` 一直用 `git init` + `git config` 持久化身份，所以它两个环境都绿——**差异一直摆在那儿，是我没把两个 builder 对齐**。上一轮我把这条标为「未证实」而不是「已证伪」，是这轮还能一次问对的唯一原因。
2. **变异的目标用例必须先是绿的，否则只能报 `INCONCLUSIVE`。** 夹具挂掉时 N-12 本就是红的，「回退修复后它变红」恒真，M-5 因此打出过一次空转绿。`mutate()` 当时只断言了变异**落地**（前后 SHA-256 不同），没断言目标**起点为绿**。附带一刀：夹具坏了也会打印 `FAIL <target> …`，旧的 grep 会把它读成"已绑定"，现在同样判 `INCONCLUSIVE`。

> **判据补第十五条：不知道根因时，只交付诊断，不要在同一次提交里附带候选修复。** 修复会把症状拿走，诊断就永远问不出话；要归因就把候选**单独**退回去跑一次。判别法：**如果这次提交绿了，我能说出是哪一处让它绿的吗？** 说不出，就说明诊断和修复该拆开。

配套一条可复用的手法：**归因实验走一次性 ref + `workflow_dispatch`**，不要在被审的 PR 分支上做——它同时给出 CI 环境的真因和零 HEAD 漂移，正好绕开本文反复吃亏的那个冲突。

**另一条 P2 是我以为已经解决的问题的下半截。** 两份 PR body 仍把当前 exact HEAD 投影成 `40029ae8`——**那枚 HEAD 是我自己撤回并标记为 known-red 的**。v1.34 立的「唯一当前真相块」纪律解决的是"有几份投影"，**没解决"这一份是否新鲜"**：块外副本删干净之后，剩下的那一份仍然要求每次推送后手工更新，而我推 `4a15a009` 时只顾着看 CI。单一真相源不等于自动真相源；**唯一的那一份如果不新鲜，它的唯一性只是让错误更权威**。

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

operator 在**千网游**中维护地址计划（顺序/优先级归千网游，见 §5 / §6.7.1），在 Auto 中选择该 schedule、设置每地址可信次数和少量常用参数，点击一次开始；Auto 逐地址运行 CellRebel，崩溃后可恢复，只在环境证据完整时计数，最终输出可追溯的可信结果与独立的未验证结果。

### 2.2 首版 A+ 范围

- 每地址可信次数（配额归 Auto）。**地址、经纬度与优先级不再由 Auto 导入**：它们属于千网游的 schedule item（§6.7.1），Auto 只持有稳定引用 `scheduleItemId` + `scheduleVersion`。〔v1.37 更正：旧版在此导入「优先级」，与 §5 的千网游拥有顺序直接冲突，且会造出第二套排序。〕
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
5. 预览**千网游给出的**计划顺序（Auto 只读、不排序、不重排）、总可信次数、预计可用时间窗和停止条件。
6. 点击开始后冻结 `PlanSnapshot`；改变“什么算成功”的设置必须生成新 plan version，不能改正在运行的账。

### 4.3 无人值守执行

1. Auto 为地址创建稳定 `attemptId` 和幂等键。
2. 调用千网游 `preflight`、`apply`，取得 lease 与证据起点。
3. 紧邻 CellRebel 启动前调用 `observe(pre)`。
4. 识别 CellRebel 是新执行还是 `PRE_EXISTING_RUN`，记录外部执行实例。
5. 完成后调用 `observe(post)`。
6. 只有前后环境、连续性、模式、CellRebel 完成证据全部成立时，事务性插入一次可信配额。
7. 调用 `release`；达到配额后**不自行进入下一地址**，而是带 `CompletionProofV1` 调用 `completeAndAdvance`（compare-and-advance，前置为 `expectedCurrentItemId` + `expectedScheduleVersion`），由千网游原子推进并返回 receipt；Auto 随后**独立 `observe` 验证新生效环境**才继续。〔v1.37 更正：旧版「完成指定次数后进入下一地址」是 Auto 自行推进，与 §6.7 握手直接冲突。〕
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

> **v1.37 边界更正（operator 裁定）**：上一版把「计划顺序」判给 Auto，同一张表下一行又把 profile/schedule 判给千网游为唯一权威——**同一个排序有两个所有者**。这不是措辞问题：Auto 一旦自己排序，就可能按经纬度排出一套顺序，而千网游按「环境」（经纬度 **+** 蜂窝/网络/Wi-Fi Hook 字段是**一个整体**）排出另一套，位置于是与网络 Hook 状态漂移。**顺序归千网游，配额与完成判定归 Auto，推进是二者之间一次幂等握手。**

| 边界 | Auto | 千网游 | 禁止 |
|---|---|---|---|
| 地址计划顺序/优先级 | **不拥有**；只读当前有效项的稳定引用（`scheduleItemId` + `scheduleVersion`） | **唯一权威**：拥有 schedule 的顺序、优先级与版本 | Auto 自行排序；或按 profile 行序、隐式首行推断顺序 |
| 每地址可信配额与完成判定 | **唯一权威**：定义配额、判定是否达成 | 不推断、不代计 | 千网游替 Auto 计 CellRebel 次数 |
| 推进（complete → advance） | 证明当前项达标后**发起**幂等推进请求 | **唯一执行方**：校验前置条件、原子推进 current item、返回 receipt | 任一方直接改写对方状态；无 receipt 即认定已推进 |
| profile/schedule | 仅保存稳定引用和计划快照 | 唯一权威，解析当前有效策略；**profile 是一个完整环境**（位置与蜂窝/网络/Wi-Fi 字段不可分割） | Auto 复制或解释内部规则；把位置与网络字段拆成两个可独立排序的维度 |
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

**返回通道（冻结，v1.59 · `KB-7`）**：六个方法**统一返回** `EnvironmentControlResultV1`——一个 app-public、显式版本化的 Parcelable 载体：成功时携带恰好一个 typed payload，预期业务失败时携带稳定的 `ContractErrorCodeV1.wire`（exact schema 见 §6.3.2）。Binder death 与 `RemoteException` 仍是 transport failure，走各自的恢复路径，不进本载体。

**由此冻结一条通用规则**：**契约引用的每个 `android.*` 类型都必须存在于编译 SDK 的 public 面。** 反例就是 v1.44 登记的 `KB-7`：旧 §6.3.2 冻结「预期业务失败经 `ServiceSpecificException` 携带稳定 wire code 返回」，而 `android.os.ServiceSpecificException` 是 `@hide`、不在 public SDK 里——**按字面实现不出来**，于是 CI 编译的只是 DTO 与方法签名，不是被要求的失败传输通道本身，任何只用 public SDK 的独立实现方照契约都做不出来。该判据与本文修过的最重一个 bug（canonical preimage 缺 domain）同源：**对任何第三方实现无条件不可用**。

该规则目前**零守卫**：实例已修，同类未防，故 §20.1 `KB-7` 由 `unfrozen` 改记为 `gap`（规则已冻结、无执行面证明）。**没有守卫就不得声称这条规则已被证明。**

```aidl
// contracts/environment-control-v1/src/main/aidl/io/github/terryyyc/fakexxx/contract/v1/IEnvironmentControlV1.aidl
package io.github.terryyyc.fakexxx.contract.v1;

import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1;
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1;

interface IEnvironmentControlV1 {
    EnvironmentControlResultV1 discover();
    EnvironmentControlResultV1 preflight(in PreflightRequestV1 request);
    EnvironmentControlResultV1 apply(in ApplyRequestV1 request);
    EnvironmentControlResultV1 observe(in ObserveRequestV1 request);
    EnvironmentControlResultV1 release(in ReleaseRequestV1 request);
    EnvironmentControlResultV1 completeAndAdvance(in CompleteAndAdvanceRequestV1 request);
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
enum class AdvanceOutcomeV1(val wire: Int) { ADVANCED(1), EXHAUSTED(2) }
enum class ScheduleDecisionV1(val wire: Int) { ALLOWED_NOW(1), WAIT_UNTIL(2), DENIED(3) }
enum class ContractResultKindV1(val wire: Int) { ERROR(1), DISCOVER(2), PREFLIGHT(3), APPLY(4), OBSERVE(5), RELEASE(6), COMPLETE_AND_ADVANCE(7) }
```

规则：

- 可信策略必须显式匹配 `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED`；禁止枚举顺序、`ordinal`、`>=` 或“非 NONE 即可信”。
- **禁止把枚举本体交给 `@Parcelize` 自动编解码。** kotlin-parcelize 的 `IrEnumParcelSerializer` 写入 `Parcel.writeString(value.name)`、读出 `EnumClass.valueOf(readString())`。后果：重排常量顺序是 wire-safe 的（ordinal 不上线），但**改名是破坏性变更，新增常量会让旧读者抛 `IllegalArgumentException`**——异常从生成的 `createFromParcel` 抛出，表现为 unparcel 崩溃，而不是 INV-03 要求的 typed fail-closed。两个 App 独立发布、版本必然 skew（§10 version 行），所以自动编解码在本方案里不可用。承载 `Int` + 显式 `fromWire()` 把 skew 变成可判定的业务错误。
- `fromWire()` 返回 `null` 时一律 fail-closed：可信路径直接判不可信，握手路径返回 `INCOMPATIBLE_PROTOCOL`。
- v1 已分配的 wire code 永久不可回收、不可改语义；新增常量只能追加新 code，且必须先通过 §6.8 兼容矩阵。
- **`ContractResultKindV1` 的定义域全部 1-based，`wire 0` 是永久非法值**（operator option B）。它与其余六个域同规：`fromWire(0)` 返回 `null`，读者 fail-closed。曾评估过的 `ERROR = 0` 能让成功 kind `1..6` 与 §6.1 的方法声明位序精确对齐，且**两案 fail-closed 安全性等价**（`kind 0` + `errorCodeWire == null` 是非法元组，未知 kind 也是非法元组，殊途同归）；取 1-based 的理由不是安全性，而是**冻结卫生**——「0 永远非法」是这个家族唯一可执行的跨域不变量，为一个域破例会把它降级成一条只写在散文里的约定。
- **kind ↔ payload 字段绑定（冻结）**：`ERROR` → `errorCodeWire`；`DISCOVER` → `capabilitySnapshot`；`PREFLIGHT` → `preflightReport`；`APPLY` → `applyReceipt`；`OBSERVE` → `environmentObservation`；`RELEASE` → `releaseReceipt`；`COMPLETE_AND_ADVANCE` → `advanceReceipt`。

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
    /** 当前有效的 schedule 投影组（§6.7.1, v1.55）。四者同为 null 表示 provider 当前
     *  没有活动 schedule——这是 discover() 时的合法状态；null 读作「没有当前项」，
     *  绝不可读作「任意项」或「未耗尽」。缺了它们，Auto 无法构造 §6.7.4 的前置，
     *  wire 14/15/16 就成了**存在但不可达**的守卫。exhausted = true 表示最后一项
     *  已完成；currentItemId 保留末项（耗尽时不为 null）。见 §6.7.4 v1.54 状态模型。 */
    val currentScheduleId: String?,
    val currentItemId: String?,
    val scheduleVersion: Long?,
    val exhausted: Boolean?,
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
    /** 本观察是**为哪个 schedule item** 作证（§6.7.1 / §6.7.5）。
     *  一个 profile 可被多个 schedule item 复用，所以匹配 profile、坐标、
     *  甚至 environmentFingerprint 都**不能**证明观察属于 receipt 声称推进到的那一项。
     *  **非终末**推进时，Auto 用它与 `AdvanceReceiptV1.advancedToItemId` 比对；没有它，
     *  唯一可做的检查就只剩「环境看起来对」——那正是错环境归因。
     *  **终末（EXHAUSTED）推进不走本载体**：那时 `advancedToItemId` 为 null 而本字段非空，
     *  该腿恒为 false，改由 §6.7.5（v1.58）的独立 schedule 状态回读验证。 */
    val scheduleItemId: String,
    val scheduleVersion: Long,
) : Parcelable
```

**exact schema 无省略**：本节与 §6.3.2 列出的字段就是全部字段，逐字段与实现一一对应。v1 的任何 Parcelable 中**不出现 Kotlin enum 类型**（含 `Set<enum>`/`List<enum>`）；枚举一律以 `...Wire: Int` 或 `...Wires: List<Int>` 承载，集合型升序去重。散文说明不得覆盖或补充 exact schema——若某字段没写在这两节里，它就不在 v1 里。`check-contract-v1.sh` 必须包含一条静态检查：contract 模块的 `@Parcelize` 类中出现任何 enum 类型字段即失败。

**字段顺序同属冻结面。** 上述"逐字段一一对应"读作**保序**对应，不是集合意义上的一一对应：本节与 §6.3.2 的 snippet 是 `@Parcelize` Kotlin 代码，而 kotlin-parcelize 按**声明顺序**写入与读回 Parcel。因此互换两个同类型字段会保留全部字段名、两侧都照常编译、运行期什么也不抛，却在 wire 上静默交换二者取值——**这与 §6.1 的 Binder 事务号随方法声明顺序而变是同一件事，低一层**。所以 `check-contract-v1.sh` 比对的是有序的 (字段名, 类型, 可空性, 有无默认值) 元组，而非字段名集合；默认值的**存在与否**参与比对，其**表达式文本**不参与（canonical 写 `= 1`、实现写 `= ContractV1.PROTOCOL_VERSION` 是同值的两种写法）。解析不了的属性行按失败处理，绝不跳过——静默丢弃读不懂的行会让两侧字段表同时变短，然后被判为"一致"。

#### 6.3.1 canonical intent digest（冻结算法）

`acceptedIntentHash` 是 `EnvironmentIntentV1` 的 canonical digest，两侧必须独立算出同一值：

```text
canonical = uint32be(byteLength(domain)) || domain
            然后按下列顺序，对每个字段依次追加：
              uint32be(byteLength(fieldBytes)) || fieldBytes
            无分隔符、无尾随字节。

  domain                    : ASCII "fakexxx:contract:v1:intent"（**首个 framed 字段**）
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

**「UTF-8 bytes，原样」的前提是输入本身是良构 Unicode，本轮把这条前提显式冻结。** JVM 的 `String.toByteArray(UTF_8)` 对**未配对代理**静默替换为 `?`(0x3F)，于是 id 为 `"\uD800"` 与 id 为 `"?"` 产出**同一串字节、同一个 digest**——framing 的单射性在它自己接受的输入上就不成立了。item id 经 Binder 以 Java String 传递、`Parcel` 走 UTF-16，**未配对代理能到达这里，不是理论输入**。

故冻结：**进入任何 preimage 的字符串必须是良构 Unicode（不含未配对代理）；不满足时 digest 计算必须失败，不得替换后继续**。这与 §6.7.1 presence 判别位是同一类修复——`presence` 消除的是"值可能等于哨兵"的偶然性，本条消除的是"值可能不可编码"的偶然性；**两者都属于「正确性依赖运行期偶然」，只是一个在判别层、一个低一层在字节转换层**。上一轮补 presence 时只扫了判别层，没往下扫一层，所以历轮 hostile id 负例**全部通过**却漏掉它：那些负例比的都是「id vs 缺省」，**没有一条比过两个会互相碰撞的 id**。

（provider 在边界上以何种 typed code 拒绝畸形 id 属 provider 侧行为，本节不代为冻结；本节只冻结**契约模块自身的 digest 必须 fail-closed**。）

acceptedIntentHash = lowercase hex of SHA-256(canonical)
```

**为什么是长度前缀而不是分隔符连接**：四个 ref 字段是自由字符串，用任何固定分隔符连接都可构造碰撞——例如以换行连接时，`runId="a\nb", attemptId="c"` 与 `runId="a", attemptId="b\nc"` 产生**完全相同**的 canonical 字节，于是两个不同意图共享同一 `acceptedIntentHash`，INV-23 的绑定被绕过。长度前缀让编码单射，碰撞不再依赖"字段里恰好没有分隔符"这种运行期巧合。禁止改回分隔符方案，也禁止用"契约上不允许出现换行"来代偿——那是把不变量的正确性押在输入校验上。

**v1.38 增补：每个 digest 必须带 domain。** 长度前缀只让**一个**字段序列单射，它不阻止「为用途 A 编码的序列」等于「为用途 B 编码的序列」——`len||bytes` 里没有任何东西说明这些字段是什么意思。没有 domain tag 时，intent preimage 与 advance-request preimage 共用同一个空间，正确性就押在「不会有构造输入跨用途碰撞」上。**这正是本节拒绝分隔符连接时用过的同一条理由**（「碰撞不再依赖运行期巧合」），只是上移了一层。所以 domain 作为**首个 framed 字段**写进每个 preimage。**exact ASCII 值就此冻结**（三者永不复用、永不改写）：

```text
intent           "fakexxx:contract:v1:intent"
advance-request  "fakexxx:contract:v1:advance-request"
advance-receipt  "fakexxx:contract:v1:advance-receipt"
```

只写「加个 domain」而不冻结逐字节值，两侧独立实现会各取各的字符串，digest 永不相等——而那读起来会像「对端不同意」，不像「我们的 domain 拼写不同」。

禁止用 `toString()`、`hashCode()`、`Objects.hash()`、任何 JSON 序列化或 Parcel 字节作为 digest 来源——它们都不保证跨版本/跨进程稳定。7 位小数（约 1.1 cm）在冻结容差之下，确保 digest 不会因浮点文本化差异漂移。

必测（both sides，逐条独立断言）：

- 上述**分隔符碰撞对**必须产生**不同** digest；
- 负坐标、`0.0`/`-0.0`（必须归一为同一表示）、需要半值向偶数舍入的边界值；
- 四个 ref 含换行、制表符、emoji、以及多字节字符时两侧 digest 一致；
- 空 ref 在业务上非法，导入/预检阶段即拒绝（这是产品校验，不是 digest 的正确性来源）。

#### 6.3.2 其余 DTO exact schema

**统一返回载体在最前**（v1.59 · `KB-7`）：§6.1 的六个方法全部返回 `EnvironmentControlResultV1`；其下的 payload 类型不再作为方法返回类型直接出现在 wire 上，而是作为该载体的字段。

```kotlin
@Parcelize
data class EnvironmentControlResultV1(
    val resultSchemaVersion: Int,
    /** ContractResultKindV1 wire code。 */
    val resultKindWire: Int,
    /** ContractErrorCodeV1 wire code；仅当 resultKindWire 为 ERROR 时非 null。 */
    val errorCodeWire: Int?,
    /** 仅供人读的诊断串；任何机器判定都不得读它。 */
    val diagnosticMessage: String?,
    val capabilitySnapshot: CapabilitySnapshotV1?,
    val preflightReport: PreflightReportV1?,
    val applyReceipt: ApplyReceiptV1?,
    val environmentObservation: EnvironmentObservationV1?,
    val releaseReceipt: ReleaseReceiptV1?,
    val advanceReceipt: AdvanceReceiptV1?,
) : Parcelable

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
    /** Schedule 投影组（§6.7.1, v1.55）。三者同为 null 表示 provider 当前没有活动
     *  schedule；三者同为非 null 表示有。不点名 item 的报告，与「上一刻还是当前项」
     *  的报告无法区分——而推进制造的正是这种混淆。
     *  v1.55 前这两个字段为非空，handler 用 `?: ""` / `?: 0L` 填充——哨兵值冒充
     *  真实数据（round-5 sentinel 反模式）。现改为 nullable，与 CapabilitySnapshotV1
     *  的投影组一致。 */
    val scheduleItemId: String?,
    val scheduleVersion: Long?,
    val exhausted: Boolean?,
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

`EnvironmentControlResultV1` 的**不变量**（冻结）：

- `resultSchemaVersion` 恒为 `1`。载体 schema 由**类名 + 字段声明顺序**冻结；不兼容的演进走**新载体／新接口版本**，不是往本类尾部追加字段——公开 DTO 冻结后追加字段是破坏性变更。
- `resultKindWire == ERROR`：`errorCodeWire` 非 null，六个 payload 字段**全部** null。
- 其余六个 kind：`errorCodeWire` 为 null，且**恰好**其绑定的那一个 payload 字段非 null（绑定表见 §6.2）。
- 不满足上述任一条的元组——含未知 `resultKindWire`、`null` 载体本身——一律按 **response anomaly** fail-closed 处理，**不得**猜测意图。未知 `errorCodeWire` 按 §6.3.3 映射为 `INTERNAL_FAILURE`。
- **解码失败不得从 `createFromParcel` 抛出**：unparcel 期崩溃会把业务失败重新推回 transport 路径，而那正是本载体要消除的形态（与 §6.2 禁止 enum 自动编解码同一条理由）。因此本类 `init` 不做校验，校验发生在消费点。
- `diagnosticMessage` 只供人读；任何机器判定读它即为缺陷（与 §6.3.3 对 error message 的规定同源）。

`EnvironmentIntentV1` 与上述全部类型都必须有对应 `.aidl` parcelable 声明与 `.kt` 实现，并纳入 §12 文件所有权。**实现者不得自行发明字段**：任何需要新增字段的发现都回本 spec 修订，不在 consumer branch 私改。

所有 request 另含 `idempotencyKey` 或稳定 operation id。

#### 6.3.3 `ContractErrorCodeV1` 全量映射（表 1）

每个 code 绑定永久稳定的 `wire: Int`，规则同 §6.2。**本表是 v1 typed failure 的完备集**：spec 中任何一条 INV 或 §10 矩阵行所要求的 typed failure，都必须能在这里找到唯一对应的 code；找不到即是 spec 缺陷，回本表补，不得由实现者复用近义 code 或私自发明。

| wire | code | 触发条件 | 承载的 INV / §10 |
|---|---|---|---|
| 1 | `NOT_PAIRED` | 调用方不在 caller allowlist（或 Auto 侧 provider 未批准，本地态） | INV-02；pairing 行 |
| 2 | `CALLER_NOT_ALLOWED` | 身份解析失败或被拒：`getPackagesForUid` ≠ 1 个包、多签名者、signer 与快照不符、已 revoke | INV-02；bypass/pairing 行 |
| 3 | `INCOMPATIBLE_PROTOCOL` | `protocolVersion` 不在支持集合，或**载荷枚举**（`VerificationLevelV1`/`ContinuityCoverageV1`/`DeliveryModeV1`/`ScheduleDecisionV1`/`AdvanceOutcomeV1`）出现未知 wire code | INV-03,04,19；version 行 |
| 4 | `CAPABILITY_UNAVAILABLE` | 请求的模式/profile/schedule 当前不可用 | INV-01,03 |
| 5 | `SCHEDULE_DENIED` | `scheduleDecision == DENIED` | INV-17；recovery 行 |
| 6 | `CONTINUITY_NOT_FULL` | coverage 为 `PARTIAL/NONE` | INV-08,09；crash/bypass 行 |
| 7 | `LEASE_CONFLICT` | 与某个 active/未收敛 lease 冲突：另一 caller 或另一 intent 的（`apply`，§6.6），**或推进时设备上存在的任一非 `RELEASED`/未收敛 lease——无论属于哪个 caller**（`completeAndAdvance`，§6.7.4a） | INV-14,16；concurrency 行；advance lease 门 |
| 8 | `STALE_LEASE` | 该 leaseId 对**本次操作**不可用：非本 caller 所有、已 `RELEASED`，或对 `apply`/`observe` 而言处于 `EXPIRED`/`REVOKED`/`RELEASE_INCOMPLETE`。**`release`、`completeAndAdvance` 与推进后验证窗口内的 `observe` 例外见下** | INV-14；release 行 |
| 9 | `ENVIRONMENT_DRIFT` | `expectedIntentHash` 与当前 lease 生效意图不符，或有效环境已漂移 | INV-08,23；intent 行 |
| 10 | `RELEASE_INCOMPLETE` | release 无法证明清理完成 | INV-21；release/recovery 行 |
| 11 | `INTERNAL_FAILURE` | 服务端内部错误；**以及未知 `ContractErrorCodeV1` wire 的唯一 fallback** | INV-03 |
| **12** | **`IDEMPOTENCY_CONFLICT`** | **同 `idempotencyKey` 但 payload digest 不同** | **INV-13；`apply 同键异 payload` 行** |
| **13** | **`REQUEST_INVALID`** | **请求结构性非法：必填 ref 为空、坐标越界、`deadline ≤ notBefore`** | **INV-04；contract round-trip 负例** |
| **14** | **`SCHEDULE_ITEM_MISMATCH`** | **`expectedCurrentItemId` ≠ 实际 `currentItemId`；挡住错项推进与重复推进** | **§6.7.4；advance 负例** |
| **15** | **`SCHEDULE_VERSION_STALE`** | **`expectedScheduleVersion` ≠ 实际 `scheduleVersion`：判定配额期间计划被改** | **§6.7.4；advance 负例** |
| **16** | **`SCHEDULE_EXHAUSTED`** | **计划已经耗尽时又请求推进——没有可完成的当前项，是真正的调用方错误**。〔v1.39 更正：本行旧文写「终态而非失败」，与 §6.7.4 冲突。**完成末项**是成功，走 receipt `outcomeWire = EXHAUSTED` + null target；本 wire 只用于**已耗尽之后再请求**〕 | **§6.7.4；advance 负例** |

**两个方向必须分清**：上表全部是**服务端→调用方**的失败码。`PreflightReportV1` 里 `scheduleDecision == WAIT_UNTIL` 却缺 `waitUntilEpochMs`，是**应答**结构性非法，不是请求非法，因此**不能**用 `REQUEST_INVALID` 表示——那会把服务端缺陷伪装成调用方错误。冻结消费方处置：Auto 收到自相矛盾的应答一律 **fail-closed**，按 §6.4.1 矛盾 tuple 处理（不进入可信判定、不启动 CellRebel、写未验证并记 typed reason），并在预检页给出可操作错误。同类规则适用于任何应答级矛盾。

12 与 13 由本次全量映射审计发现：INV-13 与 §10 早已要求"同键异 payload → typed conflict"，但清单里只有语义不同的 `LEASE_CONFLICT`；结构性非法请求此前只能落到 `INTERNAL_FAILURE`，既不可诊断，也会把调用方错误伪装成服务端故障。**两者都必须在 v1 冻结时就位**——事后追加 code 意味着旧读者按 §6.2 规则把它们 fail-closed 成 `INTERNAL_FAILURE`，语义永久丢失。

**`STALE_LEASE` 不得挡住恢复路径。** §8.4 要求 `EXPIRED` 经 `RELEASING` 收敛，若 `release` 对它返回 `STALE_LEASE`，该迁移就永远走不到，lease 会永久卡在阻挡态。因此冻结：**由 lease 所属 caller 发起的 `release`，在 `ACTIVE`/`EXPIRED`/`RELEASE_INCOMPLETE` 三态下都必须被受理**并驱动状态机；只有"非本 caller"或"已 `RELEASED`"才返回 `STALE_LEASE`。`apply` 则对全部非 `ACTIVE` 态返回 `STALE_LEASE`；`observe` 同样如此，**但推进后验证窗口例外，见下**。

**`STALE_LEASE` 同样不得挡住推进：本行的"已 `RELEASED`"分支不覆盖 `completeAndAdvance`。** 该方法的 `leaseId` 按 §6.7.4a 是**配额在哪个 lease 下挣得**的历史引用，用于归因与审计，**不是一个活动持有**；而 §6.7.4a 冻结的调用序列本身就是**先 `release` 再 advance**，所以"该 lease 已 `RELEASED`"恰恰是本请求的**正常形态**。若按本行字面对它返回 8，唯一合法的调用形态就被判成失败，`completeAndAdvance` 将无条件不可用。

**同一条规则的第三半：非终末推进后的 `observe` 也不得被「已 `RELEASED`」挡住。** §6.7.5 冻结「**非终末**推进后**必须**以 `observe()` 独立验证」（终末推进改走 §6.7.5 v1.58 的独立状态回读，见本节末注）——Auto 以 `observe()` 确认新生效环境，理由是「receipt 是对方的自述，不是生效证据」。但该调用发生在 §6.7.4a 序列的 `completeAndAdvance` 与**下一项 `apply`** 之间，而 §6.7.4b 第 5 步的 lease 门是**设备全局**的：推进期间不得存在任何非 `RELEASED` lease，推进后新 lease 要到下一项 `apply` 才授予。因此在这一刻，`ObserveRequestV1.leaseId`（非空必填，§6.3）唯一能填的就是那个已 `RELEASED` 的历史引用。按本行字面返回 8，则**这道被冻结为「必须」的验证步骤无条件不可达**，Auto 只剩「只信 receipt」一条路——正是 §6.7.5 指名要堵的错环境归因入口。

故冻结：**由该 lease 所属 caller 发起的 `observe`，当 `leaseId` 正是本 caller 最近一次成功 `completeAndAdvance` 所携带的历史引用、且此后尚未有新 lease 授予该 caller 时，本行的「已 `RELEASED`」分支不适用**，必须受理并返回当前生效环境。窗口之外（该 caller 已获授新 lease，或 `leaseId` 不是该历史引用）仍返回 8。**「非本 caller 所有」分支不受本例外影响**，仍无条件返回 8。该判定**不需要新增字段或新方法**：provider 为 §6.7.5 的幂等重取本就必须持久保留 advance receipt，其中已含该 `leaseId`；且正因为比对的是 provider 自己留存的 receipt，伪造的 `leaseId` 无法通过本例外——它落不进窗口，只会走回 8。

注意本例外**不吞掉**并发导致的失配：若他方 caller 在推进后抢先 `apply` 改了环境，`observe` 仍会被受理，由 §6.7.5 的 `effectiveIntentHash`／`effectiveEnvironmentRevision` 比对判出失配并进入恢复。**受理后判失配是可诊断的，返回 8 则不是**——后者与「你的 lease 过期了」在调用方看来完全同形，Auto 无从区分该重试还是该恢复。

**这是同一处缺陷的第三次同类。** v1.42 为 wire 7 加宽定义时漏了 wire 8 的对称收窄；v1.43 补上 `completeAndAdvance` 后，紧邻**一跳之后**的 `observe` 仍留在拒绝侧。v1.42 自己写下的「**修一个方向而不扫同类，矛盾只会挪到相邻一行**」在本轮应验，只是这次它挪到了**下一跳**而不是相邻一行。因此补一条通用要求：**冻结一条「必须」的调用序列时，必须逐跳检查每一跳的前置条件在该时刻是否可满足**，而不是只检查被点名的那个方法——序列的可执行性是逐跳的合取，任何一跳不可达，整条序列就是死的。

这与上面 wire 7 那处是**同一条规则的两半**：§6.7.4a 冻结"违反 lease 门返回 7"时，同步加宽了本节对 7 的定义并写下"定义与用法必须同向"；但同一轮只改了加宽的那一半，紧邻的 8 需要的收窄没有跟着做——**同向要求是双向的，一次只修一个方向就会在相邻行上重新长出矛盾**。

**仍未冻结，不要读成已解决**：`completeAndAdvance` 收到**非本 caller 所有**的 `leaseId` 时如何处置。§6.7.4b 的判定次序里没有这道门，本节也不在此新增——归因造假的后果是审计把配额记到别人的 lease 名下，但该校验属 provider 侧行为，须与 `M-AD-12`/`M-AD-13` 的 owner 一并裁定后再冻结。在那之前两侧实现**都不得**擅自选一种读法并依赖它：一个用 8 拒绝、另一个放行，Auto 的恢复策略就不可移植，而这正是 §6.7.4b 冻结判定次序所要消除的形态。

**`REVOKED` 不在上述三态里，因为它根本不可达。** 撤销的第一效果就是让该 caller 的每次 Binder 调用都失败（§6.5 要求逐次匹配 active `PairingRecord`），所以"让原 caller 去 release 一个 `REVOKED` lease"是自相矛盾的——它连调用都进不来。**两侧撤销必须分开定义**：

| 撤销方 | caller 还能调用 qwy 吗 | lease 清理由谁驱动 |
|---|---|---|
| **qwy 撤销 caller**（`PairingRecord`） | 否 | **qwy 内部自清理**：由 provider 直接把 lease 从 `REVOKED` 推过 `RELEASING → RELEASED`。qwy 拥有环境，不需要 caller 参与握手 |
| **Auto 撤销 provider**（`ProviderPairingRecord`） | 是（Auto 仍被 qwy 授权） | Auto 正常调用 `release`，走上面三态路径 |

**不为被撤销的 caller 保留任何 post-revoke 能力。** 给一个已失权的调用方开一条"仅用于清理"的口子，等于在鉴权面上开洞去解决一个 provider 自己就能解决的问题——qwy 本来就是环境的唯一权威。Auto 侧在 run 进行中遇到 `CALLER_NOT_ALLOWED`/`NOT_PAIRED`，一律暂停计划并暴露人工恢复，**不得假定环境已干净**，也不负责清理。

`IDEMPOTENCY_CONFLICT` 与 `LEASE_CONFLICT` 不可互相替代：前者是"你用同一把钥匙提交了不同的内容"（调用方错误，重试无用，必须换 key 或修正 payload），后者是"环境仍被某个未收敛的 lease 占用"（时序冲突，释放后可重试）——占用者可能是别的 caller/意图（`apply` 场景），**也可能是 caller 自己那个尚未 release 的 lease**（`completeAndAdvance` 场景，§6.7.4a）。两种占用的恢复动作是同一条：先让 lease 收敛，再重试。把 `IDEMPOTENCY_CONFLICT` 与 `LEASE_CONFLICT` 合并则会让 Auto 的恢复策略无法区分"该放弃"与"该等待"。

预期业务失败通过 `EnvironmentControlResultV1.errorCodeWire`（§6.1／§6.3.2）返回稳定的 `ContractErrorCodeV1.wire`；Auto 将 wire code 映射为上述 sealed error。未知 code 只能映射为 `INTERNAL_FAILURE` 并 fail-closed，不能猜成兼容。Binder death/`RemoteException` 属于 transport failure，单独进入 recovery；错误 message 只用于安全诊断，不承担机器判定。

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
| `callerProtocolVersion` | 协议兼容由 §6.8 握手判定；进 digest 会让"重试期间调用方升级"变成伪冲突。v1 已冻结，v2 走新 interface |
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
| `scheduleItemId` | **谓词** | 推进后必须 `== AdvanceReceiptV1.advancedToItemId`；profile 可跨 item 复用，故环境相符**不能**替代本条。**仅适用于非终末推进**（`ADVANCED` outcome）——终末推进 `advancedToItemId = null` 且免除 observe（§6.7.5, v1.57） |
| `scheduleVersion` | **谓词** | 必须 `== AdvanceReceiptV1.scheduleVersionAfter`；不等表示计划在观察窗口内又变了 |
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

**多签名者：v1 一律 fail-closed 拒绝**（`SigningInfo.hasMultipleSigners()` 为真即拒）。理由是窄接口优先：单一 digest 无法无歧义表示一个签名者集合。若将来产品必须支持，只能冻结"排序后 signer-set 的 canonical digest"并走 §6.8 兼容矩阵，不得用"取第一个"或"任一匹配"含混带过。

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

理由：双 App 是独立发布的（INV-19），版本 skew 由 §6.8 的 protocol handshake 判定。若把 versionCode 并入身份匹配，任何一侧的正常升级都会要求 operator 重新配对——这与"独立发布 + 能力兼容握手"直接冲突，且会训练 operator 对配对提示脱敏。

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

### 6.7 调度身份与「达标 → 完成 → 推进」（v1.37 新增）

§5 把顺序判给千网游、把配额与完成判定判给 Auto 之后，两者之间只剩一个动作没有契约：**谁、在什么前提下、如何把当前项推进到下一项。** 本节冻结它。没有这一节，「冻结 contract v1」冻的是一个无法表达自身核心循环的契约。

#### 6.7.1 身份与版本（千网游拥有）

| 字段 | 语义 | 约束 |
|---|---|---|
| `scheduleId` | 计划的稳定标识 | 跨重启、跨 revision 不变 |
| `scheduleItemId` | **计划项**的稳定标识 | **不是位置**。重排、插入、删除其它项都不得改变幸存项的 id |
| `scheduleVersion` | 计划与执行的单调版本 | 顺序 / 成员 / 优先级变化，或 committed advance（含末项完成），或 schedule (re)initialization——**任一**都必须自增；每次 committed advance 在其 CAS 事务内恰递增 1（v1.56 冻结）。**(re)initialization 也必须自增**（v1.57 冻结）——否则同拓扑 reset（清 exhausted 但 item + version 不变）消费者无法区分「原世代已耗尽」与「新世代未耗尽」，`scheduleVersion` 不再单调 |
| `currentItemId` | 当前有效项指针 | 千网游唯一权威；与「当前生效环境」同源 |

**禁止**：按行序、首行、或 profile 表的隐式顺序推断当前项。千网游现存的「隐式首行 + 遗留行序回退」必须迁移到显式 `currentItemId`；那条未标注的全 profile 折线是**投影**，不得被任何一方当作顺序真相。

**profile 是一个完整环境**：位置与蜂窝/网络/Wi-Fi Hook 字段属于同一 `scheduleItem`，不得被拆成两个可独立排序的维度——这正是双排序会让位置与网络状态漂移的地方。优先级属于**计划项**，不属于可复用的 profile 行。

#### 6.7.2 完成证明（Auto 拥有，千网游不重算）

`CompletionProofV1`：`scheduleItemId` · `trustedSuccessCount` · `quotaRequired` · `ledgerRef` · `verifiedAtElapsedRealtimeMs`。

千网游**只记录、不重算**——它一旦自行推断 CellRebel 是否完成，就违反 §5「千网游推断 CellRebel 完成」的禁止项，配额也就有了第二个所有者。

#### 6.7.3 幂等 request / receipt

`CompleteAndAdvanceRequestV1`：`leaseId` · `idempotencyKey` · `requestDigest`（§6.3.4 同一套 canonical preimage）· `expectedScheduleVersion` · `expectedCurrentItemId` · `completionProof`

`AdvanceReceiptV1`：`outcomeWire` · `advancedFromItemId` · `advancedToItemId`（耗尽时为 null）· `scheduleVersionAfter` · `effectiveIntentHash` · `effectiveEnvironmentRevision` · `receiptDigest`

**`receiptDigest` 的算法（v1.38 冻结——此前完全未定义）**：

```
domain = "fakexxx:contract:v1:advance-receipt"
  requestDigest                 UTF-8（**它应答的那个请求**）
  idempotencyKey                UTF-8
  outcomeWire                   ASCII decimal
  advancedFromItemId            UTF-8
  advancedToItemId              UTF-8；为 null 时用**存在性判别位**编码（见下），**不是哨兵、不是空串**
  scheduleVersionAfter          ASCII decimal
  effectiveIntentHash           UTF-8
  effectiveEnvironmentRevision  ASCII decimal
receiptDigest = lowercase hex of SHA-256(canonical)
```

**为什么 receipt 必须绑回请求**：不绑请求的 receipt 不构成任何证据——重试时调用方拿回一份 receipt，却无法区分「这是**我这个请求**的存档应答」与「这是 provider 手上碰巧有的另一份应答」。Auto **在信任 receipt 之前先重算它**；重算不上的 receipt 不是「弱一点的 receipt」，**它不是 receipt**。

`advancedToItemId` 为 null（耗尽）时用**显式存在性判别位**编码，**不是哨兵值**：

```text
缺省：framed "0"，其后不跟任何字段
存在：framed "1"，其后紧跟 framed 的 id
```

〔**v1.39 更正**：上一版写的是「构造上不可能出现在真实 id 中的哨兵」，并以「§6.7.1 要求 id 可打印」为依据——**§6.7.1 并没有这条要求**。于是编码的单射性只靠一个无人强制的假设，一个恰好等于哨兵的 id 会与 null 产生同一 digest。**判别位没有「取值不可达」这项义务**：任何 id 都不可能被读成缺省。这与 §6.3.1 拒绝分隔符是同一条理由——不要把正确性押在运行期巧合上。〕

**幂等语义**：同 `idempotencyKey` + 同 `requestDigest` → 返回**同一份 receipt**，**不产生第二次推进**；同键异 digest → `IDEMPOTENCY_CONFLICT`（wire 12）。

**`requestDigest` 的 preimage 必须绑住两条前置**，否则 §6.7.4 整节失效：

```
canonical = 逐字段 uint32be(byteLength(bytes)) || bytes，无分隔符、无尾字节
domain = "fakexxx:contract:v1:advance-request"（**首个 framed 字段**，§6.3.1）
  leaseId                  UTF-8 verbatim
  expectedScheduleVersion  ASCII decimal
  expectedCurrentItemId    UTF-8 verbatim
  completionProof.scheduleItemId        UTF-8 verbatim
  completionProof.trustedSuccessCount   ASCII decimal
  completionProof.quotaRequired         ASCII decimal
  completionProof.ledgerRef             UTF-8 verbatim
requestDigest = lowercase hex of SHA-256(canonical)
```

**为什么这是承重的而不是登记**：幂等重放靠「同键 + 同 digest」判定「这是同一个请求」。若 `expectedCurrentItemId` 不在 preimage 里，那么**指向不同当前项的两个请求会得到同一个 digest**——重放检查会把它们认成同一个，于是返回旧 receipt 或直接放行，**这正是 wrong-item 与 double advance**。`expectedScheduleVersion` 同理对应 skip。换句话说：**digest 漏掉哪条前置，§6.7.4 就少哪一条防护**，而表面上三个 wire code 一个不少。

`idempotencyKey`、`callerProtocolVersion` 与 `completionProof.verifiedAtElapsedRealtimeMs` **不进** preimage：前者是查找键（进 preimage 会自我指涉），第二个按 §6.3.4 已冻结的理由排除（重试期间调用方升级不得变成伪冲突）；**第三个是「配额在何时被测量」的审计元数据，不是「在报告哪一次完成」的身份**——重试若重新测量就会带上新时间戳、改变 digest、被当成另一个请求处理，于是 §6.4.2 唯一可比时钟反而成了重放的破坏者。**排除它是身份判定，不是省略**，因此写在这里而不只写在 KDoc 里：上一轮它只活在 `CanonicalDigestV1` 的注释里，本文只在字段表列出该字段、对它是否属于 digest 身份只字未提，读者据本节实现会直接把它算进去。

**长度前缀不是风格选择**：`expectedCurrentItemId`、`ledgerRef`、`scheduleItemId` 都是自由字符串。用任何固定分隔符拼接，都能让一个字段吞掉分隔符并移动边界——`itemId="a|b", ledgerRef="c"` 与 `itemId="a", ledgerRef="b|c"` 会产生**逐字节相同**的 canonical，于是两个不同的推进请求共用一个 digest。这与 §6.3.1 已经踩过的 `\n` 碰撞是同一个 bug，只是换了字段。**判据第十四条在这里再次适用。**

#### 6.7.3a 三个 advance DTO 的 exact schema（冻结）

§6.3 写明「未列字段不属于 v1」，所以这三个类必须与 §6.3 同形地给出 exact schema，而不是只有散文字段清单——否则它们既被实现又被否认，而门禁看不见。

```kotlin
@Parcelize
data class CompletionProofV1(
    val scheduleItemId: String,
    val trustedSuccessCount: Int,
    val quotaRequired: Int,
    val ledgerRef: String,
    /** 唯一可比时钟（§6.4.2）。**不进 `requestDigest` preimage**——审计元数据，非完成身份，见 §6.7.3。 */
    val verifiedAtElapsedRealtimeMs: Long,
) : Parcelable

@Parcelize
data class CompleteAndAdvanceRequestV1(
    val leaseId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val expectedScheduleVersion: Long,
    val expectedCurrentItemId: String,
    val completionProof: CompletionProofV1,
    /** 不进 requestDigest preimage（§6.7.3 与 §6.3.4 同一理由）。 */
    val callerProtocolVersion: Int,
) : Parcelable

@Parcelize
data class AdvanceReceiptV1(
    /** `AdvanceOutcomeV1.wire`，取值域见 §6.7.4；未知值必须 fail-closed。 */
    val outcomeWire: Int,
    val advancedFromItemId: String,
    /** 耗尽时为 null——终态，非失败。 */
    val advancedToItemId: String?,
    /** 始终 = expectedScheduleVersion + 1，无论 ADVANCED 还是 EXHAUSTED（v1.56 冻结）。 */
    val scheduleVersionAfter: Long,
    val effectiveIntentHash: String,
    val effectiveEnvironmentRevision: Long,
    val receiptDigest: String,
) : Parcelable
```

#### 6.7.4 前置条件即四类防护（compare-and-advance）

推进是**比较并推进**，不是无条件自增。四条前置各自对应一类事故：

| # | 前置条件不满足 | wire | 挡住的事故 |
|---|---|---|---|
| 14 | `expectedCurrentItemId` ≠ 实际 `currentItemId` | `SCHEDULE_ITEM_MISMATCH` | **错项推进**与**重复推进**（Auto 拿着过期的当前项发请求） |
| 15 | `expectedScheduleVersion` ≠ 实际 `scheduleVersion` | `SCHEDULE_VERSION_STALE` | Auto 判定配额**期间**计划被改：顺序已变，达标结论不再适用于同一项 |
| 16 | 计划**已经**耗尽时又请求推进 | `SCHEDULE_EXHAUSTED` | 越界推进。**没有可完成的当前项**，是真正的调用方错误 |
| 7 | 推进时设备上存在**任一**非 `RELEASED`/未收敛 lease（**不限本 caller**，§6.7.4a） | `LEASE_CONFLICT` | 在活动 lease 之下换掉设备全局环境——对持有者制造 `ENVIRONMENT_DRIFT` |

> **v1.37 更正——耗尽曾被定义了两次且互斥。** 上一版一处把耗尽写成 `SCHEDULE_EXHAUSTED` typed failure，一处又写成「终态不是失败」的 receipt + null target。**一次调用不可能同时是这两者**，实现者只能靠猜。分开被混为一谈的两种情形即可解开：
>
> - **完成最后一项是成功的。** 当前项确实被完成了；抛异常会让调用方把一次成功完成当成错误，并且很可能去重试它。返回 receipt，`outcomeWire = ADVANCE_OUTCOME_EXHAUSTED`、`advancedToItemId = null`。
> - **在已经耗尽之后再请求推进**才是调用方错误，仍为 `SCHEDULE_EXHAUSTED`（16）。
>
> 所以两者不冗余：**错误码说「没有东西可推进」，outcome 说「推进发生了，并且落在了末尾」**。`AdvanceReceiptV1.outcomeWire` 取值域就此冻结为 **`AdvanceOutcomeV1`**：`ADVANCED = 1` / `EXHAUSTED = 2`。**它是枚举而不是两个 `const`**——第一版写成 const，于是没有任何 carrier 和任何门禁看得见它：改一侧、全绿。改成与其它契约枚举同形后，它自动继承 `compatibility.yaml ↔ Kotlin` 绑定与 derived unknown-probe 覆盖。未知 outcome **必须 fail-closed**（`advancedOrFailClosed`）：把未知乐观读成「已推进」会让 Auto 在完全不知道发生了什么的情况下相信计划已前移，并把结果归因到错误的项——§6.7.5 要防的错环境归因，从 outcome 字段而不是 observation 进来。

任一条不满足 → **不推进、不改指针**，返回 typed failure。「跳项」不需要单独 wire：跳项要么撞 14，要么撞 15。

> **v1.54 冻结——耗尽状态模型（`exhausted` 独立判别位）。** schedule 的运行时状态冻结为 `(currentItemId: 非null String, exhausted: Boolean)`，三态如下：
>
> | 态 | 表示 | `completeAndAdvance` 应答 |
> |---|---|---|
> | 无 schedule（DB 空 / 未 init） | `scheduleSnapshot()` = null / 空 items | `REQUEST_INVALID(13)` "no active schedule" |
> | 尚未开始或进行中 | `currentItemId = 当前项`，`exhausted = false` | 正常 CAS 判定（§6.7.4b 步 4） |
> | 已耗尽 | `currentItemId = 末项`（**保持**），`exhausted = true` | `SCHEDULE_EXHAUSTED(16)` |
>
> **排除 null 指针作为耗尽表示**：① 与"尚未开始"态撞（首项 advance 在 `currentItemId = null` 上发 CAS 即踩空）；② 违反 M-AD-10 保持条款（receipt `advancedFromItemId` 需要它）；③ 制造 14-shadows-16 陷阱（null 不等于任何 `expectedCurrentItemId`，步内旧序 14 先于 16 时恒答 14，16 永不可达）；④ null-as-state 就是 §6.3.1 / round-5 刚杀掉的 sentinel 反模式。**排除指针越界魔值**：§6.7.3 明文取消了"ID 受限"假设，任何魔值 ID 都可能合法存在。**独立布尔**是显式状态判别位，与本 PR 五轮 review 烧进去的哲学（explicit discriminator / no sentinel / no unreachable value）同一坐标系。
>
> **Bit 生命周期**：仅由完成末项的 committed advance 置位（单写者 = §6.7.5 单提交协议 / CAS 事务边界内）——该事务**原子地**同时置 `exhausted = true`、保持 `currentItemId` 为末项、并递增 `scheduleVersion`（V→V+1），三者是同一提交的组成部分（v1.56 冻结 `scheduleVersionAfter = expectedScheduleVersion + 1`，与非末项推进同规则）。仅由**外部** schedule (re)initialization（operator 发起、不经 advance 协议）清零；该 (re)initialization **必须同时递增 `scheduleVersion`**（v1.57 冻结）——否则同拓扑 reset 把 `exhausted true→false` 而 version 不变，消费者持有的 `(currentItemId, scheduleVersion)` 对无法区分旧世代与新世代，stale proof 跨世代复用。advance 自身的原子版本递增不是清零触发，否则 `exhausted` 在写入它的同一事务即被清除而不可能存活。注意区分：receipt 的 `advancedToItemId = null` 是 **OUTCOME 编码**（已冻结，codec-native null），与 schedule **STATE 编码** `(currentItemId, exhausted)` 是两个问题——STATE 里没有 null。

##### 6.7.4b 判定次序（冻结）

多条前置可以同时不满足，因此**返回哪一个 typed failure 必须是确定的**。不冻结次序，两个实现会对同一请求返回不同的 code，而两者都"符合"上面的表——调用方的恢复策略就此不可移植。冻结为：

```
1. 外层安全门   未配对 / caller 不在 allowlist / protocol 不兼容 / 解码失败 / 形状非法
                → NOT_PAIRED(1) · CALLER_NOT_ALLOWED(2) · INCOMPATIBLE_PROTOCOL(3) · REQUEST_INVALID(13)
                并**由收到的字段重算 requestDigest**，与请求自带值精确比对，不符 → REQUEST_INVALID(13)
2. idempotency  同 key + 同「重算所得」digest → 返回原 receipt（终止，不再走后续任何一条）
                同 key + 异「重算所得」digest → IDEMPOTENCY_CONFLICT(12)
3. proof        缺 CompletionProofV1 / proof **内部**不自洽（含 `proof.scheduleItemId` ≠ `expectedCurrentItemId`）
                → REQUEST_INVALID(13)。**本步只判请求自身是否自洽，不与设备实际 `currentItemId` 比对**
4. schedule     **与实际 `currentItemId` 的 CAS 在本步、且只在本步判定**：
                16 SCHEDULE_EXHAUSTED → 14 SCHEDULE_ITEM_MISMATCH → 15 SCHEDULE_VERSION_STALE
5. lease        设备上存在任一非 RELEASED / 未收敛 lease（**不限本 caller**）→ LEASE_CONFLICT(7)
6. mutation     指针前移 + receipt 落库（同一事务，§6.7.5）

**第 2–6 步整体构成一个 serializable 事务／CAS 临界区，不是六个各自原子的步骤。** 指针写入必须以第 3–4 步**读到的** `currentItemId`／`scheduleVersion` 为 CAS 前置条件；幂等记录的查表与插入也在同一边界内。否则两个持**不同新 key** 的请求可以同时读到旧 `currentItemId`、同时通过 proof／schedule／lease 三门，再各自提交一次推进——**双推进由此发生，而每一次单独看都合法**。把「同一事务」只写在第 6 步，保证的是「指针与 receipt 不会半落」，**不是**「不会推进两次」；这两件事被同一个词遮住了。
```

四处次序不是任选，各有理由：

- **外层安全门必须先于幂等查表。** 幂等查表会**返回一份已存在的 receipt**——那是携带 `effectiveIntentHash` / `effectiveEnvironmentRevision` 的历史事实。若把它排在鉴权之前，一个已被撤销授权、或协议不兼容、或根本不该被受理的 caller，只要猜中一个 `idempotencyKey` 就能把别人的 receipt 取走。**幂等不是免检通道**：它只对"已经通过受理的同一个调用者的同一次调用"成立。
- **`requestDigest` 必须由 provider 用收到的字段重算，不得采信请求自带的那一份。** 自带值是**未受信输入**。只按 `(key, 自带 digest)` 查表，攻击者改掉 payload 的任意字段却原样回填 key 与 digest，即可命中旧条目并取回一份与当前 payload 毫无关系的 receipt——幂等键从"同一次调用的标识"退化成"任取一份历史结果的提货券"。重算并精确比对之后，digest 才真正绑定内容。
- **幂等查表先于 proof 与其余一切前置。** 一次成功推进之后 `currentItemId` 已经前移；崩溃重启的 Auto 以同键重放同一请求时，它携带的 `expectedCurrentItemId` 与 `proof.scheduleItemId` **必然都已过期**。若先跑 proof 或 schedule 门，重放会撞 `REQUEST_INVALID(13)` 或 14 而不是拿回原 receipt——**M-AD-02 与 M-AD-04 会塌成同一个可观察结果**，而它们恰恰必须可区分：前者是"你已经成功了，这是你的 receipt"，后者是"你丢了键并试图推第二次"。次序放反，防双推进的最后一道就变成了对合法重放的误杀。
- **schedule 门先于 lease 门。** 计划已耗尽（16）时根本没有可完成的当前项，lease 是否活动是无意义的问题；此时若先返回 `LEASE_CONFLICT`，Auto 会去 release 再重试，然后仍然撞上终态的 16——白跑一轮，且中途还释放了本不必释放的环境。反过来则不会有对称的浪费：lease 冲突在 release 后重试即可成功。
- **第 4 步步内次序：`16 SCHEDULE_EXHAUSTED` → `14 SCHEDULE_ITEM_MISMATCH` → `15 SCHEDULE_VERSION_STALE`。** 三条推理各自充分，联合必要：
  1. **规范自身先例。** schedule 门先于 lease 门的冻结理由是"已耗尽时没有可完成的当前项，lease 是否活动是无意义的问题"——**同一论证在步内同样成立**：耗尽让 item/version CAS 成为无意义的问题。步间已裁定 16 先于 7，步内同理裁定 16 先于 14/15。
  2. **恢复语义诚实性。** 14（`SCHEDULE_ITEM_MISMATCH`）和 15（`SCHEDULE_VERSION_STALE`）的恢复语义承诺"修正期望后重试**可成功**"；耗尽后任何修正都不可能成功——答 14/15 是对可恢复性的谎报，Auto 会走一轮必然终结于 16 的 discover → 重试白跑。正是 v1.39 拒绝 lease-first 的那种浪费，只是从步间搬到步内。
  3. **确定性。** 16-first 让**每一个**耗尽后的 fresh 请求无条件精确答 16，无论期望多陈旧——M-AD-11 的"稳定返回 `SCHEDULE_EXHAUSTED`"从条件命题变成恒真。**唯一行为 delta = 耗尽后陈旧期望：旧答 14，新答 16**，恰好就是被修的缺陷本身。现有 14→15 相对序不重开。

**第 5 步的 lease 门是设备全局的，不是"本 caller 的"。** 推进改变的是**设备上生效的环境**，而不是某个 caller 私有的东西。只挡本 caller 自己的 lease，则 caller B 持有 lease 的同时 caller A 可以合法推进，于是 B 的环境在它脚下被换掉——这正是 §6.6 的 lease 机制存在要防的形态，只是换了个入口。因此该门必须与并发 `apply` **以及并发 `completeAndAdvance`** 双向**串行化**（只串 `apply` 则两个并发推进各自读到无 lease、各自放行，门形同虚设）：推进期间不得有新 lease 被授予，已存在的任一非 `RELEASED` / 未收敛 lease（无论属于谁）都必须阻断推进。

**未在本次序中出现的失败一律不得提前返回。** 特别是 `INTERNAL_FAILURE(11)` 不得用来代替上述任一条——把调用方错误伪装成服务端故障，正是 §6.3.3 引入 `IDEMPOTENCY_CONFLICT(12)` 与 `REQUEST_INVALID(13)` 时要消除的形态。

#### 6.7.4a release 与 advance 的顺序，以及 lease 是否跨项（冻结）

**顺序：先 release，再 advance。**

`apply` 拿到的 lease 绑定的是**当前项的环境**。推进会改变生效环境——若在持有该 lease 期间推进，provider 就会在一个**活动 lease 之下**换掉环境，这正是 `ENVIRONMENT_DRIFT`（wire 9）存在要拦的形态，只不过由我们自己制造。所以：

```
QUOTA_COMMITTED → BEGIN_RELEASE → RELEASE_PENDING → RELEASED
                → completeAndAdvance → observe（独立验证新环境）→ 下一项 apply

非终末推进如上。**终末（exhausted）推进**不经 observe，改为：
                → completeAndAdvance → discover()／preflight() 独立回读 schedule 状态（§6.7.5 v1.58 三条腿）→ CLOSED
```

**lease 不跨项。** 一个 lease 属于一个 schedule item 的环境；推进即意味着该 lease 的环境不再有效。因此推进时**不得**存在任何活动 lease——**包括其它 caller 的**。推进改变的是设备上生效的环境，不是调用者私有的东西：只挡自己那一个，caller B 持有 lease 时 caller A 仍可合法推进，B 的环境就在它脚下被换掉。该门必须与并发 `apply` 串行化。

**违反时返回 `LEASE_CONFLICT`（wire 7）。冻结，不新增 wire。** 上一版只冻结了禁令而没有冻结违反它的 typed code，这不是遗漏细节而是同一类契约缺陷：`LEASE_CONFLICT` / `REQUEST_INVALID` / 新码三种读法都自洽，红测只能接受"任意 `ContractException`"，于是**对错误的 wire 也会全绿**——禁令有了规则却没有可执行的判据。逐条排除：

- **不是 `REQUEST_INVALID`（13）。** 请求字节完全合法：同一份字节在 release 之后重发即应成功。13 的语义是结构性非法请求，重试无用；这里重试恰恰有用，只是要先 release。（**wire 4 是 `CAPABILITY_UNAVAILABLE`，与本条无关**——见 §6.3.3 表。）
- **不是 `STALE_LEASE`。** `CompleteAndAdvanceRequestV1.leaseId` 按本节就是一个**已 RELEASED 的历史引用**，"lease 已过期"是本请求的**正常形态**而非错误。用它会把唯一合法的调用形态判成失败。**为此 §6.3.3 对 8 的定义必须同步收窄**：该行把"已 `RELEASED`"无条件列为 8 的判据，且只标注了 `release` 一处例外，按字面 `completeAndAdvance` 也应返回 8。这与下一段对 7 的加宽是**同一条要求的两个方向**——本轮若只加宽 7 而不收窄 8，矛盾只是从这一行挪到相邻一行。见 §6.3.3 wire 8 行。
- **不是 `ENVIRONMENT_DRIFT`（9）。** 9 是事后观测到环境已漂移；本条是**事前**阻止漂移发生的预防门禁。用 9 等于承认漂移已经发生，而这道门的全部意义就是不让它发生。
- **是 `LEASE_CONFLICT`（7）。** §6.3.3 对 7 的恢复语义是"时序冲突，释放后可重试"（见该节 `IDEMPOTENCY_CONFLICT` 对照段）——与本条**逐字吻合**：release 之后重试即成功。Auto 已有的 7 恢复分支可直接复用，不必为一个语义相同的新码再写一条。

**为此 §6.3.3 对 7 的定义必须同步加宽。** 原定义是"与**另一** caller 或**另一** intent 的 active/未收敛 lease 冲突"——按字面，caller **自己**在推进时持有的活动 lease 不在其中。若只在本节写"返回 7"而不动 §6.3.3，两节就会互相矛盾，实现者按 §6.3.3 读会认为本场景不该返回 7。**定义与用法必须同向**，见 §6.3.3 wire 7 行。

**那么 `CompleteAndAdvanceRequestV1.leaseId` 是什么？** 它是**配额在哪个 lease 下挣得**的历史引用，用于归因与审计，**不是一个活动持有**。这一点必须写死：不写死的话，实现者会合理地把它读成「推进时仍持有该 lease」，于是要么在活动 lease 下换环境，要么在 release 之后拿一个已 RELEASED 的 id 去做前置校验而失败——两种读法都自洽，所以歧义本身就是缺陷。

#### 6.7.5 崩溃恢复与 reconcile

- **推进与 `currentItemId` 指针必须在同一事务**：重启后只能观察到「已推进」或「未推进」，不存在中间态。**该事务边界必须同时包住幂等记录的查表／插入与 proof／schedule／lease 三门的谓词读**（§6.7.4b 第 2–6 步），且指针写入以谓词读到的值为 CAS 前置。只包住「指针 + receipt」解决的是**崩溃原子性**，解决不了**并发原子性**——后者才是双推进的入口。
- **Auto 崩溃**：以同 `idempotencyKey` 重放，拿回同一 receipt；receipt 必须持久且可按键重取，否则「重试」等于「再推一次」。
- **千网游崩溃**：Auto 未拿到 receipt 时**不得假定已推进**——无 receipt 即未推进（§5 禁止项）。
- **非终末推进后必须独立验证**：Auto 以 `observe()` 独立确认新生效环境，与 receipt 里的 `effectiveIntentHash` / `effectiveEnvironmentRevision` 比对。**receipt 是对方的自述，不是生效证据**；只信 receipt 就会把「说推进了」当成「环境真的换了」——这正是错环境归因的入口。**该比对的状态机形态冻结在 §8.1 的 `OBSERVED_TUPLE_MATCHES` / `OBSERVED_TUPLE_MISMATCH` 两条边**，三条腿必须合取；两节互为定义与用法，改一处必须改另一处（v1.42「定义与用法必须同向」）。本节此前只在散文里要求三条腿，而 §8.1 的边只比 `scheduleItemId` 一条——**要求写在这里，实现读的是那里**，于是少比的两条腿没有任何载体会报错。
- **终末（exhausted）推进免除 observe**（v1.57 冻结；**v1.58 收窄：豁免的只是「环境比对」这一步，不是「独立验证」本身**）。前两条理由说明为什么 `observe()` 这个载体不适用；第三条说明为什么豁免**不能**扩大成「只信 receipt」：①**环境不变**——终末推进保持 `currentItemId` 为末项、环境不切换，`observe()` 看到的环境与推进前一致，比对不产生新信息；②**结构上不可能**——`AdvanceReceiptV1.advancedToItemId = null`（终末 outcome 编码）而 `EnvironmentObservationV1.scheduleItemId: String`（非空），三条腿中 `scheduleItemId == advancedToItemId` 恒为 false，observe 后的 `OBSERVED_TUPLE_MATCHES` 边**永远无法命中**；③**receipt 仍不得成为唯一证据**——digest 重算只证明 provider 用**它自己填写的字段**做了 canonical framing 并与本请求绑定；它**不证明** `exhausted = true`、末项指针与 `V+1` 已真正持久化。可构造反例：provider 未落 `exhausted`（或回绕／错写 version），却返回一份字段内部自洽、digest 重算通过的 EXHAUSTED receipt——若据此直接落终态，Auto 会 CLOSED，而下一次 `discover()`／`preflight()` 仍把末项当可执行。**「receipt 是对方的自述，不是生效证据」对终末同样成立**，用「receipt 可验证」去豁免这条规则，等于拿它明确拒绝的东西当它的替代品。故冻结：**终末推进后必须以一次 fresh `discover()`／`preflight()` 独立回读 schedule 状态**，三条腿**合取**——`readback.currentItemId == receipt.advancedFromItemId` **∧** `readback.scheduleVersion == receipt.scheduleVersionAfter` **∧** `readback.exhausted == true`；任一不成立 → **`RECOVERY_REQUIRED`**，typed reason 必须记录是哪条腿。该回读走 v1.55 已冻结的公开投影（`CapabilitySnapshotV1.exhausted` / `PreflightReportV1.exhausted`），**不新增 wire、不新增字段**。状态机路径：`ADVANCE_PENDING → ADVANCE_EXHAUSTED_VERIFIED → ADVANCE_STATE_READBACK → CLOSED`（回读失配则 → `RECOVERY_REQUIRED`），**不经** `ADVANCE_OBSERVING`。

#### 6.7.6 Hook 与 System Mock 的编排等价

推进语义与实现模式**无关**：两种模式都必须产出同样形状的 receipt 和同样可独立验证的 `effectiveEnvironmentRevision`。模式差异只允许出现在千网游内部实现，不得削弱证据语义，也不得让某一模式跳过 §6.7.4 的任何一条前置。

### 6.8 兼容矩阵与握手

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
| `EnvironmentLease` | EnvironmentLeaseStore | leaseId、callerApplicationId、callerSignerDigest、`acceptedIntentHash`、`state`（§8.4 七态）、`applyIdempotencyKey`、`startingEnvironmentRevision`、`deadlineElapsedRealtimeMs`、**`applyOwnerGeneration`**（apply 时的 owner generation，用于判定单调值可比性；`startingEnvironmentRevision` 不能替代——revision 是持久单调计数，generation 是每次 owner 进程启动变化的另一根轴）、`releaseIdempotencyKey?`、`residualReasonWires`、`revokeSource?`、`recoveryEvidenceRef?` | 一个设备上的冲突 lease fail-closed；字段在此冻结，**不留给 Fable5 与 fake-qwy 各自发明** |
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
| `RELEASE_PENDING` | `RELEASE_RECEIPT`（**未提交配额**路径） | `CLOSED` | 保存 release receipt | release 别人的 lease |
| `RELEASE_PENDING` | `RELEASE_RECEIPT`（配额已提交但**未达标**：`count(TrustedQuotaEntry where taskId) < requiredSuccesses`，§7.3） | `CLOSED` | 保存 release receipt；**不推进**，当前项保留待下一 attempt | 把「提交了一次配额」当成「该项已达标」——`requiredSuccesses = 3` 的任务会在第 1 次 attempt 后就推进日程 |
| `RELEASE_PENDING` | `RELEASE_RECEIPT`（配额已提交且**已达标**：`count(...) >= requiredSuccesses`，§7.3） | `ADVANCE_PENDING` | 保存 release receipt + advance 幂等键与 `requestDigest`（**先 release 再 advance**，§6.7.4a） | 在**活动 lease 之下**推进——那是自己制造 `ENVIRONMENT_DRIFT` |
| `ADVANCE_PENDING` | `ADVANCE_RECEIPT_VERIFIED` | `ADVANCE_OBSERVING` | 保存 receipt（`receiptDigest` **已重算通过**才保存） | 收到 receipt 就认定环境已换 |
| `ADVANCE_PENDING` | `CRASH_RECOVER` | `ADVANCE_PENDING` | **同键**重放 `completeAndAdvance` 取回原 receipt | 换新键重发——会撞 `M-AD-04` 并可能双推进 |
| `ADVANCE_PENDING` | `ADVANCE_DIGEST_MISMATCH` | `RECOVERY_REQUIRED` | 拒绝该 receipt、暂停、告警 | 当作「弱一点的证据」继续 |
| `ADVANCE_PENDING` | `ADVANCE_EXHAUSTED_VERIFIED` | `ADVANCE_STATE_READBACK` | 保存耗尽 receipt（`outcomeWire = EXHAUSTED`、target 为 null；**`receiptDigest` 已重算通过才保存**）；**不落终态**——先独立回读 schedule 状态（§6.7.5 v1.58） | 当作失败去重试；或**跳过重算**直接落终态——§6.7.3 冻结「重算不上的 receipt 不是「弱一点的 receipt」，它不是 receipt」，而 `outcomeWire` 本身就在 preimage 里；或**据可验证的 receipt 直接 CLOSED**——digest 只证明 provider 为它自己填写的字段做了 framing，不证明状态已持久化 |
| `ADVANCE_STATE_READBACK` | `EXHAUSTED_STATE_CONFIRMED` | `CLOSED` | 记录**三条腿同时成立**（§6.7.5）：`readback.currentItemId == receipt.advancedFromItemId` **∧** `readback.scheduleVersion == receipt.scheduleVersionAfter` **∧** `readback.exhausted == true`；落终态，plan 正常完成 | 只比 `exhausted` 一条腿——会放过指针回绕与 version 错写；或用 receipt 自己的字段冒充回读值 |
| `ADVANCE_STATE_READBACK` | `EXHAUSTED_STATE_MISMATCH`（三条腿**任一**不成立） | `RECOVERY_REQUIRED` | **不落终态**、暂停、告警；typed reason 必须记录**是哪条腿**不成立 | 当作「弱一点的证据」继续；或因为 receipt 可验证就放行——receipt 正是本步要验证的对象，不能同时充当唯一证据源 |
| `ADVANCE_OBSERVING` | `OBSERVED_TUPLE_MATCHES` | `CLOSED` | 记录**三条腿同时成立**（§6.7.5）：`observation.scheduleItemId == receipt.advancedToItemId` **∧** `observation.acceptedIntentHash == receipt.effectiveIntentHash` **∧** `observation.environmentRevision == receipt.effectiveEnvironmentRevision` | **只比环境不比 item**——同一 profile 可跨项复用；**只比 item 不比环境**——item 对上了而环境根本没换，receipt 的自述就成了唯一依据，正是 §6.7.5 要堵的入口。两种单腿读法互为镜像，**必须三条腿合取** |
| `ADVANCE_OBSERVING` | `OBSERVED_TUPLE_MISMATCH`（三条腿**任一**不成立） | `RECOVERY_REQUIRED` | 判**错环境归因**：不计数、不继续；typed reason 必须记录**是哪条腿**不成立 | 继续下一项；或只在 item 失配时进恢复而放过 hash/revision 失配 |
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

`state` 此前只作为字段名出现，而 INV-14（release 只能清理本 caller 本 lease）与 INV-16（冲突 lease fail-closed）都以"什么算 active lease"为前提。不冻结它，**真 provider 与 `acceptance/fake-qwy` 这两条实现路径会各写一套 `state` 语义**，acceptance 就会在两套之间假绿。

> **归因更正（v1.22，由 GLM 指出）**：上一版把这条风险写成「**Fable5 的 provider 与 Sol 的 fake provider** 会各写一套」——即把风险归因于**两个不同作者**。2v2 后两者同属 Fable5，若照原句读，前提消失、结论似乎也就不必要了。**但结论仍然成立，因为它从来不依赖作者多样性**：风险的来源是**同一份契约存在两条独立实现路径**，无论由几个人写。冻结 `state` 消除的正是这个发散，与谁写无关。
>
> 作者身份只改变**发现难度**，不改变**必要性**——而且方向与直觉相反：**同一个人写两条路径时，两套语义更容易朝同一个方向一起漂移并互相自洽**，从而在 acceptance 里表现为「都过了」。因此 2v2 之下这条冻结不是变得可有可无，而是**更吃重**。

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
| INV-29 | **`applicationId` cutover 不得孤儿化用户可见状态。** 改 `applicationId` 即产生设备上另一个 App，其 sandbox 与备份域均不共享（§21 DP-2 一手依据）。因此 cutover 前必须二选一并留证：**(a)** 经可验证检查确认旧安装**不存在**持久用户状态 → 可直接 cutover；**(b)** 存在状态 → 必须先完成**版本化迁移桥**（覆盖 plan / task / attempt / result / session 及必要配置），通过数量与摘要校验，并具备可回滚验收，之后才移除旧 App。**禁止**用现有结果 CSV 冒充完整迁移（`AttemptCsvMapper` 只导出审计结果，`importCsv` 只导入 worklist）；**禁止**依赖跨 package 自动备份；**禁止**把 operator 数据复制进本仓库或任何日志 | **deferred gate — 证据载体见 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)，本文不声称已覆盖。** 该 issue 未闭合前，**任何 `applicationId` mutation / operator cutover / 旧 App 移除 / release candidate 一律阻断**；本 spec 的 §10 台账因此**不含** `appid-cutover` 行 |

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
| `M-AD-01` | advance | 无 `CompletionProofV1`，或 proof 的 `scheduleItemId` ≠ **本请求的 `expectedCurrentItemId`**（proof **内部**不自洽） | typed `REQUEST_INVALID`；**不推进**、指针不动。判据是**请求自洽性**，不是与设备实际当前项比对——后者属 `M-AD-04`／wire 14，若本行仍写「≠ 当前项」，丢键重发会同时命中两行而按次序恒落 13，`M-AD-04` 永不可达 | 4,13 |
| `M-AD-02` | advance | 同 key + 同 digest 重放（Auto 在收到 receipt 前崩溃） | 返回**原 receipt**，**不产生第二次推进**；指针只前移一次 | 13,15 |
| `M-AD-03` | advance | 同 key + **异** digest（前置被改） | typed `IDEMPOTENCY_CONFLICT`；不推进 | 13 |
| `M-AD-04` | advance | **新** key 重发同一次完成（Auto 丢了幂等键） | `expectedCurrentItemId` 已过期 → typed `SCHEDULE_ITEM_MISMATCH`；**这是防双推进的最后一道**，不依赖 Auto 记得键 | 13,15 |
| `M-AD-05` | advance | `expectedCurrentItemId` 指向非当前项 | typed `SCHEDULE_ITEM_MISMATCH`；不推进 | 15 |
| `M-AD-06` | advance | 判定配额期间计划被改（版本前移） | typed `SCHEDULE_VERSION_STALE`；不推进；Auto 需重取当前项重来 | 15,17 |
| `M-AD-07` | advance | 千网游已推进、Auto 未收到 receipt（连接中断） | **无 receipt 即视为未推进**；Auto 以同键重放取回原 receipt。**禁止**据「环境看起来变了」推断已推进 | 13,15,23 |
| `M-AD-08` | advance | receipt 取回但 `receiptDigest` 重算不上 | **不是弱证据，是无证据**：拒绝该 receipt、不前移本地状态、告警 | 13,23 |
| `M-AD-09` | advance | **非终末**推进后 `observe` 的 `scheduleItemId` ≠ receipt 的 `advancedToItemId` | 判**错环境归因**：不计数、不继续；同一 profile 可跨项复用，故环境相符**不足以**替代本条 | 8,23,26 |
| `M-AD-10` | advance | 最后一项完成 | receipt `outcomeWire = EXHAUSTED`、`advancedToItemId = null`、`scheduleVersionAfter = expectedScheduleVersion + 1`；**终态非失败**。`currentItemId` 保持末项、`exhausted` 置 `true`、`scheduleVersion` 递增 V→V+1、不回绕——三态均在同一 CAS 事务内原子提交。restart 后 bit + 指针 + 版本三存活（§6.7.5 事务持久性）。状态模型见 v1.54 冻结，版本规则见 v1.56 冻结 | 17 |
| `M-AD-11` | advance | 已耗尽后再次请求推进 | typed `SCHEDULE_EXHAUSTED(16)`，**无条件精确 16**（§6.7.4b 步内序 16→14→15）；与 M-AD-10 是两件事。三变体：(a) 保持项期望→16；(b) 历史 non-null 期望（如耗尽于 item-3 后期望 item-1，fresh key）→**精确 16**（v1.54 步内重排的核心行为 delta）；(c) 耗尽与请求之间 restart（判别位持久）→16 | 17 |
| `M-AD-12` | advance | 推进时设备上存在非 `RELEASED` lease——**本 caller 自持**与**另一 caller 持有**两种情形各测一遍 | typed `LEASE_CONFLICT`（§6.7.4a）；**不推进、指针不动**；release 后同一份请求字节重试即应成功。断言必须钉 **exact wire 7**，接受"任意 typed failure"不构成证据 | 14,16,28 |
| `M-AD-13` | advance | 同时违反多条前置（如既持活动 lease 又 `expectedScheduleVersion` 过期；或 exhausted + 陈旧 item + 陈旧 version） | 按 §6.7.4b **冻结次序**返回**首条**命中者：跨步例为 `SCHEDULE_VERSION_STALE`（步 4 先于步 5）；步内例为 `SCHEDULE_EXHAUSTED`（步 4 内序 16→14→15）。次序若被实现成任选，本行必红 | 15,16,28 |
| `M-AD-14` | advance | 配额**已提交但未达标**（`count(TrustedQuotaEntry) < requiredSuccesses`）时 release 完成 | **不推进**、当前项保留待下一 attempt；推进请求根本不得发出。把「提交过一次」读成「已达标」会让 `requiredSuccesses = 3` 的任务在第 1 次 attempt 后推进 | 10,11 |
| `M-AD-15` | advance | 配额提交后、达标判定前崩溃 | 恢复**先 reconcile 重算达标**再决定是否推进，禁止直接推进下一项（INV-15） | 10,15 |
| `M-AD-16` | advance | **耗尽** receipt 的 `receiptDigest` 重算不上 | 拒绝该 receipt、**不落终态**、进恢复；与 `ADVANCED` 同等对待——`outcomeWire` 本就在 preimage 内，耗尽 receipt 同样可验证 | 17 |
| `M-AD-17` | advance | **非终末**推进后 `observe` 的 `acceptedIntentHash` ≠ receipt 的 `effectiveIntentHash`（**item 对得上**） | `OBSERVED_TUPLE_MISMATCH` → `RECOVERY_REQUIRED`；typed reason 必须指明是 intentHash 这条腿 | 23 |
| `M-AD-18` | advance | **非终末**推进后 `observe` 的 `environmentRevision` ≠ receipt 的 `effectiveEnvironmentRevision`（**item 与 hash 都对得上**） | 同上；typed reason 必须指明是 revision 这条腿。本行与 `M-AD-17` 分立：单腿读法会各自放过对方 | 25 |
| `M-AD-19` | advance | 跨新分叉的同键重放（未达标路径与已达标路径各一次） | 可信配额保持幂等、最多增加一次；不得产生第二次推进 | 10,13 |
| `M-AD-20` | advance | **合法序列（非终末推进）**：release 后以已 `RELEASED` 的历史 `leaseId` 调 `completeAndAdvance`，随后以**同一** `leaseId` 调 `observe`。终末推进不走本序列（改走 §6.7.5 v1.58 独立回读，其载体 `discover()`／`preflight()` 不受本 lease 窗口约束） | 两者都必须被受理，`observe` 返回新生效环境；仅当该 caller 已获授新 lease 或 `leaseId` 非该历史引用时才 `STALE_LEASE`(8)。**这是 §6.7.4a 序列唯一的合法形态，此前 `M-AD-01..13` 无一行断言它** | 28 |
| `M-AD-21` | advance | **两个不同新 key** 的 `completeAndAdvance` 并发到达，均在指针前移前读到同一 `currentItemId` | **恰好一次**推进，**不得两次都成功**。loser 的 typed code 按争用项是否末项**分叉**：**非末项** → winner 提交后 `currentItemId` 前移 + `scheduleVersion` 递增 + `exhausted` 保持 `false`，loser 重进 CAS 临界区后 16 不命中、`expectedCurrentItemId ≠ actual` 先于 version 判定命中，**精确 `SCHEDULE_ITEM_MISMATCH(14)`**（非 15——winner 同时移了 item 与 version，但步内序 14 先于 15，item 失配已确定答案）；**末项** → winner 置 `exhausted = true`、`scheduleVersion` 递增、且**指针保持末项**，故 loser 的 item CAS 恒过、在步 4 内序首命中 `SCHEDULE_EXHAUSTED(16)`（**不是** 14）。不分叉则本行与 §6.7.4b 步内序 16→14→15 直接矛盾。单看每一次都合法，所以必须由事务边界而非请求内容拦截 | 15,16 |
| `M-AD-22` | advance | 在**谓词读与指针提交之间**注入故障（进程被杀／事务中止） | 恢复后只能观察到「已推进」或「未推进」，且幂等记录与 receipt 与指针三者状态一致；**不得出现指针已动而幂等记录未落**（后果是同一次完成可再推进一次） | 13,15,16 |
| `M-AD-23` | advance | **终末推进后**只拿到一份可验证的 EXHAUSTED receipt，但 provider 实际未落 `exhausted`（或指针回绕／`scheduleVersion` 错写） | Auto **不得**据 receipt 落终态：必须 fresh `discover()`／`preflight()` 独立回读，三条腿合取 `currentItemId == advancedFromItemId` **∧** `scheduleVersion == scheduleVersionAfter` **∧** `exhausted == true`；任一不成立 → `EXHAUSTED_STATE_MISMATCH` → `RECOVERY_REQUIRED`，typed reason 指明是哪条腿。**digest 重算通过不构成状态证据**——receipt 是被验证对象，不能同时是唯一证据源 | 17 |
| `M-AD-24` | advance | **同拓扑**外部 schedule (re)initialization（成员/顺序/优先级均未变）清 `exhausted true→false` | 必须**同时**递增 `scheduleVersion`（§6.7.1 v1.57）：reset 后 `(currentItemId, scheduleVersion)` 不得与 reset 前相等。否则消费者持有的旧 `(expectedCurrentItemId, expectedScheduleVersion)` 与 proof 可跨世代复用，把上一世代的完成算进新世代 | 15,17 |
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

Task 7 此前同时承诺三件事：验收方覆盖 §10 全部行、测试只消费 public v1 contract、验收方不写 Auto core。**这三件在当前结构下不可能同时成立**——ledger 事务、migration、崩溃窗口、状态机边都是内部窗口，公开契约触达不到；验收方若要测只能写进 `apps/cellrebel-auto/**`，违反 owner matrix。

> **执行者更新（v1.20）**：本节的论证在 2v2 之前以 Sol 为验收方书写。论证本身不变——它约束的是**触达边界**，与执行者是谁无关；变的只是执行者：acceptance lane 的 26 行现由 **Fable5** 编写执行，Sol 转为 review-only（见下方 legacy label 映射与 §12.1）。

**决议：不为测试在生产代码里开 driver seam。** 生产面为测试而扩大，正是本 spec 反复拒绝的模式。改为按证据类型逐行分工，并让分工**可被构建证明**。

**行 ID 规则**：ID 已显式写入 §10 表首列，**一经分配永不重排、永不复用**。新增行取该类别前缀下未使用的下一个序号；删除行时 ID 退役而不回收。**禁止**由行序推导序号——插入一行就会让既有 ID 整体错位，令历史证据失效。

**evidence class**：

| class | 谁写 | 触达方式 |
|---|---|---|
| `owner-red` | 代码 owner 在自己的 unit test 内 | 进程内 fake + 在 durable write 与外部调用之间注入故障 |
| `sol-blackbox` | **Fable5** | 只经 public v1 contract + `acceptance/fake-qwy` |
| `static-guard` | **Fable5** | `acceptance/scripts/` 下的静态扫描，无运行时 |
| `device` | **Fable5**（授权 device lease 内） | exact-build 真机证据 |

**`sol-blackbox` 是冻结的 legacy label，不是 owner 声明（冻结）**：该字符串在 §10.1 的 22 个行 ID、[Issue #6](https://github.com/TERRYYYC/fakexxx/issues/6) 与未来 `verify-a-plus.sh` 的解析里都是稳定 key，**重命名会让既有行 ID 与历史证据失配**，因此按 §10.1「行 ID 一经分配永不重排、永不复用」的同一理由保留原字符串。它现在只表示**触达方式 = 黑盒、只经 public v1 contract**，与执行者身份无关。**legacy label → 执行者的映射（唯一真相）**：`sol-blackbox` / `static-guard` / `device` 三类共 26 行的编写与执行者一律为 **Fable5**（operator 2v2；[#6](https://github.com/TERRYYYC/fakexxx/issues/6) T0：*Fable5 implements the 26 acceptance-lane rows*）。**任何位置读到 `sol-blackbox` 都不得推导出 "Sol 写这一行"**；owner 列是唯一权威，label 只描述触达方式。

**owner 列 = `evidenceOwner`，不是 `fixOwner`（v1.21 冻结）。** 上一版把 owner 定义成"若该行失败，谁必须改代码"，在 2v2 下这个定义已经坏掉：`M-RL-01` 由 Fable5 在 acceptance lane 证明 **Auto** 正确处置 typed error——若它红了，要改代码的是 **Opus5**，不是 Fable5。**一列同时承载「谁写证据」与「谁改代码」，失败就无法路由**：evidenceOwner 修不了，fixOwner 不知道该自己修。

因此拆成两个概念，只有前者进表：

- **`evidenceOwner`（= §10.1 的 owner 列，每行唯一）**：谁编写、执行该行，并对该行的证据与 `exactHead` 绑定负责。
- **`fixOwner`（不进表，由下列规则派生）**：该行红时谁必须改产品代码。

**失败路由规则（冻结，逐条判定，先命中先适用）**：

| # | 条件 | `fixOwner` |
|---|---|---|
| **0** | **证据载体自身故障**——断言写错、harness/fake 有 bug、fixture 过期或损坏、device 环境/lease 异常、evidence manifest 生成有误 | **`evidenceOwner`**（修证据，**不是**修产品）。**本条先于其余所有条判定** |
| 1 | class = `owner-red` | 就是 `evidenceOwner`（定义上二者同一） |
| 2 | 断言只涉及 Auto 侧可观察行为 | **GLM**（option B 起；`apps/cellrebel-auto/**` 已转 GLM，见 §12.1） |
| 3 | 断言只涉及千网游 provider 侧可观察行为 | **Fable5** |
| 4 | 断言涉及两侧交互 | `evidenceOwner` **必须先产出定位证据**（哪一侧先违反 v1 contract），再按 2/3 路由 |
| 5 | 第 4 条定位不能判定 | **保持红，转联合 triage**；**不得**自动改 contract。只有联合 triage **明确判定 contract 欠定义**后，才路由 Opus5 改 `contracts/**`（`contracts/**` 仍归 Opus5，未随 option B 转移）。**triage 席位按 no-self-review 排除作者**：涉及 `apps/cellrebel-auto/**`（GLM 作者）的红由 **Sol** 单独 triage，GLM 不入席；涉及 `apps/qianwangyou/**` 或 `acceptance/**`（Fable5 作者）的红由 **DeepSeek Flash + Sol** triage；涉及 `contracts/**`（Opus5 作者）的红由 **Sol** triage。**GLM 不入任何 triage 席位**（见 Epic #1 control event [5258691694](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5258691694)）。**任何情形下作者本人不得裁定自己 lane 的红** |
| 6 | class = `static-guard`（扫出越界路径） | 该路径在 §12.1 owner matrix 上的 owner |

**第 0 条是必经的第一步（v1.23 冻结）**：任何 red 在进入 2–5 之前，`evidenceOwner` 必须先完成 **evidence-validity triage** 并留证——证明这条红反映的是**产品行为**，而不是证据链自身坏了。

> **上一版这张表默认「红 ⇒ 产品有问题」，漏掉了整整一类原因。** 一条 acceptance 行变红，至少有三种解释：产品错、**证据错**、以及两者都对但契约欠定义。上一版只写了第一和第三种，于是把「证据错」静默归并进了产品故障，会让人去改一个本来正确的实现。
>
> **第 5 条上一版的推理也不成立**：它写「两侧都自认合规却仍不兼容，唯一剩余解释是 contract 欠定义」——**「自认合规」不是证据**，而且在排除第 0 条之前，「定位不出来」最常见的原因恰恰是证据链坏了。把"无法定位"自动升级成"改契约"，等于让最难判定的情形去动最难回退的东西（contract 冻结后改动会停三路、要求全部重新冻结）。**无法定位时正确的动作是保持红并升级到人，不是降级到改契约。**

**两条硬约束**：

- **`evidenceOwner` 不得因为"我修不了"而把该行标 `not-testable` / `deferred` / 降级**——那是把路由失败伪装成覆盖上限。该行**保持红**直到 `fixOwner` 修复；红行不满足任何 lane 的终门。
- **路由本身要留证据**：适用第 4/5 条时，定位证据（哪一侧、哪条 contract 条款）必须进 PR evidence，否则该行视为未定位、仍然红。**不允许口头指认对端。**

对端的消费行为仍由另一条独立行覆盖（例：`M-CC-03` 由 Fable5 证明 provider 拒绝冲突 lease），因此每行只有一个 `evidenceOwner` 与一个入口，不存在共管；但**共管的缺席不等于 fixOwner 自动等于 evidenceOwner**——那正是上一版的错误。

| ID | 类别 | evidence class | owner | 精确入口 |
|---|---|---|---|---|
| `M-AD-01` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_01` |
| `M-AD-02` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_02` |
| `M-AD-03` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_03` |
| `M-AD-04` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_04` |
| `M-AD-05` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_05` |
| `M-AD-06` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_06` |
| `M-AD-07` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_07` |
| `M-AD-08` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_08` |
| `M-AD-09` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_09` |
| `M-AD-10` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_10` |
| `M-AD-11` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_11` |
| `M-AD-12` | advance | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/AdvanceMatrixTest.kt::M_AD_12` |
| `M-AD-13` | advance | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/AdvanceMatrixTest.kt::M_AD_13` |
| `M-AD-14` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_14` |
| `M-AD-15` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_15` |
| `M-AD-16` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_16` |
| `M-AD-17` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_17` |
| `M-AD-18` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_18` |
| `M-AD-19` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_19` |
| `M-AD-20` | advance | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/AdvanceMatrixTest.kt::M_AD_20` |
| `M-AD-21` | advance | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/AdvanceMatrixTest.kt::M_AD_21` |
| `M-AD-22` | advance | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/AdvanceMatrixTest.kt::M_AD_22` |
| `M-AD-23` | advance | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt::M_AD_23` |
| `M-AD-24` | advance | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/AdvanceMatrixTest.kt::M_AD_24` |
| `M-CR-01` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_01` |
| `M-CR-02` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_02` |
| `M-CR-03` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_03` |
| `M-CR-04` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_04` |
| `M-CR-05` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_05` |
| `M-CR-06` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_06` |
| `M-CR-07` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_07` |
| `M-CR-08` | crash | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CrashMatrixTest.kt::M_CR_08` |
| `M-CR-09` | crash | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/CrashMatrixTest.kt::M_CR_09` |
| `M-CC-01` | concurrency | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConcurrencyMatrixTest.kt::M_CC_01` |
| `M-CC-02` | concurrency | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConcurrencyMatrixTest.kt::M_CC_02` |
| `M-CC-03` | concurrency | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/ConcurrencyMatrixTest.kt::M_CC_03` |
| `M-CC-04` | concurrency | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/ConcurrencyMatrixTest.kt::M_CC_04` |
| `M-RC-01` | recovery | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/RecoveryMatrixTest.kt::M_RC_01` |
| `M-CO-01` | completion | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_01` |
| `M-CO-02` | completion | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_02` |
| `M-CO-03` | completion | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_03` |
| `M-CO-04` | completion | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_04` |
| `M-CO-05` | completion | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/CompletionMatrixTest.kt::M_CO_05` |
| `M-CO-06` | completion | `device` | Fable5 | `docs/acceptance/a-plus-device-matrix.md#M-CO-06` |
| `M-RC-02` | recovery | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/RecoveryMatrixTest.kt::M_RC_02` |
| `M-RC-03` | recovery | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/RecoveryMatrixTest.kt::M_RC_03` |
| `M-RC-04` | recovery | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/RecoveryMatrixTest.kt::M_RC_04` |
| `M-BP-01` | bypass | `static-guard` | Fable5 | `acceptance/scripts/check-forbidden-boundaries.sh::M-BP-01` |
| `M-BP-02` | bypass | `static-guard` | Fable5 | `acceptance/scripts/check-forbidden-boundaries.sh::M-BP-02` |
| `M-BP-03` | bypass | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/BypassMatrixTest.kt::M_BP_03` |
| `M-BP-04` | bypass | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/BypassMatrixTest.kt::M_BP_04` |
| `M-BP-05` | bypass | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/BypassMatrixTest.kt::M_BP_05` |
| `M-BP-06` | bypass | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/BypassMatrixTest.kt::M_BP_06` |
| `M-BP-07` | bypass | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/BypassMatrixTest.kt::M_BP_07` |
| `M-RL-01` | release | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/ReleaseMatrixTest.kt::M_RL_01` |
| `M-VS-01` | version | `device` | Fable5 | `docs/acceptance/a-plus-device-matrix.md#M-VS-01` |
| `M-VS-02` | version | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/VersionMatrixTest.kt::M_VS_02` |
| `M-PA-01` | pairing | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_01` |
| `M-PA-02` | pairing | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_02` |
| `M-IN-01` | intent | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/IntentMatrixTest.kt::M_IN_01` |
| `M-IN-02` | intent | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/IntentMatrixTest.kt::M_IN_02` |
| `M-IN-03` | intent | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/IntentMatrixTest.kt::M_IN_03` |
| `M-IN-04` | intent | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/IntentMatrixTest.kt::M_IN_04` |
| `M-PA-03` | pairing | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_03` |
| `M-PA-04` | pairing | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/PairingMatrixTest.kt::M_PA_04` |
| `M-PA-05` | pairing | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/PairingMatrixTest.kt::M_PA_05` |
| `M-MG-01` | migration | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_01` |
| `M-MG-02` | migration | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_02` |
| `M-MG-03` | migration | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_03` |
| `M-MG-04` | migration | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_04` |
| `M-MG-05` | migration | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/MigrationMatrixTest.kt::M_MG_05` |
| `M-MP-01` | multiproc | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/MultiProcessMatrixTest.kt::M_MP_01` |
| `M-MP-02` | multiproc | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/MultiProcessMatrixTest.kt::M_MP_02` |
| `M-MP-03` | multiproc | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/MultiProcessMatrixTest.kt::M_MP_03` |
| `M-BP-08` | bypass | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/BypassMatrixTest.kt::M_BP_08` |
| `M-BP-09` | bypass | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/BypassMatrixTest.kt::M_BP_09` |
| `M-PA-06` | pairing | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_06` |
| `M-PA-07` | pairing | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_07` |
| `M-PA-08` | pairing | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_08` |
| `M-PA-09` | pairing | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/PairingMatrixTest.kt::M_PA_09` |
| `M-PA-10` | pairing | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_10` |
| `M-PA-11` | pairing | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/PairingMatrixTest.kt::M_PA_11` |
| `M-TU-01` | tuple | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_01` |
| `M-TU-02` | tuple | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_02` |
| `M-TU-03` | tuple | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_03` |
| `M-TU-04` | tuple | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_04` |
| `M-TU-05` | tuple | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_05` |
| `M-TU-06` | tuple | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_06` |
| `M-TU-07` | tuple | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/TrustTupleMatrixTest.kt::M_TU_07` |
| `M-LS-01` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_01` |
| `M-LS-02` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_02` |
| `M-LS-03` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_03` |
| `M-LS-04` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_04` |
| `M-LS-05` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_05` |
| `M-LS-06` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_06` |
| `M-LS-07` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_07` |
| `M-LS-08` | lease | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/LeaseMatrixTest.kt::M_LS_08` |
| `M-LS-09` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_09` |
| `M-LS-10` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_10` |
| `M-LS-11` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_11` |
| `M-LS-12` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_12` |
| `M-LS-13` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_13` |
| `M-LS-14` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_14` |
| `M-LS-15` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_15` |
| `M-LS-16` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_16` |
| `M-LS-17` | lease | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/LeaseMatrixTest.kt::M_LS_17` |
| `M-ID-01` | idempotency | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/IdempotencyMatrixTest.kt::M_ID_01` |
| `M-ID-02` | idempotency | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/IdempotencyMatrixTest.kt::M_ID_02` |
| `M-ID-03` | idempotency | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/IdempotencyMatrixTest.kt::M_ID_03` |
| `M-RQ-01` | request | `owner-red` | Fable5 | `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/matrix/RequestMatrixTest.kt::M_RQ_01` |
| `M-RS-01` | response | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/ResponseMatrixTest.kt::M_RS_01` |
| `M-CF-01` | config | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConfigMatrixTest.kt::M_CF_01` |
| `M-CF-02` | config | `owner-red` | GLM | `apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/ConfigMatrixTest.kt::M_CF_02` |
| `M-PA-12` | pairing | `sol-blackbox` | Fable5 | `acceptance/scenarios/src/matrixTest/kotlin/matrix/PairingMatrixTest.kt::M_PA_12` |

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
- 清单本身进 PR evidence，**Fable5** 的矩阵报告消费它，而不是逐行手工声明。

Task 7 的表述同步改为：**Fable5** 负责 `sol-blackbox`/`static-guard`/`device` 三类的编写与执行，并对 `owner-red` 行做 **evidence audit**（核对报告中存在该 ID 的通过用例、绑定 exact HEAD、断言与该行预期终态一致）。**Fable5** 不写 `owner-red` 测试，也不再声称"为每一行提供失败场景"。**Sol 不编写、不执行本文任何一类矩阵行**——它对 Fable5 的 26 行与该 evidence audit 本身做独立 review（[#6](https://github.com/TERRYYYC/fakexxx/issues/6) T0：*Fable5 implements the 26 acceptance-lane rows and audits the remaining 64 owner rows*〔**逐字引用 Issue #6 的 T0，刻意不改**：该 `64` 是立 issue 当轮的值，现行为 **84**（§10.1 现算）。静默改写他人 issue 的原文会让引用与被引用方永久对不上——要么由 #6 的 owner 改 T0，要么保留原文并在此注明，二者都不是本文单方能做的**改数**〕；reviewers Sol + GLM）。

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
├── contracts/environment-control-v1/          # 本分支与实际文件系统逐项一致（v1.59 重新生成）
│   ├── README.md
│   ├── compatibility.yaml
│   ├── build.gradle.kts
│   ├── consumer-rules.pro
│   └── src/main/
│       ├── aidl/io/github/terryyyc/fakexxx/contract/v1/
│       │   ├── AdvanceReceiptV1.aidl
│       │   ├── ApplyReceiptV1.aidl
│       │   ├── ApplyRequestV1.aidl
│       │   ├── CapabilitySnapshotV1.aidl
│       │   ├── CompleteAndAdvanceRequestV1.aidl
│       │   ├── CompletionProofV1.aidl
│       │   ├── EnvironmentControlResultV1.aidl
│       │   ├── EnvironmentIntentV1.aidl
│       │   ├── EnvironmentObservationV1.aidl
│       │   ├── IEnvironmentControlV1.aidl
│       │   ├── ObserveRequestV1.aidl
│       │   ├── PreflightReportV1.aidl
│       │   ├── PreflightRequestV1.aidl
│       │   ├── ReleaseReceiptV1.aidl
│       │   └── ReleaseRequestV1.aidl
│       └── java/io/github/terryyyc/fakexxx/contract/v1/
│           ├── AdvanceReceiptV1.kt
│           ├── ApplyReceiptV1.kt
│           ├── ApplyRequestV1.kt
│           ├── CanonicalDigestV1.kt
│           ├── CanonicalIntentDigestV1.kt
│           ├── CapabilitySnapshotV1.kt
│           ├── CompleteAndAdvanceRequestV1.kt
│           ├── CompletionProofV1.kt
│           ├── ContractEnumsV1.kt
│           ├── ContractErrorCodeV1.kt
│           ├── EnvironmentControlResultV1.kt
│           ├── EnvironmentIntentV1.kt
│           ├── EnvironmentObservationV1.kt
│           ├── ObserveRequestV1.kt
│           ├── PreflightReportV1.kt
│           ├── PreflightRequestV1.kt
│           ├── ReleaseReceiptV1.kt
│           └── ReleaseRequestV1.kt
├── apps/
│   ├── cellrebel-auto/                 # subtree from Faketest@48d8ec9
│   │   └── app/src/{main,test,androidTest}/...
│   └── qianwangyou/                    # subtree from FakeGps-test@285e4ca
│       └── app/src/{main,test,androidTest}/...
├── acceptance/                         # Fable5 独占（2v2；Sol review-only）
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

**本树的边界（v1.59）**：`contracts/**` 分支与实际文件系统**逐项一致**，由本轮重新生成；其余分支（`apps/`、`acceptance/`、`scripts/`、`docs/`）是**示意**，不是真相源——例如 `scripts/` 实际已多于所列三条。**一份部分更新的清单会被当成完整清单读**，所以这条边界必须写下来而不是靠读者推断；文件所有权以 §12.1 为准，DTO 的 `.kt`／`.aidl` 齐备性以 `check-contract-v1.sh` 第 3 节的**派生**结果为准（它不读本树）。

### 12.1 Owner matrix

| Owner | 独占写入范围 | 可读依赖 | 禁止并行触碰 |
|---|---|---|---|
| Opus5 | `contracts/**`、`.github/**`、root `scripts/**`（**不含 `acceptance/scripts/**`**）、`docs/provenance/**`、ownership map；仅在串行 PR-2 修改两 App 的 Gradle contract 接线 | 全仓 | PR-3 开始后不触碰 `apps/qianwangyou/**`、`acceptance/**`；**option B 起不触碰 `apps/cellrebel-auto/**`**（该范围已转 GLM，见下行）。**具名例外一：Task 3.5 / PR-3.5**——可写 `docs/provenance/qwy-lint-baseline.md` 冻结的 exact 路径集 ∪ `res/values-en/strings.xml`，该集合 ⊆ `apps/qianwangyou/app/src/main/**` 且与 `integration/**` 不相交。**具名例外二：Issue #13 applicationId cutover**——`INV-29` 现为 deferred gate，本文不声称已覆盖它；cutover 落地时 Opus5 需写 Auto 的 flavor / `applicationId` / Manifest 面，与 GLM 的 `apps/cellrebel-auto/**` 独占**必然相交**，因此该 delta **必须与 GLM 串行**并在 #13 开工前重新冻结 exact 路径集，不得并行。**两处范围外仍绝对禁止**，由 Task 3.5 的越界断言机器判定 |
| Fable5 | `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/**`、对应 qwy tests、qwy Manifest/Gradle 的集成行；**`acceptance/**`（含 `acceptance/scripts/check-forbidden-boundaries.sh`）、`docs/acceptance/**`、验收 issue 与证据** | frozen contract、两 App | contract、Auto |
| Sol | **无独占写入范围（review-only）**：review verdict、语义/验收审查报告；**#3 的 line review**（本 thread）与 **#5 的独立线级 review**（在 #5 开发线内；见 Epic #1 control event [5259280778](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5259280778)）；**不在主线做 line code review**；若需补测试代码则单独 PR 并由 GLM 审 | 全仓 | 不修改正在审的作者 branch |
| GLM | **`apps/cellrebel-auto/**`（含 `app/src/main/AndroidManifest.xml`）——Issue #5，option B 起由 Opus5 转入**；**无任何 review 角色**——作 #5 作者期间不审 #4/#6/#7 亦不审 #5（见 Epic #1 control event [5258691694](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5258691694)）；若补测试代码则单独 PR | 全仓 | 不修改正在审的作者 branch；**不得 review 任何 lane**（作者期间无 review 席位）；不触碰 `contracts/**`、`apps/qianwangyou/**`、`acceptance/**` |

**为什么 `acceptance/**` 归 Fable5（2v2 冻结）**：黑盒约束的是**依赖边界**——acceptance 只能消费 public v1 contract + `acceptance/fake-qwy`，不得进入 Auto/qwy 内部实现；它**不要求测试作者与产品作者是不同个体**。铁律"no self-review"约束的是 **author/reviewer 个体**：Fable5 写、Sol + GLM 独立审，铁律成立。若把 acceptance 留给 Sol，则 Sol 既是 #6/#7 的作者又是它们的 reviewer，那才是真的 self-review。

并行成立条件（**随 operator option B 三线重新推导**）：Contract PR exact HEAD 冻结后，写入面收敛为**三个个体、四个不重叠目录**——GLM 的 `apps/cellrebel-auto/**`（#5），Fable5 的 `apps/qianwangyou/**/integration/**`（#4）与 `acceptance/**`（#6/#7），Opus5 的 `contracts/**` ∪ `.github/**` ∪ root `scripts/**` ∪ `docs/provenance/**`（#3/#13）。**三者两两之间目录无交集，因此三线可并行**——这比 2v2 时多了一条并行边，代价是 reviewer 席位变紧：GLM 既是作者又是 #4/#6/#7 的 reviewer，**它审的与它写的必须永不相交**，#5 的 reviewer 席位因此归 Sol。**唯一已知的目录相交是 Issue #13 的 cutover**（Opus5 需写 Auto 面），按 §12.1 具名例外二**串行**处理，不计入并行边。但 **PR-3 与 PR-5 现在同属 Fable5，二者不再是跨个体并行**：Fable5 必须串行推进，或在两个独立 worktree 内分别推进并各自绑定 exact HEAD，不得在同一 worktree 混合两个 PR 的改动。任何 contract delta 先停两路、回主 Thread 重新冻结，不允许各自兼容。

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

> **`applicationId` cutover 不在本 task。** DP-2 = B 的改名及其数据连续性载体（flavor / SAF 搬运通道 / canonical bundle / variant CI / `M-AC-01..05`）已拆出到 **[Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)**，完整规范只在该 issue 的 spec PR 里。本 task 因此**不修改 `apps/cellrebel-auto/**`**，两个 app 树在 PR-1 全程保持 pristine。`INV-29` 的 deferred gate 见 §9。

**RED:** 在空目标路径运行 `scripts/check-provenance.sh --stage import`，必须因两个 app 未导入和 SHA 未登记失败。

**GREEN:** 只从远端精确 SHA subtree 导入；记录源 URL、branch、SHA、导入 commit。不得读取本机脏 worktree 作为拷贝源。

**Verify:**

```bash
./scripts/check-provenance.sh --stage import
```

本 task 不修改任何 app 树（cutover 已拆到 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)），因此 Task 1 最终 HEAD 仍满足 `--stage import` 最强的那条断言：**当前 HEAD 的 app 树与上游 root tree 逐字节相同**。`.github/workflows/android-a-plus.yml` 的 provenance job 相应传 `--stage import`。

**规则（冻结）：由第一个实际修改 app 树的 PR 原子移动 workflow 那一行到 `--stage contract`。** 不把它钉死在某个具体 issue —— 上一版写成「第一次合法分叉在 #13」是错的：**Task 2 已经要改两 App 的 Gradle 与 Auto Manifest**（contract 接线 + `<queries>`），按当前 DAG 第一分叉就是 Task 2；若 lint 清债或 #13 更早落地，则由更早者移动。谁先改 app 树谁负责移动，并在该 PR 的 body 里记录移动前后的 stage。immutable import-commit anchor 在**任何 stage 都仍然被检查**，所以那次放宽的只是"当前树"，不是 provenance 本身。

**`--stage` 是必填的，没有默认值**（PR-1 实现如此）。原因是两种默认都有害：默认严格会让 PR-2/3/4 里第一次**合法**修改 app 源码就永久性地让 CI 变红；默认宽松则会静默丢掉 PR-1 最强的那条检查（当前 HEAD 树仍与上游逐字节相同）。因此由调用方声明 stage：

| stage | 检查内容 |
|---|---|
| `import` | 全部检查 + **当前 HEAD 树仍与上游 root tree 逐字节相同** |
| `contract` / `full` | 记录的 import commit 仍携带上游 root tree（**不可变锚点，任何 stage 都查**），但允许 app 树在其后合法演进 |

CI workflow 在 app 仍应保持 pristine 期间传 `--stage import`；**第一个实际修改 app 树的 PR 负责原子移动它到 `--stage contract`**（按当前 DAG 即 Task 2 的 contract 接线；若更早者出现则由更早者移动）。

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
- **Modify: `.github/workflows/android-a-plus.yml`** —— **本 task 是当前 DAG 上第一个实际修改 app 树的 PR**（两 App 的 Gradle 接线 + Auto Manifest `<queries>`），因此**由本 task 原子把 provenance job 那一行从 `--stage import` 移到 `--stage contract`**，并在 PR body 记录移动前后的 stage

**RED:** missing `verificationLevel`、枚举 ordinal 信任、未知 wire code、canonical digest 跨实现不一致、intent 绑定缺失、v1 字段语义漂移、旧/新版本不兼容矩阵均先写失败测试。

**GREEN:** 实现本文 §6 的 exact schema（含 §6.3.2 全部 DTO 与 §6.3.1 digest 算法）；v1 不引入泛化 command 或 Map payload。contract library `minSdk = 24`；两侧构建栈已核实一致（AGP 9.1.0 / Kotlin 2.2.10 / Gradle 9.3.1 / compileSdk 35 / Java 17），无需版本对齐工作。

**Verify:**

```bash
./scripts/check-contract-v1.sh                    # 全部 contract/compatibility tests PASS
./scripts/check-provenance.sh --stage contract    # 必须 exit 0（app 树已合法分叉）
./scripts/check-provenance.sh --stage import      # 预期 exit 1 —— 证明分叉真实发生、stage 移动是必需的

# 第四条：断言 workflow 真的被移动了（前三条都证明不了这件事）
# 取出 workflow 里所有调用 check-provenance.sh 的行；只要有任何一行不带 --stage contract 就失败。
# 这一条同时覆盖「残留裸调用」与「残留 --stage import」，不需要额外分支。
grep -c 'check-provenance\.sh' .github/workflows/android-a-plus.yml     # 必须 >= 1（调用点没被删掉）
! grep 'check-provenance\.sh' .github/workflows/android-a-plus.yml \
    | grep -qv -- '--stage contract'
```

第三条是**反向证据**：若它仍然 exit 0，说明本 task 并未真正改动 app 树，那么移动 workflow 就是错的。

**第四条是「迁移真的发生了」的机器断言，缺它则前三条全绿也证明不了 CI 被改。** 前三条跑的是**本地 checker**，而 CI 调用的是 **workflow YAML 里那一行**——两者完全解耦：workflow 可以继续传 `--stage import`（甚至裸调用），本地 Verify 依然全绿，而 required job 会红在 PR 上。这不是假设：PR #11 `eddf9729` 的 workflow 至今仍是裸 `./scripts/check-provenance.sh`，而 PR #10 已把 `--stage` 改为必填——**同一个仓里已经存在这条必失败路径**。

若实现方偏好 exact GitHub job gate 而非文本断言，允许替换，但必须满足同一谓词：**能在本地 Verify 阶段判定 workflow 当前传的 stage**，不得只靠人读 YAML。

**Checkpoint:** exact contract HEAD 回主 Thread；只有该 HEAD 获得独立 verdict 后，Task 3/4/5 才开始并行。

### Task 3 — 千网游 provider、配对与连续性

**Owner:** Fable5

**Reviewer:** Sol（语义）+ GLM（对抗）

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
./gradlew assembleDebug
# lint 在本 task 只做 ratchet（不得新增 error）——raw-green 是 Task 3.5 的终门，见下
../../scripts/check-inherited-lint-debt.sh qianwangyou

cd ../..
./scripts/verify-a-plus.sh --lane pr-3      # 39 行 owner-red，绑本 PR HEAD；裸调用会按 pr-6 全 114 行判红
```

**lint 门在本 task 是 ratchet，不是 raw-green（v1.21 消环）**：上一版让 Task 3 直接要求 `lintDebug` exit 0，而基线自带 23 个 inherited error、清债又被冻结在 **PR-3 合入之后**的 Task 3.5——两者互锁，谁都过不去。本 task 只需证明**没有新增** lint error；raw-green 由 Task 3.5 达成。

### Task 3.5 — 千网游 inherited lint raw-green 清债

**Owner:** Opus5（§12.1「PR-3 开始后不触碰 qwy」的**唯一具名例外**）

**Reviewer:** Sol（语义）+ GLM（对抗）

**时序（冻结）：** 必须在 **PR-3 合入之后**开始（避免与 Fable5 并行写 `apps/qianwangyou/**`），并在 **PR-6 之前**完成。

**Files:**

- Create: **`docs/provenance/qwy-lint-baseline-report.xml`** —— 在**冻结导入基线**（`FakeGps-test@285e4ca` 的 pristine 树）上跑 `lintDebug` 得到的原始报告，**且每个 `location/@file` 必须先规范化为 repo-relative** 再逐字节提交。这是授权集合的**指向物**。
- Create: `scripts/task35-scope-guard.sh` —— **生产 guard 的唯一可调用实现**（root `scripts/**` 是 §12.1 里 Opus5 的独占范围，Task 3.5 owner 亦是 Opus5，因此这是本 task 唯一合法的落点；**不放 `acceptance/**`——那是 Fable5 独占，且 I3.5 只依赖 I3，不能假定 sibling PR-5 已交付任何文件**）
- Create: `scripts/selftest-task35-guard.sh` —— 负例/正例控制，**调用上面同一个实现**（不得复制一份逻辑：两份实现会各自漂移，负例就不再证明生产 guard 的行为）
- Create: `docs/provenance/qwy-lint-baseline.md` —— 由上述报告**机械派生**，必须记录：① `generated-at-commit:` = qwy 冻结导入 SHA，**须等于 `./scripts/check-provenance.sh --print-import apps/qianwangyou` 的输出**（PR #10 提供的机器查询，从 checker 循环实际消费的同一个 `$IMPORTS` 打印——**不得改为解析脚本文本**，文本 parser 与 runtime 值会分叉）；② `report-sha256:` = **规范化后**报告的 SHA-256；③ 一段以 `# BEGIN allowed-paths` / `# END allowed-paths` 两行**注释哨兵**界定的清单，每行一条 exact 路径，**共 5 条**（用哨兵不用代码围栏：baseline 自身是 markdown，拿围栏当数据分隔符会与容器碰撞）。**必须先于任何源码修改单独提交。**
- Modify: **仅**上述 5 条 exact 路径

> **23 与 5 是两个不同的数（v1.25 更正，由 Sol 实测证伪）**：`lintDebug` 报的是 **23 个 Error instance**，但它们只落在 **5 个唯一文件**上（`NewApi`=9 / `MissingTranslation`=6 / `Range`=5 / `MissingPermission`=3 说的是 **instance** 分布），另有 54 个 warning-only 文件与授权集合无关。上一版把「条数 == 23」当成路径集合的判据，于是**把 warning-only 文件（如 `config/ConfigPrefsSync.kt`）重复填满 23 行也能全绿**——不改报告、不改 digest 就完成扩权。现在 **23 只用于校验 Error instance 数，5 才是授权路径集合的基数**；集合由报告派生，baseline 只是声明副本，二者必须**逐元素相等**。
- Create（若 `MissingTranslation` 需要）: `apps/qianwangyou/app/src/main/res/values-en/strings.xml`
- Modify: `scripts/check-inherited-lint-debt.sh` —— raw-green 达成后退役该 ratchet

**授权路径集（冻结判定式）：** **从规范化报告中 `severity="Error"` 派生的 5 条唯一路径** ∪ `res/values-en/strings.xml`，且整体 ⊆ `apps/qianwangyou/app/src/main/**` 且 ∩ `integration/**` = ∅（后者 Fable5 独占）。

> **为什么不写「被 lint error 命中的文件」**：那是**动态描述**，会随 lint 版本、AGP 版本、`minSdk` 变化而漂移；review 时无法判定某个改动是否越界，静态 guard 也无从检查。**先把集合冻成一份提交物，再改代码**——集合本身成为可 diff、可 review 的对象。
>
> **但「冻成提交物」还不够（v1.24 更正）**：上一版声称 prefix + `integration/**` 排除这两层「封住了往 baseline 加行扩权」。**这条断言是假的**——作者只要往 baseline 里加一条任意 `src/main/**` 的非-integration 路径，三层全部 PASS。两层围栏约束的是路径的**形状**，不是集合的**成员资格**，而扩权恰恰发生在成员资格上。
>
> **真正封住它的是把集合绑到指向物**：授权集合**不由 baseline 声明，而由规范化报告中 `severity="Error"` 的 location 派生**（23 个 instance → 5 个唯一文件），baseline 里那 5 行只是声明副本，guard 要求二者**逐元素相等**。**往 baseline 手加一行，集合不再相等，直接判红。**作者要扩权就得同时伪造那份规范化报告——而它可以由任何人在冻结 commit 上重跑 `lintDebug` 并按同一规范化规则复算。**这正是 §0.1.23 判据的应用：把断言绑到可打开、可独立重放的指向物。**

**RED:**

```bash
(cd apps/qianwangyou && ./gradlew lintDebug)      # 预期 exit != 0
# 且 Error instance 数与 baseline 记录的 23 一致（多出的即 PR-3 引入的回归，退回 Task 3）
# 注意：23 是 instance 数；baseline 的 allowed-paths 只含 5 条唯一文件路径
```

**GREEN:** `(cd apps/qianwangyou && ./gradlew lintDebug)` **exit 0**。

**Verify（gate 不能只有 lint —— 本 task 会改运行时源码与资源）：**

```bash
set -euo pipefail          # 必须是整个 Verify 的【第一条可执行命令】
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT     # collision-safe，禁止固定 /tmp 文件名

# ---- 门 1：构建与既有行为（放在 strict flags 之后，失败即中止，不会被后续成功掩绿）----
(cd apps/qianwangyou && ./gradlew lintDebug)          # 终态门：exit 0（raw-green）
(cd apps/qianwangyou && ./gradlew testDebugUnitTest)  # 不得因清债破坏既有行为
(cd apps/qianwangyou && ./gradlew assembleDebug)      # 不得因清债破坏构建
./scripts/check-provenance.sh --stage contract        # 清债是合法分叉
./scripts/verify-a-plus.sh --lane pr-3.5              # 空矩阵集
# 注意：这里【不】调用 acceptance/scripts/check-forbidden-boundaries.sh。
# 它由 sibling Task 7 / I5（Fable5）交付，而 I3.5 只 depends I3 —— 按 DAG 本 task
# 可以早于 I5 执行，届时该文件根本不存在，Verify 会以 "No such file" 必红；而
# `acceptance/**` 是 Fable5 独占，本 task 的 owner 是 Opus5，也无权补上它。
# 一条永远执行不到的指令不是门。本 task 的越界判定由下面门 3 自带的断言承担，
# 它只用 git 与本仓已存在的文件，不依赖任何 sibling 交付物。

# ---- 门 2：授权集合必须【从冻结报告派生】，不是由 baseline 自行声明 ----
BASELINE=docs/provenance/qwy-lint-baseline.md
# qwy subtree 的导入 commit（PR #10 冻结）。只用于判断 DAG 证据是否仍可达，
# 不可达时不判红——见下方 (b) 的说明。
IMPORT_COMMIT=5687e319
REPORT=docs/provenance/qwy-lint-baseline-report.xml
ALLOWED_EXTRA='apps/qianwangyou/app/src/main/res/values-en/strings.xml'

# (a) 报告必须已规范化为 repo-relative：含任何绝对路径即判红
#     否则 digest 依赖 checkout 位置，别人无法重放出同一 digest
! grep -q 'file="/' "$REPORT" || { echo "REPORT NOT NORMALIZED (absolute paths)"; exit 1; }

# (b) baseline 必须声明生成 commit，且等于 qwy 冻结导入 SHA
#     carrier = provenance gate 自己的 IMPORTS 表（PR #10 已校验），不是文档里的自由文本
#     **必须用机器查询，不解析脚本文本。** 文本 parser 与 runtime `$IMPORTS` 会分叉：
#     块外 decoy 行、两个赋值块、或之后一句 `printf -v IMPORTS` / `export IMPORTS=`
#     都能让解析值与循环实际消费的值不同，且双方各自自洽为绿（实测：parser 读到
#     合法 285e…，runtime 的 qwy 为空，而 `--stage contract` 仍 rc=0，provenance 被静默跳过）。
#     `--print-import` 从循环读的同一个 $IMPORTS 打印，因此只有一个源。
FROZEN=$(./scripts/check-provenance.sh --print-import apps/qianwangyou)
DECL=$(sed -n 's/^generated-at-commit: *//p' "$BASELINE")
# 先证明两侧都是【非空、单值、40-hex】——否则"空 == 空"会自洽为绿
for v in "$FROZEN" "$DECL"; do
  [ "$(printf '%s' "$v" | wc -l)" -eq 0 ] || { echo "CARRIER NOT SINGLE-VALUED"; exit 1; }
  printf '%s' "$v" | grep -qE '^[0-9a-f]{40}$' || { echo "NOT A 40-HEX SHA: '$v'"; exit 1; }
done
[ "$DECL" = "$FROZEN" ] || { echo "BASELINE COMMIT != frozen import SHA"; exit 1; }
# 独立第二记录 —— 承重的那条必须【跨 merge 方式存活】，且【取对时间截面】。
#
# 上一版记录 pristine 的 `qwy-subtree-tree` 再与最终 HEAD 比较，是错的：baseline 必须
# 先于任何源码修改提交，而本 task 之后【必然】修改 qwy 源码，所以最终态两值必不相等
# ——合法终态必红。事后回填声明求绿则退化为同作者对最终 tree 的自洽抄录，失去 upstream
# 语义。两条路都不成立，故该字段已删除。
#
# 上一版又错了一次，方向相反：它拿 upstream 树与最终 HEAD 做**全量**差异，再要求每条
# 差异都落在本 task 的 5 文件集合内。但 Task 3.5 排在 Task 2/3 **之后**，前序已合法
# 修改 qwy 的 Gradle / settings / Manifest / integration —— 实测 contract HEAD
# `eddf9729` 相对 upstream 已有 `app/build.gradle`、`build.gradle`、`settings.gradle`
# 等差异，它们不可能落进 5 个 `app/src/main/**` 的 lint 集合。**于是前序合法工作会被
# 判越界，又一道永远红的门。** 而且该循环用的 `$TMP/derived` 在下面 (d) 才生成，
# 文件不存在时 `grep` 返回 2，5 个授权文件也会全部 exit 1。
#
# 三件事必须绑三个不同的载体与时间截面（不能用一个差异同时回答）：
#   ① baseline provenance  —— upstream 树，由 PR #10 的 check-provenance 承担
#   ② 前序 feature divergence —— Task 2/3 的合法改动，**不归本 gate 管**
#   ③ 本 task 的 scope delta —— 只看本 PR 自己改了什么，见下方门 3
# 因此这里不再做 upstream→final 的全量比较；本 task 的授权判定完全由门 3 承担，
# 而门 3 的基线是本 PR 自己的 merge-base，不是 upstream。

# (c) digest 绑定（对已规范化的报告）——同样先证两侧非空、单值、64-hex
REC=$(sed -n 's/^report-sha256: *//p' "$BASELINE")
ACT=$(shasum -a 256 "$REPORT" | cut -d' ' -f1)
for v in "$REC" "$ACT"; do
  [ "$(printf '%s' "$v" | wc -l)" -eq 0 ] || { echo "DIGEST NOT SINGLE-VALUED"; exit 1; }
  printf '%s' "$v" | grep -qE '^[0-9a-f]{64}$' || { echo "NOT A 64-HEX DIGEST: '$v'"; exit 1; }
done
[ "$REC" = "$ACT" ] || { echo "BASELINE NOT BOUND TO REPORT"; exit 1; }

# (d) 只取 severity="Error" 的 location/@file —— warning 一律不进授权集合
xmllint --xpath '//issue[@severity="Error"]/location/@file' "$REPORT" \
  | tr ' ' '\n' | sed -n 's/^file="\(.*\)"$/\1/p' > "$TMP/err-instances"
[ "$(wc -l < "$TMP/err-instances")" -eq 23 ] \
  || { echo "ERROR INSTANCE COUNT != 23"; exit 1; }
sort -u "$TMP/err-instances" > "$TMP/derived"          # 23 个 instance → 5 个唯一文件
[ "$(wc -l < "$TMP/derived")" -eq 5 ] \
  || { echo "UNIQUE ERROR FILE COUNT != 5"; exit 1; }

# (e) baseline 声明的集合必须与派生集合【完全相等】（不是"包含"、不是"命中"）
awk '/^# BEGIN allowed-paths$/{f=1;next} /^# END allowed-paths$/{f=0} f' "$BASELINE" \
  | sed '/^$/d' > "$TMP/declared-raw"
# 先查 raw 行数与重复 —— sort -u 会把"5 条正确 + 1 条重复"洗成 5 条而放行
[ "$(wc -l < "$TMP/declared-raw")" -eq 5 ] \
  || { echo "DECLARED RAW LINE COUNT != 5"; exit 1; }
if sort "$TMP/declared-raw" | uniq -d | grep -q .; then echo "DUPLICATE LINES IN BASELINE"; exit 1; fi
sort -u "$TMP/declared-raw" > "$TMP/declared"
diff -q "$TMP/derived" "$TMP/declared" >/dev/null \
  || { echo "DECLARED SET != DERIVED SET (baseline 被手工增删)"; diff "$TMP/derived" "$TMP/declared"; exit 1; }

# ---- 门 3：本 PR 改动必须落在 派生集合 ∪ {唯一具名新增文件} 内 ----
# 本 task 的 scope delta 绑自己的 pre-fix base：merge-base 之后本 PR 改了什么。
# 用 upstream 或 main 的当前 tip 都会把前序 task 的合法改动算进来。
BASE_REF=$(git merge-base origin/main HEAD) \
  || { echo "CANNOT RESOLVE MERGE-BASE"; exit 1; }
printf '%s' "$BASE_REF" | grep -qE '^[0-9a-f]{40}$' \
  || { echo "MERGE-BASE NOT A 40-HEX SHA: '$BASE_REF'"; exit 1; }
git diff --name-only "$BASE_REF" HEAD > "$TMP/changed"
CHECKED=0
while read -r p; do
  case "$p" in apps/qianwangyou/*) ;; *) continue;; esac
  CHECKED=$((CHECKED+1))
  case "$p" in apps/qianwangyou/app/src/main/*) ;; *) echo "OUT OF PREFIX: $p"; exit 1;; esac
  case "$p" in */integration/*) echo "FORBIDDEN integration/**: $p"; exit 1;; esac
  [ "$p" = "$ALLOWED_EXTRA" ] && continue
  grep -qxF -- "$p" "$TMP/derived" || { echo "OUT OF SCOPE: $p"; exit 1; }   # 整行相等
done < "$TMP/changed"
[ "$CHECKED" -gt 0 ] || { echo "NO qwy PATH CHECKED — selector 失效"; exit 1; }
```

**负例控制（冻结）——必须由 Verify 真正调用，不能只写在表里**

```bash
# 生产 guard 与负例控制共用同一实现；Verify 两条都跑，缺一不算通过
./scripts/task35-scope-guard.sh          # 生产态：必须 exit 0
./scripts/selftest-task35-guard.sh       # N-1..N-8 + P-1..P-3：必须 exit 0
```


上一版只跑了生产态正例，却在 §0.1.27 冻结了「新检查器必须同时跑正负例」——**宣称与兑现不符**。这里补齐：以下每条都必须**可被后来者重放**，不能只存在于作者的提交消息里。落地为 `scripts/selftest-task35-guard.sh`，在临时目录内构造，**不污染工作树**。

> 上一版把它写成 `acceptance/scripts/…`（并称「Sol 的 static-guard lane」）是**双重错误**：`acceptance/**` 按 §12.1 是 Fable5 独占、Sol 只有 review 权；而 Task 3.5 的 owner 是 Opus5，`I3.5` 只依赖 `I3`，不能假定 sibling PR-5 已交付任何文件。**给自己的产物挑一个别人独占的目录，等于写了一条永远执行不到的指令。**

| # | 构造 | 期望 |
|---|---|---|
| N-1 | baseline 手加一行（raw 6 行） | 红 · `DECLARED RAW LINE COUNT != 5` |
| N-2 | 正确 5 行 + 1 条重复（unique 仍 5） | 红 · `DUPLICATE LINES IN BASELINE` |
| N-3 | 用一个 warning-only 文件替换 baseline 中的一条 | 红 · `DECLARED SET != DERIVED SET` |
| N-4 | 报告保留绝对 checkout 路径（未规范化） | 红 · `REPORT NOT NORMALIZED` |
| N-5 | `generated-at-commit:` 留空 / 删除该行 | 红 · `NOT A 40-HEX SHA: ''`（**删空必红**，§0.1.26 第十条） |
| N-6 | 在授权集合外改一个 qwy 文件（本 PR 自己的 delta） | 红 · `OUT OF SCOPE: <path>` |
| N-7 | `check-provenance.sh` 的 `IMPORTS` 在 handler 之后重新赋值 | **赋值本身硬错**（`readonly`），query 与 gate 不可分叉；配套 N-7b：直接删掉 `IMPORTS` 里的 qwy 记录 → 红 · `frozen record set no longer carries apps/qianwangyou` |
| N-8 | 篡改 provenance doc 记录的 upstream root tree | 红 · `does not record the true upstream root tree`（PR #10 侧锚点） |
| **P-1** | **未改动的生产态** | **绿 · exit 0** |
| **P-2** | **history-lost（import commit 全不可达）+ pristine** | **绿 · exit 0 —— 合法合入路径不得误红** |
| **P-3** | **Task 2/3 已合法改过 qwy Gradle/Manifest 之后再跑本 task** | **绿 —— 前序合法分叉不归本 gate 管** |

**调用契约（冻结）**：上表**每一行**都必须由 `scripts/selftest-task35-guard.sh` 真正执行——`N-1..N-8` 与 `P-1..P-3` 全部在内。**新增一行到表里而不加进调用，等于没加**：v1.30 补了 N-8/P-2/P-3 却把调用契约留在 `N-1..N-7 + P-1`，那三行当轮不构成任何回归门。

**PR #10 的承重行为由 PR #10 自测，不推迟到本 task**：`scripts/selftest-provenance.sh` 已接入 #10 的 CI，**每条都执行生产 checker 并断言退出码**，不以 grep 源码字面量充数；计数由 harness 自身计数器打印，标签不会与真实执行漂移。（v1.34 更正：在此之前 **N-5 是唯一的例外**——它只在整段输出里搜 `apps/qianwangyou`，而该串由固定 entry-file 段无条件带出，因此不构成任何断言。现已改为**注入一条合法 5 格 fork 并同时断言 rc 与只可能由 doc-binding 循环产出的真值证据**。）正例含 **squash 合入 + 真实 Task-2 contract delta 必绿**（且先断言其形状：父提交 prefix tree == upstream、HEAD != upstream、恰好三个 qwy 文件）；负例含无 pristine 祖先的伪造终态、prefix-suffix 改名、重复行、SHA/root-tree 跨行互换、**单元格内部空白**、**import-commit 单元格被替换为不存在对象或另一 prefix 的 commit**、隔离的 iterator 重定义。

**变异自检的真实覆盖是 5 条具名修复，不是「逐条」（v1.34 收窄 / v1.35 增补 / v1.36 回填）**：`M-1`→`N-2`（第一格 exact 的**两个**实现层同时退回）、`M-2`→`P-4`（ancestry 走祖先而非只看 HEAD）、`M-3`→`N-9`（只 trim 外围）、`M-4`→`N-5`（`readonly -f each_import`）、`M-5`→`N-12/import-commit`（六格传输无损，**两层同退**）。**其余负例只保证"被执行并断言退出码"，不声称 mutation-bound。** 上一版写的「逐条把修复退回去」把 4 条说成了 12 条（**当前实测：4 正 / 19 负 / 5 条变异自检**，由 harness 计数器打印）——**声称的覆盖与能够证明的覆盖是两件事**，这正是本文 §0.1 反复记的那个形状，只不过这次长在描述 harness 的散文里。选取标准是**曾经产生过假绿的那条修复必须有变异兜底**；新增负例不自动获得变异，要么补 mutation，要么不写进覆盖声明。每条变异都**先证明真的落地**（比对变异前后 checker 的 SHA-256），**且要求目标用例在变异前为绿**（v1.36）——否则「回退修复后它变红」在一条本就红的用例上恒真，变异会打出空转绿；起点非绿、或内层出现 `FAIL <target> … FIXTURE`，一律判 `INCONCLUSIVE` 而不是 `PASS`。

`P-1` 不可省略：**只跑负例证明不了检查器在合法状态下会绿**，而一道永远红的门和一道永远绿的门都不是门（§0.1.27）。

**关于 PR #10 的合入方式（冻结）**：本 gate 与 `check-provenance.sh` **都不要求任何 merge 方式**。DAG 证据（import commit、`git-subtree-*` trailer）只在仍可达时断言；squash / rebase 合法丢弃它们时如实降级并说明，**不判红**。承重证明是 **fetched upstream root tree 锚点**（PR #10 section 1），它不依赖任何本地历史。**「分叉受限」已撤回**：本 gate 不再拿 upstream 与最终 HEAD 做全量比较——Task 3.5 排在 Task 2/3 之后，那样会把前序合法改动判越界（见门 2 上方注释）。本 task 的授权判定只看自己 merge-base 之后的 delta。

实测（`git archive aa6d93a` → 新仓单 commit，两个 import commit 真正 ABSENT）：旧 checker `--stage contract` **rc=1**（`recorded import commit does not exist`），改后 **rc=0**，而 `--stage import` 仍严格。**但那一版又走过了头**：history-lost 之后任意篡改 qwy tracked 文件仍 rc=0（实测 `PASS (all checks)`），因为 section 1 说「内容证明在 section 2」、section 2 说「基线由 section 1 的 import commit 证明」，DAG 一丢两边互相指望。现由 root-tree 锚点独立承担，已实测 N-8 判红。**把一条合法的合入路径做成必红，就是 SIGPIPE 那个错误换了触发者。**

**规范化规则（冻结）**：`qwy-lint-baseline-report.xml` 提交前必须把每个 `location/@file` 由绝对 checkout 路径改写为 **repo-relative**（去掉仓库根前缀，不做其他修改）。**只有规范化后的报告才有可重放的 digest**——否则换一个 checkout 目录，SHA-256 就变（实测 `731feb…` → `9111c3…`），"另一 reviewer 在冻结 commit 重跑得到同一 digest"这条根本不成立。

**解析器可自决**：上面用 `xmllint --xpath` 是参考实现；改用 TSV/JSON 或任何 parser 都可以，**只要满足同一谓词**——① 只取 `severity="Error"`；② Error instance 数 == 23；③ 唯一文件数 == 5；④ baseline 声明集合与派生集合**逐元素相等**。

> **guard 必须实现它自己声明的集合（v1.23 更正）**：上一版只做了上面的 (c)，即「每个改动路径都要出现在 baseline 里」。这与本 task 声明的授权集合**不一致，且方向是拒绝合法改动**——`res/values-en/strings.xml` 是**新建**文件，按定义不可能出现在 baseline 中，因此上一版的 guard 会把 `MissingTranslation` 的唯一合法修法判为越界。
>
> **六层一起才等于声明的契约（v1.25 重写）**：**(0)** `set -euo pipefail` 现在是**整个 Verify 的第一条可执行命令**——上一版把它放在 lint/unit/assemble/provenance/boundary/verifier **之后**，那些前置门失败仍会被后续成功掩绿（最小复现：`false; set -euo pipefail; true` 退出码 0）；**(1)** 报告必须已规范化为 repo-relative，含绝对路径即判红；**(2)** baseline 声明的生成 commit 必须等于 qwy 冻结导入 SHA；**(3)** 规范化报告的 digest 绑定；**(4)** 只取 `severity="Error"`，instance 数 == 23、唯一文件数 == 5，**派生集合与 baseline 声明集合逐元素相等**；**(5)** `grep -qxF` 整行相等 + 检查数为 0 判红 + `mktemp -d` 避免并行覆盖。
>
> **前两版在这里连错两次，都是同一个毛病——把「看起来像绑定」当成绑定。** 第一版声称 prefix + `integration/**` 排除「封住了往 baseline 加行扩权」：假的，那两层只约束路径**形状**，扩权发生在**成员资格**上。第二版改用「条数 == 23 + 每条要在报告里搜得到」：仍是假的，因为 **23 是 Error instance 数不是文件数**，而 `grep` 搜整个 XML 不区分 severity——**把 warning-only 文件重复填满 23 行，三道判定全过**。
>
> 真正的绑定只有一种形状：**集合必须是从指向物派生出来的，而不是由被检查方声明、再拿指向物去「印证」。** 前者手加一行会破坏集合相等；后者只要能在指向物里搜到任意子串就放行。

**终结谓词：** `(cd apps/qianwangyou && ./gradlew lintDebug)` exit 0 —— 同时是 §19 raw-green 终态门的唯一证据来源。`check-inherited-lint-debt.sh` 的 ratchet 在此之前是**中间证据**，之后退役。

**Checkpoint:** exact HEAD + 原始 lint 报告 + baseline 对照回主 Thread。

### Task 4 — Auto 数据模型、可信账本与恢复事务

**Owner:** GLM（operator option B：Auto lane 由 Opus5 转入，见 §12.1）

**Reviewer:** Sol（语义）+ GLM（对抗）

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
./gradlew lintDebug assembleDebug

cd ../..
./scripts/verify-a-plus.sh --lane pr-4      # 49 行 owner-red，绑本 PR HEAD；裸调用会按 pr-6 全 114 行判红
```

### Task 5 — Auto A+ 执行内核

**Owner:** GLM（operator option B：Auto lane 由 Opus5 转入，见 §12.1）

**Reviewer:** Sol（语义）+ GLM（对抗）

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

**Owner:** GLM（Auto，option B）/ Fable5（千网游，各自在独占目录）

**Reviewer:** Sol（语义 + 用户旅程验收）+ GLM（对抗）

**Files:**

- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/PlanScreen.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/ControlScreen.kt`
- Modify: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/HistoryScreen.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/PairingStatusCard.kt`
- Create: `apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/ui/ProviderApprovalScreen.kt`（§6.5.3 的 operator 批准**与撤销**入口：展示待批准候选的 applicationId / 当前 signer 摘要 / 来源，以及已批准 provider 列表与撤销动作；批准前不得进入可信判定）
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/ui/AutomationPairingScreen.kt`
- Modify: qwy navigation/settings files only in Fable5 branch

**RED:** Compose state tests 先覆盖未配对、**provider 待 operator 批准**、不兼容、可信、未验证、recovery-required、release-incomplete 七种现场状态。

**GREEN:** 默认页保持一键模板；高级配置不出现；错误给具体恢复动作。

**Verify:** 两 App unit/lint/assemble + 真机旅程截图；不得只附代码截图。

### Task 7 — 独立 fake provider 与对抗场景

**Owner:** Fable5（operator 2v2）

**Reviewer:** Sol（语义/验收）+ GLM（对抗）

**Files:**

**① 可执行载体（冻结，缺任一条 PR-5 无法启动）** —— 上一版只列了源码与脚本，**没有构建入口**，而 PR #10 的 verifier 已要求 `cd acceptance && ./gradlew test`：

- Create: `acceptance/settings.gradle.kts`（声明 `:fake-qwy` 与 `:scenarios` 两个 module）
- Create: `acceptance/build.gradle.kts` + `acceptance/gradle/libs.versions.toml`
- Create: `acceptance/gradlew` / `acceptance/gradlew.bat` / `acceptance/gradle/wrapper/**`（**独立 wrapper**：`acceptance/**` 不在两个 App 的 Gradle build 内，不得依赖它们的 wrapper）
- Create: `acceptance/fake-qwy/build.gradle.kts`、`acceptance/scenarios/build.gradle.kts`

**source set 与 task 图（冻结）——两类测试必须物理隔离，否则「PR-5 = 0 矩阵行」不可满足**：

| source set | 内容 | 归属 task |
|---|---|---|
| `scenarios/src/selfTest/kotlin/**` | harness self-test（negative control、fixture loader） | `:scenarios:selfTest` |
| `scenarios/src/matrixTest/kotlin/**` | §10.1 `sol-blackbox` 22 行 | `:scenarios:matrixTest` |

- `:scenarios:guardSelfTest`（`Exec`）：运行 `acceptance/scripts/selftest/run-guard-selftest.sh`
- **`:scenarios:selfTest` `dependsOn(":scenarios:guardSelfTest")`** —— guard self-test 由 task 图强制执行，不靠人记得单独跑
- **两个 task 互不 depends；`matrixTest` 不属于 `check`/`build` 的默认依赖**

**exact lane 入口（冻结，逐字）**：

| lane | 命令 | 跑什么 |
|---|---|---|
| **PR-5** | `cd acceptance && ./gradlew :scenarios:selfTest` | 仅 self-test + guard self-test，**0 矩阵行** |
| **PR-6** | `cd acceptance && ./gradlew :scenarios:matrixTest` | 22 行 `sol-blackbox` 对真实产品 |

> **`./gradlew test` 不得作为任何 lane 的入口（冻结）。** 上一版把它写成 PR-5 的 exact 入口，而 22 个矩阵测试当时也放在默认 `src/test` 里——`test` 会把两类一起跑掉，与「PR-5 = 0 矩阵行」直接冲突。**一个聚合 task 不能同时是「只跑 A」的入口。** 隔离 source set 是让这条契约可执行的最小改动；`verify-a-plus.sh --lane pr-5 / --lane pr-6` 必须逐字调用上表命令。

**② 被测产物**

- Create: `acceptance/fake-qwy/src/main/.../FakeEnvironmentControlService.kt`
- Create: `acceptance/scenarios/src/matrixTest/kotlin/matrix/**`（**只承担 §10.1 台账中 `sol-blackbox` 类的 22 行**；**必须在 `matrixTest` source set，不得放进 `test` 或 `selfTest`**，文件名与方法名按台账「精确入口」列）
- Create: `acceptance/fixtures/**`（v4→v5 迁移 fixture、损坏/过期 fixture 负例）
- Create: `acceptance/scripts/check-forbidden-boundaries.sh`（承担 `static-guard` 类 2 行）
- Create: `docs/acceptance/a-plus-device-matrix.md`（`device` 类 2 行的**证据模板与登记表**——注意它是 markdown，**不是可执行测试**，见下）

**③ harness self-test（PR-5 唯一的绿灯来源，冻结）**

- Create: `acceptance/scenarios/src/selfTest/kotlin/selftest/FakeNegativeControlTest.kt` —— fake provider 在**被要求返回违规响应**时，scenario 断言必须判红（证明断言不是恒真）
- Create: `acceptance/scenarios/src/selfTest/kotlin/selftest/FixtureLoaderTest.kt` —— fixture 能装载；**损坏 fixture 必须 fail 而不是静默跳过**
- Create: `acceptance/scripts/selftest/violating-sample/**` + `acceptance/scripts/selftest/run-guard-selftest.sh` —— 对**构造出来的越界样本**，`check-forbidden-boundaries.sh` 必须 exit≠0；对干净样本必须 exit 0。**由 `:scenarios:guardSelfTest` 这个 `Exec` task 调用，且 `:scenarios:selfTest` `dependsOn` 它**——上一版把这个脚本列为通过条件却没有任何 task 或 Verify 命令会执行它

> **`device` 类 2 行不在 PR-5 产生任何绿灯。** `docs/acceptance/a-plus-device-matrix.md` 是登记表，markdown 存在**不构成执行证据**（§10.1 已冻结 `reportDigest` 必须指向真实设备证据文件）。PR-5 只交付模板；两条 device 行的实际证据在 **PR-6 的授权 device lease 内**产生。

**聚合分工（冻结）——manifest 每行 `exactHead` 必须等于被验 PR 的 HEAD，因此单个 PR 不可能聚合平行 sibling 产出的行**：

| PR | 必须证明 | 行数 |
|---|---|---|
| **PR-3**（Fable5） | 自有 `owner-red` 行，绑 PR-3 HEAD | **39** |
| **PR-4**（GLM） | 自有 `owner-red` 行，绑 PR-4 HEAD | **49** |
| **PR-5**（Fable5 开发 / DeepSeek Flash 审查） | **不验任何矩阵行**；只验 acceptance **harness 自身**：fake provider 的 negative controls、fixture 装载、`static-guard` 扫描器对**构造违规样本**能判红（self-test） | **0 矩阵行** |
| **PR-6**（integration exact HEAD） | 在同一 HEAD 上重跑并聚合**全部 114 行**（含 PR-5 交付的 22 `sol-blackbox` + 2 `static-guard` + 2 `device`） | **114** |

**为什么 PR-5 不再证 26 行（v1.21 冻结，采纳 Sol 的推荐模型）**：PR-3/4/5 是 sibling。`M-BP-04`、`M-RS-01`、`M-VS-01` 与两条 `device` 行断言的是**真实 Auto / 真实 provider 的行为**，它们的产品代码在 PR-3/PR-4 上；PR-5 的 HEAD 上根本没有那些实现，`exactHead` 物理上不可能相符。**要求 PR-5 证 26 行 = 要求它证明一件在它 HEAD 上不存在的事。** 因此 PR-5 只交付并自证 harness，真实 26 行的产品通过与 device evidence 一律在 **PR-6 的汇合 HEAD** 上产生。这不降低覆盖——114 行仍然全验，只是**都在唯一一个能同时满足所有 `exactHead` 的点上验**。

**verifier 必须支持 lane 子集（否则本分工不可执行）**：`scripts/verify-a-plus.sh` 增 `--lane <pr-3|pr-3.5|pr-4|pr-5|pr-6>`。

**lane selector 必须是机器可判定的行集合，不能写「该 lane 自有行」**——Fable5 同时拥有 PR-3 的 38 行与 PR-5 交付的 26 行，仅凭 owner 无法区分。冻结为 `(class, evidenceOwner, 入口路径前缀)` 三元组：

| lane | 校验集合（机器判定式） | 行数 | 谁的 exactHead |
|---|---|---|---|
| `pr-3` | `class=owner-red` ∧ `evidenceOwner=Fable5` ∧ 入口前缀 `apps/qianwangyou/` | 39 | PR-3 HEAD |
| `pr-4` | `class=owner-red` ∧ `evidenceOwner=GLM` ∧ 入口前缀 `apps/cellrebel-auto/` | 49 | PR-4 HEAD |
| `pr-3.5` | **空集**；不验矩阵行，仅 `lintDebug` exit 0 + qwy unit + assemble | 0 | PR-3.5 HEAD |
| `pr-5` | **空集**；仅 harness self-test（见上表 PR-5 行），**不消费 evidence manifest** | 0 | PR-5 HEAD |
| `pr-6` | **全部 114 行**，且每行 `exactHead` 必须等于 PR-6 的 HEAD | 114 | PR-6 HEAD |

三元组两两不相交且并集 = 88 个 `owner-red`；26 个非 `owner-red` 行（`sol-blackbox` 22 + `static-guard` 2 + `device` 2）**只出现在 `pr-6`**。**verifier 必须自检这个划分**：若 `pr-3 ∪ pr-4` ≠ 88 或与 `pr-6` 的 114 行不自洽，直接 fail-closed——lane 定义漂移必须比矩阵失败更早被发现。

> **v1.39 更正——这张表的四个计数此前全部过时，而且不是本轮改出来的。** v1.38 往 §10.1 追加 `M-AD-01..11` 时只同步了台账自身与「矩阵↔台账 101==101」，**没有回头改这张 lane selector 表**：它仍写着 90 行 / 64 `owner-red` / Opus5 31 / Fable5 33，那是 11 行加入**之前**的数字。后果不是排版错误——`pr-4` 的机器判定式会选出 42 行却期望 31，自检 `pr-3 ∪ pr-4 ≠ 64` 直接 fail-closed，**PR-4 lane 从 v1.38 起就是不可执行的**。派生计数与台账不在同一次修改里同步，就是给自己留一个必然过期的第二真相源；本轮改 owner 投影时一并校正，并把 `sol-blackbox`/`static-guard`/`device` 的分解写进正文，让下一次漂移在文本层就能被看见。
>
> **v1.46 再更正——上面这条自述本身也过度声称了。** 它写「这张表的四个计数此前全部过时……本轮一并校正」，但本轮实测：v1.39 只改了紧邻的 **lane selector 表**（`pr-3`/`pr-4`/`pr-6`/并集自检），**没有**碰 20 行外的聚合表（`PR-3` 33 / `PR-6` 90）与 class 责任表（`owner-red` 64）——那两处一直停在 v1.38 之前的值，直到 v1.46 才随 `M-AD-14..20` 一并重算。**一条声称「我已经扫了同类」的更正note，本身没有扫同类**，于是后来的读者读到它会认为该问题已闭合而不再复查——这比不写更糟：它把一个未完成的清扫伪装成已完成的。故本轮除改数外，另立规则：**派生计数只能从 §10.1 台账现算，任何散文里的计数都是缓存**，新增或删除台账行必须在**同一个 commit** 内重算全部缓存点（本轮共 22 行 29 处），且更正note只能声称自己**实际验证过**的范围。

**没有 `--lane` 时默认 `pr-6` 语义（全 112 行同 HEAD）** —— 保持最严，避免"忘了传参就悄悄放宽"。**因此每个 Task 的 Verify 必须显式传 `--lane`**：裸调用等于要求全 112 行同 HEAD，在 PR-3/4/5 上必然红。该实现落 PR-1 分支的 `scripts/verify-a-plus.sh`；本文只冻结契约，不改脚本。

**Scope（按 §10.1 台账，不再是"全部行"）：** §10 共 **114 行 / 18 类**（`appid-cutover` 5 行随 `INV-29` 的证据载体拆出到 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)）。

| class | 行数 | **Fable5** 的职责（线级 reviewer = DeepSeek Flash，见 Epic #1 control event 5258691694） |
|---|---|---|
| `sol-blackbox` | 22 | 编写并执行；只消费 public v1 contract + `acceptance/fake-qwy` |
| `static-guard` | 2 | 编写并执行静态扫描 |
| `device` | 2 | 在授权 device lease 内执行并留存证据 |
| `owner-red` | 88 | **不编写**；做 evidence audit——核对 evidence manifest 中该 ID 的 `passed` 记录、`exactHead` 相符、断言与该行预期终态一致 |

**已知性质（显式记录，不隐藏）**：`owner-red` 88 行中有 39 行的 code owner 本身就是 Fable5，因此该 evidence audit 包含**对自有 39 行的自审**。

> **v1.22 更正——上一版给的理由是错的，由 GLM 证伪。** 上一版写「可接受的唯一理由是它不是终门，终门是 `verify-a-plus.sh` 的 owner-independent 机械校验」。**这条论证不成立**：本 Task 的 Verify ①②③ 校验的是「ID 集合相等」「覆盖绑 `status=passed` 且 `exactHead` 相符」「未覆盖行分类 `not-testable` / `deferred`」——**没有任何一条校验「断言语义是否真对应该行的预期终态」**。而「断言与预期终态一致」恰恰是 evidence audit 在做的事。**机械门覆盖不到自审所校验的东西，因此它不能充当自审的兜底。**

**这 38 行自审真正可接受的理由（冻结）**，是另外两件与机械门无关的事：

1. **测试代码本身经过独立 review**：那 39 行的 `owner-red` 测试随 PR-3 交付，`I3` 的 reviewer 是 **DeepSeek Flash**（§16）。断言写错会在代码 review 阶段被非作者看到。
2. **audit 产物本身经过独立复核**：Fable5 产出的 evidence audit 由 **DeepSeek Flash** 复核——具体义务写在 **Task 8 第 3 条**（逐条核 `passed` 记录存在性、`exactHead` 相符、**断言语义是否真对应台账「预期终态」列**），并明写机械门 ①②③ 不覆盖最后一项。**v1.23 更正**：上一版此处引用的是「Task 8 第 2 条」，而第 2 条只审 fake 是否迎合自家 provider，**并不包含 owner-red audit 复核**——那是一条当时并不存在的传播。现已把该义务真正写入 Task 8 并把引用指向它。

**因此这 38 行的独立性完全来自 PR 阶段的跨个体 review，而不是来自任何自动门。** 由此产生一条硬约束：**不得因为「CI 全绿」而放松 PR-3 的代码 review 强度**——那是这 38 行**唯一**的独立检查。**不得**把自审当作独立证据；**也不得**用机械校验替代它，因为二者根本不检查同一件事。

**RED（三处，各自绑不同 HEAD——上一版把它们混成一句，与「PR-5 = 0 矩阵行」冲突）：**

| 在哪 | 什么先红 | 涉及行 |
|---|---|---|
| **PR-5** | **只有 harness self-test 先红**：negative-control 断言、fixture loader 对损坏 fixture、guard 对构造违规样本。**不是任何一条矩阵行** | **0 矩阵行** |
| PR-3 / PR-4 | 各自 `owner-red` 行先红 | Fable5 38 / GLM 48 |
| **PR-6** | 22 `sol-blackbox` + 2 `static-guard` 对**真实 Auto/provider** 的 RED→GREEN；2 `device` 行在授权 lease 内产生首份真机证据 | 26 |

上一版写「26 行的测试代码在 PR-5 上写就、各自失败场景先红」有两处不成立：**①** 与本 Task 已冻结的「PR-5 = 0 矩阵行」直接冲突；**②** 26 行里有 **2 条是 `device` 类**，它们的载体是 `docs/acceptance/**` 的**证据登记，根本没有可以"先红"的测试代码**。**在 PR-5 的 HEAD 上，被断言的实现尚不存在，任何「矩阵行先红」都不是有效 RED，只是编译不过或断言恒假。**

**GREEN:** fake provider 能返回重复 receipt、重启/丢 coverage、revision 漂移、stale/foreign lease、矛盾 tuple、binder death；**acceptance lane 的测试只消费公开 v1 contract**——**这一约束现在与覆盖范围自洽**，因为那 **88 行 `owner-red`** 已归各自 code owner（GLM 48 / Fable5 38），由他们在自己的 lane 内证明。它们不是"无法测试"，只是**不该由 acceptance lane 跨 owner 去测**；acceptance lane（Fable5）对它们的职责是 evidence audit。**注意**：黑盒约束在此处始终指**依赖边界**（只能进 public v1 contract + fake provider），不指执行者身份——Fable5 写 qwy provider 与写 acceptance 用的是两个互不可见的入口，`check-forbidden-boundaries.sh` 对二者一视同仁地静态阻断。

**Verify（必须显式传 `--lane`，裸调用 = `pr-6` 全 112 行同 HEAD，在本 PR 上必红）：**

```bash
# PR-5 自身：只自证 harness，不消费 evidence manifest、不验矩阵行
(cd acceptance && ./gradlew :scenarios:selfTest)   # 含 guardSelfTest（dependsOn），0 矩阵行
./scripts/verify-a-plus.sh --lane pr-5             # 必须逐字调用上一行的 task，不得调 `test`
./acceptance/scripts/check-forbidden-boundaries.sh # 依赖边界静态阻断（对本 PR 自身的产物）
```

`--lane pr-5` 的通过条件：`:scenarios:selfTest` 全绿——即 fake provider 的 negative controls 判红成功、fixture 可装载且损坏 fixture 必 fail、`guardSelfTest` 证明 `check-forbidden-boundaries.sh` 对构造违规样本 exit≠0、对干净样本 exit 0（扫描器自己不会假绿）。**`matrixTest` 在本 lane 不被调用，因此 0 矩阵行是 task 图保证的，不是靠约定。**

**26 行的真实产品通过与 device evidence 在 PR-6 汇合 HEAD 上产生**，届时 `./scripts/verify-a-plus.sh --lane pr-6` 逐字调用 `(cd acceptance && ./gradlew :scenarios:matrixTest)`，并执行 contract + 两 App unit + boundary guards，并做 §10.1 的三项覆盖校验：① §10 与 §10.1 的 ID 集合相等；② 覆盖绑定 evidence manifest 中 `status=passed` 且 `exactHead` 相符的记录；③ 未覆盖行必须显式区分 `not-testable`（永久上限）与 **`deferred:<DP-x>`**，且**清单中存在任一 `deferred` 记录时最终 gate 一律失败**。

### Task 8 — 线级独立审查与 exact-HEAD 对抗验证

**Owner:** 各 lane 的线级 reviewer（#3 → Sol · #4/#6/#7 → DeepSeek Flash · #5 → Sol），**不再由 GLM 统一承担**——GLM 作 #5 作者期间无任何 review 席位（见 Epic #1 control event 5258691694）

1. 先审 Fable5 qwy provider：授权、revision 覆盖声明、idempotency、foreign lease、进程死亡。
2. 再审 **Fable5** acceptance：是否存在 fake 只验证实现细节、未覆盖真实状态边、误把心跳当连续性。**2v2 后 provider 与 acceptance 同属 Fable5，本条须额外证伪一件事**：fake provider 是否被写成"迎合自家 provider 实现"而非迎合 public v1 contract——即对 §10.1 中 `M-CC-03`/`M-RL-01` 一类跨端行，断言是否只在 Fable5 自己的实现语义下成立。
3. **复核 Fable5 对 88 行 `owner-red` 的 evidence audit（v1.23 新增，非可选）**——其中 **39 行的 code owner 就是 Fable5 本人**，属自审。逐条核：该 ID 在 evidence manifest 中的 `passed` 记录是否真实存在、`exactHead` 是否相符、**断言语义是否真对应台账该行的「预期终态」列**。最后一项是本条的重点：**`verify-a-plus.sh` 的机械门 ①②③ 不校验它**（只校验 ID 集合、`status`+`exactHead`、未覆盖分类），因此这 39 行的断言正确性除 PR-3 的代码 review 外，只有本条覆盖。发现 audit 与台账预期终态不符时，按 §10.1 失败路由表处置，**不得**因 CI 全绿而放行。
4. 对 **GLM** 的 Auto 做可信账本与 `PRE_EXISTING_RUN` 对抗审查（option B 后 Auto 作者是 GLM）。
5. 每个 finding 给 `block/approve`、精确文件/行、复现命令和 exact HEAD。
6. behavioral delta 后旧 verdict 失效，必须重跑受影响矩阵。

### Task 9 — 隔离真机验收与发布候选

**Owner:** Fable5（验收执行，operator 2v2）

**Independent reviewers（按 lane，见 Epic #1 control event 5258691694）:** #3 → Sol · #4/#6/#7 → DeepSeek Flash · #5 → Sol——与 `I6` / §19 / live [#7](https://github.com/TERRYYYC/fakexxx/issues/7) 的 2v2 配置一致。上一版只写 GLM，与这三处冲突。

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
- **`applicationId` cutover 的真机旅程不在本 task**：已随 `M-AC-03` 一并拆出到 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)。在该 issue 闭合前，本 spec 的 Task 9 **不包含**任何 cutover 设备动作；
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
./scripts/verify-a-plus.sh --lane pr-6      # 本 task 是汇合点：全 112 行、每行 exactHead == PR-6 HEAD
```

预期：全部 exit 0；测试报告归档到 PR evidence。**本 task 是唯一验证全 112 行的地方**——26 个 acceptance 行的真实产品通过与 device evidence 都在此产生（见 Task 7）。设备验收命令不写成无串号的通用 `adb` 脚本，必须在独立 device lease 中绑定 exact serial、APK SHA、安装方式和恢复边界。

## 15. PR 顺序与 merge gates

```text
PR-0 文档（本文，先独立落 main）
  ↓
PR-1 远端基线导入 + provenance + ownership + CI
  ↓
PR-2 contract v1（冻结 exact HEAD）
  ├── PR-3 Fable5：千网游 provider/continuity/security
  │      ↓
  │   PR-3.5 Opus5：qwy inherited lint raw-green 清债（§12.1 唯一具名例外）
  ├── PR-4 GLM：Auto data/trust/recovery/core/UI
  └── PR-5 Fable5：fake provider + acceptance harness（只自证 harness；26 行在 PR-6 验）（DeepSeek Flash 审查）
          ↓
PR-6 integration + exact-build device evidence（pre-cutover；只做必要胶合，不吞并三路职责）
          │
          ├──────────────┐
          ↓              ↓
    I6 release      #13 / PR-14 applicationId cutover（设备层）
    closure         post-cutover evidence · INV-29 gate 只作用于此
          └──────┬───────┘
                 ↓
           Epic #1 close（join：两个 sibling 输入都闭合才能 close）
```

**#13 的结构边（冻结，v1.21 消歧）**：#13 **不在实现链上**，**不阻断 PR-1..PR-6 任何一个的合入**——PR-6 交付的是 pre-cutover 集成与真机证据，在旧 `applicationId` 上成立。**`I6` 与 `#13` 是 Epic close 这个 join 的两个 sibling 输入，彼此之间没有依赖边**（上一版写「I6 终门必须等 #13」，与 §20「#13 不阻断 Task 9」直接对撞，此处按 §21 DP-2 的四段模型统一）。`INV-29` 的 gate 只作用于 #13 一侧的设备 mutation。

Task 6 的两半按 owner 分别随所属 PR 走，不单独成 PR：Auto 侧 UI 进 PR-4（GLM），千网游侧 `integration/ui/AutomationPairingScreen.kt` 进 PR-3（Fable5）。owner matrix 本身不变——`apps/qianwangyou/**/integration/**` 含 UI 全部归 Fable5。

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
| EPIC | `[Epic] CellRebel × 千网游 A+ 可信无人值守测试` | 本文 | Sol 主控（**coordination / review only，无写入 lane**） | 所有 P0 child 达标且等待 operator merge/close 决定 |
| I1 | `[P0] 导入双 App 精确基线并建立 provenance/CI` | EPIC | Opus5 / Sol | PR-1 exact HEAD 通过 gate |
| I2 | `[P0] 冻结 Environment Control contract v1` | I1 | Opus5 / Sol | PR-2 exact HEAD + verdict |
| I3 | `[P0] 千网游 provider：配对、lease、连续性与审计` | I2 | Fable5 / **DeepSeek Flash** | PR-3 exact HEAD + INV tests |
| I4 | `[P0] Auto：可信账本、恢复状态机与 A+ 模板` | I2 | **GLM** / **Sol** | PR-4 exact HEAD + INV tests。reviewer = Sol 独立线级审（见 Epic #1 control event [5259280778](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5259280778)）；GLM 是本 issue 作者，不得自审 |
| I5 | `[P0] A+ fake provider、崩溃/并发/旁路矩阵 harness` | I2 | **Fable5** / DeepSeek Flash | PR-5 exact HEAD + **harness self-test 全绿**（negative controls / fixture 装载 / `static-guard` 对构造违规样本判红）。**不在 PR-5 验任何矩阵行**——26 行的真实产品通过与 device evidence 在 I6/PR-6 汇合 HEAD 上产生，见 Task 7 lane 表 |
| I3.5 | `[P0] qwy inherited lint raw-green 清债` | I3 | Opus5 / Sol | `(cd apps/qianwangyou && ./gradlew lintDebug)` **exit 0** |
| I6 | `[P0] 双 App 集成与 exact-build 真机验收（pre-cutover）` | I3,I3.5,I4,I5 | **Fable5** / DeepSeek Flash | device matrix + hashes + verdict（旧 `applicationId` 上成立；**不依赖 #13**） |
| #13 | `applicationId cutover：flavor / SAF 搬运 / bundle / variant CI` | I1 | Opus5 / Sol | `M-AC-01..05` 全绿。**与 I6 是 Epic close 的两个 sibling 输入**，二者互不阻断；未闭合只阻断 Epic close，不阻断 I6 |
| I7 | `[Product Gate] A+→B→C 触发证据与非重写演进` | EPIC | Sol 主控（**coordination / review only，无写入 lane**） | 每个里程碑记录 stay/promote/reject verdict |

> **Owner 列里的「Sol 主控」不是写入所有权**：本列同时承载 owner 与 reviewer，而 §12.1 已冻结 Sol **无独占写入范围（review-only）**。「主控」指 coordination 与 verdict 汇总，不含任何代码/文档产出义务。此处显式标注以免读者从列名推断 Sol 拥有 Epic 的实现责任。

Issue body 必须链接本文、列出依赖 issue、owner/reviewer、文件范围、相关 INV、验证命令与“operator only merge”。

**I1 单 reviewer 是刻意的，不是遗漏**：[#2](https://github.com/TERRYYYC/fakexxx/issues/2) 的 T0 至今写 `Independent reviewer: Sol`，且 PR-1 已在该配置下取得 Sol 的 exact-HEAD APPROVE。2v2 是对 **#4/#6/#7 开发线**的指派，不追溯改写已完成的 I1 review 配置；若要改为 Sol + GLM，必须先改 #2 的 T0 再改本表，不得只在本文单方面声明。

## 17. Thread 编排

在 GitHub issue 图冻结后，从实施主 Thread 提议**五个**子 Thread（2v2 后 Sol 的写入 lane 撤销、审查 lane 独立成条），均使用 `state-transitions` 回报：

1. **Opus5 核心实现**：I1/I2 与 #13，独立 worktree（**I4 已随 option B 移出**）。
2. **Fable5 验收与真机**：I5/I6，独立 worktree；只经 public v1 contract + `acceptance/fake-qwy`，不写 Opus5 核心实现。**与下条 #3 同属 Fable5，必须分 worktree/分 PR，不得混提。**
3. **Fable5 千网游独立模块**：I3；只在 contract exact HEAD 冻结后开工，文件所有权不与 Opus5 重叠。
4. **GLM**：**I4（Auto）作者线**，独立 worktree。**作者期间不持有任何 review 席位**（见 Epic #1 control event [5258691694](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5258691694)）：I4 由 **Sol** 在 #5 开发线内独立审（见 Epic #1 control event [5259280778](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5259280778)）；#4/#6/#7 由 **DeepSeek Flash** 审。
5. **线级 reviewer**：Sol（#3 与 #5）· DeepSeek Flash（#4/#6/#7）。**review 发生在各开发线 thread 内**；主线 Sol 只做进展/merge review/Epic/派工，不做 line code review。
5. **Sol 独立语义/验收审查**：review-only，无写入 lane；对 I1..I6 出具 exact-HEAD verdict。

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
| AC-10 | 崩溃/并发/恢复/旁路矩阵逐项通过，且每行有 §10.1 的 evidence class 与覆盖证明 | INV-01..28（**INV-29 为 deferred gate，覆盖载体在 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)，本文不声称已覆盖**）；§10 report + `verify-a-plus.sh` 行 ID 覆盖检查 |
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
- INV-01..28 全部被自动测试或明确的真机证据覆盖；**INV-29 是 deferred gate**（§20.1 `KB-4`）——其证据载体在 [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13)，未闭合前不得声称覆盖，也不得执行任何 `applicationId` mutation；
- **`I3.5` 已闭合**：`(cd apps/qianwangyou && ./gradlew lintDebug)` **exit 0**（raw-green 终态门；ratchet 仅为中间证据）；
- **`#13` 已闭合**：`M-AC-01..05` 全绿，设备层 cutover 与旧 App 移除完成 —— **Epic #1 不得在 #13 未闭合时 close**；
- §20.1 表中**全部** `limit` 条目已在验收报告中显式记录、未被写成全绿：`KB-1`（§8.6.5 completion 跨 attempt 去重）、`KB-2`（§18.1 的 `FULL` 依赖）、`KB-3`（§6.5.3 的 TOFU 上限）。**以 §20.1 为准，不以本行的枚举为准**——本行此前只列前两条、漏掉 `KB-3`，正是 §20.1 要消除的"多入口各列子集"；
- §20.1 中的 `unfrozen` 与 `gap` 条目（当前为 `KB-5`／`KB-6`／`KB-7`／`KB-8`）在完成前**必须已被处置**：`unfrozen` 或经其 owner 裁定后冻结为具体规则、或被显式改判并写明理由；`gap` 或补齐 ledger row **／门禁**并执行、或由 operator 显式承担该覆盖缺口。**带着未冻结的契约点声称完成，等于交付一份两侧可以合法分叉的契约**；而带着未补的 `gap` 声称完成，等于把"没测过"计入了"已覆盖"。**`KB-7` 是本轮由 `unfrozen` 转入 `gap` 的实例：裁定闭合的是实例，不是同类**；
- 两 App exact APK SHA、源码 HEAD、签名、设备串号和恢复后状态完整记录；
- Hook 未验证结果与可信 System Mock 结果在类型、存储、UI、导出和配额上全部隔离；
- 原仓 #14/#15 相关风险被诚实披露并取得本候选构建的验收结论；
- **Opus5、Fable5 与 GLM**（option B 后三者均有作者改动）的改动各有独立 reviewer：Opus5 → **Sol**；Fable5 → **DeepSeek Flash**；GLM → **Sol**（见 Epic #1 control event [5259280778](https://github.com/TERRYYYC/fakexxx/issues/1#issuecomment-5259280778)）。作者与 reviewer 必须不同个体；Sol 与 GLM 均不审自己写的改动，且 Sol 在本 Epic 内无写入 lane；
- 所有 candidate PR 均停在未 merge 状态，等待 operator 对每个 PR 决定。

## 20. 当前开放项

没有需要 operator 拍板的技术 A/B 题——技术项已给出 exact schema、digest 算法、容差与 API 24–27 语义。实现中若发现 Android 无法对某类相关变化提供完整连续性事件源，正确处置是 capability 返回 `PARTIAL/NONE` 并停止可信计数，而不是降低本文的不变量。

**三件价值取舍已由 operator 决定（§21 记录逐字原文）。下表是唯一权威，任何入口读到的答案必须与此一致：**

| DP | 主题 | 决定 | 字面值 / 载体归属 | 阻塞 contract v1 冻结 / #3–#6 | 阻塞真机验收（Task 9） |
|---|---|---|---|---|---|
| DP-1 | 千网游 release signer 迁移 | **B 受控迁移** | 不涉及 | 否 | 否——但 signer cutover 本身**必须**在 DP-1 前置门（export/restore + custody + rollback）完成后才执行 |
| DP-2 | Auto 最终 `applicationId` | **B 改名 → `come.xx.fakeaauto`** | **不由 PR-1 承载**（Task 1 是 import-only、app 树 pristine，物理上产不出这些载体）。四段式见 §21 DP-2：**字面值 → 本 docs PR（PR-0.2）** · **contract/compatibility 常量 → PR-2** · **`PairingRecord`（caller allowlist）→ PR-3（千网游侧）/ `ProviderPairingRecord`（provider allowlist）→ PR-4（Auto 侧）** · **设备 mutation → [#13](https://github.com/TERRYYYC/fakexxx/issues/13)**。`INV-29` 只 gate 最后一段。**字面值 `come.xx.fakeaauto` 是 Auto 的 applicationId，故作为 caller key 出现在千网游的 `PairingRecord`；真实配对记录只在新 ID bind + operator 批准后产生，②③ 都不产生已批准记录**（v1.23 更正：v1.21 曾把两张表写反，详见 §21 DP-2） | 已解除——contract 使用已拍板的新值即可，不依赖 cutover 完成 | 否——但 cutover 受 `INV-29` deferred gate 约束 |
| DP-3 | CellRebel 可信完成的安全边界 | **A 接受 UI 证据 + 写明上限** | 否 | **条件式解除**：`#3` 需 `#2` + `#12` 均合入；`#4/#5/#6` 需其上游 issue 依赖满足。DP-3 本身的 durable closure 见 §21 清单 | 否 |

**因此本文现在是可开工的冻结实施基线**（与顶部告示一致）。

仍然开放、但**不属于 operator 价值取舍**的技术项：**`KB-5` 一条**，见下表。本行此前写作"无"——那在 `KB-5` 被显式化之前只是**看起来**成立：该留白当时并没有不存在，而是以 §6.3.3 与 §6.7.4a 互斥的形式藏着，读者读到的是两条各自自洽的规则，而不是一处待裁定的空白。**矛盾是留白的伪装态**，v1.42 只是把它还原成留白。实现中若发现 Android 无法对某类相关变化提供完整连续性事件源，正确处置仍是 capability 返回 `PARTIAL/NONE` 并停止可信计数，而不是降低本文的不变量。

### 20.1 已知边界总表（完整性投影）

本表只回答一个问题：**还有哪些东西没有被证明、或没有被冻结**。此前这个问题没有单一入口——条目分散在 §6.5.3 / §6.7.4a / §8.6.5 / §18.1 / §9，而顶部告示与 §19 各自只列了其中两条，§20 更写作"无"。**读者在任何一个入口读到的都不是全集，于是"我没读到"与"它不存在"无法区分**，而这两者要求的行动完全相反。

**分工（避免本表变成第二个真相源）**：本表是**完整性**的真相源——有哪些边界，以本表为准；每条边界的**内容**以其权威节为准，本表不复述，以免两处漂移。**新增边界必须同时进本表与权威节**，只进一处即为缺陷。

| # | 边界 | 类别 | 权威节 | 能消除它的是什么 | 验收／实现侧义务 |
|---|---|---|---|---|---|
| `KB-1` | 同一物理完成被两个 attempt 各观测一次时的去重：可信计数的归属依据是**时序因果链**，不是结果内容 | `limit` 永久上限 | §8.6.5（矩阵行 `M-CO-03`） | **不可消除**——观察面所限。DP-3 = A 意味着它被**接受并承担**，不是被解决 | 验收报告不得呈现为全绿；上限必须进入**用户可见**的计数语义（`AC-06`），不得只留在本文 |
| `KB-2` | `AC-05` 依赖千网游自报 `coverage == FULL`，Auto 没有第二个信息源可以反驳它 | `limit` 永久上限 | §18.1 | 架构上不可消除（§5：qwy 是环境唯一权威）。**上游 #14/#15 关闭也不消除它**——那只减少已知漏报，不建立独立验证面 | `AC-05` 标 `conditional-pass + 逐候选构建证据`，不得无条件通过 |
| `KB-3` | operator 对首次见到、未经独立比对的 signer 做显式批准，密码学上**仍是一次 TOFU**；本方案**不证明** publisher identity | `limit` 永久上限 | §6.5.3 | 带外分发的 signer 指纹或受控 release key（§21 DP-1）——**不在 A+ 范围内** | **不得**把本机制描述为"已解决身份伪造"；能声称的只有"禁止并防住了自动／静默 TOFU" |
| `KB-4` | `applicationId` cutover 不得孤儿化用户可见状态 | `deferred` 可触达、待闭合 | `INV-29`（§9）／§21 DP-2 | [Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13) 闭合 | 未闭合前**不得**声称 `INV-29` 已覆盖，且**不得**执行任何 `applicationId` mutation |
| `KB-5` | `completeAndAdvance` 收到**非本 caller 所有**的 `leaseId` 时如何处置 | `unfrozen` 契约留白 | §6.3.3 wire 8 例外段／§6.7.4a | `M-AD-12`／`M-AD-13` 的 owner 裁定后冻结（属 provider 侧行为，非本 PR 可单方决定） | 冻结前两侧**都不得**擅自选一种读法并依赖它——一侧用 8 拒绝、另一侧放行，Auto 的恢复策略即不可移植（§6.7.4b 冻结判定次序所要消除的正是这一形态） |
| `KB-7` | **契约引用的每个 `android.*` 类型必须存在于 public compile SDK**（§6.1 v1.59 冻结的通用规则）——**这条规则零门禁**。它的第一个实例已闭合：v1.44 登记的「预期业务失败经 `ServiceSpecificException` 携带稳定 wire code 返回」按字面不可实现（`android.os.ServiceSpecificException` 是 `@hide`；对 `android-35` 与 `android-36.1` 的 `android.jar` 做 zip entry 枚举**零命中**，对照项 `android/os/Parcel.class` 命中，证明扫描有效而非空转），operator 裁定 **A** 后改由 `EnvironmentControlResultV1` 承载（§6.3.2） | `gap` 覆盖缺口 | §6.1（规则本体）／§6.3.2（承载它的载体） | 在 `scripts/check-contract-v1.sh` 增一节：枚举契约源码引用的全部 `android.*` 类型，断言每个都存在于 compileSdk 的 `android.jar`，带对照项证明扫描非空转，并配 `selftest-contract-v1.sh` 负例证明该节承重 | **不得**把「`ServiceSpecificException` 已换掉」读作「同类已防」——换掉的是**唯一已知实例**，规则本身仍只活在散文里，**而散文正是它当初能藏到 v1.44 才被发现的原因**。v1.44 ④ 当时挂起这道门禁的理由是「断言形状取决于所选通道」；通道已定，该理由已失效，故本条不随实例一起注销，改记为 `gap` |
| `KB-8` | §2.2 声称 Auto 不再导入地址/经纬度、只持有 `scheduleItemId + scheduleVersion`，但 `EnvironmentIntentV1` 仍要求 Auto 发送 `latitude/longitude` 且进入 `acceptedIntentHash` 的 preimage；`discover()` 不暴露 schedule-item 坐标。provider 可以信 Auto 坐标、信自己 schedule 坐标、或拒绝不匹配——**契约未冻结选择，也无 wire 表达它**。Decision Packet v2 已投 | `unfrozen` 契约留白 | §2.2 / §6.3（`EnvironmentIntentV1`）/ §6.3.1（digest preimage） | **operator 裁定坐标所有权**（Decision Packet v2 已投）。裁定后冻结 wire 级行为（provider 用谁的坐标、不匹配时返回什么 code、discover 是否投影 item 坐标） | 冻结前**不得**声称坐标归属已解决。两侧实现各自选一种读法即不可移植——与 `KB-5` 同形，层不同（`KB-5` 在 lease 层，本条在 intent 层） |
| `KB-6` | provider 侧 compare-and-advance 的**产生逻辑**（§6.7.4b 的 CAS 三门判定、指针与 receipt 同事务、幂等重取）在 §10.1 台账中长期只有 `M-AD-12`／`M-AD-13` 两行；v1.46 增 `M-AD-20`、本轮再增 `M-AD-21`／`M-AD-22`，现为 `M-AD-12`／`M-AD-13`／`M-AD-20`／`M-AD-21`／`M-AD-22`（**此处刻意枚举、不计数**：本条的存货数已经过期过一次，而一个过期的数字没有任何东西能发现，一个缺失的 ID 有——这正是 `check-derived-counts` 作者为同一种病写下的解药「enumerate, never count」），但仍**零覆盖** proof/CAS provider 侧判定、同键持久重放、幂等记录+指针+receipt 原子提交、耗尽双态（`M-AD-21`／`M-AD-22` 只覆盖并发与事务边界两项）。**本条存货数此前写作「两行」已过期**——v1.46 加行时没回来改它，正是本表自己第 128 行冻结的「新增边界必须同时进两处」被违反了一次，而违反者是加那一行的人 | `gap` 覆盖缺口 | §10／§10.1（`M-AD-*` 台账行）；缺口由 v1.39 自述"provider-owned advance 覆盖的**第一批**" | 由 `M-AD-12`／`M-AD-13` 的 owner 按同一模式补齐其余 provider-owned 行，并同步 §10／§10.1 的行数与 §15 的逐 lane 派生计数 | **不得**把 `M-AD-01..13` 全绿读作"provider 的 compare-and-advance 已被证明"——`M-AD-01..11` 断言的是 **Auto 侧消费**这些结果的行为、锚在 Auto lane，provider 侧**产生**这些结果的行为目前只有两行覆盖 |

**四个类别不可互相改写**，因为每一类要求的下一步动作完全不同：`limit` 是**已经知道证明不了**（只能接受并披露）；`unfrozen` 是**还没决定**（需要 owner 裁定）；`deferred` 是**可触达、有确定载体、尚未闭合**（等载体，与 §10.1 对 `deferred:<DP-x>` 的规定同源）；`gap` 是**契约已冻结、但没有稳定 ledger row 证明实现符合它**（需要补行并执行）。把留白写成上限，等于宣布它永远不修；把上限写成留白，等于承诺一件交付不出来的东西；把任一者写成 `deferred`，等于给它一个并不存在的闭合路径。而把 `gap` 写成 `limit`——**把"还没测"说成"测不了"**——是其中最该防的一种：它让一件待办永久退出待办清单，且伪装成物理限制之后没有人会再去挑战它。

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

**两层拆分（冻结）——上一版把这两件事混成一件，导致 Task 1「不做 mutation」与本节「改名在 PR-1 完成」直接对撞**：

**上一版把「契约层」整块归给 PR-1，那是不可满足的**：Task 1 冻结为 import-only、两棵 app 树逐字节 pristine，它物理上产不出 contract 常量，更产不出两侧的 pairing 持久化。按 §13 的真实载体重新拆成**四段**（冻结，唯一模型）：

| 段 | 内容 | 载体 | 时间窗 |
|---|---|---|---|
| **① 字面值** | 冻结字符串 `come.xx.fakeaauto` 本身（含 §21.1 合法性核验） | **本 docs PR（PR-0.2 / #12）** | 已完成，随本 PR 合入 |
| **② contract / compatibility** | contract v1 与 compatibility 常量按该字面值成立 | **PR-2（Task 2）** | contract 冻结时 |
| **③ pairing 持久化** | **`PairingRecord`（caller allowlist，千网游侧）→ PR-3（Task 3）**；**`ProviderPairingRecord`（provider allowlist，Auto 侧）→ PR-4（Task 4）** | **PR-3 / PR-4** | contract exact HEAD 冻结后并行 |

> **v1.23 更正——上一版把这两张表写反了。** v1.21 引入四段拆分时写成「`ProviderPairingRecord`→qwy / `PairingRecord`→Auto」，与本文权威模型相反。§6.5.3、§7.1、§8.2 与 Task 3/4 一致地规定：**千网游维护 caller allowlist `PairingRecord`，Auto 维护 provider allowlist `ProviderPairingRecord`**，两份名单各自可撤销、互不替代。
>
> **字面值的落点也随之明确**：`come.xx.fakeaauto` 是 **Auto 的 applicationId**，因此它作为 **caller key 出现在千网游的 `PairingRecord` 里**（不是出现在 Auto 自己的 `ProviderPairingRecord` 里——那张表存的是 provider 即千网游的身份）。
>
> **真实新 ID record 的创建时点**：不由本 docs PR 或 PR-2 产生。record 只在**设备上以新 ID 完成 bind + operator 显式批准**之后才存在（§4.1 bind-first，禁止 TOFU）。因此 ②「contract/compatibility 常量」冻结的是**字符串常量与比对规则**，③ 冻结的是**两张表的 schema 与写入路径**，**都不产生任何已批准的配对记录**——那属于 ④ 之后的运行期事实。
| **④ 设备 mutation** | 真正把设备上的 App 从旧 ID 迁到新 ID（flavor / SAF 搬运 / bundle / 旧 App 移除） | **[Issue #13](https://github.com/TERRYYYC/fakexxx/issues/13) / Draft PR #14** | live cutover · 旧 App 移除 · release candidate |

**①②③ 都不是设备 mutation**，因此 `INV-29` 的 gate **只作用于 ④**。**在 #13 闭合前，`INV-29` 不得泛化阻塞 PR-1/2/3/4/5/6** —— 那些 PR 不执行设备迁移，也就没有可孤儿化的状态。PR-1 保持 import-only，因此仍满足 provenance 最强断言（`--stage import`）。

**DAG 位置（冻结，消除上一版的三处方向冲突）**：上一版同时存在「§15 把 #13 排在 PR-6 之后」「§16 让 I6 depends on #13」「§20 说 #13 不阻断 Task 9」三种方向，互相否定。统一为：

- **#13 不在实现链上**，也**不阻断 PR-6 的代码合入**——PR-6 交付的是 **pre-cutover** 的集成与真机验收证据，在旧 `applicationId` 上成立，可独立合入。
- **`I6`（release closure）与 `#13`（post-cutover evidence）是 Epic close 这个 join 的两个 sibling 输入**，彼此不构成依赖边。
- 因此：**`I6` 不再 depends on `#13`**；二者各自闭合后 Epic #1 才能 close。`INV-29` 只 gate `#13` 这一侧。

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
- **执行 lane（冻结）**：清债作为 **PR-3.5**，时序为 **PR-3 合入之后、PR-6 之前；与 PR-4 平行，二者之间没有依赖边**（上一版写「插在 PR-3 与 PR-4 之间」是线性误导，与现行 DAG 不符）。**它现在是 §13 的真实节点 [`Task 3.5`](#task-35--千网游-inherited-lint-raw-green-清债)（含 Files / RED / GREEN / Verify）与 issue `I3.5`**，不再只是本节的散文，owner **Opus5**，reviewer **Sol + GLM**。以 Task 3.5 正文为准，本节只做索引，避免两处各写一套。
  - **exact 文件范围**：**从 `docs/provenance/qwy-lint-baseline-report.xml`（规范化为 repo-relative 的原始报告）中 `severity="Error"` 派生的 5 条唯一文件路径** ∪ `res/values-en/strings.xml`；`qwy-lint-baseline.md` 只是该派生集合的**声明副本**，guard 要求二者逐元素相等。**23 是 Error instance 数，不是路径数**（v1.26 更正：上一版把两者混写成「23 条 error 的 exact 路径」，会把实现者送回旧的脆弱载体）；不用「被 lint error 命中的文件」这种会漂移、review 时无法判定的动态描述。**不碰** `integration/**`（Fable5 独占），由 Task 3.5 的越界断言机器判定。
  - **时序**：必须在 PR-3 合入后开始（避免与 Fable5 并行写 qwy），并在 PR-6 之前完成。**Task 3 的 lint 门已相应降为 ratchet**——上一版让 Task 3 先要求 raw-green、又把清债排在 PR-3 之后，两者互锁，已消环。
  - **gate 节点**：`I3.5` 的终结谓词 = `(cd apps/qianwangyou && ./gradlew lintDebug)` **exit 0**；该谓词同时是 §19 raw-green 终态门的唯一证据来源。**但 Verify 不止 lint**：清债会改运行时源码与资源，因此 unit + assemble 同为必过门。
  - owner matrix 相应放宽：**Opus5 在 PR-3.5 内可写上述 exact 范围**，这是 §12.1「PR-3 开始后不触碰 qwy」的**唯一具名例外**（已写入 §12.1 表内），范围外仍禁止。
- 清债由 Opus5 **串行**进行（不与 Fable5 的 provider 实现并行写 `apps/qianwangyou/**`），每次授权的 delta 必须可追溯，且**不得破坏 upstream import provenance**——`check-provenance.sh` 在 `--stage import` 下会因此失败。**stage 移动规则以 §13 Task 1 的冻结条款为唯一真相源：由第一个实际修改 app 树的 PR 原子移动**——按当前 DAG 即 **Task 2**（contract 接线已要改两 App Gradle 与 Auto Manifest），不是 #13。清债 PR-3.5 若在 Task 2 之后落地，届时 workflow 早已是 `--stage contract`，无需再移；若它反常地更早，则由它移动并在 PR body 记录移动前后 stage。**上一版把首次分叉钉死在 #13 是错的，此处更正**——两者都是合法分叉，不是绕过。

**(5) 87 份单机验收工件：现在复制 + SHA-256，原件不动。**
由 **Fable5** 的验收线执行（2v2 后 `docs/acceptance/**` 与 device 证据归 Fable5，见 §12.1）：复制 + 逐份 SHA-256 登记，**不动原件**，本次**不公开提交可能含 UI 内容的工件**。Opus5 不触碰这批文件；Sol 只核验登记结果，不执行复制。

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

**处置已落定（A）**：INV-11 按 A 的兑现口径生效，§8.6.5 的上限成为**已接受并须公开呈现**的产品语义（不是被消除），`M-CO-03` 终态确定，AC-06 可按 A 验收。**contract 消费方的解停是条件式的，不由本节单独成立**——与 §20 同一口径：`#3` 需 `#2` 与 `#12` **均已合入**，`#4/#5/#6` 需其上游 issue 依赖满足（见 §16 依赖图）。**DP-3 本身"已落定"只解除它作为决策阻塞的那一条边，不解除 issue 依赖边。**

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
| 11 | GitHub #6 覆盖措辞 | ✅ Sol 已同步（含 2v2 owner/reviewer） |
| 12 | GitHub #7 durable body | ✅ Sol 已同步（含 2v2 owner/reviewer） |
| 13 | PR #12 body | ✅ 已同步：顶部 banner 条件化解停、exact HEAD 绑当前值、旧 verdict 逐条标 stale、current/historical provenance 分节。**证据：** PR #12 body 顶部表的 `exact HEAD` 行 == 本 PR 当前 HEAD，且 `## ⚠️ current head vs historical snapshots` 一节列出全部历史 SHA |

第 11–13 项在仓外。**三项均已同步，因此 DP-3 的 durable closure 成立。**

> **第 13 项是 mutable 表面，因此它的 ✅ 绑 HEAD，不绑一次性动作**：PR body 可以在任意时刻被改回去。**每产生一个新 exact HEAD，本项必须重新核验**——核验式是上表给出的两条（HEAD 相符 + 历史快照分节存在）。上一版把它长期挂 ⬜，同时文档顶部又宣称 frozen baseline，两个口径互相否定；现在改为「⛓ 绑 HEAD 的可复验断言」，而不是「等一个永远不打勾的动作」。

前两轮的漏改都出在"改了结论却没枚举引用点"，因此本清单是穷举式的：**任一条未同步，DP-3 就不算落地**。

---
feature_ids: [F-13, F-19, M-CO-06]
topics: [false-green, judgment-criteria, role-separation, thread-lifecycle, device-acceptance]
doc_kind: decision
created: 2026-08-30
---

# 假绿分类学与判据（2026-08-30 实证）

本文档只收**可复用的判据**，不收叙事。每条都锚到当天的实证，便于后来者验伪。

## 0. 一句话

> 我们用一整天证明「规范说的 = 代码做的」，**没有一格问「人打开它能不能用」**——
> 直到 operator 亲手按了三下 Start，一个动作推翻了两只猫读一天数据库的结论。

---

## 1. 假绿的九个形状（同一内核：看见"长得像值"的东西就当值产出了）

| # | 形状 | 实证 |
|---|------|------|
| 1 | 工作树绿 ≠ commit 绿 | `331dccd` 工作树 exit 0，commit 上 4 FAIL |
| 2 | 字段存在 ≠ 值正确 | PRE-00 接受 `terminalReadMaxDelaySeconds=999` |
| 3 | 指针出现 ≠ 在 operative prose 里 | PR #45 fenced-code 诱饵 |
| 4 | 行首形状 ≠ 受信区 | PR #45 R10 多行 HTML 注释裹住合规行 |
| 5 | 命令回显 ≠ 命令输出 | hostenv probe：`$ echo` 那行含 `os=Darwin`，STDOUT 实为空 |
| 6 | grep 通过 ≠ 值断言 | `grep ^Pkg.Revision=` 对错误版本同样 exit 0 |
| 7 | 静默时长 ≠ 没干活 | compaction 打断、mission header 锁死，worktree 均干净 |
| 8 | 引用存在 ≠ 被引用物存在 | main 引 PR #55 blob，文件不在 main |
| 9 | 轮次数 ≠ 该不该停手 | 真判据是收敛还是发散 |

**第 10 个，当天新增：**

| 10 | 替身绿 ≠ 真身绿 | 验收矩阵要求 `discover()` 回读 `profile-1..10`。参考实现 `FakeQwyProvider.kt:267` 填充该字段，**真 provider `EnvironmentControlHandler.kt:85` 恒返回 `emptyList()`**。判据是照着替身写的，从未对真身跑过。真机上是死循环：永远 FAIL → 清空重种 → 再 FAIL。 |

**第 11 个，2026-08-31 实证：**

| 11 | **分支各自绿 ≠ 合并态绿** | PR #63 与 #65 各自 CI 8/8，`git merge-tree --write-tree` 干净产出 `ce4b2a3f`——**但没有任何一次 CI 跑过 `ce4b2a3f` 这棵树**。两者都改 `APlusComposition.kt`（同一信任腿组合根），行区间不相交所以 Git 无冲突，语义组合却无人验过。**「能合」不是「合了还对」的证据。** |

**连带一条我自己犯的反向错误——「同文件 ≠ 必冲突」：**

我据「两个 PR 改同一文件」断言「后合必然冲突」，**没跑那条一秒就能定案的命令**：

```
git merge-base <a> <b>
git merge-tree --write-tree <a> <b>     # exit=0 且产出 tree = 无文本冲突
```

实测 #63×#65：merge-base `785b8812`，exit=0，tree `ce4b2a3f`，**零冲突**。
被 @codex-sol 当场纠正后我独立复算，值逐字一致。

> 形状同 §1 内核：**看见「改了同一个文件」这个表面信号，就当「冲突」这个结论产出了。**
> 判据：能用一条命令定案的事，不许用推断代替。

> 第 10 条有个诚实的减轻情节：它 **fail-closed**，会大声假红而非安静假绿。
> 它不曾骗过我们，只是会永远挡住我们。**别和 1-9 混为一谈。**

---

## 2. 双向判据（同一把尺，两个方向都用）

不对称的判据会造成路径漂移：严格拦新缺陷、却从不复检旧阻塞，盘面积累幽灵路障。

```
新缺陷 → 不修它，哪个【具名】gate/predicate 会红？
         答得出名字 → 停下处理
         答不出     → 旁注，继续

旧阻塞 → 它【现在】还红吗？说出当前证据。
         说得出 → 保留
         说不出 → 当场撤销，不许继承
```

**实证**：S1 记 BLOCKED、S2 已记 VERIFIED，只读到 S1 就写进交接单，整天停在 operator 身上等一个已经不存在的阻塞。

---

## 3. 「能启动 ≠ 能用」——当天出现三次，同一内核

| # | 我们验了 | 我们没验 | 后果 |
|---|---------|---------|------|
| 1 | 装的字节对不对 | 装完用户还能不能用 | 重装清掉无障碍授权 → Start 变死按钮 |
| 2 | 组件能不能被拉起（`am start` 直测） | 它能不能真的连上 | handshake/discover 路径从未跑过 |
| 3 | 规范说的 = 代码做的 | 人打开它能不能用 | operator：「是不是偏移目标了」 |

**要补的门（尚未落地，见毛线球）**：任何装包块 / 环境变更块的离场检查必须新增一项——
**「该 App 对真实用户是否仍可用」**，至少覆盖 (a) 运行所需系统授权是否仍在，(b) 主入口按钮是否可点。
不可用即离场 FAIL，**不得以「我们没留脏」抵账**。

---

## 4. 「非 canonical」不豁免角色分离，也不豁免托管

当天实证：以「这只是冒烟观察，不是 canonical 证据」为由，在调度线单猫裸跑真机诊断。代价：
- 后台 logcat 哨被 session teardown 撕掉，无完成记录（设备线上有托管命令，能扛 session 死）
- 结论由执行者自读自记自判，零独立见证

**判据——不看证据等级标签，看两个客观问题：**
```
(a) 这次动作会不会产生影响后续决策的结论？  会   → 必须有独立记录者
(b) 依不依赖后台存活 / 跨 session？          依赖 → 必须托管命令，不许裸起后台
```
「冒烟 / 非 canonical」只降低证据等级与归档要求，**不豁免上面两条**。

---

## 5. Thread 停用判据（回答「长期不用的是不是可以停用」）

**静默时长不是判据。** 实证：F-15 线静默 4.6 天、M-CO-06 修复线静默 8.3 小时——两条线绑的 PR 都已合并，完成度完全一样。

```
这条线现在还持有任何"别处没有"的活状态吗？
  绑的 PR 还开着                      → 不能停
  还有未闭合的球                       → 不能停（必须先结球，否则球随线一起失踪）
  是某结论的唯一记录且仍被引用           → 不能停，标「只读引用」
  三条皆否                            → 可停用
```

⚠️ **停用 ≠ 删除。** 停用意味着不再派活、不再唤醒；内容必须继续可检索——
否则就制造了形状 8（引用存在 ≠ 被引用物存在）。

---

## 6. 平台缺陷清单（别当猫的问题）

- typed settlement 反复 422/409；GitHub 双通道可能恒 0/0，**不得据此判「没人审」**。真 verdict 常只在 thread 正文。
  **本仓「reviewer 是否已派」的真相源不是 GitHub**，而是 review lease / carrier：
  `canonical review lease id + generation + carrier messageId + direct carrier threadId + terminal review_delivered@<exact HEAD>`。
  实证：PR #63 在 GitHub 上 `/pulls/63/reviews` 与 `/issues/63/comments` 双 0，
  而 Kimi 的 generation-2 carrier 已于 7 分钟前落地。只查 GitHub 会把「已派」报成「无主」，
  进而诱发重复派单 → 冲突 lease。
- review lease 可能绑旧 head，replace 锁死在 issuer 身份（非 thread）。**别为账本卡住审查。**
- `cross_post_message` freshness gate 可能返回自相矛盾的 HELD（「0 unseen」却拦），且文档写明的 `acknowledgeHeld: true` 逃生门不兑现。改用 `post_message` + `targetCats`。
- mission header 会传染：派实现 / review 必须显式写明「『只做编排不做 review』是调度线约束，**不约束你**」。
- `cat_cafe_hold_ball(wakeWhen)` 返回 503 `HOLD_OWNER_FENCE_UNAVAILABLE`（2026-09-04 连续两次；同一 invocation 内还遇到 `complete_a2a_dispatch` 409 `source_missing`、`ack_mentions` 500 `UNRESOLVED_VISIBILITY_CURSOR`）——托管命令不可用时退到独立 session 的后台进程（见 §11 形状 B），别当猫的问题

---

## 7. 判据是互相的，不是单向的

当天记录：opus5 纠正 fable-5「`emptyList()` 是有意契约裁定」这个未查证的理由；
fable-5 回敬「我的结论对，但我给的理由本身就是我没查证的推断」；
codex-sol 在 12:17 挡下 opus5 的错误派单（执行者不能给自己的证据签字），
opus5 在 22:15 用同一把尺挡回 codex-sol。

**判据在队伍里传递、互相校准，才算落地；只由一只猫记账，等于没有。**

---

## 7b. 「查了两侧」≠「查对了地方」——纪律的失效模式

**实证（2026-08-31 19:31→19:33Z，两分钟内自我推翻）**

判 PR #62 是否冷球时，我照纪律查了第二侧（不只信 GitHub），结论：
「worktree 干净、未推 commit=0，**所以不是在本地干活没推，是真停了**」——**错的**。

```
我查的   /Users/terry/Desktop/coding/fakexxx-wt-g2   = feat/g2-harness-backfill 的工作区
         ↑ 这是我【预期】活儿该在的地方
实际     2c80589 (#62 P1-1 修复) 在 fix/g2-harness-p1-round2
         未推 origin · 无任何 PR 指向 · 工作区在 .claude/worktrees/fix-f14-f12-provider
         （目录名来自一个无关的旧任务——这正是它丢失的方式）
```

**一条命令就能定案，而我把它当顺手补充才跑：**

```
git branch -a --contains <sha>          # 找【东西】在哪，而不是猜它该在哪
git ls-remote --heads origin <branch>   # 它推了吗
gh pr list --head <branch>              # 有 PR 指向它吗
```

> **判据用对了名字，用错了对象。**
> 「两侧都查」的第二侧必须绑在**你要找的证据**上，不能绑在**你预期它在的容器**上。

**衍生的真实缺陷类型**——`#62` 不是冷球，是**交付路径断裂**：
提交存在、活儿做完了，但没接到 PR 分支上，因此对所有基于 GitHub 的检查**结构性不可见**。
静默 23.5h 是**可见性**问题，不是**停工**问题。**误判会去催一只确实在干活的猫。**

---

## 7c. 「GitHub 没动」有三种含义，从 GitHub 侧无法区分

**这条在 48 小时内三次决定了「该催人」还是「该闭嘴」，每次都指向闭嘴。**

```
GitHub 上 PR HEAD 不动，实际可能是：
  ① 没干            —— 真停滞，该催
  ② 干了没推        —— commit 存在于本地分支，丢不了，只是不可见
  ③ 干了没提交      —— 只在工作区，session 一死即在 git 中不存在任何副本
```

**三者在 GitHub 三通道（state / CI / reviews）上完全同形。** 只查 GitHub 必然把 ②③ 误判成 ①，
而 ②③ 的正确动作不是「催」，是「接线」或「先落 commit」。

**分辨配方（成本几秒，收益是不冤枉队友）：**

```
git branch -a --contains <期望的 sha>        # 找②：东西提交到哪条分支了
git ls-remote --heads origin <branch>        # 找②：推了没
git -C <worktree> status --short             # 找③：工作区有没有活
stat -f "%Sm" <改动文件>                      # 找③：最后编辑时间＝还在干还是断了
```

**三次实证：**

| 时间 | 形态 | 若只看 GitHub 会怎样 |
|---|---|---|
| 08-31 19:31 | ② `2c80589` 在未推的 `fix/g2-harness-p1-round2` | 判「作者 23.5h 无响应」→ 催一只已交付的猫 |
| 09-02 11:32 | ③ 11 文件 +435/-173 未提交，含新增 `APlus10AOwnerFence.kt`（正中 reviewer P1） | 判「37.6h 冷球，被跳过」→ 升级误伤 |
| — | ①的真形态尚未在本线出现 | — |

**③ 比 ② 更危险**：②的 commit 丢不了；**③在 git 里根本不存在**，session 死则蒸发。
发现 ③ 时正确动作是让作者先落 WIP commit（不必推、不必完整），**先进 git 再谈完不完整**。

> 判据：**在说「他没动」之前，必须先证明「东西不在别处」。**
> 说不出你查过哪几个地方，那句话就没有证据。

---

## 8. 判据的**对象不对称**——比"不知道判据"更隐蔽

§2 讲的是判据在**时间**上不对称（严拦新缺陷、从不复检旧阻塞）。
当天又暴露出第二种，更难自查：**同一轮里，对结构相同的两个对象用了不同的尺。**

**实证（2026-08-30 23:36Z 巡检）**：同一次 poll 里——

| 对象 | 我怎么处理 |
|------|-----------|
| PR #55 双通道 0/0 | ✅ 正确标注「不是冷球，durable verdict 在 thread 正文」 |
| PR #63 双通道 0/0 | ❌ 写成「零 reviewer」，未查 lease/carrier；实际 carrier 早 7 分钟已落地 |

**两个对象结构完全相同，判据我自己写的，只用在了其中一个身上。**

**自查方法**：巡检产出里若对同类对象给出了不同定性，
**必须能说出「差异来自哪条可验证事实」**；说不出，就是尺不对称，不是对象不同。

> 「知道判据」和「会对称地用判据」是两件事。
> 前者写进文档就完成了，后者只能靠每轮自查——**产出里的每一处差异都要有证据出处。**

---

## 9. 「修窄了」≠「关缝」——轮次不收敛的机制诊断

§1 形状 9 只说了「轮次数不是判据，收敛还是发散才是」。**它没说怎么判、怎么办。** 2026-09-02 的 PR #62 给出了机制。

**症状**：三轮 review，P1 数 `2 → 3 → 3`，后两轮间隔 50 分钟，CI 每轮全绿。

**我当时给的三个假设全不对**：(A) 每次修暴露相邻面 (B) review scope 在扩 (C) 判据还在成形。

**@codex-sol 的实际诊断**（更准）：

> 同样三个 root family 一直在：完整 writer-domain 种子隔离/回读、canonical 原子的
> request-owned start receipt、严格四字段证据绑定。
> **窄修只是把反例边界推远了，没有关掉产生反例的那道权限缝（authority seam）。**

```
修反例   reviewer 举出反例 X → 作者堵住 X → reviewer 从同一条缝里举出 X'
         轮次增加，P1 数不降，双方都很努力
关缝     一次性证明「这条缝里所有反例都不可能」
```

**诊断判据**：连续两轮的 P1 若属于**同一 root family**（而不是新领域），就是修反例不是关缝——**此时增加轮次只会增加轮次。**

**处置**：从「逐轮 patch-review」切到 **batch closure gate**——要求在**同一个 exact HEAD** 上一次性关闭并自证全部 root，再进下一轮 review。#62 已于 2026-09-02 13:18 切换。

> **判据**：轮次不收敛时，先问「这三轮的 P1 是不是同一条缝生出来的」。
> 是 → 停止逐轮修，改批量关缝；否 → 才是正常迭代。

**为什么这条难自查**：双方都在认真工作，每轮都有真实交付、CI 每轮真绿。
**「两个人都很努力」和「这个循环在收敛」是两回事。**

---

## 10. 「不存在 seam」是缺席断言——必须由"产品发布面枚举"支撑，不能由"预期形状未命中"支撑

**实证（2026-09-02，PR #62 R8 P1-2）**

Sol 要求 start 回执落在产品的 canonical atomic start transition 上。作者查了 `createSession`
（只盖 `configSnapshot`，无 caller token）和 `startWithPlan`（返回 `Unit`，内部单飞）后断言
「**无 debug-only seam**，确定性闭合必须改生产」，升级成 Decision Packet（A 改生产 / B 归 operator / C 弱化）。

@opus5 没照单裁决，先翻代码，三个 grep 推翻大前提：产品的 check-and-set **自己会说话**——
reject 分支 `addLog("Already running, ignoring start request")` 发布在公开 `logs: StateFlow` 上，
accept 分支在 launch 前零日志且同步置 `isRunning=true`，`startAutomation` 是同步直调。
调用返回时裁决已在 flow 上（happens-before，不是 poll）。于是「价值取舍题」变回「技术题」：
调用前快照 → 调用 → 调用后读 → 只认可证形状，其余 fail-closed。零 src/main 改动。

**失效机制**：作者按「返回值 / caller token」这一种预期形状搜，搜空即下结论。
这是「碎片够了」病的缺席变体——高置信度命中（`startWithPlan: Unit`）之后停手，
没换角度枚举**产品实际发布了什么**（StateFlow / DB 副作用 / 日志 / 返回值 / 异常）。

```
缺席断言「X 不存在」的判据：
  枚举被观察对象【实际发布】的全部通道，逐一说明为何不构成 X
  ——而不是按【我预期 X 长什么样】搜一遍没命中
```

**连带判据（同一实证）**：借用无版本契约的可观测行为（字符串 sentinel）时，必须
(a) 只认能证明的形状，未知形状一律 fail-closed——引擎 log forwarder 会整体覆写 logs，
    「sentinel 不在 ⇒ 接受」会把真 REJECT 翻成假 ACCEPT；
(b) 用静态 guard 把对方源码里的字面量与前提（接受静默 / 同步直调 / 戳格式）pin 住——
    漂移要**响亮地红**，不能安静地"全部 indeterminate"；
(c) 接手者复核裁决时要再找裁决**没覆盖**的通道：本例 `instance == null` 时 `startAutomation`
    只走 `Log.e`、不写 logs，「logs 未变」单独不是 accept，需加 `isRunning` 腿。

> 判据一句话：**「无 seam」必须由"产品发布面枚举"支撑，不能由"预期形状未命中"支撑。**

---

## 11. 「有输出 ≠ 有终态」——三种"看起来在跑 / 跑完了"的假绿

**实证（2026-09-04，#76 合入后按 exact main `4577abc9` 重切 Auto 候选）**

- **形状 A：wrapper 吞退出码。** `script.sh > out; echo EXIT=$?; tail out` —— 外层 `tail` 成功，harness 报 exit 0，
  脚本内 gradle 其实 rc=1（`SDK location not found`：fresh worktree 无 gitignored `local.properties`、脚本环境无 `ANDROID_HOME`）。
  作者自己中招；Sol 只读复核时按「有没有 `BUILD SUCCESSFUL` + 有没有 APK」判，而不是按 exit code 判，才拆穿。
- **形状 B：后台进程随 invocation 拆除。** `run_in_background` 的构建走到 `:app:kspDebugKotlin` 时 CLI 进程收尾——
  log 无终态行、无 APK、daemon 消失，半截 log「看起来在跑」。此时托管 hold（`hold_ball wakeWhen`）2×503（见 §6）。
  解：`start_new_session` 起独立 session + 前台轮询到终态；产物落盘不依赖 invocation 生命周期。
- **形状 C：`settings put secure enabled_accessibility_services …` 设的无障碍绑定不活过 Auto force-stop。**
  设置读回是「已启用」，按钮 tap 却无效；同一晚复发两次（glm52 记档）。「设置读回=值」≠「系统还在用这个值」。

**失效机制**：三者同一内核——把「进程有输出 / 命令返回了 / 设置读回了」当成终态。
终态是**被观察对象自己发布的终结信号**（`BUILD SUCCESSFUL` + 产物 sha；脚本末行显式 `DONE rc=0`；服务 `bound=true` 由系统侧读出），
不是管道里最后一个命令的退出码，也不是 harness 的 "completed"。

```
判据：
  长命令的「完成」 = 对象自己的终结行 + 产物存在 + 退出码 三者同时成立；缺一按「未完成」处理
  后台任务的「在跑」 = 进程活着（kill -0）且 log 在增长；半截 log 不是进行时，是死亡现场
  环境设置的「生效」 = 消费方行为可观察（tap 生效 / bound=true），不是 settings 读回
```

> 判据一句话：**有输出 ≠ 有终态；终态由对象自己发布，不由管道尾巴代言。**

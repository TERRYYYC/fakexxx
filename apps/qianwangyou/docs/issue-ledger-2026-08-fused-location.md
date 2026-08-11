---
feature_ids: [F001]
topics: [android, mock-location, fused, evidence, acceptance, provenance, lessons]
doc_kind: issue
created: 2026-08-07
status: in-progress
---

# Issue 与证据纪律台账 — 2026-08 fused location 轮次

本文件是**索引与边界声明**，不是结论的副本。每条都指向既有 canonical doc；canonical doc 说了
什么就以它为准，这里只补两件它们各自说不全的事：**这一轮的结论边界到哪为止**，以及**下次别再
踩的判据**。

刻意排除：review lease / projection / evidenceRef 一类**团队协作治理**教训不写在这里。它们不是
FakeGPS 的项目知识，应路由既有 Process Evolution 真相源。本文件只收 Android / 定位 / 证据方法学。

---

## 一、Issue 状态台账

### Issue #15 — PASS，但 PASS 的是被明确划定的那一块

verdict：**PASS / done**。下面三条是这个 PASS **不覆盖**的范围，任何引用 #15 的人先读这三条：

| 面 | 覆盖方式 | 边界 |
|---|---|---|
| listener 路径 | 真机行为覆盖 | 这是 PASS 的实证主体 |
| recenter | **依赖 operator attestation** | 不是自动化断言，是人工确认；换设备/换版本不自动继承 |
| LAST / CURRENT Task surface | **本机行为未触发** | 未被证伪，也未被证实——是"没测到"，不是"测过没问题" |

把 #15 转述成"fused 路径全面 PASS"就是越界。per-delivery 证据（PR #21）之所以要做，正是因为
install-time 证据让"安静地正常投递"和"停止投递"不可区分，逼得 #15 的 A/B matrix 一度 BLOCKED。
参见 `app/src/main/java/name/caiyao/fakegps/hook/DeliveryEvidencePolicy.java`。

### Issue #14 — bounded NOT-REPRODUCED，ROOT UNKNOWN

**只能这样写**：在当前这台设备、这个版本上，按已执行的复现路径**未复现**。

不能写的三种说法，以及为什么：

- ❌「已修复」——没有定位到根因，也就没有"修"这个动作。**ROOT UNKNOWN**。
- ❌「普遍 PASS」——样本是一台设备一个版本，外推不成立。
- ❌「不存在」——见下面第三条：absence 结论必须与探针范围匹配。

**`IMMEDIATE_0_30_REPLAY` 仍 NOT TESTED**：即时（0–30s）第三方 / bench replay 这条路径这一轮
根本没跑。它既没通过也没失败，是空白。下一轮谁接手 #14，第一件事是补这段，而不是重复已经
NOT-REPRODUCED 的那条路径（理由见第四条）。

相关：`docs/bug-report/fused-real-location-flapping/bug-report.md`。

### 本轮新发现，尚未处理

**`scripts/cellular_acceptance_matrix.py` 的 schemaVersion 已漂移** — master 上**既有**红灯，
不是本轮改动引入。

- `app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt:49` 声明 `SCHEMA_VERSION = 4`
  （PR #10 升上去的），而 `scripts/cellular_acceptance_matrix.py:224` 自 PR #3 起一直硬编码
  `"schemaVersion": 3`，从未跟进。
- `scripts/test_cellular_acceptance_matrix.py::test_python_payload_version_is_pinned_to_writer_contract`
  正在如实报红（`4 != 3`）——这个漂移探测器本身是好的，它抓到了真东西。
- 影响面：蜂窝验收 harness 一直在按**旧 schema** 发 payload。`PREVIOUS_SCHEMA_VERSION = 3` 还在，
  所以 reader 大概率仍接受，于是它**没有炸**——但它验的是兼容路径，不是当前出货路径。
- **本轮不修**：v3→v4 到底加了哪些字段没有核实，把 `3` 直接改成 `4` 只会让 payload 谎报自己的
  版本——比现在更糟。这需要单独定位再修，属另一条线。

---

## 二、证据方法学：这一轮实际付了学费的判据

### 1. absence 结论必须与探针范围匹配

"没查到"只在**探针能查到的范围内**成立。整机 mock baseline 必须用**全量 app-op 枚举**得出；
拿单包查询的结果外推成"整机没有 mock"是无效推理——你只证明了那一个包。

写 absence 结论时把探针范围一起写进去，否则这句话下次会被当成更强的断言引用。

### 2. `/proc/<pid>/maps` 里找不到模块 APK，证明不了 Vector 的 in-memory dex 未加载

注意别把这条写过头：`maps` **并非只列文件映射**，匿名 VMA 同样在列。所以"`maps` 里什么都没有"
这种说法本身就是错的。

能成立的只有这一句：**在 `maps` 中找不到模块 APK 的 file-backed 映射 / 路径，不构成"该模块未
加载"的证据**。in-memory dex 不以文件形式被映射，它的确会以匿名 VMA 出现，但匿名 VMA 无法凭
`maps` 归属到具体模块——探针能分辨的粒度不足以回答这个问题。

要判定加载与否，得换能回答它的探针（module 侧日志、classloader 侧证据），而不是把 `maps` 的
沉默当结论。属于第 1 条的具体案例。

### 3. 同一台坏仪器重复读数，不是独立证据

同一条有缺陷的复现路径跑 N 次，得到的是 1 份证据，不是 N 份。#14 的
NOT-REPRODUCED 不会因为再跑几遍而变强。要提高置信度只能**换探针**（换设备、换版本、换触发
路径——比如那条还没跑的 `IMMEDIATE_0_30_REPLAY`）。

### 4. 缺日志 ≠ 失败，按固定顺序逐层排除

没看到预期日志时，**依次**核这五层，不要跳：

1. module 有没有加载
2. config 有没有下发
3. surface 有没有 hook 上
4. provider 有没有真的发起 request
5. 设备是不是**解锁态**

第 5 层最容易漏——锁屏态下很多定位消费者根本不请求，于是"没日志"完全正常。

这正是 per-delivery 证据要解决的问题：让沉默**可判读**——但它并没有让沉默变成结论。

准确的说法是：心跳在，"安静"可以读作"在正常投递"；心跳**不在**，只把"停止投递"从被排除
状态变成**候选之一**，不等于"停了"。要下这个判定，至少还得：

- 绑定**预期心跳窗口**（当前 `HEARTBEAT_MS` = 30s）。窗口没到就谈缺失，是在量还没发生的事。
- 先走完上面五层（module / config / surface / provider request / 解锁态）。
- 再确认**证据管线本身可用**——没有心跳可能只是遥测坏了，而不是投递坏了。

最后一条不是补充，是和下一节的硬约束配套的：既然「证据异常不得影响投递」，那么反过来，
证据缺失也就**不能**直接推出投递异常。两边必须一起成立，否则这套遥测就自相矛盾。

### 5. `fused[mock]=0` 单点证明不了真实位置泄漏

一个 `fused[mock]=0` 只是一个**读数**。判泄漏至少还要两样：

- **消费者请求**：有没有人真的在请求这条 surface？没人请求时的陈旧值不构成泄漏。
- **value lineage**：这个值是从哪来的？是 hook 前的原始输入，还是缓存，还是 hook 后的产物？

缺这两样就下"泄漏"结论，会把正常的缓存读数报成 P0。参见
`docs/bug-report/fused-real-location-flapping/bug-report.md`。

### 6. 遥测双轴的四条硬约束

两个轴（delivered / input）同时上报时：

- **互斥词表**：两轴不能共用 token。第一版让 interception 复用 delivery 词表，结果健康的拦截
  （真实值被档案位移）报出 `NOT_EQUAL`，而验收 harness 把 `NOT_EQUAL` 读成 snap-back——**结论
  被反转**。所以有了 `INPUT_*` 这套不可能与投递失败混淆的独立词表。
- **同时进入 gate**：只用 delivered 轴做边沿触发等于没触发——该轴按构造几乎恒定（出参就是拿
  比较基准那份快照构造的），真正在变的是 input 轴。
- **token 与 count 同行**：一行证据的 token 必须描述它所计数的**每一次**投递。拿当前 input 配
  累计 count，这一行就会声称 N 次投递共享了一个它们并不共享的状态。
- **稀疏边沿必须在同一 callback 内可见**：one-shot / 稀疏 surface 可能再也不投递了，没有下一次
  心跳来带出这条边沿。**延迟上报的边沿等于从未发生过**。

契约见 `app/src/test/java/name/caiyao/fakegps/hook/DeliveryEvidencePolicyTest.java`。

### 7. 证据异常不得影响投递

遥测出问题时，坏的是遥测，不是功能。证据链路上的任何异常都不能改变或中断实际的位置投递——
否则"为了看清楚"反而制造了要看的那个故障。

### 8. APK hash 必须与 exact source + Gradle runtime JDK 绑定

同一份 clean 源码在不同 Gradle runtime JDK 下产出**不同字节**（JDK 17 为 enum switch 多生成一个
`UnavailableValueResolver$1`，D8 把差异带进 `classes3.dex` / `classes11.dex`）。裸 sha256 因此
**不是**跨环境的源同一性证据，把它当证据会把合法的 JDK lowering 差异误判成"脏源码"。

这一轮之前它只是条**约定**，然后在 PR #21 被第二次违反。现已改为机制：
`scripts/apk_provenance.py` 是 APK 证据行的唯一合法产出口，缺 JDK 则不输出任何行并 exit 2。

顺带记四条**做这个工具时自己踩的**，比工具本身更值得留：

- **读取时刻 ≠ 构建时刻**。第一版读调用时刻的 git 状态就宣称绑定了 exact source；实际上
  "commit A 构建、切到 B、再采集"会把 A 的字节标成 B。
- **"夹住"不等于"因果"**。第二版把构建夹在两次 source 读取之间就以为够了，被 review 一击
  打穿：`--build help gradlew` 退出 0，把 Gradle wrapper 脚本签成了 `built`。夹住只证明**树没
  动**，对**被哈希的字节**一无所知。真正的因果证据是：产物先清除、构建后**重新出现**。
- **探针修好了，分类器可以把洞重新打开**。第三版已能看见被 ignore 的文件，却用
  `"/build/" in path` 判定"这是构建产物"，于是
  `app/src/main/assets/build/hidden.apk` 被豁免，source 又读回 clean——而该文件确实进了已签名
  APK。`app/src/main/assets/` 是本仓库真实的打包目录（内含 tracked 的 `xposed_init`），所以这
  是真实路径不是构造场景。**豁免规则必须是显式注册的精确根**，不能用子串或后缀；默认值要落在
  "不是产物"这一侧，让误判方向是多报脏而不是漏报干净。
- **`rm -rf` 清理复现物会连带删掉 tracked 文件**。清 `app/src/main/assets/` 时把
  `xposed_init` 一起删了（此前用 `rmdir` 是安全的：目录非空会失败）。已 `git checkout --`
  完整恢复、与 HEAD 零差异。清理实验残留时用**精确路径**或会失败的 `rmdir`，别用递归强删。

共同的教训：**验证了"环境没变"，不等于验证了"产物出自这个环境"**。前者是背景条件，后者才是
待证命题；把前者当后者，是这一轮里反复出现的同一个思维滑坡。

### 8b. 负向断言必须自证它到达了目标阶段

一条"应该失败 / 应该 exit 2 / 应该抛异常"的断言，**只看结果是不成立的**——任何更早的、与被测
逻辑无关的失败都能让它变绿。

本轮实测三次，全是同一个根：

| # | 假绿 | 真实原因 |
|---|---|---|
| 1 | `built` 契约测试哈希的是 fake build 从未写过的 tempfile | 断言了 `built` 出现，没验证产物由该次构建产生 |
| 2 | "树在构建中移动"用例传了 caller path | 在 caller-path 守卫处**提前退出**，两次 source 读取根本没执行 |
| 3 | `OSError` 用例走 `main()`，测试环境无 `JAVA_HOME` | 死在 `gradlew --version`，从未到达哈希步骤 |

判据：负向用例除了断言结果，还要断言**它确实执行到了目标阶段**——用路径哨兵（记录被调用的
函数/参数）或可观测副作用（产物出现/消失、计数器递增）。#3 现在断言 `reached` 恰好一次；#1
换成"产物只在 fake build 写入时才存在"的 fixture；#2 直接删除，由真正覆盖它的用例接手。

这与第 1 条同源：**absence 结论必须与探针范围匹配**。"没报错"和"没日志"一样，只有在证明了探针
确实跑到那一步之后才有含义。

现在 `built` 要求：任务有声明产物 + 产物清除后重现 + 构建前后 source clean 且未变 + 路径由任务
推导。`asserted` **不是** exact-source binding，不得当源同一性引用。
完整设计与仍未关闭的部分见
`docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md#结构性根治2026-08-07-追加`。

⚠️ **Gradle runtime JDK 至今未固定**。漂移向量还在，只是现在每条证据都会记下自己落在哪一侧。

### 9. 模式切换后的 `0660/location=false` —— 二义性 finding，禁止先写成 bug

现象为真，**解释未定**。它可能是失效，也可能是既有传播语义的正常中间态（已运行的 Hook 目标
进程按 5–60 秒周期读取 mode，切换期间本就允许短暂重叠）。

在区分开之前，它只能记为 **finding**。先写成 bug 会把一个还没定性的观察固化成缺陷叙事，
后面所有人都顺着这个叙事找原因。**先定性，再命名。**

---

## 引用的 canonical docs

| 主题 | 真相源 |
|---|---|
| APK hash / JDK 漂移 | `docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md` |
| fused 真实位置抖动 | `docs/bug-report/fused-real-location-flapping/bug-report.md` |
| 主线集成验收证据 | `docs/acceptance/mock-location-main-integration-evidence.md` |
| Lab 历史证据（已退役） | `docs/acceptance/mock-location-v2-evidence.md` |
| per-delivery 证据契约 | `app/src/test/java/name/caiyao/fakegps/hook/DeliveryEvidencePolicyTest.java` |
| APK provenance 契约 | `scripts/test_apk_provenance.py` |

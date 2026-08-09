---
feature_ids: [F001]
topics: [android, build, provenance, jdk, reproducibility]
doc_kind: bug-report
created: 2026-08-03
status: resolved
resolved: 2026-08-03
---

# Debug APK hash 跨 JDK 漂移被误判为脏源码

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | PR #10 R3 的作者 evidence 记录 debug APK `0aa312f2…f9cbc`；Fable5 从 exact HEAD 四次构建均得到 `83e725aa…ebc4`，因而怀疑作者真机安装的是脏工作区或残留产物。期望是 artifact provenance 能区分源码与构建环境输入。 |
| **2. 证据** | 作者 worktree 在 clean exact HEAD `343fa455…` 上用 Android Studio JBR 21.0.10 重建仍得到 `0aa312f2…f9cbc`；同一 worktree 改用 Homebrew OpenJDK 17.0.20 重建即得到 `83e725aa…ebc4`。对应 Gradle daemon 分别记录 `javaVersion=21` 与 `javaVersion=17`。 |
| **3. 根因** | `sourceCompatibility` / `targetCompatibility = 17` 只约束 classfile 目标，不固定执行 javac/Gradle 的 JDK。JDK 17 对 `UnavailableValueResolver` 的 enum switch 额外生成 `$1` synthetic class，JDK 21 不生成；D8 因此改变 `classes3.dex` 与 `classes11.dex`。旧 evidence 把 source SHA 当成 debug APK hash 的全部输入，遗漏 JDK provenance。 |
| **4. 诊断策略** | 先从 clean exact HEAD 重建证伪“残留 build”，再对两个 APK 做逐 entry SHA、签名证书与 DEX class-tree 比较，最后只改变 `JAVA_HOME` 做单变量重建。 |
| **5. 超时策略** | 若单变量 JDK 重建不能复现两种 hash，则继续比较 AGP/SDK/debug keystore；在输入闭包明确前不改写原始 dogfood artifact。 |
| **6. 预警策略** | 只替换成某一 reviewer hash、或继续声称跨环境唯一 hash，会抹掉真实安装产物并让下一次跨 JDK review 再次误报。 |
| **7. 用户可见交互修正** | 无产品 UI 变化；evidence 同时记录作者 JBR 21 与 reviewer JDK 17 产物，hash 必须和构建 JDK 一起引用。 |
| **8. 验收** | JBR 21 clean build 稳定为 `0aa312f2…f9cbc`；JDK 17 clean build 稳定为 `83e725aa…ebc4`；两 APK 同签名、同资源，只有两个 DEX entry 不同；文档不再宣称 debug hash 仅由 exact source 决定。 |

## 报告人

Fable5 在 PR #10 R3 独立复审中发现 hash 不一致；Sol 负责复现、APK 内容对比与构建输入逆向追踪。

## 复现步骤

1. checkout PR #10 exact HEAD `343fa455bfd4c7f420e371276a062175bc0462cf` 并保持 tracked worktree clean。
2. 用 Android Studio JBR 21.0.10 执行 `:app:clean :app:assembleDebug --rerun-tasks`，得到 `0aa312f2…f9cbc`。
3. 只把 `JAVA_HOME` 改为 Homebrew OpenJDK 17.0.20，重复同一命令，得到 `83e725aa…ebc4`。
4. 比较 APK entries：签名证书、资源和 16 个 DEX 相同；`classes3.dex` / `classes11.dex` 不同，JDK 17 产物含 `UnavailableValueResolver$1`。

## 根因分析

R3 没有引入脏源码或残留 build。差异来自未纳入旧 provenance 的 Gradle runtime JDK：Java 17 target compatibility 不等于 Java 17 compiler/toolchain pin。javac 的合法 lowering 差异继续传播到 D8，因此同一源码可以产生两个功能等价但字节不同的 debug APK。

## 修复方案

- 保留作者真机实际安装的 JBR 21 hash，避免篡改既有验收链。
- 新增 reviewer 真机实际安装的 JDK 17 hash。
- 每个 hash 都显式绑定 exact source 与 Gradle runtime JDK；删除“debug hash 跨环境唯一”的断言。
- 本轮不把 JDK toolchain pin 混入已获代码 APPROVE 的功能 PR；若未来需要单一 canonical artifact，应在独立 build-system change 中固定 toolchain 并重新 review。

## 验证方式

- 两种 JDK 均从 `:app:clean` 开始，单变量重建分别复现两种 hash。
- `apksigner verify --print-certs`：两者证书 SHA-256 均为 `7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41`。
- APK 逐 entry SHA：仅 `classes3.dex` 与 `classes11.dex` 不同。
- Fable5 已用 JDK 17 产物完成 R3 全链真机验收 exit 0；作者此前用 JBR 21 产物完成两次全链 exit 0。

## 结构性根治（2026-08-07 追加）

上面的"修复方案"只产出了一条**约定**：以后写 hash 要带 JDK。这条约定在 PR #21 的 review 中
再次被违反——作者报了一个无 JDK 限定词的 `44fdb130…903812`，reviewer 两个环境各得一个不同
hash，三者互不相同，重新触发同一场"是不是脏源码"的怀疑。同一个坑踩第二次，说明缺的不是提醒
而是机制。

审计当时的仓库状态，可以看出为什么约定必然失效：

- 没有任何脚本产出过 APK hash 证据。唯一计算 APK sha256 的地方是
  `scripts/test-hook.sh:247`，且只用于 install 幂等判断，算完即丢，从不打印。
- `scripts/mock_provider_acceptance.sh` 这条主验收链安装 APK 时完全不做摘要。
- 全仓库 `javaVersion` 只出现过一次，就是本报告第 2 行手打的那次。
- 因此**每一个进入文档的 APK hash 都是人手算、人手贴的**，JDK 限定词是否出现完全取决于
  当事人当时记不记得。

修复：新增 `scripts/apk_provenance.py`，作为 APK 证据行的唯一合法产出口。

```
APK_PROVENANCE apk=<name> apk_sha256=<64hex> source=<40hex>[+dirty] source_binding=built|asserted jdk=<vendor>@<version> gradle=<ver>
```

发布证据用（一条命令完成构建 + 绑定）：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  python3 scripts/apk_provenance.py --build :app:assembleRelease
```

**不传产物路径**——`--build` 从任务推导产物，同时传路径会被拒绝（exit 2），即使它恰好相同。
理由见下面第 6 点：两个真相源并存时，读证据行的人无法判断当时采信了哪一个。

让"忘记标注"不可表达的几个设计点：

1. **单一装配口**：所有路径都汇入 `format_line()`，它逐字段校验后原子拼行；JDK 缺失或形如
   `unknown` / `TODO` 一律抛异常。不存在能产出裸 hash 的代码路径。
2. **失败即静默**：任一输入不可确定就不输出**任何**行，stderr 打 `HARNESS_ERROR`、exit 2。
   半条线看起来像能用的证据，空输出不会。
3. **取 daemon 而非 launcher JVM**：真正跑 javac/D8 的是 daemon。二者在设了
   `org.gradle.java.home` 时不同，报 launcher 会把字节归给错误的编译器。JDK 身份从该 JDK 自己的
   `release` 文件读取，不用可能并非 Gradle 所用的环境 `java -version`。
4. **dirty 是后缀不是邻列**：`source=<sha>+dirty`。邻列 `tree=dirty` 在有人把 sha 复制进文档时
   会被落下，后缀跟着 sha 走。
5. **不收 JDK 路径**：它不是构建身份（JBR 21.0.10 在 `/opt` 与 `/Applications` 产出相同字节），
   而真实路径含空格（`/Applications/Android Studio.app/…`）会破坏 harness 依赖的
   `key=value` 行文法。此点由测试逼出——首版把它当字段，结果在本轮基线这个最常见环境下
   直接失败。

6. **source 绑定的强度写在行里，且 `built` 必须证明因果**：读取调用时刻的工作树并不能证明
   APK 出自该树——在 commit A 构建、切到 B、再跑本工具，A 的字节就会被标成 B。

   第一版只做了"两次 source 读取夹住构建"，**这不够**，并在 review 中被实证击穿：
   `--build help gradlew` 退出 0，把 Gradle wrapper 脚本签成了 `built`。夹住构建只证明树没动，
   对被哈希的字节一无所知。

   现在 `built` 同时意味着四件事，缺一不可：

   - 任务在 `BUILD_TARGETS` 白名单内，**有声明产物**（没有产物的任务无从担保任何东西）；
   - 该产物在构建前被**清除**，构建后**重新出现**——只有"重现"才是因果证据，"构建后文件存在"
     会被任意旧构建的陈旧产物满足；
   - 构建前树 **clean**，构建后 source 未变；
   - 产物路径由**任务推导**，调用方传路径一律拒绝——**包括恰好相同的路径**。放行"反正一致"
     会把契约变成"校验了但不使用"，而读证据行的人无从判断当时采信了哪个真相源。

   `source_binding` 字段必填且受校验：一条证据行不允许对"绑定是实测还是假定"保持沉默，因为
   沉默会被读成实测。结构上还禁止 `built` 与 `+dirty` 同时出现。

7. **untracked 也算脏——只要它是构建输入**：`--untracked-files=no` 会漏掉未跟踪的
   `app/src/**`，而它照样编进 APK，于是行里会出现一个"clean `source=<HEAD>`"，但 HEAD 并不
   描述那些字节。现按 `is_build_input()` 判定：`app/`、`gradle/`、`*.gradle`、`gradle.properties`
   等算输入；docs / 治理文件 / 草稿不算——把后者也当脏会让任何真实 checkout 永远无法签发。

8. **被 ignore 的文件同样要看**：`--untracked-files=all` **仍然漏**，因为它不含 ignored 条目，
   而本仓库全局 ignore 了 `*.apk` / `*.dex` / `*.class`——这三种恰恰是 `app/src/main/assets/`
   下的合法打包资产。review 实证：往 `app/src/main/assets/` 放一个 `.apk`，`git status` 全程
   为空，工具照签 `built`，而 `unzip -l` 证明该文件**确实在已签名的 APK 里**。

   现在改用 `git status --porcelain --untracked-files=all --ignored=matching`。选 `matching`
   而非 `traditional`：前者把整体被忽略的根 collapse 成 `.gradle/`、`app/build/`、`build/`
   三行，同时仍单独列出 `app/src/main/assets/hidden.apk`；后者会把那些根展开成上千行。
   ignored 条目再经 `is_generated()` 排除构建产物与缓存——它们由构建**产生**，若算作脏，任何
   构建完的树都将永远无法签发。

   `is_generated()` 只认**显式注册的精确根**（`build`、`app/build`、`.gradle`、`.idea`、
   `.kotlin`、`app/.cxx`），命中条件是"等于该根或位于其下"。**不得**使用 `"/build/" in path`
   或 `endswith("/build")` 之类的子串 / 后缀判定：源码树里完全可以有名为 `build` 的目录，
   review 第二轮正是用 `app/src/main/assets/build/hidden.apk` 击穿了这一点——探针看见了它，
   分类器却把它当产物豁免，source 读回 clean，而文件确实进了已签名 APK。新增模块产物根必须
   显式登记；默认落在"不是产物"一侧，使误判方向为多报脏而非漏报干净。

   对应回归测试跑的是**真实 git 仓库与真实 ignore 语义**，不是手写的 porcelain 字符串——因为
   这个缺陷的本质就是 git 从不报告该文件，mock 一行 `??` 永远发现不了它。

### claim ceiling（引用时的上限）

- `source_binding=built` —— **可**作为"这些字节由该 exact clean source 在该 JDK 下构建"的凭证。
- `source_binding=asserted` —— **不是** exact-source binding，**不得**这样引用。它只声明
  "在采集时刻，仓库处于 `source=` 所述状态"，与该 APK 的来源**无因果关联**。对已安装 /
  第三方 / 无法观测构建过程的 artifact，这是**正确**的声明，不是降级；但发布证据必须用 `built`。

契约测试见 `scripts/test_apk_provenance.py`（42 项）。其中 `BuiltBindingTest` 是 `built` 因果性
击穿用例的回归套件，`RealGitIgnoreSemanticsTest` 驱动真实 git 仓库验证 ignore 语义。

**仍未关闭的部分**：本轮**没有**固定 Gradle runtime JDK。`gradle.properties` 无
`org.gradle.java.home`，也没有 `java { toolchain { } }`，`gradlew` 仍取环境 `JAVA_HOME`——漂移
向量本身还在，只是现在每条证据都会如实记下自己落在哪一侧。按原"修复方案"最后一条，toolchain
pin 属于独立的 build-system change，应单独提 PR 并重新 review，不混进功能 PR。

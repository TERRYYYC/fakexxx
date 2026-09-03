---
feature_ids: [71, 73]
topics: [issue-71, pr-73, android, binder, authorization, emulator, evidence]
doc_kind: evidence
created: 2026-09-03
exact_head: 9c6afecd7f62682dce7376d582ddd58c3776b840
status: identity-review-p1-closed-not-g2-acceptance
---

# PR #73 · Issue #71 Binder 身份修复：exact-head API-35 `ranchu` 模拟器 instrumented 证据

## 结论与边界

对 PR #73 **exact HEAD `9c6afecd7f62682dce7376d582ddd58c3776b840`**（生产改动 = `EnvironmentControlService`
一处：先取 kernel 提供的 caller UID → `clearCallingIdentity()` → try → `finally` restore），在一台**新建的、隔离的
API 35 / `ro.hardware=ranchu` 模拟器**上，用该 HEAD 构建的 debug + androidTest APK 运行
`BinderIdentityInstrumentedTest`：**`OK (2 tests)`**。

它证明的是：修复后的服务在真实跨 UID 的 Binder 调用里，(a) 以 QWY 自身身份完成本地工作（含读取自家非导出
`AppInfoProvider`），(b) 用捕获的远端 UID 做授权，(c) 在四种退出路径上都在**同一事务内**恢复来访身份。
本轮 logcat 中 **`Permission Denial` 行数 = 0**（对照 Issue #71 原始失败：`Permission Denial: reading
name.caiyao.fakegps.data.AppInfoProvider … from pid=… uid=… requires the provider be exported`）。

它**不**证明：配置跨进程传输（#71 问题 B——本轮仍是 `published=false readable=false`，模拟器上
`MODE_WORLD_READABLE no longer supported` 落到 app-private 路径）、独立定位回读、Moto Start、任何 G2 验收。
`verification=3` 是 `NONE`。这份文件只闭合 Terra 对 #73 的 identity-review P1（评审消息 `…000363`）。

## 为什么需要这份 exact-head 证据

Terra 的 exact-head review（无生产代码缺陷）指出：#72 的 Android 15 红→绿证据跑在 #72 的测试提交上，
到 #73 当前 HEAD 之间 QWY main 树有 47 个路径差异（含 `ConfigPrefsSync` / `EnvironmentControlHandler` /
`ProviderRuntime` / `QwyEnvironmentController` / lease-store），CI 8/8 与 `compileDebugKotlin` /
`compileDebugAndroidTestKotlin` 都不执行 instrumentation，因此只能作 continuity evidence。
另：控制面对同一 HEAD 的 `reviewReentry` 必然 `safe_wait`（terminal freshnessKey 未变），所以本证据以
**只含文档的新 commit** 落仓——生产树与 `9c6afecd` 逐字相同。

## 实际执行范围

| 项目 | 实际值 |
| --- | --- |
| 运行者 / 记录者 | 运行：狸花猫 glm52（PR 作者，`LiHuaCat-GLM-5.2`）；独立复核 + 落仓：仙仙51（committer，非 reviewer） |
| 工作区 | `/Users/terry/Desktop/coding/fakexxx-issue71-main`，分支 `fix/issue71-binder-identity-main`，构建时 `git status --short` 为空 |
| 被测 HEAD | `9c6afecd7f62682dce7376d582ddd58c3776b840`（committed 2026-09-03T12:56:23Z，GitHub #73 head 同值，非 draft，CI 8/8） |
| 构建 | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`（JDK 17，`sdk.dir` 本机 SDK） |
| debug APK sha256 | `63a38042f0e623e6240a24e1ab7be0666c36fce78c568b96b4cb8992a892cca5`（`app/build/outputs/apk/debug/app-debug.apk`，23,161,645 B） |
| androidTest APK sha256 | `d4c615773bfe525a6b6eb302690333e18785acefea478e0d09d840b74b8896e3`（`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，450,819 B） |
| 哈希复核 | 落仓前由 committer 用 `shasum -a 256` 对同一工作区产物重算，与运行者记录逐字一致；Terra 复核（`…000389`）亦称与 exact-head 产物一致 |
| 模拟器 | 新建 AVD `glm73_api35`，镜像 `system-images/android-35/google_apis/arm64-v8a`，emulator 36.6.11.0，`-wipe-data -no-snapshot` 冷启，serial `emulator-5554` |
| 设备属性（`props.txt`） | `ro.build.version.sdk=35`；`ro.hardware=ranchu`；`ro.boot.hardware=ranchu` |
| 启动日志（`emulator-boot.log`） | `androidboot.qemu.avd_name=glm73_api35`；`androidboot.hardware=ranchu`；`Boot completed in 20767 ms`；`Snapshots have been disabled by the user` |
| 数据 / 授权 | 仅 `pm clear` 两个 bench 包（`name.caiyao.fakegps.bench`、`name.caiyao.fakegps.bench.test`）；`appops set name.caiyao.fakegps.bench android:mock_location allow`（设备等价前提，非代码变更） |
| 真机 | **Moto ZY22JHW9M4 全程零接触**；所有 adb 调用绑定模拟器 serial |

## 精确命令

```sh
adb -s emulator-5554 shell am instrument -w \
  -e class name.caiyao.fakegps.integration.v1.BinderIdentityInstrumentedTest \
  name.caiyao.fakegps.bench.test/androidx.test.runner.AndroidJUnitRunner
```

## 结果（原始输出摘录，未改写）

`am instrument -w` 标准输出（`instrument-fresh.log`）：

```text
name.caiyao.fakegps.integration.v1.BinderIdentityInstrumentedTest:..

Time: 0.469

OK (2 tests)
```

logcat 时间窗内（本轮）的 runner 生命周期与测试标记：

```text
09-03 16:44:39.029  3914  3971 I TestRunner: run started: 2 tests
09-03 16:44:39.029  3914  3971 I TestRunner: started: identityIsRestoredInsideTheSameIncomingTransactionOnEveryExit(name.caiyao.fakegps.integration.v1.BinderIdentityInstrumentedTest)
09-03 16:44:39.128  3914  3971 I BinderIdentityTest: QWY uid=10207 remote uid=10208 pid=3974
09-03 16:44:39.142  3914  3971 I TestRunner: finished: identityIsRestoredInsideTheSameIncomingTransactionOnEveryExit(name.caiyao.fakegps.integration.v1.BinderIdentityInstrumentedTest)
09-03 16:44:39.146  3914  3971 I TestRunner: started: approvedRemoteCallerCanPublishFromTheRealPrivateProvider(name.caiyao.fakegps.integration.v1.BinderIdentityInstrumentedTest)
09-03 16:44:39.300  3914  3971 I BinderIdentityTest: QWY uid=10207 remote uid=10208 pid=3974
09-03 16:44:39.448  3914  3971 I BinderIdentityTest: private-provider payload matched; verification=3
09-03 16:44:39.497  3914  3971 I TestRunner: finished: approvedRemoteCallerCanPublishFromTheRealPrivateProvider(name.caiyao.fakegps.integration.v1.BinderIdentityInstrumentedTest)
09-03 16:44:39.498  3914  3971 I TestRunner: run finished: 2 tests, 0 failed, 0 ignored
```

**真实、不同的进程 UID**——测试自报之外，system_server 的旁证（同一时间窗）：

```text
09-03 16:44:37.859   568  1136 I ActivityManager: Force stopping name.caiyao.fakegps.bench appid=10207 user=0: start instr
09-03 16:44:39.036   568   898 I AppWidgetServiceImpl: Updating package stopped masked state for uid 10208 package name.caiyao.fakegps.bench.test isStopped false
```

即被测 QWY（`name.caiyao.fakegps.bench`）= uid **10207**，远端 caller（测试包 `…bench.test`，经
`RemoteBinderRelayService` 以自身 UID 发起真实 Binder 事务）= uid **10208**。

身份拒绝行计数（`grep -c -E "Permission Denial|requires the provider be exported"`）：**0**。

## 四种退出路径 × 五项身份断言（`identityIsRestoredInsideTheSameIncomingTransactionOnEveryExit`）

测试对 `withProviderBinderIdentity` 的每一种退出路径——`return`、`typed-rejection`（`ContractException`
→ typed result）、`initialization-throw`、`execution-throw`——都在**同一次来访事务内**读取并断言：

| 观测点 | 期望 | 含义 |
| --- | --- | --- |
| `before` | remote uid（10208） | 事务进入时的 kernel caller |
| `captured principal` | remote uid（10208） | 传给授权逻辑的 UID = clear 之前捕获的 caller |
| `local work` | `Process.myUid()`（10207） | clear 之后本地工作以 QWY 身份执行 |
| `nested restore` | `Process.myUid()`（10207） | 嵌套作用域退出后仍处于外层 QWY 身份 |
| `finally restore` | remote uid（10208） | 无论正常返回、typed 拒绝、初始化抛出、执行抛出，退出后来访身份已恢复 |

外加 `outcome == exit`，保证每条路径确实走到了它声称的退出方式（不是异常被吞掉后的假绿）。
第二个测试 `approvedRemoteCallerCanPublishFromTheRealPrivateProvider` 则以真实远端 UID 走 `NOT_PAIRED`
拒绝 → 精确配对批准 → `apply`，并要求 apply 后新 payload 中出现本轮随机 marker（`private-provider payload
matched`）——即 QWY 在远端调用内成功读取了自家非导出 provider。

## 仍然为红 / 不声称的部分（原始行保留）

```text
09-03 16:44:39.268  3914  3971 E ConfigPrefsSync: MODE_WORLD_READABLE rejected (SecurityException) — MODE_PRIVATE fallback
09-03 16:44:39.432  3914  3971 E ConfigPrefsSync: transport resolved to app-private file /data/user/0/name.caiyao.fakegps.bench/shared_prefs/spoof_config.xml; not cross-process reachable
09-03 16:44:39.437  3914  3971 W ConfigPrefsSync: published=false readable=false transportAccepted=true commit=true outcomeDurable=true profileId=1 fp=sha256:012ff489e26267f8 bytes=257
```

这是 #71 的**问题 B（配置传输）**，与本 PR 修的身份边界是两件事；本文件不把它写成已解决。

## 披露：第一轮运行作废

运行者第一次尝试（16:31Z 本地时间）时 AVD 创建静默失败（`glm73-emulator.log`：
`Unknown AVD name [glm73_api35]`），测试实际跑在 serial `emulator-5582` 上——事后确认那是本机长驻的
`f001_ui_test` 实例（API 35，测试同样 `OK (2 tests)`，见 `glm73-instrument3.log`），但「fresh isolated」
**无法证明**，故整轮在正确创建的新 AVD 上重跑，上文全部数据来自重跑。对 `f001_ui_test` 的副作用：两个 bench
包（该实例上此前不存在）被 `pm clear` + 安装；两次 `emu kill` 均被其 watchdog 自愈。Moto 零接触。

## 复现方法

按 `docs/acceptance/issue71-binder-identity-emulator.md`「精确复跑方法（仅新建独立模拟器）」执行，替换：
工作区为本分支、HEAD 断言为 `9c6afecd…`、AVD 名与端口按本机空闲选择；每条 adb 命令必须带 `-s <该模拟器>`；
先确认目标 serial 此前不存在，否则停止（本轮第一次失败正是这一条没做到）。

## 证据文件

本文件内联了全部承重摘录。原始包保留在运行机 `fakexxx-issue71-main/.glm73-emulator-evidence/`
（`README.md`、`props.txt`、`instrument-fresh.log`、`logcat-fresh.log` 1.0 MB、`emulator-boot.log`，以及
作废首轮的 `glm73-emulator.log` / `glm73-instrument3.log` / `logcat-final.log`）——**未提交**：完整 logcat 含
模拟器 telephony 等无关噪声字段，且该目录为本机未跟踪文件，不保证长期保留、其他机器不会自动获得。

## 来源（cat-cafe 调度线 thread_mtgegbvk7lj7fmsz）

Terra exact-head review `0001788441674562-000363-c55ec60d`；glm52 fresh-run 报告 `0001788443144321-000381-86c79365`；
Terra 复核说明 `0001788443617462-000387-d00092bf`；Sol 控制面约束（同 HEAD reentry `safe_wait`）
`0001788443997114-000392-317e080f`。

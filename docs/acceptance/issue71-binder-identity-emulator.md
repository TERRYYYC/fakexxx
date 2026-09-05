---
feature_ids: [G2-66]
topics: [issue-71, android, binder, authorization, emulator, regression]
doc_kind: evidence
created: 2026-09-03
status: implementation-verified-independent-review-approved
---

# Issue #71：真实跨进程 Binder 身份回归证据

## 结论与边界

在独立 Android 15 / API 35 模拟器上，旧生产代码经真实远端 UID 调用时，读取 QWY
非导出 `AppInfoProvider/settings` 被拒绝；核心测试以“应生成的新配置仍为 `Absent`”失败。
修复后，同一核心测试、相同 payload 判据通过。扩展测试也验证了原 caller 授权及身份恢复，
并杀死“省略 restore”和“clear 后才捕获 caller”两种错误实现。

这证明的是 [Issue #71](https://github.com/TERRYYYC/fakexxx/issues/71) 的 Binder 身份修复，
**不是 Moto、LSPosed 配置传递、独立位置读回或 #66 FULL 通过**。
测试中的 `verification=3` 是 `NONE`；配置仍为 `published=false/readable=false`。

最终代码 `be84974e6d574f0fc8c01dcc0129e2e500fcfd37`：九项 Android 测试全部通过，
仓库十二项 host gate 全部通过。此前全套失败及修复过程保留在末节；不隐去失败运行。
本文件是原始证据汇编，不代替非作者的代码审查，也不关闭 #71 的其余调查项或 #66。

## 实际执行范围

| 项目 | 实际值 / 范围 |
| --- | --- |
| 工作区 | `/Users/terry/Desktop/coding/fakexxx-issue71-binder-identity` |
| 基线 | `abaf4778bd60bde611b6d93d650c5a3eb7ed2b83`（PR #70）；代码修复为 `be84974` |
| 独立 AVD | `codex_issue71_api35`，新建于 `/tmp/fakexxx-issue71.sO7jrw/avds/` |
| 唯一设备 serial | `emulator-5580` |
| 系统镜像 | `system-images;android-35;google_apis;arm64-v8a` |
| 被测 APK | debug `name.caiyao.fakegps.bench`，本轮 UID `10207` |
| 调用方 APK | `name.caiyao.fakegps.bench.test`，本轮 UID `10208`，普通独立进程 |
| codexBench | 仅构建；不属于本轮 Android 运行证据 |
| Moto / 其他手机 | 不执行命令；此前 Moto 记录不转换成本次修复的复验结果 |

UID 是这次安装的观测值，复跑时必须从系统获取，不能写死为身份判据。
远端测试 APK 不是 Auto UI；它使用冻结的生产 AIDL 协议调用真实 QWY 服务，覆盖同一种跨 UID
权限边界。不能据此声称两个产品的完整用户流程已验收。

## 测试路径与判据

来源：
[BinderIdentityInstrumentedTest](../../apps/qianwangyou/app/src/androidTest/java/name/caiyao/fakegps/integration/v1/BinderIdentityInstrumentedTest.kt)、
[RemoteBinderRelayService](../../apps/qianwangyou/app/src/androidTest/java/name/caiyao/fakegps/integration/v1/RemoteBinderRelayService.java)、
[生产 Binder 边界](../../apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/EnvironmentControlService.kt)。

1. instrumentation 在 QWY UID 下经 DAO 种入唯一随机 `addname`；不经会提前发布的 repository。
   调用前断言 payload 为 `Absent`，provider 仍 `exported=false`。
2. 普通测试 APK 的 relay 以自己的真实 UID 调用固定生产 `EnvironmentControlService`。
   relay 自身逐次核验 QWY 调用 UID，不接受 caller 参数指定身份或任意目标包。
3. 未配对时，`discover/preflight/apply/observe/release/completeAndAdvance` 六入口均返回
   `NOT_PAIRED`；错误 signer 批准失败，随后仍拒绝。批准的是实际解析出的测试包 + signer。
4. 已批准远端 caller 的 apply 必须让真实私有 provider 生成新 payload，且
   `fields.addname` 精确等于本次随机标记。仅 receipt 存在、文件存在或循环结束均不够。
   已批准的 preflight/observe 返回其对应载体；advance 使用故意错误的摘要，在授权后得到
   `REQUEST_INVALID`，不提交虚构完成证据或推动排程。六入口同时有原 caller 路由检查。
5. 精确批准另一 principal（QWY 自身）后，它对远端 caller 的 lease 做 observe/release
   仍得到 `STALE_LEASE`；原远端 caller 在 `finally` 中 release，断言 `releaseComplete=true`。
6. 独立的测试 Binder 让生产身份作用域 helper 在**同一个服务端事务线程**内记录
   `before/captured/inside/nested-restored/after`：外部 UID 必须被捕获，内部为 QWY UID，
   退出后恢复外部 UID。覆盖正常返回、typed rejection、初始化异常、执行异常四种退出。
   这项是 helper 的真实 Android 身份测试，不谎称四种异常全部注入了生产服务。

## 原始 RED → GREEN

原始日志目录：`/tmp/fakexxx-issue71.sO7jrw/`。该目录是本地临时证据，不承诺长期存在，
也未将完整系统转储上传到仓库。以下保留足够解释判据的精确摘录。

### 旧生产代码 RED

`red-instrumentation-2.log`：

```text
Remote apply must query QWY's private provider and commit a fresh payload; got Absent
FAILURES!!!
Tests run: 1,  Failures: 1
```

`red-logcat-2.txt`，只取本轮 `09-02 18:10:08` 时间窗；文件还含此前运行，不能混读：

```text
I BinderIdentityTest: QWY uid=10207 remote uid=10208 pid=5158
E ConfigPrefsSync: java.lang.SecurityException: Permission Denial: reading name.caiyao.fakegps.data.AppInfoProvider uri content://name.caiyao.fakegps.bench.data.AppInfoProvider/settings from pid=5158, uid=10208 requires the provider be exported, or grantUriPermission()
```

同段调用栈依次包含 `ConfigPrefsSync.buildFieldMapJson:229`、`QwyEnvironmentController.applyEnvironment:354`、
`EnvironmentControlHandler.apply` 和生产 AIDL stub。真实 QWY UID 为 `10207`，Android 私有
provider 权限检查却看到了外部测试 UID `10208`，与 Moto 诊断的缺陷形状一致。

更早 `red-instrumentation.log` 的 `relay accepts synchronous transactions only` 是测试 relay
错误拒绝 framework 附加事务 flags，不是产品 RED；修正为只拒绝 `FLAG_ONEWAY` 后，才得到上面的有效 RED。

### 最小修复 GREEN 与扩展回归

`green-instrumentation.log`：`OK (1 test)`。
`green-logcat.txt` 的 `09-02 18:11:20` 窗口显示：

```text
field map built: 3 spoof fields, 0 unavailable fields
published=false readable=false transportAccepted=true commit=true outcomeDurable=true profileId=1
private-provider payload matched; verification=3
run finished: 1 tests, 0 failed, 0 ignored
```

这次 GREEN 没有放宽“真实 apply 后新 payload 精确匹配”的核心判据；修复的是生产 Binder
身份边界。成功生成、提交私有 payload，与跨进程运输成功是两个不同结论。

扩展后的 `expanded-instrumentation.log` 为 `OK (2 tests)`；对应 `expanded-logcat.txt`
的 `09-02 18:14:30` 窗口为 `run finished: 2 tests, 0 failed, 0 ignored`。
扩展源码中的六入口拒绝、错误 signer 拒绝、foreign lease 拒绝和四种退出断言包含在这两项测试内，
不是另外虚报为十几项独立 JUnit 测试。

### 两种错误修复均被测试杀死

| 临时突变 | 证据文件 | 真实失败 |
| --- | --- | --- |
| 省略身份 restore | `mutation-restore-instrumentation.log` | `return finally restore expected:<10208> but was:<10207>`；1 test / 1 failure |
| clear 后再读取 caller | `mutation-capture-instrumentation.log` | `return captured principal expected:<10208> but was:<10207>`；2 tests / 2 failures；生产调用测试也无法找到预期远端配对候选 |

突变失败不是最终修复失败，也不能代替还原正确代码后的最终运行。必须重新构建并安装正确产物，
以免把设备上仍运行的突变 APK 当作当前代码。

## 精确复跑方法（仅新建独立模拟器）

以下是本机 SDK 路径的复跑操作，不是宣称这些命令已在读者机器执行。
禁止 `connectedAndroidTest` 自动选择设备，禁止省略任何安装、清空或测试命令的 `-s emulator-5580`。
先确认 `emulator-5580` 不存在且端口 `5580/5581` 未被占用；若已有设备，停止本复跑流程，不能接管它。
需要已安装 Java 与上述 API 35 镜像。

在终端 A 新建独立 AVD，不覆盖现有 AVD，不使用 `--force`：

```sh
ISSUE71_REPRO_DIR=$(mktemp -d /tmp/fakexxx-issue71-repro.XXXXXX)
mkdir "$ISSUE71_REPRO_DIR/avds"
export ANDROID_AVD_HOME="$ISSUE71_REPRO_DIR/avds"
printf 'no\n' | env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  /Users/terry/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  --name codex_issue71_api35 \
  --package 'system-images;android-35;google_apis;arm64-v8a' \
  --path "$ISSUE71_REPRO_DIR/avds/codex_issue71_api35.avd"
/Users/terry/Library/Android/sdk/emulator/emulator \
  -avd codex_issue71_api35 -port 5580 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect
```

保持终端 A 中模拟器前台运行。在终端 B 检查新建目标，等 `sys.boot_completed` 返回 `1`，
并人工核对 AVD 名为 `codex_issue71_api35`、`ro.hardware` 为 `ranchu`、API 为 `35`：

```sh
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 emu avd name
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell getprop sys.boot_completed
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell getprop ro.hardware
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell getprop ro.build.version.sdk
cd /Users/terry/Desktop/coding/fakexxx-issue71-binder-identity/apps/qianwangyou
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ANDROID_HOME=/Users/terry/Library/Android/sdk ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-debug.apk
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

**仅在刚创建并核对过的 `emulator-5580` 清空下列两个测试包数据。** 完整场景复跑从干净
app 数据开始；`pm clear` 会重置权限，须重新授予 QWY。不要授予测试 caller 模拟定位权限。
本轮 restore-only 突变测试只调用身份探针，不依赖配对/配置，未为它单独清空 app 数据。

```sh
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell pm clear name.caiyao.fakegps.bench.test
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell pm clear name.caiyao.fakegps.bench
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell pm grant name.caiyao.fakegps.bench android.permission.ACCESS_COARSE_LOCATION
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell pm grant name.caiyao.fakegps.bench android.permission.ACCESS_FINE_LOCATION
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell appops set name.caiyao.fakegps.bench android:mock_location allow
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell am instrument -w \
  -e class name.caiyao.fakegps.integration.v1.BinderIdentityInstrumentedTest \
  name.caiyao.fakegps.bench.test/androidx.test.runner.AndroidJUnitRunner
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 logcat -d \
  -s BinderIdentityTest:I ConfigPrefsSync:V TestRunner:V
```

检查 JUnit 正文的 `OK (...)` 或 `FAILURES!!!`，不能只看 `am instrument` 的 shell exit code；
logcat 需按本轮时间窗归因。整个 Android 测试集应另从干净 app 数据开始运行，不能用上述指定类
的通过代替全套测试结果。

测试结束，确认测试自身 finally release 的断言结果，读取 `dumpsys location` 核对无本轮残留，
然后只清空新模拟器上的测试 app 数据并停止该模拟器：

```sh
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell dumpsys location
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell appops set name.caiyao.fakegps.bench android:mock_location default
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell pm clear name.caiyao.fakegps.bench.test
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell pm clear name.caiyao.fakegps.bench
/Users/terry/Library/Android/sdk/platform-tools/adb -s emulator-5580 emu kill
```

以上不删除已有 AVD、不卸载其他包、不接触任何真机。新临时 AVD 文件可保留，不能写成“已删除”。

## 最终验证、失败处置与清理

### 已闭合的检查失败

- `final-instrumentation.log` 首次全套为 9 tests / 1 failure。日志显示旧
  `transientMissingActiveRowKeepsLastGoodPayloadAndSelection` 将 `Long.MAX_VALUE` 从
  `spoof_config` 迁入独立 `publish_state`，finally 只清了前者；新场景因此选中不存在的旧编号。
  修复旧测试：隔离并恢复两个偏好存储，不改 ConfigPrefsSync 生产逻辑或放宽新 payload 断言。
- `full-gate.log` 首次汇总 10/12：provenance 拒绝未提交源码；host harness 拒绝旧七处
  `callingUid()` 文本形状消失。提交代码后重跑 provenance，并同步静态路由检查到统一身份作用域，
  增加 capture→clear→try→finally restore 顺序与相关突变；所有突变先检查合法原文及替换非 no-op。
- `suite-isolation-build.log` 的 Kotlin 跨模块 nullable property smart-cast 编译错误已改用局部
  `checkNotNull` 值，后续构建及全套测试通过。一次未指定 JDK 的 contract guard 为 INCONCLUSIVE，
  已由显式 JDK 环境下的完整 gate 重跑覆盖，不计为初次通过。

### 本次终态证据

| 检查 | 真实结果 | 原始日志 |
| --- | --- | --- |
| 正确代码全套 Android instrumentation | `OK (9 tests)`，含两个新跨 UID 测试 | `suite-isolation-instrumentation.log` |
| 静态检查修复后的 host integration | `HOST integration gate: PASS`，19 tests / 0 failures | `host-gate-fixed.log` |
| 已提交代码完整 repository gate | ran 12 / passed 12 / failed 0 / pending 0 | `full-gate-committed.log` |
| 原始 QWY lintDebug | **仍失败：23 errors / 173 warnings** | `regressions-build.log` |
| 仓库 lint debt ratchet | QWY 23 与既有预算相同，Auto 0；无新增 error | `full-gate-committed.log` |
| codexBench 构建、签名、打包边界 | 构建成功；签名匹配仓库 key；私有 provider 仍不导出；无测试 relay | `final-build.log`、`codexbench-manifest.xml` |

完整 gate 的命令（从仓库根目录；不执行 adb）：

<!-- issue71-historical-verification-invocation:start -->
历史实际调用记录（deprecated；不可复跑）：

```text
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ANDROID_HOME=/Users/terry/Library/Android/sdk \
  PATH='/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin':"$PATH" \
  bash scripts/verify-a-plus.sh --stage full
```
<!-- issue71-historical-verification-invocation:end -->

当前 host gate 不再把 Android Studio JBR 视为“Java 17 即可”的安全输入。macOS arm64 的
已登记 profile 是 `darwin-aarch64-eclipse-temurin-17.0.20.1+1`，JDK-tree SHA-256
`f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8`；Linux x86_64 CI 的
已登记 profile 是 `linux-x86_64-eclipse-temurin-17.0.20.1+1`，JDK-tree SHA-256
`427182064043c17bb698c7f9c5949f755f6dd80dddaf760b6fa7413178189a97`。先把
`ISSUE66_DARWIN_TEMURIN_JDK17_HOME` 指向 SHA-256
`196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8` 的官方 macOS aarch64
Adoptium archive 安全解包后的 `Contents/Home`。当前安全复跑方式：

```sh
env JAVA_HOME="$ISSUE66_DARWIN_TEMURIN_JDK17_HOME" \
  ANDROID_HOME=/Users/terry/Library/Android/sdk \
  ./scripts/verify-a-plus.sh --stage full
```

The aggregate stages that reviewed JDK inside its private root, requires both Gradle VM and test
launcher 17, and gives every one of the twelve gates a separate private Gradle home. The nested
host runner shares a different per-run isolated Gradle home only across its Auto/QWY/harness
phases. Current author-side evidence is the complete device-free harness at 15 suites / 141 tests
with zero failures, errors or skips; the three main boundary classes at 54 + 21 + 42 = 117, plus 2
`HostEphemeralCleanupGuardTest` tests, for 119 related guard tests; the three standalone Python
runtime-security suites at 40/40; and services compatibility at 131/131. The earlier collector
result was 1718/1718; it predates the final process/environment and argv-budget repairs and is not
evidence for them. Their complete rerun belongs to the clean exact-commit gate.
These checks used `ADB=/usr/bin/false` and did not rerun the historical emulator scenario
on this page. A clean exact-commit gate and independent review remain required.

The current Android validator binds only the AGP 9.1 TCB under `platforms/android-35`,
`build-tools/36.0.0`, `platform-tools` and their safe ancestors. That is not whole-SDK content
provenance. Ubuntu 24 CI separately freezes the complete preinstalled SDK as root-owned and
non-writable before any repository command.

该 gate 明确保留 `issue66Ac7=NOT_PASSED; deviceFull=BLOCKED; overall=BLOCKED`。
其中 host receipt 的 `emulator=NOT_RUN` 描述 host lane 自身；本页的 API 35 身份回归是另一个
独立 lane，不能用它覆盖产品 FULL 结论。系统服务指纹允许列表仍为空。

The current host receipt contract is schema 4 with exactly 19 keys and three SHA-bound sibling
attestations, not the historical receipt shape used when this emulator evidence was produced. Each
Auto/QWY/harness attestation is schema 2 with exactly 15 ordered lines and binds the run ID, staged
JDK profile/runtime/tree, Gradle VM/test-launcher Java 17, task/stage, nonzero test count, zero
failures and required classes. The aggregate consumer opens and re-reads each proof no-follow,
checks its SHA-256 and rejects missing, placeholder or cross-run content.

本机产物 SHA-256（只证明这些本地产物，不是 Moto 安装证据）：

- debug：`6ffeeaa60cfdf7d6d9a9e682f015eff0a0c9b9a58a9a618aca434c0417ccbe4a`
- debug androidTest：`f1f9ab3aa329b9f2be87edb298f442e2cda9fc9f8f47261f080e92aff80fc840`
- codexBench：`5d9e827a58a38cdd65d8f3068bd635355947bf986ad9b792029407278f3ce6ca`
- codexBench signer：`7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41`

The two future Moto install targets remain non-colliding: QWY
`name.caiyao.fakegps.codexbench` / `千网游 · codex-bench` launches `.ui.ComposeActivity`; Auto
`com.example.cellrebelauto.codexbench` / `CellRebel Auto · codex-bench` launches `.ui.MainActivity`.
Both exact-build fingerprint lists are empty, so production remains `BUILD_UNATTESTED`. Restart or
reboot, global Location/provider mutation, adversarial mutation and app lifecycle operations remain
separately unauthorized.

### Quality Gate 范围对账

- 原始愿景是手机实机运行；本次接球明确限定为 #71 身份子问题。修复未冒充 app 或 #66 完成。
- 行为：统一六入口，真实跨 UID RED/GREEN；数据：不改生产存储格式；安全：保留原 UID、精确配对及
  foreign lease 拒绝；契约：ContractV1 零变更且双应用 contract gate 通过；不可逆风险：无真机操作。
- Dogfood：真实测试 APK → 真实 AIDL 服务 → 真实非导出 Provider → 唯一配置字段 → lease release。
- Architecture cell：既有 QWY integration/v1；Map delta：none；没有新增生产存储/服务/授权入口。
- 无 UI 变更、无匹配设计稿、无根目录媒体工件。Clowder 专用 pnpm ownership/tips/hotfix 脚本不在
  本仓库，使用仓库自己的 Android full gate，不伪报那些不存在的检查已运行。
- 无本子问题未处置的实现验收项；非作者 `/root/binder_fix_review` 已批准 `f10cbdf`，无未解决
  P1/P2。唯一 P3 是清理回执来源说明，已在下节补齐。没有把 transport、readback、Moto 复验划成通过。

### 模拟器清理

`cleanup-location-before.txt` 记录 GPS/network mock override 已移除且二者 last location=null。
passive/fused 仍有此前 mock 样本的 last-known cache，所以**不宣称运行中所有位置缓存已清空**。
以下具体命令返回值为**作者会话工具回执转录**，不是独立文件日志：清理命令回执 `765e1c`
（exit 0），受管模拟器会话 `78278` 的终态回执 `177e4b`（exit 0）。`emulator.log` 另有退出记录，
但该文件不能单独证明两次 pm clear 的返回值。
随后只将本模拟器的 QWY mock app-op 复位为 default，清空两只测试包数据（各返回 `Success`），
读取确认 `MOCK_LOCATION: default`，再 `emu kill` 返回 `OK: killing emulator, bye bye`；
受管模拟器进程退出码为 0。临时 AVD 与本地日志保留，不复用于用户工作环境。

原主工作区已有 `.cat-cafe/capabilities.json` 修改保持不动；本次没有任何 Moto 命令。
独立审查结论见 [PR #72](https://github.com/TERRYYYC/fakexxx/pull/72) 的精确提交评论。
此次审查后变更仅是本节证据来源及审查状态说明，生产代码与通过测试的 `be84974` 完全相同。

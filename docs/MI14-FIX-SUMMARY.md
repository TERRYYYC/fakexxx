# MI14 修复版总结（glm-mi14 工作区）

> 基线：main@1a37084（fix(qwy): advance bumps SCHEDULE_BOUNDARY before the receipt snapshot, #96）
> 改动：**未提交**，4 文件，+168/-13（`git diff` 实测）
> 日期：2026-09-06 ｜ 性质：HyperOS（小米14 / Android 16）冻结故障的代码层修复

---

## 1. 背景

小米14（e53cfd3d，HyperOS 3 / Android 16）上 QWY（provider app，同时是 Vector/Xposed 模块宿主）在日程推进后主线程卡死、引擎 discover 超时暂停。深夜复盘推翻了旧结论（"XSharedPreferences IO hang 本身"），完整死因链全部实锤：

1. QWY 定位 appops 原为 `foreground`（仅使用期间）→ CellRebel 测试期间无可见 activity → **HyperOS 清掉 test provider**（`gps provider is not a test provider`）；
2. `MockProviderService` 的 refresh 失败路径 **fail-fast 自杀**（state 非 Running 即 `finishService()` → `stopForeground`）→ 进程失去 location 型前台服务；
3. OEM freezer **1.4 秒内冻结整个进程**（`FZ uid=… reason=from system`，cgroup.freeze=1，root 直读实锤）→ 之后所有 provider binder 调用（discover/apply/completeAndAdvance）被 `GreezeManager` 拉黑 → 引擎 PAUSED。"杀 QWY 重启能再跑一次"的本质是每次人工亮屏/重启解冻一次。
4. 旧 ANR 栈（`ZipFile.initCEN`）是 Vector XSharedPreferences shim 的案发现场：主线程每 30s tick `new XSharedPreferences` 时构造函数全量解析模块 APK zip，冻结时主线程恰好停在 `getString → awaitLoadedLocked`。

代码修复对应两层：**① 保住 FGS（provider 被清后立即重建自愈）② hook prefs 读取后台线程化 + shim 单例复用**。

## 2. 逐文件修复说明

### 2.1 `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/MainHook.java`（核心，+~70 行）

**改了什么：**

1. **新增 `SNAPSHOT_IO`**：单守护线程执行器（`Executors.newSingleThreadExecutor`，线程名 `FakeGPS-snapshot-io`，daemon=true），成为**运行期全部 prefs 文件 IO 的唯一属主**（心跳 reload、observer reload、observer re-arm）。
2. **新增 `PREFS`（`AtomicReference<XSharedPreferences>`）+ `prefs()` 惰性单例**：CAS 复用一个 XSharedPreferences 实例，替代旧的"每次 reload / 每次 observer 预检都 `new XSharedPreferences(...)`"。
3. **心跳 Handler 只 POST**：`handleMessage` 里原来直接在主线程执行的 `tryArmObserver(...)` 与 `reloadSnapshot(...)` 全部改为 `SNAPSHOT_IO.execute(...)`；主线程从此零 prefs IO，只负责 `sendEmptyMessageDelayed` 续 tick。
4. **`tryArmObserver` 复用单例**：`prefs().getFile()` 取路径（旧代码此处 new 一个实例，等于每次 re-arm 重解析一遍 APK zip）；observer 回调改为 `() -> SNAPSHOT_IO.execute(() -> reloadSnapshot(processName))`。
5. **初始 load 保持同步**：`handleLoadPackage` 里的 `reloadSnapshot(null)` 仍刻意在 load-package 线程同步执行（新增注释说明动机：hooks 不得在首帧快照落地前服务 passthrough 真机数据；进程启动时存储栈空闲）。

**为什么/怎么工作：**

- Vector shim 的 `XSharedPreferences(String,String)` 构造函数每次都走 `VectorMetaDataReader.getMetaData(new File(apk))` → `JarFile(apk).getEntry("AndroidManifest.xml")` 全量解析模块 APK 的 zip central directory，**无缓存**（上游源码核实：`legacy/src/main/java/de/robv/android/xposed/XSharedPreferences.java` + `xposed/src/main/kotlin/org/matrix/vector/impl/utils/VectorMetaDataReader.kt`）。模块 APK 就是宿主进程里的 base.apk，旧代码每 tick 重复付一次解析成本。
- shim 的 `reload()` 对未变化文件有 mtime/size stat 门，很便宜——所以单例复用不牺牲新鲜度，只付一次构造成本。
- shim 的 `getString()` 等所有 getter 都经过 `awaitLoadedLocked()`（`while (!mLoaded) wait();`，吞中断、无超时）：异步 loader 卡住（冻结进程 FUSE、存储 stall）时**调用线程无限期挂起**。旧代码心跳 tick 跑在主 looper（ANR 栈：`MainHook$1.handleMessage → loadSnapshot → getString → Object.wait`），一次卡读就冻死宿主 app。新路径下主线程只 POST，worker 卡死时降级为"快照冻结"（last-known-good 继续伪装）而不是主线程死亡。

### 2.2 `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/PrefsDirectoryObserver.java`

仅 javadoc 更新，无行为代码变化："Thread safety" 一节改为如实描述新事实——`onEvent`（FileObserver 线程）只 POST 到 `SNAPSHOT_IO`，observer 线程自身不做文件 IO，卡读不再阻塞进程内 inotify 投递；序列化仍由 `SNAPSHOT_LOCK` 保证。类内 `armed` 字段本来就是 `volatile`。

### 2.3 `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/mockprovider/LocationDeliveryOrchestrator.kt`（+21 行）

**改了什么：** `refresh()` 末尾新增一行调用 `maybeRebuildDroppedProvider(ready.config)`；新私有方法：

```kotlin
private fun maybeRebuildDroppedProvider(config: MockLocationConfig) {
    val failure = controller.state as? MockProviderState.Failed ?: return
    if (failure.reason == MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED) return
    controller.start(config, providerMayAlreadyExist = true)
}
```

**为什么/怎么工作：**

- tick 例行刷新（`controller.tick()` 的 `gateway.publish`）若抛 `IllegalArgumentException("gps provider is not a test provider")`，controller 进 Failed 态。旧逻辑到此为止 → service `finishService()` → FGS 没了 → 冻结链启动。新逻辑**每个 tick 内立即重建一次**（remove → replace → publish）：重建成功 → 回到 Running，FGS 保住，会话不断；重建也失败 → 维持 Failed → service 照旧自杀（与旧行为一致的终态）。
- 唯一跳过重建的失败是 `MOCK_LOCATION_APP_OP_DENIED`（SecurityException 且消息含 MOCK_LOCATION，issue #8 的 typed reason）：没有 mock-location app-op 时 re-add 必然失败，重建毫无意义。当前枚举只有这一个 reason 值。

### 2.4 `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/mockprovider/LocationDeliveryOrchestratorTest.kt`（+3 用例）

1. `refresh rebuilds provider immediately when framework dropped the test provider`：第 2 次 publish 抛 "not a test provider" → refresh 返回 Running，事件序列 `publish / remove / remove, replace, publish`（tick 失败的清理 remove + 重建的 remove/replace/publish）。
2. `refresh reports failure after an immediate rebuild also fails`：publish 持续失败 → refresh 返回 Failed，重建尝试恰好多一轮 remove，终态与旧版一致。
3. `refresh tick permission denial does not trigger a rebuild attempt`：SecurityException(MOCK_LOCATION) → 不重建，Failed.reason=MOCK_LOCATION_APP_OP_DENIED，事件仅 `publish / remove`。
4. Fixture 相应扩展了 `publishFailureCalls/publishFailureThrowable`（按调用序号注入 publish 失败）。

## 3. 关键机制核对（合入评审关注点）

### 3.1 线程安全

- **`CURRENT` 本就是 `static final AtomicReference<Snapshot>`**（MainHook.java:63），hook 回调经 `HookUtils` 在调用点 `CURRENT.get()`，从不读 prefs——发布/消费是原子的，本次未改该字段。Snapshot 实例构造后即不可变（fromJson 建好、字段为可空包装类型）。
- **共享 XSharedPreferences 实例的全部可变操作串行**：`makeWorldReadable()/reload()/getString()` 都在 `loadSnapshot()` 内，而 `loadSnapshot` 只被 `reloadSnapshot`/`reloadSnapshotLocked`/`reloadSnapshotForProbe` 调用，全部持 `SNAPSHOT_LOCK`。唯一锁外使用是 `prefs().getFile()`（只读，不动 shim 状态）。因此**不存在对 shim 实例的并发可变访问**。
- `prefs()` 的 CAS 竞态双构造：理论上两线程同时首调会各构造一个实例、输者被丢弃——实际首调发生在 load-package 线程的同步初始 load（先于任何 SNAPSHOT_IO 任务提交），稳态不会竞速；即便发生也只是多付一次解析成本，无害。
- **一个低风险残留（建议后续顺手修）**：`prefsObserver` 字段非 volatile，初始 arm 在 load-package 线程、重试 arm 在 SNAPSHOT_IO、心跳检查在主线程——理论上主线程可能读到过期值而多 POST 一次冗余 re-arm。后果仅是每 tick 多换一个 FileObserver + 一条 evidence 日志（timer 兜底本就存在），不影响正确性；实际硬件缓存一致性下几乎不会发生。建议后续把 `prefsObserver` 标 `volatile`。

### 3.2 首帧时序

- hooks 注册（步骤 2）在同步初始 load（步骤 1）**之后**，宿主进程的第一个被 hook 调用看到的已是真实配置快照，**不存在 passthrough 窗口**；`CURRENT` 的初始值 PASSTHROUGH 只在"从未发布过配置"时才有机会被看到（`Snapshot.keepLastKnownGoodOr` 的 FC-2 不变量：已激活伪装的失败刷新永不回退真机数据）。
- SNAPSHOT_IO 卡死（理论极端）时降级为 last-known-good 快照继续伪装，配置更新暂停——对测试型宿主是正确方向的失效。

### 3.3 provider 重建的循环风险

- `MockProviderService.runSession`：**只有 state=Running 才续 1s tick**，Failed 即 `finishService()`。因此重建逻辑不会形成"服务内死循环"：每 tick 至多一次重建，失败即终止（终态与旧版相同）。
- 理论残留场景：某 OEM 持续清 provider 且每次重建都成功 → 1Hz 的"清/重建"拉锯（FGS 全程存活、伪装不断，代价是耗电与日志）。HyperOS 侧该场景已由配套 appops 修复消除（`FINE/COARSE_LOCATION allow` 替代 `foreground`）。moto/Android 15 未观察到清 provider 行为，正常路径 state 恒 Running，`maybeRebuildDroppedProvider` 直接 return，零行为变化。
- 测试遗漏（建议，不阻塞）：① 重建成功后的第二个 tick 稳态回归（防拉锯回归）；② 失败仅出现在 cleanup leg 时的 reason 判定；③ 重建失败路径与 `providerCleanupRequired` 持久化标记的交互。

## 4. 验证证据摘要

- **JVM 单测（本工作区实测）**：`JAVA_HOME=Android Studio jbr` + `./gradlew :app:testDebugUnitTest --rerun` → **BUILD SUCCESSFUL，822 tests / 0 failures / 0 errors / 0 skipped**（819 存量 + 3 个新增 orchestrator 用例；glmbench buildType 无独立单测任务，`initWith debug` 与 debug 共享 src/test，debug 单测即全量覆盖）。
- **真机验收（e53cfd3d，修复版 APK 27f3a3e4…，2026-09-06 22:38–22:47，台账实锤）**：连续 6 个 SUCCEEDED（attempt 4–9，每 90s 一轮无干预），跨 ≥6 次 completeAndAdvance（schedule v1→2、指针 profile-1→2）；`grep PAUSED|discover failed` = 0；期间 cgroup.freeze 恒 0；22:38:45 唯一一次冻结尝试 4ms 内被同步 binder 自动 THAW 自愈；tick 线程 tid=20086 ≠ pid 6391（主线程零 prefs IO 实证）。
- MainHook 线程行为本身无 JVM 测试（Xposed 类 compileOnly，`MainHook` 不可直接单测——Snapshot.java 注释自证），该层证据依赖上述真机验收。

## 5. 影响面

- **车道**：hook 代码为全车道共享源码（QWY build types = release / debug(.bench) / glmbench(.glmbench)，全用 repo 内提交的 `keystores/bench.keystore`，cert sha256 7a598cbe…）。合入后任何新构建都携带两处修复；不改签名、不改 applicationId、不改 schema（`TransportSchemaContract` 未动）。
- **设备**：
  - 小米14（HyperOS/Android 16）：直接受益者（已在跑 27f3a3e4 构建实证）。
  - moto g54 ×2（Android 15）：正常路径零行为变化（见 3.3）；hook 层线程化对 A15 同样生效且只会更稳（主线程不再可能被卡读挂起）。appops 清 provider 行为在 moto 上未观察到。
  - 正式版车道（name.caiyao.fakegps，ZY22JHW9M4 上为 176a493 内容、连 #96 都未含）：合入后按 PRODUCTION-REMEDIATION-REPORT.md §3 runbook 重建正式版对时，基线应从 1a37084 顺延为**合入后 main**，一次性携带 #96 + 本修复，避免二次重装（runbook 步骤 1 的 checkout 目标相应更新）。
- **Vector 版本耦合**：无硬依赖。修复规避的是 shim 的构造成本与无超时等待——该行为在 Vector 3110 实测存在（源码核实），对原始 LSPosed/XposedBridge 的 XSharedPreferences（同步读文件语义）同样成立且无害。moto 与小米同为 Vector v2.2-3110（3110-c4a701aa）。
- **签名/配对**：改动不触碰签名配置与 pairing 存储；`install -r`（同 key）重装后既有 pairing（Auto 信任 QWY 签名 7a598cbe…、QWY 批准 Auto caller）原样保留。会丢的是 OEM 敏感开关：无障碍启用、runtime permissions（HyperOS 上 `install -r` 会清）、appops——重装后须按检查单补齐（见 §6）。

## 6. 合入后各设备动作清单（供执行线程使用）

| 设备 | 是否需重装 | 重装后必补 |
|---|---|---|
| e53cfd3d（小米14） | 可选（现跑 27f3a3e4 与本修复同内容，合入不产生新行为） | 若重装：`install -r` → 补授 FINE/COARSE/NOTIFICATIONS（HyperOS 会清，`su -c pm grant` 概率性被剥权需重试 2-4 次）→ `appops set … mock_location allow` + `FINE_LOCATION/COARSE_LOCATION allow` → `dumpsys deviceidle whitelist +pkg` → 无障碍重绑（HyperOS 单服务位）→ 发布探针 `published=true` |
| ZY22J66NX2（moto，glmbench） | 建议重装以对齐 main | `install -r`（保数据/配对）→ 无障碍重开（install 清开关）→ mock_location appops 复查 → 重启用 glmbench 模块确认（Vector apk_path 自动跟踪，仍按 runbook 复查镜像目录 publish 状态） |
| ZY22JHW9M4（moto，glmbench 压测机） | 建议同上（当前无运行中计划时） | 同上；glmbench 模块启用期间保持正式版模块停用（双 hook 红线） |
| 正式版车道（name.caiyao.fakegps 等） | 按 runbook §3 整体重建（步骤 1 基线更新为合入后 main） | 全套 runbook 步骤 2–8：装后补权限/appops → Vector 复查（先停 glmbench 模块再启用正式版）→ 51 档案重导+重锚 → Provider 重新批准 → 搁浅 attempt 处置 → 边界观察 |

## 7. 结论

可安全合入 main。两处修复语义封闭（hook 层只改 IO 线程与实例复用、provider 层只在既有 Failed 路径上加一次受控重建），JVM 全量单测 822/822 全绿，目标设备（HyperOS）真机 6 连验收通过，moto/Android 15 与正式版车道在正常路径上零行为变化。建议合入策略见交付报告。

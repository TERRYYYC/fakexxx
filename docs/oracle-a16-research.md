---
feature_ids: [149]
topics:
  - qianwangyou
  - oracle
  - android16
  - system_server
  - research
doc_kind: research
created: 2026-09-10
status: concluded-feasible
---

# Oracle A16（SDK 36）system_server hook 适配调研（#149）

> 结论先行：**可行**。A16（android-16.0.0_r1）中 oracle 依赖的全部 7 个 system_server hook 点
> 与 A15（android-15.0.0_r1）逐文件对比后**全部存活**（4 个字节级相同、3 个仅有加法性差异，
> 且被 hook 的方法名与参数形态不变）。Vector 对 A16 system_server 注入为官方支持路径，但有
> 已知 daemon 稳定性 issue（见 §3）。落地方式：新增 `Android16OracleHookPlan`（API_LEVEL=36，
> 沿用 A15 计划结构），mi14 增量构建号 `BP2A.250605.031.A3` attested 进白名单，Installer 改为
> 双版本解析门控。**红线不变：oracle 只读不注入；任何 hook 点缺失/异常都 poison → fail-closed。**

## 1. 调研方法

- AOSP 源码逐文件对比：`refs/tags/android-15.0.0_r1` vs `refs/tags/android-16.0.0_r1`
  （mi14 的 BP2A.250605.031.A3 属 Android 16 / API 36 代际）。
- frameworks/base 路径：
  - `services/core/java/com/android/server/appop/AppOpsCheckingServiceTracingDecorator.java`
  - `services/core/java/com/android/server/permission/access/appop/AppOpService.kt`（注意：在
    frameworks/base 的 `services/permission/` 源集，随 system_server 编译，不在 Permission apex）
  - `services/core/java/com/android/server/permission/access/AccessCheckingService.kt`
  - `services/core/java/com/android/server/location/LocationManagerService.java`
  - `services/core/java/com/android/server/location/provider/LocationProviderManager.java`
  - `services/core/java/com/android/server/location/provider/MockLocationProvider.java`
  - `services/core/java/com/android/server/SystemServiceManager.java`

## 2. 逐 hook 点存活状况（A15 → A16）

| Hook 点（Android15OracleHookPlan 常量） | A16 状况 | 证据 |
|---|---|---|
| `AppOpsCheckingServiceTracingDecorator` {setUidMode, setPackageMode, removePackage, removeUid, clearAllModes} | **ALIVE，文件字节级相同** | diff = 0 行 |
| `AppOpService` {setUidMode, setPackageMode, removePackage, removeUid} | **ALIVE**，签名不变（含 `deviceId` 参数形态一致）；唯一差异是 +4 行 `foregroundableOps` 初始化（加法性，不影响被 hook 方法） | diff = 4 行新增 |
| `AccessCheckingService` {onPackageRemoved, onPackageUninstalled, onUserRemoved} | **ALIVE，文件字节级相同** | diff = 0 行 |
| `LocationManagerService` {addTestProvider, removeTestProvider, setTestProviderEnabled}（QWY mutation 入口） | **ALIVE**，四个方法（含 provenance 入口 `setTestProviderLocation`）签名逐一相同；文件差异仅 density-based coarse location flag / overlay fallback 等**加法性**改动 | diff = 58 行，全部加法性 |
| `MockLocationProvider.setProviderLocation` + 字段 `mLocation`（semantic 坐标比较的读取点） | **ALIVE，文件字节级相同**（`mLocation` 字段、`new Location(l)`+`setIsFromMockProvider(true)` 语义不变） | diff = 0 行 |
| `LocationProviderManager` {onStateChanged, onEnabledChanged} | **ALIVE**，两方法均在、参数形态一致（`onEnabledChanged` 仍为 private——`hookAllMethods` 按名匹配不受影响）；文件差异仅 fudger cache API、pending-intent 选项、debug 日志（加法性） | diff = 19 行，加法性 |
| `SystemServiceManager.startBootPhase`（phase 600 bridge bind 触发器） | **ALIVE，文件字节级相同** | diff = 0 行 |

细节核对：

1. `setTestProviderLocation(String provider, Location location, String packageName, String
   attributionTag)` 两版本签名一致 → Installer 的 `captureCallerProvenance`
   （`stringArgumentFromEnd(args, 2/1)`）在 A16 上取值语义不变。
2. `hookAllMethods` 按方法名匹配，不受 A16 任何加法性重载影响；若未来出现同名重载，语义仍是
   "全部同名方法都被 journal"，方向是更保守（更多变异被计入序列）而非漏报。
3. **HyperOS 未知差异（mi14 实际运行的是小米 HyperOS 3）**：本调研只能证明 AOSP 代际差异为零。
   小米对上述类的私有改动无法离线证明。**安全性质**：Installer 的每个 hook 组都在 try/catch 内
   安装，类/方法缺失（`findClass`/空 hook 集）或回调异常 → `poisonCallback` → health=
   CALLBACK_POISONED → QWY 侧 coverage=NONE（fail-closed），**不会崩溃 system_server、不会
   开机循环**。最坏代价是"A16 车道维持 UNVERIFIED"，与今日状态相同。

## 3. Vector / LSPosed 在 A16 system_server 的注入可行性

- system_server scope 是 Vector 的**官方支持路径**（上游有专门的 system_server 模块支持工作，
  如已合并的 native library staging 修复 JingMatrix/Vector#830；本仓库 QWY 的
  `xposed_scope.xml` 已含 `<item>system</item>`，MainHook 对 `android`/`android` 进程已有
  专用分支）。
- A16 相关已知上游 issue（截至 2026-09）：
  - **JingMatrix/Vector#904（closed/fixed）**：HyperOS 3 / Android 16 上 manager 激活失败
    （parasitic manager 在 `selinux_android_setcontext` SIGABRT）——已修复；mi14 上 Vector
    v2.2-3110 canary 的 app 进程注入已被本仓库 MI14 修复工作实测（见 docs/MI14-FIX-SUMMARY.md
    与 docs/vector-upstream-issue-draft.md，ANR 栈即取自 mi14 A16）。
  - **JingMatrix/Vector#939（open）**：部分软重启场景 VectorDaemon 未捕获 DeadSystemException
    （Samsung A16 实测）。影响是"该次开机无注入"→ oracle bridge 不可用 → fail-closed，非
    system_server 崩溃、非开机循环。
  - **JingMatrix/Vector#923（open）**：OnePlus 8T / ColorOS 16 上多模块（system_server scope ×3）
    场景出现 lmkd/commcenterd native crash 与 system_server ANR。提示：system_server scope 内
    **少装模块、只装只读 oracle** 是正确姿势——本仓库红线（oracle 只读不注入）与此一致。
  - LSPosed 经典 #2759（system_server scope 导致宿主 `ApplicationInfo.sourceDir` 为 null）为
    app 侧问题、已关闭，与本 oracle 无关（system_server 分支不读宿主 ApplicationInfo）。
- **判定**：注入机制本身在 A16 可行；残余风险是特定 OEM 构建上的 daemon 稳定性，故障模式全部
  降级为 fail-closed（UNVERIFIED），不产生开机循环。因此**不做**"永久 UNVERIFIED-capped"
  的降级设计，保留 oracle 直连方案。

## 4. 实现设计（本次提交落地）

1. **`Android16OracleHookPlan`**（新增，沿用 A15 计划结构）：`API_LEVEL=36`；类/方法常量按 §2
   调研结论**与 A15 逐字相同**（这正是调研的核心结论——差异在门控与 attestation，不在 hook
   面）；测试强制两个 plan 的 hook 面逐项相等（surface lockstep）。
2. **指纹/构建白名单（attested 流程）**：
   - `ATTESTED_BUILD_IDS = {"BP2A.250605.031.A3"}`（mi14 / HyperOS 3 的
     `Build.VERSION.INCREMENTAL`，精确匹配精确 OTA 构建）；`ATTESTED_FINGERPRINTS = {}`
     （保留未来对整机 fingerprint 精确 pin 的通道，当前为空）。
   - **attested 流程 = 代码内白名单 + 逐设备 review**：每加一个构建号都是一次可审计的
     "exact-build evidence change"（与 A15 计划既定治理一致）。**运行时可配置（system
     property / 远程下发）被评估并否决**：可编辑的 attestation 会把"构建白名单"软化成"运行时
     开关"，削弱 fail-closed 语义；构建时注入（Gradle BuildConfig）留作未来设备数量变多后的
     演进项，不与本次混做。
   - `isBuildAttested(sdkInt, incremental, fingerprint)`：纯函数（无 android.os 依赖，host JVM
     可测）；`SDK_INT==36 && (BUILD_IDS.contains(incremental) || FINGERPRINTS.contains(fp))`。
3. **Installer 门控更新**：`resolvePlan(sdkInt, fingerprint, incremental)` 纯函数解析
   35/36（未知 SDK → null → inert，维持 285 实测的 fail-closed 现状）；**platform 门 →
   attestation 门 → 才创建 producer**（顺序保持原 wiring 测试意图）；Binder 侧用所选 plan 的
   `attests` 复核同一白名单（call-site 布尔无法自我证明）。
4. **coverage bit 单一事实源**：coverage bit 的**取值**是 wire 契约（protocolVersion=1 的
   `installedCoverageMask`），与平台版本无关——继续以 A15 pilot 常量为唯一声明处
   （`SystemServerOracleState` 已直接引用），**禁止按版本 fork**；A16 plan 只声明 hook 面，
   不重复声明 bit。测试断言两侧 mask 相等。
5. **不改的**：`SystemServerOracleState`/`OracleBundleCodec`/`OracleClientRegistry`/
   `OracleBridgeService`/`EnvironmentObserver` 权威窗口判定全部零改动（版本无关层）；
   `Android15OracleHookPlan` 既有常量与空 attested 语义零改动（A15 车道维持
   fail-closed 现状）。

## 5. 残余风险与缓解

| 风险 | 概率 | 缓解 |
|---|---|---|
| HyperOS 对目标类有私有改动 → hook 缺失 | 中 | fail-closed poison（安全）；代价仅"继续 UNVERIFIED"；上机阶段用 logcat `FakeGPS-ContinuityOracle` 逐点核对 |
| Hook 安装成功但 HyperOS 语义变化（如同名方法行为不同） | 低 | oracle 回调只 journal（读 provenance + 读 `mLocation`），不写框架状态；异常即 poison |
| Vector daemon 在 mi14 A16 上偶发不稳（#939 类） | 低-中 | bridge 掉线 → health=BRIDGE_UNAVAILABLE → NONE（fail-closed），不崩溃 system_server |
| system_server 内多模块互相干扰（#923 类） | 设备相关 | 交付纪律：QWY 的 system_server scope 内只有本 oracle 分支（只读）；验证阶段记录 scope 清单 |

## 6. B 层验证阶梯（本变更不含任何设备操作；按风险从低到高逐级）

1. **API 36 模拟器（首验）**：临时、单独 review 的 debug 构建把模拟器 incremental attested，
   冷启动 ≥3 次验证：system_server 正常开机、`FakeGPS-ContinuityOracle` 全部 hook 点安装成功、
   phase 600 bridge bind、QWY `authoritativeSource.snapshot()` 非 null 且 mask=FULL。
   证明：A16 分支解析 + AOSP hook 面在真实 A16 上存在。
2. **备机（A15 Moto，ZY22JHW9M4）**：证明多版本重构未回归 A15 车道——默认白名单下仍 inert
   （fail-closed）；（可选、单独 review）临时 attested 走通 A15 端到端 oracle。
3. **mi14（生产 attestation）**：`BP2A.250605.031.A3` 正式白名单生效 → #149 验收：
   snapshot 非 null、PRE/POST 窗口 VALID、coverage=FULL、285 计划
   SYSTEM_MOCK_INDEPENDENTLY_VERIFIED + FULL 可信入账。
   回滚方案：从白名单移除该构建号（或回滚提交）→ installer 在任何 hook 注册前返回 →
   系统行为与 main 完全一致。

## 7. 明确不做（本次）

- 不实现运行时可编辑白名单（见 §4.2 的否决理由）。
- 不为 A16 增加"降级运行模式/永久 UNVERIFIED-capped"设计（§3 判定不需要）。
- 不触碰 Vector scope 配置以外任何注入面；oracle 保持只读不注入红线。

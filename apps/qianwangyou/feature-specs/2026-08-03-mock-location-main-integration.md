---
feature_ids: [F001]
topics: [android, location, mock-provider, xposed, profiles, lifecycle]
doc_kind: plan
created: 2026-08-03
---

# Mock Location 主 App 集成实施计划

**Feature:** F001 — Google Maps 蓝点跨 GMS 进程缺口
**Goal:** 把已验证的 System Mock Provider 从独立 Lab 合入千网游主 App，并用一个用户开关在 Hook 与 System Mock 之间选择位置注入方式。
**Acceptance:** 使用主 App 生效中档案；用户只选择一种位置交付意图，运行中目标按既有刷新周期收敛到该意图；debug/release 主 App 都能在 Android“选择模拟位置信息应用”中被真实用户发现；System Mock 同时控制 framework gps/network 与 Google Play Services fused mock mode，真实 Maps 消费端的连续采样不得泄漏真实位置；切回 Hook 后 framework test provider 与 GMS mock cache 都确实消失；默认/验收地点为基辅；真机 Maps 蓝点与 Stop 均有可复核证据。
**Architecture cell:** Android application / location delivery
**Map delta:** none
**Map delta why:** 仓库没有 ownership-cell registry；改动将 framework gateway、官方 GMS location client 与 service 收入现有 `:app` 位置交付边界，没有新增跨进程存储、第二个状态源或自建外部服务。
**Delivery status (2026-08-04):** PR #10 已以 squash commit `008923ecca96ab6e2234901e2a7dfbc595ff5737` 合入 `master`，Issue #12 已自动关闭；clean merged-main Debug APK 与 reviewed artifact 同为 `7e18cdcc…f80c`，合入后 433/433 JVM、12/12 结构契约、Debug/Release/`lintVitalRelease` 及 moto g54 全链验收均通过。

## 完成定义

1. `name.caiyao.fakegps` 主 App 的设置页提供“系统 Mock 位置”开关。
2. 开关关闭时位置由现有 Xposed Hook 注入；开启时 Hook 只旁路位置字段，蜂窝、Wi-Fi 等其他档案字段继续 Hook。
3. System Mock 不保存第二份位置，也不新增 System-Mock 专属“当前档案”指针；每次都从 `ConfigPrefsSync` 已发布的生效中档案读取经纬度、可选海拔与精度。现有 transport 会把显式保存的 profile id 与 payload 原子提交，System Mock 只消费 payload，不另行选档案。
4. 档案经纬度在 System Mock 运行中发生变化时，服务在下一次采样周期自动采用新值。
5. 开启前校验有效经纬度；没有 mock-location app-op、缺定位权限/通知权限或 provider 调用失败时，设置页显示实际失败，不能显示为已运行。Android 13+ 由产品请求通知权限，不能由验收脚本代授。
6. 切回 Hook、通知栏 Stop，以及“清理标记尚未清除”的 Hook 启动恢复三条路径都执行 `removeTestProvider("gps")`。普通 Hook 启动不碰系统 provider。设备验收直接检查 `dumpsys location` 中 `gps` provider 已恢复 `GnssService`，不再用 PID/app-op 代理结果。
7. 地图默认中心与坐标搜索示例为 Kyiv `50.4501, 30.5234`；隔离 debug bench 真机验收使用该档案，不读取或改动 release 用户数据。
8. System Mock 运行中若用户改选了模拟位置 App，设置页必须识别权限恢复动作，指导“重新选择当前千网游 → 重试停止”；只有真实恢复 GNSS 后才清理恢复标记。
9. 首次开启时若尚未选择当前千网游，系统未发生 provider mutation：设置页指导“选择当前千网游 → 重新打开开关”，不得声称存在残留或显示“重试停止”；预写 cleanup marker 必须清除，进程重启后回到干净 Hook。
10. release 与 `.bench` 的安装 manifest 都声明 `ACCESS_MOCK_LOCATION`，因此 Android 开发者选项的真实模拟位置 App 选择器能列出当前千网游；验收必须在首次 shell app-op 旁路前打开系统选择器并证明目标可见。
11. Google Maps 使用的 GMS fused 通道在整个 System Mock 会话中只输出同一生效档案的 mock 坐标；验收必须在真实 Maps 前台连续采样，而不是用一个 `dumpsys` 瞬间代替时间轴。Stop 返回成功前必须退出 GMS mock mode，并证明 Kyiv fused cache 已消失。

## 根因与边界

PR #8 的控制器能正确调用 `removeTestProvider`；复现中显式点 Stop 后 `gps` 确实恢复为 `1000/android[GnssService]`。失败发生在另一个边界：系统 test provider 是 system_server 状态，App 被 force-stop/SIGKILL 时不保证执行 `Service.onDestroy()`，因此 provider 可以在进程消失后继续存在。

旧验收脚本只证明了“Stop 节点被点击、Lab PID 消失、mock app-op 恢复”，没有证明 provider 被移除。这使生命周期缺口被错误判绿。本次不能承诺 Android 在 force-stop 后替已死亡进程执行清理；终态约束是：

- 用户显式关闭时，App 在返回成功前完成真实 cleanup；
- 若进程在切换窗口中死亡，持久化的用户意图驱动下次主 App 启动恢复；
- 验收脚本始终以 provider 身份为真相，不再接受代理信号。

## 状态对象与唯一所有者

### 1. 位置注入方式

- 值：`hook` / `system_mock`。
- 唯一持久化所有者：`SpoofSettings`。
- 含义：只选择位置数据的交付方式，不控制蜂窝/Wi-Fi Hook。
- 默认：`hook`，保持升级兼容。

### 2. 生效档案坐标

- 唯一数据源：`ConfigPrefsSync` 发布的 `fields`。保存档案时 repository 把该 profile id 显式传给 publisher；无显式 id 时 publisher 复用与上一份 payload 同 commit 的 active profile id，fresh install 才回落到首条。
- active profile id 是既有 Hook transport 的路由元数据，不是 System Mock 的第二份状态；若显式目标暂时查不到，publisher 保留 last-good payload，删除场景才允许发布空档案。
- System Mock 只解析 `latitude`、`longitude`、可选 `altitude` 与 `accuracy`；不引入新表、新 preference 或 service extra 位置。
- 档案更新由既有 repository republish 收口；运行服务每秒读取已发布快照，所以不增加并行通知链。

### 3. Hook 快照

- `locationDeliveryMode=system_mock` 时，仅清除 Snapshot 的位置字段。
- Snapshot 中的蜂窝、Wi-Fi、设备等字段保持不变。
- transport schema 从 v3 升至 v4；新 Hook 兼容读取 v2/v3（缺字段默认 `hook`），旧 Hook 必须拒绝 v4，避免不认识开关却继续位置 Hook 造成双重注入。

### 4. System location provider session

- 唯一运行时所有者：主 App 内非导出的 `MockProviderService` + `MockProviderSessionController`。
- `start`: 先用当前已发布档案建立 framework gps/network test providers，再等待 GMS `setMockMode(true)` 与首次 fused mock 发布全部完成；成功后才持久化 `system_mock` 并发布 Hook 旁路。任一层失败都由同一 controller transaction best-effort 清理并恢复 `hook`，不能在半成功状态报告 Running。
- `stop`: 先持久化/发布 `hook` 用户意图，再无条件调用一个协调 cleanup；它同时尝试移除 framework providers 与等待 GMS `setMockMode(false)`。服务内存即使为 Idle，也不能跳过 cleanup，以修复前次进程遗留。
- `tick`: 重新解析当前已发布档案；坐标变化则 replace + immediate publish，否则向 framework 与 GMS fused 发布同一份新鲜时间戳样本。
- GMS `Task` 必须在 service worker 上有界等待；主线程不阻塞，Task 失败/超时按 provider 事务失败处理。GMS 不可用时不得静默退回已知会让 Maps 泄漏真实位置的 framework-only 伪成功。
- `onDestroy`: best-effort cleanup；不把它当可靠 Stop 证明。
- durable cleanup marker 表示“切换事务未完成”，不是“provider 正在运行”；稳定的 Hook/System Mock 状态都清除 marker。任何残留 marker 在 App 启动时都优先执行 Stop + 回 Hook，不能按残留 `system_mock` 重启。
- 移除 launcher task 不停止 FGS；用户选择 System Mock 后，服务持续运行直至开关、通知栏 Stop 或明确失败。

## 不变量

- **INV-1 单位置意图与有界交接：** System Mock 成功启用后，持久/发布快照的所有 Hook 位置字段为空，其他字段不变；已运行目标进程最迟在它当前的 5–60 秒刷新周期内采用新意图。交接窗口可能短暂重叠，但两路都来自同一生效档案坐标；不宣称跨进程瞬时原子切换。
- **INV-2 单档案真相：** Mock 服务输出坐标等于已发布有效档案坐标；保存哪条档案就发布哪条，payload 与 active profile id 原子提交；服务不接受 UI/Intent 中另一套坐标或指针。
- **INV-3 启用失败回滚：** provider 注册、首次发布或 Hook 配置发布任一失败，最终 intent 为 Hook，provider best-effort 清理，UI 为 Failed。
- **INV-4 Stop 不信内存：** stop/reconcile 即使 controller 刚创建且 state=Idle，仍调用 `removeTestProvider`。
- **INV-5 真 Stop：** Stop 验收必须看到 `gps` provider 非 `[mock]` 且 identity 为系统 GNSS；PID 与 app-op 只作辅助证据。恢复成功后清除 durable cleanup marker。
- **INV-6 档案热更新：** System Mock 运行时修改生效档案，下一 tick 使用新坐标。
- **INV-7 升级兼容：** 缺 `locationDeliveryMode` 的 v2/v3 payload 解释为 Hook；v4 的模式字段参与运行决策。
- **INV-8 数据安全：** 真机开发只改 debug bench 数据；release App、参考 Fake GPS Location 的数据不读取、不迁移、不删除。
- **INV-9 任务移除不改用户意图：** 从最近任务移除主 App 不停止 System Mock FGS；设备验收须证明 provider 与 Kyiv 输出仍存活，随后显式 Stop 恢复真实 GNSS。
- **INV-10 权限丢失可恢复：** 若运行中改选模拟位置 App，系统可能保留原 test provider 却拒绝原 owner 移除。App 不伪造成功、不自行改 app-op；UI 指导用户重新选择当前千网游并重试，恢复标记在真实 cleanup 前保持。
- **INV-11 前台状态可见：** Android 13+ 开启 System Mock 前请求通知权限；用户拒绝时不开启 provider，并说明定位/通知权限缺口。
- **INV-12 恢复动作与 ownership 一致：** start 在首次 provider mutation 前被拒绝时没有 cleanup ownership，清 marker 并允许重新打开开关；已有 System Mock session 或 Stop cleanup 被拒绝时保留 cleanup ownership、恢复标记与“重试停止”。
- **INV-13 用户授权入口可达：** 产品文案指向的“选择当前千网游”必须能在 Android Settings 真实选择器完成；harness 不得用 `cmd appops set` 的后续成功代替候选 App 可达性证据。
- **INV-14 真实消费端时间轴稳定：** 一个 System Mock session 由同一 controller marker 统一拥有 framework gps/network 与 GMS fused mock mode；只有全部 start/publish 成功才进入 Running，任一 cleanup 层失败都保留 marker。验收在 Google Maps 前台连续采样 fused，整个窗口零 non-mock/非档案坐标；Stop 后 framework 无 test provider 残留且 Kyiv fused mock cache 消失。

## TDD 实施顺序

### Task 1 — 先锁 transport 与 Hook 互斥

**Tests:**
- `app/src/test/java/name/caiyao/fakegps/config/PublishedConfigTest.kt`
- `app/src/test/java/name/caiyao/fakegps/config/TransportSchemaContractTest.kt`
- `app/src/test/java/name/caiyao/fakegps/hook/LocationDeliveryPolicyTest.java`

**Implementation:**
- `SpoofSettings.kt`: 新增 `LocationDeliveryMode` 持久状态。
- `ConfigPrefsSync.kt` / `PublishedConfig.kt`: 发布与解析 `locationDeliveryMode`，schema v4。
- `MainHook.java`: 构建 Snapshot 后应用位置交付策略。
- 新增纯 `LocationDeliveryPolicy.java`: System Mock 清空位置组但保留其他字段。

先写红测：v4 模式解析、v2/v3 默认 Hook、System Mock 清位置而保留 cell/Wi-Fi。绿后跑整个 debug JVM suite。

### Task 2 — 从生效档案解析 Mock 配置

**Tests:** 新增 `EffectiveMockLocationResolverTest.kt`，覆盖 Kyiv、缺字段、越界、非数值、accuracy 缺省及 delivery mode。
**Implementation:** 新增 `EffectiveMockLocationResolver.kt`，输入 `PublishedConfig`，输出经验证的 `MockLocationConfig`。不得读取 DB 或 Intent extras。

### Task 3 — 把 provider lifecycle 迁入主 source set

**Tests:** 将 PR #8 controller/contract 测试迁入 `src/test`，增加：

- fresh-controller Stop 仍 cleanup；
- tick 坐标变化 replace provider；
- start 后模式发布失败回滚 cleanup；
- startup System Mock 恢复；Hook 仅在 durable cleanup marker 存在时规划 cleanup，普通 Hook no-op；
- marker 为 true 时无条件规划 Stop + Hook，即使持久 mode 因中途失败仍是 `system_mock`；
- null restart 不自动开始未知坐标。

**Implementation:**

- 通用 config/gateway/controller/service/status 移到 `src/main/.../mockprovider`。
- 用 `CoordinatedMockProviderGateway` 把 framework gps/network 与 `FusedLocationProviderClient` 纳入同一 controller primitive；所有 GMS `Task` 经有界 awaiter 在 service worker 上完成，UI/main thread 只接收最终状态。
- 服务只接受 `START_FROM_EFFECTIVE_PROFILE` / `STOP_AND_USE_HOOK`，移除坐标 extras。
- main manifest 声明 FGS 权限、非导出 location service，以及 Settings 用来发现模拟位置候选 App 的 legacy `ACCESS_MOCK_LOCATION`。后者属于产品核心能力而非误带测试代码，需用带原因注释的 `tools:ignore="MockLocation"` 压制 lint 的通用 test-only 假设，且 release manifest 不能缺失。
- 删除独立 `mockProvider` build type、variant launcher/UI 与 variant-only测试；功能入口只留主 App 设置页。

### Task 4 — 设置页开关与状态反馈

**Tests:** 为显示文案、期望/实际状态映射、失败提示和按钮 gating 建纯 UI contract 测试。
**Implementation:** 设置页新增 Material switch、当前生效档案坐标摘要、实际 Running/Failed 状态、开发者选项 guidance 与重试 Stop。开启调用 FGS，关闭调用 recovery stop；切换进行中禁用重复操作。

### Task 5 — Kyiv 与验收脚本

- `MapScreen.kt` 默认中心与 placeholder 改为 `50.4501,30.5234`。
- 改写 `scripts/mock_provider_acceptance.sh`：目标为 `.bench` 主 App，trap 恢复参考 mock app；Start 后断言 gps/fused 坐标为 Kyiv；Stop 后断言 gps provider 不是 mock 且 owner 为 GNSS。
- 新增结构测试，禁止脚本仅用 PID/app-op 判 Stop 成功。
- 验收先撤销通知权限并通过产品运行时权限弹窗授予；禁止 `pm grant POST_NOTIFICATIONS` 掩盖真实入口。
- 验收安装 exact APK 后，先检查 installed manifest 声明 `ACCESS_MOCK_LOCATION`，再打开 Android 真实“选择模拟位置信息应用”页面并断言 `.bench` 可见；只有这两项通过后，才允许用 shell app-op 继续自动化后续 provider 场景。
- 增加 app-op 改选恢复阶段：provider 仍 mock → UI 指引可见 → 重新选择 bench → 重试 Stop → GNSS。
- 增加独立只读时间轴门禁：真实 Maps 前台连续 120 次、每 0.5 秒读取 fused，任一 non-mock 或非 Kyiv 样本立即失败；该门禁必须先在旧 exact APK 上复现泄漏，再用于新实现放行。
- Stop 断言除 gps/network framework identity 外，还轮询确认 Kyiv fused mock cache 已清除；日志或单次 controller 状态不能替代消费端证据。
- 更新 evidence doc，旧 NYC Lab 证据保留为历史，不冒充本次主 App 证据。

### Task 6 — 验证与交付

1. `testDebugUnitTest --rerun-tasks`、结构契约、`assembleDebug`、`assembleRelease`。
2. `aapt` 检查 main/debug 包名、Xposed metadata、service exported/type 与两种 APK 的 `ACCESS_MOCK_LOCATION` 声明；`lintVitalRelease` 必须通过。
3. 在 moto g54 上先从真实系统选择器确认 `.bench` 可见，再建立 Kyiv 生效档案；选择 `.bench` 为 mock app，验证服务、gps/network/GMS fused 与 Maps 蓝点。
4. 从最近任务移除主 App，确认 FGS/provider/Kyiv 输出仍持续；再由 UI 切回 Hook并直接检查系统 provider 已恢复；force-stop/reopen 场景验证 startup reconciliation。
5. Maps 前台执行 fused 连续采样，整段零真实位置泄漏；Stop 后确认 framework providers 恢复系统来源且 GMS fused 不再保留 Kyiv mock cache。
6. 每轮 trap 恢复 `com.hopefactory2021.fakegpslocation` 且再次确认真实 GNSS。
7. quality-gate 后请求跨个体 exact-HEAD review；reviewer 独立构建与真机复跑，放行后再进入 merge gate。

## 非目标

- 不隐藏 `Location.isMock()`；System Mock 明确保留 mock marker。
- 不为缺少 Google Play Services 的设备伪造 Maps 稳定性承诺；GMS mock mode 不能启用时显式失败，不降级到已知会偶发泄漏真实位置的 framework-only Running。
- 不修改/复制 release 用户档案，不自动替用户选择开发者选项 mock app。
- 不宣称失去 mock app-op 后仍能自动移除 system_server test provider；Android 要求用户先重新选择原 App，本产品负责让这条恢复路径可发现、可验证。
- 不承诺 force-stop 已死亡进程后仍能即时执行代码；通过用户意图持久化、下次启动恢复与真相验收封住该窗口。
- 不把 System Mock 开关等同于全局“伪装模式”；蜂窝/Wi-Fi Hook 不受位置通道切换影响。

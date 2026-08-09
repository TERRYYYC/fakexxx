---
feature_ids: [F001]
topics: [android, mock-location, main-app, google-play-services, profiles, lifecycle, temporal-acceptance]
doc_kind: quality-gate-report
created: 2026-08-03
---

# Mock Location 主 App 集成 — Evidence Manifest

## Provenance

- Repository: `https://github.com/TERRYYYC/FakeGps-test.git`
- Delivery PR: [#10](https://github.com/TERRYYYC/FakeGps-test/pull/10), squash-merged into `master` on 2026-08-04
- Final reviewed head: `04848846599d65549aca7f86f1c6ef1f948eef24`
- Merge commit: `008923ecca96ab6e2234901e2a7dfbc595ff5737`
- Merge tree identity: reviewed head and merge commit both resolve to tree `2a7f8eb464e62715c5a01016d0b4f369d03c1743`
- Operator acceptance / merge authorization: thread message `0001785846783689-001813-254e8895`
- Review-finding base: `e9274cd26997a76f4fad7840926d9384d636f119`
- Remediation implementation commit: `d79cd735feaa6bd7ad26854dc84e08562277ec24`
- R2 finding base: `65834f713443a92dde14560a84b9d3d6b988e786`
- R3 first-start recovery implementation: `5dbcfa43b17d2982772c81ee9eb2c8897f49ee94`
- R4 picker-reachability implementation: `fdcc55a7326820f139b3955dc9b56c412ef22656`
- Final pre-rebase reviewed head: `a062afc8ad4478d9bac42e96c363b982a22a7218`
- Historical first latest-master integration base: `6fe6915931408dff6e795c5a433c4538a21a118d`
- Latest-master production integration commit: `50553535ed0a55084c428c6666fdcb380919b614`
- Authority exact-value remediation implementation: `8c92f65727568f71f02862b5ab52849c3932c536`
- Current master/rebase base: `3cd9c26889b0a9b69959138e9417b497c6332696`
- Issue #12 GMS fused implementation: `efb93692c256b11ddfd3d1356a1e6404f3771cc0`
- Issue #12 awaited-task contract: `bdc1a00275fb04df6eb692a5b268b3b83dcca561`
- Issue #12 framework source-set contract / final code head before evidence: `beb3fd5ef948c42e2dea65d932565216837888b4`
- Device: moto g54 5G `ZY22JHW9M4`, Android 15
- Debug main APK: `app/build/outputs/apk/debug/app-debug.apk`
- Latest-master Debug APK SHA-256 (production integration commit + Android Studio JBR 21.0.10): `723c5099ae8ae5820622ba88df78b9a20c14cf9631c86adf6e01a85049783ff0`
- Latest-master Release APK SHA-256 sample (same source/JBR, informational): `ca83f406181c271a45b51101c125106bd59858156798b8b53212194522b07244`
- Authority-remediation Debug APK SHA-256 (implementation commit + Android Studio JBR 21.0.10): `e1e1885ddaa847b6660548f16dfc518d3b8ca3d1a09ce4c1960377046519a636`
- Authority-remediation Release APK SHA-256 sample (same source/JBR, informational): `9ee0a5a39849979d61b2c743d48d8988495886406329f67d0162da82e84c9445`
- Issue #12 Debug APK SHA-256 (current code + Android Studio JBR 21.0.10): `7e18cdcc9e950cf69d2da8f23d728064405e483e1d21a1f2fe982814f4a5f80c`
- Issue #12 Release APK SHA-256 sample (same source/JBR, informational): `514f4bfe7a2ef41db53abb4a1a56384c4b2563fb9f073a8b694a1cdcae79e5cc`
- R4 author dogfood Debug APK SHA-256 (exact implementation, Android Studio JBR 21.0.10): `07b9a7c589149175c04913e595af22316addabfd3d167f384dd8d8979f8c23ef`
- R4 Release APK SHA-256 samples (same implementation + Android Studio JBR 21.0.10, informational): `ba3a4086337791096bf7bcc64a0289dd6bfdbcf3216d1db725087cd77616f523` / `8e98b5ba2bb77eb31fb8ad97b8bbc31a7b16f4a5e9f9e2a28a0cab156dfdd4d5` / final rerun `4db1c3be082c2a7d2fc152b658923b4f99319cc3a5c8cde4886907fe04e86a46`
- Historical pre-R4 R3 author Debug APK SHA-256 (Android Studio JBR 21.0.10): `0aa312f2e5fe9b6ce6ef67e17e1e90a6dadd540fcb2ac4ef1cf69d14396f9cbc`
- Historical pre-R4 independent reviewer Debug APK SHA-256 (Homebrew OpenJDK 17.0.20): `83e725aac7615f2d646b34fd920ecce8fbab5ca70cf6066a206bf891ad62ebc4`
- Historical pre-R4 Release APK SHA-256 (Android Studio JBR 21.0.10, informational): `ae3c923eb42b080a73edeeb0836cef1783ec68b7f122ef07f50d919b9f490863`
- Private screenshot: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/maps-main-kyiv-appop-recovery.png`
- Screenshot SHA-256: `6ae4f78e3ea1f7a3f2d99e201181974aa06658bdd2ea38e3c1a1a5bf63ee2c96`
- Recovery guidance screenshot: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/settings-main-appop-recovery.png`
- Recovery screenshot SHA-256: `a1b667893813253e93707f455565919bba5d05ba7923af1e19cd2b6a896625fd`
- First-start permission screenshot: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/settings-main-first-start-permission.png`
- First-start screenshot SHA-256: `cd9fecb9326a6743065a903e174353571f380802642e4f2ce6ad0903e05519c9`
- Settings OFF screenshot: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/settings-main-system-mock-off.png` (`08bb96ae13d77e3e48ece5def00649663474a9bef9b5f41b59cb905f4e1d6d0b`)
- 15-second switch recording: `/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/settings-main-toggle.mp4` (`bb359df2d29db50e67a5b00aea12965448aa47f97544bc891071653b307f9df1`)

截图留在设备备份目录而不进入 Git。画面显示 Google Maps 蓝点位于 Kyiv 的 Independence Square / Maidan Nezalezhnosti 附近，与主 App 生效中档案 `50.4501,30.5234` 一致。

## Post-merge acceptance（2026-08-04）

PR #10 已 squash merge 到 `master`（`008923ecca96ab6e2234901e2a7dfbc595ff5737`），`Fixes #12` 已使 Issue #12 自动关闭。合入后的主干重新执行 JVM、结构、Debug/Release 与 release vital lint 门禁：433/433 JVM、12/12 结构契约、Debug/Release/`lintVitalRelease` 均通过。

主干第一次增量构建得到 Debug APK `98113d88…23e0`，与 exact-head artifact 不同；比较 Git tree 后确认 merge commit 与 reviewed head 的 source tree 完全相同，再执行 `:app:clean :app:assembleDebug --rerun-tasks`，Debug APK 恢复为 `7e18cdcc9e950cf69d2da8f23d728064405e483e1d21a1f2fe982814f4a5f80c`。差异来自主干 worktree 复用的增量 DEX shard/cache，不是 squash merge 代码漂移；因此 `98113d88…23e0` 作废，不纳入 artifact provenance。

随后用该 clean merged-main APK 在 moto g54 5G `ZY22JHW9M4` 从真实 provider 基线完整复跑产品链，exit 0：真实系统 picker 可见 → 首次未授权指引 → 重启干净 → Kyiv gps/network/fused → 划掉任务卡仍保活 → Google Maps 前台 120×0.5 秒连续采样零泄漏 → app-op 残留恢复 → fused mock cache 清除 → gps/network 恢复系统来源。最终设备状态为 `.bench` mock app-op denied、参考 App allowed、gps identity=`1000/android[GnssService]`、network/fused 为 GMS 真实 provider，参考 App 回到前台。

```text
FUSED_MOCK_STABILITY_COMPLETE samples=120 interval=0.5s coordinate=50.450100,30.523400
MOCK_STABILITY_COMPLETE samples=120 interval=0.5s
FUSED_MOCK_CACHE_CLEARED observed=Location[fused 50.450968,30.410239 ...]
PROVIDER_REAL gps=GnssService network=system
RESTORE bench=deny reference=allow provider=real status=0
ACCEPTANCE_RESTORE_PHASE_COMPLETE
```

## 对 co-creator 纠正与手动发现的闭环

| 纠正 | 产品终态 | 验收证据 |
|---|---|---|
| Lab 没有合入主 App | 删除独立 `mockProvider` build type；service/controller/gateway 进入 `src/main`，设置页成为唯一入口 | debug/release 均含非导出 `MockProviderService`；不再生成 Lab APK |
| 数据应来自主 App 档案，开关决定 Hook/Mock | schema v4 发布 `locationDeliveryMode`；System Mock 每 tick 解析 `ConfigPrefsSync` 的同一份生效档案；System Mock 时 Hook 仅清空位置字段，cell/Wi-Fi 保留 | JVM 契约覆盖 v2/v3 兼容、档案解析、位置旁路与非位置字段保留；真机用 `ProfileRepository` 保存 Kyiv 后经正常 transport 输出 |
| 虚拟位置不能停 | cleanup marker 表示未完成切换事务；显式 Stop 无条件 remove；失去 mock app-op 时给出“重新选择当前千网游 → 重试停止”；移除任务卡不停止 FGS | 任务卡移除后 FGS 与 Kyiv gps/fused 仍持续；验收会在运行中改选 mock app，确认指引后重新授权并由同一 Stop primitive 恢复 `identity=1000/android[GnssService]` |
| 地址改为基辅 | 地图默认、示例与隔离验收档案统一为 `50.4501,30.5234` | gps、fused 和 Maps 蓝点三路一致 |
| 手动验收在系统选择器找不到千网游 | main manifest 声明 Settings 候选发现所需的 `ACCESS_MOCK_LOCATION`，并解释性压制只适用于普通 App 的 `MockLocation` lint 假设 | Debug/Release merged manifest 都有声明；真机 harness 在首次 shell app-op 前打开真实系统 picker，输出 `MOCK_APP_PICKER_ENTRY_VISIBLE` |
| Maps 在 mock 与真实位置之间闪断 | framework gps/network 与 GMS `FusedLocationProviderClient` 由一个 controller transaction 协调；GMS Task 在 worker 上有界等待，失败不降级成 framework-only Running | 旧 exact APK 被新时间轴门禁在 sample 2 击杀；新 APK 在真实 Maps 前台 120×0.5 秒全为 Kyiv mock，Stop 后 GMS Kyiv cache 消失；Issue #12 |

## Exact-code 真机结果

验收从真实 GNSS 且参考 App 独占 mock app-op 开始，只安装 `.bench` debug main APK，并仅重置 `.bench` 的隔离数据。R4 reviewer follow-up 从明确的 `mWakefulness=Dozing` 起点运行同一脚本；Issue #12 当前实现用 `7e18cdcc…80c` 完整复跑，picker、Kyiv 输出、任务移除、Maps 时间轴、app-op recovery、GMS cache clear 与最终 restore 均通过：

```text
PROVIDER_REAL owner=GnssService
MOCK_LOCATION_PERMISSION_DECLARED package=name.caiyao.fakegps.bench
MOCK_APP_PICKER_ENTRY_VISIBLE package=name.caiyao.fakegps.bench label=千网游·测试
NOTIFICATION_PERMISSION_GRANTED via=product-runtime-request
FIRST_START_PERMISSION_GUIDANCE_VISIBLE
PROVIDER_REAL owner=GnssService
FIRST_START_RESTART_CLEAN
PROVIDER_MOCK owner=name.caiyao.fakegps.bench coordinate=50.4501,30.5234
isForeground=true ... types=0x00000008
last location=Location[gps 50.450100,30.523400 ... mock]
last location=Location[network 50.450100,30.523400 ... mock]
last location=Location[fused 50.450100,30.523400 ... mock]
TASK_REMOVED label=千网游·测试
ACCEPTANCE_TASK_REMOVAL_PHASE_COMPLETE
MAPS_FOREGROUND ... com.google.android.apps.maps/com.google.android.maps.MapsActivity
FUSED_MOCK_STABILITY_COMPLETE samples=120 interval=0.5s coordinate=50.450100,30.523400
MOCK_STABILITY_COMPLETE samples=120 interval=0.5s
ACCEPTANCE_ACTIVE_PHASE_COMPLETE
PROVIDER_MOCK_RESIDUE owner=name.caiyao.fakegps.bench
APP_OP_RECOVERY_GUIDANCE_VISIBLE
FUSED_MOCK_CACHE_CLEARED observed=Location[fused 50.451003,30.410315 ...]
PROVIDER_REAL owner=GnssService
ACCEPTANCE_APP_OP_RECOVERY_PHASE_COMPLETE
RESTORE bench=deny reference=allow provider=real status=0
REFERENCE_APP_FOREGROUND ... com.adevinta.leku.LocationPickerActivity
ACCEPTANCE_RESTORE_PHASE_COMPLETE
```

恢复后又单独启动一次 `.bench` 的默认 Hook 模式：mock app-op 仍由参考 App 独占、`MockProviderService` 不存在、gps provider 仍是真实 GNSS、日志无启动失败。最后再次 force-stop `.bench` 并打开参考 App。

Settings OFF 图与 15 秒录屏来自首轮主 App 集成，覆盖用户入口、同一生效档案坐标和开关动作。R4 exact build `07b9a7c5…c23ef` 先验证 installed permission，再真实打开 Android picker 并看到“千网游·测试”，之后才允许 shell app-op 自动化后续步骤。Maps 阶段先移除任务卡并确认 FGS/gps/fused 继续；Maps 已跟随蓝点而没有渲染 recenter 控件，脚本按可选控件处理。随后脚本改选 app-op，验证残留 provider 与恢复指引，再重新授权当前千网游并由“重试停止”恢复 GNSS。最后确认 `.bench` 仍安装、服务无残留，并恢复参考 App。

R3 first-start 图来自作者用 Android Studio JBR 21.0.10 构建的 debug APK `0aa312f2…f9cbc`：开关保持关闭，状态明确说明 System Mock 未启动，动作是“选择当前千网游 → 重新打开开关”；画面没有“残留位置”或“重试停止”。截图后同一 harness force-stop/reopen，并以 `FIRST_START_RESTART_CLEAN` 证明失败未持久化为 cleanup transaction。Fable5 另用 Homebrew OpenJDK 17.0.20 从 exact HEAD 构建 `83e725aa…ebc4` 并跑完整真机链 exit 0。

## Fresh verification

| Gate | Result |
|---|---|
| `./gradlew testDebugUnitTest --rerun-tasks` | Issue #12：433 tests；0 failure/error/skipped（从 XML 重算） |
| `./gradlew assembleDebug assembleRelease lintVitalRelease --rerun-tasks` | BUILD SUCCESSFUL；新增官方 `play-services-location:21.4.0` 输入 |
| `python3 scripts/test_mock_provider_main_integration.py` | 12/12 pass；新增 GMS 协调/await、framework source set、独立 fused 时间轴与 Stop cache-clear 契约 |
| `bash -n scripts/mock_provider_acceptance.sh scripts/assert_fused_mock_stability.sh` | pass |
| `git diff --check` | pass |
| APK manifest inspection | debug `.bench` / release main identity 正确；两者都声明 `ACCESS_MOCK_LOCATION`，并保留 Xposed metadata、动态 provider authority 与 `foregroundServiceType=location` |
| reviewer 原始 5 个变异 | `readCleanupRequired`、refresh mode guard、`cleanupRuntimeOnly.stop()`、Hook passthrough、sample fixed clock 任一破坏均使定向测试变红 |
| R3 新增 4 个变异 | 合并 start/stop recovery、跳过首次失败 marker clear、删除普通 Hook cleanup guard、丢失 refresh ownership context 均编译成功并触发定向断言失败 |
| 旧 APK + `scripts/assert_fused_mock_stability.sh` | installed exact old APK hash `e1e1885d…a636`；sample 2 输出 `FUSED_REAL_LOCATION_LEAK`，证明新门禁能击杀 co-creator 所见缺口 |
| `scripts/mock_provider_acceptance.sh ZY22JHW9M4` | 当前 APK 完成 installed permission / real picker / notification / first-start / restart clean / Kyiv gps+network+fused / task removal / Maps 120-sample stability / app-op recovery / fused cache clear / restore，exit 0 |
| Issue #12 mutations | C1–C10 全部 KILLED；C1–C4/C8 为编译成功后的 JVM 断言失败，C5–C7/C9–C10 为结构契约精确失败；两轮缺 SDK 的 harness 假击杀已明确作废 |
| `./gradlew lintDebug --rerun-tasks` | inherited baseline：20 errors / 158 warnings；20 个 error 全部位于未改动的 `HookProbe.kt`、`MainActivity.java`、`TempDao.java` 与 `res/values/strings.xml`，本 diff 零 lint error；release `lintVital` 通过 |

## Quality Gate 审计

- Vision / delivery completeness：operator 纠正已逐项映射到产品入口、同源档案、真实 Stop、Kyiv 与系统选择器可达性；本次产物是可扩展的主 App 实现，不再需要把 Lab 重写一遍。
- Close gate：当前只申请 code review，不关闭整个 F001；follow-up-tail scan 除本句的审计术语外零命中，无未满足 AC 被包装为“后续”。
- Architecture ownership：`Android application / location delivery`；`Map delta: none`，因为仓库无 ownership registry，官方 GMS client、协调 gateway 与 service 均在既有 `:app` 位置交付 cell 内。新增依赖不拥有状态；durable cleanup marker 仍是唯一事务真相，不引入第二份存储。
- Fallback audit：仓库无自动脚本。R4 新 picker 函数只在一个真实 Settings 真相源内做有界的页面滚动、中文/英文行名匹配与 label/package 候选匹配；这些不会退化到 app-op、manifest 或缓存代理证据，失败统一返回并交给 trap restore，因而不是逐层降低标准的 fallback。既有 orchestrator marker/ownership guards、controller recovery 与 Settings 动作仍是同一显式状态坐标的互斥边。
- Design check：仓库 glob 无 `.pen`；主 App 集成整体含 Compose UI，已有真实设备恢复指引、Settings OFF、15 秒开关录屏与 Maps 下游证据。R4 delta 仅改 manifest、验收 harness、结构测试与文档，没有新增 UI 布局或文案。
- Artifact hygiene：Git 工作树与 `origin/master...HEAD` 均无仓库根目录媒体；设备图片/视频仅在正式 backup evidence 目录。
- Capability tips / Cat Café architecture scripts：该 Android 仓库无对应 surface，not applicable。

### Dogfood-Your-Slice

Scope verdict：✅ 必做。

真实路径：安装 `.bench` → 验 installed manifest → 打开 Android 真实模拟位置 App 选择器并看到“千网游·测试” → 隔离 `.bench` 经 `ProfileRepository` 保存 Kyiv → 产品弹窗授予通知权限 → 正常 `ConfigPrefsSync` 发布 → 设置页打开 System Mock → gps/fused/Maps 验证 → 运行中改选 mock app → 恢复指引可见 → 重新选择当前千网游 → 重试停止 → GNSS → 恢复参考 App。

Dogfood 当轮发现并修复：

1. 旧 harness 只看 PID/app-op，漏掉 system_server 孤儿 provider → 改为 provider-truth 断言并引入 durable cleanup marker。
2. `.bench` manifest authority 与硬编码 `UriMatcher` 不一致 → authority 改为由 `BuildConfig.APPLICATION_ID` 构造并加 JVM test。
3. 普通 Hook 启动若无 app-op 会产生伪失败 → 只在 cleanup marker 为 true 时恢复清理，并增加 startup-plan test 与真机 no-op 验证。
4. debug 数据准备在 app-op 切换前做无意义 cleanup → 准备步骤仅重置隔离数据/marker，最终运行日志不再有伪失败。
5. 初版 Maps 截图停留在旧视野，蓝点不能证明 Kyiv → harness 明确点击“重新将您所在位置设为地图中心”后再截图，当前图显示 Independence Square。
6. reviewer 复现运行中改选 mock app 后无法 Stop → `SecurityException` 映射为结构化恢复动作；设置页内联 Developer Options 入口和重试步骤；验收必须复现并恢复该边界。
7. 验收脚本 `pm grant POST_NOTIFICATIONS` 掩盖首次用户路径 → 改为 revoke 后驱动产品权限弹窗，并核验最终 permission state。
8. 独立复跑撞到 DocumentsUI 残留 task 与 Maps 瞬态 recenter 按钮 → 首次设置页用 portable clear-task flag；recenter 变为多语言可选控件，gps/fused/provider truth 与截图仍是硬证据。
9. R2 首次未授权被误报为残留且 marker 永久保留 → recovery 拆成 start/cleanup 两条边，失败状态显式携带 cleanup ownership；harness 新增首次指引与进程重启清洁阶段。
10. co-creator 按首次指引手动进入系统选择器，却找不到千网游 → main manifest 补齐 `ACCESS_MOCK_LOCATION`；harness 在任何 shell app-op 旁路前验证 installed permission 与真实 picker 候选，堵住“开发者路径替代用户路径”的同类假绿。
11. R4 reviewer 从息屏设备运行 picker 门禁，因 wake/unlock 只存在于稍后的 `open_settings()` 而无法打开系统页 → 抽取单一 `wake_and_unlock_device` seam，由 picker 与设置页共用；结构契约锁定 picker 在启动 Settings 前调用它。
12. merge 前 master 新增“保存哪条档案就发布哪条”语义，与本分支 schema v4/delivery mode 在同一 writer 相交 → rebase 后保留显式/active profile 路由、last-good 与原子 pointer commit，同时继续发布 schema v4 的 `locationDeliveryMode`；真机 Kyiv 链共同验证。master 新 authority 测试直接初始化 Android `Uri` 导致 local JVM stub 崩溃，因此把精确 authority 值移到纯 JVM contract，并以普通值断言 + provider/publisher bytecode wiring 两层守护。
13. rebase continuity review 发现 bytecode 只验“符号存在”，`AUTHORITY = helper(...) + ".x"` 仍让 418 tests 假绿 → 恢复精确值断言并让 provider 与 `ConfigPrefsSync` 共用 `ProviderAuthority.AUTHORITY`；最终 MA3 真实断言失败、420/420 与真机链通过。
14. co-creator 连续观察 Maps 发现 Kyiv mock 偶发闪回真实位置；旧 harness 的单次 fused 快照约 97% 概率假绿。gps+network 单变量实验仍泄漏，进一步定位为 GMS FLP 未进入 mock mode → 引入官方 `FusedLocationProviderClient`、单一协调事务、GMS Task 有界等待与真实 Maps 时间轴门禁；旧 APK 红、新 APK 120 样本绿。

## Fable review findings 处置

| Finding | 处置 |
|---|---|
| Issue #12 P1 Maps 偶发泄漏真实位置 | `CoordinatedMockProviderGateway` 统一 framework gps/network 与 GMS fused；任一半成功路径用同一 cleanup marker 清理。旧 exact APK 被独立时间轴探针击杀，新 APK 120×0.5 秒零泄漏；Stop 清除 framework providers 与 GMS Kyiv cache；C1–C10 全被门禁击杀。 |
| rebase 后 authority 值契约被弱化，MA3 存活 | `ProviderAuthority.AUTHORITY` 成为 provider/publisher 单一纯 JVM 值；恢复 manifest 模板精确断言并保留两端 production wiring tests。RED 为 `ComparisonFailure`，最终 MA3 KILLED；420/420、两种 APK、release vital lint 与真机链通过。 |
| merge 前 master 前进并与配置发布/authority 相交 | rebase 到 `6fe6915`；保留 selected-profile publication 与 schema v4/delivery mode 两套契约；authority 统一由 `ProviderAuthority` 构造。targeted tests 先暴露 Android stub 测试坐标错误，再改为 JVM-safe bytecode wiring contract；全门禁与真机复跑通过。 |
| R4 P2 picker 门禁不唤醒息屏设备 | 共用 `wake_and_unlock_device` seam；RED 因 helper 缺失失败，GREEN 8/8；从 Dozing 起点重跑真实 picker/链路。 |
| R4 P3 implementation SHA 不存在 | evidence 与 R4 packet 统一更正为真实 commit `fdcc55a7326820f139b3955dc9b56c412ef22656`，并用 `git cat-file -e` 验证对象存在。 |
| R4 P0 系统选择器没有千网游 | main manifest 声明 `ACCESS_MOCK_LOCATION` 并带原因压制 release lint；结构契约先红后绿；真机真实 picker 前置门禁与完整链 exit 0。 |
| P1 app-op 改选后 Stop 死局 | 结构化 `MockProviderRecovery` + 内联 Developer Options 指引；真机完整复现与恢复。 |
| P1 `cleanupRuntimeOnly()` 零覆盖 | 新增 side-effect/order test；删除 `controller.stop()` 的 reviewer 变异现会失败。 |
| P2 Hook 空测试 | 改为逐字段断言；破坏 Hook passthrough 的变异现会失败。 |
| P2 notification 由 harness 代授 | 产品 Android 13+ 运行时请求；harness 从 revoked state 驱动真实弹窗并恢复原权限状态。 |
| P2 海拔硬编码 | `altitude` 从同一 PublishedConfig 档案解析；缺失时保持 absent，不发明城市常量。 |
| P2 harness 不可复跑 | 清理 DocumentsUI task；Maps recenter 可选且多语言；新增 app-op recovery phase。 |
| P2 时钟/controller 覆盖回退 | 恢复 exact clocks、tick count、重复 start、幂等 stop、边界与非有限海拔断言。 |
| P2 orchestrator 失败分支 | 21 个 orchestrator tests 覆盖 marker、mode、publish、provider side effect、rollback message 与 refresh guards；三个原始变异均被击杀。 |
| P2 F001 main 文档仍误报 resolved | 修正文档已在 insight branch `docs/f001-mock-provider-main-integration` / `356000b`，状态为 `in-progress`；co-creator 明令所有 merge 需其确认，因此此处只准备、不越权合入 insight main。 |
| R2 P1 首次 start denial 被误报为残留 | `providerCleanupRequired` + start/stop recovery 分流；首次失败清 marker、普通 Hook onDestroy no-op、refresh 保留已有 ownership；4 个新变异与 moto g54 两阶段验收均通过。 |

## Fresh-context findings 处置

| Finding | 处置与证据 |
|---|---|
| 未完成 enable 遇到无效档案可能遗留 provider/mode | `enable()` 在持久 mode 或 transaction marker 表示 System Mock 时先执行完整 Stop + Hook 回滚；普通 Hook 的无效档案仍不碰系统 provider。JVM 覆盖两条分支。 |
| Hook 回滚忽略 mode 持久化失败 | 回滚仅在 mode、配置发布与 provider cleanup 全部成功后清 marker；持久化失败保留 marker，下次启动无条件 Stop + Hook。 |
| `stopWithTask=true` 造成划掉任务即停服务 | 主 service 移除该属性；合并 manifest 证明属性不存在；真机划掉任务后 FGS、gps/fused Kyiv 均继续。 |
| Starting/Stopping 未发布且开关可连点 | controller 每次 transition 发布真实状态；ViewModel 在发命令前同步发布过渡态；纯 UI contract 在 Starting/Stopping 禁用 switch。 |
| Stop 失败后没有重试入口 | Failed 状态禁用 switch 并显示“重试停止”；第二次完整 Stop 成功后 marker 清除并回 Idle。 |
| “单通道”被写成瞬时保证 | INV-1 改为单一持久交付意图 + 既有 5–60 秒跨进程刷新收敛；短暂重叠使用同一生效档案坐标，不再声称原子切换。 |

## 已知边界

- System Mock 位置保留 Android mock marker；本功能不尝试隐藏 `Location.isMock()`。
- Google Maps 稳定通道依赖设备可用的 Google Play Services。若 GMS mock mode/Task 不可用，产品显式失败并保留 cleanup ownership，不静默退化到已知会泄漏真实位置的 framework-only Running。co-creator 已明确授权该依赖；当前 moto g54 的真实验收覆盖这一路径。
- API 24–30 legacy registration 有纯代码契约和 review 覆盖；本轮真机是 API 35。
- Android 不允许 App 自行成为“模拟位置信息应用”。main manifest 的 legacy `ACCESS_MOCK_LOCATION` 只让产品进入 Settings 候选列表，真正授权仍由用户选择后的 `mock_location` app-op 控制。若用户在运行中改选别的 App，原 test provider 可能残留；设置页会明确要求重新选择当前千网游后重试，绝不把权限失败显示成已停止。
- 已运行的 Hook 目标进程按既有 5–60 秒可配置周期读取 mode。切换期间 provider 与旧 Snapshot 可能短暂重叠，但两者来自同一生效档案坐标；目标在下一次刷新读取新模式，这是现有 transport 的传播语义，不伪装成跨进程同步切换。
- debug acceptance Activity 受 debug-only `android.permission.DUMP` gate 保护且不进入 release；它只操作 `.bench` 数据。该权限可由 adb 授予，正是无 root 验收 seam 的有意取舍。
- APK hash 必须与 exact source **及执行 Gradle 的 JDK**一起解释，不能单独作为跨环境 artifact identity。Issue #12 当前 JBR 21 debug 为 `7e18cdcc…80c`，release sample 为 `514f4bfe…e5cc`（informational）；新增 GMS 依赖是 APK 输入的一部分。latest-master production integration `50553535…b614` 在 JBR 21 的 debug 为 `723c5099…ff0`，release sample 为 `ca83f406…244`（informational）。R4 JBR 21 debug 为 `07b9a7c5…c23ef`；release 在同一源码/JBR 的三次 R8 构建得到 `ba3a4086…f523`、`8e98b5ba…d4d5` 与 `4db1c3be…6a46`，因此只作 informational build sample。R3 曾从 clean exact HEAD 决定性复现 debug：JBR 21.0.10 为 `0aa312f2…f9cbc`，OpenJDK 17.0.20 为 `83e725aa…ebc4`；两者签名证书、资源及 16/18 个 DEX 相同，差异来自 javac 对 enum switch 的 lowering（JDK 17 额外生成 `UnavailableValueResolver$1`），继而改变两个 DEX。项目当前只固定 Java source/target 17，未固定 Gradle runtime JDK。Release R8/resource shrinking 的逐位输出不作为 exact source identity。
- 上述规则此前**只是约定**：仓库里没有任何脚本产出过 APK hash 证据（`scripts/test-hook.sh` 算了一个但只用于 install 幂等判断，随即丢弃），`javaVersion` 全仓库仅在 `debug-apk-hash-jdk-drift/bug-report.md` 手打过一次。自本轮起改由 `scripts/apk_provenance.py` 产出单行证据，hash、exact source 与 Gradle **daemon** JDK 在同一行且缺一不可，缺任一项则不输出任何行并以 exit 2 失败——"忘记标注 JDK"从可能变为不可表达。发布证据用 `python3 scripts/apk_provenance.py --build :app:assembleRelease`（**不传路径**，产物由任务推导）。`source_binding=built` 表示：任务有声明产物、产物被清除后重新出现（因果证据）、构建前后 source clean 且未变、路径非调用方传入。已装 / 第三方 APK 只能得到 `source_binding=asserted`——它**不是** exact-source binding，不得当作源同一性引用。契约见 `scripts/test_apk_provenance.py`。
- 退役 Lab APK `name.caiyao.fakegps.mockprovider` 可能仍安装在开发设备；产品不会擅自卸载它。验收前置守卫要求参考 App 是唯一获准 mock app，避免 stale Lab 争用 app-op。

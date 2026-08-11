---
feature_ids: [F001]
topics: [android, mock-location, manifest, developer-options, acceptance]
doc_kind: bug-report
created: 2026-08-03
status: resolved
resolved: 2026-08-03
---

# 模拟位置信息应用选择器不显示千网游

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | co-creator 按产品指引打开开发者选项后，模拟位置信息应用列表只有“无”和参考 App，没有千网游；因此无法给产品授予 System Mock app-op。期望 release 与 `.bench` 都能被真实用户选中。 |
| **2. 证据** | PR #10 exact HEAD `48f28da0…`：源码、merged debug manifest、已安装 `.bench` requested permissions 与 APK permissions 均无 `ACCESS_MOCK_LOCATION`；moto g54 选择器 UI dump 只显示参考 App。验收脚本有 5 次 `cmd appops set`，对权限声明与选择器可达性断言均为 0。 |
| **3. 根因** | 产品 manifest 未声明 `android.permission.ACCESS_MOCK_LOCATION`。Android 15 选择器以该声明发现候选 App；直接写 app-op 绕过发现入口，使自动验收产生假绿。 |
| **4. 诊断策略** | 沿“产品指引 → Developer Options → picker 候选发现 → app-op”逆向追踪；对照参考 App、已安装包 dumpsys、merged APK 与真实 UI dump；扫描 harness 所有 out-of-band app-op 切换。 |
| **5. 超时策略** | 若权限声明后已安装包可见但 picker 仍不可见，停止继续补 UI 脚本，改查 Settings 的候选过滤条件与 package refresh；不靠更多 appops 绕行。 |
| **6. 预警策略** | 只在 debug manifest 声明、只断言源码、不验证已安装包/picker、或静默 suppress `MockLocation` lint，均说明修复没有闭合真实用户路径。 |
| **7. 用户可见交互修正** | 用户进入开发者选项后能看到并选择当前安装的“千网游”或“千网游·测试”，随后可按产品指引重开开关/重试停止。 |
| **8. 验收** | RED：新增结构契约后 2 条失败（manifest 无声明、harness 无入口门禁）。GREEN：结构契约 7/7、JVM 412/412、Debug/Release/`lintVitalRelease` 成功；两种 merged APK 均含权限；moto g54 安装 exact debug APK 后依次输出 `MOCK_LOCATION_PERMISSION_DECLARED`、`MOCK_APP_PICKER_ENTRY_VISIBLE`，随后完整 System Mock/Maps/recovery/restore 链 exit 0。 |

## Failure-mode audit

这与 R2 的通知权限 finding 同属“验收脚本用开发者旁路替代用户入口”。不变量：任何产品文案指向的系统授权动作，harness 在使用 adb/appops 旁路继续后续阶段前，必须先证明真实系统入口存在且目标 App 可达。扫描范围为 notification runtime prompt 与 mock-location picker；前者已有产品弹窗门禁，本轮补齐后者。

### R4 review follow-up：息屏设备打不开 picker

| 栏位 | 内容 |
|---|---|
| **1. 现象** | R4 picker 门禁在 `mWakefulness=Dozing` 时以 `Unable to open the system mock-location app picker` 失败；唤醒同一设备后立即找到真实行。期望 harness 从正常息屏起点也能验证 picker。 |
| **2. 证据** | Fable5 独立 exact-HEAD 复跑：picker assertion 位于首次 `open_settings` 之前，函数内 `KEYCODE_WAKEUP` / `dismiss-keyguard` 调用数为 0；真实文案和扫描预算在唤醒后均通过。 |
| **3. 根因** | 屏幕/锁屏归一化被私有地放在稍后才调用的 `open_settings()`，而更早执行的 picker 系统 UI 门禁没有自己的前置条件。 |
| **4. 诊断策略** | 按调用顺序检查所有 UI 驱动函数，区分“选择器不存在”和“设备不可交互”；抽取单一 wake/unlock seam，并由结构测试锁住 picker 在启动 Settings 前调用它。 |
| **5. 超时策略** | 若抽取 seam 后 Dozing 仍失败，停止增加 sleep/scroll 次数，改采集 wakefulness、keyguard 与 `am start -W` 状态，定位 OEM 电源/锁屏边界。 |
| **6. 预警策略** | 复制 wake 两行到多个调用点、增加扫描预算、或把 `Unable to open` 混成“App 不在列表”都表示修错坐标。 |
| **7. 用户可见交互修正** | 无产品 UI 变化；reviewer/operator 可从手机正常息屏状态直接运行验收。 |
| **8. 验收** | RED：`test_picker_acceptance_wakes_device_before_opening_settings` 因 helper 不存在失败。GREEN：抽取 `wake_and_unlock_device`，picker 与 `open_settings` 共用；结构契约 8/8、`bash -n` 通过，并从 Dozing 起点重跑真实 picker/完整链。 |

## 报告人

co-creator 在 PR #10 final-HEAD 手动验收中发现；Fable5 复现、定位 manifest 根因并用一次性实验包验证方向；Sol 负责独立复现、TDD 修复与最终验收。

## 复现步骤

1. 安装 PR #10 exact-HEAD `.bench` APK，保持参考 App 为当前模拟位置应用。
2. 在千网游设置页首次打开 System Mock，看到未授权指引。
3. 点击“选择当前千网游”进入开发者选项，再打开“选择模拟位置信息应用”。
4. 实际列表只有参考 App，无法选择千网游。

## 根因分析

自动验收只证明了 app-op 被 shell 直接写入后 provider 能工作，没有验证 Android Settings 是否把产品发现为候选。`ACCESS_MOCK_LOCATION` 在现代 Android 上不负责运行时授权本身，但仍是 Settings 候选发现的 manifest 信号；缺失它会让产品指引成为不可执行动作。

## 修复方案

1. 在 `src/main/AndroidManifest.xml` 声明 `android.permission.ACCESS_MOCK_LOCATION`，保证 debug 与 release 都能成为 Settings 候选。
2. 由于 mock location 就是产品能力，用带原因注释的 `tools:ignore="MockLocation"` 压制 lint 的“只应出现在测试 manifest”通用假设；不把权限降到 `src/debug`。
3. 验收脚本安装 APK 后先解析 installed manifest，再打开 Android 真实开发者选项选择器并扫描到“千网游·测试”。这两项都发生在第一次 `cmd appops set` 前；shell 旁路只负责自动化后续 provider 生命周期，不再冒充用户入口证据。
4. 结构契约同时锁住 main manifest 声明、带理由的 lint suppression，以及两条真机门禁必须存在并被调用。

## 验证方式

- `python3 scripts/test_mock_provider_main_integration.py`：7/7。
- `bash -n scripts/mock_provider_acceptance.sh`：通过。
- JBR 21 执行 `testDebugUnitTest assembleDebug assembleRelease lintVitalRelease --rerun-tasks`：BUILD SUCCESSFUL，JVM 412/412。
- `aapt2 dump permissions` 与 merged manifests：debug `.bench`、release main 均声明 `ACCESS_MOCK_LOCATION`。
- moto g54 Android 15：真实 Settings picker 列出“千网游·测试”；完整验收依次覆盖首次未授权文案、重启清洁、Kyiv gps/fused、任务卡移除仍持续、Maps、app-op 丢失恢复、真实 GNSS 与参考 App restore，exit 0。
- 最终设备状态：`.bench` app-op deny、参考 App sole allow、`gps identity=1000/android[GnssService]`、`.bench` 无残留 service；仅保留安装包以便复审。

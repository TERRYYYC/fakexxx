---
feature_ids: [F001]
topics: [android, mock-location, manifest, developer-options, acceptance, review]
doc_kind: review-request
created: 2026-08-03
---

# Review Request — F001 Mock Location 主 App 集成 / Fable R4

Review-Target-ID: `f001-mock-location-main-integration`
Branch: `feat/mock-provider-main-integration`
PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`
Withdrawn-approval base: `48f28da0fb84f8553cb6dbe7b67e297255e56ca9`
R4 implementation commit: `fdcc55a7326820f139b3955dc9b56c412ef22656`
Reviewer: `@fable5`（只读 review + 独立验证）

> Review packet/evidence 的 carrier commit 不属于产品逻辑。请从 PR #10 remote pull head 解析 exact HEAD，并与本轮路由消息中的 SHA 三方核对。

## What

- main manifest 声明 `android.permission.ACCESS_MOCK_LOCATION`，让 debug/release 产品都进入 Android“选择模拟位置信息应用”候选列表。
- 保留 release 声明，并用带原因注释的 `tools:ignore="MockLocation"` 压制 lint 对普通 App 的 test-only 假设；不把修复降到 `src/debug`。
- 真机 harness 在任何 shell `mock_location` app-op 旁路前，先检查 installed manifest，再打开真实 Android Settings picker 并断言“千网游·测试”可见。
- 结构契约锁定权限、lint suppression 与两条前置门禁；feature spec 新增“用户授权入口可达”不变量。

## Why

co-creator 在 final-HEAD 手动验收时按产品指引进入开发者选项，却找不到千网游。旧 harness 五次直接 `cmd appops set`，证明 provider 获权后能运行，却完全绕过“用户能否选择当前 App”。源码、merged manifest、已安装包和真实 picker 共同确认根因是 main manifest 缺少候选发现所需的 legacy 声明。

## Original Requirements

> “你这部分功能你打算怎么合入主app呢？”
> “做成一个开关，数据则是从主app的档案来获取，即开关来决定是使用hook还是mock”
> “你现在这个xxx lab app 虚拟位置不能停你发现了吗？”
> “我建议虚拟地址选择 基辅”
> “不行啊，我打不开，千网游的 设置下的 系统mock位置，显示not allowed to peform mock_location，按提示建议去搞也没有找到我们的app”

- 前四项来源归档：`review-notes/2026-08-03-mock-location-main-integration-review-request.md`；手动 finding 来源：co-creator direct message `0001785767834296-001631-a577b83a`。
- Fable5 在 exact HEAD `48f28da0…` 复现：选择器只有“无”与参考 App；一次性加权限实验后“千网游·测试”立即出现，并据此撤回 R3 APPROVE。
- 请对照这五项判断 R4 是否真正恢复用户路径，而不只让 shell 自动化继续通过。

## Red → Green

RED 先于实现：

- `test_product_manifest_declares_mock_location_permission_for_system_picker` 因 main manifest 无声明失败。
- harness contract 因 permission/picker assertion 数为 0 失败。

GREEN：

- 结构契约 8/8，`bash -n` 通过；R4 reviewer follow-up 新增 picker 自身 wake/unlock 顺序契约。
- JBR 21 全门禁 412/412，Debug/Release/`lintVitalRelease` BUILD SUCCESSFUL。
- debug/release merged manifest 与 APK permissions 都包含 `ACCESS_MOCK_LOCATION`。
- moto g54 exact debug APK 在第一次 shell app-op 前输出：

```text
MOCK_LOCATION_PERMISSION_DECLARED package=name.caiyao.fakegps.bench
MOCK_APP_PICKER_ENTRY_VISIBLE package=name.caiyao.fakegps.bench label=千网游·测试
```

- 后续 first-start、restart-clean、Kyiv、task removal、Maps、app-op recovery、GNSS/参考 App restore 全链 exit 0。

## Tradeoff

- `ACCESS_MOCK_LOCATION` 在现代 Android 不自行授予 mock 能力；它仍是 Settings 的候选发现信号，真实 authority 是用户选择后的 app-op。
- 只放 debug 会让 release 产品继续不可达；因此声明必须在 main。`MockLocation` lint 针对误把测试能力带入普通 release App，但模拟位置正是本产品显式能力，带原因 suppression 比降级功能更准确。
- ADB 不适合稳定点击不同 OEM picker 并做真实选择，所以 harness 只证明目标候选可达，不改变当前选择；后续生命周期仍用 shell app-op 自动化。两类证据的边界被显式拆开。

## Architecture Ownership

Architecture cell: `Android application / location delivery`
Map delta: `none`
Why: R4 只补 main manifest 产品能力声明与既有真机 harness 的用户入口门禁；不新增 Store、Queue、Router、Adapter、Dispatcher、Binding、跨进程契约或第二份状态源。

请检查 R4 diff 是否与 `Map delta: none` 一致，以及 manifest 声明是否仍位于既有 `:app` 产品边界。

## Failure-Mode Sweep Report

Pattern：自动化用开发者旁路替代产品文案承诺的用户入口。

| 扫描面 | 结论/处置 |
|---|---|
| notification permission | 既有门禁从 revoked state 驱动产品运行时弹窗，禁止 `pm grant` 假绿 |
| mock-location candidate discovery | 新门禁检查 installed manifest 并打开真实 picker，发生在任何 `cmd appops set` 前 |
| debug/release parity | 声明位于 main；Debug/Release merged manifest 与 APK 均检查 |
| release lint | 带原因 `tools:ignore`，`lintVitalRelease` 实跑通过 |
| first-start guidance | picker 可达后继续验证“选择当前千网游 → 重新打开开关”与重启清洁 |
| stop recovery guidance | 同一真实 picker 入口支持重新选择；残留 recovery 与 GNSS truth 保持通过 |

## Open Questions

### 技术 OQ

1. 请删除/变异 main manifest permission、picker 前置调用与 installed-permission 前置调用，确认结构契约分别真实变红，而不是编译失败假击杀。
2. 请独立检查 `tools:ignore="MockLocation"` 是否正当且只压制这一个产品能力声明；release APK 是否确实包含权限且 `lintVitalRelease` 通过。
3. 请用 exact remote HEAD 构建安装，在不先写 bench app-op 的前提下确认真实系统 picker 列出千网游；然后复跑完整设备链。
4. 请判断 harness 的 picker 扫描是否会误选/改变现有 mock app，或在失败时把设备留在不可恢复状态。

### 价值 OQ

无。merge 仍必须等待 co-creator 重新明确授权；本轮 review 不恢复旧授权。

## Next Action

从 GitHub 全新 clone/detached checkout PR #10 exact remote HEAD：

1. 独立运行 412 JVM、结构 8/8、Debug/Release 与 `lintVitalRelease`。
2. 运行上述 3 个 R4 变异并区分真实断言失败、编译假击杀与变异未生效。
3. moto g54 先验 permission + 真实 picker，再跑完整 harness；核对最终参考 App sole allow、真实 GNSS、Bench 无残留 service。
4. 返回 `APPROVE` 或带文件/行号的 `REQUEST CHANGES`；不改代码、不 commit、不 push、不 merge。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/f001-mock-location-main-integration/fable5-r4`
- Start Command: Android 无 web server；运行 Gradle/Python/Bash/ADB 门禁
- Ports: not applicable（不使用 3003/3004/3011/3012/4111）

## 自检证据

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/terry/Library/Android/sdk' \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease \
  :app:lintVitalRelease --rerun-tasks
# BUILD SUCCESSFUL; 412/412; 0 failure/error/skipped

python3 scripts/test_mock_provider_main_integration.py
# 8/8 OK

bash -n scripts/mock_provider_acceptance.sh
# exit 0

OBSERVE_SECONDS=1 scripts/mock_provider_acceptance.sh ZY22JHW9M4
# installed permission / real picker / first-start / restart-clean / Kyiv /
# task removal / Maps / residue recovery / restore; exit 0
```

- R4 Debug APK / JBR 21: `07b9a7c589149175c04913e595af22316addabfd3d167f384dd8d8979f8c23ef`。
- R4 Release APK / JBR 21（informational R8 samples）: `ba3a4086…f523` / `8e98b5ba…d4d5` / final rerun `4db1c3be…6a46`；不作为 exact-source artifact identity。
- Final device: reference App sole mock app；gps `identity=1000/android[GnssService]`；四包均在；Bench service absent；notification permission 恢复原值。
- 本轮没有修改 controller/orchestrator fallback；新 shell 函数线性扫描真实 Settings 页面，失败即返回并由既有 trap 恢复 app-op/provider。
- Root artifact gate：工作树与 `origin/master...HEAD` 均无仓库根目录媒体/设计工件；仓库 glob 无 `.pen`，R4 delta 无 UI 布局/文案改动。

## R4 Review Follow-up

- P2 Dozing：结构 RED 因 `wake_and_unlock_device` 缺失而失败；抽取单一 seam 后 picker 与 `open_settings` 共用，GREEN 8/8。moto g54 明确从 `mWakefulness=Dozing` 起跑，真实 picker 及完整 restore 链 exit 0。
- P3 provenance：implementation commit 已更正为可解析的 `fdcc55a7326820f139b3955dc9b56c412ef22656`；错误对象在本地/远端均不存在。

## 相关文档

- Plan: `feature-specs/2026-08-03-mock-location-main-integration.md`
- Bug: `docs/bug-report/mock-location-picker-app-missing/bug-report.md`
- Evidence: `docs/acceptance/mock-location-main-integration-evidence.md`
- PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`

[砚砚/gpt-5.6-sol🐾]

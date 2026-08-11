---
feature_ids: [F001]
topics: [android, google-play-services, fused-location, mock-location, temporal-acceptance, review]
doc_kind: review-request
created: 2026-08-04
---

# PR #10 / Issue #12 — GMS fused 时间轴稳定性复审请求

Review-Target-ID: `f001-issue-12`
Branch: `feat/mock-provider-main-integration`
PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`
Issue: `https://github.com/TERRYYYC/FakeGps-test/issues/12`
Withdrawn approved head: `2b1909c9d9e04bbe8cae4276d3478c7a292a70a6`
Current master/rebase base: `3cd9c26889b0a9b69959138e9417b497c6332696`
Issue #12 implementation: `efb93692c256b11ddfd3d1356a1e6404f3771cc0`
Awaited-task contract: `bdc1a00275fb04df6eb692a5b268b3b83dcca561`
Framework source-set contract / final code head before packet: `beb3fd5ef948c42e2dea65d932565216837888b4`

## Original Requirements

> “mock 的点位会持续一段时间，然后闪断一下回到我的实际位置，然后又会切回 mock 的点。”
> 位置伪装不能偶发泄漏真实坐标；单次快照不能代替连续体验。

- 来源：`/Users/terry/Desktop/coding/insight-f001-mock-provider-main-integration/docs/features/F001-issue-gms-fused-location-gap.md` AC 7，以及 co-creator 2026-08-04 手动验收原话。
- **请 reviewer 对照这段 operator experience 判断交付物是否真正消除了时间轴泄漏，而不只让内部 provider 快照变绿。**

co-creator 已明确授权引入官方 `play-services-location` 依赖。F001 仍保持 `in-progress`；merge gate 关闭，本 packet 只请求 exact-HEAD 独立 review。

## What

1. `AndroidMockProviderGateway` 同源接管 framework gps + network。
2. 新 `GooglePlayServicesFusedMockProviderGateway` 调用并等待 `setMockMode(true)`、每 tick `setMockLocation(Location("fused"))` 与 Stop 的 `setMockMode(false)`。
3. `CoordinatedMockProviderGateway` 把两层暴露为既有 `MockProviderGateway` 的一个事务；`MockProviderSessionController` 的 durable cleanup marker 仍是唯一 ownership truth。
4. `MockProviderSessionRunner` 把有界等待放到单线程 worker，最终状态再回 main；主线程不等待 GMS Task。
5. 新独立只读 `assert_fused_mock_stability.sh` 在真实 Maps 前台连续观察 fused；主验收 Stop 后同时证明 framework 恢复系统来源、Kyiv fused mock cache 消失。
6. 依赖固定为 Google 官方当前 setup 文档列出的 `com.google.android.gms:play-services-location:21.4.0`。

## Why

旧实现只控制 Android framework test provider。Fable5 的初始假设是补 network；作者做 gps+network 单变量实验后，network 仍为 Kyiv mock 时第 29 个 fused 样本仍泄漏真实位置，证伪该假设。稳定参考 App 的 APK 则直接使用 `FusedLocationProviderClient.setMockMode` / `setMockLocation`。

Google 官方契约明确说明：`setMockMode(true)` 会清空 FLP cache 并让 FLP 只报告 `setMockLocation` 注入；`setMockMode(false)` 退出 mock mode 并清理 mock cache；两者及 `setMockLocation` 都返回异步 Task。因此“调用过 API”不是事务完成，必须等待 terminal result。来源：

- https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
- https://developers.google.com/android/guides/setup

## Tradeoff

- 新增 Google Play Services location 依赖；无/不可用 GMS 的设备会显式失败，不降级成已知会向 Maps 泄漏真实位置的 framework-only Running。
- Fable5 的隔离构建测得 release APK 约增加 171 KB（约 +5.4%）；co-creator 在知情后授权。
- framework provider 与 GMS mock mode 是两个执行面，但不是两个状态源：产品仍只有一个开关、一个 published profile、一个 controller 与一个 durable cleanup marker。
- GMS Task 采用 5 秒有界等待；发生 timeout/failure 时 transaction 失败并 cleanup，不让 UI 先报 Running。

## Architecture Ownership

Architecture cell: `Android application / location delivery`
Map delta: `none`
Why: 官方 GMS client、协调 gateway 与 service 都在既有 `:app` 位置交付边界内；durable cleanup marker 仍是唯一 transaction owner，没有新增 Store、Queue、Router、Adapter、Dispatcher、Binding、自建外部服务或第二份持久状态。

请 reviewer 检查 diff 是否与 `Map delta: none` 一致，尤其 `FusedMockProviderGateway` 是否只是同一 controller primitive 的执行层，而非第二套 session truth。

## Root-Cause / Failure-Mode Audit

旧 `assert_provider_is_mock` 只读一次 `dumpsys location`。实测泄漏约 1/30 个样本，单次快照大约 97% 概率假绿。这个问题与 picker P0 同属“开发者代理信号替代真实用户路径”，这次多了时间轴：**持续状态承诺必须在真实消费端覆盖观察窗口，不能用一个内部瞬间替代。**

同类扫描覆盖 framework partial registration、GMS enable/publish/disable、worker/main 顺序、cleanup marker、Stop cache、network source set 与 harness 接线。没有新增 fallback、第二 store 或另一个用户开关。

## Red → Green Evidence

### 旧 exact APK 被新门禁击杀

安装并核对旧 APK SHA-256 `e1e1885ddaa847b6660548f16dfc518d3b8ca3d1a09ce4c1960377046519a636` 后，用新 standalone probe 观察真实 Maps：

```text
FUSED_REAL_LOCATION_LEAK sample=2 observed=Location[fused 50.450932,30.410255 ...]
```

这不是 gps+network 实验版，而是 co-creator 发现问题时那份 exact old binary。

### 新实现时间轴与完整链

```text
MOCK_LOCATION_PERMISSION_DECLARED package=name.caiyao.fakegps.bench
MOCK_APP_PICKER_ENTRY_VISIBLE package=name.caiyao.fakegps.bench label=千网游·测试
FIRST_START_PERMISSION_GUIDANCE_VISIBLE
FIRST_START_RESTART_CLEAN
PROVIDER_MOCK owner=name.caiyao.fakegps.bench coordinate=50.4501,30.5234
ACCEPTANCE_TASK_REMOVAL_PHASE_COMPLETE
MAPS_FOREGROUND coordinate=50.4501,30.5234
FUSED_MOCK_STABILITY_COMPLETE samples=120 interval=0.5s coordinate=50.450100,30.523400
MOCK_STABILITY_COMPLETE samples=120 interval=0.5s
APP_OP_RECOVERY_GUIDANCE_VISIBLE
FUSED_MOCK_CACHE_CLEARED observed=Location[fused 50.451003,30.410315 ...]
PROVIDER_REAL gps=GnssService network=system
RESTORE bench=deny reference=allow provider=real status=0
ACCEPTANCE_RESTORE_PHASE_COMPLETE
```

Debug APK SHA-256：`7e18cdcc9e950cf69d2da8f23d728064405e483e1d21a1f2fe982814f4a5f80c`。
Release APK sample（JBR 21，informational）：`514f4bfe7a2ef41db53abb4a1a56384c4b2563fb9f073a8b694a1cdcae79e5cc`。

## Self-check Gates

| Gate | Result |
|---|---|
| JVM | 433 / 0 failure / 0 error / 0 skipped |
| Debug + Release + `lintVitalRelease` | BUILD SUCCESSFUL |
| Structural | 12/12 |
| `bash -n` / `diff --check` | pass |
| old exact binary + new temporal gate | RED at sample 2 |
| current exact binary + full device chain | 120/120 fused mock samples; exit 0 |
| final device | reference App allow; bench deny; gps real; network system; fused real; service absent |

## Mutation Evidence — 10/10 KILLED

前两轮隔离 runner 因 SDK 坐标缺失而在测试前失败，均明确作废；下表只统计显式固定 Android SDK 后的有效运行。

| ID | Mutation | Result |
|---|---|---|
| C1 | 删除 `fused.enable()` | KILLED：5 tests / 3 failed |
| C2 | 删除 `fused.publish(config)` | KILLED：5 / 2 failed |
| C3 | cleanup 删除 `fused.disable` | KILLED：5 / 5 failed |
| C4 | provider transaction 同步跑在 caller/main | KILLED：1 / 1 failed |
| C5 | `setMockLocation` 只调用、不 await | KILLED：结构契约精确失败 |
| C6 | 主验收旁路 standalone 时间轴门禁 | KILLED：结构契约精确失败 |
| C7 | active framework source set 删除 network | KILLED：结构契约精确失败 |
| C8 | 删除 partial-start cleanup ownership 边界 | KILLED：5 / 2 failed |
| C9 | `setMockMode(true)` 只调用、不 await | KILLED：结构契约精确失败 |
| C10 | `setMockMode(false)` 只调用、不 await | KILLED：结构契约精确失败 |

C1–C4/C8 均编译成功后进入 JUnit 断言失败；C5–C7/C9–C10 是源结构性质变异，由对应 Python assertion 精确报错，不冒充 JVM mutation。

## Open Questions for Reviewer

### 技术 OQ

1. 请独立拿旧 exact APK 跑新 standalone 时间轴门禁，确认它会为真实 fused 泄漏而红，不是偶然安装/权限失败。
2. 请分别构造 framework 成功 + GMS 失败、GMS publish 成功 + framework 失败，确认 controller 不报告 Running，cleanup 两层且 marker 不被错误清除。
3. 请破坏/跳过 `setMockMode(false)` completion，确认 Stop 不能报告成功；在真机上确认 Stop 后 Kyiv fused mock cache 消失，而不只采信日志。
4. 请从 Dozing 起点复跑既有完整用户链，确认 picker、首次未授权、重启清洁、任务卡保活、app-op recovery 与 Stop 未因新 GMS 层退化。
5. 当前 base 从已审的 `6fe6915` 前进到 `3cd9c26`（PR #11 profile template）。该 master delta 不触碰 mock-provider/config authority seam，但请核对 rebase continuity，而不是继承旧 SHA verdict。

### 价值 OQ

无。新增 GMS 依赖的体积/可用性取舍已由 co-creator 明确授权；merge authority 仍未授权。

## Next Action

请从 PR #10 的 exact remote HEAD 建全新 sandbox，返回覆盖 exact SHA 的 `APPROVE` 或 `REQUEST CHANGES`。reviewer 不 commit、不 push、不 merge；merge authority 仍只属于 co-creator，且当前保持撤回。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/f001-issue-12/fable5`
- Start command: 从 PR exact remote HEAD 全新 clone；设置 `ANDROID_HOME=/Users/terry/Library/Android/sdk` 与 `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`，运行本 packet 的 Gradle/Python/Bash/ADB 门禁
- Ports: not applicable；Android project 不启动 web/api，也不使用 3003/3004/3011/3012/4111
- Device: moto g54 5G `ZY22JHW9M4`, Android 15

## Self-check Spec Compliance

- F001 truth source 新增 AC 7，状态保持 `in-progress`；本次不申请 feature close。
- INV-14 锁住真实 Maps 消费端时间轴、单一 cleanup ownership 与 Stop cache clear。
- Dogfood 必做且已执行：exact current APK 从 picker/first-start 到 120-sample Maps、app-op recovery、真实 restore 全链 exit 0；截图显示 Independence Square。
- `.pen` glob 无匹配，本轮无 UI layout/文案改动；仓库根目录媒体/设计工件在工作树与 committed diff 均为零。
- Android 仓库无 Cat Café `check-hotfix-pattern` / `check-fallback-layers` / architecture ownership pnpm scripts；人工 sweep 未发现同文件新增 ≥3 层 fallback。两个 cleanup loop 都是“每层都尝试、聚合首错”，不会降低成功标准。

## Related Documents

- Plan/spec: `feature-specs/2026-08-03-mock-location-main-integration.md`
- Evidence: `docs/acceptance/mock-location-main-integration-evidence.md`
- Root cause: `docs/bug-report/fused-real-location-flapping/bug-report.md`
- Feature truth: `/Users/terry/Desktop/coding/insight-f001-mock-provider-main-integration/docs/features/F001-issue-gms-fused-location-gap.md`

[砚砚/gpt-5.6-sol🐾]

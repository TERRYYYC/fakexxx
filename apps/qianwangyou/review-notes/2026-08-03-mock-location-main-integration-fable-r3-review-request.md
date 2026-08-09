---
feature_ids: [F001]
topics: [android, mock-location, first-use, appops, recovery, review]
doc_kind: review-request
created: 2026-08-03
---

# Review Request — F001 Mock Location 主 App 集成 / Fable R3

Review-Target-ID: `f001-mock-location-main-integration`
Branch: `feat/mock-provider-main-integration`
PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`
R2 reviewed base: `65834f713443a92dde14560a84b9d3d6b988e786`
R3 implementation commit: `5dbcfa43b17d2982772c81ee9eb2c8897f49ee94`
Reviewer: `@fable5`（只读 review + 独立验证）

> Review packet/evidence 的 carrier commit 不属于生产逻辑。请从 PR #10 remote pull head 解析本轮 exact HEAD，并以 Cat Café R3 路由消息中的 SHA 做三方核对。

## What

- 将权限恢复拆为 `SelectThisAppAndRetryStart` 与 `ReselectThisAppAndRetryStop`，不再把首次启用与真实残留压成同一动作。
- `Failed` 显式携带 `providerCleanupRequired`；首次 remove 被拒绝且系统未 mutation 时清除预写 marker，Hook + marker=false 的 `onDestroy` cleanup no-op。
- `refresh()` 显式保留既有 System Mock ownership，确保运行中权限丢失仍走清理 recovery。
- 设置页首次失败允许重新打开开关，不显示“重试停止/残留位置”；stop 失败 UI 保持 R2 行为。
- 真机 harness 新增 first-start denied → 指引 → screenshot → force-stop/restart clean，再继续 Kyiv 与 stop-residue 全链。

## Why

R2 真机证实：首次点击时首个 `removeGpsProvider()` 就因 app-op 被拒绝，系统前后都是真实 GNSS；但 R2 保留 cleanup marker 并宣称存在残留，导致一次误触在重启后继续红屏。产品的 provider-truth 原则要求恢复动作与真实 ownership 一致。

## Original Requirements

> “你这部分功能你打算怎么合入主app呢？”
> “做成一个开关，数据则是从主app的档案来获取，即开关来决定是使用hook还是mock”
> “你现在这个xxx lab app 虚拟位置不能停你发现了吗？”
> “我建议虚拟地址选择 基辅”

- 来源：co-creator direct message `0001785711887044-001396-80452593`，归档于 `review-notes/2026-08-03-mock-location-main-integration-review-request.md`。
- 请继续按“主 App / 同源档案 / 真 Stop / Kyiv”判断，不把局部测试绿等同于愿景完成。

## Tradeoff

- start 在首个 remove 前被拒绝可证明未 mutation，安全清 marker；一旦进入 replace 边界则保守认为 provider 可能部分建立，即使 best-effort cleanup 返回，也保留 transaction marker 供下次 reconciliation。
- 不解析 Android 异常字符串、不查询第二份 provider 状态缓存；动作阶段、cleanup ownership 与 recovery 均由 controller 状态表达。
- `providerCleanupRequired` 是 failure state 的事实字段，不成为第二个持久 owner；唯一 durable owner 仍是 `SpoofSettings` marker + location mode。

## Architecture Ownership

Architecture cell: `Android application / location delivery`
Map delta: `none`
Why: 只补齐既有 controller/orchestrator/UI 状态边；没有新 Store、跨进程契约、外部服务或第二份 profile 数据源。

请检查 `providerCleanupRequired` 是否确实是 failure fact 而非平行 owner，以及 `Map delta: none` 是否与 diff 一致。

## Failure-Mode Sweep Report

Pattern：失败没有同时携带动作阶段、系统 ownership 与用户下一步。

| 扫描面 | 结论/处置 |
|---|---|
| 首次 `enable/start` | start denied before mutation → start recovery，marker 清除 |
| 运行中 `refresh/start` | durable System Mock ownership → cleanup recovery，不误降级为 first-use |
| `disable/rollback` | 真实 cleanup failure 继续保留 marker + stop recovery |
| `cleanupRuntimeOnly/onDestroy` | Hook + marker=false no-op；System Mock 或 marker=true 仍 cleanup |
| Settings UI | start failure = selection + switch retry；stop failure = reselect + retry stop |
| harness | 两种 recovery 在同一真实设备流程中分别断言 provider truth、文案与重启状态 |

## Open Questions

### 技术 OQ

1. `providerCleanupRequired` 的设置边界是否覆盖 Android 调用可能“抛错但已部分 mutation”的窗口，同时不把首个 remove denial 误作 residue？
2. `cleanupRuntimeOnly()` guard 与 `refresh(providerMayAlreadyExist=true)` 是否完整守住 onDestroy / running-refresh sibling paths？
3. 请复跑 R2 的 9 个变异，并新增 4 个：合并 recovery、跳过 marker clear、删除 Hook cleanup guard、丢失 refresh ownership context；是否全部是编译成功后的断言失败？
4. 真机 first-start 图/节点与 force-stop restart 是否证明无残留、无持久假红；原 app-op residue recovery 是否仍 exit 0？

### 价值 OQ

无。merge 仍必须等待 co-creator 明确确认。

## Next Action

从 GitHub 全新 clone/detached checkout PR #10 exact remote HEAD：

1. 独立运行 412 JVM、结构 6/6、Debug/Release 与 `lintVitalRelease`。
2. 复跑 R2 9 个 + R3 4 个变异，区分断言失败与编译假击杀。
3. moto g54 完整运行 harness，重点核对 `FIRST_START_PERMISSION_GUIDANCE_VISIBLE`、`FIRST_START_RESTART_CLEAN`、真实残留 recovery 与最终 restore。
4. 返回 `APPROVE` 或带文件/行号的 `REQUEST CHANGES`；不改代码、不 commit、不 push、不 merge。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/f001-mock-location-main-integration/fable5-r3`
- Start Command: Android 无 web server；运行下方 Gradle/Python/Bash/ADB 命令
- Ports: not applicable（不使用 3003/3004/3011/3012/4111）

## 自检证据

### Spec 合规

- 原始四项要求仍由主 App、同源档案、真 Stop 与 Kyiv 覆盖。
- R3 将 INV-3/INV-10 泛化为 INV-12：恢复动作必须与 provider ownership 一致。
- Architecture cell / map delta 与 R2 不变；无 `.pen`，新 UI 真机截图已持久化到 backup evidence 目录。
- Root artifact gate：Git 工作树与 PR 已提交差异无根目录媒体/设计工件。

### 测试结果

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/terry/Library/Android/sdk' \
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease \
  :app:lintVitalRelease --rerun-tasks
# BUILD SUCCESSFUL; 412/412; 0 failure/error/skipped

python3 scripts/test_mock_provider_main_integration.py
# 6/6 OK

bash -n scripts/mock_provider_acceptance.sh
# exit 0

OBSERVE_SECONDS=1 \
FIRST_START_SCREENSHOT_PATH=/Users/terry/Desktop/coding/backup/mock-location-v2-2026-08-03/evidence/settings-main-first-start-permission.png \
scripts/mock_provider_acceptance.sh ZY22JHW9M4
# first-start / restart-clean / Kyiv / task removal / Maps / residue recovery / restore; exit 0
```

- Author Debug APK (Android Studio JBR 21.0.10): `0aa312f2e5fe9b6ce6ef67e17e1e90a6dadd540fcb2ac4ef1cf69d14396f9cbc`
- Independent reviewer Debug APK (Homebrew OpenJDK 17.0.20): `83e725aac7615f2d646b34fd920ecce8fbab5ca70cf6066a206bf891ad62ebc4`
- First-start screenshot: `cd9fecb9326a6743065a903e174353571f380802642e4f2ce6ad0903e05519c9`
- Final device: reference App sole mock app; gps `identity=1000/android[GnssService]`; four packages installed; Bench service absent; notification permission restored.
- Fallback/hotfix automation scripts are absent in this Android repo and the workspace governance repo; manual diff audit found no three-layer fallback. Controller catch has two explicit mutually exclusive recovery branches.

### 相关文档

- Plan: `feature-specs/2026-08-03-mock-location-main-integration.md`
- Bug: `docs/bug-report/mock-provider-start-permission-false-residue/bug-report.md`
- Build provenance bug: `docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md`
- Evidence: `docs/acceptance/mock-location-main-integration-evidence.md`
- PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`

[砚砚/gpt-5.6-sol🐾]

---
feature_ids: [F001]
topics: [android, mock-location, main-app, appops, recovery, review]
doc_kind: review-request
created: 2026-08-03
---

# Review Request — F001 Mock Location 主 App 集成 / Fable R2

Review-Target-ID: `f001-mock-location-main-integration`
Branch: `feat/mock-provider-main-integration`
PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`
Base: `ff48173f8bd531571d544293f317f999aa601469`
Remediation content HEAD: `7d95ff67f7f501b1bf2284280dee5088e7a03320`
Reviewer: `@fable5`（只读 review + 独立验证）

## Authorization Boundary

- Fable5 只读检查、独立 checkout、构建、变异验证与 moto g54 验收；不改代码、不 commit、不 push。
- finding 返回 Sol；新 delta 必须重新 exact-HEAD review。
- **APPROVE 不等于 merge 授权。任何 merge 都必须等 co-creator 明确确认。**

## What

- 把 mock app-op 丢失从平台字符串提升为结构化 recovery：设置页明确指导“重新选择当前千网游 → 重试停止”。
- Android 13+ 由产品请求通知权限；验收从 revoked state 驱动真实系统弹窗，不再 `pm grant` 代授。
- System Mock 的可选海拔从同一生效档案读取，删除 gateway 的 Kyiv `179.0` 硬编码。
- 把 Hook passthrough、fixed clocks、controller 生命周期、orchestrator 失败/恢复矩阵补成可击杀 reviewer 原始变异的测试。
- 修复验收的 DocumentsUI task 污染与 Maps 瞬态/locale recenter 控件依赖，并加入 app-op 改选恢复阶段。

## Why

Fable 在 `e9274cd` 真机复现：System Mock 运行中改选模拟位置 App 后，原 owner 失去 `removeTestProvider` 权限，而 system_server 中的 GPS test provider 仍可残留。App 不能替用户重新获得 app-op；正确终态是如实保留失败/恢复标记，让唯一有效恢复路径可发现、可执行、可验收。

## Original Requirements

> “你这部分功能你打算怎么合入主app呢？”
> “做成一个开关，数据则是从主app的档案来获取，即开关来决定是使用hook还是mock”
> “你现在这个xxx lab app 虚拟位置不能停你发现了吗？”
> “我建议虚拟地址选择 基辅”

- 来源：co-creator direct message `0001785711887044-001396-80452593`；归档于 `review-notes/2026-08-03-mock-location-main-integration-review-request.md`。
- 请对照以上原话判断：交付物是否是主 App 用户功能、是否真正解决“停不掉”而非只修正常路径。

## Tradeoff

- Android 不允许 App 自行成为“模拟位置信息应用”；权限丢失后不 root、不静默改 app-op、不伪报成功，必须由用户重新选择当前千网游。
- UI 保留平台失败在状态中，同时新增可操作说明；cleanup marker 只在真实恢复 GNSS 后清除。
- Release SHA-256 只作 author artifact 记录，不再宣称 R8 产物逐位可复现；debug exact-source hash 仍可独立复算。
- API 24–30 legacy provider 注册仍只有代码/单测覆盖；设备为 Android 15/API 35。

## Architecture Ownership

Architecture cell: `Android application / location delivery`
Map delta: `none`
Why: provider/service/UI recovery 与权限 policy 都在既有 `:app` 位置交付边界内；没有第二份 profile/store、外部服务或跨进程契约。

请检查 diff 是否与 `Map delta: none` 一致，尤其确认 `SystemMockEnableAction` 只是可测试的设置→service 顺序 seam，而非平行状态 owner。

## Failure-Mode Sweep Report

Pattern: System Mock 失败/恢复路径只断言类型或最终结果，不守 side effect 与恢复动作。

| 扫描面 | 处置 |
|---|---|
| enable / disable / refresh / rollback / runtime cleanup | 21 个 orchestrator tests 钉住 state、marker、mode、provider side effect、message 与顺序 |
| session start / tick / repeated start / repeated stop | 恢复直接行为测试与 emittedCount 断言 |
| effective profile fields | 经纬度、精度、可选海拔分别断言 exact Invalid reason |
| settings enable dispatch | `SystemMockEnableActionTest` 钉住 sync → read → state → start 顺序和两个失败出口 |
| Hook mode | 逐字段值断言，替代引用自相等空测试 |
| harness | notification prompt、task removal、Maps、app-op residue/guidance/retry、final GNSS 与 restore |

## Open Questions

### 技术 OQ

1. `SecurityException` → `SelectThisAppAsMockLocation` 的 domain 映射是否位于正确 gateway/controller 边界，且 rollback 不会丢 recovery？
2. 请重放五个原始变异：删 stale-marker disjunct、删 refresh mode guard、删 `cleanupRuntimeOnly.stop()`、破坏 Hook passthrough、忽略 injected clock；它们是否全部被新测试击杀？
3. Android 13+ notification request 是否走真实产品路径，拒绝时是否不会启动 provider？
4. 真机 app-op 改选后，是否同时看到残留 provider、恢复指引，并在重新选择当前 App 后由“重试停止”恢复 GnssService？

### 价值 OQ

无。merge 权限边界已由 co-creator 明确，不在本 review 内重问。

## Next Action

请从 PR #10 解析最新 remote HEAD，并确认它包含 `7d95ff67...` 后做 R2 code review + 变异验证 + moto g54 全链复跑，返回 `APPROVE` 或 `REQUEST CHANGES`。不要复用 author worktree，不要 merge。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/f001-mock-location-main-integration/fable5`
- Bootstrap: 从 GitHub `pull/10/head` 拉取，核对最新 remote HEAD 与作者 handoff 消息一致后 detached checkout
- Start Command: Android 无 web server；直接使用下列 Gradle/ADB 命令
- Ports: not applicable（不使用 3003/3004/3011/3012/4111）

## 自检证据

### Spec 合规

- 原始四项纠正已逐项映射：主 App、档案同源开关、真实 Stop/recovery、Kyiv。
- Spec: `feature-specs/2026-08-03-mock-location-main-integration.md`
- Bug root cause: `docs/bug-report/mock-provider-stop-after-appop-reassignment/bug-report.md`
- Quality gate/evidence: `docs/acceptance/mock-location-main-integration-evidence.md`
- Design：仓库无 `.pen`；真实恢复态截图与 Maps 截图位于备份 evidence 目录，未进入 Git。
- Root artifact gate：工作树与 `origin/master...HEAD` 均无根目录媒体/设计工件。

### 测试结果

```bash
ANDROID_HOME=/Users/terry/Library/Android/sdk \
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home \
./gradlew testDebugUnitTest assembleDebug assembleRelease --rerun-tasks
# BUILD SUCCESSFUL; 407 tests / 0 failure / 0 error / 0 skipped

python3 scripts/test_mock_provider_main_integration.py
# 6/6 OK

bash -n scripts/mock_provider_acceptance.sh
# exit 0

OBSERVE_SECONDS=3 scripts/mock_provider_acceptance.sh ZY22JHW9M4
# notification / active / task-removal / Maps / app-op recovery / restore; exit 0
```

- Debug APK SHA-256: `a9cd6361a50270ace6a35ac99897c072cef269edd3b36767c2f61f343eafdaed`
- `lintDebug`: inherited 20 errors / 158 warnings；20 errors 全在未改动的 `HookProbe.kt`、`MainActivity.java`、`TempDao.java`、`strings.xml`；本 diff 零 lint error，release `lintVital` 通过。
- Device final truth: sole mock app `com.hopefactory2021.fakegpslocation`；gps `identity=1000/android[GnssService]`；Bench service 无残留。

## Insight Truth Sync

- Docs branch: `docs/f001-mock-provider-main-integration`
- Docs HEAD: `1bd3cd36e9efef1ca08fdbe760e524128de11ff4`
- Branch truth is `status: in-progress` and points at PR exact HEAD `7d95ff67...`。
- Insight main 暂未 merge：这是 co-creator 明令保留的 merge gate，不是遗漏。

[砚砚/gpt-5.6-sol🐾]

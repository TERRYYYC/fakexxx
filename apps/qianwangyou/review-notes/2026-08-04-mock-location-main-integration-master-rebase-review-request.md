---
feature_ids: [F001]
topics: [android, mock-location, rebase, selected-profile, provider-authority, review]
doc_kind: review-request
created: 2026-08-04
---

# PR #10 latest-master continuity review request

Review-Target-ID: `f001`
Branch: `feat/mock-provider-main-integration`
PR: `https://github.com/TERRYYYC/FakeGps-test/pull/10`
Previously approved head: `a062afc8ad4478d9bac42e96c363b982a22a7218`
Latest master base: `6fe6915931408dff6e795c5a433c4538a21a118d`
Production integration commit: `50553535ed0a55084c428c6666fdcb380919b614`
Code/evidence head before this packet: `8be0855c26934813db224555f0c5b9a6fd53f545`
Authority exact-value remediation implementation: `8c92f65727568f71f02862b5ab52849c3932c536`

## Original Requirements

Truth source: `/Users/terry/Desktop/coding/insight-f001-mock-provider-main-integration/docs/features/F001-issue-gms-fused-location-gap.md`

> System Mock 必须进入主 App；切换只改变位置注入方式，其他 Hook 数据继续工作。
> System Mock 使用当前生效档案，不维护自己的坐标或档案状态。
> 切回 Hook 后必须以真实 gps provider 身份证明 Stop。
> release/debug 必须在 Android 真实模拟位置 App 选择器中可达，shell app-op 不能代替用户入口。

请 reviewer 继续以这四条 operator experience 判断 rebase 结果，不把“冲突消失/测试编译”当成产品完成。

## What

review 结束后 `origin/master` 新增 PR #9“保存哪条档案就发布哪条”的 transport 语义，并与 PR #10 在两个文件相交。本分支已 rebase 到最新 master：

1. `ConfigPrefsSync.kt` 同时保留 master 的显式/active profile id、last-good 与 payload/pointer 原子提交，以及 System Mock 的 schema v4、`locationDeliveryMode`。
2. `AppInfoProvider.java` 保留 feature 的 `ProviderAuthority.forApplicationId(BuildConfig.APPLICATION_ID)`，覆盖 master 的动态 applicationId 意图并继续支持 `.bench`。
3. master 新 `AppInfoProviderAuthorityTest` 原先会在 local JVM 初始化 Android `Uri` 并崩溃；测试改为读取 class bytes，锁住实际 production wiring，纯 helper 精确输出仍由 `ProviderAuthorityTest` 覆盖。
4. spec/evidence 更新为 selected-profile 真相，记录 production integration commit、418/418、APK hashes 与真机复跑。

## Why

旧 APPROVE 覆盖 `a062afc8`，不覆盖 master 后来落地的行为与冲突解法。直接合入会让 reviewer 结论陈旧；只选择一侧又会分别丢失“正确档案”或“正确 delivery mode”。因此必须先做语义合成、最新-master 全门禁与真机复跑，再请求连续性 review。

## Tradeoff

- 采用 rebase，保持 PR 历史线性，但需要 `force-with-lease` 更新 feature branch；旧 exact head 已在 evidence/packet 永久保留。
- 没给 `AppInfoProvider` 引入 Robolectric，也没开启全局 Android stub 默认值；class-byte contract 更轻且不会把 Android runtime 误用静默变绿，但它只验证编译 wiring，真实 manifest/provider 行为仍由 APK inspection 与 moto g54 验收承担。
- 没重跑 R3/R4 的全部 20 个 reviewer mutations，因为 controller/orchestrator/manifest/harness 生产语义未在冲突中人工改写；但重新跑了全部 418 JVM、8 个结构契约、两种 APK、release vital lint 与完整设备链。

## Architecture Ownership

Architecture cell: `Android application / location delivery`
Map delta: `none`
Why: rebase 只把 master 的 profile-selection transport 语义与既有 System Mock writer/provider 接线合成，没有新增 Store、Queue、Router、Adapter、Dispatcher、Binding、外部服务或第二个 System Mock 状态源。

请检查 `Map delta: none` 是否仍与冲突解法一致，尤其 active profile id 是否只是既有 transport routing metadata，而非 System Mock 新所有者。

## Failure-Mode Sweep Report

Pattern：并行 PR 在同一 transport seam 落地，机械选 ours/theirs 会让其中一套不变量静默丢失。

| 扫描面 | 结果 |
|---|---|
| `ConfigPrefsSync` schema / selected profile | 两套语义都保留；master selection contract + feature transport tests 同时通过 |
| repository / legacy editor callers | 继承 master 的显式 saved profile id；System Mock 仍只读 published payload |
| provider authority / debug suffix | 统一走 pure helper；debug/release APK identity 与 permission 实查 |
| local JVM Android stub | 原测试先红并定位到 `Uri.parse`；改为 bytecode wiring contract 后绿 |
| device path | normal repository preparation 发布 Kyiv；gps/fused/Maps 与 restore 全链 exit 0 |

## Open Questions

### 技术 OQ

1. 请独立确认合并后的 `ConfigPrefsSync` 没有出现 payload 是 schema v4、active pointer 却来自另一条 profile 的窗口；显式目标暂时缺失时是否仍保留 last-good。
2. 请把 `AppInfoProvider` 改回直接字符串拼接或让它不再引用 `ProviderAuthority`，确认新的 bytecode wiring test 会真实变红；不要只看当前绿灯。
3. 请核对 production integration commit `50553535…` 到 review HEAD 的非文档 delta只有 JVM test harness，真机 APK hash 应绑定前者而不是把 docs commit 冒充产物输入。

### 价值 OQ

无。merge 授权仍在 co-creator 手里且当前为撤回状态；本 review 只恢复或拒绝 reviewer coverage。

## Next Action

请从 PR #10 的 exact remote HEAD 建全新 detached sandbox，重点复核上述两个交叉 seam，并返回覆盖 exact SHA 的 `APPROVE` 或 `REQUEST CHANGES`。若放行，下一步由 author 重新递 merge Decision Packet 给 co-creator；reviewer 不 commit、不 push、不 merge。

## Continuity Finding Remediation

Fable5 在 rebase review 构造 `AUTHORITY = ProviderAuthority.forApplicationId(...) + ".x"`，证明第一次 bytecode-only 修复弱化了 master 的精确值契约：418 tests 全绿。作者独立重放确认该变异存活后，做了同类审计并修复：

- 精确当前 variant 值移到纯 JVM `ProviderAuthority.AUTHORITY`；manifest 模板值可用普通 `assertEquals` 验证，不初始化 Android `Uri`。
- `AppInfoProvider` 的 URI/`UriMatcher` 与 `ConfigPrefsSync` 的两个 publisher URI 共用该值，分别保留 bytecode wiring 守卫。
- RED：暂时附加 `.x`，定向测试编译成功并以 `ComparisonFailure` 失败。
- GREEN：420/420；Debug/Release/`lintVitalRelease` 成功；结构 8/8、`bash -n`、`diff --check` 通过。
- MA3 final-shape replay：重新附加 `.x`，4 tests / 1 failed，真实精确值断言失败。
- moto g54：用 remediation debug APK 跑完整 picker → Kyiv → task removal → Maps → app-op recovery → GNSS/reference restore，exit 0。

请 reviewer 只复核这一处 exact-value contract 与 MA3；之前已放行的连续性范围无生产 delta，不要求重跑完整 review。

## Self-check Evidence

```text
JBR 21 full gate: BUILD SUCCESSFUL; 420 tests; 0 failure/error/skipped
Debug APK: e1e1885ddaa847b6660548f16dfc518d3b8ca3d1a09ce4c1960377046519a636
Release APK sample: 9ee0a5a39849979d61b2c743d48d8988495886406329f67d0162da82e84c9445
Structural contracts: 8/8
bash -n / git diff --check: pass
moto g54: picker → first-start → restart-clean → selected Kyiv → task removal → Maps → app-op recovery → GNSS/reference restore; exit 0
Final device: bench deny; reference allow; gps identity=1000/android[GnssService]; Bench service absent
```

Design/artifact gate：仓库无 `.pen`；本 rebase 没有 UI layout 文案改动；feature worktree 与 `origin/master...HEAD` 均无根目录媒体/设计工件。

## Review Sandbox

- Suggested path: `/tmp/cat-cafe-review/f001/fable5-rebase`
- Start command: Android 项目，无 web server；运行 Gradle/Python/Bash/ADB 门禁
- Ports: not applicable（不使用 3003/3004/3011/3012/4111）

[砚砚/gpt-5.6-sol🐾]

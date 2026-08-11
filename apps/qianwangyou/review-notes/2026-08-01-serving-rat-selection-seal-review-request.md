---
feature_ids:
  - cellular-hook-verification
  - profile-unavailable-state
topics:
  - review
  - android
  - xposed
  - rat-selection
doc_kind: review-request
created: 2026-08-01
---

# Review Request: PR #3 serving-RAT selection final seal

Review-Target-ID: feat-verify-ux
Branch: feat/verify-ux
Implementation SHA: `7e35190beafc5bfd2bebf74510ef4bc657eb2c86`
State-model SHA: `ccad022d6355253dbbb2c21e957564ad801058aa`
Rejected predecessor: `0fd9398e3862ddd920a94e71f7f5bd3a067fbbfa`

## What

关闭 cloud review `3694209276` 与深深旧 HEAD seal verdict 的 P1：把 shared identity
projection、RAT-specific serving construction、CellLocation transformation 和 neighbor-list mutation
拆成独立状态。GSM/WCDMA/LTE/NR 只能由各自独有 identity 字段构造；MCC/MNC/LAC/CID、
signal-only 与 unavailable-only 不再选择 RAT。四条 cell-list delivery path、subscription topology、
builder 与 real-cell preserve 逻辑全部消费 canonical decision。

同时修复 state-table sibling edge：仅配置 `neighbor_cells_json` 时保留 Android 的真实 serving
对象及注册态，只替换 neighbor；明确重建 serving RAT 时，保留下来的旧 serving 会进入 bypass 并
降为 `registered=false`。

## Why

旧 `hasGsmCell()` 把公共 PLMN/area 字段当成 GSM 存在证据。LTE、NR 或 WCDMA profile 因而先
构造额外 GSM，且全局 `isRegistered()` 把两个对象都标成 serving；consumer 取第一个 registered
cell 时会看不到目标 RAT。连续三轮 predicate finding 说明根因是缺少 serving-RAT 状态机，而非
单个条件遗漏。

## Original Requirements

> One profile decision must be observed consistently through Android public surfaces.
> Shared identity fields are projections, not evidence that a specific RAT exists.
> Construct exactly the explicitly selected RAT groups; preserve Android topology when none is selected.

- 来源：`feature-specs/2026-08-01-carrier-surface-consistency.md` 与
  `feature-specs/2026-07-27-cellular-hook-verification.md` 的 Serving-RAT state machine
- 请对照以上 operator experience 判断实现是否消除了矛盾的多 serving RAT，同时保留 passthrough。

## Tradeoff

- 未从 `network_type` 或真实 baseline 猜测用户想构造的 RAT：两者都不是该字段组的明确授权；
- 未新增持久化 RAT selector：会扩大 profile schema 与迁移范围；
- 未把 shared-only profile 退化为 no-op：它仍通过既有 getter hooks 投影到框架 serving identity；
- 选择平铺的 RAT-specific set-membership predicates；Snapshot 中的 `||` 是状态表的字段并集，
  不是顺序恢复 fallback，未新增多层容错链。

## Architecture Ownership

Architecture cell: profile config → `MainHook.CURRENT` Snapshot → Android telephony public surfaces
Map delta: none
Why: 只拆分既有 projection cell 内的 topology decision；没有新增 lifecycle owner、store、queue、router、adapter、dispatcher 或 binding。

请 reviewer 检查：

- diff 是否与 `Map delta: none` 一致；
- `MainHook.CURRENT` 是否仍是唯一运行时配置 owner；
- 所有 cell-list delivery 与 subscription topology surface 是否使用同一 canonical predicate；
- neighbor-only 与 shared-only 是否保持框架 serving topology。

## Failure-Mode Sweep Report

Pattern: “共享/跨 RAT 字段被误用为 serving topology selector”

| 扫描对象 | 数量 | 处置 |
|---|---:|---|
| cell-list delivery path | 4 | 全部使用 `hasCellListMutationDecision()`；无 mutation 时登记真实 neighbor bypass |
| subscription/phone topology guard | 7 | 全部使用 `hasCellReconstructionDecision()` |
| serving builder branch | 4 RAT | 每个分支只使用对应 RAT-specific predicate |
| preserved real-cell branch | 4 RAT | 同 RAT replacement + reconstruction-aware bypass/registration |
| shared identity fields | 4 (`mcc/mnc/lac/cid`) | projection only，不进入任何 RAT construction predicate |
| unavailable-only / signal-only | 2 状态类 | 不构造 identity object |
| `neighbor_cells_json` only | 1 状态类 | 只改变 neighbors，保留真实 serving |
| 旧 predicate production 引用 | 4 名称 | 0 个残留 |

## Open Questions

### 技术 OQ

1. shared-only + LTE、shared area + WCDMA、explicit multiple RAT、unavailable-only、signal-only 与
   neighbor-only 的状态转移是否与 spec 一致；
2. explicit reconstruction 时把未替换的真实 serving 注册为 bypass 是否可靠地降为 neighbor，避免
   两个 registered serving；
3. passthrough list 的真实 neighbor census 是否足以阻止全局 serving getters 污染邻区；
4. 请以最终 remote HEAD 独立复跑门禁，并对 `0fd9398..HEAD` 做 sibling sweep；发现任何 P1/P2
   请给 production call chain。

### 价值 OQ

无。

## Next Action

请深深在 detached、read-only sandbox 对 PR #3 的精确 remote HEAD 做最终有状态 seal review。
上轮 verdict `REQUEST_CHANGES` 是正确的旧 HEAD 证据，不作为当前放行。若当前 HEAD 0 P1/P2，
请明确 `APPROVE (0 P1 / 0 P2)`、写全 SHA 与独立门禁结果；请勿进行任何设备写入。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/feat-verify-ux/deepseek-flash`
- Start Command: Android 项目无需 dev server；detached checkout 后运行下方命令
- Ports: N/A

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin \
  assembleDebug assembleRelease lintVitalRelease --rerun-tasks
python3 -m unittest discover -s scripts -p 'test_*.py'
bash -n scripts/test-hook.sh
git diff --check
```

## 自检证据

### Spec 合规

- INV-RAT-1..7 已固化在 canonical spec，并逐项映射 production guard；
- shared identity、unavailable、signal 与 neighbor decision 均不凭歧义选择 serving RAT；
- Architecture Map delta 为 none；根目录媒体/设计工件两道扫描为空；无前端 diff；
- 本轮没有设备写入。Xposed runtime dogfood 被安全边界阻止，因此不宣称 runtime feature-complete。

### 测试结果

```text
Red 1: RAT-specific production predicates missing -> compile failed (26 symbols)
Red 2: neighbor-only topology predicates missing -> compile failed (4 symbols)
Targeted Green: CellRatSelection + UnavailableState + CellBaselineFailSafe + CarrierSurfaceCoverage all green
Full JVM: 252 tests, 0 failures, 0 errors, 0 skipped
Gradle: 109/109 tasks executed; Debug + androidTest compile + Release/R8 + lintVital SUCCESS
Python: 24 tests OK
bash -n scripts/test-hook.sh: exit 0
git diff --check: exit 0
lintDebug: 20 errors / 158 warnings, identical existing baseline; changed files 0 errors
```

### 相关文档

- Canonical state machine: `feature-specs/2026-07-27-cellular-hook-verification.md`
- Carrier invariant: `feature-specs/2026-08-01-carrier-surface-consistency.md`
- Root-cause report: `docs/bug-report/shared-identity-rat-selection/bug-report.md`
- Rejecting verdict: `review-notes/2026-08-01-gsm-cell-location-seal-review-deepseek-flash.md`
- Previous seal request: `review-notes/2026-08-01-gsm-cell-location-seal-review-request.md`

[砚砚/gpt-5.6-sol🐾]

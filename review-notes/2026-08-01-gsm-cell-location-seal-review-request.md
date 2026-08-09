---
feature_ids:
  - profile-unavailable-state
  - cellular-hook-verification
topics:
  - review
  - android
  - xposed
  - gsm-cell-location
doc_kind: review-request
created: 2026-08-01
---

# Review Request: PR #3 GSM CellLocation unavailable 封口复审

Review-Target-ID: feat-verify-ux
Branch: feat/verify-ux
Implementation SHA: `29689c345eac65e5dc896635c8c771a9930c4d69`
Previously approved SHA: `38888d1d5ddce02090cfb8f220bb0ccfdc3140ee`

## What

处理 PR #3 当前 cloud review 的 P2：`lac/cid/psc` 仅选择 `--` 时，三个既有
`GsmCellLocation` surface 被 `hasGsmCell()` 提前 return，surface-specific `-1` resolver
不可达。新增 `hasGsmCellLocationDecision()`，只让
`TelephonyManager.getCellLocation()`、`PhoneStateListener`、`TelephonyCallback` 三个已有对象
路径使用它；CellInfo 构建、phone-count 与 registered-cell 路径继续使用 `hasGsmCell()`。

本次 seal review 还应覆盖深深上次批准 `38888d1` 后的三个 author commit：

- `c91c59e`：location 同组派生字段使用 `GROUP_DERIVED`，不再误报 passthrough；
- `7cc329d`：verification coercion 按 FieldType 收紧，TEXT 不再借 boolean/numeric 等价假绿；
- `29689c3`：unavailable-only GSM CellLocation 激活缺口。

## Why

“是否允许重建一个新的 CellInfo RAT”和“是否要变换 Android 已经返回的 CellLocation”是两个
不同决策。复用同一谓词会让 unavailable-only 选择泄漏真实 LAC/CID/PSC；粗暴扩大旧谓词又会
凭 unavailable 造新 RAT。两种对象生命周期必须分开。

## Original Requirements

> “需要对档案添加一个数据类型，即为空、不上报的场景，我建议为 `--`。”
> blank remains passthrough, `--` forces the public API's native unavailable result, and a
> concrete value remains spoofing. `GsmCellLocation` 的 `lac/cid` unavailable 表示为 `-1`。

- 来源：`feature-specs/2026-07-30-profile-unavailable-state.md`
- 请对照上面的 operator experience 判断现有 CellLocation 是否真正执行了 `--` 决策，同时
  没有把 unavailable 误当成构造新 RAT 的授权。

## Tradeoff

- 放弃扩大 `hasGsmCell()`：会破坏“unavailable-only 不制造 CellInfo”的已锁定不变量；
- 放弃在三个匿名 hook 里复制 `lac/cid/psc` 条件：会产生三份易漂移的 surface contract；
- 选择一个 Snapshot surface predicate + 一个 HookUtils 共享 guard，保持决策与安装点都可测；
- 没有尝试在缺少真实 CID/LAC baseline 时强行构造对象；现有 fail-safe passthrough 仍保留。

## Architecture Ownership

Architecture cell: profile persistence → flat config transport → hook snapshot → Android public surfaces
Map delta: none
Why: 只拆分现有 projection cell 内的对象激活语义，不新增 store、queue、router、adapter、dispatcher、binding 或生命周期 owner。

请 reviewer 检查：

- diff 是否与 `Map delta: none` 一致；
- `MainHook.CURRENT` 是否仍是唯一运行时配置 owner；
- 是否意外让 unavailable-only profile 激活 CellInfo 重建、phone-count 或 registered-cell 改写。

## Failure-Mode Sweep Report

Pattern: “用新对象重建 predicate 守卫已有对象 surface”

| 扫描对象 | 数量 | 处置 |
|---|---:|---|
| `spoofedGsmLocationOrPassthrough()` 的生产调用点 | 3 | 三处全部改用共享 surface guard |
| CellInfo/list/phone-count/registered-cell 的 `hasGsmCell()` 调用点 | 其余全部 | N/A，刻意保持原谓词，防止 unavailable-only 造 RAT |
| `GsmCellLocation` 字段 | 3 (`lac/cid/psc`) | configured 与 unavailable decision 均纳入新 predicate |
| 空 profile | 1 | N/A，保持不激活 |

## Open Questions

### 技术 OQ

1. `hasGsmCellLocationDecision()` 是否完整覆盖 `lac/cid/psc`，同时没有把 MCC/MNC/ARFCN/BSIC
   误当成 CellLocation surface 决策；
2. 三个生产调用点是否全部链接新 guard，且 CellInfo reconstruction 无 scope 泄漏；
3. unavailable-only 与 configured-only PSC 的测试是否足以锁住这一边界；
4. 请对 `38888d1..review HEAD` 做一次 sibling sweep，确认后续三个 author commit 没引入新的 P1/P2。

### 价值 OQ

无。

## Next Action

请深深在 detached、read-only sandbox 对 PR #3 的精确 remote HEAD 做最终有状态复审。独立复跑
下方门禁；发现 P1/P2 请给运行时路径，无 P1/P2 时明确 `APPROVE (0 P1 / 0 P2)` 并把完整 SHA
写入 verdict。请勿做任何设备写入。

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

- `--` 仍是与 typed value 正交的 unavailable decision；
- `GsmCellLocation` 使用 surface-native `-1`，CellIdentity 仍使用自己的 unknown contract；
- unavailable-only 不激活 CellInfo reconstruction；
- 三个已有 CellLocation delivery paths 共用同一 predicate；
- Architecture Map delta 为 none；根目录媒体/设计工件两道扫描均为空。

### 测试结果

```text
Red: hasGsmCellLocationDecision() missing -> compile failed (3 symbols)
Targeted Green: 22 tests, 0 failures
Full JVM: 243 tests, 0 failures, 0 errors, 0 skipped
Gradle: 109/109 tasks executed; Debug + androidTest compile + Release/R8 + lintVital SUCCESS
Python: 24 tests OK
bash -n scripts/test-hook.sh: exit 0
git diff --check: exit 0
lintDebug: 20 errors / 158 warnings, identical existing baseline; changed files 0 errors
```

UI/browser：本次增量不改 UI；Android runtime/UI 复跑需要设备写入，而当前明确禁止安装 APK、
修改 LSPosed、运行 instrumentation 或改用户 profile。故本轮只提供静态/JVM/build 证据，不宣称
runtime feature-complete。

### 相关文档

- Original requirement: `feature-specs/2026-07-30-profile-unavailable-state.md`
- Verification contract: `feature-specs/2026-07-27-cellular-hook-verification.md`
- Carrier surface contract: `feature-specs/2026-08-01-carrier-surface-consistency.md`
- Root-cause report: `docs/bug-report/gsm-cell-location-unavailable-activation/bug-report.md`
- Prior review package: `review-notes/2026-08-01-unavailable-carrier-surface-review-request.md`

[砚砚/gpt-5.6-sol🐾]

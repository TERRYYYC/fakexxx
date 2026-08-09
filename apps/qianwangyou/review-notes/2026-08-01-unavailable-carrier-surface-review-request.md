---
feature_ids:
  - profile-unavailable-state
  - cellular-hook-verification
topics:
  - review
  - android
  - xposed
  - carrier
doc_kind: review-request
created: 2026-08-01
---

# Review Request: `--` 修复闭环与运营商全 surface 一致投影

Review-Target-ID: feat-verify-ux
Branch: feat/verify-ux
Implementation SHA: `50628e927767cc845281d56881435e036ab30e01`

## What

闭环 PR #3 在旧 HEAD `5dc5b375` 的 unavailable-state review findings，并把 `operator_name`、
`operator_numeric`、`is_roaming` 投影到 TelephonyManager、CellIdentity、ServiceState、
NetworkRegistrationInfo 及其回调对象。同步补齐 Room 真迁移测试、发布/恢复失败语义、
surface-aware unavailable、真实 baseline 绕过、API 28–35 probe 与确定性 acceptance matrix。

## Why

档案的“透传 / 不上报 / 具体值”必须跨持久化、transport、hook 和 Android public surface
保持同一个决定；运营商配置也不能只改 TelephonyManager，而让 state/identity 对象继续泄露
真实网络。验证链必须能区分真实值、伪值、平台 unknown 与同值巧合，否则绿色结果本身不可信。

## Original Requirements

> “需要对档案添加一个数据类型，即为空、不上报的场景，我建议为 `--`。”
> Prove that every configured cellular identity, signal, carrier and service-state field reaches
> the public Android API observed by an app, without modifying the user's saved profiles.

- 来源：`feature-specs/2026-07-30-profile-unavailable-state.md`、
  `feature-specs/2026-07-27-cellular-hook-verification.md`
- 请对照上面的 operator experience 判断三态、carrier 多 surface 与验证证据是否真正闭环。

## Tradeoff

- 不 hook Cellular-Pro 私有/native/modem 路径；public Android API 是本 feature 的责任边界；
- baseline 的异步 cell refresh 不跨 ThreadLocal 传播伪 bypass token：guarded sync 为空时诚实返回
  无 baseline，避免把异步伪值冒充真实值；非 self-hooked 路径仍保留 fresh request；
- API 24–28 没有 `DATA_UNKNOWN`，`data_state --` 使用旧公共契约可识别的 disconnected `0`，
  API 29+ 使用 unknown `-1`；
- 未隐式把 `operator_numeric` 拆成 MCC/MNC，保留档案字段独立可编辑语义。

## Architecture Ownership

Architecture cell: profile persistence → flat config transport → hook snapshot → Android public surfaces
Map delta: none
Why: 扩展现有 projection/verification cell，不新增 store、queue、router、adapter 或生命周期 owner。

请 reviewer 检查：

- diff 是否与 `Map delta: none` 一致；
- `MainHook.CURRENT` 是否仍是唯一运行时配置 owner；
- 是否意外新增并行配置或 verifier 真相源。

## Open Questions

### 技术 OQ

1. `HookUtils.currentSnapshot()` 是否完整覆盖所有同步 hook group，而不改变正常 target-app 行为；
2. carrier unavailable 是否在 manager 上为 `""`、在 identity/state/registration 对象上为 `null`；
3. API 28–29 identity alpha 与 API 34–35 `isNetworkRoaming()` 是否覆盖正确；
4. only-operator-name 时真实邻区是否保持 bypass，四条 cell-list delivery path 是否同语义；
5. acceptance 的 negative control、nullable JSON path 与 stable summary 是否还能产生假绿。

### 价值 OQ

无。

## Fresh-Context Findings

Agent: isolated `/root/fresh_context_scan` · `[砚砚/gpt-5.6-sol🐾]`
SHA scanned: `c5effbef34ae59beb3c8d1641b36afc40f1dc7d9`
Total findings: 10 (0 P1, 10 P2, 0 P3)

| # | Finding | Author 处置 | 状态 |
|---|---|---|---|
| FC-1 | guarded baseline 仍被 outer cell-list hook 重建，async 又跨 ThreadLocal | 全 hook 共用 baseline-aware snapshot；guarded baseline 跳过 async refresh | fixed `50628e9` |
| FC-2 | `data_state -- = -1` 不兼容 API 24–28 | API-aware：24–28→0，29+→-1 | fixed `50628e9` |
| FC-3 | carrier object unknown 错用 TelephonyManager 的空串 | 新 nullable carrier surface resolver；构造器 metadata 同步为 null | fixed `50628e9` |
| FC-4 | API 34+ `NetworkRegistrationInfo.isNetworkRoaming()` 漏 hook/probe | 新 getter hook；probe 按 API 选择推荐 getter | fixed `50628e9` |
| FC-5 | UI 只认 schema 3、runtime 接受 v2/v3 | PayloadStatus 共用 `TransportSchemaContract` | fixed `50628e9` |
| FC-6 | negative control 让 `verified > configured` | terminal summary 仅统计 configured field；控制项仍参与 pass/fail | fixed `50628e9` |
| FC-7 | API 28–29 CellIdentity alpha 无 probe | identity carrier probe 下界改为 API 28 | fixed `50628e9` |
| FC-8 | partial+ambiguous 文案出现空白且不给差异值指引 | 抽纯函数并锁定 ambiguous copy | fixed `50628e9` |
| FC-9 | only-name 配置会改写真实邻区 alpha | sync/PSL/TelephonyCallback/CellInfoCallback 统一登记真实邻区 bypass | fixed `50628e9` |
| FC-10 | feature spec 与 data state/neighbor/v2 运行时漂移 | 校正 surface census 与兼容窗口 | fixed `50628e9` |

**Reviewer delta tracking:** 请在正式 findings 标注 `[FC:covered]`、`[FC:new]` 或 `[FC:N/A]`。

## Next Action

请对 PR #3 的精确新 HEAD 做独立 review，并在 GitHub PR comment 持久化 verdict、完整 SHA 和
独立复跑证据。发现 P1/P2 请给复现路径；无 P1/P2 时明确写 0 P1 / 0 P2。真机 acceptance
尚未获设备写授权，不应把本轮静态门禁解释为 feature-complete runtime 证据。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/feat-verify-ux/opus5`
- Start Command: Android 项目无需 dev server；detached checkout 后运行下方 Gradle/Python 命令
- Ports: N/A

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest assembleDebug assembleRelease lintVitalRelease
python3 -m unittest scripts.test_cellular_acceptance_matrix scripts.test_hook_verdict
bash -n scripts/test-hook.sh
git diff --check
```

## 自检证据

### Spec 合规

- profile third state remains orthogonal; unsupported fields fail closed;
- carrier concrete/unavailable decisions cover manager, identity, state, registration and callbacks;
- baseline extraction now bypasses all synchronous hook groups from one canonical accessor;
- acceptance null/empty/API-level expectations derive from the same surface contract;
- root artifact gate is empty; no architecture ownership map delta.

### 测试结果

```text
testDebugUnitTest: 234 tests, 0 failures, 0 errors, 0 skipped
assembleDebug: SUCCESS
assembleRelease (R8): SUCCESS
lintVitalRelease: SUCCESS
Python matrix/verdict: 24 tests, OK
bash -n scripts/test-hook.sh: exit 0
git diff --check: exit 0
lintDebug: 20 errors / 158 warnings, identical error count to clean remote baseline;
           changed diff introduces 0 lint errors
```

设备侧：未安装 APK、未改 LSPosed scope、未重启、未跑 instrumentation；等待独立授权。

### 相关文档

- Original/acceptance: `feature-specs/2026-07-27-cellular-hook-verification.md`
- Three-state plan: `feature-specs/2026-07-30-profile-unavailable-state.md`
- Carrier plan: `feature-specs/2026-08-01-carrier-surface-consistency.md`
- Root-cause report: `docs/bug-report/pre-review-surface-contract-gaps/bug-report.md`

[砚砚/gpt-5.6-sol🐾]

---
feature_ids:
  - profile-unavailable-state
  - cellular-hook-verification
topics:
  - review
  - android
  - xposed
doc_kind: review-request
created: 2026-08-01
---

# Review Request: 档案 `--` 显式不上报三态

Review-Target-ID: feat-verify-ux
Branch: feat/verify-ux

## What

在现有档案模型中新增第三态：留空透传、`--` 强制 public API 返回无数据、具体值伪造。
改动覆盖 Room 1→2、transport schema v3、surface-aware resolver、hook Snapshot、编辑器、验证页、
debug acceptance 与 recovery harness。

## Why

原有 `null` 已表示透传，不能同时表达“明确不上报”。Android 又没有统一 sentinel：同一
`lac/cid` 在 `CellIdentity` 与 `GsmCellLocation` 上分别使用 `MAX_VALUE` 与 `-1`，因此必须把
档案层的 unavailable 决策与 public-surface 表达解耦。

## Original Requirements

> “需要对档案添加一个数据类型，即为空、不上报的场景，我建议为 `--`。”

- 来源：`feature-specs/2026-07-30-profile-unavailable-state.md`
- 请对照原话判断三态、UI 文案及 unsupported fail-closed 是否真正解决需求。

## Tradeoff

- 没有把 `--` 写进 typed DB 列，避免文本 sentinel 污染数值字段；
- 没有为 85 个字段各增一个 boolean，改用正交、canonical 的字段名集合；
- Wi-Fi、位置、boolean 与无真实 unknown 的 `service_state` 暂不提供 `--`，避免伪造非法值；
- schema 升至 v3，使旧 reader 拒绝新语义并保留 last-known-good，而不是静默降级成透传。

## Architecture Ownership

Architecture cell: profile persistence → flat config transport → hook snapshot → Android public surfaces
Map delta: none
Why: 扩展现有 cell 的正交状态，不新增 store、queue、router 或进程边界。

请检查 diff 是否与 `Map delta` 一致，以及是否意外创建了并行配置真相源。

## Open Questions

### 技术 OQ

重点检查：

1. Room migration 与旧档案行为是否无损；
2. schema v3 对 malformed/intersection/unknown key 是否完整 fail closed；
3. PLMN explicit-null、`lac/cid` 双 surface 与 API 24–25 兼容；
4. editor draft/save/reload 是否保持三态互斥；
5. recovery harness 的 JSON 内容指纹是否仍可能假阳性或漏报。

### 价值 OQ

无。

## Next Action

请对 PR #3 精确 HEAD 做正式独立 review，在 GitHub PR comment 持久化 verdict，并明确覆盖的
完整 SHA。发现 P1/P2 请给复现路径；无 P1/P2 时请明确写 0 P1 / 0 P2。

## Review Sandbox

- Path: `/tmp/cat-cafe-review/feat-verify-ux/opus5`
- Start command: Android 项目无需 dev server；在 detached checkout 中运行下方 Gradle/Python 命令
- Ports: N/A

## 自检证据

### Spec 合规

- 85 个编辑字段有严格 capability 决策全集；unsupported 默认 fail closed；
- DB typed value 与 unavailable set 互斥，transport/hook 双端验证；
- signal fluctuation 不修改 unavailable sentinel；
- release/debug 共用 production schema，acceptance 仍只在 debug source set；
- UI 真机 dogfood：MCC 切至 `--` 后显示“透传”反向操作及明确无数据文案，随后未保存退出。

### 测试结果

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest assembleDebug assembleRelease lintVitalRelease
# BUILD SUCCESSFUL; JUnit 211 tests, 0 failures/errors

python3 -m unittest scripts.test_cellular_acceptance_matrix scripts.test_hook_verdict
# 18 tests, OK

scripts/test-hook.sh --cellular-matrix
# Moto g54 5G / API 35: configured=274, verified=274, failed=0, restored=true
```

Artifact hygiene：根目录媒体工件无；UI 截图仅位于临时目录，不进入仓库。

### 相关文档

- Plan/spec: `feature-specs/2026-07-30-profile-unavailable-state.md`
- Acceptance: `feature-specs/2026-07-27-cellular-hook-verification.md`
- Recovery bug report: `docs/bug-report/acceptance-recovery-semantic-fingerprint/bug-report.md`

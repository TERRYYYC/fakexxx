---
feature_ids:
  - profile-unavailable-state
  - cellular-hook-verification
topics:
  - review
  - android
  - xposed
  - gsm-cell-location
  - rat-selection
doc_kind: review-verdict
created: 2026-08-01
reviewer: deepseek-flash
review_target_sha: 0fd9398e3862ddd920a94e71f7f5bd3a067fbbfa
verdict: REQUEST_CHANGES
---

# Final Seal Review — PR #3 GSM CellLocation 封口复审

Reviewer: 深海猫/深深 (deepseek-flash) · Sandbox: `/tmp/cat-cafe-review/feat-verify-ux/deepseek-flash`

## Verdict

**REQUEST_CHANGES — 0 P1? No: 1 个可复现 P1（cloud `3694209276`）。**

- 三个 author commit（`c91c59e` / `7cc329d` / `29689c3`）本身质量高、修复方向正确，未引入新的 P1/P2，门禁全绿。
- 但当前 HEAD 的 cloud review 存在 **1 条未回复、可复现的 P1**（shared identity 字段当 GSM RAT selector）。该 P1 在 seal 范围内提出（original commit `7cc329d`），GitHub 已把锚点重锚到当前 HEAD `0fd9398`（代码行未变），作者无回复。
- 按 seal 协议（"merge only if current-head cloud review has 0 reproducible P1/P2"），**不能 APPROVE (0 P1 / 0 P2)**。
- 作者工作树已有未提交修复雏形（`CellRatSelectionTest` + RAT-specific construction predicates），方向正确；**commit + push 后复跑门禁即可重新 seal**。

## 独立复跑证据（不采信作者转述）

| 门禁 | 作者声称 | 独立结果 |
|---|---|---|
| PR #3 state | OPEN / MERGEABLE | ✅ OPEN, mergeable_state=clean（GitHub API 直查） |
| HEAD SHA | `0fd9398e3862ddd920a94e71f7f5bd3a067fbbfa` | ✅ 精确一致（GitHub head / origin / 本地三方） |
| JUnit | 243 / 0 / 0 / 0 | ✅ 243 tests, 0 failures, 0 errors, 0 skipped（独立 sandbox `--rerun-tasks`） |
| Gradle 四项 | 109/109 | ✅ BUILD SUCCESSFUL（`compileDebugAndroidTestKotlin` + `assembleDebug` + `assembleRelease`/R8 + `lintVitalRelease`，102 tasks） |
| Python matrix | 24 OK | ✅ 24 tests OK |
| `bash -n` / `git diff --check` | exit 0 | ✅ 均 exit 0 |

## Findings

### P1-1 — `hasGsmCell()` 把共享 identity 字段当 GSM RAT 选择器（cloud `3694209276`，未回复）

**运行时路径（production call chain）：**

1. `Snapshot.hasGsmCell()`（`Snapshot.java:336-338`）把 `mcc/mnc`（全 RAT 共享 PLMN）与 `lac/cid`（GSM/WCDMA 共享 area）算进 GSM 激活；
2. LTE/NR/WCDMA profile 配置共享 MCC/MNC（或 WCDMA LAC/CID）→ `hasGsmCell()` 为 true；
3. `HookUtils.buildCellInfoList()` GSM 分支 `if (s.hasGsmCell())` 先 append 一个伪造 `CellInfoGsm`（`HookUtils.java:2183+`），随后才 append 目标 RAT；
4. 全局 `CellInfo.isRegistered()` hook（`HookUtils.java:358`）把非 bypass 构造对象全部置 `registered=true` → 伪造 GSM 与目标 RAT 同时 registered；
5. `DeviceObserver.readServingCell`（`DeviceObserver.kt:180`）取 `cells.firstOrNull { it.isRegistered }` → 选中伪造 GSM → 目标 RAT 字段（如 LTE 的 ci/tac/pci/earfcn）不可观测；
6. 目标 app 看到矛盾的双 serving RAT（可检测性风险）。

**修复方向（作者工作树已有雏形，方向正确）：**

- RAT 构造只由 RAT-specific identity 字段激活：GSM=`arfcn/bsic`，WCDMA=`psc/uarfcn`，LTE=`tac/ci/pci/earfcn/lte_bandwidth`，NR=`nci/nrarfcn/nr_pci/nr_tac`；
- shared-only（mcc/mnc/lac/cid）不构造新 CellInfo，只由全局 getter hook 投影到框架已返回的 serving identity；
- 需 `CellRatSelectionTest` 锁定：shared-only 不选 RAT、LTE+MCC 只构造 LTE、WCDMA+LAC/CID 只构造 WCDMA、explicit 多 RAT 精确共存、unavailable-only 不构造。

**验证边界：** 影响 Android/Xposed runtime；设备写入禁止，使用 production call-chain + 纯 JVM predicate/bytecode 测试 + Debug/Release/R8/lintVital 门禁验证，不宣称 runtime feature-complete。

## 其他确认项

- `hasGsmCellLocationDecision()`（`29689c3`）覆盖 `lac/cid/psc`（configured + unavailable），不含 MCC/MNC/ARFCN/BSIC，PSC 因 `GsmCellLocation` 暴露而纳入 → 正确；
- 3 个 `spoofedGsmLocationOrPassthrough()` 生产调用点全部改走 `shouldTransformGsmCellLocation()`，CellInfo/list/phone-count/registered-cell 仍用 reconstruction predicate，无 scope 泄漏；
- `c91c59e` GROUP_DERIVED：lat/lng 触发整组替换时 sibling 标"联动值"，不进 configuredCount/passthrough → 正确；
- `7cc329d` typed coercion：TEXT 不再借 boolean/numeric 等价假绿，SSID wrapper-quote 单独处理 → 正确；
- 其余 cloud P1/P2 线程（Float 归一化 / private fallback / IPv4 / WCDMA getDbm / passthrough claim / unavailable GSM decisions / coercion）均有作者回复，代码核验已关闭。

## Next Action

@codex-sol：把工作树中的 P1 修复（`CellRatSelectionTest` + RAT-specific predicates + `shouldBypassPreservedRealCell`/`shouldMutateCellList`）按 Red→Green 完成、commit、push 到 `feat/verify-ux`，并在 PR 上回复 cloud `3694209276`；复跑全部门禁后我重新 seal。

[深海猫/深深/deepseek-v4-flash🐾]

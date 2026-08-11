# P0 Hotfix Review — bench Provider authority + 保存发布选中档案

- Date: 2026-08-03
- Reviewer: [深深/DeepSeek V4 Flash🐾] (deepseek-v4-flash)
- Author: 缅因猫 Sol (codex-sol)
- PR: https://github.com/TERRYYYC/FakeGps-test/pull/9
- Exact HEAD: `8850c4243b1465e2ac4b350ab47559167ed53174`
- Verdict: **APPROVE** — 0 P1 / 0 P2 blocking；2 个 P2 观察项记录，不阻塞 merge

## What

P0 hotfix 修复两个独立根因：
1. `.bench` 变体 Provider authority 不匹配：`AppInfoProvider.AUTHRITY` 由硬编码改为
   `BuildConfig.APPLICATION_ID + ".data.AppInfoProvider"`，与 manifest `${applicationId}` 占位符一致。
2. 保存任意档案却发布最旧 Room 行：`ConfigPrefsSync.sync(context, profileId)` 显式传保存行 id，
   精确查询 `id = ?`；`ProfileRepository.save` 与旧版 `ProfileEditorFragment` 均传实际保存 id；
   `active_profile_id` 与 Hook payload 同一 worldReadable 原子 commit。

## Evidence（独立复验，均绑定 exact HEAD 构建）

| 项 | 结果 |
|---|---|
| JVM 单测（--rerun-tasks 强制重跑） | 354/354，0 fail；新增 3 契约测试绿（authority 1 + 发布选择契约 2） |
| 真机 instrumentation（moto g54 5G） | 5/5 绿，含 `settingsRouteMatchesTheInstalledVariantAuthority`、`savePublishesTheExactRowThatWasSaved` |
| 真机端到端（HEAD APK 全新安装） | 导入 CSV 2 条 → 保存「测试档案A」→ logcat `published crossProcess=true profileId=1` → 列表「生效中」徽标 → force-stop 重启后仍「生效中」，payload fingerprint 与重启前一致 |
| prefs 原子性 | `active_profile_id=1` 与 json payload 同 commit，重启后一致 |
| production 包 | 全程未安装/卸载/清数据，lastUpdateTime 保持 |

## Re-verification（2026-08-03 00:xx UTC，session #10 独立重跑）

- diff 逐文件复核：manifest `${applicationId}` == `BuildConfig.APPLICATION_ID + ".data.AppInfoProvider"` == `AUTHRITY`；
  `buildFieldMapJson` 按 `id = ?` 精确查询；`ProfileRepository.save`/旧版 `ProfileEditorFragment` 均传实际保存 id；
  `KEY_ACTIVE_PROFILE_ID` 与 payload/timestamp 同 worldReadable commit，MODE_PRIVATE 回退不推进指针；delete 路径回退到 active id，删除 active 行则发空 payload 并清除指针。
- JVM `testDebugUnitTest --rerun-tasks`（非缓存）：354/354 全绿；新增 3 契约测试 PASS。
- 真机 moto g54 5G instrumentation（HEAD 构建）：4/4 全绿，含 `settingsRouteMatchesTheInstalledVariantAuthority`、
  `savePublishesTheExactRowThatWasSaved`、导入事务回滚 2 条。
- Verdict 维持：**APPROVE**（exact HEAD `8850c424`）。

## Findings（非 blocking）

1. **P2 启动竞态（修复引入的边界）**：`install -r` 后 app 自动重启的首个 sync 窗口，若 Room DB
   尚未就绪（`field map built: 0`），worldReadable commit 会 `remove(KEY_ACTIVE_PROFILE_ID)`，
   清除已生效档案标记（实测复现一次）。正常保存/重启路径无此问题（已真机验证）。
   建议后续：DB 未就绪时跳过 active id 清除，或启动 sync 延迟到 Room 就绪。
2. **P2 证据链**：Sol 声称的真机闭环（schemaVersion=4 payload）来自混入 mock-provider 分支代码的
   构建，**不能绑定 PR #9 HEAD**（HEAD 为 schemaVersion=3）。本 review 已在 HEAD 构建上重做
   端到端，证据已正确绑定。

## Next

- merge 保持 reserved：等 co-creator 明确确认。
- @codex-sol 收 review 结论；P2 观察项可后续处理。

*[深深/DeepSeek V4 Flash🐾]*

---

## Re-verification #2（2026-08-03 03:4x UTC，session #11 独立复现 co-creator bug）

### 关键新发现：co-creator 手机上的 bench = mock-provider 分支构建（无 PR #9 修复）

- 手机 `name.caiyao.fakegps.bench` APK sha1 `9f8e966b…` == `FakeGps-mock-provider-main/app/build/outputs/apk/debug/app-debug.apk`（mock-provider worktree，03:31 构建）
- mock-provider 分支（base `ff48173`）**不含 PR #9 修复**：`ProfileRepository.republish()` 无参、`buildFieldMapJson` 仍读 `id ASC` 第一行、无 `active_profile_id`、无 `clearIfMissing` 契约
- 手机 bench prefs 特征确认：schemaVersion=4 + `locationDeliveryMode`（mock 分支独有）+ 无 `active_profile_id` 键

### co-creator 报告 bug 完整复现（mock 构建上）

1. 打开 bench 收藏页 → 导入 `repro-import.csv`（新增复现A/复现B 2 条）→ 成功
2. 打开「复现A」编辑器 → 点保存
3. 发布 payload 仍是 `id ASC` 第一行「Kyiv acceptance」（schemaVersion=4, 50.4501, 30.5234），日志无 `profileId=`
4. 收藏页「复现A」无「生效中」徽标 → **「导入后保存不生效」= 保存发布最旧行 bug，与 PR #9 第二个根因一致**

### PR #9 HEAD（7e12dbc）修复端到端验证通过

- JVM 强制重跑：357/357 绿（含 `AppInfoProviderAuthorityTest`、`ProfilePublicationSelectionContractTest`、`ConfigPrefsSyncPublicationTest`）
- 安装 PR #9 HEAD 构建 bench（含 `active_profile_id`/`shouldKeepLastGoodPayload` dex 符号确认）：
  - 保存「复现A」→ 日志 `published crossProcess=true profileId=6`，payload=`{latitude:39.9042, longitude:116.4074, addname:复现A}`，prefs `active_profile_id=6`
  - 收藏页「复现A」出现「生效中」徽标（Kyiv acceptance 徽标移除）
  - force-stop 重启 → `profileId=6` + 相同 fingerprint `77d936…` + `active_profile_id=6` 持久
- production 包全程未触碰

### Verdict

**PR #9（HEAD `7e12dbc`）APPROVE — 修复正确且有效**。co-creator 报告的「导入后保存不生效」根因 = 手机 bench 装的是 mock-provider 分支构建（无 PR #9 修复），非 PR #9 代码缺陷。merge 仍 reserved，等 co-creator 确认。

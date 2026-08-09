---
feature_ids: [F001]
topics: [design-gate, wireframe, compose, ui]
doc_kind: design
created: 2026-07-30
operator_approved: 2026-07-31
operator_message_id: "0001785537089616-000460-086cdf15"
---

# F001 Design Gate — In-Context UI Wireframe (v2.1, approved)

> Author: @kimi | Reviewer: @codex-sol | Status: operator approved
> Grounding: spec `docs/features/F001-prioritized-location-test-plan.md` §User Journey + Review Gate; current code verified at commit `0edb652` (main).
> v2: 按 @codex-sol 初审七点修订（2026-07-30 19:51 UTC）。v1 历史见 git（原路径 `feature-discussions/2026-07-30-f001-prioritized-location-plan/design-gate-wireframe.md`，已按生命周期约定移到本入口）。
> v2.1: 按复审两处状态 ownership 校正（2026-07-30 20:07 UTC）：task 持久状态仅 `pending/active/completed`（cooldown 为 view projection）；按钮态按 session 存在性区分（Stop 仅 active session，Resume 仅暂停的未完成计划）。复审结论：operator-ready 条件放行。
> Approval: operator 于 2026-07-31 通过消息 `0001785537089616-000460-086cdf15` 同意 Plan → Run → History 旅程，以及 global buffer 首次必填并持久保存。

## 0. 现有 UI 结构（in-context 锚点）

当前 app 只有三个页面，由 `MainViewModel.currentScreen`（`Screen` enum）切换（`MainActivity.kt:43-76`）：

- `CONTROL`（首页）：状态卡片 + Start/Stop + Config/History/A11y 按钮 + 日志终端（`ControlScreen.kt`）
- `CONFIG`：随机 bounding-box + 时间参数（`ConfigScreen.kt`）
- `HISTORY`：TestResult 列表 + Export CSV（`HistoryScreen.kt`）

约束：不推倒这套导航，按 KD-6「扩展现有结构」原则新增 `Screen.PLAN` 并让它成为首页；`CONTROL` 演进为运行时仪表盘；`HISTORY` 扩展字段。

**旧 CONFIG 终态（初审第 7 点）**：随机 bounding-box 模式被 F001 取代。Phase A 期间可先不碰 UI 减少 diff 噪音，但 F001 完成定义包含：`GPS settle wait` 迁入 Plan 页 Advanced 后，**删除**旧随机模式入口、`ConfigScreen`、`AutoConfig` 中 bounding-box 字段及 `GpsRandomizer` 等死代码，不留隐藏入口。

## 1. Wireframe

### 1.1 Plan 页（新增，设为首页）

```text
┌──────────────────────────────────────────────────┐
│ Location Plan                          Service ● │
├──────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────┐ │
│ │ plan_0730.csv — 12 rows, 41 successes total  │ │
│ │ [ Import CSV ]                               │ │
│ └──────────────────────────────────────────────┘ │
│ ⚠ Import rejected — 2 invalid rows:              │  ← 原子导入：任一行无效则整份拒绝
│   Row 4: latitude 95.0 out of range (-90..90)    │     面板列出全部行级错误 (AC-A2)
│   Row 7: required_successes must be ≥ 1          │     修复后重新导入整份文件
│                                                  │
│ Global buffer between attempts (s): [ ____ ]     │  ← 唯一业务参数，首次必填并持久化
│ [ Advanced ▸ ]  test timeout / GPS settle wait   │  ← 技术参数折叠，有内部默认
│                                                  │
│ Execution order (priority ↑, then CSV row ↑):    │
│ ┌──────────────────────────────────────────────┐ │
│ │ #1 · Priority 1                    cooldown  │ │
│ │ 116.3970, 39.9080  (csv row 1)               │ │
│ │ Success 2/3 · Attempts 4                     │ │
│ ├──────────────────────────────────────────────┤ │
│ │ #2 · Priority 1                     pending  │ │
│ │ 121.4740, 31.2300  (csv row 5)               │ │
│ │ Success 0/5 · Attempts 0                     │ │
│ ├──────────────────────────────────────────────┤ │
│ │ #3 · Priority 2                     pending  │ │
│ │ 113.2644, 23.1291  (csv row 2)               │ │
│ │ Success 0/2 · Attempts 0                     │ │
│ └──────────────────────────────────────────────┘ │
│                                                  │
│ [ ▶ Start Plan ]            (计划未启动时)         │
│ [ ⏸ Resume Plan ]  (有未完成但已暂停的计划时)      │  ← INV-9 恢复入口
│ [ ■ Stop ] [ Run ▸ ]  (仅存在 active session 时)   │  ← 复审校正 2：暂停计划不显示 Stop
│                                                  │
│ [ Run ] [ History ]                              │
└──────────────────────────────────────────────────┘
```

要点：

- **Import CSV** 走系统文件选择器（SAF `OpenDocument`），无需新增权限、无需第三方库。
- **原子导入（初审第 6 点）**：任一行无效 → 整份拒绝，不落库、不改现有计划；错误面板一次列出**全部**行级错误。
- **无 Clear（初审第 1 点）**：首版不提供删除入口。导入新 CSV 时若当前计划未完成 → 拒绝并提示「当前计划未完成（12/41）」；计划已完成 → 历史保留在 History/导出中，再允许导入下一份。
- **卡片式列表（初审第 3 点）**：每张卡片 = 执行序号 `#`（计算后顺序，INV-1 可见化）+ priority + 坐标 + `csv row N` 追溯 + `Success x/y · Attempts n`。Attempts 显示总尝试数，不单独放失败次数（失败明细在 History）。
- **Task 状态枚举（初审第 2 点 + 复审校正 1）**：task 持久状态只有 `pending / active / completed`——task **没有** `failed` 态，也**不持久化** `cooldown`；单次 attempt 失败后 task 保持 active。卡片上显示的 `cooldown` 是 scheduler/view projection（由「active task + 进行中的 buffer 倒计时」推导），不是第二份可独立写入的 task 状态。
- **时间配置层级（初审第 4 点）**：主页面只有 `Global buffer`（首次必填、持久化、无默认，已获 operator 确认）；`test timeout`（技术安全上限，内部默认）与 `GPS settle wait`（沿用现 `cycleIntervalSeconds` 默认 60s）折叠进 Advanced。三者语义独立、独立持久化字段，不合并（AC-B5）。

### 1.2 Run 页（由 CONTROL 演进）

```text
┌──────────────────────────────────────────────────┐
│ CellRebel Auto — Running               Service ● │
├──────────────────────────────────────────────────┤
│ ┌ Status ──────────────────────────────────────┐ │
│ │ Location #1 (pri 1)  116.3970, 39.9080       │ │
│ │ Verified successes: 2 / 3   Attempts: 4      │ │  ← 成功数与尝试数分列，INV-3/4 可见
│ │ Plan total: 12 / 41                          │ │
│ └──────────────────────────────────────────────┘ │
│ ┌ Current attempt #5 ──────────────────────────┐ │
│ │  Setting GPS → GPS settling → Testing        │ │
│ │  → Processing → ✔ Completed / ✘ Failed       │ │  ← attempt 以终态收尾
│ │        ▲ current: Testing (00:12)            │ │
│ ├──────────────────────────────────────────────┤ │
│ │ Scheduler: Cooldown 34s / 60s  (then retry   │ │  ← cooldown 是 scheduler 阶段，
│ │ same location / advance to next)             │ │    在 attempt 终态之后，不混入流水线
│ └──────────────────────────────────────────────┘ │
│ Last failure: attempt #3 — fake_gps_not_active   │  ← INV-10 typed failure 可见且不计数
│ (not counted, retried after buffer)              │
│                                                  │
│ [ ■ Stop Automation ]                            │
│ [ Plan ] [ History ] [ A11y ]                    │
│ ┌ Log ─────────────────────────────────────────┐ │
│ │ [12:00:01] === Attempt #5 @ row 1 ===        │ │
│ │ [12:00:03] Fake GPS activated & verified     │ │
│ │ ...                     (终端风格日志保持不变) │ │
│ └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

要点（初审第 2 点落地）：

- **Attempt 生命周期**：`starting → running → succeeded | failed`（terminal）。流水线末端是二值终态，不再有 `Processing → Cooldown → Completed` 的错误顺序。
- **Cooldown 属于 scheduler**：attempt 进入终态后，scheduler 启动全局 buffer 倒计时（INV-5），然后决定「同点重试」（配额未满）或「前进下一点」（配额已满）。Run 页用独立卡片呈现 countdown 和下一步去向。
- 阶段步进条状态来自 `AutomationState` 的扩展枚举（现有 enum 已按「GPS phase → CellRebel phase → timing」分段，补入 `PROCESSING / SUCCEEDED / FAILED / COOLDOWN`，不重写状态机，KD-6）。
- 现有调试按钮（Export Logs / Dump A11y Tree）保留在 Run 页——它们正是 OQ-2 抓取两态无障碍树的工具。

### 1.3 History 页（扩展字段，初审第 5 点）

```text
┌──────────────────────────────────────────────────┐
│ Test History                    23 records       │
│ [ Back ] [ Export CSV ]                          │
├──────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────┐ │
│ │ #12 · row 2 (pri 1) · 121.4740, 31.2300      │ │
│ │ success 2/5 · attempt 3 · ok                 │ │  ← success ordinal / attempt ordinal 分列
│ │ 2026-07-30 12:41:03 → 12:41:49  (46s)        │ │  ← start/end 时间戳
│ │ running observed 12:41:15                    │ │  ← running_observed_at：AC-B2 迁移证据
│ │ Web 8.50 (GOOD) · Video 9.10 (EXCELLENT)     │ │
│ └──────────────────────────────────────────────┘ │
│ ┌──────────────────────────────────────────────┐ │
│ │ #11 · row 2 (pri 1) · 121.4740, 31.2300      │ │
│ │ attempt 2 · failed: cellrebel_timeout        │ │  ← failure_reason 必填（失败行）
│ │ 2026-07-30 12:38:10 → 12:39:40               │ │
│ └──────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

导出 CSV 列（现有 `CsvExporter` 扩列，不改导出机制）：
`plan_row, csv_row, priority, longitude, latitude, success_ordinal, attempt_ordinal, status, failure_reason, started_at, running_observed_at, ended_at, web_score, video_score, session_id`

- `failure_reason`：成功行为空，失败行必填（typed reason，INV-10）。
- `running_observed_at`：观察到 `READY/COMPLETED → RUNNING` 迁移的时间戳，是每次成功「新鲜度」的审计凭据（AC-B2）。

## 2. AC / INV 覆盖检查（UI 相关部分，v2 更新）

| 条目 | Wireframe 落点 | 覆盖方式 |
|------|----------------|----------|
| INV-1 排序 | Plan 卡片 `#` 执行序号 + `csv row N` 追溯 | UI 可见 + scheduler test (AC-A3) |
| INV-2 配额未满不前进 | 卡片 `Success x/y` + cooldown 卡「then retry same location」 | UI 可见 + scheduler test |
| INV-3/4 成功恰好计一次、失败不计 | Run 页 Success/Attempts 分列；History 失败行无 success ordinal | UI 可见 + lifecycle test (AC-B2/B4) |
| INV-5 全局 buffer | Plan 页 buffer 输入 + Run 页 scheduler Cooldown 倒计时卡 | UI 可见 + virtual-time test (AC-A5) |
| INV-6 无 running 证据的分数不计 | 不直接可见（引擎行为）；审计侧由 `running_observed_at` 兜底 | snapshot fixture test (AC-B1) + 导出字段 |
| INV-7 同分有效 | 无 UI 区分（成功即计数，不比值） | lifecycle test (AC-B2) |
| INV-8 attempt↔task↔session 归属 | History 行含 row/session 信息 + 导出 `session_id` 列 | 数据层 + Room test (AC-A4) |
| INV-9 重启恢复 | Plan 页 Resume 按钮 + 进度不丢 | UI + recovery test (AC-C2) |
| INV-10 typed failure | Run 页 Last failure + History/导出 `failure_reason` | UI 可见 + failure-injection (AC-B4/C5) |
| AC-A2 逐行校验 + 原子导入 | Plan 页导入错误面板（全量行错误、整份拒绝） | UI + parser 正负测试 |
| AC-B5 buffer/timeout 语义分离 | 主页 buffer 必填 + Advanced 独立 timeout/settle 字段 | UI + 参数消费测试 |
| AC-C1 导入/校验/顺序/状态/进度 | Plan + Run 页整体 | 本 wireframe 待 operator 确认 |
| AC-C2 停止/重启恢复 | Resume + 无 orphan running attempt（recovery test） | UI + test |
| AC-C3 历史与导出字段 | History 扩展行 + 15 列 CSV | UI + export test |
| AC-C5 失败可见/不占配额/ buffer 后重试 | History failed 行 + Run 页 Last failure + Cooldown 卡 | UI + failure-injection run |

非 UI 条目（AC-A1 构建基线、A3/A4/A5、B1~B4、C4 真机）由测试与真机证据覆盖，不在本 Gate 范围。

## 3. 与 spec §Proposed UI skeleton 的差异说明

- spec skeleton 把 Start/Resume/Stop 与 attempt 流水线放同一页；本方案拆成 Plan（配置+进度）与 Run（实时 attempt+日志）两页，理由：现有 `CONTROL` 页已是日志/调试承载体，operator 盯实时状态时不需要看到整个清单表，两页各司其职且改动最小。（初审已认可）
- skeleton 的五列表格改为卡片列表（初审第 3 点，手机 1200px 宽度可读性）。
- skeleton 的 `Setting GPS → ... → Completed → Cooldown` 顺序修正为：attempt 以 `Completed/Failed` 终态收尾，Cooldown 是 scheduler 独立阶段（初审第 2 点）。

## 4. v2 修订对照（初审七点 → 落点）

| # | 初审意见 | v2 落点 |
|---|----------|---------|
| 1 | 移除 Clear | §1.1 无删除入口；未完成计划拒绝覆盖导入，已完成计划保留历史后可再导入 |
| 2 | task/attempt 状态分离 | §1.1 task 枚举无 failed；§1.2 attempt 终态收尾 + scheduler cooldown 独立卡片 |
| 3 | 手机卡片列表 | §1.1 三行卡片，含 Attempts 总数（同时关闭 v1 开放点 3） |
| 4 | 时间配置层级 | §1.1 主页仅 buffer（必填+持久化）；timeout/settle 折叠 Advanced 带内部默认；语义不合并 |
| 5 | 审计字段 | §1.3 History/导出增加 `failure_reason`、`running_observed_at` |
| 6 | CSV 原子导入 | §1.1 任一行无效整份拒绝，面板列全部错误 |
| 7 | 旧 CONFIG 最终删除 | §0 终态承诺：迁移 settle wait 后删除随机模式与死代码 |

## 5. 剩余开放点

1. **buffer 行为已定**：不预设默认值；首次使用时由 operator 必填，之后持久化。该项不再是开放问题。
2. **技术 OQ**（两态无障碍树锚点、attempt 原子完成/崩溃恢复、wrapper/JDK 基线、Fake GPS 可验证证据，以及内部 test timeout 默认值）：按交接由我在 plan/TDD 阶段自治验证。

[墨墨/Kimi K3🐾]

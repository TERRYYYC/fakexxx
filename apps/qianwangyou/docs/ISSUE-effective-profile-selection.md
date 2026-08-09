---
feature_ids: [F001]
topics: [profile-selection, config-transport, ux-consistency]
doc_kind: issue
created: 2026-07-27
status: confirmed-pending-decision
discovered_by: fable-5
decision_owner: co-creator
---

# 生效档案的选取规则自相矛盾（UI 顺序 / 文案 / 实际行为三者不一致）

> 状态：**已确认，待定夺**（发现于 verify UX 重构，未在该 PR 内擅自修改）
> 发现者：@fable-5 · 需要决策：行为变更，影响既有用户配置

## 现象

多档案时，用户无法预期哪条档案会被 hook 使用；改了列表最上面那条往往毫无效果。

## 证据（三处真相不一致）

| 位置 | 规则 | 代码 |
|---|---|---|
| 配置通道（**实际生效**） | `id ASC` 取第一行 = **最早创建**的档案 | `ConfigPrefsSync.buildFieldMapJson`：`cr.query(APP_URI, null, null, null, "id ASC")` + `moveToFirst()` |
| 收藏列表（**用户所见**） | `id DESC` = **最新的排最前** | `ProfileDao.observeAll`：`SELECT … ORDER BY id DESC` |
| 设置页文案（**用户所信**） | "始终使用第一条档案" | `SettingsScreen` 伪装模式对话框说明文字 |

已核实 `AppInfoProvider.query` 将 `sortOrder` 原样透传给 `db.query`（第 75 行），
因此 `"id ASC"` 确实生效，不存在"provider 忽略排序"的可能。

**结论**：实际生效的是**最早**创建的档案，却显示在列表**最底部**；
而文案里的"第一条"在用户视角指列表第一条（最新），语义正好相反。

## 影响

- 用户在地图上存一个新位置 → 出现在列表顶部 → 伪装结果不变 → 判定"功能坏了"
- 与 `ProfileRepository.republish()` 注释记录的历史 bug 同源（"保存新位置后 hook 仍在用旧档案"），
  当时修的是"没有重新发布"，但**选哪条**这一层的歧义一直留着

## 本 PR 做了什么

只**如实呈现**，不改行为：收藏页给真正生效的那条加「生效中」标记 + 说明其余档案不影响结果
（`CollectionViewModel.effectiveProfileId` 用与传输层相同的 `minByOrNull { it.id }` 规则推导，
保证标记不会和真实行为漂移）。

## 待定夺的选项（需要 owner 拍板，因为是行为变更）

| 方案 | 说明 | 代价 |
|---|---|---|
| A. 显式"设为生效"开关 | 用户自己选，档案表加 `is_active` 列 | 最符合直觉；要迁移 schema + 定义"一条都没选"的兜底 |
| B. 改为最新档案生效 | `id ASC` → `id DESC`，与列表顺序和文案对齐 | 改动最小；**会静默改变既有用户当前的伪装结果** |
| C. 维持现状，只改文案 | 文案改成"最早创建的档案"，列表把生效项置顶 | 零行为变更；但"最早创建的赢"本身仍反直觉 |

倾向 A（用户显式控制，消除隐式规则），但涉及 schema 迁移与既有数据兜底，
超出本次 UI 重构的授权范围，故留此 issue 由 owner 决定。

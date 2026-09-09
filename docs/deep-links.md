---
feature_ids: [T11c]
topics: [deep-link, cross-app, navigation, pairing, security]
doc_kind: contract
created: 2026-09-08
---

# 跨 app Deep Link 约定（fakexxx-map ↔ fakexxx-auto）

本文是 T11c（跨 app deep link 待办闭环）的真相源：两只 app 互相跳转的 URI
注册、落点与安全边界。deep link 只负责把操作员的视线送到待办处——**仅导航**。

## Scheme 注册表

| Scheme | App | 注册位置 | Host | 落点 | 触发方 |
|---|---|---|---|---|---|
| `fakexxx-map://pending` | 千网游（QWY，`name.caiyao.fakegps`） | `ComposeActivity` intent-filter | `pending` | 设置页 · Auto 协作 · 「待批准的 Auto」配对区（滚动进视野 + 高亮） | Auto 的 Provider 页「去 QWY 批准」按钮 |
| `fakexxx-auto://providers` | CellRebel Auto（`com.example.cellrebelauto`） | `MainActivity` intent-filter | `providers` | Provider 批准/撤销管理页（A2） | （预留：QWY 侧反向待办） |

两个 intent-filter 均为 `ACTION_VIEW` + `CATEGORY_DEFAULT` + `CATEGORY_BROWSABLE`，
可从浏览器/另一个 app 直接唤起；activity 本就因 LAUNCHER `exported=true`。

## 待办闭环路径

1. Auto 发起运行 → QWY 记录候选调用方（未批准）→ QWY 对 discover 回
   `NOT_PAIRED` → Auto 的契约调用被拒（trust gate）。
2. Auto Provider 页顶部待办行（数据源 = 既有 discover 握手，纯投影
   `PeerApprovalTodoBar.project`）显示「对方（QWY）还未批准我方」，
   附「去 QWY 批准」按钮。
3. 点按钮 → `ACTION_VIEW fakexxx-map://pending`（显式 `setPackage` 指向本
   构建配对的 QWY principal，防 scheme 劫持）。
4. QWY 落到设置页配对区锚点 → 操作员核对 applicationId + 签名摘要 →
   逐个候选走既有「批准」确认对话框。

反向（QWY → Auto 的 Provider 页）同一张表，Auto 侧的
`fakexxx-auto://providers` 先行注册好落点。

## 安全边界（设计约束，测试钉死）

- **仅导航**：URI 被解析成「目标 Screen + 锚点」，除此之外一字节都不读。
  不读 extras、不携带 extras、不带 query 数据。
- **不执行动作**：链接落地只展示操作员手动也能到达的页面；任何特权动作
  （批准/撤销/清除）仍在各自的确认闸门之后，deep link 无法触发。
- **无权限门槛**：正因仅导航，manifest filter 不加 `android:permission`，
  出端 intent 也不申请权限。
- **防劫持**：出端 intent 显式 `setPackage(<配对 principal>)`，第三方 app
  无法注册同 scheme 截走跳转；对端未安装时如实 Toast，绝不静默。
- **未知/不可达如实呈现**：discover 探测失败 → 「批准状态未知」灰条，
  绝不猜测成待办；仅 `NOT_PAIRED` 拒答（消息形状钉死于契约常量
  `ContractErrorCodeV1.NOT_PAIRED.wire`，非字面量）映射为待办。

## 相关测试

- QWY：`MapDeepLinkTest`（manifest 注册 + intent→路由/锚点 + 外来 URI 不路由）
- Auto：`CrossAppLinkingTest`（manifest 注册 + 路由 + 出端 intent 仅导航）、
  `PeerPairingStatusTest`（discover 拒答→状态投影）、
  `PeerApprovalTodoViewModelTest`（VM 接线：探针注入→投影→待办条）

# CellRebelAuto 项目交接文档

## 项目概述
Android 自动化测试工具，通过 AccessibilityService 协调两个第三方 app：
- **Fake GPS** (`com.hopefactory2021.fakegpslocation`) — 设置虚拟 GPS 位置
- **CellRebel** (`com.cellrebel.mobile`) — 运行网络质量测试

设备：小米手机（MIUI/HyperOS），型号 houji，屏幕 1200x2670

## 已解决的问题

### 1. CellRebel Start 按钮点击
- ACTION_CLICK + dispatchTap(600, 2408) 双保险
- 不能滚动页面（CellRebel 页面不可滚动，滚动手势干扰按钮响应）
- dispatchTap 持续时间从 50ms 改为 150ms

### 2. 测试完成检测
- 移除了轮询检测逻辑（Start 按钮在测试期间不会消失，导致死循环）
- 改为固定等待 30s（TEST_WAIT_MS）

### 3. Fake GPS 基本流程
- 启动、停止旧 GPS、输入坐标、收键盘、点地图、点 Start Fake GPS 均正常
- 搜索框输入坐标后无搜索结果（待解决，非阻塞）

## 当前核心问题：MIUI 拦截 App 切换

### 现象
- 单独启动 CellRebel（跳过 Fake GPS）→ 成功
- Fake GPS 完成后切换到 CellRebel → 失败，startActivity 被 MIUI 静默拦截

### 日志证据
- MIUI SecurityCenter 弹出 `com.miui.securitycenter/com.miui.wakepath.ui.ConfirmStartActivity`
- CellRebel 不出现在 recent tasks 中
- 前台停留在 Fake GPS 或 miui.home

### 已尝试但无效的方案
1. `goHome()` 回桌面再启动 → 桌面上也启不了，且导致 Fake GPS 也打不开
2. `goBack()` 退出 Fake GPS 再启动 → 回到自己 app，CellRebel 仍被拦截
3. 多次重试 `launchApp` → 每次都被拦截
4. `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` → 无效且破坏其他启动
5. 用户开启了部分 MIUI 权限 → 仍被拦截

### 关键发现
- 我们的 app 在前台调 `launchApp(CellRebel)` → 成功（ConfirmStartActivity 闪现后放行）
- Fake GPS 在前台调 `launchApp(CellRebel)` → 永久拦截
- Fake GPS 自身的启动从不受限制

### 推测根因
MIUI 后台启动管控：从 AccessibilityService 调 startActivity，当前前台不是自己的 app 时拦截更严格。可能的解决方向：
- 确认 CellRebel 的 MIUI 权限全部开启（自启动、后台弹出界面、省电无限制）
- 启动前先切回自己的 app 再发 intent（但 goBack 导致回到自己 app 后 intent 仍失败）
- 通过 AccessibilityService 自动点击 ConfirmStartActivity 的"允许"按钮
- 使用 `am start` shell 命令替代 startActivity

## 待解决事项
1. **MIUI App 切换** — 上述核心问题
2. **Fake GPS 坐标输入** — 搜索框输入无结果，用户计划后续解决
3. **启用完整流程** — AutomationEngine.kt 中 Fake GPS 阶段当前被注释跳过

## 关键文件
- `automation/CellRebelHandler.kt` — CellRebel 交互逻辑
- `automation/FakeGpsHandler.kt` — Fake GPS 交互逻辑
- `automation/AutomationEngine.kt` — 主循环编排（Fake GPS 阶段已注释）
- `automation/AccessibilityBridge.kt` — 底层 A11y 封装（含 goHome/goBack/dispatchTap）
- `automation/NodeFinder.kt` — 无障碍树节点查找工具

## 当前代码状态
- launchApp flags 恢复为仅 `FLAG_ACTIVITY_NEW_TASK`
- FakeGpsHandler 无 goHome/goBack（正常工作）
- CellRebelHandler.launchAndWaitForForeground 直接 launchApp + 轮询重试
- AutomationEngine Phase 1/2 (Fake GPS) 被注释跳过

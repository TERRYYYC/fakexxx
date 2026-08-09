# [F001] 字段模型与传输层：修复 23/86 覆盖瓶颈 + 快照-差量高保真

> 状态：已确认方案，待实施
> 设计确认：@fable-5（SpoofConfig 原设计者）已确认降级为语义层
> Review + 验证：@kimi (k3)

## 背景（moto g54 5G / Android 15 真机确立）

- **ContentProvider 走不通**：Android 11+ 软件包可见性过滤，目标 App 无法解析 FakeGps 的 Provider
  （logcat: `Failed to find provider info for name.caiyao.fakegps.data.AppInfoProvider`）
- **正解已落地**：迁移到 XSharedPreferences（world-readable 文件 + Vector safe-zone 重定向，绕开包可见性）。
  读取端（MainHook）+ 写入端（ConfigPrefsSync）已实现，真机验证通过：
  受控探针读到伪造坐标，`isMock=false`

## 待修复：三个字段模型缺陷（`scripts/test-hook.sh` 实测）

| # | 缺陷 | 后果 |
|---|---|---|
| **B1** | `Snapshot.hasLteCell()` 仅判 `ci != null` | 只填 `tac` ⇒ 判定"未配置 LTE" ⇒ 所有 LTE hook 静默 no-op |
| **B2** | 传输层 **23 字段** / 数据表 **87 列** | `mcc/mnc/lac/cid/operator_name` 等根本传不过去（ContentProvider→XSharedPreferences 迁移引入的回归） |
| **B3** | 蜂窝 hook **整组替换** | 未配置字段被填默认值而非透传 ⇒ 物理上不可能的小区组合 ⇒ 强检测特征 |

**根因**：字段定义散落 6 处 —— 表 87 / Snapshot 86 / SpoofConfig 23 / ProfileEntity 87 / FieldSpec 7+6。
每加一字段要改 4–6 处，必然漂移。**B2 不是"忘了加"，是结构问题。**

实测证据（配置 tac 却观测到真实网络）：
```
lte:      {"tac":26999, "ci":28378431, "pci":53, "mccString":"255", "mncString":"03"}
operator: {"networkOperatorName":"UA-KYIVSTAR", "networkOperator":"25503"}
⚠️ ci/mcc/mnc/operator 均为真实值且未被配置 — 蜂窝伪造未生效
```

## 方案（已确认）

### 1. 一份清单，两个数据源（解决 B2）
`Snapshot.fromCursor` 已含完整 86 字段映射。抽出 `FieldSource` 取值抽象，让同一份清单同时服务 Cursor 与 JSON：
```java
interface FieldSource { Integer getInt(String); Double getDouble(String); ... }
static Snapshot from(FieldSource src) { /* 86 行，由 fromCursor 原样移入 */ }
fromCursor(c)   → from(new CursorSource(c))
fromJson(json)  → from(new JsonSource(json))
```
写侧通用遍历所有列（零逐字段代码），读侧复用同一清单。
**覆盖率 23→86，新增手写字段 0，真相源不增反减。**

### 2. 数据模型 = 快照 + 修改标记（解决 B3，并完成"设置界面默认填入所有信息"）
```
读系统真实值 → 灌入 draft（界面显示完整现状）
             → 记录哪些字段被用户改过
             → hook 时：改过的用配置值；没动的透传实时真实值
```
优于纯快照：未修改字段跟随现实变化，信号不会"冻住"（信号永不波动本身即检测特征）。

### 3. SpoofConfig 降为语义/校验层
不再位于传输关键路径，转而承载 typed 语义：字段依赖组、取值范围、场景一致性
（服务小区+邻区协同、RSRP/RSRQ/SINR 联合分布、坐标↔MCC/MNC 地理自洽）。
**覆盖率问题用通用映射解，语义问题用类型化 schema 解，两者不再互相绑架。**

### 4. 谓词语义修正（解决 B1）
`hasLteCell()` 改为"组内任一字段非空"，与 `NULL = 透传` 一致。

## 必须守住的三条不变量（@fable-5 作为原设计者要求）

| 不变量 | 为什么不能丢 |
|---|---|
| **NULL = 透传** | 项目哲学根基；"仅非 NULL 入 fields map"已守 |
| **last-known-good** | 解析失败不回退真实数据，防测试中泄露真实环境 |
| **schemaVersion 校验 + 配置指纹** | ⚠️ 传输从 typed 转通用 map 后**须确认未丢失**，验证重点 |

## 安全边界（已确认）

hook 仅作用于**应用进程内的读取类 API**（`getAllCellInfo` / `getNetworkOperator` / `getDbm` …），
不触及 modem / RIL / system_server。因此：手机上网、基站切换、通话均不受影响；
系统自身看到的仍是真实数据；仅 Vector 作用域内的 App 看到伪造值。

> 注：Android 未为蜂窝提供任何等价于位置 mock provider 的官方机制，真机上 hook 是唯一路径；
> 且 per-app hook 比系统级 mock 更安全（不污染系统网络决策）。

## 实施顺序

1. `FieldSource` 抽象 + `Snapshot.from(...)`（纯重构，行为不变）
2. 写侧通用列遍历 + 读侧 `fromJson` → 修复 B2
3. 谓词语义修正 → 修复 B1
4. 快照 + 修改标记 + 逐字段透传 → 修复 B3
5. `scripts/test-hook.sh` 回归：配置**区别于真实网络**的蜂窝值（如 `ci=99999`）验证逐字段生效

每步均可由测试脚本独立验证。

## 验证工具（已就绪）

- `scripts/test-hook.sh` — 端到端逐字段验证，零手动操作，可精确定位断链层
- `app/.../probe/HookProbe.kt` — 读回探针，输出真实 App 可观测值（JSON）

## Review

实施完成后由 **@kimi (k3)** 进行代码 review + 真机验证（跨族 review，家规禁止 self-merge）。

## 相关

- 设计文档：`insight/docs/design/F001-field-model-transport-design.md`
- 关联 issue：`insight/docs/features/F001-issue-gms-fused-location-gap.md`（Maps 蓝点 GMS 跨进程缺口，暂缓）
- 测试环境：moto g54 5G (cancunf/RETEU) · Android 15 · Magisk 30.7 · Vector v2.0

---

## 遗留调查更正：空列表来自锁屏 AppOps，不是设备/ROM 限制（2026-07-27）

上一版把 `getAllCellInfo()` 空列表归因为“设备/ROM 不向此 App 提供实时 modem
数据”。该结论遗漏了 Android 的 AppOps 前台门控，已被解锁对照实验推翻。

### 更正后的证据链

- PackageManager 显示 `ACCESS_FINE_LOCATION: granted=true`，但 AppOps 是
  `FINE_LOCATION: foreground`；锁屏/Dozing 时 logcat 明确记录
  `LocationAccessPolicy ... app-ops permission is specifically denied`
- 同一设备、同一进程执行 `KEYCODE_WAKEUP + wm dismiss-keyguard` 后立即读到 7 个真实小区
- 因此 `granted=true` 不等于调用时 AppOps 放行；原来的 8 项排除法漏查了这层

测试脚本现在把“设备已唤醒且 keyguard 未显示”作为蜂窝验证前置条件，避免再把权限时态
误判为 ROM 行为。

### 随后暴露的真实实现缺陷

1. Android 15 上不存在代码原先调用的 6 参数 `CellIdentityLte` 构造函数，异常被吞后
   会把真实小区列表替换为空列表。
2. `CellBaseline.from()` 通过已经被本模块 hook 的 getter 读取所谓“真实值”，导致
   `tac`、信号强度等基线被当前配置/波动逻辑污染。

修复以设备运行时实际存在的构造函数形状为准，并在基线提取的同步调用栈内临时旁路
CellInfo/CellIdentity/CellSignalStrength getter hook；离开提取栈后立即恢复正常伪造。

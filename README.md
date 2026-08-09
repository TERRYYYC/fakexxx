# FakeGPS - 设备信息伪装模块

一个基于 Xposed 框架的全方位设备信息伪装模块。拦截 80+ 系统 API 调用，覆盖定位、蜂窝网络、WiFi、IP 网络四大层面，向目标应用呈现一致的虚拟设备环境。

**运行要求：** LSPosed / Xposed 框架 | Android 7.0+（API 24）| Target API 34

## 功能一览

### 定位伪装
- GPS 坐标（经度、纬度、海拔、速度、方向、精度）
- `LocationManager.getLastKnownLocation()`、`getCurrentLocation()`（API 30+）
- Google Play Services `FusedLocationProviderClient`（经 `LocationServices` 工厂运行时发现的 Task（success-listener 包装）与 LocationListener 注入；LocationCallback 与 PendingIntent（`LocationResult` Intent 提取）已实现但尚无真实消费者验证，见 `docs/experiments/address-hook-cadence/fused-discovery-pilot.md`）
- GPS 卫星状态屏蔽

### 蜂窝网络伪装
- **小区标识**：GSM、WCDMA、LTE、NR/5G（MCC、MNC、LAC、CID、TAC、PCI、ARFCN 等）
- **信号强度**：支持随机波动（RSSI、RSRP、RSRQ、SINR、CQI、时间提前量）
- **邻区小区**：通过 JSON 配置独立的标识和信号值
- **PhysicalChannelConfig**（API 29+）：频段、带宽、信道号
- **双卡隐藏**：拦截 SubscriptionManager

### 运营商与网络状态
- 运营商名称/代码、SIM 运营商、国家代码
- 网络类型（LTE/NR 等）、漫游状态、电话类型
- 服务状态、数据状态、数据活动、网络类型覆盖

### WiFi 伪装
- SSID、BSSID、RSSI、频率、连接速率（TX/RX）、信道
- WiFi 标准（802.11n/ac/ax）、安全类型、MAC 地址、IP
- 扫描结果隐藏、WiFi 启用状态

### IP 与连接
- 本地 IPv4/IPv6、DNS（主/备）、网关、子网掩码
- 连接类型、接口名称
- DhcpInfo 字段级覆盖（网关、掩码、DNS、IP）
- LinkProperties 路由/地址替换、NetworkInterface 伪装

## 架构

```
地图界面 (OSMDroid)
    |
    v
ProfileEditorFragment  ---  FieldSpec（80+ 字段元数据）
    |
    v
ProfileDao  ---  SQLite（temp 表，80+ 可空列）
    |
    v                        空列 = 透传（不伪装）
ContentProvider  --->  Snapshot.fromCursor()  --->  AtomicReference<Snapshot>
                                                         |
                                                         v
                                                   HookUtils（16 个 Hook 段）
                                                   A. 定位
                                                   B. 小区标识
                                                   C. 小区标识 Getter
                                                   D. 信号强度
                                                   E. 电话管理器
                                                   F. 服务状态
                                                   G. WiFi
                                                   H. 网络连接
                                                   I. PhoneStateListener
                                                   J. GPS 状态
                                                   K. LocationManager 构造
                                                   L. TelephonyCallback
                                                   M. IP / LinkProperties
                                                   N. PhysicalChannelConfig
                                                   O. SubscriptionManager
                                                   P. 融合定位 (GMS)
```

**核心设计原则：** 数据库每一列均可为空。`NULL` 表示"不伪装此字段"（透传真实硬件值），只有显式设定的值才会被拦截替换。

## 安装

### 前提条件
- 已 Root 的 Android 设备（推荐 Magisk）
- 已安装 LSPosed 或 EdXposed 框架
- Android 7.0+（API 24）

### 步骤
1. 构建 APK（参见[构建](#构建)）或从 Releases 下载
2. 安装 APK 到设备
3. 打开 LSPosed Manager，启用模块
4. 在模块作用域中勾选目标应用
5. 重启设备
6. 打开应用，在地图上点击位置，配置伪装档案

## 使用指南

### 快速开始 — 仅伪装定位
1. 打开应用，浏览地图（OpenStreetMap）
2. 在地图上点击目标位置
3. 点击右下角 **+** 按钮 — 打开档案编辑器，经纬度已自动填入
4. （可选）填写海拔、速度、方向、精度
5. 点击 **保存** — 完成，目标应用现在看到的是虚拟定位

### 完整档案 — 全字段配置
1. 打开档案编辑器（地图点击 + 按钮，或在收藏列表中点击已有档案）
2. 展开 14 个分类卡片中的任意一个：

| 分类 | 关键字段 |
|------|---------|
| 定位 | 经度、纬度、海拔、速度、方向、精度 |
| 小区标识 - GSM | MCC、MNC、LAC、CID、ARFCN、BSIC |
| 小区标识 - WCDMA | PSC、UARFCN |
| 小区标识 - LTE | TAC、CI、PCI、EARFCN、带宽 |
| 小区标识 - NR/5G | NCI、NRARFCN、NR PCI、NR TAC |
| 信号 - GSM | RSSI、BER、时间提前量 |
| 信号 - WCDMA | RSSI、RSCP、Ec/No |
| 信号 - LTE | RSSI、RSRP、RSRQ、SINR、CQI、时间提前量 |
| 信号 - NR/5G | SS-RSRP/RSRQ/SINR、CSI-RSRP/RSRQ/SINR |
| 信号波动 | 启用/禁用、波动范围（dB） |
| 运营商与网络 | 运营商名称、网络类型、漫游、SIM 信息 |
| 服务状态与信道 | 服务状态、数据状态、频段、PCC 带宽 |
| WiFi | SSID、BSSID、RSSI、频率、MAC、IP、标准 |
| IP 与连接 | IPv4、IPv6、DNS、网关、子网掩码、接口 |

3. 每个字段都有输入提示和有效范围（如 RSRP: "-140 ~ -44 dBm"）
4. 留空的字段将透传真实设备值
5. 卡片标题旁的角标显示 "3/6"（6 个字段中 3 个已配置）
6. 点击 **保存**

### 档案管理
- 侧边栏 **收藏** 列出所有已保存档案
- **点击** 档案进入编辑
- **长按** 删除档案
- **清除全部** 移除所有档案

### 邻区小区（高级）
IP 与连接分类中的"邻区小区 JSON"字段接受 JSON 数组：

```json
[
  {
    "type": "lte",
    "mcc": 460,
    "mnc": 0,
    "ci": 12345678,
    "pci": 100,
    "tac": 1234,
    "rsrp": -95,
    "rsrq": -8,
    "sinr": 20
  },
  {
    "type": "gsm",
    "lac": 5678,
    "cid": 9012,
    "rssi": -75
  }
]
```

支持类型：`gsm`（rssi、ber、ta）、`lte`（rssi、rsrp、rsrq、sinr、cqi、ta）、`wcdma`（rssi、rscp、ecno）。

邻区小区在 `getAllCellInfo()` 中显示为未注册（`isRegistered()=false`）。

### 信号波动
启用"信号波动"并设置范围（如 3 dB），所有信号强度读数将叠加随机抖动，使伪装信号对检测算法更加逼真。

## 构建

```bash
# 克隆仓库
git clone https://github.com/TERRYYYC/FakeGps-test.git
cd FakeGps-test

# 构建（需要 JDK 17+ 和 Android SDK）
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug

# 输出：app/build/outputs/apk/debug/app-debug.apk
```

### 构建要求
- JDK 17 或 21（推荐 Android Studio 内置 JBR）
- Android SDK，含 Platform 34
- Gradle 8.9（已含 wrapper）
- AGP 8.5.2、Kotlin 2.0.0

## 技术细节

### Hook 注册机制
所有 Hook 在 `HookUtils.registerAllHooks()` 中通过 `safeHook()` 包装注册 — 每个段独立 try-catch，单个失败不会阻塞其他段。

### 具体类运行时发现模式
对于监听器/回调类 API（`PhoneStateListener`、`TelephonyCallback`、`CellInfoCallback`），在注册调用时（`listen()`、`registerTelephonyCallback()`、`requestCellInfoUpdate()`）拦截，运行时发现具体子类并对其 Hook。使用 `ConcurrentHashMap` 去重，防止重复 Hook。

### 邻区小区旁路机制
邻区小区对象使用基于 `WeakHashMap` 的旁路集合（`NEIGHBOR_BYPASS`）。全局 getter Hook 检查此集合，对邻区对象跳过服务小区值覆盖，保留其构造时设定的值。

### 线程安全
- `AtomicReference<Snapshot>`：ContentProvider 更新与 Hook 读取之间的无锁配置共享
- `ConcurrentHashMap`：运行时方法去重（HOOKED 集合）
- `synchronized WeakHashMap`：邻区旁路集合

## 项目结构

```
app/src/main/java/name/caiyao/fakegps/
  hook/
    MainHook.java          # Xposed 入口，AtomicReference<Snapshot>
    HookUtils.java         # 16 个 Hook 段（A-P），约 1900 行
    Snapshot.java          # 来自数据库游标的不可变配置快照
  ui/
    activity/
      MainActivity.java    # 地图 + 侧边栏导航
    fragment/
      ProfileEditorFragment.java  # 80 字段数据驱动编辑器
      CollectionFragment.java     # 档案列表
    profile/
      FieldSpec.java       # 字段元数据（14 分类，80+ 字段）
  dao/
    ProfileDao.java        # 全列 CRUD，null=透传
    TempDao.java           # 旧版 DAO（位置列表操作）
  data/
    DbHelper.java          # SQLite 架构（80+ 可空列）
    AppInfoProvider.java   # ContentProvider，跨进程配置传递
```

## 许可证

基于原始 FakeGPS 项目。详见源文件中的许可证信息。

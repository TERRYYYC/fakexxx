---
feature_ids: []
topics:
  - wireless-expansion
  - cellrebel
  - bluetooth
  - nfc
  - research
doc_kind: research
created: 2026-09-07
status: decision-draft
---

# 无线扩展调研：蓝牙 / NFC 消费面（决策稿）

> 结论先行：**蓝牙 = 暂缓（watch 项）**；**NFC = 不做**。
> 针对本项目当前的对抗目标 `com.cellrebel.mobile`，两者在其现行构建上
> **均为零消费面**（无权限声明、无可达代码引用），hook 它们收益为零；
> 但 CellRebel 的母公司 Ookla 在其隐私政策中明确披露采集"邻近 BLE 设备"，
> 蓝牙方向存在家族级趋势信号，故蓝牙列为暂缓+触发条件，NFC 直接不做。
> 本文档只调研，不含任何 hook 代码变更。

## 1. 调研问题

网络质量测量类 app（CellRebel 及同类：测速 / 连接测试 / 信号分析）是否会消费：

1. **蓝牙面**：`BluetoothAdapter.getName()` / `getAddress()`、
   `startScan` / `getBluetoothLeScanner()` 的扫描结果（设备名 / MAC / RSSI）；
2. **NFC 面**：`NfcAdapter` 状态（`getDefaultAdapter` / `isEnabled`）。

若消费成立，则千网游的 hook 层需要伪造这些面，否则真实设备指纹会穿透；
若不消费，hook 属于无收益的扩展面。

## 2. 一手证据（本机静态分析，未触碰设备）

样本：`com.cellrebel.mobile` versionName **1.9.3-full**（versionCode 363），
`base.apk` 18,371,219 bytes，sha256
`24ea13dff94ed9229f0cf68044941c96d78119c4b5e302fad63c3c0fa7659595`。
来源与提取方法见仓外诊断记录 `~/Desktop/coding/mco06-diag-cellrebel-apk/marker-strings-hit.txt`
（2026-08-30，read-only pm path + adb pull，app 从未被启动/配置）。

### 2.1 合并清单权限面（aapt dump permissions，build-tools 37.0.0）

声明的全部权限（逐字）：

```
android.permission.INTERNET
android.permission.ACCESS_FINE_LOCATION
android.permission.ACCESS_COARSE_LOCATION
android.permission.READ_PHONE_STATE
android.permission.ACCESS_WIFI_STATE
android.permission.ACCESS_NETWORK_STATE
android.permission.WAKE_LOCK
com.google.android.c2dm.permission.RECEIVE
com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.FOREGROUND_SERVICE
com.cellrebel.mobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

**结论 A1：无任何蓝牙权限**（无 `BLUETOOTH` / `BLUETOOTH_ADMIN` /
`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`），
**无 `android.permission.NFC`**。

含义（依据 Android 官方权限模型，见 §3）：

- 无 `BLUETOOTH_SCAN`/`CONNECT`（API 31+ 运行时权限）或旧版
  `BLUETOOTH`+定位授权：`BluetoothAdapter.getName/getAddress` 返回受限值
  （`getAddress` 自 6.0 起对无权限 app 返回占位 MAC `02:00:00:00:00:00`），
  扫描返回空/拒绝 —— **扫描面天然不可消费**。
- 无 `NFC`：`NfcAdapter.getDefaultAdapter()` 与 `isEnabled()` 直接要求
  `android.permission.NFC`，**连"开关状态"都读不到**。

### 2.2 DEX 代码面（unzip + strings/dexdump，本机）

对 `classes.dex`（9,077,736 B）与 `classes2.dex`（6,330,320 B）的字符串池
逐一扫描：

- `android/bluetooth/*` 类型描述符：**classes.dex 0 个，classes2.dex 0 个**；
- `android/nfc/*`：classes.dex 恰好 **1 个**池条目 `Landroid/nfc/NfcManager;`
  （dexdump 全量反汇编 classes.dex 中未发现可达引用；classes2.dex 无）——
  疑似 R8 收缩后的残留池条目或某库的死代码引用，**未证实其实际执行**；
- 对照组（证明扫描方法有效）：同一 dex 池中确有
  `Landroid/net/wifi/WifiManager;` / `WifiInfo;` / `ScanResult;` 与 4 个
  `android/telephony/*` 类型 —— 即该 app 的真实消费面就是
  WiFi 连接信息 + 扫描结果 + 电话状态，与其"网络测量"定位一致；
- 第三方库组成（`Lcom/google/ 2800`、`Lcom/cellrebel/ 1206`、
  `Lcom/facebook/ 886`、`Lcom/airbnb/`(lottie)、
  `com.akexorcist.roundcornerprogressbar`(纯 UI 库) 等）中
  **不含任何蓝牙/NFC SDK**。

**结论 A2：该构建不存在蓝牙消费代码；NFC 仅有一个不可达的池条目。**

## 3. 二手依据（公开资料 / API 文档）

| # | 依据 | 来源 |
|---|------|------|
| B1 | API 31 起 `BLUETOOTH_CONNECT/SCAN/ADVERTISE` 为运行时权限；target≤30 的 app 沿用 legacy `BLUETOOTH`+定位授权模型；无权限时蓝牙 API 受限 | [Android Developers: Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) |
| B2 | `BluetoothAdapter.getAddress()` 对无权限 app 返回固定占位 `02:00:00:00:00:00`（Android 6.0 起）；扫描（`startScan`/`BluetoothLeScanner`）需要 `BLUETOOTH_SCAN`（API 31+）或定位权限（≤API 30） | 同上 + [Manifest.permission 参考](https://developer.android.com/reference/android/Manifest.permission) |
| B3 | `NfcAdapter.getDefaultAdapter(Context)` 与 `isEnabled()` 均要求 `android.permission.NFC`（normal 级，但必须声明） | [NfcAdapter API 参考](https://developer.android.com/reference/android/nfc/NfcAdapter) + [AOSP NfcAdapter.java](https://android.googlesource.com/platform/frameworks/base/+/cccf01d/core/java/android/nfc/NfcAdapter.java) |
| B4 | **Ookla 收购 CellRebel** —— 两者同属一个数据家族 | [Ookla: Ookla Acquires CellRebel](https://www.ookla.com/articles/ookla-acquires-cellrebel) |
| B5 | Ookla（Speedtest）隐私政策披露采集"……WiFi signals or **Bluetooth Low Energy devices in your proximity**"—— 家族旗舰产品明确消费 BLE 邻近面 | [Ookla Privacy Policy](https://www.speedtest.net/about/privacy) |
| B6 | WiFi 面为什么消费：SSID/BSSID/RSSI/扫描结果属"邻近设备标识"，Android 以定位权限门控（Android 9 起扫描限流 4 次/2 分钟） | [Wi-Fi scanning overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan) |
| B7 | CellRebel 隐私政策原文（本次抓取 502，未能核对其中是否提及蓝牙/NFC 数据类目） | https://www.cellrebel.com/privacy_policy —— **未证实** |
| B8 | Exodus Privacy 对 com.cellrebel.mobile 的第三方权限/追踪报告（本次不可达）—— **未证实** | reports.exodus-privacy.eu.org |

## 4. 结论

### 4.1 蓝牙：**暂缓（watch 项）**

- **现状零收益**：结论 A1+A2 —— CellRebel 现行构建既无蓝牙权限也无蓝牙
  代码，hook `BluetoothAdapter` 对它不产生任何可观测差异。做 hook 等于
  为零消费面支付 schema、验收、维护三项成本。
- **趋势信号真实存在**：B4+B5 —— 母公司 Ookla 的旗舰产品明确披露采集
  邻近 BLE 设备。若 CellRebel 后续构建向 Speedtest 的数据模型靠拢
  （BLE 邻近 = 室内定位/指纹增强的常见手法），蓝牙面会从零变非零。
- **触发条件（满足任一即应升级为"做"）**：
  1. 新版 CellRebel APK 的合并清单出现任何 `BLUETOOTH*` 权限；或
  2. DEX 池出现可达的 `android/bluetooth/*` 类型引用；或
  3. 组合目标新增其他明确消费蓝牙的测量类 app。
  复检方法即 §2 的两条本机静态命令，5 分钟可完成，无需设备。
- 未证实项如实标注：CellRebel 自家隐私政策（B7）与第三方报告（B8）
  本次未能获取，不影响 A1/A2 的一手结论。

### 4.2 NFC：**不做**

- 结论 A1+A2：CellRebel 无 `NFC` 权限、无可达 NFC 代码；B3 表明无权限时
  连 `isEnabled()` 都读不到。网络测量类 app 消费 NFC 无已知动机
  （NFC 属标签/支付/近场配对面，与网络质量测量正交）。
- 成本不对称：伪造 `NfcAdapter` 状态需要与真机硬件状态保持一致，而验收
  探针在无 NFC 硬件/无权限环境下观测值恒定，做 hook 只增加一致性维护面，
  没有任何消费方可对齐。
- 若未来触发（可能性极低），最小面仅为：`NfcAdapter.getDefaultAdapter`
  非空性 + `isEnabled` 布尔 → 字段 `nfc_enabled`。

### 4.3 若蓝牙升级为"做"——建议的字段清单与 hook 面（仅设计，不实现）

字段命名沿用本仓 `wifi_*` 的 DB 列名风格（flat field map，schema v4 → 需
bump），hook 面以 `HookUtils.hookWifi` 组为模板：

| profile 字段 | hook 面（hookBluetooth 组） | 备注 |
|---|---|---|
| `bt_name` | `BluetoothAdapter.getName()` | String；目标 app 需 `BLUETOOTH_CONNECT`(31+) / legacy `BLUETOOTH`(≤30) 才能读到 |
| `bt_mac` | `BluetoothAdapter.getAddress()` | 必须 hook：无权限时框架返回占位 `02:00:00:00:00:00`（B2），不 hook 则"有/无权限"两种 app 观测不一致 |
| `bt_scan_enabled` | `BluetoothAdapter.startScan` / `BluetoothLeScanner().startScan` 结果改写（空列表 / 白名单设备名+MAC+RSSI） | 目标 app 需 `BLUETOOTH_SCAN`(+≤30 定位)；扫描结果含邻近真实设备名/MAC，是主要指纹面 |
| `bt_le_scanner_present` | `BluetoothAdapter.getBluetoothLeScanner()` 非空性 | 权限缺失时框架返回 null，hook 需与 `bt_scan_enabled` 保持一致 |
| `nfc_enabled`（可选，默认不做） | `NfcAdapter.getDefaultAdapter` / `isEnabled` | 需目标 app 声明 `NFC` 权限（B3） |

一致性约束（同 hookWifi 的"状态互相一致"纪律）：`bt_mac`/`bt_name` 与
扫描结果里的设备标识必须同源；`getBluetoothLeScanner` 非空性与
`bt_scan_enabled` 不得矛盾。验收探针需为异步扫描面设计确定性采样
（参考 `HookProbe` 的 callback latch 模式），扫描限流（B6 的 WiFi 数据
点对 BLE 同样存在的同类机制）决定了 exact 值验证只能在隐藏开关语义下做，
与 `wifi_hidden → scanResultsCount=0` 的"确定性区间"同构。

## 5. 复现命令（审计用）

```bash
AAPT=~/Library/Android/sdk/build-tools/37.0.0/aapt
"$AAPT" dump permissions /Users/terry/Desktop/coding/mco06-diag-cellrebel-apk/base.apk
unzip -o -q /Users/terry/Desktop/coding/mco06-diag-cellrebel-apk/base.apk "classes*.dex" -d /tmp/cr
strings -a /tmp/cr/classes.dex  | grep -cE "^Landroid/bluetooth/"   # 期望 0
strings -a /tmp/cr/classes2.dex | grep -cE "^Landroid/bluetooth/"   # 期望 0
strings -a /tmp/cr/classes.dex  | grep -E "^Landroid/(nfc|net/wifi)/" | sort -u
```

## 6. Sources

- [Bluetooth permissions — Android Developers](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [NfcAdapter — Android Developers 参考](https://developer.android.com/reference/android/nfc/NfcAdapter)
- [AOSP NfcAdapter.java](https://android.googlesource.com/platform/frameworks/base/+/cccf01d/core/java/android/nfc/NfcAdapter.java)
- [Wi-Fi scanning overview — Android Developers](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [Manifest.permission — Android Developers](https://developer.android.com/reference/android/Manifest.permission)
- [Ookla Acquires CellRebel](https://www.ookla.com/articles/ookla-acquires-cellrebel)
- [Ookla Privacy Policy](https://www.speedtest.net/about/privacy)
- [CellRebel Mobile Network Guide — Google Play](https://play.google.com/store/apps/details?id=com.cellrebel.mobile&hl=en_US)
- 未证实（本次不可达）：CellRebel 隐私政策原文（502）、Exodus Privacy 报告

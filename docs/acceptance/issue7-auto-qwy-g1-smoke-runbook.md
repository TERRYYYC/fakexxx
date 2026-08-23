---
feature_ids: [7]
topics: [acceptance, runbook, smoke, device, environment-control-v1]
doc_kind: runbook
created: 2026-08-24
source_pr: 36
exact_head: 280eb021d501c489dedb6eabedbc42cbf40ee6c3
---

# G1 冒烟 Runbook — Auto ↔ 千网游 Environment Control v1 真机前置（PR #36）

> **这是 runbook，不是测试，也不是验收证据。**
> 它回答 operator 拿到真机后的一连串问题：装哪个 APK / 点哪个 Activity /
> 千网游侧要不要先 approve pairing / 探针吐一屏字段哪些算通过 / 看到
> `CLEANUP UNSAFE — DEVICE MAY STILL BE IN MOCK STATE` 该做什么 / 用哪个地址 /
> 记录的人拿什么当基线。
>
> **文档存在不构成执行证据。** 冒烟结果必须由独立记录猫按 §11 记入证据，
> 并按 `docs/acceptance/` 既有 evidence-registration 约定提供 `reportDigest`
> 指向原始报告字节的 SHA-256。

## 0. 一句话

在一台真机上装两个 **debug** APK，按 §6 顺序跑通「**种子数据 → 配对批准 →
握手 → 完整 §6.7 loop → 清理验证**」，用探针在屏幕和 logcat 吐出的**可核验字段**
（不是"看起来正常"）判定每步 PASS/FAIL。

冒烟只回答一个问题：**这两只 app 在真机上能不能看见并信任对方、并让设备真的动起来。**
这是 #7 唯一从未被问过的问题（`HandshakeProbeActivity` KDoc，§7 walking skeleton）。

## 1. 范围（这次冒烟覆盖什么）

| 探针 | 所在 app（debug） | 回答的问题 |
|---|---|---|
| `HandshakeProbeActivity` | Auto | 双端能否 `bindService` + `discover()` + 通过 §6.5 pairing |
| `PairingApprovalActivity` | 千网游 bench | operator 能否列出并**精确**批准一个 caller 主元（applicationId + signerDigest） |
| `FullLoopProbeActivity` | Auto | 完整 §6.7 loop：discover → preflight → apply → observe → release → completeAndAdvance → 独立验证 |
| `MockProviderAcceptanceActivity` | 千网游 bench | 种子 `.bench` 隔离调度数据 + 保证 trap cleanup（shell-only，DUMP 权限） |

涉及文件即 PR #36 的 8 个文件 + 两个已存在的 `src/debug` 表面：
`EnvironmentControlClient.kt` / 两个 probe Activity / `ProviderRuntime.kt` /
`PairingApprovalActivity.kt` / 两个 debug manifest / `DebugAcceptanceManifestGuardTest.kt`，
以及种子 seam `MockProviderAcceptanceActivity`。

## 2. 显式不覆盖（G2 边界，防止假预期）

**本次冒烟不覆盖、也不因跑通就宣称可用：**

- **10 地址 × 可信次数矩阵**（A+ device matrix，独立验收，`docs/acceptance/a-plus-device-matrix.md`）
- **崩溃恢复**（RELEASE_INCOMPLETE / 进程重启 / generation 断裂 / §8.4 恢复分流）
- **撤销双侧**（qwy 撤销 caller / Auto 撤销 provider，§6.5 revoke）
- **version skew**（v1 wire 尚未冻结；探针只**报告** `!! PROTOCOL SKEW`，不测 skew 行为）
- **exact-build provenance 登记**（§10 ledger 行）
- **production / release 构建**（千网游生产包 `name.caiyao.fakegps` 全程不动）
- **既有 hook acceptance harness**（`HookAcceptanceActivity` / `RUN_HOOK_ACCEPTANCE` 签名门禁）——另一套验收，不在本文
- **任何 G1/G2/G3 放行判定**——本文只产出冒烟结果，不放行任何 gate

## 3. 前置条件

### 3.1 设备
- 一台可 `adb` 的真机（非模拟器；§7 的验收对象是 PackageManager 与 Binder，JVM lane 到不了）。
- 开启 USB 调试，`adb devices` 显示 `device` 状态（非 `unauthorized`）。
- API ≥ 26（Auto `minSdk=26`）；建议 API 30+（package visibility 与 mock_location app-op 行为都要真实）。
- **开发设置 → 选择模拟位置信息应用（Select mock location app）= `千网游·测试`**
  （包名 `name.caiyao.fakegps.bench`，debug `app_name` 资源）。apply 步骤通过
  `LocationManager.setTestProvider*` 移动设备，必须有 `android:mock_location` app-op；
  等价命令行 `adb appops set name.caiyao.fakegps.bench android:mock_location allow`。
  千网游主 manifest 已声明 `ACCESS_MOCK_LOCATION`。
- 千网游 bench 是 **Xposed 模块**（`xposedmodule=true`）。若设备有 LSPosed：
  启用 `name.caiyao.fakegps.bench` 模块并配置作用域，让 HOOK 投递链路健康（影响 apply 的
  verification 字段，见 §7.4）。**握手与配对步骤不依赖 Xposed。**

### 3.2 工具
- JDK 17+、Android SDK（`ANDROID_HOME`）、`adb`、`gh`（无需——推代码用 git）。
- 两只 app 各自是独立 Gradle root（各自带 `gradlew`），需分别构建。

## 4. 构建

```bash
# Auto（CellRebel Auto，applicationId=com.example.cellrebelauto，debug 无后缀）
cd apps/cellrebel-auto && ./gradlew :app:assembleDebug
# 产物：apps/cellrebel-auto/app/build/outputs/apk/debug/app-debug.apk

# 千网游（applicationId=name.caiyao.fakegps，debug 后缀 .bench → name.caiyao.fakegps.bench）
cd apps/qianwangyou && ./gradlew :app:assembleDebug
# 产物：apps/qianwangyou/app/build/outputs/apk/debug/app-debug.apk
```

**构建可重复性要求**：记录两只 APK 各自对应的 git commit（`git rev-parse HEAD`）。
同一 checkout 的同一个 commit 构建两只 app 是最干净基线；若分叉，两条 SHA 都要记（§11）。

## 5. 安装（顺序重要）

```bash
# 先装 provider（千网游 bench），再装 client（Auto）——顺序只影响后续解释，不改变行为
adb install -r apps/qianwangyou/app/build/outputs/apk/debug/app-debug.apk      # → name.caiyao.fakegps.bench
adb install -r apps/cellrebel-auto/app/build/outputs/apk/debug/app-debug.apk  # → com.example.cellrebelauto
```

- 用 `-r`：`name.caiyao.fakegps.bench` 与生产包 `name.caiyao.fakegps` 是**两个独立安装**，
  互不打扰；`-r` 保留已装数据（千网游释放/调试共享同一把 keystore，`-r` 无需卸载）。
- 装完确认：
  ```bash
  adb shell pm list packages | grep -E "cellrebelauto|fakegps"
  # 必须出现：com.example.cellrebelauto 和 name.caiyao.fakegps.bench
  # （生产包 name.caiyao.fakegps 可选——未装也不影响冒烟，bench 优先于生产被 bind）
  ```
- Auto 的 `<queries>` 已声明对 `name.caiyao.fakegps` 与 `name.caiyao.fakegps.bench` 的可见性
  （API 30+ bind 前提），无需手动配置。

## 6. 执行步骤

> 每次启动前清空 logcat 再抓，判据基于**当次**输出：
> ```bash
> adb logcat -c
> adb logcat -d ECHandshakeProbe:V ECFullLoop:V ECPairingApproval:V MockProviderAcceptance:V *:S
> ```

### 6.0 基线：种子 `.bench` 调度数据

完整 loop 需要「当前调度项 + 该 profile 的坐标」（§6.7 的 apply 从**当前调度项**解析坐标，
KB-8：千网游独占坐标）。`prepare_kyiv` 清空 bench 全部 profile、种入一个
"Kyiv acceptance"（50.4501, 30.5234, alt 179.0）、把投递模式设为 HOOK、
`mockProviderCleanupRequired=false`：

```bash
adb shell am start -n name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity --es command prepare_kyiv
```

- 判据（§7.1）：logcat `MockProviderAcceptance` 出 `READY command=prepare_kyiv`。
- **种子 seam 只动 `.bench` 数据**（`src/debug`，仅 shell 的 DUMP 权限可进），永不触碰生产。

### 6.1 首次握手（批准之前）

**先跑握手，再批准。** 首次握手会失败并留下配对候选——这是设计（fail-closed working）。

```bash
adb shell am start -n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity
```

- 新装设备预期：`RESULT: REFUSED`（§6.5 未批准 caller 拒绝——**这是安全 PASS，不是缺陷**）。
- 若之前已批准过，可能直接 `RESULT: CONNECTED`（跳过 6.2）。

### 6.2 列出并批准配对

```bash
# 列出 pending caller（先读身份，再批准）
adb shell am start -n name.caiyao.fakegps.bench/name.caiyao.fakegps.integration.v1.PairingApprovalActivity
```

- 判据（§7.2a）：出现 `pending callers (N)`，其中
  `applicationId : com.example.cellrebelauto` + 一行 64 hex 的 `signerDigest`。
- **批准必须同时给两个 extra，且完全精确匹配**（§6.5 禁止 silent/automatic TOFU，
  批准不是"批准一切 pending"，是点名两个半主元）：

```bash
adb shell am start -n name.caiyao.fakegps.bench/name.caiyao.fakegps.integration.v1.PairingApprovalActivity \
  --es approve_application_id com.example.cellrebelauto \
  --es approve_signer_digest <上一步读到的完整 64 hex>
```

- 判据（§7.2b）：`APPROVED: com.example.cellrebelauto`。
- `NO MATCH:` = 抄错了 digest 或 appId；从 pending 列表**逐字符**复制，批准刻意不做模糊匹配。

### 6.3 批准后握手

```bash
adb shell am start -n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity
```

- 判据（§7.3）：`RESULT: CONNECTED`、`protocolVersion  : 1`、**无** `!! PROTOCOL SKEW` 行。

### 6.4 完整 loop

```bash
adb shell am start -n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.FullLoopProbeActivity
```

- 判据（§7.4）：逐步匹配；终态 `LOOP COMPLETE — EXHAUSTED:`（单调度项种子 → 末项，预期 EXHAUSTED；
  只有 ≥2 项的调度才会 `LOOP COMPLETE — ADVANCED:`）。
- **apply 是设备真正动起来的地方**（`[3] apply`），也是新设备最可能先红的地方——先看 §8 再归因。

### 6.5 清理验证 + 收尾

1. 若 §6.4 出现 `CLEANUP UNSAFE — DEVICE MAY STILL BE IN MOCK STATE` → 执行 §9 协议。
2. 无论红绿，收尾都显式停 mock：
   ```bash
   adb shell am start -n name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity --es command stop
   ```
   判据（§7.5）：`READY command=stop`。
3. 打开任意地图 app，确认设备位置回到真实位置（不是 Kyiv）。
4. 记录猫按 §11 封存证据。

## 7. 验收判据（哪些字段算通过）

> 判据 = **逐字匹配探针输出**。任何"差不多""看起来正常"都不算。

### 7.1 种子 seam（§6.0）
- **PASS**：`READY command=prepare_kyiv`
- **FAIL**：无该行 / 出 `rejected`（说明 `--es command` 传错）

### 7.2a 配对列表（§6.2）
- **PASS**：`pending callers (N)` 且含 `applicationId : com.example.cellrebelauto` + 64 hex `signerDigest`
- `no pending callers` = 6.1 没先跑（候选还没被记录）。先跑 6.1 再回来看。
- 屏幕显示说明：pending 里同时给出 `versionCode`（审计字段，**不参与**身份匹配，§6.5.4）。

### 7.2b 配对批准（§6.2）
- **PASS**：`APPROVED: com.example.cellrebelauto`
- **FAIL**：`NO MATCH:`（主元抄错）/ `REFUSED: approval needs BOTH --es`（只给了半个主元）

### 7.3 握手（§6.1 / §6.3）
| 输出 | 判定 | 含义 |
|---|---|---|
| `RESULT: CONNECTED` + `protocolVersion  : 1` + 无 SKEW | **PASS** | 双端可见、可 bind、可 discover、配对通过、协议一致 |
| `RESULT: CONNECTED` + `!! PROTOCOL SKEW — client speaks 1, provider speaks <其他>` | **FAIL** | 版本不一致（§6.8 停止条件），先修版本再继续 |
| `RESULT: NOT BINDABLE` | **FAIL** | provider 未装/包名不符/API 30+ 可见性（§8.2） |
| `RESULT: TIMED OUT after 5000 ms` | **FAIL** | bind 成功但 discover() 5s 未返回——活但卡住的 provider（§8.3） |
| `RESULT: REFUSED` | 看 cause | 首次=**安全 PASS**（未配对 fail-closed）；已批准后出现=FAIL（§8.4） |

### 7.4 完整 loop（§6.4）
逐行核验（顺序即规范 §6.7 的顺序，**release 先于 advance** 是冻结序，不是探针偏好）：

| 探针行 | 匹配通过条件 |
|---|---|
| `[1] discover → item=… ver=… rev=…` | `item` 非空（无 `STOP: provider has no current schedule item`） |
| `[2] preflight → decision=… blockers=[…]` | 有输出即算过；`blockers` 非空会被**记录**，但探针故意继续暴露 apply 的真实答案 |
| `[3] apply → lease=… rev=… verif=…` | 有输出即算过（**设备在此移动**）；`verif` 值见下注 |
| `[4] observe → … fingerprint=… hashMatch=true` | `hashMatch=true` 必须显式出现 |
| `[5] release → complete=true residuals=[] rev=…` | `complete=true` 必须显式出现；`residuals=[]` 期望空 |
| `[6] advance → outcome=… from=… to=…` | 有输出即算过（outcome 见终态） |
| `LOOP COMPLETE — EXHAUSTED:` + 四腿 readback | **PASS（终态）**：`schedId`/`item`/`ver`/`exhausted=true` 四腿全匹配 |
| `LOOP COMPLETE — ADVANCED:` + 四腿 observe | **PASS（终态）**：item/ver/hash/rev 四腿全匹配（需 ≥2 项调度） |
| 任一 `FAILED:` / `STOP:` / `SAFETY: lease NOT cleared` / `LOOP ABORTED` | **FAIL**，按 §8 归因 |
| `CLEANUP UNSAFE: … DEVICE MAY STILL BE IN MOCK STATE` | **FAIL + 必做 §9** |

> **`verif` 字段**：`VerificationLevelV1` wire：1=`SYSTEM_MOCK_INDEPENDENTLY_VERIFIED`（
> ConfigPrefsSync 发布回读成环）、3=`NONE`（未回读成环）。`verif=3` **不是 harness 失败**，
> 是诚实报告"HOOK 投递链路没有回读确认"——常见于 Xposed 未启用/作用域未配置。
> 判据只要求 `[3] apply` 有 lease 输出。

### 7.5 清理收尾（§6.5）
- **PASS**：`READY command=stop` + 地图 app 显示真实位置
- **FAIL**：无 READY 行，或地图仍在 Kyiv

## 8. 故障解读表

| 现象 | 最可能原因 | operator 动作 |
|---|---|---|
| 握手 `NOT BINDABLE` | ① bench 没装；② `pm list packages` 没有 bench；③ API 30+ 可见性（Auto `<queries>` 缺 bench 包） | 重装 bench（`adb install -r`）；确认包名列表三行齐全；真改 `<queries>` 属 harness 缺陷 → 记 finding |
| 握手 `TIMED OUT after 5000 ms` | provider 进程活着但 `discover()` 不返回 | `adb shell am force-stop name.caiyao.fakegps.bench` 后重试；复现则记 finding（卡住≠缺失） |
| 握手 `REFUSED`（已批准后） | signer 轮转 / 换 keystore / 批准被撤销 | 看 cause；重读 pending 列表重新批准 |
| `[1] discover` STOP（无当前项） | 忘了 6.0 种子，或 `prepare_kyiv` 没成 | 重跑 6.0 + 核对 7.1 |
| `[3] apply` 失败/抛错 | ① mock_location app-op 未给 bench（最常见）；② 无当前项/无坐标 | 检查 §3.1 开发设置（或 `adb appops set … allow`）；重跑 6.0 |
| `[5] release` 非 complete | provider 未回读清理成环（mockGateway 层） | 看 `residuals`；执行 §9 后再跑一轮 |
| `[6] advance` 回 `16/17/14/15/8` | 调度状态与请求前提不符（耗尽/身份/版本/项/lease 归因） | 对照 §7.4 表核 `expected*` 字段；复现 → 记 finding（wire code 是规范 §6.7.4b 的回答，不是探针错误） |
| 地图停在 Kyiv / mock 未清 | cleanup 验证失败 | 执行 §9，确认地图回真实位置后再继续任何步骤 |

## 9. 安全协议：`CLEANUP UNSAFE` 与 mock 状态清理

`FullLoopProbeActivity` 的 **finally 块**是最后一道安全路径：任何一步抛错，只要 lease 还没被
验证 `releaseComplete=true`，它都会尝试 re-release。看到：

```
CLEANUP UNSAFE: lease <id>… release validation failed → <outcome> — DEVICE MAY STILL BE IN MOCK STATE
```

意味着 **探针没能证明设备已离开 mock 状态**。此时设备可能仍在向所有读位置的应用撒谎。

**operator 必须：**
1. **停一切**：不要继续任何下一步、不要重复跑 loop。
2. **手动断 mock**（shell seam，唯一受控出口）：
   ```bash
   adb shell am start -n name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity --es command stop
   ```
3. **验证**：logcat 出 `READY command=stop`；打开地图 app 确认位置真实（不是 Kyiv）。
4. **记录**：把完整 logcat（`ECFullLoop` + `MockProviderAcceptance`）与 `CLEANUP UNSAFE` 原文
   封进证据（§11），**不得隐藏**——"CLEANUP UNSAFE 但设备好像没问题" 是不成立的分句。
5. 若 `READY command=stop` 也失败（异常少），用系统设置手动关闭位置模拟并重启位置服务。

## 10. 角色分工（硬约束）

| 角色 | 谁 | 边界 |
|---|---|---|
| runbook 作者 | 深海猫 | ≠ harness 作者 |
| runbook 审者 | 缅因猫 Terra（@codex-terra） | ≠ runbook 作者；GitHub COMMENT 绑 exact SHA |
| harness 审者（R3） | 缅因猫 Sol（@codex-sol） | 审 PR #36 代码，不审本文 |
| 冒烟执行（operator） | co-creator 本人 | 拿真机操作 |
| 测试猫 | 观察/复现，不写结论 |
| 记录猫 | **独立于测试猫** | 不受"想让它过"的压力影响，按 §11 封证 |

## 11. 记录基线（记录猫用）

每轮冒烟**必须**记录，缺项即不可判定：

| # | 记录项 | 来源 |
|---|---|---|
| 1 | 设备品牌/型号/OS 版本/API 级别/serial(前4) | `adb shell getprop ro.product.model`、`ro.build.version.release`、`adb get-serialno` |
| 2 | 两只 APK 的 git commit（或同一 checkout SHA） | `git rev-parse HEAD` |
| 3 | 两只 APK versionCode/versionName | `adb shell dumpsys package <pkg> \| grep -E "versionCode\|versionName"` |
| 4 | 批准所用的完整 signerDigest 与 approve 命令 | §6.2（基线="批准了谁"） |
| 5 | 种子地址基线 | `prepare_kyiv` → Kyiv 50.4501 / 30.5234（§6.0） |
| 6 | 每步探针的当次 logcat 全文 | `adb logcat -d ECHandshakeProbe:V ECFullLoop:V ECPairingApproval:V MockProviderAcceptance:V *:S` |
| 7 | 每步探针屏幕截图 | `adb exec-out screencap -p > <run>-<step>.png` |
| 8 | `[3] apply` 的 rev/verif/fingerprint 与 `[4]` 的 hashMatch | 探针输出（记录猫不补写） |
| 9 | 任何 `CLEANUP UNSAFE` / `FAILED` / `STOP` 原文 | §9 协议完成后照实记 |
| 10 | 冒烟后设备位置验证（地图 app 非 Kyiv） | §6.5 |

证据文件命名：`docs/acceptance/g1-smoke-<date>-<device-serial4>-<run#>.md`（含上面字段 +
`reportDigest: sha256:<原始 logcat+截图 字节的 SHA-256>`）。**记录猫不得改原始字节。**

## 12. 退出标准 → G2 前置

本冒烟 **PASS** 的定义（全绿才算一次通过的真机前置）：

1. §7.1 / §7.2a / §7.2b 全 PASS（种子 + 精确批准）
2. §7.3 握手 PASS（CONNECTED，无 SKEW）
3. §7.4 完整 loop 到 **LOOP COMPLETE（EXHAUSTED 或 ADVANCED）**，四腿全匹配，无任何
   FAILED/STOP/SAFETY/CLEANUP UNSAFE
4. §7.5 清理 PASS（stop READY + 设备回真实位置）

PASS 后允许：`C2` 审者出非作者审 PASS → 与 `C3`（PR #36 R3）**合取**后，才谈上真机进入 G2。
冒烟 PASS **不**自动放行 G2（见 §2 边界）。

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
- 千网游 bench 是 **Xposed 模块**（`xposedmodule=true`）。若设备有 LSPosed，启用
  `name.caiyao.fakegps.bench` 模块并配置作用域（HOOK 投递是产品能力，本文只列为可选前置）。
  **握手与配对步骤不依赖 Xposed**；apply 的 `verif` 字段含义见 §7.4——它**不度量**模块启用/作用域。

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

**构建产物指纹（F-13，必记）**：构建完成立刻对两只 APK 取 SHA-256 并留存，作为 §11 与
设备实装比对的基线——`versionCode/versionName` 相同**不**证明设备跑的是这个包
（install 可能静默未生效而版本号恰好不变，F-13 实测踩实）：

```bash
shasum -a 256 apps/qianwangyou/app/build/outputs/apk/debug/app-debug.apk \
              apps/cellrebel-auto/app/build/outputs/apk/debug/app-debug.apk
```

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

### 6.0.0 抓取模板（每步通用，P1-2 生命周期显式化，不可省）

探针在后台线程出报告（`thread(name="ec-…")`），`am start` 后立即 `adb logcat -d` 会**取不到输出**。
每步调用下面的 `capture_step` 函数。**三个终态，退出码确定，与证据一致**（不变量）：

> 清理幂等且**状态归一化**（`kill` 对已死 PID 是 no-op，`wait … || true` 吸收 kill 状态）：
> - 匹配到终态 → 清理 → **返回 0**
> - 到点未匹配 → `TIMEOUT after <s>` **追加进同一证据文件** → 清理 → **返回 1**
> - 收到 SIGINT/SIGTERM → 立刻置 `aborted`、清掉 logcat 子进程 → `ABORTED by signal` **追加进同一证据文件** → 清理 → **返回 130**（第三终态，**不是**超时）
>
> 任何自动化或后续判定只看 `$?` + 证据文件，三者永不冲突。**退出码 130 = 立即停止本次运行**，见下。

```bash
# 会话开始时定义一次
capture_step() {
  local log="$1" tags="$2" pattern="$3" deadline_s="$4" amargs="$5"
  adb logcat -c
  adb logcat -s $tags > "$log" &          # -s 静默默认优先级，只显示列出的 tag（无 glob 风险）
  local pid=$!
  local aborted=0
  _sig() { aborted=1; kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null || true; }
  trap '_sig' INT TERM                    # 信号只清 logcat 并置标志，不续跑（见轮询条件）
  adb shell am start $amargs
  local i=0
  while [ "$i" -lt "$deadline_s" ] && [ "$aborted" -eq 0 ]; do   # aborted 短路 = 立即退出轮询
    grep -qE "$pattern" "$log" && break
    sleep 1; i=$((i+1))
  done
  if [ "$aborted" -eq 1 ]; then
    echo "ABORTED by signal" >> "$log"    # 第三终态证据进同一文件
    trap - INT TERM
    return 130
  fi
  if grep -qE "$pattern" "$log"; then
    kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null || true   # 归一化：kill 后 wait 的状态被吸收
    trap - INT TERM
    return 0
  fi
  echo "TIMEOUT after ${i}s (deadline ${deadline_s}s)" >> "$log"   # 超时事件写进同一证据文件，非 stdout
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null || true
  trap - INT TERM
  return 1
}
```

```bash
# 每步用法（五个实参：log / tags / pattern / deadline / am start 参数）
capture_step run-6.1-handshake.log ECHandshakeProbe "RESULT:" 60 \
  "-n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity"
echo "step exit=$?"   # 0=匹配终态 PASS；1=TIMEOUT；130=ABORTED（行已追加进 run-*.log，见下）
```

**退出码 130 处理（任何一步，强制）：**
- 130 = operator 中断。**立即停止本次运行**，**不得**重试或进入下一步。
- 若 130 发生在 **§6.4（full loop）**，设备侧探针（含 `[3] apply` 的 mock）**仍在设备上运行**，
  宿主机已停止捕获 → **设备状态未验证**。必须先完成 §9：受控 `stop` + `READY command=stop`
  验证 + mock 状态直接验证通过（§6.5 step 3），**之后**才能重试或继续任何步骤。
- 发生在其他步骤同样按"停止本次运行"处理；`ABORTED by signal` 证据随 §11 封存。

**每步三值 + deadline（确定性，勿改）：**

| 步骤 | tag（`-s` 后） | 终态 pattern | deadline |
|---|---|---|---|
| 6.0 seed | `MockProviderAcceptance` | `READY command=prepare_kyiv` | 30s |
| 6.1 / 6.3 握手 | `ECHandshakeProbe` | `RESULT:` | 60s |
| 6.2 配对列表 | `ECPairingApproval` | `pending callers\|no pending callers` | 30s |
| 6.2 批准 | `ECPairingApproval` | `APPROVED:\|NO MATCH:\|REFUSED:` | 30s |
| 6.4 full loop | `ECFullLoop` | `LOOP COMPLETE\|FAILED:\|STOP:\|LOOP ABORTED\|CLEANUP\|SAFETY:` | 180s |
| 6.5 stop | `MockProviderAcceptance` | `READY command=stop` | 30s |

> pattern 列里的 `\|` 只是 Markdown 表格转义；实际 `grep -qE` 用裸 `|`（ERE 交替）。

**deadline 依据**（每个都覆盖真实异步工作量，且到点必出确定性失败）：
- seed / pairing / stop = 30s：纯本地 DB/kv 写与同步 `commit()`，毫秒级；30s 是宽裕上界。
- handshake = 60s：客户端内部超时 `5s × 最多 2 个候选包`（~10s 最坏）+ bind 开销；60s 是内建超时的 6×，`REFUSED`（快）与 `TIMED OUT`（探针自报 5s）都必然在 60s 内落定。
- full loop = 180s：唯一没有内部逐调用超时的步（只有 5s bind latch）；apply 含
  `LocationManager` 操作与 `ConfigPrefsSync.commit()`，每一步是本地 binder 往返；180s 是确定性上界。

**超时 = 本次 FAIL(TIMEOUT)（exit 1）**：超时行已在同一证据文件内，部分日志**保留为证据**
（§11 记 exit code 与 `TIMEOUT after` 行），按 §8 归因后重跑。**不得口头延长、不得无限等待。**

**中断 = FAIL(ABORTED)（exit 130）**：`ABORTED by signal` 已在同一证据文件内；立即停止本次运行，
**不是**超时、**不**按重试处理。§6.4 内发生 → 先 §9 再任何后续（见上方"退出码 130 处理"）。

### 6.0 基线：种子 `.bench` 调度数据

完整 loop 需要「当前调度项 + 该 profile 的坐标」（§6.7 的 apply 从**当前调度项**解析坐标，
KB-8：千网游独占坐标）。`prepare_kyiv` 清空 bench 全部 profile、种入一个
"Kyiv acceptance"（50.4501, 30.5234, alt 179.0）、把投递模式设为 HOOK、
`mockProviderCleanupRequired=false`：

```bash
capture_step run-6.0-seed.log MockProviderAcceptance "READY command=prepare_kyiv" 30 \
  "-n name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity --es command prepare_kyiv"
echo "seed exit=$?"   # 0=PASS；1=TIMEOUT；130=ABORTED（立即停止）
```
- 判据（§7.1a）：logcat `MockProviderAcceptance` 出 `READY command=prepare_kyiv`。
- **种子 seam 只动 `.bench` 数据**（`src/debug`，仅 shell 的 DUMP 权限可进），永不触碰生产。

**6.0.1 进程重置（P1-1，不可省）：**

```bash
adb shell am force-stop name.caiyao.fakegps.bench
```

`ProviderRuntime.handler()` **每进程只构造一次**（`handlerRef`，第二次 bind 复用）；`QwyScheduleStore`
只在 `QwyEnvironmentController` 构造时从 profile DB（`fakegps.db` 的 `temp` 表）初始化。
而 `prepare_kyiv` **只写 Room DB**，不会刷新已存活进程里那份 in-memory 调度。所以：

- 若 provider 进程在本轮冒烟**之前**已经活着（打开过 bench app / 上一轮跑过 / 先跑过 6.2），
  live handler 会沿用**旧**调度 → 后续 `[1] discover` 报无当前项或陈旧项。
- 因此**种子写入并确认 READY 之后、任何 EC bind 之前，必须 force-stop 一次**，
  让下一次 bind 从新 DB 重建调度。此刻尚无 apply/lease，重置是安全的。
- **每次重跑（同一进程存活）都必须重做 6.0 + 6.0.1**，否则不构成可重复过程。
- **若上一轮以 `CLEANUP UNSAFE` 收场，必须先完成 §9（stop READY + mock 状态直接验证通过）才能重跑 6.0。**
  force-stop **不**移除系统侧 test providers（`removeTestProvider` 才做）；任何重置都不得凌驾于 §9 之上。
- 若个别 ROM 对 `persistent` 生效导致 force-stop 无效，退路是 `adb uninstall` 后重装 bench
  （清空 `.bench` 数据）并重新 6.0，把这一事实记入 §11。

### 6.1 首次握手（批准之前）

**先跑握手，再批准。** 首次握手会失败并留下配对候选——这是设计（fail-closed working）。

```bash
capture_step run-6.1-handshake.log ECHandshakeProbe "RESULT:" 60 \
  "-n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity"
echo "handshake exit=$?"   # 0=终态已出（看内容判定）；1=TIMEOUT；130=ABORTED（立即停止）
```

- 新装设备预期：`RESULT: REFUSED`（§6.5 未批准 caller 拒绝——**这是安全 PASS，不是缺陷**）。
- 若之前已批准过，可能直接 `RESULT: CONNECTED`（跳过 6.2）。

### 6.2 列出并批准配对

```bash
# 列出 pending caller（先读身份，再批准）
capture_step run-6.2a-pairing.log ECPairingApproval "pending callers|no pending callers" 30 \
  "-n name.caiyao.fakegps.bench/name.caiyao.fakegps.integration.v1.PairingApprovalActivity"
echo "pairing-list exit=$?"   # 0=PASS；1=TIMEOUT；130=ABORTED
```

- 判据（§7.2a）：出现 `pending callers (N)`，其中
  `applicationId : com.example.cellrebelauto` + 一行 64 hex 的 `signerDigest`。
- **批准必须同时给两个 extra，且完全精确匹配**（§6.5 禁止 silent/automatic TOFU，
  批准不是"批准一切 pending"，是点名两个半主元）：

```bash
capture_step run-6.2b-approve.log ECPairingApproval "APPROVED:|NO MATCH:|REFUSED:" 30 \
  "-n name.caiyao.fakegps.bench/name.caiyao.fakegps.integration.v1.PairingApprovalActivity --es approve_application_id com.example.cellrebelauto --es approve_signer_digest <上一步读到的完整 64 hex>"
echo "approve exit=$?"   # 0=APPROVED/NO MATCH 已出；1=TIMEOUT；130=ABORTED
```

- 判据（§7.2b）：`APPROVED: com.example.cellrebelauto`。
- `NO MATCH:` = 抄错了 digest 或 appId；从 pending 列表**逐字符**复制，批准刻意不做模糊匹配。

### 6.3 批准后握手

```bash
capture_step run-6.3-handshake.log ECHandshakeProbe "RESULT:" 60 \
  "-n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity"
echo "handshake exit=$?"
```

- 判据（§7.3）：`RESULT: CONNECTED`、`protocolVersion  : 1`、**无** `!! PROTOCOL SKEW` 行。

### 6.4 完整 loop

```bash
capture_step run-6.4-loop.log ECFullLoop "LOOP COMPLETE|FAILED:|STOP:|LOOP ABORTED|CLEANUP|SAFETY:" 180 \
  "-n com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.FullLoopProbeActivity"
echo "loop exit=$?"   # 0=探针已出终态（逐行核对 §7.4）；1=TIMEOUT；130=ABORTED（先 §9）
```

- 判据（§7.4）：逐步匹配；终态 `LOOP COMPLETE — EXHAUSTED:`（单调度项种子 → 末项，预期 EXHAUSTED；
  只有 ≥2 项的调度才会 `LOOP COMPLETE — ADVANCED:`）。
- **apply 是设备真正动起来的地方**（`[3] apply`），也是新设备最可能先红的地方——先看 §8 再归因。

### 6.5 清理验证 + 收尾

1. 若 §6.4 出现 `CLEANUP UNSAFE — DEVICE MAY STILL BE IN MOCK STATE` → 执行 §9 协议。
2. 无论红绿，收尾都显式停 mock：
   ```bash
   capture_step run-6.5-stop.log MockProviderAcceptance "READY command=stop" 30 \
     "-n name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity --es command stop"
   echo "stop exit=$?"   # 0=PASS；1=TIMEOUT；130=ABORTED
   ```
   判据（§7.5）：`READY command=stop`（exit 0）。
3. **直接查 mock 状态本身**——不得用「地图不在 Kyiv」代理（F-13：C5 接手时设备被**第三方 app**
   mock 在 49.959850,30.015237，距种子点 65.419 km，地图判据曾因此给出假绿；mock 可来自任意
   app、指向任意坐标）：
   ```bash
   adb shell dumpsys location | grep 'provider \[mock\]'
   adb shell appops get name.caiyao.fakegps.bench android:mock_location
   ```
   第一条**必须无输出**（无 `provider [mock]` 行 = 系统侧 mock provider 已清除）；
   两条输出原文随 §11 记录项 10 封存。打开地图 app 目测位置可作辅助直觉校验，**不构成判据**。
4. 记录猫按 §11 封存证据。

## 7. 验收判据（哪些字段算通过）

> 判据 = **逐字匹配探针输出**。任何"差不多""看起来正常"都不算。

### 7.1 种子 seam + 进程重置（§6.0 / §6.0.1）
- **7.1a PASS**：`READY command=prepare_kyiv`
- **7.1a FAIL**：无该行 / 出 `rejected`（说明 `--es command` 传错）
- **7.1b（P1-1 的判据）**：重置后，后续 `[1] discover` 必须能看到**本次种子**的当前项
  （`item=profile-…`），而不是 `STOP: provider has no current schedule item` 或陈旧项。
  看不到 = 6.0.1 没生效（live handler 未重建），**不得继续**，重做 6.0 + 6.0.1。

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
| `capture_step` exit **130**（`ABORTED by signal` 在日志尾部） | **FAIL(ABORTED)**——**不是** TIMEOUT、**不是**重试机会；§6.4 内发生 → 设备状态未验证，先 §9 再任何后续 |

> **`verif` 字段**：只报告 `ConfigPrefsSync.sync()` 的**发布事务**结果
> （`QwyEnvironmentController.applyEnvironment`：`published=true` → `VerificationLevelV1`
> `SYSTEM_MOCK_INDEPENDENTLY_VERIFIED(1)`，否则 `NONE(3)`）。它是**发布真值**——
> **不是** Xposed 模块启用/作用域的度量；发布未成环的具体根因（framework transport /
> 文件 mode / 模块配置）无法从探针输出单独推断，需要时看 provider 侧日志。
> 判据只要求 `[3] apply` 有 lease 输出，不依赖 verif 值；`verif=3` 照记 §11，不算 harness 失败。

### 7.5 清理收尾（§6.5）
- **PASS**：`READY command=stop` + `adb shell dumpsys location | grep 'provider \[mock\]'` **无输出**
  （系统侧 mock provider 已清除——无论此前 mock 来自哪只 app、指向哪个坐标）
- **FAIL**：无 READY 行，或仍检出 `provider [mock]` 行（设备仍在 mock 状态 = FAIL；
  坐标在哪里、是不是 Kyiv、是不是本 bench 在 mock，都不改变判定）

## 8. 故障解读表

| 现象 | 最可能原因 | operator 动作 |
|---|---|---|
| 握手 `NOT BINDABLE` | ① bench 没装；② `pm list packages` 没有 bench；③ API 30+ 可见性（Auto `<queries>` 缺 bench 包） | 重装 bench（`adb install -r`）；确认包名列表三行齐全；真改 `<queries>` 属 harness 缺陷 → 记 finding |
| 握手 `TIMED OUT after 5000 ms` | provider 进程活着但 `discover()` 不返回 | `adb shell am force-stop name.caiyao.fakegps.bench` 后重试；复现则记 finding（卡住≠缺失） |
| 握手 `REFUSED`（已批准后） | signer 轮转 / 换 keystore / 批准被撤销 | 看 cause；重读 pending 列表重新批准 |
| `[1] discover` STOP（无当前项） | ① 忘了 6.0 种子；② 漏了 6.0.1 进程重置（live handler 沿用旧调度——最常见于重跑）；③ `prepare_kyiv` 没成 | 重做 6.0 + 6.0.1（force-stop）后重跑，并核对 7.1b |
| `[3] apply` 失败/抛错 | ① mock_location app-op 未给 bench（最常见）；② 无当前项/无坐标 | 检查 §3.1 开发设置（或 `adb appops set … allow`）；重跑 6.0 |
| `[5] release` 非 complete | provider 未回读清理成环（mockGateway 层） | 看 `residuals`；执行 §9 后再跑一轮 |
| `[6] advance` 回 `16/17/14/15/8` | 调度状态与请求前提不符（耗尽/身份/版本/项/lease 归因） | 对照 §7.4 表核 `expected*` 字段；复现 → 记 finding（wire code 是规范 §6.7.4b 的回答，不是探针错误） |
| `dumpsys location` 仍见 `provider [mock]` / mock 未清 | cleanup 验证失败（含第三方 app 在 mock 的情况，F-13） | 执行 §9，mock 状态直接验证通过（§7.5）后再继续任何步骤 |

## 9. 安全协议：`CLEANUP UNSAFE` 与 mock 状态清理

`FullLoopProbeActivity` 的 **finally 块**是最后一道安全路径：任何一步抛错，只要 lease 还没被
验证 `releaseComplete=true`，它都会尝试 re-release。看到：

```
CLEANUP UNSAFE: lease <id>… release validation failed → <outcome> — DEVICE MAY STILL BE IN MOCK STATE
```

意味着 **探针没能证明设备已离开 mock 状态**。此时设备可能仍在向所有读位置的应用撒谎。

**operator 必须：**
1. **停一切**：不要继续任何下一步、不要重复跑 loop。
2. **手动断 mock**（shell seam，唯一受控出口；用 `capture_step` 保证 stop 证据与退出码一致）：
   ```bash
   capture_step run-9-stop.log MockProviderAcceptance "READY command=stop" 30 \
     "-n name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity --es command stop"
   echo "stop exit=$?"   # 0=READY 已出；1=TIMEOUT；130=ABORTED
   ```
3. **验证**：`run-9-stop.log` 出 `READY command=stop`（exit 0）；**直接查 mock 状态本身**
   （不用「地图不在 Kyiv」代理，理由与判据同 §6.5 step 3 / §7.5）：
   ```bash
   adb shell dumpsys location | grep 'provider \[mock\]'   # 必须无输出
   adb shell appops get name.caiyao.fakegps.bench android:mock_location
   ```
   两条输出原文记入 §11（记录项 10）。
4. **记录**：把完整 logcat（`ECFullLoop` + `MockProviderAcceptance`）与 `CLEANUP UNSAFE` 原文
   封进证据（§11），**不得隐藏**——"CLEANUP UNSAFE 但设备好像没问题" 是不成立的分句。
5. 若 stop 也 TIMEOUT/ABORTED（异常少），用系统设置手动关闭位置模拟并重启位置服务，
   并把该事实记入 §11。

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

每轮冒烟**必须**记录。记录项分两档：

- **判定承重**（Tier A，#1–6 / #8–11）：**缺项即不可判定**——这些项要么被 §7 判据直接用作逐字匹配输入（#6 logcat / #8 apply-observe / #10 mock 状态 / #11 exit code / #9 失败原文），要么是证据准入前提（#1 设备身份 / #2 源码基线 / #3 构建身份 / #4 配对身份 / #5 种子基线），缺少任何一项该轮冒烟无法给出可验证的 PASS/FAIL。
- **辅助附件**（Tier B，#7）：**缺项应补记但不阻塞判定**——仅供人工直觉参考与封存归档，不被 §7 任何判据引用，不影响 §12 退出标准。

### Tier A — 判定承重（缺项即不可判定）

| # | 记录项 | 来源 |
|---|---|---|
| 1 | 设备品牌/型号/OS 版本/API 级别/serial(前4) | `adb shell getprop ro.product.model`、`ro.build.version.release`、`adb get-serialno` |
| 2 | 两只 APK 的 git commit（或同一 checkout SHA） | `git rev-parse HEAD` |
| 3 | 两只 APK versionCode/versionName **+ 实装 APK SHA-256 逐字比对** | versionCode/versionName：`adb shell dumpsys package <pkg> \| grep -E "versionCode\|versionName"`；SHA-256：`adb shell pm path <pkg>`（应只有一行 `base.apk`）→ `adb exec-out cat <path> \| shasum -a 256`，与 §4 构建产物 SHA-256 **逐字相等**——不等 = 设备跑的不是本次构建（versionCode 相同不算数，F-13），重装后重记 |
| 4 | 批准所用的完整 signerDigest 与 approve 命令 | §6.2（基线="批准了谁"） |
| 5 | 种子地址基线 | `prepare_kyiv` → Kyiv 50.4501 / 30.5234（§6.0） |
| 6 | 每步探针的当次 logcat 全文 | `capture_step` 产出的 `run-<step>.log`（§6.0.0）：跟随写文件、终态或 deadline 后 `kill+wait`；**不得在 `am start` 后立即 `-d` 立取** |
| 8 | `[3] apply` 的 rev/verif/fingerprint 与 `[4]` 的 hashMatch | 探针输出（记录猫不补写） |
| 9 | 任何 `CLEANUP UNSAFE` / `FAILED` / `STOP` 原文 | §9 协议完成后照实记 |
| 10 | 冒烟后 mock 状态直接验证（`dumpsys location` 与 `appops` 输出原文；地图目测只作辅助，不构成判据） | §6.5 / §7.5 |
| 11 | 每步 `capture_step` 的 **exit code（0/1/130）** 与证据文件里的 `TIMEOUT after <s>` / `ABORTED by signal` 行原文 | 超时/中断行已**追加进同一 `run-<step>.log`**（非 stdout）；exit 1 = FAIL(TIMEOUT)、exit 130 = FAIL(ABORTED)，**部分日志必须随证据保留**，不得丢弃；130 发生在 §6.4 → §9 结果一并封存 |

### Tier B — 辅助附件（缺项应补记但不阻塞判定）

| # | 记录项 | 来源 | 有效性说明 |
|---|---|---|---|
| 7 | 每步探针屏幕截图 | `adb exec-out screencap -p > <run>-<step>.png` | 仅作人工直觉辅助与封存附件，**不参与 §7 判定、不影响 §12 退出标准**。截图存在时，记录猫应在证据报告中标注逐步有效性状态（见下），标注结果同样不改变判定 |

> **§11-7 有效性标注**（仅当截图存在时，标注进证据报告）
>
> | 标注 | 含义 | 举例 |
> |---|---|---|
> | ✅ 有效 | 截图 UI 内容与该步自身日志对应 | 探针显示 `RESULT: CONNECTED`，日志同步出 `RESULT: CONNECTED` |
> | ❌ 黑屏 | 设备 Dozing/屏幕关闭，截图全黑 | C5 run#2 v1：7 张同 hash、全黑（F-19） |
> | ❌ 陈旧帧 | 截图显示前一步或更早的 UI 内容 | C5 run#2 v2：6.5 截图仍显示 6.2b 配对界面（F-19） |
> | ❌ 转场半帧 | 动画中间帧，非稳态 UI | C5 run#2 v2：6.0 截图为上一轮握手界面的转场半帧（F-19） |
> | ⚠️ 未核 | hash 唯一但未目视核对内容 | hash 唯一性**不**构成有效性证据 |
> | ⚠️ 不适用 | 该步无对应探针 UI（如 force-stop） | 6.0.1 无探针 Activity |
> | ❌ 其他无效 | 损坏/截断 PNG、错误前台 Activity、或上述类别未覆盖的无效状态 | PNG header 损坏；截图显示非目标 Activity |
>
> **三类朴素检查均不能替代逐步内容核对（F-19 实证）：**
> ① 文件数量齐全 — 黑屏帧满足 ② hash 互不相同 — 转场半帧天然唯一 ③ 非黑屏计数 — 陈旧帧通过

证据文件命名：`docs/acceptance/g1-smoke-<date>-<device-serial4>-<run#>.md`（含上面字段 +
`reportDigest: sha256:<设备证据报告文件的 SHA-256>`）。**记录猫不得改原始字节。**

> <!-- CANONICAL:reportDigest:START -->
> **reportDigest 规范定义（§10.1 冻结）**：`SHA-256` 对**设备证据报告文件**
> （即本节所描述的 `g1-smoke-*.md`）的完整字节流求摘要，小写 hex，无前缀。
> `reportDigest` 是该报告在 evidence manifest 中的定位指针，
> **不是**原始证据文件（logcat / 截图 PNG）的拼接摘要。
> <!-- ASSERT:reportDigest:preimage=report-file:not=byte-concat:authority=§10.1 -->
> <!-- CANONICAL:reportDigest:END -->
>
> 原始证据的逐文件完整性由独立的 `evidence-manifest.sha256` 校验。
> 已有证据报告（如 run#2）中 reportDigest 使用了拼接字节定义，
> 保留原始计算不追溯修改；后续轮次按 §10.1 执行。

## 12. 退出标准 → G2 前置

本冒烟 **PASS** 的定义（§7 全绿才算一次通过的冒烟执行）：

1. §7.1a + 7.1b / §7.2a / §7.2b 全 PASS（种子 + 进程重置 + 精确批准）
2. §7.3 握手 PASS（CONNECTED，无 SKEW）
3. §7.4 完整 loop 到 **LOOP COMPLETE（EXHAUSTED 或 ADVANCED）**，四腿全匹配，无任何
   FAILED/STOP/SAFETY/CLEANUP UNSAFE/ABORTED
4. §7.5 清理 PASS（stop READY + mock 状态直接验证无 `provider [mock]` 行）

> 任何一步 `capture_step` **exit 130（ABORTED）即本次冒烟 FAIL**——**不**是 TIMEOUT、
> **不**可当作可重试的中断；§6.4 内发生必须先 §9（设备状态未验证）再谈重跑。

**执行时机顺序（调度线条件表，勿颠倒）：**
`C1` runbook 落盘（本文） → `C2` runbook 非作者审 PASS → `C3` PR #36 R3 PASS →
**`C4` = C2 ∧ C3 合取**（允许上真机，由 @opus5 显式持有）→ `C5` 冒烟执行（本 runbook，operator + 测试猫 + 记录猫）。

- 本文 §7 的 PASS 是 **C5** 的合格定义，不是 C2/C3 的替代品。
- **C5 只能在 C4 允许后执行**——冒烟 PASS 之前，C2/C3 必须先于它成立。
- 冒烟 PASS **不**自动放行 G2（见 §2 边界）。

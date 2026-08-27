---
feature_ids: [1, 7, 19]
topics: [acceptance, g2, device, install, exact-build, room, evidence]
doc_kind: evidence
created: 2026-08-27
device_serial4: ZY22
exact_head: 396dcc43207d88a0925128674f7fdc8ed8d254b9
status: incomplete
---

# G2 limited install apply 记录（ZY22）

## 结论与边界

operator 在调度线消息 `0001787827707996-001473-701b9079` 直接回复“装”，授权一次受限的
environment-establishment install。执行者为 `@fable-5`；`@codex-sol` 只独立复算冻结字节和逐项
记录，没有向设备发命令，也不兼任 evidence validity、`DUAL` signer 或最终放行人。

本轮在 candidate `396dcc43207d88a0925128674f7fdc8ed8d254b9` 上得到以下**局部可承重结论**：

- 两只 APK 均由同一 clean isolated checkout 现场真构建并安装；artifact 与安装后的 device
  `base.apk` 字节分别相等，四份 APK signer 相同。因此 §4.2 scoped installed-byte identity 为
  **PASS**。
- Auto 的 `MainActivity` 启动成功，8 秒后进程仍存活；冻结的 AndroidRuntime 错误流为空，后续
  两项日志计数均为 0。设备数据库副本可完整 replay，`user_version=6`，Room identity 为当前
  candidate 的 `0d083aef0412f6d2ad3bbce31bf37f98`。因此 F-19 本次真机 apply smoke 为
  **PASS（受下述证据覆盖限制约束）**。
- Auto 随后被 force-stop，mock provider 查询无命中，屏幕回到 `Asleep`；production
  `name.caiyao.fakegps` 的 `lastUpdateTime` 与本轮前已冻结基线逐字相同。

这**不是**完整 §4.1/§4.3 block E、`DUAL` verdict、S3 readiness 或 G2 release。冻结包仍有四项
证据覆盖缺口：没有本轮 contemporaneous serial/device-count；WAL/SHM 拉取没有绑定 device
`cat` 的 stderr/exit；没有保存未过滤的完整 logcat；包内没有完整 §4.1 环境/
`compatibility.yaml` 身份与独立 block report。历史目录不得回填。

安装使旧 P8 配置证据失效；operator 重新配置 LSPosed module/scope 并处理 production/bench scope
重叠后，必须另建目录重取 P8。P10 的 device-addressable fault/revoke 能力缺口也没有被本轮改变。

## 冻结对象

```text
evidenceDir  /Users/terry/Desktop/coding/g2-install-apply-20260827/
started      2026-08-27T10:52:32Z
ended        2026-08-27T10:53:33Z
manifest     apply-manifest.sha256
digest       1ff570957e611a392f7dffba6b2a52bf816a9861b0b023ca2eb4e51eefdc3124
payloads     54 / 54 checksum OK
directory    56 files = 54 payloads + manifest + post-manifest check output
ABORT.txt    absent
```

独立复算：

```text
shasum -a 256 apply-manifest.sha256
1ff570957e611a392f7dffba6b2a52bf816a9861b0b023ca2eb4e51eefdc3124

shasum -a 256 -c apply-manifest.sha256
54 lines, all OK

manifest names
  == recursive file set excluding apply-manifest.sha256 and manifest-check.txt
```

`manifest-check.txt` 是 manifest 生成后的校验输出，不能被其所校验的 manifest 自引用；因此本记录
准确写 54/54 payload，而不写 56/56。

## Candidate 与构建身份

冻结包内的 `probe-s1b-head.txt` 与 `probe-s1c-status-empty.txt`：

```text
$ git -C /Users/terry/Desktop/coding/g2-install-apply-checkout rev-parse HEAD
396dcc43207d88a0925128674f7fdc8ed8d254b9
EXIT 0

$ git -C /Users/terry/Desktop/coding/g2-install-apply-checkout status --short
<empty>
EXIT 0
```

独立记录者在收证时从 Git/CI 真相源补核：

```text
local HEAD       396dcc43207d88a0925128674f7fdc8ed8d254b9
origin/main      396dcc43207d88a0925128674f7fdc8ed8d254b9
remote main      396dcc43207d88a0925128674f7fdc8ed8d254b9
tree             031a652cc4bb59892d2ffd64c298fa489304ef0c
parent           8145baf73f3eb4cc9160499a945df2cb73c4c671
CI run           33063388359; 7/7 completed success at exact head
main worktree    clean
isolated tree    clean at record time
```

两个完整 Gradle transcript 的真实退出码均为 0：

```text
qianwangyou:    BUILD SUCCESSFUL in 26s; 63 actionable, 61 executed
cellrebel-auto: BUILD SUCCESSFUL in 15s; 61 actionable, 59 executed
```

`:app:compileDebugKotlin`、`:app:packageDebug`、`:app:assembleDebug` 均实际执行。日志中的
`:app:clean UP-TO-DATE` / pre-build UP-TO-DATE 不代表复用了 APK 编译或打包任务。

## 六步逐项记录

| Step | 判定 | 原始承重 |
|---|---|---|
| 1. isolated checkout | **PASS** | `probe-s1a~s1c`；exact HEAD，status 空。 |
| 2. clean rebuild | **PASS** | `probe-s2a/s2b` 完整 transcript 与 EXIT 0；compile/package/assemble 实际执行。 |
| 3. install 两只 | **PASS** | `probe-s3a/s3b` 均为 `Performing Streamed Install` / `Success` / EXIT 0。 |
| 4. §4.2 实装身份 | **PASS** | 两组 artifact/device SHA 相等、两次 `cmp` EXIT 0、四 signer 相等；package 名由 APK 原字节独立读取。 |
| 5. Auto/F-19 apply smoke | **PASS（局部）** | start EXIT 0、PID 存活、错误流无输出、全日志两关键词 count 0；DB 临时副本 `ok / 6 / 0d083aef…`。WAL/SHM 捕获 rc/stderr 与完整 logcat 缺口保留。 |
| 6. stop/restore | **PASS（本轮范围）** | Auto pid 不存在；mock grep EXIT 1；power=`Asleep`；production update time 与前置基线相同。 |

## §4.2 原始字节与 signer

独立记录者重新对四份 APK 求 SHA-256、执行 `cmp` 并重新运行 `apksigner`：

```text
artifact bench  f288b41805cf03dc4a038f8b315fb841a64ed21bd3ed6062cea769219ca58a26
device bench    f288b41805cf03dc4a038f8b315fb841a64ed21bd3ed6062cea769219ca58a26
cmp bench       0

artifact auto   2ad38a6474bd311610e65d5b4fa0f969bf133c91c7bbec81a19566407e223b02
device auto     2ad38a6474bd311610e65d5b4fa0f969bf133c91c7bbec81a19566407e223b02
cmp auto        0

all four signer certificate SHA-256
7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41
```

从 artifact 原字节读取：

```text
name.caiyao.fakegps.bench  versionCode=8  versionName=3.0.0
com.example.cellrebelauto  versionCode=1  versionName=1.0
```

install probes 与 device pull probes 的 command/stdout/stderr/exit 全部在包内；`pm path` 各返回一个
`base.apk`。production applicationId 没有出现在任何 install 命令中。

本轮 bench artifact 恰好又是 necessity run2 的 `f288b418…`。这只是两次 clean build 相等；此前
已经观察过相同源码得到不同 bench dex，本记录不据两次相等宣称构建可复现或根因已消失。

## Auto 启动与 Room apply

`probe-s5b-am-start.txt`：

```text
$ adb shell am start -W -n com.example.cellrebelauto/.ui.MainActivity
Status: ok
Activity: com.example.cellrebelauto/.ui.MainActivity
WaitTime: 3052
Complete
EXIT 0
```

8 秒后的 `probe-s5c-crash-scan.txt` 保存了空的 `AndroidRuntime:E` 流；随后
`probe-s5d-process-alive.txt` 直接读到 PID `30658`。补充负断言保存为：

```text
$ adb logcat -d | grep -c 'IllegalStateException' ; adb logcat -d | grep -c 'FATAL EXCEPTION'
0
0
EXIT 1
```

这里 EXIT 1 是最后一个 `grep -c` 的“零命中”，与两个 count=0 一致；不是命令执行失败。由于包内
没有保存未过滤的完整 `adb logcat -d` 字节，本记录只称“冻结的错误流与关键词扫描无崩溃”，不称
完整 logcat 已被独立重放。

设备侧 `ls -la databases/` 与本地 raw 文件大小逐字一致：

```text
cellrebel_auto.db       4096
cellrebel_auto.db-wal   181312
cellrebel_auto.db-shm   32768
```

raw pull-time 与 final hashes 相同：

```text
db   94772575633b6f43be37fdc3bc98e81d25f0b53e7098b5f21d547a0f415a894c
wal  5db3a31538a0f7c2d6532645d7306cb03406d900f9a29d28b9ca119e2693226c
shm  437b4fbb492cd1fc48e3290d220038507e014b9f5df52a1de9f1f3493dfa5760
```

独立记录者没有打开 raw；另复制三件到临时目录后 WAL-aware replay：

```text
PRAGMA integrity_check  ok
PRAGMA user_version     6
room_master_table       42|0d083aef0412f6d2ad3bbce31bf37f98
```

replay 前后 raw 三件 hash 不变。candidate 的 `6.json` 与生产代码常量也都以
`0d083aef0412f6d2ad3bbce31bf37f98` 为 healthy identity；旧事故值为
`dea7bb1231570ea9fab363e19fc3c9b3`。

## 归位与硬边界

```text
probe-s6a: Auto force-stop + HOME, EXIT 0
probe-s6b: provider [mock] 无命中, EXIT 1
probe-s7c: Auto pidof exit=1; mWakefulness=Asleep
production lastUpdateTime (before): 2026-08-11 15:07:12
production lastUpdateTime (after):  2026-08-11 15:07:12
```

执行脚本的完整 device command surface 经独立静态展开，只有本次授权的 bench/Auto install、身份
读取、Auto 启动/DB 读取、Auto force-stop/HOME 与状态查询；未命中 readiness、行为块、10A、
ledger、`pm clear`、卸载、reboot、appops/LSPosed 私库写或 production install/replace/clear。

包内 `probe-s7b-forbidden-ops-audit.txt` 的正则只覆盖 clear/uninstall/LSPosed SQL，故其
`NO-FORBIDDEN-OPS-FOUND` 只能视为局部自检；完整边界结论来自独立记录者对冻结脚本逐条展开，而
不是该摘要。

## §4.3 覆盖缺口

以下缺口不推翻已经由多条直接字节支撑的 §4.2 identity 与 F-19 scoped smoke，但阻止把本目录称为
完整 exact-build evidence package：

1. 本轮没有冻结 `adb devices -l`、exact serial、model、OS/API；`ZY22` 只能沿用前置 gate audit，
   不能从本目录独立证明 contemporaneous device attribution。
2. `probe-s5g-pull-wal` 与 `probe-s5h-pull-shm` 的 device `cat` 后使用 `;`，同时把 device `cat`
   stderr 重定向到 `/dev/null`；文件里的 EXIT 0 属于末尾 `stat`，不是 device `cat`。设备目录大小、
   本地大小、query integrity 与 expected identity 为内容提供旁证，但不补回缺失的原始 stderr/rc。
3. `probe-s5c` 只保存 AndroidRuntime tag 的错误流，`probe-s7a` 只保存完整 log buffer 的两项 grep
   count；未过滤的完整 logcat 没有冻结，历史字节不可回填。
4. candidate tree/parent/CI、applicationId/version 和 signer 已由独立记录者从 Git/CI/APK 原字节补核，
   但目录本身没有完整冻结 §4.1 要求的 host environment、`compatibility.yaml` digest 或独立 block
   report/reportDigest。因此 block E 保持 `INCOMPLETE`，不能请求早签 `DUAL`。

这些属于 evidence validity reviewer 的裁决输入；独立记录者不对自己的记录签 validity。

## 后续状态

- installation lease 已消费完毕；不得据此继续触碰设备。
- 当前实装身份已从旧 candidate 更新到本轮 artifact，但安装已使此前 P8 snapshot stale；P8 仍为
  **NOT SATISFIED / must re-capture after operator configuration**。
- P10 的真机 fault/revoke surface 仍缺，保持 **NOT SATISFIED**。
- 不跑 readiness、行为块、§G 或 10 地址长跑；不铸 ledger 行；不改 Issue #1 或 G2 gate。
- `DUAL` signer task 继续 reserved/no early signature；最终放行仍只属于 operator。

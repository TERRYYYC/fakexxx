---
feature_ids: [1, 7]
topics: [acceptance, g2, p10, row2, hold-lease, evidence-contract, device]
doc_kind: evidence
created: 2026-08-27
status: freeze-candidate
---

# G2 P10 Row 2 `hold_lease` 真机证据契约 v1

> 本文件冻结“什么证据足以让独立 validity reviewer 对 Row 2 作出结论”。它不是设备授权、
> 不是执行脚本，也不把既有三次场次改判为 PASS。签字前与签字后均禁止据本文自行触碰设备。

## 1. 权威边界与冻结规则

本契约只收窄以下既有要求的证据载体，不修改产品行为契约：

- `issue7-g2-acceptance-package.md` §4.1–§4.3：candidate／installed identity、每命令
  `command + stdout + stderr + exit`、全量 manifest、per-block `reportDigest`、辅助截图、
  直接状态优先与 host path/secret 禁入；
- `p10-collector-runbook.md` §160–§187：每行 before／after 三件套、`hold_lease`
  的 ACTIVE 窗口、正常 release-before-advance、fresh-state recovery 分流；
- `g2-p10-device-matrix-2026-08-27-ZY22.md`：空输出、覆盖旧 raw、恢复前置缺失、
  非同一执行单元与 post-manifest 回填均不承重；
- `g2-s1-2026-08-26-ZY22.md`、`g2-s2-2026-08-26-ZY22.md` 与
  `g2-install-apply-2026-08-27-ZY22.md`：manifest 通过不等于文件集完整；只有输出／exit
  没有实际命令行不满足 §4.3；复合命令末尾 exit 不替代中间命令 exit；raw WAL 载体不打开。

冻结状态机只有以下四步：

1. `DRAFT`：Sol 起草，零设备命令；
2. `CRITERIA_SIGNED`：Terra 对 exact Git HEAD 明确签署“仅按本文件即可判定”；
3. `EXECUTABLE`：Fable 对 exact HEAD、runner 与 argv classifier SHA-256 给出 host-only 可执行性结论；
4. `AUTHORIZED`：调度线取得新的 operator 直接授权；此前不得执行任何 device command。

`CRITERIA_SIGNED` 后修改本文件任一字节会使签字失效。`AUTHORIZED` 后出现的新证据充分性要求
不得追溯加入本轮判据。新发现只能归入下列三类之一：

- 已冻结 checklist ID 的 PASS／FAIL；
- 执行异常或安全停止；
- 契约缺陷（单独登记，不再用同一 operator 授权补跑）。

P0 设备安全事实始终允许立即停止；它不允许事后改写 checklist 或追加第四次注入。

## 2. 角色、对象与 verdict 词表

| 角色 | 唯一职责 | 禁止事项 |
|---|---|---|
| executor | 按已签 runner 执行一次并冻结 raw | 自宣 Row 2 PASS、修改判据、覆盖旧目录 |
| recorder | 不触设备；复算文件集、hash、时序、等值关系与 checklist | 签自己的 evidence-validity |
| validity reviewer | 绑定完整 manifest digest，只按本 checklist 给终态 | 补采 raw、review 产品代码、事后新增判据 |
| dispatcher/operator | 在 `CRITERIA_SIGNED + EXECUTABLE` 后决定是否授权 | 把此前授权延展为开放额度 |

终态词表固定为：

- `SCOPED ROW2 PASS`：全部 required ID 为 PASS，且独立 validity reviewer 明确批准；
- `INCOMPLETE / NOT PROVEN`：任一 required carrier 缺失、为空、未绑定、超时、集合不完整或
  manifest 不一致；这不是产品 FAIL；
- `PRODUCT FAIL`：全部准入与载体 ID 均 PASS，但冻结 raw 证明 Row 2 行为违反 §181；
- `WRONG_BUILD / PREFIRE STOP`：candidate、device、P8 或 clean baseline 在注入前不满足；
  禁止开火，也不产生产品 verdict。

executor 的 shell exit 0、托管命令“成功”或摘要中的 DONE 均不是上述任一终态。

verdict 按以下顺序机械选择，不做多数表决：

1. ADM-01..02、PRE-00..12 或 P8-01..08 任一 FAIL：`WRONG_BUILD / PREFIRE STOP`；不得注入。
2. 已注入后，先只判每个 applicable required ID 的 **carrier completeness**：路径存在、非空规则、
   command 绑定、真实 exit、时序输入、identity 输入与 manifest membership。任一 carrier 缺失、
   为空、截断、未绑定或不可解析，固定为 `INCOMPLETE / NOT PROVEN`；本步不把行为值 false 当
   carrier 缺失。完整 carrier 记录到设备不安全事实时仍立即进入恢复或 operator recovery。
3. 全部 applicable carrier-completeness PASS 后，再判 predicate value。先判非行为结构谓词与
   `EXIT-02` identity：false 时走各 ID 表内 STOP／NOT-PROVEN 路径；随后单独判 `EXIT-01/03`
   behavior，任一 false 固定为 `PRODUCT FAIL`，不得回退解释成 carrier incomplete。
4. `EXIT-01/03` behavior PASS 后再判 terminal／after／freeze 谓词；只有全部 normal-path
   required ID 的 carrier 与 predicate 均 PASS，才能进入 `SCOPED ROW2 PASS` review。

本契约的 normal-path required ID 是 ADM、PRE、P8、SET、BFR、INJ、ID-LEASE、WIN、EXIT、
TERM-01..03、AFT 与 FRZ 的全部 ID。TERM-04/05、RST-01/02 只在没有正常 terminal report
或 TERM-01/02 不干净时适用；一旦适用，本次 Row 2 固定为 `INCOMPLETE / NOT PROVEN`，这些
ID 只裁定设备能否安全归真，不能把本次改回 PASS。未进入的恢复 ID 记 `N/A — normal path`，
不是 PASS。

## 3. Evidence directory、execution packet 与每命令六文件

每次获批执行使用一个从未存在过的绝对 runtime 目录，但 evidence payload 只保存下列逻辑根与
相对路径；host 绝对路径不进入 packet、command carrier、report 或 manifest：

```text
<evidenceDir>/
  meta/
  raw/
    identity/
    p8/
    before/
    window/
    terminal/
    after/
    recovery/
  query/
  derived/
```

### 3.1 冻结 execution packet

`meta/execution-packet.json` 在 feasibility 开始前冻结；Fable verdict 同时绑定 packet、runner、
classifier implementation 与 contract 四个 SHA-256。JSON 必须含且只能从该文件读取以下执行参数：

```text
schemaVersion
contractGitHead / contractBlobSha / contractSha256
runnerRepoRelativePath / runnerSha256
accessClassifier.policyId=ROW2-ARGV-ACCESS-V1 / implementationRepoRelativePath / implementationSha256
evidenceDirName / runId
candidateHead / candidateTree / buildType / gradleTasks[] / contractYamlSha256
buildEvidence.commandDigest / reportDigest / manifestDigest
hostEnvironment.os / kernel / java / gradle / androidSdk / adb / sqlite / shasum / bash
device.serial / model / fingerprint / androidRelease / api / timezone
packages[bench|auto].applicationId / artifactRepoRelativePath / artifactSha256 / versionCode /
                     versionName / signerSha256 / installedBaseApkPath
packages.production.applicationId
components.benchAcceptance / qwyCollector / autoHandshake / autoState / autoProbe
p8.rawDevicePaths[db|wal|shm]
p8.expectedModules / p8.expectedScopes / p8.expectedMockAllowPackages
kyiv.scheduleId / scheduleVersion / currentItemId / expectedBeforeState / expectedAfterState
roles.executorTaskId|owner / recorderTaskId|owner / validityTaskId|owner
holdMs=30000 / terminalTimeoutSeconds=70 / terminalReadMaxDelaySeconds=10
commands[]: seq / checklistIds[] / phase / slug / cwdRef=repo|evidence|query / argv[] /
            carrier.command|stdout|stderr|exit|startUtc|endUtc
sealControlPaths[]
```

机械规则：

1. `seq` 为从 `001` 开始的连续三位十进制；不得重复或跳号。
2. `argv[]` 是不经 shell 二次解析的完整参数数组；不得含 secret、未展开变量、glob、alias、
   command substitution 或 `sh -c`/`bash -c`。
3. 每个 checklist 的所有外部命令都必须在 `commands[]` 中；runner 静态提取出的外部命令集合
   必须与该数组逐项、逐参数相等。
4. packet schema **不得出现** `deviceAccess`、`declaredAccess` 或等价的自报读写字段。每条
   `argv[]` 的唯一 access class 只由 §3.1.1 classifier 重新计算；packet 不能选择或覆盖结果。
5. ADM-01..02 PASS 前，classifier 结果只能为 `HOST-NONE`。此后第一项 `DEVICE-WRITE` 必须是
   SET-01；在它之前，每条设备命令都必须逐项命中固定的 `DEVICE-READ` rule，且不得出现 packet
   之外的 device command。
6. packet、runner 或 classifier implementation 变化不改变 Terra 对本契约的 criteria sign，但使
   Fable feasibility verdict 失效。contract 变化使 Terra 与 Fable 两份 verdict 同时失效。
7. `cwdRef` 只解析为 runner 进程内已有的逻辑根；`argv[]` 与所有 carrier bytes 不得出现 host
   绝对路径、用户名、home 目录、credential、token、cookie 或本地 secret。

### 3.1.1 Canonical argv→access classifier

`ROW2-ARGV-ACCESS-V1` 是本契约唯一的 argv access policy。classifier 的输入只有 packet 常量与
一条 canonical JSON `argv[]`；输出固定为 `HOST-NONE`、`DEVICE-READ`、`DEVICE-WRITE` 或
`CLASSIFIER-REJECT`，并同时输出唯一 `ruleId`。`deviceAccess` 等 packet 标签不是输入。规则按下列
顺序求值，任何 argv 命中 0 条或 2 条以上均输出 `CLASSIFIER-REJECT`：

1. `argv[]` 不做 basename、路径、大小写、空白、quote 或 shell normalization；元素逐字节比较。
2. exact `["adb","version"]` 唯一命中 `HOST-ADB-VERSION`。除此以外，`argv[0]="adb"` 必须
   命中下表一条 `ADB-READ-*` 或 `ADB-WRITE-*`；不得落入 `HOST-NONE`。
3. 非 `adb` argv 只有命中 `HOST-NONE-*` 表一条时才为 `HOST-NONE`。`fastboot`、`scrcpy`、
   其他 device transport、未列 executable、`sh -c`／`bash -c`、`eval`、subshell、process
   substitution、pipeline 与 redirection token 全部 `CLASSIFIER-REJECT`。唯一允许的 command-string
   参数是 `ADB-READ-LSPOSED` 中逐字固定的三条 `cat <path>`；它不含 metacharacter、变量或额外 token。
4. classifier implementation 是独立于 packet 的冻结 payload。PRE-02 校验其 SHA-256；PRE-04
   校验 implementation 对本节正例／负例 fixture 的完整输出，并对 packet 与 runner 的每条 argv
   重新分类。implementation 不得读取 packet 中的任何 access label。

`HOST-NONE` 是下列显式 host-only allowlist；其中 `<repo-rel>`、`<evidence-rel>`、`<query-rel>`
必须是无 `..` 的相对路径且解析后仍在对应逻辑根内。每行是完整 argv grammar：没有逐字列出的
subcommand、flag、primary 或 token 均拒绝；表中的 `...` 只代表该行“额外限制”明确允许的重复 token，
不是任意尾参。表外 host argv 一律拒绝：

| ruleId | canonical argv shape | 额外限制 |
|---|---|---|
| HOST-ADB-VERSION | `["adb","version"]` | 不含 serial 或 transport selector |
| HOST-GIT-READ | `git` + `status --porcelain=v1`、`rev-parse`、`hash-object`、`diff --exit-code` 之一 | 无 network／checkout／reset／clean／写 index 参数；path 仅 `<repo-rel>` |
| HOST-BASH-SYNTAX | `["bash","-n",<runner-rel>]` | `<runner-rel>` 逐字等于 packet runner path |
| HOST-RUNNER-MODE | `["bash",<runner-rel>,<mode>,...]` | mode∈`parse,audit,gate,seal`；尾参只可为 packet seq/checklist ID、fixed literal 或逻辑根内 carrier；runner digest 已由 PRE-02 绑定；不得含 `-c` |
| HOST-CLASSIFIER | `["bash",<classifier-rel>,<mode>,...]` | mode∈`fixture,classify`；尾参只可为 packet／canonical command carrier／classification report；classifier digest 已由 PRE-02 绑定；不得含 `-c` |
| HOST-GRADLE | `["./gradlew","--offline","--no-daemon",<task>...]` | `<task>...` 与 packet `gradleTasks[]` 逐项相等；不得含 install task |
| HOST-HASH | `["shasum","-a","256",<relative-path>...]` 或 `["shasum","-a","256","-c","manifest.sha256"]` | 每个 path 只在 `<repo-rel>/<evidence-rel>/<query-rel>`；manifest check cwdRef=evidence；仍受 §3.2 禁 suppress 规则约束 |
| HOST-CAT | `["cat",<relative-path>]` | 输入只在三个逻辑根且不是 raw DB/WAL/SHM；binary artifact stdout 允许 |
| HOST-FILESET | `["find",".","-type","f","-print"]`、`["sort",<evidence-rel>]`、`["ls","-A","."]` 之一 | `find` cwdRef=evidence 且不接受 `-exec/-delete`；sort 输入为冻结 path-list carrier |
| HOST-FILEMETA | `stat`、`wc -c`、`wc -l`、`cmp`、`diff` + 1–2 个受限 relative path | 无 dereference／write flag；输入只在三个逻辑根 |
| HOST-EVIDENCE-FS | `cp`、`chmod 0444`、`mkdir -p`、`touch` + 受限 relative path | source/destination 只在三个逻辑根；不得覆盖既有 evidenceDir；raw 只按 §8 转 0444 |
| HOST-TEXT | `grep` 的 `-E,-F,-c,-n,-o,-v`、`sed -n`、`awk`、`cut`、`head -n`、`tail -n` + literal program/pattern + carrier path | 输入只来自已冻结 carrier；不得含 quiet flag、启动子进程或读取 device node |
| HOST-SQLITE | `["sqlite3","-readonly",<query-rel>,<sql-literal>]` | DB 只在 `query/p8/`；SQL 不含 `ATTACH`、写 pragma 或 mutation |
| HOST-ENV-TIME | executable∈`date,uname,java` | argv 只允许 packet hostEnvironment 所列 version／UTC probe shape |
| HOST-PROCESS | executable∈`sleep,kill` | 只等待或停止本 runner 创建且已冻结 PID 的 host capture process |

所有 device rule 都要求 literal prefix `["adb","-s",<packet device.serial>,...]`，唯独
`ADB-READ-DEVICES` 使用 exact `["adb","devices","-l"]`。classifier 在匹配 argv 前先校验 packet
常量；任一不等即拒绝，不能用 packet 自选 package/component 扩大 allowlist：

```text
packages.bench.applicationId = name.caiyao.fakegps.bench
packages.auto.applicationId = com.example.cellrebelauto
packages.production.applicationId = name.caiyao.fakegps
components.benchAcceptance = name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity
components.qwyCollector = name.caiyao.fakegps.bench/name.caiyao.fakegps.integration.v1.FaultCollectorActivity
components.autoHandshake = com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity
components.autoState = com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.ProviderRevokeCollectorActivity
components.autoProbe = com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.FullLoopProbeActivity
p8.rawDevicePaths.db = /data/adb/lspd/config/modules_config.db
p8.rawDevicePaths.wal = /data/adb/lspd/config/modules_config.db-wal
p8.rawDevicePaths.shm = /data/adb/lspd/config/modules_config.db-shm
```

两条 installed `base.apk` path 必须为无空白／metacharacter 的绝对 `/data/app/.../base.apk`，且随后与
PRE-08 raw stdout 逐字相等；serial 必须与 ADM-01 和 PRE-05 逐字相等。full UUID 只接受 lowercase
`8-4-4-4-12`。placeholder 均为上述受约束值，不是 glob 或 regex。read allowlist 固定为：

| ruleId | canonical argv shape | applicable checklist |
|---|---|---|
| ADB-READ-DEVICES | `["adb","devices","-l"]` | PRE-05 |
| ADB-READ-GETPROP | `adb -s <serial> shell getprop <key>`；key∈`ro.product.model,ro.build.fingerprint,ro.build.version.release,ro.build.version.sdk,persist.sys.timezone` | PRE-06 |
| ADB-READ-PM-PATH | `adb -s <serial> shell pm path <bench-or-auto>` | PRE-08 |
| ADB-READ-PACKAGE | `adb -s <serial> shell dumpsys package <bench-auto-or-production>` | PRE-09/10/AFT-05 |
| ADB-READ-APK-BYTES | `adb -s <serial> exec-out cat <packet installedBaseApkPath>` | PRE-09 |
| ADB-READ-EPOCH | `adb -s <serial> shell date +%s%3N` | PRE-11 |
| ADB-READ-LSPOSED | `adb -s <serial> exec-out su -c "cat <exact-db-variant-path>"` | P8-01 |
| ADB-READ-APPOPS | `adb -s <serial> shell appops query-op android:mock_location allow` | P8-05 |
| ADB-READ-LOCATION | `adb -s <serial> shell dumpsys location` | P8-06/BFR-03/WIN-02/TERM-02/AFT-03 |
| ADB-READ-LOGCAT | `adb -s <serial> logcat -v epoch` | BFR-01/02、INJ-01、WIN-01、TERM-01、AFT-01/02、RST-02 capture |
| ADB-READ-SCREENSHOT | `adb -s <serial> exec-out screencap -p` | AFT-07 |

write allowlist 固定为以下 exact shapes；任一 extra、顺序、component、package、fault、type 或值不同
即拒绝，不能退化成“所有 `am start` 都是 write 因而允许”：

| ruleId | canonical argv shape | applicable checklist |
|---|---|---|
| ADB-WRITE-PREPARE | `adb -s <serial> shell am start -W -n <bench MockProviderAcceptanceActivity> --es command prepare_kyiv` | SET-01 first command |
| ADB-WRITE-BENCH-STOP | `adb -s <serial> shell am force-stop <bench>` | SET-01 after prepare report |
| ADB-WRITE-DISCOVER | `adb -s <serial> shell am start -W -n <auto HandshakeProbeActivity>` | SET-02 |
| ADB-WRITE-QDUMP | `adb -s <serial> shell am start -W -n <bench FaultCollectorActivity> --es cmd dump --es app_id <auto> --es signer <auto signerSha256>` | BFR-01/WIN-01/TERM-01/AFT-01 |
| ADB-WRITE-AUTO-STOP | `adb -s <serial> shell am force-stop <auto>` | BFR-02/AFT-02 |
| ADB-WRITE-ASTATE | `adb -s <serial> shell am start -W -n <auto ProviderRevokeCollectorActivity> --es cmd state` | BFR-02/AFT-02 |
| ADB-WRITE-HOLD | `adb -s <serial> shell am start -W -n <auto FullLoopProbeActivity> --es fault hold_lease --el hold_ms 30000` | INJ-02 |
| ADB-WRITE-RERELEASE | `adb -s <serial> shell am start -W -n <auto FullLoopProbeActivity> --es fault rerelease_stuck --es lease_id <full-lowercase-UUID>` | RST-01 only after TERM-04 PASS |

classifier fixture 至少包含每条 allow rule 一个 positive、每个 placeholder 一项不等值 negative，并固定包含：
误标为 host 的 `adb shell am start`、无 serial／错 serial、`adb shell input`、`adb install`、
`adb logcat -c/-G`、`fastboot`、production launch／force-stop、未知 component、`sh -c`／`bash -c`、
含 redirection／pipeline 的 argv。所有 negative 必须输出 `CLASSIFIER-REJECT`。

### 3.2 每命令 carrier

每一个外部进程调用分配 packet 中的唯一 seq／phase／slug。除本契约点名的 canonical stdout
（例如 `manifest.sha256`）外，其 **carrier stem** 为 `<phase>/<seq>-<slug>`；packet 为该命令
冻结六个唯一 carrier path：

```text
<stem>.command.txt
<stem>.stdout.txt | <stem>.stdout.bin
<stem>.stderr.bin
<stem>.exit.txt
<stem>.start-utc.txt
<stem>.end-utc.txt
```

后文写 `raw/before/q-dump`、`device identity` 或“某命令六文件”时，均指 execution packet
把该 checklist ID 映射到的唯一 carrier；不存在第二份可替换 stdout。`.stdout.txt` 与
`.stdout.bin` 二选一，stderr 始终逐字节保存为 `.stderr.bin`。若 canonical stdout 不使用
`<stem>.stdout.*`，packet 的 `carrier.stdout` 直接指向 canonical path；不得再复制一份 stdout。
所有 carrier path 在整个 packet 中必须唯一，且全部位于 `<evidenceDir>` 内。

机械规则：

1. `command.txt` 是 packet `argv[]` 的 canonical UTF-8 JSON array 加一个换行；不得保存 shell
   command string。唯一 launcher 先逐字写入该 carrier，再把同一内存数组交给内嵌的冻结 classifier；
   只有唯一 ruleId、derivedClass 与当前 phase/checklist allowlist 全部匹配时，才把**同一数组**直接交给
   `execve` 等价 API。reject／ambiguous／phase mismatch 时外部进程根本不启动，runner 立即 STOP；
   classifier 不读取 packet access label，也不得 eval `command.txt`。
2. runner、classifier implementation 与 parser／gate payload 的所有外部进程只能经上述唯一 launcher
   启动；静态 call-site
   audit 必须证明不存在第二个 process-spawn、raw `adb`、`system`、`eval`、subshell 或 process
   substitution 路径。一个 command unit 只启动一个外部进程。`;`、`&&`、`||` 与 pipeline 不能把多个外部命令
   合成一个承重 exit；解析命令另占序号。
3. command 自身不得含 `>/dev/null`、`2>/dev/null`、quiet flag 或等价 suppress。wrapper 负责把
   stdout、stderr 原字节分别写入对应文件；两者即使 0 bytes 也必须存在。
4. `exit.txt` 只含一个十进制整数与换行，值来自该外部进程本身；不得取末尾 `grep`、`stat`
   或 `echo` 的 exit 代替前序命令。
5. `start-utc.txt`、`end-utc.txt` 各含一个 RFC3339 UTC 时间。所有时序比较只使用这些同源 host
   时间与 `logcat -v epoch` 原始时间，不使用文件名或人工摘要推断。
6. 二进制 stdout 使用 `.stdout.bin`，仍属于该命令的唯一 stdout；不得复制成第二份“raw”，
   也不得另造没有 command／stderr／exit 的孤立承重文件。
7. 过滤、计数与 regex 断言各自是独立 command unit，输入必须指向已经冻结的 raw stdout；
   过滤结果不能替代 raw。
8. 没有明文许可的 required stdout 为 0 bytes 即 FAIL。空 stdout 白名单固定为 PRE-03 的
   `bash -n`、PRE-07 的 clean-status、P8-02 的 host byte-copy、BFR-02/AFT-02 的 force-stop，
   以及 INJ-01 supervisor 的 signal/wait；每项仍保存 0-byte stdout，并另有非空 gate stdout
   证明 exit/等值/进程终态。launch、probe、gate 与 state readback 不在空输出白名单。stderr
   无输出时保存 0-byte `.stderr.bin`。
9. 活动通过 logcat 返回报告时，必须在对应 launch 前启动独立的 `logcat -v epoch` carrier。
   用 PRE-11 clock bridge 把 report epoch 转成 host 区间后，区间下界必须严格晚于 launch
   start-utc；只接受一个完整 marker-to-terminal block。旧 block、截取行或另一次 launch 的 block
   均 FAIL。capture 的停止与 wait 也各自进入 packet 并保存 exit。

## 4. 上机准入与 pre-fire checklist

ADM-01..02 构成只使用 `HOST-NONE` carrier 的 device-access admission；两项全部 PASS 前，classifier
结果为 `DEVICE-READ` 或 `DEVICE-WRITE` 的命令均禁止执行。两项 PASS 后，PRE/P8 只允许执行
§3.1.1 固定 shape 推导为 `DEVICE-READ` 的取证命令；在 `PREFIRE-CUTOFF` 前仍禁止任何推导为
`DEVICE-WRITE` 的命令。任何 `CLASSIFIER-REJECT`、多 rule 命中、packet／runner／carrier argv
不等或 classifier implementation／fixture 不一致均在 SET-01 前 STOP。exact `adb version` 由
`HOST-ADB-VERSION` 判为 host-only；其他 `adb` argv 永远不能自选 `HOST-NONE`。

| ID | required carrier | 机械 PASS 谓词 | FAIL 动作 |
|---|---|---|---|
| ADM-01 | `meta/operator-authorization.json` + provenance | authoredBy=operator；明确只授权一个 Row-2 `hold_lease` run；逐字绑定 contract HEAD/blob、packet SHA-256、runner SHA-256、classifier SHA-256、candidateHead、device serial 与 forbidden scope；messageId/timestamp 非空 | STOP；不得访问设备 |
| ADM-02 | exact role-custody task readback | executor／recorder／validity 三个 taskId 与 owner 均非空且 owner 两两不同；executor task 只覆盖 ADM-01 的一次 run、status=doing；三组 task/owner 与 packet 逐字相等；recorder 不兼任 validity | STOP；不得访问设备 |

### 4.1 任何 device write 之前

`PREFIRE-CUTOFF` 是 PRE-12 成功结束与 SET-01 开始之间的唯一边界。执行顺序固定为：
PRE-00..11 → P8-01..07 → PRE-12 → SET-01 专属 `ADB-READ-LOGCAT` capture start → SET-01；
三组全部 PASS 后才可启动该 capture，capture 之后才可启动 classifier 推导出的第一条
`DEVICE-WRITE`。PRE-12 与 SET-01 之间除这一个已在 packet 固定的 report capture 外，不得启动
其他 command。
P8-08 在 SET-01 启动时回证这条边界，不是允许写入的前置 gate。这里的 device write 包括
`am start`、`force-stop`、`logcat -c/-G`、安装、卸载、清数据、appops 修改、私库写入与注入；
只读 `getprop`、`pm path`、`dumpsys`、`appops query-op`、`adb devices`、设备文件字节导出不算
write，但必须逐项落在 §3.1.1 固定 read shape 并推导为 `DEVICE-READ`：PRE-05、PRE-06、PRE-08、
PRE-09、PRE-10、PRE-11 的 device-epoch read、P8-01、P8-05、P8-06。其他 PRE/P8 command
必须命中显式 `HOST-NONE` allowlist；固定集合之外的任何 pre-fire device command 均 FAIL。

| ID | required carrier | 机械 PASS 谓词 | FAIL 动作 |
|---|---|---|---|
| PRE-00 | execution packet、packet SHA-256 与 schema-validation 六文件 | schemaVersion=1；required key 全部存在且无 unknown key；每个 command 的 seq/path 唯一；validation exit=0 | STOP |
| PRE-01 | contract snapshot、Git blob/HEAD 与 SHA-256 六文件 | snapshot 逐字等于 reviewed HEAD 的本文件；完整 HEAD/blob 与 Terra sign 相等；SHA-256 为 64 位小写 hex 且等于 snapshot bytes | STOP |
| PRE-02 | runner／classifier SHA-256 carrier + 两份 payload | 两个 digest 分别逐字等于 packet；policyId=`ROW2-ARGV-ACCESS-V1`；runner 与 classifier 均与 Fable feasibility verdict 绑定同一 digest | STOP |
| PRE-03 | runner 的 `bash -n` 六文件 | exit=0，stderr=0 bytes | STOP |
| PRE-04 | runner command-surface／secret-path audit六文件 + `meta/argv-access-classification.json` | exit=0；runner／classifier／parser／gate payload 在唯一 launcher 静态 call-site 之外 process-spawn 数=0；runner 外部命令与 packet `commands[]` 逐项相等；packet schema 的 access-label field 数=0；classifier 全部 positive/negative fixture 逐字通过；对 packet 与 runner 的**每条** argv 各输出 seq、argvSha256、ruleId、derivedClass、command carrier，且两侧结果逐项相等；unclassified／ambiguous／mismatch／forbidden／host absolute path／credential/secret counts 均=0；全部 `DEVICE-WRITE` 只落在下述允许集合 | STOP |
| PRE-05 | `adb devices -l` 六文件 | exit=0；恰好一个 state=`device` 的数据行；serial 为获批 execution packet 中的完整 serial | STOP |
| PRE-06 | device identity 六文件 | model、fingerprint、Android release、API、timezone 各一条非空；serial 与 PRE-05 相同 | STOP |
| PRE-07 | host candidate／build／artifact／contract identity 六文件 | checkout clean；完整 HEAD/tree、buildType、Gradle command 与 task、完整 build exit=0、compatibility YAML SHA、build report/manifest digests、可复现 hostEnvironment versions 均与 packet 相等；两包 host artifact bytes 分别作为 >0-byte binary stdout 封存，其 SHA 与 packet 相等；carrier 不含 host path/secret | STOP |
| PRE-08 | 两包 `pm path` 六文件 | 每包 exit=0，stdout 恰好一个 `base.apk`；package 分别为 `name.caiyao.fakegps.bench` 与 `com.example.cellrebelauto` | STOP |
| PRE-09 | 两包 device APK 字节、SHA、package/version/signer 六文件 | device bytes SHA 与对应 host artifact SHA 逐字相等；applicationId、version 与 signer 分别等于 packet；不得只比 version | WRONG_BUILD / STOP |
| PRE-10 | production package `lastUpdateTime` before 六文件 | exit=0、非空；只供 after 负边界等值比较 | STOP |
| PRE-11 | host-before、device epoch、host-after 与 clock-bridge parser 六文件 | 三次 read exit=0；host round-trip ≤2s；parser 固定输出 midpoint offset 与 uncertainty；uncertainty ≤1s | STOP |
| PRE-12 | `meta/prefire-read-command-audit.json` + 六文件 | 本 command 在 P8-07 后运行且 exit=0；解析截至其 start-utc 已完成的每份 canonical `command.txt`，逐份运行 §3.1.1 classifier，不读取 packet label；按 seq 排序后的全部 carrier argv／ruleId／derivedClass 与 PRE-04 对应项逐字相等；其中 `DEVICE-READ` 集合与 packet 中 PRE-12 前 §3.1.1 approved read shapes 的 seq／checklistIds／argv[]／六个 carrier path 逐项逐参数相等且每项 end-utc < PRE-12 start-utc；missing／extra／duplicate／reject／ambiguous／label-field／derived-write counts 均=0；packet suffix 接着只能是 SET-01 专属 `ADB-READ-LOGCAT` capture start，再接 classifier 结果恰为 `ADB-WRITE-PREPARE`／`DEVICE-WRITE` 的 exact SET-01 | STOP；不得启动 SET-01 capture 或 SET-01 |

允许的 device-write checklist 集合固定为：SET-01 的 exact `prepare_kyiv` 与其后 bench force-stop、
SET-02 的 exact handshake discover；BFR-01、WIN-01、
TERM-01、AFT-01 的 exact-principal Q-DUMP launch；BFR-02、AFT-02 的 Auto force-stop 与
`cmd=state` launch；INJ-02 的单次 `hold_lease` launch；以及 TERM-04 PASS 时的 RST-01。
其他 classifier `DEVICE-WRITE` 一律 FAIL。forbidden argv 固定为 install、uninstall、`pm clear`、reboot、
`logcat -c/-G`、`appops set/reset`、`settings put/delete`、`cmd package` mutation、production package
component launch/force-stop、LSPosed path 的 write/open-for-write、Rows 3–7 token 与 §G/长跑入口。

### 4.2 Pre-fire P8

P8-01..07 只产生并裁定 pre-write gate 输入；PRE-12 在 P8-07 后作最后一次 read-command
集合审计。P8-08 只在 PRE-12 PASS 后由 runner wrapper 写入 SET-01 的真实首次 write carrier；
它不能反向为任何更早的 write 授权。

| ID | required carrier | 机械 PASS 谓词 | FAIL 动作 |
|---|---|---|---|
| P8-01 | `raw/p8/modules_config.db{,-wal,-shm}` 的三个 binary stdout + 各自六文件 | 三次导出 exit=0、stderr=0 bytes；DB/SHM >0 bytes；WAL 可为 0 bytes；三个路径全部存在 | STOP |
| P8-02 | `query/p8/` 三件套 + raw/query hash 表 | query 三件从 raw 本地复制；复制前后 raw digest 不变；任何 SQLite 都只打开 query 副本 | STOP |
| P8-03 | query integrity 与 module-state 六文件 | `integrity_check=ok`；`lspd=0`、production module=0、bench module=1，各恰好一行 | STOP |
| P8-04 | scope query 六文件 | bench scope 恰好包含 Auto client；production scope 零包含 Auto client | STOP |
| P8-05 | `appops query-op android:mock_location allow` 六文件 | exit=0；allow 集合中包含 bench、排除 production；保存完整未过滤 stdout | STOP |
| P8-06 | `raw/p8/dumpsys-location.txt` + count 六文件 | dumpsys exit=0 且 >0 bytes；独立 parser exit=0 且输出整数 `0` | STOP |
| P8-07 | `meta/p8-gate.txt` | 内容逐字为 `P8-PREFIRE-PASS`；其 start/end-utc 晚于 P8-01..06 的全部 end-utc | STOP |
| P8-08 | `meta/first-device-write.json` + SET-01 capture／command／start-utc carrier | 解析所有在 SET-01 start-utc 前已开始的冻结 command carriers 并用 §3.1.1 classifier 重算，不读取 packet label；PRE-12 后仅有一个仍在运行的 `ADB-READ-LOGCAT`，其 checklistIds 只含 SET-01 且 start-utc < SET-01 start-utc；实际最早的 `DEVICE-WRITE` ruleId=`ADB-WRITE-PREPARE` 且 command 是 SET-01；其 seq／checklistIds／canonical argv 与 PRE-12 的 next-device-write 逐项逐参数相等；PRE-00..12 与 P8-01..07 的全部 end-utc 严格早于 capture 与 SET-01 start-utc；更早 carrier 的 derived-write／reject／ambiguous counts 均=0 | FAIL 后立即停止；若 SET-01 已开始，不得再发 device command，不得注入 |

raw DB/WAL/SHM 在 manifest 冻结前只允许复制和 hash，不允许被 SQLite、文件预览器或 recorder
直接打开。query 副本承担所有解析。

## 5. Setup 与 immediate-before checklist

setup 位于 P8 pre-fire 之后、Row 2 before trio 之前。不得沿用 setup 之前的状态快照冒充 before。

| ID | required carrier | 机械 PASS 谓词 | FAIL 动作 |
|---|---|---|---|
| SET-01 | `prepare_kyiv` launch／report 与其后 bench force-stop 的独立六文件 | first command 唯一命中 `ADB-WRITE-PREPARE`；launch exit=0；raw report 含且只含本次 `READY command=prepare_kyiv`；report 完成后 exact `ADB-WRITE-BENCH-STOP` exit=0，以清除旧 in-memory schedule | STOP |
| SET-02 | setup 后 handshake discover capture／launch／report 六文件 | launch 唯一命中 `ADB-WRITE-DISCOVER` 且 exit=0；只接受本次完整 `ECHandshakeProbe` block；current item／schedule／version 非空，且与 execution packet 的 Kyiv seed 预期一致 | STOP |
| BFR-01 | `raw/before/q-dump.txt` 及其全部 command units | exact caller+signer 查询；collector raw >0；无 blocking lease；pairing 对 exact principal 为 active | STOP |
| BFR-02 | Auto `force-stop`、fresh `cmd=state`、collector capture 的独立六文件 | force-stop exit=0；fresh start exit=0；A-STATE raw >0 且含 pairing、running attempt/aplusState、trusted total 三类行 | STOP |
| BFR-03 | `raw/before/dumpsys-location.txt` + count 六文件 | raw >0、dumpsys exit=0、parser 输出整数 `0` | STOP |
| BFR-04 | `derived/before-state.json` | parser exit=0；JSON 固定含 full principal、leaseState、attempt rows、trusted total、mockCount；值可从 BFR-01..03 逐字回算 | STOP |
| BFR-05 | command-order audit 六文件 | SET-01..02 全部早于 BFR-01..04；BFR-04 之后到 INJ-01 之间没有 classifier 推导为 `DEVICE-READ` 或 `DEVICE-WRITE` 的 command carrier | STOP |

## 6. Injection、窗口与正常出口 checklist

| ID | required carrier | 机械 PASS 谓词 | FAIL 动作 |
|---|---|---|---|
| INJ-01 | 未过滤 `logcat -v epoch` 连续流六文件 | capture 在 injection 前启动；覆盖 terminal report；不得先执行 `logcat -c/-G`；background PID 的 start/stop/wait 各有独立 command unit 与 exit | STOP / NOT PROVEN |
| INJ-02 | `FullLoopProbeActivity --es fault hold_lease --el hold_ms 30000` 六文件 | exact component 与 canonical `--el`；launch exit=0；stdout 含 Android launch success | STOP |
| INJ-03 | 完整 probe report raw + extraction 六文件 | report 为一次完整 `ECFullLoop` emission；parser exit=0 并对 discover、preflight、apply、observe、window open/closed、release、advance、completion、`FAILED:`、`LOOP ABORTED` 分别输出显式 boolean；boolean false 不等于 carrier 缺失 | NOT PROVEN |
| INJ-04 | invalid-procedure token audit 六文件 | parser exit=0；`REFUSED\|TIMEOUT\|STOP:\|DIVERGENT\|CLEANUP UNSAFE\|RERELEASE FAILED\|RERELEASE THREW\|PROVIDER_ERROR_8` 命中数为 0；`FAILED:`/`LOOP ABORTED` 留给 behavior parser，不在本 ID 吞掉 `EXIT-01/03` | STOP |
| ID-LEASE-01 | apply receipt carrier | 出现一个完整小写 UUID（`8-4-4-4-12`）；不得以 `take(8)`、省略号或前缀承重 | NOT PROVEN |
| WIN-01 | `raw/window/q-dump.txt` 六文件 | raw >0；恰好一个 blocking lease；state=`ACTIVE`；caller=Auto；leaseId 为完整 UUID | STOP |
| WIN-02 | `raw/window/dumpsys-location.txt` + count 六文件 | raw >0；parser exit=0；count=`2` | STOP |
| ID-LEASE-02 | equality report 六文件 | parser exit=0；apply full UUID == WIN-01 full UUID，逐字 36 字符相等；前缀相等返回 FAIL | NOT PROVEN |
| WIN-03 | window temporal report 六文件 | WIN-01 与 WIN-02 的 start/end 位于 injection command 开始后、terminal report 出现前；WIN-01 自身 state=ACTIVE | NOT PROVEN |
| EXIT-01 | INJ-03 完整 report raw + release predicate parser 六文件 | parser stdout 非空并明确 boolean；`[5] release` 的值为 `complete=true`、`residuals=[]`，且环境 revision 可解析 | PRODUCT FAIL（全部 applicable carrier 完整且结构谓词 PASS 时） |
| EXIT-02 | release identity carrier | 同一完整 lease UUID 与 ID-LEASE-01 逐字相等；没有完整 ID 的 release 摘要不承重 | NOT PROVEN |
| EXIT-03 | INJ-03 完整 report raw + advance/completion predicate parser 六文件 | parser stdout 非空并明确 boolean；release 行严格早于 advance；只有 release complete 后出现 advance；最后有 `LOOP COMPLETE — EXHAUSTED` | PRODUCT FAIL（全部 applicable carrier 完整且结构谓词 PASS 时） |
| EXIT-04 | completion wait 六文件 | timeout 固定 70s；捕获到终态返回 0；无终态返回非零并创建 `FAIL-CLOSED`，不得输出 DONE | STOP |

## 7. Terminal、恢复分支与 after checklist

terminal direct-state 在终态 report 后立即采集。`terminal Q-DUMP` 必须先于任何 recovery。

| ID | required carrier | 机械 PASS 谓词 | FAIL 动作 |
|---|---|---|---|
| TERM-01 | `raw/terminal/q-dump.txt` 六文件 | 由 PRE-11 bridge 得到 `0s ≤ Q-DUMP start-utc - terminal report latest-host-time ≤10s`；raw >0；无 blocking lease | 进入 TERM-04/05 分流 |
| TERM-02 | `raw/terminal/dumpsys-location.txt` + count 六文件 | raw >0；parser exit=0；count=`0` | 进入 TERM-04/05 分流 |
| TERM-03 | normal-branch audit 六文件 | TERM-01 无 blocking lease且 TERM-02=0 时，全部 command files 中 `rerelease_stuck` 执行命中数为 0 | 命中即 NOT PROVEN |
| TERM-04 | eligible recovery gate | 仅当 probe/binder 有冻结的 interruption 证据，且 TERM-01 full UUID state∈`ACTIVE,EXPIRED,RELEASE_INCOMPLETE`，gate 才输出 `ELIGIBLE` | 其他情况禁止 rerelease |
| TERM-05 | non-eligible state gate | `REVOKED`、`RELEASING`、未知状态、正常 completed report 与 active lease 冲突、或无 blocking lease 但 mock 非零，全部输出 `MANUAL-STOP`；不得自动选恢复 | STOP / operator recovery |
| RST-01 | recovery command 六文件（仅 TERM-04） | command 输入 TERM-01 的完整 UUID；`rerelease_stuck` 只执行一次 | STOP |
| RST-02 | recovery report 与 direct-state | report 输出与 RST-01 输入逐字相同的 full UUID 及 `VALIDATED complete=true residuals=[]`；after Q-DUMP 无 blocking lease；mock=0 | 不满足则 operator recovery |
| AFT-01 | `raw/after/q-dump.txt` 六文件 | raw >0；无 blocking lease | NOT PROVEN |
| AFT-02 | Auto force-stop、fresh A-STATE 的独立六文件 | 两条命令 exit=0；A-STATE raw >0；三类 required 行齐全 | NOT PROVEN |
| AFT-03 | `raw/after/dumpsys-location.txt` + count 六文件 | raw >0；parser exit=0；count=`0` | NOT PROVEN / unsafe |
| AFT-04 | `derived/after-state.json` | schema 与 BFR-04 相同；attempt rows、trusted total、pairing principal 与 before 的预期 delta 在 execution packet 中逐字段列出 | NOT PROVEN |
| AFT-05 | production package `lastUpdateTime` after 六文件 | 与 PRE-10 stdout 逐字相等 | 边界违规 / STOP |
| AFT-07 | auxiliary screenshot command 六文件；stdout canonical path=`raw/after/device-screen.png` | classifier 结果必须为唯一 `ADB-READ-SCREENSHOT`／`DEVICE-READ`；exact `adb -s <packet serial> exec-out screencap -p` argv；stdout 是未经转换／裁剪的原始字节；exit=0；stderr 原字节保留；PNG >0 bytes 且 8-byte signature=`89504e470d0a1a0a`；carrier 全部进入 FRZ-01/03 | 缺失即 INCOMPLETE；内容不参与行为裁决 |
| AFT-06 | terminal-to-after order audit 六文件 | TERM-01/02 完成后依次执行 AFT-01..05 与 AFT-07；期间 classifier 推导的 `DEVICE-READ` 或 `DEVICE-WRITE` carrier 全部属于 AFT；全部属于同一 runId/evidenceDirName | NOT PROVEN |

AFT-07 只满足 accepted §4.3 的辅助附件保留。截图内容不得替代 log、direct-state、exit 或任一
predicate，也不得把行为 FAIL 改成 PASS；但 carrier 缺失仍使完整 evidence package 为 INCOMPLETE。

## 8. Freeze、manifest 与独立复算 checklist

| ID | required carrier | 机械 PASS 谓词 | FAIL 动作 |
|---|---|---|---|
| FRZ-00 | packet `sealControlPaths[]` + precreate audit 六文件 | seal-control 集合逐字等于 precreate-audit、payload-file-set、manifest-generate、manifest-check、manifest-digest、final-file-set 六个 command unit 的 36 个 carrier path；路径互异；执行 precreate-audit 前全部已存在；除各 unit 的 command 与 precreate-audit start-utc 外均为 0 bytes | INCOMPLETE |
| FRZ-01 | payload file-set command 六文件 | stdout 为 `find` 递归所得相对普通文件路径的字节序排序；集合恰好等于 final 普通文件集减去 `sealControlPaths[]`；不以顶层 `ls -A` 代替 | INCOMPLETE |
| FRZ-02 | top-level `ls -A` command 六文件 | 保存用户点名的顶层原文；集合与 FRZ-01 加 seal-control 集合的顶层投影相等 | INCOMPLETE |
| FRZ-03 | manifest-generate 六文件；stdout canonical path=`manifest.sha256` | 只列 FRZ-01 的全部 payload；不遗漏合法 0-byte 文件、runner、contract snapshot、packet、非 seal command 六文件、raw、query、derived；条目集合与 FRZ-01 逐字相等 | INCOMPLETE |
| FRZ-04 | manifest-check 六文件 | `shasum -a 256 -c manifest.sha256` exit=0；stdout 条目数与 FRZ-01 相等；每条均以 `OK` 结束 | INCOMPLETE |
| FRZ-05 | final-file-set 六文件 | stdout 恰好等于 FRZ-01 payload 集合 ∪ packet `sealControlPaths[]`；零额外文件；所有 seal-control path 不再新增 | INCOMPLETE |
| FRZ-06 | raw immutability／mode report 六文件 | report command exit=0；`raw/` 下全部普通文件相对路径逐项列出且 mode=0444；recorder 复算前后全部 raw digest 不变；packet/command argv 中 raw WAL 三件套只允许作为 byte-copy 或 hash 输入，SQLite 输入只允许 query 副本 | INCOMPLETE |
| FRZ-07 | manifest-digest 六文件 | stdout 只含 64 位小写 hex 与换行，等于 `manifest.sha256` 文件字节 SHA-256 | INCOMPLETE |
| FRZ-08 | Row-2 report、reportDigest 与 locator 三组六文件 | canonical report stdout=`derived/row2-report.json`，含 schemaVersion=1、runId、testId=`hold_lease`、candidateHead、outcome、sorted applicableChecklistIds 与逐 ID relative evidenceRefs；digest stdout=`derived/row2-report.digest.txt`，只含 report bytes 的 64 位小写 hex；locator stdout=`derived/row2-report-index.json`，其 reportPath/digest/rawRoots 与前两者逐字相等；三组 carrier 均属于 FRZ-01/03 payload | INCOMPLETE |

`outcome` 只根据 seal 前 ADM..AFT 的事实按 §2 顺序取：`PREFIRE_STOP`、`CARRIER_INCOMPLETE`、
`STRUCTURAL_STOP`、`PRODUCT_BEHAVIOR_FAIL_OBSERVED`、`RECOVERY_ONLY_NOT_PROVEN`、
`NORMAL_PATH_EVIDENCE_CANDIDATE`。它是 facts report 的确定性分类，不是 executor 或 recorder
签发的 evidence-validity verdict；FRZ 失败不回写 report，而由 reviewer 固定判 INCOMPLETE。
只有独立 validity reviewer 能写 `SCOPED ROW2 PASS`。

FRZ-08 的三组 carrier 在 seal precreate 前完成并成为普通 payload。随后 seal 顺序固定为
`precreate-audit → top-level ls-A → payload-file-set → manifest-generate →
manifest-check → manifest-digest → final-file-set`。FRZ-00 的 36 个 path 与 top-level ls carrier
在 precreate-audit 前一次性建立；top-level ls carrier 在 payload-file-set 前写完并属于 payload。
这是避免“校验输出又改变被校验集合”的有限闭包。manifest 只 hash payload，不声称 hash 自身
或 seal controls。FRZ-04 证明 payload bytes，
FRZ-05 证明 final path 集合，独立 recorder 再直接重跑两者。任何 seal command 执行后创建新 path、
任何 raw 在 FRZ-01 后出现、或任何 addendum 都使 FRZ-05 FAIL。

recorder 必须在新的 sibling review directory 重新执行并保存以下纯 host 复算；evidenceDir 只读，
review 输出不得写回 evidenceDir：

1. manifest 逐项校验与 payload／seal-control exact file-set equality；
2. 每个 command unit 六文件完整性、序号唯一性、exit 可解析性；对每份 canonical command carrier
   独立运行冻结 classifier，ruleId／derivedClass 与 PRE-04、PRE-12、P8-08 全部逐项一致，且无
   access label、reject、ambiguous、runner/packet/carrier argv mismatch；
3. PRE-12 end-utc 与全部 P8 end-utc < SET-01 first-write start-utc；classifier 推导的第一条
   `DEVICE-WRITE` 是 exact SET-01；setup < before trio <
   injection；terminal ≤10s；
4. full lease UUID 的 regex、唯一性与 apply/window/release/recovery 等值；
5. before/window/terminal/after state 与 mock `0→2→0→0`；
6. 完整 report 正／负 token；
7. 禁止命令面、host path/secret scan 与 production `lastUpdateTime` 等值；
8. Row-2 report schema、relative evidenceRefs、`testId`/candidate/outcome 与 FRZ-08 reportDigest。

任一复算失败，recorder 只能写 `INCOMPLETE / NOT PROVEN` 或明确的 prefire／boundary STOP；
不得补 raw、改 manifest 或向设备重发命令。

## 9. Sign-off 与唯一判据面

Terra 的 criteria verdict 必须绑定本文件完整 Git blob／commit SHA，并逐字包含：

```text
CRITERIA APPROVE — if every required checklist ID in
docs/acceptance/g2-p10-row2-evidence-contract.md passes on one frozen package,
I can issue the scoped Row 2 evidence-validity verdict without adding a new
evidence-sufficiency predicate after device execution.
```

Fable 的 feasibility verdict 必须绑定同一 Git HEAD、packet SHA-256、runner SHA-256 与 classifier
implementation SHA-256，并逐项给：

- `EXECUTABLE`：每个 device command 与 carrier 在获批 candidate 上物理可产生；或
- `BLOCKED`：列出不能产生的 checklist ID、源码／工具坐标与所需的 device-free 前置修复。

只有 `CRITERIA APPROVE + EXECUTABLE` 同时存在，调度线才可向 operator 请求新的单次授权。
packet、runner、classifier implementation 或 candidate 变化后必须重新做 feasibility；本文件变化后
Terra 与 Fable 两份 verdict 全部失效。

## 10. 已知 pre-authorization blocker（起草时静态发现）

当前 collector candidate `f20d715ed393b608e12c7c840b223b3dc6041120` **不能满足**
normal path 的 `ID-LEASE-01/02`、`EXIT-02`，也不能满足条件恢复 path 的 `RST-02`：

```text
FullLoopProbeActivity.kt:209  rerelease recovery 仅输出 stuck.take(8)
FullLoopProbeActivity.kt:267  receipt.leaseId.take(8)
FullLoopProbeActivity.kt:306  leaseId?.take(8)
FullLoopProbeActivity.kt:377  release report 不输出 leaseId
```

Q-DUMP 会输出 full UUID，但“Q-DUMP full UUID + probe 8 位前缀”仍只是前缀关联，不是 apply receipt、
window state 与 release 的 exact-byte 三方绑定。这个 blocker 必须在请求下一次 operator 授权之前，
通过下列二选一关闭：

1. 找到并冻结一个当前 candidate 已存在、同时含 apply 与 release full UUID 的 raw carrier；或
2. 先走独立的 debug-only instrumentation 变更、测试、非作者 review、合入、构建与安装授权，
   再把新 candidate 写入 execution packet。

本契约不授权上述代码或安装动作。Fable 的 feasibility review 必须独立确认该 blocker，不能把
8 位前缀降级接受，也不能把下一次真机执行当 feasibility probe。

## 11. 明确排除

本文与后续 Row 2 包均不得：

- 运行 Rows 3–7、§G、10 地址长跑或铸 machine ledger；
- 安装／卸载／`pm clear`、写 LSPosed 私库、修改 appops、修改或启动 production package；
- 执行 `adb logcat -G` 或 `adb logcat -c`；
- 把 `rerelease_stuck` 当 Row 2 正常步骤；
- 修改 Issue #1、P10 readiness、G2、DUAL 或 release 状态；
- 复用 `g2-p10-row2-rerun-20260827`、`g2-p10-row2-rerun2-20260827` 或任何旧 raw；
- 在 contract／feasibility 签字前请求或消费新的设备授权。

## 12. 需求—checklist 闭包

签字 reviewer 逐行核对下表；表中没有 `covered by summary`：

| 已知失效模式／冻结要求 | 唯一承接 ID |
|---|---|
| executor／recorder／validity owner 未互斥，或 recorder 自签 validity | §2、ADM-02 |
| 每条 probe/gate 缺 command/stdout/stderr/exit；复合命令只留末尾 exit；输出被 suppress | §3.2、PRE-03/04、FRZ-01/03、recorder #2 |
| pre-fire blanket `no adb` 使 required read carrier 无法生成，或 packet 自报 `deviceAccess`／误标 `none` 绕过 write cutoff | §3.1-4、§3.1.1、§4、PRE-04/12、P8-07/08、recorder #2/3 |
| P8 在注入后才读取，无法证明 pre-fire 边界 | PRE-12、P8-01..08、recorder #3 |
| 缺 bench-only appops raw | P8-05 |
| 缺 contemporaneous candidate／device-count raw；只比 version | PRE-05..09 |
| WAL/SHM raw 被 SQLite 打开或只有 query copy | P8-01/02、FRZ-06 |
| raw 写完后未只读封存 | FRZ-06 |
| before A-STATE 0 bytes、旧 log block 冒充 fresh report | §3.2-8/9、BFR-02、AFT-02 |
| setup 发生在 before trio 之后 | BFR-05 |
| apply/release 只保留 lease 前 8 位；用前缀绑定 window full UUID | ID-LEASE-01/02、EXIT-02、recorder #4 |
| 正常 release 后误调 `rerelease_stuck` 制造假 oracle | TERM-01..05、RST-01/02 |
| 行为摘要代替 before/window/terminal/after raw | BFR、WIN、TERM、AFT 全部 ID |
| 缺 per-block canonical `reportDigest` 或 report/raw 定位关系 | FRZ-08、recorder #8 |
| 缺 §4.3 辅助截图，或让截图单独承重 | AFT-07、§7 辅助边界 |
| 0-byte transcript、无 terminal 仍输出 DONE、异步 reviewer 才追停 | INJ-03/04、EXIT-04 |
| host path/secret 进入 build evidence | §3.1-7、PRE-04/07、recorder #7 |
| 复用目录、覆盖 raw、manifest 后回填、manifest 通过但文件集不完整 | §3、FRZ-00..08 |
| 未签 criteria/feasibility 或沿用已消费授权直接上机 | ADM-01/02、§9 |

Terra criteria sign 后，本表与其引用的 checklist 是本轮 evidence-sufficiency 的完整集合。执行后
出现但未落入任一行的新充分性要求固定登记为“contract defect”；它不追溯改变本轮 verdict，也不
产生新的设备授权。

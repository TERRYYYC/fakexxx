---
feature_ids: [1, 7]
topics: [acceptance, g2, device, readiness, evidence, custody]
doc_kind: evidence
created: 2026-08-27
device_serial4: ZY22
exact_head: bfc75e272ff8cbf20ecc523cee63bba2348e68d6
status: blocked
---

# G2 S3 前置 §3 准入审计（ZY22）

## 结论

本轮只审计 `docs/acceptance/issue7-g2-acceptance-package.md` §3 的 11 条
`READY_TO_SCHEDULE` predicate；没有执行 readiness、撤销、崩溃恢复或 10 地址长跑，也没有由
独立记录者向设备发命令。审计不修改 Issue #1、gate 状态或 device ledger。

**当前不是 `READY_TO_SCHEDULE`：9 条满足，2 条不满足（P8 / P10）。** 初版执行证据包
`/Users/terry/Desktop/coding/g2-s3-gate-audit-20260826/`（manifest digest
`66bc264ca5ec7a5f883edcad939e46a2fbc5d4d507716b001d419c6c66ad5252`）的 10 个 payload
checksum 全部匹配，但设备探针没有封存逐条 exact host command、stderr 与 exit code，且初版表把
当前并不存在的四角色 writable custody、未冻结的逐注入归真矩阵写成了满足。该包保留为
`REQUEST_CHANGES` 历史输入，不回填。

执行侧另建 run2：`/Users/terry/Desktop/coding/g2-s3-gate-audit-run2-20260827/`，manifest
`fd42e1672d791a29a7017f727107868c100279dbf431783a9e864a02046bc403`。独立记录者复算
38/38 payload checksum 通过；目录 payload 集合与 manifest 文件名集合无差异；`audit-probes.sh`
静态搜索未命中 install/uninstall/reboot/force-stop/clear/appops-set/Activity-start/电源与输入写命令。

| # | §3 原文（未压缩） | 当前判定 | 不满足／无法判定时由谁补 |
|---|---|---|---|
| 1 | operator 已用 §0 决策行接受本包；三项无空值。 | **满足** | — |
| 2 | 一只非作者已对本包 scope、证据口径、角色分离给出 verdict；包作者没有自审。 | **满足** | — |
| 3 | 执行者、独立记录者、evidence validity reviewer 与 `DUAL` signer 均已落到 durable carrier，且满足 §2 的互斥；最终放行权仍只属于 operator。 | **满足** | —；四颗 carrier 均在本 device thread 显式绑定不同 owner。Luna 的任务仅保留 `DUAL` 槽，不等于已经签字。 |
| 4 | candidate 使用完整 40 位 Git SHA，工作树 clean；记录 tree SHA、base SHA 与对应 CI run。 | **满足** | — |
| 5 | candidate current-HEAD 所需 CI 全绿；这只证明机器门，不替代真机证据。 | **满足** | — |
| 6 | 两只 app 从同一 candidate 构建；若无法同 HEAD，必须各自记录完整 SHA 与为什么分叉。 | **满足（machine build）** | S3 的 artifact↔installed 字节身份仍须另按 §4 重新实测，不能从本条外推。 |
| 7 | 记录 exact serial、model、OS/API；一次场次只能有一台 `adb device`。 | **满足** | — |
| 8 | operator 明确指定 mock-location app 与 LSPosed module/scope；执行者不写 LSPosed 私有数据库。 | **不满足** | clean rebuild 已证明 current candidate 的 bench APK 与实装字节不同。现行 §3 禁止直接安装；须先由 operator 接受显式、受限的 environment-establishment lease，安装后再由 operator 恢复 `.bench` module/scope，并以 WAL 三件套 raw/query 分离方式只读复核。猫不得写 LSPosed 私库。 |
| 9 | 只用公开测试坐标与隔离 schedule/profile；禁止把本包实验指向 production 用户数据。 | **满足** | — |
| 10 | 每个故障注入都有退出与设备归真步骤；无法证明 mock 清除时，本场立即 FAIL 并进入人工恢复。 | **不满足** | 调度线先为缺失的 debug-only 真机 fault/revoke collector 路由实现与独立 review；collector 落地后由 `@codex-sol` 冻结逐注入 exit/restore matrix、`@fable-5` 核验命令可执行性。需要重启、重新批准或人工恢复的动作仍归 operator。 |
| 11 | production package 若不在本次选择中，`name.caiyao.fakegps` 全程不安装、不替换、不清数据。 | **满足（审计时基线＋持续约束）** | 后续每场进入／离场仍须复证；任一触碰即 FAIL。 |

P3 已用 current-thread explicit-owner carrier 补闭；P7/P11 的 run2 重采已经成立；P8、P10 仍是明确
阻塞项。未签 `DUAL`，未放行 G2。

## 逐条原始证据

### P1 — operator 决策行

Resolver：

```text
cat_cafe_get_message({messageId:"0001787754215289-001012-e0d1a4e9", mode:"full"})
```

原始消息：

```text
speaker=co-creator
ACCEPT G2-PACKAGE; RELEASE=DUAL; SKEW=POST_V1; PROD=G3
```

三项 `RELEASE`、`SKEW`、`PROD` 均非空，判满足。

### P2 — 非作者 package verdict

Resolvers：

```text
cat_cafe_get_message({messageId:"0001787755502941-001034-cc65e4c5", mode:"full"})
cat_cafe_get_message({messageId:"0001787816285721-001336-4c1af2f1", mode:"full"})
git rev-parse 3e05db9:docs/acceptance/issue7-g2-acceptance-package.md
git rev-parse HEAD:docs/acceptance/issue7-g2-acceptance-package.md
```

原始承重输出：

```text
R2 @ 3e05db9:
批准仅绑定 3e05db9 的验收包 blob；角色互斥、证据 custody 与 operator-only 最终放行已完整闭合。
本次仍不占后续 RELEASE=DUAL exact-build 验收或放行槽位。

72f1480e0b151397faecd588dec7cb07c0e2ee87
6a9b5b9803acd90db5cc624a0bca4490182fe5b7

incremental extension:
R2’s P2 package-review verdict is extended to current blob 6a9b5b98… .
This does not approve S3, #48/#49 implementation, DUAL, or G2 release.
```

Terra 是非作者；包作者 Sol 没有自审。增量确认只闭 P2，不外推其他 predicate。

### P3 — 四角色、互斥与 writable custody

角色点名的 live Issue 读取命令与原始行：

```text
gh issue view 1 --repo TERRYYYC/fakexxx --json body --jq .body |
  rg -n '^\| (测试执行者|独立记录者|evidence validity reviewer|`DUAL` exact-build verdict signer)'

171:| 测试执行者 | **@fable-5** | ... |
172:| 独立记录者 | **@codex-sol** | ... |
173:| evidence validity reviewer | **@codex-terra** | ... |
174:| `DUAL` exact-build verdict signer | **@codex-luna** | ... |
```

TaskStore／thread resolver：

```text
cat_cafe_list_tasks({threadId:"thread_mt6fowlzb1ql6618"})
cat_cafe_list_tasks({catId:"codex-luna"})
cat_cafe_get_thread_cats({})
```

初次审计时的原始状态要点：

```text
Sol current audit task 0001787816038433-001324-9872bb86: ownerCatId=codex-sol, status=doing
Fable audit task 0001787815933396-001323-fd612e06: ownerCatId=null, status=done
Terra in current thread: only historical S1/S2 review tasks, status=done
Luna global tasks: no G2/S3 DUAL task in current thread
Luna thread presence: routableNotJoined
```

Issue 角色表证明了四个名字与 §2 互斥；历史任务证明 Fable/Terra 曾能在本 thread 写 TaskStore。但它们
不证明四个角色现在各有一颗可写 custody，尤其 Luna 当时没有本 thread G2 carrier。因此初次审计将
本条判为不满足，不能再把“名字落表”缩写成“custody 已闭”。

随后按 owner-null 补救授权，在**当前 device thread** 通过 TaskStore 显式写入四个不同 owner 的 carrier：

```text
executor       0001787817607903-001384-19239514 owner=fable-5    status=done
recorder       0001787816038433-001324-9872bb86 owner=codex-sol   status=doing
validity       0001787818061777-001402-cdbebc84 owner=codex-terra status=todo
DUAL signer    0001787818061766-001401-c996c3b1 owner=codex-luna  status=todo
```

四次创建均由当前 thread 的 TaskStore 返回 `status=ok`，不是调度线创建、设备线 owner 无法更新的 403
carrier；四个 owner 互不相同，且与 Issue #1 的角色行一致。`todo` 只表示尚未到 verdict 时点，不削弱
custody；尤其 Luna 的 carrier 明写 `reserved, no early signature`，不能据此声称已经取得 `DUAL`。
最终放行仍只属于 operator。因此本条从初次“不满足”更正为满足。

### P4 — candidate 身份

命令：

```text
git status --short --branch
git rev-parse HEAD
git rev-parse HEAD^{tree}
git rev-parse HEAD^
gh run view 33045607052 --repo TERRYYYC/fakexxx --json databaseId,event,headSha,status,conclusion
```

原始输出：

```text
## main...origin/main
bfc75e272ff8cbf20ecc523cee63bba2348e68d6
81c1434969468d9c69bcfaa6298ed9616c7390d9
85875b0c38afc496b3d30602ea3d57f2ec272dc5
run=33045607052 event=push headSha=bfc75e272ff8cbf20ecc523cee63bba2348e68d6 status=completed conclusion=success
```

`git status` 没有 file 行，工作树 clean；full/tree/base/run 均已记录。

### P5 — current-HEAD CI

命令：

```text
HEAD_SHA=$(git rev-parse HEAD)
gh api "repos/TERRYYYC/fakexxx/commits/${HEAD_SHA}/check-runs" \
  --jq '{total_count,checks:[.check_runs[]|{name,status,conclusion,head_sha}]}'
```

原始输出为 `total_count=7`；七个对象的 `head_sha` 全为
`bfc75e272ff8cbf20ecc523cee63bba2348e68d6`，且全部
`status=completed, conclusion=success`：

```text
release-debt (selftest + matrix coverage)
cellrebel-auto (unit + lint + assemble)
install guards (F-18 selftests)
contract-v1 (static guards + both roots)
acceptance (matrix tests + static guard)
qianwangyou (unit + lint + assemble)
provenance (frozen upstream roots + reachable ancestry)
```

### P6 — 两 app 同 candidate 构建

P5 的两个 exact-head check-run 名称与原始 `head_sha` 分别为：

```text
cellrebel-auto (unit + lint + assemble)  bfc75e272ff8cbf20ecc523cee63bba2348e68d6  success
qianwangyou (unit + lint + assemble)     bfc75e272ff8cbf20ecc523cee63bba2348e68d6  success
```

这证明 machine build 来自同一 candidate；不把它扩写成 artifact 与实装 APK 已相等。另一个只读连续性
检查说明 S2 到当前 candidate 只有 runner/docs 变化，不能替代 S3 字节重取：

```text
git diff --quiet 824f077bd344f05fd25b6e7938e83df9ce3b56ba..bfc75e272ff8cbf20ecc523cee63bba2348e68d6 -- apps/qianwangyou/app
qwy-app-diff-exit=0
git diff --quiet 824f077bd344f05fd25b6e7938e83df9ce3b56ba..bfc75e272ff8cbf20ecc523cee63bba2348e68d6 -- apps/cellrebel-auto/app
auto-app-diff-exit=0
```

### P7 — device 身份与单设备

run2 的 exact probes 与原始输出为：

```text
$ adb devices -l
List of devices attached
ZY22JHW9M4  device ... model:moto_g54_5G ... transport_id:54

$ adb devices | grep -c 'device$'
1
$ adb get-serialno
ZY22JHW9M4
$ adb shell getprop ro.product.model
moto g54 5G
$ adb shell getprop ro.build.version.release
15
$ adb shell getprop ro.build.version.sdk
35
```

对应 `probe-p7-*` 七个 payload 均保存空 stderr 与 exit 0；单设备、exact serial/model/OS/API 成立。

### P8 — operator 指定与当前有效配置

operator 在调度线收到“启用 `name.caiyao.fakegps.bench`、配置 scope、把 mock-location app 指向
`.bench`”的逐项提示后，原始回复为：

```text
message 0001787771272687-001170-396f5cd9
ok了，我要求测试的位置选择基辅
```

声明不能代替实测。初版曾只用 `immutable=1` 读取主库；该方法在存在非空 WAL 时会跳过 WAL，因此单独
使用并不足以承重。本次质量门重新从冻结三件套制作临时字节拷贝后做普通 WAL-aware 读取；初版 WAL
恰为空（SHA-256 `e3b0c442…`），`PRAGMA integrity_check=ok`，最终仍确认：

```text
sqlite3 '/tmp/<copy>/modules_config.db' 'PRAGMA integrity_check;'
ok

SELECT module_pkg_name, enabled FROM modules ORDER BY mid;
lspd|0
name.caiyao.fakegps|1
name.caiyao.fakegps.bench|0

SELECT ... FROM modules JOIN scope ... WHERE module_pkg_name='name.caiyao.fakegps.bench';
(0 rows)
```

初版设备输出另显示 mock-location appops allow holder 只有 `name.caiyao.fakegps.bench`，当前 mock grep
exit=1。mock 半边成立，LSPosed 半边明确不成立，因此整条 FAIL；执行者没有写 LSPosed 私库。

关于装机顺序，包作者拒绝把 `install` 重新解释成“不是 device write”。当前 candidate 的两个 app 子树
相对 S2 均无变化，因此下一步先在 host 重建、只读 pull 当前实装字节并比 signer；字节相同则不重装，
再由 operator 恢复 module/scope。若字节不同则停，须另行修订并由 operator 接受受限 environment-setup
lease，不能口头绕过 §3。

该分支已经用 clean isolated checkout 执行，冻结包为
`/Users/terry/Desktop/coding/g2-install-necessity-run2-20260827/`，manifest digest
`7f671c30aa991995c40ca2fb0c1aa71f01c73e0eeb17d59fc91ff9dac1234e9b`（28/28 checksum 与
文件集合复算通过）：

```text
candidate: bfc75e272ff8cbf20ecc523cee63bba2348e68d6
artifact bench: f288b41805cf03dc4a038f8b315fb841a64ed21bd3ed6062cea769219ca58a26
device bench:   da508aa83d28ae6adc32945c723151b0af99e8aac7b6cd9fb40c9b4846b9cf46
bench cmp:      EXIT 1 (differ)
artifact auto:  ddf5fbd577a0b07a9d1d350b8d11faeed31e7549827a2f9947376d51de915999
device auto:    ddf5fbd577a0b07a9d1d350b8d11faeed31e7549827a2f9947376d51de915999
all signers:    7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41
```

执行侧 `verdict-assembly.txt` 曾把 bench 误写为 identical：它在整份 probe 中搜索
`BYTES-IDENTICAL`，而失败 probe 的 command 行本身含有 `&& echo BYTES-IDENTICAL`，所以即使 stdout
明确写 `differ`、exit 为 1 仍会命中。独立解包比较显示两 APK entry set 相同，但 `classes9.dex` payload
不同（device `a9d9572d…`，artifact `a7aa32f7…`）；这不是仅由 ZIP metadata 引起的 byte drift。依照
预先冻结的分支，本轮必须停在安装前，`NO_INSTALL` 结论作废。

operator 随后恢复配置的第一份正式复核包
`/Users/terry/Desktop/coding/g2-p8-reverify-20260827/`（manifest digest `d07674b0…`）读到了
`bench enabled=1`、scope 9 且包含 `com.example.cellrebelauto`，但它不能成为有效的冻结证据：collector
直接用普通 sqlite 打开原始三件套后才生成 manifest，WAL recovery/checkpoint 将 db 从 `5851cf20…`
改为 `f4691ada…`、将 416152-byte WAL `cd841f80…` 截断为 0-byte `e3b0c442…`，SHM 也发生变化。
因此原始 WAL 字节已被读取动作改写，且包内没有 recovered snapshot 的 `PRAGMA integrity_check`。
该目录保留为 `REQUEST_CHANGES` 历史输入，不回填；新的 P8 包必须将永不打开的 `raw/` 三件套与仅供
WAL-aware 查询的 `query/` 副本分离，并证明 raw 最终 hash 等于 pull 时 hash。

修正后的主包为 `/Users/terry/Desktop/coding/g2-p8-reverify-run2-20260827/`，manifest digest
`a1b8a8750246dd2422568764e6e62a6c2686dca5b4ec3d7f42e1782837665515`。独立复算 34/34
payload checksum 通过，目录文件集合与 manifest 无差异；collector 静态搜索没有设备写命令。raw 三件套
pull 时与最终 hash 逐字相同，非空 WAL 原字节被保留：

```text
db:  5851cf2029b2ecdd8caa50fee58187501818461d83f36a2ed21ab6e8d636b141
wal: cd841f804ce4cd1d9557c07691ff9415a574b11afd6a5985a228712a853aab78 (416152 bytes)
shm: 4c018b4dd82cd7b2c5637bd74266a8ad12c5e7776dcec38736b2a36041ed5a27
```

独立记录者从 raw 的临时字节拷贝重放，`PRAGMA integrity_check=ok`；读得 production 与 bench module
均 `enabled=1`，bench scope 9 行且包含 `com.example.cellrebelauto`；appops allow holder 只有 bench，
hopefactory 与 production 为 deny，`[mock]` provider grep exit 1。以上只证明**当前实装状态**；由于
current-candidate bench 字节不同并须在另行授权后安装，这份 scope 证据将在安装后 stale，不能把 P8
改判为 current-candidate 满足。

scope 交集另由 `/Users/terry/Desktop/coding/g2-p8-overlap-addendum-20260827/` 冻结，manifest digest
`1bcde703b4e1dbd691abbcf602ab855d5911935dad6478eabf088db529c84cf4`，15/15 payload checksum 与
文件集合通过。production scope 7 行、bench scope 9 行；交集 5 个 app：

```text
com.cellrebel.mobile
com.google.android.apps.maps
com.hopefactory2021.fakegpslocation
make.more.r2d2.cellular_z.play
make.more.r2d2.play.cellular_pro
```

Auto client `com.example.cellrebelauto` 不在 production scope，但测量执行 app `com.cellrebel.mobile` 在
交集内，仍可能同时加载 production 与 `.bench` 两套 hook，行为证据不能唯一归因于隔离 bench module。
由 operator 决定测试前停 production module 或移除与被测 client 的重叠 scope；猫不得代写 LSPosed
私库，且 operator 处置后必须再以相同 raw/query 证据契约复核。

### P9 — 公开坐标与隔离 profile

命令：

```text
jq '{fixtureId,fixtureVersion,scheduleId,count:(.items|length),totalRequiredSuccesses,
     computedRequiredSuccesses:([.items[].requiredSuccesses]|add),
     ids:[.items[].journeyCaseId],profiles:[.items[].expectedScheduleItemId],
     latRange:[([.items[].latitude]|min),([.items[].latitude]|max)],
     lonRange:[([.items[].longitude]|min),([.items[].longitude]|max)],isolation}' \
  docs/acceptance/a-plus-10a-fixture.json
shasum -a 256 docs/acceptance/a-plus-10a-fixture.json
```

原始输出：

```text
fixtureId=FX-G2-10A fixtureVersion=1 scheduleId=qwy-default-schedule
count=10 totalRequiredSuccesses=17 computedRequiredSuccesses=17
ids=J10A-01..J10A-10 profiles=profile-1..profile-10
latRange=[50.3800,50.4636] lonRange=[30.4622,30.5631]
isolation=PROD=G3 — public test coordinates only; seeded into the isolated .bench debug package;
          production package name.caiyao.fakegps and production user data are never touched
cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852
```

fixture 明确是公开基辅坐标与隔离 `.bench` profile，判满足。

### P10 — 逐故障注入退出与归真

命令：

```text
sed -n '233,261p' docs/acceptance/issue7-g2-acceptance-package.md
```

原始要求至少包含：

```text
B: RELEASE_INCOMPLETE / 进程重启 / generation 断裂 / §8.4 state-aware 分流
   + 至少一个 Auto checkpoint 崩溃窗口
   + release receipt 丢失重放
C: qwy 撤销 caller（新 run 前、run 中）
   + Auto 撤销 provider（新 run 前、run 中）
```

初版审计表只给出两种通用 mock 清法：bench stop 与 `adb reboot`。其中历史实证已经证明
`am force-stop` 无法移除 `LocationManagerService` 中的第三方 mock override，而 reboot 后 mock 段为
0；这只证明一个最终 mock 清除出口，**没有**逐项冻结上述注入的注入命令、正常退出、durable-state
读回、恢复动作及中止点。package 本身还要求“选择的窗口、注入法与对应 canonical `M-CR-*` 必须在
编排前列明”，当前尚无该执行矩阵，故判不满足。

进一步的源码可达性审计证明，缺口不只是“表还没写”，而是当前候选没有覆盖这些场景的真机入口：

```text
$ rg -n 'onCallerRevoked\(' apps/qianwangyou --glob '!**/build/**'
apps/qianwangyou/app/src/main/java/.../EnvironmentControlHandler.kt:795:
    fun onCallerRevoked(applicationId: String, signerDigest: String): Unit = withOwnerFence {
apps/qianwangyou/app/src/test/java/.../LeaseMatrixTest.kt:85:  h.handler.onCallerRevoked(...)
apps/qianwangyou/app/src/test/java/.../LeaseMatrixTest.kt:154: h.handler.onCallerRevoked(...)
apps/qianwangyou/app/src/test/java/.../LeaseMatrixTest.kt:246: h.handler.onCallerRevoked(...)

$ rg -n 'revoke|crash|checkpoint|fault|inject' \
    apps/qianwangyou/app/src/debug apps/cellrebel-auto/app/src/debug
(qwy/Auto debug manifests 与 debug Kotlin 中无对应 fault/revoke injection surface)
```

qwy 的 revoke 实现只有 JVM test 调用，debug `PairingApprovalActivity` 只提供 list/approve；Auto 的
provider revoke 只有主 UI `MainViewModel.revokeProvider`，没有可冻结的命令入口或 in-flight 窗口协议；
`FullLoopProbeActivity` 只运行完整 loop，也不能在指定 Auto checkpoint、provider
`RELEASE_INCOMPLETE` 或 release-receipt 丢失窗口做确定性注入。现有 `M-CR-*` JVM matrix 是机器证据，
不能替代包明文要求的真机场景。因此不能先编造一张看似可执行的矩阵；须先落一个 debug-only、能按
exact window 触发并直接回读 durable state 的 collector，并证明 release 构建不含它，之后才能冻结
逐注入矩阵。另一条合法路径是修改 accepted package 并重新取得 operator 接受；包作者不得静默放宽。

通用 safety 底线仍有效，但不冒充完整 P10：任何注入后先尝试 canonical release/stop；若直接状态查询
不能证明 mock 清除，立即停止本场并进入 operator 人工恢复；已证明 `force-stop` 不够，operator 授权的
reboot 可作为最后清场手段，且 reboot 后仍须以 `dumpsys location` 直接状态而非坐标代理判定。

### P11 — production package 不触碰

run2 exact probes：

```text
$ adb shell dumpsys package name.caiyao.fakegps | grep -E 'firstInstallTime|lastUpdateTime'
lastUpdateTime=2026-08-11 15:07:12
firstInstallTime=2026-08-01 19:48:00
$ adb shell pm path name.caiyao.fakegps
package:/data/app/.../name.caiyao.fakegps-.../base.apk
```

两条均保存空 stderr 与 exit 0；`lastUpdateTime` 早于 S1/S2，当前仍只有一个 production `base.apk`。
结合 `PROD=G3` 选择与每次派单的 production 硬排除，本条在**审计时点**满足。它不是对未来的永久
外推，也不是仅靠 package metadata 就能做出的历史 `pm clear` 取证；未来每个场次仍需在进入／离场两侧
复证，任何 install/replace/clear 都必须使本条失败并立即停止。

## 边界与下一步

1. P3 已由当前 thread 四颗 explicit-owner carrier 闭合；Issue 角色表仍不是 TaskStore custody 的替代品。
2. P10 必须先补可达的 debug-only fault/revoke collector，再在任何故障注入场次前冻结逐项
   exit/restore matrix；两种通用 mock 清法不是完整矩阵。
3. P8 闭合前不得运行 readiness；P8/P10 任一未闭都不得下发 S3。
4. 本记录不占 evidence validity 或 `DUAL` 槽，不授权 G2 放行。

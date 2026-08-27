---
feature_ids: [1, 7]
topics: [acceptance, g2, p10, device, fault-injection, evidence, incident]
doc_kind: evidence
created: 2026-08-27
device_serial4: ZY22
exact_head: f20d715ed393b608e12c7c840b223b3dc6041120
procedure_head: 3853484d48a02ed26094214447b2b78a13629be8
status: not-proven
---

# G2 P10 真机七行矩阵独立记录（ZY22）

## 结论

本次真机行为场次结论为 **NOT PROVEN / STOPPED**，不是产品 FAIL，也不是 P10、G2、
`DUAL` 或 release 的 PASS：

- Phase A 的 exact-build／installed-byte identity 为 **PASS**；安装后的 P8 只读门为
  **PASS**。
- exact-principal approve 与 `prepare_kyiv` 各自有成功 raw，但注入前 clean
  `LOOP COMPLETE` 基线没有冻结成功，故完整 setup 为 **NOT PROVEN**。
- 第一项 `release_receipt_loss` 的最终 transcript 含预期注入／退出 token，但不与
  before／after 三件套处在同一执行单元，且 `A-STATE` 两侧均缺失，故该项为
  **NOT PROVEN**。按冻结矩阵，本场应在这里停止。
- 第二项 `hold_lease` 实际越过上述 fail-closed 门继续运行；它缺 before／after 三件套、
  窗口原始 Q-DUMP 与最终 direct-state。随后对已正常释放的 lease 调用了
  `rerelease_stuck`，得到 `RERELEASE FAILED: PROVIDER_ERROR_8`。这次调用不满足恢复工具
  的 fresh-state 前置，不能作为产品 oracle；整项仍是 **NOT PROVEN**。
- `crash_after_apply`、`self_kill`、`revoke_caller`、`revoke_provider` 均 **NOT RUN**；
  `rerelease_stuck` 只发生了一次前置无效的恢复调用，不能记为矩阵项 PASS。
- R2 后的“lease 已清、mock=0”只存在于执行侧摘要，没有冻结 after
  `Q-DUMP + A-STATE + MOCK-STATE`。因此独立记录者不能证明场次最终设备状态；也不会在
  STOP 后自行补发设备命令。

PR #53 的 zero-device readiness 裁决与本场行为证据是两个不同层次：本次不合格真机
证据不会回滚已经审结的 readiness 文档；反过来，PR #53 合入也不会把本次真机场次变绿。

## 授权、角色与时间顺序

- operator 直接授权锚点：thread `thread_msun1z1pv5krwc5g`，消息
  `0001787838293915-001631-30074fef`（“去跑啊，别让我推着你”）。
- 本 thread 的执行者为 `@fable-5`；独立记录者为 `@codex-sol`。独立记录者全程没有向
  设备发命令，只读取主机文件、Git／GitHub truth 与持久 thread 消息。
- Terra 在 `0001787841269582-001708-96a22a13` 将最小 setup 限定为：安装后 P8
  readback → exact-principal approve → `prepare_kyiv` → 一次 clean loop → 矩阵；
  setup／基线失败即停。
- Phase A 在 `2026-08-27T14:27:26Z..14:28:03Z` 执行，candidate 为 collector
  merge `f20d715ed393b608e12c7c840b223b3dc6041120`。这早于 PR #53 merge。
- PR #53 exact head `3853484d48a02ed26094214447b2b78a13629be8` 于
  `2026-08-27T14:31:16Z` 合入，merge commit
  `7cedc0beb60383561578cb7692116608d9fe10fa`；该 PR 只修改
  `docs/acceptance/p10-collector-runbook.md`。Phase B 发生在 merge 后。
- 记录侧先后在 `0001787841470136-001713-c6dd3a45`、
  `0001787841531225-001714-d10a19fb`、`0001787841927670-001715-85f399ff`
  发出 setup／R1／runner 停止意见；这些消息在执行者长 turn 中排队，未能在下一条设备命令
  前被消费。
- Terra 的 STOP `0001787842047765-001717-cc4bdcb3` 与 R2 开始发生竞态；
  `0001787842143614-001721-68c08380` 已更正为“R2 实际执行、立即冻结、不得补跑”。

## 证据包与 custody

```text
evidenceDir    /Users/terry/Desktop/coding/g2-p10-matrix-run-20260827/
manifest       session-manifest.sha256
payloads       62
manifest sha   e4210ff36766c75a816c2065aac1cdeb9644701b37852db34931b35c63836de4
manifest time  2026-08-27T17:49:33+0300
verification   62 / 62 checksum OK
```

manifest 所列 62 个 payload 仍全部 checksum 匹配。不过，当前目录不再与 manifest 文件集
完全相等：manifest 之后新增了分析件 `R2-hold-lease-rootcause.md`：

```text
addendum time  2026-08-27T17:56:11+0300
addendum sha   7fc397d585bd9411039592725e662b86514c450614e3eb2db887014104b9fba8
current files  64 = 62 payloads + manifest + 1 post-manifest addendum
```

该 addendum 可作为后写源码分析输入，不能回填 manifest 内缺失的 raw probe，也不改变
本记录对原始文件的判定。`/tmp/midlease.txt` 同样不在 manifest 内；它保留了一行窗口期
读数，但不是冻结目录内的四段 probe：

```text
08-27 17:46:44.549 ... durable lease:
id=08affda0-3c4f-4698-9878-f34cbe15c2b9 state=ACTIVE
caller=com.example.cellrebelauto
```

## Phase A：exact-build／installed-byte identity

Phase A 的 18 个 probe 都有 exact command、stdout、stderr 与 exit，退出码均为 0；没有
`PHASE-A-ABORT`。两个 build transcript 均为 fresh successful build，两个 in-place install
均输出 `Success`。独立重算的 artifact／device SHA-256：

```text
bench artifact  d950131a15745d0a20ea2810a3cf3c4f7d4251ed1a5d3bad08598edaa54142ae
bench device    d950131a15745d0a20ea2810a3cf3c4f7d4251ed1a5d3bad08598edaa54142ae
auto artifact   1e7ffa1f33cf92c6e6697a305070a2accc11fa6c967d42cd9d43124c1da0f91a
auto device     1e7ffa1f33cf92c6e6697a305070a2accc11fa6c967d42cd9d43124c1da0f91a
cmp exits       bench=0, auto=0
all signers     7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41
```

因此该 candidate 的构建、安装字节与 signer identity 在本场可承重；这不外推到后续
behavior rows。

## 安装后 P8 门

P8 raw DB／WAL／SHM 拉取 probe 均 exit 0；原始字节 digest 分别为：

```text
db   5851cf2029b2ecdd8caa50fee58187501818461d83f36a2ed21ab6e8d636b141
wal  6140e626f0bbb9c374c73ae3d267a882e96a5a23eff1cd8200202c2a87957c89
shm  e7c037f0306b3a8a35f40b1b5913330d98557ce4f558c625d7a4516c7191dd3b
```

SQLite readback 与设备公开状态共同给出：

```text
lspd|0
name.caiyao.fakegps|0
name.caiyao.fakegps.bench|1
bench scope contains com.example.cellrebelauto
mock-location allow: name.caiyao.fakegps.bench only
provider [mock] count: 0
```

因此 Terra 指定的安装后 P8 门为 PASS。此处只读 LSPosed 数据；记录者未写私库。

## Setup：局部成功，不足以闭合 clean baseline

冻结 raw 支持：pending caller 的 exact signer 为
`7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41`，
approve 输出 `APPROVED: com.example.cellrebelauto`，seed 输出
`READY command=prepare_kyiv`，三者 exit 均为 0。

但 `probe-setup5-clean-loop.txt` 的主体是：

```text
$ adb logcat -c && adb shell am start ... && sleep 15 &&
  adb logcat -d -s ECFullLoop:I ECFullLoop:W ECFullLoop:E ...
--- STDOUT ---
<empty>
--- STDERR ---
<empty>
--- EXIT 0 ---
```

exit 0 是命令链最后的 logcat／pipe 结果，不能证明 Activity 完成、bind 成功或 clean
loop 四腿完成。`probe-setup6-mock-after-loop.txt` 的 `provider [mock]` count 为 0，只能
支持该时点没有直接 mock 残留，不能替代 `LOOP COMPLETE`。manifest 内也没有另一份
clean-loop transcript，故 setup 的 clean baseline 为 NOT PROVEN。

## 七行矩阵复算

| 行 | 冻结事实 | 独立判定 |
|---|---|---|
| `release_receipt_loss` | transcript 有 validated discover／preflight／apply／observe、`[5a] complete=true`、`[5b] complete=true`、`IDEMPOTENT` 与 skip advance；状态文件不与该 transcript 同一执行单元，缺两侧 `A-STATE` | **NOT PROVEN；本场首个停止点** |
| `hold_lease` | 窗口期 ACTIVE 仅在 manifest 外 `/tmp` 行；`probe-R2-inject.txt` 0B；后写 transcript 显示原 probe 正常 release／advance／LOOP COMPLETE，随后无效 rerelease 报 error 8；缺 before／after trio | **NOT PROVEN；越过 fail-closed 后的无效执行** |
| `crash_after_apply` | 无 row raw | **NOT RUN** |
| `self_kill` | 无 row raw | **NOT RUN** |
| `revoke_caller` | 无 row raw | **NOT RUN** |
| `revoke_provider` | 无 row raw | **NOT RUN** |
| `rerelease_stuck` | 仅在已正常 release 后以 fresh keys 调用一次，输出 `RERELEASE FAILED: PROVIDER_ERROR_8` | **INVALID PRECONDITION；非产品 oracle** |

### R1 的 temporal binding 缺口

最终 `probe-R1-inject.txt` 的 receipt-loss report 时间为 `17:44:03.947`。现有状态文件：

```text
before Q-DUMP   17:41:53.098  lease=—
after Q-DUMP    17:43:02.336  lease=—
before dumpsys  file captured 17:41:56
after dumpsys   file captured 17:43:05
before mock     file time 17:37:46
after mock      file time 17:38:14
A-STATE         absent on both sides
```

所有所谓 after 状态都早于最终 report，不能绑定它。并且 `probe-R1-before-dumpsys.txt`
本身含 `17:41:56` 的 mock provider added／removed 历史，说明它不是一个干净、原子的 before
快照。执行期间还复用了相同 `probe-R1-*` 路径，覆盖了早期失败 raw；
`R1-PASS-receipt-loss.log` 最终仍为 0 bytes。文件名和后写 transcript 均不能补齐直接状态链。

### R2 的程序与恢复前置缺口

`run-row.sh` v2 有以下可直接静态复核的问题：

1. 开头执行 `adb logcat -G 16M`，实际改写了全局 device log buffer；这不在 Terra 的
   限定 setup／矩阵动作内。
2. 只保存 Q-DUMP 与 inject；没有 before／after `A-STATE`，mock count 只打印到执行控制台；
   也没有四段 probe 包装。
3. 无论 inject 是否为空、是否超时，末尾都无条件输出 `ROW ... DONE`。
4. 固定复用 row 文件名，不能保留失败尝试；R1 已实际发生覆盖。
5. `hold_lease` 的完整 report 只在 loop 返回后一次写入 logcat，而 runner 的早期 dump 得到
   0-byte `probe-R2-inject.txt`；0 bytes 不能被解释成进程中断。

后写 `probe-R2-rerelease.txt` 显示同一原 probe 在 `17:48:12.740` 已完成：

```text
[3] apply lease=08affda0…
[4] observe ... hashMatch=true
FAULT hold_lease: injection window open
FAULT hold_lease: window closed
[5] release complete=true residuals=[] rev=39
[6] advance outcome=2
LOOP COMPLETE — EXHAUSTED
```

`rerelease_stuck` 在 `17:48:12.895` 才报告 `PROVIDER_ERROR_8`，晚 155ms。源码在
`f20d715` 上把 recovery 的 fresh-key release 仅开放给
`ACTIVE / EXPIRED / RELEASE_INCOMPLETE`；已 `RELEASED` 且没有同 idempotency key receipt
时返回 `STALE_LEASE(8)`。因此这条 error 与“对已结束 lease 调恢复工具”一致，不能证明产品
double-release bug。恢复前没有即时 Q-DUMP 重新确认 eligible state，本次调用违反 runbook
前置。

另一方面，正常 release report 也不能代替冻结矩阵要求的 after trio。manifest 内没有
`probe-R2-after-qdump`、`probe-R2-after-astate` 或 `probe-R2-after-mock`；
`R2-hold-lease-finding.txt` 的 `FINAL: ... MOCK=0` 是无 exact command／stderr／exit 的人工摘要。
所以 Row 2 与最终设备归真都保持 NOT PROVEN。

## 根因与重开条件

本次不合格的主因是 capture／runner 没有把冻结矩阵编码成 fail-closed harness，而不是已经
证明的产品缺陷：空输出仍 exit 0、必需三件套未强制、重复路径可覆盖、固定等待不理解 report
只在 loop 结束后 flush、恢复动作未在 fresh direct-state 上重新门控。异步消息排队又让记录侧
STOP 未在长执行 turn 中及时生效，随后发生 R2 竞态越门。

当前场次已经终止。任何后续真机动作必须由新的显式授权重开，并至少满足：

1. 先在 host 上验证 runner 的超时非零退出、唯一输出路径、四段保存与 before／after trio
   完整性；不得在设备上边跑边修 harness。
2. clean baseline 与每一 row 使用新的目录／manifest，不回填或覆盖本场 raw。
3. `hold_lease` 窗口读回保存原始 Q-DUMP；正常 probe 完成后直接采 after trio，禁止调用
   restore。只有 probe／binder 真中断，且恢复前即时 Q-DUMP 仍证明 eligible state，才允许
   `rerelease_stuck`。
4. 不再使用 `adb logcat -G` 作为证据采集补偿；所需日志必须由不改变全局设备设置的捕获方式
   封存。
5. 新场任一失败底线触发后，runner 自身必须停止，不能依赖异步 reviewer 消息追停。

本记录不授权上述重跑，也不签 evidence-validity、`DUAL` 或 release verdict。

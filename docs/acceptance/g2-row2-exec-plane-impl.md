---
feature_ids: [1, 7]
topics: [acceptance, g2, p10, row2, execution-plane, runner, classifier, host]
doc_kind: implementation
created: 2026-08-28
status: host-side-green
contract: PR #55 blob c072c83fa979cf9d222a544faf8366e6fa691d21
---

# G2 Row 2 执行面实现：runner + V2 classifier + executable manifest + execution packet

本文记录 Row 2 证据契约（PR #55，blob `c072c83f`）所要求、而 main `507b78d` 上不存在的
四件执行面交付物在本 PR 的落地。**全程 host 侧、零设备命令**（调度线硬边界 1）；设备执行
归 @fable5，在 Fable `EXECUTABLE` verdict + operator 新授权之后另派。

## 红线记录（red first）

commit `0031744`：`check-row2-exec.sh` PRESENCE 段在 main 基线上 7 failures——无 runner、
无 classifier、无 envelope lib、无 packet 工具、无 fixtures、无 manifest 机制、后续段落无法
运行。这正是「66 条 checklist 只有文档」的机械证明。

dex 扫描守卫红线（同一 PR）：把 `FullLoopProbeActivity`/`HandshakeProbeActivity` 加入禁用
符号表后守卫立刻红——`SerialProbeRunner.kt` 的两处**注释级**引用是唯一阻挡；契约绑定的
四个 am-start 组件此前完全不在守卫覆盖内。

## 交付物

| 契约缺什么 | 本 PR 交付 | 契约依据 |
|---|---|---|
| ① runner | `scripts/row2/row2-runner.sh`：supervisor（唯一 spawn call site：locationId→canonical path、symlink/非常规文件拒绝、SHA-256+mode exec 前后校验、clean-env 子 shell、stdin=/dev/null、六文件 carrier 写盘）+ 冻结 leaf 面（`parse/audit/gate/seal`，exact 6 argv，builtins only） | §3.1-8、§3.2 |
| ② V2 classifier | `scripts/row2/row2-classifier-v2.sh`：`ROW2-EXEC-ACCESS-V2` 闭合 grammar（14 HOST + 11 ADB-READ + 8 ADB-WRITE = 33 rules），envelope 预门（canonical JSON、env/stdin policy、cwdRef、64-hex digest、manifest 绑定），全规则求值、命中数 ≠1 即 `CLASSIFIER-REJECT`；145-fixture corpus（61 正例 / 84 负例）全绿 | §3.1.1 |
| ③ executable manifest | `row2-packet.sh manifest-freeze`：经 runner 源内冻结 LOCATION 表解析 canonical path，真实 SHA-256 + stat mode 落 `meta/executable-manifest.json`；`executable-manifest.template.json` 为评审模板 | §3.1-8、PRE-02 |
| ④ execution packet | `row2-packet.sh build/validate`：canonical key-order 构建器（carrier stem 机械派生）+ PRE-00 校验器（schemaVersion、top-level key 序列精确游走=无未知/乱序键、seq 连续唯一、carrier 路径唯一、envelope 完整性、**零 access-label 字段**、顶层 policy 字面量） | §3.1、§3.1-4、PRE-00 |

生产 gate `check-row2-exec.sh` 九段全绿；`selftest-row2-exec.sh` 17 用例（含调度点名的
三个 mutation：逃逸命令被放行 / 四元组缺失报成功 / packet-carrier 不匹配继续，各配 M-*
load-bearing 证明；R7/M6+M8 覆盖 gpt55 review 的 F3/F1）；CI 新增 `row2-exec` job。dex 守卫扩展见提交 `9085151`。

## 冻结解释账本（I1–I11）

契约把 grammar 以文本冻结、本 PR 以代码冻结。实现与文本的每一处解释差都在 payload
头部逐条声明；**发现分歧按契约缺陷登记，不允许悄悄放宽本文件**：

- classifier `row2-classifier-v2.sh` I1–I7：env policy 二元（base / Git 扩展
  `ROW2-CLEAN-ENV-GIT-V1`）；HOST-PROCESS sleep 以 packet `terminalTimeoutSeconds` 为上界；
  kill 的 PID 归属是 launcher 运行时属性；sort/manifest.sha256/check-form 钉 cwdRef=evidence；
  HOST-TEXT pattern 1..512 printable-ASCII 并输出其 SHA-256（纯 bash 实现）；repo payload
  token 走 locationId 而非 cwd；ADB-READ-LSPOSED 的 `su -c` 为单元素 `cat <p8-path>`。
- runner `row2-runner.sh` I8–I11：launcher 自身的 stat/shasum/date 校验 spawn 走同一
  call site 但属 launcher 机制（§3.1-8 本就把逐份校验定义为 launcher 的一部分），packet
  leaf 仍一 unit 一进程；stdout carrier 的 .txt/.bin 由 packet 冻结；write budget =
  正常路径 ≤12 + 首写必须 `ADB-WRITE-PREPARE` + RST-01 仅在 TERM-04 后（Sol gate-B 冻结
  的机械化）；clean-env 子 shell 中 bash 自身注入的 `_` 是 execve 惯例 artifact（值为被
  执行程序的 canonical path，非继承数据），sentinel 证明其余键只余 LC_ALL/LANG/TZ。

## 未做 / 边界遵守

- **零设备命令**：adb 仅作为 classifier grammar 与 manifest 条目存在；本线从未 exec adb。
- **不动 gate 状态、不碰 Issue #1 载体、不碰 PR #55**（分支基于 `507b78d`，契约以 blob
  sha 引用；#55 合并后由后续提交把引用重指 merged 路径）。
- **真实 packet 冻结未发生**：serial/构建产物摘要/signer 等真实值要等隔离构建与设备授权
  流程；本 PR 交付的是机制 + fixture 值自洽的 host-only 场次。candidateHead 必须是
  `507b78d` 的 descendant（契约 §10）。
- **66 条一条未减**；未发现物理上实现不了的条目（此前 `take(8)` blocker 已由 #56 关闭）。

## 给 Fable feasibility review 的入口

审这三个文件即覆盖执行面全部冻结字节：`row2-classifier-v2.sh`（+fixtures）、
`row2-runner.sh`、`row2-packet.sh`（+ `row2-envelope.sh` lib）。核对维度按契约 §9：
packet/runner/classifier/manifest/policy 逐 digest 绑定、闭合 grammar 与 §3.1.1 表逐条
对应、carrier 六文件机制、write budget、解释账本 I1–I11 是否可接受。

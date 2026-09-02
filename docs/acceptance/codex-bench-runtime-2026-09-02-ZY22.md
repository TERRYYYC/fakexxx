---
feature_ids: [G2-66]
topics: [codex-bench, android, device, pairing, binder, cleanup]
doc_kind: evidence
created: 2026-09-02
device_serial4: ZY22
exact_code_head: 51e330e11846eab405a66b0f88203e0d8ad70dc9
status: runtime-blocked-cleaned
---

# Moto codex-bench：启动与配对通过，定位验证受阻

## 结论

两只独立测试版能启动，未配对调用被拒绝，批准指定 caller 后真实跨进程握手成功；
Auto 的 provider 管理界面也完成了独立的 provider 批准。
但普通 FullLoop probe 返回 **verification=NONE、continuity=NONE、transport=false**。
**未验证模拟定位有效；#66 AC7 NOT_PASSED / FULL BLOCKED。**
控制调用链结束的 `LOOP COMPLETE` 不是定位或生产自动化成功证据。

本轮只运行并诊断既有 exact APK，没有修改产品源码、启用新模块 hooks 或合入 PR。
详情见 [Binder 身份缺陷报告](../bug-report/moto-codex-bench-binder-identity/bug-report.md)；
后续修复跟踪：[Issue #71](https://github.com/TERRYYYC/fakexxx/issues/71)。

## 范围与身份

- operator 对协调运行窗口回复“按你的计划来”；本轮仅操作原授权的唯一 Moto。
- 先采集旧状态：旧 Auto 唯一历史 session 已是 `paused`，7 个任务均 pending、0 attempts、0/7。
  因而没有点击旧 Start / Resume / Stop，也没有禁用其无障碍授权；不把“进程活着”当作计划正在执行。
- 新两包身份、版本、签名及完整 SHA 见 [安装记录](codex-bench-install-2026-09-02-ZY22.md)。
  本轮再次核对实装 APK，仍逐一匹配相同产物；没有重新安装或替换旧 APK。
- Android 15 / API 35，fingerprint：
  `motorola/cancunf_g_sysenq/cancunf:15/V1TDS35H.83-20-5-8-4/d3b29e-8d7d82:user/release-keys`。
- 新 QWY PID 21035 / UID 10393，新 Auto PID 20916 / UID 10395（本次 Binder 调用时）。
- Android 应用信息页目视确认名称为 **千网游 · codex-bench** 和 **CellRebel Auto · codex-bench**。
  QWY 内部地图页仍显示通用标题 FakeGPS；不将其误写为已改内部标题。

## 实际验证

设备日志时间为 UTC+3；命令 sidecar 使用 UTC。以下事件均为 2026-09-02。

| 检查 | 结果及边界 |
| --- | --- |
| 新应用启动 | QWY 地图和 Auto 空计划页面可见；未导入 Auto 生产计划、未启动新的 CellRebel 测量 |
| 新 QWY seed | 仅新 sandbox 创建 `profile-1`；`prepare_kyiv` READY，随后仅重启新 QWY 以刷新 schedule |
| 16:06:18 未配对握手 | 只发现新 QWY；REFUSED / PROVIDER_ERROR_1，caller 未配对 |
| 指定 caller 批准 | 仅批准新 Auto application ID 与已测 signer digest，不迁移旧配对 |
| 16:07:22 再次握手 | CONNECTED，protocol 1，profile-1，schedule version 1；continuity 仍 NONE |
| Auto provider 信任 | 管理 UI 显示并批准新 QWY 与同一 signer；与绕过该信任库的 HandshakeProbe 分开核证 |
| 16:09:20 普通 FullLoop | discover/preflight/apply/observe/release/advance 返回；未运行 fault / crash / background-hold 分支 |
| 独立定位与连续性 | **未通过**；apply verif=3、observe coverage=3，枚举两者均为 NONE |

关键原始摘录：

```text
[3] apply → lease=febbc86c-83d0-44b0-a4e6-dd062d9cf438 rev=7 verif=3
[4] observe → rev=8 coverage=3 mode=null
    fingerprint=system-mock:unavailable:transport=false
[5] release → complete=true residuals=[] rev=9
[6] advance → outcome=2 from=profile-1 to=null
LOOP COMPLETE — EXHAUSTED
```

FullLoopProbeActivity 使用写死的 `trustedSuccessCount=3` / `quotaRequired=3` 调试 proof；
它没有完成 Auto 可信成功记账或真实业务。post-loop 系统快照没有 mock provider，
但没有保存 apply 中间窗口的独立系统样本，故不声称“从未瞬时注入过位置”。

## 阻塞与停测理由

1. **确定的 Binder 身份缺陷**：QWY 在处理已批准 Auto 的调用时读取自己的非导出
   AppInfoProvider，权限检查却带着 Auto PID/UID，产生 SecurityException。
2. **独立的配置传输前置未满足**：QWY 自有 worker 能读取 provider，但配置落到 app-private
   文件，日志仍 `published=false readable=false`。新 LSPosed 模块 baseline 是 disabled、无 scope。
3. 原始 framework 读回缺样本的具体原因尚未区分（异常、权限/归因、空 cache）；不能全部归因于上述任一项。

没有为了变绿而导出私有 provider、放宽 caller 策略或开启 debug 自我定位 hook。
当前 debug self-hook 可能替换验证代码所调用的 getLastKnownLocation，污染独立读回。
此外 production oracle fingerprint allowlist 仍为空，不能据本轮结果关闭 #66。

## 清理与旧状态核对

| 项目 | 终态证据 |
| --- | --- |
| 本轮 lease | release receipt complete=true；清理前 durable KV 中该 lease 明确 RELEASED |
| stop 命令 | READY 仅表示接收；最终状态为“GPS 已停止，但 Hook 配置发布失败”，不是 Idle |
| 系统 mock | 清理后 dumpsys 无 mock；GPS/network 为原生 provider 身份 |
| mock 授权 | 原唯一 allowed 包恢复；前后 query-op 输出字节相同；新 QWY 回到 default/deny |
| 新测试数据 | 仅两只 codexbench 包执行 pm clear，均 Success；两者 CE 根目录回读为空；随后仅 force-stop 新两包 |
| 新权限/进程 | fine/coarse/notification 均 false，stopped=true，进程列表无 codexbench；APK 保留安装 |
| 旧 Auto 数据 | 前后 WAL 都为空，完整 SQLite .dump 完全相同，终态 integrity=ok；paused、7 pending、0 attempts、0/7 |
| 旧 QWY 状态 | durable KV 与 settings 各自前后 SHA 完全相等；旧 cleanup_required=true 是基线，未擅自修复 |
| LSPosed | 没有配置写入/重启；原始 DB 与 WAL 前后 SHA 完全相等；prod disabled、旧 bench enabled、新 codex disabled/无 scope |
| 无障碍服务 | enabled 列表前后字节相同；终态 Android 报告旧 Auto 与原 Auto Clicker 均 Bound，Binding/Crashed 均为空 |
| 屏幕与前台 | stay_on_while_plugged_in 从临时 2 恢复原值 0；原 task 928 ConnectionTestActivity 回到前台后恢复 Dozing |
| 临时文件 | 仅删除本轮创建的 /sdcard/codexbench-window.xml；随后不存在检查退出 0；本机原始证据保留 |

**限制：不能声称旧业务全程无干扰或内存状态完全一致。**旧 Auto PID 从 32722 变为 19744，
日志记录多次 Service destroyed / connected；终态能确认重新绑定，不是持续无中断证明。
最后旧 Auto UI 采样为 Service OFF，虽随后系统报告 Bound，未再次目视证明按钮可用。
底部 Start Plan 与全 pending / 0 attempts 的旧版 UI 规则相符，不与历史 paused 矛盾；
没有为了“恢复”而启动一轮新计划。旧 QWY PID 694 保留。

## 证据与独立核验

本机原始材料在 `/tmp/fakexxx-moto-codex-run.WVPYZG/`；每条设备命令保存 `.meta`、
`.stdout`、`.stderr`（exact serial、UTC、退出码）。这是本机临时留存，不是 GitHub 可下载归档，
也不保证长期可用。手机原始 DB、个人任务坐标、完整设备转储不上传仓库。

```text
290e43ec1400578dd26eb6c232a9eabbcbcdbc62f1c919c0d3d271efaed70ed4  handshake-unpaired-final-log.stdout
a8d079dba05cff8f9d283323305960f7a3ce6b831f7fdd7f0118f7e6008b6065  handshake-paired-log.stdout
d4caccd83e2b784f3ebb141892e1c25f55e16554878323150e850f6b4a3e1e82  mock-loop-auto-log.stdout
baa7b973f6bfb1111202e22006cf45d593f11cd0f6888a3ca9c985a16ddb94e3  mock-loop-qwy-log.stdout
3ca517f9fc55beae095e6dce65db41052458903b5895082d73ab9c899a8c7150  new-stop-final-log.stdout
```

只读见证者 `moto_identity_audit` 核对 exact identity、配对、NONE 结果、数据库/WAL、
清理及原前台/休眠证据；未操作手机。`moto_test_path_audit` 独立核查两个阻塞与 self-hook 风险；
`moto_restore_audit` 还直接审查旧现装 APK 的 UI/服务逻辑，确认不能因历史 paused 而点击 Start。
这些是证据/诊断核验，不是对未实现修复的批准。

## 质量门禁与交接

- PR #70 的文档前 HEAD `675f6738f91f1c6aa721bca96be0a1fdcc21810f` 上，
  [10 个远端检查全部通过](https://github.com/TERRYYYC/fakexxx/actions/runs/33632314341)。
  它们是 host/build checks，不替代本轮失败的真机验证；本文不宣称后续提交已通过同一次 CI。
- **What / Why：** 提供独立包运行、配对、失败调用链与恢复证据，让手机可用目标的真实阻塞可复现。
- **Tradeoff：** 保留 fail-closed 与旧数据，不把流程结束或配置 commit 当成生效。
- **Open：** Binder 身份修复、真实共享传输、独立读回诊断、生产 oracle attestation、完整 Auto 业务仍未完成。
- **Next：** 先补跨进程身份回归测试再修复；单独验证传输与读回，审查后再安排 exact 新产物真机复验。
  PR #70 保持 draft，不合并、不关闭 #66。

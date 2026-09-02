---
feature_ids: [G2-66]
topics: [android, binder, identity, config-transport, codex-bench]
doc_kind: bug-report
created: 2026-09-02
status: diagnosed-not-fixed
---

# 已批准跨应用 apply 继承外部 Binder 身份，读取 QWY 私有 provider 被拒绝

Reporter: Codex，获授权的 Moto codex-bench 实机验证。**已复现并诊断，未修复。**
跟踪：[Issue #71](https://github.com/TERRYYYC/fakexxx/issues/71)。
代码 HEAD `51e330e11846eab405a66b0f88203e0d8ad70dc9`，PR #70（基于 #69）；
详见 [实机记录](../../acceptance/codex-bench-runtime-2026-09-02-ZY22.md) 和
[完整 APK/SHA 身份](../../acceptance/codex-bench-install-2026-09-02-ZY22.md)。

## 诊断胶囊

| 栏位 | 内容 |
| --- | --- |
| 现象 | 期望已批准 caller 的 apply 能由 QWY 读取自己的配置；实际私有 provider 权限错误，verification=NONE、transport=false |
| 证据 | Android 15/API 35；新 QWY PID/UID=21035/10393；新 Auto=20916/10395；16:09:19.983 的完整 Binder→controller→ConfigPrefsSync 栈 |
| 根因 | 远端 Binder identity 保留到 QWY 本地 ContentProvider query，权限检查误以 Auto 身份执行；另有独立的 app-private transport 前置阻塞 |
| 诊断策略 | 对照真实跨进程日志、UID、服务入口和私有 provider 调用链；以 QWY 自有 worker 成功建字段却传输失败作反例 |
| 超时策略 | 当前受控窗口在确定失败后停止；后续每次只改变一个变量，不能用反复开关 hooks 代替诊断 |
| 预警策略 | 导出私有 provider、放宽信任、只看 commit=true 或 LOOP COMPLETE、启用自我定位 hook 后读回变绿，均不能作为修复 |
| 用户可见修正 | 未来应将“控制流程结束”与“位置已独立验证”明确区分；本轮没有改 UI 或行为 |
| 验收 | 先 RED：真实远端 caller 经授权后触发本地 provider query；再 GREEN：本地查询使用 QWY 身份、未批准 caller 仍拒绝、异常后 identity 恢复；传输与读回分别验收 |

## 复现步骤

1. 在隔离测试设备/获授权窗口安装上述 exact 两 APK。保留包名隔离；记录并可恢复原 mock、权限、LSPosed、业务状态。
2. 本次新 QWY LSPosed 为 disabled、无 scope。启动两 app，在新 QWY 执行 debug `MockProviderAcceptanceActivity` 的 `command=prepare_kyiv`；仅重启新 QWY。
3. 新 Auto 的 HandshakeProbeActivity 先应 REFUSED；在 QWY PairingApprovalActivity 精确批准新 Auto ID 与已测 signer，再次握手为 CONNECTED。
4. Auto provider 管理 UI 单独批准新 QWY。仅在获授权窗口给新 QWY fine/coarse 和唯一 mock app-op。
5. 启动新 Auto `FullLoopProbeActivity`，不传故障模式。采集双方 PID 日志及独立 framework 数据。
6. 本次 apply verif=3、observe coverage=3，均为 NONE；release complete=true，控制链到 EXHAUSTED。
7. 释放后恢复原设置并清理新测试数据。该步骤不是一个可无条件在正在工作手机上复制的脚本。

## 根因与可复核证据

QWY 进程日志（PID 21035 / Binder 线程 21047）：

```text
16:09:19.983 ConfigPrefsSync: sync failed
SecurityException: Permission Denial: reading name.caiyao.fakegps.data.AppInfoProvider
uri content://name.caiyao.fakegps.codexbench.data.AppInfoProvider/settings
from pid=20916, uid=10395 requires the provider be exported, or grantUriPermission()
    ConfigPrefsSync.buildFieldMapJson(ConfigPrefsSync.kt:229)
    ConfigPrefsSync.syncLocal(ConfigPrefsSync.kt:139)
    QwyEnvironmentController.applyEnvironment(QwyEnvironmentController.kt:354)
    EnvironmentControlHandler.apply(...)
    EnvironmentControlService$binder$1.apply(...)
    Binder.execTransact(...)
```

服务入口将 `Binder.getCallingUid()` 传给 handler，但没有切换本地框架操作的 Binder 身份。
私有 provider 正确保持 non-exported；异常中的 Auto UID 与执行该查询的 QWY UID 不同。
ConfigPrefsSync 捕获异常并返回 false，不能将其解释为 provider 整体崩溃。
Android 官方说明，处理 IPC 时调用同进程对象的权限检查可通过临时清理身份使用本进程身份，
再用 token 恢复：[Binder API](https://developer.android.com/reference/android/os/Binder#clearCallingIdentity())。

另一个独立阻塞：16:10:05 QWY 自己的 worker 22012 执行 stop 能生成字段，却仍然发布失败：

```text
field map built: 7 spoof fields, 0 unavailable fields
transport resolved to app-private file .../shared_prefs/spoof_config.xml;
not cross-process reachable
published=false readable=false transportAccepted=true commit=true outcomeDurable=true
state=Failed(message=GPS 已停止，但 Hook 配置发布失败, recovery=null, providerCleanupRequired=false)
```

这是未满足跨进程传输条件，不是本地 provider 身份错误的另一种措辞。
MODE_WORLD_READABLE 最初被拒绝并降级为 PRIVATE；后来 accepted=true 也不能证明真实 backing path 可读。
ConfigPrefsSync 的路径检查正确拒绝 app-private 文件。修复 Binder 边界不能自动解决这一前置。

`system-mock:unavailable` 的具体原因仍待拆分：AndroidSystemMockLocationReader 将 API 异常和
无样本都转换成缺样本，本轮日志不能区分权限/归因错误与空 cache。
因此不声称两个已知阻塞解释了所有缺样本，也不声称模拟位置从未短暂写入系统。

## 修复边界与回归要求（尚未实施）

- 必须保留 kernel-supplied caller UID 用于原有配对、版本、lease 所有权授权；不能先清 identity 再取 caller。
- 在授权后的 QWY 本地工作边界使用本进程身份，并保证异常路径 finally 恢复；需独立安全审查。
  handler 初始化及其他 Binder 方法也应审查，不将一次 apply 的日志外推成所有入口都已验证。
- 不改 AppInfoProvider.exported、不加跨应用 URI grant、不修改冻结 ContractV1 或弱化信任策略。
- 新增真实跨进程 Android 回归测试：批准 caller 的本地查询、拒绝未批准 caller、异常恢复身份；
  host mock 中把 UID 写成常数不能覆盖本缺陷。
- 传输独立验收字段构建、commit、真实 backing path、另一 UID 可读性；只有布尔返回值不够。
- 原始 framework 读回应记录各 provider 的失败原因、mock 标记和单调源时间，并排除 generic self-hook。
  当前 debug RuntimeSelfHookPolicy 允许主进程自 hook，HookUtils 替换 getLastKnownLocation；
  此方法也被独立读回器调用。不能通过打开 self scope 来制造通过结果。
- 复验顺序：先身份修复、保持传输条件不变确认错误消失且仍诚实失败；再单独验证传输与不受 hook 污染的读回。
  production fingerprint allowlist 仍为空，#66 FULL 还需要另行建立真实 oracle attestation。

## 验证与交接

本轮提供的是 RED 实机反例和源码根因，不提供尚不存在的 GREEN 修复证明。
FullLoop 的调试 completion proof 写死成功数，不能当成 Auto 生产可信记账。
恢复结果及旧服务中途重连的限制均在实机记录中；不声称旧业务全程无中断。

- **What：** 身份缺陷、独立传输阻塞、未定根因的缺样本已分开记录。
- **Why：** 阻止 host checks 通过或 debug loop 结束被误报为手机目标完成。
- **Tradeoff：** 本轮停在失败点，保持隐私 provider 和独立验证边界。
- **Open：** 授权边界的最小修复、传输装载、真实读回诊断及 oracle 认证。
- **Next Action：** 用独立回归测试驱动身份修复，再审查及安排新 exact APK 的受控复验；不关闭 #66。

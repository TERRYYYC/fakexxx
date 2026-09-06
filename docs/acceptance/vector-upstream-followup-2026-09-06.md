---
feature_ids: [71, 72, 73, 74]
topics: [issue-71, vector, xsharedpreferences, config-transport, upstream, pr-reconciliation]
doc_kind: evidence
created: 2026-09-06
exact_head: 1a37084c5dd0915e0d64254353f671387f4d4f03
status: upstream-latest-debug-red-candidate-host-green-device-blocked
---

# Issue #71 · Vector 上游反馈与 PR 状态核验

## 结论

1. 已向 Vector 上游发布脱敏的观察型 [Discussion #962](https://github.com/JingMatrix/Vector/discussions/962)，并补充了
   [最新 Debug 的重复实验](https://github.com/JingMatrix/Vector/discussions/962#discussioncomment-18317418)。
2. 在干净、隔离的 Vector Debug 3110（exact `c4a701aa`）环境里，A v8 → B v9 与 B v9 → C v10
   两次同签名原地替换都稳定复现 **reader-side stale APK/sourceDir**：daemon 已完成 cache map swap，新启动的
   writer 也继续向 safe-zone 正常发布，但未重启的 reader 持续解析已删除的上一代 `base.apk`，退回 app-private
   路径并保留 last-known-good；只重启 reader 即恢复。
3. 早期的 **writer-side redirect** 症状没有在 3110 干净实验中重现，不能与当前 reader-side RED 混为同一缺陷。
   已有一个基于 `c4a701aa` 的 XSP 候选补丁及 host-only 回归/构建证据；用于本机 AGP/CMake 输出布局的
   `zygisk` 打包兼容 delta 另行留证，不属于 XSP 上游修复 claim。两者均未提交 Vector PR，候选包也从未安装，
   因此没有 device GREEN。
4. fakexxx [Issue #71](https://github.com/TERRYYYC/fakexxx/issues/71) 保持 open。Binder 身份缺陷已由
   [PR #73](https://github.com/TERRYYYC/fakexxx/pull/73) 合入，但 transport/readback 的剩余关闭条件尚未全部进入 main。
5. [PR #72](https://github.com/TERRYYYC/fakexxx/pull/72) 保持 open/draft：#73 覆盖了生产修复和 Android
   回归文件，但没有完全取代 #72 的历史 RED/变异测试证据、堆栈专用 guard；开放的
   [PR #74](https://github.com/TERRYYYC/fakexxx/pull/74) 仍以 #72 为 base。
6. 本次文档更新阶段没有执行任何实体设备、模拟器或 ADB 操作。候选包从未安装；在设备冻结解除前，补丁后的
   实机/模拟器验证明确记为 `DEVICE_BLOCKED`，不把 host GREEN 包装成设备通过。

## 已核验基线

| 对象 | 精确状态 |
| --- | --- |
| fakexxx `main` | `1a37084c5dd0915e0d64254353f671387f4d4f03` |
| #73 squash | `9abc69bc0a81d341073f99a1abf995794d614a34`，已是 `main` 祖先 |
| #72 HEAD | `4192f411b2cf741990041bdf206ce3101be8582f`，open/draft |
| Vector 默认分支 | `master` |
| Vector `master` / canary-3110 | `c4a701aadbf9b4c7a7a65046fe4b9be322a909da` |
| 3110 官方 Debug ZIP SHA-256 | `afc66043dc1316d11fbb8da77c9cb0d3a21ae9c3984af338b1f4ed5f3a60c028` |
| 3110 干净实验环境 | Android 15 / API 35 Google APIs `arm64-v8a`，wiped/no snapshot，Magisk 30.7 |
| Vector 最新稳定版 | v2.2 build 3080，commit `88f8e1faa8b4e7ce20aefabe9c295cd746ea038e` |
| 观察发生版本 | v2.0 build 3021，commit `76141fed151f49b818144d54f2ebb6ab9a2df11c` |

## 最新 3110 重复 RED：reader-side stale APK/sourceDir

干净实验先证明 reader 能在同一进程内接受 30 秒 → 5 秒的 live 配置更新，然后只替换 module APK：

1. A v8 → B v9 后，Vector 收到 package event，daemon 报告 `Cache Update Complete. Map Swap successful`；
2. B writer 发布 10 秒配置，`published=true`、`readable=true`，新值存在于 Vector safe-zone，module app-private XML 不存在；
3. 未重启 reader 仍以同一进程运行，却反复打开 A 已删除的 `sourceDir/base.apk`，得到 `NoSuchFileException`；
4. `XSharedPreferences` 随后解析到 app-private 路径，reader 保留替换前的 5 秒 last-known-good；
5. 只重启 reader，不重启 daemon、writer、设备，也不切换 scope，reader 立即从 B 当前 APK 路径读取 safe-zone 的 10 秒值；
6. B v9 → C v10 重复得到同样结果，reader-only restart 再次恢复。

因此当前可复现边界是“运行中的 legacy reader 保留已删除的上一代 module APK 路径”，不是 daemon 未刷新，也不是
writer 未发布。早期 writer-side redirect 症状在这轮 3110 实验中没有重现。

公开证据只使用脱敏支持包 `Vector-logs-debug-20260906-164817-public-sanitized.zip`，SHA-256
`875328f45c1a44e63cce707fd9f5f97457c2e0e5467054b273e6fe3be94521ba`；ZIP 完整性检查通过，包内 manifest
逐项记录保留字段、替换项与排除项。原始 manager 导出不入仓、不上传，也不在本文摘录。

## 早期 writer-side 观察边界（历史背景）

一次 Android 15 实体设备观察中，legacy module 在同签名原地重装后：

- `MODE_WORLD_READABLE` 提交返回成功且没有抛 `SecurityException`；
- 改动实际落到 app-private `shared_prefs`，Vector mirror 未更新；
- 另一 UID 的读取方继续看到旧值或缺值。

随后同时执行了三项操作——升级至 canary-3110、重启 Vector daemon、在 manager 中重新启用模块——mirror
恢复更新。因为三个变量没有拆开，不能把恢复归因于版本升级，也不能称为已确认的 v2.0 回归。

原始 Discussion 没有包含设备序列号、坐标、项目或私有包名、签名材料、本机路径、数据库标识、凭证或原始日志。
它明确说明这是一次旧版本观察；后续回帖另行给出最新 Debug 的 reader-side 重复实验，不借后者倒推前者根因。

## 源码调查

Vector v2.0 和 canary-3110 都在同一条件分支里连续安装 `ContextImpl.checkMode` 与
`ContextImpl.getPreferencesDir` 两个 hook：

- [v2.0 `LoadedApkCreateCLHooker.java`](https://github.com/JingMatrix/Vector/blob/76141fed151f49b818144d54f2ebb6ab9a2df11c/core/src/main/java/org/lsposed/lspd/hooker/LoadedApkCreateCLHooker.java#L170-L203)
- [canary-3110 `LegacyDelegateImpl.java`](https://github.com/JingMatrix/Vector/blob/c4a701aadbf9b4c7a7a65046fe4b9be322a909da/legacy/src/main/java/org/matrix/vector/legacy/LegacyDelegateImpl.java#L113-L144)
- [当前 legacy 架构说明](https://github.com/JingMatrix/Vector/blob/c4a701aadbf9b4c7a7a65046fe4b9be322a909da/legacy/README.md#interception-and-redirection)

没有找到只启用其中一个 hook 的配置路径，但源码允许“部分安装”在结构上发生：底层 hook 失败时记录
`Failed to hook ...` 并返回 `null`，调用方没有检查返回值。另一个与症状吻合但尚未证实的候选是 ART
内联/反优化：两版都把 `ContextImpl#getSharedPreferencesPath(String)` 列入反优化目标，因为它可能内联
`getPreferencesDir`；反优化结果同样没有被调用方验证。

这两个候选都缺少故障时日志。Android 还会缓存 preference 名称到文件路径以及 SharedPreferences 实例；若没有每轮使用
全新 preference 名称，“未抛异常”本身也不能独立证明 `checkMode` hook 已生效。

历史上最直接的路径修复是
[`Fix wrong NewXSharedPreference path because of inline`](https://github.com/JingMatrix/Vector/commit/159a3adcf8e0832a3e45dccedbb54767dd9f7c4d)，
它已经是 v2.0 的祖先，不是 v2.0 到 3110 之间的恢复解释。相关但不等价的后续修复包括：

- [PR #678](https://github.com/JingMatrix/Vector/pull/678)：`miscPath` 初始化顺序；
- [Issue #816](https://github.com/JingMatrix/Vector/issues/816) / [PR #819](https://github.com/JingMatrix/Vector/pull/819)：
  manager 重写后 legacy module 丢失 self-scope，表现为 own-process hooks 整体缺失；
- [PR #902](https://github.com/JingMatrix/Vector/pull/902)：modern module service 的更新/UID 生命周期 stale delivery；
- [PR #149](https://github.com/JingMatrix/Vector/pull/149)：XSharedPreferences mirror 的 SELinux 访问。

以上候选都不能证明早期 writer-side hook 分裂的根因，因此没有据此为旧症状编写补丁。

3110 的 reader-side RED 则有一条不同、可由源码闭合的失效链：

- `XposedInit.loadedModules` 只在目标进程启动加载 legacy module 时记录当代 APK 路径；
- daemon 的 package cache map swap 不会刷新已经运行的 legacy 目标进程里的这份静态路径；
- 每次构造 `XSharedPreferences` 都重新打开该 APK 判定 safe-zone capability；
- 原地替换删除上一代安装目录后，解析失败并把 module 判成 legacy app-private 路径。

这条调用链与两轮“旧 `base.apk` → app-private fallback → reader-only restart 恢复”的日志一致。是否把 reader restart
定义为 legacy module 更新契约，仍由上游裁定；候选补丁选择保留“本进程已加载 generation 的 capability”，而不是热加载新 module 代码。

## 候选补丁与 host-only 证据

候选基于 Vector exact `c4a701aadbf9b4c7a7a65046fe4b9be322a909da`，尚未提交或发布到上游。它在 legacy module
APK 仍存在的加载时刻计算并缓存 `xposedminversion > 92 || xposedsharedprefs`，后续 `XSharedPreferences` 构造不再为同一
已加载 generation 重开可能已删除的 APK；module 加载失败或登记清理时同步清除 capability。AXML 的 `IOException` 与
unchecked parser failure 都 fail-closed，旧 module 的 app-private 规则保持不变。

回归测试不是只测 helper：它先登记当代 legacy module 和 safe-zone service，删除旧临时 APK，再调用真实
`XSharedPreferences` 构造器，并断言仍解析到 safe-zone。同时覆盖 malformed AXML、metadata 兼容、旧 module 路径和失败清理。

| 工件 | SHA-256 / 结果 |
| --- | --- |
| `Vector-c4a701aa-xsp-candidate-sources.tar.gz` | `4de8ce1cc07ccf4f46265c08a504151e6f829c54abed272ada2c40e2f40b7904`；6 个变更文件 |
| `Vector-candidate-XSharedPreferencesModuleStateTest.xml` | `778eeb0c145f17a604fc88082ccf5a62c292ee4a086ea57f86c603480c02b431`；5 tests / 0 failures / 0 errors |
| `Vector-candidate-lint-results-debug.txt` | `7736c93405dc81f21e593ea78bc94a18eef6a2c5a65982ca19e4595fd611e4ab`；0 errors / 13 个既有 warnings |
| `Vector-v2.2-3110-Debug-candidate-xsp-fix.zip` | `a2ac1400952713762dd59dfb649479d798071fea32da0d237b618b2940bc9d88`；**旧包保留但拒用**：CRC 可读，安装所需 16 个 `bin/` payload/sidecar 全缺失 |
| `Vector-c4a701aa-zygisk-cxx-packaging-compat.patch` | `c2826f3fc42741f24c6b68f3d4119aef5e1c9dc3efe6554f68a7ec64358ec7ce`；仅修本机构建产物从 `intermediates/cxx/Debug/<config>/obj` 映射到 `bin/<abi>/`，并在打包前 fail-closed 检查单一 config 与 4 ABI × 2 payload；不混入 XSP 根因/补丁 claim |
| `Vector-v2.2-3110-Debug-candidate-xsp-fix-bin-complete.zip` | `aa3435ab5728f63f2ce789ac5387265deeb53a762e0a76158cf0211b0f4b40b3`；structurally complete：`unzip -t` 通过，required missing `0/16`，与官方 Debug 均为 69 members 且集合差异 0，27 个内嵌 SHA-256 全部匹配 |

已留下成功终态的 host-only 命令范围为 `:legacy:testDebugUnitTest`、`:legacy:test`、`:legacy:assembleDebug`、
`:legacy:lintDebug`、`:zygisk:zipDebug` 与 `git diff --check`。`zipDebug` 复现需要完整 history/tags（不能用缺少版本历史的
shallow clone）和 Android SDK CMake 3.31.6。新 ZIP 的 `:zygisk:zipDebug` 共执行 237 tasks；以上只证明 XSP 源码回归、
host 构建和 ZIP 结构完整性。候选 ZIP 从未安装到任何设备，不能据此声称 XSP 功能在运行时已修复。

## 重复项与渠道核验

重复检索覆盖 409 个 issue、350 个 PR、186 个 Discussion，并包含英文、中文、符号名和相关概念扩展。
没有找到描述“模块原地重装后，提交成功但仍写 app-private、mirror 不更新”的 exact duplicate。

Vector 仓库没有 `CONTRIBUTING.md`、`SECURITY.md` 或 PR 模板。其
[Bug 模板](https://github.com/JingMatrix/Vector/blob/c4a701aadbf9b4c7a7a65046fe4b9be322a909da/.github/ISSUE_TEMPLATE/bug_report.yml)
强制要求最新 master debug、四位 build、完整环境和 manager 日志包；问题类内容由
[Issue 配置](https://github.com/JingMatrix/Vector/blob/c4a701aadbf9b4c7a7a65046fe4b9be322a909da/.github/ISSUE_TEMPLATE/config.yml)
路由到 Q&A。最新 Debug 的重复 RED 与脱敏证据因此作为既有 Discussion 的跟进发布；在上游澄清 restart 契约、候选补丁取得
device GREEN 之前，不绕过模板创建 Bug，也不提交代码 PR。

## 同名模块 UI：保持为独立候选，不混入 transport

canary-3110 模块列表只把 `appName`、版本和 description 传给共享 `ModuleRow`；package name 数据存在，
但不在模块列表首屏显示，进入 scope 后才可见。因此不同 package 的模块若具有相同 app label 和相同可见描述，
确实可能难以区分。

仅“相同 `xposeddescription`”不足以复现，因为 app label 同样可见。当前仓库也没有现成 Compose UI 测试基础设施。
在没有独立 RED/回归测试前，本轮不提交 UI PR，也不把它与 XSharedPreferences 观察合并成同一上游报告。

## #72 与 #73 逐项处置

#73 与当前 main 已精确覆盖 #72 的以下内容：

- `EnvironmentControlService.kt` 的 Binder identity 修复；
- Android test manifest；
- private-provider fixture 修复；
- `BinderIdentityInstrumentedTest`；
- different-UID relay service。

#72 仍有未被 #73 完整归档的内容：

- 原始 pre-fix RED、两种 mutation 被杀死、9-test/12-gate 证据与清理回执；
- 仅存在于堆栈基线的 `HarnessBoundaryGuardTest` 静态 guard；
- #74 的活动 base 关系。

因此，“#73 已完整取代 #72”的终结谓词为假，本轮没有关闭 #72，也没有删除其分支。#71 的 Binder 身份部分已经落地，
但 production publication anchor、freshness 与独立 GPS/network 双来源断言仍没有全部进入 main，所以也没有关闭 #71。

## 当前验收状态

| 条件 | 状态 | 证据边界 |
| --- | --- | --- |
| 官方 Vector Debug 3110 上复现当前缺陷 | `RED_REPRODUCED` | A→B、B→C 两轮；reader stale APK，writer/safe-zone/daemon 正常 |
| 候选源码回归与构建 | `HOST_GREEN` | 5-test XML、lint、assemble、`zipDebug`；新包 69 members、required missing 0/16、27 个内嵌 hash 全匹配 |
| 候选 Debug 安装后的同进程 A→B/C 验证 | `DEVICE_BLOCKED` | 候选从未安装；设备冻结期间禁止模拟器和实体设备操作 |
| Moto #66 真机 FULL | `DEVICE_BLOCKED` | 不由 host 证据替代，也不由本报告宣告通过 |

`DEVICE_BLOCKED` 是当前执行边界，不是测试失败，也不是“计划通过”。恢复设备操作后仍须用隔离身份重复同一 reader 热更新边界，
并把 candidate GREEN 与未修复官方 Debug RED 成对记录；在此之前不得称 Vector 修复完成。

## 独立核验

- 公开 Discussion 文本由非作者只读审查，结论 `APPROVE`；审查确认事实边界、因果措辞和脱敏范围无阻塞项。
- 最新 Debug 回帖与候选补丁分别经过非作者审查；候选最终结论为 `APPROVE`，无 P0/P1/P2，但该 verdict 不包含任何设备 GREEN。
- PR #72/#73/#71 状态由独立只读核验逐文件比对；结论是保留 #72 和 #71 open。
- Vector duplicate/source 调查分别独立执行；旧 writer-side 观察仍没有可证明根因的代码 PR。
- 本次文档增量包含新的事实判断，因此没有借用旧文档 SHA-256
  `8dffee0c5d499e780acc6d464a22c5a788a867e5d09fe17821f9b0addabe0a62` 的历史审查；当前完整 diff 已由
  非作者复核并得到 `APPROVE`，无 P0/P1/P2。该 verdict 只覆盖文档事实与 host/ZIP 结构，不覆盖设备运行时。

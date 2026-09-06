---
feature_ids: [71, 72, 73, 74]
topics: [issue-71, vector, xsharedpreferences, config-transport, upstream, pr-reconciliation]
doc_kind: evidence
created: 2026-09-06
exact_head: 1a37084c5dd0915e0d64254353f671387f4d4f03
status: upstream-q-and-a-published-no-code-pr
---

# Issue #71 · Vector 上游反馈与 PR 状态核验

## 结论

1. 已向 Vector 上游发布一份脱敏的观察型 Q&A：[Discussion #962](https://github.com/JingMatrix/Vector/discussions/962)。
2. 没有提交 Vector 代码 PR：现有证据不能在单个 hook 安装失败、ART 反优化失败和 Android
   SharedPreferences 缓存之间裁决根因，也没有最新 debug 构建上的 RED 与回归测试。
3. fakexxx [Issue #71](https://github.com/TERRYYYC/fakexxx/issues/71) 保持 open。Binder 身份缺陷已由
   [PR #73](https://github.com/TERRYYYC/fakexxx/pull/73) 合入，但 transport/readback 的剩余关闭条件尚未全部进入 main。
4. [PR #72](https://github.com/TERRYYYC/fakexxx/pull/72) 保持 open/draft：#73 覆盖了生产修复和 Android
   回归文件，但没有完全取代 #72 的历史 RED/变异测试证据、堆栈专用 guard；开放的
   [PR #74](https://github.com/TERRYYYC/fakexxx/pull/74) 仍以 #72 为 base。
5. 本轮没有执行任何实体设备、模拟器或 ADB 操作。

## 已核验基线

| 对象 | 精确状态 |
| --- | --- |
| fakexxx `main` | `1a37084c5dd0915e0d64254353f671387f4d4f03` |
| #73 squash | `9abc69bc0a81d341073f99a1abf995794d614a34`，已是 `main` 祖先 |
| #72 HEAD | `4192f411b2cf741990041bdf206ce3101be8582f`，open/draft |
| Vector 默认分支 | `master` |
| Vector `master` / canary-3110 | `c4a701aadbf9b4c7a7a65046fe4b9be322a909da` |
| Vector 最新稳定版 | v2.2 build 3080，commit `88f8e1faa8b4e7ce20aefabe9c295cd746ea038e` |
| 观察发生版本 | v2.0 build 3021，commit `76141fed151f49b818144d54f2ebb6ab9a2df11c` |

## 对上游公开的观察边界

一次 Android 15 实体设备观察中，legacy module 在同签名原地重装后：

- `MODE_WORLD_READABLE` 提交返回成功且没有抛 `SecurityException`；
- 改动实际落到 app-private `shared_prefs`，Vector mirror 未更新；
- 另一 UID 的读取方继续看到旧值或缺值。

随后同时执行了三项操作——升级至 canary-3110、重启 Vector daemon、在 manager 中重新启用模块——mirror
恢复更新。因为三个变量没有拆开，不能把恢复归因于版本升级，也不能称为已确认的 v2.0 回归。

公开 Discussion 没有包含设备序列号、坐标、项目或私有包名、签名材料、本机路径、数据库标识、凭证或原始日志。
文本明确说明这是一次旧版本观察，当前不满足 Vector Bug 模板要求的“最新 master debug + manager 日志包”。

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

以上均不能证明本次 hook 分裂的根因，所以没有据此编写补丁。

## 重复项与渠道核验

重复检索覆盖 409 个 issue、350 个 PR、186 个 Discussion，并包含英文、中文、符号名和相关概念扩展。
没有找到描述“模块原地重装后，提交成功但仍写 app-private、mirror 不更新”的 exact duplicate。

Vector 仓库没有 `CONTRIBUTING.md`、`SECURITY.md` 或 PR 模板。其
[Bug 模板](https://github.com/JingMatrix/Vector/blob/c4a701aadbf9b4c7a7a65046fe4b9be322a909da/.github/ISSUE_TEMPLATE/bug_report.yml)
强制要求最新 master debug、四位 build、完整环境和 manager 日志包；问题类内容由
[Issue 配置](https://github.com/JingMatrix/Vector/blob/c4a701aadbf9b4c7a7a65046fe4b9be322a909da/.github/ISSUE_TEMPLATE/config.yml)
路由到 Q&A。因此当前选择 Discussion，而不是绕过模板创建 Bug。

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

## 后续设备实验（需另行排期）

若要把 Discussion 升级为可修复 Bug，至少需要在独立测试窗口用最新 Vector debug：

1. 每轮使用全新的 preference 名称，避开路径/实例缓存；
2. 同时记录公开 `getSharedPreferencesPath(unique)` 与反射直接调用 `getPreferencesDir()` 的结果；
3. 捕获 `Failed to hook android.app.ContextImpl.getPreferencesDir`；
4. 记录重装前后 module APK `sourceDir`；
5. 分别只改变进程重启、daemon 重启、重新启用、框架升级中的一个变量。

本轮未安排该实验，也未把模拟器结果等同于实体设备/Vector 验收。

## 独立核验

- 公开 Discussion 文本由非作者只读审查，结论 `APPROVE`；审查确认事实边界、因果措辞和脱敏范围无阻塞项。
- PR #72/#73/#71 状态由独立只读核验逐文件比对；结论是保留 #72 和 #71 open。
- Vector duplicate/source 调查分别独立执行；两者均认定没有可证明根因的代码 PR。

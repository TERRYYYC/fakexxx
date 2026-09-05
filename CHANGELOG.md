---
feature_ids: [F-13, F-18]
topics: [release, changelog]
doc_kind: changelog
created: 2026-09-05
---

# 版本变更记录

本项目遵循 [Semantic Versioning](https://semver.org/)。只有走完发布规范、创建不可变
tag 和发布记录后，一个版本才算正式发布。

## Unreleased

- 暂无已登记的用户可见变更。

## 0.1.0 — Release Candidate

### 新增

- 千万游与 CellRebel Auto 通过版本化 Binder 契约完成发现、配对、能力协商、
  档案选择和 Provider 侧执行。
- 支持 Android System Mock 和已 root 的 Xposed/LSPosed Hook 两种模式。
- 增加配对确认与撤销确认、Mock AppOp 提示，以及可安全重跑的操作流程。
- 增加可信计数、执行耗尽后停止、跳过 legacy GPS、`SERVICE_RECYCLED` 可见终态等
  自动化行为与状态反馈。
- 增加可重复的 Release 构建检查、APK 身份校验和真机验收矩阵。

### 修复

- 强化 Binder 信任边界的包身份校验。
- 对不兼容的旧 Room v5 数据执行隔离后删除并重建，避免升级崩溃；这是明确的
  数据丢失路径，不应描述成无损迁移。
- 固定受控构建机的签名身份，减少覆盖安装时的
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 假绿。

### 安全与发布状态

- Release 变体排除 debug-only collector 行为。
- 两只 v0.1.x Release 当前使用公开 bench key，只适用于受控私下安装，不能证明
  发布者身份，也不能用于商店或公开分发。
- 候选树 `176a493e04b0c5d33a8be8ba03743e3386482edb` 有本地 A+ 14/14
  证据；其发布改动已合入 main，main 的 CI run `33987038267` 为 8/8 通过。
- 独立代码审阅已通过且无 P1/P2；最终 APK 的真机安装和验收仍未完成。
- 公共 APK 发布在迁移到受控 Release key 并批准升级方案前保持阻塞。

详见 [v0.1.0 版本说明](docs/releases/v0.1.0.md)。

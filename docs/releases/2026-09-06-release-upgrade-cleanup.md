---
feature_ids: [F-10, F-13, F-18]
topics: [release, upgrade, application-id, legacy-db, acceptance]
doc_kind: release_readiness_ledger
created: 2026-09-06
status: in-progress
---

# 发布与升级清理台账（2026-09-06）

这是一份工作台账，不是发布声明。基线为 `main` 的
`1a37084c5dd0915e0d64254353f671387f4d4f03`；任何候选 APK 都必须从最终被审阅的
exact HEAD 重新构建并重新取证。

## 升级不变量与唯一入口

| 数据/状态 | 所有者 | 唯一写/迁移入口 | 不变量 | 崩溃或重放处理 |
| --- | --- | --- | --- | --- |
| QWY `fakegps.db` 的旧 v0 profile | `AppDatabase`（#46） | `AppDatabase.ensureLegacyDatabaseRecovered(context)`，Room 调用由 `getInstance` 自动触发 | 只识别 `user_version=0`、有 `temp`、无 `room_master_table`、且 column 集与已存证 legacy schema 精确一致的库；逐列复制、行数相等、`integrity_check=ok` | 先写 Room v2 staging；仅在其校验后保留旧库为 `fakegps.db.legacy-v0-backup` 并提升 staging。下次启动从 staging/backup 恢复；未知或不完整形态 fail-closed，绝不清库 |
| QWY 直接读取 `fakegps.db` | QWY integration/v1（可信任务） | 需在每个 direct-open 前调用上面的 API | 任何直接读取不得绕开 v0 恢复 | owner 在 direct-open 的 RED test 与最小接线中验证 |
| Auto plan/task/attempt/result/session + config | applicationId cutover（#13） | 尚未实现；冻结设计要求 `legacyId` export → operator-controlled SAF carrier → `productId` import | 逐表 count + digest 相等；CSV 不能替代完整迁移 | bundle 必须一次性导入且失败时旧 App/data 保持可用；设备回滚演练未通过前不得移除旧 App |

## Issue → owner → PR/SHA → 剩余验收

| Issue | 当前责任 | 现有 PR / SHA | 本轮状态 | 仍需的验收 |
| --- | --- | --- | --- | --- |
| #13 applicationId cutover | 本发布任务（设计实现），主任务统筹共享 Gradle/Release | #14（设计，open） | 未实施，**阻断 applicationId mutation、旧 Auto 移除和 release candidate** | `legacyId`/`productId` source set 与 SAF UI；五表+配置 bundle round-trip；variant CI；`M-AC-03` 真机 old→new→rollback |
| #46 QWY legacy v0 DB | 本发布任务 | `4f624a422251862ca0265c78aaad43acc6d3c953` | 代码和真实形态 instrumentation GREEN 已有；尚未合入/审查 | integration/v1 two direct-open calls；非作者审查；最终 HEAD emulator regression；production legacy-state仍属未知，发布材料必须如实标注 |
| #66 authoritative continuity oracle | 可信任务 | #68 / #98（均 open） | 不在本任务改动面 | successor integration 的 exact-HEAD review、主任务合并次序与 G2 验收 |
| #71 Binder / Vector transport | Vector 任务 | #72、#99（open） | 框架/运维边界 | 设备+Vector 版本证据；不得用文档替代 transport 验收 |
| #79 / #83 QWY discovery & audit scale | 可信任务 | 无独立 PR 已核实 | 未完成 | profileRefs readback、存储/TTL 语义及回归 |
| #80 / #85 / #86 / #89 / #97 Auto atomicity & recovery | Auto 任务 | #101（open，覆盖部分 recovery audit） | 未完成 | 各状态迁移的原子性、负载体、重放/并发与独立审查 |
| #90 evidence surface | 可信任务 + Vector 任务 | #92 / #100（open） | 未完成 | exact-package evidence、Vector-aware resolver 与真实运行验证 |

## 版本、身份与回滚边界

- 当前可安装的 production IDs 仍为 QWY `name.caiyao.fakegps`、Auto
  `com.example.cellrebelauto`。Auto 的最终 ID `come.xx.fakeaauto` 是已决定的目标，**不是已发布事实**。
- `versionName` 必须等于最终 tag 去掉 `v` 后的值；每个 package 的 `versionCode` 必须独立递增。
- 现有 `bench.keystore` 的公开证书只能支持受控私下覆盖安装，不能证明发布身份，也不能用于公开 GitHub Release。
- 签名不兼容、升级探测不确定、DB schema 不健康或 profile 校验失败时停止。不得用 uninstall/clear-data 伪造升级成功。
- #46 仅恢复已识别的 legacy v0 QWY 形态；它不证明 production 设备上该形态是否存在。#13 的跨
  applicationId 迁移也尚未实现，不能用 QWY database recovery 代替 Auto cutover evidence。

## 三份历史记录的归档建议

调度目录曾报告以下未跟踪记录：

- `docs/acceptance/moto-release-install-2026-09-05.md`
- `docs/releases/2026-09-05-v0.1.0-preflight.md`
- `moto-install-2026-09-05-v0.1.0.md`

它们没有出现在本真仓的 `main` 工作树，因而不能以本次代码分支伪造来源或补写为已验证证据。
保留原文件；由主任务核对原始产生者、exact APK SHA、设备与执行输出后，再决定是否以带
frontmatter 的 acceptance/release 记录归档。根目录副本若与其中一份逐字相同，应只在确认
digest 后转为该归档文件的引用，不能直接删除历史。

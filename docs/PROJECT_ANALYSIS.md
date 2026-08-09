---
topics: [analysis, changelog, roadmap, release]
doc_kind: analysis
created: 2026-04-04
---

# CellRebelAuto 项目总结与规范差距分析

## 文档定位

这不是正式的 `CHANGELOG.md` 或 `docs/ROADMAP.md`，而是基于当前仓库事实做出的审计性分析。
目标是回答两件事：

1. 这个项目现在到底是什么、做到哪一步了。
2. 它距离“按三份规范合规产出正式 CHANGELOG + ROADMAP”还缺什么。

## 信息来源与证据边界

本分析只使用仓库内可见材料与用户提供的规范文件：

- `HANDOFF.md`
- `CLAUDE.md`
- `BACKLOG.md`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/cellrebelauto/ui/*`
- `app/src/main/java/com/example/cellrebelauto/automation/*`
- `app/src/main/java/com/example/cellrebelauto/db/*`
- `app/src/main/java/com/example/cellrebelauto/repository/*`
- `app/src/main/java/com/example/cellrebelauto/util/*`
- `git log`
- tag `v0.1.0-mvp` 的注释信息

以下内容一律不在本分析中伪造：

- Google Play / GitHub Release / F-Droid 的真实发布页面
- 用户下载量、崩溃率、真实反馈
- 项目负责人明确确认过的下一阶段承诺
- 开发前正式存在的 MVP 需求文档

## 一句话定位

`CellRebelAuto` 是一个面向 Android 设备实测场景的本地自动化工具，用无障碍服务协调 Fake GPS 与 CellRebel，循环执行网络质量测试并保存结果。

## 面向谁

从代码和交接文档推断，它更像是给“需要在真实 Android 设备上、按多组伪造地理位置重复执行 CellRebel 测试的测试人员或开发者”使用的内部工具，而不是泛用户应用。

这是一条“基于代码与 handoff 的推断”，不是仓库里写明的产品定义。

## 当前状态判断

- **项目形态**：可构建的 Android App，不是单纯脚本或 PoC
- **代码成熟度**：MVP / 设备特定原型之间
- **版本信号**：存在 tag `v0.1.0-mvp`，日期为 `2026-04-04`
- **发布信号**：确认存在 GitHub 仓库 `https://github.com/TERRYYYC/Faketest.git`
- **未确认项**：无法仅凭本地仓库证明其已在 Google Play、GitHub Releases 或其他公开渠道正式发布

结论：可以说“有一个已打标签的 MVP 里程碑”，但不能仅凭仓库事实说“这是一个已公开发布的 Android 应用”。

## 核心能力总结

以下能力可以直接从现有代码中证实：

### 1. 自动化控制面板

- 主界面可启动 / 停止自动化流程
- 可查看当前状态、循环计数、日志
- 可跳转配置页、历史页和系统无障碍设置

证据来源：
- `app/src/main/java/com/example/cellrebelauto/ui/MainActivity.kt`
- `app/src/main/java/com/example/cellrebelauto/ui/ControlScreen.kt`
- `app/src/main/java/com/example/cellrebelauto/ui/MainViewModel.kt`

### 2. 可配置的 GPS 区域与时序参数

- 支持输入经纬度矩形范围
- 支持“使用当前位置”快速填充
- 支持配置采集延迟、周期间隔、最大循环次数

证据来源：
- `app/src/main/java/com/example/cellrebelauto/ui/ConfigScreen.kt`
- `app/src/main/java/com/example/cellrebelauto/model/AutoConfig.kt`

### 3. 循环式自动测试编排

- 每轮生成随机 GPS 点
- 打开 Fake GPS 并尝试设置位置
- 等待 GPS 稳定
- 启动 CellRebel、运行测试、采集分数
- 保存结果并进入下一轮

证据来源：
- `app/src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt`
- `app/src/main/java/com/example/cellrebelauto/automation/FakeGpsHandler.kt`
- `app/src/main/java/com/example/cellrebelauto/automation/CellRebelHandler.kt`

### 4. 本地结果持久化与历史查看

- 使用 Room 保存 `RunSession` 与 `TestResult`
- 历史页展示每轮时间、分数、位置与状态
- 支持 CSV 导出

证据来源：
- `app/src/main/java/com/example/cellrebelauto/db/AppDatabase.kt`
- `app/src/main/java/com/example/cellrebelauto/model/RunSession.kt`
- `app/src/main/java/com/example/cellrebelauto/model/TestResult.kt`
- `app/src/main/java/com/example/cellrebelauto/repository/TestRepository.kt`
- `app/src/main/java/com/example/cellrebelauto/ui/HistoryScreen.kt`
- `app/src/main/java/com/example/cellrebelauto/util/CsvExporter.kt`

### 5. 调试辅助能力

- 支持日志导出
- 支持当前前台 App 的无障碍树导出

证据来源：
- `app/src/main/java/com/example/cellrebelauto/ui/ControlScreen.kt`
- `app/src/main/java/com/example/cellrebelauto/ui/MainViewModel.kt`
- `app/src/main/java/com/example/cellrebelauto/util/DebugExporter.kt`

## 架构轮廓

项目整体是一个“本地 UI + 前台无障碍服务 + 协程编排 + Room 持久化”的 Android 工具应用。

核心结构可以概括为：

- **UI 层**：Compose 三页结构，负责配置、启动停止、历史查看、调试入口
- **服务层**：`AutomationService` 暴露状态流与启动停止入口
- **自动化执行层**：`AutomationEngine` 负责串行编排每轮流程
- **外部 App 适配层**：`FakeGpsHandler` 与 `CellRebelHandler` 封装各自 UI 交互
- **底层桥接层**：`AccessibilityBridge` + `NodeFinder` 负责节点查找、点击、文本输入、Recent Apps 操作
- **存储层**：Room 保存运行会话与测试结果，工具类提供 CSV / 调试导出

## 可证实事实 vs 推断

| 项目 | 结论 | 类型 | 说明 |
|------|------|------|------|
| 项目名称为 CellRebelAuto | 是 | 可证实事实 | `settings.gradle.kts`、Manifest、字符串资源 |
| 技术栈为 Kotlin + Compose + Room + AccessibilityService | 是 | 可证实事实 | Gradle、Manifest、源码 |
| 项目目标是协调 Fake GPS 与 CellRebel 执行循环测试 | 是 | 可证实事实 | Manifest、源码、handoff |
| 当前存在一个 MVP tag | 是 | 可证实事实 | `v0.1.0-mvp` |
| 项目已在公开渠道发布 | 否 | 无法验证 | 只有 GitHub 仓库地址，没有本地发布证据 |
| 项目面向内部测试人员而非大众用户 | 大概率是 | 推断 | 由设备依赖、调试入口、文档语气推断 |
| 当前完整流程在目标设备上“稳定可复现” | 否 | 无法下结论 | handoff 仍指出 MIUI 切换为核心问题 |

## 按三份规范逐项分析

### 一. `AGENT_HANDOFF.md` 适配度

| 要求 | 状态 | 结论 |
|------|------|------|
| 收集 git 历史 | 已满足 | 本地只有 3 条提交，材料有限但可用 |
| 收集 README / CLAUDE / 设计文档 | 部分满足 | 有 `CLAUDE.md`，无 README 与正式设计文档 |
| 收集发布说明 | 缺失 | 本地无 Google Play / GitHub Release 说明 |
| 收集 issue / task tracking | 部分满足 | 有 `BACKLOG.md`，但几乎为空 |
| 先写 CHANGELOG 再写 ROADMAP | 可执行 | 但只能写“草案”而非正式版 |
| 做 changelog-roadmap 交叉校验 | 可执行 | 前提是承认输入缺失并标记不确定项 |

`AGENT_HANDOFF.md` 的核心假设是“已经有足够的发布与规划素材可追溯”。本项目目前最弱的不是代码，而是发布材料与需求材料太薄。

### 二. `TEMPLATE_A_CHANGELOG_SPEC.md` 适配度

| 要求 | 状态 | 说明 |
|------|------|------|
| 根目录 `CHANGELOG.md` | 尚未满足 | 当前仓库没有该文件 |
| 顶部必须有 `[Unreleased]` | 草案可满足 | 当前 HEAD 与 tag 重合，Unreleased 只能写“无可验证未发布变更” |
| 基于 Keep a Changelog + SemVer | 部分满足 | tag 是 `v0.1.0-mvp`，但 Android `versionName` 是 `1.0`，版本语义不一致 |
| 每个版本块都要有 `Known Issues` | 草案可满足 | 可以根据 `HANDOFF.md` 填当前已知问题 |
| 不写实现细节 | 可满足 | 需要重新改写 commit/tag 内容为用户语言 |
| 每条都必须是真实已交付变更 | 部分满足 | `v0.1.0-mvp` 可写；更细版本历史不足 |
| 链接引用放文件末尾 | 形式上可满足 | 但真实 release/tag URL 是否可公开访问，仓库内无法验证 |

结论：`CHANGELOG` 可以做出一版“低风险草案”，但距离“正式合规成稿”还差发布元数据统一与更完整版本说明。

### 三. `TEMPLATE_B_ROADMAP_SPEC.md` 适配度

| 要求 | 状态 | 说明 |
|------|------|------|
| `docs/ROADMAP.md` | 尚未满足 | 当前仓库没有该文件 |
| Project Identity | 可基本填写 | 大部分可从代码和 handoff 反推 |
| MVP Scope Audit | 部分满足 | 没有开发前正式 MVP 文档，只能做“低置信重建” |
| 至少 3 条 ADR | 可满足 | 代码和 handoff 足够提炼 3 条以上 |
| Technical Debt 含 Intentional 类别 | 可满足 | 例如 destructive migration、固定等待 30s |
| Won't Do 数量 ≥ Will Do 数量 | 草案可满足 | 但 owner 真实优先级尚未确认 |
| Success Criteria 可验证 | 草案可满足 | 可以写成技术可验证指标 |
| Metrics Baseline | 部分满足 | 代码里有本地数据采集，但仓库内没有实际样本值 |

结论：`ROADMAP` 可以先做“结构完整、承诺谨慎”的内部草案，但其中 `Originally Planned`、`Next Phase Scope`、`Metrics Current Value` 仍需要 owner 输入来收口。

## 高风险缺口

### 1. 发布证据缺失

这是最大的结构性缺口。

三份规范都默认“项目已经有某种公开发布或至少可被外部读者追踪的版本输出”，但当前本地仓库只能证明：

- 有代码
- 有 tag
- 有 GitHub 仓库 remote

不能证明：

- tag 是否对外可见
- 是否有 GitHub Release 页面
- 是否真的上架 Google Play
- 首次公开日期是否等于 tag 日期

### 2. 版本元数据不一致

`app/build.gradle.kts` 中：

- `versionCode = 1`
- `versionName = "1.0"`

但 git tag 为：

- `v0.1.0-mvp`

这会直接影响 changelog、release note 与实际 APK 版本的对应关系。

### 3. 版本历史过薄

当前可见历史只有 3 条提交：

- `2026-04-02` 初始功能提交
- `2026-04-04` 当前流程跑通
- `2026-04-04` 修复 Fake GPS 双击问题

这足够支撑“一个 MVP 版本块”，但不足以支撑多版本演进叙事。

### 4. 原始 MVP 范围材料不足

`BACKLOG.md` 为空表，`CLAUDE.md` 是治理约束，不是产品 spec。

因此 `ROADMAP` Section 2 的“Originally Planned”只能从代码和 tag 注释重建，置信度低。

### 5. 指标为空

项目已经有本地结果存储能力，但仓库里没有任何实际数据样本或指标摘要。

这意味着 `ROADMAP` 的 Metrics 章节目前只能写：

- 代码支持记录哪些数据
- 但当前仓库快照没有可引用的真实基线值

### 6. 未见测试

仓库中未见：

- `test/` 或 `androidTest/` 目录
- `@Test` 用例
- `testImplementation` 依赖

这不是模板强制项，但它是“正式对外发布可信度”的关键风险。

## 已知技术与产品风险

### Critical

- MIUI 对第三方 App 切换的拦截仍是核心问题；handoff 明确指出这会影响完整流程可靠性
- 当前没有自动化测试来保护关键交互与解析逻辑

### Tolerable

- Fake GPS 搜索框输入坐标后可能无结果
- 配置目前只保存在内存里，重启后不会保留
- 发布元数据未统一，后续整理 changelog 很容易对不齐

### Intentional / Conscious Trade-off

- `AppDatabase` 使用 `fallbackToDestructiveMigration()`，说明当前阶段接受升级时清库
- CellRebel 测试完成检测改为固定等待 30 秒，属于“先求可跑通”的明确取舍

## 现在距离正式文档还缺哪些 owner 输入

至少还缺以下四类关键信息：

1. **真实发布渠道与日期**
   - 是 GitHub tag、GitHub Release、APK 分发，还是 Google Play
   - 首次对外可获取日期是否就是 `2026-04-04`

2. **版本说明与版本映射**
   - `versionName = 1.0` 与 `v0.1.0-mvp` 之间哪个是对外口径
   - 是否曾存在未打 tag 的内部版本

3. **当前已知问题清单**
   - `HANDOFF.md` 中的问题哪些已修、哪些仍在线上版本存在
   - 哪些问题需要写进 changelog 的 `Known Issues`

4. **下一阶段优先级与明确不做项**
   - 哪些是 owner 承诺要做的
   - 哪些是明确延期的
   - 下一阶段成功标准是什么

## 结论

这个仓库已经足够支撑一份扎实的“项目总结”和一版“模板映射草案”，但还不够支撑完全合规、可对外发布的正式 `CHANGELOG.md` 与 `docs/ROADMAP.md`。

更准确地说：

- **代码侧**：已经形成了一个可描述、可归纳的 MVP 形态
- **文档侧**：还缺发布材料、范围材料、指标材料、owner 决策材料

因此最稳妥的做法不是直接伪造正式文档，而是先沉淀本分析，再据此收集缺失输入并收敛成正式版。

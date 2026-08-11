---
feature_ids: [rewrite-compose]
topics: [architecture, kotlin, compose]
doc_kind: plan
created: 2026-04-08
---

# Kotlin + Compose 重构计划

## 目标
将 Java/XML UI 重写为 Kotlin/Jetpack Compose + Material 3，保持 Hook 层不变。

## 技术栈
| 层 | 旧 | 新 |
|----|----|----|
| 语言 | Java | Kotlin（Hook 层保持 Java） |
| UI | XML + Fragment | Jetpack Compose + Material 3 |
| 数据库 | 原始 SQLite + Cursor | Room |
| 架构 | 无 | MVVM (ViewModel + StateFlow) |
| 导航 | 手动 Fragment 事务 | Navigation Compose |
| 主题 | 固定 Indigo | Material You 动态取色 + 暗色模式 |

## 不动的部分
- `hook/*` — MainHook, HookUtils, Snapshot, BootReceiver 等保持 Java
- `data/AppInfoProvider.java` — ContentProvider 保持 Java（Hook 跨进程依赖）
- Xposed API 集成逻辑

## Phase 1: 脚手架
- [ ] build.gradle 加 Compose + Room + KSP + Navigation
- [ ] 创建 Theme (Color/Type/Theme.kt)
- [ ] 创建 Navigation 路由 + NavGraph
- [ ] 创建 ComposeActivity 入口
- [ ] 4 个占位 Screen (Map/Editor/Collection/Settings)

## Phase 2: 数据层
- [ ] Room Entity 匹配 temp 表 schema
- [ ] Room DAO (insert/update/delete/query)
- [ ] FieldSpec.kt 迁移
- [ ] ProfileRepository
- [ ] 确保 ContentProvider 能读 Room 写入的数据

## Phase 3: 核心 UI
- [ ] MapScreen — OSMDroid 通过 AndroidView 嵌入 Compose
- [ ] ProfileEditorScreen — 分类折叠卡片 + 字段搜索
- [ ] BottomSheet 交互（地图上拉编辑）

## Phase 4: 档案管理
- [ ] CollectionScreen — LazyColumn 卡片列表
- [ ] 滑动删除、长按操作
- [ ] 档案导入/导出 (JSON)

## Phase 5: 设置界面
- [ ] 主题选择 (跟随系统/浅色/深色)
- [ ] Hook 刷新间隔 (15s/30s/60s/120s)
- [ ] 数据管理 (导出全部/导入/清空)
- [ ] 关于 (版本号、GitHub 链接)

## Phase 6: Hook 对接
- [ ] ContentProvider 适配 Room 数据库
- [ ] 验证 Hook 正常读取 Snapshot
- [ ] 端到端测试

## Phase 7: 打磨
- [ ] 过渡动画
- [ ] 暗色模式适配
- [ ] 删除旧 Java UI 文件 + 旧 XML 布局
- [ ] 清理无用资源

## 新项目结构
```
app/src/main/java/name/caiyao/fakegps/
  hook/                     # 不动 (Java)
  data/
    db/
      AppDatabase.kt
      ProfileEntity.kt
      ProfileDao.kt
    model/
      FieldSpec.kt
      Profile.kt
    provider/
      AppInfoProvider.java  # 不动
    repository/
      ProfileRepository.kt
  ui/
    theme/
      Color.kt, Type.kt, Theme.kt
    navigation/
      Screen.kt, NavGraph.kt
    screen/
      map/        MapScreen.kt, MapViewModel.kt
      editor/     ProfileEditorScreen.kt, ProfileEditorViewModel.kt
      collection/ CollectionScreen.kt, CollectionViewModel.kt
      settings/   SettingsScreen.kt, SettingsViewModel.kt
    component/
      CategoryCard.kt, FieldRow.kt
    ComposeActivity.kt
  util/
    CoordinateConvert.kt
```

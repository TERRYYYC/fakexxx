---
feature_ids: [F-10]
related_features: []
topics: [qianwangyou, room, migration, upgrade-path, legacy-db, user-data-persistence]
doc_kind: spec
created: 2026-08-24
---

# F-10: 千网游遗留 user_version=0 非 Room 库致升级后首次 DB 访问必崩

> Status: idea（已归档结论，待 operator 裁定是否立项） | Owner: 待 operator 分派

## Scope 判定（本 thread 的主交付）

**升级路径不算 Issue #7 scope。** 判定依据为三处冻结文本，非推断：

1. **Issue #7 冻结验收清单**里唯一的迁移行是「Room v4→v5 migration on a real fixture with
   non-zero legacy progress; `ProviderPairingRecord` created and empty after upgrade」——这是
   **Auto 侧**库（`apps/cellrebel-auto`，`Migration4to5Test` 已存在；`ProviderPairingRecord`
   按 spec §7.1 是 Auto 的 provider allowlist）。清单无任何 qwy `fakegps.db` 升级条目。
2. **冻结 spec 的 INV-24 / AC-14**（`feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md`）：
   INV-24 evidence 列逐字为「Auto v4→v5 真实 fixture migration test」；AC-14「已有用户数据跨版本
   升级零丢失」的验收载体同样是 v4 fixture。§10 矩阵 `M-MG-01..05` 全部是 Auto v4→v5 语义。
3. **Issue #4（qwy provider lane，已关闭）**的 Exclusive files 只有
   `apps/qianwangyou/**/integration/**` + Manifest/Gradle 集成行——qwy DB 层明确不在 qwy lane
   scope 内。

缺陷随 **PR-1 基线导入**（`5c5ba3a`，closes #2）进入仓库：`git log --follow` 显示
`AppDatabase.kt` 自 import 后零改动，早于 #36 base，**是既有缺陷，不是 #36 回归**。

判定「不算」不等于「砍掉」：本文件把问题路由进独立 backlog，**是否 release-blocking 是
operator 的价值裁定**（见 §铁律 5 张力），不由本 thread 自决。

## 现象与证据

- 崩溃（C5 真机，进程 `name.caiyao.fakegps.bench`）：
  `IllegalStateException: Pre-packaged database has an invalid schema: temp(name.caiyao.fakegps.data.db.ProfileEntity)`
- 遗留库实物（`~/Desktop/coding/c5-evidence/f10-legacy-db/`）：
  - `device-fakegps.db` sha256 `7861a449…f31283`：`PRAGMA user_version` = **0**；表 =
    `android_metadata` + `temp`；**无 `room_master_table`** → 非 Room 库实锤。
  - `temp` 共 **87 列**（DDL 已核对），存 **3 行真实 profile 数据**。
  - `crash-6.0.log` sha256 `a8ca0de3…3f5607`（含完整 Expected/Found TableInfo dump）；
    `bench-appdata.tar` sha256 `d0652c31…31f565`。
- 关键堆栈（决定修复形态）：

  ```
  BaseRoomConnectionManager.onCreate(RoomConnectionManager.kt:182)
  RoomConnectionManager$SupportOpenHelperCallback.onCreate(...:148)
  FrameworkSQLiteOpenHelper$OpenHelper.onCreate(...:236)
  SQLiteOpenHelper.getDatabaseLocked(SQLiteOpenHelper.java:410)
  ```

## 根因（已实证，非猜测）

1. **AOSP 路由**：`SQLiteOpenHelper.getDatabaseLocked` 在 `user_version == 0` 时走 **`onCreate`**
   而非 `onUpgrade`（堆栈第 4 行实证）。因此 **任何 `Migration(0, N)` 永远不会被执行**——
   Room 迁移框架对 version 0 结构性不可达。
2. **Room 2.7.1 的 onCreate 路径**把已存在的库文件当作 pre-packaged DB 做 TableInfo 校验；
   期望 = v2 schema（88 列，含 `unavailable_fields`），实得 = 遗留 87 列 → 校验失败抛
   `IllegalStateException`。
3. **「把遗留库当作 v1 再走 `MIGRATION_1_2`」也不成立**（已实验排除）：遗留库与 Room v1
   列集逐名相等、affinity 全兼容（`DOUBLE→REAL`、`VARCHAR(80)→TEXT`），但主键
   `id INTEGER PRIMARY KEY AUTOINCREMENT` 无显式 `NOT NULL`，PRAGMA 报 `notnull=0`，
   而 Room 期望 `notnull=1`——TableInfo 等值比较仍会失败。
4. **现有测试结构上测不到**：`AppDatabaseMigrationTest` 用 `MigrationTestHelper.
   createDatabase(name, 1)`，以 Room schema bundle 建 v1 fixture；构造「非 Room 的 v0 库」
   必须绕过 helper 手工建库，当前套件无此形态。

## 铁律 5 张力（须 operator 可见）

C5 当时按 runbook §6.0.1 退路**卸载重装**绕过——等价于清掉用户全部数据。真实用户同签名升级
装新包会**直接崩在首屏**，且用户手册里没有「卸载重装」这个选项。这与铁律「用户状态默认持久化」
直接冲突。本 thread 不替 operator 裁定它是否独立于 #7 阻断 release，只在 backlog 中显式标注。

## 诚实的未知项（不要读成"没有"）

- 生产包 `name.caiyao.fakegps` 是 release 包、不可 `run-as`（C5 标注，本 thread 未复核）→
  **生产侧是否存在同款遗留库 = 未知**。取证路径候选：backup/adb 备份分析，或干净设备
  装旧版再升级的复现实验。
- bench 与 production 是两个 applicationId、各自独立数据目录；bench 侧已实锤，production
  侧未知。
- runbook §6.0.1 本身不在本仓（`docs/`、`acceptance/` 均未收录），仅按调度线引用。

## 修复可行性（供立项后执行者参考，本 thread 不动代码）

- **选项 A（保数据，推荐方向）**：`AppDatabase.getInstance` 首次构建前检测——库文件存在 &&
  `user_version==0` && 无 `room_master_table` ⇒ 判定 pre-Room 遗留库；只读打开旧库，按列名把
  `temp` 行拷进 Room 新建的 v2 库（先写临时文件、拷贝成功后原子 rename；任何失败保留原文件，
  数据不丢）。可行性已验证：legacy 列集 ⊆ v2 列集（仅缺 `unavailable_fields`，按语义补 NULL
  即 passthrough），affinity 全兼容。
- **选项 B（安全失败兜底）**：检测后把原库改名备份（**不删**），UI 明示「旧版本数据不兼容，
  已备份至 X，可导出或重新开始」。可理解、不静默丢数据；体验劣于 A，可与 #13 的 SAF
  export/restore 通道对齐做导出。
- **反模式（禁止）**：catch 住崩溃然后清库 = 静默丢数据，违反调度线闭环判据 2 与铁律 5。

## Acceptance Criteria（若立项）

- [ ] AC-1：`user_version=0` 遗留 fixture（手工按 legacy DDL 建库 + 3 行数据）首开不崩，
      全部行按列名存活，`unavailable_fields` 为 NULL。
- [ ] AC-2：若走安全失败路径，原库文件保留可导出，UI 文案用户可理解；不存在任何
      「catch 后清库」代码路径。
- [ ] AC-3：0→N 覆盖测试以普通 instrumentation test 形态进 CI（`MigrationTestHelper`
      不适用，不得强用）。
- [ ] AC-4：production 包同款遗留库存在性取证完成，或在 release 材料中如实标注未知。

## Dependencies

独立于 #7 G 阶梯；若立项，导出/备份通道建议与 Issue #13（applicationId cutover 的 SAF
搬运通道）对齐，避免两套用户数据救援机制。

## Risk

- production 侧遗留库分布未知（release 包不可 `run-as`），影响面估计只能给区间。
- bench / production 两个 applicationId 需分别覆盖与回归。

## Open Questions

- 是否 release-blocking（operator 价值裁定，本 thread 已按「不算 #7 scope」路由，未自封优先级）。
- 修复进入哪个 release train；是否与 #13 cutover 同窗交付。

---

**归档签名**：[墨墨/k3🐾] 2026-08-24 · 证据坐标：本仓 `main` HEAD、`~/Desktop/coding/c5-evidence/f10-legacy-db/`（三份 sha256 见上）、Issue #7 / #4、spec v1.6x 冻结文本

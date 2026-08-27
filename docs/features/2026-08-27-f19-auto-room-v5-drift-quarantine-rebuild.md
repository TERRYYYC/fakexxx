---
feature_ids: [F-19]
related_features: [F-10, F-16, F-18]
related_issues: [5, 7]
topics: [room, schema-drift, migration, inv-24, destructive-rebuild, device, auto]
doc_kind: bug-report
created: 2026-08-27
status: merged (PR #51, merge 8145baf) — 设备端 apply 待调度线排程
---

# F-19: Auto 启动闪退 — Room v5 schema 漂移无迁移路径（operator 裁 B：v6 + destructive rebuild）

## Bug 诊断胶囊

| 栏位 | 内容 |
|---|---|
| **1. 现象** | Auto (`com.example.cellrebelauto`) 主界面在 ZY22 上必崩：`IllegalStateException: Room cannot verify the data integrity`，expected `0d083aef0412f6d2ad3bbce31bf37f98` / found `dea7bb1231570ea9fab363e19fc3c9b3`。首次记录于 C5 时代（08-25 `f16-main-launch.log`）；F-16（ViewModel 层）修复真实有效，只是剥出了本层。G2 任何真机块因此空谈。|
| **2. 证据** | ① 冻结栈：`g2-auto-crash-triage-20260827/probe-d3-wait-and-crash.txt`（2026-08-27 12:23，manifest 封存）② found 侧字节实证：probe-c7 `room_master_table = 42\|dea7bb12…`、probe-c8 `user_version=5` ③ 包身份：probe-b3 实装 = `ddf5fbd5…`（S2 验证字节，非错包）④ 当前 main 的 `5.json` identityHash 仍 = 崩溃 expected `0d083aef…` → probe-d3 即当前 main 实录（schema 层自崩溃起未变；`ac94c39` 只动 DAO 冲突策略不动 @Entity）|
| **3. 根因** | 三层叠加：(a) **设备 DB 出自 ~8/01 未提交构建**——`git log -S dea7bb12` 全历史零命中；该构建 `version=5` 但实体是另一条演化分支：仅 5 张旧表，且 `test_attempts` 带 8 个孤儿 GPS 验证列（`actualLatitude/actualLongitude/locationErrorMeters/fixIsMock/fixAt/verifiedAt/fixAccuracyMeters/toleranceMetersUsed`），任何已提交 schema 都没有这些列；11 张 A+ v5 表全部缺失。(b) **spec 冻结 `version=5`**（`Migrations.kt`「never a later bump」，R5-F5 曾把 v6 bump 砍回 v5），而 v5 实体在 11 个 commit 里持续内部演化（`6576950→afecace`）。(c) **Room 只认 identity hash、不支持版本内迁移**：两边都自称 v5 ⇒ 不触发 onUpgrade ⇒ 无迁移路径也无自救路径（`addMigrations(2_3,3_4,4_5)` 无兜底）。|
| **4. 修复裁定** | operator 2026-08-27T09:46Z 裁定**方案 B**：`version=6` + `fallbackToDestructiveMigration`。实现为三件套（见下"设计真值表"）：①开库前 **v5 漂移隔离区**（`user_version==5 && identity_hash != 0d083aef…` → 删库重建）②**`MIGRATION_5_6` 显式 no-op**（健康 v5 与 v2–v4 阶梯保数据直达 v6）③ **`fallbackToDestructiveMigration`**（v1/未知版本兜底）。方案 A（手写 5→6 迁移）被证据否决：设备 schema 是未提交分支，需删 8 孤儿列（SQLite 整表重建）+ 补 11 表，且"改过多轮"意味着变体不可枚举。|
| **5. INV-24 豁免登记** | 方案 B 直接违反 INV-24（AC-14 同源）。**豁免由 operator 明确裁定，范围写死：仅 Auto（`com.example.cellrebelauto`，开发期 app，versionCode=1）的本次 v5 漂移事故**。不外溢千网游生产包（那是 #46 / F-10 的地盘，性质不同：真实 operator 数据 + 非 Room 遗留库）。代码注释**改写留痕不删除**（`AppDatabase.kt` / `Migrations.kt` chronicle）；spec 台账 INV-24 行与 AC-14 行同步登记例外（本 PR 内）；`Migration4to5Test` 的 INV-24 守卫断言保留并升级语义，未放宽。|
| **6. 已知副作用** | 清 Auto 数据影响面（实measured，非推测）：设备 Auto DB 现存 7 run_sessions / 3 plans / 9 tasks / 48 attempts / 0 results——全部为 8/01 时代陈旧 dev 数据，重建后清零。**bench（千网游）侧 fixture 现场不受影响**：probe-c9 证明设备 Auto DB 无 `provider_pairing_records` 表，G1 的配对批准（`APPROVED: com.example.cellrebelauto`）、seed、signer 信任全部存于 bench 侧。漂移库三件套已封存于 triage `raw/`（manifest sha256 校验），考古可恢复。F-18 fixture 重建代价：**零**（signer 连续性由入库 keystore 保证，install -r 不换签名）。|
| **7. 残余风险（诚实披露）** | 隔离区只认「user_version=5 且 hash≠健康值」。若存在 *post-afecace 又一个未提交漂移变体*（假想，无已知设备），其 hash≠健康值 → 同样被隔离重建 ✓。若存在「version>6 降级安装」或「room_master_table 缺失的半损库」→ 隔离区不动，交 Room 原生行为（响亮崩溃/结构校验），不静默删库。删除面故意收窄：探测失败一律不删。|
| **8. 验收** | ① RED：设备复刻 fixture（逐字 DDL + `42\|dea7bb12` + user_version=5）经 `getInstance` 在未修复代码上跑出与设备逐字同款异常（raw log + JUnit XML 封存）② GREEN：同一测试通过——隔离重建、v6、旧数据清零 ③ 回归：v2/v3/v4 真实 fixture 经**生产配置**（含 fallback）全链迁移**数据存活**（fallback 未误触发）④ 健康 v5（`0d083aef`，MigrationTestHelper 从 5.json 构建）→ v6 数据存活 + `runMigrationsAndValidate` 对 6.json 校验通过 ⑤ INV-24 守卫：无迁移无 fallback 的 builder 打开旧库仍必须抛异常而非静默清空 ⑥ 全量单测绿（raw EXIT 0）。设备端 apply 走合入后流程（见下）。|

## 设计真值表（reviewer 请对照逐行验证）

| 开库场景 | 隔离区 | Room 路径 | 结果 |
|---|---|---|---|
| 漂移 v5（ZY22 实况，任意未提交变体） | **命中**（v=5, hash≠0d083aef）→ 删三件套 | 全新建库 @v6 | ✅ 启动，数据清零（裁定内） |
| 健康 v5（0d083aef，afecace..HEAD 期间的本地/模拟器安装） | 不动 | `MIGRATION_5_6`(no-op) + 校验 | ✅ 启动，**数据保留** |
| v2/v3/v4 库 | 不动（version≠5） | 2→3→4→5→6 阶梯 + 校验 | ✅ 启动，**数据保留**（不是无脑重建） |
| v1/未知版本 | 不动 | 无路径 → `fallbackToDestructiveMigration` | ✅ 启动，重建（兜底） |
| 半损库（无 master 表等） | 不动（探测失败不删） | Room 原生结构校验 | 响亮失败，不静默 |
| 全新安装 | 不动（无文件） | 建库 @v6 | ✅ |

关键机制事实（决定实现形状，reviewer 可复验）：Room 的 `fallbackToDestructiveMigration` **只在迁移路径缺失时触发**；路径存在但产物与实体不符 → `Migration didn't properly handle` 直接抛，**无 fallback 救援**。因此裸 `version=6 + fallback`（无 5_6）会把 v2–v4 阶梯也送进重建（路径 4→6 不完整 = 无路径）；而 no-op 5_6 若被漂移库走到会换一种崩法。隔离区 + no-op 5_6 + fallback 三件套是同时满足「裁定 B」与「阶梯不破」的最小机构。

## 设备端 apply（合入后执行，不在本 PR 内）

1. ~~封存漂移库三件套~~ ✅ 已由 triage 完成（`g2-auto-crash-triage-20260827/raw/` + manifest）
2. 合入后基于 main 构建 debug APK（bench keystore 签名，F-18 保证 install -r 连续性）
3. `scripts/install_apk_verified.sh` 实装（失败全文 + 实装 SHA 比对）
4. `am start` Auto 主界面 → logcat 断言无 `IllegalStateException`，UI 存活
5. 复查 `run_sessions` 等为空、`room_master_table` 为新 hash、`user_version=6`（走 raw/query 三件套契约，先封存再查询）
6. G2 真机块解锁判定归调度线/监督猫，本 PR 不触碰 gate 状态

## 证据指针

- Triage（根因 + 封存库）：`/Users/terry/Desktop/coding/g2-auto-crash-triage-20260827/`（TRIAGE-SUMMARY.md + triage-manifest.sha256）
- 本修复 RED/GREEN raw：`/Users/terry/Desktop/coding/f19-room-drift-fix-20260827/`
- 历史首录：`docs/acceptance/g1-smoke-2026-08-25-ZY22-run2.md` §偏离-4
- 漂移史：`git log --follow -- apps/cellrebel-auto/app/schemas/…/5.json`（11 commits，`85ec7c4` 曾砍掉 v6 bump）

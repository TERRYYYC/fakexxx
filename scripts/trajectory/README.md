# 轨迹加工工具（scripts/trajectory/，For #191）

站点 CSV → 轨迹行动计划三件套：把原始站点表的每个站点展开为周边 10-20 个轨迹点
（含原点），产出可直接导入的计划 CSV、QWY 档案 CSV 与位置→ECGI 映射。

纯 Python 3 标准库，无第三方依赖。输入样例：`~/Desktop/信息/0914 test 2.csv`
（512 个 CSV 行 = 1 标题行「表格 1」+ 1 表头 + **510 数据行**；列序
`custom_admin_3,latitude1,longitude1,ECGI`，**纬度列在前**）。

## 用法

```bash
# 预设模板：前 3 站各展开 13 点（box50 方框回路）
python3 scripts/trajectory/make_trajectory_plan.py \
    --input "~/Desktop/信息/0914 test 2.csv" \
    --out-dir /tmp/traj --stations "1-3" --preset box50 --points 13

# 参数化步进序列（E/W/N/S + 米，任意长度，超出按序列循环）
python3 scripts/trajectory/make_trajectory_plan.py \
    --input sites.csv --out-dir /tmp/traj --steps "E50,N50,E50,S50"

# 执行次数随机化：每行 required_successes 在 1-5 均匀随机（每行独立），
# 指定 --seed 可复现（同 seed 同输入 → 三件套逐字节相同）
python3 scripts/trajectory/make_trajectory_plan.py \
    --input "~/Desktop/信息/0914 test 2.csv" \
    --out-dir /tmp/traj --stations "1-10" --preset box50 --points 13 \
    --required-range "1-5" --seed 7

# 多场景组合：显式场景序列（全站统一），如 owner 原话「蛇形+方块+蛇形+1 字形」
python3 scripts/trajectory/make_trajectory_plan.py \
    --input sites.csv --out-dir /tmp/traj --stations "1-10" \
    --combo "snake+box+snake+line" --points 13

# 多场景组合：每站独立随机抽 2-5 个场景组成序列，--seed 可复现（缺省自动生成并落 manifest）
python3 scripts/trajectory/make_trajectory_plan.py \
    --input sites.csv --out-dir /tmp/traj --stations "1-10" \
    --combo-random "2-5" --seed 7 --points 13
```

| 参数 | 说明 |
| --- | --- |
| `--input` | 原始站点 CSV（表头必须为 `custom_admin_3,latitude1,longitude1,ECGI`，前面可有任意标题行） |
| `--out-dir` | 输出目录（不存在则创建；重复运行直接覆盖） |
| `--steps` | 步进序列，如 `"E50,N50,E50,S50"`；与 `--preset` 互斥 |
| `--preset` | `box50`（默认）/ `cross75` / `walk100`，`--steps` 缺省时生效 |
| `--combo` | 显式场景序列（全站统一），如 `"seven+box+snake"`；场景名可带步长后缀（`seven50`/`snake75`，缺省 50m） |
| `--combo-random` | 每站独立从场景库随机抽 `"min-max"` 个场景组成序列（可重复抽，如 snake+box+snake），如 `"2-5"`；配合 `--seed` 可复现 |
| `--scene-points` | 可选，覆盖各场景默认点数，如 `"line=4,seven=5,snake=6,box=5"`（仅 combo 模式生效） |
| `--seed` | 随机种子（仅 `--combo-random` 时生效；缺省自动生成并写入 manifest） |
| `--points` | 每站展开总点数（**含原点**），clamp 到 10-20；默认 12；combo 下=组合展开后截断/循环补足到该数 |
| `--stations` | 站点选择，1-based **原始 CSV 数据行号**，如 `"1-10,33,100"`；缺省=全部 510 站（打印规模警告） |
| `--priority` / `--required-successes` | plan.csv 两列常量，默认 3/3（对齐现有 285 计划风格） |
| `--required-range` | 每行 `required_successes` 在 `[min,max]` 闭区间均匀随机（每行独立），如 `"1-5"`；min≥1、max≥min，与 `--required-successes` 互斥 |
| `--seed` | 随机种子（`--required-range` 时生效）；缺省自动取 OS 随机 seed 并写入 manifest，事后仍可复现 |

轨迹模板**三选一**：`--steps` / `--preset` / `--combo` 或 `--combo-random`（后两者互斥）；
同时给多个直接报错。

坐标数学：纬度 1°≈111320m，经度 1°≈111320·cos(lat)m（用当前点纬度修正）；
E=+lng、W=−lng、N=+lat、S=−lat；输出统一 7 位小数（对齐输入精度）。

## 预设模板

| 预设 | 步进 | 形状 |
| --- | --- | --- |
| `box50` | E50,N50,W50,S50 | 50m 方框回路，4 步闭合回原点，循环展开 |
| `cross75` | E75,W75,N75,S75 | 东 75m 往返、北 75m 往返（两轴十字） |
| `walk100` | E100 | 持续向东 100m 直线步行 |

例：box50 + `--points 13` → 12 步 = 方框循环 3 圈，点 0/4/8/12 回到原点。

## 场景库（多场景组合，编号 1-4）

每形状默认步长 50m（场景名可带步长后缀覆盖，如 `seven50`/`snake75`）；
`--combo` 显式给序列（全站统一），`--combo-random` 每站独立随机抽（可重复抽）。

| 编号 | 场景 | 形状 | 移动段（默认步长 50m） | 默认点数 |
| --- | --- | --- | --- | --- |
| 1 | `line` | 1 字形 | 单方向直线走 3 步（E,E,E） | 4 |
| 2 | `seven` | 7 字形 | 先东 2 步再北 2 步（E,E,N,N） | 5 |
| 3 | `snake` | 蛇字形 | 东 1→北 1→西 2→北 1→东 1（西 2 步为一段 100m，终点在起点正北方） | 6 |
| 4 | `box` | 方块 | 东-北-西-南 回到进入点（E,N,W,S） | 5 |

例：`--combo "snake+box+snake+line"`（owner 原话「蛇形+方块+蛇形+1 字形」）
自然展开 1+5+4+5+3 = 18 点。

**衔接规则**：开放形状（line/seven/snake）从当前笔位置继续——上一场景终点=
下一场景起点，场景边界坐标无缝衔接无跳变；box 闭合回路（终点=进入点）。
轨迹点坐标全精度累加、7 位小数输出。

**总点数**：各场景按默认点数（或 `--scene-points` 覆盖值，场景步进序列循环/
截断到目标点数）展开，组合序列再按 `--points` 收口——超 N 截断、不足循环
整个 combo 补足（与单模板的 `--points` 语义一致，同受 10-20 clamp）。

**manifest 审计**：combo 模式下 params 记录 `trajectory_mode` / `scene_points` /
`combo`（显式 spec 或随机 count_range+seed+rng），每站记录其场景序列
（编号/名称/点数/步长，如 traj-001: `["snake","box","line"]`），事后可复现可追溯。

**`--combo-random` 审计注记**：同 seed 下某站的场景序列取决于 `--stations` 选择集——
场景流是一条共享 RNG 流按站序消费，站序在流中的位置随选择集变化（增删站点会改变
其后各站的抽取结果）。审计口径以 manifest 逐站 scenes 记录为准；重放时使用相同的
`--stations` 集合即可逐字节复现。

## 输出三件套（out-dir 下）

| 文件 | 列 | 去向 |
| --- | --- | --- |
| `plan.csv` | `longitude,latitude,priority,required_successes,ci`（**lng 在前**；ci=该点继承的源行 ECGI，Auto 旧版不识别时删该列即回 4 列） | CellRebel Auto 计划导入（ci 列供 #190 验证层对照） |
| `profiles.csv` | `addname,latitude,longitude,ci`（ci=该点继承的源行 ECGI） | QWY 收藏档案导入；#193 读回门按 ci 做字节级比对 |
| `ecgi_map.csv` | `addname,ecgi,custom_admin_3,source_row` | #189 CI hook / #190 验证层（保留作期望真相源与审计） |
| `manifest.json` | 参数快照 + 输入 sha256 + 行数统计 + 每站摘要（路径长度/包围盒/该站 required_successes 的 min/max/sum） | 审计复现 |

行序 = 站点序 × 轨迹序（step 0 = 原点）。addname 命名 `traj-{station:03d}-{step:02d}`
（如 `traj-001-00`），其中 station = 原始 CSV 数据行号；全小写字母数字连字符，
QWY 档案名保守兼容。轨迹点继承原始行的 ECGI/区县/行号。

## required_successes 随机化（审计与复现）

`--required-range "1-5"` 时每行 `required_successes` 在 `[1,5]` 闭区间独立均匀随机
（RNG：`random.Random(seed).randint` / MT19937，逐行按计划行序抽取）：

- **可复现**：同 seed + 同输入 → 三件套逐字节相同（manifest 里 `generated_at`
  是唯一随运行变化的字段——它记录的是生成时刻，不属于产物）。
- **审计**：manifest 记录 `params.seed` / `params.rng` / `params.required_successes_range`
  及顶层 `required_successes_summary`（`min`/`max`/`sum`/`distribution`——sum 即总执行
  次数，distribution 是值→行数分布）；每站摘要含该站 `required_successes` 的
  min/max/sum。逐行明细在 plan.csv 第 4 列。
- **键位说明**：随机模式启用时 `params.seed`（`--required-range` 次数流）与
  `params.combo.seed`（`--combo-random` 场景流）并行记录，两流独立派生
  （各自独立 RNG 实例顺序消费，互不挤占游标）。
- **缺省行为不变**：不给 `--required-range` 时全行仍为常量 3（`--required-successes`
  可改），manifest 模式记 `constant`，无 seed 字段。

## 与 #193 的衔接（读回门闭环）

`profiles.csv` 自带第 4 列 `ci`（= 该点继承的源行 ECGI），与 #193 QWY 档案导入器
的 header 按名绑定契约（`addname,latitude,longitude,ci`，4 列）直接对齐——**单文件
导入即闭环，无需再把 `ecgi_map.csv` 手工并进档案**。导入后：

- QWY 读回门（DeliveryReadbackGate）会把档案已发布的 ci 与 hook 载荷 ci 做
  **字节级比对**，不一致即进修复阶梯；ci 缺失/null 时该腿静默跳过（旧行为不变）。
- 因此轨迹工作流下每行档案都带正确 ci：一期「ci 跟随伪造」目标在该链路真实生效，
  不会出现「导入后 ci 全 null → 读回门静默跳过」的缺口（#193 评审 F1 已修复）。
- `ecgi_map.csv` 保留原样，供 #189 CI hook / #190 验证层消费与事后审计。

## 与装机流程配合（SKILL §2.5/§2.6）

1. 生成三件套（上文用法），把 `plan.csv` 与 `profiles.csv` 推到手机
   `/sdcard/Download/`（§2.5 第 5 步）。
2. CellRebel Auto 计划页导入 `plan.csv`；QWY 收藏档案页导入 `profiles.csv`。
3. **锚定**：QWY 收藏列表滚到底点 `traj-001-00` 进编辑页 → 右下角「保存」FAB
   （跳过这步发布会提前退出，§2.5 第 6 步实测踩坑）。
4. 每行计划都有同序对应的档案（profiles.csv 行数 = plan.csv 行数），满足
   「每行计划要有对应档案」约束。

## 与 #189 / #190 的衔接

`ecgi_map.csv` 是位置→ECGI 的期望真相源：`addname` 对齐 QWY 档案名（=
QWY hook 载荷里的 addname），`ecgi/custom_admin_3/source_row` 继承原始站点行。

- **#189（CI 按位置 hook）**：mock 切到某日程项位置时，按 addname 查
  `ecgi_map.csv` 得到该轨迹点应有的 ECGI，注入 telephony 读数。
- **#190（CI 验证层）**：探针实测 serving cell 与该位置期望 ECGI 对照
  （复用 #185 模式）；期望 ci 已随 `plan.csv` 第 5 列直接进 Auto 的
  `location_tasks.expectedCi`，Auto 运行台小区卡/地图角标就地对照，
  `ecgi_map.csv` 保留作期望真相源与事后审计。

## 测试

```bash
cd scripts/trajectory && python3 -m unittest        # 或仓库根目录：
python3 -m unittest discover -s scripts/trajectory
```

覆盖：步进数学（50m@lat49.87 → Δlat≈0.000449、Δlng≈0.00069 量级）、原点为首点、
box50 闭合、ECGI 继承、profiles.csv 列数/列名/ci 继承（4 列对齐 #193）、plan.csv
5 列 ci 继承（对齐 #190）、三件套行数一致、plan 列序 lng 在前、addname 唯一、
`--points` clamp、`--stations` 子集选择与越界拒绝、manifest 摘要、`--required-range`
随机区间（值域/有变化/同 seed 逐字节复现/异 seed 不同/缺省常量 3 逐字节兼容旧版/
非法区间与互斥拒绝/seed 落盘）。

多场景组合：四形状各自然点几何（line 直线单调 / seven 两段 / snake 蛇形往返 /
box 闭合回进入点）、场景 token 与序列解析（步长后缀）、场景点数循环/截断展开、
combo 显式序列拼接连续性（边界无跳变、box 中段闭合）、combo-random 同 seed
逐字节复现/异 seed 不同/每站独立、`--scene-points` 覆盖联动、`--points` 截断/
循环补足、三选一互斥校验、旧用法（`--preset`/`--steps`）输出与旧版逐字节相同
（测试内独立 oracle 重算整文件字节比对）。

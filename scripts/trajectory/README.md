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
```

| 参数 | 说明 |
| --- | --- |
| `--input` | 原始站点 CSV（表头必须为 `custom_admin_3,latitude1,longitude1,ECGI`，前面可有任意标题行） |
| `--out-dir` | 输出目录（不存在则创建；重复运行直接覆盖） |
| `--steps` | 步进序列，如 `"E50,N50,E50,S50"`；与 `--preset` 互斥 |
| `--preset` | `box50`（默认）/ `cross75` / `walk100`，`--steps` 缺省时生效 |
| `--points` | 每站展开点数（**含原点**），clamp 到 10-20；默认 12 |
| `--stations` | 站点选择，1-based **原始 CSV 数据行号**，如 `"1-10,33,100"`；缺省=全部 510 站（打印规模警告） |
| `--priority` / `--required-successes` | plan.csv 两列常量，默认 3/3（对齐现有 285 计划风格） |

坐标数学：纬度 1°≈111320m，经度 1°≈111320·cos(lat)m（用当前点纬度修正）；
E=+lng、W=−lng、N=+lat、S=−lat；输出统一 7 位小数（对齐输入精度）。

## 预设模板

| 预设 | 步进 | 形状 |
| --- | --- | --- |
| `box50` | E50,N50,W50,S50 | 50m 方框回路，4 步闭合回原点，循环展开 |
| `cross75` | E75,W75,N75,S75 | 东 75m 往返、北 75m 往返（两轴十字） |
| `walk100` | E100 | 持续向东 100m 直线步行 |

例：box50 + `--points 13` → 12 步 = 方框循环 3 圈，点 0/4/8/12 回到原点。

## 输出三件套（out-dir 下）

| 文件 | 列 | 去向 |
| --- | --- | --- |
| `plan.csv` | `longitude,latitude,priority,required_successes`（**lng 在前**） | CellRebel Auto 计划导入 |
| `profiles.csv` | `addname,latitude,longitude` | QWY 收藏档案导入 |
| `ecgi_map.csv` | `addname,ecgi,custom_admin_3,source_row` | #189 CI hook / #190 验证层 |
| `manifest.json` | 参数快照 + 输入 sha256 + 行数统计 + 每站摘要（路径长度/包围盒） | 审计复现 |

行序 = 站点序 × 轨迹序（step 0 = 原点）。addname 命名 `traj-{station:03d}-{step:02d}`
（如 `traj-001-00`），其中 station = 原始 CSV 数据行号；全小写字母数字连字符，
QWY 档案名保守兼容。轨迹点继承原始行的 ECGI/区县/行号。

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
  （复用 #185 模式）；`source_row` 可回溯原始站点行供 UI 展示区县。

## 测试

```bash
cd scripts/trajectory && python3 -m unittest        # 或仓库根目录：
python3 -m unittest discover -s scripts/trajectory
```

覆盖：步进数学（50m@lat49.87 → Δlat≈0.000449、Δlng≈0.00069 量级）、原点为首点、
box50 闭合、ECGI 继承、三件套行数一致、plan 列序 lng 在前、addname 唯一、
`--points` clamp、`--stations` 子集选择与越界拒绝、manifest 摘要。

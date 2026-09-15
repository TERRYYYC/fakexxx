#!/usr/bin/env python3
"""轨迹加工工具：站点 CSV → 轨迹行动计划三件套（For #191）。

输入：原始站点表（custom_admin_3,latitude1,longitude1,ECGI；前面可有任意标题行，
如 Excel 导出的「表格 1」）。注意输入列序是 latitude1 在前、longitude1 在后。

输出（写入 --out-dir）：
  plan.csv      longitude,latitude,priority,required_successes,ci（列序 lng 在前，
                对齐 Auto WorklistParser 契约；第 5 列 ci=该点继承的源行 ECGI，
                供 #190 验证层"期望 ci vs 实测 serving cell"对照；旧版 Auto 只认
                4 列时可删掉该列，其余不变）
  profiles.csv  addname,latitude,longitude,ci（QWY 收藏档案导入格式；ci=该点继承
                的源行 ECGI，#193 导入器 header 按名绑定、读回门做 ci 字节级比对）
  ecgi_map.csv  addname,ecgi,custom_admin_3,source_row（轨迹点继承原始行信息，
                供 #189 CI hook 与 #190 验证层使用；审计用，与 profiles.csv 并存）
  manifest.json 参数快照 + 行数统计 + 每站轨迹摘要（审计复现用）；required_successes
                走随机区间（--required-range）时还记录 seed/RNG 方式/分布摘要，
                同 seed 同输入可复现出逐字节相同的三件套；combo 模式下
  每站还记录其场景序列（编号/名称/点数/步长），随机组合记录 seed 可复现

仅用 Python 3 标准库。

用法示例：
  python3 make_trajectory_plan.py --input "0914 test 2.csv" --out-dir /tmp/traj \\
      --stations "1-3" --preset box50 --points 13
  python3 make_trajectory_plan.py --input sites.csv --out-dir /tmp/traj \\
      --steps "E50,N50,E50,S50" --points 12
  python3 make_trajectory_plan.py --input "0914 test 2.csv" --out-dir /tmp/traj \\
      --stations "1-10" --preset box50 --points 13 --required-range "1-5" --seed 7
  python3 make_trajectory_plan.py --input sites.csv --out-dir /tmp/traj \\
      --stations "1-10" --combo "seven+box+snake" --points 13
  python3 make_trajectory_plan.py --input sites.csv --out-dir /tmp/traj \\
      --stations "1-10" --combo-random "2-5" --seed 7 --points 13
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import random
import re
import sys
from datetime import datetime, timezone

# 纬度 1° 的地表距离（米）。经度 1° ≈ METERS_PER_DEG_LAT * cos(lat)。
METERS_PER_DEG_LAT = 111320.0

# 1-based 数据行号 → addname 前缀（全小写字母数字连字符，QWY 档案名保守兼容）。
ADDNAME_FMT = "traj-{station:03d}-{step:02d}"

# 输入表头（小写比对；跳过其前的任意标题行）。
EXPECTED_HEADER = ("custom_admin_3", "latitude1", "longitude1", "ecgi")

HEADER_SCAN_LIMIT = 10  # 表头必须出现在前 N 行内

# 需求约束：每站展开点数 clamp 到 [10, 20]。
POINTS_MIN = 10
POINTS_MAX = 20

# 全站展开时打印规模警告的站点数阈值。
SCALE_WARN_STATIONS = 100

# 预设轨迹模板（与 --steps 同一语法；循环展开）。
PRESETS = {
    # 方框回路：东50 北50 西50 南50，4 步闭合回原点。
    "box50": "E50,N50,W50,S50",
    # 十字：东75 回原点，北75 回原点（两轴往复）。
    "cross75": "E75,W75,N75,S75",
    # 直线步行：持续向东 100m。
    "walk100": "E100",
}

_STEP_RE = re.compile(r"^([EWNS])([0-9]+(?:\.[0-9]+)?)$")

# ---------------------------------------------------------------------------
# 场景库（编号 1-4）：多场景组合（combo）的原子形状。
# 每形状默认步长 50m（场景名可带步长后缀覆盖，如 seven50/snake75）；
# steps 为 (方向, 步数) 移动段序列（段距 = 步数×步长）；default_points 为默认
# 展开点数（含场景起点，即 len(steps)+1）。
#   1 line  （1 字形）单方向直线 3 步 → 4 点
#   2 seven （7 字形）先东 2 步再北 2 步 → 5 点
#   3 snake （蛇字形）东 1→北 1→西 2→北 1→东 1 横向蛇行（西 2 步合并为一段
#           2×50m，保默认 6 点，终点在起点正北方）→ 6 点
#   4 box   （方块）东-北-西-南 回到进入点 → 5 点（含回路闭合点）
# ---------------------------------------------------------------------------
SCENE_DEFAULT_STEP_M = 50.0
SCENES: dict[str, dict] = {
    "line": {"num": 1, "steps": (("E", 1), ("E", 1), ("E", 1)), "default_points": 4},
    "seven": {"num": 2, "steps": (("E", 1), ("E", 1), ("N", 1), ("N", 1)), "default_points": 5},
    "snake": {"num": 3, "steps": (("E", 1), ("N", 1), ("W", 2), ("N", 1), ("E", 1)), "default_points": 6},
    "box": {"num": 4, "steps": (("E", 1), ("N", 1), ("W", 1), ("S", 1)), "default_points": 5},
}
# 编号 1-4 顺序（combo-random 抽样空间）。
SCENE_ORDER = tuple(SCENES)
DEFAULT_SCENE_POINTS = {name: spec["default_points"] for name, spec in SCENES.items()}

# 场景 token：场景名 + 可选步长后缀（正数米数），如 "seven" / "seven50" / "snake7.5"。
_SCENE_TOKEN_RE = re.compile(r"^(line|seven|snake|box)([0-9]+(?:\.[0-9]+)?)?$")

# combo-random 每站场景数下限：combo 语义要求至少 2 个场景成序列。
COMBO_COUNT_MIN = 2


class ToolError(Exception):
    """带用户可读消息的工具级错误（main 捕获后 exit 2）。"""


def parse_steps(spec: str) -> list[tuple[str, float]]:
    """解析 "E50,N50,W50,S50" 为 [(方向, 米), ...]。方向 E/W/N/S。"""
    steps: list[tuple[str, float]] = []
    for token in spec.split(","):
        token = token.strip().upper()
        if not token:
            continue
        match = _STEP_RE.match(token)
        if not match:
            raise ToolError(
                f"非法步进项 {token!r}（--steps 语法：方向 E/W/N/S + 米数，如 E50,N50）"
            )
        meters = float(match.group(2))
        if meters <= 0:
            raise ToolError(f"步进距离必须为正数，得到 {token!r}")
        steps.append((match.group(1), meters))
    if not steps:
        raise ToolError("--steps 不能为空")
    return steps


def resolve_template(
    steps_arg: str | None, preset_arg: str | None
) -> tuple[list[tuple[str, float]], str]:
    """确定轨迹模板。--steps 优先；否则用 --preset（缺省 box50）；两者同给报错。"""
    if steps_arg is not None and preset_arg is not None:
        raise ToolError(
            "--steps 与 --preset 互斥：轨迹模板三选一"
            "（--steps 步进序列 / --preset 预设 / --combo 或 --combo-random 场景组合）"
        )
    if steps_arg is not None:
        return parse_steps(steps_arg), f"steps:{steps_arg.strip()}"
    preset = preset_arg if preset_arg is not None else "box50"
    if preset not in PRESETS:
        raise ToolError(f"未知预设 {preset!r}，可选：{', '.join(sorted(PRESETS))}")
    return parse_steps(PRESETS[preset]), f"preset:{preset}"


def parse_scene_token(token: str) -> tuple[str, float]:
    """解析场景 token 为 (场景名, 步长米)。可带步长后缀覆盖默认 50m，如 seven50。"""
    match = _SCENE_TOKEN_RE.match(token)
    if not match:
        raise ToolError(
            f"非法场景 {token!r}（可选：{', '.join(SCENE_ORDER)}，"
            "可带步长后缀如 seven50/snake75）"
        )
    step_m = float(match.group(2)) if match.group(2) else SCENE_DEFAULT_STEP_M
    if step_m <= 0:
        raise ToolError(f"场景步长必须为正数，得到 {token!r}")
    return match.group(1), step_m


def parse_combo(spec: str) -> list[tuple[str, float]]:
    """解析显式场景序列 "seven+box+snake" 为 [(场景名, 步长米), ...]（全站统一）。"""
    scenes: list[tuple[str, float]] = []
    for token in spec.split("+"):
        token = token.strip().lower()
        if not token:
            raise ToolError(f'非法场景序列 {spec!r}（"+" 分隔，不能有空项，如 "seven+box+snake"）')
        scenes.append(parse_scene_token(token))
    if not scenes:
        raise ToolError("--combo 不能为空")
    return scenes


def parse_count_range(spec: str) -> tuple[int, int]:
    """解析 combo-random 的每站场景数闭区间 "2-5" → (2, 5)。lo ≥ 2、hi ≥ lo。"""
    match = re.fullmatch(r"\s*(\d+)\s*-\s*(\d+)\s*", spec)
    if not match:
        raise ToolError(f'非法 --combo-random {spec!r}（格式应为 "min-max"，如 "2-5"）')
    lo, hi = int(match.group(1)), int(match.group(2))
    if lo < COMBO_COUNT_MIN:
        raise ToolError(
            f"--combo-random 每站场景数至少 {COMBO_COUNT_MIN} 个（combo 语义），得到 min={lo}"
        )
    if lo > hi:
        raise ToolError(f"--combo-random 区间上下界颠倒：min {lo} > max {hi}")
    return lo, hi


def parse_scene_points(spec: str) -> dict[str, int]:
    """解析 "line=4,seven=5,snake=6,box=5" 为场景点数覆盖表（可只写子集）。"""
    overrides: dict[str, int] = {}
    for token in spec.split(","):
        token = token.strip()
        if not token:
            continue
        name, sep, num = token.partition("=")
        name = name.strip().lower()
        if name not in SCENES:
            raise ToolError(f"未知场景 {name!r}（--scene-points 可选：{', '.join(SCENE_ORDER)}）")
        if not sep or not num.strip().isdigit():
            raise ToolError(f'非法场景点数项 {token!r}（格式应为 "场景=点数"，如 "snake=6"）')
        points = int(num)
        if points < 2:
            raise ToolError(f"场景 {name} 点数必须 ≥ 2（至少含一个步进点），得到 {points}")
        overrides[name] = points
    if not overrides:
        raise ToolError("--scene-points 未解析出任何覆盖项")
    return overrides


def scenes_to_template(
    scenes: list[tuple[str, float]], scene_points: dict[str, int]
) -> list[tuple[str, float]]:
    """场景序列 → 步进模板（衔接规则）。

    每场景展开到 scene_points[name] 个点（即 points-1 步，场景自身步进序列
    循环/截断到目标点数）；场景间直接拼接——开放形状从当前笔位置继续
    （上一场景终点=下一场景起点），box 步进 E,N,W,S 天然闭合回其进入点，
    全程无重复点、无跳变。
    """
    template: list[tuple[str, float]] = []
    for name, step_m in scenes:
        moves = SCENES[name]["steps"]
        for i in range(scene_points[name] - 1):
            direction, units = moves[i % len(moves)]
            template.append((direction, units * step_m))
    return template


def resolve_trajectory_spec(
    steps_arg: str | None,
    preset_arg: str | None,
    combo_arg: str | None = None,
    combo_random_arg: str | None = None,
) -> tuple[str, object, str]:
    """确定轨迹模式（三选一：--steps 步进序列 / --preset 预设 / combo 场景组合）。

    返回 (mode, payload, desc)：
      mode="template"     → payload 为步进模板（旧语义，逐字节兼容）
      mode="combo"        → payload 为显式场景序列 [(场景名, 步长米), ...]（全站统一）
      mode="combo_random" → payload 为每站场景数闭区间 (lo, hi)
    """
    combo_flags = [
        name for name, value in (("--combo", combo_arg), ("--combo-random", combo_random_arg))
        if value is not None
    ]
    if combo_flags and (steps_arg is not None or preset_arg is not None):
        old_flag = "--steps" if steps_arg is not None else "--preset"
        raise ToolError(
            f"{old_flag} 与 {combo_flags[0]} 互斥：轨迹模板三选一"
            "（--steps 步进序列 / --preset 预设 / --combo 或 --combo-random 场景组合）"
        )
    if len(combo_flags) > 1:
        raise ToolError(
            "--combo 与 --combo-random 互斥：请只指定其一（显式场景序列用前者，每站随机抽取用后者）"
        )
    if combo_arg is not None:
        scenes = parse_combo(combo_arg)
        return "combo", scenes, "combo:" + "+".join(name for name, _ in scenes)
    if combo_random_arg is not None:
        lo, hi = parse_count_range(combo_random_arg)
        return "combo_random", (lo, hi), f"combo-random:{lo}-{hi}"
    template, desc = resolve_template(steps_arg, preset_arg)
    return "template", template, desc


def parse_stations(spec: str, total: int) -> list[int]:
    """解析 "1-10,33,100" 为去重升序的 1-based 数据行号列表。"""
    selected: set[int] = set()
    for token in spec.split(","):
        token = token.strip()
        if not token:
            continue
        if "-" in token:
            lo_s, _, hi_s = token.partition("-")
            if not lo_s.isdigit() or not hi_s.isdigit():
                raise ToolError(f"非法站点区间 {token!r}（应为如 1-10）")
            lo, hi = int(lo_s), int(hi_s)
        else:
            if not token.isdigit():
                raise ToolError(f"非法站点号 {token!r}（应为正整数或区间）")
            lo = hi = int(token)
        if lo > hi:
            raise ToolError(f"站点区间上下界颠倒：{token!r}")
        if lo < 1 or hi > total:
            raise ToolError(f"站点 {token!r} 超出范围（数据行号 1-{total}）")
        selected.update(range(lo, hi + 1))
    if not selected:
        raise ToolError("--stations 未解析出任何站点")
    return sorted(selected)


def parse_required_range(spec: str) -> tuple[int, int]:
    """解析 "min-max" 为闭区间 (min, max)。min ≥ 1、max ≥ min，fail-closed。"""
    match = re.fullmatch(r"\s*(\d+)\s*-\s*(\d+)\s*", spec)
    if not match:
        raise ToolError(f'非法 --required-range {spec!r}（格式应为 "min-max"，如 "1-5"）')
    lo, hi = int(match.group(1)), int(match.group(2))
    if lo < 1:
        raise ToolError(f"--required-range 最小值必须 ≥ 1，得到 min={lo}")
    if lo > hi:
        raise ToolError(f"--required-range 区间上下界颠倒：min {lo} > max {hi}")
    return lo, hi


def delta_for_step(direction: str, meters: float, lat: float) -> tuple[float, float]:
    """由方向与米数求 (dlat, dlng)。经度换算用当前纬度做 cos 修正。"""
    if direction == "N":
        return meters / METERS_PER_DEG_LAT, 0.0
    if direction == "S":
        return -meters / METERS_PER_DEG_LAT, 0.0
    deg_lng = meters / (METERS_PER_DEG_LAT * math.cos(math.radians(lat)))
    if direction == "E":
        return 0.0, deg_lng
    return 0.0, -deg_lng  # W


def build_trajectory(
    lat0: float, lng0: float, template: list[tuple[str, float]], points: int
) -> tuple[list[tuple[float, float]], float, dict[str, float]]:
    """从原点循环套用模板生成 points 个点（含原点，第一步是模板第 1 项）。

    返回 (坐标列表(全精度), 累计路径米数, 包围盒)。
    """
    coords = [(lat0, lng0)]
    lat, lng = lat0, lng0
    path_m = 0.0
    bbox = {"min_lat": lat0, "max_lat": lat0, "min_lng": lng0, "max_lng": lng0}
    for idx in range(1, points):
        direction, meters = template[(idx - 1) % len(template)]
        dlat, dlng = delta_for_step(direction, meters, lat)
        lat, lng = lat + dlat, lng + dlng
        path_m += meters
        coords.append((lat, lng))
        bbox["min_lat"] = min(bbox["min_lat"], lat)
        bbox["max_lat"] = max(bbox["max_lat"], lat)
        bbox["min_lng"] = min(bbox["min_lng"], lng)
        bbox["max_lng"] = max(bbox["max_lng"], lng)
    return coords, path_m, bbox


def load_stations(path: str) -> list[dict[str, str]]:
    """读入站点 CSV：跳过标题行、定位表头、逐行校验（fail-closed 原子语义）。"""
    try:
        with open(path, encoding="utf-8-sig", newline="") as handle:
            rows = list(csv.reader(handle))
    except OSError as exc:
        raise ToolError(f"无法读取输入文件 {path}: {exc}") from exc
    except UnicodeDecodeError as exc:
        raise ToolError(f"输入文件不是 UTF-8 编码：{exc}") from exc

    header_idx = -1
    for i, row in enumerate(rows[:HEADER_SCAN_LIMIT]):
        if [cell.strip().lower() for cell in row] == list(EXPECTED_HEADER):
            header_idx = i
            break
    if header_idx < 0:
        raise ToolError(
            f"前 {HEADER_SCAN_LIMIT} 行内未找到表头 {','.join(EXPECTED_HEADER)}；"
            "输入应为 custom_admin_3,latitude1,longitude1,ECGI（纬度列在前）"
        )

    stations: list[dict[str, str]] = []
    for offset, row in enumerate(rows[header_idx + 1 :], start=1):
        if not row or all(not cell.strip() for cell in row):
            raise ToolError(f"数据第 {offset} 行为空行（工具按原子语义拒绝空行）")
        cells = [cell.strip() for cell in row]
        if len(cells) != 4:
            raise ToolError(
                f"数据第 {offset} 行列数为 {len(cells)}，期望 4 "
                f"(custom_admin_3,latitude1,longitude1,ECGI)"
            )
        admin, lat_s, lng_s, ecgi = cells
        try:
            lat, lng = float(lat_s), float(lng_s)
        except ValueError as exc:
            raise ToolError(f"数据第 {offset} 行坐标无法解析：{lat_s!r},{lng_s!r}") from exc
        if not -90.0 <= lat <= 90.0:
            raise ToolError(f"数据第 {offset} 行纬度越界：{lat}")
        if not -180.0 <= lng <= 180.0:
            raise ToolError(f"数据第 {offset} 行经度越界：{lng}")
        if not ecgi:
            raise ToolError(f"数据第 {offset} 行 ECGI 为空")
        stations.append(
            {"row": offset, "custom_admin_3": admin, "lat": lat, "lng": lng, "ecgi": ecgi}
        )
    if not stations:
        raise ToolError("表头之后没有任何数据行")
    return stations


def fmt7(value: float) -> str:
    """坐标统一 7 位小数（对齐输入精度）。"""
    return f"{value:.7f}"


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="make_trajectory_plan.py",
        description="站点 CSV → 轨迹行动计划三件套（plan.csv / profiles.csv / ecgi_map.csv + manifest.json）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            '  %(prog)s --input "0914 test 2.csv" --out-dir /tmp/traj \\\n'
            '      --stations "1-3" --preset box50 --points 13\n'
            '  %(prog)s --input sites.csv --out-dir /tmp/traj --steps "E50,N50,E50,S50"\n'
            '  %(prog)s --input sites.csv --out-dir /tmp/traj --stations "1-10" \\\n'
            '      --combo "seven+box+snake" --points 13\n'
            '  %(prog)s --input sites.csv --out-dir /tmp/traj --stations "1-10" \\\n'
            '      --combo-random "2-5" --seed 7 --points 13\n'
            "\n"
            "场景库 (--combo / --combo-random，编号 1-4，默认步长 50m):\n"
            "  line  (1) 1 字形：单方向直线 3 步，默认 4 点\n"
            "  seven (2) 7 字形：东 2 步→北 2 步，默认 5 点\n"
            "  snake (3) 蛇字形：东→北→西 2→北→东 蛇行，默认 6 点\n"
            "  box   (4) 方块：东-北-西-南 闭合回进入点，默认 5 点\n"
            "\n"
            "预设模板 (--preset):\n"
            "  box50    东50 北50 西50 南50 方框回路（4 步闭合，循环展开）\n"
            "  cross75  东75 回原点、北75 回原点（两轴往复十字）\n"
            "  walk100  持续向东 100m 直线步行\n"
            "\n"
            "配合导入（详见同目录 README.md）:\n"
            "  plan.csv（5 列含 ci）→ CellRebel Auto 计划导入（Download），ci 列供 #190 验证层对照；\n"
            "  profiles.csv（含 ci 第 4 列）→ QWY 收藏档案导入，#193 读回门按 ci 字节级比对；\n"
            "  ecgi_map.csv → #189 CI hook / #190 验证层的位置→ECGI 期望（审计用）。\n"
            "  导入 QWY 档案后记得编辑保存任一档案完成锚定（SKILL §2.6）。\n"
        ),
    )
    parser.add_argument("--input", required=True, help="原始站点 CSV（custom_admin_3,latitude1,longitude1,ECGI）")
    parser.add_argument("--out-dir", required=True, help="三件套输出目录（不存在则创建）")
    parser.add_argument(
        "--steps",
        help='方向+米步进序列，如 "E50,N50,E50,S50"（E/W/N/S；与 --preset 互斥）',
    )
    parser.add_argument(
        "--preset",
        choices=sorted(PRESETS),
        help="预设轨迹模板（--steps 缺省时生效；两者都不给则用 box50）",
    )
    parser.add_argument(
        "--combo",
        help=(
            '显式场景序列（全站统一），如 "seven+box+snake"；场景名可带步长后缀'
            "（seven50/snake75，缺省 50m）；与 --steps/--preset/--combo-random 互斥"
        ),
    )
    parser.add_argument(
        "--combo-random",
        help=(
            '每站独立从场景库（line/seven/snake/box，编号 1-4）随机抽 "min-max" 个'
            '场景组成序列（可重复抽，如 snake+box+snake），如 "2-5"；'
            "配合 --seed 可复现；与 --steps/--preset/--combo 互斥"
        ),
    )
    parser.add_argument(
        "--scene-points",
        help=(
            "覆盖各场景默认展开点数（仅 combo 模式生效），"
            '如 "line=4,seven=5,snake=6,box=5"；场景实际展开到该点数'
            "（步进序列循环/截断）"
        ),
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help=(
            "随机种子（--combo-random / --required-range 随机模式时生效；"
            "缺省自动生成并写入 manifest，同 seed 同输入可复现）"
        ),
    )
    parser.add_argument(
        "--points",
        type=int,
        default=12,
        help=f"每站展开点数（含原点），clamp 到 {POINTS_MIN}-{POINTS_MAX}；默认 12",
    )
    parser.add_argument(
        "--stations",
        help='站点行选择，1-based 原始 CSV 数据行号，如 "1-10,33,100"；缺省=全部（会打印规模警告）',
    )
    parser.add_argument("--priority", type=int, default=3, help="plan.csv 的 priority 常量（默认 3）")
    parser.add_argument(
        "--required-successes",
        type=int,
        default=None,
        help="plan.csv 的 required_successes 常量（默认 3；与 --required-range 互斥）",
    )
    parser.add_argument(
        "--required-range",
        help=(
            '每行 required_successes 在 [min,max] 闭区间均匀随机（每行独立），'
            '如 "1-5"；与 --required-successes 互斥，配合 --seed 可复现'
        ),
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    try:
        mode, payload, template_desc = resolve_trajectory_spec(
            args.steps, args.preset, args.combo, args.combo_random
        )

        if args.scene_points is not None and mode == "template":
            raise ToolError("--scene-points 仅在 --combo / --combo-random 时生效（单模板模式无场景概念）")
        # 各场景有效点数 = 默认表 + --scene-points 覆盖（combo 模式的展开依据）。
        scene_points = {
            **DEFAULT_SCENE_POINTS,
            **(parse_scene_points(args.scene_points) if args.scene_points is not None else {}),
        }

        if mode == "combo_random":
            count_lo, count_hi = payload
            # 复用 #209 seed 机制：不指定时取 OS 随机 seed，落盘 manifest 可事后复现。
            seed = args.seed if args.seed is not None else random.SystemRandom().randrange(2**63)
            # 场景流独立成流（req_rng/rng 服务 --required-range），两条流同源于
            # seed、各自顺序消费，互不挤占游标；单用任一模式时序列与原来一致。
            combo_rng = random.Random(seed)
            combo_template = None
        elif mode == "combo":
            combo_template = scenes_to_template(payload, scene_points)
        else:
            combo_template = None

        points = args.points
        if points < POINTS_MIN or points > POINTS_MAX:
            clamped = max(POINTS_MIN, min(POINTS_MAX, points))
            print(f"[warn] --points {points} 超出需求区间，clamp 为 {clamped}（{POINTS_MIN}-{POINTS_MAX}）")
            points = clamped

        if args.priority < 0:
            raise ToolError(f"--priority 必须 ≥ 0，得到 {args.priority}")

        # required_successes 二选一：--required-successes（常量，缺省 3）或
        # --required-range（每行独立均匀随机）。都给/都缺按 fail-closed 处理。
        range_given = args.required_range is not None
        successes_given = args.required_successes is not None
        if range_given and successes_given:
            raise ToolError(
                "--required-successes 与 --required-range 互斥：请二选一"
                "（常量用前者；每行随机用后者）"
            )
        # seed 服务两种随机源（--combo-random 每站场景抽取 / --required-range
        # 每行执行次数）；两者都没给时 seed 无处可用，fail-closed 并提示两种模式。
        if args.seed is not None and mode != "combo_random" and not range_given:
            raise ToolError(
                "--seed 仅在随机模式时生效：--combo-random（每站场景序列随机抽取）"
                "或 --required-range（每行 required_successes 随机区间）"
            )
        if range_given:
            req_min, req_max = parse_required_range(args.required_range)
            # seed 落盘到 manifest：不指定时取 OS 随机 seed，事后仍可复现该次产出。
            # 与 combo_rng 并列的第二条随机流（次数流）；单用 --required-range 时
            # 消费序列与 #209 逐字节一致。
            seed = args.seed if args.seed is not None else random.SystemRandom().randrange(2**63)
            rng = random.Random(seed)
            rng_desc = f"random.Random(seed).randint({req_min},{req_max}) [MT19937]"
        else:
            required_successes = args.required_successes if successes_given else 3
            if required_successes < 1:
                raise ToolError(f"--required-successes 必须 ≥ 1，得到 {required_successes}")

        stations = load_stations(args.input)

        if args.stations is None:
            selected_rows = list(range(1, len(stations) + 1))
            if len(selected_rows) > SCALE_WARN_STATIONS:
                print(
                    f"[warn] 未指定 --stations：将展开全部 {len(selected_rows)} 站 × {points} 点 = "
                    f"{len(selected_rows) * points} 行计划；如需小样先试 --stations \"1-10\""
                )
        else:
            selected_rows = parse_stations(args.stations, len(stations))

        chosen = [stations[row - 1] for row in selected_rows]

        plan_rows: list[list[str]] = []
        profile_rows: list[list[str]] = []
        ecgi_rows: list[list[str]] = []
        station_summaries: list[dict] = []
        req_values: list[int] = []  # plan.csv 行序的 required_successes（审计摘要用）

        for station in chosen:
            if mode == "template":
                template = payload
                station_scenes = None
            elif mode == "combo":
                template = combo_template
                station_scenes = payload
            else:  # combo_random：每站独立抽场景数与场景序列（combo_rng 按站序消耗，可复现）。
                count = combo_rng.randint(count_lo, count_hi)
                station_scenes = [
                    (combo_rng.choice(SCENE_ORDER), SCENE_DEFAULT_STEP_M) for _ in range(count)
                ]
                template = scenes_to_template(station_scenes, scene_points)
            coords, path_m, bbox = build_trajectory(
                station["lat"], station["lng"], template, points
            )
            station_reqs: list[int] = []
            for step_idx, (lat, lng) in enumerate(coords):
                addname = ADDNAME_FMT.format(station=station["row"], step=step_idx)
                if range_given:
                    req = rng.randint(req_min, req_max)
                else:
                    req = required_successes
                req_values.append(req)
                station_reqs.append(req)
                # plan.csv 第 5 列 ci（#190 验证层的期望 ECGI）：与 profiles 同源继承，
                # 同域同值零换算——Auto 导入器按 #193 的 ci 值域（28-bit ECI）校验。
                plan_rows.append(
                    [fmt7(lng), fmt7(lat), str(args.priority), str(req), station["ecgi"]]
                )
                profile_rows.append([addname, fmt7(lat), fmt7(lng), station["ecgi"]])
                ecgi_rows.append(
                    [addname, station["ecgi"], station["custom_admin_3"], str(station["row"])]
                )
            summary = {
                "station": station["row"],
                "custom_admin_3": station["custom_admin_3"],
                "ecgi": station["ecgi"],
                "origin": {"lat": round(station["lat"], 7), "lng": round(station["lng"], 7)},
                "points": points,
                "path_m": round(path_m, 3),
                "bbox": {key: round(value, 7) for key, value in bbox.items()},
                "required_successes": {
                    "min": min(station_reqs),
                    "max": max(station_reqs),
                    "sum": sum(station_reqs),
                },
            }
            if station_scenes is not None:
                # combo 模式：记录该站场景序列（编号/名称/点数/步长），审计可追溯。
                summary["scenes"] = [
                    {
                        "name": name,
                        "num": SCENES[name]["num"],
                        "points": scene_points[name],
                        "step_m": step_m,
                    }
                    for name, step_m in station_scenes
                ]
            station_summaries.append(summary)

        os.makedirs(args.out_dir, exist_ok=True)

        def write_csv(name: str, header: tuple[str, ...], rows: list[list[str]]) -> None:
            with open(os.path.join(args.out_dir, name), "w", encoding="utf-8", newline="") as handle:
                writer = csv.writer(handle, lineterminator="\n")
                writer.writerow(header)
                writer.writerows(rows)

        write_csv(
            "plan.csv",
            ("longitude", "latitude", "priority", "required_successes", "ci"),
            plan_rows,
        )
        write_csv("profiles.csv", ("addname", "latitude", "longitude", "ci"), profile_rows)
        write_csv("ecgi_map.csv", ("addname", "ecgi", "custom_admin_3", "source_row"), ecgi_rows)

        try:
            with open(args.input, "rb") as handle:
                input_sha256 = hashlib.sha256(handle.read()).hexdigest()
        except OSError:
            input_sha256 = None

        # 旧模板模式保持 manifest 原键位不变；combo 模式追加 trajectory_mode/
        # scene_points/combo 键（随机组合记 seed + rng 方式，同 seed 可复现）。
        if mode == "template":
            manifest_steps = [f"{direction}{meters:g}" for direction, meters in payload]
        elif mode == "combo":
            manifest_steps = [f"{direction}{meters:g}" for direction, meters in combo_template]
        else:
            manifest_steps = None

        manifest = {
            "tool": "scripts/trajectory/make_trajectory_plan.py",
            "issue": 191,
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "input": {
                "path": args.input,
                "sha256": input_sha256,
                "data_rows": len(stations),
            },
            "params": {
                "template": template_desc,
                "steps": manifest_steps,
                "points": points,
                "points_clamped": args.points != points,
                "stations_arg": args.stations,
                "selected_stations": len(chosen),
                "priority": args.priority,
                # 常量模式记常量值；随机模式置 null，值分布见 required_successes_summary。
                "required_successes": None if range_given else required_successes,
                "required_successes_mode": "random_range" if range_given else "constant",
            },
            "outputs": {
                "plan.csv": len(plan_rows),
                "profiles.csv": len(profile_rows),
                "ecgi_map.csv": len(ecgi_rows),
            },
            "stations": station_summaries,
        }
        if range_given:
            distribution: dict[str, int] = {}
            for value in req_values:
                distribution[str(value)] = distribution.get(str(value), 0) + 1
            manifest["params"]["required_successes_range"] = {"min": req_min, "max": req_max}
            manifest["params"]["seed"] = seed
            manifest["params"]["rng"] = rng_desc
            manifest["required_successes_summary"] = {
                "min": min(req_values),
                "max": max(req_values),
                "sum": sum(req_values),
                # 值 → 出现行数（按值升序）；总量审计看 sum，拟真度审计看分布。
                "distribution": {
                    key: distribution[key] for key in sorted(distribution, key=int)
                },
            }
        if mode == "combo":
            manifest["params"]["trajectory_mode"] = "combo"
            manifest["params"]["scene_points"] = scene_points
            manifest["params"]["combo"] = {
                "mode": "explicit",
                "spec": args.combo.strip(),
                "scenes": [name for name, _ in payload],
            }
        elif mode == "combo_random":
            manifest["params"]["trajectory_mode"] = "combo_random"
            manifest["params"]["scene_points"] = scene_points
            manifest["params"]["combo"] = {
                "mode": "random",
                "count_range": [count_lo, count_hi],
                "seed": seed,
                "rng": "random.Random(seed)：每站 randint(场景数) + choice(场景) [MT19937]",
            }
        with open(os.path.join(args.out_dir, "manifest.json"), "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

        detail = f"（模板 {template_desc}，含原点）"
        if mode == "combo_random":
            detail = f"（模板 {template_desc}，seed={seed}，含原点）"
        ok_line = (
            f"[ok] {len(chosen)} 站 × {points} 点{detail}→ {args.out_dir}\n"
            f"     plan.csv {len(plan_rows)} 行 | profiles.csv {len(profile_rows)} 行 | "
            f"ecgi_map.csv {len(ecgi_rows)} 行 | manifest.json"
        )
        if range_given:
            ok_line += (
                f"\n     required_successes 随机 [{req_min}-{req_max}] "
                f"seed={seed} | 总执行 {sum(req_values)} 次（分布见 manifest）"
            )
        print(ok_line)
        if mode != "template":
            # combo 模式逐站打印场景序列（随机组合时各站不同，审计/核对用）。
            for summary in station_summaries:
                seq = "+".join(entry["name"] for entry in summary["scenes"])
                pts = "+".join(str(entry["points"]) for entry in summary["scenes"])
                print(f"     traj-{summary['station']:03d}: {seq}（{pts} 点）")
        return 0
    except ToolError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

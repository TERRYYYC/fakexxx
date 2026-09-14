#!/usr/bin/env python3
"""轨迹加工工具：站点 CSV → 轨迹行动计划三件套（For #191）。

输入：原始站点表（custom_admin_3,latitude1,longitude1,ECGI；前面可有任意标题行，
如 Excel 导出的「表格 1」）。注意输入列序是 latitude1 在前、longitude1 在后。

输出（写入 --out-dir）：
  plan.csv      longitude,latitude,priority,required_successes（列序 lng 在前，
                对齐 WorklistParser canonical contract，直接进 Download 作计划）
  profiles.csv  addname,latitude,longitude（QWY 收藏档案导入格式）
  ecgi_map.csv  addname,ecgi,custom_admin_3,source_row（轨迹点继承原始行信息，
                供 #189 CI hook 与 #190 验证层使用）
  manifest.json 参数快照 + 行数统计 + 每站轨迹摘要（审计复现用）

仅用 Python 3 标准库。

用法示例：
  python3 make_trajectory_plan.py --input "0914 test 2.csv" --out-dir /tmp/traj \\
      --stations "1-3" --preset box50 --points 13
  python3 make_trajectory_plan.py --input sites.csv --out-dir /tmp/traj \\
      --steps "E50,N50,E50,S50" --points 12
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
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
        raise ToolError("--steps 与 --preset 互斥：请只指定其一（--steps 缺省时才用 --preset）")
    if steps_arg is not None:
        return parse_steps(steps_arg), f"steps:{steps_arg.strip()}"
    preset = preset_arg if preset_arg is not None else "box50"
    if preset not in PRESETS:
        raise ToolError(f"未知预设 {preset!r}，可选：{', '.join(sorted(PRESETS))}")
    return parse_steps(PRESETS[preset]), f"preset:{preset}"


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
            "\n"
            "预设模板 (--preset):\n"
            "  box50    东50 北50 西50 南50 方框回路（4 步闭合，循环展开）\n"
            "  cross75  东75 回原点、北75 回原点（两轴往复十字）\n"
            "  walk100  持续向东 100m 直线步行\n"
            "\n"
            "配合导入（详见同目录 README.md）:\n"
            "  plan.csv → CellRebel Auto 计划导入（Download）；profiles.csv → QWY 收藏档案导入；\n"
            "  ecgi_map.csv → #189 CI hook / #190 验证层的位置→ECGI 期望。\n"
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
        "--required-successes", type=int, default=3, help="plan.csv 的 required_successes 常量（默认 3）"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    try:
        template, template_desc = resolve_template(args.steps, args.preset)

        points = args.points
        if points < POINTS_MIN or points > POINTS_MAX:
            clamped = max(POINTS_MIN, min(POINTS_MAX, points))
            print(f"[warn] --points {points} 超出需求区间，clamp 为 {clamped}（{POINTS_MIN}-{POINTS_MAX}）")
            points = clamped

        if args.priority < 0:
            raise ToolError(f"--priority 必须 ≥ 0，得到 {args.priority}")
        if args.required_successes < 1:
            raise ToolError(f"--required-successes 必须 ≥ 1，得到 {args.required_successes}")

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

        for station in chosen:
            coords, path_m, bbox = build_trajectory(
                station["lat"], station["lng"], template, points
            )
            for step_idx, (lat, lng) in enumerate(coords):
                addname = ADDNAME_FMT.format(station=station["row"], step=step_idx)
                plan_rows.append([fmt7(lng), fmt7(lat), str(args.priority), str(args.required_successes)])
                profile_rows.append([addname, fmt7(lat), fmt7(lng)])
                ecgi_rows.append(
                    [addname, station["ecgi"], station["custom_admin_3"], str(station["row"])]
                )
            station_summaries.append(
                {
                    "station": station["row"],
                    "custom_admin_3": station["custom_admin_3"],
                    "ecgi": station["ecgi"],
                    "origin": {"lat": round(station["lat"], 7), "lng": round(station["lng"], 7)},
                    "points": points,
                    "path_m": round(path_m, 3),
                    "bbox": {key: round(value, 7) for key, value in bbox.items()},
                }
            )

        os.makedirs(args.out_dir, exist_ok=True)

        def write_csv(name: str, header: tuple[str, ...], rows: list[list[str]]) -> None:
            with open(os.path.join(args.out_dir, name), "w", encoding="utf-8", newline="") as handle:
                writer = csv.writer(handle, lineterminator="\n")
                writer.writerow(header)
                writer.writerows(rows)

        write_csv("plan.csv", ("longitude", "latitude", "priority", "required_successes"), plan_rows)
        write_csv("profiles.csv", ("addname", "latitude", "longitude"), profile_rows)
        write_csv("ecgi_map.csv", ("addname", "ecgi", "custom_admin_3", "source_row"), ecgi_rows)

        try:
            with open(args.input, "rb") as handle:
                input_sha256 = hashlib.sha256(handle.read()).hexdigest()
        except OSError:
            input_sha256 = None

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
                "steps": [f"{direction}{meters:g}" for direction, meters in template],
                "points": points,
                "points_clamped": args.points != points,
                "stations_arg": args.stations,
                "selected_stations": len(chosen),
                "priority": args.priority,
                "required_successes": args.required_successes,
            },
            "outputs": {
                "plan.csv": len(plan_rows),
                "profiles.csv": len(profile_rows),
                "ecgi_map.csv": len(ecgi_rows),
            },
            "stations": station_summaries,
        }
        with open(os.path.join(args.out_dir, "manifest.json"), "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

        print(
            f"[ok] {len(chosen)} 站 × {points} 点（模板 {template_desc}，含原点）→ {args.out_dir}\n"
            f"     plan.csv {len(plan_rows)} 行 | profiles.csv {len(profile_rows)} 行 | "
            f"ecgi_map.csv {len(ecgi_rows)} 行 | manifest.json"
        )
        return 0
    except ToolError as exc:
        print(f"[error] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

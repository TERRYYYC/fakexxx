#!/usr/bin/env python3
"""make_trajectory_plan.py 的单元测试（For #191）。

运行：在 scripts/trajectory/ 目录下 `python3 -m unittest`，
或仓库根目录 `python3 -m unittest discover -s scripts/trajectory`。
"""

from __future__ import annotations

import csv
import json
import math
import os
import re
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import make_trajectory_plan as mtp  # noqa: E402

# 与真实样例（0914 test 2.csv 数据第 1 行）一致量级的夹具站点。
FIXTURE_CSV = """表格 1
custom_admin_3,latitude1,longitude1,ECGI
_Bilotserkivs'kyi_Kyivs'ka,49.8714584,29.9243986,28918569
_Bilotserkivs'kyi_Kyivs'ka,49.8240183,29.94462,29592117
_Fastivs'kyi_Kyivs'ka,50.0561,29.9173,29073696
"""


class Base(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        self.input_path = os.path.join(self.dir, "input.csv")
        with open(self.input_path, "w", encoding="utf-8", newline="") as handle:
            handle.write(FIXTURE_CSV)
        self.out_dir = os.path.join(self.dir, "out")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def run_tool(self, *extra: str) -> int:
        return mtp.main(["--input", self.input_path, "--out-dir", self.out_dir, *extra])

    def run_tool_out(self, out_dir: str, *extra: str) -> int:
        return mtp.main(["--input", self.input_path, "--out-dir", out_dir, *extra])

    def read_manifest(self, out_dir: str | None = None) -> dict:
        path = os.path.join(out_dir or self.out_dir, "manifest.json")
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)

    def read_plan_bytes(self, out_dir: str | None = None) -> bytes:
        with open(os.path.join(out_dir or self.out_dir, "plan.csv"), "rb") as handle:
            return handle.read()

    def read_csv(self, name: str) -> list[list[str]]:
        with open(os.path.join(self.out_dir, name), encoding="utf-8", newline="") as handle:
            return list(csv.reader(handle))


class StepMathTest(unittest.TestCase):
    """步进数学：纬度 1°≈111320m，经度 1°≈111320*cos(lat)m。"""

    def test_50m_deltas_at_lat_49_87(self) -> None:
        dlat, dlng = mtp.delta_for_step("N", 50.0, 49.87)
        # 50/111320 = 0.0004491...
        self.assertAlmostEqual(dlat, 0.000449, delta=1e-6)
        self.assertAlmostEqual(dlat, 50.0 / mtp.METERS_PER_DEG_LAT, places=12)

        dlat_s, _ = mtp.delta_for_step("S", 50.0, 49.87)
        self.assertAlmostEqual(dlat_s, -dlat, places=12)

        _, dlng_e = mtp.delta_for_step("E", 50.0, 49.87)
        expected = 50.0 / (mtp.METERS_PER_DEG_LAT * math.cos(math.radians(49.87)))
        self.assertAlmostEqual(dlng_e, expected, delta=1e-6)
        # 需求口径：50m 在 lat≈49.87 处 Δlng ≈ 0.00069 量级。
        self.assertTrue(6.85e-4 <= dlng_e <= 7.0e-4, f"Δlng={dlng_e}")

        _, dlng_w = mtp.delta_for_step("W", 50.0, 49.87)
        self.assertAlmostEqual(dlng_w, -dlng_e, places=12)

    def test_direction_signs(self) -> None:
        lat, lng = 49.0, 29.0
        _, east = mtp.delta_for_step("E", 10.0, lat)
        _, west = mtp.delta_for_step("W", 10.0, lat)
        north, _ = mtp.delta_for_step("N", 10.0, lat)
        south, _ = mtp.delta_for_step("S", 10.0, lat)
        self.assertGreater(east, 0)
        self.assertLess(west, 0)
        self.assertGreater(north, 0)
        self.assertLess(south, 0)


class TrajectoryTest(unittest.TestCase):
    def test_origin_is_first_point_and_count(self) -> None:
        template = mtp.parse_steps("E50,N50")
        coords, path_m, _ = mtp.build_trajectory(49.8714584, 29.9243986, template, 5)
        self.assertEqual(len(coords), 5)
        # 原点（step=0）必须是第一点且坐标原样保留。
        self.assertEqual(coords[0], (49.8714584, 29.9243986))
        self.assertAlmostEqual(path_m, 4 * 50.0, places=9)

    def test_box50_loops_and_closes(self) -> None:
        template, desc = mtp.resolve_template(None, "box50")
        self.assertEqual(desc, "preset:box50")
        coords, path_m, _ = mtp.build_trajectory(49.87, 29.92, template, 9)
        # 第 4 步（index 4）与第 8 步（index 8）应回到原点（方框闭合）。
        # lng 允许 1e-7 残差：西行步在偏北纬度做 cos 修正（更精确），二阶闭合误差毫米级。
        self.assertAlmostEqual(coords[4][0], 49.87, places=9)
        self.assertAlmostEqual(coords[4][1], 29.92, delta=1e-7)
        self.assertAlmostEqual(coords[8][0], 49.87, places=9)
        self.assertAlmostEqual(coords[8][1], 29.92, delta=1e-7)
        # 第 1 步向东 50m：lng 增加约 0.000697，lat 不变。
        self.assertAlmostEqual(coords[1][0], 49.87, places=9)
        self.assertAlmostEqual(coords[1][1] - 29.92, 50.0 / (mtp.METERS_PER_DEG_LAT * math.cos(math.radians(49.87))), delta=1e-9)
        self.assertAlmostEqual(path_m, 8 * 50.0, places=9)

    def test_steps_exceeding_template_cycle(self) -> None:
        template = mtp.parse_steps("N10")
        coords, path_m, bbox = mtp.build_trajectory(49.0, 29.0, template, 15)
        self.assertEqual(len(coords), 15)
        self.assertAlmostEqual(path_m, 140.0, places=9)
        self.assertAlmostEqual(bbox["max_lat"] - bbox["min_lat"], 140.0 / mtp.METERS_PER_DEG_LAT, delta=1e-9)

    def test_steps_and_preset_conflict(self) -> None:
        with self.assertRaises(mtp.ToolError):
            mtp.resolve_template("E50", "box50")


class EndToEndTest(Base):
    def run_default(self) -> None:
        self.assertEqual(self.run_tool("--stations", "1-3", "--preset", "box50", "--points", "13"), 0)

    def test_three_files_row_counts_match(self) -> None:
        self.run_default()
        plan = self.read_csv("plan.csv")
        profiles = self.read_csv("profiles.csv")
        ecgi = self.read_csv("ecgi_map.csv")
        expected_rows = 3 * 13
        for name, table in (("plan.csv", plan), ("profiles.csv", profiles), ("ecgi_map.csv", ecgi)):
            self.assertEqual(len(table) - 1, expected_rows, name)
        # 第 5 列 ci（#190 验证层的期望 ECGI）。
        self.assertEqual(
            plan[0], ["longitude", "latitude", "priority", "required_successes", "ci"]
        )
        self.assertEqual(profiles[0], ["addname", "latitude", "longitude", "ci"])
        self.assertEqual(ecgi[0], ["addname", "ecgi", "custom_admin_3", "source_row"])

    def test_plan_column_order_lng_first(self) -> None:
        self.run_default()
        plan = self.read_csv("plan.csv")
        first_data = plan[1]
        self.assertEqual(len(first_data), 5)
        lng, lat = float(first_data[0]), float(first_data[1])
        # 乌克兰站点：经度 ~29 > 纬度 ~49 是 lng 在前的反证不够硬，直接对照源行。
        self.assertAlmostEqual(lng, 29.9243986, places=7)
        self.assertAlmostEqual(lat, 49.8714584, places=7)
        self.assertEqual(first_data[2], "3")
        self.assertEqual(first_data[3], "3")
        self.assertEqual(first_data[4], "28918569")

    def test_origin_included_as_first_point(self) -> None:
        self.run_default()
        profiles = self.read_csv("profiles.csv")
        plan = self.read_csv("plan.csv")
        # step=00 即原点：addname 与坐标都对应源行第 1 行。
        self.assertEqual(profiles[1][0], "traj-001-00")
        self.assertAlmostEqual(float(profiles[1][1]), 49.8714584, places=7)
        self.assertAlmostEqual(float(profiles[1][2]), 29.9243986, places=7)
        # plan.csv 第一行也必须是原点（lng 在前）。
        self.assertAlmostEqual(float(plan[1][0]), 29.9243986, places=7)
        self.assertAlmostEqual(float(plan[1][1]), 49.8714584, places=7)

    def test_profiles_ci_column_and_inheritance(self) -> None:
        """profiles.csv 第 4 列 ci：列数/列名对齐 #193 QWY 导入器 header 契约，
        值继承源行 ECGI（读回门按此做字节级比对）。"""
        self.run_default()
        profiles = self.read_csv("profiles.csv")
        self.assertEqual(profiles[0], ["addname", "latitude", "longitude", "ci"])
        for row in profiles[1:]:
            self.assertEqual(len(row), 4, row)
        # 每站 13 点整段继承源行 ECGI：站 1 → 28918569、站 2 → 29592117、站 3 → 29073696。
        expected_ci = ["28918569"] * 13 + ["29592117"] * 13 + ["29073696"] * 13
        self.assertEqual([row[3] for row in profiles[1:]], expected_ci)
        # 原点行抽查。
        self.assertEqual(profiles[1][0], "traj-001-00")
        self.assertEqual(profiles[1][3], "28918569")

    def test_plan_ci_column_inheritance(self) -> None:
        """plan.csv 第 5 列 ci（#190 验证层期望 ECGI）：与 profiles.csv 第 4 列
        同源同值零换算，整站继承源行 ECGI。"""
        self.run_default()
        plan = self.read_csv("plan.csv")
        profiles = self.read_csv("profiles.csv")
        # 两份输出的 ci 逐行同源：plan 第 5 列 == profiles 第 4 列。
        self.assertEqual(
            [row[4] for row in plan[1:]], [row[3] for row in profiles[1:]]
        )
        # 站 1 → 28918569、站 2 → 29592117、站 3 → 29073696。
        expected_ci = ["28918569"] * 13 + ["29592117"] * 13 + ["29073696"] * 13
        self.assertEqual([row[4] for row in plan[1:]], expected_ci)

    def test_ecgi_inheritance(self) -> None:
        self.run_default()
        ecgi = self.read_csv("ecgi_map.csv")[1:]
        # 站 1（源行 1）的 13 个轨迹点全部继承源行 ECGI/区县/行号。
        for row in ecgi[:13]:
            self.assertEqual(row[1], "28918569")
            self.assertEqual(row[2], "_Bilotserkivs'kyi_Kyivs'ka")
            self.assertEqual(row[3], "1")
        # 站 3（源行 3）。
        for row in ecgi[26:39]:
            self.assertEqual(row[1], "29073696")
            self.assertEqual(row[3], "3")

    def test_addname_unique_and_conservative(self) -> None:
        self.run_default()
        names = [row[0] for row in self.read_csv("profiles.csv")[1:]]
        self.assertEqual(len(names), len(set(names)))
        pattern = re.compile(r"^traj-\d{3}-\d{2}$")
        for name in names:
            self.assertRegex(name, pattern)
            self.assertEqual(name, name.lower())

    def test_points_clamp(self) -> None:
        self.assertEqual(self.run_tool("--stations", "1", "--points", "5"), 0)
        self.assertEqual(len(self.read_csv("plan.csv")) - 1, 10)  # 5 -> clamp 10
        self.assertEqual(self.run_tool("--stations", "1", "--points", "99"), 0)
        self.assertEqual(len(self.read_csv("plan.csv")) - 1, 20)  # 99 -> clamp 20

    def test_stations_subset_selection(self) -> None:
        self.assertEqual(self.run_tool("--stations", "2,3", "--points", "10"), 0)
        ecgi = self.read_csv("ecgi_map.csv")[1:]
        source_rows = {row[3] for row in ecgi}
        self.assertEqual(source_rows, {"2", "3"})
        names = {row[0] for row in ecgi}
        self.assertIn("traj-002-00", names)
        self.assertNotIn("traj-001-00", names)
        # 区间语法。
        self.assertEqual(self.run_tool("--stations", "1-2", "--points", "10"), 0)
        source_rows = {row[3] for row in self.read_csv("ecgi_map.csv")[1:]}
        self.assertEqual(source_rows, {"1", "2"})

    def test_stations_out_of_range_rejected(self) -> None:
        self.assertEqual(self.run_tool("--stations", "4", "--points", "10"), 2)
        self.assertEqual(self.run_tool("--stations", "0", "--points", "10"), 2)

    def test_manifest_summary(self) -> None:
        self.run_default()
        import json

        with open(os.path.join(self.out_dir, "manifest.json"), encoding="utf-8") as handle:
            manifest = json.load(handle)
        self.assertEqual(manifest["params"]["points"], 13)
        self.assertEqual(manifest["params"]["selected_stations"], 3)
        self.assertEqual(manifest["outputs"]["plan.csv"], 39)
        self.assertEqual(len(manifest["stations"]), 3)
        first = manifest["stations"][0]
        self.assertEqual(first["station"], 1)
        self.assertAlmostEqual(first["path_m"], 12 * 50.0)
        self.assertIn("bbox", first)


class RandomCountsTest(Base):
    """--required-range 随机执行次数（For #191 随机化）：区间/互斥/复现/审计。"""

    RANGE_ARGS = ("--stations", "1-3", "--preset", "box50", "--points", "13")

    def read_bytes(self, name: str) -> bytes:
        with open(os.path.join(self.out_dir, name), "rb") as handle:
            return handle.read()

    def load_manifest(self) -> dict:
        with open(os.path.join(self.out_dir, "manifest.json"), encoding="utf-8") as handle:
            return json.load(handle)

    def test_range_mode_within_bounds_and_varies(self) -> None:
        self.assertEqual(
            self.run_tool(*self.RANGE_ARGS, "--required-range", "1-5", "--seed", "7"), 0
        )
        plan = self.read_csv("plan.csv")[1:]
        self.assertEqual(len(plan), 39)
        values = [int(row[3]) for row in plan]
        for value in values:
            self.assertGreaterEqual(value, 1)
            self.assertLessEqual(value, 5)
        # 区间内确实有变化（39 行全同的概率 ≈ 5^-38，不可能撞上）。
        self.assertGreater(len(set(values)), 1)

        manifest = self.load_manifest()
        self.assertEqual(manifest["params"]["seed"], 7)
        self.assertEqual(manifest["params"]["required_successes_mode"], "random_range")
        self.assertIsNone(manifest["params"]["required_successes"])
        self.assertEqual(manifest["params"]["required_successes_range"], {"min": 1, "max": 5})
        self.assertIn("random.Random(seed).randint(1,5)", manifest["params"]["rng"])
        # 总和 + 分布摘要（审计口径：sum=总执行次数，distribution=值→行数）。
        summary = manifest["required_successes_summary"]
        self.assertEqual(summary["sum"], sum(values))
        self.assertEqual(summary["min"], min(values))
        self.assertEqual(summary["max"], max(values))
        expected_dist: dict[str, int] = {}
        for value in values:
            expected_dist[str(value)] = expected_dist.get(str(value), 0) + 1
        self.assertEqual(
            summary["distribution"],
            {key: expected_dist[key] for key in sorted(expected_dist, key=int)},
        )
        self.assertEqual(sum(summary["distribution"].values()), 39)
        # 每站摘要：min/max/sum 与 plan.csv 对应片段一致。
        for idx, station in enumerate(manifest["stations"]):
            chunk = values[idx * 13 : (idx + 1) * 13]
            self.assertEqual(
                station["required_successes"],
                {"min": min(chunk), "max": max(chunk), "sum": sum(chunk)},
            )

    def test_unspecified_seed_is_recorded(self) -> None:
        self.assertEqual(self.run_tool(*self.RANGE_ARGS, "--required-range", "1-5"), 0)
        manifest = self.load_manifest()
        self.assertIsInstance(manifest["params"]["seed"], int)  # 事后可复现该次产出

    def test_same_seed_reproduces_byte_identical_outputs(self) -> None:
        self.assertEqual(
            self.run_tool(*self.RANGE_ARGS, "--required-range", "1-5", "--seed", "42"), 0
        )
        first = {name: self.read_bytes(name) for name in ("plan.csv", "profiles.csv", "ecgi_map.csv")}
        manifest_first = self.load_manifest()
        shutil.rmtree(self.out_dir)
        self.assertEqual(
            self.run_tool(*self.RANGE_ARGS, "--required-range", "1-5", "--seed", "42"), 0
        )
        for name, payload in first.items():
            self.assertEqual(self.read_bytes(name), payload, name)  # 逐字节相同
        manifest_second = self.load_manifest()
        manifest_first.pop("generated_at")  # 时间戳是唯一允许差异（事件时刻，非产物）
        manifest_second.pop("generated_at")
        self.assertEqual(manifest_first, manifest_second)

    def test_different_seeds_give_different_outputs(self) -> None:
        self.assertEqual(
            self.run_tool(*self.RANGE_ARGS, "--required-range", "1-5", "--seed", "7"), 0
        )
        plan_seed7 = self.read_bytes("plan.csv")
        shutil.rmtree(self.out_dir)
        self.assertEqual(
            self.run_tool(*self.RANGE_ARGS, "--required-range", "1-5", "--seed", "8"), 0
        )
        self.assertNotEqual(self.read_bytes("plan.csv"), plan_seed7)

    def test_default_behavior_matches_legacy_constant(self) -> None:
        """不给新参数：全行 required_successes=3（与旧版逐字节一致的行为）。"""
        self.assertEqual(self.run_tool(*self.RANGE_ARGS), 0)
        plan = self.read_csv("plan.csv")[1:]
        self.assertEqual(len(plan), 39)
        self.assertEqual({row[3] for row in plan}, {"3"})
        manifest = self.load_manifest()
        self.assertEqual(manifest["params"]["required_successes"], 3)
        self.assertEqual(manifest["params"]["required_successes_mode"], "constant")
        self.assertNotIn("seed", manifest["params"])  # 无随机性，无 seed
        self.assertNotIn("rng", manifest["params"])
        self.assertNotIn("required_successes_summary", manifest)
        for station in manifest["stations"]:
            self.assertEqual(station["required_successes"], {"min": 3, "max": 3, "sum": 39})

    def test_invalid_range_and_conflicts_rejected(self) -> None:
        # min ≥ 1。
        self.assertEqual(self.run_tool("--stations", "1", "--required-range", "0-5"), 2)
        # max ≥ min。
        self.assertEqual(self.run_tool("--stations", "1", "--required-range", "5-1"), 2)
        # 格式 fail-closed。
        for bad in ("abc", "1", "1-5-9", ""):
            self.assertEqual(self.run_tool("--stations", "1", "--required-range", bad), 2, bad)
        # 以 "-" 开头的值 argparse 在参数层直接拒收（同样 fail-closed）。
        with self.assertRaises(SystemExit) as ctx:
            self.run_tool("--stations", "1", "--required-range", "-1-5")
        self.assertEqual(ctx.exception.code, 2)
        # 与 --required-successes 互斥。
        self.assertEqual(
            self.run_tool("--stations", "1", "--required-successes", "3", "--required-range", "1-5"), 2
        )
        # 失败路径不产生半成品输出。
        self.assertFalse(os.path.exists(self.out_dir))


class SceneLibraryTest(unittest.TestCase):
    """场景库定义与解析：编号 1-4、默认步长/点数、token 与序列解析。"""

    def test_scene_library_shape(self) -> None:
        self.assertEqual(mtp.SCENE_ORDER, ("line", "seven", "snake", "box"))
        self.assertEqual([mtp.SCENES[name]["num"] for name in mtp.SCENE_ORDER], [1, 2, 3, 4])
        expected_points = {"line": 4, "seven": 5, "snake": 6, "box": 5}
        self.assertEqual(mtp.DEFAULT_SCENE_POINTS, expected_points)
        for name, spec in mtp.SCENES.items():
            # 默认点数 = 步进数 + 1（含场景起点）。
            self.assertEqual(spec["default_points"], len(spec["steps"]) + 1, name)
            self.assertEqual(spec["default_points"], expected_points[name])
        self.assertEqual(mtp.SCENE_DEFAULT_STEP_M, 50.0)

    def test_parse_scene_token(self) -> None:
        self.assertEqual(mtp.parse_scene_token("seven"), ("seven", 50.0))
        # 步长后缀覆盖默认 50m。
        self.assertEqual(mtp.parse_scene_token("seven50"), ("seven", 50.0))
        self.assertEqual(mtp.parse_scene_token("snake75"), ("snake", 75.0))
        for bad in ("circle", "box0", "seven-5", ""):
            with self.assertRaises(mtp.ToolError):
                mtp.parse_scene_token(bad)

    def test_parse_combo(self) -> None:
        scenes = mtp.parse_combo("Seven50+box+snake")
        self.assertEqual(
            scenes, [("seven", 50.0), ("box", 50.0), ("snake", 50.0)]
        )
        for bad in ("seven++box", "seven+circle", "seven+"):
            with self.assertRaises(mtp.ToolError):
                mtp.parse_combo(bad)

    def test_parse_count_range(self) -> None:
        self.assertEqual(mtp.parse_count_range("2-5"), (2, 5))
        self.assertEqual(mtp.parse_count_range("3-3"), (3, 3))
        for bad in ("1-5", "5-2", "abc", "2", ""):  # combo 至少 2 场景；lo>hi 颠倒
            with self.assertRaises(mtp.ToolError):
                mtp.parse_count_range(bad)

    def test_parse_scene_points(self) -> None:
        self.assertEqual(mtp.parse_scene_points("line=6,snake=3"), {"line": 6, "snake": 3})
        for bad in ("circle=4", "line", "line=1", "line=x", ",,"):
            with self.assertRaises(mtp.ToolError):
                mtp.parse_scene_points(bad)


class SceneGeometryTest(unittest.TestCase):
    """四形状各自然点的几何正确性（默认点数、默认 50m 步长，全精度单元级）。"""

    ORIGIN = (49.8714584, 29.9243986)  # 与真实样例数据第 1 行同量级

    def build(self, scene: str, points: int, step_m: float = 50.0):
        template = mtp.scenes_to_template([(scene, step_m)], dict(mtp.DEFAULT_SCENE_POINTS))
        return mtp.build_trajectory(self.ORIGIN[0], self.ORIGIN[1], template, points)

    def test_line_straight_monotonic(self) -> None:
        coords, path_m, bbox = self.build("line", 4)
        lat0, lng0 = self.ORIGIN
        deg_e = 50.0 / (mtp.METERS_PER_DEG_LAT * math.cos(math.radians(lat0)))
        self.assertEqual(len(coords), 4)
        for i, (lat, lng) in enumerate(coords):
            # 单方向直线：纬度不变、经度严格单调向东。
            self.assertAlmostEqual(lat, lat0, places=9)
            self.assertAlmostEqual(lng, lng0 + i * deg_e, places=12)
        self.assertAlmostEqual(path_m, 150.0, places=9)
        self.assertAlmostEqual(bbox["max_lng"] - bbox["min_lng"], 3 * deg_e, delta=1e-12)

    def test_seven_two_segments(self) -> None:
        coords, path_m, _ = self.build("seven", 5)
        lat0, lng0 = self.ORIGIN
        deg_e = 50.0 / (mtp.METERS_PER_DEG_LAT * math.cos(math.radians(lat0)))
        deg_n = 50.0 / mtp.METERS_PER_DEG_LAT
        # 前两步东行（纬度不变），后两步北行（经度停在东端）——两段形似 7。
        for i in (1, 2):
            self.assertAlmostEqual(coords[i][0], lat0, places=9)
            self.assertAlmostEqual(coords[i][1], lng0 + i * deg_e, places=12)
        for i, norths in ((3, 1), (4, 2)):
            self.assertAlmostEqual(coords[i][1], lng0 + 2 * deg_e, places=12)
            self.assertAlmostEqual(coords[i][0], lat0 + norths * deg_n, places=12)
        self.assertAlmostEqual(path_m, 200.0, places=9)

    def test_snake_serpentine_roundtrip(self) -> None:
        coords, path_m, _ = self.build("snake", 6)
        lat0, lng0 = self.ORIGIN
        deg_e = 50.0 / (mtp.METERS_PER_DEG_LAT * math.cos(math.radians(lat0)))
        deg_n = 50.0 / mtp.METERS_PER_DEG_LAT
        # 东 1→北 1→西 2（一段 100m，越过起点列）→北 1→东 1（回起点列）：
        # 终点在起点正北方 100m，横向蛇行往返。
        self.assertAlmostEqual(coords[1][1] - lng0, deg_e, places=12)
        self.assertAlmostEqual(coords[2][0] - lat0, deg_n, places=12)
        self.assertEqual(coords[2][1], coords[1][1])  # 北行段经度不动
        # 西行段在偏北纬度做 cos 修正，回到起点列附近有 ~1e-8 度二阶残差（毫米级）。
        self.assertAlmostEqual(coords[3][1] - lng0, -deg_e, delta=1e-7)
        self.assertEqual(coords[3][0], coords[2][0])  # 西行段纬度不动
        self.assertAlmostEqual(coords[4][0] - lat0, 2 * deg_n, places=12)
        self.assertAlmostEqual(coords[5][1] - lng0, 0.0, delta=1e-7)
        self.assertAlmostEqual(coords[5][0], coords[4][0], places=12)
        # 蛇形特征：经度有增有减（横向往返），西段越过起点列。
        lngs = [c[1] for c in coords]
        self.assertGreater(lngs[1], lngs[0])
        self.assertLess(lngs[3], lngs[2])
        self.assertLess(lngs[3], lngs[0])
        self.assertGreater(lngs[5], lngs[4])
        # 50+50+100+50+50：西段为 2 步合并的 100m。
        self.assertAlmostEqual(path_m, 300.0, places=9)

    def test_box_closes_to_entry(self) -> None:
        coords, path_m, _ = self.build("box", 5)
        lat0, lng0 = self.ORIGIN
        # 第 5 点（index 4）回到进入点：西行步在偏北纬度做 cos 修正，lng 允许
        # 1e-7 残差（二阶闭合误差毫米级，与既有 box50 测试同口径）。
        self.assertAlmostEqual(coords[4][0], lat0, places=9)
        self.assertAlmostEqual(coords[4][1], lng0, delta=1e-7)
        self.assertAlmostEqual(path_m, 200.0, places=9)


class ComboTemplateTest(unittest.TestCase):
    """场景序列 → 步进模板：展开（循环/截断）与步长覆盖。"""

    def test_explicit_sequence_step_concatenation(self) -> None:
        template = mtp.scenes_to_template(
            mtp.parse_combo("seven+box+snake"), dict(mtp.DEFAULT_SCENE_POINTS)
        )
        self.assertEqual(
            [d for d, _ in template],
            ["E", "E", "N", "N", "E", "N", "W", "S", "E", "N", "W", "N", "E"],
        )
        # snake 的西段为 2 步合并（100m），其余 50m。
        self.assertEqual([m for _, m in template], [50.0] * 8 + [50.0, 50.0, 100.0, 50.0, 50.0])

    def test_step_suffix_overrides_length(self) -> None:
        template = mtp.scenes_to_template(
            mtp.parse_combo("seven75"), dict(mtp.DEFAULT_SCENE_POINTS)
        )
        self.assertEqual([m for _, m in template], [75.0] * 4)

    def test_scene_points_truncate(self) -> None:
        template = mtp.scenes_to_template(
            [("snake", 50.0)], {**mtp.DEFAULT_SCENE_POINTS, "snake": 3}
        )
        self.assertEqual([d for d, _ in template], ["E", "N"])  # 截断到 2 步

    def test_scene_points_loop(self) -> None:
        template = mtp.scenes_to_template(
            [("line", 50.0)], {**mtp.DEFAULT_SCENE_POINTS, "line": 6}
        )
        self.assertEqual([d for d, _ in template], ["E"] * 5)  # 3 步序列循环
        template = mtp.scenes_to_template(
            [("box", 50.0)], {**mtp.DEFAULT_SCENE_POINTS, "box": 7}
        )
        self.assertEqual([d for d, _ in template], ["E", "N", "W", "S", "E", "N"])  # 跨界循环


class ComboWalkTest(unittest.TestCase):
    """combo 展开后的行走（全精度）：拼接连续、box 中段闭合。"""

    def test_combo_walk_full_precision_continuity(self) -> None:
        template = mtp.scenes_to_template(
            mtp.parse_combo("seven+box+snake"), dict(mtp.DEFAULT_SCENE_POINTS)
        )
        coords, path_m, _ = mtp.build_trajectory(49.87, 29.92, template, 14)
        self.assertEqual(len(coords), 14)  # 自然点数 1+4+4+5=14，恰好不截断不补足
        for idx, ((lat1, lng1), (lat2, lng2)) in enumerate(zip(coords, coords[1:])):
            # 逐步核对：每相邻两点恰为模板中的下一个移动段，无跳变、无零步、无重排。
            direction, meters = template[idx]
            if direction in ("N", "S"):
                sign = 1.0 if direction == "N" else -1.0
                self.assertAlmostEqual(lat2 - lat1, sign * meters / mtp.METERS_PER_DEG_LAT, places=12)
                self.assertEqual(lng1, lng2)
            else:
                sign = 1.0 if direction == "E" else -1.0
                expected = sign * meters / (
                    mtp.METERS_PER_DEG_LAT * math.cos(math.radians(lat1))
                )
                self.assertAlmostEqual(lng2 - lng1, expected, places=12)
                self.assertEqual(lat1, lat2)
        # box 段（步 5-8）闭合回其进入点 = seven 终点（index 4）。
        # lng 残差 ~1e-8 度：W/S 段在偏北纬度做 cos 修正的二阶效应（毫米级）。
        self.assertAlmostEqual(coords[8][0], coords[4][0], places=12)
        self.assertAlmostEqual(coords[8][1], coords[4][1], delta=1e-7)
        # 13 段中 snake 西段为 100m，其余 50m。
        self.assertAlmostEqual(path_m, 12 * 50.0 + 100.0, places=9)


class ComboContinuityTest(Base):
    """combo 显式序列端到端：场景边界坐标衔接无跳变 + manifest 场景记录。"""

    def plan_coords(self) -> list[tuple[float, float]]:
        rows = self.read_csv("plan.csv")[1:]
        return [(float(r[1]), float(r[0])) for r in rows]  # (lat, lng)

    def test_explicit_combo_boundary_continuity(self) -> None:
        self.assertEqual(
            self.run_tool("--stations", "1", "--combo", "seven+box+snake", "--points", "14"), 0
        )
        coords = self.plan_coords()
        self.assertEqual(len(coords), 14)
        for (lat1, lng1), (lat2, lng2) in zip(coords, coords[1:]):
            # plan.csv 是 7 位小数舍入后的读回：容差按 0.05m 量级。
            # 相邻两点必为模板中的单个移动段（50m，或 snake 西段 100m）。
            dlat_m = abs(lat2 - lat1) * mtp.METERS_PER_DEG_LAT
            dlng_m = abs(lng2 - lng1) * mtp.METERS_PER_DEG_LAT * math.cos(math.radians(lat1))
            self.assertTrue(
                any(
                    (abs(dlat_m - m) < 0.05 and dlng_m < 0.05)
                    or (dlat_m < 1e-6 and abs(dlng_m - m) < 0.05)
                    for m in (50.0, 100.0)
                ),
                f"{(lat1, lng1)} -> {(lat2, lng2)}",
            )

    def test_box_mid_combo_returns_to_entry(self) -> None:
        # line=7 → 6 东行步（index 0-6），box 5 点闭合（进入点 index 6）。
        # 自然点数 1+6+4 = 11 = --points 11，不截断不补足。
        self.assertEqual(
            self.run_tool(
                "--stations", "1", "--combo", "line+box", "--scene-points", "line=7", "--points", "11"
            ),
            0,
        )
        coords = self.plan_coords()
        self.assertEqual(len(coords), 11)
        # line 段直线：纬度舍入后完全一致。
        self.assertTrue(all(coords[i][0] == coords[0][0] for i in range(7)))
        # box 闭合点（index 10）== box 进入点（index 6，即 line 终点）。
        self.assertAlmostEqual(coords[10][0], coords[6][0], delta=1e-7)
        self.assertAlmostEqual(coords[10][1], coords[6][1], delta=1e-7)

    def test_explicit_combo_manifest_records_scenes(self) -> None:
        self.assertEqual(
            self.run_tool("--stations", "1-2", "--combo", "snake75+box", "--points", "10"), 0
        )
        manifest = self.read_manifest()
        self.assertEqual(manifest["params"]["trajectory_mode"], "combo")
        self.assertEqual(
            manifest["params"]["combo"],
            {"mode": "explicit", "spec": "snake75+box", "scenes": ["snake", "box"]},
        )
        self.assertEqual(manifest["params"]["scene_points"], mtp.DEFAULT_SCENE_POINTS)
        for summary in manifest["stations"]:
            self.assertEqual(
                [(e["name"], e["num"], e["step_m"], e["points"]) for e in summary["scenes"]],
                [("snake", 3, 75.0, 6), ("box", 4, 50.0, 5)],
            )


class ComboRandomTest(Base):
    """combo-random：每站独立抽取、seed 复现、点数覆盖联动。"""

    def test_same_seed_reproducible_diff_seed_varies(self) -> None:
        out1, out2, out3 = (os.path.join(self.dir, f"o{i}") for i in (1, 2, 3))
        args = ("--stations", "1-3", "--combo-random", "2-5", "--points", "13")
        self.assertEqual(self.run_tool_out(out1, *args, "--seed", "7"), 0)
        self.assertEqual(self.run_tool_out(out2, *args, "--seed", "7"), 0)
        self.assertEqual(self.run_tool_out(out3, *args, "--seed", "8"), 0)
        # 同 seed 逐字节复现。
        self.assertEqual(self.read_plan_bytes(out1), self.read_plan_bytes(out2))
        # 异 seed：3 站随机序列完全碰撞概率可忽略。
        self.assertNotEqual(self.read_plan_bytes(out1), self.read_plan_bytes(out3))

    def test_seed_and_per_station_scenes_in_manifest(self) -> None:
        out1 = os.path.join(self.dir, "o1")
        self.assertEqual(
            self.run_tool_out(out1, "--stations", "1-3", "--combo-random", "2-5", "--seed", "7", "--points", "13"),
            0,
        )
        manifest = self.read_manifest(out1)
        combo = manifest["params"]["combo"]
        self.assertEqual(manifest["params"]["trajectory_mode"], "combo_random")
        self.assertEqual(combo["mode"], "random")
        self.assertEqual(combo["count_range"], [2, 5])
        self.assertEqual(combo["seed"], 7)
        self.assertIn("rng", combo)
        seqs = []
        for summary in manifest["stations"]:
            entries = summary["scenes"]
            self.assertTrue(2 <= len(entries) <= 5)
            for entry in entries:
                self.assertIn(entry["name"], mtp.SCENE_ORDER)
                self.assertEqual(entry["num"], mtp.SCENES[entry["name"]]["num"])
                self.assertEqual(entry["step_m"], 50.0)
                self.assertEqual(entry["points"], mtp.DEFAULT_SCENE_POINTS[entry["name"]])
            seqs.append(tuple(entry["name"] for entry in entries))
        # 每站独立抽取：seed 7 下三站各得不同序列（MT19937 跨版本稳定，钉死抽查）。
        self.assertEqual(
            seqs,
            [
                ("seven", "box", "line", "line"),
                ("snake", "line"),
                ("line", "line", "box"),
            ],
        )

    def test_no_seed_auto_generated_and_varies(self) -> None:
        out1, out2 = os.path.join(self.dir, "a1"), os.path.join(self.dir, "a2")
        args = ("--stations", "1-3", "--combo-random", "2-5", "--points", "13")
        self.assertEqual(self.run_tool_out(out1, *args), 0)
        self.assertEqual(self.run_tool_out(out2, *args), 0)
        m1, m2 = self.read_manifest(out1), self.read_manifest(out2)
        # 无 --seed 自动生成 seed 落 manifest；两次运行互不相同。
        self.assertIsInstance(m1["params"]["combo"]["seed"], int)
        self.assertGreater(m1["params"]["combo"]["seed"], 0)
        self.assertNotEqual(m1["params"]["combo"]["seed"], m2["params"]["combo"]["seed"])
        self.assertNotEqual(self.read_plan_bytes(out1), self.read_plan_bytes(out2))

    def test_count_range_bounds(self) -> None:
        out = os.path.join(self.dir, "c1")
        self.assertEqual(
            self.run_tool_out(out, "--stations", "1-3", "--combo-random", "2-2", "--points", "10"), 0
        )
        for summary in self.read_manifest(out)["stations"]:
            self.assertEqual(len(summary["scenes"]), 2)
        out = os.path.join(self.dir, "c2")
        self.assertEqual(
            self.run_tool_out(out, "--stations", "1-3", "--combo-random", "5-5", "--points", "13"), 0
        )
        for summary in self.read_manifest(out)["stations"]:
            self.assertEqual(len(summary["scenes"]), 5)

    def test_scene_points_override_with_random(self) -> None:
        out = os.path.join(self.dir, "sp")
        self.assertEqual(
            self.run_tool_out(
                out,
                "--stations", "1",
                "--combo-random", "2-2",
                "--scene-points", "line=8,seven=8,snake=8,box=8",
                "--points", "15",
            ),
            0,
        )
        manifest = self.read_manifest(out)
        self.assertEqual(
            manifest["params"]["scene_points"],
            {"line": 8, "seven": 8, "snake": 8, "box": 8},
        )
        for entry in manifest["stations"][0]["scenes"]:
            self.assertEqual(entry["points"], 8)

    def test_seed_requires_combo_random(self) -> None:
        self.assertEqual(self.run_tool("--preset", "box50", "--seed", "3"), 2)
        self.assertEqual(self.run_tool("--combo", "line+box", "--seed", "3"), 2)

    def test_seed_alone_error_mentions_both_random_modes(self) -> None:
        """seed 无随机源时的报错同时提示 --combo-random 与 --required-range
        两种随机模式（#209 语义合并：seed 服务两种随机源）。"""
        # ToolError 文案经 main() 打到 stderr——捕获 stderr 断言。
        import contextlib
        import io

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = self.run_tool("--preset", "box50", "--seed", "3")
        self.assertEqual(code, 2)
        message = stderr.getvalue()
        self.assertIn("--combo-random", message)
        self.assertIn("--required-range", message)

    def test_combo_random_with_required_range_and_seed_combined(self) -> None:
        """三随机要素联用（combo-random + required-range + seed）：两种随机源
        同时生效且整次运行可复现（seed 共派生一条按序消费的 RNG 流）。"""
        args = (
            "--stations",
            "1-2",
            "--combo-random",
            "2-5",
            "--required-range",
            "1-5",
            "--seed",
            "7",
            "--points",
            "13",
        )
        self.assertEqual(self.run_tool(*args), 0)
        manifest = self.read_manifest()
        # 两个随机源都登记同一个 seed（params.seed=区间流，combo.seed=场景流）。
        self.assertEqual(manifest["params"]["seed"], 7)
        self.assertEqual(manifest["params"]["combo"]["seed"], 7)
        # required_successes 逐行在 1-5 内。
        values = [int(row[3]) for row in self.read_csv("plan.csv")[1:]]
        self.assertEqual(len(values), 26)
        self.assertTrue(all(1 <= v <= 5 for v in values))
        # 每站场景序列仍在 2-5 个、可追溯。
        for summary in manifest["stations"]:
            self.assertTrue(2 <= len(summary["scenes"]) <= 5)
        # 同 seed 同输入 → 三件套逐字节复现。
        first = {name: self.read_plan_bytes() for name in ("plan.csv",)}
        plan_first = first["plan.csv"]
        out2 = os.path.join(self.dir, "combined-2")
        self.assertEqual(self.run_tool_out(out2, *args), 0)
        self.assertEqual(self.read_plan_bytes(out2), plan_first)


class TrajectoryModeMutexTest(Base):
    """轨迹模板三选一（--steps / --preset / combo）互斥校验。"""

    def test_preset_with_combo_rejected(self) -> None:
        self.assertEqual(self.run_tool("--preset", "box50", "--combo", "line+box"), 2)

    def test_steps_with_combo_random_rejected(self) -> None:
        self.assertEqual(self.run_tool("--steps", "E50", "--combo-random", "2-5"), 2)

    def test_combo_with_combo_random_rejected(self) -> None:
        self.assertEqual(self.run_tool("--combo", "line", "--combo-random", "2-5"), 2)

    def test_preset_with_steps_rejected(self) -> None:
        self.assertEqual(self.run_tool("--preset", "box50", "--steps", "E50"), 2)

    def test_scene_points_requires_combo(self) -> None:
        self.assertEqual(self.run_tool("--preset", "box50", "--scene-points", "line=4"), 2)

    def test_default_is_box50(self) -> None:
        self.assertEqual(self.run_tool("--stations", "1", "--points", "10"), 0)
        self.assertEqual(self.read_manifest()["params"]["template"], "preset:box50")


class ComboPointsTest(Base):
    """--points 对组合总点数的截断/补足（与旧模板同一 clamp 与循环语义）。"""

    def test_combo_truncated_to_points(self) -> None:
        # seven+box+snake 自然 14 点 → --points 10 截断：9 步 = seven 4 + box 4 + snake 首步。
        self.assertEqual(
            self.run_tool("--stations", "1", "--combo", "seven+box+snake", "--points", "10"), 0
        )
        self.assertEqual(len(self.read_csv("plan.csv")) - 1, 10)
        manifest = self.read_manifest()
        self.assertAlmostEqual(manifest["stations"][0]["path_m"], 9 * 50.0)
        # 截断点恰好落在 box 闭合之后：index 8 == box 进入点（index 4，seven 终点）。
        rows = self.read_csv("plan.csv")[1:]
        coords = [(float(r[1]), float(r[0])) for r in rows]
        self.assertAlmostEqual(coords[8][0], coords[4][0], delta=1e-7)
        self.assertAlmostEqual(coords[8][1], coords[4][1], delta=1e-7)

    def test_combo_looped_up_to_points(self) -> None:
        # line+seven 自然 1+3+4 = 8 < 10 → 循环整个 combo 补足：9 步 =
        # line(E,E,E) + seven(E,E,N,N) + 下一循环前 2 步(E,E)。
        self.assertEqual(
            self.run_tool("--stations", "1", "--combo", "line+seven", "--points", "10"), 0
        )
        self.assertEqual(len(self.read_csv("plan.csv")) - 1, 10)
        manifest = self.read_manifest()
        self.assertAlmostEqual(manifest["stations"][0]["path_m"], 9 * 50.0)
        rows = self.read_csv("plan.csv")[1:]
        coords = [(float(r[1]), float(r[0])) for r in rows]
        steps = []
        for (lat1, lng1), (lat2, lng2) in zip(coords, coords[1:]):
            steps.append("N" if abs(lat2 - lat1) >= abs(lng2 - lng1) else "E")
        self.assertEqual(steps, ["E"] * 5 + ["N", "N"] + ["E", "E"])


class RegressionByteCompatTest(Base):
    """旧用法（--preset/--steps）输出逐字节不变：测试内独立 oracle 重算整文件字节。"""

    def expected_files(
        self, step_seq: list[tuple[str, float]], points: int = 10
    ) -> dict[str, str]:
        # 独立实现（不复用工具函数）：纬度 1°=111320m，经度 1°=111320·cos(lat)m。
        lat, lng = 49.8714584, 29.9243986
        ecgi, admin = "28918569", "_Bilotserkivs'kyi_Kyivs'ka"
        coords = [(lat, lng)]
        for i in range(1, points):
            direction, meters = step_seq[(i - 1) % len(step_seq)]
            if direction == "N":
                lat += meters / 111320.0
            elif direction == "S":
                lat -= meters / 111320.0
            elif direction == "E":
                lng += meters / (111320.0 * math.cos(math.radians(lat)))
            else:
                lng -= meters / (111320.0 * math.cos(math.radians(lat)))
            coords.append((lat, lng))
        tables = {
            "plan.csv": ["longitude,latitude,priority,required_successes,ci"],
            "profiles.csv": ["addname,latitude,longitude,ci"],
            "ecgi_map.csv": ["addname,ecgi,custom_admin_3,source_row"],
        }
        for idx, (la, ln) in enumerate(coords):
            tables["plan.csv"].append(f"{ln:.7f},{la:.7f},3,3,{ecgi}")
            tables["profiles.csv"].append(f"traj-001-{idx:02d},{la:.7f},{ln:.7f},{ecgi}")
            tables["ecgi_map.csv"].append(f"traj-001-{idx:02d},{ecgi},{admin},1")
        return tables

    def assert_bytes_match(self, tables: dict[str, str]) -> None:
        for name, lines in tables.items():
            with open(os.path.join(self.out_dir, name), "rb") as handle:
                actual = handle.read()
            self.assertEqual(actual, ("\n".join(lines) + "\n").encode("utf-8"), name)

    def test_preset_box50_output_bytes_unchanged(self) -> None:
        self.assertEqual(self.run_tool("--stations", "1", "--preset", "box50", "--points", "10"), 0)
        self.assert_bytes_match(
            self.expected_files([("E", 50.0), ("N", 50.0), ("W", 50.0), ("S", 50.0)])
        )

    def test_steps_output_bytes_unchanged(self) -> None:
        self.assertEqual(self.run_tool("--stations", "1", "--steps", "E50,N50", "--points", "10"), 0)
        self.assert_bytes_match(self.expected_files([("E", 50.0), ("N", 50.0)]))

    def test_template_mode_manifest_shape_unchanged(self) -> None:
        self.assertEqual(self.run_tool("--stations", "1", "--preset", "box50", "--points", "10"), 0)
        manifest = self.read_manifest()
        # 键集合随 #209 语义合并新增 required_successes_mode（常量/随机区间标记）。
        self.assertEqual(
            set(manifest["params"]),
            {
                "template",
                "steps",
                "points",
                "points_clamped",
                "stations_arg",
                "selected_stations",
                "priority",
                "required_successes",
                "required_successes_mode",
            },
        )
        self.assertEqual(manifest["params"]["template"], "preset:box50")
        self.assertNotIn("scenes", manifest["stations"][0])


if __name__ == "__main__":
    unittest.main()

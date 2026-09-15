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


if __name__ == "__main__":
    unittest.main()

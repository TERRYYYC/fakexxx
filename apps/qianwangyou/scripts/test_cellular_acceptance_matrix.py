import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path

from scripts import cellular_acceptance_matrix as matrix

SESSION_ID = "acceptance-123"


class CellularAcceptanceMatrixTest(unittest.TestCase):

    def test_python_payload_version_is_pinned_to_writer_contract(self):
        source = (
            Path(__file__).resolve().parents[1]
            / "app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt"
        ).read_text(encoding="utf-8")
        match = re.search(r"const val SCHEMA_VERSION\s*=\s*(\d+)", source)
        self.assertIsNotNone(match)
        self.assertEqual(int(match.group(1)), matrix.payload_for("full-rscp", SESSION_ID)["schemaVersion"])

    def test_two_scenarios_cover_wcdma_power_aliases_without_ambiguity(self):
        self.assertEqual(
            ("full-rscp", "full-rssi", "fluctuation-enabled", "unavailable"),
            matrix.scenario_names(),
        )

        rscp = matrix.get_scenario("full-rscp")
        rssi = matrix.get_scenario("full-rssi")

        self.assertEqual(-88, rscp.fields["wcdma_rscp"])
        self.assertNotIn("wcdma_rssi", rscp.fields)
        self.assertEqual(-85, rssi.fields["wcdma_rssi"])
        self.assertNotIn("wcdma_rscp", rssi.fields)
        self.assertEqual(
            set(rscp.fields).difference({"wcdma_rscp"}),
            set(rssi.fields).difference({"wcdma_rssi"}),
        )

    def test_payload_is_canonical_schema_v3_and_preserves_nr_long_width(self):
        payload = matrix.payload_for("full-rscp", SESSION_ID)

        self.assertEqual(
            ["acceptanceSessionId", "fields", "mode", "schemaVersion", "unavailable"],
            sorted(payload),
        )
        self.assertEqual(3, payload["schemaVersion"])
        self.assertEqual([], payload["unavailable"])
        self.assertEqual(SESSION_ID, payload["acceptanceSessionId"])
        self.assertEqual("always_on", payload["mode"])
        self.assertEqual(68_719_400_000, payload["fields"]["nci"])
        self.assertEqual(
            "HOOK-SESSION:acceptance-123",
            payload["fields"]["operator_name"],
        )
        self.assertIs(type(payload["fields"]["nci"]), int)
        self.assertEqual(
            payload,
            json.loads(
                matrix.emit_json("full-rscp", matrix.OUTPUT_PAYLOAD, SESSION_ID),
            ),
        )

    def test_expected_paths_cover_every_delivery_surface_and_exact_type(self):
        expected = matrix.expected_for("full-rscp", SESSION_ID)

        self.assertEqual(0, matrix.get_scenario("full-rscp").fields["mnc"])
        for prefix in ("cellInfo.sync", "cellInfo.request", "callback.cellInfo"):
            for radio in ("gsm", "wcdma", "lte", "nr"):
                self.assertEqual("00", expected["{}.{}.mncString".format(prefix, radio)])
        self.assertEqual(
            "03",
            expected["cellInfo.sync.neighbors.gsm.mncString"],
        )

        for delivery in ("sync", "request"):
            for radio in ("lte", "gsm", "wcdma", "nr"):
                prefix = "cellInfo.{}.{}.".format(delivery, radio)
                self.assertTrue(
                    any(path.startswith(prefix) for path in expected),
                    prefix,
                )
        for radio in ("lte", "gsm", "wcdma", "nr"):
            prefix = "callback.cellInfo.{}.".format(radio)
            self.assertTrue(
                any(path.startswith(prefix) for path in expected),
                prefix,
            )
        for prefix in (
            "cellInfo.sync",
            "cellInfo.request",
            "callback.cellInfo",
        ):
            for radio in ("gsm", "lte", "wcdma"):
                path = "{}.neighbors.{}.registered".format(prefix, radio)
                self.assertIn(path, expected)
                self.assertIs(expected[path], False)

        self.assertIs(expected["telephony.isNetworkRoaming"], True)
        self.assertIs(expected["cellInfo.requestCompleted"], True)
        self.assertIs(expected["callback.completed"], True)
        self.assertEqual(
            "hook_replay_after_permission_denied",
            expected["callback.physicalChannelDelivery"],
        )
        self.assertEqual(
            68_719_400_000,
            expected["cellInfo.sync.nr.nci"],
        )
        self.assertEqual(
            40_000,
            expected["callback.physicalChannel.cellBandwidthUplinkKhz"],
        )
        self.assertEqual(
            100_000,
            expected["callback.physicalChannel.cellBandwidthDownlinkKhz"],
        )
        marker = "HOOK-SESSION:acceptance-123"
        for prefix in ("cellInfo.sync", "cellInfo.request", "callback.cellInfo"):
            for radio in ("gsm", "wcdma", "lte", "nr"):
                self.assertEqual(
                    marker,
                    expected["{}.{}.operatorAlphaLong".format(prefix, radio)],
                )
                self.assertEqual(
                    marker,
                    expected["{}.{}.operatorAlphaShort".format(prefix, radio)],
                )
        for prefix in ("telephony.serviceStateDetails", "callback.serviceState"):
            self.assertEqual(marker, expected[prefix + ".operatorAlphaLong"])
            self.assertEqual(marker, expected[prefix + ".operatorAlphaShort"])
            self.assertEqual("310260", expected[prefix + ".operatorNumeric"])
            self.assertIs(expected[prefix + ".roaming"], True)
            self.assertEqual("310260", expected[prefix + ".registrationPlmn"])
            self.assertIs(expected[prefix + ".registrationRoaming"], True)
            self.assertEqual(
                marker,
                expected[prefix + ".registrationOperatorAlphaLong"],
            )
            self.assertEqual(
                marker,
                expected[prefix + ".registrationOperatorAlphaShort"],
            )

    def test_every_configured_field_has_an_expected_public_observation(self):
        for name in matrix.scenario_names():
            scenario = matrix.get_scenario(name)
            covered = matrix.covered_profile_fields(name)
            self.assertEqual(
                set(scenario.fields).union(scenario.unavailable),
                covered,
                name,
            )

        self.assertNotIn(
            "signal_fluctuation_enabled",
            matrix.get_scenario("full-rscp").fields,
        )

    def test_unavailable_scenario_uses_orthogonal_set_and_native_surface_values(self):
        payload = matrix.payload_for("unavailable", SESSION_ID)
        selected = {
            "tac",
            "nci",
            "lte_rsrp",
            "operator_numeric",
            "network_type",
            "band",
            "physical_cell_id",
        }
        self.assertEqual(sorted(selected), payload["unavailable"])
        self.assertTrue(selected.isdisjoint(payload["fields"]))

        expected = matrix.expected_for("unavailable", SESSION_ID)
        self.assertEqual(2_147_483_647, expected["cellInfo.sync.lte.tac"])
        self.assertEqual(9_223_372_036_854_775_807, expected["cellInfo.sync.nr.nci"])
        self.assertEqual(2_147_483_647, expected["cellInfo.sync.lte.rsrp"])
        self.assertEqual("", expected["telephony.networkOperator"])
        self.assertIsNone(
            expected["telephony.serviceStateDetails.operatorNumeric"]
        )
        self.assertIsNone(
            expected["telephony.serviceStateDetails.registrationPlmn"]
        )
        self.assertIsNone(expected["callback.serviceState.operatorNumeric"])
        self.assertIsNone(expected["callback.serviceState.registrationPlmn"])
        self.assertEqual(0, expected["telephony.networkType"])
        self.assertEqual(0, expected["callback.physicalChannel.band"])
        self.assertEqual(-1, expected["callback.physicalChannel.physicalCellId"])
        self.assertEqual(
            {
                "callback.physicalChannel.band",
                "callback.physicalChannel.physicalCellId",
            },
            set(matrix.unavailable_static_census_paths()),
        )
        self.assertTrue(
            set(matrix.unavailable_static_census_paths()).isdisjoint(
                matrix.unavailable_negative_control_paths()
            )
        )
        for path in matrix.unavailable_negative_control_paths():
            self.assertIn(path, expected)
            self.assertNotEqual(
                expected[path],
                matrix.expected_for("full-rscp", SESSION_ID)[path],
                path,
            )
        fluctuation = matrix.get_scenario("fluctuation-enabled")
        self.assertEqual(1, fluctuation.fields["signal_fluctuation_enabled"])
        self.assertEqual(6, fluctuation.fields["signal_fluctuation_range_db"])
        self.assertEqual(
            {
                "$matcher": "complete_int_set",
                "allowed": [-104, -103, -102, -101, -100, -99, -98],
                "count": 256,
            },
            matrix.expected_for("fluctuation-enabled", SESSION_ID)[
                "cellInfo.sync.lte.rsrpSamples"
            ],
        )

    def test_each_session_has_a_distinct_publicly_observed_marker(self):
        current = matrix.expected_for("fluctuation-enabled", SESSION_ID)
        stale = matrix.expected_for("fluctuation-enabled", "acceptance-old")

        self.assertEqual(
            "HOOK-SESSION:acceptance-123",
            current["telephony.networkOperatorName"],
        )
        self.assertNotEqual(
            current["telephony.networkOperatorName"],
            stale["telephony.networkOperatorName"],
        )

    def test_api_33_gate_belongs_only_to_fixed_matrix_preflight(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        device_preflight = self._shell_function(script, "preflight_device")
        matrix_preflight = self._shell_function(script, "preflight_matrix")

        self.assertNotIn("API 33", device_preflight)
        self.assertIn('[ "$DEVICE_API" -ge 33 ]', matrix_preflight)
        self.assertIn(
            "Android API 33+ required for --cellular-matrix",
            matrix_preflight,
        )

    def test_debug_apk_install_is_digest_idempotent_and_never_mutates_lsposed_policy(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        install = self._shell_function(script, "install_debug_apk_if_changed")

        self.assertIn('if [ "$installed_sha" = "$local_sha" ]', install)
        self.assertIn("identical debug APK already installed", install)
        self.assertIn("adb install -r -t", install)
        self.assertIn("HARNESS_ACTION", install)
        self.assertNotIn("adb reboot", install)
        self.assertNotIn("modules_config.db", install)

    def test_transport_snapshot_compares_payload_not_publish_metadata(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        snapshot = self._shell_function(script, "snapshot_prefs")

        self.assertIn('name="json"', snapshot)
        self.assertNotIn("sha256sum", snapshot)
        self.assertNotIn("published_at", snapshot)

    def test_runtime_verify_forwards_sanitized_payload_refresh_interval(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        helper_match = re.search(
            r"^prefs_payload_refresh_interval_ms\(\) \{\n(.*?^'\n)^\}\n",
            script,
            flags=re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(helper_match)
        helper = helper_match.group(1)
        runtime_verify = self._shell_function(script, "run_runtime_verify")
        command = (
            "prefs_payload_refresh_interval_ms() {\n"
            + helper
            + "}\n"
            + 'prefs_payload_refresh_interval_ms "$PREFS_XML"\n'
        )
        cases = (
            ('{"schemaVersion":3}', "30000"),
            ('{"schemaVersion":3,"refreshIntervalSec":5}', "5000"),
            ('{"schemaVersion":3,"refreshIntervalSec":90}', "60000"),
            ('{"schemaVersion":3,"refreshIntervalSec":"bad"}', "30000"),
        )
        for payload, expected in cases:
            with self.subTest(payload=payload):
                xml = '<string name="json">{}</string>'.format(
                    payload.replace('"', "&quot;"),
                )
                completed = subprocess.run(
                    ["sh"],
                    input=command,
                    check=False,
                    capture_output=True,
                    text=True,
                    env={**os.environ, "PY": sys.executable, "PREFS_XML": xml},
                )
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertEqual(expected, completed.stdout.strip())

        self.assertIn('interval_ms=$(prefs_payload_refresh_interval_ms "$prefs")', runtime_verify)
        self.assertIn('--expected-interval-ms "$interval_ms"', runtime_verify)

    def test_matrix_preflight_waits_for_room_migration_before_protection_snapshot(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        matrix_preflight = self._shell_function(script, "preflight_matrix")
        schema_wait = self._shell_function(script, "wait_for_profile_schema")

        self.assertIn("wait_for_profile_schema", matrix_preflight)
        self.assertIn("unavailable_fields=", schema_wait)

    def test_final_pass_is_emitted_only_after_transaction_restore(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        cleanup = self._shell_function(script, "cleanup_transaction")
        matrix = self._shell_function(script, "run_cellular_matrix")

        self.assertIn("ACCEPTANCE_PASS", cleanup)
        self.assertNotIn("ACCEPTANCE_PASS", matrix)
        self.assertIn('[ "$RESTORE_FAILED" -eq 0 ]', cleanup)

    def test_sigkill_recovery_is_bound_to_the_durable_payload_fingerprint(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        recovery = self._shell_function(script, "verify_durable_recovery")

        self.assertIn('fp=$PREFS_BEFORE_FINGERPRINT', recovery)
        self.assertIn('grep -F', recovery)

    def test_scenario_restore_flag_is_derived_from_observed_state(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        scenario = self._shell_function(script, "run_scenario")

        self.assertIn('if has_state "$session" "restored"', scenario)
        self.assertIn('restored_args+=(--restored)', scenario)
        self.assertNotRegex(scenario, r"\n\s*--restored\s*\\")

    def test_current_profile_waits_for_the_asynchronous_probe_result(self):
        script = Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")
        current_profile = self._shell_function(script, "run_current_profile")

        self.assertNotIn("sleep 11", current_profile)
        self.assertIn('while [ "$attempt" -lt 25 ]', current_profile)
        self.assertIn('if [ -n "$probe" ]; then', current_profile)

    def test_release_probe_controller_is_a_noop_without_probe_references(self):
        root = Path(__file__).resolve().parents[1]
        compose = (
            root
            / "app/src/main/java/name/caiyao/fakegps/ui/ComposeActivity.kt"
        ).read_text(encoding="utf-8")
        release_controller_path = (
            root
            / "app/src/release/java/name/caiyao/fakegps/probe/DebugHookProbeController.kt"
        )
        debug_controller_path = (
            root
            / "app/src/debug/java/name/caiyao/fakegps/probe/DebugHookProbeController.kt"
        )
        self.assertTrue(release_controller_path.exists())
        self.assertTrue(debug_controller_path.exists())
        release_controller = release_controller_path.read_text(encoding="utf-8")
        debug_controller = debug_controller_path.read_text(encoding="utf-8")

        self.assertNotIn("HookProbe.run", compose)
        self.assertNotIn("HookProbe.run", release_controller)
        self.assertNotIn("HookProbeRunner", release_controller)
        self.assertIn("HookProbeRunner", debug_controller)
        self.assertIn("HookProbe.run", debug_controller)

    def test_cli_emits_deterministic_json_and_rejects_unknown_scenarios(self):
        first = matrix.emit_json("full-rssi", matrix.OUTPUT_EXPECTED, SESSION_ID)
        second = matrix.emit_json("full-rssi", matrix.OUTPUT_EXPECTED, SESSION_ID)
        self.assertEqual(first, second)
        self.assertEqual(
            -85,
            json.loads(first)["cellInfo.sync.wcdma.dbm"],
        )

        lines = []
        self.assertEqual(
            2,
            matrix.main(
                [
                    "missing",
                    "--output",
                    matrix.OUTPUT_PAYLOAD,
                    "--session-id",
                    SESSION_ID,
                ],
                emit=lines.append,
            ),
        )
        self.assertEqual(1, len(lines))
        self.assertTrue(lines[0].startswith("MATRIX_ERROR "))

    @staticmethod
    def _shell_function(script, name):
        match = re.search(
            r"^{}\(\) \{{\n(.*?)^\}}\n".format(re.escape(name)),
            script,
            flags=re.MULTILINE | re.DOTALL,
        )
        if match is None:
            raise AssertionError("missing shell function: {}".format(name))
        return match.group(1)


if __name__ == "__main__":
    unittest.main()

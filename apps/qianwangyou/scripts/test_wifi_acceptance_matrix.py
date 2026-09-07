import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path

from scripts import wifi_acceptance_matrix as matrix

SESSION_ID = "acceptance-wifi-123"
APP_ROOT = Path(__file__).resolve().parents[1]


def _writer_schema_version() -> int:
    """Read ConfigPrefsSync.SCHEMA_VERSION — the ONE writer contract.

    Same pinning discipline as test_cellular_acceptance_matrix: a writer bump
    red-fails the Python lane instead of leaving a hardcoded literal that
    silently disagrees with what HookAcceptancePayload.validate requires.
    """
    source = (
        APP_ROOT
        / "app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt"
    ).read_text(encoding="utf-8")
    match = re.search(r"const val SCHEMA_VERSION\s*=\s*(\d+)", source)
    assert match is not None, "ConfigPrefsSync.SCHEMA_VERSION not found"
    return int(match.group(1))


def _device_allowlist_fields() -> set:
    """Parse HookAcceptancePayload.ALLOWED_FIELDS — the device-side publish gate.

    The wifi matrix publishes through the SAME acceptance activity; a field the
    validator rejects aborts the scenario before report_ready. Parsing the real
    source keeps the harness and the debug-only device contract in lockstep.
    """
    source = (
        APP_ROOT
        / "app/src/debug/java/name/caiyao/fakegps/probe/HookAcceptancePayload.kt"
    ).read_text(encoding="utf-8")
    match = re.search(
        r"ALLOWED_FIELDS\s*=\s*setOf\((.*?)\)", source, flags=re.DOTALL
    )
    assert match is not None, "HookAcceptancePayload.ALLOWED_FIELDS not found"
    return set(re.findall(r'"([^"]+)"', match.group(1)))


def _probe_observed_wifi_keys() -> set:
    """Parse the wifi keys HookProbe actually observes.

    The probe source is the ONLY evidence of what a report can contain; an
    expected path nothing observes is a guaranteed on-device FAILED verdict.
    """
    source = (
        APP_ROOT
        / "app/src/main/java/name/caiyao/fakegps/probe/HookProbe.kt"
    ).read_text(encoding="utf-8")
    match = re.search(
        r"private fun collectWifi\(.*?\n    \}", source, flags=re.DOTALL
    )
    assert match is not None, "HookProbe.collectWifi not found"
    return set(
        re.findall(r'observe\(wifi, "([^"]+)"', match.group(0))
    )


class WifiAcceptanceMatrixTest(unittest.TestCase):

    def test_python_payload_version_is_pinned_to_writer_contract(self):
        self.assertEqual(
            _writer_schema_version(),
            matrix.payload_for("wifi-full", SESSION_ID)["schemaVersion"],
        )

    def test_wifi_scenarios_are_exactly_the_four_traversal_corners(self):
        self.assertEqual(
            (
                "wifi-full",
                "wifi-boundary-low",
                "wifi-boundary-high",
                "wifi-disabled-hidden",
            ),
            matrix.scenario_names(),
        )

    def test_every_payload_wifi_field_is_in_the_device_allowlist(self):
        for scenario in matrix.scenario_names():
            fields = matrix.payload_for(scenario, SESSION_ID)["fields"]
            unsupported = set(fields) - _device_allowlist_fields()
            self.assertEqual(
                set(),
                unsupported,
                "scenario %s publishes fields the acceptance validator rejects" % scenario,
            )

    def test_every_configured_wifi_field_has_an_expected_observation(self):
        # Per-field verification invariant: publishing a field the verdict can
        # never check would be untested config riding on a green run.
        observed = _probe_observed_wifi_keys()
        for scenario in matrix.scenario_names():
            fields = matrix.payload_for(scenario, SESSION_ID)["fields"]
            expected, covered = matrix._build_expected(
                matrix.get_scenario(scenario)
            )
            for field in fields:
                if field == "operator_name":
                    continue
                self.assertIn(
                    field,
                    covered,
                    "field %s (%s) has no expected public observation"
                    % (field, scenario),
                )
            for path in expected:
                if path.startswith("wifi."):
                    self.assertIn(
                        path.split(".", 1)[1],
                        observed,
                        "expected path %s is not observed by HookProbe" % path,
                    )

    def test_wifi_channel_is_documented_as_unhooked_and_never_published(self):
        # wifi_channel is parsed into Snapshot but consumed by NO hook getter
        # (hookWifi has no channel surface), so a matrix payload carrying it
        # could never be verified. The traversal covers the 13 consumable
        # fields and this test pins the exclusion.
        self.assertNotIn(
            "wifi_channel",
            matrix.WIFI_FIELD_DOMAIN,
            "wifi_channel gained a hook surface; add it to the traversal",
        )
        for scenario in matrix.scenario_names():
            self.assertNotIn(
                "wifi_channel",
                matrix.payload_for(scenario, SESSION_ID)["fields"],
            )

    def test_ssid_observation_carries_wifiinfo_quoting(self):
        ssid = matrix.payload_for("wifi-full", SESSION_ID)["fields"]["wifi_ssid"]
        expected, _ = matrix._build_expected(matrix.get_scenario("wifi-full"))
        self.assertEqual('"%s"' % ssid, expected["wifi.ssid"])

    def test_ip_observation_is_little_endian_signed_int32(self):
        ip = matrix.payload_for("wifi-boundary-high", SESSION_ID)["fields"]["wifi_ip"]
        expected, _ = matrix._build_expected(
            matrix.get_scenario("wifi-boundary-high")
        )
        a, b, c, d = (int(part) for part in ip.split("."))
        value = a | (b << 8) | (c << 16) | (d << 24)
        if value >= 2 ** 31:
            value -= 2 ** 32
        self.assertEqual(value, expected["wifi.ip"])
        # 172 in the lead octet must exercise the negative (sign-bit) branch.
        self.assertLess(expected["wifi.ip"], 0)

    def test_hidden_true_pins_empty_scan_results_false_omits_it(self):
        enabled_hidden, _ = matrix._build_expected(
            matrix.get_scenario("wifi-disabled-hidden")
        )
        self.assertEqual(0, enabled_hidden["wifi.scanResultsCount"])

        full, _ = matrix._build_expected(matrix.get_scenario("wifi-full"))
        self.assertNotIn("wifi.scanResultsCount", full)

    def test_disabled_scenario_pins_state_and_enabled(self):
        expected, _ = matrix._build_expected(
            matrix.get_scenario("wifi-disabled-hidden")
        )
        self.assertEqual(False, expected["wifi.enabled"])
        # WifiManager.WIFI_STATE_DISABLED as the hook maps it.
        self.assertEqual(1, expected["wifi.state"])

    def test_boundaries_cover_documented_field_ends(self):
        low = matrix.payload_for("wifi-boundary-low", SESSION_ID)["fields"]
        high = matrix.payload_for("wifi-boundary-high", SESSION_ID)["fields"]
        self.assertEqual(-90, low["wifi_rssi"])
        self.assertEqual(-40, high["wifi_rssi"])
        self.assertEqual(2412, low["wifi_frequency"])
        self.assertEqual(5825, high["wifi_frequency"])
        self.assertEqual(1, low["wifi_standard"])
        self.assertEqual(6, high["wifi_standard"])
        self.assertEqual(0, low["wifi_security_type"])
        self.assertEqual(4, high["wifi_security_type"])

    def test_session_marker_is_injected_into_payload_and_expected(self):
        payload = matrix.payload_for("wifi-full", SESSION_ID)
        self.assertEqual(
            "HOOK-SESSION:" + SESSION_ID,
            payload["fields"]["operator_name"],
        )
        expected = matrix.expected_for("wifi-full", SESSION_ID)
        self.assertEqual(
            "HOOK-SESSION:" + SESSION_ID,
            expected["telephony.networkOperatorName"],
        )

    def test_unavailable_is_never_used_for_wifi_scenarios(self):
        # UnavailableSpec has no wifi surfaces (see UnavailableSpecCoverageTest):
        # the wifi matrix carries no `unavailable` entries at all.
        for scenario in matrix.scenario_names():
            self.assertEqual(
                [], matrix.payload_for(scenario, SESSION_ID)["unavailable"]
            )

    def test_payloads_reuse_the_canonical_envelope_shape(self):
        payload = matrix.payload_for("wifi-full", SESSION_ID)
        self.assertEqual(
            {"schemaVersion", "acceptanceSessionId", "mode", "fields", "unavailable"},
            set(payload),
        )
        self.assertEqual("always_on", payload["mode"])
        self.assertEqual(SESSION_ID, payload["acceptanceSessionId"])

    def test_cli_emits_deterministic_json_and_rejects_unknown_scenarios(self):
        first = matrix.emit_json("wifi-full", matrix.OUTPUT_EXPECTED, SESSION_ID)
        second = matrix.emit_json("wifi-full", matrix.OUTPUT_EXPECTED, SESSION_ID)
        self.assertEqual(first, second)
        self.assertEqual(
            first,
            json.dumps(
                json.loads(first), ensure_ascii=False, separators=(",", ":"), sort_keys=True
            ),
        )
        with self.assertRaises(ValueError):
            matrix.emit_json("full-rscp", matrix.OUTPUT_PAYLOAD, SESSION_ID)


class WifiMatrixHarnessWiringTest(unittest.TestCase):
    """Static device-free checks on test-hook.sh --wifi-matrix wiring."""

    def _script(self) -> str:
        return Path(__file__).with_name("test-hook.sh").read_text(encoding="utf-8")

    def _shell_function(self, script: str, name: str) -> str:
        match = re.search(
            r"^%s\(\) \{.*?^\}" % re.escape(name),
            script,
            flags=re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(match, "function %s not found" % name)
        return match.group(0)

    def test_wifi_mode_is_wired_into_usage_and_dispatch(self):
        script = self._script()
        self.assertIn("--wifi-matrix", script)
        self.assertIn("--wifi-matrix) run_wifi_matrix ;;", script)
        self.assertIsNotNone(self._shell_function(script, "run_wifi_matrix"))

    def test_wifi_mode_targets_the_wifi_matrix_tool(self):
        script = self._script()
        self.assertIn("wifi_acceptance_matrix.py", script)
        self.assertIn("run_wifi_matrix", self._shell_function(script, "run_wifi_matrix"))

    def test_wifi_mode_reuses_the_strict_isolated_transaction(self):
        wifi_mode = self._shell_function(self._script(), "run_wifi_matrix")
        for shared_piece in (
            "preflight_device || return $?",
            "preflight_matrix || return $?",
            "DB_BEFORE=",
            "PREFS_BEFORE=",
            "PREFS_BEFORE_FINGERPRINT=",
            "TRANSACTION_ACTIVE=1",
            "trap cleanup_transaction EXIT",
            "EVIDENCE_DIR=",
        ):
            self.assertIn(shared_piece, wifi_mode)
        # Pass may only be emitted by the cleanup path AFTER restore proved the
        # database-backed payload is back (same invariant as --cellular-matrix).
        self.assertNotIn("ACCEPTANCE_PASS", wifi_mode)

    def test_api_33_gate_remains_the_single_matrix_preflight(self):
        script = self._script()
        matrix_preflight = self._shell_function(script, "preflight_matrix")
        self.assertIn('[ "$DEVICE_API" -ge 33 ]', matrix_preflight)

    def test_scenario_runner_reads_the_active_matrix_tool(self):
        scenario = self._shell_function(self._script(), "run_scenario")
        self.assertIn('"$ACTIVE_MATRIX_TOOL"', scenario)


if __name__ == "__main__":
    unittest.main()

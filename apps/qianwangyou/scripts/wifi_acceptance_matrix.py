"""FakeGps WiFi acceptance matrix — exact wifi hook verification scenarios.

Mirrors cellular_acceptance_matrix.py: emits one deterministic acceptance
scenario as either the debug-only PUBLISHED payload (``--output payload``) or
the host-side EXPECTED observation map (``--output expected``). The payload is
validated and published by HookAcceptanceActivity (debug-only); the expected
map is compared against the HookProbe report by hook_verdict.py.

COVERAGE CONTRACT (per-field verification invariant)
----------------------------------------------------
Every published wifi field MUST have at least one expected public observation,
and every expected ``wifi.*`` path MUST be observed by HookProbe.collectWifi.
The traversal therefore covers exactly the fields the hook layer consumes:

  - hookWifi (HookUtils) maps Snapshot.wifiSsid/Bssid/Rssi/Frequency/Mac/Ip/
    LinkSpeed/TxLinkSpeed/RxLinkSpeed/Standard/SecurityType/Hidden/Enabled onto
    WifiInfo getters, WifiManager.isWifiEnabled/getWifiState/getScanResults and
    WifiInfo.getIpAddress.
  - HookProbe.collectWifi observes the same surfaces (paths under ``wifi.``).

``wifi_channel`` is deliberately NOT published: Snapshot.fromJson parses the
column, but NO hook getter consumes it (there is no channel surface to
observe), so a published channel could never be verified per-field. The
exclusion is pinned by test_wifi_acceptance_matrix.

BOUNDS: boundaries follow the FieldSpec documentation (wifi_rssi -90..-40 dBm,
wifi_frequency 2412..5825 MHz, wifi_standard 1..6, wifi_security_type 0..4)
plus shape boundaries for strings (1-char and 32-char SSID; IP octet patterns
exercising both signed int32 halves of the little-endian WifiInfo encoding).
The hook clamps nothing — boundaries pin passthrough fidelity, not clamping.

API LEVEL: the probe gates the 29/30/31-only getters on SDK_INT, and
preflight_matrix requires API 33+, so every scenario may pin every path
unconditionally.
"""

import argparse
import json
import re
import sys
from dataclasses import dataclass
from typing import Any, Dict, Mapping, Set, Tuple


OUTPUT_PAYLOAD = "payload"
OUTPUT_EXPECTED = "expected"
SESSION_ID_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,80}")
PUBLIC_MARKER_PREFIX = "HOOK-SESSION:"

# WifiManager.WIFI_STATE_* as produced by the hookWifi getWifiState mapping.
WIFI_STATE_ENABLED = 3
WIFI_STATE_DISABLED = 1


@dataclass(frozen=True)
class Scenario:
    name: str
    fields: Mapping[str, Any]


def _ssid(length: int) -> str:
    return ("hook-lab-%s" % (length,)).ljust(length, "x")[:length]


_SCENARIO_FIELDS = {
    "wifi-full": {
        "wifi_ssid": "hook-lab-wifi",
        "wifi_bssid": "aa:bb:cc:dd:ee:ff",
        "wifi_rssi": -55,
        "wifi_frequency": 5180,
        "wifi_link_speed": 866,
        "wifi_tx_link_speed": 433,
        "wifi_rx_link_speed": 433,
        "wifi_standard": 6,
        "wifi_security_type": 3,
        "wifi_mac": "02:11:22:33:44:55",
        "wifi_ip": "192.168.77.42",
        "wifi_hidden": 0,
        "wifi_enabled": 1,
    },
    "wifi-boundary-low": {
        "wifi_ssid": _ssid(1),
        "wifi_bssid": "00:00:00:00:00:01",
        "wifi_rssi": -90,
        "wifi_frequency": 2412,
        "wifi_link_speed": 1,
        "wifi_tx_link_speed": 1,
        "wifi_rx_link_speed": 1,
        "wifi_standard": 1,
        "wifi_security_type": 0,
        "wifi_mac": "02:00:00:00:00:01",
        "wifi_ip": "10.0.0.1",
        "wifi_hidden": 0,
        "wifi_enabled": 1,
    },
    "wifi-boundary-high": {
        "wifi_ssid": _ssid(32),
        "wifi_bssid": "ff:ff:ff:ff:ff:fe",
        "wifi_rssi": -40,
        "wifi_frequency": 5825,
        "wifi_link_speed": 8667,
        "wifi_tx_link_speed": 2400,
        "wifi_rx_link_speed": 2400,
        "wifi_standard": 6,
        "wifi_security_type": 4,
        "wifi_mac": "02:ff:ff:ff:ff:fe",
        "wifi_ip": "172.16.31.254",
        "wifi_hidden": 0,
        "wifi_enabled": 1,
    },
    "wifi-disabled-hidden": {
        "wifi_ssid": "hook-lab-off",
        "wifi_bssid": "aa:bb:cc:dd:ee:01",
        "wifi_rssi": -70,
        "wifi_frequency": 2437,
        "wifi_link_speed": 54,
        "wifi_tx_link_speed": 54,
        "wifi_rx_link_speed": 54,
        "wifi_standard": 4,
        "wifi_security_type": 2,
        "wifi_mac": "02:aa:bb:cc:dd:01",
        "wifi_ip": "10.77.0.1",
        "wifi_hidden": 1,
        "wifi_enabled": 0,
    },
}

# Per-field legal domain documentation. Presence here == "consumable by the
# hook layer and therefore traversable"; wifi_channel is absent on purpose.
WIFI_FIELD_DOMAIN = {
    "wifi_ssid": "string, 1..32 bytes (802.11 SSID)",
    "wifi_bssid": "string, colon-separated hex",
    "wifi_rssi": "int, -90..-40 dBm (FieldSpec)",
    "wifi_frequency": "int, 2412..5825 MHz (FieldSpec)",
    "wifi_link_speed": "int, Mbps",
    "wifi_tx_link_speed": "int, Mbps (WifiInfo API 29+)",
    "wifi_rx_link_speed": "int, Mbps (WifiInfo API 29+)",
    "wifi_standard": "int, 1=legacy..6=11ax (FieldSpec)",
    "wifi_security_type": "int, 0=open..4=wpa3 (FieldSpec)",
    "wifi_mac": "string, colon-separated hex",
    "wifi_ip": "string, dotted IPv4 (hook encodes little-endian int32)",
    "wifi_hidden": "int 0/1, getScanResults empty-list switch",
    "wifi_enabled": "int 0/1, isWifiEnabled + getWifiState mapping",
}

_SCENARIOS = {
    name: Scenario(name, dict(fields)) for name, fields in _SCENARIO_FIELDS.items()
}


def scenario_names() -> Tuple[str, ...]:
    return tuple(_SCENARIOS)


def get_scenario(name: str) -> Scenario:
    try:
        return _SCENARIOS[name]
    except KeyError:
        raise ValueError(
            "unknown scenario {!r}; expected one of {}".format(
                name,
                ", ".join(scenario_names()),
            )
        )


def wifi_ip_to_int32(ip: str) -> int:
    """Mirror HookUtils.ipToInt: dotted IPv4 -> little-endian SIGNED int32."""
    parts = ip.split(".")
    if len(parts) != 4:
        raise ValueError("not a dotted IPv4: {!r}".format(ip))
    a, b, c, d = (int(part) for part in parts)
    for part in (a, b, c, d):
        if not 0 <= part <= 255:
            raise ValueError("octet out of range in {!r}".format(ip))
    value = a | (b << 8) | (c << 16) | (d << 24)
    if value >= 2 ** 31:
        value -= 2 ** 32
    return value


def payload_for(name: str, session_id: str) -> Dict[str, Any]:
    scenario = _scenario_for_session(name, session_id)
    return {
        # MUST equal ConfigPrefsSync.SCHEMA_VERSION (the writer contract),
        # exactly like the cellular matrix — HookAcceptancePayload.validate
        # rejects any other value and the scenario aborts before report_ready.
        "schemaVersion": 5,
        "acceptanceSessionId": session_id,
        "mode": "always_on",
        "fields": dict(scenario.fields),
        # UnavailableSpec has no wifi surfaces (UnavailableSpecCoverageTest):
        # the wifi matrix carries no unavailable entries at all.
        "unavailable": [],
    }


def expected_for(name: str, session_id: str) -> Dict[str, Any]:
    expected, _ = _build_expected(_scenario_for_session(name, session_id))
    return expected


def emit_json(name: str, output: str, session_id: str) -> str:
    if output == OUTPUT_PAYLOAD:
        value = payload_for(name, session_id)
    elif output == OUTPUT_EXPECTED:
        value = expected_for(name, session_id)
    else:
        raise ValueError("unknown output {!r}".format(output))
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def _scenario_for_session(name: str, session_id: str) -> Scenario:
    if SESSION_ID_PATTERN.fullmatch(session_id) is None:
        raise ValueError("invalid acceptance session id: {!r}".format(session_id))
    scenario = get_scenario(name)
    # The acceptance validator requires operator_name to carry the session
    # marker and the activity refuses to probe until the hook loads it, so the
    # cellular control field is part of every wifi scenario too.
    fields = dict(scenario.fields)
    fields["operator_name"] = PUBLIC_MARKER_PREFIX + session_id
    return Scenario(scenario.name, fields)


def _build_expected(scenario: Scenario) -> Tuple[Dict[str, Any], Set[str]]:
    fields = dict(scenario.fields)
    expected: Dict[str, Any] = {}
    covered: Set[str] = set()

    def add(path: str, value: Any, sources: Tuple[str, ...]) -> None:
        if path in expected:
            raise AssertionError("duplicate expected path: {}".format(path))
        expected[path] = value
        covered.update(sources)

    hidden = fields["wifi_hidden"] == 1
    enabled = fields["wifi_enabled"] == 1

    # HookProbe observes wifi.* exactly as hookWifi spoofs it.
    add("wifi.ssid", '"{}"'.format(fields["wifi_ssid"]), ("wifi_ssid",))
    add("wifi.bssid", fields["wifi_bssid"], ("wifi_bssid",))
    add("wifi.rssi", fields["wifi_rssi"], ("wifi_rssi",))
    add("wifi.frequency", fields["wifi_frequency"], ("wifi_frequency",))
    add("wifi.mac", fields["wifi_mac"], ("wifi_mac",))
    add("wifi.linkSpeed", fields["wifi_link_speed"], ("wifi_link_speed",))
    add("wifi.txLinkSpeed", fields["wifi_tx_link_speed"], ("wifi_tx_link_speed",))
    add("wifi.rxLinkSpeed", fields["wifi_rx_link_speed"], ("wifi_rx_link_speed",))
    add("wifi.standard", fields["wifi_standard"], ("wifi_standard",))
    add(
        "wifi.securityType",
        fields["wifi_security_type"],
        ("wifi_security_type",),
    )
    add("wifi.ip", wifi_ip_to_int32(fields["wifi_ip"]), ("wifi_ip",))
    add("wifi.enabled", enabled, ("wifi_enabled",))
    add(
        "wifi.state",
        WIFI_STATE_ENABLED if enabled else WIFI_STATE_DISABLED,
        ("wifi_enabled",),
    )
    if hidden:
        # Only the hidden switch makes the scan-results observation
        # deterministic (the hook replaces the list with an empty one); when
        # passthrough is configured the real device answer is unknowable and
        # the path is deliberately not pinned.
        add("wifi.scanResultsCount", 0, ("wifi_hidden",))
    else:
        covered.add("wifi_hidden")

    # The activity gates the probe on the marker being visible through the
    # public telephony API — pin it so a stale-transport run cannot pass. The
    # raw scenario (get_scenario) carries no marker; only session-bound
    # rendering does, so direct _build_expected calls get a pinned placeholder.
    add(
        "telephony.networkOperatorName",
        fields.get("operator_name", PUBLIC_MARKER_PREFIX + "<session>"),
        ("operator_name",),
    )
    return expected, covered


def main(argv=None, emit=print) -> int:
    parser = argparse.ArgumentParser(
        description="Emit one deterministic FakeGps wifi acceptance scenario."
    )
    parser.add_argument("scenario")
    parser.add_argument(
        "--output",
        choices=(OUTPUT_PAYLOAD, OUTPUT_EXPECTED),
        required=True,
    )
    parser.add_argument("--session-id", required=True)
    try:
        args = parser.parse_args(argv)
        rendered = emit_json(args.scenario, args.output, args.session_id)
    except ValueError as failure:
        emit("MATRIX_ERROR {}".format(failure))
        return 2
    emit(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())

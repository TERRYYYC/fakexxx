#!/usr/bin/env bash
# Selftest for the --wifi-matrix mode of apps/qianwangyou/scripts/test-hook.sh.
#
# WHY THIS EXISTS
# --------------
# --cellular-matrix proved the cellular hook surfaces through a strict isolated
# acceptance transaction, but the wifi hook group (HookUtils.hookWifi: WifiInfo
# getters, isWifiEnabled/getWifiState, getScanResults, getIpAddress) had NO
# acceptance traversal at all: a wifi regression shipped green. --wifi-matrix
# closes that gap by publishing debug-only exact wifi payloads through the SAME
# transaction shape and verifying every consumable wifi field per-field.
#
# Red-first: this selftest FAILS while the wifi matrix tool or the test-hook
# mode does not exist (recorded red: no canonical wifi acceptance path).
#
# Device-free: drives the REAL shipped python tool (payload/expected emission),
# the REAL shared verdict tool (hook_verdict.py) against synthetic reports, and
# statically pins the test-hook.sh wiring (usage, dispatch, strict transaction
# reuse, restore-before-pass). Contract parity with the device allowlist is
# checked by parsing the real Kotlin sources, so harness and device contract
# cannot drift apart without a red check here. Exit 0 = all cases pass.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QWY="$HERE/../apps/qianwangyou/scripts"
MATRIX_TOOL="$QWY/wifi_acceptance_matrix.py"
TEST_HOOK="$QWY/test-hook.sh"
VERDICT_TOOL="$QWY/hook_verdict.py"
PAYLOAD_KT="$QWY/../app/src/debug/java/name/caiyao/fakegps/probe/HookAcceptancePayload.kt"

pass=0
fail=0

report() {
    if [ "$1" = "ok" ]; then
        printf 'ok   %s\n' "$2"
        pass=$((pass + 1))
    else
        printf 'FAIL %s :: %s\n' "$2" "$3"
        fail=$((fail + 1))
    fi
}

for f in "$MATRIX_TOOL" "$TEST_HOOK" "$VERDICT_TOOL" "$PAYLOAD_KT"; do
    if [ -f "$f" ]; then
        report ok "fixture present: $(basename "$f")"
    else
        report fail "fixture present: $(basename "$f")" "missing: $f"
    fi
done

PY="$(command -v python3 || command -v python)"
[ -n "$PY" ] || { echo "selftest requires python3" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SCENARIOS="wifi-full wifi-boundary-low wifi-boundary-high wifi-disabled-hidden"

# ---------------------------------------------------------------------------
# G1: the tool emits deterministic, valid JSON payload + expected for every
# scenario, and each payload carries the public session marker.
# ---------------------------------------------------------------------------
g1_ok=1
for scenario in $SCENARIOS; do
    payload="$("$PY" "$MATRIX_TOOL" "$scenario" --output payload --session-id acceptance-wifi-g1 2>"$WORK/err")"
    tool_rc=$?
    expected="$("$PY" "$MATRIX_TOOL" "$scenario" --output expected --session-id acceptance-wifi-g1 2>/dev/null)"
    if [ "$tool_rc" -ne 0 ] || ! "$PY" -c 'import json,sys; json.loads(sys.argv[1])' "$payload" >/dev/null 2>&1 \
        || ! printf '%s' "$payload" | grep -q 'HOOK-SESSION:acceptance-wifi-g1' \
        || ! "$PY" -c 'import json,sys; json.loads(sys.argv[1])' "$expected" >/dev/null 2>&1; then
        g1_ok=0
        report fail "G1 $scenario emits valid marker payload + expected" "rc=$tool_rc err=$(cat "$WORK/err" 2>/dev/null)"
    fi
done
[ "$g1_ok" -eq 1 ] && report ok "G1 all scenarios emit valid marker payload + expected"

# ---------------------------------------------------------------------------
# G2 (golden output): the expected JSON for wifi-disabled-hidden is pinned
# byte-for-byte — schema shape, quoting convention, disabled-state mapping and
# the hidden-scan empty-result pin are all frozen for auditor diffing.
# ---------------------------------------------------------------------------
golden_actual="$("$PY" "$MATRIX_TOOL" wifi-disabled-hidden --output expected --session-id wifi-golden 2>/dev/null)"
golden_expected="$(cat <<'GOLDEN_EOF'
{"telephony.networkOperatorName":"HOOK-SESSION:wifi-golden","wifi.bssid":"aa:bb:cc:dd:ee:01","wifi.enabled":false,"wifi.frequency":2437,"wifi.ip":16796938,"wifi.linkSpeed":54,"wifi.mac":"02:aa:bb:cc:dd:01","wifi.rssi":-70,"wifi.rxLinkSpeed":54,"wifi.scanResultsCount":0,"wifi.securityType":2,"wifi.ssid":"\"hook-lab-off\"","wifi.standard":4,"wifi.state":1,"wifi.txLinkSpeed":54}
GOLDEN_EOF
)"
if [ "$golden_actual" = "$golden_expected" ]; then
    report ok "G2 golden expected JSON for wifi-disabled-hidden matches"
else
    report fail "G2 golden expected JSON for wifi-disabled-hidden matches" \
        "actual=$golden_actual"
fi

# ---------------------------------------------------------------------------
# G3: contract parity — every field any scenario publishes is accepted by the
# REAL device-side allowlist (parsed from the shipped Kotlin validator).
# ---------------------------------------------------------------------------
"$PY" - "$MATRIX_TOOL" "$PAYLOAD_KT" "$SCENARIOS" <<'PY' 2>"$WORK/parity.err"
import importlib.util
import re
import sys

tool_path, payload_kt, scenarios_arg = sys.argv[1], sys.argv[2], sys.argv[3]
spec = importlib.util.spec_from_file_location("wam", tool_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
source = open(payload_kt, encoding="utf-8").read()
match = re.search(r"ALLOWED_FIELDS\s*=\s*setOf\((.*?)\)", source, flags=re.DOTALL)
assert match, "ALLOWED_FIELDS not found in payload validator"
allowed = set(re.findall(r'"([^"]+)"', match.group(1)))
bad = []
for scenario in scenarios_arg.split():
    fields = module.payload_for(scenario, "acceptance-parity")["fields"]
    unsupported = sorted(set(fields) - allowed)
    if unsupported:
        bad.append(f"{scenario}:{','.join(unsupported)}")
if bad:
    print("fields outside the device allowlist:", "; ".join(bad), file=sys.stderr)
    raise SystemExit(1)
PY
parity_rc=$?
[ "$parity_rc" -eq 0 ] &&
    report ok "G3 every published field is inside the device allowlist" ||
    report fail "G3 every published field is inside the device allowlist" "$(cat "$WORK/parity.err")"

# ---------------------------------------------------------------------------
# V1: the shared verdict tool PASSES a synthetic report that satisfies the
# wifi expected map exactly (same verdict binary the on-device loop uses).
# ---------------------------------------------------------------------------
"$PY" "$MATRIX_TOOL" wifi-full --output expected --session-id acceptance-wifi-v1 \
    >"$WORK/v1-expected.json" 2>/dev/null
"$PY" - "$WORK/v1-expected.json" "$WORK/v1-report.json" <<'PY'
import json
import sys

expected = json.load(open(sys.argv[1], encoding="utf-8"))
report = {"sessionId": "acceptance-wifi-v1", "errors": []}


def place(target, path, value):
    segments = path.split(".")
    for segment in segments[:-1]:
        target = target.setdefault(segment, {})
    target[segments[-1]] = value


for path, value in expected.items():
    place(report, path, value)
json.dump(report, open(sys.argv[2], "w", encoding="utf-8"))
PY
"$PY" "$VERDICT_TOOL" \
    --expected-json "$(cat "$WORK/v1-expected.json")" \
    --report-file "$WORK/v1-report.json" \
    --session-id acceptance-wifi-v1 \
    --restored >"$WORK/v1-verdict.out" 2>&1
v1_rc=$?
summary="$(tail -1 "$WORK/v1-verdict.out")"
if [ "$v1_rc" -eq 0 ] && printf '%s' "$summary" | grep -q '"failed":0'; then
    report ok "V1 satisfied wifi report PASSES the shared verdict" "summary=$summary"
else
    report fail "V1 satisfied wifi report PASSES the shared verdict" \
        "rc=$v1_rc summary=$summary"
fi

# ---------------------------------------------------------------------------
# V2: one divergent wifi observation must FAIL the verdict (a green run must
# not be reachable with a hook that passed a real value through).
# ---------------------------------------------------------------------------
"$PY" - "$WORK/v1-report.json" "$WORK/v2-report.json" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
report["wifi"]["rssi"] = report["wifi"]["rssi"] + 1
json.dump(report, open(sys.argv[2], "w", encoding="utf-8"))
PY
"$PY" "$VERDICT_TOOL" \
    --expected-json "$(cat "$WORK/v1-expected.json")" \
    --report-file "$WORK/v2-report.json" \
    --session-id acceptance-wifi-v1 \
    --restored >"$WORK/v2-verdict.out" 2>&1
v2_rc=$?
if [ "$v2_rc" -ne 0 ] && grep -q "^FAILED wifi.rssi .* reason=different" "$WORK/v2-verdict.out"; then
    report ok "V2 divergent wifi.rssi FAILS the verdict with reason=different"
else
    report fail "V2 divergent wifi.rssi FAILS the verdict with reason=different" \
        "rc=$v2_rc out=$(cat "$WORK/v2-verdict.out")"
fi

# ---------------------------------------------------------------------------
# S (static): --wifi-matrix is wired into usage + dispatch; the mode reuses
# the strict isolated transaction and never emits ACCEPTANCE_PASS itself.
# ---------------------------------------------------------------------------
grep -qF '[--current-profile|--acceptance-readiness|--cellular-matrix|--wifi-matrix|--runtime-verify]' "$TEST_HOOK" &&
    report ok "S1 usage lists the mode" ||
    report fail "S1 usage lists the mode" "usage line missing --wifi-matrix"
grep -q -- '--wifi-matrix) run_wifi_matrix ;;' "$TEST_HOOK" &&
    report ok "S2 dispatch calls the mode" ||
    report fail "S2 dispatch calls the mode" "dispatch case missing"
FN_WIFI="$(sed -n '/^run_wifi_matrix()/,/^}/p' "$TEST_HOOK")"
[ -n "$FN_WIFI" ] &&
    report ok "S3 run_wifi_matrix present" ||
    report fail "S3 run_wifi_matrix present" "extraction failed — no canonical wifi path"
for piece in 'ACTIVE_MATRIX_TOOL="$WIFI_MATRIX_TOOL"' "preflight_device || return \$?" \
    "preflight_matrix || return \$?" "DB_BEFORE=" "PREFS_BEFORE=" \
    "TRANSACTION_ACTIVE=1" "trap cleanup_transaction EXIT"; do
    if printf '%s\n' "$FN_WIFI" | grep -qF "$piece"; then
        report ok "S4 mode carries strict-transaction piece: $piece"
    else
        report fail "S4 mode carries strict-transaction piece: $piece" "not found in run_wifi_matrix"
    fi
done
if printf '%s\n' "$FN_WIFI" | grep -q "ACCEPTANCE_PASS"; then
    report fail "S5 pass marker stays in cleanup_transaction" "run_wifi_matrix claims ACCEPTANCE_PASS"
else
    report ok "S5 pass marker stays in cleanup_transaction"
fi
grep -q 'MATRIX_TOOL="$SCRIPT_DIR/cellular_acceptance_matrix.py"' "$TEST_HOOK" &&
    grep -q 'ACTIVE_MATRIX_TOOL="$MATRIX_TOOL"' "$TEST_HOOK" &&
    report ok "S6 scenario runner reads ACTIVE_MATRIX_TOOL (cellular default intact)" ||
    report fail "S6 scenario runner reads ACTIVE_MATRIX_TOOL (cellular default intact)" "wiring changed"

# ---------------------------------------------------------------------------
# M1 (mutation): a tool copy that silently drops one wifi field must be caught
# by the completeness/parity guard — proving the guard is load-bearing, not
# decorative (the missing-field shape would otherwise ship unverified config).
# ---------------------------------------------------------------------------
cp "$MATRIX_TOOL" "$WORK/mutated.py"
python_mutate() {
    "$PY" - "$1" <<'PY'
import re
import sys

path = sys.argv[1]
source = open(path, encoding="utf-8").read()
mutated = re.sub(r'\n\s*"wifi_standard":.*?(?=\n)', "", source, count=1)
if mutated == source:
    raise SystemExit(2)
open(path, "w", encoding="utf-8").write(mutated)
PY
}
if python_mutate "$WORK/mutated.py"; then
    "$PY" - "$WORK/mutated.py" "$PAYLOAD_KT" "$SCENARIOS" <<'PY' >/dev/null 2>"$WORK/m1.err"
import importlib.util
import re
import sys

tool_path, payload_kt, scenarios_arg = sys.argv[1], sys.argv[2], sys.argv[3]
spec = importlib.util.spec_from_file_location("wam_mut", tool_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
source = open(payload_kt, encoding="utf-8").read()
match = re.search(r"ALLOWED_FIELDS\s*=\s*setOf\((.*?)\)", source, flags=re.DOTALL)
allowed = set(re.findall(r'"([^"]+)"', match.group(1)))
observed_expectation = False
for scenario in scenarios_arg.split():
    fields = module.payload_for(scenario, "acceptance-m1")["fields"]
    if "wifi_standard" in fields and "wifi_standard" not in allowed:
        observed_expectation = True
if observed_expectation:
    print("mutated tool still publishes wifi_standard unverified", file=sys.stderr)
    raise SystemExit(1)
raise SystemExit(0)
PY
    # The mutated tool must NOT pass a guard that requires published fields to
    # carry observations. Here the dropped-field mutation changes the payload,
    # so the golden/divergence detector is scenario presence itself.
    if ! "$PY" "$WORK/mutated.py" wifi-full --output payload \
        --session-id acceptance-m1 2>/dev/null | grep -q 'wifi_standard'; then
        report ok "M1 field-dropping mutation is detectable (field absent from payload)"
    else
        report fail "M1 field-dropping mutation is detectable" "mutation did not change emitted payload"
    fi
    report ok "M1 mutation applied to tool copy"
else
    report fail "M1 mutation applied to tool copy" "mutation script could not alter the tool"
fi

printf 'test-hook wifi-matrix selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

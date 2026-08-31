#!/usr/bin/env bash
#
# Regression tests for the issue-64 host-only readiness checker.
#
# The suite builds a throwaway Git repository and fake APK inspection tools. It
# deliberately puts a tripwire named `adb` on PATH; any device access by the
# production checker makes the suite fail. No physical device is addressed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROD="$REPO_ROOT/scripts/check-github64-device-readiness.py"
PASS=0
FAIL=0

ok() {
    printf 'ok   %s\n' "$1"
    PASS=$((PASS + 1))
}

bad() {
    printf 'FAIL %s :: %s\n' "$1" "$2"
    FAIL=$((FAIL + 1))
}

if [ ! -x "$PROD" ]; then
    bad "production checker exists" "$PROD is missing or not executable"
    printf '\nselftest-github64-device-readiness: %d passed, %d failed\n' "$PASS" "$FAIL"
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FIXTURE="$WORK/repo"
TOOLS="$WORK/tools"
REPORT="$WORK/report.json"
MANIFEST="$WORK/manifest.json"
mkdir -p "$FIXTURE/apps/cellrebel-auto/app/build/outputs/apk/debug"
mkdir -p "$FIXTURE/apps/qianwangyou/app/build/outputs/apk/debug" "$TOOLS"

git -C "$FIXTURE" init -q
git -C "$FIXTURE" config user.email fixture@example.invalid
git -C "$FIXTURE" config user.name fixture
printf 'base\n' > "$FIXTURE/base.txt"
git -C "$FIXTURE" add base.txt
git -C "$FIXTURE" commit -qm base
BASE_HEAD="$(git -C "$FIXTURE" rev-parse HEAD)"
printf 'candidate\n' > "$FIXTURE/candidate.txt"
printf 'fixture-contract\n' > "$FIXTURE/contract.yaml"
printf 'fixture-schedule\n' > "$FIXTURE/schedule.json"
printf '[]\n' > "$FIXTURE/ledger.json"
printf '**/build/\n' > "$FIXTURE/.gitignore"
git -C "$FIXTURE" add candidate.txt contract.yaml schedule.json ledger.json .gitignore
git -C "$FIXTURE" commit -qm candidate
PRODUCT_HEAD="$(git -C "$FIXTURE" rev-parse HEAD)"
PRODUCT_TREE="$(git -C "$FIXTURE" rev-parse 'HEAD^{tree}')"

AUTO_APK="$FIXTURE/apps/cellrebel-auto/app/build/outputs/apk/debug/app-debug.apk"
QWY_APK="$FIXTURE/apps/qianwangyou/app/build/outputs/apk/debug/app-debug.apk"
printf 'fixture-auto-apk\n' > "$AUTO_APK"
printf 'fixture-qwy-apk\n' > "$QWY_APK"

cat > "$TOOLS/aapt" <<'SH'
#!/usr/bin/env bash
case "$3" in
  *cellrebel-auto*) printf "package: name='com.example.cellrebelauto' versionCode='1' versionName='1.0'\n" ;;
  *qianwangyou*) printf "package: name='name.caiyao.fakegps.bench' versionCode='8' versionName='3.0.0'\n" ;;
  *) exit 2 ;;
esac
SH

cat > "$TOOLS/apksigner" <<'SH'
#!/usr/bin/env bash
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${FIXTURE_SIGNER_SHA:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"
SH

cat > "$TOOLS/adb" <<'SH'
#!/usr/bin/env bash
: > "${ADB_TRIPWIRE:?}"
exit 99
SH
chmod +x "$TOOLS/aapt" "$TOOLS/apksigner" "$TOOLS/adb"

AUTO_SHA="$(shasum -a 256 "$AUTO_APK" | awk '{print $1}')"
QWY_SHA="$(shasum -a 256 "$QWY_APK" | awk '{print $1}')"
CONTRACT_SHA="$(shasum -a 256 "$FIXTURE/contract.yaml" | awk '{print $1}')"
SCHEDULE_SHA="$(shasum -a 256 "$FIXTURE/schedule.json" | awk '{print $1}')"
LEDGER_SHA="$(shasum -a 256 "$FIXTURE/ledger.json" | awk '{print $1}')"
AAPT_TOOL_SHA="$(shasum -a 256 "$TOOLS/aapt" | awk '{print $1}')"
APKSIGNER_TOOL_SHA="$(shasum -a 256 "$TOOLS/apksigner" | awk '{print $1}')"
AUTO_SIZE="$(stat -f '%z' "$AUTO_APK" 2>/dev/null || stat -c '%s' "$AUTO_APK")"
QWY_SIZE="$(stat -f '%z' "$QWY_APK" 2>/dev/null || stat -c '%s' "$QWY_APK")"

PRODUCT_HEAD="$PRODUCT_HEAD" PRODUCT_TREE="$PRODUCT_TREE" BASE_HEAD="$BASE_HEAD" \
AUTO_SHA="$AUTO_SHA" QWY_SHA="$QWY_SHA" AUTO_SIZE="$AUTO_SIZE" QWY_SIZE="$QWY_SIZE" \
CONTRACT_SHA="$CONTRACT_SHA" SCHEDULE_SHA="$SCHEDULE_SHA" LEDGER_SHA="$LEDGER_SHA" python3 - "$MANIFEST" <<'PY'
import json, os, sys

manifest = {
    "schemaVersion": 1,
    "packageId": "github64-exact-build-device-readiness",
    "candidate": {
        "productHead": os.environ["PRODUCT_HEAD"],
        "productTree": os.environ["PRODUCT_TREE"],
        "baseHead": os.environ["BASE_HEAD"],
        "allowedPreparationDelta": [
            "docs/acceptance/github64-exact-build-device-readiness.json",
            "docs/acceptance/github64-exact-build-device-readiness.md",
            "scripts/check-github64-device-readiness.py",
            "scripts/selftest-github64-device-readiness.sh",
        ],
    },
    "artifacts": [
        {
            "id": "auto",
            "relativePath": "apps/cellrebel-auto/app/build/outputs/apk/debug/app-debug.apk",
            "sha256": os.environ["AUTO_SHA"],
            "sizeBytes": int(os.environ["AUTO_SIZE"]),
            "packageName": "com.example.cellrebelauto",
            "versionCode": "1",
            "versionName": "1.0",
            "signerSha256": "a" * 64,
        },
        {
            "id": "qwy",
            "relativePath": "apps/qianwangyou/app/build/outputs/apk/debug/app-debug.apk",
            "sha256": os.environ["QWY_SHA"],
            "sizeBytes": int(os.environ["QWY_SIZE"]),
            "packageName": "name.caiyao.fakegps.bench",
            "versionCode": "8",
            "versionName": "3.0.0",
            "signerSha256": "a" * 64,
        },
    ],
    "inputs": [
        {"id": "contract", "relativePath": "contract.yaml", "sha256": os.environ["CONTRACT_SHA"]},
        {"id": "schedule", "relativePath": "schedule.json", "sha256": os.environ["SCHEDULE_SHA"]},
        {"id": "device-ledger", "relativePath": "ledger.json", "sha256": os.environ["LEDGER_SHA"]},
    ],
    "readiness": {
        "operatorAuthorizationRequired": [
            "DEVICE_LEASE",
            "APK_INSTALL_OR_REPLACE",
            "LSPOSED_SCOPE_CHANGE",
            "SYSTEM_MOCK_SELECTION",
            "DEVICE_STATE_MUTATION",
            "CLEANUP_OR_RESTORE",
        ],
        "blockers": [
            {"id": "G2-HARNESS-SCHEMA-001", "scope": ["G"]},
            {"id": "G2-HARNESS-LEASE-002", "scope": ["A", "B", "C", "E-device", "G"]},
            {"id": "G2-HARNESS-EVIDENCE-003", "scope": ["A", "B", "C", "E-device", "G"]},
            {"id": "G2-PR62-CHANGES-REQUESTED-004", "scope": ["A", "B", "C", "G"]},
            {"id": "G2-PR63-PRINCIPAL-ROUTING-005", "scope": ["A", "B", "C"]},
            {"id": "G2-ISSUE66-CONTINUITY-006", "scope": ["A", "B", "TRUSTED_QUOTA"]},
        ],
        "canonicalLedger": {
            "relativePath": "ledger.json",
            "sha256": os.environ["LEDGER_SHA"],
            "state": "EMPTY_UNCHANGED",
        },
        "goNoGo": "NO_GO_DEVICE_EXECUTION",
        "scopeDisposition": {
            "A": "BLOCKED_BY_PR62_PR63_AND_ISSUE66",
            "B": "BLOCKED_BY_PR62_PR63_AND_ISSUE66",
            "C": "BLOCKED_BY_PR62_AND_PR63_NOT_ISSUE66",
            "E-host": "READY_FOR_HOST_AUDIT",
            "E-device": "BLOCKED_BY_HARNESS_AND_AUTHORIZATION_NOT_ISSUE66",
            "G": "BLOCKED_BY_PR62_SCHEMA_AND_EVIDENCE_NOT_ISSUE66",
            "M-CO-06": "ACCEPTED_HOST_DISPOSITION_NO_DEVICE_LEDGER_ROW",
            "M-VS-01": "POST_V1_ACCEPTED_OUT_OF_CURRENT_G2",
        },
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, indent=2, sort_keys=True)
    fh.write("\n")
PY

MANIFEST_SHA="$(shasum -a 256 "$MANIFEST" | awk '{print $1}')"

run_checker() {
    local manifest=$1 report=$2 mode=${3:-audit}
    local aapt_path=${4:-$TOOLS/aapt}
    local apksigner_path=${5:-$TOOLS/apksigner}
    local signer_output=${6:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}
    python3 - "$PROD" "$manifest" "$FIXTURE" "$report" "$mode" \
        "$MANIFEST_SHA" "$PRODUCT_HEAD" "$PRODUCT_TREE" "$BASE_HEAD" \
        "$aapt_path" "$AAPT_TOOL_SHA" "$apksigner_path" "$APKSIGNER_TOOL_SHA" \
        "$signer_output" "$WORK/adb-called" "$TOOLS" "$LEDGER_SHA" <<'PY' >/dev/null 2>&1
import importlib.util
from pathlib import Path
import sys

checker_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("github64_readiness_checker", checker_path)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

tools = Path(sys.argv[16])
common_env = (("LANG", "C"), ("LC_ALL", "C"), ("PATH", f"{tools}:/usr/bin:/bin"))
policy = module.Policy(
    manifest_sha256=sys.argv[6],
    candidate_head=sys.argv[7],
    candidate_tree=sys.argv[8],
    base_head=sys.argv[9],
    allowed_preparation_delta=frozenset({
        "docs/acceptance/github64-exact-build-device-readiness.json",
        "docs/acceptance/github64-exact-build-device-readiness.md",
        "scripts/check-github64-device-readiness.py",
        "scripts/selftest-github64-device-readiness.sh",
    }),
    artifact_ids=frozenset({"auto", "qwy"}),
    input_ids=frozenset({"contract", "schedule", "device-ledger"}),
    required_authorizations=frozenset({
        "DEVICE_LEASE", "APK_INSTALL_OR_REPLACE", "LSPOSED_SCOPE_CHANGE",
        "SYSTEM_MOCK_SELECTION", "DEVICE_STATE_MUTATION", "CLEANUP_OR_RESTORE",
    }),
    blocker_scopes=(
        ("G2-HARNESS-SCHEMA-001", frozenset({"G"})),
        ("G2-HARNESS-LEASE-002", frozenset({"A", "B", "C", "E-device", "G"})),
        ("G2-HARNESS-EVIDENCE-003", frozenset({"A", "B", "C", "E-device", "G"})),
        ("G2-PR62-CHANGES-REQUESTED-004", frozenset({"A", "B", "C", "G"})),
        ("G2-PR63-PRINCIPAL-ROUTING-005", frozenset({"A", "B", "C"})),
        ("G2-ISSUE66-CONTINUITY-006", frozenset({"A", "B", "TRUSTED_QUOTA"})),
    ),
    scope_disposition=(
        ("A", "BLOCKED_BY_PR62_PR63_AND_ISSUE66"),
        ("B", "BLOCKED_BY_PR62_PR63_AND_ISSUE66"),
        ("C", "BLOCKED_BY_PR62_AND_PR63_NOT_ISSUE66"),
        ("E-host", "READY_FOR_HOST_AUDIT"),
        ("E-device", "BLOCKED_BY_HARNESS_AND_AUTHORIZATION_NOT_ISSUE66"),
        ("G", "BLOCKED_BY_PR62_SCHEMA_AND_EVIDENCE_NOT_ISSUE66"),
        ("M-CO-06", "ACCEPTED_HOST_DISPOSITION_NO_DEVICE_LEDGER_ROW"),
        ("M-VS-01", "POST_V1_ACCEPTED_OUT_OF_CURRENT_G2"),
    ),
    canonical_ledger=(
        ("relativePath", "ledger.json"),
        ("sha256", sys.argv[17]),
        ("state", "EMPTY_UNCHANGED"),
    ),
    go_no_go="NO_GO_DEVICE_EXECUTION",
    inspectors=(
        module.InspectorPolicy(
            inspector_id="git",
            version="git version 2.50.1 (Apple Git-155)",
            executable=module.FrozenFile(
                role="executable",
                path=Path("/usr/bin/git"),
                sha256="b8763cf250e607a778bb4603cecb5b90338814d0a3dfcba0d57b1de242f610e9",
                executable=True,
            ),
            environment=(("LANG", "C"), ("LC_ALL", "C"), ("GIT_CONFIG_NOSYSTEM", "1"),
                         ("GIT_OPTIONAL_LOCKS", "0"), ("GIT_PAGER", "cat"),
                         ("GIT_TERMINAL_PROMPT", "0"), ("PATH", "/usr/bin:/bin")),
        ),
        module.InspectorPolicy(
            inspector_id="aapt",
            version="fixture-aapt-v1",
            executable=module.FrozenFile(
                role="executable", path=Path(sys.argv[10]), sha256=sys.argv[11], executable=True
            ),
            environment=common_env + (("ADB_TRIPWIRE", sys.argv[15]),),
        ),
        module.InspectorPolicy(
            inspector_id="apksigner",
            version="fixture-apksigner-v1",
            executable=module.FrozenFile(
                role="executable", path=Path(sys.argv[12]), sha256=sys.argv[13], executable=True
            ),
            environment=common_env + (("FIXTURE_SIGNER_SHA", sys.argv[14]),),
        ),
    ),
)
raise SystemExit(module.run_audit(
    policy=policy,
    manifest_path=Path(sys.argv[2]),
    source_repo=Path(sys.argv[3]),
    report_path=Path(sys.argv[4]),
    fail_on_blocked=sys.argv[5] != "audit",
))
PY
}

json_value() {
    python3 - "$1" "$2" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    value = value[int(part)] if isinstance(value, list) else value[part]
print(value)
PY
}

if run_checker "$MANIFEST" "$REPORT" audit; then
    if [ "$(json_value "$REPORT" hostStatus)" = PASS ] &&
       [ "$(json_value "$REPORT" overallStatus)" = BLOCKED ] &&
       [ "$(json_value "$REPORT" executedDeviceCommands)" = 0 ] &&
       (cd "$(dirname "$REPORT")" && shasum -a 256 -c "$(basename "$REPORT").sha256" >/dev/null 2>&1); then
        ok "P1 valid host package emits a sealed BLOCKED report"
    else
        bad "P1 valid host package" "report fields or sidecar digest are wrong"
    fi
else
    bad "P1 valid host package" "audit mode returned non-zero"
fi

if python3 - "$REPO_ROOT/docs/acceptance/github64-exact-build-device-readiness.json" <<'PY'
import json, sys
import hashlib
d = json.load(open(sys.argv[1], encoding="utf-8"))
assert hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest() == "459648d13750c3fad3cec17de1a7c4145f736bea054b456a6b7813973b446ac1"
assert d["candidate"] == {
    "allowedPreparationDelta": [
        "docs/acceptance/github64-exact-build-device-readiness.json",
        "docs/acceptance/github64-exact-build-device-readiness.md",
        "scripts/check-github64-device-readiness.py",
        "scripts/selftest-github64-device-readiness.sh",
    ],
    "baseHead": "9eb6389e05e49e5a19c3890fd1a39b9be7e11c1d",
    "productHead": "5002e0e005324c32ca3d36d10510180d1fafbf81",
    "productTree": "ff4c6440509aa1d90b4a7a8dc6647b47c2d33af1",
    "pullRequest": "https://github.com/TERRYYYC/fakexxx/pull/65",
}
assert {a["id"]: a["sha256"] for a in d["artifacts"]} == {
    "auto": "7bd07b07fde483cf1252722f2c29880c0030d47e52638761f19fa2d0dc4a3f1b",
    "qwy": "bb5be7db762a0e38218465e321b582eddb62c3f9110b714ac1c18076a151a161",
}
PY
then
    ok "P1b production manifest remains pinned to the reviewed candidate and APK bytes"
else
    bad "P1b frozen production manifest" "candidate or APK pin drifted"
fi

if run_checker "$MANIFEST" "$WORK/required.json" fail-on-blocked; then
    bad "P2 fail-on-blocked scheduling gate" "blocked package returned zero"
else
    rc=$?
    [ "$rc" -eq 3 ] && ok "P2 fail-on-blocked returns the documented frozen no-go code" ||
        bad "P2 fail-on-blocked scheduling gate" "expected rc=3, got rc=$rc"
fi

printf 'drift\n' >> "$AUTO_APK"
if run_checker "$MANIFEST" "$WORK/drift.json" audit; then
    bad "N1 artifact byte drift" "mutated APK was accepted"
else
    if [ "$(json_value "$WORK/drift.json" hostStatus)" = FAIL ] &&
       grep -q 'artifact:auto:sha256' "$WORK/drift.json"; then
        ok "N1 artifact byte drift is rejected with the exact finding"
    else
        bad "N1 artifact byte drift" "wrong or missing finding"
    fi
fi
printf 'fixture-auto-apk\n' > "$AUTO_APK"

python3 - "$MANIFEST" "$WORK/missing-blocker.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
d["readiness"]["blockers"] = [b for b in d["readiness"]["blockers"] if b["id"] != "G2-ISSUE66-CONTINUITY-006"]
json.dump(d, open(sys.argv[2], "w", encoding="utf-8"), indent=2, sort_keys=True)
PY
if run_checker "$WORK/missing-blocker.json" "$WORK/missing-blocker-report.json" audit; then
    bad "N2 required blocker deletion" "issue #66 disappeared without invalidating the package"
else
    grep -q 'policy:required-blockers' "$WORK/missing-blocker-report.json" &&
        ok "N2 deleting issue #66 blocker invalidates the package" ||
        bad "N2 required blocker deletion" "specific policy finding missing"
fi

printf 'unrelated dirty file\n' > "$FIXTURE/unrelated.txt"
if run_checker "$MANIFEST" "$WORK/dirty.json" audit; then
    bad "N3 unrelated source drift" "dirty candidate was accepted"
else
    grep -q 'source:working-tree-delta' "$WORK/dirty.json" &&
        ok "N3 unrelated source drift is rejected" ||
        bad "N3 unrelated source drift" "specific source finding missing"
fi

python3 - "$MANIFEST" "$WORK/widened.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
d["candidate"]["allowedPreparationDelta"].append("unrelated.txt")
json.dump(d, open(sys.argv[2], "w", encoding="utf-8"), indent=2, sort_keys=True)
PY
if run_checker "$WORK/widened.json" "$WORK/widened-report.json" audit; then
    bad "N3b preparation allowlist widening" "manifest expansion hid unrelated source drift"
else
    grep -q 'candidate:allowed-preparation-delta' "$WORK/widened-report.json" &&
        ok "N3b preparation allowlist cannot be widened to hide source drift" ||
        bad "N3b preparation allowlist widening" "specific policy finding missing"
fi
rm "$FIXTURE/unrelated.txt"

mkdir -p "$FIXTURE/docs/acceptance"
printf 'unreviewed readiness drift\n' > "$FIXTURE/docs/acceptance/github64-exact-build-device-readiness.md"
if run_checker "$MANIFEST" "$WORK/allowed-path-dirty.json" audit; then
    bad "N3c dirty allowed preparation file" "uncommitted package drift was accepted"
else
    grep -q 'source:checkout-clean' "$WORK/allowed-path-dirty.json" &&
        ok "N3c allowed preparation paths must still match checkout HEAD" ||
        bad "N3c dirty allowed preparation file" "specific checkout-clean finding missing"
fi
rm -f "$FIXTURE/docs/acceptance/github64-exact-build-device-readiness.md"
rmdir "$FIXTURE/docs/acceptance" "$FIXTURE/docs" 2>/dev/null || true

if run_checker "$MANIFEST" "$WORK/signer.json" audit "$TOOLS/aapt" "$TOOLS/apksigner" \
    bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb; then
    bad "N4 signer drift" "wrong signer was accepted"
else
    grep -q 'artifact:auto:signer' "$WORK/signer.json" &&
        ok "N4 signer drift is rejected" ||
        bad "N4 signer drift" "specific signer finding missing"
fi

if [ -e "$WORK/adb-called" ]; then
    bad "D1 device-free invariant" "the production checker invoked adb"
else
    ok "D1 all cases completed with zero adb calls"
fi

for scope_case in C E_G_MCO UNRELATED; do
    case "$scope_case" in
        C) scopes='["C"]' ;;
        E_G_MCO) scopes='["E-device", "G", "M-CO-06"]' ;;
        *) scopes='["UNRELATED"]' ;;
    esac
    python3 - "$MANIFEST" "$WORK/scope-$scope_case.json" "$scopes" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
replacement = json.loads(sys.argv[3])
for blocker in d["readiness"]["blockers"]:
    if blocker["id"] == "G2-ISSUE66-CONTINUITY-006":
        blocker["scope"] = replacement
json.dump(d, open(sys.argv[2], "w", encoding="utf-8"), indent=2, sort_keys=True)
PY
    if run_checker "$WORK/scope-$scope_case.json" "$WORK/scope-$scope_case-report.json" audit; then
        bad "N5/$scope_case issue-66 scope integrity" "wrong #66 scope was accepted"
    else
        grep -q 'policy:blocker-scopes' "$WORK/scope-$scope_case-report.json" &&
            ok "N5/$scope_case wrong issue-66 scope is rejected" ||
            bad "N5/$scope_case issue-66 scope integrity" "specific scope finding missing"
    fi
done

for policy_case in GO_NO_GO SCOPE_DISPOSITION CANONICAL_LEDGER; do
    python3 - "$MANIFEST" "$WORK/policy-$policy_case.json" "$policy_case" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
case = sys.argv[3]
if case == "GO_NO_GO":
    d["readiness"]["goNoGo"] = "GO_DEVICE_EXECUTION"
elif case == "SCOPE_DISPOSITION":
    d["readiness"]["scopeDisposition"]["C"] = "BLOCKED_BY_ISSUE66"
else:
    d["readiness"]["canonicalLedger"]["state"] = "PASSED"
json.dump(d, open(sys.argv[2], "w", encoding="utf-8"), indent=2, sort_keys=True)
PY
    case "$policy_case" in
        GO_NO_GO) finding='policy:go-no-go' ;;
        SCOPE_DISPOSITION) finding='policy:scope-disposition' ;;
        *) finding='policy:canonical-ledger' ;;
    esac
    if run_checker "$WORK/policy-$policy_case.json" "$WORK/policy-$policy_case-report.json" audit; then
        bad "N5b/$policy_case readiness policy integrity" "critical readiness field was mutable"
    else
        grep -q "$finding" "$WORK/policy-$policy_case-report.json" &&
            ok "N5b/$policy_case critical readiness field is pinned" ||
            bad "N5b/$policy_case readiness policy integrity" "specific policy finding missing"
    fi
done

printf 'ledger drift\n' >> "$FIXTURE/ledger.json"
if run_checker "$MANIFEST" "$WORK/ledger-drift.json" audit; then
    bad "N5c canonical ledger bytes" "modified device ledger was accepted"
else
    grep -q 'input:device-ledger:sha256' "$WORK/ledger-drift.json" &&
        ok "N5c canonical ledger byte drift is rejected" ||
        bad "N5c canonical ledger bytes" "specific input digest finding missing"
fi
git -C "$FIXTURE" checkout -q -- ledger.json

IN_REPO_REPORT="$FIXTURE/new-report.json"
if run_checker "$MANIFEST" "$IN_REPO_REPORT" audit; then
    bad "N6a in-repo report path" "checker wrote a report into the certified source tree"
else
    [ ! -e "$IN_REPO_REPORT" ] && [ ! -e "$IN_REPO_REPORT.sha256" ] &&
        ok "N6a in-repo report path is rejected before writing" ||
        bad "N6a in-repo report path" "source tree was modified before rejection"
fi
rm -f "$IN_REPO_REPORT" "$IN_REPO_REPORT.sha256"

CONTRACT_BEFORE="$(shasum -a 256 "$FIXTURE/contract.yaml" | awk '{print $1}')"
if run_checker "$MANIFEST" "$FIXTURE/contract.yaml" audit; then
    bad "N6b report/input collision" "checker overwrote a frozen input"
else
    CONTRACT_AFTER="$(shasum -a 256 "$FIXTURE/contract.yaml" | awk '{print $1}')"
    [ "$CONTRACT_AFTER" = "$CONTRACT_BEFORE" ] &&
        ok "N6b report/input collision is rejected with input bytes unchanged" ||
        bad "N6b report/input collision" "contract bytes changed before rejection"
fi
git -C "$FIXTURE" checkout -q -- contract.yaml
rm -f "$FIXTURE/contract.yaml.sha256"

rm -f "$WORK/adb-called"
cat > "$TOOLS/device-touching-aapt" <<'SH'
#!/usr/bin/env bash
: > "${ADB_TRIPWIRE:?}"
case "$3" in
  *cellrebel-auto*) printf "package: name='com.example.cellrebelauto' versionCode='1' versionName='1.0'\n" ;;
  *qianwangyou*) printf "package: name='name.caiyao.fakegps.bench' versionCode='8' versionName='3.0.0'\n" ;;
esac
SH
chmod +x "$TOOLS/device-touching-aapt"
if run_checker "$MANIFEST" "$WORK/untrusted-tool.json" audit \
    "$TOOLS/device-touching-aapt" "$TOOLS/apksigner"; then
    bad "N7 untrusted inspector" "caller-selected wrapper was executed and accepted"
else
    [ ! -e "$WORK/adb-called" ] &&
        ok "N7 untrusted inspector is rejected before execution" ||
        bad "N7 untrusted inspector" "wrapper reached the adb tripwire before rejection"
fi

printf 'alternate candidate\n' > "$FIXTURE/alternate.txt"
git -C "$FIXTURE" add alternate.txt
git -C "$FIXTURE" commit -qm alternate
ALT_HEAD="$(git -C "$FIXTURE" rev-parse HEAD)"
ALT_TREE="$(git -C "$FIXTURE" rev-parse 'HEAD^{tree}')"
python3 - "$MANIFEST" "$WORK/alternate-manifest.json" "$ALT_HEAD" "$ALT_TREE" "$PRODUCT_HEAD" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
d["candidate"]["productHead"] = sys.argv[3]
d["candidate"]["productTree"] = sys.argv[4]
d["candidate"]["baseHead"] = sys.argv[5]
json.dump(d, open(sys.argv[2], "w", encoding="utf-8"), indent=2, sort_keys=True)
PY
if run_checker "$WORK/alternate-manifest.json" "$WORK/alternate-report.json" audit; then
    bad "N8 frozen candidate identity" "self-consistent replacement candidate was accepted"
else
    grep -q 'candidate:frozen-identity' "$WORK/alternate-report.json" &&
        ok "N8 replacement candidate is rejected by immutable policy" ||
        bad "N8 frozen candidate identity" "specific frozen-identity finding missing"
fi

printf '\nselftest-github64-device-readiness: %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]

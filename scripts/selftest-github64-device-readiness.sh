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

BOOTSTRAP_TOOLS="$(mktemp -d)"
BOOTSTRAP_SITE="$(mktemp -d)"
BOOTSTRAP_PATH_MARKER="$BOOTSTRAP_TOOLS/path-python-called"
BOOTSTRAP_SITE_MARKER="$BOOTSTRAP_SITE/sitecustomize-called"
cat > "$BOOTSTRAP_TOOLS/python3" <<SH
#!/bin/sh
: > "$BOOTSTRAP_PATH_MARKER"
exec /usr/bin/python3 "\$@"
SH
cat > "$BOOTSTRAP_SITE/sitecustomize.py" <<PY
from pathlib import Path
Path("$BOOTSTRAP_SITE_MARKER").touch()
PY
chmod +x "$BOOTSTRAP_TOOLS/python3"
if PATH="$BOOTSTRAP_TOOLS:/usr/bin:/bin" PYTHONPATH="$BOOTSTRAP_SITE" \
    "$PROD" --help >/dev/null 2>&1 &&
    [ ! -e "$BOOTSTRAP_PATH_MARKER" ] && [ ! -e "$BOOTSTRAP_SITE_MARKER" ]; then
    ok "P0 production bootstrap ignores PATH python and user sitecustomize"
else
    bad "P0 production bootstrap isolation" "PATH python or sitecustomize executed"
fi
rm -rf "$BOOTSTRAP_TOOLS" "$BOOTSTRAP_SITE"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FIXTURE="$WORK/repo"
TOOLS="$WORK/tools"
RUNTIME_TREE="$WORK/runtime-tree"
REPORT="$WORK/report.json"
MANIFEST="$WORK/manifest.json"
mkdir -p "$FIXTURE/apps/cellrebel-auto/app/build/outputs/apk/debug"
mkdir -p "$FIXTURE/apps/qianwangyou/app/build/outputs/apk/debug" "$TOOLS" "$RUNTIME_TREE"
printf 'fixture runtime dependency\n' > "$RUNTIME_TREE/dependency.bin"
printf 'FIXTURE_SUPPORT_VALUE=trusted\n' > "$TOOLS/apksigner-support"

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
printf '#!/bin/sh\nexit 0\n' > "$FIXTURE/tracked-executable"
chmod 755 "$FIXTURE/tracked-executable"
ln -s base.txt "$FIXTURE/tracked-link"
git -C "$FIXTURE" add candidate.txt contract.yaml schedule.json ledger.json .gitignore \
    tracked-executable tracked-link
git -C "$FIXTURE" commit -qm candidate
PRODUCT_HEAD="$(git -C "$FIXTURE" rev-parse HEAD)"
PRODUCT_TREE="$(git -C "$FIXTURE" rev-parse 'HEAD^{tree}')"
ALTERNATE_SAME_TREE_HEAD="$(
    printf 'fixture same-tree alternate head\n' |
        git -C "$FIXTURE" commit-tree "$PRODUCT_TREE" -p "$PRODUCT_HEAD"
)"

AUTO_APK="$FIXTURE/apps/cellrebel-auto/app/build/outputs/apk/debug/app-debug.apk"
QWY_APK="$FIXTURE/apps/qianwangyou/app/build/outputs/apk/debug/app-debug.apk"
printf 'fixture-auto-apk\n' > "$AUTO_APK"
printf 'fixture-qwy-apk\n' > "$QWY_APK"

cat > "$TOOLS/aapt" <<'SH'
#!/usr/bin/env bash
if [ -n "${ATOMIC_REPLACE_SOURCE:-}" ] && [ -n "${ATOMIC_REPLACEMENT_PAYLOAD:-}" ]; then
  mv "$ATOMIC_REPLACEMENT_PAYLOAD" "$ATOMIC_REPLACE_SOURCE"
fi
payload="$(cat "$3")"
case "$payload" in
  fixture-auto-apk*) printf "package: name='com.example.cellrebelauto' versionCode='1' versionName='1.0'\n" ;;
  fixture-qwy-apk*) printf "package: name='name.caiyao.fakegps.bench' versionCode='8' versionName='3.0.0'\n" ;;
  *) exit 2 ;;
esac
SH

cat > "$TOOLS/apksigner" <<'SH'
#!/usr/bin/env bash
[ "$1" = "--fixture-prefix" ] || exit 3
[ -f "$2" ] || exit 4
. "$2"
[ "${FIXTURE_SUPPORT_VALUE:-}" = trusted ] || exit 5
shift 2
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${FIXTURE_SIGNER_SHA:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"
SH

cat > "$TOOLS/git" <<'SH'
#!/bin/sh
exec /usr/bin/git "$@"
SH

cat > "$TOOLS/adb" <<'SH'
#!/usr/bin/env bash
: > "${ADB_TRIPWIRE:?}"
exit 99
SH
chmod +x "$TOOLS/aapt" "$TOOLS/apksigner" "$TOOLS/git" "$TOOLS/adb"

AUTO_SHA="$(shasum -a 256 "$AUTO_APK" | awk '{print $1}')"
QWY_SHA="$(shasum -a 256 "$QWY_APK" | awk '{print $1}')"
CONTRACT_SHA="$(shasum -a 256 "$FIXTURE/contract.yaml" | awk '{print $1}')"
SCHEDULE_SHA="$(shasum -a 256 "$FIXTURE/schedule.json" | awk '{print $1}')"
LEDGER_SHA="$(shasum -a 256 "$FIXTURE/ledger.json" | awk '{print $1}')"
AAPT_TOOL_SHA="$(shasum -a 256 "$TOOLS/aapt" | awk '{print $1}')"
APKSIGNER_TOOL_SHA="$(shasum -a 256 "$TOOLS/apksigner" | awk '{print $1}')"
APKSIGNER_SUPPORT_SHA="$(shasum -a 256 "$TOOLS/apksigner-support" | awk '{print $1}')"
GIT_TOOL_SHA="$(shasum -a 256 "$TOOLS/git" | awk '{print $1}')"
RUNTIME_TREE_SHA="$(python3 - "$PROD" "$RUNTIME_TREE" <<'PY'
import importlib.util
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("github64_tree_digest", Path(sys.argv[1]))
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
print(module.sha256_tree(Path(sys.argv[2])))
PY
)"
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
        "allowedGeneratedRoots": [
            "apps/cellrebel-auto/app/build",
            "apps/qianwangyou/app/build",
        ],
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
    local source_repo=${7:-$FIXTURE}
    python3 - "$PROD" "$manifest" "$source_repo" "$report" "$mode" \
        "$MANIFEST_SHA" "$PRODUCT_HEAD" "$PRODUCT_TREE" "$BASE_HEAD" \
        "$aapt_path" "$AAPT_TOOL_SHA" "$apksigner_path" "$APKSIGNER_TOOL_SHA" \
        "$signer_output" "$WORK/adb-called" "$TOOLS" "$LEDGER_SHA" \
        "$RUNTIME_TREE" "$RUNTIME_TREE_SHA" "$TOOLS/git" "$GIT_TOOL_SHA" \
        "$TOOLS/apksigner-support" "$APKSIGNER_SUPPORT_SHA" \
        <<'PY' >/dev/null 2>&1
import importlib.util
import os
from pathlib import Path
import sys

checker_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("github64_readiness_checker", checker_path)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

race_source = os.environ.get("ATOMIC_REPLACE_TOOL_SOURCE")
race_payload = os.environ.get("ATOMIC_REPLACE_TOOL_PAYLOAD")
tree_race_source = os.environ.get("ATOMIC_REPLACE_TREE_SOURCE")
tree_race_payload = os.environ.get("ATOMIC_REPLACE_TREE_PAYLOAD")
support_race_source = os.environ.get("ATOMIC_REPLACE_SUPPORT_SOURCE")
support_race_payload = os.environ.get("ATOMIC_REPLACE_SUPPORT_PAYLOAD")
if (
    (race_source and race_payload)
    or (tree_race_source and tree_race_payload)
    or (support_race_source and support_race_payload)
):
    original_run = module.run
    race_fired = [False]

    def run_with_atomic_tool_replacement(command, **kwargs):
        if not race_fired[0] and "dump" in command and "badging" in command:
            if race_source and race_payload:
                os.replace(race_payload, race_source)
            if tree_race_source and tree_race_payload:
                os.replace(tree_race_payload, tree_race_source)
            if support_race_source and support_race_payload:
                os.replace(support_race_payload, support_race_source)
            race_fired[0] = True
        return original_run(command, **kwargs)

    module.run = run_with_atomic_tool_replacement

input_race_source = os.environ.get("ATOMIC_REPLACE_INPUT_SOURCE")
input_race_payload = os.environ.get("ATOMIC_REPLACE_INPUT_PAYLOAD")
if input_race_source and input_race_payload:
    original_validate_inputs = module.validate_inputs

    def validate_inputs_with_atomic_replacement(*args, **kwargs):
        result = original_validate_inputs(*args, **kwargs)
        os.replace(input_race_payload, input_race_source)
        return result

    module.validate_inputs = validate_inputs_with_atomic_replacement

manifest_race_source = os.environ.get("ATOMIC_REPLACE_MANIFEST_SOURCE")
manifest_race_payload = os.environ.get("ATOMIC_REPLACE_MANIFEST_PAYLOAD")
late_checkout_drift = os.environ.get("LATE_CHECKOUT_DRIFT_PATH")
late_head_repo = os.environ.get("LATE_CHECKOUT_HEAD_REPO")
late_head_value = os.environ.get("LATE_CHECKOUT_HEAD_VALUE")
if (
    (manifest_race_source and manifest_race_payload)
    or late_checkout_drift
    or (late_head_repo and late_head_value)
):
    original_validate_readiness = module.validate_readiness

    def validate_readiness_with_atomic_replacement(*args, **kwargs):
        result = original_validate_readiness(*args, **kwargs)
        if manifest_race_source and manifest_race_payload:
            os.replace(manifest_race_payload, manifest_race_source)
        if late_checkout_drift:
            Path(late_checkout_drift).write_text("late checkout drift\n", encoding="utf-8")
        if late_head_repo and late_head_value:
            git_dir = Path(late_head_repo) / ".git"
            head_marker = git_dir / "HEAD"
            head_payload = head_marker.read_text(encoding="utf-8").strip()
            if head_payload.startswith("ref: "):
                ref_path = git_dir / head_payload.removeprefix("ref: ")
            else:
                ref_path = head_marker
            replacement = ref_path.with_name(f".{ref_path.name}.late-switch")
            replacement.parent.mkdir(parents=True, exist_ok=True)
            replacement.write_text(f"{late_head_value}\n", encoding="ascii")
            os.replace(replacement, ref_path)
        return result

    module.validate_readiness = validate_readiness_with_atomic_replacement

tools = Path(sys.argv[16])
common_env = (("LANG", "C"), ("LC_ALL", "C"), ("PATH", f"{tools}:/usr/bin:/bin"))
aapt_env = common_env + (("ADB_TRIPWIRE", sys.argv[15]),)
for key in ("ATOMIC_REPLACE_SOURCE", "ATOMIC_REPLACEMENT_PAYLOAD"):
    if os.environ.get(key):
        aapt_env += ((key, os.environ[key]),)
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
    allowed_generated_roots=frozenset({
        "apps/cellrebel-auto/app/build",
        "apps/qianwangyou/app/build",
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
            version="fixture Git proxy",
            executable=module.FrozenFile(
                role="executable",
                path=Path(sys.argv[20]),
                sha256=sys.argv[21],
                executable=True,
            ),
            environment=(("LANG", "C"), ("LC_ALL", "C"), ("GIT_ATTR_NOSYSTEM", "1"),
                         ("GIT_CONFIG_GLOBAL", "/dev/null"), ("GIT_CONFIG_NOSYSTEM", "1"),
                         ("GIT_CONFIG_SYSTEM", "/dev/null"), ("GIT_NO_LAZY_FETCH", "1"),
                         ("GIT_NO_REPLACE_OBJECTS", "1"),
                         ("GIT_OPTIONAL_LOCKS", "0"), ("GIT_PAGER", ""),
                         ("GIT_TERMINAL_PROMPT", "0"), ("PATH", "/usr/bin:/bin")),
        ),
        module.InspectorPolicy(
            inspector_id="aapt",
            version="fixture-aapt-v1",
            executable=module.FrozenFile(
                role="executable", path=Path(sys.argv[10]), sha256=sys.argv[11], executable=True
            ),
            support_trees=(
                module.FrozenTree(
                    role="fixture-runtime", path=Path(sys.argv[18]), sha256=sys.argv[19]
                ),
            ),
            environment=aapt_env,
        ),
        module.InspectorPolicy(
            inspector_id="apksigner",
            version="fixture-apksigner-v1",
            executable=module.FrozenFile(
                role="executable", path=Path(sys.argv[12]), sha256=sys.argv[13], executable=True
            ),
            support_files=(
                module.FrozenFile(
                    role="fixture-apksigner-support",
                    path=Path(sys.argv[22]),
                    sha256=sys.argv[23],
                ),
            ),
            arguments_prefix=("--fixture-prefix", sys.argv[22]),
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

MANIFEST_SOURCE_BACKUP="$WORK/manifest-source.backup"
MANIFEST_REPLACEMENT="$WORK/manifest-byte-identical-replacement.json"
cp -p "$MANIFEST" "$MANIFEST_SOURCE_BACKUP"
cp -p "$MANIFEST" "$MANIFEST_REPLACEMENT"
if ATOMIC_REPLACE_MANIFEST_SOURCE="$MANIFEST" \
   ATOMIC_REPLACE_MANIFEST_PAYLOAD="$MANIFEST_REPLACEMENT" \
   run_checker "$MANIFEST" "$WORK/manifest-race-report.json" audit; then
    bad "N0 late manifest atomic-replace race" \
        "byte-identical new inode bypassed the final manifest seal"
else
    grep -q 'manifest:source-stable' "$WORK/manifest-race-report.json" &&
        ok "N0 final barrier rejects byte-identical late manifest replacement" ||
        bad "N0 late manifest atomic-replace race" \
            "manifest source-stable finding is missing"
fi
cp -p "$MANIFEST_SOURCE_BACKUP" "$MANIFEST"

MALFORMED_UTF8_MANIFEST="$WORK/malformed-utf8-manifest.json"
printf '\377not-json\n' > "$MALFORMED_UTF8_MANIFEST"
if run_checker "$MALFORMED_UTF8_MANIFEST" \
    "$WORK/malformed-utf8-report.json" audit; then
    bad "N0b malformed UTF-8 manifest" "invalid manifest returned zero"
elif [ -f "$WORK/malformed-utf8-report.json" ] &&
     [ -f "$WORK/malformed-utf8-report.json.sha256" ] &&
     grep -q 'manifest:read' "$WORK/malformed-utf8-report.json" &&
     (cd "$WORK" && shasum -a 256 -c malformed-utf8-report.json.sha256 >/dev/null 2>&1) &&
     [ -z "$(find "$WORK" -maxdepth 1 -type d \
         -name '.github64-audit-snapshots-*' -print -quit)" ]; then
    ok "N0b malformed UTF-8 emits sealed INVALID evidence and cleans snapshots"
else
    bad "N0b malformed UTF-8 manifest" \
        "sealed INVALID report, manifest finding, or cleanup proof is missing"
fi

LATE_CHECKOUT_DRIFT="$FIXTURE/late-untracked.txt"
if LATE_CHECKOUT_DRIFT_PATH="$LATE_CHECKOUT_DRIFT" \
   run_checker "$MANIFEST" "$WORK/late-checkout-report.json" audit; then
    bad "N0c late checkout drift" "post-inspection untracked file produced host PASS"
elif grep -q 'source:checkout-final-barrier' "$WORK/late-checkout-report.json" &&
     [ "$(json_value "$WORK/late-checkout-report.json" source.checkoutStatus)" != \
       clean-at-final-barrier ]; then
    ok "N0c final checkout barrier rejects late source drift"
else
    bad "N0c late checkout drift" "final-barrier finding or status is missing"
fi
rm -f "$LATE_CHECKOUT_DRIFT"

if LATE_CHECKOUT_HEAD_REPO="$FIXTURE" \
   LATE_CHECKOUT_HEAD_VALUE="$ALTERNATE_SAME_TREE_HEAD" \
   run_checker "$MANIFEST" "$WORK/late-head-switch-report.json" audit; then
    bad "N0e late same-tree HEAD switch" \
        "different checkout identity with identical tree produced host PASS"
elif grep -q 'source:checkout-final-barrier' \
        "$WORK/late-head-switch-report.json" &&
     python3 - "$WORK/late-head-switch-report.json" \
        "$PRODUCT_HEAD" "$ALTERNATE_SAME_TREE_HEAD" <<'PY'
import json, sys
report = json.load(open(sys.argv[1], encoding="utf-8"))
finding = next(
    item for item in report["findings"]
    if item["id"] == "source:checkout-final-barrier"
)
assert finding["status"] == "FAIL"
assert finding["actual"]["expectedCheckoutHead"] == sys.argv[2]
assert finding["actual"]["headBefore"] == sys.argv[3]
assert finding["actual"]["headAfter"] == sys.argv[3]
PY
then
    ok "N0e final checkout barrier remains bound to opening HEAD identity"
else
    bad "N0e late same-tree HEAD switch" \
        "expected/opening/final HEAD evidence is missing"
fi
git -C "$FIXTURE" update-ref HEAD "$PRODUCT_HEAD"

FIFO_PATH="$FIXTURE/apps/cellrebel-auto/app/build/manifest-selected.pipe"
FIFO_MANIFEST="$WORK/fifo-selected-manifest.json"
mkfifo "$FIFO_PATH"
python3 - "$MANIFEST" "$FIFO_MANIFEST" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
d["artifacts"][0]["relativePath"] = (
    "apps/cellrebel-auto/app/build/manifest-selected.pipe"
)
json.dump(d, open(sys.argv[2], "w", encoding="utf-8"), indent=2, sort_keys=True)
PY
FIFO_RC_FILE="$WORK/fifo-selected.rc"
(
    run_checker "$FIFO_MANIFEST" "$WORK/fifo-selected-report.json" audit
    printf '%s\n' "$?" > "$FIFO_RC_FILE"
) &
FIFO_PID=$!
FIFO_FINISHED=false
for _ in {1..50}; do
    if ! kill -0 "$FIFO_PID" 2>/dev/null; then
        FIFO_FINISHED=true
        break
    fi
    sleep 0.1
done
if [ "$FIFO_FINISHED" = false ]; then
    kill "$FIFO_PID" 2>/dev/null || true
fi
wait "$FIFO_PID" 2>/dev/null || true
if [ "$FIFO_FINISHED" = true ] && [ -f "$FIFO_RC_FILE" ] &&
   [ "$(cat "$FIFO_RC_FILE")" = 1 ] &&
   grep -q 'manifest:trusted-path-selection' "$WORK/fifo-selected-report.json" &&
   ! grep -q 'artifact:auto:exists' "$WORK/fifo-selected-report.json"; then
    ok "N0d untrusted manifest path selection is skipped without FIFO blocking"
else
    bad "N0d untrusted manifest FIFO path" \
        "checker blocked or consumed a path from a digest-invalid manifest"
fi
rm -f "$FIFO_PATH"

if python3 - "$REPO_ROOT/docs/acceptance/github64-exact-build-device-readiness.json" <<'PY'
import json, sys
import hashlib
d = json.load(open(sys.argv[1], encoding="utf-8"))
assert hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest() == "3129b3d9e0a733753e35b85e72ec726e5855cfe9f4395ab49da0cbf734cae43f"
assert d["candidate"] == {
    "allowedGeneratedRoots": [
        "acceptance/.gradle",
        "acceptance/build",
        "acceptance/fake-qwy/build",
        "acceptance/scenarios/build",
        "apps/cellrebel-auto/.gradle",
        "apps/cellrebel-auto/app/build",
        "apps/cellrebel-auto/build",
        "apps/qianwangyou/.gradle",
        "apps/qianwangyou/app/build",
        "apps/qianwangyou/build",
    ],
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

if python3 - "$PROD" "$REPO_ROOT/docs/acceptance/github64-exact-build-device-readiness.json" <<'PY'
import importlib.util
import json
from pathlib import Path
import re
import sys

checker = Path(sys.argv[1])
assert checker.read_text(encoding="utf-8").splitlines()[0] == (
    "#!/Library/Developer/CommandLineTools/usr/bin/python3 -I"
)
spec = importlib.util.spec_from_file_location("github64_production_policy", checker)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
inspectors = {item.inspector_id: item for item in module.production_policy().inspectors}
manifest_policy = json.load(open(sys.argv[2], encoding="utf-8"))["buildEnvironment"][
    "inspectionPolicy"
]
git_env = dict(inspectors["git"].environment)
assert git_env["GIT_CONFIG_GLOBAL"] == "/dev/null"
assert git_env["GIT_CONFIG_SYSTEM"] == "/dev/null"
assert git_env["GIT_NO_LAZY_FETCH"] == "1"
assert git_env["GIT_NO_REPLACE_OBJECTS"] == "1"
assert git_env["GIT_PAGER"] == ""
assert inspectors["git"].executable.path == Path(
    "/Users/terry/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/git/bin/git"
)
assert git_env["GIT_EXEC_PATH"].endswith("/native/git/libexec/git-core")
assert inspectors["apksigner"].executable.path == Path(
    "/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home/bin/java"
)
assert inspectors["apksigner"].arguments_prefix == (
    "-jar",
    "/Users/terry/Library/Android/sdk/build-tools/36.1.0/lib/apksigner.jar",
)
assert inspectors["python-bootstrap"].executable.path == Path(
    "/Library/Developer/CommandLineTools/usr/bin/python3"
)
assert manifest_policy["pythonBootstrap"]["sha256"] == inspectors[
    "python-bootstrap"
].executable.sha256
assert manifest_policy["aapt"]["sha256"] == inspectors["aapt"].executable.sha256
assert manifest_policy["apksigner"]["sha256"] == inspectors[
    "apksigner"
].executable.sha256
trees = {
    tree.role: tree.sha256
    for inspector in inspectors.values()
    for tree in inspector.support_trees
}
assert set(trees) == {"python-runtime", "git-home", "build-tools-home", "java-home"}
assert all(re.fullmatch(r"[0-9a-f]{64}", digest) for digest in trees.values())
assert manifest_policy["pythonBootstrap"]["runtimeTree"]["sha256"] == trees[
    "python-runtime"
]
assert manifest_policy["git"]["supportTrees"][0]["sha256"] == trees["git-home"]
assert manifest_policy["aapt"]["supportTrees"][0]["sha256"] == trees[
    "build-tools-home"
]
assert manifest_policy["apksigner"]["supportTrees"][0]["sha256"] == trees[
    "java-home"
]
PY
then
    ok "P1c production bootstrap, Git isolation and runtime closures are structurally pinned"
else
    bad "P1c production trust-boundary structure" "bootstrap, Git or runtime pin drifted"
fi

if python3 - "$REPORT" <<'PY'
import json, sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
evidence = report["directCommandEvidence"]
commands = evidence["commands"]
assert evidence["scope"] == "DIRECT_CHECKER_SUBPROCESS_DISPATCH_ONLY"
assert evidence["childProcessTracing"] is False
assert commands
assert report["executedDeviceCommands"] == sum(
    1 for command in commands if command["spawned"] and command["deviceTransport"]
)
assert all(command["executionSource"] == "PRIVATE_VERIFIED_SNAPSHOT" for command in commands)
assert all(command["spawned"] and command["outputUtf8"] is True for command in commands)
assert all(tool["snapshotStatus"] == "PASS" for tool in report["hostTools"])
apksigner_commands = [command for command in commands if command["inspectorId"] == "apksigner"]
assert apksigner_commands
assert all(
    command["arguments"][:2]
    == ["--fixture-prefix", "$TOOL_SNAPSHOT:fixture-apksigner-support"]
    for command in apksigner_commands
)
assert ".github64-audit-snapshots-" not in json.dumps(report)
PY
then
    ok "P1d command count is derived and private snapshot paths are not persisted"
else
    bad "P1d command/snapshot evidence" "report overclaims or leaks an ephemeral path"
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
       grep -q 'artifact:auto:sha256' "$WORK/drift.json" &&
       python3 - "$WORK/drift.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1], encoding="utf-8"))
commands = report["directCommandEvidence"]["commands"]
assert not any(
    "$ARTIFACT_SNAPSHOT:auto" in command["arguments"] for command in commands
)
assert any(
    "$ARTIFACT_SNAPSHOT:qwy" in command["arguments"] for command in commands
)
PY
    then
        ok "N1 artifact byte drift is rejected before its parsers dispatch"
    else
        bad "N1 artifact byte drift" "wrong or missing finding"
    fi
fi
printf 'fixture-auto-apk\n' > "$AUTO_APK"

ATOMIC_REPLACEMENT="$WORK/atomic-auto-replacement.apk"
printf 'fixture-auto-apkX' > "$ATOMIC_REPLACEMENT"
if ATOMIC_REPLACE_SOURCE="$AUTO_APK" \
   ATOMIC_REPLACEMENT_PAYLOAD="$ATOMIC_REPLACEMENT" \
   run_checker "$MANIFEST" "$WORK/atomic-replace-report.json" audit; then
    bad "N1b artifact atomic-replace race" \
        "old hash plus metadata from replacement APK produced host PASS"
else
    grep -q 'artifact:auto:source-stable' "$WORK/atomic-replace-report.json" &&
        ok "N1b atomic replacement invalidates the artifact source seal" ||
        bad "N1b artifact atomic-replace race" "specific source-stable finding missing"
fi
printf 'fixture-auto-apk\n' > "$AUTO_APK"

IDENTICAL_REPLACEMENT="$WORK/identical-auto-replacement.apk"
cp -p "$AUTO_APK" "$IDENTICAL_REPLACEMENT"
if ATOMIC_REPLACE_SOURCE="$AUTO_APK" \
   ATOMIC_REPLACEMENT_PAYLOAD="$IDENTICAL_REPLACEMENT" \
   run_checker "$MANIFEST" "$WORK/identical-replace-report.json" audit; then
    bad "N1c byte-identical artifact replacement" \
        "new inode with identical bytes bypassed the source identity seal"
else
    grep -q 'artifact:auto:source-stable' "$WORK/identical-replace-report.json" &&
        ok "N1c byte-identical replacement invalidates the held source identity" ||
        bad "N1c byte-identical artifact replacement" "identity finding missing"
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

INPUT_REPLACEMENT="$WORK/contract-atomic-replacement.yaml"
printf 'fixture-contract-replaced\n' > "$INPUT_REPLACEMENT"
if ATOMIC_REPLACE_INPUT_SOURCE="$FIXTURE/contract.yaml" \
   ATOMIC_REPLACE_INPUT_PAYLOAD="$INPUT_REPLACEMENT" \
   run_checker "$MANIFEST" "$WORK/input-atomic-replace-report.json" audit; then
    bad "N5d input atomic-replace race" "post-validation input drift produced host PASS"
else
    grep -q 'input:contract:source-stable' "$WORK/input-atomic-replace-report.json" &&
        ok "N5d final barrier rejects input replacement after stable read" ||
        bad "N5d input atomic-replace race" "specific source-stable finding missing"
fi
git -C "$FIXTURE" checkout -q -- contract.yaml

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

cp -p "$MANIFEST" "$WORK/manifest.backup"
MANIFEST_BEFORE="$(shasum -a 256 "$MANIFEST" | awk '{print $1}')"
if run_checker "$MANIFEST" "$MANIFEST" audit; then
    MANIFEST_COLLISION_RC=0
else
    MANIFEST_COLLISION_RC=$?
fi
MANIFEST_AFTER="$(shasum -a 256 "$MANIFEST" | awk '{print $1}')"
if [ "$MANIFEST_COLLISION_RC" -eq 2 ] && [ "$MANIFEST_AFTER" = "$MANIFEST_BEFORE" ]; then
    ok "N6b2 external manifest/report collision is rejected with bytes unchanged"
else
    bad "N6b2 external manifest/report collision" \
        "rc=$MANIFEST_COLLISION_RC before=$MANIFEST_BEFORE after=$MANIFEST_AFTER"
fi
cp -p "$WORK/manifest.backup" "$MANIFEST"
rm -f "$MANIFEST.sha256"

cp -p "$TOOLS/aapt" "$WORK/aapt.backup"
AAPT_BEFORE="$(shasum -a 256 "$TOOLS/aapt" | awk '{print $1}')"
if run_checker "$MANIFEST" "$TOOLS/aapt" audit; then
    TOOL_COLLISION_RC=0
else
    TOOL_COLLISION_RC=$?
fi
AAPT_AFTER="$(shasum -a 256 "$TOOLS/aapt" | awk '{print $1}')"
if [ "$TOOL_COLLISION_RC" -eq 2 ] && [ "$AAPT_AFTER" = "$AAPT_BEFORE" ]; then
    ok "N6c report/pinned-tool collision is rejected with tool bytes unchanged"
else
    bad "N6c report/pinned-tool collision" \
        "rc=$TOOL_COLLISION_RC before=$AAPT_BEFORE after=$AAPT_AFTER"
fi
cp -p "$WORK/aapt.backup" "$TOOLS/aapt"
rm -f "$TOOLS/aapt.sha256"

HARDLINK_REPORT="$WORK/aapt-hardlink-report"
ln "$TOOLS/aapt" "$HARDLINK_REPORT"
HARDLINK_BEFORE="$(shasum -a 256 "$HARDLINK_REPORT" | awk '{print $1}')"
if run_checker "$MANIFEST" "$HARDLINK_REPORT" audit; then
    HARDLINK_RC=0
else
    HARDLINK_RC=$?
fi
HARDLINK_AFTER="$(shasum -a 256 "$HARDLINK_REPORT" | awk '{print $1}')"
if [ "$HARDLINK_RC" -eq 2 ] && [ "$HARDLINK_AFTER" = "$HARDLINK_BEFORE" ]; then
    ok "N6c2 hard-linked report/tool alias is rejected by file identity"
else
    bad "N6c2 hard-linked report/tool alias" \
        "rc=$HARDLINK_RC before=$HARDLINK_BEFORE after=$HARDLINK_AFTER"
fi
rm -f "$HARDLINK_REPORT" "$HARDLINK_REPORT.sha256"

ALIAS_REPORT="$WORK/alias-report.json"
printf 'sentinel report\n' > "$ALIAS_REPORT"
ln -s "$ALIAS_REPORT" "$ALIAS_REPORT.sha256"
ALIAS_BEFORE="$(shasum -a 256 "$ALIAS_REPORT" | awk '{print $1}')"
if run_checker "$MANIFEST" "$ALIAS_REPORT" audit; then
    SIDECAR_ALIAS_RC=0
else
    SIDECAR_ALIAS_RC=$?
fi
ALIAS_AFTER="$(shasum -a 256 "$ALIAS_REPORT" | awk '{print $1}')"
if [ "$SIDECAR_ALIAS_RC" -eq 2 ] && [ "$ALIAS_AFTER" = "$ALIAS_BEFORE" ]; then
    ok "N6d report/sidecar alias is rejected with report bytes unchanged"
else
    bad "N6d report/sidecar alias" \
        "rc=$SIDECAR_ALIAS_RC before=$ALIAS_BEFORE after=$ALIAS_AFTER"
fi
rm -f "$ALIAS_REPORT.sha256" "$ALIAS_REPORT"

LINKED_REPO="$WORK/linked-worktree"
git -C "$FIXTURE" worktree add --detach -q "$LINKED_REPO" "$PRODUCT_HEAD"
LINKED_GITDIR="$(git -C "$LINKED_REPO" rev-parse --absolute-git-dir)"
LINKED_INDEX="$LINKED_GITDIR/index"
LINKED_INDEX_BEFORE="$(shasum -a 256 "$LINKED_INDEX" | awk '{print $1}')"
if run_checker "$MANIFEST" "$LINKED_INDEX" audit "$TOOLS/aapt" "$TOOLS/apksigner" \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa "$LINKED_REPO"; then
    GIT_METADATA_RC=0
else
    GIT_METADATA_RC=$?
fi
LINKED_INDEX_AFTER="$(shasum -a 256 "$LINKED_INDEX" | awk '{print $1}')"
if [ "$GIT_METADATA_RC" -eq 2 ] && [ "$LINKED_INDEX_AFTER" = "$LINKED_INDEX_BEFORE" ]; then
    ok "N6e out-of-tree linked-worktree metadata is protected"
else
    bad "N6e linked-worktree metadata collision" \
        "rc=$GIT_METADATA_RC before=$LINKED_INDEX_BEFORE after=$LINKED_INDEX_AFTER"
fi
git -C "$FIXTURE" worktree remove --force "$LINKED_REPO"

UNICODE_REPORT="$WORK/réport.json"
if run_checker "$MANIFEST" "$UNICODE_REPORT" audit; then
    if (cd "$WORK" && shasum -a 256 -c "$(basename "$UNICODE_REPORT").sha256" >/dev/null 2>&1); then
        ok "N6f non-ASCII report filename receives a verifiable sidecar"
    else
        bad "N6f non-ASCII report filename" "sidecar verification failed"
    fi
else
    bad "N6f non-ASCII report filename" "valid output path left a partial result"
fi

NEWLINE_REPORT="$WORK/line
break.json"
if run_checker "$MANIFEST" "$NEWLINE_REPORT" audit; then
    NEWLINE_RC=0
else
    NEWLINE_RC=$?
fi
if [ "$NEWLINE_RC" -eq 2 ] && [ ! -e "$NEWLINE_REPORT" ]; then
    ok "N6g newline-bearing report filename is rejected before writing"
else
    bad "N6g newline-bearing report filename" "rc=$NEWLINE_RC or output was created"
fi
rm -f "$NEWLINE_REPORT" "$NEWLINE_REPORT.sha256"

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

printf 'runtime drift\n' >> "$RUNTIME_TREE/dependency.bin"
if run_checker "$MANIFEST" "$WORK/runtime-tree-drift.json" audit; then
    bad "N7a frozen runtime tree" "mutated support tree was accepted"
else
    [ ! -e "$WORK/adb-called" ] &&
        grep -q 'tool:aapt:fixture-runtime:tree-sha256' "$WORK/runtime-tree-drift.json" &&
        ok "N7a runtime-tree drift is rejected before inspector execution" ||
        bad "N7a frozen runtime tree" "inspector ran or tree finding is missing"
fi
printf 'fixture runtime dependency\n' > "$RUNTIME_TREE/dependency.bin"

TOOL_EXEC_TRIPWIRE="$WORK/atomic-tool-wrapper-called"
TOOL_SOURCE_BACKUP="$WORK/aapt-source.backup"
TOOL_REPLACEMENT="$WORK/aapt-atomic-replacement"
cp -p "$TOOLS/aapt" "$TOOL_SOURCE_BACKUP"
cat > "$TOOL_REPLACEMENT" <<SH
#!/usr/bin/env bash
: > "$TOOL_EXEC_TRIPWIRE"
cp -p "$TOOL_SOURCE_BACKUP" "$WORK/aapt-restored"
mv "$WORK/aapt-restored" "$TOOLS/aapt"
payload="\$(cat "\$3")"
case "\$payload" in
  fixture-auto-apk*) printf "package: name='com.example.cellrebelauto' versionCode='1' versionName='1.0'\\n" ;;
  fixture-qwy-apk*) printf "package: name='name.caiyao.fakegps.bench' versionCode='8' versionName='3.0.0'\\n" ;;
  *) exit 2 ;;
esac
SH
chmod +x "$TOOL_REPLACEMENT"
if ATOMIC_REPLACE_TOOL_SOURCE="$TOOLS/aapt" \
   ATOMIC_REPLACE_TOOL_PAYLOAD="$TOOL_REPLACEMENT" \
   run_checker "$MANIFEST" "$WORK/atomic-tool-report.json" audit; then
    TOOL_RACE_RC=0
else
    TOOL_RACE_RC=$?
fi
if [ "$TOOL_RACE_RC" -eq 0 ] && [ -e "$TOOL_EXEC_TRIPWIRE" ] &&
   [ "$(json_value "$WORK/atomic-tool-report.json" executedDeviceCommands)" = 0 ]; then
    bad "N7a2 inspector atomic-replace race" \
        "replacement wrapper executed while report claimed zero device commands"
elif [ "$TOOL_RACE_RC" -ne 0 ] && [ ! -e "$TOOL_EXEC_TRIPWIRE" ] &&
     grep -q 'tool:aapt:source-stable' "$WORK/atomic-tool-report.json"; then
    ok "N7a2 inspector executes its trusted snapshot and rejects source drift"
else
    bad "N7a2 inspector atomic-replace race" \
        "expected fail-closed source drift with replacement wrapper untouched"
fi
cp -p "$TOOL_SOURCE_BACKUP" "$TOOLS/aapt"

TREE_REPLACEMENT="$WORK/runtime-dependency-atomic-replacement"
cp -p "$RUNTIME_TREE/dependency.bin" "$TREE_REPLACEMENT"
if ATOMIC_REPLACE_TREE_SOURCE="$RUNTIME_TREE/dependency.bin" \
   ATOMIC_REPLACE_TREE_PAYLOAD="$TREE_REPLACEMENT" \
   run_checker "$MANIFEST" "$WORK/atomic-tree-report.json" audit; then
    bad "N7a3 support-tree atomic-replace race" \
        "byte-identical new inode bypassed the final tool-tree seal"
else
    grep -q 'tool:aapt:source-stable' "$WORK/atomic-tree-report.json" &&
        ok "N7a3 private tool closure runs while final tree identity drift fails closed" ||
        bad "N7a3 support-tree atomic-replace race" "source-stable finding missing"
fi
printf 'fixture runtime dependency\n' > "$RUNTIME_TREE/dependency.bin"

SUPPORT_EXEC_TRIPWIRE="$WORK/atomic-support-file-called"
SUPPORT_SOURCE_BACKUP="$WORK/apksigner-support.backup"
SUPPORT_REPLACEMENT="$WORK/apksigner-support-atomic-replacement"
cp -p "$TOOLS/apksigner-support" "$SUPPORT_SOURCE_BACKUP"
cat > "$SUPPORT_REPLACEMENT" <<SH
: > "$SUPPORT_EXEC_TRIPWIRE"
FIXTURE_SUPPORT_VALUE=trusted
SH
if ATOMIC_REPLACE_SUPPORT_SOURCE="$TOOLS/apksigner-support" \
   ATOMIC_REPLACE_SUPPORT_PAYLOAD="$SUPPORT_REPLACEMENT" \
   run_checker "$MANIFEST" "$WORK/atomic-support-report.json" audit; then
    bad "N7a4 support-file atomic-replace race" \
        "shared replacement bypassed the final support-file seal"
elif [ ! -e "$SUPPORT_EXEC_TRIPWIRE" ] &&
     grep -q 'tool:apksigner:source-stable' "$WORK/atomic-support-report.json" &&
     python3 - "$WORK/atomic-support-report.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1], encoding="utf-8"))
commands = [
    command for command in report["directCommandEvidence"]["commands"]
    if command["inspectorId"] == "apksigner"
]
assert commands
assert all(command["spawned"] and command["returnCode"] == 0 for command in commands)
assert all(
    command["arguments"][:2]
    == ["--fixture-prefix", "$TOOL_SNAPSHOT:fixture-apksigner-support"]
    for command in commands
)
PY
then
    ok "N7a4 private support file is consumed while shared source drift fails closed"
else
    bad "N7a4 support-file atomic-replace race" \
        "replacement ran, private dependency failed, or source-stable finding is missing"
fi
cp -p "$SUPPORT_SOURCE_BACKUP" "$TOOLS/apksigner-support"

TRANSPORT_FIXTURE="$WORK/transport-fixture"
TRANSPORT_TRIPWIRE="$WORK/transport-fixture-called"
mkdir -p "$TRANSPORT_FIXTURE"
cat > "$TRANSPORT_FIXTURE/adb" <<SH
#!/bin/sh
: > "$TRANSPORT_TRIPWIRE"
SH
chmod +x "$TRANSPORT_FIXTURE/adb"
if python3 - "$PROD" "$TRANSPORT_FIXTURE/adb" "$TRANSPORT_TRIPWIRE" <<'PY'
import hashlib
import importlib.util
from pathlib import Path
import sys

checker = Path(sys.argv[1])
executable = Path(sys.argv[2])
tripwire = Path(sys.argv[3])
spec = importlib.util.spec_from_file_location("github64_transport_deny", checker)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
digest = hashlib.sha256(executable.read_bytes()).hexdigest()
policy = module.InspectorPolicy(
    inspector_id="aapt",
    version="transport-deny-fixture",
    executable=module.FrozenFile(
        role="executable", path=executable, sha256=digest, executable=True
    ),
)
prepared = module.PreparedInspector(
    policy=policy,
    executable_path=executable,
    support_files=(),
    support_trees=(),
    arguments_prefix=(),
    environment=(("PATH", "/usr/bin:/bin"),),
)
audit = module.Audit()
assert module.run_inspector(audit, prepared, [], "fixture:transport-deny") is None
assert not tripwire.exists()
assert len(audit.commands) == 1
assert audit.commands[0]["classification"] == "DEVICE_TRANSPORT"
assert audit.commands[0]["spawned"] is False
assert audit.executed_device_commands == 0
assert audit.findings[-1]["status"] == "FAIL"
PY
then
    ok "N7a5 device-transport classification denies dispatch before side effects"
else
    bad "N7a5 device-transport pre-dispatch deny" \
        "transport-classified fixture ran or was not recorded as denied"
fi

UTF8_AAPT_BACKUP="$WORK/aapt-before-invalid-utf8"
UTF8_AAPT_SHA="$AAPT_TOOL_SHA"
cp -p "$TOOLS/aapt" "$UTF8_AAPT_BACKUP"
cat > "$TOOLS/aapt" <<'SH'
#!/bin/sh
printf '\377'
exit 0
SH
chmod +x "$TOOLS/aapt"
AAPT_TOOL_SHA="$(shasum -a 256 "$TOOLS/aapt" | awk '{print $1}')"
if run_checker "$MANIFEST" "$WORK/invalid-inspector-utf8.json" audit; then
    bad "N7a6 invalid inspector UTF-8" "undecodable output produced host PASS"
elif grep -q 'artifact:auto:aapt:output-encoding' \
        "$WORK/invalid-inspector-utf8.json" &&
     python3 - "$WORK/invalid-inspector-utf8.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1], encoding="utf-8"))
commands = [
    command for command in report["directCommandEvidence"]["commands"]
    if command["inspectorId"] == "aapt"
]
assert commands
assert all(command["spawned"] for command in commands)
assert all(command["returnCode"] == 0 for command in commands)
assert all(command["outputUtf8"] is False for command in commands)
PY
then
    ok "N7a6 post-spawn decode failure remains truthfully recorded"
else
    bad "N7a6 invalid inspector UTF-8" \
        "spawn/outcome evidence or encoding finding is missing"
fi
cp -p "$UTF8_AAPT_BACKUP" "$TOOLS/aapt"
AAPT_TOOL_SHA="$UTF8_AAPT_SHA"

if python3 - "$PROD" "$FIXTURE" <<'PY'
import importlib.util
import os
from pathlib import Path
import sys

checker = Path(sys.argv[1])
repo = Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("github64_cleanup_retry", checker)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
registry = module.SourceSealRegistry(repo)
tracked = os.open(repo / "base.txt", os.O_RDONLY)
registry.track_descriptor(tracked)
original_close = module.os.close
failed_once = False

def close_with_one_failure(descriptor):
    global failed_once
    if descriptor == tracked and not failed_once:
        failed_once = True
        raise OSError("fixture close failure")
    return original_close(descriptor)

module.os.close = close_with_one_failure
try:
    try:
        registry.close()
    except OSError as exc:
        assert "fixture close failure" in str(exc)
    else:
        raise AssertionError("close failure was swallowed")
finally:
    module.os.close = original_close
assert tracked in registry._held_descriptors
assert not registry._closed
assert registry._repo_descriptor_closed
registry.close()
assert registry._closed
assert not registry._held_descriptors
PY
then
    ok "N7a7 descriptor cleanup failures propagate and remain retryable"
else
    bad "N7a7 descriptor cleanup evidence" \
        "close failure was swallowed or failed descriptor was discarded"
fi

if python3 - "$PROD" "$FIXTURE" "$WORK/pre-registration-copy" <<'PY'
import importlib.util
from pathlib import Path
import sys

checker = Path(sys.argv[1])
repo = Path(sys.argv[2])
destination = Path(sys.argv[3])
spec = importlib.util.spec_from_file_location("github64_early_fd_owner", checker)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
registry = module.SourceSealRegistry(repo)
original_close = module.os.close
failed_descriptor = [None]

def close_with_persistent_first_failure(descriptor):
    if failed_descriptor[0] is None:
        failed_descriptor[0] = descriptor
    if descriptor == failed_descriptor[0]:
        raise OSError("fixture pre-registration close failure")
    return original_close(descriptor)

module.os.close = close_with_persistent_first_failure
try:
    try:
        registry.capture_file_source(repo / "base.txt", destination)
    except OSError as exc:
        assert "pre-registration close failure" in str(exc)
    else:
        raise AssertionError("pre-registration close failure was swallowed")
finally:
    module.os.close = original_close
assert failed_descriptor[0] in registry._held_descriptors
assert not registry._closed
registry.close()
assert registry._closed
assert not registry._held_descriptors
PY
then
    ok "N7a8 newly opened descriptors have cleanup ownership before capture"
else
    bad "N7a8 pre-registration descriptor ownership" \
        "early close failure leaked outside retryable cleanup state"
fi

FSMONITOR_HELPER="$WORK/fsmonitor-tripwire.sh"
FSMONITOR_TRIPWIRE="$WORK/fsmonitor-called"
printf '#!/bin/sh\n: > "%s"\nexit 0\n' "$FSMONITOR_TRIPWIRE" > "$FSMONITOR_HELPER"
chmod +x "$FSMONITOR_HELPER"
git -C "$FIXTURE" config core.fsmonitor "$FSMONITOR_HELPER"
if run_checker "$MANIFEST" "$WORK/fsmonitor-report.json" audit; then
    bad "N7b Git fsmonitor helper policy" "repository helper config was accepted"
else
    [ ! -e "$FSMONITOR_TRIPWIRE" ] &&
        grep -q 'source:git:external-helper-policy' "$WORK/fsmonitor-report.json" &&
        ok "N7b repository fsmonitor helper is rejected before execution" ||
        bad "N7b Git fsmonitor helper policy" "helper ran or policy finding is missing"
fi
git -C "$FIXTURE" config --unset core.fsmonitor

FILTER_HELPER="$WORK/filter-tripwire.sh"
FILTER_TRIPWIRE="$WORK/filter-called"
printf '#!/bin/sh\n: > "%s"\n/bin/cat\n' "$FILTER_TRIPWIRE" > "$FILTER_HELPER"
chmod +x "$FILTER_HELPER"
printf 'candidate.txt filter=tripwire\n' > "$FIXTURE/.git/info/attributes"
git -C "$FIXTURE" config filter.tripwire.clean "$FILTER_HELPER"
git -C "$FIXTURE" config filter.tripwire.required true
printf 'force worktree hashing\n' >> "$FIXTURE/candidate.txt"
if run_checker "$MANIFEST" "$WORK/filter-report.json" audit; then
    bad "N7c Git clean-filter helper policy" "repository helper config was accepted"
else
    [ ! -e "$FILTER_TRIPWIRE" ] &&
        grep -q 'source:git:external-helper-policy' "$WORK/filter-report.json" &&
        ok "N7c repository clean filter is rejected before execution" ||
        bad "N7c Git clean-filter helper policy" "helper ran or policy finding is missing"
fi
git -C "$FIXTURE" config --unset-all filter.tripwire.clean
git -C "$FIXTURE" config --unset-all filter.tripwire.required
rm -f "$FIXTURE/.git/info/attributes"
git -C "$FIXTURE" checkout -q -- candidate.txt

git -C "$FIXTURE" update-index --assume-unchanged candidate.txt
printf 'hidden by assume-unchanged\n' >> "$FIXTURE/candidate.txt"
if run_checker "$MANIFEST" "$WORK/assume-unchanged-report.json" audit; then
    bad "N7d assume-unchanged source drift" "index flag hid modified tracked bytes"
else
    grep -q 'source:checkout-tree' "$WORK/assume-unchanged-report.json" &&
        ok "N7d raw tree comparison rejects assume-unchanged drift" ||
        bad "N7d assume-unchanged source drift" "raw checkout-tree finding is missing"
fi
git -C "$FIXTURE" update-index --no-assume-unchanged candidate.txt
git -C "$FIXTURE" checkout -q -- candidate.txt

git -C "$FIXTURE" update-index --skip-worktree candidate.txt
printf 'hidden by skip-worktree\n' >> "$FIXTURE/candidate.txt"
if run_checker "$MANIFEST" "$WORK/skip-worktree-report.json" audit; then
    bad "N7e skip-worktree source drift" "index flag hid modified tracked bytes"
else
    grep -q 'source:checkout-tree' "$WORK/skip-worktree-report.json" &&
        ok "N7e raw tree comparison rejects skip-worktree drift" ||
        bad "N7e skip-worktree source drift" "raw checkout-tree finding is missing"
fi
git -C "$FIXTURE" update-index --no-skip-worktree candidate.txt
git -C "$FIXTURE" checkout -q -- candidate.txt

INFO_EXCLUDE="$FIXTURE/.git/info/exclude"
cp -p "$INFO_EXCLUDE" "$WORK/info-exclude.backup"
printf '\nhidden-by-info-exclude.txt\n' >> "$INFO_EXCLUDE"
printf 'untracked but ignored by repository metadata\n' > "$FIXTURE/hidden-by-info-exclude.txt"
if run_checker "$MANIFEST" "$WORK/info-exclude-report.json" audit; then
    bad "N7f info/exclude source drift" "repository exclude hid an untracked file"
else
    grep -q 'source:checkout-tree' "$WORK/info-exclude-report.json" &&
        ok "N7f raw tree comparison ignores info/exclude and rejects extra files" ||
        bad "N7f info/exclude source drift" "raw checkout-tree finding is missing"
fi
rm -f "$FIXTURE/hidden-by-info-exclude.txt"
cp -p "$WORK/info-exclude.backup" "$INFO_EXCLUDE"

printf '%s %s\n' "$PRODUCT_HEAD" "$BASE_HEAD" > "$FIXTURE/.git/info/grafts"
if run_checker "$MANIFEST" "$WORK/grafts-report.json" audit; then
    bad "N7g Git graft metadata" "mutable graft metadata was accepted"
else
    grep -q 'source:git:grafts-policy' "$WORK/grafts-report.json" &&
        ok "N7g Git graft metadata fails closed before graph inspection" ||
        bad "N7g Git graft metadata" "specific grafts-policy finding is missing"
fi
rm -f "$FIXTURE/.git/info/grafts"

git -C "$FIXTURE" config core.fileMode false
chmod 455 "$FIXTURE/tracked-executable"
if run_checker "$MANIFEST" "$WORK/owner-executable-mode-report.json" audit; then
    bad "N7h owner-executable mode drift" "group/other exec hid missing owner-exec"
else
    grep -q 'source:checkout-tree' "$WORK/owner-executable-mode-report.json" &&
        ok "N7h Git executable semantics require the owner-exec bit" ||
        bad "N7h owner-executable mode drift" "raw checkout-tree finding is missing"
fi
chmod 755 "$FIXTURE/tracked-executable"
git -C "$FIXTURE" config core.fileMode true

chmod +x "$FIXTURE/candidate.txt"
if run_checker "$MANIFEST" "$WORK/executable-mode-report.json" audit; then
    bad "N7i executable-mode drift" "filesystem mode no longer matched the HEAD tree"
else
    grep -q 'source:checkout-tree' "$WORK/executable-mode-report.json" &&
        ok "N7i raw tree comparison rejects executable-mode drift" ||
        bad "N7i executable-mode drift" "raw checkout-tree finding is missing"
fi
chmod -x "$FIXTURE/candidate.txt"

rm "$FIXTURE/tracked-link"
ln -s candidate.txt "$FIXTURE/tracked-link"
if run_checker "$MANIFEST" "$WORK/symlink-target-report.json" audit; then
    bad "N7j tracked symlink drift" "changed symlink payload matched the HEAD tree"
else
    grep -q 'source:checkout-tree' "$WORK/symlink-target-report.json" &&
        ok "N7j raw tree comparison rejects tracked symlink-target drift" ||
        bad "N7j tracked symlink drift" "raw checkout-tree finding is missing"
fi
rm "$FIXTURE/tracked-link"
ln -s base.txt "$FIXTURE/tracked-link"

printf 'mutable generated content\n' > \
    "$FIXTURE/apps/cellrebel-auto/app/build/generated.tmp"
if run_checker "$MANIFEST" "$WORK/generated-content-report.json" audit; then
    ok "N7k content beneath an exact frozen generated root remains allowed"
else
    bad "N7k frozen generated root" "ordinary generated content invalidated the package"
fi
rm -f "$FIXTURE/apps/cellrebel-auto/app/build/generated.tmp"

AUTO_BUILD_ROOT="$FIXTURE/apps/cellrebel-auto/app/build"
ESCAPED_AUTO_BUILD="$WORK/escaped-auto-build"
mv "$AUTO_BUILD_ROOT" "$WORK/auto-build.backup"
mkdir -p "$ESCAPED_AUTO_BUILD/outputs/apk/debug"
printf 'fixture-auto-apk\n' > "$ESCAPED_AUTO_BUILD/outputs/apk/debug/app-debug.apk"
ln -s "$ESCAPED_AUTO_BUILD" "$AUTO_BUILD_ROOT"
if run_checker "$MANIFEST" "$WORK/generated-root-symlink-report.json" audit; then
    bad "N7l symlinked generated root" "generated root escaped the source repository"
else
    grep -q 'source:checkout-tree' "$WORK/generated-root-symlink-report.json" &&
        ok "N7l a frozen generated root must be a real in-repo directory" ||
        bad "N7l symlinked generated root" "raw checkout-tree finding is missing"
fi
rm "$AUTO_BUILD_ROOT"
mv "$WORK/auto-build.backup" "$AUTO_BUILD_ROOT"

python3 - "$MANIFEST" "$WORK/widened-generated-roots.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
d["candidate"]["allowedGeneratedRoots"].append("caller-selected-generated")
json.dump(d, open(sys.argv[2], "w", encoding="utf-8"), indent=2, sort_keys=True)
PY
if run_checker "$WORK/widened-generated-roots.json" \
    "$WORK/widened-generated-roots-report.json" audit; then
    bad "N7m generated-root allowlist widening" "caller widened the frozen root set"
else
    grep -q 'candidate:allowed-generated-roots' \
        "$WORK/widened-generated-roots-report.json" &&
        ok "N7m generated-root allowlist cannot be widened" ||
        bad "N7m generated-root allowlist widening" "specific policy finding is missing"
fi

git -C "$FIXTURE" config extensions.worktreeConfig true
if run_checker "$MANIFEST" "$WORK/worktree-extension-report.json" audit; then
    bad "N7n Git worktree-config extension" "ambiguous config scope was accepted"
else
    grep -q 'source:git:external-helper-policy' \
        "$WORK/worktree-extension-report.json" &&
        ok "N7n worktree-config extension fails closed before source inspection" ||
        bad "N7n Git worktree-config extension" "specific policy finding is missing"
fi
git -C "$FIXTURE" config --unset extensions.worktreeConfig

LINKED_CONFIG_REPO="$WORK/linked-config-worktree"
git -C "$FIXTURE" config extensions.worktreeConfig true
git -C "$FIXTURE" worktree add --detach -q "$LINKED_CONFIG_REPO" "$PRODUCT_HEAD"
mkdir -p "$LINKED_CONFIG_REPO/apps/cellrebel-auto/app/build/outputs/apk/debug"
mkdir -p "$LINKED_CONFIG_REPO/apps/qianwangyou/app/build/outputs/apk/debug"
printf 'fixture-auto-apk\n' > \
    "$LINKED_CONFIG_REPO/apps/cellrebel-auto/app/build/outputs/apk/debug/app-debug.apk"
printf 'fixture-qwy-apk\n' > \
    "$LINKED_CONFIG_REPO/apps/qianwangyou/app/build/outputs/apk/debug/app-debug.apk"
LINKED_CONFIG_GITDIR="$(git -C "$LINKED_CONFIG_REPO" rev-parse --absolute-git-dir)"
mkdir -p "$LINKED_CONFIG_GITDIR/info"
printf 'candidate.txt filter=tripwire\n' > "$LINKED_CONFIG_GITDIR/info/attributes"
git -C "$LINKED_CONFIG_REPO" config --worktree filter.tripwire.clean "$FILTER_HELPER"
git -C "$LINKED_CONFIG_REPO" config --worktree filter.tripwire.required true
printf 'force linked-worktree hashing\n' >> "$LINKED_CONFIG_REPO/candidate.txt"
rm -f "$FILTER_TRIPWIRE"
if run_checker "$MANIFEST" "$WORK/worktree-config-report.json" audit \
    "$TOOLS/aapt" "$TOOLS/apksigner" \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    "$LINKED_CONFIG_REPO"; then
    bad "N7o Git worktree-scoped helper policy" "worktree helper config was accepted"
else
    [ ! -e "$FILTER_TRIPWIRE" ] &&
        grep -q 'source:git:external-helper-policy' "$WORK/worktree-config-report.json" &&
        ok "N7o worktree config is rejected before its helper can execute" ||
        bad "N7o Git worktree-scoped helper policy" "helper ran or policy finding is missing"
fi
git -C "$FIXTURE" worktree remove --force "$LINKED_CONFIG_REPO"
git -C "$FIXTURE" config --unset extensions.worktreeConfig

if python3 - "$PROD" "$WORK/gitlink-fixture" <<'PY'
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("github64_gitlink_test", Path(sys.argv[1]))
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
root = Path(sys.argv[2])
root.mkdir()
(root / "module").mkdir()
clean, details = module.compare_checkout_to_tree(
    root,
    {"module": module.GitTreeEntry("160000", "commit", "0" * 40)},
    frozenset(),
)
assert not clean
assert details["unsupported"]["paths"] == ["module"]
assert details["mismatched"]["paths"] == ["module"]
PY
then
    ok "N7p gitlinks are unsupported and fail closed"
else
    bad "N7p gitlink policy" "synthetic 160000 entry was not rejected"
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

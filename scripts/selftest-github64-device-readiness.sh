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
printf '**/build/\n' > "$FIXTURE/.gitignore"
git -C "$FIXTURE" add candidate.txt contract.yaml schedule.json .gitignore
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
printf 'Signer #1 certificate SHA-256 digest: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'
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
AUTO_SIZE="$(stat -f '%z' "$AUTO_APK" 2>/dev/null || stat -c '%s' "$AUTO_APK")"
QWY_SIZE="$(stat -f '%z' "$QWY_APK" 2>/dev/null || stat -c '%s' "$QWY_APK")"

PRODUCT_HEAD="$PRODUCT_HEAD" PRODUCT_TREE="$PRODUCT_TREE" BASE_HEAD="$BASE_HEAD" \
AUTO_SHA="$AUTO_SHA" QWY_SHA="$QWY_SHA" AUTO_SIZE="$AUTO_SIZE" QWY_SIZE="$QWY_SIZE" \
CONTRACT_SHA="$CONTRACT_SHA" SCHEDULE_SHA="$SCHEDULE_SHA" python3 - "$MANIFEST" <<'PY'
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
            {"id": "G2-HARNESS-SCHEMA-001", "scope": ["HOOK"]},
            {"id": "G2-HARNESS-LEASE-002", "scope": ["ALL_DEVICE"]},
            {"id": "G2-HARNESS-EVIDENCE-003", "scope": ["ALL_DEVICE"]},
            {"id": "G2-PR62-CHANGES-REQUESTED-004", "scope": ["A", "B", "C", "G"]},
            {"id": "G2-PR63-PRINCIPAL-ROUTING-005", "scope": ["A", "B", "C"]},
            {"id": "G2-ISSUE66-CONTINUITY-006", "scope": ["A", "B", "TRUSTED_QUOTA"]},
        ],
    },
}
with open(sys.argv[1], "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, indent=2, sort_keys=True)
    fh.write("\n")
PY

run_checker() {
    local manifest=$1 report=$2 mode=${3:-audit}
    local -a args=(
        --manifest "$manifest"
        --source-repo "$FIXTURE"
        --report "$report"
        --aapt "$TOOLS/aapt"
        --apksigner "$TOOLS/apksigner"
    )
    [ "$mode" = audit ] || args+=(--require-device-ready)
    ADB_TRIPWIRE="$WORK/adb-called" PATH="$TOOLS:$PATH" "$PROD" "${args[@]}" >/dev/null 2>&1
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
d = json.load(open(sys.argv[1], encoding="utf-8"))
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

if run_checker "$MANIFEST" "$WORK/required.json" require-ready; then
    bad "P2 require-device-ready fails closed" "blocked package returned zero"
else
    rc=$?
    [ "$rc" -eq 3 ] && ok "P2 require-device-ready returns the documented blocked code" ||
        bad "P2 require-device-ready fails closed" "expected rc=3, got rc=$rc"
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

cat > "$TOOLS/bad-apksigner" <<'SH'
#!/usr/bin/env bash
printf 'Signer #1 certificate SHA-256 digest: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n'
SH
chmod +x "$TOOLS/bad-apksigner"
if ADB_TRIPWIRE="$WORK/adb-called" PATH="$TOOLS:$PATH" "$PROD" \
    --manifest "$MANIFEST" --source-repo "$FIXTURE" --report "$WORK/signer.json" \
    --aapt "$TOOLS/aapt" --apksigner "$TOOLS/bad-apksigner" >/dev/null 2>&1; then
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

printf '\nselftest-github64-device-readiness: %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]

#!/usr/bin/env bash
# Device-free RED matrix for the static issue #66 services.jar compatibility
# checker. It creates synthetic ZIP/JAR files whose classes*.dex entries are
# consumed only by the checked-in fake dexdump fixture.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
CHECKER="$HERE/check-issue66-services-compatibility.sh"
FIXTURE_DIR="$HERE/fixtures/issue66-services-compatibility"
MEMBERS="$FIXTURE_DIR/required-members.tsv"
APPROVED_DEXDUMP_DIGESTS="$FIXTURE_DIR/approved-dexdump-sha256.tsv"
FAKE_DEXDUMP="$FIXTURE_DIR/fake-dexdump.sh"
HOOK_PLAN="$REPO_ROOT/apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlan.java"
INSTALLER="$REPO_ROOT/apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java"

pass=0
fail=0

report() { # ok|fail name [detail]
  if [ "$1" = ok ]; then
    printf 'ok   %s\n' "$2"
    pass=$((pass + 1))
  else
    printf 'FAIL %s :: %s\n' "$2" "${3:-unspecified failure}"
    fail=$((fail + 1))
  fi
}

for fixture in \
    "$MEMBERS" \
    "$APPROVED_DEXDUMP_DIGESTS" \
    "$FAKE_DEXDUMP" \
    "$HOOK_PLAN" \
    "$INSTALLER"; do
  if [ ! -f "$fixture" ]; then
    printf 'selftest fixture/source missing: %s\n' "$fixture" >&2
    exit 2
  fi
done
if [ ! -x "$FAKE_DEXDUMP" ]; then
  printf 'selftest fake dexdump is not executable: %s\n' "$FAKE_DEXDUMP" >&2
  exit 2
fi

# Keep the fixture bound to the production hook plan instead of letting a
# duplicated class/method inventory silently drift.
assert_fixture_source_binding() { # hook-plan installer members
python3 - "$1" "$2" "$3" <<'PY'
import re
import sys

plan_path, installer_path, members_path = sys.argv[1:]
plan = open(plan_path, encoding="utf-8").read()
installer = open(installer_path, encoding="utf-8").read()

def scalar(name):
    match = re.search(rf"\b{name}\s*=\s*\"([^\"]+)\"\s*;", plan, re.S)
    if not match:
        raise AssertionError(f"missing scalar {name}")
    return match.group(1)

def array(name):
    match = re.search(rf"\b{name}\s*=\s*\{{(.*?)\}}\s*;", plan, re.S)
    if not match:
        raise AssertionError(f"missing array {name}")
    return re.findall(r'"([^"]+)"', match.group(1))

expected = set()
def add(class_constant, methods):
    class_name = scalar(class_constant)
    expected.update((class_name, method) for method in methods)

add("APP_OPS_WRAPPER_CLASS", array("APP_OPS_WRAPPER_MUTATION_METHODS"))
add("ACCESS_CHECKING_DELEGATE_CLASS", array("ACCESS_CHECKING_MUTATION_METHODS"))
add("ACCESS_CHECKING_LIFECYCLE_CLASS", array("ACCESS_CHECKING_LIFECYCLE_METHODS"))
add("LOCATION_PROVIDER_MANAGER_CLASS", array("LOCATION_MUTATION_METHODS"))
add("LOCATION_MOCK_PROVIDER_CLASS", [scalar("LOCATION_SEMANTIC_MUTATION_METHOD")])
add(
    "LOCATION_MANAGER_SERVICE_CLASS",
    array("LOCATION_QWY_MUTATION_ENTRY_METHODS")
    + [scalar("LOCATION_QWY_PROVENANCE_ENTRY_METHOD")],
)
system_service_manager = scalar("SYSTEM_SERVICE_MANAGER_CLASS")

method_start = re.search(
    r"\bprivate\s+static\s+void\s+installPhase600Bridge\s*\([^)]*\)\s*\{",
    installer,
)
if not method_start:
    raise AssertionError("missing installPhase600Bridge method")
depth = 1
cursor = method_start.end()
while cursor < len(installer) and depth:
    if installer[cursor] == "{":
        depth += 1
    elif installer[cursor] == "}":
        depth -= 1
    cursor += 1
if depth:
    raise AssertionError("unterminated installPhase600Bridge method")
bridge_body = installer[method_start.end():cursor - 1]
bridge_body = re.sub(r"/\*.*?\*/|//[^\n]*", "", bridge_body, flags=re.S)
manager_binding = re.compile(
    r"\bmanager\s*=\s*XposedHelpers\s*\.\s*findClass\s*\(\s*"
    r"Android15OracleHookPlan\s*\.\s*SYSTEM_SERVICE_MANAGER_CLASS\s*,\s*loader\s*\)\s*;",
    re.S,
)
boot_phase_hook = re.compile(
    r"\bXposedBridge\s*\.\s*hookAllMethods\s*\(\s*manager\s*,\s*"
    r'"startBootPhase"\s*,',
    re.S,
)
if not manager_binding.search(bridge_body):
    raise AssertionError("installPhase600Bridge does not resolve SYSTEM_SERVICE_MANAGER_CLASS")
if not boot_phase_hook.search(bridge_body):
    raise AssertionError(
        "installPhase600Bridge does not pass startBootPhase to hookAllMethods"
    )
expected.add((system_service_manager, "startBootPhase"))

actual = set()
with open(members_path, encoding="utf-8") as members_file:
    for line_number, raw in enumerate(members_file, 1):
        line = raw.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 2 or not all(parts):
            raise AssertionError(f"invalid members row {line_number}: {line!r}")
        pair = tuple(parts)
        if pair in actual:
            raise AssertionError(f"duplicate members row: {pair!r}")
        actual.add(pair)

assert len({class_name for class_name, _ in actual}) == 7, actual
assert len(actual) == 20, actual
if actual != expected:
    raise AssertionError(
        f"fixture/plan mismatch missing={sorted(expected - actual)!r} "
        f"extra={sorted(actual - expected)!r}"
    )
PY
}

if ! assert_fixture_source_binding "$HOOK_PLAN" "$INSTALLER" "$MEMBERS"; then
  printf 'selftest class/method fixture drifted from Android15OracleHookPlan\n' >&2
  exit 2
fi

# Credible TDD RED: all fixtures and their source binding are valid before the
# missing production checker is reported.
if [ ! -f "$CHECKER" ]; then
  printf 'RED: services compatibility checker missing: %s\n' "$CHECKER" >&2
  printf 'RED reason: the device-free matrix has no production implementation yet.\n' >&2
  exit 1
fi
if [ ! -x "$CHECKER" ]; then
  printf 'services compatibility checker is not executable: %s\n' "$CHECKER" >&2
  exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/issue66-services-compat-selftest.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
chmod 700 "$WORK"
DEXDUMP_LOG="$WORK/dexdump.log"

# Prove that the source-binding guard follows the actual hookAllMethods call,
# not the later NoSuchMethodException text which also names startBootPhase.
MUTATED_INSTALLER="$WORK/installer-with-lookalike-error-only.java"
if ! python3 - "$INSTALLER" "$MUTATED_INSTALLER" <<'PY'
import re
import sys

source_path, output_path = sys.argv[1:]
source = open(source_path, encoding="utf-8").read()
mutated, count = re.subn(
    r'(XposedBridge\s*\.\s*hookAllMethods\s*\(\s*manager\s*,\s*)'
    r'"startBootPhase"',
    r'\1"startBootPhaseLookalike"',
    source,
    count=1,
    flags=re.S,
)
assert count == 1, f"expected one startBootPhase hook call, mutated {count}"
assert 'NoSuchMethodException("SystemServiceManager#startBootPhase")' in mutated
open(output_path, "w", encoding="utf-8").write(mutated)
PY
then
  report fail "source-binding mutation fixture is constructible" "could not mutate hook call"
elif assert_fixture_source_binding "$HOOK_PLAN" "$MUTATED_INSTALLER" "$MEMBERS" \
    >/dev/null 2>&1; then
  report fail "source binding rejects error-message-only startBootPhase lookalike" \
    "guard accepted installer after the real hookAllMethods target was changed"
else
  report ok "source binding rejects error-message-only startBootPhase lookalike"
fi

# The wrapper participates only in TOCTOU tests. The fake dexdump arms a
# replacement worker; the first digest read after that invocation releases it,
# waits for the atomic replacement, and hashes the bytes now at the input path.
HASH_GATE_DIR="$WORK/hash-gate"
mkdir -p "$HASH_GATE_DIR"
cp -- "$FAKE_DEXDUMP" "$HASH_GATE_DIR/shasum"
chmod 700 "$HASH_GATE_DIR/shasum"

make_jar() { # jar mode [class] [method]
  local jar="$1" mode="$2" class_name="${3:-}" method_name="${4:-}"
  python3 - "$MEMBERS" "$jar" "$mode" "$class_name" "$method_name" <<'PY'
import collections
import sys
import zipfile

members_path, jar_path, mode, omitted_class, omitted_method = sys.argv[1:]
pairs = []
with open(members_path, encoding="utf-8") as members_file:
    for raw in members_file:
        line = raw.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        class_name, method_name = line.split("\t")
        if mode == "missing-class" and class_name == omitted_class:
            # Keep a prefix lookalike so substring-only descriptor checks fail.
            class_name = class_name + "Extra"
        if (
            mode == "missing-method"
            and class_name == omitted_class
            and method_name == omitted_method
        ):
            # Keep a prefix lookalike and preserve duplicate names in other
            # classes so class/method association and exactness both carry.
            method_name = method_name + "Extra"
        pairs.append((class_name, method_name))

grouped = collections.OrderedDict()
for class_name, method_name in pairs:
    grouped.setdefault(class_name, []).append(method_name)

lines = []
for class_index, (class_name, methods) in enumerate(grouped.items()):
    descriptor = "L" + class_name.replace(".", "/") + ";"
    lines.extend([
        f"Class #{class_index}            -",
        f"  Class descriptor  : '{descriptor}'",
        "  Direct methods    -",
    ])
    for method_index, method_name in enumerate(methods):
        lines.extend([
            f"    #{method_index}              : (in {descriptor})",
            f"      name          : '{method_name}'",
        ])
payload = ("\n".join(lines) + "\n").encode("utf-8")
if mode == "dexdump-fail":
    payload = b"FAKE_DEXDUMP_EXIT=17\n"
elif mode == "empty-dex":
    payload = b""

with zipfile.ZipFile(jar_path, "w", compression=zipfile.ZIP_STORED) as archive:
    archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    if mode != "no-dex":
        archive.writestr("classes.dex", payload)
    if mode == "multi-dex":
        archive.writestr("classes2.dex", payload)
PY
}

OUT=""
RC=0
run_checker() { # services.jar dexdump output.json [checker]
  local jar="$1" dexdump="$2" output="$3" checker="${4:-$CHECKER}"
  : >"$DEXDUMP_LOG"
  OUT="$(
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
      "$checker" --services-jar "$jar" --dexdump "$dexdump" --output "$output" \
        --allow-pinned-selftest-fixture 2>&1
  )"
  RC=$?
}

run_checker_without_fixture_mode() { # services.jar dexdump output.json
  local jar="$1" dexdump="$2" output="$3"
  : >"$DEXDUMP_LOG"
  OUT="$(
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
      "$CHECKER" --services-jar "$jar" --dexdump "$dexdump" --output "$output" 2>&1
  )"
  RC=$?
}

run_checker_with_untrusted_sdk_env() { # services.jar dexdump output.json sdk-root
  local jar="$1" dexdump="$2" output="$3" sdk_root="$4"
  : >"$DEXDUMP_LOG"
  OUT="$(
    HOME="${sdk_root%/Library/Android/sdk}" \
    ANDROID_HOME="$sdk_root" \
    ANDROID_SDK_ROOT="$sdk_root" \
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
      "$CHECKER" --services-jar "$jar" --dexdump "$dexdump" --output "$output" 2>&1
  )"
  RC=$?
}

run_checker_with_output_swap() { # jar dexdump output mode victim state
  local jar="$1" dexdump="$2" output="$3" mode="$4" victim="$5" state="$6"
  : >"$DEXDUMP_LOG"
  rm -f -- "$state"
  OUT="$(
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
    FAKE_DEXDUMP_OUTPUT_SWAP_TARGET="$output" \
    FAKE_DEXDUMP_OUTPUT_SWAP_MODE="$mode" \
    FAKE_DEXDUMP_OUTPUT_SWAP_VICTIM="$victim" \
    FAKE_DEXDUMP_OUTPUT_SWAP_STATE="$state" \
      "$CHECKER" --services-jar "$jar" --dexdump "$dexdump" --output "$output" \
        --allow-pinned-selftest-fixture 2>&1
  )"
  RC=$?
}

run_checker_with_output_write_failure() { # jar dexdump output
  local jar="$1" dexdump="$2" output="$3"
  : >"$DEXDUMP_LOG"
  OUT="$(
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
      "$CHECKER" --services-jar "$jar" --dexdump "$dexdump" --output "$output" \
        --allow-pinned-selftest-fixture --selftest-output-write-failure 2>&1
  )"
  RC=$?
}

run_checker_with_after_analysis_swap() { # jar dexdump output target replacement state
  local jar="$1" dexdump="$2" output="$3" target="$4" replacement="$5" state="$6"
  : >"$DEXDUMP_LOG"
  rm -f -- "$state"
  OUT="$(
    PATH="$HASH_GATE_DIR:$PATH" \
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
    FAKE_DEXDUMP_AFTER_ANALYSIS_SWAP_TARGET="$target" \
    FAKE_DEXDUMP_AFTER_ANALYSIS_SWAP_REPLACEMENT="$replacement" \
    FAKE_DEXDUMP_AFTER_ANALYSIS_SWAP_STATE="$state" \
      "$CHECKER" --services-jar "$jar" --dexdump "$dexdump" --output "$output" \
        --allow-pinned-selftest-fixture 2>&1
  )"
  RC=$?
}

sha256_path() { # path
  python3 - "$1" <<'PY'
import hashlib
import sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
}

candidate_splices_digest() { # output-json digest-field replacement-digest
  python3 - "$1" "$2" "$3" <<'PY' >/dev/null 2>&1
import json
import sys

path, digest_field, replacement_digest = sys.argv[1:]
payload = json.load(open(path, encoding="utf-8"))
assert payload.get("status") in {
    "COMPATIBILITY_CANDIDATE",
    "SELFTEST_STATIC_MEMBERS_PRESENT",
}, payload
assert payload.get(digest_field) == replacement_digest, payload
PY
}

assert_non_authoritative_json() { # path expected-status [expected-reason]
  python3 - "$1" "$2" "${3:-}" <<'PY'
import json
import re
import sys

path, expected_status, expected_reason = sys.argv[1:]

def exact_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AssertionError(f"duplicate JSON key: {key}")
        result[key] = value
    return result

with open(path, encoding="utf-8") as output_file:
    payload = json.load(output_file, object_pairs_hook=exact_object)
assert type(payload) is dict, payload
common = {
    "schemaVersion",
    "status",
    "reason",
    "issue66Ac7",
    "deviceFull",
    "authority",
}
if expected_status == "STOP":
    expected_keys = common | {"missingClass", "missingMethod"}
elif expected_status in {
    "COMPATIBILITY_CANDIDATE",
    "SELFTEST_STATIC_MEMBERS_PRESENT",
}:
    expected_keys = common | {
        "requiredClassCount",
        "requiredMethodCount",
        "servicesJarSha256",
        "dexdumpSha256",
        "requiredMembersSha256",
        "approvedDexdumpAllowlistSha256",
        "checkerSha256",
        "dexdumpIdentity",
        "dexdumpBuildToolsRevision",
    }
else:
    raise AssertionError(f"unsupported expected status: {expected_status}")
assert set(payload) == expected_keys, {
    "missing": sorted(expected_keys - set(payload)),
    "extra": sorted(set(payload) - expected_keys),
    "payload": payload,
}
assert type(payload["schemaVersion"]) is int and payload["schemaVersion"] == 1, payload
assert payload["status"] == expected_status, payload
if expected_reason:
    assert payload["reason"] == expected_reason, payload
elif expected_status == "COMPATIBILITY_CANDIDATE":
    assert payload["reason"] == "EXACT_STATIC_MEMBERS_PRESENT", payload
elif expected_status == "SELFTEST_STATIC_MEMBERS_PRESENT":
    assert payload["reason"] == "PINNED_SELFTEST_FIXTURE_ONLY", payload
assert payload["issue66Ac7"] == "NOT_PASSED", payload
assert payload["deviceFull"] == "BLOCKED", payload
assert payload["authority"] == "NONE", payload

if expected_status == "STOP":
    assert type(payload["missingClass"]) is str, payload
    assert type(payload["missingMethod"]) is str, payload
else:
    assert type(payload["requiredClassCount"]) is int, payload
    assert type(payload["requiredMethodCount"]) is int, payload
    assert payload["requiredClassCount"] == 7, payload
    assert payload["requiredMethodCount"] == 20, payload
    if expected_status == "COMPATIBILITY_CANDIDATE":
        assert payload["dexdumpIdentity"] == "ANDROID_SDK_BUILD_TOOLS_NATIVE_APPROVED_SHA256", payload
        assert re.fullmatch(r"[0-9][0-9.rc-]*", payload["dexdumpBuildToolsRevision"]), payload
    else:
        assert payload["dexdumpIdentity"] == "PINNED_SELFTEST_FIXTURE", payload
        assert payload["dexdumpBuildToolsRevision"] == "SELFTEST", payload
    for digest_key in (
        "servicesJarSha256",
        "dexdumpSha256",
        "requiredMembersSha256",
        "approvedDexdumpAllowlistSha256",
        "checkerSha256",
    ):
        digest = payload[digest_key]
        assert type(digest) is str and re.fullmatch(r"[0-9a-f]{64}", digest), payload

def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for nested in value.values():
            yield from strings(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from strings(nested)

forbidden = {"PASS", "FULL", "HEALTHY", "ATTESTED"}
assert forbidden.isdisjoint(set(strings(payload))), payload
PY
}

expect_stop() { # name output-json reason
  local name="$1" output="$2" reason="$3" marker="STOP_$3"
  if [ "$RC" -eq 0 ]; then
    report fail "$name" "checker returned success; expected $marker; output=$OUT"
  elif [[ "$OUT" != *"$marker"* ]]; then
    report fail "$name" "missing exact marker $marker; rc=$RC output=$OUT"
  elif [ ! -f "$output" ]; then
    report fail "$name" "STOP output JSON missing: $output"
  elif ! assert_non_authoritative_json "$output" STOP "$reason"; then
    report fail "$name" "invalid or authoritative STOP JSON: $output"
  else
    report ok "$name"
  fi
}

expect_dexdump_not_called() { # name
  if [ -s "$DEXDUMP_LOG" ]; then
    report fail "$1" "dexdump ran before structural refusal: $(tr '\n' ';' <"$DEXDUMP_LOG")"
  else
    report ok "$1"
  fi
}

# Positive parser control: all seven exact classes and all twenty class/method
# associations are required. Because this invokes the pinned fake, its success
# is explicitly selftest-only and cannot be a production compatibility verdict.
GOOD_JAR="$WORK/services-good.jar"
GOOD_JSON="$WORK/good.json"
make_jar "$GOOD_JAR" positive
run_checker "$GOOD_JAR" "$FAKE_DEXDUMP" "$GOOD_JSON"
if [ "$RC" -ne 0 ]; then
  report fail "complete synthetic services.jar exercises the pinned selftest parser" "rc=$RC output=$OUT"
elif [[ "$OUT" != *"SELFTEST_STATIC_MEMBERS_PRESENT"* ]]; then
  report fail "complete synthetic services.jar exercises the pinned selftest parser" \
    "selftest marker missing: $OUT"
elif ! assert_non_authoritative_json "$GOOD_JSON" SELFTEST_STATIC_MEMBERS_PRESENT; then
  report fail "complete synthetic services.jar exercises the pinned selftest parser" \
    "invalid or authoritative JSON"
elif ! python3 - "$GOOD_JSON" <<'PY'
import json
import sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload["requiredClassCount"] == 7, payload
assert payload["requiredMethodCount"] == 20, payload
PY
then
  report fail "complete synthetic services.jar exercises the pinned selftest parser" \
    "required counts missing"
elif [ "$(wc -l <"$DEXDUMP_LOG" | tr -d ' ')" != 1 ]; then
  report fail "complete synthetic services.jar exercises the pinned selftest parser" \
    "dexdump must run exactly once"
else
  report ok "complete synthetic services.jar exercises the pinned selftest parser"
fi

# A caller-selected executable is not an Android SDK identity. Even when it
# produces perfectly shaped output, it must be refused before invocation.
ARBITRARY_DEXDUMP="$WORK/arbitrary-dexdump"
ARBITRARY_DEXDUMP_JSON="$WORK/arbitrary-dexdump.json"
cp -- "$FAKE_DEXDUMP" "$ARBITRARY_DEXDUMP"
chmod 700 "$ARBITRARY_DEXDUMP"
run_checker_without_fixture_mode \
  "$GOOD_JAR" "$ARBITRARY_DEXDUMP" "$ARBITRARY_DEXDUMP_JSON"
expect_stop "arbitrary caller-provided dexdump is refused" \
  "$ARBITRARY_DEXDUMP_JSON" UNTRUSTED_DEXDUMP
expect_dexdump_not_called "untrusted dexdump is refused before invocation"

TRUSTED_DEXDUMP=""
for sdk_root in \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    /usr/local/lib/android/sdk \
    /opt/android-sdk \
    /opt/android-sdk-linux; do
  for candidate in "$sdk_root"/build-tools/*/dexdump; do
    [ -f "$candidate" ] && [ -x "$candidate" ] || continue
    TRUSTED_DEXDUMP=$candidate
  done
done

# Caller-controlled SDK environment variables do not create a trust root. A
# forged build-tools layout with matching metadata remains untrusted.
FAKE_SDK_ROOT="$WORK/caller-home/Library/Android/sdk"
FAKE_SDK_DEXDUMP="$FAKE_SDK_ROOT/build-tools/35.0.0/dexdump"
FAKE_SDK_JSON="$WORK/caller-sdk.json"
mkdir -p "${FAKE_SDK_DEXDUMP%/*}"
cp -- "${TRUSTED_DEXDUMP:-$FAKE_DEXDUMP}" "$FAKE_SDK_DEXDUMP"
chmod 700 "$FAKE_SDK_DEXDUMP"
printf 'Pkg.UserSrc=false\nPkg.Revision=35.0.0\n' \
  >"${FAKE_SDK_DEXDUMP%/*}/source.properties"
run_checker_with_untrusted_sdk_env \
  "$GOOD_JAR" "$FAKE_SDK_DEXDUMP" "$FAKE_SDK_JSON" "$FAKE_SDK_ROOT"
expect_stop "caller-controlled Android SDK root cannot trust a fake dexdump" \
  "$FAKE_SDK_JSON" UNTRUSTED_DEXDUMP
expect_dexdump_not_called "caller-controlled SDK fake is refused before invocation"

# The explicit selftest lane is content-pinned. It cannot bless a modified
# copy and, even for the exact fixture, its earlier positive result has the
# distinct SELFTEST_STATIC_MEMBERS_PRESENT status.
MODIFIED_SELFTEST_DEXDUMP="$WORK/modified-selftest-dexdump"
MODIFIED_SELFTEST_JSON="$WORK/modified-selftest-dexdump.json"
cp -- "$FAKE_DEXDUMP" "$MODIFIED_SELFTEST_DEXDUMP"
printf '\n# modified fixture\n' >>"$MODIFIED_SELFTEST_DEXDUMP"
chmod 700 "$MODIFIED_SELFTEST_DEXDUMP"
run_checker "$GOOD_JAR" "$MODIFIED_SELFTEST_DEXDUMP" "$MODIFIED_SELFTEST_JSON"
expect_stop "modified dexdump cannot enter the pinned selftest lane" \
  "$MODIFIED_SELFTEST_JSON" UNTRUSTED_DEXDUMP
expect_dexdump_not_called "modified selftest dexdump is refused before invocation"

# A native dexdump in the conventional Android SDK path is still user-owned.
# Without a repo-approved digest it must stop before invocation rather than
# inheriting production authority from its pathname, metadata, or file magic.
if [ -z "$TRUSTED_DEXDUMP" ]; then
  report ok "unattested SDK dexdump probe skipped because no local SDK is installed"
else
  TRUSTED_DEXDUMP_JSON="$WORK/trusted-dexdump.json"
  run_checker_without_fixture_mode \
    "$GOOD_JAR" "$TRUSTED_DEXDUMP" "$TRUSTED_DEXDUMP_JSON"
  expect_stop "installed but unattested SDK dexdump cannot mint a candidate" \
    "$TRUSTED_DEXDUMP_JSON" TOOL_NOT_ATTESTED
fi

# The allowlist is itself content-pinned by the checker. Copying the checker
# beside a caller-expanded list must fail before the fixture is invoked.
TAMPERED_APPROVAL_DIR="$WORK/tampered-approval-checker"
TAMPERED_APPROVAL_CHECKER="$TAMPERED_APPROVAL_DIR/check-issue66-services-compatibility.sh"
TAMPERED_APPROVAL_FIXTURES="$TAMPERED_APPROVAL_DIR/fixtures/issue66-services-compatibility"
mkdir -p "$TAMPERED_APPROVAL_FIXTURES"
cp -- "$CHECKER" "$TAMPERED_APPROVAL_CHECKER"
cp -- "$MEMBERS" "$TAMPERED_APPROVAL_FIXTURES/required-members.tsv"
cp -- "$FIXTURE_DIR/approved-dexdump-sha256.tsv" \
  "$TAMPERED_APPROVAL_FIXTURES/approved-dexdump-sha256.tsv"
printf '35.0.0\t%s\n' "$(sha256_path "$FAKE_DEXDUMP")" \
  >>"$TAMPERED_APPROVAL_FIXTURES/approved-dexdump-sha256.tsv"
chmod 700 "$TAMPERED_APPROVAL_CHECKER"
TAMPERED_APPROVAL_JSON="$WORK/tampered-approval.json"
run_checker "$GOOD_JAR" "$FAKE_DEXDUMP" "$TAMPERED_APPROVAL_JSON" \
  "$TAMPERED_APPROVAL_CHECKER"
expect_stop "caller-expanded dexdump approval allowlist is refused" \
  "$TAMPERED_APPROVAL_JSON" APPROVED_DEXDUMP_ALLOWLIST_MISMATCH
expect_dexdump_not_called "tampered dexdump approval is refused before invocation"

# Replacing the reserved output pathname during analysis must neither redirect
# the result into an attacker-selected file nor launder a failed final write
# into exit 0.
OUTPUT_SWAP_VICTIM="$WORK/output-swap-victim.txt"
OUTPUT_SWAP_LINK="$WORK/output-swap-link.json"
OUTPUT_SWAP_LINK_STATE="$WORK/output-swap-link.state"
printf 'victim-sentinel\n' >"$OUTPUT_SWAP_VICTIM"
run_checker_with_output_swap \
  "$GOOD_JAR" "$FAKE_DEXDUMP" "$OUTPUT_SWAP_LINK" symlink \
  "$OUTPUT_SWAP_VICTIM" "$OUTPUT_SWAP_LINK_STATE"
if [ "$RC" -eq 0 ] || [[ "$OUT" != *"STOP_OUTPUT_CHANGED"* ]]; then
  report fail "reserved output symlink replacement is refused" \
    "rc=$RC output=$OUT"
else
  report ok "reserved output symlink replacement is refused"
fi
if [ "$(sed -n '1p' "$OUTPUT_SWAP_LINK_STATE" 2>/dev/null || true)" != swapped ]; then
  report fail "reserved output symlink replacement is deterministic" \
    "state=$(cat "$OUTPUT_SWAP_LINK_STATE" 2>/dev/null || printf missing)"
elif [ "$(cat "$OUTPUT_SWAP_VICTIM")" != victim-sentinel ]; then
  report fail "reserved output fd does not clobber symlink victim" \
    "victim bytes changed to $(cat "$OUTPUT_SWAP_VICTIM")"
else
  report ok "reserved output fd does not clobber symlink victim"
fi

OUTPUT_SWAP_DIRECTORY="$WORK/output-swap-directory.json"
OUTPUT_SWAP_DIRECTORY_STATE="$WORK/output-swap-directory.state"
run_checker_with_output_swap \
  "$GOOD_JAR" "$FAKE_DEXDUMP" "$OUTPUT_SWAP_DIRECTORY" directory "" \
  "$OUTPUT_SWAP_DIRECTORY_STATE"
if [ "$RC" -eq 0 ] || [[ "$OUT" != *"STOP_OUTPUT_CHANGED"* ]]; then
  report fail "failed final pathname publication cannot return success" \
    "rc=$RC output=$OUT"
elif [ ! -d "$OUTPUT_SWAP_DIRECTORY" ]; then
  report fail "failed final pathname publication cannot return success" \
    "fixture did not replace the reserved file with a directory"
else
  report ok "failed final pathname publication cannot return success"
fi

OUTPUT_WRITE_FAILURE_JSON="$WORK/output-write-failure.json"
run_checker_with_output_write_failure \
  "$GOOD_JAR" "$FAKE_DEXDUMP" "$OUTPUT_WRITE_FAILURE_JSON"
if [ "$RC" -eq 0 ] || [[ "$OUT" != *"STOP_OUTPUT_WRITE_FAILED"* ]]; then
  report fail "reserved-fd write failure cannot return success" \
    "rc=$RC output=$OUT"
elif [ ! -f "$OUTPUT_WRITE_FAILURE_JSON" ] || [ -L "$OUTPUT_WRITE_FAILURE_JSON" ]; then
  report fail "reserved-fd write failure preserves the original output inode" \
    "output is missing or became a symlink"
elif [ -s "$OUTPUT_WRITE_FAILURE_JSON" ]; then
  report fail "reserved-fd write failure leaves no candidate JSON" \
    "unexpected bytes=$(cat "$OUTPUT_WRITE_FAILURE_JSON")"
else
  report ok "reserved-fd write failure is fail-closed"
fi

# Production must authenticate the complete checked-in 7-class/20-method
# inventory itself. A standalone checker copied with a pre-truncated fixture
# may never emit a candidate merely because all remaining rows are present.
TAMPERED_CHECKER_DIR="$WORK/tampered-checker"
TAMPERED_CHECKER="$TAMPERED_CHECKER_DIR/check-issue66-services-compatibility.sh"
TAMPERED_MEMBERS_DIR="$TAMPERED_CHECKER_DIR/fixtures/issue66-services-compatibility"
mkdir -p "$TAMPERED_MEMBERS_DIR"
cp -- "$CHECKER" "$TAMPERED_CHECKER"
sed '$d' "$MEMBERS" >"$TAMPERED_MEMBERS_DIR/required-members.tsv"
cp -- "$APPROVED_DEXDUMP_DIGESTS" \
  "$TAMPERED_MEMBERS_DIR/approved-dexdump-sha256.tsv"
chmod 700 "$TAMPERED_CHECKER"
TAMPERED_MEMBERS_JSON="$WORK/tampered-members.json"
run_checker "$GOOD_JAR" "$FAKE_DEXDUMP" "$TAMPERED_MEMBERS_JSON" "$TAMPERED_CHECKER"
expect_stop "pre-truncated required-members inventory is refused" \
  "$TAMPERED_MEMBERS_JSON" REQUIRED_MEMBERS_MISMATCH
expect_dexdump_not_called "required-members mismatch is refused before dexdump"

# The JSON consumer contract is an exact closed schema. These mutations must
# be rejected even when every original field remains otherwise valid.
DEVICE_PASS_JSON="$WORK/good-with-device-pass.json"
AUTHORITY_JSON="$WORK/good-with-production-authority.json"
if ! python3 - "$GOOD_JSON" "$DEVICE_PASS_JSON" "$AUTHORITY_JSON" <<'PY'
import json
import sys

source_path, device_pass_path, authority_path = sys.argv[1:]
payload = json.load(open(source_path, encoding="utf-8"))
device_pass = dict(payload)
device_pass["devicePass"] = True
authority = dict(payload)
authority["authority"] = "PRODUCTION"
with open(device_pass_path, "w", encoding="utf-8") as stream:
    json.dump(device_pass, stream, separators=(",", ":"), sort_keys=True)
    stream.write("\n")
with open(authority_path, "w", encoding="utf-8") as stream:
    json.dump(authority, stream, separators=(",", ":"), sort_keys=True)
    stream.write("\n")
PY
then
  report fail "checker JSON mutation fixtures are constructible" "could not write mutations"
else
  if assert_non_authoritative_json "$DEVICE_PASS_JSON" SELFTEST_STATIC_MEMBERS_PRESENT \
      >/dev/null 2>&1; then
    report fail "exact checker JSON schema rejects injected devicePass true" \
      "closed-schema verifier accepted an extra devicePass claim"
  else
    report ok "exact checker JSON schema rejects injected devicePass true"
  fi
  if assert_non_authoritative_json "$AUTHORITY_JSON" SELFTEST_STATIC_MEMBERS_PRESENT \
      >/dev/null 2>&1; then
    report fail "checker JSON rejects injected production authority" \
      "verifier accepted authority=PRODUCTION"
  else
    report ok "checker JSON rejects injected production authority"
  fi
fi

# Removing any one of the seven classes must stop. This is deliberately a
# per-class loop rather than one representative mutation.
class_index=0
while IFS= read -r class_name; do
  [ -n "$class_name" ] || continue
  class_index=$((class_index + 1))
  jar="$WORK/services-missing-class-$class_index.jar"
  output="$WORK/missing-class-$class_index.json"
  make_jar "$jar" missing-class "$class_name"
  run_checker "$jar" "$FAKE_DEXDUMP" "$output"
  expect_stop "missing required class $class_name is refused" "$output" MISSING_CLASS
  if ! python3 - "$output" "$class_name" <<'PY' >/dev/null 2>&1
import json
import sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload["missingClass"] == sys.argv[2], payload
PY
  then
    report fail "missing class receipt names $class_name" "missingClass metadata mismatch"
  else
    report ok "missing class receipt names $class_name"
  fi
done < <(cut -f1 "$MEMBERS" | grep -v '^#' | sort -u)

# Removing any one class/method association must stop. Duplicate method names
# in other classes remain present, catching a checker that only greps globally.
method_index=0
while IFS=$'\t' read -r class_name method_name; do
  [ -n "$class_name" ] || continue
  case "$class_name" in \#*) continue ;; esac
  method_index=$((method_index + 1))
  jar="$WORK/services-missing-method-$method_index.jar"
  output="$WORK/missing-method-$method_index.json"
  make_jar "$jar" missing-method "$class_name" "$method_name"
  run_checker "$jar" "$FAKE_DEXDUMP" "$output"
  expect_stop "missing $class_name#$method_name is refused" "$output" MISSING_METHOD
  if ! python3 - "$output" "$class_name" "$method_name" <<'PY' >/dev/null 2>&1
import json
import sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload["missingClass"] == sys.argv[2], payload
assert payload["missingMethod"] == sys.argv[3], payload
PY
  then
    report fail "missing method receipt names $class_name#$method_name" "metadata mismatch"
  else
    report ok "missing method receipt names $class_name#$method_name"
  fi
done <"$MEMBERS"

# ZIP structure is fail-closed: exactly one root classes.dex is accepted.
NO_DEX_JAR="$WORK/services-no-dex.jar"
NO_DEX_JSON="$WORK/no-dex.json"
make_jar "$NO_DEX_JAR" no-dex
run_checker "$NO_DEX_JAR" "$FAKE_DEXDUMP" "$NO_DEX_JSON"
expect_stop "services.jar with no classes dex is refused" "$NO_DEX_JSON" NO_DEX
expect_dexdump_not_called "no-dex refusal occurs before dexdump"

EMPTY_DEX_JAR="$WORK/services-empty-dex.jar"
EMPTY_DEX_JSON="$WORK/empty-dex.json"
make_jar "$EMPTY_DEX_JAR" empty-dex
run_checker "$EMPTY_DEX_JAR" "$FAKE_DEXDUMP" "$EMPTY_DEX_JSON"
expect_stop "services.jar with an empty classes dex is refused" "$EMPTY_DEX_JSON" EMPTY_DEX
expect_dexdump_not_called "empty-dex refusal occurs before dexdump"

MULTI_DEX_JAR="$WORK/services-multi-dex.jar"
MULTI_DEX_JSON="$WORK/multi-dex.json"
make_jar "$MULTI_DEX_JAR" multi-dex
run_checker "$MULTI_DEX_JAR" "$FAKE_DEXDUMP" "$MULTI_DEX_JSON"
expect_stop "services.jar with multiple classes dex entries is refused" "$MULTI_DEX_JSON" MULTIPLE_DEX
expect_dexdump_not_called "multi-dex refusal occurs before dexdump"

# Tool failure cannot be laundered into compatibility.
FAIL_JAR="$WORK/services-dexdump-fail.jar"
FAIL_JSON="$WORK/dexdump-fail.json"
make_jar "$FAIL_JAR" dexdump-fail
run_checker "$FAIL_JAR" "$FAKE_DEXDUMP" "$FAIL_JSON"
expect_stop "dexdump failure is refused" "$FAIL_JSON" DEXDUMP_FAILED
if [ "$(wc -l <"$DEXDUMP_LOG" | tr -d ' ')" = 1 ]; then
  report ok "dexdump failure exercised exactly one fake invocation"
else
  report fail "dexdump failure exercised exactly one fake invocation" "log=$(tr '\n' ';' <"$DEXDUMP_LOG")"
fi

# Deterministic TOCTOU probes: fake dexdump arms a worker after it has emitted
# the analysis bytes, and the test-only shasum gate releases that worker at the
# checker's first post-analysis hash. A candidate must never bind that old
# analysis to replacement bytes now present at either input path.
IFS=$'\t' read -r FIRST_REQUIRED_CLASS FIRST_REQUIRED_METHOD \
  < <(grep -v '^#' "$MEMBERS" | head -n 1)

TOCTOU_SERVICES_JAR="$WORK/services-toctou.jar"
TOCTOU_SERVICES_REPLACEMENT="$WORK/services-toctou-replacement.jar"
TOCTOU_SERVICES_JSON="$WORK/services-toctou.json"
TOCTOU_SERVICES_STATE="$WORK/services-toctou.state"
make_jar "$TOCTOU_SERVICES_JAR" positive
make_jar "$TOCTOU_SERVICES_REPLACEMENT" missing-method \
  "$FIRST_REQUIRED_CLASS" "$FIRST_REQUIRED_METHOD"
TOCTOU_SERVICES_REPLACEMENT_SHA="$(sha256_path "$TOCTOU_SERVICES_REPLACEMENT")"
run_checker_with_after_analysis_swap \
  "$TOCTOU_SERVICES_JAR" "$FAKE_DEXDUMP" "$TOCTOU_SERVICES_JSON" \
  "$TOCTOU_SERVICES_JAR" "$TOCTOU_SERVICES_REPLACEMENT" "$TOCTOU_SERVICES_STATE"
expect_stop "services.jar replacement after analysis is refused" \
  "$TOCTOU_SERVICES_JSON" INPUT_CHANGED
if [ "$(sed -n '1p' "$TOCTOU_SERVICES_STATE" 2>/dev/null || true)" != swapped ]; then
  report fail "services.jar TOCTOU replacement is deterministic" \
    "state=$(cat "$TOCTOU_SERVICES_STATE" 2>/dev/null || printf missing)"
elif [ "$(sha256_path "$TOCTOU_SERVICES_JAR")" != "$TOCTOU_SERVICES_REPLACEMENT_SHA" ]; then
  report fail "services.jar TOCTOU replacement is deterministic" \
    "target bytes do not match replacement digest"
else
  report ok "services.jar TOCTOU replacement is deterministic"
fi
if candidate_splices_digest "$TOCTOU_SERVICES_JSON" servicesJarSha256 \
    "$TOCTOU_SERVICES_REPLACEMENT_SHA"; then
  report fail "services.jar output never splices old analysis with replacement hash" \
    "candidate JSON contains the replacement servicesJarSha256"
else
  report ok "services.jar output never splices old analysis with replacement hash"
fi

TOCTOU_DEXDUMP="$WORK/dexdump-toctou"
TOCTOU_DEXDUMP_REPLACEMENT="$WORK/dexdump-toctou-replacement"
TOCTOU_DEXDUMP_JSON="$WORK/dexdump-toctou.json"
TOCTOU_DEXDUMP_STATE="$WORK/dexdump-toctou.state"
cp -- "$FAKE_DEXDUMP" "$TOCTOU_DEXDUMP"
cp -- "$FAKE_DEXDUMP" "$TOCTOU_DEXDUMP_REPLACEMENT"
printf '\n# replacement digest marker\n' >>"$TOCTOU_DEXDUMP_REPLACEMENT"
chmod 700 "$TOCTOU_DEXDUMP" "$TOCTOU_DEXDUMP_REPLACEMENT"
TOCTOU_DEXDUMP_REPLACEMENT_SHA="$(sha256_path "$TOCTOU_DEXDUMP_REPLACEMENT")"
run_checker_with_after_analysis_swap \
  "$GOOD_JAR" "$TOCTOU_DEXDUMP" "$TOCTOU_DEXDUMP_JSON" \
  "$TOCTOU_DEXDUMP" "$TOCTOU_DEXDUMP_REPLACEMENT" "$TOCTOU_DEXDUMP_STATE"
expect_stop "dexdump replacement after analysis is refused" \
  "$TOCTOU_DEXDUMP_JSON" INPUT_CHANGED
if [ "$(sed -n '1p' "$TOCTOU_DEXDUMP_STATE" 2>/dev/null || true)" != swapped ]; then
  report fail "dexdump TOCTOU replacement is deterministic" \
    "state=$(cat "$TOCTOU_DEXDUMP_STATE" 2>/dev/null || printf missing)"
elif [ "$(sha256_path "$TOCTOU_DEXDUMP")" != "$TOCTOU_DEXDUMP_REPLACEMENT_SHA" ]; then
  report fail "dexdump TOCTOU replacement is deterministic" \
    "target bytes do not match replacement digest"
else
  report ok "dexdump TOCTOU replacement is deterministic"
fi
if candidate_splices_digest "$TOCTOU_DEXDUMP_JSON" dexdumpSha256 \
    "$TOCTOU_DEXDUMP_REPLACEMENT_SHA"; then
  report fail "dexdump output never splices old execution with replacement hash" \
    "candidate JSON contains the replacement dexdumpSha256"
else
  report ok "dexdump output never splices old execution with replacement hash"
fi

# Existing output is immutable and rejected before inspecting the jar.
EXISTING_JSON="$WORK/existing.json"
printf 'sentinel-must-survive\n' >"$EXISTING_JSON"
run_checker "$GOOD_JAR" "$FAKE_DEXDUMP" "$EXISTING_JSON"
if [ "$RC" -eq 0 ] || [[ "$OUT" != *"STOP_OUTPUT_EXISTS"* ]]; then
  report fail "existing output is refused" "rc=$RC output=$OUT"
elif [ "$(cat "$EXISTING_JSON")" != "sentinel-must-survive" ]; then
  report fail "existing output is refused" "existing bytes were overwritten"
else
  report ok "existing output is refused"
fi
expect_dexdump_not_called "existing output is rejected before dexdump"

# Neither input may be a symlink. Both stop before running the target tool.
JAR_LINK="$WORK/services-link.jar"
ln -s "$GOOD_JAR" "$JAR_LINK"
JAR_LINK_JSON="$WORK/jar-link.json"
run_checker "$JAR_LINK" "$FAKE_DEXDUMP" "$JAR_LINK_JSON"
expect_stop "symlink services.jar input is refused" "$JAR_LINK_JSON" SYMLINK_INPUT
expect_dexdump_not_called "services.jar symlink is rejected before dexdump"

DEXDUMP_LINK="$WORK/dexdump-link"
ln -s "$FAKE_DEXDUMP" "$DEXDUMP_LINK"
DEXDUMP_LINK_JSON="$WORK/dexdump-link.json"
run_checker "$GOOD_JAR" "$DEXDUMP_LINK" "$DEXDUMP_LINK_JSON"
expect_stop "symlink dexdump input is refused" "$DEXDUMP_LINK_JSON" SYMLINK_INPUT
expect_dexdump_not_called "dexdump symlink is rejected before invocation"

printf 'issue66 services compatibility selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

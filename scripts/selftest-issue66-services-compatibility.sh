#!/bin/bash -p
# Device-free RED matrix for the static issue #66 services.jar compatibility
# checker. It creates synthetic ZIP/JAR files whose classes*.dex entries are
# consumed only by the checked-in fake dexdump fixture.

unset BASH_ENV ENV
unset DEVELOPER_DIR SDKROOT TOOLCHAINS
PATH=/usr/bin:/bin
export PATH
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
readonly SERVICES_JAR_SIZE_LIMIT=134217728
readonly DEXDUMP_SIZE_LIMIT=67108864
readonly SOURCE_PROPERTIES_SIZE_LIMIT=65536
readonly ARCHIVE_ENTRY_LIMIT=4096
readonly ARCHIVE_SINGLE_UNCOMPRESSED_LIMIT=268435456
readonly ARCHIVE_TOTAL_UNCOMPRESSED_LIMIT=536870912
readonly SELFTEST_DEXDUMP_TIMEOUT_SECONDS=2
readonly SELFTEST_DEXDUMP_STDOUT_LIMIT=262144
readonly SELFTEST_DEXDUMP_STDERR_LIMIT=65536

pass=0
fail=0
skip=0

report() { # ok|fail name [detail]
  if [ "$1" = ok ]; then
    printf 'ok   %s\n' "$2"
    pass=$((pass + 1))
  else
    printf 'FAIL %s :: %s\n' "$2" "${3:-unspecified failure}"
    fail=$((fail + 1))
  fi
}

report_skip() { # name
  printf 'skip %s\n' "$1"
  skip=$((skip + 1))
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
/usr/bin/python3 -I - "$1" "$2" "$3" <<'PY'
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
if ! /usr/bin/python3 -I - "$INSTALLER" "$MUTATED_INSTALLER" <<'PY'
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

make_jar() { # jar mode [class] [method]
  local jar="$1" mode="$2" class_name="${3:-}" method_name="${4:-}"
  /usr/bin/python3 -I - "$MEMBERS" "$jar" "$mode" "$class_name" "$method_name" <<'PY'
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

make_archive_boundary_jar() { # jar mode
  local jar="$1" mode="$2"
  make_jar "$jar" positive
  /usr/bin/python3 -I - "$jar" "$mode" "$ARCHIVE_ENTRY_LIMIT" \
    "$ARCHIVE_SINGLE_UNCOMPRESSED_LIMIT" "$ARCHIVE_TOTAL_UNCOMPRESSED_LIMIT" <<'PY'
import struct
import sys
import zipfile

path, mode = sys.argv[1:3]
entry_limit = int(sys.argv[3])
single_limit = int(sys.argv[4])
total_limit = int(sys.argv[5])


def central_records(payload):
    eocd = payload.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise AssertionError("fixture EOCD missing")
    directory_size, directory_offset = struct.unpack_from("<LL", payload, eocd + 12)
    records = {}
    cursor = directory_offset
    boundary = directory_offset + directory_size
    while cursor < boundary:
        assert payload[cursor:cursor + 4] == b"PK\x01\x02"
        name_length, extra_length, comment_length = struct.unpack_from(
            "<HHH", payload, cursor + 28
        )
        name = bytes(payload[cursor + 46:cursor + 46 + name_length]).decode("utf-8")
        records[name] = cursor
        cursor += 46 + name_length + extra_length + comment_length
    assert cursor == boundary
    return eocd, records


if mode in {"entries-exact", "entries-over"}:
    target = entry_limit if mode == "entries-exact" else entry_limit + 1
    with zipfile.ZipFile(path, "a", compression=zipfile.ZIP_STORED) as archive:
        existing = len(archive.infolist())
        for index in range(target - existing):
            archive.writestr(f"padding/{index:05d}.txt", b"")
elif mode == "member-over":
    with zipfile.ZipFile(path, "a", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("member-over.bin", b"x")
    payload = bytearray(open(path, "rb").read())
    _eocd, records = central_records(payload)
    struct.pack_into("<L", payload, records["member-over.bin"] + 24, single_limit + 1)
    open(path, "wb").write(payload)
elif mode == "total-over":
    names = ["total-a.bin", "total-b.bin", "total-c.bin"]
    with zipfile.ZipFile(path, "a", compression=zipfile.ZIP_STORED) as archive:
        for name in names:
            archive.writestr(name, b"x")
    payload = bytearray(open(path, "rb").read())
    _eocd, records = central_records(payload)
    declared = total_limit // len(names) + 1
    for name in names:
        struct.pack_into("<L", payload, records[name] + 24, declared)
    open(path, "wb").write(payload)
elif mode == "ratio-over":
    with zipfile.ZipFile(path, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("ratio-bomb.bin", b"0" * (2 * 1024 * 1024))
elif mode == "unsupported-method":
    with zipfile.ZipFile(path, "a", compression=zipfile.ZIP_BZIP2) as archive:
        archive.writestr("unsupported-method.bin", b"not-deflate")
elif mode == "eocd-entry-over":
    payload = bytearray(open(path, "rb").read())
    eocd, _records = central_records(payload)
    struct.pack_into("<H", payload, eocd + 8, entry_limit + 1)
    struct.pack_into("<H", payload, eocd + 10, entry_limit + 1)
    open(path, "wb").write(payload)
elif mode == "eocd-trailing-byte":
    with open(path, "ab") as stream:
        stream.write(b"x")
else:
    raise AssertionError(f"unknown boundary mode: {mode}")
PY
}

make_dexdump_resource_jar() { # jar mode [amount]
  local jar="$1" mode="$2" amount="${3:-0}"
  /usr/bin/python3 -I - "$GOOD_JAR" "$jar" "$mode" "$amount" <<'PY'
import sys
import zipfile

source, target, mode, raw_amount = sys.argv[1:]
amount = int(raw_amount)
with zipfile.ZipFile(source) as archive:
    payload = archive.read("classes.dex")
if mode == "stdout-bytes":
    if len(payload) > amount:
        raise AssertionError("requested stdout fixture is smaller than valid payload")
    padding = bytearray()
    while len(payload) + len(padding) < amount:
        remaining = amount - len(payload) - len(padding)
        if remaining <= 1024:
            padding.extend(b" " * remaining)
        else:
            padding.extend(b" " * 1023 + b"\n")
    payload += bytes(padding)
elif mode == "unique-records":
    if len(payload) > amount:
        raise AssertionError("requested unique-record fixture is smaller than valid payload")
    prefix = bytearray()
    index = 0
    while len(prefix) + len(payload) < amount:
        descriptor = f"Lselftest/irrelevant/Unique{index:06d};"
        record = (
            f"Class #{index}            -\n"
            f"  Class descriptor  : '{descriptor}'\n"
            "  Direct methods    -\n"
            f"    #0              : (in {descriptor})\n"
            f"      name          : 'irrelevantMethod{index:06d}'\n"
        ).encode("ascii")
        remaining = amount - len(payload) - len(prefix)
        if len(record) > remaining:
            prefix.extend(b" " * remaining)
            break
        prefix.extend(record)
        index += 1
    payload = bytes(prefix) + payload
elif mode == "stderr-bytes":
    payload = f"FAKE_DEXDUMP_STDERR_BYTES={amount}\n".encode("ascii") + payload
elif mode == "hang":
    payload = b"FAKE_DEXDUMP_HANG\n" + payload
elif mode == "late-write":
    payload = b"FAKE_DEXDUMP_LATE_WRITE\n" + payload
else:
    raise AssertionError(f"unknown dexdump mode: {mode}")
with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as archive:
    archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    archive.writestr("classes.dex", payload)
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

run_checker_with_late_marker() { # services.jar output.json marker
  local jar="$1" output="$2" marker="$3"
  : >"$DEXDUMP_LOG"
  rm -f -- "$marker"
  OUT="$(
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
    FAKE_DEXDUMP_LATE_WRITE_MARKER="$marker" \
      "$CHECKER" --services-jar "$jar" --dexdump "$FAKE_DEXDUMP" --output "$output" \
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
  (
    attempts=0
    while [ "$attempts" -lt 5000 ]; do
      if [ -f "$state" ] && [ "$(sed -n '1p' "$state" 2>/dev/null || true)" = hash-ready ]; then
        if mv -f -- "$replacement" "$target"; then
          printf 'swapped\n' >"$state"
        else
          printf 'swap-failed\n' >"$state"
        fi
        exit
      fi
      attempts=$((attempts + 1))
      sleep 0.001
    done
    exit 98
  ) &
  local swap_pid=$!
  OUT="$(
    FAKE_DEXDUMP_LOG="$DEXDUMP_LOG" \
      "$CHECKER" --services-jar "$jar" --dexdump "$dexdump" --output "$output" \
        --allow-pinned-selftest-fixture \
        --selftest-post-analysis-gate 2>&1
  )"
  RC=$?
  wait "$swap_pid"
  SWAP_RC=$?
}

run_checker_with_source_properties_swap() { # checker jar tool output properties mode victim
  local checker=$1 jar=$2 tool=$3 output=$4 properties=$5 mode=$6 victim=$7
  local state="${output}.selftest-source-properties.state"
  local checker_log="${output}.checker.log"
  local checker_pid swap_pid attempts writer_pid actual_rc
  CHECKER_TIMED_OUT=0
  SWAP_RC=0
  : >"$DEXDUMP_LOG"
  rm -f -- "$state" "$checker_log"
  (
    attempts=0
    while [ "$attempts" -lt 5000 ]; do
      if [ -f "$state" ] \
          && [ "$(sed -n '1p' "$state" 2>/dev/null || true)" = lstat-ready ]; then
        if ! mv -- "$properties" "${properties}.before-swap"; then
          printf 'swap-failed\n' >"$state"
          exit 97
        fi
        case "$mode" in
          symlink)
            ln -s -- "$victim" "$properties" || {
              printf 'swap-failed\n' >"$state"
              exit 97
            }
            ;;
          fifo)
            mkfifo -- "$properties" || {
              printf 'swap-failed\n' >"$state"
              exit 97
            }
            ;;
          *)
            printf 'swap-failed\n' >"$state"
            exit 97
            ;;
        esac
        printf 'swapped\n' >"$state"
        exit
      fi
      attempts=$((attempts + 1))
      sleep 0.001
    done
    exit 98
  ) &
  swap_pid=$!
  "$checker" --services-jar "$jar" --dexdump "$tool" --output "$output" \
      --allow-pinned-selftest-fixture \
      --selftest-source-properties-gate >"$checker_log" 2>&1 &
  checker_pid=$!
  wait "$swap_pid"
  SWAP_RC=$?

  attempts=0
  while kill -0 "$checker_pid" 2>/dev/null && [ "$attempts" -lt 1000 ]; do
    attempts=$((attempts + 1))
    sleep 0.001
  done
  if kill -0 "$checker_pid" 2>/dev/null; then
    CHECKER_TIMED_OUT=1
    if [ "$mode" = fifo ]; then
      printf 'Pkg.UserSrc=false\nPkg.Revision=35.0.2\n' >"$properties" &
      writer_pid=$!
      wait "$checker_pid"
      actual_rc=$?
      wait "$writer_pid" 2>/dev/null || true
    else
      kill "$checker_pid" 2>/dev/null || true
      wait "$checker_pid" 2>/dev/null
      actual_rc=$?
    fi
  else
    wait "$checker_pid"
    actual_rc=$?
  fi
  OUT="$(cat "$checker_log" 2>/dev/null || true)"
  if (( CHECKER_TIMED_OUT )); then
    RC=124
  else
    RC=$actual_rc
  fi
}

sha256_path() { # path
  /usr/bin/python3 -I - "$1" <<'PY'
import hashlib
import sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
}

assert_non_authoritative_json() { # path expected-status [expected-reason]
  /usr/bin/python3 -I - "$1" "$2" "${3:-}" <<'PY'
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

expect_selftest_success() { # name output-json
  local name="$1" output="$2"
  if [ "$RC" -ne 0 ]; then
    report fail "$name" "rc=$RC output=$OUT"
  elif [[ "$OUT" != *"SELFTEST_STATIC_MEMBERS_PRESENT"* ]]; then
    report fail "$name" "selftest marker missing: $OUT"
  elif ! assert_non_authoritative_json "$output" SELFTEST_STATIC_MEMBERS_PRESENT; then
    report fail "$name" "invalid selftest success JSON"
  else
    report ok "$name"
  fi
}

# The dexdump parser may retain only the fixed 7-class/20-method inventory.
# A stdout byte cap alone is not a heap bound when attacker-controlled unique
# strings are copied into Python sets.
if /usr/bin/python3 -I - "$CHECKER" <<'PY' >/dev/null 2>&1
import re
import sys

source = open(sys.argv[1], encoding="utf-8").read()
start = source.index("dump_path, members_path = sys.argv[1:]")
end = source.index('print("STATIC_MEMBERS_PRESENT")', start)
parser = source[start:end]
assert not re.search(r"^\s*classes\.add\(current_class\)\s*$", parser, re.M)
assert not re.search(
    r"^\s*methods\.add\(\(current_class, match\.group\(1\)\)\)\s*$",
    parser,
    re.M,
)
assert "required_classes" in parser
assert "required_methods" in parser
assert "if current_class in required_classes:" in parser
assert "if candidate in required_methods:" in parser
PY
then
  report ok "dexdump parser retains only the fixed required-member inventory"
else
  report fail "dexdump parser retains only the fixed required-member inventory" \
    "parser still accumulates attacker-selected unique classes or methods"
fi

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
elif ! /usr/bin/python3 -I - "$GOOD_JSON" <<'PY'
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

# Inputs are bounded before hashing or copying. Sparse files make the regression
# cheap to construct while still proving that declared size is checked first.
OVERSIZED_SERVICES_JAR="$WORK/services-oversized-sparse.jar"
OVERSIZED_SERVICES_JSON="$WORK/services-oversized-sparse.json"
/usr/bin/python3 -I - "$OVERSIZED_SERVICES_JAR" "$SERVICES_JAR_SIZE_LIMIT" <<'PY'
import os
import sys

with open(sys.argv[1], "wb"):
    pass
os.truncate(sys.argv[1], int(sys.argv[2]) + 1)
PY
run_checker "$OVERSIZED_SERVICES_JAR" "$FAKE_DEXDUMP" "$OVERSIZED_SERVICES_JSON"
expect_stop "oversized sparse services.jar is refused before snapshot" \
  "$OVERSIZED_SERVICES_JSON" SERVICES_JAR_SIZE_LIMIT
expect_dexdump_not_called "oversized sparse services.jar is refused before dexdump"

OVERSIZED_DEXDUMP="$WORK/dexdump-oversized-sparse"
OVERSIZED_DEXDUMP_JSON="$WORK/dexdump-oversized-sparse.json"
cp -- "$FAKE_DEXDUMP" "$OVERSIZED_DEXDUMP"
/usr/bin/python3 -I - "$OVERSIZED_DEXDUMP" "$DEXDUMP_SIZE_LIMIT" <<'PY'
import os
import sys

os.truncate(sys.argv[1], int(sys.argv[2]) + 1)
PY
chmod 700 "$OVERSIZED_DEXDUMP"
run_checker "$GOOD_JAR" "$OVERSIZED_DEXDUMP" "$OVERSIZED_DEXDUMP_JSON"
expect_stop "oversized sparse dexdump is refused before snapshot" \
  "$OVERSIZED_DEXDUMP_JSON" DEXDUMP_SIZE_LIMIT
expect_dexdump_not_called "oversized sparse dexdump is refused before invocation"

# ZIP metadata is validated before any member is expanded, and classes.dex is
# streamed instead of materialized by archive.read(). The exact-count archive
# is the positive boundary control.
ARCHIVE_ENTRIES_EXACT_JAR="$WORK/archive-entries-exact.jar"
ARCHIVE_ENTRIES_EXACT_JSON="$WORK/archive-entries-exact.json"
make_archive_boundary_jar "$ARCHIVE_ENTRIES_EXACT_JAR" entries-exact
run_checker "$ARCHIVE_ENTRIES_EXACT_JAR" "$FAKE_DEXDUMP" "$ARCHIVE_ENTRIES_EXACT_JSON"
expect_selftest_success "archive accepts exactly the entry-count cap" \
  "$ARCHIVE_ENTRIES_EXACT_JSON"

for archive_case in \
    entries-over \
    member-over \
    total-over \
    ratio-over \
    unsupported-method \
    eocd-entry-over; do
  archive_jar="$WORK/archive-$archive_case.jar"
  archive_json="$WORK/archive-$archive_case.json"
  make_archive_boundary_jar "$archive_jar" "$archive_case"
  run_checker "$archive_jar" "$FAKE_DEXDUMP" "$archive_json"
  expect_stop "archive preflight refuses $archive_case" \
    "$archive_json" SERVICES_ARCHIVE_LIMIT
  expect_dexdump_not_called "archive $archive_case is refused before dexdump"
done

ARCHIVE_EOCD_TRAILING_JAR="$WORK/archive-eocd-trailing-byte.jar"
ARCHIVE_EOCD_TRAILING_JSON="$WORK/archive-eocd-trailing-byte.json"
make_archive_boundary_jar "$ARCHIVE_EOCD_TRAILING_JAR" eocd-trailing-byte
run_checker "$ARCHIVE_EOCD_TRAILING_JAR" "$FAKE_DEXDUMP" \
  "$ARCHIVE_EOCD_TRAILING_JSON"
expect_stop "archive preflight refuses bytes after EOCD" \
  "$ARCHIVE_EOCD_TRAILING_JSON" INVALID_JAR
expect_dexdump_not_called "archive EOCD boundary refusal occurs before dexdump"

# The selftest identity uses deliberately small fixed process budgets so exact
# cap and cap+1 behavior can be covered without producing large fixtures.
DEXDUMP_STDOUT_EXACT_JAR="$WORK/dexdump-stdout-exact.jar"
DEXDUMP_STDOUT_EXACT_JSON="$WORK/dexdump-stdout-exact.json"
make_dexdump_resource_jar "$DEXDUMP_STDOUT_EXACT_JAR" stdout-bytes \
  "$SELFTEST_DEXDUMP_STDOUT_LIMIT"
run_checker "$DEXDUMP_STDOUT_EXACT_JAR" "$FAKE_DEXDUMP" \
  "$DEXDUMP_STDOUT_EXACT_JSON"
expect_selftest_success "dexdump accepts stdout at the exact cap" \
  "$DEXDUMP_STDOUT_EXACT_JSON"

DEXDUMP_UNIQUE_RECORDS_JAR="$WORK/dexdump-unique-records.jar"
DEXDUMP_UNIQUE_RECORDS_JSON="$WORK/dexdump-unique-records.json"
make_dexdump_resource_jar "$DEXDUMP_UNIQUE_RECORDS_JAR" unique-records \
  "$SELFTEST_DEXDUMP_STDOUT_LIMIT"
run_checker \
  "$DEXDUMP_UNIQUE_RECORDS_JAR" "$FAKE_DEXDUMP" "$DEXDUMP_UNIQUE_RECORDS_JSON"
expect_selftest_success \
  "dexdump accepts cap-sized high-cardinality irrelevant records with bounded state" \
  "$DEXDUMP_UNIQUE_RECORDS_JSON"

DEXDUMP_STDOUT_OVER_JAR="$WORK/dexdump-stdout-over.jar"
DEXDUMP_STDOUT_OVER_JSON="$WORK/dexdump-stdout-over.json"
make_dexdump_resource_jar "$DEXDUMP_STDOUT_OVER_JAR" stdout-bytes \
  "$((SELFTEST_DEXDUMP_STDOUT_LIMIT + 1))"
run_checker "$DEXDUMP_STDOUT_OVER_JAR" "$FAKE_DEXDUMP" \
  "$DEXDUMP_STDOUT_OVER_JSON"
expect_stop "dexdump refuses stdout at cap plus one" \
  "$DEXDUMP_STDOUT_OVER_JSON" DEXDUMP_STDOUT_LIMIT

DEXDUMP_STDERR_EXACT_JAR="$WORK/dexdump-stderr-exact.jar"
DEXDUMP_STDERR_EXACT_JSON="$WORK/dexdump-stderr-exact.json"
make_dexdump_resource_jar "$DEXDUMP_STDERR_EXACT_JAR" stderr-bytes \
  "$SELFTEST_DEXDUMP_STDERR_LIMIT"
run_checker "$DEXDUMP_STDERR_EXACT_JAR" "$FAKE_DEXDUMP" \
  "$DEXDUMP_STDERR_EXACT_JSON"
expect_selftest_success "dexdump accepts stderr at the exact cap" \
  "$DEXDUMP_STDERR_EXACT_JSON"

DEXDUMP_STDERR_OVER_JAR="$WORK/dexdump-stderr-over.jar"
DEXDUMP_STDERR_OVER_JSON="$WORK/dexdump-stderr-over.json"
make_dexdump_resource_jar "$DEXDUMP_STDERR_OVER_JAR" stderr-bytes \
  "$((SELFTEST_DEXDUMP_STDERR_LIMIT + 1))"
run_checker "$DEXDUMP_STDERR_OVER_JAR" "$FAKE_DEXDUMP" \
  "$DEXDUMP_STDERR_OVER_JSON"
expect_stop "dexdump refuses stderr at cap plus one" \
  "$DEXDUMP_STDERR_OVER_JSON" DEXDUMP_STDERR_LIMIT

DEXDUMP_HANG_JAR="$WORK/dexdump-hang.jar"
DEXDUMP_HANG_JSON="$WORK/dexdump-hang.json"
make_dexdump_resource_jar "$DEXDUMP_HANG_JAR" hang
run_checker "$DEXDUMP_HANG_JAR" "$FAKE_DEXDUMP" "$DEXDUMP_HANG_JSON"
expect_stop "dexdump timeout is fail-closed" "$DEXDUMP_HANG_JSON" DEXDUMP_TIMEOUT

DEXDUMP_LATE_JAR="$WORK/dexdump-late-write.jar"
DEXDUMP_LATE_JSON="$WORK/dexdump-late-write.json"
DEXDUMP_LATE_MARKER="$WORK/dexdump-late-write.marker"
make_dexdump_resource_jar "$DEXDUMP_LATE_JAR" late-write
run_checker_with_late_marker "$DEXDUMP_LATE_JAR" "$DEXDUMP_LATE_JSON" \
  "$DEXDUMP_LATE_MARKER"
expect_stop "dexdump surviving process group is fail-closed" \
  "$DEXDUMP_LATE_JSON" DEXDUMP_PROCESS_GROUP
/bin/sleep 1.25
if [ -e "$DEXDUMP_LATE_MARKER" ]; then
  report fail "dexdump process group cannot perform a late write" \
    "late marker exists after checker return"
else
  report ok "dexdump process group cannot perform a late write"
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

# Exercise source.properties through the production SDK-layout branch without
# trusting a caller-controlled SDK. The copied checker adds one canonical,
# private selftest root, but its native stub remains absent from the pinned
# approval list and therefore can never reach dexdump execution or success.
SOURCE_PROPERTIES_CHECKER_DIR="$WORK/source-properties-checker"
SOURCE_PROPERTIES_CHECKER="$SOURCE_PROPERTIES_CHECKER_DIR/check-issue66-services-compatibility.sh"
SOURCE_PROPERTIES_FIXTURES="$SOURCE_PROPERTIES_CHECKER_DIR/fixtures/issue66-services-compatibility"
SOURCE_PROPERTIES_SDK_ROOT="$WORK/source-properties-sdk"
mkdir -p "$SOURCE_PROPERTIES_FIXTURES" "$SOURCE_PROPERTIES_SDK_ROOT/build-tools"
SOURCE_PROPERTIES_SDK_ROOT="$(cd "$SOURCE_PROPERTIES_SDK_ROOT" && pwd -P)"
cp -- "$CHECKER" "$SOURCE_PROPERTIES_CHECKER"
cp -- "$MEMBERS" "$SOURCE_PROPERTIES_FIXTURES/required-members.tsv"
cp -- "$APPROVED_DEXDUMP_DIGESTS" \
  "$SOURCE_PROPERTIES_FIXTURES/approved-dexdump-sha256.tsv"
if ! /usr/bin/python3 -I - \
    "$SOURCE_PROPERTIES_CHECKER" "$SOURCE_PROPERTIES_SDK_ROOT" <<'PY'
import pathlib
import sys

checker_path, root = sys.argv[1:]
path = pathlib.Path(checker_path)
source = path.read_text(encoding="utf-8")
needle = '    pathlib.Path("/opt/android-sdk-linux"),\n'
assert source.count(needle) == 1
source = source.replace(needle, needle + f"    pathlib.Path({root!r}),\n")
path.write_text(source, encoding="utf-8")
PY
then
  printf 'could not prepare source.properties security checker fixture\n' >&2
  exit 2
fi
chmod 700 "$SOURCE_PROPERTIES_CHECKER" "$SOURCE_PROPERTIES_SDK_ROOT" \
  "$SOURCE_PROPERTIES_SDK_ROOT/build-tools"

make_native_sdk_stub() { # revision
  local revision=$1 directory="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/$1"
  mkdir "$directory"
  chmod 700 "$directory"
  /usr/bin/python3 -I - "$directory/dexdump" <<'PY'
import os
import sys

with open(sys.argv[1], "wb") as stream:
    stream.write(bytes.fromhex("7f454c46"))
os.chmod(sys.argv[1], 0o500)
PY
}

make_native_sdk_stub 35.0.0
SOURCE_PROPERTIES_OVERSIZED_TOOL="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.0/dexdump"
SOURCE_PROPERTIES_OVERSIZED="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.0/source.properties"
SOURCE_PROPERTIES_OVERSIZED_JSON="$WORK/source-properties-oversized.json"
/usr/bin/python3 -I - \
  "$SOURCE_PROPERTIES_OVERSIZED" "$SOURCE_PROPERTIES_SIZE_LIMIT" <<'PY'
import os
import sys

prefix = b"Pkg.UserSrc=false\nPkg.Revision=35.0.0\n"
limit = int(sys.argv[2])
with open(sys.argv[1], "wb") as stream:
    stream.write(prefix)
    stream.write(b"x" * (limit + 1 - len(prefix)))
os.chmod(sys.argv[1], 0o600)
PY
run_checker "$GOOD_JAR" "$SOURCE_PROPERTIES_OVERSIZED_TOOL" \
  "$SOURCE_PROPERTIES_OVERSIZED_JSON" "$SOURCE_PROPERTIES_CHECKER"
expect_stop "source.properties above the fixed cap is refused" \
  "$SOURCE_PROPERTIES_OVERSIZED_JSON" UNTRUSTED_DEXDUMP
expect_dexdump_not_called "oversized source.properties is refused before dexdump"

make_native_sdk_stub 35.0.3
SOURCE_PROPERTIES_EXACT_TOOL="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.3/dexdump"
SOURCE_PROPERTIES_EXACT="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.3/source.properties"
SOURCE_PROPERTIES_EXACT_JSON="$WORK/source-properties-exact.json"
/usr/bin/python3 -I - \
  "$SOURCE_PROPERTIES_EXACT" "$SOURCE_PROPERTIES_SIZE_LIMIT" <<'PY'
import os
import sys

prefix = b"Pkg.UserSrc=false\nPkg.Revision=35.0.3\n#"
limit = int(sys.argv[2])
with open(sys.argv[1], "wb") as stream:
    stream.write(prefix)
    stream.write(b"x" * (limit - len(prefix)))
os.chmod(sys.argv[1], 0o600)
PY
run_checker "$GOOD_JAR" "$SOURCE_PROPERTIES_EXACT_TOOL" \
  "$SOURCE_PROPERTIES_EXACT_JSON" "$SOURCE_PROPERTIES_CHECKER"
expect_stop "source.properties accepts the exact fixed cap before digest approval" \
  "$SOURCE_PROPERTIES_EXACT_JSON" TOOL_NOT_ATTESTED
expect_dexdump_not_called "exact-cap source.properties cannot bypass dexdump approval"

make_native_sdk_stub 35.0.1
SOURCE_PROPERTIES_LINK_TOOL="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.1/dexdump"
SOURCE_PROPERTIES_LINK="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.1/source.properties"
SOURCE_PROPERTIES_LINK_VICTIM="$WORK/source-properties-link-victim"
SOURCE_PROPERTIES_LINK_JSON="$WORK/source-properties-link.json"
printf 'Pkg.UserSrc=false\nPkg.Revision=35.0.1\n' >"$SOURCE_PROPERTIES_LINK"
printf 'Pkg.UserSrc=false\nPkg.Revision=35.0.1\n' >"$SOURCE_PROPERTIES_LINK_VICTIM"
chmod 600 "$SOURCE_PROPERTIES_LINK" "$SOURCE_PROPERTIES_LINK_VICTIM"
run_checker_with_source_properties_swap \
  "$SOURCE_PROPERTIES_CHECKER" "$GOOD_JAR" "$SOURCE_PROPERTIES_LINK_TOOL" \
  "$SOURCE_PROPERTIES_LINK_JSON" "$SOURCE_PROPERTIES_LINK" symlink \
  "$SOURCE_PROPERTIES_LINK_VICTIM"
if (( CHECKER_TIMED_OUT )); then
  report fail "regular-to-symlink source.properties race is refused without blocking" \
    "checker exceeded the one-second completion bound"
else
  expect_stop "regular-to-symlink source.properties race is refused without blocking" \
    "$SOURCE_PROPERTIES_LINK_JSON" UNTRUSTED_DEXDUMP
fi
if (( SWAP_RC != 0 )); then
  report fail "regular-to-symlink source.properties race is deterministic" \
    "swap helper rc=$SWAP_RC"
else
  report ok "regular-to-symlink source.properties race is deterministic"
fi
expect_dexdump_not_called "symlink-raced source.properties is refused before dexdump"

make_native_sdk_stub 35.0.2
SOURCE_PROPERTIES_FIFO_TOOL="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.2/dexdump"
SOURCE_PROPERTIES_FIFO="$SOURCE_PROPERTIES_SDK_ROOT/build-tools/35.0.2/source.properties"
SOURCE_PROPERTIES_FIFO_JSON="$WORK/source-properties-fifo.json"
printf 'Pkg.UserSrc=false\nPkg.Revision=35.0.2\n' >"$SOURCE_PROPERTIES_FIFO"
chmod 600 "$SOURCE_PROPERTIES_FIFO"
run_checker_with_source_properties_swap \
  "$SOURCE_PROPERTIES_CHECKER" "$GOOD_JAR" "$SOURCE_PROPERTIES_FIFO_TOOL" \
  "$SOURCE_PROPERTIES_FIFO_JSON" "$SOURCE_PROPERTIES_FIFO" fifo ""
if (( CHECKER_TIMED_OUT )); then
  report fail "regular-to-FIFO source.properties race is refused without blocking" \
    "checker blocked until the selftest supplied a FIFO writer"
else
  expect_stop "regular-to-FIFO source.properties race is refused without blocking" \
    "$SOURCE_PROPERTIES_FIFO_JSON" UNTRUSTED_DEXDUMP
fi
if (( SWAP_RC != 0 )); then
  report fail "regular-to-FIFO source.properties race is deterministic" \
    "swap helper rc=$SWAP_RC"
else
  report ok "regular-to-FIFO source.properties race is deterministic"
fi
expect_dexdump_not_called "FIFO-raced source.properties is refused before dexdump"

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
  report_skip "unattested SDK dexdump probe unavailable because no local SDK is installed"
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
if ! /usr/bin/python3 -I - "$GOOD_JSON" "$DEVICE_PASS_JSON" "$AUTHORITY_JSON" <<'PY'
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
  if ! /usr/bin/python3 -I - "$output" "$class_name" <<'PY' >/dev/null 2>&1
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
  if ! /usr/bin/python3 -I - "$output" "$class_name" "$method_name" <<'PY' >/dev/null 2>&1
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

# Deterministic fail-closed binding probes. The checker first completes the
# pinned fake analysis, then creates a fixed-token state file. This selftest
# performs the replacement itself and acknowledges through that data-only gate;
# the checker never resolves or dispatches a caller-provided executable.
TOCTOU_SERVICES_JAR="$WORK/services-toctou.jar"
TOCTOU_SERVICES_REPLACEMENT="$WORK/services-toctou-replacement.jar"
TOCTOU_SERVICES_JSON="$WORK/services-toctou.json"
TOCTOU_SERVICES_STATE="$TOCTOU_SERVICES_JSON.selftest-post-analysis.state"
make_jar "$TOCTOU_SERVICES_JAR" positive
make_jar "$TOCTOU_SERVICES_REPLACEMENT" missing-method \
  "$(grep -v '^#' "$MEMBERS" | head -n 1 | cut -f1)" \
  "$(grep -v '^#' "$MEMBERS" | head -n 1 | cut -f2)"
TOCTOU_SERVICES_REPLACEMENT_SHA="$(sha256_path "$TOCTOU_SERVICES_REPLACEMENT")"
run_checker_with_after_analysis_swap \
  "$TOCTOU_SERVICES_JAR" "$FAKE_DEXDUMP" "$TOCTOU_SERVICES_JSON" \
  "$TOCTOU_SERVICES_JAR" "$TOCTOU_SERVICES_REPLACEMENT" "$TOCTOU_SERVICES_STATE"
expect_stop "services.jar replacement after analysis is refused" \
  "$TOCTOU_SERVICES_JSON" INPUT_CHANGED
if [ "$SWAP_RC" -ne 0 ] \
    || [ "$(sed -n '1p' "$TOCTOU_SERVICES_STATE" 2>/dev/null || true)" != swapped ] \
    || [ "$(sha256_path "$TOCTOU_SERVICES_JAR")" != "$TOCTOU_SERVICES_REPLACEMENT_SHA" ]; then
  report fail "services.jar TOCTOU replacement is deterministic" \
    "swap_rc=$SWAP_RC state=$(sed -n '1p' "$TOCTOU_SERVICES_STATE" 2>/dev/null || printf missing)"
else
  report ok "services.jar TOCTOU replacement is deterministic"
fi
if [ "$(wc -l <"$DEXDUMP_LOG" | tr -d ' ')" != 1 ]; then
  report fail "services.jar binding refusal occurs after analysis" \
    "fake invocation log=$(tr '\n' ';' <"$DEXDUMP_LOG")"
else
  report ok "services.jar binding refusal occurs after analysis"
fi

TOCTOU_DEXDUMP="$WORK/dexdump-toctou"
TOCTOU_DEXDUMP_REPLACEMENT="$WORK/dexdump-toctou-replacement"
TOCTOU_DEXDUMP_JSON="$WORK/dexdump-toctou.json"
TOCTOU_DEXDUMP_STATE="$TOCTOU_DEXDUMP_JSON.selftest-post-analysis.state"
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
if [ "$SWAP_RC" -ne 0 ] \
    || [ "$(sed -n '1p' "$TOCTOU_DEXDUMP_STATE" 2>/dev/null || true)" != swapped ] \
    || [ "$(sha256_path "$TOCTOU_DEXDUMP")" != "$TOCTOU_DEXDUMP_REPLACEMENT_SHA" ]; then
  report fail "dexdump TOCTOU replacement is deterministic" \
    "swap_rc=$SWAP_RC state=$(sed -n '1p' "$TOCTOU_DEXDUMP_STATE" 2>/dev/null || printf missing)"
else
  report ok "dexdump TOCTOU replacement is deterministic"
fi
if [ "$(wc -l <"$DEXDUMP_LOG" | tr -d ' ')" != 1 ]; then
  report fail "dexdump binding refusal occurs after analysis" \
    "fake invocation log=$(tr '\n' ';' <"$DEXDUMP_LOG")"
else
  report ok "dexdump binding refusal occurs after analysis"
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

printf 'issue66 services compatibility selftest: %d passed, %d failed, %d skipped\n' \
  "$pass" "$fail" "$skip"
[ "$fail" -eq 0 ]

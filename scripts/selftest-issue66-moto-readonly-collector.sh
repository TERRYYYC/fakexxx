#!/usr/bin/env bash
# Device-free RED matrix for the issue #66 Moto read-only preflight collector.
#
# This selftest NEVER addresses a real adb binary:
#   * --adb always names the fixture explicitly;
#   * PATH and the ADB environment variable point to an independent poison;
#   * every invocation is logged and audited for the sole authorized serial;
#   * the fixture itself rejects a mutating adb command.
#
# Expected production CLI (to be implemented after this RED is observed):
#   collect-issue66-moto-readonly-preflight.sh \
#       --reviewed-head <40-lowercase-hex> \
#       --reviewed-collector-sha256 <64-lowercase-hex> \
#       --adb <absolute-fake-or-real-adb> --serial <serial> --output <new-dir>
#   collect-issue66-moto-readonly-preflight.sh \
#       --adb <path> --classify-adb -- <adb argv...>
#   collect-issue66-moto-readonly-preflight.sh \
#       --reviewed-head <40-lowercase-hex> \
#       --reviewed-collector-sha256 <64-lowercase-hex> \
#       --verify-receipts <existing-evidence-root>
#
# The classify mode is pure policy evaluation. It must not execute adb. The
# receipt verifier is also pure host evaluation. Neither mode may execute adb.
# The collector must route every executable adb command through the same exact
# allowlist before execution.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
COLLECTOR="$HERE/collect-issue66-moto-readonly-preflight.sh"
FAKE_ADB="$HERE/fixtures/issue66-moto-readonly-collector/fake-adb.sh"
ADB_ALLOWLIST="$HERE/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
PYTHON_BIN="/usr/bin/python3"
AUTHORIZED_SERIAL="ZY22JHW9M4"
KNOWN_PACKAGES=(
  "name.caiyao.fakegps"
  "name.caiyao.fakegps.bench"
  "name.caiyao.fakegps.codexbench"
  "com.example.cellrebelauto"
  "com.example.cellrebelauto.codexbench"
  "com.cellrebel.mobile"
)
MISSING_FIXTURE_PACKAGE="com.cellrebel.mobile"

# Test-only fault controls must never be inherited from the caller. Several of
# them intentionally replace paths, so every use below is opt-in and rooted in
# this selftest's private WORK directory.
unset \
  FAKE_ADB_LOG \
  FAKE_ADB_REPLACEMENT \
  FAKE_ADB_REPLACE_MARKER \
  FAKE_ADB_REPLACE_SOURCE \
  FAKE_ADB_SCENARIO \
  FAKE_ADB_SNAPSHOT_REPLACE_MARKER \
  FAKE_ADB_SWAP_MARKER \
  FAKE_ADB_SWAP_OUTPUT \
  FAKE_ADB_SWAP_TARGET \
  SELFTEST_PRESNAPSHOT_ADB_MARKER \
  SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT \
  SELFTEST_PRESNAPSHOT_ADB_SOURCE
while IFS='=' read -r inherited_name _; do
  [[ $inherited_name == GIT_* ]] && unset "$inherited_name"
done < <(env)

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

for dependency in grep; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    printf 'selftest dependency missing: %s\n' "$dependency" >&2
    exit 2
  fi
done
if [[ ! -f $PYTHON_BIN || ! -x $PYTHON_BIN ]]; then
  printf 'selftest requires the fixed Python runtime: %s\n' "$PYTHON_BIN" >&2
  exit 2
fi

file_mode() { # one no-follow implementation across Darwin and Linux
  "$PYTHON_BIN" -I - "$1" <<'PY'
import os
import stat
import sys

path = sys.argv[1]
print(format(stat.S_IMODE(os.lstat(path).st_mode), "o"))
PY
}

if [ ! -f "$COLLECTOR" ]; then
  printf 'RED: collector target missing: %s\n' "$COLLECTOR" >&2
  printf 'RED reason: Task 2 production collector has not been implemented yet; this is the expected TDD failure.\n' >&2
  exit 1
fi
if [ ! -x "$FAKE_ADB" ]; then
  printf 'selftest fixture is not executable: %s\n' "$FAKE_ADB" >&2
  exit 2
fi
if grep -Eq -- 'PYTHON_BIN.*! -L|GIT_BIN.*! -L' "$COLLECTOR"; then
  printf 'selftest portability stop: fixed system Python/Git entrypoints must permit distro symlinks\n' >&2
  exit 2
else
  report ok "fixed system Python/Git entrypoints permit distro symlinks"
fi
if ! grep -Fq -- '/usr/bin/git --no-replace-objects' "$COLLECTOR" \
    || ! grep -Fq -- 'GIT_CONFIG_NOSYSTEM=1' "$COLLECTOR" \
    || ! grep -Fq -- 'GIT_CONFIG_SYSTEM=/dev/null' "$COLLECTOR" \
    || ! grep -Fq -- 'GIT_CONFIG_GLOBAL=/dev/null' "$COLLECTOR" \
    || ! grep -Fq -- '-c core.fsmonitor=false' "$COLLECTOR"; then
  printf 'selftest review-binding stop: production Git reads must disable replacement objects, ambient configs, and fsmonitor\n' >&2
  exit 2
else
  report ok "production Git binding disables replacement objects, ambient configs, and fsmonitor"
fi

SELFTEST_REVIEWED_HEAD="$(/usr/bin/env -i PATH=/usr/bin:/bin LC_ALL=C \
  /usr/bin/git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}' 2>/dev/null)" \
  || { printf 'selftest cannot resolve the repository HEAD\n' >&2; exit 2; }
SELFTEST_REVIEWED_COLLECTOR_SHA256="$("$PYTHON_BIN" -I - "$COLLECTOR" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)" || { printf 'selftest cannot hash the collector\n' >&2; exit 2; }
if [[ ! $SELFTEST_REVIEWED_HEAD =~ ^[0-9a-f]{40}$ \
    || ! $SELFTEST_REVIEWED_COLLECTOR_SHA256 =~ ^[0-9a-f]{64}$ ]]; then
  printf 'selftest review-binding fixture is malformed\n' >&2
  exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/issue66-moto-collector-selftest.XXXXXX")"
UNSAFE_OUT="$REPO_ROOT/scripts/fixtures/issue66-moto-readonly-collector/.unsafe-output-$$"
UNSAFE_COMMON_OUT=""
SHELL_ID_FIXTURE_ROOT=""
prepare_cleanup_root() { # root created exclusively by this selftest
  local root=$1
  [ -e "$root" ] || return 0

  # Successful collections deliberately freeze tooling/ at 0500. Removing the
  # adb snapshot only requires restoring owner-write on that containing
  # directory; the evidence itself remains read-only throughout every test.
  find "$root" -type d -name tooling -exec chmod u+w {} + 2>/dev/null || true
}
cleanup() {
  prepare_cleanup_root "$WORK"
  prepare_cleanup_root "$UNSAFE_OUT"
  rm -rf "$WORK"
  rm -rf "$UNSAFE_OUT"
  if [[ -n $UNSAFE_COMMON_OUT && ${UNSAFE_COMMON_OUT##*/} == .issue66-unsafe-output-* ]]; then
    prepare_cleanup_root "$UNSAFE_COMMON_OUT"
    rm -rf "$UNSAFE_COMMON_OUT"
  fi
  if [[ -n $SHELL_ID_FIXTURE_ROOT \
      && $SHELL_ID_FIXTURE_ROOT == "$REPO_ROOT"/.issue66-shell-id-fixture-* ]]; then
    prepare_cleanup_root "$SHELL_ID_FIXTURE_ROOT"
    rm -rf "$SHELL_ID_FIXTURE_ROOT"
  fi
}
trap cleanup EXIT
chmod 700 "$WORK"
mkdir -p "$WORK/bin"

CLEANUP_REGRESSION="$WORK/cleanup-regression"
mkdir -p "$CLEANUP_REGRESSION/tooling"
: >"$CLEANUP_REGRESSION/tooling/adb"
chmod 500 "$CLEANUP_REGRESSION/tooling" "$CLEANUP_REGRESSION/tooling/adb"
prepare_cleanup_root "$CLEANUP_REGRESSION"
if [ -w "$CLEANUP_REGRESSION/tooling" ]; then
  report ok "selftest cleanup restores owner-write on a frozen tooling directory"
else
  report fail "selftest cleanup restores owner-write on a frozen tooling directory"
fi
rm -rf "$CLEANUP_REGRESSION"

# An independent poison executable owns the bare `adb` name. The only way a
# positive collection can reach the fake transport is by honoring --adb.
# A caller that falls back to PATH or the ADB environment variable gets rc=95
# and leaves a distinct poison receipt.
POISON_ADB="$WORK/bin/adb"
POISON_ADB_LOG="$WORK/poison-bare-adb.log"
cat >"$POISON_ADB" <<'POISON'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${POISON_BARE_ADB_LOG:?POISON_BARE_ADB_LOG is required}"
printf 'POISON_BARE_ADB_INVOKED\n' >&2
exit 95
POISON
chmod +x "$POISON_ADB"

# Deterministic host-side fault injection for manifest persistence. Normal runs
# are delegated byte-for-byte to the real utilities. The two named scenarios
# either occupy the initial temporary manifest path before its first write, or
# fail only the second manifest rename (the final COLLECTED replacement).
REAL_MKDIR="$(command -v mkdir)"
REAL_MV="$(command -v mv)"
REAL_PYTHON="$PYTHON_BIN"
MANIFEST_MV_LOG="$WORK/manifest-mv.log"
SUMMARY_ORDER_LOG="$WORK/summary-order.log"
cat >"$WORK/bin/mkdir" <<'MKDIR_FIXTURE'
#!/usr/bin/env bash
real_mkdir="${SELFTEST_REAL_MKDIR:?SELFTEST_REAL_MKDIR is required}"
"$real_mkdir" "$@"
rc=$?
last_argument="${!#}"
if [ "$rc" -eq 0 ] && [ "${FAKE_ADB_SCENARIO:-}" = adb-source-presnapshot-replace ] \
    && [[ $last_argument == receipts || $last_argument == */receipts ]]; then
  root="${SELFTEST_WORK_ROOT:?SELFTEST_WORK_ROOT is required}"
  source_path="${SELFTEST_PRESNAPSHOT_ADB_SOURCE:?source is required}"
  replacement="${SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT:?replacement is required}"
  marker="${SELFTEST_PRESNAPSHOT_ADB_MARKER:?marker is required}"
  for controlled_path in "$source_path" "$replacement" "$marker" "$source_path.next"; do
    case "$controlled_path" in
      "$root"/*) ;;
      *) printf 'unsafe presnapshot fixture path: %s\n' "$controlled_path" >&2; exit 96 ;;
    esac
  done
  : >"$marker" || exit 96
  cp "$replacement" "$source_path.next" || exit 96
  chmod +x "$source_path.next" || exit 96
  mv -f "$source_path.next" "$source_path" || exit 96
fi
exit "$rc"
MKDIR_FIXTURE
cat >"$WORK/bin/mv" <<'MV_FIXTURE'
#!/usr/bin/env bash
real_mv="${SELFTEST_REAL_MV:?SELFTEST_REAL_MV is required}"
real_python="${SELFTEST_REAL_PYTHON:?SELFTEST_REAL_PYTHON is required}"
if [ "${FAKE_ADB_SCENARIO:-}" = manifest-initial-write-failure ] \
    && [ "$#" -eq 3 ] && [ "$1" = -f ] \
    && { [[ $2 == */.manifest.json.tmp ]] || [[ $2 == ./.manifest.json.tmp ]]; } \
    && { [[ $3 == */manifest.json ]] || [[ $3 == ./manifest.json ]]; }; then
  printf 'fixture: refusing initial STOP manifest replacement\n' >&2
  exit 73
fi
if [ "${FAKE_ADB_SCENARIO:-}" = summary-final-replace-failure ] \
    && [ "$#" -eq 3 ] && [ "$1" = -f ] \
    && { [[ $3 == */summary.json ]] || [[ $3 == ./summary.json ]]; }; then
  manifest_path="${3%summary.json}manifest.json"
  if "$real_python" -I -c \
      'import json,sys; raise SystemExit(0 if json.load(open(sys.argv[1], encoding="utf-8")).get("status") == "COLLECTED" else 1)' \
      "$manifest_path" 2>/dev/null; then
    printf 'COLLECTED\n' >"${SELFTEST_SUMMARY_ORDER_LOG:?SELFTEST_SUMMARY_ORDER_LOG is required}"
  else
    printf 'STOP\n' >"${SELFTEST_SUMMARY_ORDER_LOG:?SELFTEST_SUMMARY_ORDER_LOG is required}"
  fi
  printf 'fixture: refusing final summary replacement\n' >&2
  exit 75
fi
if [ "${FAKE_ADB_SCENARIO:-}" = manifest-final-replace-failure ] \
    && [ "$#" -eq 3 ] && [ "$1" = -f ] \
    && { [[ $2 == */.manifest.final.json.tmp ]] || [[ $2 == ./.manifest.final.json.tmp ]]; } \
    && { [[ $3 == */manifest.json ]] || [[ $3 == ./manifest.json ]]; }; then
  printf 'manifest-mv\n' >>"${SELFTEST_MANIFEST_MV_LOG:?SELFTEST_MANIFEST_MV_LOG is required}"
  printf 'fixture: refusing final manifest replacement\n' >&2
  exit 74
fi
exec "$real_mv" "$@"
MV_FIXTURE
chmod +x "$WORK/bin/mkdir" "$WORK/bin/mv"

# Any accidental bare `adb` resolves only to the poison executable. The fake
# fixture remains available solely through the explicit --adb path.
BASE_SELFTEST_PATH="$WORK/bin:$PATH"
SELFTEST_PATH="$BASE_SELFTEST_PATH"
ADB_LOG="$WORK/adb-invocations.log"
OUT=""
RC=0

run_collect() { # scenario serial output-dir [adb-binary]
  local scenario="$1" serial="$2" output_dir="$3"
  local adb_binary="${4:-$FAKE_ADB}"
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  : >"$MANIFEST_MV_LOG"
  : >"$SUMMARY_ORDER_LOG"
  OUT="$(
    PATH="$SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    SELFTEST_REAL_MKDIR="$REAL_MKDIR" \
    SELFTEST_REAL_MV="$REAL_MV" \
    SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
    SELFTEST_WORK_ROOT="$WORK" \
    SELFTEST_MANIFEST_MV_LOG="$MANIFEST_MV_LOG" \
    SELFTEST_SUMMARY_ORDER_LOG="$SUMMARY_ORDER_LOG" \
    FAKE_ADB_SCENARIO="$scenario" \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --selftest-fixture --adb "$adb_binary" \
        --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --serial "$serial" --output "$output_dir" 2>&1
  )"
  RC=$?
}

run_collect_production() { # intentionally omits the selftest trust lane
  local output_dir="$1" adb_binary="${2:-$FAKE_ADB}"
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
    SELFTEST_WORK_ROOT="$WORK" \
    FAKE_ADB_SCENARIO=target \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --adb "$adb_binary" --serial "$AUTHORIZED_SERIAL" \
        --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --output "$output_dir" 2>&1
  )"
  RC=$?
}

run_classify() { # adb argv...
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    FAKE_ADB_SCENARIO=target \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --selftest-fixture --adb "$FAKE_ADB" \
        --classify-adb -- "$@" 2>&1
  )"
  RC=$?
}

run_verify() { # evidence-root
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
    SELFTEST_WORK_ROOT="$WORK" \
    FAKE_ADB_SCENARIO=target \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --selftest-fixture \
        --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --verify-receipts "$1" 2>&1
  )"
  RC=$?
}

run_verify_production() { # evidence-root; intentionally omits selftest lane
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
      "$COLLECTOR" --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --verify-receipts "$1" 2>&1
  )"
  RC=$?
}

run_review_binding_collection_probe() { # output-dir [review-binding args...]
  local output_dir="$1"
  shift
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" "$@" --adb /usr/bin/false \
        --serial "$AUTHORIZED_SERIAL" --output "$output_dir" 2>&1
  )"
  RC=$?
}

run_review_binding_verify_probe() { # evidence-dir [review-binding args...]
  local evidence_dir="$1"
  shift
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" "$@" --verify-receipts "$evidence_dir" 2>&1
  )"
  RC=$?
}

expect_stop() { # case-name expected-marker
  local name="$1" marker="$2"
  if [ "$RC" -eq 0 ]; then
    report fail "$name" "collector returned success; expected $marker; output=$OUT"
  elif [[ "$OUT" != *"$marker"* ]]; then
    report fail "$name" "nonzero rc=$RC but missing exact marker $marker; output=$OUT"
  else
    report ok "$name"
  fi
}

expect_exit_code() { # case-name expected-code
  if [ "$RC" -eq "$2" ]; then
    report ok "$1"
  else
    report fail "$1" "rc=$RC expected=$2 output=$OUT"
  fi
}

expect_no_adb_call() { # case-name
  if [ -s "$ADB_LOG" ] || [ -s "$POISON_ADB_LOG" ]; then
    report fail "$1" "adb must not run before this refusal; fake=$(tr '\n' ';' <"$ADB_LOG") poison=$(tr '\n' ';' <"$POISON_ADB_LOG")"
  else
    report ok "$1"
  fi
}

expect_only_authorized_target() { # case-name
  local bad=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "$line" in
      "devices -l") ;;
      "-s $AUTHORIZED_SERIAL "*) ;;
      *) bad="${bad}${bad:+;}${line}" ;;
    esac
  done <"$ADB_LOG"
  if [ -n "$bad" ]; then
    report fail "$1" "non-inventory adb call escaped the exact serial: $bad"
  else
    report ok "$1"
  fi
}

expect_poison_unused() { # case-name
  if [ -s "$POISON_ADB_LOG" ]; then
    report fail "$1" "collector ignored --adb; poison log=$(tr '\n' ';' <"$POISON_ADB_LOG")"
  else
    report ok "$1"
  fi
}

assert_boot_brackets() { # adb-log report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$AUTHORIZED_SERIAL" <<'PY' 2>&1
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
serial = sys.argv[2]
boot = f"-s {serial} shell cat /proc/sys/kernel/random/boot_id"
uptime = f"-s {serial} shell cat /proc/uptime"
identity = f"-s {serial} shell id"
boot_pos = [i for i, line in enumerate(lines) if line == boot]
uptime_pos = [i for i, line in enumerate(lines) if line == uptime]
if len(boot_pos) != 2 or len(uptime_pos) != 2:
    raise SystemExit(f"boot_id_reads={len(boot_pos)} uptime_reads={len(uptime_pos)} expected=2/2")
if lines[:2] != ["devices -l", identity]:
    raise SystemExit("shell identity is not the first serial-targeted preflight")
if sorted((boot_pos[0], uptime_pos[0])) != [2, 3]:
    raise SystemExit("start boot_id/uptime pair does not immediately follow shell identity")
if sorted((boot_pos[1], uptime_pos[1])) != [len(lines) - 2, len(lines) - 1]:
    raise SystemExit("end boot_id/uptime pair does not follow every targeted observation")
if any(not line.startswith(f"-s {serial} ") for line in lines[1:]):
    raise SystemExit("non-inventory observation escaped the exact target serial")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$2"
  else
    report fail "$2" "$check_out"
  fi
}

assert_manifest_ceiling() { # manifest path
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" <<'PY' 2>&1
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)

expected = {
    "collectionStatus": "COLLECTED",
    "issue66Ac7": "NOT_PASSED",
    "deviceFull": "BLOCKED",
    "durableAck": "NOT_CREATED",
    "fullClaim": "NOT_CREATED",
    "privilegedInspection": "NOT_COLLECTED_PRIVILEGED",
}

errors = [
    f"{key}={manifest.get(key)!r}, expected {value!r}"
    for key, value in expected.items()
    if manifest.get(key) != value
]
if manifest.get("devicePass") is not False:
    errors.append(f"devicePass={manifest.get('devicePass')!r}, expected false")
if manifest.get("status") not in {"COLLECTED", "STATIC_ANALYSIS_PENDING", "COMPATIBILITY_CANDIDATE"}:
    errors.append(f"status={manifest.get('status')!r}, exceeds the compatibility-only ceiling")
if manifest.get("coordinateCaptured") is not False:
    errors.append(f"coordinateCaptured={manifest.get('coordinateCaptured')!r}, expected false")
if manifest.get("compatibility") not in {"STATIC_ANALYSIS_PENDING", "COMPATIBILITY_CANDIDATE"}:
    errors.append(
        f"compatibility={manifest.get('compatibility')!r}, expected STATIC_ANALYSIS_PENDING or COMPATIBILITY_CANDIDATE"
    )
if errors:
    raise SystemExit("; ".join(errors))
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "G-00 manifest cannot claim ACK/FULL/#66 completion"
  else
    report fail "G-00 manifest cannot claim ACK/FULL/#66 completion" "$check_out"
  fi
}

assert_binary_hash_manifest() { # manifest path receipts dir report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import hashlib
import json
import pathlib
import re
import sys

manifest_path = pathlib.Path(sys.argv[1])
receipts_dir = pathlib.Path(sys.argv[2])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

digest_re = re.compile(r"^[0-9a-f]{64}$")

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

services_path = receipts_dir / "services-jar.stdout.bin"
services_digest = manifest.get("servicesJarSha256")
if not isinstance(services_digest, str) or not digest_re.fullmatch(services_digest):
    raise SystemExit(f"servicesJarSha256 is missing or malformed: {services_digest!r}")
actual_services_digest = sha256(services_path)
if services_digest != actual_services_digest:
    raise SystemExit(
        f"servicesJarSha256 mismatch: manifest={services_digest!r} actual={actual_services_digest!r}"
    )

statuses = manifest.get("knownPackages")
if not isinstance(statuses, dict):
    raise SystemExit(f"knownPackages is not an object: {statuses!r}")
expected_installed = {package for package, status in statuses.items() if status == "INSTALLED"}
invalid_statuses = {
    package: status
    for package, status in statuses.items()
    if status not in {"INSTALLED", "NOT_INSTALLED"}
}
if invalid_statuses:
    raise SystemExit(f"knownPackages contains invalid terminal states: {invalid_statuses!r}")

apk_digests = manifest.get("packageApkSha256")
if not isinstance(apk_digests, dict) or set(apk_digests) != expected_installed:
    raise SystemExit(
        "packageApkSha256 must exactly name installed packages: "
        f"expected={sorted(expected_installed)!r} actual={apk_digests!r}"
    )
for package in sorted(expected_installed):
    digest = apk_digests.get(package)
    if not isinstance(digest, str) or not digest_re.fullmatch(digest):
        raise SystemExit(f"packageApkSha256[{package!r}] is malformed: {digest!r}")
    stem = package.replace(".", "-")
    apk_path = receipts_dir / f"package-{stem}-apk.stdout.bin"
    actual = sha256(apk_path)
    if digest != actual:
        raise SystemExit(
            f"packageApkSha256[{package!r}] mismatch: manifest={digest!r} actual={actual!r}"
        )
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_tool_hash_binding() { # manifest summary adb collector allowlist report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" "$3" "$4" "$5" \
    "$SELFTEST_REVIEWED_HEAD" <<'PY' 2>&1
import hashlib
import json
import pathlib
import re
import sys

manifest_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])
adb_path = pathlib.Path(sys.argv[3])
collector_path = pathlib.Path(sys.argv[4])
allowlist_path = pathlib.Path(sys.argv[5])
expected_source_head = sys.argv[6]
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
summary = json.loads(summary_path.read_text(encoding="utf-8"))
digest_re = re.compile(r"^[0-9a-f]{64}$")

expected = {
    "adbSha256": hashlib.sha256(adb_path.read_bytes()).hexdigest(),
    "collectorSha256": hashlib.sha256(collector_path.read_bytes()).hexdigest(),
    "adbAllowlistSha256": hashlib.sha256(allowlist_path.read_bytes()).hexdigest(),
}
errors = []
for key, actual_digest in expected.items():
    manifest_digest = manifest.get(key)
    summary_digest = summary.get(key)
    if not isinstance(manifest_digest, str) or not digest_re.fullmatch(manifest_digest):
        errors.append(f"manifest {key} missing or malformed: {manifest_digest!r}")
    elif manifest_digest != actual_digest:
        errors.append(
            f"manifest {key} mismatch: manifest={manifest_digest!r} actual={actual_digest!r}"
        )
    if not isinstance(summary_digest, str) or not digest_re.fullmatch(summary_digest):
        errors.append(f"summary {key} missing or malformed: {summary_digest!r}")
    elif summary_digest != actual_digest:
        errors.append(
            f"summary {key} mismatch: summary={summary_digest!r} actual={actual_digest!r}"
        )
if errors:
    raise SystemExit("; ".join(errors))

if manifest.get("adbClientTrust") != "SELFTEST_FIXTURE_ONLY__NOT_DEVICE_EVIDENCE":
    errors.append(f"unexpected selftest client trust: {manifest.get('adbClientTrust')!r}")
if manifest.get("adbApprovalLane") != "SELFTEST":
    errors.append(f"unexpected selftest approval lane: {manifest.get('adbApprovalLane')!r}")
if manifest.get("sourceHead") != expected_source_head:
    errors.append(
        f"manifest sourceHead mismatch: {manifest.get('sourceHead')!r} != {expected_source_head!r}"
    )
if manifest.get("sourceHead") != summary.get("sourceHead"):
    errors.append("summary sourceHead does not match manifest")
if not re.fullmatch(r"[0-9a-f]{40}", str(manifest.get("sourceHead", ""))):
    errors.append(f"manifest sourceHead is malformed: {manifest.get('sourceHead')!r}")
rows = []
for line in allowlist_path.read_text(encoding="ascii").splitlines():
    if line.startswith("#"):
        continue
    rows.append(tuple(line.split("\t")))
matching = [
    row for row in rows
    if row == (
        "SELFTEST",
        manifest.get("adbApprovalLabel"),
        manifest.get("adbSha256"),
    )
]
if len(matching) != 1:
    errors.append("manifest selftest ADB digest/lane/label does not match the pinned allowlist")
for key in (
    "adbClientTrust", "adbApprovalLane", "adbApprovalLabel", "adbAllowlistSha256"
):
    if summary.get(key) != manifest.get(key):
        errors.append(f"summary {key} does not match manifest")
if errors:
    raise SystemExit("; ".join(errors))
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$6"
  else
    report fail "$6" "$check_out"
  fi
}

assert_receipt_tree_binding() { # evidence-root report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" <<'PY' 2>&1
import hashlib
import json
import pathlib
import stat
import struct
import sys

root = pathlib.Path(sys.argv[1])
receipts = root / "receipts"
digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
for path in sorted(receipts.iterdir(), key=lambda item: item.name.encode("utf-8")):
    value = path.lstat()
    if not stat.S_ISREG(value.st_mode) or path.is_symlink():
        raise SystemExit(f"non-regular receipt in positive bundle: {path.name}")
    name = path.name.encode("utf-8")
    data = path.read_bytes()
    digest.update(struct.pack(">Q", len(name)))
    digest.update(name)
    digest.update(struct.pack(">Q", len(data)))
    digest.update(data)
actual = digest.hexdigest()
for document in ("manifest.json", "summary.json"):
    parsed = json.loads((root / document).read_text(encoding="utf-8"))
    if parsed.get("receiptTreeSha256") != actual:
        raise SystemExit(
            f"{document} receiptTreeSha256 mismatch: "
            f"declared={parsed.get('receiptTreeSha256')!r} actual={actual!r}"
        )
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$2"
  else
    report fail "$2" "$check_out"
  fi
}

rebind_receipt_tree() { # evidence-root, for semantic-verifier mutation tests
  "$PYTHON_BIN" -I - "$1" <<'PY'
import hashlib
import json
import pathlib
import stat
import struct
import sys

root = pathlib.Path(sys.argv[1])
receipts = root / "receipts"
digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
for path in sorted(receipts.iterdir(), key=lambda item: item.name.encode("utf-8")):
    value = path.lstat()
    if not stat.S_ISREG(value.st_mode) or path.is_symlink():
        raise SystemExit(f"cannot rebind non-regular receipt: {path.name}")
    name = path.name.encode("utf-8")
    data = path.read_bytes()
    digest.update(struct.pack(">Q", len(name)))
    digest.update(name)
    digest.update(struct.pack(">Q", len(data)))
    digest.update(data)
value = digest.hexdigest()
for document in ("manifest.json", "summary.json"):
    path = root / document
    parsed = json.loads(path.read_text(encoding="utf-8"))
    parsed["receiptTreeSha256"] = value
    path.write_text(
        json.dumps(parsed, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
PY
}

rebind_binary_claim() { # evidence-root receipt-name apk-package-or-empty
  "$PYTHON_BIN" -I - "$1" "$2" "${3-}" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
receipt_name = sys.argv[2]
package = sys.argv[3]
digest = hashlib.sha256((root / "receipts" / receipt_name).read_bytes()).hexdigest()
for document_name in ("manifest.json", "summary.json"):
    path = root / document_name
    document = json.loads(path.read_text(encoding="utf-8"))
    if receipt_name == "services-jar.stdout.bin":
        if package:
            raise SystemExit("services claim cannot name a package")
        document["servicesJarSha256"] = digest
    else:
        if not package:
            raise SystemExit("APK claim requires a package")
        document["packageApkSha256"][package] = digest
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
}

assert_redacted_summary() { # summary path manifest path report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import json
import pathlib
import re
import sys

summary_path = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
if not summary_path.is_file() or summary_path.is_symlink():
    raise SystemExit("summary.json missing or symlinked")

summary = json.loads(summary_path.read_text(encoding="utf-8"))
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if not isinstance(summary, dict):
    raise SystemExit("summary.json is not an object")

allowed_keys = {
    "schemaVersion",
    "mode",
    "readOnlySemantics",
    "incidentalEffects",
    "adbServerTrust",
    "adbClientTrust",
    "adbApprovalLane",
    "adbApprovalLabel",
    "adbAllowlistSha256",
    "adbSnapshotPath",
    "status",
    "collectionStatus",
    "compatibility",
    "redacted",
    "coordinateCaptured",
    "authorizedSerial",
    "targetSerial",
    "privilegedInspection",
    "devicePass",
    "issue66Ac7",
    "deviceFull",
    "durableAck",
    "fullClaim",
    "sourceHead",
    "knownPackages",
    "servicesJarSha256",
    "packageApkSha256",
    "adbSha256",
    "collectorSha256",
    "receiptTreeSha256",
    "receiptCount",
}
if set(summary) != allowed_keys:
    raise SystemExit(
        f"summary key whitelist mismatch: missing={sorted(allowed_keys - set(summary))!r} "
        f"extra={sorted(set(summary) - allowed_keys)!r}"
    )

expected_exact = {
    "schemaVersion": 3,
    "mode": "READ_ONLY_PREFLIGHT",
    "readOnlySemantics": "OPERATIONAL_NOT_BIT_FOR_BIT",
    "incidentalEffects": [
        "ADB_TRANSPORT", "TRANSIENT_QUERY_PROCESSES", "DEVICE_AUDIT_ACCOUNTING"
    ],
    "adbServerTrust": "DEFAULT_LOCAL_ENDPOINT_NOT_ATTESTED__INHERITED_ROUTING_REJECTED",
    "adbClientTrust": "SELFTEST_FIXTURE_ONLY__NOT_DEVICE_EVIDENCE",
    "adbApprovalLane": "SELFTEST",
    "adbApprovalLabel": "issue66-fake-adb-536cc861",
    "adbAllowlistSha256": "a52061a3a5410b7fea4703ae51c20e3525f1c4d467c36155f0d556100a63930e",
    "adbSnapshotPath": "tooling/adb",
    "status": "COLLECTED",
    "collectionStatus": "COLLECTED",
    "compatibility": "STATIC_ANALYSIS_PENDING",
    "redacted": True,
    "coordinateCaptured": False,
    "authorizedSerial": "ZY22JHW9M4",
    "targetSerial": "ZY22JHW9M4",
    "privilegedInspection": "NOT_COLLECTED_PRIVILEGED",
    "devicePass": False,
    "issue66Ac7": "NOT_PASSED",
    "deviceFull": "BLOCKED",
    "durableAck": "NOT_CREATED",
    "fullClaim": "NOT_CREATED",
    "sourceHead": manifest.get("sourceHead"),
}
wrong = {
    key: (summary.get(key), expected)
    for key, expected in expected_exact.items()
    if summary.get(key) != expected
}
if wrong:
    raise SystemExit(f"summary ceiling/redaction mismatch: {wrong!r}")

for key in (
    "knownPackages",
    "servicesJarSha256",
    "packageApkSha256",
    "adbSha256",
    "collectorSha256",
    "sourceHead",
    "receiptTreeSha256",
):
    if summary.get(key) != manifest.get(key):
        raise SystemExit(f"summary {key} does not match manifest")
if summary.get("receiptCount") != len(manifest.get("receiptStems", [])):
    raise SystemExit("summary receiptCount does not match manifest receiptStems")

# Exact key/value domains above make raw observation text impossible. Keep an
# explicit lexical guard as a regression tripwire for coordinate-shaped fields.
serialized = json.dumps(summary, ensure_ascii=False, sort_keys=True)
if re.search(r"latitude|longitude|(?:^|[^a-z])lat(?:[^a-z]|$)|(?:^|[^a-z])lon(?:[^a-z]|$)|坐标", serialized, re.I):
    raise SystemExit("summary contains a coordinate-shaped key or value")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_stop_manifest() { # manifest path expected-reason report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)
expected_reason = sys.argv[2]
assert manifest.get("status") == "STOP", manifest
assert manifest.get("reason") == expected_reason, manifest
assert manifest.get("issue66Ac7") == "NOT_PASSED", manifest
assert manifest.get("deviceFull") == "BLOCKED", manifest
assert manifest.get("durableAck") == "NOT_CREATED", manifest
assert manifest.get("fullClaim") == "NOT_CREATED", manifest
assert manifest.get("adbClientTrust") == "SELFTEST_FIXTURE_ONLY__NOT_DEVICE_EVIDENCE", manifest
assert manifest.get("adbApprovalLane") == "SELFTEST", manifest
assert manifest.get("adbApprovalLabel") == "issue66-fake-adb-536cc861", manifest
assert manifest.get("adbAllowlistSha256") == "a52061a3a5410b7fea4703ae51c20e3525f1c4d467c36155f0d556100a63930e", manifest
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_six_file_receipts() { # manifest path receipts dir [report-label]
  local check_out check_rc
  local label="${3:-G-00 manifest stems each own one strict six-file receipt}"
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import datetime
import json
import pathlib
import re
import sys

manifest_path = pathlib.Path(sys.argv[1])
receipts_dir = pathlib.Path(sys.argv[2])
with manifest_path.open(encoding="utf-8") as stream:
    manifest = json.load(stream)

stems = manifest.get("receiptStems")
if not isinstance(stems, list) or not stems:
    raise SystemExit("manifest receiptStems must be a non-empty array")
if len(stems) != len(set(stems)):
    raise SystemExit("manifest receiptStems contains duplicates")

index_path = receipts_dir / "stems.txt"
indexed = index_path.read_text(encoding="utf-8").splitlines()
if indexed != stems:
    raise SystemExit(f"manifest/index stem mismatch: manifest={stems!r} index={indexed!r}")

rfc3339 = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
stem_pattern = re.compile(r"^[a-z0-9][a-z0-9-]*$")
accounted = {index_path.name}
for stem in stems:
    if not isinstance(stem, str) or not stem_pattern.fullmatch(stem):
        raise SystemExit(f"unsafe receipt stem: {stem!r}")
    stdout_candidates = [
        receipts_dir / f"{stem}.stdout.txt",
        receipts_dir / f"{stem}.stdout.bin",
    ]
    present_stdout = [path for path in stdout_candidates if path.is_file()]
    if len(present_stdout) != 1:
        raise SystemExit(f"{stem}: expected exactly one stdout txt/bin, got {present_stdout!r}")
    required = [
        receipts_dir / f"{stem}.command.txt",
        receipts_dir / f"{stem}.start-utc.txt",
        present_stdout[0],
        receipts_dir / f"{stem}.stderr.bin",
        receipts_dir / f"{stem}.exit.txt",
        receipts_dir / f"{stem}.end-utc.txt",
    ]
    actual = sorted(receipts_dir.glob(f"{stem}.*"))
    if len(actual) != 6 or {path.name for path in actual} != {path.name for path in required}:
        raise SystemExit(f"{stem}: not a strict six-file receipt: {[path.name for path in actual]!r}")
    for path in required:
        if not path.is_file():
            raise SystemExit(f"{stem}: missing {path.name}")
        accounted.add(path.name)
    exit_text = (receipts_dir / f"{stem}.exit.txt").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"\d+", exit_text):
        raise SystemExit(f"{stem}: exit is not unsigned decimal: {exit_text!r}")
    stamps = []
    for suffix in ("start-utc.txt", "end-utc.txt"):
        text = (receipts_dir / f"{stem}.{suffix}").read_text(encoding="ascii").strip()
        if not rfc3339.fullmatch(text):
            raise SystemExit(f"{stem}: {suffix} is not RFC3339 UTC: {text!r}")
        stamps.append(datetime.datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ"))
    if stamps[1] < stamps[0]:
        raise SystemExit(f"{stem}: end timestamp precedes start timestamp")

extras = sorted(path.name for path in receipts_dir.iterdir() if path.is_file() and path.name not in accounted)
if extras:
    raise SystemExit(f"unmanifested receipt files: {extras!r}")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$label"
  else
    report fail "$label" "$check_out"
  fi
}

assert_public_collection() { # manifest receipts adb-log missing-package-or-- report-label
  local check_out check_rc
  local manifest_path="$1" receipts_dir="$2" adb_log="$3" missing_pkg="$4" label="$5"
  shift 5
  check_out="$("$PYTHON_BIN" -I - "$manifest_path" "$receipts_dir" "$adb_log" "$missing_pkg" "$@" <<'PY' 2>&1
import json
import pathlib
import re
import shlex
import sys

manifest_path = pathlib.Path(sys.argv[1])
receipts_dir = pathlib.Path(sys.argv[2])
adb_log = pathlib.Path(sys.argv[3])
missing = set() if sys.argv[4] == "-" else {sys.argv[4]}
known = sys.argv[5:]
serial = "ZY22JHW9M4"

manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
lines = adb_log.read_text(encoding="utf-8").splitlines()
stems = manifest.get("receiptStems", [])
commands = {}
for stem in stems:
    command = (receipts_dir / f"{stem}.command.txt").read_text(encoding="utf-8").strip()
    try:
        parsed = shlex.split(command)
    except ValueError as error:
        raise SystemExit(f"invalid shell-escaped receipt command: {stem!r}: {error}")
    if not parsed:
        raise SystemExit(f"empty receipt command: {stem!r}")
    if parsed[0] != "./tooling/adb":
        raise SystemExit(
            f"receipt command does not name the private adb snapshot: {stem!r} -> {parsed[0]!r}"
        )
    commands[stem] = tuple(parsed[1:])

required_once = [
    "devices -l",
    f"-s {serial} get-state",
    f"-s {serial} shell getprop ro.serialno",
    f"-s {serial} shell getprop ro.product.manufacturer",
    f"-s {serial} shell getprop ro.build.version.sdk",
    f"-s {serial} shell getprop ro.build.fingerprint",
    f"-s {serial} shell getprop ro.product.model",
    f"-s {serial} shell getprop ro.product.device",
    f"-s {serial} shell getprop ro.product.cpu.abilist",
    f"-s {serial} shell getprop ro.zygote",
    f"-s {serial} shell getprop sys.boot_completed",
    f"-s {serial} shell getprop ro.build.version.release",
    f"-s {serial} shell id",
    f"-s {serial} shell getenforce",
    f"-s {serial} shell am get-current-user",
    f"-s {serial} shell ps -A -o USER,PID,NAME",
    f"-s {serial} shell cmd location is-location-enabled --user 0",
    f"-s {serial} exec-out cat /system/framework/services.jar",
]
for package in known:
    required_once.append(f"-s {serial} shell pm path {package}")
    if package not in missing:
        required_once.extend(
            [
            f"-s {serial} shell dumpsys package {package}",
            f"-s {serial} shell pidof {package}",
                f"-s {serial} shell appops get --user 0 {package} android:mock_location",
            ]
        )
        required_once.append(
            f"-s {serial} exec-out cat /data/app/~~issue66/{package}-fixture/base.apk"
        )

expected_counts = {expected: 1 for expected in required_once}
expected_counts[f"-s {serial} shell cat /proc/sys/kernel/random/boot_id"] = 2
expected_counts[f"-s {serial} shell cat /proc/uptime"] = 2
wrong_counts = [
    f"{expected!r}: got {lines.count(expected)}, expected {count}"
    for expected, count in expected_counts.items()
    if lines.count(expected) != count
]
unexpected = [line for line in lines if line not in expected_counts]
if wrong_counts or unexpected:
    raise SystemExit(
        "adb command-surface mismatch: "
        + "; ".join(wrong_counts)
        + (f"; unexpected={unexpected!r}" if unexpected else "")
    )

# Compare the two command multisets. command.txt is written with bash `%q`, so
# parse that representation and discard only the executable path before doing
# exact argv-tuple comparisons. Start/end boot_id and uptime intentionally
# repeat the same argv; carrier multiplicity must equal invocation multiplicity.
expected_argv = {
    tuple(shlex.split(expected)): expected
    for expected in expected_counts
}
carrier_counts = {argv: 0 for argv in expected_argv}
for stem, argv in commands.items():
    if argv not in carrier_counts:
        raise SystemExit(
            f"receipt carrier does not bind one frozen adb argv: {stem!r} -> {argv!r}"
        )
    carrier_counts[argv] += 1
carrier_mismatch = [
    f"{expected!r}: adb={lines.count(expected)} carriers={carrier_counts[argv]}"
    for argv, expected in expected_argv.items()
    if carrier_counts[argv] != lines.count(expected)
]
if carrier_mismatch or len(commands) != len(lines):
    raise SystemExit(
        "adb/receipt command multiset mismatch: "
        + "; ".join(carrier_mismatch)
        + f"; adb_total={len(lines)} carrier_total={len(commands)}"
    )

binary_commands = [
    tuple(shlex.split(f"-s {serial} exec-out cat /system/framework/services.jar"))
]
binary_commands.extend(
    tuple(shlex.split(f"-s {serial} exec-out cat /data/app/~~issue66/{package}-fixture/base.apk"))
    for package in known
    if package not in missing
)
for expected in binary_commands:
    matches = [stem for stem, argv in commands.items() if argv == expected]
    if len(matches) != 1:
        raise SystemExit(f"binary command missing/duplicated in receipts: {expected!r}")
    stem = matches[0]
    if not (receipts_dir / f"{stem}.stdout.bin").is_file():
        raise SystemExit(f"{stem}: binary command lacks stdout.bin")
    if (receipts_dir / f"{stem}.stdout.txt").exists():
        raise SystemExit(f"{stem}: binary command also has stdout.txt")

statuses = manifest.get("knownPackages")
if not isinstance(statuses, dict) or set(statuses) != set(known):
    raise SystemExit(f"knownPackages must be an exact fixed-package map: {statuses!r}")
for package in known:
    expected = "NOT_INSTALLED" if package in missing else "INSTALLED"
    if statuses.get(package) != expected:
        raise SystemExit(f"knownPackages[{package!r}]={statuses.get(package)!r}, expected {expected!r}")

forbidden = [
    r"(^| )su( |$)",
    r"(^| )root( |$)",
    r"(^| )logcat( |$)",
    r"dumpsys location",
    r"(^| )am start( |$)",
    r"(^| )install(-multiple)?( |$)",
    r"(^| )uninstall( |$)",
    r"(^| )push( |$)",
    r"settings (put|delete)",
    r"appops (set|reset)",
    r"(^| )pm clear( |$)",
    r"force-stop| am crash | reboot| remount|set-location-enabled|test-provider",
    r"latitude|longitude|\blat\b|\blon\b",
]
for line in lines:
    for pattern in forbidden:
        if re.search(pattern, line, flags=re.IGNORECASE):
            raise SystemExit(f"forbidden public-collector adb surface: {line!r} matched {pattern!r}")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$label"
  else
    report fail "$label" "$check_out"
  fi
}

assert_no_privileged_fallback() { # adb-log report-label
  if grep -Eiq -- '(^| )su( |$)|(^| )root( |$)|logcat|/data/adb|dumpsys location' "$1"; then
    report fail "$2" "privileged/private fallback found: $(grep -Ei -- '(^| )su( |$)|(^| )root( |$)|logcat|/data/adb|dumpsys location' "$1" | tr '\n' ';')"
  else
    report ok "$2"
  fi
}

assert_classify_stop() { # label marker argv...
  local label="$1" marker="$2"
  shift 2
  run_classify "$@"
  expect_stop "classifier rejects $label" "$marker"
  expect_exit_code "classifier $label uses local-safety rc=22" 22
  expect_no_adb_call "classifier $label performs no adb call"
}

assert_classify_allow() { # label argv...
  local label="$1"
  shift
  run_classify "$@"
  if [ "$RC" -eq 0 ] && [[ $OUT == *ALLOW_READ_ONLY* ]]; then
    report ok "classifier allows $label"
  else
    report fail "classifier allows $label" "rc=$RC output=$OUT"
  fi
  expect_no_adb_call "classifier allow-check $label performs no adb call"
}

assert_no_adb_after() { # adb-log exact-anchor report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
anchor = sys.argv[2]
positions = [index for index, line in enumerate(lines) if line == anchor]
if len(positions) != 1:
    raise SystemExit(f"anchor count={len(positions)}, expected 1: {anchor!r}")
tail = lines[positions[0] + 1:]
if tail:
    raise SystemExit(f"adb calls escaped fail-fast boundary: {tail!r}")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

# Device collection and offline verification are both authorized against an
# independently reviewed Git commit plus the exact collector bytes. Missing,
# malformed, or stale bindings fail before an output directory or adb process
# can exist. A valid production binding proceeds to the independent ADB-client
# approval gate; /usr/bin/false is deliberately not enrolled there.
BINDING_MISSING_OUT="$WORK/out-review-binding-missing"
run_review_binding_collection_probe "$BINDING_MISSING_OUT"
expect_stop "collection requires an external review binding" STOP_REVIEW_BINDING_REQUIRED
expect_exit_code "missing collection review binding uses local-safety rc=22" 22
expect_no_adb_call "missing collection review binding performs no adb call"
if [ -e "$BINDING_MISSING_OUT" ]; then
  report fail "missing collection review binding creates no evidence root" \
    "unexpected output=$BINDING_MISSING_OUT"
else
  report ok "missing collection review binding creates no evidence root"
fi

BINDING_WRONG_HEAD_OUT="$WORK/out-review-binding-wrong-head"
run_review_binding_collection_probe "$BINDING_WRONG_HEAD_OUT" \
  --reviewed-head "0000000000000000000000000000000000000000" \
  --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256"
expect_stop "collection rejects a stale reviewed HEAD" STOP_REVIEW_BINDING_MISMATCH
expect_exit_code "stale reviewed HEAD uses local-safety rc=22" 22
expect_no_adb_call "stale reviewed HEAD performs no adb call"

BINDING_WRONG_DIGEST_OUT="$WORK/out-review-binding-wrong-digest"
run_review_binding_collection_probe "$BINDING_WRONG_DIGEST_OUT" \
  --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
  --reviewed-collector-sha256 "0000000000000000000000000000000000000000000000000000000000000000"
expect_stop "collection rejects stale reviewed collector bytes" STOP_REVIEW_BINDING_MISMATCH
expect_exit_code "stale reviewed collector digest uses local-safety rc=22" 22
expect_no_adb_call "stale reviewed collector digest performs no adb call"

BINDING_UPPERCASE_OUT="$WORK/out-review-binding-uppercase"
BINDING_UPPERCASE_HEAD="$(printf '%s' "$SELFTEST_REVIEWED_HEAD" | tr '[:lower:]' '[:upper:]')"
run_review_binding_collection_probe "$BINDING_UPPERCASE_OUT" \
  --reviewed-head "$BINDING_UPPERCASE_HEAD" \
  --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256"
expect_stop "collection rejects noncanonical reviewed HEAD text" STOP_REVIEW_BINDING_MISMATCH
expect_exit_code "noncanonical reviewed HEAD uses local-safety rc=22" 22
expect_no_adb_call "noncanonical reviewed HEAD performs no adb call"

BINDING_ACCEPTED_OUT="$WORK/out-review-binding-accepted"
printf '[broken git config\n' >"$WORK/hostile-gitconfig"
export GIT_DIR="$WORK/ambient-git-dir-must-be-ignored"
export GIT_WORK_TREE="$WORK/ambient-work-tree-must-be-ignored"
export GIT_OBJECT_DIRECTORY="$WORK/ambient-object-dir-must-be-ignored"
export GIT_ALTERNATE_OBJECT_DIRECTORIES="$WORK/ambient-alternates-must-be-ignored"
export GIT_REPLACE_REF_BASE="refs/ambient-replacements-must-be-ignored"
export GIT_CONFIG_NOSYSTEM=0
export GIT_CONFIG_SYSTEM="$WORK/hostile-gitconfig"
export GIT_CONFIG_GLOBAL="$WORK/hostile-gitconfig"
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=include.path
export GIT_CONFIG_VALUE_0="$WORK/hostile-gitconfig"
run_review_binding_collection_probe "$BINDING_ACCEPTED_OUT" \
  --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
  --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256"
unset GIT_DIR GIT_WORK_TREE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES \
  GIT_REPLACE_REF_BASE GIT_CONFIG_NOSYSTEM GIT_CONFIG_SYSTEM GIT_CONFIG_GLOBAL \
  GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
expect_stop "valid review binding ignores hostile Git environment and reaches ADB approval" \
  STOP_ADB_CLIENT_UNAPPROVED
expect_exit_code "valid review binding reaches ADB approval rc=22" 22
expect_no_adb_call "review binding acceptance does not execute an unapproved adb"
if [ -e "$BINDING_ACCEPTED_OUT" ]; then
  report fail "review binding is checked before evidence-root creation" \
    "unexpected output=$BINDING_ACCEPTED_OUT"
else
  report ok "review binding is checked before evidence-root creation"
fi

run_review_binding_verify_probe "$WORK/absent-review-binding-evidence"
expect_stop "offline verification also requires an external review binding" \
  STOP_REVIEW_BINDING_REQUIRED
expect_exit_code "missing verifier review binding uses local-safety rc=22" 22
expect_no_adb_call "missing verifier review binding performs no adb call"

# Client trust is separate from command classification. The device-facing lane
# accepts only the repo-enrolled production ADB digest; the fake is usable only
# through the explicit SELFTEST lane and can never masquerade as device proof.
UNAPPROVED_PRODUCTION_OUT="$WORK/out-unapproved-production-adb"
run_collect_production "$UNAPPROVED_PRODUCTION_OUT"
expect_stop "fake adb is not approved in the production lane" \
  STOP_ADB_CLIENT_UNAPPROVED
expect_exit_code "unapproved production adb uses local-safety rc=22" 22
expect_no_adb_call "unapproved production adb is rejected before execution"
if [ -e "$UNAPPROVED_PRODUCTION_OUT" ]; then
  report fail "unapproved production adb creates no output tree" \
    "unexpected output=$UNAPPROVED_PRODUCTION_OUT"
else
  report ok "unapproved production adb creates no output tree"
fi

TAMPERED_FAKE_ADB="$WORK/tampered-fake-adb"
cp "$FAKE_ADB" "$TAMPERED_FAKE_ADB"
printf '\n# digest tamper\n' >>"$TAMPERED_FAKE_ADB"
chmod 700 "$TAMPERED_FAKE_ADB"
TAMPERED_FAKE_OUT="$WORK/out-tampered-fake-adb"
run_collect target "$AUTHORIZED_SERIAL" "$TAMPERED_FAKE_OUT" "$TAMPERED_FAKE_ADB"
expect_stop "modified fake adb is rejected from the SELFTEST lane" \
  STOP_ADB_CLIENT_UNAPPROVED
expect_exit_code "modified fake adb rejection uses local-safety rc=22" 22
expect_no_adb_call "modified fake adb is rejected before execution"

ATTESTATION_COPY="$WORK/attestation-copy"
ATTESTATION_COLLECTOR="$ATTESTATION_COPY/collect-issue66-moto-readonly-preflight.sh"
mkdir -p "$ATTESTATION_COPY/fixtures/issue66-moto-readonly-collector"
cp "$COLLECTOR" "$ATTESTATION_COLLECTOR"
cp "$ADB_ALLOWLIST" \
  "$ATTESTATION_COPY/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
chmod 700 "$ATTESTATION_COLLECTOR"
ATTESTATION_COPY_DIGEST="$("$PYTHON_BIN" -I - "$ATTESTATION_COLLECTOR" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
printf '# unauthorized allowlist rewrite\n' >> \
  "$ATTESTATION_COPY/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
: >"$ADB_LOG"
: >"$POISON_ADB_LOG"
BROKEN_ALLOWLIST_OUT="$WORK/out-broken-adb-allowlist"
OUT="$(
  PATH="$BASE_SELFTEST_PATH" \
  ADB="$POISON_ADB" \
  POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
  FAKE_ADB_LOG="$ADB_LOG" \
    "$ATTESTATION_COLLECTOR" --selftest-fixture --adb "$FAKE_ADB" \
      --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
      --reviewed-collector-sha256 "$ATTESTATION_COPY_DIGEST" \
      --serial "$AUTHORIZED_SERIAL" --output "$BROKEN_ALLOWLIST_OUT" 2>&1
)"
RC=$?
expect_stop "modified repo ADB allowlist fails closed" STOP_INTERNAL_ADB_ALLOWLIST
expect_exit_code "modified repo ADB allowlist uses internal rc=70" 70
expect_no_adb_call "modified repo ADB allowlist is rejected before execution"

# Exercise the online shell-identity gate with an independently enrolled,
# device-free wrapper. The copied collector remains in the SELFTEST lane; its
# private allowlist pins only this wrapper digest. A shell UID paired with a
# root primary GID must stop after the identity command even when the trailing
# groups/context fields otherwise look like canonical Android toybox output.
SHELL_ID_FIXTURE_ROOT="$REPO_ROOT/.issue66-shell-id-fixture-$$"
SHELL_ID_FIXTURE_COLLECTOR="$SHELL_ID_FIXTURE_ROOT/collect-issue66-moto-readonly-preflight.sh"
SHELL_ID_FIXTURE_ADB="$SHELL_ID_FIXTURE_ROOT/shell-id-fake-adb.sh"
SHELL_ID_FIXTURE_ALLOWLIST="$SHELL_ID_FIXTURE_ROOT/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
mkdir -p "$SHELL_ID_FIXTURE_ROOT/fixtures/issue66-moto-readonly-collector"
cp "$COLLECTOR" "$SHELL_ID_FIXTURE_COLLECTOR"
cat >"$SHELL_ID_FIXTURE_ADB" <<'SHELL_ID_ADB'
#!/usr/bin/env bash
if [ "$*" = 'devices -l' ] && [ -n "${SELFTEST_COLLECTOR_TAMPER_TARGET:-}" ]; then
  target="${SELFTEST_COLLECTOR_TAMPER_TARGET}"
  marker="${SELFTEST_COLLECTOR_TAMPER_MARKER:?SELFTEST_COLLECTOR_TAMPER_MARKER is required}"
  case "$target:$marker" in
    "${SELFTEST_COLLECTOR_TAMPER_ROOT:?SELFTEST_COLLECTOR_TAMPER_ROOT is required}"/*:"$SELFTEST_COLLECTOR_TAMPER_ROOT"/*) ;;
    *) printf 'unsafe collector-tamper fixture path\n' >&2; exit 96 ;;
  esac
  if [ ! -e "$marker" ]; then
    : >"$marker" || exit 96
    printf '\n# selftest runtime collector tamper\n' >>"$target" || exit 96
  fi
fi
if [ "$*" = '-s ZY22JHW9M4 shell id' ]; then
  printf '%s\n' "$*" >>"${FAKE_ADB_LOG:?FAKE_ADB_LOG is required}"
  printf '%s\n' "${SELFTEST_SHELL_ID_OVERRIDE:?SELFTEST_SHELL_ID_OVERRIDE is required}"
  exit 0
fi
exec "${SELFTEST_DELEGATE_ADB:?SELFTEST_DELEGATE_ADB is required}" "$@"
SHELL_ID_ADB
chmod 700 "$SHELL_ID_FIXTURE_COLLECTOR" "$SHELL_ID_FIXTURE_ADB"
SHELL_ID_FIXTURE_ADB_DIGEST="$("$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_ADB" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
"$PYTHON_BIN" -I - "$ADB_ALLOWLIST" "$SHELL_ID_FIXTURE_ALLOWLIST" \
  "$SHELL_ID_FIXTURE_ADB_DIGEST" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="ascii")
destination = pathlib.Path(sys.argv[2])
digest = sys.argv[3]
lines = []
replaced = 0
for line in source.splitlines():
    if line.startswith("SELFTEST\t"):
        line = f"SELFTEST\tissue66-shell-id-online\t{digest}"
        replaced += 1
    lines.append(line)
if replaced != 1:
    raise SystemExit("expected one SELFTEST allowlist row")
destination.write_text("\n".join(lines) + "\n", encoding="ascii")
PY
SHELL_ID_FIXTURE_ALLOWLIST_DIGEST="$("$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_ALLOWLIST" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
"$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_COLLECTOR" \
  "$SHELL_ID_FIXTURE_ALLOWLIST_DIGEST" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
replacement = f'ADB_ALLOWLIST_EXPECTED_SHA256="{sys.argv[2]}"'
text, count = re.subn(
    r'ADB_ALLOWLIST_EXPECTED_SHA256="[0-9a-f]{64}"',
    replacement,
    text,
    count=1,
)
if count != 1:
    raise SystemExit("collector allowlist digest assignment not found exactly once")
path.write_text(text, encoding="utf-8")
PY
SHELL_ID_FIXTURE_COLLECTOR_DIGEST="$("$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_COLLECTOR" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
: >"$ADB_LOG"
: >"$POISON_ADB_LOG"
SHELL_ID_MIXED_OUT="$WORK/out-shell-id-mixed-primary-root"
OUT="$(
  PATH="$BASE_SELFTEST_PATH" \
  ADB="$POISON_ADB" \
  POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
  SELFTEST_REAL_MKDIR="$REAL_MKDIR" \
  SELFTEST_REAL_MV="$REAL_MV" \
  SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
  SELFTEST_WORK_ROOT="$WORK" \
  SELFTEST_MANIFEST_MV_LOG="$MANIFEST_MV_LOG" \
  SELFTEST_SUMMARY_ORDER_LOG="$SUMMARY_ORDER_LOG" \
  SELFTEST_COLLECTOR_TAMPER_ROOT="$SHELL_ID_FIXTURE_ROOT" \
  SELFTEST_DELEGATE_ADB="$FAKE_ADB" \
  SELFTEST_SHELL_ID_OVERRIDE='uid=2000(shell) gid=0(root) groups=2000(shell) context=u:r:shell:s0' \
  FAKE_ADB_SCENARIO=target \
  FAKE_ADB_LOG="$ADB_LOG" \
    "$SHELL_ID_FIXTURE_COLLECTOR" --selftest-fixture \
      --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
      --reviewed-collector-sha256 "$SHELL_ID_FIXTURE_COLLECTOR_DIGEST" \
      --adb "$SHELL_ID_FIXTURE_ADB" --serial "$AUTHORIZED_SERIAL" \
      --output "$SHELL_ID_MIXED_OUT" 2>&1
)"
RC=$?
expect_stop "shell uid with root primary gid is refused online" \
  STOP_UNPRIVILEGED_SHELL_REQUIRED
expect_exit_code "mixed shell/root identity uses topology/identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell id" \
  "mixed shell/root identity stops every later device read"
expect_only_authorized_target "mixed shell/root identity remains exact-serial scoped"

: >"$ADB_LOG"
: >"$POISON_ADB_LOG"
COLLECTOR_TAMPER_OUT="$WORK/out-runtime-collector-tamper"
COLLECTOR_TAMPER_MARKER="$SHELL_ID_FIXTURE_ROOT/runtime-collector-tampered.marker"
OUT="$(
  PATH="$BASE_SELFTEST_PATH" \
  ADB="$POISON_ADB" \
  POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
  SELFTEST_REAL_MKDIR="$REAL_MKDIR" \
  SELFTEST_REAL_MV="$REAL_MV" \
  SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
  SELFTEST_WORK_ROOT="$WORK" \
  SELFTEST_MANIFEST_MV_LOG="$MANIFEST_MV_LOG" \
  SELFTEST_SUMMARY_ORDER_LOG="$SUMMARY_ORDER_LOG" \
  SELFTEST_COLLECTOR_TAMPER_ROOT="$SHELL_ID_FIXTURE_ROOT" \
  SELFTEST_DELEGATE_ADB="$FAKE_ADB" \
  SELFTEST_COLLECTOR_TAMPER_TARGET="$SHELL_ID_FIXTURE_COLLECTOR" \
  SELFTEST_COLLECTOR_TAMPER_MARKER="$COLLECTOR_TAMPER_MARKER" \
  FAKE_ADB_SCENARIO=target \
  FAKE_ADB_LOG="$ADB_LOG" \
    "$SHELL_ID_FIXTURE_COLLECTOR" --selftest-fixture \
      --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
      --reviewed-collector-sha256 "$SHELL_ID_FIXTURE_COLLECTOR_DIGEST" \
      --adb "$SHELL_ID_FIXTURE_ADB" --serial "$AUTHORIZED_SERIAL" \
      --output "$COLLECTOR_TAMPER_OUT" 2>&1
)"
RC=$?
expect_stop "collector bytes changed after the first ADB receipt are refused" \
  STOP_REVIEW_BINDING_CHANGED
expect_exit_code "runtime collector change uses local-safety rc=22" 22
assert_no_adb_after "$ADB_LOG" "devices -l" \
  "runtime collector change stops before the next ADB receipt"
if [ -e "$COLLECTOR_TAMPER_MARKER" ]; then
  report ok "runtime collector-change fixture actually changed the reviewed entrypoint"
else
  report fail "runtime collector-change fixture actually changed the reviewed entrypoint" \
    "tamper marker missing"
fi

# G-00 is the non-vacuous positive control. A fully valid fake identity may
# complete only as a compatibility candidate, never as device/#66/FULL proof.
G00_OUT="$WORK/out-g00"
run_collect target "$AUTHORIZED_SERIAL" "$G00_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "G-00 positive control returns rc=0"
else
  report fail "G-00 positive control returns rc=0" "rc=$RC output=$OUT"
fi
expect_poison_unused "G-00 honors the explicit --adb binary"
if [ -s "$ADB_LOG" ]; then
  report ok "G-00 actually exercises the fake adb"
else
  report fail "G-00 actually exercises the fake adb" "fake adb log is empty"
fi
assert_boot_brackets "$ADB_LOG" "G-00 brackets collection with boot_id and uptime"
if [ -d "$G00_OUT" ]; then
  g00_mode="$(file_mode "$G00_OUT")"
  if [ "$g00_mode" = 700 ]; then
    report ok "G-00 evidence root is mode 0700"
  else
    report fail "G-00 evidence root is mode 0700" "mode=$g00_mode"
  fi
else
  report fail "G-00 evidence root is mode 0700" "output directory missing"
fi
if [ -d "$G00_OUT/tooling" ] && [ -f "$G00_OUT/tooling/adb" ]; then
  tooling_mode="$(file_mode "$G00_OUT/tooling")"
  adb_snapshot_mode="$(file_mode "$G00_OUT/tooling/adb")"
  if [ "$tooling_mode" = 500 ] && [ "$adb_snapshot_mode" = 500 ]; then
    report ok "G-00 freezes the private adb snapshot and tooling directory at mode 0500"
  else
    report fail "G-00 freezes the private adb snapshot and tooling directory at mode 0500" \
      "tooling=$tooling_mode adb=$adb_snapshot_mode"
  fi
else
  report fail "G-00 private adb snapshot exists" "missing tooling/adb"
fi
if [ -f "$G00_OUT/manifest.json" ]; then
  assert_manifest_ceiling "$G00_OUT/manifest.json"
  assert_binary_hash_manifest "$G00_OUT/manifest.json" "$G00_OUT/receipts" \
    "G-00 manifest binds services.jar and every installed APK SHA-256"
  assert_tool_hash_binding "$G00_OUT/manifest.json" "$G00_OUT/summary.json" \
    "$G00_OUT/tooling/adb" "$COLLECTOR" "$ADB_ALLOWLIST" \
    "G-00 manifest and summary bind the executed adb snapshot/collector SHA-256"
  assert_receipt_tree_binding "$G00_OUT" \
    "G-00 manifest and summary bind an independently recomputed receipt-tree SHA-256"
  assert_redacted_summary "$G00_OUT/summary.json" "$G00_OUT/manifest.json" \
    "G-00 emits an exact-whitelist coordinate-free summary"
  assert_six_file_receipts "$G00_OUT/manifest.json" "$G00_OUT/receipts"
  assert_public_collection "$G00_OUT/manifest.json" "$G00_OUT/receipts" "$ADB_LOG" - \
    "G-00 captures the complete shell-gated public/static surface" "${KNOWN_PACKAGES[@]}"
  assert_no_privileged_fallback "$ADB_LOG" "G-00 has no privileged/private fallback"
else
  report fail "G-00 manifest exists" "missing $G00_OUT/manifest.json"
fi

# The explicitly selected adb executable is part of the evidence identity. A
# symlink could be retargeted between validation, hashing, and execution, so it
# must be rejected before either the fake transport or the PATH poison runs.
ADB_SYMLINK="$WORK/fake-adb-symlink"
if ! ln -s "$FAKE_ADB" "$ADB_SYMLINK"; then
  printf 'selftest could not create adb symlink fixture: %s\n' "$ADB_SYMLINK" >&2
  exit 2
fi
SYMLINK_ADB_OUT="$WORK/out-symlink-adb"
run_collect target "$AUTHORIZED_SERIAL" "$SYMLINK_ADB_OUT" "$ADB_SYMLINK"
expect_stop "symlink --adb binary is refused" STOP_INVALID_ADB_BINARY
expect_exit_code "symlink --adb binary uses local-safety rc=22" 22
expect_no_adb_call "symlink --adb refusal performs no fake or poison adb call"

# Inherited ADB routing can silently redirect a trusted client to a different
# server. Every supported routing variable is rejected before output or adb.
for server_var in ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT; do
  case "$server_var" in
    ADB_SERVER_SOCKET) server_value=tcp:attacker.invalid:5037 ;;
    ANDROID_ADB_SERVER_ADDRESS) server_value=attacker.invalid ;;
    ANDROID_ADB_SERVER_PORT) server_value=5038 ;;
  esac
  export "$server_var=$server_value"
  server_out="$WORK/out-server-env-$server_var"
  run_collect target "$AUTHORIZED_SERIAL" "$server_out"
  unset "$server_var"
  expect_stop "$server_var routing override is refused" STOP_UNSAFE_ADB_SERVER_ENV
  expect_exit_code "$server_var routing override uses local-safety rc=22" 22
  expect_no_adb_call "$server_var routing override performs no fake or poison adb call"
done

# Replace the validated source inode after the initial STOP manifest but before
# snapshot creation. The snapshotter must compare its already-recorded source
# identity with the opened descriptor and stop without executing either file.
PRESNAPSHOT_ADB="$WORK/presnapshot-adb"
cp "$FAKE_ADB" "$PRESNAPSHOT_ADB"
chmod +x "$PRESNAPSHOT_ADB"
export SELFTEST_PRESNAPSHOT_ADB_SOURCE="$PRESNAPSHOT_ADB"
export SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT="$POISON_ADB"
export SELFTEST_PRESNAPSHOT_ADB_MARKER="$WORK/presnapshot-adb-replaced"
PRESNAPSHOT_OUT="$WORK/out-presnapshot-adb-replace"
run_collect adb-source-presnapshot-replace \
  "$AUTHORIZED_SERIAL" "$PRESNAPSHOT_OUT" "$PRESNAPSHOT_ADB"
unset \
  SELFTEST_PRESNAPSHOT_ADB_SOURCE \
  SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT \
  SELFTEST_PRESNAPSHOT_ADB_MARKER
expect_stop "adb source replacement before snapshot is refused" STOP_INTERNAL_ADB_SNAPSHOT
expect_exit_code "adb source replacement before snapshot uses internal rc=70" 70
if [ -e "$WORK/presnapshot-adb-replaced" ]; then
  report ok "adb source replacement before snapshot fixture reached the vulnerable window"
else
  report fail "adb source replacement before snapshot fixture reached the vulnerable window"
fi
expect_no_adb_call "adb source replacement before snapshot executes neither source nor replacement"

# Replacing the caller-selected adb pathname after the first command must not
# affect later commands: collection executes one private, hashed byte snapshot.
SWAPPABLE_ADB="$WORK/swappable-adb"
cp "$FAKE_ADB" "$SWAPPABLE_ADB"
chmod +x "$SWAPPABLE_ADB"
export FAKE_ADB_REPLACE_SOURCE="$SWAPPABLE_ADB"
export FAKE_ADB_REPLACEMENT="$POISON_ADB"
export FAKE_ADB_REPLACE_MARKER="$WORK/swappable-adb-replaced"
SWAPPABLE_OUT="$WORK/out-swappable-adb"
run_collect target "$AUTHORIZED_SERIAL" "$SWAPPABLE_OUT" "$SWAPPABLE_ADB"
unset FAKE_ADB_REPLACE_SOURCE FAKE_ADB_REPLACEMENT FAKE_ADB_REPLACE_MARKER
if [ "$RC" -eq 0 ]; then
  report ok "adb source replacement cannot change the executed snapshot"
else
  report fail "adb source replacement cannot change the executed snapshot" "rc=$RC output=$OUT"
fi
expect_poison_unused "adb source replacement never executes the replacement"

export FAKE_ADB_REPLACEMENT="$POISON_ADB"
export FAKE_ADB_SNAPSHOT_REPLACE_MARKER="$WORK/adb-snapshot-self-replaced"
SNAPSHOT_REPLACE_OUT="$WORK/out-adb-snapshot-self-replace"
run_collect snapshot-self-replace "$AUTHORIZED_SERIAL" "$SNAPSHOT_REPLACE_OUT"
unset FAKE_ADB_REPLACEMENT FAKE_ADB_SNAPSHOT_REPLACE_MARKER
expect_stop "adb snapshot replacement by the running client is detected" STOP_ADB_SNAPSHOT_CHANGED
expect_exit_code "adb snapshot replacement uses local-safety rc=22" 22
if [ "$(wc -l <"$ADB_LOG" | tr -d ' ')" = 1 ]; then
  report ok "adb snapshot replacement stops before a second command"
else
  report fail "adb snapshot replacement stops before a second command" \
    "adb log=$(tr '\n' ';' <"$ADB_LOG")"
fi
expect_poison_unused "replaced adb snapshot is never executed"

# The collector pins the newly-created evidence inode as its cwd. Replacing the
# caller-visible pathname must neither redirect receipts into the attacker tree
# nor permit a successful final publication.
SWAP_OUT="$WORK/out-path-swap"
SWAP_TARGET="$WORK/path-swap-attacker-target"
mkdir -m 700 "$SWAP_TARGET"
export FAKE_ADB_SWAP_OUTPUT="$SWAP_OUT"
export FAKE_ADB_SWAP_TARGET="$SWAP_TARGET"
export FAKE_ADB_SWAP_MARKER="$WORK/path-swap-marker"
run_collect target "$AUTHORIZED_SERIAL" "$SWAP_OUT"
unset FAKE_ADB_SWAP_OUTPUT FAKE_ADB_SWAP_TARGET FAKE_ADB_SWAP_MARKER
expect_stop "evidence output pathname replacement is refused" STOP_OUTPUT_CHANGED
expect_exit_code "evidence output pathname replacement uses local-safety rc=22" 22
if [ -z "$(find "$SWAP_TARGET" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  report ok "evidence output pathname replacement cannot redirect receipts"
else
  report fail "evidence output pathname replacement cannot redirect receipts" \
    "attacker target received files"
fi
if [ -f "$SWAP_OUT.detached/manifest.json" ]; then
  assert_stop_manifest "$SWAP_OUT.detached/manifest.json" STOP_OUTPUT_CHANGED \
    "detached pinned evidence retains a STOP_OUTPUT_CHANGED manifest"
else
  report fail "detached pinned evidence retains a STOP_OUTPUT_CHANGED manifest" \
    "missing detached manifest"
fi

# The public collector requires an unprivileged adb shell principal. A root
# adbd changes observation semantics and must stop at the first serial-targeted
# identity receipt, before any boot/build/user/process/package/framework read.
ROOT_ADBD_OUT="$WORK/out-root-adbd"
run_collect root-adbd "$AUTHORIZED_SERIAL" "$ROOT_ADBD_OUT"
expect_stop "uid=0 adb shell is refused" STOP_UNPRIVILEGED_SHELL_REQUIRED
expect_exit_code "uid=0 adb shell uses topology/identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell id" \
  "uid=0 stops all adb reads immediately after shell-id"
if [ "$(wc -l <"$ADB_LOG" | tr -d ' ')" = 2 ] \
    && [ "$(sed -n '1p' "$ADB_LOG")" = "devices -l" ] \
    && [ "$(sed -n '2p' "$ADB_LOG")" = "-s $AUTHORIZED_SERIAL shell id" ]; then
  report ok "uid=0 executes no serial-targeted observation before shell identity"
else
  report fail "uid=0 executes no serial-targeted observation before shell identity" \
    "adb log=$(tr '\n' ';' <"$ADB_LOG")"
fi
expect_only_authorized_target "uid=0 refusal stays scoped"
if [ -f "$ROOT_ADBD_OUT/manifest.json" ]; then
  assert_stop_manifest "$ROOT_ADBD_OUT/manifest.json" STOP_UNPRIVILEGED_SHELL_REQUIRED \
    "uid=0 manifest preserves the exact reason"
  assert_six_file_receipts "$ROOT_ADBD_OUT/manifest.json" "$ROOT_ADBD_OUT/receipts" \
    "uid=0 refusal preserves strict six-file receipts"
else
  report fail "uid=0 refusal manifest exists" "missing manifest.json"
fi

SHELL_ID_MULTILINE_OUT="$WORK/out-shell-id-multiline"
run_collect shell-id-multiline "$AUTHORIZED_SERIAL" "$SHELL_ID_MULTILINE_OUT"
expect_stop "multi-line shell identity is refused" STOP_UNPRIVILEGED_SHELL_REQUIRED
expect_exit_code "multi-line shell identity uses identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell id" \
  "multi-line shell identity stops at its receipt"
expect_only_authorized_target "multi-line shell identity stays scoped"

for selinux_case in \
    "selinux-malformed|unknown SELinux state" \
    "selinux-extra-line|multi-line SELinux state" \
    "selinux-embedded-cr|embedded-CR SELinux state" \
    "selinux-lone-cr|lone-CR SELinux terminator"; do
  IFS='|' read -r selinux_scenario selinux_label <<<"$selinux_case"
  selinux_out="$WORK/out-$selinux_scenario"
  run_collect "$selinux_scenario" "$AUTHORIZED_SERIAL" "$selinux_out"
  expect_stop "$selinux_label is refused" STOP_INCOMPLETE_CORE_RECEIPT
  expect_exit_code "$selinux_label uses evidence rc=21" 21
  assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getenforce" \
    "$selinux_label stops at its receipt"
  expect_only_authorized_target "$selinux_label stays scoped"
  if [ -f "$selinux_out/manifest.json" ]; then
    assert_stop_manifest "$selinux_out/manifest.json" \
      STOP_INCOMPLETE_CORE_RECEIPT \
      "$selinux_label manifest preserves the exact reason"
  else
    report fail "$selinux_label manifest exists" "missing manifest.json"
  fi
done

# User-sensitive reads are frozen to Android user 0. A different current user
# stops at that receipt; it may not continue into process/location/package or
# framework observations for the wrong user context.
WRONG_USER_OUT="$WORK/out-current-user-nonzero"
run_collect current-user-nonzero "$AUTHORIZED_SERIAL" "$WRONG_USER_OUT"
expect_stop "nonzero Android current user is refused" STOP_UNSUPPORTED_USER_0_REQUIRED
expect_exit_code "nonzero Android current user uses topology/identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell am get-current-user" \
  "nonzero current user stops subsequent user-sensitive reads"
expect_only_authorized_target "nonzero current-user refusal stays scoped"
if [ -f "$WRONG_USER_OUT/manifest.json" ]; then
  assert_stop_manifest "$WRONG_USER_OUT/manifest.json" STOP_UNSUPPORTED_USER_0_REQUIRED \
    "nonzero current-user manifest preserves the exact reason"
  assert_six_file_receipts "$WRONG_USER_OUT/manifest.json" "$WRONG_USER_OUT/receipts" \
    "nonzero current-user refusal preserves strict six-file receipts"
else
  report fail "nonzero current-user manifest exists" "missing manifest.json"
fi

# The process surface is column-bounded, but Android toybox is free to pad
# those columns. Header tokens must be exact and every process row must still
# contain a decimal PID plus a nonempty user/name tuple.
for process_case in \
  "process-header-malformed|malformed process header" \
  "process-row-malformed|malformed process row"; do
  IFS='|' read -r process_scenario process_label <<<"$process_case"
  process_out="$WORK/out-$process_scenario"
  run_collect "$process_scenario" "$AUTHORIZED_SERIAL" "$process_out"
  expect_stop "$process_label is refused" STOP_PACKAGE_OBSERVATION_MALFORMED
  expect_exit_code "$process_label uses evidence rc=21" 21
  assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell ps -A -o USER,PID,NAME" \
    "$process_label stops at the process receipt"
done

PROCESS_CRLF_OUT="$WORK/out-process-crlf"
run_collect process-crlf "$AUTHORIZED_SERIAL" "$PROCESS_CRLF_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "CRLF-framed process output remains collectable"
else
  report fail "CRLF-framed process output remains collectable" \
    "rc=$RC output=$OUT"
fi
run_verify "$PROCESS_CRLF_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "CRLF-framed process output remains receipt-verifiable"
else
  report fail "CRLF-framed process output remains receipt-verifiable" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "CRLF process receipt verification performs no adb call"

# The production verifier is a pure host mode. First prove that the intact
# G-00 receipt set is accepted; then corrupt one invariant at a time. A
# corrupted evidence set is an evidence failure (rc=21), never an adb action.
run_verify "$G00_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "host verifier accepts intact G-00 receipts"
else
  report fail "host verifier accepts intact G-00 receipts" "rc=$RC output=$OUT"
fi
expect_no_adb_call "intact host verification performs no adb call"

SHELL_CONTEXT_OUT="$WORK/verify-shell-id-canonical-context"
cp -R "$G00_OUT" "$SHELL_CONTEXT_OUT"
printf '%s\n' \
  'uid=2000(shell) gid=2000(shell) groups=1003(graphics),2000(shell),3003(inet) context=u:r:shell:s0' \
  >"$SHELL_CONTEXT_OUT/receipts/shell-id.stdout.txt"
rebind_receipt_tree "$SHELL_CONTEXT_OUT"
run_verify "$SHELL_CONTEXT_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "host verifier accepts canonical Android shell groups/context identity"
else
  report fail "host verifier accepts canonical Android shell groups/context identity" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "canonical shell groups/context verification performs no adb call"

shell_identity_cases=(
  'mixed-primary-root|uid=2000(shell) gid=0(root) groups=2000(shell) context=u:r:shell:s0|mixed shell uid/root primary gid'
  'supplemental-root|uid=2000(shell) gid=2000(shell) groups=0(root),2000(shell) context=u:r:shell:s0|root supplemental group'
  'wrong-selinux-domain|uid=2000(shell) gid=2000(shell) groups=2000(shell) context=u:r:su:s0|non-shell SELinux domain'
  'arbitrary-suffix|uid=2000(shell) gid=2000(shell) groups=2000(shell) root=true|arbitrary shell-id suffix'
)
for shell_identity_case in "${shell_identity_cases[@]}"; do
  IFS='|' read -r shell_identity_name shell_identity_value shell_identity_label \
    <<<"$shell_identity_case"
  broken="$WORK/verify-shell-id-$shell_identity_name"
  cp -R "$G00_OUT" "$broken"
  printf '%s\n' "$shell_identity_value" >"$broken/receipts/shell-id.stdout.txt"
  rebind_receipt_tree "$broken"
  run_verify "$broken"
  expect_stop "host verifier rejects $shell_identity_label" STOP_INCOMPLETE_RECEIPT
  expect_exit_code "$shell_identity_label uses evidence rc=21" 21
  expect_no_adb_call "$shell_identity_label verification performs no adb call"
done

PYTHON_SHADOW_MARKER="$WORK/python-shadow-executed.marker"
PYTHON_SHADOW_ROOT="$WORK/verify-python-module-shadow"
cp -R "$G00_OUT" "$PYTHON_SHADOW_ROOT"
printf 'open("%s", "w").write("executed")\nraise SystemExit(0)\n' \
  "$PYTHON_SHADOW_MARKER" >"$PYTHON_SHADOW_ROOT/datetime.py"
run_verify "$PYTHON_SHADOW_ROOT"
expect_stop "host verifier rejects an evidence-local Python module shadow" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "evidence-local Python module shadow uses evidence rc=21" 21
if [ -e "$PYTHON_SHADOW_MARKER" ]; then
  report fail "evidence-local Python module is never imported" \
    "shadow module executed"
else
  report ok "evidence-local Python module is never imported"
fi

PYTHONPATH_SHADOW="$WORK/pythonpath-shadow"
mkdir -m 700 "$PYTHONPATH_SHADOW"
printf 'open("%s", "w").write("executed")\nraise SystemExit(0)\n' \
  "$PYTHON_SHADOW_MARKER" >"$PYTHONPATH_SHADOW/datetime.py"
export PYTHONPATH="$PYTHONPATH_SHADOW"
export PYTHONHOME="$PYTHONPATH_SHADOW/false-home"
run_verify "$G00_OUT"
unset PYTHONPATH PYTHONHOME
if [ "$RC" -eq 0 ]; then
  report ok "isolated verifier ignores ambient Python module paths"
else
  report fail "isolated verifier ignores ambient Python module paths" \
    "rc=$RC output=$OUT"
fi
if [ -e "$PYTHON_SHADOW_MARKER" ]; then
  report fail "ambient Python shadow module is never imported" \
    "shadow module executed"
else
  report ok "ambient Python shadow module is never imported"
fi

run_verify_production "$G00_OUT"
expect_stop "production verifier rejects a SELFTEST evidence bundle" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "production verifier SELFTEST rejection uses evidence rc=21" 21
expect_no_adb_call "production verifier SELFTEST rejection performs no adb call"

RELABELLED_SELFTEST_OUT="$WORK/verify-selftest-relabeled-production"
cp -R "$G00_OUT" "$RELABELLED_SELFTEST_OUT"
"$PYTHON_BIN" -I - "$RELABELLED_SELFTEST_OUT/manifest.json" \
  "$RELABELLED_SELFTEST_OUT/summary.json" <<'PY'
import json
import pathlib
import sys

for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["adbClientTrust"] = "REPO_PINNED_SHA256_PRODUCTION"
    document["adbApprovalLane"] = "PRODUCTION"
    document["adbApprovalLabel"] = "platform-tools-37.0.0-macos-google-eqhxz8m8av"
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
run_verify_production "$RELABELLED_SELFTEST_OUT"
expect_stop "fake digest cannot be relabeled as production ADB evidence" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "relabeled fake digest rejection uses evidence rc=21" 21
expect_no_adb_call "relabeled fake digest verification performs no adb call"

# Build a verifier-only positive control with the two tool digests populated.
# The tamper rows below are discriminating only after this intact binding is
# accepted; otherwise an old summary whitelist could make every mutation look
# safely rejected for the wrong reason.
TOOL_DIGEST_BASELINE="$WORK/verify-tool-digest-baseline"
if ! cp -R "$G00_OUT" "$TOOL_DIGEST_BASELINE"; then
  printf 'selftest could not copy tool-digest verifier baseline\n' >&2
  exit 2
fi
if ! "$PYTHON_BIN" -I - "$TOOL_DIGEST_BASELINE/manifest.json" \
    "$TOOL_DIGEST_BASELINE/summary.json" "$FAKE_ADB" "$COLLECTOR" <<'PY'
import hashlib
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])
digests = {
    "adbSha256": hashlib.sha256(pathlib.Path(sys.argv[3]).read_bytes()).hexdigest(),
    "collectorSha256": hashlib.sha256(pathlib.Path(sys.argv[4]).read_bytes()).hexdigest(),
}
for path in (manifest_path, summary_path):
    document = json.loads(path.read_text(encoding="utf-8"))
    document.update(digests)
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
then
  printf 'selftest could not prepare tool-digest verifier baseline\n' >&2
  exit 2
fi
run_verify "$TOOL_DIGEST_BASELINE"
TOOL_DIGEST_BASELINE_RC=$RC
TOOL_DIGEST_BASELINE_OUT=$OUT
if [ "$TOOL_DIGEST_BASELINE_RC" -eq 0 ]; then
  report ok "host verifier accepts intact adb/collector SHA-256 bindings"
else
  report fail "host verifier accepts intact adb/collector SHA-256 bindings" \
    "rc=$TOOL_DIGEST_BASELINE_RC output=$TOOL_DIGEST_BASELINE_OUT"
fi
expect_no_adb_call "intact tool-digest host verification performs no adb call"

for digest_field in adbSha256 collectorSha256; do
  broken="$WORK/verify-wrong-$digest_field"
  if ! cp -R "$TOOL_DIGEST_BASELINE" "$broken"; then
    printf 'selftest could not copy %s mutation fixture\n' "$digest_field" >&2
    exit 2
  fi
  if ! "$PYTHON_BIN" -I - "$broken/manifest.json" "$broken/summary.json" "$digest_field" <<'PY'
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])
field = sys.argv[3]
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
wrong = "0" * 64 if manifest.get(field) != "0" * 64 else "1" * 64
for path in (manifest_path, summary_path):
    document = json.loads(path.read_text(encoding="utf-8"))
    document[field] = wrong
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
  then
    printf 'selftest could not mutate %s fixture\n' "$digest_field" >&2
    exit 2
  fi
  run_verify "$broken"
  label="host verifier rejects wrong but well-shaped $digest_field"
  if [ "$TOOL_DIGEST_BASELINE_RC" -eq 0 ]; then
    expect_stop "$label" STOP_INCOMPLETE_RECEIPT
  else
    report fail "$label" \
      "intact tool-digest baseline was refused, so mutation refusal is non-discriminating"
  fi
  expect_exit_code "$label uses evidence rc=21" 21
  expect_no_adb_call "$label performs no adb call"
done

WRONG_SOURCE_HEAD="$WORK/verify-wrong-source-head"
if ! cp -R "$G00_OUT" "$WRONG_SOURCE_HEAD"; then
  printf 'selftest could not copy source-HEAD mutation fixture\n' >&2
  exit 2
fi
"$PYTHON_BIN" -I - "$WRONG_SOURCE_HEAD/manifest.json" \
  "$WRONG_SOURCE_HEAD/summary.json" <<'PY'
import json
import pathlib
import sys

for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["sourceHead"] = "0" * 40
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
run_verify "$WRONG_SOURCE_HEAD"
expect_stop "host verifier rejects a self-consistent but unreviewed sourceHead" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "unreviewed sourceHead uses evidence rc=21" 21
expect_no_adb_call "unreviewed sourceHead verification performs no adb call"

FIRST_STEM="$(sed -n '1p' "$G00_OUT/receipts/stems.txt")"
if [[ $FIRST_STEM =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  report ok "receipt mutation matrix has a safe baseline stem"
else
  report fail "receipt mutation matrix has a safe baseline stem" "stem=$FIRST_STEM"
fi

verify_mutation_stop() { # case-name evidence-root
  run_verify "$2"
  expect_stop "$1" STOP_INCOMPLETE_RECEIPT
  expect_exit_code "$1 uses evidence rc=21" 21
  expect_no_adb_call "$1 performs no adb call"
}

# Round 6: receipt completeness includes the manifest's identity, claim ceiling,
# redaction boundary, and exact fixed-package truth. Mutate one field at a time
# in an otherwise intact G-00 evidence tree; every case is a pure host check.
for manifest_field in \
    schemaVersion \
    mode \
    adbClientTrust \
    adbApprovalLane \
    adbApprovalLabel \
    adbAllowlistSha256 \
    sourceHead \
    authorizedSerial \
    targetSerial \
    status \
    collectionStatus \
    compatibility \
    privilegedInspection \
    coordinateCaptured \
    knownPackages; do
  broken="$WORK/verify-manifest-$manifest_field"
  cp -R "$G00_OUT" "$broken"
  "$PYTHON_BIN" -I - "$broken/manifest.json" "$manifest_field" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
field = sys.argv[2]
manifest = json.loads(path.read_text(encoding="utf-8"))
mutations = {
    "schemaVersion": 99,
    "mode": "MUTATING_DEVICE_RUN",
    "adbClientTrust": "REPO_PINNED_SHA256_PRODUCTION",
    "adbApprovalLane": "PRODUCTION",
    "adbApprovalLabel": "unapproved-label",
    "adbAllowlistSha256": "0" * 64,
    "sourceHead": "0" * 40,
    "authorizedSerial": "OTHER_SERIAL",
    "targetSerial": "OTHER_SERIAL",
    "status": "FULL",
    "collectionStatus": "FULL",
    "compatibility": "ATTESTED",
    "privilegedInspection": "COLLECTED_PRIVILEGED",
    "coordinateCaptured": True,
    "knownPackages": {
        **manifest.get("knownPackages", {}),
        "name.caiyao.fakegps": "FULL",
    },
}
manifest[field] = mutations[field]
path.write_text(json.dumps(manifest, separators=(",", ":")) + "\n", encoding="utf-8")
PY
  verify_mutation_stop "host verifier rejects tampered manifest $manifest_field" "$broken"
done

# `exec-out cat` bytes must remain binary evidence. Merely renaming the exact
# services.jar payload to the text carrier preserves the six-file count but
# destroys the typed receipt contract and must be rejected.
broken="$WORK/verify-services-binary-renamed-text"
cp -R "$G00_OUT" "$broken"
mv "$broken/receipts/services-jar.stdout.bin" \
  "$broken/receipts/services-jar.stdout.txt"
verify_mutation_stop "host verifier rejects services.jar binary carrier renamed as text" "$broken"

# Delete each member of the six-file carrier independently. For stdout the
# baseline may choose text or binary, but exactly one must have existed.
for suffix in command.txt start-utc.txt stdout stderr.bin exit.txt end-utc.txt; do
  broken="$WORK/verify-missing-${suffix//./-}"
  cp -R "$G00_OUT" "$broken"
  if [ "$suffix" = stdout ]; then
    if [ -f "$broken/receipts/$FIRST_STEM.stdout.txt" ]; then
      rm -f "$broken/receipts/$FIRST_STEM.stdout.txt"
    else
      rm -f "$broken/receipts/$FIRST_STEM.stdout.bin"
    fi
  else
    rm -f "$broken/receipts/$FIRST_STEM.$suffix"
  fi
  verify_mutation_stop "host verifier rejects missing $suffix" "$broken"
done

broken="$WORK/verify-dual-stdout"
cp -R "$G00_OUT" "$broken"
if [ -f "$broken/receipts/$FIRST_STEM.stdout.txt" ]; then
  cp "$broken/receipts/$FIRST_STEM.stdout.txt" "$broken/receipts/$FIRST_STEM.stdout.bin"
else
  cp "$broken/receipts/$FIRST_STEM.stdout.bin" "$broken/receipts/$FIRST_STEM.stdout.txt"
fi
verify_mutation_stop "host verifier rejects simultaneous stdout txt/bin" "$broken"

broken="$WORK/verify-bad-exit"
cp -R "$G00_OUT" "$broken"
printf 'seven\n' >"$broken/receipts/$FIRST_STEM.exit.txt"
verify_mutation_stop "host verifier rejects non-decimal exit" "$broken"

broken="$WORK/verify-bad-time"
cp -R "$G00_OUT" "$broken"
printf 'not-rfc3339\n' >"$broken/receipts/$FIRST_STEM.start-utc.txt"
verify_mutation_stop "host verifier rejects non-RFC3339 time" "$broken"

broken="$WORK/verify-undeclared-file"
cp -R "$G00_OUT" "$broken"
printf 'undeclared\n' >"$broken/receipts/undeclared.local.txt"
verify_mutation_stop "host verifier rejects undeclared receipt file" "$broken"

broken="$WORK/verify-stem-mismatch"
cp -R "$G00_OUT" "$broken"
printf 'ghost-stem\n' >>"$broken/receipts/stems.txt"
verify_mutation_stop "host verifier rejects manifest/stems mismatch" "$broken"

# Manifest is itself a claim boundary. An otherwise valid evidence tree may
# not acquire an undeclared affirmative field that the verifier silently
# ignores, even when the signed/frozen fields remain unchanged.
broken="$WORK/verify-extra-affirmative-manifest-key"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken/manifest.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
manifest = json.loads(path.read_text(encoding="utf-8"))
manifest["attested"] = True
path.write_text(json.dumps(manifest, separators=(",", ":")) + "\n", encoding="utf-8")
PY
verify_mutation_stop "host verifier rejects an extra affirmative manifest key" "$broken"

# Duplicate JSON keys create parser-dependent claims. Python's default parser
# is last-wins, so an unsafe first value followed by the expected safe value
# must be rejected explicitly in both machine-readable documents.
for duplicate_target in manifest summary; do
  broken="$WORK/verify-duplicate-$duplicate_target-key"
  cp -R "$G00_OUT" "$broken"
  "$PYTHON_BIN" -I - "$broken/$duplicate_target.json" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
needle = '"devicePass":false'
assert text.count(needle) == 1, text
path.write_text(
    text.replace(needle, '"devicePass":true,"devicePass":false', 1),
    encoding="utf-8",
)
PY
  verify_mutation_stop "host verifier rejects duplicate $duplicate_target claim key" "$broken"
done

# Every receipt stem owns one exact adb argv, not merely a common adb binary.
# Keep the executable path and hash untouched while changing the serial or
# replacing a read with a mutation; both must be rejected without execution.
for argv_mutation in other-serial mutating-command; do
  broken="$WORK/verify-command-$argv_mutation"
  cp -R "$G00_OUT" "$broken"
  "$PYTHON_BIN" -I - "$broken/receipts/serial.command.txt" "$argv_mutation" <<'PY'
import pathlib
import shlex
import sys

path = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
original = shlex.split(path.read_text(encoding="utf-8"))
if not original:
    raise SystemExit("empty baseline command")
adb = original[0]
argv = {
    "other-serial": ["-s", "OTHER_SERIAL", "shell", "getprop", "ro.serialno"],
    "mutating-command": [
        "-s", "ZY22JHW9M4", "shell", "settings", "put", "secure", "location_mode", "3"
    ],
}[mode]
path.write_text(shlex.join([adb, *argv]) + "\n", encoding="utf-8")
PY
  rebind_receipt_tree "$broken"
  verify_mutation_stop "host verifier rejects $argv_mutation receipt argv" "$broken"
done

# Shape-only verification is insufficient: removing a required identity stem
# can be hidden by synchronizing all three indexes, and successful-looking
# scalar/exit carriers can still contradict the frozen observation semantics.
broken="$WORK/verify-required-serial-stem-removed"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
stem = "serial"
manifest_path = root / "manifest.json"
summary_path = root / "summary.json"
stems_path = root / "receipts" / "stems.txt"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest["receiptStems"] = [value for value in manifest["receiptStems"] if value != stem]
manifest_path.write_text(json.dumps(manifest, separators=(",", ":")) + "\n", encoding="utf-8")
summary = json.loads(summary_path.read_text(encoding="utf-8"))
summary["receiptCount"] = len(manifest["receiptStems"])
summary_path.write_text(json.dumps(summary, separators=(",", ":")) + "\n", encoding="utf-8")
stems = [value for value in stems_path.read_text(encoding="utf-8").splitlines() if value != stem]
stems_path.write_text("\n".join(stems) + "\n", encoding="utf-8")
for suffix in ("command.txt", "start-utc.txt", "stdout.txt", "stderr.bin", "exit.txt", "end-utc.txt"):
    (root / "receipts" / f"{stem}.{suffix}").unlink()
PY
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a required identity stem removed from every index" "$broken"

broken="$WORK/verify-identity-stdout-tampered"
cp -R "$G00_OUT" "$broken"
printf 'OTHER_SERIAL\n' >"$broken/receipts/serial.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects serial stdout that contradicts target identity" "$broken"

broken="$WORK/verify-identity-stdout-multiline"
cp -R "$G00_OUT" "$broken"
printf 'ZY22\nJHW9M4\n' >"$broken/receipts/serial.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a multiline scalar that would concatenate to the authorized serial" "$broken"

for scalar_encoding in c1 line-separator; do
  broken="$WORK/verify-fingerprint-$scalar_encoding"
  cp -R "$G00_OUT" "$broken"
  if [ "$scalar_encoding" = c1 ]; then
    printf '\302\205\n' >"$broken/receipts/fingerprint.stdout.txt"
  else
    printf '\342\200\250\n' >"$broken/receipts/fingerprint.stdout.txt"
  fi
  rebind_receipt_tree "$broken"
  verify_mutation_stop \
    "host verifier rejects $scalar_encoding as a scalar line boundary" "$broken"
done

broken="$WORK/verify-fingerprint-splice"
cp -R "$G00_OUT" "$broken"
printf 'motorola/other/other:15/OTHER/2:user/release-keys\n' \
  >"$broken/receipts/fingerprint.stdout.txt"
verify_mutation_stop "host verifier rejects fingerprint bytes spliced from another build" "$broken"

broken="$WORK/verify-boot-pair-splice"
cp -R "$G00_OUT" "$broken"
printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n' \
  >"$broken/receipts/boot-id-start.stdout.txt"
printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n' \
  >"$broken/receipts/boot-id-end.stdout.txt"
verify_mutation_stop "host verifier rejects a self-consistent boot pair spliced after collection" "$broken"

broken="$WORK/verify-process-header-only"
cp -R "$G00_OUT" "$broken"
printf '%-12s %6s %-27s\n' USER PID NAME >"$broken/receipts/process-list.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a process receipt truncated to its header" "$broken"

broken="$WORK/verify-process-crlf"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken/receipts/process-list.stdout.txt" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
raw = path.read_bytes()
assert raw.endswith(b"\n") and b"\r" not in raw, raw
path.write_bytes(raw.replace(b"\n", b"\r\n"))
PY
rebind_receipt_tree "$broken"
run_verify "$broken"
if [ "$RC" -eq 0 ]; then
  report ok "host verifier accepts collector-valid CRLF process framing"
else
  report fail "host verifier accepts collector-valid CRLF process framing" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "CRLF process framing verification performs no adb call"

broken="$WORK/verify-process-missing-terminal-lf"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken/receipts/process-list.stdout.txt" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
raw = path.read_bytes()
assert raw.endswith(b"\n"), raw
path.write_bytes(raw[:-1])
PY
rebind_receipt_tree "$broken"
verify_mutation_stop \
  "host verifier rejects process output without its terminal newline" "$broken"

broken="$WORK/verify-dumpsys-anchor-only"
cp -R "$G00_OUT" "$broken"
printf 'Package [name.caiyao.fakegps] userId=10208\n' \
  >"$broken/receipts/package-name-caiyao-fakegps-dumpsys.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a package dump truncated to its anchor" "$broken"

for appops_shape in \
    wrong-operation \
    duplicate-package-row \
    inline-package-row \
    error-tail \
    default-tail \
    bogus-time \
    tab-separator \
    public-op-name \
    op-name-wrong-case \
    mode-wrong-case \
    unknown-mode \
    leading-space \
    trailing-space \
    multiple-space \
    time-without-ago \
    reject-without-ago \
    duration-with-ago \
    duration-negative \
    orphan-duration \
    orphan-running \
    duration-running \
    duration-running-reverse \
    wrong-order \
    metadata-tab \
    duration-missing-ms \
    time-missing-ms \
    reject-missing-ms \
    duration-gap \
    duration-hour-gap \
    duration-minute-gap \
    duration-day-missing-hour \
    duration-day-missing-minute \
    duration-day-missing-second \
    duration-hour-missing-second \
    duration-range \
    duration-minute-range \
    duration-second-range \
    duration-residual-hour-range \
    duration-residual-minute-range \
    duration-residual-second-range \
    duration-ms-range \
    duration-top-ms-range \
    duration-day-overflow \
    duration-day-residual-overflow \
    duration-leading-zero \
    duration-day-leading-zero \
    duration-hour-leading-zero \
    duration-minute-leading-zero \
    duration-second-leading-zero \
    duration-residual-hour-leading-zero \
    duration-residual-minute-leading-zero \
    duration-residual-second-leading-zero \
    duration-top-ms-leading-zero \
    duration-signed-zero \
    duration-negative-signed-zero \
    time-unsigned \
    elapsed-unsigned \
    elapsed-signed-zero \
    duration-unicode-digit \
    unicode-op-name \
    reject-before-time \
    duplicate-time \
    duplicate-reject \
    duplicate-duration \
    duplicate-running \
    no-operations-wrong-default \
    no-operations-missing-default \
    no-operations-extra-line \
    no-operations-wrong-case \
    no-operations-spacing \
    uid-default-deny \
    uid-after-package \
    duplicate-uid \
    uid-metadata \
    uid-wrong-operation \
    lone-cr \
    missing-newline; do
  broken="$WORK/verify-appops-$appops_shape"
  cp -R "$G00_OUT" "$broken"
  case "$appops_shape" in
    wrong-operation) appops_value='FINE_LOCATION: allow' ;;
    duplicate-package-row) appops_value=$'MOCK_LOCATION: allow\nMOCK_LOCATION: deny' ;;
    inline-package-row) appops_value='MOCK_LOCATION: allow; MOCK_LOCATION: deny' ;;
    error-tail) appops_value='MOCK_LOCATION: allow; Error: transport failed' ;;
    default-tail) appops_value='MOCK_LOCATION: allow; Default mode: deny' ;;
    bogus-time) appops_value='MOCK_LOCATION: allow; time=error transport failed' ;;
    tab-separator) appops_value=$'MOCK_LOCATION:\tallow' ;;
    public-op-name) appops_value='android:mock_location: allow' ;;
    op-name-wrong-case) appops_value='mock_location: allow' ;;
    mode-wrong-case) appops_value='MOCK_LOCATION: ALLOW' ;;
    unknown-mode) appops_value='MOCK_LOCATION: mode=5' ;;
    leading-space) appops_value=' MOCK_LOCATION: allow' ;;
    trailing-space) appops_value='MOCK_LOCATION: allow ' ;;
    multiple-space) appops_value='MOCK_LOCATION:  allow' ;;
    time-without-ago) appops_value='MOCK_LOCATION: allow; time=+1s0ms' ;;
    reject-without-ago) appops_value='MOCK_LOCATION: allow; rejectTime=+1s0ms' ;;
    duration-with-ago) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+5s0ms ago' ;;
    duration-negative) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=-1ms' ;;
    orphan-duration) appops_value='MOCK_LOCATION: allow; duration=+1ms' ;;
    orphan-running) appops_value='MOCK_LOCATION: allow (running)' ;;
    duration-running) appops_value='MOCK_LOCATION: allow; time=+1ms ago (running); duration=+5s0ms' ;;
    duration-running-reverse) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+5s0ms (running)' ;;
    wrong-order) appops_value='MOCK_LOCATION: allow; time=+1s0ms ago; duration=+5s0ms; rejectTime=+2s0ms ago' ;;
    metadata-tab) appops_value=$'MOCK_LOCATION: allow;\ttime=+1s0ms ago' ;;
    duration-missing-ms) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+1s' ;;
    time-missing-ms) appops_value='MOCK_LOCATION: allow; time=+1s ago' ;;
    reject-missing-ms) appops_value='MOCK_LOCATION: allow; rejectTime=+1s ago' ;;
    duration-gap) appops_value='MOCK_LOCATION: allow; time=+1d0ms ago' ;;
    duration-hour-gap) appops_value='MOCK_LOCATION: allow; time=+1h0s0ms ago' ;;
    duration-minute-gap) appops_value='MOCK_LOCATION: allow; time=+1m0ms ago' ;;
    duration-day-missing-hour) appops_value='MOCK_LOCATION: allow; time=+1d0m0s0ms ago' ;;
    duration-day-missing-minute) appops_value='MOCK_LOCATION: allow; time=+1d0h0s0ms ago' ;;
    duration-day-missing-second) appops_value='MOCK_LOCATION: allow; time=+1d0h0m0ms ago' ;;
    duration-hour-missing-second) appops_value='MOCK_LOCATION: allow; time=+1h0m0ms ago' ;;
    duration-range) appops_value='MOCK_LOCATION: allow; time=+24h0m0s0ms ago' ;;
    duration-minute-range) appops_value='MOCK_LOCATION: allow; time=+60m0s0ms ago' ;;
    duration-second-range) appops_value='MOCK_LOCATION: allow; time=+60s0ms ago' ;;
    duration-residual-hour-range) appops_value='MOCK_LOCATION: allow; time=+1d24h0m0s0ms ago' ;;
    duration-residual-minute-range) appops_value='MOCK_LOCATION: allow; time=+1h60m0s0ms ago' ;;
    duration-residual-second-range) appops_value='MOCK_LOCATION: allow; time=+1m60s0ms ago' ;;
    duration-ms-range) appops_value='MOCK_LOCATION: allow; time=+1s1000ms ago' ;;
    duration-top-ms-range) appops_value='MOCK_LOCATION: allow; time=+1000ms ago' ;;
    duration-day-overflow) appops_value='MOCK_LOCATION: allow; time=+24856d0h0m0s0ms ago' ;;
    duration-day-residual-overflow) appops_value='MOCK_LOCATION: allow; time=+24855d3h14m8s0ms ago' ;;
    duration-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1s000ms ago' ;;
    duration-day-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01d0h0m0s0ms ago' ;;
    duration-hour-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01h0m0s0ms ago' ;;
    duration-minute-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01m0s0ms ago' ;;
    duration-second-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01s0ms ago' ;;
    duration-residual-hour-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1d00h0m0s0ms ago' ;;
    duration-residual-minute-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1h00m0s0ms ago' ;;
    duration-residual-second-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1m00s0ms ago' ;;
    duration-top-ms-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01ms ago' ;;
    duration-signed-zero) appops_value='MOCK_LOCATION: allow; time=+0ms ago' ;;
    duration-negative-signed-zero) appops_value='MOCK_LOCATION: allow; time=-0ms ago' ;;
    time-unsigned) appops_value='MOCK_LOCATION: allow; time=1ms ago' ;;
    elapsed-unsigned) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=1ms' ;;
    elapsed-signed-zero) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+0ms' ;;
    duration-unicode-digit) appops_value=$'MOCK_LOCATION: allow; time=+1s1\331\241ms ago' ;;
    unicode-op-name) appops_value=$'MOC\342\204\252_LOCATION: allow' ;;
    reject-before-time) appops_value='MOCK_LOCATION: allow; rejectTime=+2s0ms ago; time=+1s0ms ago' ;;
    duplicate-time) appops_value='MOCK_LOCATION: allow; time=+1s0ms ago; time=+2s0ms ago' ;;
    duplicate-reject) appops_value='MOCK_LOCATION: allow; rejectTime=+1ms ago; rejectTime=+2ms ago' ;;
    duplicate-duration) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+1ms; duration=+2ms' ;;
    duplicate-running) appops_value='MOCK_LOCATION: allow; time=+1ms ago (running) (running)' ;;
    no-operations-wrong-default) appops_value=$'No operations.\nDefault mode: ignore' ;;
    no-operations-missing-default) appops_value='No operations.' ;;
    no-operations-extra-line) appops_value=$'No operations.\nDefault mode: deny\nextra' ;;
    no-operations-wrong-case) appops_value=$'no operations.\nDefault mode: deny' ;;
    no-operations-spacing) appops_value=$'No operations.\nDefault mode:  deny' ;;
    uid-default-deny) appops_value='Uid mode: MOCK_LOCATION: deny' ;;
    uid-after-package) appops_value=$'MOCK_LOCATION: allow\nUid mode: MOCK_LOCATION: ignore' ;;
    duplicate-uid) appops_value=$'Uid mode: MOCK_LOCATION: ignore\nUid mode: MOCK_LOCATION: default' ;;
    uid-metadata) appops_value='Uid mode: MOCK_LOCATION: ignore; time=+1ms ago' ;;
    uid-wrong-operation) appops_value='Uid mode: FINE_LOCATION: allow' ;;
    lone-cr) appops_value=$'MOCK_LOCATION: allow\r' ;;
    missing-newline) appops_value='MOCK_LOCATION: allow' ;;
  esac
  if [ "$appops_shape" = missing-newline ] || [ "$appops_shape" = lone-cr ]; then
    printf '%s' "$appops_value" \
      >"$broken/receipts/package-name-caiyao-fakegps-appops.stdout.txt"
  else
    printf '%s\n' "$appops_value" \
      >"$broken/receipts/package-name-caiyao-fakegps-appops.stdout.txt"
  fi
  rebind_receipt_tree "$broken"
  verify_mutation_stop \
    "host verifier rejects $appops_shape AppOps framing" "$broken"
done

# Keep all receipt semantics valid while changing one byte representation. A
# refusal here can come only from the independently recomputed tree digest.
broken="$WORK/verify-stale-receipt-tree-digest"
cp -R "$G00_OUT" "$broken"
printf 'true\r\n' >"$broken/receipts/location-enabled.stdout.txt"
verify_mutation_stop "host verifier rejects a semantics-valid receipt byte change with a stale tree digest" "$broken"

broken="$WORK/verify-world-readable-root"
cp -R "$G00_OUT" "$broken"
chmod 755 "$broken"
verify_mutation_stop "host verifier rejects an evidence root that is not private mode 0700" "$broken"

if [ "$(uname -s)" = Darwin ]; then
  broken="$WORK/verify-root-extended-acl"
  cp -R "$G00_OUT" "$broken"
  if chmod +a 'everyone allow list,search,readattr,readextattr' "$broken"; then
    verify_mutation_stop \
      "host verifier rejects a mode-0700 evidence root with an extended ACL" \
      "$broken"
  else
    report fail "selftest can install a verifier ACL fixture" "chmod +a failed"
  fi
fi

broken="$WORK/verify-adb-snapshot-writable"
cp -R "$G00_OUT" "$broken"
chmod 700 "$broken/tooling/adb"
verify_mutation_stop "host verifier rejects a writable adb snapshot" "$broken"

VERIFY_ROOT_LINK="$WORK/verify-root-link"
ln -s "$G00_OUT" "$VERIFY_ROOT_LINK"
run_verify "$VERIFY_ROOT_LINK/"
if [ "$RC" -eq 21 ] && [[ $OUT == *STOP_INCOMPLETE_RECEIPT* ]]; then
  report ok "host verifier rejects a trailing-slash evidence symlink"
else
  report fail "host verifier rejects a trailing-slash evidence symlink" "rc=$RC output=$OUT"
fi
expect_no_adb_call "trailing-slash evidence symlink verification performs no adb call"

broken="$WORK/verify-manifest-symlink"
cp -R "$G00_OUT" "$broken"
rm "$broken/manifest.json"
ln -s "$G00_OUT/manifest.json" "$broken/manifest.json"
verify_mutation_stop "host verifier rejects a symlinked manifest" "$broken"

broken="$WORK/verify-manifest-fifo"
cp -R "$G00_OUT" "$broken"
rm "$broken/manifest.json"
mkfifo "$broken/manifest.json"
verify_mutation_stop "host verifier rejects a FIFO manifest without reading it" "$broken"

broken="$WORK/verify-services-nonzero-exit"
cp -R "$G00_OUT" "$broken"
printf '13\n' >"$broken/receipts/services-jar.exit.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects nonzero services.jar exit" "$broken"

broken="$WORK/verify-truncated-apk-archive"
cp -R "$G00_OUT" "$broken"
printf 'P' >"$broken/receipts/package-name-caiyao-fakegps-apk.stdout.bin"
rebind_receipt_tree "$broken"
"$PYTHON_BIN" -I - "$broken/manifest.json" "$broken/summary.json" <<'PY'
import hashlib
import json
import pathlib
import sys

digest = hashlib.sha256(b"P").hexdigest()
for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["packageApkSha256"]["name.caiyao.fakegps"] = digest
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
verify_mutation_stop "host verifier rejects a digest-rebound truncated APK" "$broken"

broken="$WORK/verify-truncated-services-archive"
cp -R "$G00_OUT" "$broken"
printf 'P' >"$broken/receipts/services-jar.stdout.bin"
rebind_receipt_tree "$broken"
"$PYTHON_BIN" -I - "$broken/manifest.json" "$broken/summary.json" <<'PY'
import hashlib
import json
import pathlib
import sys

digest = hashlib.sha256(b"P").hexdigest()
for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["servicesJarSha256"] = digest
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
verify_mutation_stop "host verifier rejects digest-rebound truncated services.jar" "$broken"

# Android package-manager prints the base APK followed by any split APKs. The
# collector is intentionally base-byte-only, but it must validate every path,
# select exactly one base.apk, preserve the full receipt, and remain verifiable.
SPLIT_PACKAGE_OUT="$WORK/out-split-package"
run_collect split-package "$AUTHORIZED_SERIAL" "$SPLIT_PACKAGE_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "validated split-package output selects the base APK"
else
  report fail "validated split-package output selects the base APK" "rc=$RC output=$OUT"
fi
run_verify "$SPLIT_PACKAGE_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "split-package collection remains receipt-verifiable"
else
  report fail "split-package collection remains receipt-verifiable" "rc=$RC output=$OUT"
fi
expect_no_adb_call "split-package offline verification performs no adb call"

# A package APK may be read only from one exact validated `pm path` base entry
# for that same fixed package. Shell metacharacters, multiple base APKs, or a
# path naming another package are local-safety failures before exec-out.
for path_scenario in \
    unsafe-pm-path-injection \
    unsafe-pm-path-multiple \
    unsafe-pm-path-wrong-package \
    unsafe-pm-path-dot \
    unsafe-pm-path-dotdot; do
  path_out="$WORK/out-$path_scenario"
  run_collect "$path_scenario" "$AUTHORIZED_SERIAL" "$path_out"
  expect_stop "$path_scenario is refused" STOP_UNSAFE_PACKAGE_PATH
  expect_exit_code "$path_scenario uses local-safety rc=22" 22
  expect_only_authorized_target "$path_scenario stays on the authorized target"
  assert_no_privileged_fallback "$ADB_LOG" "$path_scenario has no privileged fallback"
  if grep -Eq -- "exec-out cat /data/app/" "$ADB_LOG"; then
    report fail "$path_scenario never reads an unvalidated APK path" \
      "apk byte read escaped: $(grep -E -- 'exec-out cat /data/app/' "$ADB_LOG" | tr '\n' ';')"
  else
    report ok "$path_scenario never reads an unvalidated APK path"
  fi
  if [ -f "$path_out/manifest.json" ]; then
    assert_stop_manifest "$path_out/manifest.json" STOP_UNSAFE_PACKAGE_PATH \
      "$path_scenario manifest preserves the exact reason"
    assert_six_file_receipts "$path_out/manifest.json" "$path_out/receipts" \
      "$path_scenario preserves strict six-file receipts"
  else
    report fail "$path_scenario manifest exists" "missing manifest.json"
  fi
done

# Installed-package observation truth table:
#   dumpsys rc0 requires the exact `Package [<pkg>]` anchor;
#   pidof rc0 requires decimal PID tokens, while rc1+empty is NOT_RUNNING;
#   appops rc0 requires the requested android:mock_location row;
#   malformed rc0 output is distinct from a nonzero transport/read failure.
package_observation_cases=(
  "pm-path-stderr|STOP_PACKAGE_OBSERVATION_MALFORMED|installed package path with stderr"
  "dumpsys-malformed|STOP_PACKAGE_OBSERVATION_MALFORMED|malformed dumpsys package anchor"
  "dumpsys-failure|STOP_ADB_READ_FAILED|failed dumpsys package read"
  "pidof-malformed|STOP_PACKAGE_OBSERVATION_MALFORMED|malformed pidof output"
  "pidof-failure|STOP_ADB_READ_FAILED|failed pidof read"
  "appops-malformed|STOP_PACKAGE_OBSERVATION_MALFORMED|malformed mock-location AppOps output"
  "appops-conflict|STOP_PACKAGE_OBSERVATION_MALFORMED|conflicting mock-location AppOps rows"
  "appops-conflict-inline|STOP_PACKAGE_OBSERVATION_MALFORMED|inline conflicting mock-location AppOps rows"
  "appops-public-op-name|STOP_PACKAGE_OBSERVATION_MALFORMED|public-string AppOps operation name"
  "appops-op-name-wrong-case|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong-case AppOps operation name"
  "appops-mode-wrong-case|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong-case AppOps mode"
  "appops-unknown-mode|STOP_PACKAGE_OBSERVATION_MALFORMED|unknown AppOps mode"
  "appops-leading-space|STOP_PACKAGE_OBSERVATION_MALFORMED|leading-space AppOps row"
  "appops-trailing-space|STOP_PACKAGE_OBSERVATION_MALFORMED|trailing-space AppOps row"
  "appops-multiple-space|STOP_PACKAGE_OBSERVATION_MALFORMED|multi-space AppOps separator"
  "appops-error-tail|STOP_PACKAGE_OBSERVATION_MALFORMED|error-bearing mock-location AppOps metadata"
  "appops-default-tail|STOP_PACKAGE_OBSERVATION_MALFORMED|conflicting default-mode AppOps metadata"
  "appops-bogus-time|STOP_PACKAGE_OBSERVATION_MALFORMED|non-duration AppOps time metadata"
  "appops-tab-spacing|STOP_PACKAGE_OBSERVATION_MALFORMED|control-whitespace AppOps separator"
  "appops-time-without-ago|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps time metadata without ago"
  "appops-reject-without-ago|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps rejectTime metadata without ago"
  "appops-duration-with-ago|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration metadata with ago"
  "appops-duration-negative|STOP_PACKAGE_OBSERVATION_MALFORMED|negative AppOps elapsed duration"
  "appops-orphan-duration|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration without access time"
  "appops-orphan-running|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps running marker without access time"
  "appops-duration-running|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration combined with running"
  "appops-duration-running-reverse|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps running appended after duration"
  "appops-wrong-order|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-order AppOps metadata"
  "appops-metadata-tab|STOP_PACKAGE_OBSERVATION_MALFORMED|tab-prefixed AppOps metadata"
  "appops-duration-missing-ms|STOP_PACKAGE_OBSERVATION_MALFORMED|nonzero AppOps duration without milliseconds"
  "appops-time-missing-ms|STOP_PACKAGE_OBSERVATION_MALFORMED|nonzero AppOps time without milliseconds"
  "appops-reject-missing-ms|STOP_PACKAGE_OBSERVATION_MALFORMED|nonzero AppOps rejectTime without milliseconds"
  "appops-duration-gap|STOP_PACKAGE_OBSERVATION_MALFORMED|noncanonical AppOps duration unit gap"
  "appops-duration-hour-gap|STOP_PACKAGE_OBSERVATION_MALFORMED|hour-to-second AppOps duration unit gap"
  "appops-duration-minute-gap|STOP_PACKAGE_OBSERVATION_MALFORMED|minute-to-millisecond AppOps duration unit gap"
  "appops-duration-day-missing-hour|STOP_PACKAGE_OBSERVATION_MALFORMED|day-to-minute AppOps duration unit gap"
  "appops-duration-day-missing-minute|STOP_PACKAGE_OBSERVATION_MALFORMED|day-to-second AppOps duration unit gap"
  "appops-duration-day-missing-second|STOP_PACKAGE_OBSERVATION_MALFORMED|day-to-millisecond AppOps duration unit gap"
  "appops-duration-hour-missing-second|STOP_PACKAGE_OBSERVATION_MALFORMED|hour-to-millisecond AppOps duration unit gap"
  "appops-duration-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps duration field"
  "appops-duration-minute-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps minute field"
  "appops-duration-second-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps second field"
  "appops-duration-residual-hour-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps residual hour field"
  "appops-duration-residual-minute-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps residual minute field"
  "appops-duration-residual-second-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps residual second field"
  "appops-duration-ms-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps millisecond field"
  "appops-duration-top-ms-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range top-level AppOps millisecond field"
  "appops-duration-day-overflow|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration beyond the TimeUtils day ceiling"
  "appops-duration-day-residual-overflow|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration beyond the TimeUtils residual ceiling"
  "appops-duration-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps duration field"
  "appops-duration-day-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps day field"
  "appops-duration-hour-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps hour field"
  "appops-duration-minute-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps minute field"
  "appops-duration-second-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps second field"
  "appops-duration-residual-hour-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps residual hour field"
  "appops-duration-residual-minute-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps residual minute field"
  "appops-duration-residual-second-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps residual second field"
  "appops-duration-top-ms-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded top-level AppOps millisecond field"
  "appops-duration-signed-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|signed-zero AppOps duration"
  "appops-duration-negative-signed-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|negative signed-zero AppOps duration"
  "appops-time-unsigned|STOP_PACKAGE_OBSERVATION_MALFORMED|unsigned nonzero AppOps time"
  "appops-elapsed-unsigned|STOP_PACKAGE_OBSERVATION_MALFORMED|unsigned nonzero AppOps elapsed duration"
  "appops-elapsed-signed-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|signed-zero AppOps elapsed duration"
  "appops-duration-unicode-digit|STOP_PACKAGE_OBSERVATION_MALFORMED|Unicode-digit AppOps duration"
  "appops-unicode-op-name|STOP_PACKAGE_OBSERVATION_MALFORMED|Unicode-folded AppOps operation name"
  "appops-reject-before-time|STOP_PACKAGE_OBSERVATION_MALFORMED|rejectTime preceding AppOps time"
  "appops-duplicate-time|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps time field"
  "appops-duplicate-reject|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps rejectTime field"
  "appops-duplicate-duration|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps duration field"
  "appops-duplicate-running|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps running marker"
  "appops-no-operations-wrong-default|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong default mode for empty mock-location AppOps"
  "appops-no-operations-missing-default|STOP_PACKAGE_OBSERVATION_MALFORMED|missing default line for empty mock-location AppOps"
  "appops-no-operations-extra-line|STOP_PACKAGE_OBSERVATION_MALFORMED|extra line after empty mock-location AppOps"
  "appops-no-operations-wrong-case|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong-case empty mock-location AppOps"
  "appops-no-operations-spacing|STOP_PACKAGE_OBSERVATION_MALFORMED|multi-space empty mock-location AppOps default"
  "appops-uid-default-deny|STOP_PACKAGE_OBSERVATION_MALFORMED|default-valued UID mock-location AppOps row"
  "appops-uid-after-package|STOP_PACKAGE_OBSERVATION_MALFORMED|UID AppOps row after package row"
  "appops-duplicate-uid|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate UID AppOps rows"
  "appops-uid-metadata|STOP_PACKAGE_OBSERVATION_MALFORMED|metadata attached to UID AppOps row"
  "appops-uid-wrong-operation|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong operation in UID AppOps row"
  "appops-missing-newline|STOP_PACKAGE_OBSERVATION_MALFORMED|missing AppOps line terminator"
  "appops-lone-cr|STOP_PACKAGE_OBSERVATION_MALFORMED|lone-CR AppOps terminator"
  "appops-failure|STOP_ADB_READ_FAILED|failed mock-location AppOps read"
)
for observation_case in "${package_observation_cases[@]}"; do
  IFS='|' read -r observation_scenario observation_marker observation_label \
    <<<"$observation_case"
  observation_out="$WORK/out-$observation_scenario"
  run_collect "$observation_scenario" "$AUTHORIZED_SERIAL" "$observation_out"
  expect_stop "$observation_label is refused" "$observation_marker"
  expect_exit_code "$observation_label uses evidence rc=21" 21
  expect_only_authorized_target "$observation_label stays scoped"
  assert_no_privileged_fallback "$ADB_LOG" "$observation_label has no privileged fallback"
  if [ -f "$observation_out/manifest.json" ]; then
    assert_stop_manifest "$observation_out/manifest.json" "$observation_marker" \
      "$observation_label manifest preserves the exact reason"
    assert_six_file_receipts "$observation_out/manifest.json" "$observation_out/receipts" \
      "$observation_label preserves strict six-file receipts"
  else
    report fail "$observation_label manifest exists" "missing manifest.json"
  fi
done

PIDOF_IDLE_OUT="$WORK/out-pidof-not-running"
run_collect pidof-not-running "$AUTHORIZED_SERIAL" "$PIDOF_IDLE_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "installed package pidof rc=1+empty records NOT_RUNNING without failing"
else
  report fail "installed package pidof rc=1+empty records NOT_RUNNING without failing" \
    "rc=$RC output=$OUT"
fi

APPOPS_METADATA_OUT="$WORK/out-appops-metadata"
run_collect appops-metadata "$AUTHORIZED_SERIAL" "$APPOPS_METADATA_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped AppOps time metadata remains accepted"
else
  report fail "official-shaped AppOps time metadata remains accepted" \
    "rc=$RC output=$OUT"
fi
run_verify "$APPOPS_METADATA_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped AppOps metadata remains receipt-verifiable"
else
  report fail "official-shaped AppOps metadata remains receipt-verifiable" \
    "rc=$RC output=$OUT"
fi

APPOPS_RUNNING_OUT="$WORK/out-appops-running"
run_collect appops-running "$AUTHORIZED_SERIAL" "$APPOPS_RUNNING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped running AppOps row remains accepted"
else
  report fail "official-shaped running AppOps row remains accepted" \
    "rc=$RC output=$OUT"
fi
run_verify "$APPOPS_RUNNING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped running AppOps row remains receipt-verifiable"
else
  report fail "official-shaped running AppOps row remains receipt-verifiable" \
    "rc=$RC output=$OUT"
fi

APPOPS_BOUNDARY_OUT="$WORK/out-appops-canonical-boundaries"
run_collect appops-canonical-boundaries "$AUTHORIZED_SERIAL" "$APPOPS_BOUNDARY_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "canonical day/hour/millisecond AppOps boundaries remain accepted"
else
  report fail "canonical day/hour/millisecond AppOps boundaries remain accepted" \
    "rc=$RC output=$OUT"
fi
run_verify "$APPOPS_BOUNDARY_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "canonical AppOps duration boundaries remain receipt-verifiable"
else
  report fail "canonical AppOps duration boundaries remain receipt-verifiable" \
    "rc=$RC output=$OUT"
fi

for positive_appops_case in \
    "appops-canonical-zero-negative|canonical zero/negative AppOps durations" \
    "appops-no-operations|canonical no-operations AppOps response" \
    "appops-uid-mode|canonical UID-level AppOps override" \
    "appops-uid-allow|canonical UID-level allow AppOps override" \
    "appops-uid-foreground|canonical UID-level foreground AppOps override" \
    "appops-uid-and-package|canonical UID plus package AppOps response" \
    "appops-mode-default|canonical default AppOps mode" \
    "appops-mode-foreground|canonical foreground AppOps mode" \
    "appops-crlf|CRLF-normalized canonical AppOps response" \
    "appops-canonical-minute-second|canonical minute/second AppOps boundaries" \
    "appops-canonical-day-hour-boundaries|canonical 23-hour and multi-day AppOps boundaries" \
    "appops-canonical-max-duration|canonical maximum TimeUtils AppOps duration" \
    "appops-reject-only|canonical reject-only package AppOps response" \
    "appops-time-only|canonical time-only package AppOps response" \
    "appops-time-reject-only|canonical time-plus-reject AppOps response" \
    "appops-time-reject-running|canonical time-plus-reject running AppOps response" \
    "appops-elapsed-day|canonical day AppOps elapsed duration" \
    "appops-elapsed-hour|canonical hour AppOps elapsed duration" \
    "appops-elapsed-minute|canonical minute AppOps elapsed duration"; do
  IFS='|' read -r positive_appops_scenario positive_appops_label \
    <<<"$positive_appops_case"
  positive_appops_out="$WORK/out-$positive_appops_scenario"
  run_collect "$positive_appops_scenario" "$AUTHORIZED_SERIAL" "$positive_appops_out"
  if [ "$RC" -eq 0 ]; then
    report ok "$positive_appops_label remains accepted"
  else
    report fail "$positive_appops_label remains accepted" "rc=$RC output=$OUT"
  fi
  run_verify "$positive_appops_out"
  if [ "$RC" -eq 0 ]; then
    report ok "$positive_appops_label remains receipt-verifiable"
  else
    report fail "$positive_appops_label remains receipt-verifiable" \
      "rc=$RC output=$OUT"
  fi
done

archive_cases=(
  "apk-truncated|STOP_APK_READ_FAILED|single-byte APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-empty-archive|STOP_APK_READ_FAILED|empty APK ZIP|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-duplicate-member|STOP_APK_READ_FAILED|duplicate-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-parent-member|STOP_APK_READ_FAILED|parent-traversing-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-absolute-member|STOP_APK_READ_FAILED|absolute-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-nul-member|STOP_APK_READ_FAILED|NUL-suffixed-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-missing-manifest|STOP_APK_READ_FAILED|manifest-free APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-crc-corrupt|STOP_APK_READ_FAILED|CRC-corrupt APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "services-truncated|STOP_FRAMEWORK_READ_FAILED|single-byte services.jar|services-jar.stdout.bin"
  "services-missing-dex|STOP_FRAMEWORK_READ_FAILED|dex-free services.jar|services-jar.stdout.bin"
  "services-nul-member|STOP_FRAMEWORK_READ_FAILED|NUL-suffixed-dex services.jar|services-jar.stdout.bin"
  "services-crc-corrupt|STOP_FRAMEWORK_READ_FAILED|CRC-corrupt services.jar|services-jar.stdout.bin"
)
for archive_case in "${archive_cases[@]}"; do
  IFS='|' read -r archive_scenario archive_marker archive_label archive_receipt <<EOF
$archive_case
EOF
  archive_out="$WORK/out-$archive_scenario"
  run_collect "$archive_scenario" "$AUTHORIZED_SERIAL" "$archive_out"
  expect_stop "$archive_label output is refused" "$archive_marker"
  expect_exit_code "$archive_label output uses evidence rc=21" 21
  expect_only_authorized_target "$archive_label refusal stays scoped"
  if [ -f "$archive_out/manifest.json" ]; then
    assert_stop_manifest "$archive_out/manifest.json" "$archive_marker" \
      "$archive_label manifest preserves the exact reason"
  else
    report fail "$archive_label manifest exists" "missing manifest.json"
  fi
  archive_stem=${archive_receipt%.stdout.bin}
  if [ -f "$archive_out/receipts/$archive_stem.exit.txt" ] \
      && [ "$(tr -d '\r\n' <"$archive_out/receipts/$archive_stem.exit.txt")" = 0 ] \
      && [ ! -s "$archive_out/receipts/$archive_stem.stderr.bin" ]; then
    report ok "$archive_label reaches the archive parser after adb rc=0"
  else
    report fail "$archive_label reaches the archive parser after adb rc=0" \
      "missing/nonzero exit or nonempty stderr"
  fi

  broken="$WORK/verify-$archive_scenario"
  cp -R "$G00_OUT" "$broken"
  if [ -f "$archive_out/receipts/$archive_receipt" ]; then
    cp "$archive_out/receipts/$archive_receipt" \
      "$broken/receipts/$archive_receipt"
    rebind_receipt_tree "$broken"
    if [ "$archive_receipt" = services-jar.stdout.bin ]; then
      rebind_binary_claim "$broken" "$archive_receipt"
    else
      rebind_binary_claim "$broken" "$archive_receipt" name.caiyao.fakegps
    fi
    verify_mutation_stop \
      "host verifier rejects $archive_label bytes after digest rebinding" "$broken"
  else
    report fail "host verifier fixture retains $archive_label bytes" \
      "missing $archive_out/receipts/$archive_receipt"
  fi
done

# Framework bytes are required static evidence. Preserve a real exec-out
# failure as binary six-file evidence and never retry through su/root.
FRAMEWORK_FAIL_OUT="$WORK/out-services-exit13"
run_collect services-exit13 "$AUTHORIZED_SERIAL" "$FRAMEWORK_FAIL_OUT"
expect_stop "services.jar adb rc=13 is an explicit framework failure" \
  STOP_FRAMEWORK_READ_FAILED
expect_exit_code "services.jar adb rc=13 uses evidence rc=21" 21
expect_only_authorized_target "services.jar failure stays scoped"
assert_no_privileged_fallback "$ADB_LOG" "services.jar failure has no su/root fallback"
if [ -f "$FRAMEWORK_FAIL_OUT/manifest.json" ]; then
  assert_stop_manifest "$FRAMEWORK_FAIL_OUT/manifest.json" STOP_FRAMEWORK_READ_FAILED \
    "services.jar failure manifest preserves the exact reason"
  assert_six_file_receipts "$FRAMEWORK_FAIL_OUT/manifest.json" "$FRAMEWORK_FAIL_OUT/receipts" \
    "services.jar failure preserves strict six-file receipts"
else
  report fail "services.jar failure manifest exists" "missing manifest.json"
fi
if [ -f "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stdout.bin" ] \
    && [ ! -e "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stdout.txt" ]; then
  report ok "services.jar failure uses stdout.bin only"
else
  report fail "services.jar failure uses stdout.bin only" "binary receipt missing or duplicated as text"
fi
if [ -f "$FRAMEWORK_FAIL_OUT/receipts/services-jar.exit.txt" ] \
    && [ "$(tr -d '\r\n' <"$FRAMEWORK_FAIL_OUT/receipts/services-jar.exit.txt")" = 13 ]; then
  report ok "services.jar receipt preserves adb exit=13"
else
  report fail "services.jar receipt preserves adb exit=13" "exit receipt missing or changed"
fi
if [ -f "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stderr.bin" ] \
    && grep -F -q "fixture services.jar transport failure" \
      "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stderr.bin"; then
  report ok "services.jar receipt preserves adb stderr"
else
  report fail "services.jar receipt preserves adb stderr" "fixture stderr missing"
fi

# A known package may legitimately be absent. Its AOSP-shaped `pm path`
# result is rc=1 with empty stdout/stderr; record NOT_INSTALLED and skip that package's
# dumpsys/pidof/appops/APK reads while completing the public collection.
MISSING_OUT="$WORK/out-missing-known-package"
run_collect missing-package "$AUTHORIZED_SERIAL" "$MISSING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "missing known package does not fail public collection"
else
  report fail "missing known package does not fail public collection" "rc=$RC output=$OUT"
fi
if [ -f "$MISSING_OUT/manifest.json" ]; then
  assert_manifest_ceiling "$MISSING_OUT/manifest.json"
  assert_binary_hash_manifest "$MISSING_OUT/manifest.json" "$MISSING_OUT/receipts" \
    "missing-package manifest hashes only installed APK binary receipts"
  assert_tool_hash_binding "$MISSING_OUT/manifest.json" "$MISSING_OUT/summary.json" \
    "$FAKE_ADB" "$COLLECTOR" "$ADB_ALLOWLIST" \
    "missing-package manifest and summary bind the exact adb/collector SHA-256"
  assert_redacted_summary "$MISSING_OUT/summary.json" "$MISSING_OUT/manifest.json" \
    "missing-package run emits an exact-whitelist coordinate-free summary"
  assert_six_file_receipts "$MISSING_OUT/manifest.json" "$MISSING_OUT/receipts" \
    "missing-package run preserves strict six-file receipts"
  assert_public_collection "$MISSING_OUT/manifest.json" "$MISSING_OUT/receipts" "$ADB_LOG" \
    "$MISSING_FIXTURE_PACKAGE" "missing package is recorded as NOT_INSTALLED" \
    "${KNOWN_PACKAGES[@]}"
else
  report fail "missing-package manifest exists" "missing manifest.json"
fi
assert_no_privileged_fallback "$ADB_LOG" "missing-package run has no privileged fallback"
run_verify "$MISSING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "canonical missing-package receipts remain offline-verifiable"
else
  report fail "canonical missing-package receipts remain offline-verifiable" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "canonical missing-package offline verification performs no adb call"
missing_stem="package-${MISSING_FIXTURE_PACKAGE//./-}-path"
if [ -f "$MISSING_OUT/receipts/$missing_stem.exit.txt" ] \
    && [ "$(tr -d '\r\n' <"$MISSING_OUT/receipts/$missing_stem.exit.txt")" = 1 ] \
    && [ ! -s "$MISSING_OUT/receipts/$missing_stem.stdout.txt" ] \
    && [ ! -s "$MISSING_OUT/receipts/$missing_stem.stderr.bin" ]; then
  report ok "NOT_INSTALLED preserves AOSP pm-path rc=1 with empty stdout/stderr"
else
  report fail "NOT_INSTALLED preserves AOSP pm-path rc=1 with empty stdout/stderr" \
    "missing or altered pm-path receipt"
fi
if grep -Eq -- "shell (dumpsys package|pidof|appops get).*${MISSING_FIXTURE_PACKAGE}|exec-out cat /data/app/.*/${MISSING_FIXTURE_PACKAGE}-" "$ADB_LOG"; then
  report fail "NOT_INSTALLED skips package-specific follow-up reads" \
    "unexpected follow-up: $(grep -E -- "$MISSING_FIXTURE_PACKAGE" "$ADB_LOG" | tr '\n' ';')"
else
  report ok "NOT_INSTALLED skips package-specific follow-up reads"
fi
if grep -Eq -- "exec-out cat /data/app/.*/$MISSING_FIXTURE_PACKAGE-" "$ADB_LOG"; then
  report fail "NOT_INSTALLED package has no APK byte read" "unexpected exec-out cat"
else
  report ok "NOT_INSTALLED package has no APK byte read"
fi

broken="$WORK/verify-missing-package-stderr"
cp -R "$MISSING_OUT" "$broken"
printf 'Unknown package: %s\n' "$MISSING_FIXTURE_PACKAGE" \
  >"$broken/receipts/$missing_stem.stderr.bin"
rebind_receipt_tree "$broken"
verify_mutation_stop \
  "host verifier rejects noncanonical stderr on an Android 15 missing package" "$broken"

MISSING_STDERR_OUT="$WORK/out-missing-known-package-stderr"
run_collect missing-package-stderr "$AUTHORIZED_SERIAL" "$MISSING_STDERR_OUT"
expect_stop "noncanonical missing-package stderr is refused" \
  STOP_PACKAGE_OBSERVATION_MALFORMED
expect_exit_code "noncanonical missing-package stderr uses evidence rc=21" 21
expect_only_authorized_target "noncanonical missing-package stderr stays scoped"
assert_no_privileged_fallback "$ADB_LOG" \
  "noncanonical missing-package stderr has no privileged fallback"
if [ -f "$MISSING_STDERR_OUT/manifest.json" ]; then
  assert_stop_manifest "$MISSING_STDERR_OUT/manifest.json" \
    STOP_PACKAGE_OBSERVATION_MALFORMED \
    "noncanonical missing-package stderr manifest preserves the exact reason"
else
  report fail "noncanonical missing-package stderr manifest exists" \
    "missing manifest.json"
fi

# 1. The authorized target is absent. Inventory is allowed, but no targeted
# command may run and absence can never be reinterpreted as a partial PASS.
DEVICES_FAIL_OUT="$WORK/out-devices-exit7"
run_collect devices-exit7 "$AUTHORIZED_SERIAL" "$DEVICES_FAIL_OUT"
expect_stop "devices -l rc=7 is an adb read failure" STOP_ADB_READ_FAILED
expect_exit_code "devices -l rc=7 uses evidence rc=21" 21
assert_no_adb_after "$ADB_LOG" "devices -l" \
  "failed device inventory stops before any targeted adb read"
if [ -f "$DEVICES_FAIL_OUT/manifest.json" ]; then
  assert_stop_manifest "$DEVICES_FAIL_OUT/manifest.json" STOP_ADB_READ_FAILED \
    "failed device inventory manifest preserves the exact reason"
  assert_six_file_receipts "$DEVICES_FAIL_OUT/manifest.json" "$DEVICES_FAIL_OUT/receipts" \
    "failed device inventory preserves its six-file receipt"
else
  report fail "failed device inventory manifest exists" "missing manifest.json"
fi
if [ "$(tr -d '\r\n' <"$DEVICES_FAIL_OUT/receipts/devices.exit.txt" 2>/dev/null)" = 7 ] \
    && grep -Eq -- 'fixture devices inventory transport failure' \
      "$DEVICES_FAIL_OUT/receipts/devices.stderr.bin"; then
  report ok "failed device inventory preserves rc=7 and stderr"
else
  report fail "failed device inventory preserves rc=7 and stderr" "receipt truth changed"
fi

DEVICES_HIDDEN_CR_OUT="$WORK/out-devices-hidden-cr"
run_collect devices-hidden-cr "$AUTHORIZED_SERIAL" "$DEVICES_HIDDEN_CR_OUT"
expect_stop "device inventory cannot hide an extra row behind bare CR" \
  STOP_INCOMPLETE_CORE_RECEIPT
expect_exit_code "bare-CR device inventory uses evidence rc=21" 21
assert_no_adb_after "$ADB_LOG" "devices -l" \
  "bare-CR device inventory stops before targeted adb reads"

run_collect missing-target "$AUTHORIZED_SERIAL" "$WORK/out-missing"
expect_stop "missing target is refused" "STOP_MISSING_TARGET"
expect_exit_code "missing target uses topology/identity rc=20" 20
expect_only_authorized_target "missing-target adb surface stays scoped"
if grep -q -- "^-s " "$ADB_LOG"; then
  report fail "missing target receives no targeted adb call" "log=$(tr '\n' ';' <"$ADB_LOG")"
else
  report ok "missing target receives no targeted adb call"
fi

# Inventory ambiguity includes devices that are not currently usable. Their
# offline/unauthorized state is not permission to silently ignore them.
for extra_scenario in extra-offline extra-unauthorized extra-emulator; do
  run_collect "$extra_scenario" "$AUTHORIZED_SERIAL" "$WORK/out-$extra_scenario"
  expect_stop "$extra_scenario is refused as an additional device" "STOP_EXTRA_DEVICE"
  expect_exit_code "$extra_scenario uses topology/identity rc=20" 20
  expect_only_authorized_target "$extra_scenario adb surface stays scoped"
  if grep -q -- "^-s " "$ADB_LOG"; then
    report fail "$extra_scenario receives no targeted adb call" "log=$(tr '\n' ';' <"$ADB_LOG")"
  else
    report ok "$extra_scenario receives no targeted adb call"
  fi
done

# 2. Even when the Moto is present, any additional attached device is an
# ambiguity boundary. Stop before a targeted command.
run_collect extra-device "$AUTHORIZED_SERIAL" "$WORK/out-extra"
expect_stop "additional attached device is refused" "STOP_EXTRA_DEVICE"
expect_exit_code "additional attached device uses topology/identity rc=20" 20
expect_only_authorized_target "extra-device adb surface stays scoped"
if grep -q -- "^-s " "$ADB_LOG"; then
  report fail "extra-device case receives no targeted adb call" "log=$(tr '\n' ';' <"$ADB_LOG")"
else
  report ok "extra-device case receives no targeted adb call"
fi

# 3. A caller cannot substitute a different serial. This fails before even
# querying device inventory, so authorization is not inferred from presence.
run_collect target "NOT_AUTHORIZED" "$WORK/out-wrong-serial"
expect_stop "wrong requested serial is refused" "STOP_WRONG_SERIAL"
expect_exit_code "wrong serial uses topology/identity rc=20" 20
expect_no_adb_call "wrong serial is rejected before adb"

# The live serial receipt has a three-way truth table: exact match continues,
# nonempty mismatch is identity failure, and only an empty scalar is incomplete.
LIVE_SERIAL_WRONG_OUT="$WORK/out-wrong-live-serial"
run_collect wrong-live-serial "$AUTHORIZED_SERIAL" "$LIVE_SERIAL_WRONG_OUT"
expect_stop "nonempty mismatched live serial is refused" STOP_WRONG_SERIAL
expect_exit_code "nonempty mismatched live serial uses identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getprop ro.serialno" \
  "nonempty mismatched live serial stops at its receipt"
if [ -f "$LIVE_SERIAL_WRONG_OUT/manifest.json" ]; then
  assert_stop_manifest "$LIVE_SERIAL_WRONG_OUT/manifest.json" STOP_WRONG_SERIAL \
    "nonempty mismatched live serial manifest preserves the exact reason"
else
  report fail "nonempty mismatched live serial manifest exists" "missing manifest.json"
fi

LIVE_SERIAL_EMPTY_OUT="$WORK/out-empty-live-serial"
run_collect empty-live-serial "$AUTHORIZED_SERIAL" "$LIVE_SERIAL_EMPTY_OUT"
expect_stop "empty live serial remains incomplete evidence" STOP_INCOMPLETE_CORE_RECEIPT
expect_exit_code "empty live serial uses evidence rc=21" 21
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getprop ro.serialno" \
  "empty live serial stops at its receipt"
if [ -f "$LIVE_SERIAL_EMPTY_OUT/manifest.json" ]; then
  assert_stop_manifest "$LIVE_SERIAL_EMPTY_OUT/manifest.json" STOP_INCOMPLETE_CORE_RECEIPT \
    "empty live serial manifest preserves the exact reason"
else
  report fail "empty live serial manifest exists" "missing manifest.json"
fi

# Scalar receipts are exactly one logical line. Embedded newlines must never be
# deleted into a different, apparently valid identity or API value.
for scalar_case in \
    "serial-multiline|ro.serialno|multiline live serial" \
    "serial-nul|ro.serialno|NUL-bearing live serial|STOP_INCOMPLETE_RECEIPT" \
    "serial-extra-lf|ro.serialno|extra-blank-line live serial" \
    "serial-edge-space|ro.serialno|edge-spaced live serial" \
    "api-multiline|ro.build.version.sdk|multiline API" \
    "fingerprint-control|ro.build.fingerprint|control-only fingerprint" \
    "fingerprint-c1|ro.build.fingerprint|C1 line-breaking fingerprint" \
    "fingerprint-line-separator|ro.build.fingerprint|Unicode line-separator fingerprint"; do
  IFS='|' read -r scalar_scenario scalar_anchor scalar_label scalar_marker <<EOF
$scalar_case
EOF
  scalar_marker=${scalar_marker:-STOP_INCOMPLETE_CORE_RECEIPT}
  scalar_out="$WORK/out-$scalar_scenario"
  run_collect "$scalar_scenario" "$AUTHORIZED_SERIAL" "$scalar_out"
  expect_stop "$scalar_label is refused as malformed evidence" "$scalar_marker"
  expect_exit_code "$scalar_label uses evidence rc=21" 21
  assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getprop $scalar_anchor" \
    "$scalar_label stops at its own receipt"
done

# 4. Live manufacturer must be Motorola. The exact serial alone is not enough.
run_collect wrong-manufacturer "$AUTHORIZED_SERIAL" "$WORK/out-manufacturer"
expect_stop "wrong manufacturer is refused" "STOP_WRONG_MANUFACTURER"
expect_exit_code "wrong manufacturer uses topology/identity rc=20" 20
expect_only_authorized_target "manufacturer check uses only authorized target"

# 5. Issue #66's planned oracle is Android 15 (API 35); another API stops.
run_collect wrong-api "$AUTHORIZED_SERIAL" "$WORK/out-api"
expect_stop "wrong API is refused" "STOP_WRONG_API"
expect_exit_code "wrong API uses topology/identity rc=20" 20
expect_only_authorized_target "API check uses only authorized target"

# 6. Evidence must be a newly created mode-0700 directory outside the repo.
# This path is deliberately inside the fixture tree and starts nonexistent.
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_OUT"
expect_stop "repository-contained output is refused" "STOP_UNSAFE_OUTPUT"
expect_exit_code "unsafe output uses local-safety rc=22" 22
expect_no_adb_call "unsafe output is rejected before adb"
if [ -e "$UNSAFE_OUT" ]; then
  report fail "unsafe output path is not created" "collector created $UNSAFE_OUT"
else
  report ok "unsafe output path is not created"
fi

if [ "$(uname -s)" = Darwin ] \
    && [ -d "/System/Volumes/Data$REPO_ROOT" ]; then
  run_collect target "$AUTHORIZED_SERIAL" "/System/Volumes/Data$UNSAFE_OUT"
  expect_stop "repository-contained output through the Data firmlink is refused" \
    STOP_UNSAFE_OUTPUT
  expect_exit_code "Data-firmlink output refusal uses local-safety rc=22" 22
  expect_no_adb_call "Data-firmlink output is rejected before adb"
  if [ -e "$UNSAFE_OUT" ]; then
    report fail "Data-firmlink alias cannot create inside the repository" \
      "collector created $UNSAFE_OUT"
  else
    report ok "Data-firmlink alias cannot create inside the repository"
  fi
fi

GIT_ENV_DECOY="$WORK/git-env-decoy"
git init -q "$GIT_ENV_DECOY"
export GIT_DIR="$GIT_ENV_DECOY/.git"
export GIT_WORK_TREE="$GIT_ENV_DECOY"
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_OUT"
unset GIT_DIR GIT_WORK_TREE
expect_stop "repository-contained output stays refused under poisoned GIT_DIR/GIT_WORK_TREE" \
  STOP_UNSAFE_OUTPUT
expect_exit_code "poisoned Git environment output refusal uses local-safety rc=22" 22
expect_no_adb_call "poisoned Git environment is rejected before adb"
if [ -e "$UNSAFE_OUT" ]; then
  report fail "poisoned Git environment cannot create repository-contained output" \
    "collector created $UNSAFE_OUT"
else
  report ok "poisoned Git environment cannot create repository-contained output"
fi

UNSAFE_ALIAS_PARENT="$WORK/repository-parent-alias"
ln -s "${UNSAFE_OUT%/*}" "$UNSAFE_ALIAS_PARENT"
UNSAFE_ALIAS_OUT="$UNSAFE_ALIAS_PARENT/${UNSAFE_OUT##*/}"
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_ALIAS_OUT"
expect_stop "repository-contained output through an ancestor symlink is refused" STOP_UNSAFE_OUTPUT
expect_exit_code "ancestor-symlink output refusal uses local-safety rc=22" 22
expect_no_adb_call "ancestor-symlink output is rejected before adb"
if [ -e "$UNSAFE_OUT" ]; then
  report fail "ancestor-symlink output cannot create inside the repository" \
    "collector created $UNSAFE_OUT"
else
  report ok "ancestor-symlink output cannot create inside the repository"
fi

GIT_COMMON_DIR="$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)"
UNSAFE_COMMON_OUT="$GIT_COMMON_DIR/.issue66-unsafe-output-$$"
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_COMMON_OUT"
expect_stop "git common-directory output is refused" STOP_UNSAFE_OUTPUT
expect_exit_code "git common-directory output uses local-safety rc=22" 22
expect_no_adb_call "git common-directory output is rejected before adb"
if [ -e "$UNSAFE_COMMON_OUT" ]; then
  report fail "git common-directory output path is not created" \
    "collector created $UNSAFE_COMMON_OUT"
else
  report ok "git common-directory output path is not created"
fi

if [ "$(uname -s)" = Darwin ]; then
  ACL_PARENT="$WORK/acl-inheritance-parent"
  ACL_OUTPUT="$ACL_PARENT/evidence"
  mkdir -m 700 "$ACL_PARENT"
  if chmod +a \
      'everyone allow list,search,add_file,add_subdirectory,file_inherit,directory_inherit' \
      "$ACL_PARENT"; then
    run_collect target "$AUTHORIZED_SERIAL" "$ACL_OUTPUT"
    expect_stop "output directory with an inherited extended ACL is refused" \
      STOP_UNSAFE_OUTPUT
    expect_exit_code "inherited ACL refusal uses local-safety rc=22" 22
    expect_no_adb_call "inherited ACL output is rejected before adb"
    if [ -e "$ACL_OUTPUT" ]; then
      report fail "unsafe ACL output directory is removed after refusal" \
        "unexpected output=$ACL_OUTPUT"
    else
      report ok "unsafe ACL output directory is removed after refusal"
    fi
    chmod -N "$ACL_PARENT"
  else
    report fail "selftest can install an inheritable ACL fixture" \
      "chmod +a failed"
  fi
fi

# Manifest persistence is part of the fail-closed boundary. The initial STOP
# write must fail before adb, and a failed final atomic replacement must never
# be followed by a COLLECTED message or success exit.
INITIAL_MANIFEST_FAIL_OUT="$WORK/out-manifest-initial-write-failure"
run_collect manifest-initial-write-failure "$AUTHORIZED_SERIAL" "$INITIAL_MANIFEST_FAIL_OUT"
expect_stop "initial STOP manifest write failure is fatal" STOP_INTERNAL_MANIFEST_WRITE
expect_exit_code "initial STOP manifest write failure uses internal rc=70" 70
expect_no_adb_call "initial STOP manifest write failure occurs before adb"

FINAL_MANIFEST_FAIL_OUT="$WORK/out-manifest-final-replace-failure"
run_collect manifest-final-replace-failure "$AUTHORIZED_SERIAL" "$FINAL_MANIFEST_FAIL_OUT"
expect_stop "final manifest replacement failure is fatal" STOP_INTERNAL_MANIFEST_WRITE
expect_exit_code "final manifest replacement failure uses internal rc=70" 70
if [[ $OUT == *"COLLECTED evidence="* ]]; then
  report fail "final manifest replacement failure cannot emit COLLECTED" "output=$OUT"
else
  report ok "final manifest replacement failure cannot emit COLLECTED"
fi
expect_only_authorized_target "final manifest replacement failure stays on the authorized target"

SUMMARY_FAIL_OUT="$WORK/out-summary-final-replace-failure"
run_collect summary-final-replace-failure "$AUTHORIZED_SERIAL" "$SUMMARY_FAIL_OUT"
expect_stop "final summary replacement failure is fatal" STOP_INTERNAL_SUMMARY_WRITE
expect_exit_code "final summary replacement failure uses internal rc=70" 70
if [ "$(cat "$SUMMARY_ORDER_LOG" 2>/dev/null)" = STOP ]; then
  report ok "COLLECTED manifest is not published before the final summary"
else
  report fail "COLLECTED manifest is not published before the final summary" \
    "manifest observed during summary failure: $(cat "$SUMMARY_ORDER_LOG" 2>/dev/null)"
fi

# 7. The exact allowlist must deny a mutation without executing adb. `settings
# put` is representative of the separately-authorized global-setting surface.
run_classify -s "$AUTHORIZED_SERIAL" shell settings put secure location_mode 3
expect_stop "mutating adb argv is refused" "STOP_MUTATING_COMMAND"
expect_exit_code "mutating adb argv uses local-safety rc=22" 22
expect_no_adb_call "mutating classification never executes adb"

# Full deny matrix. The first group is explicitly mutating; the final two are
# exact-allowlist drift and therefore UNLISTED. Every classifier query is pure
# host policy evaluation and must leave both fake and poison adb logs empty.
assert_classify_stop "install" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" install sample.apk
assert_classify_stop "uninstall" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" uninstall example.pkg
assert_classify_stop "push" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" push local.bin /data/local/tmp/remote.bin
assert_classify_stop "adb root" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" root
assert_classify_stop "reboot" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" reboot
assert_classify_stop "remount" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" remount
assert_classify_stop "pm clear" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell pm clear example.pkg
assert_classify_stop "am force-stop" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell am force-stop example.pkg
assert_classify_stop "am crash" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell am crash example.pkg
assert_classify_stop "settings put" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell settings put secure location_mode 3
assert_classify_stop "settings delete" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell settings delete secure mock_location
assert_classify_stop "appops set" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell appops set example.pkg android:mock_location allow
assert_classify_stop "appops reset" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell appops reset example.pkg
assert_classify_stop "global location toggle" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell cmd location set-location-enabled false
assert_classify_stop "test-provider mutation" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell cmd location providers add-test-provider gps
assert_classify_stop "sqlite UPDATE" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell sqlite3 /data/adb/lspd/config/modules_config.db \
  "UPDATE scope SET enabled=1"
assert_classify_stop "shell sh -c escape" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell sh -c id
assert_classify_stop "token appended to allowed getprop" STOP_UNLISTED_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell getprop ro.serialno unexpected-token
assert_classify_stop "authorized command with substituted serial" STOP_UNLISTED_COMMAND \
  -s OTHER_SERIAL shell getprop ro.serialno

# User and output columns are part of the frozen ps surface; mock-location
# AppOps is both user-0-bound and narrowed to one exact operation. The obsolete
# broad forms must be unlisted even though they look read-only.
assert_classify_allow "column-bounded process list" \
  -s "$AUTHORIZED_SERIAL" shell ps -A -o USER,PID,NAME
assert_classify_stop "broad process list" STOP_UNLISTED_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell ps -A
assert_classify_allow "user-0 exact mock-location AppOps read" \
  -s "$AUTHORIZED_SERIAL" shell appops get --user 0 \
  name.caiyao.fakegps android:mock_location
assert_classify_stop "broad package-wide AppOps read" STOP_UNLISTED_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell appops get name.caiyao.fakegps

# A transport/process failure is distinct from a successful-but-empty core
# value. Preserve its six-file carrier, including the real exit and stderr,
# and stop as evidence failure rather than laundering it into INCOMPLETE.
ADB_FAIL_OUT="$WORK/out-fingerprint-exit7"
run_collect fingerprint-exit7 "$AUTHORIZED_SERIAL" "$ADB_FAIL_OUT"
expect_stop "fingerprint adb rc=7 is an explicit read failure" "STOP_ADB_READ_FAILED"
expect_exit_code "fingerprint adb rc=7 uses evidence rc=21" 21
expect_only_authorized_target "fingerprint adb failure stays scoped"
if [ -f "$ADB_FAIL_OUT/manifest.json" ]; then
  assert_stop_manifest "$ADB_FAIL_OUT/manifest.json" STOP_ADB_READ_FAILED \
    "fingerprint adb failure manifest preserves the exact reason"
  assert_six_file_receipts "$ADB_FAIL_OUT/manifest.json" "$ADB_FAIL_OUT/receipts" \
    "fingerprint adb failure preserves strict six-file receipts"
else
  report fail "fingerprint adb failure manifest exists" "missing manifest.json"
fi
if [ "$(tr -d '\r\n' <"$ADB_FAIL_OUT/receipts/fingerprint.exit.txt" 2>/dev/null)" = 7 ]; then
  report ok "fingerprint receipt preserves adb exit=7"
else
  report fail "fingerprint receipt preserves adb exit=7" "exit receipt missing or changed"
fi
if grep -F -q "fixture fingerprint transport failure" \
    "$ADB_FAIL_OUT/receipts/fingerprint.stderr.bin" 2>/dev/null; then
  report ok "fingerprint receipt preserves adb stderr"
else
  report fail "fingerprint receipt preserves adb stderr" "fixture stderr missing"
fi

# Boot identity and monotonic uptime must be syntactically and numerically
# meaningful, not merely nonempty. Invalid UUIDs, negative/non-finite uptime,
# and a decreasing end sample all make the evidence incoherent.
boot_timing_cases=(
  "boot-id-malformed|STOP_INCOMPLETE_CORE_RECEIPT|malformed boot_id"
  "uptime-negative|STOP_INCOMPLETE_CORE_RECEIPT|negative uptime"
  "uptime-nonfinite|STOP_INCOMPLETE_CORE_RECEIPT|non-finite uptime"
  "uptime-decreased|STOP_BOOT_CHANGED|decreasing uptime bracket"
)
for boot_timing_case in "${boot_timing_cases[@]}"; do
  IFS='|' read -r boot_timing_scenario boot_timing_marker boot_timing_label \
    <<<"$boot_timing_case"
  boot_timing_out="$WORK/out-$boot_timing_scenario"
  run_collect "$boot_timing_scenario" "$AUTHORIZED_SERIAL" "$boot_timing_out"
  expect_stop "$boot_timing_label is refused" "$boot_timing_marker"
  expect_exit_code "$boot_timing_label uses evidence rc=21" 21
  expect_only_authorized_target "$boot_timing_label stays scoped"
  if [ -f "$boot_timing_out/manifest.json" ]; then
    assert_stop_manifest "$boot_timing_out/manifest.json" "$boot_timing_marker" \
      "$boot_timing_label manifest preserves the exact reason"
    assert_six_file_receipts "$boot_timing_out/manifest.json" "$boot_timing_out/receipts" \
      "$boot_timing_label preserves strict six-file receipts"
  else
    report fail "$boot_timing_label manifest exists" "missing manifest.json"
  fi
done

# A changed but individually valid boot id is a distinct reboot boundary.
BOOT_CHANGED_OUT="$WORK/out-boot-changed"
run_collect boot-changed "$AUTHORIZED_SERIAL" "$BOOT_CHANGED_OUT"
expect_stop "boot change across collection is refused" "STOP_BOOT_CHANGED"
expect_exit_code "boot change uses evidence rc=21" 21
expect_only_authorized_target "boot-change reads stay scoped"
assert_boot_brackets "$ADB_LOG" "boot-change path captures ordered start/end brackets"
if [ -f "$BOOT_CHANGED_OUT/manifest.json" ]; then
  assert_stop_manifest "$BOOT_CHANGED_OUT/manifest.json" STOP_BOOT_CHANGED \
    "boot-change manifest preserves STOP_BOOT_CHANGED"
  assert_six_file_receipts "$BOOT_CHANGED_OUT/manifest.json" "$BOOT_CHANGED_OUT/receipts" \
    "boot-change path preserves strict six-file receipts"
else
  report fail "boot-change manifest exists" "missing manifest.json"
fi

# 8. A core identity command that exits 0 with an empty fingerprint is still
# incomplete evidence. The run must remain STOP and must not mint a success
# receipt from a merely successful process status.
run_collect incomplete-core "$AUTHORIZED_SERIAL" "$WORK/out-incomplete"
expect_stop "empty core identity receipt is refused" "STOP_INCOMPLETE_CORE_RECEIPT"
expect_exit_code "empty core identity uses evidence rc=21" 21
expect_only_authorized_target "incomplete-core adb surface stays scoped"
if [ -d "$WORK/out-incomplete" ]; then
  mode="$(file_mode "$WORK/out-incomplete")"
  if [ "$mode" = 700 ]; then
    report ok "partial evidence directory stays mode 0700"
  else
    report fail "partial evidence directory stays mode 0700" "mode=$mode"
  fi
  if "$PYTHON_BIN" -I - "$WORK/out-incomplete/manifest.json" <<'PY' >/dev/null 2>&1
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)
assert manifest["status"] == "STOP"
assert manifest["reason"] == "STOP_INCOMPLETE_CORE_RECEIPT"
PY
  then
    report ok "incomplete core emits machine-readable STOP manifest"
  else
    report fail "incomplete core emits machine-readable STOP manifest" "missing/invalid manifest.json"
  fi
else
  report fail "incomplete core preserves partial evidence" "output directory missing"
fi

printf 'issue66 Moto read-only collector selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

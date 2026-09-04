#!/usr/bin/env bash
# Operational-read-only, exact-device preflight for issue #66.
#
# This first gate binds all device reads to the sole authorized Moto serial,
# refuses ambiguous inventories, writes a STOP manifest before any device
# evidence is interpreted, and routes every adb invocation through one exact
# allowlist. It never installs, clears, stops, configures, registers, restarts,
# reboots, or toggles device state.

set -uo pipefail
umask 077

AUTHORIZED_SERIAL="ZY22JHW9M4"
EXPECTED_MANUFACTURER="motorola"
EXPECTED_API="35"
KNOWN_PACKAGES=(
  "name.caiyao.fakegps"
  "name.caiyao.fakegps.bench"
  "name.caiyao.fakegps.codexbench"
  "com.example.cellrebelauto"
  "com.example.cellrebelauto.codexbench"
  "com.cellrebel.mobile"
)
PACKAGE_STATUSES=(
  "NOT_COLLECTED"
  "NOT_COLLECTED"
  "NOT_COLLECTED"
  "NOT_COLLECTED"
  "NOT_COLLECTED"
  "NOT_COLLECTED"
)
PACKAGE_APK_SHA256=("" "" "" "" "" "")
SERVICES_JAR_SHA256=""

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SELF_DIR/.." && pwd -P)"
COLLECTOR_PATH="$SELF_DIR/collect-issue66-moto-readonly-preflight.sh"
PYTHON_BIN="/usr/bin/python3"
ADB_ALLOWLIST_PATH="$SELF_DIR/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
ADB_ALLOWLIST_EXPECTED_SHA256="a52061a3a5410b7fea4703ae51c20e3525f1c4d467c36155f0d556100a63930e"

ADB_BIN=""
ADB_SOURCE_PATH=""
ADB_SOURCE_IDENTITY=""
ADB_SOURCE_SHA256=""
ADB_APPROVAL_LANE=""
ADB_APPROVAL_LABEL=""
ADB_CLIENT_TRUST=""
REQUESTED_SERIAL=""
OUTPUT_DIR=""
OUTPUT_DISPLAY_PATH=""
OUTPUT_IDENTITY=""
CLASSIFY_ONLY=0
SELFTEST_FIXTURE=0
CLASSIFY_ARGV=()
VERIFY_DIR=""
EVIDENCE_READY=0
LAST_STDOUT=""
LAST_RC=0
ADB_SHA256=""
ADB_SNAPSHOT_IDENTITY=""
COLLECTOR_SHA256=""
RECEIPT_TREE_SHA256=""

usage() {
  cat >&2 <<'EOF'
usage:
  collect-issue66-moto-readonly-preflight.sh \
    --adb <absolute-path> --serial ZY22JHW9M4 --output <new-absolute-directory>
  collect-issue66-moto-readonly-preflight.sh \
    --adb <absolute-path> --classify-adb -- <adb arguments...>
  collect-issue66-moto-readonly-preflight.sh \
    --verify-receipts <absolute-evidence-directory>

The internal --selftest-fixture flag selects the repo-pinned fake-ADB lane and
may be used only by the host selftest, including its receipt verifier calls.
EOF
}

json_escape() {
  local value=${1-}
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\n'/\\n}
  value=${value//$'\r'/\\r}
  value=${value//$'\t'/\\t}
  printf '%s' "$value"
}

sha256_file() { # regular file
  "$PYTHON_BIN" -I - "$1" <<'PY'
import hashlib
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
with path.open("rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
print(digest.hexdigest())
PY
}

sha256_receipt_tree() { # flat receipts directory, deterministic name+byte binding
  "$PYTHON_BIN" -I - "$1" <<'PY'
import hashlib
import pathlib
import stat
import struct
import sys

root = pathlib.Path(sys.argv[1])
digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
entries = sorted(root.iterdir(), key=lambda path: path.name.encode("utf-8"))
if not entries:
    raise SystemExit(1)
for path in entries:
    value = path.lstat()
    if not stat.S_ISREG(value.st_mode) or path.is_symlink():
        raise SystemExit(1)
    name = path.name.encode("utf-8")
    data = path.read_bytes()
    digest.update(struct.pack(">Q", len(name)))
    digest.update(name)
    digest.update(struct.pack(">Q", len(data)))
    digest.update(data)
print(digest.hexdigest())
PY
}

render_manifest() { # status reason destination
  local status=$1 reason=$2 destination=$3
  (( EVIDENCE_READY )) || return 0
  local stems_json="" stem separator=""
  local packages_json="" package_hashes_json="" package package_status package_hash i
  local collection_status="STOP"
  [[ $status == COLLECTED ]] && collection_status="COLLECTED"
  if [[ -f $OUTPUT_DIR/receipts/stems.txt ]]; then
    while IFS= read -r stem; do
      [[ -n $stem ]] || continue
      stems_json+="$separator\"$(json_escape "$stem")\""
      separator=,
    done <"$OUTPUT_DIR/receipts/stems.txt"
  fi
  separator=""
  for ((i = 0; i < ${#KNOWN_PACKAGES[@]}; i++)); do
    package=${KNOWN_PACKAGES[i]}
    package_status=${PACKAGE_STATUSES[i]}
    packages_json+="$separator\"$(json_escape "$package")\":\"$(json_escape "$package_status")\""
    separator=,
  done
  separator=""
  for ((i = 0; i < ${#KNOWN_PACKAGES[@]}; i++)); do
    [[ ${PACKAGE_STATUSES[i]} == INSTALLED && -n ${PACKAGE_APK_SHA256[i]} ]] || continue
    package=${KNOWN_PACKAGES[i]}
    package_hash=${PACKAGE_APK_SHA256[i]}
    package_hashes_json+="$separator\"$(json_escape "$package")\":\"$(json_escape "$package_hash")\""
    separator=,
  done
  if ! printf '{"schemaVersion":2,"mode":"READ_ONLY_PREFLIGHT","readOnlySemantics":"OPERATIONAL_NOT_BIT_FOR_BIT","incidentalEffects":["ADB_TRANSPORT","TRANSIENT_QUERY_PROCESSES","DEVICE_AUDIT_ACCOUNTING"],"adbServerTrust":"DEFAULT_LOCAL_ENDPOINT_NOT_ATTESTED__INHERITED_ROUTING_REJECTED","adbClientTrust":"%s","adbApprovalLane":"%s","adbApprovalLabel":"%s","adbAllowlistSha256":"%s","adbSnapshotPath":"tooling/adb","status":"%s","terminalStatus":"%s","reason":"%s","collectionStatus":"%s","compatibility":"STATIC_ANALYSIS_PENDING","privilegedInspection":"NOT_COLLECTED_PRIVILEGED","coordinateCaptured":false,"authorizedSerial":"%s","targetSerial":"%s","devicePass":false,"issue66Ac7":"NOT_PASSED","deviceFull":"BLOCKED","durableAck":"NOT_CREATED","fullClaim":"NOT_CREATED","adbSha256":"%s","collectorSha256":"%s","receiptTreeSha256":"%s","knownPackages":{%s},"servicesJarSha256":"%s","packageApkSha256":{%s},"receiptStems":[%s]}\n' \
    "$(json_escape "$ADB_CLIENT_TRUST")" \
    "$(json_escape "$ADB_APPROVAL_LANE")" \
    "$(json_escape "$ADB_APPROVAL_LABEL")" \
    "$ADB_ALLOWLIST_EXPECTED_SHA256" \
    "$(json_escape "$status")" \
    "$(json_escape "$reason")" \
    "$(json_escape "$reason")" \
    "$collection_status" \
    "$AUTHORIZED_SERIAL" \
    "$AUTHORIZED_SERIAL" \
    "$(json_escape "$ADB_SHA256")" \
    "$(json_escape "$COLLECTOR_SHA256")" \
    "$(json_escape "$RECEIPT_TREE_SHA256")" \
    "$packages_json" \
    "$(json_escape "$SERVICES_JAR_SHA256")" \
    "$package_hashes_json" \
    "$stems_json" >"$destination"; then
    return 1
  fi
}

write_manifest() { # status reason
  local status=$1 reason=$2 tmp="$OUTPUT_DIR/.manifest.json.tmp"
  (( EVIDENCE_READY )) || return 0
  render_manifest "$status" "$reason" "$tmp" || return 1
  [[ ! -d $OUTPUT_DIR/manifest.json && ! -L $OUTPUT_DIR/manifest.json ]] || return 1
  mv -f "$tmp" "$OUTPUT_DIR/manifest.json" || return 1
}

write_redacted_summary() { # manifest source
  local manifest_source=$1
  local tmp="$OUTPUT_DIR/.summary.json.tmp"
  "$PYTHON_BIN" -I - "$manifest_source" "$tmp" <<'PY' \
    || stop_now STOP_INTERNAL_SUMMARY_WRITE
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
output_path = pathlib.Path(sys.argv[2])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
keys = (
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
    "coordinateCaptured",
    "authorizedSerial",
    "targetSerial",
    "privilegedInspection",
    "devicePass",
    "issue66Ac7",
    "deviceFull",
    "durableAck",
    "fullClaim",
    "knownPackages",
    "servicesJarSha256",
    "packageApkSha256",
    "adbSha256",
    "collectorSha256",
    "receiptTreeSha256",
)
summary = {key: manifest[key] for key in keys}
summary["redacted"] = True
summary["receiptCount"] = len(manifest["receiptStems"])
output_path.write_text(
    json.dumps(summary, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
  mv -f "$tmp" "$OUTPUT_DIR/summary.json" \
    || stop_now STOP_INTERNAL_SUMMARY_WRITE
}

publish_collected_bundle() {
  local final_manifest="$OUTPUT_DIR/.manifest.final.json.tmp"
  output_binding_intact || stop_now STOP_OUTPUT_CHANGED
  render_manifest "COLLECTED" \
    "PUBLIC_STATIC_EVIDENCE_COLLECTED__STATIC_ANALYSIS_PENDING" \
    "$final_manifest" || fatal_manifest_write
  write_redacted_summary "$final_manifest"
  output_binding_intact || stop_now STOP_OUTPUT_CHANGED
  [[ ! -d $OUTPUT_DIR/manifest.json && ! -L $OUTPUT_DIR/manifest.json ]] \
    || fatal_manifest_write
  mv -f "$final_manifest" "$OUTPUT_DIR/manifest.json" || fatal_manifest_write
}

fatal_manifest_write() {
  printf 'STOP_INTERNAL_MANIFEST_WRITE\n' >&2
  exit 70
}

stop_now() { # exact marker
  local marker=$1
  write_manifest "STOP" "$marker" || fatal_manifest_write
  printf '%s\n' "$marker" >&2
  case "$marker" in
    STOP_MISSING_TARGET|STOP_EXTRA_DEVICE|STOP_WRONG_SERIAL|\
    STOP_WRONG_MANUFACTURER|STOP_WRONG_API|\
    STOP_UNPRIVILEGED_SHELL_REQUIRED|STOP_UNSUPPORTED_USER_0_REQUIRED) exit 20 ;;
    STOP_INCOMPLETE_CORE_RECEIPT|STOP_INCOMPLETE_RECEIPT|\
    STOP_ADB_READ_FAILED|STOP_FRAMEWORK_READ_FAILED|STOP_APK_READ_FAILED|\
    STOP_PACKAGE_OBSERVATION_MALFORMED|STOP_BOOT_CHANGED) exit 21 ;;
    STOP_INTERNAL_*) exit 70 ;;
    *) exit 22 ;;
  esac
}

argv_eq() { # actual argv in CLASSIFY_ARGV, expected argv as parameters
  local expected=("$@") i
  (( ${#CLASSIFY_ARGV[@]} == ${#expected[@]} )) || return 1
  for ((i = 0; i < ${#expected[@]}; i++)); do
    [[ ${CLASSIFY_ARGV[i]} == "${expected[i]}" ]] || return 1
  done
}

is_mutating_argv() {
  local joined=" ${CLASSIFY_ARGV[*]} "
  case "$joined" in
    *" install "*|*" install-multiple "*|*" uninstall "*|*" push "*|*" pull "*) return 0 ;;
    *" root "*|*" reboot "*|*" remount "*|*" disable-verity "*|*" enable-verity "*) return 0 ;;
    *" settings put "*|*" settings delete "*|*" appops set "*|*" appops reset "*) return 0 ;;
    *" am force-stop "*|*" am crash "*|*" pm clear "*|*" pm grant "*|*" pm revoke "*) return 0 ;;
    *" logcat -c "*|*" set-location-enabled "*|*" add-test-provider "*|*" set-test-provider "*) return 0 ;;
    *" shell stop "*|*" shell start "*|*" shell kill "*|*" shell pkill "*|*" shell sqlite3 "*) return 0 ;;
    *" shell sh -c "*|*" shell su "*|*" shell am start "*) return 0 ;;
  esac
  return 1
}

is_known_package() { # package
  local candidate=$1 package
  for package in "${KNOWN_PACKAGES[@]}"; do
    [[ $candidate == "$package" ]] && return 0
  done
  return 1
}

package_index() { # package; prints a decimal index
  local candidate=$1 i
  for ((i = 0; i < ${#KNOWN_PACKAGES[@]}; i++)); do
    if [[ ${KNOWN_PACKAGES[i]} == "$candidate" ]]; then
      printf '%d' "$i"
      return 0
    fi
  done
  return 1
}

is_safe_package_apk_path() { # exact base/split path [expected package]
  local path=$1 expected_package=${2-} relative first_segment remainder app_segment leaf
  [[ $path =~ ^/data/app/[A-Za-z0-9._+=~-]+/[A-Za-z0-9._+=~-]+/[A-Za-z0-9._+=~-]+\.apk$ ]] \
    || return 1
  relative=${path#/data/app/}
  first_segment=${relative%%/*}
  remainder=${relative#*/}
  app_segment=${remainder%%/*}
  leaf=${remainder#*/}
  [[ -n $first_segment && $first_segment != . && $first_segment != .. ]] || return 1
  [[ $app_segment != . && $app_segment != .. ]] || return 1
  [[ $leaf == base.apk || $leaf =~ ^split_[A-Za-z0-9._+=~-]+\.apk$ ]] || return 1
  if [[ -n $expected_package ]]; then
    is_known_package "$expected_package" || return 1
    [[ $app_segment == "$expected_package"-* ]] || return 1
    return 0
  fi
  local package
  for package in "${KNOWN_PACKAGES[@]}"; do
    [[ $app_segment == "$package"-* ]] && return 0
  done
  return 1
}

is_safe_apk_path() { # exact base.apk path [expected package]
  local path=$1 expected_package=${2-}
  [[ $path == */base.apk ]] || return 1
  is_safe_package_apk_path "$path" "$expected_package"
}

select_base_apk_path() { # pm path stdout file, expected package
  "$PYTHON_BIN" -I - "$1" "$2" <<'PY'
import pathlib
import re
import sys

try:
    text = pathlib.Path(sys.argv[1]).read_bytes().decode("utf-8")
except (OSError, UnicodeDecodeError):
    raise SystemExit(1)
if "\x00" in text or not text.endswith("\n"):
    raise SystemExit(1)
text = text.replace("\r\n", "\n")
if "\r" in text:
    raise SystemExit(1)
lines = text[:-1].split("\n")
pattern = re.compile(
    r"/data/app/([A-Za-z0-9._+=~-]+)/([A-Za-z0-9._+=~-]+)/"
    r"(base|split_[A-Za-z0-9._+=~-]+)\.apk"
)
paths = []
for line in lines:
    if not line.startswith("package:"):
        raise SystemExit(1)
    path = line[len("package:"):]
    match = pattern.fullmatch(path)
    if (
        not match
        or match.group(1) in {".", ".."}
        or match.group(2) in {".", ".."}
        or not match.group(2).startswith(sys.argv[2] + "-")
    ):
        raise SystemExit(1)
    paths.append((path, match.group(3)))
if not paths or len({path for path, _ in paths}) != len(paths):
    raise SystemExit(1)
bases = [path for path, leaf in paths if leaf == "base"]
if len(bases) != 1:
    raise SystemExit(1)
sys.stdout.write(bases[0])
PY
}

valid_pidof_file() { # exact one-line decimal PID list
  local value
  value="$(read_scalar_receipt "$1")" || return 1
  [[ $value =~ ^[0-9]+([[:space:]][0-9]+)*$ ]]
}

classify_adb_argv() {
  is_mutating_argv && return 2

  argv_eq devices -l && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" get-state && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" shell cat /proc/sys/kernel/random/boot_id && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" shell cat /proc/uptime && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" shell id && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" shell getenforce && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" shell am get-current-user && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" shell ps -A -o USER,PID,NAME && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" shell cmd location is-location-enabled --user 0 && return 0
  argv_eq -s "$AUTHORIZED_SERIAL" exec-out cat /system/framework/services.jar && return 0

  if (( ${#CLASSIFY_ARGV[@]} == 5 )) \
      && [[ ${CLASSIFY_ARGV[0]} == -s ]] \
      && [[ ${CLASSIFY_ARGV[1]} == "$AUTHORIZED_SERIAL" ]] \
      && [[ ${CLASSIFY_ARGV[2]} == shell ]] \
      && [[ ${CLASSIFY_ARGV[3]} == getprop ]]; then
    case "${CLASSIFY_ARGV[4]}" in
      ro.serialno|ro.product.manufacturer|ro.build.version.sdk|ro.build.fingerprint|\
      ro.product.model|ro.product.device|ro.product.cpu.abilist|ro.zygote|\
      ro.build.version.release|sys.boot_completed) return 0 ;;
    esac
  fi

  if (( ${#CLASSIFY_ARGV[@]} == 6 )) \
      && [[ ${CLASSIFY_ARGV[0]} == -s ]] \
      && [[ ${CLASSIFY_ARGV[1]} == "$AUTHORIZED_SERIAL" ]] \
      && [[ ${CLASSIFY_ARGV[2]} == shell ]]; then
    if [[ ${CLASSIFY_ARGV[3]} == pm && ${CLASSIFY_ARGV[4]} == path ]] \
        || [[ ${CLASSIFY_ARGV[3]} == dumpsys && ${CLASSIFY_ARGV[4]} == package ]]; then
      is_known_package "${CLASSIFY_ARGV[5]}" && return 0
    fi
  fi

  if (( ${#CLASSIFY_ARGV[@]} == 9 )) \
      && [[ ${CLASSIFY_ARGV[0]} == -s ]] \
      && [[ ${CLASSIFY_ARGV[1]} == "$AUTHORIZED_SERIAL" ]] \
      && [[ ${CLASSIFY_ARGV[2]} == shell ]] \
      && [[ ${CLASSIFY_ARGV[3]} == appops ]] \
      && [[ ${CLASSIFY_ARGV[4]} == get ]] \
      && [[ ${CLASSIFY_ARGV[5]} == --user ]] \
      && [[ ${CLASSIFY_ARGV[6]} == 0 ]] \
      && is_known_package "${CLASSIFY_ARGV[7]}" \
      && [[ ${CLASSIFY_ARGV[8]} == android:mock_location ]]; then
    return 0
  fi

  if (( ${#CLASSIFY_ARGV[@]} == 5 )) \
      && [[ ${CLASSIFY_ARGV[0]} == -s ]] \
      && [[ ${CLASSIFY_ARGV[1]} == "$AUTHORIZED_SERIAL" ]] \
      && [[ ${CLASSIFY_ARGV[2]} == shell ]] \
      && [[ ${CLASSIFY_ARGV[3]} == pidof ]] \
      && is_known_package "${CLASSIFY_ARGV[4]}"; then
    return 0
  fi

  if (( ${#CLASSIFY_ARGV[@]} == 5 )) \
      && [[ ${CLASSIFY_ARGV[0]} == -s ]] \
      && [[ ${CLASSIFY_ARGV[1]} == "$AUTHORIZED_SERIAL" ]] \
      && [[ ${CLASSIFY_ARGV[2]} == exec-out ]] \
      && [[ ${CLASSIFY_ARGV[3]} == cat ]] \
      && is_safe_apk_path "${CLASSIFY_ARGV[4]}"; then
    return 0
  fi

  return 1
}

validate_adb_binary() {
  [[ $ADB_BIN == /* ]] || stop_now STOP_INVALID_ADB_BINARY
  [[ $ADB_BIN != *$'\n'* && $ADB_BIN != *$'\r'* && $ADB_BIN != *$'\t'* ]] \
    || stop_now STOP_INVALID_ADB_BINARY
  [[ -f $ADB_BIN && -x $ADB_BIN && ! -L $ADB_BIN ]] \
    || stop_now STOP_INVALID_ADB_BINARY
  local validated
  validated="$("$PYTHON_BIN" -I - "$ADB_BIN" <<'PY'
import os
import pathlib
import stat
import sys
import hashlib

try:
    source = pathlib.Path(sys.argv[1]).resolve(strict=True)
    if any(separator in str(source) for separator in ("\n", "\r", "\t")):
        raise OSError("unsafe source pathname")
    before = source.lstat()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(source, flags)
    try:
        opened = os.fstat(descriptor)
        digest = hashlib.sha256()
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
        opened_after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    after = source.lstat()
except (OSError, RuntimeError):
    raise SystemExit(1)

def identity(value):
    return (
        value.st_dev, value.st_ino, value.st_size,
        value.st_mtime_ns, value.st_ctime_ns, stat.S_IMODE(value.st_mode),
    )

if (
    not stat.S_ISREG(before.st_mode)
    or identity(before) != identity(opened)
    or identity(before) != identity(opened_after)
    or identity(before) != identity(after)
    or not os.access(source, os.X_OK)
):
    raise SystemExit(1)
print(
    f"{source}\t"
    + ":".join(str(item) for item in identity(before))
    + f"\t{digest.hexdigest()}"
)
PY
  )" || stop_now STOP_INVALID_ADB_BINARY
  IFS=$'\t' read -r ADB_SOURCE_PATH ADB_SOURCE_IDENTITY ADB_SOURCE_SHA256 \
    <<<"$validated"
  [[ $ADB_SOURCE_PATH == /* && -f $ADB_SOURCE_PATH && -x $ADB_SOURCE_PATH \
      && ! -L $ADB_SOURCE_PATH && -n $ADB_SOURCE_IDENTITY \
      && $ADB_SOURCE_SHA256 =~ ^[0-9a-f]{64}$ ]] \
    || stop_now STOP_INVALID_ADB_BINARY
}

select_adb_approval_lane() {
  if (( SELFTEST_FIXTURE )); then
    ADB_APPROVAL_LANE="SELFTEST"
    ADB_CLIENT_TRUST="SELFTEST_FIXTURE_ONLY__NOT_DEVICE_EVIDENCE"
  else
    ADB_APPROVAL_LANE="PRODUCTION"
    ADB_CLIENT_TRUST="REPO_PINNED_SHA256_PRODUCTION"
  fi
}

validate_adb_approval() {
  local label approval_rc
  label="$("$PYTHON_BIN" -I - \
    "$ADB_ALLOWLIST_PATH" \
    "$ADB_ALLOWLIST_EXPECTED_SHA256" \
    "$ADB_SOURCE_SHA256" \
    "$ADB_APPROVAL_LANE" <<'PY'
import hashlib
import os
import pathlib
import re
import stat
import sys

path = pathlib.Path(sys.argv[1])
expected_allowlist_digest = sys.argv[2]
source_digest = sys.argv[3]
expected_lane = sys.argv[4]

def internal_failure():
    raise SystemExit(70)

def identity(value):
    return (
        value.st_dev, value.st_ino, value.st_size,
        value.st_mtime_ns, value.st_ctime_ns, stat.S_IMODE(value.st_mode),
    )

try:
    named_before = path.lstat()
    if stat.S_ISLNK(named_before.st_mode) or not stat.S_ISREG(named_before.st_mode):
        raise OSError("allowlist is not a regular file")
    if named_before.st_uid != os.geteuid() or stat.S_IMODE(named_before.st_mode) & 0o022:
        raise OSError("allowlist ownership/mode is unsafe")
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened_before = os.fstat(descriptor)
        chunks = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        opened_after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    named_after = path.lstat()
except OSError:
    internal_failure()

if not (
    identity(named_before) == identity(opened_before)
    == identity(opened_after) == identity(named_after)
):
    internal_failure()
data = b"".join(chunks)
if hashlib.sha256(data).hexdigest() != expected_allowlist_digest:
    internal_failure()
try:
    text = data.decode("ascii")
except UnicodeDecodeError:
    internal_failure()
if "\x00" in text or "\r" in text or not text.endswith("\n"):
    internal_failure()

rows = []
for line in text.splitlines():
    if line.startswith("#"):
        continue
    if not line:
        internal_failure()
    fields = line.split("\t")
    if len(fields) != 3:
        internal_failure()
    lane, label, digest = fields
    if lane not in {"PRODUCTION", "SELFTEST"}:
        internal_failure()
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,95}", label):
        internal_failure()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        internal_failure()
    rows.append((lane, label, digest))
if (
    not rows
    or len({row[1] for row in rows}) != len(rows)
    or len({row[2] for row in rows}) != len(rows)
):
    internal_failure()
matches = [row for row in rows if row[0] == expected_lane and row[2] == source_digest]
if len(matches) != 1:
    raise SystemExit(22)
print(matches[0][1])
PY
  )"
  approval_rc=$?
  case "$approval_rc" in
    0) ;;
    22) stop_now STOP_ADB_CLIENT_UNAPPROVED ;;
    *) stop_now STOP_INTERNAL_ADB_ALLOWLIST ;;
  esac
  [[ $label =~ ^[a-z0-9][a-z0-9._-]{0,95}$ ]] \
    || stop_now STOP_ADB_CLIENT_UNAPPROVED
  ADB_APPROVAL_LABEL=$label
}

create_output_dir() { # securely create and print canonical path + inode identity
  "$PYTHON_BIN" -I - "$OUTPUT_DIR" "$REPO_ROOT" <<'PY'
import os
import pathlib
import stat
import subprocess
import sys

raw_output = pathlib.Path(sys.argv[1])
repo = pathlib.Path(sys.argv[2])
if not raw_output.is_absolute() or raw_output.name in {"", ".", ".."}:
    raise SystemExit(1)
if any(separator in str(raw_output) for separator in ("\n", "\r", "\t")):
    raise SystemExit(1)
try:
    raw_output.lstat()
except FileNotFoundError:
    pass
except OSError:
    raise SystemExit(1)
else:
    raise SystemExit(1)

raw_parent = raw_output.parent
try:
    physical_parent = pathlib.Path(os.path.realpath(raw_parent, strict=True))
except (OSError, RuntimeError, TypeError):
    # Python versions without realpath(strict=...) still receive a strict
    # existence/type check below before any directory is created.
    try:
        physical_parent = pathlib.Path(os.path.realpath(raw_parent))
        if not physical_parent.is_dir():
            raise OSError("output parent is not a directory")
    except (OSError, RuntimeError):
        raise SystemExit(1)
candidate = physical_parent / raw_output.name

directory_flags = (
    os.O_RDONLY | os.O_DIRECTORY
    | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
)

def open_physical_directory(path):
    descriptor = os.open("/", directory_flags)
    try:
        for component in path.parts[1:]:
            next_descriptor = os.open(component, directory_flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = next_descriptor
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise

def has_extended_acl(path):
    """Reject access grants that POSIX mode bits do not disclose."""
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except (AttributeError, OSError):
        attributes = ()
    if any(
        attribute in {"system.posix_acl_access", "system.posix_acl_default"}
        for attribute in attributes
    ):
        return True
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/bin/ls", "-lde", str(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
            check=False,
        )
        if result.returncode != 0 or not result.stdout.splitlines():
            raise OSError("cannot inspect output ACL")
        output_lines = result.stdout.splitlines()
        mode_token = output_lines[0].split(maxsplit=1)[0]
        acl_rows = [
            line for line in output_lines[1:]
            if line.lstrip().split(":", 1)[0].isdigit()
        ]
        return "+" in mode_token or bool(acl_rows)
    return False

def descriptor_has_extended_acl(descriptor):
    original = os.open(".", directory_flags)
    try:
        os.fchdir(descriptor)
        return has_extended_acl(pathlib.Path("."))
    finally:
        os.fchdir(original)
        os.close(original)

def directory_identity(path):
    value = path.stat()
    if not stat.S_ISDIR(value.st_mode):
        raise OSError(f"forbidden Git path is not a directory: {path}")
    return value.st_dev, value.st_ino

def ancestry_intersects(descriptor, forbidden_identities):
    cursor = os.dup(descriptor)
    try:
        while True:
            current = os.fstat(cursor)
            current_identity = (current.st_dev, current.st_ino)
            if current_identity in forbidden_identities:
                return True
            parent_cursor = os.open("..", directory_flags, dir_fd=cursor)
            parent_value = os.fstat(parent_cursor)
            parent_identity = (parent_value.st_dev, parent_value.st_ino)
            if parent_identity == current_identity:
                os.close(parent_cursor)
                return False
            os.close(cursor)
            cursor = parent_cursor
    finally:
        os.close(cursor)

parent = -1
child = -1
created = False
try:
    parent = open_physical_directory(physical_parent)
    parent_stat = os.fstat(parent)
    if not stat.S_ISDIR(parent_stat.st_mode):
        raise OSError("output parent descriptor is not a directory")
    resolved_again = pathlib.Path(os.path.realpath(raw_parent))
    named_parent = resolved_again.stat()
    if (
        resolved_again != physical_parent
        or (named_parent.st_dev, named_parent.st_ino)
            != (parent_stat.st_dev, parent_stat.st_ino)
    ):
        raise OSError("output parent changed during validation")
    mode = stat.S_IMODE(parent_stat.st_mode)
    if parent_stat.st_uid != os.geteuid() and not mode & stat.S_ISVTX:
        raise OSError("output parent has an unsafe owner")
    if mode & 0o022 and not mode & stat.S_ISVTX:
        raise OSError("output parent is writable without sticky protection")

    git_env = {
        key: value for key, value in os.environ.items()
        if not key.startswith("GIT_")
    }
    common = pathlib.Path(subprocess.check_output(
        ["git", "-C", str(repo), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        text=True,
        env=git_env,
    ).strip()).resolve()
    listing = subprocess.check_output(
        ["git", "-C", str(repo), "worktree", "list", "--porcelain"],
        text=True,
        env=git_env,
    )
    forbidden_paths = [repo.resolve(), common] + [
        pathlib.Path(line[len("worktree "):]).resolve()
        for line in listing.splitlines()
        if line.startswith("worktree ")
    ]
    forbidden_identities = {directory_identity(path) for path in forbidden_paths}
    if ancestry_intersects(parent, forbidden_identities):
        raise OSError("output would be inside a linked worktree or common Git directory")

    os.mkdir(raw_output.name, mode=0o700, dir_fd=parent)
    created = True
    child = os.open(raw_output.name, directory_flags, dir_fd=parent)
    child_stat = os.fstat(child)
    if (
        not stat.S_ISDIR(child_stat.st_mode)
        or child_stat.st_uid != os.geteuid()
        or stat.S_IMODE(child_stat.st_mode) != 0o700
        or descriptor_has_extended_acl(child)
    ):
        raise OSError("created output directory is unsafe")
    # The display pathname must still identify the directory created beneath
    # the pinned parent before the shell can enter it and write any evidence.
    named_child = candidate.lstat()
    if (
        stat.S_ISLNK(named_child.st_mode)
        or (named_child.st_dev, named_child.st_ino)
            != (child_stat.st_dev, child_stat.st_ino)
    ):
        raise OSError("created output pathname changed")
except (OSError, subprocess.CalledProcessError):
    if created:
        try:
            os.rmdir(raw_output.name, dir_fd=parent)
        except OSError:
            pass
    raise SystemExit(1)
finally:
    if child >= 0:
        os.close(child)
    if parent >= 0:
        os.close(parent)
print(f"{candidate}\t{child_stat.st_dev}:{child_stat.st_ino}")
PY
}

path_identity() { # existing path
  "$PYTHON_BIN" -I - "$1" <<'PY'
import os
import pathlib
import stat
import sys

value = os.lstat(pathlib.Path(sys.argv[1]))
if not stat.S_ISDIR(value.st_mode) or stat.S_ISLNK(value.st_mode):
    raise SystemExit(1)
print(f"{value.st_dev}:{value.st_ino}")
PY
}

output_binding_intact() {
  [[ -n $OUTPUT_DISPLAY_PATH && -n $OUTPUT_IDENTITY ]] || return 1
  "$PYTHON_BIN" -I - "$OUTPUT_DISPLAY_PATH" "$OUTPUT_IDENTITY" <<'PY' >/dev/null 2>&1
import os
import pathlib
import stat
import subprocess
import sys

expected = sys.argv[2]

def has_extended_acl(path):
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except (AttributeError, OSError):
        attributes = ()
    if any(
        attribute in {"system.posix_acl_access", "system.posix_acl_default"}
        for attribute in attributes
    ):
        return True
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/bin/ls", "-lde", str(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
            check=False,
        )
        if result.returncode != 0 or not result.stdout.splitlines():
            return True
        output_lines = result.stdout.splitlines()
        mode_token = output_lines[0].split(maxsplit=1)[0]
        return "+" in mode_token or any(
            line.lstrip().split(":", 1)[0].isdigit() for line in output_lines[1:]
        )
    return False

try:
    named = os.lstat(pathlib.Path(sys.argv[1]))
    current = os.stat(".")
except OSError:
    raise SystemExit(1)
if (
    stat.S_ISLNK(named.st_mode)
    or not stat.S_ISDIR(named.st_mode)
    or f"{named.st_dev}:{named.st_ino}" != expected
    or (named.st_dev, named.st_ino) != (current.st_dev, current.st_ino)
    or stat.S_IMODE(named.st_mode) != 0o700
    or named.st_uid != os.geteuid()
    or has_extended_acl(pathlib.Path("."))
):
    raise SystemExit(1)
PY
}

snapshot_adb_binary() {
  mkdir -m 700 tooling || return 1
  ADB_SHA256="$("$PYTHON_BIN" -I - "$ADB_SOURCE_PATH" "$ADB_SOURCE_IDENTITY" tooling/.adb.tmp <<'PY'
import hashlib
import os
import stat
import sys

source_path, expected_identity, destination_path = sys.argv[1:]

def identity(value):
    return ":".join(str(item) for item in (
        value.st_dev, value.st_ino, value.st_size,
        value.st_mtime_ns, value.st_ctime_ns, stat.S_IMODE(value.st_mode),
    ))

source_flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
destination_flags = (
    os.O_WRONLY | os.O_CREAT | os.O_EXCL
    | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
)
try:
    source = os.open(source_path, source_flags)
    destination = -1
    try:
        before = os.fstat(source)
        if not stat.S_ISREG(before.st_mode) or identity(before) != expected_identity:
            raise OSError("adb source identity changed before snapshot")
        destination = os.open(destination_path, destination_flags, 0o500)
        digest = hashlib.sha256()
        while True:
            chunk = os.read(source, 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            view = memoryview(chunk)
            while view:
                written = os.write(destination, view)
                if written <= 0:
                    raise OSError("short adb snapshot write")
                view = view[written:]
        os.fchmod(destination, 0o500)
        os.fsync(destination)
        after = os.fstat(source)
        if identity(after) != expected_identity:
            raise OSError("adb source changed while snapshotting")
    finally:
        if destination >= 0:
            os.close(destination)
        os.close(source)
except OSError:
    raise SystemExit(1)
print(digest.hexdigest())
PY
  )" || return 1
  [[ $ADB_SHA256 =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ $ADB_SHA256 == "$ADB_SOURCE_SHA256" ]] || return 1
  [[ -f tooling/.adb.tmp && -x tooling/.adb.tmp && ! -L tooling/.adb.tmp ]] || return 1
  mv -f tooling/.adb.tmp tooling/adb || return 1
  [[ -f tooling/adb && -x tooling/adb && ! -L tooling/adb ]] || return 1
  [[ $(sha256_file tooling/adb) == "$ADB_SHA256" ]] || return 1
  ADB_SNAPSHOT_IDENTITY="$("$PYTHON_BIN" -I - tooling/adb <<'PY'
import os
import pathlib
import stat
import sys

value = os.lstat(pathlib.Path(sys.argv[1]))
if not stat.S_ISREG(value.st_mode) or stat.S_ISLNK(value.st_mode):
    raise SystemExit(1)
print(f"{value.st_dev}:{value.st_ino}:{value.st_size}")
PY
  )" || return 1
  chmod 500 tooling || return 1
  ADB_BIN=./tooling/adb
}

adb_snapshot_intact() {
  [[ -n $ADB_SHA256 && -n $ADB_SNAPSHOT_IDENTITY ]] || return 1
  "$PYTHON_BIN" -I - tooling tooling/adb "$ADB_SNAPSHOT_IDENTITY" "$ADB_SHA256" <<'PY' >/dev/null 2>&1
import hashlib
import os
import pathlib
import stat
import sys

directory = os.lstat(pathlib.Path(sys.argv[1]))
binary = os.lstat(pathlib.Path(sys.argv[2]))
if (
    not stat.S_ISDIR(directory.st_mode)
    or stat.S_ISLNK(directory.st_mode)
    or stat.S_IMODE(directory.st_mode) != 0o500
    or directory.st_uid != os.geteuid()
    or not stat.S_ISREG(binary.st_mode)
    or stat.S_ISLNK(binary.st_mode)
    or stat.S_IMODE(binary.st_mode) != 0o500
    or binary.st_uid != os.geteuid()
    or f"{binary.st_dev}:{binary.st_ino}:{binary.st_size}" != sys.argv[3]
):
    raise SystemExit(1)
digest = hashlib.sha256(pathlib.Path(sys.argv[2]).read_bytes()).hexdigest()
if digest != sys.argv[4]:
    raise SystemExit(1)
PY
}

timestamp_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

run_text_receipt() { # stem adb-argv...
  local stem=$1
  shift
  adb_snapshot_intact || stop_now STOP_ADB_SNAPSHOT_CHANGED
  [[ $stem =~ ^[a-z0-9][a-z0-9-]*$ ]] || stop_now STOP_INTERNAL_RECEIPT_NAME
  CLASSIFY_ARGV=("$@")
  classify_adb_argv
  local class_rc=$?
  if (( class_rc == 2 )); then stop_now STOP_MUTATING_COMMAND; fi
  if (( class_rc != 0 )); then stop_now STOP_UNLISTED_COMMAND; fi

  local prefix="$OUTPUT_DIR/receipts/$stem"
  if ! {
    printf '%q' "$ADB_BIN"
    local arg
    for arg in "$@"; do printf ' %q' "$arg"; done
    printf '\n'
  } >"$prefix.command.txt"; then
    stop_now STOP_INTERNAL_RECEIPT_WRITE
  fi
  timestamp_utc >"$prefix.start-utc.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  : >"$prefix.stdout.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  : >"$prefix.stderr.bin" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  (
    unset ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT
    "$ADB_BIN" "$@"
  ) </dev/null >"$prefix.stdout.txt" 2>"$prefix.stderr.bin"
  LAST_RC=$?
  printf '%d\n' "$LAST_RC" >"$prefix.exit.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  timestamp_utc >"$prefix.end-utc.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  "$PYTHON_BIN" -I - "$prefix.stdout.txt" <<'PY' >/dev/null 2>&1 \
    || stop_now STOP_INCOMPLETE_RECEIPT
import pathlib
import sys

if b"\x00" in pathlib.Path(sys.argv[1]).read_bytes():
    raise SystemExit(1)
PY
  # Command substitution removes only trailing LF bytes. Embedded separators
  # remain available to strict scalar consumers instead of being concatenated.
  LAST_STDOUT="$(<"$prefix.stdout.txt")"
  printf '%s\n' "$stem" >>"$OUTPUT_DIR/receipts/stems.txt" \
    || stop_now STOP_INTERNAL_RECEIPT_WRITE
  adb_snapshot_intact || stop_now STOP_ADB_SNAPSHOT_CHANGED
}

run_binary_receipt() { # stem adb-argv...
  local stem=$1
  shift
  adb_snapshot_intact || stop_now STOP_ADB_SNAPSHOT_CHANGED
  [[ $stem =~ ^[a-z0-9][a-z0-9-]*$ ]] || stop_now STOP_INTERNAL_RECEIPT_NAME
  CLASSIFY_ARGV=("$@")
  classify_adb_argv
  local class_rc=$?
  if (( class_rc == 2 )); then stop_now STOP_MUTATING_COMMAND; fi
  if (( class_rc != 0 )); then stop_now STOP_UNLISTED_COMMAND; fi

  local prefix="$OUTPUT_DIR/receipts/$stem"
  if ! {
    printf '%q' "$ADB_BIN"
    local arg
    for arg in "$@"; do printf ' %q' "$arg"; done
    printf '\n'
  } >"$prefix.command.txt"; then
    stop_now STOP_INTERNAL_RECEIPT_WRITE
  fi
  timestamp_utc >"$prefix.start-utc.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  : >"$prefix.stdout.bin" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  : >"$prefix.stderr.bin" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  (
    unset ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT
    "$ADB_BIN" "$@"
  ) </dev/null >"$prefix.stdout.bin" 2>"$prefix.stderr.bin"
  LAST_RC=$?
  printf '%d\n' "$LAST_RC" >"$prefix.exit.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  timestamp_utc >"$prefix.end-utc.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  LAST_STDOUT=""
  printf '%s\n' "$stem" >>"$OUTPUT_DIR/receipts/stems.txt" \
    || stop_now STOP_INTERNAL_RECEIPT_WRITE
  adb_snapshot_intact || stop_now STOP_ADB_SNAPSHOT_CHANGED
}

verify_receipts() { # existing evidence root; host-only, no adb
  local root=$1 detail rc verify_identity
  [[ $root == /* && $root != */ && -d $root && ! -L $root ]] || {
    printf 'STOP_INCOMPLETE_RECEIPT\n' >&2
    return 21
  }
  verify_identity="$(path_identity "$root")" || {
    printf 'STOP_INCOMPLETE_RECEIPT\n' >&2
    return 21
  }
  cd "$root" || {
    printf 'STOP_INCOMPLETE_RECEIPT\n' >&2
    return 21
  }
  [[ $(path_identity .) == "$verify_identity" ]] || {
    printf 'STOP_INCOMPLETE_RECEIPT\n' >&2
    return 21
  }
  detail="$("$PYTHON_BIN" -I - . "$COLLECTOR_PATH" "$root" "$verify_identity" \
    "$ADB_ALLOWLIST_PATH" "$ADB_ALLOWLIST_EXPECTED_SHA256" \
    "$ADB_APPROVAL_LANE" "$ADB_CLIENT_TRUST" <<'PY' 2>&1
import datetime
import decimal
import hashlib
import io
import json
import os
import pathlib
import re
import shlex
import stat
import struct
import subprocess
import sys
import unicodedata
import zipfile

root = pathlib.Path(sys.argv[1])
collector_path = pathlib.Path(sys.argv[2])
display_path = pathlib.Path(sys.argv[3])
expected_root_identity = sys.argv[4]
allowlist_path = pathlib.Path(sys.argv[5])
expected_allowlist_digest = sys.argv[6]
expected_approval_lane = sys.argv[7]
expected_client_trust = sys.argv[8]
manifest_path = root / "manifest.json"
summary_path = root / "summary.json"
receipts = root / "receipts"
tooling = root / "tooling"

def inode_state(value):
    return (
        value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode),
        value.st_uid, stat.S_IMODE(value.st_mode), value.st_size,
        value.st_mtime_ns, value.st_ctime_ns,
    )

def has_extended_acl(path):
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except (AttributeError, OSError):
        attributes = ()
    if any(
        attribute in {"system.posix_acl_access", "system.posix_acl_default"}
        for attribute in attributes
    ):
        return True
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/bin/ls", "-lde", str(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={key: value for key, value in os.environ.items() if not key.startswith("GIT_")},
            check=False,
        )
        if result.returncode != 0 or not result.stdout.splitlines():
            return True
        output_lines = result.stdout.splitlines()
        mode_token = output_lines[0].split(maxsplit=1)[0]
        return "+" in mode_token or any(
            line.lstrip().split(":", 1)[0].isdigit() for line in output_lines[1:]
        )
    return False

def directory_state(path, expected_mode=0o700):
    value = path.lstat()
    if (
        not stat.S_ISDIR(value.st_mode)
        or stat.S_ISLNK(value.st_mode)
        or value.st_uid != os.geteuid()
        or stat.S_IMODE(value.st_mode) != expected_mode
        or has_extended_acl(path)
    ):
        raise SystemExit(f"unsafe evidence directory ownership/mode: {path}")
    after = path.lstat()
    if inode_state(value) != inode_state(after):
        raise SystemExit(f"evidence directory changed while checking ACL: {path}")
    return inode_state(after)

file_states = {}

def stable_bytes(path, expected_mode=0o600):
    descriptor = -1
    try:
        before = path.lstat()
        if (
            not stat.S_ISREG(before.st_mode)
            or stat.S_ISLNK(before.st_mode)
            or before.st_uid != os.geteuid()
            or (expected_mode is not None and stat.S_IMODE(before.st_mode) != expected_mode)
            or has_extended_acl(path)
        ):
            raise OSError("unsafe evidence file ownership/mode")
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
        )
        opened_before = os.fstat(descriptor)
        if (
            inode_state(before) != inode_state(opened_before)
            or not stat.S_ISREG(opened_before.st_mode)
            or opened_before.st_uid != os.geteuid()
            or (
                expected_mode is not None
                and stat.S_IMODE(opened_before.st_mode) != expected_mode
            )
        ):
            raise OSError("unsafe evidence file ownership/mode")
        key = str(path)
        current_state = inode_state(opened_before)
        if key in file_states and file_states[key] != current_state:
            raise OSError("evidence file identity changed")
        chunks = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        opened_after = os.fstat(descriptor)
        after = path.lstat()
    except OSError as error:
        raise SystemExit(f"unsafe or unstable evidence file: {path}: {error}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    data = b"".join(chunks)
    if not (
        inode_state(before) == inode_state(opened_before)
        == inode_state(opened_after) == inode_state(after)
    ) or opened_after.st_size != len(data):
        raise SystemExit(f"evidence file changed while reading: {path}")
    file_states.setdefault(key, current_state)
    return data

def stable_repo_bytes(path):
    descriptor = -1
    try:
        before = path.lstat()
        if (
            not stat.S_ISREG(before.st_mode)
            or stat.S_ISLNK(before.st_mode)
            or before.st_uid != os.geteuid()
            or stat.S_IMODE(before.st_mode) & 0o022
            or has_extended_acl(path)
        ):
            raise OSError("unsafe repo trust file ownership/mode")
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
        )
        opened_before = os.fstat(descriptor)
        if (
            inode_state(before) != inode_state(opened_before)
            or not stat.S_ISREG(opened_before.st_mode)
            or opened_before.st_uid != os.geteuid()
            or stat.S_IMODE(opened_before.st_mode) & 0o022
        ):
            raise OSError("unsafe repo trust file ownership/mode")
        key = str(path)
        current_state = inode_state(opened_before)
        if key in file_states and file_states[key] != current_state:
            raise OSError("repo trust file identity changed")
        chunks = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        opened_after = os.fstat(descriptor)
        after = path.lstat()
    except OSError as error:
        raise SystemExit(f"unsafe or unstable repo trust file: {path}: {error}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    data = b"".join(chunks)
    if not (
        inode_state(before) == inode_state(opened_before)
        == inode_state(opened_after) == inode_state(after)
    ) or opened_after.st_size != len(data):
        raise SystemExit(f"repo trust file changed while reading: {path}")
    file_states.setdefault(key, current_state)
    return data

root_state = directory_state(root)
receipts_state = directory_state(receipts)
tooling_state = directory_state(tooling, expected_mode=0o500)
allowlist_bytes = stable_repo_bytes(allowlist_path)
if hashlib.sha256(allowlist_bytes).hexdigest() != expected_allowlist_digest:
    raise SystemExit("ADB allowlist digest mismatch")
try:
    named_root = display_path.lstat()
except OSError as error:
    raise SystemExit(f"evidence display path unavailable: {error}")
if (
    stat.S_ISLNK(named_root.st_mode)
    or not stat.S_ISDIR(named_root.st_mode)
    or f"{named_root.st_dev}:{named_root.st_ino}" != expected_root_identity
    or (named_root.st_dev, named_root.st_ino) != (root_state[0], root_state[1])
):
    raise SystemExit("evidence display path no longer names the pinned root")

manifest_bytes = stable_bytes(manifest_path)
summary_bytes = stable_bytes(summary_path)
collector_bytes = stable_repo_bytes(collector_path)

def exact_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result

try:
    manifest = json.loads(
        manifest_bytes.decode("utf-8"),
        object_pairs_hook=exact_json_object,
    )
except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as error:
    raise SystemExit(f"manifest JSON invalid: {error}")
manifest_keys = {
    "schemaVersion", "mode", "readOnlySemantics", "incidentalEffects",
    "adbServerTrust", "adbClientTrust", "adbApprovalLane", "adbApprovalLabel",
    "adbAllowlistSha256", "adbSnapshotPath", "status", "terminalStatus", "reason",
    "collectionStatus", "compatibility", "privilegedInspection",
    "coordinateCaptured", "authorizedSerial", "targetSerial", "devicePass",
    "issue66Ac7", "deviceFull", "durableAck", "fullClaim", "adbSha256",
    "collectorSha256", "receiptTreeSha256", "knownPackages", "servicesJarSha256",
    "packageApkSha256", "receiptStems",
}
if not isinstance(manifest, dict) or set(manifest) != manifest_keys:
    raise SystemExit("manifest key whitelist mismatch")
expected_scalars = {
    "schemaVersion": 2,
    "mode": "READ_ONLY_PREFLIGHT",
    "readOnlySemantics": "OPERATIONAL_NOT_BIT_FOR_BIT",
    "incidentalEffects": [
        "ADB_TRANSPORT", "TRANSIENT_QUERY_PROCESSES", "DEVICE_AUDIT_ACCOUNTING"
    ],
    "adbServerTrust": "DEFAULT_LOCAL_ENDPOINT_NOT_ATTESTED__INHERITED_ROUTING_REJECTED",
    "adbClientTrust": expected_client_trust,
    "adbApprovalLane": expected_approval_lane,
    "adbAllowlistSha256": expected_allowlist_digest,
    "adbSnapshotPath": "tooling/adb",
    "status": "COLLECTED",
    "terminalStatus": "PUBLIC_STATIC_EVIDENCE_COLLECTED__STATIC_ANALYSIS_PENDING",
    "reason": "PUBLIC_STATIC_EVIDENCE_COLLECTED__STATIC_ANALYSIS_PENDING",
    "collectionStatus": "COLLECTED",
    "compatibility": "STATIC_ANALYSIS_PENDING",
    "privilegedInspection": "NOT_COLLECTED_PRIVILEGED",
    "coordinateCaptured": False,
    "authorizedSerial": "ZY22JHW9M4",
    "targetSerial": "ZY22JHW9M4",
    "devicePass": False,
    "issue66Ac7": "NOT_PASSED",
    "deviceFull": "BLOCKED",
    "durableAck": "NOT_CREATED",
    "fullClaim": "NOT_CREATED",
}
for key, expected in expected_scalars.items():
    if manifest.get(key) != expected or type(manifest.get(key)) is not type(expected):
        raise SystemExit(f"manifest identity/claim mismatch: {key}")

serial = "ZY22JHW9M4"
known_packages = (
    "name.caiyao.fakegps",
    "name.caiyao.fakegps.bench",
    "name.caiyao.fakegps.codexbench",
    "com.example.cellrebelauto",
    "com.example.cellrebelauto.codexbench",
    "com.cellrebel.mobile",
)
statuses = manifest.get("knownPackages")
if not isinstance(statuses, dict) or set(statuses) != set(known_packages):
    raise SystemExit("knownPackages does not match the fixed package set")
if any(status not in {"INSTALLED", "NOT_INSTALLED"} for status in statuses.values()):
    raise SystemExit("knownPackages contains a nonterminal state")
installed = {package for package in known_packages if statuses[package] == "INSTALLED"}

digest_re = re.compile(r"^[0-9a-f]{64}$")
adb_digest = manifest.get("adbSha256")
collector_digest = manifest.get("collectorSha256")
receipt_tree_digest = manifest.get("receiptTreeSha256")
services_digest = manifest.get("servicesJarSha256")
for name, value in (
    ("adbSha256", adb_digest),
    ("collectorSha256", collector_digest),
    ("receiptTreeSha256", receipt_tree_digest),
    ("servicesJarSha256", services_digest),
):
    if not isinstance(value, str) or not digest_re.fullmatch(value):
        raise SystemExit(f"{name} missing or malformed")

try:
    allowlist_text = allowlist_bytes.decode("ascii")
except UnicodeDecodeError as error:
    raise SystemExit(f"ADB allowlist is not ASCII: {error}")
if "\x00" in allowlist_text or "\r" in allowlist_text or not allowlist_text.endswith("\n"):
    raise SystemExit("ADB allowlist framing mismatch")
allowlist_rows = []
for line in allowlist_text.splitlines():
    if line.startswith("#"):
        continue
    if not line:
        raise SystemExit("ADB allowlist contains an empty row")
    fields = line.split("\t")
    if len(fields) != 3:
        raise SystemExit("ADB allowlist row shape mismatch")
    lane, label, digest = fields
    if lane not in {"PRODUCTION", "SELFTEST"}:
        raise SystemExit("ADB allowlist lane mismatch")
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,95}", label):
        raise SystemExit("ADB allowlist label mismatch")
    if not digest_re.fullmatch(digest):
        raise SystemExit("ADB allowlist client digest mismatch")
    allowlist_rows.append((lane, label, digest))
if (
    not allowlist_rows
    or len({row[1] for row in allowlist_rows}) != len(allowlist_rows)
    or len({row[2] for row in allowlist_rows}) != len(allowlist_rows)
):
    raise SystemExit("ADB allowlist uniqueness mismatch")
approval_label = manifest.get("adbApprovalLabel")
if not isinstance(approval_label, str):
    raise SystemExit("ADB approval label missing")
approval_matches = [
    row for row in allowlist_rows
    if row == (expected_approval_lane, approval_label, adb_digest)
]
if len(approval_matches) != 1:
    raise SystemExit("ADB client digest/lane/label is not repo-approved")

apk_digests = manifest.get("packageApkSha256")
if not isinstance(apk_digests, dict) or set(apk_digests) != installed:
    raise SystemExit("packageApkSha256 does not exactly match installed packages")
if any(not isinstance(value, str) or not digest_re.fullmatch(value) for value in apk_digests.values()):
    raise SystemExit("packageApkSha256 contains a malformed digest")

try:
    summary = json.loads(
        summary_bytes.decode("utf-8"),
        object_pairs_hook=exact_json_object,
    )
except (json.JSONDecodeError, UnicodeDecodeError, ValueError) as error:
    raise SystemExit(f"summary JSON invalid: {error}")
summary_keys = {
    "schemaVersion", "mode", "readOnlySemantics", "incidentalEffects",
    "adbServerTrust", "adbClientTrust", "adbApprovalLane", "adbApprovalLabel",
    "adbAllowlistSha256", "adbSnapshotPath", "status", "collectionStatus", "compatibility",
    "redacted", "coordinateCaptured", "authorizedSerial", "targetSerial",
    "privilegedInspection", "devicePass", "issue66Ac7", "deviceFull",
    "durableAck", "fullClaim", "knownPackages", "servicesJarSha256",
    "packageApkSha256", "adbSha256", "collectorSha256", "receiptTreeSha256",
    "receiptCount",
}
if not isinstance(summary, dict) or set(summary) != summary_keys:
    raise SystemExit("summary key whitelist mismatch")
for key in summary_keys - {"redacted", "receiptCount"}:
    if summary.get(key) != manifest.get(key) or type(summary.get(key)) is not type(manifest.get(key)):
        raise SystemExit(f"summary/manifest mismatch: {key}")
if summary.get("redacted") is not True:
    raise SystemExit("summary is not marked redacted")

expected_stems = [
    "devices", "shell-id", "boot-id-start", "uptime-start", "transport-state", "serial",
    "manufacturer", "api", "fingerprint", "model", "device", "release",
    "abilist", "zygote", "boot-completed", "selinux",
    "current-user", "process-list", "location-enabled",
]
for package in known_packages:
    package_stem = package.replace(".", "-")
    expected_stems.append(f"package-{package_stem}-path")
    if package in installed:
        expected_stems.extend(
            (
                f"package-{package_stem}-dumpsys",
                f"package-{package_stem}-pidof",
                f"package-{package_stem}-appops",
            )
        )
for package in known_packages:
    if package in installed:
        expected_stems.append(f"package-{package.replace('.', '-')}-apk")
expected_stems.extend(("services-jar", "uptime-end", "boot-id-end"))

stems = manifest.get("receiptStems")
if stems != expected_stems:
    raise SystemExit("receiptStems does not match the exact ordered collection graph")
if type(summary.get("receiptCount")) is not int or summary["receiptCount"] != len(expected_stems):
    raise SystemExit("summary receiptCount mismatch")
stems_path = receipts / "stems.txt"
try:
    stems_bytes = stable_bytes(stems_path)
    stems_text = stems_bytes.decode("utf-8")
except UnicodeDecodeError as error:
    raise SystemExit(f"stems.txt is not UTF-8: {error}")
if stems_text.splitlines() != expected_stems:
    raise SystemExit("manifest/stems mismatch")

rfc3339 = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
binary_stems = {"services-jar"} | {
    f"package-{package.replace('.', '-')}-apk" for package in installed
}
accounted = {"stems.txt"}
carriers = {}
receipt_snapshot = {"stems.txt": stems_bytes}
previous_end = None
for stem in expected_stems:
    candidates = [receipts / f"{stem}.stdout.txt", receipts / f"{stem}.stdout.bin"]
    stdout = [path for path in candidates if path.is_file() and not path.is_symlink()]
    if len(stdout) != 1:
        raise SystemExit(f"{stem}: stdout carrier count is {len(stdout)}")
    expects_binary = stem in binary_stems
    expected_suffix = ".bin" if expects_binary else ".txt"
    if stdout[0].suffix != expected_suffix:
        raise SystemExit(f"{stem}: wrong stdout carrier type")
    required = [
        receipts / f"{stem}.command.txt",
        receipts / f"{stem}.start-utc.txt",
        stdout[0],
        receipts / f"{stem}.stderr.bin",
        receipts / f"{stem}.exit.txt",
        receipts / f"{stem}.end-utc.txt",
    ]
    actual = list(receipts.glob(f"{stem}.*"))
    if len(actual) != 6 or {path.name for path in actual} != {path.name for path in required}:
        raise SystemExit(f"{stem}: not an exact six-file carrier")
    raw = {path.name: stable_bytes(path) for path in required}
    receipt_snapshot.update(raw)
    try:
        command_argv = shlex.split(raw[required[0].name].decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as error:
        raise SystemExit(f"{stem}: invalid command carrier: {error}")
    if len(command_argv) < 2:
        raise SystemExit(f"{stem}: incomplete command argv")
    try:
        exit_text = raw[f"{stem}.exit.txt"].decode("ascii").strip()
    except UnicodeDecodeError as error:
        raise SystemExit(f"{stem}: non-ASCII exit carrier: {error}")
    if not re.fullmatch(r"\d+", exit_text):
        raise SystemExit(f"{stem}: invalid exit carrier")
    times = []
    for suffix in ("start-utc.txt", "end-utc.txt"):
        try:
            value = raw[f"{stem}.{suffix}"].decode("ascii").strip()
        except UnicodeDecodeError as error:
            raise SystemExit(f"{stem}: non-ASCII {suffix}: {error}")
        if not rfc3339.fullmatch(value):
            raise SystemExit(f"{stem}: invalid {suffix}")
        times.append(datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ"))
    if times[1] < times[0]:
        raise SystemExit(f"{stem}: end precedes start")
    if previous_end is not None and times[0] < previous_end:
        raise SystemExit(f"{stem}: receipt order moves backwards in time")
    previous_end = times[1]
    carriers[stem] = {
        "argv": tuple(command_argv),
        "stdout": raw[stdout[0].name],
        "stderr": raw[f"{stem}.stderr.bin"],
        "rc": int(exit_text),
    }
    accounted.update(path.name for path in required)

def text(stem):
    try:
        return carriers[stem]["stdout"].decode("utf-8")
    except UnicodeDecodeError as error:
        raise SystemExit(f"{stem}: stdout is not UTF-8: {error}")

def scalar(stem):
    try:
        value = carriers[stem]["stdout"].decode("utf-8")
    except UnicodeDecodeError as error:
        raise SystemExit(f"{stem}: scalar stdout is not UTF-8: {error}")
    if value.endswith("\r\n"):
        value = value[:-2]
    elif value.endswith("\n"):
        value = value[:-1]
    if any(
        unicodedata.category(character) in {"Cc", "Zl", "Zp"}
        for character in value
    ):
        raise SystemExit(f"{stem}: scalar stdout is not exactly one line")
    if value != value.strip():
        raise SystemExit(f"{stem}: scalar stdout has edge whitespace")
    return value

def require_rc(stem, expected=0):
    if carriers[stem]["rc"] != expected:
        raise SystemExit(f"{stem}: exit={carriers[stem]['rc']} expected={expected}")

expected_argv = {
    "devices": ("devices", "-l"),
    "boot-id-start": ("-s", serial, "shell", "cat", "/proc/sys/kernel/random/boot_id"),
    "uptime-start": ("-s", serial, "shell", "cat", "/proc/uptime"),
    "transport-state": ("-s", serial, "get-state"),
    "serial": ("-s", serial, "shell", "getprop", "ro.serialno"),
    "manufacturer": ("-s", serial, "shell", "getprop", "ro.product.manufacturer"),
    "api": ("-s", serial, "shell", "getprop", "ro.build.version.sdk"),
    "fingerprint": ("-s", serial, "shell", "getprop", "ro.build.fingerprint"),
    "model": ("-s", serial, "shell", "getprop", "ro.product.model"),
    "device": ("-s", serial, "shell", "getprop", "ro.product.device"),
    "release": ("-s", serial, "shell", "getprop", "ro.build.version.release"),
    "abilist": ("-s", serial, "shell", "getprop", "ro.product.cpu.abilist"),
    "zygote": ("-s", serial, "shell", "getprop", "ro.zygote"),
    "boot-completed": ("-s", serial, "shell", "getprop", "sys.boot_completed"),
    "shell-id": ("-s", serial, "shell", "id"),
    "selinux": ("-s", serial, "shell", "getenforce"),
    "current-user": ("-s", serial, "shell", "am", "get-current-user"),
    "process-list": ("-s", serial, "shell", "ps", "-A", "-o", "USER,PID,NAME"),
    "location-enabled": (
        "-s", serial, "shell", "cmd", "location", "is-location-enabled", "--user", "0"
    ),
    "services-jar": ("-s", serial, "exec-out", "cat", "/system/framework/services.jar"),
    "uptime-end": ("-s", serial, "shell", "cat", "/proc/uptime"),
    "boot-id-end": ("-s", serial, "shell", "cat", "/proc/sys/kernel/random/boot_id"),
}

for stem in (
    "devices", "shell-id", "boot-id-start", "uptime-start", "transport-state", "serial",
    "manufacturer", "api", "fingerprint", "model", "device", "release",
    "abilist", "zygote", "boot-completed", "selinux",
    "current-user", "process-list", "location-enabled", "services-jar",
    "uptime-end", "boot-id-end",
):
    require_rc(stem)

try:
    devices_text = carriers["devices"]["stdout"].decode("utf-8")
except UnicodeDecodeError as error:
    raise SystemExit(f"devices: stdout is not UTF-8: {error}")
devices_text = devices_text.replace("\r\n", "\n")
if "\x00" in devices_text or "\r" in devices_text or not devices_text.endswith("\n\n"):
    raise SystemExit("devices: bare/internal CR")
device_lines = devices_text[:-2].split("\n")
if not device_lines or device_lines[0] != "List of devices attached":
    raise SystemExit("devices: header mismatch")
device_rows = device_lines[1:]
if (
    len(device_rows) != 1
    or not re.fullmatch(r"[!-~]+ +[!-~]+(?: +[!-~]+)*", device_rows[0])
):
    raise SystemExit("devices: exact target inventory mismatch")
device_fields = device_rows[0].split()
device_serial, device_state = device_fields[:2]
if device_serial != serial or device_state != "device":
    raise SystemExit("devices: exact target inventory mismatch")
if scalar("transport-state") != "device":
    raise SystemExit("transport-state mismatch")
if scalar("serial") != serial:
    raise SystemExit("serial identity mismatch")
if scalar("manufacturer").lower() != "motorola":
    raise SystemExit("manufacturer identity mismatch")
if scalar("api") != "35":
    raise SystemExit("API identity mismatch")
for stem in ("fingerprint", "model", "device", "release", "abilist", "zygote", "boot-completed"):
    if not scalar(stem):
        raise SystemExit(f"{stem}: empty core identity")
if carriers["shell-id"]["stderr"] or not re.fullmatch(
    r"uid=2000\(shell\)(?:\s+.*)?", scalar("shell-id")
):
    raise SystemExit("shell identity mismatch")
if scalar("selinux") not in {"Enforcing", "Permissive", "Disabled"}:
    raise SystemExit("SELinux state malformed")
if scalar("current-user") != "0":
    raise SystemExit("Android user mismatch")
process_text = text("process-list")
process_text = process_text.replace("\r\n", "\n")
if "\r" in process_text or "\x00" in process_text or not process_text.endswith("\n"):
    raise SystemExit("process list contains an invalid control byte")
process_lines = process_text[:-1].split("\n")
if (
    any(not line for line in process_lines)
    or len(process_lines) < 2
    or process_lines[0].split() != ["USER", "PID", "NAME"]
):
    raise SystemExit("process list schema mismatch")
for row in process_lines[1:]:
    parts = row.split(maxsplit=2)
    if len(parts) != 3 or not re.fullmatch(r"[0-9]+", parts[1]) or not parts[0] or not parts[2].strip():
        raise SystemExit("process list row malformed")
if scalar("location-enabled") not in {"true", "false"}:
    raise SystemExit("location-enabled state malformed")

boot_re = re.compile(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
boot_start = scalar("boot-id-start")
boot_end = scalar("boot-id-end")
if not boot_re.fullmatch(boot_start) or not boot_re.fullmatch(boot_end):
    raise SystemExit("boot ID malformed")
if boot_start != boot_end:
    raise SystemExit("boot ID changed")

def uptime(stem):
    parts = scalar(stem).split()
    if len(parts) != 2:
        raise SystemExit(f"{stem}: malformed uptime")
    try:
        values = [decimal.Decimal(part) for part in parts]
    except decimal.InvalidOperation:
        raise SystemExit(f"{stem}: malformed uptime")
    if any(not value.is_finite() or value < 0 for value in values):
        raise SystemExit(f"{stem}: invalid uptime")
    return values[0]

if uptime("uptime-end") < uptime("uptime-start"):
    raise SystemExit("uptime decreased")

safe_path_re = re.compile(
    r"^/data/app/([A-Za-z0-9._+=~-]+)/([A-Za-z0-9._+=~-]+)/"
    r"(base|split_[A-Za-z0-9._+=~-]+)\.apk$"
)

def valid_appops_text(value):
    value = value.replace("\r\n", "\n")
    if not value.isascii() or "\r" in value or "\x00" in value:
        return False
    if not value.endswith("\n"):
        return False
    value = value[:-1]
    lines = value.split("\n")
    if not lines or any(not line for line in lines):
        return False
    mode = r"(?:allow|ignore|deny|default|foreground)"
    uid_mode = r"(?:allow|ignore|default|foreground)"
    u23 = r"(?:0|[1-9]|1[0-9]|2[0-3])"
    nz23 = r"(?:[1-9]|1[0-9]|2[0-3])"
    u59 = r"(?:0|[1-9]|[1-5][0-9])"
    nz59 = r"(?:[1-9]|[1-5][0-9])"
    u999 = r"(?:0|[1-9][0-9]{0,2})"
    nz999 = r"(?:[1-9][0-9]{0,2})"
    duration_body = (
        rf"(?:"
        rf"[1-9][0-9]*d{u23}h{u59}m{u59}s{u999}ms|"
        rf"{nz23}h{u59}m{u59}s{u999}ms|"
        rf"{nz59}m{u59}s{u999}ms|"
        rf"{nz59}s{u999}ms|"
        rf"{nz999}ms)"
    )
    delta_duration = rf"(?:0|[+-]{duration_body})"
    elapsed_duration = rf"(?:0|\+{duration_body})"
    present = re.compile(
        rf"MOCK_LOCATION: {mode}"
        rf"(?:"
        rf"; rejectTime={delta_duration} ago|"
        rf"; time={delta_duration} ago"
        rf"(?:; rejectTime={delta_duration} ago)?"
        rf"(?: \(running\)|; duration={elapsed_duration})?"
        rf")?"
    )
    uid_present = re.compile(rf"Uid mode: MOCK_LOCATION: {uid_mode}")

    def valid_package_row(line):
        if present.fullmatch(line) is None:
            return False
        for token in re.findall(r"(?:time|rejectTime|duration)=([^ ;]+)", line):
            if token == "0":
                continue
            fields = {
                unit: int(amount)
                for amount, unit in re.findall(r"([0-9]+)(ms|[dhms])", token[1:])
            }
            seconds = (
                fields.get("d", 0) * 86400
                + fields.get("h", 0) * 3600
                + fields.get("m", 0) * 60
                + fields.get("s", 0)
            )
            if seconds > 2147483647:
                return False
        return True

    return (
        len(lines) == 1
        and (
            valid_package_row(lines[0])
            or uid_present.fullmatch(lines[0]) is not None
        )
    ) or (
        len(lines) == 2
        and uid_present.fullmatch(lines[0]) is not None
        and valid_package_row(lines[1])
    ) or (
        len(lines) == 2
        and lines[0] == "No operations."
        and lines[1] == "Default mode: deny"
    )

def valid_archive_bytes(data, kind):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            members = archive.infolist()
            if any(member.filename != member.orig_filename for member in members):
                return False
            names = [member.orig_filename for member in members]
            if not members or len(names) != len(set(names)):
                return False
            for name in names:
                parts = pathlib.PurePosixPath(name).parts
                if not name or name.startswith("/") or ".." in parts or "\x00" in name:
                    return False
            if kind == "apk" and names.count("AndroidManifest.xml") != 1:
                return False
            if kind == "services" and not any(
                re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name) for name in names
            ):
                return False
            if kind not in {"apk", "services"} or archive.testzip() is not None:
                return False
    except (RuntimeError, ValueError, zipfile.BadZipFile, zipfile.LargeZipFile):
        return False
    return True

for package in known_packages:
    package_stem = package.replace(".", "-")
    path_stem = f"package-{package_stem}-path"
    expected_argv[path_stem] = ("-s", serial, "shell", "pm", "path", package)
    path_stdout_raw = carriers[path_stem]["stdout"]
    path_stderr_raw = carriers[path_stem]["stderr"]
    if package not in installed:
        require_rc(path_stem, 1)
        if path_stdout_raw or path_stderr_raw:
            raise SystemExit(f"{path_stem}: NOT_INSTALLED truth mismatch")
        continue
    if path_stderr_raw:
        raise SystemExit(f"{path_stem}: installed path emitted stderr")
    require_rc(path_stem)
    try:
        path_stdout = path_stdout_raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SystemExit(f"{path_stem}: path stdout is not UTF-8: {error}")
    if "\x00" in path_stdout or not path_stdout.endswith("\n"):
        raise SystemExit(f"{path_stem}: installed path framing mismatch")
    path_stdout = path_stdout.replace("\r\n", "\n")
    if "\r" in path_stdout:
        raise SystemExit(f"{path_stem}: installed path contains bare CR")
    path_lines = path_stdout[:-1].split("\n")
    package_paths = []
    for line in path_lines:
        if not line.startswith("package:"):
            raise SystemExit(f"{path_stem}: installed path malformed")
        candidate = line[len("package:"):]
        match = safe_path_re.fullmatch(candidate)
        if (
            not match
            or match.group(1) in {".", ".."}
            or match.group(2) in {".", ".."}
            or not match.group(2).startswith(package + "-")
        ):
            raise SystemExit(f"{path_stem}: unsafe installed path")
        package_paths.append((candidate, match.group(3)))
    if len({path for path, _ in package_paths}) != len(package_paths):
        raise SystemExit(f"{path_stem}: duplicate installed path")
    base_paths = [path for path, leaf in package_paths if leaf == "base"]
    if len(base_paths) != 1:
        raise SystemExit(f"{path_stem}: expected exactly one base APK")
    package_path = base_paths[0]

    dumpsys_stem = f"package-{package_stem}-dumpsys"
    pidof_stem = f"package-{package_stem}-pidof"
    appops_stem = f"package-{package_stem}-appops"
    apk_stem = f"package-{package_stem}-apk"
    expected_argv[dumpsys_stem] = ("-s", serial, "shell", "dumpsys", "package", package)
    expected_argv[pidof_stem] = ("-s", serial, "shell", "pidof", package)
    expected_argv[appops_stem] = (
        "-s", serial, "shell", "appops", "get", "--user", "0", package,
        "android:mock_location",
    )
    expected_argv[apk_stem] = ("-s", serial, "exec-out", "cat", package_path)

    require_rc(dumpsys_stem)
    dumpsys_text = text(dumpsys_stem)
    if (
        carriers[dumpsys_stem]["stderr"]
        or f"Package [{package}]" not in dumpsys_text
        or not re.search(r"(?:^|\s)versionCode=\d+(?:\s|$)", dumpsys_text)
    ):
        raise SystemExit(f"{dumpsys_stem}: package dump mismatch")
    if re.search(r"DUMP TIMEOUT|Unable to find package|Can.t find service|^Error:", dumpsys_text, re.I | re.M):
        raise SystemExit(f"{dumpsys_stem}: package dump reports failure")

    if carriers[pidof_stem]["stderr"]:
        raise SystemExit(f"{pidof_stem}: unexpected stderr")
    if carriers[pidof_stem]["rc"] == 0:
        pid_text = scalar(pidof_stem)
        if not re.fullmatch(r"[0-9]+(?:\s+[0-9]+)*", pid_text):
            raise SystemExit(f"{pidof_stem}: running PID schema mismatch")
    elif carriers[pidof_stem]["rc"] == 1:
        if carriers[pidof_stem]["stdout"]:
            raise SystemExit(f"{pidof_stem}: stopped process emitted stdout")
    else:
        raise SystemExit(f"{pidof_stem}: invalid exit truth")

    require_rc(appops_stem)
    if carriers[appops_stem]["stderr"]:
        raise SystemExit(f"{appops_stem}: unexpected stderr")
    if not valid_appops_text(text(appops_stem)):
        raise SystemExit(f"{appops_stem}: mock-location AppOp malformed or ambiguous")

    require_rc(apk_stem)
    if not valid_archive_bytes(carriers[apk_stem]["stdout"], "apk"):
        raise SystemExit(f"{apk_stem}: invalid APK archive")

require_rc("services-jar")
if not valid_archive_bytes(carriers["services-jar"]["stdout"], "services"):
    raise SystemExit("services.jar archive invalid")

adb_paths = {carrier["argv"][0] for carrier in carriers.values()}
if len(adb_paths) != 1:
    raise SystemExit("receipt commands do not share one adb executable")
for stem in expected_stems:
    if carriers[stem]["argv"][1:] != expected_argv.get(stem):
        raise SystemExit(f"{stem}: command argv mismatch")

def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()

if adb_paths != {"./tooling/adb"}:
    raise SystemExit("receipt commands do not name the private adb snapshot")
adb_path = tooling / "adb"
adb_bytes = stable_bytes(adb_path, expected_mode=0o500)
if sha256_bytes(adb_bytes) != adb_digest:
    raise SystemExit("adb executable digest mismatch")
if sha256_bytes(collector_bytes) != collector_digest:
    raise SystemExit("collector executable digest mismatch")
if sha256_bytes(carriers["services-jar"]["stdout"]) != services_digest:
    raise SystemExit("services.jar digest mismatch")
for package in installed:
    stem = f"package-{package.replace('.', '-')}-apk"
    if sha256_bytes(carriers[stem]["stdout"]) != apk_digests[package]:
        raise SystemExit(f"APK digest mismatch: {package}")

actual_entries = {path.name for path in receipts.iterdir()}
if actual_entries != accounted:
    raise SystemExit("unmanifested or missing receipt entry")

tree_digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
for name_text in sorted(receipt_snapshot, key=lambda value: value.encode("utf-8")):
    name = name_text.encode("utf-8")
    data = receipt_snapshot[name_text]
    tree_digest.update(struct.pack(">Q", len(name)))
    tree_digest.update(name)
    tree_digest.update(struct.pack(">Q", len(data)))
    tree_digest.update(data)
if tree_digest.hexdigest() != receipt_tree_digest:
    raise SystemExit("receipt tree digest mismatch")

if {path.name for path in tooling.iterdir()} != {"adb"}:
    raise SystemExit("tooling directory contains an unexpected entry")
if {path.name for path in root.iterdir()} != {"manifest.json", "summary.json", "receipts", "tooling"}:
    raise SystemExit("evidence root contains an unexpected entry")

# Re-open every authenticated byte source and re-check directory identities.
# This rejects a verifier result assembled from multiple pathname snapshots.
if stable_bytes(manifest_path) != manifest_bytes or stable_bytes(summary_path) != summary_bytes:
    raise SystemExit("manifest or summary changed during verification")
if stable_bytes(adb_path, expected_mode=0o500) != adb_bytes:
    raise SystemExit("adb snapshot changed during verification")
if stable_repo_bytes(collector_path) != collector_bytes:
    raise SystemExit("collector changed during verification")
if stable_repo_bytes(allowlist_path) != allowlist_bytes:
    raise SystemExit("ADB allowlist changed during verification")
for name, data in receipt_snapshot.items():
    if stable_bytes(receipts / name) != data:
        raise SystemExit(f"receipt changed during verification: {name}")
if (
    directory_state(root) != root_state
    or directory_state(receipts) != receipts_state
    or directory_state(tooling, expected_mode=0o500) != tooling_state
):
    raise SystemExit("evidence directory identity changed during verification")
named_root = display_path.lstat()
if (
    stat.S_ISLNK(named_root.st_mode)
    or f"{named_root.st_dev}:{named_root.st_ino}" != expected_root_identity
    or (named_root.st_dev, named_root.st_ino) != (root_state[0], root_state[1])
):
    raise SystemExit("evidence display path changed during verification")
PY
)"
  rc=$?
  if (( rc != 0 )); then
    printf 'STOP_INCOMPLETE_RECEIPT: %s\n' "$detail" >&2
    return 21
  fi
  printf 'RECEIPTS_COMPLETE\n'
  return 0
}

read_scalar_receipt() { # byte-exact UTF-8 scalar, optional LF/CRLF terminator
  "$PYTHON_BIN" -I - "$1" <<'PY'
import pathlib
import sys
import unicodedata

try:
    value = pathlib.Path(sys.argv[1]).read_bytes().decode("utf-8")
except (OSError, UnicodeDecodeError):
    raise SystemExit(1)
if value.endswith("\r\n"):
    value = value[:-2]
elif value.endswith("\n"):
    value = value[:-1]
if any(
    unicodedata.category(character) in {"Cc", "Zl", "Zp"}
    for character in value
):
    raise SystemExit(1)
if value != value.strip():
    raise SystemExit(1)
sys.stdout.write(value)
PY
}

valid_boot_id() { # canonical Linux boot UUID
  [[ $1 =~ ^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$ ]]
}

valid_uptime_line() { # /proc/uptime text
  "$PYTHON_BIN" -I - "$1" <<'PY' >/dev/null 2>&1
import decimal
import sys

parts = sys.argv[1].split()
if len(parts) != 2:
    raise SystemExit(1)
for part in parts:
    try:
        value = decimal.Decimal(part)
    except decimal.InvalidOperation:
        raise SystemExit(1)
    if not value.is_finite() or value < 0:
        raise SystemExit(1)
PY
}

uptime_not_decreased() { # start line, end line
  "$PYTHON_BIN" -I - "$1" "$2" <<'PY' >/dev/null 2>&1
import decimal
import sys

def first(line):
    parts = line.split()
    if len(parts) != 2:
        raise ValueError("malformed uptime")
    values = [decimal.Decimal(part) for part in parts]
    if any(not value.is_finite() or value < 0 for value in values):
        raise ValueError("invalid uptime")
    return values[0]

try:
    start = first(sys.argv[1])
    end = first(sys.argv[2])
except (decimal.InvalidOperation, ValueError):
    raise SystemExit(1)
raise SystemExit(0 if end >= start else 1)
PY
}

valid_process_list_file() { # Android toybox ps -A -o USER,PID,NAME output
  "$PYTHON_BIN" -I - "$1" <<'PY' >/dev/null 2>&1
import pathlib
import re
import sys

try:
    text = pathlib.Path(sys.argv[1]).read_bytes().decode("utf-8")
except (OSError, UnicodeDecodeError):
    raise SystemExit(1)
text = text.replace("\r\n", "\n")
if "\r" in text or "\x00" in text or not text.endswith("\n"):
    raise SystemExit(1)
lines = text[:-1].split("\n")
if any(not line for line in lines):
    raise SystemExit(1)
if len(lines) < 2 or lines[0].split() != ["USER", "PID", "NAME"]:
    raise SystemExit(1)
for line in lines[1:]:
    try:
        user, pid, name = line.split(maxsplit=2)
    except ValueError:
        raise SystemExit(1)
    if not user or not re.fullmatch(r"[0-9]+", pid) or not name.strip():
        raise SystemExit(1)
PY
}

valid_package_dump_file() { # dumpsys package file, exact package
  "$PYTHON_BIN" -I - "$1" "$2" <<'PY' >/dev/null 2>&1
import pathlib
import re
import sys

try:
    text = pathlib.Path(sys.argv[1]).read_bytes().decode("utf-8")
except (OSError, UnicodeDecodeError):
    raise SystemExit(1)
package = sys.argv[2]
if f"Package [{package}]" not in text:
    raise SystemExit(1)
if not re.search(r"(?:^|\s)versionCode=\d+(?:\s|$)", text):
    raise SystemExit(1)
if re.search(r"DUMP TIMEOUT|Unable to find package|Can.t find service|^Error:", text, re.I | re.M):
    raise SystemExit(1)
PY
}

valid_selinux_file() { # exact getenforce scalar
  "$PYTHON_BIN" -I - "$1" <<'PY' >/dev/null 2>&1
import pathlib
import sys

try:
    text = pathlib.Path(sys.argv[1]).read_bytes().decode("utf-8")
except (OSError, UnicodeDecodeError):
    raise SystemExit(1)
allowed = {"Enforcing", "Permissive", "Disabled"}
if text not in (
    allowed
    | {value + "\n" for value in allowed}
    | {value + "\r\n" for value in allowed}
):
    raise SystemExit(1)
PY
}

valid_appops_file() { # one unambiguous mock_location result
  "$PYTHON_BIN" -I - "$1" <<'PY' >/dev/null 2>&1
import pathlib
import re
import sys

try:
    text = pathlib.Path(sys.argv[1]).read_bytes().decode("utf-8")
except (OSError, UnicodeDecodeError):
    raise SystemExit(1)
text = text.replace("\r\n", "\n")
if not text.isascii() or "\r" in text or "\x00" in text:
    raise SystemExit(1)
if not text.endswith("\n"):
    raise SystemExit(1)
text = text[:-1]
lines = text.split("\n")
if not lines or any(not line for line in lines):
    raise SystemExit(1)
mode = r"(?:allow|ignore|deny|default|foreground)"
uid_mode = r"(?:allow|ignore|default|foreground)"
u23 = r"(?:0|[1-9]|1[0-9]|2[0-3])"
nz23 = r"(?:[1-9]|1[0-9]|2[0-3])"
u59 = r"(?:0|[1-9]|[1-5][0-9])"
nz59 = r"(?:[1-9]|[1-5][0-9])"
u999 = r"(?:0|[1-9][0-9]{0,2})"
nz999 = r"(?:[1-9][0-9]{0,2})"
duration_body = (
    rf"(?:"
    rf"[1-9][0-9]*d{u23}h{u59}m{u59}s{u999}ms|"
    rf"{nz23}h{u59}m{u59}s{u999}ms|"
    rf"{nz59}m{u59}s{u999}ms|"
    rf"{nz59}s{u999}ms|"
    rf"{nz999}ms)"
)
delta_duration = rf"(?:0|[+-]{duration_body})"
elapsed_duration = rf"(?:0|\+{duration_body})"
present = re.compile(
    rf"MOCK_LOCATION: {mode}"
    rf"(?:"
    rf"; rejectTime={delta_duration} ago|"
    rf"; time={delta_duration} ago"
    rf"(?:; rejectTime={delta_duration} ago)?"
    rf"(?: \(running\)|; duration={elapsed_duration})?"
    rf")?"
)
uid_present = re.compile(rf"Uid mode: MOCK_LOCATION: {uid_mode}")

def valid_package_row(line):
    if present.fullmatch(line) is None:
        return False
    for token in re.findall(r"(?:time|rejectTime|duration)=([^ ;]+)", line):
        if token == "0":
            continue
        fields = {
            unit: int(amount)
            for amount, unit in re.findall(r"([0-9]+)(ms|[dhms])", token[1:])
        }
        seconds = (
            fields.get("d", 0) * 86400
            + fields.get("h", 0) * 3600
            + fields.get("m", 0) * 60
            + fields.get("s", 0)
        )
        if seconds > 2147483647:
            return False
    return True

if len(lines) == 1 and (
    valid_package_row(lines[0]) or uid_present.fullmatch(lines[0])
):
    raise SystemExit(0)
if len(lines) == 2 and uid_present.fullmatch(lines[0]) and valid_package_row(lines[1]):
    raise SystemExit(0)
if len(lines) == 2 and lines == ["No operations.", "Default mode: deny"]:
    raise SystemExit(0)
raise SystemExit(1)
PY
}

valid_archive_file() { # path apk|services
  "$PYTHON_BIN" -I - "$1" "$2" <<'PY' >/dev/null 2>&1
import pathlib
import re
import sys
import zipfile

path = pathlib.Path(sys.argv[1])
kind = sys.argv[2]
try:
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        if any(member.filename != member.orig_filename for member in members):
            raise ValueError("archive member name contains a NUL suffix")
        names = [member.orig_filename for member in members]
        if not members or len(names) != len(set(names)):
            raise ValueError("empty archive or duplicate member")
        for name in names:
            parts = pathlib.PurePosixPath(name).parts
            if not name or name.startswith("/") or ".." in parts or "\x00" in name:
                raise ValueError("unsafe archive member")
        if kind == "apk":
            if names.count("AndroidManifest.xml") != 1:
                raise ValueError("APK manifest missing")
        elif kind == "services":
            if not any(re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name) for name in names):
                raise ValueError("services dex missing")
        else:
            raise ValueError("unknown archive kind")
        if archive.testzip() is not None:
            raise ValueError("archive CRC failure")
except (OSError, RuntimeError, ValueError, zipfile.BadZipFile, zipfile.LargeZipFile):
    raise SystemExit(1)
PY
}

classify_devices_inventory_file() { # devices stdout, authorized serial
  "$PYTHON_BIN" -I - "$1" "$2" <<'PY'
import pathlib
import re
import sys

try:
    raw = pathlib.Path(sys.argv[1]).read_bytes()
    text = raw.decode("utf-8")
except (OSError, UnicodeDecodeError):
    raise SystemExit(2)
text = text.replace("\r\n", "\n")
if "\x00" in text or "\r" in text or not text.endswith("\n\n"):
    raise SystemExit(2)
lines = text[:-2].split("\n")
if not lines or lines[0] != "List of devices attached":
    raise SystemExit(2)
rows = lines[1:]
if any(not row or not re.fullmatch(r"[!-~]+ +[!-~]+(?: +[!-~]+)*", row) for row in rows):
    raise SystemExit(2)
if len(rows) > 1:
    print("EXTRA")
elif not rows:
    print("MISSING")
else:
    fields = rows[0].split()
    serial, state = fields[:2]
    print("OK" if serial == sys.argv[2] and state == "device" else "MISSING")
PY
}

parse_args() {
  while (( $# > 0 )); do
    case "$1" in
      --adb) (( $# >= 2 )) || { usage; exit 2; }; ADB_BIN=$2; shift 2 ;;
      --serial) (( $# >= 2 )) || { usage; exit 2; }; REQUESTED_SERIAL=$2; shift 2 ;;
      --output) (( $# >= 2 )) || { usage; exit 2; }; OUTPUT_DIR=$2; shift 2 ;;
      --classify-adb) CLASSIFY_ONLY=1; shift ;;
      --selftest-fixture)
        (( SELFTEST_FIXTURE == 0 )) || { usage; exit 2; }
        SELFTEST_FIXTURE=1
        shift
        ;;
      --verify-receipts) (( $# >= 2 )) || { usage; exit 2; }; VERIFY_DIR=$2; shift 2 ;;
      --) shift; CLASSIFY_ARGV=("$@"); break ;;
      *) usage; exit 2 ;;
    esac
  done
}

main() {
  parse_args "$@"
  [[ -f $PYTHON_BIN && -x $PYTHON_BIN && ! -L $PYTHON_BIN ]] \
    || stop_now STOP_INTERNAL_PYTHON_RUNTIME
  select_adb_approval_lane

  if [[ -n $VERIFY_DIR ]]; then
    [[ -z $ADB_BIN && -z $REQUESTED_SERIAL && -z $OUTPUT_DIR ]] || { usage; exit 2; }
    verify_receipts "$VERIFY_DIR"
    exit $?
  fi

  [[ -n $ADB_BIN ]] || { usage; exit 2; }

  if (( CLASSIFY_ONLY )); then
    validate_adb_binary
    validate_adb_approval
    (( ${#CLASSIFY_ARGV[@]} > 0 )) || { usage; exit 2; }
    classify_adb_argv
    case $? in
      0) printf 'ALLOW_READ_ONLY\n'; exit 0 ;;
      2) printf 'STOP_MUTATING_COMMAND\n' >&2; exit 22 ;;
      *) printf 'STOP_UNLISTED_COMMAND\n' >&2; exit 22 ;;
    esac
  fi

  if [[ -n ${ADB_SERVER_SOCKET+x} || -n ${ANDROID_ADB_SERVER_ADDRESS+x} \
      || -n ${ANDROID_ADB_SERVER_PORT+x} ]]; then
    stop_now STOP_UNSAFE_ADB_SERVER_ENV
  fi

  [[ $REQUESTED_SERIAL == "$AUTHORIZED_SERIAL" ]] || stop_now STOP_WRONG_SERIAL
  [[ -n $OUTPUT_DIR ]] || { usage; exit 2; }
  validate_adb_binary
  validate_adb_approval
  [[ -f $COLLECTOR_PATH && ! -L $COLLECTOR_PATH ]] \
    || stop_now STOP_INTERNAL_COLLECTOR_IDENTITY
  COLLECTOR_SHA256="$(sha256_file "$COLLECTOR_PATH")" \
    || stop_now STOP_INTERNAL_HASH_FAILED
  [[ $COLLECTOR_SHA256 =~ ^[0-9a-f]{64}$ ]] || stop_now STOP_INTERNAL_HASH_FAILED
  local output_record
  output_record="$(create_output_dir)" || stop_now STOP_UNSAFE_OUTPUT
  IFS=$'\t' read -r OUTPUT_DISPLAY_PATH OUTPUT_IDENTITY <<<"$output_record"
  [[ -n $OUTPUT_DISPLAY_PATH && -n $OUTPUT_IDENTITY ]] || stop_now STOP_UNSAFE_OUTPUT
  OUTPUT_DIR=$OUTPUT_DISPLAY_PATH
  cd "$OUTPUT_DISPLAY_PATH" || stop_now STOP_UNSAFE_OUTPUT
  OUTPUT_DIR=.
  output_binding_intact || stop_now STOP_UNSAFE_OUTPUT
  EVIDENCE_READY=1
  write_manifest "STOP" "STOP_RUNNING" || fatal_manifest_write
  mkdir -m 700 receipts || stop_now STOP_INTERNAL_RECEIPT_WRITE
  : >receipts/stems.txt || stop_now STOP_INTERNAL_RECEIPT_WRITE
  snapshot_adb_binary || stop_now STOP_INTERNAL_ADB_SNAPSHOT
  # Refresh the authoritative STOP only after the exact executable snapshot is
  # available, still before the first device command.
  write_manifest "STOP" "STOP_RUNNING" || fatal_manifest_write

  run_text_receipt devices devices -l
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED

  local inventory_status inventory_rc
  inventory_status="$(classify_devices_inventory_file \
    "$OUTPUT_DIR/receipts/devices.stdout.txt" "$AUTHORIZED_SERIAL")"
  inventory_rc=$?
  (( inventory_rc == 0 )) || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  case "$inventory_status" in
    OK) ;;
    EXTRA) stop_now STOP_EXTRA_DEVICE ;;
    MISSING) stop_now STOP_MISSING_TARGET ;;
    *) stop_now STOP_INCOMPLETE_CORE_RECEIPT ;;
  esac

  # This is the first serial-targeted command. It does not request privilege;
  # if the negotiated adbd principal is not shell, no other device observation
  # is permitted.
  run_text_receipt shell-id -s "$AUTHORIZED_SERIAL" shell id
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  local shell_identity
  shell_identity="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/shell-id.stdout.txt")" \
    || stop_now STOP_UNPRIVILEGED_SHELL_REQUIRED
  [[ ! -s $OUTPUT_DIR/receipts/shell-id.stderr.bin ]] \
    || stop_now STOP_UNPRIVILEGED_SHELL_REQUIRED
  [[ $shell_identity =~ ^uid=2000\(shell\)([[:space:]].*)?$ ]] \
    || stop_now STOP_UNPRIVILEGED_SHELL_REQUIRED

  run_text_receipt boot-id-start \
    -s "$AUTHORIZED_SERIAL" shell cat /proc/sys/kernel/random/boot_id
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  local boot_id_start uptime_start
  boot_id_start="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/boot-id-start.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  valid_boot_id "$boot_id_start" || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  run_text_receipt uptime-start -s "$AUTHORIZED_SERIAL" shell cat /proc/uptime
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  uptime_start="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/uptime-start.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  valid_uptime_line "$uptime_start" || stop_now STOP_INCOMPLETE_CORE_RECEIPT

  run_text_receipt transport-state -s "$AUTHORIZED_SERIAL" get-state
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  local transport_state
  transport_state="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/transport-state.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  [[ $transport_state == device ]] || stop_now STOP_MISSING_TARGET

  run_text_receipt serial -s "$AUTHORIZED_SERIAL" shell getprop ro.serialno
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  local live_serial
  live_serial="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/serial.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  [[ -n $live_serial ]] || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  [[ $live_serial == "$AUTHORIZED_SERIAL" ]] || stop_now STOP_WRONG_SERIAL

  run_text_receipt manufacturer -s "$AUTHORIZED_SERIAL" shell getprop ro.product.manufacturer
  local manufacturer
  manufacturer="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/manufacturer.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  manufacturer="$(printf '%s' "$manufacturer" | tr '[:upper:]' '[:lower:]')"
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  [[ -n $manufacturer ]] || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  [[ $manufacturer == "$EXPECTED_MANUFACTURER" ]] || stop_now STOP_WRONG_MANUFACTURER

  run_text_receipt api -s "$AUTHORIZED_SERIAL" shell getprop ro.build.version.sdk
  local api
  api="$(read_scalar_receipt "$OUTPUT_DIR/receipts/api.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  [[ -n $api ]] || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  [[ $api == "$EXPECTED_API" ]] || stop_now STOP_WRONG_API

  local stem property value
  for stem in fingerprint model device release abilist zygote boot-completed; do
    case "$stem" in
      fingerprint) property=ro.build.fingerprint ;;
      model) property=ro.product.model ;;
      device) property=ro.product.device ;;
      release) property=ro.build.version.release ;;
      abilist) property=ro.product.cpu.abilist ;;
      zygote) property=ro.zygote ;;
      boot-completed) property=sys.boot_completed ;;
    esac
    run_text_receipt "$stem" -s "$AUTHORIZED_SERIAL" shell getprop "$property"
    value="$(read_scalar_receipt "$OUTPUT_DIR/receipts/$stem.stdout.txt")" \
      || stop_now STOP_INCOMPLETE_CORE_RECEIPT
    (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
    [[ -n $value ]] || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  done

  run_text_receipt selinux -s "$AUTHORIZED_SERIAL" shell getenforce
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  valid_selinux_file "$OUTPUT_DIR/receipts/selinux.stdout.txt" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT

  run_text_receipt current-user -s "$AUTHORIZED_SERIAL" shell am get-current-user
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  local current_user
  current_user="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/current-user.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  [[ $current_user == 0 ]] || stop_now STOP_UNSUPPORTED_USER_0_REQUIRED

  run_text_receipt process-list \
    -s "$AUTHORIZED_SERIAL" shell ps -A -o USER,PID,NAME
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  valid_process_list_file "$OUTPUT_DIR/receipts/process-list.stdout.txt" \
    || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED

  run_text_receipt location-enabled \
    -s "$AUTHORIZED_SERIAL" shell cmd location is-location-enabled --user 0
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  local location_enabled
  location_enabled="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/location-enabled.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  case "$location_enabled" in
    true|false) ;;
    *) stop_now STOP_INCOMPLETE_CORE_RECEIPT ;;
  esac

  # First collect and validate every fixed package path before reading any APK
  # bytes. This prevents an unsafe later path from leaving a partially trusted
  # set of APK binaries in the same evidence run.
  local package package_stem package_path package_i query_rc
  local package_apk_paths=("" "" "" "" "" "")
  for ((package_i = 0; package_i < ${#KNOWN_PACKAGES[@]}; package_i++)); do
    package=${KNOWN_PACKAGES[package_i]}
    package_stem=${package//./-}

    run_text_receipt "package-$package_stem-path" \
      -s "$AUTHORIZED_SERIAL" shell pm path "$package"
    query_rc=$LAST_RC
    if (( query_rc == 1 )); then
      [[ ! -s $OUTPUT_DIR/receipts/package-$package_stem-path.stdout.txt ]] \
          && [[ ! -s $OUTPUT_DIR/receipts/package-$package_stem-path.stderr.bin ]] \
        || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
      PACKAGE_STATUSES[package_i]="NOT_INSTALLED"
      continue
    elif (( query_rc != 0 )); then
      stop_now STOP_ADB_READ_FAILED
    fi
    [[ ! -s $OUTPUT_DIR/receipts/package-$package_stem-path.stderr.bin ]] \
      || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
    [[ -s $OUTPUT_DIR/receipts/package-$package_stem-path.stdout.txt ]] \
      || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
    package_path="$(select_base_apk_path \
      "$OUTPUT_DIR/receipts/package-$package_stem-path.stdout.txt" "$package")" \
      || stop_now STOP_UNSAFE_PACKAGE_PATH
    PACKAGE_STATUSES[package_i]="INSTALLED"
    package_apk_paths[package_i]=$package_path

    run_text_receipt "package-$package_stem-dumpsys" \
      -s "$AUTHORIZED_SERIAL" shell dumpsys package "$package"
    query_rc=$LAST_RC
    (( query_rc == 0 )) || stop_now STOP_ADB_READ_FAILED
    if [[ -s $OUTPUT_DIR/receipts/package-$package_stem-dumpsys.stderr.bin ]] \
        || ! valid_package_dump_file \
          "$OUTPUT_DIR/receipts/package-$package_stem-dumpsys.stdout.txt" "$package"; then
      stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
    fi

    run_text_receipt "package-$package_stem-pidof" \
      -s "$AUTHORIZED_SERIAL" shell pidof "$package"
    query_rc=$LAST_RC
    if (( query_rc == 0 )); then
      valid_pidof_file \
        "$OUTPUT_DIR/receipts/package-$package_stem-pidof.stdout.txt" \
        && [[ ! -s $OUTPUT_DIR/receipts/package-$package_stem-pidof.stderr.bin ]] \
        || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
    elif (( query_rc == 1 )); then
      [[ ! -s $OUTPUT_DIR/receipts/package-$package_stem-pidof.stdout.txt ]] \
        && [[ ! -s $OUTPUT_DIR/receipts/package-$package_stem-pidof.stderr.bin ]] \
        || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
    else
      stop_now STOP_ADB_READ_FAILED
    fi

    run_text_receipt "package-$package_stem-appops" \
      -s "$AUTHORIZED_SERIAL" shell appops get --user 0 \
      "$package" android:mock_location
    query_rc=$LAST_RC
    (( query_rc == 0 )) || stop_now STOP_ADB_READ_FAILED
    [[ ! -s $OUTPUT_DIR/receipts/package-$package_stem-appops.stderr.bin ]] \
      || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
    valid_appops_file \
      "$OUTPUT_DIR/receipts/package-$package_stem-appops.stdout.txt" \
      || stop_now STOP_PACKAGE_OBSERVATION_MALFORMED
  done

  for ((package_i = 0; package_i < ${#KNOWN_PACKAGES[@]}; package_i++)); do
    [[ ${PACKAGE_STATUSES[package_i]} == INSTALLED ]] || continue
    package=${KNOWN_PACKAGES[package_i]}
    package_stem=${package//./-}
    package_path=${package_apk_paths[package_i]}
    run_binary_receipt "package-$package_stem-apk" \
      -s "$AUTHORIZED_SERIAL" exec-out cat "$package_path"
    (( LAST_RC == 0 )) || stop_now STOP_APK_READ_FAILED
    [[ -s $OUTPUT_DIR/receipts/package-$package_stem-apk.stdout.bin ]] \
      || stop_now STOP_APK_READ_FAILED
    valid_archive_file \
      "$OUTPUT_DIR/receipts/package-$package_stem-apk.stdout.bin" apk \
      || stop_now STOP_APK_READ_FAILED
    PACKAGE_APK_SHA256[package_i]="$(sha256_file \
      "$OUTPUT_DIR/receipts/package-$package_stem-apk.stdout.bin")" \
      || stop_now STOP_INTERNAL_HASH_FAILED
    [[ ${PACKAGE_APK_SHA256[package_i]} =~ ^[0-9a-f]{64}$ ]] \
      || stop_now STOP_INTERNAL_HASH_FAILED
  done

  run_binary_receipt services-jar \
    -s "$AUTHORIZED_SERIAL" exec-out cat /system/framework/services.jar
  (( LAST_RC == 0 )) || stop_now STOP_FRAMEWORK_READ_FAILED
  [[ -s $OUTPUT_DIR/receipts/services-jar.stdout.bin ]] \
    || stop_now STOP_FRAMEWORK_READ_FAILED
  valid_archive_file "$OUTPUT_DIR/receipts/services-jar.stdout.bin" services \
    || stop_now STOP_FRAMEWORK_READ_FAILED
  SERVICES_JAR_SHA256="$(sha256_file "$OUTPUT_DIR/receipts/services-jar.stdout.bin")" \
    || stop_now STOP_INTERNAL_HASH_FAILED
  [[ $SERVICES_JAR_SHA256 =~ ^[0-9a-f]{64}$ ]] \
    || stop_now STOP_INTERNAL_HASH_FAILED

  local boot_id_end uptime_end
  run_text_receipt uptime-end -s "$AUTHORIZED_SERIAL" shell cat /proc/uptime
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  uptime_end="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/uptime-end.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  valid_uptime_line "$uptime_end" || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  run_text_receipt boot-id-end \
    -s "$AUTHORIZED_SERIAL" shell cat /proc/sys/kernel/random/boot_id
  (( LAST_RC == 0 )) || stop_now STOP_ADB_READ_FAILED
  boot_id_end="$(read_scalar_receipt \
    "$OUTPUT_DIR/receipts/boot-id-end.stdout.txt")" \
    || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  valid_boot_id "$boot_id_end" || stop_now STOP_INCOMPLETE_CORE_RECEIPT
  [[ $boot_id_start == "$boot_id_end" ]] || stop_now STOP_BOOT_CHANGED
  uptime_not_decreased "$uptime_start" "$uptime_end" || stop_now STOP_BOOT_CHANGED

  RECEIPT_TREE_SHA256="$(sha256_receipt_tree "$OUTPUT_DIR/receipts")" \
    || stop_now STOP_INTERNAL_HASH_FAILED
  [[ $RECEIPT_TREE_SHA256 =~ ^[0-9a-f]{64}$ ]] || stop_now STOP_INTERNAL_HASH_FAILED
  publish_collected_bundle
  printf 'COLLECTED evidence=%s compatibility=STATIC_ANALYSIS_PENDING adbApprovalLane=%s\n' \
    "$OUTPUT_DISPLAY_PATH" "$ADB_APPROVAL_LANE"
}

main "$@"

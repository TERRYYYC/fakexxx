#!/usr/bin/env bash
# Device-free static compatibility check for an exact Android services.jar.
#
# A production success is only COMPATIBILITY_CANDIDATE. It does not prove that
# any hook installed or ran, and it can never mint #66/FULL/attestation state.
# The pinned fake dexdump used by the host selftest has a separate result status
# and can never emit a production compatibility candidate.

set -uo pipefail
umask 077

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
MEMBERS="$SELF_DIR/fixtures/issue66-services-compatibility/required-members.tsv"
APPROVED_DEXDUMP_DIGESTS="$SELF_DIR/fixtures/issue66-services-compatibility/approved-dexdump-sha256.tsv"
CHECKER_PATH="$SELF_DIR/check-issue66-services-compatibility.sh"
EXPECTED_REQUIRED_MEMBERS_SHA256="f67953df36dfbe0c5f2d687015c5f48f527d6c6cb0d9858b6edf2154b9709154"
EXPECTED_APPROVED_DEXDUMP_DIGESTS_SHA256="bce0868ba52870baf8d9b74fdfdf8f62a585b51b44291151d732912bd8d92a3a"
EXPECTED_SELFTEST_DEXDUMP_SHA256="bafe125157541a659c1d89d18270c85381f74d934989f8aa0b74861a497be4a6"

SERVICES_JAR=""
DEXDUMP=""
OUTPUT=""
OUTPUT_RESERVED=0
ALLOW_PINNED_SELFTEST_FIXTURE=0
SELFTEST_OUTPUT_WRITE_FAILURE=0
DEXDUMP_IDENTITY=""
DEXDUMP_BUILD_TOOLS_REVISION=""
WORK=""

usage() {
  printf 'usage: %s --services-jar <path> --dexdump <path> --output <new-json> [--allow-pinned-selftest-fixture] [--selftest-output-write-failure]\n' \
    "${0##*/}" >&2
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

cleanup() {
  if [[ -n $WORK && -d $WORK ]]; then
    rm -rf -- "$WORK"
  fi
}
trap cleanup EXIT

reserve_output() {
  [[ $OUTPUT == /* ]] || return 1
  local parent=${OUTPUT%/*}
  [[ -d $parent && ! -L $parent ]] || return 1
  if [[ -e $OUTPUT || -L $OUTPUT ]]; then return 2; fi
  set -o noclobber
  if ! { exec 9>"$OUTPUT"; } 2>/dev/null; then
    set +o noclobber
    return 2
  fi
  set +o noclobber
  OUTPUT_RESERVED=1
}

output_binding_intact() {
  (( OUTPUT_RESERVED )) || return 1
  python3 - "$OUTPUT" <<'PY'
import os
import stat
import sys

path = sys.argv[1]
try:
    path_stat = os.lstat(path)
    fd_stat = os.fstat(9)
except OSError:
    raise SystemExit(1)
if not stat.S_ISREG(path_stat.st_mode) or not stat.S_ISREG(fd_stat.st_mode):
    raise SystemExit(1)
if (path_stat.st_dev, path_stat.st_ino) != (fd_stat.st_dev, fd_stat.st_ino):
    raise SystemExit(1)
PY
}

write_reserved_output() { # complete-json-line
  local payload=$1
  output_binding_intact || return 2
  if (( SELFTEST_OUTPUT_WRITE_FAILURE )) \
      && [[ $DEXDUMP_IDENTITY == PINNED_SELFTEST_FIXTURE ]]; then
    exec 9>&-
    exec 9<"$OUTPUT" || return 3
  fi
  python3 - "$payload" <<'PY' || return 3
import os
import sys

data = (sys.argv[1] + "\n").encode("utf-8")
try:
    os.lseek(9, 0, os.SEEK_SET)
    os.ftruncate(9, 0)
    view = memoryview(data)
    while view:
        written = os.write(9, view)
        if written <= 0:
            raise OSError("short output write")
        view = view[written:]
    os.fsync(9)
    if os.fstat(9).st_size != len(data):
        raise OSError("unexpected output size")
except OSError:
    raise SystemExit(1)
PY
  output_binding_intact || return 2
}

emit_stop() { # reason [missing-class] [missing-method]
  local reason=$1 missing_class=${2-} missing_method=${3-}
  if (( OUTPUT_RESERVED )); then
    local payload
    payload="$(printf '{"schemaVersion":1,"status":"STOP","reason":"%s","missingClass":"%s","missingMethod":"%s","issue66Ac7":"NOT_PASSED","deviceFull":"BLOCKED","authority":"NONE"}' \
      "$(json_escape "$reason")" \
      "$(json_escape "$missing_class")" \
      "$(json_escape "$missing_method")")"
    write_reserved_output "$payload" >/dev/null 2>&1 || true
  fi
  printf 'STOP_%s\n' "$reason" >&2
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$1" | awk '{print $1}'
  else
    sha256sum -- "$1" | awk '{print $1}'
  fi
}

validate_dexdump_identity() { # original-path snapshot-path sha256 approved-digests
  local path=$1 snapshot=$2 digest=$3 approved_digests=$4 identity_result verdict revision
  if (( ALLOW_PINNED_SELFTEST_FIXTURE )) \
      && [[ $digest == "$EXPECTED_SELFTEST_DEXDUMP_SHA256" ]]; then
    DEXDUMP_IDENTITY="PINNED_SELFTEST_FIXTURE"
    DEXDUMP_BUILD_TOOLS_REVISION="SELFTEST"
    return 0
  fi

  identity_result="$(python3 - "$path" "$snapshot" "$digest" "$approved_digests" <<'PY'
import os
import pathlib
import pwd
import stat
import sys

tool = pathlib.Path(sys.argv[1])
snapshot = pathlib.Path(sys.argv[2])
digest = sys.argv[3]
approved_path = pathlib.Path(sys.argv[4])
if not tool.is_absolute() or str(tool) != os.path.normpath(str(tool)):
    raise SystemExit(1)
try:
    resolved_tool = tool.resolve(strict=True)
except OSError:
    raise SystemExit(1)
if resolved_tool != tool or tool.name != "dexdump":
    raise SystemExit(1)

trusted_root = None
relative = None
try:
    account_home = pathlib.Path(pwd.getpwuid(os.getuid()).pw_dir)
except KeyError:
    raise SystemExit(1)
trusted_roots = (
    account_home / "Library/Android/sdk",
    account_home / "Android/Sdk",
    pathlib.Path("/usr/local/lib/android/sdk"),
    pathlib.Path("/opt/android-sdk"),
    pathlib.Path("/opt/android-sdk-linux"),
)
for root in trusted_roots:
    try:
        if not root.is_absolute() or root.resolve(strict=True) != root:
            continue
        candidate = tool.relative_to(root)
    except (OSError, ValueError):
        continue
    if len(candidate.parts) == 3 and candidate.parts[0] == "build-tools":
        trusted_root = root
        relative = candidate
        break
if trusted_root is None or relative is None or relative.parts[2] != "dexdump":
    raise SystemExit(1)

revision = relative.parts[1]
if not revision or any(char not in "0123456789.rc-" for char in revision):
    raise SystemExit(1)

uid = os.getuid()
for candidate in (
    trusted_root,
    trusted_root / "build-tools",
    trusted_root / "build-tools" / revision,
    tool,
):
    try:
        info = candidate.lstat()
    except OSError:
        raise SystemExit(1)
    if stat.S_ISLNK(info.st_mode) or info.st_uid not in {0, uid}:
        raise SystemExit(1)
    if info.st_mode & 0o022:
        raise SystemExit(1)
if not stat.S_ISREG(tool.lstat().st_mode) or not os.access(tool, os.X_OK):
    raise SystemExit(1)

properties = tool.parent / "source.properties"
try:
    properties_info = properties.lstat()
    properties_text = properties.read_text(encoding="utf-8")
except (OSError, UnicodeError):
    raise SystemExit(1)
if (
    not stat.S_ISREG(properties_info.st_mode)
    or stat.S_ISLNK(properties_info.st_mode)
    or properties_info.st_uid not in {0, uid}
    or properties_info.st_mode & 0o022
):
    raise SystemExit(1)
values = {}
for raw_line in properties_text.splitlines():
    line = raw_line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    values[key.strip()] = value.strip()
if values.get("Pkg.UserSrc") != "false" or values.get("Pkg.Revision") != revision:
    raise SystemExit(1)

try:
    with snapshot.open("rb") as stream:
        magic = stream.read(4)
except OSError:
    raise SystemExit(1)
native_magic = {
    bytes.fromhex("7f454c46"),  # ELF
    bytes.fromhex("feedface"), bytes.fromhex("feedfacf"),
    bytes.fromhex("cefaedfe"), bytes.fromhex("cffaedfe"),
    bytes.fromhex("cafebabe"), bytes.fromhex("cafebabf"),
    bytes.fromhex("bebafeca"), bytes.fromhex("bfbafeca"),
}
if magic not in native_magic:
    raise SystemExit(1)

approved = set()
try:
    with approved_path.open(encoding="utf-8") as stream:
        for line_number, raw in enumerate(stream, 1):
            line = raw.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 2:
                raise ValueError(f"invalid approval row {line_number}")
            approved_revision, approved_digest = parts
            if (
                not approved_revision
                or any(char not in "0123456789.rc-" for char in approved_revision)
                or len(approved_digest) != 64
                or any(char not in "0123456789abcdef" for char in approved_digest)
            ):
                raise ValueError(f"invalid approval row {line_number}")
            pair = (approved_revision, approved_digest)
            if pair in approved:
                raise ValueError(f"duplicate approval row {line_number}")
            approved.add(pair)
except (OSError, UnicodeError, ValueError):
    raise SystemExit(1)
verdict = "APPROVED" if (revision, digest) in approved else "UNATTESTED"
print(verdict, revision, sep="\t")
PY
  )" || return 1
  IFS=$'\t' read -r verdict revision <<<"$identity_result"
  [[ -n $revision ]] || return 1
  DEXDUMP_BUILD_TOOLS_REVISION=$revision
  if [[ $verdict == APPROVED ]]; then
    DEXDUMP_IDENTITY="ANDROID_SDK_BUILD_TOOLS_NATIVE_APPROVED_SHA256"
    return 0
  elif [[ $verdict == UNATTESTED ]]; then
    DEXDUMP_IDENTITY="ANDROID_SDK_BUILD_TOOLS_NATIVE_UNATTESTED"
    return 3
  fi
  return 1
}

parse_args() {
  while (( $# > 0 )); do
    case "$1" in
      --services-jar) (( $# >= 2 )) || return 2; SERVICES_JAR=$2; shift 2 ;;
      --dexdump) (( $# >= 2 )) || return 2; DEXDUMP=$2; shift 2 ;;
      --output) (( $# >= 2 )) || return 2; OUTPUT=$2; shift 2 ;;
      --allow-pinned-selftest-fixture)
        (( ALLOW_PINNED_SELFTEST_FIXTURE == 0 )) || return 2
        ALLOW_PINNED_SELFTEST_FIXTURE=1
        shift
        ;;
      --selftest-output-write-failure)
        (( SELFTEST_OUTPUT_WRITE_FAILURE == 0 )) || return 2
        SELFTEST_OUTPUT_WRITE_FAILURE=1
        shift
        ;;
      *) return 2 ;;
    esac
  done
  [[ -n $SERVICES_JAR && -n $DEXDUMP && -n $OUTPUT ]] || return 2
  (( SELFTEST_OUTPUT_WRITE_FAILURE == 0 || ALLOW_PINNED_SELFTEST_FIXTURE == 1 )) \
    || return 2
}

main() {
  parse_args "$@" || { usage; exit 2; }

  reserve_output
  local reserve_rc=$?
  if (( reserve_rc == 2 )); then
    printf 'STOP_OUTPUT_EXISTS\n' >&2
    exit 22
  elif (( reserve_rc != 0 )); then
    printf 'STOP_UNSAFE_OUTPUT\n' >&2
    exit 22
  fi

  if [[ -L $SERVICES_JAR || -L $DEXDUMP ]]; then
    emit_stop SYMLINK_INPUT
    exit 22
  fi
  if [[ ! -f $SERVICES_JAR || ! -f $DEXDUMP || ! -x $DEXDUMP \
      || ! -f $MEMBERS || ! -f $APPROVED_DEXDUMP_DIGESTS ]]; then
    emit_stop INVALID_INPUT
    exit 22
  fi

  WORK="$(mktemp -d "${TMPDIR:-/tmp}/issue66-services-compat.XXXXXX")" || {
    emit_stop INTERNAL_ERROR
    exit 70
  }
  chmod 700 "$WORK" || { emit_stop INTERNAL_ERROR; exit 70; }
  local jar_snapshot="$WORK/services.jar"
  local dexdump_snapshot="$WORK/dexdump"
  local members_snapshot="$WORK/required-members.tsv"
  local approved_digests_snapshot="$WORK/approved-dexdump-sha256.tsv"
  local services_before dexdump_before members_before approved_digests_before checker_before
  services_before="$(sha256_file "$SERVICES_JAR")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  dexdump_before="$(sha256_file "$DEXDUMP")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  members_before="$(sha256_file "$MEMBERS")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  approved_digests_before="$(sha256_file "$APPROVED_DEXDUMP_DIGESTS")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  checker_before="$(sha256_file "$CHECKER_PATH")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  if [[ $members_before != "$EXPECTED_REQUIRED_MEMBERS_SHA256" ]]; then
    emit_stop REQUIRED_MEMBERS_MISMATCH
    exit 21
  fi
  if [[ $approved_digests_before != "$EXPECTED_APPROVED_DEXDUMP_DIGESTS_SHA256" ]]; then
    emit_stop APPROVED_DEXDUMP_ALLOWLIST_MISMATCH
    exit 21
  fi
  cp -- "$SERVICES_JAR" "$jar_snapshot" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  cp -- "$DEXDUMP" "$dexdump_snapshot" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  cp -- "$MEMBERS" "$members_snapshot" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  cp -- "$APPROVED_DEXDUMP_DIGESTS" "$approved_digests_snapshot" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  chmod 500 "$dexdump_snapshot" || { emit_stop INTERNAL_ERROR; exit 70; }
  if [[ $(sha256_file "$jar_snapshot") != "$services_before" \
      || $(sha256_file "$dexdump_snapshot") != "$dexdump_before" \
      || $(sha256_file "$members_snapshot") != "$members_before" \
      || $(sha256_file "$approved_digests_snapshot") != "$approved_digests_before" ]]; then
    emit_stop INPUT_CHANGED
    exit 21
  fi
  validate_dexdump_identity \
    "$DEXDUMP" "$dexdump_snapshot" "$dexdump_before" "$approved_digests_snapshot"
  local identity_rc=$?
  if (( identity_rc == 3 )); then
    emit_stop TOOL_NOT_ATTESTED
    exit 22
  elif (( identity_rc != 0 )); then
    emit_stop UNTRUSTED_DEXDUMP
    exit 22
  fi

  local dex="$WORK/classes.dex" structure="$WORK/structure.txt"
  if ! python3 - "$jar_snapshot" "$dex" >"$structure" <<'PY'
import pathlib
import re
import sys
import zipfile

jar_path, dex_path = sys.argv[1:]
try:
    with zipfile.ZipFile(jar_path) as archive:
        dex_names = [name for name in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        if not dex_names:
            print("NO_DEX")
            raise SystemExit(10)
        if len(dex_names) != 1:
            print("MULTIPLE_DEX")
            raise SystemExit(11)
        payload = archive.read(dex_names[0])
except (OSError, zipfile.BadZipFile, KeyError) as error:
    print(f"INVALID_JAR:{error}")
    raise SystemExit(12)
if not payload:
    print("EMPTY_DEX")
    raise SystemExit(13)
pathlib.Path(dex_path).write_bytes(payload)
print("ONE_DEX")
PY
  then
    local structure_reason
    structure_reason="$(sed -n '1p' "$structure")"
    case "$structure_reason" in
      NO_DEX) emit_stop NO_DEX ;;
      MULTIPLE_DEX) emit_stop MULTIPLE_DEX ;;
      EMPTY_DEX) emit_stop EMPTY_DEX ;;
      *) emit_stop INVALID_JAR ;;
    esac
    exit 21
  fi

  local dump_out="$WORK/dexdump.stdout" dump_err="$WORK/dexdump.stderr"
  "$dexdump_snapshot" -d "$dex" </dev/null >"$dump_out" 2>"$dump_err" 9>&-
  local dump_rc=$?
  if (( dump_rc != 0 )); then
    emit_stop DEXDUMP_FAILED
    exit 21
  fi

  local result="$WORK/result.txt"
  if ! python3 - "$dump_out" "$members_snapshot" >"$result" <<'PY'
import re
import sys

dump_path, members_path = sys.argv[1:]
required = []
with open(members_path, encoding="utf-8") as stream:
    for raw in stream:
        line = raw.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        class_name, method_name = line.split("\t")
        required.append((class_name, method_name))

classes = set()
methods = set()
current_class = None
method_section = False
descriptor_re = re.compile(r"Class descriptor\s*:\s*'L([^;]+);'")
name_re = re.compile(r"^\s+name\s*:\s*'([^']+)'\s*$")
section_re = re.compile(r"^\s{2}(?:[A-Za-z][A-Za-z ]+)\s+-\s*$")
with open(dump_path, encoding="utf-8", errors="replace") as stream:
    for raw in stream:
        match = descriptor_re.search(raw)
        if match:
            current_class = match.group(1).replace("/", ".")
            classes.add(current_class)
            method_section = False
            continue
        if section_re.match(raw):
            method_section = "methods" in raw.lower()
            continue
        if method_section and current_class:
            match = name_re.match(raw)
            if match:
                methods.add((current_class, match.group(1)))

for class_name, _ in required:
    if class_name not in classes:
        print("MISSING_CLASS", class_name, sep="\t")
        raise SystemExit(20)
for class_name, method_name in required:
    if (class_name, method_name) not in methods:
        print("MISSING_METHOD", class_name, method_name, sep="\t")
        raise SystemExit(21)
print("STATIC_MEMBERS_PRESENT")
PY
  then
    local kind missing_class missing_method
    IFS=$'\t' read -r kind missing_class missing_method <"$result" || true
    case "$kind" in
      MISSING_CLASS) emit_stop MISSING_CLASS "$missing_class" ;;
      MISSING_METHOD) emit_stop MISSING_METHOD "$missing_class" "$missing_method" ;;
      *) emit_stop ANALYSIS_FAILED ;;
    esac
    exit 21
  fi

  local services_after dexdump_after members_after approved_digests_after checker_after
  services_after="$(sha256_file "$SERVICES_JAR")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  dexdump_after="$(sha256_file "$DEXDUMP")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  members_after="$(sha256_file "$MEMBERS")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  approved_digests_after="$(sha256_file "$APPROVED_DEXDUMP_DIGESTS")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  checker_after="$(sha256_file "$CHECKER_PATH")" \
    || { emit_stop INTERNAL_ERROR; exit 70; }
  if [[ $services_after != "$services_before" \
      || $dexdump_after != "$dexdump_before" \
      || $members_after != "$members_before" \
      || $approved_digests_after != "$approved_digests_before" \
      || $checker_after != "$checker_before" ]]; then
    emit_stop INPUT_CHANGED
    exit 21
  fi
  local final_status final_reason payload write_rc
  if [[ $DEXDUMP_IDENTITY == PINNED_SELFTEST_FIXTURE ]]; then
    final_status="SELFTEST_STATIC_MEMBERS_PRESENT"
    final_reason="PINNED_SELFTEST_FIXTURE_ONLY"
  else
    final_status="COMPATIBILITY_CANDIDATE"
    final_reason="EXACT_STATIC_MEMBERS_PRESENT"
  fi
  payload="$(printf '{"schemaVersion":1,"status":"%s","reason":"%s","requiredClassCount":7,"requiredMethodCount":20,"servicesJarSha256":"%s","dexdumpSha256":"%s","requiredMembersSha256":"%s","approvedDexdumpAllowlistSha256":"%s","checkerSha256":"%s","dexdumpIdentity":"%s","dexdumpBuildToolsRevision":"%s","issue66Ac7":"NOT_PASSED","deviceFull":"BLOCKED","authority":"NONE"}' \
    "$final_status" "$final_reason" \
    "$services_before" "$dexdump_before" "$members_before" \
    "$approved_digests_before" "$checker_before" \
    "$DEXDUMP_IDENTITY" "$DEXDUMP_BUILD_TOOLS_REVISION")"
  write_reserved_output "$payload"
  write_rc=$?
  if (( write_rc == 2 )); then
    printf 'STOP_OUTPUT_CHANGED\n' >&2
    exit 22
  elif (( write_rc != 0 )); then
    printf 'STOP_OUTPUT_WRITE_FAILED\n' >&2
    exit 70
  fi
  exec 9>&-
  printf '%s\n' "$final_status"
}

main "$@"

#!/bin/bash -p
# Device-free static compatibility check for an exact Android services.jar.
#
# A production success is only COMPATIBILITY_CANDIDATE. It does not prove that
# any hook installed or ran, and it can never mint #66/FULL/attestation state.
# The pinned fake dexdump used by the host selftest has a separate result status
# and can never emit a production compatibility candidate.

unset BASH_ENV ENV
unset DEVELOPER_DIR SDKROOT TOOLCHAINS
PATH=/usr/bin:/bin
export PATH
set -uo pipefail
umask 077

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
MEMBERS="$SELF_DIR/fixtures/issue66-services-compatibility/required-members.tsv"
APPROVED_DEXDUMP_DIGESTS="$SELF_DIR/fixtures/issue66-services-compatibility/approved-dexdump-sha256.tsv"
CHECKER_PATH="$SELF_DIR/check-issue66-services-compatibility.sh"
EXPECTED_REQUIRED_MEMBERS_SHA256="f67953df36dfbe0c5f2d687015c5f48f527d6c6cb0d9858b6edf2154b9709154"
EXPECTED_APPROVED_DEXDUMP_DIGESTS_SHA256="bce0868ba52870baf8d9b74fdfdf8f62a585b51b44291151d732912bd8d92a3a"
EXPECTED_SELFTEST_DEXDUMP_SHA256="dffe6aa7ee2e3a9ed4f85244fa291844903ec5bb33141a8a177f9b8524a0cab3"
readonly SERVICES_JAR_SIZE_LIMIT=134217728
readonly DEXDUMP_SIZE_LIMIT=67108864
readonly SUPPORT_FILE_SIZE_LIMIT=1048576
readonly SOURCE_PROPERTIES_SIZE_LIMIT=65536
readonly CHECKER_SIZE_LIMIT=4194304
readonly ARCHIVE_ENTRY_LIMIT=4096
readonly ARCHIVE_SINGLE_UNCOMPRESSED_LIMIT=268435456
readonly ARCHIVE_TOTAL_UNCOMPRESSED_LIMIT=536870912
readonly ARCHIVE_COMPRESSION_RATIO_LIMIT=100
readonly ARCHIVE_COMPRESSION_RATIO_SLACK=1048576
readonly PROD_DEXDUMP_TIMEOUT_SECONDS=120
readonly PROD_DEXDUMP_STDOUT_LIMIT=134217728
readonly PROD_DEXDUMP_STDERR_LIMIT=1048576
readonly SELFTEST_DEXDUMP_TIMEOUT_SECONDS=2
readonly SELFTEST_DEXDUMP_STDOUT_LIMIT=262144
readonly SELFTEST_DEXDUMP_STDERR_LIMIT=65536

SERVICES_JAR=""
DEXDUMP=""
OUTPUT=""
OUTPUT_RESERVED=0
ALLOW_PINNED_SELFTEST_FIXTURE=0
SELFTEST_OUTPUT_WRITE_FAILURE=0
SELFTEST_POST_ANALYSIS_GATE=0
SELFTEST_SOURCE_PROPERTIES_GATE=0
DEXDUMP_IDENTITY=""
DEXDUMP_BUILD_TOOLS_REVISION=""
SNAPSHOT_DIGEST=""
WORK=""

usage() {
  printf 'usage: %s --services-jar <path> --dexdump <path> --output <new-json> [--allow-pinned-selftest-fixture] [--selftest-output-write-failure] [--selftest-post-analysis-gate] [--selftest-source-properties-gate]\n' \
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
  /usr/bin/python3 -I - "$OUTPUT" <<'PY'
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
  /usr/bin/python3 -I - "$payload" <<'PY' || return 3
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

snapshot_bounded_file() { # source destination maximum-bytes
  /usr/bin/python3 -I - "$1" "$2" "$3" <<'PY'
import hashlib
import os
import stat
import sys

source_path, destination_path, raw_limit = sys.argv[1:]


class ResourceLimit(Exception):
    pass


def identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
        value.st_uid,
        value.st_gid,
        value.st_nlink,
        stat.S_IMODE(value.st_mode),
    )


source_descriptor = None
destination_descriptor = None
try:
    limit = int(raw_limit)
    if limit <= 0:
        raise OSError("invalid resource limit")
    named_before = os.lstat(source_path)
    if named_before.st_size > limit:
        raise ResourceLimit()
    source_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    source_descriptor = os.open(source_path, source_flags)
    opened = os.fstat(source_descriptor)
    if opened.st_size > limit:
        raise ResourceLimit()
    if (
        not stat.S_ISREG(named_before.st_mode)
        or not stat.S_ISREG(opened.st_mode)
        or identity(named_before) != identity(opened)
    ):
        raise OSError("source identity changed before snapshot")

    destination_flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    destination_descriptor = os.open(destination_path, destination_flags, 0o600)
    destination_opened = os.fstat(destination_descriptor)
    if (
        not stat.S_ISREG(destination_opened.st_mode)
        or destination_opened.st_uid != os.geteuid()
        or destination_opened.st_nlink != 1
        or stat.S_IMODE(destination_opened.st_mode) != 0o600
    ):
        raise OSError("unsafe snapshot destination")

    digest = hashlib.sha256()
    byte_count = 0
    while True:
        remaining = limit - byte_count
        chunk = os.read(source_descriptor, min(1024 * 1024, remaining + 1))
        if not chunk:
            break
        if len(chunk) > remaining:
            raise ResourceLimit()
        digest.update(chunk)
        view = memoryview(chunk)
        while view:
            written = os.write(destination_descriptor, view)
            if written <= 0 or written > len(view):
                raise OSError("snapshot write made invalid progress")
            view = view[written:]
        byte_count += len(chunk)
    os.fsync(destination_descriptor)

    opened_after = os.fstat(source_descriptor)
    named_after = os.lstat(source_path)
    destination_after = os.fstat(destination_descriptor)
    destination_named = os.lstat(destination_path)
    if (
        byte_count != opened_after.st_size
        or identity(named_before) != identity(opened_after)
        or identity(named_before) != identity(named_after)
        or destination_after.st_size != byte_count
        or identity(destination_opened)[:2] != identity(destination_after)[:2]
        or identity(destination_opened)[:2] != identity(destination_named)[:2]
        or destination_after.st_nlink != 1
        or destination_named.st_nlink != 1
        or stat.S_IMODE(destination_after.st_mode) != 0o600
        or stat.S_IMODE(destination_named.st_mode) != 0o600
    ):
        raise OSError("source or snapshot identity changed")
except ResourceLimit:
    raise SystemExit(20)
except (OSError, RuntimeError, ValueError):
    raise SystemExit(21)
finally:
    if source_descriptor is not None:
        os.close(source_descriptor)
    if destination_descriptor is not None:
        os.close(destination_descriptor)
print(digest.hexdigest())
PY
}

snapshot_or_stop() { # source destination maximum-bytes resource-limit-reason
  local source=$1 destination=$2 limit=$3 limit_reason=$4 result snapshot_rc
  result="$(snapshot_bounded_file "$source" "$destination" "$limit")"
  snapshot_rc=$?
  if (( snapshot_rc == 20 )); then
    emit_stop "$limit_reason"
    exit 21
  elif (( snapshot_rc == 21 )); then
    emit_stop INPUT_CHANGED
    exit 21
  elif (( snapshot_rc != 0 )) || [[ ! $result =~ ^[0-9a-f]{64}$ ]]; then
    emit_stop INTERNAL_ERROR
    exit 70
  fi
  SNAPSHOT_DIGEST=$result
}

verify_bounded_file() { # source maximum-bytes expected-sha256
  /usr/bin/python3 -I - "$1" "$2" "$3" <<'PY'
import hashlib
import os
import stat
import sys

path, raw_limit, expected_digest = sys.argv[1:]


def identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
        value.st_uid,
        value.st_gid,
        value.st_nlink,
        stat.S_IMODE(value.st_mode),
    )


descriptor = None
try:
    limit = int(raw_limit)
    named_before = os.lstat(path)
    if named_before.st_size > limit or not stat.S_ISREG(named_before.st_mode):
        raise OSError("source is no longer bounded and regular")
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    descriptor = os.open(path, flags)
    opened = os.fstat(descriptor)
    if identity(named_before) != identity(opened):
        raise OSError("source identity changed before verification")
    digest = hashlib.sha256()
    byte_count = 0
    while True:
        remaining = limit - byte_count
        chunk = os.read(descriptor, min(1024 * 1024, remaining + 1))
        if not chunk:
            break
        if len(chunk) > remaining:
            raise OSError("source grew beyond resource limit")
        digest.update(chunk)
        byte_count += len(chunk)
    opened_after = os.fstat(descriptor)
    named_after = os.lstat(path)
    if (
        byte_count != opened_after.st_size
        or identity(named_before) != identity(opened_after)
        or identity(named_before) != identity(named_after)
        or digest.hexdigest() != expected_digest
    ):
        raise OSError("source changed after snapshot")
except (OSError, RuntimeError, ValueError):
    raise SystemExit(1)
finally:
    if descriptor is not None:
        os.close(descriptor)
PY
}

supervise_dexdump() { # timeout stdout-limit stderr-limit stdout-path stderr-path executable dex identity
  /usr/bin/python3 -I - "$@" <<'PY'
import os
import selectors
import signal
import stat
import subprocess
import sys
import time

timeout_seconds = int(sys.argv[1])
limits = {"stdout": int(sys.argv[2]), "stderr": int(sys.argv[3])}
paths = {"stdout": sys.argv[4], "stderr": sys.argv[5]}
executable, dex_path, tool_identity = sys.argv[6:9]
process = None
selector = None
streams = {}
outputs = {}
output_identities = {}


def group_exists():
    if process is None:
        return False
    try:
        os.killpg(process.pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return False


def signal_group(value):
    if process is None:
        return
    try:
        os.killpg(process.pid, value)
    except (ProcessLookupError, PermissionError):
        pass


def bounded_group_stop():
    if process is None:
        return
    signal_group(signal.SIGTERM)
    grace_deadline = time.monotonic() + 0.25
    while time.monotonic() < grace_deadline:
        process.poll()
        if not group_exists():
            break
        time.sleep(0.01)
    if group_exists():
        signal_group(signal.SIGKILL)
        kill_deadline = time.monotonic() + 0.50
        while time.monotonic() < kill_deadline:
            process.poll()
            if not group_exists():
                break
            time.sleep(0.01)
    if group_exists():
        raise RuntimeError("dexdump process group survived SIGKILL")
    try:
        process.wait(timeout=0.75)
    except subprocess.TimeoutExpired:
        signal_group(signal.SIGKILL)
        process.wait(timeout=0.25)


def safe_output(name, path):
    flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    descriptor = os.open(path, flags, 0o600)
    opened = os.fstat(descriptor)
    visible = os.lstat(path)
    if (
        not stat.S_ISREG(opened.st_mode)
        or opened.st_uid != os.geteuid()
        or opened.st_nlink != 1
        or stat.S_IMODE(opened.st_mode) != 0o600
        or (opened.st_dev, opened.st_ino) != (visible.st_dev, visible.st_ino)
    ):
        os.close(descriptor)
        raise OSError(f"unsafe {name} output")
    output_identities[name] = (opened.st_dev, opened.st_ino)
    return os.fdopen(descriptor, "wb", buffering=0)


def write_all(output, value):
    remaining = memoryview(value)
    while remaining:
        written = output.write(remaining)
        if not isinstance(written, int) or written <= 0 or written > len(remaining):
            raise OSError("dexdump output made invalid write progress")
        remaining = remaining[written:]


outcome = "INTERNAL"
result_exit = 70
try:
    if (
        timeout_seconds <= 0
        or any(limit < 0 for limit in limits.values())
        or tool_identity
        not in {
            "PINNED_SELFTEST_FIXTURE",
            "ANDROID_SDK_BUILD_TOOLS_NATIVE_APPROVED_SHA256",
        }
    ):
        raise ValueError("invalid dexdump supervisor arguments")
    outputs = {name: safe_output(name, path) for name, path in paths.items()}
    child_env = {"PATH": "/usr/bin:/bin", "LC_ALL": "C", "LANG": "C"}
    if tool_identity == "PINNED_SELFTEST_FIXTURE":
        for name in (
            "FAKE_DEXDUMP_LOG",
            "FAKE_DEXDUMP_OUTPUT_SWAP_TARGET",
            "FAKE_DEXDUMP_OUTPUT_SWAP_MODE",
            "FAKE_DEXDUMP_OUTPUT_SWAP_VICTIM",
            "FAKE_DEXDUMP_OUTPUT_SWAP_STATE",
            "FAKE_DEXDUMP_LATE_WRITE_MARKER",
        ):
            if name in os.environ:
                child_env[name] = os.environ[name]
    process = subprocess.Popen(
        [executable, "-d", dex_path],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        shell=False,
        start_new_session=True,
        env=child_env,
        close_fds=True,
    )
    selector = selectors.DefaultSelector()
    for name, stream in (("stdout", process.stdout), ("stderr", process.stderr)):
        if stream is None:
            raise OSError(f"missing {name} pipe")
        os.set_blocking(stream.fileno(), False)
        selector.register(stream, selectors.EVENT_READ, name)
        streams[name] = stream
    totals = {"stdout": 0, "stderr": 0}
    deadline = time.monotonic() + timeout_seconds
    outcome = None
    while selector.get_map() or process.poll() is None:
        remaining_time = deadline - time.monotonic()
        if remaining_time <= 0:
            outcome = "TIMEOUT"
            result_exit = 124
            break
        if selector.get_map():
            events = selector.select(min(remaining_time, 0.10))
        else:
            time.sleep(min(remaining_time, 0.01))
            events = ()
        for key, _mask in events:
            name = key.data
            try:
                chunk = os.read(key.fileobj.fileno(), 65536)
            except BlockingIOError:
                continue
            if not chunk:
                selector.unregister(key.fileobj)
                key.fileobj.close()
                continue
            available = limits[name] - totals[name]
            if available > 0:
                accepted = chunk[:available]
                write_all(outputs[name], accepted)
                totals[name] += len(accepted)
            if len(chunk) > available:
                outcome = "STDOUT_LIMIT" if name == "stdout" else "STDERR_LIMIT"
                result_exit = 125 if name == "stdout" else 126
                break
        if outcome is not None:
            break
    if outcome is None:
        try:
            child_exit = process.wait(timeout=0.25)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError("dexdump remained alive after both pipes closed") from error
        if group_exists():
            outcome = "PROCESS_GROUP"
            result_exit = 127
            bounded_group_stop()
        else:
            outcome = "OK"
            result_exit = child_exit if child_exit >= 0 else 128 - child_exit
    else:
        bounded_group_stop()
except Exception:
    outcome = "INTERNAL"
    result_exit = 70
    try:
        bounded_group_stop()
    except Exception:
        pass
finally:
    if selector is not None:
        try:
            selector.close()
        except Exception:
            pass
    for stream in streams.values():
        try:
            stream.close()
        except Exception:
            pass
    for name, output in outputs.items():
        try:
            os.fsync(output.fileno())
            opened = os.fstat(output.fileno())
            visible = os.lstat(paths[name])
            if (
                (opened.st_dev, opened.st_ino) != output_identities[name]
                or (visible.st_dev, visible.st_ino) != output_identities[name]
                or opened.st_nlink != 1
                or visible.st_nlink != 1
                or opened.st_size > limits[name]
                or stat.S_IMODE(opened.st_mode) != 0o600
                or stat.S_IMODE(visible.st_mode) != 0o600
            ):
                raise OSError("dexdump output identity changed")
            output.close()
        except Exception:
            outcome = "INTERNAL"
            result_exit = 70
print(f"{outcome}\t{result_exit}")
PY
}

selftest_post_analysis_gate() {
  (( SELFTEST_POST_ANALYSIS_GATE )) \
    && [[ $DEXDUMP_IDENTITY == PINNED_SELFTEST_FIXTURE ]] \
    || return 1
  # The gate is data-only and failure-only: create one new sibling state file,
  # publish a fixed token, and wait for the device-free selftest to acknowledge
  # its input replacement. No caller-provided executable is dispatched.
  /usr/bin/python3 -I - "${OUTPUT}.selftest-post-analysis.state" <<'PY'
import os
import stat
import sys
import time

path = os.fsencode(sys.argv[1])
flags = os.O_RDWR | os.O_CREAT | os.O_EXCL
flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
try:
    descriptor = os.open(path, flags, 0o600)
except OSError:
    raise SystemExit(1)
try:
    opened = os.fstat(descriptor)
    visible = os.lstat(path)
    if (
        not stat.S_ISREG(opened.st_mode)
        or not stat.S_ISREG(visible.st_mode)
        or (opened.st_dev, opened.st_ino) != (visible.st_dev, visible.st_ino)
        or opened.st_uid != os.geteuid()
        or stat.S_IMODE(opened.st_mode) != 0o600
    ):
        raise OSError("unsafe selftest gate")
    os.write(descriptor, b"hash-ready\n")
    os.fsync(descriptor)
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        os.lseek(descriptor, 0, os.SEEK_SET)
        if os.read(descriptor, 32) == b"swapped\n":
            confirmed = os.fstat(descriptor)
            visible = os.lstat(path)
            if (
                (confirmed.st_dev, confirmed.st_ino) != (opened.st_dev, opened.st_ino)
                or (visible.st_dev, visible.st_ino) != (opened.st_dev, opened.st_ino)
                or stat.S_IMODE(confirmed.st_mode) != 0o600
            ):
                raise OSError("selftest gate identity changed")
            break
        time.sleep(0.001)
    else:
        raise OSError("selftest gate timeout")
except OSError:
    raise SystemExit(1)
finally:
    os.close(descriptor)
PY
}

validate_dexdump_identity() { # original-path snapshot-path sha256 approved-digests
  local path=$1 snapshot=$2 digest=$3 approved_digests=$4 identity_result verdict revision
  if (( ALLOW_PINNED_SELFTEST_FIXTURE )) \
      && [[ $digest == "$EXPECTED_SELFTEST_DEXDUMP_SHA256" ]]; then
    DEXDUMP_IDENTITY="PINNED_SELFTEST_FIXTURE"
    DEXDUMP_BUILD_TOOLS_REVISION="SELFTEST"
    return 0
  fi

  identity_result="$(/usr/bin/python3 -I - \
      "$path" "$snapshot" "$digest" "$approved_digests" \
      "$SELFTEST_SOURCE_PROPERTIES_GATE" \
      "${OUTPUT}.selftest-source-properties.state" \
      "$SOURCE_PROPERTIES_SIZE_LIMIT" <<'PY'
import os
import pathlib
import pwd
import stat
import sys
import time

tool = pathlib.Path(sys.argv[1])
snapshot = pathlib.Path(sys.argv[2])
digest = sys.argv[3]
approved_path = pathlib.Path(sys.argv[4])
selftest_source_properties_gate_enabled = sys.argv[5] == "1"
selftest_source_properties_gate_path = pathlib.Path(sys.argv[6])
source_properties_size_limit = int(sys.argv[7])


def await_selftest_source_properties_swap():
    if not selftest_source_properties_gate_enabled:
        return
    flags = os.O_RDWR | os.O_CREAT | os.O_EXCL
    flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = None
    try:
        descriptor = os.open(selftest_source_properties_gate_path, flags, 0o600)
        opened = os.fstat(descriptor)
        visible = os.lstat(selftest_source_properties_gate_path)
        if (
            not stat.S_ISREG(opened.st_mode)
            or (opened.st_dev, opened.st_ino) != (visible.st_dev, visible.st_ino)
            or opened.st_uid != os.geteuid()
            or opened.st_nlink != 1
            or stat.S_IMODE(opened.st_mode) != 0o600
        ):
            raise OSError("unsafe source.properties selftest gate")
        os.write(descriptor, b"lstat-ready\n")
        os.fsync(descriptor)
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            os.lseek(descriptor, 0, os.SEEK_SET)
            if os.read(descriptor, 32) == b"swapped\n":
                confirmed = os.fstat(descriptor)
                visible = os.lstat(selftest_source_properties_gate_path)
                if (
                    (confirmed.st_dev, confirmed.st_ino)
                    != (opened.st_dev, opened.st_ino)
                    or (visible.st_dev, visible.st_ino)
                    != (opened.st_dev, opened.st_ino)
                    or stat.S_IMODE(confirmed.st_mode) != 0o600
                ):
                    raise OSError("source.properties selftest gate changed")
                return
            time.sleep(0.001)
        raise OSError("source.properties selftest gate timeout")
    finally:
        if descriptor is not None:
            os.close(descriptor)


def file_identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
        value.st_uid,
        value.st_gid,
        value.st_nlink,
        stat.S_IMODE(value.st_mode),
    )


def read_bounded(descriptor, limit):
    chunks = []
    byte_count = 0
    while True:
        remaining = limit - byte_count
        chunk = os.read(descriptor, min(65536, remaining + 1))
        if not chunk:
            break
        if len(chunk) > remaining:
            raise OSError("source.properties exceeds fixed limit")
        chunks.append(chunk)
        byte_count += len(chunk)
    return b"".join(chunks)


def read_stable_source_properties(path, named_before, limit):
    if (
        limit <= 0
        or not hasattr(os, "O_NOFOLLOW")
        or not hasattr(os, "O_NONBLOCK")
    ):
        raise OSError("source.properties safety flags unavailable")
    if (
        not stat.S_ISREG(named_before.st_mode)
        or stat.S_ISLNK(named_before.st_mode)
        or named_before.st_uid not in {0, os.getuid()}
        or named_before.st_mode & 0o022
        or named_before.st_nlink != 1
        or named_before.st_size > limit
    ):
        raise OSError("unsafe source.properties metadata")
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    flags |= getattr(os, "O_CLOEXEC", 0)
    first_descriptor = None
    second_descriptor = None
    try:
        first_descriptor = os.open(path, flags)
        first_opened = os.fstat(first_descriptor)
        if (
            not stat.S_ISREG(first_opened.st_mode)
            or file_identity(first_opened) != file_identity(named_before)
        ):
            raise OSError("source.properties identity changed before read")
        first_bytes = read_bounded(first_descriptor, limit)
        first_after = os.fstat(first_descriptor)
        named_after_first_read = os.lstat(path)
        if (
            len(first_bytes) != first_after.st_size
            or file_identity(first_after) != file_identity(named_before)
            or file_identity(named_after_first_read) != file_identity(named_before)
        ):
            raise OSError("source.properties changed during read")

        second_descriptor = os.open(path, flags)
        second_opened = os.fstat(second_descriptor)
        if file_identity(second_opened) != file_identity(named_before):
            raise OSError("source.properties reopen selected another identity")
        second_bytes = read_bounded(second_descriptor, limit)
        second_after = os.fstat(second_descriptor)
        named_after_reopen = os.lstat(path)
        if (
            second_bytes != first_bytes
            or len(second_bytes) != second_after.st_size
            or file_identity(second_after) != file_identity(named_before)
            or file_identity(named_after_reopen) != file_identity(named_before)
        ):
            raise OSError("source.properties changed across reopen")
        return first_bytes.decode("utf-8")
    finally:
        if second_descriptor is not None:
            os.close(second_descriptor)
        if first_descriptor is not None:
            os.close(first_descriptor)


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
    await_selftest_source_properties_swap()
    properties_text = read_stable_source_properties(
        properties, properties_info, source_properties_size_limit
    )
except (OSError, UnicodeError):
    raise SystemExit(1)
values = {"Pkg.UserSrc": None, "Pkg.Revision": None}
for raw_line in properties_text.splitlines():
    line = raw_line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    key = key.strip()
    if key in values:
        if values[key] is not None:
            raise SystemExit(1)
        values[key] = value.strip()
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
      --selftest-post-analysis-gate)
        (( SELFTEST_POST_ANALYSIS_GATE == 0 )) || return 2
        SELFTEST_POST_ANALYSIS_GATE=1
        shift
        ;;
      --selftest-source-properties-gate)
        (( SELFTEST_SOURCE_PROPERTIES_GATE == 0 )) || return 2
        SELFTEST_SOURCE_PROPERTIES_GATE=1
        shift
        ;;
      *) return 2 ;;
    esac
  done
  [[ -n $SERVICES_JAR && -n $DEXDUMP && -n $OUTPUT ]] || return 2
  (( SELFTEST_OUTPUT_WRITE_FAILURE == 0 || ALLOW_PINNED_SELFTEST_FIXTURE == 1 )) \
    || return 2
  (( SELFTEST_POST_ANALYSIS_GATE == 0 || ALLOW_PINNED_SELFTEST_FIXTURE == 1 )) \
    || return 2
  (( SELFTEST_SOURCE_PROPERTIES_GATE == 0 || ALLOW_PINNED_SELFTEST_FIXTURE == 1 )) \
    || return 2
  (( SELFTEST_POST_ANALYSIS_GATE == 0 || SELFTEST_OUTPUT_WRITE_FAILURE == 0 )) \
    || return 2
  (( SELFTEST_SOURCE_PROPERTIES_GATE == 0 || SELFTEST_OUTPUT_WRITE_FAILURE == 0 )) \
    || return 2
  (( SELFTEST_SOURCE_PROPERTIES_GATE == 0 || SELFTEST_POST_ANALYSIS_GATE == 0 )) \
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
  local checker_snapshot="$WORK/checker.sh"
  local services_before dexdump_before members_before approved_digests_before checker_before
  snapshot_or_stop "$SERVICES_JAR" "$jar_snapshot" \
    "$SERVICES_JAR_SIZE_LIMIT" SERVICES_JAR_SIZE_LIMIT
  services_before=$SNAPSHOT_DIGEST
  snapshot_or_stop "$DEXDUMP" "$dexdump_snapshot" \
    "$DEXDUMP_SIZE_LIMIT" DEXDUMP_SIZE_LIMIT
  dexdump_before=$SNAPSHOT_DIGEST
  snapshot_or_stop "$MEMBERS" "$members_snapshot" \
    "$SUPPORT_FILE_SIZE_LIMIT" REQUIRED_MEMBERS_SIZE_LIMIT
  members_before=$SNAPSHOT_DIGEST
  snapshot_or_stop "$APPROVED_DEXDUMP_DIGESTS" "$approved_digests_snapshot" \
    "$SUPPORT_FILE_SIZE_LIMIT" APPROVED_DEXDUMP_ALLOWLIST_SIZE_LIMIT
  approved_digests_before=$SNAPSHOT_DIGEST
  snapshot_or_stop "$CHECKER_PATH" "$checker_snapshot" \
    "$CHECKER_SIZE_LIMIT" CHECKER_SIZE_LIMIT
  checker_before=$SNAPSHOT_DIGEST
  if [[ $members_before != "$EXPECTED_REQUIRED_MEMBERS_SHA256" ]]; then
    emit_stop REQUIRED_MEMBERS_MISMATCH
    exit 21
  fi
  if [[ $approved_digests_before != "$EXPECTED_APPROVED_DEXDUMP_DIGESTS_SHA256" ]]; then
    emit_stop APPROVED_DEXDUMP_ALLOWLIST_MISMATCH
    exit 21
  fi
  chmod 500 "$dexdump_snapshot" || { emit_stop INTERNAL_ERROR; exit 70; }
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
  if (( SELFTEST_SOURCE_PROPERTIES_GATE )); then
    # This data-only race hook must remain failure-only even after a production
    # dexdump digest is eventually approved.
    emit_stop UNTRUSTED_DEXDUMP
    exit 22
  fi
  if (( SELFTEST_POST_ANALYSIS_GATE )) \
      && [[ $DEXDUMP_IDENTITY != PINNED_SELFTEST_FIXTURE ]]; then
    emit_stop UNTRUSTED_DEXDUMP
    exit 22
  fi

  local dex="$WORK/classes.dex" structure="$WORK/structure.txt"
  if ! /usr/bin/python3 -I - \
      "$jar_snapshot" "$dex" \
      "$SERVICES_JAR_SIZE_LIMIT" "$ARCHIVE_ENTRY_LIMIT" \
      "$ARCHIVE_SINGLE_UNCOMPRESSED_LIMIT" \
      "$ARCHIVE_TOTAL_UNCOMPRESSED_LIMIT" \
      "$ARCHIVE_COMPRESSION_RATIO_LIMIT" \
      "$ARCHIVE_COMPRESSION_RATIO_SLACK" >"$structure" <<'PY'
import os
import re
import stat
import struct
import sys
import zipfile

jar_path, dex_path = sys.argv[1:3]
archive_size_limit = int(sys.argv[3])
entry_limit = int(sys.argv[4])
single_limit = int(sys.argv[5])
total_limit = int(sys.argv[6])
ratio_limit = int(sys.argv[7])
ratio_slack = int(sys.argv[8])


class ArchiveLimit(Exception):
    pass


def identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
        value.st_uid,
        value.st_gid,
        value.st_nlink,
        stat.S_IMODE(value.st_mode),
    )


def directory_preflight(descriptor, archive_size):
    def exact(offset, length):
        if offset < 0 or length < 0 or offset + length > archive_size:
            return None
        value = os.pread(descriptor, length, offset)
        return value if len(value) == length else None

    if archive_size < 22:
        return "INVALID"
    tail_size = min(archive_size, 22 + 65535)
    tail_offset = archive_size - tail_size
    tail = exact(tail_offset, tail_size)
    if tail is None:
        return "INVALID"
    eocd = None
    search_from = 0
    while True:
        position = tail.find(b"PK\x05\x06", search_from)
        if position < 0:
            break
        if position + 22 <= len(tail):
            fields = struct.unpack_from("<4s4H2LH", tail, position)
            if tail_offset + position + 22 + fields[7] == archive_size:
                if eocd is not None:
                    return "INVALID"
                eocd = (tail_offset + position, fields)
        search_from = position + 1
    if eocd is None:
        return "INVALID"

    eocd_offset, fields = eocd
    (
        _signature,
        disk,
        directory_disk,
        disk_entries,
        entries,
        directory_size,
        directory_offset,
        _comment,
    ) = fields
    needs_zip64 = (
        disk == 0xFFFF
        or directory_disk == 0xFFFF
        or disk_entries == 0xFFFF
        or entries == 0xFFFF
        or directory_size == 0xFFFFFFFF
        or directory_offset == 0xFFFFFFFF
    )
    directory_boundary = eocd_offset
    if needs_zip64:
        locator_offset = eocd_offset - 20
        locator = exact(locator_offset, 20)
        if locator is None:
            return "INVALID"
        locator_signature, zip64_disk, zip64_offset, total_disks = struct.unpack(
            "<4sLQL", locator
        )
        if locator_signature != b"PK\x06\x07" or zip64_disk != 0 or total_disks != 1:
            return "INVALID"
        zip64_header = exact(zip64_offset, 56)
        if zip64_header is None:
            return "INVALID"
        zip64_fields = struct.unpack("<4sQ2H2L4Q", zip64_header)
        (
            zip64_signature,
            zip64_record_size,
            _made_by,
            _needed,
            actual_disk,
            actual_directory_disk,
            actual_disk_entries,
            actual_entries,
            actual_directory_size,
            actual_directory_offset,
        ) = zip64_fields
        if (
            zip64_signature != b"PK\x06\x06"
            or zip64_record_size < 44
            or zip64_offset + 12 + zip64_record_size != locator_offset
            or actual_disk != 0
            or actual_directory_disk != 0
            or actual_disk_entries != actual_entries
        ):
            return "INVALID"
        legacy_pairs = (
            (disk, 0xFFFF, actual_disk),
            (directory_disk, 0xFFFF, actual_directory_disk),
            (disk_entries, 0xFFFF, actual_disk_entries),
            (entries, 0xFFFF, actual_entries),
            (directory_size, 0xFFFFFFFF, actual_directory_size),
            (directory_offset, 0xFFFFFFFF, actual_directory_offset),
        )
        if any(
            legacy not in {sentinel, actual}
            for legacy, sentinel, actual in legacy_pairs
        ):
            return "INVALID"
        entries = actual_entries
        directory_size = actual_directory_size
        directory_offset = actual_directory_offset
        directory_boundary = zip64_offset
    elif disk != 0 or directory_disk != 0 or disk_entries != entries:
        return "INVALID"

    if entries > entry_limit:
        return "LIMIT"
    directory_end = directory_offset + directory_size
    if directory_end != directory_boundary or directory_end > archive_size:
        return "INVALID"
    cursor = directory_offset
    actual_entries = 0
    while cursor < directory_end:
        header = exact(cursor, 46)
        if header is None or header[:4] != b"PK\x01\x02":
            return "INVALID"
        central = struct.unpack("<4s6H3L5H2L", header)
        record_size = 46 + central[10] + central[11] + central[12]
        if record_size < 46 or cursor + record_size > directory_end:
            return "INVALID"
        actual_entries += 1
        if actual_entries > entry_limit:
            return "LIMIT"
        cursor += record_size
    if cursor != directory_end or actual_entries != entries:
        return "INVALID"
    return "OK"


def metadata_within_limits(members):
    if len(members) > entry_limit:
        return False
    total_size = 0
    total_compressed_size = 0
    for member in members:
        if member.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
            return False
        if member.file_size < 0 or member.compress_size < 0:
            return False
        if member.file_size > single_limit:
            return False
        total_size += member.file_size
        total_compressed_size += member.compress_size
        if total_size > total_limit:
            return False
        if member.file_size > member.compress_size * ratio_limit + ratio_slack:
            return False
    return total_size <= total_compressed_size * ratio_limit + ratio_slack


descriptor = None
dex_descriptor = None
try:
    named_before = os.lstat(jar_path)
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    descriptor = os.open(jar_path, flags)
    opened = os.fstat(descriptor)
    if (
        not stat.S_ISREG(named_before.st_mode)
        or not stat.S_ISREG(opened.st_mode)
        or identity(named_before) != identity(opened)
        or opened.st_size > archive_size_limit
    ):
        raise OSError("unsafe services.jar snapshot")
    preflight = directory_preflight(descriptor, opened.st_size)
    if preflight == "LIMIT":
        raise ArchiveLimit()
    if preflight != "OK":
        raise zipfile.BadZipFile("central directory boundary mismatch")

    with os.fdopen(os.dup(descriptor), "rb") as archive_stream:
      with zipfile.ZipFile(archive_stream) as archive:
        members = archive.infolist()
        if not metadata_within_limits(members):
            raise ArchiveLimit()
        if any(member.filename != member.orig_filename for member in members):
            raise zipfile.BadZipFile("ambiguous archive member name")
        names = [member.orig_filename for member in members]
        if not members or len(names) != len(set(names)):
            raise zipfile.BadZipFile("empty archive or duplicate member name")
        for name in names:
            parts = name.split("/")
            if not name or name.startswith("/") or ".." in parts or "\x00" in name:
                raise zipfile.BadZipFile("unsafe archive member name")
        dex_members = [
            member
            for member in members
            if re.fullmatch(r"classes(?:\d+)?\.dex", member.orig_filename)
        ]
        dex_names = [member.orig_filename for member in dex_members]
        if not dex_names:
            print("NO_DEX")
            raise SystemExit(10)
        if len(dex_names) != 1:
            print("MULTIPLE_DEX")
            raise SystemExit(11)
        dex_flags = (
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        dex_descriptor = os.open(dex_path, dex_flags, 0o600)
        dex_opened = os.fstat(dex_descriptor)
        if (
            not stat.S_ISREG(dex_opened.st_mode)
            or dex_opened.st_uid != os.geteuid()
            or dex_opened.st_nlink != 1
            or stat.S_IMODE(dex_opened.st_mode) != 0o600
        ):
            raise OSError("unsafe dex destination")
        byte_count = 0
        with archive.open(dex_members[0], "r") as source:
            while True:
                remaining = single_limit - byte_count
                chunk = source.read(min(65536, remaining + 1))
                if not chunk:
                    break
                if len(chunk) > remaining:
                    raise ArchiveLimit()
                view = memoryview(chunk)
                while view:
                    written = os.write(dex_descriptor, view)
                    if written <= 0 or written > len(view):
                        raise OSError("dex write made invalid progress")
                    view = view[written:]
                byte_count += len(chunk)
        if byte_count == 0:
            print("EMPTY_DEX")
            raise SystemExit(13)
        if byte_count != dex_members[0].file_size:
            raise zipfile.BadZipFile("dex size differs from central directory")
        os.fsync(dex_descriptor)

    opened_after = os.fstat(descriptor)
    named_after = os.lstat(jar_path)
    dex_after = os.fstat(dex_descriptor)
    dex_named = os.lstat(dex_path)
    if (
        identity(named_before) != identity(opened_after)
        or identity(named_before) != identity(named_after)
        or (dex_after.st_dev, dex_after.st_ino) != (dex_named.st_dev, dex_named.st_ino)
        or dex_after.st_nlink != 1
        or dex_named.st_nlink != 1
        or dex_after.st_size != byte_count
        or stat.S_IMODE(dex_after.st_mode) != 0o600
        or stat.S_IMODE(dex_named.st_mode) != 0o600
    ):
        raise OSError("archive or dex identity changed")
except ArchiveLimit:
    print("RESOURCE_LIMIT")
    raise SystemExit(14)
except (OSError, RuntimeError, NotImplementedError, zipfile.BadZipFile, zipfile.LargeZipFile, KeyError) as error:
    print(f"INVALID_JAR:{error}")
    raise SystemExit(12)
finally:
    if dex_descriptor is not None:
        os.close(dex_descriptor)
    if descriptor is not None:
        os.close(descriptor)
print("ONE_DEX")
PY
  then
    local structure_reason
    structure_reason="$(sed -n '1p' "$structure")"
    case "$structure_reason" in
      NO_DEX) emit_stop NO_DEX ;;
      MULTIPLE_DEX) emit_stop MULTIPLE_DEX ;;
      EMPTY_DEX) emit_stop EMPTY_DEX ;;
      RESOURCE_LIMIT) emit_stop SERVICES_ARCHIVE_LIMIT ;;
      *) emit_stop INVALID_JAR ;;
    esac
    exit 21
  fi

  local dump_out="$WORK/dexdump.stdout" dump_err="$WORK/dexdump.stderr"
  local timeout_seconds stdout_limit stderr_limit supervisor_result supervisor_rc
  if [[ $DEXDUMP_IDENTITY == PINNED_SELFTEST_FIXTURE ]]; then
    timeout_seconds=$SELFTEST_DEXDUMP_TIMEOUT_SECONDS
    stdout_limit=$SELFTEST_DEXDUMP_STDOUT_LIMIT
    stderr_limit=$SELFTEST_DEXDUMP_STDERR_LIMIT
  else
    timeout_seconds=$PROD_DEXDUMP_TIMEOUT_SECONDS
    stdout_limit=$PROD_DEXDUMP_STDOUT_LIMIT
    stderr_limit=$PROD_DEXDUMP_STDERR_LIMIT
  fi
  supervisor_result="$(supervise_dexdump \
    "$timeout_seconds" "$stdout_limit" "$stderr_limit" \
    "$dump_out" "$dump_err" "$dexdump_snapshot" "$dex" \
    "$DEXDUMP_IDENTITY")"
  supervisor_rc=$?
  if (( supervisor_rc != 0 )) || [[ $supervisor_result == *$'\n'* ]]; then
    emit_stop INTERNAL_ERROR
    exit 70
  fi
  local supervisor_status dump_rc
  IFS=$'\t' read -r supervisor_status dump_rc <<<"$supervisor_result"
  case "$supervisor_status" in
    TIMEOUT) emit_stop DEXDUMP_TIMEOUT; exit 21 ;;
    STDOUT_LIMIT) emit_stop DEXDUMP_STDOUT_LIMIT; exit 21 ;;
    STDERR_LIMIT) emit_stop DEXDUMP_STDERR_LIMIT; exit 21 ;;
    PROCESS_GROUP) emit_stop DEXDUMP_PROCESS_GROUP; exit 21 ;;
    OK)
      if [[ ! $dump_rc =~ ^[0-9]+$ ]] || (( dump_rc != 0 )); then
        emit_stop DEXDUMP_FAILED
        exit 21
      fi
      ;;
    *) emit_stop INTERNAL_ERROR; exit 70 ;;
  esac

  local result="$WORK/result.txt"
  if ! /usr/bin/python3 -I - "$dump_out" "$members_snapshot" >"$result" <<'PY'
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

required_methods = frozenset(required)
required_classes = frozenset(class_name for class_name, _ in required_methods)
required_descriptors = {
    class_name.replace(".", "/"): class_name for class_name in required_classes
}
found_classes = set()
found_methods = set()
current_class = None
method_section = False
maximum_line_bytes = 65536
descriptor_re = re.compile(r"Class descriptor\s*:\s*'L([^;]+);'")
name_re = re.compile(r"^\s+name\s*:\s*'([^']+)'\s*$")
section_re = re.compile(r"^\s{2}(?:[A-Za-z][A-Za-z ]+)\s+-\s*$")
with open(dump_path, "rb") as stream:
    while True:
        encoded = stream.readline(maximum_line_bytes + 1)
        if not encoded:
            break
        if len(encoded) > maximum_line_bytes:
            raise ValueError("dexdump line exceeds fixed parser limit")
        raw = encoded.decode("utf-8", errors="replace")
        match = descriptor_re.search(raw)
        if match:
            current_class = required_descriptors.get(match.group(1))
            if current_class in required_classes:
                found_classes.add(current_class)
            method_section = False
            continue
        if section_re.match(raw):
            method_section = "methods" in raw.lower()
            continue
        if method_section and current_class:
            match = name_re.match(raw)
            if match:
                candidate = (current_class, match.group(1))
                if candidate in required_methods:
                    found_methods.add(candidate)

for class_name, _ in required:
    if class_name not in found_classes:
        print("MISSING_CLASS", class_name, sep="\t")
        raise SystemExit(20)
for class_name, method_name in required:
    if (class_name, method_name) not in found_methods:
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

  if (( SELFTEST_POST_ANALYSIS_GATE )) && ! selftest_post_analysis_gate; then
    emit_stop INTERNAL_ERROR
    exit 70
  fi

  if ! verify_bounded_file "$SERVICES_JAR" \
      "$SERVICES_JAR_SIZE_LIMIT" "$services_before" \
      || ! verify_bounded_file "$DEXDUMP" \
      "$DEXDUMP_SIZE_LIMIT" "$dexdump_before" \
      || ! verify_bounded_file "$MEMBERS" \
      "$SUPPORT_FILE_SIZE_LIMIT" "$members_before" \
      || ! verify_bounded_file "$APPROVED_DEXDUMP_DIGESTS" \
      "$SUPPORT_FILE_SIZE_LIMIT" "$approved_digests_before" \
      || ! verify_bounded_file "$CHECKER_PATH" \
      "$CHECKER_SIZE_LIMIT" "$checker_before"; then
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

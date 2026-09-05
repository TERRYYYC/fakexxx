#!/bin/bash -p
# Operational-read-only, exact-device preflight for issue #66.
#
# This first gate binds all device reads to the sole authorized Moto serial,
# refuses ambiguous inventories, writes a STOP manifest before any device
# evidence is interpreted, and routes every adb invocation through one exact
# allowlist. It never installs, clears, stops, configures, registers, restarts,
# reboots, or toggles device state.

unset BASH_ENV ENV DEVELOPER_DIR SDKROOT TOOLCHAINS
set -uo pipefail
umask 077

# Production must not resolve reviewed host-side operations through a caller-
# controlled PATH. The internal fake-ADB lane deliberately keeps its injected
# poison PATH so the selftest can prove that no bare `adb` invocation escapes.
STARTUP_SELFTEST_FIXTURE=0
for startup_argument in "$@"; do
  case "$startup_argument" in
    --) break ;;
    --selftest-fixture) STARTUP_SELFTEST_FIXTURE=1 ;;
  esac
done
if (( STARTUP_SELFTEST_FIXTURE == 0 )); then
  PATH=/usr/bin:/bin
  export PATH
fi
unset startup_argument

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
GIT_BIN="/usr/bin/git"
ADB_ALLOWLIST_PATH="$SELF_DIR/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
ADB_ALLOWLIST_EXPECTED_SHA256="92fe765782212bbd51536110a4023e4eb75472d0ce9ea1446c54a013653cea49"

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
REVIEWED_HEAD=""
REVIEWED_COLLECTOR_SHA256=""
SOURCE_HEAD=""
EVIDENCE_READY=0
LAST_STDOUT=""
LAST_RC=0
ADB_SHA256=""
ADB_SNAPSHOT_IDENTITY=""
COLLECTOR_SHA256=""
COLLECTOR_SOURCE_IDENTITY=""
RECEIPT_TREE_SHA256=""

# Resource ceilings are part of the reviewed collector, not caller policy.
# The compact SELFTEST lane keeps the same behavior while making boundary and
# timeout regressions practical to exercise on a host with no device access.
readonly PROD_TEXT_TIMEOUT_SECONDS=30
readonly PROD_TEXT_STDOUT_LIMIT=4194304
readonly PROD_BINARY_APK_TIMEOUT_SECONDS=180
readonly PROD_BINARY_APK_STDOUT_LIMIT=268435456
readonly PROD_BINARY_SERVICES_TIMEOUT_SECONDS=120
readonly PROD_BINARY_SERVICES_STDOUT_LIMIT=134217728
readonly PROD_STDERR_LIMIT=1048576
readonly SELFTEST_TIMEOUT_SECONDS=2
readonly SELFTEST_TEXT_STDOUT_LIMIT=65536
readonly SELFTEST_APK_STDOUT_LIMIT=3145728
readonly SELFTEST_SERVICES_STDOUT_LIMIT=2097152
readonly SELFTEST_STDERR_LIMIT=32768
readonly APK_ARCHIVE_MEMBER_LIMIT=16384
readonly FRAMEWORK_ARCHIVE_MEMBER_LIMIT=4096
readonly ARCHIVE_SINGLE_UNCOMPRESSED_LIMIT=268435456
readonly ARCHIVE_TOTAL_UNCOMPRESSED_LIMIT=536870912
readonly ARCHIVE_RATIO_LIMIT=100
readonly ARCHIVE_RATIO_SLACK=1048576
readonly ADB_SNAPSHOT_SIZE_LIMIT=67108864
readonly ADB_ALLOWLIST_SIZE_LIMIT=65536
readonly COLLECTOR_SOURCE_SIZE_LIMIT=2097152
# Offline verification retains only bounded control-plane bytes. APK and
# services.jar payloads are validated and hashed one descriptor at a time.
readonly OFFLINE_RETAINED_CONTROL_LIMIT=67108864

usage() {
  cat >&2 <<'EOF'
usage:
  collect-issue66-moto-readonly-preflight.sh \
    --reviewed-head <40-lowercase-hex> \
    --reviewed-collector-sha256 <64-lowercase-hex> \
    --adb <absolute-path> --serial ZY22JHW9M4 --output <new-absolute-directory>
  collect-issue66-moto-readonly-preflight.sh \
    --adb <absolute-path> --classify-adb -- <adb arguments...>
  collect-issue66-moto-readonly-preflight.sh \
    --reviewed-head <40-lowercase-hex> \
    --reviewed-collector-sha256 <64-lowercase-hex> \
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

stable_collector_binding() { # stable SHA-256 + inode state for the exact entrypoint
  "$PYTHON_BIN" -I - "$COLLECTOR_PATH" "$REPO_ROOT" \
    "$COLLECTOR_SOURCE_SIZE_LIMIT" <<'PY'
import ctypes
import errno
import hashlib
import os
import pathlib
import stat
import sys

path = pathlib.Path(sys.argv[1])
repo_root = pathlib.Path(sys.argv[2])

MAX_COLLECTOR_SIZE = int(sys.argv[3])


def unsupported_acl_errnos():
    return {
        getattr(errno, name)
        for name in ("ENOTSUP", "EOPNOTSUPP")
        if hasattr(errno, name)
    }


def fd_has_extended_acl(descriptor_fd):
    """Return whether the descriptor ACL can grant write/rebind authority."""
    if sys.platform == "darwin":
        library = ctypes.CDLL(None, use_errno=True)
        library.acl_get_fd_np.argtypes = [ctypes.c_int, ctypes.c_int]
        library.acl_get_fd_np.restype = ctypes.c_void_p
        library.acl_get_entry.argtypes = [
            ctypes.c_void_p,
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_void_p),
        ]
        library.acl_get_entry.restype = ctypes.c_int
        library.acl_get_tag_type.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_int),
        ]
        library.acl_get_tag_type.restype = ctypes.c_int
        library.acl_free.argtypes = [ctypes.c_void_p]
        library.acl_free.restype = ctypes.c_int

        ctypes.set_errno(0)
        acl = library.acl_get_fd_np(descriptor_fd, 0x100)  # ACL_TYPE_EXTENDED
        if not acl:
            error_number = ctypes.get_errno()
            if error_number == errno.ENOENT or error_number in unsupported_acl_errnos():
                return False
            raise OSError(error_number, os.strerror(error_number))

        # Darwin's normal user-home chain can carry system-installed deny-only
        # ACLs (for example, `everyone deny delete`). Those entries cannot grant
        # replacement authority. Any ALLOW entry is unsafe, and unknown tags or
        # iteration failures fail closed.
        ACL_FIRST_ENTRY = 0
        ACL_NEXT_ENTRY = -1
        ACL_EXTENDED_ALLOW = 1
        ACL_EXTENDED_DENY = 2
        has_allow_entry = False
        try:
            entry_id = ACL_FIRST_ENTRY
            while True:
                entry = ctypes.c_void_p()
                ctypes.set_errno(0)
                entry_result = library.acl_get_entry(
                    acl,
                    entry_id,
                    ctypes.byref(entry),
                )
                if entry_result != 0:
                    error_number = ctypes.get_errno()
                    if entry_id == ACL_NEXT_ENTRY and error_number == errno.EINVAL:
                        break
                    error_number = error_number or errno.EIO
                    raise OSError(error_number, os.strerror(error_number))

                tag_type = ctypes.c_int()
                ctypes.set_errno(0)
                if library.acl_get_tag_type(entry, ctypes.byref(tag_type)) != 0:
                    error_number = ctypes.get_errno() or errno.EIO
                    raise OSError(error_number, os.strerror(error_number))
                if tag_type.value == ACL_EXTENDED_ALLOW:
                    has_allow_entry = True
                    break
                if tag_type.value != ACL_EXTENDED_DENY:
                    raise OSError(errno.EINVAL, "unknown Darwin ACL entry type")
                entry_id = ACL_NEXT_ENTRY
        finally:
            ctypes.set_errno(0)
            if library.acl_free(acl) != 0:
                error_number = ctypes.get_errno() or errno.EIO
                raise OSError(error_number, os.strerror(error_number))
        return has_allow_entry

    if not hasattr(os, "listxattr"):
        raise OSError(
            getattr(errno, "ENOSYS", errno.EIO),
            "descriptor ACL inspection unavailable",
        )
    try:
        attributes = os.listxattr(descriptor_fd)
    except OSError as error:
        if error.errno in unsupported_acl_errnos():
            return False
        raise
    names = {os.fsdecode(attribute) for attribute in attributes}
    return bool(
        names.intersection({"system.posix_acl_access", "system.posix_acl_default"})
    )


def identity(value):
    return (
        value.st_dev,
        value.st_ino,
        stat.S_IFMT(value.st_mode),
        value.st_uid,
        value.st_gid,
        stat.S_IMODE(value.st_mode),
        value.st_nlink,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def validate_directory_fd(descriptor_fd):
    before = os.fstat(descriptor_fd)
    if (
        not stat.S_ISDIR(before.st_mode)
        or before.st_uid not in {0, os.geteuid()}
        or stat.S_IMODE(before.st_mode) & 0o022
    ):
        raise OSError("unsafe collector directory identity")
    if fd_has_extended_acl(descriptor_fd):
        raise OSError("collector directory has an allowing ACL")
    after = os.fstat(descriptor_fd)
    if identity(before) != identity(after):
        raise OSError("collector directory changed during ACL inspection")
    return identity(before)


def close_chain(chain):
    for record in reversed(chain):
        os.close(record["descriptor"])


if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
    raise OSError("required no-follow directory operations unavailable")
if not path.is_absolute() or not repo_root.is_absolute():
    raise OSError("collector and repository paths must be absolute")
if os.path.normpath(os.fspath(path)) != os.fspath(path):
    raise OSError("collector path is not normalized")
if os.path.normpath(os.fspath(repo_root)) != os.fspath(repo_root):
    raise OSError("repository path is not normalized")
try:
    relative_path = path.relative_to(repo_root)
except ValueError as error:
    raise OSError("collector is outside repository root") from error
relative_parts = relative_path.parts
if not relative_parts or any(part in ("", ".", "..") for part in relative_parts):
    raise OSError("unsafe repository-relative collector path")
repo_parts = repo_root.parts
if not repo_parts or repo_parts[0] != os.path.sep:
    raise OSError("repository root has no absolute root component")
directory_components = repo_parts[1:] + relative_parts[:-1]
if any(part in ("", ".", "..") for part in directory_components):
    raise OSError("unsafe absolute collector directory component")

directory_flags = (
    os.O_RDONLY
    | os.O_DIRECTORY
    | os.O_NOFOLLOW
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_NONBLOCK", 0)
)
file_flags = (
    os.O_RDONLY
    | os.O_NOFOLLOW
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_NONBLOCK", 0)
)


def open_directory_chain():
    chain = []
    try:
        root_named = os.lstat(os.path.sep)
        root_fd = os.open(os.path.sep, directory_flags)
        chain.append(
            {
                "descriptor": root_fd,
                "component": None,
                "identity": None,
            }
        )
        root_identity = validate_directory_fd(root_fd)
        if identity(root_named) != root_identity:
            raise OSError("filesystem root changed before open")
        chain[-1]["identity"] = root_identity

        for component in directory_components:
            parent_fd = chain[-1]["descriptor"]
            named = os.stat(component, dir_fd=parent_fd, follow_symlinks=False)
            child_fd = os.open(component, directory_flags, dir_fd=parent_fd)
            chain.append(
                {
                    "descriptor": child_fd,
                    "component": component,
                    "identity": None,
                }
            )
            child_identity = validate_directory_fd(child_fd)
            if identity(named) != child_identity:
                raise OSError("collector ancestor changed before open")
            chain[-1]["identity"] = child_identity
        return chain
    except BaseException:
        close_chain(chain)
        raise


def validate_retained_chain(chain):
    for index, record in enumerate(chain):
        current_identity = validate_directory_fd(record["descriptor"])
        if current_identity != record["identity"]:
            raise OSError("collector directory changed after file read")
        if index == 0:
            named = os.lstat(os.path.sep)
        else:
            named = os.stat(
                record["component"],
                dir_fd=chain[index - 1]["descriptor"],
                follow_symlinks=False,
            )
        if identity(named) != record["identity"]:
            raise OSError("collector directory name changed after file read")


chain = []
reopened_chain = []
descriptor = -1
try:
    chain = open_directory_chain()
    parent_fd = chain[-1]["descriptor"]
    leaf_name = relative_parts[-1]
    named_before = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(named_before.st_mode)
        or named_before.st_uid != os.geteuid()
        or stat.S_IMODE(named_before.st_mode) & 0o022
        or named_before.st_nlink != 1
        or named_before.st_size <= 0
        or named_before.st_size > MAX_COLLECTOR_SIZE
    ):
        raise OSError("unsafe collector identity")

    descriptor = os.open(leaf_name, file_flags, dir_fd=parent_fd)
    opened_before = os.fstat(descriptor)
    if identity(named_before) != identity(opened_before):
        raise OSError("collector changed before open")
    if fd_has_extended_acl(descriptor):
        raise OSError("collector has an extended ACL")
    opened_after_acl = os.fstat(descriptor)
    if identity(opened_before) != identity(opened_after_acl):
        raise OSError("collector changed during initial ACL inspection")

    digest = hashlib.sha256()
    byte_count = 0
    while True:
        chunk = os.read(descriptor, 1024 * 1024)
        if not chunk:
            break
        byte_count += len(chunk)
        if byte_count > MAX_COLLECTOR_SIZE:
            raise OSError("collector exceeds size limit")
        digest.update(chunk)

    opened_after = os.fstat(descriptor)
    named_after = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
    if fd_has_extended_acl(descriptor):
        raise OSError("collector acquired an extended ACL")
    opened_final = os.fstat(descriptor)
    named_final = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        identity(named_before) != identity(opened_after)
        or identity(named_before) != identity(named_after)
        or identity(named_before) != identity(opened_final)
        or identity(named_before) != identity(named_final)
        or byte_count != opened_final.st_size
    ):
        raise OSError("collector changed during read")

    validate_retained_chain(chain)
    reopened_chain = open_directory_chain()
    if [record["identity"] for record in reopened_chain] != [
        record["identity"] for record in chain
    ]:
        raise OSError("collector parent path changed during read")
    print(
        digest.hexdigest()
        + "\t"
        + ":".join(str(item) for item in identity(named_before))
    )
finally:
    if descriptor >= 0:
        os.close(descriptor)
    close_chain(reopened_chain)
    close_chain(chain)
PY
}

isolated_git() { # fixed Git with no caller, system, global, replacement, or fsmonitor controls
  /usr/bin/env -i \
    PATH=/usr/bin:/bin \
    LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_SYSTEM=/dev/null \
    GIT_CONFIG_GLOBAL=/dev/null \
    /usr/bin/git --no-replace-objects -c core.fsmonitor=false "$@"
}

production_git_binding() { # physical repo root + exact HEAD, with no ambient Git controls
  [[ -f $GIT_BIN && -x $GIT_BIN ]] || return 1
  local top head
  top="$(isolated_git -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null)" \
    || return 1
  head="$(isolated_git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}' 2>/dev/null)" \
    || return 1
  [[ $top == "$REPO_ROOT" && $head =~ ^[0-9a-f]{40}$ ]] || return 1
  printf '%s\t%s\n' "$top" "$head"
}

validate_review_binding() {
  [[ -n $REVIEWED_HEAD && -n $REVIEWED_COLLECTOR_SHA256 ]] \
    || stop_now STOP_REVIEW_BINDING_REQUIRED
  [[ $REVIEWED_HEAD =~ ^[0-9a-f]{40}$ \
      && $REVIEWED_COLLECTOR_SHA256 =~ ^[0-9a-f]{64}$ ]] \
    || stop_now STOP_REVIEW_BINDING_MISMATCH

  local collector_record actual_digest actual_identity git_record actual_root actual_head
  collector_record="$(stable_collector_binding)" \
    || stop_now STOP_REVIEW_BINDING_MISMATCH
  IFS=$'\t' read -r actual_digest actual_identity <<<"$collector_record"
  [[ $actual_digest == "$REVIEWED_COLLECTOR_SHA256" && -n $actual_identity ]] \
    || stop_now STOP_REVIEW_BINDING_MISMATCH

  if (( SELFTEST_FIXTURE )); then
    actual_head=$REVIEWED_HEAD
  else
    git_record="$(production_git_binding)" \
      || stop_now STOP_REVIEW_BINDING_MISMATCH
    IFS=$'\t' read -r actual_root actual_head <<<"$git_record"
    [[ $actual_root == "$REPO_ROOT" && $actual_head == "$REVIEWED_HEAD" ]] \
      || stop_now STOP_REVIEW_BINDING_MISMATCH
  fi

  SOURCE_HEAD=$actual_head
  COLLECTOR_SHA256=$actual_digest
  COLLECTOR_SOURCE_IDENTITY=$actual_identity
}

review_binding_intact() {
  [[ -n $SOURCE_HEAD && -n $COLLECTOR_SHA256 \
      && -n $COLLECTOR_SOURCE_IDENTITY ]] || return 1
  local collector_record actual_digest actual_identity git_record actual_root actual_head
  collector_record="$(stable_collector_binding)" || return 1
  IFS=$'\t' read -r actual_digest actual_identity <<<"$collector_record"
  [[ $actual_digest == "$REVIEWED_COLLECTOR_SHA256" \
      && $actual_digest == "$COLLECTOR_SHA256" \
      && $actual_identity == "$COLLECTOR_SOURCE_IDENTITY" ]] || return 1
  if (( SELFTEST_FIXTURE )); then
    [[ $SOURCE_HEAD == "$REVIEWED_HEAD" ]]
    return
  fi
  git_record="$(production_git_binding)" || return 1
  IFS=$'\t' read -r actual_root actual_head <<<"$git_record"
  [[ $actual_root == "$REPO_ROOT" && $actual_head == "$REVIEWED_HEAD" \
      && $actual_head == "$SOURCE_HEAD" ]]
}

sha256_receipt_tree() { # directory [archive-name digest identity]...
  local root=$1 text_budget apk_budget services_budget
  local text_timeout text_limit text_stderr
  local apk_timeout apk_limit apk_stderr
  local services_timeout services_limit services_stderr
  shift
  text_budget="$(receipt_budget text)" || return 1
  apk_budget="$(receipt_budget apk)" || return 1
  services_budget="$(receipt_budget services)" || return 1
  IFS=$'\t' read -r text_timeout text_limit text_stderr <<<"$text_budget"
  IFS=$'\t' read -r apk_timeout apk_limit apk_stderr <<<"$apk_budget"
  IFS=$'\t' read -r services_timeout services_limit services_stderr \
    <<<"$services_budget"
  [[ $text_stderr == "$apk_stderr" && $text_stderr == "$services_stderr" ]] \
    || return 1
  "$PYTHON_BIN" -I - "$root" "$text_limit" "$apk_limit" \
    "$services_limit" "$text_stderr" "$@" <<'PY'
import hashlib
import os
import re
import stat
import struct
import sys

root = sys.argv[1]
text_limit = int(sys.argv[2])
apk_limit = int(sys.argv[3])
services_limit = int(sys.argv[4])
stderr_limit = int(sys.argv[5])
binding_args = sys.argv[6:]

class ReceiptLimitError(Exception):
    def __init__(self, exit_code, detail):
        super().__init__(detail)
        self.exit_code = exit_code

def inode_state(value):
    return (
        value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode),
        value.st_uid, value.st_gid, stat.S_IMODE(value.st_mode),
        value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns,
    )

def identity_text(value):
    return ":".join(str(item) for item in inode_state(value))

def receipt_profile(name):
    if name == "services-jar.stdout.bin":
        return services_limit, 3
    if re.fullmatch(r"package-[a-z0-9-]+-apk\.stdout\.bin", name):
        return apk_limit, 2
    if name.endswith(".stdout.bin"):
        raise OSError("unknown binary receipt lane")
    if name.endswith(".stderr.bin"):
        return stderr_limit, 1
    if name.endswith(".stdout.txt") or name == "stems.txt" or re.fullmatch(
        r"[a-z0-9][a-z0-9-]*\.(?:command|start-utc|exit|end-utc)\.txt",
        name,
    ):
        return text_limit, 1
    raise OSError("unknown receipt carrier name")

def bounded_names(descriptor, maximum):
    names = []
    with os.scandir(descriptor) as entries:
        for entry in entries:
            if len(names) >= maximum:
                raise OSError("invalid receipt directory cardinality")
            names.append(entry.name)
    if not names or len(names) != len(set(names)):
        raise OSError("invalid receipt directory cardinality")
    names.sort(key=lambda value: value.encode("utf-8"))
    return names

if len(binding_args) % 3:
    raise SystemExit(1)
expected_bindings = {}
for index in range(0, len(binding_args), 3):
    name, expected_digest, expected_identity = binding_args[index:index + 3]
    if (
        name in expected_bindings
        or not re.fullmatch(
            r"(?:services-jar|package-[a-z0-9-]+-apk)\.stdout\.bin", name
        )
        or re.fullmatch(r"[0-9a-f]{64}", expected_digest) is None
        or re.fullmatch(r"[0-9]+(?::[0-9]+){9}", expected_identity) is None
    ):
        raise SystemExit(1)
    expected_bindings[name] = (expected_digest, expected_identity)

directory_fd = -1
try:
    named_directory_before = os.lstat(root)
    directory_fd = os.open(
        root,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_DIRECTORY", 0),
    )
    opened_directory_before = os.fstat(directory_fd)
    if (
        inode_state(named_directory_before) != inode_state(opened_directory_before)
        or not stat.S_ISDIR(opened_directory_before.st_mode)
        or opened_directory_before.st_uid != os.geteuid()
        or stat.S_IMODE(opened_directory_before.st_mode) != 0o700
    ):
        raise OSError("unsafe receipt directory")
    names = bounded_names(directory_fd, 512)

    digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
    seen_bindings = set()
    for raw_name in names:
        if not raw_name or raw_name in {".", ".."} or "/" in raw_name or "\x00" in raw_name:
            raise OSError("unsafe receipt carrier name")
        name = raw_name.encode("utf-8")
        file_limit, limit_exit = receipt_profile(raw_name)
        named_before = os.stat(raw_name, dir_fd=directory_fd, follow_symlinks=False)
        if named_before.st_size > file_limit:
            raise ReceiptLimitError(limit_exit, "receipt exceeds its lane cap")
        if (
            not stat.S_ISREG(named_before.st_mode)
            or named_before.st_uid != os.geteuid()
            or stat.S_IMODE(named_before.st_mode) != 0o600
            or named_before.st_nlink != 1
        ):
            raise OSError("unsafe receipt carrier")
        descriptor = -1
        try:
            descriptor = os.open(
                raw_name,
                os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
                dir_fd=directory_fd,
            )
            opened_before = os.fstat(descriptor)
            if inode_state(named_before) != inode_state(opened_before):
                raise OSError("receipt carrier changed before open")

            digest.update(struct.pack(">Q", len(name)))
            digest.update(name)
            digest.update(struct.pack(">Q", opened_before.st_size))
            file_digest = hashlib.sha256()
            bytes_read = 0
            while True:
                remaining = file_limit - bytes_read
                chunk = os.read(descriptor, min(1024 * 1024, remaining + 1))
                if not chunk:
                    break
                if len(chunk) > remaining:
                    raise ReceiptLimitError(limit_exit, "receipt grew beyond its lane cap")
                digest.update(chunk)
                file_digest.update(chunk)
                bytes_read += len(chunk)
            opened_after = os.fstat(descriptor)
            if opened_after.st_size > file_limit:
                raise ReceiptLimitError(limit_exit, "receipt grew beyond its lane cap")
            named_after = os.stat(
                raw_name, dir_fd=directory_fd, follow_symlinks=False
            )
            if not (
                inode_state(opened_before) == inode_state(opened_after)
                == inode_state(named_after)
            ) or bytes_read != opened_after.st_size:
                raise OSError("receipt carrier changed while hashing")
            if raw_name in expected_bindings:
                expected_digest, expected_identity = expected_bindings[raw_name]
                if (
                    file_digest.hexdigest() != expected_digest
                    or identity_text(opened_after) != expected_identity
                ):
                    raise OSError("archive receipt no longer matches validated bytes")
                seen_bindings.add(raw_name)
        finally:
            if descriptor >= 0:
                os.close(descriptor)

    names_after = bounded_names(directory_fd, 512)
    opened_directory_after = os.fstat(directory_fd)
    named_directory_after = os.lstat(root)
    if (
        inode_state(opened_directory_before) != inode_state(opened_directory_after)
        or inode_state(opened_directory_after) != inode_state(named_directory_after)
        or names != names_after
        or seen_bindings != set(expected_bindings)
    ):
        raise OSError("receipt directory changed while hashing")
except ReceiptLimitError as error:
    raise SystemExit(error.exit_code)
except (OSError, UnicodeError, ValueError):
    raise SystemExit(1)
finally:
    if directory_fd >= 0:
        os.close(directory_fd)
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
  if ! printf '{"schemaVersion":3,"mode":"READ_ONLY_PREFLIGHT","readOnlySemantics":"OPERATIONAL_NOT_BIT_FOR_BIT","incidentalEffects":["ADB_TRANSPORT","TRANSIENT_QUERY_PROCESSES","DEVICE_AUDIT_ACCOUNTING"],"adbServerTrust":"DEFAULT_LOCAL_ENDPOINT_NOT_ATTESTED__INHERITED_ROUTING_REJECTED","adbClientTrust":"%s","adbApprovalLane":"%s","adbApprovalLabel":"%s","adbAllowlistSha256":"%s","adbSnapshotPath":"tooling/adb","status":"%s","terminalStatus":"%s","reason":"%s","collectionStatus":"%s","compatibility":"STATIC_ANALYSIS_PENDING","privilegedInspection":"NOT_COLLECTED_PRIVILEGED","coordinateCaptured":false,"authorizedSerial":"%s","targetSerial":"%s","devicePass":false,"issue66Ac7":"NOT_PASSED","deviceFull":"BLOCKED","durableAck":"NOT_CREATED","fullClaim":"NOT_CREATED","sourceHead":"%s","adbSha256":"%s","collectorSha256":"%s","receiptTreeSha256":"%s","knownPackages":{%s},"servicesJarSha256":"%s","packageApkSha256":{%s},"receiptStems":[%s]}\n' \
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
    "$(json_escape "$SOURCE_HEAD")" \
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
    "sourceHead",
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
  review_binding_intact || stop_now STOP_REVIEW_BINDING_CHANGED
  output_binding_intact || stop_now STOP_OUTPUT_CHANGED
  render_manifest "COLLECTED" \
    "PUBLIC_STATIC_EVIDENCE_COLLECTED__STATIC_ANALYSIS_PENDING" \
    "$final_manifest" || fatal_manifest_write
  write_redacted_summary "$final_manifest"
  output_binding_intact || stop_now STOP_OUTPUT_CHANGED
  [[ ! -d $OUTPUT_DIR/manifest.json && ! -L $OUTPUT_DIR/manifest.json ]] \
    || fatal_manifest_write
  review_binding_intact || stop_now STOP_REVIEW_BINDING_CHANGED
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
    STOP_ADB_READ_FAILED|STOP_ADB_TIMEOUT|STOP_ADB_STDOUT_LIMIT|\
    STOP_ADB_STDERR_LIMIT|STOP_FRAMEWORK_READ_FAILED|STOP_APK_READ_FAILED|\
    STOP_FRAMEWORK_ARCHIVE_LIMIT|STOP_APK_ARCHIVE_LIMIT|\
    STOP_PACKAGE_OBSERVATION_MALFORMED|STOP_PACKAGE_PATH_CHANGED|\
    STOP_BOOT_CHANGED) exit 21 ;;
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

matching_package_path_receipt() { # receipt stem, package, expected base path
  local stem=$1 package=$2 expected_path=$3 observed_path
  run_text_receipt "$stem" \
    -s "$AUTHORIZED_SERIAL" shell pm path "$package"
  if (( LAST_RC == 1 )); then
    [[ ! -s $OUTPUT_DIR/receipts/$stem.stdout.txt \
        && ! -s $OUTPUT_DIR/receipts/$stem.stderr.bin ]] || return 1
    return 1
  elif (( LAST_RC != 0 )); then
    return 2
  fi
  [[ -s $OUTPUT_DIR/receipts/$stem.stdout.txt \
      && ! -s $OUTPUT_DIR/receipts/$stem.stderr.bin ]] || return 1
  observed_path="$(select_base_apk_path \
    "$OUTPUT_DIR/receipts/$stem.stdout.txt" "$package")" || return 1
  [[ $observed_path == "$expected_path" ]]
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
  validated="$("$PYTHON_BIN" -I - "$ADB_BIN" "$ADB_SNAPSHOT_SIZE_LIMIT" <<'PY'
import os
import pathlib
import stat
import sys
import hashlib

try:
    source = pathlib.Path(sys.argv[1]).resolve(strict=True)
    size_limit = int(sys.argv[2])
    if any(separator in str(source) for separator in ("\n", "\r", "\t")):
        raise OSError("unsafe source pathname")
    before = source.lstat()
    if before.st_size > size_limit:
        raise OSError("adb source exceeds size limit")
    flags = (
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    )
    descriptor = os.open(source, flags)
    try:
        opened = os.fstat(descriptor)
        if opened.st_size > size_limit:
            raise OSError("adb source exceeds size limit")
        digest = hashlib.sha256()
        byte_count = 0
        while True:
            remaining = size_limit - byte_count
            chunk = os.read(descriptor, min(1024 * 1024, remaining + 1))
            if not chunk:
                break
            if len(chunk) > remaining:
                raise OSError("adb source grew beyond size limit")
            digest.update(chunk)
            byte_count += len(chunk)
        opened_after = os.fstat(descriptor)
        if opened_after.st_size > size_limit or byte_count != opened_after.st_size:
            raise OSError("adb source changed size while reading")
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
    "$ADB_APPROVAL_LANE" \
    "$ADB_ALLOWLIST_SIZE_LIMIT" <<'PY'
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
allowlist_size_limit = int(sys.argv[5])

def internal_failure():
    raise SystemExit(70)

def stable_trust_bytes(path, byte_limit):
    def identity(value):
        return (
            value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode),
            value.st_uid, stat.S_IMODE(value.st_mode), value.st_nlink,
            value.st_size, value.st_mtime_ns, value.st_ctime_ns,
        )

    descriptor = -1
    try:
        named_before = path.lstat()
        if (
            not stat.S_ISREG(named_before.st_mode)
            or stat.S_ISLNK(named_before.st_mode)
            or named_before.st_uid != os.geteuid()
            or stat.S_IMODE(named_before.st_mode) & 0o022
            or named_before.st_nlink != 1
            or named_before.st_size <= 0
            or named_before.st_size > byte_limit
        ):
            raise OSError("unsafe repo trust file ownership, type or size")
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
        )
        opened_before = os.fstat(descriptor)
        if identity(named_before) != identity(opened_before):
            raise OSError("repo trust file changed before open")
        data = bytearray()
        while True:
            remaining = byte_limit - len(data)
            chunk = os.read(descriptor, min(1024 * 1024, remaining + 1))
            if not chunk:
                break
            if len(chunk) > remaining:
                raise OSError("repo trust file grew beyond its fixed byte limit")
            data.extend(chunk)
        opened_after = os.fstat(descriptor)
        named_after = path.lstat()
        if (
            opened_after.st_size > byte_limit
            or len(data) != opened_after.st_size
            or identity(named_before) != identity(opened_after)
            or identity(opened_after) != identity(named_after)
        ):
            raise OSError("repo trust file changed while reading")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    return bytes(data)

try:
    data = stable_trust_bytes(path, allowlist_size_limit)
except OSError:
    internal_failure()
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
import errno
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
    except AttributeError as error:
        if sys.platform != "darwin":
            raise OSError("ACL inspection unavailable") from error
        attributes = ()
    except OSError as error:
        unsupported_errnos = {
            getattr(errno, name)
            for name in ("ENOTSUP", "EOPNOTSUPP")
            if hasattr(errno, name)
        }
        if error.errno not in unsupported_errnos:
            raise
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
        "LC_ALL": "C",
        "LANG": "C",
        "PATH": "/usr/bin:/bin",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_SYSTEM": "/dev/null",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_CONFIG_COUNT": "0",
        "GIT_OPTIONAL_LOCKS": "0",
    }
    git_prefix = [
        "/usr/bin/git",
        "--no-replace-objects",
        "-c", "core.hooksPath=/dev/null",
        "-c", "core.fsmonitor=false",
        "-c", "core.untrackedCache=false",
        "-C", str(repo),
    ]
    common = pathlib.Path(subprocess.check_output(
        [*git_prefix, "rev-parse", "--path-format=absolute", "--git-common-dir"],
        text=True,
        env=git_env,
    ).strip()).resolve()
    listing = subprocess.check_output(
        [*git_prefix, "worktree", "list", "--porcelain"],
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
import errno
import os
import pathlib
import stat
import subprocess
import sys

expected = sys.argv[2]

def has_extended_acl(path):
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except AttributeError as error:
        if sys.platform != "darwin":
            raise OSError("ACL inspection unavailable") from error
        attributes = ()
    except OSError as error:
        unsupported_errnos = {
            getattr(errno, name)
            for name in ("ENOTSUP", "EOPNOTSUPP")
            if hasattr(errno, name)
        }
        if error.errno not in unsupported_errnos:
            raise
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
  ADB_SHA256="$("$PYTHON_BIN" -I - "$ADB_SOURCE_PATH" "$ADB_SOURCE_IDENTITY" \
    tooling/.adb.tmp "$ADB_SNAPSHOT_SIZE_LIMIT" <<'PY'
import hashlib
import os
import stat
import sys

source_path, expected_identity, destination_path = sys.argv[1:4]
size_limit = int(sys.argv[4])

def identity(value):
    return ":".join(str(item) for item in (
        value.st_dev, value.st_ino, value.st_size,
        value.st_mtime_ns, value.st_ctime_ns, stat.S_IMODE(value.st_mode),
    ))

source_flags = (
    os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
)
destination_flags = (
    os.O_WRONLY | os.O_CREAT | os.O_EXCL
    | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
)
try:
    source = os.open(source_path, source_flags)
    destination = -1
    try:
        before = os.fstat(source)
        if (
            not stat.S_ISREG(before.st_mode)
            or identity(before) != expected_identity
            or before.st_size > size_limit
        ):
            raise OSError("adb source identity changed before snapshot")
        destination = os.open(destination_path, destination_flags, 0o500)
        digest = hashlib.sha256()
        byte_count = 0
        while True:
            remaining = size_limit - byte_count
            chunk = os.read(source, min(1024 * 1024, remaining + 1))
            if not chunk:
                break
            if len(chunk) > remaining:
                raise OSError("adb source grew beyond size limit")
            digest.update(chunk)
            byte_count += len(chunk)
            view = memoryview(chunk)
            while view:
                written = os.write(destination, view)
                if written <= 0:
                    raise OSError("short adb snapshot write")
                view = view[written:]
        os.fchmod(destination, 0o500)
        os.fsync(destination)
        after = os.fstat(source)
        destination_after = os.fstat(destination)
        if (
            identity(after) != expected_identity
            or after.st_size > size_limit
            or byte_count != after.st_size
            or destination_after.st_size != byte_count
        ):
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
  adb_snapshot_intact || return 1
}

adb_snapshot_intact() {
  [[ -n $ADB_SHA256 && -n $ADB_SNAPSHOT_IDENTITY ]] || return 1
  "$PYTHON_BIN" -I - tooling tooling/adb "$ADB_SNAPSHOT_IDENTITY" \
    "$ADB_SHA256" "$ADB_SNAPSHOT_SIZE_LIMIT" <<'PY' >/dev/null 2>&1
import hashlib
import os
import pathlib
import stat
import sys

directory = os.lstat(pathlib.Path(sys.argv[1]))
binary_path = pathlib.Path(sys.argv[2])
expected_identity = sys.argv[3]
expected_digest = sys.argv[4]
size_limit = int(sys.argv[5])

def identity(value):
    return (
        value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode), value.st_uid,
        stat.S_IMODE(value.st_mode), value.st_nlink, value.st_size,
        value.st_mtime_ns, value.st_ctime_ns,
    )

descriptor = -1
try:
    binary_before = binary_path.lstat()
    if (
        not stat.S_ISDIR(directory.st_mode)
        or stat.S_ISLNK(directory.st_mode)
        or stat.S_IMODE(directory.st_mode) != 0o500
        or directory.st_uid != os.geteuid()
        or not stat.S_ISREG(binary_before.st_mode)
        or stat.S_ISLNK(binary_before.st_mode)
        or stat.S_IMODE(binary_before.st_mode) != 0o500
        or binary_before.st_uid != os.geteuid()
        or binary_before.st_nlink != 1
        or binary_before.st_size > size_limit
        or f"{binary_before.st_dev}:{binary_before.st_ino}:{binary_before.st_size}"
        != expected_identity
    ):
        raise OSError("unsafe adb snapshot")
    descriptor = os.open(
        binary_path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
    )
    opened_before = os.fstat(descriptor)
    if identity(binary_before) != identity(opened_before):
        raise OSError("adb snapshot changed before open")
    digest = hashlib.sha256()
    byte_count = 0
    while True:
        remaining = size_limit - byte_count
        chunk = os.read(descriptor, min(1024 * 1024, remaining + 1))
        if not chunk:
            break
        if len(chunk) > remaining:
            raise OSError("adb snapshot grew beyond size limit")
        digest.update(chunk)
        byte_count += len(chunk)
    opened_after = os.fstat(descriptor)
    binary_after = binary_path.lstat()
    if (
        identity(opened_before) != identity(opened_after)
        or identity(opened_after) != identity(binary_after)
        or byte_count != opened_after.st_size
        or opened_after.st_size > size_limit
    ):
        raise OSError("adb snapshot changed while reading")
except (OSError, ValueError):
    raise SystemExit(1)
finally:
    if descriptor >= 0:
        os.close(descriptor)
if digest.hexdigest() != expected_digest:
    raise SystemExit(1)
PY
}

timestamp_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

receipt_budget() { # text|apk|services; prints timeout stdout-limit stderr-limit
  local kind=$1
  if (( SELFTEST_FIXTURE )); then
    case "$kind" in
      text) printf '%d\t%d\t%d\n' \
        "$SELFTEST_TIMEOUT_SECONDS" "$SELFTEST_TEXT_STDOUT_LIMIT" \
        "$SELFTEST_STDERR_LIMIT" ;;
      apk) printf '%d\t%d\t%d\n' \
        "$SELFTEST_TIMEOUT_SECONDS" "$SELFTEST_APK_STDOUT_LIMIT" \
        "$SELFTEST_STDERR_LIMIT" ;;
      services) printf '%d\t%d\t%d\n' \
        "$SELFTEST_TIMEOUT_SECONDS" "$SELFTEST_SERVICES_STDOUT_LIMIT" \
        "$SELFTEST_STDERR_LIMIT" ;;
      *) return 1 ;;
    esac
  else
    case "$kind" in
      text) printf '%d\t%d\t%d\n' \
        "$PROD_TEXT_TIMEOUT_SECONDS" "$PROD_TEXT_STDOUT_LIMIT" \
        "$PROD_STDERR_LIMIT" ;;
      apk) printf '%d\t%d\t%d\n' \
        "$PROD_BINARY_APK_TIMEOUT_SECONDS" "$PROD_BINARY_APK_STDOUT_LIMIT" \
        "$PROD_STDERR_LIMIT" ;;
      services) printf '%d\t%d\t%d\n' \
        "$PROD_BINARY_SERVICES_TIMEOUT_SECONDS" \
        "$PROD_BINARY_SERVICES_STDOUT_LIMIT" "$PROD_STDERR_LIMIT" ;;
      *) return 1 ;;
    esac
  fi
}

supervise_adb_receipt() { # kind stdout-path stderr-path exact-adb-argv...
  local kind=$1 stdout_path=$2 stderr_path=$3 budget
  shift 3
  budget="$(receipt_budget "$kind")" || return 70
  local timeout_seconds stdout_limit stderr_limit
  IFS=$'\t' read -r timeout_seconds stdout_limit stderr_limit <<<"$budget"
  "$PYTHON_BIN" -I - "$timeout_seconds" "$stdout_limit" "$stderr_limit" \
    "$ADB_APPROVAL_LANE" "$stdout_path" "$stderr_path" "$@" <<'PY'
import errno
import os
import selectors
import signal
import stat
import subprocess
import sys
import time

timeout_seconds = int(sys.argv[1])
limits = {"stdout": int(sys.argv[2]), "stderr": int(sys.argv[3])}
approval_lane = sys.argv[4]
paths = {"stdout": sys.argv[5], "stderr": sys.argv[6]}
argv = sys.argv[7:]
process = None
selector = None
streams = {}
files = {}

def group_exists():
    if process is None:
        return False
    try:
        os.killpg(process.pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        # Darwin may report EPERM for a just-reaped, empty process group.
        # A live descendant of this mode-0500 snapshot retains our euid and
        # therefore remains signalable; the timeout fixture exercises that.
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
        raise RuntimeError("adb process group survived SIGKILL")
    try:
        process.wait(timeout=0.75)
    except subprocess.TimeoutExpired:
        signal_group(signal.SIGKILL)
        try:
            process.wait(timeout=0.25)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError("adb process leader was not reaped") from error

def safe_output(path):
    flags = (
        os.O_WRONLY | os.O_TRUNC | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    descriptor = os.open(path, flags)
    value = os.fstat(descriptor)
    if not stat.S_ISREG(value.st_mode):
        os.close(descriptor)
        raise OSError("receipt output is not regular")
    return os.fdopen(descriptor, "wb", buffering=0)

def write_all(output, value):
    remaining = memoryview(value)
    while remaining:
        written = output.write(remaining)
        if (
            not isinstance(written, int)
            or written <= 0
            or written > len(remaining)
        ):
            raise OSError("receipt output made invalid write progress")
        remaining = remaining[written:]

def sanitized_child_environment(lane):
    if lane not in {"PRODUCTION", "SELFTEST"}:
        raise ValueError("invalid ADB approval lane")
    result = {"PATH": "/usr/bin:/bin", "LC_ALL": "C"}
    # Preserve only the documented host identity/key-discovery inputs needed by
    # adb. Loader, shell-startup and language-startup injection is absent by
    # construction rather than maintained as a fragile denylist.
    for name in (
        "HOME", "USER", "LOGNAME", "TMPDIR", "ADB_VENDOR_KEYS",
        "ANDROID_USER_HOME", "ANDROID_SDK_HOME",
    ):
        if name in os.environ:
            result[name] = os.environ[name]
    if lane == "SELFTEST":
        # The fake transport has no device/network surface. Its explicit fault
        # controls are kept only in the disjoint SELFTEST trust lane.
        for name, value in os.environ.items():
            if name.startswith(("FAKE_ADB_", "SELFTEST_", "POISON_")):
                result[name] = value
    return result

outcome = "INTERNAL"
receipt_exit = 70
try:
    if not argv or timeout_seconds <= 0 or any(limit < 0 for limit in limits.values()):
        raise ValueError("invalid supervisor arguments")
    files = {name: safe_output(path) for name, path in paths.items()}
    child_env = sanitized_child_environment(approval_lane)
    process = subprocess.Popen(
        argv,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        shell=False,
        start_new_session=True,
        env=child_env,
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
            receipt_exit = 124
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
                write_all(files[name], accepted)
                totals[name] += len(accepted)
            if len(chunk) > available:
                outcome = "STDOUT_LIMIT" if name == "stdout" else "STDERR_LIMIT"
                receipt_exit = 125 if name == "stdout" else 126
                break
        if outcome is not None:
            break
    if outcome is None:
        try:
            child_exit = process.wait(timeout=0.25)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError("child remained alive after both pipes closed") from error
        if group_exists():
            bounded_group_stop()
            outcome = "ORPHANED_GROUP"
            receipt_exit = 70
        else:
            outcome = "OK"
            receipt_exit = child_exit if child_exit >= 0 else 128 - child_exit
    else:
        bounded_group_stop()
except Exception:
    outcome = "INTERNAL"
    receipt_exit = 70
    bounded_group_stop()
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
    for output in files.values():
        try:
            output.close()
        except Exception:
            outcome = "INTERNAL"
            receipt_exit = 70
print(f"{outcome}\t{receipt_exit}")
PY
}

complete_supervised_receipt() { # stem prefix supervisor-result
  local stem=$1 prefix=$2 result=$3 status receipt_exit
  if [[ $result == *$'\n'* ]]; then
    status=INTERNAL
    receipt_exit=70
  else
    IFS=$'\t' read -r status receipt_exit <<<"$result"
  fi
  case "$status:$receipt_exit" in
    OK:[0-9]*|TIMEOUT:124|STDOUT_LIMIT:125|STDERR_LIMIT:126|\
    ORPHANED_GROUP:70|INTERNAL:70) ;;
    *) status=INTERNAL; receipt_exit=70 ;;
  esac
  LAST_RC=$receipt_exit
  printf '%d\n' "$LAST_RC" >"$prefix.exit.txt" \
    || stop_now STOP_INTERNAL_RECEIPT_WRITE
  timestamp_utc >"$prefix.end-utc.txt" || stop_now STOP_INTERNAL_RECEIPT_WRITE
  if [[ $status != OK ]]; then
    printf '%s\n' "$stem" >>"$OUTPUT_DIR/receipts/stems.txt" \
      || stop_now STOP_INTERNAL_RECEIPT_WRITE
    adb_snapshot_intact || stop_now STOP_ADB_SNAPSHOT_CHANGED
    case "$status" in
      TIMEOUT) stop_now STOP_ADB_TIMEOUT ;;
      STDOUT_LIMIT) stop_now STOP_ADB_STDOUT_LIMIT ;;
      STDERR_LIMIT) stop_now STOP_ADB_STDERR_LIMIT ;;
      ORPHANED_GROUP) stop_now STOP_INTERNAL_ADB_PROCESS_GROUP ;;
      *) stop_now STOP_INTERNAL_ADB_SUPERVISOR ;;
    esac
  fi
}

run_text_receipt() { # stem adb-argv...
  local stem=$1
  shift
  review_binding_intact || stop_now STOP_REVIEW_BINDING_CHANGED
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
  local supervisor_result supervisor_rc
  supervisor_result="$(supervise_adb_receipt text \
    "$prefix.stdout.txt" "$prefix.stderr.bin" "$ADB_BIN" "$@")"
  supervisor_rc=$?
  (( supervisor_rc == 0 )) || supervisor_result=$'INTERNAL\t70'
  complete_supervised_receipt "$stem" "$prefix" "$supervisor_result"
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
  review_binding_intact || stop_now STOP_REVIEW_BINDING_CHANGED
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
  local receipt_kind=apk supervisor_result supervisor_rc
  [[ $stem == services-jar ]] && receipt_kind=services
  supervisor_result="$(supervise_adb_receipt "$receipt_kind" \
    "$prefix.stdout.bin" "$prefix.stderr.bin" "$ADB_BIN" "$@")"
  supervisor_rc=$?
  (( supervisor_rc == 0 )) || supervisor_result=$'INTERNAL\t70'
  complete_supervised_receipt "$stem" "$prefix" "$supervisor_result"
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
    "$ADB_APPROVAL_LANE" "$ADB_CLIENT_TRUST" \
    "$REVIEWED_HEAD" "$REVIEWED_COLLECTOR_SHA256" \
    "$APK_ARCHIVE_MEMBER_LIMIT" "$FRAMEWORK_ARCHIVE_MEMBER_LIMIT" \
    "$ARCHIVE_SINGLE_UNCOMPRESSED_LIMIT" \
    "$ARCHIVE_TOTAL_UNCOMPRESSED_LIMIT" "$ARCHIVE_RATIO_LIMIT" \
    "$ARCHIVE_RATIO_SLACK" "$ADB_SNAPSHOT_SIZE_LIMIT" \
    "$ADB_ALLOWLIST_SIZE_LIMIT" "$COLLECTOR_SOURCE_SIZE_LIMIT" \
    "$OFFLINE_RETAINED_CONTROL_LIMIT" <<'PY' 2>&1
import datetime
import decimal
import errno
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
expected_source_head = sys.argv[9]
expected_collector_digest = sys.argv[10]
archive_limits = {
    "apk_members": int(sys.argv[11]),
    "services_members": int(sys.argv[12]),
    "single_size": int(sys.argv[13]),
    "total_size": int(sys.argv[14]),
    "ratio": int(sys.argv[15]),
    "ratio_slack": int(sys.argv[16]),
}
adb_snapshot_size_limit = int(sys.argv[17])
allowlist_size_limit = int(sys.argv[18])
collector_size_limit = int(sys.argv[19])
retained_control_limit = int(sys.argv[20])
manifest_path = root / "manifest.json"
summary_path = root / "summary.json"
receipts = root / "receipts"
tooling = root / "tooling"
adb_path = tooling / "adb"

if expected_approval_lane == "SELFTEST":
    text_stdout_limit = 64 * 1024
    apk_stdout_limit = 3 * 1024 * 1024
    services_stdout_limit = 2 * 1024 * 1024
    stderr_limit = 32 * 1024
else:
    text_stdout_limit = 4 * 1024 * 1024
    apk_stdout_limit = 256 * 1024 * 1024
    services_stdout_limit = 128 * 1024 * 1024
    stderr_limit = 1024 * 1024
metadata_limit = text_stdout_limit

def inode_state(value):
    return (
        value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode),
        value.st_uid, stat.S_IMODE(value.st_mode), value.st_size,
        value.st_mtime_ns, value.st_ctime_ns,
    )

def has_extended_acl(path):
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except AttributeError as error:
        if sys.platform != "darwin":
            raise OSError("ACL inspection unavailable") from error
        attributes = ()
    except OSError as error:
        unsupported_errnos = {
            getattr(errno, name)
            for name in ("ENOTSUP", "EOPNOTSUPP")
            if hasattr(errno, name)
        }
        if error.errno not in unsupported_errnos:
            raise
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

def bounded_directory_names(path, maximum, label):
    descriptor = -1
    try:
        named_before = path.lstat()
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_DIRECTORY", 0),
        )
        opened_before = os.fstat(descriptor)
        if (
            inode_state(named_before) != inode_state(opened_before)
            or not stat.S_ISDIR(opened_before.st_mode)
        ):
            raise OSError("directory identity changed before enumeration")
        names = set()
        with os.scandir(descriptor) as entries:
            for entry in entries:
                if len(names) >= maximum:
                    raise SystemExit(f"{label} cardinality exceeds {maximum}")
                if entry.name in names:
                    raise OSError("duplicate directory entry")
                names.add(entry.name)
        opened_after = os.fstat(descriptor)
        named_after = path.lstat()
    except OSError as error:
        raise SystemExit(f"unsafe or unstable {label}: {error}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    if not (
        inode_state(named_before) == inode_state(opened_before)
        == inode_state(opened_after) == inode_state(named_after)
    ):
        raise SystemExit(f"{label} changed while enumerating")
    return names

file_states = {}
file_digests = {}
file_read_limits = {}
file_read_limits[str(manifest_path)] = (metadata_limit, None)
file_read_limits[str(summary_path)] = (metadata_limit, None)
file_read_limits[str(receipts / "stems.txt")] = (metadata_limit, None)
file_read_limits[str(adb_path)] = (adb_snapshot_size_limit, None)

def typed_stop(marker, detail):
    raise SystemExit(f"{marker}: {detail}")

def stable_bytes(path, expected_mode=0o600):
    descriptor = -1
    try:
        before = path.lstat()
        read_limit = file_read_limits.get(str(path))
        if read_limit is not None and before.st_size > read_limit[0]:
            if read_limit[1] is not None:
                typed_stop(read_limit[1], f"{path.name} exceeds its lane file limit")
            raise OSError("evidence file exceeds its lane read limit")
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
        data = bytearray()
        digest = hashlib.sha256()
        bytes_read = 0
        while True:
            if read_limit is None:
                read_size = 1024 * 1024
            else:
                remaining = read_limit[0] - bytes_read
                read_size = min(1024 * 1024, remaining + 1)
            chunk = os.read(descriptor, read_size)
            if not chunk:
                break
            if read_limit is not None and len(chunk) > remaining:
                if read_limit[1] is not None:
                    typed_stop(
                        read_limit[1],
                        f"{path.name} grew beyond its lane file limit",
                    )
                raise OSError("evidence file grew beyond its lane read limit")
            data.extend(chunk)
            digest.update(chunk)
            bytes_read += len(chunk)
        opened_after = os.fstat(descriptor)
        if read_limit is not None and opened_after.st_size > read_limit[0]:
            if read_limit[1] is not None:
                typed_stop(
                    read_limit[1],
                    f"{path.name} grew beyond its lane file limit",
                )
            raise OSError("evidence file grew beyond its lane read limit")
        after = path.lstat()
    except OSError as error:
        raise SystemExit(f"unsafe or unstable evidence file: {path}: {error}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    if not (
        inode_state(before) == inode_state(opened_before)
        == inode_state(opened_after) == inode_state(after)
    ) or opened_after.st_size != len(data):
        raise SystemExit(f"evidence file changed while reading: {path}")
    file_states.setdefault(key, current_state)
    digest_hex = digest.hexdigest()
    if key in file_digests and file_digests[key] != digest_hex:
        raise SystemExit(f"evidence file digest changed while reading: {path}")
    file_digests.setdefault(key, digest_hex)
    return bytes(data)

def stable_file_digest(path, expected_mode=0o600, tree_digest=None, tree_name=None):
    descriptor = -1
    try:
        before = path.lstat()
        read_limit = file_read_limits.get(str(path))
        if read_limit is None:
            raise OSError("evidence file has no fixed lane read limit")
        if before.st_size > read_limit[0]:
            if read_limit[1] is not None:
                typed_stop(read_limit[1], f"{path.name} exceeds its lane file limit")
            raise OSError("evidence file exceeds its lane read limit")
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
        if tree_digest is not None:
            if tree_name is None:
                raise OSError("tree digest name is missing")
            tree_digest.update(struct.pack(">Q", len(tree_name)))
            tree_digest.update(tree_name)
            tree_digest.update(struct.pack(">Q", opened_before.st_size))
        digest = hashlib.sha256()
        bytes_read = 0
        while True:
            remaining = read_limit[0] - bytes_read
            chunk = os.read(descriptor, min(1024 * 1024, remaining + 1))
            if not chunk:
                break
            if len(chunk) > remaining:
                if read_limit[1] is not None:
                    typed_stop(
                        read_limit[1],
                        f"{path.name} grew beyond its lane file limit",
                    )
                raise OSError("evidence file grew beyond its lane read limit")
            digest.update(chunk)
            if tree_digest is not None:
                tree_digest.update(chunk)
            bytes_read += len(chunk)
        opened_after = os.fstat(descriptor)
        if opened_after.st_size > read_limit[0]:
            if read_limit[1] is not None:
                typed_stop(
                    read_limit[1],
                    f"{path.name} grew beyond its lane file limit",
                )
            raise OSError("evidence file grew beyond its lane read limit")
        after = path.lstat()
    except OSError as error:
        raise SystemExit(f"unsafe or unstable evidence file: {path}: {error}")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    if not (
        inode_state(before) == inode_state(opened_before)
        == inode_state(opened_after) == inode_state(after)
    ) or opened_after.st_size != bytes_read:
        raise SystemExit(f"evidence file changed while streaming: {path}")
    digest_hex = digest.hexdigest()
    if key in file_digests and file_digests[key] != digest_hex:
        raise SystemExit(f"evidence file digest changed while streaming: {path}")
    file_states.setdefault(key, current_state)
    file_digests.setdefault(key, digest_hex)
    return digest_hex

def stable_trust_bytes(path, byte_limit):
    def identity(value):
        return (
            value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode),
            value.st_uid, stat.S_IMODE(value.st_mode), value.st_nlink,
            value.st_size, value.st_mtime_ns, value.st_ctime_ns,
        )

    descriptor = -1
    try:
        named_before = path.lstat()
        if (
            not stat.S_ISREG(named_before.st_mode)
            or stat.S_ISLNK(named_before.st_mode)
            or named_before.st_uid != os.geteuid()
            or stat.S_IMODE(named_before.st_mode) & 0o022
            or named_before.st_nlink != 1
            or named_before.st_size <= 0
            or named_before.st_size > byte_limit
        ):
            raise OSError("unsafe repo trust file ownership, type or size")
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
        )
        opened_before = os.fstat(descriptor)
        if identity(named_before) != identity(opened_before):
            raise OSError("repo trust file changed before open")
        data = bytearray()
        while True:
            remaining = byte_limit - len(data)
            chunk = os.read(descriptor, min(1024 * 1024, remaining + 1))
            if not chunk:
                break
            if len(chunk) > remaining:
                raise OSError("repo trust file grew beyond its fixed byte limit")
            data.extend(chunk)
        opened_after = os.fstat(descriptor)
        named_after = path.lstat()
        if (
            opened_after.st_size > byte_limit
            or len(data) != opened_after.st_size
            or identity(named_before) != identity(opened_after)
            or identity(opened_after) != identity(named_after)
        ):
            raise OSError("repo trust file changed while reading")
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    return bytes(data)

def stable_repo_bytes(path, byte_limit):
    try:
        before = path.lstat()
        if has_extended_acl(path):
            raise OSError("repo trust file has an extended ACL")
        data = stable_trust_bytes(path, byte_limit)
        after = path.lstat()
    except OSError as error:
        raise SystemExit(f"unsafe or unstable repo trust file: {path}: {error}")
    key = str(path)
    current_state = inode_state(after)
    if inode_state(before) != current_state:
        raise SystemExit(f"repo trust file changed while reading: {path}")
    digest_hex = hashlib.sha256(data).hexdigest()
    if key in file_states and file_states[key] != current_state:
        raise SystemExit(f"repo trust file identity changed: {path}")
    if key in file_digests and file_digests[key] != digest_hex:
        raise SystemExit(f"repo trust file digest changed: {path}")
    file_states.setdefault(key, current_state)
    file_digests.setdefault(key, digest_hex)
    return data

def bounded_retained_control_total(current, values, byte_limit):
    if type(current) is not int or current < 0 or current > byte_limit:
        raise SystemExit("offline retained control byte accounting is invalid")
    remaining = byte_limit - current
    for value in values:
        value_size = len(value)
        if value_size > remaining:
            raise SystemExit("offline retained control bytes exceed the fixed memory limit")
        remaining -= value_size
    return byte_limit - remaining

root_state = directory_state(root)
receipts_state = directory_state(receipts)
tooling_state = directory_state(tooling, expected_mode=0o500)
root_names = bounded_directory_names(root, 4, "evidence root")
tooling_names = bounded_directory_names(tooling, 1, "tooling directory")
receipt_names = bounded_directory_names(receipts, 512, "receipt directory")
if root_names != {"manifest.json", "summary.json", "receipts", "tooling"}:
    raise SystemExit("evidence root contains an unexpected entry")
if tooling_names != {"adb"}:
    raise SystemExit("tooling directory contains an unexpected entry")
allowlist_bytes = stable_repo_bytes(allowlist_path, allowlist_size_limit)
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
collector_bytes = stable_repo_bytes(collector_path, collector_size_limit)

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
    "issue66Ac7", "deviceFull", "durableAck", "fullClaim", "sourceHead", "adbSha256",
    "collectorSha256", "receiptTreeSha256", "knownPackages", "servicesJarSha256",
    "packageApkSha256", "receiptStems",
}
if not isinstance(manifest, dict) or set(manifest) != manifest_keys:
    raise SystemExit("manifest key whitelist mismatch")
expected_scalars = {
    "schemaVersion": 3,
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
    "sourceHead": expected_source_head,
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
head_re = re.compile(r"^[0-9a-f]{40}$")
if not head_re.fullmatch(expected_source_head):
    raise SystemExit("expected source HEAD is malformed")
if not digest_re.fullmatch(expected_collector_digest):
    raise SystemExit("expected collector digest is malformed")
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
if collector_digest != expected_collector_digest:
    raise SystemExit("collector digest does not match the reviewed digest")

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
    "durableAck", "fullClaim", "sourceHead", "knownPackages", "servicesJarSha256",
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
        package_stem = package.replace(".", "-")
        expected_stems.extend(
            (
                f"package-{package_stem}-path-pre-apk",
                f"package-{package_stem}-apk",
                f"package-{package_stem}-path-post-apk",
            )
        )
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
retained_control_bytes = bounded_retained_control_total(
    0,
    (
        allowlist_bytes, collector_bytes, manifest_bytes, summary_bytes, stems_bytes,
    ),
    retained_control_limit,
)
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
    # Command carriers contain only fixed collector argv, never general output.
    # Bound them separately before parsing or retaining any command strings.
    file_read_limits[str(required[0])] = (4096, None)
    for metadata_path in (required[1], required[4], required[5]):
        file_read_limits[str(metadata_path)] = (metadata_limit, None)
    if expects_binary:
        if stem == "services-jar":
            file_read_limits[str(stdout[0])] = (
                services_stdout_limit, "STOP_FRAMEWORK_ARCHIVE_LIMIT"
            )
        else:
            file_read_limits[str(stdout[0])] = (
                apk_stdout_limit, "STOP_APK_ARCHIVE_LIMIT"
            )
    else:
        file_read_limits[str(stdout[0])] = (text_stdout_limit, None)
    file_read_limits[str(receipts / f"{stem}.stderr.bin")] = (stderr_limit, None)
    actual_names = {
        name for name in receipt_names if name.startswith(f"{stem}.")
    }
    if actual_names != {path.name for path in required}:
        raise SystemExit(f"{stem}: not an exact six-file carrier")
    control_paths = [
        path for path in required
        if not (expects_binary and path == stdout[0])
    ]
    raw = {path.name: stable_bytes(path) for path in control_paths}
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
    retained_values = [argument.encode("utf-8") for argument in command_argv]
    retained_values.append(raw[f"{stem}.stderr.bin"])
    if not expects_binary:
        retained_values.append(raw[stdout[0].name])
    retained_control_bytes = bounded_retained_control_total(
        retained_control_bytes,
        retained_values,
        retained_control_limit,
    )
    carriers[stem] = {
        "argv": tuple(command_argv),
        "stdout": None if expects_binary else raw[stdout[0].name],
        "stdout_path": stdout[0],
        "stdout_digest": None,
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

def valid_shell_identity(value):
    group = r"[0-9]+(?:\([A-Za-z0-9_.-]+\))?"
    shell_id_re = re.compile(
        r"uid=2000\(shell\) gid=2000\(shell\)"
        rf"(?: groups=(?P<groups>{group}(?:,{group})*))?"
        r"(?: context=(?P<context>u:r:shell:s[0-9]+(?:-s[0-9]+)?"
        r"(?::c[0-9]+(?:,c[0-9]+)*)?))?"
    )
    match = shell_id_re.fullmatch(value)
    if match is None:
        return False
    groups = match.group("groups")
    if groups:
        for entry in groups.split(","):
            parsed = re.fullmatch(
                r"(?P<id>[0-9]+)(?:\((?P<name>[A-Za-z0-9_.-]+)\))?",
                entry,
            )
            if parsed is None:
                return False
            if int(parsed.group("id")) == 0 or parsed.group("name") == "root":
                return False
    return True

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
if carriers["shell-id"]["stderr"] or not valid_shell_identity(scalar("shell-id")):
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

def archive_directory_preflight(read_at, archive_size, kind):
    member_limit = (
        archive_limits["apk_members"]
        if kind == "apk"
        else archive_limits["services_members"]
        if kind == "services"
        else -1
    )
    if member_limit < 0 or archive_size < 22:
        return "INVALID"

    def exact(offset, length):
        if offset < 0 or length < 0 or offset + length > archive_size:
            return None
        try:
            value = read_at(offset, length)
        except (OSError, ValueError):
            return None
        return value if value is not None and len(value) == length else None

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
    _signature, disk, directory_disk, disk_entries, entries, directory_size, directory_offset, _comment = fields
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
            zip64_signature, zip64_record_size, _made_by, _needed,
            actual_disk, actual_directory_disk, actual_disk_entries,
            actual_entries, actual_directory_size, actual_directory_offset,
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
        if any(legacy not in {sentinel, actual} for legacy, sentinel, actual in legacy_pairs):
            return "INVALID"
        entries = actual_entries
        directory_size = actual_directory_size
        directory_offset = actual_directory_offset
        directory_boundary = zip64_offset
    elif disk != 0 or directory_disk != 0 or disk_entries != entries:
        return "INVALID"

    if entries > member_limit:
        return "LIMIT"
    directory_end = directory_offset + directory_size
    if (
        directory_offset < 0
        or directory_size < 0
        or directory_end != directory_boundary
        or directory_end > archive_size
    ):
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
        if actual_entries > member_limit:
            return "LIMIT"
        cursor += record_size
    if cursor != directory_end or actual_entries != entries:
        return "INVALID"
    return "OK"

def archive_metadata_within_limits(members, kind):
    member_limit = (
        archive_limits["apk_members"]
        if kind == "apk"
        else archive_limits["services_members"]
        if kind == "services"
        else -1
    )
    if member_limit < 0 or len(members) > member_limit:
        return False
    total_size = 0
    total_compressed_size = 0
    for member in members:
        if member.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
            return False
        if member.file_size < 0 or member.compress_size < 0:
            return False
        if member.file_size > archive_limits["single_size"]:
            return False
        total_size += member.file_size
        total_compressed_size += member.compress_size
        if total_size > archive_limits["total_size"]:
            return False
        if member.file_size > (
            member.compress_size * archive_limits["ratio"]
            + archive_limits["ratio_slack"]
        ):
            return False
    if total_size > (
        total_compressed_size * archive_limits["ratio"]
        + archive_limits["ratio_slack"]
    ):
        return False
    return True

class BoundedArchiveStream:
    def __init__(self, stream, size):
        self.stream = stream
        self.size = size
        self.position = 0

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=io.SEEK_SET):
        if whence == io.SEEK_SET:
            target = offset
        elif whence == io.SEEK_CUR:
            target = self.position + offset
        elif whence == io.SEEK_END:
            target = self.size + offset
        else:
            raise ValueError("invalid seek origin")
        if target < 0 or target > self.size:
            raise OSError("archive seek escaped the frozen size")
        self.stream.seek(target, io.SEEK_SET)
        self.position = target
        return target

    def read(self, amount=-1):
        remaining = self.size - self.position
        if amount is None or amount < 0 or amount > remaining:
            amount = remaining
        value = self.stream.read(amount)
        self.position += len(value)
        return value

def stable_archive_file(path, kind):
    read_limit = file_read_limits.get(str(path))
    if read_limit is None or read_limit[1] is None:
        raise SystemExit(f"archive has no typed lane read limit: {path}")
    marker = read_limit[1]
    descriptor = -1
    stream = None
    try:
        named_before = path.lstat()
        if named_before.st_size > read_limit[0]:
            typed_stop(marker, f"{path.name} exceeds its lane file limit")
        if (
            not stat.S_ISREG(named_before.st_mode)
            or stat.S_ISLNK(named_before.st_mode)
            or named_before.st_uid != os.geteuid()
            or stat.S_IMODE(named_before.st_mode) != 0o600
            or named_before.st_nlink != 1
            or has_extended_acl(path)
        ):
            raise OSError("unsafe archive receipt ownership/mode")
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
        )
        opened_before = os.fstat(descriptor)
        if (
            inode_state(named_before) != inode_state(opened_before)
            or not stat.S_ISREG(opened_before.st_mode)
            or opened_before.st_uid != os.geteuid()
            or stat.S_IMODE(opened_before.st_mode) != 0o600
            or opened_before.st_nlink != 1
        ):
            raise OSError("archive receipt changed before open")
        key = str(path)
        current_state = inode_state(opened_before)
        if key in file_states and file_states[key] != current_state:
            raise OSError("archive receipt identity changed")
        stream = os.fdopen(descriptor, "rb", buffering=0)
        descriptor = -1
        bounded = BoundedArchiveStream(stream, opened_before.st_size)

        def read_at(offset, length):
            bounded.seek(offset)
            return bounded.read(length)

        preflight = archive_directory_preflight(
            read_at,
            opened_before.st_size,
            kind,
        )
        if preflight == "LIMIT":
            typed_stop(marker, f"{kind} archive member count exceeds its fixed limit")
        if preflight != "OK":
            return None
        preflight_after = os.fstat(stream.fileno())
        if preflight_after.st_size > read_limit[0]:
            typed_stop(marker, f"{path.name} grew beyond its lane file limit")
        if inode_state(preflight_after) != current_state:
            raise OSError("archive changed during central-directory preflight")
        bounded.seek(0)
        with zipfile.ZipFile(bounded) as archive:
            members = archive.infolist()
            if not archive_metadata_within_limits(members, kind):
                typed_stop(marker, f"{kind} archive metadata exceeds a fixed limit")
            if any(member.filename != member.orig_filename for member in members):
                return None
            names = [member.orig_filename for member in members]
            if not members or len(names) != len(set(names)):
                return None
            for name in names:
                parts = pathlib.PurePosixPath(name).parts
                if not name or name.startswith("/") or ".." in parts or "\x00" in name:
                    return None
            if kind == "apk" and names.count("AndroidManifest.xml") != 1:
                return None
            if kind == "services" and not any(
                re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name) for name in names
            ):
                return None
            if kind not in {"apk", "services"} or archive.testzip() is not None:
                return None
        bounded.seek(0)
        digest = hashlib.sha256()
        remaining = opened_before.st_size
        while remaining:
            chunk = bounded.read(min(1024 * 1024, remaining))
            if not chunk:
                raise OSError("archive truncated while hashing validated bytes")
            digest.update(chunk)
            remaining -= len(chunk)
        opened_after = os.fstat(stream.fileno())
        named_after = path.lstat()
        if opened_after.st_size > read_limit[0]:
            typed_stop(marker, f"{path.name} grew beyond its lane file limit")
        if (
            inode_state(opened_before) != inode_state(opened_after)
            or inode_state(opened_after) != inode_state(named_after)
            or opened_after.st_nlink != 1
        ):
            raise OSError("archive changed while validating")
        digest_hex = digest.hexdigest()
        if key in file_digests and file_digests[key] != digest_hex:
            raise OSError("archive receipt digest changed")
        file_states.setdefault(key, current_state)
        file_digests.setdefault(key, digest_hex)
        return digest_hex
    except (RuntimeError, ValueError, zipfile.BadZipFile, zipfile.LargeZipFile):
        return None
    except OSError as error:
        raise SystemExit(f"unsafe or unstable archive receipt: {path}: {error}")
    finally:
        if stream is not None:
            try:
                stream.close()
            except OSError:
                pass
        if descriptor >= 0:
            os.close(descriptor)

def installed_base_path(stem, package):
    expected_argv[stem] = ("-s", serial, "shell", "pm", "path", package)
    path_stdout_raw = carriers[stem]["stdout"]
    if carriers[stem]["stderr"]:
        raise SystemExit(f"{stem}: installed path emitted stderr")
    require_rc(stem)
    try:
        path_stdout = path_stdout_raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SystemExit(f"{stem}: path stdout is not UTF-8: {error}")
    if "\x00" in path_stdout or not path_stdout.endswith("\n"):
        raise SystemExit(f"{stem}: installed path framing mismatch")
    path_stdout = path_stdout.replace("\r\n", "\n")
    if "\r" in path_stdout:
        raise SystemExit(f"{stem}: installed path contains bare CR")
    package_paths = []
    for line in path_stdout[:-1].split("\n"):
        if not line.startswith("package:"):
            raise SystemExit(f"{stem}: installed path malformed")
        candidate = line[len("package:"):]
        match = safe_path_re.fullmatch(candidate)
        if (
            not match
            or match.group(1) in {".", ".."}
            or match.group(2) in {".", ".."}
            or not match.group(2).startswith(package + "-")
        ):
            raise SystemExit(f"{stem}: unsafe installed path")
        package_paths.append((candidate, match.group(3)))
    if not package_paths or len({path for path, _ in package_paths}) != len(package_paths):
        raise SystemExit(f"{stem}: empty or duplicate installed path")
    base_paths = [path for path, leaf in package_paths if leaf == "base"]
    if len(base_paths) != 1:
        raise SystemExit(f"{stem}: expected exactly one base APK")
    return base_paths[0]

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
    package_path = installed_base_path(path_stem, package)

    dumpsys_stem = f"package-{package_stem}-dumpsys"
    pidof_stem = f"package-{package_stem}-pidof"
    appops_stem = f"package-{package_stem}-appops"
    pre_path_stem = f"package-{package_stem}-path-pre-apk"
    apk_stem = f"package-{package_stem}-apk"
    post_path_stem = f"package-{package_stem}-path-post-apk"
    expected_argv[dumpsys_stem] = ("-s", serial, "shell", "dumpsys", "package", package)
    expected_argv[pidof_stem] = ("-s", serial, "shell", "pidof", package)
    expected_argv[appops_stem] = (
        "-s", serial, "shell", "appops", "get", "--user", "0", package,
        "android:mock_location",
    )
    pre_package_path = installed_base_path(pre_path_stem, package)
    post_package_path = installed_base_path(post_path_stem, package)
    if package_path != pre_package_path or package_path != post_package_path:
        raise SystemExit(f"{package}: base APK path changed across collection")
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
    archive_digest = stable_archive_file(carriers[apk_stem]["stdout_path"], "apk")
    if archive_digest is None:
        raise SystemExit(f"{apk_stem}: invalid APK archive")
    carriers[apk_stem]["stdout_digest"] = archive_digest

require_rc("services-jar")
services_archive_digest = stable_archive_file(
    carriers["services-jar"]["stdout_path"], "services"
)
if services_archive_digest is None:
    raise SystemExit("services.jar archive invalid")
carriers["services-jar"]["stdout_digest"] = services_archive_digest

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
adb_snapshot_digest = stable_file_digest(adb_path, expected_mode=0o500)
if adb_snapshot_digest != adb_digest:
    raise SystemExit("adb executable digest mismatch")
if sha256_bytes(collector_bytes) != collector_digest:
    raise SystemExit("collector executable digest mismatch")
if sha256_bytes(collector_bytes) != expected_collector_digest:
    raise SystemExit("current collector does not match the reviewed digest")
if services_archive_digest != services_digest:
    raise SystemExit("services.jar digest mismatch")
for package in installed:
    stem = f"package-{package.replace('.', '-')}-apk"
    if carriers[stem]["stdout_digest"] != apk_digests[package]:
        raise SystemExit(f"APK digest mismatch: {package}")

actual_entries = bounded_directory_names(receipts, 512, "receipt directory")
if actual_entries != accounted:
    raise SystemExit("unmanifested or missing receipt entry")

tree_digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
for name_text in sorted(accounted, key=lambda value: value.encode("utf-8")):
    name = name_text.encode("utf-8")
    stable_file_digest(
        receipts / name_text,
        tree_digest=tree_digest,
        tree_name=name,
    )
if tree_digest.hexdigest() != receipt_tree_digest:
    raise SystemExit("receipt tree digest mismatch")

if bounded_directory_names(tooling, 1, "tooling directory") != tooling_names:
    raise SystemExit("tooling directory contains an unexpected entry")
if bounded_directory_names(root, 4, "evidence root") != root_names:
    raise SystemExit("evidence root contains an unexpected entry")

# Re-open every authenticated byte source and re-check directory identities.
# This rejects a verifier result assembled from multiple pathname snapshots.
if (
    stable_file_digest(manifest_path) != sha256_bytes(manifest_bytes)
    or stable_file_digest(summary_path) != sha256_bytes(summary_bytes)
):
    raise SystemExit("manifest or summary changed during verification")
if stable_file_digest(adb_path, expected_mode=0o500) != adb_snapshot_digest:
    raise SystemExit("adb snapshot changed during verification")
if stable_repo_bytes(collector_path, collector_size_limit) != collector_bytes:
    raise SystemExit("collector changed during verification")
if stable_repo_bytes(allowlist_path, allowlist_size_limit) != allowlist_bytes:
    raise SystemExit("ADB allowlist changed during verification")
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
    case "$detail" in
      STOP_APK_ARCHIVE_LIMIT:*|STOP_FRAMEWORK_ARCHIVE_LIMIT:*)
        printf '%s\n' "$detail" >&2
        ;;
      *) printf 'STOP_INCOMPLETE_RECEIPT: %s\n' "$detail" >&2 ;;
    esac
    return 21
  fi
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

valid_shell_identity() { # complete canonical Android toybox `id` scalar
  "$PYTHON_BIN" -I - "$1" <<'PY' >/dev/null 2>&1
import re
import sys

value = sys.argv[1]
group = r"[0-9]+(?:\([A-Za-z0-9_.-]+\))?"
shell_id_re = re.compile(
    r"uid=2000\(shell\) gid=2000\(shell\)"
    rf"(?: groups=(?P<groups>{group}(?:,{group})*))?"
    r"(?: context=(?P<context>u:r:shell:s[0-9]+(?:-s[0-9]+)?"
    r"(?::c[0-9]+(?:,c[0-9]+)*)?))?"
)
match = shell_id_re.fullmatch(value)
if match is None:
    raise SystemExit(1)
groups = match.group("groups")
if groups:
    for entry in groups.split(","):
        parsed = re.fullmatch(r"(?P<id>[0-9]+)(?:\((?P<name>[A-Za-z0-9_.-]+)\))?", entry)
        if parsed is None:
            raise SystemExit(1)
        if int(parsed.group("id")) == 0 or parsed.group("name") == "root":
            raise SystemExit(1)
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

archive_stdout_limit() { # apk|services
  local budget timeout_seconds stdout_limit stderr_limit
  budget="$(receipt_budget "$1")" || return 1
  IFS=$'\t' read -r timeout_seconds stdout_limit stderr_limit <<<"$budget"
  printf '%d\n' "$stdout_limit"
}

valid_archive_file() { # path apk|services; stdout=digest<TAB>identity, rc2=limit
  local file_limit
  file_limit="$(archive_stdout_limit "$2")" || return 1
  "$PYTHON_BIN" -I - "$1" "$2" "$file_limit" \
    "$APK_ARCHIVE_MEMBER_LIMIT" "$FRAMEWORK_ARCHIVE_MEMBER_LIMIT" \
    "$ARCHIVE_SINGLE_UNCOMPRESSED_LIMIT" \
    "$ARCHIVE_TOTAL_UNCOMPRESSED_LIMIT" "$ARCHIVE_RATIO_LIMIT" \
    "$ARCHIVE_RATIO_SLACK" <<'PY' 2>/dev/null
import hashlib
import io
import os
import pathlib
import re
import stat
import struct
import sys
import zipfile

path = pathlib.Path(sys.argv[1])
kind = sys.argv[2]
file_limit = int(sys.argv[3])
archive_limits = {
    "apk_members": int(sys.argv[4]),
    "services_members": int(sys.argv[5]),
    "single_size": int(sys.argv[6]),
    "total_size": int(sys.argv[7]),
    "ratio": int(sys.argv[8]),
    "ratio_slack": int(sys.argv[9]),
}

class ArchiveLimitError(Exception):
    pass

def archive_directory_preflight(read_at, archive_size, kind):
    member_limit = (
        archive_limits["apk_members"]
        if kind == "apk"
        else archive_limits["services_members"]
        if kind == "services"
        else -1
    )
    if member_limit < 0 or archive_size < 22:
        return "INVALID"

    def exact(offset, length):
        if offset < 0 or length < 0 or offset + length > archive_size:
            return None
        try:
            value = read_at(offset, length)
        except (OSError, ValueError):
            return None
        return value if value is not None and len(value) == length else None

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
    _signature, disk, directory_disk, disk_entries, entries, directory_size, directory_offset, _comment = fields
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
            zip64_signature, zip64_record_size, _made_by, _needed,
            actual_disk, actual_directory_disk, actual_disk_entries,
            actual_entries, actual_directory_size, actual_directory_offset,
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
        if any(legacy not in {sentinel, actual} for legacy, sentinel, actual in legacy_pairs):
            return "INVALID"
        entries = actual_entries
        directory_size = actual_directory_size
        directory_offset = actual_directory_offset
        directory_boundary = zip64_offset
    elif disk != 0 or directory_disk != 0 or disk_entries != entries:
        return "INVALID"

    if entries > member_limit:
        return "LIMIT"
    directory_end = directory_offset + directory_size
    if (
        directory_offset < 0
        or directory_size < 0
        or directory_end != directory_boundary
        or directory_end > archive_size
    ):
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
        if actual_entries > member_limit:
            return "LIMIT"
        cursor += record_size
    if cursor != directory_end or actual_entries != entries:
        return "INVALID"
    return "OK"

def archive_metadata_within_limits(members, kind):
    member_limit = (
        archive_limits["apk_members"]
        if kind == "apk"
        else archive_limits["services_members"]
        if kind == "services"
        else -1
    )
    if member_limit < 0 or len(members) > member_limit:
        return False
    total_size = 0
    total_compressed_size = 0
    for member in members:
        if member.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
            return False
        if member.file_size < 0 or member.compress_size < 0:
            return False
        if member.file_size > archive_limits["single_size"]:
            return False
        total_size += member.file_size
        total_compressed_size += member.compress_size
        if total_size > archive_limits["total_size"]:
            return False
        if member.file_size > (
            member.compress_size * archive_limits["ratio"]
            + archive_limits["ratio_slack"]
        ):
            return False
    if total_size > (
        total_compressed_size * archive_limits["ratio"]
        + archive_limits["ratio_slack"]
    ):
        return False
    return True

def inode_state(value):
    return (
        value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode),
        value.st_uid, value.st_gid, stat.S_IMODE(value.st_mode),
        value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns,
    )

def identity_text(value):
    return ":".join(str(item) for item in inode_state(value))

class BoundedArchiveStream:
    def __init__(self, stream, size):
        self.stream = stream
        self.size = size
        self.position = 0

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=io.SEEK_SET):
        if whence == io.SEEK_SET:
            target = offset
        elif whence == io.SEEK_CUR:
            target = self.position + offset
        elif whence == io.SEEK_END:
            target = self.size + offset
        else:
            raise ValueError("invalid seek origin")
        if target < 0 or target > self.size:
            raise OSError("archive seek escaped the frozen size")
        self.stream.seek(target, io.SEEK_SET)
        self.position = target
        return target

    def read(self, amount=-1):
        remaining = self.size - self.position
        if amount is None or amount < 0 or amount > remaining:
            amount = remaining
        value = self.stream.read(amount)
        self.position += len(value)
        return value

descriptor = -1
stream = None
try:
    named_before = path.lstat()
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0),
    )
    opened_before = os.fstat(descriptor)
    if (
        inode_state(named_before) != inode_state(opened_before)
        or not stat.S_ISREG(opened_before.st_mode)
        or opened_before.st_uid != os.geteuid()
        or stat.S_IMODE(opened_before.st_mode) != 0o600
        or opened_before.st_nlink != 1
    ):
        raise OSError("archive identity or mode is unsafe")
    if opened_before.st_size > file_limit:
        raise ArchiveLimitError("archive file exceeds its lane profile")
    stream = os.fdopen(descriptor, "rb", buffering=0)
    descriptor = -1
    bounded = BoundedArchiveStream(stream, opened_before.st_size)

    def read_at(offset, length):
        bounded.seek(offset)
        return bounded.read(length)

    preflight = archive_directory_preflight(
        read_at,
        opened_before.st_size,
        kind,
    )
    if preflight == "LIMIT":
        raise ArchiveLimitError("archive member count exceeds its fixed limit")
    if preflight != "OK":
        raise ValueError("archive central directory is invalid")
    preflight_after = os.fstat(stream.fileno())
    if preflight_after.st_size > file_limit:
        raise ArchiveLimitError("archive grew beyond its lane profile")
    if inode_state(preflight_after) != inode_state(opened_before):
        raise OSError("archive changed during central-directory preflight")
    bounded.seek(0)
    with zipfile.ZipFile(bounded) as archive:
        members = archive.infolist()
        if not archive_metadata_within_limits(members, kind):
            raise ArchiveLimitError("archive metadata exceeds a fixed limit")
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
    bounded.seek(0)
    digest = hashlib.sha256()
    remaining = opened_before.st_size
    while remaining:
        chunk = bounded.read(min(1024 * 1024, remaining))
        if not chunk:
            raise OSError("archive truncated while hashing validated bytes")
        digest.update(chunk)
        remaining -= len(chunk)
    opened_after = os.fstat(stream.fileno())
    named_after = path.lstat()
    if opened_after.st_size > file_limit:
        raise ArchiveLimitError("archive grew beyond its lane profile")
    if (
        inode_state(opened_before) != inode_state(opened_after)
        or inode_state(opened_after) != inode_state(named_after)
    ):
        raise OSError("archive changed while validating")
    print(f"{digest.hexdigest()}\t{identity_text(opened_after)}")
except ArchiveLimitError:
    raise SystemExit(2)
except (OSError, RuntimeError, ValueError, zipfile.BadZipFile, zipfile.LargeZipFile):
    raise SystemExit(1)
finally:
    if stream is not None:
        try:
            stream.close()
        except OSError:
            pass
    if descriptor >= 0:
        os.close(descriptor)
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
      --reviewed-head) (( $# >= 2 )) || { usage; exit 2; }; REVIEWED_HEAD=$2; shift 2 ;;
      --reviewed-collector-sha256)
        (( $# >= 2 )) || { usage; exit 2; }
        REVIEWED_COLLECTOR_SHA256=$2
        shift 2
        ;;
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
  [[ -f $PYTHON_BIN && -x $PYTHON_BIN ]] \
    || stop_now STOP_INTERNAL_PYTHON_RUNTIME
  select_adb_approval_lane

  if [[ -n $VERIFY_DIR ]]; then
    [[ -z $ADB_BIN && -z $REQUESTED_SERIAL && -z $OUTPUT_DIR ]] || { usage; exit 2; }
    validate_review_binding
    verify_receipts "$VERIFY_DIR"
    local verify_rc=$?
    (( verify_rc == 0 )) || exit "$verify_rc"
    review_binding_intact || stop_now STOP_REVIEW_BINDING_CHANGED
    printf 'RECEIPTS_COMPLETE\n'
    exit 0
  fi

  [[ -n $ADB_BIN ]] || { usage; exit 2; }

  if (( CLASSIFY_ONLY )); then
    [[ -z $REVIEWED_HEAD && -z $REVIEWED_COLLECTOR_SHA256 ]] || { usage; exit 2; }
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

  validate_review_binding

  if [[ -n ${ADB_SERVER_SOCKET+x} || -n ${ANDROID_ADB_SERVER_ADDRESS+x} \
      || -n ${ANDROID_ADB_SERVER_PORT+x} ]]; then
    stop_now STOP_UNSAFE_ADB_SERVER_ENV
  fi

  [[ $REQUESTED_SERIAL == "$AUTHORIZED_SERIAL" ]] || stop_now STOP_WRONG_SERIAL
  [[ -n $OUTPUT_DIR ]] || { usage; exit 2; }
  validate_adb_binary
  validate_adb_approval
  review_binding_intact || stop_now STOP_REVIEW_BINDING_CHANGED
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

  local -a archive_binding_names archive_binding_digests archive_binding_identities
  local -a tree_archive_args
  archive_binding_names=()
  archive_binding_digests=()
  archive_binding_identities=()
  tree_archive_args=()

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
  valid_shell_identity "$shell_identity" \
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
  local package package_stem package_path package_i query_rc path_check_rc
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

    matching_package_path_receipt "package-$package_stem-path-pre-apk" \
      "$package" "$package_path"
    path_check_rc=$?
    case "$path_check_rc" in
      0) ;;
      2) stop_now STOP_ADB_READ_FAILED ;;
      *) stop_now STOP_PACKAGE_PATH_CHANGED ;;
    esac

    run_binary_receipt "package-$package_stem-apk" \
      -s "$AUTHORIZED_SERIAL" exec-out cat "$package_path"
    (( LAST_RC == 0 )) || stop_now STOP_APK_READ_FAILED

    matching_package_path_receipt "package-$package_stem-path-post-apk" \
      "$package" "$package_path"
    path_check_rc=$?
    case "$path_check_rc" in
      0) ;;
      2) stop_now STOP_ADB_READ_FAILED ;;
      *) stop_now STOP_PACKAGE_PATH_CHANGED ;;
    esac

    [[ -s $OUTPUT_DIR/receipts/package-$package_stem-apk.stdout.bin ]] \
      || stop_now STOP_APK_READ_FAILED
    local archive_record archive_digest archive_identity archive_extra
    archive_record="$(valid_archive_file \
      "$OUTPUT_DIR/receipts/package-$package_stem-apk.stdout.bin" apk)"
    path_check_rc=$?
    case "$path_check_rc" in
      0) ;;
      2) stop_now STOP_APK_ARCHIVE_LIMIT ;;
      *) stop_now STOP_APK_READ_FAILED ;;
    esac
    IFS=$'\t' read -r archive_digest archive_identity archive_extra \
      <<<"$archive_record"
    [[ $archive_record != *$'\n'* && -z $archive_extra \
        && $archive_digest =~ ^[0-9a-f]{64}$ \
        && $archive_identity =~ ^[0-9]+(:[0-9]+){9}$ ]] \
      || stop_now STOP_INTERNAL_HASH_FAILED
    PACKAGE_APK_SHA256[package_i]=$archive_digest
    archive_binding_names+=("package-$package_stem-apk.stdout.bin")
    archive_binding_digests+=("$archive_digest")
    archive_binding_identities+=("$archive_identity")
  done

  run_binary_receipt services-jar \
    -s "$AUTHORIZED_SERIAL" exec-out cat /system/framework/services.jar
  (( LAST_RC == 0 )) || stop_now STOP_FRAMEWORK_READ_FAILED
  [[ -s $OUTPUT_DIR/receipts/services-jar.stdout.bin ]] \
    || stop_now STOP_FRAMEWORK_READ_FAILED
  local archive_record archive_digest archive_identity archive_extra
  archive_record="$(valid_archive_file \
    "$OUTPUT_DIR/receipts/services-jar.stdout.bin" services)"
  path_check_rc=$?
  case "$path_check_rc" in
    0) ;;
    2) stop_now STOP_FRAMEWORK_ARCHIVE_LIMIT ;;
    *) stop_now STOP_FRAMEWORK_READ_FAILED ;;
  esac
  IFS=$'\t' read -r archive_digest archive_identity archive_extra \
    <<<"$archive_record"
  [[ $archive_record != *$'\n'* && -z $archive_extra \
      && $archive_digest =~ ^[0-9a-f]{64}$ \
      && $archive_identity =~ ^[0-9]+(:[0-9]+){9}$ ]] \
    || stop_now STOP_INTERNAL_HASH_FAILED
  SERVICES_JAR_SHA256=$archive_digest
  archive_binding_names+=(services-jar.stdout.bin)
  archive_binding_digests+=("$archive_digest")
  archive_binding_identities+=("$archive_identity")

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

  local archive_i tree_record tree_rc
  for ((archive_i = 0; archive_i < ${#archive_binding_names[@]}; archive_i++)); do
    tree_archive_args+=(
      "${archive_binding_names[archive_i]}"
      "${archive_binding_digests[archive_i]}"
      "${archive_binding_identities[archive_i]}"
    )
  done
  tree_record="$(sha256_receipt_tree \
    "$OUTPUT_DIR/receipts" "${tree_archive_args[@]}")"
  tree_rc=$?
  case "$tree_rc" in
    0) ;;
    2) stop_now STOP_APK_ARCHIVE_LIMIT ;;
    3) stop_now STOP_FRAMEWORK_ARCHIVE_LIMIT ;;
    *) stop_now STOP_INTERNAL_HASH_FAILED ;;
  esac
  RECEIPT_TREE_SHA256=$tree_record
  [[ $RECEIPT_TREE_SHA256 =~ ^[0-9a-f]{64}$ ]] || stop_now STOP_INTERNAL_HASH_FAILED
  publish_collected_bundle
  printf 'COLLECTED evidence=%s compatibility=STATIC_ANALYSIS_PENDING adbApprovalLane=%s\n' \
    "$OUTPUT_DISPLAY_PATH" "$ADB_APPROVAL_LANE"
}

main "$@"

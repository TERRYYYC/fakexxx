#!/bin/bash -p
#
# verify-a-plus.sh — aggregate verification gate for the A+ baseline.
#
# Spec: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §14.
#
# The full A+ gate is assembled across several PRs, so this script is stage
# aware. Two rules keep it honest while the repository is incomplete:
#
#   1. A gate that is required at the requested stage but whose script does not
#      exist is a FAILURE, never a skip. A skip would let a PR print a green
#      aggregate line that means nothing.
#   2. Gates belonging to a later stage are printed as PENDING with the PR that
#      owns them, and the summary never claims coverage they do not have.
#
# Usage:
#   ./scripts/verify-a-plus.sh                 # --stage full (strictest)
#   ./scripts/verify-a-plus.sh --stage import  # gates required as of PR-1
#   ./scripts/verify-a-plus.sh --list
#
# Exit codes: 0 = every gate required at this stage passed; 1 = otherwise.

set -uo pipefail
unset BASH_ENV ENV
unset DEVELOPER_DIR SDKROOT TOOLCHAINS
PATH=/usr/bin:/bin
export PATH

inspect_inherited_environment() {
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 70
  /usr/bin/python3 -I - <<'PY'
import os

if hasattr(os, "environb"):
    environment_names = os.environb.keys()
else:
    environment_names = (os.fsencode(name) for name in os.environ)
if any(name.startswith(b"BASH_FUNC_") for name in environment_names):
    raise SystemExit(78)
PY
}

inherited_environment_status=0
inspect_inherited_environment || inherited_environment_status=$?
case "$inherited_environment_status" in
  0) ;;
  78)
    printf '%s\n' 'VERIFY_A_PLUS_UNSAFE_INHERITED_BASH_FUNCTION_ENV' >&2
    exit 1
    ;;
  *)
    printf '%s\n' 'VERIFY_A_PLUS_INHERITED_ENVIRONMENT_INSPECTION_UNAVAILABLE' >&2
    exit 1
    ;;
esac
unset inherited_environment_status

# Repository discovery starts only after startup hooks are cleared and host lookup is fixed.
REPO_ROOT="$(cd "$(/usr/bin/dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$REPO_ROOT" || exit 1
readonly requested_java_home="${JAVA_HOME:-}"
unset JAVA_HOME
readonly requested_android_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
readonly java_profile_validator="$REPO_ROOT/scripts/validate-java17-runtime.py"
readonly java_runtime_stager="$REPO_ROOT/scripts/stage-java17-runtime.py"
readonly android_sdk_validator="$REPO_ROOT/scripts/validate-android-sdk-runtime.py"
readonly verify_temp_anchor="$REPO_ROOT/integration-tests/pr63-on-issue66/harness"
readonly verify_temp_parent="$verify_temp_anchor/build"
host_java_home=""
host_java_binding=""

STAGE="full"
LIST_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    # See check-provenance.sh: `shift 2` with one argument left fails without
    # advancing $#, so a value-less `--stage` spins forever. Same fix here.
    --stage)
      if [ $# -lt 2 ]; then
        printf 'verify-a-plus: --stage requires a value (import | contract | full)\n' >&2
        exit 1
      fi
      STAGE="$2"; shift 2 ;;
    --stage=*) STAGE="${1#*=}"; shift ;;
    --list) LIST_ONLY=1; shift ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'verify-a-plus: unknown argument "%s"\n' "$1" >&2; exit 1 ;;
  esac
done

# Stage ordering. A stage runs every gate introduced at or before it.
case "$STAGE" in
  import)   STAGE_RANK=1 ;;
  contract) STAGE_RANK=2 ;;
  full)     STAGE_RANK=3 ;;
  *)
    printf 'verify-a-plus: unknown stage "%s" (expected: import | contract | full)\n' "$STAGE" >&2
    exit 1
    ;;
esac

# rank|gate name|owning PR|required file|command
#
# Lint is NOT run as `lintDebug` here. At the imported baseline that command
# exits non-zero on apps/qianwangyou because of 23 pre-existing upstream lint
# errors (neither upstream repo had CI, so lint had never been enforced). The
# `inherited-lint-debt` gate runs lint for both apps and fails only if the
# frozen error inventory grows — the debt stays visible instead of being
# silenced with a lint baseline or a continue-on-error step. Its disposition is
# tracked in docs/provenance/upstream-imports.md.
# Stage 3 ("full") is the A+ acceptance gate. It must contain the gates that
# prove scenarios actually ran, not only the ones that prove code compiles.
# Without them, a tree with zero acceptance scenarios and an empty evidence
# ledger would satisfy `--stage full` — a green light for something nobody
# tested. These entries are declared before their scripts exist precisely so
# that `--stage full` FAILS today with "REQUIRED but missing" instead of
# quietly passing.
GATES="
1|provenance|PR-1|scripts/check-provenance.sh|./scripts/check-provenance.sh --stage \$STAGE
1|auto-unit-tests|PR-1|apps/cellrebel-auto/gradlew|cd apps/cellrebel-auto && ./gradlew testDebugUnitTest --no-daemon
1|auto-assemble|PR-1|apps/cellrebel-auto/gradlew|cd apps/cellrebel-auto && ./gradlew assembleDebug --no-daemon
1|qwy-unit-tests|PR-1|apps/qianwangyou/gradlew|cd apps/qianwangyou && ./gradlew testDebugUnitTest --no-daemon
1|qwy-assemble|PR-1|apps/qianwangyou/gradlew|cd apps/qianwangyou && ./gradlew assembleDebug --no-daemon
1|inherited-lint-debt|PR-1|scripts/check-inherited-lint-debt.sh|./scripts/check-inherited-lint-debt.sh
2|contract-v1|PR-2|scripts/check-contract-v1.sh|./scripts/check-contract-v1.sh
3|acceptance-scenarios|PR-5|acceptance/scenarios|cd acceptance && ./gradlew test --no-daemon
3|matrix-coverage|PR-5|scripts/check-matrix-coverage.sh|./scripts/check-matrix-coverage.sh
3|forbidden-boundaries|PR-5|acceptance/scripts/check-forbidden-boundaries.sh|./acceptance/scripts/check-forbidden-boundaries.sh
3|auto-qwy-host|PR-6|integration-tests/pr63-on-issue66/run-host-gate.sh|/bin/bash -p ./integration-tests/pr63-on-issue66/run-host-gate.sh
3|release-debt|PR-2|scripts/check-release-debt.sh|./scripts/check-release-debt.sh
"
readonly GATES

readonly EXPECTED_GATE_COUNT=12
readonly EXPECTED_GATE_NAMES="provenance,auto-unit-tests,auto-assemble,qwy-unit-tests,qwy-assemble,inherited-lint-debt,contract-v1,acceptance-scenarios,matrix-coverage,forbidden-boundaries,auto-qwy-host,release-debt"
readonly EXPECTED_GATE_MANIFEST_SHA256="fcac0011972a263f61972d4810c3cc3388b4022b3cb1e44c6bcbfbe67ea3c358"
if ! manifest_sha256="$(printf '%s' "$GATES" | /usr/bin/python3 -I -c \
  'import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())')" ||
  [ "$manifest_sha256" != "$EXPECTED_GATE_MANIFEST_SHA256" ]; then
  printf '%s\n' 'VERIFY_A_PLUS_GATE_MANIFEST_INCOMPLETE' >&2
  exit 1
fi
unset manifest_sha256
manifest_count=0
manifest_names=""
expected_active=0
expected_pending=0
manifest_invalid=0
while IFS='|' read -r rank name pr file cmd extra; do
  [ -z "${rank:-}" ] && continue
  case "$rank" in 1|2|3) ;; *) manifest_invalid=1 ;; esac
  case "$name" in
    ''|*[!a-z0-9-]*) manifest_invalid=1 ;;
  esac
  case "$pr" in PR-[1-9]|PR-[1-9][0-9]*) ;; *) manifest_invalid=1 ;; esac
  if [ -z "$file" ] || [ -z "$cmd" ] || [ -n "${extra:-}" ]; then
    manifest_invalid=1
  fi
  manifest_count=$((manifest_count + 1))
  if [ -z "$manifest_names" ]; then
    manifest_names="$name"
  else
    manifest_names="$manifest_names,$name"
  fi
  if [ "$rank" -gt "$STAGE_RANK" ]; then
    expected_pending=$((expected_pending + 1))
  else
    expected_active=$((expected_active + 1))
  fi
done <<EOF
$(printf '%s\n' "$GATES")
EOF
if [ "$manifest_invalid" -ne 0 ] ||
  [ "$manifest_count" -ne "$EXPECTED_GATE_COUNT" ] ||
  [ "$manifest_names" != "$EXPECTED_GATE_NAMES" ]; then
  printf '%s\n' 'VERIFY_A_PLUS_GATE_MANIFEST_INCOMPLETE' >&2
  exit 1
fi
readonly expected_active expected_pending
unset manifest_invalid manifest_count manifest_names

if [ "$LIST_ONLY" -eq 1 ]; then
  printf 'Gate manifest (requested stage: %s)\n\n' "$STAGE"
  printf '%-24s %-8s %-8s %s\n' GATE STAGE OWNER PRESENT
  while IFS='|' read -r rank name pr file _cmd; do
    [ -z "${rank:-}" ] && continue
    case "$rank" in 1) s=import ;; 2) s=contract ;; *) s=full ;; esac
    if [ -e "$file" ]; then present=yes; else present=no; fi
    printf '%-24s %-8s %-8s %s\n' "$name" "$s" "$pr" "$present"
  done <<EOF
$(printf '%s\n' "$GATES")
EOF
  exit 0
fi

read_java_binding_field() {
  local binding="$1" field="$2"
  /usr/bin/python3 -I - "$binding" "$field" <<'PY'
import json
import sys

raw = sys.argv[1]
field = sys.argv[2]
expected_keys = {
    "schemaVersion",
    "profileId",
    "javaHome",
    "os",
    "arch",
    "javaMajor",
    "javaVendor",
    "javaVmVendor",
    "javaRuntimeVersion",
    "jdkTreeSha256",
}


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


try:
    if not raw or len(raw.encode("utf-8")) > 8192:
        raise ValueError("binding size")
    value = json.loads(raw, object_pairs_hook=reject_duplicate_keys)
    canonical = json.dumps(
        value,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    )
except (UnicodeEncodeError, ValueError, json.JSONDecodeError):
    raise SystemExit(1)
if (
    not isinstance(value, dict)
    or set(value) != expected_keys
    or canonical != raw
    or type(value.get("schemaVersion")) is not int
    or value["schemaVersion"] != 1
    or type(value.get("javaMajor")) is not int
    or value["javaMajor"] != 17
    or field != "javaHome"
):
    raise SystemExit(1)
result = value.get(field)
if (
    not isinstance(result, str)
    or not result
    or result != result.strip()
    or any(delimiter in result for delimiter in ("\x00", "\r", "\n"))
):
    raise SystemExit(1)
print(result)
PY
}

stage_java_runtime() {
  local candidate="$1" stage_root="$2"
  [ -f /usr/bin/python3 ] && [ -x /usr/bin/python3 ] || return 1
  [ -f "$java_runtime_stager" ] && [ ! -L "$java_runtime_stager" ] || return 1
  /usr/bin/python3 -I "$java_runtime_stager" "$candidate" "$stage_root"
}

verify_java_runtime_binding() {
  [ -n "$host_java_home" ] && [ -n "$host_java_binding" ] || return 1
  [ -f "$java_profile_validator" ] && [ ! -L "$java_profile_validator" ] || return 1
  /usr/bin/python3 -I "$java_profile_validator" \
    --verify-binding "$host_java_home" "$host_java_binding" >/dev/null
}

create_verify_temp_root() {
  local anchor="$1"
  /usr/bin/python3 -I - "$anchor" <<'PY'
import errno
import os
import pathlib
import secrets
import stat
import subprocess
import sys

anchor = pathlib.Path(sys.argv[1])
try:
    if not anchor.is_absolute() or anchor.resolve(strict=True) != anchor:
        raise OSError("anchor is not one physical absolute directory")
except OSError:
    raise SystemExit(1)
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit(1)
directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)


def dev_inode(value):
    return value.st_dev, value.st_ino


def directory_is_empty(directory_fd):
    with os.scandir(directory_fd) as entries:
        return next(entries, None) is None


def has_extended_acl(path):
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except AttributeError:
        attributes = ()
    except OSError as error:
        unsupported = {
            item
            for item in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if item is not None
        }
        if error.errno not in unsupported:
            raise
        attributes = ()
    if any(
        attribute in {"system.posix_acl_access", "system.posix_acl_default"}
        for attribute in attributes
    ):
        return True
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/bin/ls", "-lde", os.fspath(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"},
            check=False,
        )
        if result.returncode != 0 or not result.stdout.splitlines():
            raise OSError("cannot inspect directory ACL")
        lines = result.stdout.splitlines()
        mode_token = lines[0].split(maxsplit=1)[0]
        return "+" in mode_token or any(
            line.lstrip().split(":", 1)[0].isdigit() for line in lines[1:]
        )
    return False


def validate_directory(descriptor, path, expected_mode=None):
    opened = os.fstat(descriptor)
    named = os.stat(path, follow_symlinks=False)
    if (
        not stat.S_ISDIR(opened.st_mode)
        or not stat.S_ISDIR(named.st_mode)
        or opened.st_uid != os.geteuid()
        or named.st_uid != os.geteuid()
        or stat.S_IMODE(opened.st_mode) & 0o022
        or stat.S_IMODE(named.st_mode) & 0o022
        or dev_inode(opened) != dev_inode(named)
        or (expected_mode is not None and stat.S_IMODE(opened.st_mode) != expected_mode)
        or (expected_mode is not None and stat.S_IMODE(named.st_mode) != expected_mode)
        or has_extended_acl(path)
    ):
        raise OSError("directory identity, owner, mode, or ACL is unsafe")
    opened_after = os.fstat(descriptor)
    named_after = os.stat(path, follow_symlinks=False)
    if dev_inode(opened_after) != dev_inode(opened) or dev_inode(named_after) != dev_inode(opened):
        raise OSError("directory changed during ACL validation")
    return opened


anchor_fd = None
build_fd = None
root_fd = None
created_root_identity = None
root_name = None
try:
    anchor_fd = os.open(anchor, directory_flags)
    validate_directory(anchor_fd, anchor)
    try:
        os.mkdir("build", 0o700, dir_fd=anchor_fd)
    except FileExistsError:
        pass
    build_path = anchor / "build"
    build_fd = os.open("build", directory_flags, dir_fd=anchor_fd)
    validate_directory(build_fd, build_path)
    for _ in range(128):
        candidate = f"verify-a-plus.{secrets.token_hex(4)}"
        try:
            os.mkdir(candidate, 0o700, dir_fd=build_fd)
        except FileExistsError:
            continue
        root_name = candidate
        created = os.stat(root_name, dir_fd=build_fd, follow_symlinks=False)
        if not stat.S_ISDIR(created.st_mode) or created.st_uid != os.geteuid():
            raise OSError("new private root identity is unsafe")
        created_root_identity = dev_inode(created)
        break
    if root_name is None:
        raise OSError("private namespace exhausted")
    root_path = build_path / root_name
    root_fd = os.open(root_name, directory_flags, dir_fd=build_fd)
    validate_directory(root_fd, root_path, expected_mode=0o700)
    if not directory_is_empty(root_fd):
        raise OSError("new private root is not empty")
    print(root_path)
except BaseException as error:
    if root_name is not None and created_root_identity is not None and build_fd is not None:
        try:
            if root_fd is None:
                root_fd = os.open(root_name, directory_flags, dir_fd=build_fd)
            opened = os.fstat(root_fd)
            named = os.stat(root_name, dir_fd=build_fd, follow_symlinks=False)
            if (
                stat.S_ISDIR(opened.st_mode)
                and opened.st_uid == os.geteuid()
                and dev_inode(opened) == created_root_identity
                and dev_inode(named) == created_root_identity
                and directory_is_empty(root_fd)
            ):
                os.close(root_fd)
                root_fd = None
                confirmed = os.stat(root_name, dir_fd=build_fd, follow_symlinks=False)
                if dev_inode(confirmed) == created_root_identity:
                    os.rmdir(root_name, dir_fd=build_fd)
        except OSError:
            pass
    if isinstance(error, (KeyboardInterrupt, SystemExit)):
        raise
    raise SystemExit(1)
finally:
    if root_fd is not None:
        os.close(root_fd)
    if build_fd is not None:
        os.close(build_fd)
    if anchor_fd is not None:
        os.close(anchor_fd)
PY
}

create_private_java_runtime_root() {
  local private_root="$1"
  /usr/bin/python3 -I - "$private_root" <<'PY'
import os
import pathlib
import re
import secrets
import stat
import sys

private_root = pathlib.Path(sys.argv[1])
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit(1)
directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
root_fd = None
stage_fd = None


def directory_is_empty(directory_fd):
    with os.scandir(directory_fd) as entries:
        return next(entries, None) is None


try:
    if (
        not private_root.is_absolute()
        or private_root.resolve(strict=True) != private_root
        or re.fullmatch(r"verify-a-plus\.[0-9a-f]{8}", private_root.name) is None
    ):
        raise OSError("unsafe private root")
    root_fd = os.open(private_root, directory_flags)
    opened_root = os.fstat(root_fd)
    named_root = os.stat(private_root, follow_symlinks=False)
    if (
        not stat.S_ISDIR(opened_root.st_mode)
        or opened_root.st_uid != os.geteuid()
        or stat.S_IMODE(opened_root.st_mode) != 0o700
        or (opened_root.st_dev, opened_root.st_ino) != (named_root.st_dev, named_root.st_ino)
    ):
        raise OSError("unsafe private root identity")
    stage_name = None
    for _ in range(128):
        candidate = f"jdk-runtime.{secrets.token_hex(16)}"
        try:
            os.mkdir(candidate, 0o700, dir_fd=root_fd)
        except FileExistsError:
            continue
        stage_name = candidate
        break
    if stage_name is None:
        raise OSError("private JDK namespace exhausted")
    if re.fullmatch(r"jdk-runtime\.[0-9a-f]{32}", stage_name) is None:
        raise OSError("private JDK stage name is unsafe")
    stage_path = private_root / stage_name
    stage_fd = os.open(stage_name, directory_flags, dir_fd=root_fd)
    opened_stage = os.fstat(stage_fd)
    named_stage = os.stat(stage_name, dir_fd=root_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(opened_stage.st_mode)
        or opened_stage.st_uid != os.geteuid()
        or stat.S_IMODE(opened_stage.st_mode) != 0o700
        or (opened_stage.st_dev, opened_stage.st_ino)
        != (named_stage.st_dev, named_stage.st_ino)
        or not directory_is_empty(stage_fd)
    ):
        raise OSError("unsafe private JDK stage")
    print(stage_path)
except OSError:
    raise SystemExit(1)
finally:
    if stage_fd is not None:
        os.close(stage_fd)
    if root_fd is not None:
        os.close(root_fd)
PY
}

# Toolchain preconditions — reported once, explicitly, instead of surfacing as
# an opaque Gradle stack trace inside the first app gate.
if [ -z "$requested_java_home" ]; then
  printf 'verify-a-plus: JAVA_HOME must name an explicit Java 17 runtime\n' >&2
  exit 1
fi
if [ -z "$requested_android_home" ]; then
  printf 'verify-a-plus: Android SDK location unknown (set ANDROID_HOME or ANDROID_SDK_ROOT)\n' >&2
  exit 1
fi
emit_android_sdk_binding() {
  local candidate="$1"
  [ -f /usr/bin/python3 ] && [ -x /usr/bin/python3 ] || return 1
  [ -f "$android_sdk_validator" ] && [ ! -L "$android_sdk_validator" ] || return 1
  /usr/bin/python3 -I "$android_sdk_validator" --emit-binding "$candidate"
}

verify_android_sdk_binding() {
  [ -n "$host_android_home" ] && [ -n "$host_android_binding" ] || return 1
  /usr/bin/python3 -I "$android_sdk_validator" \
    --verify-binding "$host_android_home" "$host_android_binding" >/dev/null
}

host_android_home="$requested_android_home"
if ! host_android_binding="$(emit_android_sdk_binding "$requested_android_home")" ||
  [ -z "$host_android_binding" ] ||
  ! verify_android_sdk_binding; then
  printf '%s\n' 'VERIFY_A_PLUS_ANDROID_SDK_INVALID' >&2
  exit 1
fi
readonly host_android_home host_android_binding
for local_sdk_override in \
  "$REPO_ROOT/local.properties" \
  "$REPO_ROOT/apps/cellrebel-auto/local.properties" \
  "$REPO_ROOT/apps/qianwangyou/local.properties" \
  "$REPO_ROOT/acceptance/local.properties" \
  "$REPO_ROOT/integration-tests/pr63-on-issue66/local.properties"; do
  if [ -e "$local_sdk_override" ] || [ -L "$local_sdk_override" ]; then
    printf 'VERIFY_A_PLUS_LOCAL_SDK_OVERRIDE_PRESENT: %s\n' "$local_sdk_override" >&2
    exit 1
  fi
done
unset local_sdk_override

verify_temp_root=""
cleanup_verify_environment() {
  local original_status=$?
  trap '' HUP INT TERM
  trap - EXIT
  if [ -z "${verify_temp_root:-}" ]; then
    exit "$original_status"
  fi
  if ! /usr/bin/python3 -I - "$verify_temp_parent" "$verify_temp_root" <<'PY'
import errno
import os
import pathlib
import re
import stat
import subprocess
import sys

parent = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
if (
    not parent.is_absolute()
    or not target.is_absolute()
    or target.parent != parent
    or re.fullmatch(r"verify-a-plus\.[0-9a-f]{8}", target.name) is None
    or not hasattr(os, "O_DIRECTORY")
    or not hasattr(os, "O_NOFOLLOW")
):
    raise SystemExit(1)
directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)


def dev_inode(value):
    return value.st_dev, value.st_ino


def has_extended_acl(path):
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except AttributeError:
        attributes = ()
    except OSError as error:
        unsupported = {
            item
            for item in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if item is not None
        }
        if error.errno not in unsupported:
            raise
        attributes = ()
    if any(
        attribute in {"system.posix_acl_access", "system.posix_acl_default"}
        for attribute in attributes
    ):
        return True
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/bin/ls", "-lde", os.fspath(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"},
            check=False,
        )
        if result.returncode != 0 or not result.stdout.splitlines():
            raise OSError("cannot inspect cleanup parent ACL")
        lines = result.stdout.splitlines()
        mode_token = lines[0].split(maxsplit=1)[0]
        return "+" in mode_token or any(
            line.lstrip().split(":", 1)[0].isdigit() for line in lines[1:]
        )
    return False


try:
    parent_fd = os.open(parent, directory_flags)
except FileNotFoundError:
    raise SystemExit(1)
root_fd = None
seen = 0


def bounded_directory_names(directory_fd, remaining):
    if remaining < 0:
        raise OSError("cleanup entry count exceeded")
    names = []
    with os.scandir(directory_fd) as entries:
        for entry in entries:
            if len(names) >= remaining:
                raise OSError("cleanup entry count exceeded")
            names.append(entry.name)
    names.sort(key=os.fsencode)
    return names


def clear(directory_fd, depth):
    global seen
    if depth > 128:
        raise OSError("cleanup depth exceeded")
    for name in bounded_directory_names(directory_fd, 200000 - seen):
        seen += 1
        value = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        if stat.S_ISDIR(value.st_mode):
            if value.st_uid != os.geteuid():
                raise OSError("cleanup directory owner changed")
            if stat.S_IMODE(value.st_mode) != 0o700:
                os.chmod(name, 0o700, dir_fd=directory_fd, follow_symlinks=False)
                value = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            child_fd = os.open(name, directory_flags, dir_fd=directory_fd)
            try:
                if dev_inode(os.fstat(child_fd)) != dev_inode(value):
                    raise OSError("cleanup directory identity changed")
                clear(child_fd, depth + 1)
                if dev_inode(os.fstat(child_fd)) != dev_inode(value):
                    raise OSError("cleanup directory changed")
            finally:
                os.close(child_fd)
            os.rmdir(name, dir_fd=directory_fd)
        else:
            os.unlink(name, dir_fd=directory_fd)


try:
    parent_state = os.fstat(parent_fd)
    named_parent = os.stat(parent, follow_symlinks=False)
    if (
        not stat.S_ISDIR(parent_state.st_mode)
        or parent_state.st_uid != os.geteuid()
        or named_parent.st_uid != os.geteuid()
        or stat.S_IMODE(parent_state.st_mode) & 0o022
        or stat.S_IMODE(named_parent.st_mode) & 0o022
        or dev_inode(parent_state) != dev_inode(named_parent)
        or has_extended_acl(parent)
    ):
        raise OSError("cleanup parent is unsafe")
    parent_after_acl = os.fstat(parent_fd)
    named_parent_after_acl = os.stat(parent, follow_symlinks=False)
    if (
        dev_inode(parent_after_acl) != dev_inode(parent_state)
        or dev_inode(named_parent_after_acl) != dev_inode(parent_state)
        or parent_after_acl.st_uid != os.geteuid()
        or named_parent_after_acl.st_uid != os.geteuid()
        or stat.S_IMODE(parent_after_acl.st_mode) & 0o022
        or stat.S_IMODE(named_parent_after_acl.st_mode) & 0o022
    ):
        raise OSError("cleanup parent changed during ACL validation")
    try:
        value = os.stat(target.name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        raise SystemExit(0)
    if not stat.S_ISDIR(value.st_mode) or value.st_uid != os.geteuid():
        raise OSError("cleanup target is unsafe")
    if stat.S_IMODE(value.st_mode) != 0o700:
        os.chmod(target.name, 0o700, dir_fd=parent_fd, follow_symlinks=False)
        value = os.stat(target.name, dir_fd=parent_fd, follow_symlinks=False)
    root_fd = os.open(target.name, directory_flags, dir_fd=parent_fd)
    if dev_inode(os.fstat(root_fd)) != dev_inode(value):
        raise OSError("cleanup target identity changed")
    clear(root_fd, 0)
    if dev_inode(os.fstat(root_fd)) != dev_inode(value):
        raise OSError("cleanup target changed")
    os.close(root_fd)
    root_fd = None
    os.rmdir(target.name, dir_fd=parent_fd)
    parent_confirmed = os.fstat(parent_fd)
    named_parent_confirmed = os.stat(parent, follow_symlinks=False)
    if (
        dev_inode(parent_confirmed) != dev_inode(parent_state)
        or dev_inode(named_parent_confirmed) != dev_inode(parent_state)
        or parent_confirmed.st_uid != os.geteuid()
        or named_parent_confirmed.st_uid != os.geteuid()
        or stat.S_IMODE(parent_confirmed.st_mode) & 0o022
        or stat.S_IMODE(named_parent_confirmed.st_mode) & 0o022
        or has_extended_acl(parent)
    ):
        raise OSError("cleanup parent changed")
except OSError:
    raise SystemExit(1)
finally:
    if root_fd is not None:
        os.close(root_fd)
    os.close(parent_fd)
PY
  then
    printf 'VERIFY_A_PLUS_PRIVATE_ENVIRONMENT_CLEANUP_FAILED: %s\n' "$verify_temp_root" >&2
    original_status=1
  fi
  exit "$original_status"
}

trap cleanup_verify_environment EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
if ! verify_temp_root="$(create_verify_temp_root "$verify_temp_anchor")" ||
  [ -z "$verify_temp_root" ]; then
  printf '%s\n' 'VERIFY_A_PLUS_PRIVATE_ENVIRONMENT_UNAVAILABLE' >&2
  exit 1
fi
readonly verify_temp_root
if ! /bin/mkdir "$verify_temp_root/home" ||
  ! /bin/chmod 0700 "$verify_temp_root/home"; then
  printf '%s\n' 'VERIFY_A_PLUS_PRIVATE_ENVIRONMENT_UNAVAILABLE' >&2
  exit 1
fi
readonly verify_child_home="$verify_temp_root/home"
if ! host_java_stage_root="$(create_private_java_runtime_root "$verify_temp_root")" ||
  [ -z "$host_java_stage_root" ]; then
  printf '%s\n' 'VERIFY_A_PLUS_EPHEMERAL_JAVA_RUNTIME_PREPARATION_FAILED' >&2
  exit 1
fi
readonly host_java_stage_root
if ! host_java_binding="$(
  stage_java_runtime "$requested_java_home" "$host_java_stage_root"
)" || [ -z "$host_java_binding" ]; then
  printf '%s\n' 'VERIFY_A_PLUS_JAVA_RUNTIME_INVALID' >&2
  exit 1
fi
if ! host_java_home="$(read_java_binding_field "$host_java_binding" javaHome)" ||
  [ -z "$host_java_home" ] ||
  [[ "$host_java_home" != "$host_java_stage_root/home" ]] ||
  ! verify_java_runtime_binding; then
  printf '%s\n' 'VERIFY_A_PLUS_STAGED_JAVA_RUNTIME_INVALID' >&2
  exit 1
fi
readonly host_java_home host_java_binding

run_clean_gate_command() {
  [ "$#" -eq 1 ] || return 1
  local gate_gradle_user_home command_status=0
  if ! gate_gradle_user_home="$(
    /usr/bin/mktemp -d "$verify_temp_root/gradle-user-home.XXXXXXXX"
  )" || [ -z "$gate_gradle_user_home" ] ||
    ! /bin/chmod 0700 "$gate_gradle_user_home"; then
    printf '%s\n' 'VERIFY_A_PLUS_GATE_PRIVATE_ENVIRONMENT_UNAVAILABLE' >&2
    return 1
  fi
  if ! verify_java_runtime_binding; then
    printf '%s\n' 'VERIFY_A_PLUS_JAVA_RUNTIME_CHANGED' >&2
    return 1
  fi
  if ! verify_android_sdk_binding; then
    printf '%s\n' 'VERIFY_A_PLUS_ANDROID_SDK_CHANGED' >&2
    return 1
  fi
  /usr/bin/env -i \
    ADB=/usr/bin/false \
    ANDROID_HOME="$host_android_home" \
    ANDROID_SDK_ROOT="$host_android_home" \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_SYSTEM=/dev/null \
    GRADLE_USER_HOME="$gate_gradle_user_home" \
    HOME="$verify_child_home" \
    JAVA_HOME="$host_java_home" \
    LANG=C \
    LC_ALL=C \
    PATH=/usr/bin:/bin \
    STAGE="$STAGE" \
    /bin/bash -p -c "$1" </dev/null || command_status=$?
  if ! verify_java_runtime_binding; then
    printf '%s\n' 'VERIFY_A_PLUS_JAVA_RUNTIME_CHANGED' >&2
    return 1
  fi
  if ! verify_android_sdk_binding; then
    printf '%s\n' 'VERIFY_A_PLUS_ANDROID_SDK_CHANGED' >&2
    return 1
  fi
  return "$command_status"
}

RUN=0; PASSED=0; FAILED=0; PENDING=0
FAILED_NAMES=""; PENDING_NAMES=""
MANIFEST_SEEN=0; ACTIVE_SEEN=0
readonly HOST_RECEIPT="integration-tests/pr63-on-issue66/harness/build/reports/pr63-on-issue66/host-gate-receipt.json"
readonly HOST_RECEIPT_LOCK="${HOST_RECEIPT%/*}/host-gate.lock"
readonly HOST_GATE_RUNNER="$REPO_ROOT/integration-tests/pr63-on-issue66/run-host-gate.sh"
HOST_RECEIPT_VALIDATED=0

verify_host_receipt() {
  local receipt_path="$1"
  local lock_path="$2"
  local repo_root="$3"
  local runner_path="$4"

  if [[ ! -x /usr/bin/python3 || ! -x /usr/bin/env || ! -x /usr/bin/git ]]; then
    printf 'verify-a-plus: fixed /usr/bin/python3, /usr/bin/env and /usr/bin/git are required to validate the host-gate JSON receipt\n' >&2
    return 1
  fi

  /usr/bin/python3 -I - "$receipt_path" "$lock_path" "$repo_root" "$runner_path" <<'PY'
import errno
import hashlib
import json
import os
import re
import stat
import subprocess
import sys

receipt_path = sys.argv[1]
lock_path = sys.argv[2]
repo_root = os.path.abspath(sys.argv[3])
runner_path = os.path.abspath(sys.argv[4])
canonical_runner_relative = "integration-tests/pr63-on-issue66/run-host-gate.sh"
canonical_runner_path = os.path.join(repo_root, canonical_runner_relative)
if runner_path != canonical_runner_path:
    print(
        "verify-a-plus: host-gate runner path is not the canonical repository entrypoint",
        file=sys.stderr,
    )
    raise SystemExit(1)

expected = {
    "schemaVersion": 4,
    "hostIntegration": "PASS",
    "issue66Ac7": "NOT_PASSED",
    "emulator": "NOT_RUN",
    "physicalDevice": "NOT_RUN",
    "deviceFull": "BLOCKED",
    "overall": "BLOCKED",
    "sourceState": "CLEAN",
    "reason": (
        "HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__"
        "ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_"
        "ADDITIONAL_AUTHORIZATION"
    ),
}
binding_keys = {
    "sourceHead",
    "sourceTree",
    "runnerSha256",
    "runId",
    "jdkProfileId",
    "jdkRuntimeVersion",
    "jdkTreeSha256",
    "gradleAttestationAutoSha256",
    "gradleAttestationQwySha256",
    "gradleAttestationHarnessSha256",
}

class ReceiptSchemaError(ValueError):
    pass


class SourceBindingError(ValueError):
    pass


class GradleAttestationError(ValueError):
    pass


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ReceiptSchemaError(f"duplicate JSON key: {key!r}")
        value[key] = item
    return value


def reject_constant(value):
    raise ReceiptSchemaError(f"non-finite JSON constant: {value}")


def file_identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_uid,
        value.st_gid,
        value.st_nlink,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def directory_location(value):
    return (value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode))


def has_extended_acl(path):
    """Reject access grants that POSIX mode bits do not disclose."""
    try:
        attributes = os.listxattr(path, follow_symlinks=False)
    except AttributeError:
        attributes = ()
    except OSError as error:
        unsupported = {
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        }
        if error.errno not in unsupported:
            raise
        attributes = ()
    normalized_attributes = {
        os.fsdecode(attribute) if isinstance(attribute, bytes) else attribute
        for attribute in attributes
    }
    if normalized_attributes.intersection(
        {"system.posix_acl_access", "system.posix_acl_default"}
    ):
        return True
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/bin/ls", "-lde", os.fspath(path)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            env={"PATH": "/usr/bin:/bin", "LC_ALL": "C", "LANG": "C"},
            check=False,
        )
        if result.returncode != 0 or not result.stdout.splitlines():
            raise OSError(f"cannot inspect extended ACL: {path}")
        output_lines = result.stdout.splitlines()
        mode_token = output_lines[0].split(maxsplit=1)[0]
        return "+" in mode_token or any(
            line.lstrip().split(":", 1)[0].isdigit() for line in output_lines[1:]
        )
    return False


def open_directory_nofollow(directory_path):
    if not os.path.isabs(directory_path):
        raise OSError("directory walk requires an absolute path")
    normalized = os.path.normpath(directory_path)
    components = [component for component in normalized.split(os.sep) if component]
    if any(component in {".", ".."} for component in components):
        raise OSError("directory walk contains an unsafe component")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
    current_fd = os.open(os.sep, flags)
    try:
        for component in components:
            next_fd = os.open(component, flags, dir_fd=current_fd)
            try:
                opened_state = os.fstat(next_fd)
                named_state = os.stat(
                    component,
                    dir_fd=current_fd,
                    follow_symlinks=False,
                )
                if (
                    not stat.S_ISDIR(opened_state.st_mode)
                    or directory_location(opened_state) != directory_location(named_state)
                ):
                    raise OSError(
                        f"directory component changed or is not a directory: {component!r}"
                    )
            except BaseException:
                os.close(next_fd)
                raise
            os.close(current_fd)
            current_fd = next_fd
        return current_fd
    except BaseException:
        os.close(current_fd)
        raise


def confirm_directory_path(directory_path, expected_state):
    confirmed_fd = open_directory_nofollow(directory_path)
    try:
        confirmed_state = os.fstat(confirmed_fd)
        if directory_location(confirmed_state) != directory_location(expected_state):
            raise OSError("directory path resolves to a different inode")
        return confirmed_state
    finally:
        os.close(confirmed_fd)


def fixed_git(*arguments):
    command = [
        "/usr/bin/env",
        "-i",
        "LC_ALL=C",
        "LANG=C",
        "PATH=/usr/bin:/bin",
        "GIT_ATTR_NOSYSTEM=1",
        "GIT_CONFIG_NOSYSTEM=1",
        "GIT_CONFIG_SYSTEM=/dev/null",
        "GIT_CONFIG_GLOBAL=/dev/null",
        "GIT_CONFIG_COUNT=0",
        "GIT_OPTIONAL_LOCKS=0",
        "/usr/bin/git",
        "--no-replace-objects",
        "-c",
        "core.hooksPath=/dev/null",
        "-c",
        "core.fsmonitor=false",
        "-c",
        "core.untrackedCache=false",
        "-c",
        "core.trustctime=true",
        "-c",
        "core.checkStat=default",
        "-c",
        "core.fileMode=true",
        "-c",
        "core.excludesFile=/dev/null",
        "-c",
        "core.attributesFile=/dev/null",
        "-c",
        "core.ignoreCase=false",
        "-C",
        repo_root,
        *arguments,
    ]
    try:
        result = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=20,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise SourceBindingError(f"fixed git invocation failed: {error}") from error
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        if len(detail) > 512:
            detail = detail[:512] + "..."
        raise SourceBindingError(
            f"fixed git exited {result.returncode}: {detail or 'no diagnostic'}"
        )
    return result.stdout


def git_scalar(*arguments):
    raw_value = fixed_git(*arguments)
    if not raw_value.endswith(b"\n") or b"\n" in raw_value[:-1] or b"\x00" in raw_value:
        raise SourceBindingError(f"git {' '.join(arguments)} returned a non-scalar value")
    try:
        return raw_value[:-1].decode("utf-8")
    except UnicodeError as error:
        raise SourceBindingError(
            f"git {' '.join(arguments)} returned non-UTF-8 output"
        ) from error


def stable_runner_sha256():
    runner_parent_path = os.path.dirname(runner_path)
    runner_name = os.path.basename(runner_path)
    if runner_name in {"", ".", ".."}:
        raise SourceBindingError("canonical runner has an unsafe basename")
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise SourceBindingError("required runner no-follow/directory flags are unavailable")

    runner_flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_NONBLOCK", 0)
    runner_flags |= getattr(os, "O_CLOEXEC", 0)
    runner_parent_fd = None
    runner_fd = None
    try:
        runner_parent_fd = open_directory_nofollow(runner_parent_path)
        runner_parent_state = os.fstat(runner_parent_fd)
        if not stat.S_ISDIR(runner_parent_state.st_mode):
            raise SourceBindingError("canonical runner parent identity changed")

        runner_fd = os.open(runner_name, runner_flags, dir_fd=runner_parent_fd)
        runner_state = os.fstat(runner_fd)
        runner_path_state = os.stat(
            runner_name,
            dir_fd=runner_parent_fd,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISREG(runner_state.st_mode)
            or runner_state.st_uid != os.geteuid()
            or stat.S_IMODE(runner_state.st_mode) & 0o022
            or not stat.S_IMODE(runner_state.st_mode) & stat.S_IXUSR
            or runner_state.st_nlink != 1
            or file_identity(runner_state) != file_identity(runner_path_state)
        ):
            raise SourceBindingError(
                "canonical runner is not one owned, non-writable, executable regular file"
            )
        if runner_state.st_size > 1024 * 1024:
            raise SourceBindingError("canonical runner exceeds the 1 MiB source ceiling")

        runner_bytes = b""
        while True:
            chunk = os.read(runner_fd, 65536)
            if not chunk:
                break
            runner_bytes += chunk
            if len(runner_bytes) > 1024 * 1024:
                raise SourceBindingError("canonical runner exceeds the 1 MiB source ceiling")

        runner_state_after = os.fstat(runner_fd)
        runner_path_state_after = os.stat(
            runner_name,
            dir_fd=runner_parent_fd,
            follow_symlinks=False,
        )
        runner_parent_state_after = os.fstat(runner_parent_fd)
        confirmed_runner_parent_state = confirm_directory_path(
            runner_parent_path,
            runner_parent_state,
        )
        if (
            file_identity(runner_state_after) != file_identity(runner_state)
            or file_identity(runner_path_state_after) != file_identity(runner_state)
            or directory_location(runner_parent_state_after)
            != directory_location(runner_parent_state)
            or directory_location(confirmed_runner_parent_state)
            != directory_location(runner_parent_state)
        ):
            raise SourceBindingError("canonical runner identity changed during digest read")
        return hashlib.sha256(runner_bytes).hexdigest()
    except OSError as error:
        raise SourceBindingError(f"cannot stably read canonical runner: {error}") from error
    finally:
        if runner_fd is not None:
            os.close(runner_fd)
        if runner_parent_fd is not None:
            os.close(runner_parent_fd)


def nul_records(raw, label):
    if not raw:
        return []
    if not raw.endswith(b"\x00"):
        raise SourceBindingError(f"{label} is not NUL terminated")
    return raw[:-1].split(b"\x00")


def safe_source_path(path):
    components = path.split(b"/")
    return (
        bool(path)
        and not path.startswith(b"/")
        and all(component not in {b"", b".", b".."} for component in components)
        and components[0] != b".git"
    )


def parse_head_entries(raw):
    entries = {}
    for record in nul_records(raw, "HEAD tree listing"):
        try:
            metadata, path = record.split(b"\t", 1)
            mode, object_type, object_id = metadata.split(b" ")
        except ValueError as error:
            raise SourceBindingError("HEAD tree contains a malformed entry") from error
        if (
            not safe_source_path(path)
            or path in entries
            or object_type != b"blob"
            or mode not in {b"100644", b"100755", b"120000"}
            or re.fullmatch(b"[0-9a-f]{40}", object_id) is None
        ):
            raise SourceBindingError(
                "HEAD tree contains an unsupported, unsafe, or duplicate entry"
            )
        entries[path] = (mode, object_id)
    if not entries:
        raise SourceBindingError("HEAD tree is empty")
    return entries


def parse_index_entries(raw):
    entries = {}
    for record in nul_records(raw, "repository index listing"):
        try:
            metadata, path = record.split(b"\t", 1)
            tag, mode, object_id, stage = metadata.split(b" ")
        except ValueError as error:
            raise SourceBindingError("repository index contains a malformed entry") from error
        if (
            tag != b"H"
            or stage != b"0"
            or not safe_source_path(path)
            or path in entries
            or mode not in {b"100644", b"100755", b"120000"}
            or re.fullmatch(b"[0-9a-f]{40}", object_id) is None
        ):
            raise SourceBindingError(
                "repository index flags, stage, mode, object, or path are not plain"
            )
        entries[path] = (mode, object_id)
    return entries


def raw_source_manifest(source_head):
    head_tree = fixed_git("ls-tree", "-r", "-z", "--full-tree", source_head)
    index = fixed_git("ls-files", "--stage", "-v", "-z", "--cached")
    head_entries = parse_head_entries(head_tree)
    index_entries = parse_index_entries(index)
    if head_entries != index_entries:
        raise SourceBindingError("repository index content or mode does not equal HEAD")
    return head_tree, index, head_entries


def source_directory_is_safe(value):
    return (
        stat.S_ISDIR(value.st_mode)
        and value.st_uid == os.geteuid()
        and not stat.S_IMODE(value.st_mode) & 0o022
    )


if sys.platform == "darwin":
    import ctypes

    darwin_libc = ctypes.CDLL(None, use_errno=True)
    darwin_acl_get_fd = darwin_libc.acl_get_fd_np
    darwin_acl_get_fd.argtypes = [ctypes.c_int, ctypes.c_int]
    darwin_acl_get_fd.restype = ctypes.c_void_p
    darwin_acl_free = darwin_libc.acl_free
    darwin_acl_free.argtypes = [ctypes.c_void_p]
    darwin_acl_free.restype = ctypes.c_int


def fd_has_extended_acl(descriptor_fd):
    """Inspect the pinned source inode without resolving its pathname again."""
    if sys.platform == "darwin":
        ctypes.set_errno(0)
        acl = darwin_acl_get_fd(descriptor_fd, 0x00000100)
        if not acl:
            error_number = ctypes.get_errno()
            if error_number == errno.ENOENT:
                return False
            unsupported = {
                value
                for value in (
                    getattr(errno, "ENOTSUP", None),
                    getattr(errno, "EOPNOTSUPP", None),
                )
                if value is not None
            }
            if error_number in unsupported:
                return False
            raise OSError(error_number, "cannot inspect pinned Darwin ACL")
        ctypes.set_errno(0)
        if darwin_acl_free(acl) != 0:
            error_number = ctypes.get_errno()
            raise OSError(error_number, "cannot release pinned Darwin ACL")
        return True

    try:
        attributes = os.listxattr(descriptor_fd)
    except AttributeError as error:
        raise OSError("descriptor xattr inspection is unavailable") from error
    except OSError as error:
        unsupported = {
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        }
        if error.errno in unsupported:
            return False
        raise
    attribute_names = {os.fsdecode(attribute) for attribute in attributes}
    return bool(
        attribute_names
        & {"system.posix_acl_access", "system.posix_acl_default"}
    )


def validate_source_directory_fd(directory_fd, label):
    before = os.fstat(directory_fd)
    if not source_directory_is_safe(before):
        raise OSError(f"{label} identity, owner, or mode is unsafe")
    if fd_has_extended_acl(directory_fd):
        raise OSError(f"{label} has an extended ACL")
    after = os.fstat(directory_fd)
    if file_identity(after) != file_identity(before) or not source_directory_is_safe(after):
        raise OSError(f"{label} changed during ACL inspection")
    if fd_has_extended_acl(directory_fd):
        raise OSError(f"{label} acquired an extended ACL")
    confirmed = os.fstat(directory_fd)
    if (
        file_identity(confirmed) != file_identity(after)
        or not source_directory_is_safe(confirmed)
    ):
        raise OSError(f"{label} changed after ACL inspection")
    return confirmed


def open_source_parent(repo_fd, path):
    components = path.split(b"/")
    parent_fd = os.dup(repo_fd)
    try:
        for component in components[:-1]:
            next_fd = os.open(
                component,
                os.O_RDONLY
                | os.O_DIRECTORY
                | os.O_NOFOLLOW
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NONBLOCK", 0),
                dir_fd=parent_fd,
            )
            try:
                opened_state = validate_source_directory_fd(
                    next_fd,
                    "tracked source directory",
                )
                named_state = os.stat(
                    component,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                if (
                    not source_directory_is_safe(named_state)
                    or file_identity(opened_state) != file_identity(named_state)
                ):
                    raise OSError(
                        "tracked path traverses an unsafe or unstable directory"
                    )
                opened_state_confirmed = validate_source_directory_fd(
                    next_fd,
                    "tracked source directory",
                )
                named_state_confirmed = os.stat(
                    component,
                    dir_fd=parent_fd,
                    follow_symlinks=False,
                )
                if (
                    file_identity(opened_state_confirmed) != file_identity(opened_state)
                    or file_identity(named_state_confirmed) != file_identity(named_state)
                    or file_identity(opened_state_confirmed)
                    != file_identity(named_state_confirmed)
                ):
                    raise OSError("tracked source directory changed during validation")
            except BaseException:
                os.close(next_fd)
                raise
            os.close(parent_fd)
            parent_fd = next_fd
        validate_source_directory_fd(parent_fd, "tracked source parent")
        return parent_fd, components[-1]
    except BaseException:
        os.close(parent_fd)
        raise


def git_blob_sha1(payload):
    header = b"blob " + str(len(payload)).encode("ascii") + b"\x00"
    try:
        digest = hashlib.sha1(usedforsecurity=False)
    except TypeError:
        digest = hashlib.sha1()
    digest.update(header)
    digest.update(payload)
    return digest.hexdigest().encode("ascii")


def verify_raw_leaf(repo_fd, path, expected_mode, expected_object_id):
    parent_fd, leaf_name = open_source_parent(repo_fd, path)
    file_fd = None
    confirmed_parent_fd = None
    try:
        parent_state = validate_source_directory_fd(parent_fd, "tracked source parent")
        before = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
        if expected_mode == b"120000":
            if not stat.S_ISLNK(before.st_mode) or before.st_uid != os.geteuid():
                raise OSError("tracked symlink type or owner differs from HEAD")
            # A symlink's replacement authority lives on its pinned, ACL-checked
            # parent; Darwin does not expose an ACL on the symlink inode itself.
            payload = os.readlink(leaf_name, dir_fd=parent_fd)
            if isinstance(payload, str):
                payload = os.fsencode(payload)
            after = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
            if file_identity(after) != file_identity(before):
                raise OSError("tracked symlink changed during raw read")
        else:
            file_fd = os.open(
                leaf_name,
                os.O_RDONLY
                | os.O_NOFOLLOW
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NONBLOCK", 0),
                dir_fd=parent_fd,
            )
            opened_state = os.fstat(file_fd)
            if (
                not stat.S_ISREG(opened_state.st_mode)
                or opened_state.st_uid != os.geteuid()
                or before.st_uid != os.geteuid()
                or stat.S_IMODE(opened_state.st_mode) & 0o022
                or stat.S_IMODE(before.st_mode) & 0o022
                or file_identity(opened_state) != file_identity(before)
            ):
                raise OSError("tracked regular file identity, owner, or mode is unsafe")
            if fd_has_extended_acl(file_fd):
                raise OSError("tracked regular file has an extended ACL")
            opened_state_after_acl = os.fstat(file_fd)
            named_state_after_acl = os.stat(
                leaf_name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if (
                file_identity(opened_state_after_acl) != file_identity(opened_state)
                or file_identity(named_state_after_acl) != file_identity(opened_state)
            ):
                raise OSError("tracked regular file changed during ACL inspection")
            payload_buffer = bytearray()
            while True:
                chunk = os.read(file_fd, 65536)
                if not chunk:
                    break
                payload_buffer.extend(chunk)
                if len(payload_buffer) > 64 * 1024 * 1024:
                    raise OSError("tracked file exceeds the 64 MiB provenance ceiling")
            opened_state_after = os.fstat(file_fd)
            after = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
            if (
                file_identity(opened_state_after) != file_identity(opened_state)
                or file_identity(after) != file_identity(opened_state)
            ):
                raise OSError("tracked regular file changed during raw read")
            if fd_has_extended_acl(file_fd):
                raise OSError("tracked regular file acquired an extended ACL")
            opened_state_final = os.fstat(file_fd)
            named_state_final = os.stat(
                leaf_name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if (
                file_identity(opened_state_final) != file_identity(opened_state)
                or file_identity(named_state_final) != file_identity(opened_state)
            ):
                raise OSError("tracked regular file changed during final ACL inspection")
            actual_mode = (
                b"100755"
                if stat.S_IMODE(opened_state.st_mode) & 0o111
                else b"100644"
            )
            if actual_mode != expected_mode:
                raise OSError("tracked executable mode differs from HEAD")
            payload = bytes(payload_buffer)
        if git_blob_sha1(payload) != expected_object_id:
            raise OSError("tracked raw bytes differ from HEAD")

        parent_state_after = os.fstat(parent_fd)
        confirmed_parent_fd, _ = open_source_parent(repo_fd, path)
        confirmed_parent_state = validate_source_directory_fd(
            confirmed_parent_fd,
            "tracked source parent",
        )
        if (
            file_identity(parent_state_after) != file_identity(parent_state)
            or file_identity(confirmed_parent_state) != file_identity(parent_state)
            or not source_directory_is_safe(parent_state_after)
            or not source_directory_is_safe(confirmed_parent_state)
        ):
            raise OSError("tracked source parent changed during raw read")
        parent_state_final = validate_source_directory_fd(
            parent_fd,
            "tracked source parent",
        )
        confirmed_parent_state_final = os.fstat(confirmed_parent_fd)
        if (
            file_identity(parent_state_final) != file_identity(parent_state)
            or file_identity(confirmed_parent_state_final) != file_identity(parent_state)
        ):
            raise OSError("tracked source parent changed during final ACL inspection")
    finally:
        if confirmed_parent_fd is not None:
            os.close(confirmed_parent_fd)
        if file_fd is not None:
            os.close(file_fd)
        os.close(parent_fd)


def verify_raw_worktree(repo_fd, entries):
    try:
        repo_state = validate_source_directory_fd(repo_fd, "repository root")
        named_repo_state = os.stat(repo_root, follow_symlinks=False)
        if (
            not source_directory_is_safe(named_repo_state)
            or file_identity(repo_state) != file_identity(named_repo_state)
        ):
            raise OSError("repository root identity, owner, or mode is unsafe")
        repo_state_after_acl = validate_source_directory_fd(repo_fd, "repository root")
        named_repo_state_after_acl = os.stat(repo_root, follow_symlinks=False)
        if (
            file_identity(repo_state_after_acl) != file_identity(repo_state)
            or file_identity(named_repo_state_after_acl) != file_identity(repo_state)
        ):
            raise OSError("repository root changed during ACL inspection")

        for path, (expected_mode, expected_object_id) in entries.items():
            verify_raw_leaf(repo_fd, path, expected_mode, expected_object_id)
        repo_state_after = validate_source_directory_fd(repo_fd, "repository root")
        named_repo_state_after = os.stat(repo_root, follow_symlinks=False)
        if (
            file_identity(repo_state_after) != file_identity(repo_state)
            or file_identity(named_repo_state_after) != file_identity(repo_state)
            or not source_directory_is_safe(repo_state_after)
            or not source_directory_is_safe(named_repo_state_after)
        ):
            raise OSError("repository root changed during raw source verification")
        repo_state_final = validate_source_directory_fd(repo_fd, "repository root")
        named_repo_state_final = os.stat(repo_root, follow_symlinks=False)
        if (
            file_identity(repo_state_final) != file_identity(repo_state)
            or file_identity(named_repo_state_final) != file_identity(repo_state)
        ):
            raise OSError("repository root changed during final ACL inspection")
    except OSError as error:
        raise SourceBindingError(f"cannot verify raw tracked source: {error}") from error


def verify_raw_untracked():
    untracked_ignore_files = fixed_git(
        "ls-files",
        "--others",
        "-z",
        "--",
        ".gitignore",
        ":(glob)**/.gitignore",
    )
    if nul_records(untracked_ignore_files, "untracked .gitignore listing"):
        raise SourceBindingError("untracked .gitignore could alter ignore semantics")
    untracked = fixed_git(
        "ls-files",
        "--others",
        "-z",
        "--exclude-per-directory=.gitignore",
    )
    if nul_records(untracked, "untracked source listing"):
        raise SourceBindingError("repository contains non-committed, non-ignored source")


def head_runner_sha256(source_head):
    runner_blob = fixed_git(
        "cat-file",
        "blob",
        f"{source_head}:{canonical_runner_relative}",
    )
    if not runner_blob or len(runner_blob) > 1024 * 1024:
        raise SourceBindingError("canonical HEAD runner blob is empty or exceeds 1 MiB")
    return hashlib.sha256(runner_blob).hexdigest()


def read_source_binding():
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise SourceBindingError("required repository no-follow/directory flags are unavailable")
    repo_fd = None
    try:
        repo_fd = open_directory_nofollow(repo_root)
        repo_state = os.fstat(repo_fd)
        if not stat.S_ISDIR(repo_state.st_mode):
            raise SourceBindingError("repository root identity changed")

        observed_root = git_scalar("rev-parse", "--show-toplevel")
        if observed_root != repo_root:
            raise SourceBindingError(
                f"git top-level mismatch: observed={observed_root!r} expected={repo_root!r}"
            )
        source_head = git_scalar("rev-parse", "--verify", "HEAD^{commit}")
        source_tree = git_scalar("rev-parse", "--verify", "HEAD^{tree}")
        if not re.fullmatch(r"[0-9a-f]{40}", source_head):
            raise SourceBindingError("source HEAD is not one SHA-1 commit id")
        if not re.fullmatch(r"[0-9a-f]{40}", source_tree):
            raise SourceBindingError("source tree is not one SHA-1 tree id")
        manifest_before = raw_source_manifest(source_head)
        verify_raw_worktree(repo_fd, manifest_before[2])
        verify_raw_untracked()
        runner_sha256 = stable_runner_sha256()
        reviewed_runner_sha256 = head_runner_sha256(source_head)
        if runner_sha256 != reviewed_runner_sha256:
            raise SourceBindingError(
                "canonical runner bytes do not match the runner blob at source HEAD"
            )

        confirmed_head = git_scalar("rev-parse", "--verify", "HEAD^{commit}")
        confirmed_tree = git_scalar("rev-parse", "--verify", "HEAD^{tree}")
        manifest_after = raw_source_manifest(source_head)
        if (
            confirmed_head != source_head
            or confirmed_tree != source_tree
            or manifest_after[:2] != manifest_before[:2]
        ):
            raise SourceBindingError("HEAD or index changed during raw source binding")
        verify_raw_worktree(repo_fd, manifest_after[2])
        verify_raw_untracked()

        repo_state_after = os.fstat(repo_fd)
        confirmed_repo_state = confirm_directory_path(repo_root, repo_state)
        if (
            directory_location(repo_state_after) != directory_location(repo_state)
            or directory_location(confirmed_repo_state) != directory_location(repo_state)
        ):
            raise SourceBindingError("repository root identity changed during source binding")
        return {
            "sourceHead": source_head,
            "sourceTree": source_tree,
            "runnerSha256": runner_sha256,
        }
    except OSError as error:
        raise SourceBindingError(f"cannot pin repository root: {error}") from error
    finally:
        if repo_fd is not None:
            os.close(repo_fd)


gradle_attestation_specs = {
    "auto": (
        "gradleAttestationAutoSha256",
        ":app:testDebugUnitTest",
        ("com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest",),
    ),
    "qwy": (
        "gradleAttestationQwySha256",
        ":app:testDebugUnitTest",
        (
            "name.caiyao.fakegps.hook.oracle.Android15OracleHookPlanTest",
            "name.caiyao.fakegps.hook.oracle.SystemServerOracleWiringGuardTest",
            "name.caiyao.fakegps.integration.v1.AuthoritativeOracleProductionGuardTest",
            "name.caiyao.fakegps.integration.v1.BinderAuthoritativeContinuitySourceTest",
            "name.caiyao.fakegps.oracle.OracleBundleCodecTest",
            "name.caiyao.fakegps.integration.v1.AuthoritativeAdvanceProviderTest",
        ),
    ),
    "harness": (
        "gradleAttestationHarnessSha256",
        ":harness:testDebugUnitTest",
        (
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HarnessBoundaryGuardTest",
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HostRunnerEnvironmentGuardTest",
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HostReceiptModeGuardTest",
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HostEphemeralCleanupGuardTest",
        ),
    ),
}
gradle_attestation_keys = [
    "schemaVersion",
    "runId",
    "stage",
    "taskPath",
    "jdkHome",
    "jdkProfileId",
    "javaVendor",
    "javaVmVendor",
    "jdkRuntimeVersion",
    "jdkTreeSha256",
    "jdkMajor",
    "testLauncherMajor",
    "testCount",
    "failureCount",
    "classes",
]
# This offline consumer cannot reexecute a removed staged JDK. Bind its proof
# to the exact admitted identities; the standalone profile test checks this
# table against the runtime registry on both supported host platforms.
reviewed_java_profiles = {
    "darwin-aarch64-eclipse-temurin-17.0.20.1+1": (
        "Eclipse Adoptium", "Eclipse Adoptium", "17.0.20.1+1",
        "f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8",
    ),
    "linux-x86_64-eclipse-temurin-17.0.20.1+1": (
        "Eclipse Adoptium", "Eclipse Adoptium", "17.0.20.1+1",
        "427182064043c17bb698c7f9c5949f755f6dd80dddaf760b6fa7413178189a97",
    ),
}


def read_gradle_attestation(stage, expected_sha256, receipt):
    if stage not in gradle_attestation_specs:
        raise GradleAttestationError(f"unsupported stage: {stage!r}")
    receipt_run_id = receipt["runId"]
    name = f"gradle-attestation-{stage}-{receipt_run_id}.txt"
    if os.path.basename(name) != name:
        raise GradleAttestationError("derived filename is unsafe")
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    flags |= getattr(os, "O_CLOEXEC", 0)
    descriptor = None
    try:
        descriptor = os.open(name, flags, dir_fd=parent_fd)
        initial = os.fstat(descriptor)
        named_initial = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (
            not stat.S_ISREG(initial.st_mode)
            or initial.st_uid != os.geteuid()
            or stat.S_IMODE(initial.st_mode) != 0o600
            or initial.st_nlink != 1
            or initial.st_size < 1
            or initial.st_size > 16384
            or file_identity(initial) != file_identity(named_initial)
            or fd_has_extended_acl(descriptor)
        ):
            raise GradleAttestationError(
                f"{stage} proof identity, owner, mode, link count, size, or ACL is unsafe"
            )

        raw = b""
        while True:
            chunk = os.read(descriptor, 16385 - len(raw))
            if not chunk:
                break
            raw += chunk
            if len(raw) > 16384:
                raise GradleAttestationError(f"{stage} proof exceeds 16384 bytes")

        after_read = os.fstat(descriptor)
        named_after_read = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (
            file_identity(after_read) != file_identity(initial)
            or file_identity(named_after_read) != file_identity(initial)
            or fd_has_extended_acl(descriptor)
        ):
            raise GradleAttestationError(f"{stage} proof changed during its first read")

        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise GradleAttestationError(f"{stage} proof is not UTF-8") from error
        if not text.endswith("\n") or "\r" in text or "\x00" in text:
            raise GradleAttestationError(f"{stage} proof has unsafe text framing")
        lines = text[:-1].split("\n")
        if len(lines) != len(gradle_attestation_keys):
            raise GradleAttestationError(f"{stage} proof must contain exactly 15 lines")
        values = {}
        for expected_key, line in zip(gradle_attestation_keys, lines):
            key, separator, value = line.partition("=")
            if not separator or key != expected_key or key in values or not value:
                raise GradleAttestationError(
                    f"{stage} proof has a missing, duplicate, extra, or reordered field"
                )
            values[key] = value

        hash_value = hashlib.sha256(raw).hexdigest()
        if hash_value != expected_sha256:
            raise GradleAttestationError(f"{stage} proof digest does not match the receipt")

        _, expected_task, required_classes = gradle_attestation_specs[stage]
        if (
            values["schemaVersion"] != "2"
            or values["runId"] != receipt_run_id
            or values["stage"] != stage
            or values["taskPath"] != expected_task
            or values["jdkProfileId"] != receipt["jdkProfileId"]
            or values["jdkRuntimeVersion"] != receipt["jdkRuntimeVersion"]
            or values["jdkTreeSha256"] != receipt["jdkTreeSha256"]
            or values["jdkMajor"] != "17"
            or values["testLauncherMajor"] != "17"
            or not re.fullmatch(r"[1-9][0-9]*", values["testCount"])
            or values["failureCount"] != "0"
        ):
            raise GradleAttestationError(f"{stage} proof contract does not match the receipt")
        expected_jdk_home = re.escape(parent_path) + r"/jdk-runtime\.[0-9a-f]{32}/home"
        if re.fullmatch(expected_jdk_home, values["jdkHome"]) is None:
            raise GradleAttestationError(f"{stage} proof JDK home is not a staged sibling")
        observed_java_identity = tuple(values[field] for field in (
            "javaVendor", "javaVmVendor", "jdkRuntimeVersion", "jdkTreeSha256",
        ))
        if observed_java_identity != reviewed_java_profiles.get(values["jdkProfileId"]):
            raise GradleAttestationError(f"{stage} proof Java identity is not a registered profile")
        classes = values["classes"].split(",")
        if classes != sorted(set(classes)) or any(
            re.fullmatch(
                r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+",
                name,
            ) is None
            for name in classes
        ):
            raise GradleAttestationError(f"{stage} proof classes are unsafe or not canonical")
        for required_class in required_classes:
            if required_class not in classes:
                raise GradleAttestationError(
                    f"{stage} proof did not execute required class {required_class}"
                )

        os.lseek(descriptor, 0, os.SEEK_SET)
        confirmed_raw = b""
        while True:
            chunk = os.read(descriptor, 16385 - len(confirmed_raw))
            if not chunk:
                break
            confirmed_raw += chunk
            if len(confirmed_raw) > 16384:
                raise GradleAttestationError(f"{stage} proof changed beyond its size ceiling")
        confirmed = os.fstat(descriptor)
        named_confirmed = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (
            confirmed_raw != raw
            or file_identity(confirmed) != file_identity(initial)
            or file_identity(named_confirmed) != file_identity(initial)
            or fd_has_extended_acl(descriptor)
        ):
            raise GradleAttestationError(f"{stage} proof identity or content changed")
        return values
    except OSError as error:
        raise GradleAttestationError(f"cannot safely read {stage} proof: {error}") from error
    finally:
        if descriptor is not None:
            os.close(descriptor)


parent_path = os.path.abspath(os.path.dirname(receipt_path) or ".")
lock_parent_path = os.path.abspath(os.path.dirname(lock_path) or ".")
receipt_name = os.path.basename(receipt_path)
lock_name = os.path.basename(lock_path)
if (
    parent_path != lock_parent_path
    or receipt_name in {"", ".", ".."}
    or lock_name in {"", ".", ".."}
):
    print(
        "verify-a-plus: receipt and lock must be safe siblings in one directory",
        file=sys.stderr,
    )
    raise SystemExit(1)

parent_fd = None
lock_fd = None
try:
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise OSError("required no-follow/directory flags are unavailable")
    parent_fd = open_directory_nofollow(parent_path)
    parent_fd_state = os.fstat(parent_fd)
    if (
        not stat.S_ISDIR(parent_fd_state.st_mode)
        or stat.S_IMODE(parent_fd_state.st_mode) != 0o700
        or parent_fd_state.st_uid != os.geteuid()
        or has_extended_acl(parent_path)
    ):
        raise OSError("host-gate receipt parent identity, owner, mode or extended ACL is unsafe")
except OSError as error:
    if parent_fd is not None:
        os.close(parent_fd)
    print(
        f"verify-a-plus: cannot pin host-gate receipt parent: {error}",
        file=sys.stderr,
    )
    raise SystemExit(1)

try:
    os.mkdir(lock_name, 0o700, dir_fd=parent_fd)
except FileExistsError:
    os.close(parent_fd)
    print(
        "verify-a-plus: host-gate lock already exists; "
        "the canonical receipt is not authoritative",
        file=sys.stderr,
    )
    raise SystemExit(1)
except OSError as error:
    os.close(parent_fd)
    print(
        f"verify-a-plus: cannot acquire host-gate validation lock: {error}",
        file=sys.stderr,
    )
    raise SystemExit(1)

try:
    lock_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    lock_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
    lock_fd = os.open(lock_name, lock_flags, dir_fd=parent_fd)
    os.fchmod(lock_fd, 0o700)
    lock_fd_state = os.fstat(lock_fd)
    lock_path_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(lock_fd_state.st_mode)
        or stat.S_IMODE(lock_fd_state.st_mode) != 0o700
        or lock_fd_state.st_uid != os.geteuid()
        or (lock_fd_state.st_dev, lock_fd_state.st_ino)
        != (lock_path_state.st_dev, lock_path_state.st_ino)
        or has_extended_acl(lock_path)
    ):
        raise OSError("validation-lock directory identity or extended ACL changed")
except OSError as error:
    if lock_fd is not None:
        os.close(lock_fd)
    os.close(parent_fd)
    print(
        f"verify-a-plus: cannot pin owned host-gate validation lock: {error}; "
        "the lock is retained as a fail-closed fence",
        file=sys.stderr,
    )
    raise SystemExit(1)

owner_path = os.path.join(lock_path, "owner")
owner_token = (
    f"validator-pid={os.getpid()};ppid={os.getppid()};"
    f"nonce={os.urandom(16).hex()}\n"
).encode("ascii")
owner_fd = None
try:
    owner_flags = os.O_RDWR | os.O_CREAT | os.O_EXCL
    owner_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    owner_fd = os.open("owner", owner_flags, 0o600, dir_fd=lock_fd)
    os.fchmod(owner_fd, 0o600)
    remaining = memoryview(owner_token)
    while remaining:
        written = os.write(owner_fd, remaining)
        if written <= 0:
            raise OSError("short write while recording validation-lock ownership")
        remaining = remaining[written:]
    os.fsync(owner_fd)
    owner_fd_state_created = os.fstat(owner_fd)
    owner_path_state_created = os.stat("owner", dir_fd=lock_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(owner_fd_state_created.st_mode)
        or owner_fd_state_created.st_uid != os.geteuid()
        or stat.S_IMODE(owner_fd_state_created.st_mode) != 0o600
        or owner_fd_state_created.st_nlink != 1
        or (owner_fd_state_created.st_dev, owner_fd_state_created.st_ino)
        != (owner_path_state_created.st_dev, owner_path_state_created.st_ino)
        or has_extended_acl(owner_path)
    ):
        raise OSError("validation-lock owner identity or extended ACL is unsafe")
except OSError as error:
    if owner_fd is not None:
        os.close(owner_fd)
    os.close(lock_fd)
    os.close(parent_fd)
    print(
        f"verify-a-plus: cannot establish host-gate validation-lock ownership: {error}; "
        "the lock is retained as a fail-closed fence",
        file=sys.stderr,
    )
    raise SystemExit(1)


validation_error = None
source_binding_before = None
try:
    source_binding_before = read_source_binding()
    expected.update(source_binding_before)
except SourceBindingError as error:
    validation_error = f"host-gate source provenance invalid: {error}"

receipt_fd = None
try:
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_NONBLOCK"):
        raise OSError("required no-follow/nonblocking receipt flags are unavailable")
    receipt_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    receipt_flags |= getattr(os, "O_CLOEXEC", 0)
    receipt_fd = os.open(receipt_name, receipt_flags, dir_fd=parent_fd)
    receipt_fd_state = os.fstat(receipt_fd)
    receipt_path_state = os.stat(
        receipt_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    if (
        not stat.S_ISREG(receipt_fd_state.st_mode)
        or receipt_fd_state.st_nlink != 1
        or (receipt_fd_state.st_dev, receipt_fd_state.st_ino)
        != (receipt_path_state.st_dev, receipt_path_state.st_ino)
    ):
        raise OSError("host-gate receipt identity changed or is not a single regular file")
    if (
        receipt_fd_state.st_uid != os.geteuid()
        or stat.S_IMODE(receipt_fd_state.st_mode) & 0o022
        or has_extended_acl(receipt_path)
    ):
        raise OSError(
            "host-gate receipt permissions or owner are unsafe (including extended ACL)"
        )
    if receipt_fd_state.st_size > 4096:
        raise ReceiptSchemaError("receipt exceeds the 4096-byte contract ceiling")
    receipt_bytes = b""
    while True:
        chunk = os.read(receipt_fd, 4097 - len(receipt_bytes))
        if not chunk:
            break
        receipt_bytes += chunk
        if len(receipt_bytes) > 4096:
            raise ReceiptSchemaError("receipt exceeds the 4096-byte contract ceiling")
    receipt_fd_state_after = os.fstat(receipt_fd)
    receipt_path_state_after = os.stat(
        receipt_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    if (
        file_identity(receipt_fd_state_after) != file_identity(receipt_fd_state)
        or (receipt_path_state_after.st_dev, receipt_path_state_after.st_ino)
        != (receipt_fd_state.st_dev, receipt_fd_state.st_ino)
        or stat.S_ISLNK(receipt_path_state_after.st_mode)
    ):
        raise OSError("host-gate receipt identity changed during read")
    receipt_text = receipt_bytes.decode("utf-8")
    receipt = json.loads(
        receipt_text,
        object_pairs_hook=unique_object,
        parse_constant=reject_constant,
    )
except FileNotFoundError:
    if validation_error is None:
        validation_error = f"host gate passed but receipt is missing: {receipt_path}"
except ReceiptSchemaError as error:
    if validation_error is None:
        validation_error = f"host-gate receipt schema mismatch: {error}"
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    if validation_error is None:
        validation_error = f"invalid host-gate JSON receipt: {error}"

if validation_error is None and type(receipt) is not dict:
    validation_error = "host-gate receipt schema mismatch: root must be an object"

expected_keys = set(expected) | binding_keys
if validation_error is None and set(receipt) != expected_keys:
    missing = sorted(expected_keys - set(receipt))
    extra = sorted(set(receipt) - expected_keys)
    validation_error = (
        "host-gate receipt schema mismatch: "
        f"missing={missing!r}; extra={extra!r}"
    )

if validation_error is None:
    type_mismatches = [
        f"{field} has type {type(receipt[field]).__name__} "
        f"(expected {type(expected_value).__name__})"
        for field, expected_value in expected.items()
        if type(receipt[field]) is not type(expected_value)
    ]
    type_mismatches.extend(
        f"{field} has type {type(receipt[field]).__name__} (expected str)"
        for field in binding_keys
        if field not in expected and type(receipt[field]) is not str
    )
    if type_mismatches:
        validation_error = (
            "host-gate receipt schema mismatch: " + "; ".join(type_mismatches)
        )

if validation_error is None:
    receipt_format_errors = []
    if not re.fullmatch(r"[0-9a-f]{32}", receipt["runId"]):
        receipt_format_errors.append("runId must be 32 lowercase hex characters")
    if not re.fullmatch(r"[a-z0-9][a-z0-9._+-]{0,127}", receipt["jdkProfileId"]):
        receipt_format_errors.append("jdkProfileId has an unsafe format")
    if not re.fullmatch(r"17\.[0-9][0-9A-Za-z.+_-]*", receipt["jdkRuntimeVersion"]):
        receipt_format_errors.append("jdkRuntimeVersion is not a Java 17 runtime")
    if not re.fullmatch(r"[0-9a-f]{64}", receipt["jdkTreeSha256"]):
        receipt_format_errors.append("jdkTreeSha256 must be one lowercase SHA-256")
    for field in (
        "gradleAttestationAutoSha256",
        "gradleAttestationQwySha256",
        "gradleAttestationHarnessSha256",
    ):
        if not re.fullmatch(r"[0-9a-f]{64}", receipt[field]):
            receipt_format_errors.append(f"{field} must be one lowercase SHA-256")
    if receipt_format_errors:
        validation_error = "host-gate receipt schema mismatch: " + "; ".join(
            receipt_format_errors
        )

if validation_error is None:
    mismatches = [
        f"{field}={receipt[field]!r} (expected {expected_value!r})"
        for field, expected_value in expected.items()
        if receipt[field] != expected_value
    ]
    if mismatches:
        validation_error = "host-gate receipt contract mismatch: " + "; ".join(mismatches)

if validation_error is None:
    gradle_attestations = {}
    try:
        for stage, (sha_field, _, _) in gradle_attestation_specs.items():
            gradle_attestations[stage] = read_gradle_attestation(
                stage,
                receipt[sha_field],
                receipt,
            )
        jdk_homes = {values["jdkHome"] for values in gradle_attestations.values()}
        java_vendors = {values["javaVendor"] for values in gradle_attestations.values()}
        java_vm_vendors = {
            values["javaVmVendor"] for values in gradle_attestations.values()
        }
        if len(jdk_homes) != 1 or len(java_vendors) != 1 or len(java_vm_vendors) != 1:
            raise GradleAttestationError(
                "the three proofs do not bind one JDK home and vendor identity"
            )
    except GradleAttestationError as error:
        validation_error = f"Gradle attestation invalid: {error}"

receipt_recheck_error = None
if receipt_fd is not None:
    try:
        if validation_error is None:
            os.lseek(receipt_fd, 0, os.SEEK_SET)
            confirmed_bytes = b""
            while True:
                chunk = os.read(receipt_fd, 4097 - len(confirmed_bytes))
                if not chunk:
                    break
                confirmed_bytes += chunk
                if len(confirmed_bytes) > 4096:
                    raise OSError("host-gate receipt changed beyond its size ceiling")
            receipt_fd_state_final = os.fstat(receipt_fd)
            receipt_path_state_final = os.stat(
                receipt_name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if (
                confirmed_bytes != receipt_bytes
                or file_identity(receipt_fd_state_final) != file_identity(receipt_fd_state)
                or file_identity(receipt_path_state_final) != file_identity(receipt_fd_state)
                or stat.S_ISLNK(receipt_path_state_final.st_mode)
                or has_extended_acl(receipt_path)
            ):
                raise OSError(
                    "host-gate receipt identity changed after contract validation "
                    "(including extended ACL)"
                )
    except OSError as error:
        receipt_recheck_error = str(error)
    finally:
        try:
            os.close(receipt_fd)
        except OSError as error:
            if receipt_recheck_error is None:
                receipt_recheck_error = f"could not close host-gate receipt: {error}"
        receipt_fd = None

if validation_error is None and receipt_recheck_error is not None:
    validation_error = f"invalid host-gate JSON receipt: {receipt_recheck_error}"

source_binding_after = None
if validation_error is None:
    try:
        source_binding_after = read_source_binding()
        if source_binding_after != source_binding_before:
            raise SourceBindingError("source HEAD/tree or canonical runner changed during validation")
    except SourceBindingError as error:
        validation_error = f"host-gate source provenance changed: {error}"

try:
    parent_fd_state_final = os.fstat(parent_fd)
    parent_path_state_final = confirm_directory_path(parent_path, parent_fd_state)
    if (
        not stat.S_ISDIR(parent_fd_state_final.st_mode)
        or stat.S_IMODE(parent_fd_state_final.st_mode) != 0o700
        or parent_fd_state_final.st_uid != os.geteuid()
        or (parent_fd_state_final.st_dev, parent_fd_state_final.st_ino)
        != (parent_fd_state.st_dev, parent_fd_state.st_ino)
        or (parent_path_state_final.st_dev, parent_path_state_final.st_ino)
        != (parent_fd_state.st_dev, parent_fd_state.st_ino)
        or stat.S_IMODE(parent_path_state_final.st_mode) != 0o700
        or parent_path_state_final.st_uid != os.geteuid()
        or has_extended_acl(parent_path)
    ):
        raise OSError(
            "host-gate receipt parent identity, owner, mode or extended ACL changed during validation"
        )
except OSError as error:
    if validation_error is None:
        validation_error = f"invalid host-gate JSON receipt: {error}"

cleanup_error = None
try:
    owner_fd_state = os.fstat(owner_fd)
    owner_path_state = os.stat("owner", dir_fd=lock_fd, follow_symlinks=False)
    lock_fd_state_final = os.fstat(lock_fd)
    lock_path_state_final = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(owner_fd_state.st_mode)
        or stat.S_IMODE(owner_fd_state.st_mode) != 0o600
        or owner_fd_state.st_uid != os.geteuid()
        or owner_fd_state.st_nlink != 1
        or (owner_fd_state.st_dev, owner_fd_state.st_ino)
        != (owner_path_state.st_dev, owner_path_state.st_ino)
        or not stat.S_ISDIR(lock_fd_state_final.st_mode)
        or stat.S_IMODE(lock_fd_state_final.st_mode) != 0o700
        or lock_fd_state_final.st_uid != os.geteuid()
        or (lock_fd_state_final.st_dev, lock_fd_state_final.st_ino)
        != (lock_fd_state.st_dev, lock_fd_state.st_ino)
        or (lock_path_state_final.st_dev, lock_path_state_final.st_ino)
        != (lock_fd_state.st_dev, lock_fd_state.st_ino)
        or has_extended_acl(owner_path)
        or has_extended_acl(lock_path)
    ):
        raise OSError("validation-lock owner identity or extended ACL changed")
    os.lseek(owner_fd, 0, os.SEEK_SET)
    observed_owner = b""
    while True:
        chunk = os.read(owner_fd, 4096)
        if not chunk:
            break
        observed_owner += chunk
        if len(observed_owner) > len(owner_token):
            break
    if observed_owner != owner_token:
        raise OSError("validation-lock owner token changed")
    os.unlink("owner", dir_fd=lock_fd)
    os.close(owner_fd)
    owner_fd = None
    os.close(lock_fd)
    lock_fd = None
    os.rmdir(lock_name, dir_fd=parent_fd)
except OSError as error:
    cleanup_error = str(error)
finally:
    if owner_fd is not None:
        os.close(owner_fd)
    if lock_fd is not None:
        os.close(lock_fd)
    os.close(parent_fd)

if validation_error is not None:
    print(f"verify-a-plus: {validation_error}", file=sys.stderr)
    raise SystemExit(1)
if cleanup_error is not None:
    print(
        f"verify-a-plus: could not release the owned host-gate validation lock: "
        f"{cleanup_error}; the receipt is not authoritative",
        file=sys.stderr,
    )
    raise SystemExit(1)

print(
    "     receipt: VALID — schemaVersion=4; hostIntegration=PASS; "
    f"sourceState=CLEAN; sourceHead={receipt['sourceHead'][:12]}; "
    f"runId={receipt['runId']}; "
    "issue66Ac7=NOT_PASSED; physicalDevice=NOT_RUN; "
    "deviceFull=BLOCKED; overall=BLOCKED"
)
PY
}

printf 'verify-a-plus: stage=%s\n' "$STAGE"

while IFS='|' read -r rank name pr file cmd; do
  [ -z "${rank:-}" ] && continue
  MANIFEST_SEEN=$((MANIFEST_SEEN + 1))

  if [ "$rank" -gt "$STAGE_RANK" ]; then
    PENDING=$((PENDING + 1))
    PENDING_NAMES="$PENDING_NAMES $name(owner=$pr)"
    continue
  fi
  ACTIVE_SEEN=$((ACTIVE_SEEN + 1))

  if [ ! -e "$file" ]; then
    # Required at this stage but absent: fail loudly. Never skip.
    printf '\n---- %-22s REQUIRED at stage %s but %s is missing\n' "$name" "$STAGE" "$file"
    FAILED=$((FAILED + 1))
    FAILED_NAMES="$FAILED_NAMES $name(missing)"
    continue
  fi

  printf '\n---- %s\n     $ %s\n' "$name" "$cmd"
  RUN=$((RUN + 1))
  if run_clean_gate_command "$cmd" </dev/null; then
    if [ "$name" = "auto-qwy-host" ]; then
      if verify_host_receipt "$HOST_RECEIPT" "$HOST_RECEIPT_LOCK" "$REPO_ROOT" "$HOST_GATE_RUNNER"; then
        HOST_RECEIPT_VALIDATED=1
        PASSED=$((PASSED + 1))
        printf '     -> PASS (repository host integration only; product/device remains BLOCKED)\n'
      else
        FAILED=$((FAILED + 1))
        FAILED_NAMES="$FAILED_NAMES $name(receipt)"
        printf '     -> FAIL (missing or invalid host evidence receipt)\n'
      fi
    else
      PASSED=$((PASSED + 1))
      printf '     -> PASS\n'
    fi
  else
    FAILED=$((FAILED + 1))
    FAILED_NAMES="$FAILED_NAMES $name"
    printf '     -> FAIL\n'
  fi
done <<EOF
$(printf '%s\n' "$GATES")
EOF

if [ "$MANIFEST_SEEN" -ne "$EXPECTED_GATE_COUNT" ] ||
  [ "$ACTIVE_SEEN" -ne "$expected_active" ] ||
  [ "$PENDING" -ne "$expected_pending" ]; then
  printf '%s\n' 'VERIFY_A_PLUS_GATE_MANIFEST_INCOMPLETE' >&2
  FAILED=$((FAILED + 1))
  FAILED_NAMES="$FAILED_NAMES manifest(incomplete)"
fi

printf '\n========================================\n'
printf 'verify-a-plus summary (stage=%s)\n' "$STAGE"
printf '  ran     : %d\n' "$RUN"
printf '  passed  : %d\n' "$PASSED"
printf '  failed  : %d%s\n' "$FAILED" "${FAILED_NAMES:+ ->$FAILED_NAMES}"
printf '  pending : %d%s\n' "$PENDING" "${PENDING_NAMES:+ ->$PENDING_NAMES}"

if [ "$PENDING" -gt 0 ]; then
  printf '\nNOTE: %d gate(s) belong to a later stage and were NOT evaluated.\n' "$PENDING"
  printf 'A pass at stage=%s does not mean the A+ acceptance criteria are met.\n' "$STAGE"
fi

if [ "$FAILED" -eq 0 ]; then
  if [ "$HOST_RECEIPT_VALIDATED" -eq 1 ]; then
    printf '\nRESULT: repository host gates required at stage=%s passed.\n' "$STAGE"
    printf 'PRODUCT/DEVICE RESULT: BLOCKED — issue66Ac7=NOT_PASSED; physicalDevice=NOT_RUN; deviceFull=BLOCKED; overall=BLOCKED.\n'
    printf 'REASON: HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION\n'
  else
    printf '\nRESULT: all gates required at stage=%s passed.\n' "$STAGE"
  fi
  exit 0
fi
printf '\nRESULT: %d gate(s) failed at stage=%s.\n' "$FAILED" "$STAGE"
exit 1

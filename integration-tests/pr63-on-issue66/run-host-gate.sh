#!/bin/bash -p
unset BASH_ENV ENV
unset DEVELOPER_DIR SDKROOT TOOLCHAINS
set -euo pipefail
umask 077
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
    printf '%s\n' 'HOST_GATE_UNSAFE_INHERITED_BASH_FUNCTION_ENV' >&2
    exit 1
    ;;
  *)
    printf '%s\n' 'HOST_GATE_INHERITED_ENVIRONMENT_INSPECTION_UNAVAILABLE' >&2
    exit 1
    ;;
esac
unset inherited_environment_status

script_dir="$(cd "$(dirname "$0")" && pwd -P)"
repo_root="$(cd "$script_dir/../.." && pwd -P)"
readonly auto_wrapper="$repo_root/apps/cellrebel-auto/gradlew"
readonly qwy_wrapper="$repo_root/apps/qianwangyou/gradlew"
readonly host_gradle_attestation_script="$script_dir/host-gate-test-attestation.init.gradle"
readonly java_profile_validator="$repo_root/scripts/validate-java17-runtime.py"
readonly java_runtime_stager="$repo_root/scripts/stage-java17-runtime.py"
readonly android_sdk_validator="$repo_root/scripts/validate-android-sdk-runtime.py"
readonly java_profile_validator_test="$repo_root/scripts/test_validate_java17_runtime.py"
readonly java_runtime_stager_test="$repo_root/scripts/test_stage_java17_runtime.py"
readonly android_sdk_validator_test="$repo_root/scripts/test_validate_android_sdk_runtime.py"
readonly requested_java_home="${JAVA_HOME:-}"
readonly requested_android_home="${ANDROID_HOME:-}"
host_java_home=""
host_java_binding=""
host_java_profile_id=""
host_java_vendor=""
host_java_vm_vendor=""
host_java_runtime_version=""
host_java_tree_sha256=""
host_java_darwin_temurin_profile_home=""
host_java_temurin_profile_home=""
host_android_home="$requested_android_home"
host_android_binding=""

emit_java_runtime_binding() {
  local candidate="$1"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  [[ -f "$java_profile_validator" && ! -L "$java_profile_validator" ]] || return 1
  /usr/bin/python3 -I "$java_profile_validator" --emit-binding "$candidate"
}

verify_java_runtime_binding() {
  [[ -n "$host_java_home" && -n "$host_java_binding" ]] || return 1
  /usr/bin/python3 -I "$java_profile_validator" \
    --verify-binding "$host_java_home" "$host_java_binding" >/dev/null
}

read_java_binding_field() {
  local binding="$1" field="$2"
  /usr/bin/python3 -I - "$binding" "$field" <<'PY'
import json
import sys

try:
    value = json.loads(sys.argv[1])
except json.JSONDecodeError:
    raise SystemExit(1)
field = sys.argv[2]
if field not in {
    "javaHome",
    "profileId",
    "javaVendor",
    "javaVmVendor",
    "javaRuntimeVersion",
    "jdkTreeSha256",
}:
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
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  [[ -f "$java_runtime_stager" && ! -L "$java_runtime_stager" ]] || return 1
  /usr/bin/python3 -I "$java_runtime_stager" "$candidate" "$stage_root"
}

validate_android_sdk_root() {
  local candidate="$1"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  [[ -f "$android_sdk_validator" && ! -L "$android_sdk_validator" ]] || return 1
  /usr/bin/python3 -I "$android_sdk_validator" --emit-binding "$candidate"
}

verify_android_sdk_binding() {
  [[ -n "$host_android_home" && -n "$host_android_binding" ]] || return 1
  /usr/bin/python3 -I "$android_sdk_validator" \
    --verify-binding "$host_android_home" "$host_android_binding" >/dev/null
}

run_standalone_runtime_security_tests() {
  local security_test=""
  for security_test in \
    "$java_profile_validator_test" \
    "$java_runtime_stager_test" \
    "$android_sdk_validator_test"; do
    [[ -f "$security_test" && ! -L "$security_test" ]] || return 1
  done
  if ! verify_java_runtime_binding || ! verify_android_sdk_binding; then
    return 1
  fi
  run_clean_host_command /usr/bin/python3 -I "$java_profile_validator_test" || return 1
  run_clean_host_command /usr/bin/python3 -I "$java_runtime_stager_test" || return 1
  run_clean_host_command /usr/bin/python3 -I "$android_sdk_validator_test" || return 1
  if ! verify_java_runtime_binding || ! verify_android_sdk_binding; then
    return 1
  fi
}

prepare_private_directory() {
  local anchor_path="$1" relative_path="$2"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - "$anchor_path" "$relative_path" <<'PY'
import errno
import os
import stat
import subprocess
import sys

anchor_path = os.path.abspath(sys.argv[1])
components = sys.argv[2].split("/")
if not components or any(
    component in {"", ".", ".."} or os.path.basename(component) != component
    for component in components
):
    raise SystemExit("unsafe private directory path")
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required private directory flags are unavailable")
directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
directory_fds = []
directory_chain = []


def dev_inode(value):
    return (value.st_dev, value.st_ino)


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


def sync_directory(directory_fd):
    try:
        os.fsync(directory_fd)
    except OSError as error:
        unsupported = {errno.EINVAL}
        unsupported.update(
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        )
        if error.errno not in unsupported:
            raise


current_path = anchor_path
anchor_fd = os.open(anchor_path, directory_flags)
directory_fds.append(anchor_fd)
try:
    anchor_state = os.fstat(anchor_fd)
    named_anchor_state = os.lstat(anchor_path)
    if (
        not stat.S_ISDIR(anchor_state.st_mode)
        or anchor_state.st_uid != os.geteuid()
        or named_anchor_state.st_uid != os.geteuid()
        or stat.S_IMODE(anchor_state.st_mode) & 0o022
        or stat.S_IMODE(named_anchor_state.st_mode) & 0o022
        or dev_inode(anchor_state) != dev_inode(named_anchor_state)
        or has_extended_acl(current_path)
    ):
        raise OSError("private directory anchor identity, mode, or extended ACL is unsafe")

    parent_fd = anchor_fd
    for index, component in enumerate(components):
        current_path = os.path.join(current_path, component)
        try:
            os.mkdir(component, 0o700, dir_fd=parent_fd)
        except FileExistsError:
            pass
        directory_fd = os.open(component, directory_flags, dir_fd=parent_fd)
        directory_fds.append(directory_fd)
        directory_state = os.fstat(directory_fd)
        named_directory_state = os.stat(
            component,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        is_final = index == len(components) - 1
        if (
            not stat.S_ISDIR(directory_state.st_mode)
            or directory_state.st_uid != os.geteuid()
            or named_directory_state.st_uid != os.geteuid()
            or dev_inode(directory_state) != dev_inode(named_directory_state)
            or has_extended_acl(current_path)
            or (
                not is_final
                and (
                    stat.S_IMODE(directory_state.st_mode) & 0o022
                    or stat.S_IMODE(named_directory_state.st_mode) & 0o022
                )
            )
        ):
            raise OSError(
                "private directory component identity, owner, mode, or extended ACL is unsafe"
            )
        if is_final and stat.S_IMODE(directory_state.st_mode) != 0o700:
            os.fchmod(directory_fd, 0o700)
            directory_state = os.fstat(directory_fd)
            named_directory_state = os.stat(
                component,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
        if is_final and (
            stat.S_IMODE(directory_state.st_mode) != 0o700
            or stat.S_IMODE(named_directory_state.st_mode) != 0o700
            or dev_inode(directory_state) != dev_inode(named_directory_state)
            or has_extended_acl(current_path)
        ):
            raise OSError(
                "private directory changed while tightening permissions or has an extended ACL"
            )
        directory_chain.append(
            (parent_fd, component, dev_inode(directory_state), current_path)
        )
        parent_fd = directory_fd

    for parent_fd, component, expected_identity, component_path in directory_chain:
        named_directory_state = os.stat(
            component,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISDIR(named_directory_state.st_mode)
            or named_directory_state.st_uid != os.geteuid()
            or dev_inode(named_directory_state) != expected_identity
            or has_extended_acl(component_path)
        ):
            raise OSError(
                "private directory chain or extended ACL changed during creation"
            )
    for directory_fd in reversed(directory_fds):
        sync_directory(directory_fd)
finally:
    for directory_fd in reversed(directory_fds):
        os.close(directory_fd)
PY
}

create_host_gate_lock() {
  local anchor_path="$1" relative_path="$2" lock_path="$3"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - "$anchor_path" "$relative_path" "$lock_path" <<'PY'
import errno
import os
import stat
import subprocess
import sys

anchor_path = os.path.abspath(sys.argv[1])
components = sys.argv[2].split("/")
lock_path = os.path.abspath(sys.argv[3])
parent_path = os.path.dirname(lock_path)
lock_name = os.path.basename(lock_path)
if (
    not components
    or any(
        component in {"", ".", ".."} or os.path.basename(component) != component
        for component in components
    )
    or parent_path != os.path.join(anchor_path, *components)
    or lock_name in {"", ".", ".."}
):
    raise SystemExit("unsafe host-gate lock path")
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required host-gate lock flags are unavailable")
directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)


def dev_inode(value):
    return (value.st_dev, value.st_ino)


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


def sync_directory(directory_fd):
    try:
        os.fsync(directory_fd)
    except OSError as error:
        unsupported = {errno.EINVAL}
        unsupported.update(
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        )
        if error.errno not in unsupported:
            raise


def open_receipt_parent():
    directory_fds = []
    try:
        current_path = anchor_path
        current_fd = os.open(anchor_path, directory_flags)
        directory_fds.append(current_fd)
        anchor_state = os.fstat(current_fd)
        named_anchor_state = os.lstat(anchor_path)
        if (
            not stat.S_ISDIR(anchor_state.st_mode)
            or anchor_state.st_uid != os.geteuid()
            or named_anchor_state.st_uid != os.geteuid()
            or stat.S_IMODE(anchor_state.st_mode) & 0o022
            or stat.S_IMODE(named_anchor_state.st_mode) & 0o022
            or dev_inode(anchor_state) != dev_inode(named_anchor_state)
            or has_extended_acl(current_path)
        ):
            raise OSError(
                "host-gate lock anchor identity, mode, or extended ACL is unsafe"
            )
        for index, component in enumerate(components):
            current_path = os.path.join(current_path, component)
            next_fd = os.open(component, directory_flags, dir_fd=current_fd)
            directory_fds.append(next_fd)
            directory_state = os.fstat(next_fd)
            named_directory_state = os.stat(
                component,
                dir_fd=current_fd,
                follow_symlinks=False,
            )
            is_final = index == len(components) - 1
            if (
                not stat.S_ISDIR(directory_state.st_mode)
                or directory_state.st_uid != os.geteuid()
                or named_directory_state.st_uid != os.geteuid()
                or dev_inode(directory_state) != dev_inode(named_directory_state)
                or has_extended_acl(current_path)
                or (
                    is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) != 0o700
                        or stat.S_IMODE(named_directory_state.st_mode) != 0o700
                    )
                )
                or (
                    not is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) & 0o022
                        or stat.S_IMODE(named_directory_state.st_mode) & 0o022
                    )
                )
            ):
                raise OSError(
                    "host-gate lock directory chain or extended ACL is unsafe"
                )
            current_fd = next_fd
        result_fd = directory_fds.pop()
        return result_fd
    finally:
        for directory_fd in reversed(directory_fds):
            os.close(directory_fd)


parent_fd = open_receipt_parent()
lock_fd = None
try:
    parent_state = os.fstat(parent_fd)
    try:
        os.mkdir(lock_name, 0o700, dir_fd=parent_fd)
    except FileExistsError:
        raise SystemExit(75)
    lock_fd = os.open(lock_name, directory_flags, dir_fd=parent_fd)
    os.fchmod(lock_fd, 0o700)
    lock_state = os.fstat(lock_fd)
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    verified_parent_fd = open_receipt_parent()
    verified_parent_state = os.fstat(verified_parent_fd)
    os.close(verified_parent_fd)
    if (
        not stat.S_ISDIR(lock_state.st_mode)
        or lock_state.st_uid != os.geteuid()
        or named_lock_state.st_uid != os.geteuid()
        or stat.S_IMODE(lock_state.st_mode) != 0o700
        or stat.S_IMODE(named_lock_state.st_mode) != 0o700
        or dev_inode(lock_state) != dev_inode(named_lock_state)
        or dev_inode(verified_parent_state) != dev_inode(parent_state)
        or has_extended_acl(lock_path)
    ):
        raise OSError(
            "host-gate lock identity, mode, or extended ACL changed during creation"
        )
    sync_directory(lock_fd)
    sync_directory(parent_fd)
    print(
        f"{parent_state.st_dev}:{parent_state.st_ino}:"
        f"{lock_state.st_dev}:{lock_state.st_ino}"
    )
finally:
    if lock_fd is not None:
        os.close(lock_fd)
    os.close(parent_fd)
PY
}

write_private_file_exclusively() {
  local anchor_path="$1" relative_path="$2" output_path="$3" payload="$4"
  local expected_lock_identity="$5"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - \
    "$anchor_path" "$relative_path" "$output_path" "$payload" \
    "$expected_lock_identity" <<'PY'
import errno
import os
import stat
import subprocess
import sys

anchor_path = os.path.abspath(sys.argv[1])
components = sys.argv[2].split("/")
output_path = os.path.abspath(sys.argv[3])
payload = (sys.argv[4] + "\n").encode("utf-8")
try:
    expected_lock_identity = tuple(int(part) for part in sys.argv[5].split(":"))
except ValueError as error:
    raise SystemExit(f"invalid private lock identity: {error}")
if len(expected_lock_identity) != 4:
    raise SystemExit("invalid private lock identity")
lock_path = os.path.dirname(output_path)
parent_path = os.path.dirname(lock_path)
lock_name = os.path.basename(lock_path)
output_name = os.path.basename(output_path)
if (
    not components
    or any(
        component in {"", ".", ".."} or os.path.basename(component) != component
        for component in components
    )
    or lock_name in {"", ".", ".."}
    or output_name in {"", ".", ".."}
    or parent_path != os.path.join(anchor_path, *components)
):
    raise SystemExit("unsafe private output name")
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required private output flags are unavailable")
parent_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
parent_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)


def dev_inode(value):
    return (value.st_dev, value.st_ino)


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


def open_receipt_parent():
    directory_fds = []
    try:
        current_path = anchor_path
        current_fd = os.open(anchor_path, parent_flags)
        directory_fds.append(current_fd)
        anchor_state = os.fstat(current_fd)
        named_anchor_state = os.lstat(anchor_path)
        if (
            not stat.S_ISDIR(anchor_state.st_mode)
            or anchor_state.st_uid != os.geteuid()
            or named_anchor_state.st_uid != os.geteuid()
            or stat.S_IMODE(anchor_state.st_mode) & 0o022
            or stat.S_IMODE(named_anchor_state.st_mode) & 0o022
            or dev_inode(anchor_state) != dev_inode(named_anchor_state)
            or has_extended_acl(current_path)
        ):
            raise OSError(
                "private output anchor identity, mode, or extended ACL is unsafe"
            )
        for index, component in enumerate(components):
            current_path = os.path.join(current_path, component)
            next_fd = os.open(component, parent_flags, dir_fd=current_fd)
            directory_fds.append(next_fd)
            directory_state = os.fstat(next_fd)
            named_directory_state = os.stat(
                component,
                dir_fd=current_fd,
                follow_symlinks=False,
            )
            is_final = index == len(components) - 1
            if (
                not stat.S_ISDIR(directory_state.st_mode)
                or directory_state.st_uid != os.geteuid()
                or named_directory_state.st_uid != os.geteuid()
                or dev_inode(directory_state) != dev_inode(named_directory_state)
                or has_extended_acl(current_path)
                or (
                    is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) != 0o700
                        or stat.S_IMODE(named_directory_state.st_mode) != 0o700
                    )
                )
                or (
                    not is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) & 0o022
                        or stat.S_IMODE(named_directory_state.st_mode) & 0o022
                    )
                )
            ):
                raise OSError(
                    "private output directory chain or extended ACL is unsafe"
                )
            current_fd = next_fd
        result_fd = directory_fds.pop()
        return result_fd
    finally:
        for directory_fd in reversed(directory_fds):
            os.close(directory_fd)


parent_fd = open_receipt_parent()
lock_fd = None
output_fd = None
try:
    parent_state = os.fstat(parent_fd)
    if dev_inode(parent_state) != expected_lock_identity[:2]:
        raise OSError("private output parent identity changed")
    lock_fd = os.open(lock_name, parent_flags, dir_fd=parent_fd)
    lock_state = os.fstat(lock_fd)
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(lock_state.st_mode)
        or lock_state.st_uid != os.geteuid()
        or named_lock_state.st_uid != os.geteuid()
        or stat.S_IMODE(lock_state.st_mode) != 0o700
        or stat.S_IMODE(named_lock_state.st_mode) != 0o700
        or (lock_state.st_dev, lock_state.st_ino)
        != (named_lock_state.st_dev, named_lock_state.st_ino)
        or dev_inode(lock_state) != expected_lock_identity[2:]
        or has_extended_acl(lock_path)
    ):
        raise OSError("private output lock identity or extended ACL changed")
    output_flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    output_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
    output_fd = os.open(output_name, output_flags, 0o600, dir_fd=lock_fd)
    os.fchmod(output_fd, 0o600)
    remaining = memoryview(payload)
    while remaining:
        written = os.write(output_fd, remaining)
        if written <= 0:
            raise OSError("short private output write")
        remaining = remaining[written:]
    os.fsync(output_fd)
    output_state = os.fstat(output_fd)
    named_output_state = os.stat(output_name, dir_fd=lock_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(output_state.st_mode)
        or output_state.st_uid != os.geteuid()
        or named_output_state.st_uid != os.geteuid()
        or stat.S_IMODE(output_state.st_mode) != 0o600
        or stat.S_IMODE(named_output_state.st_mode) != 0o600
        or output_state.st_nlink != 1
        or (output_state.st_dev, output_state.st_ino)
        != (named_output_state.st_dev, named_output_state.st_ino)
        or has_extended_acl(output_path)
    ):
        raise OSError("private output identity or extended ACL changed")
    current_lock_state = os.fstat(lock_fd)
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    os.lseek(output_fd, 0, os.SEEK_SET)
    current_payload = b""
    while len(current_payload) <= len(payload):
        chunk = os.read(output_fd, len(payload) + 1 - len(current_payload))
        if not chunk:
            break
        current_payload += chunk
    current_output_state = os.fstat(output_fd)
    named_output_state = os.stat(output_name, dir_fd=lock_fd, follow_symlinks=False)
    verified_parent_fd = open_receipt_parent()
    verified_parent_state = os.fstat(verified_parent_fd)
    os.close(verified_parent_fd)
    if (
        dev_inode(verified_parent_state) != dev_inode(parent_state)
        or (current_lock_state.st_dev, current_lock_state.st_ino)
        != (lock_state.st_dev, lock_state.st_ino)
        or (named_lock_state.st_dev, named_lock_state.st_ino)
        != (lock_state.st_dev, lock_state.st_ino)
        or current_lock_state.st_uid != os.geteuid()
        or named_lock_state.st_uid != os.geteuid()
        or stat.S_IMODE(current_lock_state.st_mode) != 0o700
        or stat.S_IMODE(named_lock_state.st_mode) != 0o700
        or has_extended_acl(lock_path)
        or (current_output_state.st_dev, current_output_state.st_ino)
        != (output_state.st_dev, output_state.st_ino)
        or (named_output_state.st_dev, named_output_state.st_ino)
        != (output_state.st_dev, output_state.st_ino)
        or current_output_state.st_uid != os.geteuid()
        or named_output_state.st_uid != os.geteuid()
        or stat.S_IMODE(current_output_state.st_mode) != 0o600
        or stat.S_IMODE(named_output_state.st_mode) != 0o600
        or current_output_state.st_nlink != 1
        or has_extended_acl(output_path)
        or current_payload != payload
    ):
        raise OSError(
            "private output parent identity or extended ACL changed after write"
        )
    try:
        os.fsync(lock_fd)
    except OSError as error:
        unsupported = {errno.EINVAL}
        unsupported.update(
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        )
        if error.errno not in unsupported:
            raise
    try:
        os.fsync(parent_fd)
    except OSError as error:
        unsupported = {errno.EINVAL}
        unsupported.update(
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        )
        if error.errno not in unsupported:
            raise
    print(
        f"{parent_state.st_dev}:{parent_state.st_ino}:"
        f"{lock_state.st_dev}:{lock_state.st_ino}:"
        f"{output_state.st_dev}:{output_state.st_ino}"
    )
finally:
    if output_fd is not None:
        os.close(output_fd)
    if lock_fd is not None:
        os.close(lock_fd)
    os.close(parent_fd)
PY
}

read_source_provenance() {
  [[ -x /usr/bin/env && -x /usr/bin/git && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - "$repo_root" <<'PY'
import errno
import hashlib
import os
import re
import stat
import subprocess
import sys

repo_root = os.path.realpath(os.path.abspath(sys.argv[1]))
if os.path.abspath(sys.argv[1]) != repo_root:
    raise SystemExit("repository root must be a physical absolute path")
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required source-provenance flags are unavailable")

git_environment = {
    "LC_ALL": "C",
    "LANG": "C",
    "PATH": "/usr/bin:/bin",
    "GIT_ATTR_NOSYSTEM": "1",
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
    "-c", "core.trustctime=true",
    "-c", "core.checkStat=default",
    "-c", "core.fileMode=true",
    "-c", "core.excludesFile=/dev/null",
    "-c", "core.attributesFile=/dev/null",
    "-c", "core.ignoreCase=false",
    "-C", repo_root,
]


def git_output(*arguments):
    result = subprocess.run(
        git_prefix + list(arguments),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env=git_environment,
        check=False,
    )
    if result.returncode != 0:
        raise OSError("isolated Git source query failed")
    return result.stdout


def one_line(value, label):
    if not value.endswith(b"\n") or b"\n" in value[:-1]:
        raise OSError(f"invalid {label}")
    return value[:-1]


def nul_records(value, label):
    if not value:
        return []
    if not value.endswith(b"\0"):
        raise OSError(f"unterminated {label}")
    return value[:-1].split(b"\0")


def safe_path(value):
    components = value.split(b"/")
    return (
        bool(value)
        and not value.startswith(b"/")
        and all(component not in {b"", b".", b".."} for component in components)
        and components[0] != b".git"
    )


def parse_head_tree(value):
    entries = {}
    for record in nul_records(value, "HEAD tree"):
        try:
            metadata, path = record.split(b"\t", 1)
            mode, object_type, object_id = metadata.split(b" ")
        except ValueError as error:
            raise OSError("invalid HEAD tree record") from error
        if (
            not safe_path(path)
            or path in entries
            or object_type != b"blob"
            or mode not in {b"100644", b"100755", b"120000"}
            or re.fullmatch(b"[0-9a-f]{40}", object_id) is None
        ):
            raise OSError("unsupported or duplicate HEAD tree entry")
        entries[path] = (mode, object_id)
    if not entries:
        raise OSError("HEAD tree is empty")
    return entries


def parse_index(value):
    entries = {}
    for record in nul_records(value, "index"):
        try:
            metadata, path = record.split(b"\t", 1)
            tag, mode, object_id, stage = metadata.split(b" ")
        except ValueError as error:
            raise OSError("invalid index record") from error
        if (
            tag != b"H"
            or stage != b"0"
            or not safe_path(path)
            or path in entries
            or mode not in {b"100644", b"100755", b"120000"}
            or re.fullmatch(b"[0-9a-f]{40}", object_id) is None
        ):
            raise OSError("index flags, stage, mode, or path are not plain")
        entries[path] = (mode, object_id)
    return entries


def repository_snapshot():
    source_top = os.path.realpath(os.fsdecode(one_line(
        git_output("rev-parse", "--show-toplevel"),
        "repository root",
    )))
    source_head = one_line(
        git_output("rev-parse", "--verify", "HEAD^{commit}"),
        "HEAD commit",
    )
    source_tree = one_line(
        git_output("rev-parse", "--verify", "HEAD^{tree}"),
        "HEAD tree identity",
    )
    if (
        source_top != repo_root
        or re.fullmatch(b"[0-9a-f]{40}", source_head) is None
        or re.fullmatch(b"[0-9a-f]{40}", source_tree) is None
    ):
        raise OSError("repository root, HEAD, or tree identity is invalid")
    head_tree = git_output("ls-tree", "-rz", "--full-tree", source_head)
    index = git_output("ls-files", "--stage", "-v", "-z")
    head_entries = parse_head_tree(head_tree)
    if parse_index(index) != head_entries:
        raise OSError("index content or mode does not equal HEAD")
    return source_head, source_tree, head_tree, index, head_entries


directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
file_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
file_flags |= getattr(os, "O_CLOEXEC", 0)
root_fd = os.open(repo_root, directory_flags)

if sys.platform == "darwin":
    import ctypes

    darwin_libc = ctypes.CDLL(None, use_errno=True)
    darwin_acl_get_fd = darwin_libc.acl_get_fd_np
    darwin_acl_get_fd.argtypes = [ctypes.c_int, ctypes.c_int]
    darwin_acl_get_fd.restype = ctypes.c_void_p
    darwin_acl_free = darwin_libc.acl_free
    darwin_acl_free.argtypes = [ctypes.c_void_p]
    darwin_acl_free.restype = ctypes.c_int


def identity(value):
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


def fd_has_extended_acl(descriptor_fd):
    """Inspect the pinned inode, not a pathname that could be substituted."""
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


def directory_state_is_safe(value):
    return (
        stat.S_ISDIR(value.st_mode)
        and value.st_uid == os.geteuid()
        and not stat.S_IMODE(value.st_mode) & 0o022
    )


def validate_open_directory(directory_fd, label):
    before = os.fstat(directory_fd)
    if not directory_state_is_safe(before):
        raise OSError(f"{label} identity, owner, or mode is unsafe")
    if fd_has_extended_acl(directory_fd):
        raise OSError(f"{label} has an extended ACL")
    after = os.fstat(directory_fd)
    if identity(after) != identity(before) or not directory_state_is_safe(after):
        raise OSError(f"{label} changed during ACL validation")
    if fd_has_extended_acl(directory_fd):
        raise OSError(f"{label} acquired an extended ACL")
    confirmed = os.fstat(directory_fd)
    if identity(confirmed) != identity(after) or not directory_state_is_safe(confirmed):
        raise OSError(f"{label} changed after ACL validation")
    return confirmed


def validate_root():
    descriptor_state = validate_open_directory(root_fd, "repository root")
    named_state = os.lstat(repo_root)
    if (
        not stat.S_ISDIR(named_state.st_mode)
        or named_state.st_uid != os.geteuid()
        or stat.S_IMODE(named_state.st_mode) & 0o022
        or identity(descriptor_state) != identity(named_state)
    ):
        raise OSError("repository root identity, owner, or mode is unsafe")
    confirmed_descriptor_state = validate_open_directory(root_fd, "repository root")
    confirmed_named_state = os.lstat(repo_root)
    if (
        identity(confirmed_descriptor_state) != identity(descriptor_state)
        or identity(confirmed_named_state) != identity(named_state)
        or identity(confirmed_descriptor_state) != identity(confirmed_named_state)
    ):
        raise OSError("repository root changed during validation")


def open_parent(path):
    components = path.split(b"/")
    parent_fd = os.dup(root_fd)
    try:
        for component in components[:-1]:
            next_fd = os.open(component, directory_flags, dir_fd=parent_fd)
            descriptor_state = validate_open_directory(next_fd, "tracked parent directory")
            named_state = os.stat(component, dir_fd=parent_fd, follow_symlinks=False)
            if (
                not stat.S_ISDIR(named_state.st_mode)
                or named_state.st_uid != os.geteuid()
                or stat.S_IMODE(named_state.st_mode) & 0o022
                or identity(descriptor_state) != identity(named_state)
            ):
                os.close(next_fd)
                raise OSError("tracked path traverses an unsafe directory")
            confirmed_descriptor_state = validate_open_directory(
                next_fd,
                "tracked parent directory",
            )
            confirmed_named_state = os.stat(
                component,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if (
                identity(confirmed_descriptor_state) != identity(descriptor_state)
                or identity(confirmed_named_state) != identity(named_state)
                or identity(confirmed_descriptor_state) != identity(confirmed_named_state)
            ):
                os.close(next_fd)
                raise OSError("tracked parent directory changed during validation")
            os.close(parent_fd)
            parent_fd = next_fd
        validate_open_directory(parent_fd, "tracked leaf parent directory")
        return parent_fd, components[-1]
    except BaseException:
        os.close(parent_fd)
        raise


def git_blob_id(payload):
    header = b"blob " + str(len(payload)).encode("ascii") + b"\0"
    try:
        digest = hashlib.sha1(usedforsecurity=False)
    except TypeError:
        digest = hashlib.sha1()
    digest.update(header)
    digest.update(payload)
    return digest.hexdigest().encode("ascii")


def validate_leaf(path, expected_mode, expected_object_id):
    parent_fd, leaf_name = open_parent(path)
    file_fd = None
    try:
        before = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
        if expected_mode == b"120000":
            if not stat.S_ISLNK(before.st_mode) or before.st_uid != os.geteuid():
                raise OSError("tracked symlink type or owner changed")
            payload = os.readlink(leaf_name, dir_fd=parent_fd)
            if isinstance(payload, str):
                payload = os.fsencode(payload)
            after = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
            if identity(after) != identity(before):
                raise OSError("tracked symlink changed during raw read")
        else:
            file_fd = os.open(leaf_name, file_flags, dir_fd=parent_fd)
            descriptor_before = os.fstat(file_fd)
            if (
                not stat.S_ISREG(descriptor_before.st_mode)
                or descriptor_before.st_uid != os.geteuid()
                or before.st_uid != os.geteuid()
                or stat.S_IMODE(descriptor_before.st_mode) & 0o022
                or stat.S_IMODE(before.st_mode) & 0o022
                or identity(descriptor_before) != identity(before)
            ):
                raise OSError("tracked regular-file type, owner, mode, or identity changed")
            if fd_has_extended_acl(file_fd):
                raise OSError("tracked regular file has an extended ACL")
            payload = bytearray()
            while True:
                chunk = os.read(file_fd, 65536)
                if not chunk:
                    break
                payload.extend(chunk)
                if len(payload) > 67108864:
                    raise OSError("tracked file exceeds the 64 MiB provenance ceiling")
            descriptor_after = os.fstat(file_fd)
            after = os.stat(leaf_name, dir_fd=parent_fd, follow_symlinks=False)
            if (
                identity(descriptor_after) != identity(descriptor_before)
                or identity(after) != identity(descriptor_before)
            ):
                raise OSError("tracked regular file changed during raw read")
            if fd_has_extended_acl(file_fd):
                raise OSError("tracked regular file acquired an extended ACL")
            descriptor_confirmed = os.fstat(file_fd)
            named_confirmed = os.stat(
                leaf_name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if (
                identity(descriptor_confirmed) != identity(descriptor_after)
                or identity(named_confirmed) != identity(after)
                or identity(descriptor_confirmed) != identity(named_confirmed)
            ):
                raise OSError("tracked regular file changed after ACL validation")
            executable = bool(stat.S_IMODE(descriptor_before.st_mode) & 0o111)
            actual_mode = b"100755" if executable else b"100644"
            if actual_mode != expected_mode:
                raise OSError("tracked executable mode differs from HEAD")
            payload = bytes(payload)
        if git_blob_id(payload) != expected_object_id:
            raise OSError("tracked raw bytes differ from HEAD")
        # A symlink's replacement authority lives on its pinned parent; symlink
        # inode ACLs do not govern readlink or replacement on Darwin/Linux.
        validate_open_directory(parent_fd, "tracked leaf parent directory")
    finally:
        if file_fd is not None:
            os.close(file_fd)
        os.close(parent_fd)


def validate_worktree(entries):
    validate_root()
    for path, (expected_mode, expected_object_id) in entries.items():
        validate_leaf(path, expected_mode, expected_object_id)
    validate_root()


def validate_untracked():
    untracked_ignore_files = git_output(
        "ls-files", "--others", "-z", "--", ".gitignore", ":(glob)**/.gitignore",
    )
    if nul_records(untracked_ignore_files, "untracked .gitignore list"):
        raise OSError("an untracked .gitignore could alter ignore semantics")
    untracked = git_output(
        "ls-files", "--others", "-z", "--exclude-per-directory=.gitignore",
    )
    if nul_records(untracked, "untracked path list"):
        raise OSError("repository contains a non-committed, non-ignored path")


try:
    validate_root()
    initial = repository_snapshot()
    validate_worktree(initial[4])
    validate_untracked()
    validate_root()
    confirmed = repository_snapshot()
    if initial[:4] != confirmed[:4]:
        raise OSError("HEAD or index changed during source provenance")
    validate_worktree(confirmed[4])
    validate_untracked()
    validate_root()
    print(f"{initial[0].decode('ascii')}:{initial[1].decode('ascii')}")
finally:
    os.close(root_fd)
PY
}

new_run_id() {
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - <<'PY'
import os

print(os.urandom(16).hex())
PY
}

read_runner_sha256() {
  local runner_path="$1"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - "$runner_path" <<'PY'
import hashlib
import os
import stat
import sys

runner_path = os.path.abspath(sys.argv[1])
parent_path = os.path.dirname(runner_path)
runner_name = os.path.basename(runner_path)
if runner_name in {"", ".", ".."}:
    raise SystemExit("unsafe runner name")
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required runner-read flags are unavailable")
parent_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
parent_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
parent_fd = os.open(parent_path, parent_flags)
runner_fd = None
try:
    parent_state = os.fstat(parent_fd)
    named_parent_state = os.lstat(parent_path)
    if (
        not stat.S_ISDIR(parent_state.st_mode)
        or parent_state.st_uid != os.geteuid()
        or named_parent_state.st_uid != os.geteuid()
        or stat.S_IMODE(parent_state.st_mode) & 0o022
        or stat.S_IMODE(named_parent_state.st_mode) & 0o022
        or (parent_state.st_dev, parent_state.st_ino)
        != (named_parent_state.st_dev, named_parent_state.st_ino)
    ):
        raise OSError("runner parent identity changed")
    runner_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    runner_flags |= getattr(os, "O_CLOEXEC", 0)
    runner_fd = os.open(runner_name, runner_flags, dir_fd=parent_fd)
    runner_state = os.fstat(runner_fd)
    named_runner_state = os.stat(runner_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(runner_state.st_mode)
        or runner_state.st_uid != os.geteuid()
        or named_runner_state.st_uid != os.geteuid()
        or stat.S_IMODE(runner_state.st_mode) & 0o022
        or stat.S_IMODE(named_runner_state.st_mode) & 0o022
        or not stat.S_IMODE(runner_state.st_mode) & stat.S_IXUSR
        or not stat.S_IMODE(named_runner_state.st_mode) & stat.S_IXUSR
        or runner_state.st_nlink != 1
        or (runner_state.st_dev, runner_state.st_ino)
        != (named_runner_state.st_dev, named_runner_state.st_ino)
    ):
        raise OSError("runner identity changed or is not a single regular file")
    runner_bytes = b""
    while True:
        chunk = os.read(runner_fd, 65536)
        if not chunk:
            break
        runner_bytes += chunk
        if len(runner_bytes) > 1048576:
            raise OSError("runner exceeds the 1 MiB provenance ceiling")
    runner_state_after = os.fstat(runner_fd)
    named_runner_state_after = os.stat(
        runner_name,
        dir_fd=parent_fd,
        follow_symlinks=False,
    )
    named_parent_state_after = os.lstat(parent_path)
    identity = lambda value: (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_nlink,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )
    if (
        identity(runner_state_after) != identity(runner_state)
        or identity(named_runner_state_after) != identity(runner_state)
        or runner_state_after.st_uid != os.geteuid()
        or named_runner_state_after.st_uid != os.geteuid()
        or stat.S_IMODE(runner_state_after.st_mode) & 0o022
        or stat.S_IMODE(named_runner_state_after.st_mode) & 0o022
        or named_parent_state_after.st_uid != os.geteuid()
        or stat.S_IMODE(named_parent_state_after.st_mode) & 0o022
        or (named_parent_state_after.st_dev, named_parent_state_after.st_ino)
        != (parent_state.st_dev, parent_state.st_ino)
    ):
        raise OSError("runner identity changed during provenance read")
    print(hashlib.sha256(runner_bytes).hexdigest())
finally:
    if runner_fd is not None:
        os.close(runner_fd)
    os.close(parent_fd)
PY
}

read_head_runner_sha256() {
  local source_head="$1" runner_path="$2"
  local runner_relative_path="integration-tests/pr63-on-issue66/run-host-gate.sh"
  [[ -x /usr/bin/env && -x /usr/bin/git && -x /usr/bin/python3 ]] || return 1
  [[ "$source_head" =~ ^[0-9a-f]{40}$ ]] || return 1
  [[ "$runner_path" == "$repo_root/$runner_relative_path" ]] || return 1
  /usr/bin/env -i \
    LC_ALL=C \
    LANG=C \
    PATH=/usr/bin:/bin \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_SYSTEM=/dev/null \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_COUNT=0 \
    GIT_OPTIONAL_LOCKS=0 \
    /usr/bin/git --no-replace-objects \
      -c core.hooksPath=/dev/null \
      -c core.fsmonitor=false \
      -c core.untrackedCache=false \
      -C "$repo_root" cat-file blob "$source_head:$runner_relative_path" |
    /usr/bin/python3 -I -c '
import hashlib
import sys

runner_bytes = sys.stdin.buffer.read(1048577)
if not runner_bytes or len(runner_bytes) > 1048576:
    raise SystemExit(1)
print(hashlib.sha256(runner_bytes).hexdigest())
'
}

write_receipt_atomically() {
  local payload="$1"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - \
    "$script_dir" "$receipt_relative_dir" "$receipt_path" "$lock_dir" \
    "$run_owner" "$lock_identity" "$payload" <<'PY'
import errno
import os
import stat
import subprocess
import sys

anchor_path = os.path.abspath(sys.argv[1])
components = sys.argv[2].split("/")
receipt_path = os.path.abspath(sys.argv[3])
lock_path = os.path.abspath(sys.argv[4])
expected_owner = (sys.argv[5] + "\n").encode("utf-8")
try:
    expected_identity = tuple(int(part) for part in sys.argv[6].split(":"))
except ValueError as error:
    raise SystemExit(f"invalid host-gate lock identity: {error}")
payload = (sys.argv[7] + "\n").encode("utf-8")
if len(expected_identity) != 6:
    raise SystemExit("invalid host-gate lock identity")
if len(payload) > 4096:
    raise SystemExit("host-gate receipt exceeds the 4096-byte contract ceiling")

parent_path = os.path.dirname(receipt_path)
lock_parent_path = os.path.dirname(lock_path)
receipt_name = os.path.basename(receipt_path)
lock_name = os.path.basename(lock_path)
owner_path = os.path.join(lock_path, "owner")
if (
    not components
    or any(
        component in {"", ".", ".."} or os.path.basename(component) != component
        for component in components
    )
    or parent_path != lock_parent_path
    or parent_path != os.path.join(anchor_path, *components)
    or receipt_name in {"", ".", ".."}
    or lock_name in {"", ".", ".."}
):
    raise SystemExit("receipt and lock must be safe siblings in one directory")
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required atomic receipt flags are unavailable")


def dev_inode(value):
    return (value.st_dev, value.st_ino)


def file_identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_nlink,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


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


def read_owner(owner_fd, limit):
    os.lseek(owner_fd, 0, os.SEEK_SET)
    value = b""
    while len(value) <= limit:
        chunk = os.read(owner_fd, limit + 1 - len(value))
        if not chunk:
            break
        value += chunk
    return value


def sync_directory(directory_fd):
    try:
        os.fsync(directory_fd)
    except OSError as error:
        unsupported = {errno.EINVAL}
        unsupported.update(
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        )
        if error.errno not in unsupported:
            raise


parent_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
parent_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
lock_flags = parent_flags
owner_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
owner_flags |= getattr(os, "O_CLOEXEC", 0)
temp_flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
temp_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)


def open_receipt_parent():
    directory_fds = []
    try:
        current_path = anchor_path
        current_fd = os.open(anchor_path, parent_flags)
        directory_fds.append(current_fd)
        anchor_state = os.fstat(current_fd)
        named_anchor_state = os.lstat(anchor_path)
        if (
            not stat.S_ISDIR(anchor_state.st_mode)
            or anchor_state.st_uid != os.geteuid()
            or named_anchor_state.st_uid != os.geteuid()
            or stat.S_IMODE(anchor_state.st_mode) & 0o022
            or stat.S_IMODE(named_anchor_state.st_mode) & 0o022
            or dev_inode(anchor_state) != dev_inode(named_anchor_state)
            or has_extended_acl(current_path)
        ):
            raise OSError("receipt anchor identity, mode, or extended ACL is unsafe")
        for index, component in enumerate(components):
            current_path = os.path.join(current_path, component)
            next_fd = os.open(component, parent_flags, dir_fd=current_fd)
            directory_fds.append(next_fd)
            directory_state = os.fstat(next_fd)
            named_directory_state = os.stat(
                component,
                dir_fd=current_fd,
                follow_symlinks=False,
            )
            is_final = index == len(components) - 1
            if (
                not stat.S_ISDIR(directory_state.st_mode)
                or directory_state.st_uid != os.geteuid()
                or named_directory_state.st_uid != os.geteuid()
                or dev_inode(directory_state) != dev_inode(named_directory_state)
                or has_extended_acl(current_path)
                or (
                    is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) != 0o700
                        or stat.S_IMODE(named_directory_state.st_mode) != 0o700
                    )
                )
                or (
                    not is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) & 0o022
                        or stat.S_IMODE(named_directory_state.st_mode) & 0o022
                    )
                )
            ):
                raise OSError("receipt directory chain or extended ACL is unsafe")
            current_fd = next_fd
        result_fd = directory_fds.pop()
        return result_fd
    finally:
        for directory_fd in reversed(directory_fds):
            os.close(directory_fd)


parent_fd = open_receipt_parent()
lock_fd = None
owner_fd = None
temp_fd = None
temp_name = None
temp_identity = None
committed = False
try:
    parent_state = os.fstat(parent_fd)
    if (
        not stat.S_ISDIR(parent_state.st_mode)
        or parent_state.st_uid != os.geteuid()
        or stat.S_IMODE(parent_state.st_mode) != 0o700
        or dev_inode(parent_state) != expected_identity[:2]
        or has_extended_acl(parent_path)
    ):
        raise OSError("receipt parent identity or extended ACL changed")

    lock_fd = os.open(lock_name, lock_flags, dir_fd=parent_fd)
    lock_state = os.fstat(lock_fd)
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(lock_state.st_mode)
        or lock_state.st_uid != os.geteuid()
        or named_lock_state.st_uid != os.geteuid()
        or stat.S_IMODE(lock_state.st_mode) != 0o700
        or stat.S_IMODE(named_lock_state.st_mode) != 0o700
        or dev_inode(lock_state) != dev_inode(named_lock_state)
        or dev_inode(lock_state) != expected_identity[2:4]
        or has_extended_acl(lock_path)
    ):
        raise OSError("host-gate lock identity or extended ACL changed")

    owner_fd = os.open("owner", owner_flags, dir_fd=lock_fd)
    owner_state = os.fstat(owner_fd)
    named_owner_state = os.stat("owner", dir_fd=lock_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(owner_state.st_mode)
        or owner_state.st_uid != os.geteuid()
        or named_owner_state.st_uid != os.geteuid()
        or stat.S_IMODE(owner_state.st_mode) != 0o600
        or stat.S_IMODE(named_owner_state.st_mode) != 0o600
        or owner_state.st_nlink != 1
        or dev_inode(owner_state) != dev_inode(named_owner_state)
        or dev_inode(owner_state) != expected_identity[4:]
        or has_extended_acl(owner_path)
        or read_owner(owner_fd, len(expected_owner)) != expected_owner
    ):
        raise OSError("host-gate lock owner identity, ACL, or token changed")

    for _ in range(16):
        candidate = f".{receipt_name}.{os.urandom(16).hex()}.tmp"
        try:
            temp_fd = os.open(candidate, temp_flags, 0o600, dir_fd=parent_fd)
            temp_name = candidate
            break
        except FileExistsError:
            continue
    if temp_fd is None or temp_name is None:
        raise OSError("could not reserve a private receipt temp")
    temp_path = os.path.join(parent_path, temp_name)
    os.fchmod(temp_fd, 0o600)
    temp_identity = dev_inode(os.fstat(temp_fd))
    remaining = memoryview(payload)
    while remaining:
        written = os.write(temp_fd, remaining)
        if written <= 0:
            raise OSError("short receipt temp write")
        remaining = remaining[written:]
    os.fsync(temp_fd)

    temp_state = os.fstat(temp_fd)
    named_temp_state = os.stat(temp_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(temp_state.st_mode)
        or temp_state.st_uid != os.geteuid()
        or named_temp_state.st_uid != os.geteuid()
        or stat.S_IMODE(temp_state.st_mode) != 0o600
        or stat.S_IMODE(named_temp_state.st_mode) != 0o600
        or temp_state.st_nlink != 1
        or dev_inode(temp_state) != dev_inode(named_temp_state)
        or dev_inode(temp_state) != temp_identity
        or has_extended_acl(temp_path)
    ):
        raise OSError("receipt temp identity or extended ACL changed")

    verified_parent_fd = open_receipt_parent()
    verified_parent_state = os.fstat(verified_parent_fd)
    os.close(verified_parent_fd)
    current_lock_state = os.fstat(lock_fd)
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    current_owner_state = os.fstat(owner_fd)
    named_owner_state = os.stat("owner", dir_fd=lock_fd, follow_symlinks=False)
    if (
        dev_inode(verified_parent_state) != expected_identity[:2]
        or dev_inode(current_lock_state) != expected_identity[2:4]
        or dev_inode(named_lock_state) != expected_identity[2:4]
        or current_lock_state.st_uid != os.geteuid()
        or named_lock_state.st_uid != os.geteuid()
        or stat.S_IMODE(current_lock_state.st_mode) != 0o700
        or stat.S_IMODE(named_lock_state.st_mode) != 0o700
        or has_extended_acl(lock_path)
        or file_identity(current_owner_state) != file_identity(owner_state)
        or dev_inode(named_owner_state) != expected_identity[4:]
        or current_owner_state.st_uid != os.geteuid()
        or named_owner_state.st_uid != os.geteuid()
        or stat.S_IMODE(current_owner_state.st_mode) != 0o600
        or stat.S_IMODE(named_owner_state.st_mode) != 0o600
        or has_extended_acl(owner_path)
        or read_owner(owner_fd, len(expected_owner)) != expected_owner
    ):
        raise OSError("host-gate publication ownership or extended ACL changed")

    os.replace(
        temp_name,
        receipt_name,
        src_dir_fd=parent_fd,
        dst_dir_fd=parent_fd,
    )
    committed = True
    os.lseek(temp_fd, 0, os.SEEK_SET)
    published_bytes = b""
    while len(published_bytes) <= len(payload):
        chunk = os.read(temp_fd, len(payload) + 1 - len(published_bytes))
        if not chunk:
            break
        published_bytes += chunk
    published_fd_state = os.fstat(temp_fd)
    receipt_state = os.stat(receipt_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(published_fd_state.st_mode)
        or published_fd_state.st_uid != os.geteuid()
        or receipt_state.st_uid != os.geteuid()
        or stat.S_IMODE(published_fd_state.st_mode) != 0o600
        or published_fd_state.st_nlink != 1
        or published_fd_state.st_size != len(payload)
        or dev_inode(published_fd_state) != temp_identity
        or file_identity(receipt_state) != file_identity(published_fd_state)
        or has_extended_acl(receipt_path)
        or published_bytes != payload
    ):
        raise OSError(
            "published receipt identity, size, bytes, or extended ACL changed"
        )
    verified_parent_fd = open_receipt_parent()
    verified_parent_state = os.fstat(verified_parent_fd)
    os.close(verified_parent_fd)
    if (
        dev_inode(verified_parent_state) != expected_identity[:2]
        or has_extended_acl(parent_path)
        or has_extended_acl(lock_path)
        or has_extended_acl(owner_path)
        or has_extended_acl(receipt_path)
    ):
        raise OSError(
            "receipt state identity or extended ACL changed after publication"
        )
    sync_directory(parent_fd)
    print(":".join(str(part) for part in file_identity(published_fd_state)))
finally:
    if temp_name is not None and not committed and parent_fd is not None:
        try:
            current_temp_state = os.stat(
                temp_name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if temp_identity is not None and dev_inode(current_temp_state) == temp_identity:
                os.unlink(temp_name, dir_fd=parent_fd)
        except FileNotFoundError:
            pass
    if temp_fd is not None:
        os.close(temp_fd)
    if owner_fd is not None:
        os.close(owner_fd)
    if lock_fd is not None:
        os.close(lock_fd)
    os.close(parent_fd)
PY
}

release_host_gate_lock() {
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - \
    "$script_dir" "$receipt_relative_dir" "$receipt_path" "$lock_dir" \
    "$run_owner" "$lock_identity" "$active_receipt_identity" "$active_receipt" <<'PY'
import errno
import os
import stat
import subprocess
import sys

anchor_path = os.path.abspath(sys.argv[1])
components = sys.argv[2].split("/")
receipt_path = os.path.abspath(sys.argv[3])
lock_path = os.path.abspath(sys.argv[4])
expected_owner = (sys.argv[5] + "\n").encode("utf-8")
try:
    expected_identity = tuple(int(part) for part in sys.argv[6].split(":"))
except ValueError as error:
    raise SystemExit(f"invalid host-gate lock identity: {error}")
if len(expected_identity) != 6:
    raise SystemExit("invalid host-gate lock identity")
try:
    expected_receipt_identity = tuple(int(part) for part in sys.argv[7].split(":"))
except ValueError as error:
    raise SystemExit(f"invalid host-gate receipt identity: {error}")
if len(expected_receipt_identity) != 7:
    raise SystemExit("invalid host-gate receipt identity")
expected_receipt = (sys.argv[8] + "\n").encode("utf-8")

parent_path = os.path.dirname(receipt_path)
lock_parent_path = os.path.dirname(lock_path)
receipt_name = os.path.basename(receipt_path)
lock_name = os.path.basename(lock_path)
owner_path = os.path.join(lock_path, "owner")
if (
    not components
    or any(
        component in {"", ".", ".."} or os.path.basename(component) != component
        for component in components
    )
    or parent_path != lock_parent_path
    or parent_path != os.path.join(anchor_path, *components)
    or receipt_name in {"", ".", ".."}
    or lock_name in {"", ".", ".."}
):
    raise SystemExit("receipt and lock must be safe siblings in one directory")
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required lock-cleanup flags are unavailable")


def dev_inode(value):
    return (value.st_dev, value.st_ino)


def file_identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_nlink,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


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


def read_owner(owner_fd, limit):
    os.lseek(owner_fd, 0, os.SEEK_SET)
    value = b""
    while len(value) <= limit:
        chunk = os.read(owner_fd, limit + 1 - len(value))
        if not chunk:
            break
        value += chunk
    return value


def open_receipt_parent():
    directory_fds = []
    try:
        current_path = anchor_path
        current_fd = os.open(anchor_path, parent_flags)
        directory_fds.append(current_fd)
        anchor_state = os.fstat(current_fd)
        named_anchor_state = os.lstat(anchor_path)
        if (
            not stat.S_ISDIR(anchor_state.st_mode)
            or anchor_state.st_uid != os.geteuid()
            or named_anchor_state.st_uid != os.geteuid()
            or stat.S_IMODE(anchor_state.st_mode) & 0o022
            or stat.S_IMODE(named_anchor_state.st_mode) & 0o022
            or dev_inode(anchor_state) != dev_inode(named_anchor_state)
            or has_extended_acl(current_path)
        ):
            raise OSError("lock anchor identity, mode, or extended ACL is unsafe")
        for index, component in enumerate(components):
            current_path = os.path.join(current_path, component)
            next_fd = os.open(component, parent_flags, dir_fd=current_fd)
            directory_fds.append(next_fd)
            directory_state = os.fstat(next_fd)
            named_directory_state = os.stat(
                component,
                dir_fd=current_fd,
                follow_symlinks=False,
            )
            is_final = index == len(components) - 1
            if (
                not stat.S_ISDIR(directory_state.st_mode)
                or directory_state.st_uid != os.geteuid()
                or named_directory_state.st_uid != os.geteuid()
                or dev_inode(directory_state) != dev_inode(named_directory_state)
                or has_extended_acl(current_path)
                or (
                    is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) != 0o700
                        or stat.S_IMODE(named_directory_state.st_mode) != 0o700
                    )
                )
                or (
                    not is_final
                    and (
                        stat.S_IMODE(directory_state.st_mode) & 0o022
                        or stat.S_IMODE(named_directory_state.st_mode) & 0o022
                    )
                )
            ):
                raise OSError("lock directory chain or extended ACL is unsafe")
            current_fd = next_fd
        result_fd = directory_fds.pop()
        return result_fd
    finally:
        for directory_fd in reversed(directory_fds):
            os.close(directory_fd)


def sync_directory(directory_fd):
    try:
        os.fsync(directory_fd)
    except OSError as error:
        unsupported = {errno.EINVAL}
        unsupported.update(
            value
            for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        )
        if error.errno not in unsupported:
            raise


parent_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
parent_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
owner_flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
owner_flags |= getattr(os, "O_CLOEXEC", 0)
receipt_flags = owner_flags

parent_fd = open_receipt_parent()
lock_fd = None
owner_fd = None
receipt_fd = None
release_committed = False
try:
    parent_state = os.fstat(parent_fd)
    if (
        not stat.S_ISDIR(parent_state.st_mode)
        or parent_state.st_uid != os.geteuid()
        or stat.S_IMODE(parent_state.st_mode) != 0o700
        or dev_inode(parent_state) != expected_identity[:2]
        or has_extended_acl(parent_path)
    ):
        raise OSError("lock parent identity or extended ACL changed")

    receipt_fd = os.open(receipt_name, receipt_flags, dir_fd=parent_fd)

    def validate_bound_receipt():
        receipt_state = os.fstat(receipt_fd)
        named_receipt_state = os.stat(
            receipt_name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        if (
            not stat.S_ISREG(receipt_state.st_mode)
            or receipt_state.st_uid != os.geteuid()
            or named_receipt_state.st_uid != os.geteuid()
            or stat.S_IMODE(receipt_state.st_mode) != 0o600
            or stat.S_IMODE(named_receipt_state.st_mode) != 0o600
            or receipt_state.st_nlink != 1
            or file_identity(receipt_state) != expected_receipt_identity
            or file_identity(named_receipt_state) != expected_receipt_identity
            or has_extended_acl(receipt_path)
            or read_owner(receipt_fd, len(expected_receipt)) != expected_receipt
        ):
            raise OSError(
                "published host-gate receipt identity, ACL, or bytes changed"
            )

    validate_bound_receipt()

    lock_fd = os.open(lock_name, parent_flags, dir_fd=parent_fd)
    lock_state = os.fstat(lock_fd)
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(lock_state.st_mode)
        or lock_state.st_uid != os.geteuid()
        or named_lock_state.st_uid != os.geteuid()
        or stat.S_IMODE(lock_state.st_mode) != 0o700
        or stat.S_IMODE(named_lock_state.st_mode) != 0o700
        or dev_inode(lock_state) != dev_inode(named_lock_state)
        or dev_inode(lock_state) != expected_identity[2:4]
        or has_extended_acl(lock_path)
    ):
        raise OSError("host-gate lock identity or extended ACL changed")

    owner_fd = os.open("owner", owner_flags, dir_fd=lock_fd)
    owner_state = os.fstat(owner_fd)
    named_owner_state = os.stat("owner", dir_fd=lock_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(owner_state.st_mode)
        or owner_state.st_uid != os.geteuid()
        or named_owner_state.st_uid != os.geteuid()
        or stat.S_IMODE(owner_state.st_mode) != 0o600
        or stat.S_IMODE(named_owner_state.st_mode) != 0o600
        or owner_state.st_nlink != 1
        or dev_inode(owner_state) != dev_inode(named_owner_state)
        or dev_inode(owner_state) != expected_identity[4:]
        or has_extended_acl(owner_path)
        or read_owner(owner_fd, len(expected_owner)) != expected_owner
    ):
        raise OSError("host-gate lock owner identity, ACL, or token changed")

    verified_parent_fd = open_receipt_parent()
    verified_parent_state = os.fstat(verified_parent_fd)
    os.close(verified_parent_fd)
    current_lock_state = os.fstat(lock_fd)
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    current_owner_state = os.fstat(owner_fd)
    named_owner_state = os.stat("owner", dir_fd=lock_fd, follow_symlinks=False)
    if (
        dev_inode(verified_parent_state) != expected_identity[:2]
        or dev_inode(current_lock_state) != expected_identity[2:4]
        or dev_inode(named_lock_state) != expected_identity[2:4]
        or current_lock_state.st_uid != os.geteuid()
        or named_lock_state.st_uid != os.geteuid()
        or stat.S_IMODE(current_lock_state.st_mode) != 0o700
        or stat.S_IMODE(named_lock_state.st_mode) != 0o700
        or has_extended_acl(lock_path)
        or file_identity(current_owner_state) != file_identity(owner_state)
        or dev_inode(named_owner_state) != expected_identity[4:]
        or current_owner_state.st_uid != os.geteuid()
        or named_owner_state.st_uid != os.geteuid()
        or stat.S_IMODE(current_owner_state.st_mode) != 0o600
        or stat.S_IMODE(named_owner_state.st_mode) != 0o600
        or has_extended_acl(owner_path)
        or has_extended_acl(receipt_path)
        or read_owner(owner_fd, len(expected_owner)) != expected_owner
    ):
        raise OSError("host-gate cleanup ownership or extended ACL changed")

    validate_bound_receipt()
    os.unlink("owner", dir_fd=lock_fd)
    sync_directory(lock_fd)
    os.close(owner_fd)
    owner_fd = None
    named_lock_state = os.stat(lock_name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        dev_inode(named_lock_state) != expected_identity[2:4]
        or has_extended_acl(parent_path)
        or has_extended_acl(lock_path)
        or has_extended_acl(receipt_path)
    ):
        raise OSError("host-gate lock or extended ACL changed before removal")
    # POSIX cannot atomically bind this sibling receipt check to rmdir.  This
    # fence covers cooperating runners and accidental path/inode races, not a
    # hostile same-EUID process; authority comes from the exact-HEAD CI artifact.
    validate_bound_receipt()
    os.close(receipt_fd)
    receipt_fd = None
    sync_directory(parent_fd)
    os.close(lock_fd)
    lock_fd = None
    # rmdir is the release commit.  A SIGKILL or host loss in the unavoidable
    # interval between the kernel completing rmdir and the caller observing this
    # return cannot be represented atomically; no terminal PASS is emitted in
    # that interval.  After this point, descriptor cleanup is best-effort so it
    # cannot turn an unlocked PASS into a reportable failure.
    os.rmdir(lock_name, dir_fd=parent_fd)
    release_committed = True
finally:
    if receipt_fd is not None:
        try:
            os.close(receipt_fd)
        except OSError:
            if not release_committed:
                raise
    if owner_fd is not None:
        try:
            os.close(owner_fd)
        except OSError:
            if not release_committed:
                raise
    if lock_fd is not None:
        try:
            os.close(lock_fd)
        except OSError:
            if not release_committed:
                raise
    try:
        os.close(parent_fd)
    except OSError:
        if not release_committed:
            raise
PY
}

cleanup_host_gate_lock() {
  if [[ "${java_stage_owned:-0}" -eq 1 ]]; then
    if remove_ephemeral_java_runtime_root "$receipt_dir" "$host_java_stage_root"; then
      java_stage_owned=0
    else
      printf 'Host integration gate retained an unsafe ephemeral Java runtime: %s\n' \
        "$host_java_stage_root" >&2
    fi
  fi
  if [[ "${gradle_home_owned:-0}" -eq 1 ]]; then
    if remove_ephemeral_gradle_home "$receipt_dir" "$host_gradle_user_home"; then
      gradle_home_owned=0
    else
      printf 'Host integration gate retained an unsafe ephemeral Gradle home: %s\n' \
        "$host_gradle_user_home" >&2
    fi
  fi
  if [[ "${child_home_owned:-0}" -eq 1 ]]; then
    if remove_ephemeral_child_home "$receipt_dir" "$host_child_home"; then
      child_home_owned=0
    else
      printf 'Host integration gate retained an unsafe ephemeral child home: %s\n' \
        "$host_child_home" >&2
    fi
  fi
  if [[ "${lock_owned:-0}" -eq 1 && "${lock_releasable:-0}" -eq 1 ]]; then
    if release_host_gate_lock; then
      lock_owned=0
    else
      printf 'Host integration gate retained an ambiguous owner lock: %s\n' "$lock_dir" >&2
    fi
  fi
}

run_clean_host_command() {
  [[ "$#" -gt 0 ]] || return 1
  /usr/bin/env -i \
    ADB=/usr/bin/false \
    ANDROID_HOME="$host_android_home" \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_SYSTEM=/dev/null \
    GRADLE_USER_HOME="$host_gradle_user_home" \
    HOME="$host_child_home" \
    ISSUE66_ACTIVE_JDK17_HOME="$host_java_home" \
    ISSUE66_DARWIN_TEMURIN_JDK17_HOME="$host_java_darwin_temurin_profile_home" \
    ISSUE66_TEMURIN_JDK17_HOME="$host_java_temurin_profile_home" \
    JAVA_HOME="$host_java_home" \
    LANG=C \
    LC_ALL=C \
    PATH=/usr/bin:/bin \
    "$@"
}

create_ephemeral_private_home() {
  local parent="$1" prefix="$2"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - "$parent" "$prefix" <<'PY'
import os
import pathlib
import secrets
import stat
import sys

parent = pathlib.Path(os.path.abspath(sys.argv[1]))
prefix = sys.argv[2]
if prefix not in {"gradle-user-home", "child-home", "jdk-runtime"}:
    raise SystemExit(1)
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit(1)
flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
parent_fd = os.open(parent, flags)
try:
    parent_stat = os.fstat(parent_fd)
    named_parent = os.lstat(parent)
    if (
        not stat.S_ISDIR(parent_stat.st_mode)
        or parent_stat.st_uid != os.geteuid()
        or stat.S_IMODE(parent_stat.st_mode) != 0o700
        or (parent_stat.st_dev, parent_stat.st_ino)
        != (named_parent.st_dev, named_parent.st_ino)
    ):
        raise SystemExit(1)
    for _ in range(16):
        name = f"{prefix}.{secrets.token_hex(16)}"
        try:
            os.mkdir(name, mode=0o700, dir_fd=parent_fd)
        except FileExistsError:
            continue
        child_fd = os.open(name, flags, dir_fd=parent_fd)
        try:
            child_stat = os.fstat(child_fd)
            if (
                not stat.S_ISDIR(child_stat.st_mode)
                or child_stat.st_uid != os.geteuid()
                or stat.S_IMODE(child_stat.st_mode) != 0o700
            ):
                raise SystemExit(1)
        finally:
            os.close(child_fd)
        print(parent / name)
        break
    else:
        raise SystemExit(1)
finally:
    os.close(parent_fd)
PY
}

create_ephemeral_gradle_home() {
  create_ephemeral_private_home "$1" gradle-user-home
}

create_ephemeral_child_home() {
  create_ephemeral_private_home "$1" child-home
}

create_ephemeral_java_runtime_root() {
  create_ephemeral_private_home "$1" jdk-runtime
}

remove_ephemeral_private_home() {
  local parent="$1" target="$2" prefix="$3"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - "$parent" "$target" "$prefix" <<'PY'
import os
import pathlib
import re
import stat
import sys

parent = pathlib.Path(os.path.abspath(sys.argv[1]))
target = pathlib.Path(os.path.abspath(sys.argv[2]))
prefix = sys.argv[3]
if prefix not in {"gradle-user-home", "child-home", "jdk-runtime"}:
    raise SystemExit(1)
if target.parent != parent or not re.fullmatch(
    re.escape(prefix) + r"\.[0-9a-f]{32}", target.name
):
    raise SystemExit(1)
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit(1)
directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
parent_fd = os.open(parent, directory_flags)
target_fd = None
removed = 0


def bounded_directory_names(directory_fd, remaining):
    if remaining < 0:
        raise OSError("ephemeral home exceeds its fixed traversal ceiling")
    names = []
    with os.scandir(directory_fd) as entries:
        for entry in entries:
            if len(names) >= remaining:
                raise OSError("ephemeral home exceeds its fixed traversal ceiling")
            names.append(entry.name)
    names.sort(key=os.fsencode)
    return names


def clear_directory(directory_fd, depth):
    global removed
    if depth > 128:
        raise OSError("ephemeral home exceeds its fixed traversal depth")
    for name in bounded_directory_names(directory_fd, 100000 - removed):
        removed += 1
        value = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        if stat.S_ISDIR(value.st_mode) and not stat.S_ISLNK(value.st_mode):
            if value.st_uid != os.geteuid():
                raise OSError("ephemeral home directory owner changed")
            if stat.S_IMODE(value.st_mode) != 0o700:
                os.chmod(name, 0o700, dir_fd=directory_fd, follow_symlinks=False)
                changed = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
                if (
                    not stat.S_ISDIR(changed.st_mode)
                    or changed.st_uid != os.geteuid()
                    or stat.S_IMODE(changed.st_mode) != 0o700
                    or (changed.st_dev, changed.st_ino) != (value.st_dev, value.st_ino)
                ):
                    raise OSError("ephemeral home directory changed during permission repair")
                value = changed
            child_fd = os.open(name, directory_flags, dir_fd=directory_fd)
            try:
                opened = os.fstat(child_fd)
                if (opened.st_dev, opened.st_ino) != (value.st_dev, value.st_ino):
                    raise OSError("ephemeral home entry changed during cleanup")
                clear_directory(child_fd, depth + 1)
            finally:
                os.close(child_fd)
            os.rmdir(name, dir_fd=directory_fd)
        else:
            os.unlink(name, dir_fd=directory_fd)


try:
    parent_state = os.fstat(parent_fd)
    named_parent_state = os.lstat(parent)
    if (
        not stat.S_ISDIR(parent_state.st_mode)
        or parent_state.st_uid != os.geteuid()
        or stat.S_IMODE(parent_state.st_mode) != 0o700
        or (parent_state.st_dev, parent_state.st_ino)
        != (named_parent_state.st_dev, named_parent_state.st_ino)
    ):
        raise OSError("ephemeral-home parent identity, owner, or mode is unsafe")
    try:
        target_fd = os.open(target.name, directory_flags, dir_fd=parent_fd)
    except FileNotFoundError:
        raise SystemExit(0)
    target_state = os.fstat(target_fd)
    named_target_state = os.stat(target.name, dir_fd=parent_fd, follow_symlinks=False)
    if (
        not stat.S_ISDIR(target_state.st_mode)
        or target_state.st_uid != os.geteuid()
        or stat.S_IMODE(target_state.st_mode) != 0o700
        or (target_state.st_dev, target_state.st_ino)
        != (named_target_state.st_dev, named_target_state.st_ino)
    ):
        raise OSError("ephemeral home identity, owner, or mode is unsafe")
    clear_directory(target_fd, 0)
    os.close(target_fd)
    target_fd = None
    confirmed_target_state = os.stat(
        target.name, dir_fd=parent_fd, follow_symlinks=False
    )
    if (confirmed_target_state.st_dev, confirmed_target_state.st_ino) != (
        target_state.st_dev,
        target_state.st_ino,
    ):
        raise OSError("ephemeral home changed during cleanup")
    os.rmdir(target.name, dir_fd=parent_fd)
    try:
        os.stat(target.name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        pass
    else:
        raise OSError("ephemeral home still exists after cleanup")
finally:
    if target_fd is not None:
        os.close(target_fd)
    os.close(parent_fd)
PY
}

remove_ephemeral_gradle_home() {
  remove_ephemeral_private_home "$1" "$2" gradle-user-home
}

remove_ephemeral_child_home() {
  remove_ephemeral_private_home "$1" "$2" child-home
}

remove_ephemeral_java_runtime_root() {
  remove_ephemeral_private_home "$1" "$2" jdk-runtime
}

validate_clean_gradle_user_home() {
  local gradle_home="$1"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - "$gradle_home" <<'PY'
import os
import stat
import sys

gradle_home = os.path.abspath(sys.argv[1])
if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
    raise SystemExit("required Gradle-home validation flags are unavailable")
directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
directory_fd = os.open(gradle_home, directory_flags)


def identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_nlink,
        value.st_uid,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def reject_startup_injection_entries():
    for name in ("init.gradle", "init.gradle.kts", "init.d", "gradle.properties"):
        try:
            os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
        except FileNotFoundError:
            continue
        raise OSError(f"Gradle startup injection entry is forbidden: {name}")

    inspected = 0

    def bounded_directory_names(current_fd, remaining):
        if remaining < 0:
            raise OSError("Gradle home exceeds its fixed traversal ceiling")
        names = []
        with os.scandir(current_fd) as entries:
            for entry in entries:
                if len(names) >= remaining:
                    raise OSError("Gradle home exceeds its fixed traversal ceiling")
                names.append(entry.name)
        names.sort(key=os.fsencode)
        return names

    def inspect_directory(current_fd, current_name, depth):
        nonlocal inspected
        if depth > 128:
            raise OSError("Gradle home exceeds its fixed traversal depth")
        for name in bounded_directory_names(current_fd, 100000 - inspected):
            inspected += 1
            value = os.stat(name, dir_fd=current_fd, follow_symlinks=False)
            if stat.S_ISLNK(value.st_mode):
                raise OSError(f"Gradle home symlink is forbidden: {name}")
            if current_name == "init.d" and (
                name.endswith(".gradle") or name.endswith(".gradle.kts")
            ):
                raise OSError(f"Gradle distribution init script is forbidden: {name}")
            if not stat.S_ISDIR(value.st_mode):
                continue
            child_fd = os.open(name, directory_flags, dir_fd=current_fd)
            try:
                opened = os.fstat(child_fd)
                if (opened.st_dev, opened.st_ino) != (value.st_dev, value.st_ino):
                    raise OSError("Gradle home entry changed during validation")
                inspect_directory(child_fd, name, depth + 1)
            finally:
                os.close(child_fd)

    inspect_directory(directory_fd, "", 0)


try:
    initial_state = os.fstat(directory_fd)
    named_initial_state = os.lstat(gradle_home)
    if (
        not stat.S_ISDIR(initial_state.st_mode)
        or initial_state.st_uid != os.geteuid()
        or named_initial_state.st_uid != os.geteuid()
        or stat.S_IMODE(initial_state.st_mode) != 0o700
        or stat.S_IMODE(named_initial_state.st_mode) != 0o700
        or (initial_state.st_dev, initial_state.st_ino)
        != (named_initial_state.st_dev, named_initial_state.st_ino)
    ):
        raise OSError("dedicated Gradle home identity, owner, or mode is unsafe")
    reject_startup_injection_entries()
    confirmed_state = os.fstat(directory_fd)
    named_confirmed_state = os.lstat(gradle_home)
    reject_startup_injection_entries()
    if (
        identity(confirmed_state) != identity(initial_state)
        or identity(named_confirmed_state) != identity(initial_state)
    ):
        raise OSError("dedicated Gradle home changed during validation")
finally:
    os.close(directory_fd)
PY
}

run_clean_gradle_command() {
  local command_status=0
  if ! verify_java_runtime_binding; then
    printf '%s\n' 'HOST_GATE_STAGED_JAVA_RUNTIME_CHANGED' >&2
    return 1
  fi
  if ! verify_android_sdk_binding; then
    printf '%s\n' 'HOST_GATE_ANDROID_SDK_CHANGED' >&2
    return 1
  fi
  if ! validate_clean_gradle_user_home "$host_gradle_user_home"; then
    printf '%s\n' 'HOST_GATE_UNSAFE_DEDICATED_GRADLE_HOME' >&2
    return 1
  fi
  run_clean_host_command "$@" || command_status=$?
  if ! verify_java_runtime_binding; then
    printf '%s\n' 'HOST_GATE_STAGED_JAVA_RUNTIME_CHANGED' >&2
    return 1
  fi
  if ! verify_android_sdk_binding; then
    printf '%s\n' 'HOST_GATE_ANDROID_SDK_CHANGED' >&2
    return 1
  fi
  if ! validate_clean_gradle_user_home "$host_gradle_user_home"; then
    printf '%s\n' 'HOST_GATE_DEDICATED_GRADLE_HOME_CHANGED' >&2
    return 1
  fi
  return "$command_status"
}

verify_gradle_test_attestation() {
  local attestation="$1" parent="$2" run_id_value="$3" stage="$4"
  local expected_task="$5" required_classes="$6"
  [[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 1
  /usr/bin/python3 -I - \
    "$attestation" "$parent" "$run_id_value" "$stage" "$expected_task" \
    "$required_classes" "$host_java_home" "$host_java_profile_id" \
    "$host_java_vendor" "$host_java_vm_vendor" "$host_java_runtime_version" \
    "$host_java_tree_sha256" <<'PY'
import hashlib
import os
import pathlib
import re
import stat
import sys

path = pathlib.Path(sys.argv[1]).absolute()
parent = pathlib.Path(sys.argv[2]).absolute()
expected_run_id = sys.argv[3]
expected_stage = sys.argv[4]
expected_task = sys.argv[5]
required_classes = sys.argv[6].split(",")
expected_java_home = sys.argv[7]
expected_profile_id = sys.argv[8]
expected_java_vendor = sys.argv[9]
expected_java_vm_vendor = sys.argv[10]
expected_runtime_version = sys.argv[11]
expected_tree_sha256 = sys.argv[12]
if (
    path.parent != parent
    or not re.fullmatch(r"gradle-attestation-[a-z]+-[0-9a-f]{32}\.txt", path.name)
    or not re.fullmatch(r"[0-9a-f]{32}", expected_run_id)
    or not re.fullmatch(r"[a-z]+", expected_stage)
    or not re.fullmatch(r":[A-Za-z0-9:_-]+", expected_task)
    or not required_classes
    or any(
        not re.fullmatch(
            r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+",
            name,
        )
        for name in required_classes
    )
    or not os.path.isabs(expected_java_home)
    or not re.fullmatch(r"[a-z0-9][a-z0-9._+-]{0,127}", expected_profile_id)
    or not expected_java_vendor
    or not expected_java_vm_vendor
    or not re.fullmatch(r"17\.[0-9][0-9A-Za-z.+_-]*", expected_runtime_version)
    or not re.fullmatch(r"[0-9a-f]{64}", expected_tree_sha256)
):
    raise SystemExit(1)
flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0)
descriptor = os.open(path, flags)
try:
    initial = os.fstat(descriptor)
    named_initial = os.lstat(path)
    if (
        not stat.S_ISREG(initial.st_mode)
        or initial.st_uid != os.geteuid()
        or stat.S_IMODE(initial.st_mode) != 0o600
        or initial.st_nlink != 1
        or initial.st_size < 1
        or initial.st_size > 16384
        or (initial.st_dev, initial.st_ino) != (named_initial.st_dev, named_initial.st_ino)
    ):
        raise SystemExit(1)
    raw = bytearray()
    while True:
        chunk = os.read(descriptor, min(4096, 16385 - len(raw)))
        if not chunk:
            break
        raw.extend(chunk)
        if len(raw) > 16384:
            raise SystemExit(1)
    confirmed = os.fstat(descriptor)
    named_confirmed = os.lstat(path)
    if (
        (confirmed.st_dev, confirmed.st_ino, confirmed.st_size, confirmed.st_mtime_ns, confirmed.st_ctime_ns)
        != (initial.st_dev, initial.st_ino, initial.st_size, initial.st_mtime_ns, initial.st_ctime_ns)
        or (named_confirmed.st_dev, named_confirmed.st_ino)
        != (initial.st_dev, initial.st_ino)
    ):
        raise SystemExit(1)
finally:
    os.close(descriptor)
try:
    text = bytes(raw).decode("utf-8")
except UnicodeDecodeError:
    raise SystemExit(1)
if not text.endswith("\n") or "\r" in text or "\x00" in text:
    raise SystemExit(1)
lines = text[:-1].split("\n")
expected_keys = [
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
if len(lines) != len(expected_keys):
    raise SystemExit(1)
values = {}
for expected_key, line in zip(expected_keys, lines):
    key, separator, value = line.partition("=")
    if not separator or key != expected_key or key in values or not value:
        raise SystemExit(1)
    values[key] = value
if (
    values["schemaVersion"] != "2"
    or values["runId"] != expected_run_id
    or values["stage"] != expected_stage
    or values["taskPath"] != expected_task
    or values["jdkHome"] != expected_java_home
    or values["jdkProfileId"] != expected_profile_id
    or values["javaVendor"] != expected_java_vendor
    or values["javaVmVendor"] != expected_java_vm_vendor
    or values["jdkRuntimeVersion"] != expected_runtime_version
    or values["jdkTreeSha256"] != expected_tree_sha256
    or values["jdkMajor"] != "17"
    or values["testLauncherMajor"] != "17"
    or not re.fullmatch(r"[1-9][0-9]*", values["testCount"])
    or values["failureCount"] != "0"
):
    raise SystemExit(1)
classes = values["classes"].split(",")
if classes != sorted(set(classes)) or any(
    not re.fullmatch(
        r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+",
        name,
    )
    for name in classes
):
    raise SystemExit(1)
for required in required_classes:
    if required not in classes:
        raise SystemExit(1)
print(hashlib.sha256(raw).hexdigest())
PY
}

run_attested_gradle_test() {
  local stage="$1" expected_task="$2" required_classes="$3"
  shift 3
  local attestation="$receipt_dir/gradle-attestation-$stage-$run_id.txt"
  local attestation_sha256=""
  if [[ -e "$attestation" || -L "$attestation" ]]; then
    printf '%s\n' 'HOST_GATE_GRADLE_ATTESTATION_INVALID' >&2
    return 1
  fi
  if ! run_clean_gradle_command "$@" \
    --init-script "$host_gradle_attestation_script" \
    --rerun-tasks \
    --no-build-cache \
    --no-configuration-cache \
    "-Pissue66AttestationPath=$attestation" \
    "-Pissue66RunId=$run_id" \
    "-Pissue66Stage=$stage" \
    "-Pissue66ExpectedTask=$expected_task" \
    "-Pissue66RequiredClasses=$required_classes" \
    "-Pissue66JdkHome=$host_java_home" \
    "-Pissue66JdkProfileId=$host_java_profile_id" \
    "-Pissue66JavaVendor=$host_java_vendor" \
    "-Pissue66JavaVmVendor=$host_java_vm_vendor" \
    "-Pissue66JdkRuntimeVersion=$host_java_runtime_version" \
    "-Pissue66JdkTreeSha256=$host_java_tree_sha256" \
    "-Dorg.gradle.java.installations.auto-detect=false" \
    "-Dorg.gradle.java.installations.auto-download=false" \
    "-Dorg.gradle.java.installations.paths=$host_java_home" \
    "-Pkotlin.compiler.execution.strategy=in-process"; then
    return 1
  fi
  if ! attestation_sha256="$(verify_gradle_test_attestation \
    "$attestation" "$receipt_dir" "$run_id" "$stage" "$expected_task" \
    "$required_classes")" || [[ ! "$attestation_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    printf '%s\n' 'HOST_GATE_GRADLE_ATTESTATION_INVALID' >&2
    return 1
  fi
  host_last_attestation_sha256="$attestation_sha256"
  printf 'Host Gradle attestation %s: %s\n' "$stage" "$attestation_sha256"
}

if [[ "$#" -eq 0 ]]; then
  receipt_relative_dir="harness/build/reports/pr63-on-issue66"
  receipt_dir="$script_dir/$receipt_relative_dir"
  host_child_home=""
  host_gradle_user_home=""
  host_java_stage_root=""
  host_last_attestation_sha256=""
  auto_attestation_sha256="NOT_AVAILABLE_YET"
  qwy_attestation_sha256="NOT_AVAILABLE_YET"
  harness_attestation_sha256="NOT_AVAILABLE_YET"
  child_home_owned=0
  gradle_home_owned=0
  java_stage_owned=0
  lock_owned=0
  lock_releasable=0
  trap cleanup_host_gate_lock EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  if ! prepare_private_directory "$script_dir" "$receipt_relative_dir"; then
    echo "Host integration gate receipt directory is not private and current-user-owned." >&2
    exit 1
  fi
  receipt_path="$receipt_dir/host-gate-receipt.json"
  lock_dir="$receipt_dir/host-gate.lock"
  lock_owner_path="$lock_dir/owner"
  if ! host_child_home="$(create_ephemeral_child_home "$receipt_dir")" ||
    [[ -z "$host_child_home" ]]; then
    echo "Host integration gate could not prepare its clean child environment." >&2
    exit 1
  fi
  child_home_owned=1
  if ! host_gradle_user_home="$(create_ephemeral_gradle_home "$receipt_dir")" ||
    [[ -z "$host_gradle_user_home" ]] ||
    ! validate_clean_gradle_user_home "$host_gradle_user_home"; then
    echo "Host integration gate could not prepare its clean child environment." >&2
    exit 1
  fi
  gradle_home_owned=1
  readonly host_child_home host_gradle_user_home
  runner_path="$script_dir/${0##*/}"
  run_id=""
  run_owner=""
  lock_base_identity=""
  lock_identity=""
  active_receipt=""
  active_receipt_identity=""
  if lock_base_identity="$(
    create_host_gate_lock "$script_dir" "$receipt_relative_dir" "$lock_dir"
  )"; then
    if [[ ! "$lock_base_identity" =~ ^[0-9]+(:[0-9]+){3}$ ]]; then
      echo "Host integration gate could not bind its new lock identity." >&2
      exit 1
    fi
  else
    lock_status="$?"
    if [[ "$lock_status" -eq 75 ]]; then
      echo "Host integration gate is already running; lock: $lock_dir" >&2
      exit 75
    fi
    echo "Host integration gate could not create its private lock." >&2
    exit 1
  fi
  lock_owned=1

  # Until a RUNNING receipt is published, any early failure deliberately leaves
  # this owner lock behind as a fail-closed fence around an older receipt.
  if ! run_id="$(new_run_id)" || [[ ! "$run_id" =~ ^[0-9a-f]{32}$ ]]; then
    echo "Host integration gate could not create a private run identifier." >&2
    exit 1
  fi
  run_owner="pid=$$;ppid=$PPID;run-id=$run_id"
  if ! lock_identity="$(
    write_private_file_exclusively \
      "$script_dir" "$receipt_relative_dir" "$lock_owner_path" "$run_owner" \
      "$lock_base_identity"
  )"; then
    echo "Host integration gate could not publish its lock ownership." >&2
    exit 1
  fi
  if ! source_identity="$(read_source_provenance)"; then
    echo "Host integration gate requires one stable clean HEAD/tree with no untracked files." >&2
    exit 1
  fi
  source_head="${source_identity%%:*}"
  source_tree="${source_identity#*:}"
  if ! runner_sha256="$(read_runner_sha256 "$runner_path")" ||
    [[ ! "$runner_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Host integration gate could not bind the executing runner bytes." >&2
    exit 1
  fi
  if ! reviewed_runner_sha256="$(read_head_runner_sha256 "$source_head" "$runner_path")" ||
    [[ ! "$reviewed_runner_sha256" =~ ^[0-9a-f]{64}$ ]] ||
    [[ "$reviewed_runner_sha256" != "$runner_sha256" ]]; then
    echo "Host integration gate runner bytes do not match the runner blob at source HEAD." >&2
    exit 1
  fi
  if [[ -z "$requested_java_home" ]]; then
    echo "JAVA_HOME must point to a reviewed JDK 17 runtime." >&2
    exit 1
  fi
  if ! host_java_stage_root="$(create_ephemeral_java_runtime_root "$receipt_dir")" ||
    [[ -z "$host_java_stage_root" ]]; then
    printf '%s\n' 'HOST_GATE_EPHEMERAL_JAVA_RUNTIME_PREPARATION_FAILED' >&2
    exit 1
  fi
  java_stage_owned=1
  if ! host_java_binding="$(
    stage_java_runtime "$requested_java_home" "$host_java_stage_root"
  )" || [[ -z "$host_java_binding" ]]; then
    printf '%s\n' 'HOST_GATE_JAVA_RUNTIME_INVALID' >&2
    exit 1
  fi
  if ! host_java_home="$(read_java_binding_field "$host_java_binding" javaHome)" ||
    ! host_java_profile_id="$(read_java_binding_field "$host_java_binding" profileId)" ||
    ! host_java_vendor="$(read_java_binding_field "$host_java_binding" javaVendor)" ||
    ! host_java_vm_vendor="$(read_java_binding_field "$host_java_binding" javaVmVendor)" ||
    ! host_java_runtime_version="$(
      read_java_binding_field "$host_java_binding" javaRuntimeVersion
    )" ||
    ! host_java_tree_sha256="$(read_java_binding_field "$host_java_binding" jdkTreeSha256)" ||
    [[ "$host_java_home" != "$host_java_stage_root/home" ]] ||
    [[ ! "$host_java_profile_id" =~ ^[a-z0-9][a-z0-9._+-]{0,127}$ ]] ||
    [[ -z "$host_java_vendor" ]] ||
    [[ -z "$host_java_vm_vendor" ]] ||
    [[ ! "$host_java_runtime_version" =~ ^17\.[0-9][0-9A-Za-z.+_-]*$ ]] ||
    [[ ! "$host_java_tree_sha256" =~ ^[0-9a-f]{64}$ ]] ||
    ! verify_java_runtime_binding; then
    printf '%s\n' 'HOST_GATE_STAGED_JAVA_RUNTIME_INVALID' >&2
    exit 1
  fi
  readonly host_java_stage_root host_java_home host_java_binding
  readonly host_java_profile_id host_java_vendor host_java_vm_vendor
  readonly host_java_runtime_version host_java_tree_sha256
  running_receipt="{\"schemaVersion\":4,\"sourceHead\":\"$source_head\",\"sourceTree\":\"$source_tree\",\"sourceState\":\"CLEAN\",\"runnerSha256\":\"$runner_sha256\",\"runId\":\"$run_id\",\"jdkProfileId\":\"$host_java_profile_id\",\"jdkRuntimeVersion\":\"$host_java_runtime_version\",\"jdkTreeSha256\":\"$host_java_tree_sha256\",\"gradleAttestationAutoSha256\":\"$auto_attestation_sha256\",\"gradleAttestationQwySha256\":\"$qwy_attestation_sha256\",\"gradleAttestationHarnessSha256\":\"$harness_attestation_sha256\",\"hostIntegration\":\"RUNNING\",\"issue66Ac7\":\"NOT_PASSED\",\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\",\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\",\"reason\":\"HOST_GATE_RUNNING_NO_PASS_RECEIPT\"}"
  lock_releasable=0
  if ! active_receipt_identity="$(write_receipt_atomically "$running_receipt")" ||
    [[ ! "$active_receipt_identity" =~ ^[0-9]+(:[0-9]+){6}$ ]]; then
    echo "Host integration gate could not bind its RUNNING receipt identity." >&2
    exit 1
  fi
  active_receipt="$running_receipt"
  lock_releasable=1
fi

for pinned_wrapper in "$auto_wrapper" "$qwy_wrapper"; do
  if [[ ! -x "$pinned_wrapper" ]]; then
    echo "Pinned repository Gradle wrapper is unavailable: $pinned_wrapper" >&2
    exit 1
  fi
done
if [[ -z "$host_java_home" ]]; then
  if [[ -z "$requested_java_home" ]]; then
    echo "JAVA_HOME must point to a reviewed JDK 17 runtime." >&2
    exit 1
  fi
  if ! host_java_binding="$(emit_java_runtime_binding "$requested_java_home")" ||
    ! host_java_home="$(read_java_binding_field "$host_java_binding" javaHome)" ||
    ! host_java_profile_id="$(read_java_binding_field "$host_java_binding" profileId)" ||
    ! host_java_vendor="$(read_java_binding_field "$host_java_binding" javaVendor)" ||
    ! host_java_vm_vendor="$(read_java_binding_field "$host_java_binding" javaVmVendor)" ||
    ! host_java_runtime_version="$(
      read_java_binding_field "$host_java_binding" javaRuntimeVersion
    )" ||
    ! host_java_tree_sha256="$(read_java_binding_field "$host_java_binding" jdkTreeSha256)" ||
    ! verify_java_runtime_binding; then
    printf '%s\n' 'HOST_GATE_JAVA_RUNTIME_INVALID' >&2
    exit 1
  fi
fi
readonly host_java_home host_java_binding
readonly host_java_profile_id host_java_vendor host_java_vm_vendor
readonly host_java_runtime_version host_java_tree_sha256
case "$host_java_profile_id" in
  darwin-aarch64-eclipse-temurin-17.0.20.1+1)
    host_java_darwin_temurin_profile_home="$host_java_home"
    ;;
  linux-x86_64-eclipse-temurin-17.0.20.1+1)
    host_java_temurin_profile_home="$host_java_home"
    ;;
  *)
    printf '%s\n' 'HOST_GATE_JAVA_PROFILE_ENVIRONMENT_INVALID' >&2
    exit 1
    ;;
esac
readonly host_java_darwin_temurin_profile_home host_java_temurin_profile_home
if [[ -z "$requested_android_home" ]]; then
  echo "ANDROID_HOME must point to the Android SDK." >&2
  exit 1
fi
if ! host_android_binding="$(validate_android_sdk_root "$requested_android_home")" ||
  [[ -z "$host_android_binding" ]] ||
  ! verify_android_sdk_binding; then
  printf '%s\n' 'HOST_GATE_ANDROID_SDK_INVALID' >&2
  exit 1
fi
readonly host_android_home host_android_binding
for local_sdk_override in \
  "$repo_root/local.properties" \
  "$repo_root/apps/cellrebel-auto/local.properties" \
  "$repo_root/apps/qianwangyou/local.properties" \
  "$script_dir/local.properties"; do
  if [[ -e "$local_sdk_override" || -L "$local_sdk_override" ]]; then
    printf 'HOST_GATE_LOCAL_SDK_OVERRIDE_PRESENT: %s\n' "$local_sdk_override" >&2
    exit 1
  fi
done
unset local_sdk_override

if [[ "$#" -eq 0 ]]; then
  if ! run_standalone_runtime_security_tests; then
    printf '%s\n' 'HOST_GATE_STANDALONE_RUNTIME_SECURITY_TESTS_FAILED' >&2
    exit 1
  fi
  run_clean_host_command /bin/bash -p "$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh"
  run_clean_host_command /bin/bash -p "$repo_root/scripts/selftest-issue66-services-compatibility.sh"
  run_attested_gradle_test auto :app:testDebugUnitTest \
    com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest \
    "$auto_wrapper" -p "$repo_root/apps/cellrebel-auto" \
    :app:testDebugUnitTest \
    --tests '*ProviderPrincipalRoutingRedTest' \
    --no-daemon
  auto_attestation_sha256="$host_last_attestation_sha256"
  [[ "$auto_attestation_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 1
  run_attested_gradle_test qwy :app:testDebugUnitTest \
    name.caiyao.fakegps.hook.oracle.Android15OracleHookPlanTest,name.caiyao.fakegps.hook.oracle.SystemServerOracleWiringGuardTest,name.caiyao.fakegps.integration.v1.AuthoritativeOracleProductionGuardTest,name.caiyao.fakegps.integration.v1.BinderAuthoritativeContinuitySourceTest,name.caiyao.fakegps.oracle.OracleBundleCodecTest,name.caiyao.fakegps.integration.v1.AuthoritativeAdvanceProviderTest \
    "$qwy_wrapper" -p "$repo_root/apps/qianwangyou" \
    :app:testDebugUnitTest \
    --tests '*Android15OracleHookPlanTest' \
    --tests '*SystemServerOracleWiringGuardTest' \
    --tests '*AuthoritativeOracleProductionGuardTest' \
    --tests '*BinderAuthoritativeContinuitySourceTest' \
    --tests '*OracleBundleCodecTest' \
    --tests '*AuthoritativeAdvanceProviderTest' \
    --no-daemon
  qwy_attestation_sha256="$host_last_attestation_sha256"
  [[ "$qwy_attestation_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 1
  run_attested_gradle_test harness :harness:testDebugUnitTest \
    io.github.terryyyc.fakexxx.integration.pr63issue66.HarnessBoundaryGuardTest,io.github.terryyyc.fakexxx.integration.pr63issue66.HostRunnerEnvironmentGuardTest,io.github.terryyyc.fakexxx.integration.pr63issue66.HostReceiptModeGuardTest,io.github.terryyyc.fakexxx.integration.pr63issue66.HostEphemeralCleanupGuardTest \
    "$auto_wrapper" -p "$script_dir" \
    :harness:testDebugUnitTest \
    --no-daemon
  harness_attestation_sha256="$host_last_attestation_sha256"
  [[ "$harness_attestation_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 1
  if ! validate_clean_gradle_user_home "$host_gradle_user_home" ||
    ! remove_ephemeral_gradle_home "$receipt_dir" "$host_gradle_user_home"; then
    printf '%s\n' 'HOST_GATE_EPHEMERAL_GRADLE_HOME_CLEANUP_FAILED' >&2
    exit 1
  fi
  gradle_home_owned=0
  if ! remove_ephemeral_child_home "$receipt_dir" "$host_child_home" ||
    [[ -e "$host_child_home" || -L "$host_child_home" ]]; then
    printf '%s\n' 'HOST_GATE_EPHEMERAL_CHILD_HOME_CLEANUP_FAILED' >&2
    exit 1
  fi
  child_home_owned=0
  if ! verify_java_runtime_binding ||
    ! remove_ephemeral_java_runtime_root "$receipt_dir" "$host_java_stage_root" ||
    [[ -e "$host_java_stage_root" || -L "$host_java_stage_root" ]]; then
    printf '%s\n' 'HOST_GATE_EPHEMERAL_JAVA_RUNTIME_CLEANUP_FAILED' >&2
    exit 1
  fi
  java_stage_owned=0
  lock_releasable=0
  if ! confirmed_source_identity="$(read_source_provenance)" ||
    [[ "$confirmed_source_identity" != "$source_identity" ]]; then
    echo "Host integration gate source HEAD/tree/clean state changed during the run." >&2
    exit 1
  fi
  if ! confirmed_runner_sha256="$(read_runner_sha256 "$runner_path")" ||
    [[ "$confirmed_runner_sha256" != "$runner_sha256" ]]; then
    echo "Host integration gate runner bytes changed during the run." >&2
    exit 1
  fi
  receipt="{\"schemaVersion\":4,\"sourceHead\":\"$source_head\",\"sourceTree\":\"$source_tree\",\"sourceState\":\"CLEAN\",\"runnerSha256\":\"$runner_sha256\",\"runId\":\"$run_id\",\"jdkProfileId\":\"$host_java_profile_id\",\"jdkRuntimeVersion\":\"$host_java_runtime_version\",\"jdkTreeSha256\":\"$host_java_tree_sha256\",\"gradleAttestationAutoSha256\":\"$auto_attestation_sha256\",\"gradleAttestationQwySha256\":\"$qwy_attestation_sha256\",\"gradleAttestationHarnessSha256\":\"$harness_attestation_sha256\",\"hostIntegration\":\"PASS\",\"issue66Ac7\":\"NOT_PASSED\",\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\",\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\",\"reason\":\"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION\"}"
  if ! pass_receipt_identity="$(write_receipt_atomically "$receipt")" ||
    [[ ! "$pass_receipt_identity" =~ ^[0-9]+(:[0-9]+){6}$ ]]; then
    echo "Host integration gate could not bind its PASS receipt identity." >&2
    exit 1
  fi
  if ! published_source_identity="$(read_source_provenance)" ||
    [[ "$published_source_identity" != "$source_identity" ]]; then
    echo "Host integration gate source changed across PASS publication." >&2
    exit 1
  fi
  if ! published_runner_sha256="$(read_runner_sha256 "$runner_path")" ||
    [[ "$published_runner_sha256" != "$runner_sha256" ]]; then
    echo "Host integration gate runner changed across PASS publication." >&2
    exit 1
  fi
  active_receipt="$receipt"
  active_receipt_identity="$pass_receipt_identity"
  if ! release_host_gate_lock; then
    printf 'Host integration gate retained an ambiguous owner lock: %s\n' "$lock_dir" >&2
    exit 1
  fi
  lock_owned=0
  echo "HOST integration gate: PASS"
  echo "PHYSICAL DEVICE: NOT_RUN (this host gate emits no device evidence)"
  echo "DEVICE/FULL evidence: BLOCKED (both exact-build admission lists stay empty)"
  echo "OVERALL: BLOCKED pending additional authorization for activation/cleanup reboots and adversarial mutations"
  printf '%s\n' "$receipt"
  exit 0
fi

run_direct_gradle_command() {
  local command_status=0
  if ! verify_java_runtime_binding; then
    printf '%s\n' 'HOST_GATE_JAVA_RUNTIME_CHANGED' >&2
    return 1
  fi
  if ! verify_android_sdk_binding; then
    printf '%s\n' 'HOST_GATE_ANDROID_SDK_CHANGED' >&2
    return 1
  fi
  ADB=/usr/bin/false \
    ANDROID_HOME="$host_android_home" \
    ANDROID_SDK_ROOT="$host_android_home" \
    JAVA_HOME="$host_java_home" \
    "$auto_wrapper" -p "$script_dir" "$@" || command_status=$?
  if ! verify_java_runtime_binding; then
    printf '%s\n' 'HOST_GATE_JAVA_RUNTIME_CHANGED' >&2
    return 1
  fi
  if ! verify_android_sdk_binding; then
    printf '%s\n' 'HOST_GATE_ANDROID_SDK_CHANGED' >&2
    return 1
  fi
  return "$command_status"
}

run_direct_gradle_command "$@"

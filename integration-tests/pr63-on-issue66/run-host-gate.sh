#!/bin/bash
set -euo pipefail
umask 077
PATH=/usr/bin:/bin
export PATH

script_dir="$(cd "$(dirname "$0")" && pwd -P)"
repo_root="$(cd "$script_dir/../.." && pwd -P)"
auto_wrapper="$repo_root/apps/cellrebel-auto/gradlew"
qwy_wrapper="$repo_root/apps/qianwangyou/gradlew"

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
  local source_top source_head source_tree source_status
  local confirmed_top confirmed_head confirmed_tree confirmed_status
  [[ -x /usr/bin/env && -x /usr/bin/git && -x /usr/bin/python3 ]] || return 1
  git_isolated() {
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
        -C "$repo_root" "$@"
  }
  git_index_is_plain() {
    git_isolated ls-files -v -z --cached |
      /usr/bin/python3 -I -c '
import sys

raw = sys.stdin.buffer.read()
if not raw or not raw.endswith(b"\0"):
    raise SystemExit(1)
records = raw[:-1].split(b"\0")
if any(len(record) < 3 or record[:2] != b"H " for record in records):
    raise SystemExit(1)
'
  }
  source_top="$(git_isolated rev-parse --show-toplevel)" || return 1
  source_head="$(git_isolated rev-parse --verify 'HEAD^{commit}')" || return 1
  source_tree="$(git_isolated rev-parse --verify 'HEAD^{tree}')" || return 1
  source_status="$(
    git_isolated status --porcelain=v1 --untracked-files=all --ignore-submodules=none
  )" || return 1
  [[ "$source_top" == "$repo_root" ]] || return 1
  [[ -z "$source_status" ]] || return 1
  git_index_is_plain || return 1
  confirmed_top="$(git_isolated rev-parse --show-toplevel)" || return 1
  confirmed_head="$(git_isolated rev-parse --verify 'HEAD^{commit}')" || return 1
  confirmed_tree="$(git_isolated rev-parse --verify 'HEAD^{tree}')" || return 1
  confirmed_status="$(
    git_isolated status --porcelain=v1 --untracked-files=all --ignore-submodules=none
  )" || return 1
  [[ "$source_head" =~ ^[0-9a-f]{40}$ ]] || return 1
  [[ "$source_tree" =~ ^[0-9a-f]{40}$ ]] || return 1
  [[ "$confirmed_top" == "$repo_root" ]] || return 1
  [[ "$source_head" == "$confirmed_head" && "$source_tree" == "$confirmed_tree" ]] ||
    return 1
  [[ -z "$confirmed_status" ]] || return 1
  git_index_is_plain || return 1
  printf '%s:%s\n' "$source_head" "$source_tree"
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
    os.close(lock_fd)
    lock_fd = None
    os.rmdir(lock_name, dir_fd=parent_fd)
    sync_directory(parent_fd)
finally:
    if receipt_fd is not None:
        os.close(receipt_fd)
    if owner_fd is not None:
        os.close(owner_fd)
    if lock_fd is not None:
        os.close(lock_fd)
    os.close(parent_fd)
PY
}

cleanup_host_gate_lock() {
  if [[ "${lock_owned:-0}" -eq 1 && "${lock_releasable:-0}" -eq 1 ]]; then
    if release_host_gate_lock; then
      lock_owned=0
    else
      printf 'Host integration gate retained an ambiguous owner lock: %s\n' "$lock_dir" >&2
    fi
  fi
}

if [[ "$#" -eq 0 ]]; then
  receipt_relative_dir="harness/build/reports/pr63-on-issue66"
  receipt_dir="$script_dir/$receipt_relative_dir"
  if ! prepare_private_directory "$script_dir" "$receipt_relative_dir"; then
    echo "Host integration gate receipt directory is not private and current-user-owned." >&2
    exit 1
  fi
  receipt_path="$receipt_dir/host-gate-receipt.json"
  lock_dir="$receipt_dir/host-gate.lock"
  lock_owner_path="$lock_dir/owner"
  runner_path="$script_dir/${0##*/}"
  run_id=""
  run_owner=""
  lock_base_identity=""
  lock_identity=""
  active_receipt=""
  active_receipt_identity=""
  lock_owned=0
  lock_releasable=0
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
  trap cleanup_host_gate_lock EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

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
  running_receipt="{\"schemaVersion\":3,\"sourceHead\":\"$source_head\",\"sourceTree\":\"$source_tree\",\"sourceState\":\"CLEAN\",\"runnerSha256\":\"$runner_sha256\",\"runId\":\"$run_id\",\"hostIntegration\":\"RUNNING\",\"issue66Ac7\":\"NOT_PASSED\",\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\",\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\",\"reason\":\"HOST_GATE_RUNNING_NO_PASS_RECEIPT\"}"
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
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME must point to a JDK 17 runtime." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME must point to the Android SDK." >&2
  exit 1
fi

if [[ "$#" -eq 0 ]]; then
  /bin/bash "$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh"
  /bin/bash "$repo_root/scripts/selftest-issue66-services-compatibility.sh"
  "$auto_wrapper" -p "$repo_root/apps/cellrebel-auto" \
    :app:testDebugUnitTest \
    --tests '*ProviderPrincipalRoutingRedTest'
  "$qwy_wrapper" -p "$repo_root/apps/qianwangyou" \
    :app:testDebugUnitTest \
    --tests '*Android15OracleHookPlanTest' \
    --tests '*SystemServerOracleWiringGuardTest' \
    --tests '*AuthoritativeOracleProductionGuardTest' \
    --tests '*BinderAuthoritativeContinuitySourceTest' \
    --tests '*OracleBundleCodecTest' \
    --tests '*AuthoritativeAdvanceProviderTest'
  "$auto_wrapper" -p "$script_dir" :harness:testDebugUnitTest
  echo "HOST integration gate: PASS"
  echo "PHYSICAL DEVICE: NOT_RUN (this host gate emits no device evidence)"
  echo "DEVICE/FULL evidence: BLOCKED (both exact-build admission lists stay empty)"
  echo "OVERALL: BLOCKED pending additional authorization for activation/cleanup reboots and adversarial mutations"
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
  receipt="{\"schemaVersion\":3,\"sourceHead\":\"$source_head\",\"sourceTree\":\"$source_tree\",\"sourceState\":\"CLEAN\",\"runnerSha256\":\"$runner_sha256\",\"runId\":\"$run_id\",\"hostIntegration\":\"PASS\",\"issue66Ac7\":\"NOT_PASSED\",\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\",\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\",\"reason\":\"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION\"}"
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
  lock_releasable=1
  printf '%s\n' "$receipt"
  exit 0
fi

exec "$auto_wrapper" -p "$script_dir" "$@"

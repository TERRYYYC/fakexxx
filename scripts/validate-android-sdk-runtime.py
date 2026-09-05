#!/usr/bin/python3 -I
"""Validate the bounded Android SDK inputs selected by this repository's AGP build."""

import ctypes
import errno
import hashlib
import json
import os
import pathlib
import re
import stat
import struct
import sys


PLATFORM_API = "35"
BUILD_TOOLS_VERSION = "36.0.0"
TREE_ENTRY_LIMIT = 200000
TREE_DEPTH_LIMIT = 64
TREE_FILE_BYTES_LIMIT = 17179869184
TREE_TOTAL_BYTES_LIMIT = 68719476736
PATH_BYTES_LIMIT = 4096
BINDING_BYTES_LIMIT = 8192
AGP_TCB_DIGEST_DOMAIN = b"issue66-android-sdk-agp-tcb-v1\0"

SELECTED_AGP_SUBTREES = (
    (b"platforms", b"android-35"),
    (b"build-tools", b"36.0.0"),
    (b"platform-tools",),
)

REQUIRED_DIRECTORIES = {
    b"platforms",
    b"platforms/android-35",
    b"platforms/android-35/data",
    b"build-tools",
    b"build-tools/36.0.0",
    b"platform-tools",
}
REQUIRED_FILES = {
    b"platforms/android-35/android.jar",
    b"platforms/android-35/framework.aidl",
    b"platforms/android-35/package.xml",
    b"platforms/android-35/source.properties",
    b"platforms/android-35/data/api-versions.xml",
    b"build-tools/36.0.0/package.xml",
    b"build-tools/36.0.0/source.properties",
    b"build-tools/36.0.0/aapt2",
    b"platform-tools/package.xml",
    b"platform-tools/source.properties",
    b"platform-tools/adb",
}
BINDING_KEYS = {
    "schemaVersion",
    "androidSdkRoot",
    "platformApi",
    "buildToolsVersion",
    "agpTcbEntryCount",
    "agpTcbFileBytes",
    "agpTcbStateSha256",
}

DARWIN_ACL_TYPE_EXTENDED = 0x00000100
DARWIN_WRITE_PERMISSIONS = {
    "write",
    "append",
    "writeattr",
    "writeextattr",
    "delete",
    "delete_child",
    "add_file",
    "add_subdirectory",
    "chown",
    "writeowner",
    "writesecurity",
}
POSIX_ACL_XATTR_VERSION = 0x0002
POSIX_ACL_USER_OBJ = 0x01
POSIX_ACL_USER = 0x02
POSIX_ACL_GROUP_OBJ = 0x04
POSIX_ACL_GROUP = 0x08
POSIX_ACL_MASK = 0x10
POSIX_ACL_OTHER = 0x20
POSIX_ACL_WRITE = 0x02


class SdkValidationError(Exception):
    """The candidate SDK or its previously emitted binding is unsafe."""


def fail():
    raise SdkValidationError()


def state_identity(value):
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_nlink,
        value.st_uid,
        value.st_gid,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def require_safe_state(value, expected_kind):
    if expected_kind == "directory":
        if not stat.S_ISDIR(value.st_mode):
            fail()
    elif expected_kind == "file":
        if not stat.S_ISREG(value.st_mode) or value.st_nlink != 1:
            fail()
        if value.st_size < 0 or value.st_size > TREE_FILE_BYTES_LIMIT:
            fail()
    else:
        fail()
    if value.st_uid not in {0, os.geteuid()}:
        fail()
    if stat.S_IMODE(value.st_mode) & 0o022:
        fail()


def darwin_acl_grants_write(raw_acl):
    try:
        text = raw_acl.decode("utf-8")
    except UnicodeDecodeError:
        fail()
    for line in text.splitlines():
        if not line or line == "!#acl 1":
            continue
        fields = line.split(":")
        try:
            allow_index = fields.index("allow")
        except ValueError:
            continue
        permissions = {
            permission
            for field in fields[allow_index + 1 :]
            for permission in field.split(",")
        }
        if permissions & DARWIN_WRITE_PERMISSIONS:
            return True
    return False


def posix_acl_grants_write(raw_acl):
    if len(raw_acl) < 4 or (len(raw_acl) - 4) % 8 != 0:
        fail()
    version = struct.unpack_from("<I", raw_acl, 0)[0]
    if version != POSIX_ACL_XATTR_VERSION:
        fail()
    entries = []
    for offset in range(4, len(raw_acl), 8):
        tag, permissions, qualifier = struct.unpack_from("<HHI", raw_acl, offset)
        if tag not in {
            POSIX_ACL_USER_OBJ,
            POSIX_ACL_USER,
            POSIX_ACL_GROUP_OBJ,
            POSIX_ACL_GROUP,
            POSIX_ACL_MASK,
            POSIX_ACL_OTHER,
        } or permissions & ~0o7:
            fail()
        entries.append((tag, permissions, qualifier))
    if not entries:
        fail()
    masks = [permissions for tag, permissions, _qualifier in entries if tag == POSIX_ACL_MASK]
    if len(masks) > 1:
        fail()
    mask = masks[0] if masks else 0o7
    if any(tag in {POSIX_ACL_USER, POSIX_ACL_GROUP} for tag, _permissions, _id in entries) and not masks:
        fail()
    for tag, permissions, _qualifier in entries:
        if tag == POSIX_ACL_USER_OBJ:
            continue
        effective_permissions = permissions
        if tag in {POSIX_ACL_USER, POSIX_ACL_GROUP_OBJ, POSIX_ACL_GROUP}:
            effective_permissions &= mask
        if effective_permissions & POSIX_ACL_WRITE:
            return True
    return False


class AclInspector:
    def __init__(self):
        self._darwin_libc = None
        if sys.platform == "darwin":
            libc = ctypes.CDLL(None, use_errno=True)
            libc.acl_get_fd_np.argtypes = [ctypes.c_int, ctypes.c_int]
            libc.acl_get_fd_np.restype = ctypes.c_void_p
            libc.acl_to_text.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ssize_t)]
            libc.acl_to_text.restype = ctypes.c_void_p
            libc.acl_free.argtypes = [ctypes.c_void_p]
            libc.acl_free.restype = ctypes.c_int
            self._darwin_libc = libc

    def snapshot(self, descriptor):
        if self._darwin_libc is not None:
            return self._darwin_snapshot(descriptor)
        return self._posix_snapshot(descriptor)

    def _darwin_snapshot(self, descriptor):
        ctypes.set_errno(0)
        acl = self._darwin_libc.acl_get_fd_np(descriptor, DARWIN_ACL_TYPE_EXTENDED)
        if not acl:
            error_number = ctypes.get_errno()
            if error_number == errno.ENOENT:
                return b""
            if error_number in unsupported_error_numbers():
                return b""
            fail()
        text_pointer = None
        try:
            length = ctypes.c_ssize_t()
            ctypes.set_errno(0)
            text_pointer = self._darwin_libc.acl_to_text(acl, ctypes.byref(length))
            if not text_pointer or length.value < 0 or length.value > 65536:
                fail()
            raw_acl = ctypes.string_at(text_pointer, length.value)
            if darwin_acl_grants_write(raw_acl):
                fail()
            return raw_acl
        finally:
            if text_pointer and self._darwin_libc.acl_free(text_pointer) != 0:
                fail()
            if self._darwin_libc.acl_free(acl) != 0:
                fail()

    def _posix_snapshot(self, descriptor):
        payloads = []
        try:
            attribute_names = os.listxattr(descriptor)
        except AttributeError:
            fail()
        except OSError as error:
            if error.errno in unsupported_error_numbers():
                return b""
            fail()
        normalized_names = {os.fsdecode(name) for name in attribute_names}
        for name in ("system.posix_acl_access", "system.posix_acl_default"):
            if name not in normalized_names:
                continue
            try:
                payload = os.getxattr(descriptor, name)
            except OSError:
                fail()
            if len(payload) > 65536 or posix_acl_grants_write(payload):
                fail()
            encoded_name = name.encode("ascii")
            payloads.append(
                len(encoded_name).to_bytes(4, "big")
                + encoded_name
                + len(payload).to_bytes(4, "big")
                + payload
            )
        return b"".join(payloads)


def unsupported_error_numbers():
    return {
        value
        for value in (
            getattr(errno, "ENOTSUP", None),
            getattr(errno, "EOPNOTSUPP", None),
        )
        if value is not None
    }


ACL_INSPECTOR = AclInspector()


def inspect_open_inode(descriptor, expected_kind, named_state=None):
    initial = os.fstat(descriptor)
    require_safe_state(initial, expected_kind)
    if named_state is not None and state_identity(named_state) != state_identity(initial):
        fail()
    acl_snapshot = ACL_INSPECTOR.snapshot(descriptor)
    middle = os.fstat(descriptor)
    require_safe_state(middle, expected_kind)
    if state_identity(middle) != state_identity(initial):
        fail()
    if ACL_INSPECTOR.snapshot(descriptor) != acl_snapshot:
        fail()
    confirmed = os.fstat(descriptor)
    require_safe_state(confirmed, expected_kind)
    if state_identity(confirmed) != state_identity(initial):
        fail()
    return initial, acl_snapshot


def confirm_open_inode(descriptor, expected_kind, initial, initial_acl):
    confirmed = os.fstat(descriptor)
    require_safe_state(confirmed, expected_kind)
    if state_identity(confirmed) != state_identity(initial):
        fail()
    if ACL_INSPECTOR.snapshot(descriptor) != initial_acl:
        fail()
    final = os.fstat(descriptor)
    require_safe_state(final, expected_kind)
    if state_identity(final) != state_identity(initial):
        fail()


def directory_flags():
    if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
        fail()
    result = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    return result | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)


def file_flags():
    if not hasattr(os, "O_NOFOLLOW"):
        fail()
    result = os.O_RDONLY | os.O_NOFOLLOW
    return result | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)


def open_physical_root(raw_root):
    if (
        not isinstance(raw_root, str)
        or not raw_root
        or not os.path.isabs(raw_root)
        or any(character in raw_root for character in ("\x00", "\r", "\n"))
    ):
        fail()
    try:
        encoded_root = os.fsencode(raw_root)
    except UnicodeEncodeError:
        fail()
    if len(encoded_root) > PATH_BYTES_LIMIT:
        fail()
    canonical_root = os.path.normpath(os.path.abspath(raw_root))
    if (
        raw_root != canonical_root
        or not os.path.isabs(canonical_root)
        or os.path.realpath(canonical_root) != canonical_root
    ):
        fail()
    components = pathlib.PurePath(canonical_root).parts[1:]
    if any(
        component in {"", ".", ".."}
        or b"/" in os.fsencode(component)
        or len(os.fsencode(component)) > 255
        for component in components
    ):
        fail()

    flags = directory_flags()
    opened = []
    root_fd = os.open(os.path.sep, flags)
    try:
        named_root = os.stat(os.path.sep, follow_symlinks=False)
        root_state, root_acl = inspect_open_inode(root_fd, "directory", named_root)
        opened.append((None, None, root_fd, root_state, root_acl))
        current_fd = root_fd
        for component in components:
            named_state = os.stat(component, dir_fd=current_fd, follow_symlinks=False)
            child_fd = os.open(component, flags, dir_fd=current_fd)
            try:
                child_state, child_acl = inspect_open_inode(
                    child_fd,
                    "directory",
                    named_state,
                )
            except Exception:
                os.close(child_fd)
                raise
            opened.append((current_fd, component, child_fd, child_state, child_acl))
            current_fd = child_fd
        return canonical_root, opened
    except Exception:
        for _parent, _name, descriptor, _state, _acl in reversed(opened):
            os.close(descriptor)
        if not opened:
            os.close(root_fd)
        raise


def add_digest_record(digest, kind, relative_path, value, acl_snapshot):
    fields = (
        kind,
        relative_path,
        format(stat.S_IMODE(value.st_mode), "04o").encode("ascii"),
        str(value.st_uid).encode("ascii"),
        str(value.st_gid).encode("ascii"),
        str(value.st_dev).encode("ascii"),
        str(value.st_ino).encode("ascii"),
        str(value.st_nlink).encode("ascii"),
        str(value.st_size).encode("ascii"),
        str(value.st_mtime_ns).encode("ascii"),
        str(value.st_ctime_ns).encode("ascii"),
        acl_snapshot,
    )
    for field in fields:
        digest.update(len(field).to_bytes(8, "big"))
        digest.update(field)


def scan_selected_agp_tcb(root_fd):
    digest = hashlib.sha256(AGP_TCB_DIGEST_DOMAIN)
    counters = {"entries": 0, "bytes": 0}
    observed_directories = set()
    observed_files = set()
    recorded_directories = set()
    dir_open_flags = directory_flags()
    regular_open_flags = file_flags()

    def count_entry():
        counters["entries"] += 1
        if counters["entries"] > TREE_ENTRY_LIMIT:
            fail()

    def bounded_sorted_names(directory_fd):
        names = []
        try:
            with os.scandir(directory_fd) as entries:
                for entry in entries:
                    if counters["entries"] >= TREE_ENTRY_LIMIT:
                        fail()
                    counters["entries"] += 1
                    names.append(entry.name)
        except OSError:
            fail()
        names.sort(key=os.fsencode)
        encoded_names = [os.fsencode(name) for name in names]
        if len(encoded_names) != len(set(encoded_names)):
            fail()
        return names, encoded_names

    def record_directory(relative_path, value, acl_snapshot, *, entry_counted=False):
        if relative_path in recorded_directories:
            return
        if not entry_counted:
            count_entry()
        add_digest_record(digest, b"D", relative_path, value, acl_snapshot)
        recorded_directories.add(relative_path)
        if relative_path in REQUIRED_DIRECTORIES:
            observed_directories.add(relative_path)

    def visit_directory(directory_fd, relative_parts, depth, *, entry_counted=False):
        if depth > TREE_DEPTH_LIMIT:
            fail()
        initial, initial_acl = inspect_open_inode(directory_fd, "directory")
        relative_path = b"/".join(relative_parts)
        record_directory(
            relative_path,
            initial,
            initial_acl,
            entry_counted=entry_counted,
        )
        names, encoded_names = bounded_sorted_names(directory_fd)

        for name, encoded_name in zip(names, encoded_names):
            if (
                not encoded_name
                or b"/" in encoded_name
                or encoded_name in {b".", b".."}
                or len(encoded_name) > 255
            ):
                fail()
            child_relative_parts = relative_parts + (encoded_name,)
            child_relative_path = b"/".join(child_relative_parts)
            if len(child_relative_path) > PATH_BYTES_LIMIT:
                fail()
            try:
                named_initial = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            except OSError:
                fail()

            if stat.S_ISDIR(named_initial.st_mode):
                try:
                    child_fd = os.open(name, dir_open_flags, dir_fd=directory_fd)
                except OSError:
                    fail()
                try:
                    opened_initial, opened_acl = inspect_open_inode(
                        child_fd,
                        "directory",
                        named_initial,
                    )
                    visit_directory(
                        child_fd,
                        child_relative_parts,
                        depth + 1,
                        entry_counted=True,
                    )
                    named_confirmed = os.stat(
                        name,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                    confirm_open_inode(child_fd, "directory", opened_initial, opened_acl)
                    if state_identity(named_confirmed) != state_identity(opened_initial):
                        fail()
                finally:
                    os.close(child_fd)
                continue

            if stat.S_ISREG(named_initial.st_mode):
                try:
                    child_fd = os.open(name, regular_open_flags, dir_fd=directory_fd)
                except OSError:
                    fail()
                try:
                    opened_initial, opened_acl = inspect_open_inode(
                        child_fd,
                        "file",
                        named_initial,
                    )
                    named_confirmed = os.stat(
                        name,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                    confirm_open_inode(child_fd, "file", opened_initial, opened_acl)
                    if state_identity(named_confirmed) != state_identity(opened_initial):
                        fail()
                finally:
                    os.close(child_fd)
                counters["bytes"] += opened_initial.st_size
                if counters["bytes"] > TREE_TOTAL_BYTES_LIMIT:
                    fail()
                add_digest_record(
                    digest,
                    b"F",
                    child_relative_path,
                    opened_initial,
                    opened_acl,
                )
                if child_relative_path in REQUIRED_FILES:
                    observed_files.add(child_relative_path)
                continue

            # Symlinks, sockets, devices, FIFOs and unknown future types are excluded.
            fail()

        confirm_open_inode(directory_fd, "directory", initial, initial_acl)

    root_state, root_acl = inspect_open_inode(root_fd, "directory")
    record_directory(b"", root_state, root_acl)
    for subtree_parts in SELECTED_AGP_SUBTREES:
        opened = []
        current_fd = root_fd
        relative_parts = ()
        try:
            for index, component in enumerate(subtree_parts):
                relative_parts += (component,)
                named_initial = os.stat(
                    component,
                    dir_fd=current_fd,
                    follow_symlinks=False,
                )
                child_fd = os.open(component, dir_open_flags, dir_fd=current_fd)
                try:
                    opened_initial, opened_acl = inspect_open_inode(
                        child_fd,
                        "directory",
                        named_initial,
                    )
                except Exception:
                    os.close(child_fd)
                    raise
                opened.append(
                    (current_fd, component, child_fd, opened_initial, opened_acl)
                )
                current_fd = child_fd
                if index < len(subtree_parts) - 1:
                    record_directory(
                        b"/".join(relative_parts),
                        opened_initial,
                        opened_acl,
                    )
            visit_directory(current_fd, relative_parts, len(relative_parts))
            for parent_fd, name, descriptor, initial, initial_acl in reversed(opened):
                confirm_open_inode(descriptor, "directory", initial, initial_acl)
                named_confirmed = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
                if state_identity(named_confirmed) != state_identity(initial):
                    fail()
        finally:
            for _parent, _name, descriptor, _state, _acl in reversed(opened):
                os.close(descriptor)
    confirm_open_inode(root_fd, "directory", root_state, root_acl)
    if observed_directories != REQUIRED_DIRECTORIES or observed_files != REQUIRED_FILES:
        fail()
    return counters["entries"], counters["bytes"], digest.hexdigest()


def validate_sdk(raw_root):
    canonical_root, opened_chain = open_physical_root(raw_root)
    try:
        root_fd = opened_chain[-1][2]
        entry_count, file_bytes, agp_tcb_digest = scan_selected_agp_tcb(root_fd)
        for parent_fd, name, descriptor, initial, initial_acl in reversed(opened_chain):
            confirm_open_inode(descriptor, "directory", initial, initial_acl)
            if parent_fd is not None:
                named_confirmed = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
                if state_identity(named_confirmed) != state_identity(initial):
                    fail()
    finally:
        for _parent, _name, descriptor, _state, _acl in reversed(opened_chain):
            os.close(descriptor)
    return {
        "schemaVersion": 1,
        "androidSdkRoot": canonical_root,
        "platformApi": PLATFORM_API,
        "buildToolsVersion": BUILD_TOOLS_VERSION,
        "agpTcbEntryCount": entry_count,
        "agpTcbFileBytes": file_bytes,
        # This binds selected AGP-input metadata and stability, not SDK content provenance.
        "agpTcbStateSha256": agp_tcb_digest,
    }


def reject_duplicate_json_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail()
        result[key] = value
    return result


def encode_binding(binding):
    return json.dumps(binding, ensure_ascii=True, separators=(",", ":"), sort_keys=True)


def parse_expected_binding(raw_binding):
    try:
        encoded_binding = raw_binding.encode("utf-8")
    except UnicodeEncodeError:
        fail()
    if not raw_binding or len(encoded_binding) > BINDING_BYTES_LIMIT:
        fail()
    try:
        binding = json.loads(raw_binding, object_pairs_hook=reject_duplicate_json_keys)
    except json.JSONDecodeError:
        fail()
    if not isinstance(binding, dict) or set(binding) != BINDING_KEYS:
        fail()
    if type(binding["schemaVersion"]) is not int or binding["schemaVersion"] != 1:
        fail()
    if binding["platformApi"] != PLATFORM_API:
        fail()
    if binding["buildToolsVersion"] != BUILD_TOOLS_VERSION:
        fail()
    for name in ("androidSdkRoot", "platformApi", "buildToolsVersion", "agpTcbStateSha256"):
        if not isinstance(binding[name], str) or not binding[name]:
            fail()
    if not re.fullmatch(r"[0-9a-f]{64}", binding["agpTcbStateSha256"]):
        fail()
    if (
        type(binding["agpTcbEntryCount"]) is not int
        or binding["agpTcbEntryCount"] < len(REQUIRED_DIRECTORIES) + len(REQUIRED_FILES) + 1
        or binding["agpTcbEntryCount"] > TREE_ENTRY_LIMIT
        or type(binding["agpTcbFileBytes"]) is not int
        or binding["agpTcbFileBytes"] < 0
        or binding["agpTcbFileBytes"] > TREE_TOTAL_BYTES_LIMIT
    ):
        fail()
    if encode_binding(binding) != raw_binding:
        fail()
    return binding


def main():
    mode = "legacy"
    expected_binding = None
    if len(sys.argv) == 2:
        raw_root = sys.argv[1]
    elif len(sys.argv) == 3 and sys.argv[1] == "--emit-binding":
        mode = "emit"
        raw_root = sys.argv[2]
    elif len(sys.argv) == 4 and sys.argv[1] == "--verify-binding":
        mode = "verify"
        raw_root = sys.argv[2]
        expected_binding = parse_expected_binding(sys.argv[3])
    else:
        fail()
    binding = validate_sdk(raw_root)
    if expected_binding is not None and binding != expected_binding:
        fail()
    if mode == "legacy":
        print(binding["androidSdkRoot"])
    else:
        print(encode_binding(binding))


if __name__ == "__main__":
    try:
        main()
    except (SdkValidationError, OSError, ValueError, OverflowError, UnicodeError):
        raise SystemExit(1)

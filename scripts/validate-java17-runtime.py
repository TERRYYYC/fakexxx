#!/usr/bin/python3 -I
"""Fail-closed Java 17 runtime/profile probe for repository host gates."""

import hashlib
import json
import os
import pathlib
import platform
import re
import secrets
import selectors
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import time


OUTPUT_LIMIT = 65536
PROCESS_TIMEOUT_SECONDS = 20.0
PROFILE_BYTES_LIMIT = 65536
TREE_ENTRY_LIMIT = 10000
TREE_DEPTH_LIMIT = 64
TREE_FILE_BYTES_LIMIT = 536870912
TREE_TOTAL_BYTES_LIMIT = 1073741824
TREE_DIGEST_DOMAIN = b"issue66-jdk-tree-v1\0"
SYMLINK_DIGEST_MODE = 0o777
MACHO_LOAD_COMMAND_COUNT_LIMIT = 4096
MACHO_LOAD_COMMAND_BYTES_LIMIT = 4194304
MACHO_PATH_BYTES_LIMIT = 4096
# Keep this census explicit. A new load command must be classified before a
# runtime containing it can become trusted; otherwise a future pathname-bearing
# command could silently bypass the loader-closure audit.
KNOWN_MACHO_LOAD_COMMANDS = frozenset({
    0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x9, 0xA, 0xB,
    0xC, 0xD, 0xE, 0xF, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
    0x16, 0x17, 0x80000018, 0x19, 0x1A, 0x1B, 0x8000001C,
    0x1D, 0x1E, 0x8000001F, 0x20, 0x21, 0x22, 0x80000022,
    0x80000023, 0x24, 0x25, 0x26, 0x27, 0x80000028, 0x29,
    0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32,
    0x80000033, 0x80000034, 0x80000035, 0x36, 0x37, 0x38,
    0x39, 0x3A,
})
MACHO_DYLIB_LOAD_COMMANDS = frozenset({
    0xC,  # LC_LOAD_DYLIB
    0x80000018,  # LC_LOAD_WEAK_DYLIB
    0x8000001F,  # LC_REEXPORT_DYLIB
    0x20,  # LC_LAZY_LOAD_DYLIB
    0x80000023,  # LC_LOAD_UPWARD_DYLIB
})
UNSUPPORTED_MACHO_PATHNAME_COMMANDS = frozenset({
    0x6,  # LC_LOADFVMLIB
    0x7,  # LC_IDFVMLIB
    0x9,  # LC_FVMFILE
    0xF,  # LC_ID_DYLINKER
    0x10,  # LC_PREBOUND_DYLIB
})
PROFILE_PATH = pathlib.Path(__file__).resolve().parent / "fixtures" / "issue66-java17-runtime-profiles.json"
EXPECTED_PROFILE_TREE_SHA256 = {
    "darwin-aarch64-eclipse-temurin-17.0.20.1+1":
        "f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8",
    "linux-x86_64-eclipse-temurin-17.0.20.1+1":
        "427182064043c17bb698c7f9c5949f755f6dd80dddaf760b6fa7413178189a97",
}
PROFILE_KEYS = {
    "profileId",
    "os",
    "arch",
    "javaMajor",
    "javaVendor",
    "javaVmVendor",
    "javaRuntimeVersion",
    "jdkTreeSha256",
}
BINDING_KEYS = {
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


class RuntimeValidationError(Exception):
    """A candidate runtime or binding failed closed."""


def fail():
    raise RuntimeValidationError()


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


def require_safe_path(path, *, directory):
    try:
        value = os.stat(path, follow_symlinks=False)
    except OSError:
        fail()
    if directory and not stat.S_ISDIR(value.st_mode):
        fail()
    if not directory and not stat.S_ISREG(value.st_mode):
        fail()
    mode = stat.S_IMODE(value.st_mode)
    if value.st_uid not in {0, os.geteuid()}:
        fail()
    # A user-owned ancestor may be group-writable; the runtime tree itself is
    # still mode-checked, content-addressed and loader-closure checked before
    # any candidate executable runs. Root-owned group-write and every
    # world-write remain untrusted path authority.
    if mode & 0o002 or (value.st_uid == 0 and mode & 0o020):
        fail()
    return value


def require_safe_tree_entry(value, *, allow_symlink_mode=False):
    if value.st_uid not in {0, os.geteuid()}:
        fail()
    if not allow_symlink_mode and stat.S_IMODE(value.st_mode) & 0o022:
        fail()


def add_digest_record(digest, kind, relative_path, mode, payload):
    fields = (
        kind,
        relative_path,
        format(mode, "04o").encode("ascii"),
        payload,
    )
    for field in fields:
        digest.update(len(field).to_bytes(8, "big"))
        digest.update(field)


def pread_exact(descriptor, size, offset):
    if size < 0 or offset < 0 or not hasattr(os, "pread"):
        fail()
    payload = bytearray()
    while len(payload) < size:
        try:
            chunk = os.pread(descriptor, min(65536, size - len(payload)), offset + len(payload))
        except OSError:
            fail()
        if not chunk:
            fail()
        payload.extend(chunk)
    return bytes(payload)


def macho_command_path(command, endian, fixed_size):
    if len(command) < fixed_size:
        fail()
    path_offset = struct.unpack_from(endian + "I", command, 8)[0]
    if path_offset < fixed_size or path_offset >= len(command):
        fail()
    raw = command[path_offset:]
    terminator = raw.find(b"\0")
    if terminator <= 0 or any(raw[terminator + 1:]):
        fail()
    path = raw[:terminator]
    if (
        len(path) > MACHO_PATH_BYTES_LIMIT
        or any(value < 0x20 or value == 0x7F for value in path)
    ):
        fail()
    return path


def require_internal_macho_path(home, candidate, *, directory):
    try:
        normalized = os.path.normpath(candidate)
        if not os.path.isabs(normalized) or os.path.commonpath((home, normalized)) != home:
            fail()
        resolved = os.path.realpath(normalized)
        if os.path.commonpath((home, resolved)) != home:
            fail()
        value = os.stat(normalized)
    except (OSError, ValueError):
        fail()
    if directory and not stat.S_ISDIR(value.st_mode):
        fail()
    if not directory and not stat.S_ISREG(value.st_mode):
        fail()
    return normalized


def resolve_loader_path(home, loader_directory, value, *, directory):
    if value == b"@loader_path":
        suffix = b""
    elif value.startswith(b"@loader_path/"):
        suffix = value[len(b"@loader_path/"):]
    else:
        fail()
    return require_internal_macho_path(
        home,
        os.path.join(loader_directory, suffix),
        directory=directory,
    )


def is_approved_macos_system_path(value):
    return (
        os.path.isabs(value)
        and os.path.normpath(value) == value
        and value.startswith((b"/usr/lib/", b"/System/Library/"))
    )


def audit_macho_dependencies(descriptor, file_size, home, relative_path):
    """Reject Mach-O loader paths that can escape the reviewed JDK tree."""
    if file_size < 4:
        return None
    magic = pread_exact(descriptor, 4, 0)
    thin_magics = {
        b"\xce\xfa\xed\xfe": ("<", 28),
        b"\xfe\xed\xfa\xce": (">", 28),
        b"\xcf\xfa\xed\xfe": ("<", 32),
        b"\xfe\xed\xfa\xcf": (">", 32),
    }
    fat_magics = {
        b"\xca\xfe\xba\xbe",
        b"\xbe\xba\xfe\xca",
        b"\xca\xfe\xba\xbf",
        b"\xbf\xba\xfe\xca",
    }
    if magic in fat_magics:
        # No reviewed Issue 66 runtime is universal. Parsing only one slice
        # would leave the other architecture's loader graph unbound.
        fail()
    if magic not in thin_magics:
        return None
    endian, header_size = thin_magics[magic]
    if file_size < header_size:
        fail()
    header = pread_exact(descriptor, header_size, 0)
    command_count, command_bytes = struct.unpack_from(endian + "II", header, 16)
    if (
        command_count > MACHO_LOAD_COMMAND_COUNT_LIMIT
        or command_bytes > MACHO_LOAD_COMMAND_BYTES_LIMIT
        or header_size + command_bytes > file_size
    ):
        fail()
    table = pread_exact(descriptor, command_bytes, header_size) if command_bytes else b""
    commands = []
    offset = 0
    for _ in range(command_count):
        if offset + 8 > len(table):
            fail()
        command_id, command_size = struct.unpack_from(endian + "II", table, offset)
        if command_size < 8 or command_size % 4 or offset + command_size > len(table):
            fail()
        commands.append((command_id, table[offset:offset + command_size]))
        offset += command_size
    if offset != len(table):
        fail()

    home = os.fsencode(home)
    loader_path = os.path.join(home, relative_path)
    loader_directory = os.path.dirname(loader_path)
    unresolved_rpath_dependencies = []
    for command_id, command in commands:
        if command_id not in KNOWN_MACHO_LOAD_COMMANDS:
            fail()
        if command_id in UNSUPPORTED_MACHO_PATHNAME_COMMANDS:
            # These obsolete/internal commands can name executable inputs but
            # are not part of the reviewed Temurin loader model. Refuse the
            # image rather than guessing at legacy dyld semantics.
            fail()
        if command_id == 0x8000001C:  # LC_RPATH
            rpath = macho_command_path(command, endian, 12)
            resolve_loader_path(home, loader_directory, rpath, directory=True)
        elif command_id == 0x27:  # LC_DYLD_ENVIRONMENT
            fail()

    for command_id, command in commands:
        if command_id in MACHO_DYLIB_LOAD_COMMANDS:
            dependency = macho_command_path(command, endian, 24)
            if is_approved_macos_system_path(dependency):
                continue
            if dependency.startswith(b"@loader_path/"):
                resolve_loader_path(home, loader_directory, dependency, directory=False)
                continue
            if dependency.startswith(b"@rpath/"):
                suffix = dependency[len(b"@rpath/"):]
                normalized_suffix = os.path.normpath(suffix)
                if (
                    not suffix
                    or os.path.isabs(suffix)
                    or normalized_suffix in {b"", b"."}
                    or normalized_suffix == b".."
                    or normalized_suffix.startswith(b"../")
                ):
                    fail()
                # A dylib can inherit an LC_RPATH from its loader chain. Bind
                # the path syntax here, then resolve it against the complete
                # reviewed Mach-O set once the bounded tree walk is complete.
                unresolved_rpath_dependencies.append(normalized_suffix)
                continue
            fail()
        if command_id == 0xE:  # LC_LOAD_DYLINKER
            dylinker = macho_command_path(command, endian, 12)
            if not is_approved_macos_system_path(dylinker):
                fail()
        if command_id == 0xD:  # LC_ID_DYLIB is an identity, not a load edge.
            identity_path = macho_command_path(command, endian, 24)
            if not (
                identity_path.startswith((b"@rpath/", b"@loader_path/"))
                or is_approved_macos_system_path(identity_path)
            ):
                fail()
    return unresolved_rpath_dependencies


def compute_jdk_tree_digest(home):
    """Return a deterministic, no-follow digest of one bounded JDK tree."""
    try:
        home = pathlib.Path(home).resolve(strict=True)
    except OSError:
        fail()
    if not home.is_absolute() or not home.is_dir():
        fail()
    home_bytes = os.fsencode(home)
    if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
        fail()
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
    file_flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
    file_flags |= getattr(os, "O_NONBLOCK", 0)
    try:
        root_fd = os.open(home, directory_flags)
    except OSError:
        fail()

    digest = hashlib.sha256(TREE_DIGEST_DOMAIN)
    counters = {"entries": 0, "bytes": 0}
    link_paths = []
    macho_paths = set()
    macho_rpath_dependencies = []

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
        if len(names) != len(set(os.fsencode(name) for name in names)):
            fail()
        return names

    def visit_directory(directory_fd, relative_parts, depth):
        if depth > TREE_DEPTH_LIMIT:
            fail()
        initial = os.fstat(directory_fd)
        if not stat.S_ISDIR(initial.st_mode):
            fail()
        require_safe_tree_entry(initial)
        relative_path = b"/".join(relative_parts)
        add_digest_record(
            digest,
            b"D",
            relative_path,
            stat.S_IMODE(initial.st_mode),
            b"",
        )
        names = bounded_sorted_names(directory_fd)

        for name in names:
            encoded_name = os.fsencode(name)
            if not encoded_name or b"/" in encoded_name or encoded_name in {b".", b".."}:
                fail()
            child_relative_parts = relative_parts + (encoded_name,)
            child_relative_path = b"/".join(child_relative_parts)
            try:
                named_initial = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            except OSError:
                fail()

            if stat.S_ISDIR(named_initial.st_mode):
                require_safe_tree_entry(named_initial)
                try:
                    child_fd = os.open(name, directory_flags, dir_fd=directory_fd)
                except OSError:
                    fail()
                try:
                    opened_initial = os.fstat(child_fd)
                    if state_identity(opened_initial) != state_identity(named_initial):
                        fail()
                    visit_directory(child_fd, child_relative_parts, depth + 1)
                    opened_confirmed = os.fstat(child_fd)
                    named_confirmed = os.stat(
                        name,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                    if (
                        state_identity(opened_confirmed) != state_identity(opened_initial)
                        or state_identity(named_confirmed) != state_identity(opened_initial)
                    ):
                        fail()
                finally:
                    os.close(child_fd)
                continue

            if stat.S_ISREG(named_initial.st_mode):
                require_safe_tree_entry(named_initial)
                if named_initial.st_nlink != 1 or named_initial.st_size > TREE_FILE_BYTES_LIMIT:
                    fail()
                try:
                    child_fd = os.open(name, file_flags, dir_fd=directory_fd)
                except OSError:
                    fail()
                try:
                    opened_initial = os.fstat(child_fd)
                    if (
                        not stat.S_ISREG(opened_initial.st_mode)
                        or state_identity(opened_initial) != state_identity(named_initial)
                    ):
                        fail()
                    file_digest = hashlib.sha256()
                    file_bytes = 0
                    while True:
                        chunk = os.read(child_fd, 65536)
                        if not chunk:
                            break
                        file_bytes += len(chunk)
                        counters["bytes"] += len(chunk)
                        if (
                            file_bytes > TREE_FILE_BYTES_LIMIT
                            or counters["bytes"] > TREE_TOTAL_BYTES_LIMIT
                        ):
                            fail()
                        file_digest.update(chunk)
                    opened_confirmed = os.fstat(child_fd)
                    named_confirmed = os.stat(
                        name,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                    if (
                        file_bytes != opened_initial.st_size
                        or state_identity(opened_confirmed) != state_identity(opened_initial)
                        or state_identity(named_confirmed) != state_identity(opened_initial)
                    ):
                        fail()
                    unresolved_macho_dependencies = audit_macho_dependencies(
                        child_fd,
                        file_bytes,
                        home_bytes,
                        child_relative_path,
                    )
                    if unresolved_macho_dependencies is not None:
                        macho_paths.add(child_relative_path)
                        macho_rpath_dependencies.extend(unresolved_macho_dependencies)
                    opened_audited = os.fstat(child_fd)
                    named_audited = os.stat(
                        name,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                    if (
                        state_identity(opened_audited) != state_identity(opened_initial)
                        or state_identity(named_audited) != state_identity(opened_initial)
                    ):
                        fail()
                finally:
                    os.close(child_fd)
                add_digest_record(
                    digest,
                    b"F",
                    child_relative_path,
                    stat.S_IMODE(named_initial.st_mode),
                    file_bytes.to_bytes(8, "big") + file_digest.digest(),
                )
                continue

            if stat.S_ISLNK(named_initial.st_mode):
                require_safe_tree_entry(named_initial, allow_symlink_mode=True)
                try:
                    target = os.readlink(name, dir_fd=directory_fd)
                    named_confirmed = os.stat(
                        name,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                except OSError:
                    fail()
                if state_identity(named_confirmed) != state_identity(named_initial):
                    fail()
                encoded_target = os.fsencode(target)
                parent_relative = b"/".join(relative_parts)
                if os.path.isabs(encoded_target):
                    fail()
                normalized_target = os.path.normpath(os.path.join(parent_relative, encoded_target))
                if normalized_target == b".." or normalized_target.startswith(b"../"):
                    fail()
                add_digest_record(
                    digest,
                    b"L",
                    child_relative_path,
                    # POSIX does not use symlink permission bits for access
                    # control. Darwin reports archive links as 0755 while
                    # Linux reports the same links as 0777, so normalize only
                    # this non-semantic mode and keep the raw target bound.
                    SYMLINK_DIGEST_MODE,
                    encoded_target,
                )
                link_paths.append(child_relative_path)
                continue

            # Devices, sockets, FIFOs and any future unhandled type are never
            # part of a trusted executable runtime tree.
            fail()

        confirmed = os.fstat(directory_fd)
        if state_identity(confirmed) != state_identity(initial):
            fail()

    try:
        named_root = os.stat(home, follow_symlinks=False)
        opened_root = os.fstat(root_fd)
        if state_identity(named_root) != state_identity(opened_root):
            fail()
        count_entry()
        visit_directory(root_fd, (), 0)
        opened_confirmed = os.fstat(root_fd)
        named_confirmed = os.stat(home, follow_symlinks=False)
        if (
            state_identity(opened_confirmed) != state_identity(opened_root)
            or state_identity(named_confirmed) != state_identity(opened_root)
        ):
            fail()
    finally:
        os.close(root_fd)

    for relative_path in link_paths:
        link_path = os.path.join(home_bytes, relative_path)
        try:
            resolved = os.path.realpath(link_path)
            os.stat(link_path)
            common = os.path.commonpath((home_bytes, resolved))
        except (OSError, ValueError):
            fail()
        if common != home_bytes:
            fail()
    for suffix in macho_rpath_dependencies:
        if not any(
            candidate == suffix or candidate.endswith(b"/" + suffix)
            for candidate in macho_paths
        ):
            fail()
    return digest.hexdigest()


def read_profile_bytes():
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NONBLOCK", 0)
    try:
        descriptor = os.open(PROFILE_PATH, flags)
    except OSError:
        fail()
    try:
        initial = os.fstat(descriptor)
        named_initial = os.stat(PROFILE_PATH, follow_symlinks=False)
        if (
            not stat.S_ISREG(initial.st_mode)
            or initial.st_uid not in {0, os.geteuid()}
            or stat.S_IMODE(initial.st_mode) & 0o022
            or initial.st_nlink != 1
            or initial.st_size < 1
            or initial.st_size > PROFILE_BYTES_LIMIT
            or state_identity(named_initial) != state_identity(initial)
        ):
            fail()
        payload = bytearray()
        while True:
            chunk = os.read(descriptor, min(4096, PROFILE_BYTES_LIMIT + 1 - len(payload)))
            if not chunk:
                break
            payload.extend(chunk)
            if len(payload) > PROFILE_BYTES_LIMIT:
                fail()
        confirmed = os.fstat(descriptor)
        named_confirmed = os.stat(PROFILE_PATH, follow_symlinks=False)
        if (
            len(payload) != initial.st_size
            or state_identity(confirmed) != state_identity(initial)
            or state_identity(named_confirmed) != state_identity(initial)
        ):
            fail()
        return bytes(payload)
    finally:
        os.close(descriptor)


def reject_duplicate_json_keys(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            fail()
        value[key] = item
    return value


def load_profiles():
    try:
        document = json.loads(
            read_profile_bytes().decode("utf-8"),
            object_pairs_hook=reject_duplicate_json_keys,
        )
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail()
    if (
        not isinstance(document, dict)
        or set(document) != {"schemaVersion", "profiles"}
        or document["schemaVersion"] != 1
    ):
        fail()
    profiles = document["profiles"]
    if not isinstance(profiles, list) or not profiles:
        fail()
    seen_ids = set()
    seen_bindings = set()
    for profile_value in profiles:
        if not isinstance(profile_value, dict) or set(profile_value) != PROFILE_KEYS:
            fail()
        profile_id = profile_value["profileId"]
        os_name = profile_value["os"]
        architecture = profile_value["arch"]
        java_major = profile_value["javaMajor"]
        vendor = profile_value["javaVendor"]
        vm_vendor = profile_value["javaVmVendor"]
        runtime_version = profile_value["javaRuntimeVersion"]
        tree_digest = profile_value["jdkTreeSha256"]
        if (
            not isinstance(profile_id, str)
            or not re.fullmatch(r"[a-z0-9][a-z0-9._+-]{0,127}", profile_id)
            or os_name not in {"darwin", "linux"}
            or architecture not in {"aarch64", "x86_64"}
            or type(java_major) is not int
            or java_major != 17
            or not isinstance(vendor, str)
            or not vendor
            or vendor != vendor.strip()
            or not isinstance(vm_vendor, str)
            or not vm_vendor
            or vm_vendor != vm_vendor.strip()
            or not isinstance(runtime_version, str)
            or not re.fullmatch(r"17\.[0-9][0-9A-Za-z.+_-]*", runtime_version)
            or not isinstance(tree_digest, str)
            or not re.fullmatch(r"[0-9a-f]{64}", tree_digest)
        ):
            fail()
        binding_key = (os_name, architecture, tree_digest)
        if profile_id in seen_ids or binding_key in seen_bindings:
            fail()
        seen_ids.add(profile_id)
        seen_bindings.add(binding_key)
    if {
        profile_value["profileId"]: profile_value["jdkTreeSha256"]
        for profile_value in profiles
    } != EXPECTED_PROFILE_TREE_SHA256:
        fail()
    return profiles


def normalized_host_platform():
    os_name = sys.platform
    if os_name.startswith("linux"):
        os_name = "linux"
    elif os_name == "darwin":
        os_name = "darwin"
    else:
        fail()
    architecture = platform.machine().lower()
    if architecture in {"arm64", "aarch64"}:
        architecture = "aarch64"
    elif architecture in {"amd64", "x86_64"}:
        architecture = "x86_64"
    else:
        fail()
    return os_name, architecture


def run_bounded(arguments, *, cwd=None):
    try:
        process = subprocess.Popen(
            arguments,
            cwd=cwd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            env={
                "HOME": "/nonexistent",
                "LANG": "C",
                "LC_ALL": "C",
                "PATH": "/usr/bin:/bin",
            },
            start_new_session=True,
        )
    except OSError:
        fail()
    if process.stdout is None:
        fail()
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    output = bytearray()
    deadline = time.monotonic() + PROCESS_TIMEOUT_SECONDS
    failed = False
    status = 1
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                failed = True
                break
            events = selector.select(min(remaining, 0.25))
            if not events and process.poll() is not None:
                events = [(key, selectors.EVENT_READ) for key in selector.get_map().values()]
            for key, _ in events:
                try:
                    chunk = os.read(key.fd, 4096)
                except OSError:
                    failed = True
                    break
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                output.extend(chunk)
                if len(output) > OUTPUT_LIMIT:
                    failed = True
                    break
            if failed:
                break
        if failed:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        try:
            status = process.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                pass
            failed = True
    finally:
        selector.close()
        process.stdout.close()
    if failed or status != 0:
        fail()
    try:
        return bytes(output).decode("utf-8")
    except UnicodeDecodeError:
        fail()


def parse_required_properties(output):
    properties = {}
    required_names = {
        "issue66.hostGateChallenge",
        "java.home",
        "java.runtime.version",
        "java.specification.version",
        "java.vendor",
        "java.vm.vendor",
        "os.arch",
    }
    for line in output.splitlines():
        match = re.fullmatch(r"\s*([A-Za-z0-9_.-]+)\s*=\s*(.*?)\s*", line)
        if match and match.group(1) in required_names:
            name = match.group(1)
            if name in properties:
                fail()
            properties[name] = match.group(2)
    if set(properties) != required_names:
        fail()
    return properties


def validate_runtime(raw_home):
    if not raw_home or not os.path.isabs(raw_home) or "\x00" in raw_home:
        fail()
    try:
        home = pathlib.Path(raw_home).resolve(strict=True)
    except OSError:
        fail()
    if not home.is_dir():
        fail()

    current = pathlib.Path(home.anchor)
    require_safe_path(current, directory=True)
    for component in home.parts[1:]:
        current = current / component
        require_safe_path(current, directory=True)

    os_name, architecture = normalized_host_platform()
    tree_digest = compute_jdk_tree_digest(home)
    profiles = [
        profile_value
        for profile_value in load_profiles()
        if profile_value["os"] == os_name
        and profile_value["arch"] == architecture
        and profile_value["jdkTreeSha256"] == tree_digest
    ]
    if len(profiles) != 1:
        fail()
    profile_value = profiles[0]

    java = home / "bin" / "java"
    java_stat = require_safe_path(java, directory=False)
    if not java_stat.st_mode & 0o111:
        fail()
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    flags |= getattr(os, "O_CLOEXEC", 0)
    try:
        java_fd = os.open(java, flags)
        try:
            opened = os.fstat(java_fd)
            if state_identity(opened) != state_identity(java_stat):
                fail()
            magic = os.read(java_fd, 4)
            confirmed = os.fstat(java_fd)
            named_confirmed = os.stat(java, follow_symlinks=False)
            if (
                state_identity(confirmed) != state_identity(opened)
                or state_identity(named_confirmed) != state_identity(opened)
            ):
                fail()
        finally:
            os.close(java_fd)
    except OSError:
        fail()
    if magic not in {
        b"\x7fELF",
        bytes.fromhex("cffaedfe"),
        bytes.fromhex("feedfacf"),
        bytes.fromhex("cafebabe"),
        bytes.fromhex("bebafeca"),
    }:
        fail()

    settings_challenge = secrets.token_hex(16)
    settings = run_bounded(
        [
            str(java),
            f"-Dissue66.hostGateChallenge={settings_challenge}",
            "-XshowSettings:properties",
            "-version",
        ]
    )
    properties = parse_required_properties(settings)
    if properties["java.specification.version"] != str(profile_value["javaMajor"]):
        fail()
    if properties["issue66.hostGateChallenge"] != settings_challenge:
        fail()
    try:
        reported_home = pathlib.Path(properties["java.home"]).resolve(strict=True)
    except OSError:
        fail()
    if reported_home != home:
        fail()
    if (
        properties["java.vendor"] != profile_value["javaVendor"]
        or properties["java.vm.vendor"] != profile_value["javaVmVendor"]
        or properties["java.runtime.version"] != profile_value["javaRuntimeVersion"]
    ):
        fail()
    property_architecture = properties["os.arch"].lower()
    if property_architecture in {"arm64", "aarch64"}:
        property_architecture = "aarch64"
    elif property_architecture in {"amd64", "x86_64"}:
        property_architecture = "x86_64"
    else:
        fail()
    if property_architecture != architecture:
        fail()

    source_challenge = secrets.token_hex(16)
    with tempfile.TemporaryDirectory(prefix="issue66-host-java-probe-", dir="/tmp") as directory:
        probe = pathlib.Path(directory) / "Issue66HostJavaProbe.java"
        probe.write_text(
            "public final class Issue66HostJavaProbe {"
            " public static void main(String[] args) {"
            f"  if (Runtime.version().feature() != {profile_value['javaMajor']}) System.exit(71);"
            f'  System.out.print("{source_challenge}");'
            " }"
            "}",
            encoding="utf-8",
        )
        output = run_bounded([str(java), str(probe)], cwd=directory)
    if output != source_challenge:
        fail()

    confirmed_tree_digest = compute_jdk_tree_digest(home)
    if confirmed_tree_digest != tree_digest:
        fail()
    return {
        "schemaVersion": 1,
        "profileId": profile_value["profileId"],
        "javaHome": str(home),
        "os": os_name,
        "arch": architecture,
        "javaMajor": profile_value["javaMajor"],
        "javaVendor": profile_value["javaVendor"],
        "javaVmVendor": profile_value["javaVmVendor"],
        "javaRuntimeVersion": profile_value["javaRuntimeVersion"],
        "jdkTreeSha256": tree_digest,
    }


def encode_binding(binding):
    return json.dumps(binding, ensure_ascii=True, separators=(",", ":"), sort_keys=True)


def parse_expected_binding(raw_binding):
    if not raw_binding or len(raw_binding.encode("utf-8")) > 8192:
        fail()
    try:
        binding = json.loads(raw_binding, object_pairs_hook=reject_duplicate_json_keys)
    except (UnicodeEncodeError, json.JSONDecodeError):
        fail()
    if not isinstance(binding, dict) or set(binding) != BINDING_KEYS:
        fail()
    if type(binding["schemaVersion"]) is not int or binding["schemaVersion"] != 1:
        fail()
    if type(binding["javaMajor"]) is not int or binding["javaMajor"] != 17:
        fail()
    for name in BINDING_KEYS - {"schemaVersion", "javaMajor"}:
        if not isinstance(binding[name], str) or not binding[name]:
            fail()
    if not re.fullmatch(r"[0-9a-f]{64}", binding["jdkTreeSha256"]):
        fail()
    return binding


def main():
    mode = "legacy"
    expected_binding = None
    if len(sys.argv) == 2:
        raw_home = sys.argv[1]
    elif len(sys.argv) == 3 and sys.argv[1] == "--emit-binding":
        mode = "emit"
        raw_home = sys.argv[2]
    elif len(sys.argv) == 4 and sys.argv[1] == "--verify-binding":
        mode = "verify"
        raw_home = sys.argv[2]
        expected_binding = parse_expected_binding(sys.argv[3])
    else:
        fail()
    binding = validate_runtime(raw_home)
    if expected_binding is not None and binding != expected_binding:
        fail()
    if mode == "legacy":
        print(binding["javaHome"])
    else:
        print(encode_binding(binding))


if __name__ == "__main__":
    try:
        main()
    except RuntimeValidationError:
        raise SystemExit(1)

#!/usr/bin/python3 -I
"""Copy one reviewed Java 17 runtime into a private per-run execution root."""

import json
import os
import pathlib
import re
import stat
import subprocess
import sys


VALIDATOR = pathlib.Path(__file__).resolve().with_name("validate-java17-runtime.py")
ENTRY_LIMIT = 10000
DEPTH_LIMIT = 64
FILE_BYTES_LIMIT = 536870912
TOTAL_BYTES_LIMIT = 1073741824
OUTPUT_LIMIT = 16384


class StageError(Exception):
    """The requested runtime could not be copied or removed safely."""


def fail():
    raise StageError()


def identity(value):
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


def inode_identity(value):
    return value.st_dev, value.st_ino


def require_private_parent(parent):
    try:
        parent = pathlib.Path(parent)
        if not parent.is_absolute() or parent.resolve(strict=True) != parent:
            fail()
        named = os.stat(parent, follow_symlinks=False)
    except OSError:
        fail()
    if (
        not stat.S_ISDIR(named.st_mode)
        or named.st_uid != os.geteuid()
        or stat.S_IMODE(named.st_mode) != 0o700
    ):
        fail()
    return parent


def stage_runtime_tree(source, stage_root):
    """Descriptor-copy a bounded runtime tree into one known empty 0700 stage root."""
    source = pathlib.Path(source)
    stage_root = require_private_parent(stage_root)
    if re.fullmatch(r"jdk-runtime\.[0-9a-f]{32}", stage_root.name) is None:
        fail()
    try:
        source = source.resolve(strict=True)
        source_root_state = os.stat(source, follow_symlinks=False)
    except OSError:
        fail()
    if not source.is_absolute() or not stat.S_ISDIR(source_root_state.st_mode):
        fail()
    if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
        fail()

    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
    source_file_flags = os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_CLOEXEC", 0)
    source_file_flags |= getattr(os, "O_NONBLOCK", 0)
    destination_file_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    destination_file_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)

    stage_fd = os.open(stage_root, directory_flags)
    counters = {"entries": 0, "bytes": 0}

    def count_entry():
        counters["entries"] += 1
        if counters["entries"] > ENTRY_LIMIT:
            fail()

    def bounded_sorted_names(directory_fd, entry_counters, ceiling):
        names = []
        try:
            with os.scandir(directory_fd) as entries:
                for entry in entries:
                    if entry_counters["entries"] >= ceiling:
                        fail()
                    entry_counters["entries"] += 1
                    names.append(entry.name)
        except OSError:
            fail()
        names.sort(key=os.fsencode)
        if len(names) != len(set(os.fsencode(name) for name in names)):
            fail()
        return names

    def copy_directory(source_fd, destination_fd, depth):
        if depth > DEPTH_LIMIT:
            fail()
        source_initial = os.fstat(source_fd)
        destination_initial = os.fstat(destination_fd)
        if not stat.S_ISDIR(source_initial.st_mode) or not stat.S_ISDIR(destination_initial.st_mode):
            fail()
        names = bounded_sorted_names(source_fd, counters, ENTRY_LIMIT)
        for name in names:
            encoded = os.fsencode(name)
            if not encoded or b"/" in encoded or encoded in {b".", b".."}:
                fail()
            named_initial = os.stat(name, dir_fd=source_fd, follow_symlinks=False)
            mode = stat.S_IMODE(named_initial.st_mode)

            if stat.S_ISDIR(named_initial.st_mode):
                os.mkdir(name, mode=0o700, dir_fd=destination_fd)
                child_source_fd = os.open(name, directory_flags, dir_fd=source_fd)
                child_destination_fd = os.open(name, directory_flags, dir_fd=destination_fd)
                try:
                    if identity(os.fstat(child_source_fd)) != identity(named_initial):
                        fail()
                    copy_directory(child_source_fd, child_destination_fd, depth + 1)
                    os.fchmod(child_destination_fd, mode)
                    if identity(os.fstat(child_source_fd)) != identity(named_initial):
                        fail()
                finally:
                    os.close(child_destination_fd)
                    os.close(child_source_fd)
                continue

            if stat.S_ISREG(named_initial.st_mode):
                if named_initial.st_nlink != 1 or named_initial.st_size > FILE_BYTES_LIMIT:
                    fail()
                source_child_fd = os.open(name, source_file_flags, dir_fd=source_fd)
                destination_child_fd = None
                try:
                    if identity(os.fstat(source_child_fd)) != identity(named_initial):
                        fail()
                    destination_child_fd = os.open(
                        name,
                        destination_file_flags,
                        0o600,
                        dir_fd=destination_fd,
                    )
                    copied = 0
                    while True:
                        chunk = os.read(source_child_fd, 65536)
                        if not chunk:
                            break
                        copied += len(chunk)
                        counters["bytes"] += len(chunk)
                        if copied > FILE_BYTES_LIMIT or counters["bytes"] > TOTAL_BYTES_LIMIT:
                            fail()
                        view = memoryview(chunk)
                        while view:
                            written = os.write(destination_child_fd, view)
                            if written <= 0:
                                fail()
                            view = view[written:]
                    if copied != named_initial.st_size:
                        fail()
                    os.fchmod(destination_child_fd, mode)
                    os.fsync(destination_child_fd)
                    if identity(os.fstat(source_child_fd)) != identity(named_initial):
                        fail()
                finally:
                    if destination_child_fd is not None:
                        os.close(destination_child_fd)
                    os.close(source_child_fd)
                continue

            if stat.S_ISLNK(named_initial.st_mode):
                target = os.readlink(name, dir_fd=source_fd)
                confirmed = os.stat(name, dir_fd=source_fd, follow_symlinks=False)
                if identity(confirmed) != identity(named_initial) or os.path.isabs(target):
                    fail()
                os.symlink(target, name, dir_fd=destination_fd)
                continue

            fail()

        if identity(os.fstat(source_fd)) != identity(source_initial):
            fail()

    try:
        stage_state = os.fstat(stage_fd)
        if identity(stage_state) != identity(os.stat(stage_root, follow_symlinks=False)):
            fail()
        bounded_sorted_names(stage_fd, {"entries": 0}, 0)
        try:
            os.mkdir("home", mode=0o700, dir_fd=stage_fd)
            destination_fd = os.open("home", directory_flags, dir_fd=stage_fd)
            source_fd = os.open(source, directory_flags)
            try:
                if identity(os.fstat(source_fd)) != identity(source_root_state):
                    fail()
                count_entry()
                copy_directory(source_fd, destination_fd, 0)
                os.fchmod(destination_fd, stat.S_IMODE(source_root_state.st_mode))
                if identity(os.fstat(source_fd)) != identity(source_root_state):
                    fail()
            finally:
                os.close(source_fd)
                os.close(destination_fd)
        except BaseException as staging_error:
            try:
                remove_staged_home(stage_root)
            except StageError as cleanup_error:
                raise cleanup_error from staging_error
            raise
        return stage_root / "home"
    except BaseException:
        raise
    finally:
        os.close(stage_fd)


def remove_staged_home(stage_root):
    stage_root = require_private_parent(stage_root)
    if not hasattr(os, "O_DIRECTORY") or not hasattr(os, "O_NOFOLLOW"):
        fail()
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    directory_flags |= getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NONBLOCK", 0)
    stage_fd = os.open(stage_root, directory_flags)
    home_fd = None
    counters = {"entries": 0}

    def bounded_sorted_names(directory_fd):
        names = []
        ceiling = ENTRY_LIMIT + 2
        try:
            with os.scandir(directory_fd) as entries:
                for entry in entries:
                    if counters["entries"] >= ceiling:
                        fail()
                    counters["entries"] += 1
                    names.append(entry.name)
        except OSError:
            fail()
        names.sort(key=os.fsencode)
        if len(names) != len(set(os.fsencode(name) for name in names)):
            fail()
        return names

    def prepare_owned_directory(parent_fd, name, directory_fd, named_initial):
        opened_initial = os.fstat(directory_fd)
        expected_inode = inode_identity(named_initial)
        if (
            not stat.S_ISDIR(named_initial.st_mode)
            or not stat.S_ISDIR(opened_initial.st_mode)
            or named_initial.st_uid != os.geteuid()
            or opened_initial.st_uid != os.geteuid()
            or inode_identity(opened_initial) != expected_inode
        ):
            fail()
        if stat.S_IMODE(opened_initial.st_mode) != 0o700:
            os.fchmod(directory_fd, 0o700)
        opened_ready = os.fstat(directory_fd)
        named_ready = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (
            not stat.S_ISDIR(opened_ready.st_mode)
            or not stat.S_ISDIR(named_ready.st_mode)
            or opened_ready.st_uid != os.geteuid()
            or named_ready.st_uid != os.geteuid()
            or stat.S_IMODE(opened_ready.st_mode) != 0o700
            or stat.S_IMODE(named_ready.st_mode) != 0o700
            or inode_identity(opened_ready) != expected_inode
            or inode_identity(named_ready) != expected_inode
        ):
            fail()
        return expected_inode

    def clear(directory_fd, depth):
        if depth > DEPTH_LIMIT + 2:
            fail()
        for name in bounded_sorted_names(directory_fd):
            value = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
            if stat.S_ISDIR(value.st_mode):
                child_fd = os.open(name, directory_flags, dir_fd=directory_fd)
                try:
                    child_inode = prepare_owned_directory(
                        directory_fd,
                        name,
                        child_fd,
                        value,
                    )
                    clear(child_fd, depth + 1)
                    opened_confirmed = os.fstat(child_fd)
                    named_confirmed = os.stat(
                        name,
                        dir_fd=directory_fd,
                        follow_symlinks=False,
                    )
                    if (
                        opened_confirmed.st_uid != os.geteuid()
                        or named_confirmed.st_uid != os.geteuid()
                        or inode_identity(opened_confirmed) != child_inode
                        or inode_identity(named_confirmed) != child_inode
                    ):
                        fail()
                    os.rmdir(name, dir_fd=directory_fd)
                finally:
                    os.close(child_fd)
            else:
                os.unlink(name, dir_fd=directory_fd)

    try:
        stage_state = os.fstat(stage_fd)
        named_stage = os.stat(stage_root, follow_symlinks=False)
        if identity(stage_state) != identity(named_stage):
            fail()
        try:
            home_fd = os.open("home", directory_flags, dir_fd=stage_fd)
        except FileNotFoundError:
            return
        home_state = os.fstat(home_fd)
        named_home = os.stat("home", dir_fd=stage_fd, follow_symlinks=False)
        if (
            not stat.S_ISDIR(home_state.st_mode)
            or home_state.st_uid != os.geteuid()
            or identity(home_state) != identity(named_home)
        ):
            fail()
        home_inode = prepare_owned_directory(stage_fd, "home", home_fd, named_home)
        clear(home_fd, 0)
        opened_home_confirmed = os.fstat(home_fd)
        named_home_confirmed = os.stat("home", dir_fd=stage_fd, follow_symlinks=False)
        if (
            opened_home_confirmed.st_uid != os.geteuid()
            or named_home_confirmed.st_uid != os.geteuid()
            or inode_identity(opened_home_confirmed) != home_inode
            or inode_identity(named_home_confirmed) != home_inode
        ):
            fail()
        os.rmdir("home", dir_fd=stage_fd)
        os.close(home_fd)
        home_fd = None
    except OSError:
        fail()
    finally:
        if home_fd is not None:
            os.close(home_fd)
        os.close(stage_fd)


def validator_binding(home):
    result = subprocess.run(
        ["/usr/bin/python3", "-I", os.fspath(VALIDATOR), "--emit-binding", os.fspath(home)],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        env={"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"},
        check=False,
    )
    if result.returncode != 0 or len(result.stdout) > OUTPUT_LIMIT:
        fail()
    try:
        binding = json.loads(result.stdout.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail()
    return binding


def canonical_binding(binding):
    return json.dumps(binding, ensure_ascii=True, separators=(",", ":"), sort_keys=True)


def main():
    if len(sys.argv) != 3:
        fail()
    source = pathlib.Path(sys.argv[1])
    stage_root = pathlib.Path(sys.argv[2])
    staged_home = stage_runtime_tree(source, stage_root)
    staged_binding = validator_binding(staged_home)
    print(canonical_binding(staged_binding))


if __name__ == "__main__":
    try:
        main()
    except StageError:
        raise SystemExit(1)

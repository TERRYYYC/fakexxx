#!/Library/Developer/CommandLineTools/usr/bin/python3 -I
"""Seal the frozen, host-only readiness report for GitHub issue #64.

The production CLI has one immutable policy: one checked-in manifest digest,
one exact product candidate, and pinned host inspection tools. It never accepts
a caller-selected manifest or executable. Tests inject a separate Policy by
importing :func:`run_audit`; that fixture seam is not exposed by this CLI.

Exit codes:
  0  the frozen host report is valid and truthfully says BLOCKED
  1  a host fact or frozen policy failed validation
  2  command-line usage or an unsafe/unwritable report path
  3  --fail-on-blocked was requested and the valid frozen state is BLOCKED
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from typing import Any, Mapping


SCHEMA_VERSION = 1
PACKAGE_ID = "github64-exact-build-device-readiness"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
DEVICE_TRANSPORT_EXECUTABLES = frozenset({"adb", "fastboot"})
NON_DEVICE_INSPECTOR_IDS = frozenset({"git", "aapt", "apksigner"})


@dataclass(frozen=True)
class FrozenFile:
    role: str
    path: Path
    sha256: str
    executable: bool = False


@dataclass(frozen=True)
class FrozenTree:
    role: str
    path: Path
    sha256: str


@dataclass(frozen=True)
class InspectorPolicy:
    inspector_id: str
    version: str
    executable: FrozenFile
    support_files: tuple[FrozenFile, ...] = ()
    support_trees: tuple[FrozenTree, ...] = ()
    arguments_prefix: tuple[str, ...] = ()
    environment: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class PreparedInspector:
    policy: InspectorPolicy
    executable_path: Path
    support_files: tuple[tuple[FrozenFile, Path], ...]
    support_trees: tuple[tuple[FrozenTree, Path], ...]
    arguments_prefix: tuple[str, ...]
    environment: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class FileSourceSeal:
    source_path: Path
    resolved_path: Path
    device: int
    inode: int
    mode: int
    links: int
    uid: int
    gid: int
    size: int
    mtime_ns: int
    ctime_ns: int
    sha256: str
    relative_path: str | None = None
    descriptor: int | None = field(default=None, compare=False, repr=False)


@dataclass(frozen=True)
class TreeSourceSeal:
    source_path: Path
    resolved_path: Path
    device: int
    inode: int
    mode: int
    mtime_ns: int
    ctime_ns: int
    sha256: str
    state_sha256: str


@dataclass(frozen=True)
class Policy:
    manifest_sha256: str
    candidate_head: str
    candidate_tree: str
    base_head: str
    allowed_preparation_delta: frozenset[str]
    allowed_generated_roots: frozenset[str]
    artifact_ids: frozenset[str]
    input_ids: frozenset[str]
    required_authorizations: frozenset[str]
    blocker_scopes: tuple[tuple[str, frozenset[str]], ...]
    scope_disposition: tuple[tuple[str, str], ...]
    canonical_ledger: tuple[tuple[str, str], ...]
    go_no_go: str
    inspectors: tuple[InspectorPolicy, ...]

    def blocker_scope_map(self) -> dict[str, frozenset[str]]:
        return dict(self.blocker_scopes)


def production_policy() -> Policy:
    java_home = "/opt/homebrew/Cellar/openjdk@17/17.0.20/libexec/openjdk.jdk/Contents/Home"
    git_home = (
        "/Users/terry/.cache/codex-runtimes/"
        "codex-primary-runtime/dependencies/native/git"
    )
    common_env = (("LANG", "C"), ("LC_ALL", "C"))
    return Policy(
        manifest_sha256="3129b3d9e0a733753e35b85e72ec726e5855cfe9f4395ab49da0cbf734cae43f",
        candidate_head="5002e0e005324c32ca3d36d10510180d1fafbf81",
        candidate_tree="ff4c6440509aa1d90b4a7a8dc6647b47c2d33af1",
        base_head="9eb6389e05e49e5a19c3890fd1a39b9be7e11c1d",
        allowed_preparation_delta=frozenset(
            {
                "docs/acceptance/github64-exact-build-device-readiness.json",
                "docs/acceptance/github64-exact-build-device-readiness.md",
                "scripts/check-github64-device-readiness.py",
                "scripts/selftest-github64-device-readiness.sh",
            }
        ),
        allowed_generated_roots=frozenset(
            {
                "acceptance/.gradle",
                "acceptance/build",
                "acceptance/fake-qwy/build",
                "acceptance/scenarios/build",
                "apps/cellrebel-auto/.gradle",
                "apps/cellrebel-auto/app/build",
                "apps/cellrebel-auto/build",
                "apps/qianwangyou/.gradle",
                "apps/qianwangyou/app/build",
                "apps/qianwangyou/build",
            }
        ),
        artifact_ids=frozenset({"auto", "qwy"}),
        input_ids=frozenset({"contract", "schedule", "device-ledger"}),
        required_authorizations=frozenset(
            {
                "DEVICE_LEASE",
                "APK_INSTALL_OR_REPLACE",
                "LSPOSED_SCOPE_CHANGE",
                "SYSTEM_MOCK_SELECTION",
                "DEVICE_STATE_MUTATION",
                "CLEANUP_OR_RESTORE",
            }
        ),
        blocker_scopes=(
            ("G2-HARNESS-SCHEMA-001", frozenset({"G"})),
            ("G2-HARNESS-LEASE-002", frozenset({"A", "B", "C", "E-device", "G"})),
            ("G2-HARNESS-EVIDENCE-003", frozenset({"A", "B", "C", "E-device", "G"})),
            ("G2-PR62-CHANGES-REQUESTED-004", frozenset({"A", "B", "C", "G"})),
            ("G2-PR63-PRINCIPAL-ROUTING-005", frozenset({"A", "B", "C"})),
            ("G2-ISSUE66-CONTINUITY-006", frozenset({"A", "B", "TRUSTED_QUOTA"})),
        ),
        scope_disposition=(
            ("A", "BLOCKED_BY_PR62_PR63_AND_ISSUE66"),
            ("B", "BLOCKED_BY_PR62_PR63_AND_ISSUE66"),
            ("C", "BLOCKED_BY_PR62_AND_PR63_NOT_ISSUE66"),
            ("E-host", "READY_FOR_HOST_AUDIT"),
            ("E-device", "BLOCKED_BY_HARNESS_AND_AUTHORIZATION_NOT_ISSUE66"),
            ("G", "BLOCKED_BY_PR62_SCHEMA_AND_EVIDENCE_NOT_ISSUE66"),
            ("M-CO-06", "ACCEPTED_HOST_DISPOSITION_NO_DEVICE_LEDGER_ROW"),
            ("M-VS-01", "POST_V1_ACCEPTED_OUT_OF_CURRENT_G2"),
        ),
        canonical_ledger=(
            ("relativePath", "docs/acceptance/matrix-evidence-device.json"),
            ("sha256", "37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570"),
            ("state", "EMPTY_UNCHANGED"),
        ),
        go_no_go="NO_GO_DEVICE_EXECUTION",
        inspectors=(
            InspectorPolicy(
                inspector_id="python-bootstrap",
                version="Python 3.9.6 isolated mode (CommandLineTools)",
                executable=FrozenFile(
                    role="executable",
                    path=Path("/Library/Developer/CommandLineTools/usr/bin/python3"),
                    sha256="bdea59019a38eb6600cc9e71e984a97fedadc406448431281e7657030f54987e",
                    executable=True,
                ),
                support_trees=(
                    FrozenTree(
                        role="python-runtime",
                        path=Path(
                            "/Library/Developer/CommandLineTools/Library/Frameworks/"
                            "Python3.framework/Versions/3.9"
                        ),
                        sha256="9554093f9f3037f2de48bb897245a9ff54796d1c0952c1fc631d98b1fe714508",
                    ),
                ),
                environment=common_env,
            ),
            InspectorPolicy(
                inspector_id="git",
                version="git version 2.53.0 (Codex primary runtime)",
                executable=FrozenFile(
                    role="executable",
                    path=Path(f"{git_home}/bin/git"),
                    sha256="ee73b116cc37f44ecdaa9e3fdfbc25ce827675859f5f966ec671112fd5caf074",
                    executable=True,
                ),
                support_trees=(
                    FrozenTree(
                        role="git-home",
                        path=Path(git_home),
                        sha256="a78b5118e8fd018ab1d7538109772cefa4098bb5afa54bd7fd10764486d08c1a",
                    ),
                ),
                environment=common_env
                + (
                    ("GIT_ATTR_NOSYSTEM", "1"),
                    ("GIT_CONFIG_GLOBAL", "/dev/null"),
                    ("GIT_CONFIG_NOSYSTEM", "1"),
                    ("GIT_CONFIG_SYSTEM", "/dev/null"),
                    ("GIT_NO_LAZY_FETCH", "1"),
                    ("GIT_NO_REPLACE_OBJECTS", "1"),
                    ("GIT_OPTIONAL_LOCKS", "0"),
                    ("GIT_PAGER", ""),
                    ("GIT_TERMINAL_PROMPT", "0"),
                    ("GIT_EXEC_PATH", f"{git_home}/libexec/git-core"),
                    ("GIT_TEMPLATE_DIR", f"{git_home}/share/git-core/templates"),
                    ("PATH", f"{git_home}/bin:/usr/bin:/bin"),
                ),
            ),
            InspectorPolicy(
                inspector_id="aapt",
                version="Android Asset Packaging Tool v0.2-14042983 (build-tools 36.1.0)",
                executable=FrozenFile(
                    role="executable",
                    path=Path("/Users/terry/Library/Android/sdk/build-tools/36.1.0/aapt"),
                    sha256="b08d65ee8f8ee6c8a2e9d5ed6b7881873df83e60c44800b951c30d4ff80d9efe",
                    executable=True,
                ),
                support_files=(
                    FrozenFile(
                        role="aapt-libc++",
                        path=Path(
                            "/Users/terry/Library/Android/sdk/build-tools/36.1.0/lib64/libc++.dylib"
                        ),
                        sha256="66499e49a1c5a9c73d2d4958f5d9f4dccec56c5eb8bba7ac4e29297ea3cf3fed",
                    ),
                ),
                support_trees=(
                    FrozenTree(
                        role="build-tools-home",
                        path=Path("/Users/terry/Library/Android/sdk/build-tools/36.1.0"),
                        sha256="71cca8b37798d10aaea1f94e502a8952ef77a0644c0449d773f1b3758a00f128",
                    ),
                ),
                environment=common_env + (("PATH", "/usr/bin:/bin"),),
            ),
            InspectorPolicy(
                inspector_id="apksigner",
                version="0.9 JAR (build-tools 36.1.0) via OpenJDK 17.0.20",
                executable=FrozenFile(
                    role="executable",
                    path=Path(f"{java_home}/bin/java"),
                    sha256="77ddcbc036c6f6261d2583725018a6a45a2385d5339deea14e53cb8d91086192",
                    executable=True,
                ),
                support_files=(
                    FrozenFile(
                        role="apksigner-jar",
                        path=Path(
                            "/Users/terry/Library/Android/sdk/build-tools/36.1.0/"
                            "lib/apksigner.jar"
                        ),
                        sha256="71e18adf733f5e112d1f062dbe6b0c2eb439a4d7c773d083c42a703c66f56df1",
                    ),
                ),
                support_trees=(
                    FrozenTree(
                        role="java-home",
                        path=Path(java_home),
                        sha256="cec57e31b8945654d0d463138bd55bec881f3283a458fda5cb65f0e9263f1e36",
                    ),
                ),
                arguments_prefix=(
                    "-jar",
                    "/Users/terry/Library/Android/sdk/build-tools/36.1.0/lib/apksigner.jar",
                ),
                environment=common_env
                + (
                    ("JAVA_HOME", java_home),
                    ("PATH", f"{java_home}/bin:/usr/bin:/bin"),
                ),
            ),
        ),
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument(
        "--fail-on-blocked",
        action="store_true",
        help="return 3 for this valid but frozen NO-GO package",
    )
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    descriptor = os.open(path, flags)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise ValueError(f"not a regular file: {path}")
        digest = hashlib.sha256()
        offset = 0
        while True:
            chunk = os.pread(descriptor, 1024 * 1024, offset)
            if not chunk:
                break
            digest.update(chunk)
            offset += len(chunk)
        return digest.hexdigest()
    finally:
        os.close(descriptor)


def sha256_tree(root: Path) -> str:
    """Hash a directory's names, kinds, modes, symlink targets and file bytes."""
    root = root.resolve()
    if not root.is_dir():
        raise ValueError(f"not a directory: {root}")
    digest = hashlib.sha256()
    digest.update(b"github64-frozen-tree-v1\0")
    for directory, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        dirnames.sort()
        filenames.sort()
        base = Path(directory)
        for name in sorted((*dirnames, *filenames)):
            path = base / name
            relative = path.relative_to(root).as_posix().encode("utf-8")
            metadata = path.lstat()
            mode = stat.S_IMODE(metadata.st_mode)
            if stat.S_ISLNK(metadata.st_mode):
                kind = b"L"
                payload = os.readlink(path).encode("utf-8")
            elif stat.S_ISDIR(metadata.st_mode):
                kind = b"D"
                payload = b""
            elif stat.S_ISREG(metadata.st_mode):
                kind = b"F"
                payload = bytes.fromhex(sha256_file(path))
            else:
                raise ValueError(f"unsupported entry in frozen tree: {path}")
            digest.update(relative)
            digest.update(b"\0")
            digest.update(kind)
            digest.update(f"{mode:o}".encode("ascii"))
            digest.update(b"\0")
            digest.update(payload)
            digest.update(b"\0")
    return digest.hexdigest()


def sha256_tree_state(root: Path) -> str:
    """Hash entry identity and metadata so byte-identical replacement is visible."""
    root = root.resolve()
    if not root.is_dir():
        raise ValueError(f"not a directory: {root}")
    digest = hashlib.sha256()
    digest.update(b"github64-frozen-tree-state-v1\0")
    for directory, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        dirnames.sort()
        filenames.sort()
        base = Path(directory)
        for name in sorted((*dirnames, *filenames)):
            path = base / name
            relative = path.relative_to(root).as_posix().encode("utf-8")
            metadata = path.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                kind = b"L"
                target = os.fsencode(os.readlink(path))
            elif stat.S_ISDIR(metadata.st_mode):
                kind = b"D"
                target = b""
            elif stat.S_ISREG(metadata.st_mode):
                kind = b"F"
                target = b""
            else:
                raise ValueError(f"unsupported entry in frozen tree: {path}")
            fields = (
                metadata.st_dev,
                metadata.st_ino,
                stat.S_IMODE(metadata.st_mode),
                metadata.st_size,
                metadata.st_mtime_ns,
                metadata.st_ctime_ns,
            )
            digest.update(relative)
            digest.update(b"\0")
            digest.update(kind)
            digest.update(b":".join(str(value).encode("ascii") for value in fields))
            digest.update(b"\0")
            digest.update(target)
            digest.update(b"\0")
    return digest.hexdigest()


def file_stat_signature(
    metadata: os.stat_result,
) -> tuple[int, int, int, int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def tree_stat_signature(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        stat.S_IMODE(metadata.st_mode),
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def sha256_descriptor(descriptor: int) -> str:
    digest = hashlib.sha256()
    offset = 0
    while True:
        chunk = os.pread(descriptor, 1024 * 1024, offset)
        if not chunk:
            break
        digest.update(chunk)
        offset += len(chunk)
    return digest.hexdigest()


def read_descriptor(descriptor: int) -> bytes:
    chunks: list[bytes] = []
    offset = 0
    while True:
        chunk = os.pread(descriptor, 1024 * 1024, offset)
        if not chunk:
            break
        chunks.append(chunk)
        offset += len(chunk)
    return b"".join(chunks)


def track_owned_descriptor(owner: Any | None, descriptor: int) -> None:
    if owner is not None:
        owner.track_descriptor(descriptor)


def close_owned_descriptor(owner: Any | None, descriptor: int) -> None:
    if owner is None:
        os.close(descriptor)
    else:
        owner.close_descriptor(descriptor)


def write_descriptor(descriptor: int, payload: bytes) -> None:
    offset = 0
    while offset < len(payload):
        written = os.write(descriptor, payload[offset:])
        if written <= 0:
            raise OSError("short write to private snapshot")
        offset += written


def capture_open_file(
    descriptor: int,
    *,
    source_path: Path,
    resolved_path: Path,
    destination: Path | None = None,
    relative_path: str | None = None,
    hold_open: bool = False,
    require_single_link: bool = False,
    descriptor_owner: Any | None = None,
) -> FileSourceSeal:
    track_owned_descriptor(descriptor_owner, descriptor)
    source_descriptor: int | None = descriptor
    destination_descriptor: int | None = None
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ValueError(f"not a regular file: {source_path}")
        if require_single_link and before.st_nlink != 1:
            raise ValueError(
                f"source must have exactly one hard link: {source_path} has {before.st_nlink}"
            )
        if destination is not None:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination_descriptor = os.open(
                destination,
                os.O_WRONLY
                | os.O_CREAT
                | os.O_EXCL
                | getattr(os, "O_CLOEXEC", 0),
                0o600,
            )
            track_owned_descriptor(descriptor_owner, destination_descriptor)
        digest = hashlib.sha256()
        offset = 0
        while True:
            chunk = os.pread(descriptor, 1024 * 1024, offset)
            if not chunk:
                break
            digest.update(chunk)
            if destination_descriptor is not None:
                write_descriptor(destination_descriptor, chunk)
            offset += len(chunk)
        after = os.fstat(descriptor)
        if file_stat_signature(before) != file_stat_signature(after):
            raise OSError(f"source changed while being read: {source_path}")
        if destination_descriptor is not None:
            os.fsync(destination_descriptor)
            os.fchmod(destination_descriptor, stat.S_IMODE(before.st_mode))
        seal = FileSourceSeal(
            source_path=source_path,
            resolved_path=resolved_path,
            device=before.st_dev,
            inode=before.st_ino,
            mode=before.st_mode,
            links=before.st_nlink,
            uid=before.st_uid,
            gid=before.st_gid,
            size=before.st_size,
            mtime_ns=before.st_mtime_ns,
            ctime_ns=before.st_ctime_ns,
            sha256=digest.hexdigest(),
            relative_path=relative_path,
            descriptor=descriptor if hold_open else None,
        )
        if destination_descriptor is not None:
            close_owned_descriptor(descriptor_owner, destination_descriptor)
            destination_descriptor = None
        if not hold_open:
            close_owned_descriptor(descriptor_owner, descriptor)
            source_descriptor = None
        return seal
    except BaseException as primary:
        cleanup_errors: list[str] = []
        for label, owned in (
            ("private destination", destination_descriptor),
            ("source", source_descriptor),
        ):
            if owned is None:
                continue
            try:
                close_owned_descriptor(descriptor_owner, owned)
            except OSError as exc:
                cleanup_errors.append(f"{label} fd {owned}: {exc}")
        if cleanup_errors:
            raise OSError(
                f"{primary}; pre-registration cleanup failed: "
                + "; ".join(cleanup_errors)
            ) from primary
        raise


def capture_file_source(
    source_path: Path,
    destination: Path | None = None,
    *,
    hold_open: bool = False,
    descriptor_owner: Any | None = None,
) -> FileSourceSeal:
    """Read one resolved regular-file generation, optionally retaining its fd."""
    resolved = source_path.resolve(strict=True)
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    descriptor = os.open(resolved, flags)
    track_owned_descriptor(descriptor_owner, descriptor)
    return capture_open_file(
        descriptor,
        source_path=source_path,
        resolved_path=resolved,
        destination=destination,
        hold_open=hold_open,
        descriptor_owner=descriptor_owner,
    )


def open_repo_relative(
    repo_descriptor: int,
    raw: Any,
    descriptor_owner: Any | None = None,
) -> tuple[int, str]:
    if not isinstance(raw, str) or not raw or "\x00" in raw:
        raise ValueError("path must be a non-empty string")
    relative = Path(raw)
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        raise ValueError(f"path must remain repo-relative: {raw!r}")
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    file_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    parent = os.dup(repo_descriptor)
    track_owned_descriptor(descriptor_owner, parent)
    try:
        for component in relative.parts[:-1]:
            child = os.open(component, directory_flags, dir_fd=parent)
            track_owned_descriptor(descriptor_owner, child)
            close_owned_descriptor(descriptor_owner, parent)
            parent = child
        descriptor = os.open(relative.parts[-1], file_flags, dir_fd=parent)
        track_owned_descriptor(descriptor_owner, descriptor)
        return descriptor, relative.as_posix()
    finally:
        close_owned_descriptor(descriptor_owner, parent)


def capture_repo_file(
    repo: Path,
    repo_descriptor: int,
    raw: Any,
    destination: Path | None = None,
    *,
    hold_open: bool = False,
    descriptor_owner: Any | None = None,
) -> FileSourceSeal:
    descriptor, relative = open_repo_relative(
        repo_descriptor, raw, descriptor_owner
    )
    source_path = repo / relative
    return capture_open_file(
        descriptor,
        source_path=source_path,
        resolved_path=source_path,
        destination=destination,
        relative_path=relative,
        hold_open=hold_open,
        require_single_link=True,
        descriptor_owner=descriptor_owner,
    )


def open_unlinked_snapshot_readers(
    path: Path,
    count: int,
    descriptor_owner: Any | None = None,
) -> tuple[int, ...]:
    """Open independent read-only descriptions, then remove the last pathname."""
    descriptors: list[int] = []
    try:
        os.chmod(path, 0o400)
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
            raise OSError("private snapshot must begin as one regular linked inode")
        flags = (
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NONBLOCK", 0)
        )
        for _ in range(count):
            descriptor = os.open(path, flags)
            track_owned_descriptor(descriptor_owner, descriptor)
            metadata = os.fstat(descriptor)
            if metadata.st_dev != before.st_dev or metadata.st_ino != before.st_ino:
                close_owned_descriptor(descriptor_owner, descriptor)
                raise OSError("private snapshot identity changed while opening readers")
            descriptors.append(descriptor)
        path.unlink()
        if any(os.fstat(descriptor).st_nlink != 0 for descriptor in descriptors):
            raise OSError("private snapshot pathname was not fully unlinked")
        return tuple(descriptors)
    except BaseException as primary:
        cleanup_errors: list[str] = []
        for descriptor in descriptors:
            try:
                close_owned_descriptor(descriptor_owner, descriptor)
            except OSError as exc:
                cleanup_errors.append(f"reader fd {descriptor}: {exc}")
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        except OSError as exc:
            cleanup_errors.append(f"private path: {exc}")
        if cleanup_errors:
            raise OSError(
                f"{primary}; snapshot reader cleanup failed: "
                + "; ".join(cleanup_errors)
            ) from primary
        raise


def capture_tree_source(source_path: Path) -> TreeSourceSeal:
    resolved = source_path.resolve(strict=True)
    before = resolved.lstat()
    if not stat.S_ISDIR(before.st_mode):
        raise ValueError(f"not a directory: {resolved}")
    state_before = sha256_tree_state(resolved)
    digest = sha256_tree(resolved)
    state_after = sha256_tree_state(resolved)
    after = resolved.lstat()
    if (
        tree_stat_signature(before) != tree_stat_signature(after)
        or state_before != state_after
    ):
        raise OSError(f"tree changed while being hashed: {source_path}")
    return TreeSourceSeal(
        source_path=source_path,
        resolved_path=resolved,
        device=before.st_dev,
        inode=before.st_ino,
        mode=stat.S_IMODE(before.st_mode),
        mtime_ns=before.st_mtime_ns,
        ctime_ns=before.st_ctime_ns,
        sha256=digest,
        state_sha256=state_after,
    )


def escaping_snapshot_symlinks(root: Path) -> list[str]:
    root = root.resolve(strict=True)
    errors: list[str] = []
    for directory, dirnames, filenames in os.walk(root, topdown=True, followlinks=False):
        dirnames.sort()
        filenames.sort()
        base = Path(directory)
        for name in sorted((*dirnames, *filenames)):
            path = base / name
            if not path.is_symlink():
                continue
            try:
                target = path.resolve(strict=True)
                if not is_within(target, root):
                    errors.append(f"{path.relative_to(root)} -> {os.readlink(path)}")
            except (OSError, RuntimeError, ValueError) as exc:
                errors.append(f"{path.relative_to(root)} -> {exc}")
    return errors


def run(
    command: list[str],
    *,
    cwd: Path | None = None,
    environment: Mapping[str, str] | None = None,
    pass_fds: tuple[int, ...] = (),
) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        command,
        cwd=cwd,
        env=dict(environment) if environment is not None else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        pass_fds=pass_fds,
    )


def safe_relative(root: Path, raw: Any) -> Path:
    if not isinstance(raw, str) or not raw or "\x00" in raw:
        raise ValueError("path must be a non-empty string")
    relative = Path(raw)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"path must remain repo-relative: {raw!r}")
    resolved = (root / relative).resolve()
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise ValueError(f"path escapes source repository: {raw!r}") from exc
    return resolved


def is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def atomic_write_pair(
    report_path: Path,
    report_data: bytes,
    sidecar_path: Path,
    sidecar_data: bytes,
) -> None:
    """Stage both outputs and restore previous bytes if either replace fails."""
    if report_path.parent != sidecar_path.parent:
        raise OSError("report and sidecar must share one resolved directory")
    parent = report_path.parent
    parent.mkdir(parents=True, exist_ok=True)
    targets = ((report_path, report_data), (sidecar_path, sidecar_data))
    staged: dict[Path, Path] = {}
    backups: dict[Path, Path] = {}
    existed = {target: target.exists() for target, _ in targets}
    try:
        for target, data in targets:
            if existed[target] and not target.is_file():
                raise OSError(f"output target is not a regular file: {target}")
            fd, raw_temporary = tempfile.mkstemp(prefix=f".{target.name}.stage.", dir=parent)
            temporary = Path(raw_temporary)
            staged[target] = temporary
            with os.fdopen(fd, "wb") as fh:
                fh.write(data)
                fh.flush()
                os.fsync(fh.fileno())
        for target, _ in targets:
            if existed[target]:
                fd, raw_backup = tempfile.mkstemp(prefix=f".{target.name}.backup.", dir=parent)
                os.close(fd)
                backup = Path(raw_backup)
                backup.unlink()
                os.link(target, backup)
                backups[target] = backup
        for target, _ in targets:
            os.replace(staged.pop(target), target)
    except BaseException:
        for target, _ in reversed(targets):
            backup = backups.pop(target, None)
            try:
                if backup is not None and backup.exists():
                    os.replace(backup, target)
                elif not existed[target] and target.exists():
                    target.unlink()
            except OSError:
                pass
        raise
    finally:
        for temporary in (*staged.values(), *backups.values()):
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass


def paths_alias(left: Path, right: Path) -> bool:
    if left == right:
        return True
    try:
        return left.exists() and right.exists() and os.path.samefile(left, right)
    except OSError:
        return False


def git_metadata_roots(repo: Path) -> tuple[Path, ...]:
    """Resolve the per-worktree and common Git metadata roots without running Git."""
    marker = repo / ".git"
    if marker.is_dir():
        return (marker.resolve(),)
    if not marker.is_file():
        return ()
    try:
        first_line = marker.read_text(encoding="utf-8").splitlines()[0]
    except (OSError, IndexError, UnicodeError) as exc:
        raise ValueError(f"cannot resolve Git metadata pointer: {exc}") from exc
    if not first_line.startswith("gitdir: "):
        raise ValueError("invalid Git metadata pointer")
    raw_gitdir = Path(first_line.removeprefix("gitdir: "))
    gitdir = (
        (marker.parent / raw_gitdir).resolve()
        if not raw_gitdir.is_absolute()
        else raw_gitdir.resolve()
    )
    roots = [gitdir]
    common_marker = gitdir / "commondir"
    if common_marker.is_file():
        try:
            raw_common = Path(common_marker.read_text(encoding="utf-8").strip())
        except (OSError, UnicodeError) as exc:
            raise ValueError(f"cannot resolve common Git metadata root: {exc}") from exc
        common = (
            (gitdir / raw_common).resolve()
            if not raw_common.is_absolute()
            else raw_common.resolve()
        )
        roots.append(common)
    return tuple(dict.fromkeys(roots))


def unsafe_output_reason(
    policy: Policy,
    source_repo: Path,
    manifest_path: Path,
    report_path: Path,
    sidecar_path: Path,
) -> str | None:
    outputs = (("report", report_path), ("sidecar", sidecar_path))
    if any("\n" in path.name or "\r" in path.name for _, path in outputs):
        return "report and sidecar filenames must not contain CR or LF"
    if any(is_within(path, source_repo) for _, path in outputs):
        return "report and sidecar must be outside the source repository"
    if paths_alias(report_path, sidecar_path):
        return "report and sidecar must not resolve to the same file"
    if report_path.parent != sidecar_path.parent:
        return "report and sidecar must share one resolved directory"

    protected_files = [("manifest", manifest_path)]
    protected_trees: list[tuple[str, Path]] = []
    for inspector in policy.inspectors:
        for frozen in (inspector.executable, *inspector.support_files):
            protected_files.append(
                (f"tool:{inspector.inspector_id}:{frozen.role}", frozen.path.resolve())
            )
        for frozen_tree in inspector.support_trees:
            protected_trees.append(
                (
                    f"tool-tree:{inspector.inspector_id}:{frozen_tree.role}",
                    frozen_tree.path.resolve(),
                )
            )
    try:
        protected_trees.extend(
            (f"git-metadata:{index}", root)
            for index, root in enumerate(git_metadata_roots(source_repo), start=1)
        )
    except ValueError as exc:
        return str(exc)

    for output_name, output in outputs:
        for protected_name, protected in protected_files:
            if paths_alias(output, protected):
                return f"{output_name} collides with protected file {protected_name}"
        for protected_name, protected_root in protected_trees:
            if is_within(output, protected_root):
                return f"{output_name} is inside protected tree {protected_name}"
    return None


class Audit:
    def __init__(self) -> None:
        self.findings: list[dict[str, Any]] = []
        self.commands: list[dict[str, Any]] = []

    def check(self, finding_id: str, condition: bool, expected: Any, actual: Any) -> bool:
        self.findings.append(
            {
                "id": finding_id,
                "status": "PASS" if condition else "FAIL",
                "expected": expected,
                "actual": actual,
            }
        )
        return condition

    @property
    def passed(self) -> bool:
        return all(item["status"] == "PASS" for item in self.findings)

    def begin_command(
        self,
        inspector: PreparedInspector,
        command: list[str],
        evidence_arguments: list[str],
    ) -> dict[str, Any]:
        inspector_id = inspector.policy.inspector_id
        executable_name = Path(command[0]).name.lower() if command else ""
        if executable_name in DEVICE_TRANSPORT_EXECUTABLES:
            classification = "DEVICE_TRANSPORT"
        elif inspector_id in NON_DEVICE_INSPECTOR_IDS:
            classification = "NON_DEVICE_INSPECTOR"
        else:
            classification = "UNCLASSIFIED"
        record = {
            "dispatchIndex": len(self.commands) + 1,
            "inspectorId": inspector_id,
            "executableRole": inspector.policy.executable.role,
            "executableSha256": inspector.policy.executable.sha256,
            "executionSource": "PRIVATE_VERIFIED_SNAPSHOT",
            "arguments": evidence_arguments,
            "classification": classification,
            "deviceTransport": classification == "DEVICE_TRANSPORT",
            "spawned": False,
            "returnCode": None,
            "outputUtf8": None,
        }
        self.commands.append(record)
        return record

    @staticmethod
    def finish_command(
        record: dict[str, Any],
        completed: subprocess.CompletedProcess[Any],
    ) -> None:
        record["spawned"] = True
        record["returnCode"] = completed.returncode

    @property
    def executed_device_commands(self) -> int:
        return sum(
            bool(command["spawned"] and command["deviceTransport"])
            for command in self.commands
        )


class SourceSealRegistry:
    def __init__(self, repo: Path) -> None:
        self.repo = repo.resolve(strict=True)
        root_flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
            | getattr(os, "O_NONBLOCK", 0)
        )
        self.repo_descriptor = os.open(self.repo, root_flags)
        self._repo_identity = os.fstat(self.repo_descriptor)
        self._files: dict[str, list[FileSourceSeal]] = {}
        self._trees: dict[str, list[TreeSourceSeal]] = {}
        self._held_descriptors: set[int] = set()
        self._repo_descriptor_closed = False
        self._closed = False

    def capture_file_source(
        self,
        source_path: Path,
        destination: Path | None = None,
    ) -> FileSourceSeal:
        seal = capture_file_source(
            source_path,
            destination,
            hold_open=True,
            descriptor_owner=self,
        )
        if seal.descriptor is not None:
            self._held_descriptors.add(seal.descriptor)
        return seal

    def capture_repo_file(
        self,
        raw: Any,
        destination: Path | None = None,
    ) -> FileSourceSeal:
        seal = capture_repo_file(
            self.repo,
            self.repo_descriptor,
            raw,
            destination,
            hold_open=True,
            descriptor_owner=self,
        )
        if seal.descriptor is not None:
            self._held_descriptors.add(seal.descriptor)
        return seal

    def register_file(self, finding_id: str, seal: FileSourceSeal) -> None:
        self._files.setdefault(finding_id, []).append(seal)
        if seal.descriptor is not None:
            self._held_descriptors.add(seal.descriptor)

    def register_tree(self, finding_id: str, seal: TreeSourceSeal) -> None:
        self._trees.setdefault(finding_id, []).append(seal)

    def track_descriptor(self, descriptor: int) -> None:
        self._held_descriptors.add(descriptor)

    def close_descriptor(self, descriptor: int) -> None:
        os.close(descriptor)
        self._held_descriptors.discard(descriptor)

    def verify(self, audit: Audit) -> None:
        try:
            current_root = self.repo.resolve(strict=True).stat()
            root_stable = (
                current_root.st_dev == self._repo_identity.st_dev
                and current_root.st_ino == self._repo_identity.st_ino
                and stat.S_ISDIR(current_root.st_mode)
            )
            root_actual: Any = {
                "device": current_root.st_dev,
                "inode": current_root.st_ino,
            }
        except (OSError, RuntimeError, ValueError) as exc:
            root_stable = False
            root_actual = str(exc)
        audit.check(
            "source:repo-root-stable",
            root_stable,
            {
                "device": self._repo_identity.st_dev,
                "inode": self._repo_identity.st_ino,
            },
            root_actual,
        )
        finding_ids = sorted(set(self._files) | set(self._trees))
        for finding_id in finding_ids:
            details: list[dict[str, Any]] = []
            stable = True
            for opening in self._files.get(finding_id, []):
                try:
                    if opening.descriptor is None:
                        raise OSError("opening source descriptor was not retained")
                    held_metadata = os.fstat(opening.descriptor)
                    held_stable = (
                        file_stat_signature(held_metadata)
                        == (
                            opening.device,
                            opening.inode,
                            opening.mode,
                            opening.links,
                            opening.uid,
                            opening.gid,
                            opening.size,
                            opening.mtime_ns,
                            opening.ctime_ns,
                        )
                        and sha256_descriptor(opening.descriptor) == opening.sha256
                    )
                    current = (
                        capture_repo_file(
                            self.repo,
                            self.repo_descriptor,
                            opening.relative_path,
                            descriptor_owner=self,
                        )
                        if opening.relative_path is not None
                        else capture_file_source(
                            opening.source_path, descriptor_owner=self
                        )
                    )
                    item_stable = held_stable and current == opening
                    actual: Any = {
                        "path": str(current.resolved_path),
                        "sha256": current.sha256,
                        "size": current.size,
                        "device": current.device,
                        "inode": current.inode,
                    }
                except (OSError, RuntimeError, ValueError) as exc:
                    item_stable = False
                    actual = str(exc)
                details.append(
                    {
                        "kind": "file",
                        "source": str(opening.source_path),
                        "stable": item_stable,
                        "actual": actual,
                    }
                )
                stable = stable and item_stable
            for opening in self._trees.get(finding_id, []):
                try:
                    current = capture_tree_source(opening.source_path)
                    item_stable = current == opening
                    actual = {
                        "path": str(current.resolved_path),
                        "sha256": current.sha256,
                        "device": current.device,
                        "inode": current.inode,
                    }
                except (OSError, RuntimeError, ValueError) as exc:
                    item_stable = False
                    actual = str(exc)
                details.append(
                    {
                        "kind": "tree",
                        "source": str(opening.source_path),
                        "stable": item_stable,
                        "actual": actual,
                    }
                )
                stable = stable and item_stable
            audit.check(
                finding_id,
                stable,
                "opening path identity, metadata and digest unchanged at final barrier",
                details,
            )

    def close(self) -> None:
        if self._closed:
            return
        errors: list[str] = []
        for descriptor in tuple(self._held_descriptors):
            try:
                os.close(descriptor)
                self._held_descriptors.discard(descriptor)
            except OSError as exc:
                errors.append(f"fd {descriptor}: {exc}")
        if not self._repo_descriptor_closed:
            try:
                os.close(self.repo_descriptor)
                self._repo_descriptor_closed = True
            except OSError as exc:
                errors.append(f"repo fd {self.repo_descriptor}: {exc}")
        self._closed = not self._held_descriptors and self._repo_descriptor_closed
        if errors:
            raise OSError("; ".join(errors))


def ids(entries: Any) -> tuple[set[str], bool]:
    if not isinstance(entries, list):
        return set(), False
    values: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            return set(), False
        values.append(entry["id"])
    return set(values), len(values) == len(set(values))


def is_git_external_helper_key(key: str) -> bool:
    normalized = key.lower()
    if normalized in {
        "core.fsmonitor",
        "core.hookspath",
        "diff.external",
        "extensions.worktreeconfig",
    }:
        return True
    if normalized.startswith("pager.") or normalized == "core.pager":
        return True
    parts = normalized.split(".")
    return (
        len(parts) >= 3
        and parts[0] == "filter"
        and parts[-1] in {"clean", "smudge", "process"}
    ) or (
        len(parts) >= 3
        and parts[0] == "diff"
        and parts[-1] in {"command", "textconv"}
    )


def validate_git_external_helper_policy(
    audit: Audit,
    inspector: PreparedInspector,
    repo: Path,
    finding_prefix: str = "source:git",
) -> bool:
    """Reject repository config capable of spawning a helper before worktree I/O."""
    completed = run_inspector(
        audit,
        inspector,
        [
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.hooksPath=/dev/null",
            "-C",
            str(repo),
            "config",
            "--local",
            "--includes",
            "--name-only",
            "--get-regexp",
            ".*",
        ],
        f"{finding_prefix}:external-helper-config",
    )
    if completed is None:
        audit.check(
            f"{finding_prefix}:external-helper-policy",
            False,
            "no repository-configured external helpers",
            "pinned Git unavailable",
        )
        return False
    keys = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    dangerous = sorted({key for key in keys if is_git_external_helper_key(key)})
    ok = completed.returncode in {0, 1} and not dangerous
    audit.check(
        f"{finding_prefix}:external-helper-policy",
        ok,
        "no repository-configured helper and no worktree-config extension",
        dangerous if dangerous else completed.stderr or "none",
    )
    return ok


def validate_git_grafts_policy(
    audit: Audit,
    repo: Path,
    finding_id: str = "source:git:grafts-policy",
) -> bool:
    """Reject mutable fake-parent metadata before any Git graph operation."""
    try:
        metadata_roots = git_metadata_roots(repo)
        error = None
    except ValueError as exc:
        metadata_roots = ()
        error = str(exc)
    present = [
        str(root / "info/grafts")
        for root in metadata_roots
        if os.path.lexists(root / "info/grafts")
    ]
    ok = error is None and bool(metadata_roots) and not present
    audit.check(
        finding_id,
        ok,
        "info/grafts absent from per-worktree and common Git metadata",
        error or present or "absent",
    )
    return ok


def git_output(
    audit: Audit,
    inspector: PreparedInspector,
    repo: Path,
    finding_suffix: str,
    *args: str,
) -> tuple[str, str, int]:
    completed = run_inspector(
        audit,
        inspector,
        [
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.hooksPath=/dev/null",
            "-C",
            str(repo),
            *args,
        ],
        f"source:git:{finding_suffix}",
    )
    if completed is None:
        return "", "pinned Git command unavailable", 1
    return completed.stdout.strip(), completed.stderr.strip(), completed.returncode


@dataclass(frozen=True)
class GitTreeEntry:
    mode: str
    object_type: str
    object_id: str


def git_tree_entries(
    audit: Audit,
    inspector: PreparedInspector,
    repo: Path,
    revision: str,
    finding_suffix: str,
) -> dict[str, GitTreeEntry] | None:
    output, error, returncode = git_output(
        audit,
        inspector,
        repo,
        finding_suffix,
        "ls-tree",
        "-r",
        "-z",
        "--full-tree",
        revision,
    )
    parsed: dict[str, GitTreeEntry] = {}
    parse_errors: list[str] = []
    if returncode == 0:
        for record in output.split("\0"):
            if not record:
                continue
            try:
                header, relative = record.split("\t", 1)
                mode, object_type, object_id = header.split(" ", 2)
            except ValueError:
                parse_errors.append(repr(record[:160]))
                continue
            path = Path(relative)
            well_formed = (
                relative
                and not path.is_absolute()
                and ".." not in path.parts
                and relative not in parsed
                and bool(re.fullmatch(r"[0-7]{6}", mode))
                and object_type in {"blob", "commit"}
                and bool(HEX40.fullmatch(object_id))
            )
            if not well_formed:
                parse_errors.append(repr(relative))
                continue
            parsed[relative] = GitTreeEntry(mode, object_type, object_id)
    ok = returncode == 0 and not parse_errors
    audit.check(
        f"source:git:{finding_suffix}:format",
        ok,
        "complete NUL-delimited Git tree",
        error or parse_errors or f"{len(parsed)} entries",
    )
    return parsed if ok else None


def git_blob_oid(payload: bytes) -> str:
    header = f"blob {len(payload)}\0".encode("ascii")
    return hashlib.sha1(header + payload).hexdigest()


def display_filesystem_path(value: str) -> str:
    return os.fsencode(value).decode("utf-8", "backslashreplace")


def bounded_paths(values: list[str], limit: int = 100) -> dict[str, Any]:
    rendered = sorted(display_filesystem_path(value) for value in values)
    return {
        "count": len(rendered),
        "paths": rendered[:limit],
        "omitted": max(0, len(rendered) - limit),
    }


def compare_checkout_to_tree(
    repo: Path,
    expected: Mapping[str, GitTreeEntry],
    generated_roots: frozenset[str],
) -> tuple[bool, dict[str, Any]]:
    """Compare raw filesystem state to a commit tree without consulting Git's index."""
    seen: set[str] = set()
    mismatched: list[str] = []
    unexpected: list[str] = []
    errors: list[str] = []
    present_generated_roots: list[str] = []

    for tracked in expected:
        if any(tracked == root or tracked.startswith(f"{root}/") for root in generated_roots):
            errors.append(f"tracked path overlaps generated root: {tracked}")

    pending: list[tuple[Path, str]] = [(repo, "")]
    while pending:
        directory, prefix = pending.pop()
        try:
            with os.scandir(directory) as iterator:
                children = sorted(iterator, key=lambda item: os.fsencode(item.name))
        except OSError as exc:
            errors.append(f"cannot scan {prefix or '.'}: {exc}")
            continue
        for child in children:
            relative = f"{prefix}/{child.name}" if prefix else child.name
            if not prefix and child.name == ".git":
                continue
            try:
                metadata = child.stat(follow_symlinks=False)
            except OSError as exc:
                errors.append(f"cannot stat {display_filesystem_path(relative)}: {exc}")
                continue

            if relative in generated_roots:
                if stat.S_ISDIR(metadata.st_mode):
                    present_generated_roots.append(relative)
                else:
                    mismatched.append(relative)
                    errors.append(
                        f"generated root is not a real directory: "
                        f"{display_filesystem_path(relative)}"
                    )
                continue

            tree_entry = expected.get(relative)
            if stat.S_ISDIR(metadata.st_mode):
                if tree_entry is not None:
                    seen.add(relative)
                    mismatched.append(relative)
                else:
                    pending.append((Path(child.path), relative))
                continue

            if tree_entry is None:
                unexpected.append(relative)
                continue
            seen.add(relative)

            actual_mode: str | None = None
            actual_oid: str | None = None
            try:
                if stat.S_ISREG(metadata.st_mode):
                    actual_mode = (
                        "100755" if metadata.st_mode & stat.S_IXUSR else "100644"
                    )
                    actual_oid = git_blob_oid(Path(child.path).read_bytes())
                elif stat.S_ISLNK(metadata.st_mode):
                    actual_mode = "120000"
                    actual_oid = git_blob_oid(os.fsencode(os.readlink(child.path)))
            except OSError as exc:
                errors.append(f"cannot read {display_filesystem_path(relative)}: {exc}")
            if (
                tree_entry.object_type != "blob"
                or tree_entry.mode not in {"100644", "100755", "120000"}
                or actual_mode != tree_entry.mode
                or actual_oid != tree_entry.object_id
            ):
                mismatched.append(relative)

    missing = sorted(set(expected) - seen)
    unsupported = sorted(
        relative
        for relative, entry in expected.items()
        if entry.object_type != "blob" or entry.mode not in {"100644", "100755", "120000"}
    )
    clean = not (mismatched or unexpected or missing or unsupported or errors)
    return clean, {
        "mismatched": bounded_paths(mismatched),
        "missing": bounded_paths(missing),
        "unexpected": bounded_paths(unexpected),
        "unsupported": bounded_paths(unsupported),
        "errors": errors[:100],
        "errorCount": len(errors),
        "presentGeneratedRoots": sorted(present_generated_roots),
    }


def validate_source(
    audit: Audit,
    repo: Path,
    candidate: Any,
    policy: Policy,
    git_inspector: PreparedInspector | None,
    git_ready: bool,
) -> dict[str, Any]:
    snapshot: dict[str, Any] = {}
    if not isinstance(candidate, dict):
        audit.check("manifest:candidate", False, "object", type(candidate).__name__)
        return snapshot

    product_head = candidate.get("productHead")
    product_tree = candidate.get("productTree")
    base_head = candidate.get("baseHead")
    allowed = candidate.get("allowedPreparationDelta")
    generated = candidate.get("allowedGeneratedRoots")
    actual_identity = {
        "productHead": product_head,
        "productTree": product_tree,
        "baseHead": base_head,
    }
    expected_identity = {
        "productHead": policy.candidate_head,
        "productTree": policy.candidate_tree,
        "baseHead": policy.base_head,
    }
    audit.check(
        "candidate:frozen-identity",
        actual_identity == expected_identity,
        expected_identity,
        actual_identity,
    )
    audit.check(
        "candidate:product-head-format",
        isinstance(product_head, str) and bool(HEX40.fullmatch(product_head)),
        "40 lowercase hex",
        product_head,
    )
    audit.check(
        "candidate:product-tree-format",
        isinstance(product_tree, str) and bool(HEX40.fullmatch(product_tree)),
        "40 lowercase hex",
        product_tree,
    )
    audit.check(
        "candidate:base-head-format",
        isinstance(base_head, str) and bool(HEX40.fullmatch(base_head)),
        "40 lowercase hex",
        base_head,
    )
    allowed_well_formed = (
        isinstance(allowed, list)
        and all(
            isinstance(item, str)
            and item
            and not Path(item).is_absolute()
            and ".." not in Path(item).parts
            for item in allowed
        )
        and len(allowed) == len(set(allowed))
    )
    allowed_ok = allowed_well_formed and set(allowed) == policy.allowed_preparation_delta
    audit.check(
        "candidate:allowed-preparation-delta",
        allowed_ok,
        sorted(policy.allowed_preparation_delta),
        sorted(allowed) if isinstance(allowed, list) else allowed,
    )
    generated_well_formed = (
        isinstance(generated, list)
        and all(
            isinstance(item, str)
            and item
            and not Path(item).is_absolute()
            and ".." not in Path(item).parts
            for item in generated
        )
        and len(generated) == len(set(generated))
    )
    generated_ok = (
        generated_well_formed and set(generated) == policy.allowed_generated_roots
    )
    audit.check(
        "candidate:allowed-generated-roots",
        generated_ok,
        sorted(policy.allowed_generated_roots),
        sorted(generated) if isinstance(generated, list) else generated,
    )
    if not all(
        isinstance(value, str) and HEX40.fullmatch(value)
        for value in (product_head, product_tree, base_head)
    ):
        return snapshot

    if not git_ready or git_inspector is None:
        audit.check(
            "source:git-policy",
            False,
            "frozen Git executable and trust root validated before source inspection",
            "unavailable",
        )
        return snapshot

    if not validate_git_grafts_policy(audit, repo):
        snapshot.update(
            {
                "productHead": product_head,
                "productTree": None,
                "baseHead": None,
                "checkoutHead": None,
                "checkoutStatus": "NOT_INSPECTED_UNSAFE_GIT_METADATA",
                "preparationDelta": [],
            }
        )
        return snapshot

    if not validate_git_external_helper_policy(audit, git_inspector, repo):
        snapshot.update(
            {
                "productHead": product_head,
                "productTree": None,
                "baseHead": None,
                "checkoutHead": None,
                "checkoutStatus": "NOT_INSPECTED_UNSAFE_GIT_CONFIG",
                "preparationDelta": [],
            }
        )
        return snapshot

    inside, inside_err, inside_rc = git_output(
        audit, git_inspector, repo, "worktree", "rev-parse", "--is-inside-work-tree"
    )
    audit.check(
        "source:git-worktree",
        inside_rc == 0 and inside == "true",
        "true",
        inside or inside_err,
    )
    if inside_rc != 0:
        return snapshot

    current_head, current_err, current_rc = git_output(
        audit, git_inspector, repo, "head", "rev-parse", "HEAD"
    )
    actual_tree, tree_err, tree_rc = git_output(
        audit, git_inspector, repo, "tree", "rev-parse", f"{product_head}^{{tree}}"
    )
    actual_base, base_err, base_rc = git_output(
        audit, git_inspector, repo, "base", "rev-parse", f"{product_head}^"
    )
    _, ancestor_err, ancestor_rc = git_output(
        audit,
        git_inspector,
        repo,
        "ancestor",
        "merge-base",
        "--is-ancestor",
        product_head,
        "HEAD",
    )
    audit.check(
        "source:current-head-resolves",
        current_rc == 0 and bool(HEX40.fullmatch(current_head)),
        "40 lowercase hex",
        current_head or current_err,
    )
    audit.check(
        "source:product-tree",
        tree_rc == 0 and actual_tree == product_tree,
        product_tree,
        actual_tree or tree_err,
    )
    audit.check(
        "source:base-head",
        base_rc == 0 and actual_base == base_head,
        base_head,
        actual_base or base_err,
    )
    audit.check(
        "source:product-ancestor",
        ancestor_rc == 0,
        f"{product_head} ancestor of HEAD",
        ancestor_err or f"rc={ancestor_rc}",
    )

    checkout_tree = git_tree_entries(
        audit, git_inspector, repo, "HEAD", "checkout-tree-entries"
    )
    product_entries = git_tree_entries(
        audit, git_inspector, repo, product_head, "product-tree-entries"
    )
    if checkout_tree is None:
        checkout_clean = False
        checkout_details: dict[str, Any] = {"error": "HEAD tree unavailable"}
    else:
        checkout_clean, checkout_details = compare_checkout_to_tree(
            repo, checkout_tree, policy.allowed_generated_roots
        )
    audit.check(
        "source:checkout-tree",
        checkout_clean,
        {
            "tracked": "raw bytes/type/executable mode equal HEAD",
            "untracked": "none outside frozen generated roots",
        },
        checkout_details,
    )
    audit.check(
        "source:checkout-clean",
        checkout_clean,
        "raw checkout equals HEAD independent of index flags and ignore metadata",
        "clean" if checkout_clean else checkout_details,
    )
    if checkout_tree is not None and product_entries is not None:
        delta = {
            relative
            for relative in set(checkout_tree) | set(product_entries)
            if checkout_tree.get(relative) != product_entries.get(relative)
        }
        delta_ready = True
    else:
        delta = set()
        delta_ready = False
    unexpected = sorted(delta - policy.allowed_preparation_delta)
    audit.check(
        "source:working-tree-delta",
        delta_ready and checkout_clean and not unexpected,
        {
            "checkout": "raw-clean",
            "onlyCommittedPreparationDelta": sorted(policy.allowed_preparation_delta),
        },
        {
            "committedPreparationDelta": sorted(delta),
            "unexpected": unexpected,
            "checkout": checkout_details,
        },
    )
    snapshot.update(
        {
            "productHead": product_head,
            "productTree": actual_tree if tree_rc == 0 else None,
            "baseHead": actual_base if base_rc == 0 else None,
            "checkoutHead": current_head if current_rc == 0 else None,
            "checkoutStatus": "clean" if checkout_clean else checkout_details,
            "preparationDelta": sorted(delta),
        }
    )
    return snapshot


def validate_checkout_final_barrier(
    audit: Audit,
    repo: Path,
    policy: Policy,
    git_inspector: PreparedInspector | None,
    git_ready: bool,
    expected_checkout_head: str | None,
) -> tuple[bool, dict[str, Any]]:
    """Re-observe raw checkout state after all manifest-driven consumption."""
    if not git_ready or git_inspector is None:
        details = {"error": "frozen Git unavailable at final barrier"}
        audit.check(
            "source:checkout-final-barrier",
            False,
            "raw checkout equals HEAD at final barrier",
            details,
        )
        return False, details
    metadata_safe = validate_git_grafts_policy(
        audit, repo, "source:git-final:grafts-policy"
    )
    helpers_safe = validate_git_external_helper_policy(
        audit, git_inspector, repo, "source:git-final"
    )
    head_before, before_error, before_rc = git_output(
        audit,
        git_inspector,
        repo,
        "final-head-before",
        "rev-parse",
        "HEAD",
    )
    head_before_matches = (
        isinstance(expected_checkout_head, str)
        and bool(HEX40.fullmatch(expected_checkout_head))
        and before_rc == 0
        and head_before == expected_checkout_head
    )
    checkout_tree = git_tree_entries(
        audit,
        git_inspector,
        repo,
        expected_checkout_head or "INVALID_EXPECTED_HEAD",
        "checkout-final-tree-entries",
    )
    if checkout_tree is None:
        checkout_clean = False
        checkout_details = {"error": "frozen checkout tree unavailable at final barrier"}
    else:
        checkout_clean, checkout_details = compare_checkout_to_tree(
            repo, checkout_tree, policy.allowed_generated_roots
        )
    head_after, after_error, after_rc = git_output(
        audit,
        git_inspector,
        repo,
        "final-head-after",
        "rev-parse",
        "HEAD",
    )
    head_after_matches = (
        after_rc == 0
        and head_after == expected_checkout_head
        and head_after == head_before
    )
    clean = (
        metadata_safe
        and helpers_safe
        and head_before_matches
        and checkout_clean
        and head_after_matches
    )
    details = {
        "expectedCheckoutHead": expected_checkout_head,
        "headBefore": head_before or before_error or None,
        "headAfter": head_after or after_error or None,
        "metadataSafe": metadata_safe,
        "helpersSafe": helpers_safe,
        "checkout": checkout_details,
    }
    audit.check(
        "source:checkout-final-barrier",
        clean,
        {
            "tracked": "raw bytes/type/executable mode equal HEAD",
            "untracked": "none outside frozen generated roots",
            "observation": "after manifest-driven inspection",
            "checkoutHead": expected_checkout_head,
        },
        details,
    )
    return clean, details


def inspect_frozen_file(item: FrozenFile) -> tuple[bool, dict[str, Any]]:
    try:
        path = item.path.resolve(strict=True)
        exists = path.is_file()
        actual_sha = sha256_file(path) if exists else None
        executable_ok = not item.executable or (exists and os.access(path, os.X_OK))
        error = None
    except (OSError, RuntimeError, ValueError) as exc:
        path = item.path
        exists = False
        actual_sha = None
        executable_ok = False
        error = str(exc)
    return (
        exists and actual_sha == item.sha256 and executable_ok,
        {
            "role": item.role,
            "path": str(path),
            "expectedSha256": item.sha256,
            "actualSha256": actual_sha,
            "executable": executable_ok,
            "error": error,
        },
    )


def inspect_frozen_tree(item: FrozenTree) -> tuple[bool, dict[str, Any]]:
    try:
        path = item.path.resolve(strict=True)
        actual_sha = sha256_tree(path)
        error = None
    except (OSError, RuntimeError, ValueError) as exc:
        path = item.path
        actual_sha = None
        error = str(exc)
    return (
        actual_sha == item.sha256,
        {
            "role": item.role,
            "path": str(path),
            "expectedSha256": item.sha256,
            "actualSha256": actual_sha,
            "error": error,
        },
    )


def mapped_tree_path(
    source: Path,
    tree_copies: Mapping[Path, Path],
) -> Path | None:
    for source_root, snapshot_root in sorted(
        tree_copies.items(), key=lambda item: len(item[0].parts), reverse=True
    ):
        if source == source_root or is_within(source, source_root):
            return snapshot_root / source.relative_to(source_root)
    return None


def remap_frozen_path(
    raw: str,
    tree_copies: Mapping[Path, Path],
    file_copies: Mapping[Path, Path],
    directory_copies: Mapping[Path, Path],
) -> str:
    path = Path(raw)
    if not path.is_absolute():
        return raw
    resolved = path.resolve()
    mapped = file_copies.get(resolved)
    if mapped is None:
        mapped = mapped_tree_path(resolved, tree_copies)
    if mapped is None:
        mapped = directory_copies.get(resolved)
    return str(mapped) if mapped is not None else raw


def prepare_inspectors(
    audit: Audit,
    inspectors: tuple[InspectorPolicy, ...],
    snapshot_root: Path,
    source_seals: SourceSealRegistry,
) -> tuple[bool, dict[str, PreparedInspector], list[dict[str, Any]]]:
    all_ok = True
    snapshots: list[dict[str, Any]] = []
    snapshot_by_id: dict[str, dict[str, Any]] = {}
    inspector_ids = [item.inspector_id for item in inspectors]
    audit.check(
        "tool:inspector-ids",
        len(inspector_ids) == len(set(inspector_ids)),
        "unique inspector ids",
        inspector_ids,
    )
    all_ok = all_ok and len(inspector_ids) == len(set(inspector_ids))
    for inspector in inspectors:
        file_snapshots: list[dict[str, Any]] = []
        inspector_ok = True
        for frozen in (inspector.executable, *inspector.support_files):
            ok, snapshot = inspect_frozen_file(frozen)
            file_snapshots.append(snapshot)
            audit.check(
                f"tool:{inspector.inspector_id}:{frozen.role}:sha256",
                ok,
                {"sha256": frozen.sha256, "executable": frozen.executable},
                {
                    "sha256": snapshot["actualSha256"],
                    "executable": snapshot["executable"],
                },
            )
            inspector_ok = inspector_ok and ok
        tree_snapshots: list[dict[str, Any]] = []
        for frozen_tree in inspector.support_trees:
            ok, snapshot = inspect_frozen_tree(frozen_tree)
            tree_snapshots.append(snapshot)
            audit.check(
                f"tool:{inspector.inspector_id}:{frozen_tree.role}:tree-sha256",
                ok,
                frozen_tree.sha256,
                snapshot["actualSha256"] or snapshot["error"],
            )
            inspector_ok = inspector_ok and ok
        snapshot = {
            "id": inspector.inspector_id,
            "version": inspector.version,
            "files": file_snapshots,
            "trees": tree_snapshots,
            "argumentsPrefix": list(inspector.arguments_prefix),
            "environmentKeys": sorted(dict(inspector.environment)),
            "policyStatus": "PASS" if inspector_ok else "FAIL",
            "snapshotStatus": "NOT_CREATED_POLICY_INVALID",
        }
        snapshots.append(snapshot)
        snapshot_by_id[inspector.inspector_id] = snapshot
        all_ok = all_ok and inspector_ok
    if not all_ok:
        return False, {}, snapshots

    tree_items: dict[Path, FrozenTree] = {}
    tree_conflicts: list[str] = []
    for inspector in inspectors:
        for item in inspector.support_trees:
            resolved = item.path.resolve(strict=True)
            existing = tree_items.get(resolved)
            if existing is not None and existing.sha256 != item.sha256:
                tree_conflicts.append(str(resolved))
            tree_items[resolved] = item
    audit.check(
        "tool-snapshot:tree-policy",
        not tree_conflicts,
        "one digest per frozen tree root",
        tree_conflicts or "consistent",
    )
    if tree_conflicts:
        return False, {}, snapshots

    tree_copies: dict[Path, Path] = {}
    tree_seals: dict[Path, TreeSourceSeal] = {}
    closure_ok = True
    trees_root = snapshot_root / "trees"
    trees_root.mkdir(parents=True, exist_ok=True)
    ordered_trees = sorted(
        tree_items.items(), key=lambda item: (len(item[0].parts), str(item[0]))
    )
    for index, (resolved, item) in enumerate(ordered_trees):
        try:
            opening = capture_tree_source(item.path)
            destination = mapped_tree_path(resolved, tree_copies)
            if destination is None:
                destination = trees_root / f"tree-{index:02d}"
                shutil.copytree(
                    opening.resolved_path,
                    destination,
                    symlinks=True,
                    copy_function=shutil.copy2,
                )
            snapshot_sha = sha256_tree(destination)
            escaping_links = escaping_snapshot_symlinks(destination)
            copied = (
                opening.sha256 == item.sha256
                and snapshot_sha == item.sha256
                and not escaping_links
            )
            actual: Any = {
                "sourceSha256": opening.sha256,
                "snapshotSha256": snapshot_sha,
                "snapshotLocation": "PRIVATE_AUDIT_ROOT",
                "escapingSymlinks": escaping_links,
            }
            if copied:
                tree_copies[resolved] = destination
                tree_seals[resolved] = opening
        except (OSError, RuntimeError, ValueError) as exc:
            copied = False
            actual = str(exc)
        audit.check(
            f"tool-snapshot:tree:{index:02d}",
            copied,
            item.sha256,
            actual,
        )
        closure_ok = closure_ok and copied

    all_files: dict[Path, FrozenFile] = {}
    file_conflicts: list[str] = []
    for inspector in inspectors:
        for item in (inspector.executable, *inspector.support_files):
            resolved = item.path.resolve(strict=True)
            existing = all_files.get(resolved)
            if existing is not None and (
                existing.sha256 != item.sha256 or existing.executable != item.executable
            ):
                file_conflicts.append(str(resolved))
            all_files[resolved] = item
    audit.check(
        "tool-snapshot:file-policy",
        not file_conflicts,
        "one digest and executable policy per frozen file",
        file_conflicts or "consistent",
    )
    if file_conflicts:
        return False, {}, snapshots

    standalone_parents = sorted(
        {
            resolved.parent
            for resolved in all_files
            if mapped_tree_path(resolved, tree_copies) is None
        },
        key=str,
    )
    directory_copies = {
        parent: snapshot_root / "file-groups" / f"group-{index:02d}"
        for index, parent in enumerate(standalone_parents)
    }
    for destination in directory_copies.values():
        destination.mkdir(parents=True, exist_ok=True)
    preserved_os_directories = {Path("/usr/bin").resolve(), Path("/bin").resolve()}
    environment_directory_copies = {
        source: destination
        for source, destination in directory_copies.items()
        if source not in preserved_os_directories
    }

    file_copies: dict[Path, Path] = {}
    file_seals: dict[Path, FileSourceSeal] = {}
    for index, (resolved, item) in enumerate(sorted(all_files.items(), key=lambda x: str(x[0]))):
        destination = mapped_tree_path(resolved, tree_copies)
        try:
            if destination is None:
                destination = directory_copies[resolved.parent] / resolved.name
                opening = source_seals.capture_file_source(item.path, destination)
            else:
                opening = source_seals.capture_file_source(item.path)
            snapshot_sha = sha256_file(destination)
            executable_ok = not item.executable or os.access(destination, os.X_OK)
            copied = (
                opening.sha256 == item.sha256
                and snapshot_sha == item.sha256
                and executable_ok
            )
            actual = {
                "sourceSha256": opening.sha256,
                "snapshotSha256": snapshot_sha,
                "snapshotLocation": "PRIVATE_AUDIT_ROOT",
                "executable": executable_ok,
            }
            if copied:
                file_copies[resolved] = destination
                file_seals[resolved] = opening
        except (OSError, RuntimeError, ValueError) as exc:
            copied = False
            actual = str(exc)
        audit.check(
            f"tool-snapshot:file:{index:02d}",
            copied,
            {"sha256": item.sha256, "executable": item.executable},
            actual,
        )
        closure_ok = closure_ok and copied

    if not closure_ok:
        return False, {}, snapshots

    prepared: dict[str, PreparedInspector] = {}
    for inspector in inspectors:
        executable_source = inspector.executable.path.resolve(strict=True)
        support_files = tuple(
            (item, file_copies[item.path.resolve(strict=True)])
            for item in inspector.support_files
        )
        support_trees = tuple(
            (item, tree_copies[item.path.resolve(strict=True)])
            for item in inspector.support_trees
        )
        arguments_prefix = tuple(
            remap_frozen_path(
                argument, tree_copies, file_copies, directory_copies
            )
            for argument in inspector.arguments_prefix
        )
        environment_items: list[tuple[str, str]] = []
        for key, value in inspector.environment:
            if key == "PATH":
                remapped = os.pathsep.join(
                    remap_frozen_path(
                        component,
                        tree_copies,
                        file_copies,
                        environment_directory_copies,
                    )
                    for component in value.split(os.pathsep)
                )
            else:
                remapped = remap_frozen_path(
                    value,
                    tree_copies,
                    file_copies,
                    environment_directory_copies,
                )
            environment_items.append((key, remapped))
        prepared_item = PreparedInspector(
            policy=inspector,
            executable_path=file_copies[executable_source],
            support_files=support_files,
            support_trees=support_trees,
            arguments_prefix=arguments_prefix,
            environment=tuple(environment_items),
        )
        remapped_path_values = list(arguments_prefix)
        for key, value in prepared_item.environment:
            remapped_path_values.extend(
                value.split(os.pathsep) if key == "PATH" else [value]
            )
        shared_references: list[str] = []
        for value in remapped_path_values:
            candidate = Path(value)
            if not candidate.is_absolute():
                continue
            resolved_candidate = candidate.resolve()
            if resolved_candidate in all_files or any(
                resolved_candidate == root or is_within(resolved_candidate, root)
                for root in tree_items
            ):
                shared_references.append(value)
        remap_ok = not shared_references
        audit.check(
            f"tool:{inspector.inspector_id}:snapshot-path-remap",
            remap_ok,
            "no argv/environment reference to a shared frozen file or tree",
            shared_references or "private-or-explicit-OS-boundary",
        )
        closure_ok = closure_ok and remap_ok
        prepared[inspector.inspector_id] = prepared_item
        finding_id = f"tool:{inspector.inspector_id}:source-stable"
        for item in (inspector.executable, *inspector.support_files):
            source_seals.register_file(
                finding_id, file_seals[item.path.resolve(strict=True)]
            )
        for item in inspector.support_trees:
            source_seals.register_tree(
                finding_id, tree_seals[item.path.resolve(strict=True)]
            )
        snapshot = snapshot_by_id[inspector.inspector_id]
        bootstrap_only = inspector.inspector_id == "python-bootstrap"
        snapshot.update(
            {
                "snapshotStatus": "PASS",
                "executionSource": (
                    "SHARED_PINNED_BOOTSTRAP_PROCESS"
                    if bootstrap_only
                    else "PRIVATE_VERIFIED_SNAPSHOT"
                ),
                "snapshotPurpose": (
                    "EVIDENCE_ONLY_NOT_REEXECUTED"
                    if bootstrap_only
                    else "EXECUTION_CLOSURE"
                ),
                "privateSupportFileCount": len(support_files),
                "privateSupportTreeCount": len(support_trees),
                "argumentsPrefixRemapped": arguments_prefix
                != inspector.arguments_prefix,
                "environmentRemappedKeys": sorted(
                    key
                    for (key, original), (_, remapped) in zip(
                        inspector.environment, prepared_item.environment
                    )
                    if original != remapped
                ),
            }
        )
    return closure_ok, prepared if closure_ok else {}, snapshots


def stable_snapshot_argument(inspector: PreparedInspector, argument: str) -> str:
    """Describe a private argv path without persisting its ephemeral root."""
    candidate = Path(argument)
    if not candidate.is_absolute():
        return argument
    try:
        resolved = candidate.resolve(strict=True)
    except OSError:
        return argument
    executable = inspector.executable_path.resolve(strict=True)
    if resolved == executable:
        return "$TOOL_SNAPSHOT:executable"
    for item, private_path in inspector.support_files:
        if resolved == private_path.resolve(strict=True):
            return f"$TOOL_SNAPSHOT:{item.role}"
    for item, private_root in inspector.support_trees:
        resolved_root = private_root.resolve(strict=True)
        if resolved == resolved_root or is_within(resolved, resolved_root):
            relative = resolved.relative_to(resolved_root).as_posix()
            suffix = f"/{relative}" if relative != "." else ""
            return f"$TOOL_SNAPSHOT:{item.role}{suffix}"
    return argument


def run_inspector(
    audit: Audit,
    inspector: PreparedInspector,
    args: list[str],
    finding_id: str,
    *,
    pass_fds: tuple[int, ...] = (),
    evidence_args: list[str] | None = None,
) -> subprocess.CompletedProcess[str] | None:
    try:
        files_valid = (
            sha256_file(inspector.executable_path) == inspector.policy.executable.sha256
            and (
                not inspector.policy.executable.executable
                or os.access(inspector.executable_path, os.X_OK)
            )
            and all(
                sha256_file(path) == item.sha256
                and (not item.executable or os.access(path, os.X_OK))
                for item, path in inspector.support_files
            )
        )
        trees_valid = all(
            sha256_tree(path) == item.sha256 for item, path in inspector.support_trees
        )
        validation_error = None
    except (OSError, RuntimeError, ValueError) as exc:
        files_valid = False
        trees_valid = False
        validation_error = str(exc)
    still_valid = files_valid and trees_valid
    audit.check(
        f"{finding_id}:pre-exec-snapshot",
        still_valid,
        "all private inspector snapshot bytes unchanged",
        "unchanged" if still_valid else validation_error or "drifted",
    )
    if not still_valid:
        return None
    command = [
        str(inspector.executable_path),
        *inspector.arguments_prefix,
        *args,
    ]
    recorded_arguments = [
        *(
            stable_snapshot_argument(inspector, argument)
            for argument in inspector.arguments_prefix
        ),
        *(evidence_args if evidence_args is not None else args),
    ]
    record = audit.begin_command(inspector, command, recorded_arguments)
    dispatch_allowed = record["classification"] == "NON_DEVICE_INSPECTOR"
    audit.check(
        f"{finding_id}:direct-command-classification",
        dispatch_allowed,
        "known pinned non-device inspector classification",
        record["classification"],
    )
    if not dispatch_allowed:
        return None
    try:
        completed_raw = run(
            command,
            environment=dict(inspector.environment),
            pass_fds=pass_fds,
        )
    except OSError as exc:
        audit.check(
            f"{finding_id}:spawn",
            False,
            "private inspector process spawned",
            str(exc),
        )
        return None
    audit.finish_command(record, completed_raw)
    audit.check(
        f"{finding_id}:spawn",
        True,
        "private inspector process spawned",
        "spawned",
    )
    try:
        stdout = completed_raw.stdout.decode("utf-8")
        stderr = completed_raw.stderr.decode("utf-8")
        record["outputUtf8"] = True
    except UnicodeError as exc:
        record["outputUtf8"] = False
        audit.check(
            f"{finding_id}:output-encoding",
            False,
            "strict UTF-8 inspector stdout/stderr",
            str(exc),
        )
        return None
    audit.check(
        f"{finding_id}:output-encoding",
        True,
        "strict UTF-8 inspector stdout/stderr",
        "valid",
    )
    completed = subprocess.CompletedProcess(
        args=completed_raw.args,
        returncode=completed_raw.returncode,
        stdout=stdout,
        stderr=stderr,
    )
    return completed


def validate_artifacts(
    audit: Audit,
    repo: Path,
    entries: Any,
    policy: Policy,
    inspectors: Mapping[str, PreparedInspector],
    inspectors_ready: bool,
    snapshot_root: Path,
    source_seals: SourceSealRegistry,
) -> list[dict[str, Any]]:
    snapshots: list[dict[str, Any]] = []
    artifact_ids, unique = ids(entries)
    audit.check(
        "manifest:artifact-ids",
        unique and artifact_ids == policy.artifact_ids,
        sorted(policy.artifact_ids),
        sorted(artifact_ids),
    )
    if not isinstance(entries, list):
        return snapshots
    aapt = inspectors.get("aapt")
    apksigner = inspectors.get("apksigner")
    can_inspect = inspectors_ready and aapt is not None and apksigner is not None
    artifacts_root = snapshot_root / "artifacts"
    artifacts_root.mkdir(parents=True, exist_ok=True)

    for index, entry in enumerate(entries):
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        artifact_id = entry["id"]
        expected_sha = entry.get("sha256")
        expected_size = entry.get("sizeBytes")
        expected_signer = entry.get("signerSha256")
        audit.check(
            f"artifact:{artifact_id}:sha-format",
            isinstance(expected_sha, str) and bool(HEX64.fullmatch(expected_sha)),
            "64 lowercase hex",
            expected_sha,
        )
        audit.check(
            f"artifact:{artifact_id}:signer-format",
            isinstance(expected_signer, str) and bool(HEX64.fullmatch(expected_signer)),
            "64 lowercase hex",
            expected_signer,
        )
        try:
            safe_relative(repo, entry.get("relativePath"))
        except ValueError as exc:
            audit.check(
                f"artifact:{artifact_id}:path", False, "safe repo-relative file", str(exc)
            )
            continue
        private_path = artifacts_root / f"artifact-{index:02d}.apk"
        try:
            opening = source_seals.capture_repo_file(
                entry.get("relativePath"), private_path
            )
            source_seals.register_file(
                f"artifact:{artifact_id}:source-stable", opening
            )
            audit.check(f"artifact:{artifact_id}:exists", True, True, True)
        except (OSError, RuntimeError, ValueError) as exc:
            audit.check(f"artifact:{artifact_id}:exists", False, True, str(exc))
            audit.check(
                f"artifact:{artifact_id}:snapshot",
                False,
                "one unlinked private inode for every artifact observation",
                str(exc),
            )
            continue
        reader_fds: tuple[int, ...] = ()
        try:
            reader_fds = open_unlinked_snapshot_readers(
                private_path, 3, source_seals
            )
            for descriptor in reader_fds:
                source_seals.track_descriptor(descriptor)
            snapshot_digests = [
                sha256_descriptor(descriptor) for descriptor in reader_fds
            ]
            snapshot_sizes = [os.fstat(descriptor).st_size for descriptor in reader_fds]
            snapshot_valid = (
                snapshot_digests == [opening.sha256] * 3
                and snapshot_sizes == [opening.size] * 3
            )
            snapshot_actual: Any = {
                "sourceSha256": opening.sha256,
                "sourceSize": opening.size,
                "readerSha256": snapshot_digests,
                "readerSize": snapshot_sizes,
                "linkedNames": 0,
            }
        except (OSError, RuntimeError, ValueError) as exc:
            snapshot_valid = False
            snapshot_actual = str(exc)
        audit.check(
            f"artifact:{artifact_id}:snapshot",
            snapshot_valid,
            "one unlinked private inode for every artifact observation",
            snapshot_actual,
        )
        actual_sha = opening.sha256
        actual_size = opening.size
        sha_matches = actual_sha == expected_sha
        size_matches = isinstance(expected_size, int) and actual_size == expected_size
        audit.check(
            f"artifact:{artifact_id}:sha256",
            sha_matches,
            expected_sha,
            actual_sha,
        )
        audit.check(
            f"artifact:{artifact_id}:size",
            size_matches,
            expected_size,
            actual_size,
        )

        package_fields: dict[str, str | None] = {
            "packageName": None,
            "versionCode": None,
            "versionName": None,
        }
        signer: str | None = None
        if (
            snapshot_valid
            and sha_matches
            and size_matches
            and len(reader_fds) == 3
            and can_inspect
            and aapt is not None
            and apksigner is not None
        ):
            _, aapt_fd, apksigner_fd = reader_fds
            inspected = run_inspector(
                audit,
                aapt,
                ["dump", "badging", f"/dev/fd/{aapt_fd}"],
                f"artifact:{artifact_id}:aapt",
                pass_fds=(aapt_fd,),
                evidence_args=[
                    "dump",
                    "badging",
                    f"$ARTIFACT_SNAPSHOT:{artifact_id}",
                ],
            )
            if inspected is not None:
                lines = inspected.stdout.splitlines()
                line = lines[0] if lines else ""
                match = re.search(
                    r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
                    line,
                )
                audit.check(
                    f"artifact:{artifact_id}:aapt-output",
                    inspected.returncode == 0 and match is not None,
                    "parseable package badging",
                    inspected.stderr or line or f"rc={inspected.returncode}",
                )
                if inspected.returncode == 0 and match is not None:
                    package_fields = dict(zip(package_fields, match.groups()))
                for field, actual in package_fields.items():
                    audit.check(
                        f"artifact:{artifact_id}:{field}",
                        actual == str(entry.get(field)),
                        str(entry.get(field)),
                        actual,
                    )

            inspected = run_inspector(
                audit,
                apksigner,
                ["verify", "--print-certs", f"/dev/fd/{apksigner_fd}"],
                f"artifact:{artifact_id}:apksigner",
                pass_fds=(apksigner_fd,),
                evidence_args=[
                    "verify",
                    "--print-certs",
                    f"$ARTIFACT_SNAPSHOT:{artifact_id}",
                ],
            )
            if inspected is not None:
                match = re.search(
                    r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]{64,95})",
                    inspected.stdout,
                )
                audit.check(
                    f"artifact:{artifact_id}:apksigner-output",
                    inspected.returncode == 0 and match is not None,
                    "parseable certificate digest",
                    inspected.stderr or inspected.stdout or f"rc={inspected.returncode}",
                )
                if inspected.returncode == 0 and match is not None:
                    signer = match.group(1).replace(":", "").lower()
                audit.check(
                    f"artifact:{artifact_id}:signer",
                    signer == expected_signer,
                    expected_signer,
                    signer,
                )

        try:
            private_stable = bool(reader_fds) and all(
                sha256_descriptor(descriptor) == actual_sha
                and os.fstat(descriptor).st_size == actual_size
                and os.fstat(descriptor).st_nlink == 0
                for descriptor in reader_fds
            )
        except OSError:
            private_stable = False
        audit.check(
            f"artifact:{artifact_id}:snapshot-stable",
            private_stable,
            {"sha256": actual_sha, "sizeBytes": actual_size},
            "unchanged" if private_stable else "drifted",
        )
        for descriptor in reader_fds:
            source_seals.close_descriptor(descriptor)

        snapshots.append(
            {
                "id": artifact_id,
                "relativePath": entry.get("relativePath"),
                "sha256": actual_sha,
                "sizeBytes": actual_size,
                **package_fields,
                "signerSha256": signer,
            }
        )
    return snapshots


def validate_inputs(
    audit: Audit,
    repo: Path,
    entries: Any,
    policy: Policy,
    source_seals: SourceSealRegistry,
) -> list[dict[str, Any]]:
    snapshots: list[dict[str, Any]] = []
    input_ids, unique = ids(entries)
    audit.check(
        "manifest:input-ids",
        unique and input_ids == policy.input_ids,
        sorted(policy.input_ids),
        sorted(input_ids),
    )
    if not isinstance(entries, list):
        return snapshots
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        input_id = entry["id"]
        expected_sha = entry.get("sha256")
        audit.check(
            f"input:{input_id}:sha-format",
            isinstance(expected_sha, str) and bool(HEX64.fullmatch(expected_sha)),
            "64 lowercase hex",
            expected_sha,
        )
        try:
            safe_relative(repo, entry.get("relativePath"))
        except ValueError as exc:
            audit.check(
                f"input:{input_id}:path", False, "safe repo-relative file", str(exc)
            )
            continue
        try:
            opening = source_seals.capture_repo_file(entry.get("relativePath"))
            actual_sha = opening.sha256
            source_seals.register_file(f"input:{input_id}:source-stable", opening)
            audit.check(f"input:{input_id}:exists", True, True, True)
            stable_read = True
            stable_actual: Any = {
                "sha256": actual_sha,
                "sizeBytes": opening.size,
            }
        except (OSError, RuntimeError, ValueError) as exc:
            audit.check(f"input:{input_id}:exists", False, True, str(exc))
            stable_read = False
            stable_actual = str(exc)
            actual_sha = None
        audit.check(
            f"input:{input_id}:stable-read",
            stable_read,
            "one stable regular-file generation",
            stable_actual,
        )
        audit.check(
            f"input:{input_id}:sha256",
            actual_sha == expected_sha,
            expected_sha,
            actual_sha,
        )
        snapshots.append(
            {"id": input_id, "relativePath": entry.get("relativePath"), "sha256": actual_sha}
        )
    return snapshots


def validate_readiness(
    audit: Audit,
    readiness: Any,
    policy: Policy,
) -> tuple[list[dict[str, Any]], list[str]]:
    if not isinstance(readiness, dict):
        audit.check("manifest:readiness", False, "object", type(readiness).__name__)
        return [], []
    blockers = readiness.get("blockers")
    authorizations = readiness.get("operatorAuthorizationRequired")
    blocker_ids, unique = ids(blockers)
    expected_scopes = policy.blocker_scope_map()
    audit.check(
        "policy:required-blockers",
        unique and blocker_ids == set(expected_scopes),
        sorted(expected_scopes),
        sorted(blocker_ids),
    )
    actual_scopes: dict[str, frozenset[str]] = {}
    scopes_well_formed = isinstance(blockers, list)
    if isinstance(blockers, list):
        for item in blockers:
            if not isinstance(item, dict) or not isinstance(item.get("id"), str):
                scopes_well_formed = False
                continue
            scope = item.get("scope")
            if (
                not isinstance(scope, list)
                or not scope
                or len(scope) != len(set(scope))
                or not all(isinstance(value, str) and value for value in scope)
            ):
                scopes_well_formed = False
                continue
            actual_scopes[item["id"]] = frozenset(scope)
    audit.check(
        "policy:blocker-scopes",
        scopes_well_formed and actual_scopes == expected_scopes,
        {key: sorted(value) for key, value in sorted(expected_scopes.items())},
        {key: sorted(value) for key, value in sorted(actual_scopes.items())},
    )
    auth_ok = (
        isinstance(authorizations, list)
        and len(authorizations) == len(set(authorizations))
        and set(authorizations) == policy.required_authorizations
    )
    audit.check(
        "policy:operator-authorizations",
        auth_ok,
        sorted(policy.required_authorizations),
        sorted(authorizations) if isinstance(authorizations, list) else authorizations,
    )
    expected_disposition = dict(policy.scope_disposition)
    audit.check(
        "policy:scope-disposition",
        readiness.get("scopeDisposition") == expected_disposition,
        expected_disposition,
        readiness.get("scopeDisposition"),
    )
    expected_ledger = dict(policy.canonical_ledger)
    audit.check(
        "policy:canonical-ledger",
        readiness.get("canonicalLedger") == expected_ledger,
        expected_ledger,
        readiness.get("canonicalLedger"),
    )
    audit.check(
        "policy:go-no-go",
        readiness.get("goNoGo") == policy.go_no_go,
        policy.go_no_go,
        readiness.get("goNoGo"),
    )
    return (
        blockers if isinstance(blockers, list) else [],
        authorizations if isinstance(authorizations, list) else [],
    )


def run_audit(
    *,
    policy: Policy,
    manifest_path: Path,
    source_repo: Path,
    report_path: Path,
    fail_on_blocked: bool = False,
) -> int:
    source_repo = source_repo.resolve()
    manifest_path = manifest_path.resolve()
    report_path = report_path.resolve()
    sidecar_path = Path(f"{report_path}.sha256").resolve()
    output_error = unsafe_output_reason(
        policy,
        source_repo,
        manifest_path,
        report_path,
        sidecar_path,
    )
    if output_error is not None:
        print(
            f"check-github64-device-readiness: unsafe output path: {output_error}",
            file=sys.stderr,
        )
        return 2

    try:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        snapshot_directory = tempfile.TemporaryDirectory(
            prefix=".github64-audit-snapshots-",
            dir=report_path.parent,
        )
        snapshot_root = Path(snapshot_directory.name).resolve(strict=True)
        os.chmod(snapshot_root, 0o700)
    except OSError as exc:
        print(
            f"check-github64-device-readiness: cannot create private snapshot root: {exc}",
            file=sys.stderr,
        )
        return 2

    audit = Audit()
    manifest: dict[str, Any] = {}
    manifest_sha: str | None = None
    source_seals: SourceSealRegistry | None = None
    try:
        source_seals = SourceSealRegistry(source_repo)
        if is_within(manifest_path, source_repo):
            manifest_opening = source_seals.capture_repo_file(
                manifest_path.relative_to(source_repo).as_posix()
            )
        else:
            manifest_opening = source_seals.capture_file_source(manifest_path)
        source_seals.register_file("manifest:source-stable", manifest_opening)
        if manifest_opening.descriptor is None:
            raise OSError("manifest source descriptor was not retained")
        raw = read_descriptor(manifest_opening.descriptor)
        manifest_sha = hashlib.sha256(raw).hexdigest()
        if manifest_sha != manifest_opening.sha256:
            raise OSError("manifest changed between capture and decode")
        decoded = json.loads(raw)
        if isinstance(decoded, dict):
            manifest = decoded
        else:
            audit.check("manifest:root", False, "object", type(decoded).__name__)
    except (OSError, RuntimeError, UnicodeError, ValueError) as exc:
        audit.check("manifest:read", False, "readable JSON", str(exc))

    frozen_manifest = audit.check(
        "manifest:frozen-identity",
        manifest_sha == policy.manifest_sha256,
        policy.manifest_sha256,
        manifest_sha,
    )
    audit.check(
        "manifest:schema-version",
        manifest.get("schemaVersion") == SCHEMA_VERSION,
        SCHEMA_VERSION,
        manifest.get("schemaVersion"),
    )
    audit.check(
        "manifest:package-id",
        manifest.get("packageId") == PACKAGE_ID,
        PACKAGE_ID,
        manifest.get("packageId"),
    )

    inspector_snapshots: list[dict[str, Any]] = []
    source_snapshot: dict[str, Any] = {}
    artifact_snapshots: list[dict[str, Any]] = []
    input_snapshots: list[dict[str, Any]] = []
    blockers: list[dict[str, Any]] = []
    authorizations: list[str] = []
    cleanup_errors: list[str] = []
    try:
        if source_seals is None:
            raise RuntimeError("manifest/source seal registry unavailable")
        inspectors_ready, inspector_map, inspector_snapshots = prepare_inspectors(
            audit,
            policy.inspectors,
            snapshot_root,
            source_seals,
        )
        audit.check(
            "manifest:trusted-path-selection",
            frozen_manifest,
            "only the exact frozen manifest may select input or artifact paths",
            "trusted" if frozen_manifest else "path consumption skipped",
        )
        if frozen_manifest:
            input_snapshots = validate_inputs(
                audit,
                source_repo,
                manifest.get("inputs"),
                policy,
                source_seals,
            )
        source_snapshot = validate_source(
            audit,
            source_repo,
            manifest.get("candidate"),
            policy,
            inspector_map.get("git"),
            inspectors_ready and frozen_manifest,
        )
        if frozen_manifest:
            artifact_snapshots = validate_artifacts(
                audit,
                source_repo,
                manifest.get("artifacts"),
                policy,
                inspector_map,
                inspectors_ready,
                snapshot_root,
                source_seals,
            )
        blockers, authorizations = validate_readiness(
            audit, manifest.get("readiness"), policy
        )
        final_checkout_clean, final_checkout_details = validate_checkout_final_barrier(
            audit,
            source_repo,
            policy,
            inspector_map.get("git"),
            inspectors_ready and frozen_manifest,
            source_snapshot.get("checkoutHead") if source_snapshot else None,
        )
        if source_snapshot:
            source_snapshot["checkoutStatus"] = (
                "clean-at-final-barrier"
                if final_checkout_clean
                else final_checkout_details
            )
        source_seals.verify(audit)
    except Exception as exc:
        audit.check(
            "audit:fail-closed-execution",
            False,
            "all snapshot and validation phases completed",
            f"{type(exc).__name__}: {exc}",
        )
    finally:
        if source_seals is not None:
            for attempt in (1, 2):
                try:
                    source_seals.close()
                    break
                except OSError as exc:
                    cleanup_errors.append(
                        f"source descriptors close attempt {attempt}: {exc}"
                    )
        try:
            snapshot_directory.cleanup()
        except OSError as exc:
            cleanup_errors.append(f"private snapshot root: {exc}")
    audit.check(
        "snapshot:cleanup",
        not cleanup_errors,
        (
            "private snapshot root and all registered retained/source-copy "
            "descriptors closed before report creation"
        ),
        cleanup_errors or "complete",
    )
    executed_device_commands = audit.executed_device_commands
    audit.check(
        "device:direct-command-count",
        executed_device_commands == 0,
        0,
        executed_device_commands,
    )

    host_status = "PASS" if audit.passed else "FAIL"
    overall_status = "BLOCKED" if audit.passed else "INVALID"
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "packageId": PACKAGE_ID,
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "manifestPath": str(manifest_path),
        "manifestSha256": manifest_sha,
        "sourceRepo": str(source_repo),
        "source": source_snapshot,
        "artifacts": artifact_snapshots,
        "inputs": input_snapshots,
        "hostTools": inspector_snapshots,
        "hostStatus": host_status,
        "overallStatus": overall_status,
        "deviceAccess": (
            "NO_DIRECT_DEVICE_TRANSPORT_DISPATCHED"
            if executed_device_commands == 0
            else "DIRECT_DEVICE_TRANSPORT_DISPATCHED"
        ),
        "executedDeviceCommands": executed_device_commands,
        "directCommandEvidence": {
            "scope": "DIRECT_CHECKER_SUBPROCESS_DISPATCH_ONLY",
            "childProcessTracing": False,
            "commands": audit.commands,
        },
        "blockers": blockers,
        "operatorAuthorizationRequired": authorizations,
        "findings": audit.findings,
    }
    encoded = (json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode(
        "utf-8"
    )
    try:
        report_sha = hashlib.sha256(encoded).hexdigest()
        sidecar = f"{report_sha}  {report_path.name}\n".encode("utf-8")
        atomic_write_pair(report_path, encoded, sidecar_path, sidecar)
    except OSError as exc:
        print(f"check-github64-device-readiness: cannot write report: {exc}", file=sys.stderr)
        return 2

    print(f"hostStatus={host_status} overallStatus={overall_status}")
    print(f"report={report_path}")
    print(f"reportSha256={hashlib.sha256(encoded).hexdigest()}")
    print(f"executedDeviceCommands={executed_device_commands}")
    if not audit.passed:
        return 1
    if fail_on_blocked:
        return 3
    return 0


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    return run_audit(
        policy=production_policy(),
        manifest_path=repo_root
        / "docs/acceptance/github64-exact-build-device-readiness.json",
        source_repo=repo_root,
        report_path=args.report,
        fail_on_blocked=args.fail_on_blocked,
    )


if __name__ == "__main__":
    raise SystemExit(main())

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
from dataclasses import dataclass
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from typing import Any, Mapping


SCHEMA_VERSION = 1
PACKAGE_ID = "github64-exact-build-device-readiness"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")


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
class Policy:
    manifest_sha256: str
    candidate_head: str
    candidate_tree: str
    base_head: str
    allowed_preparation_delta: frozenset[str]
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
    common_env = (("LANG", "C"), ("LC_ALL", "C"))
    return Policy(
        manifest_sha256="4cbed538821b603250ebfb5633b77454948631c3f09047abebc5ee1028c1c4af",
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
                version="git version 2.50.1 (Apple Git-155)",
                executable=FrozenFile(
                    role="executable",
                    path=Path("/usr/bin/git"),
                    sha256="b8763cf250e607a778bb4603cecb5b90338814d0a3dfcba0d57b1de242f610e9",
                    executable=True,
                ),
                environment=common_env
                + (
                    ("GIT_ATTR_NOSYSTEM", "1"),
                    ("GIT_CONFIG_GLOBAL", "/dev/null"),
                    ("GIT_CONFIG_NOSYSTEM", "1"),
                    ("GIT_CONFIG_SYSTEM", "/dev/null"),
                    ("GIT_NO_LAZY_FETCH", "1"),
                    ("GIT_OPTIONAL_LOCKS", "0"),
                    ("GIT_PAGER", ""),
                    ("GIT_TERMINAL_PROMPT", "0"),
                    ("PATH", "/usr/bin:/bin"),
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
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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


def run(
    command: list[str],
    *,
    cwd: Path | None = None,
    environment: Mapping[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        env=dict(environment) if environment is not None else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
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
    if normalized in {"core.fsmonitor", "core.hookspath", "diff.external"}:
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
    inspector: InspectorPolicy,
    repo: Path,
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
        "source:git:external-helper-config",
    )
    if completed is None:
        audit.check(
            "source:git:external-helper-policy",
            False,
            "no repository-configured external helpers",
            "pinned Git unavailable",
        )
        return False
    keys = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    dangerous = sorted({key for key in keys if is_git_external_helper_key(key)})
    ok = completed.returncode in {0, 1} and not dangerous
    audit.check(
        "source:git:external-helper-policy",
        ok,
        "no repository-configured fsmonitor, filter, diff, hook or pager helper",
        dangerous if dangerous else completed.stderr or "none",
    )
    return ok


def git_output(
    audit: Audit,
    inspector: InspectorPolicy,
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


def validate_source(
    audit: Audit,
    repo: Path,
    candidate: Any,
    policy: Policy,
    git_inspector: InspectorPolicy | None,
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
        and len(allowed) == len(set(allowed))
        and all(
            isinstance(item, str)
            and item
            and not Path(item).is_absolute()
            and ".." not in Path(item).parts
            for item in allowed
        )
    )
    allowed_ok = allowed_well_formed and set(allowed) == policy.allowed_preparation_delta
    audit.check(
        "candidate:allowed-preparation-delta",
        allowed_ok,
        sorted(policy.allowed_preparation_delta),
        sorted(allowed) if isinstance(allowed, list) else allowed,
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

    delta_out, delta_err, delta_rc = git_output(
        audit,
        git_inspector,
        repo,
        "delta",
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        "--name-only",
        product_head,
        "--",
    )
    untracked_out, untracked_err, untracked_rc = git_output(
        audit,
        git_inspector,
        repo,
        "untracked",
        "ls-files",
        "--others",
        "--exclude-standard",
    )
    status_out, status_err, status_rc = git_output(
        audit,
        git_inspector,
        repo,
        "checkout-status",
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
    )
    audit.check(
        "source:checkout-clean",
        status_rc == 0 and not status_out,
        "no tracked or untracked checkout delta relative to HEAD",
        status_out or status_err or "clean",
    )
    delta = {line for line in delta_out.splitlines() if line}
    delta.update(line for line in untracked_out.splitlines() if line)
    unexpected = sorted(delta - policy.allowed_preparation_delta)
    audit.check(
        "source:working-tree-delta",
        delta_rc == 0 and untracked_rc == 0 and not unexpected,
        {"onlyAllowed": sorted(policy.allowed_preparation_delta)},
        {
            "allDelta": sorted(delta),
            "unexpected": unexpected,
            "errors": [delta_err, untracked_err],
        },
    )
    snapshot.update(
        {
            "productHead": product_head,
            "productTree": actual_tree if tree_rc == 0 else None,
            "baseHead": actual_base if base_rc == 0 else None,
            "checkoutHead": current_head if current_rc == 0 else None,
            "checkoutStatus": status_out or "clean",
            "preparationDelta": sorted(delta),
        }
    )
    return snapshot


def inspect_frozen_file(item: FrozenFile) -> tuple[bool, dict[str, Any]]:
    path = item.path.resolve()
    exists = path.is_file()
    actual_sha = sha256_file(path) if exists else None
    executable_ok = not item.executable or (exists and os.access(path, os.X_OK))
    return (
        exists and actual_sha == item.sha256 and executable_ok,
        {
            "role": item.role,
            "path": str(path),
            "expectedSha256": item.sha256,
            "actualSha256": actual_sha,
            "executable": executable_ok,
        },
    )


def inspect_frozen_tree(item: FrozenTree) -> tuple[bool, dict[str, Any]]:
    path = item.path.resolve()
    try:
        actual_sha = sha256_tree(path)
        error = None
    except (OSError, ValueError) as exc:
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


def validate_inspectors(
    audit: Audit,
    inspectors: tuple[InspectorPolicy, ...],
) -> tuple[bool, list[dict[str, Any]]]:
    all_ok = True
    snapshots: list[dict[str, Any]] = []
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
        snapshots.append(
            {
                "id": inspector.inspector_id,
                "version": inspector.version,
                "files": file_snapshots,
                "trees": tree_snapshots,
                "argumentsPrefix": list(inspector.arguments_prefix),
                "environmentKeys": sorted(dict(inspector.environment)),
                "policyStatus": "PASS" if inspector_ok else "FAIL",
            }
        )
        all_ok = all_ok and inspector_ok
    return all_ok, snapshots


def run_inspector(
    audit: Audit,
    inspector: InspectorPolicy,
    args: list[str],
    finding_id: str,
) -> subprocess.CompletedProcess[str] | None:
    files_valid = all(
        inspect_frozen_file(item)[0]
        for item in (inspector.executable, *inspector.support_files)
    )
    trees_valid = all(inspect_frozen_tree(item)[0] for item in inspector.support_trees)
    still_valid = files_valid and trees_valid
    audit.check(
        f"{finding_id}:pre-exec-policy",
        still_valid,
        "all pinned inspector files unchanged",
        "unchanged" if still_valid else "drifted",
    )
    if not still_valid:
        return None
    return run(
        [
            str(inspector.executable.path.resolve()),
            *inspector.arguments_prefix,
            *args,
        ],
        environment=dict(inspector.environment),
    )


def validate_artifacts(
    audit: Audit,
    repo: Path,
    entries: Any,
    policy: Policy,
    inspectors_ready: bool,
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
    inspectors = {item.inspector_id: item for item in policy.inspectors}
    aapt = inspectors.get("aapt")
    apksigner = inspectors.get("apksigner")
    can_inspect = inspectors_ready and aapt is not None and apksigner is not None

    for entry in entries:
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
            path = safe_relative(repo, entry.get("relativePath"))
        except ValueError as exc:
            audit.check(
                f"artifact:{artifact_id}:path", False, "safe repo-relative file", str(exc)
            )
            continue
        exists = path.is_file()
        audit.check(f"artifact:{artifact_id}:exists", exists, True, exists)
        if not exists:
            continue
        actual_sha = sha256_file(path)
        actual_size = path.stat().st_size
        audit.check(
            f"artifact:{artifact_id}:sha256",
            actual_sha == expected_sha,
            expected_sha,
            actual_sha,
        )
        audit.check(
            f"artifact:{artifact_id}:size",
            isinstance(expected_size, int) and actual_size == expected_size,
            expected_size,
            actual_size,
        )

        package_fields: dict[str, str | None] = {
            "packageName": None,
            "versionCode": None,
            "versionName": None,
        }
        signer: str | None = None
        if can_inspect and aapt is not None and apksigner is not None:
            inspected = run_inspector(
                audit,
                aapt,
                ["dump", "badging", str(path)],
                f"artifact:{artifact_id}:aapt",
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
                ["verify", "--print-certs", str(path)],
                f"artifact:{artifact_id}:apksigner",
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
            path = safe_relative(repo, entry.get("relativePath"))
        except ValueError as exc:
            audit.check(
                f"input:{input_id}:path", False, "safe repo-relative file", str(exc)
            )
            continue
        exists = path.is_file()
        audit.check(f"input:{input_id}:exists", exists, True, exists)
        if not exists:
            continue
        actual_sha = sha256_file(path)
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

    audit = Audit()
    manifest: dict[str, Any] = {}
    manifest_sha: str | None = None
    try:
        raw = manifest_path.read_bytes()
        manifest_sha = hashlib.sha256(raw).hexdigest()
        decoded = json.loads(raw)
        if isinstance(decoded, dict):
            manifest = decoded
        else:
            audit.check("manifest:root", False, "object", type(decoded).__name__)
    except (OSError, json.JSONDecodeError) as exc:
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
    inspectors_ready, inspector_snapshots = validate_inspectors(audit, policy.inspectors)
    inspector_map = {item.inspector_id: item for item in policy.inspectors}
    source_snapshot = validate_source(
        audit,
        source_repo,
        manifest.get("candidate"),
        policy,
        inspector_map.get("git"),
        inspectors_ready and frozen_manifest,
    )
    artifact_snapshots = validate_artifacts(
        audit,
        source_repo,
        manifest.get("artifacts"),
        policy,
        inspectors_ready and frozen_manifest,
    )
    input_snapshots = validate_inputs(audit, source_repo, manifest.get("inputs"), policy)
    blockers, authorizations = validate_readiness(audit, manifest.get("readiness"), policy)

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
        "deviceAccess": "NOT_ATTEMPTED",
        "executedDeviceCommands": 0,
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
    print("executedDeviceCommands=0")
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

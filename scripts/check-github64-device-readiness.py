#!/usr/bin/env python3
"""Seal a host-only readiness report for GitHub issue #64.

This checker intentionally has no device transport. It validates the frozen
source, APK bytes/identity/signer, contract inputs, authorization envelope and
known blockers, then writes an atomic JSON report plus SHA-256 sidecar.

Exit codes:
  0  the host report is valid (the report may still say BLOCKED)
  1  a host fact or frozen policy failed validation
  2  command-line usage or report-write failure
  3  --require-device-ready was requested and the truthful state is not READY
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any


SCHEMA_VERSION = 1
PACKAGE_ID = "github64-exact-build-device-readiness"
REQUIRED_ARTIFACT_IDS = {"auto", "qwy"}
REQUIRED_INPUT_IDS = {"contract", "schedule"}
REQUIRED_PREPARATION_DELTA = {
    "docs/acceptance/github64-exact-build-device-readiness.json",
    "docs/acceptance/github64-exact-build-device-readiness.md",
    "scripts/check-github64-device-readiness.py",
    "scripts/selftest-github64-device-readiness.sh",
}
REQUIRED_AUTHORIZATIONS = {
    "DEVICE_LEASE",
    "APK_INSTALL_OR_REPLACE",
    "LSPOSED_SCOPE_CHANGE",
    "SYSTEM_MOCK_SELECTION",
    "DEVICE_STATE_MUTATION",
    "CLEANUP_OR_RESTORE",
}
REQUIRED_BLOCKERS = {
    "G2-HARNESS-SCHEMA-001",
    "G2-HARNESS-LEASE-002",
    "G2-HARNESS-EVIDENCE-003",
    "G2-PR62-CHANGES-REQUESTED-004",
    "G2-PR63-PRINCIPAL-ROUTING-005",
    "G2-ISSUE66-CONTINUITY-006",
}
HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")


def parse_args() -> argparse.Namespace:
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=repo_root / "docs/acceptance/github64-exact-build-device-readiness.json",
    )
    parser.add_argument("--source-repo", type=Path, default=repo_root)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--aapt", type=Path)
    parser.add_argument("--apksigner", type=Path)
    parser.add_argument(
        "--require-device-ready",
        action="store_true",
        help="return 3 unless the report is fully READY; never performs device work",
    )
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(command: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def tool_from_sdk(name: str, explicit: Path | None) -> Path | None:
    if explicit is not None:
        return explicit.resolve()
    found = shutil.which(name)
    if found:
        return Path(found).resolve()
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if not root:
            continue
        candidates = list((Path(root) / "build-tools").glob(f"*/{name}"))
        if candidates:
            def version_key(path: Path) -> tuple[tuple[int, str], ...]:
                return tuple(
                    (0, part.zfill(12)) if part.isdigit() else (1, part)
                    for part in re.split(r"[.-]", path.parent.name)
                )

            return max(candidates, key=version_key).resolve()
    return None


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


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


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


def git_output(repo: Path, *args: str) -> tuple[str, str, int]:
    completed = run(["git", "-C", str(repo), *args])
    return completed.stdout.strip(), completed.stderr.strip(), completed.returncode


def validate_source(audit: Audit, repo: Path, candidate: Any) -> dict[str, Any]:
    snapshot: dict[str, Any] = {}
    if not isinstance(candidate, dict):
        audit.check("manifest:candidate", False, "object", type(candidate).__name__)
        return snapshot

    product_head = candidate.get("productHead")
    product_tree = candidate.get("productTree")
    base_head = candidate.get("baseHead")
    allowed = candidate.get("allowedPreparationDelta")
    audit.check("candidate:product-head-format", isinstance(product_head, str) and bool(HEX40.fullmatch(product_head)), "40 lowercase hex", product_head)
    audit.check("candidate:product-tree-format", isinstance(product_tree, str) and bool(HEX40.fullmatch(product_tree)), "40 lowercase hex", product_tree)
    audit.check("candidate:base-head-format", isinstance(base_head, str) and bool(HEX40.fullmatch(base_head)), "40 lowercase hex", base_head)
    allowed_well_formed = isinstance(allowed, list) and len(allowed) == len(set(allowed)) and all(
        isinstance(item, str) and item and not Path(item).is_absolute() and ".." not in Path(item).parts
        for item in allowed
    )
    allowed_ok = allowed_well_formed and set(allowed) == REQUIRED_PREPARATION_DELTA
    audit.check("candidate:allowed-preparation-delta", allowed_ok, sorted(REQUIRED_PREPARATION_DELTA), sorted(allowed) if isinstance(allowed, list) else allowed)
    if not all(isinstance(value, str) and HEX40.fullmatch(value) for value in (product_head, product_tree, base_head)):
        return snapshot

    inside, inside_err, inside_rc = git_output(repo, "rev-parse", "--is-inside-work-tree")
    audit.check("source:git-worktree", inside_rc == 0 and inside == "true", "true", inside or inside_err)
    if inside_rc != 0:
        return snapshot

    current_head, current_err, current_rc = git_output(repo, "rev-parse", "HEAD")
    actual_tree, tree_err, tree_rc = git_output(repo, "rev-parse", f"{product_head}^{{tree}}")
    actual_base, base_err, base_rc = git_output(repo, "rev-parse", f"{product_head}^")
    _, ancestor_err, ancestor_rc = git_output(repo, "merge-base", "--is-ancestor", product_head, "HEAD")
    audit.check("source:current-head-resolves", current_rc == 0 and bool(HEX40.fullmatch(current_head)), "40 lowercase hex", current_head or current_err)
    audit.check("source:product-tree", tree_rc == 0 and actual_tree == product_tree, product_tree, actual_tree or tree_err)
    audit.check("source:base-head", base_rc == 0 and actual_base == base_head, base_head, actual_base or base_err)
    audit.check("source:product-ancestor", ancestor_rc == 0, f"{product_head} ancestor of HEAD", ancestor_err or f"rc={ancestor_rc}")

    delta_out, delta_err, delta_rc = git_output(repo, "diff", "--name-only", product_head, "--")
    untracked_out, untracked_err, untracked_rc = git_output(repo, "ls-files", "--others", "--exclude-standard")
    delta = {line for line in delta_out.splitlines() if line}
    delta.update(line for line in untracked_out.splitlines() if line)
    unexpected = sorted(delta - set(allowed if allowed_ok else []))
    audit.check(
        "source:working-tree-delta",
        delta_rc == 0 and untracked_rc == 0 and not unexpected,
        {"onlyAllowed": sorted(allowed if allowed_ok else [])},
        {"allDelta": sorted(delta), "unexpected": unexpected, "errors": [delta_err, untracked_err]},
    )
    snapshot.update(
        {
            "productHead": product_head,
            "productTree": actual_tree if tree_rc == 0 else None,
            "baseHead": actual_base if base_rc == 0 else None,
            "checkoutHead": current_head if current_rc == 0 else None,
            "preparationDelta": sorted(delta),
        }
    )
    return snapshot


def validate_artifacts(
    audit: Audit,
    repo: Path,
    entries: Any,
    aapt: Path | None,
    apksigner: Path | None,
) -> list[dict[str, Any]]:
    snapshots: list[dict[str, Any]] = []
    artifact_ids, unique = ids(entries)
    audit.check("manifest:artifact-ids", unique and artifact_ids == REQUIRED_ARTIFACT_IDS, sorted(REQUIRED_ARTIFACT_IDS), sorted(artifact_ids))
    aapt_ok = aapt is not None and aapt.is_file() and os.access(aapt, os.X_OK)
    signer_ok = apksigner is not None and apksigner.is_file() and os.access(apksigner, os.X_OK)
    audit.check("tool:aapt", aapt_ok, "executable aapt", str(aapt) if aapt else None)
    audit.check("tool:apksigner", signer_ok, "executable apksigner", str(apksigner) if apksigner else None)
    if not isinstance(entries, list):
        return snapshots

    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        artifact_id = entry["id"]
        expected_sha = entry.get("sha256")
        expected_size = entry.get("sizeBytes")
        expected_signer = entry.get("signerSha256")
        audit.check(f"artifact:{artifact_id}:sha-format", isinstance(expected_sha, str) and bool(HEX64.fullmatch(expected_sha)), "64 lowercase hex", expected_sha)
        audit.check(f"artifact:{artifact_id}:signer-format", isinstance(expected_signer, str) and bool(HEX64.fullmatch(expected_signer)), "64 lowercase hex", expected_signer)
        try:
            path = safe_relative(repo, entry.get("relativePath"))
        except ValueError as exc:
            audit.check(f"artifact:{artifact_id}:path", False, "safe repo-relative file", str(exc))
            continue
        exists = path.is_file()
        audit.check(f"artifact:{artifact_id}:exists", exists, True, exists)
        if not exists:
            continue
        actual_sha = sha256_file(path)
        actual_size = path.stat().st_size
        audit.check(f"artifact:{artifact_id}:sha256", actual_sha == expected_sha, expected_sha, actual_sha)
        audit.check(f"artifact:{artifact_id}:size", isinstance(expected_size, int) and actual_size == expected_size, expected_size, actual_size)

        package_fields: dict[str, str | None] = {"packageName": None, "versionCode": None, "versionName": None}
        if aapt_ok and aapt is not None:
            inspected = run([str(aapt), "dump", "badging", str(path)])
            line = inspected.stdout.splitlines()[0] if inspected.stdout.splitlines() else ""
            match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", line)
            if inspected.returncode == 0 and match:
                package_fields = dict(zip(package_fields, match.groups()))
            else:
                audit.check(f"artifact:{artifact_id}:aapt", False, "parseable package badging", inspected.stderr or line or f"rc={inspected.returncode}")
            for field, actual in package_fields.items():
                audit.check(f"artifact:{artifact_id}:{field}", actual == str(entry.get(field)), str(entry.get(field)), actual)

        signer: str | None = None
        if signer_ok and apksigner is not None:
            inspected = run([str(apksigner), "verify", "--print-certs", str(path)])
            match = re.search(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]{64,95})", inspected.stdout)
            if inspected.returncode == 0 and match:
                signer = match.group(1).replace(":", "").lower()
            else:
                audit.check(f"artifact:{artifact_id}:apksigner", False, "parseable certificate digest", inspected.stderr or inspected.stdout or f"rc={inspected.returncode}")
            audit.check(f"artifact:{artifact_id}:signer", signer == expected_signer, expected_signer, signer)

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


def validate_inputs(audit: Audit, repo: Path, entries: Any) -> list[dict[str, Any]]:
    snapshots: list[dict[str, Any]] = []
    input_ids, unique = ids(entries)
    audit.check("manifest:input-ids", unique and input_ids == REQUIRED_INPUT_IDS, sorted(REQUIRED_INPUT_IDS), sorted(input_ids))
    if not isinstance(entries, list):
        return snapshots
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        input_id = entry["id"]
        expected_sha = entry.get("sha256")
        audit.check(f"input:{input_id}:sha-format", isinstance(expected_sha, str) and bool(HEX64.fullmatch(expected_sha)), "64 lowercase hex", expected_sha)
        try:
            path = safe_relative(repo, entry.get("relativePath"))
        except ValueError as exc:
            audit.check(f"input:{input_id}:path", False, "safe repo-relative file", str(exc))
            continue
        exists = path.is_file()
        audit.check(f"input:{input_id}:exists", exists, True, exists)
        if not exists:
            continue
        actual_sha = sha256_file(path)
        audit.check(f"input:{input_id}:sha256", actual_sha == expected_sha, expected_sha, actual_sha)
        snapshots.append({"id": input_id, "relativePath": entry.get("relativePath"), "sha256": actual_sha})
    return snapshots


def validate_readiness(audit: Audit, readiness: Any) -> tuple[list[dict[str, Any]], list[str]]:
    if not isinstance(readiness, dict):
        audit.check("manifest:readiness", False, "object", type(readiness).__name__)
        return [], []
    blockers = readiness.get("blockers")
    authorizations = readiness.get("operatorAuthorizationRequired")
    blocker_ids, unique = ids(blockers)
    audit.check("policy:required-blockers", unique and blocker_ids == REQUIRED_BLOCKERS, sorted(REQUIRED_BLOCKERS), sorted(blocker_ids))
    auth_ok = isinstance(authorizations, list) and len(authorizations) == len(set(authorizations)) and set(authorizations) == REQUIRED_AUTHORIZATIONS
    audit.check("policy:operator-authorizations", auth_ok, sorted(REQUIRED_AUTHORIZATIONS), sorted(authorizations) if isinstance(authorizations, list) else authorizations)
    scopes_ok = isinstance(blockers, list) and all(
        isinstance(item, dict)
        and isinstance(item.get("scope"), list)
        and bool(item["scope"])
        and all(isinstance(scope, str) and scope for scope in item["scope"])
        for item in blockers
    )
    audit.check("policy:blocker-scopes", scopes_ok, "each blocker has at least one named scope", blockers)
    return blockers if isinstance(blockers, list) else [], authorizations if isinstance(authorizations, list) else []


def main() -> int:
    args = parse_args()
    audit = Audit()
    manifest_path = args.manifest.resolve()
    source_repo = args.source_repo.resolve()
    report_path = args.report.resolve()
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

    audit.check("manifest:schema-version", manifest.get("schemaVersion") == SCHEMA_VERSION, SCHEMA_VERSION, manifest.get("schemaVersion"))
    audit.check("manifest:package-id", manifest.get("packageId") == PACKAGE_ID, PACKAGE_ID, manifest.get("packageId"))
    source_snapshot = validate_source(audit, source_repo, manifest.get("candidate"))
    aapt = tool_from_sdk("aapt", args.aapt)
    apksigner = tool_from_sdk("apksigner", args.apksigner)
    artifact_snapshots = validate_artifacts(audit, source_repo, manifest.get("artifacts"), aapt, apksigner)
    input_snapshots = validate_inputs(audit, source_repo, manifest.get("inputs"))
    blockers, authorizations = validate_readiness(audit, manifest.get("readiness"))

    host_status = "PASS" if audit.passed else "FAIL"
    if not audit.passed:
        overall_status = "INVALID"
    elif blockers:
        overall_status = "BLOCKED"
    elif authorizations:
        overall_status = "NEEDS_OPERATOR_AUTHORIZATION"
    else:
        overall_status = "READY"

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
        "hostStatus": host_status,
        "overallStatus": overall_status,
        "deviceAccess": "NOT_ATTEMPTED",
        "executedDeviceCommands": 0,
        "blockers": blockers,
        "operatorAuthorizationRequired": authorizations,
        "findings": audit.findings,
    }
    encoded = (json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")
    try:
        atomic_write(report_path, encoded)
        report_sha = hashlib.sha256(encoded).hexdigest()
        sidecar = f"{report_sha}  {report_path.name}\n".encode("ascii")
        atomic_write(Path(f"{report_path}.sha256"), sidecar)
    except OSError as exc:
        print(f"check-github64-device-readiness: cannot write report: {exc}", file=sys.stderr)
        return 2

    print(f"hostStatus={host_status} overallStatus={overall_status}")
    print(f"report={report_path}")
    print(f"reportSha256={hashlib.sha256(encoded).hexdigest()}")
    print("executedDeviceCommands=0")
    if not audit.passed:
        return 1
    if args.require_device_ready and overall_status != "READY":
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

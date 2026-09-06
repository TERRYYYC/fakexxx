#!/usr/bin/env python3
"""Validate host-resident UI evidence and scoped negative code searches.

This program has no device transport. The ``ui`` command consumes an existing
uiautomator XML file; ``code-search`` reads regular files below an explicitly
named repository scope. Successful manifests are create-only and published
atomically so a failed retry cannot overwrite or masquerade as prior evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import xml.etree.ElementTree as ElementTree


SCHEMA = "fakexxx-host-evidence-surface-v1"
BOUNDS_RE = re.compile(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")
ROUTE_KIND_RE = re.compile(r"^[a-z][a-z0-9_-]*$")
XML_DECLARATION_ENCODING_RE = re.compile(
    r'^\s*<\?xml\b[^>]*\bencoding\s*=\s*(["\'])([^"\']+)\1', re.IGNORECASE
)
ROUTE_KIND_MEANINGS = {
    "definition": "declaration or definition surface",
    "call-site": "invocation or use-site surface",
    "storage-key": "durable schema, preference, database, or file key",
    "write-api": "state mutation or persistence-write API surface",
    "read-api": "state observation or persistence-read API surface",
    "registration": "manifest, registry, dependency, or component registration surface",
    "transport-path": "wire, provider, IPC, filesystem-zone, or endpoint surface",
}
SELECTOR_ATTRIBUTES = frozenset(("resource-id", "text", "content-desc", "class"))
MAX_UI_XML_BYTES = 32 * 1024 * 1024
MAX_HIT_SAMPLES_PER_ROUTE = 50


class GuardError(Exception):
    """A fail-closed input, provenance, or publication error."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def decode_utf8_evidence(data: bytes, label: str) -> str:
    """Decode the sole accepted evidence encoding, rejecting binary ambiguity."""

    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise GuardError(f"{label} is not UTF-8: {error}") from error
    if "\x00" in text:
        # BOM-less UTF-16/32 can otherwise be valid UTF-8 byte-by-byte while
        # hiding every ASCII token behind interleaved NUL characters.
        raise GuardError(f"{label} contains NUL bytes and is not accepted as UTF-8 text")
    return text


def manifest_bytes(manifest: dict[str, object]) -> bytes:
    return (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")


def require_fresh_output(path: Path) -> None:
    # Path.exists() is false for a broken symlink; lexists keeps publication
    # create-only even for that case.
    if os.path.lexists(path):
        raise GuardError(f"output manifest already exists (refusing overwrite): {path}")


def publish_create_only(path: Path, payload: bytes) -> None:
    """Publish complete bytes without replacing any previous artifact."""

    require_fresh_output(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".staging", dir=path.parent
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            # A same-filesystem hard link is both atomic and create-only.
            os.link(temporary_path, path)
        except FileExistsError as error:
            raise GuardError(
                f"output manifest appeared concurrently (refusing overwrite): {path}"
            ) from error
        try:
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError as durability_error:
            try:
                path.unlink()
            except OSError as cleanup_error:
                raise GuardError(
                    "manifest durability check failed and the published path "
                    f"could not be retracted: {path}: {cleanup_error}"
                ) from durability_error
            raise GuardError(
                f"manifest durability check failed; publication was retracted: {path}: "
                f"{durability_error}"
            ) from durability_error
    finally:
        try:
            temporary_path.unlink()
        except FileNotFoundError:
            pass


def parse_bounds(raw: str, label: str) -> dict[str, int | str]:
    match = BOUNDS_RE.fullmatch(raw)
    if match is None:
        raise GuardError(f'{label} bounds are malformed: "{raw}"')
    left, top, right, bottom = (int(value) for value in match.groups())
    width = right - left
    height = bottom - top
    if width <= 0 or height <= 0:
        raise GuardError(f'{label} bounds are not positive: "{raw}"')
    return {
        "raw": raw,
        "left": left,
        "top": top,
        "right": right,
        "bottom": bottom,
        "width": width,
        "height": height,
    }


def parse_selector(raw: str) -> tuple[str, str]:
    if "=" not in raw:
        raise GuardError(
            "target selector must use ATTRIBUTE=VALUE; supported attributes: "
            + ", ".join(sorted(SELECTOR_ATTRIBUTES))
        )
    attribute, value = raw.split("=", 1)
    if attribute not in SELECTOR_ATTRIBUTES:
        raise GuardError(
            f'unsupported target selector attribute "{attribute}"; expected one of: '
            + ", ".join(sorted(SELECTOR_ATTRIBUTES))
        )
    if not value:
        raise GuardError("target selector value must not be empty")
    return attribute, value


def path_label(path: Path) -> str:
    return str(path.resolve())


def run_ui(args: argparse.Namespace) -> int:
    xml_path = Path(args.xml).expanduser()
    output_path = Path(args.output_manifest).expanduser()
    require_fresh_output(output_path)

    if not args.expected_package or any(ch.isspace() for ch in args.expected_package):
        raise GuardError("expected package must be a non-empty package identifier")
    if not xml_path.is_file():
        raise GuardError(f"UI XML is not a regular file: {xml_path}")
    if xml_path.is_symlink():
        raise GuardError(f"UI XML must not be a symlink: {xml_path}")

    xml_bytes = xml_path.read_bytes()
    if not xml_bytes:
        raise GuardError(f"UI XML is empty: {xml_path}")
    if len(xml_bytes) > MAX_UI_XML_BYTES:
        raise GuardError(
            f"UI XML exceeds {MAX_UI_XML_BYTES} bytes: {len(xml_bytes)}"
        )
    xml_text = decode_utf8_evidence(xml_bytes, "UI XML")
    encoding_match = XML_DECLARATION_ENCODING_RE.match(xml_text)
    if encoding_match is not None:
        declared_encoding = encoding_match.group(2).lower().replace("_", "-")
        if declared_encoding not in ("utf-8", "utf8"):
            raise GuardError(
                f'UI XML declares unsupported encoding "{encoding_match.group(2)}"; '
                "uiautomator evidence must be UTF-8"
            )
    upper_xml = xml_text.upper()
    if "<!DOCTYPE" in upper_xml or "<!ENTITY" in upper_xml:
        raise GuardError("UI XML declarations with entities are not accepted")
    try:
        hierarchy = ElementTree.fromstring(xml_text)
    except ElementTree.ParseError as error:
        raise GuardError(f"UI XML is malformed: {error}") from error
    if hierarchy.tag != "hierarchy":
        raise GuardError(f'UI XML root must be "hierarchy", got "{hierarchy.tag}"')

    app_roots = [
        child
        for child in list(hierarchy)
        if child.tag == "node" and child.attrib.get("package") == args.expected_package
    ]
    if len(app_roots) != 1:
        raise GuardError(
            "expected exactly one direct UI root for package "
            f'"{args.expected_package}", found {len(app_roots)}'
        )
    app_root = app_roots[0]
    root_bounds = parse_bounds(app_root.attrib.get("bounds", ""), "root")
    width = int(root_bounds["width"])
    height = int(root_bounds["height"])
    if width == height:
        raise GuardError("square root bounds do not prove portrait or landscape")
    derived_orientation = "portrait" if height > width else "landscape"
    if derived_orientation != args.expected_orientation:
        raise GuardError(
            f'orientation mismatch: expected "{args.expected_orientation}", '
            f'derived "{derived_orientation}" from {root_bounds["raw"]}'
        )

    selector_attribute, selector_value = parse_selector(args.target_selector)
    target_matches = [
        node
        for node in app_root.iter("node")
        if node.attrib.get("package") == args.expected_package
        and node.attrib.get(selector_attribute) == selector_value
    ]
    if len(target_matches) != 1:
        raise GuardError(
            f'target selector "{args.target_selector}" must match exactly one '
            f'node in package "{args.expected_package}", found {len(target_matches)}'
        )
    target_bounds = parse_bounds(
        target_matches[0].attrib.get("bounds", ""), "target selector node"
    )
    if not (
        int(root_bounds["left"]) <= int(target_bounds["left"])
        and int(root_bounds["top"]) <= int(target_bounds["top"])
        and int(target_bounds["right"]) <= int(root_bounds["right"])
        and int(target_bounds["bottom"]) <= int(root_bounds["bottom"])
    ):
        raise GuardError("target selector node bounds fall outside the package root")

    digest = sha256_bytes(xml_bytes)
    manifest: dict[str, object] = {
        "$schema": SCHEMA,
        "kind": "uiautomator-screen-assertion",
        "status": "passed",
        "input": {
            "path": path_label(xml_path),
            "sha256": digest,
            "sizeBytes": len(xml_bytes),
            "encoding": "UTF-8",
        },
        "assertions": {
            "expectedPackage": args.expected_package,
            "rootBounds": root_bounds,
            "expectedOrientation": args.expected_orientation,
            "derivedOrientation": derived_orientation,
            "targetScreenSelector": {
                "attribute": selector_attribute,
                "value": selector_value,
                "matchCount": len(target_matches),
                "nodeBounds": target_bounds,
            },
        },
    }
    publish_create_only(output_path, manifest_bytes(manifest))
    print(
        "UI_EVIDENCE_PASS "
        f"manifest={path_label(output_path)} inputSha256={digest} "
        f"package={args.expected_package} orientation={derived_orientation} "
        f'targetSelector="{args.target_selector}"'
    )
    return 0


def parse_routes(raw_routes: list[str]) -> list[tuple[str, str, re.Pattern[str]]]:
    if len(raw_routes) < 2:
        raise GuardError("negative code search requires at least two routes")
    routes: list[tuple[str, str, re.Pattern[str]]] = []
    kinds: set[str] = set()
    patterns: set[str] = set()
    for raw in raw_routes:
        if "::" not in raw:
            raise GuardError('route must use ROUTE_KIND::REGEX_PATTERN')
        kind, pattern = raw.split("::", 1)
        if ROUTE_KIND_RE.fullmatch(kind) is None:
            raise GuardError(
                f'invalid route kind "{kind}"; use lowercase letters, digits, "_" or "-"'
            )
        if kind not in ROUTE_KIND_MEANINGS:
            raise GuardError(
                f'unknown route kind "{kind}"; expected one of: '
                + ", ".join(sorted(ROUTE_KIND_MEANINGS))
            )
        if not pattern:
            raise GuardError(f'route "{kind}" has an empty pattern')
        if kind in kinds:
            raise GuardError(f'duplicate route kind cannot prove orthogonality: "{kind}"')
        if pattern in patterns:
            raise GuardError(f'duplicate search pattern cannot prove orthogonality: "{pattern}"')
        try:
            compiled = re.compile(pattern, re.MULTILINE)
        except re.error as error:
            raise GuardError(f'invalid regex for route "{kind}": {error}') from error
        kinds.add(kind)
        patterns.add(pattern)
        routes.append((kind, pattern, compiled))
    return routes


def relative_to_root(path: Path, repo_root: Path, label: str) -> Path:
    try:
        return path.relative_to(repo_root)
    except ValueError as error:
        raise GuardError(f"{label} escapes repository root: {path}") from error


def collect_regular_files(scope: Path, repo_root: Path) -> list[Path]:
    if scope.is_symlink():
        raise GuardError(f"code-search scope must not be a symlink: {scope}")
    if scope.is_file():
        mode = scope.stat().st_mode
        if not stat.S_ISREG(mode):
            raise GuardError(f"code-search scope is not a regular file: {scope}")
        return [scope]
    if not scope.is_dir():
        raise GuardError(f"code-search scope does not exist: {scope}")

    files: list[Path] = []
    def fail_walk(error: OSError) -> None:
        raise GuardError(f"failed to enumerate code-search scope {scope}: {error}")

    for directory, directory_names, file_names in os.walk(
        scope, followlinks=False, onerror=fail_walk
    ):
        directory_path = Path(directory)
        for name in directory_names:
            candidate = directory_path / name
            if candidate.is_symlink():
                raise GuardError(
                    f"code-search scope contains a symlinked directory: {candidate}"
                )
        for name in file_names:
            candidate = directory_path / name
            if candidate.is_symlink():
                raise GuardError(f"code-search scope contains a symlinked file: {candidate}")
            mode = candidate.stat().st_mode
            if not stat.S_ISREG(mode):
                raise GuardError(f"code-search scope contains a non-regular file: {candidate}")
            resolved = candidate.resolve()
            relative_to_root(resolved, repo_root, "code-search file")
            files.append(resolved)
    if not files:
        raise GuardError(f"code-search scope contains no regular files: {scope}")
    return sorted(files, key=lambda path: str(relative_to_root(path, repo_root, "file")))


def snapshot_digest(files_with_bytes: list[tuple[Path, bytes]], repo_root: Path) -> str:
    digest = hashlib.sha256()
    for path, data in files_with_bytes:
        relative_bytes = str(relative_to_root(path, repo_root, "snapshot file")).encode(
            "utf-8"
        )
        digest.update(len(relative_bytes).to_bytes(8, "big"))
        digest.update(relative_bytes)
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def run_code_search(args: argparse.Namespace) -> int:
    output_path = Path(args.output_manifest).expanduser()
    require_fresh_output(output_path)
    repo_root_arg = Path(args.repo_root).expanduser()
    if repo_root_arg.is_symlink():
        raise GuardError(f"repository root must not be a symlink: {repo_root_arg}")
    repo_root = repo_root_arg.resolve()
    if not repo_root.is_dir():
        raise GuardError(f"repository root is not a directory: {repo_root_arg}")
    routes = parse_routes(args.route)
    lexical_repo_root = Path(os.path.abspath(repo_root_arg))

    normalized_scope_names: set[str] = set()
    scope_records: list[dict[str, object]] = []
    unique_files: dict[Path, None] = {}
    for raw_scope in args.scope:
        raw_path = Path(raw_scope).expanduser()
        candidate = (
            raw_path if raw_path.is_absolute() else lexical_repo_root / raw_path
        )
        lexical_candidate = Path(os.path.abspath(candidate))
        lexical_relative = relative_to_root(
            lexical_candidate, lexical_repo_root, "code-search scope"
        )
        current = lexical_repo_root
        for component in lexical_relative.parts:
            current = current / component
            if current.is_symlink():
                raise GuardError(f"code-search scope uses a symlink path entry: {current}")
        resolved = candidate.resolve()
        relative = relative_to_root(resolved, repo_root, "code-search scope")
        normalized = str(relative) if str(relative) else "."
        if normalized in normalized_scope_names:
            raise GuardError(f'duplicate code-search scope: "{normalized}"')
        normalized_scope_names.add(normalized)
        scope_files = collect_regular_files(resolved, repo_root)
        scope_records.append(
            {
                "path": normalized,
                "kind": "file" if resolved.is_file() else "directory",
                "regularFileCount": len(scope_files),
            }
        )
        for path in scope_files:
            unique_files[path] = None

    files = sorted(
        unique_files,
        key=lambda path: str(relative_to_root(path, repo_root, "code-search file")),
    )
    if not files:
        raise GuardError("explicit code-search scopes contain no regular files")
    files_with_bytes: list[tuple[Path, bytes]] = []
    files_with_text: list[tuple[Path, str]] = []
    for path in files:
        try:
            data = path.read_bytes()
        except OSError as error:
            raise GuardError(f"failed to read code-search file {path}: {error}") from error
        files_with_bytes.append((path, data))
        files_with_text.append(
            (path, decode_utf8_evidence(data, f"code-search file {path}"))
        )

    route_records: list[dict[str, object]] = []
    failure_reasons: list[str] = []
    for kind, pattern, compiled in routes:
        hit_count = 0
        hit_files: set[str] = set()
        samples: list[dict[str, object]] = []
        for path, text in files_with_text:
            relative = str(relative_to_root(path, repo_root, "code-search file"))
            for match in compiled.finditer(text):
                hit_count += 1
                hit_files.add(relative)
                if len(samples) < MAX_HIT_SAMPLES_PER_ROUTE:
                    samples.append(
                        {
                            "path": relative,
                            "line": text.count("\n", 0, match.start()) + 1,
                        }
                    )
        record: dict[str, object] = {
            "kind": kind,
            "kindMeaning": ROUTE_KIND_MEANINGS[kind],
            "pattern": pattern,
            "hitCount": hit_count,
            "hitFileCount": len(hit_files),
            "hitSamples": samples,
            "hitSamplesTruncated": hit_count > len(samples),
        }
        route_records.append(record)
        if hit_count:
            failure_reasons.append(f'route "{kind}" matched {hit_count} time(s)')

    absence_supported = not failure_reasons
    manifest: dict[str, object] = {
        "$schema": SCHEMA,
        "kind": "negative-code-search",
        "status": "passed" if absence_supported else "failed",
        "absenceSupported": absence_supported,
        "orthogonalityBasis": "distinct-enumerated-route-kinds-and-patterns",
        "repoRoot": str(repo_root),
        "scopes": scope_records,
        "filesScanned": len(files_with_bytes),
        "sourceEncoding": "UTF-8",
        "sourceSnapshotSha256": snapshot_digest(files_with_bytes, repo_root),
        "routes": route_records,
        "failureReasons": failure_reasons,
    }
    publish_create_only(output_path, manifest_bytes(manifest))
    if absence_supported:
        print(
            "CODE_SEARCH_ABSENCE_SUPPORTED "
            f"manifest={path_label(output_path)} files={len(files_with_bytes)} "
            f"routes={len(route_records)}"
        )
        return 0
    print(
        "CODE_SEARCH_ABSENCE_UNSUPPORTED "
        f"manifest={path_label(output_path)} reasons={' | '.join(failure_reasons)}",
        file=sys.stderr,
    )
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Host-only guard for UI evidence and scoped negative code search."
    )
    commands = parser.add_subparsers(dest="command", required=True)

    ui = commands.add_parser(
        "ui", help="validate an existing uiautomator XML evidence file"
    )
    ui.add_argument("--xml", required=True, help="existing host-side UI XML file")
    ui.add_argument("--expected-package", required=True)
    ui.add_argument(
        "--expected-orientation", required=True, choices=("portrait", "landscape")
    )
    ui.add_argument(
        "--target-selector",
        required=True,
        help="unique screen anchor as resource-id=VALUE, text=VALUE, content-desc=VALUE, or class=VALUE",
    )
    ui.add_argument("--output-manifest", required=True)
    ui.set_defaults(run=run_ui)

    search = commands.add_parser(
        "code-search", help="evaluate a scoped negative code-search assertion"
    )
    search.add_argument("--repo-root", required=True)
    search.add_argument(
        "--scope",
        action="append",
        required=True,
        help="repository-relative file or directory; repeat for additional scopes",
    )
    search.add_argument(
        "--route",
        action="append",
        required=True,
        help=(
            "orthogonal route as ROUTE_KIND::REGEX_PATTERN; at least two distinct "
            "kinds and patterns; kinds: " + ", ".join(sorted(ROUTE_KIND_MEANINGS))
        ),
    )
    search.add_argument("--output-manifest", required=True)
    search.set_defaults(run=run_code_search)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return int(args.run(args))
    except GuardError as error:
        print(f"HOST_EVIDENCE_GUARD_FAIL {error}", file=sys.stderr)
        return 2
    except (OSError, ValueError) as error:
        print(
            f"HOST_EVIDENCE_GUARD_FAIL unexpected host input error: {error}",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

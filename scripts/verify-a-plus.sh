#!/usr/bin/env bash
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

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

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
1|auto-unit-tests|PR-1|apps/cellrebel-auto/gradlew|cd apps/cellrebel-auto && ./gradlew testDebugUnitTest
1|auto-assemble|PR-1|apps/cellrebel-auto/gradlew|cd apps/cellrebel-auto && ./gradlew assembleDebug
1|qwy-unit-tests|PR-1|apps/qianwangyou/gradlew|cd apps/qianwangyou && ./gradlew testDebugUnitTest
1|qwy-assemble|PR-1|apps/qianwangyou/gradlew|cd apps/qianwangyou && ./gradlew assembleDebug
1|inherited-lint-debt|PR-1|scripts/check-inherited-lint-debt.sh|./scripts/check-inherited-lint-debt.sh
2|contract-v1|PR-2|scripts/check-contract-v1.sh|./scripts/check-contract-v1.sh
3|acceptance-scenarios|PR-5|acceptance/scenarios|cd acceptance && ./gradlew test
3|matrix-coverage|PR-5|scripts/check-matrix-coverage.sh|./scripts/check-matrix-coverage.sh
3|forbidden-boundaries|PR-5|acceptance/scripts/check-forbidden-boundaries.sh|./acceptance/scripts/check-forbidden-boundaries.sh
3|auto-qwy-host|PR-6|integration-tests/pr63-on-issue66/run-host-gate.sh|bash ./integration-tests/pr63-on-issue66/run-host-gate.sh
3|release-debt|PR-2|scripts/check-release-debt.sh|./scripts/check-release-debt.sh
"

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

# Toolchain preconditions — reported once, explicitly, instead of surfacing as
# an opaque Gradle stack trace inside the first app gate.
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  printf 'verify-a-plus: no JVM on PATH and JAVA_HOME is unset (spec requires Java 17)\n' >&2
  exit 1
fi
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ ! -f apps/cellrebel-auto/local.properties ]; then
  printf 'verify-a-plus: Android SDK location unknown (set ANDROID_HOME or ANDROID_SDK_ROOT)\n' >&2
  exit 1
fi

RUN=0; PASSED=0; FAILED=0; PENDING=0
FAILED_NAMES=""; PENDING_NAMES=""
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
    "schemaVersion": 3,
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
provenance_keys = {"sourceHead", "sourceTree", "runnerSha256", "runId"}

class ReceiptSchemaError(ValueError):
    pass


class SourceBindingError(ValueError):
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


def require_plain_index():
    raw = fixed_git("ls-files", "-v", "-z", "--cached")
    if not raw or not raw.endswith(b"\x00"):
        raise SourceBindingError("repository index listing is empty or malformed")
    records = raw[:-1].split(b"\x00")
    if any(len(record) < 3 or record[:2] != b"H " for record in records):
        raise SourceBindingError(
            "repository index contains hidden assume-unchanged, skip-worktree, "
            "or other noncanonical flags"
        )


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
        require_plain_index()
        status = fixed_git(
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
            "--ignore-submodules=none",
        )
        if status:
            raise SourceBindingError("repository tracked/untracked source state is not clean")
        runner_sha256 = stable_runner_sha256()
        reviewed_runner_sha256 = head_runner_sha256(source_head)
        if runner_sha256 != reviewed_runner_sha256:
            raise SourceBindingError(
                "canonical runner bytes do not match the runner blob at source HEAD"
            )
        require_plain_index()

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

expected_keys = set(expected) | provenance_keys
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
    if type(receipt["runId"]) is not str:
        type_mismatches.append(
            f"runId has type {type(receipt['runId']).__name__} (expected str)"
        )
    if type_mismatches:
        validation_error = (
            "host-gate receipt schema mismatch: " + "; ".join(type_mismatches)
        )

if validation_error is None:
    if not re.fullmatch(r"[0-9a-f]{32}", receipt["runId"]):
        validation_error = (
            "host-gate receipt schema mismatch: runId must be 32 lowercase hex characters"
        )

if validation_error is None:
    mismatches = [
        f"{field}={receipt[field]!r} (expected {expected_value!r})"
        for field, expected_value in expected.items()
        if receipt[field] != expected_value
    ]
    if mismatches:
        validation_error = "host-gate receipt contract mismatch: " + "; ".join(mismatches)

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
    "     receipt: VALID — schemaVersion=3; hostIntegration=PASS; "
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

  if [ "$rank" -gt "$STAGE_RANK" ]; then
    PENDING=$((PENDING + 1))
    PENDING_NAMES="$PENDING_NAMES $name(owner=$pr)"
    continue
  fi

  if [ ! -e "$file" ]; then
    # Required at this stage but absent: fail loudly. Never skip.
    printf '\n---- %-22s REQUIRED at stage %s but %s is missing\n' "$name" "$STAGE" "$file"
    FAILED=$((FAILED + 1))
    FAILED_NAMES="$FAILED_NAMES $name(missing)"
    continue
  fi

  printf '\n---- %s\n     $ %s\n' "$name" "$cmd"
  RUN=$((RUN + 1))
  if ( eval "$cmd" ); then
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

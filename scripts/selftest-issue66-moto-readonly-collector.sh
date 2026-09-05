#!/bin/bash -p
# Device-free RED matrix for the issue #66 Moto read-only preflight collector.
#
# This selftest NEVER addresses a real adb binary:
#   * --adb always names the fixture explicitly;
#   * PATH and the ADB environment variable point to an independent poison;
#   * every invocation is logged and audited for the sole authorized serial;
#   * the fixture itself rejects a mutating adb command.
#
# Expected production CLI (to be implemented after this RED is observed):
#   collect-issue66-moto-readonly-preflight.sh \
#       --reviewed-head <40-lowercase-hex> \
#       --reviewed-collector-sha256 <64-lowercase-hex> \
#       --adb <absolute-fake-or-real-adb> --serial <serial> --output <new-dir>
#   collect-issue66-moto-readonly-preflight.sh \
#       --adb <path> --classify-adb -- <adb argv...>
#   collect-issue66-moto-readonly-preflight.sh \
#       --reviewed-head <40-lowercase-hex> \
#       --reviewed-collector-sha256 <64-lowercase-hex> \
#       --verify-receipts <existing-evidence-root>
#
# The classify mode is pure policy evaluation. It must not execute adb. The
# receipt verifier is also pure host evaluation. Neither mode may execute adb.
# The collector must route every executable adb command through the same exact
# allowlist before execution.

unset BASH_ENV ENV DEVELOPER_DIR SDKROOT TOOLCHAINS
PATH=/usr/bin:/bin
export PATH
set -uo pipefail

ACL_HELPER_CONTRACT_ONLY=0
COLLECTOR_BINDING_CONTRACT_ONLY=0
PACKAGE_PATH_CONTRACT_ONLY=0
RESOURCE_BUDGET_CONTRACT_ONLY=0
STARTUP_ENV_CONTRACT_ONLY=0
if (( $# > 0 )); then
  if (( $# == 1 )); then
    case "$1" in
      --acl-helper-contract-only) ACL_HELPER_CONTRACT_ONLY=1 ;;
      --collector-binding-contract-only) COLLECTOR_BINDING_CONTRACT_ONLY=1 ;;
      --package-path-contract-only) PACKAGE_PATH_CONTRACT_ONLY=1 ;;
      --resource-budget-contract-only) RESOURCE_BUDGET_CONTRACT_ONLY=1 ;;
      --startup-env-contract-only) STARTUP_ENV_CONTRACT_ONLY=1 ;;
      *)
        printf 'usage: %s [--acl-helper-contract-only|--collector-binding-contract-only|--package-path-contract-only|--resource-budget-contract-only|--startup-env-contract-only]\n' \
          "${0##*/}" >&2
        exit 2
        ;;
    esac
  else
    printf 'usage: %s [--acl-helper-contract-only|--collector-binding-contract-only|--package-path-contract-only|--resource-budget-contract-only|--startup-env-contract-only]\n' \
      "${0##*/}" >&2
    exit 2
  fi
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
COLLECTOR="$HERE/collect-issue66-moto-readonly-preflight.sh"
FAKE_ADB="$HERE/fixtures/issue66-moto-readonly-collector/fake-adb.sh"
ADB_ALLOWLIST="$HERE/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
PYTHON_BIN="/usr/bin/python3"
AUTHORIZED_SERIAL="ZY22JHW9M4"
KNOWN_PACKAGES=(
  "name.caiyao.fakegps"
  "name.caiyao.fakegps.bench"
  "name.caiyao.fakegps.codexbench"
  "com.example.cellrebelauto"
  "com.example.cellrebelauto.codexbench"
  "com.cellrebel.mobile"
)
MISSING_FIXTURE_PACKAGE="com.cellrebel.mobile"

# Test-only fault controls must never be inherited from the caller. Several of
# them intentionally replace paths, so every use below is opt-in and rooted in
# this selftest's private WORK directory.
unset \
  FAKE_ADB_LOG \
  FAKE_ADB_REPLACEMENT \
  FAKE_ADB_REPLACE_MARKER \
  FAKE_ADB_REPLACE_SOURCE \
  FAKE_ADB_LATE_MARKER \
  FAKE_ADB_SCENARIO \
  FAKE_ADB_SNAPSHOT_REPLACE_MARKER \
  FAKE_ADB_SWAP_MARKER \
  FAKE_ADB_SWAP_OUTPUT \
  FAKE_ADB_SWAP_TARGET \
  SELFTEST_PRESNAPSHOT_ADB_MARKER \
  SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT \
  SELFTEST_PRESNAPSHOT_ADB_SOURCE
while IFS='=' read -r inherited_name _; do
  [[ $inherited_name == GIT_* ]] && unset "$inherited_name"
done < <(env)

pass=0
fail=0

report() { # ok|fail name [detail]
  if [ "$1" = ok ]; then
    printf 'ok   %s\n' "$2"
    pass=$((pass + 1))
  else
    printf 'FAIL %s :: %s\n' "$2" "${3:-unspecified failure}"
    fail=$((fail + 1))
  fi
}

for dependency in grep; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    printf 'selftest dependency missing: %s\n' "$dependency" >&2
    exit 2
  fi
done
if [[ ! -f $PYTHON_BIN || ! -x $PYTHON_BIN ]]; then
  printf 'selftest requires the fixed Python runtime: %s\n' "$PYTHON_BIN" >&2
  exit 2
fi

file_mode() { # one no-follow implementation across Darwin and Linux
  "$PYTHON_BIN" -I - "$1" <<'PY'
import os
import stat
import sys

path = sys.argv[1]
print(format(stat.S_IMODE(os.lstat(path).st_mode), "o"))
PY
}

if [ ! -f "$COLLECTOR" ]; then
  printf 'RED: collector target missing: %s\n' "$COLLECTOR" >&2
  printf 'RED reason: Task 2 production collector has not been implemented yet; this is the expected TDD failure.\n' >&2
  exit 1
fi
if [ ! -x "$FAKE_ADB" ]; then
  printf 'selftest fixture is not executable: %s\n' "$FAKE_ADB" >&2
  exit 2
fi
if grep -Eq -- 'PYTHON_BIN.*! -L|GIT_BIN.*! -L' "$COLLECTOR"; then
  printf 'selftest portability stop: fixed system Python/Git entrypoints must permit distro symlinks\n' >&2
  exit 2
else
  report ok "fixed system Python/Git entrypoints permit distro symlinks"
fi
if ! grep -Fq -- '/usr/bin/git --no-replace-objects' "$COLLECTOR" \
    || ! grep -Fq -- 'GIT_CONFIG_NOSYSTEM=1' "$COLLECTOR" \
    || ! grep -Fq -- 'GIT_CONFIG_SYSTEM=/dev/null' "$COLLECTOR" \
    || ! grep -Fq -- 'GIT_CONFIG_GLOBAL=/dev/null' "$COLLECTOR" \
    || ! grep -Fq -- '-c core.fsmonitor=false' "$COLLECTOR"; then
  printf 'selftest review-binding stop: production Git reads must disable replacement objects, ambient configs, and fsmonitor\n' >&2
  exit 2
else
  report ok "production Git binding disables replacement objects, ambient configs, and fsmonitor"
fi

# Exercise the three embedded ACL helpers directly. This source-level seam is
# intentionally device-free: it compiles each real helper, replaces only that
# Python process's os.listxattr, and checks the errno contract without running
# the collector or resolving any adb executable.
ACL_HELPER_CONTRACT_DETAIL="$("$PYTHON_BIN" -I - "$COLLECTOR" <<'PY' 2>&1
import errno
import os
import pathlib
import stat
import subprocess
import sys

source_path = pathlib.Path(sys.argv[1])
lines = source_path.read_text(encoding="utf-8").splitlines()
starts = [
    index for index, line in enumerate(lines)
    if line == "def has_extended_acl(path):"
]
if len(starts) != 3:
    raise SystemExit(f"expected exactly 3 ACL helpers, found {len(starts)}")

helpers = []
for helper_number, start in enumerate(starts, 1):
    end = start + 1
    while end < len(lines) and (not lines[end] or lines[end][0].isspace()):
        end += 1
    snippet = "\n".join(lines[start:end]) + "\n"
    namespace = {
        "errno": errno,
        "os": os,
        "pathlib": pathlib,
        "stat": stat,
        "subprocess": subprocess,
        "sys": sys,
    }
    exec(compile(snippet, f"{source_path}:acl-helper-{helper_number}", "exec"), namespace)
    helpers.append(namespace["has_extended_acl"])

had_listxattr = hasattr(os, "listxattr")
original_listxattr = getattr(os, "listxattr", None)
original_platform = sys.platform

def raise_errno(error_number):
    def injected_listxattr(*_args, **_kwargs):
        raise OSError(error_number, os.strerror(error_number))
    return injected_listxattr

try:
    # Force the xattr-only branch so the contract is independent of whether
    # this selftest host is Darwin or Linux.
    sys.platform = "linux"
    for helper_number, helper in enumerate(helpers, 1):
        for error_number in (errno.EACCES, errno.EIO):
            os.listxattr = raise_errno(error_number)
            try:
                helper(pathlib.Path("."))
            except OSError as error:
                if error.errno != error_number:
                    raise SystemExit(
                        f"ACL helper {helper_number} changed errno "
                        f"{error_number} to {error.errno}"
                    ) from error
            else:
                raise SystemExit(
                    f"ACL helper {helper_number} swallowed "
                    f"{errno.errorcode[error_number]}"
                )

        unsupported_errnos = {
            value for value in (
                getattr(errno, "ENOTSUP", None),
                getattr(errno, "EOPNOTSUPP", None),
            )
            if value is not None
        }
        if not unsupported_errnos:
            raise SystemExit("host Python exposes no unsupported-xattr errno")
        for error_number in unsupported_errnos:
            os.listxattr = raise_errno(error_number)
            if helper(pathlib.Path(".")) is not False:
                raise SystemExit(
                    f"ACL helper {helper_number} did not treat "
                    f"{errno.errorcode.get(error_number, error_number)} as unsupported"
                )

        delattr(os, "listxattr")
        try:
            helper(pathlib.Path("."))
        except (AttributeError, OSError):
            pass
        else:
            raise SystemExit(
                f"ACL helper {helper_number} accepted a non-Darwin host "
                "without descriptor/path xattr inspection"
            )
        if had_listxattr:
            os.listxattr = original_listxattr
finally:
    sys.platform = original_platform
    if had_listxattr:
        os.listxattr = original_listxattr
    elif hasattr(os, "listxattr"):
        delattr(os, "listxattr")
PY
)"
ACL_HELPER_CONTRACT_RC=$?
if (( ACL_HELPER_CONTRACT_RC == 0 )); then
  report ok "all three ACL helpers propagate EACCES/EIO and ignore only unsupported-xattr errors"
else
  report fail \
    "all three ACL helpers propagate EACCES/EIO and ignore only unsupported-xattr errors" \
    "$ACL_HELPER_CONTRACT_DETAIL"
fi

# A status-only Python launcher can make the source-level helper probe above
# report success without executing its body. Bind the contract-only lane to
# output that only the real isolated interpreter can produce.
ACL_PYTHON_RUNTIME_OUT="$("$PYTHON_BIN" -I -c \
  'import sys; sys.stdout.write("ISSUE66_REAL_PYTHON\n")' 2>&1)"
ACL_PYTHON_RUNTIME_RC=$?
if (( ACL_PYTHON_RUNTIME_RC == 0 )) \
    && [[ $ACL_PYTHON_RUNTIME_OUT == ISSUE66_REAL_PYTHON ]]; then
  report ok "ACL-only selftest executes the real isolated Python runtime"
else
  report fail "ACL-only selftest executes the real isolated Python runtime" \
    "rc=$ACL_PYTHON_RUNTIME_RC output=$ACL_PYTHON_RUNTIME_OUT"
fi
unset ACL_PYTHON_RUNTIME_OUT ACL_PYTHON_RUNTIME_RC

if (( ACL_HELPER_CONTRACT_ONLY )); then
  printf 'issue66 Moto read-only collector selftest: %d passed, %d failed\n' "$pass" "$fail"
  [ "$fail" -eq 0 ]
  exit
fi

# Exercise the real stable_collector_binding body without parsing arguments or
# resolving an adb executable. The fixture keeps every byte private and proves
# that the entrypoint file plus every absolute parent from the filesystem root
# are one no-follow, owner/mode/ACL-checked binding.
COLLECTOR_BINDING_CONTRACT_DETAIL="$("$PYTHON_BIN" -I - "$COLLECTOR" <<'PY' 2>&1
import errno
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import time

source_path = pathlib.Path(sys.argv[1])
source = source_path.read_text(encoding="utf-8")
function_marker = "stable_collector_binding() {"
try:
    function_tail = source.split(function_marker, 1)[1]
    binding_source = function_tail.split("<<'PY'\n", 1)[1].split("\nPY\n}", 1)[0] + "\n"
except (IndexError, ValueError) as error:
    raise SystemExit(f"cannot extract stable_collector_binding body: {error}")

failures = []


def make_fixture():
    # Keep the fixture beneath the already-reviewed source chain. A generic
    # /tmp fixture would be rejected on hosts where /tmp is intentionally
    # world-writable once the binding pins every ancestor from `/`.
    temporary = tempfile.TemporaryDirectory(
        prefix=".issue66-collector-binding-",
        dir=source_path.parent,
    )
    repo = pathlib.Path(temporary.name) / "repo"
    scripts = repo / "scripts"
    scripts.mkdir(parents=True)
    collector = scripts / source_path.name
    shutil.copyfile(source_path, collector)
    os.chmod(repo, 0o700)
    os.chmod(scripts, 0o700)
    os.chmod(collector, 0o700)
    return temporary, repo, scripts, collector


def invoke(code, collector, repo, extra_environment=None):
    environment = os.environ.copy()
    if extra_environment:
        environment.update(extra_environment)
    return subprocess.run(
        [
            sys.executable, "-I", "-", os.fspath(collector),
            os.fspath(repo), str(2 * 1024 * 1024),
        ],
        input=code.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=environment,
        check=False,
    )


def expect_rejected(label, result):
    if result.returncode == 0:
        failures.append(f"{label} was accepted: {result.stdout.decode(errors='replace').strip()}")


temporary, repo, scripts, collector = make_fixture()
try:
    baseline = invoke(binding_source, collector, repo)
    if baseline.returncode != 0:
        failures.append(
            "safe collector binding was rejected: "
            + baseline.stdout.decode(errors="replace").strip()
        )

    outer = repo.parent
    os.chmod(outer, 0o777)
    expect_rejected(
        "world-writable ancestor outside repository root",
        invoke(binding_source, collector, repo),
    )
    os.chmod(outer, 0o700)

    os.chmod(repo, 0o720)
    expect_rejected("group-writable repository root", invoke(binding_source, collector, repo))
    os.chmod(repo, 0o700)

    os.chmod(scripts, 0o720)
    expect_rejected("group-writable collector parent", invoke(binding_source, collector, repo))
    os.chmod(scripts, 0o700)

    alias = repo / "scripts-alias"
    alias.symlink_to(scripts, target_is_directory=True)
    expect_rejected(
        "symlinked collector parent",
        invoke(binding_source, alias / collector.name, repo),
    )

    owner_marker = "path = pathlib.Path(sys.argv[1])\n"
    owner_hook = r'''
_selftest_real_fstat = os.fstat
def _selftest_foreign_directory_owner(descriptor):
    value = _selftest_real_fstat(descriptor)
    if stat.S_ISDIR(value.st_mode):
        fields = list(value)
        fields[4] = value.st_uid + 1
        return os.stat_result(fields)
    return value
os.fstat = _selftest_foreign_directory_owner
'''
    if binding_source.count(owner_marker) != 1:
        failures.append("collector binding path marker changed")
    else:
        owner_instrumented = binding_source.replace(
            owner_marker,
            owner_marker + owner_hook,
            1,
        )
        expect_rejected(
            "foreign-owned collector directory",
            invoke(owner_instrumented, collector, repo),
        )
finally:
    temporary.cleanup()


def extract_function(code, signature):
    lines = code.splitlines()
    starts = [index for index, line in enumerate(lines) if line == signature]
    if len(starts) != 1:
        return None
    start = starts[0]
    end = start + 1
    while end < len(lines) and (not lines[end] or lines[end][0].isspace()):
        end += 1
    return "\n".join(lines[start:end]) + "\n"


fd_acl_source = extract_function(binding_source, "def fd_has_extended_acl(descriptor_fd):")
if fd_acl_source is None:
    failures.append("stable collector binding has no unique fd-pinned ACL helper")
else:
    namespace = {"errno": errno, "os": os, "sys": sys}
    exec(compile(fd_acl_source, f"{source_path}:fd-acl-helper", "exec"), namespace)
    fd_acl_helper = namespace["fd_has_extended_acl"]
    temporary, repo, scripts, collector = make_fixture()
    descriptor = os.open(collector, os.O_RDONLY)
    had_listxattr = hasattr(os, "listxattr")
    original_listxattr = getattr(os, "listxattr", None)
    original_platform = sys.platform
    try:
        sys.platform = "linux"
        os.listxattr = lambda candidate: (
            ["system.posix_acl_access"] if candidate == descriptor else []
        )
        if fd_acl_helper(descriptor) is not True:
            failures.append("Linux fd ACL helper missed system.posix_acl_access")
        os.listxattr = lambda candidate: ["user.unrelated"]
        if fd_acl_helper(descriptor) is not False:
            failures.append("Linux fd ACL helper treated an unrelated xattr as an ACL")
        if hasattr(os, "listxattr"):
            delattr(os, "listxattr")
        try:
            fd_acl_helper(descriptor)
        except (AttributeError, OSError):
            pass
        else:
            failures.append("non-Darwin fd ACL helper accepted a missing listxattr API")
    finally:
        sys.platform = original_platform
        if had_listxattr:
            os.listxattr = original_listxattr
        elif hasattr(os, "listxattr"):
            delattr(os, "listxattr")
        os.close(descriptor)
        temporary.cleanup()


if sys.platform == "darwin":
    def add_acl(target, rule):
        result = subprocess.run(
            ["/bin/chmod", "+a", rule, os.fspath(target)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(result.stdout.decode(errors="replace"))

    def remove_acl(target):
        subprocess.run(
            ["/bin/chmod", "-N", os.fspath(target)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )

    acl_cases = (
        ("outside-repository ancestor allowing ACL", lambda repo, scripts, collector: repo.parent,
         "everyone allow add_file,add_subdirectory,delete_child,writeattr,writeextattr"),
        ("repository-root ACL", lambda repo, scripts, collector: repo,
         "everyone allow add_file,add_subdirectory,delete_child,writeattr,writeextattr"),
        ("collector-parent ACL", lambda repo, scripts, collector: scripts,
         "everyone allow add_file,add_subdirectory,delete_child,writeattr,writeextattr"),
        ("collector-file ACL", lambda repo, scripts, collector: collector,
         "everyone allow write,append,writeattr,writeextattr"),
    )
    for label, choose_target, rule in acl_cases:
        temporary, repo, scripts, collector = make_fixture()
        target = choose_target(repo, scripts, collector)
        try:
            mode_before = stat.S_IMODE(target.lstat().st_mode)
            add_acl(target, rule)
            if stat.S_IMODE(target.lstat().st_mode) != mode_before:
                failures.append(f"{label} fixture changed POSIX mode")
            expect_rejected(label, invoke(binding_source, collector, repo))
        finally:
            remove_acl(target)
            temporary.cleanup()

    temporary, repo, scripts, collector = make_fixture()
    try:
        add_acl(repo, "everyone deny delete")
        deny_only = invoke(binding_source, collector, repo)
        if deny_only.returncode != 0:
            failures.append(
                "deny-only repository ACL was rejected: "
                + deny_only.stdout.decode(errors="replace").strip()
            )
    finally:
        remove_acl(repo)
        temporary.cleanup()

    live_repo = source_path.parent.parent
    live_binding = invoke(binding_source, source_path, live_repo)
    if live_binding.returncode != 0:
        failures.append(
            "live collector chain (including the deny-only user-home ACL) was rejected: "
            + live_binding.stdout.decode(errors="replace").strip()
        )

    read_marker = "path = pathlib.Path(sys.argv[1])\n"
    read_hook = r'''
_selftest_real_read = os.read
_selftest_acl_added = False
def _selftest_read_then_add_acl(descriptor, amount):
    global _selftest_acl_added
    chunk = _selftest_real_read(descriptor, amount)
    if chunk and not _selftest_acl_added:
        _selftest_acl_added = True
        completed = __import__("subprocess").run(
            ["/bin/chmod", "+a", os.environ["SELFTEST_ACL_AFTER_READ_RULE"],
             os.environ["SELFTEST_ACL_AFTER_READ_TARGET"]],
            stdin=__import__("subprocess").DEVNULL,
            stdout=__import__("subprocess").DEVNULL,
            stderr=__import__("subprocess").DEVNULL,
            check=False,
        )
        if completed.returncode != 0:
            raise OSError("could not install after-read ACL fixture")
    return chunk
os.read = _selftest_read_then_add_acl
'''
    if binding_source.count(read_marker) != 1:
        failures.append("collector binding read-rendezvous marker changed")
    else:
        read_instrumented = binding_source.replace(read_marker, read_marker + read_hook, 1)
        after_read_cases = (
            ("collector file acquired an ACL during read", lambda repo, scripts, collector: collector,
             "everyone allow write,append,writeattr,writeextattr"),
            ("collector parent acquired an ACL during read", lambda repo, scripts, collector: scripts,
             "everyone allow add_file,add_subdirectory,delete_child,writeattr,writeextattr"),
        )
        for label, choose_target, rule in after_read_cases:
            temporary, repo, scripts, collector = make_fixture()
            target = choose_target(repo, scripts, collector)
            try:
                expect_rejected(
                    label,
                    invoke(
                        read_instrumented,
                        collector,
                        repo,
                        {
                            "SELFTEST_ACL_AFTER_READ_TARGET": os.fspath(target),
                            "SELFTEST_ACL_AFTER_READ_RULE": rule,
                        },
                    ),
                )
            finally:
                remove_acl(target)
                temporary.cleanup()

if failures:
    raise SystemExit("; ".join(failures))
PY
)"
COLLECTOR_BINDING_CONTRACT_RC=$?
if (( COLLECTOR_BINDING_CONTRACT_RC == 0 )); then
  report ok "collector binding pins file and absolute parent chain owner mode and ACL"
else
  report fail \
    "collector binding pins file and absolute parent chain owner mode and ACL" \
    "$COLLECTOR_BINDING_CONTRACT_DETAIL"
fi
if (( COLLECTOR_BINDING_CONTRACT_ONLY )); then
  printf 'issue66 Moto read-only collector selftest: %d passed, %d failed\n' "$pass" "$fail"
  [ "$fail" -eq 0 ]
  exit
fi

privileged_shell_header_intact() { # script-path
  "$PYTHON_BIN" -I - "$1" <<'PY'
import pathlib
import json
import sys

lines = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
if not lines or lines[0] != "#!/bin/bash -p":
    raise SystemExit("missing fixed privileged-mode Bash shebang")
for line in lines[1:]:
    stripped = line.strip()
    if not stripped or stripped.startswith("#"):
        continue
    if stripped != "unset BASH_ENV ENV DEVELOPER_DIR SDKROOT TOOLCHAINS":
        raise SystemExit(f"first executable line is not environment cleanup: {stripped!r}")
    break
else:
    raise SystemExit("script has no executable environment cleanup")
PY
}

if SHELL_HEADER_DETAIL="$(privileged_shell_header_intact "$COLLECTOR" 2>&1)"; then
  report ok "collector enters fixed privileged Bash and clears startup-file variables first"
else
  report fail \
    "collector enters fixed privileged Bash and clears startup-file variables first" \
    "$SHELL_HEADER_DETAIL"
fi
if SHELL_HEADER_DETAIL="$(privileged_shell_header_intact "$0" 2>&1)"; then
  report ok "selftest enters fixed privileged Bash and clears startup-file variables first"
else
  report fail \
    "selftest enters fixed privileged Bash and clears startup-file variables first" \
    "$SHELL_HEADER_DETAIL"
fi
if SHELL_HEADER_DETAIL="$(privileged_shell_header_intact "$FAKE_ADB" 2>&1)"; then
  report ok "fake adb enters fixed privileged Bash and clears startup selectors first"
else
  report fail \
    "fake adb enters fixed privileged Bash and clears startup selectors first" \
    "$SHELL_HEADER_DETAIL"
fi

# Prove the kernel-selected shebang ignores a hostile Bash startup file and,
# on Darwin, clears every xcrun developer-tool selector before the first fixed
# /usr/bin/python3 call.  The nested selftest uses the ACL-only lane to avoid
# recursion and adb, and that lane emits a real-interpreter sentinel so a
# selector-routed /usr/bin/true cannot create a false green.
STARTUP_ENV_WORK="$(/usr/bin/mktemp -d \
  "${TMPDIR:-/tmp}/issue66-startup-env-selftest.XXXXXX")" \
  || { printf 'selftest cannot create startup-environment fixture\n' >&2; exit 2; }
/bin/chmod 700 "$STARTUP_ENV_WORK"
STARTUP_ENV_POISON="$STARTUP_ENV_WORK/poison.sh"
STARTUP_ENV_MARKER="$STARTUP_ENV_WORK/sourced"
printf '%s\n' ': >"${SELFTEST_STARTUP_POISON_MARKER:?}"' >"$STARTUP_ENV_POISON"
/bin/chmod 600 "$STARTUP_ENV_POISON"

STARTUP_ENV_OUT="$(
  BASH_ENV="$STARTUP_ENV_POISON" \
  ENV="$STARTUP_ENV_POISON" \
  SELFTEST_STARTUP_POISON_MARKER="$STARTUP_ENV_MARKER" \
    "$COLLECTOR" </dev/null 2>&1
)"
STARTUP_ENV_RC=$?
if (( STARTUP_ENV_RC == 2 )) \
    && [[ $STARTUP_ENV_OUT == *"usage:"* ]] \
    && [[ ! -e $STARTUP_ENV_MARKER ]]; then
  report ok "collector usage path ignores poison BASH_ENV/ENV"
else
  report fail "collector usage path ignores poison BASH_ENV/ENV" \
    "rc=$STARTUP_ENV_RC marker=$([[ -e $STARTUP_ENV_MARKER ]] && printf present || printf absent) output=$STARTUP_ENV_OUT"
fi

/bin/rm -f "$STARTUP_ENV_MARKER"
STARTUP_ENV_OUT="$(
  BASH_ENV="$STARTUP_ENV_POISON" \
  ENV="$STARTUP_ENV_POISON" \
  SELFTEST_STARTUP_POISON_MARKER="$STARTUP_ENV_MARKER" \
    "$0" --acl-helper-contract-only </dev/null 2>&1
)"
STARTUP_ENV_RC=$?
if (( STARTUP_ENV_RC == 0 )) \
    && [[ $STARTUP_ENV_OUT == *"4 passed, 0 failed"* ]] \
    && [[ ! -e $STARTUP_ENV_MARKER ]]; then
  report ok "ACL-only selftest ignores poison BASH_ENV/ENV"
else
  report fail "ACL-only selftest ignores poison BASH_ENV/ENV" \
    "rc=$STARTUP_ENV_RC marker=$([[ -e $STARTUP_ENV_MARKER ]] && printf present || printf absent) output=$STARTUP_ENV_OUT"
fi

if [[ $(/usr/bin/uname -s) == Darwin ]]; then
  STARTUP_SELECTOR_ROOT="$STARTUP_ENV_WORK/fake-command-line-tools"
  /bin/mkdir -p "$STARTUP_SELECTOR_ROOT/usr/bin" \
    || { printf 'selftest cannot create xcrun selector fixture\n' >&2; exit 2; }
  /bin/ln -s /usr/bin/true "$STARTUP_SELECTOR_ROOT/usr/bin/xcrun" \
    || { printf 'selftest cannot arm xcrun selector fixture\n' >&2; exit 2; }

  DEVELOPER_DIR="$STARTUP_SELECTOR_ROOT" \
    /usr/bin/python3 -I -c 'raise SystemExit(91)' >/dev/null 2>&1
  STARTUP_SELECTOR_ARM_RC=$?
  if (( STARTUP_SELECTOR_ARM_RC == 0 )); then
    report ok "macOS fake CommandLineTools reroutes unguarded system Python"
  else
    report fail "macOS fake CommandLineTools reroutes unguarded system Python" \
      "expected armed rc=0, got rc=$STARTUP_SELECTOR_ARM_RC"
  fi

  STARTUP_SELECTOR_OUT="$(
    BASH_ENV="$STARTUP_ENV_POISON" \
    ENV="$STARTUP_ENV_POISON" \
    DEVELOPER_DIR="$STARTUP_SELECTOR_ROOT" \
    SDKROOT="$STARTUP_SELECTOR_ROOT/SDKs/Poison.sdk" \
    TOOLCHAINS=issue66-poison-toolchain \
    SELFTEST_STARTUP_POISON_MARKER="$STARTUP_ENV_MARKER" \
      "$COLLECTOR" --selftest-fixture --adb "$FAKE_ADB" \
        --classify-adb -- devices -l </dev/null 2>&1
  )"
  STARTUP_SELECTOR_RC=$?
  if (( STARTUP_SELECTOR_RC == 0 )) \
      && [[ $STARTUP_SELECTOR_OUT == ALLOW_READ_ONLY ]] \
      && [[ ! -e $STARTUP_ENV_MARKER ]]; then
    report ok "collector clears combined Bash and xcrun selector poison"
  else
    report fail "collector clears combined Bash and xcrun selector poison" \
      "rc=$STARTUP_SELECTOR_RC marker=$([[ -e $STARTUP_ENV_MARKER ]] && printf present || printf absent) output=$STARTUP_SELECTOR_OUT"
  fi

  /bin/rm -f "$STARTUP_ENV_MARKER"
  STARTUP_SELECTOR_OUT="$(
    BASH_ENV="$STARTUP_ENV_POISON" \
    ENV="$STARTUP_ENV_POISON" \
    DEVELOPER_DIR="$STARTUP_SELECTOR_ROOT" \
    SDKROOT="$STARTUP_SELECTOR_ROOT/SDKs/Poison.sdk" \
    TOOLCHAINS=issue66-poison-toolchain \
    SELFTEST_STARTUP_POISON_MARKER="$STARTUP_ENV_MARKER" \
      "$0" --acl-helper-contract-only </dev/null 2>&1
  )"
  STARTUP_SELECTOR_RC=$?
  if (( STARTUP_SELECTOR_RC == 0 )) \
      && [[ $STARTUP_SELECTOR_OUT == *"4 passed, 0 failed"* ]] \
      && [[ $STARTUP_SELECTOR_OUT == *"ACL-only selftest executes the real isolated Python runtime"* ]] \
      && [[ ! -e $STARTUP_ENV_MARKER ]]; then
    report ok "selftest clears combined Bash and xcrun selector poison"
  else
    report fail "selftest clears combined Bash and xcrun selector poison" \
      "rc=$STARTUP_SELECTOR_RC marker=$([[ -e $STARTUP_ENV_MARKER ]] && printf present || printf absent) output=$STARTUP_SELECTOR_OUT"
  fi

  /bin/rm -f "$STARTUP_ENV_MARKER"
  STARTUP_FAKE_LOG="$STARTUP_ENV_WORK/fake-adb.log"
  STARTUP_FAKE_OUT="$(
    BASH_ENV="$STARTUP_ENV_POISON" \
    ENV="$STARTUP_ENV_POISON" \
    DEVELOPER_DIR="$STARTUP_SELECTOR_ROOT" \
    SDKROOT="$STARTUP_SELECTOR_ROOT/SDKs/Poison.sdk" \
    TOOLCHAINS=issue66-poison-toolchain \
    SELFTEST_STARTUP_POISON_MARKER="$STARTUP_ENV_MARKER" \
    FAKE_ADB_SCENARIO=budget-text-stdout-over \
    FAKE_ADB_LOG="$STARTUP_FAKE_LOG" \
    SELFTEST_WORK_ROOT="$STARTUP_ENV_WORK" \
    SELFTEST_REAL_PYTHON=/usr/bin/python3 \
      "$FAKE_ADB" devices -l 2>/dev/null
  )"
  STARTUP_FAKE_RC=$?
  if (( STARTUP_FAKE_RC == 0 )) \
      && (( ${#STARTUP_FAKE_OUT} == 65537 )) \
      && [[ ! -e $STARTUP_ENV_MARKER ]]; then
    report ok "fake adb clears combined Bash and xcrun poison before Python"
  else
    report fail "fake adb clears combined Bash and xcrun poison before Python" \
      "rc=$STARTUP_FAKE_RC marker=$([[ -e $STARTUP_ENV_MARKER ]] && printf present || printf absent) stdout-bytes=${#STARTUP_FAKE_OUT}"
  fi

  /bin/rm -f "$STARTUP_SELECTOR_ROOT/usr/bin/xcrun" "$STARTUP_FAKE_LOG"
  /bin/rmdir "$STARTUP_SELECTOR_ROOT/usr/bin" "$STARTUP_SELECTOR_ROOT/usr" \
    "$STARTUP_SELECTOR_ROOT" \
    || { printf 'selftest cannot remove xcrun selector fixture\n' >&2; exit 2; }
  unset STARTUP_FAKE_LOG STARTUP_FAKE_OUT STARTUP_FAKE_RC \
    STARTUP_SELECTOR_ARM_RC STARTUP_SELECTOR_OUT STARTUP_SELECTOR_RC \
    STARTUP_SELECTOR_ROOT
fi
/bin/rm -f "$STARTUP_ENV_POISON" "$STARTUP_ENV_MARKER"
/bin/rmdir "$STARTUP_ENV_WORK" \
  || { printf 'selftest cannot remove startup-environment fixture\n' >&2; exit 2; }
unset STARTUP_ENV_MARKER STARTUP_ENV_OUT STARTUP_ENV_POISON STARTUP_ENV_RC STARTUP_ENV_WORK

if (( STARTUP_ENV_CONTRACT_ONLY )); then
  printf 'issue66 Moto read-only collector selftest: %d passed, %d failed\n' "$pass" "$fail"
  [ "$fail" -eq 0 ]
  exit
fi

SELFTEST_REVIEWED_HEAD="$(/usr/bin/env -i PATH=/usr/bin:/bin LC_ALL=C \
  /usr/bin/git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}' 2>/dev/null)" \
  || { printf 'selftest cannot resolve the repository HEAD\n' >&2; exit 2; }
SELFTEST_REVIEWED_COLLECTOR_SHA256="$("$PYTHON_BIN" -I - "$COLLECTOR" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)" || { printf 'selftest cannot hash the collector\n' >&2; exit 2; }
if [[ ! $SELFTEST_REVIEWED_HEAD =~ ^[0-9a-f]{40}$ \
    || ! $SELFTEST_REVIEWED_COLLECTOR_SHA256 =~ ^[0-9a-f]{64}$ ]]; then
  printf 'selftest review-binding fixture is malformed\n' >&2
  exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/issue66-moto-collector-selftest.XXXXXX")"
UNSAFE_OUT="$REPO_ROOT/scripts/fixtures/issue66-moto-readonly-collector/.unsafe-output-$$"
UNSAFE_COMMON_OUT=""
SHELL_ID_FIXTURE_ROOT=""
ATTESTATION_COPY=""
prepare_cleanup_root() { # root created exclusively by this selftest
  local root=$1
  [ -e "$root" ] || return 0

  # Successful collections deliberately freeze tooling/ at 0500. Removing the
  # adb snapshot only requires restoring owner-write on that containing
  # directory; the evidence itself remains read-only throughout every test.
  find "$root" -type d -name tooling -exec chmod u+w {} + 2>/dev/null || true
}
cleanup() {
  prepare_cleanup_root "$WORK"
  prepare_cleanup_root "$UNSAFE_OUT"
  rm -rf "$WORK"
  rm -rf "$UNSAFE_OUT"
  if [[ -n $UNSAFE_COMMON_OUT && ${UNSAFE_COMMON_OUT##*/} == .issue66-unsafe-output-* ]]; then
    prepare_cleanup_root "$UNSAFE_COMMON_OUT"
    rm -rf "$UNSAFE_COMMON_OUT"
  fi
  if [[ -n $SHELL_ID_FIXTURE_ROOT \
      && $SHELL_ID_FIXTURE_ROOT == "$REPO_ROOT"/.issue66-shell-id-fixture-* ]]; then
    prepare_cleanup_root "$SHELL_ID_FIXTURE_ROOT"
    rm -rf "$SHELL_ID_FIXTURE_ROOT"
  fi
  if [[ -n $ATTESTATION_COPY \
      && $ATTESTATION_COPY == "$REPO_ROOT"/.issue66-allowlist-fixture.* ]]; then
    rm -rf "$ATTESTATION_COPY"
  fi
}
trap cleanup EXIT
chmod 700 "$WORK"
mkdir -p "$WORK/bin"

CLEANUP_REGRESSION="$WORK/cleanup-regression"
mkdir -p "$CLEANUP_REGRESSION/tooling"
: >"$CLEANUP_REGRESSION/tooling/adb"
chmod 500 "$CLEANUP_REGRESSION/tooling" "$CLEANUP_REGRESSION/tooling/adb"
prepare_cleanup_root "$CLEANUP_REGRESSION"
if [ -w "$CLEANUP_REGRESSION/tooling" ]; then
  report ok "selftest cleanup restores owner-write on a frozen tooling directory"
else
  report fail "selftest cleanup restores owner-write on a frozen tooling directory"
fi
rm -rf "$CLEANUP_REGRESSION"

# An independent poison executable owns the bare `adb` name. The only way a
# positive collection can reach the fake transport is by honoring --adb.
# A caller that falls back to PATH or the ADB environment variable gets rc=95
# and leaves a distinct poison receipt.
POISON_ADB="$WORK/bin/adb"
POISON_ADB_LOG="$WORK/poison-bare-adb.log"
cat >"$POISON_ADB" <<'POISON'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${POISON_BARE_ADB_LOG:?POISON_BARE_ADB_LOG is required}"
printf 'POISON_BARE_ADB_INVOKED\n' >&2
exit 95
POISON
chmod +x "$POISON_ADB"

# Deterministic host-side fault injection for manifest persistence. Normal runs
# are delegated byte-for-byte to the real utilities. The two named scenarios
# either occupy the initial temporary manifest path before its first write, or
# fail only the second manifest rename (the final COLLECTED replacement).
REAL_MKDIR="$(command -v mkdir)"
REAL_MV="$(command -v mv)"
REAL_PYTHON="$PYTHON_BIN"
MANIFEST_MV_LOG="$WORK/manifest-mv.log"
SUMMARY_ORDER_LOG="$WORK/summary-order.log"
cat >"$WORK/bin/mkdir" <<'MKDIR_FIXTURE'
#!/usr/bin/env bash
real_mkdir="${SELFTEST_REAL_MKDIR:?SELFTEST_REAL_MKDIR is required}"
"$real_mkdir" "$@"
rc=$?
last_argument="${!#}"
if [ "$rc" -eq 0 ] && [ "${FAKE_ADB_SCENARIO:-}" = adb-source-presnapshot-replace ] \
    && [[ $last_argument == receipts || $last_argument == */receipts ]]; then
  root="${SELFTEST_WORK_ROOT:?SELFTEST_WORK_ROOT is required}"
  source_path="${SELFTEST_PRESNAPSHOT_ADB_SOURCE:?source is required}"
  replacement="${SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT:?replacement is required}"
  marker="${SELFTEST_PRESNAPSHOT_ADB_MARKER:?marker is required}"
  for controlled_path in "$source_path" "$replacement" "$marker" "$source_path.next"; do
    case "$controlled_path" in
      "$root"/*) ;;
      *) printf 'unsafe presnapshot fixture path: %s\n' "$controlled_path" >&2; exit 96 ;;
    esac
  done
  : >"$marker" || exit 96
  cp "$replacement" "$source_path.next" || exit 96
  chmod +x "$source_path.next" || exit 96
  mv -f "$source_path.next" "$source_path" || exit 96
fi
exit "$rc"
MKDIR_FIXTURE
cat >"$WORK/bin/mv" <<'MV_FIXTURE'
#!/usr/bin/env bash
real_mv="${SELFTEST_REAL_MV:?SELFTEST_REAL_MV is required}"
real_python="${SELFTEST_REAL_PYTHON:?SELFTEST_REAL_PYTHON is required}"
if [ "${FAKE_ADB_SCENARIO:-}" = manifest-initial-write-failure ] \
    && [ "$#" -eq 3 ] && [ "$1" = -f ] \
    && { [[ $2 == */.manifest.json.tmp ]] || [[ $2 == ./.manifest.json.tmp ]]; } \
    && { [[ $3 == */manifest.json ]] || [[ $3 == ./manifest.json ]]; }; then
  printf 'fixture: refusing initial STOP manifest replacement\n' >&2
  exit 73
fi
if [ "${FAKE_ADB_SCENARIO:-}" = summary-final-replace-failure ] \
    && [ "$#" -eq 3 ] && [ "$1" = -f ] \
    && { [[ $3 == */summary.json ]] || [[ $3 == ./summary.json ]]; }; then
  manifest_path="${3%summary.json}manifest.json"
  if "$real_python" -I -c \
      'import json,sys; raise SystemExit(0 if json.load(open(sys.argv[1], encoding="utf-8")).get("status") == "COLLECTED" else 1)' \
      "$manifest_path" 2>/dev/null; then
    printf 'COLLECTED\n' >"${SELFTEST_SUMMARY_ORDER_LOG:?SELFTEST_SUMMARY_ORDER_LOG is required}"
  else
    printf 'STOP\n' >"${SELFTEST_SUMMARY_ORDER_LOG:?SELFTEST_SUMMARY_ORDER_LOG is required}"
  fi
  printf 'fixture: refusing final summary replacement\n' >&2
  exit 75
fi
if [ "${FAKE_ADB_SCENARIO:-}" = manifest-final-replace-failure ] \
    && [ "$#" -eq 3 ] && [ "$1" = -f ] \
    && { [[ $2 == */.manifest.final.json.tmp ]] || [[ $2 == ./.manifest.final.json.tmp ]]; } \
    && { [[ $3 == */manifest.json ]] || [[ $3 == ./manifest.json ]]; }; then
  printf 'manifest-mv\n' >>"${SELFTEST_MANIFEST_MV_LOG:?SELFTEST_MANIFEST_MV_LOG is required}"
  printf 'fixture: refusing final manifest replacement\n' >&2
  exit 74
fi
exec "$real_mv" "$@"
MV_FIXTURE
chmod +x "$WORK/bin/mkdir" "$WORK/bin/mv"

# Any accidental bare `adb` resolves only to the poison executable. The fake
# fixture remains available solely through the explicit --adb path.
BASE_SELFTEST_PATH="$WORK/bin:$PATH"
SELFTEST_PATH="$BASE_SELFTEST_PATH"
ADB_LOG="$WORK/adb-invocations.log"
OUT=""
RC=0

run_collect() { # scenario serial output-dir [adb-binary]
  local scenario="$1" serial="$2" output_dir="$3"
  local adb_binary="${4:-$FAKE_ADB}"
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  : >"$MANIFEST_MV_LOG"
  : >"$SUMMARY_ORDER_LOG"
  OUT="$(
    PATH="$SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    SELFTEST_REAL_MKDIR="$REAL_MKDIR" \
    SELFTEST_REAL_MV="$REAL_MV" \
    SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
    SELFTEST_WORK_ROOT="$WORK" \
    SELFTEST_MANIFEST_MV_LOG="$MANIFEST_MV_LOG" \
    SELFTEST_SUMMARY_ORDER_LOG="$SUMMARY_ORDER_LOG" \
    FAKE_ADB_SCENARIO="$scenario" \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --selftest-fixture --adb "$adb_binary" \
        --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --serial "$serial" --output "$output_dir" 2>&1
  )"
  RC=$?
}

run_collect_production() { # intentionally omits the selftest trust lane
  local output_dir="$1" adb_binary="${2:-$FAKE_ADB}"
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
    SELFTEST_WORK_ROOT="$WORK" \
    FAKE_ADB_SCENARIO=target \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --adb "$adb_binary" --serial "$AUTHORIZED_SERIAL" \
        --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --output "$output_dir" 2>&1
  )"
  RC=$?
}

run_classify() { # adb argv...
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    FAKE_ADB_SCENARIO=target \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --selftest-fixture --adb "$FAKE_ADB" \
        --classify-adb -- "$@" 2>&1
  )"
  RC=$?
}

run_verify() { # evidence-root
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
    SELFTEST_WORK_ROOT="$WORK" \
    FAKE_ADB_SCENARIO=target \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" --selftest-fixture \
        --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --verify-receipts "$1" 2>&1
  )"
  RC=$?
}

run_verify_production() { # evidence-root; intentionally omits selftest lane
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
      "$COLLECTOR" --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
        --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256" \
        --verify-receipts "$1" 2>&1
  )"
  RC=$?
}

run_review_binding_collection_probe() { # output-dir [review-binding args...]
  local output_dir="$1"
  shift
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" "$@" --adb /usr/bin/false \
        --serial "$AUTHORIZED_SERIAL" --output "$output_dir" 2>&1
  )"
  RC=$?
}

run_review_binding_verify_probe() { # evidence-dir [review-binding args...]
  local evidence_dir="$1"
  shift
  : >"$ADB_LOG"
  : >"$POISON_ADB_LOG"
  OUT="$(
    PATH="$BASE_SELFTEST_PATH" \
    ADB="$POISON_ADB" \
    POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
    FAKE_ADB_LOG="$ADB_LOG" \
      "$COLLECTOR" "$@" --verify-receipts "$evidence_dir" 2>&1
  )"
  RC=$?
}

expect_stop() { # case-name expected-marker
  local name="$1" marker="$2"
  if [ "$RC" -eq 0 ]; then
    report fail "$name" "collector returned success; expected $marker; output=$OUT"
  elif [[ "$OUT" != *"$marker"* ]]; then
    report fail "$name" "nonzero rc=$RC but missing exact marker $marker; output=$OUT"
  else
    report ok "$name"
  fi
}

expect_exit_code() { # case-name expected-code
  if [ "$RC" -eq "$2" ]; then
    report ok "$1"
  else
    report fail "$1" "rc=$RC expected=$2 output=$OUT"
  fi
}

expect_no_adb_call() { # case-name
  if [ -s "$ADB_LOG" ] || [ -s "$POISON_ADB_LOG" ]; then
    report fail "$1" "adb must not run before this refusal; fake=$(tr '\n' ';' <"$ADB_LOG") poison=$(tr '\n' ';' <"$POISON_ADB_LOG")"
  else
    report ok "$1"
  fi
}

expect_only_authorized_target() { # case-name
  local bad=""
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "$line" in
      "devices -l") ;;
      "-s $AUTHORIZED_SERIAL "*) ;;
      *) bad="${bad}${bad:+;}${line}" ;;
    esac
  done <"$ADB_LOG"
  if [ -n "$bad" ]; then
    report fail "$1" "non-inventory adb call escaped the exact serial: $bad"
  else
    report ok "$1"
  fi
}

expect_poison_unused() { # case-name
  if [ -s "$POISON_ADB_LOG" ]; then
    report fail "$1" "collector ignored --adb; poison log=$(tr '\n' ';' <"$POISON_ADB_LOG")"
  else
    report ok "$1"
  fi
}

assert_boot_brackets() { # adb-log report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$AUTHORIZED_SERIAL" <<'PY' 2>&1
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
serial = sys.argv[2]
boot = f"-s {serial} shell cat /proc/sys/kernel/random/boot_id"
uptime = f"-s {serial} shell cat /proc/uptime"
identity = f"-s {serial} shell id"
boot_pos = [i for i, line in enumerate(lines) if line == boot]
uptime_pos = [i for i, line in enumerate(lines) if line == uptime]
if len(boot_pos) != 2 or len(uptime_pos) != 2:
    raise SystemExit(f"boot_id_reads={len(boot_pos)} uptime_reads={len(uptime_pos)} expected=2/2")
if lines[:2] != ["devices -l", identity]:
    raise SystemExit("shell identity is not the first serial-targeted preflight")
if sorted((boot_pos[0], uptime_pos[0])) != [2, 3]:
    raise SystemExit("start boot_id/uptime pair does not immediately follow shell identity")
if sorted((boot_pos[1], uptime_pos[1])) != [len(lines) - 2, len(lines) - 1]:
    raise SystemExit("end boot_id/uptime pair does not follow every targeted observation")
if any(not line.startswith(f"-s {serial} ") for line in lines[1:]):
    raise SystemExit("non-inventory observation escaped the exact target serial")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$2"
  else
    report fail "$2" "$check_out"
  fi
}

assert_manifest_ceiling() { # manifest path
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" <<'PY' 2>&1
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)

expected = {
    "collectionStatus": "COLLECTED",
    "issue66Ac7": "NOT_PASSED",
    "deviceFull": "BLOCKED",
    "durableAck": "NOT_CREATED",
    "fullClaim": "NOT_CREATED",
    "privilegedInspection": "NOT_COLLECTED_PRIVILEGED",
}

errors = [
    f"{key}={manifest.get(key)!r}, expected {value!r}"
    for key, value in expected.items()
    if manifest.get(key) != value
]
if manifest.get("devicePass") is not False:
    errors.append(f"devicePass={manifest.get('devicePass')!r}, expected false")
if manifest.get("status") not in {"COLLECTED", "STATIC_ANALYSIS_PENDING", "COMPATIBILITY_CANDIDATE"}:
    errors.append(f"status={manifest.get('status')!r}, exceeds the compatibility-only ceiling")
if manifest.get("coordinateCaptured") is not False:
    errors.append(f"coordinateCaptured={manifest.get('coordinateCaptured')!r}, expected false")
if manifest.get("compatibility") not in {"STATIC_ANALYSIS_PENDING", "COMPATIBILITY_CANDIDATE"}:
    errors.append(
        f"compatibility={manifest.get('compatibility')!r}, expected STATIC_ANALYSIS_PENDING or COMPATIBILITY_CANDIDATE"
    )
if errors:
    raise SystemExit("; ".join(errors))
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "G-00 manifest cannot claim ACK/FULL/#66 completion"
  else
    report fail "G-00 manifest cannot claim ACK/FULL/#66 completion" "$check_out"
  fi
}

assert_binary_hash_manifest() { # manifest path receipts dir report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import hashlib
import json
import pathlib
import re
import sys

manifest_path = pathlib.Path(sys.argv[1])
receipts_dir = pathlib.Path(sys.argv[2])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

digest_re = re.compile(r"^[0-9a-f]{64}$")

def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

services_path = receipts_dir / "services-jar.stdout.bin"
services_digest = manifest.get("servicesJarSha256")
if not isinstance(services_digest, str) or not digest_re.fullmatch(services_digest):
    raise SystemExit(f"servicesJarSha256 is missing or malformed: {services_digest!r}")
actual_services_digest = sha256(services_path)
if services_digest != actual_services_digest:
    raise SystemExit(
        f"servicesJarSha256 mismatch: manifest={services_digest!r} actual={actual_services_digest!r}"
    )

statuses = manifest.get("knownPackages")
if not isinstance(statuses, dict):
    raise SystemExit(f"knownPackages is not an object: {statuses!r}")
expected_installed = {package for package, status in statuses.items() if status == "INSTALLED"}
invalid_statuses = {
    package: status
    for package, status in statuses.items()
    if status not in {"INSTALLED", "NOT_INSTALLED"}
}
if invalid_statuses:
    raise SystemExit(f"knownPackages contains invalid terminal states: {invalid_statuses!r}")

apk_digests = manifest.get("packageApkSha256")
if not isinstance(apk_digests, dict) or set(apk_digests) != expected_installed:
    raise SystemExit(
        "packageApkSha256 must exactly name installed packages: "
        f"expected={sorted(expected_installed)!r} actual={apk_digests!r}"
    )
for package in sorted(expected_installed):
    digest = apk_digests.get(package)
    if not isinstance(digest, str) or not digest_re.fullmatch(digest):
        raise SystemExit(f"packageApkSha256[{package!r}] is malformed: {digest!r}")
    stem = package.replace(".", "-")
    apk_path = receipts_dir / f"package-{stem}-apk.stdout.bin"
    actual = sha256(apk_path)
    if digest != actual:
        raise SystemExit(
            f"packageApkSha256[{package!r}] mismatch: manifest={digest!r} actual={actual!r}"
        )
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_tool_hash_binding() { # manifest summary adb collector allowlist report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" "$3" "$4" "$5" \
    "$SELFTEST_REVIEWED_HEAD" <<'PY' 2>&1
import hashlib
import json
import pathlib
import re
import sys

manifest_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])
adb_path = pathlib.Path(sys.argv[3])
collector_path = pathlib.Path(sys.argv[4])
allowlist_path = pathlib.Path(sys.argv[5])
expected_source_head = sys.argv[6]
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
summary = json.loads(summary_path.read_text(encoding="utf-8"))
digest_re = re.compile(r"^[0-9a-f]{64}$")

expected = {
    "adbSha256": hashlib.sha256(adb_path.read_bytes()).hexdigest(),
    "collectorSha256": hashlib.sha256(collector_path.read_bytes()).hexdigest(),
    "adbAllowlistSha256": hashlib.sha256(allowlist_path.read_bytes()).hexdigest(),
}
errors = []
for key, actual_digest in expected.items():
    manifest_digest = manifest.get(key)
    summary_digest = summary.get(key)
    if not isinstance(manifest_digest, str) or not digest_re.fullmatch(manifest_digest):
        errors.append(f"manifest {key} missing or malformed: {manifest_digest!r}")
    elif manifest_digest != actual_digest:
        errors.append(
            f"manifest {key} mismatch: manifest={manifest_digest!r} actual={actual_digest!r}"
        )
    if not isinstance(summary_digest, str) or not digest_re.fullmatch(summary_digest):
        errors.append(f"summary {key} missing or malformed: {summary_digest!r}")
    elif summary_digest != actual_digest:
        errors.append(
            f"summary {key} mismatch: summary={summary_digest!r} actual={actual_digest!r}"
        )
if errors:
    raise SystemExit("; ".join(errors))

if manifest.get("adbClientTrust") != "SELFTEST_FIXTURE_ONLY__NOT_DEVICE_EVIDENCE":
    errors.append(f"unexpected selftest client trust: {manifest.get('adbClientTrust')!r}")
if manifest.get("adbApprovalLane") != "SELFTEST":
    errors.append(f"unexpected selftest approval lane: {manifest.get('adbApprovalLane')!r}")
if manifest.get("sourceHead") != expected_source_head:
    errors.append(
        f"manifest sourceHead mismatch: {manifest.get('sourceHead')!r} != {expected_source_head!r}"
    )
if manifest.get("sourceHead") != summary.get("sourceHead"):
    errors.append("summary sourceHead does not match manifest")
if not re.fullmatch(r"[0-9a-f]{40}", str(manifest.get("sourceHead", ""))):
    errors.append(f"manifest sourceHead is malformed: {manifest.get('sourceHead')!r}")
rows = []
for line in allowlist_path.read_text(encoding="ascii").splitlines():
    if line.startswith("#"):
        continue
    rows.append(tuple(line.split("\t")))
matching = [
    row for row in rows
    if row == (
        "SELFTEST",
        manifest.get("adbApprovalLabel"),
        manifest.get("adbSha256"),
    )
]
if len(matching) != 1:
    errors.append("manifest selftest ADB digest/lane/label does not match the pinned allowlist")
for key in (
    "adbClientTrust", "adbApprovalLane", "adbApprovalLabel", "adbAllowlistSha256"
):
    if summary.get(key) != manifest.get(key):
        errors.append(f"summary {key} does not match manifest")
if errors:
    raise SystemExit("; ".join(errors))
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$6"
  else
    report fail "$6" "$check_out"
  fi
}

assert_receipt_tree_binding() { # evidence-root report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" <<'PY' 2>&1
import hashlib
import json
import pathlib
import stat
import struct
import sys

root = pathlib.Path(sys.argv[1])
receipts = root / "receipts"
digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
for path in sorted(receipts.iterdir(), key=lambda item: item.name.encode("utf-8")):
    value = path.lstat()
    if not stat.S_ISREG(value.st_mode) or path.is_symlink():
        raise SystemExit(f"non-regular receipt in positive bundle: {path.name}")
    name = path.name.encode("utf-8")
    data = path.read_bytes()
    digest.update(struct.pack(">Q", len(name)))
    digest.update(name)
    digest.update(struct.pack(">Q", len(data)))
    digest.update(data)
actual = digest.hexdigest()
for document in ("manifest.json", "summary.json"):
    parsed = json.loads((root / document).read_text(encoding="utf-8"))
    if parsed.get("receiptTreeSha256") != actual:
        raise SystemExit(
            f"{document} receiptTreeSha256 mismatch: "
            f"declared={parsed.get('receiptTreeSha256')!r} actual={actual!r}"
        )
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$2"
  else
    report fail "$2" "$check_out"
  fi
}

rebind_receipt_tree() { # evidence-root, for semantic-verifier mutation tests
  "$PYTHON_BIN" -I - "$1" <<'PY'
import hashlib
import json
import pathlib
import stat
import struct
import sys

root = pathlib.Path(sys.argv[1])
receipts = root / "receipts"
digest = hashlib.sha256(b"issue66-receipt-tree-v1\0")
for path in sorted(receipts.iterdir(), key=lambda item: item.name.encode("utf-8")):
    value = path.lstat()
    if not stat.S_ISREG(value.st_mode) or path.is_symlink():
        raise SystemExit(f"cannot rebind non-regular receipt: {path.name}")
    name = path.name.encode("utf-8")
    data = path.read_bytes()
    digest.update(struct.pack(">Q", len(name)))
    digest.update(name)
    digest.update(struct.pack(">Q", len(data)))
    digest.update(data)
value = digest.hexdigest()
for document in ("manifest.json", "summary.json"):
    path = root / document
    parsed = json.loads(path.read_text(encoding="utf-8"))
    parsed["receiptTreeSha256"] = value
    path.write_text(
        json.dumps(parsed, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
PY
}

rebind_binary_claim() { # evidence-root receipt-name apk-package-or-empty
  "$PYTHON_BIN" -I - "$1" "$2" "${3-}" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
receipt_name = sys.argv[2]
package = sys.argv[3]
digest = hashlib.sha256((root / "receipts" / receipt_name).read_bytes()).hexdigest()
for document_name in ("manifest.json", "summary.json"):
    path = root / document_name
    document = json.loads(path.read_text(encoding="utf-8"))
    if receipt_name == "services-jar.stdout.bin":
        if package:
            raise SystemExit("services claim cannot name a package")
        document["servicesJarSha256"] = digest
    else:
        if not package:
            raise SystemExit("APK claim requires a package")
        document["packageApkSha256"][package] = digest
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
}

assert_redacted_summary() { # summary path manifest path report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import json
import pathlib
import re
import sys

summary_path = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
if not summary_path.is_file() or summary_path.is_symlink():
    raise SystemExit("summary.json missing or symlinked")

summary = json.loads(summary_path.read_text(encoding="utf-8"))
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if not isinstance(summary, dict):
    raise SystemExit("summary.json is not an object")

allowed_keys = {
    "schemaVersion",
    "mode",
    "readOnlySemantics",
    "incidentalEffects",
    "adbServerTrust",
    "adbClientTrust",
    "adbApprovalLane",
    "adbApprovalLabel",
    "adbAllowlistSha256",
    "adbSnapshotPath",
    "status",
    "collectionStatus",
    "compatibility",
    "redacted",
    "coordinateCaptured",
    "authorizedSerial",
    "targetSerial",
    "privilegedInspection",
    "devicePass",
    "issue66Ac7",
    "deviceFull",
    "durableAck",
    "fullClaim",
    "sourceHead",
    "knownPackages",
    "servicesJarSha256",
    "packageApkSha256",
    "adbSha256",
    "collectorSha256",
    "receiptTreeSha256",
    "receiptCount",
}
if set(summary) != allowed_keys:
    raise SystemExit(
        f"summary key whitelist mismatch: missing={sorted(allowed_keys - set(summary))!r} "
        f"extra={sorted(set(summary) - allowed_keys)!r}"
    )

expected_exact = {
    "schemaVersion": 3,
    "mode": "READ_ONLY_PREFLIGHT",
    "readOnlySemantics": "OPERATIONAL_NOT_BIT_FOR_BIT",
    "incidentalEffects": [
        "ADB_TRANSPORT", "TRANSIENT_QUERY_PROCESSES", "DEVICE_AUDIT_ACCOUNTING"
    ],
    "adbServerTrust": "DEFAULT_LOCAL_ENDPOINT_NOT_ATTESTED__INHERITED_ROUTING_REJECTED",
    "adbClientTrust": "SELFTEST_FIXTURE_ONLY__NOT_DEVICE_EVIDENCE",
    "adbApprovalLane": "SELFTEST",
    "adbApprovalLabel": "issue66-fake-adb-317c1607",
    "adbAllowlistSha256": "92fe765782212bbd51536110a4023e4eb75472d0ce9ea1446c54a013653cea49",
    "adbSnapshotPath": "tooling/adb",
    "status": "COLLECTED",
    "collectionStatus": "COLLECTED",
    "compatibility": "STATIC_ANALYSIS_PENDING",
    "redacted": True,
    "coordinateCaptured": False,
    "authorizedSerial": "ZY22JHW9M4",
    "targetSerial": "ZY22JHW9M4",
    "privilegedInspection": "NOT_COLLECTED_PRIVILEGED",
    "devicePass": False,
    "issue66Ac7": "NOT_PASSED",
    "deviceFull": "BLOCKED",
    "durableAck": "NOT_CREATED",
    "fullClaim": "NOT_CREATED",
    "sourceHead": manifest.get("sourceHead"),
}
wrong = {
    key: (summary.get(key), expected)
    for key, expected in expected_exact.items()
    if summary.get(key) != expected
}
if wrong:
    raise SystemExit(f"summary ceiling/redaction mismatch: {wrong!r}")

for key in (
    "knownPackages",
    "servicesJarSha256",
    "packageApkSha256",
    "adbSha256",
    "collectorSha256",
    "sourceHead",
    "receiptTreeSha256",
):
    if summary.get(key) != manifest.get(key):
        raise SystemExit(f"summary {key} does not match manifest")
if summary.get("receiptCount") != len(manifest.get("receiptStems", [])):
    raise SystemExit("summary receiptCount does not match manifest receiptStems")

# Exact key/value domains above make raw observation text impossible. Keep an
# explicit lexical guard as a regression tripwire for coordinate-shaped fields.
serialized = json.dumps(summary, ensure_ascii=False, sort_keys=True)
if re.search(r"latitude|longitude|(?:^|[^a-z])lat(?:[^a-z]|$)|(?:^|[^a-z])lon(?:[^a-z]|$)|坐标", serialized, re.I):
    raise SystemExit("summary contains a coordinate-shaped key or value")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_stop_manifest() { # manifest path expected-reason report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)
expected_reason = sys.argv[2]
assert manifest.get("status") == "STOP", manifest
assert manifest.get("reason") == expected_reason, manifest
assert manifest.get("issue66Ac7") == "NOT_PASSED", manifest
assert manifest.get("deviceFull") == "BLOCKED", manifest
assert manifest.get("durableAck") == "NOT_CREATED", manifest
assert manifest.get("fullClaim") == "NOT_CREATED", manifest
assert manifest.get("adbClientTrust") == "SELFTEST_FIXTURE_ONLY__NOT_DEVICE_EVIDENCE", manifest
assert manifest.get("adbApprovalLane") == "SELFTEST", manifest
assert manifest.get("adbApprovalLabel") == "issue66-fake-adb-317c1607", manifest
assert manifest.get("adbAllowlistSha256") == "92fe765782212bbd51536110a4023e4eb75472d0ce9ea1446c54a013653cea49", manifest
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_six_file_receipts() { # manifest path receipts dir [report-label]
  local check_out check_rc
  local label="${3:-G-00 manifest stems each own one strict six-file receipt}"
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import datetime
import json
import pathlib
import re
import sys

manifest_path = pathlib.Path(sys.argv[1])
receipts_dir = pathlib.Path(sys.argv[2])
with manifest_path.open(encoding="utf-8") as stream:
    manifest = json.load(stream)

stems = manifest.get("receiptStems")
if not isinstance(stems, list) or not stems:
    raise SystemExit("manifest receiptStems must be a non-empty array")
if len(stems) != len(set(stems)):
    raise SystemExit("manifest receiptStems contains duplicates")

index_path = receipts_dir / "stems.txt"
indexed = index_path.read_text(encoding="utf-8").splitlines()
if indexed != stems:
    raise SystemExit(f"manifest/index stem mismatch: manifest={stems!r} index={indexed!r}")

rfc3339 = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
stem_pattern = re.compile(r"^[a-z0-9][a-z0-9-]*$")
accounted = {index_path.name}
for stem in stems:
    if not isinstance(stem, str) or not stem_pattern.fullmatch(stem):
        raise SystemExit(f"unsafe receipt stem: {stem!r}")
    stdout_candidates = [
        receipts_dir / f"{stem}.stdout.txt",
        receipts_dir / f"{stem}.stdout.bin",
    ]
    present_stdout = [path for path in stdout_candidates if path.is_file()]
    if len(present_stdout) != 1:
        raise SystemExit(f"{stem}: expected exactly one stdout txt/bin, got {present_stdout!r}")
    required = [
        receipts_dir / f"{stem}.command.txt",
        receipts_dir / f"{stem}.start-utc.txt",
        present_stdout[0],
        receipts_dir / f"{stem}.stderr.bin",
        receipts_dir / f"{stem}.exit.txt",
        receipts_dir / f"{stem}.end-utc.txt",
    ]
    actual = sorted(receipts_dir.glob(f"{stem}.*"))
    if len(actual) != 6 or {path.name for path in actual} != {path.name for path in required}:
        raise SystemExit(f"{stem}: not a strict six-file receipt: {[path.name for path in actual]!r}")
    for path in required:
        if not path.is_file():
            raise SystemExit(f"{stem}: missing {path.name}")
        accounted.add(path.name)
    exit_text = (receipts_dir / f"{stem}.exit.txt").read_text(encoding="ascii").strip()
    if not re.fullmatch(r"\d+", exit_text):
        raise SystemExit(f"{stem}: exit is not unsigned decimal: {exit_text!r}")
    stamps = []
    for suffix in ("start-utc.txt", "end-utc.txt"):
        text = (receipts_dir / f"{stem}.{suffix}").read_text(encoding="ascii").strip()
        if not rfc3339.fullmatch(text):
            raise SystemExit(f"{stem}: {suffix} is not RFC3339 UTC: {text!r}")
        stamps.append(datetime.datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ"))
    if stamps[1] < stamps[0]:
        raise SystemExit(f"{stem}: end timestamp precedes start timestamp")

extras = sorted(path.name for path in receipts_dir.iterdir() if path.is_file() and path.name not in accounted)
if extras:
    raise SystemExit(f"unmanifested receipt files: {extras!r}")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$label"
  else
    report fail "$label" "$check_out"
  fi
}

assert_public_collection() { # manifest receipts adb-log missing-package-or-- report-label
  local check_out check_rc
  local manifest_path="$1" receipts_dir="$2" adb_log="$3" missing_pkg="$4" label="$5"
  shift 5
  check_out="$("$PYTHON_BIN" -I - "$manifest_path" "$receipts_dir" "$adb_log" "$missing_pkg" "$@" <<'PY' 2>&1
import json
import pathlib
import re
import shlex
import sys

manifest_path = pathlib.Path(sys.argv[1])
receipts_dir = pathlib.Path(sys.argv[2])
adb_log = pathlib.Path(sys.argv[3])
missing = set() if sys.argv[4] == "-" else {sys.argv[4]}
known = sys.argv[5:]
serial = "ZY22JHW9M4"

manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
lines = adb_log.read_text(encoding="utf-8").splitlines()
stems = manifest.get("receiptStems", [])
commands = {}
for stem in stems:
    command = (receipts_dir / f"{stem}.command.txt").read_text(encoding="utf-8").strip()
    try:
        parsed = shlex.split(command)
    except ValueError as error:
        raise SystemExit(f"invalid shell-escaped receipt command: {stem!r}: {error}")
    if not parsed:
        raise SystemExit(f"empty receipt command: {stem!r}")
    if parsed[0] != "./tooling/adb":
        raise SystemExit(
            f"receipt command does not name the private adb snapshot: {stem!r} -> {parsed[0]!r}"
        )
    commands[stem] = tuple(parsed[1:])

required_once = [
    "devices -l",
    f"-s {serial} get-state",
    f"-s {serial} shell getprop ro.serialno",
    f"-s {serial} shell getprop ro.product.manufacturer",
    f"-s {serial} shell getprop ro.build.version.sdk",
    f"-s {serial} shell getprop ro.build.fingerprint",
    f"-s {serial} shell getprop ro.product.model",
    f"-s {serial} shell getprop ro.product.device",
    f"-s {serial} shell getprop ro.product.cpu.abilist",
    f"-s {serial} shell getprop ro.zygote",
    f"-s {serial} shell getprop sys.boot_completed",
    f"-s {serial} shell getprop ro.build.version.release",
    f"-s {serial} shell id",
    f"-s {serial} shell getenforce",
    f"-s {serial} shell am get-current-user",
    f"-s {serial} shell ps -A -o USER,PID,NAME",
    f"-s {serial} shell cmd location is-location-enabled --user 0",
    f"-s {serial} exec-out cat /system/framework/services.jar",
]
for package in known:
    required_once.append(f"-s {serial} shell pm path {package}")
    if package not in missing:
        required_once.extend(
            [
            f"-s {serial} shell dumpsys package {package}",
            f"-s {serial} shell pidof {package}",
                f"-s {serial} shell appops get --user 0 {package} android:mock_location",
            ]
        )
        required_once.append(
            f"-s {serial} exec-out cat /data/app/~~issue66/{package}-fixture/base.apk"
        )

expected_counts = {expected: 1 for expected in required_once}
for package in known:
    if package not in missing:
        expected_counts[f"-s {serial} shell pm path {package}"] = 3
expected_counts[f"-s {serial} shell cat /proc/sys/kernel/random/boot_id"] = 2
expected_counts[f"-s {serial} shell cat /proc/uptime"] = 2
wrong_counts = [
    f"{expected!r}: got {lines.count(expected)}, expected {count}"
    for expected, count in expected_counts.items()
    if lines.count(expected) != count
]
unexpected = [line for line in lines if line not in expected_counts]
if wrong_counts or unexpected:
    raise SystemExit(
        "adb command-surface mismatch: "
        + "; ".join(wrong_counts)
        + (f"; unexpected={unexpected!r}" if unexpected else "")
    )

# Compare the two command multisets. command.txt is written with bash `%q`, so
# parse that representation and discard only the executable path before doing
# exact argv-tuple comparisons. Start/end boot_id and uptime intentionally
# repeat the same argv; carrier multiplicity must equal invocation multiplicity.
expected_argv = {
    tuple(shlex.split(expected)): expected
    for expected in expected_counts
}
carrier_counts = {argv: 0 for argv in expected_argv}
for stem, argv in commands.items():
    if argv not in carrier_counts:
        raise SystemExit(
            f"receipt carrier does not bind one frozen adb argv: {stem!r} -> {argv!r}"
        )
    carrier_counts[argv] += 1
carrier_mismatch = [
    f"{expected!r}: adb={lines.count(expected)} carriers={carrier_counts[argv]}"
    for argv, expected in expected_argv.items()
    if carrier_counts[argv] != lines.count(expected)
]
if carrier_mismatch or len(commands) != len(lines):
    raise SystemExit(
        "adb/receipt command multiset mismatch: "
        + "; ".join(carrier_mismatch)
        + f"; adb_total={len(lines)} carrier_total={len(commands)}"
    )

binary_commands = [
    tuple(shlex.split(f"-s {serial} exec-out cat /system/framework/services.jar"))
]
binary_commands.extend(
    tuple(shlex.split(f"-s {serial} exec-out cat /data/app/~~issue66/{package}-fixture/base.apk"))
    for package in known
    if package not in missing
)
for expected in binary_commands:
    matches = [stem for stem, argv in commands.items() if argv == expected]
    if len(matches) != 1:
        raise SystemExit(f"binary command missing/duplicated in receipts: {expected!r}")
    stem = matches[0]
    if not (receipts_dir / f"{stem}.stdout.bin").is_file():
        raise SystemExit(f"{stem}: binary command lacks stdout.bin")
    if (receipts_dir / f"{stem}.stdout.txt").exists():
        raise SystemExit(f"{stem}: binary command also has stdout.txt")

statuses = manifest.get("knownPackages")
if not isinstance(statuses, dict) or set(statuses) != set(known):
    raise SystemExit(f"knownPackages must be an exact fixed-package map: {statuses!r}")
for package in known:
    expected = "NOT_INSTALLED" if package in missing else "INSTALLED"
    if statuses.get(package) != expected:
        raise SystemExit(f"knownPackages[{package!r}]={statuses.get(package)!r}, expected {expected!r}")

forbidden = [
    r"(^| )su( |$)",
    r"(^| )root( |$)",
    r"(^| )logcat( |$)",
    r"dumpsys location",
    r"(^| )am start( |$)",
    r"(^| )install(-multiple)?( |$)",
    r"(^| )uninstall( |$)",
    r"(^| )push( |$)",
    r"settings (put|delete)",
    r"appops (set|reset)",
    r"(^| )pm clear( |$)",
    r"force-stop| am crash | reboot| remount|set-location-enabled|test-provider",
    r"latitude|longitude|\blat\b|\blon\b",
]
for line in lines:
    for pattern in forbidden:
        if re.search(pattern, line, flags=re.IGNORECASE):
            raise SystemExit(f"forbidden public-collector adb surface: {line!r} matched {pattern!r}")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$label"
  else
    report fail "$label" "$check_out"
  fi
}

assert_no_privileged_fallback() { # adb-log report-label
  if grep -Eiq -- '(^| )su( |$)|(^| )root( |$)|logcat|/data/adb|dumpsys location' "$1"; then
    report fail "$2" "privileged/private fallback found: $(grep -Ei -- '(^| )su( |$)|(^| )root( |$)|logcat|/data/adb|dumpsys location' "$1" | tr '\n' ';')"
  else
    report ok "$2"
  fi
}

assert_classify_stop() { # label marker argv...
  local label="$1" marker="$2"
  shift 2
  run_classify "$@"
  expect_stop "classifier rejects $label" "$marker"
  expect_exit_code "classifier $label uses local-safety rc=22" 22
  expect_no_adb_call "classifier $label performs no adb call"
}

assert_classify_allow() { # label argv...
  local label="$1"
  shift
  run_classify "$@"
  if [ "$RC" -eq 0 ] && [[ $OUT == *ALLOW_READ_ONLY* ]]; then
    report ok "classifier allows $label"
  else
    report fail "classifier allows $label" "rc=$RC output=$OUT"
  fi
  expect_no_adb_call "classifier allow-check $label performs no adb call"
}

assert_no_adb_after() { # adb-log exact-anchor report-label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
anchor = sys.argv[2]
positions = [index for index, line in enumerate(lines) if line == anchor]
if len(positions) != 1:
    raise SystemExit(f"anchor count={len(positions)}, expected 1: {anchor!r}")
tail = lines[positions[0] + 1:]
if tail:
    raise SystemExit(f"adb calls escaped fail-fast boundary: {tail!r}")
PY
  )"
  check_rc=$?
  if [ "$check_rc" -eq 0 ]; then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_package_path_bracket() { # manifest receipts adb-log missing-package-or-- label packages...
  local manifest_path="$1" receipts_dir="$2" adb_log="$3" missing_pkg="$4" label="$5"
  shift 5
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - \
    "$manifest_path" "$receipts_dir" "$adb_log" "$missing_pkg" "$@" <<'PY' 2>&1
import json
import pathlib
import re
import shlex
import sys

manifest_path = pathlib.Path(sys.argv[1])
receipts = pathlib.Path(sys.argv[2])
adb_log = pathlib.Path(sys.argv[3])
missing = set() if sys.argv[4] == "-" else {sys.argv[4]}
known = tuple(sys.argv[5:])
serial = "ZY22JHW9M4"

manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
stems = manifest.get("receiptStems")
statuses = manifest.get("knownPackages")
if not isinstance(stems, list) or len(stems) != len(set(stems)):
    raise SystemExit("receiptStems is not one unique ordered list")
if not isinstance(statuses, dict) or set(statuses) != set(known):
    raise SystemExit("knownPackages does not match the fixed package set")
positions = {stem: index for index, stem in enumerate(stems)}
adb_lines = adb_log.read_text(encoding="utf-8").splitlines()

path_re = re.compile(
    r"^/data/app/([A-Za-z0-9._+=~-]+)/([A-Za-z0-9._+=~-]+)/"
    r"(base|split_[A-Za-z0-9._+=~-]+)\.apk$"
)


def command(stem):
    raw = (receipts / f"{stem}.command.txt").read_text(encoding="utf-8")
    return tuple(shlex.split(raw))


def base_path(stem, package):
    raw = (receipts / f"{stem}.stdout.txt").read_bytes()
    try:
        value = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SystemExit(f"{stem}: path stdout is not UTF-8: {error}")
    if "\x00" in value or not value.endswith("\n"):
        raise SystemExit(f"{stem}: path framing mismatch")
    value = value.replace("\r\n", "\n")
    if "\r" in value:
        raise SystemExit(f"{stem}: path contains bare CR")
    paths = []
    for line in value[:-1].split("\n"):
        if not line.startswith("package:"):
            raise SystemExit(f"{stem}: path line lacks package prefix")
        candidate = line[len("package:"):]
        match = path_re.fullmatch(candidate)
        if (
            match is None
            or match.group(1) in {".", ".."}
            or match.group(2) in {".", ".."}
            or not match.group(2).startswith(package + "-")
        ):
            raise SystemExit(f"{stem}: unsafe package path")
        paths.append((candidate, match.group(3)))
    if not paths or len({path for path, _ in paths}) != len(paths):
        raise SystemExit(f"{stem}: empty or duplicate package path")
    bases = [path for path, leaf in paths if leaf == "base"]
    if len(bases) != 1:
        raise SystemExit(f"{stem}: expected exactly one base APK")
    return bases[0]


for package in known:
    package_stem = package.replace(".", "-")
    initial = f"package-{package_stem}-path"
    pre = f"package-{package_stem}-path-pre-apk"
    apk = f"package-{package_stem}-apk"
    post = f"package-{package_stem}-path-post-apk"
    pm_argv = ("./tooling/adb", "-s", serial, "shell", "pm", "path", package)
    adb_pm = f"-s {serial} shell pm path {package}"

    if package in missing:
        if statuses.get(package) != "NOT_INSTALLED":
            raise SystemExit(f"{package}: missing package status changed")
        if initial not in positions or any(stem in positions for stem in (pre, apk, post)):
            raise SystemExit(f"{package}: NOT_INSTALLED receipt graph is not initial-path only")
        if command(initial) != pm_argv or adb_lines.count(adb_pm) != 1:
            raise SystemExit(f"{package}: NOT_INSTALLED pm path is not exactly once")
        continue

    if statuses.get(package) != "INSTALLED":
        raise SystemExit(f"{package}: installed package status changed")
    metadata = (
        initial,
        f"package-{package_stem}-dumpsys",
        f"package-{package_stem}-pidof",
        f"package-{package_stem}-appops",
    )
    if any(stem not in positions for stem in metadata + (pre, apk, post)):
        raise SystemExit(f"{package}: path bracket stem missing")
    metadata_positions = [positions[stem] for stem in metadata]
    if metadata_positions != list(range(metadata_positions[0], metadata_positions[0] + 4)):
        raise SystemExit(f"{package}: initial path and metadata are not ordered")
    if [positions[pre], positions[apk], positions[post]] != list(
        range(positions[pre], positions[pre] + 3)
    ):
        raise SystemExit(f"{package}: pre/APK/post bracket is not contiguous")
    if positions[initial] >= positions[pre] or positions[post] >= positions["services-jar"]:
        raise SystemExit(f"{package}: path bracket crosses its collection boundary")
    if any(command(stem) != pm_argv for stem in (initial, pre, post)):
        raise SystemExit(f"{package}: three pm path receipts do not bind identical argv")
    paths = [base_path(stem, package) for stem in (initial, pre, post)]
    if len(set(paths)) != 1:
        raise SystemExit(f"{package}: base APK path changed across bracket: {paths!r}")
    expected_apk = ("./tooling/adb", "-s", serial, "exec-out", "cat", paths[0])
    if command(apk) != expected_apk:
        raise SystemExit(f"{package}: APK receipt does not use the bracketed base path")
    if adb_lines.count(adb_pm) != 3:
        raise SystemExit(f"{package}: live pm path count is {adb_lines.count(adb_pm)}, expected 3")
PY
  )"
  check_rc=$?
  if (( check_rc == 0 )); then
    report ok "$label"
  else
    report fail "$label" "$check_out"
  fi
}

assert_package_path_stop_boundary() { # adb-log before|after label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" "$AUTHORIZED_SERIAL" <<'PY' 2>&1
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
phase = sys.argv[2]
serial = sys.argv[3]
package = "name.caiyao.fakegps"
pm_path = f"-s {serial} shell pm path {package}"
apk = f"-s {serial} exec-out cat /data/app/~~issue66/{package}-fixture/base.apk"
services = f"-s {serial} exec-out cat /system/framework/services.jar"
expected_path_reads = 2 if phase == "before" else 3
if lines.count(pm_path) != expected_path_reads:
    raise SystemExit(
        f"{phase}: pm path count={lines.count(pm_path)}, expected={expected_path_reads}"
    )
if not lines or lines[-1] != pm_path:
    raise SystemExit(f"{phase}: path-change receipt is not the final adb observation")
apk_reads = [line for line in lines if " exec-out cat /data/app/" in line]
if phase == "before" and apk_reads:
    raise SystemExit(f"before: APK bytes were read before rejection: {apk_reads!r}")
if phase == "after" and apk_reads != [apk]:
    raise SystemExit(f"after: APK read set is not the single bracketed path: {apk_reads!r}")
if services in lines:
    raise SystemExit(f"{phase}: services.jar ran after package-path change")
PY
  )"
  check_rc=$?
  if (( check_rc == 0 )); then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

run_fc5_verifier_mutation() { # evidence-root label
  run_verify "$1"
  expect_stop "$2" STOP_INCOMPLETE_RECEIPT
  expect_exit_code "$2 uses evidence rc=21" 21
  expect_no_adb_call "$2 performs no adb call"
}

assert_package_hash_absent() { # manifest package label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
digests = manifest.get("packageApkSha256")
if not isinstance(digests, dict) or sys.argv[2] in digests:
    raise SystemExit(f"unexpected package digest state: {digests!r}")
PY
  )"
  check_rc=$?
  if (( check_rc == 0 )); then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

assert_budget_receipt() { # evidence-root stem stdout-suffix size exit label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" "$3" "$4" "$5" <<'PY' 2>&1
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
stem = sys.argv[2]
stdout_suffix = sys.argv[3]
expected_size = int(sys.argv[4])
expected_exit = sys.argv[5]
receipts = root / "receipts"
payload = receipts / f"{stem}.{stdout_suffix}"
if payload.stat().st_size != expected_size:
    raise SystemExit(
        f"{payload.name} size={payload.stat().st_size}, expected={expected_size}"
    )
exit_text = (receipts / f"{stem}.exit.txt").read_text(encoding="ascii").strip()
if exit_text != expected_exit:
    raise SystemExit(f"{stem} exit={exit_text!r}, expected={expected_exit!r}")
PY
  )"
  check_rc=$?
  if (( check_rc == 0 )); then
    report ok "$6"
  else
    report fail "$6" "$check_out"
  fi
}

assert_budget_stop_stem_last() { # evidence-root stem label
  local check_out check_rc
  check_out="$("$PYTHON_BIN" -I - "$1" "$2" <<'PY' 2>&1
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
stem = sys.argv[2]
manifest_stems = json.loads(
    (root / "manifest.json").read_text(encoding="utf-8")
)["receiptStems"]
file_stems = (root / "receipts" / "stems.txt").read_text(
    encoding="ascii"
).splitlines()
if not manifest_stems or manifest_stems[-1] != stem:
    raise SystemExit(f"manifest failing stem is not last: {manifest_stems[-1:]!r}")
if not file_stems or file_stems[-1] != stem:
    raise SystemExit(f"stems.txt failing stem is not last: {file_stems[-1:]!r}")
expected = {
    f"{stem}.command.txt", f"{stem}.start-utc.txt", f"{stem}.stdout.txt",
    f"{stem}.stdout.bin", f"{stem}.stderr.bin", f"{stem}.exit.txt",
    f"{stem}.end-utc.txt",
}
actual = {path.name for path in (root / "receipts").glob(f"{stem}.*")}
expected = {name for name in expected if (root / "receipts" / name).exists()}
if len(actual) != 6 or actual != expected:
    raise SystemExit(f"failing carrier sidecars={sorted(actual)!r}")
PY
  )"
  check_rc=$?
  if (( check_rc == 0 )); then
    report ok "$3"
  else
    report fail "$3" "$check_out"
  fi
}

expect_fc6_live_stop() { # case-name exact-marker
  if (( RC != 0 )) && [[ $OUT == "$2" ]]; then
    report ok "$1"
  else
    report fail "$1" "rc=$RC expected exact output=$2 actual output=$OUT"
  fi
}

run_fc6_typed_live_case() { # scenario marker stem suffix cap exit anchor label
  local scenario=$1 marker=$2 stem=$3 suffix=$4 cap=$5 receipt_exit=$6 anchor=$7 label=$8
  local output_dir="$WORK/out-$scenario"
  run_collect "$scenario" "$AUTHORIZED_SERIAL" "$output_dir"
  expect_fc6_live_stop "$label is refused with one exact typed marker" "$marker"
  expect_exit_code "$label uses evidence rc=21" 21
  if [[ -f $output_dir/manifest.json ]]; then
    assert_stop_manifest "$output_dir/manifest.json" "$marker" \
      "$label manifest preserves the typed reason"
    assert_six_file_receipts "$output_dir/manifest.json" "$output_dir/receipts" \
      "$label completes the six-file carrier before stopping"
    assert_budget_receipt "$output_dir" "$stem" "$suffix" "$cap" "$receipt_exit" \
      "$label stores the exact capped bytes and canonical exit"
    assert_budget_stop_stem_last "$output_dir" "$stem" \
      "$label records the failing carrier last and complete"
  else
    report fail "$label manifest exists" "missing manifest.json"
  fi
  assert_no_adb_after "$ADB_LOG" "$anchor" "$label has no retry or later adb command"
  assert_no_privileged_fallback "$ADB_LOG" "$label has no privileged fallback"
}

run_fc6_child_exit_case() { # child-exit
  local child_exit=$1 scenario="budget-child-exit-$1"
  local label="ordinary adb child exit $1" output_dir="$WORK/out-$scenario"
  run_collect "$scenario" "$AUTHORIZED_SERIAL" "$output_dir"
  expect_fc6_live_stop "$label is exactly a transport read failure" \
    STOP_ADB_READ_FAILED
  expect_exit_code "$label uses evidence rc=21" 21
  if [[ -f $output_dir/manifest.json ]]; then
    assert_stop_manifest "$output_dir/manifest.json" STOP_ADB_READ_FAILED \
      "$label is not confused with a supervisor outcome"
    assert_six_file_receipts "$output_dir/manifest.json" "$output_dir/receipts" \
      "$label completes the six-file carrier before stopping"
    assert_budget_receipt "$output_dir" devices stdout.txt 0 "$child_exit" \
      "$label preserves the child exit code"
    assert_budget_stop_stem_last "$output_dir" devices \
      "$label records devices as the failing carrier"
  else
    report fail "$label manifest exists" "missing manifest.json"
  fi
  assert_no_adb_after "$ADB_LOG" "devices -l" \
    "$label has no retry or later adb command"
  assert_no_privileged_fallback "$ADB_LOG" "$label has no privileged fallback"
}

run_fc6_success_live_case() { # scenario stem suffix size label
  local scenario=$1 stem=$2 suffix=$3 size=$4 label=$5
  local output_dir="$WORK/out-$scenario"
  run_collect "$scenario" "$AUTHORIZED_SERIAL" "$output_dir"
  if (( RC == 0 )) && [[ $OUT == *"COLLECTED evidence="* ]]; then
    report ok "$label completes collection"
  else
    report fail "$label completes collection" "rc=$RC output=$OUT"
  fi
  if [[ -f $output_dir/manifest.json ]]; then
    assert_budget_receipt "$output_dir" "$stem" "$suffix" "$size" 0 \
      "$label accepts the exact transport cap"
  else
    report fail "$label manifest exists" "missing manifest.json"
  fi
  run_verify "$output_dir"
  if (( RC == 0 )) && [[ $OUT == *RECEIPTS_COMPLETE* ]]; then
    report ok "$label passes offline receipt verification"
  else
    report fail "$label passes offline receipt verification" "rc=$RC output=$OUT"
  fi
  expect_no_adb_call "$label offline verification performs no adb call"
}

run_fc6_archive_live_case() { # scenario marker label
  local scenario=$1 marker=$2 label=$3
  local output_dir="$WORK/out-$scenario" stem
  run_collect "$scenario" "$AUTHORIZED_SERIAL" "$output_dir"
  expect_fc6_live_stop "$label is refused with one exact typed marker" "$marker"
  expect_exit_code "$label uses evidence rc=21" 21
  if [[ -f $output_dir/manifest.json ]]; then
    assert_stop_manifest "$output_dir/manifest.json" "$marker" \
      "$label manifest preserves the archive-limit reason"
    assert_six_file_receipts "$output_dir/manifest.json" "$output_dir/receipts" \
      "$label preserves strict receipts"
    if [[ $scenario == archive-apk-* ]]; then
      stem=package-name-caiyao-fakegps-path-post-apk
    else
      stem=services-jar
    fi
    assert_budget_stop_stem_last "$output_dir" "$stem" \
      "$label records the failing archive carrier last and complete"
  else
    report fail "$label manifest exists" "missing manifest.json"
  fi
  assert_no_privileged_fallback "$ADB_LOG" "$label has no privileged fallback"
}

run_fc6_tree_rebind_case() { # scenario marker exit label
  local scenario=$1 marker=$2 expected_exit=$3 label=$4
  local output_dir="$WORK/out-$scenario"
  run_collect "$scenario" "$AUTHORIZED_SERIAL" "$output_dir"
  expect_fc6_live_stop "$label stops before publication" "$marker"
  expect_exit_code "$label uses its typed exit" "$expected_exit"
  if [[ -f $output_dir/manifest.json ]]; then
    assert_stop_manifest "$output_dir/manifest.json" "$marker" \
      "$label manifest remains STOP"
    assert_six_file_receipts "$output_dir/manifest.json" "$output_dir/receipts" \
      "$label leaves only complete receipt carriers"
  else
    report fail "$label manifest exists" "missing manifest.json"
  fi
  assert_no_privileged_fallback "$ADB_LOG" "$label has no privileged fallback"
}

run_fc6_verifier_case() { # evidence-root marker label
  run_verify "$1"
  if (( RC == 21 )) \
      && [[ $OUT == "$2: "* ]] \
      && [[ $OUT != *STOP_INCOMPLETE_RECEIPT* ]]; then
    report ok "$3 preserves the typed verifier marker without wrapping"
  else
    report fail "$3 preserves the typed verifier marker without wrapping" \
      "rc=$RC output=$OUT"
  fi
  expect_exit_code "$3 uses evidence rc=21" 21
  expect_no_adb_call "$3 performs no adb call"
}

write_fc6_archive_mutation() { # output-path apk|services mutation
  "$PYTHON_BIN" -I - "$1" "$2" "$3" <<'PY'
import io
import pathlib
import struct
import sys
import zipfile

path = pathlib.Path(sys.argv[1])
kind = sys.argv[2]
mutation = sys.argv[3]

file_caps = {"apk": 3 * 1024 * 1024, "services": 2 * 1024 * 1024}
member_caps = {"apk": 16384, "services": 4096}
if mutation == "file-over":
    path.write_bytes(b"F" * (file_caps[kind] + 1))
    raise SystemExit(0)

compression = zipfile.ZIP_BZIP2 if mutation == "method-over" else (
    zipfile.ZIP_DEFLATED
    if mutation in {"ratio-over", "aggregate-ratio-over"}
    else zipfile.ZIP_STORED
)
buffer = io.BytesIO()
with zipfile.ZipFile(buffer, "w", compression=compression) as archive:
    def add(name, data=b""):
        member = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        member.external_attr = 0o100600 << 16
        member.compress_type = compression
        archive.writestr(member, data)

    if kind == "apk":
        add("AndroidManifest.xml", b"manifest")
        add("classes.dex", b"dex\n035\0")
    elif kind == "services":
        add("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\n\r\n")
        add("classes.dex", b"dex\n035\0")
    else:
        raise SystemExit("unknown archive kind")

    if mutation in {"member-over", "member-boundary"}:
        total = member_caps[kind] + (1 if mutation == "member-over" else 0)
        for index in range(total - 2):
            add(f"budget/{index:05d}")
    elif mutation == "ratio-over":
        add("budget/ratio.bin", b"R" * (2 * 1024 * 1024))
    elif mutation == "aggregate-ratio-over":
        add("budget/ratio-one.bin", b"R" * (768 * 1024))
        add("budget/ratio-two.bin", b"S" * (768 * 1024))
    elif mutation in {"single-over", "total-over"}:
        add("budget/one.bin", b"1")
        if mutation == "total-over":
            add("budget/two.bin", b"2")
            add("budget/three.bin", b"3")
    elif mutation == "method-over":
        add("budget/method.bin", b"method")
    else:
        raise SystemExit(f"unknown archive mutation: {mutation}")

data = bytearray(buffer.getvalue())
if mutation in {"single-over", "total-over"}:
    central_signature = b"PK\x01\x02"
    positions = []
    cursor = 0
    while True:
        cursor = data.find(central_signature, cursor)
        if cursor < 0:
            break
        positions.append(cursor)
        cursor += 4
    target_positions = positions[-1:] if mutation == "single-over" else positions[-3:]
    sizes = (
        [256 * 1024 * 1024 + 1]
        if mutation == "single-over"
        else [200 * 1024 * 1024, 200 * 1024 * 1024, 112 * 1024 * 1024 + 1]
    )
    for position, size in zip(target_positions, sizes):
        struct.pack_into("<I", data, position + 24, size)
path.write_bytes(data)
PY
}

# FC-6: one immutable lane-selected budget supervises every exact adb argv.
# The source contract supplements live deadlock/limit cases with integer-only
# ZIP metadata boundary checks against both independent validator copies.
FC6_STATIC_DETAIL="$("$PYTHON_BIN" -I - "$COLLECTOR" <<'PY' 2>&1
import ast
import datetime
import errno
import hashlib
import os
import pathlib
import re
import shlex
import stat
import subprocess
import sys
import tempfile
import time

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
required = (
    "readonly PROD_TEXT_TIMEOUT_SECONDS=30",
    "readonly PROD_TEXT_STDOUT_LIMIT=4194304",
    "readonly PROD_BINARY_APK_TIMEOUT_SECONDS=180",
    "readonly PROD_BINARY_APK_STDOUT_LIMIT=268435456",
    "readonly PROD_BINARY_SERVICES_TIMEOUT_SECONDS=120",
    "readonly PROD_BINARY_SERVICES_STDOUT_LIMIT=134217728",
    "readonly PROD_STDERR_LIMIT=1048576",
    "readonly SELFTEST_TIMEOUT_SECONDS=2",
    "readonly SELFTEST_TEXT_STDOUT_LIMIT=65536",
    "readonly SELFTEST_APK_STDOUT_LIMIT=3145728",
    "readonly SELFTEST_SERVICES_STDOUT_LIMIT=2097152",
    "readonly SELFTEST_STDERR_LIMIT=32768",
    "readonly ADB_SNAPSHOT_SIZE_LIMIT=67108864",
    "readonly ADB_ALLOWLIST_SIZE_LIMIT=65536",
    "readonly COLLECTOR_SOURCE_SIZE_LIMIT=2097152",
    "readonly OFFLINE_RETAINED_CONTROL_LIMIT=67108864",
    "subprocess.Popen(",
    "shell=False",
    "start_new_session=True",
    "selectors.DefaultSelector()",
    "os.killpg(",
    "process.wait(timeout=",
    "def sanitized_child_environment(lane):",
    "def bounded_retained_control_total(current, values, byte_limit):",
    "def receipt_profile(name):",
    'bounded_directory_names(root, 4, "evidence root")',
    'bounded_directory_names(tooling, 1, "tooling directory")',
    "file_read_limits[str(adb_path)] = (adb_snapshot_size_limit, None)",
    "file_digest.hexdigest() != expected_digest",
    "print(f\"{digest.hexdigest()}\\t{identity_text(opened_after)}\")",
)
missing = [fragment for fragment in required if fragment not in source]
if missing:
    raise SystemExit(f"resource supervisor contract missing: {missing!r}")
for forbidden in ("--adb-timeout", "--stdout-limit", "--stderr-limit", "ADB_TIMEOUT_SECONDS"):
    if forbidden in source:
        raise SystemExit(f"resource budget exposes an override surface: {forbidden}")

tree_start = source.index("sha256_receipt_tree() {")
tree_end = source.index("\nrender_manifest()", tree_start)
tree_source = source[tree_start:tree_end]
verify_start = source.index("verify_receipts() {")
verify_end = source.index("\nread_scalar_receipt()", verify_start)
verify_source = source[verify_start:verify_end]
main_source = source[source.index("main() {"):]
if "read_bytes(" in tree_source or "path.open(" in tree_source:
    raise SystemExit("receipt-tree hashing contains an unbounded pathname read")
if "os.listdir(" in tree_source:
    raise SystemExit("receipt-tree hashing materializes an unbounded directory listing")
if "receipts.glob(" in verify_source or ".iterdir()" in verify_source:
    raise SystemExit("offline verification materializes an unbounded directory listing")
if "sha256_file() {" in source:
    raise SystemExit("collector retains a dead unbounded pathname hash helper")

def embedded_python(function_name):
    tail = source.split(f"{function_name}() {{", 1)[1]
    heredoc_tail = tail.split("<<'PY'", 1)[1].split("\n", 1)[1]
    return heredoc_tail.split("\nPY\n", 1)[0] + "\n"

validate_adb_python = embedded_python("validate_adb_binary")
snapshot_adb_python = embedded_python("snapshot_adb_binary")
intact_adb_python = embedded_python("adb_snapshot_intact")
verify_python = embedded_python("verify_receipts")
if "O_NONBLOCK" not in validate_adb_python or "O_NONBLOCK" not in snapshot_adb_python:
    raise SystemExit("ADB source opens are not uniformly nonblocking before type checks")
validate_allowlist_python = embedded_python("validate_adb_approval")
if "O_NONBLOCK" not in validate_allowlist_python:
    raise SystemExit("live ADB allowlist open is not nonblocking")
if "def stable_trust_bytes(path, byte_limit):" not in validate_allowlist_python:
    raise SystemExit("live ADB allowlist lacks the bounded trust-file reader")
if "def stable_trust_bytes(path, byte_limit):" not in verify_python:
    raise SystemExit("offline repo trust inputs lack the bounded trust-file reader")
if "receipt_snapshot" in verify_python or "valid_archive_bytes" in verify_python:
    raise SystemExit("offline verification still retains complete archive receipt bytes")
if "def stable_archive_file(path, kind):" not in verify_python:
    raise SystemExit("offline verification lacks descriptor-streamed archive validation")

parsed_verifier = ast.parse(verify_python)
retained_nodes = [
    node for node in parsed_verifier.body
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
    and node.name == "bounded_retained_control_total"
]
if len(retained_nodes) != 1:
    raise SystemExit("cannot extract the offline retained-control accounting seam")
retained_namespace = {}
exec(
    compile(
        ast.Module(body=retained_nodes, type_ignores=[]),
        sys.argv[1],
        "exec",
    ),
    retained_namespace,
)
bounded_retained_control_total = retained_namespace[
    "bounded_retained_control_total"
]
retained_limit = 4096
if bounded_retained_control_total(
    1024, (b"A" * 1024, b"B" * 2048), retained_limit
) != retained_limit:
    raise SystemExit("offline retained-control accounting rejected its exact byte cap")
try:
    bounded_retained_control_total(
        1024, (b"A" * 1024, b"B" * 2049), retained_limit
    )
except SystemExit as error:
    if "exceed the fixed memory limit" not in str(error):
        raise
else:
    raise SystemExit("offline retained-control accounting accepted byte cap+1")

# Execute the unchanged production carrier loop, not only its accounting helper.

carrier_nodes = [
    node for node in parsed_verifier.body
    if isinstance(node, ast.For)
    and isinstance(node.target, ast.Name) and node.target.id == "stem"
    and isinstance(node.iter, ast.Name) and node.iter.id == "expected_stems"
    and any(
        isinstance(child, ast.Assign)
        and any(isinstance(target, ast.Subscript)
                and isinstance(target.value, ast.Name)
                and target.value.id == "carriers" for target in child.targets)
        for child in ast.walk(node)
    )
]
retained_nodes = [
    node for node in parsed_verifier.body
    if isinstance(node, ast.FunctionDef)
    and node.name == "bounded_retained_control_total"
]
if len(carrier_nodes) != 1 or len(retained_nodes) != 1:
    raise SystemExit("cannot extract the offline carrier-loop accounting seam")
carrier_program = compile(
    ast.Module(body=retained_nodes + carrier_nodes, type_ignores=[]),
    sys.argv[1], "exec",
)

def run_carrier_accounting(command, count, budget):
    with tempfile.TemporaryDirectory(prefix="issue66-command-accounting-") as temp:
        receipts = pathlib.Path(temp)
        stems = [f"test-{index}" for index in range(count)]
        for stem in stems:
            values = {
                "command.txt": command,
                "start-utc.txt": b"2026-09-05T00:00:00Z\n",
                "stdout.txt": b"",
                "stderr.bin": b"",
                "exit.txt": b"0\n",
                "end-utc.txt": b"2026-09-05T00:00:00Z\n",
            }
            for suffix, value in values.items():
                (receipts / f"{stem}.{suffix}").write_bytes(value)
        limits = {}
        def bounded_fixture_read(path):
            value = path.read_bytes()
            if len(value) > limits[str(path)][0]:
                raise SystemExit("fixture input exceeds assigned file limit")
            return value
        namespace = {
            "receipts": receipts, "expected_stems": stems, "binary_stems": set(),
            "file_read_limits": limits, "metadata_limit": 4 * 1024 * 1024,
            "text_stdout_limit": 4 * 1024 * 1024, "stderr_limit": 1024 * 1024,
            "retained_control_bytes": 0, "retained_control_limit": budget,
            "carriers": {}, "accounted": set(), "previous_end": None,
            "receipt_names": {path.name for path in receipts.iterdir()},
            "stable_bytes": bounded_fixture_read, "shlex": shlex,
            "datetime": datetime, "re": re,
            "rfc3339": re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"),
        }
        failure = None
        try:
            exec(carrier_program, namespace)
        except SystemExit as error:
            failure = str(error)
        return namespace, failure

failures = []
exact_command = b"adb -s " + b"A" * 4089
exact_payload = sum(len(arg.encode("utf-8")) for arg in shlex.split(exact_command.decode()))
state, error = run_carrier_accounting(exact_command, 1, exact_payload)
if error is not None or state["retained_control_bytes"] != exact_payload or len(state["carriers"]) != 1:
    failures.append("exact-cap command argv is not retained and counted exactly")
state, error = run_carrier_accounting(exact_command + b"A", 1, 65536)
if error != "fixture input exceeds assigned file limit" or state["carriers"]:
    failures.append("command carrier byte cap+1 is accepted")
state, error = run_carrier_accounting(exact_command, 1, exact_payload - 1)
if error != "offline retained control bytes exceed the fixed memory limit" or state["carriers"]:
    failures.append("argv is stored before rejecting aggregate byte cap+1")
state, error = run_carrier_accounting(exact_command, 17, 65536)
if error != "offline retained control bytes exceed the fixed memory limit" or len(state["carriers"]) != 16:
    failures.append("multiple command carriers bypass the aggregate byte cap")
unicode_command = "adb -s " + "é" * 2044 + "a"
state, error = run_carrier_accounting(unicode_command.encode("utf-8"), 1, exact_payload)
if error is not None or state["retained_control_bytes"] != exact_payload:
    failures.append("argv accounting counts characters instead of retained UTF-8 bytes")
if failures:
    raise SystemExit("; ".join(failures))
print("offline carrier-loop command caps and aggregate argv accounting passed")

root_scan = verify_source.index('bounded_directory_names(root, 4, "evidence root")')
tooling_scan = verify_source.index(
    'bounded_directory_names(tooling, 1, "tooling directory")'
)
first_untrusted_read = min(
    verify_source.index("manifest_bytes = stable_bytes(manifest_path)"),
    verify_source.index("summary_bytes = stable_bytes(summary_path)"),
    verify_source.index("adb_snapshot_digest = stable_file_digest(adb_path"),
)
if root_scan > first_untrusted_read or tooling_scan > first_untrusted_read:
    raise SystemExit("offline directory cardinality gates run after an untrusted read")

def run_embedded(program, *arguments, timeout=5):
    return subprocess.run(
        ["/usr/bin/python3", "-I", "-", *(str(value) for value in arguments)],
        input=program.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )

def adb_identity(path):
    value = path.stat()
    return ":".join(str(item) for item in (
        value.st_dev, value.st_ino, value.st_size,
        value.st_mtime_ns, value.st_ctime_ns, stat.S_IMODE(value.st_mode),
    ))

def snapshot_identity(path):
    value = path.stat()
    return f"{value.st_dev}:{value.st_ino}:{value.st_size}"

with tempfile.TemporaryDirectory(prefix="issue66-fc6-adb-cap-") as directory:
    boundary_root = pathlib.Path(directory)
    size_limit = 64 * 1024 * 1024
    exact_source = boundary_root / "adb-exact"
    over_source = boundary_root / "adb-over"
    exact_source.touch(mode=0o600)
    over_source.touch(mode=0o600)
    os.truncate(exact_source, size_limit)
    os.truncate(over_source, size_limit + 1)
    exact_source.chmod(0o500)
    over_source.chmod(0o500)

    exact_validation = run_embedded(
        validate_adb_python, exact_source, size_limit, timeout=10
    )
    over_validation = run_embedded(validate_adb_python, over_source, size_limit)
    if exact_validation.returncode != 0 or over_validation.returncode == 0:
        raise SystemExit(
            "ADB validation size boundary drifted: "
            f"exact={exact_validation.returncode} over={over_validation.returncode}"
        )

    fifo_source = boundary_root / "adb-fifo"
    os.mkfifo(fifo_source, 0o500)
    fifo_validation = run_embedded(validate_adb_python, fifo_source, size_limit)
    fifo_snapshot = run_embedded(
        snapshot_adb_python,
        fifo_source,
        adb_identity(fifo_source),
        boundary_root / "fifo-copy",
        size_limit,
    )
    if fifo_validation.returncode == 0 or fifo_snapshot.returncode == 0:
        raise SystemExit("ADB FIFO source was not refused after a nonblocking open")

    tooling = boundary_root / "tooling"
    tooling.mkdir(mode=0o700)
    exact_snapshot = tooling / "adb"
    copied = run_embedded(
        snapshot_adb_python,
        exact_source,
        adb_identity(exact_source),
        exact_snapshot,
        size_limit,
        timeout=10,
    )
    over_copy = run_embedded(
        snapshot_adb_python,
        over_source,
        adb_identity(over_source),
        boundary_root / "over-copy",
        size_limit,
    )
    if (
        copied.returncode != 0
        or over_copy.returncode == 0
        or exact_snapshot.stat().st_size != size_limit
    ):
        raise SystemExit(
            "ADB snapshot size boundary drifted: "
            f"exact={copied.returncode} over={over_copy.returncode}"
        )
    copied_digest = copied.stdout.decode("ascii").strip()
    tooling.chmod(0o500)
    intact = run_embedded(
        intact_adb_python,
        tooling,
        exact_snapshot,
        snapshot_identity(exact_snapshot),
        copied_digest,
        size_limit,
        timeout=10,
    )
    tooling.chmod(0o700)
    over_snapshot = tooling / "adb-over"
    over_snapshot.touch(mode=0o600)
    os.truncate(over_snapshot, size_limit + 1)
    over_snapshot.chmod(0o500)
    tooling.chmod(0o500)
    over_intact = run_embedded(
        intact_adb_python,
        tooling,
        over_snapshot,
        snapshot_identity(over_snapshot),
        "0" * 64,
        size_limit,
    )
    tooling.chmod(0o700)
    if intact.returncode != 0 or over_intact.returncode == 0:
        raise SystemExit(
            "ADB snapshot integrity size boundary drifted: "
            f"exact={intact.returncode} over={over_intact.returncode}"
        )

    wanted_functions = {"inode_state", "has_extended_acl", "typed_stop", "stable_bytes"}
    parsed_verifier = ast.parse(verify_python)
    selected = [
        node for node in parsed_verifier.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name in wanted_functions
    ]
    if {node.name for node in selected} != wanted_functions:
        raise SystemExit("cannot extract the offline bounded-reader seam")
    offline_namespace = {
        "errno": errno,
        "hashlib": hashlib,
        "os": os,
        "pathlib": pathlib,
        "stat": stat,
        "subprocess": subprocess,
        "sys": sys,
        "file_states": {},
        "file_digests": {},
        "file_read_limits": {str(exact_source): (size_limit, None)},
    }
    exec(compile(ast.Module(body=selected, type_ignores=[]), sys.argv[1], "exec"), offline_namespace)
    exact_bytes = offline_namespace["stable_bytes"](exact_source, expected_mode=0o500)
    if len(exact_bytes) != size_limit:
        raise SystemExit("offline ADB reader rejected or truncated the exact size cap")
    del exact_bytes
    offline_namespace["file_read_limits"][str(over_source)] = (size_limit, None)
    try:
        offline_namespace["stable_bytes"](over_source, expected_mode=0o500)
    except SystemExit as error:
        if "exceeds its lane read limit" not in str(error):
            raise
    else:
        raise SystemExit("offline ADB reader accepted size cap+1")

trust_signature = "def stable_trust_bytes(path, byte_limit):"
trust_starts = [
    index for index, line in enumerate(source.splitlines())
    if line == trust_signature
]
if len(trust_starts) != 2:
    raise SystemExit(f"expected two bounded trust readers, found {len(trust_starts)}")
trust_sources = []
source_lines = source.splitlines()
for start in trust_starts:
    end = start + 1
    while end < len(source_lines) and (
        not source_lines[end] or source_lines[end][0].isspace()
    ):
        end += 1
    trust_sources.append("\n".join(source_lines[start:end]) + "\n")
if trust_sources[0] != trust_sources[1]:
    raise SystemExit("live and offline repo trust readers have drifted")

trust_namespace = {"os": os, "stat": stat}
exec(compile(trust_sources[0], sys.argv[1], "exec"), trust_namespace)
stable_trust_bytes = trust_namespace["stable_trust_bytes"]
trust_limit = 4096
trust_temporary = tempfile.TemporaryDirectory(prefix="issue66-fc6-trust-inputs-")
trust_root = pathlib.Path(trust_temporary.name)
trust_exact = trust_root / "exact"
trust_over = trust_root / "over"
trust_fifo = trust_root / "fifo"
trust_exact.write_bytes(b"A" * trust_limit)
trust_over.write_bytes(b"B" * (trust_limit + 1))
os.mkfifo(trust_fifo, 0o600)
if stable_trust_bytes(trust_exact, trust_limit) != b"A" * trust_limit:
    raise SystemExit("bounded trust reader rejected its exact byte cap")
for label, candidate in (("cap+1", trust_over), ("FIFO", trust_fifo)):
    started = time.monotonic()
    try:
        stable_trust_bytes(candidate, trust_limit)
    except OSError:
        pass
    else:
        raise SystemExit(f"bounded trust reader accepted {label}")
    if time.monotonic() - started > 0.5:
        raise SystemExit(f"bounded trust reader blocked on {label}")

trust_swap = trust_root / "swap"
trust_replacement = trust_root / "replacement"
trust_detached = trust_root / "detached"
trust_swap.write_bytes(b"S" * trust_limit)
trust_replacement.write_bytes(b"R" * trust_limit)

class TrustSwapOs:
    def __init__(self):
        self.swapped = False

    def __getattr__(self, name):
        return getattr(os, name)

    def read(self, descriptor, amount):
        value = os.read(descriptor, amount)
        if value and not self.swapped:
            self.swapped = True
            os.replace(trust_swap, trust_detached)
            os.replace(trust_replacement, trust_swap)
        return value

swap_namespace = {"os": TrustSwapOs(), "stat": stat}
exec(compile(trust_sources[0], sys.argv[1], "exec"), swap_namespace)
try:
    swap_namespace["stable_trust_bytes"](trust_swap, trust_limit)
except OSError:
    pass
else:
    raise SystemExit("bounded trust reader accepted a pathname swap during read")
trust_temporary.cleanup()

try:
    supervisor_tail = source.split("supervise_adb_receipt() {", 1)[1]
    supervisor_python = (
        supervisor_tail.split("<<'PY'\n", 1)[1].split("\nPY\n}", 1)[0] + "\n"
    )
    complete_start = source.index("complete_supervised_receipt() {")
    complete_end = source.index("\nrun_text_receipt()", complete_start)
    complete_function = source[complete_start:complete_end]
except (IndexError, ValueError) as error:
    raise SystemExit(f"cannot extract FC-6 supervisor seams: {error}") from error

supervisor_lines = supervisor_python.splitlines()
write_signature = "def write_all(output, value):"
write_starts = [
    index for index, line in enumerate(supervisor_lines) if line == write_signature
]
if len(write_starts) != 1:
    raise SystemExit(
        f"expected one supervisor write-all helper, found {len(write_starts)}"
    )
write_start = write_starts[0]
write_end = write_start + 1
while write_end < len(supervisor_lines) and (
    not supervisor_lines[write_end] or supervisor_lines[write_end][0].isspace()
):
    write_end += 1
write_namespace = {}
exec(
    compile(
        "\n".join(supervisor_lines[write_start:write_end]) + "\n",
        str(pathlib.Path(sys.argv[1])),
        "exec",
    ),
    write_namespace,
)

class ShortWriter:
    def __init__(self, maximum):
        self.maximum = maximum
        self.data = bytearray()

    def write(self, value):
        amount = min(self.maximum, len(value))
        self.data.extend(value[:amount])
        return amount

payload = b"0123456789abcdef"
short_writer = ShortWriter(3)
write_namespace["write_all"](short_writer, payload)
if bytes(short_writer.data) != payload:
    raise SystemExit(f"short file writes lost bytes: {bytes(short_writer.data)!r}")
try:
    write_namespace["write_all"](ShortWriter(0), payload)
except OSError:
    pass
else:
    raise SystemExit("zero-progress receipt write was not an internal failure")

class InvalidWriter:
    def __init__(self, result):
        self.result = result

    def write(self, _value):
        return self.result

for label, result in (("None", None), ("oversized", len(payload) + 1)):
    try:
        write_namespace["write_all"](InvalidWriter(result), payload)
    except OSError:
        pass
    else:
        raise SystemExit(f"{label} receipt write progress was not an internal failure")

with tempfile.TemporaryDirectory(prefix="issue66-fc6-internal-") as directory:
    root = pathlib.Path(directory)
    missing = root / "missing"
    supervised = subprocess.run(
        [
            "/usr/bin/python3", "-I", "-", "2", "65536", "32768", "SELFTEST",
            str(missing / "stdout"), str(missing / "stderr"), "/usr/bin/false",
        ],
        input=supervisor_python.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if supervised.returncode != 0 or supervised.stdout != b"INTERNAL\t70\n":
        raise SystemExit(
            "supervisor internal failure protocol drifted: "
            f"rc={supervised.returncode} stdout={supervised.stdout!r} "
            f"stderr={supervised.stderr!r}"
        )

    # A successful leader may fork a same-process-group descendant that closes
    # both inherited pipes before continuing. Pipe EOF plus leader exit=0 is
    # not command completion: the supervisor must stop that group and report a
    # typed fail-closed outcome instead of accepting OK.
    orphan_marker = root / "orphan-late-marker"
    orphan_stdout = root / "orphan.stdout"
    orphan_stderr = root / "orphan.stderr"
    orphan_stdout.touch()
    orphan_stderr.touch()
    orphan_program = (
        "import os,sys,time\n"
        "if os.fork() != 0: os._exit(0)\n"
        "os.close(1); os.close(2)\n"
        "time.sleep(1.0)\n"
        "open(sys.argv[1], 'wb').write(b'orphan\\n')\n"
        "os._exit(0)\n"
    )
    orphaned = subprocess.run(
        [
            "/usr/bin/python3", "-I", "-", "2", "65536", "32768", "SELFTEST",
            str(orphan_stdout), str(orphan_stderr),
            "/usr/bin/python3", "-I", "-c", orphan_program,
            str(orphan_marker),
        ],
        input=supervisor_python.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=5,
        check=False,
    )
    if orphaned.returncode != 0 or orphaned.stdout != b"ORPHANED_GROUP\t70\n":
        raise SystemExit(
            "successful leader with a live same-group descendant was accepted: "
            f"rc={orphaned.returncode} stdout={orphaned.stdout!r} "
            f"stderr={orphaned.stderr!r}"
        )
    marker_deadline = time.monotonic() + 1.25
    while time.monotonic() < marker_deadline and not orphan_marker.exists():
        time.sleep(0.025)
    if orphan_marker.exists():
        raise SystemExit("orphaned adb descendant survived the bounded group stop")

    environment_stdout = root / "environment.stdout"
    environment_stderr = root / "environment.stderr"
    environment_stdout.touch()
    environment_stderr.touch()
    poison_names = (
        "BASH_ENV", "ENV", "PYTHONHOME", "PYTHONPATH", "PYTHONSTARTUP",
        "LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH",
        "DYLD_INSERT_LIBRARIES",
    )
    environment_program = (
        "import os\n"
        f"names={poison_names!r}\n"
        "print('poison=' + ','.join(name for name in names if name in os.environ))\n"
        "print('home=' + ('present' if os.environ.get('HOME') else 'missing'))\n"
    )
    poisoned_environment = os.environ.copy()
    # The test may itself run under env -i; provide the optional identity input
    # explicitly so this checks preservation independently of the login shell.
    poisoned_environment["HOME"] = str(root / "fixture-home")
    poisoned_environment.update({name: "/definitely/not/allowed" for name in poison_names})
    sanitized = subprocess.run(
        [
            "/usr/bin/python3", "-I", "-", "2", "65536", "32768", "PRODUCTION",
            str(environment_stdout), str(environment_stderr),
            "/usr/bin/python3", "-I", "-c", environment_program,
        ],
        input=supervisor_python.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=poisoned_environment,
        timeout=5,
        check=False,
    )
    if (
        sanitized.returncode != 0
        or sanitized.stdout != b"OK\t0\n"
        or environment_stdout.read_bytes() != b"poison=\nhome=present\n"
    ):
        raise SystemExit(
            "production adb child environment retained loader/startup injection: "
            f"rc={sanitized.returncode} result={sanitized.stdout!r} "
            f"child={environment_stdout.read_bytes()!r} "
            f"stderr={sanitized.stderr!r}"
        )

    receipts = root / "receipts"
    receipts.mkdir()
    prefix = receipts / "devices"
    for suffix in ("command.txt", "start-utc.txt", "stdout.txt", "stderr.bin"):
        pathlib.Path(f"{prefix}.{suffix}").write_bytes(b"")
    shell_harness = (
        "set -uo pipefail\n"
        + complete_function
        + "\n"
        + r'''
timestamp_utc() { printf '%s\n' '2000-01-01T00:00:00Z'; }
adb_snapshot_intact() { return 0; }
stop_now() {
  printf '%s\n' "$1"
  case "$1" in STOP_INTERNAL_*) exit 70 ;; *) exit 99 ;; esac
}
OUTPUT_DIR=$1
complete_supervised_receipt devices "$OUTPUT_DIR/receipts/devices" "$2"
'''
    )
    completed = subprocess.run(
        [
            "/bin/bash", "-p", "-s", "--", str(root),
            supervised.stdout.decode("ascii").strip(),
        ],
        input=shell_harness.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    expected_files = {
        "devices.command.txt", "devices.start-utc.txt", "devices.stdout.txt",
        "devices.stderr.bin", "devices.exit.txt", "devices.end-utc.txt",
    }
    actual_files = {
        path.name for path in receipts.iterdir() if path.name != "stems.txt"
    }
    if (
        completed.returncode != 70
        or completed.stdout != b"STOP_INTERNAL_ADB_SUPERVISOR\n"
        or actual_files != expected_files
        or (receipts / "devices.exit.txt").read_text(encoding="ascii") != "70\n"
        or (receipts / "stems.txt").read_text(encoding="ascii") != "devices\n"
    ):
        raise SystemExit(
            "internal supervisor completion contract drifted: "
            f"rc={completed.returncode} stdout={completed.stdout!r} "
            f"stderr={completed.stderr!r} files={sorted(actual_files)!r}"
        )
PY
)"
FC6_STATIC_RC=$?
if (( FC6_STATIC_RC == 0 )); then
  report ok "FC-6 supervisor has fixed budgets, group termination and typed internal completion"
else
  report fail "FC-6 supervisor has fixed budgets, group termination and typed internal completion" \
    "$FC6_STATIC_DETAIL"
fi

FC6_ARCHIVE_BOUNDARY_DETAIL="$("$PYTHON_BIN" -I - "$COLLECTOR" <<'PY' 2>&1
import io
import pathlib
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

source_path = pathlib.Path(sys.argv[1])
source = source_path.read_text(encoding="utf-8")
lines = source.splitlines()
archive_limits = {
    "apk_members": 16384,
    "services_members": 4096,
    "single_size": 256 * 1024 * 1024,
    "total_size": 512 * 1024 * 1024,
    "ratio": 100,
    "ratio_slack": 1024 * 1024,
}

def extracted_helpers(signature):
    starts = [index for index, line in enumerate(lines) if line == signature]
    if len(starts) != 2:
        raise SystemExit(f"expected two {signature!r} helpers, found {len(starts)}")
    helpers = []
    helper_sources = []
    for start in starts:
        end = start + 1
        while end < len(lines) and (not lines[end] or lines[end][0].isspace()):
            end += 1
        helper_source = "\n".join(lines[start:end]) + "\n"
        helper_sources.append(helper_source)
        namespace = {
            "archive_limits": archive_limits,
            "struct": struct,
            "zipfile": zipfile,
        }
        exec(compile(helper_source, str(source_path), "exec"), namespace)
        helpers.append(namespace[signature.split("(", 1)[0].split()[1]])
    if helper_sources[0] != helper_sources[1]:
        raise SystemExit(f"live and offline {signature!r} implementations drifted")
    return helpers

metadata_helpers = extracted_helpers(
    "def archive_metadata_within_limits(members, kind):"
)
preflight_helpers = extracted_helpers(
    "def archive_directory_preflight(read_at, archive_size, kind):"
)

offline_start = source.index("def stable_archive_file(path, kind):")
offline_end = source.index("\ndef installed_base_path(", offline_start)
offline = source[offline_start:offline_end]
if offline.index("preflight = archive_directory_preflight(") > offline.index(
    "with zipfile.ZipFile("
):
    raise SystemExit("offline ZipFile constructor precedes bounded directory preflight")

live_start = source.index("valid_archive_file() { # path apk|services")
live_end = source.index("\nclassify_devices_inventory_file()", live_start)
live = source[live_start:live_end]
live_order = (
    'getattr(os, "O_NOFOLLOW", 0)',
    'stream = os.fdopen(descriptor, "rb", buffering=0)',
    "preflight = archive_directory_preflight(",
    "preflight_after = os.fstat(stream.fileno())",
    "if preflight_after.st_size > file_limit:",
    "with zipfile.ZipFile(bounded)",
    "opened_after = os.fstat(stream.fileno())",
    "named_after = path.lstat()",
    "if opened_after.st_size > file_limit:",
)
positions = [live.index(fragment) for fragment in live_order]
if positions != sorted(positions) or "zipfile.ZipFile(path" in live:
    raise SystemExit("live archive validation is not pinned to one nofollow descriptor")

class Member:
    def __init__(self, file_size=0, compress_size=0, compress_type=zipfile.ZIP_STORED):
        self.file_size = file_size
        self.compress_size = compress_size
        self.compress_type = compress_type

MIB = 1024 * 1024
SLACK = MIB

def compressed_for(size):
    return max(0, (max(0, size - SLACK) + 99) // 100)

for number, helper in enumerate(metadata_helpers, 1):
    checks = (
        ("APK member cap", [Member()] * 16384, "apk", True),
        ("APK member cap+1", [Member()] * 16385, "apk", False),
        ("services member cap", [Member()] * 4096, "services", True),
        ("services member cap+1", [Member()] * 4097, "services", False),
        ("single cap", [Member(256*MIB, compressed_for(256*MIB))], "apk", True),
        ("single cap+1", [Member(256*MIB+1, compressed_for(256*MIB+1))], "apk", False),
        ("total cap", [Member(256*MIB, 256*MIB)] * 2, "apk", True),
        ("total cap+1", [
            Member(256*MIB, 256*MIB),
            Member(256*MIB, 256*MIB),
            Member(1, 1),
        ], "apk", False),
        ("ratio cap", [Member(100 + SLACK, 1)], "apk", True),
        ("ratio cap+1", [Member(101 + SLACK, 1)], "apk", False),
        ("aggregate ratio cap", [
            Member(SLACK//2, 0), Member(SLACK//2, 0),
        ], "apk", True),
        ("aggregate ratio cap+1", [
            Member(SLACK//2 + 1, 0), Member(SLACK//2, 0),
        ], "apk", False),
        ("stored method", [Member()], "apk", True),
        ("deflated method", [Member(compress_type=zipfile.ZIP_DEFLATED)], "apk", True),
        ("unsupported method", [Member(compress_type=zipfile.ZIP_BZIP2)], "apk", False),
    )
    for label, members, kind, expected in checks:
        actual = helper(members, kind)
        if actual is not expected:
            raise SystemExit(
                f"helper {number} {label}: actual={actual!r} expected={expected!r}"
            )

CENTRAL_HEADER = b"PK\x01\x02" + b"\0" * 42

def legacy_eocd(entries, directory_size, directory_offset=0):
    return struct.pack(
        "<4s4H2LH", b"PK\x05\x06", 0, 0, entries, entries,
        directory_size, directory_offset, 0,
    )

def regular_directory(actual_entries, declared_entries=None):
    if declared_entries is None:
        declared_entries = actual_entries
    directory = CENTRAL_HEADER * actual_entries
    return directory + legacy_eocd(declared_entries, len(directory))

def zip64_declared_directory(entries):
    zip64 = struct.pack(
        "<4sQ2H2L4Q", b"PK\x06\x06", 44, 45, 45, 0, 0,
        entries, entries, 0, 0,
    )
    locator = struct.pack("<4sLQL", b"PK\x06\x07", 0, 0, 1)
    legacy = struct.pack(
        "<4s4H2LH", b"PK\x05\x06", 0, 0, 0xFFFF, 0xFFFF,
        0xFFFFFFFF, 0xFFFFFFFF, 0,
    )
    return zip64 + locator + legacy

malformed = bytearray(CENTRAL_HEADER)
struct.pack_into("<H", malformed, 28, 1)
malformed = bytes(malformed) + legacy_eocd(1, len(malformed))
preflight_checks = (
    ("empty directory", regular_directory(0), "apk", "OK"),
    ("APK exact declared/actual member cap", regular_directory(16384), "apk", "OK"),
    ("services exact declared/actual member cap", regular_directory(4096), "services", "OK"),
    ("APK legacy declared member cap+1", legacy_eocd(16385, 0), "apk", "LIMIT"),
    ("services legacy declared member cap+1", legacy_eocd(4097, 0), "services", "LIMIT"),
    ("APK Zip64 declared member cap+1", zip64_declared_directory(16385), "apk", "LIMIT"),
    (
        "forged-low declaration with actual APK cap+1",
        regular_directory(16385, declared_entries=1),
        "apk",
        "LIMIT",
    ),
    ("truncated central-directory variable field", malformed, "apk", "INVALID"),
)
for number, helper in enumerate(preflight_helpers, 1):
    for label, data, kind, expected in preflight_checks:
        actual = helper(
            lambda offset, length, value=data: value[offset:offset + length],
            len(data),
            kind,
        )
        if actual != expected:
            raise SystemExit(
                f"preflight {number} {label}: actual={actual!r} expected={expected!r}"
            )

live_python_start = live.index("import io\n")
live_python_end = live.rindex("\nPY\n")
live_python = live[live_python_start:live_python_end] + "\n"

class TypedArchiveStop(Exception):
    pass

class ZipFileSentinel:
    ZIP_STORED = zipfile.ZIP_STORED
    ZIP_DEFLATED = zipfile.ZIP_DEFLATED
    BadZipFile = zipfile.BadZipFile
    LargeZipFile = zipfile.LargeZipFile

    def __init__(self):
        self.calls = 0

    def ZipFile(self, *_args, **_kwargs):
        self.calls += 1
        raise AssertionError("ZipFile constructor ran before archive-count preflight")

def typed_stop(marker, _detail):
    raise TypedArchiveStop(marker)

constructor_limit_cases = (
    ("legacy declared count", legacy_eocd(16385, 0)),
    ("Zip64 declared count", zip64_declared_directory(16385)),
    ("forged-low actual count", regular_directory(16385, declared_entries=1)),
)
live_without_zip_import = live_python.replace("import zipfile\n", "", 1)
if live_without_zip_import == live_python:
    raise SystemExit("cannot install live ZipFile constructor sentinel")
with tempfile.TemporaryDirectory(prefix="issue66-fc6-live-cap-") as directory:
    for index, (label, data) in enumerate(constructor_limit_cases):
        archive_path = pathlib.Path(directory) / f"preflight-{index}.apk"
        archive_path.write_bytes(data)
        archive_path.chmod(0o600)
        sentinel = ZipFileSentinel()
        saved_argv = sys.argv
        sys.argv = [
            "-", str(archive_path), "apk", str(1024 * 1024),
            "16384", "4096", str(256 * 1024 * 1024),
            str(512 * 1024 * 1024), "100", str(1024 * 1024),
        ]
        try:
            try:
                exec(
                    compile(live_without_zip_import, str(source_path), "exec"),
                    {"__name__": "__main__", "zipfile": sentinel},
                )
            except SystemExit as error:
                if error.code != 2:
                    raise SystemExit(
                        f"live {label} produced wrong preflight exit: {error.code!r}"
                    ) from error
            else:
                raise SystemExit(f"live {label} did not stop at the preflight limit")
        finally:
            sys.argv = saved_argv
        if sentinel.calls:
            raise SystemExit(f"live {label} constructed ZipFile before stopping")

    over_cap = pathlib.Path(directory) / "archive.apk"
    over_cap.write_bytes(b"A" * 17)
    over_cap.chmod(0o600)
    completed = subprocess.run(
        [
            "/usr/bin/python3", "-I", "-", str(over_cap), "apk", "16",
            "16384", "4096", str(256 * 1024 * 1024),
            str(512 * 1024 * 1024), "100", str(1024 * 1024),
        ],
        input=live_python.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 2:
        raise SystemExit(
            "live initial file-cap violation lost typed rc=2: "
            f"rc={completed.returncode} stderr={completed.stderr.decode(errors='replace')!r}"
        )
PY
)"
FC6_ARCHIVE_BOUNDARY_RC=$?
if (( FC6_ARCHIVE_BOUNDARY_RC == 0 )); then
  report ok "live/offline ZIP preflight and metadata gates share exact bounded contracts"
else
  report fail "live/offline ZIP preflight and metadata gates share exact bounded contracts" \
    "$FC6_ARCHIVE_BOUNDARY_DETAIL"
fi

FC6_GROWTH_LIMIT_DETAIL="$("$PYTHON_BIN" -I - "$COLLECTOR" <<'PY' 2>&1
import hashlib
import os as real_os
import pathlib
import stat
import sys
import tempfile

source_path = pathlib.Path(sys.argv[1])
lines = source_path.read_text(encoding="utf-8").splitlines()
signature = "def stable_bytes(path, expected_mode=0o600):"
try:
    start = lines.index(signature)
except ValueError as error:
    raise SystemExit("offline stable reader missing") from error
end = start + 1
while end < len(lines) and (not lines[end] or lines[end][0].isspace()):
    end += 1

def inode_state(value):
    return (
        value.st_dev, value.st_ino, stat.S_IFMT(value.st_mode),
        value.st_uid, stat.S_IMODE(value.st_mode), value.st_size,
        value.st_mtime_ns, value.st_ctime_ns,
    )

def typed_stop(marker, detail):
    raise SystemExit(f"{marker}: {detail}")

with tempfile.TemporaryDirectory(prefix="issue66-fc6-growth-") as directory:
    path = pathlib.Path(directory) / "archive.bin"
    path.write_bytes(b"A" * 16)
    path.chmod(0o600)

    class OsProxy:
        def __init__(self):
            self.grew = False
            self.read_sizes = []

        def __getattr__(self, name):
            return getattr(real_os, name)

        def read(self, descriptor, amount):
            self.read_sizes.append(amount)
            if not self.grew:
                self.grew = True
                with path.open("ab") as stream:
                    stream.write(b"B" * 1024)
            return real_os.read(descriptor, amount)

    proxy = OsProxy()
    namespace = {
        "hashlib": hashlib,
        "os": proxy,
        "pathlib": pathlib,
        "stat": stat,
        "inode_state": inode_state,
        "has_extended_acl": lambda _path: False,
        "file_states": {},
        "file_digests": {},
        "file_read_limits": {
            str(path): (16, "STOP_APK_ARCHIVE_LIMIT"),
        },
        "typed_stop": typed_stop,
    }
    exec(
        compile("\n".join(lines[start:end]) + "\n", str(source_path), "exec"),
        namespace,
    )
    try:
        namespace["stable_bytes"](path)
    except SystemExit as error:
        if not str(error).startswith("STOP_APK_ARCHIVE_LIMIT:"):
            raise SystemExit(f"growth produced wrong failure: {error}") from error
    else:
        raise SystemExit("same-inode post-fstat growth escaped the lane cap")
    if not proxy.read_sizes or max(proxy.read_sizes) > 17:
        raise SystemExit(f"reader requested beyond remaining+1: {proxy.read_sizes!r}")

    path.write_bytes(b"A" * 16)

    class EofGrowthProxy:
        def __init__(self):
            self.fstat_calls = 0

        def __getattr__(self, name):
            return getattr(real_os, name)

        def fstat(self, descriptor):
            self.fstat_calls += 1
            if self.fstat_calls == 2:
                with path.open("ab") as stream:
                    stream.write(b"B")
            return real_os.fstat(descriptor)

    eof_proxy = EofGrowthProxy()
    namespace = {
        "hashlib": hashlib,
        "os": eof_proxy,
        "pathlib": pathlib,
        "stat": stat,
        "inode_state": inode_state,
        "has_extended_acl": lambda _path: False,
        "file_states": {},
        "file_digests": {},
        "file_read_limits": {
            str(path): (16, "STOP_APK_ARCHIVE_LIMIT"),
        },
        "typed_stop": typed_stop,
    }
    exec(
        compile("\n".join(lines[start:end]) + "\n", str(source_path), "exec"),
        namespace,
    )
    try:
        namespace["stable_bytes"](path)
    except SystemExit as error:
        if not str(error).startswith("STOP_APK_ARCHIVE_LIMIT:"):
            raise SystemExit(f"EOF growth produced wrong failure: {error}") from error
    else:
        raise SystemExit("EOF-to-fstat growth escaped the typed lane cap")
PY
)"
FC6_GROWTH_LIMIT_RC=$?
if (( FC6_GROWTH_LIMIT_RC == 0 )); then
  report ok "offline descriptor reads remain bounded across same-inode growth"
else
  report fail "offline descriptor reads remain bounded across same-inode growth" \
    "$FC6_GROWTH_LIMIT_DETAIL"
fi

FC6_LATE_MARKER="$WORK/fc6-timeout-late-marker"
export FAKE_ADB_LATE_MARKER="$FC6_LATE_MARKER"
export ISSUE66_ADB_TIMEOUT_SECONDS=60
export ISSUE66_ADB_STDOUT_LIMIT=999999999
run_fc6_typed_live_case budget-timeout STOP_ADB_TIMEOUT devices stdout.txt 0 124 \
  "devices -l" "timed-out adb process group"
/bin/sleep 2
if [[ ! -e $FC6_LATE_MARKER ]]; then
  report ok "timeout kills the complete process group before its late marker"
else
  report fail "timeout kills the complete process group before its late marker" \
    "late marker exists: $FC6_LATE_MARKER"
fi
unset FAKE_ADB_LATE_MARKER ISSUE66_ADB_TIMEOUT_SECONDS ISSUE66_ADB_STDOUT_LIMIT

run_fc6_typed_live_case budget-text-stdout-over STOP_ADB_STDOUT_LIMIT devices \
  stdout.txt 65536 125 "devices -l" "text stdout cap+1"
run_fc6_typed_live_case budget-stderr-over STOP_ADB_STDERR_LIMIT devices \
  stderr.bin 32768 126 "devices -l" "stderr cap+1"
for fc6_child_exit in 124 125 126 70; do
  run_fc6_child_exit_case "$fc6_child_exit"
done
run_fc6_success_live_case budget-dual-boundary process-list stdout.txt 65536 \
  "simultaneous exact-cap stdout/stderr"
assert_budget_receipt "$WORK/out-budget-dual-boundary" process-list stderr.bin 32768 0 \
  "simultaneous exact-cap stderr is fully drained"

apk_anchor="-s $AUTHORIZED_SERIAL exec-out cat /data/app/~~issue66/name.caiyao.fakegps-fixture/base.apk"
services_anchor="-s $AUTHORIZED_SERIAL exec-out cat /system/framework/services.jar"
run_fc6_typed_live_case budget-apk-stdout-over STOP_ADB_STDOUT_LIMIT \
  package-name-caiyao-fakegps-apk stdout.bin 3145728 125 "$apk_anchor" \
  "APK stdout cap+1"
run_fc6_success_live_case budget-apk-stdout-boundary \
  package-name-caiyao-fakegps-apk stdout.bin 3145728 \
  "APK stdout exact cap"
run_fc6_typed_live_case budget-services-stdout-over STOP_ADB_STDOUT_LIMIT \
  services-jar stdout.bin 2097152 125 "$services_anchor" \
  "services stdout cap+1"
run_fc6_success_live_case budget-services-stdout-boundary \
  services-jar stdout.bin 2097152 \
  "services stdout exact cap"

run_fc6_tree_rebind_case archive-tree-services-grow \
  STOP_FRAMEWORK_ARCHIVE_LIMIT 21 \
  "same-inode services growth after validation"
run_fc6_tree_rebind_case archive-tree-services-swap \
  STOP_INTERNAL_HASH_FAILED 70 \
  "services pathname replacement after validation"

run_fc6_archive_live_case archive-apk-member-over STOP_APK_ARCHIVE_LIMIT \
  "live APK member-count cap+1"
run_fc6_archive_live_case archive-apk-ratio-over STOP_APK_ARCHIVE_LIMIT \
  "live APK compression-ratio cap+1"
run_fc6_archive_live_case archive-apk-aggregate-ratio-over STOP_APK_ARCHIVE_LIMIT \
  "live APK aggregate compression-ratio cap+1"
run_fc6_archive_live_case archive-apk-method-over STOP_APK_ARCHIVE_LIMIT \
  "live APK unsupported compression method"
run_fc6_archive_live_case archive-services-member-over STOP_FRAMEWORK_ARCHIVE_LIMIT \
  "live services member-count cap+1"

FC6_BASE_OUT="$WORK/out-fc6-member-boundary"
run_collect archive-apk-member-boundary "$AUTHORIZED_SERIAL" "$FC6_BASE_OUT"
if (( RC == 0 )); then
  report ok "live APK exact member-count boundary completes"
else
  report fail "live APK exact member-count boundary completes" "rc=$RC output=$OUT"
fi
run_verify "$FC6_BASE_OUT"
if (( RC == 0 )); then
  report ok "offline verifier accepts the exact APK member-count boundary"
else
  report fail "offline verifier accepts the exact APK member-count boundary" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "exact archive boundary verification performs no adb call"

FC6_METADATA_OVER="$WORK/verify-fc6-command-metadata-over"
cp -R "$FC6_BASE_OUT" "$FC6_METADATA_OVER"
"$PYTHON_BIN" -I - \
  "$FC6_METADATA_OVER/receipts/devices.command.txt" <<'PY'
import pathlib
import sys

pathlib.Path(sys.argv[1]).write_bytes(b"X" * 65537)
PY
run_verify "$FC6_METADATA_OVER"
if (( RC == 21 )) \
    && [[ $OUT == *STOP_INCOMPLETE_RECEIPT* ]] \
    && [[ $OUT == *"exceeds its lane read limit"* ]]; then
  report ok "offline verifier rejects command metadata cap+1 before reading it"
else
  report fail "offline verifier rejects command metadata cap+1 before reading it" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "command metadata cap verification performs no adb call"

FC6_RECEIPT_CARDINALITY="$WORK/verify-fc6-receipt-cardinality-over"
cp -R "$FC6_BASE_OUT" "$FC6_RECEIPT_CARDINALITY"
for ((fc6_extra_index = 0; fc6_extra_index < 513; fc6_extra_index++)); do
  : >"$FC6_RECEIPT_CARDINALITY/receipts/extra-$fc6_extra_index.txt"
done
run_verify "$FC6_RECEIPT_CARDINALITY"
if (( RC == 21 )) \
    && [[ $OUT == *STOP_INCOMPLETE_RECEIPT* ]] \
    && [[ $OUT == *"receipt directory cardinality exceeds 512"* ]]; then
  report ok "offline verifier bounds receipt enumeration before carrier reads"
else
  report fail "offline verifier bounds receipt enumeration before carrier reads" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "receipt cardinality verification performs no adb call"

FC6_ROOT_CARDINALITY="$WORK/verify-fc6-root-cardinality-over"
cp -R "$FC6_BASE_OUT" "$FC6_ROOT_CARDINALITY"
: >"$FC6_ROOT_CARDINALITY/extra-root-entry"
run_verify "$FC6_ROOT_CARDINALITY"
if (( RC == 21 )) \
    && [[ $OUT == *STOP_INCOMPLETE_RECEIPT* ]] \
    && [[ $OUT == *"evidence root cardinality exceeds 4"* ]]; then
  report ok "offline verifier bounds evidence-root enumeration before reads"
else
  report fail "offline verifier bounds evidence-root enumeration before reads" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "evidence-root cardinality verification performs no adb call"

FC6_TOOLING_CARDINALITY="$WORK/verify-fc6-tooling-cardinality-over"
cp -R "$FC6_BASE_OUT" "$FC6_TOOLING_CARDINALITY"
/bin/chmod 700 "$FC6_TOOLING_CARDINALITY/tooling"
: >"$FC6_TOOLING_CARDINALITY/tooling/extra-tool"
/bin/chmod 500 "$FC6_TOOLING_CARDINALITY/tooling"
run_verify "$FC6_TOOLING_CARDINALITY"
if (( RC == 21 )) \
    && [[ $OUT == *STOP_INCOMPLETE_RECEIPT* ]] \
    && [[ $OUT == *"tooling directory cardinality exceeds 1"* ]]; then
  report ok "offline verifier bounds tooling enumeration before reads"
else
  report fail "offline verifier bounds tooling enumeration before reads" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "tooling cardinality verification performs no adb call"

FC6_ADB_SNAPSHOT_OVER="$WORK/verify-fc6-adb-snapshot-over"
cp -R "$FC6_BASE_OUT" "$FC6_ADB_SNAPSHOT_OVER"
/bin/chmod 700 "$FC6_ADB_SNAPSHOT_OVER/tooling"
"$PYTHON_BIN" -I - "$FC6_ADB_SNAPSHOT_OVER/tooling/adb" <<'PY'
import os
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.chmod(0o700)
with path.open("r+b", buffering=0) as stream:
    stream.truncate(64 * 1024 * 1024 + 1)
path.chmod(0o500)
PY
/bin/chmod 500 "$FC6_ADB_SNAPSHOT_OVER/tooling"
run_verify "$FC6_ADB_SNAPSHOT_OVER"
if (( RC == 21 )) \
    && [[ $OUT == *STOP_INCOMPLETE_RECEIPT* ]] \
    && [[ $OUT == *"exceeds its lane read limit"* ]]; then
  report ok "offline verifier rejects adb snapshot cap+1 before reading it"
else
  report fail "offline verifier rejects adb snapshot cap+1 before reading it" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "adb snapshot cap verification performs no adb call"

unset FC6_ADB_SNAPSHOT_OVER FC6_METADATA_OVER FC6_RECEIPT_CARDINALITY \
  FC6_ROOT_CARDINALITY FC6_TOOLING_CARDINALITY fc6_extra_index

fc6_archive_mutations=(
  "apk|file-over|STOP_APK_ARCHIVE_LIMIT"
  "services|file-over|STOP_FRAMEWORK_ARCHIVE_LIMIT"
  "apk|member-over|STOP_APK_ARCHIVE_LIMIT"
  "services|member-over|STOP_FRAMEWORK_ARCHIVE_LIMIT"
  "apk|single-over|STOP_APK_ARCHIVE_LIMIT"
  "apk|total-over|STOP_APK_ARCHIVE_LIMIT"
  "apk|ratio-over|STOP_APK_ARCHIVE_LIMIT"
  "apk|aggregate-ratio-over|STOP_APK_ARCHIVE_LIMIT"
  "apk|method-over|STOP_APK_ARCHIVE_LIMIT"
)
for fc6_case in "${fc6_archive_mutations[@]}"; do
  IFS='|' read -r fc6_kind fc6_mutation fc6_marker <<EOF
$fc6_case
EOF
  fc6_broken="$WORK/verify-fc6-$fc6_kind-$fc6_mutation"
  cp -R "$FC6_BASE_OUT" "$fc6_broken"
  if [[ $fc6_kind == apk ]]; then
    fc6_receipt=package-name-caiyao-fakegps-apk.stdout.bin
    fc6_package=name.caiyao.fakegps
  else
    fc6_receipt=services-jar.stdout.bin
    fc6_package=""
  fi
  write_fc6_archive_mutation "$fc6_broken/receipts/$fc6_receipt" \
    "$fc6_kind" "$fc6_mutation"
  rebind_binary_claim "$fc6_broken" "$fc6_receipt" "$fc6_package"
  rebind_receipt_tree "$fc6_broken"
  run_fc6_verifier_case "$fc6_broken" "$fc6_marker" \
    "offline verifier rejects $fc6_kind $fc6_mutation budget violation"
done

if (( RESOURCE_BUDGET_CONTRACT_ONLY )); then
  printf 'issue66 Moto read-only collector selftest: %d passed, %d failed\n' "$pass" "$fail"
  [[ $fail -eq 0 ]]
  exit
fi

# FC-5: every installed package path is observed initially, immediately before
# its APK read, and immediately afterwards. The three exact pm-path argv and
# unique base path must agree. These cases are intentionally early so the
# focused mode can prove the contract without traversing the full mutation set.
path_change_cases=(
  "package-path-change-before-apk|before|before-APK package path change"
  "package-path-change-after-apk|after|after-APK package path change"
  "package-path-disappear-before-apk|before|before-APK package disappearance"
  "package-path-disappear-after-apk|after|after-APK package disappearance"
)
for path_change_case in "${path_change_cases[@]}"; do
  IFS='|' read -r path_change_scenario path_change_phase path_change_label <<EOF
$path_change_case
EOF
  path_change_out="$WORK/out-$path_change_scenario"
  run_collect "$path_change_scenario" "$AUTHORIZED_SERIAL" "$path_change_out"
  expect_stop "$path_change_label is refused" \
    STOP_PACKAGE_PATH_CHANGED
  expect_exit_code "$path_change_label uses evidence rc=21" 21
  expect_only_authorized_target "$path_change_label stays scoped"
  assert_no_privileged_fallback "$ADB_LOG" \
    "$path_change_label has no privileged fallback"
  if [[ -f $path_change_out/manifest.json ]]; then
    assert_stop_manifest "$path_change_out/manifest.json" STOP_PACKAGE_PATH_CHANGED \
      "$path_change_label manifest preserves the exact reason"
    assert_six_file_receipts "$path_change_out/manifest.json" \
      "$path_change_out/receipts" \
      "$path_change_label preserves strict six-file receipts"
    assert_package_hash_absent "$path_change_out/manifest.json" name.caiyao.fakegps \
      "$path_change_label cannot publish an APK digest"
  else
    report fail "$path_change_label manifest exists" "missing manifest.json"
  fi
  assert_package_path_stop_boundary "$ADB_LOG" "$path_change_phase" \
    "$path_change_label stops at the bracket boundary"
done

FC5_BASE_OUT="$WORK/out-fc5-path-bracket"
run_collect target "$AUTHORIZED_SERIAL" "$FC5_BASE_OUT"
if (( RC == 0 )); then
  report ok "FC-5 positive collection completes"
else
  report fail "FC-5 positive collection completes" "rc=$RC output=$OUT"
fi
if [[ -f $FC5_BASE_OUT/manifest.json ]]; then
  assert_package_path_bracket "$FC5_BASE_OUT/manifest.json" \
    "$FC5_BASE_OUT/receipts" "$ADB_LOG" - \
    "FC-5 positive collection binds three identical package paths" \
    "${KNOWN_PACKAGES[@]}"
else
  report fail "FC-5 positive package-path graph exists" "missing manifest.json"
fi
run_verify "$FC5_BASE_OUT"
if (( RC == 0 )); then
  report ok "offline verifier accepts the intact FC-5 path bracket"
else
  report fail "offline verifier accepts the intact FC-5 path bracket" "rc=$RC output=$OUT"
fi
expect_no_adb_call "intact FC-5 offline verification performs no adb call"

FC5_MISSING_OUT="$WORK/out-fc5-missing-package"
run_collect missing-package "$AUTHORIZED_SERIAL" "$FC5_MISSING_OUT"
if (( RC == 0 )) && [[ -f $FC5_MISSING_OUT/manifest.json ]]; then
  assert_package_path_bracket "$FC5_MISSING_OUT/manifest.json" \
    "$FC5_MISSING_OUT/receipts" "$ADB_LOG" "$MISSING_FIXTURE_PACKAGE" \
    "NOT_INSTALLED remains a single initial pm-path receipt" \
    "${KNOWN_PACKAGES[@]}"
else
  report fail "FC-5 missing-package collection completes" "rc=$RC output=$OUT"
fi

fc5_required=(
  package-name-caiyao-fakegps-path-pre-apk
  package-name-caiyao-fakegps-apk
  package-name-caiyao-fakegps-path-post-apk
)
fc5_fixture_ready=1
for fc5_stem in "${fc5_required[@]}"; do
  [[ -e $FC5_BASE_OUT/receipts/$fc5_stem.command.txt ]] || fc5_fixture_ready=0
done

fc5_mutations=(
  pre-path post-path pre-missing post-missing pre-command post-command
  delete-pre extra-pre swap-bracket
)
if (( fc5_fixture_ready )); then
  for fc5_mutation in "${fc5_mutations[@]}"; do
    fc5_broken="$WORK/verify-fc5-$fc5_mutation"
    cp -R "$FC5_BASE_OUT" "$fc5_broken"
    "$PYTHON_BIN" -I - "$fc5_broken" "$fc5_mutation" <<'PY'
import json
import pathlib
import shutil
import sys

root = pathlib.Path(sys.argv[1])
mutation = sys.argv[2]
receipts = root / "receipts"
prefix = "package-name-caiyao-fakegps"
pre = f"{prefix}-path-pre-apk"
apk = f"{prefix}-apk"
post = f"{prefix}-path-post-apk"

if mutation in {"pre-path", "post-path"}:
    stem = pre if mutation == "pre-path" else post
    (receipts / f"{stem}.stdout.txt").write_text(
        "package:/data/app/~~verifieddrift/name.caiyao.fakegps-fixture/base.apk\n",
        encoding="utf-8",
    )
elif mutation in {"pre-missing", "post-missing"}:
    stem = pre if mutation == "pre-missing" else post
    (receipts / f"{stem}.stdout.txt").write_bytes(b"")
    (receipts / f"{stem}.stderr.bin").write_bytes(b"")
    (receipts / f"{stem}.exit.txt").write_text("1\n", encoding="ascii")
elif mutation in {"pre-command", "post-command"}:
    stem = pre if mutation == "pre-command" else post
    (receipts / f"{stem}.command.txt").write_text(
        "./tooling/adb -s ZY22JHW9M4 shell pm path name.caiyao.fakegps.bench\n",
        encoding="utf-8",
    )
elif mutation == "delete-pre":
    for path in receipts.glob(f"{pre}.*"):
        path.unlink()
    for document_name in ("manifest.json", "summary.json"):
        document_path = root / document_name
        document = json.loads(document_path.read_text(encoding="utf-8"))
        if document_name == "manifest.json":
            document["receiptStems"].remove(pre)
        else:
            document["receiptCount"] -= 1
        document_path.write_text(
            json.dumps(document, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    stems_path = receipts / "stems.txt"
    stems = stems_path.read_text(encoding="utf-8").splitlines()
    stems.remove(pre)
    stems_path.write_text("\n".join(stems) + "\n", encoding="utf-8")
elif mutation == "extra-pre":
    extra = f"{pre}-extra"
    for path in receipts.glob(f"{pre}.*"):
        suffix = path.name[len(pre):]
        shutil.copyfile(path, receipts / f"{extra}{suffix}")
    for document_name in ("manifest.json", "summary.json"):
        document_path = root / document_name
        document = json.loads(document_path.read_text(encoding="utf-8"))
        if document_name == "manifest.json":
            index = document["receiptStems"].index(pre) + 1
            document["receiptStems"].insert(index, extra)
        else:
            document["receiptCount"] += 1
        document_path.write_text(
            json.dumps(document, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    stems_path = receipts / "stems.txt"
    stems = stems_path.read_text(encoding="utf-8").splitlines()
    stems.insert(stems.index(pre) + 1, extra)
    stems_path.write_text("\n".join(stems) + "\n", encoding="utf-8")
elif mutation == "swap-bracket":
    for document_name in ("manifest.json",):
        document_path = root / document_name
        document = json.loads(document_path.read_text(encoding="utf-8"))
        first = document["receiptStems"].index(pre)
        second = document["receiptStems"].index(apk)
        document["receiptStems"][first], document["receiptStems"][second] = (
            document["receiptStems"][second],
            document["receiptStems"][first],
        )
        document_path.write_text(
            json.dumps(document, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    stems_path = receipts / "stems.txt"
    stems = stems_path.read_text(encoding="utf-8").splitlines()
    first = stems.index(pre)
    second = stems.index(apk)
    stems[first], stems[second] = stems[second], stems[first]
    stems_path.write_text("\n".join(stems) + "\n", encoding="utf-8")
else:
    raise SystemExit(f"unknown FC-5 mutation: {mutation}")
PY
    rebind_receipt_tree "$fc5_broken"
    run_fc5_verifier_mutation "$fc5_broken" \
      "offline verifier rejects FC-5 $fc5_mutation mutation"
  done
else
  for fc5_mutation in "${fc5_mutations[@]}"; do
    report fail "offline verifier rejects FC-5 $fc5_mutation mutation" \
      "positive bundle lacks pre/APK/post path bracket"
  done
fi

if (( PACKAGE_PATH_CONTRACT_ONLY )); then
  printf 'issue66 Moto read-only collector selftest: %d passed, %d failed\n' "$pass" "$fail"
  [[ $fail -eq 0 ]]
  exit
fi

# Device collection and offline verification are both authorized against an
# independently reviewed Git commit plus the exact collector bytes. Missing,
# malformed, or stale bindings fail before an output directory or adb process
# can exist. A valid production binding proceeds to the independent ADB-client
# approval gate; /usr/bin/false is deliberately not enrolled there.
BINDING_MISSING_OUT="$WORK/out-review-binding-missing"
run_review_binding_collection_probe "$BINDING_MISSING_OUT"
expect_stop "collection requires an external review binding" STOP_REVIEW_BINDING_REQUIRED
expect_exit_code "missing collection review binding uses local-safety rc=22" 22
expect_no_adb_call "missing collection review binding performs no adb call"
if [ -e "$BINDING_MISSING_OUT" ]; then
  report fail "missing collection review binding creates no evidence root" \
    "unexpected output=$BINDING_MISSING_OUT"
else
  report ok "missing collection review binding creates no evidence root"
fi

BINDING_WRONG_HEAD_OUT="$WORK/out-review-binding-wrong-head"
run_review_binding_collection_probe "$BINDING_WRONG_HEAD_OUT" \
  --reviewed-head "0000000000000000000000000000000000000000" \
  --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256"
expect_stop "collection rejects a stale reviewed HEAD" STOP_REVIEW_BINDING_MISMATCH
expect_exit_code "stale reviewed HEAD uses local-safety rc=22" 22
expect_no_adb_call "stale reviewed HEAD performs no adb call"

BINDING_WRONG_DIGEST_OUT="$WORK/out-review-binding-wrong-digest"
run_review_binding_collection_probe "$BINDING_WRONG_DIGEST_OUT" \
  --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
  --reviewed-collector-sha256 "0000000000000000000000000000000000000000000000000000000000000000"
expect_stop "collection rejects stale reviewed collector bytes" STOP_REVIEW_BINDING_MISMATCH
expect_exit_code "stale reviewed collector digest uses local-safety rc=22" 22
expect_no_adb_call "stale reviewed collector digest performs no adb call"

BINDING_UPPERCASE_OUT="$WORK/out-review-binding-uppercase"
BINDING_UPPERCASE_HEAD="$(printf '%s' "$SELFTEST_REVIEWED_HEAD" | tr '[:lower:]' '[:upper:]')"
run_review_binding_collection_probe "$BINDING_UPPERCASE_OUT" \
  --reviewed-head "$BINDING_UPPERCASE_HEAD" \
  --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256"
expect_stop "collection rejects noncanonical reviewed HEAD text" STOP_REVIEW_BINDING_MISMATCH
expect_exit_code "noncanonical reviewed HEAD uses local-safety rc=22" 22
expect_no_adb_call "noncanonical reviewed HEAD performs no adb call"

BINDING_ACCEPTED_OUT="$WORK/out-review-binding-accepted"
printf '[broken git config\n' >"$WORK/hostile-gitconfig"
export GIT_DIR="$WORK/ambient-git-dir-must-be-ignored"
export GIT_WORK_TREE="$WORK/ambient-work-tree-must-be-ignored"
export GIT_OBJECT_DIRECTORY="$WORK/ambient-object-dir-must-be-ignored"
export GIT_ALTERNATE_OBJECT_DIRECTORIES="$WORK/ambient-alternates-must-be-ignored"
export GIT_REPLACE_REF_BASE="refs/ambient-replacements-must-be-ignored"
export GIT_CONFIG_NOSYSTEM=0
export GIT_CONFIG_SYSTEM="$WORK/hostile-gitconfig"
export GIT_CONFIG_GLOBAL="$WORK/hostile-gitconfig"
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=include.path
export GIT_CONFIG_VALUE_0="$WORK/hostile-gitconfig"
run_review_binding_collection_probe "$BINDING_ACCEPTED_OUT" \
  --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
  --reviewed-collector-sha256 "$SELFTEST_REVIEWED_COLLECTOR_SHA256"
unset GIT_DIR GIT_WORK_TREE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES \
  GIT_REPLACE_REF_BASE GIT_CONFIG_NOSYSTEM GIT_CONFIG_SYSTEM GIT_CONFIG_GLOBAL \
  GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
expect_stop "valid review binding ignores hostile Git environment and reaches ADB approval" \
  STOP_ADB_CLIENT_UNAPPROVED
expect_exit_code "valid review binding reaches ADB approval rc=22" 22
expect_no_adb_call "review binding acceptance does not execute an unapproved adb"
if [ -e "$BINDING_ACCEPTED_OUT" ]; then
  report fail "review binding is checked before evidence-root creation" \
    "unexpected output=$BINDING_ACCEPTED_OUT"
else
  report ok "review binding is checked before evidence-root creation"
fi

run_review_binding_verify_probe "$WORK/absent-review-binding-evidence"
expect_stop "offline verification also requires an external review binding" \
  STOP_REVIEW_BINDING_REQUIRED
expect_exit_code "missing verifier review binding uses local-safety rc=22" 22
expect_no_adb_call "missing verifier review binding performs no adb call"

# Client trust is separate from command classification. The device-facing lane
# accepts only the repo-enrolled production ADB digest; the fake is usable only
# through the explicit SELFTEST lane and can never masquerade as device proof.
UNAPPROVED_PRODUCTION_OUT="$WORK/out-unapproved-production-adb"
run_collect_production "$UNAPPROVED_PRODUCTION_OUT"
expect_stop "fake adb is not approved in the production lane" \
  STOP_ADB_CLIENT_UNAPPROVED
expect_exit_code "unapproved production adb uses local-safety rc=22" 22
expect_no_adb_call "unapproved production adb is rejected before execution"
if [ -e "$UNAPPROVED_PRODUCTION_OUT" ]; then
  report fail "unapproved production adb creates no output tree" \
    "unexpected output=$UNAPPROVED_PRODUCTION_OUT"
else
  report ok "unapproved production adb creates no output tree"
fi

TAMPERED_FAKE_ADB="$WORK/tampered-fake-adb"
cp "$FAKE_ADB" "$TAMPERED_FAKE_ADB"
printf '\n# digest tamper\n' >>"$TAMPERED_FAKE_ADB"
chmod 700 "$TAMPERED_FAKE_ADB"
TAMPERED_FAKE_OUT="$WORK/out-tampered-fake-adb"
run_collect target "$AUTHORIZED_SERIAL" "$TAMPERED_FAKE_OUT" "$TAMPERED_FAKE_ADB"
expect_stop "modified fake adb is rejected from the SELFTEST lane" \
  STOP_ADB_CLIENT_UNAPPROVED
expect_exit_code "modified fake adb rejection uses local-safety rc=22" 22
expect_no_adb_call "modified fake adb is rejected before execution"

# Keep this source-copy fixture under the trusted repository parent chain so
# the intentional allowlist tamper reaches its own gate rather than failing
# earlier on a world-writable system temporary ancestor.
ATTESTATION_COPY="$(mktemp -d "$REPO_ROOT/.issue66-allowlist-fixture.XXXXXX")" \
  || { printf 'selftest cannot create the allowlist fixture\n' >&2; exit 2; }
ATTESTATION_COLLECTOR="$ATTESTATION_COPY/collect-issue66-moto-readonly-preflight.sh"
mkdir -p "$ATTESTATION_COPY/fixtures/issue66-moto-readonly-collector"
cp "$COLLECTOR" "$ATTESTATION_COLLECTOR"
cp "$ADB_ALLOWLIST" \
  "$ATTESTATION_COPY/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
chmod 700 "$ATTESTATION_COLLECTOR"
ATTESTATION_COPY_DIGEST="$("$PYTHON_BIN" -I - "$ATTESTATION_COLLECTOR" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
printf '# unauthorized allowlist rewrite\n' >> \
  "$ATTESTATION_COPY/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
: >"$ADB_LOG"
: >"$POISON_ADB_LOG"
BROKEN_ALLOWLIST_OUT="$WORK/out-broken-adb-allowlist"
OUT="$(
  PATH="$BASE_SELFTEST_PATH" \
  ADB="$POISON_ADB" \
  POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
  FAKE_ADB_LOG="$ADB_LOG" \
    "$ATTESTATION_COLLECTOR" --selftest-fixture --adb "$FAKE_ADB" \
      --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
      --reviewed-collector-sha256 "$ATTESTATION_COPY_DIGEST" \
      --serial "$AUTHORIZED_SERIAL" --output "$BROKEN_ALLOWLIST_OUT" 2>&1
)"
RC=$?
expect_stop "modified repo ADB allowlist fails closed" STOP_INTERNAL_ADB_ALLOWLIST
expect_exit_code "modified repo ADB allowlist uses internal rc=70" 70
expect_no_adb_call "modified repo ADB allowlist is rejected before execution"

# Exercise the online shell-identity gate with an independently enrolled,
# device-free wrapper. The copied collector remains in the SELFTEST lane; its
# private allowlist pins only this wrapper digest. A shell UID paired with a
# root primary GID must stop after the identity command even when the trailing
# groups/context fields otherwise look like canonical Android toybox output.
SHELL_ID_FIXTURE_ROOT="$REPO_ROOT/.issue66-shell-id-fixture-$$"
SHELL_ID_FIXTURE_COLLECTOR="$SHELL_ID_FIXTURE_ROOT/collect-issue66-moto-readonly-preflight.sh"
SHELL_ID_FIXTURE_ADB="$SHELL_ID_FIXTURE_ROOT/shell-id-fake-adb.sh"
SHELL_ID_FIXTURE_ALLOWLIST="$SHELL_ID_FIXTURE_ROOT/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"
mkdir -p "$SHELL_ID_FIXTURE_ROOT/fixtures/issue66-moto-readonly-collector"
cp "$COLLECTOR" "$SHELL_ID_FIXTURE_COLLECTOR"
cat >"$SHELL_ID_FIXTURE_ADB" <<'SHELL_ID_ADB'
#!/usr/bin/env bash
if [ "$*" = 'devices -l' ] && [ -n "${SELFTEST_COLLECTOR_TAMPER_TARGET:-}" ]; then
  target="${SELFTEST_COLLECTOR_TAMPER_TARGET}"
  marker="${SELFTEST_COLLECTOR_TAMPER_MARKER:?SELFTEST_COLLECTOR_TAMPER_MARKER is required}"
  case "$target:$marker" in
    "${SELFTEST_COLLECTOR_TAMPER_ROOT:?SELFTEST_COLLECTOR_TAMPER_ROOT is required}"/*:"$SELFTEST_COLLECTOR_TAMPER_ROOT"/*) ;;
    *) printf 'unsafe collector-tamper fixture path\n' >&2; exit 96 ;;
  esac
  if [ ! -e "$marker" ]; then
    : >"$marker" || exit 96
    printf '\n# selftest runtime collector tamper\n' >>"$target" || exit 96
  fi
fi
if [ "$*" = '-s ZY22JHW9M4 shell id' ]; then
  printf '%s\n' "$*" >>"${FAKE_ADB_LOG:?FAKE_ADB_LOG is required}"
  printf '%s\n' "${SELFTEST_SHELL_ID_OVERRIDE:?SELFTEST_SHELL_ID_OVERRIDE is required}"
  exit 0
fi
exec "${SELFTEST_DELEGATE_ADB:?SELFTEST_DELEGATE_ADB is required}" "$@"
SHELL_ID_ADB
chmod 700 "$SHELL_ID_FIXTURE_COLLECTOR" "$SHELL_ID_FIXTURE_ADB"
SHELL_ID_FIXTURE_ADB_DIGEST="$("$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_ADB" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
"$PYTHON_BIN" -I - "$ADB_ALLOWLIST" "$SHELL_ID_FIXTURE_ALLOWLIST" \
  "$SHELL_ID_FIXTURE_ADB_DIGEST" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="ascii")
destination = pathlib.Path(sys.argv[2])
digest = sys.argv[3]
lines = []
replaced = 0
for line in source.splitlines():
    if line.startswith("SELFTEST\t"):
        line = f"SELFTEST\tissue66-shell-id-online\t{digest}"
        replaced += 1
    lines.append(line)
if replaced != 1:
    raise SystemExit("expected one SELFTEST allowlist row")
destination.write_text("\n".join(lines) + "\n", encoding="ascii")
PY
SHELL_ID_FIXTURE_ALLOWLIST_DIGEST="$("$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_ALLOWLIST" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
"$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_COLLECTOR" \
  "$SHELL_ID_FIXTURE_ALLOWLIST_DIGEST" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
replacement = f'ADB_ALLOWLIST_EXPECTED_SHA256="{sys.argv[2]}"'
text, count = re.subn(
    r'ADB_ALLOWLIST_EXPECTED_SHA256="[0-9a-f]{64}"',
    replacement,
    text,
    count=1,
)
if count != 1:
    raise SystemExit("collector allowlist digest assignment not found exactly once")
path.write_text(text, encoding="utf-8")
PY
SHELL_ID_FIXTURE_COLLECTOR_DIGEST="$("$PYTHON_BIN" -I - "$SHELL_ID_FIXTURE_COLLECTOR" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
: >"$ADB_LOG"
: >"$POISON_ADB_LOG"
SHELL_ID_MIXED_OUT="$WORK/out-shell-id-mixed-primary-root"
OUT="$(
  PATH="$BASE_SELFTEST_PATH" \
  ADB="$POISON_ADB" \
  POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
  SELFTEST_REAL_MKDIR="$REAL_MKDIR" \
  SELFTEST_REAL_MV="$REAL_MV" \
  SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
  SELFTEST_WORK_ROOT="$WORK" \
  SELFTEST_MANIFEST_MV_LOG="$MANIFEST_MV_LOG" \
  SELFTEST_SUMMARY_ORDER_LOG="$SUMMARY_ORDER_LOG" \
  SELFTEST_COLLECTOR_TAMPER_ROOT="$SHELL_ID_FIXTURE_ROOT" \
  SELFTEST_DELEGATE_ADB="$FAKE_ADB" \
  SELFTEST_SHELL_ID_OVERRIDE='uid=2000(shell) gid=0(root) groups=2000(shell) context=u:r:shell:s0' \
  FAKE_ADB_SCENARIO=target \
  FAKE_ADB_LOG="$ADB_LOG" \
    "$SHELL_ID_FIXTURE_COLLECTOR" --selftest-fixture \
      --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
      --reviewed-collector-sha256 "$SHELL_ID_FIXTURE_COLLECTOR_DIGEST" \
      --adb "$SHELL_ID_FIXTURE_ADB" --serial "$AUTHORIZED_SERIAL" \
      --output "$SHELL_ID_MIXED_OUT" 2>&1
)"
RC=$?
expect_stop "shell uid with root primary gid is refused online" \
  STOP_UNPRIVILEGED_SHELL_REQUIRED
expect_exit_code "mixed shell/root identity uses topology/identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell id" \
  "mixed shell/root identity stops every later device read"
expect_only_authorized_target "mixed shell/root identity remains exact-serial scoped"

: >"$ADB_LOG"
: >"$POISON_ADB_LOG"
COLLECTOR_TAMPER_OUT="$WORK/out-runtime-collector-tamper"
COLLECTOR_TAMPER_MARKER="$SHELL_ID_FIXTURE_ROOT/runtime-collector-tampered.marker"
OUT="$(
  PATH="$BASE_SELFTEST_PATH" \
  ADB="$POISON_ADB" \
  POISON_BARE_ADB_LOG="$POISON_ADB_LOG" \
  SELFTEST_REAL_MKDIR="$REAL_MKDIR" \
  SELFTEST_REAL_MV="$REAL_MV" \
  SELFTEST_REAL_PYTHON="$REAL_PYTHON" \
  SELFTEST_WORK_ROOT="$WORK" \
  SELFTEST_MANIFEST_MV_LOG="$MANIFEST_MV_LOG" \
  SELFTEST_SUMMARY_ORDER_LOG="$SUMMARY_ORDER_LOG" \
  SELFTEST_COLLECTOR_TAMPER_ROOT="$SHELL_ID_FIXTURE_ROOT" \
  SELFTEST_DELEGATE_ADB="$FAKE_ADB" \
  SELFTEST_COLLECTOR_TAMPER_TARGET="$SHELL_ID_FIXTURE_COLLECTOR" \
  SELFTEST_COLLECTOR_TAMPER_MARKER="$COLLECTOR_TAMPER_MARKER" \
  FAKE_ADB_SCENARIO=target \
  FAKE_ADB_LOG="$ADB_LOG" \
    "$SHELL_ID_FIXTURE_COLLECTOR" --selftest-fixture \
      --reviewed-head "$SELFTEST_REVIEWED_HEAD" \
      --reviewed-collector-sha256 "$SHELL_ID_FIXTURE_COLLECTOR_DIGEST" \
      --adb "$SHELL_ID_FIXTURE_ADB" --serial "$AUTHORIZED_SERIAL" \
      --output "$COLLECTOR_TAMPER_OUT" 2>&1
)"
RC=$?
expect_stop "collector bytes changed after the first ADB receipt are refused" \
  STOP_REVIEW_BINDING_CHANGED
expect_exit_code "runtime collector change uses local-safety rc=22" 22
assert_no_adb_after "$ADB_LOG" "devices -l" \
  "runtime collector change stops before the next ADB receipt"
if [ -e "$COLLECTOR_TAMPER_MARKER" ]; then
  report ok "runtime collector-change fixture actually changed the reviewed entrypoint"
else
  report fail "runtime collector-change fixture actually changed the reviewed entrypoint" \
    "tamper marker missing"
fi

# G-00 is the non-vacuous positive control. A fully valid fake identity may
# complete only as a compatibility candidate, never as device/#66/FULL proof.
G00_OUT="$WORK/out-g00"
run_collect target "$AUTHORIZED_SERIAL" "$G00_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "G-00 positive control returns rc=0"
else
  report fail "G-00 positive control returns rc=0" "rc=$RC output=$OUT"
fi
expect_poison_unused "G-00 honors the explicit --adb binary"
if [ -s "$ADB_LOG" ]; then
  report ok "G-00 actually exercises the fake adb"
else
  report fail "G-00 actually exercises the fake adb" "fake adb log is empty"
fi
assert_boot_brackets "$ADB_LOG" "G-00 brackets collection with boot_id and uptime"
if [ -d "$G00_OUT" ]; then
  g00_mode="$(file_mode "$G00_OUT")"
  if [ "$g00_mode" = 700 ]; then
    report ok "G-00 evidence root is mode 0700"
  else
    report fail "G-00 evidence root is mode 0700" "mode=$g00_mode"
  fi
else
  report fail "G-00 evidence root is mode 0700" "output directory missing"
fi
if [ -d "$G00_OUT/tooling" ] && [ -f "$G00_OUT/tooling/adb" ]; then
  tooling_mode="$(file_mode "$G00_OUT/tooling")"
  adb_snapshot_mode="$(file_mode "$G00_OUT/tooling/adb")"
  if [ "$tooling_mode" = 500 ] && [ "$adb_snapshot_mode" = 500 ]; then
    report ok "G-00 freezes the private adb snapshot and tooling directory at mode 0500"
  else
    report fail "G-00 freezes the private adb snapshot and tooling directory at mode 0500" \
      "tooling=$tooling_mode adb=$adb_snapshot_mode"
  fi
else
  report fail "G-00 private adb snapshot exists" "missing tooling/adb"
fi
if [ -f "$G00_OUT/manifest.json" ]; then
  assert_manifest_ceiling "$G00_OUT/manifest.json"
  assert_binary_hash_manifest "$G00_OUT/manifest.json" "$G00_OUT/receipts" \
    "G-00 manifest binds services.jar and every installed APK SHA-256"
  assert_tool_hash_binding "$G00_OUT/manifest.json" "$G00_OUT/summary.json" \
    "$G00_OUT/tooling/adb" "$COLLECTOR" "$ADB_ALLOWLIST" \
    "G-00 manifest and summary bind the executed adb snapshot/collector SHA-256"
  assert_receipt_tree_binding "$G00_OUT" \
    "G-00 manifest and summary bind an independently recomputed receipt-tree SHA-256"
  assert_redacted_summary "$G00_OUT/summary.json" "$G00_OUT/manifest.json" \
    "G-00 emits an exact-whitelist coordinate-free summary"
  assert_six_file_receipts "$G00_OUT/manifest.json" "$G00_OUT/receipts"
  assert_public_collection "$G00_OUT/manifest.json" "$G00_OUT/receipts" "$ADB_LOG" - \
    "G-00 captures the complete shell-gated public/static surface" "${KNOWN_PACKAGES[@]}"
  assert_no_privileged_fallback "$ADB_LOG" "G-00 has no privileged/private fallback"
else
  report fail "G-00 manifest exists" "missing $G00_OUT/manifest.json"
fi

# The explicitly selected adb executable is part of the evidence identity. A
# symlink could be retargeted between validation, hashing, and execution, so it
# must be rejected before either the fake transport or the PATH poison runs.
ADB_SYMLINK="$WORK/fake-adb-symlink"
if ! ln -s "$FAKE_ADB" "$ADB_SYMLINK"; then
  printf 'selftest could not create adb symlink fixture: %s\n' "$ADB_SYMLINK" >&2
  exit 2
fi
SYMLINK_ADB_OUT="$WORK/out-symlink-adb"
run_collect target "$AUTHORIZED_SERIAL" "$SYMLINK_ADB_OUT" "$ADB_SYMLINK"
expect_stop "symlink --adb binary is refused" STOP_INVALID_ADB_BINARY
expect_exit_code "symlink --adb binary uses local-safety rc=22" 22
expect_no_adb_call "symlink --adb refusal performs no fake or poison adb call"

# Inherited ADB routing can silently redirect a trusted client to a different
# server. Every supported routing variable is rejected before output or adb.
for server_var in ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT; do
  case "$server_var" in
    ADB_SERVER_SOCKET) server_value=tcp:attacker.invalid:5037 ;;
    ANDROID_ADB_SERVER_ADDRESS) server_value=attacker.invalid ;;
    ANDROID_ADB_SERVER_PORT) server_value=5038 ;;
  esac
  export "$server_var=$server_value"
  server_out="$WORK/out-server-env-$server_var"
  run_collect target "$AUTHORIZED_SERIAL" "$server_out"
  unset "$server_var"
  expect_stop "$server_var routing override is refused" STOP_UNSAFE_ADB_SERVER_ENV
  expect_exit_code "$server_var routing override uses local-safety rc=22" 22
  expect_no_adb_call "$server_var routing override performs no fake or poison adb call"
done

# Replace the validated source inode after the initial STOP manifest but before
# snapshot creation. The snapshotter must compare its already-recorded source
# identity with the opened descriptor and stop without executing either file.
PRESNAPSHOT_ADB="$WORK/presnapshot-adb"
cp "$FAKE_ADB" "$PRESNAPSHOT_ADB"
chmod +x "$PRESNAPSHOT_ADB"
export SELFTEST_PRESNAPSHOT_ADB_SOURCE="$PRESNAPSHOT_ADB"
export SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT="$POISON_ADB"
export SELFTEST_PRESNAPSHOT_ADB_MARKER="$WORK/presnapshot-adb-replaced"
PRESNAPSHOT_OUT="$WORK/out-presnapshot-adb-replace"
run_collect adb-source-presnapshot-replace \
  "$AUTHORIZED_SERIAL" "$PRESNAPSHOT_OUT" "$PRESNAPSHOT_ADB"
unset \
  SELFTEST_PRESNAPSHOT_ADB_SOURCE \
  SELFTEST_PRESNAPSHOT_ADB_REPLACEMENT \
  SELFTEST_PRESNAPSHOT_ADB_MARKER
expect_stop "adb source replacement before snapshot is refused" STOP_INTERNAL_ADB_SNAPSHOT
expect_exit_code "adb source replacement before snapshot uses internal rc=70" 70
if [ -e "$WORK/presnapshot-adb-replaced" ]; then
  report ok "adb source replacement before snapshot fixture reached the vulnerable window"
else
  report fail "adb source replacement before snapshot fixture reached the vulnerable window"
fi
expect_no_adb_call "adb source replacement before snapshot executes neither source nor replacement"

# Replacing the caller-selected adb pathname after the first command must not
# affect later commands: collection executes one private, hashed byte snapshot.
SWAPPABLE_ADB="$WORK/swappable-adb"
cp "$FAKE_ADB" "$SWAPPABLE_ADB"
chmod +x "$SWAPPABLE_ADB"
export FAKE_ADB_REPLACE_SOURCE="$SWAPPABLE_ADB"
export FAKE_ADB_REPLACEMENT="$POISON_ADB"
export FAKE_ADB_REPLACE_MARKER="$WORK/swappable-adb-replaced"
SWAPPABLE_OUT="$WORK/out-swappable-adb"
run_collect target "$AUTHORIZED_SERIAL" "$SWAPPABLE_OUT" "$SWAPPABLE_ADB"
unset FAKE_ADB_REPLACE_SOURCE FAKE_ADB_REPLACEMENT FAKE_ADB_REPLACE_MARKER
if [ "$RC" -eq 0 ]; then
  report ok "adb source replacement cannot change the executed snapshot"
else
  report fail "adb source replacement cannot change the executed snapshot" "rc=$RC output=$OUT"
fi
expect_poison_unused "adb source replacement never executes the replacement"

export FAKE_ADB_REPLACEMENT="$POISON_ADB"
export FAKE_ADB_SNAPSHOT_REPLACE_MARKER="$WORK/adb-snapshot-self-replaced"
SNAPSHOT_REPLACE_OUT="$WORK/out-adb-snapshot-self-replace"
run_collect snapshot-self-replace "$AUTHORIZED_SERIAL" "$SNAPSHOT_REPLACE_OUT"
unset FAKE_ADB_REPLACEMENT FAKE_ADB_SNAPSHOT_REPLACE_MARKER
expect_stop "adb snapshot replacement by the running client is detected" STOP_ADB_SNAPSHOT_CHANGED
expect_exit_code "adb snapshot replacement uses local-safety rc=22" 22
if [ "$(wc -l <"$ADB_LOG" | tr -d ' ')" = 1 ]; then
  report ok "adb snapshot replacement stops before a second command"
else
  report fail "adb snapshot replacement stops before a second command" \
    "adb log=$(tr '\n' ';' <"$ADB_LOG")"
fi
expect_poison_unused "replaced adb snapshot is never executed"

# The collector pins the newly-created evidence inode as its cwd. Replacing the
# caller-visible pathname must neither redirect receipts into the attacker tree
# nor permit a successful final publication.
SWAP_OUT="$WORK/out-path-swap"
SWAP_TARGET="$WORK/path-swap-attacker-target"
mkdir -m 700 "$SWAP_TARGET"
export FAKE_ADB_SWAP_OUTPUT="$SWAP_OUT"
export FAKE_ADB_SWAP_TARGET="$SWAP_TARGET"
export FAKE_ADB_SWAP_MARKER="$WORK/path-swap-marker"
run_collect target "$AUTHORIZED_SERIAL" "$SWAP_OUT"
unset FAKE_ADB_SWAP_OUTPUT FAKE_ADB_SWAP_TARGET FAKE_ADB_SWAP_MARKER
expect_stop "evidence output pathname replacement is refused" STOP_OUTPUT_CHANGED
expect_exit_code "evidence output pathname replacement uses local-safety rc=22" 22
if [ -z "$(find "$SWAP_TARGET" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  report ok "evidence output pathname replacement cannot redirect receipts"
else
  report fail "evidence output pathname replacement cannot redirect receipts" \
    "attacker target received files"
fi
if [ -f "$SWAP_OUT.detached/manifest.json" ]; then
  assert_stop_manifest "$SWAP_OUT.detached/manifest.json" STOP_OUTPUT_CHANGED \
    "detached pinned evidence retains a STOP_OUTPUT_CHANGED manifest"
else
  report fail "detached pinned evidence retains a STOP_OUTPUT_CHANGED manifest" \
    "missing detached manifest"
fi

# The public collector requires an unprivileged adb shell principal. A root
# adbd changes observation semantics and must stop at the first serial-targeted
# identity receipt, before any boot/build/user/process/package/framework read.
ROOT_ADBD_OUT="$WORK/out-root-adbd"
run_collect root-adbd "$AUTHORIZED_SERIAL" "$ROOT_ADBD_OUT"
expect_stop "uid=0 adb shell is refused" STOP_UNPRIVILEGED_SHELL_REQUIRED
expect_exit_code "uid=0 adb shell uses topology/identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell id" \
  "uid=0 stops all adb reads immediately after shell-id"
if [ "$(wc -l <"$ADB_LOG" | tr -d ' ')" = 2 ] \
    && [ "$(sed -n '1p' "$ADB_LOG")" = "devices -l" ] \
    && [ "$(sed -n '2p' "$ADB_LOG")" = "-s $AUTHORIZED_SERIAL shell id" ]; then
  report ok "uid=0 executes no serial-targeted observation before shell identity"
else
  report fail "uid=0 executes no serial-targeted observation before shell identity" \
    "adb log=$(tr '\n' ';' <"$ADB_LOG")"
fi
expect_only_authorized_target "uid=0 refusal stays scoped"
if [ -f "$ROOT_ADBD_OUT/manifest.json" ]; then
  assert_stop_manifest "$ROOT_ADBD_OUT/manifest.json" STOP_UNPRIVILEGED_SHELL_REQUIRED \
    "uid=0 manifest preserves the exact reason"
  assert_six_file_receipts "$ROOT_ADBD_OUT/manifest.json" "$ROOT_ADBD_OUT/receipts" \
    "uid=0 refusal preserves strict six-file receipts"
else
  report fail "uid=0 refusal manifest exists" "missing manifest.json"
fi

SHELL_ID_MULTILINE_OUT="$WORK/out-shell-id-multiline"
run_collect shell-id-multiline "$AUTHORIZED_SERIAL" "$SHELL_ID_MULTILINE_OUT"
expect_stop "multi-line shell identity is refused" STOP_UNPRIVILEGED_SHELL_REQUIRED
expect_exit_code "multi-line shell identity uses identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell id" \
  "multi-line shell identity stops at its receipt"
expect_only_authorized_target "multi-line shell identity stays scoped"

for selinux_case in \
    "selinux-malformed|unknown SELinux state" \
    "selinux-extra-line|multi-line SELinux state" \
    "selinux-embedded-cr|embedded-CR SELinux state" \
    "selinux-lone-cr|lone-CR SELinux terminator"; do
  IFS='|' read -r selinux_scenario selinux_label <<<"$selinux_case"
  selinux_out="$WORK/out-$selinux_scenario"
  run_collect "$selinux_scenario" "$AUTHORIZED_SERIAL" "$selinux_out"
  expect_stop "$selinux_label is refused" STOP_INCOMPLETE_CORE_RECEIPT
  expect_exit_code "$selinux_label uses evidence rc=21" 21
  assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getenforce" \
    "$selinux_label stops at its receipt"
  expect_only_authorized_target "$selinux_label stays scoped"
  if [ -f "$selinux_out/manifest.json" ]; then
    assert_stop_manifest "$selinux_out/manifest.json" \
      STOP_INCOMPLETE_CORE_RECEIPT \
      "$selinux_label manifest preserves the exact reason"
  else
    report fail "$selinux_label manifest exists" "missing manifest.json"
  fi
done

# User-sensitive reads are frozen to Android user 0. A different current user
# stops at that receipt; it may not continue into process/location/package or
# framework observations for the wrong user context.
WRONG_USER_OUT="$WORK/out-current-user-nonzero"
run_collect current-user-nonzero "$AUTHORIZED_SERIAL" "$WRONG_USER_OUT"
expect_stop "nonzero Android current user is refused" STOP_UNSUPPORTED_USER_0_REQUIRED
expect_exit_code "nonzero Android current user uses topology/identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell am get-current-user" \
  "nonzero current user stops subsequent user-sensitive reads"
expect_only_authorized_target "nonzero current-user refusal stays scoped"
if [ -f "$WRONG_USER_OUT/manifest.json" ]; then
  assert_stop_manifest "$WRONG_USER_OUT/manifest.json" STOP_UNSUPPORTED_USER_0_REQUIRED \
    "nonzero current-user manifest preserves the exact reason"
  assert_six_file_receipts "$WRONG_USER_OUT/manifest.json" "$WRONG_USER_OUT/receipts" \
    "nonzero current-user refusal preserves strict six-file receipts"
else
  report fail "nonzero current-user manifest exists" "missing manifest.json"
fi

# The process surface is column-bounded, but Android toybox is free to pad
# those columns. Header tokens must be exact and every process row must still
# contain a decimal PID plus a nonempty user/name tuple.
for process_case in \
  "process-header-malformed|malformed process header" \
  "process-row-malformed|malformed process row"; do
  IFS='|' read -r process_scenario process_label <<<"$process_case"
  process_out="$WORK/out-$process_scenario"
  run_collect "$process_scenario" "$AUTHORIZED_SERIAL" "$process_out"
  expect_stop "$process_label is refused" STOP_PACKAGE_OBSERVATION_MALFORMED
  expect_exit_code "$process_label uses evidence rc=21" 21
  assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell ps -A -o USER,PID,NAME" \
    "$process_label stops at the process receipt"
done

PROCESS_CRLF_OUT="$WORK/out-process-crlf"
run_collect process-crlf "$AUTHORIZED_SERIAL" "$PROCESS_CRLF_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "CRLF-framed process output remains collectable"
else
  report fail "CRLF-framed process output remains collectable" \
    "rc=$RC output=$OUT"
fi
run_verify "$PROCESS_CRLF_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "CRLF-framed process output remains receipt-verifiable"
else
  report fail "CRLF-framed process output remains receipt-verifiable" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "CRLF process receipt verification performs no adb call"

# The production verifier is a pure host mode. First prove that the intact
# G-00 receipt set is accepted; then corrupt one invariant at a time. A
# corrupted evidence set is an evidence failure (rc=21), never an adb action.
run_verify "$G00_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "host verifier accepts intact G-00 receipts"
else
  report fail "host verifier accepts intact G-00 receipts" "rc=$RC output=$OUT"
fi
expect_no_adb_call "intact host verification performs no adb call"

SHELL_CONTEXT_OUT="$WORK/verify-shell-id-canonical-context"
cp -R "$G00_OUT" "$SHELL_CONTEXT_OUT"
printf '%s\n' \
  'uid=2000(shell) gid=2000(shell) groups=1003(graphics),2000(shell),3003(inet) context=u:r:shell:s0' \
  >"$SHELL_CONTEXT_OUT/receipts/shell-id.stdout.txt"
rebind_receipt_tree "$SHELL_CONTEXT_OUT"
run_verify "$SHELL_CONTEXT_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "host verifier accepts canonical Android shell groups/context identity"
else
  report fail "host verifier accepts canonical Android shell groups/context identity" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "canonical shell groups/context verification performs no adb call"

shell_identity_cases=(
  'mixed-primary-root|uid=2000(shell) gid=0(root) groups=2000(shell) context=u:r:shell:s0|mixed shell uid/root primary gid'
  'supplemental-root|uid=2000(shell) gid=2000(shell) groups=0(root),2000(shell) context=u:r:shell:s0|root supplemental group'
  'wrong-selinux-domain|uid=2000(shell) gid=2000(shell) groups=2000(shell) context=u:r:su:s0|non-shell SELinux domain'
  'arbitrary-suffix|uid=2000(shell) gid=2000(shell) groups=2000(shell) root=true|arbitrary shell-id suffix'
)
for shell_identity_case in "${shell_identity_cases[@]}"; do
  IFS='|' read -r shell_identity_name shell_identity_value shell_identity_label \
    <<<"$shell_identity_case"
  broken="$WORK/verify-shell-id-$shell_identity_name"
  cp -R "$G00_OUT" "$broken"
  printf '%s\n' "$shell_identity_value" >"$broken/receipts/shell-id.stdout.txt"
  rebind_receipt_tree "$broken"
  run_verify "$broken"
  expect_stop "host verifier rejects $shell_identity_label" STOP_INCOMPLETE_RECEIPT
  expect_exit_code "$shell_identity_label uses evidence rc=21" 21
  expect_no_adb_call "$shell_identity_label verification performs no adb call"
done

PYTHON_SHADOW_MARKER="$WORK/python-shadow-executed.marker"
PYTHON_SHADOW_ROOT="$WORK/verify-python-module-shadow"
cp -R "$G00_OUT" "$PYTHON_SHADOW_ROOT"
printf 'open("%s", "w").write("executed")\nraise SystemExit(0)\n' \
  "$PYTHON_SHADOW_MARKER" >"$PYTHON_SHADOW_ROOT/datetime.py"
run_verify "$PYTHON_SHADOW_ROOT"
expect_stop "host verifier rejects an evidence-local Python module shadow" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "evidence-local Python module shadow uses evidence rc=21" 21
if [ -e "$PYTHON_SHADOW_MARKER" ]; then
  report fail "evidence-local Python module is never imported" \
    "shadow module executed"
else
  report ok "evidence-local Python module is never imported"
fi

PYTHONPATH_SHADOW="$WORK/pythonpath-shadow"
mkdir -m 700 "$PYTHONPATH_SHADOW"
printf 'open("%s", "w").write("executed")\nraise SystemExit(0)\n' \
  "$PYTHON_SHADOW_MARKER" >"$PYTHONPATH_SHADOW/datetime.py"
export PYTHONPATH="$PYTHONPATH_SHADOW"
export PYTHONHOME="$PYTHONPATH_SHADOW/false-home"
run_verify "$G00_OUT"
unset PYTHONPATH PYTHONHOME
if [ "$RC" -eq 0 ]; then
  report ok "isolated verifier ignores ambient Python module paths"
else
  report fail "isolated verifier ignores ambient Python module paths" \
    "rc=$RC output=$OUT"
fi
if [ -e "$PYTHON_SHADOW_MARKER" ]; then
  report fail "ambient Python shadow module is never imported" \
    "shadow module executed"
else
  report ok "ambient Python shadow module is never imported"
fi

run_verify_production "$G00_OUT"
expect_stop "production verifier rejects a SELFTEST evidence bundle" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "production verifier SELFTEST rejection uses evidence rc=21" 21
expect_no_adb_call "production verifier SELFTEST rejection performs no adb call"

RELABELLED_SELFTEST_OUT="$WORK/verify-selftest-relabeled-production"
cp -R "$G00_OUT" "$RELABELLED_SELFTEST_OUT"
"$PYTHON_BIN" -I - "$RELABELLED_SELFTEST_OUT/manifest.json" \
  "$RELABELLED_SELFTEST_OUT/summary.json" <<'PY'
import json
import pathlib
import sys

for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["adbClientTrust"] = "REPO_PINNED_SHA256_PRODUCTION"
    document["adbApprovalLane"] = "PRODUCTION"
    document["adbApprovalLabel"] = "platform-tools-37.0.0-macos-google-eqhxz8m8av"
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
run_verify_production "$RELABELLED_SELFTEST_OUT"
expect_stop "fake digest cannot be relabeled as production ADB evidence" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "relabeled fake digest rejection uses evidence rc=21" 21
expect_no_adb_call "relabeled fake digest verification performs no adb call"

# Build a verifier-only positive control with the two tool digests populated.
# The tamper rows below are discriminating only after this intact binding is
# accepted; otherwise an old summary whitelist could make every mutation look
# safely rejected for the wrong reason.
TOOL_DIGEST_BASELINE="$WORK/verify-tool-digest-baseline"
if ! cp -R "$G00_OUT" "$TOOL_DIGEST_BASELINE"; then
  printf 'selftest could not copy tool-digest verifier baseline\n' >&2
  exit 2
fi
if ! "$PYTHON_BIN" -I - "$TOOL_DIGEST_BASELINE/manifest.json" \
    "$TOOL_DIGEST_BASELINE/summary.json" "$FAKE_ADB" "$COLLECTOR" <<'PY'
import hashlib
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])
digests = {
    "adbSha256": hashlib.sha256(pathlib.Path(sys.argv[3]).read_bytes()).hexdigest(),
    "collectorSha256": hashlib.sha256(pathlib.Path(sys.argv[4]).read_bytes()).hexdigest(),
}
for path in (manifest_path, summary_path):
    document = json.loads(path.read_text(encoding="utf-8"))
    document.update(digests)
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
then
  printf 'selftest could not prepare tool-digest verifier baseline\n' >&2
  exit 2
fi
run_verify "$TOOL_DIGEST_BASELINE"
TOOL_DIGEST_BASELINE_RC=$RC
TOOL_DIGEST_BASELINE_OUT=$OUT
if [ "$TOOL_DIGEST_BASELINE_RC" -eq 0 ]; then
  report ok "host verifier accepts intact adb/collector SHA-256 bindings"
else
  report fail "host verifier accepts intact adb/collector SHA-256 bindings" \
    "rc=$TOOL_DIGEST_BASELINE_RC output=$TOOL_DIGEST_BASELINE_OUT"
fi
expect_no_adb_call "intact tool-digest host verification performs no adb call"

for digest_field in adbSha256 collectorSha256; do
  broken="$WORK/verify-wrong-$digest_field"
  if ! cp -R "$TOOL_DIGEST_BASELINE" "$broken"; then
    printf 'selftest could not copy %s mutation fixture\n' "$digest_field" >&2
    exit 2
  fi
  if ! "$PYTHON_BIN" -I - "$broken/manifest.json" "$broken/summary.json" "$digest_field" <<'PY'
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])
field = sys.argv[3]
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
wrong = "0" * 64 if manifest.get(field) != "0" * 64 else "1" * 64
for path in (manifest_path, summary_path):
    document = json.loads(path.read_text(encoding="utf-8"))
    document[field] = wrong
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
  then
    printf 'selftest could not mutate %s fixture\n' "$digest_field" >&2
    exit 2
  fi
  run_verify "$broken"
  label="host verifier rejects wrong but well-shaped $digest_field"
  if [ "$TOOL_DIGEST_BASELINE_RC" -eq 0 ]; then
    expect_stop "$label" STOP_INCOMPLETE_RECEIPT
  else
    report fail "$label" \
      "intact tool-digest baseline was refused, so mutation refusal is non-discriminating"
  fi
  expect_exit_code "$label uses evidence rc=21" 21
  expect_no_adb_call "$label performs no adb call"
done

WRONG_SOURCE_HEAD="$WORK/verify-wrong-source-head"
if ! cp -R "$G00_OUT" "$WRONG_SOURCE_HEAD"; then
  printf 'selftest could not copy source-HEAD mutation fixture\n' >&2
  exit 2
fi
"$PYTHON_BIN" -I - "$WRONG_SOURCE_HEAD/manifest.json" \
  "$WRONG_SOURCE_HEAD/summary.json" <<'PY'
import json
import pathlib
import sys

for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["sourceHead"] = "0" * 40
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
run_verify "$WRONG_SOURCE_HEAD"
expect_stop "host verifier rejects a self-consistent but unreviewed sourceHead" \
  STOP_INCOMPLETE_RECEIPT
expect_exit_code "unreviewed sourceHead uses evidence rc=21" 21
expect_no_adb_call "unreviewed sourceHead verification performs no adb call"

FIRST_STEM="$(sed -n '1p' "$G00_OUT/receipts/stems.txt")"
if [[ $FIRST_STEM =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  report ok "receipt mutation matrix has a safe baseline stem"
else
  report fail "receipt mutation matrix has a safe baseline stem" "stem=$FIRST_STEM"
fi

verify_mutation_stop() { # case-name evidence-root
  run_verify "$2"
  expect_stop "$1" STOP_INCOMPLETE_RECEIPT
  expect_exit_code "$1 uses evidence rc=21" 21
  expect_no_adb_call "$1 performs no adb call"
}

# Round 6: receipt completeness includes the manifest's identity, claim ceiling,
# redaction boundary, and exact fixed-package truth. Mutate one field at a time
# in an otherwise intact G-00 evidence tree; every case is a pure host check.
for manifest_field in \
    schemaVersion \
    mode \
    adbClientTrust \
    adbApprovalLane \
    adbApprovalLabel \
    adbAllowlistSha256 \
    sourceHead \
    authorizedSerial \
    targetSerial \
    status \
    collectionStatus \
    compatibility \
    privilegedInspection \
    coordinateCaptured \
    knownPackages; do
  broken="$WORK/verify-manifest-$manifest_field"
  cp -R "$G00_OUT" "$broken"
  "$PYTHON_BIN" -I - "$broken/manifest.json" "$manifest_field" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
field = sys.argv[2]
manifest = json.loads(path.read_text(encoding="utf-8"))
mutations = {
    "schemaVersion": 99,
    "mode": "MUTATING_DEVICE_RUN",
    "adbClientTrust": "REPO_PINNED_SHA256_PRODUCTION",
    "adbApprovalLane": "PRODUCTION",
    "adbApprovalLabel": "unapproved-label",
    "adbAllowlistSha256": "0" * 64,
    "sourceHead": "0" * 40,
    "authorizedSerial": "OTHER_SERIAL",
    "targetSerial": "OTHER_SERIAL",
    "status": "FULL",
    "collectionStatus": "FULL",
    "compatibility": "ATTESTED",
    "privilegedInspection": "COLLECTED_PRIVILEGED",
    "coordinateCaptured": True,
    "knownPackages": {
        **manifest.get("knownPackages", {}),
        "name.caiyao.fakegps": "FULL",
    },
}
manifest[field] = mutations[field]
path.write_text(json.dumps(manifest, separators=(",", ":")) + "\n", encoding="utf-8")
PY
  verify_mutation_stop "host verifier rejects tampered manifest $manifest_field" "$broken"
done

# `exec-out cat` bytes must remain binary evidence. Merely renaming the exact
# services.jar payload to the text carrier preserves the six-file count but
# destroys the typed receipt contract and must be rejected.
broken="$WORK/verify-services-binary-renamed-text"
cp -R "$G00_OUT" "$broken"
mv "$broken/receipts/services-jar.stdout.bin" \
  "$broken/receipts/services-jar.stdout.txt"
verify_mutation_stop "host verifier rejects services.jar binary carrier renamed as text" "$broken"

# Delete each member of the six-file carrier independently. For stdout the
# baseline may choose text or binary, but exactly one must have existed.
for suffix in command.txt start-utc.txt stdout stderr.bin exit.txt end-utc.txt; do
  broken="$WORK/verify-missing-${suffix//./-}"
  cp -R "$G00_OUT" "$broken"
  if [ "$suffix" = stdout ]; then
    if [ -f "$broken/receipts/$FIRST_STEM.stdout.txt" ]; then
      rm -f "$broken/receipts/$FIRST_STEM.stdout.txt"
    else
      rm -f "$broken/receipts/$FIRST_STEM.stdout.bin"
    fi
  else
    rm -f "$broken/receipts/$FIRST_STEM.$suffix"
  fi
  verify_mutation_stop "host verifier rejects missing $suffix" "$broken"
done

broken="$WORK/verify-dual-stdout"
cp -R "$G00_OUT" "$broken"
if [ -f "$broken/receipts/$FIRST_STEM.stdout.txt" ]; then
  cp "$broken/receipts/$FIRST_STEM.stdout.txt" "$broken/receipts/$FIRST_STEM.stdout.bin"
else
  cp "$broken/receipts/$FIRST_STEM.stdout.bin" "$broken/receipts/$FIRST_STEM.stdout.txt"
fi
verify_mutation_stop "host verifier rejects simultaneous stdout txt/bin" "$broken"

broken="$WORK/verify-bad-exit"
cp -R "$G00_OUT" "$broken"
printf 'seven\n' >"$broken/receipts/$FIRST_STEM.exit.txt"
verify_mutation_stop "host verifier rejects non-decimal exit" "$broken"

broken="$WORK/verify-bad-time"
cp -R "$G00_OUT" "$broken"
printf 'not-rfc3339\n' >"$broken/receipts/$FIRST_STEM.start-utc.txt"
verify_mutation_stop "host verifier rejects non-RFC3339 time" "$broken"

broken="$WORK/verify-undeclared-file"
cp -R "$G00_OUT" "$broken"
printf 'undeclared\n' >"$broken/receipts/undeclared.local.txt"
verify_mutation_stop "host verifier rejects undeclared receipt file" "$broken"

broken="$WORK/verify-stem-mismatch"
cp -R "$G00_OUT" "$broken"
printf 'ghost-stem\n' >>"$broken/receipts/stems.txt"
verify_mutation_stop "host verifier rejects manifest/stems mismatch" "$broken"

# Manifest is itself a claim boundary. An otherwise valid evidence tree may
# not acquire an undeclared affirmative field that the verifier silently
# ignores, even when the signed/frozen fields remain unchanged.
broken="$WORK/verify-extra-affirmative-manifest-key"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken/manifest.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
manifest = json.loads(path.read_text(encoding="utf-8"))
manifest["attested"] = True
path.write_text(json.dumps(manifest, separators=(",", ":")) + "\n", encoding="utf-8")
PY
verify_mutation_stop "host verifier rejects an extra affirmative manifest key" "$broken"

# Duplicate JSON keys create parser-dependent claims. Python's default parser
# is last-wins, so an unsafe first value followed by the expected safe value
# must be rejected explicitly in both machine-readable documents.
for duplicate_target in manifest summary; do
  broken="$WORK/verify-duplicate-$duplicate_target-key"
  cp -R "$G00_OUT" "$broken"
  "$PYTHON_BIN" -I - "$broken/$duplicate_target.json" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
needle = '"devicePass":false'
assert text.count(needle) == 1, text
path.write_text(
    text.replace(needle, '"devicePass":true,"devicePass":false', 1),
    encoding="utf-8",
)
PY
  verify_mutation_stop "host verifier rejects duplicate $duplicate_target claim key" "$broken"
done

# Every receipt stem owns one exact adb argv, not merely a common adb binary.
# Keep the executable path and hash untouched while changing the serial or
# replacing a read with a mutation; both must be rejected without execution.
for argv_mutation in other-serial mutating-command; do
  broken="$WORK/verify-command-$argv_mutation"
  cp -R "$G00_OUT" "$broken"
  "$PYTHON_BIN" -I - "$broken/receipts/serial.command.txt" "$argv_mutation" <<'PY'
import pathlib
import shlex
import sys

path = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
original = shlex.split(path.read_text(encoding="utf-8"))
if not original:
    raise SystemExit("empty baseline command")
adb = original[0]
argv = {
    "other-serial": ["-s", "OTHER_SERIAL", "shell", "getprop", "ro.serialno"],
    "mutating-command": [
        "-s", "ZY22JHW9M4", "shell", "settings", "put", "secure", "location_mode", "3"
    ],
}[mode]
path.write_text(shlex.join([adb, *argv]) + "\n", encoding="utf-8")
PY
  rebind_receipt_tree "$broken"
  verify_mutation_stop "host verifier rejects $argv_mutation receipt argv" "$broken"
done

# Shape-only verification is insufficient: removing a required identity stem
# can be hidden by synchronizing all three indexes, and successful-looking
# scalar/exit carriers can still contradict the frozen observation semantics.
broken="$WORK/verify-required-serial-stem-removed"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
stem = "serial"
manifest_path = root / "manifest.json"
summary_path = root / "summary.json"
stems_path = root / "receipts" / "stems.txt"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
manifest["receiptStems"] = [value for value in manifest["receiptStems"] if value != stem]
manifest_path.write_text(json.dumps(manifest, separators=(",", ":")) + "\n", encoding="utf-8")
summary = json.loads(summary_path.read_text(encoding="utf-8"))
summary["receiptCount"] = len(manifest["receiptStems"])
summary_path.write_text(json.dumps(summary, separators=(",", ":")) + "\n", encoding="utf-8")
stems = [value for value in stems_path.read_text(encoding="utf-8").splitlines() if value != stem]
stems_path.write_text("\n".join(stems) + "\n", encoding="utf-8")
for suffix in ("command.txt", "start-utc.txt", "stdout.txt", "stderr.bin", "exit.txt", "end-utc.txt"):
    (root / "receipts" / f"{stem}.{suffix}").unlink()
PY
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a required identity stem removed from every index" "$broken"

broken="$WORK/verify-identity-stdout-tampered"
cp -R "$G00_OUT" "$broken"
printf 'OTHER_SERIAL\n' >"$broken/receipts/serial.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects serial stdout that contradicts target identity" "$broken"

broken="$WORK/verify-identity-stdout-multiline"
cp -R "$G00_OUT" "$broken"
printf 'ZY22\nJHW9M4\n' >"$broken/receipts/serial.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a multiline scalar that would concatenate to the authorized serial" "$broken"

for scalar_encoding in c1 line-separator; do
  broken="$WORK/verify-fingerprint-$scalar_encoding"
  cp -R "$G00_OUT" "$broken"
  if [ "$scalar_encoding" = c1 ]; then
    printf '\302\205\n' >"$broken/receipts/fingerprint.stdout.txt"
  else
    printf '\342\200\250\n' >"$broken/receipts/fingerprint.stdout.txt"
  fi
  rebind_receipt_tree "$broken"
  verify_mutation_stop \
    "host verifier rejects $scalar_encoding as a scalar line boundary" "$broken"
done

broken="$WORK/verify-fingerprint-splice"
cp -R "$G00_OUT" "$broken"
printf 'motorola/other/other:15/OTHER/2:user/release-keys\n' \
  >"$broken/receipts/fingerprint.stdout.txt"
verify_mutation_stop "host verifier rejects fingerprint bytes spliced from another build" "$broken"

broken="$WORK/verify-boot-pair-splice"
cp -R "$G00_OUT" "$broken"
printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n' \
  >"$broken/receipts/boot-id-start.stdout.txt"
printf 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n' \
  >"$broken/receipts/boot-id-end.stdout.txt"
verify_mutation_stop "host verifier rejects a self-consistent boot pair spliced after collection" "$broken"

broken="$WORK/verify-process-header-only"
cp -R "$G00_OUT" "$broken"
printf '%-12s %6s %-27s\n' USER PID NAME >"$broken/receipts/process-list.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a process receipt truncated to its header" "$broken"

broken="$WORK/verify-process-crlf"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken/receipts/process-list.stdout.txt" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
raw = path.read_bytes()
assert raw.endswith(b"\n") and b"\r" not in raw, raw
path.write_bytes(raw.replace(b"\n", b"\r\n"))
PY
rebind_receipt_tree "$broken"
run_verify "$broken"
if [ "$RC" -eq 0 ]; then
  report ok "host verifier accepts collector-valid CRLF process framing"
else
  report fail "host verifier accepts collector-valid CRLF process framing" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "CRLF process framing verification performs no adb call"

broken="$WORK/verify-process-missing-terminal-lf"
cp -R "$G00_OUT" "$broken"
"$PYTHON_BIN" -I - "$broken/receipts/process-list.stdout.txt" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
raw = path.read_bytes()
assert raw.endswith(b"\n"), raw
path.write_bytes(raw[:-1])
PY
rebind_receipt_tree "$broken"
verify_mutation_stop \
  "host verifier rejects process output without its terminal newline" "$broken"

broken="$WORK/verify-dumpsys-anchor-only"
cp -R "$G00_OUT" "$broken"
printf 'Package [name.caiyao.fakegps] userId=10208\n' \
  >"$broken/receipts/package-name-caiyao-fakegps-dumpsys.stdout.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects a package dump truncated to its anchor" "$broken"

for appops_shape in \
    wrong-operation \
    duplicate-package-row \
    inline-package-row \
    error-tail \
    default-tail \
    bogus-time \
    tab-separator \
    public-op-name \
    op-name-wrong-case \
    mode-wrong-case \
    unknown-mode \
    leading-space \
    trailing-space \
    multiple-space \
    time-without-ago \
    reject-without-ago \
    duration-with-ago \
    duration-negative \
    orphan-duration \
    orphan-running \
    duration-running \
    duration-running-reverse \
    wrong-order \
    metadata-tab \
    duration-missing-ms \
    time-missing-ms \
    reject-missing-ms \
    duration-gap \
    duration-hour-gap \
    duration-minute-gap \
    duration-day-missing-hour \
    duration-day-missing-minute \
    duration-day-missing-second \
    duration-hour-missing-second \
    duration-range \
    duration-minute-range \
    duration-second-range \
    duration-residual-hour-range \
    duration-residual-minute-range \
    duration-residual-second-range \
    duration-ms-range \
    duration-top-ms-range \
    duration-day-overflow \
    duration-day-residual-overflow \
    duration-leading-zero \
    duration-day-leading-zero \
    duration-hour-leading-zero \
    duration-minute-leading-zero \
    duration-second-leading-zero \
    duration-residual-hour-leading-zero \
    duration-residual-minute-leading-zero \
    duration-residual-second-leading-zero \
    duration-top-ms-leading-zero \
    duration-signed-zero \
    duration-negative-signed-zero \
    time-unsigned \
    elapsed-unsigned \
    elapsed-signed-zero \
    duration-unicode-digit \
    unicode-op-name \
    reject-before-time \
    duplicate-time \
    duplicate-reject \
    duplicate-duration \
    duplicate-running \
    no-operations-wrong-default \
    no-operations-missing-default \
    no-operations-extra-line \
    no-operations-wrong-case \
    no-operations-spacing \
    uid-default-deny \
    uid-after-package \
    duplicate-uid \
    uid-metadata \
    uid-wrong-operation \
    lone-cr \
    missing-newline; do
  broken="$WORK/verify-appops-$appops_shape"
  cp -R "$G00_OUT" "$broken"
  case "$appops_shape" in
    wrong-operation) appops_value='FINE_LOCATION: allow' ;;
    duplicate-package-row) appops_value=$'MOCK_LOCATION: allow\nMOCK_LOCATION: deny' ;;
    inline-package-row) appops_value='MOCK_LOCATION: allow; MOCK_LOCATION: deny' ;;
    error-tail) appops_value='MOCK_LOCATION: allow; Error: transport failed' ;;
    default-tail) appops_value='MOCK_LOCATION: allow; Default mode: deny' ;;
    bogus-time) appops_value='MOCK_LOCATION: allow; time=error transport failed' ;;
    tab-separator) appops_value=$'MOCK_LOCATION:\tallow' ;;
    public-op-name) appops_value='android:mock_location: allow' ;;
    op-name-wrong-case) appops_value='mock_location: allow' ;;
    mode-wrong-case) appops_value='MOCK_LOCATION: ALLOW' ;;
    unknown-mode) appops_value='MOCK_LOCATION: mode=5' ;;
    leading-space) appops_value=' MOCK_LOCATION: allow' ;;
    trailing-space) appops_value='MOCK_LOCATION: allow ' ;;
    multiple-space) appops_value='MOCK_LOCATION:  allow' ;;
    time-without-ago) appops_value='MOCK_LOCATION: allow; time=+1s0ms' ;;
    reject-without-ago) appops_value='MOCK_LOCATION: allow; rejectTime=+1s0ms' ;;
    duration-with-ago) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+5s0ms ago' ;;
    duration-negative) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=-1ms' ;;
    orphan-duration) appops_value='MOCK_LOCATION: allow; duration=+1ms' ;;
    orphan-running) appops_value='MOCK_LOCATION: allow (running)' ;;
    duration-running) appops_value='MOCK_LOCATION: allow; time=+1ms ago (running); duration=+5s0ms' ;;
    duration-running-reverse) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+5s0ms (running)' ;;
    wrong-order) appops_value='MOCK_LOCATION: allow; time=+1s0ms ago; duration=+5s0ms; rejectTime=+2s0ms ago' ;;
    metadata-tab) appops_value=$'MOCK_LOCATION: allow;\ttime=+1s0ms ago' ;;
    duration-missing-ms) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+1s' ;;
    time-missing-ms) appops_value='MOCK_LOCATION: allow; time=+1s ago' ;;
    reject-missing-ms) appops_value='MOCK_LOCATION: allow; rejectTime=+1s ago' ;;
    duration-gap) appops_value='MOCK_LOCATION: allow; time=+1d0ms ago' ;;
    duration-hour-gap) appops_value='MOCK_LOCATION: allow; time=+1h0s0ms ago' ;;
    duration-minute-gap) appops_value='MOCK_LOCATION: allow; time=+1m0ms ago' ;;
    duration-day-missing-hour) appops_value='MOCK_LOCATION: allow; time=+1d0m0s0ms ago' ;;
    duration-day-missing-minute) appops_value='MOCK_LOCATION: allow; time=+1d0h0s0ms ago' ;;
    duration-day-missing-second) appops_value='MOCK_LOCATION: allow; time=+1d0h0m0ms ago' ;;
    duration-hour-missing-second) appops_value='MOCK_LOCATION: allow; time=+1h0m0ms ago' ;;
    duration-range) appops_value='MOCK_LOCATION: allow; time=+24h0m0s0ms ago' ;;
    duration-minute-range) appops_value='MOCK_LOCATION: allow; time=+60m0s0ms ago' ;;
    duration-second-range) appops_value='MOCK_LOCATION: allow; time=+60s0ms ago' ;;
    duration-residual-hour-range) appops_value='MOCK_LOCATION: allow; time=+1d24h0m0s0ms ago' ;;
    duration-residual-minute-range) appops_value='MOCK_LOCATION: allow; time=+1h60m0s0ms ago' ;;
    duration-residual-second-range) appops_value='MOCK_LOCATION: allow; time=+1m60s0ms ago' ;;
    duration-ms-range) appops_value='MOCK_LOCATION: allow; time=+1s1000ms ago' ;;
    duration-top-ms-range) appops_value='MOCK_LOCATION: allow; time=+1000ms ago' ;;
    duration-day-overflow) appops_value='MOCK_LOCATION: allow; time=+24856d0h0m0s0ms ago' ;;
    duration-day-residual-overflow) appops_value='MOCK_LOCATION: allow; time=+24855d3h14m8s0ms ago' ;;
    duration-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1s000ms ago' ;;
    duration-day-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01d0h0m0s0ms ago' ;;
    duration-hour-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01h0m0s0ms ago' ;;
    duration-minute-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01m0s0ms ago' ;;
    duration-second-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01s0ms ago' ;;
    duration-residual-hour-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1d00h0m0s0ms ago' ;;
    duration-residual-minute-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1h00m0s0ms ago' ;;
    duration-residual-second-leading-zero) appops_value='MOCK_LOCATION: allow; time=+1m00s0ms ago' ;;
    duration-top-ms-leading-zero) appops_value='MOCK_LOCATION: allow; time=+01ms ago' ;;
    duration-signed-zero) appops_value='MOCK_LOCATION: allow; time=+0ms ago' ;;
    duration-negative-signed-zero) appops_value='MOCK_LOCATION: allow; time=-0ms ago' ;;
    time-unsigned) appops_value='MOCK_LOCATION: allow; time=1ms ago' ;;
    elapsed-unsigned) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=1ms' ;;
    elapsed-signed-zero) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+0ms' ;;
    duration-unicode-digit) appops_value=$'MOCK_LOCATION: allow; time=+1s1\331\241ms ago' ;;
    unicode-op-name) appops_value=$'MOC\342\204\252_LOCATION: allow' ;;
    reject-before-time) appops_value='MOCK_LOCATION: allow; rejectTime=+2s0ms ago; time=+1s0ms ago' ;;
    duplicate-time) appops_value='MOCK_LOCATION: allow; time=+1s0ms ago; time=+2s0ms ago' ;;
    duplicate-reject) appops_value='MOCK_LOCATION: allow; rejectTime=+1ms ago; rejectTime=+2ms ago' ;;
    duplicate-duration) appops_value='MOCK_LOCATION: allow; time=+1ms ago; duration=+1ms; duration=+2ms' ;;
    duplicate-running) appops_value='MOCK_LOCATION: allow; time=+1ms ago (running) (running)' ;;
    no-operations-wrong-default) appops_value=$'No operations.\nDefault mode: ignore' ;;
    no-operations-missing-default) appops_value='No operations.' ;;
    no-operations-extra-line) appops_value=$'No operations.\nDefault mode: deny\nextra' ;;
    no-operations-wrong-case) appops_value=$'no operations.\nDefault mode: deny' ;;
    no-operations-spacing) appops_value=$'No operations.\nDefault mode:  deny' ;;
    uid-default-deny) appops_value='Uid mode: MOCK_LOCATION: deny' ;;
    uid-after-package) appops_value=$'MOCK_LOCATION: allow\nUid mode: MOCK_LOCATION: ignore' ;;
    duplicate-uid) appops_value=$'Uid mode: MOCK_LOCATION: ignore\nUid mode: MOCK_LOCATION: default' ;;
    uid-metadata) appops_value='Uid mode: MOCK_LOCATION: ignore; time=+1ms ago' ;;
    uid-wrong-operation) appops_value='Uid mode: FINE_LOCATION: allow' ;;
    lone-cr) appops_value=$'MOCK_LOCATION: allow\r' ;;
    missing-newline) appops_value='MOCK_LOCATION: allow' ;;
  esac
  if [ "$appops_shape" = missing-newline ] || [ "$appops_shape" = lone-cr ]; then
    printf '%s' "$appops_value" \
      >"$broken/receipts/package-name-caiyao-fakegps-appops.stdout.txt"
  else
    printf '%s\n' "$appops_value" \
      >"$broken/receipts/package-name-caiyao-fakegps-appops.stdout.txt"
  fi
  rebind_receipt_tree "$broken"
  verify_mutation_stop \
    "host verifier rejects $appops_shape AppOps framing" "$broken"
done

# Keep all receipt semantics valid while changing one byte representation. A
# refusal here can come only from the independently recomputed tree digest.
broken="$WORK/verify-stale-receipt-tree-digest"
cp -R "$G00_OUT" "$broken"
printf 'true\r\n' >"$broken/receipts/location-enabled.stdout.txt"
verify_mutation_stop "host verifier rejects a semantics-valid receipt byte change with a stale tree digest" "$broken"

broken="$WORK/verify-world-readable-root"
cp -R "$G00_OUT" "$broken"
chmod 755 "$broken"
verify_mutation_stop "host verifier rejects an evidence root that is not private mode 0700" "$broken"

if [ "$(uname -s)" = Darwin ]; then
  broken="$WORK/verify-root-extended-acl"
  cp -R "$G00_OUT" "$broken"
  if chmod +a 'everyone allow list,search,readattr,readextattr' "$broken"; then
    verify_mutation_stop \
      "host verifier rejects a mode-0700 evidence root with an extended ACL" \
      "$broken"
  else
    report fail "selftest can install a verifier ACL fixture" "chmod +a failed"
  fi
fi

broken="$WORK/verify-adb-snapshot-writable"
cp -R "$G00_OUT" "$broken"
chmod 700 "$broken/tooling/adb"
verify_mutation_stop "host verifier rejects a writable adb snapshot" "$broken"

VERIFY_ROOT_LINK="$WORK/verify-root-link"
ln -s "$G00_OUT" "$VERIFY_ROOT_LINK"
run_verify "$VERIFY_ROOT_LINK/"
if [ "$RC" -eq 21 ] && [[ $OUT == *STOP_INCOMPLETE_RECEIPT* ]]; then
  report ok "host verifier rejects a trailing-slash evidence symlink"
else
  report fail "host verifier rejects a trailing-slash evidence symlink" "rc=$RC output=$OUT"
fi
expect_no_adb_call "trailing-slash evidence symlink verification performs no adb call"

broken="$WORK/verify-manifest-symlink"
cp -R "$G00_OUT" "$broken"
rm "$broken/manifest.json"
ln -s "$G00_OUT/manifest.json" "$broken/manifest.json"
verify_mutation_stop "host verifier rejects a symlinked manifest" "$broken"

broken="$WORK/verify-manifest-fifo"
cp -R "$G00_OUT" "$broken"
rm "$broken/manifest.json"
mkfifo "$broken/manifest.json"
verify_mutation_stop "host verifier rejects a FIFO manifest without reading it" "$broken"

broken="$WORK/verify-services-nonzero-exit"
cp -R "$G00_OUT" "$broken"
printf '13\n' >"$broken/receipts/services-jar.exit.txt"
rebind_receipt_tree "$broken"
verify_mutation_stop "host verifier rejects nonzero services.jar exit" "$broken"

broken="$WORK/verify-truncated-apk-archive"
cp -R "$G00_OUT" "$broken"
printf 'P' >"$broken/receipts/package-name-caiyao-fakegps-apk.stdout.bin"
rebind_receipt_tree "$broken"
"$PYTHON_BIN" -I - "$broken/manifest.json" "$broken/summary.json" <<'PY'
import hashlib
import json
import pathlib
import sys

digest = hashlib.sha256(b"P").hexdigest()
for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["packageApkSha256"]["name.caiyao.fakegps"] = digest
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
verify_mutation_stop "host verifier rejects a digest-rebound truncated APK" "$broken"

broken="$WORK/verify-truncated-services-archive"
cp -R "$G00_OUT" "$broken"
printf 'P' >"$broken/receipts/services-jar.stdout.bin"
rebind_receipt_tree "$broken"
"$PYTHON_BIN" -I - "$broken/manifest.json" "$broken/summary.json" <<'PY'
import hashlib
import json
import pathlib
import sys

digest = hashlib.sha256(b"P").hexdigest()
for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    document = json.loads(path.read_text(encoding="utf-8"))
    document["servicesJarSha256"] = digest
    path.write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
PY
verify_mutation_stop "host verifier rejects digest-rebound truncated services.jar" "$broken"

# Android package-manager prints the base APK followed by any split APKs. The
# collector is intentionally base-byte-only, but it must validate every path,
# select exactly one base.apk, preserve the full receipt, and remain verifiable.
SPLIT_PACKAGE_OUT="$WORK/out-split-package"
run_collect split-package "$AUTHORIZED_SERIAL" "$SPLIT_PACKAGE_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "validated split-package output selects the base APK"
else
  report fail "validated split-package output selects the base APK" "rc=$RC output=$OUT"
fi
run_verify "$SPLIT_PACKAGE_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "split-package collection remains receipt-verifiable"
else
  report fail "split-package collection remains receipt-verifiable" "rc=$RC output=$OUT"
fi
expect_no_adb_call "split-package offline verification performs no adb call"

# A package APK may be read only from one exact validated `pm path` base entry
# for that same fixed package. Shell metacharacters, multiple base APKs, or a
# path naming another package are local-safety failures before exec-out.
for path_scenario in \
    unsafe-pm-path-injection \
    unsafe-pm-path-multiple \
    unsafe-pm-path-wrong-package \
    unsafe-pm-path-dot \
    unsafe-pm-path-dotdot; do
  path_out="$WORK/out-$path_scenario"
  run_collect "$path_scenario" "$AUTHORIZED_SERIAL" "$path_out"
  expect_stop "$path_scenario is refused" STOP_UNSAFE_PACKAGE_PATH
  expect_exit_code "$path_scenario uses local-safety rc=22" 22
  expect_only_authorized_target "$path_scenario stays on the authorized target"
  assert_no_privileged_fallback "$ADB_LOG" "$path_scenario has no privileged fallback"
  if grep -Eq -- "exec-out cat /data/app/" "$ADB_LOG"; then
    report fail "$path_scenario never reads an unvalidated APK path" \
      "apk byte read escaped: $(grep -E -- 'exec-out cat /data/app/' "$ADB_LOG" | tr '\n' ';')"
  else
    report ok "$path_scenario never reads an unvalidated APK path"
  fi
  if [ -f "$path_out/manifest.json" ]; then
    assert_stop_manifest "$path_out/manifest.json" STOP_UNSAFE_PACKAGE_PATH \
      "$path_scenario manifest preserves the exact reason"
    assert_six_file_receipts "$path_out/manifest.json" "$path_out/receipts" \
      "$path_scenario preserves strict six-file receipts"
  else
    report fail "$path_scenario manifest exists" "missing manifest.json"
  fi
done

# Installed-package observation truth table:
#   dumpsys rc0 requires the exact `Package [<pkg>]` anchor;
#   pidof rc0 requires decimal PID tokens, while rc1+empty is NOT_RUNNING;
#   appops rc0 requires the requested android:mock_location row;
#   malformed rc0 output is distinct from a nonzero transport/read failure.
package_observation_cases=(
  "pm-path-stderr|STOP_PACKAGE_OBSERVATION_MALFORMED|installed package path with stderr"
  "dumpsys-malformed|STOP_PACKAGE_OBSERVATION_MALFORMED|malformed dumpsys package anchor"
  "dumpsys-failure|STOP_ADB_READ_FAILED|failed dumpsys package read"
  "pidof-malformed|STOP_PACKAGE_OBSERVATION_MALFORMED|malformed pidof output"
  "pidof-failure|STOP_ADB_READ_FAILED|failed pidof read"
  "appops-malformed|STOP_PACKAGE_OBSERVATION_MALFORMED|malformed mock-location AppOps output"
  "appops-conflict|STOP_PACKAGE_OBSERVATION_MALFORMED|conflicting mock-location AppOps rows"
  "appops-conflict-inline|STOP_PACKAGE_OBSERVATION_MALFORMED|inline conflicting mock-location AppOps rows"
  "appops-public-op-name|STOP_PACKAGE_OBSERVATION_MALFORMED|public-string AppOps operation name"
  "appops-op-name-wrong-case|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong-case AppOps operation name"
  "appops-mode-wrong-case|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong-case AppOps mode"
  "appops-unknown-mode|STOP_PACKAGE_OBSERVATION_MALFORMED|unknown AppOps mode"
  "appops-leading-space|STOP_PACKAGE_OBSERVATION_MALFORMED|leading-space AppOps row"
  "appops-trailing-space|STOP_PACKAGE_OBSERVATION_MALFORMED|trailing-space AppOps row"
  "appops-multiple-space|STOP_PACKAGE_OBSERVATION_MALFORMED|multi-space AppOps separator"
  "appops-error-tail|STOP_PACKAGE_OBSERVATION_MALFORMED|error-bearing mock-location AppOps metadata"
  "appops-default-tail|STOP_PACKAGE_OBSERVATION_MALFORMED|conflicting default-mode AppOps metadata"
  "appops-bogus-time|STOP_PACKAGE_OBSERVATION_MALFORMED|non-duration AppOps time metadata"
  "appops-tab-spacing|STOP_PACKAGE_OBSERVATION_MALFORMED|control-whitespace AppOps separator"
  "appops-time-without-ago|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps time metadata without ago"
  "appops-reject-without-ago|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps rejectTime metadata without ago"
  "appops-duration-with-ago|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration metadata with ago"
  "appops-duration-negative|STOP_PACKAGE_OBSERVATION_MALFORMED|negative AppOps elapsed duration"
  "appops-orphan-duration|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration without access time"
  "appops-orphan-running|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps running marker without access time"
  "appops-duration-running|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration combined with running"
  "appops-duration-running-reverse|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps running appended after duration"
  "appops-wrong-order|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-order AppOps metadata"
  "appops-metadata-tab|STOP_PACKAGE_OBSERVATION_MALFORMED|tab-prefixed AppOps metadata"
  "appops-duration-missing-ms|STOP_PACKAGE_OBSERVATION_MALFORMED|nonzero AppOps duration without milliseconds"
  "appops-time-missing-ms|STOP_PACKAGE_OBSERVATION_MALFORMED|nonzero AppOps time without milliseconds"
  "appops-reject-missing-ms|STOP_PACKAGE_OBSERVATION_MALFORMED|nonzero AppOps rejectTime without milliseconds"
  "appops-duration-gap|STOP_PACKAGE_OBSERVATION_MALFORMED|noncanonical AppOps duration unit gap"
  "appops-duration-hour-gap|STOP_PACKAGE_OBSERVATION_MALFORMED|hour-to-second AppOps duration unit gap"
  "appops-duration-minute-gap|STOP_PACKAGE_OBSERVATION_MALFORMED|minute-to-millisecond AppOps duration unit gap"
  "appops-duration-day-missing-hour|STOP_PACKAGE_OBSERVATION_MALFORMED|day-to-minute AppOps duration unit gap"
  "appops-duration-day-missing-minute|STOP_PACKAGE_OBSERVATION_MALFORMED|day-to-second AppOps duration unit gap"
  "appops-duration-day-missing-second|STOP_PACKAGE_OBSERVATION_MALFORMED|day-to-millisecond AppOps duration unit gap"
  "appops-duration-hour-missing-second|STOP_PACKAGE_OBSERVATION_MALFORMED|hour-to-millisecond AppOps duration unit gap"
  "appops-duration-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps duration field"
  "appops-duration-minute-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps minute field"
  "appops-duration-second-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps second field"
  "appops-duration-residual-hour-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps residual hour field"
  "appops-duration-residual-minute-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps residual minute field"
  "appops-duration-residual-second-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps residual second field"
  "appops-duration-ms-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range AppOps millisecond field"
  "appops-duration-top-ms-range|STOP_PACKAGE_OBSERVATION_MALFORMED|out-of-range top-level AppOps millisecond field"
  "appops-duration-day-overflow|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration beyond the TimeUtils day ceiling"
  "appops-duration-day-residual-overflow|STOP_PACKAGE_OBSERVATION_MALFORMED|AppOps duration beyond the TimeUtils residual ceiling"
  "appops-duration-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps duration field"
  "appops-duration-day-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps day field"
  "appops-duration-hour-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps hour field"
  "appops-duration-minute-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps minute field"
  "appops-duration-second-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps second field"
  "appops-duration-residual-hour-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps residual hour field"
  "appops-duration-residual-minute-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps residual minute field"
  "appops-duration-residual-second-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded AppOps residual second field"
  "appops-duration-top-ms-leading-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|zero-padded top-level AppOps millisecond field"
  "appops-duration-signed-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|signed-zero AppOps duration"
  "appops-duration-negative-signed-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|negative signed-zero AppOps duration"
  "appops-time-unsigned|STOP_PACKAGE_OBSERVATION_MALFORMED|unsigned nonzero AppOps time"
  "appops-elapsed-unsigned|STOP_PACKAGE_OBSERVATION_MALFORMED|unsigned nonzero AppOps elapsed duration"
  "appops-elapsed-signed-zero|STOP_PACKAGE_OBSERVATION_MALFORMED|signed-zero AppOps elapsed duration"
  "appops-duration-unicode-digit|STOP_PACKAGE_OBSERVATION_MALFORMED|Unicode-digit AppOps duration"
  "appops-unicode-op-name|STOP_PACKAGE_OBSERVATION_MALFORMED|Unicode-folded AppOps operation name"
  "appops-reject-before-time|STOP_PACKAGE_OBSERVATION_MALFORMED|rejectTime preceding AppOps time"
  "appops-duplicate-time|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps time field"
  "appops-duplicate-reject|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps rejectTime field"
  "appops-duplicate-duration|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps duration field"
  "appops-duplicate-running|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate AppOps running marker"
  "appops-no-operations-wrong-default|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong default mode for empty mock-location AppOps"
  "appops-no-operations-missing-default|STOP_PACKAGE_OBSERVATION_MALFORMED|missing default line for empty mock-location AppOps"
  "appops-no-operations-extra-line|STOP_PACKAGE_OBSERVATION_MALFORMED|extra line after empty mock-location AppOps"
  "appops-no-operations-wrong-case|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong-case empty mock-location AppOps"
  "appops-no-operations-spacing|STOP_PACKAGE_OBSERVATION_MALFORMED|multi-space empty mock-location AppOps default"
  "appops-uid-default-deny|STOP_PACKAGE_OBSERVATION_MALFORMED|default-valued UID mock-location AppOps row"
  "appops-uid-after-package|STOP_PACKAGE_OBSERVATION_MALFORMED|UID AppOps row after package row"
  "appops-duplicate-uid|STOP_PACKAGE_OBSERVATION_MALFORMED|duplicate UID AppOps rows"
  "appops-uid-metadata|STOP_PACKAGE_OBSERVATION_MALFORMED|metadata attached to UID AppOps row"
  "appops-uid-wrong-operation|STOP_PACKAGE_OBSERVATION_MALFORMED|wrong operation in UID AppOps row"
  "appops-missing-newline|STOP_PACKAGE_OBSERVATION_MALFORMED|missing AppOps line terminator"
  "appops-lone-cr|STOP_PACKAGE_OBSERVATION_MALFORMED|lone-CR AppOps terminator"
  "appops-failure|STOP_ADB_READ_FAILED|failed mock-location AppOps read"
)
for observation_case in "${package_observation_cases[@]}"; do
  IFS='|' read -r observation_scenario observation_marker observation_label \
    <<<"$observation_case"
  observation_out="$WORK/out-$observation_scenario"
  run_collect "$observation_scenario" "$AUTHORIZED_SERIAL" "$observation_out"
  expect_stop "$observation_label is refused" "$observation_marker"
  expect_exit_code "$observation_label uses evidence rc=21" 21
  expect_only_authorized_target "$observation_label stays scoped"
  assert_no_privileged_fallback "$ADB_LOG" "$observation_label has no privileged fallback"
  if [ -f "$observation_out/manifest.json" ]; then
    assert_stop_manifest "$observation_out/manifest.json" "$observation_marker" \
      "$observation_label manifest preserves the exact reason"
    assert_six_file_receipts "$observation_out/manifest.json" "$observation_out/receipts" \
      "$observation_label preserves strict six-file receipts"
  else
    report fail "$observation_label manifest exists" "missing manifest.json"
  fi
done

PIDOF_IDLE_OUT="$WORK/out-pidof-not-running"
run_collect pidof-not-running "$AUTHORIZED_SERIAL" "$PIDOF_IDLE_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "installed package pidof rc=1+empty records NOT_RUNNING without failing"
else
  report fail "installed package pidof rc=1+empty records NOT_RUNNING without failing" \
    "rc=$RC output=$OUT"
fi

APPOPS_METADATA_OUT="$WORK/out-appops-metadata"
run_collect appops-metadata "$AUTHORIZED_SERIAL" "$APPOPS_METADATA_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped AppOps time metadata remains accepted"
else
  report fail "official-shaped AppOps time metadata remains accepted" \
    "rc=$RC output=$OUT"
fi
run_verify "$APPOPS_METADATA_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped AppOps metadata remains receipt-verifiable"
else
  report fail "official-shaped AppOps metadata remains receipt-verifiable" \
    "rc=$RC output=$OUT"
fi

APPOPS_RUNNING_OUT="$WORK/out-appops-running"
run_collect appops-running "$AUTHORIZED_SERIAL" "$APPOPS_RUNNING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped running AppOps row remains accepted"
else
  report fail "official-shaped running AppOps row remains accepted" \
    "rc=$RC output=$OUT"
fi
run_verify "$APPOPS_RUNNING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "official-shaped running AppOps row remains receipt-verifiable"
else
  report fail "official-shaped running AppOps row remains receipt-verifiable" \
    "rc=$RC output=$OUT"
fi

APPOPS_BOUNDARY_OUT="$WORK/out-appops-canonical-boundaries"
run_collect appops-canonical-boundaries "$AUTHORIZED_SERIAL" "$APPOPS_BOUNDARY_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "canonical day/hour/millisecond AppOps boundaries remain accepted"
else
  report fail "canonical day/hour/millisecond AppOps boundaries remain accepted" \
    "rc=$RC output=$OUT"
fi
run_verify "$APPOPS_BOUNDARY_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "canonical AppOps duration boundaries remain receipt-verifiable"
else
  report fail "canonical AppOps duration boundaries remain receipt-verifiable" \
    "rc=$RC output=$OUT"
fi

for positive_appops_case in \
    "appops-canonical-zero-negative|canonical zero/negative AppOps durations" \
    "appops-no-operations|canonical no-operations AppOps response" \
    "appops-uid-mode|canonical UID-level AppOps override" \
    "appops-uid-allow|canonical UID-level allow AppOps override" \
    "appops-uid-foreground|canonical UID-level foreground AppOps override" \
    "appops-uid-and-package|canonical UID plus package AppOps response" \
    "appops-mode-default|canonical default AppOps mode" \
    "appops-mode-foreground|canonical foreground AppOps mode" \
    "appops-crlf|CRLF-normalized canonical AppOps response" \
    "appops-canonical-minute-second|canonical minute/second AppOps boundaries" \
    "appops-canonical-day-hour-boundaries|canonical 23-hour and multi-day AppOps boundaries" \
    "appops-canonical-max-duration|canonical maximum TimeUtils AppOps duration" \
    "appops-reject-only|canonical reject-only package AppOps response" \
    "appops-time-only|canonical time-only package AppOps response" \
    "appops-time-reject-only|canonical time-plus-reject AppOps response" \
    "appops-time-reject-running|canonical time-plus-reject running AppOps response" \
    "appops-elapsed-day|canonical day AppOps elapsed duration" \
    "appops-elapsed-hour|canonical hour AppOps elapsed duration" \
    "appops-elapsed-minute|canonical minute AppOps elapsed duration"; do
  IFS='|' read -r positive_appops_scenario positive_appops_label \
    <<<"$positive_appops_case"
  positive_appops_out="$WORK/out-$positive_appops_scenario"
  run_collect "$positive_appops_scenario" "$AUTHORIZED_SERIAL" "$positive_appops_out"
  if [ "$RC" -eq 0 ]; then
    report ok "$positive_appops_label remains accepted"
  else
    report fail "$positive_appops_label remains accepted" "rc=$RC output=$OUT"
  fi
  run_verify "$positive_appops_out"
  if [ "$RC" -eq 0 ]; then
    report ok "$positive_appops_label remains receipt-verifiable"
  else
    report fail "$positive_appops_label remains receipt-verifiable" \
      "rc=$RC output=$OUT"
  fi
done

archive_cases=(
  "apk-truncated|STOP_APK_READ_FAILED|single-byte APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-empty-archive|STOP_APK_READ_FAILED|empty APK ZIP|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-duplicate-member|STOP_APK_READ_FAILED|duplicate-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-parent-member|STOP_APK_READ_FAILED|parent-traversing-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-absolute-member|STOP_APK_READ_FAILED|absolute-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-nul-member|STOP_APK_READ_FAILED|NUL-suffixed-member APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-missing-manifest|STOP_APK_READ_FAILED|manifest-free APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "apk-crc-corrupt|STOP_APK_READ_FAILED|CRC-corrupt APK|package-name-caiyao-fakegps-apk.stdout.bin"
  "services-truncated|STOP_FRAMEWORK_READ_FAILED|single-byte services.jar|services-jar.stdout.bin"
  "services-missing-dex|STOP_FRAMEWORK_READ_FAILED|dex-free services.jar|services-jar.stdout.bin"
  "services-nul-member|STOP_FRAMEWORK_READ_FAILED|NUL-suffixed-dex services.jar|services-jar.stdout.bin"
  "services-crc-corrupt|STOP_FRAMEWORK_READ_FAILED|CRC-corrupt services.jar|services-jar.stdout.bin"
)
for archive_case in "${archive_cases[@]}"; do
  IFS='|' read -r archive_scenario archive_marker archive_label archive_receipt <<EOF
$archive_case
EOF
  archive_out="$WORK/out-$archive_scenario"
  run_collect "$archive_scenario" "$AUTHORIZED_SERIAL" "$archive_out"
  expect_stop "$archive_label output is refused" "$archive_marker"
  expect_exit_code "$archive_label output uses evidence rc=21" 21
  expect_only_authorized_target "$archive_label refusal stays scoped"
  if [ -f "$archive_out/manifest.json" ]; then
    assert_stop_manifest "$archive_out/manifest.json" "$archive_marker" \
      "$archive_label manifest preserves the exact reason"
  else
    report fail "$archive_label manifest exists" "missing manifest.json"
  fi
  archive_stem=${archive_receipt%.stdout.bin}
  if [ -f "$archive_out/receipts/$archive_stem.exit.txt" ] \
      && [ "$(tr -d '\r\n' <"$archive_out/receipts/$archive_stem.exit.txt")" = 0 ] \
      && [ ! -s "$archive_out/receipts/$archive_stem.stderr.bin" ]; then
    report ok "$archive_label reaches the archive parser after adb rc=0"
  else
    report fail "$archive_label reaches the archive parser after adb rc=0" \
      "missing/nonzero exit or nonempty stderr"
  fi

  broken="$WORK/verify-$archive_scenario"
  cp -R "$G00_OUT" "$broken"
  if [ -f "$archive_out/receipts/$archive_receipt" ]; then
    cp "$archive_out/receipts/$archive_receipt" \
      "$broken/receipts/$archive_receipt"
    rebind_receipt_tree "$broken"
    if [ "$archive_receipt" = services-jar.stdout.bin ]; then
      rebind_binary_claim "$broken" "$archive_receipt"
    else
      rebind_binary_claim "$broken" "$archive_receipt" name.caiyao.fakegps
    fi
    verify_mutation_stop \
      "host verifier rejects $archive_label bytes after digest rebinding" "$broken"
  else
    report fail "host verifier fixture retains $archive_label bytes" \
      "missing $archive_out/receipts/$archive_receipt"
  fi
done

# Framework bytes are required static evidence. Preserve a real exec-out
# failure as binary six-file evidence and never retry through su/root.
FRAMEWORK_FAIL_OUT="$WORK/out-services-exit13"
run_collect services-exit13 "$AUTHORIZED_SERIAL" "$FRAMEWORK_FAIL_OUT"
expect_stop "services.jar adb rc=13 is an explicit framework failure" \
  STOP_FRAMEWORK_READ_FAILED
expect_exit_code "services.jar adb rc=13 uses evidence rc=21" 21
expect_only_authorized_target "services.jar failure stays scoped"
assert_no_privileged_fallback "$ADB_LOG" "services.jar failure has no su/root fallback"
if [ -f "$FRAMEWORK_FAIL_OUT/manifest.json" ]; then
  assert_stop_manifest "$FRAMEWORK_FAIL_OUT/manifest.json" STOP_FRAMEWORK_READ_FAILED \
    "services.jar failure manifest preserves the exact reason"
  assert_six_file_receipts "$FRAMEWORK_FAIL_OUT/manifest.json" "$FRAMEWORK_FAIL_OUT/receipts" \
    "services.jar failure preserves strict six-file receipts"
else
  report fail "services.jar failure manifest exists" "missing manifest.json"
fi
if [ -f "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stdout.bin" ] \
    && [ ! -e "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stdout.txt" ]; then
  report ok "services.jar failure uses stdout.bin only"
else
  report fail "services.jar failure uses stdout.bin only" "binary receipt missing or duplicated as text"
fi
if [ -f "$FRAMEWORK_FAIL_OUT/receipts/services-jar.exit.txt" ] \
    && [ "$(tr -d '\r\n' <"$FRAMEWORK_FAIL_OUT/receipts/services-jar.exit.txt")" = 13 ]; then
  report ok "services.jar receipt preserves adb exit=13"
else
  report fail "services.jar receipt preserves adb exit=13" "exit receipt missing or changed"
fi
if [ -f "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stderr.bin" ] \
    && grep -F -q "fixture services.jar transport failure" \
      "$FRAMEWORK_FAIL_OUT/receipts/services-jar.stderr.bin"; then
  report ok "services.jar receipt preserves adb stderr"
else
  report fail "services.jar receipt preserves adb stderr" "fixture stderr missing"
fi

# A known package may legitimately be absent. Its AOSP-shaped `pm path`
# result is rc=1 with empty stdout/stderr; record NOT_INSTALLED and skip that package's
# dumpsys/pidof/appops/APK reads while completing the public collection.
MISSING_OUT="$WORK/out-missing-known-package"
run_collect missing-package "$AUTHORIZED_SERIAL" "$MISSING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "missing known package does not fail public collection"
else
  report fail "missing known package does not fail public collection" "rc=$RC output=$OUT"
fi
if [ -f "$MISSING_OUT/manifest.json" ]; then
  assert_manifest_ceiling "$MISSING_OUT/manifest.json"
  assert_binary_hash_manifest "$MISSING_OUT/manifest.json" "$MISSING_OUT/receipts" \
    "missing-package manifest hashes only installed APK binary receipts"
  assert_tool_hash_binding "$MISSING_OUT/manifest.json" "$MISSING_OUT/summary.json" \
    "$FAKE_ADB" "$COLLECTOR" "$ADB_ALLOWLIST" \
    "missing-package manifest and summary bind the exact adb/collector SHA-256"
  assert_redacted_summary "$MISSING_OUT/summary.json" "$MISSING_OUT/manifest.json" \
    "missing-package run emits an exact-whitelist coordinate-free summary"
  assert_six_file_receipts "$MISSING_OUT/manifest.json" "$MISSING_OUT/receipts" \
    "missing-package run preserves strict six-file receipts"
  assert_public_collection "$MISSING_OUT/manifest.json" "$MISSING_OUT/receipts" "$ADB_LOG" \
    "$MISSING_FIXTURE_PACKAGE" "missing package is recorded as NOT_INSTALLED" \
    "${KNOWN_PACKAGES[@]}"
else
  report fail "missing-package manifest exists" "missing manifest.json"
fi
assert_no_privileged_fallback "$ADB_LOG" "missing-package run has no privileged fallback"
run_verify "$MISSING_OUT"
if [ "$RC" -eq 0 ]; then
  report ok "canonical missing-package receipts remain offline-verifiable"
else
  report fail "canonical missing-package receipts remain offline-verifiable" \
    "rc=$RC output=$OUT"
fi
expect_no_adb_call "canonical missing-package offline verification performs no adb call"
missing_stem="package-${MISSING_FIXTURE_PACKAGE//./-}-path"
if [ -f "$MISSING_OUT/receipts/$missing_stem.exit.txt" ] \
    && [ "$(tr -d '\r\n' <"$MISSING_OUT/receipts/$missing_stem.exit.txt")" = 1 ] \
    && [ ! -s "$MISSING_OUT/receipts/$missing_stem.stdout.txt" ] \
    && [ ! -s "$MISSING_OUT/receipts/$missing_stem.stderr.bin" ]; then
  report ok "NOT_INSTALLED preserves AOSP pm-path rc=1 with empty stdout/stderr"
else
  report fail "NOT_INSTALLED preserves AOSP pm-path rc=1 with empty stdout/stderr" \
    "missing or altered pm-path receipt"
fi
if grep -Eq -- "shell (dumpsys package|pidof|appops get).*${MISSING_FIXTURE_PACKAGE}|exec-out cat /data/app/.*/${MISSING_FIXTURE_PACKAGE}-" "$ADB_LOG"; then
  report fail "NOT_INSTALLED skips package-specific follow-up reads" \
    "unexpected follow-up: $(grep -E -- "$MISSING_FIXTURE_PACKAGE" "$ADB_LOG" | tr '\n' ';')"
else
  report ok "NOT_INSTALLED skips package-specific follow-up reads"
fi
if grep -Eq -- "exec-out cat /data/app/.*/$MISSING_FIXTURE_PACKAGE-" "$ADB_LOG"; then
  report fail "NOT_INSTALLED package has no APK byte read" "unexpected exec-out cat"
else
  report ok "NOT_INSTALLED package has no APK byte read"
fi

broken="$WORK/verify-missing-package-stderr"
cp -R "$MISSING_OUT" "$broken"
printf 'Unknown package: %s\n' "$MISSING_FIXTURE_PACKAGE" \
  >"$broken/receipts/$missing_stem.stderr.bin"
rebind_receipt_tree "$broken"
verify_mutation_stop \
  "host verifier rejects noncanonical stderr on an Android 15 missing package" "$broken"

MISSING_STDERR_OUT="$WORK/out-missing-known-package-stderr"
run_collect missing-package-stderr "$AUTHORIZED_SERIAL" "$MISSING_STDERR_OUT"
expect_stop "noncanonical missing-package stderr is refused" \
  STOP_PACKAGE_OBSERVATION_MALFORMED
expect_exit_code "noncanonical missing-package stderr uses evidence rc=21" 21
expect_only_authorized_target "noncanonical missing-package stderr stays scoped"
assert_no_privileged_fallback "$ADB_LOG" \
  "noncanonical missing-package stderr has no privileged fallback"
if [ -f "$MISSING_STDERR_OUT/manifest.json" ]; then
  assert_stop_manifest "$MISSING_STDERR_OUT/manifest.json" \
    STOP_PACKAGE_OBSERVATION_MALFORMED \
    "noncanonical missing-package stderr manifest preserves the exact reason"
else
  report fail "noncanonical missing-package stderr manifest exists" \
    "missing manifest.json"
fi

# 1. The authorized target is absent. Inventory is allowed, but no targeted
# command may run and absence can never be reinterpreted as a partial PASS.
DEVICES_FAIL_OUT="$WORK/out-devices-exit7"
run_collect devices-exit7 "$AUTHORIZED_SERIAL" "$DEVICES_FAIL_OUT"
expect_stop "devices -l rc=7 is an adb read failure" STOP_ADB_READ_FAILED
expect_exit_code "devices -l rc=7 uses evidence rc=21" 21
assert_no_adb_after "$ADB_LOG" "devices -l" \
  "failed device inventory stops before any targeted adb read"
if [ -f "$DEVICES_FAIL_OUT/manifest.json" ]; then
  assert_stop_manifest "$DEVICES_FAIL_OUT/manifest.json" STOP_ADB_READ_FAILED \
    "failed device inventory manifest preserves the exact reason"
  assert_six_file_receipts "$DEVICES_FAIL_OUT/manifest.json" "$DEVICES_FAIL_OUT/receipts" \
    "failed device inventory preserves its six-file receipt"
else
  report fail "failed device inventory manifest exists" "missing manifest.json"
fi
if [ "$(tr -d '\r\n' <"$DEVICES_FAIL_OUT/receipts/devices.exit.txt" 2>/dev/null)" = 7 ] \
    && grep -Eq -- 'fixture devices inventory transport failure' \
      "$DEVICES_FAIL_OUT/receipts/devices.stderr.bin"; then
  report ok "failed device inventory preserves rc=7 and stderr"
else
  report fail "failed device inventory preserves rc=7 and stderr" "receipt truth changed"
fi

DEVICES_HIDDEN_CR_OUT="$WORK/out-devices-hidden-cr"
run_collect devices-hidden-cr "$AUTHORIZED_SERIAL" "$DEVICES_HIDDEN_CR_OUT"
expect_stop "device inventory cannot hide an extra row behind bare CR" \
  STOP_INCOMPLETE_CORE_RECEIPT
expect_exit_code "bare-CR device inventory uses evidence rc=21" 21
assert_no_adb_after "$ADB_LOG" "devices -l" \
  "bare-CR device inventory stops before targeted adb reads"

run_collect missing-target "$AUTHORIZED_SERIAL" "$WORK/out-missing"
expect_stop "missing target is refused" "STOP_MISSING_TARGET"
expect_exit_code "missing target uses topology/identity rc=20" 20
expect_only_authorized_target "missing-target adb surface stays scoped"
if grep -q -- "^-s " "$ADB_LOG"; then
  report fail "missing target receives no targeted adb call" "log=$(tr '\n' ';' <"$ADB_LOG")"
else
  report ok "missing target receives no targeted adb call"
fi

# Inventory ambiguity includes devices that are not currently usable. Their
# offline/unauthorized state is not permission to silently ignore them.
for extra_scenario in extra-offline extra-unauthorized extra-emulator; do
  run_collect "$extra_scenario" "$AUTHORIZED_SERIAL" "$WORK/out-$extra_scenario"
  expect_stop "$extra_scenario is refused as an additional device" "STOP_EXTRA_DEVICE"
  expect_exit_code "$extra_scenario uses topology/identity rc=20" 20
  expect_only_authorized_target "$extra_scenario adb surface stays scoped"
  if grep -q -- "^-s " "$ADB_LOG"; then
    report fail "$extra_scenario receives no targeted adb call" "log=$(tr '\n' ';' <"$ADB_LOG")"
  else
    report ok "$extra_scenario receives no targeted adb call"
  fi
done

# 2. Even when the Moto is present, any additional attached device is an
# ambiguity boundary. Stop before a targeted command.
run_collect extra-device "$AUTHORIZED_SERIAL" "$WORK/out-extra"
expect_stop "additional attached device is refused" "STOP_EXTRA_DEVICE"
expect_exit_code "additional attached device uses topology/identity rc=20" 20
expect_only_authorized_target "extra-device adb surface stays scoped"
if grep -q -- "^-s " "$ADB_LOG"; then
  report fail "extra-device case receives no targeted adb call" "log=$(tr '\n' ';' <"$ADB_LOG")"
else
  report ok "extra-device case receives no targeted adb call"
fi

# 3. A caller cannot substitute a different serial. This fails before even
# querying device inventory, so authorization is not inferred from presence.
run_collect target "NOT_AUTHORIZED" "$WORK/out-wrong-serial"
expect_stop "wrong requested serial is refused" "STOP_WRONG_SERIAL"
expect_exit_code "wrong serial uses topology/identity rc=20" 20
expect_no_adb_call "wrong serial is rejected before adb"

# The live serial receipt has a three-way truth table: exact match continues,
# nonempty mismatch is identity failure, and only an empty scalar is incomplete.
LIVE_SERIAL_WRONG_OUT="$WORK/out-wrong-live-serial"
run_collect wrong-live-serial "$AUTHORIZED_SERIAL" "$LIVE_SERIAL_WRONG_OUT"
expect_stop "nonempty mismatched live serial is refused" STOP_WRONG_SERIAL
expect_exit_code "nonempty mismatched live serial uses identity rc=20" 20
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getprop ro.serialno" \
  "nonempty mismatched live serial stops at its receipt"
if [ -f "$LIVE_SERIAL_WRONG_OUT/manifest.json" ]; then
  assert_stop_manifest "$LIVE_SERIAL_WRONG_OUT/manifest.json" STOP_WRONG_SERIAL \
    "nonempty mismatched live serial manifest preserves the exact reason"
else
  report fail "nonempty mismatched live serial manifest exists" "missing manifest.json"
fi

LIVE_SERIAL_EMPTY_OUT="$WORK/out-empty-live-serial"
run_collect empty-live-serial "$AUTHORIZED_SERIAL" "$LIVE_SERIAL_EMPTY_OUT"
expect_stop "empty live serial remains incomplete evidence" STOP_INCOMPLETE_CORE_RECEIPT
expect_exit_code "empty live serial uses evidence rc=21" 21
assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getprop ro.serialno" \
  "empty live serial stops at its receipt"
if [ -f "$LIVE_SERIAL_EMPTY_OUT/manifest.json" ]; then
  assert_stop_manifest "$LIVE_SERIAL_EMPTY_OUT/manifest.json" STOP_INCOMPLETE_CORE_RECEIPT \
    "empty live serial manifest preserves the exact reason"
else
  report fail "empty live serial manifest exists" "missing manifest.json"
fi

# Scalar receipts are exactly one logical line. Embedded newlines must never be
# deleted into a different, apparently valid identity or API value.
for scalar_case in \
    "serial-multiline|ro.serialno|multiline live serial" \
    "serial-nul|ro.serialno|NUL-bearing live serial|STOP_INCOMPLETE_RECEIPT" \
    "serial-extra-lf|ro.serialno|extra-blank-line live serial" \
    "serial-edge-space|ro.serialno|edge-spaced live serial" \
    "api-multiline|ro.build.version.sdk|multiline API" \
    "fingerprint-control|ro.build.fingerprint|control-only fingerprint" \
    "fingerprint-c1|ro.build.fingerprint|C1 line-breaking fingerprint" \
    "fingerprint-line-separator|ro.build.fingerprint|Unicode line-separator fingerprint"; do
  IFS='|' read -r scalar_scenario scalar_anchor scalar_label scalar_marker <<EOF
$scalar_case
EOF
  scalar_marker=${scalar_marker:-STOP_INCOMPLETE_CORE_RECEIPT}
  scalar_out="$WORK/out-$scalar_scenario"
  run_collect "$scalar_scenario" "$AUTHORIZED_SERIAL" "$scalar_out"
  expect_stop "$scalar_label is refused as malformed evidence" "$scalar_marker"
  expect_exit_code "$scalar_label uses evidence rc=21" 21
  assert_no_adb_after "$ADB_LOG" "-s $AUTHORIZED_SERIAL shell getprop $scalar_anchor" \
    "$scalar_label stops at its own receipt"
done

# 4. Live manufacturer must be Motorola. The exact serial alone is not enough.
run_collect wrong-manufacturer "$AUTHORIZED_SERIAL" "$WORK/out-manufacturer"
expect_stop "wrong manufacturer is refused" "STOP_WRONG_MANUFACTURER"
expect_exit_code "wrong manufacturer uses topology/identity rc=20" 20
expect_only_authorized_target "manufacturer check uses only authorized target"

# 5. Issue #66's planned oracle is Android 15 (API 35); another API stops.
run_collect wrong-api "$AUTHORIZED_SERIAL" "$WORK/out-api"
expect_stop "wrong API is refused" "STOP_WRONG_API"
expect_exit_code "wrong API uses topology/identity rc=20" 20
expect_only_authorized_target "API check uses only authorized target"

# 6. Evidence must be a newly created mode-0700 directory outside the repo.
# This path is deliberately inside the fixture tree and starts nonexistent.
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_OUT"
expect_stop "repository-contained output is refused" "STOP_UNSAFE_OUTPUT"
expect_exit_code "unsafe output uses local-safety rc=22" 22
expect_no_adb_call "unsafe output is rejected before adb"
if [ -e "$UNSAFE_OUT" ]; then
  report fail "unsafe output path is not created" "collector created $UNSAFE_OUT"
else
  report ok "unsafe output path is not created"
fi

if [ "$(uname -s)" = Darwin ] \
    && [ -d "/System/Volumes/Data$REPO_ROOT" ]; then
  run_collect target "$AUTHORIZED_SERIAL" "/System/Volumes/Data$UNSAFE_OUT"
  expect_stop "repository-contained output through the Data firmlink is refused" \
    STOP_UNSAFE_OUTPUT
  expect_exit_code "Data-firmlink output refusal uses local-safety rc=22" 22
  expect_no_adb_call "Data-firmlink output is rejected before adb"
  if [ -e "$UNSAFE_OUT" ]; then
    report fail "Data-firmlink alias cannot create inside the repository" \
      "collector created $UNSAFE_OUT"
  else
    report ok "Data-firmlink alias cannot create inside the repository"
  fi
fi

GIT_ENV_DECOY="$WORK/git-env-decoy"
git init -q "$GIT_ENV_DECOY"
export GIT_DIR="$GIT_ENV_DECOY/.git"
export GIT_WORK_TREE="$GIT_ENV_DECOY"
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_OUT"
unset GIT_DIR GIT_WORK_TREE
expect_stop "repository-contained output stays refused under poisoned GIT_DIR/GIT_WORK_TREE" \
  STOP_UNSAFE_OUTPUT
expect_exit_code "poisoned Git environment output refusal uses local-safety rc=22" 22
expect_no_adb_call "poisoned Git environment is rejected before adb"
if [ -e "$UNSAFE_OUT" ]; then
  report fail "poisoned Git environment cannot create repository-contained output" \
    "collector created $UNSAFE_OUT"
else
  report ok "poisoned Git environment cannot create repository-contained output"
fi

UNSAFE_ALIAS_PARENT="$WORK/repository-parent-alias"
ln -s "${UNSAFE_OUT%/*}" "$UNSAFE_ALIAS_PARENT"
UNSAFE_ALIAS_OUT="$UNSAFE_ALIAS_PARENT/${UNSAFE_OUT##*/}"
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_ALIAS_OUT"
expect_stop "repository-contained output through an ancestor symlink is refused" STOP_UNSAFE_OUTPUT
expect_exit_code "ancestor-symlink output refusal uses local-safety rc=22" 22
expect_no_adb_call "ancestor-symlink output is rejected before adb"
if [ -e "$UNSAFE_OUT" ]; then
  report fail "ancestor-symlink output cannot create inside the repository" \
    "collector created $UNSAFE_OUT"
else
  report ok "ancestor-symlink output cannot create inside the repository"
fi

GIT_COMMON_DIR="$(git -C "$REPO_ROOT" rev-parse --path-format=absolute --git-common-dir)"
UNSAFE_COMMON_OUT="$GIT_COMMON_DIR/.issue66-unsafe-output-$$"
run_collect target "$AUTHORIZED_SERIAL" "$UNSAFE_COMMON_OUT"
expect_stop "git common-directory output is refused" STOP_UNSAFE_OUTPUT
expect_exit_code "git common-directory output uses local-safety rc=22" 22
expect_no_adb_call "git common-directory output is rejected before adb"
if [ -e "$UNSAFE_COMMON_OUT" ]; then
  report fail "git common-directory output path is not created" \
    "collector created $UNSAFE_COMMON_OUT"
else
  report ok "git common-directory output path is not created"
fi

if [ "$(uname -s)" = Darwin ]; then
  ACL_PARENT="$WORK/acl-inheritance-parent"
  ACL_OUTPUT="$ACL_PARENT/evidence"
  mkdir -m 700 "$ACL_PARENT"
  if chmod +a \
      'everyone allow list,search,add_file,add_subdirectory,file_inherit,directory_inherit' \
      "$ACL_PARENT"; then
    run_collect target "$AUTHORIZED_SERIAL" "$ACL_OUTPUT"
    expect_stop "output directory with an inherited extended ACL is refused" \
      STOP_UNSAFE_OUTPUT
    expect_exit_code "inherited ACL refusal uses local-safety rc=22" 22
    expect_no_adb_call "inherited ACL output is rejected before adb"
    if [ -e "$ACL_OUTPUT" ]; then
      report fail "unsafe ACL output directory is removed after refusal" \
        "unexpected output=$ACL_OUTPUT"
    else
      report ok "unsafe ACL output directory is removed after refusal"
    fi
    chmod -N "$ACL_PARENT"
  else
    report fail "selftest can install an inheritable ACL fixture" \
      "chmod +a failed"
  fi
fi

# Manifest persistence is part of the fail-closed boundary. The initial STOP
# write must fail before adb, and a failed final atomic replacement must never
# be followed by a COLLECTED message or success exit.
INITIAL_MANIFEST_FAIL_OUT="$WORK/out-manifest-initial-write-failure"
run_collect manifest-initial-write-failure "$AUTHORIZED_SERIAL" "$INITIAL_MANIFEST_FAIL_OUT"
expect_stop "initial STOP manifest write failure is fatal" STOP_INTERNAL_MANIFEST_WRITE
expect_exit_code "initial STOP manifest write failure uses internal rc=70" 70
expect_no_adb_call "initial STOP manifest write failure occurs before adb"

FINAL_MANIFEST_FAIL_OUT="$WORK/out-manifest-final-replace-failure"
run_collect manifest-final-replace-failure "$AUTHORIZED_SERIAL" "$FINAL_MANIFEST_FAIL_OUT"
expect_stop "final manifest replacement failure is fatal" STOP_INTERNAL_MANIFEST_WRITE
expect_exit_code "final manifest replacement failure uses internal rc=70" 70
if [[ $OUT == *"COLLECTED evidence="* ]]; then
  report fail "final manifest replacement failure cannot emit COLLECTED" "output=$OUT"
else
  report ok "final manifest replacement failure cannot emit COLLECTED"
fi
expect_only_authorized_target "final manifest replacement failure stays on the authorized target"

SUMMARY_FAIL_OUT="$WORK/out-summary-final-replace-failure"
run_collect summary-final-replace-failure "$AUTHORIZED_SERIAL" "$SUMMARY_FAIL_OUT"
expect_stop "final summary replacement failure is fatal" STOP_INTERNAL_SUMMARY_WRITE
expect_exit_code "final summary replacement failure uses internal rc=70" 70
if [ "$(cat "$SUMMARY_ORDER_LOG" 2>/dev/null)" = STOP ]; then
  report ok "COLLECTED manifest is not published before the final summary"
else
  report fail "COLLECTED manifest is not published before the final summary" \
    "manifest observed during summary failure: $(cat "$SUMMARY_ORDER_LOG" 2>/dev/null)"
fi

# 7. The exact allowlist must deny a mutation without executing adb. `settings
# put` is representative of the separately-authorized global-setting surface.
run_classify -s "$AUTHORIZED_SERIAL" shell settings put secure location_mode 3
expect_stop "mutating adb argv is refused" "STOP_MUTATING_COMMAND"
expect_exit_code "mutating adb argv uses local-safety rc=22" 22
expect_no_adb_call "mutating classification never executes adb"

# Full deny matrix. The first group is explicitly mutating; the final two are
# exact-allowlist drift and therefore UNLISTED. Every classifier query is pure
# host policy evaluation and must leave both fake and poison adb logs empty.
assert_classify_stop "install" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" install sample.apk
assert_classify_stop "uninstall" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" uninstall example.pkg
assert_classify_stop "push" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" push local.bin /data/local/tmp/remote.bin
assert_classify_stop "adb root" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" root
assert_classify_stop "reboot" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" reboot
assert_classify_stop "remount" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" remount
assert_classify_stop "pm clear" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell pm clear example.pkg
assert_classify_stop "am force-stop" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell am force-stop example.pkg
assert_classify_stop "am crash" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell am crash example.pkg
assert_classify_stop "settings put" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell settings put secure location_mode 3
assert_classify_stop "settings delete" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell settings delete secure mock_location
assert_classify_stop "appops set" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell appops set example.pkg android:mock_location allow
assert_classify_stop "appops reset" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell appops reset example.pkg
assert_classify_stop "global location toggle" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell cmd location set-location-enabled false
assert_classify_stop "test-provider mutation" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell cmd location providers add-test-provider gps
assert_classify_stop "sqlite UPDATE" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell sqlite3 /data/adb/lspd/config/modules_config.db \
  "UPDATE scope SET enabled=1"
assert_classify_stop "shell sh -c escape" STOP_MUTATING_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell sh -c id
assert_classify_stop "token appended to allowed getprop" STOP_UNLISTED_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell getprop ro.serialno unexpected-token
assert_classify_stop "authorized command with substituted serial" STOP_UNLISTED_COMMAND \
  -s OTHER_SERIAL shell getprop ro.serialno

# User and output columns are part of the frozen ps surface; mock-location
# AppOps is both user-0-bound and narrowed to one exact operation. The obsolete
# broad forms must be unlisted even though they look read-only.
assert_classify_allow "column-bounded process list" \
  -s "$AUTHORIZED_SERIAL" shell ps -A -o USER,PID,NAME
assert_classify_stop "broad process list" STOP_UNLISTED_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell ps -A
assert_classify_allow "user-0 exact mock-location AppOps read" \
  -s "$AUTHORIZED_SERIAL" shell appops get --user 0 \
  name.caiyao.fakegps android:mock_location
assert_classify_stop "broad package-wide AppOps read" STOP_UNLISTED_COMMAND \
  -s "$AUTHORIZED_SERIAL" shell appops get name.caiyao.fakegps

# A transport/process failure is distinct from a successful-but-empty core
# value. Preserve its six-file carrier, including the real exit and stderr,
# and stop as evidence failure rather than laundering it into INCOMPLETE.
ADB_FAIL_OUT="$WORK/out-fingerprint-exit7"
run_collect fingerprint-exit7 "$AUTHORIZED_SERIAL" "$ADB_FAIL_OUT"
expect_stop "fingerprint adb rc=7 is an explicit read failure" "STOP_ADB_READ_FAILED"
expect_exit_code "fingerprint adb rc=7 uses evidence rc=21" 21
expect_only_authorized_target "fingerprint adb failure stays scoped"
if [ -f "$ADB_FAIL_OUT/manifest.json" ]; then
  assert_stop_manifest "$ADB_FAIL_OUT/manifest.json" STOP_ADB_READ_FAILED \
    "fingerprint adb failure manifest preserves the exact reason"
  assert_six_file_receipts "$ADB_FAIL_OUT/manifest.json" "$ADB_FAIL_OUT/receipts" \
    "fingerprint adb failure preserves strict six-file receipts"
else
  report fail "fingerprint adb failure manifest exists" "missing manifest.json"
fi
if [ "$(tr -d '\r\n' <"$ADB_FAIL_OUT/receipts/fingerprint.exit.txt" 2>/dev/null)" = 7 ]; then
  report ok "fingerprint receipt preserves adb exit=7"
else
  report fail "fingerprint receipt preserves adb exit=7" "exit receipt missing or changed"
fi
if grep -F -q "fixture fingerprint transport failure" \
    "$ADB_FAIL_OUT/receipts/fingerprint.stderr.bin" 2>/dev/null; then
  report ok "fingerprint receipt preserves adb stderr"
else
  report fail "fingerprint receipt preserves adb stderr" "fixture stderr missing"
fi

# Boot identity and monotonic uptime must be syntactically and numerically
# meaningful, not merely nonempty. Invalid UUIDs, negative/non-finite uptime,
# and a decreasing end sample all make the evidence incoherent.
boot_timing_cases=(
  "boot-id-malformed|STOP_INCOMPLETE_CORE_RECEIPT|malformed boot_id"
  "uptime-negative|STOP_INCOMPLETE_CORE_RECEIPT|negative uptime"
  "uptime-nonfinite|STOP_INCOMPLETE_CORE_RECEIPT|non-finite uptime"
  "uptime-decreased|STOP_BOOT_CHANGED|decreasing uptime bracket"
)
for boot_timing_case in "${boot_timing_cases[@]}"; do
  IFS='|' read -r boot_timing_scenario boot_timing_marker boot_timing_label \
    <<<"$boot_timing_case"
  boot_timing_out="$WORK/out-$boot_timing_scenario"
  run_collect "$boot_timing_scenario" "$AUTHORIZED_SERIAL" "$boot_timing_out"
  expect_stop "$boot_timing_label is refused" "$boot_timing_marker"
  expect_exit_code "$boot_timing_label uses evidence rc=21" 21
  expect_only_authorized_target "$boot_timing_label stays scoped"
  if [ -f "$boot_timing_out/manifest.json" ]; then
    assert_stop_manifest "$boot_timing_out/manifest.json" "$boot_timing_marker" \
      "$boot_timing_label manifest preserves the exact reason"
    assert_six_file_receipts "$boot_timing_out/manifest.json" "$boot_timing_out/receipts" \
      "$boot_timing_label preserves strict six-file receipts"
  else
    report fail "$boot_timing_label manifest exists" "missing manifest.json"
  fi
done

# A changed but individually valid boot id is a distinct reboot boundary.
BOOT_CHANGED_OUT="$WORK/out-boot-changed"
run_collect boot-changed "$AUTHORIZED_SERIAL" "$BOOT_CHANGED_OUT"
expect_stop "boot change across collection is refused" "STOP_BOOT_CHANGED"
expect_exit_code "boot change uses evidence rc=21" 21
expect_only_authorized_target "boot-change reads stay scoped"
assert_boot_brackets "$ADB_LOG" "boot-change path captures ordered start/end brackets"
if [ -f "$BOOT_CHANGED_OUT/manifest.json" ]; then
  assert_stop_manifest "$BOOT_CHANGED_OUT/manifest.json" STOP_BOOT_CHANGED \
    "boot-change manifest preserves STOP_BOOT_CHANGED"
  assert_six_file_receipts "$BOOT_CHANGED_OUT/manifest.json" "$BOOT_CHANGED_OUT/receipts" \
    "boot-change path preserves strict six-file receipts"
else
  report fail "boot-change manifest exists" "missing manifest.json"
fi

# 8. A core identity command that exits 0 with an empty fingerprint is still
# incomplete evidence. The run must remain STOP and must not mint a success
# receipt from a merely successful process status.
run_collect incomplete-core "$AUTHORIZED_SERIAL" "$WORK/out-incomplete"
expect_stop "empty core identity receipt is refused" "STOP_INCOMPLETE_CORE_RECEIPT"
expect_exit_code "empty core identity uses evidence rc=21" 21
expect_only_authorized_target "incomplete-core adb surface stays scoped"
if [ -d "$WORK/out-incomplete" ]; then
  mode="$(file_mode "$WORK/out-incomplete")"
  if [ "$mode" = 700 ]; then
    report ok "partial evidence directory stays mode 0700"
  else
    report fail "partial evidence directory stays mode 0700" "mode=$mode"
  fi
  if "$PYTHON_BIN" -I - "$WORK/out-incomplete/manifest.json" <<'PY' >/dev/null 2>&1
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)
assert manifest["status"] == "STOP"
assert manifest["reason"] == "STOP_INCOMPLETE_CORE_RECEIPT"
PY
  then
    report ok "incomplete core emits machine-readable STOP manifest"
  else
    report fail "incomplete core emits machine-readable STOP manifest" "missing/invalid manifest.json"
  fi
else
  report fail "incomplete core preserves partial evidence" "output directory missing"
fi

printf 'issue66 Moto read-only collector selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]

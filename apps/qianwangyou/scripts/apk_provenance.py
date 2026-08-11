#!/usr/bin/env python3
"""Bind an APK digest to the exact source AND the Gradle runtime JDK that produced it.

A bare APK sha256 is not cross-environment artifact identity. Identical clean source
yields different APK bytes under different Gradle runtime JDKs: JDK 17 emits a synthetic
``UnavailableValueResolver$1`` for an enum switch that JDK 21 does not, and D8 propagates
that into ``classes3.dex`` / ``classes11.dex``. The divergence once read as "the author
installed a dirty worktree" and cost a review cycle -- see
``docs/bug-report/debug-apk-hash-jdk-drift/bug-report.md``.

The repo pins Java source/target 17 but pins NO Gradle runtime JDK (no
``org.gradle.java.home``, no ``java { toolchain { ... } }``), so the building JVM is
ambient per-run environment. Until a toolchain is pinned, the only defence is to make the
JDK travel WITH the hash -- on one line, every time.

That is what this module enforces STRUCTURALLY rather than by convention. Every field is
validated and the line is assembled atomically by :func:`format_line`; a missing or
unparseable JDK raises instead of degrading. There is no code path that emits an
unqualified hash, so "forgot to write down the JDK" is not expressible.

The JDK reported is the **daemon** JVM -- the one that actually runs javac and D8 -- read
from ``gradlew --version`` and then identified from that JDK's own ``release`` file,
rather than from an ambient ``java -version`` that may not be the JVM Gradle used.

One honesty note the line carries itself, in ``source_binding``.

``built`` is the release credential and means all four of: the task is one with a declared
output; that output did not exist when the build started and does exist now, so this build
produced these bytes; the tree was clean before and unchanged after; and the artifact path
came from the task, never from the caller. Bracketing alone was NOT enough -- an earlier
cut accepted any task with any file and happily signed the Gradle wrapper script as
``built``, because holding the tree still says nothing about the bytes being hashed.

``asserted`` is what remains when this process did not observe the build: an installed or
third-party APK. It is the correct claim for those, not a weaker grade of the same claim,
and it is NOT an exact-source binding -- do not quote it as one.

Exit codes follow the harness convention: 0 emitted, 2 harness error.
"""

import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

TOKEN = "APK_PROVENANCE"

_SHA256_RE = re.compile(r"\A[0-9a-f]{64}\Z")
_GIT_SHA_RE = re.compile(r"\A[0-9a-f]{40}\Z")
# vendor@version, e.g. JetBrains-s.r.o.@21.0.10 -- neither half may be empty.
_JDK_RE = re.compile(r"\A[^\s@]+@[0-9][^\s@]*\Z")
_DIRTY_SUFFIX = "+dirty"


class ProvenanceError(Exception):
    """Raised when a provenance field cannot be established. Never downgraded."""


# --------------------------------------------------------------------------- parsing


def parse_daemon_java_home(gradle_version_output):
    """Extract the Gradle DAEMON JVM home -- the JVM that actually compiles.

    ``gradlew --version`` reports both a launcher and a daemon JVM. They differ whenever
    ``org.gradle.java.home`` is set, and it is the daemon that runs javac/D8, so the
    daemon is the provenance-relevant one.
    """
    for line in gradle_version_output.splitlines():
        stripped = line.strip()
        if not stripped.startswith("Daemon JVM:"):
            continue
        value = stripped.split(":", 1)[1].strip()
        # Gradle appends a parenthetical rationale, e.g.
        #   /path/to/home (no Daemon JVM specified, using current Java home)
        home = value.split(" (", 1)[0].strip()
        if not home:
            break
        return home
    raise ProvenanceError(
        "could not read 'Daemon JVM:' from gradlew --version output; "
        "refusing to guess the build JDK"
    )


def parse_release_file(text):
    """Identify a JDK from its own ``release`` file: (implementor, version)."""
    fields = {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        key, _, raw = line.partition("=")
        fields[key.strip()] = raw.strip().strip('"')
    version = fields.get("JAVA_VERSION", "")
    implementor = fields.get("IMPLEMENTOR", "")
    if not version:
        raise ProvenanceError("JDK release file has no JAVA_VERSION")
    if not implementor:
        raise ProvenanceError("JDK release file has no IMPLEMENTOR")
    return implementor, version


def jdk_token(implementor, version):
    """Collapse vendor+version into one unsplittable, greppable token."""
    vendor = re.sub(r"\s+", "-", implementor.strip())
    token = "{}@{}".format(vendor, version.strip())
    if not _JDK_RE.match(token):
        raise ProvenanceError("unusable JDK identity: {!r}".format(token))
    return token


def source_token(head_sha, dirty):
    """Exact source identity. Dirtiness is a SUFFIX, not a neighbouring field.

    A separate ``tree=dirty`` column can be quoted away from the sha; a suffix cannot.
    """
    sha = head_sha.strip()
    if not _GIT_SHA_RE.match(sha):
        raise ProvenanceError("not a full 40-hex git sha: {!r}".format(head_sha))
    return sha + _DIRTY_SUFFIX if dirty else sha


BINDING_BUILT = "built"
BINDING_ASSERTED = "asserted"

# The ONLY tasks that may sign anything, each mapped to the single artifact it is allowed
# to vouch for. An open task/path pair is what let `--build help gradlew` sign the Gradle
# wrapper script as `built`: bracketing a build proves the tree held still, never that the
# hashed bytes came out of it. Deriving the path from the task -- and refusing any task
# without a declared output -- removes the whole class.
BUILD_TARGETS = {
    ":app:assembleDebug": "app/build/outputs/apk/debug/app-debug.apk",
    ":app:assembleRelease": "app/build/outputs/apk/release/app-release.apk",
}

# Untracked files that can still reach the APK. Everything else (docs, governance files,
# scratch notes) provably cannot, and treating those as dirt would make every real
# checkout permanently unsignable.
_BUILD_INPUT_PREFIXES = ("app/", "gradle/")
_BUILD_INPUT_EXACT = ("gradle.properties", "settings.gradle", "settings.gradle.kts")
_BUILD_INPUT_SUFFIXES = (".gradle", ".gradle.kts", ".pro")

# Gradle's own outputs and caches. These live under build-input paths but are produced BY
# the build, so counting them as dirt would make every post-build tree unsignable.
#
# Registered EXPLICITLY as exact roots. Substring or suffix matching on "build" is NOT
# safe: a source tree may legitimately contain a directory named `build` -- this repo
# really does ship app/src/main/assets/ -- and exempting any path with a /build/ segment
# turns the ignored-input probe straight back into a clean-source bypass. An ignored
# app/src/main/assets/build/x.apk still lands in the signed APK.
#
# New module output roots must be added here deliberately; the default is "not generated",
# which fails toward a false +dirty rather than a false clean.
_GENERATED_ROOTS = (
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    "app/build",
    "app/.cxx",
)


def is_build_input(path):
    """Can this repo-relative path change the APK's bytes?"""
    path = path.strip()
    if not path:
        return False
    return (
        path.startswith(_BUILD_INPUT_PREFIXES)
        or path in _BUILD_INPUT_EXACT
        or path.endswith(_BUILD_INPUT_SUFFIXES)
    )


def is_generated(path):
    """Is this AT or INSIDE a registered build-output/cache root?

    Exact-root containment only -- never a substring or suffix test. See _GENERATED_ROOTS.
    """
    path = path.strip().rstrip("/")
    return any(path == root or path.startswith(root + "/") for root in _GENERATED_ROOTS)


def format_line(apk_name, apk_sha256, source, jdk, gradle, source_binding):
    """Assemble the one evidence line -- or raise. The single sanctioned producer.

    Every caller path funnels through here, which is what makes an unqualified hash
    impossible to produce rather than merely discouraged.

    The JDK's filesystem home is deliberately NOT a field. It is not build identity --
    JBR 21.0.10 emits the same bytes from ``/opt`` as from ``/Applications`` -- and real
    homes contain spaces (``/Applications/Android Studio.app/...``), which would break the
    whitespace-delimited ``key=value`` grammar every harness parser here relies on.
    """
    if not apk_name or re.search(r"\s", apk_name):
        raise ProvenanceError("bad apk name: {!r}".format(apk_name))
    if not _SHA256_RE.match(apk_sha256 or ""):
        raise ProvenanceError("bad apk sha256: {!r}".format(apk_sha256))
    base = source[: -len(_DIRTY_SUFFIX)] if source.endswith(_DIRTY_SUFFIX) else source
    if not _GIT_SHA_RE.match(base or ""):
        raise ProvenanceError("bad source token: {!r}".format(source))
    if not _JDK_RE.match(jdk or ""):
        raise ProvenanceError("bad jdk token: {!r}".format(jdk))
    if not gradle or re.search(r"\s", gradle):
        raise ProvenanceError("bad gradle version: {!r}".format(gradle))
    if source_binding not in (BINDING_BUILT, BINDING_ASSERTED):
        raise ProvenanceError("bad source_binding: {!r}".format(source_binding))
    if source_binding == BINDING_BUILT and source.endswith(_DIRTY_SUFFIX):
        raise ProvenanceError(
            "'built' is the release credential and cannot be issued off a dirty tree"
        )
    return "{} apk={} apk_sha256={} source={} source_binding={} jdk={} gradle={}".format(
        TOKEN, apk_name, apk_sha256, source, source_binding, jdk, gradle
    )


def parse_gradle_version(gradle_version_output):
    match = re.search(r"^Gradle\s+(\S+)\s*$", gradle_version_output, re.MULTILINE)
    if not match:
        raise ProvenanceError("could not read Gradle version from --version output")
    return match.group(1)


def sha256_file(path, _chunk=1024 * 1024):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(_chunk), b""):
            digest.update(block)
    return digest.hexdigest()


# ------------------------------------------------------------------------ collection


def _run(args, cwd):
    try:
        completed = subprocess.run(
            args, cwd=str(cwd), stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
    except OSError as exc:
        raise ProvenanceError("could not run {}: {}".format(args[0], exc))
    if completed.returncode != 0:
        raise ProvenanceError(
            "{} exited {}: {}".format(
                args[0], completed.returncode, completed.stderr.decode("utf-8", "replace").strip()
            )
        )
    return completed.stdout.decode("utf-8", "replace")


def _source_state(run, repo_root):
    head = run(["git", "rev-parse", "HEAD"], repo_root).strip()
    # --untracked-files=all is still not enough: it omits IGNORED files, and this repo
    # globally ignores *.apk, *.dex and *.class -- all of which are legitimate packaged
    # assets under app/src/main/assets/. A file planted there is invisible to plain
    # `git status`, ships inside the signed APK, and would leave the line claiming a clean
    # source=<HEAD> for bytes HEAD does not describe. So ask for ignored entries too.
    #
    # --ignored=matching (not =traditional) because it collapses wholly-ignored roots to
    # `.gradle/`, `app/build/`, `build/` while still surfacing an individually-ignored
    # file like app/src/main/assets/hidden.apk. traditional expands those roots into
    # thousands of lines instead.
    porcelain = run(
        ["git", "status", "--porcelain", "--untracked-files=all", "--ignored=matching"],
        repo_root,
    )
    dirty = False
    for line in porcelain.splitlines():
        if len(line) < 4:
            continue
        code, path = line[:2], line[3:]
        if " -> " in path:  # rename: the destination is what exists now
            path = path.split(" -> ", 1)[1]
        path = path.strip().strip('"')
        if code in ("??", "!!"):
            # Ignored build outputs are produced BY the build; only inputs count.
            if is_build_input(path) and not is_generated(path):
                dirty = True
        else:
            # Any tracked modification means HEAD no longer describes the tree.
            dirty = True
        if dirty:
            break
    return source_token(head, dirty)


def collect(repo_root, apk_path, run=_run, read_text=None, build_task=None):
    """Gather every field. Any failure raises; nothing partial is returned.

    ``build_task`` is what makes ``source=`` trustworthy. Without it this function can
    only read the tree as it is *now*, which is not necessarily the tree the APK was
    built from -- build at commit A, check out commit B, run this, and the line would
    attribute A's bytes to B. That path is still supported (installed or third-party APKs
    have no build to observe) but self-labels ``source_binding=asserted``.

    With ``build_task`` the build is bracketed by two reads of the source state and the
    run is rejected if the tree moved underneath it, so ``source_binding=built`` means the
    binding was observed rather than assumed.
    """
    repo_root = Path(repo_root)

    if build_task:
        if build_task not in BUILD_TARGETS:
            raise ProvenanceError(
                "{!r} has no declared output, so it can vouch for nothing; "
                "signable tasks: {}".format(build_task, ", ".join(sorted(BUILD_TARGETS)))
            )
        expected = repo_root / BUILD_TARGETS[build_task]
        if apk_path is not None:
            # Refused even when it happens to match. Accepting an equal path would make
            # the contract "checked but not used", and a reader cannot tell from the
            # emitted line which of the two sources of truth was honoured.
            raise ProvenanceError(
                "--build derives the artifact from the task ({}); passing a path as well "
                "is a second source of truth and is refused".format(expected)
            )

        before = _source_state(run, repo_root)
        if before.endswith(_DIRTY_SUFFIX):
            raise ProvenanceError(
                "refusing to build release evidence from a dirty tree ({}); commit or "
                "stash first".format(before)
            )

        # Clear the target so that its REAPPEARANCE is the proof. Without this, "the file
        # exists afterwards" is satisfied by a stale artifact from any earlier build of
        # any earlier source.
        try:
            expected.unlink()
        except FileNotFoundError:
            pass
        except OSError as exc:
            raise ProvenanceError("cannot clear {} before building: {}".format(expected, exc))

        run(["./gradlew", build_task, "--console=plain"], repo_root)

        after = _source_state(run, repo_root)
        if before != after:
            raise ProvenanceError(
                "source changed during the build ({} -> {}); the APK cannot be bound to "
                "either state".format(before, after)
            )
        if not expected.is_file():
            raise ProvenanceError(
                "{} did not produce {}; there is no artifact to sign".format(
                    build_task, expected
                )
            )
        apk, source, binding = expected, after, BINDING_BUILT
    else:
        if apk_path is None:
            raise ProvenanceError("an APK path is required without --build")
        apk = Path(apk_path)
        source, binding = _source_state(run, repo_root), BINDING_ASSERTED

    if not apk.is_file():
        raise ProvenanceError("no such APK: {}".format(apk))

    gradle_out = run(["./gradlew", "--version", "--console=plain", "--quiet"], repo_root)
    java_home = parse_daemon_java_home(gradle_out)
    gradle_version = parse_gradle_version(gradle_out)

    reader = read_text or (lambda p: Path(p).read_text(encoding="utf-8"))
    release_path = Path(java_home) / "release"
    try:
        release_text = reader(release_path)
    except OSError as exc:
        raise ProvenanceError("cannot identify build JDK at {}: {}".format(release_path, exc))

    implementor, version = parse_release_file(release_text)
    try:
        apk_sha256 = sha256_file(apk)
    except OSError as exc:
        # is_file() passing does not mean the bytes are readable: permissions, a race with
        # a concurrent build, or plain I/O error. Letting OSError escape would exit 1 with
        # a traceback, breaking the exit-2 harness contract this module promises.
        raise ProvenanceError("cannot read {}: {}".format(apk, exc))
    return format_line(
        apk_name=apk.name,
        apk_sha256=apk_sha256,
        source=source,
        jdk=jdk_token(implementor, version),
        gradle=gradle_version,
        source_binding=binding,
    )


def main(argv=None, emit=print):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "apk",
        nargs="?",
        help="path to an existing APK (omit with --build, which derives it from the task)",
    )
    parser.add_argument(
        "--repo-root",
        default=str(Path(__file__).resolve().parents[1]),
        help="repository root used for git and gradlew (default: this script's repo)",
    )
    parser.add_argument(
        "--build",
        metavar="TASK",
        help=(
            "build this task from a clean tree and sign the artifact it declares "
            "(e.g. --build :app:assembleRelease). The output is cleared first, so only "
            "its reappearance counts. Required for release evidence."
        ),
    )
    args = parser.parse_args(argv)
    try:
        emit(collect(args.repo_root, args.apk, build_task=args.build))
    except ProvenanceError as exc:
        print("HARNESS_ERROR {}".format(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

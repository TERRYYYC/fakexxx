#!/usr/bin/env python3
"""Contract tests for the APK provenance line.

The point of these is not coverage of a formatter. It is that the ONE failure this repo
already paid for -- an APK hash quoted without the JDK that produced it, read as a dirty
worktree -- must be unrepresentable, not merely discouraged. So every test below asks the
same question: can a caller still get a hash out of this module without its build JDK?
"""

import unittest
from pathlib import Path

from scripts import apk_provenance as prov

HEAD = "f825b01aee0a31febf1f85f7ba1806ae22ccf3e9"
APK_SHA = "a" * 64

# Real Gradle 9.3.1 output shape, JBR 21 with no daemon pin.
GRADLE_OUT = """
------------------------------------------------------------
Gradle 9.3.1
------------------------------------------------------------

Kotlin:        2.2.21
Launcher JVM:  21.0.10 (JetBrains s.r.o. 21.0.10+-117844308-b1163.108)
Daemon JVM:    /Applications/Android Studio.app/Contents/jbr/Contents/Home (no Daemon JVM specified, using current Java home)
OS:            Mac OS X 26.5.2 aarch64
"""

JBR_RELEASE = 'JAVA_VERSION="21.0.10"\nIMPLEMENTOR="JetBrains s.r.o."\n'


class BuildFixture:
    """A throwaway repo whose fake Gradle really writes the task's declared output.

    The previous fixture hashed a tempfile no build ever touched, which is exactly how a
    contract that signs unrelated files got pinned green. Here the artifact only exists
    if the fake build produced it, so 'built' has something real to be wrong about.
    """

    def __init__(self, produces=True, porcelain="", moves_tree=False):
        self.produces = produces
        self.porcelain = porcelain
        self.moves_tree = moves_tree
        self.tasks_run = []

    def __enter__(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self._heads = iter([HEAD, "b" * 40] if self.moves_tree else [HEAD, HEAD, HEAD])
        return self

    def __exit__(self, *exc):
        self._tmp.cleanup()
        return False

    def run(self, args, cwd):
        if args[0] == "git" and args[1] == "rev-parse":
            return next(self._heads) + "\n"
        if args[0] == "git" and args[1] == "status":
            return self.porcelain
        if args[0] == "./gradlew" and "--version" in args:
            return GRADLE_OUT
        if args[0] == "./gradlew":
            self.tasks_run.append(args[1])
            if self.produces:
                out = self.root / prov.BUILD_TARGETS[args[1]]
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(b"fresh apk bytes")
            return ""
        raise AssertionError("unexpected command {}".format(args))

    def plant_stale_artifact(self, task):
        out = self.root / prov.BUILD_TARGETS[task]
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(b"STALE bytes from an older build")
        return out

    def collect(self, **kwargs):
        kwargs.setdefault("read_text", lambda p: JBR_RELEASE)
        return prov.collect(self.root, kwargs.pop("apk_path", None), run=self.run, **kwargs)


class ParseDaemonJavaHomeTest(unittest.TestCase):
    def test_reads_daemon_home_and_drops_the_parenthetical_rationale(self):
        self.assertEqual(
            "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
            prov.parse_daemon_java_home(GRADLE_OUT),
        )

    def test_prefers_the_daemon_over_the_launcher_when_they_differ(self):
        # org.gradle.java.home was set: the launcher runs on 17 but javac/D8 run on 21.
        # Reporting the launcher would attribute the bytes to the wrong compiler.
        out = (
            "Launcher JVM:  17.0.20 (Homebrew 17.0.20+0)\n"
            "Daemon JVM:    /opt/jbr-21 (from org.gradle.java.home)\n"
        )
        self.assertEqual("/opt/jbr-21", prov.parse_daemon_java_home(out))

    def test_missing_daemon_line_raises_instead_of_falling_back_to_ambient_java(self):
        # An ambient `java -version` may not be the JVM Gradle used at all. Guessing here
        # is exactly how a hash acquires a JDK label that is not true.
        with self.assertRaises(prov.ProvenanceError):
            prov.parse_daemon_java_home("Gradle 9.3.1\nLauncher JVM:  21.0.10 (JetBrains)\n")


class ReleaseFileTest(unittest.TestCase):
    def test_identifies_vendor_and_version_from_the_jdks_own_release_file(self):
        self.assertEqual(("JetBrains s.r.o.", "21.0.10"), prov.parse_release_file(JBR_RELEASE))

    def test_missing_version_raises(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.parse_release_file('IMPLEMENTOR="JetBrains s.r.o."\n')

    def test_missing_implementor_raises(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.parse_release_file('JAVA_VERSION="21.0.10"\n')

    def test_vendor_whitespace_is_collapsed_so_the_token_cannot_split_a_line(self):
        self.assertEqual("JetBrains-s.r.o.@21.0.10", prov.jdk_token("JetBrains s.r.o.", "21.0.10"))

    def test_empty_vendor_or_version_is_rejected(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.jdk_token("", "21.0.10")
        with self.assertRaises(prov.ProvenanceError):
            prov.jdk_token("JetBrains", "")


class SourceTokenTest(unittest.TestCase):
    def test_clean_tree_is_the_bare_sha(self):
        self.assertEqual(HEAD, prov.source_token(HEAD, dirty=False))

    def test_dirty_is_a_suffix_so_it_cannot_be_quoted_away_from_the_sha(self):
        # A neighbouring `tree=dirty` field can be dropped when someone copies the sha
        # into a doc; a suffix travels with it.
        self.assertEqual(HEAD + "+dirty", prov.source_token(HEAD, dirty=True))

    def test_abbreviated_sha_is_rejected(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.source_token("f825b01", dirty=False)


class FormatLineTest(unittest.TestCase):
    def line(self, **overrides):
        kwargs = dict(
            apk_name="app-release.apk",
            apk_sha256=APK_SHA,
            source=HEAD,
            jdk="JetBrains-s.r.o.@21.0.10",
            gradle="9.3.1",
            source_binding=prov.BINDING_BUILT,
        )
        kwargs.update(overrides)
        return prov.format_line(**kwargs)

    def test_one_line_carries_hash_source_and_jdk_together(self):
        line = self.line()
        self.assertEqual(1, len(line.splitlines()))
        self.assertIn("apk_sha256=" + APK_SHA, line)
        self.assertIn("source=" + HEAD, line)
        self.assertIn("jdk=JetBrains-s.r.o.@21.0.10", line)

    def test_a_hash_without_a_jdk_cannot_be_formatted(self):
        # THE regression this module exists for.
        for absent in ("", None):
            with self.assertRaises(prov.ProvenanceError):
                self.line(jdk=absent)

    def test_a_placeholder_jdk_is_not_accepted_as_a_jdk(self):
        for bogus in ("unknown", "TODO", "n/a", "@21.0.10", "JetBrains@"):
            with self.assertRaises(prov.ProvenanceError):
                self.line(jdk=bogus)

    def test_malformed_hash_is_rejected(self):
        for bad in ("", "abc123", APK_SHA.upper(), "z" * 64):
            with self.assertRaises(prov.ProvenanceError):
                self.line(apk_sha256=bad)

    def test_dirty_source_still_formats_but_stays_marked(self):
        self.assertIn(
            "source=" + HEAD + "+dirty",
            self.line(source=HEAD + "+dirty", source_binding=prov.BINDING_ASSERTED),
        )

    def test_the_strength_of_the_source_claim_is_itself_a_required_field(self):
        # A line may not stay silent about whether its source binding was observed or
        # merely assumed -- silence would read as "observed".
        for bogus in ("", None, "probably", "true"):
            with self.assertRaises(prov.ProvenanceError):
                self.line(source_binding=bogus)


class CollectTest(unittest.TestCase):
    def fake_run(self, gradle_out=GRADLE_OUT):
        def run(args, cwd):
            if args[0] == "git" and args[1] == "rev-parse":
                return HEAD + "\n"
            if args[0] == "git" and args[1] == "status":
                return ""
            if args[0] == "./gradlew":
                return gradle_out
            raise AssertionError("unexpected command {}".format(args))

        return run

    def test_the_baseline_jbr_home_contains_a_space_and_must_not_break_collection(self):
        # Regression: the release-build baseline lives at
        # /Applications/Android Studio.app/Contents/jbr/Contents/Home. An earlier cut put
        # that path in the line and rejected it for containing whitespace, so the tool
        # failed closed on the single most common real setup. The home is not build
        # identity and is no longer a field.
        import tempfile

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            line = prov.collect(
                ".", apk.name, run=self.fake_run(), read_text=lambda p: JBR_RELEASE
            )
        self.assertNotIn(" /", line.split("apk=", 1)[1])
        self.assertIn("jdk=JetBrains-s.r.o.@21.0.10", line)

    def test_emits_a_complete_line_for_a_real_apk(self):
        import tempfile

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            line = prov.collect(
                ".", apk.name, run=self.fake_run(), read_text=lambda p: JBR_RELEASE
            )
        self.assertTrue(line.startswith("APK_PROVENANCE "))
        self.assertIn("jdk=JetBrains-s.r.o.@21.0.10", line)
        self.assertIn("gradle=9.3.1", line)
        self.assertIn("source=" + HEAD, line)

    def test_unreadable_jdk_yields_no_line_at_all_rather_than_a_bare_hash(self):
        import tempfile

        def unreadable(path):
            raise OSError("no such file")

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            with self.assertRaises(prov.ProvenanceError):
                prov.collect(".", apk.name, run=self.fake_run(), read_text=unreadable)

    def test_missing_apk_raises(self):
        with self.assertRaises(prov.ProvenanceError):
            prov.collect(".", "/nonexistent/app.apk", run=self.fake_run())

    def test_an_unreadable_apk_becomes_a_provenance_error_not_a_raw_oserror(self):
        # is_file() passing does not mean the bytes are readable: permissions, a race with
        # a concurrent build, plain I/O error. A raw OSError escapes main()'s handler and
        # exits 1 with a traceback, breaking the exit-2 contract this module advertises.
        #
        # NOTE this must reach sha256_file to mean anything. An earlier cut of this test
        # ran through main() in an env with no JAVA_HOME, died at `gradlew --version`, and
        # passed for entirely the wrong reason.
        import tempfile

        original = prov.sha256_file
        reached = []

        def boom(path, **kwargs):
            reached.append(path)
            raise OSError("read denied")

        prov.sha256_file = boom
        self.addCleanup(setattr, prov, "sha256_file", original)

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            with self.assertRaises(prov.ProvenanceError):
                prov.collect(
                    ".", apk.name, run=self.fake_run(), read_text=lambda p: JBR_RELEASE
                )
        self.assertEqual(1, len(reached), "the test never reached the hashing step")

    def test_without_a_build_the_source_claim_self_labels_as_assumed(self):
        # Reading the tree now does not prove the APK came from it: build at A, check out
        # B, run this, and A's bytes would be labelled B. The line must say so.
        import tempfile

        with tempfile.NamedTemporaryFile(suffix=".apk") as apk:
            apk.write(b"payload")
            apk.flush()
            line = prov.collect(
                ".", apk.name, run=self.fake_run(), read_text=lambda p: JBR_RELEASE
            )
        self.assertIn("source_binding=asserted", line)

    def test_building_here_upgrades_the_claim_to_observed(self):
        with BuildFixture() as fx:
            line = fx.collect(build_task=":app:assembleRelease")
        self.assertIn("source_binding=built", line)
        self.assertIn("apk=app-release.apk", line)


class BuiltBindingTest(unittest.TestCase):
    """`built` must mean THIS build produced THIS file from an exact clean source.

    Regression suite for the review finding that `--build help gradlew` exited 0 and
    signed the Gradle wrapper script as `source_binding=built`. Bracketing the build with
    two source reads only proves the tree held still; it proves nothing about the bytes
    being hashed.
    """

    def test_an_unrecognised_task_cannot_sign_anything(self):
        # `help` builds no artifact, so there is no artifact it could vouch for.
        with BuildFixture() as fx:
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task="help")

    def test_the_hashed_file_is_derived_from_the_task_not_supplied_by_the_caller(self):
        # Accepting a caller-supplied path is what let an unrelated file ride along.
        with BuildFixture() as fx:
            (fx.root / "gradlew").write_bytes(b"#!/bin/sh\n")
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task=":app:assembleRelease", apk_path=str(fx.root / "gradlew"))

    def test_even_a_matching_caller_path_is_refused(self):
        # Accepting it "because it agrees" leaves the contract as "checked but not used",
        # and a reader of the emitted line cannot tell which source of truth was honoured.
        with BuildFixture() as fx:
            matching = fx.root / prov.BUILD_TARGETS[":app:assembleRelease"]
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task=":app:assembleRelease", apk_path=str(matching))

    def test_a_task_that_produces_nothing_yields_no_line(self):
        with BuildFixture(produces=False) as fx:
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task=":app:assembleRelease")

    def test_a_stale_artifact_left_by_an_earlier_build_cannot_be_signed_as_built(self):
        # The killing case: the output already exists, so "it exists afterwards" is not
        # evidence. It must be cleared first, and only its REAPPEARANCE counts.
        with BuildFixture(produces=False) as fx:
            stale = fx.plant_stale_artifact(":app:assembleRelease")
            stale_sha = __import__("hashlib").sha256(stale.read_bytes()).hexdigest()
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task=":app:assembleRelease")
            self.assertNotIn(stale_sha, "")

    def test_a_fresh_build_overwriting_a_stale_artifact_hashes_the_fresh_bytes(self):
        with BuildFixture(produces=True) as fx:
            stale = fx.plant_stale_artifact(":app:assembleRelease")
            stale_sha = __import__("hashlib").sha256(stale.read_bytes()).hexdigest()
            line = fx.collect(build_task=":app:assembleRelease")
        self.assertNotIn(stale_sha, line)
        self.assertIn("source_binding=built", line)

    def test_release_evidence_refuses_a_dirty_tree(self):
        # `built` is the release credential; it may not be issued off a modified tree.
        with BuildFixture(porcelain=" M app/src/main/java/X.java\n") as fx:
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task=":app:assembleRelease")

    def test_an_untracked_build_input_is_dirt_even_though_git_calls_it_untracked(self):
        # --untracked-files=no hid these: an untracked app/src file still compiles into
        # the APK, so a clean `source=<HEAD>` would be a false claim.
        with BuildFixture(porcelain="?? app/src/main/java/Sneaky.java\n") as fx:
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task=":app:assembleRelease")

    def test_untracked_non_build_input_does_not_taint_the_source(self):
        # Governance files and scratch notes provably cannot enter the APK; treating them
        # as dirt would make every real checkout permanently unsignable.
        with BuildFixture(porcelain="?? BACKLOG.md\n?? .claude/settings.json\n") as fx:
            line = fx.collect(build_task=":app:assembleRelease")
        self.assertIn("source=" + HEAD + " ", line)
        self.assertIn("source_binding=built", line)

    def test_a_tree_that_moves_mid_build_still_yields_no_line(self):
        with BuildFixture(moves_tree=True) as fx:
            with self.assertRaises(prov.ProvenanceError):
                fx.collect(build_task=":app:assembleRelease")

    def test_built_and_dirty_can_never_appear_on_the_same_line(self):
        # Structural backstop independent of the collection path.
        with self.assertRaises(prov.ProvenanceError):
            prov.format_line(
                apk_name="app-release.apk",
                apk_sha256=APK_SHA,
                source=HEAD + "+dirty",
                jdk="JetBrains-s.r.o.@21.0.10",
                gradle="9.3.1",
                source_binding=prov.BINDING_BUILT,
            )


class RealGitIgnoreSemanticsTest(unittest.TestCase):
    """Drives real `git status` against a real repo, not a hand-written porcelain string.

    A mocked '??' line cannot catch this class of bug, because the bug IS that git never
    reports the file. This repo globally ignores *.apk / *.dex / *.class, and those are
    legitimate packaged assets under app/src/main/assets/ -- so a planted asset is
    invisible to plain `git status`, ships inside the signed APK, and previously left the
    line claiming a clean source.
    """

    def make_repo(self):
        import subprocess
        import tempfile

        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        root = Path(self._tmp.name)

        def git(*args):
            subprocess.run(
                ["git"] + list(args),
                cwd=str(root),
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )

        git("init", "-q")
        git("config", "user.email", "test@example.invalid")
        git("config", "user.name", "test")
        # The real ignore rules that made the bypass possible.
        (root / ".gitignore").write_text("*.apk\n*.dex\n*.class\n.gradle/\nbuild/\n")
        (root / "app").mkdir()
        (root / "app" / "build.gradle").write_text("// app\n")
        git("add", "-A")
        git("commit", "-qm", "base")
        return root

    def test_an_ignored_asset_under_app_src_still_dirties_the_source(self):
        root = self.make_repo()
        assets = root / "app" / "src" / "main" / "assets"
        assets.mkdir(parents=True)
        (assets / "hidden.apk").write_text("payload that ships inside the signed APK")

        import subprocess

        visible = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=str(root),
            stdout=subprocess.PIPE,
        ).stdout.decode()
        self.assertEqual("", visible.strip(), "precondition: git must not see the file")

        state = prov._source_state(prov._run, root)
        self.assertTrue(
            state.endswith("+dirty"),
            "an ignored but packaged asset must taint the source, got {}".format(state),
        )

    def test_generated_build_outputs_do_not_dirty_the_source(self):
        # The other half: after any build, app/build/ is full of ignored files. Counting
        # those would make every post-build tree permanently unsignable.
        root = self.make_repo()
        out = root / "app" / "build" / "outputs" / "apk" / "release"
        out.mkdir(parents=True)
        (out / "app-release.apk").write_text("a legitimate build output")
        (root / ".gradle").mkdir()
        (root / ".gradle" / "cache.bin").write_text("cache")

        state = prov._source_state(prov._run, root)
        self.assertFalse(
            state.endswith("+dirty"),
            "build outputs are produced BY the build and must not count, got {}".format(state),
        )

    def test_a_source_directory_merely_NAMED_build_is_not_a_generated_root(self):
        # Second-order regression. Once the ignored probe was sound, an over-broad
        # generated-output classifier re-opened the same bypass: is_generated() matched
        # any path containing a /build/ segment, so an ignored
        # app/src/main/assets/build/hidden.apk was exempted and the source read back clean
        # -- while the file still shipped inside the signed APK.
        #
        # app/src/main/assets/ is a real packaged directory in this repo (it carries
        # xposed_init), so this is a live path, not a contrived one.
        root = self.make_repo()
        nested = root / "app" / "src" / "main" / "assets" / "build"
        nested.mkdir(parents=True)
        (nested / "hidden.apk").write_text("payload under a directory merely named build")

        import subprocess

        visible = subprocess.run(
            ["git", "status", "--porcelain"], cwd=str(root), stdout=subprocess.PIPE
        ).stdout.decode()
        self.assertEqual("", visible.strip(), "precondition: git must not see the file")

        state = prov._source_state(prov._run, root)
        self.assertTrue(
            state.endswith("+dirty"),
            "a source dir named 'build' is not a generated root; got {}".format(state),
        )

    def test_generated_roots_are_matched_exactly_not_by_substring(self):
        self.assertTrue(prov.is_generated("app/build"))
        self.assertTrue(prov.is_generated("app/build/"))
        self.assertTrue(prov.is_generated("app/build/outputs/apk/release/app-release.apk"))
        self.assertTrue(prov.is_generated("build/reports/x.html"))
        self.assertTrue(prov.is_generated(".gradle/file-system.probe"))
        # ...and the paths that must NOT be exempted:
        self.assertFalse(prov.is_generated("app/src/main/assets/build/hidden.apk"))
        self.assertFalse(prov.is_generated("app/src/main/java/build/Foo.java"))
        self.assertFalse(prov.is_generated("app/buildsrc/Thing.kt"))
        self.assertFalse(prov.is_generated("app/src/build"))

    def test_an_ignored_class_file_outside_build_still_counts(self):
        root = self.make_repo()
        assets = root / "app" / "src" / "main" / "assets"
        assets.mkdir(parents=True)
        (assets / "Payload.class").write_text("cafebabe")
        self.assertTrue(prov._source_state(prov._run, root).endswith("+dirty"))


class MainTest(unittest.TestCase):
    def test_build_and_a_path_together_are_rejected_at_the_cli(self):
        emitted = []
        code = prov.main(
            ["--build", ":app:assembleRelease", "app/build/outputs/apk/release/app-release.apk"],
            emit=emitted.append,
        )
        self.assertEqual(2, code)
        self.assertEqual([], emitted)

    def test_harness_error_exits_2_and_prints_nothing_to_stdout(self):
        # Fail closed: a non-zero exit with an empty stdout is unusable as evidence,
        # which is the intent. A partial line would have looked usable.
        emitted = []
        code = prov.main(["/nonexistent/app.apk"], emit=emitted.append)
        self.assertEqual(2, code)
        self.assertEqual([], emitted)


if __name__ == "__main__":
    unittest.main()

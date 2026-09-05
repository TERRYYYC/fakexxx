#!/usr/bin/python3 -I
"""Regression tests for the private Issue 66 JDK runtime stager."""

import importlib.util
import os
import pathlib
import stat
import tempfile
import unittest
from unittest import mock


REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
STAGER = REPO_ROOT / "scripts" / "stage-java17-runtime.py"
VALIDATOR = REPO_ROOT / "scripts" / "validate-java17-runtime.py"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


STAGER_MODULE = load_module("issue66_java17_stager", STAGER)
VALIDATOR_MODULE = load_module("issue66_java17_validator_for_stager", VALIDATOR)


class _ObservableScandir:
    def __init__(self, names):
        self._names = iter(names)
        self.next_requests = 0

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        return False

    def __iter__(self):
        return self

    def __next__(self):
        self.next_requests += 1
        name = next(self._names)
        return type("ObservedDirEntry", (), {"name": name})()


class Java17RuntimeStagerTest(unittest.TestCase):
    def test_source_walk_stops_after_the_remaining_entry_budget_plus_one(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-stage-stream-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            source = parent / "source"
            source.mkdir(mode=0o755)
            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            stage_root.mkdir(mode=0o700)
            source_identity = (source.stat().st_dev, source.stat().st_ino)
            observed = _ObservableScandir(f"entry-{index}" for index in range(100))
            real_listdir = os.listdir
            original_limit = STAGER_MODULE.ENTRY_LIMIT

            def reject_eager_source_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == source_identity:
                    raise AssertionError("source tree used eager os.listdir")
                return real_listdir(directory_fd)

            def observable_source_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == source_identity:
                    return observed
                return _ObservableScandir(())

            try:
                STAGER_MODULE.ENTRY_LIMIT = 4
                with mock.patch.object(
                    STAGER_MODULE.os,
                    "listdir",
                    side_effect=reject_eager_source_enumeration,
                ), mock.patch.object(
                    STAGER_MODULE.os,
                    "scandir",
                    side_effect=observable_source_enumeration,
                ):
                    with self.assertRaises(STAGER_MODULE.StageError):
                        STAGER_MODULE.stage_runtime_tree(source, stage_root)
            finally:
                STAGER_MODULE.ENTRY_LIMIT = original_limit

            self.assertEqual(
                4,
                observed.next_requests,
                "source enumeration requested ceiling+2 instead of stopping at remaining+1",
            )
            self.assertEqual([], list(stage_root.iterdir()))

    def test_cleanup_walk_stops_after_the_remaining_entry_budget_plus_one(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-cleanup-stream-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            home = stage_root / "home"
            stage_root.mkdir(mode=0o700)
            home.mkdir(mode=0o700)
            home_identity = (home.stat().st_dev, home.stat().st_ino)
            observed = _ObservableScandir(f"entry-{index}" for index in range(100))
            real_listdir = os.listdir
            original_limit = STAGER_MODULE.ENTRY_LIMIT

            def reject_eager_cleanup_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == home_identity:
                    raise AssertionError("staged cleanup used eager os.listdir")
                return real_listdir(directory_fd)

            def observable_cleanup_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == home_identity:
                    return observed
                return _ObservableScandir(())

            try:
                STAGER_MODULE.ENTRY_LIMIT = 2
                with mock.patch.object(
                    STAGER_MODULE.os,
                    "listdir",
                    side_effect=reject_eager_cleanup_enumeration,
                ), mock.patch.object(
                    STAGER_MODULE.os,
                    "scandir",
                    side_effect=observable_cleanup_enumeration,
                ):
                    with self.assertRaises(STAGER_MODULE.StageError):
                        STAGER_MODULE.remove_staged_home(stage_root)
            finally:
                STAGER_MODULE.ENTRY_LIMIT = original_limit

            self.assertEqual(
                5,
                observed.next_requests,
                "cleanup requested ceiling+2 instead of stopping at remaining+1",
            )

    def test_private_stage_preserves_the_profile_tree_digest_and_is_removable(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-stage-test-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            source = parent / "source"
            self._write_runtime_fixture(source)

            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            stage_root.mkdir(mode=0o700)
            staged_home = STAGER_MODULE.stage_runtime_tree(source, stage_root)

            self.assertEqual(stat.S_IMODE(stage_root.lstat().st_mode), 0o700)
            self.assertEqual(staged_home.parent, stage_root)
            self.assertNotEqual(source, staged_home)
            self.assertEqual(
                VALIDATOR_MODULE.compute_jdk_tree_digest(source),
                VALIDATOR_MODULE.compute_jdk_tree_digest(staged_home),
            )
            self.assertEqual(b"runtime-bytes", staged_home.joinpath("lib/runtime.bin").read_bytes())
            self.assertEqual("../lib/runtime.bin", os.readlink(staged_home / "legal/runtime-link"))

            STAGER_MODULE.remove_staged_home(stage_root)
            self.assertTrue(stage_root.exists())
            self.assertEqual([], list(stage_root.iterdir()))

    def test_stage_rejects_special_files_and_removes_a_partial_destination(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-stage-fifo-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            source = parent / "source"
            self._write_runtime_fixture(source)
            os.mkfifo(source / "runtime-pipe", mode=0o600)

            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            stage_root.mkdir(mode=0o700)
            with self.assertRaises(STAGER_MODULE.StageError):
                STAGER_MODULE.stage_runtime_tree(source, stage_root)

            self.assertEqual(
                ["jdk-runtime.0123456789abcdef0123456789abcdef", "source"],
                sorted(path.name for path in parent.iterdir()),
                "failed staging retained a partial private runtime",
            )

    def test_failed_stage_restores_readonly_partial_directories_before_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-stage-readonly-cleanup-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            source = parent / "source"
            readonly_source = source / "a-readonly"
            readonly_source.mkdir(parents=True, mode=0o755)
            readonly_source.joinpath("payload.bin").write_bytes(b"payload")
            readonly_source.chmod(0o555)
            os.mkfifo(source / "z-runtime-pipe", mode=0o600)
            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            stage_root.mkdir(mode=0o700)
            try:
                with self.assertRaises(STAGER_MODULE.StageError):
                    STAGER_MODULE.stage_runtime_tree(source, stage_root)

                self.assertEqual(
                    [],
                    list(stage_root.iterdir()),
                    "a readonly partial subtree survived failed staging cleanup",
                )
            finally:
                readonly_source.chmod(0o700)
                staged_readonly = stage_root / "home" / "a-readonly"
                if staged_readonly.exists():
                    staged_readonly.chmod(0o700)

    def test_failed_stage_propagates_a_cleanup_error_instead_of_hiding_residuals(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-stage-cleanup-error-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            source = parent / "source"
            source.mkdir(mode=0o755)
            os.mkfifo(source / "runtime-pipe", mode=0o600)
            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            stage_root.mkdir(mode=0o700)
            cleanup_error = STAGER_MODULE.StageError()

            with mock.patch.object(
                STAGER_MODULE,
                "remove_staged_home",
                side_effect=cleanup_error,
            ):
                with self.assertRaises(STAGER_MODULE.StageError) as caught:
                    STAGER_MODULE.stage_runtime_tree(source, stage_root)

            self.assertIs(
                cleanup_error,
                caught.exception,
                "staging hid the cleanup error and its possible residual tree",
            )

    def test_remove_staged_home_restores_nested_readonly_directories(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-nested-readonly-cleanup-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            stage_root.mkdir(mode=0o700)
            payload_parent = stage_root / "home" / "outer" / "inner"
            payload_parent.mkdir(parents=True, mode=0o700)
            payload_parent.joinpath("payload.bin").write_bytes(b"payload")
            payload_parent.chmod(0o555)
            payload_parent.parent.chmod(0o555)
            stage_root.joinpath("home").chmod(0o555)
            try:
                STAGER_MODULE.remove_staged_home(stage_root)
                self.assertEqual([], list(stage_root.iterdir()))
            finally:
                for path in (
                    payload_parent,
                    payload_parent.parent,
                    stage_root / "home",
                ):
                    if path.exists():
                        path.chmod(0o700)

    def test_stager_never_executes_the_source_runtime(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-stage-remove-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            source = parent / "source"
            self._write_runtime_fixture(source)
            marker = parent / "source-java-ran"
            source.joinpath("bin/java").write_text(
                "#!/bin/sh\n/usr/bin/touch '%s'\nexit 91\n" % marker,
                encoding="utf-8",
            )
            source.joinpath("bin/java").chmod(0o755)
            stage_root = parent / "jdk-runtime.0123456789abcdef0123456789abcdef"
            stage_root.mkdir(mode=0o700)
            with self.assertRaises(STAGER_MODULE.StageError):
                # The low-level copy succeeds, but the real CLI profile admission would reject
                # this tree before executing its unreviewed java bytes.
                staged_home = STAGER_MODULE.stage_runtime_tree(source, stage_root)
                STAGER_MODULE.validator_binding(staged_home)

            self.assertFalse(marker.exists())

    def test_stage_rejects_an_outer_directory_outside_the_runner_namespace(self):
        with tempfile.TemporaryDirectory(prefix="issue66-jdk-stage-name-") as directory:
            parent = pathlib.Path(directory).resolve()
            parent.chmod(0o700)
            source = parent / "source"
            self._write_runtime_fixture(source)
            unbound_outer = parent / "unbound-runtime"
            unbound_outer.mkdir(mode=0o700)

            with self.assertRaises(STAGER_MODULE.StageError):
                STAGER_MODULE.stage_runtime_tree(source, unbound_outer)

            self.assertEqual([], list(unbound_outer.iterdir()))

    def _write_runtime_fixture(self, root):
        root.mkdir(mode=0o755)
        root.joinpath("bin").mkdir(mode=0o755)
        root.joinpath("lib").mkdir(mode=0o755)
        root.joinpath("legal").mkdir(mode=0o755)
        root.joinpath("bin/java").write_bytes(b"java-bytes")
        root.joinpath("bin/java").chmod(0o755)
        root.joinpath("lib/runtime.bin").write_bytes(b"runtime-bytes")
        root.joinpath("lib/runtime.bin").chmod(0o644)
        root.joinpath("legal/runtime-link").symlink_to("../lib/runtime.bin")


if __name__ == "__main__":
    unittest.main()

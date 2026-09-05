#!/usr/bin/python3 -I
"""Regression tests for the Issue 66 Android SDK trust boundary."""

import importlib.util
import json
import os
import pathlib
import shutil
import stat
import subprocess
import tempfile
import traceback
import unittest
from unittest import mock


REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
VALIDATOR = REPO_ROOT / "scripts" / "validate-android-sdk-runtime.py"
RUNNER = REPO_ROOT / "integration-tests" / "pr63-on-issue66" / "run-host-gate.sh"
AGGREGATE = REPO_ROOT / "scripts" / "verify-a-plus.sh"
CLEAN_ENVIRONMENT = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"}
VALIDATOR_SPEC = importlib.util.spec_from_file_location("issue66_android_sdk_validator", VALIDATOR)
VALIDATOR_MODULE = importlib.util.module_from_spec(VALIDATOR_SPEC)
VALIDATOR_SPEC.loader.exec_module(VALIDATOR_MODULE)


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


class AndroidSdkRuntimeValidationTest(unittest.TestCase):
    def test_fixture_baseline_reports_unsafe_ancestor_before_negative_mutation(self):
        with self._sdk_fixture() as fixture:
            fixture_path = fixture
            unsafe_parent = fixture / "unsafe-parent"
            unsafe_parent.mkdir(mode=0o755)
            unsafe_parent.chmod(0o775)
            sdk = unsafe_parent / "sdk"
            secret = "issue66-diagnostic-must-not-print-environment"
            with mock.patch.dict(os.environ, {"ISSUE66_DIAGNOSTIC_SECRET": secret}):
                with self.assertRaises(AssertionError) as raised:
                    self._write_sdk(sdk)
            message = str(raised.exception)
            diagnostic = json.loads(message)
            self.assertEqual("SDK_FIXTURE_BASELINE_REJECTED", diagnostic["error"])
            self.assertEqual("SdkValidationError", diagnostic["productionException"])
            self.assertIn("require_safe_state", diagnostic["productionTrace"])
            ancestor = next(
                entry for entry in diagnostic["ancestorAuthority"]
                if entry["path"] == str(unsafe_parent)
            )
            self.assertEqual(os.geteuid(), ancestor["uid"])
            self.assertEqual("0775", ancestor["mode"])
            self.assertIn("acl", ancestor)
            self.assertNotIn(secret, message)
            self.assertNotIn("platform-package", message)
            self.assertNotIn("android-jar", message)
            self.assertLessEqual(len(diagnostic["ancestorAuthority"]), 32)
            self.assertLessEqual(len(diagnostic["productionTrace"]), 4096)
        self.assertFalse(fixture_path.exists(), "rejected private fixture was not cleaned")

    def test_selected_tcb_walk_stops_after_the_remaining_entry_budget_plus_one(self):
        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            selected_root = sdk / "platforms" / "android-35"
            selected_identity = (selected_root.stat().st_dev, selected_root.stat().st_ino)
            observed = _ObservableScandir(f"entry-{index}" for index in range(100))
            real_listdir = os.listdir
            original_entry_limit = VALIDATOR_MODULE.TREE_ENTRY_LIMIT
            root_fd = os.open(sdk, VALIDATOR_MODULE.directory_flags())

            def reject_eager_tcb_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == selected_identity:
                    raise AssertionError("Android TCB validator used eager os.listdir")
                return real_listdir(directory_fd)

            def observable_tcb_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == selected_identity:
                    return observed
                return _ObservableScandir(())

            try:
                VALIDATOR_MODULE.TREE_ENTRY_LIMIT = 6
                with mock.patch.object(
                    VALIDATOR_MODULE.os,
                    "listdir",
                    side_effect=reject_eager_tcb_enumeration,
                ), mock.patch.object(
                    VALIDATOR_MODULE.os,
                    "scandir",
                    side_effect=observable_tcb_enumeration,
                ):
                    with self.assertRaises(VALIDATOR_MODULE.SdkValidationError):
                        VALIDATOR_MODULE.scan_selected_agp_tcb(root_fd)
            finally:
                VALIDATOR_MODULE.TREE_ENTRY_LIMIT = original_entry_limit
                os.close(root_fd)

            self.assertEqual(
                4,
                observed.next_requests,
                "Android TCB enumeration requested ceiling+2 instead of stopping at remaining+1",
            )

    def test_rejects_an_unsafe_ancestor_that_the_old_validator_accepted(self):
        with self._sdk_fixture() as fixture:
            unsafe_parent = fixture / "unsafe-parent"
            unsafe_parent.mkdir(mode=0o755)
            sdk = unsafe_parent / "sdk"
            self._write_sdk(sdk)
            unsafe_parent.chmod(0o775)

            self._assert_old_validator_accepts(sdk)
            self._assert_rejected(sdk)

    def test_rejects_an_unsafe_platform_parent_that_the_old_validator_accepted(self):
        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            sdk.joinpath("platforms", "android-35").chmod(0o775)

            self._assert_old_validator_accepts(sdk)
            self._assert_rejected(sdk)

    def test_rejects_an_android_jar_symlink_escaping_the_sdk(self):
        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            outside = fixture / "outside.jar"
            outside.write_bytes(b"outside")
            outside.chmod(0o644)
            android_jar = sdk / "platforms" / "android-35" / "android.jar"
            android_jar.unlink()
            android_jar.symlink_to(outside)

            self._assert_old_validator_accepts(sdk)
            self._assert_rejected(sdk)

    def test_rejects_writable_package_and_tool_inputs_hidden_from_the_old_validator(self):
        mutations = {
            "writable platform package metadata": ("platforms/android-35/package.xml", 0o664),
            "writable build tool": ("build-tools/36.0.0/aapt2", 0o775),
            "writable platform-tools directory": ("platform-tools", 0o775),
        }
        for label, (relative_path, mode) in mutations.items():
            with self.subTest(label=label), self._sdk_fixture() as fixture:
                sdk = fixture / "sdk"
                self._write_sdk(sdk)
                sdk.joinpath(relative_path).chmod(mode)

                self._assert_old_validator_accepts(sdk)
                self._assert_rejected(sdk)

    def test_rejects_unrelated_symlinks_and_special_entries_hidden_from_the_old_validator(self):
        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            sdk.joinpath("platform-tools", "adb-alias").symlink_to("adb")

            self._assert_old_validator_accepts(sdk)
            self._assert_rejected(sdk)

        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            os.mkfifo(sdk / "build-tools" / "36.0.0" / "untrusted-pipe", mode=0o600)

            self._assert_old_validator_accepts(sdk)
            self._assert_rejected(sdk)

    def test_rejects_acl_write_authority_hidden_from_the_old_validator(self):
        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            target = sdk / "platforms" / "android-35" / "package.xml"
            if not self._grant_write_acl(target):
                self.skipTest("the host has no supported ACL mutation utility")

            self._assert_old_validator_accepts(sdk)
            self._assert_rejected(sdk)

    def test_emits_a_canonical_binding_and_rejects_namespace_changes(self):
        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            emitted = self._run_validator("--emit-binding", os.fspath(sdk))
            self.assertEqual(0, emitted.returncode, emitted.stderr)
            binding = json.loads(emitted.stdout)
            self.assertEqual(
                {
                    "androidSdkRoot",
                    "agpTcbEntryCount",
                    "agpTcbFileBytes",
                    "agpTcbStateSha256",
                    "buildToolsVersion",
                    "platformApi",
                    "schemaVersion",
                },
                set(binding),
            )
            self.assertEqual(str(sdk), binding["androidSdkRoot"])
            self.assertEqual("35", binding["platformApi"])
            self.assertEqual("36.0.0", binding["buildToolsVersion"])
            self.assertRegex(binding["agpTcbStateSha256"], r"^[0-9a-f]{64}$")

            verified = self._run_validator(
                "--verify-binding",
                os.fspath(sdk),
                self._encode_binding(binding),
            )
            self.assertEqual(0, verified.returncode, verified.stderr)
            self.assertEqual(binding, json.loads(verified.stdout))

            sdk.joinpath("platform-tools", "source.properties").write_bytes(b"changed-metadata")
            rejected = self._run_validator(
                "--verify-binding",
                os.fspath(sdk),
                self._encode_binding(binding),
            )
            self.assertNotEqual(0, rejected.returncode, rejected.stdout)
            self.assertEqual("", rejected.stdout)
            self.assertEqual("", rejected.stderr)

    def test_ignores_a_legal_symlink_in_an_unselected_ndk_package(self):
        with self._sdk_fixture() as fixture:
            sdk = fixture / "sdk"
            self._write_sdk(sdk)
            ndk = sdk / "ndk" / "27.0.12077973"
            ndk.joinpath("toolchains", "llvm", "prebuilt", "bin").mkdir(
                parents=True,
                mode=0o755,
            )
            clang = ndk / "toolchains" / "llvm" / "prebuilt" / "bin" / "clang"
            clang.write_bytes(b"clang")
            clang.chmod(0o755)
            ndk.joinpath("toolchains", "llvm", "prebuilt", "bin", "clang++").symlink_to(
                "clang"
            )

            result = self._run_validator(os.fspath(sdk))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(str(sdk) + "\n", result.stdout)

    def test_requires_the_complete_declared_agp_sdk_input_set(self):
        required = (
            "platforms/android-35/android.jar",
            "platforms/android-35/framework.aidl",
            "platforms/android-35/package.xml",
            "platforms/android-35/source.properties",
            "platforms/android-35/data/api-versions.xml",
            "build-tools/36.0.0/package.xml",
            "build-tools/36.0.0/source.properties",
            "build-tools/36.0.0/aapt2",
            "platform-tools/package.xml",
            "platform-tools/source.properties",
            "platform-tools/adb",
        )
        for relative_path in required:
            with self.subTest(relative_path=relative_path), self._sdk_fixture() as fixture:
                sdk = fixture / "sdk"
                self._write_sdk(sdk)
                sdk.joinpath(relative_path).unlink()
                self._assert_rejected(sdk)

    def test_runner_and_aggregate_delegate_to_one_validator_and_recheck_each_command(self):
        runner = RUNNER.read_text(encoding="utf-8")
        aggregate = AGGREGATE.read_text(encoding="utf-8")
        validator_assignment = (
            'readonly android_sdk_validator="$repo_root/scripts/'
            'validate-android-sdk-runtime.py"'
        )
        self.assertIn(validator_assignment, runner)
        self.assertIn(
            'readonly android_sdk_validator="$REPO_ROOT/scripts/'
            'validate-android-sdk-runtime.py"',
            aggregate,
        )
        self.assertNotIn('android_jar = (root / "platforms"', runner)
        self.assertNotIn('android_jar = (root / "platforms"', aggregate)

        runner_gradle = self._shell_function(runner, "run_clean_gradle_command")
        self._assert_bracketed(
            runner_gradle,
            "verify_android_sdk_binding",
            'run_clean_host_command "$@"',
        )
        aggregate_gate = self._shell_function(aggregate, "run_clean_gate_command")
        self._assert_bracketed(
            aggregate_gate,
            "verify_android_sdk_binding",
            '/bin/bash -p -c "$1"',
        )
        direct_gradle = self._shell_function(runner, "run_direct_gradle_command")
        self._assert_bracketed(
            direct_gradle,
            "verify_android_sdk_binding",
            '"$auto_wrapper" -p "$script_dir" "$@"',
        )
        self.assertIn("ADB=/usr/bin/false", direct_gradle)

    def _assert_bracketed(self, function_body, validator_call, command):
        first_validation = function_body.find(validator_call)
        command_position = function_body.find(command)
        second_validation = function_body.find(validator_call, first_validation + 1)
        self.assertGreaterEqual(first_validation, 0, function_body)
        self.assertGreater(command_position, first_validation, function_body)
        self.assertGreater(second_validation, command_position, function_body)

    def _assert_rejected(self, sdk):
        self.assertTrue(
            VALIDATOR.is_file(),
            "the old SDK validator accepted this unsafe fixture; the shared validator is missing",
        )
        result = self._run_validator(os.fspath(sdk))
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual("", result.stdout)
        self.assertEqual("", result.stderr)

    def _assert_old_validator_accepts(self, sdk):
        root = sdk.resolve(strict=True)
        root_state = root.stat()
        android_jar = (root / "platforms" / "android-35" / "android.jar").resolve(strict=True)
        jar_state = android_jar.stat()
        accepted = (
            stat.S_ISDIR(root_state.st_mode)
            and root_state.st_uid in {0, os.geteuid()}
            and not stat.S_IMODE(root_state.st_mode) & 0o022
            and stat.S_ISREG(jar_state.st_mode)
            and jar_state.st_uid in {0, os.geteuid()}
            and not stat.S_IMODE(jar_state.st_mode) & 0o022
        )
        self.assertTrue(accepted, "the regression fixture no longer bypasses the old validator")

    def _run_validator(self, *arguments):
        return subprocess.run(
            ["/usr/bin/python3", "-I", os.fspath(VALIDATOR), *arguments],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=CLEAN_ENVIRONMENT,
            check=False,
        )

    def _grant_write_acl(self, target):
        if sys_platform() == "darwin":
            command = ["/bin/chmod", "+a", "everyone allow write", os.fspath(target)]
        else:
            setfacl = next(
                (path for path in ("/usr/bin/setfacl", "/bin/setfacl") if os.access(path, os.X_OK)),
                None,
            )
            if setfacl is None:
                return False
            command = [setfacl, "-m", "u:65534:w", os.fspath(target)]
        result = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=CLEAN_ENVIRONMENT,
            check=False,
        )
        return result.returncode == 0

    def _sdk_fixture(self):
        return _RepositoryTemporaryDirectory(REPO_ROOT / "scripts")

    def _write_sdk(self, root):
        directories = (
            "platforms/android-35/data",
            "build-tools/36.0.0",
            "platform-tools",
        )
        for relative_path in directories:
            root.joinpath(relative_path).mkdir(parents=True, mode=0o755, exist_ok=True)
        root.chmod(0o755)
        files = {
            "platforms/android-35/android.jar": b"android-jar",
            "platforms/android-35/framework.aidl": b"framework-aidl",
            "platforms/android-35/package.xml": b"platform-package",
            "platforms/android-35/source.properties": b"platform-source",
            "platforms/android-35/data/api-versions.xml": b"api-versions",
            "build-tools/36.0.0/package.xml": b"build-package",
            "build-tools/36.0.0/source.properties": b"build-source",
            "build-tools/36.0.0/aapt2": b"aapt2",
            "platform-tools/package.xml": b"tools-package",
            "platform-tools/source.properties": b"tools-source",
            "platform-tools/adb": b"adb",
        }
        for relative_path, payload in files.items():
            path = root / relative_path
            path.write_bytes(payload)
            path.chmod(0o755 if path.name in {"aapt2", "adb"} else 0o644)
        # Check the unmodified fixture before a test poisons it or installs mocks.
        # Otherwise an unsafe host ancestor can make negative tests falsely green.
        self._assert_sdk_fixture_baseline(root)

    def _assert_sdk_fixture_baseline(self, root):
        failure_types = (
            VALIDATOR_MODULE.SdkValidationError, OSError, ValueError, OverflowError, UnicodeError,
        )
        try:
            VALIDATOR_MODULE.validate_sdk(os.fspath(root))
        except failure_types as error:
            # Report only bounded authority metadata. Never include environment,
            # SDK bytes, ACL payloads, subprocess output, or exception messages.
            production_trace = "".join(traceback.format_tb(error.__traceback__, limit=12))[:4096]
            ancestors = list(reversed((root, *root.parents)))
            authority = []
            for ancestor in ancestors[:32]:
                entry = {"path": str(ancestor)[:512], "acl": "not_inspected"}
                descriptor = None
                try:
                    state = ancestor.lstat()
                    entry.update(uid=state.st_uid, mode=format(stat.S_IMODE(state.st_mode), "04o"))
                    descriptor = os.open(ancestor, VALIDATOR_MODULE.directory_flags())
                    VALIDATOR_MODULE.ACL_INSPECTOR.snapshot(descriptor)
                    entry["acl"] = "accepted"
                except failure_types as inspection_error:
                    entry["acl"] = "rejected:" + type(inspection_error).__name__
                finally:
                    if descriptor is not None:
                        os.close(descriptor)
                authority.append(entry)
            self.fail(json.dumps({
                "error": "SDK_FIXTURE_BASELINE_REJECTED",
                "effectiveUid": os.geteuid(),
                "ancestorAuthority": authority,
                "ancestorsTruncated": len(ancestors) > 32,
                "productionException": type(error).__name__,
                "productionTrace": production_trace,
            }, ensure_ascii=True, sort_keys=True))

    def _encode_binding(self, binding):
        return json.dumps(binding, ensure_ascii=True, separators=(",", ":"), sort_keys=True)

    def _shell_function(self, source, name):
        marker = name + "() {\n"
        start = source.find(marker)
        self.assertGreaterEqual(start, 0, name)
        end = source.find("\n}\n", start + len(marker))
        self.assertGreater(end, start, name)
        return source[start : end + 3]


class _RepositoryTemporaryDirectory:
    def __init__(self, parent):
        self.parent = pathlib.Path(parent)
        self.path = None

    def __enter__(self):
        self.path = pathlib.Path(
            tempfile.mkdtemp(prefix=".issue66-sdk-validator-test.", dir=os.fspath(self.parent))
        ).resolve()
        self.path.chmod(0o700)
        return self.path

    def __exit__(self, _type, _value, _traceback):
        if self.path is not None:
            shutil.rmtree(self.path)


def sys_platform():
    import sys

    return sys.platform


if __name__ == "__main__":
    unittest.main()

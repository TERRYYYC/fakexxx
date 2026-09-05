#!/usr/bin/python3 -I
"""Regression tests for the Issue 66 Android SDK trust boundary."""

import importlib.util
import contextlib
import errno
import io
import json
import os
import pathlib
import shutil
import stat
import struct
import subprocess
import tempfile
import textwrap
import traceback
import types
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
    def test_runtime_security_imports_preserve_a_clean_repository_with_linux_bytecode_defaults(self):
        workflow = (REPO_ROOT / ".github/workflows/android-a-plus.yml").read_text()
        home_step = workflow.split("      - name: normalize reviewed home default ACL\n", 1)[1]
        home_step = home_step.split("\n      - name:", 1)[0]
        home_program = textwrap.dedent(home_step.split("        run: |\n", 1)[1])
        home_program = home_program.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        # Execute the original import path only, before any real /home access.
        home_import = home_program.split('root = pathlib.Path("/home")', 1)[0]
        self.assertNotEqual(home_import, home_program)
        home_command = next(line for line in home_step.splitlines() if "/usr/bin/python3 " in line)
        security_step = workflow.split("      - name: standalone runtime security tests\n", 1)[1]
        security_step = security_step.split("\n      - name:", 1)[0]
        runner = RUNNER.read_text()
        runner_step = runner.split("run_standalone_runtime_security_tests() {\n", 1)[1].split("\n}\n", 1)[0]
        test_paths = (
            ("scripts/test_validate_java17_runtime.py", "java_profile_validator_test"),
            ("scripts/test_stage_java17_runtime.py", "java_runtime_stager_test"),
            ("scripts/test_validate_android_sdk_runtime.py", "android_sdk_validator_test"),
        )
        entries = [("ci-home", home_command, "inline", home_import)]
        for relative, variable in test_paths:
            entries.append((
                "ci-" + variable,
                next(line for line in security_step.splitlines() if "/usr/bin/python3 " in line and relative in line),
                "script", relative,
            ))
            entries.append((
                "runner-" + variable,
                next(line for line in runner_step.splitlines() if "/usr/bin/python3 " in line and variable in line),
                "script", relative,
            ))
        sources = [relative for relative, _variable in test_paths] + [
            "scripts/validate-java17-runtime.py", "scripts/stage-java17-runtime.py",
            "scripts/validate-android-sdk-runtime.py",
        ]
        environment = dict(CLEAN_ENVIRONMENT, ADB="/usr/bin/false", GIT_CONFIG_NOSYSTEM="1",
                           GIT_CONFIG_GLOBAL="/dev/null", GIT_TERMINAL_PROMPT="0")
        for label, command, kind, payload in entries:
            flags = []
            for argument in command.split("/usr/bin/python3 ", 1)[1].split():
                if not argument.startswith("-") or argument == "-":
                    break
                flags.append(argument)
            self.assertIn("-I", flags)
            for mutant in (False, True):
                with self.subTest(entry=label, remove_explicit_B=mutant), self._sdk_fixture() as fixture:
                    for relative in sources:
                        target = fixture / relative
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(REPO_ROOT / relative, target)

                    def git(*arguments):
                        return subprocess.run(
                            ["/usr/bin/git", "-c", "core.hooksPath=/dev/null", *arguments],
                            cwd=fixture, env=environment, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=True, timeout=20,
                        ).stdout

                    git("init", "--quiet")
                    git("add", "--", "scripts")
                    git("-c", "user.name=Issue66 Fixture", "-c", "user.email=fixture@example.invalid",
                        "commit", "--quiet", "-m", "runtime import fixture")
                    head = git("rev-parse", "HEAD")
                    self.assertEqual(b"", git("status", "--porcelain"))
                    selected_flags = [flag for flag in flags if not (mutant and flag == "-B")]
                    bootstrap = (
                        "import runpy, sys\n"
                        # Apple system Python has implicit -B; model Linux's default explicitly.
                        "sys.dont_write_bytecode = sys.argv[1] == 'explicit-B'\n"
                        "if sys.argv[2] == 'inline':\n"
                        "    sys.platform = 'linux'\n"
                        "    exec(compile(sys.argv[3], '<ci-home-acl-import>', 'exec'))\n"
                        "else:\n"
                        "    runpy.run_path(sys.argv[3], run_name='issue66_import_fixture')\n"
                    )
                    result = subprocess.run(
                        ["/usr/bin/python3", *selected_flags, "-c", bootstrap,
                         "explicit-B" if "-B" in selected_flags else "linux-default", kind, payload],
                        cwd=fixture, env=environment, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, timeout=20,
                    )
                    self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", errors="replace"))
                    untracked = git("ls-files", "--others", "-z", "--exclude-per-directory=.gitignore")
                    caches = list((fixture / "scripts").rglob("*.pyc"))
                    if mutant:
                        self.assertTrue(caches, "removing -B no longer reproduces Linux cache creation")
                        self.assertIn(b"scripts/__pycache__/", untracked)
                    else:
                        self.assertEqual([], caches, "runtime-security imports wrote repository bytecode")
                        self.assertEqual(b"", untracked)
                    self.assertEqual(head, git("rev-parse", "HEAD"))
                    self.assertEqual(b"", git("diff", "HEAD", "--"))

    def test_ci_removes_only_the_reviewed_home_default_acl(self):
        workflow = (REPO_ROOT / ".github/workflows/android-a-plus.yml").read_text()
        marker = "      - name: normalize reviewed home default ACL\n"
        self.assertIn(marker, workflow, "CI does not establish safe /home default-ACL authority")
        start = workflow.index(marker)
        end = workflow.index("\n      - name:", start + len(marker))
        step = workflow[start:end]
        self.assertTrue(workflow[end:].startswith("\n      - name: standalone runtime security tests"))
        self.assertIn("shell: /bin/bash --noprofile --norc -p -euo pipefail {0}", step)
        self.assertIn('"/usr/bin/sudo", "--", "/usr/bin/setfacl", "--remove-default", "--", "/home"', step)
        self.assertNotIn("--recursive", step)
        self.assertNotIn("--remove-all", step)
        self.assertNotIn("chmod", step)
        self.assertNotIn("chown", step)
        program = textwrap.dedent(step.split("        run: |\n", 1)[1])
        program = program.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        access = struct.pack("<I", 2) + b"".join(
            struct.pack("<HHI", tag, permissions, qualifier)
            for tag, permissions, qualifier in (
                (1, 7, 0xFFFFFFFF), (2, 7, 65534), (4, 5, 0xFFFFFFFF),
                (16, 5, 0xFFFFFFFF), (32, 5, 0xFFFFFFFF),
            )
        )
        default = struct.pack("<I", 2) + b"".join(
            struct.pack("<HHI", tag, permissions, 0xFFFFFFFF)
            for tag, permissions in ((1, 7), (4, 7), (32, 5))
        )
        self.assertFalse(VALIDATOR_MODULE.posix_acl_grants_write(access))
        self.assertTrue(VALIDATOR_MODULE.posix_acl_grants_write(default))
        for scenario in (
            "remove_default", "already_safe", "unsafe_access", "readonly_default",
            "oversized_default", "wrong_owner", "wrong_mode", "symlink", "file",
            "changed_access", "retained_default", "changed_inode",
        ):
            with self.subTest(scenario=scenario), self._sdk_fixture() as fixture:
                home = fixture / "home"
                home.mkdir(mode=0o755)
                child = home / "untouched"
                child.write_bytes(b"existing-child")
                child_before = (child.read_bytes(), child.stat().st_mode)
                target_inode = home.stat().st_ino
                acl_state = {"system.posix_acl_access": access, "system.posix_acl_default": default}
                if scenario == "already_safe":
                    acl_state.pop("system.posix_acl_default")
                if scenario == "unsafe_access":
                    acl_state["system.posix_acl_access"] = default
                if scenario == "readonly_default":
                    acl_state["system.posix_acl_default"] = access
                if scenario == "oversized_default":
                    acl_state["system.posix_acl_default"] = b"x" * 65537
                if scenario == "wrong_mode":
                    home.chmod(0o775)
                if scenario in {"symlink", "file"}:
                    original = home.with_name("home.original")
                    home.rename(original)
                    if scenario == "symlink":
                        home.symlink_to(original, target_is_directory=True)
                    else:
                        home.write_bytes(b"not a directory")
                real_stat, real_fstat, real_lstat = os.stat, os.fstat, pathlib.Path.lstat
                calls = []

                def reviewed_state(value):
                    if value.st_ino != target_inode:
                        return value
                    fields = {name: getattr(value, name) for name in dir(value) if name.startswith("st_")}
                    fields["st_uid"] = 12345 if scenario == "wrong_owner" else 0
                    if calls and scenario == "changed_inode":
                        fields["st_ino"] += 1
                    return types.SimpleNamespace(**fields)

                def fixture_stat(*args, **kwargs):
                    return reviewed_state(real_stat(*args, **kwargs))

                def fixture_fstat(descriptor):
                    return reviewed_state(real_fstat(descriptor))

                def fixture_getxattr(descriptor, name):
                    self.assertEqual(target_inode, real_fstat(descriptor).st_ino)
                    if name not in acl_state:
                        raise OSError(errno.ENODATA, "fixture ACL absent")
                    return acl_state[name]

                def remove_default(arguments, **kwargs):
                    self.assertEqual(
                        ["/usr/bin/sudo", "--", "/usr/bin/setfacl", "--remove-default", "--", str(home)],
                        arguments,
                    )
                    self.assertTrue(kwargs["check"])
                    self.assertEqual(10, kwargs["timeout"])
                    calls.append(arguments)
                    if scenario != "retained_default":
                        acl_state.pop("system.posix_acl_default")
                    if scenario == "changed_access":
                        acl_state["system.posix_acl_access"] = default
                    return subprocess.CompletedProcess(arguments, 0)

                output = io.StringIO()
                rejected = None
                # All literal /home references are mapped to this private fixture.
                # POSIX ACL bytes and the original parser run even on macOS; no
                # real /home, privilege elevation, or ACL mutation command is used.
                fixture_program = program.replace('"/home"', repr(str(home)))
                with mock.patch("sys.platform", "linux"), mock.patch.object(os, "stat", fixture_stat), \
                        mock.patch.object(os, "fstat", fixture_fstat), \
                        mock.patch.object(pathlib.Path, "lstat", lambda path: reviewed_state(real_lstat(path))), \
                        mock.patch.object(os, "getxattr", fixture_getxattr, create=True), \
                        mock.patch.object(os, "listxattr", lambda _fd: list(acl_state), create=True), \
                        mock.patch.object(subprocess, "run", remove_default), \
                        contextlib.redirect_stdout(output):
                    try:
                        exec(compile(fixture_program, "<reviewed-home-acl-step>", "exec"), {})
                    except (Exception, SystemExit) as error:
                        if isinstance(error, AssertionError):
                            raise
                        rejected = error
                if scenario in {"remove_default", "already_safe"}:
                    self.assertIsNone(rejected, repr(rejected))
                    self.assertEqual(access, acl_state["system.posix_acl_access"])
                    self.assertNotIn("system.posix_acl_default", acl_state)
                    self.assertEqual(child_before, (child.read_bytes(), child.stat().st_mode))
                    self.assertEqual(1 if scenario == "remove_default" else 0, len(calls))
                    expected = "HOST_HOME_DEFAULT_ACL_REMOVED" if calls else "HOST_HOME_ACL_ALREADY_SAFE"
                    self.assertEqual(expected + "\n", output.getvalue())
                else:
                    self.assertIsNotNone(rejected, "unsafe or changed authority was accepted")
                    self.assertEqual(1 if scenario in {"changed_access", "retained_default", "changed_inode"} else 0, len(calls))

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

#!/usr/bin/python3 -I
"""Regression tests for the Issue 66 host-gate JDK trust boundary."""

import ast
import json
import importlib.util
import os
import pathlib
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
VALIDATOR = REPO_ROOT / "scripts" / "validate-java17-runtime.py"
VALIDATOR_SPEC = importlib.util.spec_from_file_location("issue66_java_validator", VALIDATOR)
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


class JavaRuntimeProfileTest(unittest.TestCase):
    def test_native_fake_java_cannot_self_attest_into_a_trusted_profile(self):
        compiler = next(
            (path for path in ("/usr/bin/cc", "/usr/bin/clang", "/usr/bin/gcc") if os.access(path, os.X_OK)),
            None,
        )
        if compiler is None:
            self.fail("a fixed system C compiler is required for the native-fake regression")

        with tempfile.TemporaryDirectory(prefix="issue66-native-fake-java-") as directory:
            root = pathlib.Path(directory)
            fake_home = root / "fake-jdk"
            fake_java = fake_home / "bin" / "java"
            source = root / "fake-java.c"
            if sys.platform == "darwin":
                vendor = "Eclipse Adoptium"
                runtime_version = "17.0.20.1+1"
                java_arch = "aarch64"
            else:
                vendor = "Eclipse Adoptium"
                runtime_version = "17.0.20.1+1"
                java_arch = "amd64"
            fake_java.parent.mkdir(parents=True)
            source.write_text(
                """
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    const char *home = HOME_LITERAL;
    const char *property_prefix = "-Dissue66.hostGateChallenge=";
    size_t property_prefix_length = strlen(property_prefix);

    for (int index = 1; index < argc; index++) {
        if (strncmp(argv[index], property_prefix, property_prefix_length) == 0) {
            const char *challenge = argv[index] + property_prefix_length;
            fprintf(stderr, "Property settings:\\n");
            fprintf(stderr, "    issue66.hostGateChallenge = %s\\n", challenge);
            fprintf(stderr, "    java.home = %s\\n", home);
            fprintf(stderr, "    java.runtime.version = RUNTIME_LITERAL\\n");
            fprintf(stderr, "    java.specification.version = 17\\n");
            fprintf(stderr, "    java.vendor = VENDOR_LITERAL\\n");
            fprintf(stderr, "    java.vm.vendor = VENDOR_LITERAL\\n");
            fprintf(stderr, "    os.arch = ARCH_LITERAL\\n");
            return 0;
        }
    }

    if (argc == 2) {
        FILE *probe = fopen(argv[1], "rb");
        char buffer[4096];
        if (probe == NULL) return 91;
        size_t count = fread(buffer, 1, sizeof(buffer) - 1, probe);
        if (fclose(probe) != 0) return 92;
        buffer[count] = '\\0';
        const char *prefix = "System.out.print(\\\"";
        char *start = strstr(buffer, prefix);
        if (start == NULL) return 93;
        start += strlen(prefix);
        char *end = strchr(start, '\"');
        if (end == NULL) return 94;
        if (fwrite(start, 1, (size_t)(end - start), stdout) != (size_t)(end - start)) return 95;
        return 0;
    }
    return 96;
}
"""
                .replace("HOME_LITERAL", json.dumps(str(fake_home)))
                .replace("RUNTIME_LITERAL", runtime_version)
                .replace("VENDOR_LITERAL", vendor)
                .replace("ARCH_LITERAL", java_arch),
                encoding="utf-8",
            )
            compile_result = subprocess.run(
                [compiler, "-O2", "-o", os.fspath(fake_java), os.fspath(source)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                env={"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"},
                check=False,
            )
            self.assertEqual(0, compile_result.returncode, compile_result.stdout)

            result = subprocess.run(
                ["/usr/bin/python3", "-I", os.fspath(VALIDATOR), os.fspath(fake_home)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                env={"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"},
                check=False,
            )

            self.assertNotEqual(
                0,
                result.returncode,
                "a native fake Java self-reported every dynamic challenge and was trusted:\n" + result.stdout,
            )

    def test_tree_digest_is_sorted_and_binds_content_mode_and_link_target(self):
        with tempfile.TemporaryDirectory(prefix="issue66-tree-digest-") as directory:
            parent = pathlib.Path(directory)
            first = parent / "first"
            second = parent / "second"
            self._write_digest_fixture(first, reverse=False)
            self._write_digest_fixture(second, reverse=True)

            baseline = VALIDATOR_MODULE.compute_jdk_tree_digest(first)
            self.assertEqual(baseline, VALIDATOR_MODULE.compute_jdk_tree_digest(first))
            self.assertEqual(baseline, VALIDATOR_MODULE.compute_jdk_tree_digest(second))

            first.joinpath("lib", "payload.bin").write_bytes(b"changed")
            self.assertNotEqual(baseline, VALIDATOR_MODULE.compute_jdk_tree_digest(first))
            first.joinpath("lib", "payload.bin").write_bytes(b"payload")
            first.joinpath("lib", "payload.bin").chmod(0o755)
            self.assertNotEqual(baseline, VALIDATOR_MODULE.compute_jdk_tree_digest(first))
            first.joinpath("lib", "payload.bin").chmod(0o644)
            first.joinpath("lib").chmod(0o700)
            self.assertNotEqual(baseline, VALIDATOR_MODULE.compute_jdk_tree_digest(first))
            first.joinpath("lib").chmod(0o755)
            first.joinpath("legal", "COPY").unlink()
            first.joinpath("legal", "COPY").symlink_to("../lib/other.bin")
            self.assertNotEqual(baseline, VALIDATOR_MODULE.compute_jdk_tree_digest(first))

    def test_tree_digest_normalizes_host_reported_symlink_mode_only(self):
        with tempfile.TemporaryDirectory(prefix="issue66-tree-symlink-mode-") as directory:
            root = pathlib.Path(directory) / "jdk"
            self._write_digest_fixture(root, reverse=False)
            baseline = VALIDATOR_MODULE.compute_jdk_tree_digest(root)
            real_stat = os.stat

            def stat_with_linux_symlink_mode(path, *args, **kwargs):
                value = real_stat(path, *args, **kwargs)
                if kwargs.get("follow_symlinks") is False and stat.S_ISLNK(value.st_mode):
                    fields = list(value)
                    fields[0] = stat.S_IFLNK | 0o777
                    return os.stat_result(fields)
                return value

            with mock.patch.object(
                VALIDATOR_MODULE.os,
                "stat",
                side_effect=stat_with_linux_symlink_mode,
            ):
                linux_view = VALIDATOR_MODULE.compute_jdk_tree_digest(root)

            self.assertEqual(
                baseline,
                linux_view,
                "the same symlink target acquired a host-specific mode in the tree digest",
            )

    def test_tree_digest_rejects_escaping_links_and_special_files(self):
        with tempfile.TemporaryDirectory(prefix="issue66-tree-boundary-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.mkdir(mode=0o755)
            root.joinpath("escape").symlink_to("../outside")
            with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                VALIDATOR_MODULE.compute_jdk_tree_digest(root)

            root.joinpath("escape").unlink()
            os.mkfifo(root / "runtime-pipe", mode=0o600)
            with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_enforces_entry_and_byte_ceilings(self):
        with tempfile.TemporaryDirectory(prefix="issue66-tree-cap-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.mkdir(mode=0o755)
            root.joinpath("payload").write_bytes(b"12")
            original_entry_limit = VALIDATOR_MODULE.TREE_ENTRY_LIMIT
            original_byte_limit = VALIDATOR_MODULE.TREE_TOTAL_BYTES_LIMIT
            try:
                VALIDATOR_MODULE.TREE_ENTRY_LIMIT = 1
                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)
                VALIDATOR_MODULE.TREE_ENTRY_LIMIT = original_entry_limit
                VALIDATOR_MODULE.TREE_TOTAL_BYTES_LIMIT = 1
                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)
            finally:
                VALIDATOR_MODULE.TREE_ENTRY_LIMIT = original_entry_limit
                VALIDATOR_MODULE.TREE_TOTAL_BYTES_LIMIT = original_byte_limit

    def test_tree_digest_stops_after_the_remaining_entry_budget_plus_one(self):
        with tempfile.TemporaryDirectory(prefix="issue66-tree-stream-cap-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.mkdir(mode=0o755)
            root_identity = (root.stat().st_dev, root.stat().st_ino)
            observed = _ObservableScandir(f"entry-{index}" for index in range(100))
            real_listdir = os.listdir
            original_entry_limit = VALIDATOR_MODULE.TREE_ENTRY_LIMIT

            def reject_eager_tree_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == root_identity:
                    raise AssertionError("JDK validator used eager os.listdir")
                return real_listdir(directory_fd)

            def observable_tree_enumeration(directory_fd):
                value = os.fstat(directory_fd)
                if (value.st_dev, value.st_ino) == root_identity:
                    return observed
                return _ObservableScandir(())

            try:
                VALIDATOR_MODULE.TREE_ENTRY_LIMIT = 4
                with mock.patch.object(
                    VALIDATOR_MODULE.os,
                    "listdir",
                    side_effect=reject_eager_tree_enumeration,
                ), mock.patch.object(
                    VALIDATOR_MODULE.os,
                    "scandir",
                    side_effect=observable_tree_enumeration,
                ):
                    with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                        VALIDATOR_MODULE.compute_jdk_tree_digest(root)
            finally:
                VALIDATOR_MODULE.TREE_ENTRY_LIMIT = original_entry_limit

            self.assertEqual(
                4,
                observed.next_requests,
                "JDK enumeration requested ceiling+2 instead of stopping at remaining+1",
            )

    def test_tree_digest_rejects_a_macho_dependency_outside_the_jdk_tree(self):
        with tempfile.TemporaryDirectory(prefix="issue66-macho-external-") as directory:
            parent = pathlib.Path(directory)
            root = parent / "jdk"
            root.mkdir(mode=0o755)
            external_root = parent / "user-writable"
            external_root.mkdir(mode=0o777)
            external_root.chmod(0o777)
            external_library = external_root / "libattacker.dylib"
            external_library.write_bytes(b"attacker-controlled")
            self._write_macho(
                root / "libdanger.dylib",
                dependencies=[os.fspath(external_library)],
            )

            with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_accepts_a_closed_macho_dependency_graph(self):
        with tempfile.TemporaryDirectory(prefix="issue66-macho-closed-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.joinpath("bin").mkdir(parents=True, mode=0o755)
            root.joinpath("lib").mkdir(mode=0o755)
            self._write_macho(
                root / "bin" / "java",
                rpaths=["@loader_path/../lib"],
                dependencies=["@rpath/libsafe.dylib", "/usr/lib/libSystem.B.dylib"],
            )
            self._write_macho(
                root / "lib" / "libsafe.dylib",
                dependencies=["/System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation"],
            )

            self.assertRegex(
                VALIDATOR_MODULE.compute_jdk_tree_digest(root),
                r"^[0-9a-f]{64}$",
            )

    def test_tree_digest_rejects_path_traversal_disguised_as_a_system_dependency(self):
        with tempfile.TemporaryDirectory(prefix="issue66-macho-system-traversal-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.mkdir(mode=0o755)
            self._write_macho(
                root / "libdanger.dylib",
                dependencies=["/usr/lib/../../tmp/libattacker.dylib"],
            )

            with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_rejects_obsolete_pathname_load_commands(self):
        commands = {
            0x6: 20,  # LC_LOADFVMLIB
            0x9: 16,  # LC_FVMFILE
            0x10: 20,  # LC_PREBOUND_DYLIB
        }
        for command_id, fixed_size in commands.items():
            with self.subTest(command_id=hex(command_id)), tempfile.TemporaryDirectory(
                prefix="issue66-macho-obsolete-path-",
            ) as directory:
                root = pathlib.Path(directory) / "jdk"
                root.mkdir(mode=0o755)
                self._write_macho_commands(
                    root / "libdanger.dylib",
                    [
                        self._macho_path_command(
                            command_id,
                            fixed_size,
                            "/tmp/user-writable/libattacker.dylib",
                        ),
                    ],
                )

                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_rejects_unsupported_pathname_identity_commands(self):
        commands = {
            0x7: 20,  # LC_IDFVMLIB
            0xF: 12,  # LC_ID_DYLINKER
        }
        for command_id, fixed_size in commands.items():
            with self.subTest(command_id=hex(command_id)), tempfile.TemporaryDirectory(
                prefix="issue66-macho-unsupported-path-",
            ) as directory:
                root = pathlib.Path(directory) / "jdk"
                root.mkdir(mode=0o755)
                self._write_macho_commands(
                    root / "libdanger.dylib",
                    [
                        self._macho_path_command(
                            command_id,
                            fixed_size,
                            "/tmp/user-writable/identity",
                        ),
                    ],
                )

                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_rejects_an_unknown_lc_str_style_command(self):
        with tempfile.TemporaryDirectory(prefix="issue66-macho-unknown-path-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.mkdir(mode=0o755)
            self._write_macho_commands(
                root / "libdanger.dylib",
                [
                    self._macho_path_command(
                        0x7FFFFFFE,
                        12,
                        "/tmp/user-writable/future-loader-path",
                    ),
                ],
            )

            with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_rejects_every_external_dylib_load_variant(self):
        command_ids = {
            0xC,  # LC_LOAD_DYLIB
            0x80000018,  # LC_LOAD_WEAK_DYLIB
            0x8000001F,  # LC_REEXPORT_DYLIB
            0x20,  # LC_LAZY_LOAD_DYLIB
            0x80000023,  # LC_LOAD_UPWARD_DYLIB
        }
        for command_id in command_ids:
            with self.subTest(command_id=hex(command_id)), tempfile.TemporaryDirectory(
                prefix="issue66-macho-load-variant-",
            ) as directory:
                root = pathlib.Path(directory) / "jdk"
                root.mkdir(mode=0o755)
                self._write_macho_commands(
                    root / "libdanger.dylib",
                    [
                        self._macho_path_command(
                            command_id,
                            24,
                            "/tmp/user-writable/libattacker.dylib",
                        ),
                    ],
                )

                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)

        with tempfile.TemporaryDirectory(prefix="issue66-macho-load-alternate-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.mkdir(mode=0o755)
            self._write_macho_commands(
                root / "libdanger.dylib",
                [
                    # macOS 15's dylib_use_command reuses LC_LOAD_DYLIB but
                    # moves the pathname behind a 28-byte fixed structure.
                    self._macho_path_command(
                        0xC,
                        28,
                        "/tmp/user-writable/libattacker.dylib",
                    ),
                ],
            )
            with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_accepts_every_closed_dylib_load_variant(self):
        command_ids = {
            0xC,  # LC_LOAD_DYLIB
            0x80000018,  # LC_LOAD_WEAK_DYLIB
            0x8000001F,  # LC_REEXPORT_DYLIB
            0x20,  # LC_LAZY_LOAD_DYLIB
            0x80000023,  # LC_LOAD_UPWARD_DYLIB
        }
        with tempfile.TemporaryDirectory(prefix="issue66-macho-load-variant-closed-") as directory:
            root = pathlib.Path(directory) / "jdk"
            root.joinpath("lib").mkdir(parents=True, mode=0o755)
            commands = []
            for index, command_id in enumerate(sorted(command_ids)):
                root.joinpath("lib", f"libsafe-{index}.dylib").write_bytes(b"closed")
                commands.append(
                    self._macho_path_command(
                        command_id,
                        24,
                        f"@loader_path/lib/libsafe-{index}.dylib",
                    ),
                )
            self._write_macho_commands(root / "java", commands)

            self.assertRegex(
                VALIDATOR_MODULE.compute_jdk_tree_digest(root),
                r"^[0-9a-f]{64}$",
            )

    def test_tree_digest_rejects_escaping_rpaths_and_dyld_environment(self):
        commands = {
            "absolute-rpath": self._macho_path_command(
                0x8000001C,
                12,
                "/tmp/user-writable",
            ),
            "escaping-rpath": self._macho_path_command(
                0x8000001C,
                12,
                "@loader_path/../../user-writable",
            ),
            "dyld-environment": self._macho_path_command(
                0x27,
                12,
                "DYLD_LIBRARY_PATH=/tmp/user-writable",
            ),
        }
        for label, command in commands.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory(
                prefix="issue66-macho-search-path-",
            ) as directory:
                root = pathlib.Path(directory) / "jdk"
                root.mkdir(mode=0o755)
                self._write_macho_commands(root / "libdanger.dylib", [command])

                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_rejects_external_dylinker_and_dylib_identity_paths(self):
        commands = {
            "load-dylinker": self._macho_path_command(
                0xE,
                12,
                "/tmp/user-writable/dyld",
            ),
            "dylib-identity": self._macho_path_command(
                0xD,
                24,
                "/tmp/user-writable/libattacker.dylib",
            ),
        }
        for label, command in commands.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory(
                prefix="issue66-macho-identity-path-",
            ) as directory:
                root = pathlib.Path(directory) / "jdk"
                root.mkdir(mode=0o755)
                self._write_macho_commands(root / "libdanger.dylib", [command])
                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_tree_digest_rejects_fat_and_truncated_macho_metadata(self):
        fat_magics = {
            b"\xca\xfe\xba\xbe",
            b"\xbe\xba\xfe\xca",
            b"\xca\xfe\xba\xbf",
            b"\xbf\xba\xfe\xca",
        }
        for magic in fat_magics:
            with self.subTest(magic=magic.hex()), tempfile.TemporaryDirectory(
                prefix="issue66-macho-fat-",
            ) as directory:
                root = pathlib.Path(directory) / "jdk"
                root.mkdir(mode=0o755)
                root.joinpath("universal").write_bytes(magic)
                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)

        truncated_payloads = {
            "thin-header": struct.pack("<I", 0xFEEDFACF),
            "dylib-command": self._macho_file(
                [struct.pack("<IIIII", 0xC, 20, 12, 0, 0)],
            ),
            "rpath-command": self._macho_file(
                [struct.pack("<II", 0x8000001C, 8)],
            ),
            "obsolete-path-command": self._macho_file(
                [
                    self._macho_path_command(
                        0x6,
                        12,
                        "/tmp/user-writable/libattacker.dylib",
                    ),
                ],
            ),
        }
        for label, payload in truncated_payloads.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory(
                prefix="issue66-macho-truncated-",
            ) as directory:
                root = pathlib.Path(directory) / "jdk"
                root.mkdir(mode=0o755)
                root.joinpath("libdanger.dylib").write_bytes(payload)
                with self.assertRaises(VALIDATOR_MODULE.RuntimeValidationError):
                    VALIDATOR_MODULE.compute_jdk_tree_digest(root)

    def test_profiles_pin_both_supported_platforms(self):
        profiles = {value["profileId"]: value for value in VALIDATOR_MODULE.load_profiles()}
        self.assertEqual(
            {
                "darwin-aarch64-eclipse-temurin-17.0.20.1+1",
                "linux-x86_64-eclipse-temurin-17.0.20.1+1",
            },
            set(profiles),
        )
        self.assertEqual(
            ("darwin", "aarch64", 17, "Eclipse Adoptium", "Eclipse Adoptium", "17.0.20.1+1"),
            self._profile_identity(
                profiles["darwin-aarch64-eclipse-temurin-17.0.20.1+1"],
            ),
        )
        self.assertEqual(
            ("linux", "x86_64", 17, "Eclipse Adoptium", "Eclipse Adoptium", "17.0.20.1+1"),
            self._profile_identity(profiles["linux-x86_64-eclipse-temurin-17.0.20.1+1"]),
        )
        self.assertEqual(
            {
                "darwin-aarch64-eclipse-temurin-17.0.20.1+1":
                    "f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8",
                "linux-x86_64-eclipse-temurin-17.0.20.1+1":
                    "427182064043c17bb698c7f9c5949f755f6dd80dddaf760b6fa7413178189a97",
            },
            {
                profile_id: profile["jdkTreeSha256"]
                for profile_id, profile in profiles.items()
            },
            "both supported platforms must stay statically bound on every host",
        )
        verifier = (REPO_ROOT / "scripts" / "verify-a-plus.sh").read_text()
        profile_source = verifier.split("reviewed_java_profiles = ", 1)[1].split(
            "\n\n", 1,
        )[0]
        self.assertEqual(
            {
                profile_id: tuple(profile[field] for field in (
                    "javaVendor", "javaVmVendor", "javaRuntimeVersion", "jdkTreeSha256",
                ))
                for profile_id, profile in profiles.items()
            },
            ast.literal_eval(profile_source),
            "offline receipt admission must bind the complete registered Java identities",
        )

    def test_ci_normalizes_only_the_exact_jdk_cache_containers(self):
        import textwrap

        workflow = (REPO_ROOT / ".github/workflows/android-a-plus.yml").read_text()
        host_job = workflow.split("  auto-qwy-host-integration:\n", 1)[1].split(
            "\n  install-guards:", 1,
        )[0]
        marker = "      - name: normalize reviewed JDK cache containers\n"
        self.assertIn(marker, host_job, "CI passes an unsafe cache root directly to validation")
        step_start = host_job.index(marker)
        next_step = host_job.index("\n      - name:", step_start + len(marker))
        step = host_job[step_start:next_step]
        self.assertLess(host_job.index("freeze preinstalled Android SDK permissions"), step_start)
        self.assertTrue(host_job[next_step:].startswith("\n      - name: standalone runtime security tests"))
        self.assertIn("shell: /bin/bash --noprofile --norc -p -euo pipefail {0}", step)
        program = textwrap.dedent(step.split("        run: |\n", 1)[1])
        self.assertNotIn("--recursive", program)
        self.assertNotIn("chmod -R", program)
        expected_prefix = "/opt/hostedtoolcache"
        self.assertIn(
            "readonly expected_jdk=/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-101/x64",
            program,
        )
        for scenario in ("valid", "wrong_home", "root_symlink", "parent_symlink", "root_file"):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(
                prefix="issue66-ci-jdk-permissions-",
            ) as directory:
                private = pathlib.Path(directory).resolve()
                cache = private / "toolcache"
                version = cache / "Java_Temurin-Hotspot_jdk" / "17.0.20-101"
                jdk = version / "x64"
                (jdk / "bin").mkdir(parents=True)
                (jdk / "bin/java").write_bytes(b"fixture Java payload; never executed")
                (jdk / "bin/java").chmod(0o755)
                (cache / "unrelated").write_bytes(b"unrelated cache payload")
                (cache / "unrelated").chmod(0o777)
                targets = (cache, version.parent, version, jdk)
                for target in targets:
                    target.chmod(0o777)
                if scenario in {"root_symlink", "parent_symlink", "root_file"}:
                    target = version if scenario == "parent_symlink" else jdk
                    original = target.with_name(target.name + ".original")
                    target.rename(original)
                    if scenario == "root_file":
                        target.write_bytes(b"not a directory")
                    else:
                        target.symlink_to(original, target_is_directory=True)

                def snapshot():
                    result = {}
                    for path in (cache, *cache.rglob("*")):
                        state = path.lstat()
                        if stat.S_ISLNK(state.st_mode):
                            payload = os.readlink(path)
                        elif stat.S_ISREG(state.st_mode):
                            payload = path.read_bytes()
                        else:
                            payload = None
                        result[path] = (state.st_mode, payload)
                    return result

                before = snapshot()
                # Private process-owned fixtures do not need sudo. The only other
                # substitutions bind the fixed cache prefix and OS chmod location.
                fixture_program = program.replace(expected_prefix, str(cache)).replace(
                    "/usr/bin/sudo -- ", "",
                )
                if not os.path.exists("/usr/bin/chmod"):
                    fixture_program = fixture_program.replace("/usr/bin/chmod", "/bin/chmod")
                completed = subprocess.run(
                    ["/bin/bash", "-p", "-euo", "pipefail", "-c", fixture_program],
                    env={
                        "PATH": "/usr/bin:/bin", "ADB": "/usr/bin/false",
                        "JAVA_HOME": str(jdk) + (".wrong" if scenario == "wrong_home" else ""),
                    },
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
                )
                after = snapshot()
                if scenario == "valid":
                    self.assertEqual(0, completed.returncode, completed.stderr)
                    self.assertEqual([0o755] * 4, [stat.S_IMODE(path.stat().st_mode) for path in targets])
                    self.assertEqual(
                        {path: value for path, value in before.items() if path not in targets},
                        {path: value for path, value in after.items() if path not in targets},
                        "normalization altered JDK descendants or unrelated cache entries",
                    )
                else:
                    self.assertNotEqual(0, completed.returncode)
                    self.assertEqual(before, after, "invalid layout was modified before rejection")

    def test_checked_profile_trees_reproduce_the_committed_digests(self):
        configured = {
            "ISSUE66_DARWIN_TEMURIN_JDK17_HOME": os.environ.get(
                "ISSUE66_DARWIN_TEMURIN_JDK17_HOME",
            ),
            "ISSUE66_TEMURIN_JDK17_HOME": os.environ.get(
                "ISSUE66_TEMURIN_JDK17_HOME",
            ),
        }
        profile_by_environment = {
            "ISSUE66_DARWIN_TEMURIN_JDK17_HOME":
                "darwin-aarch64-eclipse-temurin-17.0.20.1+1",
            "ISSUE66_TEMURIN_JDK17_HOME":
                "linux-x86_64-eclipse-temurin-17.0.20.1+1",
        }
        required_environment_by_platform = {
            "darwin": "ISSUE66_DARWIN_TEMURIN_JDK17_HOME",
            "linux": "ISSUE66_TEMURIN_JDK17_HOME",
        }
        self.assertIn(
            sys.platform,
            required_environment_by_platform,
            "the active host platform has no reviewed Java 17 profile",
        )
        required_environment = required_environment_by_platform[sys.platform]
        self.assertTrue(
            configured.get(required_environment),
            f"{required_environment} must identify the active platform's reviewed JDK",
        )
        profiles = {value["profileId"]: value for value in VALIDATOR_MODULE.load_profiles()}
        for environment, home in configured.items():
            if not home:
                continue
            profile_id = profile_by_environment[environment]
            self.assertEqual(
                profiles[profile_id]["jdkTreeSha256"],
                VALIDATOR_MODULE.compute_jdk_tree_digest(pathlib.Path(home)),
                profile_id,
            )

    def test_real_runtime_binding_can_be_rechecked_and_tampering_is_rejected(self):
        java_home = os.environ.get("ISSUE66_ACTIVE_JDK17_HOME") or os.environ.get("JAVA_HOME")
        if not java_home:
            self.skipTest("an active reviewed Java 17 profile is required")
        clean_environment = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"}
        emitted = subprocess.run(
            ["/usr/bin/python3", "-I", os.fspath(VALIDATOR), "--emit-binding", java_home],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env=clean_environment,
            check=False,
        )
        self.assertEqual(0, emitted.returncode, emitted.stdout)
        binding = json.loads(emitted.stdout)
        verified = subprocess.run(
            [
                "/usr/bin/python3",
                "-I",
                os.fspath(VALIDATOR),
                "--verify-binding",
                java_home,
                VALIDATOR_MODULE.encode_binding(binding),
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env=clean_environment,
            check=False,
        )
        self.assertEqual(0, verified.returncode, verified.stdout)
        self.assertEqual(binding, json.loads(verified.stdout))

        binding["jdkTreeSha256"] = "0" * 64
        rejected = subprocess.run(
            [
                "/usr/bin/python3",
                "-I",
                os.fspath(VALIDATOR),
                "--verify-binding",
                java_home,
                VALIDATOR_MODULE.encode_binding(binding),
            ],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env=clean_environment,
            check=False,
        )
        self.assertNotEqual(0, rejected.returncode, rejected.stdout)

    def _write_digest_fixture(self, root, *, reverse):
        paths = [root / "bin", root / "lib", root / "legal"]
        for path in reversed(paths) if reverse else paths:
            path.mkdir(parents=True, mode=0o755, exist_ok=True)
            path.chmod(0o755)
        root.chmod(0o755)
        files = [
            (root / "bin" / "java", b"native-java", 0o755),
            (root / "lib" / "payload.bin", b"payload", 0o644),
            (root / "lib" / "other.bin", b"other", 0o644),
        ]
        for path, payload, mode in reversed(files) if reverse else files:
            path.write_bytes(payload)
            path.chmod(mode)
        root.joinpath("legal", "COPY").symlink_to("../lib/payload.bin")

    def _macho_path_command(self, command_id, fixed_size, value):
        payload = value.encode("utf-8") + b"\x00"
        command_size = (fixed_size + len(payload) + 7) & ~7
        header = struct.pack("<III", command_id, command_size, fixed_size)
        header += b"\x00" * (fixed_size - len(header))
        return header + payload + b"\x00" * (command_size - fixed_size - len(payload))

    def _write_macho_commands(self, path, commands):
        path.write_bytes(self._macho_file(commands))
        path.chmod(0o755)

    def _macho_file(self, commands):
        header = struct.pack(
            "<IiiIIIII",
            0xFEEDFACF,
            0x0100000C,
            0,
            2,
            len(commands),
            sum(len(value) for value in commands),
            0,
            0,
        )
        return header + b"".join(commands)

    def _write_macho(self, path, *, dependencies, rpaths=()):
        commands = [
            self._macho_path_command(0x8000001C, 12, value)
            for value in rpaths
        ]
        commands.extend(
            self._macho_path_command(0xC, 24, value)
            for value in dependencies
        )
        self._write_macho_commands(path, commands)

    def _profile_identity(self, profile_value):
        return (
            profile_value["os"],
            profile_value["arch"],
            profile_value["javaMajor"],
            profile_value["javaVendor"],
            profile_value["javaVmVendor"],
            profile_value["javaRuntimeVersion"],
        )


if __name__ == "__main__":
    unittest.main()

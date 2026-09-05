package io.github.terryyyc.fakexxx.integration.pr63issue66

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRunnerEnvironmentGuardTest {

    @Test
    fun `runner rejects inherited exported Bash function before selector-controlled inspection`() {
        val canonicalRunner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val fixture = Files.createTempDirectory("issue66-host-env-guard-").toRealPath()
        val runner = fixture.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val marker = fixture.resolve("exported-function-ran")
        val developerDirectory = fixture.resolve("developer-tools")
        try {
            Files.createDirectories(runner.parent)
            Files.copy(canonicalRunner, runner)
            makeExecutable(runner)
            Files.createDirectories(developerDirectory.resolve("usr/bin"))
            Files.createSymbolicLink(
                developerDirectory.resolve("usr/bin/xcrun"),
                Paths.get("/usr/bin/true"),
            )
            writeExecutable(
                fixture.resolve("apps/cellrebel-auto/gradlew"),
                "#!/usr/bin/env bash\nfc2_exported_probe\n",
            )
            writeExecutable(
                fixture.resolve("apps/qianwangyou/gradlew"),
                "#!/bin/sh\nexit 0\n",
            )

            val process = ProcessBuilder(runner.toString(), "--fc2-probe")
                .directory(fixture.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/usr/bin:/bin"
                    environment()["JAVA_HOME"] = System.getProperty("java.home")
                    environment()["ANDROID_HOME"] = fixture.resolve("android-sdk").toString()
                    environment()["DEVELOPER_DIR"] = developerDirectory.toString()
                    environment()["BASH_FUNC_fc2_exported_probe%%"] =
                        "() { /usr/bin/touch \"$marker\"; }"
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals("runner accepted an inherited Bash function:\n$output", 1, process.waitFor())
            assertTrue(
                "runner did not report the typed inherited-function rejection:\n$output",
                output.contains("HOST_GATE_UNSAFE_INHERITED_BASH_FUNCTION_ENV"),
            )
            assertFalse("an unprivileged child re-imported the function", Files.exists(marker))
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner rejects a fake Java 17 shim before Gradle wrapper dispatch`() {
        val canonicalRunner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val fixture = Files.createTempDirectory("issue66-host-fake-java-").toRealPath()
        val runner = fixture.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val fakeJavaHome = fixture.resolve("fake-jdk-17")
        val wrapperMarker = fixture.resolve("gradle-wrapper-dispatched")
        try {
            Files.createDirectories(runner.parent)
            Files.copy(canonicalRunner, runner)
            makeExecutable(runner)
            writeExecutable(
                fakeJavaHome.resolve("bin/java"),
                "#!/bin/sh\n" +
                    "case \"\$*\" in\n" +
                    "  *-version*)\n" +
                    "    printf '%s\\n' 'Property settings:' " +
                    "'    java.home = $fakeJavaHome' '    java.specification.version = 17' >&2\n" +
                    "    printf '%s\\n' 'openjdk version \"17.0.99\"' >&2\n" +
                    "    ;;\n" +
                    "esac\n" +
                    "exit 0\n",
            )
            writeExecutable(
                fixture.resolve("apps/cellrebel-auto/gradlew"),
                "#!/bin/sh\n" +
                    "/usr/bin/touch \"$wrapperMarker\"\n" +
                    "exec \"\$JAVA_HOME/bin/java\" \"\$@\"\n",
            )
            writeExecutable(
                fixture.resolve("apps/qianwangyou/gradlew"),
                "#!/bin/sh\nexit 0\n",
            )

            val process = ProcessBuilder(runner.toString(), "--version")
                .directory(fixture.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/usr/bin:/bin"
                    environment()["JAVA_HOME"] = fakeJavaHome.toString()
                    environment()["ANDROID_HOME"] = fixture.resolve("android-sdk").toString()
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals("runner trusted a caller-supplied fake Java runtime:\n$output", 1, process.waitFor())
            assertTrue(
                "runner omitted its typed Java-runtime rejection:\n$output",
                "HOST_GATE_JAVA_RUNTIME_INVALID" in output,
            )
            assertFalse("runner dispatched Gradle behind the fake runtime", Files.exists(wrapperMarker))
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate verifier rejects a fake Java 17 shim before imported Gradle gates`() {
        val canonicalVerifier = findRepoRoot().resolve("scripts/verify-a-plus.sh")
        val fixture = Files.createTempDirectory(
            findRepoRoot().resolve("integration-tests/pr63-on-issue66/harness/build"),
            "issue66-aggregate-fake-java-",
        ).toRealPath()
        val verifier = fixture.resolve("scripts/verify-a-plus.sh")
        val fakeJavaHome = fixture.resolve("fake-jdk-17")
        val wrapperMarker = fixture.resolve("aggregate-gradle-wrapper-dispatched")
        val androidHome = requireAndroidSdk35()
        try {
            Files.createDirectories(verifier.parent)
            Files.createDirectories(
                fixture.resolve("integration-tests/pr63-on-issue66/harness"),
            )
            Files.copy(canonicalVerifier, verifier)
            makeExecutable(verifier)
            listOf(
                "validate-java17-runtime.py",
                "stage-java17-runtime.py",
                "validate-android-sdk-runtime.py",
            ).forEach { name ->
                Files.copy(
                    findRepoRoot().resolve("scripts/$name"),
                    fixture.resolve("scripts/$name"),
                )
            }
            Files.createDirectories(verifier.resolveSibling("fixtures"))
            Files.copy(
                findRepoRoot().resolve("scripts/fixtures/issue66-java17-runtime-profiles.json"),
                verifier.resolveSibling("fixtures/issue66-java17-runtime-profiles.json"),
            )
            writeExecutable(
                fakeJavaHome.resolve("bin/java"),
                "#!/bin/sh\n" +
                    "case \"\$*\" in\n" +
                    "  *-version*)\n" +
                    "    printf '%s\\n' 'Property settings:' " +
                    "'    java.home = $fakeJavaHome' '    java.specification.version = 17' >&2\n" +
                    "    printf '%s\\n' 'openjdk version \"17.0.99\"' >&2\n" +
                    "    ;;\n" +
                    "esac\n" +
                    "exit 0\n",
            )
            listOf("apps/cellrebel-auto/gradlew", "apps/qianwangyou/gradlew").forEach { relative ->
                writeExecutable(
                    fixture.resolve(relative),
                    "#!/bin/sh\n" +
                        "/usr/bin/touch \"$wrapperMarker\"\n" +
                        "exec \"\$JAVA_HOME/bin/java\" \"\$@\"\n",
                )
            }
            listOf("scripts/check-provenance.sh", "scripts/check-inherited-lint-debt.sh")
                .forEach { relative -> writeExecutable(fixture.resolve(relative), "#!/bin/sh\nexit 0\n") }

            val process = ProcessBuilder(verifier.toString(), "--stage", "import")
                .directory(fixture.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/usr/bin:/bin"
                    environment()["JAVA_HOME"] = fakeJavaHome.toString()
                    environment()["ANDROID_HOME"] = androidHome.toString()
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals("aggregate verifier trusted a fake Java runtime:\n$output", 1, process.waitFor())
            assertTrue(
                "aggregate verifier omitted its typed Java-runtime rejection:\n$output",
                "VERIFY_A_PLUS_JAVA_RUNTIME_INVALID" in output,
            )
            assertFalse("aggregate verifier dispatched Gradle behind the fake runtime", Files.exists(wrapperMarker))
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate verifier stages one private JDK and never exposes the requested source to gates`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val listExit = verifier.indexOf("if [ \"\$LIST_ONLY\" -eq 1 ]; then")
        val tempRootCreation = verifier.indexOf(
            "verify_temp_root=\"\$(create_verify_temp_root \"\$verify_temp_anchor\")\"",
        )
        val javaStageCreation = verifier.indexOf(
            "host_java_stage_root=\"\$(create_private_java_runtime_root \"\$verify_temp_root\")\"",
        )
        val javaStageCall = verifier.indexOf(
            "stage_java_runtime \"\$requested_java_home\" \"\$host_java_stage_root\"",
        )
        val firstGate = verifier.indexOf("while IFS='|' read -r rank name pr file cmd; do", javaStageCall)

        assertTrue(
            "aggregate verifier must finish its list-only path before allocating private state",
            listExit >= 0 && tempRootCreation > listExit,
        )
        assertTrue(
            "aggregate verifier does not stage the requested JDK before its first gate",
            tempRootCreation >= 0 && javaStageCreation > tempRootCreation &&
                javaStageCall > javaStageCreation && firstGate > javaStageCall,
        )
        assertTrue(
            "aggregate verifier does not bind the reviewed validator and descriptor stager",
            "readonly java_profile_validator=\"\$REPO_ROOT/scripts/validate-java17-runtime.py\"" in verifier &&
                "readonly java_runtime_stager=\"\$REPO_ROOT/scripts/stage-java17-runtime.py\"" in verifier,
        )
        assertTrue(
            "aggregate private root is not inside the ignored harness build namespace",
            "readonly verify_temp_anchor=\"\$REPO_ROOT/integration-tests/pr63-on-issue66/harness\"" in
                verifier &&
                "verify-a-plus\\.[0-9a-f]{8}" in verifier,
        )
        assertTrue(
            "aggregate JDK stage does not use the exact private runtime namespace",
            "jdk-runtime\\.[0-9a-f]{32}" in verifier &&
                "[[ \"\$host_java_home\" != \"\$host_java_stage_root/home\" ]]" in verifier,
        )
        assertTrue(
            "aggregate verifier does not parse and revalidate the canonical staged binding",
            "read_java_binding_field \"\$host_java_binding\" javaHome" in verifier &&
                "--verify-binding \"\$host_java_home\" \"\$host_java_binding\"" in verifier &&
                "canonical != raw" in verifier,
        )
        assertTrue(
            "the inherited raw JAVA_HOME remains exported after it is captured",
            verifier.indexOf("unset JAVA_HOME") > verifier.indexOf(
                "readonly requested_java_home=\"\${JAVA_HOME:-}\"",
            ),
        )
        assertFalse(
            "aggregate verifier still validates and executes the requested source JDK directly",
            Regex(
                "validate-java17-runtime\\.py[\\s\\S]{0,80}\\\"\\\$requested_java_home\\\"",
            ).containsMatchIn(verifier),
        )

        val launcher = shellFunctionBefore(verifier, "run_clean_gate_command", "RUN=0;")
        val commandDispatch = launcher.indexOf("/usr/bin/env -i")
        val preJavaCheck = launcher.lastIndexOf("verify_java_runtime_binding", commandDispatch)
        val postJavaCheck = launcher.indexOf("verify_java_runtime_binding", commandDispatch)
        assertTrue(
            "each aggregate gate is not bracketed by exact staged-JDK binding checks",
            preJavaCheck >= 0 && commandDispatch > preJavaCheck && postJavaCheck > commandDispatch,
        )
        assertTrue(
            "aggregate child gates do not receive the staged JAVA_HOME",
            "JAVA_HOME=\"\$host_java_home\"" in launcher,
        )
        assertFalse(
            "aggregate child gates can observe the untrusted requested JAVA_HOME",
            "requested_java_home" in launcher,
        )

        val rawJavaHomeMutation = launcher.replaceFirst(
            "JAVA_HOME=\"\$host_java_home\"",
            "JAVA_HOME=\"\$requested_java_home\"",
        )
        assertNotEquals("raw-JAVA_HOME mutation was a no-op", launcher, rawJavaHomeMutation)
        assertTrue(
            "the regression guard no longer distinguishes a raw-JAVA_HOME child mutation",
            "requested_java_home" in rawJavaHomeMutation,
        )
    }

    @Test
    fun `controlled host scripts clear developer tool selectors before external commands`() {
        val repo = findRepoRoot()
        val scripts = linkedMapOf(
            "runner" to repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh"),
            "services checker" to repo.resolve("scripts/check-issue66-services-compatibility.sh"),
            "services selftest" to repo.resolve("scripts/selftest-issue66-services-compatibility.sh"),
            "services fake dexdump" to repo.resolve(
                "scripts/fixtures/issue66-services-compatibility/fake-dexdump.sh",
            ),
            "aggregate verifier" to repo.resolve("scripts/verify-a-plus.sh"),
        )

        scripts.forEach { (label, path) ->
            val source = path.readText()
            val selectorClear = source.indexOf(PINNED_DEVELOPER_SELECTOR_CLEAR)
            assertEquals(
                "$label developer-selector clear count changed",
                1,
                source.lineSequence().count { it == PINNED_DEVELOPER_SELECTOR_CLEAR },
            )
            val statementsBeforeClear = source.substring(0, selectorClear.coerceAtLeast(0))
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            assertTrue(
                "$label can execute an external command before clearing developer selectors: " +
                    statementsBeforeClear,
                selectorClear >= 0 &&
                    "unset BASH_ENV ENV" in statementsBeforeClear &&
                    statementsBeforeClear.all {
                        it == "unset BASH_ENV ENV" || it == "set -uo pipefail"
                    },
            )
            listOf("DEVELOPER_DIR", "SDKROOT", "TOOLCHAINS").forEach { name ->
                assertEquals(
                    "$label may restore $name after startup",
                    1,
                    Regex("\\b$name\\b").findAll(source).count(),
                )
            }
        }
    }

    @Test
    fun `aggregate verifier has an exact privileged Bash entrypoint`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()

        assertEquals(
            "aggregate verifier entrypoint can import inherited Bash functions before its guard",
            "#!/bin/bash -p",
            verifier.lineSequence().first(),
        )
    }

    @Test
    fun `aggregate privileged entry blocks imported set before its first shell statement`() {
        val canonicalSource = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val fixture = Files.createTempDirectory("issue66-aggregate-entry-guard-").toRealPath()
        val verifier = fixture.resolve("scripts/verify-a-plus.sh")
        val marker = fixture.resolve("imported-set-ran")
        try {
            fun run(source: String): Pair<Int, String> {
                Files.deleteIfExists(marker)
                writeExecutable(verifier, source)
                val process = ProcessBuilder(verifier.toString(), "--stage", "import")
                    .directory(fixture.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment()["PATH"] = "/usr/bin:/bin"
                        environment()["BASH_FUNC_set%%"] =
                            "() { /usr/bin/touch \"$marker\"; builtin set \"\$@\"; }"
                    }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                return process.waitFor() to output
            }

            val (canonicalStatus, canonicalOutput) = run(canonicalSource)
            assertEquals(
                "aggregate entry guard returned an unexpected status:\n$canonicalOutput",
                1,
                canonicalStatus,
            )
            assertFalse(
                "an inherited set function ran before the aggregate environment guard:\n$canonicalOutput",
                Files.exists(marker),
            )
            assertTrue(
                "aggregate verifier omitted its typed inherited-function stop:\n$canonicalOutput",
                "VERIFY_A_PLUS_UNSAFE_INHERITED_BASH_FUNCTION_ENV" in canonicalOutput,
            )

            val envBashSource = canonicalSource.replaceFirst("#!/bin/bash -p", "#!/usr/bin/env bash")
            val (mutantStatus, mutantOutput) = run(envBashSource)
            assertEquals(
                "env-Bash mutation returned an unexpected status:\n$mutantOutput",
                1,
                mutantStatus,
            )
            assertTrue(
                "the mutation probe no longer demonstrates why the privileged entrypoint is required:\n$mutantOutput",
                Files.exists(marker),
            )
            assertTrue(
                "env-Bash mutation did not eventually reach the typed inherited-function stop:\n$mutantOutput",
                "VERIFY_A_PLUS_UNSAFE_INHERITED_BASH_FUNCTION_ENV" in mutantOutput,
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate verifier rejects exported functions before an env Bash child can import them`() {
        val canonicalVerifier = findRepoRoot().resolve("scripts/verify-a-plus.sh")
        val fixture = Files.createTempDirectory(
            findRepoRoot().resolve("integration-tests/pr63-on-issue66/harness/build"),
            "issue66-aggregate-function-env-",
        ).toRealPath()
        val verifier = fixture.resolve("scripts/verify-a-plus.sh")
        val marker = fixture.resolve("inherited-dirname-ran")
        val javaHome = requireJava17Home()
        val androidHome = requireAndroidSdk35()
        try {
            Files.createDirectories(verifier.parent)
            Files.createDirectories(
                fixture.resolve("integration-tests/pr63-on-issue66/harness"),
            )
            Files.copy(canonicalVerifier, verifier)
            Files.copy(
                canonicalVerifier.resolveSibling("validate-java17-runtime.py"),
                verifier.resolveSibling("validate-java17-runtime.py"),
            )
            Files.copy(
                canonicalVerifier.resolveSibling("stage-java17-runtime.py"),
                verifier.resolveSibling("stage-java17-runtime.py"),
            )
            Files.copy(
                canonicalVerifier.resolveSibling("validate-android-sdk-runtime.py"),
                verifier.resolveSibling("validate-android-sdk-runtime.py"),
            )
            Files.createDirectories(verifier.resolveSibling("fixtures"))
            Files.copy(
                findRepoRoot().resolve("scripts/fixtures/issue66-java17-runtime-profiles.json"),
                verifier.resolveSibling("fixtures/issue66-java17-runtime-profiles.json"),
            )
            makeExecutable(verifier)
            writeExecutable(
                fixture.resolve("scripts/check-provenance.sh"),
                "#!/usr/bin/env bash\n" +
                    "dirname \"\${BASH_SOURCE[0]}\" >/dev/null\n" +
                    "exit 0\n",
            )

            fun run(poisoned: Boolean): Pair<Int, String> {
                val process = ProcessBuilder(verifier.toString(), "--stage", "import")
                    .directory(fixture.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment()["PATH"] = "/usr/bin:/bin"
                        environment()["JAVA_HOME"] = javaHome.toString()
                        environment()["ANDROID_HOME"] = androidHome.toString()
                        environment()["ADB"] = "/usr/bin/false"
                        environment()["LC_ALL"] = "C"
                        if (poisoned) {
                            environment()["BASH_FUNC_dirname%%"] =
                                "() { /usr/bin/touch \"$marker\"; /usr/bin/dirname \"\$@\"; }"
                        }
                    }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                return process.waitFor() to output
            }

            val (cleanStatus, cleanOutput) = run(poisoned = false)
            assertEquals("clean aggregate fixture returned an unexpected status:\n$cleanOutput", 1, cleanStatus)
            assertTrue(
                "clean aggregate fixture did not reach and run its ordinary child gate:\n$cleanOutput",
                "---- provenance" in cleanOutput && "-> PASS" in cleanOutput,
            )
            assertFalse(
                "clean aggregate fixture reported an inherited-environment startup stop:\n$cleanOutput",
                "VERIFY_A_PLUS_UNSAFE_INHERITED_BASH_FUNCTION_ENV" in cleanOutput ||
                    "VERIFY_A_PLUS_INHERITED_ENVIRONMENT_INSPECTION_UNAVAILABLE" in cleanOutput,
            )
            assertFalse("clean aggregate fixture created the poison marker", Files.exists(marker))

            val (poisonedStatus, poisonedOutput) = run(poisoned = true)
            assertEquals("poisoned aggregate verifier returned an unexpected status:\n$poisonedOutput", 1, poisonedStatus)
            assertFalse(
                "an env-Bash child imported and executed the inherited dirname function:\n$poisonedOutput",
                Files.exists(marker),
            )
            assertTrue(
                "aggregate verifier omitted its typed inherited-function stop:\n$poisonedOutput",
                "VERIFY_A_PLUS_UNSAFE_INHERITED_BASH_FUNCTION_ENV" in poisonedOutput,
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate inherited-function inspection precedes every external action`() {
        val verifier = findRepoRoot().resolve("scripts/verify-a-plus.sh").readText()
        val selectorClear = verifier.indexOf(PINNED_DEVELOPER_SELECTOR_CLEAR)
        val inspection = verifier.indexOf("inspect_inherited_environment() {")
        val inspectionEnd = verifier.indexOf(
            "\n}\n\ninherited_environment_status=0",
            inspection,
        )
        val inspectionCall = verifier.indexOf(
            "inspect_inherited_environment || inherited_environment_status=\$?",
            inspectionEnd,
        )
        val repositoryLookup = verifier.indexOf("REPO_ROOT=")
        val pythonCall = verifier.indexOf("/usr/bin/python3 -I -")
        val executablePrelude = verifier.substring(0, pythonCall.coerceAtLeast(0))
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

        assertTrue(
            "aggregate verifier can perform an external action before inherited-function rejection",
            selectorClear >= 0 && inspection > selectorClear && inspectionEnd > inspection &&
                inspectionCall > inspectionEnd && repositoryLookup > inspectionCall &&
                pythonCall > inspection,
        )
        assertEquals(
            "aggregate verifier gained an action before its fixed inherited-environment inspector",
            listOf(
                "set -uo pipefail",
                "unset BASH_ENV ENV",
                PINNED_DEVELOPER_SELECTOR_CLEAR,
                "PATH=/usr/bin:/bin",
                "export PATH",
                "inspect_inherited_environment() {",
                "[[ -f /usr/bin/python3 && -x /usr/bin/python3 ]] || return 70",
            ),
            executablePrelude,
        )
        assertTrue(
            "aggregate verifier must use fixed isolated Python with typed fail-closed outcomes",
            "/usr/bin/python3 -I -" in verifier &&
                "VERIFY_A_PLUS_UNSAFE_INHERITED_BASH_FUNCTION_ENV" in verifier &&
                "VERIFY_A_PLUS_INHERITED_ENVIRONMENT_INSPECTION_UNAVAILABLE" in verifier,
        )
    }

    @Test
    fun `clean child launcher strips executable environment hooks and preserves pinned tools`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val functionSource = shellFunction(runner, "run_clean_host_command", "validate_clean_gradle_user_home")
        val fixture = Files.createTempDirectory("issue66-host-clean-child-").toRealPath()
        val marker = fixture.resolve("inherited-hook-ran")
        val poison = fixture.resolve("bash-env-poison.sh")
        val report = fixture.resolve("child-environment.txt")
        val child = fixture.resolve("unprivileged-child.sh")
        val probe = fixture.resolve("probe.sh")
        val cleanHome = fixture.resolve("clean-home")
        val cleanGradleHome = fixture.resolve("clean-gradle-home")
        val fixedJavaHome = fixture.resolve("fixed-java-home")
        val fixedAndroidHome = fixture.resolve("fixed-android-home")
        try {
            Files.createDirectories(cleanHome)
            Files.createDirectories(cleanGradleHome)
            writeExecutable(
                child,
                "#!/usr/bin/env bash\n" +
                    "/usr/bin/python3 -I - \"$report\" <<'PY'\n" +
                    "import os\n" +
                    "import sys\n" +
                    "keys = [\n" +
                    "    'BASH_ENV', 'ENV', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',\n" +
                    "    '_JAVA_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS',\n" +
                    "    'JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME', 'HOME',\n" +
                    "    'ISSUE66_ACTIVE_JDK17_HOME', 'ISSUE66_DARWIN_TEMURIN_JDK17_HOME',\n" +
                    "    'ISSUE66_TEMURIN_JDK17_HOME',\n" +
                    "    'PATH', 'ADB',\n" +
                    "]\n" +
                    "with open(sys.argv[1], 'w', encoding='utf-8') as output:\n" +
                    "    for key in keys:\n" +
                    "        output.write(f'{key}={os.environ.get(key, \"<absent>\")}\\n')\n" +
                    "    output.write(f'BASH_FUNC_KEYS={sum(key.startswith(\"BASH_FUNC_\") for key in os.environ)}\\n')\n" +
                    "PY\n" +
                    "if type fc2_exported_probe >/dev/null 2>&1; then fc2_exported_probe; fi\n",
            )
            Files.write(poison, "/usr/bin/touch \"$marker\"\n".toByteArray())

            fun runLauncher(source: String): Pair<Int, String> {
                Files.deleteIfExists(marker)
                Files.deleteIfExists(report)
                Files.write(
                    probe,
                    (
                        "#!/bin/bash -p\n" +
                            "unset BASH_ENV ENV\n" +
                            "unset DEVELOPER_DIR SDKROOT TOOLCHAINS\n" +
                            "set -uo pipefail\n" +
                            "host_child_home=\"$cleanHome\"\n" +
                            "host_java_home=\"$fixedJavaHome\"\n" +
                            "host_java_darwin_temurin_profile_home=\"$fixedJavaHome\"\n" +
                            "host_java_temurin_profile_home=\"\"\n" +
                            "host_android_home=\"$fixedAndroidHome\"\n" +
                            "host_gradle_user_home=\"$cleanGradleHome\"\n" +
                            source + "\n" +
                            "run_clean_host_command \"$child\"\n"
                        ).toByteArray(),
                )
                makeExecutable(probe)
                val process = ProcessBuilder("/bin/bash", "-p", probe.toString())
                    .redirectErrorStream(true)
                    .apply {
                        environment()["BASH_ENV"] = poison.toString()
                        environment()["ENV"] = poison.toString()
                        environment()["JAVA_TOOL_OPTIONS"] = "-javaagent:/attacker/java-tool-options.jar"
                        environment()["JDK_JAVA_OPTIONS"] = "-javaagent:/attacker/jdk-java-options.jar"
                        environment()["_JAVA_OPTIONS"] = "-javaagent:/attacker/underscore-java-options.jar"
                        environment()["JAVA_OPTS"] = "-javaagent:/attacker/java-opts.jar"
                        environment()["GRADLE_OPTS"] = "-I /attacker/init.gradle"
                        environment()["GRADLE_USER_HOME"] = fixture.resolve("attacker-gradle-home").toString()
                        environment()["BASH_FUNC_fc2_exported_probe%%"] =
                            "() { /usr/bin/touch \"$marker\"; }"
                    }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                return process.waitFor() to output
            }

            val (status, output) = runLauncher(functionSource)
            assertEquals("clean child launcher failed:\n$output", 0, status)
            val environment = report.readText()
            listOf(
                "BASH_ENV", "ENV", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS",
            ).forEach { name ->
                assertTrue("$name reached the child:\n$environment", "$name=<absent>" in environment)
            }
            assertTrue(environment, "BASH_FUNC_KEYS=0" in environment)
            assertTrue(environment, "JAVA_HOME=$fixedJavaHome" in environment)
            assertTrue(environment, "ISSUE66_ACTIVE_JDK17_HOME=$fixedJavaHome" in environment)
            assertTrue(environment, "ISSUE66_DARWIN_TEMURIN_JDK17_HOME=$fixedJavaHome" in environment)
            assertTrue(environment, "ISSUE66_TEMURIN_JDK17_HOME=" in environment)
            assertTrue(environment, "ANDROID_HOME=$fixedAndroidHome" in environment)
            assertTrue(environment, "GRADLE_USER_HOME=$cleanGradleHome" in environment)
            assertTrue(environment, "HOME=$cleanHome" in environment)
            assertTrue(environment, "PATH=/usr/bin:/bin" in environment)
            assertTrue(environment, "ADB=/usr/bin/false" in environment)
            assertFalse("clean child imported an executable hook", Files.exists(marker))

            val weakened = functionSource.replace("/usr/bin/env -i", "/usr/bin/env")
            assertNotEquals("clean-environment mutation was a no-op", functionSource, weakened)
            val (weakenedStatus, weakenedOutput) = runLauncher(weakened)
            assertEquals("weakened launcher did not run its child:\n$weakenedOutput", 0, weakenedStatus)
            assertTrue(
                "mutation did not prove inherited executable hooks are live",
                Files.exists(marker),
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `dedicated Gradle home rejects user init injection surfaces`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val functionSource = shellFunction(runner, "validate_clean_gradle_user_home", "run_clean_gradle_command")
        val fixture = Files.createTempDirectory("issue66-host-gradle-home-").toRealPath()
        val gradleHome = fixture.resolve("gradle-user-home")
        val probe = fixture.resolve("probe.sh")
        try {
            Files.createDirectories(gradleHome.resolve("caches"))
            Files.createDirectories(gradleHome.resolve("wrapper"))
            Files.setPosixFilePermissions(
                gradleHome,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\n" +
                        "unset BASH_ENV ENV\n" +
                        "unset DEVELOPER_DIR SDKROOT TOOLCHAINS\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "validate_clean_gradle_user_home \"$gradleHome\"\n"
                    ).toByteArray(),
            )
            makeExecutable(probe)

            fun validate(): Int = ProcessBuilder("/bin/bash", "-p", probe.toString())
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    process.inputStream.bufferedReader().use { it.readText() }
                    process.waitFor()
                }

            assertEquals("ordinary Gradle caches were rejected", 0, validate())
            listOf("init.gradle", "init.gradle.kts", "gradle.properties").forEach { name ->
                val injected = gradleHome.resolve(name)
                Files.write(injected, "// injected\n".toByteArray())
                assertEquals("$name escaped the Gradle-home guard", 1, validate())
                Files.delete(injected)
            }
            Files.createDirectories(gradleHome.resolve("init.d"))
            assertEquals("init.d escaped the Gradle-home guard", 1, validate())
            gradleHome.resolve("init.d").toFile().deleteRecursively()

            val distributionInit = gradleHome.resolve(
                "wrapper/dists/gradle-9.3.1-bin/cache/gradle-9.3.1/init.d",
            )
            Files.createDirectories(distributionInit)
            Files.write(distributionInit.resolve("injected.gradle"), "// injected\n".toByteArray())
            assertEquals("distribution-level init.d escaped the Gradle-home guard", 1, validate())
            distributionInit.toFile().deleteRecursively()

            val attackerInit = fixture.resolve("attacker-init.d")
            Files.createDirectories(attackerInit)
            Files.write(attackerInit.resolve("injected.gradle"), "// injected\n".toByteArray())
            Files.createSymbolicLink(distributionInit, attackerInit)
            assertEquals(
                "symlinked distribution-level init.d escaped the Gradle-home guard",
                1,
                validate(),
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `child home is private fresh per run and removed before PASS`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val homeFunctions = shellFunction(
            runner,
            "run_clean_host_command",
            "validate_clean_gradle_user_home",
        )
        val fixture = Files.createTempDirectory("issue66-host-child-home-").toRealPath()
        val report = fixture.resolve("homes.txt")
        val probe = fixture.resolve("probe.sh")
        try {
            Files.setPosixFilePermissions(
                fixture,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\n" +
                        "unset BASH_ENV ENV DEVELOPER_DIR SDKROOT TOOLCHAINS\n" +
                        "set -euo pipefail\n" +
                        homeFunctions + "\n" +
                        "first_home=\"\$(create_ephemeral_child_home \"$fixture\")\"\n" +
                        "/usr/bin/python3 -I -c 'import os, stat, sys; " +
                        "value = os.lstat(sys.argv[1]); " +
                        "assert stat.S_ISDIR(value.st_mode); " +
                        "assert stat.S_IMODE(value.st_mode) == 0o700' \"\$first_home\"\n" +
                        "/bin/mkdir \"\$first_home/.gradle\"\n" +
                        "printf '%s\\n' 'source /attacker/startup.sh' > \"\$first_home/.bash_profile\"\n" +
                        "printf '%s\\n' 'org.gradle.jvmargs=-javaagent:/attacker.jar' > " +
                        "\"\$first_home/.gradle/gradle.properties\"\n" +
                        "remove_ephemeral_child_home \"$fixture\" \"\$first_home\"\n" +
                        "[[ ! -e \"\$first_home\" && ! -L \"\$first_home\" ]]\n" +
                        "second_home=\"\$(create_ephemeral_child_home \"$fixture\")\"\n" +
                        "[[ \"\$second_home\" != \"\$first_home\" ]]\n" +
                        "[[ ! -e \"\$second_home/.bash_profile\" ]]\n" +
                        "[[ ! -e \"\$second_home/.gradle/gradle.properties\" ]]\n" +
                        "/usr/bin/python3 -I -c 'import os, stat, sys; " +
                        "value = os.lstat(sys.argv[1]); " +
                        "assert stat.S_ISDIR(value.st_mode); " +
                        "assert stat.S_IMODE(value.st_mode) == 0o700' \"\$second_home\"\n" +
                        "printf '%s\\n%s\\n' \"\$first_home\" \"\$second_home\" > \"$report\"\n" +
                        "remove_ephemeral_child_home \"$fixture\" \"\$second_home\"\n" +
                        "[[ ! -e \"\$second_home\" && ! -L \"\$second_home\" ]]\n"
                    ).toByteArray(),
            )
            makeExecutable(probe)

            val process = ProcessBuilder("/bin/bash", "-p", probe.toString())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/usr/bin:/bin"
                    environment()["ADB"] = "/usr/bin/false"
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals("ephemeral child-home lifecycle failed:\n$output", 0, process.waitFor())
            val homes = Files.readAllLines(report).map(Paths::get)
            assertEquals("child-home lifecycle did not report both runs", 2, homes.size)
            assertNotEquals("two host-gate runs reused one child home", homes[0], homes[1])
            homes.forEach { home ->
                assertFalse("child home survived its cleanup: $home", Files.exists(home))
            }

            assertFalse(
                "zero-argument gate still uses a persistent child home",
                "host_child_home=\"\$receipt_dir/child-home\"" in runner,
            )
            assertTrue(
                "zero-argument gate does not create a per-run child home",
                "host_child_home=\"\$(create_ephemeral_child_home \"\$receipt_dir\")\"" in runner,
            )
            val trapCleanup = shellFunction(runner, "cleanup_host_gate_lock", "run_clean_host_command")
            assertTrue(
                "EXIT cleanup omits the per-run child home",
                "remove_ephemeral_child_home \"\$receipt_dir\" \"\$host_child_home\"" in trapCleanup,
            )
            val passReceipt = runner.indexOf("\\\"hostIntegration\\\":\\\"PASS\\\"")
            val explicitCleanup = runner.lastIndexOf(
                "remove_ephemeral_child_home \"\$receipt_dir\" \"\$host_child_home\"",
                passReceipt,
            )
            val removalConfirmation = runner.lastIndexOf(
                "[[ -e \"\$host_child_home\" || -L \"\$host_child_home\" ]]",
                passReceipt,
            )
            assertTrue(
                "PASS can publish before the per-run child home is confirmed absent",
                passReceipt >= 0 && explicitCleanup >= 0 && removalConfirmation > explicitCleanup &&
                    removalConfirmation < passReceipt,
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Gradle launcher rejects rather than repairs a child-weakened home`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val prepareSource = shellFunction(runner, "prepare_private_directory", "create_host_gate_lock")
        val hostCommandSource = shellFunction(
            runner,
            "run_clean_host_command",
            "validate_clean_gradle_user_home",
        )
        val validateSource = shellFunction(
            runner,
            "validate_clean_gradle_user_home",
            "run_clean_gradle_command",
        )
        val gradleCommandSource = shellFunctionBefore(
            runner,
            "run_clean_gradle_command",
            "if [[ \"\$#\" -eq 0 ]]",
        )
        val fixture = Files.createTempDirectory("issue66-host-gradle-post-").toRealPath()
        val gradleHome = fixture.resolve("gradle-user-home")
        val childHome = fixture.resolve("child-home")
        val child = fixture.resolve("weaken-gradle-home.sh")
        val probe = fixture.resolve("probe.sh")
        val poison = gradleHome.resolve("caches/fc2-poison")
        try {
            Files.createDirectories(gradleHome.resolve("caches"))
            Files.createDirectories(childHome)
            Files.setPosixFilePermissions(
                gradleHome,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            writeExecutable(
                child,
                "#!/bin/sh\n" +
                    "/bin/chmod 0777 \"\$GRADLE_USER_HOME\" || exit 90\n" +
                    "/usr/bin/touch \"\$GRADLE_USER_HOME/caches/fc2-poison\" || exit 91\n",
            )
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\n" +
                        "unset BASH_ENV ENV DEVELOPER_DIR SDKROOT TOOLCHAINS\n" +
                        "set -uo pipefail\n" +
                        "script_dir=\"$fixture\"\n" +
                        "host_gradle_user_home_relative=gradle-user-home\n" +
                        "host_gradle_user_home=\"$gradleHome\"\n" +
                        "host_child_home=\"$childHome\"\n" +
                        "host_java_home=\"${System.getProperty("java.home")}\"\n" +
                        "host_java_darwin_temurin_profile_home=\"${System.getProperty("java.home")}\"\n" +
                        "host_java_temurin_profile_home=\"\"\n" +
                        "host_android_home=\"$fixture/android-sdk\"\n" +
                        "verify_java_runtime_binding() { return 0; }\n" +
                        "validate_android_sdk_root() { printf '%s\\n' fixture-binding; }\n" +
                        "verify_android_sdk_binding() { return 0; }\n" +
                        prepareSource + "\n" +
                        hostCommandSource + "\n" +
                        validateSource + "\n" +
                        gradleCommandSource + "\n" +
                        "run_clean_gradle_command \"$child\"\n"
                    ).toByteArray(),
            )
            makeExecutable(probe)

            val process = ProcessBuilder("/bin/bash", "-p", probe.toString())
                .redirectErrorStream(true)
                .apply {
                    environment()["ADB"] = "/usr/bin/false"
                    environment().remove("DEVELOPER_DIR")
                    environment().remove("SDKROOT")
                    environment().remove("TOOLCHAINS")
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals("launcher repaired and accepted a child-weakened home:\n$output", 1, process.waitFor())
            assertTrue(
                "launcher omitted its typed post-run rejection:\n$output",
                "HOST_GATE_DEDICATED_GRADLE_HOME_CHANGED" in output,
            )
            assertTrue("post-run validation removed the child payload", Files.exists(poison))
            assertEquals(
                "post-run validation repaired the unsafe mode",
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
                Files.getPosixFilePermissions(gradleHome),
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `zero argument gate stages and revalidates one reviewed JDK profile before child execution`() {
        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        val stager = repo.resolve("scripts/stage-java17-runtime.py")
        val validator = repo.resolve("scripts/validate-java17-runtime.py")
        val profiles = repo.resolve("scripts/fixtures/issue66-java17-runtime-profiles.json")
        val build = repo.resolve("integration-tests/pr63-on-issue66/harness/build.gradle.kts").readText()

        assertTrue("reviewed Java profile validator is missing", Files.isRegularFile(validator))
        assertTrue("private Java runtime stager is missing", Files.isRegularFile(stager))
        assertTrue("reviewed Java profile catalog is missing", Files.isRegularFile(profiles))
        assertTrue(
            "runner does not bind the standalone Java validator and stager paths",
            "readonly java_profile_validator=\"\$repo_root/scripts/validate-java17-runtime.py\"" in runner &&
                "readonly java_runtime_stager=\"\$repo_root/scripts/stage-java17-runtime.py\"" in runner,
        )
        assertFalse(
            "runner retained a second inline Java validator instead of the reviewed implementation",
            "validate_java_17_runtime() {" in runner,
        )
        assertTrue(
            "runner does not create a known private JDK stage before invoking the stager",
            "host_java_stage_root=\"\$(create_ephemeral_java_runtime_root \"\$receipt_dir\")\"" in runner &&
                "java_stage_owned=1" in runner &&
                "stage_java_runtime \"\$requested_java_home\" \"\$host_java_stage_root\"" in runner,
        )
        assertTrue(
            "runner does not verify the staged binding around Gradle execution",
            Regex("verify_java_runtime_binding").findAll(runner).count() >= 3 &&
                "JAVA_HOME=\"\$host_java_home\"" in runner,
        )
        assertTrue(
            "runner does not clean the private JDK copy before PASS and from EXIT",
            Regex("remove_ephemeral_java_runtime_root").findAll(runner).count() >= 3 &&
                "HOST_GATE_EPHEMERAL_JAVA_RUNTIME_CLEANUP_FAILED" in runner,
        )
        listOf(
            "../../scripts/validate-java17-runtime.py",
            "../../scripts/stage-java17-runtime.py",
            "../../scripts/fixtures/issue66-java17-runtime-profiles.json",
            "../../scripts/test_validate_java17_runtime.py",
            "../../scripts/test_stage_java17_runtime.py",
            "../../scripts/test_validate_android_sdk_runtime.py",
        ).forEach { input ->
            assertTrue("host harness build inputs omit $input", input in build)
        }
    }

    @Test
    fun `zero argument gate executes every standalone runtime security suite on the staged JDK`() {
        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        val javaTests = repo.resolve("scripts/test_validate_java17_runtime.py").readText()
        val securityFunction = shellFunction(
            runner,
            "run_standalone_runtime_security_tests",
            "prepare_private_directory",
        )
        val cleanHostFunction = shellFunction(
            runner,
            "run_clean_host_command",
            "create_ephemeral_private_home",
        )
        val zeroArgumentStart = runner.indexOf(
            "if [[ \"\$#\" -eq 0 ]]; then",
            runner.indexOf("unset local_sdk_override"),
        )
        val firstExistingSelftest = runner.indexOf(
            "  run_clean_host_command /bin/bash -p \"\$repo_root/scripts/" +
                "selftest-issue66-moto-readonly-collector.sh\"",
            zeroArgumentStart,
        )
        val securityInvocation =
            "  if ! run_standalone_runtime_security_tests; then\n" +
                "    printf '%s\\n' 'HOST_GATE_STANDALONE_RUNTIME_SECURITY_TESTS_FAILED' >&2\n" +
                "    exit 1\n" +
                "  fi"
        val invocationStart = runner.indexOf(securityInvocation, zeroArgumentStart)

        listOf(
            "readonly java_profile_validator_test=\"\$repo_root/scripts/" +
                "test_validate_java17_runtime.py\"",
            "readonly java_runtime_stager_test=\"\$repo_root/scripts/" +
                "test_stage_java17_runtime.py\"",
            "readonly android_sdk_validator_test=\"\$repo_root/scripts/" +
                "test_validate_android_sdk_runtime.py\"",
        ).forEach { assignment ->
            assertEquals("standalone security-test path is not pinned exactly", 1, runner.windowed(assignment.length).count { it == assignment })
        }
        assertTrue(
            "standalone security suites do not fail closed before other zero-argument checks",
            zeroArgumentStart >= 0 && invocationStart > zeroArgumentStart &&
                firstExistingSelftest > invocationStart,
        )
        assertEquals(
            "standalone security-test invocation is optional or duplicated",
            1,
            runner.windowed(securityInvocation.length).count { it == securityInvocation },
        )
        assertTrue(
            "standalone security tests can be missing or replaced by symlinks",
            "[[ -f \"\$security_test\" && ! -L \"\$security_test\" ]] || return 1" in securityFunction,
        )
        assertTrue(
            "Darwin does not bind ACTIVE and Temurin to the staged reviewed runtime",
            "darwin-aarch64-eclipse-temurin-17.0.20.1+1)" in runner &&
                "host_java_darwin_temurin_profile_home=\"\$host_java_home\"" in runner &&
                "ISSUE66_ACTIVE_JDK17_HOME=\"\$host_java_home\"" in cleanHostFunction &&
                "ISSUE66_DARWIN_TEMURIN_JDK17_HOME=\"\$host_java_darwin_temurin_profile_home\"" in
                cleanHostFunction,
        )
        assertTrue(
            "Linux does not bind ACTIVE and TEMURIN to the staged reviewed runtime",
            "linux-x86_64-eclipse-temurin-17.0.20.1+1)" in runner &&
                "host_java_temurin_profile_home=\"\$host_java_home\"" in runner &&
                "ISSUE66_TEMURIN_JDK17_HOME=\"\$host_java_temurin_profile_home\"" in
                cleanHostFunction,
        )
        assertTrue(
            "the active JDK test is not executed through the clean launcher and fixed Python",
            "run_clean_host_command /usr/bin/python3 -I \"\$java_profile_validator_test\"" in
                securityFunction,
        )
        listOf(
            "run_clean_host_command /usr/bin/python3 -I \"\$java_runtime_stager_test\"",
            "run_clean_host_command /usr/bin/python3 -I \"\$android_sdk_validator_test\"",
        ).forEach { command ->
            assertTrue("standalone security test is not executed by fixed isolated Python: $command", command in securityFunction)
        }
        assertTrue(
            "standalone security tests are not bounded by the reviewed JDK and SDK bindings",
            Regex("verify_java_runtime_binding").findAll(securityFunction).count() == 2 &&
                Regex("verify_android_sdk_binding").findAll(securityFunction).count() == 2,
        )
        val activeProfileTest = javaTests.substring(
            javaTests.indexOf("    def test_checked_profile_trees_reproduce_the_committed_digests"),
            javaTests.indexOf("    def test_real_runtime_binding", javaTests.indexOf("    def test_checked_profile_trees_reproduce_the_committed_digests")),
        )
        assertFalse(
            "single-platform execution silently skips its applicable real JDK tree",
            "skipTest" in activeProfileTest,
        )
        assertTrue(
            "single-platform execution does not require its matching real JDK profile",
            "required_environment_by_platform" in activeProfileTest &&
                "configured.get(required_environment)" in activeProfileTest,
        )

        val deletionMutant = runner.replace(securityInvocation, "  : # deleted security suites")
        assertFalse(
            "deleting the standalone suites no longer demonstrates the missing-gate regression",
            securityInvocation in deletionMutant,
        )
    }

    @Test
    fun `Gradle attestations and PASS receipt bind the staged JDK profile and all attestation hashes`() {
        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        val attestation = repo.resolve(
            "integration-tests/pr63-on-issue66/host-gate-test-attestation.init.gradle",
        ).readText()

        listOf(
            "jdkHome",
            "jdkProfileId",
            "javaVendor",
            "javaVmVendor",
            "jdkRuntimeVersion",
            "jdkTreeSha256",
        ).forEach { field ->
            assertTrue("Gradle attestation omits $field", "\"$field=" in attestation)
            assertTrue("host attestation parser omits $field", "\"$field\"," in runner)
        }
        listOf("jdkProfileId", "jdkRuntimeVersion", "jdkTreeSha256").forEach { field ->
            assertTrue("PASS receipt omits $field", "\\\"$field\\\"" in runner)
        }
        assertTrue(
            "Gradle invocation does not pass the reviewed JDK profile into the init script",
            "-Pissue66JdkHome=\$host_java_home" in runner &&
            "-Pissue66JdkProfileId=\$host_java_profile_id" in runner &&
                "-Pissue66JavaVendor=\$host_java_vendor" in runner &&
                "-Pissue66JavaVmVendor=\$host_java_vm_vendor" in runner &&
                "-Pissue66JdkRuntimeVersion=\$host_java_runtime_version" in runner &&
                "-Pissue66JdkTreeSha256=\$host_java_tree_sha256" in runner,
        )
        assertTrue(
            "Gradle init script does not bind both VM and Test launcher to the staged home",
            "System.getProperty(\"java.home\")" in attestation &&
                "metadata.installationPath.asFile" in attestation &&
                ".toPath().toRealPath()" in attestation,
        )
        listOf(
            "-Dorg.gradle.java.installations.auto-detect=false",
            "-Dorg.gradle.java.installations.auto-download=false",
            "-Dorg.gradle.java.installations.paths=\$host_java_home",
            "-Pkotlin.compiler.execution.strategy=in-process",
        ).forEach { setting ->
            assertTrue("Gradle runtime discovery is not pinned by $setting", setting in runner)
        }
        listOf(
            "gradleAttestationAutoSha256",
            "gradleAttestationQwySha256",
            "gradleAttestationHarnessSha256",
        ).forEach { field ->
            assertTrue("PASS receipt omits $field", "\\\"$field\\\"" in runner)
        }
        assertTrue("host receipt schema did not advance with its exact key set", "\\\"schemaVersion\\\":4" in runner)
    }

    @Test
    fun `host integration workflow pins action code and the reviewed Temurin release`() {
        val workflow = findRepoRoot().resolve(".github/workflows/android-a-plus.yml").readText()
        val hostJobStart = workflow.indexOf("  auto-qwy-host-integration:")
        val nextJob = workflow.indexOf("\n  install-guards:", hostJobStart)
        check(hostJobStart >= 0 && nextJob > hostJobStart) { "host integration workflow job moved" }
        val hostJob = workflow.substring(hostJobStart, nextJob)

        assertTrue(
            "host integration checkout action is not pinned to reviewed code",
            "actions/checkout@11d5960a326750d5838078e36cf38b85af677262" in hostJob,
        )
        assertTrue(
            "host integration setup-java action is not pinned to reviewed code",
            "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961" in hostJob,
        )
        assertTrue(
            "host integration artifact uploader is not pinned to reviewed code",
            "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02" in hostJob,
        )
        listOf(
            "distribution: temurin",
            "java-version: '17.0.20+101'",
            "architecture: x64",
            "check-latest: false",
            "verify-signature: true",
        ).forEach { setting ->
            assertTrue("host integration setup-java omits exact setting $setting", setting in hostJob)
        }
    }

    @Test
    fun `host integration runs standalone runtime security suites on setup Java`() {
        val workflow = findRepoRoot().resolve(".github/workflows/android-a-plus.yml").readText()
        val hostJobStart = workflow.indexOf("  auto-qwy-host-integration:")
        val nextJob = workflow.indexOf("\n  install-guards:", hostJobStart)
        check(hostJobStart >= 0 && nextJob > hostJobStart) { "host integration workflow job moved" }
        val hostJob = workflow.substring(hostJobStart, nextJob)
        val freezeStart = hostJob.indexOf("      - name: freeze preinstalled Android SDK permissions")
        val securityStart = hostJob.indexOf("      - name: standalone runtime security tests")
        val repositorySetup = hostJob.indexOf(
            "      - name: repository wrappers and host runner are executable",
        )
        check(securityStart >= 0) { "standalone runtime security-test step is missing" }
        val securityEnd = hostJob.indexOf("\n      - name:", securityStart + 1)
        check(securityEnd > securityStart) { "standalone runtime security-test step is unbounded" }
        val securityStep = hostJob.substring(securityStart, securityEnd)

        assertTrue(
            "standalone runtime security tests do not run after SDK freeze and before the host gate",
            freezeStart >= 0 && securityStart > freezeStart && repositorySetup > securityStart,
        )
        assertTrue(
            "standalone runtime security tests can import shell startup state",
            "        shell: /bin/bash --noprofile --norc -p -euo pipefail {0}" in securityStep,
        )
        assertTrue(
            "CI does not bind setup-java as both ACTIVE and TEMURIN",
            "ISSUE66_ACTIVE_JDK17_HOME=\"\$setup_java_home\"" in securityStep &&
                "ISSUE66_TEMURIN_JDK17_HOME=\"\$setup_java_home\"" in securityStep,
        )
        listOf(
            "scripts/test_validate_java17_runtime.py",
            "scripts/test_stage_java17_runtime.py",
            "scripts/test_validate_android_sdk_runtime.py",
        ).forEach { test ->
            assertEquals(
                "CI does not execute $test exactly once",
                1,
                securityStep.lineSequence().count { "/usr/bin/python3 -I $test" in it },
            )
        }
        assertEquals(
            "CI standalone suites gained a non-isolated Python entrypoint",
            3,
            securityStep.lineSequence().count { "/usr/bin/python3 -I scripts/" in it },
        )
        val deletionMutant = hostJob.replace(securityStep, "      # deleted standalone security suites")
        assertFalse(
            "deleting the CI standalone suites no longer demonstrates the missing-gate regression",
            "      - name: standalone runtime security tests" in deletionMutant,
        )
    }

    @Test
    fun `host integration freezes the exact Ubuntu Android SDK before repository execution`() {
        val workflow = findRepoRoot().resolve(".github/workflows/android-a-plus.yml").readText()
        val hostJobStart = workflow.indexOf("  auto-qwy-host-integration:")
        val nextJob = workflow.indexOf("\n  install-guards:", hostJobStart)
        check(hostJobStart >= 0 && nextJob > hostJobStart) { "host integration workflow job moved" }
        val hostJob = workflow.substring(hostJobStart, nextJob)
        assertEquals(
            "host integration runner image must be uniquely pinned with its exact SDK layout",
            listOf("    runs-on: ubuntu-24.04"),
            hostJob.lineSequence().filter { it.trimStart().startsWith("runs-on:") }.toList(),
        )
        val setupJava = hostJob.indexOf(
            "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
        )
        val freezeStart = hostJob.indexOf("      - name: freeze preinstalled Android SDK permissions")
        val standaloneSecurityTests = hostJob.indexOf(
            "      - name: standalone runtime security tests",
        )
        val repositoryExecution = hostJob.indexOf(
            "      - name: repository wrappers and host runner are executable",
        )
        val combinedGate = hostJob.indexOf("      - name: combined host gate")
        val stepsStart = hostJob.indexOf("    steps:\n")
        check(stepsStart >= 0) { "host integration workflow steps moved" }
        val expectedPreFreezeSteps =
            "      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262\n" +
                "\n" +
                "      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961\n" +
                "        with:\n" +
                "          distribution: temurin\n" +
                "          java-version: '17.0.20+101'\n" +
                "          architecture: x64\n" +
                "          check-latest: false\n" +
                "          verify-signature: true\n" +
                "\n"
        fun preFreezeSteps(job: String): String {
            val jobStepsStart = job.indexOf("    steps:\n")
            val jobFreezeStart = job.indexOf(
                "      - name: freeze preinstalled Android SDK permissions",
            )
            check(jobStepsStart >= 0 && jobFreezeStart > jobStepsStart) {
                "host integration pre-freeze step boundary moved"
            }
            return job.substring(jobStepsStart + "    steps:\n".length, jobFreezeStart)
        }

        assertTrue(
            "Android SDK permission freeze must run after actions setup and before any repository command",
            setupJava >= 0 && freezeStart > setupJava && standaloneSecurityTests > freezeStart &&
                repositoryExecution > standaloneSecurityTests && combinedGate > repositoryExecution,
        )
        assertEquals(
            "repository code or an unreviewed action can execute before the Android SDK freeze",
            expectedPreFreezeSteps,
            preFreezeSteps(hostJob),
        )
        val preFreezeExecutionMutant = hostJob.replaceFirst(
            "    steps:\n",
            "    steps:\n      - run: ./scripts/pre-freeze-mutation.sh\n\n",
        )
        assertTrue(
            "pre-freeze mutation no longer demonstrates the old index-only false green",
            preFreezeExecutionMutant.indexOf(
                "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
            ) < preFreezeExecutionMutant.indexOf(
                "      - name: freeze preinstalled Android SDK permissions",
            ),
        )
        assertNotEquals(
            "exact pre-freeze allowlist accepted a repository command mutation",
            expectedPreFreezeSteps,
            preFreezeSteps(preFreezeExecutionMutant),
        )
        val freezeEnd = hostJob.indexOf("\n      - name:", freezeStart + 1)
        check(freezeEnd > freezeStart) { "Android SDK permission freeze step has no bounded end" }
        val jdkNormalizeStart = hostJob.indexOf(
            "      - name: normalize reviewed JDK cache containers",
        )
        val jdkNormalizeEnd = hostJob.indexOf("\n      - name:", jdkNormalizeStart + 1)
        check(jdkNormalizeStart >= 0 && jdkNormalizeEnd > jdkNormalizeStart) {
            "reviewed JDK cache normalization step has no bounded extent"
        }
        assertEquals(
            "an unreviewed step can run between the SDK freeze and JDK cache normalization",
            freezeEnd + 1,
            jdkNormalizeStart,
        )
        assertEquals(
            "an unreviewed step can run between JDK cache normalization and the first repository command",
            jdkNormalizeEnd + 1,
            standaloneSecurityTests,
        )
        // Bind the whole independently reviewed step, including shell/metadata.
        // Its actual command semantics are exercised by the seven-layout Python regression.
        val jdkNormalizeStep = hostJob.substring(jdkNormalizeStart, jdkNormalizeEnd)
        val reviewedJdkNormalizeSha256 =
            "8beb1c536150e94794fc5edfeadebc0073688bb510ce51592c904a9950e5ba71"
        fun stepSha256(step: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(step.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(
            "JDK cache normalization gained an unreviewed command or bypass metadata",
            reviewedJdkNormalizeSha256,
            stepSha256(jdkNormalizeStep),
        )
        assertNotEquals(
            "JDK cache normalization accepted a conditional bypass",
            reviewedJdkNormalizeSha256,
            stepSha256(jdkNormalizeStep.replace("        shell:", "        if: false\n        shell:")),
        )
        val freezeStep = hostJob.substring(freezeStart, freezeEnd)

        val runMarker = "        run: |\n"
        val runStart = freezeStep.indexOf(runMarker)
        check(runStart >= 0) { "Android SDK permission freeze step omits its run block" }
        assertEquals(
            "Android SDK permission freeze metadata can bypass its fail-closed command",
            "      - name: freeze preinstalled Android SDK permissions\n" +
                "        shell: /bin/bash --noprofile --norc -p -euo pipefail {0}\n",
            freezeStep.substring(0, runStart),
        )
        val runScript = freezeStep.substring(runStart + runMarker.length)
            .lineSequence()
            .joinToString("\n") { line ->
                check(line.isBlank() || line.startsWith("          ")) {
                    "Android SDK permission freeze gained an unbounded YAML line: $line"
                }
                line.removePrefix("          ")
            } + "\n"

        assertEquals(
            "Android SDK permission freeze command contract changed",
            listOf(
                "readonly expected_sdk=/usr/local/lib/android/sdk",
                "if [[ \"\${ANDROID_HOME-}\" != \"\$expected_sdk\" ||",
                "      \"\${ANDROID_SDK_ROOT-}\" != \"\$expected_sdk\" ]]; then",
                "  printf '%s\\n' 'HOST_INTEGRATION_ANDROID_SDK_TARGET_MISMATCH' >&2",
                "  exit 1",
                "fi",
                "if [[ ! -d \"\$expected_sdk\" || -L \"\$expected_sdk\" ]]; then",
                "  printf '%s\\n' 'HOST_INTEGRATION_ANDROID_SDK_TYPE_INVALID' >&2",
                "  exit 1",
                "fi",
                "physical_sdk=\$(/usr/bin/realpath --canonicalize-existing -- \"\$expected_sdk\")",
                "readonly physical_sdk",
                "if [[ \"\$physical_sdk\" != \"\$expected_sdk\" ]]; then",
                "  printf '%s\\n' 'HOST_INTEGRATION_ANDROID_SDK_PHYSICAL_TARGET_MISMATCH' >&2",
                "  exit 1",
                "fi",
                "/usr/bin/sudo -- /usr/bin/chown --recursive --no-dereference root:root -- \"\$expected_sdk\"",
                "/usr/bin/sudo -- /usr/bin/chmod --recursive a-w -- \"\$expected_sdk\"",
                "unsafe_sdk_entry=\$(",
                "  /usr/bin/find -P \"\$expected_sdk\" \\",
                "    \\( \\",
                "      \\( -type l \\( ! -user root -o ! -group root \\) \\) \\",
                "      -o \\",
                "      \\( ! -type l \\( ! -user root -o ! -group root -o -perm /0222 \\) \\) \\",
                "    \\) \\",
                "    -print -quit",
                ")",
                "readonly unsafe_sdk_entry",
                "if [[ -n \"\$unsafe_sdk_entry\" ]]; then",
                "  printf '%s\\n' 'HOST_INTEGRATION_ANDROID_SDK_PERMISSION_FREEZE_FAILED' >&2",
                "  exit 1",
                "fi",
            ),
            runScript.lineSequence().filter(String::isNotBlank).toList(),
        )
        assertFalse(
            "recursive ownership or mode changes may target an inherited SDK variable",
            Regex("(?m)^/usr/bin/sudo .*ANDROID_(HOME|SDK_ROOT)").containsMatchIn(runScript),
        )

        val fixture = Files.createTempDirectory("issue66-workflow-sdk-freeze-").toRealPath()
        val probe = fixture.resolve("probe.sh")
        val privilegedMarker = fixture.resolve("privileged-phase-reached")
        val bashEnvPoison = fixture.resolve("bash-env-poison.sh")
        val bashEnvMarker = fixture.resolve("bash-env-ran")
        val functionMarker = fixture.resolve("inherited-readonly-ran")
        try {
            val chownCommand =
                "/usr/bin/sudo -- /usr/bin/chown --recursive --no-dereference root:root -- \"\$expected_sdk\""
            val chmodCommand =
                "/usr/bin/sudo -- /usr/bin/chmod --recursive a-w -- \"\$expected_sdk\""
            val probeScript = runScript
                .replace(chownCommand, "/usr/bin/touch \"\$SDK_FREEZE_PROBE_MARKER\"")
                .replace(chmodCommand, "/usr/bin/touch \"\$SDK_FREEZE_PROBE_MARKER\"")
            assertNotEquals("dynamic probe did not neutralize privileged commands", runScript, probeScript)
            writeExecutable(probe, "#!/bin/bash\n$probeScript")
            Files.write(bashEnvPoison, "/usr/bin/touch \"$bashEnvMarker\"\n".toByteArray())

            fun runProbe(privileged: Boolean): Pair<Int, String> {
                Files.deleteIfExists(privilegedMarker)
                Files.deleteIfExists(bashEnvMarker)
                Files.deleteIfExists(functionMarker)
                val command = mutableListOf("/bin/bash", "--noprofile", "--norc")
                if (privileged) command += "-p"
                command += listOf("-euo", "pipefail", probe.toString())
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment()["PATH"] = "/usr/bin:/bin"
                        environment()["ANDROID_HOME"] = "/tmp"
                        environment()["ANDROID_SDK_ROOT"] = "/tmp"
                        environment()["SDK_FREEZE_PROBE_MARKER"] = privilegedMarker.toString()
                        environment()["BASH_ENV"] = bashEnvPoison.toString()
                        environment()["BASH_FUNC_readonly%%"] =
                            "() { /usr/bin/touch \"$functionMarker\"; " +
                                "builtin readonly expected_sdk=/tmp; }"
                    }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                return process.waitFor() to output
            }

            val (status, output) = runProbe(privileged = true)
            assertEquals("mismatched Android SDK target was not rejected:\n$output", 1, status)
            assertTrue(
                "mismatched Android SDK target omitted its typed rejection:\n$output",
                "HOST_INTEGRATION_ANDROID_SDK_TARGET_MISMATCH" in output,
            )
            assertFalse("privileged Bash executed BASH_ENV", Files.exists(bashEnvMarker))
            assertFalse("privileged Bash imported the readonly function", Files.exists(functionMarker))
            assertFalse(
                "permission mutation phase ran before exact-target validation",
                Files.exists(privilegedMarker),
            )

            val (_, weakenedOutput) = runProbe(privileged = false)
            assertTrue(
                "non-privileged mutation no longer proves BASH_ENV is executable:\n$weakenedOutput",
                Files.exists(bashEnvMarker),
            )
            assertTrue(
                "non-privileged mutation no longer proves readonly can be imported:\n$weakenedOutput",
                Files.exists(functionMarker),
            )
        } finally {
            fixture.toFile().deleteRecursively()
        }
    }

    @Test
    fun `zero argument host gate routes every child through the clean environment`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val inspection = runner.indexOf("inspect_inherited_environment() {")
        val inspectionEnd = runner.indexOf(
            "\n}\n\ninherited_environment_status=0",
            inspection,
        )
        val inspectionCall = runner.indexOf(
            "inspect_inherited_environment || inherited_environment_status=\$?",
            inspectionEnd,
        )
        val scriptDir = runner.indexOf("script_dir=")
        assertEquals(
            "runner performs an external action before inherited-function inspection is available",
            "#!/bin/bash -p\n" +
                "unset BASH_ENV ENV\n" +
                "unset DEVELOPER_DIR SDKROOT TOOLCHAINS\n" +
                "set -euo pipefail\n" +
                "umask 077\n" +
                "PATH=/usr/bin:/bin\n" +
                "export PATH\n\n",
            runner.substring(0, inspection),
        )
        assertTrue(
            "inherited environment must be inspected by fixed isolated Python before path lookup",
            inspection >= 0 && inspectionEnd > inspection && inspectionCall > inspectionEnd &&
                inspectionCall < scriptDir &&
                "/usr/bin/python3 -I" in runner &&
                "HOST_GATE_UNSAFE_INHERITED_BASH_FUNCTION_ENV" in runner,
        )
        assertEquals(
            "runner executes another top-level statement before inherited environment inspection",
            "inherited_environment_status=0",
            runner.substring(inspectionEnd + "\n}\n\n".length, inspectionCall).trim(),
        )
        assertTrue(
            "zero-argument selftests bypass the clean child launcher",
            "run_clean_host_command /bin/bash -p \"\$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh\"" in runner &&
                "run_clean_host_command /bin/bash -p \"\$repo_root/scripts/selftest-issue66-services-compatibility.sh\"" in runner,
        )
        assertEquals(
            "zero-argument Gradle invocations bypass the guarded clean launcher",
            3,
            Regex("(?m)^  run_attested_gradle_test ").findAll(runner).count(),
        )
        assertTrue(
            "zero-argument Gradle builds may reconnect to an ambient daemon",
            Regex("(?m)^    --no-daemon$").findAll(runner).count() == 3,
        )
        assertTrue(
            "runner does not assign fresh per-run child and Gradle homes",
            "host_gradle_user_home=\"\$receipt_dir/gradle-user-home\"" !in runner &&
                "create_ephemeral_gradle_home" in runner &&
                "remove_ephemeral_gradle_home" in runner &&
                "host_child_home=\"\$receipt_dir/child-home\"" !in runner &&
                "create_ephemeral_child_home" in runner &&
                "remove_ephemeral_child_home" in runner,
        )
        assertTrue(
            "Gradle commands must validate their clean home before and after execution",
            Regex("validate_clean_gradle_user_home").findAll(runner).count() >= 3,
        )

        listOf(
            findRepoRoot().resolve("apps/cellrebel-auto/gradle/wrapper/gradle-wrapper.properties"),
            findRepoRoot().resolve("apps/qianwangyou/gradle/wrapper/gradle-wrapper.properties"),
            findRepoRoot().resolve("acceptance/gradle/wrapper/gradle-wrapper.properties"),
        ).forEach { wrapperProperties ->
            assertTrue(
                "${wrapperProperties.parent.parent.parent.fileName} wrapper distribution is not SHA-256 pinned",
                "distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06" in
                    wrapperProperties.readText(),
            )
        }
    }

    @Test
    fun `zero argument host Gradle gates require fresh in-VM test attestations`() {
        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        val attestationScript = repo.resolve(
            "integration-tests/pr63-on-issue66/host-gate-test-attestation.init.gradle",
        )

        assertTrue("reviewed Gradle attestation init script is missing", Files.isRegularFile(attestationScript))
        val attestationSource = attestationScript.readText()
        val exactTaskCountGuard = Regex("matchingTasks\\.size\\(\\)\\s*!=\\s*1")
            .find(attestationSource)?.range?.first ?: -1
        val indexedTaskSelection = Regex("matchingTasks\\s*\\[\\s*0\\s*]")
            .find(attestationSource)?.range?.first ?: -1
        assertTrue(
            "Gradle attestation must prove exactly one matching task before selecting index zero",
            exactTaskCountGuard >= 0 && indexedTaskSelection > exactTaskCountGuard,
        )
        assertFalse(
            "Gradle attestation uses Kotlin single() on a Groovy ArrayList",
            Regex("matchingTasks\\s*\\.\\s*single\\s*\\(").containsMatchIn(attestationSource),
        )
        assertTrue(
            "Gradle attestation does not bind the Gradle VM to Java 17",
            "Runtime.version().feature() != 17" in attestationSource,
        )
        assertTrue(
            "Gradle attestation permits task-graph-only execution with every task action disabled",
            "gradle.startParameter.taskGraph" in attestationSource,
        )
        assertTrue(
            "Gradle attestation does not bind the Test worker launcher to Java 17",
            "javaLauncher.get().metadata.languageVersion.asInt()" in attestationSource,
        )
        assertTrue(
            "Gradle attestation does not require real test events and required classes",
            "testCount" in attestationSource &&
                "requiredClasses" in attestationSource &&
                "afterTest" in attestationSource,
        )
        assertTrue(
            "Gradle attestation accepts a same-simple-name class from another package",
            "return !observedClasses.contains(requiredClass)" in attestationSource,
        )
        val groovyFqcnPattern =
            "'[A-Za-z_\$][A-Za-z0-9_\$]*(?:\\\\.[A-Za-z_\$][A-Za-z0-9_\$]*)+'"
        assertEquals(
            "Gradle attestation does not require observed and required names to be FQCNs",
            1,
            Regex(Regex.escape(groovyFqcnPattern)).findAll(attestationSource).count(),
        )
        assertFalse(
            "Gradle attestation still has a simple-name fallback",
            "simpleName" in attestationSource || "lastIndexOf('.')" in attestationSource,
        )
        assertTrue(
            "Gradle attestation can overwrite or replay an older marker",
            "StandardOpenOption.CREATE_NEW" in attestationSource,
        )
        assertEquals(
            "host Gradle gates do not all pass through attestation verification",
            3,
            Regex("(?m)^  run_attested_gradle_test ").findAll(runner).count(),
        )
        assertTrue(
            "host runner does not verify nofollow per-run attestation bytes",
            "verify_gradle_test_attestation" in runner &&
                "HOST_GATE_GRADLE_ATTESTATION_INVALID" in runner,
        )
        listOf(
            "com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest",
            "name.caiyao.fakegps.hook.oracle.Android15OracleHookPlanTest",
            "name.caiyao.fakegps.hook.oracle.SystemServerOracleWiringGuardTest",
            "name.caiyao.fakegps.integration.v1.AuthoritativeOracleProductionGuardTest",
            "name.caiyao.fakegps.integration.v1.BinderAuthoritativeContinuitySourceTest",
            "name.caiyao.fakegps.oracle.OracleBundleCodecTest",
            "name.caiyao.fakegps.integration.v1.AuthoritativeAdvanceProviderTest",
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HarnessBoundaryGuardTest",
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HostRunnerEnvironmentGuardTest",
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HostReceiptModeGuardTest",
            "io.github.terryyyc.fakexxx.integration.pr63issue66.HostEphemeralCleanupGuardTest",
        ).forEach { requiredClass ->
            assertTrue("host attestation omits required class $requiredClass", requiredClass in runner)
        }
        val verifierFunction = shellFunction(
            runner,
            "verify_gradle_test_attestation",
            "run_attested_gradle_test",
        )
        val pythonFqcnPattern =
            "r\"[A-Za-z_\$][A-Za-z0-9_\$]*(?:\\.[A-Za-z_\$][A-Za-z0-9_\$]*)+\""
        assertEquals(
            "host attestation verifier must apply one FQCN grammar to requirements and evidence",
            2,
            Regex(Regex.escape(pythonFqcnPattern)).findAll(verifierFunction).count(),
        )
        assertTrue(
            "host attestation verifier does not require exact FQCN membership",
            "if required not in classes:" in verifierFunction,
        )
        assertFalse(
            "host attestation verifier still accepts suffix matches",
            "observed.endswith(\".\" + required)" in verifierFunction,
        )
    }

    private fun writeExecutable(path: Path, contents: String) {
        Files.createDirectories(path.parent)
        Files.write(path, contents.toByteArray())
        makeExecutable(path)
    }

    private fun makeExecutable(path: Path) {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
        )
    }

    private fun shellFunction(source: String, name: String, nextName: String): String {
        val start = source.indexOf("$name() {")
        val end = source.indexOf("\n}\n\n$nextName() {", start)
        check(start >= 0 && end > start) { "runner function $name is missing or moved" }
        return source.substring(start, end + 2)
    }

    private fun shellFunctionBefore(source: String, name: String, nextStatement: String): String {
        val start = source.indexOf("$name() {")
        val end = source.indexOf("\n}\n\n$nextStatement", start)
        check(start >= 0 && end > start) { "runner function $name is missing or moved" }
        return source.substring(start, end + 2)
    }

    private fun requireJava17Home(): Path {
        check(System.getProperty("java.specification.version") == "17") {
            "aggregate fixture requires the same real Java 17 runtime as the host gate"
        }
        return Paths.get(System.getProperty("java.home")).toRealPath()
    }

    private fun requireAndroidSdk35(): Path {
        val configured = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        check(!configured.isNullOrBlank()) {
            "aggregate fixture requires an explicit ANDROID_HOME or ANDROID_SDK_ROOT"
        }
        val sdk = Paths.get(configured).toRealPath()
        check(Files.isRegularFile(sdk.resolve("platforms/android-35/android.jar"))) {
            "aggregate fixture requires a real Android SDK 35: $sdk"
        }
        return sdk
    }

    private fun findRepoRoot(): Path {
        var candidate = Paths.get("").toAbsolutePath().normalize()
        while (candidate.parent != null) {
            if (candidate.resolve("apps/cellrebel-auto").isDirectory() &&
                candidate.resolve("apps/qianwangyou").isDirectory()
            ) return candidate
            candidate = candidate.parent
        }
        error("could not locate fakexxx repository root")
    }

    companion object {
        private const val PINNED_DEVELOPER_SELECTOR_CLEAR =
            "unset DEVELOPER_DIR SDKROOT TOOLCHAINS"
    }
}

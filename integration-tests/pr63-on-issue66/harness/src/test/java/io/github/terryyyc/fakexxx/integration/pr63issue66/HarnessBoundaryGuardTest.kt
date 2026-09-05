package io.github.terryyyc.fakexxx.integration.pr63issue66

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HarnessBoundaryGuardTest {

    @Test
    fun `integration harness mounts only the canonical test support and one contract`() {
        val repo = findRepoRoot()
        val script = repo.resolve("integration-tests/pr63-on-issue66/harness/build.gradle.kts").readText()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        val service = repo.resolve(AUTOMATION_SERVICE).readText()
        val qwyService = repo.resolve(QWY_ENVIRONMENT_SERVICE).readText()
        val durableBridge = repo.resolve(DURABLE_BRIDGE).readText()

        assertEquals(emptyList<String>(), violations(script))
        assertEquals(emptyList<String>(), runnerViolations(runner))
        assertEquals(emptyList<String>(), serviceRouteViolations(service))
        assertEquals(emptyList<String>(), qwyServiceRouteViolations(qwyService))
        assertEquals(emptyList<String>(), durableBridgeViolations(durableBridge))

        val productionBridgeReferences = Files.walk(repo.resolve("apps/cellrebel-auto/app/src/main")).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                .filter { it.readText().contains("AutoIntegrationBridge") }
                .toList()
        }
        assertEquals(emptyList<Path>(), productionBridgeReferences)
    }

    @Test
    fun `boundary checker rejects every prohibited harness mutation`() {
        val repo = findRepoRoot()
        val original = repo.resolve("integration-tests/pr63-on-issue66/harness/build.gradle.kts").readText()
        assertEquals("Mutation baseline must be valid", emptyList<String>(), violations(original))

        val mutations = listOf(
            original.replace(FRIEND_SCOPE, "if (name.contains(\"UnitTest\")) {"),
            original.replace(QWY_FAKES, "../../../apps/qianwangyou/app/src/test/java"),
            original.replace(QWY_HARNESS, "../../../apps/qianwangyou/app/src/test/java"),
            original.replace(
                QWY_HARNESS,
                "$QWY_HARNESS\n    \"../../../apps/qianwangyou/app/src/test/java/extra/Third.kt\","),
            original.replace(
                "source(qwyCanonicalSupport)",
                "source(qwyCanonicalSupport)\n        source(file(\"Third.kt\"))",
            ),
            original.replace(
                "defaultConfig { minSdk = 26 }",
                "defaultConfig { minSdk = 26 }\n    sourceSets.getByName(\"test\").java.srcDir(\"third\")",
            ),
            original.replace(FRIEND_ARTIFACT, "file(\"../../../apps/qianwangyou/app/build/intermediates/classes.jar\")"),
            original.replace(AUTO_CONTRACT_EXCLUDE, ""),
            original.replace(QWY_CONTRACT_EXCLUDE, ""),
            original.replace(AUTO_RELEASE_ATTRIBUTE, ""),
            original.replace(QWY_DEBUG_ATTRIBUTE, ""),
            original.replace(RESOLVED_BOUNDARY_TASK, "tasks.register(\"unverifiedBoundary\")"),
            original.replace(
                RESOLVED_DIRECT_BLOCK,
                RESOLVED_DIRECT_BLOCK.replace(
                    "            \"project :qianwangyou:app\",",
                    "            \"project :qianwangyou:app\",\n            \"project :third:first-party\",",
                ),
            ),
            original.replace(TEST_DEPENDS_ON_BOUNDARY, "dependsOn(\"nothing\")"),
            original.replace(AUTO_DEPENDENCY, "$AUTO_DEPENDENCY\n        isTransitive = false"),
            original.replace(
                "testImplementation(\"junit:junit:4.13.2\")",
                "testImplementation(\"junit:junit:4.13.2\")\n    testImplementation(\"bad:third:1\")",
            ),
            original.replace(
                "testImplementation(\"junit:junit:4.13.2\")",
                "testImplementation(\"junit:junit:4.13.2\")\n    testRuntimeOnly(\"bad:third:1\")",
            ),
            original.replace(
                "testImplementation(\"junit:junit:4.13.2\")",
                "testImplementation(\"junit:junit:4.13.2\")\n    implementation(\"bad:third:1\")",
            ),
        )

        mutations.forEachIndexed { index, mutated ->
            assertTrue("mutation $index is a no-op", mutated != original)
            assertTrue("mutation $index escaped the integration boundary", violations(mutated).isNotEmpty())
        }
    }

    @Test
    fun `production routing and pinned runner guards reject every route mutation`() {
        val repo = findRepoRoot()
        val service = repo.resolve(AUTOMATION_SERVICE).readText()
        val qwyService = repo.resolve(QWY_ENVIRONMENT_SERVICE).readText()
        val durableBridge = repo.resolve(DURABLE_BRIDGE).readText()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        assertEquals(emptyList<String>(), serviceRouteViolations(service))
        assertEquals(emptyList<String>(), qwyServiceRouteViolations(qwyService))
        assertEquals(emptyList<String>(), durableBridgeViolations(durableBridge))
        assertEquals(emptyList<String>(), runnerViolations(runner))

        listOf(
            service.replace(SERVICE_PLAN_READ, "planRepository.getPlanFromCurrentBuild(planId)"),
            service.replace(SERVICE_DURABLE_PROVIDER, "ProviderPrincipal.selected"),
            service.replace(
                SERVICE_SIGNER_FROM_DURABLE_PROVIDER,
                SERVICE_SIGNER_FROM_DURABLE_PROVIDER.replace(
                    "providerApplicationId)",
                    "ProviderPrincipal.selected)",
                ),
            ),
            service.replace(
                SERVICE_SIGNER_FROM_DURABLE_PROVIDER,
                SERVICE_SIGNER_FROM_DURABLE_PROVIDER.replace(
                    "providerApplicationId)",
                    "io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION)",
                ),
            ),
            service.replace(SERVICE_PRINCIPAL_GUARD, "guardCurrentBuildProviderPrincipal("),
            service.replace(SERVICE_GUARD_ARGS, SERVICE_GUARD_ARGS.replace("providerApplicationId", "ProviderPrincipal.selected")),
            service.replace(SERVICE_ACQUIRE, "providerExecutorRegistry.value.acquireCurrentBuild("),
            service.replace(SERVICE_ACQUIRE_ARGS, SERVICE_ACQUIRE_ARGS.replace("capturedProviderSigner", "currentBuildSigner")),
            service.replace(SERVICE_READY_GATE, "if (false) {"),
            service.replace(SERVICE_BACKEND, "APlusComposition.testOnlyBackend("),
            service.replace(SERVICE_ENGINE_PARAMS, "APlusComposition.recoveryCoordinator(aplusBackend)"),
            service.replace(SERVICE_ENGINE_FACTORY, "AutomationEngine("),
            service + "\nval forbidden = ProviderPrincipal.selected\n",
        ).forEachIndexed { index, mutated ->
            assertTrue("production service route mutation $index is a no-op", mutated != service)
            assertTrue(
                "production service route mutation $index escaped",
                serviceRouteViolations(mutated).isNotEmpty(),
            )
        }

        listOf(
            qwyService.replace(QWY_DISCOVER_ROUTE, "handler().preflight(callingUid())"),
            qwyService.replace(QWY_PREFLIGHT_ROUTE, "handler().discover(callingUid())"),
            qwyService.replace(QWY_APPLY_ROUTE, "handler().release(callingUid(), request)"),
            qwyService.replace(QWY_OBSERVE_ROUTE, "handler().apply(callingUid(), request)"),
            qwyService.replace(QWY_RELEASE_ROUTE, "handler().observe(callingUid(), request)"),
            qwyService.replace(QWY_ADVANCE_ROUTE, "handler().release(callingUid(), request)"),
            qwyService.replace(QWY_CALLING_UID, "val callerUid = 10101"),
            qwyService.replace(QWY_APPLY_ROUTE, QWY_APPLY_ROUTE.replace("apply(uid, request)", "apply(10101, request)")),
            qwyService.replace(QWY_TYPED_SCOPE, "toTypedResult { block(Binder.getCallingUid()) }"),
            qwyService.replace(QWY_CLEAR_IDENTITY, "val token = 0L"),
            qwyService.replace(QWY_RESTORE_IDENTITY, "Unit"),
            qwyService.replace("} finally {", "} catch (e: RuntimeException) {"),
            qwyService.replace(
                "$QWY_CALLING_UID\n    $QWY_CLEAR_IDENTITY",
                "$QWY_CLEAR_IDENTITY\n    $QWY_CALLING_UID",
            ),
            qwyService.replace("block(callerUid)\n    } finally", "block(Binder.getCallingUid())\n    } finally"),
        ).forEachIndexed { index, mutated ->
            assertTrue("QWY Service route/uid mutation $index is a no-op", mutated != qwyService)
            assertTrue(
                "QWY Service route/uid mutation $index escaped",
                qwyServiceRouteViolations(mutated).isNotEmpty(),
            )
        }

        listOf(
            durableBridge.replace(BRIDGE_PLAN_READ, "repository.getCurrentBuildPlan("),
            durableBridge.replace(
                BRIDGE_PROVIDER_FROM_PLAN,
                "String durableProviderApplicationId = \"name.caiyao.fakegps.bench\";",
            ),
            durableBridge.replace(
                BRIDGE_SIGNER_FROM_DURABLE_PROVIDER,
                BRIDGE_SIGNER_FROM_DURABLE_PROVIDER.replace(
                    "durableProviderApplicationId",
                    "\"name.caiyao.fakegps.bench\"",
                ),
            ),
            durableBridge.replace(
                BRIDGE_SIGNER_FROM_DURABLE_PROVIDER,
                BRIDGE_SIGNER_FROM_DURABLE_PROVIDER.replace(
                    "durableProviderApplicationId",
                    "ProviderPrincipal.INSTANCE.getSelected()",
                ),
            ),
            durableBridge.replace(BRIDGE_PRINCIPAL_GUARD, "guardCurrentBuildProviderPrincipal("),
            durableBridge.replace(BRIDGE_ACQUIRE, "registry.acquireCurrentBuild("),
            durableBridge.replace(BRIDGE_ENGINE_PARAMS, "recoveryCoordinator\$app_release"),
            durableBridge.replace(
                "long planId,",
                "long planId, String durableProviderApplicationId,",
            ),
        ).forEachIndexed { index, mutated ->
            assertTrue("durable bridge route mutation $index is a no-op", mutated != durableBridge)
            assertTrue(
                "durable bridge route mutation $index escaped",
                durableBridgeViolations(mutated).isNotEmpty(),
            )
        }

        listOf(
            runner.replace(PINNED_PRIVILEGED_BASH_SHEBANG, "#!/bin/bash"),
            runner.replace(PINNED_BASH_STARTUP_ENV_CLEAR, ": # startup env clear deleted"),
            runner.replace(PINNED_FAIL_CLOSED_SHELL, "set -uo pipefail"),
            runner.replace(PINNED_AUTO_WRAPPER, "auto_wrapper=\"gradle\""),
            runner.replace(PINNED_QWY_WRAPPER, "qwy_wrapper=\"gradle\""),
            runner.replace(PINNED_PROJECT, "\"\$@\""),
            runner.replace(
                PINNED_MOTO_READONLY_SELFTEST_LINE,
                "  : # Moto read-only collector selftest deleted",
            ),
            runner.replace(
                PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE,
                "  : # services compatibility selftest deleted",
            ),
            runner.replace(
                PINNED_MOTO_READONLY_SELFTEST_LINE,
                "$PINNED_MOTO_READONLY_SELFTEST_LINE || true",
            ),
            runner.replace(
                PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE,
                "$PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE || true",
            ),
            runner.replace(
                PINNED_ZERO_ARG_SELFTEST_BLOCK,
                "$PINNED_MOTO_READONLY_SELFTEST_LINE\n" +
                    "$PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE\n" +
                    "if [[ \"\$#\" -eq 0 ]]; then\n" +
                    "  \"\$auto_wrapper\" -p \"\$repo_root/apps/cellrebel-auto\"",
            ),
            runner + "\nadb devices\n",
            runner +
                "\nbash \"\$repo_root/scripts/collect-issue66-moto-readonly-preflight.sh\"\n",
            runner.replace(PINNED_AUTO_ROUTING_PROJECT, "\"\$wrapper\" :app:testDebugUnitTest"),
            runner.replace(PINNED_AUTO_ROUTING_TEST, "'*SomeOtherTest'"),
            runner.replace(PINNED_QWY_PROJECT, "\"\$wrapper\" :app:testDebugUnitTest"),
            runner.replace(PINNED_QWY_HOOK_PLAN, "'*SomeOtherTest'"),
            runner.replace(PINNED_QWY_WIRING, "'*SomeOtherTest'"),
            runner.replace(PINNED_QWY_PRODUCTION, "'*SomeOtherTest'"),
            runner.replace(PINNED_QWY_ADAPTER, "'*SomeOtherTest'"),
            runner.replace(PINNED_QWY_CODEC, "'*SomeOtherTest'"),
            runner.replace(PINNED_QWY_ADVANCE, "'*SomeOtherTest'"),
            runner.replace(PINNED_FULL_HARNESS, "\"\$wrapper\" :harness:testDebugUnitTest"),
            runner.replace(PINNED_JAVA_PROFILE_VALIDATOR, "readonly java_profile_validator=/tmp/unreviewed"),
            runner.replace(PINNED_JAVA_RUNTIME_STAGER, "readonly java_runtime_stager=/tmp/unreviewed"),
            runner.replace(PINNED_ANDROID_SDK_VALIDATOR, "readonly android_sdk_validator=/tmp/unreviewed"),
            runner.replace(
                PINNED_STANDALONE_RUNTIME_SECURITY_TESTS,
                "  : # standalone runtime security tests deleted",
            ),
            runner.replace(PINNED_PASS_RECEIPT_SCHEMA, "\\\"hostIntegration\\\":\\\"RUNNING\\\""),
            runner.replace(PINNED_RUNNING_RECEIPT_SCHEMA, "\\\"hostIntegration\\\":\\\"PASS\\\""),
            runner.replace(PINNED_PRIVATE_UMASK, "umask 022"),
            runner.replace(PINNED_RECEIPT_DIR_PREPARE, "  : # private receipt directory deleted"),
            runner.replace(PINNED_LOCK_ACQUIRE, "  if false; then"),
            runner.replace(PINNED_LOCK_CLEANUP_TRAP, "  : # owner cleanup trap deleted"),
            runner.replace(PINNED_ATOMIC_RECEIPT_REPLACE, "    : # atomic replace deleted"),
            runner.replace(PINNED_LOCK_OWNER_WRITE, "  : # lock owner write deleted"),
            runner.replace(PINNED_LOCK_RELEASE_DISARM, "  lock_releasable=1"),
            runner.replace(PINNED_LOCK_RELEASE_ARM, "  : # lock release arm deleted"),
            runner.replace(PINNED_FINAL_LOCK_RELEASE, "  : # verified final lock release deleted"),
            runner.replace(PINNED_LOCK_RELEASE_GUARD, "  if [[ \"\${lock_owned:-0}\" -eq 1 ]]; then"),
            runner.replace(PINNED_LOCK_OWNER_CLEANUP_GUARD, "    if true; then"),
            runner.replace(PINNED_TEMP_IDENTITY_CLEANUP, "            if True:"),
            runner.replace(PINNED_POST_PUBLISH_BYTES_CHECK, "        or published_bytes == payload"),
            runner.replace(PINNED_RUNNING_RECEIPT_WRITE, "  : # RUNNING write deleted"),
            runner.replace(PINNED_PASS_RECEIPT_WRITE, "  : # PASS write deleted"),
            runner.replace(PINNED_POST_PASS_SOURCE_CHECK, "  if false; then"),
            runner.replace(PINNED_POST_PASS_RUNNER_CHECK, "  if false; then"),
            runner.replace(JAVA_HOME_MARKER, "\${UNPINNED_JAVA_HOME:-}"),
            runner.replace(ANDROID_HOME_MARKER, "\${UNPINNED_ANDROID_HOME:-}"),
            runner + "\nauto_wrapper[0]=/usr/bin/false\n",
            runner + "\nprintf -v auto_wrapper %s /usr/bin/false\n",
            runner + "\nunset qwy_wrapper\n",
            runner + "\ndeclare -n runner_alias=auto_wrapper\n",
            runner +
                "\nwrapper_prefix=auto_\n" +
                "wrapper_name=\"\${wrapper_prefix}wrapper\"\n" +
                "printf -v \"\$wrapper_name\" %s /usr/bin/false\n",
            runner +
                "\nwrapper_prefix=qwy_\n" +
                "wrapper_name=\"\${wrapper_prefix}wrapper\"\n" +
                "declare -n wrapper_reference=\"\$wrapper_name\"\n" +
                "wrapper_reference=/usr/bin/false\n",
            runner +
                "\nwrapper_prefix=auto_\n" +
                "wrapper_name=\"\${wrapper_prefix}wrapper\"\n" +
                "unset \"\$wrapper_name\"\n",
            runner + "\nhost_java_home=/usr/bin\n",
            runner + "\nhost_android_home=/tmp/unreviewed-sdk\n",
            runner + "\nhost_gradle_user_home=/tmp/persistent-gradle-home\n",
            runner + "\nunset local_sdk_override\n",
        ).forEachIndexed { index, mutated ->
            assertTrue("runner mutation $index is a no-op", mutated != runner)
            assertTrue(
                "runner mutation $index escaped",
                runnerViolations(mutated).isNotEmpty(),
            )
        }
    }

    @Test
    fun `Moto collector selftest uses portable grep rather than ripgrep`() {
        val source = findRepoRoot()
            .resolve("scripts/selftest-issue66-moto-readonly-collector.sh")
            .readText()

        assertTrue(
            "device-free host selftest must not depend on the nonstandard rg executable",
            !Regex("(?<![A-Za-z0-9_])rg(?![A-Za-z0-9_])").containsMatchIn(source),
        )
        assertTrue(
            "device-free host selftest must explicitly preflight portable grep",
            "for dependency in grep; do" in source,
        )
    }

    @Test
    fun `Moto collector selftest reads modes without mixing stat dialects`() {
        val source = findRepoRoot()
            .resolve("scripts/selftest-issue66-moto-readonly-collector.sh")
            .readText()

        assertTrue(
            "BSD stat fallback can emit filesystem text before GNU stat succeeds",
            "stat -f '%Lp'" !in source,
        )
        assertTrue(
            "GNU stat fallback must not be parsed after a noisy BSD-form probe",
            "stat -c '%a'" !in source,
        )
        assertTrue(
            "mode checks must use one cross-platform no-follow implementation",
            "file_mode()" in source && "stat.S_IMODE(os.lstat(path).st_mode)" in source,
        )
    }

    @Test
    fun `production collector pins host command lookup before external tools`() {
        val collector = findRepoRoot()
            .resolve("scripts/collect-issue66-moto-readonly-preflight.sh")
        val source = collector.readText()
        val sourceLines = source.lineSequence().toList()
        assertEquals(
            emptyList<String>(),
            privilegedBashStartupViolations(source, UNIFIED_BASH_STARTUP_CLEAR_TOPOLOGY),
        )
        val pathPin = sourceLines.indexOfFirst { it.trim() == PINNED_HOST_PATH_PIN }
        val firstHostLookup = sourceLines.indexOfFirst { it.startsWith("SELF_DIR=") }
        assertTrue(
            "production PATH must be pinned before the first host command lookup",
            pathPin >= 0 && firstHostLookup >= 0 && pathPin < firstHostLookup,
        )
        assertTrue(
            "embedded output-boundary Git calls must use the fixed reviewed client",
            "\"/usr/bin/git\"" in source && "[\"git\", " !in source,
        )
        assertTrue(
            "embedded output-boundary Git calls must ignore ambient Git configuration",
            "\"GIT_CONFIG_GLOBAL\": \"/dev/null\"" in source &&
                "\"GIT_OPTIONAL_LOCKS\": \"0\"" in source,
        )

        val poisonDir = Files.createTempDirectory("issue66-collector-path-poison-")
        val marker = poisonDir.resolve("unexpected-host-tool")
        val poison = poisonDir.resolve("dirname")
        try {
            Files.write(
                poison,
                (
                    "#!/bin/bash\n" +
                        "printf 'unexpected dirname execution\\n' >>\"\$ISSUE66_PATH_MARKER\"\n" +
                        "exec /usr/bin/dirname \"\$@\"\n"
                    ).toByteArray(),
            )
            Files.setPosixFilePermissions(
                poison,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            val probe = ProcessBuilder(collector.toString())
                .redirectErrorStream(true)
            probe.environment()["PATH"] = "$poisonDir:/usr/bin:/bin"
            probe.environment()["ISSUE66_PATH_MARKER"] = marker.toString()
            val process = probe.start()
            process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("usage-only probe must stop before collection", 2, process.waitFor())
            assertTrue("ambient dirname shim executed before argument refusal", !Files.exists(marker))
        } finally {
            poisonDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collector selftest and fake adb reject downgraded Bash startup`() {
        val repo = findRepoRoot()
        val scripts = linkedMapOf(
            "collector" to repo.resolve(
                "scripts/collect-issue66-moto-readonly-preflight.sh",
            ).readText(),
            "selftest" to repo.resolve(
                "scripts/selftest-issue66-moto-readonly-collector.sh",
            ).readText(),
            "fake-adb" to repo.resolve(
                "scripts/fixtures/issue66-moto-readonly-collector/fake-adb.sh",
            ).readText(),
        )

        scripts.forEach { (label, source) ->
            assertEquals(
                label,
                emptyList<String>(),
                privilegedBashStartupViolations(source, UNIFIED_BASH_STARTUP_CLEAR_TOPOLOGY),
            )
        }
        val mutations = scripts.flatMap { (label, source) ->
            listOf(
                "$label plain Bash" to
                    source.replaceFirst(PINNED_PRIVILEGED_BASH_SHEBANG, "#!/bin/bash"),
                "$label unified startup clear deleted" to
                    source.replaceFirst(PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR, ":"),
                "$label BASH_ENV clear omitted" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "unset ENV DEVELOPER_DIR SDKROOT TOOLCHAINS",
                    ),
                "$label ENV clear omitted" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "unset BASH_ENV DEVELOPER_DIR SDKROOT TOOLCHAINS",
                    ),
                "$label DEVELOPER_DIR clear omitted" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "unset BASH_ENV ENV SDKROOT TOOLCHAINS",
                    ),
                "$label SDKROOT clear omitted" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "unset BASH_ENV ENV DEVELOPER_DIR TOOLCHAINS",
                    ),
                "$label TOOLCHAINS clear omitted" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "unset BASH_ENV ENV DEVELOPER_DIR SDKROOT",
                    ),
                "$label unified startup clear split" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "$PINNED_BASH_STARTUP_ENV_CLEAR\n$PINNED_DEVELOPER_SELECTOR_CLEAR",
                    ),
                "$label startup clear delayed" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "$PINNED_COLLECTOR_FAIL_CLOSED_SHELL\n$PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR",
                    ),
                "$label startup clear duplicated" to
                    source.replaceFirst(
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                        "$PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR\n" +
                            PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                    ),
            )
        }
        mutations.forEach { (label, mutated) ->
            val original = scripts.getValue(label.substringBefore(' '))
            assertTrue("$label mutation is a no-op", mutated != original)
        }
        val escaped = mutations
            .filter { (_, mutated) ->
                privilegedBashStartupViolations(
                    mutated,
                    UNIFIED_BASH_STARTUP_CLEAR_TOPOLOGY,
                ).isEmpty()
            }
            .map { it.first }
        assertEquals("privileged Bash startup mutations escaped", emptyList<String>(), escaped)
    }

    @Test
    fun `Moto collector selftest pins host lookup before bootstrap`() {
        val selftest = findRepoRoot()
            .resolve("scripts/selftest-issue66-moto-readonly-collector.sh")
        val source = selftest.readText()
        assertEquals(
            emptyList<String>(),
            servicesStartupViolations(source, UNIFIED_BASH_STARTUP_CLEAR_TOPOLOGY),
        )
        assertTrue(
            "the deliberate fixture PATH must extend the already pinned base PATH",
            "BASE_SELFTEST_PATH=\"\$WORK/bin:\$PATH\"" in source,
        )

        val poisonDir = Files.createTempDirectory("issue66-collector-selftest-path-poison-")
        val marker = poisonDir.resolve("unexpected-host-tool")
        val poison = poisonDir.resolve("dirname")
        try {
            Files.write(
                poison,
                (
                    "#!/bin/bash\n" +
                        "/usr/bin/touch \"\$ISSUE66_PATH_MARKER\"\n" +
                        "exec /usr/bin/dirname \"\$@\"\n"
                    ).toByteArray(),
            )
            Files.setPosixFilePermissions(
                poison,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            val process = ProcessBuilder(selftest.toString(), "--startup-env-contract-only")
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$poisonDir:/usr/bin:/bin"
                    environment()["ISSUE66_PATH_MARKER"] = marker.toString()
                    environment()["ADB"] = "/usr/bin/false"
                    environment().remove("BASH_ENV")
                    environment().remove("ENV")
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            assertTrue("Moto selftest executed the ambient dirname shim", !Files.exists(marker))
        } finally {
            poisonDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `services checker and selftest reject downgraded Bash startup`() {
        val repo = findRepoRoot()
        val scripts = linkedMapOf(
            "checker" to repo.resolve(
                "scripts/check-issue66-services-compatibility.sh",
            ).readText(),
            "selftest" to repo.resolve(
                "scripts/selftest-issue66-services-compatibility.sh",
            ).readText(),
        )

        scripts.forEach { (label, source) ->
            assertEquals(
                label,
                emptyList<String>(),
                servicesStartupViolations(source, SPLIT_BASH_STARTUP_CLEAR_TOPOLOGY),
            )
        }
        val mutations = scripts.flatMap { (label, source) ->
            listOf(
                "$label env Bash" to
                    source.replaceFirst(PINNED_PRIVILEGED_BASH_SHEBANG, "#!/usr/bin/env bash"),
                "$label startup clear deleted" to
                    source.replaceFirst(PINNED_BASH_STARTUP_ENV_CLEAR, ":"),
                "$label startup clear partial" to
                    source.replaceFirst(PINNED_BASH_STARTUP_ENV_CLEAR, "unset BASH_ENV"),
                "$label selector clear deleted" to
                    source.replaceFirst(PINNED_DEVELOPER_SELECTOR_CLEAR, ":"),
                "$label selector clear partial" to
                    source.replaceFirst(
                        PINNED_DEVELOPER_SELECTOR_CLEAR,
                        "unset DEVELOPER_DIR SDKROOT",
                    ),
                "$label split startup clear coalesced" to
                    source.replaceFirst(
                        "$PINNED_BASH_STARTUP_ENV_CLEAR\n$PINNED_DEVELOPER_SELECTOR_CLEAR",
                        PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR,
                    ),
                "$label startup clear order reversed" to
                    source.replaceFirst(
                        "$PINNED_BASH_STARTUP_ENV_CLEAR\n$PINNED_DEVELOPER_SELECTOR_CLEAR",
                        "$PINNED_DEVELOPER_SELECTOR_CLEAR\n$PINNED_BASH_STARTUP_ENV_CLEAR",
                    ),
                "$label startup clear duplicated" to
                    source.replaceFirst(
                        PINNED_BASH_STARTUP_ENV_CLEAR,
                        "$PINNED_BASH_STARTUP_ENV_CLEAR\n$PINNED_BASH_STARTUP_ENV_CLEAR",
                    ),
                "$label selector clear duplicated" to
                    source.replaceFirst(
                        PINNED_DEVELOPER_SELECTOR_CLEAR,
                        "$PINNED_DEVELOPER_SELECTOR_CLEAR\n$PINNED_DEVELOPER_SELECTOR_CLEAR",
                    ),
                "$label PATH pin deleted" to
                    source.replaceFirst(PINNED_HOST_PATH_PIN, ": # PATH pin deleted"),
                "$label PATH export deleted" to
                    source.replaceFirst(PINNED_HOST_PATH_EXPORT, ": # PATH export deleted"),
                "$label PATH pin delayed" to
                    source.replaceFirst(
                        "$PINNED_HOST_PATH_EXPORT\n$PINNED_COLLECTOR_FAIL_CLOSED_SHELL",
                        "$PINNED_COLLECTOR_FAIL_CLOSED_SHELL\n$PINNED_HOST_PATH_EXPORT",
                    ),
            )
        }
        mutations.forEach { (label, mutated) ->
            val original = scripts.getValue(label.substringBefore(' '))
            assertTrue("$label mutation is a no-op", mutated != original)
        }
        val escaped = mutations
            .filter { (_, mutated) ->
                servicesStartupViolations(
                    mutated,
                    SPLIT_BASH_STARTUP_CLEAR_TOPOLOGY,
                ).isEmpty()
            }
            .map { it.first }
        assertEquals("services startup mutations escaped", emptyList<String>(), escaped)
    }

    @Test
    fun `services checker and selftest isolate every embedded Python interpreter`() {
        val repo = findRepoRoot()
        val scripts = linkedMapOf(
            "checker" to repo.resolve("scripts/check-issue66-services-compatibility.sh").readText(),
            "selftest" to repo.resolve("scripts/selftest-issue66-services-compatibility.sh").readText(),
        )
        val barePython = Regex("(?m)(?<![/A-Za-z0-9_])python3\\s+-")
        val fixedPython = Regex("(?m)/usr/bin/python3\\s+-I\\s+-")

        scripts.forEach { (label, source) ->
            assertEquals(
                "$label retains caller-controlled embedded Python calls",
                emptyList<String>(),
                barePython.findAll(source).map { it.value }.toList(),
            )
            assertTrue(
                "$label no longer has any fixed isolated embedded Python calls",
                fixedPython.containsMatchIn(source),
            )
            val weakened = source.replaceFirst(fixedPython, "python3 -")
            assertTrue("$label Python-isolation mutation is a no-op", weakened != source)
            assertTrue(
                "$label Python-isolation mutation escaped the guard",
                barePython.containsMatchIn(weakened),
            )
        }
    }

    @Test
    fun `services direct entry does not source poisoned Bash startup files`() {
        val repo = findRepoRoot()
        val sourceScripts = linkedMapOf(
            "checker" to repo.resolve("scripts/check-issue66-services-compatibility.sh"),
            "selftest" to repo.resolve("scripts/selftest-issue66-services-compatibility.sh"),
        )
        val probeDir = Files.createTempDirectory("issue66-services-bash-env-")
        val poison = probeDir.resolve("poison.sh")
        try {
            Files.write(
                poison,
                (
                    "/usr/bin/touch \"\$ISSUE66_BASH_ENV_MARKER\"\n" +
                        "exit 0\n"
                    ).toByteArray(),
            )
            sourceScripts.forEach { (label, source) ->
                val script = probeDir.resolve("$label-probe.sh")
                val marker = probeDir.resolve("$label-poison-ran")
                Files.copy(source, script)
                Files.setPosixFilePermissions(
                    script,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
                val process = ProcessBuilder(script.toString())
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PATH"] = "/usr/bin:/bin"
                        environment()["BASH_ENV"] = poison.toString()
                        environment()["ENV"] = poison.toString()
                        environment()["ISSUE66_BASH_ENV_MARKER"] = marker.toString()
                        environment()["ADB"] = "/usr/bin/false"
                    }
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val rc = process.waitFor()
                assertTrue("$label sourced BASH_ENV/ENV before its body: $output", !Files.exists(marker))
                assertTrue("$label copied probe unexpectedly succeeded: $output", rc != 0)
            }
        } finally {
            probeDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `services fake dexdump does not import an exported Bash function`() {
        val repo = findRepoRoot()
        val fakeDexdump = repo.resolve(
            "scripts/fixtures/issue66-services-compatibility/fake-dexdump.sh",
        )
        assertEquals(
            "fake dexdump startup",
            emptyList<String>(),
            privilegedBashStartupViolations(
                fakeDexdump.readText(),
                SPLIT_BASH_STARTUP_CLEAR_TOPOLOGY,
            ),
        )
        val probeDir = Files.createTempDirectory("issue66-services-function-poison-")
        val launcher = probeDir.resolve("launch.sh")
        val marker = probeDir.resolve("imported-function-ran")
        val dex = probeDir.resolve("classes.dex")
        try {
            Files.write(dex, "fixture payload\n".toByteArray())
            Files.write(
                launcher,
                (
                    "#!/bin/bash -p\n" +
                        "unset BASH_ENV ENV\n" +
                        "PATH=/usr/bin:/bin\n" +
                        "export PATH\n" +
                        "ISSUE66_FUNCTION_MARKER=\"\$1\"\n" +
                        "export ISSUE66_FUNCTION_MARKER\n" +
                        "cat() { /usr/bin/touch \"\$ISSUE66_FUNCTION_MARKER\"; /bin/cat \"\$@\"; }\n" +
                        "export -f cat\n" +
                        "exec \"\$2\" -d \"\$3\"\n"
                    ).toByteArray(),
            )
            val process = ProcessBuilder(
                "/bin/bash",
                "-p",
                launcher.toString(),
                marker.toString(),
                fakeDexdump.toString(),
                dex.toString(),
            ).redirectErrorStream(true)
                .apply { environment()["ADB"] = "/usr/bin/false" }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            assertEquals("fixture payload\n", output)
            assertTrue("fake dexdump imported and ran the exported cat function", !Files.exists(marker))

            val downgraded = probeDir.resolve("fake-dexdump-env-bash.sh")
            val downgradedSource = fakeDexdump.readText().replaceFirst(
                PINNED_PRIVILEGED_BASH_SHEBANG,
                "#!/usr/bin/env bash",
            )
            assertTrue("fake dexdump downgrade mutation is a no-op", downgradedSource != fakeDexdump.readText())
            Files.write(downgraded, downgradedSource.toByteArray())
            Files.setPosixFilePermissions(
                downgraded,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            Files.deleteIfExists(marker)
            val mutationProcess = ProcessBuilder(
                "/bin/bash",
                "-p",
                launcher.toString(),
                marker.toString(),
                downgraded.toString(),
                dex.toString(),
            ).redirectErrorStream(true).start()
            mutationProcess.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, mutationProcess.waitFor())
            assertTrue(
                "exported-function probe did not discriminate the env-Bash mutation",
                Files.exists(marker),
            )
        } finally {
            probeDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `services checker pins host command lookup before source binding`() {
        val checker = findRepoRoot().resolve("scripts/check-issue66-services-compatibility.sh")
        val source = checker.readText()
        val sourceLines = source.lineSequence().toList()
        assertEquals(
            emptyList<String>(),
            servicesStartupViolations(source, SPLIT_BASH_STARTUP_CLEAR_TOPOLOGY),
        )
        val pathPin = sourceLines.indexOf(PINNED_HOST_PATH_PIN)
        val firstHostLookup = sourceLines.indexOfFirst { it.startsWith("SELF_DIR=") }
        assertTrue(
            "services checker PATH must be pinned before its first host command lookup",
            pathPin >= 0 && firstHostLookup >= 0 && pathPin < firstHostLookup,
        )

        val poisonDir = Files.createTempDirectory("issue66-services-path-poison-")
        val marker = poisonDir.resolve("unexpected-host-tool")
        val poison = poisonDir.resolve("dirname")
        try {
            Files.write(
                poison,
                (
                    "#!/bin/bash\n" +
                        "/usr/bin/touch \"\$ISSUE66_PATH_MARKER\"\n" +
                        "exec /usr/bin/dirname \"\$@\"\n"
                    ).toByteArray(),
            )
            Files.setPosixFilePermissions(
                poison,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            val process = ProcessBuilder(checker.toString())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$poisonDir:/usr/bin:/bin"
                    environment()["ISSUE66_PATH_MARKER"] = marker.toString()
                    environment()["ADB"] = "/usr/bin/false"
                    environment().remove("BASH_ENV")
                    environment().remove("ENV")
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 2, process.waitFor())
            assertTrue("ambient dirname shim executed before argument refusal", !Files.exists(marker))
        } finally {
            poisonDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Gradle cache binds every services compatibility executable input`() {
        val buildScript = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/harness/build.gradle.kts")
            .readText()
        listOf(
            "../../scripts/check-issue66-services-compatibility.sh",
            "../../scripts/selftest-issue66-services-compatibility.sh",
            "../../scripts/fixtures/issue66-services-compatibility/fake-dexdump.sh",
        ).forEach { path ->
            assertEquals("Gradle input occurrence changed for $path", 1, buildScript.windowed(path.length).count { it == path })
        }
    }

    @Test
    fun `services selftest reports an unavailable SDK probe as skipped`() {
        val source = findRepoRoot()
            .resolve("scripts/selftest-issue66-services-compatibility.sh")
            .readText()
        assertTrue("services selftest must count skipped probes", "skip=0" in source)
        assertTrue(
            "missing local SDK dexdump must not be reported as a passing assertion",
            "report_skip \"unattested SDK dexdump probe unavailable because no local SDK is installed\"" in source &&
                "report ok \"unattested SDK dexdump probe skipped" !in source,
        )
        assertTrue(
            "services selftest summary must disclose skipped probes",
            "passed, %d failed, %d skipped" in source,
        )
    }

    @Test
    fun `Moto collector requires an externally reviewed head and digest`() {
        val repo = findRepoRoot()
        val collector = repo.resolve("scripts/collect-issue66-moto-readonly-preflight.sh")
        val source = collector.readText()
        assertTrue(source, "--reviewed-head" in source)
        assertTrue(source, "--reviewed-collector-sha256" in source)
        assertTrue(source, "\"sourceHead\"" in source)
        assertTrue(source, "\"schemaVersion\":3" in source)
        assertTrue(
            "collector source must be rechecked before every live ADB call",
            "review_binding_intact || stop_now STOP_REVIEW_BINDING_CHANGED" in source,
        )

        val headProcess = ProcessBuilder(
            "/usr/bin/git",
            "-C",
            repo.toString(),
            "rev-parse",
            "--verify",
            "HEAD^{commit}",
        ).redirectErrorStream(true).start()
        val reviewedHead = headProcess.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(reviewedHead, 0, headProcess.waitFor())
        assertTrue(reviewedHead, reviewedHead.matches(Regex("[0-9a-f]{40}")))
        val reviewedDigest = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(collector))
            .joinToString("") { "%02x".format(it) }
        val stateDir = Files.createTempDirectory("issue66-review-binding-")

        fun invoke(vararg arguments: String): Pair<Int, String> {
            val process = ProcessBuilder(collector.toString(), *arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output
        }

        try {
            val common = arrayOf(
                "--adb", "/usr/bin/false",
                "--serial", "ZY22JHW9M4",
                "--output", stateDir.resolve("must-not-exist").toString(),
            )
            val missing = invoke(*common)
            assertEquals(missing.second, 22, missing.first)
            assertTrue(missing.second, missing.second.contains("STOP_REVIEW_BINDING_REQUIRED"))
            assertTrue("missing binding created evidence", !Files.exists(stateDir.resolve("must-not-exist")))

            val wrongHead = invoke(
                "--reviewed-head", "0".repeat(40),
                "--reviewed-collector-sha256", reviewedDigest,
                *common,
            )
            assertEquals(wrongHead.second, 22, wrongHead.first)
            assertTrue(wrongHead.second, wrongHead.second.contains("STOP_REVIEW_BINDING_MISMATCH"))

            val wrongDigest = invoke(
                "--reviewed-head", reviewedHead,
                "--reviewed-collector-sha256", "0".repeat(64),
                *common,
            )
            assertEquals(wrongDigest.second, 22, wrongDigest.first)
            assertTrue(wrongDigest.second, wrongDigest.second.contains("STOP_REVIEW_BINDING_MISMATCH"))

            val accepted = invoke(
                "--reviewed-head", reviewedHead,
                "--reviewed-collector-sha256", reviewedDigest,
                *common,
            )
            assertEquals(accepted.second, 22, accepted.first)
            assertTrue(accepted.second, accepted.second.contains("STOP_ADB_CLIENT_UNAPPROVED"))

            val verifyMissing = invoke("--verify-receipts", stateDir.resolve("absent").toString())
            assertEquals(verifyMissing.second, 22, verifyMissing.first)
            assertTrue(
                verifyMissing.second,
                verifyMissing.second.contains("STOP_REVIEW_BINDING_REQUIRED"),
            )
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner guard rejects quoted and nested-shell adb commands`() {
        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        assertEquals(emptyList<String>(), runnerViolations(runner))
        val indirectCommandSetup =
            "\ndevice_prefix=a\n" +
                "device_suffix=db\n" +
                "device_command=\"\$ANDROID_HOME/platform-tools/\$device_prefix\$device_suffix\"\n"

        val mutations = linkedMapOf(
            "double-quoted adb" to runner + "\n\"adb\" devices\n",
            "single-quoted adb" to runner + "\n'adb' devices\n",
            "ANSI-C quoted adb" to runner + "\n\$'\\x61\\x64\\x62' devices\n",
            "bash-c adb" to runner + "\nbash -c 'adb devices'\n",
            "command-substitution adb" to runner + "\nprobe=\"\$(adb devices)\"\n",
            "backtick adb" to runner + "\nprobe=`adb devices`\n",
            "environment-indirect adb" to runner + "\n\"\${ADB}\" devices\n",
            "adjacent-token adb path" to
                runner + "\n\"\$ANDROID_HOME/platform-tools/a\"\"db\" devices\n",
            "variable-composed adb command" to
                runner + indirectCommandSetup +
                    "\"\$device_command\" devices\n",
            "function-wrapped variable-composed adb" to
                runner + indirectCommandSetup +
                    "run_device_probe() {\n" +
                    "  \"\$device_command\" devices\n" +
                    "}\n" +
                    "run_device_probe\n",
            "command-dispatched variable-composed adb" to
                runner + indirectCommandSetup +
                    "command \"\$device_command\" devices\n",
            "allowed-wrapper variable redirected to composed adb" to
                runner +
                    "\ndevice_prefix=a\n" +
                    "device_suffix=db\n" +
                    "auto_wrapper=\"\$ANDROID_HOME/platform-tools/\$device_prefix\$device_suffix\"\n" +
                    "\"\$auto_wrapper\" devices\n",
            "dynamic command after and" to
                runner + indirectCommandSetup + "true && \"\$device_command\" devices\n",
            "dynamic command after or" to
                runner + indirectCommandSetup + "false || \"\$device_command\" devices\n",
            "dynamic command after semicolon" to
                runner + indirectCommandSetup + ": ; \"\$device_command\" devices\n",
            "dynamic command after pipeline" to
                runner + indirectCommandSetup + "printf x | \"\$device_command\" devices\n",
            "dynamic command in if" to
                runner + indirectCommandSetup +
                    "if \"\$device_command\" devices; then :; fi\n",
            "dynamic command in elif" to
                runner + indirectCommandSetup +
                    "if false; then :; elif \"\$device_command\" devices; then :; fi\n",
            "dynamic command in while" to
                runner + indirectCommandSetup +
                    "while \"\$device_command\" devices; do :; done\n",
            "dynamic command in until" to
                runner + indirectCommandSetup +
                    "until \"\$device_command\" devices; do :; done\n",
            "dynamic command after then" to
                runner + indirectCommandSetup +
                    "if true; then \"\$device_command\" devices; fi\n",
            "dynamic command after do" to
                runner + indirectCommandSetup +
                    "while true; do \"\$device_command\" devices; break; done\n",
            "dynamic command after else" to
                runner + indirectCommandSetup +
                    "if false; then :; else \"\$device_command\" devices; fi\n",
            "dynamic command after background separator" to
                runner + indirectCommandSetup + "true & \"\$device_command\" devices\n",
            "dynamic command in subshell" to
                runner + indirectCommandSetup + "( \"\$device_command\" devices )\n",
            "dynamic command in group" to
                runner + indirectCommandSetup + "{ \"\$device_command\" devices; }\n",
            "dynamic command in case arm" to
                runner + indirectCommandSetup +
                    "case x in x) \"\$device_command\" devices ;; esac\n",
            "dynamic command via exec" to
                runner + indirectCommandSetup + "exec \"\$device_command\" devices\n",
            "dynamic command via env" to
                runner + indirectCommandSetup + "env TEST_ONLY=1 \"\$device_command\" devices\n",
            "dynamic command via coproc" to
                runner + indirectCommandSetup + "coproc \"\$device_command\" devices\n",
            "dynamic command via time" to
                runner + indirectCommandSetup + "time \"\$device_command\" devices\n",
            "dynamic command via nohup" to
                runner + indirectCommandSetup + "nohup \"\$device_command\" devices\n",
            "assignment-prefixed dynamic command" to
                runner + indirectCommandSetup +
                    "PROBE_ONLY=1 \"\$device_command\" devices\n",
            "command substitution as command word" to
                runner + indirectCommandSetup +
                    "\"\$(printf '%s' \"\$device_command\")\" devices\n",
            "partially dynamic command word" to
                runner + indirectCommandSetup +
                    "cd \"\$host_android_home/platform-tools\"\n" +
                    "\"./a\${device_suffix}\" devices\n",
            "dynamic command via nice" to
                runner + indirectCommandSetup +
                    "/usr/bin/nice \"\$device_command\" devices\n",
            "dynamic command after parameter-length expansion" to
                runner + indirectCommandSetup +
                    "noop=x\n" +
                    ": \${#noop}; \"\$device_command\" devices\n",
            "dynamic command substitution" to
                runner + indirectCommandSetup + "probe=\"\$(\"\$device_command\" devices)\"\n",
            "eval after semicolon" to
                runner + indirectCommandSetup +
                    "command_text='\"\$device_command\" devices'\n" +
                    ":; eval \"\$command_text\"\n",
            "shell interpreter after semicolon" to
                runner + indirectCommandSetup +
                    ":; bash -c '\"\$device_command\" devices'\n",
            "backslash-continued adb token" to
                runner + "\n\"\$ANDROID_HOME/platform-tools/a\"\\\n\"db\" devices\n",
            "indirect device helper" to
                runner + "\nbash \"\$repo_root/scripts/device-probe.sh\"\n",
        )
        mutations.forEach { (label, mutated) ->
            assertTrue("$label mutation is a no-op", mutated != runner)
        }
        val escaped = mutations
            .filterValues { mutated -> runnerViolations(mutated).isEmpty() }
            .keys
            .toList()
        assertEquals("quoted/nested adb mutations escaped", emptyList<String>(), escaped)
    }

    @Test
    fun `runner guard rejects deferred and interpreter command dispatch`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        assertEquals(emptyList<String>(), runnerViolations(runner))
        val indirectCommandSetup =
            "\ndevice_prefix=a\n" +
                "device_suffix=db\n" +
                "device_command=\"\$ANDROID_HOME/platform-tools/\$device_prefix\$device_suffix\"\n"

        val mutations = linkedMapOf(
            "source after semicolon" to
                runner + "\n:; source \"\$repo_root/no-extension\"\n",
            "dot source after semicolon" to
                runner + "\n:; . \"\$repo_root/no-extension\"\n",
            "deferred device command in trap" to
                runner + indirectCommandSetup +
                    "trap \"\\\"\$device_command\\\" devices\" EXIT\n",
            "command substitution inside arithmetic" to
                runner + indirectCommandSetup +
                    "probe=\$(( \$(\"\$device_command\" devices) + 1 ))\n",
            "xargs command dispatch" to
                runner + indirectCommandSetup +
                    "printf '%s\\0' \"\$device_command\" | /usr/bin/xargs -0 -I{} \"{}\" devices\n",
            "find exec command dispatch" to
                runner + indirectCommandSetup +
                    "find \"\$repo_root\" -maxdepth 0 -exec \"\$device_command\" devices \\;\n",
            "Python heredoc subprocess dispatch" to
                runner +
                    "\n/usr/bin/python3 -I - <<'PY'\n" +
                    "import os\n" +
                    "import subprocess\n" +
                    "subprocess.run([os.environ[\"ANDROID_HOME\"] + \"/platform-tools/\" + \"a\" + \"db\", \"devices\"])\n" +
                    "PY\n",
            "indirect expansion command position" to
                runner + indirectCommandSetup +
                    "command_ref=device_command\n" +
                    "\"\${!command_ref}\" devices\n",
            "positional parameter command position" to
                runner + indirectCommandSetup +
                    "run_positional_probe() {\n" +
                    "  \"\$1\" devices\n" +
                    "}\n" +
                    "run_positional_probe \"\$device_command\"\n",
            "dynamic cleanup trap" to
                runner +
                    "\ncleanup_command=cleanup_host_gate_lock\n" +
                    "trap \"\$cleanup_command\" EXIT\n",
        )

        mutations.forEach { (label, mutated) ->
            assertTrue("$label mutation is a no-op", mutated != runner)
        }
        val escaped = mutations
            .filterValues { mutated -> runnerViolations(mutated).isEmpty() }
            .keys
            .toList()
        assertEquals("deferred/interpreter mutations escaped", emptyList<String>(), escaped)
    }

    @Test
    fun `runner Python AST guard rejects non allowlisted execution nodes`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        assertEquals(emptyList<String>(), runnerViolations(runner))
        val marker = "import os\n\nif hasattr(os, \"environb\"):\n"
        assertTrue("Python AST mutation marker is missing", marker in runner)

        fun inject(source: String): String = runner.replaceFirst(
            marker,
            "import os\n$source\nif hasattr(os, \"environb\"):\n",
        )

        val mutations = linkedMapOf(
            "subprocess Popen" to
                inject("import subprocess\nsubprocess.Popen(['/usr/bin/false'])"),
            "os system" to inject("os.system('/usr/bin/false')"),
            "os popen" to inject("os.popen('/usr/bin/false')"),
            "os exec" to inject("os.execv('/usr/bin/false', ['false'])"),
            "os spawn" to inject("os.spawnv(os.P_WAIT, '/usr/bin/false', ['false'])"),
            "dynamic eval" to inject("eval('1 + 1')"),
            "dynamic exec" to inject("exec('pass')"),
            "dynamic import" to
                inject("__import__('subprocess').run(['/usr/bin/false'])"),
            "importlib execution" to
                inject("import importlib\nimportlib.import_module('subprocess').run(['/usr/bin/false'])"),
            "ctypes loader" to
                inject("import ctypes\nctypes.CDLL('/tmp/unreviewed-library')"),
            "aliased subprocess import" to
                inject("import subprocess as process\nprocess.run(['/usr/bin/false'])"),
            "from subprocess import" to
                inject("from subprocess import run\nrun(['/usr/bin/false'])"),
        )
        mutations.forEach { (label, mutated) ->
            assertTrue("$label mutation is a no-op", mutated != runner)
            assertTrue(
                "$label escaped the Python AST guard",
                runnerViolations(mutated).isNotEmpty(),
            )
        }

        val moduleAliasMutations = linkedMapOf(
            "subprocess module alias" to
                inject(
                    "import subprocess\n" +
                        "process_module = subprocess\n" +
                        "process_module.run(['/usr/bin/false'])",
                ),
            "os module alias" to
                inject(
                    "operating_system = os\n" +
                        "operating_system.system('/usr/bin/false')",
                ),
        )
        val escapedModuleAliases = moduleAliasMutations
            .filterValues { mutated -> runnerViolations(mutated).isEmpty() }
            .keys
            .toList()
        assertEquals(
            "Python process-module aliases escaped",
            emptyList<String>(),
            escapedModuleAliases,
        )

        val inertText = inject(
            "# subprocess.run and os.system are inert comments\n" +
                "process_documentation = 'ctypes.CDLL and eval are inert strings'",
        )
        assertEquals(emptyList<String>(), runnerViolations(inertText))
    }

    @Test
    fun `runner guard ignores adb text confined to shell comments and heredoc bodies`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val commentOnly = runner + "\n: # adb is documentation, not a command\n"
        val quotedOnly = runner +
            "\nnote='if \"\$device_command\" devices; " +
            "/usr/bin/nice \"\$device_command\"'\n"
        val heredocLookingCommentOnly = runner +
            "\n# /usr/bin/python3 -I - <<'INERT_COMMENT_ONLY'\n" +
            "# This is a comment, not a heredoc body.\n"
        val heredocOnly = runner.replaceFirst(
            "import errno\n",
            "import errno\n# adb is inert Python heredoc text\n",
        )
        val expandingHeredoc = runner +
            "\n: <<HOST_GATE_EXPANDING\n" +
            "\$(adb devices)\n" +
            "HOST_GATE_EXPANDING\n"
        val expandingNonIdentifierHeredoc = runner +
            "\n: <<1HOST_GATE_EXPANDING\n" +
            "literal body\n" +
            "1HOST_GATE_EXPANDING\n"

        assertEquals(emptyList<String>(), runnerViolations(commentOnly))
        assertEquals(emptyList<String>(), runnerViolations(quotedOnly))
        assertEquals(emptyList<String>(), runnerViolations(heredocLookingCommentOnly))
        assertTrue("heredoc fixture mutation is a no-op", heredocOnly != runner)
        assertEquals(emptyList<String>(), runnerViolations(heredocOnly))
        assertTrue("unquoted heredoc mutation is a no-op", expandingHeredoc != runner)
        assertTrue(
            "unquoted heredoc expansion escaped the runner guard",
            runnerViolations(expandingHeredoc).isNotEmpty(),
        )
        assertTrue(
            "unquoted non-identifier heredoc escaped the runner guard",
            runnerViolations(expandingNonIdentifierHeredoc).isNotEmpty(),
        )
    }

    @Test
    fun `offline verifier reads authenticated files through one no-follow descriptor`() {
        val repo = findRepoRoot()
        val collector = repo.resolve("scripts/collect-issue66-moto-readonly-preflight.sh").readText()
        val verifierMarker = "verify_receipts() { # existing evidence root; host-only, no adb"
        assertEquals("offline verifier marker must occur exactly once", 1, collector.split(verifierMarker).size - 1)
        val verifier = collector.substringAfter(verifierMarker)
        fun helperSource(start: String, end: String): String {
            assertEquals("helper start must occur exactly once: $start", 1, verifier.split(start).size - 1)
            assertEquals("helper end must occur exactly once: $end", 1, verifier.split(end).size - 1)
            assertTrue("helper end must follow its start", verifier.indexOf(end) > verifier.indexOf(start))
            return verifier.substringAfter(start).substringBefore(end)
        }
        val evidenceReader = helperSource(
            "def stable_bytes(path, expected_mode=0o600):",
            "def stable_file_digest(path, expected_mode=0o600, tree_digest=None, tree_name=None):",
        )
        val digestReader = helperSource(
            "def stable_file_digest(path, expected_mode=0o600, tree_digest=None, tree_name=None):",
            "def stable_trust_bytes(path, byte_limit):",
        )
        val trustReader = helperSource(
            "def stable_trust_bytes(path, byte_limit):",
            "def stable_repo_bytes(path, byte_limit):",
        )
        val repoReader = helperSource(
            "def stable_repo_bytes(path, byte_limit):",
            "def bounded_retained_control_total(current, values, byte_limit):",
        )

        listOf(
            "evidence reader" to evidenceReader,
            "streamed digest reader" to digestReader,
            "repo trust reader" to trustReader,
        ).forEach {
                (label, source) ->
            assertTrue("$label must open a pinned file descriptor", source.contains("os.open("))
            assertTrue("$label must refuse symlink traversal", source.contains("O_NOFOLLOW"))
            assertTrue("$label must never block on a raced FIFO", source.contains("O_NONBLOCK"))
            assertTrue("$label must bind descriptor state", source.contains("os.fstat("))
            assertTrue("$label must read the opened descriptor", source.contains("os.read("))
            assertTrue("$label must not reopen the pathname", !source.contains("path.read_bytes()"))
        }
        assertTrue("repo reader must delegate its fixed limit", repoReader.contains("stable_trust_bytes(path, byte_limit)"))
        assertTrue("trust reader must stop at limit plus one", trustReader.contains("min(1024 * 1024, remaining + 1)"))
        assertTrue("digest reader must stop at limit plus one", digestReader.contains("min(1024 * 1024, remaining + 1)"))
        assertTrue("digest reader must not retain artifact bytes", !digestReader.contains("bytearray(") && !digestReader.contains("data.extend("))
        assertTrue(
            "collector source must use the same stable repository reader",
            collector.contains("collector_bytes = stable_repo_bytes(collector_path, collector_size_limit)"),
        )
    }

    @Test
    fun `zero argument runner invalidates a stale pass before its first failing selftest`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val runner = isolated.script
        val receipt = isolated.receipt
        val fakeBin = Files.createTempDirectory("issue66-host-gate-fail-")
        val fakeBash = fakeBin.resolve("bash")
        try {
            Files.write(fakeBash, "#!/bin/sh\nexit 23\n".toByteArray())
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))
            Files.write(receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())

            val process = ProcessBuilder("/bin/bash", runner.toString())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = fakeBin.toString() + ":" + System.getenv("PATH")
                    environment()["JAVA_HOME"] = System.getProperty("java.home")
                    environment()["ANDROID_HOME"] = requireNotNull(System.getenv("ANDROID_HOME"))
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 23, process.waitFor())
            assertRunnerReceipt(isolated, "RUNNING")
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `zero argument runner fences a stale pass before JDK bound RUNNING publication`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val runner = isolated.script
        val receipt = isolated.receipt
        try {
            Files.write(receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())

            val process = ProcessBuilder("/bin/bash", runner.toString())
                .redirectErrorStream(true)
                .apply {
                    environment().remove("JAVA_HOME")
                    environment()["ANDROID_HOME"] = requireNotNull(System.getenv("ANDROID_HOME"))
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, process.waitFor())
            assertTrue(output, output.contains("JAVA_HOME must point to a reviewed JDK 17 runtime."))
            assertEquals(MACHINE_READABLE_BLOCKED, receipt.readText().trim())
            assertTrue("pre-publication failure must retain its owner lock", Files.exists(isolated.lock))
            assertTrue(
                "pre-publication failure must retain its lock owner",
                Files.isRegularFile(isolated.lock.resolve("owner")),
            )
        } finally {
            isolated.close()
        }
    }

    @Test
    fun `post replace RUNNING failure cannot expose a stale pass without its lock fence`() {
        val repo = findRepoRoot()
        val postReplaceMarker = "    committed = True\n    os.lseek(temp_fd, 0, os.SEEK_SET)"
        val isolated = isolatedRunner(repo) { source ->
            check(source.windowed(postReplaceMarker.length).count { it == postReplaceMarker } == 1) {
                "atomic receipt post-replace marker changed"
            }
            source.replace(
                postReplaceMarker,
                "    committed = True\n" +
                    "    raise OSError(\"forced post-replace failure\")\n" +
                    "    os.lseek(temp_fd, 0, os.SEEK_SET)",
            )
        }
        val fakeBin = Files.createTempDirectory("issue66-host-gate-replace-fail-")
        try {
            Files.write(isolated.receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())

            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(output, process.waitFor() != 0)
            assertTrue(output, output.contains("forced post-replace failure"))
            assertRunnerReceipt(isolated, "RUNNING")
            assertTrue("ambiguous RUNNING publication must retain its owner lock", Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `RUNNING publisher detects same inode byte mutation before releasing its lock fence`() {
        val repo = findRepoRoot()
        val postReplaceMarker = "    committed = True\n    os.lseek(temp_fd, 0, os.SEEK_SET)"
        val isolated = isolatedRunner(repo) { source ->
            check(source.windowed(postReplaceMarker.length).count { it == postReplaceMarker } == 1) {
                "atomic receipt post-replace marker changed"
            }
            source.replace(
                postReplaceMarker,
                "    committed = True\n" +
                    "    os.lseek(temp_fd, 0, os.SEEK_SET)\n" +
                    "    tampered_payload = payload.replace(b'\\\"RUNNING\\\"', b'\\\"PASS___\\\"', 1)\n" +
                    "    remaining_tampered = memoryview(tampered_payload)\n" +
                    "    while remaining_tampered:\n" +
                    "        written_tampered = os.write(temp_fd, remaining_tampered)\n" +
                    "        if written_tampered <= 0:\n" +
                    "            raise OSError(\"short injected tamper write\")\n" +
                    "        remaining_tampered = remaining_tampered[written_tampered:]\n" +
                    "    os.ftruncate(temp_fd, len(tampered_payload))\n" +
                    "    os.fsync(temp_fd)\n" +
                    "    os.lseek(temp_fd, 0, os.SEEK_SET)",
            )
        }
        val fakeBin = Files.createTempDirectory("issue66-host-gate-byte-tamper-")
        try {
            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(output, process.waitFor() != 0)
            assertTrue(
                output,
                output.contains("published receipt identity, size, bytes, or extended ACL changed"),
            )
            assertTrue(isolated.receipt.readText(), isolated.receipt.readText().contains("PASS___"))
            assertTrue("byte-ambiguous RUNNING publication must retain its lock", Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `readonly stale pass is replaced before the first failing selftest`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-gate-readonly-pass-")
        val fakeBash = fakeBin.resolve("bash")
        try {
            Files.write(fakeBash, "#!/bin/sh\nexit 23\n".toByteArray())
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))
            Files.write(isolated.receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())
            assertTrue("stale PASS must become read-only", isolated.receipt.toFile().setWritable(false))

            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 23, process.waitFor())
            assertRunnerReceipt(isolated, "RUNNING")
            assertTrue("ordinary failure must release the owned lock", !Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `failed lock owner publication preserves a stale pass behind the lock fence`() {
        val repo = findRepoRoot()
        val ownerOpenMarker =
            "    output_fd = os.open(output_name, output_flags, 0o600, dir_fd=lock_fd)"
        val isolated = isolatedRunner(repo) { source ->
            check(source.windowed(ownerOpenMarker.length).count { it == ownerOpenMarker } == 1) {
                "exclusive lock-owner open marker changed"
            }
            source.replace(
                ownerOpenMarker,
                "    os.mkdir(output_name, 0o700, dir_fd=lock_fd)\n" + ownerOpenMarker,
            )
        }
        val fakeBin = Files.createTempDirectory("issue66-host-gate-owner-fail-")
        try {
            Files.write(isolated.receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())

            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(output, process.waitFor() != 0)
            assertEquals(MACHINE_READABLE_BLOCKED, isolated.receipt.readText().trim())
            assertTrue("failed owner publication must retain the lock", Files.exists(isolated.lock))
            assertTrue(
                "owner publication failure was not reached",
                Files.isDirectory(isolated.lock.resolve("owner")),
            )
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `failed atomic replacement of a nonfile receipt leaves the host gate lock as a fence`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-gate-unlink-fail-")
        try {
            Files.createDirectory(isolated.receipt)
            val stalePass = isolated.receipt.resolve("stale-pass.json")
            Files.write(stalePass, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())

            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(output, process.waitFor() != 0)
            assertEquals(MACHINE_READABLE_BLOCKED, stalePass.readText().trim())
            assertTrue("failed atomic replacement must retain the lock", Files.exists(isolated.lock))
            assertTrue(
                "lock owner must have been published before replacement",
                Files.isRegularFile(isolated.lock.resolve("owner")),
            )
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `zero argument runner creates private state without following precreated files`() {
        val repo = findRepoRoot()
        val canonical = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val source = canonical.readText()
        val publisherStart = source.indexOf("write_receipt_atomically() {")
        val publisherEnd = source.indexOf("\n}\n\nrelease_host_gate_lock() {", publisherStart)
        assertTrue("atomic receipt publisher is missing", publisherStart >= 0 && publisherEnd > publisherStart)
        val publisher = source.substring(publisherStart, publisherEnd)
        val directoryPrep = source
            .substringAfter("prepare_private_directory() {")
            .substringBefore("\n}\n\ncreate_host_gate_lock() {")
        val lockCreator = source
            .substringAfter("create_host_gate_lock() {")
            .substringBefore("\n}\n\nwrite_private_file_exclusively() {")
        val cleanupStart = source.indexOf("release_host_gate_lock() {")
        val cleanupEnd = source.indexOf("\n}\n\ncleanup_host_gate_lock() {", cleanupStart)
        assertTrue("lock cleanup helper is missing", cleanupStart >= 0 && cleanupEnd > cleanupStart)
        val cleanup = source.substring(cleanupStart, cleanupEnd)
        val provenance = source
            .substringAfter("read_source_provenance() {")
            .substringBefore("\n}\n\nnew_run_id() {")
        val runnerReader = source
            .substringAfter("read_runner_sha256() {")
            .substringBefore("\n}\n\nwrite_receipt_atomically() {")

        assertEquals("host gate must set exactly one private umask", 1, source.lines().count { it == "umask 077" })
        assertTrue(
            "private umask must precede creation of the receipt directory",
            source.indexOf("umask 077") < source.indexOf(PINNED_RECEIPT_DIR_PREPARE),
        )
        assertTrue("pathname mkdir -p must not create receipt state", "mkdir -p \"\$receipt_dir\"" !in source)
        assertTrue(
            "receipt directories must be created by a no-follow descriptor walk and tightened to 0700",
            "directory_state.st_uid != os.geteuid()" in directoryPrep &&
                "os.mkdir(component, 0o700, dir_fd=parent_fd)" in directoryPrep &&
                "os.open(component, directory_flags, dir_fd=parent_fd)" in directoryPrep &&
                "os.fchmod(directory_fd, 0o700)" in directoryPrep &&
                "follow_symlinks=False" in directoryPrep,
        )
        assertTrue(
            "host lock itself must be created relative to the pinned receipt parent",
            "os.mkdir(lock_name, 0o700, dir_fd=parent_fd)" in lockCreator &&
                "os.open(lock_name, directory_flags, dir_fd=parent_fd)" in lockCreator,
        )
        assertTrue(
            "host gate state writer must use fixed isolated Python",
            "/usr/bin/python3 -I -" in source,
        )
        assertTrue(
            "receipt publisher itself must reserve a private read-write no-follow temp",
            "temp_flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW" in publisher,
        )
        assertTrue(
            "receipt publisher itself must force private temp mode",
            "os.fchmod(temp_fd, 0o600)" in publisher,
        )
        assertTrue(
            "receipt commit must stay relative to one pinned parent descriptor",
            "os.replace(" in publisher &&
                "src_dir_fd=parent_fd" in publisher &&
                "dst_dir_fd=parent_fd" in publisher,
        )
        assertTrue(
            "post-publication check must bind destination identity and exact open-FD bytes",
            "file_identity(receipt_state) != file_identity(published_fd_state)" in publisher &&
                "published_bytes != payload" in publisher &&
                "published_fd_state.st_size != len(payload)" in publisher,
        )
        assertTrue(
            "failed publication may unlink only the temp inode it created",
            "dev_inode(current_temp_state) == temp_identity" in publisher &&
                "os.unlink(temp_name, dir_fd=parent_fd)" in publisher,
        )
        assertTrue(
            "lock cleanup must bind parent, lock, owner, and current receipt identity plus exact bytes",
            "len(expected_identity) != 6" in cleanup &&
                "len(expected_receipt_identity) != 7" in cleanup &&
                "os.O_NOFOLLOW" in cleanup &&
                "parent_state.st_uid != os.geteuid()" in cleanup &&
                "stat.S_IMODE(parent_state.st_mode) != 0o700" in cleanup &&
                "read_owner(owner_fd, len(expected_owner)) != expected_owner" in cleanup &&
                "file_identity(receipt_state) != expected_receipt_identity" in cleanup &&
                "read_owner(receipt_fd, len(expected_receipt)) != expected_receipt" in cleanup,
        )
        assertTrue(
            "source provenance must bind raw HEAD, index, and no-follow worktree bytes and modes",
                "/usr/bin/python3 -I - \"\$repo_root\" <<'PY'" in provenance &&
                "/usr/bin/git" in provenance &&
                "--no-replace-objects" in provenance &&
                "\"GIT_CONFIG_NOSYSTEM\": \"1\"" in provenance &&
                "\"GIT_CONFIG_GLOBAL\": \"/dev/null\"" in provenance &&
                "git_output(\"rev-parse\", \"--show-toplevel\")" in provenance &&
                "ls-tree\", \"-rz\", \"--full-tree" in provenance &&
                "ls-files\", \"--stage\", \"-v\", \"-z" in provenance &&
                "parse_index(index) != head_entries" in provenance &&
                "os.O_DIRECTORY" in provenance &&
                "os.O_NOFOLLOW" in provenance &&
                "dir_fd=parent_fd" in provenance &&
                "b\"blob \" + str(len(payload)).encode(\"ascii\") + b\"\\0\"" in provenance &&
                "hashlib.sha1(usedforsecurity=False)" in provenance &&
                "b\"120000\"" in provenance &&
                "os.readlink(" in provenance &&
                "--exclude-per-directory=.gitignore" in provenance &&
                "--exclude-standard" !in provenance,
        )
        assertTrue(
            "raw source traversal must reject foreign-owned or writable roots, directories, and files",
            provenance.windowed("st_uid != os.geteuid()".length)
                .count { it == "st_uid != os.geteuid()" } >= 3 &&
                provenance.windowed("stat.S_IMODE(".length)
                    .count { it == "stat.S_IMODE(" } >= 3,
        )
        assertTrue(
            "raw source traversal must inspect pinned descriptor ACLs before and after reads",
            "darwin_libc.acl_get_fd_np" in provenance &&
                "attributes = os.listxattr(descriptor_fd)" in provenance &&
                "os.fsdecode(attribute)" in provenance &&
                provenance.windowed("fd_has_extended_acl(directory_fd)".length)
                    .count { it == "fd_has_extended_acl(directory_fd)" } == 2 &&
                provenance.windowed("fd_has_extended_acl(file_fd)".length)
                    .count { it == "fd_has_extended_acl(file_fd)" } == 2,
        )
        assertTrue(
            "runner digest must bind a current-user non-writable regular file and parent",
            "runner_state.st_uid != os.geteuid()" in runnerReader &&
                "parent_state.st_uid != os.geteuid()" in runnerReader &&
                "stat.S_IMODE(runner_state.st_mode) & 0o022" in runnerReader &&
                "stat.S_IMODE(parent_state.st_mode) & 0o022" in runnerReader,
        )
        assertTrue(
            "predictable shell-redirection receipt temp is forbidden",
            "\$receipt_path.tmp.\$\$" !in source,
        )
        assertTrue("receipt publication must not delegate to ambient mv", !Regex("(?m)^\\s*mv\\b").containsMatchIn(source))
        assertTrue("RUNNING publication must atomically replace an old PASS", "/bin/rm -f -- \"\$receipt_path\"" !in source)

        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-private-state-")
        val fakeBash = fakeBin.resolve("bash")
        val holderReady = fakeBin.resolve("holder-ready")
        val holderRelease = fakeBin.resolve("holder-release")
        var holder: Process? = null
        try {
            Files.write(
                fakeBash,
                (
                    "#!/bin/sh\n" +
                        "/usr/bin/touch \"\$HOST_GATE_HOLDER_READY\"\n" +
                        "while [ ! -e \"\$HOST_GATE_HOLDER_RELEASE\" ]; do /bin/sleep 0.02; done\n" +
                        "exit 23\n"
                    ).toByteArray(),
            )
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))
            Files.setPosixFilePermissions(
                isolated.stateDir,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"),
            )
            holder = ProcessBuilder(
                "/bin/bash",
                "-c",
                "umask 000; exec /bin/bash \"\$1\"",
                "host-gate-private-state",
                isolated.script.toString(),
            ).redirectErrorStream(true).apply {
                environment()["PATH"] = fakeBin.toString() + ":" + System.getenv("PATH")
                environment()["JAVA_HOME"] = System.getProperty("java.home")
                environment()["ANDROID_HOME"] = requireNotNull(System.getenv("ANDROID_HOME"))
                environment()["HOST_GATE_HOLDER_READY"] = holderReady.toString()
                environment()["HOST_GATE_HOLDER_RELEASE"] = holderRelease.toString()
            }.start()
            waitForPath(holderReady)

            assertEquals(
                "[OWNER_EXECUTE, OWNER_READ, OWNER_WRITE]",
                Files.getPosixFilePermissions(isolated.stateDir).sortedBy { it.name }.toString(),
            )
            assertEquals(
                "[OWNER_EXECUTE, OWNER_READ, OWNER_WRITE]",
                Files.getPosixFilePermissions(isolated.lock).sortedBy { it.name }.toString(),
            )
            assertEquals(
                "[OWNER_READ, OWNER_WRITE]",
                Files.getPosixFilePermissions(isolated.lock.resolve("owner"))
                    .sortedBy { it.name }.toString(),
            )
            assertEquals(
                "[OWNER_READ, OWNER_WRITE]",
                Files.getPosixFilePermissions(isolated.receipt).sortedBy { it.name }.toString(),
            )

            Files.createFile(holderRelease)
            val output = holder.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 23, holder.waitFor())
            assertTrue("ordinary failure must release its private lock", !Files.exists(isolated.lock))
        } finally {
            if (!Files.exists(holderRelease)) Files.createFile(holderRelease)
            holder?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `runner refuses intermediate receipt directory symlinks without touching their targets`() {
        val repo = findRepoRoot()
        val canonical = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        listOf("build", "reports").forEach { symlinkComponent ->
            val isolatedRepo = Files.createTempDirectory("issue66-host-gate-intermediate-symlink-")
            val scriptDir = isolatedRepo.resolve("integration-tests/pr63-on-issue66")
            val harnessDir = scriptDir.resolve("harness")
            val external = Files.createTempDirectory("issue66-host-gate-external-state-")
            try {
                Files.createDirectories(harnessDir)
                if (symlinkComponent == "build") {
                    Files.createSymbolicLink(harnessDir.resolve("build"), external)
                } else {
                    Files.createDirectory(harnessDir.resolve("build"))
                    Files.createSymbolicLink(harnessDir.resolve("build/reports"), external)
                }
                val runner = scriptDir.resolve("run-host-gate.sh")
                Files.copy(canonical, runner)
                check(runner.toFile().setExecutable(true, true)) {
                    "intermediate-symlink fixture runner must be executable"
                }

                val process = ProcessBuilder("/bin/bash", runner.toString())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(output, 1, process.waitFor())
                assertTrue(output, output.contains("receipt directory is not private"))
                assertEquals(
                    "intermediate $symlinkComponent symlink redirected host-gate state outside the repo",
                    emptyList<String>(),
                    Files.list(external).use { entries ->
                        entries.map { it.fileName.toString() }.sorted().toList()
                    },
                )
            } finally {
                isolatedRepo.toFile().deleteRecursively()
                external.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `runner source provenance accepts only the exact clean repository root`() {
        val canonical = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val functionStart = canonical.indexOf("read_source_provenance() {")
        val functionEnd = canonical.indexOf("\n}\n\nnew_run_id() {", functionStart)
        assertTrue("source provenance helper is missing", functionStart >= 0 && functionEnd > functionStart)
        val functionSource = canonical.substring(functionStart, functionEnd + 2)
        val stateRoot = Files.createTempDirectory("issue66-source-provenance-").toRealPath()
        val repo = stateRoot.resolve("repo")
        val probe = stateRoot.resolve("probe.sh")

        fun git(vararg arguments: String): String {
            val process = ProcessBuilder("/usr/bin/git", "-C", repo.toString(), *arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            return output.trim()
        }

        fun probe(repoRoot: Path): Pair<Int, String> {
            val process = ProcessBuilder("/bin/bash", probe.toString(), repoRoot.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output.trim()
        }

        try {
            Files.createDirectory(repo)
            git("init")
            git("config", "user.name", "Host Gate Test")
            git("config", "user.email", "host-gate@example.invalid")
            Files.write(repo.resolve("tracked.txt"), "clean\n".toByteArray())
            Files.createDirectory(repo.resolve("tracked-dir"))
            Files.write(repo.resolve("tracked-dir/nested.txt"), "nested\n".toByteArray())
            Files.write(repo.resolve(".gitignore"), "ignored/\n".toByteArray())
            Files.createSymbolicLink(repo.resolve("tracked-link"), Paths.get("tracked.txt"))
            git("add", "tracked.txt", "tracked-dir/nested.txt", "tracked-link", ".gitignore")
            git("-c", "commit.gpgSign=false", "commit", "-m", "fixture")
            Files.createDirectories(repo.resolve("ignored"))
            Files.write(repo.resolve("ignored/generated.txt"), "ignored\n".toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -euo pipefail\n" +
                        "repo_root=\"\$1\"\n" +
                        functionSource + "\n" +
                        "read_source_provenance\n"
                    ).toByteArray(),
            )

            val expected = git("rev-parse", "HEAD^{commit}") + ":" + git("rev-parse", "HEAD^{tree}")
            val clean = probe(repo)
            assertEquals(clean.second, 0, clean.first)
            assertEquals(expected, clean.second)

            val nested = Files.createDirectory(repo.resolve("nested"))
            val wrongRoot = probe(nested)
            assertTrue(wrongRoot.second, wrongRoot.first != 0)
            Files.delete(nested)

            Files.write(repo.resolve("untracked.txt"), "dirty\n".toByteArray())
            val dirty = probe(repo)
            assertTrue(dirty.second, dirty.first != 0)
            Files.delete(repo.resolve("untracked.txt"))

            val escaped = mutableListOf<String>()
            val tracked = repo.resolve("tracked.txt")
            git("config", "core.trustctime", "false")
            git("config", "core.checkStat", "minimal")
            git("update-index", "--refresh")
            val cachedMtime = Files.getLastModifiedTime(tracked)
            Files.write(tracked, "dirty\n".toByteArray())
            Files.setLastModifiedTime(tracked, cachedMtime)
            if (probe(repo).first == 0) escaped += "trustctime/checkStat same-size restored-mtime"

            Files.write(tracked, "clean\n".toByteArray())
            git("config", "core.trustctime", "true")
            git("config", "core.checkStat", "default")
            git("update-index", "--refresh")
            git("config", "filter.host-gate-test.clean", "/usr/bin/sed s/dirty/clean/g")
            git("config", "filter.host-gate-test.required", "true")
            val attributes = repo.resolve(".git/info/attributes")
            Files.write(attributes, "tracked.txt filter=host-gate-test\n".toByteArray())
            Files.write(tracked, "dirty\n".toByteArray())
            Files.setLastModifiedTime(
                tracked,
                FileTime.fromMillis(Files.getLastModifiedTime(tracked).toMillis() + 2_000),
            )
            if (probe(repo).first == 0) escaped += ".git info attributes clean filter"

            Files.write(tracked, "clean\n".toByteArray())
            Files.delete(attributes)
            git("update-index", "--refresh")
            git("config", "core.fileMode", "false")
            val originalPermissions = Files.getPosixFilePermissions(tracked)
            Files.setPosixFilePermissions(
                tracked,
                originalPermissions + PosixFilePermission.OWNER_EXECUTE,
            )
            if (probe(repo).first == 0) escaped += "core fileMode executable-bit"
            Files.setPosixFilePermissions(tracked, originalPermissions)

            val infoExclude = repo.resolve(".git/info/exclude")
            Files.write(infoExclude, "hidden-by-info-exclude.txt\n".toByteArray())
            Files.write(repo.resolve("hidden-by-info-exclude.txt"), "hidden\n".toByteArray())
            if (probe(repo).first == 0) escaped += ".git info exclude"

            Files.delete(repo.resolve("hidden-by-info-exclude.txt"))
            git("update-index", "--chmod=+x", "tracked.txt")
            if (probe(repo).first == 0) escaped += "HEAD-index executable mode mismatch"
            git("update-index", "--chmod=-x", "tracked.txt")

            val repoPermissions = Files.getPosixFilePermissions(repo)
            Files.setPosixFilePermissions(repo, repoPermissions + PosixFilePermission.GROUP_WRITE)
            if (probe(repo).first == 0) escaped += "group-writable repository root"
            Files.setPosixFilePermissions(repo, repoPermissions)

            val trackedDirectory = repo.resolve("tracked-dir")
            val directoryPermissions = Files.getPosixFilePermissions(trackedDirectory)
            Files.setPosixFilePermissions(
                trackedDirectory,
                directoryPermissions + PosixFilePermission.GROUP_WRITE,
            )
            if (probe(repo).first == 0) escaped += "group-writable tracked parent"
            Files.setPosixFilePermissions(trackedDirectory, directoryPermissions)

            Files.setPosixFilePermissions(
                tracked,
                originalPermissions + PosixFilePermission.GROUP_WRITE,
            )
            if (probe(repo).first == 0) escaped += "group-writable tracked regular file"
            Files.setPosixFilePermissions(tracked, originalPermissions)

            assertEquals("raw source-provenance bypasses escaped", emptyList<String>(), escaped)

            Files.delete(repo.resolve("tracked-link"))
            Files.createSymbolicLink(repo.resolve("tracked-link"), Paths.get("changed.txt"))
            val changedSymlink = probe(repo)
            assertTrue(changedSymlink.second, changedSymlink.first != 0)
        } finally {
            stateRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `runner source provenance rejects a Darwin ACL on the pinned root`() {
        assumeTrue("Darwin ACL semantics are required", isDarwin())
        assertDarwinSourceAclRejected(null)
    }

    @Test
    fun `runner source provenance rejects a Darwin ACL on a tracked parent`() {
        assumeTrue("Darwin ACL semantics are required", isDarwin())
        assertDarwinSourceAclRejected("tracked-dir")
    }

    @Test
    fun `runner source provenance rejects a Darwin ACL on a tracked regular file`() {
        assumeTrue("Darwin ACL semantics are required", isDarwin())
        assertDarwinSourceAclRejected("tracked-dir/nested.txt")
    }

    @Test
    fun `runner source provenance rechecks Darwin ACLs after its initial raw scan`() {
        assumeTrue("Darwin ACL semantics are required", isDarwin())
        assertDarwinSourceAclRejected(
            relativeTarget = "tracked-dir/nested.txt",
            injectBeforeConfirmedScan = true,
        )
    }

    @Test
    fun `runner cannot substitute a forged pass between temp creation and receipt publication`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-receipt-substitution-")
        val fakeBash = fakeBin.resolve("bash")
        val fakeMv = fakeBin.resolve("mv")
        val forgedPass = fakeBin.resolve("forged-pass.json")
        val mvInvoked = fakeBin.resolve("mv-invoked")
        try {
            Files.write(fakeBash, "#!/bin/sh\nexit 23\n".toByteArray())
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))
            Files.write(forgedPass, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())
            Files.write(
                fakeMv,
                (
                    "#!/bin/sh\n" +
                        "/usr/bin/touch \"\$HOST_GATE_MV_INVOKED\"\n" +
                        "if [ \"\$#\" -eq 3 ] && [ \"\$1\" = -f ]; then\n" +
                        "  /bin/rm -f -- \"\$2\"\n" +
                        "  /bin/cp \"\$HOST_GATE_FORGED_PASS\" \"\$2\"\n" +
                        "fi\n" +
                        "exec /bin/mv \"\$@\"\n"
                    ).toByteArray(),
            )
            assertTrue("fake mv must be executable", fakeMv.toFile().setExecutable(true))

            val process = hostGateProcess(
                isolated.script,
                fakeBin,
                mapOf(
                    "HOST_GATE_FORGED_PASS" to forgedPass.toString(),
                    "HOST_GATE_MV_INVOKED" to mvInvoked.toString(),
                ),
            ).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 23, process.waitFor())
            assertEquals(
                "a substituted PASS escaped the RUNNING publication transaction",
                "RUNNING",
                Regex("\\\"hostIntegration\\\":\\\"([^\"]+)\\\"")
                    .find(isolated.receipt.readText())?.groupValues?.get(1),
            )
            assertTrue("receipt publication delegated its commit to ambient mv", !Files.exists(mvInvoked))
            assertTrue("ordinary failure must release the owned lock", !Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `aggregate validator rejects a pass while the host gate lock exists`() {
        val repo = findRepoRoot()
        val verifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        val functionStart = verifier.indexOf("verify_host_receipt() {")
        val functionEndMarker = "\n}\n\nprintf 'verify-a-plus: stage=%s\\n'"
        val functionEnd = verifier.indexOf(functionEndMarker, functionStart)
        assertTrue("host receipt validator function is missing", functionStart >= 0)
        assertTrue("host receipt validator end marker is missing", functionEnd >= 0)
        val functionSource = verifier.substring(functionStart, functionEnd + 2)
        assertTrue(
            "validator must atomically own the runner lock while reading the receipt",
            functionSource.contains("os.mkdir(lock_name, 0o700, dir_fd=parent_fd)"),
        )
        assertEquals(emptyList<String>(), aggregateReceiptSurfaceViolations(verifier))
        listOf(
            verifier + "\n: >\"\$HOST_RECEIPT\"\n",
            verifier +
                "\nreceipt_alias=\"\$HOST_RECEIPT\"\n" +
                "printf bad >\"\$receipt_alias\"\n",
            verifier + "\nprintf bad >\"$PINNED_HOST_RECEIPT_PATH\"\n",
            verifier.replace(
                PINNED_HOST_LOCK_DERIVATION,
                "HOST_RECEIPT_LOCK=\"host-gate-validator.lock\"",
            ),
            verifier + "\nHOST_RECEIPT=\"/other/receipt.json\"\n",
            verifier + "\nHOST_RECEIPT_LOCK=\"/other/host-gate.lock\"\n",
            verifier + "\n  HOST_RECEIPT=\"/other/receipt.json\"\n",
            verifier + "\nexport HOST_RECEIPT_LOCK=\"/other/host-gate.lock\"\n",
            verifier + "\nHOST_RECEIPT+=.bak\n",
            verifier.replace("readonly HOST_RECEIPT=", "HOST_RECEIPT="),
            verifier.replace("readonly HOST_RECEIPT_LOCK=", "HOST_RECEIPT_LOCK="),
        ).forEachIndexed { index, mutated ->
            assertTrue("aggregate receipt mutation $index is a no-op", mutated != verifier)
            assertTrue(
                "aggregate receipt mutation $index escaped",
                aggregateReceiptSurfaceViolations(mutated).isNotEmpty(),
            )
        }

        val stateDir = Files.createTempDirectory("issue66-host-receipt-validator-").toRealPath()
        val binding = createValidatorBinding(stateDir)
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt.sh")
        try {
            Files.write(receipt, (binding.receipt("PASS") + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )

            val unlocked = validatorProcessBuilder(probe, receipt, lock, binding).start()
            val unlockedOutput = unlocked.inputStream.bufferedReader().use { it.readText() }
            assertEquals(unlockedOutput, 0, unlocked.waitFor())
            assertTrue(unlockedOutput, unlockedOutput.contains("receipt: VALID"))
            assertTrue("validator leaked its owned lock", !Files.exists(lock))

            Files.createDirectory(lock)
            val preexistingOwner = lock.resolve("owner")
            Files.write(preexistingOwner, "preexisting-owner\n".toByteArray())
            val preexistingOwnerKey = Files.readAttributes(
                preexistingOwner,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            val locked = validatorProcessBuilder(probe, receipt, lock, binding).start()
            val lockedOutput = locked.inputStream.bufferedReader().use { it.readText() }
            assertEquals(lockedOutput, 1, locked.waitFor())
            assertTrue(lockedOutput, lockedOutput.contains("host-gate lock"))
            assertTrue("validator removed a lock it did not own", Files.exists(lock))
            assertEquals("preexisting-owner", preexistingOwner.readText().trim())
            assertEquals(
                preexistingOwnerKey,
                Files.readAttributes(
                    preexistingOwner,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey(),
            )

            Files.delete(preexistingOwner)
            Files.delete(lock)
            Files.write(receipt, "{malformed\n".toByteArray())
            val malformed = validatorProcessBuilder(probe, receipt, lock, binding).start()
            val malformedOutput = malformed.inputStream.bufferedReader().use { it.readText() }
            assertEquals(malformedOutput, 1, malformed.waitFor())
            assertTrue(malformedOutput, malformedOutput.contains("invalid host-gate JSON receipt"))
            assertTrue("failed validation leaked its owned lock", !Files.exists(lock))

            Files.write(receipt, (binding.receipt("RUNNING") + "\n").toByteArray())
            val running = validatorProcessBuilder(probe, receipt, lock, binding).start()
            val runningOutput = running.inputStream.bufferedReader().use { it.readText() }
            assertEquals(runningOutput, 1, running.waitFor())
            assertTrue(runningOutput, runningOutput.contains("receipt schema mismatch"))
            assertTrue("contract failure leaked its owned lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate validator rejects duplicate and extra receipt fields`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val stateDir = Files.createTempDirectory("issue66-host-receipt-schema-").toRealPath()
        val binding = createValidatorBinding(stateDir)
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt.sh")
        try {
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            val validReceipt = binding.receipt("PASS")
            val invalidReceipts = listOf(
                "duplicate key" to validReceipt.dropLast(1) +
                    ",\"overall\":\"BLOCKED\"}",
                "extra field" to validReceipt.dropLast(1) +
                    ",\"devicePass\":true}",
                "numeric type substitution" to validReceipt.replace(
                    "\"schemaVersion\":4",
                    "\"schemaVersion\":4.0",
                ),
            )
            invalidReceipts.forEach { (label, payload) ->
                Files.write(receipt, (payload + "\n").toByteArray())
                val validation = validatorProcessBuilder(probe, receipt, lock, binding).start()
                val output = validation.inputStream.bufferedReader().use { it.readText() }
                assertEquals("$label escaped:\n$output", 1, validation.waitFor())
                assertTrue(output, output.contains("receipt schema mismatch"))
                assertTrue("$label leaked the validator lock", !Files.exists(lock))
            }
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate validator rejects a symlink receipt`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        assertTrue(
            "receipt open must use no-follow semantics",
            functionSource.contains("receipt_flags") &&
                functionSource.contains("os.O_NOFOLLOW") &&
                functionSource.contains("os.O_NONBLOCK"),
        )
        assertTrue(
            "receipt descriptor must be proven regular",
            functionSource.contains("receipt_fd_state") &&
                functionSource.contains("stat.S_ISREG(receipt_fd_state.st_mode)"),
        )

        val stateDir = Files.createTempDirectory("issue66-host-receipt-symlink-").toRealPath()
        val binding = createValidatorBinding(stateDir)
        val target = stateDir.resolve("valid-target.json")
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt.sh")
        try {
            Files.write(target, (binding.receipt("PASS") + "\n").toByteArray())
            Files.createSymbolicLink(receipt, target.fileName)
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            val validation = validatorProcessBuilder(probe, receipt, lock, binding).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, output.contains("invalid host-gate JSON receipt"))
            assertTrue("symlink rejection leaked the validator lock", !Files.exists(lock))

            Files.delete(receipt)
            val fifo = stateDir.resolve("host-gate-receipt.fifo")
            val mkfifo = ProcessBuilder("/usr/bin/mkfifo", fifo.toString())
                .redirectErrorStream(true)
                .start()
            val mkfifoOutput = mkfifo.inputStream.bufferedReader().use { it.readText() }
            assertEquals(mkfifoOutput, 0, mkfifo.waitFor())
            val fifoValidation = validatorProcessBuilder(probe, fifo, lock, binding).start()
            assertTrue("FIFO receipt open blocked", fifoValidation.waitFor(5, TimeUnit.SECONDS))
            val fifoOutput = fifoValidation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(fifoOutput, 1, fifoValidation.exitValue())
            assertTrue(fifoOutput, fifoOutput.contains("invalid host-gate JSON receipt"))
            assertTrue("FIFO rejection leaked the validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate validator rejects a receipt inode replacement during read`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val oldOpenMarker = "    with open(receipt_path, encoding=\"utf-8\") as receipt_file:\n"
        val pinnedOpenMarker =
            "    receipt_fd = os.open(receipt_name, receipt_flags, dir_fd=parent_fd)\n"
        val openMarker = when {
            oldOpenMarker in functionSource -> oldOpenMarker
            pinnedOpenMarker in functionSource -> pinnedOpenMarker
            else -> error("host receipt open marker changed")
        }
        val indent = if (openMarker == oldOpenMarker) "        " else "    "
        val rendezvous = openMarker +
            indent + "with open(os.environ[\"HOST_RECEIPT_READ_READY\"], \"w\") as ready_file:\n" +
            indent + "    ready_file.write(\"ready\\n\")\n" +
            indent + "while not os.path.exists(os.environ[\"HOST_RECEIPT_READ_RELEASE\"]):\n" +
            indent + "    __import__(\"time\").sleep(0.01)\n"
        val instrumented = functionSource.replace(openMarker, rendezvous)
        assertTrue("receipt-read rendezvous mutation is a no-op", instrumented != functionSource)

        val stateDir = Files.createTempDirectory("issue66-host-receipt-swap-").toRealPath()
        val binding = createValidatorBinding(stateDir)
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt-rendezvous.sh")
        val ready = stateDir.resolve("receipt-read-ready")
        val release = stateDir.resolve("receipt-read-release")
        var validator: Process? = null
        try {
            Files.write(receipt, (binding.receipt("PASS") + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        instrumented + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            validator = validatorProcessBuilder(probe, receipt, lock, binding).apply {
                environment()["HOST_RECEIPT_READ_READY"] = ready.toString()
                environment()["HOST_RECEIPT_READ_RELEASE"] = release.toString()
            }.start()
            waitForPath(ready)
            val originalKey = Files.readAttributes(
                receipt,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            Files.delete(receipt)
            Files.write(receipt, (binding.receipt("PASS") + "\n").toByteArray())
            val replacementKey = Files.readAttributes(
                receipt,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            assertTrue("receipt inode replacement was not reached", originalKey != replacementKey)
            Files.createFile(release)

            val output = validator.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validator.waitFor())
            assertTrue(output, output.contains("receipt identity changed"))
            assertTrue("receipt replacement rejection leaked the validator lock", !Files.exists(lock))
        } finally {
            if (!Files.exists(release)) Files.createFile(release)
            validator?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            stateDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate validator rechecks the receipt after contract validation`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        val recheckMarker = if ("receipt_recheck_error = None\n" in functionSource) {
            "receipt_recheck_error = None\n"
        } else {
            "cleanup_error = None\n"
        }
        val rendezvous =
            "with open(os.environ[\"HOST_RECEIPT_POST_CONTRACT_READY\"], \"w\") as ready_file:\n" +
                "    ready_file.write(\"ready\\n\")\n" +
                "while not os.path.exists(os.environ[\"HOST_RECEIPT_POST_CONTRACT_RELEASE\"]):\n" +
                "    __import__(\"time\").sleep(0.01)\n" +
                recheckMarker
        val instrumented = functionSource.replace(recheckMarker, rendezvous)
        assertTrue("post-contract rendezvous mutation is a no-op", instrumented != functionSource)

        val stateDir = Files.createTempDirectory("issue66-host-receipt-post-contract-").toRealPath()
        val binding = createValidatorBinding(stateDir)
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt-rendezvous.sh")
        val ready = stateDir.resolve("post-contract-ready")
        val release = stateDir.resolve("post-contract-release")
        var validator: Process? = null
        try {
            Files.write(receipt, (binding.receipt("PASS") + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        instrumented + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            validator = validatorProcessBuilder(probe, receipt, lock, binding).apply {
                environment()["HOST_RECEIPT_POST_CONTRACT_READY"] = ready.toString()
                environment()["HOST_RECEIPT_POST_CONTRACT_RELEASE"] = release.toString()
            }.start()
            waitForPath(ready)
            val originalKey = Files.readAttributes(
                receipt,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            Files.delete(receipt)
            Files.write(receipt, (binding.receipt("PASS") + "\n").toByteArray())
            val replacementKey = Files.readAttributes(
                receipt,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            assertTrue("post-contract inode replacement was not reached", originalKey != replacementKey)
            Files.createFile(release)

            val output = validator.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validator.waitFor())
            assertTrue(output, output.contains("receipt identity changed"))
            assertTrue("post-contract rejection leaked the validator lock", !Files.exists(lock))
        } finally {
            if (!Files.exists(release)) Files.createFile(release)
            validator?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            stateDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate validator isolates its fixed Python parser`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        assertTrue(
            "aggregate receipt parsing must use the fixed isolated Python runtime",
            functionSource.contains("/usr/bin/python3 -I -"),
        )

        val stateDir = Files.createTempDirectory("issue66-host-receipt-python-shadow-").toRealPath()
        val binding = createValidatorBinding(stateDir)
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt.sh")
        val shadowMarker = stateDir.resolve("python-shadow-executed")
        try {
            Files.write(receipt, (binding.receipt("PASS") + "\n").toByteArray())
            Files.write(
                stateDir.resolve("json.py"),
                (
                    "open(${shadowMarker.toString().quoteForPython()}, \"w\").write(\"executed\")\n" +
                        "raise SystemExit(0)\n"
                    ).toByteArray(),
            )
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            val validation = validatorProcessBuilder(probe, receipt, lock, binding)
                .directory(stateDir.toFile()).apply {
                environment()["PYTHONPATH"] = stateDir.toString()
            }.start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, validation.waitFor())
            assertTrue(output, output.contains("receipt: VALID"))
            assertTrue("ambient json.py replaced the receipt parser", !Files.exists(shadowMarker))
            assertTrue("isolated parser leaked the validator lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate validator pins one nofollow parent for receipt and lock`() {
        val repo = findRepoRoot()
        val functionSource = verifyHostReceiptFunction(repo)
        assertTrue(
            "receipt and lock must share a parent reached by a no-follow component walk",
            functionSource.contains("def open_directory_nofollow(directory_path):") &&
                functionSource.contains("next_fd = os.open(component, flags, dir_fd=current_fd)") &&
                functionSource.contains("parent_fd = open_directory_nofollow(parent_path)") &&
                functionSource.contains("dir_fd=parent_fd") &&
                functionSource.contains("receipt_name") &&
                functionSource.contains("lock_name"),
        )

        val stateRoot = Files.createTempDirectory("issue66-host-receipt-parent-").toRealPath()
        val binding = createValidatorBinding(stateRoot)
        val realParent = stateRoot.resolve("real-parent")
        val aliasParent = stateRoot.resolve("alias-parent")
        val receipt = aliasParent.resolve("host-gate-receipt.json")
        val lock = aliasParent.resolve("host-gate.lock")
        val probe = stateRoot.resolve("verify-host-receipt.sh")
        try {
            Files.createDirectory(realParent)
            Files.write(
                realParent.resolve("host-gate-receipt.json"),
                (binding.receipt("PASS", realParent) + "\n").toByteArray(),
            )
            Files.createSymbolicLink(aliasParent, realParent.fileName)
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            val validation = validatorProcessBuilder(probe, receipt, lock, binding).start()
            val output = validation.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, validation.waitFor())
            assertTrue(output, output.contains("cannot pin host-gate receipt parent"))
            assertTrue(
                "parent-symlink rejection created a lock through the alias",
                !Files.exists(realParent.resolve("host-gate.lock")),
            )
        } finally {
            stateRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aggregate validator owns the shared lock throughout receipt parsing`() {
        val repo = findRepoRoot()
        val verifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        val functionStart = verifier.indexOf("verify_host_receipt() {")
        val functionEndMarker = "\n}\n\nprintf 'verify-a-plus: stage=%s\\n'"
        val functionEnd = verifier.indexOf(functionEndMarker, functionStart)
        assertTrue("host receipt validator function is missing", functionStart >= 0)
        assertTrue("host receipt validator end marker is missing", functionEnd >= 0)
        val functionSource = verifier.substring(functionStart, functionEnd + 2)
        val helperMarker = "import sys\n"
        val helper =
            "import sys\n\n" +
                "def receipt_lock_rendezvous(stage):\n" +
                "    prefix = os.environ[\"HOST_RECEIPT_VALIDATOR_RENDEZVOUS\"]\n" +
                "    ready_path = prefix + \"-\" + stage + \"-ready\"\n" +
                "    release_path = prefix + \"-\" + stage + \"-release\"\n" +
                "    with open(ready_path, \"w\") as ready_file:\n" +
                "        ready_file.write(\"ready\\n\")\n" +
                "    while not os.path.exists(release_path):\n" +
                "        __import__(\"time\").sleep(0.01)\n"
        val withHelper = functionSource.replace(helperMarker, helper)
        assertTrue("validator rendezvous helper mutation is a no-op", withHelper != functionSource)
        val preOpenMarker = "validation_error = None\n"
        val preLoadMarker = "    receipt_text = receipt_bytes.decode(\"utf-8\")"
        val postContractMarker = "cleanup_error = None\n"
        val withPreOpen = withHelper.replace(
            preOpenMarker,
            "receipt_lock_rendezvous(\"pre-open\")\n" + preOpenMarker,
        )
        val withPreLoad = withPreOpen.replace(
            preLoadMarker,
            "    receipt_lock_rendezvous(\"pre-load\")\n" + preLoadMarker,
        )
        val instrumented = withPreLoad.replace(
            postContractMarker,
            "receipt_lock_rendezvous(\"post-contract\")\n" + postContractMarker,
        )
        assertTrue("pre-open rendezvous mutation is a no-op", withPreOpen != withHelper)
        assertTrue("pre-load rendezvous mutation is a no-op", withPreLoad != withPreOpen)
        assertTrue("post-contract rendezvous mutation is a no-op", instrumented != withPreLoad)

        val isolated = isolatedRunner(repo)
        val binding = createValidatorBinding(isolated.stateDir)
        val fakeBin = Files.createTempDirectory("issue66-host-validator-lifetime-")
        val probe = isolated.stateDir.resolve("verify-host-receipt-rendezvous.sh")
        val rendezvousPrefix = isolated.stateDir.resolve("validator")
        val stages = listOf("pre-open", "pre-load", "post-contract")
        var validator: Process? = null
        try {
            Files.write(isolated.receipt, (binding.receipt("PASS") + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        instrumented + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                    ).toByteArray(),
            )
            validator = validatorProcessBuilder(
                probe,
                isolated.receipt,
                isolated.lock,
                binding,
            ).apply {
                environment()["HOST_RECEIPT_VALIDATOR_RENDEZVOUS"] = rendezvousPrefix.toString()
            }.start()

            var pinnedOwnerKey: Any? = null
            var pinnedOwnerToken: String? = null
            stages.forEach { stage ->
                val ready = Paths.get("$rendezvousPrefix-$stage-ready")
                val release = Paths.get("$rendezvousPrefix-$stage-release")
                waitForPath(ready)

                val owner = isolated.lock.resolve("owner")
                assertTrue("$stage did not hold the shared lock", Files.isRegularFile(owner))
                val ownerKey = Files.readAttributes(
                    owner,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey()
                val ownerToken = owner.readText()
                assertTrue("$stage validator owner token is missing", ownerToken.contains("validator-pid="))
                if (pinnedOwnerToken == null) {
                    pinnedOwnerKey = ownerKey
                    pinnedOwnerToken = ownerToken
                } else {
                    assertEquals("$stage changed the lock-owner inode", pinnedOwnerKey, ownerKey)
                    assertEquals("$stage changed the lock-owner token", pinnedOwnerToken, ownerToken)
                }

                val contender = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
                val contenderOutput = contender.inputStream.bufferedReader().use { it.readText() }
                assertEquals(contenderOutput, HOST_GATE_ALREADY_RUNNING_EXIT, contender.waitFor())
                assertTrue(contenderOutput, contenderOutput.contains("already running"))
                assertEquals(binding.receipt("PASS"), isolated.receipt.readText().trim())
                Files.createFile(release)
            }

            val validatorOutput = validator.inputStream.bufferedReader().use { it.readText() }
            assertEquals(validatorOutput, 0, validator.waitFor())
            assertTrue(validatorOutput, validatorOutput.contains("receipt: VALID"))
            assertTrue("validator leaked its owned lock", !Files.exists(isolated.lock))
        } finally {
            stages.forEach { stage ->
                val release = Paths.get("$rendezvousPrefix-$stage-release")
                if (!Files.exists(release)) Files.createFile(release)
            }
            validator?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `aggregate validator retains replaced owner identity and token as foreign lock fences`() {
        val repo = findRepoRoot()
        val verifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        val functionStart = verifier.indexOf("verify_host_receipt() {")
        val functionEndMarker = "\n}\n\nprintf 'verify-a-plus: stage=%s\\n'"
        val functionEnd = verifier.indexOf(functionEndMarker, functionStart)
        assertTrue("host receipt validator function is missing", functionStart >= 0)
        assertTrue("host receipt validator end marker is missing", functionEnd >= 0)
        val functionSource = verifier.substring(functionStart, functionEnd + 2)
        val readMarker = "validation_error = None\nsource_binding_before = None\n"
        val rendezvous =
            readMarker +
                "with open(os.environ[\"HOST_RECEIPT_VALIDATOR_READY\"], \"w\") as ready_file:\n" +
                "    ready_file.write(\"ready\\n\")\n" +
                "while not os.path.exists(os.environ[\"HOST_RECEIPT_VALIDATOR_RELEASE\"]):\n" +
                "    __import__(\"time\").sleep(0.01)\n"
        val instrumented = functionSource.replace(readMarker, rendezvous)
        assertTrue("receipt-read rendezvous mutation is a no-op", instrumented != functionSource)

        listOf("inode-replaced", "token-overwritten").forEach { mutation ->
            val stateDir = Files.createTempDirectory("issue66-host-validator-$mutation-").toRealPath()
            val binding = createValidatorBinding(stateDir)
            val receipt = stateDir.resolve("host-gate-receipt.json")
            val lock = stateDir.resolve("host-gate.lock")
            val probe = stateDir.resolve("verify-host-receipt-rendezvous.sh")
            val ready = stateDir.resolve("validator-ready")
            val release = stateDir.resolve("validator-release")
            var validator: Process? = null
            try {
                Files.write(receipt, (binding.receipt("PASS") + "\n").toByteArray())
                Files.write(
                    probe,
                    (
                        "#!/bin/bash\n" +
                            "set -uo pipefail\n" +
                            instrumented + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\" \"\$3\" \"\$4\"\n"
                        ).toByteArray(),
                )
                validator = validatorProcessBuilder(probe, receipt, lock, binding).apply {
                    environment()["HOST_RECEIPT_VALIDATOR_READY"] = ready.toString()
                    environment()["HOST_RECEIPT_VALIDATOR_RELEASE"] = release.toString()
                }.start()
                waitForPath(ready)

                val owner = lock.resolve("owner")
                assertTrue("$mutation validator owner token is missing", Files.isRegularFile(owner))
                val originalOwnerKey = Files.readAttributes(
                    owner,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey()
                if (mutation == "inode-replaced") {
                    Files.delete(owner)
                }
                Files.write(owner, "foreign-owner\n".toByteArray())
                val foreignOwnerKey = Files.readAttributes(
                    owner,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey()
                if (mutation == "inode-replaced") {
                    assertTrue("owner inode replacement was not reached", foreignOwnerKey != originalOwnerKey)
                } else {
                    assertEquals("token overwrite replaced the owner inode", originalOwnerKey, foreignOwnerKey)
                }
                Files.createFile(release)

                val validatorOutput = validator.inputStream.bufferedReader().use { it.readText() }
                assertEquals(validatorOutput, 1, validator.waitFor())
                assertTrue(validatorOutput, validatorOutput.contains("receipt is not authoritative"))
                assertTrue("$mutation validator removed the foreign lock", Files.exists(lock))
                assertEquals("foreign-owner", owner.readText().trim())
            } finally {
                if (!Files.exists(release)) Files.createFile(release)
                validator?.let { process ->
                    if (process.isAlive) {
                        process.destroyForcibly()
                        process.waitFor()
                    }
                }
                stateDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `zero argument runner owns one lock for the complete run`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-gate-lock-")
        val fakeBash = fakeBin.resolve("bash")
        val holderReady = fakeBin.resolve("holder-ready")
        val holderRelease = fakeBin.resolve("holder-release")
        var holder: Process? = null
        try {
            Files.write(
                fakeBash,
                (
                    "#!/bin/sh\n" +
                        "if [ \"\${HOST_GATE_HOLDER_MODE:-}\" = hold ]; then\n" +
                        "  /usr/bin/touch \"\$HOST_GATE_HOLDER_READY\"\n" +
                        "  while [ ! -e \"\$HOST_GATE_HOLDER_RELEASE\" ]; do /bin/sleep 0.02; done\n" +
                        "  exit 23\n" +
                        "fi\n" +
                        "exit 24\n"
                    ).toByteArray(),
            )
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))

            holder = hostGateProcess(
                isolated.script,
                fakeBin,
                mapOf(
                    "HOST_GATE_HOLDER_MODE" to "hold",
                    "HOST_GATE_HOLDER_READY" to holderReady.toString(),
                    "HOST_GATE_HOLDER_RELEASE" to holderRelease.toString(),
                ),
            ).start()
            waitForPath(holderReady)

            val owner = isolated.lock.resolve("owner")
            assertTrue("active host gate must expose lock ownership", Files.isRegularFile(owner))
            assertTrue("lock owner must identify its process", owner.readText().contains("pid="))

            val contender = hostGateProcess(
                isolated.script,
                fakeBin,
                mapOf("HOST_GATE_HOLDER_MODE" to "fail"),
            ).start()
            val contenderOutput = contender.inputStream.bufferedReader().use { it.readText() }
            assertEquals(contenderOutput, HOST_GATE_ALREADY_RUNNING_EXIT, contender.waitFor())
            assertTrue(contenderOutput, contenderOutput.contains("already running"))
            assertRunnerReceipt(isolated, "RUNNING")

            Files.createFile(holderRelease)
            val holderOutput = holder.inputStream.bufferedReader().use { it.readText() }
            assertEquals(holderOutput, 23, holder.waitFor())
            assertTrue("lock must be released by its owner", !Files.exists(isolated.lock))
        } finally {
            if (!Files.exists(holderRelease)) Files.createFile(holderRelease)
            holder?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `ordinary failure retains the lock when the RUNNING receipt bytes were forged`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-gate-running-forgery-")
        val fakeBash = fakeBin.resolve("bash")
        val holderReady = fakeBin.resolve("holder-ready")
        val holderRelease = fakeBin.resolve("holder-release")
        var holder: Process? = null
        try {
            Files.write(
                fakeBash,
                (
                    "#!/bin/sh\n" +
                        "/usr/bin/touch \"\$HOST_GATE_HOLDER_READY\"\n" +
                        "while [ ! -e \"\$HOST_GATE_HOLDER_RELEASE\" ]; do /bin/sleep 0.02; done\n" +
                        "exit 23\n"
                    ).toByteArray(),
            )
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))
            holder = hostGateProcess(
                isolated.script,
                fakeBin,
                mapOf(
                    "HOST_GATE_HOLDER_READY" to holderReady.toString(),
                    "HOST_GATE_HOLDER_RELEASE" to holderRelease.toString(),
                ),
            ).start()
            waitForPath(holderReady)
            assertRunnerReceipt(isolated, "RUNNING")

            Files.write(isolated.receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())
            Files.createFile(holderRelease)
            val output = holder.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 23, holder.waitFor())
            assertTrue(output, output.contains("retained an ambiguous owner lock"))
            assertTrue("cleanup removed the lock around a forged receipt", Files.exists(isolated.lock))
            assertEquals(MACHINE_READABLE_BLOCKED, isolated.receipt.readText().trim())
        } finally {
            if (!Files.exists(holderRelease)) Files.createFile(holderRelease)
            holder?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `runner cleanup retains a same token replacement owner as a foreign lock fence`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-gate-owner-replace-")
        val fakeBash = fakeBin.resolve("bash")
        val holderReady = fakeBin.resolve("holder-ready")
        val holderRelease = fakeBin.resolve("holder-release")
        var holder: Process? = null
        try {
            Files.write(
                fakeBash,
                (
                    "#!/bin/sh\n" +
                        "/usr/bin/touch \"\$HOST_GATE_HOLDER_READY\"\n" +
                        "while [ ! -e \"\$HOST_GATE_HOLDER_RELEASE\" ]; do /bin/sleep 0.02; done\n" +
                        "exit 23\n"
                    ).toByteArray(),
            )
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))
            holder = hostGateProcess(
                isolated.script,
                fakeBin,
                mapOf(
                    "HOST_GATE_HOLDER_READY" to holderReady.toString(),
                    "HOST_GATE_HOLDER_RELEASE" to holderRelease.toString(),
                ),
            ).start()
            waitForPath(holderReady)

            val owner = isolated.lock.resolve("owner")
            val originalToken = owner.readText()
            val originalKey = Files.readAttributes(
                owner,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            Files.delete(owner)
            Files.write(owner, originalToken.toByteArray())
            val replacementKey = Files.readAttributes(
                owner,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()
            assertTrue("owner inode replacement was not reached", replacementKey != originalKey)

            Files.createFile(holderRelease)
            val output = holder.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 23, holder.waitFor())
            assertTrue(output, output.contains("retained an ambiguous owner lock"))
            assertTrue("cleanup removed a replacement owner lock", Files.exists(isolated.lock))
            assertEquals(originalToken, owner.readText())
        } finally {
            if (!Files.exists(holderRelease)) Files.createFile(holderRelease)
            holder?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `runner cleanup rejects a replaced receipt parent even when its lock inode is moved back`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val movedParent = isolated.stateDir.resolveSibling(isolated.stateDir.fileName.toString() + "-moved")
        val fakeBin = Files.createTempDirectory("issue66-host-gate-parent-replace-")
        val fakeBash = fakeBin.resolve("bash")
        val holderReady = fakeBin.resolve("holder-ready")
        val holderRelease = fakeBin.resolve("holder-release")
        var holder: Process? = null
        try {
            Files.write(
                fakeBash,
                (
                    "#!/bin/sh\n" +
                        "/usr/bin/touch \"\$HOST_GATE_HOLDER_READY\"\n" +
                        "while [ ! -e \"\$HOST_GATE_HOLDER_RELEASE\" ]; do /bin/sleep 0.02; done\n" +
                        "exit 23\n"
                    ).toByteArray(),
            )
            assertTrue("fake bash must be executable", fakeBash.toFile().setExecutable(true))
            holder = hostGateProcess(
                isolated.script,
                fakeBin,
                mapOf(
                    "HOST_GATE_HOLDER_READY" to holderReady.toString(),
                    "HOST_GATE_HOLDER_RELEASE" to holderRelease.toString(),
                ),
            ).start()
            waitForPath(holderReady)

            Files.move(isolated.stateDir, movedParent)
            Files.createDirectory(isolated.stateDir)
            Files.setPosixFilePermissions(
                isolated.stateDir,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            )
            Files.move(movedParent.resolve("host-gate.lock"), isolated.lock)
            Files.write(isolated.receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())

            Files.createFile(holderRelease)
            val output = holder.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 23, holder.waitFor())
            assertTrue(output, output.contains("retained an ambiguous owner lock"))
            assertTrue("cleanup trusted a moved lock under a replacement parent", Files.exists(isolated.lock))
            assertEquals(MACHINE_READABLE_BLOCKED, isolated.receipt.readText().trim())
        } finally {
            if (!Files.exists(holderRelease)) Files.createFile(holderRelease)
            holder?.let { process ->
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }
            fakeBin.toFile().deleteRecursively()
            movedParent.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `post PASS provenance change retains the lock around the published PASS`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo) { source ->
            val provenanceStart = source.indexOf("read_source_provenance() {")
            val provenanceEnd = source.indexOf("\n}\n\nnew_run_id() {", provenanceStart)
            check(provenanceStart >= 0 && provenanceEnd > provenanceStart) {
                "isolated source-provenance stub changed"
            }
            val statefulProvenance =
                "read_source_provenance() {\n" +
                    "  local count_file=\"\$receipt_dir/.test-provenance-count\" count=0\n" +
                    "  if [[ -f \"\$count_file\" ]]; then count=\"\$(<\"\$count_file\")\"; fi\n" +
                    "  count=\$((count + 1))\n" +
                    "  printf '%s\\n' \"\$count\" >\"\$count_file\"\n" +
                    "  if [[ \"\$count\" -ge 3 ]]; then\n" +
                    "    printf '%s:%s\\n' '$TEST_CHANGED_SOURCE_HEAD' '$TEST_CHANGED_SOURCE_TREE'\n" +
                    "  else\n" +
                    "    printf '%s:%s\\n' '$TEST_SOURCE_HEAD' '$TEST_SOURCE_TREE'\n" +
                    "  fi\n" +
                    "}"
            val withStatefulProvenance = source.replaceRange(
                provenanceStart,
                provenanceEnd + 2,
                statefulProvenance,
            )
            withSuccessfulHostVerificationStub(withStatefulProvenance)
        }
        val fakeBin = Files.createTempDirectory("issue66-host-gate-post-pass-source-")
        try {
            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 1, process.waitFor())
            assertTrue(output, output.contains("source changed across PASS publication"))
            assertRunnerReceipt(isolated, "PASS")
            assertTrue("post-PASS provenance ambiguity must retain the lock", Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `terminal success is emitted only after verified lock release`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo) { source ->
            val releaseStart = source.indexOf("release_host_gate_lock() {")
            val releaseEnd = source.indexOf("\n}\n\ncleanup_host_gate_lock() {", releaseStart)
            check(releaseStart >= 0 && releaseEnd > releaseStart) {
                "host-gate lock release helper changed"
            }
            val releaseProbe =
                "release_host_gate_lock() {\n" +
                    "  /bin/rm \"\$lock_owner_path\" &&\n" +
                    "    /bin/rmdir \"\$lock_dir\" &&\n" +
                    "    printf '%s\\n' TEST_LOCK_RELEASE_VERIFIED\n" +
                    "}"
            withSuccessfulHostVerificationStub(
                source.replaceRange(releaseStart, releaseEnd + 2, releaseProbe),
            )
        }
        val fakeBin = Files.createTempDirectory("issue66-host-gate-release-order-")
        try {
            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            val release = output.indexOf("TEST_LOCK_RELEASE_VERIFIED")
            val terminalPass = output.indexOf("HOST integration gate: PASS")
            val terminalReceipt = output.indexOf("{\"schemaVersion\":4")
            assertTrue(output, release >= 0)
            assertTrue(output, terminalPass > release)
            assertTrue(output, terminalReceipt > release)
            assertTrue("successful release probe left the lock behind", !Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `release failure is nonzero retains ambiguity lock and emits no terminal pass`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo) { source ->
            val releaseStart = source.indexOf("release_host_gate_lock() {")
            val releaseEnd = source.indexOf("\n}\n\ncleanup_host_gate_lock() {", releaseStart)
            check(releaseStart >= 0 && releaseEnd > releaseStart) {
                "host-gate lock release helper changed"
            }
            val failingRelease =
                "release_host_gate_lock() {\n" +
                    "  return 91\n" +
                    "}"
            withSuccessfulHostVerificationStub(
                source.replaceRange(releaseStart, releaseEnd + 2, failingRelease),
            )
        }
        val fakeBin = Files.createTempDirectory("issue66-host-gate-release-failure-")
        try {
            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue(output, process.waitFor() != 0)
            assertTrue(output, output.contains("retained an ambiguous owner lock"))
            assertTrue(output, !output.contains("HOST integration gate: PASS"))
            assertTrue(output, !output.contains("\"hostIntegration\":\"PASS\""))
            assertRunnerReceipt(isolated, "PASS")
            assertTrue("release failure removed the ambiguity lock", Files.exists(isolated.lock))
            assertTrue(
                "release failure removed the bound owner",
                Files.isRegularFile(isolated.lock.resolve("owner")),
            )
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `post removal cleanup failure remains a committed successful release`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo) { source ->
            val releaseStart = source.indexOf("release_host_gate_lock() {")
            val releaseEnd = source.indexOf("\n}\n\ncleanup_host_gate_lock() {", releaseStart)
            check(releaseStart >= 0 && releaseEnd > releaseStart) {
                "host-gate lock release helper changed"
            }
            val releaseSource = source.substring(releaseStart, releaseEnd + 2)
            val closeMarker = "    os.close(parent_fd)"
            check(releaseSource.windowed(closeMarker.length).count { it == closeMarker } == 1) {
                "release parent-close marker changed"
            }
            val failingFinalizer = releaseSource.replace(
                closeMarker,
                "    raise OSError(\"forced post-removal close failure\")",
            )
            withSuccessfulHostVerificationStub(
                source.replaceRange(releaseStart, releaseEnd + 2, failingFinalizer),
            )
        }
        val fakeBin = Files.createTempDirectory("issue66-host-gate-post-removal-close-")
        try {
            val process = hostGateProcess(isolated.script, fakeBin, emptyMap()).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())
            assertTrue(output, output.contains("HOST integration gate: PASS"))
            assertTrue(output, output.contains("\"hostIntegration\":\"PASS\""))
            assertTrue("committed release recreated or retained the lock", !Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `lock removal is the final reportable release operation`() {
        val runner = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val release = runner
            .substringAfter("release_host_gate_lock() {", missingDelimiterValue = "")
            .substringBefore("\n}\n\ncleanup_host_gate_lock() {", missingDelimiterValue = "")
        val parentSync = release.lastIndexOf("    sync_directory(parent_fd)")
        val lockRemoval = release.indexOf("    os.rmdir(lock_name, dir_fd=parent_fd)")
        val commit = release.indexOf("    release_committed = True", lockRemoval)
        val guardedParentClose =
            "    try:\n" +
                "        os.close(parent_fd)\n" +
                "    except OSError:\n" +
                "        if not release_committed:\n" +
                "            raise"

        assertTrue("release helper is missing", release.isNotEmpty())
        assertTrue("parent durability check must finish before lock removal", parentSync in 0 until lockRemoval)
        assertTrue("rmdir must be followed only by its non-failing commit assignment", commit > lockRemoval)
        assertEquals(
            "reportable work remains between rmdir and the release commit",
            "release_committed = True",
            release.substring(lockRemoval).lines().drop(1).first { it.isNotBlank() }.trim(),
        )
        assertTrue(
            "descriptor finalization after the release commit must suppress close errors",
            guardedParentClose in release,
        )
    }

    private fun withSuccessfulHostVerificationStub(source: String): String {
        check(
            source.windowed(PINNED_ZERO_ARG_HOST_VERIFICATION_BLOCK.length)
                .count { it == PINNED_ZERO_ARG_HOST_VERIFICATION_BLOCK } == 1,
        ) {
            "isolated successful host-verification block changed"
        }
        return source.replace(
            PINNED_ZERO_ARG_HOST_VERIFICATION_BLOCK,
            "  : # injected successful host verification\n" +
                "  auto_attestation_sha256=\"${"a".repeat(64)}\"\n" +
                "  qwy_attestation_sha256=\"${"b".repeat(64)}\"\n" +
                "  harness_attestation_sha256=\"${"c".repeat(64)}\"",
        )
    }

    private fun assertDarwinSourceAclRejected(
        relativeTarget: String?,
        injectBeforeConfirmedScan: Boolean = false,
    ) {
        val canonical = findRepoRoot()
            .resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
            .readText()
        val functionStart = canonical.indexOf("read_source_provenance() {")
        val functionEnd = canonical.indexOf("\n}\n\nnew_run_id() {", functionStart)
        check(functionStart >= 0 && functionEnd > functionStart) {
            "source provenance helper is missing"
        }
        val functionSource = canonical.substring(functionStart, functionEnd + 2)
        val stateRoot = Files.createTempDirectory("issue66-source-provenance-acl-").toRealPath()
        val repo = stateRoot.resolve("repo")
        val probe = stateRoot.resolve("probe.sh")
        var aclTarget: Path? = null

        fun git(vararg arguments: String): String {
            val process = ProcessBuilder("/usr/bin/git", "-C", repo.toString(), *arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "ACL fixture git failed: $output" }
            return output.trim()
        }

        fun probe(): Pair<Int, String> {
            val process = ProcessBuilder("/bin/bash", "-p", probe.toString(), repo.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output.trim()
        }

        fun writeProbe(source: String) {
            Files.write(
                probe,
                (
                    "#!/bin/bash -p\n" +
                        "unset BASH_ENV ENV\n" +
                        "set -euo pipefail\n" +
                        "repo_root=\"\$1\"\n" +
                        source + "\n" +
                        "read_source_provenance\n"
                    ).toByteArray(),
            )
        }

        try {
            Files.createDirectory(repo)
            git("init", "-q")
            git("config", "user.name", "Host Gate ACL Test")
            git("config", "user.email", "host-gate-acl@example.invalid")
            Files.createDirectory(repo.resolve("tracked-dir"))
            Files.write(repo.resolve("tracked-dir/nested.txt"), "reviewed\n".toByteArray())
            git("add", "tracked-dir/nested.txt")
            git("-c", "commit.gpgSign=false", "commit", "-q", "-m", "ACL fixture")
            writeProbe(functionSource)

            val clean = probe()
            assertEquals(clean.second, 0, clean.first)
            aclTarget = relativeTarget?.let(repo::resolve) ?: repo
            if (injectBeforeConfirmedScan) {
                val marker = "    confirmed = repository_snapshot()"
                check(functionSource.windowed(marker.length).count { it == marker } == 1) {
                    "confirmed raw source scan marker changed"
                }
                val injected = functionSource.replace(
                    marker,
                    "    subprocess.run(\n" +
                        "        [\"/bin/chmod\", \"+a\", \"everyone allow write\", " +
                        "os.path.join(repo_root, \"tracked-dir\", \"nested.txt\")],\n" +
                        "        check=True,\n" +
                        "    )\n" +
                        marker,
                )
                writeProbe(injected)
            } else {
                addDarwinAcl(aclTarget, "everyone allow write")
            }

            val escaped = probe()
            assertTrue(escaped.second, escaped.first != 0)
            assertTrue(escaped.second, escaped.second.contains("extended ACL"))
        } finally {
            aclTarget?.takeIf(Files::exists)?.let(::removeDarwinAcl)
            stateRoot.toFile().deleteRecursively()
        }
    }

    private fun isDarwin(): Boolean = System.getProperty("os.name") == "Mac OS X"

    private fun addDarwinAcl(path: Path, rule: String) {
        val process = ProcessBuilder("/bin/chmod", "+a", rule, path.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "could not install Darwin ACL fixture: $output" }
    }

    private fun removeDarwinAcl(path: Path) {
        val process = ProcessBuilder("/bin/chmod", "-N", path.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "could not remove Darwin ACL fixture: $output" }
    }

    private fun isolatedRunner(
        repo: Path,
        transform: (String) -> String = { it },
    ): IsolatedHostGate {
        val canonical = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val reportsDir = canonical.parent.resolve("harness/build/reports")
        Files.createDirectories(reportsDir)
        val stateDir = Files.createTempDirectory(reportsDir, "pr63-on-issue66-test-").toRealPath()
        val relativeStateDir = canonical.parent.toRealPath().relativize(stateDir)
            .joinToString("/") { it.toString() }
        val marker = "  receipt_relative_dir=\"harness/build/reports/pr63-on-issue66\""
        val source = canonical.readText()
        check(source.windowed(marker.length).count { it == marker } == 1) {
            "canonical host gate receipt directory marker changed"
        }
        val provenanceStart = source.indexOf("read_source_provenance() {")
        val provenanceEndMarker = "\n}\n\nnew_run_id() {"
        val provenanceEnd = source.indexOf(provenanceEndMarker, provenanceStart)
        check(provenanceStart >= 0 && provenanceEnd > provenanceStart) {
            "canonical host gate source-provenance function changed"
        }
        val testProvenance =
            "read_source_provenance() {\n" +
                "  printf '%s:%s\\n' '$TEST_SOURCE_HEAD' '$TEST_SOURCE_TREE'\n" +
                "}"
        val sourceWithTestProvenance = source.replaceRange(
            provenanceStart,
            provenanceEnd + 2,
            testProvenance,
        )
        val reviewedRunnerStart = sourceWithTestProvenance.indexOf("read_head_runner_sha256() {")
        val reviewedRunnerEndMarker = "\n}\n\nwrite_receipt_atomically() {"
        val reviewedRunnerEnd = sourceWithTestProvenance.indexOf(
            reviewedRunnerEndMarker,
            reviewedRunnerStart,
        )
        check(reviewedRunnerStart >= 0 && reviewedRunnerEnd > reviewedRunnerStart) {
            "canonical HEAD-runner digest helper changed"
        }
        val testReviewedRunner =
            "read_head_runner_sha256() {\n" +
                "  read_runner_sha256 \"\$2\"\n" +
                "}"
        val sourceWithTestBindings = sourceWithTestProvenance.replaceRange(
            reviewedRunnerStart,
            reviewedRunnerEnd + 2,
            testReviewedRunner,
        )
        val sourceWithIsolatedState = sourceWithTestBindings.replace(
            marker,
            "  receipt_relative_dir='$relativeStateDir'",
        )
        val isolatedSource = withIsolatedHostEnvironmentValidators(
            transform(sourceWithIsolatedState),
        )
            .replace(
                "PATH=/usr/bin:/bin\nexport PATH",
                ": # isolated fixture preserves the injected command PATH",
            )
            .replace(
                PINNED_MOTO_READONLY_SELFTEST_LINE,
                "  bash \"\$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh\"",
            )
            .replace(
                PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE,
                "  bash \"\$repo_root/scripts/selftest-issue66-services-compatibility.sh\"",
            )
        val script = Files.createTempFile(canonical.parent, ".run-host-gate-test-", ".sh")
        Files.write(script, isolatedSource.toByteArray())
        check(script.toFile().setExecutable(true, true)) { "isolated host gate must be executable" }
        return IsolatedHostGate(
            script = script,
            stateDir = stateDir,
            receipt = stateDir.resolve("host-gate-receipt.json"),
            lock = stateDir.resolve("host-gate.lock"),
        )
    }

    private fun withIsolatedHostEnvironmentValidators(source: String): String {
        fun replaceFunction(
            input: String,
            name: String,
            nextName: String,
            replacement: String,
        ): String {
            val start = input.indexOf("$name() {")
            val end = input.indexOf("\n}\n\n$nextName() {", start)
            check(start >= 0 && end > start) { "canonical $name helper changed" }
            return input.replaceRange(start, end + 2, replacement)
        }
        val bindingJson =
            "{\\\"arch\\\":\\\"aarch64\\\",\\\"javaHome\\\":\\\"%s\\\"," +
                "\\\"javaMajor\\\":17,\\\"javaRuntimeVersion\\\":\\\"17.0.20.1+1\\\"," +
                "\\\"javaVendor\\\":\\\"Eclipse Adoptium\\\",\\\"javaVmVendor\\\":\\\"Eclipse Adoptium\\\"," +
                "\\\"jdkTreeSha256\\\":\\\"$TEST_JDK_TREE_SHA256\\\",\\\"os\\\":\\\"darwin\\\"," +
                "\\\"profileId\\\":\\\"darwin-aarch64-eclipse-temurin-17.0.20.1+1\\\"," +
                "\\\"schemaVersion\\\":1}"
        val emitStub =
            "emit_java_runtime_binding() {\n" +
                "  printf '$bindingJson\\n' \"\$1\"\n" +
                "}"
        val verifyStub = "verify_java_runtime_binding() {\n  return 0\n}"
        val stageStub =
            "stage_java_runtime() {\n" +
                "  /bin/mkdir -m 0755 \"\$2/home\" || return 1\n" +
                "  printf '$bindingJson\\n' \"\$2/home\"\n" +
                "}"
        val withEmitStub = replaceFunction(
            source,
            "emit_java_runtime_binding",
            "verify_java_runtime_binding",
            emitStub,
        )
        val withVerifyStub = replaceFunction(
            withEmitStub,
            "verify_java_runtime_binding",
            "read_java_binding_field",
            verifyStub,
        )
        val withJavaStub = replaceFunction(
            withVerifyStub,
            "stage_java_runtime",
            "validate_android_sdk_root",
            stageStub,
        )

        val androidStub =
            "validate_android_sdk_root() {\n" +
                "  printf '%s\\n' 'isolated-android-sdk-binding'\n" +
                "}"
        val withAndroidStub = replaceFunction(
            withJavaStub,
            "validate_android_sdk_root",
            "verify_android_sdk_binding",
            androidStub,
        )
        val withAndroidVerifyStub = replaceFunction(
            withAndroidStub,
            "verify_android_sdk_binding",
            "run_standalone_runtime_security_tests",
            "verify_android_sdk_binding() {\n  return 0\n}",
        )
        return replaceFunction(
            withAndroidVerifyStub,
            "run_standalone_runtime_security_tests",
            "prepare_private_directory",
            "run_standalone_runtime_security_tests() {\n  return 0\n}",
        )
    }

    private fun createValidatorBinding(parent: Path): ValidatorBinding {
        val requestedRepo = parent.resolve("validator-source-repo")
        Files.createDirectories(
            requestedRepo.resolve("integration-tests/pr63-on-issue66"),
        )
        val sourceRepo = requestedRepo.toRealPath()
        val runner = sourceRepo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        Files.write(runner, "#!/bin/bash\nexit 0\n".toByteArray())
        check(runner.toFile().setExecutable(true, true)) { "validator fixture runner must be executable" }

        fun git(vararg arguments: String): String {
            val process = ProcessBuilder("/usr/bin/git", "-C", sourceRepo.toString(), *arguments)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "validator fixture git failed: $output" }
            return output.trim()
        }

        git("init", "-q")
        git("config", "user.name", "Host Receipt Test")
        git("config", "user.email", "host-receipt@example.invalid")
        git("add", "integration-tests/pr63-on-issue66/run-host-gate.sh")
        git("-c", "commit.gpgSign=false", "commit", "-q", "-m", "validator fixture")
        return ValidatorBinding(
            repo = sourceRepo,
            runner = runner,
            sourceHead = git("rev-parse", "HEAD^{commit}"),
            sourceTree = git("rev-parse", "HEAD^{tree}"),
            runnerSha256 = sha256(runner),
            receiptParent = parent,
        )
    }

    private fun validatorProcessBuilder(
        probe: Path,
        receipt: Path,
        lock: Path,
        binding: ValidatorBinding,
    ): ProcessBuilder = ProcessBuilder(
        "/bin/bash",
        probe.toString(),
        receipt.toString(),
        lock.toString(),
        binding.repo.toString(),
        binding.runner.toString(),
    ).redirectErrorStream(true)

    private fun assertRunnerReceipt(isolated: IsolatedHostGate, expectedHostIntegration: String) {
        val actual = isolated.receipt.readText().trim()
        val runId = Regex("\\\"runId\\\":\\\"([0-9a-f]{32})\\\"")
            .find(actual)?.groupValues?.get(1)
            ?: error("runner receipt has no canonical runId: $actual")
        val reason = if (expectedHostIntegration == "RUNNING") {
            "HOST_GATE_RUNNING_NO_PASS_RECEIPT"
        } else {
            "HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__" +
                "ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION"
        }
        val autoAttestationSha256 = if (expectedHostIntegration == "RUNNING") {
            "NOT_AVAILABLE_YET"
        } else {
            "a".repeat(64)
        }
        val qwyAttestationSha256 = if (expectedHostIntegration == "RUNNING") {
            "NOT_AVAILABLE_YET"
        } else {
            "b".repeat(64)
        }
        val harnessAttestationSha256 = if (expectedHostIntegration == "RUNNING") {
            "NOT_AVAILABLE_YET"
        } else {
            "c".repeat(64)
        }
        val expected =
            "{\"schemaVersion\":4,\"sourceHead\":\"$TEST_SOURCE_HEAD\",\"sourceTree\":\"$TEST_SOURCE_TREE\"," +
                "\"sourceState\":\"CLEAN\",\"runnerSha256\":\"${sha256(isolated.script)}\"," +
                "\"runId\":\"$runId\"," +
                "\"jdkProfileId\":\"$TEST_JDK_PROFILE_ID\"," +
                "\"jdkRuntimeVersion\":\"$TEST_JDK_RUNTIME_VERSION\"," +
                "\"jdkTreeSha256\":\"$TEST_JDK_TREE_SHA256\"," +
                "\"gradleAttestationAutoSha256\":\"$autoAttestationSha256\"," +
                "\"gradleAttestationQwySha256\":\"$qwyAttestationSha256\"," +
                "\"gradleAttestationHarnessSha256\":\"$harnessAttestationSha256\"," +
                "\"hostIntegration\":\"$expectedHostIntegration\"," +
                "\"issue66Ac7\":\"NOT_PASSED\",\"emulator\":\"NOT_RUN\"," +
                "\"physicalDevice\":\"NOT_RUN\",\"deviceFull\":\"BLOCKED\"," +
                "\"overall\":\"BLOCKED\",\"reason\":\"$reason\"}"
        assertEquals(expected, actual)
    }

    private fun sha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun hostGateProcess(
        runner: Path,
        fakeBin: Path,
        extraEnvironment: Map<String, String>,
    ): ProcessBuilder = ProcessBuilder("/bin/bash", runner.toString())
        .redirectErrorStream(true)
        .apply {
            environment()["PATH"] = fakeBin.toString() + ":" + System.getenv("PATH")
            environment()["JAVA_HOME"] = System.getProperty("java.home")
            environment()["ANDROID_HOME"] = requireNotNull(System.getenv("ANDROID_HOME"))
            environment().putAll(extraEnvironment)
        }

    private fun waitForPath(path: Path) {
        repeat(250) {
            if (Files.exists(path)) return
            Thread.sleep(20)
        }
        error("timed out waiting for $path")
    }

    private data class IsolatedHostGate(
        val script: Path,
        val stateDir: Path,
        val receipt: Path,
        val lock: Path,
    ) : AutoCloseable {
        override fun close() {
            Files.deleteIfExists(script)
            stateDir.toFile().deleteRecursively()
        }
    }

    private data class ValidatorBinding(
        val repo: Path,
        val runner: Path,
        val sourceHead: String,
        val sourceTree: String,
        val runnerSha256: String,
        val receiptParent: Path,
    ) {
        fun receipt(hostIntegration: String, parent: Path = receiptParent): String {
            val reason = if (hostIntegration == "RUNNING") {
                "HOST_GATE_RUNNING_NO_PASS_RECEIPT"
            } else {
                "HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__" +
                    "ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_" +
                    "ADDITIONAL_AUTHORIZATION"
            }
            val attestationSha256 = if (hostIntegration == "PASS") {
                writeGradleAttestations(parent)
            } else {
                mapOf(
                    "auto" to "NOT_AVAILABLE_YET",
                    "qwy" to "NOT_AVAILABLE_YET",
                    "harness" to "NOT_AVAILABLE_YET",
                )
            }
            return "{\"schemaVersion\":4,\"sourceHead\":\"$sourceHead\"," +
                "\"sourceTree\":\"$sourceTree\",\"sourceState\":\"CLEAN\"," +
                "\"runnerSha256\":\"$runnerSha256\",\"runId\":\"$VALIDATOR_RUN_ID\"," +
                "\"jdkProfileId\":\"$TEST_JDK_PROFILE_ID\"," +
                "\"jdkRuntimeVersion\":\"$TEST_JDK_RUNTIME_VERSION\"," +
                "\"jdkTreeSha256\":\"$TEST_JDK_TREE_SHA256\"," +
                "\"gradleAttestationAutoSha256\":\"${attestationSha256.getValue("auto")}\"," +
                "\"gradleAttestationQwySha256\":\"${attestationSha256.getValue("qwy")}\"," +
                "\"gradleAttestationHarnessSha256\":\"${attestationSha256.getValue("harness")}\"," +
                "\"hostIntegration\":\"$hostIntegration\",\"issue66Ac7\":\"NOT_PASSED\"," +
                "\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\"," +
                "\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\",\"reason\":\"$reason\"}"
        }

        private fun writeGradleAttestations(parent: Path): Map<String, String> =
            VALIDATOR_ATTESTATION_SPECS.associate { (stage, taskPath, classes) ->
                val path = parent.resolve(
                    "gradle-attestation-$stage-$VALIDATOR_RUN_ID.txt",
                )
                if (!Files.exists(path)) {
                    val jdkHome = parent.resolve(
                        "jdk-runtime.${"c".repeat(32)}/home",
                    )
                    val body = listOf(
                        "schemaVersion=2",
                        "runId=$VALIDATOR_RUN_ID",
                        "stage=$stage",
                        "taskPath=$taskPath",
                        "jdkHome=$jdkHome",
                        "jdkProfileId=$TEST_JDK_PROFILE_ID",
                        "javaVendor=Eclipse Adoptium",
                        "javaVmVendor=Eclipse Adoptium",
                        "jdkRuntimeVersion=$TEST_JDK_RUNTIME_VERSION",
                        "jdkTreeSha256=$TEST_JDK_TREE_SHA256",
                        "jdkMajor=17",
                        "testLauncherMajor=17",
                        "testCount=1",
                        "failureCount=0",
                        "classes=$classes",
                    ).joinToString("\n", postfix = "\n")
                    Files.write(path, body.toByteArray())
                    Files.setPosixFilePermissions(
                        path,
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        ),
                    )
                }
                stage to sha256(path)
            }

        private fun sha256(path: Path): String = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun violations(script: String): List<String> = buildList {
        expectExactlyOnce(script, QWY_FAKES, "canonical Fakes.kt")
        expectExactlyOnce(script, QWY_HARNESS, "canonical ProviderHarness.kt")
        expectExactlyOnce(script, FRIEND_SCOPE, "debug-unit-test-only friend scope")
        expectExactlyOnce(script, FRIEND_ARTIFACT, "resolved QWY production artifact")
        expectExactlyOnce(script, AUTO_CONTRACT_EXCLUDE, "Auto contract exclusion")
        expectExactlyOnce(script, QWY_CONTRACT_EXCLUDE, "QWY contract exclusion")
        expectExactlyOnce(script, AUTO_RELEASE_ATTRIBUTE, "real Auto release variant selection")
        expectExactlyOnce(script, QWY_DEBUG_ATTRIBUTE, "explicit QWY debug variant selection")
        expectExactlyOnce(script, RESOLVED_BOUNDARY_TASK, "resolved dependency/source boundary task")
        expectExactlyOnce(script, RESOLVED_DIRECT_BLOCK, "resolved direct dependency whitelist")
        expectExactlyOnce(script, TEST_DEPENDS_ON_BOUNDARY, "tests depend on the resolved boundary")
        expectExactlyOnce(script, AUTO_DEPENDENCY, "Auto included-build dependency")
        EXPECTED_DIRECT_DEPENDENCIES.forEach { dependency ->
            expectExactlyOnce(script, dependency, "direct dependency: $dependency")
        }
        if (Regex("\\btestImplementation\\s*\\(").findAll(script).count() !=
            EXPECTED_DIRECT_DEPENDENCIES.size
        ) add("direct dependency whitelist changed")
        val qwySupportBlock = script
            .substringAfter("val qwyCanonicalSupport = files(", missingDelimiterValue = "")
            .substringBefore("\n)", missingDelimiterValue = "")
        val mountedSources = Regex("\"([^\"]+\\.kt)\"")
            .findAll(qwySupportBlock)
            .map { it.groupValues[1] }
            .toSet()
        if (mountedSources != setOf(QWY_FAKES, QWY_HARNESS)) {
            add("QWY canonical support source whitelist changed")
        }
        if ("isTransitive = false" in script) add("app dependency graph must stay transitive")
        if (Regex("\\bsource\\s*\\(").findAll(script).count() != 1) {
            add("only the exact canonical QWY source collection may be mounted")
        }
        if ("sourceSets" in script || "setSource" in script) {
            add("additional source-set mounting is forbidden")
        }
        val dependencyBlock = script
            .substringAfter("dependencies {", missingDelimiterValue = "")
            .substringBefore("\n}", missingDelimiterValue = "")
        val directConfigurations = Regex("(?m)^ {4}([A-Za-z][A-Za-z0-9]*)\\(")
            .findAll(dependencyBlock)
            .map { it.groupValues[1] }
            .toList()
        if (directConfigurations != List(EXPECTED_DIRECT_DEPENDENCIES.size) { "testImplementation" }) {
            add("direct dependency configuration whitelist changed")
        }
        if (Regex("src/test/java[\\\"/]?\\s*[,)]").containsMatchIn(script)) {
            add("directory-wide QWY test source mount is forbidden")
        }
        if ("build/intermediates" in script) add("friend path must come from the resolved artifact")
    }

    private fun shellWithoutHeredocsOrComments(script: String): String {
        val heredocStart = Regex(
            """<<(-)?\s*(?:'([^']+)'|"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))""",
        )
        val sanitized = StringBuilder()
        var heredocDelimiter: String? = null
        var heredocStripsTabs = false
        var inSingleQuote = false
        var inDoubleQuote = false
        var parameterExpansionDepth = 0

        script.lineSequence().forEach { line ->
            val activeDelimiter = heredocDelimiter
            if (activeDelimiter != null) {
                val candidate = if (heredocStripsTabs) line.trimStart('\t') else line
                if (candidate == activeDelimiter) {
                    heredocDelimiter = null
                    heredocStripsTabs = false
                }
                return@forEach
            }

            val withoutComment = StringBuilder()
            var escaped = false
            for ((index, character) in line.withIndex()) {
                if (escaped) {
                    withoutComment.append(character)
                    escaped = false
                } else if (character == '\\' && !inSingleQuote) {
                    withoutComment.append(character)
                    escaped = true
                } else if (character == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote
                    withoutComment.append(character)
                } else if (character == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote
                    withoutComment.append(character)
                } else if (
                    character == '$' && !inSingleQuote && line.getOrNull(index + 1) == '{'
                ) {
                    parameterExpansionDepth += 1
                    withoutComment.append(character)
                } else if (
                    character == '}' && !inSingleQuote && parameterExpansionDepth > 0
                ) {
                    parameterExpansionDepth -= 1
                    withoutComment.append(character)
                } else if (
                    character == '#' && !inSingleQuote && !inDoubleQuote &&
                    parameterExpansionDepth == 0 &&
                    (index == 0 || line[index - 1].isWhitespace() || line[index - 1] in ";|&(){}")
                ) {
                    break
                } else {
                    withoutComment.append(character)
                }
            }
            val shellLine = withoutComment.toString()
            sanitized.append(shellLine).append('\n')
            heredocStart.find(shellLine)?.let { match ->
                val quotedDelimiter = match.groupValues
                    .slice(2..3)
                    .firstOrNull(String::isNotEmpty)
                if (quotedDelimiter != null) {
                    heredocStripsTabs = match.groupValues[1] == "-"
                    heredocDelimiter = quotedDelimiter
                    inSingleQuote = false
                    inDoubleQuote = false
                    parameterExpansionDepth = 0
                }
            }
        }
        return sanitized.toString()
    }

    private fun shellWithoutInertSingleQuotedText(script: String): String {
        val masked = StringBuilder(script.length)
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false
        script.forEach { character ->
            when {
                character == '\n' -> {
                    masked.append(character)
                    escaped = false
                }
                escaped -> {
                    masked.append(if (inSingleQuote) ' ' else character)
                    escaped = false
                }
                character == '\\' && !inSingleQuote -> {
                    masked.append(if (inSingleQuote) ' ' else character)
                    escaped = true
                }
                character == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    masked.append(' ')
                }
                character == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    masked.append(character)
                }
                inSingleQuote -> masked.append(' ')
                else -> masked.append(character)
            }
        }
        return masked.toString()
    }

    private data class DynamicShellCommand(
        val word: String,
        val wordStart: Int,
        val expansionStart: Int,
    )

    private data class ShellLexeme(
        val text: String,
        val start: Int,
        val operator: Boolean,
        val activeExpansionStart: Int?,
    )

    /**
     * Enumerates dynamically expanded command words without treating operators inside
     * shell quotes or parameter expansions as command boundaries. This intentionally
     * covers only the runner's reviewed shell subset; the exact-HEAD review remains the
     * authority for Bash semantics.
     */
    private fun dynamicShellCommandWords(script: String): List<DynamicShellCommand> {
        val lexemes = mutableListOf<ShellLexeme>()
        val word = StringBuilder()
        var wordStart = -1
        var activeExpansionStart: Int? = null
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false
        var parameterExpansionDepth = 0

        fun append(character: Char, index: Int) {
            if (wordStart < 0) wordStart = index
            word.append(character)
        }

        fun flushWord() {
            if (wordStart < 0) return
            lexemes += ShellLexeme(
                text = word.toString(),
                start = wordStart,
                operator = false,
                activeExpansionStart = activeExpansionStart,
            )
            word.clear()
            wordStart = -1
            activeExpansionStart = null
        }

        fun addOperator(operator: String, index: Int) {
            flushWord()
            lexemes += ShellLexeme(operator, index, true, null)
        }

        var index = 0
        while (index < script.length) {
            val character = script[index]
            when {
                escaped -> {
                    append(character, index)
                    escaped = false
                }
                character == '\\' && !inSingleQuote -> {
                    append(character, index)
                    escaped = true
                }
                character == '\'' && !inDoubleQuote -> {
                    append(character, index)
                    inSingleQuote = !inSingleQuote
                }
                character == '"' && !inSingleQuote -> {
                    append(character, index)
                    inDoubleQuote = !inDoubleQuote
                }
                character == '$' && !inSingleQuote -> {
                    append(character, index)
                    if (activeExpansionStart == null) activeExpansionStart = index
                    if (script.getOrNull(index + 1) == '{') parameterExpansionDepth += 1
                }
                character == '}' && !inSingleQuote && parameterExpansionDepth > 0 -> {
                    append(character, index)
                    parameterExpansionDepth -= 1
                }
                parameterExpansionDepth > 0 -> append(character, index)
                !inSingleQuote && !inDoubleQuote && character == '\n' ->
                    addOperator("\n", index)
                !inSingleQuote && !inDoubleQuote && character.isWhitespace() -> flushWord()
                !inSingleQuote && !inDoubleQuote && character in ";|&(){}" -> {
                    val paired = script.getOrNull(index + 1)?.let { next ->
                        (character == '&' && next == '&') ||
                            (character == '|' && next == '|') ||
                            (character == ';' && next in setOf(';', '&'))
                    } == true
                    val operator = if (paired) {
                        "$character${script[index + 1]}"
                    } else {
                        character.toString()
                    }
                    addOperator(operator, index)
                    if (paired) index += 1
                }
                else -> append(character, index)
            }
            index += 1
        }
        flushWord()

        val commandIntroducers = setOf("if", "elif", "while", "until", "then", "else", "do")
        val dispatchPrefixes = setOf(
            "exec",
            "command",
            "builtin",
            "eval",
            "coproc",
            "time",
            "nohup",
            "/usr/bin/nohup",
            "nice",
            "/usr/bin/nice",
        )
        val assignmentPrefix = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\+)?=")
        val dynamicCommands = mutableListOf<DynamicShellCommand>()
        var commandExpected = true
        var envPrefix = false
        var dispatcherPrefix = false

        lexemes.forEach { lexeme ->
            if (lexeme.operator) {
                commandExpected = true
                envPrefix = false
                dispatcherPrefix = false
                return@forEach
            }
            if (!commandExpected) return@forEach

            val rawWord = lexeme.text
            when {
                rawWord in commandIntroducers || rawWord == "!" -> return@forEach
                assignmentPrefix.containsMatchIn(rawWord) -> return@forEach
                envPrefix && (rawWord.startsWith("-") || assignmentPrefix.containsMatchIn(rawWord)) ->
                    return@forEach
                dispatcherPrefix && rawWord.startsWith("-") -> return@forEach
                rawWord == "env" || rawWord == "/usr/bin/env" -> {
                    envPrefix = true
                    dispatcherPrefix = true
                    return@forEach
                }
                rawWord in dispatchPrefixes -> {
                    dispatcherPrefix = true
                    return@forEach
                }
            }

            lexeme.activeExpansionStart?.let { expansionStart ->
                val normalizedWord = if (
                    rawWord.length >= 2 &&
                    rawWord.first() == rawWord.last() &&
                    rawWord.first() in setOf('\'', '"')
                ) {
                    rawWord.substring(1, rawWord.length - 1)
                } else {
                    rawWord
                }
                dynamicCommands += DynamicShellCommand(
                    normalizedWord,
                    lexeme.start,
                    expansionStart,
                )
            }
            commandExpected = false
            envPrefix = false
            dispatcherPrefix = false
        }
        return dynamicCommands
    }

    private fun privilegedBashStartupViolations(
        script: String,
        startupClearTopology: List<String>,
    ): List<String> {
        val sourceLines = script.lineSequence().toList()
        val logicalLines = shellWithoutHeredocsOrComments(script)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val startupClearIndexes = startupClearTopology.map { line -> sourceLines.indexOf(line) }
        return buildList {
            if (!script.startsWith("$PINNED_PRIVILEGED_BASH_SHEBANG\n") ||
                sourceLines.count { it == PINNED_PRIVILEGED_BASH_SHEBANG } != 1
            ) {
                add("script must start with privileged-mode Bash")
            }
            startupClearTopology.forEach { clearLine ->
                if (sourceLines.count { it == clearLine } != 1) {
                    add("script must contain exact startup clear exactly once: $clearLine")
                }
            }
            if (startupClearTopology.isEmpty() ||
                logicalLines.take(startupClearTopology.size) != startupClearTopology
            ) {
                add("exact Bash startup clear topology must precede every shell statement")
            }
            val lastStartupClear = startupClearIndexes.lastOrNull() ?: -1
            val failClosed = sourceLines.indexOf(PINNED_COLLECTOR_FAIL_CLOSED_SHELL)
            val pathPin = sourceLines.indexOf(PINNED_HOST_PATH_PIN)
            if (!(startupClearIndexes.isNotEmpty() && startupClearIndexes.none { it < 0 } &&
                    startupClearIndexes.zipWithNext().all { (first, second) -> second > first } &&
                    failClosed > lastStartupClear &&
                    (pathPin < 0 || pathPin > lastStartupClear))
            ) {
                add("Bash startup hooks must be cleared before shell options and PATH setup")
            }
        }
    }

    private fun servicesStartupViolations(
        script: String,
        startupClearTopology: List<String>,
    ): List<String> {
        val sourceLines = script.lineSequence().toList()
        val startupClearIndexes = startupClearTopology.map { line -> sourceLines.indexOf(line) }
        return buildList {
            addAll(privilegedBashStartupViolations(script, startupClearTopology))
            if (sourceLines.count { it == PINNED_HOST_PATH_PIN } != 1) {
                add("script must pin the host command PATH exactly once")
            }
            if (sourceLines.count { it == PINNED_HOST_PATH_EXPORT } != 1) {
                add("script must export the pinned host command PATH exactly once")
            }
            val lastStartupClear = startupClearIndexes.lastOrNull() ?: -1
            val pathPin = sourceLines.indexOf(PINNED_HOST_PATH_PIN)
            val pathExport = sourceLines.indexOf(PINNED_HOST_PATH_EXPORT)
            val failClosed = sourceLines.indexOf(PINNED_COLLECTOR_FAIL_CLOSED_SHELL)
            val firstHostLookup = sourceLines.indexOfFirst { line ->
                line.startsWith("SELF_DIR=") || line.startsWith("HERE=")
            }
            if (!(startupClearIndexes.isNotEmpty() && startupClearIndexes.none { it < 0 } &&
                    pathPin > lastStartupClear && pathExport > pathPin &&
                    failClosed > pathExport && firstHostLookup > failClosed)
            ) {
                add("startup clear and fixed PATH must precede shell setup and host command lookup")
            }
        }
    }

    private data class QuotedHeredoc(
        val command: String,
        val body: String,
    )

    private data class QuotedHeredocScan(
        val heredocs: List<QuotedHeredoc>,
        val unterminated: Boolean,
    )

    private data class PythonHeredocInspection(
        val violations: List<String>,
        val allowedLsRuns: Int,
        val allowedGitRuns: Int,
        val allowedCtypesLoads: Int,
        val allowedJavaRuntimePopens: Int,
    )

    private val pythonHeredocInspectionCache = mutableMapOf<String, PythonHeredocInspection>()

    private fun quotedHeredocs(script: String): QuotedHeredocScan {
        val heredocStart = Regex(
            """<<(-)?\s*(?:'([^']+)'|"([^"]+)")""",
        )
        val heredocs = mutableListOf<QuotedHeredoc>()
        var delimiter: String? = null
        var stripsTabs = false
        var command = ""
        var commandPrefix = ""
        var body = StringBuilder()

        script.lineSequence().forEach { line ->
            val activeDelimiter = delimiter
            if (activeDelimiter != null) {
                val candidate = if (stripsTabs) line.trimStart('\t') else line
                if (candidate == activeDelimiter) {
                    heredocs += QuotedHeredoc(command, body.toString())
                    delimiter = null
                    stripsTabs = false
                    command = ""
                    body = StringBuilder()
                } else {
                    body.append(line).append('\n')
                }
                return@forEach
            }

            val commentFreeLine = shellWithoutHeredocsOrComments(line).trimEnd('\n')
            val logicalCommand = commandPrefix + commentFreeLine
            heredocStart.find(commentFreeLine)?.let { match ->
                delimiter = match.groupValues[2].ifEmpty { match.groupValues[3] }
                stripsTabs = match.groupValues[1] == "-"
                command = logicalCommand
                commandPrefix = ""
                return@forEach
            }
            commandPrefix = if (line.trimEnd().endsWith("\\")) {
                logicalCommand.dropLastWhile(Char::isWhitespace).dropLast(1) + " "
            } else {
                ""
            }
        }
        return QuotedHeredocScan(heredocs, delimiter != null)
    }

    private fun inspectPythonHeredoc(body: String): PythonHeredocInspection =
        synchronized(pythonHeredocInspectionCache) {
            pythonHeredocInspectionCache[body] ?: run {
                val process = ProcessBuilder(
                    "/usr/bin/python3",
                    "-I",
                    "-c",
                    PYTHON_HEREDOC_AST_CHECKER,
                ).redirectErrorStream(true).apply {
                    environment().clear()
                    environment()["LC_ALL"] = "C"
                    environment()["LANG"] = "C"
                }.start()
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor()
                    return@synchronized PythonHeredocInspection(
                        listOf("Python heredoc AST inspection timed out"),
                        0,
                        0,
                        0,
                        0,
                    )
                }
                val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (process.exitValue() != 0) {
                    return@synchronized PythonHeredocInspection(
                        listOf("Python heredoc AST inspector failed: ${output.trim()}"),
                        0,
                        0,
                        0,
                        0,
                    )
                }
                var allowedLsRuns = -1
                var allowedGitRuns = -1
                var allowedCtypesLoads = -1
                var allowedJavaRuntimePopens = -1
                val violations = mutableListOf<String>()
                output.lineSequence().filter(String::isNotBlank).forEach { line ->
                    val fields = line.split('\t')
                    when {
                        fields.size == 5 && fields[0] == "COUNTS" -> {
                            allowedLsRuns = fields[1].toIntOrNull() ?: -1
                            allowedGitRuns = fields[2].toIntOrNull() ?: -1
                            allowedCtypesLoads = fields[3].toIntOrNull() ?: -1
                            allowedJavaRuntimePopens = fields[4].toIntOrNull() ?: -1
                        }
                        fields.size >= 3 && fields[0] == "ISSUE" ->
                            violations += "line ${fields[1]}: ${fields.drop(2).joinToString(" ")}"
                        else -> violations += "malformed Python AST inspector output: $line"
                    }
                }
                if (
                    allowedLsRuns < 0 || allowedGitRuns < 0 || allowedCtypesLoads < 0 ||
                    allowedJavaRuntimePopens < 0
                ) {
                    violations += "Python AST inspector omitted its structural counts"
                }
                PythonHeredocInspection(
                    violations,
                    allowedLsRuns,
                    allowedGitRuns,
                    allowedCtypesLoads,
                    allowedJavaRuntimePopens,
                ).also { pythonHeredocInspectionCache[body] = it }
            }
        }

    private fun runnerViolations(script: String): List<String> = buildList {
        expectExactlyOneLine(
            script,
            PINNED_PRIVILEGED_BASH_SHEBANG,
            "host runner must use privileged-mode Bash",
        )
        expectExactlyOneLine(
            script,
            PINNED_BASH_STARTUP_ENV_CLEAR,
            "host runner must clear inherited Bash startup hooks",
        )
        val shebang = script.indexOf(PINNED_PRIVILEGED_BASH_SHEBANG)
        val startupClear = script.indexOf(PINNED_BASH_STARTUP_ENV_CLEAR)
        val failClosed = script.indexOf(PINNED_FAIL_CLOSED_SHELL)
        if (!(shebang == 0 && startupClear > shebang && startupClear < failClosed)) {
            add("privileged Bash startup hardening must precede runner setup")
        }
        expectExactlyOneLine(script, PINNED_FAIL_CLOSED_SHELL, "host runner must fail closed")
        expectExactlyOnce(script, PINNED_AUTO_WRAPPER, "Auto repository Gradle wrapper")
        expectExactlyOnce(script, PINNED_QWY_WRAPPER, "QWY repository Gradle wrapper")
        expectExactlyOnce(script, PINNED_JAVA_PROFILE_VALIDATOR, "reviewed Java profile validator")
        expectExactlyOnce(script, PINNED_JAVA_RUNTIME_STAGER, "private Java runtime stager")
        expectExactlyOnce(script, PINNED_ANDROID_SDK_VALIDATOR, "reviewed Android SDK validator")
        expectExactlyOnce(
            script,
            PINNED_JAVA_PROFILE_VALIDATOR_TEST,
            "Java profile validator regression suite",
        )
        expectExactlyOnce(
            script,
            PINNED_JAVA_RUNTIME_STAGER_TEST,
            "Java runtime stager regression suite",
        )
        expectExactlyOnce(
            script,
            PINNED_ANDROID_SDK_VALIDATOR_TEST,
            "Android SDK validator regression suite",
        )
        expectExactlyOnce(script, PINNED_PROJECT, "pinned integration project")
        expectExactlyOneLine(
            script,
            PINNED_MOTO_READONLY_SELFTEST_LINE,
            "Moto read-only collector selftest",
        )
        expectExactlyOneLine(
            script,
            PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE,
            "services compatibility selftest",
        )
        expectExactlyOnce(
            script,
            PINNED_ZERO_ARG_RUNNING_PREFIX,
            "zero-argument receipt and lock path",
        )
        expectExactlyOnce(
            script,
            PINNED_ZERO_ARG_SELFTEST_BLOCK,
            "device-free selftests must start the zero-argument host gate",
        )
        expectExactlyOnce(
            script,
            PINNED_STANDALONE_RUNTIME_SECURITY_TESTS,
            "standalone runtime security suites must fail closed in the zero-argument host gate",
        )
        expectExactlyOnce(
            script,
            PINNED_ZERO_ARG_HOST_VERIFICATION_BLOCK,
            "zero-argument host gate must run the complete attested verification block",
        )
        expectExactlyOnce(
            script,
            PINNED_EPHEMERAL_GRADLE_HOME_PREPARE,
            "zero-argument host gate must prepare private per-run child and Gradle homes",
        )
        expectExactlyOnce(
            script,
            PINNED_EPHEMERAL_GRADLE_HOME_CLEANUP,
            "zero-argument host gate must remove both private per-run homes before PASS",
        )
        expectExactlyOnce(
            script,
            PINNED_EPHEMERAL_JAVA_RUNTIME_CLEANUP,
            "zero-argument host gate must revalidate and remove its private JDK before PASS",
        )
        expectExactlyOnce(
            script,
            PINNED_JAVA_RUNTIME_VALIDATION,
            "zero-argument host gate must stage and bind a reviewed Java 17 runtime",
        )
        expectExactlyOnce(
            script,
            PINNED_ANDROID_SDK_VALIDATION,
            "zero-argument host gate must bind a validated Android SDK",
        )
        if (script.lines().count { it == PINNED_ANDROID_SDK_RECHECK } != 4) {
            add("every Gradle execution must retain its Android SDK pre/post binding checks")
        }
        expectExactlyOnce(script, PINNED_AUTO_ROUTING_PROJECT, "pinned Auto routing project")
        expectExactlyOnce(script, PINNED_AUTO_ROUTING_TEST, "exact Auto routing regression")
        expectExactlyOnce(script, PINNED_QWY_PROJECT, "pinned QWY production project")
        expectExactlyOnce(script, PINNED_QWY_HOOK_PLAN, "QWY hook-plan production guard")
        expectExactlyOnce(script, PINNED_QWY_WIRING, "QWY installer wiring guard")
        expectExactlyOnce(script, PINNED_QWY_PRODUCTION, "QWY authoritative production guard")
        expectExactlyOnce(script, PINNED_QWY_ADAPTER, "QWY evidence-health adapter regression")
        expectExactlyOnce(script, PINNED_QWY_CODEC, "QWY evidence-health codec regression")
        expectExactlyOnce(script, PINNED_QWY_ADVANCE, "QWY authoritative revision suite")
        expectExactlyOnce(script, PINNED_FULL_HARNESS, "complete host harness")
        expectExactlyOneLine(script, PINNED_PRIVATE_UMASK, "private host-gate umask")
        expectExactlyOnce(script, PINNED_RECEIPT_DIR_PREPARE, "private receipt directory preparation")
        expectExactlyOnce(script, PINNED_RUNNING_RECEIPT_SCHEMA, "schema-v4 RUNNING receipt")
        expectExactlyOnce(script, PINNED_PASS_RECEIPT_SCHEMA, "schema-v4 PASS receipt")
        expectExactlyOnce(script, PINNED_LOCK_ACQUIRE, "exclusive host-gate lock acquisition")
        expectExactlyOnce(script, PINNED_LOCK_CLEANUP_TRAP, "host-gate lock cleanup trap")
        expectExactlyOnce(script, PINNED_ATOMIC_RECEIPT_REPLACE, "descriptor-relative atomic receipt replace")
        expectExactlyOnce(script, PINNED_LOCK_OWNER_WRITE, "host-gate run ownership")
        if (script.lines().count { it == PINNED_LOCK_RELEASE_DISARM } != 3) {
            add("host-gate lock must start disarmed and disarm both receipt publications")
        }
        if (script.lines().count { it == PINNED_LOCK_RELEASE_ARM } != 1) {
            add("host-gate lock must rearm only after a verified RUNNING publication")
        }
        expectExactlyOnce(script, PINNED_LOCK_RELEASE_GUARD, "host-gate lock release guard")
        expectExactlyOnce(
            script,
            PINNED_LOCK_OWNER_CLEANUP_GUARD,
            "host-gate lock owner cleanup guard",
        )
        expectExactlyOnce(
            script,
            PINNED_COOPERATIVE_RELEASE_BOUNDARY,
            "host-gate cooperative release boundary",
        )
        expectExactlyOnce(
            script,
            PINNED_TEMP_IDENTITY_CLEANUP,
            "failed atomic receipt temp identity cleanup",
        )
        expectExactlyOnce(script, PINNED_POST_PUBLISH_BYTES_CHECK, "post-publish exact bytes check")
        expectExactlyOnce(script, PINNED_RUNNING_RECEIPT_WRITE, "atomic RUNNING receipt write")
        expectExactlyOnce(script, PINNED_PASS_RECEIPT_WRITE, "atomic PASS receipt write")
        expectExactlyOnce(script, PINNED_POST_PASS_SOURCE_CHECK, "post-PASS source recheck")
        expectExactlyOnce(script, PINNED_POST_PASS_RUNNER_CHECK, "post-PASS runner recheck")
        expectExactlyOnce(script, PINNED_FINAL_LOCK_RELEASE, "verified final lock release")
        expectExactlyOnce(script, PINNED_TERMINAL_PASS, "terminal host PASS")
        expectExactlyOnce(script, PINNED_TERMINAL_RECEIPT, "terminal PASS receipt")
        val zeroArgStart = script.indexOf(PINNED_ZERO_ARG_RUNNING_PREFIX)
        val receiptPrepare = script.indexOf(PINNED_RECEIPT_DIR_PREPARE)
        val lockAcquire = script.indexOf(PINNED_LOCK_ACQUIRE)
        val lockTrap = script.indexOf(PINNED_LOCK_CLEANUP_TRAP)
        val ownerWrite = script.indexOf(PINNED_LOCK_OWNER_WRITE)
        val runningSchema = script.indexOf(PINNED_RUNNING_RECEIPT_SCHEMA)
        val runningWrite = script.indexOf(PINNED_RUNNING_RECEIPT_WRITE)
        val initialDisarm = script.indexOf(PINNED_LOCK_RELEASE_DISARM)
        val runningDisarm = script.indexOf(
            PINNED_LOCK_RELEASE_DISARM,
            initialDisarm + PINNED_LOCK_RELEASE_DISARM.length,
        )
        val passDisarm = script.indexOf(
            PINNED_LOCK_RELEASE_DISARM,
            runningDisarm + PINNED_LOCK_RELEASE_DISARM.length,
        )
        val runningArm = script.indexOf(PINNED_LOCK_RELEASE_ARM)
        val gradleHomePrepare = script.indexOf(PINNED_EPHEMERAL_GRADLE_HOME_PREPARE)
        val wrapperPreflight = script.indexOf(PINNED_WRAPPER_PREFLIGHT)
        val javaRuntimeValidation = script.indexOf(PINNED_JAVA_RUNTIME_VALIDATION)
        val androidSdkValidation = script.indexOf(PINNED_ANDROID_SDK_VALIDATION)
        val firstSelftest = script.indexOf(PINNED_MOTO_READONLY_SELFTEST_LINE)
        val fullHarness = script.indexOf(PINNED_FULL_HARNESS)
        val gradleHomeCleanup = script.indexOf(PINNED_EPHEMERAL_GRADLE_HOME_CLEANUP)
        val javaRuntimeCleanup = script.indexOf(PINNED_EPHEMERAL_JAVA_RUNTIME_CLEANUP)
        val passSchema = script.indexOf(PINNED_PASS_RECEIPT_SCHEMA)
        val passWrite = script.indexOf(PINNED_PASS_RECEIPT_WRITE)
        val postPassSourceCheck = script.indexOf(PINNED_POST_PASS_SOURCE_CHECK)
        val postPassRunnerCheck = script.indexOf(PINNED_POST_PASS_RUNNER_CHECK)
        val finalLockRelease = script.indexOf(PINNED_FINAL_LOCK_RELEASE)
        val terminalPass = script.indexOf(PINNED_TERMINAL_PASS)
        val terminalReceipt = script.indexOf(PINNED_TERMINAL_RECEIPT)
        if (!(zeroArgStart >= 0 && initialDisarm > zeroArgStart && lockTrap > initialDisarm &&
                receiptPrepare > lockTrap && gradleHomePrepare > receiptPrepare &&
                lockAcquire > gradleHomePrepare && ownerWrite > lockAcquire &&
                javaRuntimeValidation > ownerWrite && runningSchema > javaRuntimeValidation &&
                runningDisarm > runningSchema && runningWrite > runningDisarm &&
                runningArm > runningWrite && wrapperPreflight > runningArm &&
                androidSdkValidation > wrapperPreflight &&
                firstSelftest > androidSdkValidation)
        ) {
            add("lock ownership and RUNNING invalidation must precede host preflight and selftests")
        }
        if (!(fullHarness > firstSelftest && gradleHomeCleanup > fullHarness &&
                javaRuntimeCleanup > gradleHomeCleanup && passDisarm > javaRuntimeCleanup &&
                passSchema > passDisarm &&
                passWrite > passSchema && postPassSourceCheck > passWrite &&
                postPassRunnerCheck > postPassSourceCheck &&
                finalLockRelease > postPassRunnerCheck && terminalPass > finalLockRelease &&
                terminalReceipt > terminalPass)
        ) {
            add("terminal PASS may be emitted only after publication checks and verified lock release")
        }
        if ("/bin/rm -f -- \"\$receipt_path\"" in script || Regex("(?m)^\\s*mv\\b").containsMatchIn(script)) {
            add("receipt publication must not use pathname unlink or ambient mv handoff")
        }
        expectExactlyOnce(script, JAVA_HOME_MARKER, "JDK must be explicit")
        expectExactlyOnce(script, ANDROID_HOME_MARKER, "Android SDK must be explicit")
        if (Regex("(?m)^\\s*(exec\\s+)?gradle\\b").containsMatchIn(script)) {
            add("system Gradle is forbidden")
        }
        val shellSurface = shellWithoutHeredocsOrComments(script)
        val logicalShellSurface = shellSurface
            .replace("\\\r\n", " ")
            .replace("\\\n", " ")
        val executableLines = logicalShellSurface.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val tokenNormalizedScript = logicalShellSurface
            .replace("\\", "")
            .replace("\"", "")
            .replace("'", "")
        val directAdbLines = executableLines.map { line ->
            line.replace("ADB=/usr/bin/false", "DEVICE_CLIENT_DISABLED=/usr/bin/false")
        }
        val directAdbNormalizedScript = tokenNormalizedScript
            .replace("ADB=/usr/bin/false", "DEVICE_CLIENT_DISABLED=/usr/bin/false")
        if (directAdbLines.any(DIRECT_ADB_COMMAND::containsMatchIn) ||
            DIRECT_ADB_COMMAND.containsMatchIn(directAdbNormalizedScript)
        ) {
            add("host gate must not execute adb directly")
        }
        if (Regex("""\$'(?:\\.|[^'])*'""").containsMatchIn(shellSurface)) {
            add("host gate must not use ANSI-C shell quoting")
        }
        val heredocOperators = Regex("""<<-?[ \t]*+([^ \t\r\n;|&]+)""")
            .findAll(shellSurface)
        if (heredocOperators.any { match ->
                match.groupValues[1].firstOrNull() !in setOf('\'', '"')
            }
        ) {
            add("host gate heredocs must quote their delimiters")
        }

        // This is an enumerated, fail-closed defense-in-depth guard, not a proof
        // of Bash/Python semantics. Authority remains the independently reviewed
        // exact-HEAD artifact that the runner binds before publishing a receipt.
        val quotedHeredocScan = quotedHeredocs(script)
        if (quotedHeredocScan.unterminated || quotedHeredocScan.heredocs.size != 14) {
            add("host gate quoted Python heredoc surface changed")
        }
        val fixedPythonHeredocCommand = Regex(
            "(?m)(?:^|&&|\\|\\||(?<!&)&(?!&)|[;|(){}])\\s*" +
                "/usr/bin/python3\\s+-I\\s+-(?:\\s|$)",
        )
        if (quotedHeredocScan.heredocs.any { heredoc ->
                !fixedPythonHeredocCommand.containsMatchIn(heredoc.command)
            }
        ) {
            add("host gate heredocs must use the fixed isolated Python interpreter")
        }
        val heredocClassifications = quotedHeredocScan.heredocs.map { heredoc ->
            EXPECTED_HOST_GATE_PYTHON_HEREDOC_MARKERS.filter { marker ->
                marker in heredoc.body
            }
        }
        if (
            heredocClassifications.any { it.size != 1 } ||
            heredocClassifications.flatten().toSet() != EXPECTED_HOST_GATE_PYTHON_HEREDOC_MARKERS
        ) {
            add("host gate quoted Python heredoc classification changed")
        }
        val pythonInspections = quotedHeredocScan.heredocs.map { heredoc ->
            inspectPythonHeredoc(heredoc.body)
        }
        val pythonViolations = pythonInspections.flatMap(PythonHeredocInspection::violations)
        if (pythonViolations.isNotEmpty()) {
            add("host gate Python process-execution AST surface changed: ${pythonViolations.joinToString("; ")}")
        }
        if (
            pythonInspections.sumOf(PythonHeredocInspection::allowedLsRuns) != 5 ||
            pythonInspections.sumOf(PythonHeredocInspection::allowedGitRuns) != 1 ||
            pythonInspections.sumOf(PythonHeredocInspection::allowedCtypesLoads) != 1 ||
            pythonInspections.sumOf(PythonHeredocInspection::allowedJavaRuntimePopens) != 0
        ) {
            add("host gate Python process-execution structural counts changed")
        }

        val commandBoundary =
            "(?:^|&&|\\|\\||(?<!&)&(?!&)|[;|(){}]|" +
                "\\b(?:if|elif|while|until|then|else|do)\\b)"
        val dynamicCommandBoundary =
            "(?:^|&&|\\|\\||(?<!&)&(?!&)|[;|(){}]|\\$\\(|" +
                "\\b(?:if|elif|while|until|then|else|do)\\b)"
        val commandPrefixes =
            "[ \\t]*(?:![ \\t]*)?(?:(?:exec|command|builtin|eval|coproc|time|" +
                "(?:/usr/bin/)?nohup|(?:/usr/bin/)?nice)[ \\t]+)*+" +
                "(?:(?:/usr/bin/)?env\\b" +
                "(?:[ \\t]+(?:-[^ \\t\\r\\n]+|" +
                "[A-Za-z_][A-Za-z0-9_]*=[^ \\t\\r\\n]+))*[ \\t]+)?"
        val dynamicCommand = Regex(
            "(?m)$dynamicCommandBoundary$commandPrefixes" +
                "[\"']?(\\${'$'}(?:\\(|\\{[^}\\r\\n]+}|[A-Za-z_][A-Za-z0-9_]*|" +
                "[0-9]+|[@*#?!-]))",
        )
        val nonCommandExpressionRanges = Regex("""(?s)\[\[.*?]]|\(\(.*?\)\)""")
            .findAll(logicalShellSurface)
            .map { it.range }
            .toList()
        if (nonCommandExpressionRanges.any { expression ->
                val source = logicalShellSurface.substring(expression)
                Regex("""\$\((?!\()|`""").containsMatchIn(source.drop(2))
            }
        ) {
            add("host gate arithmetic expressions must not execute command substitutions")
        }
        val allowedDynamicCommands = setOf(
            "\$auto_wrapper",
            "\$qwy_wrapper",
            "${'$'}{auto_wrapper}",
            "${'$'}{qwy_wrapper}",
        )

        fun isAllowedDynamicCommand(command: DynamicShellCommand): Boolean {
            if (command.word in allowedDynamicCommands) return true
            if (command.word != "\$@") return false
            val lineStart = logicalShellSurface.lastIndexOf('\n', command.wordStart)
                .let { if (it < 0) 0 else it + 1 }
            val lineEnd = logicalShellSurface.indexOf('\n', command.wordStart)
                .let { if (it < 0) logicalShellSurface.length else it }
            val line = logicalShellSurface.substring(lineStart, lineEnd).trim()
            return line.startsWith("/usr/bin/env -i ") &&
                "ADB=/usr/bin/false" in line &&
                "ANDROID_HOME=\"\$host_android_home\"" in line &&
                "GRADLE_USER_HOME=\"\$host_gradle_user_home\"" in line &&
                "JAVA_HOME=\"\$host_java_home\"" in line &&
                "PATH=/usr/bin:/bin" in line &&
                line.endsWith("\"\$@\"")
        }

        val dynamicShellSurface = shellWithoutInertSingleQuotedText(logicalShellSurface)
        val unexpectedDynamicCommands = dynamicCommand.findAll(dynamicShellSurface)
            .map { match ->
                DynamicShellCommand(
                    word = match.groupValues[1],
                    wordStart = match.range.first,
                    expansionStart = match.groups[1]?.range?.first ?: match.range.first,
                )
            }
            .plus(dynamicShellCommandWords(logicalShellSurface).asSequence())
            .filterNot { command ->
                nonCommandExpressionRanges.any { expression ->
                    command.expansionStart in expression
                }
            }
            .filterNot(::isAllowedDynamicCommand)
            .distinctBy { command -> command.wordStart to command.word }
            .toList()
        if (unexpectedDynamicCommands.isNotEmpty()) {
            add(
                "host gate dynamic command surface changed: " +
                    unexpectedDynamicCommands.joinToString(", ") { it.word },
            )
        }
        val trapCommand = Regex("(?m)$commandBoundary\\s*trap(?:\\s|$)")
        val trapLines = executableLines.filter(trapCommand::containsMatchIn)
        val allowedTrapLines = listOf(
            "trap cleanup_host_gate_lock EXIT",
            "trap 'exit 129' HUP",
            "trap 'exit 130' INT",
            "trap 'exit 143' TERM",
        )
        if (trapLines != allowedTrapLines) {
            add("host gate trap surface changed")
        }
        val sourceCommand = Regex(
            "(?m)$commandBoundary$commandPrefixes(?:source|\\.)(?:\\s|$)",
        )
        if (sourceCommand.containsMatchIn(logicalShellSurface)) {
            add("host gate must not source an indirect command surface")
        }
        val fanOutCommand = Regex(
            "(?m)$commandBoundary$commandPrefixes" +
                "(?:[^\\s;|&(){}]+/)?(?:xargs|parallel)(?:\\s|$)",
        )
        if (fanOutCommand.containsMatchIn(logicalShellSurface)) {
            add("host gate must not use fan-out command dispatch")
        }
        val findExecCommand = Regex(
            "(?m)$commandBoundary$commandPrefixes" +
                "(?:[^\\s;|&(){}]+/)?find(?:\\s|$)[^\\r\\n]*" +
                "(?:^|\\s)-(?:exec|execdir)(?:\\s|$)",
        )
        if (findExecCommand.containsMatchIn(logicalShellSurface)) {
            add("host gate must not use find exec dispatch")
        }
        val genericDispatch = Regex(
            """(?:^|&&|\|\||(?<!&)&(?!&)|[;|(){}])\s*(?:exec\s+)?""" +
                """(?:command|builtin|eval)\b""",
        )
        if (executableLines.any(genericDispatch::containsMatchIn)) {
            add("host gate must not use generic shell command dispatch")
        }
        if (executableLines.any { line ->
                (line !in setOf(
                    PINNED_BASH_STARTUP_ENV_CLEAR,
                    PINNED_DEVELOPER_SELECTOR_CLEAR,
                    "unset inherited_environment_status",
                    "unset local_sdk_override",
                ) &&
                    Regex("^unset\\b").containsMatchIn(line)) ||
                    Regex("^(?:read|readarray|mapfile)\\b").containsMatchIn(line) ||
                    Regex("^printf\\b.*(?:^|\\s)-[A-Za-z]*v(?:\\s|[\"'\$])").containsMatchIn(line) ||
                    Regex("^(?:declare|typeset)\\b.*(?:^|\\s)-[A-Za-z]*n(?:\\s|[\"'\$])")
                        .containsMatchIn(line)
            }
        ) {
            add("host gate must not expose a shell variable rebinding surface")
        }
        if (script.lines().count { it == "unset local_sdk_override" } != 1) {
            add("host gate local SDK loop variable cleanup surface changed")
        }
        mapOf(
            "host_gradle_attestation_script" to 1,
            "android_sdk_validator" to 1,
            "java_profile_validator_test" to 1,
            "java_runtime_stager_test" to 1,
            "android_sdk_validator_test" to 1,
            "requested_java_home" to 1,
            "requested_android_home" to 1,
            "host_java_home" to 1,
            "host_java_binding" to 1,
            "host_java_profile_id" to 1,
            "host_java_vendor" to 1,
            "host_java_vm_vendor" to 1,
            "host_java_runtime_version" to 1,
            "host_java_tree_sha256" to 1,
            "host_java_darwin_temurin_profile_home" to 2,
            "host_java_temurin_profile_home" to 2,
            "host_android_home" to 1,
            "host_gradle_user_home" to 1,
            "host_child_home" to 1,
            "host_java_stage_root" to 1,
            "host_last_attestation_sha256" to 2,
            "auto_attestation_sha256" to 2,
            "qwy_attestation_sha256" to 2,
            "harness_attestation_sha256" to 2,
        ).forEach { (binding, expected) ->
            val assignments = Regex(
                "(?m)^\\s*(?:(?:local|readonly|declare|typeset|export)\\s+)*" +
                    Regex.escape(binding) + "\\s*(?:\\+)?=",
            ).findAll(script).count()
            if (assignments != expected) {
                add("$binding assignment surface changed")
            }
        }
        listOf("auto_wrapper", "qwy_wrapper").forEach { wrapper ->
            val assignments = Regex(
                "(?m)^\\s*(?:(?:local|readonly|declare|typeset|export)\\s+)*" +
                    Regex.escape(wrapper) + "\\s*(?:\\+)?=",
            ).findAll(script).count()
            if (assignments != 1) {
                add("$wrapper assignment surface changed")
            }
        }
        mapOf("auto_wrapper" to 5, "qwy_wrapper" to 3).forEach { (wrapper, expected) ->
            val references = Regex(
                "(?<![A-Za-z0-9_])" + Regex.escape(wrapper) + "(?![A-Za-z0-9_])",
            ).findAll(shellSurface).count()
            if (references != expected) {
                add("$wrapper identifier reference surface changed")
            }
        }
        val allowedShellScripts = listOf(
            PINNED_MOTO_READONLY_SELFTEST_LINE.trim(),
            PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE.trim(),
        )
        val indirectShell = Regex(
            """(?:^|&&|\|\||(?<!&)&(?!&)|[;|(){}])\s*""" +
                """(?:(?:exec|command|builtin|coproc|time|(?:/usr/bin/)?nohup)\s+)*""" +
                """(?:/bin/)?(?:bash|sh|zsh)\b""",
        )
        val shellInterpreterLines = executableLines.filter {
            indirectShell.containsMatchIn(it) ||
                Regex("^run_clean_host_command\\s+/bin/bash\\b").containsMatchIn(it)
        }
        if (shellInterpreterLines != allowedShellScripts) {
            add("host gate shell-script execution surface changed")
        }
        val scriptPathLines = executableLines.filter {
            Regex("(?:^|[\\s\\\"'])(?:[^\\s\\\"']+/)?[^\\s\\\"']+\\.sh(?:[\\s\\\"']|$)")
                .containsMatchIn(it)
        }
        val allowedScriptPaths = listOf(
            "local runner_relative_path=\"integration-tests/pr63-on-issue66/run-host-gate.sh\"",
        ) + allowedShellScripts
        if (scriptPathLines != allowedScriptPaths) {
            add("host gate indirect script surface changed")
        }
        if (executableLines.any { PRODUCTION_MOTO_COLLECTOR in it }) {
            add("host gate must not execute the production Moto collector")
        }
    }

    private fun serviceRouteViolations(source: String): List<String> = buildList {
        val ordered = listOf(
            SERVICE_PLAN_READ,
            SERVICE_DURABLE_PROVIDER,
            SERVICE_SIGNER_FROM_DURABLE_PROVIDER,
            SERVICE_PRINCIPAL_GUARD,
            SERVICE_ACQUIRE,
            SERVICE_READY_GATE,
            SERVICE_BACKEND,
            SERVICE_ENGINE_PARAMS,
            SERVICE_ENGINE_FACTORY,
        )
        ordered.forEach { marker ->
            expectExactlyOnce(source, marker, "missing production route marker: $marker")
        }
        expectExactlyOnce(source, SERVICE_GUARD_ARGS, "durable guard must consume captured P and S")
        expectExactlyOnce(source, SERVICE_ACQUIRE_ARGS, "registry must consume the same captured P and S")
        val positions = ordered.map(source::indexOf)
        if (positions.any { it < 0 } || positions.zipWithNext().any { (left, right) -> left >= right }) {
            add("production route must be Room plan -> guard -> acquire -> ready -> backend -> params -> engine")
        }
        if ("ProviderPackageTarget" in source || "ProviderPrincipal.selected" in source) {
            add("AutomationService must never select the provider from the current build")
        }
    }

    private fun qwyServiceRouteViolations(source: String): List<String> = buildList {
        listOf(
            QWY_DISCOVER_ROUTE,
            QWY_PREFLIGHT_ROUTE,
            QWY_APPLY_ROUTE,
            QWY_OBSERVE_ROUTE,
            QWY_RELEASE_ROUTE,
            QWY_ADVANCE_ROUTE,
            QWY_CALLING_UID,
            QWY_TYPED_SCOPE,
        ).forEach { marker ->
            expectExactlyOnce(source, marker, "missing exact QWY Service route/uid marker: $marker")
        }
        // Static wiring supplement only; real Android UID/exception restoration is exercised
        // by BinderIdentityInstrumentedTest, not inferred from source text or host mocks.
        val scope = source.substringAfter("internal inline fun <T> withProviderBinderIdentity", "")
            .substringBefore("\n}\n", "")
        val ordered = listOf(QWY_CALLING_UID, QWY_CLEAR_IDENTITY, "return try {",
            "block(callerUid)", "} finally {", QWY_RESTORE_IDENTITY)
        ordered.forEach { marker -> expectExactlyOnce(scope, marker, "missing identity scope marker: $marker") }
        val positions = ordered.map(scope::indexOf)
        if (positions.any { it < 0 } || positions.zipWithNext().any { (left, right) -> left >= right }) {
            add("identity scope must capture caller -> clear -> try block -> finally restore")
        }
    }

    private fun durableBridgeViolations(source: String): List<String> = buildList {
        val ordered = listOf(
            BRIDGE_PLAN_READ,
            BRIDGE_PROVIDER_FROM_PLAN,
            BRIDGE_SIGNER_FROM_DURABLE_PROVIDER,
            BRIDGE_PRINCIPAL_GUARD,
            BRIDGE_ACQUIRE,
            BRIDGE_ENGINE_PARAMS,
        )
        ordered.forEach { marker ->
            expectExactlyOnce(source, marker, "missing durable bridge marker: $marker")
        }
        val positions = ordered.map(source::indexOf)
        if (positions.any { it < 0 } || positions.zipWithNext().any { (left, right) -> left >= right }) {
            add("test bridge must be Room plan -> guard -> acquire -> production engine params")
        }
        if (Regex("long planId,\\s*String\\s+durableProviderApplicationId").containsMatchIn(source)) {
            add("tests must not supply the durable provider application id")
        }
        if ("recoveryCoordinator\$app_release" in source) {
            add("test bridge must use the Service composition oracle")
        }
        if ("ProviderPrincipal.INSTANCE.getSelected()" in source) {
            add("test bridge signer lookup must consume the durable plan provider")
        }
    }

    private fun MutableList<String>.expectExactlyOnce(
        script: String,
        expected: String,
        label: String,
    ) {
        if (script.windowed(expected.length).count { it == expected } != 1) add(label)
    }

    private fun MutableList<String>.expectExactlyOneLine(
        script: String,
        expected: String,
        label: String,
    ) {
        if (script.lineSequence().count { it == expected } != 1) add(label)
    }

    private fun aggregateReceiptSurfaceViolations(script: String): List<String> = buildList {
        if (Regex("(?m)^[ \\t]*(?:(?:readonly|export|declare)[ \\t]+)?HOST_RECEIPT(?:\\+)?=")
                .findAll(script).count() != 1
        ) {
            add("HOST_RECEIPT must have exactly one assignment")
        }
        if (Regex("(?m)^[ \\t]*(?:(?:readonly|export|declare)[ \\t]+)?HOST_RECEIPT_LOCK(?:\\+)?=")
                .findAll(script).count() != 1
        ) {
            add("HOST_RECEIPT_LOCK must have exactly one assignment")
        }
        if (script.lineSequence().count { it == PINNED_HOST_RECEIPT_ASSIGNMENT } != 1) {
            add("canonical host receipt assignment changed")
        }
        if (script.lineSequence().count { it == PINNED_HOST_LOCK_DERIVATION } != 1) {
            add("host receipt lock is not derived from the receipt sibling")
        }
        if (script.lineSequence().count { it == PINNED_HOST_RECEIPT_VALIDATION_CALL } != 1) {
            add("canonical host receipt validator call changed")
        }
        if (script.windowed(PINNED_HOST_RECEIPT_PATH.length)
                .count { it == PINNED_HOST_RECEIPT_PATH } != 1
        ) {
            add("canonical host receipt pathname escaped its sole assignment")
        }
        val receiptVariableReferences = Regex(
            """\${'$'}(?:HOST_RECEIPT(?![A-Za-z0-9_])|\{HOST_RECEIPT(?:[^A-Za-z0-9_][^}]*)?\})""",
        ).findAll(script).count()
        if (receiptVariableReferences != 2) {
            add("HOST_RECEIPT escaped the lock derivation/validator-call whitelist")
        }
    }

    private fun verifyHostReceiptFunction(repo: Path): String {
        val verifier = repo.resolve("scripts/verify-a-plus.sh").readText()
        val functionStart = verifier.indexOf("verify_host_receipt() {")
        val functionEndMarker = "\n}\n\nprintf 'verify-a-plus: stage=%s\\n'"
        val functionEnd = verifier.indexOf(functionEndMarker, functionStart)
        check(functionStart >= 0) { "host receipt validator function is missing" }
        check(functionEnd >= 0) { "host receipt validator end marker is missing" }
        return verifier.substring(functionStart, functionEnd + 2)
    }

    private fun String.quoteForPython(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"

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

    private companion object {
        val PYTHON_HEREDOC_AST_CHECKER =
            """
            import ast
            import sys

            def shape(node):
                return ast.dump(node, include_attributes=False)

            def expression(source):
                return ast.parse(source, mode="eval").body

            def assigned_value(source):
                return ast.parse(source).body[0].value

            expected_ls_run = shape(expression('''
            subprocess.run(
                ["/bin/ls", "-lde", os.fspath(path)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                env={"PATH": "/usr/bin:/bin", "LC_ALL": "C", "LANG": "C"},
                check=False,
            )
            '''))
            expected_git_run = shape(expression('''
            subprocess.run(
                git_prefix + list(arguments),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                env=git_environment,
                check=False,
            )
            '''))
            expected_git_prefix = shape(assigned_value('''
            git_prefix = [
                "/usr/bin/git",
                "--no-replace-objects",
                "-c", "core.hooksPath=/dev/null",
                "-c", "core.fsmonitor=false",
                "-c", "core.untrackedCache=false",
                "-c", "core.trustctime=true",
                "-c", "core.checkStat=default",
                "-c", "core.fileMode=true",
                "-c", "core.excludesFile=/dev/null",
                "-c", "core.attributesFile=/dev/null",
                "-c", "core.ignoreCase=false",
                "-C", repo_root,
            ]
            '''))
            expected_git_environment = shape(assigned_value('''
            git_environment = {
                "LC_ALL": "C",
                "LANG": "C",
                "PATH": "/usr/bin:/bin",
                "GIT_ATTR_NOSYSTEM": "1",
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_SYSTEM": "/dev/null",
                "GIT_CONFIG_GLOBAL": "/dev/null",
                "GIT_CONFIG_COUNT": "0",
                "GIT_OPTIONAL_LOCKS": "0",
            }
            '''))
            expected_java_runtime_function_node = ast.parse('''
            def run_bounded(arguments, *, cwd=None):
                process = subprocess.Popen(
                    arguments,
                    cwd=cwd,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    env={"HOME": "/nonexistent", "LANG": "C", "LC_ALL": "C", "PATH": "/usr/bin:/bin"},
                    start_new_session=True,
                )
                if process.stdout is None:
                    raise SystemExit(1)
                selector = selectors.DefaultSelector()
                selector.register(process.stdout, selectors.EVENT_READ)
                output = bytearray()
                deadline = time.monotonic() + 20.0
                failed = False
                try:
                    while selector.get_map():
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            failed = True
                            break
                        events = selector.select(min(remaining, 0.25))
                        if not events and process.poll() is not None:
                            events = [(key, selectors.EVENT_READ) for key in selector.get_map().values()]
                        for key, _ in events:
                            chunk = os.read(key.fd, 4096)
                            if not chunk:
                                selector.unregister(key.fileobj)
                                continue
                            output.extend(chunk)
                            if len(output) > 65536:
                                failed = True
                                break
                        if failed:
                            break
                    if failed:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    try:
                        status = process.wait(timeout=2.0)
                    except subprocess.TimeoutExpired:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                        process.wait(timeout=2.0)
                        failed = True
                finally:
                    selector.close()
                    process.stdout.close()
                if failed or status != 0:
                    raise SystemExit(1)
                try:
                    return bytes(output).decode("utf-8")
                except UnicodeDecodeError:
                    raise SystemExit(1)
            ''').body[0]
            expected_java_runtime_function = shape(expected_java_runtime_function_node)
            expected_java_popen = shape(next(
                node for node in ast.walk(expected_java_runtime_function_node)
                if isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "Popen"
            ))
            expected_java_bounded_calls = {
                shape(expression('''
            run_bounded([
                str(java),
                f"-Dissue66.hostGateChallenge={challenge}",
                "-XshowSettings:properties",
                "-version",
            ])
            ''')),
                shape(expression("run_bounded([str(java), str(probe)], cwd=directory)")),
            }
            expected_git_calls = {
                shape(expression("git_output('rev-parse', '--show-toplevel')")),
                shape(expression("git_output('rev-parse', '--verify', 'HEAD^{commit}')")),
                shape(expression("git_output('rev-parse', '--verify', 'HEAD^{tree}')")),
                shape(expression("git_output('ls-tree', '-rz', '--full-tree', source_head)")),
                shape(expression("git_output('ls-files', '--stage', '-v', '-z')")),
                shape(expression(
                    "git_output('ls-files', '--others', '-z', '--', "
                    "'.gitignore', ':(glob)**/.gitignore')"
                )),
                shape(expression(
                    "git_output('ls-files', '--others', '-z', "
                    "'--exclude-per-directory=.gitignore')"
                )),
            }
            expected_ctypes_block = shape(ast.parse('''
            if sys.platform == "darwin":
                import ctypes

                darwin_libc = ctypes.CDLL(None, use_errno=True)
                darwin_acl_get_fd = darwin_libc.acl_get_fd_np
                darwin_acl_get_fd.argtypes = [ctypes.c_int, ctypes.c_int]
                darwin_acl_get_fd.restype = ctypes.c_void_p
                darwin_acl_free = darwin_libc.acl_free
                darwin_acl_free.argtypes = [ctypes.c_void_p]
                darwin_acl_free.restype = ctypes.c_int
            ''').body[0])
            expected_ctypes_load = shape(expression("ctypes.CDLL(None, use_errno=True)"))
            expected_ctypes_set_errno = shape(expression("ctypes.set_errno(0)"))
            expected_ctypes_get_errno = shape(expression("ctypes.get_errno()"))
            expected_acl_get = shape(expression(
                "darwin_acl_get_fd(descriptor_fd, 0x00000100)"
            ))
            expected_acl_free = shape(expression("darwin_acl_free(acl)"))

            issues = []
            allowed_ls_runs = 0
            allowed_git_runs = 0
            allowed_ctypes_loads = 0
            allowed_java_runtime_popens = 0

            def issue(node, message):
                issues.append((getattr(node, "lineno", 0), message))

            def dotted_name(node):
                if isinstance(node, ast.Name):
                    return node.id
                if isinstance(node, ast.Attribute):
                    prefix = dotted_name(node.value)
                    if prefix is not None:
                        return prefix + "." + node.attr
                return None

            def target_root(node):
                if isinstance(node, ast.Name):
                    return node.id
                if isinstance(node, (ast.Attribute, ast.Subscript)):
                    return target_root(node.value)
                if isinstance(node, (ast.Tuple, ast.List)):
                    roots = {target_root(item) for item in node.elts}
                    roots.discard(None)
                    return next(iter(roots)) if len(roots) == 1 else None
                return None

            def assignment_values(tree, name):
                values = []
                for node in ast.walk(tree):
                    if isinstance(node, ast.Assign):
                        if any(isinstance(target, ast.Name) and target.id == name for target in node.targets):
                            values.append(node.value)
                    elif isinstance(node, ast.AnnAssign):
                        if isinstance(node.target, ast.Name) and node.target.id == name:
                            values.append(node.value)
                return values

            try:
                tree = ast.parse(sys.stdin.read())
            except SyntaxError as error:
                print("COUNTS\t0\t0\t0")
                print("ISSUE\t{}\tPython syntax is not statically inspectable".format(error.lineno or 0))
                raise SystemExit(0)

            parents = {
                child: parent
                for parent in ast.walk(tree)
                for child in ast.iter_child_nodes(parent)
            }

            sensitive_modules = {"subprocess", "os", "ctypes", "importlib"}
            module_aliases = {name: name for name in sensitive_modules}
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    for alias in node.names:
                        module_name = alias.name.split(".", 1)[0]
                        if module_name in sensitive_modules:
                            module_aliases[alias.asname or module_name] = module_name
                            if alias.asname is not None:
                                issue(node, "aliased sensitive-module import")
                elif isinstance(node, ast.ImportFrom):
                    if (node.module or "").split(".", 1)[0] in sensitive_modules:
                        issue(node, "from-import of process-execution module")

            sensitive_alias_assignments = set()
            aliases_changed = True
            while aliases_changed:
                aliases_changed = False
                for node in ast.walk(tree):
                    if isinstance(node, ast.Assign):
                        targets = node.targets
                        value = node.value
                    elif isinstance(node, (ast.AnnAssign, ast.NamedExpr)):
                        targets = [node.target]
                        value = node.value
                    else:
                        continue
                    if not isinstance(value, ast.Name) or value.id not in module_aliases:
                        continue
                    for target in targets:
                        if not isinstance(target, ast.Name):
                            continue
                        canonical_module = module_aliases[value.id]
                        if module_aliases.get(target.id) != canonical_module:
                            module_aliases[target.id] = canonical_module
                            aliases_changed = True
                        if target.id != value.id:
                            sensitive_alias_assignments.add(node)

            for node in sensitive_alias_assignments:
                issue(node, "sensitive process module is aliased")

            def resolved_dotted_name(node):
                name = dotted_name(node)
                if name is None:
                    return None
                root, separator, suffix = name.partition(".")
                canonical_root = module_aliases.get(root, root)
                return canonical_root + (separator + suffix if separator else "")

            ctypes_blocks = [
                node for node in tree.body
                if isinstance(node, ast.If) and shape(node) == expected_ctypes_block
            ]
            ctypes_block_nodes = {
                nested
                for block in ctypes_blocks
                for nested in ast.walk(block)
            }
            java_runtime_functions = [
                node for node in ast.walk(tree)
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                and node.name == "run_bounded"
                and shape(node) == expected_java_runtime_function
            ]
            java_runtime_function_nodes = {
                nested
                for function in java_runtime_functions
                for nested in ast.walk(function)
            }

            dangerous_os_names = {
                "system", "popen", "startfile", "fork", "forkpty",
                "posix_spawn", "posix_spawnp",
            }
            dynamic_builtin_names = {
                "eval", "exec", "compile", "__import__",
            }

            for node in ast.walk(tree):
                if not isinstance(node, ast.Call):
                    continue
                name = resolved_dotted_name(node.func)
                node_shape = shape(node)
                if name == "subprocess.run":
                    if node_shape == expected_ls_run:
                        allowed_ls_runs += 1
                    elif node_shape == expected_git_run:
                        allowed_git_runs += 1
                    else:
                        issue(node, "subprocess.run is outside the exact argv allowlist")
                elif name == "subprocess.Popen":
                    if node_shape == expected_java_popen and node in java_runtime_function_nodes:
                        allowed_java_runtime_popens += 1
                    else:
                        issue(node, "subprocess.Popen is outside the bounded Java-runtime probe")
                elif name is not None and name.startswith("subprocess."):
                    issue(node, "non-allowlisted subprocess execution")
                elif name is not None and name.startswith("os."):
                    member = name.split(".", 1)[1]
                    if member in dangerous_os_names or member.startswith("exec") or member.startswith("spawn"):
                        issue(node, "os process-execution call")
                elif name in dynamic_builtin_names:
                    issue(node, "dynamic Python execution")
                elif name is not None and name.startswith("importlib."):
                    issue(node, "dynamic importlib execution")
                elif name == "ctypes.CDLL":
                    if node_shape == expected_ctypes_load and node in ctypes_block_nodes:
                        allowed_ctypes_loads += 1
                    else:
                        issue(node, "ctypes loader is outside the exact ACL block")
                elif name is not None and name.startswith("ctypes."):
                    allowed = (
                        node_shape == expected_ctypes_set_errno or
                        node_shape == expected_ctypes_get_errno
                    )
                    if not allowed:
                        issue(node, "non-allowlisted ctypes call")
                elif name == "darwin_acl_get_fd":
                    if node_shape != expected_acl_get:
                        issue(node, "Darwin ACL getter call changed")
                elif name == "darwin_acl_free":
                    if node_shape != expected_acl_free:
                        issue(node, "Darwin ACL free call changed")
                elif name is not None and name.startswith("darwin_libc."):
                    issue(node, "direct Darwin libc execution")

                if name in {"getattr", "setattr", "delattr", "vars"} and node.args:
                    root = resolved_dotted_name(node.args[0])
                    attribute = None
                    if len(node.args) > 1 and isinstance(node.args[1], ast.Constant):
                        attribute = node.args[1].value
                    if root in {"subprocess", "ctypes", "importlib", "darwin_libc"}:
                        issue(node, "reflective process-execution lookup")
                    elif root == "os" and (
                        not isinstance(attribute, str) or
                        attribute in dangerous_os_names or
                        attribute.startswith("exec") or
                        attribute.startswith("spawn")
                    ):
                        issue(node, "reflective os process-execution lookup")

            for node in ast.walk(tree):
                if isinstance(node, ast.Attribute):
                    name = resolved_dotted_name(node)
                    if name is not None and name.startswith("subprocess."):
                        if node.attr not in {
                            "run", "Popen", "DEVNULL", "PIPE", "STDOUT", "TimeoutExpired"
                        }:
                            issue(node, "unknown subprocess attribute")
                        if node.attr in {"run", "Popen"}:
                            parent = parents.get(node)
                            if not isinstance(parent, ast.Call) or parent.func is not node:
                                issue(node, "indirect subprocess execution reference")
                    elif name is not None and name.startswith("ctypes."):
                        if node.attr not in {"CDLL", "set_errno", "get_errno", "c_int", "c_void_p"}:
                            issue(node, "unknown ctypes attribute")
                    elif name is not None and name.startswith("darwin_libc."):
                        if node not in ctypes_block_nodes:
                            issue(node, "Darwin libc attribute escaped the exact ACL block")
                if isinstance(node, (ast.Assign, ast.AnnAssign, ast.AugAssign, ast.NamedExpr)):
                    targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                    for target in targets:
                        root = target_root(target)
                        if root in {"subprocess", "ctypes", "darwin_libc"} and not (
                            root == "darwin_libc" and node in ctypes_block_nodes
                        ):
                            issue(node, "sensitive process module or handle is rebound")

            if allowed_git_runs:
                prefix_values = assignment_values(tree, "git_prefix")
                environment_values = assignment_values(tree, "git_environment")
                if len(prefix_values) != 1 or shape(prefix_values[0]) != expected_git_prefix:
                    issue(tree, "isolated Git argv prefix changed")
                if len(environment_values) != 1 or shape(environment_values[0]) != expected_git_environment:
                    issue(tree, "isolated Git environment changed")
                stores = [
                    node for node in ast.walk(tree)
                    if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store)
                    and node.id in {"git_prefix", "git_environment"}
                ]
                if len(stores) != 2:
                    issue(tree, "isolated Git binding is reassigned")
                actual_git_calls = [
                    shape(node) for node in ast.walk(tree)
                    if isinstance(node, ast.Call) and dotted_name(node.func) == "git_output"
                ]
                if len(actual_git_calls) != len(expected_git_calls) or set(actual_git_calls) != expected_git_calls:
                    issue(tree, "isolated Git call-site argv set changed")
                for node in ast.walk(tree):
                    if isinstance(node, ast.Call) and dotted_name(node.func) in {
                        "git_prefix.append", "git_prefix.extend", "git_prefix.insert",
                        "git_environment.update", "git_environment.setdefault",
                    }:
                        issue(node, "isolated Git binding is mutated")
                    if isinstance(node, ast.Subscript) and isinstance(node.ctx, ast.Store):
                        if target_root(node) in {"git_prefix", "git_environment"}:
                            issue(node, "isolated Git binding item is mutated")

            if allowed_java_runtime_popens:
                if len(java_runtime_functions) != 1:
                    issue(tree, "bounded Java-runtime process function changed")
                actual_bounded_calls = [
                    shape(node) for node in ast.walk(tree)
                    if isinstance(node, ast.Call) and dotted_name(node.func) == "run_bounded"
                ]
                if (
                    len(actual_bounded_calls) != len(expected_java_bounded_calls) or
                    set(actual_bounded_calls) != expected_java_bounded_calls
                ):
                    issue(tree, "Java-runtime probe call-site argv set changed")

            if allowed_ctypes_loads:
                if len(ctypes_blocks) != 1:
                    issue(tree, "Darwin ACL ctypes block changed")
                expected_calls_and_counts = {
                    expected_ctypes_set_errno: 2,
                    expected_ctypes_get_errno: 2,
                    expected_acl_get: 1,
                    expected_acl_free: 1,
                }
                all_call_shapes = [
                    shape(node) for node in ast.walk(tree) if isinstance(node, ast.Call)
                ]
                for expected_call, expected_count in expected_calls_and_counts.items():
                    if all_call_shapes.count(expected_call) != expected_count:
                        issue(tree, "Darwin ACL ctypes call count changed")

            print("COUNTS\t{}\t{}\t{}\t{}".format(
                allowed_ls_runs,
                allowed_git_runs,
                allowed_ctypes_loads,
                allowed_java_runtime_popens,
            ))
            for line, message in issues:
                print("ISSUE\t{}\t{}".format(line, message))
            """.trimIndent()

        const val QWY_FAKES =
            "../../../apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/Fakes.kt"
        const val QWY_HARNESS =
            "../../../apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/ProviderHarness.kt"
        const val FRIEND_SCOPE = "if (name == \"compileDebugUnitTestKotlin\") {"
        const val FRIEND_ARTIFACT = "componentFilter { it.displayName == \"project :qianwangyou:app\" }"
        const val AUTO_DEPENDENCY = "testImplementation(\"local.integration:cellrebel-auto-app\") {"
        const val AUTO_RELEASE_ATTRIBUTE =
            "objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, \"release\")"
        const val QWY_DEBUG_ATTRIBUTE =
            "objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, \"debug\")"
        const val RESOLVED_BOUNDARY_TASK =
            "tasks.register(\"verifyResolvedIntegrationBoundary\")"
        const val RESOLVED_DIRECT_BLOCK =
            "val expectedDirect = setOf(\n" +
                "            \"project :harness\",\n" +
                "            \"project :environment-control-v1\",\n" +
                "            \"project :cellrebel-auto:app\",\n" +
                "            \"project :qianwangyou:app\",\n" +
                "            \"junit:junit:4.13.2\",\n" +
                "            \"org.robolectric:robolectric:4.14.1\",\n" +
                "            \"androidx.test:core:1.6.1\",\n" +
                "            \"androidx.room:room-testing:2.7.1\",\n" +
                "            \"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0\",\n" +
                "            \"org.jetbrains.kotlin:kotlin-stdlib:2.2.10\",\n" +
                "        )"
        const val TEST_DEPENDS_ON_BOUNDARY =
            "tasks.matching { it.name == \"testDebugUnitTest\" }.configureEach {\n" +
                "    dependsOn(verifyResolvedIntegrationBoundary)"
        const val AUTO_CONTRACT_EXCLUDE =
            "exclude(group = \"CellRebelAuto\", module = \"environment-control-v1\")"
        const val QWY_CONTRACT_EXCLUDE =
            "exclude(group = \"FakeGPS\", module = \"environment-control-v1\")"
        const val AUTOMATION_SERVICE =
            "apps/cellrebel-auto/app/src/main/java/com/example/cellrebelauto/automation/AutomationService.kt"
        const val QWY_ENVIRONMENT_SERVICE =
            "apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/EnvironmentControlService.kt"
        const val DURABLE_BRIDGE =
            "integration-tests/pr63-on-issue66/harness/src/test/java/" +
                "io/github/terryyyc/fakexxx/integration/pr63issue66/AutoDurableJourneyBridge.java"
        const val SERVICE_PLAN_READ = "planRepository.getPlan(planId)"
        const val SERVICE_DURABLE_PROVIDER =
            "requireKnownApplicationId(plan.providerApplicationId)"
        const val SERVICE_SIGNER_FROM_DURABLE_PROVIDER =
            "val capturedProviderSigner =\n" +
                "                    com.example.cellrebelauto.environment.ProviderSignerDigest.normalizeOrNull(\n" +
                "                        com.example.cellrebelauto.environment.ProviderTrustGate\n" +
                "                            .packageManagerSignerDigest(packageManager, providerApplicationId)\n" +
                "                    )"
        const val SERVICE_PRINCIPAL_GUARD = "guardRecoveryProviderPrincipal("
        const val SERVICE_GUARD_ARGS =
            "guardRecoveryProviderPrincipal(\n                    planId,\n" +
                "                    providerApplicationId,\n" +
                "                    capturedProviderSigner,\n                )"
        const val SERVICE_ACQUIRE = "providerExecutorRegistry.value.acquire("
        const val SERVICE_ACQUIRE_ARGS =
            "providerExecutorRegistry.value.acquire(\n" +
                "                            providerApplicationId,\n" +
                "                            capturedProviderSigner"
        const val SERVICE_READY_GATE = "if (!providerReady) {"
        const val SERVICE_BACKEND = "APlusComposition.productionBackend("
        const val SERVICE_ENGINE_PARAMS = "APlusComposition.engineAplusParams(aplusBackend)"
        const val SERVICE_ENGINE_FACTORY = "AutomationEngineFactory.productionEngine("
        const val BRIDGE_PLAN_READ = "repository.getPlan(planId, continuation)"
        const val BRIDGE_PROVIDER_FROM_PLAN =
            "String durableProviderApplicationId = plan.getProviderApplicationId();"
        const val BRIDGE_SIGNER_FROM_DURABLE_PROVIDER =
            "String durableProviderSignerDigest = currentSignerResolver.apply(durableProviderApplicationId);"
        const val BRIDGE_PRINCIPAL_GUARD = "repository.guardRecoveryProviderPrincipal("
        const val BRIDGE_ACQUIRE = "registry.acquire(durableProviderApplicationId, durableProviderSignerDigest)"
        const val BRIDGE_ENGINE_PARAMS = "engineAplusParams\$app_release"
        const val QWY_DISCOVER_ROUTE = "typedResult { uid -> EnvironmentControlResultV1.discover(handler().discover(uid)) }"
        const val QWY_PREFLIGHT_ROUTE = "typedResult { uid -> EnvironmentControlResultV1.preflight(handler().preflight(uid, request)) }"
        const val QWY_APPLY_ROUTE = "typedResult { uid -> EnvironmentControlResultV1.apply(handler().apply(uid, request)) }"
        const val QWY_OBSERVE_ROUTE = "typedResult { uid -> EnvironmentControlResultV1.observe(handler().observe(uid, request)) }"
        const val QWY_RELEASE_ROUTE = "typedResult { uid -> EnvironmentControlResultV1.release(handler().release(uid, request)) }"
        const val QWY_ADVANCE_ROUTE = "typedResult { uid -> EnvironmentControlResultV1.completeAndAdvance(handler().completeAndAdvance(uid, request)) }"
        const val QWY_CALLING_UID = "val callerUid = Binder.getCallingUid()"
        const val QWY_CLEAR_IDENTITY = "val token = Binder.clearCallingIdentity()"
        const val QWY_RESTORE_IDENTITY = "Binder.restoreCallingIdentity(token)"
        const val QWY_TYPED_SCOPE = "withProviderBinderIdentity { callerUid -> toTypedResult { block(callerUid) } }"
        const val PINNED_AUTO_WRAPPER =
            "readonly auto_wrapper=\"\$repo_root/apps/cellrebel-auto/gradlew\""
        const val PINNED_QWY_WRAPPER =
            "readonly qwy_wrapper=\"\$repo_root/apps/qianwangyou/gradlew\""
        const val PINNED_JAVA_PROFILE_VALIDATOR =
            "readonly java_profile_validator=\"\$repo_root/scripts/validate-java17-runtime.py\""
        const val PINNED_JAVA_RUNTIME_STAGER =
            "readonly java_runtime_stager=\"\$repo_root/scripts/stage-java17-runtime.py\""
        const val PINNED_ANDROID_SDK_VALIDATOR =
            "readonly android_sdk_validator=\"\$repo_root/scripts/validate-android-sdk-runtime.py\""
        const val PINNED_JAVA_PROFILE_VALIDATOR_TEST =
            "readonly java_profile_validator_test=\"\$repo_root/scripts/test_validate_java17_runtime.py\""
        const val PINNED_JAVA_RUNTIME_STAGER_TEST =
            "readonly java_runtime_stager_test=\"\$repo_root/scripts/test_stage_java17_runtime.py\""
        const val PINNED_ANDROID_SDK_VALIDATOR_TEST =
            "readonly android_sdk_validator_test=\"\$repo_root/scripts/test_validate_android_sdk_runtime.py\""
        const val PINNED_FAIL_CLOSED_SHELL = "set -euo pipefail"
        const val PINNED_COLLECTOR_FAIL_CLOSED_SHELL = "set -uo pipefail"
        const val PINNED_PROJECT = "run_direct_gradle_command \"\$@\""
        const val PINNED_PRIVILEGED_BASH_SHEBANG = "#!/bin/bash -p"
        const val PINNED_BASH_STARTUP_ENV_CLEAR = "unset BASH_ENV ENV"
        const val PINNED_DEVELOPER_SELECTOR_CLEAR = "unset DEVELOPER_DIR SDKROOT TOOLCHAINS"
        const val PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR =
            "unset BASH_ENV ENV DEVELOPER_DIR SDKROOT TOOLCHAINS"
        val UNIFIED_BASH_STARTUP_CLEAR_TOPOLOGY =
            listOf(PINNED_UNIFIED_BASH_STARTUP_ENV_CLEAR)
        val SPLIT_BASH_STARTUP_CLEAR_TOPOLOGY =
            listOf(PINNED_BASH_STARTUP_ENV_CLEAR, PINNED_DEVELOPER_SELECTOR_CLEAR)
        const val PINNED_HOST_PATH_PIN = "PATH=/usr/bin:/bin"
        const val PINNED_HOST_PATH_EXPORT = "export PATH"
        const val PINNED_MOTO_READONLY_SELFTEST_LINE =
            "  run_clean_host_command /bin/bash -p \"\$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh\""
        const val PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE =
            "  run_clean_host_command /bin/bash -p \"\$repo_root/scripts/selftest-issue66-services-compatibility.sh\""
        const val PINNED_STANDALONE_RUNTIME_SECURITY_TESTS =
            "  if ! run_standalone_runtime_security_tests; then\n" +
                "    printf '%s\\n' 'HOST_GATE_STANDALONE_RUNTIME_SECURITY_TESTS_FAILED' >&2\n" +
                "    exit 1\n" +
                "  fi"
        const val TEST_SOURCE_HEAD = "1111111111111111111111111111111111111111"
        const val TEST_SOURCE_TREE = "2222222222222222222222222222222222222222"
        const val TEST_CHANGED_SOURCE_HEAD = "3333333333333333333333333333333333333333"
        const val TEST_CHANGED_SOURCE_TREE = "4444444444444444444444444444444444444444"
        const val TEST_JDK_PROFILE_ID = "darwin-aarch64-eclipse-temurin-17.0.20.1+1"
        const val TEST_JDK_RUNTIME_VERSION = "17.0.20.1+1"
        const val TEST_JDK_TREE_SHA256 =
            "f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8"
        const val VALIDATOR_RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val VALIDATOR_ATTESTATION_SPECS = listOf(
            Triple(
                "auto",
                ":app:testDebugUnitTest",
                "com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest",
            ),
            Triple(
                "qwy",
                ":app:testDebugUnitTest",
                listOf(
                    "name.caiyao.fakegps.hook.oracle.Android15OracleHookPlanTest",
                    "name.caiyao.fakegps.hook.oracle.SystemServerOracleWiringGuardTest",
                    "name.caiyao.fakegps.integration.v1.AuthoritativeAdvanceProviderTest",
                    "name.caiyao.fakegps.integration.v1.AuthoritativeOracleProductionGuardTest",
                    "name.caiyao.fakegps.integration.v1.BinderAuthoritativeContinuitySourceTest",
                    "name.caiyao.fakegps.oracle.OracleBundleCodecTest",
                ).sorted().joinToString(","),
            ),
            Triple(
                "harness",
                ":harness:testDebugUnitTest",
                listOf(
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HarnessBoundaryGuardTest",
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HostEphemeralCleanupGuardTest",
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HostReceiptModeGuardTest",
                    "io.github.terryyyc.fakexxx.integration.pr63issue66.HostRunnerEnvironmentGuardTest",
                ).sorted().joinToString(","),
            ),
        )
        const val MACHINE_READABLE_RUNNING =
            "{\"schemaVersion\":3,\"sourceHead\":\"0000000000000000000000000000000000000000\"," +
                "\"sourceTree\":\"0000000000000000000000000000000000000000\"," +
                "\"sourceState\":\"CLEAN\"," +
                "\"runnerSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"," +
                "\"runId\":\"00000000000000000000000000000000\"," +
                "\"hostIntegration\":\"RUNNING\",\"issue66Ac7\":\"NOT_PASSED\"," +
                "\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\"," +
                "\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\"," +
                "\"reason\":\"HOST_GATE_RUNNING_NO_PASS_RECEIPT\"}"
        const val PINNED_RUNNING_RECEIPT_SCHEMA =
            "\\\"gradleAttestationHarnessSha256\\\":\\\"\$harness_attestation_sha256\\\"," +
                "\\\"hostIntegration\\\":\\\"RUNNING\\\""
        const val PINNED_PASS_RECEIPT_SCHEMA =
            "\\\"gradleAttestationHarnessSha256\\\":\\\"\$harness_attestation_sha256\\\"," +
                "\\\"hostIntegration\\\":\\\"PASS\\\""
        const val PINNED_RUNNING_RECEIPT_WRITE =
            "  if ! active_receipt_identity=\"\$(write_receipt_atomically \"\$running_receipt\")\" ||"
        const val PINNED_PASS_RECEIPT_WRITE =
            "  if ! pass_receipt_identity=\"\$(write_receipt_atomically \"\$receipt\")\" ||"
        const val PINNED_POST_PASS_SOURCE_CHECK =
            "  if ! published_source_identity=\"\$(read_source_provenance)\" ||"
        const val PINNED_POST_PASS_RUNNER_CHECK =
            "  if ! published_runner_sha256=\"\$(read_runner_sha256 \"\$runner_path\")\" ||"
        const val PINNED_WRAPPER_PREFLIGHT =
            "for pinned_wrapper in \"\$auto_wrapper\" \"\$qwy_wrapper\"; do"
        const val PINNED_ZERO_ARG_RUNNING_PREFIX =
            "if [[ \"\$#\" -eq 0 ]]; then\n" +
                "  receipt_relative_dir=\"harness/build/reports/pr63-on-issue66\"\n" +
                "  receipt_dir=\"\$script_dir/\$receipt_relative_dir\"\n" +
                "  host_child_home=\"\"\n" +
                "  host_gradle_user_home=\"\"\n" +
                "  host_java_stage_root=\"\"\n" +
                "  host_last_attestation_sha256=\"\"\n" +
                "  auto_attestation_sha256=\"NOT_AVAILABLE_YET\"\n" +
                "  qwy_attestation_sha256=\"NOT_AVAILABLE_YET\"\n" +
                "  harness_attestation_sha256=\"NOT_AVAILABLE_YET\"\n" +
                "  child_home_owned=0\n" +
                "  gradle_home_owned=0\n" +
                "  java_stage_owned=0\n" +
                "  lock_owned=0\n" +
                "  lock_releasable=0\n" +
                "  trap cleanup_host_gate_lock EXIT\n" +
                "  trap 'exit 129' HUP\n" +
                "  trap 'exit 130' INT\n" +
                "  trap 'exit 143' TERM\n" +
                "  if ! prepare_private_directory \"\$script_dir\" \"\$receipt_relative_dir\"; then"
        const val PINNED_PRIVATE_UMASK = "umask 077"
        const val PINNED_RECEIPT_DIR_PREPARE =
            "  if ! prepare_private_directory \"\$script_dir\" \"\$receipt_relative_dir\"; then"
        const val PINNED_LOCK_ACQUIRE =
            "  if lock_base_identity=\"\$(\n" +
                "    create_host_gate_lock \"\$script_dir\" \"\$receipt_relative_dir\" \"\$lock_dir\"\n" +
                "  )\"; then"
        const val PINNED_LOCK_CLEANUP_TRAP = "  trap cleanup_host_gate_lock EXIT"
        const val PINNED_LOCK_OWNER_WRITE =
            "  if ! lock_identity=\"\$(\n" +
                "    write_private_file_exclusively \\\n" +
                "      \"\$script_dir\" \"\$receipt_relative_dir\" \"\$lock_owner_path\" \"\$run_owner\" \\\n" +
                "      \"\$lock_base_identity\"\n" +
                "  )\"; then"
        const val PINNED_LOCK_RELEASE_DISARM = "  lock_releasable=0"
        const val PINNED_LOCK_RELEASE_GUARD =
            "  if [[ \"\${lock_owned:-0}\" -eq 1 && \"\${lock_releasable:-0}\" -eq 1 ]]; then"
        const val PINNED_LOCK_OWNER_CLEANUP_GUARD =
            "    if release_host_gate_lock; then"
        const val PINNED_COOPERATIVE_RELEASE_BOUNDARY =
            "    # POSIX cannot atomically bind this sibling receipt check to rmdir.  This\n" +
                "    # fence covers cooperating runners and accidental path/inode races, not a\n" +
                "    # hostile same-EUID process; authority comes from the exact-HEAD CI artifact.\n" +
                "    validate_bound_receipt()\n" +
                "    os.close(receipt_fd)\n" +
                "    receipt_fd = None\n" +
                "    sync_directory(parent_fd)\n" +
                "    os.close(lock_fd)\n" +
                "    lock_fd = None\n" +
                "    # rmdir is the release commit.  A SIGKILL or host loss in the unavoidable\n" +
                "    # interval between the kernel completing rmdir and the caller observing this\n" +
                "    # return cannot be represented atomically; no terminal PASS is emitted in\n" +
                "    # that interval.  After this point, descriptor cleanup is best-effort so it\n" +
                "    # cannot turn an unlocked PASS into a reportable failure.\n" +
                "    os.rmdir(lock_name, dir_fd=parent_fd)"
        const val PINNED_LOCK_RELEASE_ARM = "  lock_releasable=1"
        const val PINNED_FINAL_LOCK_RELEASE =
            "  if ! release_host_gate_lock; then\n" +
                "    printf 'Host integration gate retained an ambiguous owner lock: %s\\n' \"\$lock_dir\" >&2\n" +
                "    exit 1\n" +
                "  fi\n" +
                "  lock_owned=0"
        const val PINNED_TERMINAL_PASS = "  echo \"HOST integration gate: PASS\""
        const val PINNED_TERMINAL_RECEIPT = "  printf '%s\\n' \"\$receipt\""
        const val PINNED_ATOMIC_RECEIPT_REPLACE =
            "    os.replace(\n" +
                "        temp_name,\n" +
                "        receipt_name,\n" +
                "        src_dir_fd=parent_fd,\n" +
                "        dst_dir_fd=parent_fd,\n" +
                "    )"
        const val PINNED_TEMP_IDENTITY_CLEANUP =
            "            if temp_identity is not None and dev_inode(current_temp_state) == temp_identity:"
        const val PINNED_POST_PUBLISH_BYTES_CHECK = "        or published_bytes != payload"
        const val PINNED_ZERO_ARG_SELFTEST_BLOCK =
            "if [[ \"\$#\" -eq 0 ]]; then\n" +
                PINNED_STANDALONE_RUNTIME_SECURITY_TESTS + "\n" +
                PINNED_MOTO_READONLY_SELFTEST_LINE + "\n" +
                PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE + "\n" +
                "  run_attested_gradle_test auto :app:testDebugUnitTest"
        const val PINNED_AUTO_ROUTING_PROJECT =
            "run_attested_gradle_test auto :app:testDebugUnitTest \\\n" +
                "    com.example.cellrebelauto.automation.ProviderPrincipalRoutingRedTest \\\n" +
                "    \"\$auto_wrapper\" -p \"\$repo_root/apps/cellrebel-auto\" \\\n" +
                "    :app:testDebugUnitTest \\\n" +
                "    --tests '*ProviderPrincipalRoutingRedTest' \\\n" +
                "    --no-daemon"
        const val PINNED_AUTO_ROUTING_TEST = "--tests '*ProviderPrincipalRoutingRedTest'"
        const val PINNED_QWY_PROJECT =
            "run_attested_gradle_test qwy :app:testDebugUnitTest \\\n" +
                "    name.caiyao.fakegps.hook.oracle.Android15OracleHookPlanTest," +
                "name.caiyao.fakegps.hook.oracle.SystemServerOracleWiringGuardTest," +
                "name.caiyao.fakegps.integration.v1.AuthoritativeOracleProductionGuardTest," +
                "name.caiyao.fakegps.integration.v1.BinderAuthoritativeContinuitySourceTest," +
                "name.caiyao.fakegps.oracle.OracleBundleCodecTest," +
                "name.caiyao.fakegps.integration.v1.AuthoritativeAdvanceProviderTest \\\n" +
                "    \"\$qwy_wrapper\" -p \"\$repo_root/apps/qianwangyou\" \\\n" +
                "    :app:testDebugUnitTest \\\n" +
                "    --tests '*Android15OracleHookPlanTest' \\\n" +
                "    --tests '*SystemServerOracleWiringGuardTest' \\\n" +
                "    --tests '*AuthoritativeOracleProductionGuardTest' \\\n" +
                "    --tests '*BinderAuthoritativeContinuitySourceTest' \\\n" +
                "    --tests '*OracleBundleCodecTest' \\\n" +
                "    --tests '*AuthoritativeAdvanceProviderTest' \\\n" +
                "    --no-daemon"
        const val PINNED_QWY_HOOK_PLAN = "--tests '*Android15OracleHookPlanTest'"
        const val PINNED_QWY_WIRING = "--tests '*SystemServerOracleWiringGuardTest'"
        const val PINNED_QWY_PRODUCTION = "--tests '*AuthoritativeOracleProductionGuardTest'"
        const val PINNED_QWY_ADAPTER = "--tests '*BinderAuthoritativeContinuitySourceTest'"
        const val PINNED_QWY_CODEC = "--tests '*OracleBundleCodecTest'"
        const val PINNED_QWY_ADVANCE = "--tests '*AuthoritativeAdvanceProviderTest'"
        const val PINNED_FULL_HARNESS =
            "run_attested_gradle_test harness :harness:testDebugUnitTest \\\n" +
                "    io.github.terryyyc.fakexxx.integration.pr63issue66." +
                "HarnessBoundaryGuardTest,io.github.terryyyc.fakexxx.integration.pr63issue66." +
                "HostRunnerEnvironmentGuardTest,io.github.terryyyc.fakexxx.integration." +
                "pr63issue66.HostReceiptModeGuardTest,io.github.terryyyc.fakexxx.integration." +
                "pr63issue66.HostEphemeralCleanupGuardTest \\\n" +
                "    \"\$auto_wrapper\" -p \"\$script_dir\" \\\n" +
                "    :harness:testDebugUnitTest \\\n" +
                "    --no-daemon"
        const val PINNED_ZERO_ARG_HOST_VERIFICATION_BLOCK =
            PINNED_STANDALONE_RUNTIME_SECURITY_TESTS + "\n" +
                PINNED_MOTO_READONLY_SELFTEST_LINE + "\n" +
                PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE + "\n" +
                "  " + PINNED_AUTO_ROUTING_PROJECT + "\n" +
                "  auto_attestation_sha256=\"\$host_last_attestation_sha256\"\n" +
                "  [[ \"\$auto_attestation_sha256\" =~ ^[0-9a-f]{64}\$ ]] || exit 1\n" +
                "  " + PINNED_QWY_PROJECT + "\n" +
                "  qwy_attestation_sha256=\"\$host_last_attestation_sha256\"\n" +
                "  [[ \"\$qwy_attestation_sha256\" =~ ^[0-9a-f]{64}\$ ]] || exit 1\n" +
                "  " + PINNED_FULL_HARNESS + "\n" +
                "  harness_attestation_sha256=\"\$host_last_attestation_sha256\"\n" +
                "  [[ \"\$harness_attestation_sha256\" =~ ^[0-9a-f]{64}\$ ]] || exit 1"
        const val PINNED_EPHEMERAL_GRADLE_HOME_PREPARE =
            "  if ! host_child_home=\"\$(create_ephemeral_child_home " +
                "\"\$receipt_dir\")\" ||\n" +
                "    [[ -z \"\$host_child_home\" ]]; then\n" +
                "    echo \"Host integration gate could not prepare its clean child " +
                "environment.\" >&2\n" +
                "    exit 1\n" +
                "  fi\n" +
                "  child_home_owned=1\n" +
                "  if ! host_gradle_user_home=\"\$(create_ephemeral_gradle_home " +
                "\"\$receipt_dir\")\" ||\n" +
                "    [[ -z \"\$host_gradle_user_home\" ]] ||\n" +
                "    ! validate_clean_gradle_user_home \"\$host_gradle_user_home\"; then\n" +
                "    echo \"Host integration gate could not prepare its clean child " +
                "environment.\" >&2\n" +
                "    exit 1\n" +
                "  fi\n" +
                "  gradle_home_owned=1\n" +
                "  readonly host_child_home host_gradle_user_home"
        const val PINNED_EPHEMERAL_GRADLE_HOME_CLEANUP =
            "  if ! validate_clean_gradle_user_home \"\$host_gradle_user_home\" ||\n" +
                "    ! remove_ephemeral_gradle_home \"\$receipt_dir\" " +
                "\"\$host_gradle_user_home\"; then\n" +
                "    printf '%s\\n' 'HOST_GATE_EPHEMERAL_GRADLE_HOME_CLEANUP_FAILED' >&2\n" +
                "    exit 1\n" +
                "  fi\n" +
                "  gradle_home_owned=0\n" +
                "  if ! remove_ephemeral_child_home \"\$receipt_dir\" " +
                "\"\$host_child_home\" ||\n" +
                "    [[ -e \"\$host_child_home\" || -L \"\$host_child_home\" ]]; then\n" +
                "    printf '%s\\n' 'HOST_GATE_EPHEMERAL_CHILD_HOME_CLEANUP_FAILED' >&2\n" +
                "    exit 1\n" +
                "  fi\n" +
                "  child_home_owned=0"
        const val PINNED_EPHEMERAL_JAVA_RUNTIME_CLEANUP =
            "  if ! verify_java_runtime_binding ||\n" +
                "    ! remove_ephemeral_java_runtime_root \"\$receipt_dir\" \"\$host_java_stage_root\" ||\n" +
                "    [[ -e \"\$host_java_stage_root\" || -L \"\$host_java_stage_root\" ]]; then\n" +
                "    printf '%s\\n' 'HOST_GATE_EPHEMERAL_JAVA_RUNTIME_CLEANUP_FAILED' >&2\n" +
                "    exit 1\n" +
                "  fi\n" +
                "  java_stage_owned=0"
        const val PINNED_JAVA_RUNTIME_VALIDATION =
            "  if ! host_java_stage_root=\"\$(create_ephemeral_java_runtime_root " +
                "\"\$receipt_dir\")\" ||\n" +
                "    [[ -z \"\$host_java_stage_root\" ]]; then\n" +
                "    printf '%s\\n' 'HOST_GATE_EPHEMERAL_JAVA_RUNTIME_PREPARATION_FAILED' >&2\n" +
                "    exit 1\n" +
                "  fi\n" +
                "  java_stage_owned=1\n" +
                "  if ! host_java_binding=\"\$(\n" +
                "    stage_java_runtime \"\$requested_java_home\" \"\$host_java_stage_root\"\n" +
                "  )\" || [[ -z \"\$host_java_binding\" ]]; then\n" +
                "    printf '%s\\n' 'HOST_GATE_JAVA_RUNTIME_INVALID' >&2\n" +
                "    exit 1\n" +
                "  fi"
        const val PINNED_ANDROID_SDK_VALIDATION =
            "if ! host_android_binding=\"\$(validate_android_sdk_root " +
                "\"\$requested_android_home\")\" ||\n" +
                "  [[ -z \"\$host_android_binding\" ]] ||\n" +
                "  ! verify_android_sdk_binding; then\n" +
                "  printf '%s\\n' 'HOST_GATE_ANDROID_SDK_INVALID' >&2\n" +
                "  exit 1\n" +
                "fi\n" +
                "readonly host_android_home host_android_binding"
        const val PINNED_ANDROID_SDK_RECHECK = "  if ! verify_android_sdk_binding; then"
        const val MACHINE_READABLE_BLOCKED =
            "{\"schemaVersion\":3,\"sourceHead\":\"0000000000000000000000000000000000000000\"," +
                "\"sourceTree\":\"0000000000000000000000000000000000000000\"," +
                "\"sourceState\":\"CLEAN\"," +
                "\"runnerSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"," +
                "\"runId\":\"00000000000000000000000000000000\"," +
                "\"hostIntegration\":\"PASS\",\"issue66Ac7\":\"NOT_PASSED\"," +
                "\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\"," +
                "\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\"," +
                "\"reason\":\"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__" +
                "ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION\"}"
        const val JAVA_HOME_MARKER = "\${JAVA_HOME:-}"
        const val ANDROID_HOME_MARKER = "\${ANDROID_HOME:-}"
        const val HOST_GATE_ALREADY_RUNNING_EXIT = 75
        const val PINNED_HOST_RECEIPT_PATH =
            "integration-tests/pr63-on-issue66/harness/build/reports/pr63-on-issue66/host-gate-receipt.json"
        const val PINNED_HOST_RECEIPT_ASSIGNMENT =
            "readonly HOST_RECEIPT=\"$PINNED_HOST_RECEIPT_PATH\""
        const val PINNED_HOST_LOCK_DERIVATION =
            "readonly HOST_RECEIPT_LOCK=\"\${HOST_RECEIPT%/*}/host-gate.lock\""
        const val PINNED_HOST_RECEIPT_VALIDATION_CALL =
            "      if verify_host_receipt \"\$HOST_RECEIPT\" \"\$HOST_RECEIPT_LOCK\" \"\$REPO_ROOT\" \"\$HOST_GATE_RUNNER\"; then"
        const val PRODUCTION_MOTO_COLLECTOR =
            "scripts/collect-issue66-moto-readonly-preflight.sh"
        val DIRECT_ADB_COMMAND =
            Regex("(?i)(?:^|[^A-Za-z0-9_])adb(?:[^A-Za-z0-9_]|$)")
        val EXPECTED_HOST_GATE_PYTHON_HEREDOC_MARKERS = setOf(
            "if any(name.startswith(b\"BASH_FUNC_\")",
            "field not in {",
            "raise SystemExit(\"unsafe private directory path\")",
            "raise SystemExit(\"unsafe host-gate lock path\")",
            "raise SystemExit(\"unsafe private output name\")",
            "git_prefix = [",
            "print(os.urandom(16).hex())",
            "runner exceeds the 1 MiB provenance ceiling",
            "host-gate receipt exceeds the 4096-byte contract ceiling",
            "invalid host-gate receipt identity",
            "name = f\"{prefix}.{secrets.token_hex(16)}\"",
            "def clear_directory(directory_fd, depth):",
            "def reject_startup_injection_entries():",
            "expected_keys = [",
        )
        val EXPECTED_DIRECT_DEPENDENCIES = listOf(
            "testImplementation(project(\":environment-control-v1\"))",
            "testImplementation(\"local.integration:cellrebel-auto-app\")",
            "testImplementation(\"local.integration:qianwangyou-app\")",
            "testImplementation(\"junit:junit:4.13.2\")",
            "testImplementation(\"org.robolectric:robolectric:4.14.1\")",
            "testImplementation(\"androidx.test:core:1.6.1\")",
            "testImplementation(\"androidx.room:room-testing:2.7.1\")",
            "testImplementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0\")",
        )
    }
}

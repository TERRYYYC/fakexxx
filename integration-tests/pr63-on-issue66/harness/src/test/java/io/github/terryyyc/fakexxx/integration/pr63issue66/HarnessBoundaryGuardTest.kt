package io.github.terryyyc.fakexxx.integration.pr63issue66

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            runner.replace(MACHINE_READABLE_BLOCKED, "{}"),
            runner.replace(MACHINE_READABLE_RUNNING, "{}"),
            runner.replace(PINNED_LOCK_ACQUIRE, "  if false; then"),
            runner.replace(PINNED_LOCK_CLEANUP_TRAP, "  : # owner cleanup trap deleted"),
            runner.replace(PINNED_STALE_RECEIPT_INVALIDATION, "  : # stale PASS invalidation deleted"),
            runner.replace(PINNED_LOCK_OWNER_WRITE, "  : # lock owner write deleted"),
            runner.replace(PINNED_LOCK_RELEASE_DISARM, "  lock_releasable=1"),
            runner.replace(PINNED_LOCK_RELEASE_ARM, "  : # lock release arm deleted"),
            runner.replace(PINNED_LOCK_RELEASE_GUARD, "  if [[ \"\${lock_owned:-0}\" -eq 1 ]]; then"),
            runner.replace(PINNED_LOCK_OWNER_CLEANUP_GUARD, "    if true; then"),
            runner.replace(PINNED_RECEIPT_MOVE_FAILURE_CLEANUP, "  mv -f \"\$receipt_tmp\" \"\$receipt_path\""),
            runner.replace(PINNED_RUNNING_RECEIPT_WRITE, "  : # RUNNING write deleted"),
            runner.replace(PINNED_PASS_RECEIPT_WRITE, "  : # PASS write deleted"),
            runner.replace(JAVA_HOME_MARKER, "\${UNPINNED_JAVA_HOME:-}"),
            runner.replace(ANDROID_HOME_MARKER, "\${UNPINNED_ANDROID_HOME:-}"),
        ).forEachIndexed { index, mutated ->
            assertTrue("runner mutation $index is a no-op", mutated != runner)
            assertTrue(
                "runner mutation $index escaped",
                runnerViolations(mutated).isNotEmpty(),
            )
        }
    }

    @Test
    fun `runner guard rejects quoted and nested-shell adb commands`() {
        val repo = findRepoRoot()
        val runner = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh").readText()
        assertEquals(emptyList<String>(), runnerViolations(runner))

        val mutations = linkedMapOf(
            "double-quoted adb" to runner + "\n\"adb\" devices\n",
            "single-quoted adb" to runner + "\n'adb' devices\n",
            "bash-c adb" to runner + "\nbash -c 'adb devices'\n",
            "command-substitution adb" to runner + "\nprobe=\"\$(adb devices)\"\n",
            "backtick adb" to runner + "\nprobe=`adb devices`\n",
            "environment-indirect adb" to runner + "\n\"\${ADB}\" devices\n",
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
    fun `offline verifier reads authenticated files through one no-follow descriptor`() {
        val repo = findRepoRoot()
        val collector = repo.resolve("scripts/collect-issue66-moto-readonly-preflight.sh").readText()
        val evidenceReader = collector
            .substringAfter("def stable_bytes(path, expected_mode=0o600):")
            .substringBefore("def stable_repo_bytes(path):")
        val repoReader = collector
            .substringAfter("def stable_repo_bytes(path):")
            .substringBefore("root_state = directory_state(root)")

        listOf("evidence reader" to evidenceReader, "repo reader" to repoReader).forEach {
                (label, source) ->
            assertTrue("$label must open a pinned file descriptor", source.contains("os.open("))
            assertTrue("$label must refuse symlink traversal", source.contains("O_NOFOLLOW"))
            assertTrue("$label must never block on a raced FIFO", source.contains("O_NONBLOCK"))
            assertTrue("$label must bind descriptor state", source.contains("os.fstat("))
            assertTrue("$label must read the opened descriptor", source.contains("os.read("))
            assertTrue("$label must not reopen the pathname", !source.contains("path.read_bytes()"))
        }
        assertTrue(
            "collector source must use the same stable repository reader",
            collector.contains("collector_bytes = stable_repo_bytes(collector_path)"),
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
            assertEquals(MACHINE_READABLE_RUNNING, receipt.readText().trim())
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `zero argument runner invalidates a stale pass before environment preflight`() {
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
            assertTrue(output, output.contains("JAVA_HOME must point to a JDK 17 runtime."))
            assertEquals(MACHINE_READABLE_RUNNING, receipt.readText().trim())
        } finally {
            isolated.close()
        }
    }

    @Test
    fun `failed first RUNNING replace cannot preserve a stale pass`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-gate-mv-fail-")
        val fakeMv = fakeBin.resolve("mv")
        try {
            Files.write(fakeMv, "#!/bin/sh\nexit 41\n".toByteArray())
            assertTrue("fake mv must be executable", fakeMv.toFile().setExecutable(true))
            Files.write(isolated.receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())

            val process = ProcessBuilder("/bin/bash", isolated.script.toString())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = fakeBin.toString() + ":" + System.getenv("PATH")
                    environment()["JAVA_HOME"] = System.getProperty("java.home")
                    environment()["ANDROID_HOME"] = requireNotNull(System.getenv("ANDROID_HOME"))
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 41, process.waitFor())
            assertTrue(
                "failed RUNNING replace preserved an authoritative stale PASS",
                !Files.exists(isolated.receipt) ||
                    isolated.receipt.readText().trim() != MACHINE_READABLE_BLOCKED,
            )
            assertTrue("failed RUNNING publication must leave its owner lock", Files.exists(isolated.lock))
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
            assertEquals(MACHINE_READABLE_RUNNING, isolated.receipt.readText().trim())
            assertTrue("ordinary failure must release the owned lock", !Files.exists(isolated.lock))
        } finally {
            fakeBin.toFile().deleteRecursively()
            isolated.close()
        }
    }

    @Test
    fun `failed lock owner publication preserves a stale pass behind the lock fence`() {
        val repo = findRepoRoot()
        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-gate-owner-fail-")
        val fakeMkdir = fakeBin.resolve("mkdir")
        try {
            Files.write(
                fakeMkdir,
                (
                    "#!/bin/sh\n" +
                        "/bin/mkdir \"\$@\"\n" +
                        "rc=\$?\n" +
                        "if [ \"\$rc\" -eq 0 ]; then\n" +
                        "  for arg in \"\$@\"; do\n" +
                        "    case \"\$arg\" in\n" +
                        "      *host-gate.lock) /bin/mkdir \"\$arg/owner\" ;;\n" +
                        "    esac\n" +
                        "  done\n" +
                        "fi\n" +
                        "exit \"\$rc\"\n"
                    ).toByteArray(),
            )
            assertTrue("fake mkdir must be executable", fakeMkdir.toFile().setExecutable(true))
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
    fun `failed stale receipt unlink leaves the host gate lock as a fence`() {
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
            assertTrue("failed unlink must retain the lock", Files.exists(isolated.lock))
            assertTrue(
                "lock owner must have been published before unlink",
                Files.isRegularFile(isolated.lock.resolve("owner")),
            )
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
            functionSource.contains("os.mkdir(lock_path"),
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

        val stateDir = Files.createTempDirectory("issue66-host-receipt-validator-")
        val receipt = stateDir.resolve("host-gate-receipt.json")
        val lock = stateDir.resolve("host-gate.lock")
        val probe = stateDir.resolve("verify-host-receipt.sh")
        try {
            Files.write(receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        functionSource + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\"\n"
                    ).toByteArray(),
            )

            val unlocked = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
            ).redirectErrorStream(true).start()
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
            val locked = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
            ).redirectErrorStream(true).start()
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
            val malformed = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
            ).redirectErrorStream(true).start()
            val malformedOutput = malformed.inputStream.bufferedReader().use { it.readText() }
            assertEquals(malformedOutput, 1, malformed.waitFor())
            assertTrue(malformedOutput, malformedOutput.contains("invalid host-gate JSON receipt"))
            assertTrue("failed validation leaked its owned lock", !Files.exists(lock))

            Files.write(receipt, (MACHINE_READABLE_RUNNING + "\n").toByteArray())
            val running = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                receipt.toString(),
                lock.toString(),
            ).redirectErrorStream(true).start()
            val runningOutput = running.inputStream.bufferedReader().use { it.readText() }
            assertEquals(runningOutput, 1, running.waitFor())
            assertTrue(runningOutput, runningOutput.contains("receipt contract mismatch"))
            assertTrue("contract failure leaked its owned lock", !Files.exists(lock))
        } finally {
            stateDir.toFile().deleteRecursively()
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
        val preLoadMarker = "        receipt = json.load(receipt_file)"
        val postContractMarker = "cleanup_error = None\n"
        val withPreOpen = withHelper.replace(
            preOpenMarker,
            "receipt_lock_rendezvous(\"pre-open\")\n" + preOpenMarker,
        )
        val withPreLoad = withPreOpen.replace(
            preLoadMarker,
            "        receipt_lock_rendezvous(\"pre-load\")\n" + preLoadMarker,
        )
        val instrumented = withPreLoad.replace(
            postContractMarker,
            "receipt_lock_rendezvous(\"post-contract\")\n" + postContractMarker,
        )
        assertTrue("pre-open rendezvous mutation is a no-op", withPreOpen != withHelper)
        assertTrue("pre-load rendezvous mutation is a no-op", withPreLoad != withPreOpen)
        assertTrue("post-contract rendezvous mutation is a no-op", instrumented != withPreLoad)

        val isolated = isolatedRunner(repo)
        val fakeBin = Files.createTempDirectory("issue66-host-validator-lifetime-")
        val probe = isolated.stateDir.resolve("verify-host-receipt-rendezvous.sh")
        val rendezvousPrefix = isolated.stateDir.resolve("validator")
        val stages = listOf("pre-open", "pre-load", "post-contract")
        var validator: Process? = null
        try {
            Files.write(isolated.receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())
            Files.write(
                probe,
                (
                    "#!/bin/bash\n" +
                        "set -uo pipefail\n" +
                        instrumented + "\n" +
                        "verify_host_receipt \"\$1\" \"\$2\"\n"
                    ).toByteArray(),
            )
            validator = ProcessBuilder(
                "/bin/bash",
                probe.toString(),
                isolated.receipt.toString(),
                isolated.lock.toString(),
            ).redirectErrorStream(true).apply {
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
                assertEquals(MACHINE_READABLE_BLOCKED, isolated.receipt.readText().trim())
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
        val readMarker = "validation_error = None\ntry:\n    with open(receipt_path"
        val rendezvous =
            "validation_error = None\n" +
                "with open(os.environ[\"HOST_RECEIPT_VALIDATOR_READY\"], \"w\") as ready_file:\n" +
                "    ready_file.write(\"ready\\n\")\n" +
                "while not os.path.exists(os.environ[\"HOST_RECEIPT_VALIDATOR_RELEASE\"]):\n" +
                "    __import__(\"time\").sleep(0.01)\n" +
                "try:\n" +
                "    with open(receipt_path"
        val instrumented = functionSource.replace(readMarker, rendezvous)
        assertTrue("receipt-read rendezvous mutation is a no-op", instrumented != functionSource)

        listOf("inode-replaced", "token-overwritten").forEach { mutation ->
            val stateDir = Files.createTempDirectory("issue66-host-validator-$mutation-")
            val receipt = stateDir.resolve("host-gate-receipt.json")
            val lock = stateDir.resolve("host-gate.lock")
            val probe = stateDir.resolve("verify-host-receipt-rendezvous.sh")
            val ready = stateDir.resolve("validator-ready")
            val release = stateDir.resolve("validator-release")
            var validator: Process? = null
            try {
                Files.write(receipt, (MACHINE_READABLE_BLOCKED + "\n").toByteArray())
                Files.write(
                    probe,
                    (
                        "#!/bin/bash\n" +
                            "set -uo pipefail\n" +
                            instrumented + "\n" +
                            "verify_host_receipt \"\$1\" \"\$2\"\n"
                        ).toByteArray(),
                )
                validator = ProcessBuilder(
                    "/bin/bash",
                    probe.toString(),
                    receipt.toString(),
                    lock.toString(),
                ).redirectErrorStream(true).apply {
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
            assertEquals(MACHINE_READABLE_RUNNING, isolated.receipt.readText().trim())

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

    private fun isolatedRunner(repo: Path): IsolatedHostGate {
        val stateDir = Files.createTempDirectory("issue66-host-gate-state-")
        val canonical = repo.resolve("integration-tests/pr63-on-issue66/run-host-gate.sh")
        val marker = "  receipt_dir=\"\$script_dir/harness/build/reports/pr63-on-issue66\""
        val source = canonical.readText()
        check(source.windowed(marker.length).count { it == marker } == 1) {
            "canonical host gate receipt directory marker changed"
        }
        val isolatedSource = source.replace(marker, "  receipt_dir='${stateDir.toAbsolutePath()}'")
        val script = Files.createTempFile(canonical.parent, ".run-host-gate-test-", ".sh")
        Files.write(script, isolatedSource.toByteArray())
        return IsolatedHostGate(
            script = script,
            stateDir = stateDir,
            receipt = stateDir.resolve("host-gate-receipt.json"),
            lock = stateDir.resolve("host-gate.lock"),
        )
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

    private fun runnerViolations(script: String): List<String> = buildList {
        expectExactlyOneLine(script, PINNED_FAIL_CLOSED_SHELL, "host runner must fail closed")
        expectExactlyOnce(script, PINNED_AUTO_WRAPPER, "Auto repository Gradle wrapper")
        expectExactlyOnce(script, PINNED_QWY_WRAPPER, "QWY repository Gradle wrapper")
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
        expectExactlyOnce(script, MACHINE_READABLE_BLOCKED, "machine-readable blocked receipt")
        expectExactlyOnce(script, MACHINE_READABLE_RUNNING, "stale PASS receipt invalidation")
        expectExactlyOnce(script, PINNED_LOCK_ACQUIRE, "exclusive host-gate lock acquisition")
        expectExactlyOnce(script, PINNED_LOCK_CLEANUP_TRAP, "host-gate lock cleanup trap")
        expectExactlyOnce(script, PINNED_STALE_RECEIPT_INVALIDATION, "stale PASS invalidation")
        expectExactlyOnce(script, PINNED_LOCK_OWNER_WRITE, "host-gate run ownership")
        expectExactlyOnce(script, PINNED_LOCK_RELEASE_DISARM, "host-gate lock release disarm")
        expectExactlyOnce(script, PINNED_LOCK_RELEASE_ARM, "host-gate lock release arm")
        expectExactlyOnce(script, PINNED_LOCK_RELEASE_GUARD, "host-gate lock release guard")
        expectExactlyOnce(
            script,
            PINNED_LOCK_OWNER_CLEANUP_GUARD,
            "host-gate lock owner cleanup guard",
        )
        expectExactlyOnce(
            script,
            PINNED_RECEIPT_MOVE_FAILURE_CLEANUP,
            "failed atomic receipt move cleanup",
        )
        expectExactlyOnce(script, PINNED_RUNNING_RECEIPT_WRITE, "atomic RUNNING receipt write")
        expectExactlyOnce(script, PINNED_PASS_RECEIPT_WRITE, "atomic PASS receipt write")
        val zeroArgStart = script.indexOf(PINNED_ZERO_ARG_RUNNING_PREFIX)
        val lockAcquire = script.indexOf(PINNED_LOCK_ACQUIRE)
        val lockTrap = script.indexOf(PINNED_LOCK_CLEANUP_TRAP)
        val staleInvalidation = script.indexOf(PINNED_STALE_RECEIPT_INVALIDATION)
        val ownerWrite = script.indexOf(PINNED_LOCK_OWNER_WRITE)
        val runningWrite = script.indexOf(PINNED_RUNNING_RECEIPT_WRITE)
        val lockReleaseArm = script.indexOf(PINNED_LOCK_RELEASE_ARM)
        val wrapperPreflight = script.indexOf(PINNED_WRAPPER_PREFLIGHT)
        val firstSelftest = script.indexOf(PINNED_MOTO_READONLY_SELFTEST_LINE)
        val fullHarness = script.indexOf(PINNED_FULL_HARNESS)
        val passWrite = script.indexOf(PINNED_PASS_RECEIPT_WRITE)
        if (!(zeroArgStart >= 0 && lockAcquire > zeroArgStart && lockTrap > lockAcquire &&
                ownerWrite > lockTrap && staleInvalidation > ownerWrite &&
                runningWrite > staleInvalidation && lockReleaseArm > runningWrite &&
                wrapperPreflight > lockReleaseArm && firstSelftest > wrapperPreflight)
        ) {
            add("lock ownership and RUNNING invalidation must precede host preflight and selftests")
        }
        if (passWrite <= fullHarness || fullHarness <= firstSelftest) {
            add("PASS receipt may be written only after complete host verification")
        }
        expectExactlyOnce(script, JAVA_HOME_MARKER, "JDK must be explicit")
        expectExactlyOnce(script, ANDROID_HOME_MARKER, "Android SDK must be explicit")
        if (Regex("(?m)^\\s*(exec\\s+)?gradle\\b").containsMatchIn(script)) {
            add("system Gradle is forbidden")
        }
        val executableLines = script.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (executableLines.any(DIRECT_ADB_COMMAND::containsMatchIn)) {
            add("host gate must not execute adb directly")
        }
        val allowedShellScripts = listOf(
            PINNED_MOTO_READONLY_SELFTEST_LINE.trim(),
            PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE.trim(),
        )
        val shellInterpreterLines = executableLines.filter {
            Regex("^(?:bash|sh|zsh)\\b").containsMatchIn(it)
        }
        if (shellInterpreterLines != allowedShellScripts) {
            add("host gate shell-script execution surface changed")
        }
        val scriptPathLines = executableLines.filter {
            Regex("(?:^|[\\s\\\"'])(?:[^\\s\\\"']+/)?[^\\s\\\"']+\\.sh(?:[\\s\\\"']|$)")
                .containsMatchIn(it)
        }
        if (scriptPathLines != allowedShellScripts) {
            add("host gate indirect script surface changed")
        }
        if (executableLines.any { Regex("^(?:source|\\.)\\s").containsMatchIn(it) }) {
            add("host gate must not source an indirect command surface")
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
            "auto_wrapper=\"\$repo_root/apps/cellrebel-auto/gradlew\""
        const val PINNED_QWY_WRAPPER =
            "qwy_wrapper=\"\$repo_root/apps/qianwangyou/gradlew\""
        const val PINNED_FAIL_CLOSED_SHELL = "set -euo pipefail"
        const val PINNED_PROJECT = "exec \"\$auto_wrapper\" -p \"\$script_dir\" \"\$@\""
        const val PINNED_MOTO_READONLY_SELFTEST_LINE =
            "  bash \"\$repo_root/scripts/selftest-issue66-moto-readonly-collector.sh\""
        const val PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE =
            "  bash \"\$repo_root/scripts/selftest-issue66-services-compatibility.sh\""
        const val MACHINE_READABLE_RUNNING =
            "{\"schemaVersion\":2,\"hostIntegration\":\"RUNNING\",\"issue66Ac7\":\"NOT_PASSED\",\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\",\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\",\"reason\":\"HOST_GATE_RUNNING_NO_PASS_RECEIPT\"}"
        const val PINNED_RUNNING_RECEIPT_WRITE =
            "  write_receipt_atomically \"\$running_receipt\""
        const val PINNED_PASS_RECEIPT_WRITE =
            "  write_receipt_atomically \"\$receipt\""
        const val PINNED_WRAPPER_PREFLIGHT =
            "for pinned_wrapper in \"\$auto_wrapper\" \"\$qwy_wrapper\"; do"
        const val PINNED_ZERO_ARG_RUNNING_PREFIX =
            "if [[ \"\$#\" -eq 0 ]]; then\n" +
                "  receipt_dir=\"\$script_dir/harness/build/reports/pr63-on-issue66\"\n" +
                "  mkdir -p \"\$receipt_dir\"\n" +
                "  receipt_path=\"\$receipt_dir/host-gate-receipt.json\"\n" +
                "  lock_dir=\"\$receipt_dir/host-gate.lock\"\n" +
                "  lock_owner_path=\"\$lock_dir/owner\""
        const val PINNED_LOCK_ACQUIRE = "  if ! mkdir \"\$lock_dir\" 2>/dev/null; then"
        const val PINNED_LOCK_CLEANUP_TRAP = "  trap cleanup_host_gate_lock EXIT"
        const val PINNED_STALE_RECEIPT_INVALIDATION =
            "  /bin/rm -f -- \"\$receipt_path\"\n  [[ ! -e \"\$receipt_path\" ]]"
        const val PINNED_LOCK_OWNER_WRITE =
            "  printf '%s\\n' \"\$run_owner\" >\"\$lock_owner_path\""
        const val PINNED_LOCK_RELEASE_DISARM = "  lock_releasable=0"
        const val PINNED_LOCK_RELEASE_GUARD =
            "  if [[ \"\${lock_owned:-0}\" -eq 1 && \"\${lock_releasable:-0}\" -eq 1 ]]; then"
        const val PINNED_LOCK_OWNER_CLEANUP_GUARD =
            "    if [[ ! -e \"\$lock_owner_path\" || \"\$current_owner\" == \"\$run_owner\" ]]; then"
        const val PINNED_LOCK_RELEASE_ARM = "  lock_releasable=1"
        const val PINNED_RECEIPT_MOVE_FAILURE_CLEANUP =
            "  mv -f \"\$receipt_tmp\" \"\$receipt_path\" || {"
        const val PINNED_ZERO_ARG_SELFTEST_BLOCK =
            "if [[ \"\$#\" -eq 0 ]]; then\n" +
                PINNED_MOTO_READONLY_SELFTEST_LINE + "\n" +
                PINNED_SERVICES_COMPATIBILITY_SELFTEST_LINE + "\n" +
                "  \"\$auto_wrapper\" -p \"\$repo_root/apps/cellrebel-auto\""
        const val PINNED_AUTO_ROUTING_PROJECT =
            "\"\$auto_wrapper\" -p \"\$repo_root/apps/cellrebel-auto\""
        const val PINNED_AUTO_ROUTING_TEST = "--tests '*ProviderPrincipalRoutingRedTest'"
        const val PINNED_QWY_PROJECT = "\"\$qwy_wrapper\" -p \"\$repo_root/apps/qianwangyou\""
        const val PINNED_QWY_HOOK_PLAN = "--tests '*Android15OracleHookPlanTest'"
        const val PINNED_QWY_WIRING = "--tests '*SystemServerOracleWiringGuardTest'"
        const val PINNED_QWY_PRODUCTION = "--tests '*AuthoritativeOracleProductionGuardTest'"
        const val PINNED_QWY_ADAPTER = "--tests '*BinderAuthoritativeContinuitySourceTest'"
        const val PINNED_QWY_CODEC = "--tests '*OracleBundleCodecTest'"
        const val PINNED_QWY_ADVANCE = "--tests '*AuthoritativeAdvanceProviderTest'"
        const val PINNED_FULL_HARNESS =
            "\"\$auto_wrapper\" -p \"\$script_dir\" :harness:testDebugUnitTest"
        const val MACHINE_READABLE_BLOCKED =
            "{\"schemaVersion\":2,\"hostIntegration\":\"PASS\",\"issue66Ac7\":\"NOT_PASSED\",\"emulator\":\"NOT_RUN\",\"physicalDevice\":\"NOT_RUN\",\"deviceFull\":\"BLOCKED\",\"overall\":\"BLOCKED\",\"reason\":\"HOST_GATE_HAS_NO_DEVICE_EVIDENCE__BOTH_ADMISSION_LISTS_EMPTY__ACTIVATION_CLEANUP_REBOOTS_AND_ADVERSARIAL_MUTATIONS_REQUIRE_ADDITIONAL_AUTHORIZATION\"}"
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
            "      if verify_host_receipt \"\$HOST_RECEIPT\" \"\$HOST_RECEIPT_LOCK\"; then"
        const val PRODUCTION_MOTO_COLLECTOR =
            "scripts/collect-issue66-moto-readonly-preflight.sh"
        val DIRECT_ADB_COMMAND =
            Regex("(?i)(?:^|[^A-Za-z0-9_])adb(?:[^A-Za-z0-9_]|$)")
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

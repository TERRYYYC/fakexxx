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
            runner.replace(PINNED_AUTO_WRAPPER, "auto_wrapper=\"gradle\""),
            runner.replace(PINNED_QWY_WRAPPER, "qwy_wrapper=\"gradle\""),
            runner.replace(PINNED_PROJECT, "\"\$@\""),
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
        expectExactlyOnce(script, PINNED_AUTO_WRAPPER, "Auto repository Gradle wrapper")
        expectExactlyOnce(script, PINNED_QWY_WRAPPER, "QWY repository Gradle wrapper")
        expectExactlyOnce(script, PINNED_PROJECT, "pinned integration project")
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
        expectExactlyOnce(script, JAVA_HOME_MARKER, "JDK must be explicit")
        expectExactlyOnce(script, ANDROID_HOME_MARKER, "Android SDK must be explicit")
        if (Regex("(?m)^\\s*(exec\\s+)?gradle\\b").containsMatchIn(script)) {
            add("system Gradle is forbidden")
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
        const val PINNED_PROJECT = "exec \"\$auto_wrapper\" -p \"\$script_dir\" \"\$@\""
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

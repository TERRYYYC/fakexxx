package com.example.cellrebelauto.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.integration.v1.EnvironmentControlClient
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
import com.example.cellrebelauto.recovery.ProviderExecutorAcquisition
import com.example.cellrebelauto.recovery.ProviderExecutorRegistry
import com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * PR #63 durable-principal RED: kill process/build routing fallback before adding persistence.
 * These tests deliberately exercise shipped composition/lifecycle entry points rather than a
 * parallel test-only selector. New work still derives its default from the single
 * [ProviderPrincipal] build selection; recovery is scoped by its durable Room owner.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderPrincipalRoutingRedTest {
    // Preserve the original explicit production-owner test in ordinary builds;
    // the codex variant has exactly one legal owner and must exercise that owner.
    private val explicitTarget = if (ProviderPrincipalBuild.isCodexBenchBuild) {
        "name.caiyao.fakegps.codexbench"
    } else {
        ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
    }

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/java/com/example/cellrebelauto/automation/AutomationService.kt").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private fun strippedSource(file: File): String {
        val noBlocks = file.readText().replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlocks.lineSequence().map { it.substringBefore("//") }.joinToString("\n")
    }

    private fun unexpectedTestOnlyPrincipalSeamReferences(
        sources: List<Pair<String, String>>,
    ): List<String> {
        val allowedBySymbol = mapOf(
            "testOnlyRecoveryProviderApplicationFailure" to setOf(
                "src/main/java/com/example/cellrebelauto/repository/PlanRepository.kt",
            ),
            "TestOnlyRoomDurableProviderPrincipalPreflight" to setOf(
                "src/main/java/com/example/cellrebelauto/automation/APlusComposition.kt",
                "src/main/java/com/example/cellrebelauto/recovery/DurableProviderPrincipalPreflight.kt",
            ),
        )
        return sources.flatMap { (path, source) ->
            allowedBySymbol.keys.mapNotNull { symbol ->
                if (source.contains(symbol) && path !in allowedBySymbol.getValue(symbol)) {
                    "$path:$symbol"
                } else {
                    null
                }
            }
        }.sorted()
    }

    @Test
    fun `selection is one pure function of build type`() {
        assertEquals(
            ContractV1.PROVIDER_APPLICATION_ID_BENCH,
            ProviderPrincipal.resolve(isDebugBuild = true),
        )
        assertEquals(
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
            ProviderPrincipal.resolve(isDebugBuild = false),
        )
        assertEquals(
            ProviderPrincipal.resolve(ProviderPrincipalBuild.isDebugBuild, ProviderPrincipalBuild.isCodexBenchBuild),
            ProviderPrincipal.selected,
        )
    }

    @Test
    fun `the Binder leg carries no independent default`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(ProviderPrincipal.selected, BinderExternalApplyExecutor(app).targetApplicationId)
    }

    @Test
    fun `diagnostic client cannot fall back to a different provider identity`() {
        assertEquals(listOf(ProviderPrincipal.selected), EnvironmentControlClient.PROVIDER_PACKAGES)
    }

    @Test
    fun `handshake probes only the selected provider and never its sibling`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val attemptedPackages = mutableListOf<String>()
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                attemptedPackages += requireNotNull(service.component).packageName
                return false
            }

            override fun unbindService(conn: ServiceConnection) {
                error("a bind request that returned false must not be unbound")
            }
        }

        val result = EnvironmentControlClient(recordingContext).handshake(timeoutMs = 1L)

        assertEquals(
            "a failed bind is terminal for the selected identity; sibling fallback is forbidden",
            listOf(ProviderPrincipal.selected),
            attemptedPackages,
        )
        assertEquals(
            listOf(ProviderPrincipal.selected),
            (result as EnvironmentControlClient.HandshakeResult.NotBindable).triedPackages,
        )
    }

    @Test
    fun `Binder executor exposes the exact applicationId it will bind`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val executor = BinderExternalApplyExecutor(
            app,
            providerApplicationId = explicitTarget,
        )

        val getter = executor.javaClass.methods.singleOrNull {
            it.name == "getTargetApplicationId" && it.parameterCount == 0
        }
        assertNotNull("the target identity must be observable to the composition guard", getter)
        assertEquals(
            explicitTarget,
            getter!!.invoke(executor),
        )
    }

    @Test
    fun `production composition rejects executor target and trust principal mismatch`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val productionExecutor = BinderExternalApplyExecutor(
                app,
                providerApplicationId = explicitTarget,
            )

            assertThrows(IllegalArgumentException::class.java) {
                ProviderScopedExternalApplyExecutor.wrap(
                    ContractV1.PROVIDER_APPLICATION_ID_BENCH,
                    productionExecutor,
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `production composition rejects an unknown provider identity before executor use`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val executor = BinderExternalApplyExecutor(app, explicitTarget)
            assertThrows(IllegalArgumentException::class.java) {
                ProviderScopedExternalApplyExecutor.wrap(
                    "unknown.provider",
                    executor,
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `production composition keeps one non-null scoped target through backend and coordinator`() {
        val signer =
            "sha256:ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val boundContext = object : ContextWrapper(app) {
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
                conn.onServiceConnected(requireNotNull(service.component), TestNoopEnvironmentControlService())
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                // Lifecycle call is covered separately; this test observes the cleared capability.
            }
        }
        val acquisition = ProviderExecutorRegistry(
            boundContext,
            currentSignerDigest = { signer },
        ) { applicationId ->
            BinderExternalApplyExecutor(boundContext, applicationId)
        }
            .acquire(explicitTarget, signer)
        try {
            kotlinx.coroutines.runBlocking {
                assertTrue(acquisition.awaitBound(1_000L))
            }
            val backend = APlusComposition.productionBackend(
                app,
                db,
                providerAcquisition = acquisition,
                providerSignerDigest = { signer },
                attemptValidityTimeoutMs = 90_000L,
            )

            assertEquals(
                explicitTarget,
                (backend.executor as ProviderScopedExternalApplyExecutor).targetApplicationId,
            )
            assertEquals(
                "the coordinator cannot lose the production executor principal",
                explicitTarget,
                APlusComposition.recoveryCoordinator(backend).targetApplicationId,
            )
            val factory = APlusComposition::class.java.methods.single {
                it.name.startsWith("productionBackend") &&
                    it.parameterTypes.getOrNull(2) == ProviderExecutorAcquisition::class.java
            }
            assertEquals(
                "the production factory type rejects generic/unscoped executors",
                ProviderExecutorAcquisition::class.java,
                factory.parameterTypes[2],
            )
        } finally {
            acquisition.close()
            org.junit.Assert.assertFalse(
                "closing the production capability clears its published Binder",
                (acquisition.executor as BinderExternalApplyExecutor).isBound,
            )
            db.close()
        }
    }

    @Test
    fun `production engine factory rejects an unscoped coordinator`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val unscopedCoordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
                com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor(),
                com.example.cellrebelauto.recovery.FakeDurableRecoveryLog(),
            )
            val evidence = object : com.example.cellrebelauto.automation.aplus.APlusEvidenceSource {
                override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) =
                    null as com.example.cellrebelauto.environment.ObservationSnapshot?

                override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) =
                    null as com.example.cellrebelauto.environment.ObservationSnapshot?

                override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) =
                    null as com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence?
            }

            assertThrows(IllegalArgumentException::class.java) {
                AutomationEngineFactory.productionEngine(
                    planId = 1L,
                    planRepository = com.example.cellrebelauto.repository.PlanRepository(db),
                    cellRebelRunner = object : CellRebelRunner {
                        override suspend fun runTest(
                            startedAt: Long,
                            testTimeoutMs: Long,
                            onRunningObserved: suspend (Long) -> Unit,
                        ) = AttemptOutcome.Failure(
                            FailureReason.UNTRUSTED,
                            "not used",
                            startedAt,
                            startedAt,
                        )
                    },
                    gpsSetter = object : GpsLocationSetter {
                        override suspend fun setLocation(lat: Double, lng: Double) =
                            GpsOutcome.Failed(FailureReason.FAKE_GPS_NOT_ACTIVE, "not used")
                    },
                    globalBufferSeconds = 0,
                    testTimeoutMs = 1L,
                    gpsSettleMs = 0L,
                    stageToggles = {
                        com.example.cellrebelauto.model.plan.StageToggles(
                            locationStageEnabled = true,
                            testStageEnabled = true,
                        )
                    },
                    auditDao = db.auditEventDao(),
                    aplusCoordinator = unscopedCoordinator,
                    aplusEvidence = evidence,
                    bridge = null,
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `production engine factory rejects a scoped coordinator with a test-only unchecked owner seam`() {
        val scopedButUnchecked = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            ProviderScopedExternalApplyExecutor.wrap(
                explicitTarget,
                com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor(),
            ),
            com.example.cellrebelauto.recovery.FakeDurableRecoveryLog(),
        )

        assertEquals(
            explicitTarget,
            scopedButUnchecked.targetApplicationId,
        )
        assertEquals(false, scopedButUnchecked.hasDurableProviderPrincipalPreflight)
        assertThrows(IllegalArgumentException::class.java) {
            AutomationEngineFactory.requireProductionCoordinator(scopedButUnchecked)
        }
    }

    @Test
    fun `a raw executor wrapped with a claimed target cannot forge production capability`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val raw = com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor()
        try {
            // This is the exact public-forgery chain from the review: an arbitrary raw executor is
            // given a claimed target, then combined with a real Room preflight. Neither fact proves
            // that ProviderExecutorRegistry acquired, exact-bound, and readied this executor.
            val backend = APlusComposition.testOnlyBackend(
                context = app,
                db = db,
                providerExecutor = ProviderScopedExternalApplyExecutor.wrap(
                    explicitTarget,
                    raw,
                ),
                providerSignerDigest = { "sha256:3c8acf667613543c77f23ebe1d934d56e08f94b7deee67b173cc9016baf6b381" },
                attemptValidityTimeoutMs = 90_000L,
            )
            val forgedCoordinator = APlusComposition.recoveryCoordinator(backend)

            assertTrue(
                "production composition must expose no raw/scoped executor overload",
                APlusComposition::class.java.methods.none { method ->
                    method.name.startsWith("productionBackend") &&
                        method.parameterTypes.any { it == ProviderScopedExternalApplyExecutor::class.java }
                },
            )

            assertThrows(IllegalArgumentException::class.java) {
                AutomationEngineFactory.requireProductionCoordinator(forgedCoordinator)
            }
            assertTrue("construction rejection precedes apply/release", raw.lifecycleEvents.isEmpty())
            assertEquals("construction rejection precedes discover", 0, raw.discoverCalls)
            assertTrue("construction rejection precedes preflight", raw.preflightCalls.isEmpty())
            assertTrue("construction rejection precedes observe", raw.observeCalls.isEmpty())
            assertTrue("construction rejection precedes advance", raw.advanceCalls.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `AutomationService is wired only through the production engine factory`() {
        val mainRoot = File(moduleRoot, "src/main/java/com/example/cellrebelauto")
        val mainSources = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to strippedSource(it) }
            .toList()
        val directConstructors = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AutomationEngineFactory.kt" }
            .filter { it.name != "AutomationEngine.kt" }
            .map { it to strippedSource(it) }
            .filter { (_, source) -> Regex("""\bAutomationEngine\s*\(""").containsMatchIn(source) }
            .map { (file, _) -> file.relativeTo(moduleRoot).path }
            .toList()
        assertEquals(
            "main code must not bypass the guarded production factory",
            emptyList<String>(),
            directConstructors,
        )
        assertEquals(
            "main code must never call the explicit test-only backend seam",
            emptyList<String>(),
            mainSources
                .filter { (_, source) ->
                    Regex("""\bAPlusComposition\.testOnlyBackend\s*\(""")
                        .containsMatchIn(source)
                }
                .map { (file, _) -> file.relativeTo(moduleRoot).path },
        )
        assertEquals(
            "main code must never promote a claimed target wrapper into production",
            emptyList<String>(),
            mainSources
                .filter { (_, source) ->
                    Regex("""\bProviderScopedExternalApplyExecutor\.wrap\s*\(""")
                        .containsMatchIn(source)
                }
                .map { (file, _) -> file.relativeTo(moduleRoot).path },
        )
        assertEquals(
            "only the registry file may implement the acquisition interface",
            emptyList<String>(),
            mainSources
                .filter { (file, source) ->
                    file.name != "ProviderExecutorRegistry.kt" &&
                        Regex("""\)\s*:\s*ProviderExecutorAcquisition\s*\{""")
                            .containsMatchIn(source)
                }
                .map { (file, _) -> file.relativeTo(moduleRoot).path },
        )
        val registrySource = strippedSource(
            File(
                moduleRoot,
                "src/main/java/com/example/cellrebelauto/recovery/ProviderExecutorRegistry.kt",
            )
        )
        assertTrue(
            "the actual registry-issued acquisition is file-private and cannot be constructed elsewhere",
            Regex("""private\s+class\s+RegistryIssuedProviderExecutorAcquisition\b""")
                .containsMatchIn(registrySource),
        )

        val service = strippedSource(
            File(moduleRoot, "src/main/java/com/example/cellrebelauto/automation/AutomationService.kt")
        )
        assertEquals(
            "AutomationService must construct exactly one engine through productionEngine",
            1,
            Regex("""AutomationEngineFactory\.productionEngine\s*\(""")
                .findAll(service).count(),
        )
        assertTrue(
            "a readiness failure must persist the typed pause and return before backend/engine construction",
            Regex(
                """if\s*\(\s*!providerReady\s*\)\s*\{[\s\S]*?persistProviderPrincipalRecovery\([\s\S]*?return@launch[\s\S]*?\}[\s\S]*?APlusComposition\.productionBackend"""
            ).containsMatchIn(service),
        )
        assertTrue(
            "restored Room (applicationId, signer) owners must be checked before registry acquisition",
            service.indexOf("guardRecoveryProviderPrincipal(") in
                0 until service.indexOf("providerExecutorRegistry.value.acquire("),
        )
        assertTrue(
            "the Service must capture the exact signer before the Room owner join",
            service.indexOf("val capturedProviderSigner") in
                0 until service.indexOf("guardRecoveryProviderPrincipal("),
        )
        assertTrue(
            "the same captured signer must scope both the durable join and registry capability",
            Regex(
                """guardRecoveryProviderPrincipal\s*\(\s*planId\s*,\s*providerApplicationId\s*,\s*capturedProviderSigner\s*,?\s*\)[\s\S]*?providerExecutorRegistry\.value\.acquire\s*\(\s*providerApplicationId\s*,\s*capturedProviderSigner"""
            ).containsMatchIn(service),
        )
        assertEquals(
            "P-only fixture seams must have no main caller outside their exact definitions/composition",
            emptyList<String>(),
            unexpectedTestOnlyPrincipalSeamReferences(
                mainSources.map { (file, source) -> file.relativeTo(moduleRoot).path to source },
            ),
        )
        val repositorySource = mainSources.single { (file, _) -> file.name == "PlanRepository.kt" }.second
        assertEquals(
            "the repository P-only seam is one definition, never a production call",
            1,
            Regex("""fun\s+testOnlyRecoveryProviderApplicationFailure\s*\(""")
                .findAll(repositorySource).count(),
        )
        assertEquals(
            "the repository definition is the only occurrence of the P-only seam symbol",
            1,
            Regex("""\btestOnlyRecoveryProviderApplicationFailure\b""")
                .findAll(repositorySource).count(),
        )
        val preflightSource = mainSources.single {
            (file, _) -> file.name == "DurableProviderPrincipalPreflight.kt"
        }.second
        assertEquals(
            "the P-only preflight seam is one explicit type declaration",
            1,
            Regex("""class\s+TestOnlyRoomDurableProviderPrincipalPreflight\s*\(""")
                .findAll(preflightSource).count(),
        )
        assertEquals(
            "the preflight definition file contains no second seam call",
            1,
            Regex("""\bTestOnlyRoomDurableProviderPrincipalPreflight\b""")
                .findAll(preflightSource).count(),
        )
        val compositionSource = mainSources.single { (file, _) -> file.name == "APlusComposition.kt" }.second
        assertEquals(
            "only testOnlyBackend constructs the P-only preflight",
            1,
            Regex("""\?:\s*TestOnlyRoomDurableProviderPrincipalPreflight\s*\(""")
                .findAll(compositionSource).count(),
        )
        assertEquals(
            "APlusComposition contains exactly one import and one testOnlyBackend construction",
            2,
            Regex("""\bTestOnlyRoomDurableProviderPrincipalPreflight\b""")
                .findAll(compositionSource).count(),
        )
        assertTrue(
            "the production Service must keep acquisition, readiness, and engine dispatch on Main",
            Regex(
                """CoroutineScope\s*\(\s*SupervisorJob\s*\(\s*\)\s*\+\s*Dispatchers\.Main\s*\)"""
            ).containsMatchIn(service),
        )

        val engineSource = File(
            moduleRoot,
            "src/main/java/com/example/cellrebelauto/automation/AutomationEngine.kt",
        ).readText()
        assertTrue(
            "the production engine constructor must be module-internal so external callers cannot bypass the factory",
            Regex("""class\s+AutomationEngine\s+internal\s+constructor\s*\(""")
                .containsMatchIn(engineSource),
        )
    }

    @Test
    fun `production wiring guard catches a new caller of a test-only principal seam`() {
        val unexpected = unexpectedTestOnlyPrincipalSeamReferences(
            listOf(
                "src/main/java/com/example/cellrebelauto/automation/UnexpectedProductionCaller.kt" to
                    "fun build(db: AppDatabase) = TestOnlyRoomDurableProviderPrincipalPreflight(db)",
            ),
        )

        assertEquals(
            listOf(
                "src/main/java/com/example/cellrebelauto/automation/UnexpectedProductionCaller.kt:" +
                    "TestOnlyRoomDurableProviderPrincipalPreflight",
            ),
            unexpected,
        )
    }
}

package com.example.cellrebelauto.cutover

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the complete production Room/DataStore ownership census for CUT-A26. */
class CutoverProductionConsumerCensusTest {
    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/java/com/example/cellrebelauto/CellRebelAutoApp.kt").isFile }
        ?: error("cannot locate app module")
    private val sourceRoot = File(moduleRoot, "src/main/java/com/example/cellrebelauto")

    @Test
    fun applicationReadsDurableJournalBeforeConstructingTheOnlyProductionDatabaseAndStore() {
        val app = source("CellRebelAutoApp.kt")
        val startup = app.substringAfter("override fun onCreate")
        val journal = startup.indexOf("cutoverControlStore.read()")
        val gate = startup.indexOf("CutoverAccessGate.fromJournal")

        assertTrue("application must read the durable cutover journal", journal >= 0)
        assertTrue("journal-derived gate must be created after the read", gate > journal)
        assertTrue("an unreadable journal must fail closed", startup.contains("CutoverAccessGate.recoveryRequired()"))
        assertTrue(app.contains("cutover gate must initialize before Room"))
        assertTrue(app.contains("cutover gate must initialize before PlanConfig"))
    }

    @Test
    fun directRoomConsumersAreExactlyTheDeclaredOwnersAndEveryRuntimeOwnerNamesTheGate() {
        val consumers = kotlinSources()
            .filter { (_, code) -> Regex("""\.[A-Za-z0-9]+Dao\(""").containsMatchIn(code) }
            .map { (file, _) -> relative(file) }
            .sorted()

        assertEquals(
            listOf(
                "automation/APlusComposition.kt",
                "automation/AutomationService.kt",
                // Rebase note: T3's remote-control receiver is a declared direct
                // Room consumer (adb STATUS plan read + audit append). It
                // resolves the DB and gate through the app-singleton-derived
                // seams (CellRebelAutoApp.databaseFor / accessGateFor), so the
                // CUT-A26 ownership stays closed and gated.
                "remote/RemoteControlReceiver.kt",
                "repository/PlanRepository.kt",
                "ui/MainViewModel.kt"
            ),
            consumers
        )
        consumers.filterNot { it == "repository/PlanRepository.kt" }.forEach { path ->
            assertTrue("$path must receive the application cutover gate", source(path).contains("accessGate"))
        }
        assertTrue(source("repository/PlanRepository.kt").contains("CutoverAccessGate"))
    }

    @Test
    fun productionFactoriesCannotConstructAnUngatedDatabaseRepositoryTrustOrPreferenceStore() {
        val sources = kotlinSources().associate { relative(it.first) to it.second }
        assertEquals(
            listOf("CellRebelAutoApp.kt"),
            callersOf(sources, "AppDatabase.getInstance(")
        )
        assertEquals(
            listOf("CellRebelAutoApp.kt"),
            callersOf(sources, "PlanConfigStore(").filterNot { it == "data/PlanConfigStore.kt" }
        )
        assertEquals(
            listOf(
                "automation/AutomationService.kt",
                // Rebase note: T3's remote-control receiver builds a gated
                // PlanRepository for STATUS/RESET_PLAN (declared direct Room
                // consumer above).
                "remote/RemoteControlReceiver.kt",
                "ui/MainViewModel.kt"
            ),
            callersOf(sources, "PlanRepository(").filterNot { it == "repository/PlanRepository.kt" }
        )
        callersOf(sources, "ProviderTrustStore(")
            .filterNot { it == "environment/ProviderTrustStore.kt" }
            .forEach { path -> assertTrue("$path must pass accessGate", sources.getValue(path).contains("accessGate")) }
        callersOf(sources, "RoomDurableRecoveryLog(")
            .filterNot { it == "recovery/RoomDurableRecoveryLog.kt" }
            .forEach { path -> assertTrue("$path must pass accessGate", sources.getValue(path).contains("accessGate")) }
    }

    @Test
    fun automationRunOwnsOneNormalLeaseAndCutoverQuiescenceJoinsEveryServiceOwner() {
        val service = source("automation/AutomationService.kt")
        assertEquals(2, Regex("accessGate\\.withNormalAccess").findAll(service).count())
        assertTrue(service.contains("quiesceForCutover"))
        assertTrue(service.contains("automationJob?.cancelAndJoin()"))
        assertTrue(service.contains("supersessionStopJob?.cancelAndJoin()"))
    }

    private fun callersOf(sources: Map<String, String>, token: String): List<String> =
        sources.filterValues { it.contains(token) }.keys.sorted()

    private fun kotlinSources(): List<Pair<File, String>> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it to withoutComments(it.readText()) }
        .toList()

    private fun source(path: String): String = withoutComments(File(sourceRoot, path).readText())

    private fun relative(file: File): String = file.relativeTo(sourceRoot).invariantSeparatorsPath

    private fun withoutComments(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }
}

package com.example.cellrebelauto.cutover

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.repository.PlanRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room races proving the production executor and observable-reader gate. */
@RunWith(RobolectricTestRunner::class)
class CutoverRoomAccessRaceTest {
    private lateinit var gate: CutoverAccessGate
    private lateinit var db: AppDatabase
    private lateinit var queryExecutor: ExecutorService
    private lateinit var transactionExecutor: ExecutorService

    private val identity = CutoverRestoreIdentity(
        archiveDigest = "sha256:${"b".repeat(64)}",
        captureId = "room-race"
    )

    @Before
    fun setUp() {
        gate = CutoverAccessGate.open()
        queryExecutor = Executors.newSingleThreadExecutor()
        transactionExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .setQueryExecutor(gate.guardExecutor(queryExecutor))
            .setTransactionExecutor(gate.guardExecutor(transactionExecutor))
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        queryExecutor.shutdownNow()
        transactionExecutor.shutdownNow()
    }

    @Test
    fun observableReaderEmitsNoExclusiveGenerationAndReopensWithFreshRoomState() = runBlocking {
        val repository = PlanRepository(db, gate)
        val emissions = Channel<LocationPlan>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            repository.observeLatestPlan().filterNotNull().collect { emissions.send(it) }
        }
        db.planDao().insertPlan(plan("before.csv", importedAt = 1L))
        assertEquals("before.csv", withTimeout(2_000) { emissions.receive() }.sourceFileName)

        val lease = (gate.acquireExclusive(identity) as CutoverExclusiveAdmission.Granted).lease
        lease.withExclusiveAccess {
            db.planDao().insertPlan(plan("restored.csv", importedAt = 2L))
        }

        val whileExclusive = mutableListOf<LocationPlan>()
        withTimeoutOrNull(200) {
            while (true) whileExclusive += emissions.receive()
        }
        assertTrue(
            "the exclusive generation must not escape before reopen: $whileExclusive",
            whileExclusive.none { it.sourceFileName == "restored.csv" }
        )
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
        assertEquals("restored.csv", withTimeout(2_000) { emissions.receive() }.sourceFileName)
        collector.cancel()
    }

    @Test
    fun ordinaryRoomWriterIsDeferredDuringExclusiveAndLandsAfterReopen() = runBlocking {
        // Force Room open while normal admission is available.
        assertNull(db.planDao().getLatestPlan())
        val lease = (gate.acquireExclusive(identity) as CutoverExclusiveAdmission.Granted).lease

        // #164: the executor path requeues instead of throwing into framework internals. The
        // suspend writer therefore parks on its Room continuation until the cutover reopens.
        val writer = async(Dispatchers.Default) {
            db.planDao().insertPlan(plan("deferred.csv", importedAt = 3L))
        }
        withTimeoutOrNull(500) { writer.join() }
        assertTrue(
            "the writer must not complete (and the insert must not land) while the gate is exclusive",
            writer.isActive
        )
        lease.withExclusiveAccess { assertNull(db.planDao().getLatestPlan()) }

        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
        withTimeout(5_000) { writer.join() }
        assertEquals("deferred.csv", db.planDao().getLatestPlan()?.sourceFileName)
    }

    @Test
    fun admittedRoomWriterCanFinishAfterDrainClosesAndExclusiveWaitsForItsCommit() = runBlocking {
        // Force Room open before the race so this test isolates executor admission, not DB startup.
        assertNull(db.planDao().getLatestPlan())
        val admitted = CompletableDeferred<Unit>()
        val finishDuringDrain = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.Default) {
            gate.withNormalAccess {
                admitted.complete(Unit)
                finishDuringDrain.await()
                db.planDao().insertPlan(plan("admitted.csv", importedAt = 4L))
            }
        }
        admitted.await()

        val exclusive = async(Dispatchers.Default) { gate.acquireExclusive(identity) }
        withTimeout(2_000) {
            while (gate.snapshot().phase != CutoverGatePhase.DRAINING) yield()
        }
        finishDuringDrain.complete(Unit)

        assertTrue(writer.await() is CutoverAccessResult.Granted)
        val lease = (exclusive.await() as CutoverExclusiveAdmission.Granted).lease
        lease.withExclusiveAccess {
            assertEquals("admitted.csv", db.planDao().getLatestPlan()?.sourceFileName)
        }
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
    }

    private fun plan(name: String, importedAt: Long) = LocationPlan(
        sourceFileName = name,
        importedAt = importedAt,
        globalBufferSeconds = 0,
        totalRows = 0,
        totalRequiredSuccesses = 0
    )
}

package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Immutable durable-carrier replay contract: the same owner payload is idempotent, while a replay
 * that changes immutable evidence fails closed and preserves the first committed row.
 */
@RunWith(RobolectricTestRunner::class)
class DurableEvidenceReplayTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun observation(lat: Double = 50.4501) = ObservationSnapshot(
        leaseId = "lease-77",
        acceptedIntentHash = "intent-77",
        coverage = "FULL",
        verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
        deliveryMode = "SYSTEM_MOCK",
        isMock = true,
        scheduleDecision = "ALLOWED_NOW",
        effectiveLat = lat,
        effectiveLng = 30.5234,
        environmentRevision = 7L,
        environmentFingerprint = "fp-77",
        observedAtElapsedRealtimeMs = 1_000L,
        observedAtEpochMs = 2_000L,
        continuitySinceElapsedRealtimeMs = 500L,
        evidenceRefs = listOf("qwy:store:77")
    )

    @Test
    fun `identical observation replay is a no-op but an immutable mismatch fails closed`() = runTest {
        repo.persistObservation(77L, "PRE", observation())
        repo.persistObservation(77L, "PRE", observation())

        assertEquals(1, db.durableObservationDao().countForAttempt(77L))
        val mismatch = runCatching {
            repo.persistObservation(77L, "PRE", observation(lat = 51.0))
        }.exceptionOrNull()
        assertTrue("a conflicting replay must fail closed", mismatch is IllegalStateException)
        assertEquals("the first immutable carrier wins", 50.4501, repo.getObservation(77L, "PRE")!!.effectiveLat!!, 0.0)
    }

    @Test
    fun `identical completion receipt replay is a no-op but an immutable mismatch fails closed`() = runTest {
        repo.persistCompletionReceipt(77L, 1, "intent-77", "lease-77")
        repo.persistCompletionReceipt(77L, 1, "intent-77", "lease-77")

        val mismatch = runCatching {
            repo.persistCompletionReceipt(77L, 1, "intent-other", "lease-77")
        }.exceptionOrNull()
        assertTrue("a conflicting completion replay must fail closed", mismatch is IllegalStateException)
        val durable = repo.getCompletionReceipt(77L)!!
        assertEquals("intent-77", durable.acceptedIntentHash)
        assertEquals("lease-77", durable.leaseId)
    }
}

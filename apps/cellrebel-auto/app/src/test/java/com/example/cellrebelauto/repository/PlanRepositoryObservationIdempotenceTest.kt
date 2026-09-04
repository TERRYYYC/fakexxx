package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Device-observed 2026-09-03 (ZY22JHW9M4, Auto d55807d, first real A+ lease): the live path
 * persists the PRE observation TWICE for one attempt — APlusComposition.observeLive() inserts the
 * durable record "before returning", then AutomationEngine calls persistObservation(attemptId,
 * "PRE") for the same snapshot. durable_observation_records is UNIQUE(attemptId, phase) and the
 * DAO is a bare @Insert, so the second write threw SQLiteConstraintException inside the engine's
 * Room transaction and the run paused with an unresolved lease: every fresh attempt died at
 * PRE_OBSERVED. PR #65 restructures the carrier into one transaction; until it lands the
 * repository persist must be idempotent per (attemptId, phase) — first durable carrier wins.
 */
@RunWith(RobolectricTestRunner::class)
class PlanRepositoryObservationIdempotenceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = PlanRepository(db)
    }

    @After
    fun tearDown() { db.close() }

    private fun snapshot(lease: String = "lease-1") = ObservationSnapshot(
        leaseId = lease, acceptedIntentHash = "hash-1", coverage = "FULL",
        verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED", deliveryMode = "SYSTEM_MOCK", isMock = true,
        scheduleDecision = "ALLOWED_NOW", effectiveLat = 50.45, effectiveLng = 30.52,
        environmentRevision = 7L, environmentFingerprint = "fp", observedAtElapsedRealtimeMs = 1000L,
        observedAtEpochMs = 1_700_000_000_000L, continuitySinceElapsedRealtimeMs = 900L, evidenceRefs = listOf("ref-1"),
    )

    @Test
    fun persistObservation_isIdempotentPerAttemptAndPhase() = runTest {
        repo.persistObservation(1L, "PRE", snapshot())
        // The device failure shape: the live path ALWAYS persists the same (attemptId, phase)
        // a second time. It must neither throw nor create a second carrier.
        repo.persistObservation(1L, "PRE", snapshot())
        assertEquals(1, db.durableObservationDao().countForAttempt(1L))
        assertEquals("lease-1", db.durableObservationDao().forAttemptPhase(1L, "PRE")!!.leaseId)
    }

    @Test
    fun persistObservation_keepsPhasesAndAttemptsDistinct() = runTest {
        repo.persistObservation(1L, "PRE", snapshot())
        repo.persistObservation(1L, "POST", snapshot())
        repo.persistObservation(2L, "PRE", snapshot("lease-2"))
        assertEquals(2, db.durableObservationDao().countForAttempt(1L))
        assertEquals(1, db.durableObservationDao().countForAttempt(2L))
    }
}

package com.example.cellrebelauto.automation

import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupersessionStopCoordinatorTest {
    @Test
    fun `engine job retirement precedes proof and already-safe state never invokes convergence`() = runTest {
        val calls = mutableListOf<String>()
        val proof = PlanRepository.SupersessionStopProof("r1", 1L, 2L, 3L, "digest")
        val result = SupersessionStopCoordinator(
            cancelAndJoinRun = { calls += "join" },
            verifyDurableStop = {
                calls += "verify"
                PlanRepository.SupersessionStopVerification.Verified(proof)
            },
            convergeExistingOwner = {
                calls += "converge"
                true
            }
        ).execute()

        assertEquals(listOf("join", "verify"), calls)
        assertEquals(proof, (result as PlanRepository.SupersessionStopVerification.Verified).proof)
    }

    @Test
    fun `durable owner convergence is stop-only and followed by a fresh proof read`() = runTest {
        val calls = mutableListOf<String>()
        var verificationCount = 0
        val proof = PlanRepository.SupersessionStopProof("r2", 1L, 2L, 3L, "digest")
        val result = SupersessionStopCoordinator(
            cancelAndJoinRun = { calls += "join" },
            verifyDurableStop = {
                calls += "verify"
                verificationCount += 1
                if (verificationCount == 1) {
                    PlanRepository.SupersessionStopVerification.NeedsConvergence("owner")
                } else {
                    PlanRepository.SupersessionStopVerification.Verified(proof)
                }
            },
            convergeExistingOwner = {
                calls += "converge"
                true
            }
        ).execute()

        assertEquals(listOf("join", "verify", "converge", "verify"), calls)
        assertEquals(proof, (result as PlanRepository.SupersessionStopVerification.Verified).proof)
    }

    @Test
    fun `failed convergence cannot manufacture a verified result`() = runTest {
        var verifyCalls = 0
        val result = SupersessionStopCoordinator(
            cancelAndJoinRun = {},
            verifyDurableStop = {
                verifyCalls += 1
                PlanRepository.SupersessionStopVerification.NeedsConvergence("owner")
            },
            convergeExistingOwner = { false }
        ).execute()

        assertTrue(result is PlanRepository.SupersessionStopVerification.Blocked)
        assertEquals(1, verifyCalls)
    }
}

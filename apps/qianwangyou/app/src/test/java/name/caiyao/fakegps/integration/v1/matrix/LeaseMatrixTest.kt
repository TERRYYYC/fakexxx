package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.CleanupOutcome
import name.caiyao.fakegps.integration.v1.LeaseState
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_UID
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * §10 lease rows owned by the qwy provider lane (§8.4 state machine; INV-14/16/21/28).
 *
 * Frozen stance under test everywhere here: any non-RELEASED lease blocks a new
 * apply; false-red (stop and wait for a human) is always chosen over
 * false-green (trusted quota on a dirty environment).
 */
class LeaseMatrixTest {

    private fun pairedHarness(): ProviderHarness =
        ProviderHarness.create().also { it.pair(AUTO_PKG, AUTO_SIGNER) }

    /** M-LS-01: ACTIVE lease + another caller's apply → typed LEASE_CONFLICT. */
    @Test
    fun M_LS_01() {
        val h = pairedHarness()
        h.pair(OTHER_PKG, OTHER_SIGNER)
        h.apply(uid = AUTO_UID, key = "ls01-a")

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) {
            h.apply(uid = OTHER_UID, key = "ls01-b")
        }
        assertEquals("second environment apply never happened", 1, h.env.applyCount)
    }

    /** M-LS-02: RELEASE_INCOMPLETE keeps blocking — "already released once" is not a pass. */
    @Test
    fun M_LS_02() {
        val h = pairedHarness()
        h.env.cleanupOutcome = CleanupOutcome.Incomplete(listOf(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire))
        val receipt = h.apply(key = "ls02-a")
        val release = h.release(receipt.leaseId, key = "ls02-rel")
        assertEquals("release honestly reports incompleteness", false, release.releaseComplete)
        assertEquals(
            LeaseState.RELEASE_INCOMPLETE,
            h.leases.effectiveState(receipt.leaseId, h.tracker.generation),
        )

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) { h.apply(key = "ls02-b") }
    }

    /** M-LS-03: deadline passed → EXPIRED, and EXPIRED still blocks (TTL is not an INV-21 bypass). */
    @Test
    fun M_LS_03() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls03-a", intent = h.intent(deadlineInMs = 60_000L))

        h.clock.advance(61_000L)
        assertEquals(LeaseState.EXPIRED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) { h.apply(key = "ls03-b") }
    }

    /**
     * M-LS-04: qwy revokes the caller → lease REVOKED, blocks applies, former
     * caller fully locked out; convergence is PROVIDER-driven internal cleanup
     * (REVOKED → RELEASING → RELEASED), never a post-revoke caller capability.
     */
    @Test
    fun M_LS_04() {
        val h = pairedHarness()
        h.pair(OTHER_PKG, OTHER_SIGNER)
        val receipt = h.apply(key = "ls04-a")

        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        assertEquals(LeaseState.REVOKED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        // Former caller: every call typed-fails at authorization.
        expectContractFailure(ContractErrorCodeV1.CALLER_NOT_ALLOWED) {
            h.handler.observe(AUTO_UID, ObserveRequestV1(receipt.leaseId, "op-ls04", receipt.acceptedIntentHash))
        }

        // Still blocks a different paired caller.
        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) { h.apply(uid = OTHER_UID, key = "ls04-b") }

        // Provider self-cleanup converges and unblocks.
        h.handler.runRevokedLeaseCleanup()
        assertEquals(LeaseState.RELEASED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
        val next = h.apply(uid = OTHER_UID, key = "ls04-c")
        assertTrue(next.leaseId.isNotEmpty())
    }

    @Test
    fun providerDrivenCleanupPublishesTerminalRevisionAndAudit() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls04-cleanup-evidence-apply")
        val fullBeforeCleanup = h.handler.discover(AUTO_UID)
        assertEquals(ContinuityCoverageV1.FULL.wire, fullBeforeCleanup.continuityCoverageWire)
        assertNotNull(h.tracker.snapshot().continuitySinceElapsedRealtimeMs)
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        val revisionBefore = h.tracker.snapshot().revision
        val auditCountBefore = h.audit.all().size

        h.handler.runRevokedLeaseCleanup()

        assertEquals(LeaseState.RELEASED, h.leases.get(receipt.leaseId)?.state)
        assertEquals(revisionBefore + 1L, h.tracker.snapshot().revision)
        assertEquals(auditCountBefore + 1, h.audit.all().size)
        val cleanupAudit = h.audit.all().last()
        assertEquals("provider_revoked_cleanup", cleanupAudit.event)
        assertEquals(AUTO_PKG, cleanupAudit.callerApplicationId)
        assertEquals(receipt.leaseId, cleanupAudit.leaseId)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.snapshot().continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun providerCleanupMutationFailureRevokesFullBeforeFinalize() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls04-cleanup-mutation-failure-apply")
        val fullBeforeCleanup = h.handler.discover(AUTO_UID)
        assertEquals(ContinuityCoverageV1.FULL.wire, fullBeforeCleanup.continuityCoverageWire)
        assertNotNull(h.tracker.snapshot().continuitySinceElapsedRealtimeMs)
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        h.env.afterCleanupEnvironmentMutation = {
            throw IllegalStateException("cleanup mutated then failed")
        }

        assertThrows(IllegalStateException::class.java) {
            h.handler.runRevokedLeaseCleanup()
        }

        assertEquals(LeaseState.RELEASING, h.leases.get(receipt.leaseId)?.state)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.snapshot().continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun providerCleanupTerminalRevisionAndAuditRollbackTogether() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls04-cleanup-atomic-apply")
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        val revisionBefore = h.tracker.snapshot().revision
        h.kv.failOnWrite = { namespace, _ -> namespace == "integration.v1.audit" }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.runRevokedLeaseCleanup()
        }
        h.kv.failOnWrite = null

        assertEquals(
            "terminal lease cannot commit without its revision and audit evidence",
            LeaseState.RELEASING,
            h.leases.get(receipt.leaseId)?.state,
        )
        assertEquals(revisionBefore, h.tracker.snapshot().revision)

        h.handler.runRevokedLeaseCleanup()
        assertEquals(LeaseState.RELEASED, h.leases.get(receipt.leaseId)?.state)
        assertEquals(revisionBefore + 1L, h.tracker.snapshot().revision)
        assertEquals("provider_revoked_cleanup", h.audit.all().last().event)
    }

    @Test
    fun callerRevocationRollsBackPairingWhenLeaseTransitionCannotCommit() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls04-atomic-revoke-apply")
        val auditBefore = h.audit.all()
        h.kv.failOnWrite = { namespace, key ->
            namespace == "integration.v1.leases" && key.startsWith("lease:")
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        }
        h.kv.failOnWrite = null

        assertNotNull(
            "a torn revoke must not strand an ACTIVE lease behind an already-revoked principal",
            h.pairing.findActive(AUTO_PKG, AUTO_SIGNER),
        )
        assertEquals(
            LeaseState.ACTIVE,
            h.leases.effectiveState(receipt.leaseId, h.tracker.generation),
        )
        assertEquals("failed revoke must not append an audit success", auditBefore, h.audit.all())

        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)

        assertNull(h.pairing.findActive(AUTO_PKG, AUTO_SIGNER))
        assertEquals(
            LeaseState.REVOKED,
            h.leases.effectiveState(receipt.leaseId, h.tracker.generation),
        )
        assertEquals("caller_revoked", h.audit.all().last().event)
    }

    @Test
    fun revokingOneSignerDoesNotRevokeAnotherSignersLeaseForTheSamePackage() {
        val h = ProviderHarness.create()
        val rotatedSigner = "signer-auto-2"
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.pair(AUTO_PKG, rotatedSigner)
        h.resolver.register(AUTO_UID, AUTO_PKG, rotatedSigner)
        val receipt = h.apply(key = "ls04-principal-scope-apply")

        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)

        assertNull(h.pairing.findActive(AUTO_PKG, AUTO_SIGNER))
        assertNotNull(h.pairing.findActive(AUTO_PKG, rotatedSigner))
        assertEquals(
            "lease ownership is the full (applicationId, signerDigest) principal",
            LeaseState.ACTIVE,
            h.leases.effectiveState(receipt.leaseId, h.tracker.generation),
        )
    }

    @Test
    fun revokedProviderCleanupCrashIsFinishedWithoutCallingTheRevokedPrincipal() {
        val h = pairedHarness()
        h.pair(OTHER_PKG, OTHER_SIGNER)
        val receipt = h.apply(key = "ls04-provider-crash-apply")
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        h.env.beforeCleanup = {
            throw IllegalStateException("process died after provider RELEASING commit")
        }

        try {
            h.handler.runRevokedLeaseCleanup()
            throw AssertionError("injected provider cleanup crash must surface")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "process died after provider RELEASING commit",
                expected.message,
            )
        }
        assertEquals(LeaseState.RELEASING, h.leases.get(receipt.leaseId)?.state)

        h.env.beforeCleanup = null
        val auditCountBeforeRestart = h.audit.all().size
        h.restart(cleanlinessProvable = false)

        assertEquals(
            "provider-owned cleanup has no authorized caller receipt to wait for",
            LeaseState.RELEASED,
            h.leases.get(receipt.leaseId)?.state,
        )
        assertEquals(auditCountBeforeRestart + 1, h.audit.all().size)
        assertEquals("provider_revoked_cleanup", h.audit.all().last().event)
        assertEquals(receipt.leaseId, h.audit.all().last().leaseId)
        val next = h.apply(uid = OTHER_UID, key = "ls04-provider-crash-next")
        assertNotEquals(receipt.leaseId, next.leaseId)
    }

    @Test
    fun startupProviderCleanupTerminalAndAuditRollbackTogether() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls04-startup-cleanup-atomic-apply")
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        h.env.beforeCleanup = {
            throw IllegalStateException("process died after provider phase one")
        }
        assertThrows(IllegalStateException::class.java) {
            h.handler.runRevokedLeaseCleanup()
        }
        h.env.beforeCleanup = null
        assertEquals(LeaseState.RELEASING, h.leases.get(receipt.leaseId)?.state)
        val auditCountBeforeRestart = h.audit.all().size
        h.kv.failOnWrite = { namespace, _ -> namespace == "integration.v1.audit" }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.restart(cleanlinessProvable = false)
        }
        h.kv.failOnWrite = null

        assertEquals(
            "startup must not publish terminal lease without cleanup audit",
            LeaseState.RELEASING,
            h.leases.get(receipt.leaseId)?.state,
        )
        assertEquals(auditCountBeforeRestart, h.audit.all().size)

        h.restart(cleanlinessProvable = false)
        assertEquals(LeaseState.RELEASED, h.leases.get(receipt.leaseId)?.state)
        assertEquals(auditCountBeforeRestart + 1, h.audit.all().size)
        assertEquals("provider_revoked_cleanup", h.audit.all().last().event)
    }

    /** M-LS-05: same caller + same idempotencyKey replay → original receipt, no conflict, one real apply. */
    @Test
    fun M_LS_05() {
        val h = pairedHarness()
        val first = h.apply(key = "ls05-key")
        val replay = h.apply(key = "ls05-key")

        assertEquals(first.leaseId, replay.leaseId)
        assertEquals(first.acceptedIntentHash, replay.acceptedIntentHash)
        assertEquals(first.environmentRevision, replay.environmentRevision)
        assertEquals("idempotent replay does not re-apply the environment", 1, h.env.applyCount)
    }

    /** M-LS-06: same caller, DIFFERENT key / different intent while active → LEASE_CONFLICT. */
    @Test
    fun M_LS_06() {
        val h = pairedHarness()
        h.apply(key = "ls06-a")

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) {
            h.apply(key = "ls06-b", intent = h.intent(attemptId = "att-2"))
        }
        assertEquals(1, h.env.applyCount)
    }

    /**
     * M-LS-07: ACQUIRING/ACTIVE + owner restart + cleanliness NOT provable →
     * rebuilt as RELEASE_INCOMPLETE, revision bumped, coverage degraded.
     */
    @Test
    fun M_LS_07() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls07-a")
        val revisionBefore = h.tracker.snapshot().revision

        h.restart(cleanlinessProvable = false)

        assertEquals(
            LeaseState.RELEASE_INCOMPLETE,
            h.leases.effectiveState(receipt.leaseId, h.tracker.generation),
        )
        val snap = h.tracker.snapshot()
        assertTrue("revision bumped across unprovable restart", snap.revision > revisionBefore)
        assertNotEquals("coverage degraded", ContinuityCoverageV1.FULL.wire, snap.coverageWire)
    }

    /** M-LS-09: the REVOKED caller tries to release its old lease → CALLER_NOT_ALLOWED, no carve-out. */
    @Test
    fun M_LS_09() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls09-a")
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)

        expectContractFailure(ContractErrorCodeV1.CALLER_NOT_ALLOWED) {
            h.release(receipt.leaseId, key = "ls09-rel")
        }
        assertEquals(LeaseState.REVOKED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
    }

    /** M-LS-10: wall clock jumps hours in both directions — expiry timing must not move. */
    @Test
    fun M_LS_10() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls10-a", intent = h.intent(deadlineInMs = 300_000L))

        h.clock.jumpWallClock(+4 * 3_600_000L)
        assertEquals(LeaseState.ACTIVE, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        h.clock.jumpWallClock(-8 * 3_600_000L)
        assertEquals(LeaseState.ACTIVE, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        h.clock.advance(301_000L)
        assertEquals(LeaseState.EXPIRED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
    }

    /** M-LS-11: deadlineEpochMs already in the past → immediately expired via max(0, …), never a wrap-around mega-lease. */
    @Test
    fun M_LS_11() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls11-a", intent = h.intent(deadlineInMs = -5_000L))

        assertEquals(LeaseState.EXPIRED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) { h.apply(key = "ls11-b") }
    }

    /**
     * M-LS-12: ACTIVE + owner restart + cleanliness provable + generation
     * mismatch → EXPIRED; the owning caller can still release to converge.
     */
    @Test
    fun M_LS_12() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls12-a")

        h.restart(cleanlinessProvable = true)

        assertEquals(LeaseState.EXPIRED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        val release = h.release(receipt.leaseId, key = "ls12-rel")
        assertEquals(true, release.releaseComplete)
        assertEquals(LeaseState.RELEASED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
        assertTrue(h.apply(key = "ls12-b").leaseId.isNotEmpty())
    }

    /**
     * M-LS-13: device reboot resets the elapsed epoch. A naive raw comparison
     * against the OLD absolute deadline would call the lease ACTIVE forever —
     * generation mismatch must force EXPIRED instead.
     */
    @Test
    fun M_LS_13() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls13-a", intent = h.intent(deadlineInMs = 600_000L))

        h.restart(cleanlinessProvable = true, reboot = true)

        assertEquals(
            "raw elapsed comparison across reboot must not keep the lease alive",
            LeaseState.EXPIRED,
            h.leases.effectiveState(receipt.leaseId, h.tracker.generation),
        )
    }

    /** M-LS-14: plain process restart (clock comparable) still expires — the documented false-red policy. */
    @Test
    fun M_LS_14() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls14-a")

        h.restart(cleanlinessProvable = true, reboot = false)

        assertEquals(LeaseState.EXPIRED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
    }

    /**
     * M-LS-15: REVOKED survives restarts verbatim under BOTH cleanliness
     * verdicts — rewriting it to EXPIRED would orphan the provider-cleanup exit
     * (the former caller cannot release).
     */
    @Test
    fun M_LS_15() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls15-a")
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)

        h.restart(cleanlinessProvable = true)
        assertEquals(LeaseState.REVOKED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        h.restart(cleanlinessProvable = false)
        assertEquals(LeaseState.REVOKED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        // Exit still reachable after the restarts.
        h.handler.runRevokedLeaseCleanup()
        assertEquals(LeaseState.RELEASED, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
    }

    /** M-LS-16: RELEASE_INCOMPLETE survives restarts verbatim (both verdicts) — the human-recovery flag must not be lost. */
    @Test
    fun M_LS_16() {
        val h = pairedHarness()
        h.env.cleanupOutcome = CleanupOutcome.Incomplete(listOf(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire))
        val receipt = h.apply(key = "ls16-a")
        h.release(receipt.leaseId, key = "ls16-rel")
        assertEquals(LeaseState.RELEASE_INCOMPLETE, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        h.restart(cleanlinessProvable = true)
        assertEquals(LeaseState.RELEASE_INCOMPLETE, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))

        h.restart(cleanlinessProvable = false)
        assertEquals(LeaseState.RELEASE_INCOMPLETE, h.leases.effectiveState(receipt.leaseId, h.tracker.generation))
    }

    /** M-LS-17: RELEASING + owner restart → release replayed idempotently; unprovable cleanup → RELEASE_INCOMPLETE. */
    @Test
    fun M_LS_17() {
        val h = pairedHarness()
        val receipt = h.apply(key = "ls17-a")

        // Persisted mid-release state (crash after the durable RELEASING write).
        val record = h.leases.get(receipt.leaseId)!!
        h.leases.put(record.copy(state = LeaseState.RELEASING, releaseIdempotencyKey = "ls17-rel"))

        h.env.cleanupOutcome = CleanupOutcome.Incomplete(listOf(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire))
        val cleanupsBefore = h.env.cleanupCount
        h.restart(cleanlinessProvable = true)

        assertTrue("release was re-driven on recovery", h.env.cleanupCount > cleanupsBefore)
        assertEquals(
            LeaseState.RELEASE_INCOMPLETE,
            h.leases.effectiveState(receipt.leaseId, h.tracker.generation),
        )
    }
}

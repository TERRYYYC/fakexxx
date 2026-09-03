package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_UID
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SUPPLEMENTARY RED LANE — provider-side apply/release crash convergence and
 * idempotency-store scope (§6.3.4, §7.2 OperationReceipt, §8.4; INV-13/INV-28).
 *
 * NOT ledger rows and NOT counted as ledger coverage (same discipline as
 * AdvanceProviderRedTest: Opus5 allocates IDs in one batch — no self-assigned
 * IDs, and deleting this file would still leave the verifier green).
 *
 * Provenance: dsf advisory on af06993 — F-6 asked for write-fault crash cases
 * at ALL THREE mutation points (apply / release / advance); the advance point
 * landed with the F-2 closure (AdvanceProviderRedTest.advance_crashBetweenWrites),
 * these are the remaining two. F-9 asked for cross-caller idempotency-key
 * isolation. Both are contract-decoupled (they pin §7.2 store semantics and
 * §10.1 fault-injection charter already frozen at this branch's base), so they
 * are consumed BEFORE the #3 interface freeze; the contract-coupled findings
 * (F-4 ledger binding, F-7 §6.7.4b order flip, digest-mismatch case) stay
 * parked for the post-freeze rebase.
 *
 * Grounding for the convergence predicates:
 *  - §7.2 OperationReceipt: "无中间态" — same key + same digest replays the
 *    ORIGINAL receipt; the receipt row's whole reason to exist is that a crash
 *    retry is indistinguishable from a first attempt to the caller (INV-13).
 *  - §10 M-CR-01/02 expected terminals (Auto-side rows): "同键 apply，最多一个
 *    lease" / "同键返回原 receipt" — the provider-side halves below pin the
 *    same device truths from the owner's side.
 *  - INV-28: ANY non-RELEASED lease blocks a new apply. Apply admission is a
 *    deliberate ACQUIRING commit before the external mutation; success then
 *    atomically commits ACTIVE + receipt. A failure after admission preserves
 *    that exact owner as RELEASE_INCOMPLETE for explicit release recovery — it
 *    never rolls an already-mutated external environment back to "no lease".
 */
class ApplyReleaseProviderRedTest {

    private fun harness(): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        return h
    }

    // ------------------------------------------------------- crash: apply

    /**
     * FC-1: the external environment mutation may begin only after an
     * ACQUIRING owner is visible from committed backing, not merely through
     * the writer transaction's read-your-writes buffer.
     */
    @Test
    fun apply_externalMutationBeginsOnlyAfterAcquiringOwnerIsDurable() {
        val h = harness()
        var durableOwnerAtExternalBoundary: LeaseRecord? = null
        h.env.beforeApplyEnvironment = {
            durableOwnerAtExternalBoundary = EnvironmentLeaseStore(
                h.kv.reopenCommitted(),
                h.clock,
            ).blockingLease()
        }

        val receipt = h.apply(key = "apl-owner-before-external")

        assertNotNull(
            "external mutation must never run while the ACQUIRING owner exists only in txBuffer",
            durableOwnerAtExternalBoundary,
        )
        assertEquals(receipt.leaseId, durableOwnerAtExternalBoundary?.leaseId)
        assertEquals(LeaseState.ACQUIRING, durableOwnerAtExternalBoundary?.state)
        assertEquals(AUTO_PKG, durableOwnerAtExternalBoundary?.callerApplicationId)
        assertEquals(AUTO_SIGNER, durableOwnerAtExternalBoundary?.callerSignerDigest)
        assertEquals("apl-owner-before-external", durableOwnerAtExternalBoundary?.applyIdempotencyKey)
    }

    /**
     * FC-1 second window: an external apply can succeed and the finalize write
     * can still fail. The failure must leave one durable, caller-owned lease
     * that blocks a duplicate apply and can be released to recover the device.
     */
    @Test
    fun apply_externalSuccessThenFinalizeFailure_publicReplayConvergesSameDurableOwner() {
        val h = harness()
        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }

        try {
            h.apply(key = "apl-finalize-fault")
            fail("injected finalize fault must surface")
        } catch (expected: RuntimeException) {
            // Persistence fault, not a typed business answer.
        }
        h.kv.failOnWrite = null

        assertEquals("the external mutation completed before finalize failed", 1, h.env.applyCount)
        val durableOwner = h.leases.blockingLease()
        assertNotNull("a successful external mutation must never be ownerless", durableOwner)
        assertEquals(AUTO_PKG, durableOwner?.callerApplicationId)
        assertEquals(AUTO_SIGNER, durableOwner?.callerSignerDigest)
        assertEquals("apl-finalize-fault", durableOwner?.applyIdempotencyKey)
        assertEquals(
            "the uncertain external result must be caller-recoverable without trusting it",
            LeaseState.RELEASE_INCOMPLETE,
            durableOwner?.state,
        )

        // Recovery must use only the public request. The caller never received
        // the server-generated leaseId from the failed first call.
        val replay = h.apply(key = "apl-finalize-fault")
        val replayAgain = h.apply(key = "apl-finalize-fault")
        assertEquals(replay, replayAgain)
        assertEquals(durableOwner?.leaseId, replay.leaseId)
        assertEquals(LeaseState.ACTIVE, h.leases.get(replay.leaseId)?.state)
        expectContractFailure(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT) {
            h.apply(
                key = "apl-finalize-fault",
                intent = h.intent(attemptId = "different-pending-payload"),
            )
        }
        assertEquals("recovery may re-drive the idempotent environment but never create lease 2", 2, h.env.applyCount)
        assertEquals(replay.leaseId, h.leases.blockingLease()?.leaseId)

        h.release(replay.leaseId, key = "apl-finalize-recover")
        val next = h.apply(key = "apl-after-recovery", intent = h.intent(attemptId = "att-2"))
        assertNotEquals(replay.leaseId, next.leaseId)
    }

    @Test
    fun apply_partialExternalFailure_persistsTypedReasonAndPublicReplayRecovers() {
        val h = harness()
        h.env.afterApplyEnvironmentMutation = {
            throw IllegalStateException("injected failure after external mutation")
        }

        try {
            h.apply(key = "apl-external-fault")
            fail("injected external fault must surface")
        } catch (expected: IllegalStateException) {
            assertEquals("injected failure after external mutation", expected.message)
        }

        val durableOwner = h.leases.blockingLease()
        assertNotNull("external failure must retain its admitted durable owner", durableOwner)
        assertEquals(LeaseState.RELEASE_INCOMPLETE, durableOwner?.state)
        assertEquals("apl-external-fault", durableOwner?.applyIdempotencyKey)
        assertEquals(
            "the uncertain external result needs a durable typed recovery reason",
            listOf(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire),
            durableOwner?.residualReasonWires,
        )

        h.env.afterApplyEnvironmentMutation = null
        val replay = h.apply(key = "apl-external-fault")
        assertEquals(durableOwner?.leaseId, replay.leaseId)
        assertEquals(2, h.env.applyCount)
    }

    @Test
    fun apply_incompleteMarkerWriteAlsoFails_samePublicReplayResumesAcquiringOwner() {
        val h = harness()
        h.env.afterApplyEnvironmentMutation = {
            // The external state has already changed. Also fail the best-effort
            // ACQUIRING -> RELEASE_INCOMPLETE recovery commit.
            h.kv.failOnWrite = { _, _ -> true }
            throw IllegalStateException("external and recovery persistence failed")
        }

        try {
            h.apply(key = "apl-double-fault")
            fail("double fault must surface")
        } catch (expected: IllegalStateException) {
            assertEquals("external and recovery persistence failed", expected.message)
            assertTrue("secondary durable failure is preserved", expected.suppressed.isNotEmpty())
        }
        h.kv.failOnWrite = null
        h.env.afterApplyEnvironmentMutation = null

        assertEquals(LeaseState.ACQUIRING, h.leases.blockingLease()?.state)
        val replay = h.apply(key = "apl-double-fault")
        assertEquals(h.leases.blockingLease()?.leaseId, replay.leaseId)
        assertEquals(2, h.env.applyCount)
    }

    @Test
    fun resumedPublicApplyClearsFormerProviderCleanupOwnership() {
        val h = harness()
        h.env.afterApplyEnvironmentMutation = {
            throw IllegalStateException("leave admitted apply without receipt")
        }
        try {
            h.apply(key = "apl-provider-handoff")
            fail("injected first apply failure must surface")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        h.env.afterApplyEnvironmentMutation = null

        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        h.env.cleanupOutcome = CleanupOutcome.Incomplete(
            listOf(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire),
        )
        h.handler.runRevokedLeaseCleanup()
        val providerIncomplete = h.leases.blockingLease()!!
        assertEquals(LeaseState.RELEASE_INCOMPLETE, providerIncomplete.state)
        assertNotNull(providerIncomplete.recoveryEvidenceRef)

        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.env.cleanupOutcome = CleanupOutcome.Complete
        val resumed = h.apply(key = "apl-provider-handoff")
        assertEquals(providerIncomplete.leaseId, resumed.leaseId)
        assertEquals(LeaseState.ACTIVE, h.leases.get(resumed.leaseId)?.state)

        // A later unclean restart makes the public APPLY owner uncertain. It
        // must stay caller-recoverable; stale revoke/provider provenance from
        // the earlier lifecycle must not silently reassign cleanup to qwy.
        h.restart(cleanlinessProvable = false)
        assertEquals(LeaseState.RELEASE_INCOMPLETE, h.leases.get(resumed.leaseId)?.state)
        h.handler.runRevokedLeaseCleanup()
        assertEquals(
            "a resumed public apply is no longer provider-owned cleanup",
            LeaseState.RELEASE_INCOMPLETE,
            h.leases.get(resumed.leaseId)?.state,
        )

        val released = h.release(resumed.leaseId, key = "rel-public-after-resumed-apply")
        assertTrue(released.releaseComplete)
    }

    @Test
    fun resumedApplyNeverRebridgesItsDeadlineFromChangedWallClock() {
        val h = harness()
        val intent = h.intent(deadlineInMs = 60_000L)
        h.env.afterApplyEnvironmentMutation = {
            throw IllegalStateException("leave admission resumable")
        }
        try {
            h.apply(key = "apl-deadline-once", intent = intent)
            fail("injected first apply failure must surface")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        h.env.afterApplyEnvironmentMutation = null

        // Elapsed time did not move, but the human/NTP moved wall time back a
        // day. Replaying the same admission must retain its first monotonic
        // deadline rather than minting another day of lease lifetime.
        h.clock.jumpWallClock(-24L * 60L * 60L * 1_000L)
        val resumed = h.apply(key = "apl-deadline-once", intent = intent)
        h.clock.advance(61_000L)

        assertEquals(
            LeaseState.EXPIRED,
            h.leases.effectiveState(resumed.leaseId, h.tracker.generation),
        )
    }

    @Test
    fun apply_failedAdmissionKeyRemainsPermanentlyBoundAfterOwnerIsReleased() {
        val h = harness()
        h.env.afterApplyEnvironmentMutation = {
            throw IllegalStateException("leave admitted key without receipt")
        }
        try {
            h.apply(key = "apl-permanent-binding")
            fail("injected failure must surface")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        h.env.afterApplyEnvironmentMutation = null

        // Simulate an explicit/operator recovery of the uncertain owner. This
        // setup may inspect provider state; the assertion below may not.
        val failedOwner = h.leases.blockingLease()!!
        h.release(failedOwner.leaseId, key = "rel-failed-admission")

        expectContractFailure(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT) {
            h.apply(
                key = "apl-permanent-binding",
                intent = h.intent(attemptId = "different-after-release"),
            )
        }
        assertEquals("a released owner does not erase the original key binding", 1, h.env.applyCount)
    }

    /**
     * dsf F-6 apply half, strengthened by FC-1: a receipt-write fault happens
     * after the external mutation. The already-committed admission must remain
     * as exactly one recoverable owner; restart and retry cannot manufacture a
     * second lease, and explicit release converges the uncertain environment.
     */
    @Test
    fun apply_crashBetweenWrites_convergesThroughDurableOwnerRecovery() {
        val h = harness()

        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }
        try {
            h.apply(key = "apl-crash")
            fail("injected write fault must surface, not be swallowed")
        } catch (expected: RuntimeException) {
            // SimulatedWriteCrash (or a transport wrapper) — NOT a typed
            // ContractException: a crash is not a business answer.
        }
        h.kv.failOnWrite = null

        h.restart(cleanlinessProvable = true)

        // The external mutation happened, so rollback-to-nothing is no longer
        // legal. Public same-key replay must recover without knowing leaseId.
        val owner = h.leases.blockingLease()
        assertNotNull("finalize failure must retain one durable owner", owner)
        assertEquals(LeaseState.RELEASE_INCOMPLETE, owner?.state)
        val replay = h.apply(key = "apl-crash")
        val replay2 = h.apply(key = "apl-crash")
        assertEquals(replay, replay2)
        assertEquals(owner?.leaseId, replay.leaseId)
        assertEquals("retry re-drives the same owner once", 2, h.env.applyCount)

        h.release(replay.leaseId, key = "apl-crash-rel")
        val next = h.apply(key = "apl-next", intent = h.intent(attemptId = "att-2"))
        assertNotEquals("fresh lease after convergence", replay.leaseId, next.leaseId)
    }

    // ----------------------------------------------------- crash: release

    /**
     * dsf F-6 release half: a write fault at the receipt write crashes release
     * mid-flight. Whatever the implementation committed before the fault
     * (nothing, or a RELEASING transition the §8.4 recovery table replays as
     * M-LS-17), the caller-visible terminal after restart is single-shaped:
     * the same-key replay returns a durable receipt, and the lease stops
     * blocking new applies — a release that neither completes nor frees the
     * device is the INV-21 false-green this row exists to kill.
     */
    @Test
    fun release_crashBetweenWrites_convergesToTerminalRelease() {
        val h = harness()
        val receipt = h.apply(key = "rel-crash-apply")

        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }
        try {
            h.release(receipt.leaseId, key = "rel-crash")
            fail("injected write fault must surface, not be swallowed")
        } catch (expected: RuntimeException) {
            // SimulatedWriteCrash (or a transport wrapper), see above.
        }
        h.kv.failOnWrite = null

        h.restart(cleanlinessProvable = true)

        // Same-key replay converges and is durable-stable.
        val replay = h.release(receipt.leaseId, key = "rel-crash")
        val replay2 = h.release(receipt.leaseId, key = "rel-crash")
        assertEquals("stored receipt, not a second release", replay, replay2)

        // Device converged: the released lease no longer blocks a new apply.
        val next = h.apply(key = "rel-next", intent = h.intent(attemptId = "att-2"))
        assertNotEquals(receipt.leaseId, next.leaseId)
    }

    @Test
    fun release_externalCleanupBeginsOnlyAfterReleasingOwnerIsDurable() {
        val h = harness()
        val applied = h.apply(key = "rel-owner-apply")
        var durableOwnerAtCleanup: LeaseRecord? = null
        h.env.beforeCleanup = {
            durableOwnerAtCleanup = EnvironmentLeaseStore(
                h.kv.reopenCommitted(),
                h.clock,
            ).get(applied.leaseId)
        }

        h.release(applied.leaseId, key = "rel-owner-durable")

        assertEquals(LeaseState.RELEASING, durableOwnerAtCleanup?.state)
        assertEquals("rel-owner-durable", durableOwnerAtCleanup?.releaseIdempotencyKey)
    }

    @Test
    fun release_cleanupSuccessThenFinalizeFailure_samePublicReplayReturnsReceipt() {
        val h = harness()
        val applied = h.apply(key = "rel-finalize-apply")
        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }

        try {
            h.release(applied.leaseId, key = "rel-finalize-fault")
            fail("finalize fault must surface")
        } catch (_: RuntimeException) {
            // Expected.
        }
        h.kv.failOnWrite = null

        assertEquals(LeaseState.RELEASE_INCOMPLETE, h.leases.get(applied.leaseId)?.state)
        val replay = h.release(applied.leaseId, key = "rel-finalize-fault")
        assertTrue(replay.releaseComplete)
        assertEquals(replay, h.release(applied.leaseId, key = "rel-finalize-fault"))
    }

    @Test
    fun release_processDiesAfterCleanup_restartKeepsSameKeyPublicReplayReachable() {
        val h = harness()
        val applied = h.apply(key = "rel-restart-apply")
        h.env.afterCleanupEnvironmentMutation = {
            h.kv.failOnWrite = { _, _ -> true }
            throw IllegalStateException("process died after cleanup")
        }

        try {
            h.release(applied.leaseId, key = "rel-restart-fault")
            fail("simulated process death must surface")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        h.kv.failOnWrite = null
        h.env.afterCleanupEnvironmentMutation = null
        assertEquals(LeaseState.RELEASING, h.leases.get(applied.leaseId)?.state)

        h.restart(cleanlinessProvable = false)
        val replay = h.release(applied.leaseId, key = "rel-restart-fault")
        assertTrue(replay.releaseComplete)
        assertEquals(replay, h.release(applied.leaseId, key = "rel-restart-fault"))
    }

    @Test
    fun publicReleaseAfterProviderCleanupMustKeepItsReceiptRecoveryOwnership() {
        val h = harness()
        val applied = h.apply(key = "rel-provider-handoff-apply")
        h.handler.onCallerRevoked(AUTO_PKG, AUTO_SIGNER)
        h.env.cleanupOutcome = CleanupOutcome.Incomplete(
            listOf(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire),
        )
        h.handler.runRevokedLeaseCleanup()
        assertEquals(LeaseState.RELEASE_INCOMPLETE, h.leases.get(applied.leaseId)?.state)
        assertNotNull(h.leases.get(applied.leaseId)?.recoveryEvidenceRef)

        // A new operator decision authorizes the same exact principal to take
        // ownership of public release recovery.
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.env.cleanupOutcome = CleanupOutcome.Complete
        h.env.afterCleanupEnvironmentMutation = {
            // Model process death after public RELEASING + cleanup: neither the
            // finalize commit nor the catch-path marker can land.
            h.kv.failOnWrite = { _, _ -> true }
            throw IllegalStateException("process died during public recovery release")
        }
        try {
            h.release(applied.leaseId, key = "rel-provider-handoff-public")
            fail("simulated process death must surface")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        h.kv.failOnWrite = null
        h.env.afterCleanupEnvironmentMutation = null

        val publicOwner = h.leases.get(applied.leaseId)
        assertEquals(LeaseState.RELEASING, publicOwner?.state)
        assertEquals("rel-provider-handoff-public", publicOwner?.releaseIdempotencyKey)

        h.restart(cleanlinessProvable = false)

        assertEquals(
            "non-null public release key must dominate stale provider-cleanup provenance",
            LeaseState.RELEASING,
            h.leases.get(applied.leaseId)?.state,
        )
        val replay = h.release(applied.leaseId, key = "rel-provider-handoff-public")
        assertTrue(replay.releaseComplete)
        assertEquals(
            replay,
            h.release(applied.leaseId, key = "rel-provider-handoff-public"),
        )
    }

    /**
     * INV-14 caller identity is the full (applicationId, signerDigest)
     * principal. Approving a replacement signer for the same application id
     * must not transfer release authority over a lease earned by the previous
     * signer. The old applicationId-only check made this operation succeed.
     */
    @Test
    fun release_sameApplicationIdDifferentSigner_isForeignStaleLease() {
        val h = harness()
        val receipt = h.apply(key = "rel-signer-apply")

        val replacementSigner = "signer-auto-2-repaired"
        h.resolver.register(AUTO_UID, AUTO_PKG, replacementSigner)
        h.pair(AUTO_PKG, replacementSigner)

        expectContractFailure(ContractErrorCodeV1.STALE_LEASE) {
            h.release(receipt.leaseId, key = "rel-signer-replacement")
        }
        assertEquals(
            "foreign replacement principal must leave the old lease active",
            LeaseState.ACTIVE,
            h.leases.get(receipt.leaseId)?.state,
        )
    }

    // ------------------------------------------------- idempotency scope

    /**
     * dsf F-9: the §7.2 lookup scope is (caller, operation, idempotencyKey) —
     * the KEY is caller-local, not device-global. Two callers reusing the same
     * key string must not see each other's receipts. The mutant this kills: a
     * key-global store would answer caller B's DIFFERENT request under A's key
     * with IDEMPOTENCY_CONFLICT (same key, different digest — wire 12) or,
     * worse, replay A's receipt to B.
     */
    @Test
    fun idempotencyScope_sameKeyAcrossCallers_isIsolated() {
        val h = harness()
        h.pair(OTHER_PKG, OTHER_SIGNER)

        // Caller A earns and releases under key "shared-k".
        val a = h.apply(uid = AUTO_UID, key = "shared-k")
        h.release(a.leaseId, uid = AUTO_UID, key = "shared-k-rel")

        // Caller B reuses the SAME key string for a DIFFERENT request:
        // (B, APPLY, shared-k) finds nothing — fresh execution, fresh lease.
        val bIntent = h.intent(attemptId = "att-b")
        val b = h.apply(uid = OTHER_UID, key = "shared-k", intent = bIntent)
        assertNotEquals("B gets its own lease, not A's replay", a.leaseId, b.leaseId)
        assertEquals("both applies really executed", 2, h.env.applyCount)

        // B's replay under its own scope returns B's receipt — and only B's.
        val bReplay = h.apply(uid = OTHER_UID, key = "shared-k", intent = bIntent)
        assertEquals("per-caller replay returns the caller's own receipt", b, bReplay)
        assertEquals("replay executed nothing", 2, h.env.applyCount)
    }

    /**
     * `caller` in the idempotency scope is the full authorization principal,
     * not applicationId alone. After signer replacement, the same package and
     * key identify a different caller and must create a fresh receipt instead
     * of replaying the previous signer's lease.
     */
    @Test
    fun idempotencyScope_sameApplicationIdAcrossSigners_isIsolated() {
        val h = harness()
        val old = h.apply(key = "shared-signer-k")
        h.release(old.leaseId, key = "shared-signer-release")

        val replacementSigner = "signer-auto-2-repaired"
        h.resolver.register(AUTO_UID, AUTO_PKG, replacementSigner)
        h.pair(AUTO_PKG, replacementSigner)

        val replacement = h.apply(key = "shared-signer-k")
        assertNotEquals("replacement principal gets a fresh lease", old.leaseId, replacement.leaseId)
        assertEquals("both caller principals execute one apply", 2, h.env.applyCount)
    }

    /** A safely attributable pre-signer-scope receipt is replayed and migrated. */
    @Test
    fun idempotencyScope_legacyApplyReceipt_migratesOnlyAfterLeaseOwnershipProof() {
        val h = harness()
        val intent = h.intent()
        val intentHash = io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1.compute(intent)
        val leaseId = "legacy-receipt-lease"
        h.leases.put(
            LeaseRecord(
                leaseId = leaseId,
                callerApplicationId = AUTO_PKG,
                callerSignerDigest = AUTO_SIGNER,
                acceptedIntentHash = intentHash,
                state = LeaseState.RELEASED,
                applyIdempotencyKey = "legacy-receipt-key",
                startingEnvironmentRevision = 0L,
                deadlineElapsedRealtimeMs = h.clock.elapsedRealtimeMs() + 60_000L,
                applyOwnerGeneration = h.tracker.generation,
                earnedScheduleRef = "item-1",
            ),
        )
        val original = io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1(
            operationId = "legacy-operation",
            idempotencyKey = "legacy-receipt-key",
            leaseId = leaseId,
            acceptedIntentHash = intentHash,
            appliedAtEpochMs = h.clock.epochMs(),
            environmentRevision = 0L,
            verificationLevelWire =
                io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
                    .SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        )
        val receiptPayload = DurableFieldCodec.encode(
            listOf(
                original.operationId,
                original.idempotencyKey,
                original.leaseId,
                original.acceptedIntentHash,
                original.appliedAtEpochMs.toString(),
                original.environmentRevision.toString(),
                original.verificationLevelWire.toString(),
            ),
        )
        val legacyKey = DurableFieldCodec.encode(
            listOf(AUTO_PKG, ContractOperation.APPLY.name, original.idempotencyKey),
        )
        h.kv.write(
            DurableIdempotencyStore.RECEIPT_NAMESPACE,
            legacyKey,
            DurableFieldCodec.encode(
                listOf(
                    AUTO_PKG,
                    ContractOperation.APPLY.name,
                    original.idempotencyKey,
                    RequestDigests.applyDigest(intentHash),
                    "",
                    h.clock.elapsedRealtimeMs().toString(),
                    receiptPayload,
                ),
            ),
        )

        assertEquals(original, h.apply(key = original.idempotencyKey, intent = intent))
        assertEquals("legacy replay executes no environment mutation", 0, h.env.applyCount)
        assertEquals(
            AUTO_SIGNER,
            h.idempotency.find(
                AUTO_PKG,
                AUTO_SIGNER,
                ContractOperation.APPLY,
                original.idempotencyKey,
            )?.callerSignerDigest,
        )
    }

    // ------------------------------------------------ durable codec totality

    /**
     * §6.3.4 idempotency keys are caller FREE strings, and the stored receipt
     * payload embeds them. Same fault class as the round-4 marker finding: a
     * separator-framed payload codec shifts every following field when the key
     * contains the separator, so the REPLAY (which deserializes the stored
     * payload) corrupts or crashes instead of returning the original receipt.
     * The codec must be total over all strings — "IDs are printable" is exactly
     * the assumption §6.7.3 removed.
     */
    @Test
    fun applyRelease_tabInIdempotencyKey_replayRoundTripsReceipt() {
        val h = harness()
        val hostileApplyKey = "k\t1:hostile"

        val first = h.apply(key = hostileApplyKey)
        val replay = h.apply(key = hostileApplyKey)
        assertEquals("stored apply receipt must round-trip byte-identically", first, replay)
        assertEquals("replay executed nothing", 1, h.env.applyCount)

        val hostileReleaseKey = "rk\t:x"
        val rel = h.release(first.leaseId, key = hostileReleaseKey)
        val relReplay = h.release(first.leaseId, key = hostileReleaseKey)
        assertEquals("stored release receipt must round-trip byte-identically", rel, relReplay)
        assertEquals("cleanup ran once", 1, h.env.cleanupCount)
    }

    // ------------------------------------------------ F14: honest apply receipt

    /**
     * C5 F14 (task 0001787595763599): the apply receipt's
     * `verificationLevelWire` must report the COMPUTED verification outcome of
     * THIS apply — the controller already computes it from the real publish
     * result (ConfigPrefsSync failure → NONE; P1-2 fix), but the handler
     * discarded that return value and stamped the VERIFIED constant into the
     * receipt. On the C5 device that produced receipt verif=1 while
     * observe reported verified=false: a trusted-ledger entry whose
     * verification level was CLAIMED, not measured (INV-08).
     *
     * Kill-the-regression shape: reverting the handler to the constant makes
     * this red again, because the fake's publish outcome is pinned to NONE.
     */
    @Test
    fun apply_publishFails_receiptReportsNone_notConstantVerified() {
        val h = harness()
        h.env.applyVerificationLevelWire = VerificationLevelV1.NONE.wire

        val receipt = h.apply(key = "apl-f14-none")

        assertEquals(
            "receipt must carry the computed verification level (publish failed → NONE), not a constant",
            VerificationLevelV1.NONE.wire,
            receipt.verificationLevelWire,
        )
    }

    /** Complement: a successful publish still reports VERIFIED through the same computed path. */
    @Test
    fun apply_publishSucceeds_receiptReportsVerified() {
        val h = harness()
        h.env.applyVerificationLevelWire =
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire

        val receipt = h.apply(key = "apl-f14-ok")

        assertEquals(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            receipt.verificationLevelWire,
        )
    }

    /**
     * R4 P3 (C5 cross-surface): the F14 regression must reproduce the ORIGINAL
     * device symptom — receipt/observe disagreeing — not just kill a handler
     * constant. On the C5 device, receipt verif=1 while observe reported
     * verified=false. The fake now mirrors production: the same publish
     * outcome that lands in the receipt (recordLastApplied(verified)) also
     * drives observeEffective(), so a failed publish must surface NONE on BOTH
     * surfaces. Asserting observe == NONE here pins the cross-surface
     * agreement that the old fake (observe hardcoded VERIFIED) could never
     * fail on.
     */
    @Test
    fun apply_publishFails_receiptAndObserveAgreeOnNone() {
        val h = harness()
        h.env.applyVerificationLevelWire = VerificationLevelV1.NONE.wire

        val receipt = h.apply(key = "apl-f14-none")

        assertEquals(
            "receipt must carry the computed verification level (publish failed → NONE)",
            VerificationLevelV1.NONE.wire,
            receipt.verificationLevelWire,
        )

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = receipt.leaseId,
                operationId = "apl-f14-observe",
                expectedIntentHash = receipt.acceptedIntentHash,
            ),
        )
        assertEquals(
            "observe must NOT report VERIFIED while the receipt says NONE — the " +
                "C5 cross-surface contradiction is the original symptom",
            VerificationLevelV1.NONE.wire,
            observed.verificationLevelWire,
        )
    }

    @Test
    fun observe_verifiedResponseCarriesAResolvableDurableAuditEvidenceRef() {
        val h = harness()
        // Production QwyEnvironmentController has no audit-store dependency. Model its current
        // empty environment projection: the contract handler, which owns the durable audit store
        // and caller/lease identity, must attach the observation's provenance before returning it.
        h.env.evidenceRefs = emptyList()
        val receipt = h.apply(key = "apl-observe-evidence")

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = receipt.leaseId,
                operationId = "observe-evidence",
                expectedIntentHash = receipt.acceptedIntentHash,
            ),
        )

        val auditEvent = h.audit.all().last()
        val durableRef = "qwy:integration.v1.audit:${auditEvent.seq}"
        assertTrue(
            "a VERIFIED observation with no evidenceRefs is unverifiable and Auto must reject it",
            observed.evidenceRefs.isNotEmpty(),
        )
        assertTrue(
            "the returned reference must resolve to the exact durable observe audit event",
            durableRef in observed.evidenceRefs,
        )
        assertEquals("observe", auditEvent.event)
        assertEquals(AUTO_PKG, auditEvent.callerApplicationId)
        assertEquals(receipt.leaseId, auditEvent.leaseId)
        assertEquals(receipt.acceptedIntentHash, auditEvent.payloadDigest)
    }
}

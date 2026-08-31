package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.SimulatedWriteCrash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeAdvanceProviderTest {

    @Test
    fun `reserved advance and immediate PRE POST observation share exactly R plus one`() {
        val h = readyAuthoritativeHarness("authoritative-normal")
        val lease = h.apply(key = "authoritative-normal-apply")
        h.release(lease.leaseId, key = "authoritative-normal-release")
        val before = h.tracker.snapshot().revision

        val receipt = h.handler.completeAndAdvance(
            AUTO_UID,
            request(h, lease.leaseId, "authoritative-normal-advance"),
        )

        assertEquals(before + 1L, receipt.effectiveEnvironmentRevision)
        assertEquals(receipt.effectiveEnvironmentRevision, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))

        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-normal-observe",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(receipt.effectiveEnvironmentRevision, observed.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
        assertEquals("item-2", observed.scheduleItemId)
    }

    @Test
    fun `remote completion then local pending-clear crash rolls back ACK and replays once`() {
        val h = readyAuthoritativeHarness("authoritative-finalize-crash")
        val lease = h.apply(key = "authoritative-finalize-crash-apply")
        h.release(lease.leaseId, key = "authoritative-finalize-crash-release")
        val request = request(h, lease.leaseId, "authoritative-finalize-crash-advance")
        val before = h.tracker.snapshot().revision
        val beginBefore = h.semanticEndpoint.beginCount
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null

        assertEquals(before, h.tracker.snapshot().revision)
        assertFalse(pendingMarker(h).isNullOrEmpty())
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals(before + 1L, reservation.reservedRevision)
        assertEquals("item-2", h.env.currentItemId)
        assertEquals(beginBefore + 1, h.semanticEndpoint.beginCount)

        val replay = h.handler.completeAndAdvance(AUTO_UID, request)

        assertEquals(reservation.reservedRevision, replay.effectiveEnvironmentRevision)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertEquals("completed correlation must avoid a second remote begin",
            beginBefore + 1, h.semanticEndpoint.beginCount)
        assertEquals(1, h.env.advanceCount)
        assertEquals("", pendingMarker(h))
        assertNull(h.tracker.activeAuthoritativeReservation())
    }

    @Test
    fun `failure before pointer mutation proves no-op and retries the same reservation`() {
        val h = readyAuthoritativeHarness("authoritative-pre-pointer-crash")
        val lease = h.apply(key = "authoritative-pre-pointer-crash-apply")
        h.release(lease.leaseId, key = "authoritative-pre-pointer-crash-release")
        val request = request(h, lease.leaseId, "authoritative-pre-pointer-crash-advance")
        val revisionBefore = h.tracker.snapshot().revision
        val beginBefore = h.semanticEndpoint.beginCount
        h.env.failNextAdvancePointer = true

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }

        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals(revisionBefore + 1L, reservation.reservedRevision)
        assertEquals("item-1", h.env.currentItemId)
        assertEquals(revisionBefore, h.tracker.snapshot().revision)
        assertFalse(pendingMarker(h).isNullOrEmpty())
        assertEquals(beginBefore + 1, h.semanticEndpoint.beginCount)

        val replay = h.handler.completeAndAdvance(AUTO_UID, request)

        assertEquals(reservation.reservedRevision, replay.effectiveEnvironmentRevision)
        assertEquals("item-2", h.env.currentItemId)
        assertEquals(1, h.env.advanceCount)
        assertEquals(beginBefore + 2, h.semanticEndpoint.beginCount)
        assertEquals(
            reservation.mutationId,
            h.authoritativeOracle.snapshot()?.lastCompletedQwyMutationId,
        )
        assertTrue(h.semanticCoordinator.isReadyFor(
            checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation)),
        ))
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
    }

    @Test
    fun `owner restart coalesces a remotely completed pending advance without inheriting FULL`() {
        val h = readyAuthoritativeHarness("authoritative-owner-restart")
        val lease = h.apply(key = "authoritative-owner-restart-apply")
        h.release(lease.leaseId, key = "authoritative-owner-restart-release")
        val request = request(h, lease.leaseId, "authoritative-owner-restart-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("item-2", h.env.currentItemId)

        h.restart(cleanlinessProvable = false)

        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertEquals(
            reservation.reservedRevision,
            h.handler.completeAndAdvance(AUTO_UID, request).effectiveEnvironmentRevision,
        )

        val first = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-owner-restart-observe-1",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision, first.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, first.continuityCoverageWire)

        val second = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-owner-restart-observe-2",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision, second.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
    }

    @Test
    fun `reboot quarantines an uncorrelatable advance receipt without stranding service`() {
        val h = readyAuthoritativeHarness("authoritative-reboot-quarantine")
        val lease = h.apply(key = "authoritative-reboot-quarantine-apply")
        h.release(lease.leaseId, key = "authoritative-reboot-quarantine-release")
        val request = request(h, lease.leaseId, "authoritative-reboot-quarantine-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())

        h.restart(cleanlinessProvable = false, reboot = true)

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertEquals("the committed pointer still moves only once", 1, h.env.advanceCount)
        assertThrows(IllegalStateException::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }

        val first = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-reboot-quarantine-observe-1",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision + 1L, first.environmentRevision)
        assertEquals(ContinuityCoverageV1.NONE.wire, first.continuityCoverageWire)

        val second = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = lease.leaseId,
                operationId = "authoritative-reboot-quarantine-observe-2",
                expectedIntentHash = lease.acceptedIntentHash,
            ),
        )
        assertEquals(reservation.reservedRevision + 1L, second.environmentRevision)
        assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
    }

    @Test
    fun `unrelated oracle mutation quarantines stale advance correlation on restart`() {
        val h = readyAuthoritativeHarness("authoritative-interleaving-quarantine")
        val lease = h.apply(key = "authoritative-interleaving-quarantine-apply")
        h.release(lease.leaseId, key = "authoritative-interleaving-quarantine-release")
        val request = request(h, lease.leaseId, "authoritative-interleaving-quarantine-advance")
        var pendingWrites = 0
        h.kv.failOnWrite = { namespace, key ->
            if (namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY
            ) {
                pendingWrites += 1
                pendingWrites == 2
            } else {
                false
            }
        }

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        h.kv.failOnWrite = null
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        h.authoritativeOracle.changed(mutationId = "foreign-interleaving")

        h.restart(cleanlinessProvable = false)

        assertEquals(reservation.reservedRevision + 1L, h.tracker.snapshot().revision)
        assertEquals(ContinuityCoverageV1.NONE.wire, h.tracker.snapshot().coverageWire)
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
        assertTrue(h.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
        assertThrows(IllegalStateException::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
    }

    @Test
    fun `partial pointer success rebaselines and settles in the same owner process`() {
        val h = readyAuthoritativeHarness("authoritative-partial-projection")
        val lease = h.apply(key = "authoritative-partial-projection-apply")
        h.release(lease.leaseId, key = "authoritative-partial-projection-release")
        val request = request(h, lease.leaseId, "authoritative-partial-projection-advance")
        val beginBefore = h.semanticEndpoint.beginCount
        h.env.failNextProjectionAfterPointer = true

        assertThrows(SimulatedWriteCrash::class.java) {
            h.handler.completeAndAdvance(AUTO_UID, request)
        }
        val reservation = checkNotNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("item-2", h.env.currentItemId)
        assertFalse(pendingMarker(h).isNullOrEmpty())

        val replay = h.handler.completeAndAdvance(AUTO_UID, request)

        assertEquals(reservation.reservedRevision, replay.effectiveEnvironmentRevision)
        assertEquals(reservation.reservedRevision, h.tracker.snapshot().revision)
        assertEquals(beginBefore + 2, h.semanticEndpoint.beginCount)
        assertEquals(1, h.env.advanceCount)
        assertEquals(1, h.env.projectionConvergenceCount)
        assertEquals(
            reservation.mutationId,
            h.authoritativeOracle.snapshot()?.lastCompletedQwyMutationId,
        )
        assertNull(h.tracker.activeAuthoritativeReservation())
        assertEquals("", pendingMarker(h))
    }

    private fun readyAuthoritativeHarness(key: String): ProviderHarness =
        ProviderHarness.create().also { h ->
            h.env.authoritativeSemanticMutations = true
            h.pair()
            // The harness registered its initial semantic baseline at boot;
            // assert the controlled source is genuinely ready before mutating.
            val digest = checkNotNull(h.env.authoritativeSemanticDigest(h.tracker.generation))
            check(h.semanticCoordinator.isReadyFor(digest)) { "$key semantic session not ready" }
        }

    private fun request(
        h: ProviderHarness,
        leaseId: String,
        key: String,
    ): CompleteAndAdvanceRequestV1 {
        val proof = CompletionProofV1(
            scheduleItemId = "item-1",
            trustedSuccessCount = 3,
            quotaRequired = 3,
            ledgerRef = "auto:ledger:$key:item-1",
            verifiedAtElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
        )
        val bare = CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = key,
            requestDigest = "",
            expectedScheduleId = h.env.scheduleId,
            expectedScheduleVersion = h.env.scheduleVersion,
            expectedCurrentItemId = "item-1",
            completionProof = proof,
            callerProtocolVersion = 1,
        )
        return bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
    }

    private fun pendingMarker(h: ProviderHarness): String? = h.kv.read(
        EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
        EnvironmentControlHandler.ADVANCE_PENDING_KEY,
    )
}

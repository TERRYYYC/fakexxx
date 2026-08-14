package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Parcel round trips for every v1 DTO, spec §6.3 / §6.3.2.
 *
 * These use a real [Parcel] rather than data-class equality so the assertions
 * cover what actually crosses the Binder boundary, including nullability and
 * collection ordering.
 */
@RunWith(RobolectricTestRunner::class)
class ContractRoundTripTest {

    private inline fun <reified T : Parcelable> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            return requireNotNull(parcel.readParcelable(T::class.java.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private fun intent() = EnvironmentIntentV1(
        runId = "run-1",
        attemptId = "attempt-1",
        profileRef = "profile-1",
        scheduleRef = "schedule-1",
        requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs = 1_786_000_000_000L,
        deadlineEpochMs = 1_786_000_600_000L,
    )

    @Test
    fun `EnvironmentIntentV1 survives a parcel round trip`() {
        val original = intent()
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `CapabilitySnapshotV1 survives a parcel round trip`() {
        val original = CapabilitySnapshotV1(
            serviceVersion = "3.0.0",
            supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire, DeliveryModeV1.HOOK.wire),
            supportedVerificationLevelWires = listOf(
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                VerificationLevelV1.HOOK_UNVERIFIED.wire,
            ),
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 42L,
            profileRefs = listOf("p1", "p2"),
            scheduleRefs = listOf("s1"),
            currentScheduleId = "s1",
            currentItemId = "item-7",
            scheduleVersion = 3L,
            exhausted = false,
        )
        val restored = roundTrip(original)
        assertEquals(original, restored)
        assertEquals(ContractV1.PROTOCOL_VERSION, restored.protocolVersion)
        // Wire-code list order is part of the representation, not incidental.
        assertEquals(original.supportedModeWires, restored.supportedModeWires)
    }

    @Test
    fun `PreflightRequestV1 and PreflightReportV1 survive parcel round trips`() {
        val request = PreflightRequestV1(
            intent = intent(),
            idempotencyKey = "idem-1",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        )
        assertEquals(request, roundTrip(request))

        val passed = PreflightReportV1(
            acceptedIntentHash = CanonicalIntentDigestV1.compute(intent()),
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            waitUntilEpochMs = null,
            achievableVerificationLevelWire =
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L,
            blockingReasonWires = emptyList(),
            scheduleItemId = "item-7",
            scheduleVersion = 3L,
            exhausted = false,
        )
        val restoredPassed = roundTrip(passed)
        assertEquals(passed, restoredPassed)
        assertNull("waitUntil must stay null when not WAIT_UNTIL", restoredPassed.waitUntilEpochMs)

        val blocked = passed.copy(
            scheduleDecisionWire = ScheduleDecisionV1.WAIT_UNTIL.wire,
            waitUntilEpochMs = 1_786_000_900_000L,
            blockingReasonWires = listOf(
                ContractErrorCodeV1.SCHEDULE_DENIED.wire,
                ContractErrorCodeV1.CONTINUITY_NOT_FULL.wire,
            ),
        )
        val restoredBlocked = roundTrip(blocked)
        assertEquals(blocked, restoredBlocked)
        assertNotNull(restoredBlocked.waitUntilEpochMs)
        assertEquals(2, restoredBlocked.blockingReasonWires.size)
    }

    @Test
    fun `ApplyRequestV1 and ApplyReceiptV1 survive parcel round trips`() {
        val request = ApplyRequestV1(
            intent = intent(),
            idempotencyKey = "idem-2",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        )
        assertEquals(request, roundTrip(request))

        val receipt = ApplyReceiptV1(
            operationId = "op-1",
            idempotencyKey = "idem-2",
            leaseId = "lease-1",
            acceptedIntentHash = CanonicalIntentDigestV1.compute(intent()),
            appliedAtEpochMs = 1_786_000_010_000L,
            environmentRevision = 8L,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        )
        assertEquals(receipt, roundTrip(receipt))
    }

    @Test
    fun `ObserveRequestV1 survives a parcel round trip`() {
        val request = ObserveRequestV1(
            leaseId = "lease-1",
            operationId = "op-2",
            expectedIntentHash = CanonicalIntentDigestV1.compute(intent()),
        )
        assertEquals(request, roundTrip(request))
    }

    @Test
    fun `EnvironmentObservationV1 survives a parcel round trip with all fields present`() {
        val observation = EnvironmentObservationV1(
            leaseId = "lease-1",
            acceptedIntentHash = CanonicalIntentDigestV1.compute(intent()),
            observedAtEpochMs = 1_786_000_020_000L,
            observedAtElapsedRealtimeMs = 620_000L,
            environmentRevision = 8L,
            environmentFingerprint = "fp-abc",
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            continuitySinceEpochMs = 1_786_000_005_000L,
            continuitySinceElapsedRealtimeMs = 605_000L,
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            effectiveLatitude = -33.8688197,
            effectiveLongitude = 151.2092955,
            isMock = true,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            evidenceRefs = listOf("ev-1", "ev-2"),
            scheduleItemId = "item-7",
            scheduleVersion = 3L,
        )
        assertEquals(observation, roundTrip(observation))
    }

    /**
     * Nullable observation fields must survive as null.
     *
     * This is the shape a degraded provider returns, and it is exactly the shape
     * the trust policy has to reject rather than treat as "everything else
     * passed" (INV-23 null-coordinate negative case).
     */
    @Test
    fun `EnvironmentObservationV1 preserves nulls in its optional fields`() {
        val degraded = EnvironmentObservationV1(
            leaseId = "lease-1",
            acceptedIntentHash = "",
            observedAtEpochMs = 1_786_000_020_000L,
            observedAtElapsedRealtimeMs = 620_000L,
            environmentRevision = 9L,
            environmentFingerprint = "fp-degraded",
            continuityCoverageWire = ContinuityCoverageV1.NONE.wire,
            continuitySinceEpochMs = null,
            continuitySinceElapsedRealtimeMs = null,
            deliveryModeWire = null,
            verificationLevelWire = VerificationLevelV1.NONE.wire,
            effectiveLatitude = null,
            effectiveLongitude = null,
            isMock = null,
            scheduleDecisionWire = ScheduleDecisionV1.DENIED.wire,
            evidenceRefs = emptyList(),
            scheduleItemId = "item-7",
            scheduleVersion = 3L,
        )
        val restored = roundTrip(degraded)
        assertEquals(degraded, restored)
        assertNull(restored.continuitySinceEpochMs)
        assertNull(restored.deliveryModeWire)
        assertNull(restored.effectiveLatitude)
        assertNull(restored.effectiveLongitude)
        assertNull(restored.isMock)
        assertNull(restored.continuitySinceElapsedRealtimeMs)
    }

    @Test
    fun `ReleaseRequestV1 and ReleaseReceiptV1 survive parcel round trips`() {
        val request = ReleaseRequestV1(
            leaseId = "lease-1",
            operationId = "op-3",
            idempotencyKey = "idem-3",
        )
        assertEquals(request, roundTrip(request))

        val complete = ReleaseReceiptV1(
            operationId = "op-3",
            idempotencyKey = "idem-3",
            leaseId = "lease-1",
            releasedAtEpochMs = 1_786_000_030_000L,
            environmentRevision = 10L,
            releaseComplete = true,
            residualReasonWires = emptyList(),
        )
        assertEquals(complete, roundTrip(complete))

        val incomplete = complete.copy(
            releaseComplete = false,
            residualReasonWires = listOf(ContractErrorCodeV1.RELEASE_INCOMPLETE.wire),
        )
        val restored = roundTrip(incomplete)
        assertEquals(incomplete, restored)
        // A false here is what drives INV-21's pause; it must not decay to true.
        assertEquals(false, restored.releaseComplete)
    }

    /**
     * A newer peer's unknown wire code must survive the parcel intact so the
     * consumer can decide about it, rather than blowing up during unparcel the
     * way an auto-encoded enum would.
     */
    @Test
    fun `unknown wire codes survive the parcel and stay decidable`() {
        val fromNewerPeer = roundTrip(
            CapabilitySnapshotV1(
                serviceVersion = "9.9.9",
                supportedModeWires = listOf(1, 2, 99),
                supportedVerificationLevelWires = listOf(1, 2, 3, 77),
                continuityCoverageWire = 88,
                environmentRevision = 1L,
                profileRefs = emptyList(),
                scheduleRefs = emptyList(),
                // No active schedule: §6.7.1 requires all four to be null together.
                currentScheduleId = null,
                currentItemId = null,
                scheduleVersion = null,
                exhausted = null,
            ),
        )
        assertEquals(listOf(1, 2, 99), fromNewerPeer.supportedModeWires)
        assertNull(
            "unknown coverage code must not decode to a known value",
            ContinuityCoverageV1.fromWire(fromNewerPeer.continuityCoverageWire),
        )
    }

    /**
     * A schedule that has been exhausted — the terminal item is retained, the
     * schedule does not wrap, and [CapabilitySnapshotV1.exhausted] is true (§6.7.1,
     * v1.54/v1.55). The `currentItemId` is the terminal item (not null — null is
     * outcome encoding in [AdvanceReceiptV1.advancedToItemId], not state encoding).
     */
    @Test
    fun `CapabilitySnapshotV1 with exhausted schedule survives the parcel`() {
        val exhaustedSchedule = CapabilitySnapshotV1(
            serviceVersion = "3.0.0",
            supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            ),
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 50L,
            profileRefs = listOf("p1"),
            scheduleRefs = listOf("s1"),
            currentScheduleId = "s1",
            currentItemId = "item-last",
            scheduleVersion = 7L,
            exhausted = true,
        )
        val restored = roundTrip(exhaustedSchedule)
        assertEquals(exhaustedSchedule, restored)
        assertEquals(true, restored.exhausted)
        assertEquals("item-last", restored.currentItemId)
        assertEquals(7L, restored.scheduleVersion)
    }

    /**
     * PreflightReportV1 with no active schedule: all three schedule group fields
     * must be null together (§6.7.1 group invariant, v1.55).
     */
    @Test
    fun `PreflightReportV1 all-null schedule group survives the parcel`() {
        val noSchedule = PreflightReportV1(
            acceptedIntentHash = CanonicalIntentDigestV1.compute(intent()),
            scheduleDecisionWire = ScheduleDecisionV1.DENIED.wire,
            waitUntilEpochMs = null,
            achievableVerificationLevelWire = VerificationLevelV1.NONE.wire,
            continuityCoverageWire = ContinuityCoverageV1.NONE.wire,
            environmentRevision = 1L,
            blockingReasonWires = listOf(ContractErrorCodeV1.SCHEDULE_DENIED.wire),
            scheduleItemId = null,
            scheduleVersion = null,
            exhausted = null,
        )
        val restored = roundTrip(noSchedule)
        assertEquals(noSchedule, restored)
        assertNull("scheduleItemId must stay null", restored.scheduleItemId)
        assertNull("scheduleVersion must stay null", restored.scheduleVersion)
        assertNull("exhausted must stay null", restored.exhausted)
    }

    private fun proof() = CompletionProofV1(
        scheduleItemId = "item-7",
        trustedSuccessCount = 12,
        quotaRequired = 12,
        ledgerRef = "ledger-abc",
        verifiedAtElapsedRealtimeMs = 640_000L,
    )

    private fun advanceRequest() = CompleteAndAdvanceRequestV1(
        leaseId = "lease-1",
        idempotencyKey = "idem-1",
        requestDigest = "d".repeat(64),
        expectedScheduleVersion = 3L,
        expectedCurrentItemId = "item-7",
        completionProof = proof(),
        callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
    )

    /** The advance request must survive Binder with its nested proof intact. */
    @Test
    fun `CompleteAndAdvanceRequestV1 survives the parcel with its nested proof`() {
        val original = advanceRequest()
        val restored = roundTrip(original)
        assertEquals(original, restored)
        // The nested Parcelable is the part most likely to be silently dropped.
        assertEquals(original.completionProof, restored.completionProof)
        assertEquals("item-7", restored.completionProof.scheduleItemId)
    }

    /**
     * The two preconditions must cross the boundary intact.
     *
     * If either were lost or defaulted in transit, the provider would evaluate
     * compare-and-advance against a value the caller never sent, and
     * SCHEDULE_ITEM_MISMATCH / SCHEDULE_VERSION_STALE would fire (or fail to
     * fire) on data that is not the caller's (§6.7.4).
     */
    @Test
    fun `advance preconditions are not lost or defaulted in transit`() {
        val restored = roundTrip(advanceRequest())
        assertEquals("item-7", restored.expectedCurrentItemId)
        assertEquals(3L, restored.expectedScheduleVersion)

        val other = roundTrip(advanceRequest().copy(expectedCurrentItemId = "item-8"))
        assertEquals("item-8", other.expectedCurrentItemId)
        // Two requests differing only in the precondition must stay distinguishable
        // after the round trip; if they collapsed, replay could not tell a
        // wrong-item advance from a retry of the same one.
        assertNotEquals(restored, other)
    }

    /**
     * Exhaustion is a terminal receipt with a null target, not a failure
     * (§6.7.4). A null advancedToItemId must survive as null rather than
     * becoming an empty string, which would read as "advanced to an item".
     */
    @Test
    fun `AdvanceReceiptV1 keeps a null target for the exhausted case`() {
        val advanced = AdvanceReceiptV1(
            outcomeWire = AdvanceOutcomeV1.ADVANCED.wire,
            advancedFromItemId = "item-7",
            advancedToItemId = "item-8",
            scheduleVersionAfter = 4L,
            effectiveIntentHash = "e".repeat(64),
            effectiveEnvironmentRevision = 9L,
            receiptDigest = "r".repeat(64),
        )
        assertEquals(advanced, roundTrip(advanced))

        val exhausted = advanced.copy(
            outcomeWire = AdvanceOutcomeV1.EXHAUSTED.wire,
            advancedToItemId = null,
        )
        val restored = roundTrip(exhausted)
        assertEquals(exhausted, restored)
        assertNull("exhausted must stay null, not become an empty string", restored.advancedToItemId)
    }
}

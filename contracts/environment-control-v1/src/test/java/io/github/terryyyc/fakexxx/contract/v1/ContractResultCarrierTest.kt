package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcel
import android.os.Parcelable
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Public business failures must cross Binder in a Parcelable carrier. They must
 * not depend on hidden framework exception classes.
 */
@RunWith(RobolectricTestRunner::class)
class ContractResultCarrierTest {

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

    @Test
    fun `business failure carrier preserves stable and unknown wire codes`() {
        val blocked = EnvironmentControlResultV1.failure(
            errorCodeWire = ContractErrorCodeV1.SCHEDULE_DENIED.wire,
            diagnosticMessage = "schedule denied",
        )
        val restoredBlocked = roundTrip(blocked)

        assertEquals(EnvironmentControlResultV1.SCHEMA_VERSION, restoredBlocked.resultSchemaVersion)
        assertEquals(ContractResultKindV1.ERROR.wire, restoredBlocked.resultKindWire)
        assertEquals(ContractErrorCodeV1.SCHEDULE_DENIED.wire, restoredBlocked.errorCodeWire)
        assertEquals(ContractErrorCodeV1.SCHEDULE_DENIED, restoredBlocked.errorCodeOrInternalFailure())
        assertNull(restoredBlocked.preflightReport)
        assertNull(restoredBlocked.applyReceipt)

        val fromNewerPeer = roundTrip(
            EnvironmentControlResultV1.failure(
                errorCodeWire = 9_999,
                diagnosticMessage = null,
            ),
        )
        assertEquals(9_999, fromNewerPeer.errorCodeWire)
        assertEquals(ContractErrorCodeV1.INTERNAL_FAILURE, fromNewerPeer.errorCodeOrInternalFailure())
    }

    @Test
    fun `success carrier keeps exactly the typed payload for every result kind`() {
        val snapshot = sampleCapabilitySnapshot()
        val preflight = samplePreflightReport()
        val apply = sampleApplyReceipt()
        val observation = sampleObservation()
        val release = sampleReleaseReceipt()
        val advance = sampleAdvanceReceipt()

        val cases = listOf(
            SuccessCase(
                kind = ContractResultKindV1.DISCOVER,
                payload = snapshot,
                result = EnvironmentControlResultV1.discover(snapshot),
                payloadFrom = EnvironmentControlResultV1::capabilitySnapshot,
            ),
            SuccessCase(
                kind = ContractResultKindV1.PREFLIGHT,
                payload = preflight,
                result = EnvironmentControlResultV1.preflight(preflight),
                payloadFrom = EnvironmentControlResultV1::preflightReport,
            ),
            SuccessCase(
                kind = ContractResultKindV1.APPLY,
                payload = apply,
                result = EnvironmentControlResultV1.apply(apply),
                payloadFrom = EnvironmentControlResultV1::applyReceipt,
            ),
            SuccessCase(
                kind = ContractResultKindV1.OBSERVE,
                payload = observation,
                result = EnvironmentControlResultV1.observe(observation),
                payloadFrom = EnvironmentControlResultV1::environmentObservation,
            ),
            SuccessCase(
                kind = ContractResultKindV1.RELEASE,
                payload = release,
                result = EnvironmentControlResultV1.release(release),
                payloadFrom = EnvironmentControlResultV1::releaseReceipt,
            ),
            SuccessCase(
                kind = ContractResultKindV1.COMPLETE_AND_ADVANCE,
                payload = advance,
                result = EnvironmentControlResultV1.completeAndAdvance(advance),
                payloadFrom = EnvironmentControlResultV1::advanceReceipt,
            ),
        )

        cases.forEach { case ->
            val result = roundTrip(case.result)

            assertEquals(case.kind.wire, result.resultKindWire)
            assertNull("${case.kind} must not carry an error code", result.errorCodeWire)
            assertEquals(case.payload, case.payloadFrom(result))
            assertOnlyPayload(result, case.kind)
        }
    }

    @Test
    fun `aidl surface returns the public result carrier instead of hidden exception failures`() {
        val aidl = repoRoot()
            .resolve("contracts/environment-control-v1/src/main/aidl/io/github/terryyyc/fakexxx/contract/v1/IEnvironmentControlV1.aidl")
            .toFile()
            .readText()

        listOf(
            "EnvironmentControlResultV1 discover();",
            "EnvironmentControlResultV1 preflight(in PreflightRequestV1 request);",
            "EnvironmentControlResultV1 apply(in ApplyRequestV1 request);",
            "EnvironmentControlResultV1 observe(in ObserveRequestV1 request);",
            "EnvironmentControlResultV1 release(in ReleaseRequestV1 request);",
            "EnvironmentControlResultV1 completeAndAdvance(in CompleteAndAdvanceRequestV1 request);",
        ).forEach { signature ->
            assertTrue("$signature must be part of the public AIDL surface", aidl.contains(signature))
        }

        assertTrue(aidl.contains("import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1;"))
        assertTrue("hidden framework exception must not be the business-failure contract",
            !aidl.contains("ServiceSpecificException"))
    }

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (current.parent != null) {
            if (current.resolve("contracts/environment-control-v1").toFile().exists()) {
                return current
            }
            current = current.parent
        }
        error("repository root not found from ${Path.of("").toAbsolutePath()}")
    }

    private data class SuccessCase(
        val kind: ContractResultKindV1,
        val payload: Any,
        val result: EnvironmentControlResultV1,
        val payloadFrom: (EnvironmentControlResultV1) -> Any?,
    )

    private fun assertOnlyPayload(result: EnvironmentControlResultV1, kind: ContractResultKindV1) {
        if (kind != ContractResultKindV1.DISCOVER) assertNull(result.capabilitySnapshot)
        if (kind != ContractResultKindV1.PREFLIGHT) assertNull(result.preflightReport)
        if (kind != ContractResultKindV1.APPLY) assertNull(result.applyReceipt)
        if (kind != ContractResultKindV1.OBSERVE) assertNull(result.environmentObservation)
        if (kind != ContractResultKindV1.RELEASE) assertNull(result.releaseReceipt)
        if (kind != ContractResultKindV1.COMPLETE_AND_ADVANCE) assertNull(result.advanceReceipt)
    }

    private fun sampleCapabilitySnapshot(): CapabilitySnapshotV1 =
        CapabilitySnapshotV1(
            serviceVersion = "3.0.0",
            supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            ),
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 42L,
            profileRefs = listOf("profile-1"),
            scheduleRefs = listOf("schedule-1"),
            currentScheduleId = "schedule-1",
            currentItemId = "item-1",
            scheduleVersion = 5L,
            exhausted = false,
        )

    private fun samplePreflightReport(): PreflightReportV1 =
        PreflightReportV1(
            acceptedIntentHash = "intent-hash",
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            waitUntilEpochMs = null,
            achievableVerificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 43L,
            blockingReasonWires = emptyList(),
            scheduleItemId = "item-1",
            scheduleVersion = 6L,
            exhausted = false,
        )

    private fun sampleApplyReceipt(): ApplyReceiptV1 =
        ApplyReceiptV1(
            operationId = "operation-1",
            idempotencyKey = "idempotency-1",
            leaseId = "lease-1",
            acceptedIntentHash = "intent-hash",
            appliedAtEpochMs = 1_786_711_000_000L,
            environmentRevision = 44L,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        )

    private fun sampleObservation(): EnvironmentObservationV1 =
        EnvironmentObservationV1(
            leaseId = "lease-1",
            acceptedIntentHash = "intent-hash",
            observedAtEpochMs = 1_786_711_000_100L,
            observedAtElapsedRealtimeMs = 123_456L,
            environmentRevision = 45L,
            environmentFingerprint = "fingerprint-1",
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            continuitySinceEpochMs = 1_786_710_999_000L,
            continuitySinceElapsedRealtimeMs = 122_456L,
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            effectiveLatitude = 10.0,
            effectiveLongitude = 20.0,
            isMock = true,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            evidenceRefs = listOf("evidence-1"),
            scheduleItemId = "item-1",
            scheduleVersion = 6L,
        )

    private fun sampleReleaseReceipt(): ReleaseReceiptV1 =
        ReleaseReceiptV1(
            operationId = "operation-1",
            idempotencyKey = "release-key-1",
            leaseId = "lease-1",
            releasedAtEpochMs = 1_786_711_000_200L,
            environmentRevision = 46L,
            releaseComplete = true,
            residualReasonWires = emptyList(),
        )

    private fun sampleAdvanceReceipt(): AdvanceReceiptV1 =
        AdvanceReceiptV1(
            outcomeWire = AdvanceOutcomeV1.ADVANCED.wire,
            advancedFromItemId = "item-1",
            advancedToItemId = "item-2",
            scheduleVersionAfter = 7L,
            effectiveIntentHash = "intent-hash-2",
            effectiveEnvironmentRevision = 47L,
            receiptDigest = "digest-1",
        )
}

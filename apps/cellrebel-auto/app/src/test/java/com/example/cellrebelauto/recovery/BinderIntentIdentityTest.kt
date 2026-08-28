package com.example.cellrebelauto.recovery

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * R44 (Sol GREEN-review-3 F2): the Binder apply request must carry the SAME intent object the
 * caller digested. Sol's production-path probe: the engine digested intent(runSessionId=5,
 * attemptId=77), but the executor REBUILT the intent from the idempotency key and the wire request
 * ended up with runId="auto-run-77" — the provider's acceptedIntentHash then attested a different
 * intent than the one Auto digested. This oracle stands a fake provider up through the Robolectric
 * bind shadow, drives the PRODUCTION [BinderExternalApplyExecutor], and pins request intent ==
 * digested intent, field by field.
 *
 * # Binder intent 同一性 oracle：线上 executor 走 Robolectric bind 假 provider，钉 request==digest  preimage
 */
@RunWith(RobolectricTestRunner::class)
class BinderIntentIdentityTest {

    private class FakeProviderService : IEnvironmentControlV1.Stub() {
        var lastApplyRequest: ApplyRequestV1? = null

        override fun discover(): EnvironmentControlResultV1 = EnvironmentControlResultV1.failure(0)
        override fun preflight(request: PreflightRequestV1): EnvironmentControlResultV1 = EnvironmentControlResultV1.failure(0)
        override fun observe(request: ObserveRequestV1): EnvironmentControlResultV1 = EnvironmentControlResultV1.failure(0)
        override fun release(request: ReleaseRequestV1): EnvironmentControlResultV1 = EnvironmentControlResultV1.failure(0)
        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): EnvironmentControlResultV1 =
            EnvironmentControlResultV1.failure(0)

        override fun apply(request: ApplyRequestV1): EnvironmentControlResultV1 {
            lastApplyRequest = request
            return EnvironmentControlResultV1.apply(
                ApplyReceiptV1(
                    operationId = "op-1",
                    idempotencyKey = request.idempotencyKey,
                    leaseId = "lease-1",
                    acceptedIntentHash = CanonicalIntentDigestV1.compute(request.intent),
                    appliedAtEpochMs = 1000L,
                    environmentRevision = 7L,
                    verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
                )
            )
        }
    }

    @Test
    fun `the Binder apply request carries the SAME intent the caller digested (no rebuild drift)`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val service = FakeProviderService()
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, ContractV1.SERVICE_CLASS_NAME),
            service
        )
        val executor = BinderExternalApplyExecutor(app)
        assertTrue("bind must dispatch against the frozen component", executor.bind())
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val intent = APlusOperationIdentity.intent(5L, 77L, 9L, "qwy-default-schedule", 1234L, 1234L + 90_000L)
        val digest = APlusOperationIdentity.requestDigest(intent)
        val outcome = executor.apply(77L, intent, "auto-aplus-apply-77", digest, 1000L)

        assertEquals("a valid provider response validates and yields the lease", "APPLIED", outcome.outcome)
        assertEquals("lease-1", outcome.leaseId)
        val sent = service.lastApplyRequest
        assertNotNull("the fake provider must have received the apply request", sent)
        sent!!
        // THE oracle (Sol's probe shape): runId carries the REAL session (5), never the attempt id.
        assertEquals("auto-run-5", sent.intent.runId)
        assertEquals("77", sent.intent.attemptId)
        assertEquals("plan-9", sent.intent.profileRef)
        assertEquals("qwy-default-schedule", sent.intent.scheduleRef)  // F12: verbatim provider anchor
        assertEquals(1234L, sent.intent.notBeforeEpochMs)
        assertEquals(1234L + 90_000L, sent.intent.deadlineEpochMs)
        // And the digest of the WIRE intent equals the digest the caller computed (preimage == request).
        assertEquals(digest, CanonicalIntentDigestV1.compute(sent.intent))
    }
}

package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ObservationAdmissionIntegrationTest {

    @Test
    fun `same window retries and overflow reject before creating another audit event`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply()
        fun request(operationId: String) = ObserveRequestV1(receipt.leaseId, operationId, receipt.acceptedIntentHash)

        h.handler.observe(ProviderHarness.AUTO_UID, request("retry"))
        expectContractFailure(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE) {
            h.handler.observe(ProviderHarness.AUTO_UID, request("retry"))
        }
        repeat(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW - 1) { index ->
            h.handler.observe(ProviderHarness.AUTO_UID, request("unique-$index"))
        }
        expectContractFailure(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE) {
            h.handler.observe(ProviderHarness.AUTO_UID, request("overflow"))
        }

        assertEquals(
            ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW,
            h.audit.all().count { it.event == "observe" },
        )
    }

    @Test
    fun `revision advance opens a new bounded window without raising coverage`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply()
        fun request(operationId: String) = ObserveRequestV1(receipt.leaseId, operationId, receipt.acceptedIntentHash)

        repeat(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW) { index ->
            h.handler.observe(ProviderHarness.AUTO_UID, request("before-$index"))
        }
        h.tracker.bump(RevisionBumpReason.PROFILE_CHANGED)
        val expectedCoverage = h.tracker.snapshot().coverageWire
        val after = h.handler.observe(ProviderHarness.AUTO_UID, request("after-bump"))

        assertEquals(1, h.audit.all().count { it.operationId == "after-bump" })
        assertEquals(expectedCoverage, after.continuityCoverageWire)
    }

    @Test
    fun `clock rollback cannot reset admission and owner restart closes the old lease`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply()
        fun request(operationId: String) = ObserveRequestV1(receipt.leaseId, operationId, receipt.acceptedIntentHash)

        repeat(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW) { index ->
            h.handler.observe(ProviderHarness.AUTO_UID, request("before-$index"))
        }
        h.clock.elapsed -= 5_000L
        expectContractFailure(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE) {
            h.handler.observe(ProviderHarness.AUTO_UID, request("clock-rollback"))
        }
        h.restart(cleanlinessProvable = true)
        expectContractFailure(ContractErrorCodeV1.STALE_LEASE) {
            h.handler.observe(ProviderHarness.AUTO_UID, request("after-owner-restart"))
        }
        assertEquals(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW, h.audit.all().count { it.event == "observe" })
    }

    @Test
    fun `lease replacement cannot reuse the old lease bucket or bypass its stale gate`() {
        val h = ProviderHarness.create()
        h.pair()
        val first = h.apply()
        fun firstRequest(operationId: String) = ObserveRequestV1(first.leaseId, operationId, first.acceptedIntentHash)

        repeat(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW) { index ->
            h.handler.observe(ProviderHarness.AUTO_UID, firstRequest("old-$index"))
        }
        h.release(first.leaseId, key = "release-old", operationId = "release-old-op")
        val replacement = h.apply(
            key = "apply-replacement",
            intent = h.intent(runId = "run-replacement", attemptId = "attempt-replacement"),
        )

        expectContractFailure(ContractErrorCodeV1.STALE_LEASE) {
            h.handler.observe(ProviderHarness.AUTO_UID, firstRequest("old-after-release"))
        }
        h.handler.observe(
            ProviderHarness.AUTO_UID,
            ObserveRequestV1(replacement.leaseId, "new-lease", replacement.acceptedIntentHash),
        )
        assertEquals(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW + 1, h.audit.all().count { it.event == "observe" })
    }

    @Test
    fun `concurrent unique observes admit exactly one bounded window`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val outcomes = executor.invokeAll((0..ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW).map { index ->
                Callable {
                    runCatching {
                        h.handler.observe(
                            ProviderHarness.AUTO_UID,
                            ObserveRequestV1(receipt.leaseId, "parallel-$index", receipt.acceptedIntentHash),
                        )
                    }.exceptionOrNull() as? ContractException
                }
            }).map { it.get() }
            assertEquals(1, outcomes.count { it?.code == ContractErrorCodeV1.CAPABILITY_UNAVAILABLE })
            assertEquals(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW, outcomes.count { it == null })
            assertEquals(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW, h.audit.all().count { it.event == "observe" })
        } finally {
            executor.shutdownNow()
        }
    }
}

package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.ContractResultKindV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * KB-7=A (v1.59): the exception→carrier mapping at the provider's Binder seam.
 *
 * [toTypedResult] is the pure half of EnvironmentControlService.typedResult —
 * extracted because the service class is Android-bound (Stub/Binder) and
 * cannot be instantiated in a JVM lane, while the mapping is exactly the part
 * that can drift silently between "expected business failure" and "transport
 * failure".
 *
 * dsf P2-2: the contract module's ContractResultCarrierTest covers the
 * carrier's own round-trip; this pins the PROVIDER-side jump — which
 * exceptions enter the carrier and which must not.
 */
class TypedResultMappingTest {

    private fun successCarrier(): EnvironmentControlResultV1 =
        EnvironmentControlResultV1.discover(
            CapabilitySnapshotV1(
                serviceVersion = "test",
                supportedModeWires = listOf(1),
                supportedVerificationLevelWires = listOf(1),
                continuityCoverageWire = 1,
                environmentRevision = 1L,
                profileRefs = emptyList(),
                scheduleRefs = emptyList(),
                currentScheduleId = null,
                currentItemId = null,
                scheduleVersion = null,
                exhausted = null,
            ),
        )

    /** A ContractException IS a business answer → ERROR + the frozen wire code. */
    @Test
    fun contractException_becomesErrorWithExactWireCode() {
        val result = toTypedResult {
            throw ContractException(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH, "provider test")
        }

        assertEquals(
            ContractResultKindV1.ERROR.wire,
            result.resultKindWire,
        )
        assertEquals(
            "the frozen wire code crosses Binder as data",
            ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH.wire,
            result.errorCodeWire,
        )
        // Carrier invariant: ERROR carries no payload.
        assertNull(result.capabilitySnapshot)
        assertNull(result.advanceReceipt)
    }

    /** Success passes through untouched. */
    @Test
    fun success_passthrough() {
        val success = successCarrier()
        val result = toTypedResult { success }
        assertEquals(success, result)
        assertEquals(ContractResultKindV1.DISCOVER.wire, result.resultKindWire)
        assertNull(result.errorCodeWire)
    }

    /**
     * A NON-contract throwable is a transport failure — it must PROPAGATE, not
     * be laundered into a business ERROR. Reusing an approximate wire code
     * would let Auto make a trust decision on a fabricated outcome.
     */
    @Test
    fun nonContractThrowable_propagatesAsTransportFailure() {
        try {
            toTypedResult { throw IllegalStateException("store I/O failure") }
            fail("non-contract throwable must propagate, not become a carrier")
        } catch (expected: IllegalStateException) {
            assertEquals("store I/O failure", expected.message)
        }
    }
}

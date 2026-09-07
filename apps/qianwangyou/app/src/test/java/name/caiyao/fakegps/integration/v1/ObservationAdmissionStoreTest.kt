package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservationAdmissionStoreTest {

    @Test
    fun `caller lease and monotonic revision isolate bounded admission windows`() {
        val kv = InMemoryDurableKv()
        val store = ObservationAdmissionStore(kv)
        val caller = CallerIdentity(101, "caller.a", "signer-a")
        val otherCaller = CallerIdentity(202, "caller.b", "signer-b")
        val firstWindow = ObservationAdmissionWindow(ownerGeneration = 1L, environmentRevision = 7L)

        repeat(ObservationAdmissionStore.MAX_REQUESTS_PER_WINDOW) { index ->
            store.admit(caller, "lease-a", firstWindow, "op-$index")
        }
        expectContractFailure(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE) {
            store.admit(caller, "lease-a", firstWindow, "overflow")
        }
        expectContractFailure(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE) {
            store.admit(caller, "lease-a", firstWindow, "op-0")
        }

        // Reconstructing the helper over the same durable store cannot reset
        // the window's cap.
        val afterOwnerRestart = ObservationAdmissionStore(kv)
        expectContractFailure(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE) {
            afterOwnerRestart.admit(caller, "lease-a", firstWindow, "after-restart")
        }

        // A new monotonic revision is a new observation window. Caller and lease
        // are also part of the bucket, so neither can consume the other's cap.
        store.admit(caller, "lease-a", firstWindow.copy(environmentRevision = 8L), "op-0")
        store.admit(otherCaller, "lease-a", firstWindow, "op-0")
        store.admit(caller, "lease-b", firstWindow, "op-0")

        assertEquals(0, store.count(caller, "lease-a", firstWindow))
        assertEquals(1, store.count(caller, "lease-a", firstWindow.copy(environmentRevision = 8L)))
        expectContractFailure(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE) {
            store.admit(caller, "lease-a", firstWindow, "late-old-window")
        }
    }
}

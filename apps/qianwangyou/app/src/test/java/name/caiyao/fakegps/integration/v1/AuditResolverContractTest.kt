package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AuditResolverContractTest {

    @Test
    fun `exact audit sequence resolves after a reopened store`() {
        val kv = InMemoryDurableKv()
        val first = DurableIntegrationAuditStore(kv, FakeMonotonicClock()).append("observe")

        assertEquals(first, DurableIntegrationAuditStore(kv, FakeMonotonicClock()).resolve(first.seq))
        assertNull(DurableIntegrationAuditStore(kv, FakeMonotonicClock()).resolve(first.seq + 1L))
    }

    @Test
    fun `advertised sequence with missing row is corruption not a silently shortened audit`() {
        val kv = InMemoryDurableKv().apply {
            write("integration.v1.audit", "__seq__", "2")
            write("integration.v1.audit", "evt:2", "not-a-valid-record")
        }
        val store = DurableIntegrationAuditStore(kv, FakeMonotonicClock())

        assertThrows(IllegalStateException::class.java) { store.all() }
    }
}

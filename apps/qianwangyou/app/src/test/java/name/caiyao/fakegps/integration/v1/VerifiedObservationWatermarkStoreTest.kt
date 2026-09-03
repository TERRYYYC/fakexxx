package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedObservationWatermarkStoreTest {

    private val samples = linkedMapOf("gps" to 1_200L, "network" to 1_100L)

    @Test
    fun `durable per lease watermark rejects sample replay after component rebuild`() {
        val kv = InMemoryDurableKv()
        assertTrue(VerifiedObservationWatermarkStore(kv).admit("lease-1", samples))

        val rebuilt = VerifiedObservationWatermarkStore(kv)
        assertFalse(rebuilt.admit("lease-1", samples))
        assertTrue(
            rebuilt.admit(
                "lease-1",
                linkedMapOf("gps" to 1_201L, "network" to 1_101L),
            ),
        )
    }

    @Test
    fun `every required source must advance beyond its own lease watermark`() {
        val store = VerifiedObservationWatermarkStore(InMemoryDurableKv())
        assertTrue(store.admit("lease-1", samples))

        assertFalse(
            "a new network sample cannot launder a replayed gps sample",
            store.admit(
                "lease-1",
                linkedMapOf("gps" to 1_200L, "network" to 1_101L),
            ),
        )
    }

    @Test
    fun `missing watermark after owner generation recovery fails closed`() {
        val rebuilt = VerifiedObservationWatermarkStore(InMemoryDurableKv())

        assertFalse(
            rebuilt.admit(
                leaseId = "legacy-or-recovered-lease",
                sourceElapsedRealtimeMs = samples,
                allowFirstUse = false,
            ),
        )
    }
}

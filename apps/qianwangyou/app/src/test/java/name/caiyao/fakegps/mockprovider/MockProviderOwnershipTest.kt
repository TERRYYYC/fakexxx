package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderOwnershipTest {

    @Test
    fun `cold startup proves inactive only by successfully removing real providers`() {
        val ownership = InProcessMockProviderOwnership()
        val gateway = RecordingMockProviderGateway()

        assertTrue(MockProviderStartupProjectionReconciler(ownership, gateway).reconcile())

        assertEquals(listOf("remove"), gateway.calls)
        assertEquals(
            MockProviderProjectionOwnership.ProvablyInactive,
            ownership.projectionOwnershipSnapshot(),
        )
    }

    @Test
    fun `cold startup removal failure remains uncertain and cannot baseline inactive`() {
        val ownership = InProcessMockProviderOwnership()
        val gateway = RecordingMockProviderGateway(failAt = "remove")

        assertFalse(MockProviderStartupProjectionReconciler(ownership, gateway).reconcile())

        assertEquals(listOf("remove"), gateway.calls)
        assertEquals(
            MockProviderProjectionOwnership.Uncertain,
            ownership.projectionOwnershipSnapshot(),
        )
    }

    @Test
    fun `startup reconciliation preserves a service projection already established in process`() {
        val ownership = InProcessMockProviderOwnership()
        val gateway = RecordingMockProviderGateway()
        val config = MockLocationConfig(50.4501, 30.5234)
        ownership.runAsService {
            ownership.markServiceProjectionActive(config)
        }

        assertTrue(MockProviderStartupProjectionReconciler(ownership, gateway).reconcile())

        assertTrue(gateway.calls.isEmpty())
        assertEquals(
            MockProviderProjectionOwnership.ServiceActive(config),
            ownership.projectionOwnershipSnapshot(),
        )
    }

    @Test
    fun `fresh process is recovery unknown until an owner explicitly reconciles projection`() {
        val ownership = InProcessMockProviderOwnership()

        assertEquals(
            MockProviderProjectionOwnership.RecoveryUnknown,
            ownership.projectionOwnershipSnapshot(),
        )

        val claim = ownership.claimIntegration()
        assertTrue(ownership.releaseIntegration(claim) {})
        assertEquals(
            MockProviderProjectionOwnership.ProvablyInactive,
            ownership.projectionOwnershipSnapshot(),
        )
    }

    @Test
    fun `service projection ownership is superseded and failed integration cleanup stays uncertain`() {
        val ownership = InProcessMockProviderOwnership()
        val reset = ownership.claimIntegration()
        ownership.releaseIntegration(reset) {}
        val config = MockLocationConfig(50.4501, 30.5234)
        assertTrue(ownership.runAsService {
            ownership.markServiceProjectionUncertain()
            ownership.markServiceProjectionActive(config)
        })
        assertEquals(
            MockProviderProjectionOwnership.ServiceActive(config),
            ownership.projectionOwnershipSnapshot(),
        )

        val claim = ownership.claimIntegration()
        try {
            assertEquals(
                MockProviderProjectionOwnership.IntegrationActive(claim),
                ownership.projectionOwnershipSnapshot(),
            )
            assertFalse(ownership.runAsService {
                ownership.markServiceProjectionInactive()
            })
            assertThrows(IllegalStateException::class.java) {
                ownership.releaseIntegration(claim) {
                    throw IllegalStateException("remove failed")
                }
            }
            assertEquals(
                MockProviderProjectionOwnership.Uncertain,
                ownership.projectionOwnershipSnapshot(),
            )

            assertTrue(ownership.releaseIntegration(claim) {})
            assertEquals(
                MockProviderProjectionOwnership.ProvablyInactive,
                ownership.projectionOwnershipSnapshot(),
            )
        } finally {
            ownership.releaseIntegration(claim) {}
        }
    }

    @Test
    fun `failed integration removal retains ownership until the same claim retries`() {
        val ownership = InProcessMockProviderOwnership()
        val claim = ownership.claimIntegration()
        try {
            assertThrows(IllegalStateException::class.java) {
                ownership.releaseIntegration(claim) {
                    throw IllegalStateException("provider removal failed")
                }
            }

            var staleServiceRan = false
            assertFalse(
                ownership.runAsService { staleServiceRan = true },
            )
            assertFalse(staleServiceRan)

            var retryRan = false
            assertTrue(
                ownership.releaseIntegration(claim) { retryRan = true },
            )
            assertTrue(retryRan)
            assertTrue(ownership.runAsService {})
        } finally {
            ownership.releaseIntegration(claim) {}
        }
    }
}

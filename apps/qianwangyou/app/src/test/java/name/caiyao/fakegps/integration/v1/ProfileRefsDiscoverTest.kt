package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileRefsDiscoverTest {

    @Test
    fun `discover returns the QWY owned ordered profile projection`() {
        val h = ProviderHarness.create()
        h.env.profileRefs = (1L..10L).map { "profile-$it" }
        h.pair()

        assertEquals(
            (1L..10L).map { "profile-$it" },
            h.handler.discover(ProviderHarness.AUTO_UID).profileRefs,
        )
    }

    @Test
    fun `profile projection helper rejects duplicate or invalid legacy ids`() {
        assertEquals(emptyList<String>(), ProfileRefProjection.fromLegacyIds(listOf(1L, 1L)))
        assertEquals(emptyList<String>(), ProfileRefProjection.fromLegacyIds(listOf(0L)))
    }
}

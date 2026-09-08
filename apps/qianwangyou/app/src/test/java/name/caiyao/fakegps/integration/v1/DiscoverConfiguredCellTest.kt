package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.81 CI-attestation projection (operator 拍板 2026-09-08): discover() must
 * carry the EFFECTIVE schedule item's configured cellular identity columns so
 * Auto can cross-attest "what CellRebel sees" against "what the hook injects",
 * and the three-state CI badge (注入/透传·真实/设备读数) becomes fully reachable.
 *
 * Killing mutation: handler discover() stops reading
 * environment.configuredCellSnapshot() — the first assertion goes red (the
 * snapshot answers the construction defaults, not the projection).
 *
 * ATTESTATION-ONLY red line: nothing here feeds the §6.4 trust predicates.
 */
class DiscoverConfiguredCellTest {

    private lateinit var harness: ProviderHarness

    @Before
    fun setUp() {
        harness = ProviderHarness.create()
        harness.pair()
    }

    @Test
    fun `discover projects the effective profile cellular columns`() {
        harness.env.configuredCell = ConfiguredCellSnapshot(
            ci = 289_001L, tac = 31461, pci = 210, mcc = "460", mnc = "0",
        )

        val snapshot = harness.handler.discover(ProviderHarness.AUTO_UID)

        assertEquals(289_001L, snapshot.configuredCellCi)
        assertEquals(31461, snapshot.configuredCellTac)
        assertEquals(210, snapshot.configuredCellPci)
        assertEquals("460", snapshot.configuredCellMcc)
        assertEquals("0", snapshot.configuredCellMnc)
        assertTrue("any configured column ⇒ cellularHookConfigured", snapshot.cellularHookConfigured)
    }

    @Test
    fun `an unevaluable projection fail-closes to no-configuration`() {
        // Null source (no current item / DB unreadable) — the wire must attest
        // ABSENCE honestly: all-null columns, hook flag false. Never zeros,
        // never a guessed row.
        harness.env.configuredCell = null

        val snapshot = harness.handler.discover(ProviderHarness.AUTO_UID)

        assertNull(snapshot.configuredCellCi)
        assertNull(snapshot.configuredCellTac)
        assertNull(snapshot.configuredCellPci)
        assertNull(snapshot.configuredCellMcc)
        assertNull(snapshot.configuredCellMnc)
        assertFalse(snapshot.cellularHookConfigured)
    }

    @Test
    fun `an empty cellular group projects full passthrough semantics`() {
        // A profile row exists but the operator filled no cellular column:
        // per-field null = passthrough for that field.
        harness.env.configuredCell = ConfiguredCellSnapshot(
            ci = null, tac = null, pci = null, mcc = null, mnc = null,
        )

        val snapshot = harness.handler.discover(ProviderHarness.AUTO_UID)

        assertNull(snapshot.configuredCellCi)
        assertFalse("an empty group is never reported as configured", snapshot.cellularHookConfigured)
    }

    @Test
    fun `cellularHookConfigured follows any single configured column`() {
        // The discriminator is the badge's fail-closed leg: INJECTED requires it.
        // Pure projection type — one configured column among nulls must flip it.
        fun hook(cell: ConfiguredCellSnapshot) = cell.cellularHookConfigured

        assertTrue(hook(ConfiguredCellSnapshot(289_001L, null, null, null, null)))
        assertTrue(hook(ConfiguredCellSnapshot(null, 31461, null, null, null)))
        assertTrue(hook(ConfiguredCellSnapshot(null, null, 210, null, null)))
        assertTrue(hook(ConfiguredCellSnapshot(null, null, null, "460", null)))
        assertTrue(hook(ConfiguredCellSnapshot(null, null, null, null, "0")))
        assertFalse(hook(ConfiguredCellSnapshot(null, null, null, null, null)))
    }
}

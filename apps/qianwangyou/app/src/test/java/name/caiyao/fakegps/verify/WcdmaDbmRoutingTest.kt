package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WCDMA exposes a single public `getDbm()`, but the profile has two columns for it.
 *
 * The hook resolves that ambiguity with `Snapshot.resolveWcdmaDbm(rscp, rssi)` — RSCP wins, RSSI is
 * the fallback. The observer must mirror the SAME priority, otherwise:
 *   - configuring only RSCP shows 读不到 for RSCP (nothing ever lands there), and
 *   - the reading is filed under RSSI, which can invent a mismatch against a configured RSSI.
 */
class WcdmaDbmRoutingTest {

    @Test
    fun `only RSCP configured routes the observed dbm to RSCP`() {
        assertEquals(
            "wcdma_rscp",
            DeviceObserver.wcdmaDbmColumn(configuredColumns = setOf("wcdma_rscp")),
        )
    }

    @Test
    fun `only RSSI configured routes the observed dbm to RSSI`() {
        assertEquals(
            "wcdma_rssi",
            DeviceObserver.wcdmaDbmColumn(configuredColumns = setOf("wcdma_rssi")),
        )
    }

    @Test
    fun `both configured follows the hook's RSCP-wins priority`() {
        // resolveWcdmaDbm returns rscp when non-null, so getDbm() reports the RSCP value. Filing it
        // under RSSI would compare the RSCP value against the configured RSSI and report a mismatch
        // for a hook doing exactly what it was told.
        assertEquals(
            "wcdma_rscp",
            DeviceObserver.wcdmaDbmColumn(configuredColumns = setOf("wcdma_rscp", "wcdma_rssi")),
        )
    }

    @Test
    fun `neither configured falls back to RSSI as the conventional dbm home`() {
        assertEquals("wcdma_rssi", DeviceObserver.wcdmaDbmColumn(configuredColumns = emptySet()))
    }
}

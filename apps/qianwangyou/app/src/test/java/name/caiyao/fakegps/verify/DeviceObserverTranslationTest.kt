package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceObserverTranslationTest {

    @Test
    fun asynchronousCellRefreshIsSkippedInsideThreadLocalBaselineExtraction() {
        assertEquals(true, DeviceObserver.shouldRequestFreshCellInfo(true, true, false))
        assertEquals(false, DeviceObserver.shouldRequestFreshCellInfo(true, true, true))
        assertEquals(false, DeviceObserver.shouldRequestFreshCellInfo(false, true, false))
        assertEquals(false, DeviceObserver.shouldRequestFreshCellInfo(true, false, false))
    }

    @Test
    fun unavailableSentinelIsMarkedButSameRealBaselineRemainsDetectable() {
        assertEquals(
            "--",
            DeviceObserver.observedScalar(
                "lte_ta",
                Int.MAX_VALUE,
                configuredColumns = setOf("lte_ta"),
                unavailableColumns = setOf("lte_ta"),
            ),
        )
        assertEquals(
            Int.MAX_VALUE.toString(),
            DeviceObserver.observedScalar(
                "lte_ta",
                Int.MAX_VALUE,
                configuredColumns = setOf("lte_ta"),
                unavailableColumns = emptySet(),
            ),
        )
        assertNull(
            DeviceObserver.observedScalar(
                "lte_ta",
                Int.MAX_VALUE,
                configuredColumns = emptySet(),
                unavailableColumns = emptySet(),
            ),
        )
    }

    @Test
    fun configuredConcreteFieldKeepsSentinelAsMismatchEvidence() {
        assertEquals(
            Int.MAX_VALUE.toString(),
            DeviceObserver.observedScalar(
                "lte_ta",
                Int.MAX_VALUE,
                configuredColumns = setOf("lte_ta"),
                unavailableColumns = emptySet(),
            ),
        )
    }
}

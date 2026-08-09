package name.caiyao.fakegps.config

import org.junit.Assert.assertEquals
import org.junit.Test

/** Runtime-side bounds for refresh values arriving through the cross-process payload. */
class HookRefreshRuntimePolicyTest {

    @Test
    fun `missing interval keeps the backward-compatible thirty-second default`() {
        assertEquals(30_000L, PublishPropagation.runtimeIntervalMs(null))
    }

    @Test
    fun `runtime clamps hostile values into the safe five-to-sixty-second range`() {
        assertEquals(5_000L, PublishPropagation.runtimeIntervalMs(-1))
        assertEquals(5_000L, PublishPropagation.runtimeIntervalMs(0))
        assertEquals(60_000L, PublishPropagation.runtimeIntervalMs(999))
    }

    @Test
    fun `runtime honours bounded values even when the UI does not offer them`() {
        assertEquals(5_000L, PublishPropagation.runtimeIntervalMs(5))
        assertEquals(15_000L, PublishPropagation.runtimeIntervalMs(15))
        assertEquals(60_000L, PublishPropagation.runtimeIntervalMs(60))
    }
}

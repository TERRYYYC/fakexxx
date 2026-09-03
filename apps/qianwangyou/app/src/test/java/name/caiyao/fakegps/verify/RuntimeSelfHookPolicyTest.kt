package name.caiyao.fakegps.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSelfHookPolicyTest {

    @Test
    fun `release main process remains unhooked`() {
        assertFalse(
            RuntimeSelfHookPolicy.shouldHook(
                allowNonProbeSelfHook = false,
                packageName = RuntimeSelfHookPolicy.MODULE_PACKAGE,
                processName = RuntimeSelfHookPolicy.MODULE_PACKAGE,
            ),
        )
    }

    @Test
    fun `release verification process is deliberately hook eligible`() {
        assertTrue(
            RuntimeSelfHookPolicy.shouldHook(
                allowNonProbeSelfHook = false,
                packageName = RuntimeSelfHookPolicy.MODULE_PACKAGE,
                processName = RuntimeSelfHookPolicy.PROBE_PROCESS,
            ),
        )
    }

    @Test
    fun `debug main process keeps the controlled self hook`() {
        assertTrue(
            RuntimeSelfHookPolicy.shouldHook(
                allowNonProbeSelfHook = true,
                packageName = RuntimeSelfHookPolicy.MODULE_PACKAGE,
                processName = RuntimeSelfHookPolicy.MODULE_PACKAGE,
            ),
        )
    }

    @Test
    fun `unrelated scoped packages remain eligible`() {
        assertTrue(
            RuntimeSelfHookPolicy.shouldHook(
                allowNonProbeSelfHook = false,
                packageName = "com.example.target",
                processName = "com.example.target:worker",
            ),
        )
    }

    @Test
    fun `probe sentinel defaults false without an installed Xposed replacement`() {
        assertFalse(RuntimeHookSentinel.isHookActive())
    }

    @Test
    fun `probe snapshot reload has no authority without an installed Xposed replacement`() {
        assertNull(RuntimeHookSentinel.reloadHookSnapshot("sha256:expected"))
    }
}

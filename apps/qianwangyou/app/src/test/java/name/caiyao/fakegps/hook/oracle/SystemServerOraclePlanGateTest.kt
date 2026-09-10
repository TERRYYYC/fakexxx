package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Three-way platform gate: unknown SDKs (34 and below, 37 and above) and every unattested build
 * resolve to null, which makes the system_server installer return before registering a single
 * hook. This is the executable form of the 285-rerun finding: an unported platform must stay
 * inert, never half-installed.
 */
class SystemServerOraclePlanGateTest {
    private val mi14Incremental = "OS3.0.303.0.WNCCNXM"

    @Test
    fun `api 34 and below never resolve a plan`() {
        assertNull(SystemServerOraclePlanGate.resolvePlan(34, "android/fingerprint", mi14Incremental))
        assertNull(SystemServerOraclePlanGate.resolvePlan(33, null, null))
        assertNull(SystemServerOraclePlanGate.resolvePlan(29, null, mi14Incremental))
    }

    @Test
    fun `api 35 stays inert while its fingerprint allowlist is empty`() {
        assertNull(
            "the pilot allowlist is intentionally empty; nothing attests an API-35 build yet",
            SystemServerOraclePlanGate.resolvePlan(35, "some/whole/fingerprint", "some.incremental"),
        )
        assertNull(SystemServerOraclePlanGate.resolvePlan(35, null, mi14Incremental))
    }

    @Test
    fun `api 36 resolves the a16 plan only for the attested mi14 build`() {
        assertSame(
            Android16OracleHookPlan.SURFACE,
            SystemServerOraclePlanGate.resolvePlan(36, "Xiaomi/msi14/mi14:16/anything", mi14Incremental),
        )
        assertNull(
            "same OEM family on an unattested OTA stays inert",
            SystemServerOraclePlanGate.resolvePlan(36, "Xiaomi/msi14/mi14:16/anything", "BP2A.250605.031"),
        )
        assertNull(SystemServerOraclePlanGate.resolvePlan(36, null, null))
    }

    @Test
    fun `api 37 and above never resolve a plan`() {
        assertNull(SystemServerOraclePlanGate.resolvePlan(37, null, mi14Incremental))
        assertNull(SystemServerOraclePlanGate.resolvePlan(38, "future/fingerprint", "FUTURE.BUILD"))
    }
}

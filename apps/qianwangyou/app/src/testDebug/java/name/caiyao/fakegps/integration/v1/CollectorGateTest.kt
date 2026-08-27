package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10DBG-COLLECTOR-V1 gate/arm logic tests (qwy side).
 *
 * The exact-window protocol is only as good as its parser and predicate: a
 * gate that mis-parses fires at the WRONG moment and the §5B/§5C evidence
 * collected through it is silently about a different window. These pin the
 * frozen vocabulary end to end.
 */
class CollectorGateTest {

    private fun snapshot(state: String?, caller: String? = "com.example.cellrebelauto") =
        QwyLeaseSnapshot(
            currentLeaseId = state?.let { "lease-1" },
            leaseState = state,
            callerApplicationId = caller,
        )

    @Test
    fun gateTokensParseExactly() {
        assertEquals(FaultGate.LeaseActive, FaultGate.parse("lease_active"))
        assertEquals(FaultGate.LeaseReleasing, FaultGate.parse("lease_releasing"))
        assertEquals(FaultGate.LeaseAcquiring, FaultGate.parse("lease_acquiring"))
        // Tolerates stray whitespace only — every other string is refused, so a
        // typo'd gate refuses to arm instead of arming on a near-miss token.
        assertEquals(FaultGate.LeaseActive, FaultGate.parse(" lease_active "))
        assertNull("near-miss token must not parse", FaultGate.parse("lease-active"))
        assertNull("unknown token must not parse", FaultGate.parse("lease_revoked"))
        assertNull("empty token must not parse", FaultGate.parse(""))
    }

    @Test
    fun gatesMatchExactlyTheirCommittedState() {
        assertTrue(FaultGate.LeaseActive.isSatisfiedBy(snapshot("ACTIVE")))
        assertFalse("ACTIVE must not satisfy lease_releasing", FaultGate.LeaseReleasing.isSatisfiedBy(snapshot("ACTIVE")))
        assertFalse("RELEASE_INCOMPLETE must not satisfy lease_active — that is a " +
            "post-injection readback state, not an in-flight window",
            FaultGate.LeaseActive.isSatisfiedBy(snapshot("RELEASE_INCOMPLETE")))
        assertTrue(FaultGate.LeaseReleasing.isSatisfiedBy(snapshot("RELEASING")))
        assertTrue(FaultGate.LeaseAcquiring.isSatisfiedBy(snapshot("ACQUIRING")))
        assertFalse("no lease at all satisfies nothing", FaultGate.LeaseActive.isSatisfiedBy(snapshot(null)))
    }

    @Test
    fun callerScopeNarrowsWithoutWeakeningTheGate() {
        val scoped = CallerScope("com.example.cellrebelauto")
        assertTrue(scoped.matches(snapshot("ACTIVE", caller = "com.example.cellrebelauto")))
        assertFalse("another caller's lease must not open a scoped gate",
            scoped.matches(snapshot("ACTIVE", caller = "someone.else")))
        assertTrue("null scope = any caller (kill windows are not caller-specific)",
            CallerScope(null).matches(snapshot("ACTIVE", caller = "someone.else")))
    }

    @Test
    fun armSpecValidationRefusesWhatCannotFire() {
        val gate = FaultGate.LeaseActive
        val scope = CallerScope("com.example.cellrebelauto")

        // A revoke without both halves of the principal is a different decision — refuse.
        assertTrue(ArmSpec.validate(ArmAction.REVOKE_CALLER, gate, scope, null, 200, 60_000) != null)
        val noCaller = CallerScope(null)
        assertTrue(ArmSpec.validate(ArmAction.REVOKE_CALLER, gate, noCaller, "abc", 200, 60_000) != null)

        // Unknown action / unknown gate / absurd poll / out-of-range timeout.
        assertTrue(ArmSpec.validate(null, gate, scope, null, 200, 60_000) != null)
        assertTrue(ArmSpec.validate(ArmAction.SELF_KILL, null, scope, null, 200, 60_000) != null)
        assertTrue(ArmSpec.validate(ArmAction.SELF_KILL, gate, scope, null, 10, 60_000) != null)
        assertTrue(ArmSpec.validate(ArmAction.SELF_KILL, gate, scope, null, 200, 10) != null)

        // A clean self-kill arm validates.
        assertNull(ArmSpec.validate(ArmAction.SELF_KILL, gate, scope, null, 200, 60_000))
        assertNull(ArmSpec.validate(ArmAction.REVOKE_CALLER, gate, scope, "sha256:aa", 200, 60_000))
    }

    @Test
    fun armRecordCodecSurvivesRoundTripWithNulls() {
        val line = ArmRecordCodec.ArmLine(
            kind = "ARMED", action = "self_kill", gate = "lease_active",
            caller = null, atMs = 1_778_000_000_000, detail = null,
        )
        assertEquals(line, ArmRecordCodec.decode(ArmRecordCodec.encode(line)))

        val full = ArmRecordCodec.ArmLine(
            kind = "OUTCOME", action = "revoke_caller", gate = "lease_active",
            caller = "com.example.cellrebelauto", atMs = 42,
            detail = "stillActive=false revokedAudited=true lease=abc state=ACTIVE",
        )
        assertEquals(full, ArmRecordCodec.decode(ArmRecordCodec.encode(full)))

        // Free strings survive: detail is operator text, not an enum.
        val weird = full.copy(detail = "lease-1\u001F%0A state=ACTIVE") // unit separator + escaped newline
        assertEquals(weird, ArmRecordCodec.decode(ArmRecordCodec.encode(weird)))
    }
}

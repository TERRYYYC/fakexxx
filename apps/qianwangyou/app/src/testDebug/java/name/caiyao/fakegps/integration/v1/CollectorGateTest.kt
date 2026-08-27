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
            callerSignerDigest = "deadbeef",
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

    /**
     * R2 (gpt55 P1-3): lease ownership is the FULL principal — a same-package
     * rotated-signer lease is a DIFFERENT principal's transaction (§6.5). A
     * revoke arm scoped (appId, signerA) must NOT open on signerB's lease.
     */
    @Test
    fun callerScopeMustMatchBothHalvesOfThePrincipal() {
        val fullScope = CallerScope("com.example.cellrebelauto", "signerA")
        val rotatedSignerLease = snapshot("ACTIVE", caller = "com.example.cellrebelauto")
            .copy(callerSignerDigest = "signerB")
        assertFalse(
            "a rotated-signer (same package) lease must not open a full-principal gate — " +
                "that is exact-window for the WRONG in-flight transaction",
            fullScope.matches(rotatedSignerLease),
        )
        assertTrue(fullScope.matches(snapshot("ACTIVE").copy(callerSignerDigest = "signerA")))
        // appId-only scopes still match any signer (self_kill windows).
        assertTrue(CallerScope("com.example.cellrebelauto", null).matches(rotatedSignerLease))
    }

    /**
     * R2 (gpt55 P1-2 companion): the revoke verdict must be the principal's
     * before→after transition — never broad post-conditions.
     */
    @Test
    fun revokeProofKillsTheFalseGreens() {
        // The real transition: active before → inactive after + audit row.
        assertEquals(
            QwyRevokeProof.Verdict.PROVEN,
            QwyRevokeProof.verdict(beforeActive = true, afterActive = false, revokeAudited = true),
        )
        // Typo'd / never-paired / already-revoked principal: nothing WAS active.
        assertEquals(
            "absence-before must NOT prove — the audit row exists either way",
            QwyRevokeProof.Verdict.NOT_PROVEN_NOTHING_ACTIVE,
            QwyRevokeProof.verdict(beforeActive = false, afterActive = false, revokeAudited = true),
        )
        assertEquals(
            QwyRevokeProof.Verdict.NOT_PROVEN_NOTHING_ACTIVE,
            QwyRevokeProof.verdict(beforeActive = null, afterActive = null, revokeAudited = null),
        )
        // Still active after the fire — investigate, do not green.
        assertEquals(
            QwyRevokeProof.Verdict.NOT_PROVEN_STILL_ACTIVE,
            QwyRevokeProof.verdict(beforeActive = true, afterActive = true, revokeAudited = true),
        )
        // Inactive but no audit row — investigate.
        assertEquals(
            QwyRevokeProof.Verdict.NOT_PROVEN_NO_AUDIT,
            QwyRevokeProof.verdict(beforeActive = true, afterActive = false, revokeAudited = false),
        )
        // After-state unreadable.
        assertEquals(
            QwyRevokeProof.Verdict.UNKNOWN,
            QwyRevokeProof.verdict(beforeActive = true, afterActive = null, revokeAudited = true),
        )
    }

    @Test
    fun armSpecValidationRefusesWhatCannotFire() {
        val gate = FaultGate.LeaseActive
        val scope = CallerScope("com.example.cellrebelauto", "sha256:aa")
        val noSigner = CallerScope("com.example.cellrebelauto", null)
        val noCaller = CallerScope(null, "sha256:aa")

        // A revoke without both halves of the principal is a different decision — refuse.
        assertTrue("signer-less scope must be refused for revoke_caller",
            ArmSpec.validate(ArmAction.REVOKE_CALLER, gate, noSigner, 200, 60_000) != null)
        assertTrue("caller-less scope must be refused for revoke_caller",
            ArmSpec.validate(ArmAction.REVOKE_CALLER, gate, noCaller, 200, 60_000) != null)

        // Unknown action / unknown gate / absurd poll / out-of-range timeout.
        assertTrue(ArmSpec.validate(null, gate, scope, 200, 60_000) != null)
        assertTrue(ArmSpec.validate(ArmAction.SELF_KILL, null, scope, 200, 60_000) != null)
        assertTrue(ArmSpec.validate(ArmAction.SELF_KILL, gate, scope, 10, 60_000) != null)
        assertTrue(ArmSpec.validate(ArmAction.SELF_KILL, gate, scope, 200, 10) != null)

        // Clean arms validate.
        assertNull(ArmSpec.validate(ArmAction.SELF_KILL, gate, scope, 200, 60_000))
        assertNull(ArmSpec.validate(ArmAction.REVOKE_CALLER, gate, scope, 200, 60_000))
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

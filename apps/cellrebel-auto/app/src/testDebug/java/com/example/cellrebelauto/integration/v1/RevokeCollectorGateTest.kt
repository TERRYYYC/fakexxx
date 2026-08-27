package com.example.cellrebelauto.integration.v1

import com.example.cellrebelauto.automation.aplus.AttemptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull

/**
 * P10DBG-COLLECTOR-V1 gate/arm logic tests (Auto side).
 *
 * Same discipline as the qwy CollectorGateTest: the exact-window protocol is
 * only as good as its parser and predicate. A gate that mis-parses fires at
 * the wrong moment, and §5C evidence collected through it silently describes
 * a different window.
 */
class RevokeCollectorGateTest {

    private fun snapshot(
        running: Int = 0,
        states: List<String> = emptyList(),
        trusted: Int = 0,
    ) = AutoRunSnapshot(runningAttemptCount = running, runningAplusStates = states, trustedCountTotal = trusted)

    @Test
    fun runActiveGateMatchesOnlyLiveAttempts() {
        val gate = AutoGate.parse("run_active")
        assertNotNull(gate)
        assertTrue(gate!!.isSatisfiedBy(snapshot(running = 1)))
        assertFalse("no running attempt must not open run_active", gate.isSatisfiedBy(snapshot(running = 0)))
    }

    @Test
    fun attemptStateGateBindsToTheRealStateMachineEnum() {
        val gate = AutoGate.parse("attempt_state:ENV_APPLIED")
        assertNotNull(gate)
        assertTrue(gate!!.isSatisfiedBy(snapshot(running = 1, states = listOf("ENV_APPLIED"))))
        assertFalse("a different durable aplusState must not open the gate",
            gate.isSatisfiedBy(snapshot(running = 1, states = listOf("RELEASE_PENDING"))))

        // Every REAL §8.1 state parses; casing is normalized for adb ergonomics.
        AttemptState.entries.forEach { state ->
            assertNotNull("state ${state.name} must parse", AutoGate.parse("attempt_state:${state.name}"))
        }
        assertNotNull(AutoGate.parse("attempt_state:release_pending"))

        // A token no state machine can ever hold must REFUSE to arm — an
        // arm-and-never-fire gate would look armed while proving nothing.
        assertNull(AutoGate.parse("attempt_state:NOT_A_STATE"))
        assertNull(AutoGate.parse("attempt_state:"))
    }

    @Test
    fun trustedCountGateFiresAtOrAboveN() {
        val gate = AutoGate.parse("trusted_count:3")
        assertNotNull(gate)
        assertTrue(gate!!.isSatisfiedBy(snapshot(trusted = 3)))
        assertTrue("fires at >= N (a burst may commit several between polls)",
            gate.isSatisfiedBy(snapshot(trusted = 5)))
        assertFalse(gate.isSatisfiedBy(snapshot(trusted = 2)))
        assertNull(AutoGate.parse("trusted_count:-1"))
        assertNull(AutoGate.parse("trusted_count:abc"))
        assertNull(AutoGate.parse("trusted_count:"))
    }

    @Test
    fun unknownTokensRefuseToParse() {
        assertNull(AutoGate.parse(""))
        assertNull(AutoGate.parse("run"))
        assertNull(AutoGate.parse("lease_active")) // qwy vocabulary must not parse here
    }

    @Test
    fun armSpecValidationRefusesWhatCannotFire() {
        val gate = AutoGate.parse("run_active")!!

        // revoke_provider without both halves of the principal — refused.
        assertTrue(AutoArmSpec.validate(AutoArmAction.REVOKE_PROVIDER, gate, "run_active",
            null, "sha256:aa", 200, 60_000) != null)
        assertTrue(AutoArmSpec.validate(AutoArmAction.REVOKE_PROVIDER, gate, "run_active",
            "name.caiyao.fakegps.bench", null, 200, 60_000) != null)

        // Unknown action / gate / poll / timeout — refused.
        assertTrue(AutoArmSpec.validate(null, gate, "run_active", null, null, 200, 60_000) != null)
        assertTrue(AutoArmSpec.validate(AutoArmAction.SELF_KILL, null, "bogus", null, null, 200, 60_000) != null)
        assertTrue(AutoArmSpec.validate(AutoArmAction.SELF_KILL, gate, "run_active", null, null, 10, 60_000) != null)
        assertTrue(AutoArmSpec.validate(AutoArmAction.SELF_KILL, gate, "run_active", null, null, 200, 10) != null)

        // Clean arms validate.
        assertNull(AutoArmSpec.validate(AutoArmAction.SELF_KILL, gate, "run_active", null, null, 200, 60_000))
        assertNull(AutoArmSpec.validate(AutoArmAction.REVOKE_PROVIDER, gate, "run_active",
            "name.caiyao.fakegps.bench", "sha256:aa", 200, 60_000))
    }

    @Test
    fun armRecordCodecSurvivesRoundTripWithNulls() {
        val line = AutoArmRecordCodec.ArmLine(
            kind = "ARMED", action = "revoke_provider", gate = "attempt_state:ENV_APPLIED",
            target = null, atMs = 1_778_000_000_000, detail = null,
        )
        assertEquals(line, AutoArmRecordCodec.decode(AutoArmRecordCodec.encode(line)))

        val full = line.copy(
            kind = "OUTCOME", target = "name.caiyao.fakegps.bench",
            detail = "anyActive=false running=1 trusted=3 lease\u001Fsep",
        )
        val decoded = AutoArmRecordCodec.decode(AutoArmRecordCodec.encode(full))
        assertNotNull(decoded)
        // Format-breaking characters are SANITIZED (space), not escaped — the
        // codec's contract — so the roundtrip equals the sanitized original.
        assertEquals(
            full.copy(detail = "anyActive=false running=1 trusted=3 lease sep"),
            decoded,
        )
    }
}

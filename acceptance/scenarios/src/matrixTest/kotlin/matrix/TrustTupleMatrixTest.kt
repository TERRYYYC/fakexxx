package matrix

import io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge
import io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.Refusal
import io.github.terryyyc.fakexxx.acceptance.scenarios.TrustTupleJudge.Verdict
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §10 `tuple` rows M-TU-01..07 (lane `sol-blackbox`, owner Fable5, §10.1
 * frozen entry acceptance/scenarios/src/matrixTest/kotlin/matrix/
 * TrustTupleMatrixTest.kt).
 *
 * Every row pins ONE leg of the consumer trust predicate to fail-closed with a
 * TYPED reason. A baseline fully-proven tuple is Counted — asserted once here
 * so each row's refusal is attributable to exactly the leg it corrupts, not to
 * an always-refusing judge.
 */
class TrustTupleMatrixTest {

    /** A tuple with EVERY leg proven — the only shape that may count. */
    private fun provenTuple() = TrustTupleJudge.AttemptEvidence(
        deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
        verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        isMock = true,
        scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
        continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
        continuitySinceElapsedMs = 1_000L,
        preObservedAtElapsedMs = 2_000L,
        postObservedAtElapsedMs = 10_000L,
        cellRebelCompletedAtElapsedMs = 9_000L,
        evidenceRefs = listOf("qwy:audit:1"),
    )

    private fun assertRefused(evidence: TrustTupleJudge.AttemptEvidence, reason: Refusal) {
        assertEquals(Verdict.NotCounted(reason), TrustTupleJudge.judge(evidence))
    }

    /** Baseline sanity: the fully-proven tuple counts — makes each row's refusal leg-attributable. */
    @Test
    fun baseline_fullyProvenTuple_isCounted() {
        assertEquals(Verdict.Counted, TrustTupleJudge.judge(provenTuple()))
    }

    /** M-TU-01: HOOK delivery + SYSTEM_MOCK_INDEPENDENTLY_VERIFIED → fail-closed, not counted (§6; INV-27). */
    @Test
    fun M_TU_01() {
        assertRefused(
            provenTuple().copy(deliveryModeWire = DeliveryModeV1.HOOK.wire),
            Refusal.DELIVERY_NOT_SYSTEM_MOCK,
        )
    }

    /** M-TU-02: isMock=false (or null) + VERIFIED → fail-closed, not counted (INV-27). */
    @Test
    fun M_TU_02() {
        assertRefused(provenTuple().copy(isMock = false), Refusal.NOT_OBSERVED_AS_MOCK)
        assertRefused(provenTuple().copy(isMock = null), Refusal.NOT_OBSERVED_AS_MOCK)
    }

    /** M-TU-03: schedule DENIED / WAIT_UNTIL + VERIFIED → fail-closed, not counted (wire 17 family; INV-27). */
    @Test
    fun M_TU_03() {
        assertRefused(
            provenTuple().copy(scheduleDecisionWire = ScheduleDecisionV1.DENIED.wire),
            Refusal.SCHEDULE_NOT_ALLOWED,
        )
        assertRefused(
            provenTuple().copy(scheduleDecisionWire = ScheduleDecisionV1.WAIT_UNTIL.wire),
            Refusal.SCHEDULE_NOT_ALLOWED,
        )
    }

    /** M-TU-04: coverage=FULL + continuitySince=null is self-contradictory → fail-closed (INV-08/09/27). */
    @Test
    fun M_TU_04() {
        assertRefused(
            provenTuple().copy(continuitySinceElapsedMs = null),
            Refusal.CONTINUITY_WINDOW_UNPROVEN,
        )
    }

    /** M-TU-05: continuitySince AFTER the pre observation → window does not cover it, not counted (INV-08/27). */
    @Test
    fun M_TU_05() {
        assertRefused(
            provenTuple().copy(continuitySinceElapsedMs = 3_000L /* pre observed at 2_000 */),
            Refusal.CONTINUITY_STARTED_AFTER_PRE,
        )
    }

    /** M-TU-06: post observation earlier than CellRebel completion → post leg unproven, not counted (INV-07/27). */
    @Test
    fun M_TU_06() {
        assertRefused(
            provenTuple().copy(postObservedAtElapsedMs = 8_000L /* completed at 9_000 */),
            Refusal.POST_OBSERVATION_TOO_EARLY,
        )
    }

    /** M-TU-07: evidenceRefs empty + VERIFIED → nothing reviewable, not counted (INV-18/27). */
    @Test
    fun M_TU_07() {
        assertRefused(
            provenTuple().copy(evidenceRefs = emptyList()),
            Refusal.NO_REVIEWABLE_EVIDENCE,
        )
    }
}

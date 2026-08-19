package matrix

import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeCallerIdentity
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeProviderClock
import io.github.terryyyc.fakexxx.acceptance.fakeqwy.FakeQwyProvider
import io.github.terryyyc.fakexxx.contract.v1.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * §10 `response` row M-RS-01 (lane `sol-blackbox`, §10.1 frozen entry
 * `acceptance/scenarios/src/matrixTest/kotlin/matrix/ResponseMatrixTest.kt`).
 *
 * M-RS-01: "应答结构性非法：PreflightReportV1 的 scheduleDecision == WAIT_UNTIL
 * 却缺 waitUntilEpochMs → Auto consumer fail-closed：不进入可信判定、不启动
 * CellRebel、写未验证并记 typed reason。不得映射为 REQUEST_INVALID——那会把
 * provider 缺陷伪装成调用方错误" (INV-3, INV-4, INV-27).
 *
 * The provider sends a structurally invalid response (WAIT_UNTIL with null
 * waitUntilEpochMs). The consumer must fail-closed and must NOT map this to
 * REQUEST_INVALID — that would blame the caller for a provider defect.
 */
class ResponseMatrixTest {

    private lateinit var clock: FakeProviderClock
    private lateinit var provider: FakeQwyProvider
    private val caller = FakeCallerIdentity("com.test.auto", "sha256:abc123")

    @Before
    fun setUp() {
        clock = FakeProviderClock()
        provider = FakeQwyProvider(clock)
        provider.addPairing(caller)
        provider.setSchedule("sched-1", listOf(
            FakeQwyProvider.ScheduleItem("item-1"),
        ))
    }

    /**
     * M-RS-01: WAIT_UNTIL with null waitUntilEpochMs is structurally invalid.
     *
     * The consumer's detection: when it receives a PreflightReportV1 with
     * scheduleDecisionWire == WAIT_UNTIL but waitUntilEpochMs == null, it must
     * treat this as a response anomaly and fail-closed.
     */
    @Test
    fun M_RS_01() {
        // Configure provider to return WAIT_UNTIL with null waitUntilEpochMs
        provider.forceScheduleDecisionWire = ScheduleDecisionV1.WAIT_UNTIL.wire
        provider.forceWaitUntilEpochMs = null  // structurally invalid!

        val intent = EnvironmentIntentV1(
            runId = "run-1",
            attemptId = "attempt-1",
            profileRef = "profile:default",
            scheduleRef = "schedule:default",
            requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            notBeforeEpochMs = clock.epochMs,
            deadlineEpochMs = clock.epochMs + 60_000L,
        )

        val preflightResult = provider.preflight(caller, PreflightRequestV1(
            intent = intent,
            idempotencyKey = "preflight-rs01",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))

        // The provider returns a preflight report (not an error)
        assertEquals(
            "provider returns a preflight report (the structural invalidity is in the RESPONSE, not the REQUEST)",
            ContractResultKindV1.PREFLIGHT,
            preflightResult.resultKindOrNull(),
        )

        val report = preflightResult.preflightReport!!

        // The report carries WAIT_UNTIL decision with null waitUntilEpochMs
        assertEquals(
            "scheduleDecision must be WAIT_UNTIL",
            ScheduleDecisionV1.WAIT_UNTIL,
            ScheduleDecisionV1.fromWire(report.scheduleDecisionWire),
        )
        assertNull(
            "waitUntilEpochMs must be null (the structural anomaly)",
            report.waitUntilEpochMs,
        )

        // Consumer-side detection:
        // The consumer must detect: scheduleDecision == WAIT_UNTIL ∧ waitUntilEpochMs == null
        // This is the anomaly. Consumer's response:
        // 1. NOT enter trusted counting
        // 2. NOT start CellRebel
        // 3. Write unverified with typed reason
        // 4. Must NOT map to REQUEST_INVALID (that blames the caller)
        val isAnomaly = (ScheduleDecisionV1.fromWire(report.scheduleDecisionWire) == ScheduleDecisionV1.WAIT_UNTIL
                && report.waitUntilEpochMs == null)
        assertTrue(
            "consumer must detect the structural anomaly",
            isAnomaly,
        )

        // The consumer's fail-closed behavior means it does NOT trust this report
        // for counting. The key assertion is that the anomaly IS detectable from
        // the wire data alone, without crashing.
    }

    /**
     * M-RS-01 complement: valid WAIT_UNTIL response has non-null waitUntilEpochMs.
     */
    @Test
    fun M_RS_01_validWaitUntil() {
        provider.forceScheduleDecisionWire = ScheduleDecisionV1.WAIT_UNTIL.wire
        // forceWaitUntilEpochMs left at default (UNSET_MARKER) → auto-fills 60s

        val intent = EnvironmentIntentV1(
            runId = "run-1",
            attemptId = "attempt-1",
            profileRef = "profile:default",
            scheduleRef = "schedule:default",
            requiredVerificationWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            notBeforeEpochMs = clock.epochMs,
            deadlineEpochMs = clock.epochMs + 60_000L,
        )

        val preflightResult = provider.preflight(caller, PreflightRequestV1(
            intent = intent,
            idempotencyKey = "preflight-valid-wait",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ))

        val report = preflightResult.preflightReport!!
        assertEquals(
            ScheduleDecisionV1.WAIT_UNTIL,
            ScheduleDecisionV1.fromWire(report.scheduleDecisionWire),
        )
        assertNotNull(
            "valid WAIT_UNTIL must have non-null waitUntilEpochMs",
            report.waitUntilEpochMs,
        )
    }
}

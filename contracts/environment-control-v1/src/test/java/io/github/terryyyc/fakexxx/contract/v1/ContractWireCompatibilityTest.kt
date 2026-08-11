package io.github.terryyyc.fakexxx.contract.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-code stability, fail-closed decoding and version skew. Spec §6.2, §6.7.
 *
 * These are schema mutation guards: they fail if a published v1 wire code is
 * renumbered, reused or dropped, which is the class of change §6.1 forbids
 * outright ("v1 已发布的方法和字段语义不可原地改写").
 */
class ContractWireCompatibilityTest {

    // ---------------------------------------------------------------- stability

    @Test
    fun `VerificationLevelV1 wire codes are frozen`() {
        assertEquals(1, VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire)
        assertEquals(2, VerificationLevelV1.HOOK_UNVERIFIED.wire)
        assertEquals(3, VerificationLevelV1.NONE.wire)
        assertEquals(3, VerificationLevelV1.entries.size)
    }

    @Test
    fun `ContinuityCoverageV1 DeliveryModeV1 and ScheduleDecisionV1 wire codes are frozen`() {
        assertEquals(1, ContinuityCoverageV1.FULL.wire)
        assertEquals(2, ContinuityCoverageV1.PARTIAL.wire)
        assertEquals(3, ContinuityCoverageV1.NONE.wire)

        assertEquals(1, DeliveryModeV1.SYSTEM_MOCK.wire)
        assertEquals(2, DeliveryModeV1.HOOK.wire)

        assertEquals(1, ScheduleDecisionV1.ALLOWED_NOW.wire)
        assertEquals(2, ScheduleDecisionV1.WAIT_UNTIL.wire)
        assertEquals(3, ScheduleDecisionV1.DENIED.wire)
    }

    @Test
    fun `ContractErrorCodeV1 wire values are frozen, and no code is added silently`() {
        // This test used to be called "frozen and complete" and asserted that the
        // list below WAS the §6.3.2 error set. It could not have known that: a
        // unit test cannot read the canonical document. While the module was
        // frozen against a superseded §6, this test was green -- so a test whose
        // name said "complete" was actively certifying the superseded set. That
        // is worse than having no test, because it reads as proof.
        //
        // What a hardcoded list here CAN prove is narrower and still worth having:
        // that no wire value silently changed, and that nobody added or removed a
        // constant without touching this list. Completeness against canonical
        // §6.3.3 is proven by check-contract-v1.sh section 5, which compares
        // (name, code) SETS across spec, Kotlin and compatibility.yaml in both
        // directions -- the only place that can see all three carriers.
        val frozen = mapOf(
            ContractErrorCodeV1.NOT_PAIRED to 1,
            ContractErrorCodeV1.CALLER_NOT_ALLOWED to 2,
            ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL to 3,
            ContractErrorCodeV1.CAPABILITY_UNAVAILABLE to 4,
            ContractErrorCodeV1.SCHEDULE_DENIED to 5,
            ContractErrorCodeV1.CONTINUITY_NOT_FULL to 6,
            ContractErrorCodeV1.LEASE_CONFLICT to 7,
            ContractErrorCodeV1.STALE_LEASE to 8,
            ContractErrorCodeV1.ENVIRONMENT_DRIFT to 9,
            ContractErrorCodeV1.RELEASE_INCOMPLETE to 10,
            ContractErrorCodeV1.INTERNAL_FAILURE to 11,
            ContractErrorCodeV1.IDEMPOTENCY_CONFLICT to 12,
            ContractErrorCodeV1.REQUEST_INVALID to 13,
            ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH to 14,
            ContractErrorCodeV1.SCHEDULE_VERSION_STALE to 15,
            ContractErrorCodeV1.SCHEDULE_EXHAUSTED to 16,
        )
        frozen.forEach { (code, wire) -> assertEquals("$code", wire, code.wire) }
        assertEquals(
            "a constant was added or removed without updating this tripwire; " +
                "canonical completeness is check-contract-v1.sh section 5, not this test",
            frozen.keys,
            ContractErrorCodeV1.entries.toSet(),
        )
    }

    /**
     * An unknown advance outcome must never be optimistically read as ADVANCED.
     *
     * Reading it that way would let Auto believe the schedule moved when it has
     * no idea what happened, and then attribute results to the wrong item -- the
     * wrong-environment attribution §6.7.5 exists to prevent, arriving through
     * the outcome field instead of through the observation.
     */
    @Test
    fun `unknown advance outcome fails closed rather than reading as advanced`() {
        assertTrue(AdvanceOutcomeV1.advancedOrFailClosed(AdvanceOutcomeV1.ADVANCED.wire))
        assertFalse(AdvanceOutcomeV1.advancedOrFailClosed(AdvanceOutcomeV1.EXHAUSTED.wire))
        val unknown = AdvanceOutcomeV1.entries.maxOf { it.wire } + 1
        assertFalse("unknown outcome must not be treated as advanced",
            AdvanceOutcomeV1.advancedOrFailClosed(unknown))
        assertFalse(AdvanceOutcomeV1.advancedOrFailClosed(0))
        assertFalse(AdvanceOutcomeV1.advancedOrFailClosed(-1))
    }

    /** A reused wire code would make two distinct meanings indistinguishable. */
    @Test
    fun `wire codes are unique within each enum`() {
        fun <T> assertUnique(name: String, wires: List<Int>) {
            assertEquals("$name has duplicate wire codes: $wires", wires.size, wires.toSet().size)
        }
        assertUnique<VerificationLevelV1>("VerificationLevelV1", VerificationLevelV1.entries.map { it.wire })
        assertUnique<ContinuityCoverageV1>("ContinuityCoverageV1", ContinuityCoverageV1.entries.map { it.wire })
        assertUnique<DeliveryModeV1>("DeliveryModeV1", DeliveryModeV1.entries.map { it.wire })
        assertUnique<ScheduleDecisionV1>("ScheduleDecisionV1", ScheduleDecisionV1.entries.map { it.wire })
        assertUnique<ContractErrorCodeV1>("ContractErrorCodeV1", ContractErrorCodeV1.entries.map { it.wire })
        assertUnique<AdvanceOutcomeV1>("AdvanceOutcomeV1", AdvanceOutcomeV1.entries.map { it.wire })
    }

    // -------------------------------------------------------------- fail-closed

    @Test
    fun `unknown wire codes decode to null on every enum`() {
        // The probe for "just past the top" is DERIVED, never a literal.
        //
        // This test used to assert ContractErrorCodeV1.fromWire(12) == null. 12 was
        // unknown when the line was written, so the assertion looked permanent. The
        // moment IDEMPOTENCY_CONFLICT(12) landed, this file became statically
        // self-contradictory: this test demanded 12 decode to null while
        // `known wire codes decode back to the same constant` demanded every entry
        // round-trip. Both cannot hold, and CI reported it as one failing test
        // rather than as what it was -- a stale literal.
        //
        // A hardcoded "this number is unknown" is a claim about the FUTURE of the
        // enum, which a literal cannot keep. Deriving it from entries keeps the
        // assertion true by construction however many codes get appended.
        fun probes(topWire: Int) = listOf(0, -1, topWire + 1, Int.MAX_VALUE, Int.MIN_VALUE)

        probes(VerificationLevelV1.entries.maxOf { it.wire }).forEach {
            assertNull("VerificationLevelV1.fromWire($it)", VerificationLevelV1.fromWire(it))
        }
        probes(ContinuityCoverageV1.entries.maxOf { it.wire }).forEach {
            assertNull("ContinuityCoverageV1.fromWire($it)", ContinuityCoverageV1.fromWire(it))
        }
        probes(DeliveryModeV1.entries.maxOf { it.wire }).forEach {
            assertNull("DeliveryModeV1.fromWire($it)", DeliveryModeV1.fromWire(it))
        }
        probes(ScheduleDecisionV1.entries.maxOf { it.wire }).forEach {
            assertNull("ScheduleDecisionV1.fromWire($it)", ScheduleDecisionV1.fromWire(it))
        }
        probes(ContractErrorCodeV1.entries.maxOf { it.wire }).forEach {
            assertNull("ContractErrorCodeV1.fromWire($it)", ContractErrorCodeV1.fromWire(it))
        }
        probes(AdvanceOutcomeV1.entries.maxOf { it.wire }).forEach {
            assertNull("AdvanceOutcomeV1.fromWire($it)", AdvanceOutcomeV1.fromWire(it))
        }
    }

    @Test
    fun `known wire codes decode back to the same constant`() {
        VerificationLevelV1.entries.forEach { assertEquals(it, VerificationLevelV1.fromWire(it.wire)) }
        ContinuityCoverageV1.entries.forEach { assertEquals(it, ContinuityCoverageV1.fromWire(it.wire)) }
        DeliveryModeV1.entries.forEach { assertEquals(it, DeliveryModeV1.fromWire(it.wire)) }
        ScheduleDecisionV1.entries.forEach { assertEquals(it, ScheduleDecisionV1.fromWire(it.wire)) }
        ContractErrorCodeV1.entries.forEach { assertEquals(it, ContractErrorCodeV1.fromWire(it.wire)) }
        AdvanceOutcomeV1.entries.forEach { assertEquals(it, AdvanceOutcomeV1.fromWire(it.wire)) }
    }

    /**
     * An unknown error code from a newer peer must land on INTERNAL_FAILURE and
     * fail closed. It must never be optimistically read as a benign or
     * compatible outcome.
     */
    @Test
    fun `unknown error wire code maps to INTERNAL_FAILURE`() {
        assertEquals(
            ContractErrorCodeV1.INTERNAL_FAILURE,
            ContractErrorCodeV1.fromWireOrInternalFailure(9999),
        )
        assertEquals(
            ContractErrorCodeV1.NOT_PAIRED,
            ContractErrorCodeV1.fromWireOrInternalFailure(ContractErrorCodeV1.NOT_PAIRED.wire),
        )
    }

    // ------------------------------------------------------------- trust policy

    /**
     * The trust decision must be an exact match on one constant.
     *
     * `ordinal`-based and `>=`-based policies happen to agree with the exact
     * match today, which is what makes them dangerous: they only diverge later,
     * when a constant is added or reordered, and then they diverge silently in
     * the direction of trusting more. This test states the contract that only
     * the exact match is permitted (INV-05).
     */
    @Test
    fun `only the exact independently-verified constant is trustworthy`() {
        fun trusted(level: VerificationLevelV1?) =
            level == VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED

        assertTrue(trusted(VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED))
        assertFalse(trusted(VerificationLevelV1.HOOK_UNVERIFIED))
        assertFalse(trusted(VerificationLevelV1.NONE))
        assertFalse("an undecodable level is never trusted", trusted(null))
        assertFalse(trusted(VerificationLevelV1.fromWire(99)))
    }

    /**
     * Guards the "not NONE means trusted" shortcut: HOOK_UNVERIFIED is not NONE,
     * and must still be untrusted.
     */
    @Test
    fun `hook unverified is not none and still must not be trusted`() {
        val hook = VerificationLevelV1.HOOK_UNVERIFIED
        assertTrue(hook != VerificationLevelV1.NONE)
        assertFalse(hook == VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED)
    }

    /**
     * Documents that ordinal order is not a trust order. Trusted is ordinal 0
     * and NONE is ordinal 2, so `level.ordinal >= …` reads backwards; anyone
     * reaching for ordinals has to break this test first.
     */
    @Test
    fun `ordinal order is not a trust order`() {
        assertEquals(0, VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.ordinal)
        assertEquals(2, VerificationLevelV1.NONE.ordinal)
        assertTrue(
            "the least trustworthy constant has the highest ordinal",
            VerificationLevelV1.NONE.ordinal >
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.ordinal,
        )
    }

    // ------------------------------------------------------------------- skew

    /**
     * §6.7 handshake matrix: same version runs; either side out of the supported
     * set stops at preflight; an unknown wire code is INCOMPATIBLE_PROTOCOL.
     */
    @Test
    fun `version skew matrix resolves to run or to incompatible protocol`() {
        val supported = setOf(ContractV1.PROTOCOL_VERSION)

        fun handshake(peerProtocolVersion: Int, peerCoverageWire: Int): ContractErrorCodeV1? = when {
            peerProtocolVersion !in supported -> ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL
            ContinuityCoverageV1.fromWire(peerCoverageWire) == null ->
                ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL
            else -> null
        }

        // compatible: same protocol, known codes
        assertNull(handshake(1, ContinuityCoverageV1.FULL.wire))
        // new Auto + old provider
        assertEquals(ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL, handshake(0, ContinuityCoverageV1.FULL.wire))
        // old Auto + new provider
        assertEquals(ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL, handshake(2, ContinuityCoverageV1.FULL.wire))
        // same protocol but a wire code this build has never heard of
        assertEquals(ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL, handshake(1, 88))
    }

    @Test
    fun `protocol version and provider identity constants are frozen`() {
        assertEquals(1, ContractV1.PROTOCOL_VERSION)
        assertEquals(1.0, ContractV1.TRUSTED_LOCATION_TOLERANCE_METERS, 0.0)
        assertEquals("name.caiyao.fakegps", ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION)
        assertEquals("name.caiyao.fakegps.bench", ContractV1.PROVIDER_APPLICATION_ID_BENCH)
        assertEquals(
            "name.caiyao.fakegps.integration.v1.EnvironmentControlService",
            ContractV1.SERVICE_CLASS_NAME,
        )
        // production and bench are distinct principals that pair independently
        assertNotNull(ContractV1.PROVIDER_APPLICATION_ID_BENCH)
        assertTrue(
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION !=
                ContractV1.PROVIDER_APPLICATION_ID_BENCH,
        )
    }
}

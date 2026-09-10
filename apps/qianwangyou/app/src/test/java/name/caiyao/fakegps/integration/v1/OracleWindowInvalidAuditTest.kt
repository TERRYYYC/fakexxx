package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #153: an INVALID authoritative window must leave a durable diagnostic trace —
 * ONE extra append-only audit row per observe, event=ORACLE_WINDOW_INVALID,
 * payloadDigest = the short failing-predicate reason (same precedence as the
 * judgement). Diagnostics only: the wire observation, the trust decision, and
 * the replay watermark store are untouched, and a valid (or source-less)
 * observe appends nothing.
 */
class OracleWindowInvalidAuditTest {

    private fun invalidRows(h: ProviderHarness) = h.audit.all().filter { it.event == "ORACLE_WINDOW_INVALID" }

    @Test
    fun `digest mismatch appends one audit row naming the predicate`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-digest")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val localDigest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        // Producer echoes a DIFFERENT digest than the local projection computes.
        val observer = observer(h, { snapshot(localDigest + "-drift", sequence = 8L) })

        val observed = h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        val rows = invalidRows(h)
        assertEquals("exactly one diagnostic row per invalid observe", 1, rows.size)
        assertEquals("digest_mismatch", rows.single().payloadDigest)
        assertEquals("correlated like every audit row", lease.leaseId, rows.single().leaseId)
        assertEquals(receipt.operationId, rows.single().operationId)
    }

    @Test
    fun `absent endpoints are diagnosed as pre_null`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-absent")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val observer = observer(h, { null })

        h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }

        assertEquals("pre_null", invalidRows(h).single().payloadDigest)
    }

    @Test
    fun `owner identity mismatch is diagnosed not left as bare unhealthy`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-owner")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { snapshot(digest, sequence = 8L) },
            expectedOracleOwnerPackage = "some.other.lane",
            expectedOracleOwnerUid = 10_321,
        )

        h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }

        assertEquals("owner_mismatch", invalidRows(h).single().payloadDigest)
    }

    @Test
    fun `a stale replay after a higher acknowledged cursor is diagnosed as stale_replay`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-replay")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val reads = ArrayDeque<AuthoritativeContinuitySnapshot?>().apply {
            add(snapshot(digest, 10L)); add(snapshot(digest, 10L))
            add(snapshot(digest, 8L)); add(snapshot(digest, 8L))
        }
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { reads.removeFirst() },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = AuthoritativeObservationCommitStore(h.kv),
        )
        val request = ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash)

        val stable = h.kv.transaction { observer.observe(lease, request) }
        h.clock.advance(30_000L)
        val regressed = h.kv.transaction { observer.observe(lease, request) }

        assertEquals(ContinuityCoverageV1.FULL.wire, stable.continuityCoverageWire)
        assertEquals(ContinuityCoverageV1.NONE.wire, regressed.continuityCoverageWire)
        // Exactly ONE diagnostic row across the two observes: the valid window
        // audits nothing, the regressed replay does.
        val rows = invalidRows(h)
        assertEquals(1, rows.size)
        assertEquals("stale_replay", rows.single().payloadDigest)
    }

    @Test
    fun `a producer epoch replacement inside one local generation is diagnosed as epoch_changed`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-epoch")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val stableA = snapshot(digest, 8L)
        val stableB = stableA.copy(bootId = "new-boot", oracleInstanceId = "oracle-b")
        val reads = ArrayDeque<AuthoritativeContinuitySnapshot?>().apply {
            add(stableA); add(stableA); add(stableB); add(stableB)
        }
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { reads.removeFirst() },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = AuthoritativeObservationCommitStore(h.kv),
        )
        val request = ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash)

        val valid = h.kv.transaction { observer.observe(lease, request) }
        h.clock.advance(30_000L)
        val replaced = h.kv.transaction { observer.observe(lease, request) }

        assertEquals(ContinuityCoverageV1.FULL.wire, valid.continuityCoverageWire)
        assertEquals(ContinuityCoverageV1.NONE.wire, replaced.continuityCoverageWire)
        assertEquals("epoch_changed", invalidRows(h).single().payloadDigest)
    }

    @Test
    fun `a valid window appends no diagnostic row and the wire stays untouched`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-valid")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val observer = observer(h, { snapshot(digest, sequence = 8L) })

        val observed = h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }

        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
        assertEquals(emptyList<QwyAuditEvent>(), invalidRows(h))
        // Wire contract unchanged: evidenceRefs still points at exactly the observe row.
        val evidenceSeq = observed.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        assertEquals("observe", h.audit.resolve(evidenceSeq)?.event)
    }

    @Test
    fun `a source-less observe has no window to diagnose and appends nothing`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-sourceless")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val before = h.audit.all()
        val observer = EnvironmentObserver(h.tracker, h.env, h.clock, h.audit)

        observer.observe(
            lease,
            ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash),
        )

        // The normal observe row is still appended; no ORACLE_WINDOW_INVALID on top.
        assertEquals(1, h.audit.all().size - before.size)
        assertEquals(emptyList<QwyAuditEvent>(), invalidRows(h))
    }

    @Test
    fun `the diagnostic row never becomes a commit or acknowledgement`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "oracle-audit-no-commit")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val localDigest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val store = AuthoritativeObservationCommitStore(h.kv)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { snapshot(localDigest, sequence = 9L) },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = store,
        )

        val observed = h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
        assertEquals("mutating_or_changed", invalidRows(h).single().payloadDigest)
        val evidenceSeq = observed.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        assertNull(store.recordForEvidence(evidenceSeq))
        assertEquals(emptySet<String>(), h.kv.keys("integration.v1.authoritative_observation"))
    }

    private fun observer(
        h: ProviderHarness,
        source: () -> AuthoritativeContinuitySnapshot?,
    ): EnvironmentObserver = EnvironmentObserver(
        tracker = h.tracker,
        environment = h.env,
        clock = h.clock,
        audit = h.audit,
        authoritativeSource = AuthoritativeContinuitySource(source),
        expectedOracleOwnerPackage = "name.caiyao.fakegps",
        expectedOracleOwnerUid = 10_321,
    )

    private fun snapshot(digest: String, sequence: Long) = AuthoritativeContinuitySnapshot(
        protocolVersion = 1,
        bootId = "123e4567-e89b-12d3-a456-426614174000",
        oracleInstanceId = "oracle-a",
        sequence = sequence,
        ownerUid = 10_321,
        ownerPackage = "name.caiyao.fakegps",
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        requiredCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = AuthoritativeOracleHealth.HEALTHY,
        qwySemanticDigest = digest,
        lastCompletedQwyMutationId = null,
    )
}

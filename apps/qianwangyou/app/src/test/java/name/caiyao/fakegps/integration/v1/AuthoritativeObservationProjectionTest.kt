package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthoritativeObservationProjectionTest {

    @Test
    fun `only a stable PRE POST source matching the whole local projection yields FULL`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-window")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val source = AuthoritativeContinuitySource { snapshot(digest, sequence = 8L) }
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = source,
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
        )

        val observed = observer.observe(
            lease,
            ObserveRequestV1(receipt.leaseId, "authoritative-window-observe", receipt.acceptedIntentHash),
        )

        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
    }

    @Test
    fun `changed sequence or mismatched local digest remains NONE`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-window-changed")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        var call = 0
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource {
                call += 1
                snapshot("wrong-local-digest", sequence = if (call == 1) 8L else 10L)
            },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
        )

        val observed = observer.observe(
            lease,
            ObserveRequestV1(receipt.leaseId, "authoritative-window-changed-observe", receipt.acceptedIntentHash),
        )

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
    }

    @Test
    fun `one stable source cursor commits each fresh PRE POST observation without moving its watermark`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-replay-watermark")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val cursor = AuthoritativeObservationCursor(
            bootId = "123e4567-e89b-12d3-a456-426614174000",
            oracleInstanceId = "oracle-a",
            sequence = 8L,
            qwySemanticDigest = digest,
        )
        val store = AuthoritativeObservationCommitStore(h.kv)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { snapshot(digest, sequence = 8L) },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = store,
        )

        val first = h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }
        h.clock.advance(30_000L)
        val second = h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }

        assertEquals(ContinuityCoverageV1.FULL.wire, first.continuityCoverageWire)
        assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
        assertEquals(
            "replaying one source cursor is an observation, not a local revision",
            first.environmentRevision,
            second.environmentRevision,
        )
        assertNotEquals(first.evidenceRefs.single(), second.evidenceRefs.single())
        assertEquals(
            AuthoritativeObservationAcknowledgement(cursor, h.tracker.generation, h.tracker.snapshot().revision),
            store.acknowledgement(cursor),
        )
        val evidenceSeqs = (first.evidenceRefs + second.evidenceRefs).map { it.removePrefix("qwy:audit:").toLong() }
        assertEquals(2, evidenceSeqs.distinct().size)
        evidenceSeqs.forEach { evidenceSeq ->
            assertEquals(cursor, store.recordForEvidence(evidenceSeq)?.cursor)
        }
    }

    @Test
    fun `missing odd boot instance and incomplete source windows remain NONE without a replay watermark`() {
        val invalidWindow = { stable: AuthoritativeContinuitySnapshot ->
            listOf<AuthoritativeContinuitySnapshot?>(stable.copy(sequence = 9L), stable.copy(sequence = 9L))
        }
        val cases: List<Pair<String, (AuthoritativeContinuitySnapshot) -> List<AuthoritativeContinuitySnapshot?>>> = listOf(
            "missing" to { _ -> listOf(null, null) },
            "odd" to invalidWindow,
            "boot-changed" to { stable -> listOf(stable, stable.copy(bootId = "different-boot")) },
            "instance-changed" to { stable -> listOf(stable, stable.copy(oracleInstanceId = "oracle-b")) },
            "coverage-incomplete" to { stable ->
                listOf(stable.copy(installedCoverageMask = 0L), stable.copy(installedCoverageMask = 0L))
            },
        )

        cases.forEach { (name, buildWindow) ->
            val h = ProviderHarness.create()
            h.pair()
            val receipt = h.apply(key = "authoritative-invalid-$name")
            val lease = checkNotNull(h.leases.get(receipt.leaseId))
            val digest = QwyObservedSemanticDigest.compute(
                ownerGeneration = h.tracker.generation,
                effective = h.env.observeEffective(),
                schedule = h.env.scheduleSnapshot(),
            )
            val window = buildWindow(snapshot(digest, sequence = 8L))
            var read = 0
            val store = AuthoritativeObservationCommitStore(h.kv)
            val observer = EnvironmentObserver(
                tracker = h.tracker,
                environment = h.env,
                clock = h.clock,
                audit = h.audit,
                authoritativeSource = AuthoritativeContinuitySource { window[read++ % window.size] },
                expectedOracleOwnerPackage = "name.caiyao.fakegps",
                expectedOracleOwnerUid = 10_321,
                authoritativeCommitStore = store,
            )

            val observed = h.kv.transaction {
                observer.observe(
                    lease,
                    ObserveRequestV1(receipt.leaseId, "authoritative-invalid-observe-$name", receipt.acceptedIntentHash),
                )
            }

            assertEquals("$name source window", ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
            val evidenceSeq = observed.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
            assertNull("$name must not make a record", store.recordForEvidence(evidenceSeq))
            assertEquals("$name must not make an acknowledgement", emptySet<String>(), h.kv.keys("integration.v1.authoritative_observation"))
        }
    }

    @Test
    fun `a later incomplete frame cannot reuse an earlier FULL cursor or evidence ref`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-replay-does-not-promote")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val stable = snapshot(digest, sequence = 8L)
        val reads = listOf<AuthoritativeContinuitySnapshot?>(stable, stable, null, null)
        var read = 0
        val store = AuthoritativeObservationCommitStore(h.kv)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { reads[read++] },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = store,
        )

        val full = h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }
        val none = h.kv.transaction {
            observer.observe(lease, ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash))
        }

        assertEquals(ContinuityCoverageV1.FULL.wire, full.continuityCoverageWire)
        assertEquals(ContinuityCoverageV1.NONE.wire, none.continuityCoverageWire)
        assertNotEquals(full.evidenceRefs.single(), none.evidenceRefs.single())
        val fullSeq = full.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        val noneSeq = none.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        assertEquals(stable.bootId, store.recordForEvidence(fullSeq)?.cursor?.bootId)
        assertNull("an incomplete replay gets fresh NONE, never cached FULL", store.recordForEvidence(noneSeq))
    }

    @Test
    fun `a later valid cursor binds its own local revision without changing the earlier acknowledgement`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-new-cursor")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        fun localDigest() = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val firstCursor = AuthoritativeObservationCursor(
            "123e4567-e89b-12d3-a456-426614174000", "oracle-a", 8L, localDigest(),
        )
        val reads = ArrayDeque<AuthoritativeContinuitySnapshot?>().apply {
            add(snapshot(firstCursor.qwySemanticDigest, firstCursor.sequence))
            add(snapshot(firstCursor.qwySemanticDigest, firstCursor.sequence))
        }
        val store = AuthoritativeObservationCommitStore(h.kv)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { reads.removeFirst() },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = store,
        )
        val request = ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash)

        val first = h.kv.transaction { observer.observe(lease, request) }
        h.env.hijackAndRestoreMockOwner()
        val secondCursor = firstCursor.copy(sequence = 10L, qwySemanticDigest = localDigest())
        reads.add(snapshot(secondCursor.qwySemanticDigest, secondCursor.sequence))
        reads.add(snapshot(secondCursor.qwySemanticDigest, secondCursor.sequence))
        val second = h.kv.transaction { observer.observe(lease, request) }

        assertEquals(ContinuityCoverageV1.FULL.wire, first.continuityCoverageWire)
        assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
        assertNotEquals(first.environmentRevision, second.environmentRevision)
        assertEquals(firstCursor, store.acknowledgement(firstCursor)?.cursor)
        assertEquals(secondCursor, store.acknowledgement(secondCursor)?.cursor)
        val firstSeq = first.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        val secondSeq = second.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        assertEquals(first.environmentRevision, store.recordForEvidence(firstSeq)?.localRevision)
        assertEquals(second.environmentRevision, store.recordForEvidence(secondSeq)?.localRevision)
    }

    @Test
    fun `a new valid source cursor conservatively advances local revision inside its observation commit`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-source-cursor-bump")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val reads = ArrayDeque<AuthoritativeContinuitySnapshot?>().apply {
            add(snapshot(digest, 8L)); add(snapshot(digest, 8L))
            add(snapshot(digest, 10L)); add(snapshot(digest, 10L))
        }
        val store = AuthoritativeObservationCommitStore(h.kv)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { reads.removeFirst() },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = store,
        )
        val request = ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash)

        val first = h.kv.transaction { observer.observe(lease, request) }
        val second = h.kv.transaction { observer.observe(lease, request) }

        assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
        assertEquals(
            "a new trusted cursor must not reuse the earlier local revision",
            first.environmentRevision + 1L,
            second.environmentRevision,
        )
    }

    @Test
    fun `a completed source epoch cannot regress to an earlier cursor after a fresh PRE POST window`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-source-cursor-regression")
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
        val store = AuthoritativeObservationCommitStore(h.kv)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { reads.removeFirst() },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = store,
        )
        val request = ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash)

        val stable = h.kv.transaction { observer.observe(lease, request) }
        val regressed = h.kv.transaction { observer.observe(lease, request) }

        assertEquals(ContinuityCoverageV1.FULL.wire, stable.continuityCoverageWire)
        assertEquals(ContinuityCoverageV1.NONE.wire, regressed.continuityCoverageWire)
        val regressedSeq = regressed.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        assertNull(store.recordForEvidence(regressedSeq))
    }

    @Test
    fun `a producer boot or instance replacement is NONE within one local owner generation`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-source-epoch-change")
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
        val store = AuthoritativeObservationCommitStore(h.kv)
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource { reads.removeFirst() },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
            authoritativeCommitStore = store,
        )
        val request = ObserveRequestV1(receipt.leaseId, receipt.operationId, receipt.acceptedIntentHash)

        h.kv.transaction { observer.observe(lease, request) }
        val replaced = h.kv.transaction { observer.observe(lease, request) }

        assertEquals(ContinuityCoverageV1.NONE.wire, replaced.continuityCoverageWire)
        val replacedSeq = replaced.evidenceRefs.single().removePrefix("qwy:audit:").toLong()
        assertNull(store.recordForEvidence(replacedSeq))
        assertNull(store.acknowledgement(AuthoritativeObservationCursor(
            stableB.bootId, stableB.oracleInstanceId, stableB.sequence, checkNotNull(stableB.qwySemanticDigest),
        )))
    }

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

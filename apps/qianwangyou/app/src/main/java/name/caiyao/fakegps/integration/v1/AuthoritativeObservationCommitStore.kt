package name.caiyao.fakegps.integration.v1

/** Exact stable oracle cursor captured from one valid PRE/POST observation window. */
data class AuthoritativeObservationCursor(
    val bootId: String,
    val oracleInstanceId: String,
    val sequence: Long,
    val qwySemanticDigest: String,
)

/**
 * #199: the durable digest interval one cleanly finished owner bracket drove
 * (beforeDigest → afterDigest). Recorded by the handler right after the bracket
 * publishes its after-digest, BEFORE the bracket-time cursor ack is attempted —
 * so when that ack is later skipped (odd sequence in flight, foreign mutation
 * sharing the window, unreadable after-read), the NEXT observe can still
 * attribute the cursor motion to the owner's own, already revision-counted
 * mutation instead of double-counting it as an external change.
 */
data class OwnerMutationInterval(
    val mutationId: String,
    val beforeDigest: String,
    val afterDigest: String,
    val localGeneration: Long,
)

/** First local acknowledgement of one source cursor. It is diagnostic, never a source of FULL. */
data class AuthoritativeObservationAcknowledgement(
    val cursor: AuthoritativeObservationCursor,
    val localGeneration: Long,
    val localRevision: Long,
)

/** Immutable association from one durable audit event to its authoritative source cursor. */
data class AuthoritativeObservationCommitRecord(
    val cursor: AuthoritativeObservationCursor,
    val localGeneration: Long,
    val localRevision: Long,
    val evidenceSeq: Long,
    val evidenceDigest: String,
)

/**
 * Provider-private replay watermark for authoritative observations.
 *
 * This class intentionally has no coverage/result API: callers must read a
 * fresh oracle PRE/POST window for every FULL decision. It only records the
 * already-validated cursor with the audit event it was committed beside.
 */
class AuthoritativeObservationCommitStore(
    private val storage: DurableKv,
) {
    companion object {
        private const val NAMESPACE = "integration.v1.authoritative_observation"
        private const val ACK_PREFIX = "ack:"
        private const val RECORD_PREFIX = "record:"
        private const val INTERVAL_PREFIX = "interval:"

        /**
         * #199: bounded DFS for owner digest-interval chains. Real chains are
         * one or two brackets long (apply → pre-observe, release → advance →
         * post-observe). The depth cap terminates pathological cycles where
         * repeated re-publishes return the environment to a prior digest; the
         * NODE budget bounds TOTAL work so a pathological interval family
         * (many intervals sharing one beforeDigest) cannot turn the observe
         * path exponential — overshooting the budget classifies the transition
         * as unexplained, which is the conservative bump direction.
         */
        private const val INTERVAL_WALK_LIMIT = 16
        private const val INTERVAL_WALK_NODE_BUDGET = 256
    }

    /**
     * Joins a caller-owned outer transaction with the audit append. The first
     * acknowledgement for [cursor] is immutable; later observations at the
     * same cursor receive their own [evidence] record without changing it.
     */
    fun record(
        cursor: AuthoritativeObservationCursor,
        localGeneration: Long,
        localRevision: Long,
        evidence: QwyAuditEvent,
        evidenceDigest: String,
    ) = storage.transaction {
        require(cursor.bootId.isNotBlank() && cursor.oracleInstanceId.isNotBlank()) {
            "authoritative observation cursor identity must be present"
        }
        require(cursor.sequence >= 0L && cursor.sequence and 1L == 0L) {
            "authoritative observation cursor must be a stable even sequence"
        }
        require(cursor.qwySemanticDigest.isNotBlank()) {
            "authoritative observation cursor requires a semantic digest"
        }
        require(localGeneration >= 0L && localRevision >= 0L) {
            "authoritative observation local generation and revision must be non-negative"
        }
        require(evidence.seq > 0L) { "authoritative observation requires an assigned audit sequence" }
        require(evidence.event == "observe") { "authoritative observation requires an observe audit event" }
        require(evidence.payloadDigest == evidenceDigest) {
            "authoritative observation evidence digest must match the audit event"
        }
        val acknowledgement = AuthoritativeObservationAcknowledgement(cursor, localGeneration, localRevision)
        val acknowledgementKey = ACK_PREFIX + encodeCursor(cursor)
        val existingAcknowledgement = storage.read(NAMESPACE, acknowledgementKey)?.let(::decodeAcknowledgement)
        when {
            existingAcknowledgement == null -> storage.write(
                NAMESPACE,
                acknowledgementKey,
                encodeAcknowledgement(acknowledgement),
            )
            // The acknowledgement is deliberately first-write-wins. A local
            // relevant-change bump may occur between two independently valid
            // observations of the same source cursor; its new audit record
            // carries the current local revision, while this replay watermark
            // remains the original diagnostic binding.
            else -> Unit
        }

        val record = AuthoritativeObservationCommitRecord(
            cursor = cursor,
            localGeneration = localGeneration,
            localRevision = localRevision,
            evidenceSeq = evidence.seq,
            evidenceDigest = evidenceDigest,
        )
        val recordKey = RECORD_PREFIX + evidence.seq
        val existingRecord = storage.read(NAMESPACE, recordKey)?.let(::decodeRecord)
        when {
            existingRecord == null -> storage.write(NAMESPACE, recordKey, encodeRecord(record))
            existingRecord != record -> error("audit sequence already has a different authoritative observation record")
        }
    }

    /**
     * #166: first local acknowledgement of a cursor the OWNER itself just
     * produced by completing one of its own bracketed semantic mutations. It
     * writes the same replay-watermark rows as [record] — the observe-side
     * predicates read both identically — but without an observe audit row,
     * because the producing event is the owner's own operation, not an
     * observation. First-write-wins exactly like [record]; nested calls join
     * the caller's outer DurableKv transaction.
     */
    internal fun acknowledgeOwnerMutation(
        cursor: AuthoritativeObservationCursor,
        localGeneration: Long,
        localRevision: Long,
    ) = storage.transaction {
        require(cursor.bootId.isNotBlank() && cursor.oracleInstanceId.isNotBlank()) {
            "authoritative observation cursor identity must be present"
        }
        require(cursor.sequence >= 0L && cursor.sequence and 1L == 0L) {
            "authoritative observation cursor must be a stable even sequence"
        }
        require(cursor.qwySemanticDigest.isNotBlank()) {
            "authoritative observation cursor requires a semantic digest"
        }
        require(localGeneration >= 0L && localRevision >= 0L) {
            "authoritative observation local generation and revision must be non-negative"
        }
        val acknowledgementKey = ACK_PREFIX + encodeCursor(cursor)
        if (storage.read(NAMESPACE, acknowledgementKey) == null) {
            storage.write(
                NAMESPACE,
                acknowledgementKey,
                encodeAcknowledgement(
                    AuthoritativeObservationAcknowledgement(cursor, localGeneration, localRevision),
                ),
            )
        }
    }

    internal fun acknowledgement(cursor: AuthoritativeObservationCursor): AuthoritativeObservationAcknowledgement? =
        storage.read(NAMESPACE, ACK_PREFIX + encodeCursor(cursor))?.let(::decodeAcknowledgement)

    /**
     * #199: the FULL highest-acknowledged cursor of the cursor's source epoch —
     * its [AuthoritativeObservationCursor.qwySemanticDigest] is the semantic
     * baseline the observer attributes unacknowledged cursor motion against.
     */
    internal fun highestAcknowledgedCursorForSourceEpoch(
        cursor: AuthoritativeObservationCursor,
    ): AuthoritativeObservationCursor? = storage.keys(NAMESPACE)
        .asSequence()
        .filter { it.startsWith(ACK_PREFIX) }
        .map { decodeAcknowledgement(checkNotNull(storage.read(NAMESPACE, it))).cursor }
        .filter { acknowledged ->
            acknowledged.bootId == cursor.bootId && acknowledged.oracleInstanceId == cursor.oracleInstanceId
        }
        .maxByOrNull { it.sequence }

    /**
     * #199: durable attribution evidence for the observer's revision catch-up.
     * A digest transition [fromDigest] → [toDigest] is OWNER-ACCOUNTED when a
     * chain of recorded owner-mutation intervals explains it — the owner's own
     * operation already bumped the tracker, so the observer must acknowledge
     * the cursor instead of double-counting it.
     *
     * Soundness rests on two producer invariants (SystemServerOracleState):
     * the oracle digest only changes when an owner bracket publishes an
     * afterDigest (covered platform mutations pass null), and every handler
     * operation bumps the tracker BEFORE its bracket publishes. An interval
     * row can therefore only ever explain a transition the tracker counted.
     */
    internal fun explainsDigestTransition(fromDigest: String, toDigest: String): Boolean {
        if (fromDigest == toDigest) return true
        val budget = intArrayOf(INTERVAL_WALK_NODE_BUDGET)
        return walkIntervals(ownerMutationIntervals(), fromDigest, toDigest, mutableSetOf(), 0, budget)
    }

    private fun walkIntervals(
        intervals: List<OwnerMutationInterval>,
        current: String,
        target: String,
        used: MutableSet<String>,
        depth: Int,
        budget: IntArray,
    ): Boolean {
        if (current == target) return true
        if (depth >= INTERVAL_WALK_LIMIT) return false
        for (interval in intervals) {
            if (interval.beforeDigest != current || interval.mutationId in used) continue
            if (budget[0] <= 0) return false
            budget[0] -= 1
            used += interval.mutationId
            if (walkIntervals(intervals, interval.afterDigest, target, used, depth + 1, budget)) return true
            used -= interval.mutationId
        }
        return false
    }

    /** Every durable owner-mutation interval row (bounded per owner epoch). */
    internal fun ownerMutationIntervals(): List<OwnerMutationInterval> =
        storage.keys(NAMESPACE)
            .asSequence()
            .filter { it.startsWith(INTERVAL_PREFIX) }
            .map { decodeInterval(checkNotNull(storage.read(NAMESPACE, it))) }
            .toList()

    /**
     * #199: record the digest interval of one cleanly finished owner bracket.
     * First-write-wins per (generation, mutationId) — a settled replay of the
     * same operation drives the same transition. Callers join an outer
     * DurableKv transaction; failures degrade at the call site to the
     * conservative pre-#199 bump behavior.
     */
    fun recordOwnerMutationInterval(
        mutationId: String,
        beforeDigest: String,
        afterDigest: String,
        localGeneration: Long,
    ) = storage.transaction {
        require(mutationId.isNotBlank()) { "owner mutation interval requires a mutation id" }
        require(beforeDigest.isNotBlank() && afterDigest.isNotBlank()) {
            "owner mutation interval requires both digest endpoints"
        }
        val key = INTERVAL_PREFIX + "$localGeneration:$mutationId"
        if (storage.read(NAMESPACE, key) == null) {
            storage.write(
                NAMESPACE,
                key,
                encodeInterval(OwnerMutationInterval(mutationId, beforeDigest, afterDigest, localGeneration)),
            )
        }
    }

    /** True only when this oracle boot/instance has an earlier trusted cursor. */
    internal fun hasAcknowledgementForSourceEpoch(cursor: AuthoritativeObservationCursor): Boolean =
        storage.keys(NAMESPACE)
            .asSequence()
            .filter { it.startsWith(ACK_PREFIX) }
            .map { key -> decodeAcknowledgement(checkNotNull(storage.read(NAMESPACE, key))) }
            .any { acknowledged ->
                acknowledged.cursor.bootId == cursor.bootId &&
                    acknowledged.cursor.oracleInstanceId == cursor.oracleInstanceId
            }

    /** The highest locally acknowledged stable sequence for this source epoch. */
    internal fun highestAcknowledgedSequenceForSourceEpoch(cursor: AuthoritativeObservationCursor): Long? =
        storage.keys(NAMESPACE)
            .asSequence()
            .filter { it.startsWith(ACK_PREFIX) }
            .map { key -> decodeAcknowledgement(checkNotNull(storage.read(NAMESPACE, key))).cursor }
            .filter { acknowledged ->
                acknowledged.bootId == cursor.bootId && acknowledged.oracleInstanceId == cursor.oracleInstanceId
            }
            .maxOfOrNull { it.sequence }

    internal fun digestForAcknowledgedSequence(cursor: AuthoritativeObservationCursor): String? =
        storage.keys(NAMESPACE).asSequence().filter { it.startsWith(ACK_PREFIX) }
            .map { decodeAcknowledgement(checkNotNull(storage.read(NAMESPACE, it))).cursor }
            .firstOrNull { it.bootId == cursor.bootId && it.oracleInstanceId == cursor.oracleInstanceId && it.sequence == cursor.sequence }
            ?.qwySemanticDigest

    /** A source epoch switch within one local owner generation is unproven. */
    internal fun hasAcknowledgementForDifferentSourceEpoch(
        localGeneration: Long,
        cursor: AuthoritativeObservationCursor,
    ): Boolean = storage.keys(NAMESPACE)
        .asSequence()
        .filter { it.startsWith(ACK_PREFIX) }
        .map { key -> decodeAcknowledgement(checkNotNull(storage.read(NAMESPACE, key))) }
        .any { acknowledged ->
            acknowledged.localGeneration == localGeneration &&
                (acknowledged.cursor.bootId != cursor.bootId ||
                    acknowledged.cursor.oracleInstanceId != cursor.oracleInstanceId)
        }

    internal fun recordForEvidence(evidenceSeq: Long): AuthoritativeObservationCommitRecord? {
        require(evidenceSeq > 0L) { "audit sequence must be positive" }
        return storage.read(NAMESPACE, RECORD_PREFIX + evidenceSeq)?.let(::decodeRecord)
    }

    private fun encodeCursor(cursor: AuthoritativeObservationCursor): String = DurableFieldCodec.encode(
        listOf(cursor.bootId, cursor.oracleInstanceId, cursor.sequence.toString(), cursor.qwySemanticDigest),
    )

    private fun decodeCursor(encoded: String): AuthoritativeObservationCursor {
        val fields = DurableFieldCodec.decodeNonNull(encoded)
        check(fields.size == 4) { "invalid authoritative observation cursor" }
        return AuthoritativeObservationCursor(fields[0], fields[1], fields[2].toLong(), fields[3])
    }

    private fun encodeAcknowledgement(value: AuthoritativeObservationAcknowledgement): String = DurableFieldCodec.encode(
        listOf(
            encodeCursor(value.cursor),
            value.localGeneration.toString(),
            value.localRevision.toString(),
        ),
    )

    private fun decodeAcknowledgement(encoded: String): AuthoritativeObservationAcknowledgement {
        val fields = DurableFieldCodec.decodeNonNull(encoded)
        check(fields.size == 3) { "invalid authoritative observation acknowledgement" }
        return AuthoritativeObservationAcknowledgement(
            cursor = decodeCursor(fields[0]),
            localGeneration = fields[1].toLong(),
            localRevision = fields[2].toLong(),
        )
    }

    private fun encodeInterval(value: OwnerMutationInterval): String = DurableFieldCodec.encode(
        listOf(
            value.mutationId,
            value.beforeDigest,
            value.afterDigest,
            value.localGeneration.toString(),
        ),
    )

    private fun decodeInterval(encoded: String): OwnerMutationInterval {
        val fields = DurableFieldCodec.decodeNonNull(encoded)
        check(fields.size == 4) { "invalid owner mutation interval" }
        return OwnerMutationInterval(
            mutationId = fields[0],
            beforeDigest = fields[1],
            afterDigest = fields[2],
            localGeneration = fields[3].toLong(),
        )
    }

    private fun encodeRecord(value: AuthoritativeObservationCommitRecord): String = DurableFieldCodec.encode(
        listOf(
            encodeCursor(value.cursor),
            value.localGeneration.toString(),
            value.localRevision.toString(),
            value.evidenceSeq.toString(),
            value.evidenceDigest,
        ),
    )

    private fun decodeRecord(encoded: String): AuthoritativeObservationCommitRecord {
        val fields = DurableFieldCodec.decodeNonNull(encoded)
        check(fields.size == 5) { "invalid authoritative observation record" }
        return AuthoritativeObservationCommitRecord(
            cursor = decodeCursor(fields[0]),
            localGeneration = fields[1].toLong(),
            localRevision = fields[2].toLong(),
            evidenceSeq = fields[3].toLong(),
            evidenceDigest = fields[4],
        )
    }
}

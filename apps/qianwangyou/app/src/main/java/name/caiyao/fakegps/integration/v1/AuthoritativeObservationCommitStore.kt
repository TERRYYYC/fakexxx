package name.caiyao.fakegps.integration.v1

/** Exact stable oracle cursor captured from one valid PRE/POST observation window. */
data class AuthoritativeObservationCursor(
    val bootId: String,
    val oracleInstanceId: String,
    val sequence: Long,
    val qwySemanticDigest: String,
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

    internal fun acknowledgement(cursor: AuthoritativeObservationCursor): AuthoritativeObservationAcknowledgement? =
        storage.read(NAMESPACE, ACK_PREFIX + encodeCursor(cursor))?.let(::decodeAcknowledgement)

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

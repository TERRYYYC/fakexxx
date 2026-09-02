package name.caiyao.fakegps.integration.v1

internal sealed interface PendingAdvanceTicket {
    val fromItemId: String
    val toItemId: String?
    val versionAfter: Long?

    data class Legacy(
        override val fromItemId: String,
        override val toItemId: String?,
        override val versionAfter: Long?,
    ) : PendingAdvanceTicket

    data class Authoritative(
        override val fromItemId: String,
        override val toItemId: String?,
        override val versionAfter: Long,
        val reservation: AuthoritativeRevisionReservation,
    ) : PendingAdvanceTicket

    companion object {
        // The pre-review oracle-v1 draft omitted the retained owner generation. It was never
        // reachable in a distributable build because production attestation stayed empty; reject
        // that dead format rather than decoding a reservation that cannot be recovered safely.
        private const val AUTHORITATIVE_V2 = "oracle-v2"

        fun decode(encoded: String): PendingAdvanceTicket {
            val fields = DurableFieldCodec.decode(encoded)
            // Check the total field count before the tag: legacy item IDs are
            // free strings and may themselves equal an oracle schema tag.
            val authoritativeV2 = fields.size == 12 && fields[0] == AUTHORITATIVE_V2
            if (authoritativeV2) {
                return Authoritative(
                    fromItemId = checkNotNull(fields[1]),
                    toItemId = fields[2],
                    versionAfter = checkNotNull(fields[3]).toLong(),
                    reservation = AuthoritativeRevisionReservation(
                        mutationId = checkNotNull(fields[4]),
                        baseRevision = checkNotNull(fields[5]).toLong(),
                        reservedRevision = checkNotNull(fields[6]).toLong(),
                        startingBootId = checkNotNull(fields[7]),
                        startingOracleInstanceId = checkNotNull(fields[8]),
                        startingSequence = checkNotNull(fields[9]).toLong(),
                        startingSemanticDigest = checkNotNull(fields[10]),
                        ownerGenerationAtReservation = checkNotNull(fields[11]).toLong(),
                    ),
                ).also { ticket ->
                    check(ticket.fromItemId.isNotBlank()) { "pending advance from item is blank" }
                    check(ticket.versionAfter >= 0L) { "pending advance version is negative" }
                    check(ticket.reservation.mutationId.isNotBlank()) {
                        "pending authoritative mutation id is blank"
                    }
                    check(ticket.reservation.reservedRevision ==
                        ticket.reservation.baseRevision + 1L
                    ) { "pending authoritative revision is not base + 1" }
                    check(ticket.reservation.startingBootId.isNotBlank() &&
                        ticket.reservation.startingOracleInstanceId.isNotBlank() &&
                        ticket.reservation.startingSemanticDigest.isNotBlank()
                    ) { "pending authoritative baseline is incomplete" }
                    check(ticket.reservation.startingSequence >= 0L &&
                        ticket.reservation.startingSequence % 2L == 0L
                    ) { "pending authoritative sequence is not stable" }
                }
            }
            check(fields.size in 2..3) { "invalid legacy pending advance field count" }
            return Legacy(
                fromItemId = checkNotNull(fields[0]),
                toItemId = fields[1],
                versionAfter = fields.getOrNull(2)?.toLongOrNull(),
            )
        }

        fun encode(ticket: PendingAdvanceTicket): String = when (ticket) {
            is Legacy -> DurableFieldCodec.encode(
                listOf(
                    ticket.fromItemId,
                    ticket.toItemId,
                    ticket.versionAfter?.toString(),
                ),
            )

            is Authoritative -> DurableFieldCodec.encode(
                listOf(
                    AUTHORITATIVE_V2,
                    ticket.fromItemId,
                    ticket.toItemId,
                    ticket.versionAfter.toString(),
                    ticket.reservation.mutationId,
                    ticket.reservation.baseRevision.toString(),
                    ticket.reservation.reservedRevision.toString(),
                    ticket.reservation.startingBootId,
                    ticket.reservation.startingOracleInstanceId,
                    ticket.reservation.startingSequence.toString(),
                    ticket.reservation.startingSemanticDigest,
                    ticket.reservation.ownerGenerationAtReservation.toString(),
                ),
            )
        }
    }
}

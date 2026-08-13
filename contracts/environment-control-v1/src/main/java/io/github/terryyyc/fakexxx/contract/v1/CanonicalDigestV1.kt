package io.github.terryyyc.fakexxx.contract.v1

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Shared canonical framing for every v1 digest. Spec §6.3.1 / §6.3.4 / §6.7.3.
 *
 * ## Why one helper and not a copy per digest
 *
 * Both apps compute these independently and compare, so the encoding IS the
 * contract. A second hand-rolled copy of the framing is a second place for it to
 * drift, and a drifted framing does not fail loudly — it produces a different
 * digest, which reads as "the peer disagrees" rather than "we encode
 * differently".
 *
 * ## Why every digest carries a domain
 *
 * Length-prefixed framing makes ONE field sequence injective. It does not stop a
 * sequence for one purpose from equalling a sequence for another: nothing in
 * `len||bytes || len||bytes ...` says what the fields meant. Without a domain
 * tag, an intent preimage and an advance-request preimage share one space, and
 * correctness depends on no crafted input ever colliding across them.
 *
 * That is the same reasoning §6.3.1 already used to reject separator joining —
 * "correctness stops depending on the runtime accident that no field happened to
 * contain the separator". Cross-purpose collision is that accident one level up,
 * so the domain tag is framed as the FIRST field of every preimage and the
 * accident is removed rather than assumed away.
 */
object CanonicalDigestV1 {

    /** Frozen domain tags. Never reuse or renumber; a new digest gets a new tag. */
    const val DOMAIN_INTENT: String = "fakexxx:contract:v1:intent"
    const val DOMAIN_ADVANCE_REQUEST: String = "fakexxx:contract:v1:advance-request"
    const val DOMAIN_ADVANCE_RECEIPT: String = "fakexxx:contract:v1:advance-receipt"

    /**
     * `uint32be(len(domain)) || domain || (uint32be(len(f)) || f)*`
     *
     * No separators and no trailing bytes, exactly as §6.3.1 freezes.
     */
    fun canonicalBytes(domain: String, fields: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        appendFramed(out, domain.toByteArray(Charsets.US_ASCII))
        for (f in fields) appendFramed(out, f)
        return out.toByteArray()
    }

    /** Lowercase hex SHA-256 over [canonicalBytes]. */
    fun digest(domain: String, fields: List<ByteArray>): String =
        sha256Hex(canonicalBytes(domain, fields))

    fun utf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)
    fun decimal(value: Long): ByteArray = value.toString().toByteArray(Charsets.US_ASCII)
    fun decimal(value: Int): ByteArray = value.toString().toByteArray(Charsets.US_ASCII)

    private fun appendFramed(out: ByteArrayOutputStream, bytes: ByteArray) {
        val len = bytes.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes, 0, len)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * `requestDigest` for [CompleteAndAdvanceRequestV1]. Spec §6.7.3.
 *
 * The preimage MUST bind both preconditions. Idempotent replay decides "this is
 * the same request" from key + digest; if `expectedCurrentItemId` were absent,
 * two requests aimed at DIFFERENT current items would produce the SAME digest,
 * replay would treat them as one, and that is exactly wrong-item and double
 * advance. `expectedScheduleVersion` does the same for skip.
 *
 * The failure would be invisible from the wire table: 14/15/16 all still exist
 * and still run, they simply can no longer distinguish the cases they are named
 * after.
 *
 * Three fields are deliberately OUTSIDE the preimage, each for a stated reason:
 * `idempotencyKey` is the lookup key, so including it would be self-referential;
 * `callerProtocolVersion` is excluded for the reason §6.3.4 already froze, that a
 * caller upgrading mid-retry must not become a false conflict; and
 * `completionProof.verifiedAtElapsedRealtimeMs` is audit metadata about WHEN the
 * quota was measured, not part of WHICH completion is being reported. A retry
 * that re-measures would otherwise carry a new timestamp, change the digest, and
 * be treated as a different request — turning every retry into a new advance,
 * which is precisely what the digest exists to prevent.
 */
object CanonicalAdvanceDigestV1 {

    fun compute(request: CompleteAndAdvanceRequestV1): String =
        CanonicalDigestV1.digest(CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST, fields(request))

    /** Exposed so a conformance test can assert the framing, not only the digest. */
    fun canonicalBytes(request: CompleteAndAdvanceRequestV1): ByteArray =
        CanonicalDigestV1.canonicalBytes(CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST, fields(request))

    private fun fields(r: CompleteAndAdvanceRequestV1): List<ByteArray> = listOf(
        CanonicalDigestV1.utf8(r.leaseId),
        CanonicalDigestV1.decimal(r.expectedScheduleVersion),
        CanonicalDigestV1.utf8(r.expectedCurrentItemId),
        CanonicalDigestV1.utf8(r.completionProof.scheduleItemId),
        CanonicalDigestV1.decimal(r.completionProof.trustedSuccessCount),
        CanonicalDigestV1.decimal(r.completionProof.quotaRequired),
        CanonicalDigestV1.utf8(r.completionProof.ledgerRef),
    )
}

/**
 * `receiptDigest` for [AdvanceReceiptV1]. Spec §6.7.3 / §6.7.5.
 *
 * A receipt that does not bind the request it answers is not evidence of
 * anything: on a retry the caller gets a receipt back and cannot tell "this is
 * the stored answer to MY request" from "this is some other answer the provider
 * happened to have". So the digest covers the originating `requestDigest` and
 * `idempotencyKey` together with the outcome the provider claims, and Auto
 * recomputes it before trusting the receipt at all.
 *
 * `advancedToItemId` is null when exhausted. Absence is encoded with an explicit
 * presence discriminator (see below), never a magic value, so no id can be read
 * as absence — the same reason §6.3.1 refuses to let a value absorb a delimiter.
 */
object CanonicalAdvanceReceiptDigestV1 {

    /**
     * Absence is encoded as an explicit PRESENCE DISCRIMINATOR, not a sentinel
     * value.
     *
     * The first version used a magic string and justified it with "this cannot
     * appear in a real schedule item id, which §6.7.1 requires to be printable".
     * §6.7.1 requires no such thing. That made the encoding injective only by an
     * assumption nothing enforced, so an id equal to the sentinel collided with
     * null and produced the same receiptDigest -- the exact "correctness rests on
     * a runtime accident" this file rejects two objects above, reintroduced by
     * the person who wrote that rejection.
     *
     * A discriminator has no unreachable-value obligation at all: "0" means
     * absent and is followed by nothing, "1" means present and is followed by the
     * framed id. No id, however chosen, can be read as absence.
     */
    private const val ABSENT: String = "0"
    private const val PRESENT: String = "1"

    fun compute(receipt: AdvanceReceiptV1, requestDigest: String, idempotencyKey: String): String =
        CanonicalDigestV1.digest(
            CanonicalDigestV1.DOMAIN_ADVANCE_RECEIPT,
            fields(receipt, requestDigest, idempotencyKey),
        )

    /**
     * Exposed so a conformance test can assert the framing, not only the digest.
     *
     * The other two digests already expose this; this one did not, so the
     * advance-receipt domain was the one preimage whose first-field framing
     * could not be asserted at all.
     */
    fun canonicalBytes(
        receipt: AdvanceReceiptV1,
        requestDigest: String,
        idempotencyKey: String,
    ): ByteArray =
        CanonicalDigestV1.canonicalBytes(
            CanonicalDigestV1.DOMAIN_ADVANCE_RECEIPT,
            fields(receipt, requestDigest, idempotencyKey),
        )

    private fun fields(
        receipt: AdvanceReceiptV1,
        requestDigest: String,
        idempotencyKey: String,
    ): List<ByteArray> =
        listOf(
            CanonicalDigestV1.utf8(requestDigest),
            CanonicalDigestV1.utf8(idempotencyKey),
            CanonicalDigestV1.decimal(receipt.outcomeWire),
            CanonicalDigestV1.utf8(receipt.advancedFromItemId),
        ) + presence(receipt.advancedToItemId) + listOf(
            CanonicalDigestV1.decimal(receipt.scheduleVersionAfter),
            CanonicalDigestV1.utf8(receipt.effectiveIntentHash),
            CanonicalDigestV1.decimal(receipt.effectiveEnvironmentRevision),
        )

    /** `"0"` alone when absent; `"1"` followed by the framed id when present. */
    private fun presence(value: String?): List<ByteArray> =
        if (value == null) listOf(CanonicalDigestV1.utf8(ABSENT))
        else listOf(CanonicalDigestV1.utf8(PRESENT), CanonicalDigestV1.utf8(value))

    /**
     * Auto's check before believing a receipt. A receipt whose digest does not
     * recompute is not a weaker receipt — it is not a receipt.
     */
    fun verify(receipt: AdvanceReceiptV1, requestDigest: String, idempotencyKey: String): Boolean =
        compute(receipt, requestDigest, idempotencyKey) == receipt.receiptDigest
}

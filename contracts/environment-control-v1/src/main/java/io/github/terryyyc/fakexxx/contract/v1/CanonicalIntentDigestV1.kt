package io.github.terryyyc.fakexxx.contract.v1

/**
 * Canonical digest of an [EnvironmentIntentV1]. Frozen algorithm, spec §6.3.1.
 *
 * Both apps compute this independently and compare the results, so the encoding
 * is part of the contract exactly like the field list is.
 *
 * ```text
 * canonical = uint32be(byteLength(domain)) || domain,
 *             then for each field, in this order:
 *               uint32be(byteLength(fieldBytes)) || fieldBytes
 *             no separators, no trailing bytes.
 *
 *   domain                   : ASCII "fakexxx:contract:v1:intent"
 *                              (the FIRST framed field — §6.3.1 freezes the tag
 *                              and its position; an implementation that omits it
 *                              computes a different digest, not a different
 *                              reading)
 *   runId                    : UTF-8 bytes, verbatim
 *   attemptId                : UTF-8 bytes, verbatim
 *   profileRef               : UTF-8 bytes, verbatim
 *   scheduleRef              : UTF-8 bytes, verbatim
 *   requiredVerificationWire : ASCII decimal
 *   notBeforeEpochMs         : ASCII decimal
 *   deadlineEpochMs          : ASCII decimal
 *
 * acceptedIntentHash = lowercase hex of SHA-256(canonical)
 * ```
 *
 * "UTF-8 bytes, verbatim" is fail-closed: an unpaired surrogate rejects the
 * whole computation rather than being silently replaced (see
 * [CanonicalDigestV1.utf8]; §6.3.1 freezes this, or two malformed ids could
 * collapse onto one digest).
 *
 * ## KB-8: coordinates removed from the preimage
 *
 * `latitude` and `longitude` were removed from both [EnvironmentIntentV1] and
 * this preimage. The provider (Qianwangyou) is the sole coordinate authority;
 * Auto passes only item references (`profileRef`, `scheduleRef`), never
 * coordinates. The `acceptedIntentHash` binds the attempt to its schedule
 * identity, not to a coordinate the consumer has no authority to assert.
 *
 * ## Why length prefixes and not a separator
 *
 * Four of the fields are free-form strings. Joining with any fixed separator
 * lets a field absorb that separator and move a boundary: joined with `\n`,
 * `runId="a\nb", attemptId="c"` and `runId="a", attemptId="b\nc"` produce
 * **byte-identical** canonical forms, so two different intents share one
 * `acceptedIntentHash` and INV-23's binding is bypassed.
 *
 * Length prefixes make the encoding injective, so correctness stops depending on
 * the runtime accident that no field happened to contain the separator. Do not
 * revert to a separator scheme, and do not "fix" it by forbidding newlines in
 * refs — that would rest an invariant on input validation instead of on the
 * encoding.
 *
 * ## What must never be used as the digest source
 *
 * `toString()`, `hashCode()`, `Objects.hash()`, any JSON serialisation, and the
 * raw Parcel bytes are all forbidden: none of them is stable across versions or
 * processes.
 */
object CanonicalIntentDigestV1 {

    /** @return lowercase hex SHA-256 of the canonical encoding of [intent]. */
    fun compute(intent: EnvironmentIntentV1): String =
        CanonicalDigestV1.digest(CanonicalDigestV1.DOMAIN_INTENT, fields(intent))

    /**
     * The canonical byte string. Exposed so a conformance test can assert the
     * framing directly instead of only observing digests.
     */
    fun canonicalBytes(intent: EnvironmentIntentV1): ByteArray =
        CanonicalDigestV1.canonicalBytes(CanonicalDigestV1.DOMAIN_INTENT, fields(intent))

    private fun fields(intent: EnvironmentIntentV1): List<ByteArray> = listOf(
        CanonicalDigestV1.utf8(intent.runId),
        CanonicalDigestV1.utf8(intent.attemptId),
        CanonicalDigestV1.utf8(intent.profileRef),
        CanonicalDigestV1.utf8(intent.scheduleRef),
        CanonicalDigestV1.decimal(intent.requiredVerificationWire),
        CanonicalDigestV1.decimal(intent.notBeforeEpochMs),
        CanonicalDigestV1.decimal(intent.deadlineEpochMs),
    )

}

package io.github.terryyyc.fakexxx.contract.v1

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Canonical digest of an [EnvironmentIntentV1]. Frozen algorithm, spec §6.3.1.
 *
 * Both apps compute this independently and compare the results, so the encoding
 * is part of the contract exactly like the field list is.
 *
 * ```text
 * canonical = for each field, in this order:
 *               uint32be(byteLength(fieldBytes)) || fieldBytes
 *             no separators, no trailing bytes.
 *
 *   runId                    : UTF-8 bytes, verbatim
 *   attemptId                : UTF-8 bytes, verbatim
 *   profileRef               : UTF-8 bytes, verbatim
 *   scheduleRef              : UTF-8 bytes, verbatim
 *   latitude                 : ASCII fixed point, exactly 7 decimals, half-even,
 *                              minus sign kept, no '+', no exponent, no grouping
 *   longitude                : same
 *   requiredVerificationWire : ASCII decimal
 *   notBeforeEpochMs         : ASCII decimal
 *   deadlineEpochMs          : ASCII decimal
 *
 * acceptedIntentHash = lowercase hex of SHA-256(canonical)
 * ```
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
 *
 * ## Coordinate rendering
 *
 * 7 decimals is ~1.1 cm, far below [ContractV1.TRUSTED_LOCATION_TOLERANCE_METERS],
 * so quantisation can never cause a false negative while it does remove drift
 * from float-to-text differences.
 *
 * Rounding applies to the double's **exact** binary value. `BigDecimal(double)`
 * takes the exact value; `BigDecimal.valueOf(double)` would first go through the
 * shortest decimal representation, which is a second, language-specific rule. The
 * exact value is decided purely by the bits, which are what actually crossed the
 * Binder boundary. `BigDecimal` has no negative zero, so `-0.0` normalises to
 * `0.0000000` on its own.
 */
object CanonicalIntentDigestV1 {

    /** Decimal places used to render coordinates. Frozen. */
    const val COORDINATE_SCALE: Int = 7

    /** @return lowercase hex SHA-256 of the canonical encoding of [intent]. */
    fun compute(intent: EnvironmentIntentV1): String =
        sha256Hex(canonicalBytes(intent))

    /**
     * The canonical byte string. Exposed so a conformance test can assert the
     * framing directly instead of only observing digests.
     */
    fun canonicalBytes(intent: EnvironmentIntentV1): ByteArray {
        val out = ByteArrayOutputStream()
        appendFramed(out, intent.runId.toByteArray(Charsets.UTF_8))
        appendFramed(out, intent.attemptId.toByteArray(Charsets.UTF_8))
        appendFramed(out, intent.profileRef.toByteArray(Charsets.UTF_8))
        appendFramed(out, intent.scheduleRef.toByteArray(Charsets.UTF_8))
        appendFramed(out, fixedPoint7(intent.latitude).toByteArray(Charsets.US_ASCII))
        appendFramed(out, fixedPoint7(intent.longitude).toByteArray(Charsets.US_ASCII))
        appendFramed(out, intent.requiredVerificationWire.toString().toByteArray(Charsets.US_ASCII))
        appendFramed(out, intent.notBeforeEpochMs.toString().toByteArray(Charsets.US_ASCII))
        appendFramed(out, intent.deadlineEpochMs.toString().toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /** `uint32be(len) || bytes`. */
    private fun appendFramed(out: ByteArrayOutputStream, bytes: ByteArray) {
        val len = bytes.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes, 0, len)
    }

    /**
     * Fixed point with exactly [COORDINATE_SCALE] decimals, half-even, plain
     * notation. `-0.0` renders as `0.0000000`.
     */
    internal fun fixedPoint7(value: Double): String {
        require(value.isFinite()) { "coordinate must be finite, got $value" }
        return BigDecimal(value)
            .setScale(COORDINATE_SCALE, RoundingMode.HALF_EVEN)
            .toPlainString()
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

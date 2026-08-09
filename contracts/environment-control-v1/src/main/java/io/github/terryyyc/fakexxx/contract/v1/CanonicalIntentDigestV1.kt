package io.github.terryyyc.fakexxx.contract.v1

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Canonical digest of an [EnvironmentIntentV1].
 *
 * RED STAGE — this is the newline-joined encoding, kept only long enough for
 * `CanonicalIntentDigestV1Test` to demonstrate the collision it permits.
 */
object CanonicalIntentDigestV1 {

    fun compute(intent: EnvironmentIntentV1): String {
        val canonical = buildString {
            append(intent.runId).append('\n')
            append(intent.attemptId).append('\n')
            append(intent.profileRef).append('\n')
            append(intent.scheduleRef).append('\n')
            append(fixedPoint7(intent.latitude)).append('\n')
            append(fixedPoint7(intent.longitude)).append('\n')
            append(intent.requiredVerificationWire).append('\n')
            append(intent.notBeforeEpochMs).append('\n')
            append(intent.deadlineEpochMs)
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    internal fun fixedPoint7(value: Double): String =
        BigDecimal(value).setScale(7, RoundingMode.HALF_EVEN).toPlainString()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

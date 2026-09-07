package name.caiyao.fakegps.integration.v1

import java.security.MessageDigest

/**
 * Canonical digest of the complete QWY projection read inside an oracle window.
 * It deliberately includes no wall-clock or refresh cadence data: those do not
 * describe a semantic environment change. A producer digest mismatch is absence
 * of proof, never a reason to rewrite the local observation.
 */
object QwyObservedSemanticDigest {
    fun compute(
        ownerGeneration: Long,
        effective: EffectiveEnvironment,
        schedule: ScheduleSnapshot?,
    ): String {
        val framed = DurableFieldCodec.encode(
            listOf(
                "qwy-observed-semantic-v1",
                ownerGeneration.toString(),
                schedule?.scheduleId,
                schedule?.scheduleVersion?.toString(),
                schedule?.currentItemId,
                schedule?.exhausted?.toString(),
                effective.latitude?.toRawBits()?.toString(),
                effective.longitude?.toRawBits()?.toString(),
                effective.isMock?.toString(),
                effective.deliveryModeWire?.toString(),
                effective.verificationLevelWire.toString(),
                effective.environmentFingerprint,
            ),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(framed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

package name.caiyao.fakegps.integration.v1

/**
 * Durable per-lease high-water mark for independently verified OS samples.
 *
 * A PRE and POST observation may have the same environment fingerprint, but
 * they must not be the same cached GPS/network samples. Admission is strict
 * per source: every required provider timestamp must advance. The record is
 * durable so rebuilding the provider process cannot make an old sample new.
 */
internal class VerifiedObservationWatermarkStore(
    private val storage: DurableKv,
) {
    fun admit(
        leaseId: String,
        sourceElapsedRealtimeMs: Map<String, Long>,
        allowFirstUse: Boolean = true,
    ): Boolean = storage.transaction {
        if (leaseId.isBlank() ||
            sourceElapsedRealtimeMs.keys != SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES ||
            sourceElapsedRealtimeMs.values.any { it <= 0L }
        ) {
            return@transaction false
        }

        val key = "lease:$leaseId"
        val previousRaw = storage.read(NAMESPACE, key)
        if (previousRaw == null) {
            if (!allowFirstUse) return@transaction false
            storage.write(NAMESPACE, key, encode(sourceElapsedRealtimeMs))
            return@transaction true
        }

        val previous = decode(previousRaw) ?: return@transaction false
        val everySourceAdvanced = SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES.all { source ->
            sourceElapsedRealtimeMs.getValue(source) > previous.getValue(source)
        }
        if (!everySourceAdvanced) return@transaction false

        storage.write(NAMESPACE, key, encode(sourceElapsedRealtimeMs))
        true
    }

    private fun encode(sourceElapsedRealtimeMs: Map<String, Long>): String =
        DurableFieldCodec.encode(
            SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES.sorted().flatMap { source ->
                listOf(source, sourceElapsedRealtimeMs.getValue(source).toString())
            },
        )

    private fun decode(raw: String): Map<String, Long>? = runCatching {
        val fields = DurableFieldCodec.decodeNonNull(raw)
        if (fields.size != SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES.size * 2) {
            return@runCatching null
        }
        fields.chunked(2).associate { (source, elapsed) ->
            source to elapsed.toLong()
        }.takeIf { decoded ->
            decoded.keys == SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES &&
                decoded.values.all { it > 0L }
        }
    }.getOrNull()

    companion object {
        internal const val NAMESPACE = "integration.v1.observe.sample-watermarks"
    }
}

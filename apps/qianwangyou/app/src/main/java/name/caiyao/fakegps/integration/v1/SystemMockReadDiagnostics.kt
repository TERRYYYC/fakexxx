package name.caiyao.fakegps.integration.v1

/** Acquisition state, not a statement that the location is trusted. */
internal enum class SystemMockSourceReadStatus {
    SAMPLE,
    NO_SAMPLE,
    PROVIDER_QUERY_FAILED,
    CACHE_QUERY_FAILED,
    SAMPLE_EXTRACTION_FAILED,
    UNREPORTED,
}

internal enum class SystemMockReadFailure {
    SECURITY,
    ILLEGAL_ARGUMENT,
    OTHER,
    ;

    companion object {
        fun from(cause: Throwable): SystemMockReadFailure = when (cause) {
            is SecurityException -> SECURITY
            is IllegalArgumentException -> ILLEGAL_ARGUMENT
            else -> OTHER
        }
    }
}

/** Deliberately cannot carry coordinates, exception text, or an Android Location object. */
internal data class SystemMockSourceReadDiagnostic(
    val source: String,
    val status: SystemMockSourceReadStatus,
    val providerEnabled: Boolean? = null,
    val failure: SystemMockReadFailure? = null,
    val isMock: Boolean? = null,
    val sourceElapsedRealtimeMs: Long? = null,
)

/** Ephemeral result of one read. Never pass this raw-sample container to a logger. */
internal data class SystemMockReadSnapshot(
    val readbacks: List<SystemMockLocationReadback>,
    val sourceDiagnostics: List<SystemMockSourceReadDiagnostic> = emptyList(),
    val readerFailure: SystemMockReadFailure? = null,
)

internal enum class SystemMockSampleFreshness {
    UNASSESSED,
    BEFORE_PUBLISH,
    AT_OR_AFTER_PUBLISH,
}

internal data class SystemMockSourceEvaluationDiagnostic(
    val read: SystemMockSourceReadDiagnostic,
    val freshness: SystemMockSampleFreshness,
)

/** The diagnostic sink only receives this coordinate-free type, never trust/raw results. */
internal data class SystemMockEvaluationDiagnostics(
    val sources: List<SystemMockSourceEvaluationDiagnostic>,
    val publishAnchorElapsedRealtimeMs: Long,
    val readerFailure: SystemMockReadFailure? = null,
)

internal fun SystemMockReadSnapshot.evaluationDiagnostics(
    requiredSources: Set<String>,
    publishAnchorElapsedRealtimeMs: Long,
): SystemMockEvaluationDiagnostics = SystemMockEvaluationDiagnostics(
    sources = requiredSources.sorted().map { source ->
        val sample = readbacks.filter { it.source == source }.singleOrNull()
        val read = sourceDiagnostics.filter { it.source == source }.singleOrNull()
            ?: sample?.let {
                SystemMockSourceReadDiagnostic(
                    source, SystemMockSourceReadStatus.SAMPLE, it.providerEnabled,
                    isMock = it.isMock,
                    sourceElapsedRealtimeMs = it.observedAtElapsedRealtimeMs,
                )
            }
            ?: SystemMockSourceReadDiagnostic(source, SystemMockSourceReadStatus.UNREPORTED)
        val sourceTime = read.sourceElapsedRealtimeMs
        val freshness = when {
            publishAnchorElapsedRealtimeMs <= 0L || sourceTime == null ||
                read.status != SystemMockSourceReadStatus.SAMPLE -> SystemMockSampleFreshness.UNASSESSED
            sourceTime < publishAnchorElapsedRealtimeMs -> SystemMockSampleFreshness.BEFORE_PUBLISH
            else -> SystemMockSampleFreshness.AT_OR_AFTER_PUBLISH
        }
        SystemMockSourceEvaluationDiagnostic(read, freshness)
    },
    publishAnchorElapsedRealtimeMs = publishAnchorElapsedRealtimeMs,
    readerFailure = readerFailure,
)

internal object SystemMockDiagnosticFormatter {
    fun lines(diagnostics: SystemMockEvaluationDiagnostics): List<String> =
        diagnostics.sources.map { source ->
            val read = source.read
            // Legacy/injected readers can supply arbitrary strings. Only fixed provider names
            // may cross this boundary; never echo unknown names or exception messages.
            val sourceName = when (read.source) {
                "gps", "network" -> read.source
                else -> "other"
            }
            "source=$sourceName status=${read.status.name} " +
                "enabled=${read.providerEnabled ?: "unknown"} mock=${read.isMock ?: "unknown"} " +
                "source_elapsed_ms=${read.sourceElapsedRealtimeMs ?: "unknown"} " +
                "publish_anchor_ms=${diagnostics.publishAnchorElapsedRealtimeMs} " +
                "freshness=${source.freshness.name} failure=${read.failure?.name ?: "none"} " +
                "reader_failure=${diagnostics.readerFailure?.name ?: "none"}"
        }
}

package name.caiyao.fakegps.integration.v1

/**
 * Observation-side proof that every process writer currently shares the one
 * authoritative QWY lane for this exact local semantic digest.
 */
fun interface QwySemanticWriterReadiness {
    fun ensureReadyFor(semanticDigest: String): Boolean
}

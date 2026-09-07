package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1

/** A monotonic provider-owned observation window; wall time is deliberately absent. */
data class ObservationAdmissionWindow(
    val ownerGeneration: Long,
    val environmentRevision: Long,
)

/**
 * Bounded, non-evidence admission state for observation audit writes (#83).
 *
 * The bucket is an authorized caller principal plus lease. Its state is
 * overwritten only when the revision-owner's monotonic (generation, revision)
 * window advances. The audit stream remains the permanent evidence source: this
 * store has no evidence rows, no retention policy and no ability to mint FULL.
 *
 * A repeated operationId in the same window is deliberately rejected rather
 * than replayed: the v1 observation wire has no persisted replay payload, and
 * returning a newly projected observation with an old evidence ref would break
 * the evidence digest binding. Rejection happens before audit append.
 */
class ObservationAdmissionStore(
    private val storage: DurableKv,
) {
    companion object {
        /** Measured 64-row production-journal checkpoint; host-only, not an Android capacity claim. */
        const val MAX_REQUESTS_PER_WINDOW = 64
        private const val NAMESPACE = "integration.v1.observation.admission"
        private const val PREFIX = "bucket:"
    }

    fun admit(
        caller: CallerIdentity,
        leaseId: String,
        window: ObservationAdmissionWindow,
        operationId: String,
    ) = storage.transaction {
        val key = bucketKey(caller, leaseId)
        val prior = storage.read(NAMESPACE, key)?.let(::decode)
        val current = when {
            prior == null -> State(window, emptyList())
            prior.window == window -> prior
            window.isAfter(prior.window) -> State(window, emptyList())
            else -> unavailable("observation admission window moved backwards")
        }
        if (operationId in current.operationIds) unavailable("same-window observation retry is non-amplifying")
        if (current.operationIds.size >= MAX_REQUESTS_PER_WINDOW) {
            unavailable("observation admission exhausted for current monotonic window")
        }
        storage.write(NAMESPACE, key, encode(current.copy(operationIds = current.operationIds + operationId)))
    }

    internal fun count(caller: CallerIdentity, leaseId: String, window: ObservationAdmissionWindow): Int {
        val state = storage.read(NAMESPACE, bucketKey(caller, leaseId))?.let(::decode) ?: return 0
        return if (state.window == window) state.operationIds.size else 0
    }

    private fun bucketKey(caller: CallerIdentity, leaseId: String): String =
        PREFIX + DurableFieldCodec.encode(listOf(caller.applicationId, caller.signerDigest, leaseId))

    private fun encode(state: State): String = DurableFieldCodec.encode(
        listOf(state.window.ownerGeneration.toString(), state.window.environmentRevision.toString()) + state.operationIds,
    )

    private fun decode(encoded: String): State = try {
        val fields = DurableFieldCodec.decodeNonNull(encoded)
        check(fields.size >= 2) { "missing admission window" }
        val operations = fields.drop(2)
        check(operations.size <= MAX_REQUESTS_PER_WINDOW) { "admission count exceeds bound" }
        check(operations.distinct().size == operations.size) { "duplicate admission operation" }
        State(
            ObservationAdmissionWindow(fields[0].toLong(), fields[1].toLong()),
            operations,
        )
    } catch (failure: RuntimeException) {
        throw ContractException(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE, "corrupt observation admission state")
    }

    private fun unavailable(message: String): Nothing =
        throw ContractException(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE, message)

    private fun ObservationAdmissionWindow.isAfter(other: ObservationAdmissionWindow): Boolean =
        ownerGeneration > other.ownerGeneration ||
            (ownerGeneration == other.ownerGeneration && environmentRevision > other.environmentRevision)

    private data class State(
        val window: ObservationAdmissionWindow,
        val operationIds: List<String>,
    )
}

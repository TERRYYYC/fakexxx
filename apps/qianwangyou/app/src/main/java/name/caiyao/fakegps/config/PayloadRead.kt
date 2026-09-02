package name.caiyao.fakegps.config

/**
 * Outcome of reading the published payload back off disk.
 *
 * Three outcomes, deliberately not collapsed into a nullable String. "The read threw" and "nothing
 * was ever published" look identical as `null`, but mean opposite things to the user:
 *
 *   - [Absent]     — the hook genuinely has no config, so passthrough everywhere is correct.
 *   - [ReadError]  — we cannot see the config, but MainHook keeps its last-known-good Snapshot and
 *                    goes on spoofing. Reporting this as "尚未发布过配置，所有字段都会透传" tells
 *                    the user nothing is being spoofed while the hook is actively spoofing.
 *
 * The distinction only exists because the UI draws opposite conclusions from them.
 */
sealed interface PayloadRead {

    /** No payload has ever been written. */
    data object Absent : PayloadRead

    /** Bytes were read successfully. They may still fail to parse — that is [HookApplicability]'s call. */
    data class Raw(val text: String) : PayloadRead

    /** The read itself failed (permissions, corruption, I/O). We do not know what the hook is running. */
    data class ReadError(val cause: String) : PayloadRead

    /** The payload text when one was actually read, else null. */
    val textOrNull: String?
        get() = (this as? Raw)?.text
}

/** Fail-closed read of the independently persisted active publication pointer. */
sealed interface SemanticPublicationIdentityRead {
    data class Known(val activeProfileId: Long?) : SemanticPublicationIdentityRead
    data class ReadError(val cause: String) : SemanticPublicationIdentityRead
}

package name.caiyao.fakegps.probe

internal class HookAcceptanceRecoveryCoordinator(
    private val record: Record,
    private val transport: Transport,
) {
    interface Record {
        fun readPending(): String?
        fun savePending(previousPayload: String): Boolean
        fun clear(): Boolean
    }

    interface Transport {
        fun readCurrent(): String?
        fun publish(payload: String): Boolean
    }

    fun prepare(): Boolean {
        val previousPayload = transport.readCurrent() ?: return false
        return record.savePending(previousPayload)
    }

    fun recoverIfPending(): Boolean {
        val previousPayload = record.readPending() ?: return true
        if (!transport.publish(previousPayload)) return false
        return record.clear()
    }

    fun complete(): Boolean = record.clear()
}

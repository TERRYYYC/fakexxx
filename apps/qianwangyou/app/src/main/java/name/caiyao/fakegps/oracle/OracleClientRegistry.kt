package name.caiyao.fakegps.oracle

import android.os.IBinder

object OracleBridgePolicy {
    const val SYSTEM_UID: Int = 1_000

    @JvmStatic
    fun acceptsRegistrarCaller(callingUid: Int): Boolean = callingUid == SYSTEM_UID
}

fun interface OracleDeathLink {
    fun link(onDeath: () -> Unit)

    fun unlink() = Unit
}

data class OracleRegistration<T : Any>(
    val oracle: T,
    val deathLink: OracleDeathLink,
)

/** One system authority per QWY process; Binder death removes rather than preserving proof. */
class OracleClientRegistry<T : Any>(
    private val systemUid: Int = OracleBridgePolicy.SYSTEM_UID,
) {
    private data class Slot<T : Any>(
        val registration: OracleRegistration<T>,
        @Volatile var diedBeforePublish: Boolean = false,
    )

    private val lock = Any()
    private var current: Slot<T>? = null

    fun register(callingUid: Int, registration: OracleRegistration<T>): Boolean {
        if (callingUid != systemUid) return false
        val candidate = Slot(registration)
        try {
            registration.deathLink.link {
                synchronized(lock) {
                    candidate.diedBeforePublish = true
                    if (current === candidate) current = null
                }
            }
        } catch (_: RuntimeException) {
            return false
        }
        val publishable = synchronized(lock) {
            if (candidate.diedBeforePublish) null else PublishResult(current).also { current = candidate }
        } ?: run {
            synchronized(lock) { if (current === candidate) current = null }
            try { registration.deathLink.unlink() } catch (_: RuntimeException) { }
            return false
        }
        try { publishable.previous?.registration?.deathLink?.unlink() } catch (_: RuntimeException) { }
        return true
    }

    private data class PublishResult<T : Any>(val previous: Slot<T>?)

    fun current(): T? = synchronized(lock) { current?.registration?.oracle }

    fun clear() {
        val old = synchronized(lock) { current.also { current = null } }
        try { old?.registration?.deathLink?.unlink() } catch (_: RuntimeException) { }
    }

    companion object {
        @JvmField val process = OracleClientRegistry<IAuthoritativeContinuityOracle>()

        @JvmStatic
        fun binderRegistration(oracle: IAuthoritativeContinuityOracle): OracleRegistration<IAuthoritativeContinuityOracle> =
            OracleRegistration(oracle, BinderOracleDeathLink(oracle.asBinder()))
    }
}

private class BinderOracleDeathLink(private val binder: IBinder) : OracleDeathLink {
    private var recipient: IBinder.DeathRecipient? = null

    override fun link(onDeath: () -> Unit) {
        val next = IBinder.DeathRecipient { onDeath() }
        binder.linkToDeath(next, 0)
        recipient = next
    }

    override fun unlink() {
        recipient?.let { binder.unlinkToDeath(it, 0) }
        recipient = null
    }
}

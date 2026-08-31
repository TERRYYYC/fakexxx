package name.caiyao.fakegps.oracle

import android.os.IBinder

object OracleBridgePolicy {
    const val SYSTEM_UID: Int = 1_000

    @JvmStatic
    fun acceptsRegistrarCaller(callingUid: Int): Boolean = callingUid == SYSTEM_UID

    @JvmStatic
    fun acceptsQwyCaller(callingUid: Int, resolvedQwyUid: Int?): Boolean =
        resolvedQwyUid != null && resolvedQwyUid >= 0 && callingUid == resolvedQwyUid
}

fun interface OracleDeathLink {
    fun link(onDeath: () -> Unit)

    fun unlink() = Unit
}

data class OracleRegistration<T : Any>(
    val oracle: T,
    val deathLink: OracleDeathLink,
)

/**
 * One authority per QWY process. Replacement links the new Binder before publishing it; a failed
 * link cannot evict the still-live producer, and a stale death callback cannot clear its successor.
 */
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

        val publish = synchronized(lock) {
            if (candidate.diedBeforePublish) {
                null
            } else {
                val previous = current
                current = candidate
                PublishResult(previous)
            }
        }
        if (publish == null) {
            try {
                registration.deathLink.unlink()
            } catch (_: RuntimeException) {
                // It already died during link; authority was never published.
            }
            return false
        }
        try {
            publish.previous?.registration?.deathLink?.unlink()
        } catch (_: RuntimeException) {
            // The stale handle is already unpublished; unlink failure cannot restore authority.
        }
        return true
    }

    private data class PublishResult<T : Any>(val previous: Slot<T>?)

    fun current(): T? = synchronized(lock) { current?.registration?.oracle }

    fun clear() {
        val old = synchronized(lock) {
            val value = current
            current = null
            value
        }
        try {
            old?.registration?.deathLink?.unlink()
        } catch (_: RuntimeException) {
            // Authority is already cleared.
        }
    }

    companion object {
        @JvmField
        val process = OracleClientRegistry<IAuthoritativeContinuityOracle>()

        @JvmStatic
        fun binderRegistration(oracle: IAuthoritativeContinuityOracle): OracleRegistration<IAuthoritativeContinuityOracle> =
            OracleRegistration(oracle, BinderOracleDeathLink(oracle.asBinder()))
    }
}

private class BinderOracleDeathLink(
    private val binder: IBinder,
) : OracleDeathLink {
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

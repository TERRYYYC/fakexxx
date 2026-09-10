package name.caiyao.fakegps.oracle

import android.os.IBinder

object OracleBridgePolicy {
    const val SYSTEM_UID: Int = 1_000

    @JvmStatic
    fun acceptsRegistrarCaller(callingUid: Int): Boolean = callingUid == SYSTEM_UID

    @JvmStatic
    fun acceptsQwyCaller(callingUid: Int, expectedQwyUid: Int?): Boolean =
        expectedQwyUid != null && expectedQwyUid >= 0 && callingUid == expectedQwyUid
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
 * Registration-arrival/departure notification (#155). [onChanged] receives the
 * newly published oracle, or null when the registry lost it (explicit clear or
 * binder death). Callbacks run OUTSIDE the registry lock — a listener may make
 * Binder calls back into the publisher — and must tolerate reordering against
 * later notifications; staleness is the listener's to check via [OracleClientRegistry.current].
 */
fun interface OracleRegistryListener<T> {
    fun onChanged(oracle: T?)
}

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
    private val listeners = mutableListOf<OracleRegistryListener<T>>()

    /**
     * Subscribes to arrival/departure. An oracle registered BEFORE this call is
     * replayed immediately, so a late-attaching consumer (the session driver at
     * first provider touch) cannot miss an arrival that already happened.
     */
    fun addListener(listener: OracleRegistryListener<T>) {
        val present = synchronized(lock) {
            listeners += listener
            current?.registration?.oracle
        }
        if (present != null) listener.onChanged(present)
    }

    fun removeListener(listener: OracleRegistryListener<T>) {
        synchronized(lock) { listeners -= listener }
    }

    private fun notifyChanged(oracle: T?) {
        val targets = synchronized(lock) { listeners.toList() }
        targets.forEach { listener -> listener.onChanged(oracle) }
    }

    fun register(callingUid: Int, registration: OracleRegistration<T>): Boolean {
        if (callingUid != systemUid) return false
        val candidate = Slot(registration)
        try {
            registration.deathLink.link {
                var removed: T? = null
                synchronized(lock) {
                    candidate.diedBeforePublish = true
                    if (current === candidate) {
                        current = null
                        removed = candidate.registration.oracle
                    }
                }
                // Outside the registry lock: listeners may make Binder calls.
                if (removed != null) notifyChanged(null)
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
        notifyChanged(candidate.registration.oracle)
        return true
    }

    private data class PublishResult<T : Any>(val previous: Slot<T>?)

    fun current(): T? = synchronized(lock) { current?.registration?.oracle }

    fun clear() {
        val old = synchronized(lock) { current.also { current = null } }
        try { old?.registration?.deathLink?.unlink() } catch (_: RuntimeException) { }
        if (old != null) notifyChanged(null)
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

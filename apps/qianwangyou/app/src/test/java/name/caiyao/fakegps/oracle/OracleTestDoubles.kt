package name.caiyao.fakegps.oracle

import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.io.FileDescriptor

/**
 * JVM Binder identity double: working death-link bookkeeping, no transport.
 * Identity (equals/hashCode) is object identity, like a local Binder object.
 */
open class FakeBinder : IBinder {
    private val deaths = mutableListOf<IBinder.DeathRecipient>()
    var dead = false
        private set

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        if (dead) throw IllegalStateException("already dead")
        deaths += recipient
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
        deaths.remove(recipient)

    /** Fires the linked death recipients, as a real binder death would. */
    fun die() {
        dead = true
        deaths.toList().forEach { it.binderDied() }
    }

    override fun pingBinder(): Boolean = !dead
    override fun isBinderAlive(): Boolean = !dead
    override fun queryLocalInterface(descriptor: String): IInterface? = null
    override fun getInterfaceDescriptor(): String? = null
    override fun dump(fd: FileDescriptor, args: Array<out String>?) = Unit
    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = Unit
    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
}

/** Death-link double that records the linked callback so tests can simulate death. */
class FakeOracleDeathLink : OracleDeathLink {
    private var onDeath: (() -> Unit)? = null

    override fun link(onDeath: () -> Unit) {
        this.onDeath = onDeath
    }

    override fun unlink() {
        onDeath = null
    }

    fun die() = onDeath?.invoke()
}

/**
 * Recording [IAuthoritativeContinuityOracle] double. With no delegate it only
 * records; the #155 integration test delegates the same methods into the real
 * [name.caiyao.fakegps.hook.oracle.SystemServerOracleState].
 */
open class RecordingOracle(private val binder: FakeBinder = FakeBinder()) :
    IAuthoritativeContinuityOracle {

    data class FinishCall(val rawToken: Long, val changed: Boolean, val uncertain: Boolean, val afterDigest: String?)

    val registrations = mutableListOf<Pair<String?, IBinder?>>()
    val begins = mutableListOf<Triple<String?, String?, IBinder?>>()
    val finishes = mutableListOf<FinishCall>()

    /** One-shot begin failure (simulates oracle-side session rejection). */
    var failNextBegin = false

    override fun snapshot(): Bundle? = null

    override fun registerQwySession(semanticDigest: String?, clientDeathToken: IBinder?) {
        registrations += semanticDigest to clientDeathToken
    }

    override fun beginQwySemanticMutation(
        mutationId: String?,
        beforeDigest: String?,
        clientDeathToken: IBinder?,
    ): Long {
        begins += Triple(mutationId, beforeDigest, clientDeathToken)
        if (failNextBegin) {
            failNextBegin = false
            throw IllegalStateException("QWY session is absent or semantic baseline changed")
        }
        return begins.size.toLong()
    }

    override fun finishQwySemanticMutation(
        token: Long,
        changed: Boolean,
        uncertain: Boolean,
        afterDigest: String?,
    ) {
        finishes += FinishCall(token, changed, uncertain, afterDigest)
    }

    override fun asBinder(): IBinder = binder
}

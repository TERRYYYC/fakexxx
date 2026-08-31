package name.caiyao.fakegps.integration.v1

import android.os.Binder
import android.os.IBinder
import name.caiyao.fakegps.oracle.IAuthoritativeContinuityOracle
import name.caiyao.fakegps.oracle.OracleClientRegistry

/** Binder-backed endpoint provider with stable wrapper identity per authority. */
class BinderQwySemanticMutationEndpointProvider : QwySemanticMutationEndpointProvider {
    private var lastOracle: IAuthoritativeContinuityOracle? = null
    private var lastEndpoint: QwySemanticMutationEndpoint? = null

    @Synchronized
    override fun current(): QwySemanticMutationEndpoint? {
        val oracle = OracleClientRegistry.process.current() ?: run {
            lastOracle = null
            lastEndpoint = null
            return null
        }
        if (oracle === lastOracle) return lastEndpoint
        return BinderQwySemanticMutationEndpoint(oracle).also {
            lastOracle = oracle
            lastEndpoint = it
        }
    }
}

class BinderQwySemanticClientDeathToken internal constructor(
    internal val binder: IBinder = Binder(),
) : QwySemanticClientDeathToken {
    override fun isAlive(): Boolean = binder.isBinderAlive
}

object BinderQwySemanticClientDeathTokenFactory : QwySemanticClientDeathTokenFactory {
    override fun create(): QwySemanticClientDeathToken = BinderQwySemanticClientDeathToken()
}

private class BinderQwySemanticMutationEndpoint(
    private val oracle: IAuthoritativeContinuityOracle,
) : QwySemanticMutationEndpoint {
    override fun registerCurrentSession(
        semanticDigest: String,
        clientDeathToken: QwySemanticClientDeathToken,
    ) {
        oracle.registerQwySession(semanticDigest, clientDeathToken.requireBinder())
    }

    override fun beginMutation(
        mutationId: String,
        beforeDigest: String,
        clientDeathToken: QwySemanticClientDeathToken,
    ): Long = oracle.beginQwySemanticMutation(
        mutationId,
        beforeDigest,
        clientDeathToken.requireBinder(),
    )

    override fun finishMutation(
        token: Long,
        changed: Boolean,
        uncertain: Boolean,
        afterDigest: String?,
    ) {
        oracle.finishQwySemanticMutation(token, changed, uncertain, afterDigest)
    }

    private fun QwySemanticClientDeathToken.requireBinder(): IBinder =
        (this as? BinderQwySemanticClientDeathToken)?.binder
            ?: throw IllegalArgumentException("Binder oracle requires a Binder death token")
}

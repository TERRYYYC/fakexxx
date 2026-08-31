package com.example.cellrebelauto.recovery

import com.example.cellrebelauto.automation.ProviderPrincipal

/**
 * One executor and the one provider identity it can contact. Production composition accepts this
 * single object instead of an independently supplied applicationId plus a generic executor, so
 * the trust principal and Binder target cannot drift apart while a run is being assembled.
 */
interface ProviderScopedExternalApplyExecutor : ExternalApplyExecutor {
    val targetApplicationId: String

    companion object {
        /**
         * Test-only compatibility seam for coordinator fixtures. This attaches an asserted target
         * but is deliberately NOT a production capability; production composition accepts only a
         * live [ProviderExecutorAcquisition] issued by [ProviderExecutorRegistry].
         */
        internal fun wrap(
            targetApplicationId: String,
            delegate: ExternalApplyExecutor,
        ): ProviderScopedExternalApplyExecutor {
            val known = ProviderPrincipal.requireKnownApplicationId(targetApplicationId)
            if (delegate is ProviderScopedExternalApplyExecutor) {
                require(delegate.targetApplicationId == known) {
                    "executor target ${delegate.targetApplicationId} does not match scoped principal $known"
                }
                return delegate
            }
            return DelegatingProviderScopedExternalApplyExecutor(known, delegate)
        }
    }
}

private class DelegatingProviderScopedExternalApplyExecutor(
    override val targetApplicationId: String,
    private val delegate: ExternalApplyExecutor,
) : ProviderScopedExternalApplyExecutor, ExternalApplyExecutor by delegate

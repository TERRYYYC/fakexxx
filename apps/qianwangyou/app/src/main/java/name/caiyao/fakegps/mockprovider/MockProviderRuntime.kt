package name.caiyao.fakegps.mockprovider

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import name.caiyao.fakegps.data.SpoofSettings

object MockProviderRuntime {
    fun enableSystemMock(context: Context) {
        val intent = serviceIntent(context, MockProviderServiceContract.ACTION_START_FROM_EFFECTIVE_PROFILE)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { MockProviderStatusStore.publish(MockProviderState.Failed(describe(it))) }
    }

    fun useHookAndStopSystemMock(context: Context) {
        val intent = serviceIntent(context, MockProviderServiceContract.ACTION_STOP_AND_USE_HOOK)
        runCatching { context.startService(intent) }
            .onFailure { MockProviderStatusStore.publish(MockProviderState.Failed(describe(it))) }
    }

    fun reconcileOnAppLaunch(context: Context) {
        val settings = SpoofSettings.getInstance(context)
        when (
            LocationDeliveryStartupPlan.commandFor(
                settings.readLocationDeliveryMode(),
                settings.isMockProviderCleanupRequired(),
            )
        ) {
            MockProviderCommand.StartFromEffectiveProfile -> enableSystemMock(context)
            MockProviderCommand.StopAndUseHook -> useHookAndStopSystemMock(context)
            null -> MockProviderStatusStore.publish(MockProviderState.Idle)
            else -> error("startup plan returned a non-startup command")
        }
    }

    private fun serviceIntent(context: Context, action: String) =
        Intent(context, MockProviderService::class.java).setAction(action)

    private fun describe(failure: Throwable): String =
        failure.message ?: failure.javaClass.simpleName
}

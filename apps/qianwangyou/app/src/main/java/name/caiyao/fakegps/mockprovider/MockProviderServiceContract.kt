package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.data.LocationDeliveryMode

sealed interface MockProviderCommand {
    data object StartFromEffectiveProfile : MockProviderCommand
    data object StopAndUseHook : MockProviderCommand
    data object CleanupRuntimeOnly : MockProviderCommand
    data class Rejected(val message: String) : MockProviderCommand
}

object MockProviderServiceContract {
    const val ACTION_START_FROM_EFFECTIVE_PROFILE =
        "name.caiyao.fakegps.action.START_SYSTEM_MOCK_FROM_EFFECTIVE_PROFILE"
    const val ACTION_STOP_AND_USE_HOOK =
        "name.caiyao.fakegps.action.STOP_SYSTEM_MOCK_AND_USE_HOOK"

    fun decode(action: String?): MockProviderCommand = when (action) {
        null -> MockProviderCommand.CleanupRuntimeOnly
        ACTION_START_FROM_EFFECTIVE_PROFILE -> MockProviderCommand.StartFromEffectiveProfile
        ACTION_STOP_AND_USE_HOOK -> MockProviderCommand.StopAndUseHook
        else -> MockProviderCommand.Rejected("unknown service action: $action")
    }
}

object LocationDeliveryStartupPlan {
    fun commandFor(
        mode: LocationDeliveryMode,
        cleanupRequired: Boolean,
    ): MockProviderCommand? {
        if (cleanupRequired) return MockProviderCommand.StopAndUseHook
        return when (mode) {
            LocationDeliveryMode.HOOK -> null
            LocationDeliveryMode.SYSTEM_MOCK -> MockProviderCommand.StartFromEffectiveProfile
        }
    }
}

enum class ProviderApiFamily {
    Legacy,
    Modern;

    companion object {
        fun forSdk(sdkInt: Int): ProviderApiFamily =
            if (sdkInt >= 31) Modern else Legacy
    }
}

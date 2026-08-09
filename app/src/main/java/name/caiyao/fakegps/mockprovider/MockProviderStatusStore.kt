package name.caiyao.fakegps.mockprovider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MockProviderStatusStore {
    private val mutableState = MutableStateFlow<MockProviderState>(MockProviderState.Idle)
    val state: StateFlow<MockProviderState> = mutableState.asStateFlow()

    fun publish(state: MockProviderState) {
        mutableState.value = state
    }
}

package name.caiyao.fakegps.ui.screen.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileSummary
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore

data class TapPoint(val lat: Double, val lon: Double)

class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(AppDatabase.getInstance(app), app)

    val profiles: StateFlow<List<ProfileSummary>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profileCount: StateFlow<Int> = repo.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _tappedPoint = MutableStateFlow<TapPoint?>(null)
    val tappedPoint: StateFlow<TapPoint?> = _tappedPoint

    fun onMapTap(lat: Double, lon: Double) {
        _tappedPoint.value = TapPoint(lat, lon)
    }

    fun clearTap() {
        _tappedPoint.value = null
    }

    /** Resolve from the runtime owners at click time; map composition must not cache this answer. */
    fun resolveRecenterTarget(
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): MapRecenterTarget = MapRecenterTargetResolver.resolve(
        read = ConfigPrefsSync.readPublished(getApplication()),
        providerState = MockProviderStatusStore.state.value,
        currentHour = currentHour,
    )

    fun deleteAll() {
        viewModelScope.launch { repo.deleteAll() }
    }
}

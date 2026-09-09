package name.caiyao.fakegps.ui.screen.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.hook.BaselineExtractionGuard
import name.caiyao.fakegps.motion.RouteSummary
import name.caiyao.fakegps.ui.SingleFlightGate
import name.caiyao.fakegps.verify.DeviceObserver
import name.caiyao.fakegps.verify.ObservationScope

class ProfileEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(AppDatabase.getInstance(app), app)

    private val _fieldValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val fieldValues: StateFlow<Map<String, String>> = _fieldValues

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    // Per-ViewModel ownership; a cleared scope and its claim cannot leak into another editor.
    private val saveGate = SingleFlightGate()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _fieldErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val fieldErrors: StateFlow<Map<String, String>> = _fieldErrors

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /**
     * What this device currently reports, keyed by dbColumn.
     *
     * Shown beside each input because a spoofed value is only verifiable if it DIFFERS from the real
     * one — with an empty form and no reference, users could not tell whether the value they typed
     * was distinguishable from the network they were already on.
     */
    private val _reference = MutableStateFlow<Map<String, String>>(emptyMap())
    val reference: StateFlow<Map<String, String>> = _reference

    /** Whether [reference] holds real device values or values this process already spoofs. */
    val scope: ObservationScope = ObservationScope.current()

    private var editingId: Long = 0L
    private var editingNameOverride: String? = null

    /**
     * Set by the first [updateField] and never cleared: the operator has touched the draft, so a
     * DB read that was launched before that edit and finishes after it must NOT overwrite
     * [_fieldValues] with the stored row. Without this guard, a load landing in that window
     * silently reverts the draft (an "不上报" toggle included) and the eventual save publishes a
     * byte-identical payload — the exact zero-effect save observed in issue #127. Identity/route
     * metadata (editingId/editingNameOverride/editingRouteWaypointsJson) is NOT draft state and
     * always applies, so a save still updates the edited row instead of inserting a duplicate.
     */
    private var draftDirty = false

    /**
     * P3.1 运动链: the profile's route column is NOT an editable text field — it is owned by the
     * route CSV import — so the editor carries it opaquely and MUST hand it back on save (a plain
     * round-trip through the field draft would silently strip it, turning a route profile into a
     * single point on the first unrelated edit).
     */
    private var editingRouteWaypointsJson: String? = null

    private val _routeSummary = MutableStateFlow<RouteSummary?>(null)
    val routeSummary: StateFlow<RouteSummary?> = _routeSummary

    fun load(profileId: Long, defaultLat: Double, defaultLon: Double) {
        viewModelScope.launch {
            runCatching {
                if (profileId > 0) {
                    val entity = repo.getById(profileId)
                    if (entity != null) {
                        editingId = entity.id
                        editingNameOverride = profileNameOverride(entity)
                        editingRouteWaypointsJson = entity.routeWaypointsJson
                        _routeSummary.value = RouteSummary.of(entity.routeWaypointsJson)
                        if (draftDirty) return@runCatching
                        _fieldValues.value = runCatching { entityToMap(entity) }
                            .getOrElse {
                                _notice.value =
                                    "档案中的不上报元数据已损坏或来自不兼容版本；已保留普通字段，请检查后重新保存"
                                entityToMap(entity.copy(unavailableFields = null))
                            }
                        _fieldErrors.value = ProfileFieldDraft.validationErrors(_fieldValues.value)
                        return@runCatching
                    }
                }
                editingId = 0L
                editingNameOverride = null
                editingRouteWaypointsJson = null
                _routeSummary.value = null
                _fieldValues.value = mapOf(
                    "latitude" to defaultLat.toString(),
                    "longitude" to defaultLon.toString(),
                )
                _fieldErrors.value = emptyMap()
            }.onSuccess {
                refreshReference(_fieldValues.value)
            }.onFailure { failure ->
                _notice.value = "档案读取失败：${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    fun updateField(column: String, value: String) {
        draftDirty = true
        val previousRouting = DeviceObserver.wcdmaDbmColumn(referenceColumns(_fieldValues.value))
        _fieldValues.value = ProfileFieldDraft.update(_fieldValues.value, column, value)
        _fieldErrors.value = ProfileFieldDraft.validationErrors(_fieldValues.value)
        _notice.value = null
        val nextRouting = DeviceObserver.wcdmaDbmColumn(referenceColumns(_fieldValues.value))
        if (previousRouting != nextRouting) refreshReference(_fieldValues.value)
    }

    /**
     * Emitted only when a save both succeeded AND was requested with "保存并验证".
     * Kept separate from [saved] so a failed publish cannot navigate anywhere — see
     * [postSaveAction].
     */
    private val _verifyRequested = MutableStateFlow(false)
    val verifyRequested: StateFlow<Boolean> = _verifyRequested

    fun saveAndVerify() = save(thenVerify = true)

    fun save(thenVerify: Boolean = false) {
        if (!saveGate.tryStart()) return
        _saving.value = true
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val values = _fieldValues.value
                val errors = ProfileFieldDraft.validationErrors(values)
                _fieldErrors.value = errors
                if (errors.isNotEmpty()) {
                    _notice.value = "有 ${errors.size} 个字段格式无效，尚未保存"
                    return@launch
                }
                runCatching {
                    val entity = mapToEntity(values, editingId, editingNameOverride)
                        .copy(routeWaypointsJson = editingRouteWaypointsJson)
                    val result = repo.save(entity)
                    editingId = result.id
                    when (postSaveAction(result.published, thenVerify)) {
                        PostSaveAction.VERIFY -> _verifyRequested.value = true
                        PostSaveAction.BACK -> _saved.value = true
                        PostSaveAction.STAY ->
                            _notice.value =
                                "档案已写入数据库，但未发布给 Hook；当前目标 App 仍使用上一份配置"
                    }
                }.onFailure { failure ->
                    _notice.value = "保存失败：${failure.message ?: failure.javaClass.simpleName}"
                }
            } finally {
                // This gate belongs to this ViewModel. The default viewModelScope dispatcher is
                // main, so saving=false and release are one non-suspending UI transition.
                _saving.value = false
                saveGate.finish()
            }
        }
    }

    private fun refreshReference(values: Map<String, String>) {
        val configuredColumns = referenceColumns(values)
        viewModelScope.launch(Dispatchers.IO) {
            _reference.value = runCatching {
                val observe = {
                    DeviceObserver(
                        getApplication(),
                        configuredColumns = configuredColumns,
                    ).observe().values
                }
                if (scope == ObservationScope.SELF_HOOKED) {
                    BaselineExtractionGuard.call(observe)
                } else {
                    observe()
                }
            }.getOrDefault(emptyMap())
        }
    }
}

internal fun referenceColumns(values: Map<String, String>): Set<String> =
    values.filterValues { it.isNotBlank() }.keys

internal fun entityToMap(entity: ProfileEntity): Map<String, String> =
    ProfileEntityCodec.toDraft(entity)

internal fun mapToEntity(
    draft: Map<String, String>,
    id: Long,
    addname: String? = null,
): ProfileEntity {
    val split = ProfileFieldDraft.split(draft)
    val normalized = split.values + split.unavailable.associateWith {
        ProfileEntityCodec.UNAVAILABLE_TOKEN
    }
    return ProfileEntityCodec.fromDraft(normalized, id = id, addname = addname)
}

internal fun profileNameOverride(entity: ProfileEntity): String? = entity.addname?.takeUnless {
    it == ProfileEntityCodec.generatedName(entity.latitude, entity.longitude)
}

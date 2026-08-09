package name.caiyao.fakegps.ui.screen.collection

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileSummary
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.importer.ImportIssueCode
import name.caiyao.fakegps.data.importer.ProfileArchiveParser
import name.caiyao.fakegps.data.importer.ProfileImportAnalysis
import name.caiyao.fakegps.data.importer.ProfileImportIssue
import name.caiyao.fakegps.data.importer.ProfileImportTemplate
import name.caiyao.fakegps.data.repository.ProfileRepository

class CollectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(AppDatabase.getInstance(app), app)
    private val parser = ProfileArchiveParser()
    private var importGeneration = 0L
    private var parseJob: Job? = null
    private val publicationRevision = MutableStateFlow(0L)

    private val _importState = MutableStateFlow<ProfileImportUiState>(ProfileImportUiState.Idle)
    val importState: StateFlow<ProfileImportUiState> = _importState

    private val _templateSaveState =
        MutableStateFlow<ProfileTemplateSaveState>(ProfileTemplateSaveState.Idle)
    val templateSaveState: StateFlow<ProfileTemplateSaveState> = _templateSaveState

    val profiles: StateFlow<List<ProfileSummary>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Id of the row represented by the actual published payload, or null when none matches.
     *
     * Import deliberately does not publish, so guessing from Room order would mark the first row of
     * an empty-database import as active even though the hook still has no config. Matching the
     * published bytes also keeps stale/failed publication states from receiving a false badge.
     */
    val effectiveProfileId: StateFlow<Long?> = combine(
        repo.observeEntities(),
        publicationRevision,
    ) { entities, _ ->
        PublishedProfileMatcher.effectiveProfileId(
            entities,
            ConfigPrefsSync.readPublished(getApplication()),
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun delete(id: Long) {
        viewModelScope.launch {
            repo.deleteById(id)
            publicationRevision.value++
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            repo.deleteAll()
            publicationRevision.value++
        }
    }

    fun previewImport(uri: Uri) {
        if (_importState.value is ProfileImportUiState.Importing) return
        parseJob?.cancel()
        val generation = ++importGeneration
        _importState.value = ProfileImportReducer.start(generation, "所选文件")
        parseJob = viewModelScope.launch {
            val (fileName, analysis) = withContext(Dispatchers.IO) {
                val resolvedName = resolveDisplayName(uri)
                val result = runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        parser.parse(resolvedName, input)
                    } ?: throw IOException("无法打开所选文件")
                }.getOrElse { failure ->
                    ProfileImportAnalysis.Invalid(
                        listOf(
                            ProfileImportIssue(
                                ImportIssueCode.MALFORMED_FILE,
                                failure.message ?: "文件读取失败",
                            ),
                        ),
                    )
                }
                resolvedName to result
            }
            _importState.value = ProfileImportReducer.analysis(
                current = _importState.value,
                generation = generation,
                fileName = fileName,
                result = analysis,
            )
        }
    }

    fun confirmImport() {
        val begin = ProfileImportReducer.beginImport(_importState.value) ?: return
        _importState.value = begin.state
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repo.importAll(begin.records) } }
                .onSuccess { result ->
                    _importState.value = ProfileImportReducer.imported(_importState.value, result)
                }
                .onFailure { failure ->
                    _importState.value = ProfileImportReducer.failed(
                        _importState.value,
                        failure.message ?: failure.javaClass.simpleName,
                    )
                }
        }
    }

    fun dismissImport() {
        if (_importState.value is ProfileImportUiState.Importing) return
        parseJob?.cancel()
        importGeneration++
        _importState.value = ProfileImportUiState.Idle
    }

    fun saveImportTemplate(uri: Uri) {
        if (!ProfileTemplateSaveReducer.canStart(_templateSaveState.value)) return
        _templateSaveState.value = ProfileTemplateSaveState.Saving
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?.use(ProfileImportTemplate::writeTo)
                        ?: throw IOException("无法创建模板文件")
                }
            }
            _templateSaveState.value = result.fold(
                onSuccess = { ProfileTemplateSaveState.Success },
                onFailure = { failure ->
                    ProfileTemplateSaveState.Failure(failure.message ?: "模板保存失败")
                },
            )
        }
    }

    fun dismissTemplateSaveResult() {
        _templateSaveState.value = ProfileTemplateSaveReducer.dismiss(_templateSaveState.value)
    }

    private fun resolveDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "所选文件"
    }
}

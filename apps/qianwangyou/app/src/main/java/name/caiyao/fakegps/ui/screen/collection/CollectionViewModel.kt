package name.caiyao.fakegps.ui.screen.collection

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
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
import name.caiyao.fakegps.data.DownloadCsvArchive
import name.caiyao.fakegps.data.DownloadCsvScanner
import name.caiyao.fakegps.data.ImportScanLog
import name.caiyao.fakegps.data.importer.ImportIssueCode
import name.caiyao.fakegps.data.importer.ProfileArchiveParser
import name.caiyao.fakegps.data.importer.ProfileImportAnalysis
import name.caiyao.fakegps.data.importer.ProfileImportIssue
import name.caiyao.fakegps.data.importer.ProfileImportTemplate
import name.caiyao.fakegps.data.repository.ProfileRepository

// @JvmOverloads is load-bearing: ViewModelProvider's AndroidViewModelFactory reflectively calls
// the single-(Application) constructor. Without it the repoOverride default param hides that
// ctor and tapping 收藏档案 crashes with NoSuchMethodException — found on device by BOTH the
// emulator and mi14 verification threads (2026-09-07); JVM tests construct directly and never
// exercise the reflective path.
class CollectionViewModel @JvmOverloads constructor(
    app: Application,
    // Robolectric oracle seam: tests inject a repository over an in-memory DB with a recording
    // publisher; production keeps the singleton DB + real ConfigPrefsSync publish chain.
    repoOverride: ProfileRepository? = null,
) : AndroidViewModel(app) {

    private val repo = repoOverride ?: ProfileRepository(AppDatabase.getInstance(app), app)
    private val parser = ProfileArchiveParser()
    private val scanLog = ImportScanLog(app)
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

    // ---- P0.1-3: Download/ 未导入档案的发现提示条 ----

    /** Download/ 里从未导入过（且未被关闭）的 CSV 候选；空 = 无提示条。 */
    private val _downloadCandidates = MutableStateFlow<List<DownloadCsvScanner.Entry>>(emptyList())
    val downloadCandidates: StateFlow<List<DownloadCsvScanner.Entry>> = _downloadCandidates

    init {
        refreshDownloadCandidates()
    }

    fun refreshDownloadCandidates() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val scanned = DownloadCsvArchive.scan(getApplication())
                DownloadCsvScanner.newCandidates(scanned, scanLog.seen())
            }
            _downloadCandidates.value = entries
        }
    }

    /** 提示条的关闭（或该文件的导入完成）都会写入指纹台账，提示只出现一次。 */
    fun dismissDownloadCandidates() {
        val seen = _downloadCandidates.value.map { it.fingerprint }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { scanLog.markSeen(seen) }
            _downloadCandidates.value = emptyList()
        }
    }

    /** 从扫描结果直达导入（跳过 SAF 翻找）：仅当候选带本地路径时可开。 */
    fun previewDownloadCsv(entry: DownloadCsvScanner.Entry) {
        val path = entry.localPath ?: return
        if (_importState.value is ProfileImportUiState.Importing) return
        parseJob?.cancel()
        val generation = ++importGeneration
        _importState.value = ProfileImportReducer.start(generation, entry.displayName)
        parseJob = viewModelScope.launch {
            val analysis = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = File(path).readBytes()
                    pendingFingerprint = DownloadCsvScanner.fingerprintOf(bytes)
                    parser.parse(entry.displayName, bytes.inputStream())
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
            }
            _importState.value = ProfileImportReducer.analysis(
                current = _importState.value,
                generation = generation,
                fileName = entry.displayName,
                result = analysis,
            )
        }
    }

    /**
     * Id of the row represented by the actual published payload, or null when none matches.
     * Refreshed via [publicationRevision] after delete/anchor/other publication-affecting actions.
     */
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

    // ---- P0.1-3: 一键锚定（导入完成对话框按钮 / 列表长按菜单共用） ----

    /**
     * 锚定结果提示；null = 无提示。成功消息确认「activeProfileId 已写 + publish 链已走」，
     * 失败消息明确「Hook 仍用上一份配置」，绝不把失败说成生效。
     */
    private val _activationNotice = MutableStateFlow<String?>(null)
    val activationNotice: StateFlow<String?> = _activationNotice

    fun dismissActivationNotice() {
        _activationNotice.value = null
    }

    fun setActiveProfile(id: Long) {
        viewModelScope.launch {
            val published = repo.setActiveProfile(id)
            publicationRevision.value++
            _activationNotice.value =
                if (published) "已设为生效档案并发布给 Hook"
                else "已请求设为生效档案，但发布失败：目标 App 仍使用上一份配置"
        }
    }

    // ---- Import flow ----

    /** 内容指纹 of the file being previewed; recorded into the scan log on successful import. */
    private var pendingFingerprint: String? = null

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
                        val bytes = input.readBytes()
                        pendingFingerprint = DownloadCsvScanner.fingerprintOf(bytes)
                        parser.parse(resolvedName, bytes.inputStream())
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
                    // 导入成功 = 该文件内容已入收藏：写入指纹台账，Download 提示条不再打扰。
                    withContext(Dispatchers.IO) {
                        scanLog.markSeen(listOfNotNull(pendingFingerprint))
                        _downloadCandidates.value =
                            _downloadCandidates.value.filter { it.fingerprint != pendingFingerprint }
                    }
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

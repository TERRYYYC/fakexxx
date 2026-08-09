package name.caiyao.fakegps.ui.screen.collection

import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.importer.ProfileImportAnalysis
import name.caiyao.fakegps.data.importer.ProfileImportIssue
import name.caiyao.fakegps.data.repository.ProfileRepository

sealed interface ProfileImportUiState {
    val generation: Long

    data object Idle : ProfileImportUiState {
        override val generation: Long = 0L
    }

    data class Parsing(
        override val generation: Long,
        val fileName: String,
    ) : ProfileImportUiState

    data class Preview(
        override val generation: Long,
        val fileName: String,
        val records: List<ProfileEntity>,
        val dataRows: Int,
        val fileDuplicates: Int,
    ) : ProfileImportUiState

    data class Invalid(
        override val generation: Long,
        val fileName: String,
        val issues: List<ProfileImportIssue>,
    ) : ProfileImportUiState

    data class Importing(
        override val generation: Long,
        val fileName: String,
        val fileDuplicates: Int,
    ) : ProfileImportUiState

    data class Success(
        override val generation: Long,
        val fileName: String,
        val imported: Int,
        val duplicates: Int,
    ) : ProfileImportUiState

    data class Failure(
        override val generation: Long,
        val fileName: String,
        val message: String,
    ) : ProfileImportUiState
}

sealed interface ProfileTemplateSaveState {
    data object Idle : ProfileTemplateSaveState
    data object Saving : ProfileTemplateSaveState
    data object Success : ProfileTemplateSaveState
    data class Failure(val message: String) : ProfileTemplateSaveState
}

object ProfileTemplateSaveReducer {
    fun canStart(current: ProfileTemplateSaveState): Boolean =
        current is ProfileTemplateSaveState.Idle

    fun dismiss(current: ProfileTemplateSaveState): ProfileTemplateSaveState =
        if (current is ProfileTemplateSaveState.Saving) current else ProfileTemplateSaveState.Idle
}

object ProfileImportReducer {
    data class BeginImport(
        val state: ProfileImportUiState.Importing,
        val records: List<ProfileEntity>,
    )

    fun start(generation: Long, fileName: String): ProfileImportUiState.Parsing =
        ProfileImportUiState.Parsing(generation, fileName)

    fun analysis(
        current: ProfileImportUiState,
        generation: Long,
        fileName: String,
        result: ProfileImportAnalysis,
    ): ProfileImportUiState {
        if (current !is ProfileImportUiState.Parsing || current.generation != generation) {
            return current
        }
        return when (result) {
            is ProfileImportAnalysis.Ready -> ProfileImportUiState.Preview(
                generation = generation,
                fileName = fileName,
                records = result.records,
                dataRows = result.dataRows,
                fileDuplicates = result.duplicateRows,
            )
            is ProfileImportAnalysis.Invalid -> ProfileImportUiState.Invalid(
                generation = generation,
                fileName = fileName,
                issues = result.issues,
            )
        }
    }

    fun beginImport(current: ProfileImportUiState): BeginImport? {
        if (current !is ProfileImportUiState.Preview) return null
        return BeginImport(
            state = ProfileImportUiState.Importing(
                generation = current.generation,
                fileName = current.fileName,
                fileDuplicates = current.fileDuplicates,
            ),
            records = current.records,
        )
    }

    fun imported(
        current: ProfileImportUiState,
        result: ProfileRepository.ImportResult,
    ): ProfileImportUiState {
        if (current !is ProfileImportUiState.Importing) return current
        return ProfileImportUiState.Success(
            generation = current.generation,
            fileName = current.fileName,
            imported = result.imported,
            duplicates = current.fileDuplicates + result.duplicates,
        )
    }

    fun failed(current: ProfileImportUiState, message: String): ProfileImportUiState {
        if (current !is ProfileImportUiState.Importing) return current
        return ProfileImportUiState.Failure(current.generation, current.fileName, message)
    }
}

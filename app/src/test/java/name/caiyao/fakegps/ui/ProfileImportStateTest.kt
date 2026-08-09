package name.caiyao.fakegps.ui

import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.importer.ImportIssueCode
import name.caiyao.fakegps.data.importer.ProfileImportAnalysis
import name.caiyao.fakegps.data.importer.ProfileImportIssue
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.ui.screen.collection.ProfileImportReducer
import name.caiyao.fakegps.ui.screen.collection.ProfileImportUiState
import name.caiyao.fakegps.ui.screen.collection.ProfileTemplateSaveReducer
import name.caiyao.fakegps.ui.screen.collection.ProfileTemplateSaveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImportStateTest {
    @Test
    fun `template save may start only from idle`() {
        assertTrue(ProfileTemplateSaveReducer.canStart(ProfileTemplateSaveState.Idle))
        assertFalse(ProfileTemplateSaveReducer.canStart(ProfileTemplateSaveState.Saving))
        assertFalse(ProfileTemplateSaveReducer.canStart(ProfileTemplateSaveState.Success))
        assertFalse(ProfileTemplateSaveReducer.canStart(ProfileTemplateSaveState.Failure("disk full")))
    }

    @Test
    fun `template result dismiss keeps in-flight save and clears terminal states`() {
        assertEquals(
            ProfileTemplateSaveState.Saving,
            ProfileTemplateSaveReducer.dismiss(ProfileTemplateSaveState.Saving),
        )
        assertEquals(
            ProfileTemplateSaveState.Idle,
            ProfileTemplateSaveReducer.dismiss(ProfileTemplateSaveState.Success),
        )
        assertEquals(
            ProfileTemplateSaveState.Idle,
            ProfileTemplateSaveReducer.dismiss(ProfileTemplateSaveState.Failure("disk full")),
        )
    }

    @Test
    fun `stale parse completion cannot replace a newer file session`() {
        val first = ProfileImportReducer.start(1, "a.csv")
        val second = ProfileImportReducer.start(2, "b.xlsx")

        val afterStale = ProfileImportReducer.analysis(
            current = second,
            generation = first.generation,
            fileName = "a.csv",
            result = ProfileImportAnalysis.Ready(listOf(ProfileEntity(tac = 1)), 1, 0),
        )

        assertEquals(second, afterStale)
    }

    @Test
    fun `invalid analysis has no confirm action`() {
        val parsing = ProfileImportReducer.start(1, "bad.csv")
        val invalid = ProfileImportReducer.analysis(
            parsing,
            1,
            "bad.csv",
            ProfileImportAnalysis.Invalid(
                listOf(ProfileImportIssue(ImportIssueCode.INVALID_VALUE, "bad", 2, "tac")),
            ),
        )

        assertTrue(invalid is ProfileImportUiState.Invalid)
        assertNull(ProfileImportReducer.beginImport(invalid))
    }

    @Test
    fun `preview can be confirmed once and success combines file and database duplicates`() {
        val preview = ProfileImportReducer.analysis(
            ProfileImportReducer.start(5, "profiles.csv"),
            5,
            "profiles.csv",
            ProfileImportAnalysis.Ready(
                records = listOf(ProfileEntity(tac = 1)),
                dataRows = 3,
                duplicateRows = 2,
            ),
        )
        val firstConfirm = ProfileImportReducer.beginImport(preview)

        assertEquals(listOf(ProfileEntity(tac = 1)), firstConfirm!!.records)
        assertNull(ProfileImportReducer.beginImport(firstConfirm.state))

        val success = ProfileImportReducer.imported(
            firstConfirm.state,
            ProfileRepository.ImportResult(imported = 0, duplicates = 1),
        ) as ProfileImportUiState.Success
        assertEquals(0, success.imported)
        assertEquals(3, success.duplicates)
    }

    @Test
    fun `transaction failure remains visible and never claims success`() {
        val preview = ProfileImportReducer.analysis(
            ProfileImportReducer.start(4, "profiles.xlsx"),
            4,
            "profiles.xlsx",
            ProfileImportAnalysis.Ready(listOf(ProfileEntity(tac = 1)), 1, 0),
        )
        val importing = ProfileImportReducer.beginImport(preview)!!.state

        val failed = ProfileImportReducer.failed(importing, "disk full")

        assertEquals(ProfileImportUiState.Failure(4, "profiles.xlsx", "disk full"), failed)
    }
}

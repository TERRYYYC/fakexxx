package com.example.cellrebelauto.ui

import com.example.cellrebelauto.cutover.ProductCutoverImportResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CutoverSafResultMappingTest {

    @Test
    fun readyAndRolledBackTerminalResultsHaveDistinctOperatorMessages() {
        val digest = "sha256:${"a".repeat(64)}"

        assertEquals(
            "Archive imported ($digest)",
            importStatus(ProductCutoverImportResult.Completed(digest))
        )
        assertEquals(
            "Import rolled back; no data was kept. Retry the migration archive ($digest)",
            importStatus(ProductCutoverImportResult.RolledBack(digest))
        )
    }
}

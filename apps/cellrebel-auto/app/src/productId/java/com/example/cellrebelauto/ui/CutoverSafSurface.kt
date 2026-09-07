package com.example.cellrebelauto.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.cellrebelauto.CellRebelAutoApp
import com.example.cellrebelauto.cutover.AndroidCutoverEligibilityPort
import com.example.cellrebelauto.cutover.AutoCutoverRestoreCoordinator
import com.example.cellrebelauto.cutover.CutoverSafContract
import com.example.cellrebelauto.cutover.ProductCutoverImportRejection
import com.example.cellrebelauto.cutover.ProductCutoverImportResult
import com.example.cellrebelauto.cutover.ProductCutoverImporter
import com.example.cellrebelauto.cutover.RoomV9CutoverStore
import kotlinx.coroutines.launch

internal object CutoverSafActivityResultContract {
    val contract = ActivityResultContracts.OpenDocument()

    fun createIntent(context: Context): Intent = contract.createIntent(
        context,
        arrayOf(CutoverSafContract.MEDIA_TYPE)
    )
}

@Composable
fun CutoverSafSurface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember(context) {
        val app = context.applicationContext as CellRebelAutoApp
        val roomStore = RoomV9CutoverStore(app.database)
        val coordinator = AutoCutoverRestoreCoordinator(
            accessGate = app.cutoverAccessGate,
            journalPort = app.cutoverControlStore,
            roomPort = roomStore,
            preferencePort = app.planConfigStore,
            eligibilityPort = AndroidCutoverEligibilityPort(app),
            quiescencePort = app.cutoverRunQuiescence
        )
        ProductCutoverImporter(
            contentType = app.contentResolver::getType,
            openInputStream = app.contentResolver::openInputStream,
            policy = roomStore::schemaPolicy,
            restore = coordinator::restore
        )
    }
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CutoverSafActivityResultContract.contract) { uri ->
        if (uri == null) {
            status = "Migration archive import cancelled"
        } else {
            busy = true
            scope.launch {
                status = importStatus(importer.import(uri))
                busy = false
            }
        }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Move app data", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Import a complete migration archive from a document you choose.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                enabled = !busy,
                onClick = {
                    status = null
                    launcher.launch(arrayOf(CutoverSafContract.MEDIA_TYPE))
                }
            ) {
                Text(if (busy) "Importing…" else "Import migration archive")
            }
            status?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun importStatus(result: ProductCutoverImportResult): String = when (result) {
    is ProductCutoverImportResult.Completed -> "Archive imported (${result.archiveDigest})"
    is ProductCutoverImportResult.RecoveryRequired ->
        "Import requires recovery (${result.archiveDigest})"
    is ProductCutoverImportResult.Rejected -> when (result.reason) {
        ProductCutoverImportRejection.WRONG_MEDIA -> "Import rejected: choose a migration archive"
        ProductCutoverImportRejection.DOCUMENT_READ_FAILED -> "Import failed while reading the document"
        ProductCutoverImportRejection.INVALID_ARCHIVE -> "Import rejected: invalid migration archive"
        ProductCutoverImportRejection.TARGET_UNAVAILABLE -> "Import unavailable: recovery is required"
        ProductCutoverImportRejection.BUSY -> "Import unavailable: another migration is active"
        ProductCutoverImportRejection.QUIESCENCE_FAILED -> "Import unavailable: automation did not stop"
        ProductCutoverImportRejection.TARGET_NOT_EMPTY -> "Import rejected: this app already has data"
        ProductCutoverImportRejection.NOT_ELIGIBLE ->
            "Import rejected: the installed legacy app does not match this build"
        ProductCutoverImportRejection.ARCHIVE_CONFLICT ->
            "Import rejected: a different migration is already staged"
        ProductCutoverImportRejection.INVALID_PHASE -> "Import rejected: invalid recovery phase"
    }
}

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
import com.example.cellrebelauto.cutover.AutoCutoverSnapshotPort
import com.example.cellrebelauto.cutover.CutoverSafContract
import com.example.cellrebelauto.cutover.LegacyCutoverExportRejection
import com.example.cellrebelauto.cutover.LegacyCutoverExportResult
import com.example.cellrebelauto.cutover.LegacyCutoverExporter
import com.example.cellrebelauto.cutover.RoomV10CutoverStore
import java.util.UUID
import kotlinx.coroutines.launch

internal object CutoverSafActivityResultContract {
    val contract = ActivityResultContracts.CreateDocument(CutoverSafContract.MEDIA_TYPE)

    fun createIntent(context: Context): Intent = contract.createIntent(
        context,
        CutoverSafContract.suggestedFileName("snapshot")
    )
}

@Composable
fun CutoverSafSurface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember(context) {
        val app = context.applicationContext as CellRebelAutoApp
        val roomStore = RoomV10CutoverStore(app.database)
        val snapshotPort = AutoCutoverSnapshotPort(
            accessGate = app.cutoverAccessGate,
            quiescencePort = app.cutoverRunQuiescence,
            roomPort = roomStore,
            preferencePort = app.planConfigStore
        )
        LegacyCutoverExporter(
            capture = snapshotPort::capture,
            openOutputStream = { uri -> app.contentResolver.openOutputStream(uri, "w") }
        )
    }
    var pendingCaptureId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CutoverSafActivityResultContract.contract) { uri ->
        val captureId = pendingCaptureId
        pendingCaptureId = null
        if (uri == null || captureId == null) {
            status = "Migration archive export cancelled"
        } else {
            busy = true
            scope.launch {
                status = exportStatus(exporter.export(uri, captureId))
                busy = false
            }
        }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Move app data", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Export a complete migration archive to a document you choose.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                enabled = !busy,
                onClick = {
                    val captureId = UUID.randomUUID().toString()
                    pendingCaptureId = captureId
                    status = null
                    launcher.launch(CutoverSafContract.suggestedFileName(captureId))
                }
            ) {
                Text(if (busy) "Exporting…" else "Export migration archive")
            }
            status?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun exportStatus(result: LegacyCutoverExportResult): String = when (result) {
    is LegacyCutoverExportResult.Completed ->
        "Archive exported (${result.archiveDigest})"
    is LegacyCutoverExportResult.Rejected -> when (result.reason) {
        LegacyCutoverExportRejection.BUSY -> "Export unavailable: another migration is active"
        LegacyCutoverExportRejection.QUIESCENCE_FAILED -> "Export unavailable: automation did not stop"
        LegacyCutoverExportRejection.CAPTURE_FAILED -> "Export failed while capturing app data"
        LegacyCutoverExportRejection.DOCUMENT_WRITE_FAILED -> "Export failed while writing the document"
    }
}

package com.example.cellrebelauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A provider entry the approval screen renders: pending candidate or active (approved) record. */
data class ProviderEntry(
    val applicationId: String,
    val signerDigest: String,
    val approvedVersionCode: Int?,
    val isApproved: Boolean
)

/**
 * R43 (spec Task 6 / Sol GREEN-review-2 F5): the §6.5.3 operator approval AND revocation surface.
 * Pending candidates show applicationId / current signer digest / source; approved providers list
 * with a revoke action. Nothing enters the trusted path until an explicit approve (no silent TOFU).
 *
 * # Provider 批准/撤销入口：候选展示 + 批准/撤销；批准前绝不进入可信判定
 */
@Composable
fun ProviderApprovalScreen(
    pending: List<ProviderEntry>,
    approved: List<ProviderEntry>,
    onApprove: (ProviderEntry) -> Unit,
    onRevoke: (ProviderEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Provider 管理", style = MaterialTheme.typography.titleMedium)

        Text("待批准候选", style = MaterialTheme.typography.titleSmall)
        if (pending.isEmpty()) {
            Text("无待批准候选", style = MaterialTheme.typography.bodySmall)
        }
        for (entry in pending) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.applicationId, style = MaterialTheme.typography.bodyMedium)
                    Text("signer: ${entry.signerDigest}", style = MaterialTheme.typography.bodySmall)
                    Text("来源: 配对请求（调用内快照）", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onApprove(entry) }) { Text("批准") }
                    }
                }
            }
        }

        Text("已批准 provider", style = MaterialTheme.typography.titleSmall)
        if (approved.isEmpty()) {
            Text("无已批准 provider", style = MaterialTheme.typography.bodySmall)
        }
        for (entry in approved) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.applicationId, style = MaterialTheme.typography.bodyMedium)
                    Text("signer: ${entry.signerDigest}", style = MaterialTheme.typography.bodySmall)
                    entry.approvedVersionCode?.let {
                        Text("批准版本: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { onRevoke(entry) }) { Text("撤销") }
                    }
                }
            }
        }
    }
}

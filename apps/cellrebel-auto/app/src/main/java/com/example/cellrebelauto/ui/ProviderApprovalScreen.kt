package com.example.cellrebelauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
/**
 * Issue #10: revoke confirmation state rendered by [ProviderApprovalScreen]. The screen only
 * renders this; [MainViewModel] owns the staging/confirm/dismiss logic (tested there).
 * # 撤销确认对话框状态：屏幕只渲染，ViewModel 持有暂存/确认/取消逻辑
 */
data class ProviderRevokeDialogState(
    val candidate: ProviderEntry,
    val confirmLabel: String = "撤销",
    val dismissLabel: String = "取消",
    val title: String = "撤销该 provider？",
    val irreversibleNotice: String =
        "此操作不可自动恢复。撤销后引擎的信任门将拒绝该 provider 的一切契约调用" +
            "（discover/preflight/apply/observe/completeAndAdvance），运行会暂停，" +
            "直到重新批准同一签名。"
)

@Composable
fun ProviderApprovalScreen(
    pending: List<ProviderEntry>,
    approved: List<ProviderEntry>,
    onApprove: (ProviderEntry) -> Unit,
    onRevoke: (ProviderEntry) -> Unit,
    onBack: () -> Unit = {},
    // Issue #10: staged-revoke confirmation + post-revoke impact banner.
    revokeDialog: ProviderRevokeDialogState? = null,
    onRevokeConfirmed: () -> Unit = {},
    onRevokeDismissed: () -> Unit = {},
    revokeImpactNotice: String? = null,
    onRevokeNoticeDismissed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // # Issue #10：不可逆撤销必须经确认对话框；撤销后以横幅说明引擎影响
    revokeDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = onRevokeDismissed,
            title = { Text(dialog.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${dialog.candidate.applicationId}")
                    Text("signer: ${dialog.candidate.signerDigest}", style = MaterialTheme.typography.bodySmall)
                    Text(dialog.irreversibleNotice, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = onRevokeConfirmed) { Text(dialog.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = onRevokeDismissed) { Text(dialog.dismissLabel) }
            }
        )
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Provider 管理", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        revokeImpactNotice?.let { notice ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onRevokeNoticeDismissed) { Text("知道了") }
                }
            }
        }

        Text("待批准候选", style = MaterialTheme.typography.titleSmall)
        if (pending.isEmpty()) {
            Text("无待批准候选", style = MaterialTheme.typography.bodySmall)
        }
        for (entry in pending) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.applicationId, style = MaterialTheme.typography.bodyMedium)
                    Text("signer: ${entry.signerDigest}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        // R44 F5: the REAL discovery source — the provider is installed and its
                        // current signer was resolved at discovery time. Never a fabricated label.
                        "来源: 已安装 provider（当前 signer 实测解析）",
                        style = MaterialTheme.typography.bodySmall
                    )
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
                        // # Issue #10：一键撤销 → 先暂存到确认对话框（onRevoke 仅发起请求）
                        OutlinedButton(onClick = { onRevoke(entry) }) { Text("撤销") }
                    }
                }
            }
        }
    }
}

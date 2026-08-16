package com.example.cellrebelauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * R43 (spec Task 6 / Sol GREEN-review-2 F5): the pairing/compatibility/recovery status card on
 * the run surface. Renders [PairingUiState] with a CONCRETE recovery action per state — the
 * operator always sees WHAT is wrong and WHAT to do, never a bare error code.
 *
 * # 配对/兼容/恢复状态卡片：七态 + 具体恢复动作（绝不裸错误码）
 */
@Composable
fun PairingStatusCard(
    state: PairingUiState,
    onOpenApproval: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (title, tone) = when (state) {
        is PairingUiState.NotPaired -> "未配对" to MaterialTheme.colorScheme.error
        is PairingUiState.PendingOperatorApproval -> "待 Operator 批准" to MaterialTheme.colorScheme.tertiary
        is PairingUiState.Incompatible -> "Provider 不兼容" to MaterialTheme.colorScheme.error
        is PairingUiState.Trusted -> "可信配对" to MaterialTheme.colorScheme.primary
        is PairingUiState.UnverifiedCompletion -> "完成未验证" to MaterialTheme.colorScheme.tertiary
        is PairingUiState.RecoveryRequired -> "需要恢复" to MaterialTheme.colorScheme.error
        is PairingUiState.ReleaseIncomplete -> "租约未清理" to MaterialTheme.colorScheme.error
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = tone)
            if (state is PairingUiState.Incompatible) {
                Text(state.detail, style = MaterialTheme.typography.bodySmall)
            }
            if (state.recoveryAction.isNotBlank()) {
                Text(
                    "→ ${state.recoveryAction}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state is PairingUiState.NotPaired || state is PairingUiState.PendingOperatorApproval) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onOpenApproval) { Text("打开 Provider 管理") }
                }
            }
        }
    }
}

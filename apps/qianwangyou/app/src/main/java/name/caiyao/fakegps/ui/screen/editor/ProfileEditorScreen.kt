package name.caiyao.fakegps.ui.screen.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import name.caiyao.fakegps.data.model.FieldSpec
import name.caiyao.fakegps.data.model.FieldType
import name.caiyao.fakegps.config.UnavailableSpec
import name.caiyao.fakegps.ui.observationScopePresentation
import name.caiyao.fakegps.verify.ObservationScope
import name.caiyao.fakegps.verify.VerificationEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profileId: Long,
    lat: Double,
    lon: Double,
    onBack: () -> Unit,
    onVerify: () -> Unit = {},
    vm: ProfileEditorViewModel = viewModel(),
) {
    val isNew = profileId == -1L || profileId == 0L
    val fieldValues by vm.fieldValues.collectAsState()
    val reference by vm.reference.collectAsState()
    val saved by vm.saved.collectAsState()
    val saving by vm.saving.collectAsState()
    val fieldErrors by vm.fieldErrors.collectAsState()
    val notice by vm.notice.collectAsState()

    LaunchedEffect(profileId) {
        vm.load(if (isNew) -1L else profileId, lat, lon)
    }

    val verifyRequested by vm.verifyRequested.collectAsState()

    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    // Only ever set after a SUCCESSFUL publish (see postSaveAction) — a failed publish keeps the
    // user here with the notice instead of sending them to verify a payload the hook never got.
    LaunchedEffect(verifyRequested) {
        if (verifyRequested) onVerify()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建档案" else "编辑档案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Saves first and only then navigates, and only if the payload actually reached
                // the hook — verifying an unpublished draft would report every changed field as
                // wrong and blame the spoof for a publish failure.
                ExtendedFloatingActionButton(
                    onClick = { if (!saving) vm.saveAndVerify() },
                    icon = { Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null) },
                    text = { Text("保存并验证") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .alpha(if (saving) 0.5f else 1f),
                )
                FloatingActionButton(
                    onClick = { if (!saving) vm.save() },
                    modifier = Modifier.alpha(if (saving) 0.5f else 1f),
                ) {
                    Icon(Icons.Default.Save, contentDescription = "保存")
                }
            }
        },
    ) { innerPadding ->
        val categories = remember { FieldSpec.allCategories() }
        val expandedState = remember { mutableStateMapOf<String, Boolean>() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            notice?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            for ((category, fields) in categories) {
                val expanded = expandedState[category] ?: (category == "定位")
                val activeCount = fields.count { fieldValues.containsKey(it.dbColumn) }

                CategoryCard(
                    title = category,
                    activeCount = activeCount,
                    totalCount = fields.size,
                    expanded = expanded,
                    onToggle = { expandedState[category] = !expanded },
                ) {
                    for (spec in fields) {
                        val value = fieldValues[spec.dbColumn] ?: ""
                        when (spec.type) {
                            FieldType.BOOLEAN -> BooleanField(
                                spec = spec,
                                value = value,
                                reference = reference[spec.dbColumn],
                                scope = vm.scope,
                                onValueChange = { vm.updateField(spec.dbColumn, it) },
                            )
                            else -> TextField(
                                spec = spec,
                                value = value,
                                reference = reference[spec.dbColumn],
                                validationError = fieldErrors[spec.dbColumn],
                                scope = vm.scope,
                                onValueChange = { vm.updateField(spec.dbColumn, it) },
                            )
                        }
                    }
                }
            }
            // Bottom padding for FAB clearance
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.padding(bottom = 72.dp)
            )
        }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    activeCount: Int,
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (activeCount > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text("$activeCount/$totalCount")
                    }
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun TextField(
    spec: FieldSpec,
    value: String,
    reference: String?,
    validationError: String?,
    scope: ObservationScope,
    onValueChange: (String) -> Unit,
) {
    val keyboardType = when (spec.type) {
        FieldType.INTEGER -> KeyboardType.Number
        FieldType.DOUBLE, FieldType.FLOAT -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }

    val label = buildString {
        append(spec.displayName)
        if (spec.unit != null) append(" (${spec.unit})")
    }

    // A spoofed value is only provable if it differs from what the device already reports. Flagging
    // the collision at input time is the only point where the user can still act on it.
    //
    // Only meaningful against a baseline not self-hooked by this module. A self-hook-eligible
    // build may already read its spoofed value as "reference", so a collision warning there would
    // be misleading. Neither baseline nor passthrough certifies the absence of other spoofing.
    val unavailable = value == ProfileFieldDraft.UNAVAILABLE_TOKEN
    val supportsUnavailable = UnavailableSpec.supportsUnavailable(spec.dbColumn)
    val collides = !unavailable && scope == ObservationScope.REAL_BASELINE && value.isNotBlank() &&
        reference != null && VerificationEngine.valuesMatch(value, reference)

    val referenceLabel = observationScopePresentation(scope).referenceLabel

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            // Empty means passthrough — the project's core invariant, previously stated only on the
            // boolean dropdown, so text fields gave no hint that blank was a meaningful choice.
            placeholder = { Text(if (spec.hint.isBlank()) "留空 = 透传系统 API 返回值" else "${spec.hint}（留空 = 透传）") },
            isError = collides || validationError != null,
            supportingText = {
                when {
                    validationError != null -> Text(validationError)
                    unavailable -> Text("不上报：目标 App 将看到该 API 的“无数据”值")
                    collides -> Text("与$referenceLabel 相同 — 即使生效也无法区分，建议换一个明显不同的值")
                    supportsUnavailable -> Text("留空 = 透传系统 API 返回值；不上报 = 该 API 返回无数据")
                    else -> Text("留空 = 透传系统 API 返回值；此字段不支持不上报")
                }
            },
            trailingIcon = if (supportsUnavailable) {
                {
                    TextButton(
                        onClick = {
                            onValueChange(
                                if (unavailable) "" else ProfileFieldDraft.UNAVAILABLE_TOKEN,
                            )
                        },
                    ) {
                        Text(if (unavailable) "透传" else "不上报")
                    }
                }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (reference != null) {
            Text(
                text = "$referenceLabel：$reference",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BooleanField(
    spec: FieldSpec,
    value: String,
    reference: String?,
    scope: ObservationScope,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("" to "透传", "1" to "是", "0" to "否")
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: "透传"

    // Booleans need the same reference and collision treatment as text fields: with only two
    // possible values a collision with the baseline is far MORE likely here, not less.
    val collides = scope == ObservationScope.REAL_BASELINE && value.isNotBlank() &&
        reference != null && VerificationEngine.valuesMatch(value, reference)

    val referenceLabel = observationScopePresentation(scope).referenceLabel
    val referenceText = reference?.let { if (it.equals("true", true) || it == "1") "是" else "否" }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(spec.displayName) },
            isError = collides,
            supportingText = {
                when {
                    collides -> Text("与$referenceLabel 相同 — 即使生效也无法区分，建议改成相反的值")
                    referenceText != null -> Text("$referenceLabel：$referenceText")
                    else -> Text("透传 = 保持系统 API 返回值")
                }
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((optValue, label) in options) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onValueChange(optValue)
                        expanded = false
                    },
                )
            }
        }
    }
}

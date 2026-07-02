package com.pocketide.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.data.ai.AiConfig
import com.pocketide.data.ai.AiConfigRepository
import com.pocketide.data.ai.Quantization
import com.pocketide.data.model.Language
import com.pocketide.ui.theme.ThemeColors
import com.pocketide.ui.theme.ThemeViewModel

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeViewModel: ThemeViewModel,
) {
    val context = LocalContext.current
    val aiConfigRepository = remember { AiConfigRepository(context) }
    var aiConfig by remember { mutableStateOf(aiConfigRepository.load()) }
    val isDarkMode by themeViewModel.isDarkMode.collectAsState()

    fun persistAiConfig(update: (AiConfig) -> AiConfig) {
        aiConfig = update(aiConfig)
        aiConfigRepository.save(aiConfig)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        // Top bar with status bar padding
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                )
                .height(44.dp)
                .padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // === Appearance ===
            SectionHeader("Appearance")
            SettingsGroup {
                CompactToggleRow(
                    label = "Dark mode",
                    checked = isDarkMode,
                    onCheckedChange = { themeViewModel.setDarkMode(it) },
                )
            }

            // === On-Device Model ===
            SectionHeader("On-Device Model")
            SettingsGroup {
                Text(
                    text = "Load a local .pte model for fully offline inference.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = aiConfig.modelPath,
                    onValueChange = { value -> persistAiConfig { it.copy(modelPath = value) } },
                    label = { Text("Model path", style = MaterialTheme.typography.labelSmall) },
                    placeholder = { Text("/sdcard/models/qwen3-0.6b.pte") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Quantization",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Quantization.entries.forEach { quant ->
                        FilterChip(
                            selected = aiConfig.quantization == quant,
                            onClick = { persistAiConfig { it.copy(quantization = quant) } },
                            label = { Text(quant.displayName, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                StatusPill(
                    text = if (aiConfig.isConfigured) "Model configured" else "No model loaded",
                    isError = !aiConfig.isConfigured,
                    isDark = isDarkMode,
                )
            }

            // === Optimization ===
            SectionHeader("Optimization")
            SettingsGroup {
                CompactToggleRow(
                    label = "Power saving",
                    checked = aiConfig.powerSaving,
                    onCheckedChange = { value -> persistAiConfig { it.copy(powerSaving = value) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                CompactToggleRow(
                    label = "Thermal-aware",
                    checked = aiConfig.thermalAware,
                    onCheckedChange = { value -> persistAiConfig { it.copy(thermalAware = value) } },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                CompactToggleRow(
                    label = "Adaptive cores",
                    checked = aiConfig.adaptiveCores,
                    onCheckedChange = { value -> persistAiConfig { it.copy(adaptiveCores = value) } },
                )
            }

            // === Agent ===
            SectionHeader("Agent")
            SettingsGroup {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Max repair iterations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${aiConfig.maxRepairIterations}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
                Slider(
                    value = aiConfig.maxRepairIterations.toFloat(),
                    onValueChange = { value ->
                        persistAiConfig { it.copy(maxRepairIterations = value.toInt()) }
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.height(32.dp),
                )
            }

            // === Sandbox Languages (bottom) ===
            SectionHeader("Sandbox Languages")
            SettingsGroup {
                val enabledLanguages = remember { mutableStateOf(Language.entries.toSet()) }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Language.entries.forEach { language ->
                        val enabled = language in enabledLanguages.value
                        FilterChip(
                            selected = enabled,
                            onClick = {
                                enabledLanguages.value = if (enabled) {
                                    enabledLanguages.value - language
                                } else {
                                    enabledLanguages.value + language
                                }
                            },
                            label = { Text(language.displayName, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp),
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsGroup(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun CompactToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.7f),
        )
    }
}

@Composable
private fun StatusPill(
    text: String,
    isError: Boolean,
    isDark: Boolean,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else ThemeColors.agentCoder(isDark)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

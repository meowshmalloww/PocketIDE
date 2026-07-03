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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    fun persistAiConfig(update: (AiConfig) -> AiConfig) {
        aiConfig = update(aiConfig)
        aiConfigRepository.save(aiConfig)
    }

    val sections = listOf(
        "General" to Icons.Filled.Tune,
        "On-Device Model" to Icons.Filled.ModelTraining,
        "Optimization" to Icons.Filled.Speed,
        "Agent" to Icons.Filled.Security,
        "Sandbox Languages" to Icons.Filled.Recommend,
    )
    var selectedSection by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                )
                .height(48.dp)
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

        if (isTablet && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // Tablet landscape: sidebar + detail pane
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SettingsSidebar(
                    sections = sections,
                    selectedIndex = selectedSection,
                    onSelect = { selectedSection = it },
                    isDark = isDarkMode,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    SettingsContent(
                        aiConfig = aiConfig,
                        isDarkMode = isDarkMode,
                        onPersist = ::persistAiConfig,
                        onThemeChange = { themeViewModel.setDarkMode(it) },
                    )
                }
            }
        } else {
            // Phone: scrollable vertical list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsContent(
                    aiConfig = aiConfig,
                    isDarkMode = isDarkMode,
                    onPersist = ::persistAiConfig,
                    onThemeChange = { themeViewModel.setDarkMode(it) },
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(
    aiConfig: AiConfig,
    isDarkMode: Boolean,
    onPersist: ((AiConfig) -> AiConfig) -> Unit,
    onThemeChange: (Boolean) -> Unit,
) {
    // Appearance
    SectionHeader("Appearance")
    SettingsGroup {
        IconToggleRow(
            icon = Icons.Filled.DarkMode,
            title = "Dark mode",
            subtitle = "Use dark theme",
            checked = isDarkMode,
            onCheckedChange = onThemeChange,
        )
    }

    // On-Device Model
    SectionHeader("On-Device Model")
    SettingsGroup {
        ModelPathRow(
            path = aiConfig.modelPath,
            onPathChange = { value -> onPersist { it.copy(modelPath = value) } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(
            text = "Quantization",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Quantization.entries.forEach { quant ->
                SelectableChip(
                    selected = aiConfig.quantization == quant,
                    label = quant.displayName,
                    onClick = { onPersist { it.copy(quantization = quant) } },
                )
            }
        }
        StatusPill(
            text = when {
                aiConfig.isConfigured -> "${aiConfig.modelFormat.displayName} model configured"
                else -> "No model loaded"
            },
            isError = !aiConfig.isConfigured,
            isDark = isDarkMode,
        )
    }

    // Optimization
    SectionHeader("Optimization")
    SettingsGroup {
        IconToggleRow(
            icon = Icons.Filled.Bolt,
            title = "Power saving",
            subtitle = "Reduce performance for lower power usage",
            checked = aiConfig.powerSaving,
            onCheckedChange = { value -> onPersist { it.copy(powerSaving = value) } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        IconToggleRow(
            icon = Icons.Filled.Thermostat,
            title = "Thermal-aware",
            subtitle = "Adjust performance to prevent overheating",
            checked = aiConfig.thermalAware,
            onCheckedChange = { value -> onPersist { it.copy(thermalAware = value) } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        IconToggleRow(
            icon = Icons.Filled.Speed,
            title = "Adaptive cores",
            subtitle = "Dynamically use optimal CPU cores",
            checked = aiConfig.adaptiveCores,
            onCheckedChange = { value -> onPersist { it.copy(adaptiveCores = value) } },
        )
    }

    // Agent
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
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Text(
            text = "Higher values may improve results but take longer",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepSlider(
            value = aiConfig.maxRepairIterations,
            range = 1..10,
            onValueChange = { value -> onPersist { it.copy(maxRepairIterations = value) } },
        )
    }

    // Sandbox Languages
    SectionHeader("Sandbox Languages")
    SettingsGroup {
        val enabledLanguages = remember { mutableStateOf(Language.entries.toSet()) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Language.entries.forEach { language ->
                val enabled = language in enabledLanguages.value
                SelectableChip(
                    selected = enabled,
                    label = language.displayName,
                    onClick = {
                        enabledLanguages.value = if (enabled) {
                            enabledLanguages.value - language
                        } else {
                            enabledLanguages.value + language
                        }
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SettingsSidebar(
    sections: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    isDark: Boolean,
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEachIndexed { index, (label, icon) ->
            val selected = index == selectedIndex
            val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
            val content = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
            }
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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun IconToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.8f),
        )
    }
}

@Composable
private fun ModelPathRow(
    path: String,
    onPathChange: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.CreateNewFolder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        OutlinedTextField(
            value = path,
            onValueChange = onPathChange,
            label = { Text("Model file", style = MaterialTheme.typography.labelSmall) },
            placeholder = { Text("/sdcard/models/model.gguf or .pte") },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            ),
        )
        IconButton(onClick = { /* TODO: open file picker */ }) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = "Browse model file",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SelectableChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
private fun StepSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            (range.first..range.last).forEach { step ->
                Text(
                    text = step.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (step == value) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
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

package com.pocketide.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.R
import com.pocketide.data.ai.AiConfig
import com.pocketide.data.ai.AiConfigRepository
import com.pocketide.data.ai.ModelDownloader
import com.pocketide.data.ai.ModelEntry
import com.pocketide.data.ai.ModelFileImporter
import com.pocketide.data.ai.PromptTemplate
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
    var selectedSection by remember { mutableIntStateOf(0) }

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
                        onCatalogModelActivated = { aiConfig = aiConfigRepository.load() },
                        selectedSection = selectedSection,
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
                    onCatalogModelActivated = { aiConfig = aiConfigRepository.load() },
                    selectedSection = -1,
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
    onCatalogModelActivated: () -> Unit,
    selectedSection: Int = -1,
) {
    // Section indices match the sidebar list:
    // 0=General, 1=On-Device Model, 2=Optimization, 3=Agent, 4=Sandbox Languages
    // -1 means show all (portrait mode)

    if (selectedSection == -1 || selectedSection == 0) {
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
    }

    if (selectedSection == -1 || selectedSection == 1) {
    // On-Device Model
    SectionHeader("On-Device Model")
    SettingsGroup {
        ModelManagerSection(
            models = aiConfig.models,
            activeModelIndex = aiConfig.activeModelIndex,
            onSelectModel = { index -> onPersist { it.copy(activeModelIndex = index) } },
            onAddModel = { entry -> onPersist { it.copy(models = it.models + entry) } },
            onCatalogModelActivated = onCatalogModelActivated,
            onRemoveModel = { index -> onPersist {
                val newModels = it.models.toMutableList()
                val wasActive = it.activeModelIndex == index
                newModels.removeAt(index)
                val newActive = if (newModels.isEmpty()) 0 else if (wasActive) (index - 1).coerceAtLeast(0) else it.activeModelIndex.coerceAtMost(newModels.lastIndex)
                it.copy(models = newModels, activeModelIndex = newActive)
            } },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Text(
            text = "Model quantization label",
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
        Text(
            text = "PocketIDE does not quantize after import. Select the label matching the actual GGUF/PTE artifact used for benchmark comparisons.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill(
            text = when {
                aiConfig.isConfigured -> "${aiConfig.modelFormat.displayName} model configured"
                else -> "No model loaded"
            },
            isError = !aiConfig.isConfigured,
            isDark = isDarkMode,
        )
    }
    }

    if (selectedSection == -1 || selectedSection == 2) {
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
            subtitle = "Apply a device-aware worker-thread count to the native runtime",
            checked = aiConfig.adaptiveCores,
            onCheckedChange = { value -> onPersist { it.copy(adaptiveCores = value) } },
        )
    }
    }

    if (selectedSection == -1 || selectedSection == 3) {
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

    // Context Window
    SectionHeader("Context Window")
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_requested_context_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (aiConfig.contextWindowSize >= 1000) {
                    "${aiConfig.contextWindowSize / 1000}K tokens"
                } else {
                    "${aiConfig.contextWindowSize} tokens"
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Text(
            text = stringResource(R.string.settings_context_explanation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val contextSteps = listOf(2048, 4096, 8192, 16384, 32768, 65536, 131072)
        val contextIndex = contextSteps.indexOf(aiConfig.contextWindowSize).coerceAtLeast(0)
        Slider(
            value = contextIndex.toFloat(),
            onValueChange = { idx ->
                val clamped = idx.toInt().coerceIn(0, contextSteps.size - 1)
                onPersist { it.copy(contextWindowSize = contextSteps[clamped]) }
            },
            valueRange = 0f..(contextSteps.size - 1).toFloat(),
            steps = contextSteps.size - 2,
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
            contextSteps.forEach { step ->
                Text(
                    text = if (step >= 1000) "${step / 1000}K" else "${step}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step == aiConfig.contextWindowSize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (step == aiConfig.contextWindowSize) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        IconToggleRow(
            icon = Icons.Filled.Code,
            title = stringResource(R.string.settings_retrieve_context_title),
            subtitle = stringResource(R.string.settings_retrieve_context_subtitle),
            checked = aiConfig.enableCodeContext,
            onCheckedChange = { v -> onPersist { it.copy(enableCodeContext = v) } },
        )

        IconToggleRow(
            icon = Icons.Filled.History,
            title = "Summarize old history",
            subtitle = "Compress older conversation messages into summaries to save context space",
            checked = aiConfig.enableHistorySummary,
            onCheckedChange = { v -> onPersist { it.copy(enableHistorySummary = v) } },
        )

        Text(
            text = stringResource(R.string.settings_retrieval_disclosure),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    }

    if (selectedSection == -1 || selectedSection == 4) {
    // Sandbox Languages
    SectionHeader("Sandbox Languages")
    SettingsGroup {
        Text(
            text = "PocketIDE edits all listed formats. Runtime labels below describe what can actually execute on this device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Language.entries.groupBy { it.executionSupport }.forEach { (support, languages) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = support.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = languages.joinToString(" · ") { language ->
                        buildString {
                            append(language.displayName)
                            if (language.supportsHardwareBridge) append(" + hardware")
                            if (language.supportsWebPreview && language.executionSupport.name != "PREVIEW") append(" + preview")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = support.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun ModelManagerSection(
    models: List<ModelEntry>,
    activeModelIndex: Int,
    onSelectModel: (Int) -> Unit,
    onAddModel: (ModelEntry) -> Unit,
    onCatalogModelActivated: () -> Unit,
    onRemoveModel: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { ModelDownloader(context) }
    val fileImporter = remember { ModelFileImporter(context) }

    var showAddForm by remember { mutableStateOf(false) }
    var showDownloadForm by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var dlUrl by remember { mutableStateOf("") }
    var dlName by remember { mutableStateOf("") }
    var dlTemplate by remember { mutableStateOf(PromptTemplate.AUTO) }

    var newName by remember { mutableStateOf("") }
    var newPath by remember { mutableStateOf("") }
    var newTokenizer by remember { mutableStateOf("") }
    var newTemplate by remember { mutableStateOf(PromptTemplate.AUTO) }
    var importError by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importError = null
                fileImporter.import(uri, requireModelExtension = true)
                    .onSuccess { imported ->
                        fileImporter.deleteImported(newPath)
                        newPath = imported.internalPath
                        newName = prettyModelName(imported.displayName)
                        newTemplate = detectPromptTemplate(imported.displayName)
                    }
                    .onFailure { importError = it.message ?: "Model import failed" }
                isImporting = false
            }
        }
    }
    val tokenizerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importError = null
                fileImporter.import(uri, requireModelExtension = false)
                    .onSuccess {
                        fileImporter.deleteImported(newTokenizer)
                        newTokenizer = it.internalPath
                    }
                    .onFailure { importError = it.message ?: "Tokenizer import failed" }
                isImporting = false
            }
        }
    }

    CatalogModelDownloads(onModelActivated = onCatalogModelActivated)
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        modifier = Modifier.padding(vertical = 8.dp),
    )

    // List existing models
    if (models.isNotEmpty()) {
        models.forEachIndexed { index, model ->
            val isActive = index == activeModelIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else Color.Transparent,
                    )
                    .clickable { onSelectModel(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Filled.Check else Icons.Filled.ModelTraining,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = buildString {
                            append(model.modelPath.substringAfterLast('/').substringAfterLast('\\'))
                            append(" · ")
                            append(model.format.displayName)
                            append(" · ")
                            append(model.promptTemplate.displayName)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = {
                        fileImporter.deleteImported(model.modelPath)
                        fileImporter.deleteImported(model.tokenizerPath)
                        onRemoveModel(index)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Remove model",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (index < models.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
        }
    } else {
        Text(
            text = "No models added yet. Tap \"Add Model\" below.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Download from URL section
    if (isDownloading) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Downloading ${dlName.ifBlank { "model" } }...",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            Text(
                text = "${(downloadProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        downloadError?.let { error ->
            Text(
                text = "Download failed: $error",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        if (showDownloadForm) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dlName,
                    onValueChange = { dlName = it },
                    label = { Text("Model name", style = MaterialTheme.typography.labelSmall) },
                    placeholder = { Text("e.g. My Fine-Tuned Qwen 1.5B") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                OutlinedTextField(
                    value = dlUrl,
                    onValueChange = { dlUrl = it },
                    label = { Text("HuggingFace download URL", style = MaterialTheme.typography.labelSmall) },
                    placeholder = { Text("https://huggingface.co/user/repo/resolve/main/model.gguf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(
                    text = "Prompt template",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptTemplate.entries.forEach { template ->
                        SelectableChip(
                            selected = dlTemplate == template,
                            label = template.displayName,
                            onClick = { dlTemplate = template },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showDownloadForm = false
                            dlUrl = ""
                            dlName = ""
                            dlTemplate = PromptTemplate.AUTO
                        },
                    ) {
                        Text("Cancel")
                    }
                    androidx.compose.material3.Button(
                        onClick = {
                        val fileName = dlUrl.substringAfterLast("/").ifBlank { "model.bin" }
                        isDownloading = true
                        downloadProgress = 0f
                        downloadError = null
                        scope.launch {
                            val result = downloader.download(
                                downloadUrl = dlUrl.trim(),
                                fileName = fileName,
                            ) { percent ->
                                downloadProgress = percent / 100f
                            }
                            when (result) {
                                is ModelDownloader.DownloadResult.Success -> {
                                    onAddModel(
                                        ModelEntry(
                                            name = dlName.trim().ifBlank { fileName },
                                            modelPath = result.savedPath,
                                            promptTemplate = dlTemplate,
                                        ),
                                    )
                                    showDownloadForm = false
                                    dlUrl = ""
                                    dlName = ""
                                    dlTemplate = PromptTemplate.AUTO
                                }
                                is ModelDownloader.DownloadResult.Error -> {
                                    downloadError = result.message
                                }
                            }
                            isDownloading = false
                        }
                    },
                        enabled = dlUrl.isNotBlank(),
                    ) {
                        Text("Download")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    .clickable { showDownloadForm = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Download model from URL",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Download from URL",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    if (showAddForm) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Model name", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("e.g. Qwen 2.5 0.5B") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            FilePickerRow(
                label = "Model file (.gguf or .pte)",
                selectedFile = newPath.substringAfterLast('/').substringAfterLast('\\').ifBlank { null },
                enabled = !isImporting,
                onClick = { modelPicker.launch(arrayOf("*/*")) },
            )
            if (newPath.endsWith(".pte", ignoreCase = true)) {
                FilePickerRow(
                    label = "Tokenizer file (required for .pte)",
                    selectedFile = newTokenizer.substringAfterLast('/').substringAfterLast('\\').ifBlank { null },
                    enabled = !isImporting,
                    onClick = { tokenizerPicker.launch(arrayOf("*/*")) },
                )
            }
            if (isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Copying into PocketIDE private storage…", style = MaterialTheme.typography.labelSmall)
            }
            importError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                text = "Prompt template",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PromptTemplate.entries.forEach { template ->
                    SelectableChip(
                        selected = newTemplate == template,
                        label = template.displayName,
                        onClick = { newTemplate = template },
                    )
                }
            }
            Text(
                text = "Auto uses the model filename to select Qwen ChatML or Llama formatting; unknown families use plain formatting.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        fileImporter.deleteImported(newPath)
                        fileImporter.deleteImported(newTokenizer)
                        showAddForm = false
                        newName = ""
                        newPath = ""
                        newTokenizer = ""
                        newTemplate = PromptTemplate.AUTO
                    },
                ) {
                    Text("Cancel")
                }
                androidx.compose.material3.Button(
                    onClick = {
                        if (newName.isNotBlank() && newPath.isNotBlank()) {
                            onAddModel(
                                ModelEntry(
                                    name = newName.trim(),
                                    modelPath = newPath.trim(),
                                    tokenizerPath = newTokenizer.trim(),
                                    promptTemplate = newTemplate,
                                ),
                            )
                            showAddForm = false
                            newName = ""
                            newPath = ""
                            newTokenizer = ""
                            newTemplate = PromptTemplate.AUTO
                        }
                    },
                    enabled = newName.isNotBlank() && newPath.isNotBlank() &&
                        (!newPath.endsWith(".pte", ignoreCase = true) || newTokenizer.isNotBlank()),
                ) {
                    Text("Save")
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                .clickable { showAddForm = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CreateNewFolder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Add Model",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FilePickerRow(
    label: String,
    selectedFile: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CreateNewFolder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                selectedFile ?: "Tap to choose a file",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

private fun detectPromptTemplate(fileName: String): PromptTemplate {
    val normalized = fileName.lowercase()
    return when {
        "qwen" in normalized || "chatml" in normalized -> PromptTemplate.QWEN
        "llama" in normalized || "smollm" in normalized -> PromptTemplate.LLAMA3
        else -> PromptTemplate.AUTO
    }
}

private fun prettyModelName(fileName: String): String = fileName
    .substringBeforeLast('.', fileName)
    .replace(Regex("[-_]+"), " ")
    .trim()

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
        Icon(
            imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Check,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

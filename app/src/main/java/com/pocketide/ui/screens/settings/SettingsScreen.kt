package com.pocketide.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.data.model.Language
import com.pocketide.ui.theme.ThemeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeViewModel: ThemeViewModel,
) {
    var selectedModel by remember { mutableStateOf("None (not loaded yet)") }
    var modelExpanded by remember { mutableStateOf(false) }
    var quantization by remember { mutableStateOf("INT4") }
    var quantExpanded by remember { mutableStateOf(false) }
    var powerSaving by remember { mutableStateOf(false) }
    var thermalAware by remember { mutableStateOf(true) }
    var adaptiveCores by remember { mutableStateOf(true) }
    var maxRepairIterations by remember { mutableStateOf(3f) }
    val enabledLanguages = remember { mutableStateOf(Language.entries.toSet()) }

    val isDarkMode by themeViewModel.isDarkMode.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Appearance Section ---
            SectionHeader("Appearance")

            SwitchRow(
                label = "Dark mode",
                description = "Toggle between dark and light theme",
                checked = isDarkMode,
                onCheckedChange = { themeViewModel.setDarkMode(it) },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // --- AI Model Section ---
            SectionHeader("AI Model")

            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("None (not loaded yet)") },
                        onClick = { selectedModel = "None (not loaded yet)"; modelExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Qwen2.5-Coder-0.5B (INT4)") },
                        onClick = { selectedModel = "Qwen2.5-Coder-0.5B (INT4)"; modelExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Qwen3-0.6B (INT4)") },
                        onClick = { selectedModel = "Qwen3-0.6B (INT4)"; modelExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("SmolLM2-135M (INT4)") },
                        onClick = { selectedModel = "SmolLM2-135M (INT4)"; modelExpanded = false },
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = quantExpanded,
                onExpandedChange = { quantExpanded = it },
            ) {
                OutlinedTextField(
                    value = quantization,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Quantization") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = quantExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = quantExpanded,
                    onDismissRequest = { quantExpanded = false },
                ) {
                    listOf("INT4", "INT8", "FP32").forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode) },
                            onClick = { quantization = mode; quantExpanded = false },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // --- Optimization Section ---
            SectionHeader("Optimization")

            SwitchRow(
                label = "Power saving mode",
                description = "Use little cores for decode, reduce thread count",
                checked = powerSaving,
                onCheckedChange = { powerSaving = it },
            )

            SwitchRow(
                label = "Thermal-aware inference",
                description = "Reduce performance when device throttles",
                checked = thermalAware,
                onCheckedChange = { thermalAware = it },
            )

            SwitchRow(
                label = "Adaptive core selection",
                description = "Big cores for prefill, little cores for decode",
                checked = adaptiveCores,
                onCheckedChange = { adaptiveCores = it },
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // --- Agent Section ---
            SectionHeader("Agent Configuration")

            Text(
                text = "Max repair iterations: ${maxRepairIterations.toInt()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = maxRepairIterations,
                onValueChange = { maxRepairIterations = it },
                valueRange = 1f..10f,
                steps = 8,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // --- Sandbox Section ---
            SectionHeader("Sandbox Languages")

            Language.entries.forEach { language ->
                val enabled = language in enabledLanguages.value
                SwitchRow(
                    label = language.displayName,
                    description = ".${language.fileExtension} files",
                    checked = enabled,
                    onCheckedChange = { isChecked ->
                        enabledLanguages.value = if (isChecked) {
                            enabledLanguages.value + language
                        } else {
                            enabledLanguages.value - language
                        }
                    },
                )
            }
        }
    }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

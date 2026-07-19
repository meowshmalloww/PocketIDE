package com.pocketide.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketide.R
import com.pocketide.data.ai.BenchmarkDashboard
import com.pocketide.data.ai.BenchmarkDashboardMetric
import com.pocketide.data.ai.BenchmarkDepth
import com.pocketide.data.ai.InferencePhase
import com.pocketide.data.ai.PreviousProcessExit
import com.pocketide.data.model.AiMode
import com.pocketide.data.model.AgentStatus
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.ChatSessionSummary
import com.pocketide.data.model.MessageRole
import com.pocketide.data.model.ModelMode
import kotlinx.coroutines.launch

private enum class BenchmarkTab(val label: String) {
    SUMMARY("Summary"),
    REPORT("Report"),
    JSON("JSON"),
}

@Composable
fun AiChatPanel(
    messages: List<ChatMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    onStopGeneration: () -> Unit = {},
    onNewChat: () -> Unit = {},
    chatSessions: List<ChatSessionSummary> = emptyList(),
    activeChatSessionId: String? = null,
    onOpenChatSession: (String) -> Unit = {},
    onDeleteChatSession: (String) -> Unit = {},
    aiMode: AiMode = AiMode.CODE,
    onAiModeChange: (AiMode) -> Unit = {},
    modelMode: ModelMode = ModelMode.SINGLE,
    onModelModeChange: (ModelMode) -> Unit = {},
    isThinking: Boolean = false,
    inferencePhase: InferencePhase = InferencePhase.IDLE,
    previousProcessExit: PreviousProcessExit? = null,
    onDismissPreviousProcessExit: () -> Unit = {},
    lastTokensPerSecond: Float? = null,
    lastTtftMs: Long? = null,
    lastStrategy: String? = null,
    onExportBenchmark: (() -> Unit)? = null,
    benchmarkRunning: Boolean = false,
    benchmarkCompletedRuns: Int = 0,
    benchmarkTotalRuns: Int = 0,
    benchmarkPhase: String? = null,
    benchmarkSummary: BenchmarkDashboard? = null,
    benchmarkReport: String? = null,
    benchmarkJson: String? = null,
    benchmarkError: String? = null,
    onRunBenchmark: (BenchmarkDepth) -> Unit = {},
    onStopBenchmark: () -> Unit = {},
    onCopyBenchmarkJson: () -> Unit = {},
    onClearBenchmark: () -> Unit = {},
) {
    var historyExpanded by remember { mutableStateOf(false) }
    var benchmarkOpen by remember { mutableStateOf(false) }
    var benchmarkDepth by rememberSaveable { mutableStateOf(BenchmarkDepth.QUICK) }
    var benchmarkTab by rememberSaveable { mutableStateOf(BenchmarkTab.SUMMARY) }
    LaunchedEffect(benchmarkSummary) {
        if (benchmarkSummary != null) benchmarkTab = BenchmarkTab.SUMMARY
    }
    if (benchmarkOpen) {
        AlertDialog(
            onDismissRequest = { if (!benchmarkRunning) benchmarkOpen = false },
            title = { Text("On-device benchmark") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Every score comes from the selected on-device model running on this device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BenchmarkDepth.entries.forEach { depth ->
                            TextButton(
                                onClick = { benchmarkDepth = depth },
                                enabled = !benchmarkRunning,
                                modifier = Modifier.weight(1f),
                            ) {
                                val shortLabel = when (depth) {
                                    BenchmarkDepth.QUICK -> "Quick"
                                    BenchmarkDepth.DEEP -> "Deep"
                                    BenchmarkDepth.SUSTAINED -> "Sustain"
                                }
                                Text(if (benchmarkDepth == depth) "● $shortLabel" else shortLabel)
                            }
                        }
                    }
                    Text(
                        "${benchmarkDepth.displayName}: ${benchmarkDepth.description}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (benchmarkRunning) {
                        LinearProgressIndicator(
                            progress = {
                                if (benchmarkTotalRuns == 0) 0f
                                else benchmarkCompletedRuns.toFloat() / benchmarkTotalRuns
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Run $benchmarkCompletedRuns of $benchmarkTotalRuns", style = MaterialTheme.typography.labelSmall)
                        benchmarkPhase?.let { phase ->
                            Text(phase, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    benchmarkError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (benchmarkSummary != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            BenchmarkTab.entries.forEach { tab ->
                                TextButton(onClick = { benchmarkTab = tab }) {
                                    Text(
                                        tab.label,
                                        fontWeight = if (benchmarkTab == tab) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        when (benchmarkTab) {
                            BenchmarkTab.SUMMARY -> BenchmarkDashboardContent(benchmarkSummary)
                            BenchmarkTab.REPORT -> SelectionContainer {
                                Text(
                                    benchmarkReport ?: "Report unavailable",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                )
                            }
                            BenchmarkTab.JSON -> SelectionContainer {
                                Text(
                                    benchmarkJson ?: "{}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                    Text(
                        "Warmups are excluded. End-to-end chat and native kernel measurements stay separate, and unverified acceleration claims are marked explicitly.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (benchmarkRunning) onStopBenchmark() else onRunBenchmark(benchmarkDepth)
                    },
                ) {
                    Text(
                        if (benchmarkRunning) {
                            "Stop"
                        } else if (benchmarkSummary == null) {
                            "Run ${benchmarkDepth.displayName}"
                        } else {
                            "Run again"
                        },
                    )
                }
            },
            dismissButton = {
                Row {
                    if (benchmarkSummary != null) {
                        TextButton(onClick = onCopyBenchmarkJson) { Text("JSON") }
                        onExportBenchmark?.let { copyReport ->
                            TextButton(onClick = copyReport) { Text("Report") }
                        }
                        TextButton(onClick = onClearBenchmark) { Text("Clear") }
                    }
                }
            },
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "AI",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = "AI Assistant",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
            Box {
                IconButton(onClick = { historyExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = "Chat history",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = historyExpanded,
                    onDismissRequest = { historyExpanded = false },
                ) {
                    if (chatSessions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No saved conversations") },
                            onClick = { historyExpanded = false },
                            enabled = false,
                        )
                    } else {
                        chatSessions.forEach { session ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            session.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (session.id == activeChatSessionId) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                        )
                                        Text(
                                            "${session.projectName} · ${session.messageCount} messages",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    historyExpanded = false
                                    onOpenChatSession(session.id)
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { onDeleteChatSession(session.id) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete conversation",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "New",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Benchmark export button — visible after at least one generation
            IconButton(onClick = { benchmarkOpen = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Assessment,
                    contentDescription = "Benchmark",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

        previousProcessExit?.let { exit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Previous run: ${exit.category.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = exit.userMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                IconButton(
                    onClick = onDismissPreviousProcessExit,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss previous exit notice",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Message list or empty state
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (messages.isEmpty()) {
                EmptyAiState()
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size, isGenerating, isThinking) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.lastIndex)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                    if (isGenerating) {
                        item {
                            InferenceIndicator(
                                label = inferencePhase.displayName,
                                prominent = isThinking,
                            )
                        }
                    }
                }
            }
        }

        // Mode and model selectors as dropdowns
        ModeAndModelDropdowns(
            aiMode = aiMode,
            onAiModeChange = onAiModeChange,
            modelMode = modelMode,
            onModelModeChange = onModelModeChange,
        )

        // Compact input area — send icon inside the text field
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            placeholder = {
                Text(
                    text = when (aiMode) {
                        AiMode.CODE -> if (isGenerating) "Generating..." else "Ask AI to write code..."
                        AiMode.ASK -> if (isGenerating) "Generating..." else "Ask a question..."
                        AiMode.PLAN -> if (isGenerating) "Generating..." else "Describe what to plan..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            readOnly = isGenerating || benchmarkRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                IconButton(
                    onClick = { if (isGenerating) onStopGeneration() else onSend() },
                    enabled = isGenerating || (!benchmarkRunning && inputText.isNotBlank()),
                    modifier = Modifier.size(32.dp),
                ) {
                    if (isGenerating) {
                        Icon(
                            imageVector = Icons.Filled.StopCircle,
                            contentDescription = "Stop generation",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun BenchmarkDashboardContent(summary: BenchmarkDashboard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = summary.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = summary.headline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        summary.metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowMetrics.forEach { metric ->
                    BenchmarkMetricCard(metric = metric, modifier = Modifier.weight(1f))
                }
                if (rowMetrics.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
        DashboardEvidence(
            label = stringResource(R.string.benchmark_arm_runtime),
            value = summary.armRuntimeEvidence,
        )
        DashboardEvidence(
            label = stringResource(R.string.benchmark_resource_plan),
            value = summary.resourcePlanEvidence,
        )
        Text(
            text = summary.integrityNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = summary.energyDisclosure,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BenchmarkMetricCard(
    metric: BenchmarkDashboardMetric,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = metric.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DashboardEvidence(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ModeAndModelDropdowns(
    aiMode: AiMode,
    onAiModeChange: (AiMode) -> Unit,
    modelMode: ModelMode,
    onModelModeChange: (ModelMode) -> Unit,
) {
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var agentMenuExpanded by remember { mutableStateOf(false) }

    val modeIcon = when (aiMode) {
        AiMode.CODE -> Icons.Filled.Code
        AiMode.ASK -> Icons.AutoMirrored.Filled.HelpOutline
        AiMode.PLAN -> Icons.AutoMirrored.Filled.ListAlt
    }
    val agentIcon = when (modelMode) {
        ModelMode.SINGLE -> Icons.Filled.Psychology
        ModelMode.SWARM -> Icons.Filled.Hub
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Mode dropdown (Code / Ask / Plan)
        Box(
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { modeMenuExpanded = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = modeIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = aiMode.displayName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            DropdownMenu(
                expanded = modeMenuExpanded,
                onDismissRequest = { modeMenuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                AiMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName, color = MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            onAiModeChange(mode)
                            modeMenuExpanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (mode) {
                                    AiMode.CODE -> Icons.Filled.Code
                                    AiMode.ASK -> Icons.AutoMirrored.Filled.HelpOutline
                                    AiMode.PLAN -> Icons.AutoMirrored.Filled.ListAlt
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }

        // Agent mode dropdown (Single / Swarm)
        Box(
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { agentMenuExpanded = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = agentIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = modelMode.displayName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            DropdownMenu(
                expanded = agentMenuExpanded,
                onDismissRequest = { agentMenuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                ModelMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName, color = MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            onModelModeChange(mode)
                            agentMenuExpanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (mode) {
                                    ModelMode.SINGLE -> Icons.Filled.Psychology
                                    ModelMode.SWARM -> Icons.Filled.Hub
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAiState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "How can I help you?",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Describe what you want to build and I'll generate the code for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
) {
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!isUser && message.role != MessageRole.ASSISTANT) {
            AgentStatusBadge(role = message.role, status = message.agentStatus)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .align(if (isUser) Alignment.End else Alignment.Start)
                .padding(vertical = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(alignment)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MessageContent(
                    content = message.content,
                    textColor = textColor,
                )
            }
        }
        TextButton(
            onClick = {
                clipboardScope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("PocketIDE message", message.content)),
                    )
                }
            },
            modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy")
        }
        // Benchmark metrics under assistant messages
        if (!isUser && message.tokensPerSecond != null) {
            val metrics = buildString {
                append("%.1f tok/s".format(message.tokensPerSecond))
                if (message.ttftMs != null && message.ttftMs >= 0) {
                    append("  TTFT ${message.ttftMs}ms")
                }
                if (message.memoryDeltaMb != null) {
                    append("  mem %+,.1fMB".format(message.memoryDeltaMb))
                }
                if (message.strategy != null) {
                    append("  [${message.strategy}]")
                }
            }
            Text(
                text = metrics,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

private val codeBlockRegex = "```(?:[A-Za-z0-9+#]*\\n)?([\\s\\S]*?)```".toRegex()

@Composable
private fun MessageContent(
    content: String,
    textColor: Color,
) {
    val parts = codeBlockRegex.split(content)
    val codeBlocks = codeBlockRegex.findAll(content).map { it.groupValues[1].trim() }.toList()

    Column {
        parts.forEachIndexed { index, text ->
            if (text.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = text.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor,
                    )
                }
            }
            if (index < codeBlocks.size) {
                CodeBlock(code = codeBlocks[index])
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    clipboardScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("PocketIDE code", code)),
                        )
                    }
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copy code")
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(scrollState),
        ) {
            SelectionContainer {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun AgentStatusBadge(
    role: MessageRole,
    status: AgentStatus?,
) {
    val label = when (role) {
        MessageRole.ARCHITECT -> "ARCHITECT"
        MessageRole.CODER -> "CODER"
        MessageRole.VALIDATOR -> "VALIDATOR"
        else -> role.name.uppercase()
    }
    val statusText = status?.name?.uppercase() ?: "THINKING"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = "$label \u2022 $statusText",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InferenceIndicator(label: String, prominent: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = if (prominent) 8.dp else 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (prominent) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                else Color.Transparent,
            )
            .padding(
                horizontal = if (prominent) 12.dp else 0.dp,
                vertical = if (prominent) 8.dp else 0.dp,
            ),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(if (prominent) 18.dp else 14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (prominent) FontWeight.Medium else FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

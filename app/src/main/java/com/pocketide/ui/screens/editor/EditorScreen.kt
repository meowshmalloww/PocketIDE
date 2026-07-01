package com.pocketide.ui.screens.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketide.ui.components.ActivityBar
import com.pocketide.ui.components.ActivityTab
import com.pocketide.ui.components.AiChatPanel
import com.pocketide.ui.components.FileExplorerPanel
import com.pocketide.ui.components.FileTabBar
import com.pocketide.ui.components.TerminalPanel
import com.pocketide.ui.theme.ThemeViewModel

@Composable
fun EditorScreen(
    onNavigateToSettings: () -> Unit,
    themeViewModel: ThemeViewModel,
    viewModel: EditorViewModel,
) {
    val state by viewModel.state.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val isDark by themeViewModel.isDarkMode.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
        ) {
        // Activity bar + theme toggle at bottom
        Column(modifier = Modifier.fillMaxHeight()) {
            ActivityBar(
                activeTab = state.activeTab,
                onTabSelected = { tab ->
                    if (tab == ActivityTab.SETTINGS) {
                        onNavigateToSettings()
                    } else {
                        viewModel.setActiveTab(tab)
                    }
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { themeViewModel.toggleTheme() },
                modifier = Modifier
                    .width(52.dp)
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = if (isDark) "Light mode" else "Dark mode",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (isLandscape) {
            LandscapeContent(
                state = state,
                viewModel = viewModel,
            )
        } else {
            PortraitContent(
                state = state,
                viewModel = viewModel,
            )
        }
        }
    }
}

@Composable
private fun RowScope.LandscapeContent(
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    // Explorer panel (left, always visible in landscape)
    FileExplorerPanel(
        files = state.files,
        activeFileIndex = state.activeFileIndex,
        onFileSelected = viewModel::selectFile,
        onFileCreated = viewModel::createNewFile,
        modifier = Modifier.width(180.dp),
    )

    // Center: editor + terminal
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
    ) {
        EditorArea(state = state, viewModel = viewModel)

        // Terminal at bottom (collapsible)
        TerminalPanel(
            stdout = state.stdout,
            stderr = state.stderr,
            status = state.executionStatus,
            isExpanded = state.terminalExpanded,
            onToggle = viewModel::toggleTerminal,
            onRun = viewModel::runCode,
            onRetry = viewModel::retryRepair,
        )
    }

    // AI Chat panel (right, toggleable)
    AnimatedVisibility(
        visible = state.activeTab == ActivityTab.AI_CHAT,
        enter = expandHorizontally(),
        exit = shrinkHorizontally(),
    ) {
        Box(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            AiChatPanel(
                messages = state.messages,
                inputText = state.inputText,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                isGenerating = state.isGenerating,
            )
        }
    }
}

@Composable
private fun RowScope.PortraitContent(
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    when (state.activeTab) {
        ActivityTab.EXPLORER -> {
            FileExplorerPanel(
                files = state.files,
                activeFileIndex = state.activeFileIndex,
                onFileSelected = { index ->
                    viewModel.selectFile(index)
                    viewModel.setActiveTab(ActivityTab.EDITOR)
                },
                onFileCreated = viewModel::createNewFile,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }

        ActivityTab.AI_CHAT -> {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                AiChatPanel(
                    messages = state.messages,
                    inputText = state.inputText,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::sendMessage,
                    isGenerating = state.isGenerating,
                )
            }
        }

        ActivityTab.TERMINAL -> {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                EditorArea(state = state, viewModel = viewModel)
                TerminalPanel(
                    stdout = state.stdout,
                    stderr = state.stderr,
                    status = state.executionStatus,
                    isExpanded = true,
                    onToggle = { viewModel.setActiveTab(ActivityTab.EDITOR) },
                    onRun = viewModel::runCode,
                    onRetry = viewModel::retryRepair,
                )
            }
        }

        ActivityTab.EDITOR, ActivityTab.EXTENSIONS -> {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                EditorArea(state = state, viewModel = viewModel)
                TerminalPanel(
                    stdout = state.stdout,
                    stderr = state.stderr,
                    status = state.executionStatus,
                    isExpanded = state.terminalExpanded,
                    onToggle = viewModel::toggleTerminal,
                    onRun = viewModel::runCode,
                    onRetry = viewModel::retryRepair,
                )
            }
        }

        ActivityTab.SETTINGS -> {
            Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ColumnScope.EditorArea(
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    // File tabs
    if (state.files.isNotEmpty()) {
        FileTabBar(
            files = state.files,
            activeIndex = state.activeFileIndex,
            onTabSelected = viewModel::selectFile,
            onTabClosed = viewModel::closeFile,
        )
    }

    // Code editor area
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(8.dp),
    ) {
        if (state.files.isEmpty()) {
            Text(
                text = "// No file open\n// Use the Explorer to create a file\n// or ask AI to generate code",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            BasicTextField(
                value = state.activeFileContent,
                onValueChange = viewModel::onCodeChange,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

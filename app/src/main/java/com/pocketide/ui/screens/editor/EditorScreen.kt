package com.pocketide.ui.screens.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketide.data.model.Language
import com.pocketide.ui.components.ActivityBar
import com.pocketide.ui.components.ActivityTab
import com.pocketide.ui.components.AiChatPanel
import com.pocketide.ui.components.BottomActivityBar
import com.pocketide.ui.components.ExtensionsPanel
import com.pocketide.ui.components.FileExplorerPanel
import com.pocketide.ui.components.ProjectSwitcherDialog
import com.pocketide.ui.components.TerminalPanel
import com.pocketide.ui.components.TopTabBar
import com.pocketide.ui.editor.CodeEditorField
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
        if (isLandscape) {
            LandscapeLayout(
                state = state,
                viewModel = viewModel,
                isDark = isDark,
                onNavigateToSettings = onNavigateToSettings,
            )
        } else {
            PortraitLayout(
                state = state,
                viewModel = viewModel,
                isDark = isDark,
                onNavigateToSettings = onNavigateToSettings,
            )
        }

        if (state.showProjectDialog) {
            ProjectSwitcherDialog(
                projects = state.projects,
                activeProject = state.projectName,
                onCreateProject = viewModel::createProject,
                onSwitchProject = viewModel::switchProject,
                onDeleteProject = viewModel::deleteProject,
                onDismiss = viewModel::hideProjectDialog,
            )
        }
    }
}

// === LANDSCAPE: Side activity bar + explorer + editor + terminal + AI panel ===

@Composable
private fun LandscapeLayout(
    state: EditorUiState,
    viewModel: EditorViewModel,
    isDark: Boolean,
    onNavigateToSettings: () -> Unit,
) {
    var explorerWidth by remember { mutableStateOf(320f) }
    var terminalHeight by remember { mutableStateOf(300f) }
    var aiWidth by remember { mutableStateOf(900f) }
    val density = LocalDensity.current

    Row(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Side activity bar (far left) — with status bar padding
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding(),
        ) {
            ActivityBar(
                activeTab = state.activeTab,
                isExplorerOpen = state.activeTab != ActivityTab.AI_CHAT && state.activeTab != ActivityTab.EXTENSIONS,
                isTerminalOpen = state.terminalExpanded && state.activeTab != ActivityTab.AI_CHAT && state.activeTab != ActivityTab.EXTENSIONS,
                onTabSelected = { tab ->
                    if (tab == ActivityTab.SETTINGS) {
                        onNavigateToSettings()
                    } else {
                        viewModel.setActiveTab(tab)
                    }
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // Full-page AI or Extensions panel (no explorer, no terminal, no toggle arrow)
        if (state.activeTab == ActivityTab.AI_CHAT || state.activeTab == ActivityTab.EXTENSIONS) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (state.activeTab == ActivityTab.AI_CHAT) {
                    AiChatPanel(
                        messages = state.messages,
                        inputText = state.inputText,
                        onInputChange = viewModel::onInputChange,
                        onSend = viewModel::sendMessage,
                        isGenerating = state.isGenerating,
                        onNewChat = viewModel::newChat,
                        aiMode = state.aiMode,
                        onAiModeChange = viewModel::setAiMode,
                        modelMode = state.modelMode,
                        onModelModeChange = viewModel::setModelMode,
                    )
                } else {
                    ExtensionsPanel(
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            return
        }

        // Explorer panel (draggable width)
        FileExplorerPanel(
            files = state.files,
            activeFileIndex = state.activeFileIndex,
            onFileSelected = viewModel::selectFile,
            onFileCreated = viewModel::createNewFile,
            onFileDeleted = viewModel::deleteFile,
            projectName = state.projectName,
            onProjectClick = viewModel::showProjectDialog,
            modifier = Modifier
                .width(with(density) { explorerWidth.toDp() })
                .fillMaxHeight()
                .statusBarsPadding(),
        )

        // Vertical drag handle for explorer
        VerticalDragHandle(
            onDragDelta = { delta ->
                explorerWidth = (explorerWidth + delta / density.density).coerceIn(160f, 500f)
            },
        )

        // Center: editor + terminal + AI overlay (side by side, not overlay)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .statusBarsPadding(),
        ) {
            // Editor + terminal column (shrinks when AI overlay is open)
            Column(
                modifier = Modifier
                    .weight(1f, fill = !state.showAiOverlay)
                    .fillMaxHeight(),
            ) {
                LandscapeEditorArea(state = state, viewModel = viewModel)

                // Horizontal drag handle for terminal
                HorizontalDragHandle(
                    onDragDelta = { delta ->
                        terminalHeight = (terminalHeight - delta / density.density).coerceIn(60f, 500f)
                    },
                )

                TerminalPanel(
                    stdout = state.stdout,
                    stderr = state.stderr,
                    status = state.executionStatus,
                    isExpanded = state.terminalExpanded,
                    onToggle = viewModel::toggleTerminal,
                    onRun = viewModel::runCode,
                    onRetry = viewModel::retryRepair,
                    modifier = Modifier.height(with(density) { terminalHeight.toDp() }),
                )
            }

            // AI slide-out panel (right side, toggled by arrow button)
            AiChatOverlay(
                state = state,
                viewModel = viewModel,
                panelWidth = with(density) { aiWidth.toDp() },
                onWidthDrag = { delta ->
                    aiWidth = (aiWidth - delta / density.density).coerceIn(300f, 1400f)
                },
            )

            // Toggle arrow button for AI overlay (right edge)
            AiOverlayToggleButton(
                isOverlayOpen = state.showAiOverlay,
                onClick = viewModel::toggleAiOverlay,
            )
        }
    }
}

@Composable
private fun VerticalDragHandle(
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(8.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDragDelta(dragAmount.x)
                }
            },
    )
}

@Composable
private fun HorizontalDragHandle(
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onDragDelta(dragAmount.y)
                }
            },
    )
}

@Composable
private fun ColumnScope.LandscapeEditorArea(
    state: EditorUiState,
    viewModel: EditorViewModel,
) {
    // File tabs — only show when on code editor view
    val activeFile = state.files.getOrNull(state.activeFileIndex)
    val hasUnsaved = activeFile?.isModified == true
    if (state.activeTab != ActivityTab.AI_CHAT && state.activeTab != ActivityTab.EXTENSIONS) {
        TopTabBar(
            files = state.files,
            activeFileIndex = state.activeFileIndex,
            onFileTabSelected = { index ->
                viewModel.setAiTabActive(false)
                viewModel.selectFile(index)
            },
            onFileTabClosed = viewModel::closeFile,
            hasUnsavedChanges = hasUnsaved,
            onSave = viewModel::saveActiveFile,
        )
    }

    // Code editor area or AI page
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(8.dp),
    ) {
        if (state.activeTab == ActivityTab.AI_CHAT) {
            AiChatPanel(
                messages = state.messages,
                inputText = state.inputText,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::sendMessage,
                isGenerating = state.isGenerating,
                onNewChat = viewModel::newChat,
                aiMode = state.aiMode,
                onAiModeChange = viewModel::setAiMode,
                modelMode = state.modelMode,
                onModelModeChange = viewModel::setModelMode,
            )
        } else if (state.files.isEmpty()) {
            Text(
                text = "// No file open\n// Use the Explorer to create a file\n// or ask AI to generate code",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            val activeLanguage = state.files.getOrNull(state.activeFileIndex)?.language
                ?: Language.PYTHON
            CodeEditorField(
                value = state.activeFileContent,
                onValueChange = viewModel::onCodeChange,
                language = activeLanguage,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AiOverlayToggleButton(
    isOverlayOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(18.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isOverlayOpen) {
                Icons.Filled.ChevronRight
            } else {
                Icons.Filled.ChevronLeft
            },
            contentDescription = if (isOverlayOpen) "Close AI panel" else "Open AI panel",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun AiChatOverlay(
    state: EditorUiState,
    viewModel: EditorViewModel,
    panelWidth: androidx.compose.ui.unit.Dp,
    onWidthDrag: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.showAiOverlay,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle on left edge of AI panel (invisible)
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            onWidthDrag(dragAmount.x)
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                AiChatPanel(
                    messages = state.messages,
                    inputText = state.inputText,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::sendMessage,
                    isGenerating = state.isGenerating,
                    onNewChat = viewModel::newChat,
                    aiMode = state.aiMode,
                    onAiModeChange = viewModel::setAiMode,
                    modelMode = state.modelMode,
                    onModelModeChange = viewModel::setModelMode,
                )
            }
        }
    }
}

// === PORTRAIT: Top tab bar + explorer sidebar + code/terminal split + bottom nav ===

@Composable
private fun PortraitLayout(
    state: EditorUiState,
    viewModel: EditorViewModel,
    isDark: Boolean,
    onNavigateToSettings: () -> Unit,
) {
    var explorerWidth by remember { mutableStateOf(280f) }
    var terminalHeight by remember { mutableStateOf(320f) }
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // Main content row: explorer + code column — with status bar padding at top
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            // Explorer sidebar — draggable width
            if (state.showExplorer && !state.isAiTabActive && state.activeTab != ActivityTab.EXTENSIONS) {
                FileExplorerPanel(
                    files = state.files,
                    activeFileIndex = state.activeFileIndex,
                    onFileSelected = { index ->
                        viewModel.selectFile(index)
                    },
                    onFileCreated = viewModel::createNewFile,
                    onFileDeleted = viewModel::deleteFile,
                    projectName = state.projectName,
                    onProjectClick = viewModel::showProjectDialog,
                    modifier = Modifier
                        .width(with(density) { explorerWidth.toDp() })
                        .fillMaxHeight(),
                )

                // Vertical drag handle for explorer
                VerticalDragHandle(
                    onDragDelta = { delta ->
                        explorerWidth = (explorerWidth + delta / density.density).coerceIn(120f, 400f)
                    },
                )
            }

            // Right column: tab bar + content + terminal
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // Tab bar — only show when on code editor view
                val activeFile = state.files.getOrNull(state.activeFileIndex)
                val hasUnsaved = activeFile?.isModified == true
                if (!state.isAiTabActive && state.activeTab != ActivityTab.EXTENSIONS) {
                    TopTabBar(
                        files = state.files,
                        activeFileIndex = state.activeFileIndex,
                        onFileTabSelected = { index ->
                            viewModel.setAiTabActive(false)
                            viewModel.selectFile(index)
                        },
                        onFileTabClosed = viewModel::closeFile,
                        hasUnsavedChanges = hasUnsaved,
                        onSave = viewModel::saveActiveFile,
                    )
                }

                // Content area
                if (state.isAiTabActive) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        AiChatPanel(
                            messages = state.messages,
                            inputText = state.inputText,
                            onInputChange = viewModel::onInputChange,
                            onSend = viewModel::sendMessage,
                            isGenerating = state.isGenerating,
                            onNewChat = viewModel::newChat,
                            aiMode = state.aiMode,
                            onAiModeChange = viewModel::setAiMode,
                            modelMode = state.modelMode,
                            onModelModeChange = viewModel::setModelMode,
                        )
                    }
                } else if (state.activeTab == ActivityTab.EXTENSIONS) {
                    ExtensionsPanel(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                } else {
                    // Code editor — takes remaining space
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(8.dp),
                    ) {
                        if (state.files.isEmpty()) {
                            Text(
                                text = "// No file open\n// Tap the folder icon to create a file\n// or switch to AI tab to generate code",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        } else {
                            val activeLanguage = state.files.getOrNull(state.activeFileIndex)?.language
                                ?: Language.PYTHON
                            CodeEditorField(
                                value = state.activeFileContent,
                                onValueChange = viewModel::onCodeChange,
                                language = activeLanguage,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // Horizontal drag handle for terminal
                    if (state.terminalExpanded) {
                        HorizontalDragHandle(
                            onDragDelta = { delta ->
                                terminalHeight = (terminalHeight - delta / density.density).coerceIn(60f, 500f)
                            },
                        )
                    }

                    // Terminal — draggable height when expanded
                    TerminalPanel(
                        stdout = state.stdout,
                        stderr = state.stderr,
                        status = state.executionStatus,
                        isExpanded = state.terminalExpanded,
                        onToggle = viewModel::toggleTerminal,
                        onRun = viewModel::runCode,
                        onRetry = viewModel::retryRepair,
                        modifier = if (state.terminalExpanded) {
                            Modifier.height(with(density) { terminalHeight.toDp() })
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }

        // Bottom activity bar — with navigation bar padding
        BottomActivityBar(
            activeTab = if (state.isAiTabActive) ActivityTab.AI_CHAT else state.activeTab,
            isExplorerOpen = state.showExplorer && !state.isAiTabActive && state.activeTab != ActivityTab.EXTENSIONS,
            isTerminalOpen = state.terminalExpanded,
            onTabSelected = { tab ->
                when (tab) {
                    ActivityTab.AI_CHAT -> viewModel.setAiTabActive(true)
                    ActivityTab.EXPLORER -> {
                        viewModel.setAiTabActive(false)
                        viewModel.setActiveTab(ActivityTab.EDITOR)
                        viewModel.toggleExplorer()
                    }
                    ActivityTab.SETTINGS -> onNavigateToSettings()
                    ActivityTab.TERMINAL -> {
                        viewModel.setAiTabActive(false)
                        viewModel.setActiveTab(ActivityTab.EDITOR)
                        viewModel.toggleTerminal()
                    }
                    ActivityTab.EXTENSIONS -> {
                        viewModel.setAiTabActive(false)
                        viewModel.setActiveTab(ActivityTab.EXTENSIONS)
                    }
                    ActivityTab.EDITOR -> {
                        viewModel.setAiTabActive(false)
                        viewModel.setActiveTab(ActivityTab.EDITOR)
                    }
                }
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

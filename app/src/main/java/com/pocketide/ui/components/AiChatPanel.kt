package com.pocketide.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.data.model.AiMode
import com.pocketide.data.model.AgentStatus
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.MessageRole
import com.pocketide.data.model.ModelMode

@Composable
fun AiChatPanel(
    messages: List<ChatMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    onNewChat: () -> Unit = {},
    aiMode: AiMode = AiMode.CODE,
    onAiModeChange: (AiMode) -> Unit = {},
    modelMode: ModelMode = ModelMode.SINGLE,
    onModelModeChange: (ModelMode) -> Unit = {},
    isThinking: Boolean = false,
) {
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
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )

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
                            if (isThinking) {
                                ThinkingIndicator()
                            } else {
                                GeneratingIndicator()
                            }
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
            enabled = !isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                IconButton(
                    onClick = onSend,
                    enabled = !isGenerating && inputText.isNotBlank(),
                    modifier = Modifier.size(32.dp),
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface,
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
        // Tokens per second under assistant messages
        if (!isUser && message.tokensPerSecond != null) {
            Text(
                text = "%.1f tok/s".format(message.tokensPerSecond),
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
                Text(
                    text = text.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(scrollState),
        ) {
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
private fun GeneratingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Generating...",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThinkingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .rotate(rotation),
        )
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

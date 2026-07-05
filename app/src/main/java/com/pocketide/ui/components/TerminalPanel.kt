package com.pocketide.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.ui.theme.LocalIsDarkTheme
import com.pocketide.ui.theme.ThemeColors

@Composable
fun TerminalPanel(
    stdout: String,
    stderr: String,
    status: ExecutionStatus,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(ThemeColors.consoleBg(isDark)),
    ) {
        // Terminal header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Terminal,
                contentDescription = "Terminal",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp, end = 12.dp),
            )

            // Status indicator
            val statusText = when (status) {
                ExecutionStatus.IDLE -> ""
                ExecutionStatus.RUNNING -> "running"
                ExecutionStatus.PASSED -> "passed"
                ExecutionStatus.FAILED -> "failed"
            }
            val statusColor = when (status) {
                ExecutionStatus.PASSED -> ThemeColors.consoleSuccess(isDark)
                ExecutionStatus.FAILED -> ThemeColors.consoleStderr(isDark)
                ExecutionStatus.RUNNING -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            if (statusText.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    when (status) {
                        ExecutionStatus.PASSED -> Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp),
                        )
                        ExecutionStatus.FAILED -> Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp),
                        )
                        ExecutionStatus.RUNNING -> CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = statusColor,
                        )
                        else -> {}
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons
            IconButton(onClick = onRun, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Run",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (status == ExecutionStatus.FAILED) {
                IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Retry repair",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Terminal output (collapsible)
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val output = buildString {
                if (stdout.isNotBlank()) append(stdout)
                if (stderr.isNotBlank()) {
                    if (stdout.isNotBlank()) append("\n")
                    append(stderr)
                }
            }

            val scrollState = rememberScrollState()
            LaunchedEffect(output) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                if (output.isBlank()) {
                    Text(
                        text = "$ ",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = ThemeColors.consoleStdout(isDark),
                    )
                } else {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (status == ExecutionStatus.FAILED && stderr.isNotBlank()) {
                            ThemeColors.consoleStderr(isDark)
                        } else {
                            ThemeColors.consoleStdout(isDark)
                        },
                    )
                }
            }
        }
    }
}

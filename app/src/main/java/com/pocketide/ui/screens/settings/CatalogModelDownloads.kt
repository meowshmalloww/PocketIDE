package com.pocketide.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.R
import com.pocketide.data.ai.CatalogDownloadPhase
import com.pocketide.data.ai.CatalogDownloadState
import com.pocketide.data.ai.ModelCatalog
import com.pocketide.data.ai.ModelCatalogDownloadManager
import com.pocketide.data.ai.ModelCatalogEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun CatalogModelDownloads(onModelActivated: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { ModelCatalogDownloadManager(context) }
    val scope = rememberCoroutineScope()
    val latestActivationCallback by rememberUpdatedState(onModelActivated)
    val startFailureMessage = stringResource(R.string.model_catalog_start_failed)
    val cancelFailureMessage = stringResource(R.string.model_catalog_cancel_failed)
    var states by remember {
        mutableStateOf(
            ModelCatalog.entries.associate { entry ->
                entry.id to CatalogDownloadState(
                    phase = CatalogDownloadPhase.AVAILABLE,
                    totalBytes = entry.totalBytes,
                )
            },
        )
    }
    var actionErrors by remember { mutableStateOf(emptyMap<String, String>()) }
    var reportedInstalled by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(manager) {
        while (currentCoroutineContext().isActive) {
            val refreshed = ModelCatalog.entries.associate { entry ->
                entry.id to resolveCatalogState(manager, entry)
            }
            states = refreshed
            val installed = refreshed.filterValues {
                it.phase == CatalogDownloadPhase.INSTALLED
            }.keys
            if ((installed - reportedInstalled).isNotEmpty()) latestActivationCallback()
            reportedInstalled = installed
            delay(1_000)
        }
    }

    Text(
        text = stringResource(R.string.model_catalog_title),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.model_catalog_explanation),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelCatalog.entries.forEach { entry ->
            CatalogModelCard(
                entry = entry,
                state = states.getValue(entry.id),
                actionError = actionErrors[entry.id],
                onStart = {
                    scope.launch {
                        val result = manager.start(entry)
                        actionErrors = if (result.isSuccess) {
                            actionErrors - entry.id
                        } else {
                            actionErrors + (
                                entry.id to (result.exceptionOrNull()?.message
                                    ?: startFailureMessage)
                                )
                        }
                        states = states + (entry.id to resolveCatalogState(manager, entry))
                    }
                },
                onCancel = {
                    scope.launch {
                        runCatching { manager.cancel(entry) }.onFailure { error ->
                            actionErrors = actionErrors + (
                                entry.id to (error.message
                                    ?: cancelFailureMessage)
                                )
                        }
                        states = states + (entry.id to resolveCatalogState(manager, entry))
                    }
                },
            )
        }
    }
}

@Composable
private fun CatalogModelCard(
    entry: ModelCatalogEntry,
    state: CatalogDownloadState,
    actionError: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val isActive = state.phase == CatalogDownloadPhase.QUEUED ||
        state.phase == CatalogDownloadPhase.DOWNLOADING ||
        state.phase == CatalogDownloadPhase.PAUSED
    val description = when (entry.id) {
        ModelCatalog.qwenCoder.id -> stringResource(R.string.model_catalog_qwen_description)
        else -> stringResource(R.string.model_catalog_llama_description)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (state.phase == CatalogDownloadPhase.INSTALLED) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.Download
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = catalogStatusText(state.phase),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.phase == CatalogDownloadPhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            CatalogActionButton(state.phase, onStart, onCancel)
        }

        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.model_catalog_metadata,
                formatBytes(entry.totalBytes),
                entry.licenseLabel,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isActive || state.phase == CatalogDownloadPhase.VERIFYING) {
            LinearProgressIndicator(
                progress = { if (state.phase == CatalogDownloadPhase.VERIFYING) 1f else state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.model_catalog_progress,
                    formatBytes(state.downloadedBytes),
                    formatBytes(state.totalBytes),
                    (state.progress * 100).toInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        (actionError ?: state.detail)?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (actionError != null || state.phase == CatalogDownloadPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(
            onClick = { uriHandler.openUri(entry.sourceUrl) },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(R.string.model_catalog_view_source))
        }
    }
}

@Composable
private fun CatalogActionButton(
    phase: CatalogDownloadPhase,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    when (phase) {
        CatalogDownloadPhase.QUEUED,
        CatalogDownloadPhase.DOWNLOADING,
        CatalogDownloadPhase.PAUSED,
        -> Button(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        CatalogDownloadPhase.AVAILABLE ->
            Button(onClick = onStart) { Text(stringResource(R.string.action_download)) }
        CatalogDownloadPhase.FAILED ->
            Button(onClick = onStart) { Text(stringResource(R.string.action_retry)) }
        CatalogDownloadPhase.VERIFYING,
        CatalogDownloadPhase.INSTALLED,
        -> Unit
    }
}

@Composable
private fun catalogStatusText(phase: CatalogDownloadPhase): String = stringResource(
    when (phase) {
        CatalogDownloadPhase.AVAILABLE -> R.string.model_catalog_status_available
        CatalogDownloadPhase.QUEUED -> R.string.model_catalog_status_queued
        CatalogDownloadPhase.DOWNLOADING -> R.string.model_catalog_status_downloading
        CatalogDownloadPhase.PAUSED -> R.string.model_catalog_status_paused
        CatalogDownloadPhase.VERIFYING -> R.string.model_catalog_status_verifying
        CatalogDownloadPhase.INSTALLED -> R.string.model_catalog_status_installed
        CatalogDownloadPhase.FAILED -> R.string.model_catalog_status_failed
    },
)

private suspend fun resolveCatalogState(
    manager: ModelCatalogDownloadManager,
    entry: ModelCatalogEntry,
): CatalogDownloadState {
    val current = manager.state(entry)
    return if (current.phase == CatalogDownloadPhase.VERIFYING) {
        manager.finalizeIfComplete(entry)
    } else {
        current
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
}

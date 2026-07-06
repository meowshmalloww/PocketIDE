package com.pocketide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language

@Composable
fun TopTabBar(
    files: List<CodeFile>,
    activeFileIndex: Int,
    onFileTabSelected: (Int) -> Unit,
    onFileTabClosed: (Int) -> Unit,
    hasUnsavedChanges: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
        ) {
        // File tabs
        LazyRow(
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(files) { index, file ->
                val isActive = index == activeFileIndex
                FileTab(
                    file = file,
                    isActive = isActive,
                    canClose = files.size > 1,
                    onClick = { onFileTabSelected(index) },
                    onClose = { onFileTabClosed(index) },
                )
            }
        }

        // Save button
        if (hasUnsavedChanges) {
            IconButton(
                onClick = onSave,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = "Save file",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        }

    }
}

@Composable
private fun FileTab(
    file: CodeFile,
    isActive: Boolean,
    canClose: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val textColor = if (isActive) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .height(34.dp)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            // Language abbreviation badge
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(languageColor(file.language).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = languageAbbrev(file.language),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = languageColor(file.language),
                )
            }
            Text(
                text = file.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.isModified) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            // Close button — always show if canClose, else show a subtle dot
            if (canClose) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close ${file.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        // Active indicator — bottom border line like VS Code
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(borderColor),
        )
    }
}


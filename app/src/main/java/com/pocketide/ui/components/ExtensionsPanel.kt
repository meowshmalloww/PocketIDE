package com.pocketide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.data.model.Language

private data class LanguageSupport(
    val language: Language,
    val canExecute: Boolean,
)

private val SUPPORTED_LANGUAGES = listOf(
    LanguageSupport(Language.JAVASCRIPT, canExecute = true),
    LanguageSupport(Language.LUA, canExecute = true),
    LanguageSupport(Language.SHELL, canExecute = true),
    LanguageSupport(Language.SQL, canExecute = true),
    LanguageSupport(Language.PYTHON, canExecute = false),
    LanguageSupport(Language.TYPESCRIPT, canExecute = false),
    LanguageSupport(Language.KOTLIN, canExecute = false),
    LanguageSupport(Language.DART, canExecute = false),
    LanguageSupport(Language.JAVA, canExecute = true),
    LanguageSupport(Language.HTML, canExecute = false),
    LanguageSupport(Language.CSS, canExecute = false),
    LanguageSupport(Language.YAML, canExecute = false),
    LanguageSupport(Language.MARKDOWN, canExecute = false),
    LanguageSupport(Language.JSON, canExecute = false),
)

@Composable
fun ExtensionsPanel(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = "Extensions",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Extensions",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Code,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Language Support",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(SUPPORTED_LANGUAGES, key = { it.language.name }) { support ->
                ExtensionItem(support = support)
            }
        }
    }
}

@Composable
private fun ExtensionItem(support: LanguageSupport) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Icon(
            imageVector = if (support.canExecute) Icons.Filled.Lightbulb else Icons.Filled.Code,
            contentDescription = null,
            tint = if (support.canExecute) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = support.language.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (support.canExecute) "Runnable" else "Syntax only",
            style = MaterialTheme.typography.labelSmall,
            color = if (support.canExecute) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

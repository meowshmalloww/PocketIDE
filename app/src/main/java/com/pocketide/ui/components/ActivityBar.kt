package com.pocketide.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketide.ui.theme.LocalIsDarkTheme
import com.pocketide.ui.theme.ThemeColors

enum class ActivityTab(val icon: ImageVector, val label: String) {
    EXPLORER(Icons.Filled.FolderOpen, "Explorer"),
    EDITOR(Icons.Filled.Edit, "Editor"),
    AI_CHAT(Icons.Filled.Lightbulb, "AI Chat"),
    TERMINAL(Icons.Filled.Terminal, "Terminal"),
    EXTENSIONS(Icons.Filled.Build, "Extensions"),
    SETTINGS(Icons.Filled.Settings, "Settings"),
}

@Composable
fun ActivityBar(
    activeTab: ActivityTab,
    onTabSelected: (ActivityTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(ThemeColors.activityBarBg(LocalIsDarkTheme.current))
            .padding(vertical = 8.dp),
    ) {
        ActivityTab.entries.forEach { tab ->
            ActivityIconButton(
                icon = tab.icon,
                label = tab.label,
                isActive = tab == activeTab,
                onClick = { onTabSelected(tab) },
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ActivityIconButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    val tint by animateColorAsState(
        targetValue = if (isActive) ThemeColors.activityBarActive(isDark) else ThemeColors.activityBarInactive(isDark),
        animationSpec = tween(200),
        label = "activityIconTint",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
    ) {
        // Active indicator stripe on left
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(ThemeColors.activityBarActive(isDark)),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

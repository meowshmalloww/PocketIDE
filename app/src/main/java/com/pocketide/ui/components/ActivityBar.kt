package com.pocketide.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    AI_CHAT(Icons.Filled.AutoAwesome, "AI Chat"),
    TERMINAL(Icons.Filled.Terminal, "Terminal"),
    EXTENSIONS(Icons.Filled.Extension, "Extensions"),
    SETTINGS(Icons.Filled.Settings, "Settings"),
}

@Composable
fun ActivityBar(
    activeTab: ActivityTab,
    onTabSelected: (ActivityTab) -> Unit,
    isExplorerOpen: Boolean = false,
    isTerminalOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(52.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(ThemeColors.activityBarBg(isDark))
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        // Top group: primary tools (above camera)
        ActivityIconButton(
            icon = ActivityTab.EXPLORER.icon,
            label = ActivityTab.EXPLORER.label,
            isActive = isExplorerOpen,
            onClick = { onTabSelected(ActivityTab.EXPLORER) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ActivityIconButton(
            icon = ActivityTab.AI_CHAT.icon,
            label = ActivityTab.AI_CHAT.label,
            isActive = activeTab == ActivityTab.AI_CHAT,
            onClick = { onTabSelected(ActivityTab.AI_CHAT) },
        )

        // Middle spacer — camera-safe area
        Spacer(modifier = Modifier.weight(1f))

        // Bottom group: secondary tools (below camera)
        ActivityIconButton(
            icon = ActivityTab.TERMINAL.icon,
            label = ActivityTab.TERMINAL.label,
            isActive = isTerminalOpen,
            onClick = { onTabSelected(ActivityTab.TERMINAL) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ActivityIconButton(
            icon = ActivityTab.SETTINGS.icon,
            label = ActivityTab.SETTINGS.label,
            isActive = activeTab == ActivityTab.SETTINGS,
            onClick = { onTabSelected(ActivityTab.SETTINGS) },
        )
    }
}

@Composable
fun BottomActivityBar(
    activeTab: ActivityTab,
    onTabSelected: (ActivityTab) -> Unit,
    isExplorerOpen: Boolean = false,
    isTerminalOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current
    val barColor = ThemeColors.activityBarBg(isDark)
    val visibleTabs = listOf(
        ActivityTab.EXPLORER,
        ActivityTab.AI_CHAT,
        ActivityTab.TERMINAL,
        ActivityTab.SETTINGS,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(barColor),
    ) {
        visibleTabs.forEach { tab ->
            val isFeatureActive = when (tab) {
                ActivityTab.EXPLORER -> isExplorerOpen
                ActivityTab.TERMINAL -> isTerminalOpen
                else -> tab == activeTab
            }
            BottomNavIcon(
                icon = tab.icon,
                label = tab.label,
                isActive = isFeatureActive,
                onClick = { onTabSelected(tab) },
            )
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
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            ThemeColors.activityBarInactive(isDark)
        },
        animationSpec = tween(200),
        label = "activityIconTint",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BottomNavIcon(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    val tint by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            ThemeColors.activityBarInactive(isDark)
        },
        animationSpec = tween(200),
        label = "bottomNavIconTint",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

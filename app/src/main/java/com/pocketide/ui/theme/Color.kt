package com.pocketide.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// === DARK THEME (Carbon + White) ===
val DarkBackground = Color(0xFF0E0E10)
val DarkSurface = Color(0xFF161618)
val DarkSurfaceVariant = Color(0xFF1E1E22)
val DarkPrimary = Color(0xFFE8E8E8)
val DarkOnPrimary = Color(0xFF0E0E10)
val DarkSecondary = Color(0xFF2A2A2E)
val DarkOnSurface = Color(0xFFD4D4D8)
val DarkOnSurfaceVariant = Color(0xFF8B8B92)
val DarkOutline = Color(0xFF2E2E34)
val DarkError = Color(0xFFEF4444)

val DarkActivityBarBg = Color(0xFF0A0A0C)
val DarkActivityBarActive = Color(0xFFE8E8E8)
val DarkActivityBarInactive = Color(0xFF5C5C66)

val DarkChatUserBubble = Color(0xFF1E1E22)
val DarkChatAssistantBubble = Color(0xFF161618)
val DarkChatUserText = Color(0xFFD4D4D8)
val DarkChatAssistantText = Color(0xFFB4B4BC)
val DarkChatAgentLabel = Color(0xFF8B8B92)

val DarkConsoleBg = Color(0xFF08080A)
val DarkConsoleStdout = Color(0xFFB8B8C0)
val DarkConsoleStderr = Color(0xFFEF4444)
val DarkConsoleSuccess = Color(0xFF22C55E)

val DarkAgentArchitect = Color(0xFFE8E8E8)
val DarkAgentCoder = Color(0xFF22C55E)
val DarkAgentValidator = Color(0xFF38BDF8)
val DarkAgentIdle = Color(0xFF5C5C66)

// === LIGHT THEME (Warm Paper + Amber) ===
val LightBackground = Color(0xFFFAF8F5)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF3F0EB)
val LightPrimary = Color(0xFFB45309)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFFE7E2D9)
val LightOnSurface = Color(0xFF1C1917)
val LightOnSurfaceVariant = Color(0xFF78716C)
val LightOutline = Color(0xFFD6D3D1)
val LightError = Color(0xFFDC2626)

val LightActivityBarBg = Color(0xFFF3F0EB)
val LightActivityBarActive = Color(0xFFB45309)
val LightActivityBarInactive = Color(0xFFA8A29E)

val LightChatUserBubble = Color(0xFFFEF3C7)
val LightChatAssistantBubble = Color(0xFFFAF8F5)
val LightChatUserText = Color(0xFF1C1917)
val LightChatAssistantText = Color(0xFF44403C)
val LightChatAgentLabel = Color(0xFF78716C)

val LightConsoleBg = Color(0xFFF3F0EB)
val LightConsoleStdout = Color(0xFF92400E)
val LightConsoleStderr = Color(0xFFDC2626)
val LightConsoleSuccess = Color(0xFF16A34A)

val LightAgentArchitect = Color(0xFFB45309)
val LightAgentCoder = Color(0xFF16A34A)
val LightAgentValidator = Color(0xFF0284C7)
val LightAgentIdle = Color(0xFFA8A29E)

// === COLOR SCHEMES ===
val PocketIDEDarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = Color(0xFF2A2A2E),
    onPrimaryContainer = Color(0xFFD4D4D8),
    secondary = DarkSecondary,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
)

val PocketIDELightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = Color(0xFFFDE68A),
    onPrimaryContainer = Color(0xFF78350F),
    secondary = LightSecondary,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
)

// === THEME-DEPENDENT COLORS ===
object ThemeColors {
    fun activityBarBg(isDark: Boolean) = if (isDark) DarkActivityBarBg else LightActivityBarBg
    fun activityBarActive(isDark: Boolean) = if (isDark) DarkActivityBarActive else LightActivityBarActive
    fun activityBarInactive(isDark: Boolean) = if (isDark) DarkActivityBarInactive else LightActivityBarInactive
    fun chatUserBubble(isDark: Boolean) = if (isDark) DarkChatUserBubble else LightChatUserBubble
    fun chatAssistantBubble(isDark: Boolean) = if (isDark) DarkChatAssistantBubble else LightChatAssistantBubble
    fun chatUserText(isDark: Boolean) = if (isDark) DarkChatUserText else LightChatUserText
    fun chatAssistantText(isDark: Boolean) = if (isDark) DarkChatAssistantText else LightChatAssistantText
    fun chatAgentLabel(isDark: Boolean) = if (isDark) DarkChatAgentLabel else LightChatAgentLabel
    fun consoleBg(isDark: Boolean) = if (isDark) DarkConsoleBg else LightConsoleBg
    fun consoleStdout(isDark: Boolean) = if (isDark) DarkConsoleStdout else LightConsoleStdout
    fun consoleStderr(isDark: Boolean) = if (isDark) DarkConsoleStderr else LightConsoleStderr
    fun consoleSuccess(isDark: Boolean) = if (isDark) DarkConsoleSuccess else LightConsoleSuccess
    fun agentArchitect(isDark: Boolean) = if (isDark) DarkAgentArchitect else LightAgentArchitect
    fun agentCoder(isDark: Boolean) = if (isDark) DarkAgentCoder else LightAgentCoder
    fun agentValidator(isDark: Boolean) = if (isDark) DarkAgentValidator else LightAgentValidator
    fun agentIdle(isDark: Boolean) = if (isDark) DarkAgentIdle else LightAgentIdle
}
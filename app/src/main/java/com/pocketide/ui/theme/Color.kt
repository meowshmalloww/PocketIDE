package com.pocketide.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// === DARK THEME (Neutral Charcoal) ===
val DarkBackground = Color(0xFF1E1E1E)
val DarkSurface = Color(0xFF252525)
val DarkSurfaceVariant = Color(0xFF2D2D2D)
val DarkPrimary = Color(0xFFA8A8A8)
val DarkOnPrimary = Color(0xFF1E1E1E)
val DarkSecondary = Color(0xFF3A3A3A)
val DarkOnSurface = Color(0xFFD4D4D4)
val DarkOnSurfaceVariant = Color(0xFF808080)
val DarkOutline = Color(0xFF383838)
val DarkError = Color(0xFFEF5350)

val DarkActivityBarBg = Color(0xFF181818)
val DarkActivityBarActive = Color(0xFFE0E0E0)
val DarkActivityBarInactive = Color(0xFF707070)

val DarkChatUserBubble = Color(0xFF303030)
val DarkChatAssistantBubble = Color(0xFF282828)
val DarkChatUserText = Color(0xFFE0E0E0)
val DarkChatAssistantText = Color(0xFFC0C0C0)
val DarkChatAgentLabel = Color(0xFF808080)

val DarkConsoleBg = Color(0xFF141414)
val DarkConsoleStdout = Color(0xFFB0B0B0)
val DarkConsoleStderr = Color(0xFFEF5350)
val DarkConsoleSuccess = Color(0xFF81C784)

val DarkAgentArchitect = Color(0xFFB0B0B0)
val DarkAgentCoder = Color(0xFF81C784)
val DarkAgentValidator = Color(0xFFCE93D8)
val DarkAgentIdle = Color(0xFF707070)

// === LIGHT THEME (Clean Paper) ===
val LightBackground = Color(0xFFF5F5F5)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFECECEC)
val LightPrimary = Color(0xFF505050)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFFD0D0D0)
val LightOnSurface = Color(0xFF1E1E1E)
val LightOnSurfaceVariant = Color(0xFF666666)
val LightOutline = Color(0xFFD6D6D6)
val LightError = Color(0xFFC62828)

val LightActivityBarBg = Color(0xFFE8E8E8)
val LightActivityBarActive = Color(0xFF2A2A2A)
val LightActivityBarInactive = Color(0xFF888888)

val LightChatUserBubble = Color(0xFFE8E8E8)
val LightChatAssistantBubble = Color(0xFFF2F2F2)
val LightChatUserText = Color(0xFF1E1E1E)
val LightChatAssistantText = Color(0xFF444444)
val LightChatAgentLabel = Color(0xFF888888)

val LightConsoleBg = Color(0xFFE0E0E0)
val LightConsoleStdout = Color(0xFF333333)
val LightConsoleStderr = Color(0xFFC62828)
val LightConsoleSuccess = Color(0xFF2E7D32)

val LightAgentArchitect = Color(0xFF505050)
val LightAgentCoder = Color(0xFF2E7D32)
val LightAgentValidator = Color(0xFF6A1B9A)
val LightAgentIdle = Color(0xFF888888)

// === COLOR SCHEMES ===
val PocketIDEDarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
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
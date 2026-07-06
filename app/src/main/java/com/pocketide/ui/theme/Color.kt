package com.pocketide.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// === DARK THEME (Pure Black + White) ===
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1C1C1C)
val DarkSurfaceVariant = Color(0xFF262626)
val DarkPrimary = Color(0xFFFFFFFF)
val DarkOnPrimary = Color(0xFF000000)
val DarkSecondary = Color(0xFF242424)
val DarkOnSurface = Color(0xFFE0E0E0)
val DarkOnSurfaceVariant = Color(0xFF909090)
val DarkOutline = Color(0xFF333333)
val DarkError = Color(0xFFEF4444)

val DarkActivityBarBg = Color(0xFF000000)
val DarkActivityBarActive = Color(0xFFFFFFFF)
val DarkActivityBarInactive = Color(0xFF555555)

val DarkChatUserBubble = Color(0xFF242424)
val DarkChatAssistantBubble = Color(0xFF181818)
val DarkChatUserText = Color(0xFFE0E0E0)
val DarkChatAssistantText = Color(0xFFB0B0B0)
val DarkChatAgentLabel = Color(0xFF808080)

val DarkConsoleBg = Color(0xFF111111)
val DarkConsoleStdout = Color(0xFFB0B0B0)
val DarkConsoleStderr = Color(0xFFEF4444)
val DarkConsoleSuccess = Color(0xFF22C55E)

val DarkAgentArchitect = Color(0xFFFFFFFF)
val DarkAgentCoder = Color(0xFF22C55E)
val DarkAgentValidator = Color(0xFF38BDF8)
val DarkAgentIdle = Color(0xFF555555)

// === LIGHT THEME (White + Black) ===
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF5F5F5)
val LightPrimary = Color(0xFF000000)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFFE0E0E0)
val LightOnSurface = Color(0xFF000000)
val LightOnSurfaceVariant = Color(0xFF666666)
val LightOutline = Color(0xFFCCCCCC)
val LightError = Color(0xFFDC2626)

val LightActivityBarBg = Color(0xFFF5F5F5)
val LightActivityBarActive = Color(0xFF000000)
val LightActivityBarInactive = Color(0xFF999999)

val LightChatUserBubble = Color(0xFFF0F0F0)
val LightChatAssistantBubble = Color(0xFFFFFFFF)
val LightChatUserText = Color(0xFF000000)
val LightChatAssistantText = Color(0xFF333333)
val LightChatAgentLabel = Color(0xFF666666)

val LightConsoleBg = Color(0xFFF5F5F5)
val LightConsoleStdout = Color(0xFF1A1A1A)
val LightConsoleStderr = Color(0xFFDC2626)
val LightConsoleSuccess = Color(0xFF16A34A)

val LightAgentArchitect = Color(0xFF000000)
val LightAgentCoder = Color(0xFF16A34A)
val LightAgentValidator = Color(0xFF0284C7)
val LightAgentIdle = Color(0xFF999999)

// === COLOR SCHEMES ===
val PocketIDEDarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFE0E0E0),
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
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF1A1A1A),
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
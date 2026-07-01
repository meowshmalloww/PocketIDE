package com.pocketide.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
fun PocketIDETheme(
    isDarkMode: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (isDarkMode) PocketIDEDarkScheme else PocketIDELightScheme

    CompositionLocalProvider(LocalIsDarkTheme provides isDarkMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
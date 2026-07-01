package com.pocketide.ui.navigation

sealed class Screen(val route: String) {
    data object Editor : Screen("editor")
    data object Settings : Screen("settings")
}

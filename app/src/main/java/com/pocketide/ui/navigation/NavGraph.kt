package com.pocketide.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketide.ui.screens.editor.EditorScreen
import com.pocketide.ui.screens.editor.EditorViewModel
import com.pocketide.ui.screens.settings.SettingsScreen
import com.pocketide.ui.theme.ThemeViewModel

@Composable
fun PocketIDENavGraph(
    themeViewModel: ThemeViewModel,
) {
    val navController = rememberNavController()
    val editorViewModel: EditorViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.Editor.route,
    ) {
        composable(Screen.Editor.route) {
            EditorScreen(
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                themeViewModel = themeViewModel,
                viewModel = editorViewModel,
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                themeViewModel = themeViewModel,
            )
        }
    }
}

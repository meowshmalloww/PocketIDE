package com.pocketide

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PocketIDEUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun terminalIsDirectlyVisibleWithoutDiagnosticTabs() {
        composeRule.onNodeWithText("Terminal").assertIsDisplayed()
        composeRule.onNodeWithText("Problems").assertDoesNotExist()
        composeRule.onNodeWithText("Benchmark").assertDoesNotExist()
    }

    @Test
    fun opensAiHistoryAndBenchmarkDashboard() {
        composeRule.onNodeWithContentDescription("AI Chat").performClick()
        composeRule.onNodeWithText("AI Assistant").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Chat history").performClick()
        composeRule.onNodeWithText("No saved conversations").assertIsDisplayed()
        pressBack()

        composeRule.onNodeWithContentDescription("Benchmark").performClick()
        composeRule.onNodeWithText("On-device benchmark").assertIsDisplayed()
        composeRule.onNodeWithText("Run benchmark").assertIsDisplayed()
    }

    @Test
    fun settingsUsesDocumentPickerInsteadOfRawPathField() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Add Model").performScrollTo().performClick()
        composeRule.onNodeWithText("Model file (.gguf or .pte)").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to choose a file").assertIsDisplayed()
    }
}

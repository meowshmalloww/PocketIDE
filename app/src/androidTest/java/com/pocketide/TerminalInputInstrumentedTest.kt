package com.pocketide

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.pocketide.data.model.ExecutionStatus
import com.pocketide.ui.components.TerminalPanel
import com.pocketide.ui.theme.PocketIDETheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TerminalInputInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun runningProgramCanReceiveTypedTerminalInput() {
        var submitted = ""
        composeRule.setContent {
            PocketIDETheme {
                TerminalPanel(
                    stdout = "Number: ",
                    stderr = "",
                    status = ExecutionStatus.RUNNING,
                    isExpanded = true,
                    onToggle = {},
                    onRun = {},
                    onRetry = {},
                    waitingForInput = true,
                    inputPrompt = "Number",
                    onSubmitInput = {
                        submitted = it
                        true
                    },
                    modifier = Modifier.height(240.dp),
                )
            }
        }

        val input = composeRule.onNodeWithContentDescription("Program input")
        input.assertIsDisplayed()
        input.performTextInput("42")
        input.performImeAction()

        composeRule.runOnIdle { assertEquals("42", submitted) }
    }
}

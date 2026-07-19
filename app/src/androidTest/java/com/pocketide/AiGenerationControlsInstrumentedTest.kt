package com.pocketide

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketide.data.ai.BenchmarkDashboard
import com.pocketide.data.ai.BenchmarkDashboardMetric
import com.pocketide.data.ai.InferencePhase
import com.pocketide.data.ai.PreviousProcessExit
import com.pocketide.data.ai.ProcessExitCategory
import com.pocketide.data.model.ChatMessage
import com.pocketide.data.model.MessageRole
import com.pocketide.ui.components.AiChatPanel
import com.pocketide.ui.theme.PocketIDETheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiGenerationControlsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stopRemainsClickableWhilePromptIsReadOnly() {
        var stopped = false
        composeRule.setContent {
            PocketIDETheme {
                AiChatPanel(
                    messages = listOf(ChatMessage(role = MessageRole.USER, content = "Build a calculator")),
                    inputText = "",
                    onInputChange = {},
                    onSend = {},
                    isGenerating = true,
                    onStopGeneration = { stopped = true },
                    inferencePhase = InferencePhase.LOADING_MODEL,
                    isThinking = true,
                )
            }
        }

        composeRule.onNodeWithText("Loading local model").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop generation").performClick()
        composeRule.runOnIdle { assertTrue(stopped) }
    }

    @Test
    fun previousLowMemoryExitCanBeDismissed() {
        var dismissed = false
        composeRule.setContent {
            PocketIDETheme {
                AiChatPanel(
                    messages = emptyList(),
                    inputText = "",
                    onInputChange = {},
                    onSend = {},
                    isGenerating = false,
                    previousProcessExit = PreviousProcessExit(
                        category = ProcessExitCategory.LOW_MEMORY,
                        timestampMs = 1,
                        status = 0,
                        description = "lmkd",
                        pssBytes = 1,
                        rssBytes = 2,
                    ),
                    onDismissPreviousProcessExit = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Previous run: Low memory kill").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss previous exit notice").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun benchmarkSummaryShowsVideoReadyMetricsAndArmEvidence() {
        composeRule.setContent {
            PocketIDETheme {
                AiChatPanel(
                    messages = emptyList(),
                    inputText = "",
                    onInputChange = {},
                    onSend = {},
                    isGenerating = false,
                    benchmarkSummary = BenchmarkDashboard(
                        title = "Sustained evidence complete",
                        headline = "11.67 tok/s | 2 threads | +41.6% vs heuristic",
                        metrics = listOf(
                            BenchmarkDashboardMetric("Decode speed", "11.67 tok/s", "8 measured runs"),
                            BenchmarkDashboardMetric("Sustained decode", "98.0% retained", "first vs second half"),
                        ),
                        armRuntimeEvidence = "librnllama_v8_2_dotprod.so",
                        resourcePlanEvidence = "Context 1536 | batch 128",
                        integrityNote = "No simulated scores.",
                        energyDisclosure = "Whole-device energy.",
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Benchmark").performClick()
        composeRule.onNodeWithText("11.67 tok/s | 2 threads | +41.6% vs heuristic").assertIsDisplayed()
        composeRule.onNodeWithText("Decode speed").assertIsDisplayed()
        composeRule.onNodeWithText("Arm runtime evidence").assertIsDisplayed()
    }

    @Test
    fun sustainedBenchmarkCanBeStoppedFromItsDialog() {
        var stopped = false
        composeRule.setContent {
            PocketIDETheme {
                AiChatPanel(
                    messages = emptyList(),
                    inputText = "",
                    onInputChange = {},
                    onSend = {},
                    isGenerating = false,
                    benchmarkRunning = true,
                    benchmarkCompletedRuns = 3,
                    benchmarkTotalRuns = 9,
                    benchmarkPhase = "Measuring sustained speed",
                    onStopBenchmark = { stopped = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Benchmark").performClick()
        composeRule.onNodeWithText("Stop").performClick()
        composeRule.runOnIdle { assertTrue(stopped) }
    }
}

package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExecutorchGenerationStatsTest {
    @Test
    fun `parses exact executorch llm counters`() {
        val stats = ExecutorchGenerationStats.parse(
            """{"prompt_tokens":87,"generated_tokens":96,"inference_start_ms":100,"prompt_eval_end_ms":300,"first_token_ms":310,"inference_end_ms":900}""",
        )

        requireNotNull(stats)
        assertEquals(87, stats.promptTokens)
        assertEquals(96, stats.generatedTokens)
        assertEquals(210, stats.firstTokenMs - stats.inferenceStartMs)
    }

    @Test
    fun `rejects missing or malformed stats`() {
        assertNull(ExecutorchGenerationStats.parse(null))
        assertNull(ExecutorchGenerationStats.parse("{}"))
        assertNull(ExecutorchGenerationStats.parse("not json"))
    }

    @Test
    fun `output limiter emits exactly requested tokens and requests one stop`() {
        val limiter = OutputTokenLimiter(128)
        var emitted = 0
        var stops = 0

        repeat(140) {
            when (limiter.accept()) {
                OutputTokenLimiter.Decision.EMIT -> emitted += 1
                OutputTokenLimiter.Decision.EMIT_AND_STOP -> {
                    emitted += 1
                    stops += 1
                }
                OutputTokenLimiter.Decision.DROP -> Unit
            }
        }

        assertEquals(128, emitted)
        assertEquals(128, limiter.emittedTokens)
        assertEquals(1, stops)
    }
}

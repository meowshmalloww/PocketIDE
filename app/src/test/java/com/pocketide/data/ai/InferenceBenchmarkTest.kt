package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceBenchmarkTest {

    @Test
    fun `start and finish with no tokens returns valid result`() {
        val benchmark = InferenceBenchmark()
        benchmark.start()
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        val result = benchmark.finish()

        assertEquals(0, result.tokenCount)
        assertTrue(result.ttftMs < 0) // No tokens, so TTFT is -1
        assertTrue(result.totalDurationMs >= 10)
    }

    @Test
    fun `onToken increments count and records TTFT`() {
        val benchmark = InferenceBenchmark()
        benchmark.start()
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        benchmark.onToken()
        ShadowSystemClock.advanceBy(Duration.ofMillis(5))
        benchmark.onToken()
        benchmark.onToken()
        val result = benchmark.finish()

        assertEquals(3, result.tokenCount)
        assertTrue(result.ttftMs >= 0)
        assertTrue(result.tokensPerSecond > 0f)
    }

    @Test
    fun `summary contains key metrics`() {
        val result = InferenceBenchmark.BenchmarkResult(
            ttftMs = 50,
            totalDurationMs = 200,
            tokenCount = 10,
            tokensPerSecond = 50.5f,
            memoryDeltaBytes = 1024 * 1024,
            peakMemoryBytes = 10L * 1024 * 1024,
            maxHeapBytes = 256L * 1024 * 1024,
        )
        val summary = result.summary()

        assertTrue(summary.contains("TTFT=50ms"))
        assertTrue(summary.contains("50.50 tok/s"))
        assertTrue(summary.contains("10 tokens"))
    }

    @Test
    fun `toJson produces valid json structure`() {
        val result = InferenceBenchmark.BenchmarkResult(
            ttftMs = 100,
            totalDurationMs = 500,
            tokenCount = 25,
            tokensPerSecond = 50.0f,
            memoryDeltaBytes = 2048,
            peakMemoryBytes = 4096,
            maxHeapBytes = 8192,
        )
        val json = result.toJson()
        val parsed = JSONObject(json)

        assertEquals(100L, parsed.getLong("ttft_ms"))
        assertEquals(25, parsed.getInt("tokens"))
        assertEquals(50.0, parsed.getDouble("tps"), 0.0001)
    }
}

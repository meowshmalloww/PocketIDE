package com.pocketide.data.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BenchmarkSessionTest {
    @Test
    fun `export is valid versioned json and warmup is marked`() {
        val session = BenchmarkSession()
        session.setModelInfo("/models/qwen\"demo.gguf", "GGUF", "Q4", 1234)
        session.record(
            result = InferenceBenchmark.BenchmarkResult(
                ttftMs = 20,
                totalDurationMs = 100,
                tokenCount = 5,
                tokensPerSecond = 50f,
                memoryDeltaBytes = 10,
                peakMemoryBytes = 20,
                maxHeapBytes = 100,
            ),
            tuning = InferenceTuning(
                seqLen = 128,
                threadCount = 4,
                strategy = InferenceStrategy.BALANCED,
                batteryLevel = 80,
                batteryTempCelsius = 31f,
                isCharging = false,
                memoryPressureRatio = 0.2f,
                cpuCores = 8,
                thermalStatus = 0,
            ),
            kvCacheQuantized = false,
            kvCacheBytesPerElement = 2,
            promptTokenEstimate = 10,
        )
        session.setThreadCalibration(
            heuristicThreads = 4,
            calibration = ThreadCalibration(
                threadCount = 1,
                medianTokensPerSecond = 8.4f,
                averageTtftMs = 120,
                averagePeakProcessPssBytes = 100,
                measuredAtMs = 123,
            ),
        )

        val parsed = JSONObject(session.exportJson())
        assertEquals(4, parsed.getInt("schema_version"))
        assertEquals("qwen\"demo.gguf", parsed.getJSONObject("model").getString("name"))
        assertEquals("llama.cpp via KotlinLlamaCpp", parsed.getJSONObject("gguf_runtime").getString("engine"))
        assertEquals(1, parsed.getJSONObject("thread_calibration").getInt("selected_threads"))
        val first = parsed.getJSONArray("generations").getJSONObject(0)
        assertEquals(true, first.getBoolean("warmup"))
        assertFalse(first.getBoolean("kv_estimate_quantized"))

        session.clear()
        assertEquals(0, session.entryCount())
    }
}

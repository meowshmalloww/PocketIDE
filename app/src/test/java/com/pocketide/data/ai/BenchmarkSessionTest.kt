package com.pocketide.data.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        session.setSuccessfullyLoadedNativeLibrary("librnllama_v8_2_dotprod.so")
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
        assertEquals(12, parsed.getInt("schema_version"))
        assertEquals("qwen\"demo.gguf", parsed.getJSONObject("model").getString("name"))
        assertEquals("llama.cpp via KotlinLlamaCpp", parsed.getJSONObject("runtime").getString("engine"))
        assertEquals(
            "librnllama_v8_2_dotprod.so",
            parsed.getJSONObject("runtime").getString("successfully_loaded_native_library"),
        )
        assertTrue(parsed.getJSONObject("runtime").getBoolean("native_library_load_verified"))
        assertEquals(1, parsed.getJSONObject("thread_calibration").getInt("selected_threads"))
        val first = parsed.getJSONArray("generations").getJSONObject(0)
        assertEquals(true, first.getBoolean("warmup"))
        assertFalse(first.getBoolean("kv_estimate_quantized"))
        assertEquals("start, first token, finish", parsed.getJSONObject("measurement_integrity").getString("process_pss_sampling"))
        assertFalse(parsed.getJSONObject("measurement_integrity").getBoolean("simulated_scores"))

        session.clear()
        assertEquals(0, session.entryCount())
    }

    @Test
    fun `report is narrow readable and labels native evidence honestly`() {
        val session = BenchmarkSession()
        session.setProtocol(
            BenchmarkProtocol(
                depth = BenchmarkDepth.QUICK,
                workload = "deterministic_code_generation",
                promptSha256 = "abc",
                generatedTokens = 96,
                candidateThreads = listOf(1, 2),
                screeningMeasuredRuns = 3,
                confirmationMeasuredRuns = 0,
            ),
        )
        session.setNativeKernelBenchmark(
            NativeKernelBenchmark.parse(
                """{"n_kv_max":1024,"n_batch":512,"n_ubatch":512,"flash_attn":0,"n_gpu_layers":0,"n_threads":2,"n_threads_batch":2,"pp":128,"tg":32,"pl":1,"t_pp":1.0,"speed_pp":128.0,"t_tg":4.0,"speed_tg":8.0,"t":5.0,"speed":32.0}""",
                repetitions = 3,
            ),
        )

        val report = session.exportReport()
        assertTrue(report.contains("Simulated or estimated performance scores: none"))
        assertTrue(report.contains("Prompt processing (pp128): 128.00 tok/s"))
        assertTrue(report.contains("KleidiAI kernel invocation verified: no"))
    }

    @Test
    fun `pte report labels runtime and unavailable controls honestly`() {
        val session = BenchmarkSession()
        session.setModelInfo("/models/llama-int4.pte", "ExecuTorch (.pte)", "INT4", 500)
        session.setProtocol(
            BenchmarkProtocol(
                depth = BenchmarkDepth.QUICK,
                backend = "ExecuTorch / PTE",
                workload = "deterministic_code_generation",
                promptSha256 = "pte",
                generatedTokens = 96,
                candidateThreads = listOf(4),
                screeningMeasuredRuns = 3,
                confirmationMeasuredRuns = 0,
                deterministicSeed = -1,
                ignoreEos = false,
                threadControl = "PTE export/runtime controlled",
            ),
        )
        session.setNativeKernelBenchmark(null, "Not applicable to PTE")

        val json = JSONObject(session.exportJson())
        val runtime = json.getJSONObject("runtime")
        assertEquals("ExecuTorch Android", runtime.getString("engine"))
        assertFalse(runtime.getBoolean("actual_worker_count_exposed"))
        assertTrue(session.exportReport().contains("real loaded-model ExecuTorch PTE generation call"))
    }

    @Test
    fun `sustained dashboard uses current profile speed and interval tokens`() {
        val session = BenchmarkSession()
        session.setModelInfo("/models/qwen-q4_0.gguf", "GGUF", "Q4_0", 1_000)
        session.setProtocol(
            BenchmarkProtocol(
                depth = BenchmarkDepth.SUSTAINED,
                workload = "deterministic_code_generation",
                promptSha256 = "sustained",
                generatedTokens = 25,
                candidateThreads = listOf(2),
                screeningMeasuredRuns = 1,
                confirmationMeasuredRuns = 0,
                sustainedMeasuredRuns = 4,
            ),
        )
        session.setThreadCalibration(
            heuristicThreads = 4,
            calibration = ThreadCalibration(
                threadCount = 2,
                medianTokensPerSecond = 11.67f,
                averageTtftMs = 100,
                averagePeakProcessPssBytes = 200,
                comparisonThreadCount = 4,
                comparisonMedianTokensPerSecond = 8.24f,
            ),
        )
        session.record(
            result = result(tokens = 100, tps = 20f),
            tuning = null,
            kvCacheQuantized = false,
            kvCacheBytesPerElement = 2,
            promptTokenEstimate = 10,
            configuredThreadCount = 2,
            isWarmupOverride = false,
            phase = "screening",
        )
        repeat(4) {
            session.record(
                result = result(tokens = 25, tps = 10f),
                tuning = null,
                kvCacheQuantized = false,
                kvCacheBytesPerElement = 2,
                promptTokenEstimate = 10,
                configuredThreadCount = 2,
                isWarmupOverride = false,
                phase = "sustained",
            )
        }
        session.recordPowerSample(powerSample("sustained_start", 11_000_000))
        session.recordPowerSample(powerSample("sustained_complete", 10_000_000, elapsedMs = 1_000))

        val dashboard = session.dashboard()
        assertEquals("10.00 tok/s", dashboard.metrics.first { it.label == "Decode speed" }.value)
        assertTrue(dashboard.headline.contains("+21.4% vs heuristic"))
        assertTrue(
            dashboard.metrics.first { it.label == "CPU profile" }.detail.contains("saved calibration"),
        )
        assertTrue(
            dashboard.metrics.first { it.label == "Device energy" }.detail.startsWith("10.00 uWh/token"),
        )
    }

    private fun result(tokens: Int, tps: Float) = InferenceBenchmark.BenchmarkResult(
        ttftMs = 100,
        totalDurationMs = 1_000,
        tokenCount = tokens,
        tokensPerSecond = tps,
        memoryDeltaBytes = 0,
        peakMemoryBytes = 0,
        maxHeapBytes = 1,
        peakProcessPssBytes = 100,
    )

    private fun powerSample(stage: String, energyNwh: Long, elapsedMs: Long = 0) = BenchmarkPowerSample(
        stage = stage,
        runIndex = 0,
        timestampMs = elapsedMs,
        elapsedRealtimeMs = elapsedMs,
        batteryLevelPercent = 50,
        batteryTemperatureCelsius = 30f,
        thermalStatus = 0,
        isCharging = false,
        voltageMillivolts = 4_000,
        chargeCounterMicroAmpHours = null,
        energyCounterNanoWattHours = energyNwh,
        currentNowMicroAmps = null,
        currentAverageMicroAmps = null,
    )
}

package com.pocketide.data.ai

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone-readable text rendering kept separate from benchmark state collection. */
internal object BenchmarkTextReport {
    fun export(data: BenchmarkExportSnapshot): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val steady = data.entries.filter { !it.isWarmup }
        val features = BackendInfo.features
        val throughput = BenchmarkStatistics.calculate(steady.map { it.tokensPerSecond })
        val ttft = BenchmarkStatistics.calculate(steady.mapNotNull { it.ttftMs.takeIf { value -> value >= 0 } })
        val profiles = steady.groupBy { it.profile }.toSortedMap(compareBy(::profileThread))
        val isPte = isPte(data)
        val power = BenchmarkPowerAnalyzer.analyze(
            selectPowerEvidenceSamples(data.powerSamples, data.protocol?.depth),
            steady.filter { data.protocol?.depth != BenchmarkDepth.SUSTAINED || it.phase == "sustained" }
                .sumOf { it.tokenCount },
        )

        return buildString {
            appendLine("POCKETIDE BENCHMARK REPORT")
            appendLine("Generated: $now")
            appendLine("Schema: $SCHEMA_VERSION")
            appendLine()

            appendLine("=== MEASUREMENT INTEGRITY ===")
            appendLine("Chat workload: real loaded-model ${if (isPte) "ExecuTorch PTE" else "llama.cpp GGUF"} generation call")
            appendLine(
                if (isPte) {
                    "Native microbenchmark: not exposed by the ExecuTorch 1.0 Android LLM API"
                } else {
                    "Native microbenchmark: real llama_decode calls using randomized vocabulary token IDs"
                },
            )
            appendLine("Simulated or estimated performance scores: none")
            appendLine("Warmups excluded from reported statistics: yes")
            appendLine("Decode tok/s timing: after first emitted token")
            appendLine("Model loading and prompt formatting included in TTFT: no")
            appendLine("KV state reset before each measured PTE run: ${isPte}")
            appendLine("Fixed GGUF prompt may reuse the runtime prefix cache after warmup: ${!isPte}")
            if (!isPte) {
                appendLine("GGUF thread profiles: native context reloaded for each load-time n_threads setting")
                appendLine("Native worker-count introspection: unavailable; configured count is labeled separately")
            }
            appendLine("Native prompt test clears KV state between repetitions: ${!isPte}")
            appendLine("PSS label: maximum of start, first-token, and finish samples")
            appendLine("KleidiAI kernel invocation verified: no (runtime exposes no proof)")
            appendLine("Energy scope: public Android whole-device fuel gauge; never presented as app-only power")
            appendLine()

            data.protocol?.let { protocol -> appendProtocol(protocol) }
            data.resourcePlan?.let { plan -> appendResourcePlan(plan) }
            data.previousProcessExit?.let { exit -> appendPreviousExit(exit) }

            appendLine("=== DEVICE ===")
            appendLine("Manufacturer/model: ${Build.MANUFACTURER} ${Build.MODEL}")
            BackendInfo.socModel?.takeIf { it.isNotBlank() }?.let { soc ->
                val maker = BackendInfo.socManufacturer?.takeIf { it.isNotBlank() }
                appendLine("SoC: ${listOfNotNull(maker, soc).joinToString(" ")}")
            }
            appendLine("Device/product: ${Build.DEVICE} / ${Build.PRODUCT}")
            appendLine("Hardware/board: ${Build.HARDWARE} / ${Build.BOARD}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU cores: ${features.coreCount}")
            if (BackendInfo.frequencyClusters.isNotEmpty()) {
                appendLine("CPU max-frequency clusters: ${frequencyClusterText()}")
            }
            appendLine("App max Java heap: ${mb(Runtime.getRuntime().maxMemory())} MB")
            appendLine()

            appendLine("=== ARM / RUNTIME EVIDENCE ===")
            appendLine("Architecture: ${if (features.isArm64) "arm64-v8a" else "non-Arm"}")
            appendLine("NEON/ASIMD: ${features.hasNeon}")
            appendLine("Dotprod/i8mm/FP16: ${features.hasDotprod}/${features.hasI8mm}/${features.hasFp16}")
            appendLine("SVE/SVE2/SME2: ${features.hasSve}/${features.hasSve2}/${features.hasSme2}")
            if (isPte) {
                appendLine("Engine: ExecuTorch Android $EXECUTORCH_VERSION")
                appendLine("Backend/delegates: embedded in the PTE export and runtime; not inferred from extension")
                appendLine("Actual worker count/delegate proof exposed by API: no")
            } else {
                appendLine("Engine: llama.cpp via KotlinLlamaCpp $LLAMA_CPP_WRAPPER_VERSION")
                appendLine("Loader-selected native library: ${BackendInfo.llamaCppNativeLibrary}")
                appendLine("GGUF GPU layers: ${data.nativeKernelBenchmark?.gpuLayers ?: 0}")
                appendLine("Thread control: n_threads configured when each native context is loaded")
                appendLine("Native-reported live worker count exposed by wrapper: no")
                appendLine("Current GGUF path: CPU; Arm ISA library selected from /proc/cpuinfo")
            }
            appendLine()

            appendModel(data)
            data.selectedCalibration?.let { calibration -> appendCalibration(data, calibration) }
            appendNativeBenchmark(data)

            if (profiles.isNotEmpty()) {
                appendLine("=== MEASURED CHAT PROFILES ===")
                profiles.forEach { (profile, runs) -> appendProfile(profile, runs) }
                appendLine()
            }

            if (steady.isNotEmpty()) {
                appendAggregate(data.entries, steady, throughput, ttft, isPte)
            }
            appendPowerEvidence(power)

            if (data.entries.isNotEmpty()) {
                appendLine("=== PER-RUN CHAT RESULTS ===")
                data.entries.forEach { entry -> appendRun(entry) }
                appendLine()
            }
            appendLine("END OF REPORT")
        }
    }

    private fun StringBuilder.appendProtocol(protocol: BenchmarkProtocol) {
        appendLine("=== PROTOCOL ===")
        appendLine("Mode: ${protocol.depth.displayName}")
        appendLine("Workload: ${protocol.workload}")
        appendLine("Prompt SHA-256: ${protocol.promptSha256}")
        appendLine("Requested output: ${protocol.generatedTokens} tokens")
        appendLine("Candidate threads: ${protocol.candidateThreads.joinToString()}")
        appendLine("Screening measured runs/profile: ${protocol.screeningMeasuredRuns}")
        appendLine("Confirmation measured runs/profile: ${protocol.confirmationMeasuredRuns}")
        appendLine("Sustained measured runs: ${protocol.sustainedMeasuredRuns}")
        if (protocol.confirmationThreads.isNotEmpty()) {
            appendLine("Confirmed threads: ${protocol.confirmationThreads.joinToString()}")
        }
        appendLine("Sampler: temperature ${fmt(protocol.temperature, 1)}, seed ${protocol.deterministicSeed}, ignore EOS ${protocol.ignoreEos}")
        appendLine("Backend: ${protocol.backend}")
        appendLine("Thread control: ${protocol.threadControl}")
        appendLine()
    }

    private fun StringBuilder.appendModel(data: BenchmarkExportSnapshot) {
        appendLine("=== MODEL ===")
        appendLine("Name: ${fileName(data.modelPath)}")
        appendLine("Format: ${data.modelFormat}")
        appendLine("Quantization: ${data.modelQuantization}")
        appendLine("File size: ${fmt(data.modelSizeBytes / (1024.0 * 1024.0), 1)} MB (${data.modelSizeBytes} bytes)")
        runCatching { ModelSpec.detect(data.modelPath) }.getOrNull()?.let { architecture ->
            appendLine("Architecture: ${architecture.displayName}, ${architecture.paramCountBillion}B params")
            appendLine("Architecture source: ${architecture.source}")
            appendLine("Layers/hidden/KV heads/head dim: ${architecture.numLayers}/${architecture.hiddenDim}/${architecture.numKvHeads}/${architecture.headDim}")
            appendLine("Model max context: ${architecture.maxContextLength}")
        }
        appendLine()
    }

    private fun StringBuilder.appendResourcePlan(plan: InferenceResourcePlan) {
        appendLine("=== INFERENCE RESOURCE PLAN ===")
        appendLine("Format: ${plan.format.name}")
        if (plan.format == ModelFormat.PTE) {
            appendLine("PTE context: export controlled; app sequence upper bound ${plan.effectiveContext}")
        } else {
            appendLine("Context requested/effective: ${plan.requestedContext}/${plan.effectiveContext}")
        }
        appendLine("Batch size: ${if (plan.batchSize > 0) plan.batchSize else "export controlled"}")
        appendLine("Output requested/cap: ${plan.requestedOutputTokens}/${plan.maxOutputTokens}")
        appendLine("Selected threads: ${plan.selectedThreads}")
        appendLine("Cold load: ${plan.coldLoad}; profile reload: ${plan.profileReload}; allowed: ${plan.allowed}")
        appendLine("System memory total/available/low threshold: ${mb(plan.totalMemoryBytes)} / ${mb(plan.availableMemoryBytes)} / ${mb(plan.lowMemoryThresholdBytes)} MB")
        appendLine("Process PSS before plan: ${mb(plan.processPssBytes)} MB")
        appendLine("Estimated KV / required headroom: ${mb(plan.estimatedKvBytes)} / ${mb(plan.requiredHeadroomBytes)} MB")
        appendLine("Decision: ${plan.reason}")
        appendLine()
    }

    private fun StringBuilder.appendPreviousExit(exit: PreviousProcessExit) {
        appendLine("=== PREVIOUS PROCESS EXIT ===")
        appendLine("Category: ${exit.category.displayName}")
        appendLine("Timestamp/status: ${exit.timestampMs}/${exit.status}")
        appendLine("Last sampled PSS/RSS: ${mb(exit.pssBytes)} / ${mb(exit.rssBytes)} MB")
        exit.description?.let { appendLine("System description: $it") }
        appendLine("Source: Android ApplicationExitInfo; PSS and RSS are last samples, not exact kill-time values")
        appendLine()
    }

    private fun StringBuilder.appendCalibration(data: BenchmarkExportSnapshot, calibration: ThreadCalibration) {
        appendLine("=== SELECTED CHAT PROFILE ===")
        appendLine("Threads: ${calibration.threadCount}")
        appendLine("Median decode: ${fmt(calibration.medianTokensPerSecond.toDouble(), 2)} tok/s")
        appendLine("Average TTFT: ${calibration.averageTtftMs} ms")
        appendLine("Heuristic before calibration: ${data.heuristicThreadCount ?: "unknown"} threads")
        if (calibration.comparisonThreadCount != null && calibration.comparisonMedianTokensPerSecond != null) {
            val improvement = (calibration.medianTokensPerSecond /
                calibration.comparisonMedianTokensPerSecond - 1f) * 100f
            appendLine(
                "Saved comparison: ${calibration.comparisonThreadCount} threads at " +
                    "${fmt(calibration.comparisonMedianTokensPerSecond.toDouble(), 2)} tok/s " +
                    "(${signedPercent(improvement.toDouble())})",
            )
        }
        appendLine("Rule: fastest median; within 1%, prefer fewer configured threads then sampled PSS")
        appendLine("Saved for this exact device and model file")
        appendLine()
    }

    private fun StringBuilder.appendNativeBenchmark(data: BenchmarkExportSnapshot) {
        data.nativeKernelBenchmark?.let { native ->
            appendLine("=== NATIVE LLAMA.CPP MICROBENCHMARK ===")
            appendLine("Prompt processing (pp${native.promptTokens}): ${fmt(native.promptTokensPerSecond, 2)} tok/s")
            appendLine("Token generation (tg${native.generatedTokens}): ${fmt(native.generationTokensPerSecond, 2)} tok/s")
            appendLine("Combined: ${fmt(native.combinedTokensPerSecond, 2)} tok/s")
            appendLine("Repetitions: ${native.repetitions} plus internal warmup")
            appendLine("Threads generation/batch: ${native.threads}/${native.batchThreads}")
            appendLine("Context/batch/microbatch: ${native.contextSize}/${native.batchSize}/${native.microBatchSize}")
            appendLine("GPU layers/flash-attention mode: ${native.gpuLayers}/${native.flashAttentionMode}")
            appendLine("Scope: kernel throughput only; not TTFT or app UX latency")
            appendLine()
        } ?: data.nativeKernelBenchmarkError?.let { error ->
            appendLine("=== NATIVE MICROBENCHMARK ===")
            appendLine("Unavailable: $error")
            appendLine()
        }
    }

    private fun StringBuilder.appendProfile(profile: String, runs: List<BenchmarkSession.Entry>) {
        val stats = BenchmarkStatistics.calculate(runs.map { it.tokensPerSecond }) ?: return
        val profileTtft = BenchmarkStatistics.calculate(runs.mapNotNull { it.ttftMs.takeIf { value -> value >= 0 } })
        val pss = BenchmarkStatistics.calculate(runs.map { it.peakProcessPssBytes / (1024.0 * 1024.0) })
        val cpuToWall = BenchmarkStatistics.calculate(runs.mapNotNull { entry ->
            entry.totalDurationMs.takeIf { it > 0L }
                ?.let { entry.cpuTimeMs.toDouble() / it.toDouble() }
        })
        appendLine("$profile (${runs.size} measured; phases ${runs.groupingBy { it.phase }.eachCount()}):")
        appendLine("  decode median/mean: ${fmt(stats.median, 2)} / ${fmt(stats.mean, 2)} tok/s")
        appendLine("  range/stddev/CV: ${fmt(stats.minimum, 2)}-${fmt(stats.maximum, 2)} / ${fmt(stats.standardDeviation, 2)} / ${fmt(stats.coefficientOfVariationPercent, 1)}%")
        appendLine("  TTFT median/p95: ${profileTtft?.let { fmt(it.median, 0) } ?: "N/A"} / ${profileTtft?.let { fmt(it.p95, 0) } ?: "N/A"} ms")
        appendLine("  max sampled PSS mean: ${pss?.let { fmt(it.mean, 1) } ?: "N/A"} MB")
        appendLine("  process CPU/wall median: ${cpuToWall?.let { fmt(it.median, 2) } ?: "N/A"}x")
    }

    private fun StringBuilder.appendAggregate(
        entries: List<BenchmarkSession.Entry>,
        steady: List<BenchmarkSession.Entry>,
        throughput: MetricStatistics?,
        ttft: MetricStatistics?,
        isPte: Boolean,
    ) {
        appendLine("=== ALL MEASURED RUNS AGGREGATE ===")
        appendLine(
            if (isPte) {
                "Scope: every measured run on the fixed PTE export/runtime profile"
            } else {
                "Scope: every measured profile; use SELECTED CHAT PROFILE for the applied GGUF result"
            },
        )
        appendLine("Measured/warmup runs: ${steady.size}/${entries.count { it.isWarmup }}")
        throughput?.let { stats ->
            appendLine("Decode median/mean: ${fmt(stats.median, 2)} / ${fmt(stats.mean, 2)} tok/s")
            appendLine("Decode min/max/stddev/CV: ${fmt(stats.minimum, 2)} / ${fmt(stats.maximum, 2)} / ${fmt(stats.standardDeviation, 2)} / ${fmt(stats.coefficientOfVariationPercent, 1)}%")
        }
        ttft?.let { stats ->
            appendLine("TTFT median/p90/p95: ${fmt(stats.median, 0)} / ${fmt(stats.p90, 0)} / ${fmt(stats.p95, 0)} ms")
        }
        BenchmarkStatistics.calculateSustained(
            steady.filter { it.phase == "sustained" }.map { it.tokensPerSecond },
        )?.let { sustained ->
            appendLine("Sustained first/second-half median: ${fmt(sustained.firstHalfMedian, 2)} / ${fmt(sustained.secondHalfMedian, 2)} tok/s")
            appendLine("Sustained throughput retained/change: ${fmt(sustained.retentionPercent, 1)}% / ${fmt(sustained.changePercent, 1)}%")
        }
        appendLine("Generated tokens: ${steady.sumOf { it.tokenCount }}")
        appendLine("Measured generation time: ${steady.sumOf { it.totalDurationMs }} ms")
        val firstTemp = entries.first().batteryTempCelsius
        val lastTemp = entries.last().batteryTempCelsius
        appendLine("Battery temperature start/end/delta: ${fmt(firstTemp.toDouble(), 1)} / ${fmt(lastTemp.toDouble(), 1)} / ${fmt((lastTemp - firstTemp).toDouble(), 1)} C")
        appendLine("Final Android thermal status: ${thermalLabel(entries.last().thermalStatus)}")
        appendLine()
    }

    private fun StringBuilder.appendPowerEvidence(power: BenchmarkPowerSummary?) {
        appendLine("=== DEVICE THERMAL / ENERGY EVIDENCE ===")
        if (power == null) {
            appendLine("Unavailable: fewer than two real device samples")
            appendLine()
            return
        }
        appendLine("Interval/samples: ${power.durationMs} ms / ${power.sampleCount}")
        appendLine("Charging observed: ${power.chargingObserved}")
        appendLine("Energy source: ${power.energySource.displayName}")
        appendLine("Measurement quality: ${power.energyQuality.displayName}")
        appendLine("Fuel-gauge decrease events: ${power.counterDecreaseEvents}")
        appendLine("Whole-device energy: ${power.energyMilliWattHours?.let { fmt(it, 4) } ?: "unavailable"} mWh")
        appendLine("Energy per generated token: ${power.energyPerTokenMicroWattHours?.let { fmt(it, 3) } ?: "unavailable"} uWh/token")
        appendLine("Average whole-device power: ${power.averagePowerMilliWatts?.let { fmt(it, 1) } ?: "unavailable"} mW")
        appendLine("Charge decrease: ${power.chargeUsedMicroAmpHours ?: "unavailable"} uAh")
        appendLine("Current-integration coverage: ${fmt(power.currentIntegrationCoveragePercent, 1)}%")
        appendLine("Battery level start/end: ${power.startBatteryLevelPercent ?: "unavailable"} / ${power.endBatteryLevelPercent ?: "unavailable"}%")
        appendLine(
            "Battery temperature start/end/delta: " +
                "${power.startTemperatureCelsius?.let { fmt(it.toDouble(), 1) } ?: "unavailable"} / " +
                "${power.endTemperatureCelsius?.let { fmt(it.toDouble(), 1) } ?: "unavailable"} / " +
                "${power.temperatureDeltaCelsius?.let { fmt(it.toDouble(), 1) } ?: "unavailable"} C",
        )
        appendLine("Maximum Android thermal status: ${thermalLabel(power.maximumThermalStatus)}")
        appendLine("Scope warning: public BatteryManager counters cover the entire phone, not only PocketIDE")
        appendLine()
    }

    private fun StringBuilder.appendRun(entry: BenchmarkSession.Entry) {
        val warmup = if (entry.isWarmup) " WARMUP" else ""
        appendLine("#${entry.index} ${entry.profile} ${entry.phase}$warmup")
        appendLine("  ${fmt(entry.tokensPerSecond.toDouble(), 2)} tok/s | TTFT ${entry.ttftMs} ms | ${entry.tokenCount}/${entry.seqLen} output tokens")
        appendLine("  total ${entry.totalDurationMs} ms | CPU ${entry.cpuTimeMs} ms | sampled PSS ${mb(entry.peakProcessPssBytes)} MB")
        val configuredThreads = entry.threadCount.takeIf { it > 0 }?.toString() ?: "export controlled"
        appendLine("  threads requested/load-configured ${entry.requestedThreadCount}/$configuredThreads | native live count not exposed")
        appendLine("  prompt ${entry.promptTokenCount}${if (entry.promptTokensExact) " exact" else " estimated"}")
        appendLine("  battery ${entry.batteryLevel}% | temp ${fmt(entry.batteryTempCelsius.toDouble(), 1)} C | thermal ${thermalLabel(entry.thermalStatus)}")
    }

    private fun frequencyClusterText(): String = BackendInfo.frequencyClusters.joinToString { (khz, count) ->
        "${count}x${fmt(khz / 1000.0, 0)}MHz"
    }

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
    private fun isPte(data: BenchmarkExportSnapshot): Boolean = data.modelPath.endsWith(".pte", ignoreCase = true)
    private fun profileThread(profile: String): Int = profile.removePrefix("T").toIntOrNull() ?: Int.MAX_VALUE
    private fun fmt(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)

    private fun signedPercent(value: Double): String = String.format(Locale.US, "%+.1f%%", value)
    private fun mb(bytes: Long): String = fmt(bytes / (1024.0 * 1024.0), 1)

    private fun thermalLabel(status: Int?): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        null -> "unavailable"
        else -> "unknown($status)"
    }

    private const val SCHEMA_VERSION = 9
    private const val LLAMA_CPP_WRAPPER_VERSION = "0.4.0"
    private const val EXECUTORCH_VERSION = "1.0.1"
}

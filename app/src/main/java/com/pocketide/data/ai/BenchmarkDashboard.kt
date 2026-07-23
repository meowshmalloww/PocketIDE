package com.pocketide.data.ai

import java.util.Locale

data class BenchmarkDashboardMetric(
    val label: String,
    val value: String,
    val detail: String,
)

/** Compact, video-friendly projection of the full benchmark evidence. */
data class BenchmarkDashboard(
    val title: String,
    val headline: String,
    val metrics: List<BenchmarkDashboardMetric>,
    val armRuntimeEvidence: String,
    val resourcePlanEvidence: String,
    val integrityNote: String,
    val energyDisclosure: String,
)

internal object BenchmarkDashboardFactory {
    fun create(data: BenchmarkExportSnapshot): BenchmarkDashboard {
        val steady = data.entries.filter { !it.isWarmup && it.tokensPerSecond > 0f }
        val selected = data.selectedCalibration
        val selectedThreads = selected?.threadCount
        val currentProfileEntries = when {
            data.protocol?.depth == BenchmarkDepth.SUSTAINED -> steady.filter { it.phase == "sustained" }
            selectedThreads != null -> steady.filter { it.threadCount == selectedThreads }
            else -> steady
        }.ifEmpty { steady }
        val currentProfileThroughput = BenchmarkStatistics.calculate(
            currentProfileEntries.map { it.tokensPerSecond },
        )
        val ttft = BenchmarkStatistics.calculate(
            currentProfileEntries.mapNotNull { it.ttftMs.takeIf { value -> value >= 0L } },
        )
        val cpuToWall = BenchmarkStatistics.calculate(
            currentProfileEntries.mapNotNull { entry ->
                entry.totalDurationMs.takeIf { it > 0L }
                    ?.let { entry.cpuTimeMs.toDouble() / it.toDouble() }
            },
        )
        val selectedTps = currentProfileThroughput?.median ?: selected?.medianTokensPerSecond?.toDouble()
        val currentHeuristicTps = data.heuristicThreadCount?.let { heuristic ->
            BenchmarkStatistics.calculate(
                steady.filter { it.threadCount == heuristic }.map { it.tokensPerSecond },
            )?.median
        }
        val heuristicTps = currentHeuristicTps
            ?: selected?.comparisonMedianTokensPerSecond?.toDouble()
        val heuristicThreads = data.heuristicThreadCount ?: selected?.comparisonThreadCount
        val improvement = if (selectedTps != null && heuristicTps != null && heuristicTps > 0.0) {
            (selectedTps / heuristicTps - 1.0) * 100.0
        } else {
            null
        }
        val power = BenchmarkPowerAnalyzer.analyze(
            samples = selectPowerEvidenceSamples(data.powerSamples, data.protocol?.depth),
            generatedTokens = steady
                .filter { data.protocol?.depth != BenchmarkDepth.SUSTAINED || it.phase == "sustained" }
                .sumOf { it.tokenCount },
        )
        val sustained = BenchmarkStatistics.calculateSustained(
            steady.filter { it.phase == "sustained" }.map { it.tokensPerSecond },
        )
        val pssBytes = currentProfileEntries.maxOfOrNull { it.peakProcessPssBytes }?.takeIf { it > 0L }
            ?: selected?.averagePeakProcessPssBytes?.takeIf { it > 0L }
        val isPte = data.modelPath.endsWith(".pte", ignoreCase = true)
        val features = BackendInfo.features
        val resourcePlan = data.resourcePlan

        val metrics = buildList {
            add(
                BenchmarkDashboardMetric(
                    label = "Decode speed",
                    value = selectedTps?.let { "${fmt(it, 2)} tok/s" } ?: "Unavailable",
                    detail = "Median from ${currentProfileEntries.size} current profile run${if (currentProfileEntries.size == 1) "" else "s"}",
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "Time to first token",
                    value = ttft?.median?.let { "${fmt(it, 0)} ms" } ?: "Unavailable",
                    detail = "p95 ${ttft?.p95?.let { fmt(it, 0) } ?: "N/A"} ms",
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "CPU profile",
                    value = if (isPte) "Export controlled" else selectedThreads?.let { "$it thread${if (it == 1) "" else "s"}" }
                        ?: "Not selected",
                    detail = improvement?.let {
                        "${signedPercent(it)} vs $heuristicThreads thread heuristic" +
                            if (currentHeuristicTps == null) " (saved calibration)" else ""
                    }
                        ?: if (isPte) "Worker count is not exposed by this PTE API" else "Run a completed GGUF calibration",
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "Sampled process PSS",
                    value = pssBytes?.let { "${fmt(it / MIB, 0)} MB" } ?: "Unavailable",
                    detail = "Maximum sampled process memory, not Java heap only",
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "Process CPU to wall",
                    value = cpuToWall?.median?.let { "${fmt(it, 2)}x" } ?: "Unavailable",
                    detail = "Process CPU seconds per elapsed second; not delegate or NPU proof",
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "Sustained decode",
                    value = sustained?.retentionPercent?.let { "${fmt(it, 1)}% retained" } ?: "Not measured",
                    detail = sustained?.let {
                        "First half ${fmt(it.firstHalfMedian, 2)}, second half ${fmt(it.secondHalfMedian, 2)} tok/s"
                    } ?: "Run Sustained evidence for thermal retention",
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "Device energy",
                    value = power?.energyMilliWattHours?.let { "${fmt(it, 3)} mWh" } ?: "Unavailable",
                    detail = power?.energyPerTokenMicroWattHours?.let {
                        "${fmt(it, 2)} uWh/token via ${power.energySource.displayName}"
                    } ?: power?.energySource?.displayName ?: "No supported Android fuel-gauge reading",
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "Battery temperature",
                    value = power?.temperatureDeltaCelsius?.let { "${signedNumber(it.toDouble(), 1)} C" } ?: "Unavailable",
                    detail = if (power?.startTemperatureCelsius != null && power.endTemperatureCelsius != null) {
                        "${fmt(power.startTemperatureCelsius.toDouble(), 1)} to ${fmt(power.endTemperatureCelsius.toDouble(), 1)} C"
                    } else {
                        "Device did not expose both temperature samples"
                    },
                ),
            )
            add(
                BenchmarkDashboardMetric(
                    label = "Native llama.cpp",
                    value = data.nativeKernelBenchmark?.generationTokensPerSecond?.let { "${fmt(it, 2)} tok/s" }
                        ?: if (isPte) "Not applicable" else "Unavailable",
                    detail = data.nativeKernelBenchmark?.let {
                        "tg${it.generatedTokens} kernel result; prompt ${fmt(it.promptTokensPerSecond, 1)} tok/s"
                    } ?: if (isPte) "PTE measured through real generation callbacks" else "Native microbenchmark returned no result",
                ),
            )
        }
        val headline = buildList {
            selectedTps?.let { add("${fmt(it, 2)} tok/s") }
            if (isPte) add("PTE export controlled") else selectedThreads?.let { add("$it thread${if (it == 1) "" else "s"}") }
            improvement?.let { add("${signedPercent(it)} vs heuristic") }
        }.joinToString("  |  ").ifBlank { "No valid measured generation was recorded" }

        return BenchmarkDashboard(
            title = "${data.protocol?.depth?.displayName ?: "Benchmark"} complete",
            headline = headline,
            metrics = metrics,
            armRuntimeEvidence = if (isPte) {
                "Arm64 ${features.isArm64} | ExecuTorch PTE | delegate and KleidiAI invocation not exposed"
            } else {
                "${data.successfullyLoadedNativeLibrary ?: "native library not recorded"} | " +
                    "load verified ${data.successfullyLoadedNativeLibrary != null} | " +
                    "dotprod ${features.hasDotprod} | i8mm ${features.hasI8mm} | SME2 ${features.hasSme2}"
            },
            resourcePlanEvidence = resourcePlan?.let {
                if (isPte) {
                    "PTE context ${it.effectiveContext}/${it.modelContextLimit} export controlled | " +
                        "output cap ${it.maxOutputTokens} | headroom ${fmt(it.requiredHeadroomBytes / MIB, 0)} MB"
                } else {
                    "Context ${it.effectiveContext}/${it.modelContextLimit} | KV ${it.kvCacheTypeK}/${it.kvCacheTypeV} | " +
                        "Flash ${it.flashAttention} | ${fmt(it.estimatedKvBytes / MIB, 0)} MB KV | " +
                        "${if (it.sparseMoe) "sparse MoE" else "dense"} mmap | " +
                        "${fmt(it.estimatedWeightWorkingSetBytes / MIB, 0)} MB weight working set | " +
                        "${fmt(it.requiredHeadroomBytes / MIB, 0)} MB KV/runtime headroom | " +
                        "total guard ${fmt(it.requiredTotalCapacityBytes / MIB, 0)} MB"
                }
            } ?: "Resource plan unavailable",
            integrityNote = if (isPte) {
                "Real loaded-model generations. Exact callback output cap. Warmups excluded. No simulated scores."
            } else {
                "Real loaded-model generations. Context reload per thread profile. Warmups excluded. No simulated scores."
            },
            energyDisclosure = if (power?.chargingObserved == true) {
                "Energy is hidden because charging was detected. Rerun unplugged for usable device-level energy evidence."
            } else if (power?.energyQuality == BenchmarkEnergyQuality.COARSE_COUNTER) {
                "Energy is whole-device and low resolution because only one fuel-gauge decrement was observed. Use a longer run for comparison."
            } else {
                "Energy uses public Android fuel-gauge data for the whole phone. It is not an app-only power measurement."
            },
        )
    }

    private fun fmt(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)
    private fun signedPercent(value: Double, digits: Int = 1): String =
        String.format(Locale.US, "%+.${digits}f%%", value)

    private fun signedNumber(value: Double, digits: Int): String =
        String.format(Locale.US, "%+.${digits}f", value)

    private const val MIB = 1024.0 * 1024.0
}

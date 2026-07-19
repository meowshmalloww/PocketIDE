package com.pocketide.data.ai

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Versioned machine-readable rendering of benchmark evidence. */
internal object BenchmarkJsonReport {
    fun export(data: BenchmarkExportSnapshot): String {
        val features = BackendInfo.features
        val steady = data.entries.filter { !it.isWarmup }
        return JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("generated_at_ms", System.currentTimeMillis())
            .put("session_started_at_ms", data.sessionStartTimeMs)
            .put("measurement_integrity", integrityJson(data))
            .put("device", deviceJson(features))
            .put("cpu_features", cpuFeaturesJson(features))
            .put("runtime", runtimeJson(data))
            .put("model", modelJson(data))
            .put("protocol", protocolJson(data.protocol))
            .put("resource_plan", resourcePlanJson(data.resourcePlan))
            .put("previous_process_exit", previousExitJson(data.previousProcessExit))
            .put("thread_calibration", calibrationJson(data))
            .put("native_microbenchmark", nativeBenchmarkJson(data))
            .put("power_evidence", powerEvidenceJson(data, steady))
            .put("power_samples", JSONArray().apply {
                data.powerSamples.forEach { sample -> put(powerSampleJson(sample)) }
            })
            .put("statistics", statisticsJson(data.entries, steady))
            .put("generations", JSONArray().apply {
                data.entries.forEach { entry -> put(entryJson(entry)) }
            })
            .toString(2)
    }

    private fun integrityJson(data: BenchmarkExportSnapshot): JSONObject {
        val pte = isPte(data)
        return JSONObject()
            .put("simulated_scores", false)
            .put("chat_workload", "real loaded-model generation call")
            .put(
                "native_workload",
                if (pte) JSONObject.NULL else "real llama_decode microbenchmark with randomized vocabulary token IDs",
            )
            .put("warmups_excluded", true)
            .put("decode_tps_clock_start", "first emitted token")
            .put("model_load_included_in_ttft", false)
            .put("prompt_formatting_included_in_ttft", false)
            .put("pte_kv_reset_before_each_run", pte)
            .put("fixed_chat_prompt_prefix_cache_may_be_reused", !pte)
            .put("gguf_thread_profile_control", if (pte) JSONObject.NULL else "native context load time")
            .put("gguf_context_reloaded_between_thread_profiles", !pte)
            .put("native_worker_count_introspection", false)
            .put("native_prompt_test_clears_kv_between_repetitions", !pte)
            .put("process_pss_sampling", "start, first token, finish")
            .put("cpu_to_wall_definition", "process CPU time divided by generation elapsed time")
            .put("kleidiai_kernel_invocation_verified", false)
            .put("energy_scope", "public Android whole-device fuel gauge; not app-only")
    }

    private fun deviceJson(features: BackendInfo.CpuFeatures) = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("product", Build.PRODUCT)
        .put("hardware", Build.HARDWARE)
        .put("board", Build.BOARD)
        .put("soc_manufacturer", BackendInfo.socManufacturer ?: JSONObject.NULL)
        .put("soc_model", BackendInfo.socModel ?: JSONObject.NULL)
        .put("android_release", Build.VERSION.RELEASE)
        .put("api_level", Build.VERSION.SDK_INT)
        .put("security_patch", Build.VERSION.SECURITY_PATCH)
        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("cpu_cores", features.coreCount)
        .put("cpu_max_frequency_khz", JSONArray().apply {
            BackendInfo.coreFrequencies.forEach { core ->
                put(JSONObject().put("core", core.core).put("max_khz", core.maximumKHz))
            }
        })
        .put("max_java_heap_bytes", Runtime.getRuntime().maxMemory())

    private fun cpuFeaturesJson(features: BackendInfo.CpuFeatures) = JSONObject()
        .put("source", "/proc/cpuinfo")
        .put("arm64", features.isArm64)
        .put("neon_asimd", features.hasNeon)
        .put("i8mm", features.hasI8mm)
        .put("dotprod", features.hasDotprod)
        .put("fp16", features.hasFp16)
        .put("sve", features.hasSve)
        .put("sve2", features.hasSve2)
        .put("sme2", features.hasSme2)

    private fun runtimeJson(data: BenchmarkExportSnapshot): JSONObject = if (isPte(data)) {
        JSONObject()
            .put("engine", "ExecuTorch Android")
            .put("version", EXECUTORCH_VERSION)
            .put("backend", "embedded in PTE export/runtime")
            .put("actual_worker_count_exposed", false)
            .put("delegate_invocation_verified", false)
            .put("kleidiai_kernel_invocation_verified", false)
    } else {
        JSONObject()
            .put("engine", "llama.cpp via KotlinLlamaCpp")
            .put("wrapper_version", LLAMA_CPP_WRAPPER_VERSION)
            .put("loader_selected_native_library", BackendInfo.llamaCppNativeLibrary)
            .put("dispatch_evidence", "native library selected from /proc/cpuinfo Arm ISA flags")
            .put("thread_configuration", "n_threads configured when each native context is loaded")
            .put("actual_worker_count_exposed", false)
            .put("gpu_layers", data.nativeKernelBenchmark?.gpuLayers ?: 0)
            .put("kleidiai_kernel_invocation_verified", false)
    }

    private fun modelJson(data: BenchmarkExportSnapshot) = JSONObject()
        .put("name", fileName(data.modelPath))
        .put("format", data.modelFormat)
        .put("quantization", data.modelQuantization)
        .put("size_bytes", data.modelSizeBytes)
        .apply {
            runCatching { ModelSpec.detect(data.modelPath) }.getOrNull()?.let { architecture ->
                put("architecture", architecture.displayName)
                put("architecture_source", architecture.source)
                put("parameter_count_billion", architecture.paramCountBillion.toDouble())
                put("block_count", architecture.numLayers)
                put("embedding_length", architecture.hiddenDim)
                put("attention_kv_head_count", architecture.numKvHeads)
                put("attention_head_dimension", architecture.headDim)
                put("maximum_context", architecture.maxContextLength)
            }
        }

    private fun protocolJson(protocol: BenchmarkProtocol?): Any = protocol?.let { value ->
        JSONObject()
            .put("mode", value.depth.name.lowercase(Locale.US))
            .put("backend", value.backend)
            .put("workload", value.workload)
            .put("prompt_sha256", value.promptSha256)
            .put("requested_output_tokens", value.generatedTokens)
            .put("candidate_threads", JSONArray(value.candidateThreads))
            .put("screening_measured_runs_per_profile", value.screeningMeasuredRuns)
            .put("confirmation_measured_runs_per_profile", value.confirmationMeasuredRuns)
            .put("sustained_measured_runs", value.sustainedMeasuredRuns)
            .put("confirmation_threads", JSONArray(value.confirmationThreads))
            .put("warmup_policy", "one before each contiguous load-configured profile block")
            .put("temperature", value.temperature)
            .put("seed", value.deterministicSeed)
            .put("ignore_eos", value.ignoreEos)
            .put("thread_control", value.threadControl)
            .put("started_at_ms", value.startedAtMs)
    } ?: JSONObject.NULL

    private fun calibrationJson(data: BenchmarkExportSnapshot): Any =
        data.selectedCalibration?.let { calibration ->
            JSONObject()
                .put("selected_threads", calibration.threadCount)
                .put("heuristic_threads", data.heuristicThreadCount ?: JSONObject.NULL)
                .put("median_decode_tps", calibration.medianTokensPerSecond.toDouble())
                .put("average_ttft_ms", calibration.averageTtftMs)
                .put("average_max_sampled_pss_bytes", calibration.averagePeakProcessPssBytes)
                .put("comparison_threads", calibration.comparisonThreadCount ?: JSONObject.NULL)
                .put(
                    "comparison_median_decode_tps",
                    calibration.comparisonMedianTokensPerSecond?.toDouble() ?: JSONObject.NULL,
                )
                .put("measured_at_ms", calibration.measuredAtMs)
        } ?: JSONObject.NULL

    private fun resourcePlanJson(plan: InferenceResourcePlan?): Any = plan?.let { value ->
        JSONObject()
            .put("format", value.format.name)
            .put("requested_context", value.requestedContext)
            .put(
                "effective_context",
                if (value.format == ModelFormat.PTE) JSONObject.NULL else value.effectiveContext,
            )
            .put(
                "pte_sequence_length_upper_bound",
                if (value.format == ModelFormat.PTE) value.effectiveContext else JSONObject.NULL,
            )
            .put("batch_size", value.batchSize)
            .put("requested_output_tokens", value.requestedOutputTokens)
            .put("max_output_tokens", value.maxOutputTokens)
            .put("selected_threads", value.selectedThreads)
            .put("total_memory_bytes", value.totalMemoryBytes)
            .put("available_memory_bytes", value.availableMemoryBytes)
            .put("low_memory_threshold_bytes", value.lowMemoryThresholdBytes)
            .put("process_pss_bytes", value.processPssBytes)
            .put("estimated_kv_bytes", value.estimatedKvBytes)
            .put("required_headroom_bytes", value.requiredHeadroomBytes)
            .put("cold_load", value.coldLoad)
            .put("profile_reload", value.profileReload)
            .put("allowed", value.allowed)
            .put("reason", value.reason)
    } ?: JSONObject.NULL

    private fun previousExitJson(exit: PreviousProcessExit?): Any = exit?.let { value ->
        JSONObject()
            .put("category", value.category.name)
            .put("timestamp_ms", value.timestampMs)
            .put("status", value.status)
            .put("description", value.description ?: JSONObject.NULL)
            .put("last_sampled_pss_bytes", value.pssBytes)
            .put("last_sampled_rss_bytes", value.rssBytes)
    } ?: JSONObject.NULL

    private fun nativeBenchmarkJson(data: BenchmarkExportSnapshot): Any =
        data.nativeKernelBenchmark?.let { native ->
            JSONObject()
                .put("scope", "real llama_decode kernel microbenchmark; excludes app TTFT")
                .put("prompt_tokens", native.promptTokens)
                .put("generated_tokens", native.generatedTokens)
                .put("parallel_sequences", native.parallelSequences)
                .put("repetitions", native.repetitions)
                .put("prompt_seconds", native.promptSeconds)
                .put("prompt_tps", native.promptTokensPerSecond)
                .put("generation_seconds", native.generationSeconds)
                .put("generation_tps", native.generationTokensPerSecond)
                .put("combined_seconds", native.combinedSeconds)
                .put("combined_tps", native.combinedTokensPerSecond)
                .put("context_size", native.contextSize)
                .put("batch_size", native.batchSize)
                .put("micro_batch_size", native.microBatchSize)
                .put("generation_threads", native.threads)
                .put("batch_threads", native.batchThreads)
                .put("gpu_layers", native.gpuLayers)
                .put("flash_attention_mode", native.flashAttentionMode)
                .put("raw", JSONObject(native.rawJson))
        } ?: data.nativeKernelBenchmarkError?.let { JSONObject().put("error", it) } ?: JSONObject.NULL

    private fun powerEvidenceJson(
        data: BenchmarkExportSnapshot,
        steady: List<BenchmarkSession.Entry>,
    ): Any = BenchmarkPowerAnalyzer.analyze(
        samples = selectPowerEvidenceSamples(data.powerSamples, data.protocol?.depth),
        generatedTokens = steady
            .filter { data.protocol?.depth != BenchmarkDepth.SUSTAINED || it.phase == "sustained" }
            .sumOf { it.tokenCount },
    )?.let { power ->
        JSONObject()
            .put("scope", "whole device; public Android BatteryManager fuel gauge")
            .put("app_only", false)
            .put("duration_ms", power.durationMs)
            .put("sample_count", power.sampleCount)
            .put("charging_observed", power.chargingObserved)
            .put("energy_source", power.energySource.name.lowercase(Locale.US))
            .put("energy_source_label", power.energySource.displayName)
            .put("measurement_quality", power.energyQuality.name.lowercase(Locale.US))
            .put("measurement_quality_label", power.energyQuality.displayName)
            .put("counter_decrease_events", power.counterDecreaseEvents)
            .put("energy_mwh", power.energyMilliWattHours ?: JSONObject.NULL)
            .put("energy_per_token_uwh", power.energyPerTokenMicroWattHours ?: JSONObject.NULL)
            .put("average_power_mw", power.averagePowerMilliWatts ?: JSONObject.NULL)
            .put("charge_used_uah", power.chargeUsedMicroAmpHours ?: JSONObject.NULL)
            .put("current_integration_coverage_pct", power.currentIntegrationCoveragePercent)
            .put("battery_start_pct", power.startBatteryLevelPercent ?: JSONObject.NULL)
            .put("battery_end_pct", power.endBatteryLevelPercent ?: JSONObject.NULL)
            .put("temperature_start_c", power.startTemperatureCelsius ?: JSONObject.NULL)
            .put("temperature_end_c", power.endTemperatureCelsius ?: JSONObject.NULL)
            .put("temperature_delta_c", power.temperatureDeltaCelsius ?: JSONObject.NULL)
            .put("maximum_thermal_status", power.maximumThermalStatus ?: JSONObject.NULL)
            .put("maximum_thermal_status_label", thermalLabel(power.maximumThermalStatus))
    } ?: JSONObject.NULL

    private fun powerSampleJson(sample: BenchmarkPowerSample) = JSONObject()
        .put("stage", sample.stage)
        .put("run_index", sample.runIndex)
        .put("timestamp_ms", sample.timestampMs)
        .put("elapsed_realtime_ms", sample.elapsedRealtimeMs)
        .put("battery_pct", sample.batteryLevelPercent ?: JSONObject.NULL)
        .put("battery_temp_c", sample.batteryTemperatureCelsius ?: JSONObject.NULL)
        .put("thermal_status", sample.thermalStatus ?: JSONObject.NULL)
        .put("charging", sample.isCharging)
        .put("voltage_mv", sample.voltageMillivolts ?: JSONObject.NULL)
        .put("charge_counter_uah", sample.chargeCounterMicroAmpHours ?: JSONObject.NULL)
        .put("energy_counter_nwh", sample.energyCounterNanoWattHours ?: JSONObject.NULL)
        .put("current_now_ua", sample.currentNowMicroAmps ?: JSONObject.NULL)
        .put("current_average_ua", sample.currentAverageMicroAmps ?: JSONObject.NULL)

    private fun statisticsJson(
        entries: List<BenchmarkSession.Entry>,
        steady: List<BenchmarkSession.Entry>,
    ) = JSONObject()
        .put("scope", "all measured profiles; selected result is in thread_calibration")
        .put("percentile_method", "nearest-rank")
        .put("measured_runs", steady.size)
        .put("warmup_runs", entries.count { it.isWarmup })
        .put("decode_tps", metricJson(steady.map { it.tokensPerSecond }))
        .put("ttft_ms", metricJson(steady.mapNotNull { it.ttftMs.takeIf { value -> value >= 0 } }))
        .put(
            "sustained_decode",
            sustainedJson(steady.filter { it.phase == "sustained" }.map { it.tokensPerSecond }),
        )
        .put("profiles", JSONArray().apply {
            steady.groupBy { it.profile }.toSortedMap(compareBy(::profileThread)).forEach { (name, runs) ->
                put(JSONObject()
                    .put("profile", name)
                    .put("load_configured_threads", JSONArray(runs.map { it.threadCount }.filter { it > 0 }.distinct()))
                    .put("native_reported_worker_threads", JSONObject.NULL)
                    .put("measured_runs", runs.size)
                    .put("phases", JSONObject().apply {
                        runs.groupingBy { it.phase }.eachCount().forEach { (phase, count) -> put(phase, count) }
                    })
                    .put("decode_tps", metricJson(runs.map { it.tokensPerSecond }))
                    .put("ttft_ms", metricJson(runs.mapNotNull { it.ttftMs.takeIf { value -> value >= 0 } }))
                    .put(
                        "process_cpu_to_wall_ratio",
                        metricJson(runs.mapNotNull { entry ->
                            entry.totalDurationMs.takeIf { it > 0L }
                                ?.let { entry.cpuTimeMs.toDouble() / it.toDouble() }
                        }),
                    )
                    .put("max_sampled_process_pss_bytes", metricJson(runs.map { it.peakProcessPssBytes })))
            }
        })

    private fun sustainedJson(values: List<Number>): Any =
        BenchmarkStatistics.calculateSustained(values)?.let { sustained ->
            JSONObject()
                .put("run_count", sustained.runCount)
                .put("first_half_median_tps", sustained.firstHalfMedian)
                .put("second_half_median_tps", sustained.secondHalfMedian)
                .put("retention_pct", sustained.retentionPercent)
                .put("change_pct", sustained.changePercent)
        } ?: JSONObject.NULL

    private fun entryJson(entry: BenchmarkSession.Entry) = JSONObject()
        .put("run", entry.index)
        .put("timestamp_ms", entry.timestampMs)
        .put("profile", entry.profile)
        .put("phase", entry.phase)
        .put("workload", entry.workload)
        .put("warmup", entry.isWarmup)
        .put("ttft_ms", entry.ttftMs)
        .put("total_ms", entry.totalDurationMs)
        .put("generated_tokens", entry.tokenCount)
        .put("requested_output_tokens", entry.seqLen)
        .put("decode_tps", entry.tokensPerSecond.toDouble())
        .put("java_heap_delta_bytes", entry.memoryDeltaBytes)
        .put("java_heap_max_sampled_bytes", entry.peakMemoryBytes)
        .put("java_heap_limit_bytes", entry.maxHeapBytes)
        .put("process_pss_delta_bytes", entry.processPssDeltaBytes)
        .put("process_pss_max_sampled_bytes", entry.peakProcessPssBytes)
        .put("native_heap_delta_bytes", entry.nativeHeapDeltaBytes)
        .put("cpu_time_ms", entry.cpuTimeMs)
        .put("strategy", entry.strategy)
        .put("requested_threads", entry.requestedThreadCount)
        .put("load_configured_threads", entry.threadCount.takeIf { it > 0 } ?: JSONObject.NULL)
        .put("native_reported_worker_threads", JSONObject.NULL)
        .put("battery_pct", entry.batteryLevel)
        .put("battery_temp_c", entry.batteryTempCelsius.toDouble())
        .put("charging", entry.isCharging)
        .put("thermal_status", entry.thermalStatus ?: JSONObject.NULL)
        .put("thermal_status_label", thermalLabel(entry.thermalStatus))
        .put("memory_pressure", entry.memoryPressureRatio.toDouble())
        .put("kv_estimate_quantized", entry.kvCacheQuantized)
        .put("kv_estimate_bytes_per_element", entry.kvCacheBytesPerElement)
        .put("prompt_tokens", entry.promptTokenCount)
        .put("prompt_tokens_exact", entry.promptTokensExact)

    private fun metricJson(values: List<Number>): Any = BenchmarkStatistics.calculate(values)?.let { stats ->
        JSONObject()
            .put("count", stats.count)
            .put("min", stats.minimum)
            .put("max", stats.maximum)
            .put("mean", stats.mean)
            .put("median", stats.median)
            .put("standard_deviation_population", stats.standardDeviation)
            .put("coefficient_of_variation_pct", stats.coefficientOfVariationPercent)
            .put("p90", stats.p90)
            .put("p95", stats.p95)
    } ?: JSONObject.NULL

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
    private fun isPte(data: BenchmarkExportSnapshot): Boolean = data.modelPath.endsWith(".pte", ignoreCase = true)
    private fun profileThread(profile: String): Int = profile.removePrefix("T").toIntOrNull() ?: Int.MAX_VALUE
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

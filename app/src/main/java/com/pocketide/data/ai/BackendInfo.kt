package com.pocketide.data.ai

import android.os.Build
import android.util.Log
import org.nehuatl.llamacpp.NativeLibrarySelector

/**
 * Detects and reports Arm CPU capabilities relevant to ExecuTorch inference
 * optimization via XNNPACK and KleidiAI.
 *
 * **XNNPACK** — The ExecuTorch Android AAR (`org.pytorch:executorch-android`)
 * bundles XNNPACK native libraries for arm64-v8a. XNNPACK provides optimized
 * kernels for Arm NEON and automatically selects the best code path based on
 * CPU features detected at runtime.
 *
 * **KleidiAI** — Arm's lightweight inference acceleration library that provides
 * INT4/INT8 matrix multiplication kernels optimized for Arm Cortex-A and
 * Cortex-X cores. KleidiAI is integrated at model export time (Python) via
 * ExecuTorch's `--enable_kleidiai` flag. At runtime, the XNNPACK backend
 * automatically dispatches to KleidiAI kernels when the .pte model contains
 * KleidiAI-delegated operations and the CPU supports the required features
 * (i8mm for INT8, SME2 for advanced workloads).
 *
 * Reference:
 * - https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/build-llama3-chat-android-app-using-executorch-and-xnnpack/
 * - https://learn.arm.com/learning-paths/mobile-graphics-and-gaming/performance_llama_cpp_sme2/
 */
object BackendInfo {

    private const val TAG = "BackendInfo"

    data class CpuCoreFrequency(
        val core: Int,
        val maximumKHz: Long,
    )

    /** Arm CPU features detected from /proc/cpuinfo. */
    data class CpuFeatures(
        val isArm64: Boolean,
        val hasNeon: Boolean,
        val hasI8mm: Boolean,
        val hasSve: Boolean,
        val hasSve2: Boolean,
        val hasSme2: Boolean,
        val hasDotprod: Boolean,
        val hasFp16: Boolean,
        val hasAsimd: Boolean,
        val hasAes: Boolean,
        val hasCrc32: Boolean,
        val coreCount: Int,
    ) {
        /** Coarse ISA precondition only; this does not prove that a runtime invokes KleidiAI. */
        val kleidiAiInt4Capable: Boolean
            get() = isArm64 && hasNeon && hasI8mm

        /** Coarse ISA precondition only; model export and runtime backend still decide dispatch. */
        val kleidiAiInt8Capable: Boolean
            get() = isArm64 && hasNeon && hasI8mm && hasDotprod

        /** SME2 enables advanced matrix multiplication for newer Arm cores. */
        val sme2Capable: Boolean
            get() = isArm64 && hasSme2
    }

    /** Cached CPU features — read once, reused. */
    val features: CpuFeatures by lazy { detectCpuFeatures() }

    /** Stable maximum-frequency topology exposed by Linux sysfs, when readable. */
    val coreFrequencies: List<CpuCoreFrequency> by lazy {
        (0 until features.coreCount).mapNotNull { core ->
            val base = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq")
            val maximum = readLong(java.io.File(base, "cpuinfo_max_freq"))
                ?: readLong(java.io.File(base, "scaling_max_freq"))
            maximum?.takeIf { it > 0L }?.let { CpuCoreFrequency(core, it) }
        }
    }

    val frequencyClusters: List<Pair<Long, Int>> by lazy {
        coreFrequencies
            .groupingBy { it.maximumKHz }
            .eachCount()
            .toList()
            .sortedBy { it.first }
    }

    val socManufacturer: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null

    val socModel: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

    /**
     * Returns the optimal thread count for inference on this device.
     *
     * On Arm big.LITTLE SoCs, using all cores can cause thermal throttling
     * and actually reduce throughput. We use a heuristic:
     * - 8+ newer SIMD-capable cores: start at 6 threads
     * - 8+ legacy big.LITTLE cores: start at 4 threads to avoid loading every efficiency core
     * - 6 cores: use 4 threads
     * - 4 cores: use 3 threads
     * - fewer: use all available
     */
    val optimalThreadCount: Int by lazy {
        val cores = features.coreCount
        when {
            cores >= 8 && features.hasI8mm -> 6
            cores >= 8 -> 4
            cores >= 6 -> 4
            cores >= 4 -> 3
            else -> cores.coerceAtLeast(1)
        }
    }

    /**
     * Native llama.cpp library selected by KotlinLlamaCpp's Android loader.
     * Keep this logic aligned with LlamaContext's runtime dispatch so reports
     * identify the Arm ISA path that actually backs GGUF inference.
     */
    val llamaCppNativeLibrary: String by lazy {
        NativeLibrarySelector.select(
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull(),
            cpuFeatures = cpuFeaturesLine(),
        ).reportName
    }

    /** Human-readable backend summary for logging and UI display. */
    val backendSummary: String by lazy {
        buildString {
            appendLine("Backends: llama.cpp (GGUF) or ExecuTorch + XNNPACK (PTE)")
            appendLine("Architecture: ${if (features.isArm64) "arm64-v8a" else Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("Cores: ${features.coreCount} (suggested starting point: $optimalThreadCount threads)")
            socModel?.takeIf { it.isNotBlank() }?.let { appendLine("SoC: $it") }
            if (frequencyClusters.isNotEmpty()) {
                appendLine(
                    "CPU max-frequency clusters: " + frequencyClusters.joinToString { (khz, count) ->
                        "${count}x${"%.0f".format(khz / 1000.0)}MHz"
                    },
                )
            }
            appendLine("NEON: ${features.hasNeon}")
            appendLine("i8mm: ${features.hasI8mm}")
            appendLine("Dotprod: ${features.hasDotprod}")
            appendLine("SVE2: ${features.hasSve2}")
            appendLine("SME2: ${features.hasSme2}")
            appendLine("llama.cpp native library: $llamaCppNativeLibrary")
            appendLine("KleidiAI INT4 ISA precondition: ${if (features.kleidiAiInt4Capable) "present" else "absent"}")
            appendLine("KleidiAI INT8 ISA precondition: ${if (features.kleidiAiInt8Capable) "present" else "absent"}")
            appendLine("KleidiAI kernel invocation: not exposed or verified by the current runtime")
            appendLine("SME2: ${if (features.sme2Capable) "capable" else "not supported"}")
        }.trim()
    }

    /** Logs backend info at model load time. Call from [ExecutorchLlmRunner.ensureLoaded]. */
    fun logBackendInfo() {
        Log.i(TAG, "=== Arm Backend Info ===")
        backendSummary.lineSequence().forEach { line ->
            Log.i(TAG, line)
        }
        Log.i(TAG, "========================")
    }

    private fun detectCpuFeatures(): CpuFeatures {
        val abis = Build.SUPPORTED_ABIS
        val isArm64 = abis.any { it.equals("arm64-v8a", ignoreCase = true) }

        val featuresLine = cpuFeaturesLine()

        val coreCount = Runtime.getRuntime().availableProcessors()

        return CpuFeatures(
            isArm64 = isArm64,
            hasNeon = featuresLine.contains("neon") || isArm64,
            hasI8mm = featuresLine.contains("i8mm"),
            hasSve = featuresLine.contains("sve"),
            hasSve2 = featuresLine.contains("sve2"),
            hasSme2 = featuresLine.contains("sme2"),
            hasDotprod = featuresLine.contains("dotprod") || featuresLine.contains("asimddp"),
            hasFp16 = featuresLine.contains("fphp") || featuresLine.contains("asimdhp"),
            hasAsimd = featuresLine.contains("asimd") || featuresLine.contains("neon"),
            hasAes = featuresLine.contains("aes"),
            hasCrc32 = featuresLine.contains("crc32"),
            coreCount = coreCount,
        )
    }

    private fun readLong(file: java.io.File): Long? = runCatching {
        file.readText().trim().toLong()
    }.getOrNull()

    private fun cpuFeaturesLine(): String = runCatching {
        java.io.File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Features", ignoreCase = true) }.orEmpty().lowercase()
        }
    }.getOrDefault("")
}

package com.pocketide.data.ai

import android.os.Build
import android.util.Log

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
        /** KleidiAI INT4 kernels require at minimum NEON + i8mm support. */
        val kleidiAiInt4Capable: Boolean
            get() = isArm64 && hasNeon && hasI8mm

        /** KleidiAI INT8 kernels benefit from dotprod (ARMv8.4+). */
        val kleidiAiInt8Capable: Boolean
            get() = isArm64 && hasNeon && hasI8mm && hasDotprod

        /** SME2 enables advanced matrix multiplication for newer Arm cores. */
        val sme2Capable: Boolean
            get() = isArm64 && hasSme2
    }

    /** Cached CPU features — read once, reused. */
    val features: CpuFeatures by lazy { detectCpuFeatures() }

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
        val f = features
        if (!f.isArm64) {
            if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
                "librnllama_x86_64.so"
            } else {
                "librnllama.so"
            }
        } else {
            val armV82Dispatch = f.hasAsimd && f.hasCrc32 && f.hasAes
            when {
                armV82Dispatch && f.hasDotprod && f.hasI8mm -> "librnllama_v8_2_dotprod_i8mm.so"
                armV82Dispatch && f.hasDotprod -> "librnllama_v8_2_dotprod.so"
                armV82Dispatch && f.hasI8mm -> "librnllama_v8_2_i8mm.so"
                armV82Dispatch -> "librnllama_v8_2.so"
                else -> "librnllama_v8.so"
            }
        }
    }

    /** Human-readable backend summary for logging and UI display. */
    val backendSummary: String by lazy {
        buildString {
            appendLine("Backends: llama.cpp (GGUF) or ExecuTorch + XNNPACK (PTE)")
            appendLine("Architecture: ${if (features.isArm64) "arm64-v8a" else Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("Cores: ${features.coreCount} (suggested starting point: $optimalThreadCount threads)")
            appendLine("NEON: ${features.hasNeon}")
            appendLine("i8mm: ${features.hasI8mm}")
            appendLine("Dotprod: ${features.hasDotprod}")
            appendLine("SVE2: ${features.hasSve2}")
            appendLine("SME2: ${features.hasSme2}")
            appendLine("llama.cpp native library: $llamaCppNativeLibrary")
            appendLine("KleidiAI INT4: ${if (features.kleidiAiInt4Capable) "capable" else "not supported"}")
            appendLine("KleidiAI INT8: ${if (features.kleidiAiInt8Capable) "capable" else "not supported"}")
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

        val cpuInfo = try {
            java.io.File("/proc/cpuinfo").readText()
        } catch (e: Exception) {
            ""
        }

        val featuresLine = cpuInfo.lineSequence()
            .firstOrNull { it.startsWith("Features", ignoreCase = true) }
            ?.lowercase() ?: ""

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
}

package com.pocketide.data.ai

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.File

/**
 * Dynamically tunes inference parameters based on real-time device conditions.
 *
 * Reads thermal state, battery level, available memory, and CPU core count to
 * produce an [InferenceTuning] that adjusts:
 * - **Thread count**: fewer threads when thermal pressure is high or battery is low.
 * - **Sequence length**: shorter generation when memory is constrained or device is hot.
 * - **Strategy**: [InferenceStrategy] enum guides the trade-off between quality and speed.
 *
 * The caller (typically [AiService]) applies the tuning before calling [LlmRunner.generate].
 */
object AdaptiveInferenceTuner {

    private const val TAG = "AdaptiveInferenceTuner"
    private const val THERMAL_THRESHOLD_CELSIUS = 38f
    private const val THERMAL_CRITICAL_CELSIUS = 45f
    private const val LOW_BATTERY_THRESHOLD = 20
    private const val MEMORY_PRESSURE_RATIO = 0.75f

    /**
     * Produces an [InferenceTuning] based on current device conditions and the
     * user's [AiConfig] optimization flags.
     */
    fun tune(
        config: AiConfig,
        context: Context?,
        calibratedThreadCount: Int? = null,
    ): InferenceTuning {
        val baseSeqLen = config.maxSeqLen
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val optimalThreads = BackendInfo.optimalThreadCount
        val cpuFeatures = BackendInfo.features

        val batteryLevel = readBatteryLevel(context)
        val batteryTemp = readBatteryTemperature(context)
        val thermalStatus = readThermalStatus(context)
        val isCharging = isCharging(context)
        val memoryPressure = computeMemoryPressure()

        var seqLen = baseSeqLen
        var threads = calibratedThreadCount?.coerceIn(1, cpuCores) ?: optimalThreads
        var strategy = if (calibratedThreadCount != null) {
            InferenceStrategy.DEVICE_CALIBRATED
        } else {
            InferenceStrategy.BALANCED
        }

        // --- Thermal ---
        if (config.thermalAware && thermalStatus != null) {
            when {
                thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                    seqLen = (seqLen * 0.4f).toInt().coerceAtLeast(64)
                    threads = (threads * 0.5f).toInt().coerceAtLeast(1)
                    strategy = InferenceStrategy.THERMAL_EMERGENCY
                }
                thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> {
                    seqLen = (seqLen * 0.5f).toInt().coerceAtLeast(64)
                    threads = (threads * 0.6f).toInt().coerceAtLeast(1)
                    strategy = InferenceStrategy.THERMAL_THROTTLED
                }
                thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> {
                    seqLen = (seqLen * 0.7f).toInt().coerceAtLeast(64)
                    threads = (threads * 0.75f).toInt().coerceAtLeast(1)
                    strategy = InferenceStrategy.THERMAL_THROTTLED
                }
            }
        } else if (config.thermalAware) {
            when {
                batteryTemp >= THERMAL_CRITICAL_CELSIUS -> {
                    seqLen = (seqLen * 0.4f).toInt().coerceAtLeast(64)
                    threads = (threads * 0.5f).toInt().coerceAtLeast(1)
                    strategy = InferenceStrategy.THERMAL_EMERGENCY
                }
                batteryTemp >= THERMAL_THRESHOLD_CELSIUS -> {
                    val overheat = batteryTemp - THERMAL_THRESHOLD_CELSIUS
                    val reduction = 1f - (overheat / 15f).coerceAtMost(0.5f)
                    seqLen = (seqLen * reduction).toInt().coerceAtLeast(64)
                    threads = (threads * 0.75f).toInt().coerceAtLeast(1)
                    strategy = InferenceStrategy.THERMAL_THROTTLED
                }
            }
        }

        // --- Battery / Power saving ---
        if (config.powerSaving || (batteryLevel in 0..LOW_BATTERY_THRESHOLD && !isCharging)) {
            seqLen = (seqLen * 0.5f).toInt().coerceAtLeast(128)
            threads = (threads * 0.6f).toInt().coerceAtLeast(1)
            if (strategy == InferenceStrategy.BALANCED || strategy == InferenceStrategy.DEVICE_CALIBRATED) {
                strategy = InferenceStrategy.POWER_SAVER
            }
        }

        // --- Memory pressure ---
        if (memoryPressure >= MEMORY_PRESSURE_RATIO) {
            seqLen = (seqLen * 0.6f).toInt().coerceAtLeast(64)
            if (strategy == InferenceStrategy.BALANCED || strategy == InferenceStrategy.DEVICE_CALIBRATED) {
                strategy = InferenceStrategy.MEMORY_CONSTRAINED
            }
        }

        // --- Adaptive cores ---
        if (config.adaptiveCores && calibratedThreadCount == null) {
            // Use BackendInfo's big.LITTLE-aware heuristic
            if (cpuCores <= 4) {
                seqLen = (seqLen * 0.8f).toInt().coerceAtLeast(64)
                threads = cpuCores
            } else if (cpuCores <= 6) {
                threads = (cpuCores * 0.75f).toInt().coerceAtLeast(2)
            } else {
                threads = optimalThreads
            }
            // On Arm big.LITTLE, i8mm is a useful starting-profile hint. This
            // does not claim KleidiAI dispatch; measured calibration overrides it.
            if (cpuFeatures.kleidiAiInt4Capable && threads > 4) {
                threads = (threads * 0.85f).toInt().coerceAtLeast(2)
            }
        }

        // Cap threads at CPU cores
        threads = threads.coerceAtMost(cpuCores).coerceAtLeast(1)

        val tuning = InferenceTuning(
            seqLen = seqLen,
            threadCount = threads,
            strategy = strategy,
            batteryLevel = batteryLevel,
            batteryTempCelsius = batteryTemp,
            isCharging = isCharging,
            memoryPressureRatio = memoryPressure,
            cpuCores = cpuCores,
            thermalStatus = thermalStatus,
            kleidiAiInt4Capable = cpuFeatures.kleidiAiInt4Capable,
            kleidiAiInt8Capable = cpuFeatures.kleidiAiInt8Capable,
            hasI8mm = cpuFeatures.hasI8mm,
            hasDotprod = cpuFeatures.hasDotprod,
            hasSve2 = cpuFeatures.hasSve2,
        )

        Log.i(TAG, "Tuned: $tuning")
        return tuning
    }

    private fun readBatteryLevel(context: Context?): Int {
        if (context == null) return 100
        return try {
            val intent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else 100
        } catch (_: Exception) {
            100
        }
    }

    private fun readBatteryTemperature(context: Context?): Float {
        if (context == null) return 25f
        return try {
            val intent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            )
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) temp / 10f else 25f
        } catch (_: Exception) {
            25f
        }
    }

    private fun isCharging(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val intent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
            )
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
    }

    private fun readThermalStatus(context: Context?): Int? {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.currentThermalStatus
        }.getOrNull()
    }

    private fun computeMemoryPressure(): Float {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        return if (max > 0) used.toFloat() / max.toFloat() else 0f
    }
}

enum class InferenceStrategy(val displayName: String) {
    BALANCED("Balanced — full quality"),
    DEVICE_CALIBRATED("Device calibrated — measured thread profile"),
    BENCHMARK_FIXED("Benchmark fixed profile"),
    POWER_SAVER("Power saver — reduced seqLen and threads"),
    THERMAL_THROTTLED("Thermal throttled — reduced compute"),
    THERMAL_EMERGENCY("Thermal emergency — minimal compute"),
    MEMORY_CONSTRAINED("Memory constrained — reduced seqLen"),
}

data class InferenceTuning(
    val seqLen: Int,
    val threadCount: Int,
    val strategy: InferenceStrategy,
    val batteryLevel: Int,
    val batteryTempCelsius: Float,
    val isCharging: Boolean,
    val memoryPressureRatio: Float,
    val cpuCores: Int,
    val thermalStatus: Int? = null,
    val kleidiAiInt4Capable: Boolean = false,
    val kleidiAiInt8Capable: Boolean = false,
    val hasI8mm: Boolean = false,
    val hasDotprod: Boolean = false,
    val hasSve2: Boolean = false,
) {
    fun summary(): String =
        "strategy=${strategy.displayName}, seqLen=$seqLen, threads=$threadCount, " +
            "battery=$batteryLevel% (${if (isCharging) "charging" else "discharging"}), " +
            "temp=${batteryTempCelsius}C, memPressure=${(memoryPressureRatio * 100).toInt()}%, " +
            "thermalStatus=${thermalStatus ?: "unavailable"}, cores=$cpuCores, i8mm=$hasI8mm, dotprod=$hasDotprod, " +
            "kleidiAI_int4_isa_precondition=$kleidiAiInt4Capable, sve2=$hasSve2"
}

package com.pocketide.data.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** One real device fuel-gauge and thermal sample collected during a benchmark. */
data class BenchmarkPowerSample(
    val stage: String,
    val runIndex: Int,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Float?,
    val thermalStatus: Int?,
    val isCharging: Boolean,
    val voltageMillivolts: Int?,
    val chargeCounterMicroAmpHours: Long?,
    val energyCounterNanoWattHours: Long?,
    val currentNowMicroAmps: Long?,
    val currentAverageMicroAmps: Long?,
)

enum class BenchmarkEnergySource(val displayName: String) {
    ENERGY_COUNTER("Android battery energy counter"),
    CHARGE_COUNTER("Android charge counter with sampled voltage"),
    CURRENT_INTEGRATION("Sampled battery current and voltage integration"),
    UNAVAILABLE("Unavailable on this device"),
}

enum class BenchmarkEnergyQuality(val displayName: String) {
    MULTI_STEP_COUNTER("multiple fuel-gauge decrements observed"),
    COARSE_COUNTER("one fuel-gauge decrement observed; low-resolution estimate"),
    SAMPLED_ESTIMATE("sampled current integration; device-level estimate"),
    UNAVAILABLE("no usable discharge evidence"),
}

/**
 * Device-level power evidence for the benchmark interval.
 *
 * Android's public fuel-gauge counters describe the whole phone, not only this
 * process. The report therefore never labels this value as app-only energy.
 */
data class BenchmarkPowerSummary(
    val durationMs: Long,
    val energyMilliWattHours: Double?,
    val energyPerTokenMicroWattHours: Double?,
    val averagePowerMilliWatts: Double?,
    val chargeUsedMicroAmpHours: Long?,
    val energySource: BenchmarkEnergySource,
    val currentIntegrationCoveragePercent: Double,
    val startBatteryLevelPercent: Int?,
    val endBatteryLevelPercent: Int?,
    val startTemperatureCelsius: Float?,
    val endTemperatureCelsius: Float?,
    val temperatureDeltaCelsius: Float?,
    val maximumThermalStatus: Int?,
    val chargingObserved: Boolean,
    val sampleCount: Int,
    val counterDecreaseEvents: Int,
    val energyQuality: BenchmarkEnergyQuality,
)

/** Reads public Android battery counters without requiring privileged permissions. */
class BenchmarkPowerSampler(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun sample(stage: String, runIndex: Int): BenchmarkPowerSample {
        val batteryIntent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperatureTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        // Any external power invalidates battery-discharge attribution, even when
        // a full battery is no longer actively accepting charge.
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING

        return BenchmarkPowerSample(
            stage = stage,
            runIndex = runIndex,
            timestampMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            batteryLevelPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else null,
            batteryTemperatureCelsius = temperatureTenths.takeIf { it > 0 }?.div(10f),
            thermalStatus = readThermalStatus(),
            isCharging = charging,
            voltageMillivolts = voltage.takeIf { it > 0 },
            chargeCounterMicroAmpHours = readIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it >= 0L },
            energyCounterNanoWattHours = readLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                ?.takeIf { it >= 0L },
            currentNowMicroAmps = readIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAverageMicroAmps = readIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
        )
    }

    private fun readIntProperty(property: Int): Long? = runCatching {
        batteryManager.getIntProperty(property)
    }.getOrNull()?.takeUnless { it == Int.MIN_VALUE }?.toLong()

    private fun readLongProperty(property: Int): Long? = runCatching {
        batteryManager.getLongProperty(property)
    }.getOrNull()?.takeUnless { it == Long.MIN_VALUE }

    private fun readThermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        }.getOrNull()
    }
}

/** Converts raw public fuel-gauge samples into explicitly qualified energy evidence. */
object BenchmarkPowerAnalyzer {
    fun analyze(
        samples: List<BenchmarkPowerSample>,
        generatedTokens: Int,
    ): BenchmarkPowerSummary? {
        val ordered = samples.sortedBy { it.elapsedRealtimeMs }
        if (ordered.size < 2) return null
        val first = ordered.first()
        val last = ordered.last()
        val durationMs = (last.elapsedRealtimeMs - first.elapsedRealtimeMs).coerceAtLeast(0L)
        val chargingObserved = ordered.any { it.isCharging }
        val chargeUsed = if (!chargingObserved) {
            positiveDecrease(first.chargeCounterMicroAmpHours, last.chargeCounterMicroAmpHours)
        } else {
            null
        }
        val energyCounterUsed = if (!chargingObserved) {
            positiveDecrease(first.energyCounterNanoWattHours, last.energyCounterNanoWattHours)
        } else {
            null
        }
        val averageVoltage = ordered.mapNotNull { it.voltageMillivolts }.takeIf { it.isNotEmpty() }?.average()
        val chargeEnergyMilliWattHours = if (chargeUsed != null && averageVoltage != null) {
            // microamp-hours * millivolts = nanowatt-hours.
            chargeUsed * averageVoltage / NANO_WATT_HOURS_PER_MILLI_WATT_HOUR
        } else {
            null
        }
        val integrated = if (!chargingObserved) integrateCurrent(ordered) else IntegratedEnergy.EMPTY
        val (energy, source) = when {
            energyCounterUsed != null ->
                energyCounterUsed / NANO_WATT_HOURS_PER_MILLI_WATT_HOUR to BenchmarkEnergySource.ENERGY_COUNTER
            chargeEnergyMilliWattHours != null && chargeEnergyMilliWattHours > 0.0 ->
                chargeEnergyMilliWattHours to BenchmarkEnergySource.CHARGE_COUNTER
            integrated.energyMilliWattHours > 0.0 ->
                integrated.energyMilliWattHours to BenchmarkEnergySource.CURRENT_INTEGRATION
            else -> null to BenchmarkEnergySource.UNAVAILABLE
        }
        val counterDecreaseEvents = when (source) {
            BenchmarkEnergySource.ENERGY_COUNTER -> decreaseEvents(
                ordered.map { it.energyCounterNanoWattHours },
            )
            BenchmarkEnergySource.CHARGE_COUNTER -> decreaseEvents(
                ordered.map { it.chargeCounterMicroAmpHours },
            )
            else -> 0
        }
        val energyQuality = when (source) {
            BenchmarkEnergySource.ENERGY_COUNTER,
            BenchmarkEnergySource.CHARGE_COUNTER -> if (counterDecreaseEvents >= 2) {
                BenchmarkEnergyQuality.MULTI_STEP_COUNTER
            } else {
                BenchmarkEnergyQuality.COARSE_COUNTER
            }
            BenchmarkEnergySource.CURRENT_INTEGRATION -> BenchmarkEnergyQuality.SAMPLED_ESTIMATE
            BenchmarkEnergySource.UNAVAILABLE -> BenchmarkEnergyQuality.UNAVAILABLE
        }
        val averagePower = energy?.takeIf { durationMs > 0L }?.let {
            it / (durationMs / MILLIS_PER_HOUR)
        }
        val temperatures = ordered.mapNotNull { it.batteryTemperatureCelsius }
        val startTemperature = temperatures.firstOrNull()
        val endTemperature = temperatures.lastOrNull()

        return BenchmarkPowerSummary(
            durationMs = durationMs,
            energyMilliWattHours = energy,
            energyPerTokenMicroWattHours = energy
                ?.takeIf { generatedTokens > 0 }
                ?.let { it * MICRO_WATT_HOURS_PER_MILLI_WATT_HOUR / generatedTokens },
            averagePowerMilliWatts = averagePower,
            chargeUsedMicroAmpHours = chargeUsed,
            energySource = source,
            currentIntegrationCoveragePercent = integrated.coveragePercent,
            startBatteryLevelPercent = ordered.mapNotNull { it.batteryLevelPercent }.firstOrNull(),
            endBatteryLevelPercent = ordered.mapNotNull { it.batteryLevelPercent }.lastOrNull(),
            startTemperatureCelsius = startTemperature,
            endTemperatureCelsius = endTemperature,
            temperatureDeltaCelsius = if (startTemperature != null && endTemperature != null) {
                endTemperature - startTemperature
            } else {
                null
            },
            maximumThermalStatus = ordered.mapNotNull { it.thermalStatus }.maxOrNull(),
            chargingObserved = chargingObserved,
            sampleCount = ordered.size,
            counterDecreaseEvents = counterDecreaseEvents,
            energyQuality = energyQuality,
        )
    }

    private fun integrateCurrent(samples: List<BenchmarkPowerSample>): IntegratedEnergy {
        var energyMilliWattHours = 0.0
        var coveredMs = 0L
        val totalMs = (samples.last().elapsedRealtimeMs - samples.first().elapsedRealtimeMs).coerceAtLeast(0L)
        samples.zipWithNext().forEach { (start, end) ->
            val intervalMs = (end.elapsedRealtimeMs - start.elapsedRealtimeMs).coerceAtLeast(0L)
            val currentSamples = listOfNotNull(preferredCurrent(start), preferredCurrent(end))
                .filter { it < 0L }
            val voltageSamples = listOfNotNull(start.voltageMillivolts, end.voltageMillivolts)
            if (intervalMs == 0L || currentSamples.isEmpty() || voltageSamples.isEmpty()) return@forEach
            val dischargeMicroAmps = -currentSamples.average()
            val voltageMillivolts = voltageSamples.average()
            val powerMilliWatts = dischargeMicroAmps * voltageMillivolts / 1_000_000.0
            energyMilliWattHours += powerMilliWatts * (intervalMs / MILLIS_PER_HOUR)
            coveredMs += intervalMs
        }
        return IntegratedEnergy(
            energyMilliWattHours = energyMilliWattHours,
            coveragePercent = if (totalMs > 0L) coveredMs * 100.0 / totalMs else 0.0,
        )
    }

    private fun preferredCurrent(sample: BenchmarkPowerSample): Long? =
        sample.currentAverageMicroAmps ?: sample.currentNowMicroAmps

    private fun positiveDecrease(start: Long?, end: Long?): Long? =
        if (start != null && end != null && start > end) start - end else null

    private fun decreaseEvents(values: List<Long?>): Int = values.zipWithNext().count { (start, end) ->
        start != null && end != null && start > end
    }

    private data class IntegratedEnergy(
        val energyMilliWattHours: Double,
        val coveragePercent: Double,
    ) {
        companion object {
            val EMPTY = IntegratedEnergy(0.0, 0.0)
        }
    }

    private const val NANO_WATT_HOURS_PER_MILLI_WATT_HOUR = 1_000_000.0
    private const val MICRO_WATT_HOURS_PER_MILLI_WATT_HOUR = 1_000.0
    private const val MILLIS_PER_HOUR = 3_600_000.0
}

/** Uses only the selected-profile interval for sustained mode, and the full suite otherwise. */
internal fun selectPowerEvidenceSamples(
    samples: List<BenchmarkPowerSample>,
    depth: BenchmarkDepth?,
): List<BenchmarkPowerSample> {
    if (depth != BenchmarkDepth.SUSTAINED) return samples
    val start = samples.indexOfFirst { it.stage == "sustained_start" }
    val end = samples.indexOfLast { it.stage == "sustained_complete" }
    return if (start >= 0 && end >= start) samples.subList(start, end + 1) else samples
}

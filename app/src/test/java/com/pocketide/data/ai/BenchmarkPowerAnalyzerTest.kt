package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkPowerAnalyzerTest {

    @Test
    fun `prefers direct Android energy counter`() {
        val summary = BenchmarkPowerAnalyzer.analyze(
            samples = listOf(
                sample(elapsedMs = 0, energyNwh = 11_000_000, chargeUah = 3_000, temperature = 30f),
                sample(elapsedMs = 3_600_000, energyNwh = 10_000_000, chargeUah = 2_800, temperature = 32f),
            ),
            generatedTokens = 100,
        )!!

        assertEquals(BenchmarkEnergySource.ENERGY_COUNTER, summary.energySource)
        assertEquals(1.0, summary.energyMilliWattHours ?: 0.0, 0.0001)
        assertEquals(10.0, summary.energyPerTokenMicroWattHours ?: 0.0, 0.0001)
        assertEquals(1.0, summary.averagePowerMilliWatts ?: 0.0, 0.0001)
        assertEquals(2f, summary.temperatureDeltaCelsius ?: 0f, 0.001f)
    }

    @Test
    fun `uses charge counter and voltage when energy counter is absent`() {
        val summary = BenchmarkPowerAnalyzer.analyze(
            samples = listOf(
                sample(elapsedMs = 0, chargeUah = 2_000, voltageMv = 4_000),
                sample(elapsedMs = 60_000, chargeUah = 1_900, voltageMv = 4_000),
            ),
            generatedTokens = 50,
        )!!

        assertEquals(BenchmarkEnergySource.CHARGE_COUNTER, summary.energySource)
        assertEquals(0.4, summary.energyMilliWattHours ?: 0.0, 0.0001)
        assertEquals(100L, summary.chargeUsedMicroAmpHours)
        assertEquals(BenchmarkEnergyQuality.COARSE_COUNTER, summary.energyQuality)
        assertEquals(1, summary.counterDecreaseEvents)
    }

    @Test
    fun `integrates negative discharge current with voltage`() {
        val summary = BenchmarkPowerAnalyzer.analyze(
            samples = listOf(
                sample(elapsedMs = 0, currentUa = -1_000_000, voltageMv = 4_000),
                sample(elapsedMs = 3_600_000, currentUa = -1_000_000, voltageMv = 4_000),
            ),
            generatedTokens = 1_000,
        )!!

        assertEquals(BenchmarkEnergySource.CURRENT_INTEGRATION, summary.energySource)
        assertEquals(4_000.0, summary.energyMilliWattHours ?: 0.0, 0.001)
        assertEquals(100.0, summary.currentIntegrationCoveragePercent, 0.001)
    }

    @Test
    fun `does not attribute energy while charging`() {
        val summary = BenchmarkPowerAnalyzer.analyze(
            samples = listOf(
                sample(elapsedMs = 0, energyNwh = 10_000_000, charging = true),
                sample(elapsedMs = 1_000, energyNwh = 11_000_000, charging = true),
            ),
            generatedTokens = 10,
        )!!

        assertEquals(BenchmarkEnergySource.UNAVAILABLE, summary.energySource)
        assertNull(summary.energyMilliWattHours)
        assertEquals(true, summary.chargingObserved)
    }

    @Test
    fun `requires two samples`() {
        assertNull(BenchmarkPowerAnalyzer.analyze(listOf(sample(elapsedMs = 0)), 10))
    }

    @Test
    fun `labels multiple counter decrements as stronger energy evidence`() {
        val summary = BenchmarkPowerAnalyzer.analyze(
            samples = listOf(
                sample(elapsedMs = 0, chargeUah = 2_000, voltageMv = 4_000),
                sample(elapsedMs = 30_000, chargeUah = 1_950, voltageMv = 4_000),
                sample(elapsedMs = 60_000, chargeUah = 1_900, voltageMv = 4_000),
            ),
            generatedTokens = 50,
        )!!

        assertEquals(BenchmarkEnergyQuality.MULTI_STEP_COUNTER, summary.energyQuality)
        assertEquals(2, summary.counterDecreaseEvents)
    }

    private fun sample(
        elapsedMs: Long,
        energyNwh: Long? = null,
        chargeUah: Long? = null,
        currentUa: Long? = null,
        voltageMv: Int? = null,
        temperature: Float? = 30f,
        charging: Boolean = false,
    ) = BenchmarkPowerSample(
        stage = "test",
        runIndex = 0,
        timestampMs = elapsedMs,
        elapsedRealtimeMs = elapsedMs,
        batteryLevelPercent = 50,
        batteryTemperatureCelsius = temperature,
        thermalStatus = 0,
        isCharging = charging,
        voltageMillivolts = voltageMv,
        chargeCounterMicroAmpHours = chargeUah,
        energyCounterNanoWattHours = energyNwh,
        currentNowMicroAmps = currentUa,
        currentAverageMicroAmps = currentUa,
    )
}

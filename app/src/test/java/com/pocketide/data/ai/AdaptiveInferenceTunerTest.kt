package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdaptiveInferenceTunerTest {

    @Test
    fun `tune with null context returns balanced strategy with base seqLen`() {
        val config = AiConfig(
            maxSeqLen = 1024,
            powerSaving = false,
            thermalAware = false,
            adaptiveCores = false,
        )

        val tuning = AdaptiveInferenceTuner.tune(config, context = null)

        assertEquals(InferenceStrategy.BALANCED, tuning.strategy)
        assertEquals(1024, tuning.seqLen)
        assertTrue(tuning.threadCount >= 1)
    }

    @Test
    fun `power saving reduces seqLen`() {
        val config = AiConfig(
            maxSeqLen = 1024,
            powerSaving = true,
            thermalAware = false,
            adaptiveCores = false,
        )

        val tuning = AdaptiveInferenceTuner.tune(config, context = null)

        assertEquals(InferenceStrategy.POWER_SAVER, tuning.strategy)
        assertTrue(tuning.seqLen < 1024)
        assertTrue(tuning.seqLen >= 128)
    }

    @Test
    fun `thermal aware with no context defaults to balanced`() {
        val config = AiConfig(
            maxSeqLen = 1024,
            powerSaving = false,
            thermalAware = true,
            adaptiveCores = false,
        )

        val tuning = AdaptiveInferenceTuner.tune(config, context = null)

        // With null context, battery temp defaults to 25 (no thermal pressure)
        assertEquals(InferenceStrategy.BALANCED, tuning.strategy)
        assertEquals(1024, tuning.seqLen)
    }

    @Test
    fun `adaptive cores adjusts seqLen for low core count`() {
        val config = AiConfig(
            maxSeqLen = 1024,
            powerSaving = false,
            thermalAware = false,
            adaptiveCores = true,
        )

        val tuning = AdaptiveInferenceTuner.tune(config, context = null)

        // With adaptiveCores, seqLen should be adjusted based on available processors
        // The exact value depends on the test machine, but it should be <= base
        assertTrue(tuning.seqLen <= 1024)
        assertTrue(tuning.threadCount >= 1)
    }

    @Test
    fun `tuning summary contains strategy and key metrics`() {
        val tuning = InferenceTuning(
            seqLen = 512,
            threadCount = 4,
            strategy = InferenceStrategy.POWER_SAVER,
            batteryLevel = 15,
            batteryTempCelsius = 30f,
            isCharging = false,
            memoryPressureRatio = 0.5f,
            cpuCores = 8,
        )

        val summary = tuning.summary()

        assertTrue(summary.contains("Power saver"))
        assertTrue(summary.contains("seqLen=512"))
        assertTrue(summary.contains("threads=4"))
        assertTrue(summary.contains("battery=15%"))
        assertTrue(summary.contains("discharging"))
    }
}

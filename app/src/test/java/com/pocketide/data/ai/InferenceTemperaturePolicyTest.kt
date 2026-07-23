package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceTemperaturePolicyTest {

    @Test
    fun `PTE code generation loads with deterministic temperature`() {
        assertEquals(
            0f,
            resolveLoadTemperature(
                format = ModelFormat.PTE,
                configuredTemperature = 0.6f,
                isBenchmark = false,
                deterministicGeneration = true,
            ),
        )
    }

    @Test
    fun `PTE benchmark loads with deterministic temperature`() {
        assertEquals(
            0f,
            resolveLoadTemperature(
                format = ModelFormat.PTE,
                configuredTemperature = 0.6f,
                isBenchmark = true,
                deterministicGeneration = false,
            ),
        )
    }

    @Test
    fun `normal PTE chat keeps configured temperature`() {
        assertEquals(
            0.6f,
            resolveLoadTemperature(
                format = ModelFormat.PTE,
                configuredTemperature = 0.6f,
                isBenchmark = false,
                deterministicGeneration = false,
            ),
        )
    }

    @Test
    fun `GGUF keeps load temperature because deterministic sampling is per generation`() {
        assertEquals(
            0.6f,
            resolveLoadTemperature(
                format = ModelFormat.GGUF,
                configuredTemperature = 0.6f,
                isBenchmark = false,
                deterministicGeneration = true,
            ),
        )
    }
}

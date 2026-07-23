package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSpecPteTest {
    @Test
    fun `qwen coder three billion fallback uses official grouped query architecture`() {
        val architecture = ModelSpec.detect(
            "/models/qwen2.5-coder-3b-instruct-q4_0.gguf",
        )

        assertEquals(3.0f, architecture.paramCountBillion)
        assertEquals(36, architecture.numLayers)
        assertEquals(2, architecture.numKvHeads)
        assertEquals(128, architecture.headDim)
        assertEquals(32768, architecture.maxContextLength)
    }

    @Test
    fun `catalog spinquant pte uses published two thousand context bound`() {
        val architecture = ModelSpec.detect(
            "/models/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte",
        )

        assertEquals(2048, architecture.maxContextLength)
        assertEquals(4, architecture.exportedKvBytesPerElement)
        assertEquals(16, architecture.numLayers)
        assertEquals(8, architecture.numKvHeads)
        assertTrue(architecture.source.contains("published"))
    }

    @Test
    fun `unknown pte remains conservative instead of claiming model card maximum`() {
        val architecture = ModelSpec.detect("/models/custom-llama-3.2-1b.pte")

        assertEquals(2048, architecture.maxContextLength)
        assertEquals(null, architecture.exportedKvBytesPerElement)
        assertTrue(architecture.source.contains("unavailable"))
    }
}

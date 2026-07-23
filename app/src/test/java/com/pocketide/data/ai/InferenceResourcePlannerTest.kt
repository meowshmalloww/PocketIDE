package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceResourcePlannerTest {
    private val qwen = ModelSpec.Architecture(
        paramCountBillion = 1.5f,
        numLayers = 28,
        hiddenDim = 1536,
        numKvHeads = 2,
        headDim = 128,
        maxContextLength = 32768,
        displayName = "Qwen2.5-1.5B",
    )

    @Test
    fun `four gigabyte gguf uses true four thousand token profile by default`() {
        val plan = plan(totalGiB = 4.0, availableGiB = 2.0)

        assertTrue(plan.allowed)
        assertEquals(4096, plan.effectiveContext)
        assertEquals(256, plan.batchSize)
        assertEquals(768, plan.maxOutputTokens)
        assertEquals("f16", plan.kvCacheTypeK)
        assertFalse(plan.kvCacheQuantized)
    }

    @Test
    fun `four gigabyte device caps a sixteen thousand request at eight thousand`() {
        val plan = plan(
            totalGiB = 4.0,
            availableGiB = 2.0,
            requestedContext = 16384,
        )

        assertTrue(plan.allowed)
        assertEquals(8192, plan.effectiveContext)
        assertEquals("q8_0", plan.kvCacheTypeK)
        assertEquals("q8_0", plan.kvCacheTypeV)
        assertEquals("on", plan.flashAttention)
        assertEquals(119L * MIB, plan.estimatedKvBytes)
        assertTrue(plan.reason.contains("device tier cap 8192"))
    }

    @Test
    fun `eight gigabyte device can select model native thirty two thousand context`() {
        val plan = plan(
            totalGiB = 8.0,
            availableGiB = 4.0,
            requestedContext = 32768,
        )

        assertTrue(plan.allowed)
        assertEquals(32768, plan.effectiveContext)
        assertEquals(476L * MIB, plan.estimatedKvBytes)
        assertEquals("q8_0", plan.kvCacheTypeK)
        assertEquals(32768, plan.modelContextLimit)
    }

    @Test
    fun `six gigabyte device tier caps a longer model at sixteen thousand`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = qwen.copy(maxContextLength = 131072),
                requestedContext = 131072,
            ),
            memory(totalGiB = 6.0, availableGiB = 3.5),
        )

        assertTrue(plan.allowed)
        assertEquals(16384, plan.effectiveContext)
        assertEquals("q8_0", plan.kvCacheTypeK)
        assertTrue(plan.reason.contains("device tier cap 16384"))
    }

    @Test
    fun `twelve gigabyte device tier permits real sixty five thousand allocation`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = qwen.copy(maxContextLength = 131072),
                requestedContext = 131072,
            ),
            memory(totalGiB = 12.0, availableGiB = 9.0),
        )

        assertTrue(plan.allowed)
        assertEquals(65536, plan.effectiveContext)
        assertEquals(952L * MIB, plan.estimatedKvBytes)
    }

    @Test
    fun `sixteen gigabyte device tier permits real one hundred thirty one thousand allocation`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = qwen.copy(maxContextLength = 131072),
                requestedContext = 131072,
            ),
            memory(totalGiB = 16.0, availableGiB = 12.0),
        )

        assertTrue(plan.allowed)
        assertEquals(131072, plan.effectiveContext)
        assertEquals(1904L * MIB, plan.estimatedKvBytes)
    }

    @Test
    fun `four gigabyte device admits three billion GGUF through mmap capacity guard`() {
        val qwen3b = ModelSpec.Architecture(
            paramCountBillion = 3.0f,
            numLayers = 36,
            hiddenDim = 2048,
            numKvHeads = 2,
            headDim = 128,
            maxContextLength = 32768,
            displayName = "Qwen2.5-Coder-3B",
        )
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = qwen3b,
                modelSizeBytes = 1_997_879_744L,
                requestedContext = 8192,
            ),
            memory(totalGiB = 4.0, availableGiB = 2.25),
        )

        assertTrue(plan.allowed)
        assertEquals(8192, plan.effectiveContext)
        assertTrue(plan.mmapWeights)
        assertFalse(plan.capacityConstrained)
        assertTrue(plan.requiredHeadroomBytes < plan.requiredTotalCapacityBytes)
        assertTrue(plan.reason.contains("mmap weights"))
    }

    @Test
    fun `PowerMoE mmap is admitted without requiring the whole file in available memory`() {
        val powerMoe = ModelSpec.Architecture(
            paramCountBillion = 3.0f,
            numLayers = 32,
            hiddenDim = 1536,
            numKvHeads = 8,
            headDim = 64,
            maxContextLength = 4096,
            displayName = "PowerMoE-3B",
            source = "GGUF metadata",
            architectureId = "granitemoe",
            expertCount = 40,
            expertUsedCount = 8,
        )
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = powerMoe,
                modelSizeBytes = 1_942_000_000L,
                requestedContext = 32768,
            ),
            memory(totalGiB = 4.0, availableGiB = 1.25),
        )

        assertTrue(plan.allowed)
        assertEquals(4096, plan.modelContextLimit)
        assertEquals(4096, plan.effectiveContext)
        assertEquals(256L * MIB, plan.estimatedKvBytes)
        assertEquals(1_942_000_000L, plan.estimatedWeightWorkingSetBytes)
        assertEquals(768L * MIB, plan.requiredHeadroomBytes)
        assertTrue(plan.requiredHeadroomBytes < plan.availableMemoryBytes)
        assertTrue(plan.sparseMoe)
        assertTrue(plan.reason.contains("not an up-front allocation"))
        assertTrue(plan.reason.contains("clamped to model metadata"))
    }

    @Test
    fun `PowerMoE remains blocked when KV and runtime headroom are genuinely unavailable`() {
        val powerMoe = ModelSpec.Architecture(
            paramCountBillion = 3.0f,
            numLayers = 32,
            hiddenDim = 1536,
            numKvHeads = 8,
            headDim = 64,
            maxContextLength = 4096,
            displayName = "PowerMoE-3B",
            source = "GGUF metadata",
            architectureId = "granitemoe",
            expertCount = 40,
            expertUsedCount = 8,
        )
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = powerMoe,
                modelSizeBytes = 1_942_000_000L,
                requestedContext = 4096,
            ),
            memory(totalGiB = 4.0, availableGiB = 0.45),
        )

        assertFalse(plan.allowed)
        assertEquals(512, plan.effectiveContext)
        assertEquals(32L * MIB, plan.estimatedKvBytes)
        assertEquals(544L * MIB, plan.requiredHeadroomBytes)
        assertTrue(plan.blockingMessage().contains("KV and native runtime buffers"))
    }

    @Test
    fun `PowerMoE is admitted at four thousand context when real free memory fits experts`() {
        val powerMoe = ModelSpec.Architecture(
            paramCountBillion = 3.0f,
            numLayers = 32,
            hiddenDim = 1536,
            numKvHeads = 8,
            headDim = 64,
            maxContextLength = 4096,
            displayName = "PowerMoE-3B",
            source = "GGUF metadata",
            architectureId = "granitemoe",
            expertCount = 40,
            expertUsedCount = 8,
        )
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = powerMoe,
                modelSizeBytes = 1_942_000_000L,
                requestedContext = 32768,
            ),
            memory(totalGiB = 8.0, availableGiB = 4.0),
        )

        assertTrue(plan.allowed)
        assertEquals(4096, plan.effectiveContext)
        assertTrue(plan.sparseMoe)
        assertTrue(plan.reason.contains("8/40 experts active per token"))
    }

    @Test
    fun `mmap still refuses a model that exceeds total physical capacity`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                modelSizeBytes = 3_200L * MIB,
                requestedContext = 4096,
            ),
            memory(totalGiB = 4.0, availableGiB = 2.0),
        )

        assertFalse(plan.allowed)
        assertTrue(plan.capacityConstrained)
        assertTrue(plan.requiredTotalCapacityBytes > plan.totalMemoryBytes)
        assertTrue(plan.blockingMessage().contains("total-memory capacity"))
    }

    @Test
    fun `eight gigabyte device can plan real three billion q8 context`() {
        val qwen3b = ModelSpec.Architecture(
            paramCountBillion = 3.0f,
            numLayers = 36,
            hiddenDim = 2048,
            numKvHeads = 2,
            headDim = 128,
            maxContextLength = 32768,
            displayName = "Qwen2.5-Coder-3B",
        )
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = qwen3b,
                modelSizeBytes = 1_997_879_744L,
                requestedContext = 16384,
            ),
            memory(totalGiB = 8.0, availableGiB = 5.0),
        )

        assertTrue(plan.allowed)
        assertEquals(16384, plan.effectiveContext)
        assertEquals("q8_0", plan.kvCacheTypeK)
        assertEquals(306L * MIB, plan.estimatedKvBytes)
    }

    @Test
    fun `eight gigabyte device can plan recommended three billion quality quant at full model context`() {
        val qwen3b = ModelSpec.Architecture(
            paramCountBillion = 3.09f,
            numLayers = 36,
            hiddenDim = 2048,
            numKvHeads = 2,
            headDim = 128,
            maxContextLength = 32768,
            displayName = "Qwen2.5-Coder-3B",
        )
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                architecture = qwen3b,
                modelSizeBytes = 2_104_932_800L,
                requestedContext = 32768,
            ),
            memory(totalGiB = 8.0, availableGiB = 5.0),
        )

        assertTrue(plan.allowed)
        assertEquals(32768, plan.effectiveContext)
        assertEquals(612L * MIB, plan.estimatedKvBytes)
        assertTrue(plan.requiredHeadroomBytes < 4L * GIB)
    }

    @Test
    fun `qwen metadata prevents fake sixty five thousand context`() {
        val plan = plan(
            totalGiB = 16.0,
            availableGiB = 12.0,
            requestedContext = 131072,
        )

        assertEquals(32768, plan.effectiveContext)
        assertEquals(32768, plan.modelContextLimit)
        assertTrue(plan.reason.contains("clamped to model metadata"))
    }

    @Test
    fun `memory preflight reduces context until complete native profile fits`() {
        val plan = plan(
            totalGiB = 8.0,
            availableGiB = 0.70,
            requestedContext = 32768,
        )

        assertTrue(plan.allowed)
        assertEquals(8192, plan.effectiveContext)
        assertTrue(plan.reason.contains("memory preflight reduced"))
    }

    @Test
    fun `low memory blocks cold native load`() {
        val plan = plan(
            totalGiB = 4.0,
            availableGiB = 1.0,
            lowMemory = true,
        )

        assertFalse(plan.allowed)
        assertEquals(512, plan.effectiveContext)
        assertTrue(plan.androidLowMemory)
        assertTrue(plan.blockingMessage().contains("critical low-memory state"))
        assertTrue(plan.reason.contains("blocked"))
    }

    @Test
    fun `already loaded exact profile is reused under memory pressure`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                modelAlreadyLoaded = true,
                loadedContextLength = 4096,
                loadedBatchSize = 256,
                loadedThreadCount = 2,
                loadedKvCacheTypeK = GgufKvCacheType.F16,
                loadedKvCacheTypeV = GgufKvCacheType.F16,
                loadedFlashAttention = "auto",
            ),
            memory(totalGiB = 4.0, availableGiB = 0.5),
        )

        assertTrue(plan.allowed)
        assertFalse(plan.coldLoad)
        assertFalse(plan.profileReload)
        assertEquals(4096, plan.effectiveContext)
        assertTrue(plan.reason.contains("reusing the exact loaded GGUF profile"))
    }

    @Test
    fun `thread change is labeled as profile reload`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                selectedThreads = 2,
                modelAlreadyLoaded = true,
                loadedContextLength = 4096,
                loadedBatchSize = 256,
                loadedThreadCount = 4,
                loadedKvCacheTypeK = GgufKvCacheType.F16,
                loadedKvCacheTypeV = GgufKvCacheType.F16,
                loadedFlashAttention = "auto",
            ),
            memory(totalGiB = 4.0, availableGiB = 1.2),
        )

        assertTrue(plan.allowed)
        assertTrue(plan.profileReload)
        assertTrue(plan.reason.contains("replacing the loaded GGUF profile"))
    }

    @Test
    fun `low memory retains an already loaded quantized long context profile`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                requestedContext = 16384,
                selectedThreads = 2,
                modelAlreadyLoaded = true,
                loadedContextLength = 8192,
                loadedBatchSize = 256,
                loadedThreadCount = 4,
                loadedKvCacheTypeK = GgufKvCacheType.Q8_0,
                loadedKvCacheTypeV = GgufKvCacheType.Q8_0,
                loadedFlashAttention = "on",
            ),
            memory(totalGiB = 4.0, availableGiB = 0.8, lowMemory = true),
        )

        assertTrue(plan.allowed)
        assertFalse(plan.profileReload)
        assertEquals(8192, plan.effectiveContext)
        assertEquals(4, plan.selectedThreads)
        assertEquals("q8_0", plan.kvCacheTypeK)
    }

    @Test
    fun `published spinquant pte is clamped to its real two thousand export`() {
        val pte = ModelSpec.Architecture(
            paramCountBillion = 1.0f,
            numLayers = 16,
            hiddenDim = 2048,
            numKvHeads = 8,
            headDim = 64,
            maxContextLength = 2048,
            displayName = "Llama PTE",
            exportedKvBytesPerElement = 4,
        )
        val request = baseRequest().copy(
            format = ModelFormat.PTE,
            requestedContext = 131072,
            architecture = pte,
            modelSizeBytes = 1100L * MIB,
        )
        val plan = InferenceResourcePlanner.plan(request, memory(4.0, 2.0))

        assertTrue(plan.allowed)
        assertEquals(2048, plan.effectiveContext)
        assertEquals(2048, plan.modelContextLimit)
        assertEquals(128L * MIB, plan.estimatedKvBytes)
        assertEquals("exported_32bit", plan.kvCacheTypeK)
        assertTrue(plan.reason.contains("fixed by the export"))
    }

    @Test
    fun `very long user prompt is truncated within effective context`() {
        val original = "generate code ".repeat(2000)
        val managed = ContextManager.buildContext(
            systemPrompt = "follow the output contract ".repeat(200),
            history = emptyList(),
            userMessage = original,
            files = emptyList(),
            contextWindowSize = 1024,
            activeFileIndex = 0,
        )

        assertTrue(managed.userMessage.length < original.length)
        assertTrue(managed.systemPrompt.length + managed.userMessage.length <= 704 * 4)
    }

    private fun plan(
        totalGiB: Double,
        availableGiB: Double,
        requestedContext: Int = 4096,
        lowMemory: Boolean = false,
    ): InferenceResourcePlan = InferenceResourcePlanner.plan(
        baseRequest().copy(requestedContext = requestedContext),
        memory(totalGiB, availableGiB, lowMemory),
    )

    private fun baseRequest() = InferenceResourceRequest(
        format = ModelFormat.GGUF,
        requestedContext = 4096,
        requestedOutputTokens = 1024,
        selectedThreads = 2,
        modelSizeBytes = 1017L * MIB,
        architecture = qwen,
        modelAlreadyLoaded = false,
    )

    private fun memory(
        totalGiB: Double,
        availableGiB: Double,
        lowMemory: Boolean = false,
    ) = DeviceMemorySnapshot(
        totalBytes = (totalGiB * GIB).toLong(),
        availableBytes = (availableGiB * GIB).toLong(),
        lowMemoryThresholdBytes = 512L * MIB,
        lowMemory = lowMemory,
        processPssBytes = 300L * MIB,
    )

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * 1024L * 1024L
    }
}

package com.pocketide.data.ai

import com.pocketide.data.model.CodeFile
import com.pocketide.data.model.Language
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
    fun `four gigabyte gguf uses demo safe profile`() {
        val plan = plan(totalGiB = 4.0, availableGiB = 2.0)

        assertTrue(plan.allowed)
        assertEquals(1536, plan.effectiveContext)
        assertEquals(128, plan.batchSize)
        assertEquals(384, plan.maxOutputTokens)
        assertTrue(plan.coldLoad)
    }

    @Test
    fun `six gigabyte gguf uses middle profile`() {
        val plan = plan(totalGiB = 6.0, availableGiB = 3.0)

        assertEquals(2048, plan.effectiveContext)
        assertEquals(256, plan.batchSize)
        assertEquals(512, plan.maxOutputTokens)
    }

    @Test
    fun `eight gigabyte gguf caps oversized request at four thousand ninety six`() {
        val plan = plan(
            totalGiB = 8.0,
            availableGiB = 4.0,
            requestedContext = 8192,
        )

        assertEquals(4096, plan.effectiveContext)
        assertEquals(512, plan.batchSize)
        assertEquals(768, plan.maxOutputTokens)
    }

    @Test
    fun `low memory selects emergency profile and blocks cold native load`() {
        val plan = plan(
            totalGiB = 4.0,
            availableGiB = 1.0,
            lowMemory = true,
        )

        assertFalse(plan.allowed)
        assertEquals(1024, plan.effectiveContext)
        assertEquals(64, plan.batchSize)
        assertEquals(256, plan.maxOutputTokens)
        assertTrue(plan.reason.contains("blocked"))
    }

    @Test
    fun `insufficient cold load headroom is refused before native allocation`() {
        val plan = plan(totalGiB = 4.0, availableGiB = 1.1)

        assertFalse(plan.allowed)
        assertEquals(1024, plan.effectiveContext)
        assertTrue(plan.requiredHeadroomBytes > plan.availableMemoryBytes)
        assertTrue(plan.blockingMessage().contains("Not enough safe memory"))
    }

    @Test
    fun `already loaded exact profile does not require model file headroom again`() {
        val plan = plan(
            totalGiB = 4.0,
            availableGiB = 0.5,
            modelAlreadyLoaded = true,
            loadedContextLength = 1536,
            loadedBatchSize = 128,
        )

        assertTrue(plan.allowed)
        assertFalse(plan.coldLoad)
        assertEquals(1536, plan.effectiveContext)
        assertEquals(128, plan.batchSize)
        assertTrue(plan.requiredHeadroomBytes < 256L * MIB)
    }

    @Test
    fun `benchmark reuses loaded profile after available memory falls`() {
        val cold = plan(totalGiB = 4.0, availableGiB = 1.6)
        assertTrue(cold.allowed)
        assertEquals(1536, cold.effectiveContext)

        val secondRun = plan(
            totalGiB = 4.0,
            availableGiB = 1.25,
            modelAlreadyLoaded = true,
            loadedContextLength = cold.effectiveContext,
            loadedBatchSize = cold.batchSize,
        )

        assertTrue(secondRun.allowed)
        assertFalse(secondRun.coldLoad)
        assertEquals(1536, secondRun.effectiveContext)
        assertEquals(128, secondRun.batchSize)
        assertTrue(secondRun.reason.contains("reusing loaded GGUF"))
    }

    @Test
    fun `thread change is labeled as a safe profile reload with fixed context`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                selectedThreads = 2,
                modelAlreadyLoaded = true,
                loadedContextLength = 1536,
                loadedBatchSize = 128,
                loadedThreadCount = 4,
            ),
            memory(totalGiB = 4.0, availableGiB = 1.2),
        )

        assertTrue(plan.allowed)
        assertTrue(plan.profileReload)
        assertFalse(plan.coldLoad)
        assertEquals(1536, plan.effectiveContext)
        assertTrue(plan.reason.contains("replacing loaded GGUF thread profile 4 with 2"))
    }

    @Test
    fun `low memory retains loaded thread profile instead of reloading native context`() {
        val plan = InferenceResourcePlanner.plan(
            baseRequest().copy(
                selectedThreads = 2,
                modelAlreadyLoaded = true,
                loadedContextLength = 1536,
                loadedBatchSize = 128,
                loadedThreadCount = 4,
            ),
            memory(totalGiB = 4.0, availableGiB = 1.0, lowMemory = true),
        )

        assertTrue(plan.allowed)
        assertFalse(plan.profileReload)
        assertEquals(4, plan.selectedThreads)
        assertTrue(plan.reason.contains("retaining loaded GGUF thread profile 4"))
    }

    @Test
    fun `pte keeps configured context and reports export controlled batch`() {
        val request = baseRequest().copy(format = ModelFormat.PTE, requestedContext = 4096)
        val plan = InferenceResourcePlanner.plan(request, memory(4.0, 2.0))

        assertTrue(plan.allowed)
        assertEquals(4096, plan.effectiveContext)
        assertEquals(0, plan.batchSize)
        assertTrue(plan.reason.contains("export controlled"))
    }

    @Test
    fun `managed prompt uses the same effective context selected for loading`() {
        val plan = plan(totalGiB = 4.0, availableGiB = 2.0)
        val managed = ContextManager.buildContext(
            systemPrompt = "system ".repeat(200),
            history = listOf(ChatTurn("user", "old ".repeat(500))),
            userMessage = "Build a calculator",
            files = emptyList(),
            contextWindowSize = plan.effectiveContext,
            activeFileIndex = 0,
            enableCodeContext = true,
            enableHistorySummary = true,
        )

        val estimatedInputTokens = (
            managed.systemPrompt.length +
                managed.history.sumOf { it.content.length } +
                managed.userMessage.length
            ) / 4
        assertTrue(estimatedInputTokens <= 1088)
    }

    @Test
    fun `very long user prompt is truncated without negative length crash`() {
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

    @Test
    fun `code context minimum never exceeds the remaining small context budget`() {
        val managed = ContextManager.buildContext(
            systemPrompt = "runtime rule ".repeat(190),
            history = listOf(ChatTurn("user", "old request ".repeat(100))),
            userMessage = "Fix the current file",
            files = listOf(
                CodeFile(
                    name = "main.py",
                    language = Language.PYTHON,
                    content = "print('value')\n".repeat(300),
                ),
            ),
            contextWindowSize = 1024,
            activeFileIndex = 0,
        )

        val estimatedInputTokens = (
            managed.systemPrompt.length +
                managed.history.sumOf { it.content.length } +
                managed.userMessage.length
            ) / 4
        assertTrue(estimatedInputTokens <= 704)
    }

    private fun plan(
        totalGiB: Double,
        availableGiB: Double,
        requestedContext: Int = 4096,
        lowMemory: Boolean = false,
        modelAlreadyLoaded: Boolean = false,
        loadedContextLength: Int? = null,
        loadedBatchSize: Int? = null,
    ): InferenceResourcePlan = InferenceResourcePlanner.plan(
        baseRequest().copy(
            requestedContext = requestedContext,
            modelAlreadyLoaded = modelAlreadyLoaded,
            loadedContextLength = loadedContextLength,
            loadedBatchSize = loadedBatchSize,
        ),
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

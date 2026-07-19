package com.pocketide.data.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/** A device-wide memory snapshot used before any native model allocation. */
data class DeviceMemorySnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
    val processPssBytes: Long,
) {
    companion object {
        fun capture(context: Context): DeviceMemorySnapshot {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            return DeviceMemorySnapshot(
                totalBytes = info.totalMem,
                availableBytes = info.availMem,
                lowMemoryThresholdBytes = info.threshold,
                lowMemory = info.lowMemory,
                processPssBytes = runCatching { Debug.getPss() * 1024L }.getOrDefault(0L),
            )
        }
    }
}

data class InferenceResourceRequest(
    val format: ModelFormat,
    val requestedContext: Int,
    val requestedOutputTokens: Int,
    val selectedThreads: Int,
    val modelSizeBytes: Long,
    val architecture: ModelSpec.Architecture,
    val modelAlreadyLoaded: Boolean,
    val loadedContextLength: Int? = null,
    val loadedBatchSize: Int? = null,
    val loadedThreadCount: Int? = null,
)

/**
 * The exact native allocation profile selected before prompt construction and model loading.
 * Benchmark exports include this record so context and memory decisions are reproducible.
 */
data class InferenceResourcePlan(
    val format: ModelFormat,
    val requestedContext: Int,
    val effectiveContext: Int,
    val batchSize: Int,
    val requestedOutputTokens: Int,
    val maxOutputTokens: Int,
    val selectedThreads: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val processPssBytes: Long,
    val estimatedKvBytes: Long,
    val requiredHeadroomBytes: Long,
    val coldLoad: Boolean,
    val profileReload: Boolean,
    val allowed: Boolean,
    val reason: String,
) {
    fun blockingMessage(): String =
        "Not enough safe memory to load this model. PocketIDE needs about " +
            "${requiredHeadroomBytes.toMiB()} MB available but Android reports " +
            "${availableMemoryBytes.toMiB()} MB. Close other apps, reopen PocketIDE, and try again."

    private fun Long.toMiB(): Long = this / MIB

    private companion object {
        const val MIB = 1024L * 1024L
    }
}

/** Conservative, deterministic resource tiers for phone-class on-device inference. */
object InferenceResourcePlanner {
    fun plan(
        request: InferenceResourceRequest,
        memory: DeviceMemorySnapshot,
    ): InferenceResourcePlan {
        val requestedContext = request.requestedContext
            .coerceIn(MIN_CONTEXT, request.architecture.maxContextLength)
        val requestedOutput = request.requestedOutputTokens.coerceAtLeast(MIN_OUTPUT)
        val requestedThreads = request.selectedThreads.coerceIn(
            1,
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )

        val baseTier = when {
            memory.lowMemory -> ResourceTier(1024, 64, 256, "Android reports low memory")
            memory.totalBytes <= LOW_RAM_LIMIT ->
                ResourceTier(1536, 128, 384, "memory tier up to 4.5 GiB")
            memory.totalBytes <= MID_RAM_LIMIT ->
                ResourceTier(2048, 256, 512, "memory tier from 4.5 to 6.5 GiB")
            else -> ResourceTier(4096, 512, 768, "memory tier above 6.5 GiB")
        }

        val loadedContextLength = request.loadedContextLength
        val loadedBatchSize = request.loadedBatchSize
        val loadedThreadCount = request.loadedThreadCount
        val reuseLoadedGguf = request.format == ModelFormat.GGUF &&
            request.modelAlreadyLoaded &&
            loadedContextLength != null &&
            loadedBatchSize != null
        val retainLoadedThreadProfile = reuseLoadedGguf && memory.lowMemory &&
            loadedThreadCount != null
        val threads = if (retainLoadedThreadProfile) {
            requireNotNull(loadedThreadCount).coerceIn(
                1,
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            )
        } else {
            requestedThreads
        }
        val profileReload = reuseLoadedGguf && loadedThreadCount != null &&
            loadedThreadCount != threads
        var effectiveContext = when {
            reuseLoadedGguf -> requireNotNull(loadedContextLength)
                .coerceIn(MIN_CONTEXT, request.architecture.maxContextLength)
            request.format == ModelFormat.GGUF ->
                requestedContext.coerceAtMost(baseTier.context).coerceAtLeast(MIN_CONTEXT)
            else -> requestedContext
        }
        var batchSize = when {
            reuseLoadedGguf -> requireNotNull(loadedBatchSize).coerceIn(1, effectiveContext)
            request.format == ModelFormat.GGUF -> baseTier.batch.coerceAtMost(effectiveContext)
            else -> 0
        }
        var maxOutput = requestedOutput.coerceAtMost(baseTier.output)
        var reason = if (retainLoadedThreadProfile && loadedThreadCount != requestedThreads) {
            "${baseTier.reason}; retaining loaded GGUF thread profile $threads instead of " +
                "requesting $requestedThreads to avoid a native context reload"
        } else if (profileReload) {
            "${baseTier.reason}; replacing loaded GGUF thread profile $loadedThreadCount with $threads " +
                "after releasing the existing native context; context $effectiveContext and batch $batchSize stay fixed"
        } else if (reuseLoadedGguf) {
            "${baseTier.reason}; reusing loaded GGUF context $effectiveContext, batch $batchSize, and $threads threads"
        } else {
            baseTier.reason
        }

        var estimatedKvBytes = estimateKvBytes(request, effectiveContext)
        var requiredHeadroom = requiredHeadroom(
            modelSizeBytes = request.modelSizeBytes,
            estimatedKvBytes = estimatedKvBytes,
            coldLoad = !request.modelAlreadyLoaded,
        )

        // Try the smallest useful GGUF profile before refusing a cold load. This also reduces
        // transient prompt buffers through n_batch, not only the KV allocation.
        if (request.format == ModelFormat.GGUF &&
            !request.modelAlreadyLoaded &&
            memory.availableBytes < requiredHeadroom &&
            effectiveContext > LOW_MEMORY_CONTEXT
        ) {
            effectiveContext = requestedContext.coerceAtMost(LOW_MEMORY_CONTEXT)
                .coerceAtLeast(MIN_CONTEXT)
            batchSize = LOW_MEMORY_BATCH.coerceAtMost(effectiveContext)
            maxOutput = maxOutput.coerceAtMost(LOW_MEMORY_OUTPUT)
            estimatedKvBytes = estimateKvBytes(request, effectiveContext)
            requiredHeadroom = requiredHeadroom(
                modelSizeBytes = request.modelSizeBytes,
                estimatedKvBytes = estimatedKvBytes,
                coldLoad = true,
            )
            reason += "; reduced to the emergency profile because cold-load headroom is limited"
        }

        val allowed = request.modelAlreadyLoaded ||
            (!memory.lowMemory && memory.availableBytes >= requiredHeadroom)
        if (!allowed) {
            reason += "; native loading blocked before Android could kill the process"
        } else if (request.format == ModelFormat.PTE) {
            reason += "; PTE context, workers, and delegates remain export controlled"
        }

        return InferenceResourcePlan(
            format = request.format,
            requestedContext = request.requestedContext,
            effectiveContext = effectiveContext,
            batchSize = batchSize,
            requestedOutputTokens = request.requestedOutputTokens,
            maxOutputTokens = maxOutput,
            selectedThreads = threads,
            totalMemoryBytes = memory.totalBytes,
            availableMemoryBytes = memory.availableBytes,
            lowMemoryThresholdBytes = memory.lowMemoryThresholdBytes,
            processPssBytes = memory.processPssBytes,
            estimatedKvBytes = estimatedKvBytes,
            requiredHeadroomBytes = requiredHeadroom,
            coldLoad = !request.modelAlreadyLoaded,
            profileReload = profileReload,
            allowed = allowed,
            reason = reason,
        )
    }

    private fun estimateKvBytes(request: InferenceResourceRequest, context: Int): Long {
        if (request.format != ModelFormat.GGUF) return 0L
        val arch = request.architecture
        return 2L * arch.numLayers * context * (arch.numKvHeads * arch.headDim) * FP16_BYTES
    }

    private fun requiredHeadroom(
        modelSizeBytes: Long,
        estimatedKvBytes: Long,
        coldLoad: Boolean,
    ): Long = if (coldLoad) {
        modelSizeBytes.coerceAtLeast(0L) + estimatedKvBytes + COLD_LOAD_RESERVE
    } else {
        estimatedKvBytes + WARM_RUN_RESERVE
    }

    private data class ResourceTier(
        val context: Int,
        val batch: Int,
        val output: Int,
        val reason: String,
    )

    private const val MIN_CONTEXT = 512
    private const val MIN_OUTPUT = 64
    private const val LOW_MEMORY_CONTEXT = 1024
    private const val LOW_MEMORY_BATCH = 64
    private const val LOW_MEMORY_OUTPUT = 256
    private const val FP16_BYTES = 2
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L
    private const val LOW_RAM_LIMIT = 9L * GIB / 2L
    private const val MID_RAM_LIMIT = 13L * GIB / 2L
    private const val COLD_LOAD_RESERVE = 384L * MIB
    private const val WARM_RUN_RESERVE = 128L * MIB
}

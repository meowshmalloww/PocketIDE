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

/** Native llama.cpp KV encodings exposed by PocketIDE's pinned Android binding. */
enum class GgufKvCacheType(
    val nativeName: String,
    val displayName: String,
    private val blockElements: Int,
    private val blockBytes: Int,
) {
    F16("f16", "F16", 1, 2),
    Q8_0("q8_0", "Q8_0", 32, 34),
    Q4_0("q4_0", "Q4_0 experimental", 32, 18),
    ;

    fun bytesForRows(rowElements: Int, rowCount: Long): Long {
        if (rowElements <= 0 || rowCount <= 0L) return 0L
        val blocksPerRow = (rowElements + blockElements - 1L) / blockElements
        return rowCount * blocksPerRow * blockBytes
    }

    val isQuantized: Boolean get() = this != F16
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
    val loadedKvCacheTypeK: GgufKvCacheType? = null,
    val loadedKvCacheTypeV: GgufKvCacheType? = null,
    val loadedFlashAttention: String? = null,
)

/**
 * The exact native allocation profile selected before prompt construction and model loading.
 * Benchmark exports include this record so context and memory decisions are reproducible.
 */
data class InferenceResourcePlan(
    val format: ModelFormat,
    val requestedContext: Int,
    val modelContextLimit: Int,
    val effectiveContext: Int,
    val batchSize: Int,
    val requestedOutputTokens: Int,
    val maxOutputTokens: Int,
    val selectedThreads: Int,
    val kvCacheTypeK: String,
    val kvCacheTypeV: String,
    val flashAttention: String,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val androidLowMemory: Boolean,
    val processPssBytes: Long,
    val estimatedKvBytes: Long,
    val estimatedWeightWorkingSetBytes: Long,
    val requiredHeadroomBytes: Long,
    val requiredTotalCapacityBytes: Long,
    val mmapWeights: Boolean,
    val sparseMoe: Boolean,
    val capacityConstrained: Boolean,
    val coldLoad: Boolean,
    val profileReload: Boolean,
    val allowed: Boolean,
    val reason: String,
) {
    val kvCacheQuantized: Boolean
        get() = kvCacheTypeK.startsWith("q", ignoreCase = true) ||
            kvCacheTypeV.startsWith("q", ignoreCase = true)

    fun blockingMessage(): String = if (capacityConstrained) {
        "This model profile exceeds the safe total-memory capacity. PocketIDE estimates about " +
            "${requiredTotalCapacityBytes.toMiB()} MB total is needed including mapped weights, " +
            "KV cache, the current app process, and Android reserve, but this device exposes " +
            "${totalMemoryBytes.toMiB()} MB. Choose a smaller model or context."
    } else if (androidLowMemory) {
        "Android reports a critical low-memory state, so PocketIDE stopped before starting a new " +
            "native model load. Close other apps, reopen PocketIDE, and try again. The mapped " +
            "model file is checked separately against total device capacity."
    } else if (sparseMoe) {
        "Not enough immediate memory headroom for this sparse MoE profile. PocketIDE needs about " +
            "${requiredHeadroomBytes.toMiB()} MB free for KV and native runtime buffers, but Android " +
            "reports ${availableMemoryBytes.toMiB()} MB. Its ${estimatedWeightWorkingSetBytes.toMiB()} MB " +
            "memory-mapped weight file is checked separately against total device capacity instead " +
            "of being incorrectly counted as an up-front allocation."
    } else {
        "Not enough immediate memory headroom to load this model profile. PocketIDE needs about " +
            "${requiredHeadroomBytes.toMiB()} MB available for native KV and runtime buffers, " +
            "but Android reports ${availableMemoryBytes.toMiB()} MB. Close other apps, reopen " +
            "PocketIDE, and try again."
    }

    private fun Long.toMiB(): Long = this / MIB

    private companion object {
        const val MIB = 1024L * 1024L
    }
}

/**
 * Memory-derived context planning for phone-class inference.
 *
 * GGUF uses true native context and never exceeds the model's GGUF metadata. Q8_0 KV is selected
 * above 4K because it nearly halves Qwen's cache while preserving a higher-quality cache than Q4.
 * Q4 exists in the binding for controlled experiments but is never selected automatically.
 * PTE context remains fixed by its export.
 */
object InferenceResourcePlanner {
    fun plan(
        request: InferenceResourceRequest,
        memory: DeviceMemorySnapshot,
    ): InferenceResourcePlan {
        val modelLimit = request.architecture.maxContextLength.coerceAtLeast(MIN_CONTEXT)
        val requestedContext = request.requestedContext.coerceIn(MIN_CONTEXT, modelLimit)
        val requestedOutput = request.requestedOutputTokens.coerceAtLeast(MIN_OUTPUT)
        val requestedThreads = request.selectedThreads.coerceIn(
            1,
            Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
        val sparseMoe = request.architecture.isMixtureOfExperts

        if (request.format == ModelFormat.PTE) {
            return planPte(
                request = request,
                memory = memory,
                modelLimit = modelLimit,
                requestedContext = requestedContext,
                requestedOutput = requestedOutput,
                requestedThreads = requestedThreads,
            )
        }

        val loadedContext = request.loadedContextLength
        val retainLoadedDuringLowMemory = request.modelAlreadyLoaded && memory.lowMemory &&
            loadedContext != null
        val deviceCap = deviceContextCap(memory.totalBytes)
        val targetContext = if (retainLoadedDuringLowMemory) {
            loadedContext.coerceIn(MIN_CONTEXT, modelLimit)
        } else {
            standardContextAtOrBelow(minOf(requestedContext, deviceCap))
        }
        val threads = if (retainLoadedDuringLowMemory && request.loadedThreadCount != null) {
            request.loadedThreadCount.coerceIn(
                1,
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            )
        } else {
            requestedThreads
        }

        val candidates = contextCandidates(targetContext)
        var selected: Candidate? = null
        var lastCandidate: Candidate? = null
        for (context in candidates) {
            val policy = if (retainLoadedDuringLowMemory && context == loadedContext) {
                CachePolicy(
                    request.loadedKvCacheTypeK ?: automaticCachePolicy(context).key,
                    request.loadedKvCacheTypeV ?: automaticCachePolicy(context).value,
                    request.loadedFlashAttention ?: automaticCachePolicy(context).flashAttention,
                )
            } else {
                automaticCachePolicy(context)
            }
            val batch = if (retainLoadedDuringLowMemory && context == loadedContext &&
                request.loadedBatchSize != null
            ) {
                request.loadedBatchSize.coerceIn(1, context)
            } else {
                batchFor(context, memory.lowMemory)
            }
            val kvBytes = estimateGgufKvBytes(request.architecture, context, policy)
            val weightWorkingSetBytes = if (!request.modelAlreadyLoaded && sparseMoe) {
                request.modelSizeBytes.coerceAtLeast(0L)
            } else {
                0L
            }
            val loadedKvBytes = loadedKvBytes(request)
            val exactLoadedProfile = request.modelAlreadyLoaded &&
                request.loadedContextLength == context &&
                request.loadedBatchSize == batch &&
                request.loadedThreadCount == threads &&
                (request.loadedKvCacheTypeK ?: GgufKvCacheType.F16) == policy.key &&
                (request.loadedKvCacheTypeV ?: GgufKvCacheType.F16) == policy.value &&
                (request.loadedFlashAttention ?: "auto") == policy.flashAttention
            val profileReload = request.modelAlreadyLoaded && !exactLoadedProfile
            val required = requiredHeadroom(
                estimatedKvBytes = kvBytes,
                loadedKvBytes = loadedKvBytes,
                coldLoad = !request.modelAlreadyLoaded,
                profileReload = profileReload,
                lowMemoryThresholdBytes = memory.lowMemoryThresholdBytes,
            )
            val requiredTotalCapacity = if (!request.modelAlreadyLoaded) {
                requiredTotalCapacity(
                    modelSizeBytes = request.modelSizeBytes,
                    estimatedKvBytes = kvBytes,
                    processPssBytes = memory.processPssBytes,
                    lowMemoryThresholdBytes = memory.lowMemoryThresholdBytes,
                )
            } else {
                0L
            }
            val totalCapacityFits = requiredTotalCapacity == 0L ||
                memory.totalBytes >= requiredTotalCapacity
            val fits = exactLoadedProfile ||
                (!memory.lowMemory && memory.availableBytes >= required && totalCapacityFits)
            val candidate = Candidate(
                context = context,
                batch = batch,
                policy = policy,
                kvBytes = kvBytes,
                weightWorkingSetBytes = weightWorkingSetBytes,
                requiredHeadroom = required,
                exactLoadedProfile = exactLoadedProfile,
                profileReload = profileReload,
                requiredTotalCapacity = requiredTotalCapacity,
                totalCapacityFits = totalCapacityFits,
                fits = fits,
            )
            lastCandidate = candidate
            if (fits) {
                selected = candidate
                break
            }
        }

        val choice = selected ?: requireNotNull(lastCandidate)
        val allowed = selected != null
        val maxOutput = requestedOutput
            .coerceAtMost(outputCap(memory.totalBytes))
            .coerceAtMost((choice.context / 2).coerceAtLeast(MIN_OUTPUT))
        val reason = buildString {
            append("model limit $modelLimit; device tier cap $deviceCap")
            if (request.requestedContext > modelLimit) {
                append("; request clamped to model metadata")
            }
            if (targetContext < requestedContext) {
                append("; target reduced to $targetContext for this device tier")
            }
            if (choice.context < targetContext) {
                append("; memory preflight reduced native context to ${choice.context}")
            }
            append("; KV ${choice.policy.key.displayName}/${choice.policy.value.displayName}")
            append(" with Flash Attention ${choice.policy.flashAttention}")
            if (choice.policy.key.isQuantized || choice.policy.value.isQuantized) {
                append("; quantized KV is a real native cache allocation, not weight quantization")
            }
            if (sparseMoe) {
                append("; sparse MoE ${request.architecture.expertUsedCount ?: "unknown"}/")
                append("${request.architecture.expertCount ?: "unknown"} experts active per token")
                append("; active parameters reduce compute, not the mapped weight working set")
                append("; mapped expert residency is a page-pressure risk, not an up-front allocation")
            }
            when {
                choice.exactLoadedProfile -> append("; reusing the exact loaded GGUF profile")
                choice.profileReload -> append("; releasing and replacing the loaded GGUF profile")
                else -> {
                    append("; cold native load with mmap weights")
                    append("; immediate headroom counts KV/runtime buffers, not the entire file mapping")
                    append("; total-capacity guard ${choice.requiredTotalCapacity / MIB} MiB")
                }
            }
            append("; output budget $maxOutput is clamped again after exact tokenization")
            if (!choice.totalCapacityFits) append("; total-capacity guard failed")
            if (!allowed) append("; native loading blocked before Android could kill the process")
        }

        return InferenceResourcePlan(
            format = request.format,
            requestedContext = request.requestedContext,
            modelContextLimit = modelLimit,
            effectiveContext = choice.context,
            batchSize = choice.batch,
            requestedOutputTokens = request.requestedOutputTokens,
            maxOutputTokens = maxOutput,
            selectedThreads = threads,
            kvCacheTypeK = choice.policy.key.nativeName,
            kvCacheTypeV = choice.policy.value.nativeName,
            flashAttention = choice.policy.flashAttention,
            totalMemoryBytes = memory.totalBytes,
            availableMemoryBytes = memory.availableBytes,
            lowMemoryThresholdBytes = memory.lowMemoryThresholdBytes,
            androidLowMemory = memory.lowMemory,
            processPssBytes = memory.processPssBytes,
            estimatedKvBytes = choice.kvBytes,
            estimatedWeightWorkingSetBytes = choice.weightWorkingSetBytes,
            requiredHeadroomBytes = choice.requiredHeadroom,
            requiredTotalCapacityBytes = choice.requiredTotalCapacity,
            mmapWeights = true,
            sparseMoe = sparseMoe,
            capacityConstrained = !choice.totalCapacityFits,
            coldLoad = !request.modelAlreadyLoaded,
            profileReload = choice.profileReload,
            allowed = allowed,
            reason = reason,
        )
    }

    private fun planPte(
        request: InferenceResourceRequest,
        memory: DeviceMemorySnapshot,
        modelLimit: Int,
        requestedContext: Int,
        requestedOutput: Int,
        requestedThreads: Int,
    ): InferenceResourcePlan {
        val bytesPerElement = request.architecture.exportedKvBytesPerElement
        val estimatedKv = bytesPerElement?.let {
            estimatePlainKvBytes(request.architecture, requestedContext, it)
        } ?: 0L
        val required = if (request.modelAlreadyLoaded) {
            WARM_RUN_RESERVE
        } else {
            request.modelSizeBytes.coerceAtLeast(0L) + estimatedKv + COLD_LOAD_RESERVE
        }
        val allowed = request.modelAlreadyLoaded ||
            (!memory.lowMemory && memory.availableBytes >= required)
        val output = requestedOutput
            .coerceAtMost(outputCap(memory.totalBytes))
            .coerceAtMost((requestedContext / 2).coerceAtLeast(MIN_OUTPUT))
        val reason = buildString {
            append("PTE context, cache precision, workers, and delegates are fixed by the export")
            append("; app sequence upper bound $requestedContext of export limit $modelLimit")
            if (request.requestedContext > modelLimit) append("; UI request clamped to the export bound")
            if (bytesPerElement != null) {
                append("; KV estimate uses the published ${bytesPerElement * 8}-bit export recipe")
            } else {
                append("; KV precision is not exposed, so no cache-memory reduction is claimed")
            }
            if (!allowed) append("; native loading blocked before Android could kill the process")
        }
        return InferenceResourcePlan(
            format = request.format,
            requestedContext = request.requestedContext,
            modelContextLimit = modelLimit,
            effectiveContext = requestedContext,
            batchSize = 0,
            requestedOutputTokens = request.requestedOutputTokens,
            maxOutputTokens = output,
            selectedThreads = requestedThreads,
            kvCacheTypeK = bytesPerElement?.let { "exported_${it * 8}bit" } ?: "export_controlled",
            kvCacheTypeV = bytesPerElement?.let { "exported_${it * 8}bit" } ?: "export_controlled",
            flashAttention = "export_controlled",
            totalMemoryBytes = memory.totalBytes,
            availableMemoryBytes = memory.availableBytes,
            lowMemoryThresholdBytes = memory.lowMemoryThresholdBytes,
            androidLowMemory = memory.lowMemory,
            processPssBytes = memory.processPssBytes,
            estimatedKvBytes = estimatedKv,
            estimatedWeightWorkingSetBytes = 0L,
            requiredHeadroomBytes = required,
            requiredTotalCapacityBytes = 0L,
            mmapWeights = false,
            sparseMoe = false,
            capacityConstrained = false,
            coldLoad = !request.modelAlreadyLoaded,
            profileReload = false,
            allowed = allowed,
            reason = reason,
        )
    }

    private fun automaticCachePolicy(context: Int): CachePolicy = if (context > F16_CONTEXT_LIMIT) {
        CachePolicy(
            key = GgufKvCacheType.Q8_0,
            value = GgufKvCacheType.Q8_0,
            flashAttention = "on",
        )
    } else {
        CachePolicy(
            key = GgufKvCacheType.F16,
            value = GgufKvCacheType.F16,
            flashAttention = "auto",
        )
    }

    private fun estimateGgufKvBytes(
        architecture: ModelSpec.Architecture,
        context: Int,
        policy: CachePolicy,
    ): Long {
        val rows = architecture.numLayers.toLong() * context * architecture.numKvHeads
        return policy.key.bytesForRows(architecture.headDim, rows) +
            policy.value.bytesForRows(architecture.headDim, rows)
    }

    private fun estimatePlainKvBytes(
        architecture: ModelSpec.Architecture,
        context: Int,
        bytesPerElement: Int,
    ): Long = 2L * architecture.numLayers * context *
        (architecture.numKvHeads * architecture.headDim) * bytesPerElement

    private fun loadedKvBytes(request: InferenceResourceRequest): Long {
        val context = request.loadedContextLength ?: return 0L
        val policy = CachePolicy(
            key = request.loadedKvCacheTypeK ?: GgufKvCacheType.F16,
            value = request.loadedKvCacheTypeV ?: GgufKvCacheType.F16,
            flashAttention = request.loadedFlashAttention ?: "auto",
        )
        return estimateGgufKvBytes(request.architecture, context, policy)
    }

    private fun requiredHeadroom(
        estimatedKvBytes: Long,
        loadedKvBytes: Long,
        coldLoad: Boolean,
        profileReload: Boolean,
        lowMemoryThresholdBytes: Long,
    ): Long = when {
        // GGUF weights use a file-backed mmap. They can become resident as inference touches pages,
        // but ActivityManager.availMem is not an up-front allocation budget for the complete file.
        // Count anonymous KV/runtime allocations here and enforce mapped weights in the separate
        // whole-device capacity guard below.
        coldLoad -> estimatedKvBytes + maxOf(COLD_LOAD_RESERVE, lowMemoryThresholdBytes)
        profileReload -> (estimatedKvBytes - loadedKvBytes).coerceAtLeast(0L) + PROFILE_RELOAD_RESERVE
        else -> WARM_RUN_RESERVE
    }

    /**
     * mmap does not allocate the complete GGUF as anonymous memory, but the mapped pages still
     * need physical capacity while inference touches weights. Keep a separate whole-device guard
     * without incorrectly requiring every file byte to be present in ActivityManager.availMem.
     */
    private fun requiredTotalCapacity(
        modelSizeBytes: Long,
        estimatedKvBytes: Long,
        processPssBytes: Long,
        lowMemoryThresholdBytes: Long,
    ): Long = modelSizeBytes.coerceAtLeast(0L) +
        estimatedKvBytes.coerceAtLeast(0L) +
        processPssBytes.coerceAtLeast(0L) +
        maxOf(MMAP_SYSTEM_RESERVE, lowMemoryThresholdBytes + MMAP_THRESHOLD_MARGIN)

    private fun deviceContextCap(totalBytes: Long): Int = when {
        totalBytes <= 9L * GIB / 2L -> 8192
        totalBytes <= 13L * GIB / 2L -> 16384
        totalBytes <= 10L * GIB -> 32768
        totalBytes <= 14L * GIB -> 65536
        else -> 131072
    }

    private fun batchFor(context: Int, lowMemory: Boolean): Int = when {
        lowMemory -> 64
        context <= 2048 -> 128
        context <= 8192 -> 256
        else -> 512
    }.coerceAtMost(context)

    private fun outputCap(totalBytes: Long): Int = if (totalBytes <= 9L * GIB / 2L) 768 else 1024

    private fun standardContextAtOrBelow(value: Int): Int =
        CONTEXT_STEPS.lastOrNull { it <= value } ?: MIN_CONTEXT

    private fun contextCandidates(target: Int): List<Int> = buildList {
        add(target)
        CONTEXT_STEPS.asReversed().forEach { if (it < target) add(it) }
    }.distinct()

    private data class CachePolicy(
        val key: GgufKvCacheType,
        val value: GgufKvCacheType,
        val flashAttention: String,
    )

    private data class Candidate(
        val context: Int,
        val batch: Int,
        val policy: CachePolicy,
        val kvBytes: Long,
        val weightWorkingSetBytes: Long,
        val requiredHeadroom: Long,
        val exactLoadedProfile: Boolean,
        val profileReload: Boolean,
        val requiredTotalCapacity: Long,
        val totalCapacityFits: Boolean,
        val fits: Boolean,
    )

    private const val MIN_CONTEXT = 512
    private const val MIN_OUTPUT = 64
    private const val F16_CONTEXT_LIMIT = 4096
    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L
    private const val COLD_LOAD_RESERVE = 384L * MIB
    private const val PROFILE_RELOAD_RESERVE = 256L * MIB
    private const val WARM_RUN_RESERVE = 128L * MIB
    private const val MMAP_SYSTEM_RESERVE = 768L * MIB
    private const val MMAP_THRESHOLD_MARGIN = 256L * MIB
    private val CONTEXT_STEPS = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072)
}

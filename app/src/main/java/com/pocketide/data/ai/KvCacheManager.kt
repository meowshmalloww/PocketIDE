package com.pocketide.data.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Estimates and manages the KV cache memory footprint for on-device LLM inference.
 *
 * The KV cache stores key/value tensors for attention computation. For quantized models
 * (INT4 weights), the KV cache is often the dominant memory consumer during generation.
 *
 * Memory estimation formula:
 * ```
 * kv_cache_bytes = 2 * num_layers * seq_len * hidden_dim * kv_heads * head_dim * bytes_per_element
 * ```
 *
 * This manager:
 * 1. Estimates KV cache size for a given model config and sequence length.
 * 2. Determines when eviction is needed based on available heap memory.
 * 3. Recommends a reduced sequence length or context reset when memory is low.
 * 4. Tracks cumulative token positions to decide when to trigger context reset.
 *
 * The caller (typically [AiService]) consults this manager before each generation call
 * to ensure the KV cache won't cause OOM.
 */
class KvCacheManager(
    internal val numLayers: Int,
    private val hiddenDim: Int,
    private val kvHeads: Int,
    private val headDim: Int,
    private val bytesPerElement: Int = 2, // FP16 KV cache estimate by default
) {

    private var currentSeqLen: Int = 0
    private var totalTokensGenerated: Int = 0

    /**
     * Estimates the KV cache memory in bytes for the given sequence length.
     */
    fun estimateKvCacheBytes(seqLen: Int): Long {
        // 2 (K and V) * layers * seq_len * (kv_heads * head_dim) * bytes_per_element
        return 2L * numLayers * seqLen * (kvHeads * headDim) * bytesPerElement
    }

    /**
     * Estimates the KV cache memory in megabytes.
     */
    fun estimateKvCacheMb(seqLen: Int): Float {
        return estimateKvCacheBytes(seqLen) / (1024f * 1024f)
    }

    /**
     * Checks whether the current device has enough heap memory for the KV cache
     * at the requested sequence length, plus a safety margin for activations.
     *
     * @param seqLen the planned generation sequence length
     * @param safetyMarginBytes extra memory to reserve for activations and intermediate tensors
     * @return [KvCacheDecision] indicating whether to proceed, reduce, or reset
     */
    fun checkMemory(
        seqLen: Int,
        context: Context? = null,
        safetyMarginBytes: Long = 64L * 1024 * 1024,
    ): KvCacheDecision {
        val runtime = Runtime.getRuntime()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val maxHeap = runtime.maxMemory()
        val javaHeapAvailable = (maxHeap - usedHeap).coerceAtLeast(0L)
        val memoryBudget = memoryBudget(context, javaHeapAvailable)
        val availableMemory = memoryBudget.availableBytes

        val kvCacheBytes = estimateKvCacheBytes(seqLen)
        val totalNeeded = kvCacheBytes + safetyMarginBytes

        val availableMb = availableMemory / (1024f * 1024f)
        val neededMb = totalNeeded / (1024f * 1024f)

        Log.d(TAG, "KV cache check: need=${neededMb.format(1)}MB, available=${availableMb.format(1)}MB, " +
            "seqLen=$seqLen, layers=$numLayers, kvHeads=$kvHeads, headDim=$headDim, " +
            "bytesPerElem=$bytesPerElement, source=${memoryBudget.source}")

        return when {
            totalNeeded > availableMemory -> {
                val maxAffordableSeqLen = findMaxAffordableSeqLen(availableMemory - safetyMarginBytes)
                if (maxAffordableSeqLen < MIN_SEQ_LEN) {
                    KvCacheDecision.ResetContext(
                        reason = "Insufficient memory for KV cache: need ${neededMb.format(1)}MB, " +
                            "available ${availableMb.format(1)}MB (${memoryBudget.source})",
                    )
                } else {
                    KvCacheDecision.ReduceSeqLen(
                        newSeqLen = maxAffordableSeqLen,
                        estimatedKvCacheMb = estimateKvCacheMb(maxAffordableSeqLen),
                        reason = "Reduced seqLen from $seqLen to $maxAffordableSeqLen to fit memory",
                    )
                }
            }
            totalNeeded > availableMemory * 0.75f -> {
                val reducedSeqLen = (seqLen * 0.8f).toInt().coerceAtLeast(MIN_SEQ_LEN)
                KvCacheDecision.ReduceSeqLen(
                    newSeqLen = reducedSeqLen,
                    estimatedKvCacheMb = estimateKvCacheMb(reducedSeqLen),
                    reason = "Memory pressure warning: reduced seqLen from $seqLen to $reducedSeqLen",
                )
            }
            else -> {
                KvCacheDecision.Proceed(
                    estimatedKvCacheMb = estimateKvCacheMb(seqLen),
                )
            }
        }
    }

    /**
     * Records that tokens were generated, updating internal tracking.
     * Call after each generation to track cumulative position.
     */
    fun recordGeneration(tokenCount: Int) {
        totalTokensGenerated += tokenCount
        currentSeqLen += tokenCount
    }

    /**
     * Resets the KV cache tracking after a context reset.
     */
    fun reset() {
        currentSeqLen = 0
        totalTokensGenerated = 0
        Log.i(TAG, "KV cache tracking reset")
    }

    /**
     * Returns the total tokens generated since last reset.
     */
    fun totalTokensGenerated(): Int = totalTokensGenerated

    /** This reports the estimate only; native KV precision is controlled by the runtime/model export. */
    fun isKvCacheQuantized(): Boolean = bytesPerElement == 1

    fun currentBytesPerElement(): Int = bytesPerElement

    /**
     * Finds the maximum sequence length that fits within the given byte budget.
     */
    private fun findMaxAffordableSeqLen(byteBudget: Long): Int {
        if (byteBudget <= 0) return 0
        val bytesPerToken = 2L * numLayers * (kvHeads * headDim) * bytesPerElement
        if (bytesPerToken <= 0) return 0
        val affordable = (byteBudget / bytesPerToken).toInt()
        return if (affordable < MIN_SEQ_LEN) 0 else affordable.coerceAtMost(MAX_SEQ_LEN)
    }

    private fun memoryBudget(context: Context?, javaHeapAvailable: Long): MemoryBudget {
        if (context == null) return MemoryBudget(javaHeapAvailable, "Java heap")
        val memoryInfo = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }.getOrNull() ?: return MemoryBudget(javaHeapAvailable, "Java heap")
        val systemHeadroom = (memoryInfo.availMem - memoryInfo.threshold).coerceAtLeast(0L)
        return MemoryBudget(
            availableBytes = minOf(javaHeapAvailable, systemHeadroom),
            source = if (memoryInfo.lowMemory) {
                "Android low-memory state"
            } else {
                "min(Java heap, Android system headroom)"
            },
        )
    }

    private data class MemoryBudget(val availableBytes: Long, val source: String)

    private fun Float.format(digits: Int): String = "%.${digits}f".format(this)

    sealed class KvCacheDecision {
        data class Proceed(
            val estimatedKvCacheMb: Float,
        ) : KvCacheDecision()

        data class ReduceSeqLen(
            val newSeqLen: Int,
            val estimatedKvCacheMb: Float,
            val reason: String,
        ) : KvCacheDecision()

        data class ResetContext(
            val reason: String,
        ) : KvCacheDecision()
    }

    companion object {
        private const val TAG = "KvCacheManager"
        private const val MIN_SEQ_LEN = 64
        private const val MAX_SEQ_LEN = 8192

        /**
         * Creates a [KvCacheManager] with typical parameters for common small models.
         *
         * @param modelSize approximate parameter count (e.g. 0.5e9 for 0.5B, 1.5e9 for 1.5B)
         */
        fun forModelSize(modelSize: Float, bytesPerElement: Int = 2): KvCacheManager {
            // Heuristic for backward compatibility — prefer forArchitecture()
            val numLayers = when {
                modelSize <= 0.5e9f -> 24
                modelSize <= 1.5e9f -> 28
                modelSize <= 3.0e9f -> 36
                modelSize <= 7.0e9f -> 28
                else -> 40
            }
            val hiddenDim = when {
                modelSize <= 0.5e9f -> 896
                modelSize <= 1.5e9f -> 1536
                modelSize <= 3.0e9f -> 2048
                modelSize <= 7.0e9f -> 3584
                else -> 5120
            }
            val kvHeads = when {
                modelSize <= 0.5e9f -> 2
                modelSize <= 1.5e9f -> 2
                modelSize <= 3.0e9f -> 4
                else -> 4
            }
            val headDim = when {
                modelSize <= 0.5e9f -> 64
                modelSize <= 1.5e9f -> 128
                modelSize <= 3.0e9f -> 64
                else -> 128
            }
            return KvCacheManager(
                numLayers = numLayers,
                hiddenDim = hiddenDim,
                kvHeads = kvHeads,
                headDim = headDim,
                bytesPerElement = bytesPerElement,
            )
        }

        /**
         * Creates a [KvCacheManager] from a [ModelSpec.Architecture] detected
         * from the actual model file. Preferred over [forModelSize].
         */
        fun forArchitecture(arch: ModelSpec.Architecture, bytesPerElement: Int = 2): KvCacheManager {
            return KvCacheManager(
                numLayers = arch.numLayers,
                hiddenDim = arch.hiddenDim,
                kvHeads = arch.numKvHeads,
                headDim = arch.headDim,
                bytesPerElement = bytesPerElement,
            )
        }
    }
}

package com.pocketide.data.ai

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
    private val numLayers: Int,
    private val hiddenDim: Int,
    private val kvHeads: Int,
    private val headDim: Int,
    private val bytesPerElement: Int = 2, // FP16 KV cache by default
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
    fun checkMemory(seqLen: Int, safetyMarginBytes: Long = 64L * 1024 * 1024): KvCacheDecision {
        val runtime = Runtime.getRuntime()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val maxHeap = runtime.maxMemory()
        val availableHeap = maxHeap - usedHeap

        val kvCacheBytes = estimateKvCacheBytes(seqLen)
        val totalNeeded = kvCacheBytes + safetyMarginBytes

        val availableMb = availableHeap / (1024f * 1024f)
        val neededMb = totalNeeded / (1024f * 1024f)

        Log.d(TAG, "KV cache check: need=${neededMb.format(1)}MB, available=${availableMb.format(1)}MB, " +
            "seqLen=$seqLen, layers=$numLayers, kvHeads=$kvHeads, headDim=$headDim")

        return when {
            totalNeeded > availableHeap -> {
                val maxAffordableSeqLen = findMaxAffordableSeqLen(availableHeap - safetyMarginBytes)
                if (maxAffordableSeqLen < MIN_SEQ_LEN) {
                    KvCacheDecision.ResetContext(
                        reason = "Insufficient memory for KV cache: need ${neededMb.format(1)}MB, " +
                            "available ${availableMb.format(1)}MB",
                    )
                } else {
                    KvCacheDecision.ReduceSeqLen(
                        newSeqLen = maxAffordableSeqLen,
                        estimatedKvCacheMb = estimateKvCacheMb(maxAffordableSeqLen),
                        reason = "Reduced seqLen from $seqLen to $maxAffordableSeqLen to fit memory",
                    )
                }
            }
            totalNeeded > availableHeap * 0.75f -> {
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

    /**
     * Finds the maximum sequence length that fits within the given byte budget.
     */
    private fun findMaxAffordableSeqLen(byteBudget: Long): Int {
        if (byteBudget <= 0) return MIN_SEQ_LEN
        val bytesPerToken = 2L * numLayers * (kvHeads * headDim) * bytesPerElement
        if (bytesPerToken <= 0) return MIN_SEQ_LEN
        return (byteBudget / bytesPerToken).toInt().coerceIn(MIN_SEQ_LEN, MAX_SEQ_LEN)
    }

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
            val numLayers = when {
                modelSize <= 0.5e9f -> 24
                modelSize <= 1.5e9f -> 28
                modelSize <= 3.0e9f -> 32
                modelSize <= 7.0e9f -> 32
                else -> 40
            }
            val hiddenDim = when {
                modelSize <= 0.5e9f -> 896
                modelSize <= 1.5e9f -> 1536
                modelSize <= 3.0e9f -> 2048
                modelSize <= 7.0e9f -> 4096
                else -> 5120
            }
            val kvHeads = when {
                modelSize <= 0.5e9f -> 2
                modelSize <= 1.5e9f -> 2
                modelSize <= 3.0e9f -> 4
                else -> 8
            }
            val headDim = hiddenDim / when {
                modelSize <= 0.5e9f -> 14
                modelSize <= 1.5e9f -> 12
                modelSize <= 3.0e9f -> 16
                else -> 32
            }
            return KvCacheManager(
                numLayers = numLayers,
                hiddenDim = hiddenDim,
                kvHeads = kvHeads,
                headDim = headDim,
                bytesPerElement = bytesPerElement,
            )
        }
    }
}

package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KvCacheManagerTest {

    @Test
    fun `estimateKvCacheBytes returns positive value for valid seqLen`() {
        val manager = KvCacheManager(
            numLayers = 24,
            hiddenDim = 896,
            kvHeads = 2,
            headDim = 64,
            bytesPerElement = 2,
        )

        val bytes = manager.estimateKvCacheBytes(1024)
        assertTrue(bytes > 0)
        // 2 * 24 * 1024 * (2 * 64) * 2 = 12,582,912 bytes
        assertEquals(12_582_912L, bytes)
    }

    @Test
    fun `estimateKvCacheMb converts bytes to megabytes`() {
        val manager = KvCacheManager(
            numLayers = 24,
            hiddenDim = 896,
            kvHeads = 2,
            headDim = 64,
            bytesPerElement = 2,
        )

        val mb = manager.estimateKvCacheMb(1024)
        assertTrue(mb > 0f)
        // 12582912 / (1024*1024) ≈ 12.0 MB
        assertTrue(mb > 11f && mb < 13f)
    }

    @Test
    fun `checkMemory returns Proceed when memory is sufficient`() {
        val manager = KvCacheManager(
            numLayers = 4,
            hiddenDim = 128,
            kvHeads = 1,
            headDim = 32,
            bytesPerElement = 2,
        )

        // Small KV cache, should fit
        val decision = manager.checkMemory(64)
        assertTrue(decision is KvCacheManager.KvCacheDecision.Proceed)
    }

    @Test
    fun `checkMemory returns ReduceSeqLen or ResetContext when memory is tight`() {
        // Create a manager with very large cache requirements
        val manager = KvCacheManager(
            numLayers = 100,
            hiddenDim = 4096,
            kvHeads = 32,
            headDim = 128,
            bytesPerElement = 8,
        )

        // Request very large seqLen that likely won't fit
        val decision = manager.checkMemory(8192, safetyMarginBytes = 1L * 1024 * 1024 * 1024)
        assertTrue(
            "Expected ReduceSeqLen or ResetContext, got $decision",
            decision is KvCacheManager.KvCacheDecision.ReduceSeqLen ||
                decision is KvCacheManager.KvCacheDecision.ResetContext,
        )
    }

    @Test
    fun `recordGeneration increments token count`() {
        val manager = KvCacheManager(
            numLayers = 24,
            hiddenDim = 896,
            kvHeads = 2,
            headDim = 64,
            bytesPerElement = 2,
        )

        assertEquals(0, manager.totalTokensGenerated())
        manager.recordGeneration(50)
        assertEquals(50, manager.totalTokensGenerated())
        manager.recordGeneration(30)
        assertEquals(80, manager.totalTokensGenerated())
    }

    @Test
    fun `reset clears token count`() {
        val manager = KvCacheManager(
            numLayers = 24,
            hiddenDim = 896,
            kvHeads = 2,
            headDim = 64,
            bytesPerElement = 2,
        )

        manager.recordGeneration(100)
        assertEquals(100, manager.totalTokensGenerated())
        manager.reset()
        assertEquals(0, manager.totalTokensGenerated())
    }

    @Test
    fun `forModelSize creates manager with reasonable defaults for 0_5B model`() {
        val manager = KvCacheManager.forModelSize(0.5e9f)

        val bytes = manager.estimateKvCacheBytes(512)
        assertTrue(bytes > 0)
        // Should be reasonable for a 0.5B model (not gigabytes)
        val mb = bytes / (1024f * 1024f)
        assertTrue("Expected < 100MB for 0.5B model at 512 seqLen, got $mb MB", mb < 100f)
    }

    @Test
    fun `forModelSize creates manager with reasonable defaults for 7B model`() {
        val manager = KvCacheManager.forModelSize(7e9f)

        val bytes = manager.estimateKvCacheBytes(2048)
        assertTrue(bytes > 0)
        // 7B model will have larger cache, but should still be estimable
        val mb = bytes / (1024f * 1024f)
        assertTrue("Expected positive MB, got $mb", mb > 0f)
    }

    @Test
    fun `ReduceSeqLen decision contains new seqLen and reason`() {
        val decision = KvCacheManager.KvCacheDecision.ReduceSeqLen(
            newSeqLen = 256,
            estimatedKvCacheMb = 10.5f,
            reason = "Memory pressure",
        )

        assertEquals(256, decision.newSeqLen)
        assertEquals("Memory pressure", decision.reason)
        assertEquals(10.5f, decision.estimatedKvCacheMb, 0.01f)
    }

    @Test
    fun `ResetContext decision contains reason`() {
        val decision = KvCacheManager.KvCacheDecision.ResetContext(
            reason = "OOM imminent",
        )

        assertEquals("OOM imminent", decision.reason)
    }
}

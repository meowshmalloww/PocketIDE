package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NativeKernelBenchmarkTest {
    @Test
    fun `parses real llama cpp benchmark json`() {
        val result = NativeKernelBenchmark.parse(
            """{"n_kv_max":1024,"n_batch":512,"n_ubatch":256,"flash_attn":0,"n_gpu_layers":0,"n_threads":2,"n_threads_batch":4,"pp":128,"tg":32,"pl":1,"t_pp":0.5,"speed_pp":256.0,"t_tg":4.0,"speed_tg":8.0,"t":4.5,"speed":35.5}""",
            repetitions = 5,
        )!!

        assertEquals(256.0, result.promptTokensPerSecond, 0.0001)
        assertEquals(8.0, result.generationTokensPerSecond, 0.0001)
        assertEquals(2, result.threads)
        assertEquals(4, result.batchThreads)
        assertEquals(5, result.repetitions)
    }

    @Test
    fun `rejects missing or zero speed measurements`() {
        assertNull(NativeKernelBenchmark.parse("{}", repetitions = 3))
        assertNull(
            NativeKernelBenchmark.parse(
                """{"speed_pp":0,"speed_tg":0}""",
                repetitions = 3,
            ),
        )
    }

    @Test
    fun `accepts wrapper output with optional metadata omitted and surrounding log text`() {
        val result = NativeKernelBenchmark.parse(
            "native result: {\"pp\":128,\"tg\":32,\"speed_pp\":250.0,\"speed_tg\":8.5}",
            repetitions = 3,
        )

        requireNotNull(result)
        assertEquals(250.0, result.promptTokensPerSecond, 0.0001)
        assertEquals(8.5, result.generationTokensPerSecond, 0.0001)
        assertEquals(1, result.parallelSequences)
        assertEquals(0, result.contextSize)
    }
}

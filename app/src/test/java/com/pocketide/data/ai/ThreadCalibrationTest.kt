package com.pocketide.data.ai

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThreadCalibrationTest {

    @Test
    fun `selects profile with fastest measured median`() {
        val selected = ThreadProfileSelector.select(
            listOf(
                sample(1, 8.4f, 1_700),
                sample(1, 8.5f, 1_710),
                sample(4, 7.8f, 2_000),
                sample(4, 7.7f, 1_980),
            ),
        )

        assertEquals(1, selected?.threadCount)
        assertEquals(8.45f, selected?.medianTokensPerSecond ?: 0f, 0.001f)
    }

    @Test
    fun `within one percent prefers lower process memory`() {
        val selected = ThreadProfileSelector.select(
            listOf(
                sample(1, 8.40f, 1_700),
                sample(1, 8.42f, 1_710),
                sample(2, 8.45f, 1_950),
                sample(2, 8.46f, 1_960),
            ),
        )

        assertEquals(1, selected?.threadCount)
    }

    @Test
    fun `returns null when no valid throughput was measured`() {
        assertNull(ThreadProfileSelector.select(listOf(sample(1, 0f, 100))))
    }

    @Test
    fun `persists calibration for the exact model file`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val model = File(context.cacheDir, "calibration-test.gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
        val repository = ThreadCalibrationRepository(context)
        val calibration = ThreadCalibration(
            threadCount = 1,
            medianTokensPerSecond = 8.45f,
            averageTtftMs = 120,
            averagePeakProcessPssBytes = 1_700L * 1024L * 1024L,
            measuredAtMs = 1234,
        )

        repository.save(model.absolutePath, calibration)

        assertEquals(calibration, repository.load(model.absolutePath))
        repository.clear(model.absolutePath)
        assertNull(repository.load(model.absolutePath))
    }

    private fun sample(threads: Int, tps: Float, pssMb: Long) = ThreadProfileSample(
        threadCount = threads,
        tokensPerSecond = tps,
        ttftMs = 120,
        peakProcessPssBytes = pssMb * 1024L * 1024L,
    )
}

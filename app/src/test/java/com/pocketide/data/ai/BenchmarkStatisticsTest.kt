package com.pocketide.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkStatisticsTest {
    @Test
    fun `even median averages both middle values`() {
        val stats = BenchmarkStatistics.calculate(listOf(1.0, 9.0, 3.0, 5.0))
        assertNotNull(stats)
        assertEquals(4.0, stats!!.median, 0.0001)
    }

    @Test
    fun `nearest rank percentiles and stability are deterministic`() {
        val stats = BenchmarkStatistics.calculate((1..10).toList())!!
        assertEquals(9.0, stats.p90, 0.0001)
        assertEquals(10.0, stats.p95, 0.0001)
        assertEquals(5.5, stats.mean, 0.0001)
    }

    @Test
    fun `empty input has no statistics`() {
        assertNull(BenchmarkStatistics.calculate(emptyList()))
    }

    @Test
    fun `sustained retention compares ordered first and second halves`() {
        val sustained = BenchmarkStatistics.calculateSustained(listOf(10.0, 10.0, 9.0, 9.0))!!

        assertEquals(10.0, sustained.firstHalfMedian, 0.0001)
        assertEquals(9.0, sustained.secondHalfMedian, 0.0001)
        assertEquals(90.0, sustained.retentionPercent, 0.0001)
        assertEquals(-10.0, sustained.changePercent, 0.0001)
    }

    @Test
    fun `sustained statistics require four real runs`() {
        assertNull(BenchmarkStatistics.calculateSustained(listOf(10.0, 9.0, 8.0)))
    }
}

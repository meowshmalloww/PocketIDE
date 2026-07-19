package com.pocketide.data.ai

import kotlin.math.ceil
import kotlin.math.sqrt

/** Descriptive statistics used by both the human-readable and JSON reports. */
data class MetricStatistics(
    val count: Int,
    val minimum: Double,
    val maximum: Double,
    val mean: Double,
    val median: Double,
    val standardDeviation: Double,
    val coefficientOfVariationPercent: Double,
    val p90: Double,
    val p95: Double,
)

data class SustainedMetricStatistics(
    val runCount: Int,
    val firstHalfMedian: Double,
    val secondHalfMedian: Double,
    val retentionPercent: Double,
    val changePercent: Double,
)

object BenchmarkStatistics {
    fun calculate(values: List<Number>): MetricStatistics? {
        val finite = values.map { it.toDouble() }.filter { it.isFinite() }.sorted()
        if (finite.isEmpty()) return null
        val mean = finite.average()
        val variance = finite.sumOf { value ->
            val delta = value - mean
            delta * delta
        } / finite.size
        val deviation = sqrt(variance)
        return MetricStatistics(
            count = finite.size,
            minimum = finite.first(),
            maximum = finite.last(),
            mean = mean,
            median = median(finite),
            standardDeviation = deviation,
            coefficientOfVariationPercent = if (mean == 0.0) 0.0 else deviation / mean * 100.0,
            p90 = nearestRank(finite, 0.90),
            p95 = nearestRank(finite, 0.95),
        )
    }

    fun calculateSustained(values: List<Number>): SustainedMetricStatistics? {
        if (values.size < 4) return null
        val split = values.size / 2
        val first = calculate(values.take(split)) ?: return null
        val second = calculate(values.takeLast(split)) ?: return null
        if (first.median <= 0.0) return null
        val retention = second.median / first.median * 100.0
        return SustainedMetricStatistics(
            runCount = values.size,
            firstHalfMedian = first.median,
            secondHalfMedian = second.median,
            retentionPercent = retention,
            changePercent = retention - 100.0,
        )
    }

    private fun median(sorted: List<Double>): Double {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    /** Nearest-rank percentile, explicitly recorded in the report schema. */
    private fun nearestRank(sorted: List<Double>, percentile: Double): Double {
        val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}

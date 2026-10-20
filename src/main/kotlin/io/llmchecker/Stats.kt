package io.llmchecker

import kotlin.math.floor

fun percentile(sorted: DoubleArray, p: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val rank = (p / 100.0) * (sorted.size - 1)
    val lo = floor(rank).toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
}

fun distOf(values: Collection<Double>): Dist? {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return null
    val sorted = clean.sorted().toDoubleArray()
    return Dist(
        count = sorted.size,
        min = sorted.first(),
        mean = sorted.average(),
        p50 = percentile(sorted, 50.0),
        p90 = percentile(sorted, 90.0),
        p95 = percentile(sorted, 95.0),
        p99 = percentile(sorted, 99.0),
        max = sorted.last(),
    )
}

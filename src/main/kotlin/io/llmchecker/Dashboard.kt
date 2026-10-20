package io.llmchecker

import kotlinx.serialization.Serializable

@Serializable
data class ProbeStats(
    val samples: Int,
    val failures: Int,
    val successRate: Double,
    val avgTps: Double?,
    val avgE2eTps: Double?,
    val avgTtftMs: Double?,
    val avgResponseMs: Double?,
    val p50ResponseMs: Double?,
    val p95ResponseMs: Double?,
    val lastTps: Double?,
    val lastResponseMs: Double?,
    val lastAt: Long?,
    val lastError: String?,
    val avgPromptTokens: Double?,
    val avgCompletionTokens: Double?,
)

@Serializable
data class BenchStats(
    val runs: Int,
    val requests: Int,
    val avgTps: Double?,
    val avgTtftMs: Double?,
    val avgResponseMs: Double?,
    val peakOutputTps: Double?,
    val lastRunAt: Long?,
)

@Serializable
data class DashboardModel(val model: String, val probe: ProbeStats?, val bench: BenchStats?)

@Serializable
data class DashboardResponse(
    val generatedAt: Long,
    val windowMinutes: Int,
    val models: List<DashboardModel>,
    val totalProbes: Int,
    val totalBenchRuns: Int,
)

private fun List<Double>.avgOrNull(): Double? = if (isEmpty()) null else average()

fun computeProbeStats(samples: List<MonitorSample>): ProbeStats? {
    if (samples.isEmpty()) return null
    val ok = samples.filter { it.ok }
    val totals = ok.map { it.totalMs }
    val last = samples.maxBy { it.ts }
    val lastOk = ok.maxByOrNull { it.ts }
    return ProbeStats(
        samples = samples.size,
        failures = samples.size - ok.size,
        successRate = ok.size.toDouble() / samples.size,
        avgTps = ok.mapNotNull { it.decodeTps }.avgOrNull(),
        avgE2eTps = ok.mapNotNull { it.e2eTps }.avgOrNull(),
        avgTtftMs = ok.mapNotNull { it.ttftMs }.avgOrNull(),
        avgResponseMs = totals.avgOrNull(),
        p50ResponseMs = distOf(totals)?.p50,
        p95ResponseMs = distOf(totals)?.p95,
        lastTps = lastOk?.decodeTps,
        lastResponseMs = lastOk?.totalMs,
        lastAt = last.ts,
        lastError = last.error,
        avgPromptTokens = ok.mapNotNull { it.promptTokens?.toDouble() }.avgOrNull(),
        avgCompletionTokens = ok.mapNotNull { it.completionTokens?.toDouble() }.avgOrNull(),
    )
}

/**
 * Benchmark figures use the lowest-concurrency stage of every run, because that is the closest thing to
 * what a single developer experiences. Aggregate throughput peaks use every stage.
 */
fun computeBenchStats(runs: List<RunState>): BenchStats? {
    val usable = runs.filter { r -> r.stages.any { it.completed > 0 } }
    if (usable.isEmpty()) return null
    val requests = usable.flatMap { run ->
        run.stages.filter { it.completed > 0 }.minBy { it.concurrency }.requests.filter { it.ok }
    }
    return BenchStats(
        runs = usable.size,
        requests = requests.size,
        avgTps = requests.mapNotNull { it.decodeTps }.avgOrNull(),
        avgTtftMs = requests.mapNotNull { it.ttftMs }.avgOrNull(),
        avgResponseMs = requests.map { it.totalMs }.avgOrNull(),
        peakOutputTps = usable.flatMap { it.stages }.maxOfOrNull { it.outputTps },
        lastRunAt = usable.maxOf { it.finishedAt ?: it.createdAt },
    )
}

fun computeDashboard(runs: List<RunState>, samples: List<MonitorSample>, windowMinutes: Int, now: Long = System.currentTimeMillis()): DashboardResponse {
    val since = if (windowMinutes <= 0) 0L else now - windowMinutes * 60_000L
    val inWindowSamples = samples.filter { it.ts >= since }
    val inWindowRuns = runs.filter { it.status != "running" && (it.finishedAt ?: it.createdAt) >= since }
    val models = (inWindowSamples.map { it.model } + inWindowRuns.map { it.request.model }).distinct().sorted()
    return DashboardResponse(
        generatedAt = now,
        windowMinutes = windowMinutes,
        models = models.map { m ->
            DashboardModel(
                model = m,
                probe = computeProbeStats(inWindowSamples.filter { it.model == m }),
                bench = computeBenchStats(inWindowRuns.filter { it.request.model == m }),
            )
        },
        totalProbes = inWindowSamples.size,
        totalBenchRuns = inWindowRuns.size,
    )
}

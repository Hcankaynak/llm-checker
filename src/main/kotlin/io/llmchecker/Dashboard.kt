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
data class DashboardModel(val model: String, val probe: ProbeStats?)

@Serializable
data class DashboardResponse(
    val generatedAt: Long,
    val windowMinutes: Int,
    val models: List<DashboardModel>,
    val totalProbes: Int,
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

fun computeDashboard(samples: List<MonitorSample>, windowMinutes: Int, now: Long = System.currentTimeMillis()): DashboardResponse {
    val since = if (windowMinutes <= 0) 0L else now - windowMinutes * 60_000L
    val inWindow = samples.filter { it.ts >= since }
    val models = inWindow.map { it.model }.distinct().sorted()
    return DashboardResponse(
        generatedAt = now,
        windowMinutes = windowMinutes,
        models = models.map { m -> DashboardModel(m, computeProbeStats(inWindow.filter { it.model == m })) },
        totalProbes = inWindow.size,
    )
}

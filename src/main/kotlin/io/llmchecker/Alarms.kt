package io.llmchecker

import java.util.Locale
import kotlin.math.abs

const val METRIC_FAILURE = "failure"

private val METRICS = setOf("decodeTps", "totalMs", "ttftMs", "completionTokens", METRIC_FAILURE)
private val DIRECTIONS = setOf("drop", "rise", "change")
private val UNITS = setOf("percent", "absolute")
private val BASELINES = setOf("window", "previous", "last5")

@kotlinx.serialization.Serializable
data class AlarmRule(
    val id: String = "",
    val enabled: Boolean = true,
    val model: String = "",
    val metric: String = "decodeTps",
    val direction: String = "drop",
    val threshold: Double = 10.0,
    val unit: String = "percent",
    val baseline: String = "previous",
    val windowMinutes: Int = 1440,
    val channel: String = "",
    val topic: String = "",
) {
    fun validate() {
        require(metric in METRICS) { "metric must be decodeTps, totalMs, ttftMs, completionTokens, or failure" }
        require(channel.isNotBlank() && channel.length <= 100) { "Channel is required" }
        require(topic.isNotBlank() && topic.length <= 200) { "Topic is required" }
        require(model.length <= 200) { "Model name is too long" }
        if (metric == METRIC_FAILURE) return
        require(direction in DIRECTIONS) { "direction must be drop, rise, or change" }
        require(unit in UNITS) { "unit must be percent or absolute" }
        require(baseline in BASELINES) { "baseline must be window, previous, or last5" }
        require(threshold.isFinite() && threshold > 0 && threshold <= 1_000_000) { "Threshold must be greater than 0" }
        require(windowMinutes in 0..525_600) { "Window must be between 0 and 525600 minutes" }
    }

    fun sameComparison(other: AlarmRule): Boolean =
        model == other.model && metric == other.metric && direction == other.direction &&
            threshold == other.threshold && unit == other.unit && baseline == other.baseline &&
            windowMinutes == other.windowMinutes && channel == other.channel && topic == other.topic
}

sealed class AlarmVerdict {
    data object Waiting : AlarmVerdict()
    data object Clear : AlarmVerdict()
    data class Trip(val message: String) : AlarmVerdict()
}

fun metricValue(sample: MonitorSample, metric: String): Double? = when (metric) {
    "decodeTps" -> sample.decodeTps
    "totalMs" -> sample.totalMs
    "ttftMs" -> sample.ttftMs
    "completionTokens" -> sample.completionTokens?.toDouble()
    else -> null
}

fun judgeAlarm(history: List<MonitorSample>, current: MonitorSample, rule: AlarmRule): AlarmVerdict {
    if (rule.model.isNotBlank() && rule.model != current.model) return AlarmVerdict.Waiting
    if (rule.metric == METRIC_FAILURE) {
        return if (current.ok) AlarmVerdict.Clear else AlarmVerdict.Trip(failureText(current))
    }
    if (!current.ok) return AlarmVerdict.Clear
    if (metricValue(current, rule.metric) == null) return AlarmVerdict.Waiting
    val series = history
        .filter { it.model == current.model && it.ok && metricValue(it, rule.metric) != null }
        .sortedBy { it.ts }
    val pair = when (rule.baseline) {
        "previous" -> previousPair(series, current, rule.metric)
        "last5" -> lastFivePair(series, current, rule.metric)
        else -> windowPair(series, current, rule)
    } ?: return AlarmVerdict.Waiting
    val (baseline, value) = pair
    if (rule.unit == "percent" && baseline == 0.0) return AlarmVerdict.Waiting
    return if (crossed(baseline, value, rule)) AlarmVerdict.Trip(alarmText(rule, current.model, baseline, value))
    else AlarmVerdict.Clear
}

private fun previousPair(series: List<MonitorSample>, current: MonitorSample, metric: String): Pair<Double, Double>? {
    val idx = series.indexOfLast { it === current || it.ts == current.ts }
    if (idx <= 0) return null
    val baseline = metricValue(series[idx - 1], metric) ?: return null
    val value = metricValue(series[idx], metric) ?: return null
    return baseline to value
}

private fun lastFivePair(series: List<MonitorSample>, current: MonitorSample, metric: String): Pair<Double, Double>? {
    val idx = series.indexOfLast { it === current || it.ts == current.ts }
    if (idx < 9) return null
    val through = series.take(idx + 1)
    if (through.size < 10) return null
    val newest = through.takeLast(5).map { metricValue(it, metric)!! }.average()
    val prior = through.dropLast(5).takeLast(5).map { metricValue(it, metric)!! }.average()
    return prior to newest
}

private fun windowPair(series: List<MonitorSample>, current: MonitorSample, rule: AlarmRule): Pair<Double, Double>? {
    val value = metricValue(current, rule.metric) ?: return null
    val since = if (rule.windowMinutes <= 0) 0L else current.ts - rule.windowMinutes * 60_000L
    val others = series.filter { it.ts >= since && !(it === current || it.ts == current.ts) }
    if (others.isEmpty()) return null
    return others.map { metricValue(it, rule.metric)!! }.average() to value
}

private fun crossed(baseline: Double, current: Double, rule: AlarmRule): Boolean {
    val delta = if (rule.unit == "percent") (current - baseline) / abs(baseline) * 100.0 else current - baseline
    return when (rule.direction) {
        "drop" -> delta <= -rule.threshold
        "rise" -> delta >= rule.threshold
        else -> abs(delta) >= rule.threshold
    }
}

fun failureText(sample: MonitorSample): String {
    val why = sample.error?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return "${sample.model} check failed$why.\nAlarm: check failed."
}

fun alarmText(rule: AlarmRule, model: String, baseline: Double, current: Double): String {
    val amount = if (rule.unit == "percent") (current - baseline) / abs(baseline) * 100.0 else current - baseline
    val verb = when {
        amount < 0 -> "dropped"
        amount > 0 -> "rose"
        else -> "changed"
    }
    val magnitude = if (rule.unit == "percent") "${trimNum(abs(amount))}%" else "${trimNum(abs(amount))} ${metricSuffix(rule.metric)}"
    val numbers = "${trimNum(baseline)} → ${trimNum(current)} ${metricSuffix(rule.metric)}"
    val how = when (rule.direction) {
        "drop" -> "drops by"
        "rise" -> "rises by"
        else -> "changes by"
    }
    val limit = if (rule.unit == "percent") "${trimNum(rule.threshold)}%" else "${trimNum(rule.threshold)} ${metricSuffix(rule.metric)}"
    return "$model ${metricLabel(rule.metric)} $verb $magnitude ($numbers) compared with ${baselinePhrase(rule)}.\n" +
        "Alarm: ${metricLabel(rule.metric)} $how at least $limit compared with ${baselinePhrase(rule)}."
}

fun metricLabel(metric: String): String = when (metric) {
    "decodeTps" -> "decode speed"
    "totalMs" -> "response time"
    "ttftMs" -> "time to first token"
    "completionTokens" -> "output tokens"
    METRIC_FAILURE -> "check"
    else -> metric
}

fun metricSuffix(metric: String): String = when (metric) {
    "decodeTps" -> "tok/s"
    "totalMs", "ttftMs" -> "ms"
    "completionTokens" -> "tokens"
    else -> ""
}

fun baselinePhrase(rule: AlarmRule): String = when (rule.baseline) {
    "previous" -> "the previous check"
    "last5" -> "the 5 checks before the latest 5"
    else -> when (rule.windowMinutes) {
        0 -> "the all-time average"
        60 -> "the 1-hour average"
        360 -> "the 6-hour average"
        1440 -> "the 24-hour average"
        10080 -> "the 7-day average"
        else -> "the ${rule.windowMinutes}-minute average"
    }
}

private fun trimNum(v: Double): String =
    if (v.isFinite() && v % 1.0 == 0.0 && abs(v) < 1e15) String.format(Locale.US, "%.0f", v)
    else String.format(Locale.US, "%.1f", v)

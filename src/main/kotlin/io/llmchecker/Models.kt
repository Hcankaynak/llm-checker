package io.llmchecker

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val baseUrl: String = "",
    val apiKey: String? = null,
    val skipTlsVerify: Boolean = false,
    val requestTimeoutSec: Int = 600,
)

@Serializable
data class SettingsView(
    val baseUrl: String,
    val hasApiKey: Boolean,
    val skipTlsVerify: Boolean,
    val requestTimeoutSec: Int,
    val mockAvailable: Boolean,
    val mockBaseUrl: String?,
)

@Serializable
data class SettingsUpdate(
    val baseUrl: String,
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
    val skipTlsVerify: Boolean = false,
    val requestTimeoutSec: Int = 600,
)

@Serializable
data class ModelInfo(
    val id: String,
    val ownedBy: String? = null,
    val maxModelLen: Long? = null,
    val root: String? = null,
    val created: Long? = null,
)

@Serializable
data class ModelsResponse(val baseUrl: String, val latencyMs: Double, val models: List<ModelInfo>)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class CheckRequest(val model: String)

@Serializable
data class CheckResult(
    val id: String,
    val title: String,
    val status: String,
    val detail: String,
    val latencyMs: Double? = null,
)

@Serializable
data class ChecksResponse(val model: String, val results: List<CheckResult>)

@Serializable
data class BenchmarkRequest(
    val model: String,
    val protocol: String = "openai",
    val promptTokens: Int = 512,
    val sharedPrefixTokens: Int = 0,
    val outputTokens: Int = 256,
    val concurrencyLevels: List<Int> = listOf(1),
    val requestsPerLevel: Int = 8,
    val warmupRequests: Int = 1,
    val ignoreEos: Boolean = true,
    val temperature: Double = 0.0,
    val label: String? = null,
) {
    fun validate() {
        require(model.isNotBlank()) { "model is required" }
        require(protocol in setOf("openai", "anthropic")) { "protocol must be openai or anthropic" }
        require(promptTokens in 16..400_000) { "promptTokens must be between 16 and 400000" }
        require(sharedPrefixTokens in 0..promptTokens) { "sharedPrefixTokens must be between 0 and promptTokens" }
        require(outputTokens in 1..65_536) { "outputTokens must be between 1 and 65536" }
        require(concurrencyLevels.isNotEmpty() && concurrencyLevels.size <= 12) { "1 to 12 concurrency levels are allowed" }
        require(concurrencyLevels.all { it in 1..512 }) { "each concurrency level must be between 1 and 512" }
        require(requestsPerLevel in 1..2000) { "requestsPerLevel must be between 1 and 2000" }
        require(warmupRequests in 0..20) { "warmupRequests must be between 0 and 20" }
        require(temperature in 0.0..2.0) { "temperature must be between 0 and 2" }
        val total = concurrencyLevels.sumOf { maxOf(requestsPerLevel, it) }
        require(total <= 6000) { "total request count ($total) exceeds the limit of 6000" }
    }
}

@Serializable
data class RequestResult(
    val index: Int,
    val ok: Boolean,
    val error: String? = null,
    val httpStatus: Int? = null,
    val startOffsetMs: Double = 0.0,
    val ttftMs: Double? = null,
    val totalMs: Double = 0.0,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    val tokensEstimated: Boolean = false,
    val decodeTps: Double? = null,
    val tpotMs: Double? = null,
    val e2eTps: Double? = null,
)

@Serializable
data class Dist(
    val count: Int,
    val min: Double,
    val mean: Double,
    val p50: Double,
    val p90: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
)

@Serializable
data class ServerSnapshot(
    val running: Double? = null,
    val waiting: Double? = null,
    val kvCacheUsage: Double? = null,
    val generationTokensTotal: Double? = null,
    val promptTokensTotal: Double? = null,
    val prefixCacheHits: Double? = null,
    val prefixCacheQueries: Double? = null,
    val preemptionsTotal: Double? = null,
)

@Serializable
data class ServerStageStats(
    val generationTps: Double? = null,
    val prefixCacheHitRate: Double? = null,
    val peakRunning: Double? = null,
    val peakWaiting: Double? = null,
    val peakKvCacheUsage: Double? = null,
    val preemptions: Double? = null,
)

@Serializable
data class StageResult(
    val concurrency: Int,
    val requested: Int,
    val completed: Int,
    val failed: Int,
    val wallSec: Double,
    val outputTps: Double,
    val inputTps: Double,
    val requestsPerSec: Double,
    val ttft: Dist? = null,
    val tpot: Dist? = null,
    val itl: Dist? = null,
    val e2e: Dist? = null,
    val perRequestTps: Dist? = null,
    val avgPromptTokens: Double? = null,
    val avgCompletionTokens: Double? = null,
    val avgCachedTokens: Double? = null,
    val server: ServerStageStats? = null,
    val errors: Map<String, Int> = emptyMap(),
    val requests: List<RequestResult> = emptyList(),
)

@Serializable
data class StageProgress(
    val concurrency: Int,
    val completed: Int,
    val total: Int,
    val failed: Int,
    val liveOutputTps: Double,
)

@Serializable
data class Calibration(
    val tokensPerWord: Double,
    val overheadTokens: Int,
    val fromServer: Boolean,
)

@Serializable
data class RunSummary(
    val peakOutputTps: Double,
    val peakOutputTpsConcurrency: Int,
    val singleStreamTps: Double?,
    val ttftP50Ms: Double?,
    val ttftP95Ms: Double?,
    val totalRequests: Int,
    val failedRequests: Int,
)

@Serializable
data class RunState(
    val id: String,
    val status: String,
    val phase: String,
    val request: BenchmarkRequest,
    val baseUrl: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    val calibration: Calibration? = null,
    val coldProbe: RequestResult? = null,
    val stages: List<StageResult> = emptyList(),
    val currentStage: StageProgress? = null,
    val summary: RunSummary? = null,
)

@Serializable
data class RunListItem(
    val id: String,
    val status: String,
    val label: String?,
    val model: String,
    val protocol: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val promptTokens: Int,
    val outputTokens: Int,
    val concurrencyLevels: List<Int>,
    val summary: RunSummary?,
)

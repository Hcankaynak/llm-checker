package io.llmchecker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

data class Settings(
    val baseUrl: String = "",
    val apiKey: String? = null,
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

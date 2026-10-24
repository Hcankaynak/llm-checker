package io.llmchecker

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.X509TrustManager

class UpstreamException(message: String, val status: Int? = null) : Exception(message)

data class StreamRequest(
    val protocol: String,
    val model: String,
    val prompt: String,
    val maxTokens: Int,
    val ignoreEos: Boolean,
    val temperature: Double,
)

data class StreamOutcome(val result: RequestResult, val gapsMs: DoubleArray)

fun normalizeBaseUrl(input: String): String {
    var url = input.trim().trimEnd('/')
    for (suffix in listOf("/v1/chat/completions", "/v1/messages", "/v1/models", "/v1", "/metrics")) {
        if (url.endsWith(suffix)) {
            url = url.removeSuffix(suffix)
            break
        }
    }
    return url.trimEnd('/')
}

private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class VllmClient(private val settings: Settings) : AutoCloseable {
    val root: String = normalizeBaseUrl(settings.baseUrl)
    private val log = LoggerFactory.getLogger(VllmClient::class.java)

    private val http = HttpClient(CIO) {
        expectSuccess = false
        engine {
            maxConnectionsCount = 2048
            endpoint.maxConnectionsPerRoute = 2048
            endpoint.connectTimeout = 10_000
            endpoint.keepAliveTime = 30_000
            endpoint.connectAttempts = 1
            if (settings.skipTlsVerify) {
                https { trustManager = TrustAllManager }
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = settings.requestTimeoutSec * 1000L
            socketTimeoutMillis = settings.requestTimeoutSec * 1000L
        }
    }

    init {
        if (root.isBlank()) throw UpstreamException("No vLLM base URL is configured. Set it on the Connection tab.")
        if (!root.startsWith("http://") && !root.startsWith("https://")) {
            throw UpstreamException("Base URL must start with http:// or https://")
        }
    }

    private fun HttpRequestBuilder.authHeaders(anthropic: Boolean = false) {
        settings.apiKey?.takeIf { it.isNotBlank() }?.let {
            header(HttpHeaders.Authorization, "Bearer $it")
            if (anthropic) header("x-api-key", it)
        }
        if (anthropic) header("anthropic-version", "2023-06-01")
    }

    suspend fun listModels(): Pair<List<ModelInfo>, Double> {
        log.info("Fetching models from {}", root)
        val t0 = System.nanoTime()
        val resp = try {
            http.get { url("$root/v1/models"); authHeaders() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = (System.nanoTime() - t0) / 1e6
            log.warn("Could not fetch models from {} in {} ms: {}", root, formatMillis(elapsed), describe(e), e)
            throw UpstreamException("Cannot reach $root: ${describe(e)}")
        }
        val latency = (System.nanoTime() - t0) / 1e6
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            val message = "GET /v1/models returned HTTP ${resp.status.value}: ${text.take(300)}"
            log.warn("Could not fetch models from {} in {} ms: {}", root, formatMillis(latency), message)
            throw UpstreamException(message, resp.status.value)
        }
        val parsed = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: run {
                val message = "GET /v1/models did not return JSON. Is this a vLLM/OpenAI-compatible server?"
                log.warn("Could not fetch models from {} in {} ms: {}", root, formatMillis(latency), message)
                throw UpstreamException(message)
            }
        val models = parsed["data"]?.jsonArray?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ModelInfo(
                id = id,
                ownedBy = o["owned_by"]?.jsonPrimitive?.contentOrNull,
                maxModelLen = o["max_model_len"]?.jsonPrimitive?.longOrNull,
                root = o["root"]?.jsonPrimitive?.contentOrNull,
                created = o["created"]?.jsonPrimitive?.longOrNull,
            )
        }.orEmpty()
        log.info("Fetched {} models from {} in {} ms", models.size, root, formatMillis(latency))
        return models to latency
    }

    fun chatBody(req: StreamRequest, stream: Boolean): JsonObject = when (req.protocol) {
        "anthropic" -> buildJsonObject {
            put("model", req.model)
            put("max_tokens", req.maxTokens)
            put("temperature", req.temperature)
            put("stream", stream)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", req.prompt) })
            }
        }
        else -> buildJsonObject {
            put("model", req.model)
            put("max_tokens", req.maxTokens)
            put("temperature", req.temperature)
            put("stream", stream)
            if (stream) putJsonObject("stream_options") { put("include_usage", true) }
            if (req.ignoreEos) put("ignore_eos", true)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "user"); put("content", req.prompt) })
            }
        }
    }

    suspend fun stream(req: StreamRequest, runStartNanos: Long, index: Int): StreamOutcome {
        val anthropic = req.protocol == "anthropic"
        val path = if (anthropic) "/v1/messages" else "/v1/chat/completions"
        val body = chatBody(req, stream = true).toString()

        log.info("Request to {} started", req.model)
        val start = System.nanoTime()
        val startOffsetMs = (start - runStartNanos) / 1e6
        var firstTokenAt = 0L
        var lastTokenAt = 0L
        var chunkCount = 0
        var usagePrompt: Int? = null
        var usageCompletion: Int? = null
        var usageCached: Int? = null
        var httpStatus: Int? = null
        val gaps = ArrayList<Double>()

        fun fail(message: String, error: Throwable? = null): StreamOutcome {
            val end = System.nanoTime()
            val totalMs = (end - start) / 1e6
            if (error != null) {
                log.warn("Request to {} failed in {} ms: {}", req.model, formatMillis(totalMs), message, error)
            } else {
                log.warn("Request to {} failed in {} ms: {}", req.model, formatMillis(totalMs), message)
            }
            return StreamOutcome(
                RequestResult(
                    index = index, ok = false, error = message, httpStatus = httpStatus,
                    startOffsetMs = startOffsetMs, totalMs = totalMs,
                    ttftMs = if (firstTokenAt > 0) (firstTokenAt - start) / 1e6 else null,
                ),
                gaps.toDoubleArray(),
            )
        }

        try {
            val error: String? = http.prepareRequest {
                method = HttpMethod.Post
                url("$root$path")
                authHeaders(anthropic)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(body)
            }.execute { resp ->
                httpStatus = resp.status.value
                if (!resp.status.isSuccess()) {
                    return@execute "HTTP ${resp.status.value}: ${resp.bodyAsText().take(300)}"
                }
                val channel = resp.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val now = System.nanoTime()
                    val obj = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

                    obj["error"]?.let { err ->
                        val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                        return@execute "Server error in stream: ${msg.take(300)}"
                    }

                    val emitted: Boolean
                    if (anthropic) {
                        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                            "message_start" -> obj["message"]?.jsonObject?.get("usage")?.jsonObject?.let {
                                usagePrompt = it["input_tokens"]?.jsonPrimitive?.intOrNull ?: usagePrompt
                                usageCached = it["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: usageCached
                            }
                            "message_delta" -> obj["usage"]?.jsonObject?.let {
                                usageCompletion = it["output_tokens"]?.jsonPrimitive?.intOrNull ?: usageCompletion
                                it["input_tokens"]?.jsonPrimitive?.intOrNull?.let { v -> usagePrompt = v }
                            }
                        }
                        emitted = obj["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta" &&
                            obj["delta"]?.jsonObject?.let { d ->
                                listOf("text", "thinking", "partial_json").any { !d[it]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() }
                            } == true
                    } else {
                        obj["usage"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject?.let { u ->
                            usagePrompt = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: usagePrompt
                            usageCompletion = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: usageCompletion
                            usageCached = u["prompt_tokens_details"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                                ?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: usageCached
                        }
                        val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject
                        emitted = delta != null && (
                            listOf("content", "reasoning_content", "reasoning").any { !delta[it]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() } ||
                                (delta["tool_calls"] as? JsonArray)?.isNotEmpty() == true
                            )
                    }

                    if (emitted) {
                        if (firstTokenAt == 0L) firstTokenAt = now else gaps.add((now - lastTokenAt) / 1e6)
                        lastTokenAt = now
                        chunkCount++
                    }
                }
                null
            }
            if (error != null) return fail(error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(describe(e), e)
        }

        val end = System.nanoTime()
        if (firstTokenAt == 0L) return fail("Stream ended without any generated tokens")

        val estimated = usageCompletion == null
        val completion = usageCompletion ?: chunkCount
        val ttftMs = (firstTokenAt - start) / 1e6
        val totalMs = (end - start) / 1e6
        val decodeWindowMs = (lastTokenAt - firstTokenAt) / 1e6
        val decodeTps = if (completion > 1 && decodeWindowMs > 0) (completion - 1) / (decodeWindowMs / 1000.0) else null
        val tpot = if (completion > 1 && decodeWindowMs > 0) decodeWindowMs / (completion - 1) else null
        val e2eTps = if (totalMs > 0) completion / (totalMs / 1000.0) else null
        log.info("Request to {} succeeded in {} ms", req.model, formatMillis(totalMs))

        return StreamOutcome(
            RequestResult(
                index = index, ok = true, httpStatus = httpStatus, startOffsetMs = startOffsetMs,
                ttftMs = ttftMs, totalMs = totalMs, promptTokens = usagePrompt, completionTokens = completion,
                cachedTokens = usageCached, tokensEstimated = estimated,
                decodeTps = decodeTps, tpotMs = tpot, e2eTps = e2eTps,
            ),
            gaps.toDoubleArray(),
        )
    }

    override fun close() = http.close()
}

private fun formatMillis(ms: Double): String = String.format(Locale.US, "%.1f", ms)

fun describe(e: Throwable): String {
    val name = e::class.simpleName ?: "Error"
    val msg = e.message ?: e.cause?.message
    return if (msg.isNullOrBlank()) name else "$name: ${msg.take(300)}"
}

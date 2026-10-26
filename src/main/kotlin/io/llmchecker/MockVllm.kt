package io.llmchecker

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A tiny stand-in for a vLLM server that speaks the OpenAI chat API, the Anthropic Messages API and
 * exposes Prometheus metrics. Throughput degrades as concurrency rises so the UI has something to show.
 */
class MockVllmState(private val speed: Double) {
    val inflight = AtomicInteger(0)
    val generated = AtomicLong(0)
    val promptTotal = AtomicLong(0)
    val prefixHits = AtomicLong(0)
    val prefixQueries = AtomicLong(0)
    private val seenPrompts = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val rng = Random()

    private val baseDecodeTps = 55.0 * speed
    private val basePrefillTps = 5000.0 * speed

    fun estimateTokens(text: String): Int = (text.split(Regex("\\s+")).size * 1.3).toInt() + 8

    fun cachedChars(prompt: String): Int {
        var best = 0
        for (prev in seenPrompts) {
            val limit = minOf(prev.length, prompt.length)
            var i = 0
            while (i < limit && prev[i] == prompt[i]) i++
            if (i > best) best = i
        }
        seenPrompts.addFirst(prompt)
        while (seenPrompts.size > 32) seenPrompts.pollLast()
        return best
    }

    private fun degradation(): Double = 1.0 + 0.12 * (inflight.get() - 1).coerceAtLeast(0)

    fun decodeDelayMs(): Long {
        val tps = baseDecodeTps / degradation()
        val jitter = 0.85 + rng.nextDouble() * 0.3
        return maxOf(0L, (1000.0 / tps * jitter).toLong())
    }

    fun prefillDelayMs(uncachedTokens: Int): Long =
        ((uncachedTokens / (basePrefillTps / degradation())) * 1000).toLong() + 15

    fun metrics(): String = buildString {
        appendLine("# HELP vllm:num_requests_running Number of requests in model execution batches.")
        appendLine("vllm:num_requests_running{model_name=\"mock-model\"} ${inflight.get()}")
        appendLine("vllm:num_requests_waiting{model_name=\"mock-model\"} 0")
        appendLine("vllm:kv_cache_usage_perc{model_name=\"mock-model\"} ${"%.4f".format((inflight.get() * 0.07).coerceAtMost(1.0))}")
        appendLine("vllm:generation_tokens_total{model_name=\"mock-model\"} ${generated.get()}")
        appendLine("vllm:prompt_tokens_total{model_name=\"mock-model\"} ${promptTotal.get()}")
        appendLine("vllm:prefix_cache_hits_total{model_name=\"mock-model\"} ${prefixHits.get()}")
        appendLine("vllm:prefix_cache_queries_total{model_name=\"mock-model\"} ${prefixQueries.get()}")
        appendLine("vllm:num_preemptions_total{model_name=\"mock-model\"} 0")
    }
}

fun Route.mockVllmRoutes(state: MockVllmState) {
    route("/mock") {
        get("/metrics") { call.respondText(state.metrics(), ContentType.Text.Plain) }

        get("/v1/models") {
            call.respondText(
                buildJsonObject {
                    put("object", "list")
                    putJsonArray("data") {
                        for (id in listOf("mock-model", "mock-coder-32b")) {
                            add(buildJsonObject {
                                put("id", id); put("object", "model"); put("owned_by", "vllm")
                                put("root", id); put("created", 1_700_000_000); put("max_model_len", 131072)
                            })
                        }
                    }
                }.toString(),
                ContentType.Application.Json,
            )
        }

        post("/v1/chat/completions") { handleChat(state, anthropic = false) }
        post("/v1/messages") { handleChat(state, anthropic = true) }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleChat(state: MockVllmState, anthropic: Boolean) {
    val body = runCatching { AppJson.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
    if (body == null) {
        call.respondText("""{"error":{"message":"invalid json"}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        return
    }
    val model = body["model"]?.jsonPrimitive?.content ?: "mock-model"
    // Test hook: hold this response open so a probe of "stall" can time out on its own.
    if (model == "stall") delay(15_000)
    val maxTokens = body["max_tokens"]?.jsonPrimitive?.int ?: 64
    val stream = body["stream"]?.jsonPrimitive?.boolean ?: false
    val prompt = body["messages"]?.jsonArray?.joinToString("\n") { it.jsonObject["content"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
    val hasTools = (body["tools"] as? JsonArray)?.isNotEmpty() == true
    val promptTokens = state.estimateTokens(prompt)

    if (hasTools) {
        val args = """{"city":"Paris"}"""
        val json = if (anthropic) buildJsonObject {
            put("id", "msg_mock"); put("type", "message"); put("role", "assistant"); put("model", model)
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "tool_use"); put("id", "toolu_1"); put("name", "get_weather")
                    putJsonObject("input") { put("city", "Paris") }
                })
            }
            put("stop_reason", "tool_use")
            putJsonObject("usage") { put("input_tokens", promptTokens); put("output_tokens", 12) }
        } else buildJsonObject {
            put("id", "chatcmpl-mock"); put("object", "chat.completion"); put("model", model)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0); put("finish_reason", "tool_calls")
                    putJsonObject("message") {
                        put("role", "assistant"); put("content", JsonPrimitive(null as String?))
                        putJsonArray("tool_calls") {
                            add(buildJsonObject {
                                put("id", "call_1"); put("type", "function")
                                putJsonObject("function") { put("name", "get_weather"); put("arguments", args) }
                            })
                        }
                    }
                })
            }
            putJsonObject("usage") { put("prompt_tokens", promptTokens); put("completion_tokens", 12); put("total_tokens", promptTokens + 12) }
        }
        delay(30)
        call.respondText(json.toString(), ContentType.Application.Json)
        return
    }

    state.inflight.incrementAndGet()
    try {
        val cachedChars = state.cachedChars(prompt)
        val cachedTokens = if (prompt.isEmpty()) 0 else (promptTokens.toLong() * cachedChars / prompt.length).toInt()
        state.prefixQueries.addAndGet(promptTokens.toLong())
        state.prefixHits.addAndGet(cachedTokens.toLong())
        state.promptTotal.addAndGet(promptTokens.toLong())
        val outTokens = maxTokens.coerceAtMost(4096)

        if (!stream) {
            delay(state.prefillDelayMs(promptTokens - cachedTokens))
            repeat(outTokens.coerceAtMost(32)) { delay(state.decodeDelayMs()) }
            state.generated.addAndGet(outTokens.toLong())
            val text = "ok"
            val json = if (anthropic) buildJsonObject {
                put("id", "msg_mock"); put("type", "message"); put("role", "assistant"); put("model", model)
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
                put("stop_reason", "end_turn")
                putJsonObject("usage") { put("input_tokens", promptTokens); put("output_tokens", 1) }
            } else buildJsonObject {
                put("id", "chatcmpl-mock"); put("object", "chat.completion"); put("model", model)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0); put("finish_reason", "stop")
                        putJsonObject("message") { put("role", "assistant"); put("content", text) }
                    })
                }
                putJsonObject("usage") { put("prompt_tokens", promptTokens); put("completion_tokens", 1); put("total_tokens", promptTokens + 1) }
            }
            call.respondText(json.toString(), ContentType.Application.Json)
            return
        }

        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            suspend fun send(event: String?, data: String) {
                if (event != null) writeStringUtf8("event: $event\n")
                writeStringUtf8("data: $data\n\n")
                flush()
            }
            delay(state.prefillDelayMs(promptTokens - cachedTokens))
            if (anthropic) {
                send("message_start", buildJsonObject {
                    put("type", "message_start")
                    putJsonObject("message") {
                        put("id", "msg_mock"); put("type", "message"); put("role", "assistant"); put("model", model)
                        putJsonObject("usage") {
                            put("input_tokens", promptTokens); put("output_tokens", 1)
                            put("cache_read_input_tokens", cachedTokens)
                        }
                    }
                }.toString())
                send("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
                for (i in 0 until outTokens) {
                    if (i > 0) delay(state.decodeDelayMs())
                    send("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"tok$i "}}""")
                }
                send("content_block_stop", """{"type":"content_block_stop","index":0}""")
                send("message_delta", """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":$outTokens}}""")
                send("message_stop", """{"type":"message_stop"}""")
            } else {
                send(null, """{"id":"chatcmpl-mock","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""")
                for (i in 0 until outTokens) {
                    if (i > 0) delay(state.decodeDelayMs())
                    send(null, """{"id":"chatcmpl-mock","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":{"content":"tok$i "}}]}""")
                }
                send(null, """{"id":"chatcmpl-mock","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}""")
                send(null, buildJsonObject {
                    put("id", "chatcmpl-mock"); put("object", "chat.completion.chunk"); put("model", model)
                    put("choices", buildJsonArray { })
                    putJsonObject("usage") {
                        put("prompt_tokens", promptTokens); put("completion_tokens", outTokens)
                        put("total_tokens", promptTokens + outTokens)
                        putJsonObject("prompt_tokens_details") { put("cached_tokens", cachedTokens) }
                    }
                }.toString())
                send(null, "[DONE]")
            }
            state.generated.addAndGet(outTokens.toLong())
        }
    } finally {
        state.inflight.decrementAndGet()
    }
}

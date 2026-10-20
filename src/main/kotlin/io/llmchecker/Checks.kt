package io.llmchecker

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val MIN_RECOMMENDED_CONTEXT = 32_768L

private val weatherToolOpenAi = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", "get_weather")
        put("description", "Get the current weather for a city")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("city")) }
        }
    }
}

private val weatherToolAnthropic = buildJsonObject {
    put("name", "get_weather")
    put("description", "Get the current weather for a city")
    putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("city")) }
    }
}

private fun userMessage(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

suspend fun runCompatibilityChecks(client: VllmClient, model: String): List<CheckResult> {
    val results = mutableListOf<CheckResult>()

    suspend fun check(id: String, title: String, block: suspend () -> CheckResult) {
        results += try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult(id, title, "fail", describe(e))
        }
    }

    var modelInfo: ModelInfo? = null
    check("models", "Model listing (GET /v1/models)") {
        val (models, latency) = client.listModels()
        modelInfo = models.firstOrNull { it.id == model }
        when {
            models.isEmpty() -> CheckResult("models", "Model listing (GET /v1/models)", "warn", "Server returned no models", latency)
            modelInfo == null -> CheckResult("models", "Model listing (GET /v1/models)", "warn", "${models.size} model(s) served, but '$model' is not among them", latency)
            else -> CheckResult("models", "Model listing (GET /v1/models)", "pass", "${models.size} model(s) served, '$model' is available", latency)
        }
    }

    check("context", "Context window size") {
        val len = modelInfo?.maxModelLen
        when {
            len == null -> CheckResult("context", "Context window size", "skip", "Server did not report max_model_len")
            len < MIN_RECOMMENDED_CONTEXT -> CheckResult(
                "context", "Context window size", "warn",
                "max_model_len is $len. Agentic coding tools send large prompts; at least $MIN_RECOMMENDED_CONTEXT is recommended.",
            )
            else -> CheckResult("context", "Context window size", "pass", "max_model_len is $len tokens")
        }
    }

    check("chat", "Chat completion (non-streaming)") {
        val body = buildJsonObject {
            put("model", model); put("max_tokens", 16); put("temperature", 0)
            putJsonArray("messages") { add(userMessage("Reply with the single word: ok")) }
        }
        val r = client.postJson("/v1/chat/completions", body)
        if (r.status !in 200..299) return@check CheckResult("chat", "Chat completion (non-streaming)", "fail", httpDetail(r), r.latencyMs)
        val text = r.json?.jsonObject?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        CheckResult("chat", "Chat completion (non-streaming)", "pass", "Responded: ${text?.trim()?.take(80) ?: "(empty content)"}", r.latencyMs)
    }

    check("stream", "Streaming with usage statistics") {
        val outcome = client.stream(StreamRequest("openai", model, "Count from 1 to 20.", 48, false, 0.0), System.nanoTime(), 0)
        val res = outcome.result
        when {
            !res.ok -> CheckResult("stream", "Streaming with usage statistics", "fail", res.error ?: "stream failed", res.totalMs)
            res.tokensEstimated -> CheckResult(
                "stream", "Streaming with usage statistics", "warn",
                "Streaming works but the server sent no usage block; token counts will be estimated from chunks.", res.totalMs,
            )
            else -> CheckResult(
                "stream", "Streaming with usage statistics", "pass",
                "TTFT ${"%.0f".format(res.ttftMs)} ms, ${res.completionTokens} tokens, usage reported by server", res.totalMs,
            )
        }
    }

    check("tools", "Tool calling (OpenAI format)") {
        val body = buildJsonObject {
            put("model", model); put("max_tokens", 128); put("temperature", 0)
            putJsonArray("messages") { add(userMessage("What is the weather in Paris? Use the tool.")) }
            putJsonArray("tools") { add(weatherToolOpenAi) }
            put("tool_choice", "auto")
        }
        val r = client.postJson("/v1/chat/completions", body)
        if (r.status !in 200..299) {
            return@check CheckResult(
                "tools", "Tool calling (OpenAI format)", "fail",
                httpDetail(r) + " Start vLLM with --enable-auto-tool-choice and a matching --tool-call-parser.", r.latencyMs,
            )
        }
        val message = r.json?.jsonObject?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
        val calls = message?.get("tool_calls") as? JsonArray
        if (calls != null && calls.isNotEmpty()) {
            val name = calls[0].jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            CheckResult("tools", "Tool calling (OpenAI format)", "pass", "Model produced a structured tool call to '$name'", r.latencyMs)
        } else {
            CheckResult(
                "tools", "Tool calling (OpenAI format)", "warn",
                "Request accepted but no structured tool_calls came back. Check --tool-call-parser matches the model family.", r.latencyMs,
            )
        }
    }

    check("anthropic", "Anthropic Messages API (POST /v1/messages)") {
        val body = buildJsonObject {
            put("model", model); put("max_tokens", 16)
            putJsonArray("messages") { add(userMessage("Reply with the single word: ok")) }
        }
        val r = client.postJson("/v1/messages", body, anthropic = true)
        when {
            r.status in 200..299 -> {
                val type = r.json?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
                if (type == "message") CheckResult("anthropic", "Anthropic Messages API (POST /v1/messages)", "pass", "Native endpoint available; Claude Code can point at this server directly", r.latencyMs)
                else CheckResult("anthropic", "Anthropic Messages API (POST /v1/messages)", "warn", "HTTP ${r.status} but the body is not an Anthropic message", r.latencyMs)
            }
            r.status == 404 || r.status == 405 -> CheckResult(
                "anthropic", "Anthropic Messages API (POST /v1/messages)", "warn",
                "Not exposed by this server. Claude Code needs a translating gateway (for example LiteLLM) in front of it.", r.latencyMs,
            )
            else -> CheckResult("anthropic", "Anthropic Messages API (POST /v1/messages)", "fail", httpDetail(r), r.latencyMs)
        }
    }

    val anthropicOk = results.firstOrNull { it.id == "anthropic" }?.status == "pass"
    check("anthropic-tools", "Tool use (Anthropic format)") {
        if (!anthropicOk) return@check CheckResult("anthropic-tools", "Tool use (Anthropic format)", "skip", "Skipped because /v1/messages is unavailable")
        val body = buildJsonObject {
            put("model", model); put("max_tokens", 128)
            putJsonArray("messages") { add(userMessage("What is the weather in Paris? Use the tool.")) }
            putJsonArray("tools") { add(weatherToolAnthropic) }
        }
        val r = client.postJson("/v1/messages", body, anthropic = true)
        if (r.status !in 200..299) return@check CheckResult("anthropic-tools", "Tool use (Anthropic format)", "fail", httpDetail(r), r.latencyMs)
        val blocks = r.json?.jsonObject?.get("content") as? JsonArray
        val used = blocks?.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use" } == true
        if (used) CheckResult("anthropic-tools", "Tool use (Anthropic format)", "pass", "Model produced a tool_use block", r.latencyMs)
        else CheckResult("anthropic-tools", "Tool use (Anthropic format)", "warn", "No tool_use block returned", r.latencyMs)
    }

    check("metrics", "Prometheus metrics (GET /metrics)") {
        val t0 = System.nanoTime()
        val text = client.fetchMetricsText()
        val latency = (System.nanoTime() - t0) / 1e6
        if (text == null) {
            CheckResult("metrics", "Prometheus metrics (GET /metrics)", "warn", "Not reachable. Benchmarks still work but server-side cache and queue stats will be missing.", latency)
        } else {
            val snap = parseServerSnapshot(text)
            val found = listOfNotNull(
                "running".takeIf { snap.running != null }, "waiting".takeIf { snap.waiting != null },
                "KV cache".takeIf { snap.kvCacheUsage != null }, "prefix cache".takeIf { snap.prefixCacheQueries != null },
                "generation tokens".takeIf { snap.generationTokensTotal != null },
            )
            if (found.isEmpty()) CheckResult("metrics", "Prometheus metrics (GET /metrics)", "warn", "Endpoint responds but no vllm: metrics were found", latency)
            else CheckResult("metrics", "Prometheus metrics (GET /metrics)", "pass", "Available: ${found.joinToString(", ")}", latency)
        }
    }

    return results
}

private fun httpDetail(r: RawResponse): String {
    val message = (r.json as? JsonObject)?.let { o ->
        (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: o["message"]?.jsonPrimitive?.contentOrNull
            ?: o["detail"]?.toString()
    } ?: r.text
    return "HTTP ${r.status}: ${message.take(240)}"
}


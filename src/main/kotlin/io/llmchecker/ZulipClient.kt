package io.llmchecker

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

class ZulipClient(private val config: ZulipConfig) {
    suspend fun send(channel: String, topic: String, content: String) {
        require(config.configured) { "Set ZULIP_SITE, ZULIP_EMAIL, and ZULIP_API_KEY" }
        val token = Base64.getEncoder().encodeToString("${config.email}:${config.apiKey}".toByteArray(Charsets.UTF_8))
        val body = listOf(
            "type" to "stream",
            "to" to channel,
            "topic" to topic,
            "content" to content,
        ).joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
        }.use { http ->
            val response = http.post("${config.site}/api/v1/messages") {
                header(HttpHeaders.Authorization, "Basic $token")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            val text = response.bodyAsText()
            val msg = runCatching { AppJson.parseToJsonElement(text).jsonObject["msg"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val result = runCatching { AppJson.parseToJsonElement(text).jsonObject["result"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (!response.status.isSuccess() || result != "success") {
                throw UpstreamException("Zulip: ${msg ?: response.status.description}", response.status.value)
            }
        }
    }
}

private fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

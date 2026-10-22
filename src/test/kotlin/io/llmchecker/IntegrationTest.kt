package io.llmchecker

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntegrationTest {
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var port = 0
    private lateinit var settings: Settings

    @BeforeTest
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        val config = AppConfig(host = "127.0.0.1", port = port, dataDir = Files.createTempDirectory("llmchecker"), mockEnabled = true, mockSpeed = 30.0)
        settings = config.connection()
        server = embeddedServer(Netty, port = port, host = "127.0.0.1") { module(config) }.start(wait = false)
    }

    @AfterTest
    fun stop() = server.stop(100, 500)

    @Test
    fun listsModelsFromServer() = runBlocking {
        VllmClient(settings).use { client ->
            val (models, _) = client.listModels()
            assertEquals(listOf("mock-model", "mock-coder-32b"), models.map { it.id })
            assertEquals(131072L, models.first().maxModelLen)
        }
    }

    @Test
    fun streamsAndMeasuresBothProtocols() = runBlocking {
        VllmClient(settings).use { client ->
            for (protocol in listOf("openai", "anthropic")) {
                val outcome = client.stream(StreamRequest(protocol, "mock-model", "hello world ".repeat(50), 40, true, 0.0), System.nanoTime(), 0)
                val r = outcome.result
                assertTrue(r.ok, "$protocol failed: ${r.error}")
                assertEquals(40, r.completionTokens)
                assertTrue(!r.tokensEstimated)
                assertNotNull(r.ttftMs)
                assertNotNull(r.decodeTps)
                assertTrue(r.decodeTps > 100, "mock runs at 30x speed, got ${r.decodeTps}")
                assertEquals(39, outcome.gapsMs.size)
            }
        }
    }

    @Test
    fun reportsHttpErrorsInsteadOfThrowing() = runBlocking {
        VllmClient(Settings(baseUrl = "http://127.0.0.1:$port/does-not-exist")).use { client ->
            val outcome = client.stream(StreamRequest("openai", "m", "hi", 4, false, 0.0), System.nanoTime(), 0)
            assertTrue(!outcome.result.ok)
            assertEquals(404, outcome.result.httpStatus)
        }
    }

    @Test
    fun monitorProbesStoreSamplesAndFeedTheDashboard() = runBlocking {
        val dir = Files.createTempDirectory("monitor")
        val monitor = MonitorService(this, settings, dir)
        monitor.update(MonitorConfig(enabled = false, models = listOf("mock-model", "mock-coder-32b"), promptTokens = 200, outputTokens = 20))

        val taken = monitor.probeNow()
        assertEquals(2, taken.size)
        assertTrue(taken.all { it.ok && it.completionTokens == 20 && it.decodeTps != null })

        val dashboard = computeDashboard(monitor.samples(0, null), 60)
        assertEquals(listOf("mock-coder-32b", "mock-model"), dashboard.models.map { it.model })
        assertTrue(dashboard.models.all { (it.probe?.avgTps ?: 0.0) > 0 })

        val reloaded = MonitorService(this, settings, dir)
        assertEquals(2, reloaded.samples(0, null).size)
        assertEquals(listOf("mock-model", "mock-coder-32b"), reloaded.status().config.models)
    }

    @Test
    fun savesAlarmsAndRefusesATestPostWithoutZulip() {
        val http = java.net.http.HttpClient.newHttpClient()
        val base = "http://127.0.0.1:$port"
        fun send(path: String, method: String, body: String? = null): java.net.http.HttpResponse<String> {
            val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
            if (body != null) builder.header("content-type", "application/json")
            if (method == "GET") builder.GET() else builder.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body))
            return http.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        }
        val saved = send(
            "/api/alarms",
            "PUT",
            """{"alarms":[{"model":"mock-model","metric":"decodeTps","direction":"drop","threshold":10,"unit":"percent","baseline":"previous","channel":"ops","topic":"speed"}]}""",
        )
        assertEquals(200, saved.statusCode(), saved.body())
        assertTrue(saved.body().contains("\"channel\":\"ops\""))
        assertTrue(saved.body().contains("\"zulipConfigured\":false"))
        val listed = send("/api/alarms", "GET")
        assertEquals(200, listed.statusCode())
        assertTrue(listed.body().contains("ops"))
        val test = send("/api/alarms/test", "POST", """{"channel":"ops","topic":"speed"}""")
        assertEquals(400, test.statusCode(), test.body())
    }
}

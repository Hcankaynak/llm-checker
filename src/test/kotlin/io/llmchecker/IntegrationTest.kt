package io.llmchecker

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.delay
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
        settings = Settings(baseUrl = config.mockBaseUrl)
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
    fun compatibilityChecksAllPassAgainstMock() = runBlocking {
        val results = VllmClient(settings).use { runCompatibilityChecks(it, "mock-model") }
        val failing = results.filter { it.status != "pass" }
        assertTrue(failing.isEmpty(), "unexpected: $failing")
    }

    @Test
    fun benchmarkRunProducesStagesAndCachingSignal() = runBlocking {
        val dir = Files.createTempDirectory("runs")
        val manager = BenchmarkManager(this, RunStore(dir), SettingsStore(dir.resolve("s.json"), settings))
        val started = manager.start(
            BenchmarkRequest(
                model = "mock-model", promptTokens = 3000, sharedPrefixTokens = 2500, outputTokens = 20,
                concurrencyLevels = listOf(1, 3), requestsPerLevel = 3,
            ),
        )
        var run = manager.get(started.id)!!
        repeat(200) {
            if (run.status != "running") return@repeat
            delay(100)
            run = manager.get(started.id)!!
        }
        assertEquals("completed", run.status, run.error)
        assertEquals(2, run.stages.size)
        assertTrue(run.stages.all { it.completed == it.requested && it.failed == 0 })
        val cold = run.coldProbe!!
        val warm = run.stages[0].ttft!!.p50
        assertTrue(cold.ttftMs!! > warm, "cold ${cold.ttftMs} should exceed warm $warm")
        assertNotNull(run.stages[0].server?.generationTps)
        assertNotNull(run.summary)
        assertTrue(runToCsv(run).lines().size > 6)
    }

    @Test
    fun rejectsConcurrentRuns() = runBlocking {
        val dir = Files.createTempDirectory("runs2")
        val manager = BenchmarkManager(this, RunStore(dir), SettingsStore(dir.resolve("s.json"), settings))
        val slow = BenchmarkRequest(model = "mock-model", promptTokens = 200, outputTokens = 200, concurrencyLevels = listOf(1), requestsPerLevel = 3)
        val first = manager.start(slow)
        val failure = runCatching { manager.start(slow) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        manager.cancel(first.id)
        delay(300)
        assertEquals("cancelled", manager.get(first.id)!!.status)
    }

    @Test
    fun monitorProbesStoreSamplesAndFeedTheDashboard() = runBlocking {
        val dir = Files.createTempDirectory("monitor")
        val store = SettingsStore(dir.resolve("s.json"), settings)
        val manager = BenchmarkManager(this, RunStore(dir.resolve("runs")), store)
        val monitor = MonitorService(this, store, manager, dir)
        monitor.update(MonitorConfig(enabled = false, models = listOf("mock-model", "mock-coder-32b"), promptTokens = 200, outputTokens = 20))

        val taken = monitor.probeNow()
        assertEquals(2, taken.size)
        assertTrue(taken.all { it.ok && it.completionTokens == 20 && it.decodeTps != null })

        val dashboard = computeDashboard(emptyList(), monitor.samples(0, null), 60)
        assertEquals(listOf("mock-coder-32b", "mock-model"), dashboard.models.map { it.model })
        assertTrue(dashboard.models.all { (it.probe?.avgTps ?: 0.0) > 0 })

        val reloaded = MonitorService(this, store, manager, dir)
        assertEquals(2, reloaded.samples(0, null).size)
        assertEquals(listOf("mock-model", "mock-coder-32b"), reloaded.status().config.models)
    }
}

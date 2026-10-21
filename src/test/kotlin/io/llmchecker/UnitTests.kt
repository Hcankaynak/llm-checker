package io.llmchecker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitTests {
    @Test
    fun percentilesInterpolate() {
        val d = distOf((1..100).map { it.toDouble() })!!
        assertEquals(100, d.count)
        assertEquals(50.5, d.p50, 1e-9)
        assertEquals(95.05, d.p95, 1e-9)
        assertEquals(1.0, d.min)
        assertEquals(100.0, d.max)
        assertNull(distOf(emptyList()))
    }

    @Test
    fun baseUrlIsNormalized() {
        assertEquals("http://h:8000", normalizeBaseUrl("http://h:8000/"))
        assertEquals("http://h:8000", normalizeBaseUrl("http://h:8000/v1"))
        assertEquals("http://h:8000", normalizeBaseUrl(" http://h:8000/v1/chat/completions "))
        assertEquals("https://gw/vllm", normalizeBaseUrl("https://gw/vllm/v1/"))
    }

    @Test
    fun connectionComesFromTheEnvironment() {
        val config = AppConfig.fromEnv(
            mapOf(
                "VLLM_BASE_URL" to "http://vllm:8000",
                "VLLM_API_KEY" to "secret",
                "VLLM_SKIP_TLS_VERIFY" to "true",
                "VLLM_TIMEOUT_SEC" to "30",
            ),
        )
        val settings = config.connection()
        assertEquals("http://vllm:8000", settings.baseUrl)
        assertEquals("secret", settings.apiKey)
        assertTrue(settings.skipTlsVerify)
        assertEquals(30, settings.requestTimeoutSec)
    }

    @Test
    fun mockIsUsedWhenNoBaseUrlIsSet() {
        val settings = AppConfig.fromEnv(mapOf("MOCK_ENABLED" to "true", "PORT" to "18642")).connection()
        assertEquals("http://127.0.0.1:18642/mock", settings.baseUrl)
    }

    @Test
    fun promptsShareAPrefixOnlyWhenRequested() {
        val shared = PromptBuilder(1.3, 10, "abc", sharedPrefixTokens = 500, totalPromptTokens = 800, outputTokens = 64, seed = 7)
        val a = shared.build(1)
        val b = shared.build(2)
        val common = a.commonPrefixWith(b).length
        assertTrue(common > 1500, "shared prefix should be identical, was $common chars")
        assertTrue(a != b)

        val unshared = PromptBuilder(1.3, 10, "abc", sharedPrefixTokens = 0, totalPromptTokens = 800, outputTokens = 64, seed = 7)
        assertTrue(unshared.build(1).commonPrefixWith(unshared.build(2)).length < 40)
    }
}

class DashboardTests {
    private fun sample(ts: Long, model: String, tps: Double?, total: Double, ok: Boolean = true) =
        MonitorSample(ts, model, "openai", ok, if (ok) null else "boom", ttftMs = 100.0, totalMs = total, decodeTps = tps, completionTokens = 64, promptTokens = 200)

    @Test
    fun probeStatsAverageOnlySuccessfulSamples() {
        val stats = computeProbeStats(
            listOf(
                sample(1, "m", 40.0, 1000.0),
                sample(2, "m", 60.0, 2000.0),
                sample(3, "m", null, 50.0, ok = false),
            ),
        )!!
        assertEquals(3, stats.samples)
        assertEquals(1, stats.failures)
        assertEquals(50.0, stats.avgTps!!, 1e-9)
        assertEquals(1500.0, stats.avgResponseMs!!, 1e-9)
        assertEquals(60.0, stats.lastTps!!, 1e-9)
        assertEquals("boom", stats.lastError)
        assertEquals(2.0 / 3.0, stats.successRate, 1e-9)
    }

    @Test
    fun dashboardGroupsPerModelAndRespectsWindow() {
        val now = 100_000_000L
        val samples = listOf(
            sample(now - 10 * 60_000, "a", 30.0, 1000.0),
            sample(now - 5 * 60_000, "a", 50.0, 3000.0),
            sample(now - 5 * 60_000, "b", 80.0, 500.0),
            sample(now - 3 * 3_600_000, "a", 5.0, 9000.0),
        )
        val within = computeDashboard(samples, windowMinutes = 60, now = now)
        assertEquals(listOf("a", "b"), within.models.map { it.model })
        assertEquals(40.0, within.models[0].probe!!.avgTps!!, 1e-9)
        assertEquals(3, within.totalProbes)

        val all = computeDashboard(samples, windowMinutes = 0, now = now)
        assertEquals(4, all.totalProbes)
    }
}

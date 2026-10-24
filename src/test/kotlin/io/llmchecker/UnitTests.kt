package io.llmchecker

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun zulipComesFromTheEnvironment() {
        val zulip = AppConfig.fromEnv(
            mapOf(
                "ZULIP_SITE" to "https://chat.example.com/",
                "ZULIP_EMAIL" to "bot@example.com",
                "ZULIP_API_KEY" to "secret",
            ),
        ).zulip()
        assertEquals("https://chat.example.com", zulip.site)
        assertEquals("bot@example.com", zulip.email)
        assertEquals("secret", zulip.apiKey)
        assertTrue(zulip.configured)
        assertTrue(!AppConfig.fromEnv(emptyMap()).zulip().configured)
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

class AlarmTests {
    private fun sample(ts: Long, tps: Double? = 40.0, tokens: Int? = 64, ok: Boolean = true) =
        MonitorSample(ts, "m", "openai", ok, if (ok) null else "boom", ttftMs = 80.0, totalMs = 1000.0, promptTokens = 200, completionTokens = tokens, decodeTps = tps)

    private fun speed(baseline: String, threshold: Double = 10.0, direction: String = "drop", unit: String = "percent", window: Int = 0) =
        AlarmRule(id = "a", model = "m", metric = "decodeTps", direction = direction, threshold = threshold, unit = unit, baseline = baseline, windowMinutes = window, channel = "ops", topic = "speed")

    @Test
    fun previousCheckTripsOnALargeEnoughDrop() {
        val older = sample(1, tps = 100.0)
        val current = sample(2, tps = 80.0)
        val trip = judgeAlarm(listOf(older, current), current, speed("previous"))
        assertTrue(trip is AlarmVerdict.Trip)
        assertTrue(trip.message.contains("100 → 80"))
        assertTrue(trip.message.contains("the previous check"))
        assertTrue(judgeAlarm(listOf(older, sample(2, tps = 95.0)), sample(2, tps = 95.0), speed("previous")) is AlarmVerdict.Clear)
        assertTrue(judgeAlarm(listOf(current), current, speed("previous")) is AlarmVerdict.Waiting)
    }

    @Test
    fun windowAverageIgnoresTheCurrentCheck() {
        val current = sample(3, tps = 50.0)
        val history = listOf(sample(1, tps = 100.0), sample(2, tps = 100.0), current)
        val verdict = judgeAlarm(history, current, speed("window"))
        assertTrue(verdict is AlarmVerdict.Trip)
        assertTrue(verdict.message.contains("100 → 50"))
        assertTrue(verdict.message.contains("all-time average"))
    }

    @Test
    fun lastFiveComparesTheNewestGroupWithTheFiveBefore() {
        val slow = (1..5).map { sample(it.toLong(), tps = 100.0) }
        val fast = (6..10).map { sample(it.toLong(), tps = 80.0) }
        val current = fast.last()
        val verdict = judgeAlarm(slow + fast, current, speed("last5"))
        assertTrue(verdict is AlarmVerdict.Trip, verdict.toString())
        assertTrue(verdict.message.contains("100 → 80"))
        assertTrue(verdict.message.contains("5 checks before the latest 5"))
        assertTrue(judgeAlarm(slow + fast.take(4), fast[3], speed("last5")) is AlarmVerdict.Waiting)
    }

    @Test
    fun outputTokensUseAnAbsoluteRise() {
        val older = sample(1, tokens = 64)
        val current = sample(2, tokens = 70)
        val rule = AlarmRule(id = "t", metric = "completionTokens", direction = "rise", threshold = 5.0, unit = "absolute", baseline = "previous", channel = "ops", topic = "tokens")
        val verdict = judgeAlarm(listOf(older, current), current, rule)
        assertTrue(verdict is AlarmVerdict.Trip)
        assertTrue(verdict.message.contains("64 → 70"))
        val small = sample(2, tokens = 68)
        assertTrue(judgeAlarm(listOf(older, small), small, rule) is AlarmVerdict.Clear)
    }

    @Test
    fun failureAlarmUsesTheNewestCheckOnly() {
        val rule = AlarmRule(id = "f", metric = "failure", channel = "ops", topic = "down")
        val failed = sample(2, ok = false)
        val verdict = judgeAlarm(listOf(sample(1), failed), failed, rule)
        assertTrue(verdict is AlarmVerdict.Trip)
        assertTrue(verdict.message.contains("boom"))
        assertTrue(judgeAlarm(listOf(sample(1), sample(2)), sample(2), rule) is AlarmVerdict.Clear)
    }

    @Test
    fun alarmPostsOnceUntilTheModelRecovers() = runBlocking {
        val stored = mutableListOf<MonitorSample>()
        val sent = mutableListOf<String>()
        val service = AlarmService(
            ZulipConfig("https://chat.example", "bot@example.com", "key"),
            Files.createTempDirectory("alarms"),
            { stored.toList() },
        ) { _, _, content -> sent += content }
        service.replace(listOf(speed("previous")))
        suspend fun check(ts: Long, tps: Double) {
            val sample = sample(ts, tps)
            stored += sample
            service.onSample(sample)
        }
        check(1, 100.0)
        check(2, 80.0)
        check(3, 70.0)
        assertEquals(1, sent.size)
        assertTrue(service.view().states.single().firing)
        check(4, 100.0)
        assertTrue(!service.view().states.single().firing)
        check(5, 80.0)
        assertEquals(2, sent.size)
    }

    @Test
    fun unconfiguredZulipDoesNotPost() = runBlocking {
        val stored = mutableListOf<MonitorSample>()
        var calls = 0
        val service = AlarmService(ZulipConfig(), Files.createTempDirectory("alarms"), { stored.toList() }) { _, _, _ -> calls++ }
        service.replace(listOf(speed("previous")))
        val first = sample(1, tps = 100.0)
        val second = sample(2, tps = 50.0)
        stored += first
        service.onSample(first)
        stored += second
        service.onSample(second)
        assertEquals(0, calls)
        assertEquals("Zulip is not configured", service.view().states.single().lastError)
    }

    @Test
    fun logsProbeRunFailures() = runBlocking {
        val logger = LoggerFactory.getLogger(MonitorService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val monitor = MonitorService(this, Settings(baseUrl = ""), Files.createTempDirectory("monitor-log"))
        try {
            monitor.update(MonitorConfig(enabled = true, intervalSec = 30, models = listOf("m"), promptTokens = 16, outputTokens = 1))
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && appender.list.none { it.message == "Probe run failed" }) {
                delay(20)
            }
            val event = appender.list.find { it.message == "Probe run failed" }
            assertNotNull(event)
            assertNotNull(event.throwableProxy)
            assertTrue(event.throwableProxy!!.message.orEmpty().contains("No vLLM base URL"))
        } finally {
            monitor.update(MonitorConfig(enabled = false))
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}

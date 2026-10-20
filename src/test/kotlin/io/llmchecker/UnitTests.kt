package io.llmchecker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun prometheusSnapshotSumsLabelsAndSupportsOldNames() {
        val text = """
            # HELP vllm:num_requests_running running
            vllm:num_requests_running{engine="0",model_name="m"} 3.0
            vllm:num_requests_running{engine="1",model_name="m"} 2.0
            vllm:num_requests_waiting{model_name="m"} 1.0
            vllm:gpu_cache_usage_perc{model_name="m"} 0.42
            vllm:generation_tokens_total{model_name="m"} 1.5e+03
            vllm:gpu_prefix_cache_hits_total{model_name="m"} 50
            vllm:gpu_prefix_cache_queries_total{model_name="m"} 200
            vllm:generation_tokens_created{model_name="m"} 1.7e9
        """.trimIndent()
        val s = parseServerSnapshot(text)
        assertEquals(5.0, s.running)
        assertEquals(1.0, s.waiting)
        assertEquals(0.42, s.kvCacheUsage)
        assertEquals(1500.0, s.generationTokensTotal)
        assertEquals(50.0, s.prefixCacheHits)
        assertEquals(200.0, s.prefixCacheQueries)
        assertNull(s.preemptionsTotal)
    }

    @Test
    fun benchmarkRequestValidation() {
        BenchmarkRequest(model = "m").validate()
        assertFailsWith<IllegalArgumentException> { BenchmarkRequest(model = "").validate() }
        assertFailsWith<IllegalArgumentException> { BenchmarkRequest(model = "m", promptTokens = 100, sharedPrefixTokens = 200).validate() }
        assertFailsWith<IllegalArgumentException> { BenchmarkRequest(model = "m", concurrencyLevels = listOf(0)).validate() }
        assertFailsWith<IllegalArgumentException> { BenchmarkRequest(model = "m", protocol = "grpc").validate() }
        assertFailsWith<IllegalArgumentException> {
            BenchmarkRequest(model = "m", concurrencyLevels = listOf(1, 2, 4, 8), requestsPerLevel = 2000).validate()
        }
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

    @Test
    fun stageAggregatesIgnoreFailures() {
        fun ok(i: Int, tokens: Int) = StreamOutcome(
            RequestResult(i, true, ttftMs = 100.0 + i, totalMs = 1000.0, promptTokens = 50, completionTokens = tokens, decodeTps = 40.0, tpotMs = 25.0),
            doubleArrayOf(20.0, 30.0),
        )
        val bad = StreamOutcome(RequestResult(9, false, error = "HTTP 500"), DoubleArray(0))
        val stage = buildStage(2, 3, 2.0, listOf(ok(0, 100), ok(1, 100), bad), null)
        assertEquals(2, stage.completed)
        assertEquals(1, stage.failed)
        assertEquals(100.0, stage.outputTps, 1e-9)
        assertEquals(50.0, stage.inputTps, 1e-9)
        assertNotNull(stage.itl)
        assertEquals(4, stage.itl.count)
        assertEquals(mapOf("HTTP 500" to 1), stage.errors)
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
        val within = computeDashboard(emptyList(), samples, windowMinutes = 60, now = now)
        assertEquals(listOf("a", "b"), within.models.map { it.model })
        assertEquals(40.0, within.models[0].probe!!.avgTps!!, 1e-9)
        assertEquals(3, within.totalProbes)

        val all = computeDashboard(emptyList(), samples, windowMinutes = 0, now = now)
        assertEquals(4, all.totalProbes)
    }

    @Test
    fun benchStatsUseLowestConcurrencyStage() {
        fun req(i: Int, tps: Double) = RequestResult(i, true, ttftMs = 200.0, totalMs = 2000.0, decodeTps = tps, completionTokens = 10)
        fun stage(c: Int, tps: Double, out: Double) = StageResult(
            c, 2, 2, 0, 1.0, out, 0.0, 0.0, requests = listOf(req(0, tps), req(1, tps)),
        )
        val run = RunState(
            id = "r1", status = "completed", phase = "done", request = BenchmarkRequest(model = "m"), baseUrl = "x",
            createdAt = 1, finishedAt = 2, stages = listOf(stage(4, 10.0, 300.0), stage(1, 50.0, 90.0)),
        )
        val stats = computeBenchStats(listOf(run))!!
        assertEquals(50.0, stats.avgTps!!, 1e-9)
        assertEquals(300.0, stats.peakOutputTps!!, 1e-9)
        assertEquals(2, stats.requests)
    }
}

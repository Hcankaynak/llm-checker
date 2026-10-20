package io.llmchecker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BenchmarkManager(
    private val scope: CoroutineScope,
    private val store: RunStore,
    private val settings: SettingsStore,
) {
    private val log = LoggerFactory.getLogger(BenchmarkManager::class.java)
    private val live = ConcurrentHashMap<String, AtomicReference<RunState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    @Synchronized
    fun start(request: BenchmarkRequest): RunState {
        request.validate()
        if (jobs.values.any { it.isActive }) throw IllegalStateException("Another benchmark is already running. Wait for it or cancel it first.")
        val current = settings.get()
        val client = VllmClient(current)
        val id = UUID.randomUUID().toString().substring(0, 8)
        val state = RunState(
            id = id, status = "running", phase = "starting", request = request, baseUrl = client.root,
            createdAt = System.currentTimeMillis(), startedAt = System.currentTimeMillis(),
        )
        val ref = AtomicReference(state)
        live[id] = ref
        jobs[id] = scope.launch(Dispatchers.Default) {
            try {
                client.use { execute(it, request, ref) }
                ref.updateAndGet { it.copy(status = "completed", phase = "done", currentStage = null, finishedAt = System.currentTimeMillis(), summary = summarize(it)) }
            } catch (e: CancellationException) {
                ref.updateAndGet { it.copy(status = "cancelled", phase = "cancelled", currentStage = null, finishedAt = System.currentTimeMillis(), summary = summarize(it)) }
            } catch (e: Exception) {
                log.warn("Run {} failed", id, e)
                ref.updateAndGet { it.copy(status = "failed", phase = "failed", error = describe(e), currentStage = null, finishedAt = System.currentTimeMillis(), summary = summarize(it)) }
            } finally {
                withContext(NonCancellable) {
                    runCatching { store.save(ref.get()) }.onFailure { log.warn("Could not persist run {}", id, it) }
                    live.remove(id)
                    jobs.remove(id)
                }
            }
        }
        return state
    }

    fun isBusy(): Boolean = jobs.values.any { it.isActive }

    fun get(id: String): RunState? = live[id]?.get() ?: store.get(id)

    fun list(): List<RunState> {
        val liveRuns = live.values.map { it.get() }
        val liveIds = liveRuns.map { it.id }.toSet()
        return (liveRuns + store.list().filter { it.id !in liveIds }).sortedByDescending { it.createdAt }
    }

    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        job.cancel()
        return true
    }

    fun delete(id: String): Boolean {
        cancel(id)
        return store.delete(id)
    }

    private fun summarize(run: RunState): RunSummary? {
        val stages = run.stages.filter { it.completed > 0 }
        if (stages.isEmpty()) return null
        val best = stages.maxBy { it.outputTps }
        val single = stages.firstOrNull { it.concurrency == 1 }
        val ref = single ?: stages.first()
        return RunSummary(
            peakOutputTps = best.outputTps,
            peakOutputTpsConcurrency = best.concurrency,
            singleStreamTps = single?.perRequestTps?.p50,
            ttftP50Ms = ref.ttft?.p50,
            ttftP95Ms = ref.ttft?.p95,
            totalRequests = run.stages.sumOf { it.requested },
            failedRequests = run.stages.sumOf { it.failed } + (if (run.coldProbe?.ok == false) 1 else 0),
        )
    }

    private suspend fun execute(client: VllmClient, req: BenchmarkRequest, ref: AtomicReference<RunState>) {
        fun phase(p: String) = ref.updateAndGet { it.copy(phase = p) }

        phase("calibrating")
        val nonce = UUID.randomUUID().toString().substring(0, 8)
        val seed = nonce.hashCode().toLong()
        val clockStart = System.nanoTime()
        val calibration = calibrate(client, req, nonce, clockStart)
        ref.updateAndGet { it.copy(calibration = calibration) }

        val builder = PromptBuilder(
            tokensPerWord = calibration.tokensPerWord,
            overheadTokens = calibration.overheadTokens,
            runNonce = nonce,
            sharedPrefixTokens = req.sharedPrefixTokens,
            totalPromptTokens = req.promptTokens,
            outputTokens = req.outputTokens,
            seed = seed,
        )
        val counter = AtomicInteger(1_000_000)

        if (req.sharedPrefixTokens > 0) {
            phase("cold prefix probe")
            val outcome = client.stream(streamRequest(req, builder.build(counter.getAndIncrement())), System.nanoTime(), 0)
            ref.updateAndGet { it.copy(coldProbe = outcome.result) }
            if (!outcome.result.ok) throw IllegalStateException("First request failed: ${outcome.result.error}")
        } else if (req.warmupRequests > 0) {
            phase("warming up")
            repeat(req.warmupRequests) {
                val warm = client.stream(
                    streamRequest(req, builder.build(counter.getAndIncrement()), maxTokens = minOf(req.outputTokens, 32)),
                    System.nanoTime(), 0,
                )
                if (!warm.result.ok) throw IllegalStateException("Warm-up request failed: ${warm.result.error}")
            }
        }

        for (level in req.concurrencyLevels) {
            phase("running at concurrency $level")
            val total = maxOf(req.requestsPerLevel, level)
            val stage = runStage(client, req, builder, counter, level, total) { progress ->
                ref.updateAndGet { it.copy(currentStage = progress) }
            }
            ref.updateAndGet { it.copy(stages = it.stages + stage, currentStage = null) }
        }
    }

    private fun streamRequest(req: BenchmarkRequest, prompt: String, maxTokens: Int = req.outputTokens) = StreamRequest(
        protocol = req.protocol, model = req.model, prompt = prompt, maxTokens = maxTokens,
        ignoreEos = req.ignoreEos, temperature = req.temperature,
    )

    private suspend fun calibrate(client: VllmClient, req: BenchmarkRequest, nonce: String, clockStart: Long): Calibration {
        val small = 100
        val large = 1100
        suspend fun probe(words: Int, seedOffset: Long): Int? {
            val outcome = client.stream(
                StreamRequest(req.protocol, req.model, PromptBuilder.probeText(words, "$nonce-$words", seedOffset), 1, false, 0.0),
                clockStart, 0,
            )
            if (!outcome.result.ok && outcome.result.httpStatus != null && outcome.result.httpStatus!! in 400..499) {
                throw IllegalStateException("Model rejected the request: ${outcome.result.error}")
            }
            return outcome.result.promptTokens
        }
        val a = probe(small, 11)
        val b = probe(large, 13)
        if (a != null && b != null && b > a) {
            val tpw = ((b - a).toDouble() / (large - small)).coerceIn(0.5, 4.0)
            val overhead = (a - small * tpw).toInt().coerceAtLeast(0)
            return Calibration(tpw, overhead, fromServer = true)
        }
        return Calibration(1.3, 16, fromServer = false)
    }

    private suspend fun runStage(
        client: VllmClient,
        req: BenchmarkRequest,
        builder: PromptBuilder,
        counter: AtomicInteger,
        concurrency: Int,
        total: Int,
        onProgress: (StageProgress) -> Unit,
    ): StageResult = coroutineScope {
        val next = AtomicInteger(0)
        val outcomes = java.util.Collections.synchronizedList(ArrayList<StreamOutcome>())
        val monitor = ServerMonitor(client)
        monitor.begin()
        val sampler = launch { monitor.sampleLoop() }
        val stageStart = System.nanoTime()

        try {
            val workers = (1..concurrency).map {
                launch {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= total) break
                        val prompt = builder.build(counter.getAndIncrement())
                        val outcome = client.stream(streamRequest(req, prompt), stageStart, i)
                        outcomes.add(outcome)
                        val snapshot = synchronized(outcomes) { outcomes.toList() }
                        val elapsed = (System.nanoTime() - stageStart) / 1e9
                        val tokens = snapshot.sumOf { it.result.completionTokens ?: 0 }
                        onProgress(
                            StageProgress(
                                concurrency, snapshot.size, total, snapshot.count { !it.result.ok },
                                if (elapsed > 0) tokens / elapsed else 0.0,
                            ),
                        )
                    }
                }
            }
            workers.forEach { it.join() }
        } finally {
            sampler.cancel()
        }

        val wallSec = (System.nanoTime() - stageStart) / 1e9
        val server = withContext(NonCancellable) { monitor.finish(wallSec) }
        buildStage(concurrency, total, wallSec, outcomes.toList(), server)
    }
}

class ServerMonitor(private val client: VllmClient) {
    private var before: ServerSnapshot? = null
    private var peakRunning: Double? = null
    private var peakWaiting: Double? = null
    private var peakKv: Double? = null
    private var consecutiveFailures = 0

    suspend fun begin() {
        before = client.serverSnapshot()
        observe(before)
    }

    suspend fun sampleLoop() {
        if (before == null) return
        while (true) {
            delay(1000)
            val snap = client.serverSnapshot()
            if (snap == null) {
                if (++consecutiveFailures >= 3) return
            } else {
                consecutiveFailures = 0
                observe(snap)
            }
        }
    }

    @Synchronized
    private fun observe(s: ServerSnapshot?) {
        if (s == null) return
        s.running?.let { peakRunning = maxOf(peakRunning ?: it, it) }
        s.waiting?.let { peakWaiting = maxOf(peakWaiting ?: it, it) }
        s.kvCacheUsage?.let { peakKv = maxOf(peakKv ?: it, it) }
    }

    suspend fun finish(wallSec: Double): ServerStageStats? {
        val b = before ?: return null
        val after = client.serverSnapshot() ?: return null
        observe(after)
        fun delta(x: Double?, y: Double?) = if (x != null && y != null) (y - x).coerceAtLeast(0.0) else null
        val gen = delta(b.generationTokensTotal, after.generationTokensTotal)
        val hits = delta(b.prefixCacheHits, after.prefixCacheHits)
        val queries = delta(b.prefixCacheQueries, after.prefixCacheQueries)
        return synchronized(this) {
            ServerStageStats(
                generationTps = gen?.let { it / wallSec },
                prefixCacheHitRate = if (hits != null && queries != null && queries > 0) hits / queries else null,
                peakRunning = peakRunning,
                peakWaiting = peakWaiting,
                peakKvCacheUsage = peakKv,
                preemptions = delta(b.preemptionsTotal, after.preemptionsTotal),
            )
        }
    }
}

fun buildStage(concurrency: Int, requested: Int, wallSec: Double, outcomes: List<StreamOutcome>, server: ServerStageStats?): StageResult {
    val ok = outcomes.filter { it.result.ok }.map { it.result }
    val failed = outcomes.size - ok.size
    val completionTotal = ok.sumOf { it.completionTokens ?: 0 }
    val promptTotal = ok.sumOf { it.promptTokens ?: 0 }
    val errors = outcomes.filter { !it.result.ok }
        .groupingBy { it.result.error?.take(160) ?: "unknown error" }.eachCount()
        .entries.sortedByDescending { it.value }.take(5).associate { it.key to it.value }
    return StageResult(
        concurrency = concurrency,
        requested = requested,
        completed = ok.size,
        failed = failed,
        wallSec = wallSec,
        outputTps = if (wallSec > 0) completionTotal / wallSec else 0.0,
        inputTps = if (wallSec > 0) promptTotal / wallSec else 0.0,
        requestsPerSec = if (wallSec > 0) ok.size / wallSec else 0.0,
        ttft = distOf(ok.mapNotNull { it.ttftMs }),
        tpot = distOf(ok.mapNotNull { it.tpotMs }),
        itl = distOf(outcomes.filter { it.result.ok }.flatMap { it.gapsMs.asList() }),
        e2e = distOf(ok.map { it.totalMs }),
        perRequestTps = distOf(ok.mapNotNull { it.decodeTps }),
        avgPromptTokens = ok.mapNotNull { it.promptTokens }.takeIf { it.isNotEmpty() }?.average(),
        avgCompletionTokens = ok.mapNotNull { it.completionTokens }.takeIf { it.isNotEmpty() }?.average(),
        avgCachedTokens = ok.mapNotNull { it.cachedTokens }.takeIf { it.isNotEmpty() }?.average(),
        server = server,
        errors = errors,
        requests = outcomes.map { it.result }.sortedBy { it.index },
    )
}

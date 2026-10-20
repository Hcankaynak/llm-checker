package io.llmchecker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

@Serializable
data class MonitorConfig(
    val enabled: Boolean = false,
    val intervalSec: Int = 60,
    val models: List<String> = emptyList(),
    val protocol: String = "openai",
    val promptTokens: Int = 256,
    val outputTokens: Int = 64,
) {
    fun validate() {
        require(intervalSec in 5..86_400) { "Interval must be between 5 and 86400 seconds" }
        require(protocol in setOf("openai", "anthropic")) { "protocol must be openai or anthropic" }
        require(promptTokens in 16..32_000) { "promptTokens must be between 16 and 32000" }
        require(outputTokens in 1..2048) { "outputTokens must be between 1 and 2048" }
        require(models.size <= 20) { "At most 20 models can be monitored" }
        require(!enabled || models.isNotEmpty()) { "Select at least one model to monitor" }
    }
}

@Serializable
data class MonitorSample(
    val ts: Long,
    val model: String,
    val protocol: String,
    val ok: Boolean,
    val error: String? = null,
    val ttftMs: Double? = null,
    val totalMs: Double = 0.0,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val decodeTps: Double? = null,
    val e2eTps: Double? = null,
)

@Serializable
data class MonitorStatus(
    val config: MonitorConfig,
    val probing: Boolean,
    val lastRunAt: Long?,
    val nextRunAt: Long?,
    val note: String?,
    val sampleCount: Int,
)

class MonitorService(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val manager: BenchmarkManager,
    private val dir: Path,
    private val maxSamples: Int = 5000,
) {
    private val log = LoggerFactory.getLogger(MonitorService::class.java)
    private val configFile = dir.resolve("monitor.json")
    private val samplesFile = dir.resolve("monitor-samples.jsonl")
    private val samples = ArrayDeque<MonitorSample>()
    private val probeLock = Mutex()
    private var loop: Job? = null

    @Volatile private var config: MonitorConfig = loadConfig()
    @Volatile private var lastRunAt: Long? = null
    @Volatile private var nextRunAt: Long? = null
    @Volatile private var note: String? = null

    init {
        Files.createDirectories(dir)
        loadSamples()
        if (config.enabled) startLoop()
    }

    private fun loadConfig(): MonitorConfig = runCatching {
        if (Files.exists(configFile)) AppJson.decodeFromString<MonitorConfig>(Files.readString(configFile)) else MonitorConfig()
    }.getOrDefault(MonitorConfig())

    private fun loadSamples() {
        if (!Files.exists(samplesFile)) return
        val parsed = Files.readAllLines(samplesFile).mapNotNull { line ->
            runCatching { AppJson.decodeFromString<MonitorSample>(line) }.getOrNull()
        }
        synchronized(samples) {
            parsed.takeLast(maxSamples).forEach { samples.addLast(it) }
        }
        if (parsed.size > maxSamples * 2) rewriteSamples()
    }

    private fun rewriteSamples() {
        val lines = synchronized(samples) { samples.map { AppJson.encodeToString(it) } }
        Files.write(samplesFile, lines)
    }

    private fun record(sample: MonitorSample) {
        synchronized(samples) {
            samples.addLast(sample)
            while (samples.size > maxSamples) samples.removeFirst()
        }
        runCatching {
            Files.writeString(samplesFile, AppJson.encodeToString(sample) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { log.warn("Could not persist monitor sample", it) }
    }

    fun status() = MonitorStatus(config, probeLock.isLocked, lastRunAt, if (config.enabled) nextRunAt else null, note, synchronized(samples) { samples.size })

    @Synchronized
    fun update(next: MonitorConfig): MonitorStatus {
        next.validate()
        config = next
        Files.createDirectories(dir)
        Files.writeString(configFile, AppJson.encodeToString(next))
        loop?.cancel()
        loop = null
        nextRunAt = null
        if (next.enabled) startLoop() else note = null
        return status()
    }

    private fun startLoop() {
        loop = scope.launch {
            delay(1000)
            while (true) {
                val cfg = config
                try {
                    probeAll(cfg, scheduled = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = describe(e)
                }
                nextRunAt = System.currentTimeMillis() + cfg.intervalSec * 1000L
                delay(cfg.intervalSec * 1000L)
            }
        }
    }

    suspend fun probeNow(models: List<String>? = null): List<MonitorSample> {
        val cfg = config.let { if (models.isNullOrEmpty()) it else it.copy(models = models) }
        require(cfg.models.isNotEmpty()) { "Select at least one model to probe" }
        return probeAll(cfg, scheduled = false)
    }

    private suspend fun probeAll(cfg: MonitorConfig, scheduled: Boolean): List<MonitorSample> {
        if (manager.isBusy()) {
            if (!scheduled) throw IllegalStateException("A benchmark is running. Probes are paused so they do not skew its results.")
            note = "Skipped a probe round because a benchmark was running"
            return emptyList()
        }
        if (!probeLock.tryLock()) throw IllegalStateException("A probe is already in progress")
        try {
            val out = mutableListOf<MonitorSample>()
            val client = VllmClient(settings.get())
            client.use {
                for (model in cfg.models) {
                    val sample = probe(it, cfg, model)
                    record(sample)
                    out += sample
                }
            }
            lastRunAt = System.currentTimeMillis()
            note = null
            return out
        } finally {
            probeLock.unlock()
        }
    }

    private suspend fun probe(client: VllmClient, cfg: MonitorConfig, model: String): MonitorSample {
        val nonce = UUID.randomUUID().toString().substring(0, 8)
        val prompt = PromptBuilder(1.3, 16, nonce, 0, cfg.promptTokens, cfg.outputTokens, nonce.hashCode().toLong()).build(0)
        val outcome = client.stream(
            StreamRequest(cfg.protocol, model, prompt, cfg.outputTokens, ignoreEos = true, temperature = 0.0),
            System.nanoTime(), 0,
        )
        val r = outcome.result
        return MonitorSample(
            ts = System.currentTimeMillis(), model = model, protocol = cfg.protocol, ok = r.ok, error = r.error,
            ttftMs = r.ttftMs, totalMs = r.totalMs, promptTokens = r.promptTokens, completionTokens = r.completionTokens,
            decodeTps = r.decodeTps, e2eTps = r.e2eTps,
        )
    }

    fun samples(sinceMs: Long, model: String?, limit: Int = 3000): List<MonitorSample> = synchronized(samples) {
        samples.filter { it.ts >= sinceMs && (model == null || it.model == model) }.takeLast(limit)
    }

    fun clearSamples() {
        synchronized(samples) { samples.clear() }
        Files.deleteIfExists(samplesFile)
    }
}

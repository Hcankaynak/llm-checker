package io.llmchecker

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Serializable
data class AlarmsDocument(val alarms: List<AlarmRule> = emptyList())

@Serializable
data class AlarmFireState(
    val alarmId: String,
    val model: String,
    val firing: Boolean = false,
    val lastSentAt: Long? = null,
    val lastError: String? = null,
)

@Serializable
data class AlarmsView(
    val zulipConfigured: Boolean,
    val alarms: List<AlarmRule>,
    val states: List<AlarmFireState>,
)

class AlarmService(
    private val zulip: ZulipConfig,
    private val dir: Path,
    private val samplesFor: (String) -> List<MonitorSample>,
    private val send: suspend (channel: String, topic: String, content: String) -> Unit = { channel, topic, content ->
        ZulipClient(zulip).send(channel, topic, content)
    },
) {
    private val log = LoggerFactory.getLogger(AlarmService::class.java)
    private val alarmsFile = dir.resolve("alarms.json")
    private val stateFile = dir.resolve("alarm-state.json")
    private val lock = Any()
    private var alarms: List<AlarmRule> = loadAlarms()
    private val states = mutableMapOf<String, AlarmFireState>()

    init {
        Files.createDirectories(dir)
        loadStates().forEach { states[key(it.alarmId, it.model)] = it }
    }

    fun view(): AlarmsView = synchronized(lock) {
        AlarmsView(zulip.configured, alarms, states.values.sortedWith(compareBy({ it.alarmId }, { it.model })))
    }

    fun replace(incoming: List<AlarmRule>): AlarmsView = synchronized(lock) {
        require(incoming.size <= 50) { "At most 50 alarms" }
        val next = incoming.map { rule ->
            rule.copy(
                id = rule.id.ifBlank { UUID.randomUUID().toString() },
                model = rule.model.trim(),
                channel = rule.channel.trim(),
                topic = rule.topic.trim(),
            )
        }
        require(next.map { it.id }.distinct().size == next.size) { "Alarm ids must be unique" }
        next.forEach { it.validate() }
        val oldById = alarms.associateBy { it.id }
        alarms = next
        val ids = next.map { it.id }.toSet()
        states.keys.retainAll { key -> key.substringBefore('\u0000') in ids }
        for (rule in next) {
            val old = oldById[rule.id] ?: continue
            if (!old.sameComparison(rule)) {
                states.keys.filter { it.startsWith("${rule.id}\u0000") }.forEach { k ->
                    states[k] = states.getValue(k).copy(firing = false)
                }
            }
        }
        persist()
        view()
    }

    suspend fun sendTest(channel: String, topic: String) {
        require(channel.isNotBlank() && topic.isNotBlank()) { "Channel and topic are required" }
        require(zulip.configured) { "Set ZULIP_SITE, ZULIP_EMAIL, and ZULIP_API_KEY" }
        send(channel.trim(), topic.trim(), "LLM Checker test. Alarms for this channel and topic will be posted here.")
    }

    suspend fun onSample(sample: MonitorSample) {
        val pending = synchronized(lock) {
            alarms.filter { it.enabled && (it.model.isBlank() || it.model == sample.model) }.map { rule ->
                val verdict = judgeAlarm(samplesFor(sample.model), sample, rule)
                rule to verdict
            }
        }
        for ((rule, verdict) in pending) {
            when (verdict) {
                AlarmVerdict.Waiting -> Unit
                AlarmVerdict.Clear -> synchronized(lock) {
                    val current = states[key(rule.id, sample.model)]
                    if (current?.firing == true) {
                        states[key(rule.id, sample.model)] = current.copy(firing = false)
                        persistState()
                    }
                }
                is AlarmVerdict.Trip -> dispatch(rule, sample.model, verdict.message)
            }
        }
    }

    fun onSamplesCleared() {
        synchronized(lock) {
            if (states.isEmpty()) return
            states.keys.toList().forEach { k -> states[k] = states.getValue(k).copy(firing = false) }
            persistState()
        }
    }

    private suspend fun dispatch(rule: AlarmRule, model: String, message: String) {
        val already = synchronized(lock) { states[key(rule.id, model)]?.firing == true }
        if (already) return
        if (!zulip.configured) {
            remember(rule.id, model, firing = false, error = "Zulip is not configured")
            log.warn("Alarm matched for {} but Zulip is not configured", model)
            return
        }
        try {
            send(rule.channel, rule.topic, message)
            remember(rule.id, model, firing = true, sentAt = System.currentTimeMillis(), error = null)
            log.info("Posted alarm for {} to #{} > {}", model, rule.channel, rule.topic)
        } catch (e: Exception) {
            remember(rule.id, model, firing = false, error = e.message ?: "Zulip request failed")
            log.warn("Could not post alarm for {} to #{}", model, rule.channel, e)
        }
    }

    private fun remember(alarmId: String, model: String, firing: Boolean, sentAt: Long? = null, error: String? = null) {
        synchronized(lock) {
            val previous = states[key(alarmId, model)]
            states[key(alarmId, model)] = AlarmFireState(
                alarmId = alarmId,
                model = model,
                firing = firing,
                lastSentAt = sentAt ?: previous?.lastSentAt,
                lastError = error,
            )
            persistState()
        }
    }

    private fun loadAlarms(): List<AlarmRule> = runCatching {
        if (Files.exists(alarmsFile)) AppJson.decodeFromString<AlarmsDocument>(Files.readString(alarmsFile)).alarms else emptyList()
    }.getOrDefault(emptyList())

    private fun loadStates(): List<AlarmFireState> = runCatching {
        if (Files.exists(stateFile)) AppJson.decodeFromString<List<AlarmFireState>>(Files.readString(stateFile)) else emptyList()
    }.getOrDefault(emptyList())

    private fun persist() {
        Files.createDirectories(dir)
        Files.writeString(alarmsFile, AppJson.encodeToString(AlarmsDocument(alarms)))
        persistState()
    }

    private fun persistState() {
        Files.createDirectories(dir)
        Files.writeString(stateFile, AppJson.encodeToString(states.values.toList()))
    }

    private fun key(alarmId: String, model: String) = "$alarmId\u0000$model"
}

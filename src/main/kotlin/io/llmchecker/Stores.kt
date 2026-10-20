package io.llmchecker

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

class SettingsStore(private val file: Path, defaults: Settings) {
    @Volatile
    private var current: Settings = load() ?: defaults

    private fun load(): Settings? = runCatching {
        if (Files.exists(file)) AppJson.decodeFromString<Settings>(Files.readString(file)) else null
    }.getOrNull()

    fun get(): Settings = current

    @Synchronized
    fun update(settings: Settings) {
        current = settings
        Files.createDirectories(file.parent)
        Files.writeString(file, AppJson.encodeToString(settings))
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }
}

class RunStore(private val dir: Path) {
    private val cache = ConcurrentHashMap<String, RunState>()

    init {
        Files.createDirectories(dir)
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.forEach { path ->
                runCatching { AppJson.decodeFromString<RunState>(Files.readString(path)) }
                    .getOrNull()
                    ?.let { run ->
                        val fixed = if (run.status in setOf("running", "queued")) {
                            run.copy(status = "failed", phase = "interrupted", error = "Server restarted while the run was in progress")
                        } else run
                        cache[fixed.id] = fixed
                    }
            }
        }
    }

    fun save(run: RunState) {
        cache[run.id] = run
        Files.writeString(dir.resolve("${run.id}.json"), AppJson.encodeToString(run))
    }

    fun get(id: String): RunState? = cache[id]

    fun list(): List<RunState> = cache.values.sortedByDescending { it.createdAt }

    fun delete(id: String): Boolean {
        val removed = cache.remove(id) != null
        Files.deleteIfExists(dir.resolve("$id.json"))
        return removed
    }
}

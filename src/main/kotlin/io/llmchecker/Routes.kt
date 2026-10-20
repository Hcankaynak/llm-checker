package io.llmchecker

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val idPattern = Regex("^[a-z0-9-]{4,40}$")

@Serializable
private data class ProbeRequest(val models: List<String>? = null)

@Serializable
private data class Health(val ok: Boolean, val version: String, val mockAvailable: Boolean)

fun Route.staticRoutes() {
    staticResources("/", "static") { default("index.html") }
}

fun Route.apiRoutes(config: AppConfig, settings: SettingsStore, manager: BenchmarkManager, monitor: MonitorService) {
    fun view(s: Settings) = SettingsView(
        baseUrl = s.baseUrl, hasApiKey = !s.apiKey.isNullOrBlank(), skipTlsVerify = s.skipTlsVerify,
        requestTimeoutSec = s.requestTimeoutSec, mockAvailable = config.mockEnabled,
        mockBaseUrl = if (config.mockEnabled) config.mockBaseUrl else null,
    )

    route("/api") {
        get("/health") { call.respond(Health(true, "0.1.0", config.mockEnabled)) }

        get("/settings") { call.respond(view(settings.get())) }

        put("/settings") {
            val update = call.receive<SettingsUpdate>()
            val base = update.baseUrl.trim()
            require(base.isEmpty() || base.startsWith("http://") || base.startsWith("https://")) { "Base URL must start with http:// or https://" }
            require(update.requestTimeoutSec in 5..7200) { "Timeout must be between 5 and 7200 seconds" }
            val old = settings.get()
            val key = when {
                update.clearApiKey -> null
                !update.apiKey.isNullOrBlank() -> update.apiKey.trim()
                else -> old.apiKey
            }
            val next = Settings(base, key, update.skipTlsVerify, update.requestTimeoutSec)
            settings.update(next)
            call.respond(view(next))
        }

        get("/models") {
            val (models, latency) = VllmClient(settings.get()).use { it.listModels() }
            val root = normalizeBaseUrl(settings.get().baseUrl)
            call.respond(ModelsResponse(root, latency, models))
        }

        get("/server-metrics") {
            val snap = VllmClient(settings.get()).use { it.serverSnapshot() }
            if (snap == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("The server does not expose /metrics"))
            else call.respond(snap)
        }

        post("/checks") {
            val req = call.receive<CheckRequest>()
            require(req.model.isNotBlank()) { "model is required" }
            val results = VllmClient(settings.get()).use { runCompatibilityChecks(it, req.model) }
            call.respond(ChecksResponse(req.model, results))
        }

        get("/dashboard") {
            val minutes = call.request.queryParameters["minutes"]?.toIntOrNull()?.coerceIn(0, 525_600) ?: 1440
            val since = if (minutes <= 0) 0L else System.currentTimeMillis() - minutes * 60_000L
            call.respond(computeDashboard(manager.list(), monitor.samples(since, null, 5000), minutes))
        }

        route("/monitor") {
            get { call.respond(monitor.status()) }
            put { call.respond(monitor.update(call.receive<MonitorConfig>())) }
            post("/probe") {
                val req = runCatching { call.receive<ProbeRequest>() }.getOrDefault(ProbeRequest())
                call.respond(monitor.probeNow(req.models))
            }
            get("/samples") {
                val minutes = call.request.queryParameters["minutes"]?.toIntOrNull()?.coerceIn(0, 525_600) ?: 1440
                val since = if (minutes <= 0) 0L else System.currentTimeMillis() - minutes * 60_000L
                call.respond(monitor.samples(since, call.request.queryParameters["model"]))
            }
            delete("/samples") {
                monitor.clearSamples()
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/runs") {
            get {
                call.respond(manager.list().map {
                    RunListItem(
                        it.id, it.status, it.request.label, it.request.model, it.request.protocol, it.createdAt, it.finishedAt,
                        it.request.promptTokens, it.request.outputTokens, it.request.concurrencyLevels, it.summary,
                    )
                })
            }

            post {
                val req = call.receive<BenchmarkRequest>()
                call.respond(HttpStatusCode.Accepted, manager.start(req))
            }

            get("/{id}") {
                val run = runOrNull(call.parameters["id"], manager)
                if (run == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Run not found")) else call.respond(run)
            }

            post("/{id}/cancel") {
                val id = call.parameters["id"].orEmpty()
                if (manager.cancel(id)) call.respond(HttpStatusCode.Accepted, ErrorResponse("Cancelling"))
                else call.respond(HttpStatusCode.NotFound, ErrorResponse("No active run with that id"))
            }

            delete("/{id}") {
                val id = call.parameters["id"].orEmpty()
                if (!idPattern.matches(id)) return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid run id"))
                if (manager.delete(id)) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ErrorResponse("Run not found"))
            }

            get("/{id}/export.json") {
                val run = runOrNull(call.parameters["id"], manager)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Run not found"))
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"llm-checker-${run.id}.json\"")
                call.respondText(AppJson.encodeToString(run), ContentType.Application.Json)
            }

            get("/{id}/export.csv") {
                val run = runOrNull(call.parameters["id"], manager)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Run not found"))
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"llm-checker-${run.id}.csv\"")
                call.respondText(runToCsv(run), ContentType.Text.CSV)
            }
        }
    }
}

private fun runOrNull(id: String?, manager: BenchmarkManager): RunState? =
    id?.takeIf { idPattern.matches(it) }?.let { manager.get(it) }

fun runToCsv(run: RunState): String = buildString {
    appendLine("run_id,model,protocol,concurrency,request_index,ok,http_status,start_offset_ms,ttft_ms,total_ms,prompt_tokens,completion_tokens,cached_tokens,tokens_estimated,decode_tps,tpot_ms,e2e_tps,error")
    fun f(v: Double?) = v?.let { "%.3f".format(java.util.Locale.ROOT, it) }.orEmpty()
    for (stage in run.stages) {
        for (r in stage.requests) {
            val err = r.error.orEmpty().replace("\"", "\"\"").replace('\n', ' ')
            appendLine(
                listOf(
                    run.id, run.request.model, run.request.protocol, stage.concurrency, r.index, r.ok, r.httpStatus ?: "",
                    f(r.startOffsetMs), f(r.ttftMs), f(r.totalMs), r.promptTokens ?: "", r.completionTokens ?: "",
                    r.cachedTokens ?: "", r.tokensEstimated, f(r.decodeTps), f(r.tpotMs), f(r.e2eTps), "\"$err\"",
                ).joinToString(","),
            )
        }
    }
}

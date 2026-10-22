package io.llmchecker

import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
private data class Health(val ok: Boolean, val version: String, val baseUrl: String)

@Serializable
private data class ProbeRequest(val models: List<String>? = null)

@Serializable
private data class AlarmTestRequest(val channel: String = "", val topic: String = "")

@Serializable
private data class OkResponse(val ok: Boolean = true)

fun Route.staticRoutes() {
    staticResources("/", "static") { default("index.html") }
}

fun Route.apiRoutes(config: AppConfig, monitor: MonitorService, alarms: AlarmService) {
    val settings = config.connection()
    route("/api") {
        get("/health") { call.respond(Health(true, "0.1.0", settings.baseUrl)) }

        get("/models") {
            val (models, latency) = VllmClient(settings).use { it.listModels() }
            call.respond(ModelsResponse(normalizeBaseUrl(settings.baseUrl), latency, models))
        }

        get("/dashboard") {
            val minutes = call.request.queryParameters["minutes"]?.toIntOrNull()?.coerceIn(0, 525_600) ?: 1440
            val since = if (minutes <= 0) 0L else System.currentTimeMillis() - minutes * 60_000L
            call.respond(computeDashboard(monitor.samples(since, null, 5000), minutes))
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

        route("/alarms") {
            get { call.respond(alarms.view()) }
            put { call.respond(alarms.replace(call.receive<AlarmsDocument>().alarms)) }
            post("/test") {
                val req = call.receive<AlarmTestRequest>()
                alarms.sendTest(req.channel, req.topic)
                call.respond(OkResponse())
            }
        }
    }
}

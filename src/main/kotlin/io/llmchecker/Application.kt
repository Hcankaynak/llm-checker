package io.llmchecker

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.event.Level

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = config.host) { module(config) }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    val defaultBase = config.defaultBaseUrl.ifBlank { if (config.mockEnabled) config.mockBaseUrl else "" }
    val settings = SettingsStore(
        config.dataDir.resolve("settings.json"),
        Settings(baseUrl = defaultBase, apiKey = config.defaultApiKey),
    )
    val runStore = RunStore(config.dataDir.resolve("runs"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val manager = BenchmarkManager(scope, runStore, settings)
    val monitor = MonitorService(scope, settings, manager, config.dataDir)
    val mock = if (config.mockEnabled) MockVllmState(config.mockSpeed) else null

    this.monitor.subscribe(io.ktor.server.application.ApplicationStopping) { scope.cancel() }

    install(ContentNegotiation) { json(AppJson) }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.local.uri.startsWith("/api/") && it.request.local.uri != "/api/health" && !it.request.local.uri.startsWith("/api/runs") }
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Conflict"))
        }
        exception<UpstreamException> { call, cause ->
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(cause.message ?: "Upstream error"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(describe(cause)))
        }
    }

    routing {
        apiRoutes(config, settings, manager, monitor)
        if (mock != null) mockVllmRoutes(mock)
        staticRoutes()
    }
}

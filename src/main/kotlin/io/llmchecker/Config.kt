package io.llmchecker

import java.nio.file.Path
import java.nio.file.Paths

data class AppConfig(
    val host: String = "0.0.0.0",
    val port: Int = 18642,
    val dataDir: Path = Paths.get("data"),
    val baseUrl: String = "",
    val apiKey: String? = null,
    val skipTlsVerify: Boolean = false,
    val requestTimeoutSec: Int = 600,
    val mockEnabled: Boolean = false,
    val mockSpeed: Double = 1.0,
) {
    val mockBaseUrl: String get() = "http://127.0.0.1:$port/mock"

    fun connection(): Settings {
        val url = baseUrl.ifBlank { if (mockEnabled) mockBaseUrl else "" }
        return Settings(url, apiKey, skipTlsVerify, requestTimeoutSec)
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun on(name: String) = env[name]?.lowercase() in setOf("1", "true", "yes")
            return AppConfig(
                host = env["HOST"] ?: "0.0.0.0",
                port = env["PORT"]?.toIntOrNull() ?: 18642,
                dataDir = Paths.get(env["DATA_DIR"] ?: "data"),
                baseUrl = env["VLLM_BASE_URL"].orEmpty().trim(),
                apiKey = env["VLLM_API_KEY"]?.takeIf { it.isNotBlank() },
                skipTlsVerify = on("VLLM_SKIP_TLS_VERIFY"),
                requestTimeoutSec = env["VLLM_TIMEOUT_SEC"]?.toIntOrNull()?.coerceIn(5, 7200) ?: 600,
                mockEnabled = on("MOCK_ENABLED"),
                mockSpeed = env["MOCK_SPEED"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
            )
        }
    }
}

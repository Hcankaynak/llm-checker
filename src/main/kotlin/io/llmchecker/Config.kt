package io.llmchecker

import java.nio.file.Path
import java.nio.file.Paths

data class AppConfig(
    val host: String = "0.0.0.0",
    val port: Int = 18642,
    val dataDir: Path = Paths.get("data"),
    val defaultBaseUrl: String = "",
    val defaultApiKey: String? = null,
    val mockEnabled: Boolean = false,
    val mockSpeed: Double = 1.0,
) {
    val mockBaseUrl: String get() = "http://127.0.0.1:$port/mock"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val mock = env["MOCK_ENABLED"]?.lowercase() in setOf("1", "true", "yes")
            val port = env["PORT"]?.toIntOrNull() ?: 18642
            return AppConfig(
                host = env["HOST"] ?: "0.0.0.0",
                port = port,
                dataDir = Paths.get(env["DATA_DIR"] ?: "data"),
                defaultBaseUrl = env["VLLM_BASE_URL"].orEmpty(),
                defaultApiKey = env["VLLM_API_KEY"]?.takeIf { it.isNotBlank() },
                mockEnabled = mock,
                mockSpeed = env["MOCK_SPEED"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
            )
        }
    }
}

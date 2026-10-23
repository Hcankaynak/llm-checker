package io.llmchecker

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VllmTlsTest {
    @Test
    fun trustsACertificateThatDoesNotMatchTheHost() = runBlocking {
        mismatchedServer().use { server ->
            VllmClient(Settings(baseUrl = server.baseUrl, skipTlsVerify = true, requestTimeoutSec = 20)).use { client ->
                val (models, _) = client.listModels()
                assertEquals(listOf("closed-model"), models.map { it.id })

                val outcome = client.stream(
                    StreamRequest("openai", "closed-model", "hello", 8, true, 0.0),
                    System.nanoTime(),
                    0,
                )
                val result = outcome.result
                assertTrue(result.ok, result.error)
                val ttft = result.ttftMs
                assertNotNull(ttft)
                assertTrue(result.totalMs > ttft + 400, "body was buffered: ttft=$ttft total=${result.totalMs}")
            }
        }
    }

    @Test
    fun stillRejectsThatCertificateWhenVerificationIsOn() = runBlocking {
        mismatchedServer().use { server ->
            VllmClient(Settings(baseUrl = server.baseUrl, skipTlsVerify = false, requestTimeoutSec = 15)).use { client ->
                val error = runCatching { client.listModels() }.exceptionOrNull()
                assertNotNull(error)
                val text = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
                assertTrue(
                    text.contains("PKIX") || text.contains("certification path") || text.contains("No server host"),
                    text,
                )
            }
        }
    }

    private class ServerHandle(val baseUrl: String, private val server: HttpsServer) : AutoCloseable {
        override fun close() = server.stop(0)
    }

    private fun mismatchedServer(): ServerHandle {
        val dir = Files.createTempDirectory("vllm-tls")
        val store = dir.resolve("store.p12")
        val keytool = System.getProperty("java.home").let { java.nio.file.Path.of(it, "bin", "keytool") }
        val process = ProcessBuilder(
            keytool.toString(),
            "-genkeypair",
            "-alias", "vllm",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-storetype", "PKCS12",
            "-keystore", store.toString(),
            "-storepass", "changeit",
            "-keypass", "changeit",
            "-dname", "CN=not-the-host",
            "-ext", "SAN=dns:not-the-host",
            "-validity", "1",
            "-noprompt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { output }

        val passwords = "changeit".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        Files.newInputStream(store).use { keyStore.load(it, passwords) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(keyStore, passwords)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(keys.keyManagers, null, null)

        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(ssl)
        server.createContext("/v1/models") { exchange ->
            val body = """{"data":[{"id":"closed-model"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                fun chunk(content: String, done: Boolean) {
                    val usage = if (done) ""","usage":{"prompt_tokens":1,"completion_tokens":2}""" else ""
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]$usage}\n\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(700)
                chunk("aa", false)
                Thread.sleep(700)
                chunk("bb", true)
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            }
        }
        server.start()
        val port = (server.address as InetSocketAddress).port
        return ServerHandle("https://127.0.0.1:$port", server)
    }
}

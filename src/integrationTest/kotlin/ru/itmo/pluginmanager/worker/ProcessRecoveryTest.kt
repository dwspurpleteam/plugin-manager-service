package ru.itmo.pluginmanager.worker

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import ru.itmo.pluginmanager.support.manifest
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProcessRecoveryTest {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()

    /**
     * Тест проверяет восстановление фоновой работы после реального перезапуска JVM.
     * Ожидаемый результат: сохранённая ACTIVATING завершается после старта нового процесса,
     * а появление latest 2.0.0 не изменяет уже закреплённую версию 1.0.0.
     */
    @Test
    @Timeout(120)
    fun `packaged service recovers committed work and keeps pinned version`() {
        PostgreSQLContainer("postgres:17-alpine").use { postgres ->
            GenericContainer("redis:7-alpine").withExposedPorts(6379).use { redis ->
                postgres.start()
                redis.start()
                val metadataRequests = AtomicInteger()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    val body = when (exchange.requestURI.path) {
                        "/v1/plugins" -> {
                            metadataRequests.incrementAndGet()
                            """{"items":[{"id":"plugin","name":"Name","description":"Desc","latestVersion":"1.0.0"}],
                                "page":1,"limit":20,"totalItems":1,"totalPages":1}"""
                        }

                        "/health" -> "{}"

                        else -> mapper.writeValueAsString(
                            manifest(health = "http://127.0.0.1:${server.address.port}/health"),
                        )
                    }
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                server.start()
                try {
                    recover(postgres, redis, server, metadataRequests)
                } finally {
                    server.stop(0)
                }
            }
        }
    }

    private fun recover(
        postgres: PostgreSQLContainer,
        redis: GenericContainer<*>,
        server: HttpServer,
        metadataRequests: AtomicInteger,
    ) {
        val port = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port"
        var process = start(postgres, redis, server.address.port, port, false)
        try {
            await { get("$base/v1/activations/00000000-0000-0000-0000-000000000000").statusCode() == 404 }
            val request = HttpRequest.newBuilder(URI("$base/v1/activations"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {
                          "callId":"recovery",
                          "pluginId":"plugin",
                          "requestedBy":"user",
                          "idempotencyKey":"key",
                          "pluginRegistryId":"recovery"
                        }
                        """.trimIndent(),
                    ),
                ).build()
            val created = http.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(202, created.statusCode())
            val id = mapper.readTree(created.body())["activationId"].asText()
            assertEquals("ACTIVATING", mapper.readTree(get("$base/v1/activations/$id").body())["state"].asText())
            stop(process)
            process = start(postgres, redis, server.address.port, port, true)
            await {
                val response = get("$base/v1/activations/$id")
                response.statusCode() == 200 && mapper.readTree(response.body())["state"].asText() == "ACTIVE"
            }
            assertEquals("1.0.0", mapper.readTree(get("$base/v1/activations/$id").body())["pluginVersion"].asText())
            assertEquals(200, get("$base/v1/activations/$id/signal-route").statusCode())
            val before = metadataRequests.get()
            stop(process)
            server.createContext("/v1/plugins") { exchange ->
                metadataRequests.incrementAndGet()
                val body = """{"items":[{"id":"plugin","name":"Name","description":"Desc","latestVersion":"2.0.0"}],
                    "page":1,"limit":20,"totalItems":1,"totalPages":1}"""
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            process = start(postgres, redis, server.address.port, port, true)
            await { get("$base/v1/activations/$id").statusCode() == 200 }
            assertEquals("1.0.0", mapper.readTree(get("$base/v1/activations/$id").body())["pluginVersion"].asText())
            assertEquals(before, metadataRequests.get())
        } finally {
            stop(process)
        }
    }

    private fun start(
        postgres: PostgreSQLContainer,
        redis: GenericContainer<*>,
        registryPort: Int,
        port: Int,
        scheduler: Boolean,
    ): Process {
        val builder = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dplugin-manager.registries.recovery.url=http://127.0.0.1:$registryPort",
            "-jar",
            "build/quarkus-app/quarkus-run.jar",
        )
        builder.environment().putAll(
            mapOf(
                "DB_HOST" to postgres.host, "DB_PORT" to postgres.firstMappedPort.toString(),
                "DB_NAME" to postgres.databaseName, "DB_USERNAME" to postgres.username,
                "DB_PASSWORD" to postgres.password, "DB_MIGRATION_USERNAME" to postgres.username,
                "DB_MIGRATION_PASSWORD" to postgres.password,
                "REDIS_URL" to "redis://${redis.host}:${redis.firstMappedPort}",
                "QUARKUS_HTTP_HOST" to "127.0.0.1", "QUARKUS_HTTP_PORT" to port.toString(),
                "QUARKUS_SCHEDULER_ENABLED" to scheduler.toString(),
            ),
        )
        return builder.redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("build/process-recovery.log").toFile())).start()
    }

    private fun get(url: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(2)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(100)
        }
        assertTrue(false, "Application did not reach expected state; inspect build/process-recovery.log")
    }

    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}

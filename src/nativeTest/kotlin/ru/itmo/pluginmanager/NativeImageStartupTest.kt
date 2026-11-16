package ru.itmo.pluginmanager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class NativeImageStartupTest {
    /**
     * Тест проверяет запуск native executable и доступность liveness endpoint.
     * Ожидаемый результат: процесс запускается и `/q/health/live` возвращает HTTP 200.
     */
    @Test
    fun nativeExecutableStartsAndExposesLivenessEndpoint() {
        val runner = Path.of(requireNotNull(System.getProperty("native.runner.path")))
        check(Files.isExecutable(runner)) { "Native runner is unavailable: $runner" }

        val httpPort = findFreePort()
        val grpcPort = findFreePort()
        val log = Files.createTempFile("plugin-manager-native-", ".log")
        val process = ProcessBuilder(
            runner.toString(),
            "-Dquarkus.http.port=$httpPort",
            "-Dquarkus.grpc.server.port=$grpcPort",
            "-Dquarkus.flyway.migrate-at-start=false",
            "-Dquarkus.scheduler.enabled=false",
        )
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()

        try {
            val response = awaitLiveness(process, httpPort, log)
            assertEquals(HTTP_OK, response.statusCode())
        } finally {
            process.destroy()
            if (!process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            Files.deleteIfExists(log)
        }
    }

    private fun awaitLiveness(process: Process, port: Int, log: Path): java.net.http.HttpResponse<String> {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/q/health/live"))
            .timeout(Duration.ofSeconds(1))
            .GET()
            .build()
        val deadline = System.nanoTime() + Duration.ofSeconds(STARTUP_TIMEOUT_SECONDS).toNanos()

        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw AssertionError("Native executable stopped before liveness check:\n${Files.readString(log)}")
            }
            runCatching { client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()) }
                .getOrNull()
                ?.let { return it }
            Thread.sleep(HEALTH_RETRY_DELAY_MILLIS)
        }

        throw AssertionError("Native executable did not expose liveness endpoint:\n${Files.readString(log)}")
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val HTTP_OK = 200
        const val PROCESS_STOP_TIMEOUT_SECONDS = 10L
        const val STARTUP_TIMEOUT_SECONDS = 60L
        const val HEALTH_RETRY_DELAY_MILLIS = 250L
    }
}

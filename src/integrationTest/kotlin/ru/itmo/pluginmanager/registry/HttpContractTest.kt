package ru.itmo.pluginmanager.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.database.PostgresTestResource
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.health.HttpPluginHealthcheck
import ru.itmo.pluginmanager.health.PluginHealthcheck
import ru.itmo.pluginmanager.support.manifest
import ru.itmo.pluginmanager.support.policy
import java.net.InetSocketAddress

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class HttpContractTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    /**
     * Тест проверяет реальные HTTP-контракты реестра и отсутствие версии.
     * Ожидаемый результат: метаданные, массив версий и манифест разобраны; HTTP 404 возвращает null.
     */
    @Test
    fun `registry parses metadata version strings and manifest`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/v1/plugins" -> """
                    {"items":[{"id":"plugin","name":"Name","description":"Desc","latestVersion":"1.0.0"}],
                    "page":1,"limit":20,"totalItems":1,"totalPages":1}
                """.trimIndent()

                "/v1/plugins/plugin/versions" -> """["1.0.0","0.9.0"]"""

                "/v1/plugins/plugin/versions/1.0.0/manifest" -> mapper.writeValueAsString(manifest())

                else -> null
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (body == null) 404 else 200, (body ?: "{}").toByteArray().size.toLong())
            exchange.responseBody.use { it.write((body ?: "{}").toByteArray()) }
        }
        server.start()
        try {
            val config = policy(
                mapOf("plugin-manager.registries.test.url" to "http://127.0.0.1:${server.address.port}"),
            )
            val registry: PluginRegistry = HttpPluginRegistry(config, mapper)
            assertEquals("1.0.0", registry.plugins("test").items.single().latestVersion)
            assertEquals(listOf("1.0.0", "0.9.0"), registry.versions("test", "plugin"))
            assertEquals(manifest(), registry.manifest("test", "plugin", "1.0.0"))
            assertNull(registry.manifest("test", "plugin", "missing"))
            assertThrows(PluginManagerException::class.java) { runBlocking { registry.plugins("unknown") } }
            Unit
        } finally {
            server.stop(0)
        }
    }

    /**
     * Тест проверяет строгий код healthcheck и запрет следования redirects.
     * Ожидаемый результат: только 200 успешен; 204 и redirect считаются неуспехом.
     */
    @Test
    fun `probe accepts exactly 200 and does not follow redirects`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val code = when (exchange.requestURI.path) {
                "/ok" -> 200
                "/redirect" -> 302
                else -> 204
            }
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }
        server.start()
        try {
            val probe: PluginHealthcheck = HttpPluginHealthcheck(policy())
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(true, probe.check("$base/ok"))
            assertFalse(probe.check("$base/redirect"))
            assertFalse(probe.check("$base/empty"))
        } finally {
            server.stop(0)
        }
    }
}

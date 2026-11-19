package ru.itmo.pluginmanager.registry

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.application.DefaultConfigurationService
import ru.itmo.pluginmanager.application.ManifestSchemaService
import ru.itmo.pluginmanager.database.PostgresTestResource
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.health.HttpPluginHealthcheck
import ru.itmo.pluginmanager.health.PluginHealthcheck
import ru.itmo.pluginmanager.support.manifest
import ru.itmo.pluginmanager.support.policy
import java.net.InetSocketAddress
import java.net.ServerSocket

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class HttpContractTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Inject
    lateinit var schemas: ManifestSchemaService

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
                    {"items":[{"id":"plugin","name":"Name","description":null}],
                    "page":1,"limit":20,"totalItems":1,"totalPages":1}
                """.trimIndent()

                "/v1/plugins/plugin/versions" -> {
                    val page = if (exchange.requestURI.query.contains("page=2")) 2 else 1
                    val version = if (page == 1) "0.9.0" else "1.0.0"
                    """{"items":[{"pluginId":"plugin","version":"$version"}],
                       "page":$page,"limit":20,"totalItems":2,"totalPages":2}""".trimIndent()
                }

                "/v1/plugins/plugin/versions/1.0.0/manifest" -> mapper.writeValueAsString(
                    manifest().let { it.copy(backend = it.backend.copy(events = null)) },
                )

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
            assertEquals("plugin", registry.plugins("test").items.single().id)
            assertEquals(listOf("0.9.0", "1.0.0"), registry.versions("test", "plugin"))
            assertEquals(
                mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    manifest().let { it.copy(backend = it.backend.copy(events = null)) },
                ),
                registry.manifest("test", "plugin", "1.0.0"),
            )
            assertNull(registry.manifest("test", "plugin", "missing"))
            assertThrows(PluginManagerException::class.java) { runBlocking { registry.plugins("unknown") } }
            Unit
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `resolves a version 1_1 manifest without optional sdk fields`() = runBlocking {
        val manifest = mapper.readTree(
            """
            {
              "manifest-version":"1.1", "plugin-id":"plugin", "plugin-version":"1.0.0", "name":"plugin",
              "frontend":{"url-web":"https://example.com/ui"},
              "backend":{
                "health-url":"https://example.com/health", "control-url":"https://example.com/control",
                "events":{"available-event-types":["participant.joined"]},
                "actions":["microphone.mute"], "results":{"keys":["attendance.summary"]}
              }
            }
            """.trimIndent(),
        )
        val registry = object : PluginRegistry {
            override suspend fun plugins(registryId: String, page: Int, limit: Int) =
                RegistryPage(listOf(RegistryPlugin("plugin", "plugin")), page, limit, 1, 1)
            override suspend fun versions(registryId: String, pluginId: String) = listOf("0.9.0", "1.0.0")
            override suspend fun manifest(registryId: String, pluginId: String, version: String) = manifest
        }
        val service = DefaultConfigurationService(
            policy(mapOf("plugin-manager.registries.test.url" to "http://localhost:1")),
            registry,
            schemas,
            mapper,
        )
        val selection = service.resolve("plugin")
        assertEquals("1.0.0", selection.pluginVersion)
        assertEquals(setOf("microphone.mute"), service.get(selection).manifest.backend.actions)
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

    /**
     * Тест проверяет обработку отказа TCP-соединения при healthcheck.
     * Ожидаемый результат: отказ соединения считается неуспешной проверкой и возвращает false.
     */
    @Test
    fun `probe treats connection refusal as unhealthy`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val probe: PluginHealthcheck = HttpPluginHealthcheck(policy())

        assertFalse(probe.check("http://127.0.0.1:$port"))
    }
}

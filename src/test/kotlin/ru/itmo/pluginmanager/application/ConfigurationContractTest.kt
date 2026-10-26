package ru.itmo.pluginmanager.application

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginManifest
import ru.itmo.pluginmanager.registry.PluginRegistry
import ru.itmo.pluginmanager.registry.RegistryPage
import ru.itmo.pluginmanager.registry.RegistryPlugin
import ru.itmo.pluginmanager.support.manifest
import ru.itmo.pluginmanager.support.policy

class ConfigurationContractTest {

    private var calls = 0
    private val registry = object : PluginRegistry {
        override suspend fun plugins(registryId: String, page: Int, limit: Int) =
            RegistryPage(listOf(RegistryPlugin("plugin", "Example", "Description", "2.0.0")), page, limit, 1, 1)

        override suspend fun versions(registryId: String, pluginId: String) = listOf("2.0.0", "1.0.0")

        override suspend fun manifest(registryId: String, pluginId: String, version: String): PluginManifest? {
            calls++
            if (registryId == "down") throw PluginManagerException(ErrorCode.REGISTRY_UNAVAILABLE, "Unavailable")
            if (pluginId == "missing") return null
            if (registryId == "slow") delay(100)
            return manifest(version)
        }
    }

    private fun service(): ConfigurationService = DefaultConfigurationService(
        policy(
            mapOf(
                "plugin-manager.registries.down.url" to "http://localhost:1",
                "plugin-manager.registries.slow.url" to "http://localhost:2",
                "plugin-manager.registries.fast.url" to "http://localhost:3",
            ),
        ),
        registry,
    )

    /**
     * Тест проверяет конкурентный поиск без приоритетов реестров при недоступности одного из них.
     * Ожидаемый результат: побеждает быстрый реестр, последующее чтение повторно запрашивает манифест.
     */
    @Test
    fun `first suitable result wins despite unavailable registry`() = runBlocking {
        val service = service()
        val selected = service.resolve("plugin")
        assertEquals("fast", selected.registryId)
        assertEquals("2.0.0", selected.pluginVersion)
        val before = calls
        assertEquals("2.0.0", service.get(selected).manifest.pluginVersion)
        assertEquals(before + 1, calls)
    }

    /**
     * Тест проверяет явный реестр и различие отсутствия и частичной недоступности.
     * Ожидаемый результат: явный промах даёт NOT_FOUND, общий неполный поиск — UNAVAILABLE.
     */
    @Test
    fun `absence is not inferred from partial outage`() = runBlocking {
        val service = service()
        val absent = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.resolve("missing", "1", "fast") }
        }
        assertEquals(ErrorCode.PLUGIN_NOT_FOUND, absent.code)
        val partial = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.resolve("missing", "1") }
        }
        assertEquals(ErrorCode.REGISTRY_UNAVAILABLE, partial.code)
        val unknown = assertThrows(PluginManagerException::class.java) {
            runBlocking { service.resolve("plugin", "1", "unknown") }
        }
        assertEquals(ErrorCode.UNKNOWN_REGISTRY, unknown.code)
    }
}

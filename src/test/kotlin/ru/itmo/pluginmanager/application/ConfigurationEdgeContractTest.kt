package ru.itmo.pluginmanager.application

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.cache.ManifestCache
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginManifest
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.registry.PluginRegistry
import ru.itmo.pluginmanager.registry.RegistryPage
import ru.itmo.pluginmanager.registry.RegistryPlugin
import ru.itmo.pluginmanager.support.component
import ru.itmo.pluginmanager.support.manifest
import ru.itmo.pluginmanager.support.policy

class ConfigurationEdgeContractTest {
    private val pages = mutableListOf<Int>()
    private var response: PluginManifest? = manifest()
    private var pause = 0L
    private val registry = object : PluginRegistry {
        override suspend fun plugins(registryId: String, page: Int, limit: Int): RegistryPage<RegistryPlugin> {
            pages.add(page)
            return RegistryPage(
                if (page == 2) listOf(RegistryPlugin("plugin", "Name", "Description", "1.0.0")) else emptyList(),
                page,
                limit,
                1,
                2,
            )
        }
        override suspend fun versions(registryId: String, pluginId: String) = emptyList<String>()
        override suspend fun manifest(registryId: String, pluginId: String, version: String): PluginManifest? {
            delay(pause)
            return response
        }
    }
    private val cache = object : ManifestCache {
        override suspend fun get(selection: PluginSelection) = manifest("wrong-cached-version")
        override suspend fun put(selection: PluginSelection, manifest: PluginManifest) = Unit
    }
    private fun service(): ConfigurationService = component(
        "application.DefaultConfigurationService",
        policy(
            mapOf(
                "plugin-manager.registries.test.url" to "http://localhost:1",
                "plugin-manager.http.search-timeout" to "PT0.05S",
            ),
        ),
        registry,
        cache,
    )

    /**
     * Тест проверяет пагинацию latest и отбрасывание кеша с неверной идентичностью.
     * Ожидаемый результат: поиск доходит до второй страницы и возвращает проверенную версию реестра.
     */
    @Disabled
    @Test
    fun `latest traverses pages and rejects wrong cached identity`() = runBlocking {
        val selected = service().resolve("plugin")
        assertEquals(listOf(1, 2), pages)
        assertEquals("1.0.0", selected.pluginVersion)
    }

    /**
     * Тест проверяет несовпадение манифеста и общий deadline поиска.
     * Ожидаемый результат: неверная версия даёт INVALID_MANIFEST, медленный реестр — UNAVAILABLE.
     */
    @Test
    fun `invalid identity and search deadline are distinguishable`() = runBlocking {
        response = manifest("wrong")
        val invalid = assertThrows(PluginManagerException::class.java) {
            runBlocking { service().resolve("plugin", "1.0.0") }
        }
        assertEquals(ErrorCode.INVALID_MANIFEST, invalid.code)
        response = manifest()
        pause = 200
        val timeout = assertThrows(PluginManagerException::class.java) {
            runBlocking { service().resolve("plugin", "1.0.0") }
        }
        assertEquals(ErrorCode.REGISTRY_UNAVAILABLE, timeout.code)
    }
}

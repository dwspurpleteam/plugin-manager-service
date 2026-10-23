package ru.itmo.pluginmanager.registry

import ru.itmo.pluginmanager.domain.PluginManifest

data class RegistryPlugin(val id: String, val name: String, val description: String, val latestVersion: String)

data class RegistryPage<T>(
    val items: List<T>,
    val page: Int,
    val limit: Int,
    val totalItems: Long,
    val totalPages: Int,
)

/** HTTP-контракт отдельного реестра; отсутствие отделяется от недоступности и некорректного ответа. */
interface PluginRegistry {
    /** Читает страницу метаданных; нумерация начинается с 1, лимит по умолчанию 20. */
    suspend fun plugins(registryId: String, page: Int = 1, limit: Int = 20): RegistryPage<RegistryPlugin>

    /** Читает массив строк версий без самостоятельного ранжирования или сравнения SemVer. */
    suspend fun versions(registryId: String, pluginId: String): List<String>

    /** Получает манифест через маршрут конкретной версии; null означает HTTP 404. */
    suspend fun manifest(registryId: String, pluginId: String, version: String): PluginManifest?
}

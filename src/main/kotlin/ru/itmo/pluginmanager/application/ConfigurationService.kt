package ru.itmo.pluginmanager.application

import ru.itmo.pluginmanager.domain.PluginConfiguration
import ru.itmo.pluginmanager.domain.PluginSelection

/** Получение конфигурации без хранения полного манифеста в PostgreSQL. */
interface ConfigurationService {
    /** Выбирает точную или текущую последнюю версию в первом подходящем реестре. */
    suspend fun resolve(pluginId: String, pluginVersion: String? = null, registryId: String? = null): PluginSelection

    /** Получает манифест фиксированной версии из Redis либо указанного реестра. */
    suspend fun get(selection: PluginSelection): PluginConfiguration
}

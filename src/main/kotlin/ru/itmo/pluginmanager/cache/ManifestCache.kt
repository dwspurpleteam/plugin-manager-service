package ru.itmo.pluginmanager.cache

import ru.itmo.pluginmanager.domain.PluginManifest
import ru.itmo.pluginmanager.domain.PluginSelection

/** Необязательный для доступности кеш неизменяемых манифестов в Redis. */
interface ManifestCache {
    /** Возвращает корректный манифест либо null при промахе, повреждении или недоступности кеша. */
    suspend fun get(selection: PluginSelection): PluginManifest?

    /** Записывает манифест с настроенным TTL; ошибка Redis не отменяет успешный запрос реестра. */
    suspend fun put(selection: PluginSelection, manifest: PluginManifest)
}

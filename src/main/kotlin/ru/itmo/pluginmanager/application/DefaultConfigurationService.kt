package ru.itmo.pluginmanager.application

import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginConfiguration
import ru.itmo.pluginmanager.domain.PluginManagerException
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.domain.validateIdentifier
import ru.itmo.pluginmanager.domain.validateManifest
import ru.itmo.pluginmanager.registry.PluginRegistry
import kotlin.time.Duration.Companion.milliseconds

@ApplicationScoped
class DefaultConfigurationService(private val config: PluginManagerConfig, private val registry: PluginRegistry) :
    ConfigurationService {
    private val permits = Semaphore(config.http().concurrency())

    override suspend fun resolve(pluginId: String, pluginVersion: String?, registryId: String?): PluginSelection {
        validateIdentifier(pluginId)
        pluginVersion?.let(::validateIdentifier)
        val ids = registryIds(registryId)
        return withTimeoutOrNull(config.http().searchTimeout().toMillis().milliseconds) {
            search(ids, pluginId, pluginVersion)
        } ?: throw PluginManagerException(ErrorCode.REGISTRY_UNAVAILABLE, "Registry search timed out")
    }

    private fun registryIds(registryId: String?): List<String> {
        if (registryId != null && registryId !in config.registries()) {
            throw PluginManagerException(ErrorCode.UNKNOWN_REGISTRY, "Unknown registry")
        }
        val ids = registryId?.let(::listOf) ?: config.registries().keys.toList()
        if (ids.isEmpty()) throw PluginManagerException(ErrorCode.REGISTRY_UNAVAILABLE, "No registries configured")
        return ids
    }

    private suspend fun search(ids: List<String>, pluginId: String, version: String?): PluginSelection =
        coroutineScope {
            // todo почему канал не закрывается?
            val results = Channel<Result<PluginSelection>>(ids.size)
            val jobs = ids.map { id ->
                launch {
                    val result = try {
                        Result.success(resolveIn(id, pluginId, version))
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: PluginManagerException) {
                        Result.failure(exception)
                    }
                    results.send(result)
                }
            }
            val errors = mutableListOf<ErrorCode>()
            repeat(ids.size) {
                val result = results.receive()
                result.getOrNull()?.let { selection ->
                    jobs.forEach { it.cancel() }
                    return@coroutineScope selection
                }
                // todo cce не упадет?
                errors.add((result.exceptionOrNull() as PluginManagerException).code)
            }
            val code = when {
                ErrorCode.REGISTRY_UNAVAILABLE in errors -> ErrorCode.REGISTRY_UNAVAILABLE
                ErrorCode.INVALID_MANIFEST in errors -> ErrorCode.INVALID_MANIFEST
                ErrorCode.REGISTRY_INVALID_RESPONSE in errors -> ErrorCode.REGISTRY_INVALID_RESPONSE
                else -> ErrorCode.PLUGIN_NOT_FOUND
            }
            throw PluginManagerException(code, "No suitable plugin found")
        }

    private suspend fun resolveIn(id: String, pluginId: String, version: String?): PluginSelection {
        var page = 1
        var selected = version
        while (selected == null) {
            val metadata = permits.withPermit { registry.plugins(id, page) }
            selected = metadata.items.firstOrNull { it.id == pluginId }?.latestVersion
            if (selected == null && page >= metadata.totalPages) {
                throw PluginManagerException(ErrorCode.PLUGIN_NOT_FOUND, "Plugin not found")
            }
            page++
        }
        val selection = PluginSelection(pluginId, selected, id)
        get(selection)
        return selection
    }

    override suspend fun get(selection: PluginSelection): PluginConfiguration {
        if (selection.registryId !in config.registries()) {
            throw PluginManagerException(ErrorCode.UNKNOWN_REGISTRY, "Unknown registry")
        }
        val manifest = permits.withPermit {
            registry.manifest(selection.registryId, selection.pluginId, selection.pluginVersion)
        }
            ?: throw PluginManagerException(ErrorCode.PLUGIN_NOT_FOUND, "Plugin not found")
        validateManifest(selection, manifest)
        return PluginConfiguration(selection, manifest)
    }
}

package ru.itmo.pluginmanager.api

import jakarta.ws.rs.Path
import ru.itmo.pluginmanager.application.ConfigurationService

@Path("/v1/plugins")
class ConfigurationResource(private val service: ConfigurationService) : ConfigurationApi {
    override suspend fun version(pluginId: String, version: String, registryId: String?): ConfigurationResponse =
        response(pluginId, version, registryId)

    override suspend fun latest(pluginId: String, registryId: String?): ConfigurationResponse =
        response(pluginId, null, registryId)

    private suspend fun response(pluginId: String, version: String?, registryId: String?): ConfigurationResponse {
        val config = service.get(service.resolve(pluginId, version, registryId))
        return ConfigurationResponse(
            config.selection.pluginId,
            config.selection.pluginVersion,
            config.selection.registryId,
            config.manifest,
        )
    }
}

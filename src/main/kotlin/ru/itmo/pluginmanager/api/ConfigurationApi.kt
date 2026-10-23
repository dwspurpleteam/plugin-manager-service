package ru.itmo.pluginmanager.api

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

/** REST-контракт чтения конфигурации; выбор без версии не закрепляется глобально между запросами. */
@Path("/v1/plugins")
@Produces(MediaType.APPLICATION_JSON)
interface ConfigurationApi {
    /** Возвращает точную неизменяемую версию из кеша или реестра. */
    @GET
    @Path("/{pluginId}/versions/{version}/configuration")
    suspend fun version(
        @PathParam("pluginId") pluginId: String,
        @PathParam("version") version: String,
        @QueryParam("pluginRegistryId") registryId: String?,
    ): ConfigurationResponse

    /** Возвращает текущую последнюю версию; активация закрепляет выбор отдельно в PostgreSQL. */
    @GET
    @Path("/{pluginId}/configuration")
    suspend fun latest(
        @PathParam("pluginId") pluginId: String,
        @QueryParam("pluginRegistryId") registryId: String?,
    ): ConfigurationResponse
}

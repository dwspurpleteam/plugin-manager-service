package ru.itmo.pluginmanager.registry

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import org.jboss.resteasy.reactive.RestResponse

/** Низкоуровневый REST-клиент реестра; ответы закрывает вызывающий адаптер. */
interface RegistryHttpEndpoint : AutoCloseable {
    @GET
    @Path("/v1/plugins")
    suspend fun plugins(
        @QueryParam("page") page: Int,
        @QueryParam("limit") limit: Int,
    ): RestResponse<String>

    @GET
    @Path("/v1/plugins/{id}/versions")
    suspend fun versions(
        @PathParam("id") id: String,
    ): RestResponse<String>

    @GET
    @Path("/v1/plugins/{id}/versions/{version}/manifest")
    suspend fun manifest(
        @PathParam("id") id: String,
        @PathParam("version") version: String,
    ): RestResponse<String>
}

package ru.itmo.pluginmanager.api

import com.fasterxml.jackson.databind.JsonNode
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/** Административный REST-контракт регистрации неизменяемых версий схем манифеста. */
@Path("/v1/manifest-versions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface ManifestVersionApi {
    /** Регистрирует JSON Schema; идентичный повтор идемпотентен, изменение версии запрещено. */
    @POST
    suspend fun register(schema: JsonNode): Response
}

data class ManifestVersionResponse(val version: String)

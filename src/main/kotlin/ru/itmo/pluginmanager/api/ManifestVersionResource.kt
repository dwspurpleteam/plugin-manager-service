package ru.itmo.pluginmanager.api

import com.fasterxml.jackson.databind.JsonNode
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import ru.itmo.pluginmanager.application.ManifestSchemaService
import java.net.URI

@Path("/v1/manifest-versions")
class ManifestVersionResource(private val service: ManifestSchemaService) : ManifestVersionApi {
    override suspend fun register(schema: JsonNode): Response {
        val result = service.register(schema)
        return Response.status(if (result.created) Response.Status.CREATED else Response.Status.OK)
            .location(URI("/v1/manifest-versions/${result.version}"))
            .entity(ManifestVersionResponse(result.version))
            .build()
    }
}

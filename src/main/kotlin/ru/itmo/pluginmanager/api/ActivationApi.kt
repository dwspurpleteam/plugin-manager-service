package ru.itmo.pluginmanager.api

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.media.Content
import org.eclipse.microprofile.openapi.annotations.media.Schema
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse
import java.util.UUID

/** REST-контракт активаций; реализация подключается после согласования каркаса. */
@Path("/v1/activations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface ActivationApi {
    /** Возвращает 202 и Location для новой активации, 200 для повтора, 409 для конфликта ключа. */
    @POST
    suspend fun register(request: RegisterActivationRequest): Response

    /** Ставит деактивацию плагина в очередь и возвращает 202 и Location. */
    @POST
    @Path("/{activationId}/deactivate")
    @APIResponse(
        responseCode = "202",
        description = "Deactivation accepted",
        content = [Content(schema = Schema(implementation = DeactivationContext::class))],
    )
    suspend fun deactivate(
        @PathParam("activationId") activationId: UUID,
        request: DeactivateActivationRequest,
    ): Response

    /** Возвращает состояние либо 404 для неизвестной активации. */
    @GET
    @Path("/{activationId}")
    suspend fun get(
        @PathParam("activationId") activationId: UUID,
    ): ActivationResponse

    /** Возвращает маршрут в ACTIVE; для других состояний возвращается 409. */
    @GET
    @Path("/{activationId}/signal-route")
    suspend fun signalRoute(
        @PathParam("activationId") activationId: UUID,
    ): SignalRouteResponse

    /** Возвращает входные типы медиа в ACTIVE; для других состояний возвращается 409. */
    @GET
    @Path("/{activationId}/media-types")
    suspend fun mediaTypes(
        @PathParam("activationId") activationId: UUID,
    ): MediaTypesResponse

    // todo добавить потом веб сокет соединение для трансляции статуса плагина
}

package ru.itmo.pluginmanager.api

import jakarta.ws.rs.Path
import jakarta.ws.rs.core.Response
import ru.itmo.pluginmanager.application.ActivationService
import ru.itmo.pluginmanager.domain.ActivationRequest
import java.net.URI
import java.util.UUID

@Path("/v1/activations")
class ActivationResource(private val service: ActivationService) : ActivationApi {
    override suspend fun register(request: RegisterActivationRequest): Response {
        val result = service.register(
            ActivationRequest(
                request.callId,
                request.pluginId,
                request.requestedBy,
                request.idempotencyKey,
                request.pluginVersion,
                request.pluginRegistryId,
            ),
        )
        return Response.status(if (result.created) Response.Status.ACCEPTED else Response.Status.OK)
            .location(URI("/v1/activations/${result.activationId}"))
            .entity(RegisterActivationResponse(result.activationId)).build()
    }

    override suspend fun get(activationId: UUID): ActivationResponse {
        val activation = service.get(activationId)
        return ActivationResponse(
            activation.activationId, activation.request.callId, activation.request.pluginId,
            activation.request.pluginVersion, activation.request.pluginRegistryId, activation.request.requestedBy,
            activation.selection?.pluginVersion, activation.selection?.registryId, activation.state,
            activation.stateChangedAt, activation.failureCode,
        )
    }

    override suspend fun signalRoute(activationId: UUID): SignalRouteResponse {
        val route = service.signalRoute(activationId)
        val activation = service.get(activationId)
        val selection = checkNotNull(activation.selection)
        return SignalRouteResponse(
            activationId,
            route.routeId,
            selection.pluginId,
            selection.pluginVersion,
            selection.registryId,
            route.signalDst,
        )
    }

    override suspend fun mediaTypes(activationId: UUID) = MediaTypesResponse(
        activationId,
        service.mediaTypes(activationId),
    )
}

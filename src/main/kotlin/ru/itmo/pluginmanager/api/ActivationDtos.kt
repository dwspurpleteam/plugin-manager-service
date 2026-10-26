package ru.itmo.pluginmanager.api

import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.MediaType
import java.time.Instant
import java.util.UUID

data class RegisterActivationRequest(
    val callId: String,
    val pluginId: String,
    val requestedBy: String,
    val idempotencyKey: String,
    val pluginVersion: String? = null,
    val pluginRegistryId: String? = null,
)

data class RegisterActivationResponse(val activationId: UUID)

data class ActivationResponse(
    val activationId: UUID,
    val callId: String,
    val pluginId: String,
    val requestedVersion: String?,
    val requestedRegistryId: String?,
    val requestedBy: String,
    val pluginVersion: String?,
    val registryId: String?,
    val state: ActivationState,
    val stateChangedAt: Instant,
    val failureCode: ErrorCode?,
)

data class SignalRouteResponse(
    val activationId: UUID,
    val routeId: UUID,
    val pluginId: String,
    val pluginVersion: String,
    val registryId: String,
    val signalDst: String,
)

data class MediaTypesResponse(val activationId: UUID, val mediaTypes: Set<MediaType>)

data class ErrorResponse(val code: ErrorCode, val message: String, val activationId: UUID? = null)

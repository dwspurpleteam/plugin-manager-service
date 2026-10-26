package ru.itmo.pluginmanager.domain

import java.time.Instant
import java.util.UUID

enum class ActivationState {
    ACTIVATING,
    ACTIVE,
    FAILED,
    DEGRADED,
    SUSPENDING,
    SUSPENDED,
    RESUMING,
    STOPPING,
    STOPPED,
}

enum class MediaType {
    AUDIO,
    VIDEO,
    SCREEN_SHARE,
    SCREEN_SHARE_AUDIO,
}

data class ActivationRequest(
    val callId: String,
    val pluginId: String,
    val requestedBy: String,
    val idempotencyKey: String,
    val pluginVersion: String? = null,
    val pluginRegistryId: String? = null,
)

data class PluginSelection(val pluginId: String, val pluginVersion: String, val registryId: String)

data class Activation(
    val activationId: UUID,
    val request: ActivationRequest,
    val selection: PluginSelection?,
    val state: ActivationState,
    val stateRevision: Long,
    val createdAt: Instant,
    val stateChangedAt: Instant,
    val failureCode: ErrorCode?,
)

data class ActivationRoute(
    val routeId: UUID,
    val activationId: UUID,
    val signalDst: String,
    val allowedEventTypes: Set<String>?,
    val allowedEventGroups: Set<String>?,
    val healthcheckUrl: String,
)

data class RegistrationResult(val activationId: UUID, val created: Boolean)

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
    val initialContext: ActivationContext? = null,
)

/** Доверенный начальный снимок звонка, сформированный Call Backend для активации per-call. */
data class ActivationContext(
    val callId: String,
    val roomName: String? = null,
    val startDate: Long? = null,
    val endDate: Long? = null,
    val participantCount: Int? = null,
    val recordingActive: Boolean? = null,
    val maxParticipants: Int? = null,
    val participants: List<ActivationParticipant> = emptyList(),
    val snapshot: ActivationContextSnapshot,
)

/** Участник в доверенном начальном снимке звонка. */
data class ActivationParticipant(
    val participantIdentity: String,
    val participantName: String? = null,
    val externalId: String? = null,
    val metadata: String? = null,
    val role: String? = null,
    val joinDate: Long? = null,
    val audioActive: Boolean? = null,
    val videoActive: Boolean? = null,
    val screenShareActive: Boolean? = null,
)

/** Метаданные консистентности доверенного снимка звонка. */
data class ActivationContextSnapshot(val capturedAt: Instant, val source: String, val consistency: String)

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

/** Версионированное сообщение, отправляемое Plugin Backend при активации. */
data class PluginActivationMessage(
    val schemaVersion: String = "1.0",
    val activationId: UUID,
    val pluginId: String,
    val pluginVersion: String,
    val registryId: String,
    val callId: String,
    val initialContext: ActivationContext? = null,
)

package ru.itmo.pluginmanager.routes

import jakarta.enterprise.context.ApplicationScoped
import ru.itmo.pluginmanager.application.ConfigurationService
import ru.itmo.pluginmanager.domain.Activation
import ru.itmo.pluginmanager.persistence.ActivationStore
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionResponse
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionResponse
import ru.itmo.pluginmanager.routes.v1.EventType
import ru.itmo.pluginmanager.domain.MediaType as ManifestMediaType
import ru.itmo.pluginmanager.routes.v1.MediaType as RouteMediaType

@ApplicationScoped
class RoutesServiceImpl(private val activations: ActivationStore, private val configurations: ConfigurationService) :
    RoutesService {
    override suspend fun getPermissionsForEvent(request: CheckEventPermissionRequest): CheckEventPermissionResponse {
        val pluginIds = activeActivations(request.callId)
            .filter { activation ->
                val events = configurations.get(requireNotNull(activation.selection)).manifest.backend.events
                request.eventType.groupName() in events.availableEventGroups.orEmpty() ||
                        request.eventType.eventName() in events.availableEventTypes.orEmpty()
            }
            .map { it.request.pluginId }
            .distinct()
        return CheckEventPermissionResponse.newBuilder()
            .setEventType(request.eventType)
            .setCanSend(pluginIds.isNotEmpty())
            .addAllPluginIds(pluginIds)
            .build()
    }

    override suspend fun getPermissionsForMedia(request: CheckMediaPermissionRequest): CheckMediaPermissionResponse {
        val pluginIds = request.mediaType.manifestMediaType()?.let { requestedMediaType ->
            activeActivations(request.callId)
                .filter { activation ->
                    val input = configurations.get(requireNotNull(activation.selection)).manifest.backend.media.input
                    requestedMediaType in input
                }
                .map { it.request.pluginId }
                .distinct()
        }.orEmpty()
        return CheckMediaPermissionResponse.newBuilder()
            .setMediaType(request.mediaType)
            .setCanSend(pluginIds.isNotEmpty())
            .addAllPluginIds(pluginIds)
            .build()
    }

    private suspend fun activeActivations(callId: String): List<Activation> = activations.findActiveByCall(callId)

    private fun EventType.eventName(): String? = eventNames[this]

    private fun EventType.groupName(): String? = when (this) {
        EventType.EVENT_TYPE_PARTICIPANT_JOINED,
        EventType.EVENT_TYPE_PARTICIPANT_LEFT,
        EventType.EVENT_TYPE_PARTICIPANT_CONNECTION_ABORTED,
        EventType.EVENT_TYPE_PARTICIPANT_RECONNECTING,
        EventType.EVENT_TYPE_PARTICIPANT_RECONNECTED,
        EventType.EVENT_TYPE_PARTICIPANT_KICKED,
        -> "participants"

        EventType.EVENT_TYPE_MEDIA_AUDIO_MUTED,
        EventType.EVENT_TYPE_MEDIA_AUDIO_UNMUTED,
        EventType.EVENT_TYPE_MEDIA_VIDEO_ENABLED,
        EventType.EVENT_TYPE_MEDIA_VIDEO_DISABLED,
        EventType.EVENT_TYPE_MEDIA_SCREEN_SHARE_STARTED,
        EventType.EVENT_TYPE_MEDIA_SCREEN_SHARE_STOPPED,
        EventType.EVENT_TYPE_MEDIA_TRACK_PUBLISHED,
        EventType.EVENT_TYPE_MEDIA_TRACK_UNPUBLISHED,
        -> "media"

        EventType.EVENT_TYPE_INTERACTION_CHAT_MESSAGE_RECEIVED -> "interaction"

        else -> null
    }

    private fun RouteMediaType.manifestMediaType(): ManifestMediaType? = when (this) {
        RouteMediaType.MEDIA_TYPE_AUDIO -> ManifestMediaType.AUDIO
        RouteMediaType.MEDIA_TYPE_VIDEO -> ManifestMediaType.VIDEO
        RouteMediaType.MEDIA_TYPE_SCREEN_SHARE -> ManifestMediaType.SCREEN_SHARE
        RouteMediaType.MEDIA_TYPE_SCREEN_SHARE_AUDIO -> ManifestMediaType.SCREEN_SHARE_AUDIO
        else -> null
    }

    private companion object {
        val eventNames = mapOf(
            EventType.EVENT_TYPE_PARTICIPANT_JOINED to "participant.joined",
            EventType.EVENT_TYPE_PARTICIPANT_LEFT to "participant.left",
            EventType.EVENT_TYPE_PARTICIPANT_CONNECTION_ABORTED to "participant.connection_aborted",
            EventType.EVENT_TYPE_PARTICIPANT_RECONNECTING to "participant.reconnecting",
            EventType.EVENT_TYPE_PARTICIPANT_RECONNECTED to "participant.reconnected",
            EventType.EVENT_TYPE_PARTICIPANT_KICKED to "participant.kicked",
            EventType.EVENT_TYPE_MEDIA_AUDIO_MUTED to "media.audio_muted",
            EventType.EVENT_TYPE_MEDIA_AUDIO_UNMUTED to "media.audio_unmuted",
            EventType.EVENT_TYPE_MEDIA_VIDEO_ENABLED to "media.video_enabled",
            EventType.EVENT_TYPE_MEDIA_VIDEO_DISABLED to "media.video_disabled",
            EventType.EVENT_TYPE_MEDIA_SCREEN_SHARE_STARTED to "media.screen_share_started",
            EventType.EVENT_TYPE_MEDIA_SCREEN_SHARE_STOPPED to "media.screen_share_stopped",
            EventType.EVENT_TYPE_MEDIA_TRACK_PUBLISHED to "media.track_published",
            EventType.EVENT_TYPE_MEDIA_TRACK_UNPUBLISHED to "media.track_unpublished",
            EventType.EVENT_TYPE_INTERACTION_CHAT_MESSAGE_RECEIVED to "interaction.chat_message_received",
        )
    }
}

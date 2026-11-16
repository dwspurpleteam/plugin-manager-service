package ru.itmo.pluginmanager.routes

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.application.ConfigurationService
import ru.itmo.pluginmanager.domain.ActivationState
import ru.itmo.pluginmanager.domain.PluginConfiguration
import ru.itmo.pluginmanager.domain.PluginSelection
import ru.itmo.pluginmanager.routes.v1.CheckActionPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionRequest
import ru.itmo.pluginmanager.routes.v1.EventType
import ru.itmo.pluginmanager.routes.v1.MediaType
import ru.itmo.pluginmanager.support.MemoryStores
import ru.itmo.pluginmanager.support.manifest

class RoutesServiceImplTest {
    private val store = MemoryStores()
    private val selection = PluginSelection("plugin", "1.0.0", "registry")
    private val configuration = object : ConfigurationService {
        override suspend fun resolve(pluginId: String, pluginVersion: String?, registryId: String?) = selection
        override suspend fun get(selection: PluginSelection) = PluginConfiguration(selection, manifest())
    }
    private val secrets = ActivationSecrets("0123456789abcdef0123456789abcdef")
    private val service = RoutesServiceImpl(store, configuration, secrets)

    @Test
    fun `explicit event types take precedence over event groups`() = runBlocking {
        store.activation = store.activation.copy(state = ActivationState.ACTIVE, selection = selection)
        val base = manifest()
        val events = base.backend.events.copy(
            availableEventTypes = setOf("participant.left"),
            availableEventGroups = setOf("participant"),
        )
        val restricted = base.copy(backend = base.backend.copy(events = events))
        val source = object : ConfigurationService {
            override suspend fun resolve(pluginId: String, pluginVersion: String?, registryId: String?) = selection
            override suspend fun get(selection: PluginSelection) = PluginConfiguration(selection, restricted)
        }
        val request = CheckEventPermissionRequest.newBuilder().setCallId("call")
            .setEventType(EventType.EVENT_TYPE_PARTICIPANT_JOINED).build()

        assertFalse(RoutesServiceImpl(store, source, secrets).getPermissionsForEvent(request).canSend)
    }

    @Test
    fun `allows participant events declared by group`() = runBlocking {
        store.activation = store.activation.copy(state = ActivationState.ACTIVE, selection = selection)
        val base = manifest()
        val source = object : ConfigurationService {
            override suspend fun resolve(pluginId: String, pluginVersion: String?, registryId: String?) = selection
            override suspend fun get(selection: PluginSelection) = PluginConfiguration(
                selection,
                base.copy(
                    backend = base.backend.copy(
                        events = base.backend.events.copy(
                            availableEventTypes = null,
                            availableEventGroups = setOf("participant"),
                        ),
                    ),
                ),
            )
        }
        val request = CheckEventPermissionRequest.newBuilder().setCallId("call")
            .setEventType(EventType.EVENT_TYPE_PARTICIPANT_JOINED).build()
        assertTrue(RoutesServiceImpl(store, source, secrets).getPermissionsForEvent(request).canSend)
    }

    @Test
    fun `permits configured event and media only for an active call`() = runBlocking {
        store.activation = store.activation.copy(state = ActivationState.ACTIVE, selection = selection)
        val event = CheckEventPermissionRequest.newBuilder().setCallId("call")
            .setEventType(EventType.EVENT_TYPE_PARTICIPANT_JOINED).build()
        val media = CheckMediaPermissionRequest.newBuilder().setCallId("call")
            .setMediaType(MediaType.MEDIA_TYPE_AUDIO).build()

        assertTrue(service.getPermissionsForEvent(event).canSend)
        val result = service.getPermissionsForMedia(media)
        assertTrue(result.canSend)
        assertEquals("1.0.0", result.getPlugins(0).pluginVersion)
        assertFalse(service.getPermissionsForEvent(event.toBuilder().setCallId("another").build()).canSend)
        val video = media.toBuilder().setMediaType(MediaType.MEDIA_TYPE_VIDEO).build()
        assertFalse(service.getPermissionsForMedia(video).canSend)
        store.activation = store.activation.copy(state = ActivationState.STOPPED)
        assertFalse(service.getPermissionsForMedia(media).canSend)
    }

    @Test
    fun `checks event and media for the requested activation`() = runBlocking {
        store.activation = store.activation.copy(state = ActivationState.ACTIVE, selection = selection)
        val activationId = store.activation.activationId.toString()
        val event = CheckEventPermissionRequest.newBuilder().setCallId("call")
            .setEventType(EventType.EVENT_TYPE_PARTICIPANT_JOINED).setActivationId(activationId)
            .setActivationSecret(secrets.issue("call", activationId)).build()
        val media = CheckMediaPermissionRequest.newBuilder().setCallId("call")
            .setMediaType(MediaType.MEDIA_TYPE_AUDIO).setActivationId(activationId)
            .setActivationSecret(secrets.issue("call", activationId)).build()

        assertTrue(service.getPermissionsForEvent(event).canSend)
        assertTrue(service.getPermissionsForMedia(media).canSend)
        assertFalse(service.getPermissionsForEvent(event.toBuilder().setActivationSecret("wrong").build()).canSend)
        assertFalse(service.getPermissionsForMedia(media.toBuilder().setActivationSecret("wrong").build()).canSend)
        assertFalse(service.getPermissionsForEvent(event.toBuilder().setActivationId("wrong").build()).canSend)
        assertFalse(service.getPermissionsForMedia(media.toBuilder().setActivationId("wrong").build()).canSend)
        store.activation = store.activation.copy(state = ActivationState.STOPPED)
        assertFalse(service.getPermissionsForEvent(event).canSend)
        assertFalse(service.getPermissionsForMedia(media).canSend)
    }

    @Test
    fun `allows only declared actions for active activation`() = runBlocking {
        store.activation = store.activation.copy(state = ActivationState.ACTIVE, selection = selection)
        val base = manifest()
        val source = object : ConfigurationService {
            override suspend fun resolve(pluginId: String, pluginVersion: String?, registryId: String?) = selection
            override suspend fun get(selection: PluginSelection) = PluginConfiguration(
                selection,
                base.copy(backend = base.backend.copy(actions = setOf("microphone.mute"))),
            )
        }
        val request = CheckActionPermissionRequest.newBuilder().setCallId("call")
            .setActivationId(store.activation.activationId.toString()).setAction("microphone.mute")
            .setActivationSecret(secrets.issue("call", store.activation.activationId.toString())).build()
        val service = RoutesServiceImpl(store, source, secrets)
        assertTrue(service.checkAction(request).allowed)
        assertFalse(service.checkAction(request.toBuilder().setActivationSecret("wrong").build()).allowed)
        assertFalse(service.checkAction(request.toBuilder().setAction("participant.remove").build()).allowed)
        assertFalse(service.checkAction(request.toBuilder().setCallId("other").build()).allowed)
        assertFalse(service.checkAction(request.toBuilder().setActivationId("").build()).allowed)
        store.activation = store.activation.copy(state = ActivationState.STOPPED)
        assertFalse(service.checkAction(request).allowed)
    }
}

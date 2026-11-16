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
    private val service = RoutesServiceImpl(store, configuration)

    @Test
    fun `explicit event types take precedence over event groups`() = runBlocking {
        store.activation = store.activation.copy(state = ActivationState.ACTIVE, selection = selection)
        val base = manifest()
        val events = base.backend.events.copy(
            availableEventTypes = setOf("participant.left"),
            availableEventGroups = setOf("participants"),
        )
        val restricted = base.copy(backend = base.backend.copy(events = events))
        val source = object : ConfigurationService {
            override suspend fun resolve(pluginId: String, pluginVersion: String?, registryId: String?) = selection
            override suspend fun get(selection: PluginSelection) = PluginConfiguration(selection, restricted)
        }
        val request = CheckEventPermissionRequest.newBuilder().setCallId("call")
            .setEventType(EventType.EVENT_TYPE_PARTICIPANT_JOINED).build()

        assertFalse(RoutesServiceImpl(store, source).getPermissionsForEvent(request).canSend)
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
}

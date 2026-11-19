package ru.itmo.pluginmanager.activation

import jakarta.ws.rs.POST
import jakarta.ws.rs.core.Response
import ru.itmo.pluginmanager.domain.PluginActivationMessage

/** REST-клиент POST по полному URL endpoint событий Plugin Backend. */
interface ActivationHttpEndpoint : AutoCloseable {
    @POST
    suspend fun activate(message: PluginActivationMessage): Response
}

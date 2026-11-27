package ru.itmo.pluginmanager.activation

import jakarta.ws.rs.POST
import jakarta.ws.rs.core.Response
import ru.itmo.pluginmanager.domain.PluginActivationMessage
import ru.itmo.pluginmanager.domain.PluginDeactivationMessage

/** REST-клиент команд по полному control-url Plugin Backend. */
interface ActivationHttpEndpoint : AutoCloseable {
    /** Отправляет контекст активации. */
    @POST
    suspend fun activate(message: PluginActivationMessage): Response

    /** Отправляет команду деактивации для конкретного звонка. */
    @POST
    suspend fun deactivate(message: PluginDeactivationMessage): Response
}

package ru.itmo.pluginmanager.health

import jakarta.ws.rs.GET
import jakarta.ws.rs.core.Response

/** REST-клиент GET-проверки по полному базовому URL плагина. */
interface ProbeHttpEndpoint : AutoCloseable {
    @GET
    suspend fun probe(): Response
}

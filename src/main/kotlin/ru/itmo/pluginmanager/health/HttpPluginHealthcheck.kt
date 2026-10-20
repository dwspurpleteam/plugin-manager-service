package ru.itmo.pluginmanager.health

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ProcessingException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.withTimeoutOrNull
import ru.itmo.pluginmanager.config.PluginManagerConfig
import ru.itmo.pluginmanager.domain.validateHttpUrl
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@ApplicationScoped
class HttpPluginHealthcheck(private val config: PluginManagerConfig) : PluginHealthcheck {
    override suspend fun check(url: String): Boolean {
        validateHttpUrl(url)
        return try {
            withTimeoutOrNull(config.health().timeout().toMillis().milliseconds) {
                QuarkusRestClientBuilder.newBuilder().baseUri(URI(url))
                    .connectTimeout(config.http().connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(config.health().timeout().toMillis(), TimeUnit.MILLISECONDS)
                    .followRedirects(false).property("microprofile.rest.client.disable.default.mapper", true)
                    .build(ProbeHttpEndpoint::class.java).use { client ->
                        client.probe().use { it.status == Response.Status.OK.statusCode }
                    }
            } ?: false
        } catch (_: ProcessingException) {
            false
        }
    }
}
